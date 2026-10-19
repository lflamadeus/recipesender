package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundContainerClient;
import com.lai.recipesender.client.NoticeOverlay;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 批量绑定模式的状态机。
 *
 * <p>只管「走到哪一步、框里有什么、按什么角色排」：渲染在 {@link BatchRenderer}，
 * 提示复用 {@link NoticeOverlay}，确认与提交在 {@link BatchBindScreen}，服务端在
 * {@code BatchBindService}。整条流程都在客户端——候选池、角色、配对都能即时算出来，
 * 只有玩家确认后才发一个包。
 *
 * <p>阶段：{@link Stage#IDLE} → 右键第一个方块 → {@link Stage#FIRST} → 右键第二个方块 →
 * {@link Stage#BOX}（可看、可剔除、可切角色）→ 回车进确认页。
 */
public final class BatchSelection {

    /** 流程阶段。 */
    public enum Stage {
        /** 没在批量绑定。 */
        IDLE,
        /** 第一个角点已定，等第二个。 */
        FIRST,
        /** 框已定，候选池可看可改，等玩家确认。 */
        BOX
    }

    /** 从容器模式下的配对：从容器坐标 → 目标组的成员坐标。 */
    public record Pair(BlockPos slave, BlockPos member) {
    }

    private static Stage stage = Stage.IDLE;
    private static ResourceKey<Level> dimension;
    private static BlockPos first;
    private static BlockPos second;
    /** 扫描结果（不含玩家的剔除），重新规划时始终从它出发。 */
    private static List<BatchCandidate> raw = List.of();
    /** 规划结果（含角色与拒绝原因），界面与渲染都读它。 */
    private static List<BatchCandidate> candidates = List.of();
    private static final Set<BlockPos> excluded = new HashSet<>();
    private static Direction.Axis primary = Direction.Axis.X;
    private static BoundContainer.Role targetRole = BoundContainer.Role.PARALLEL;
    private static BoundContainer.GroupMode groupMode = BoundContainer.GroupMode.INDEPENDENT;
    private static UUID targetGroupId;

    private BatchSelection() {
    }

    public static Stage stage() {
        return stage;
    }

    public static boolean active() {
        return stage != Stage.IDLE;
    }

    public static BlockPos first() {
        return first;
    }

    public static BlockPos second() {
        return second;
    }

    public static List<BatchCandidate> candidates() {
        return candidates;
    }

    public static Direction.Axis primary() {
        return primary;
    }

    public static BoundContainer.Role targetRole() {
        return targetRole;
    }

    public static BoundContainer.GroupMode groupMode() {
        return groupMode;
    }

    public static UUID targetGroupId() {
        return targetGroupId;
    }

    /** 第一个角点落下：进入批量绑定模式并开始绘制。 */
    public static void start(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        dimension = minecraft.level.dimension();
        first = pos.immutable();
        second = null;
        raw = List.of();
        candidates = List.of();
        excluded.clear();
        targetRole = BoundContainer.Role.PARALLEL;
        targetGroupId = null;
        stage = Stage.FIRST;
        notice("text.recipe_sender.batch_pick_second", "右键点第二个方块（每个方向最多 32 格）");
    }

    /**
     * 右键一个方块：按当前阶段推进。
     *
     * @param modifierHeld 是否按着 Shift + 绑定键（只有它会「重新开始」）
     */
    public static void click(BlockPos pos, boolean modifierHeld) {
        switch (stage) {
            case IDLE -> {
                if (modifierHeld) {
                    start(pos);
                }
            }
            case FIRST -> setSecond(pos);
            case BOX -> {
                if (modifierHeld) {
                    start(pos);
                }
            }
        }
    }

    /** 第二个角点：超范围就拒绝（不静默截断，否则玩家框的和拿到的不一样）。 */
    private static void setSecond(BlockPos pos) {
        if (first == null) {
            return;
        }
        if (!BatchScanner.withinLimit(first, pos)) {
            notice("text.recipe_sender.batch_too_large", "超出范围：每个方向最多 32 格，请重新点第二个角点");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        second = pos.immutable();
        raw = BatchScanner.scan(minecraft.level, dimension.location(), first, second);
        replan();
        stage = Stage.BOX;
        if (acceptedCount() == 0) {
            notice("text.recipe_sender.batch_empty", "框里没有可用的物品容器");
        } else {
            notice("text.recipe_sender.batch_box_ready", "已选 {0} 项：回车确认，左键剔除/恢复", acceptedCount());
        }
    }

    /** 左键点一个候选：剔除或恢复。 */
    public static void toggle(BlockPos pos) {
        if (stage != Stage.BOX || pos == null) {
            return;
        }
        if (!excluded.remove(pos)) {
            excluded.add(pos);
        }
        replan();
    }

    /** 切换「并列成员 / 从容器」。 */
    public static void setTargetRole(BoundContainer.Role role) {
        if (role == null || role == targetRole) {
            return;
        }
        targetRole = role;
        targetGroupId = role == BoundContainer.Role.SLAVE ? defaultGroupId() : null;
        replan();
    }

    /** 切换建组模式（只在并列成员模式下有意义）。 */
    public static void setGroupMode(BoundContainer.GroupMode mode) {
        groupMode = mode == null ? BoundContainer.GroupMode.INDEPENDENT : mode;
    }

    /** 指定从容器要挂靠的组。 */
    public static void setTargetGroup(UUID groupId) {
        targetGroupId = groupId;
    }

    /** 退出批量绑定（提交成功、换维度、断线都走这里）。 */
    public static void cancel() {
        stage = Stage.IDLE;
        dimension = null;
        first = null;
        second = null;
        raw = List.of();
        candidates = List.of();
        excluded.clear();
        targetRole = BoundContainer.Role.PARALLEL;
        targetGroupId = null;
    }

    /** 每客户端 tick 调一次：换维度就作废。 */
    public static void tick() {
        if (stage == Stage.IDLE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || dimension == null || !dimension.equals(minecraft.level.dimension())) {
            cancel();
        }
    }

    /** 可提交的项（角色已定、未被拒绝）。 */
    public static List<BatchCandidate> accepted() {
        List<BatchCandidate> accepted = new ArrayList<>();
        for (BatchCandidate candidate : candidates) {
            if (candidate.accepted()) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    public static int acceptedCount() {
        return accepted().size();
    }

    /** 主容器坐标（并列成员模式下第一个可用项）。 */
    public static BlockPos masterPos() {
        for (BatchCandidate candidate : candidates) {
            if (candidate.accepted() && candidate.role() == BoundContainer.Role.MASTER) {
                return candidate.pos();
            }
        }
        return null;
    }

    /** 从容器配对；数量对不上时返回空列表（界面据此提示「数量不匹配」）。 */
    public static List<Pair> pairing() {
        if (targetRole != BoundContainer.Role.SLAVE) {
            return List.of();
        }
        List<BoundContainer> members = targetMembers();
        List<BatchCandidate> slaves = accepted();
        if (members.isEmpty() || members.size() != slaves.size()) {
            return List.of();
        }
        List<Pair> pairs = new ArrayList<>(slaves.size());
        for (int index = 0; index < slaves.size(); index++) {
            pairs.add(new Pair(slaves.get(index).pos(), members.get(index).pos()));
        }
        return pairs;
    }

    /** 成员数与本次从容器数量相同的组（下拉只列这些）。 */
    public static List<BoundContainer> eligibleGroups() {
        int count = acceptedCount();
        if (targetRole != BoundContainer.Role.SLAVE || count < 1) {
            return List.of();
        }
        List<BatchCandidate> slaves = accepted();
        List<BoundContainer> groups = new ArrayList<>();
        for (BoundContainer master : BoundContainerClient.masters()) {
            if (BoundContainerClient.memberCount(master.id()) != count) {
                continue;
            }
            BoundGroupResolver.BoundGroup group = BoundContainerClient.groupOf(master.id());
            if (group != null && sameKind(group, slaves.get(0))) {
                groups.add(master);
            }
        }
        return groups;
    }

    private static UUID defaultGroupId() {
        List<BoundContainer> groups = eligibleGroups();
        return groups.isEmpty() ? null : groups.get(0).id();
    }

    private static List<BoundContainer> targetMembers() {
        if (targetGroupId == null) {
            return List.of();
        }
        BoundGroupResolver.BoundGroup group = BoundContainerClient.groupOf(targetGroupId);
        if (group == null) {
            return List.of();
        }
        List<BoundContainer> members = new ArrayList<>(group.members());
        members.sort(Comparator.comparingInt(member -> BatchPlanner.along(member.pos(), primary)));
        return members;
    }

    private static boolean sameKind(BoundGroupResolver.BoundGroup group, BatchCandidate slave) {
        for (BoundContainer member : group.members()) {
            if (!BatchPlanner.sameKind(member.iconItem(), slave.icon())) {
                return false;
            }
        }
        return true;
    }

    /** 从扫描结果重新规划：应用剔除 → 排序 → 角色。 */
    private static void replan() {
        List<BatchCandidate> applied = new ArrayList<>(raw.size());
        for (BatchCandidate candidate : raw) {
            applied.add(excluded.contains(candidate.pos())
                    ? candidate.withReject(BatchCandidate.Reject.EXCLUDED) : candidate);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Direction.Axis fallback = minecraft.player == null
                ? Direction.Axis.X : minecraft.player.getDirection().getAxis();
        primary = BatchPlanner.primaryAxis(applied, fallback);
        List<BatchCandidate> ordered = BatchPlanner.order(applied, primary);
        candidates = targetRole == BoundContainer.Role.SLAVE
                ? BatchPlanner.planSlaves(ordered) : BatchPlanner.planMembers(ordered);
        if (targetRole == BoundContainer.Role.SLAVE && targetGroupId == null) {
            targetGroupId = defaultGroupId();
        }
    }

    private static void notice(String key, String fallback, Object... args) {
        NoticeOverlay.show(Component.translatableWithFallback(key, fallback, args), NoticeSeverity.INFO);
    }
}
