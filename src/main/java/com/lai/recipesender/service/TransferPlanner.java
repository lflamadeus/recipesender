package com.lai.recipesender.service;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端与服务端共用的投放规划器。
 * 把一次材料需求拆解为“从背包哪些槽扣除”和“向目标槽放多少”，规划阶段只操作副本，
 * 不会修改任何真实容器状态，因此客户端也能用它估算目标容器还能容纳多少份配方。
 *
 * <p>投放本身（{@link TransferPlan#apply}）1.0.38 起不再直写 {@code Slot.set}：优先调用容器
 * 自己的插入口（LDLib 槽位的 {@code IItemTransfer}，或真容器上的 {@code InvWrapper}），
 * 只有两者都认不出来的槽位才退回老写法。理由与后果见 {@link #resolveInserter} 与
 * {@link TransferPlan#apply}。
 */
public final class TransferPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    /** LDLib 的物品转移器接口（格雷机器 GUI 的槽位背后就是它）；LDLib 不在时保持 null。 */
    private static final Class<?> ITEM_TRANSFER_INTERFACE = resolveItemTransferInterface();

    /** {@code IItemTransfer.insertItem(int, ItemStack, boolean)}，接口上的 default 方法。 */
    private static final Method ITEM_TRANSFER_INSERT = resolveItemTransferInsert();

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
                insertions.add(new SlotInsertion(simulated.slot, simulated.inserter,
                        requirement.copyWithCount(moved)));
                remaining -= moved;
            }
        }
        return remaining == 0;
    }

    /**
     * 解析一个目标槽位的真实插入口。
     * <p>
     * 直写 {@code Slot.set} 会绕过容器自己的判断（{@code IItemHandler.insertItem} 里的输入限制、
     * 过滤器、并行上限、容量规则），而这些判断正是「模拟说装得下、实际收不下」的来源；绕过它们的
     * 代价不只是数字不准，还会让机器上的过滤器形同虚设。按下面顺序找一个真正的插入口：
     * <ol>
     *     <li>LDLib 槽位（{@code SlotWidget$WidgetSlotItemTransfer}，格雷机器的 GUI 都是它）：
     *         反射 {@code getItemHandler()} 拿到 {@code IItemTransfer}，优先当 Forge 的
     *         {@code IItemHandler} 用（GT 的 {@code ICustomItemStackHandler} 本身就是它），
     *         否则按接口反射调 {@code insertItem}。槽位号用 {@code slot.getSlotIndex()}——
     *         LDLib 构造 {@code Slot} 时传的就是转移器里的下标。</li>
     *     <li>普通容器槽位：{@code slot.container} 就是真容器时用 {@code InvWrapper} 走原版
     *         {@code insertItem}。容量检查顺手把 LDLib 的空壳容器挡在外面
     *         （它是 {@code new SimpleContainer(0)}，容量为 0）。</li>
     *     <li>都认不出来：保留老写法直写槽位。这条路只剩既不是 LDLib 槽位、容器又不是真容器的
     *         情况，现实里基本见不到。</li>
     * </ol>
     */
    private static Inserter resolveInserter(Slot slot) {
        Inserter transfer = resolveItemTransferInserter(slot);
        if (transfer != null) {
            return transfer;
        }
        Container container = slot.container;
        if (container != null && container.getContainerSize() > slot.getSlotIndex()) {
            IItemHandler wrapper = new InvWrapper(container);
            int index = slot.getSlotIndex();
            if (index >= 0 && index < wrapper.getSlots()) {
                return stack -> inserted(wrapper, index, stack);
            }
        }
        return stack -> {
            ItemStack current = slot.getItem();
            if (current.isEmpty()) {
                slot.set(stack.copy());
            } else {
                ItemStack merged = current.copy();
                merged.grow(stack.getCount());
                slot.set(merged);
            }
            slot.setChanged();
            return stack.getCount();
        };
    }

    /** 解析 LDLib 槽位背后的物品转移器插入口；不是 LDLib 槽位时返回 null。 */
    private static Inserter resolveItemTransferInserter(Slot slot) {
        Object transfer = itemTransferOf(slot);
        if (transfer == null) {
            return null;
        }
        int index = slot.getSlotIndex();
        if (transfer instanceof IItemHandler handler) {
            if (index < 0 || index >= handler.getSlots()) {
                return null;
            }
            return stack -> inserted(handler, index, stack);
        }
        if (ITEM_TRANSFER_INSERT == null) {
            return null;
        }
        return stack -> {
            try {
                Object remainder = ITEM_TRANSFER_INSERT.invoke(transfer, index, stack, false);
                return remainder instanceof ItemStack left
                        ? stack.getCount() - left.getCount()
                        : stack.getCount();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("向槽位 {} 的物品转移器插入材料失败", index, exception);
                return 0;
            }
        };
    }

    /** 读 LDLib 槽位背后的 {@code IItemTransfer}；不是这种槽位时返回 null。 */
    private static Object itemTransferOf(Slot slot) {
        try {
            Method getter = slot.getClass().getMethod("getItemHandler");
            Object transfer = getter.invoke(slot);
            return ITEM_TRANSFER_INTERFACE != null && ITEM_TRANSFER_INTERFACE.isInstance(transfer)
                    ? transfer
                    : null;
        } catch (NoSuchMethodException exception) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("读取槽位 {} 的物品转移器失败", slot.getClass().getName(), exception);
            return null;
        }
    }

    /** 往 {@code IItemHandler} 的指定槽位插入，返回真正收下的数量。 */
    private static int inserted(IItemHandler handler, int slot, ItemStack stack) {
        ItemStack remainder = handler.insertItem(slot, stack, false);
        if (remainder == null || remainder.isEmpty()) {
            return stack.getCount();
        }
        return Math.max(0, stack.getCount() - remainder.getCount());
    }

    private static Class<?> resolveItemTransferInterface() {
        try {
            return Class.forName("com.lowdragmc.lowdraglib.side.item.IItemTransfer");
        } catch (ClassNotFoundException | LinkageError exception) {
            return null;
        }
    }

    private static Method resolveItemTransferInsert() {
        if (ITEM_TRANSFER_INTERFACE == null) {
            return null;
        }
        try {
            return ITEM_TRANSFER_INTERFACE.getMethod("insertItem", int.class, ItemStack.class,
                    boolean.class);
        } catch (NoSuchMethodException | RuntimeException exception) {
            return null;
        }
    }

    /** 一个目标槽位的真实插入口，返回实际收下的数量。 */
    private interface Inserter {
        int insert(ItemStack stack);
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

        /**
         * 应用已验证的投放计划：先投放，再按<b>真正收下的量</b>扣背包。
         * <p>
         * 顺序不能反过来。模拟用的是 {@code Slot.mayPlace} 加容量算术，而容器自己的
         * {@code insertItem} 可能给出不同的答案（格雷机器「同一种材料最多占一组」、总线过滤器、
         * 并行上限），先扣背包再直写槽位的话，容器一拒收材料就凭空消失——背包已经扣了，容器又没收到。
         * 现在收下多少扣多少，退回来的部分自然留在背包里；最坏情况是容器收下了而背包扣除计划
         * 意外失败（计划阶段已确认拿得出，理论上不会发生），那时宁可不扣，并留一条警告。
         */
        public void apply(Inventory inventory) {
            List<ItemStack> applied = new ArrayList<>();
            for (SlotInsertion insertion : insertions) {
                int moved = insertion.inserter().insert(insertion.stack().copy());
                if (moved <= 0) {
                    continue;
                }
                applied.add(insertion.stack().copyWithCount(moved));
            }
            List<InventoryDeduction.Removal> actual = applied.isEmpty()
                    ? List.of()
                    : InventoryDeduction.plan(inventory, applied);
            if (actual == null) {
                LOGGER.warn("投放成功但背包扣除计划失败，本次不扣除任何材料（材料留在背包）");
                actual = List.of();
            }
            InventoryDeduction.apply(inventory, actual);
            inventory.setChanged();
        }
    }

    private record SlotInsertion(Slot slot, Inserter inserter, ItemStack stack) {
    }

    /** 目标槽位的只读模拟，容量计算不会影响真实容器。 */
    private static final class SimulatedSlot {
        private final Slot slot;
        private final Inserter inserter;
        private ItemStack stack;

        private SimulatedSlot(Slot slot) {
            this.slot = slot;
            this.inserter = resolveInserter(slot);
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
