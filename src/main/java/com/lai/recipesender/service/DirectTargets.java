package com.lai.recipesender.service;

import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.integration.gt.MachineStateProbe;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.MachineState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次发送的落点集合：主容器、并列成员、每个成员自己的从容器、组级从容器，外加忙闲探测结果。
 *
 * <p>落点原来是一组平行表（{@code members} / {@code memberTargets} / {@code memberNames} /
 * {@code memberOverflow} / {@code memberFluidHandlers}），全靠下标手工对齐：机器被跳过时要记得
 * 从每张表里同步摘掉同一项，分块轮换时要记得把每张表转同样的偏移。少摘一次、漏转一张，
 * 材料就会进别人的箱子，而且没有任何编译期提示。这里把它们收成一个 {@link Member}：
 * <b>一台机器一行</b>，跳过、自己的从容器、流体能力都跟着它走，过滤与轮换只作用在一个列表上，
 * 其余两张表由它派生。
 *
 * <p>包内可见：{@link DirectInsertionService} 与 {@link BoundInsertionService} 是同一套落点语义，
 * 必须共用这里的解析，否则同一只箱子在两条路径上的可用性判定会漂移。
 */
final class DirectTargets {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private DirectTargets() {
    }

    /**
     * 一个落点成员。
     *
     * @param target      主落点
     * @param ownOverflow 这一台<b>自己的</b>从容器（独立模式下给单台扩容的箱子），整体模式下为空
     * @param name        绑定名，整体模式电路不一致时要靠它点名是哪一台
     * @param skipped     在忙且是独立模式，这一次整个跳过：不投料、不改电路、不收尾
     * @param fluids      流体能力，没开流体支持或这台没有流体仓时为 {@code null}
     */
    record Member(BoundTargetResolver.ResolvedTarget target,
                  List<BoundTargetResolver.ResolvedTarget> ownOverflow,
                  String name,
                  boolean skipped,
                  IFluidHandler fluids) {

        RemoteTransferPlanner.Target slot() {
            return target.toTarget();
        }

        BlockEntity blockEntity() {
            return target.blockEntity();
        }
    }

    /**
     * 整组的落点。
     *
     * @param members       主容器 + 可用的并列成员（含被跳过的那些，用 {@link Member#skipped()} 区分）
     * @param groupOverflow 组级从容器：整体模式下是整组的溢出缓冲，独立模式下为空
     * @param unified       整体模式
     * @param busy          整体模式下正在运行的成员，交给电路闸门判定
     */
    record TargetSet(List<Member> members,
                     List<BoundTargetResolver.ResolvedTarget> groupOverflow,
                     boolean unified,
                     List<GroupCircuitGate.Member> busy,
                     Set<BlockPos> positions) {

        /** 这一次真正接料的成员。 */
        List<Member> active() {
            List<Member> active = new ArrayList<>(members.size());
            for (Member member : members) {
                if (!member.skipped()) {
                    active.add(member);
                }
            }
            return active;
        }

        /** 因为正在运行而被跳过的成员数。 */
        int skipped() {
            int skipped = 0;
            for (Member member : members) {
                if (member.skipped()) {
                    skipped++;
                }
            }
            return skipped;
        }

        /**
         * 收尾时要 {@code setChanged()} 的全部目标。
         *
         * <p>被跳过的成员自己不参与收尾，但它<b>自己的从容器</b>照旧收尾：那只是让方块实体
         * 正常参与保存与比较，不涉及材料归属。
         */
        List<BoundTargetResolver.ResolvedTarget> allResolved() {
            List<BoundTargetResolver.ResolvedTarget> all =
                    new ArrayList<>(members.size() + groupOverflow.size());
            for (Member member : members) {
                if (!member.skipped()) {
                    all.add(member.target());
                }
                all.addAll(member.ownOverflow());
            }
            all.addAll(groupOverflow);
            return all;
        }

        List<RemoteTransferPlanner.Target> groupOverflowSlots() {
            return slotsOf(groupOverflow);
        }
    }

    /**
     * 解析整组落点并探完忙闲。
     *
     * <p>忙闲两种模式的用法完全不同：
     * <ul>
     *   <li><b>独立模式</b>只往空闲机器投料、只给空闲机器调电路——机器跑到一半被改电路，
     *       手上那一炉就会按新电路重新匹配。正在跑的成员这一次整个跳过，等它跑完下一次自然用上。</li>
     *   <li><b>整体模式</b>把整组当一台大机器，在忙的也接料（材料由玩家自己铺的路径从从容器补进组内），
     *       代价是它们的电路必须已经等于本次电路，否则整单取消（由调用方过闸门）。</li>
     * </ul>
     *
     * <p>两种模式都只过滤主容器与并列成员：从容器是容量备份（通常是箱子），本来就没有配方逻辑。
     *
     * @param probeFluids 是否顺带探测流体能力；只有这一次真的要搬流体时才为真
     */
    static TargetSet resolve(ServerPlayer player, BoundGroupResolver.BoundGroup group,
                             BoundTargetResolver.ResolvedTarget master, boolean probeFluids) {
        boolean unified = group.unified();
        List<Member> members = new ArrayList<>();
        List<GroupCircuitGate.Member> busy = new ArrayList<>();
        members.add(member(player, group, group.master(), master, unified, probeFluids, busy));
        for (BoundContainer parallel : group.parallels()) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, parallel);
            if (!target.available()) {
                LOGGER.debug("并列成员「{}」当前不可用（{}），本次均分跳过", parallel.name(), target.status());
                continue;
            }
            members.add(member(player, group, parallel, target, unified, probeFluids, busy));
        }
        List<BoundTargetResolver.ResolvedTarget> groupOverflow = new ArrayList<>();
        // 组级从容器（挂在主容器下的那些）：整体模式下它们就是整组的溢出缓冲，材料由玩家自己
        // 铺的路径补进组内；独立模式下 effectiveSlaves() 返回空——那时每个成员的余量由它<b>自己</b>
        // 的从容器接住，组级从容器不再是落点。
        for (BoundContainer slave : group.effectiveSlaves()) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
            if (!target.available()) {
                LOGGER.debug("从容器「{}」当前不可用（{}），本次溢出跳过", slave.name(), target.status());
                continue;
            }
            groupOverflow.add(target);
        }
        return new TargetSet(List.copyOf(members), List.copyOf(groupOverflow),
                unified, List.copyOf(busy), positionsOf(group));
    }

    /**
     * 目标组自身占据的坐标，统计与抽取时都要排除。
     *
     * <p>排除的是整组而不只是主容器：并列成员和从容器同样是落点，从它们里面掏材料再放回去，
     * 净效果为零，却会打乱目标里已经分好的份数。
     *
     * <p>独立模式下组级从容器不参与（{@link BoundGroupResolver.BoundGroup#effectiveSlaves()} 返回空），
     * 它们此刻只是普通箱子，里面的材料是可以用的来源；而每个成员<b>自己</b>的从容器仍然是落点，
     * 必须排除——那正是 {@code effectiveMemberSlaves} 给出的那批。
     */
    private static Set<BlockPos> positionsOf(BoundGroupResolver.BoundGroup group) {
        Set<BlockPos> positions = new HashSet<>();
        positions.add(group.master().pos());
        for (BoundContainer parallel : group.parallels()) {
            positions.add(parallel.pos());
        }
        for (BoundContainer slave : group.effectiveSlaves()) {
            positions.add(slave.pos());
        }
        // 独立模式下的成员私有从容器不在 effectiveSlaves() 里（那时它返回空），但它同样是落点，
        // 漏掉它就会从「这一台的扩容箱」里掏出材料再放回去，白折腾还可能打乱份数。
        for (BoundContainer member : group.members()) {
            for (BoundContainer slave : group.effectiveMemberSlaves(member)) {
                positions.add(slave.pos());
            }
        }
        return Set.copyOf(positions);
    }

    private static Member member(ServerPlayer player, BoundGroupResolver.BoundGroup group,
                                 BoundContainer container, BoundTargetResolver.ResolvedTarget target,
                                 boolean unified, boolean probeFluids,
                                 List<GroupCircuitGate.Member> busy) {
        List<BoundTargetResolver.ResolvedTarget> ownOverflow =
                ownOverflowOf(player, group, container);
        boolean running = MachineStateProbe.stateOf(target.blockEntity()) == MachineState.BUSY;
        if (running && unified) {
            // 在忙也接料：记下来交给闸门判定电路，落点表里留着它。
            busy.add(new GroupCircuitGate.Member(container.name(), target.blockEntity()));
        }
        if (running && !unified) {
            LOGGER.debug("成员 {} 正在运行，本次不投料也不改电路", target.blockEntity().getBlockPos());
        }
        IFluidHandler fluids = probeFluids
                ? FindMeExtendedAdapter.fluidHandlerOf(target.blockEntity())
                : null;
        return new Member(target, ownOverflow, container.name(), running && !unified, fluids);
    }

    /**
     * 某个成员<b>自己的</b>从容器。
     *
     * <p>独立模式下每个并列成员可以各挂一只从容器，作为那一台机器的容量扩展：机器收不下的余量
     * 先进它自己的箱子，箱子也满了才顺延给别的成员。整体模式下
     * {@link BoundGroupResolver.BoundGroup#effectiveMemberSlaves} 一律返回空——那时全组的从容器
     * 已经由 {@code effectiveSlaves()} 当作整组缓冲了，再按成员算一遍就是同一批箱子被投两次。
     *
     * <p>不可用的从容器直接跳过（记一条 debug 日志），不让一只没加载的箱子把整单拦下来。
     */
    private static List<BoundTargetResolver.ResolvedTarget> ownOverflowOf(
            ServerPlayer player, BoundGroupResolver.BoundGroup group, BoundContainer member) {
        List<BoundTargetResolver.ResolvedTarget> targets = new ArrayList<>();
        for (BoundContainer slave : group.effectiveMemberSlaves(member)) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
            if (!target.available()) {
                LOGGER.debug("成员「{}」的从容器「{}」当前不可用（{}），本次跳过",
                        member.name(), slave.name(), target.status());
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    /** 成员主落点表，顺序与 {@code members} 一致。 */
    static List<RemoteTransferPlanner.Target> slots(List<Member> members) {
        List<RemoteTransferPlanner.Target> slots = new ArrayList<>(members.size());
        for (Member member : members) {
            slots.add(member.slot());
        }
        return slots;
    }

    /** 成员自己的从容器表，下标与 {@code members} 严格对齐。 */
    static List<List<RemoteTransferPlanner.Target>> ownOverflows(List<Member> members) {
        List<List<RemoteTransferPlanner.Target>> overflows = new ArrayList<>(members.size());
        for (Member member : members) {
            overflows.add(slotsOf(member.ownOverflow()));
        }
        return overflows;
    }

    /**
     * 把成员表循环左移 {@code offset} 位（分块轮换起点用）。
     *
     * <p>只转这一个列表：从容器表与流体能力都由它派生，所以不可能再出现「机器转了、箱子没转」
     * 这种错位。
     */
    static List<Member> rotate(List<Member> members, int offset) {
        if (members.isEmpty() || offset <= 0) {
            return members;
        }
        List<Member> rotated = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            rotated.add(members.get((index + offset) % members.size()));
        }
        return rotated;
    }

    private static List<RemoteTransferPlanner.Target> slotsOf(
            List<BoundTargetResolver.ResolvedTarget> resolved) {
        List<RemoteTransferPlanner.Target> slots = new ArrayList<>(resolved.size());
        for (BoundTargetResolver.ResolvedTarget target : resolved) {
            slots.add(target.toTarget());
        }
        return slots;
    }
}
