package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BoundInsertResultPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsToBoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            // 落点集合：主容器 + 并列成员 + 各自的从容器 + 组级从容器，忙闲也在这一步探完。
            // 一台机器一行（{@link DirectTargets.Member}），跳过标记与自己的从容器都跟着它走，
            // 不再需要几张靠下标手工对齐的平行表。
            DirectTargets.TargetSet targets = DirectTargets.resolve(player, group, master, false);
            List<DirectTargets.Member> placeMembers = targets.active();
            if (placeMembers.isEmpty()) {
                // 独立模式下一台空闲机器都没有：一份都不投，电路也不动。这里刻意不走从容器（overflow）——
                // 把材料塞进没人加工的箱子等于把它们留在那儿，不是玩家要的「发送」。
                reply(player, packet, BoundStatus.OK, 0, 0, BoundDetail.ALL_BUSY);
                return;
            }
            if (targets.unified()) {
                // 在忙的机器电路必须已经符合本次要求；不一致就整单取消——一台机器的电路都不动、
                // 一份材料都不投。改它们的电路会让它们按新电路重新匹配，等于毁掉手上那批活。
                GroupCircuitGate.Result gate =
                        GroupCircuitGate.checkBusy(targets.busy(), packet.circuit(), packet.gregRecipe());
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
            applyCircuits(DirectTargets.slots(placeMembers), packet.circuit(), packet.gregRecipe());
            RemoteTransferPlanner.Applied applied = RemoteTransferPlanner.applyBySharesDetailed(
                    DirectTargets.slots(placeMembers), DirectTargets.ownOverflows(placeMembers),
                    targets.groupOverflowSlots(), requirements, packet.batches());
            List<ItemStack> inserted = applied.inserted();
            if (inserted.isEmpty()) {
                reply(player, packet, BoundStatus.OK, 0, 0,
                        targets.skipped() > 0 ? BoundDetail.MACHINES_BUSY : BoundDetail.TARGET_FULL);
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
            for (BoundTargetResolver.ResolvedTarget target : targets.allResolved()) {
                markChanged(target);
            }
            int insertedBatches = countInsertedBatches(requirements, inserted, packet.batches());
            BoundDetail detail;
            if (insertedBatches >= packet.batches()) {
                detail = null;
            } else if (targets.skipped() > 0) {
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
