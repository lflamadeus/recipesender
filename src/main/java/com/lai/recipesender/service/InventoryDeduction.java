package com.lai.recipesender.service;

import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 从玩家背包扣除材料：规范化需求、规划扣除、执行扣除。
 *
 * <p>这一份实现同时服务于两条投放通路：
 * <ul>
 *   <li>{@link TransferPlanner}——目标就是当前打开的菜单，走 {@code Slot}；</li>
 *   <li>{@link RemoteTransferPlanner}——目标是按坐标访问的远程容器，走 {@code IItemHandler}。</li>
 * </ul>
 * 扣背包这一步两条通路完全一致，抽出来共用，避免两边的 NBT 匹配规则和上限判定各写一份、日后跑偏。
 */
public final class InventoryDeduction {

    /** 一次扣除：从背包第 {@code slot} 个主背包槽拿走 {@code amount} 个。 */
    public record Removal(int slot, int amount) {
    }

    private InventoryDeduction() {
    }

    /**
     * 合并并校验客户端提交的物品需求。
     *
     * @return 规范化后的列表；数据非法时返回空列表（调用方据此整体拒绝）
     */
    public static List<ItemStack> normalize(List<ItemStack> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<ItemStack> normalized = new ArrayList<>();
        int totalItems = 0;
        for (ItemStack requirement : requirements) {
            if (requirement == null || requirement.isEmpty()
                    || requirement.getCount() <= 0
                    || requirement.getCount() > InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT) {
                return List.of();
            }
            totalItems = Math.min(Integer.MAX_VALUE, totalItems + requirement.getCount());
            if (totalItems > 32768) {
                return List.of();
            }
            addNormalized(normalized, requirement);
        }
        return normalized;
    }

    /** 将一个需求合并进规范化材料列表。 */
    private static void addNormalized(List<ItemStack> normalized, ItemStack requirement) {
        int remaining = requirement.getCount();
        for (ItemStack existing : normalized) {
            if (!ItemStack.isSameItemSameTags(existing, requirement)) {
                continue;
            }
            int capacity = InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT - existing.getCount();
            int moved = Math.min(remaining, Math.max(0, capacity));
            existing.grow(moved);
            remaining -= moved;
            if (remaining == 0) {
                return;
            }
        }
        while (remaining > 0) {
            int moved = Math.min(remaining, InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT);
            normalized.add(requirement.copyWithCount(moved));
            remaining -= moved;
        }
    }

    /**
     * 规划从背包哪些槽位扣除材料。
     *
     * <p>只在副本上扣减，借此同时验证总量和 NBT；实际扣除延迟到 {@link #apply}。
     *
     * @return 扣除清单；材料不足或 NBT 不一致时返回 {@code null}
     */
    public static List<Removal> plan(Inventory inventory, List<ItemStack> requirements) {
        if (inventory == null || requirements == null || requirements.isEmpty()) {
            return null;
        }
        List<ItemStack> available = inventory.items.stream().map(ItemStack::copy).toList();
        List<Removal> removals = new ArrayList<>();
        for (ItemStack requirement : requirements) {
            int remaining = requirement.getCount();
            for (int index = 0; index < available.size() && remaining > 0; index++) {
                ItemStack availableStack = available.get(index);
                if (availableStack.isEmpty()
                        || !ItemStack.isSameItemSameTags(availableStack, requirement)) {
                    continue;
                }
                int moved = Math.min(remaining, availableStack.getCount());
                availableStack.shrink(moved);
                removals.add(new Removal(index, moved));
                remaining -= moved;
            }
            if (remaining > 0) {
                return null;
            }
        }
        return removals;
    }

    /** 应用已经规划成功的扣除清单。 */
    public static void apply(Inventory inventory, List<Removal> removals) {
        for (Removal removal : removals) {
            ItemStack stack = inventory.getItem(removal.slot());
            stack.shrink(removal.amount());
            inventory.setItem(removal.slot(), stack);
        }
        inventory.setChanged();
    }
}
