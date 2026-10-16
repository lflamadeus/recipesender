package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.integration.gt.MachineStateProbe;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.MachineState;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BoundInsertResultPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsToBoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 「发送到已绑定容器」的服务端执行。
 *
 * <p>与 {@link RecipeInsertionService} 的骨架一致（校验 → 规划 → 应用 → 捕获 RuntimeException），
 * 但落点不是玩家当前打开的菜单，而是绑定记录里的坐标：
 * <ol>
 *   <li>绑定必须存在、属于该玩家，且是<b>主容器</b>（发送单元的代表）；</li>
 *   <li>{@link BoundGroupResolver} 在发送这一刻动态解析出整组（主容器 + 并列成员 + 从容器）；</li>
 *   <li>{@link BoundTargetResolver} 按坐标逐个解析出 {@code IItemHandler}；</li>
 *   <li>{@link InventoryDeduction} 先在背包上做全量模拟（材料不足就整体拒绝）；</li>
 *   <li>{@link RemoteTransferPlanner#applyByShares} 按份均分并逐份投放，装不下的份顺延给下一个落点；</li>
 *   <li>真实投放后，<b>按实际插入量</b>扣背包——目标实现可能给出与模拟不一致的结果。</li>
 * </ol>
 *
 * <p><b>主容器不可用 → 整个发送单元失败</b>；并列成员或从容器不可用只是少一个落点，其余照常均分。
 *
 * <p>无论成功还是失败都回执：落点在玩家看不到的地方，没有回执就只剩「点了没反应」。
 */
public final class BoundInsertionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private BoundInsertionService() {
    }

    /** 处理一次「发送到已绑定容器」请求。 */
    public static void insert(ServerPlayer player, InsertRecipeItemsToBoundPacket packet) {
        if (player == null || packet == null || packet.requirements().isEmpty()) {
            return;
        }
        try {
            if (packet.batches() < 1 || packet.batches() > RecipeSenderConfig.maxBatches()) {
                // 份数是客户端说了算的数字，但落点分配要为每一份建一个列表：
                // 不校验的话一个伪造的「20 亿份」就能把服务端算到卡死。
                reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
                return;
            }
            BoundContainer binding = BoundContainerService.find(player, packet.bindingId());
            if (binding == null || !binding.isMaster()) {
                // 绑定可能刚被玩家在管理界面删掉、或者指向的是并列成员/从容器（它们不独立收材料），
                // 也可能是伪造的数据包。
                reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
                return;
            }
            BoundGroupResolver.BoundGroup group =
                    BoundGroupResolver.groupOf(BoundContainerService.list(player), binding.id());
            if (group == null) {
                reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
                return;
            }
            List<ItemStack> requirements = InventoryDeduction.normalize(packet.requirements());
            if (requirements.isEmpty()) {
                reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
                return;
            }
            if (InventoryDeduction.plan(player.getInventory(), requirements) == null) {
                reply(player, packet, BoundStatus.OK, 0, 0, BoundDetail.MATERIALS);
                return;
            }
            BoundTargetResolver.ResolvedTarget master = BoundTargetResolver.resolve(player, group.master());
            if (!master.available()) {
                reply(player, packet, master.status(), 0, 0, null);
                return;
            }
            List<BoundTargetResolver.ResolvedTarget> resolved = new ArrayList<>();
            List<RemoteTransferPlanner.Target> members = new ArrayList<>();
            // 与 members 一一对应：按机器状态过滤时，要用它同步摘掉 resolved 里对应的那一项。
            List<BoundTargetResolver.ResolvedTarget> memberTargets = new ArrayList<>();
            // 同样与 members 一一对应：整体模式的电路闸门要在报错文案里点名是哪一台机器。
            List<String> memberNames = new ArrayList<>();
            // 同样与 members 一一对应：这一台机器<b>自己的</b>从容器（独立模式下给单台扩容的箱子）。
            // 下标必须严格对齐——规划器靠下标找「这一台的箱子」，错位就会把材料塞进别人的箱子。
            List<List<RemoteTransferPlanner.Target>> memberOverflow = new ArrayList<>();
            members.add(master.toTarget());
            memberTargets.add(master);
            memberNames.add(binding.name());
            resolved.add(master);
            memberOverflow.add(resolveMemberSlaves(player, group, group.master(), resolved));
            for (BoundContainer parallel : group.parallels()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, parallel);
                if (!target.available()) {
                    LOGGER.debug("并列成员「{}」当前不可用（{}），本次均分跳过", parallel.name(), target.status());
                    continue;
                }
                members.add(target.toTarget());
                memberTargets.add(target);
                memberNames.add(parallel.name());
                resolved.add(target);
                memberOverflow.add(resolveMemberSlaves(player, group, parallel, resolved));
            }
            List<RemoteTransferPlanner.Target> overflow = new ArrayList<>();
            // 组级从容器（挂在主容器下的那些）：整体模式下它们就是整组的溢出缓冲。
            // 独立模式下 effectiveSlaves() 返回空——那时每个成员的余量由它<b>自己</b>的从容器接住
            // （见上面的 memberOverflow），组级从容器不再是落点。
            for (BoundContainer slave : group.effectiveSlaves()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
                if (!target.available()) {
                    LOGGER.debug("从容器「{}」当前不可用（{}），本次溢出跳过", slave.name(), target.status());
                    continue;
                }
                overflow.add(target.toTarget());
                resolved.add(target);
            }
            // 忙闲探测：两种模式都要，但用法完全不同。
            //
            // 独立模式（1.0.34 起的既有行为）：只往空闲机器投料、只给空闲机器调电路。机器跑到一半被改
            // 电路，手上那一炉就会按新电路重新匹配——玩家看到的就是「明明在跑却换了配方」。所以正在跑
            // 的成员这一次整个跳过：不改它的电路，也不给它投料，等它跑完，下一次发送自然会用上它。
            //
            // 整体模式（1.0.38 起）：整组被当成一台大机器，电路必须一致。在忙的机器也接料（材料由玩家
            // 自己铺的路径从从容器补进组内，这正是整体模式要解决的场景），代价是它们的电路必须已经
            // 等于本次电路，否则整单取消。
            //
            // 两种模式都只过滤主容器与并列成员：从容器是容量备份（通常是箱子），本来就没有配方逻辑，
            // 也不参与调电路，没有「跑到一半」可言。
            boolean unified = group.unified();
            List<RemoteTransferPlanner.Target> idleMembers = new ArrayList<>(members.size());
            // 与 idleMembers 一一对应：机器在忙被跳过时，它自己的从容器也一起跳过——
            // 箱子是那一台机器的扩容，机器这一趟不接料，箱子就不该替它收（收进去的材料没有归属）。
            List<List<RemoteTransferPlanner.Target>> idleOverflow = new ArrayList<>(members.size());
            List<GroupCircuitGate.Member> busyMembers = new ArrayList<>();
            int skippedMembers = 0;
            for (int index = 0; index < members.size(); index++) {
                RemoteTransferPlanner.Target member = members.get(index);
                if (MachineStateProbe.stateOf(member.blockEntity()) != MachineState.BUSY) {
                    idleMembers.add(member);
                    idleOverflow.add(memberOverflow.get(index));
                    continue;
                }
                if (unified) {
                    // 在忙也接料：记下来交给闸门判定电路，落点表里留着它。
                    busyMembers.add(new GroupCircuitGate.Member(memberNames.get(index), member.blockEntity()));
                    continue;
                }
                skippedMembers++;
                // 从 resolved 里摘掉：它不该再参与后面的收尾（markChanged）。
                resolved.remove(memberTargets.get(index));
                LOGGER.debug("成员 {} 正在运行，本次不投料也不改电路", member.blockEntity().getBlockPos());
            }
            // 整体模式下在忙的成员照常接料，落点表就是全组；独立模式下只有空闲机器能收。
            // 成员自己的从容器表必须跟着同一套选择走，否则下标就对不上机器了。
            List<RemoteTransferPlanner.Target> placeMembers = unified ? members : idleMembers;
            List<List<RemoteTransferPlanner.Target>> placeOverflow = unified ? memberOverflow : idleOverflow;
            if (placeMembers.isEmpty()) {
                // 独立模式下一台空闲机器都没有：一份都不投，电路也不动。这里刻意不走从容器（overflow）——
                // 把材料塞进没人加工的箱子等于把它们留在那儿，不是玩家要的「发送」。
                reply(player, packet, BoundStatus.OK, 0, 0, BoundDetail.ALL_BUSY);
                return;
            }
            if (unified) {
                // 在忙的机器电路必须已经符合本次要求；不一致就整单取消——一台机器的电路都不动、
                // 一份材料都不投。改它们的电路会让它们按新电路重新匹配，等于毁掉手上那批活。
                GroupCircuitGate.Result gate =
                        GroupCircuitGate.checkBusy(busyMembers, packet.circuit(), packet.gregRecipe());
                if (!gate.allowed()) {
                    reportGroupCircuitMismatch(player, gate, packet.circuit(), packet.gregRecipe());
                    reply(player, packet, BoundStatus.OK, 0, 0, BoundDetail.GROUP_CIRCUIT_MISMATCH);
                    return;
                }
            }
            // 电路必须在投料之前全部调完：材料一进机器，配方逻辑就会按当时的电路立刻开始匹配，
            // 调晚了等于先跑错一次。从容器刻意不在这个列表里——它只是容量备份，溢出的材料进去
            // 之后由玩家自己搬，改它的电路只会让玩家困惑。
            //
            // 整体模式调全组：空闲成员按本次电路写入，在忙成员刚刚已被闸门确认一致（写入是 no-op）。
            applyCircuits(placeMembers, packet.circuit(), packet.gregRecipe());
            RemoteTransferPlanner.Applied applied = RemoteTransferPlanner.applyBySharesDetailed(
                    placeMembers, placeOverflow, overflow, requirements, packet.batches());
            List<ItemStack> inserted = applied.inserted();
            if (inserted.isEmpty()) {
                reply(player, packet, BoundStatus.OK, 0, 0,
                        skippedMembers > 0 ? BoundDetail.MACHINES_BUSY : BoundDetail.TARGET_FULL);
                return;
            }
            // 真实插入量可能少于计划量（个别 IItemHandler 会在模拟与执行之间给出不同答案），
            // 所以扣背包必须用实际插入量，绝不能用计划量。
            List<InventoryDeduction.Removal> removals = InventoryDeduction.plan(player.getInventory(), inserted);
            if (removals == null) {
                LOGGER.warn("玩家 {} 的背包在投放前后不一致，放弃扣除（目标已收到材料）",
                        player.getGameProfile().getName());
            } else {
                InventoryDeduction.apply(player.getInventory(), removals);
            }
            for (BoundTargetResolver.ResolvedTarget target : resolved) {
                markChanged(target);
            }
            int insertedBatches = countInsertedBatches(requirements, inserted, packet.batches());
            BoundDetail detail;
            if (insertedBatches >= packet.batches()) {
                detail = null;
            } else if (skippedMembers > 0) {
                // 有机器在跑被跳过了：这一次少送的份数里可能就有它们的一份，比笼统说「目标已满」准确。
                detail = BoundDetail.MACHINES_BUSY;
            } else {
                detail = BoundDetail.TARGET_FULL;
            }
            reply(player, packet, BoundStatus.OK, insertedBatches, applied.overflowBatches(), detail);
        } catch (RuntimeException exception) {
            LOGGER.warn("向已绑定容器投放配方材料失败：玩家 {}", player.getGameProfile().getName(), exception);
            reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
        }
    }

    /**
     * 整体模式电路不一致时的详细说明。
     *
     * <p>回执里只有 {@link BoundDetail#GROUP_CIRCUIT_MISMATCH} 那条固定文案，玩家还得知道是哪一台、
     * 当前几号、本次要几号，才能决定是去改机器还是改配方。所以这里再发一条带细节的提示。
     *
     * <p>参数一律转成字符串推给客户端（{@code BoundNoticePacket} 只传字符串），因此「本次要清空电路」
     * 走另一条语言键，而不是把「无」这种中文硬编码进服务端。
     */
    static void reportGroupCircuitMismatch(ServerPlayer player, GroupCircuitGate.Result gate,
                                           int circuit, boolean gregRecipe) {
        boolean foreign = gate.blockerCircuit() == GtCircuitSupport.NO_CIRCUIT;
        if (circuit < 0) {
            // 本次配方不用电路（gregRecipe 为真时 circuit < 0 才会走到这里）：槽必须清空。
            if (foreign) {
                BoundBindingService.notice(player, NoticeSeverity.WARN,
                        "text.recipe_sender.group_circuit_blocked_foreign_clear", gate.blocker());
            } else {
                BoundBindingService.notice(player, NoticeSeverity.WARN,
                        "text.recipe_sender.group_circuit_blocked_clear", gate.blocker(),
                        String.valueOf(gate.blockerCircuit()));
            }
            return;
        }
        if (foreign) {
            BoundBindingService.notice(player, NoticeSeverity.WARN,
                    "text.recipe_sender.group_circuit_blocked_foreign", gate.blocker(),
                    String.valueOf(circuit));
        } else {
            BoundBindingService.notice(player, NoticeSeverity.WARN,
                    "text.recipe_sender.group_circuit_blocked", gate.blocker(),
                    String.valueOf(gate.blockerCircuit()), String.valueOf(circuit));
        }
    }

    /**
     * 按配方要求把并列组里每台机器的电路调好。
     *
     * <p>只调主容器与并列成员（{@code members}）：从容器即使有电路槽也不动，它只是容量备份。
     *
     * <p>取不到电路槽（普通箱子、非格雷机器、多方块部件挂不到控制器）时静默跳过——绑定目标本来
     * 就可能是箱子，电路调不了绝不能牵连材料投放。
     *
     * <p>包内可见：直达模式（{@link DirectInsertionService}）的落点语义与绑定发送完全一致，
     * 共用这一段，免得两处对「电路什么时候调」给出不同答案。
     */
    static void applyCircuits(List<RemoteTransferPlanner.Target> members,
                              int circuit, boolean gregRecipe) {
        for (RemoteTransferPlanner.Target member : members) {
            MachineCircuitService.applyToBlockEntity(member.blockEntity(), circuit, gregRecipe);
        }
    }

    /**
     * 把某个成员<b>自己的</b>从容器解析成落点表。
     *
     * <p>独立模式下每个并列成员可以各挂一只从容器，作为那一台机器的容量扩展：机器收不下的余量
     * 先进它自己的箱子，箱子也满了才顺延给别的成员。整体模式下
     * {@link BoundGroupResolver.BoundGroup#effectiveMemberSlaves} 一律返回空——那时全组的从容器
     * 已经由 {@code effectiveSlaves()} 当作整组缓冲了，再按成员算一遍就是同一批箱子被投两次。
     *
     * <p>不可用的从容器直接跳过（记一条 debug 日志），不让一只没加载的箱子把整单拦下来。
     * 可用的会被塞进 {@code resolved}，好让收尾阶段一样给它们 {@code setChanged()}。
     *
     * <p>包内可见：直达模式也是「一台机器配一只自己的箱子」，两处必须用同一套解析，
     * 否则同一只箱子在两条路径上的可用性判定会漂移。
     */
    static List<RemoteTransferPlanner.Target> resolveMemberSlaves(
            ServerPlayer player, BoundGroupResolver.BoundGroup group, BoundContainer member,
            List<BoundTargetResolver.ResolvedTarget> resolved) {
        List<RemoteTransferPlanner.Target> targets = new ArrayList<>();
        for (BoundContainer slave : group.effectiveMemberSlaves(member)) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
            if (!target.available()) {
                LOGGER.debug("成员「{}」的从容器「{}」当前不可用（{}），本次跳过",
                        member.name(), slave.name(), target.status());
                continue;
            }
            targets.add(target.toTarget());
            resolved.add(target);
        }
        return targets;
    }

    /**
     * 通知目标方块内容已变化。
     *
     * <p>原版箱子经由 {@code InvWrapper} 时自己会调 {@code setChanged}，但自定义实现不一定；
     * 这里补一次，让方块实体正常参与保存与比较。
     *
     * <p>包内可见：直达模式与绑定发送落到同一批目标上，用同一条收尾逻辑。
     */
    static void markChanged(BoundTargetResolver.ResolvedTarget target) {
        if (target.blockEntity() != null) {
            target.blockEntity().setChanged();
        }
    }

    /**
     * 按「完整送达的份数」计数。
     *
     * <p>包内可见：直达模式每搬一块都要按同样的口径数一遍「这一块真正进了几份」。
     * 实现已挪到 {@link RemoteTransferPlanner#countBatches}，好让投放路径内部折算
     * 「溢出到从容器的份数」时用同一条口径，两处不会漂移。
     */
    static int countInsertedBatches(List<ItemStack> requirements, List<ItemStack> inserted,
                                    int batches) {
        return RemoteTransferPlanner.countBatches(requirements, inserted, batches);
    }

    /** 把结果回执给发起请求的玩家。 */
    private static void reply(ServerPlayer player, InsertRecipeItemsToBoundPacket packet,
                              BoundStatus status, int insertedBatches, int overflowBatches, BoundDetail detail) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BoundInsertResultPacket(packet.requestId(), packet.bindingId(), status,
                        insertedBatches, packet.batches(), overflowBatches, detail));
    }
}
