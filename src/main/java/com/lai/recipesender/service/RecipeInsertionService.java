package com.lai.recipesender.service;

import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 在服务端主线程中完成验证、扣除和菜单同步，保证一次投放的原子性。
 * 所有外部输入都视为不可信，客户端的材料统计只用于界面提示，不能替代服务端校验。
 */
public final class RecipeInsertionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private RecipeInsertionService() {
    }

    /** 在服务端验证并原子执行一次正向材料投放。 */
    public static void insert(ServerPlayer player, InsertRecipeItemsPacket packet) {
        // 先确认玩家和菜单仍然对应，防止延迟数据包写入错误的容器。
        if (player == null || packet.requirements().isEmpty()) {
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != packet.containerId()) {
            return;
        }

        try {
            // 先完整规划扣除和投放，规划失败时不执行任何修改，避免部分成功。
            PlanResult result = TransferPlan.create(player, menu, packet.requirements());
            if (!result.success()) {
                return;
            }
            result.plan().apply(player.getInventory());
            menu.broadcastChanges();
        } catch (RuntimeException exception) {
            // 第三方机器菜单可能在关闭或重载时改变插槽，单次失败不能破坏服务器线程。
            LOGGER.warn("Failed to insert recipe ingredients for {}", player.getGameProfile().getName(), exception);
        }
    }

    private static final class TransferPlan {
        private final List<InventoryRemoval> removals;
        private final List<SlotInsertion> insertions;

        private TransferPlan(List<InventoryRemoval> removals, List<SlotInsertion> insertions) {
            this.removals = removals;
            this.insertions = insertions;
        }

        /** 创建扣除背包并填充目标容器的完整执行计划。 */
        private static PlanResult create(ServerPlayer player, AbstractContainerMenu menu,
                                         List<ItemStack> requirements) {
            // 规范化同类物品后统一规划，兼容一个配方输入跨越多个背包槽位的情况。
            List<ItemStack> normalized = normalize(requirements);
            if (normalized.isEmpty()) {
                return PlanResult.failure("服务端收到的物品需求为空或格式非法");
            }

            List<InventoryRemoval> removals = planInventoryRemoval(player.getInventory(), normalized);
            if (removals == null) {
                return PlanResult.failure("背包物品不足，或物品 NBT 与请求不一致");
            }

            List<SimulatedSlot> slots = createTargetSlots(player.getInventory(), menu);
            if (slots.isEmpty()) {
                return PlanResult.failure("当前菜单没有可投放的目标槽");
            }
            List<SlotInsertion> insertions = new ArrayList<>();
            for (ItemStack requirement : normalized) {
                if (!planInsertion(requirement, slots, insertions)) {
                    return PlanResult.failure("目标槽无法容纳完整物品需求："
                            + requirement.getHoverName().getString() + " x" + requirement.getCount());
                }
            }
            return PlanResult.success(new TransferPlan(removals, insertions));
        }

        /** 合并并校验客户端提交的物品需求。 */
        private static List<ItemStack> normalize(List<ItemStack> requirements) {
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

        /** 规划从背包哪些槽位扣除材料。 */
        private static List<InventoryRemoval> planInventoryRemoval(Inventory inventory, List<ItemStack> requirements) {
            // 只在副本上扣减，借此同时验证总量和 NBT；实际扣除延迟到 apply 阶段。
            List<ItemStack> available = inventory.items.stream().map(ItemStack::copy).toList();
            List<InventoryRemoval> removals = new ArrayList<>();
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
                    removals.add(new InventoryRemoval(index, moved));
                    remaining -= moved;
                }
                if (remaining > 0) {
                    return null;
                }
            }
            return removals;
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

        /** 应用已验证的扣除和投放计划。 */
        private void apply(Inventory inventory) {
            for (InventoryRemoval removal : removals) {
                ItemStack stack = inventory.getItem(removal.slot());
                stack.shrink(removal.amount());
                inventory.setItem(removal.slot(), stack);
            }
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

    private record PlanResult(TransferPlan plan, String failure) {
        /** 创建成功的执行计划结果。 */
        private static PlanResult success(TransferPlan plan) {
            return new PlanResult(plan, "");
        }

        /** 创建失败的执行计划结果。 */
        private static PlanResult failure(String reason) {
            return new PlanResult(null, reason);
        }

        /** 判断计划是否创建成功。 */
        private boolean success() {
            return plan != null;
        }
    }

    private record InventoryRemoval(int slot, int amount) {
    }

    private record SlotInsertion(Slot slot, int amount, ItemStack stack) {
    }

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
