package com.lai.recipesender.service;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.SourceScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
     * <p>与 {@code FindMeExtendedAdapter.snapshotNearby} 保持同一套「看得见 ≠ 取得出」语义：
     * 先按 {@code relevant} 过滤（避免碰无关槽位），再做一次模拟抽取确认真的取得出来。
     * 唯一区别是不枚举世界——直接读绑定容器的槽位。
     */
    public static List<ItemStack> contents(ServerPlayer player, Predicate<ItemStack> relevant) {
        List<ItemStack> stacks = new ArrayList<>();
        for (BoundTargetResolver.ResolvedTarget target : resolve(player)) {
            IItemHandler handler = target.handler();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || !relevant.test(stack)) {
                    continue;
                }
                if (!canExtract(handler, slot)) {
                    continue;
                }
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    /**
     * 从物品中枢抽料，抽到的追加进 {@code output}。
     *
     * <p>与周围直达的抽取是两条独立实现，这是有意的：周围直达要经过 FindMeExtended 的反射通路
     * （AE2 存储、黑名单、模拟抽取的副作用都要照顾），而中枢就是普通的 {@link IItemHandler}，
     * 直接按槽位 {@code extractItem} 即可，少一层反射也少一处可能对不上的语义。
     *
     * @return 每一项实际抽到的数量，下标与 {@code requirements} 对齐
     */
    public static int[] extract(ServerPlayer player, List<ItemStack> requirements,
                                List<ItemStack> output) {
        int[] pulled = new int[requirements.size()];
        if (player == null || requirements == null || requirements.isEmpty()) {
            return pulled;
        }
        List<BoundTargetResolver.ResolvedTarget> targets = resolve(player);
        if (targets.isEmpty()) {
            return pulled;
        }
        for (int index = 0; index < requirements.size(); index++) {
            ItemStack requirement = requirements.get(index);
            if (requirement.isEmpty()) {
                continue;
            }
            int remaining = requirement.getCount();
            for (BoundTargetResolver.ResolvedTarget target : targets) {
                if (remaining <= 0) {
                    break;
                }
                remaining -= take(target, requirement, remaining, output);
                target.blockEntity().setChanged();
            }
            pulled[index] = requirement.getCount() - remaining;
        }
        return pulled;
    }

    /** 在单个中枢里按槽位抽一项材料，返回实际抽到的数量。 */
    private static int take(BoundTargetResolver.ResolvedTarget target, ItemStack requirement,
                            int wanted, List<ItemStack> output) {
        IItemHandler handler = target.handler();
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

    /** 模拟抽取一个槽位：别人的 {@code extractItem} 可能有副作用，所以只探相关的槽位。 */
    private static boolean canExtract(IItemHandler handler, int slot) {
        try {
            return !handler.extractItem(slot, 1, true).isEmpty();
        } catch (RuntimeException exception) {
            LOGGER.debug("模拟抽取中枢槽位 {} 失败，本次不统计该槽位", slot, exception);
            return false;
        }
    }
}
