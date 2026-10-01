package com.lai.recipesender.service;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
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
                reply(player, packet, BoundStatus.OK, 0, 0,
                        "text.recipe_sender.bound_detail.materials");
                return;
            }
            BoundTargetResolver.ResolvedTarget master = BoundTargetResolver.resolve(player, group.master());
            if (!master.available()) {
                reply(player, packet, master.status(), 0, 0, null);
                return;
            }
            List<BoundTargetResolver.ResolvedTarget> resolved = new ArrayList<>();
            List<RemoteTransferPlanner.Target> members = new ArrayList<>();
            members.add(new RemoteTransferPlanner.Target(master.handler(), master.blockEntity()));
            resolved.add(master);
            for (BoundContainer parallel : group.parallels()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, parallel);
                if (!target.available()) {
                    LOGGER.debug("并列成员「{}」当前不可用（{}），本次均分跳过", parallel.name(), target.status());
                    continue;
                }
                members.add(new RemoteTransferPlanner.Target(target.handler(), target.blockEntity()));
                resolved.add(target);
            }
            List<RemoteTransferPlanner.Target> overflow = new ArrayList<>();
            for (BoundContainer slave : group.slaves()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
                if (!target.available()) {
                    LOGGER.debug("从容器「{}」当前不可用（{}），本次溢出跳过", slave.name(), target.status());
                    continue;
                }
                overflow.add(new RemoteTransferPlanner.Target(target.handler(), target.blockEntity()));
                resolved.add(target);
            }
            // 电路必须在投料之前全部调完：材料一进机器，配方逻辑就会按当时的电路立刻开始匹配，
            // 调晚了等于先跑错一次。从容器刻意不在这个列表里——它只是容量备份，溢出的材料进去
            // 之后由玩家自己搬，改它的电路只会让玩家困惑。
            applyCircuits(members, packet.circuit(), packet.gregRecipe());
            RemoteTransferPlanner.Applied applied =
                    RemoteTransferPlanner.applyBySharesDetailed(members, overflow, requirements, packet.batches());
            List<ItemStack> inserted = applied.inserted();
            if (inserted.isEmpty()) {
                reply(player, packet, BoundStatus.OK, 0, 0, "text.recipe_sender.bound_detail.target_full");
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
            String detail = insertedBatches >= packet.batches()
                    ? null : "text.recipe_sender.bound_detail.target_full";
            reply(player, packet, BoundStatus.OK, insertedBatches, applied.overflowBatches(), detail);
        } catch (RuntimeException exception) {
            LOGGER.warn("向已绑定容器投放配方材料失败：玩家 {}", player.getGameProfile().getName(), exception);
            reply(player, packet, BoundStatus.FORBIDDEN, 0, 0, null);
        }
    }

    /**
     * 按配方要求把并列组里每台机器的电路调好。
     *
     * <p>只调主容器与并列成员（{@code members}）：从容器即使有电路槽也不动，它只是容量备份。
     *
     * <p>取不到电路槽（普通箱子、非格雷机器、多方块部件挂不到控制器）时静默跳过——绑定目标本来
     * 就可能是箱子，电路调不了绝不能牵连材料投放。
     */
    private static void applyCircuits(List<RemoteTransferPlanner.Target> members,
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
     */
    private static void markChanged(BoundTargetResolver.ResolvedTarget target) {
        if (target.blockEntity() != null) {
            target.blockEntity().setChanged();
        }
    }

    /**
     * 按「完整送达的份数」计数。
     *
     * <p>份是原子单位：某项材料只要少一份，这一份就不能算送达，因此取各项的最小值。
     */
    private static int countInsertedBatches(List<ItemStack> requirements, List<ItemStack> inserted,
                                            int batches) {
        if (batches <= 0) {
            return 0;
        }
        int best = batches;
        for (ItemStack requirement : mergeByItem(requirements)) {
            int perBatch = requirement.getCount() / batches;
            if (perBatch <= 0) {
                continue;
            }
            int actual = 0;
            for (ItemStack stack : inserted) {
                if (ItemStack.isSameItemSameTags(stack, requirement)) {
                    actual += stack.getCount();
                }
            }
            best = Math.min(best, actual / perBatch);
        }
        return Math.max(0, best);
    }

    /**
     * 按物品类型合并同一物品的多个分片。
     *
     * <p>{@code RecipeMaterialCollector} 在单项超过 4096 时会切成多块，这里必须先把它们合起来，
     * 否则每一块都会把同一份计数重复算一遍。
     */
    private static List<ItemStack> mergeByItem(List<ItemStack> requirements) {
        return RemoteTransferPlanner.mergeByItem(requirements);
    }

    /** 把结果回执给发起请求的玩家。 */
    private static void reply(ServerPlayer player, InsertRecipeItemsToBoundPacket packet,
                              BoundStatus status, int insertedBatches, int overflowBatches, String detailKey) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BoundInsertResultPacket(packet.requestId(), packet.bindingId(), status,
                        insertedBatches, packet.batches(), overflowBatches, detailKey));
    }
}
