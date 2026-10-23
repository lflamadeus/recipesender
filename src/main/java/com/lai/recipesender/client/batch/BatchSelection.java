package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundContainerClient;
import com.lai.recipesender.client.MachineRoutes;
import com.lai.recipesender.client.NoticeOverlay;
import com.lai.recipesender.client.RecipeSenderClient;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BatchBindPacket;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 批量绑定模式的状态机。
 *
 * <p>只管「走到哪一步、池里有什么、怎么分组配对、怎么提交」：渲染在 {@link BatchRenderer}，
 * 提示复用 {@link NoticeOverlay}（常驻通道），服务端在 {@code BatchBindService}。
 * 整条流程都在客户端——候选池、分组、配对都能即时算出来，全部配好之后才发一个包。
 *
 * <p>阶段：{@link Stage#IDLE} → Shift+绑定键+左键（或右键）点起点 → {@link Stage#FIRST}
 * → Shift+右键点另一个角点出框 → {@link Stage#BOX}（可看、可补一台、可剔除）→ 回车提交。
 * <b>没有提交前确认页</b>：关系在世界里用色系和连线表达，页面只会让人对着坐标猜。
 *
 * <p><b>绑定键按着 = 改角点，没按 = 改池子</b>：Shift+绑定键+左键 任何时候都是重定起点、
 * Shift+绑定键+右键 任何时候都是重定角点（保留已剔除的与手动主向）；不按绑定键时
 * Shift+左键 = 移除、Shift+右键 = 补一台。
 *
 * <p><b>主向</b>默认取框选范围最长边；玩家可以「按住绑定键 + P」把它定成自己正对着的水平轴，
 * 定过一次就锁定，退出模式时恢复自动。主向同时决定配对怎么分片和排序（排序第一个当主容器）。
 *
 * <p><b>模式不会自己结束</b>：松开任何键都不退出，只有「对空气按绑定键」、ESC、提交成功
 * 或换维度才结束。所以退出方式必须一直挂在常驻提示上。
 */
public final class BatchSelection {

    /** 流程阶段。 */
    public enum Stage {
        /** 没在批量绑定。 */
        IDLE,
        /** 起点已定，等另一个角点。 */
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
    /** 扫描结果（不含玩家的剔除），重新规划时始终从它出发。可变：BOX 阶段的「补一台」要往里追加。 */
    private static List<BatchCandidate> raw = new ArrayList<>();
    /** 规划结果（含角色、组号、配对与拒绝原因），界面与渲染都读它。 */
    private static List<BatchCandidate> candidates = List.of();
    private static final Set<BlockPos> excluded = new HashSet<>();
    private static Direction.Axis primary = Direction.Axis.X;
    /** 玩家手动切过主向：锁定，不再跟随最长边。 */
    private static boolean primaryLocked;
    /** 整池没有机器：退回「从容器挂已有组」的老办法。 */
    private static boolean slaveMode;
    /** 主向坐标 → 这一片有配不起来的方块。渲染高亮用。 */
    private static Set<Integer> ambiguousSlices = Set.of();
    private static UUID targetGroupId;
    /** 已经发出去、正在等服务端回执：防止连按两下回车重复提交。 */
    private static boolean submitting;

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

    /** 池里这个坐标的候选（含已被剔除的）；不在池里返回 {@code null}。渲染准星预览用。 */
    public static BatchCandidate candidateAt(BlockPos pos) {
        for (BatchCandidate candidate : candidates) {
            if (candidate.pos().equals(pos)) {
                return candidate;
            }
        }
        return null;
    }

    public static Direction.Axis primary() {
        return primary;
    }

    /** 主向的显示名（X / Y / Z）。 */
    public static String primaryName() {
        return primary.getName().toUpperCase(java.util.Locale.ROOT);
    }

    /** 整池没有机器、退回「挂已有组」时为 {@code true}。 */
    public static boolean slaveMode() {
        return slaveMode;
    }

    public static UUID targetGroupId() {
        return targetGroupId;
    }

    /** 这一片（主向坐标）配不起来：渲染用琥珀色标整片。 */
    public static boolean ambiguous(int slice) {
        return ambiguousSlices.contains(slice);
    }

    /** 这个方块所在的那一片配不起来。 */
    public static boolean ambiguousAt(BlockPos pos) {
        return pos != null && ambiguousSlices.contains(BatchPlanner.along(pos, primary));
    }

    /**
     * 提交整份计划。
     *
     * <p><b>没有提交前确认页</b>：规则已经保证「不配对不许提交」，玩家真正需要看的是**有没有异常**，
     * 而异常已经在世界里用琥珀色标出来了、也会在这里被拒绝并说清是哪一片。多一道核对，
     * 只是让玩家为「万一」付摩擦。
     *
     * <p>主容器会带上按 EMI 登记算出来的默认配方类别（见 {@link MachineRoutes}）：服务端没有 EMI，
     * 算不出来，不带的话绑出来的组永远收不到配方。
     */
    public static void submit() {
        if (submitting) {
            return;
        }
        if (!submitReady()) {
            int unpaired = unpairedCount();
            if (unpaired > 0) {
                NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_blocked",
                                "还有 %s 个方块配不起来，无法提交：剔掉多余的，或退出重来", unpaired),
                        NoticeSeverity.ERROR);
                return;
            }
            NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_empty",
                    "框里没有可用的物品容器"), NoticeSeverity.ERROR);
            return;
        }

        List<BatchBindPacket.Entry> entries = new ArrayList<>();
        int noRoutes = 0;
        if (slaveMode) {
            for (Pair pair : pairing()) {
                entries.add(new BatchBindPacket.Entry(pair.slave(), BoundContainer.Role.SLAVE, pair.member()));
            }
        } else {
            for (BatchCandidate candidate : accepted()) {
                Set<ResourceLocation> routes = candidate.role() == BoundContainer.Role.MASTER
                        ? MachineRoutes.defaultFor(candidate.icon()) : Set.of();
                if (candidate.role() == BoundContainer.Role.MASTER && routes.isEmpty()) {
                    noRoutes++;
                }
                entries.add(new BatchBindPacket.Entry(candidate.pos(), candidate.role(), candidate.parent(), routes));
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation dimension = minecraft.level != null ? minecraft.level.dimension().location() : null;
        if (dimension == null || entries.isEmpty()) {
            return;
        }
        submitting = true;
        // 建组模式一律独立：整体模式在批量场景里没有意义（各组本来就不一样），
        // 真需要整体就去管理面板单独改。
        ModNetwork.CHANNEL.sendToServer(new BatchBindPacket(UUID.randomUUID(), dimension,
                first, second, BoundContainer.GroupMode.INDEPENDENT, entries));
        if (noRoutes > 0 && minecraft.player != null) {
            // 不拦提交：类别算不出来照样能绑，只是这台不参与自动路由。走聊天栏而不是浮层——
            // 服务端的回执马上就到，浮层会被那条回执顶掉，等于没提示。
            minecraft.player.displayClientMessage(Component.translatableWithFallback(
                    "text.recipe_sender.batch_no_routes",
                    "%s 台主容器没算出配方类别，不会参与自动路由（可到管理面板补）", noRoutes), false);
        }
    }

    /** 组数（按已分组的机器算）。 */
    public static int groupCount() {
        Set<Integer> groups = new HashSet<>();
        for (BatchCandidate candidate : candidates) {
            if (candidate.accepted() && candidate.group() > 0) {
                groups.add(candidate.group());
            }
        }
        return groups.size();
    }

    /** 未配对的方块数。 */
    public static int unpairedCount() {
        int count = 0;
        for (BatchCandidate candidate : candidates) {
            if (candidate.reject() == BatchCandidate.Reject.UNPAIRED) {
                count++;
            }
        }
        return count;
    }

    /** 配不起来的那些片的主向坐标（去重、按升序），用来在提示里说清是哪几片。 */
    public static List<Integer> unpairedSlices() {
        Set<Integer> slices = new LinkedHashSet<>();
        List<BatchCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(candidate -> BatchPlanner.along(candidate.pos(), primary)));
        for (BatchCandidate candidate : sorted) {
            if (candidate.reject() == BatchCandidate.Reject.UNPAIRED) {
                slices.add(BatchPlanner.along(candidate.pos(), primary));
            }
        }
        return new ArrayList<>(slices);
    }

    /**
     * 现在能不能提交。
     *
     * <p>规则是「**可以不绑，不能绑错**」：只要还有一处配不起来，整单就不许提交，
     * 而不是把配不上的悄悄丢掉。
     */
    public static boolean submitReady() {
        if (acceptedCount() == 0) {
            return false;
        }
        if (unpairedCount() > 0) {
            return false;
        }
        if (slaveMode) {
            return !pairing().isEmpty();
        }
        return groupCount() > 0;
    }

    /**
     * 起点落下：进入批量绑定模式并开始绘制；已经在 FIRST 阶段时就是「重定起点」。
     *
     * <p>左键、右键都能定起点（右键是兜底：一个键连点两下也能完成框选），所以这里不区分来源。
     */
    public static void cornerA(BlockPos pos) {
        cornerA(pos, false);
    }

    /**
     * 重定起点。
     *
     * @param keepState 为 {@code true} 时**保留**另一个角点、已剔除的方块和手动锁定的主向，
     *                  直接把框重算一遍。改角点是修正而不是重来：玩家刚剔掉几台多余的、
     *                  又想把起点往外挪一格，不该让刚才的剔除白做，也不该逼他再点一次终点。
     */
    public static void cornerA(BlockPos pos, boolean keepState) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || pos == null) {
            return;
        }
        boolean restart = stage == Stage.FIRST;
        if (keepState && second != null) {
            if (!BatchScanner.withinLimit(pos, second)) {
                notice("text.recipe_sender.batch_too_large", "超出范围：每个方向最多 32 格");
                return;
            }
            dimension = minecraft.level.dimension();
            first = pos.immutable();
            raw = BatchScanner.scan(minecraft.level, dimension.location(), first, second);
            // 组内数量可能变了（纯容器池按数量挑组），目标组跟着重算。
            targetGroupId = null;
            replan();
            stage = Stage.BOX;
            notice("text.recipe_sender.batch_corner_moved",
                    "起点已重定（{0} 项），已重新配对", acceptedCount());
            announceProblems();
            return;
        }
        dimension = minecraft.level.dimension();
        first = pos.immutable();
        second = null;
        raw = new ArrayList<>();
        candidates = List.of();
        excluded.clear();
        // 新框 = 新的主向判定：上一轮手动锁的轴不该跟过来。
        primaryLocked = false;
        slaveMode = false;
        ambiguousSlices = Set.of();
        targetGroupId = null;
        stage = Stage.FIRST;
        notice(restart ? "text.recipe_sender.batch_first_moved" : "text.recipe_sender.batch_pick_second",
                restart ? "起点已重定：Shift+右键 定另一个角点"
                        : "已定起点：Shift+右键 定另一个角点（每方向 ≤ 32 格）");
    }

    /** 另一个角点：超范围就拒绝（不静默截断，否则玩家框的和拿到的不一样）。 */
    public static void cornerB(BlockPos pos) {
        if (first == null) {
            return;
        }
        if (!BatchScanner.withinLimit(first, pos)) {
            notice("text.recipe_sender.batch_too_large", "超出范围：每个方向最多 32 格，Shift+左键 可重定起点");
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
            return;
        }
        notice("text.recipe_sender.batch_box_ready",
                "已选 {0} 项：Shift+右键 加一台，Shift+左键 移除", acceptedCount());
        announceProblems();
    }

    /**
     * 定主向（按住绑定键 + P）：取玩家**正对着的那条水平轴**，不循环。
     *
     * <p>以前是 X → Z → Y 循环，问题是玩家得先知道自己想要哪个轴，那就得按 F3 去看坐标。
     * 改成「按你面朝的方向」之后，<b>朝着那排机器延伸的方向看</b>按一下就行，不用看任何数字。
     * 注意主向是切片轴：一排机器沿 X 排开时，人也要朝 X 看（站在排的端头），
     * 正对着机器站（朝 Z 看）会把整排切进同一片——那是站位问题，不是规则问题。
     *
     * <p>{@code getYRot()} 只是水平角（抬头低头不影响它），所以结果永远落在 X 或 Z；
     * 竖直堆叠那种排布交给自动模式（最长边）判断。
     *
     * <p>定过就锁定，之后剔除/补一台重算都不会跳回「最长边」——否则玩家会觉得按了没用。
     * 再按一次会重新读当前朝向，所以想换方向就转身再按，不需要循环。
     */
    public static void setPrimaryFromFacing() {
        if (!active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Direction.Axis axis = Direction.fromYRot(minecraft.player.getYRot()).getAxis();
        primary = axis;
        primaryLocked = true;
        if (stage == Stage.BOX) {
            replan();
        }
        notice("text.recipe_sender.batch_primary", "主向：{0}（按你面朝的方向）", primaryName());
        if (stage == Stage.BOX) {
            announceProblems();
        }
    }

    /**
     * 世界里的 Shift+左键：把这一台移出候选池。
     *
     * <p>不在池里就<b>什么都不做、也不提示</b>——玩家往往是在一排机器上顺手划，
     * 点到框外的空气或别的方块很正常；但输入层照样会吞掉这一下，避免把机器挖了。
     */
    public static void remove(BlockPos pos) {
        if (stage != Stage.BOX || pos == null) {
            return;
        }
        if (raw.stream().noneMatch(candidate -> candidate.pos().equals(pos))) {
            return;
        }
        if (!excluded.add(pos)) {
            return;
        }
        replan();
        notice("text.recipe_sender.batch_removed", "已移除（共 {0} 台）", acceptedCount());
        announceProblems();
    }

    /**
     * BOX 阶段的 Shift+右键：把一台容器补进候选池（框选是主路径，这是补充调整）。
     *
     * <p>校验口径与 {@link BatchScanner} 完全一致，免得补进来的东西等到提交才发现用不了。
     * 不再做「与主容器同类」的判定：不同类会自己成为一个新组。
     */
    public static void addSingle(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BatchCandidate existing = find(pos);
        if (existing != null) {
            if (excluded.remove(pos)) {
                replan();
                notice("text.recipe_sender.batch_added", "已添加（共 {0} 台）", acceptedCount());
                announceProblems();
            } else {
                notice("text.recipe_sender.batch_already", "已在批量绑定中");
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || dimension == null) {
            return;
        }
        // 没有方块实体的方块（石头、泥土、墙面）不值得报错：模式内 Shift+右键 很容易点到
        // 机器旁边的墙，每次都闪一条红字只会让人以为出问题了。真正「像机器却没有物品容器」
        // 的才提示——那才是玩家会困惑的情况。
        if (minecraft.level.getBlockEntity(pos) == null) {
            return;
        }
        BatchCandidate scanned = BatchScanner.classify(minecraft.level, dimension.location(), pos);
        if (scanned == null) {
            error("text.recipe_sender.batch_reject_container", "这个方块不是物品容器");
            return;
        }
        switch (scanned.reject()) {
            case ALREADY_BOUND -> {
                warn("text.recipe_sender.batch_reject_bound", "已绑定");
                return;
            }
            case HUB -> {
                warn("text.recipe_sender.batch_reject_hub", "是中枢");
                return;
            }
            default -> {
            }
        }
        raw.add(scanned);
        replan();
        notice("text.recipe_sender.batch_added", "已添加（共 {0} 台）", acceptedCount());
        announceProblems();
    }

    private static BatchCandidate find(BlockPos pos) {
        for (BatchCandidate candidate : raw) {
            if (candidate.pos().equals(pos)) {
                return candidate;
            }
        }
        return null;
    }

    /** 指定从容器要挂靠的组（只在纯箱子池里有意义）。 */
    public static void setTargetGroup(UUID groupId) {
        targetGroupId = groupId;
    }

    /** 静默清空（提交成功、换维度、断线都走这里；玩家的主动退出用 {@link #exit()}）。 */
    public static void cancel() {
        stage = Stage.IDLE;
        dimension = null;
        first = null;
        second = null;
        raw = new ArrayList<>();
        candidates = List.of();
        excluded.clear();
        primaryLocked = false;
        slaveMode = false;
        ambiguousSlices = Set.of();
        targetGroupId = null;
        submitting = false;
        // 常驻提示的生命周期绑在模式上：状态没了提示必须跟着消失，否则会一直挂在屏幕上。
        NoticeOverlay.clearSticky();
    }

    /** 玩家主动退出（退出键 / ESC）：清状态并给一条回执。 */
    public static void exit() {
        if (!active()) {
            return;
        }
        cancel();
        notice("text.recipe_sender.batch_exit", "已退出批量绑定");
    }

    /**
     * 每客户端 tick 调一次：换维度就作废、被拆掉的方块剔出池、刷新常驻提示。
     *
     * <p>常驻提示每 tick 重推一次内容：格数、主向、配对随时在变，与其在每个改动点手动刷新，
     * 不如让 tick 做唯一出口——{@link NoticeOverlay#showSticky} 是幂等的。
     *
     * <p>配不起来的提示**不在这里发**：它只在状态变化时说一次，每 tick 发会变成永动机。
     */
    public static void tick() {
        if (stage == Stage.IDLE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || dimension == null || !dimension.equals(minecraft.level.dimension())) {
            cancel();
            return;
        }
        pruneVanished(minecraft.level);
        NoticeOverlay.showSticky(banner(), NoticeSeverity.INFO);
    }

    /**
     * 方块实体没了（被拆、被替换）就从池里剔掉。
     *
     * <p>区块<b>未加载</b>的保留——那不是「没了」，玩家只是走远了；按未加载剔掉会让
     * 框选结果在走两步之后自己缩水。
     */
    private static void pruneVanished(Level level) {
        if (raw.isEmpty()) {
            return;
        }
        boolean changed = raw.removeIf(candidate -> level.hasChunkAt(candidate.pos())
                && level.getBlockEntity(candidate.pos()) == null);
        if (changed) {
            replan();
        }
    }

    /**
     * 常驻提示的三行文案：状态 / 主向 / 手势与退出。
     *
     * <p>退出键和绑定键的显示名都现取：玩家在控制设置里改过之后，提示要跟着变，否则
     * 屏幕上写的还是旧键，比不写还误导。
     */
    public static Component banner() {
        String bindName = RecipeSenderClient.boundKeyName();
        String exitName = RecipeSenderClient.batchExitKeyName();
        String axisHint = Component.translatableWithFallback("text.recipe_sender.batch_primary_hint",
                "主向：{0}（按住 {1} 按 P 取面朝方向）", primaryName(), bindName).getString();
        if (stage == Stage.FIRST) {
            BlockPos corner = first == null ? BlockPos.ZERO : first;
            return Component.translatableWithFallback("text.recipe_sender.batch_banner_first",
                    "批量绑定 · 起点 ({0}, {1}, {2})\n{3}\nShift+右键 定另一个角点 · {4} 退出",
                    corner.getX(), corner.getY(), corner.getZ(), axisHint, exitName);
        }
        int unpaired = unpairedCount();
        if (slaveMode && acceptedCount() > 0) {
            // 纯容器池没有机器可以配，只能挂到已有的组：选的是哪个组必须写出来，
            // 否则玩家提交完才发现挂错了地方。
            BoundContainer target = targetGroup();
            String targetName = target == null
                    ? Component.translatableWithFallback("text.recipe_sender.batch_no_group",
                    "没有数量匹配的组").getString()
                    : BoundContainerClient.displayName(target);
            return Component.translatableWithFallback("text.recipe_sender.batch_banner_slave",
                    "批量绑定 · 纯容器池 · 挂到「{0}」\n{1}\nShift+右键 加一台 · Shift+左键 移除 · 回车 提交 · {2} 退出",
                    targetName, axisHint, exitName);
        }
        String state = unpaired > 0
                ? Component.translatableWithFallback("text.recipe_sender.batch_banner_unpaired",
                "批量绑定 · {0} 组 · 未配对 {1}", groupCount(), unpaired).getString()
                : Component.translatableWithFallback("text.recipe_sender.batch_banner_groups",
                "批量绑定 · {0} 组 · 已选 {1} 台", groupCount(), acceptedCount()).getString();
        return Component.translatableWithFallback("text.recipe_sender.batch_banner_box",
                "{0}\n{1}\nShift+右键 加一台 · Shift+左键 移除 · 回车 提交 · {2} 退出",
                state, axisHint, exitName);
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

    /** 主容器坐标（第一组的第一个可用项）。 */
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
        if (!slaveMode) {
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
        if (!slaveMode || count < 1) {
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

    /**
     * 纯容器池的目标组：**离框最近**的那个数量匹配的组。
     *
     * <p>以前是在确认页上点，现在没有确认页了。按距离取是唯一不需要玩家再做一次选择、
     * 又几乎总是对的办法（箱子就摆在机器旁边）。选中的组会写进常驻提示，提交前看得见。
     */
    private static UUID defaultGroupId() {
        List<BoundContainer> groups = eligibleGroups();
        if (groups.isEmpty()) {
            return null;
        }
        BlockPos center = frameCenter();
        if (center == null) {
            return groups.get(0).id();
        }
        BoundContainer nearest = groups.get(0);
        double best = Double.MAX_VALUE;
        for (BoundContainer group : groups) {
            double distance = group.pos().distSqr(center);
            if (distance < best) {
                best = distance;
                nearest = group;
            }
        }
        return nearest.id();
    }

    /** 框的中心，用来量「哪一组离得最近」。 */
    private static BlockPos frameCenter() {
        if (first == null) {
            return null;
        }
        if (second == null) {
            return first;
        }
        return new BlockPos((first.getX() + second.getX()) / 2,
                (first.getY() + second.getY()) / 2,
                (first.getZ() + second.getZ()) / 2);
    }

    /** 纯容器池当前要挂的那一组（常驻提示要用它的名字）。 */
    private static BoundContainer targetGroup() {
        if (targetGroupId == null) {
            return null;
        }
        for (BoundContainer master : BoundContainerClient.masters()) {
            if (master.id().equals(targetGroupId)) {
                return master;
            }
        }
        return null;
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

    /** 从扫描结果重新规划：应用剔除 → 定主向 → 排序 → 分组配对 → 编号。 */
    private static void replan() {
        List<BatchCandidate> applied = new ArrayList<>(raw.size());
        for (BatchCandidate candidate : raw) {
            applied.add(excluded.contains(candidate.pos())
                    ? candidate.withReject(BatchCandidate.Reject.EXCLUDED) : candidate);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Direction.Axis fallback = minecraft.player == null
                ? Direction.Axis.X : minecraft.player.getDirection().getAxis();
        if (!primaryLocked) {
            primary = BatchPlanner.primaryAxis(applied, fallback);
        }
        List<BatchCandidate> ordered = BatchPlanner.order(applied, primary);
        candidates = BatchPlanner.plan(ordered, primary);
        slaveMode = isSlaveMode();
        if (slaveMode && targetGroupId == null) {
            targetGroupId = defaultGroupId();
        }
        computeAmbiguous();
    }

    /** 规划结果里有没有机器（有机器就不是纯箱子池）。 */
    private static boolean isSlaveMode() {
        for (BatchCandidate candidate : candidates) {
            if (candidate.accepted() && candidate.machine()) {
                return false;
            }
        }
        return true;
    }

    /** 哪些片里有配不起来的方块（整片高亮用）。 */
    private static void computeAmbiguous() {
        Set<Integer> slices = new HashSet<>();
        for (BatchCandidate candidate : candidates) {
            if (candidate.reject() == BatchCandidate.Reject.UNPAIRED) {
                slices.add(BatchPlanner.along(candidate.pos(), primary));
            }
        }
        ambiguousSlices = slices;
    }

    /**
     * 把「现在配不起来」说一次。
     *
     * <p>只在状态变化时调（进框、切主向、剔除、补一台），不要放进 {@link #tick()}：
     * 那样每 tick 都会刷一条，玩家永远看不清。
     */
    private static void announceProblems() {
        if (stage != Stage.BOX) {
            return;
        }
        int unpaired = unpairedCount();
        if (unpaired > 0) {
            List<Integer> slices = unpairedSlices();
            StringBuilder detail = new StringBuilder();
            for (int index = 0; index < slices.size() && index < 3; index++) {
                if (index > 0) {
                    detail.append(", ");
                }
                detail.append(slices.get(index));
            }
            if (slices.size() > 3) {
                detail.append(" …");
            }
            warn("text.recipe_sender.batch_unpaired_notice",
                    "{0} 个方块配不起来（主向坐标 {1}）：同一片里必须恰好一台机器 + 一个容器",
                    unpaired, detail.toString());
            return;
        }
        if (slaveMode && pairing().isEmpty()) {
            warn("text.recipe_sender.batch_pair_mismatch", "数量与目标组不匹配，无法配对");
        }
    }

    private static void notice(String key, String fallback, Object... args) {
        NoticeOverlay.show(Component.translatableWithFallback(key, fallback, args), NoticeSeverity.INFO);
    }

    /** 能用但不是玩家想要的（已绑定、配不起来）：黄条，别用红色吓人。 */
    private static void warn(String key, String fallback, Object... args) {
        NoticeOverlay.show(Component.translatableWithFallback(key, fallback, args), NoticeSeverity.WARN);
    }

    /** 这个方块根本不是容器：红条。 */
    private static void error(String key, String fallback, Object... args) {
        NoticeOverlay.show(Component.translatableWithFallback(key, fallback, args), NoticeSeverity.ERROR);
    }
}
