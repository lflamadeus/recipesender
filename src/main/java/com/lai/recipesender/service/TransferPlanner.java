package com.lai.recipesender.service;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端与服务端共用的投放规划器。
 * 把一次材料需求拆解为“从背包哪些槽扣除”和“向目标槽放多少”，规划阶段只操作副本，
 * 不会修改任何真实容器状态，因此客户端也能用它估算目标容器还能容纳多少份配方。
 */
public final class TransferPlanner {

    private TransferPlanner() {
    }

    /**
     * 创建完整的执行计划。
     * 任一环节不满足（背包不足、目标槽放不下）都会整体失败，返回 {@code null}。
     */
    public static TransferPlan create(Inventory inventory, AbstractContainerMenu menu,
                                      List<ItemStack> requirements) {
        if (inventory == null || menu == null || requirements == null || requirements.isEmpty()) {
            return null;
        }
        // 先规划扣除和投放，规划失败时不产生任何修改，避免部分成功。
        List<ItemStack> normalized = InventoryDeduction.normalize(requirements);
        if (normalized.isEmpty()) {
            return null;
        }
        List<InventoryDeduction.Removal> removals = InventoryDeduction.plan(inventory, normalized);
        if (removals == null) {
            return null;
        }
        List<SimulatedSlot> slots = createTargetSlots(inventory, menu);
        if (slots.isEmpty()) {
            return null;
        }
        List<SlotInsertion> insertions = new ArrayList<>();
        for (ItemStack requirement : normalized) {
            if (!planInsertion(requirement, slots, insertions)) {
                return null;
            }
        }
        return new TransferPlan(removals, insertions);
    }

    /** 创建当前菜单中允许投放的目标槽位模拟。 */
    private static List<SimulatedSlot> createTargetSlots(Inventory inventory, AbstractContainerMenu menu) {
        // 排除玩家背包槽位，只允许向当前菜单提供的机器或容器槽位投放。
        List<SimulatedSlot> targetSlots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container != inventory && slot.isActive()) {
                targetSlots.add(new SimulatedSlot(slot));
            }
        }
        return targetSlots;
    }

    /** 规划一个材料需求在目标槽位中的投放位置。 */
    private static boolean planInsertion(ItemStack requirement, List<SimulatedSlot> slots,
                                         List<SlotInsertion> insertions) {
        int remaining = requirement.getCount();
        for (SimulatedSlot simulated : slots) {
            if (remaining == 0) {
                break;
            }
            if (!simulated.canReceive(requirement)) {
                continue;
            }
            int moved = simulated.receive(requirement, remaining);
            if (moved > 0) {
                insertions.add(new SlotInsertion(simulated.slot, moved, requirement.copyWithCount(moved)));
                remaining -= moved;
            }
        }
        return remaining == 0;
    }

    /** 一次已验证的投放计划，必须先规划成功才能应用。 */
    public static final class TransferPlan {
        private final List<InventoryDeduction.Removal> removals;
        private final List<SlotInsertion> insertions;

        private TransferPlan(List<InventoryDeduction.Removal> removals,
                             List<SlotInsertion> insertions) {
            this.removals = removals;
            this.insertions = insertions;
        }

        /** 应用已验证的扣除和投放计划。 */
        public void apply(Inventory inventory) {
            InventoryDeduction.apply(inventory, removals);
            for (SlotInsertion insertion : insertions) {
                ItemStack current = insertion.slot().getItem();
                if (current.isEmpty()) {
                    insertion.slot().set(insertion.stack().copy());
                } else {
                    current.grow(insertion.amount());
                    insertion.slot().set(current);
                }
                insertion.slot().setChanged();
            }
            inventory.setChanged();
        }
    }

    private record SlotInsertion(Slot slot, int amount, ItemStack stack) {
    }

    /** 目标槽位的只读模拟，容量计算不会影响真实容器。 */
    private static final class SimulatedSlot {
        private final Slot slot;
        private ItemStack stack;

        private SimulatedSlot(Slot slot) {
            this.slot = slot;
            this.stack = slot.getItem().copy();
        }

        /** 判断模拟槽位能否接收指定物品。 */
        private boolean canReceive(ItemStack incoming) {
            if (!slot.mayPlace(incoming)) {
                return false;
            }
            if (stack.isEmpty()) {
                return true;
            }
            return ItemStack.isSameItemSameTags(stack, incoming)
                    && stack.getCount() < getCapacity(incoming);
        }

        /** 向模拟槽位接收指定数量的物品。 */
        private int receive(ItemStack incoming, int amount) {
            int capacity = getCapacity(incoming);
            int moved = Math.min(amount, Math.max(0, capacity - stack.getCount()));
            if (moved == 0) {
                return 0;
            }
            if (stack.isEmpty()) {
                stack = incoming.copyWithCount(moved);
            } else {
                stack.grow(moved);
            }
            return moved;
        }

        /** 计算物品在该槽位中的最大容量。 */
        private int getCapacity(ItemStack incoming) {
            return Math.min(incoming.getMaxStackSize(), slot.getMaxStackSize(incoming));
        }
    }
}
