package com.lai.recipesender.service;

import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.SourceScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 物品中枢：直达模式里「材料从哪来」的另一种答案。
 *
 * <p>与 {@link FluidHubService} 同一个思路——绑定一两个容器当固定来源，用的时候不再扫世界。
 * 区别在用途：流体中枢是流体的<b>唯一</b>来源，物品中枢则只是 {@link SourceScope#HUB}
 * 这一种取料范围的来源；周围直达（{@link SourceScope#NEARBY}）完全不看它。
 *
 * <p>为什么值得单独存在：周围直达每次发送都要枚举搜索范围内的方块实体，成本与基地大小成正比；
 * 中枢直达只读绑定容器的槽位，成本与中枢大小成正比。材料集中在少数容器里的基地因此既快，
 * 又不会顺手拿走周围箱子里不该拿的东西。
 *
 * <p>中枢可以绑多个：大型基地的材料常常分散在几个网络/箱子上，多绑一个比换一次绑定省事。
 * 它们之间没有主从关系，按绑定顺序依次取料。
 */
public final class ItemHubService {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private ItemHubService() {
    }

    /** 玩家绑定的所有物品中枢（不校验可用性，列表页要用）。 */
    public static List<BoundContainer> find(ServerPlayer player) {
        List<BoundContainer> hubs = new ArrayList<>();
        for (BoundContainer binding : BoundContainerService.list(player)) {
            if (binding.isItemHub()) {
                hubs.add(binding);
            }
        }
        return hubs;
    }

    /**
     * 当前可用的物品中枢。
     *
     * <p>维度不对、没加载、方块实体没了、不是物品容器的中枢会被跳过（与流体中枢一致：
     * 绑了但此刻不可用，对玩家来说等同于没绑）。
     */
    public static List<BoundTargetResolver.ResolvedTarget> resolve(ServerPlayer player) {
        List<BoundTargetResolver.ResolvedTarget> targets = new ArrayList<>();
        // 同一个方块被绑成两个中枢（绑定界面不禁止）时只算一次：否则份数统计会翻倍，
        // 而真正抽料时第二个「中枢」其实是同一个容器，于是份数永远对不上。
        List<BlockEntity> seen = new ArrayList<>();
        for (BoundContainer hub : find(player)) {
            BoundTargetResolver.ResolvedTarget resolved = BoundTargetResolver.resolve(player, hub);
            if (!resolved.available()) {
                LOGGER.debug("物品中枢「{}」当前不可用（{}），本次跳过", hub.name(), resolved.status());
                continue;
            }
            if (seen.contains(resolved.blockEntity())) {
                LOGGER.debug("物品中枢「{}」与已绑定的中枢是同一个方块，本次只算一次", hub.name());
                continue;
            }
            seen.add(resolved.blockEntity());
            targets.add(resolved);
        }
        return targets;
    }

    /**
     * 中枢里的物品快照，供份数统计与规划使用。
     *
     * <p>读取走 {@link FindMeExtendedAdapter#snapshotContainers}，也就是周围扫描用的同一条路径：
     * <b>先问 AE2 存储，问不到才退回 Forge 物品处理器</b>。这一点对 GTO 的物品保险库是必须的——
     * 它把同一份库存同时挂成处理器和 MEStorage，而处理器那一侧只暴露第一个物品键，
     * 直接按槽位读会读出一堆空槽，中枢直达就会显示「0 份」，明明仓里堆满了料。
     *
     * <p>与周围扫描的唯一区别是容器列表来自绑定，不枚举世界：中枢直达的开销只和中枢大小成正比。
     */
    public static List<ItemStack> contents(ServerPlayer player, Predicate<ItemStack> relevant) {
        return FindMeExtendedAdapter.snapshotContainers(blocks(resolve(player)), relevant);
    }

    /**
     * 从物品中枢抽料，抽到的追加进 {@code output}。
     *
     * <p>同样复用 {@code pullAllTo}：它先试 AE2 存储、再按槽位 {@code extractItem}，
     * 与上面统计时「模拟抽取说抽得出来才抽得出来」的判定是同一套调用，不会出现
     * 「统计说有、真抽时抽不到」。
     *
     * @return 每一项实际抽到的数量，下标与 {@code requirements} 对齐
     */
    public static int[] extract(ServerPlayer player, List<ItemStack> requirements,
                                List<ItemStack> output) {
        if (player == null || requirements == null || requirements.isEmpty()) {
            return new int[requirements == null ? 0 : requirements.size()];
        }
        List<BlockEntity> blocks = blocks(resolve(player));
        if (blocks.isEmpty()) {
            return new int[requirements.size()];
        }
        int[] pulled = FindMeExtendedAdapter.pullAllTo(player, blocks, requirements, output);
        // 没装 FindMeExtended（反射通路为 null）时 pullAllTo 一律返回 0，但普通箱子的
        // Forge 处理器照样能抽——中枢直达不该因为缺一个可选模组就完全不可用。
        for (int index = 0; index < requirements.size(); index++) {
            int missing = requirements.get(index).getCount() - pulled[index];
            if (missing > 0) {
                pulled[index] += takeFromHandlers(blocks, requirements.get(index), missing, output);
            }
        }
        for (BlockEntity block : blocks) {
            block.setChanged();
        }
        return pulled;
    }

    /** 退回 Forge 槽位抽取；只在反射通路不可用时兜底。 */
    private static int takeFromHandlers(List<BlockEntity> blocks, ItemStack requirement, int wanted,
                                        List<ItemStack> output) {
        int taken = 0;
        for (BlockEntity block : blocks) {
            if (taken >= wanted) {
                break;
            }
            Optional<IItemHandler> handler = block.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                    .resolve();
            if (handler.isEmpty()) {
                continue;
            }
            taken += takeFromHandler(handler.get(), requirement, wanted - taken, output);
        }
        return taken;
    }

    private static int takeFromHandler(IItemHandler handler, ItemStack requirement, int wanted,
                                       List<ItemStack> output) {
        int taken = 0;
        for (int slot = 0; slot < handler.getSlots() && taken < wanted; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, requirement)) {
                continue;
            }
            ItemStack extracted;
            try {
                extracted = handler.extractItem(slot, wanted - taken, false);
            } catch (RuntimeException exception) {
                LOGGER.debug("从中枢槽位 {} 抽取失败，跳过该槽位", slot, exception);
                continue;
            }
            if (extracted.isEmpty()) {
                continue;
            }
            taken += extracted.getCount();
            output.add(extracted.copy());
        }
        return taken;
    }

    /** 已解析的中枢方块实体；统计与抽取共用同一份顺序。 */
    private static List<BlockEntity> blocks(List<BoundTargetResolver.ResolvedTarget> targets) {
        List<BlockEntity> blocks = new ArrayList<>(targets.size());
        for (BoundTargetResolver.ResolvedTarget target : targets) {
            if (target.blockEntity() != null) {
                blocks.add(target.blockEntity());
            }
        }
        return blocks;
    }

    /**
     * 把材料放回物品中枢。
     *
     * <p>中枢直达的料是从中枢抽出来的，没投进目标的余料自然该回中枢：塞进玩家背包等于把中枢
     * 的东西悄悄搬进背包，玩家下一次中枢直达就会发现料莫名少了一截。
     *
     * @return 中枢也放不下、只能交给玩家的部分
     */
    public static List<ItemStack> insert(ServerPlayer player, List<ItemStack> stacks) {
        List<ItemStack> leftover = new ArrayList<>();
        if (player == null || stacks == null || stacks.isEmpty()) {
            return leftover;
        }
        List<BoundTargetResolver.ResolvedTarget> targets = resolve(player);
        if (targets.isEmpty()) {
            leftover.addAll(stacks);
            return leftover;
        }
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            for (BoundTargetResolver.ResolvedTarget target : targets) {
                if (remaining.isEmpty()) {
                    break;
                }
                remaining = insertInto(target.handler(), remaining);
                target.blockEntity().setChanged();
            }
            if (!remaining.isEmpty()) {
                leftover.add(remaining);
            }
        }
        return leftover;
    }

    /** 往单个中枢里塞，返回没塞下的部分。 */
    private static ItemStack insertInto(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            try {
                remaining = handler.insertItem(slot, remaining, false);
            } catch (RuntimeException exception) {
                LOGGER.debug("向中枢槽位 {} 放回余料失败，跳过该槽位", slot, exception);
                return remaining;
            }
        }
        return remaining;
    }
}
