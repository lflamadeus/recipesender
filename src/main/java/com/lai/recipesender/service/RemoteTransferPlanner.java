package com.lai.recipesender.service;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 面向远程容器的投放规划器，与 {@link TransferPlanner} 并列。
 *
 * <p>差别只有一个：{@link TransferPlanner} 的目标是当前打开的菜单，走 {@code Slot}
 * （有 {@code mayPlace}、{@code getMaxStackSize}、{@code set}）；远程目标只有
 * {@code IItemHandler}（{@code isItemValid}、{@code getSlotLimit}、{@code insertItem}）。
 * 两边的规划语义保持一致：<b>先在副本上完整模拟，模拟不通过就整单失败，绝不出现部分投放。</b>
 *
 * <p>并列组（一个主容器 + 挂在它下面的并列成员）走 {@link #applyByShares}：材料按<b>份</b>
 * 均分，每台拿到整数份；余数从主容器往后摊。某台<b>当前塞满</b>时会在每一轮被跳过，
 * 它的份额由其余还有空间的成员平摊（而不是整堆甩给下一台）；从容器只在整组都放满后
 * 才按份接收。任何情况下都<b>不拆份</b>。
 */
public final class RemoteTransferPlanner {

    /** 一次投放：把 {@code stack} 放进第 {@code slot} 个槽位。 */
    public record SlotInsert(int slot, ItemStack stack) {
    }

    /**
     * 一个落点。
     *
     * @param handler     目标的物品能力
     * @param blockEntity 目标方块实体，仅用于投放后 {@code setChanged()}；箱子/总线可能为 {@code null}
     */
    public record Target(IItemHandler handler, BlockEntity blockEntity) {
    }

    private RemoteTransferPlanner() {
    }

    /**
     * 在副本上模拟一次完整投放。
     *
     * @return 投放清单；目标装不下任何一个需求时返回 {@code null}（调用方据此整体拒绝）
     */
    public static List<SlotInsert> plan(IItemHandler handler, List<ItemStack> requirements) {
        if (handler == null || requirements == null || requirements.isEmpty()) {
            return null;
        }
        List<SimulatedSlot> slots = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            slots.add(new SimulatedSlot(handler, slot));
        }
        if (slots.isEmpty()) {
            return null;
        }
        List<SlotInsert> insertions = new ArrayList<>();
        for (ItemStack requirement : requirements) {
            if (!planInsertion(requirement, slots, insertions)) {
                return null;
            }
        }
        return insertions;
    }

    /**
     * 执行已经模拟通过的投放清单。
     *
     * <p>仍然逐个用 {@code insertItem(slot, stack, false)} 的真实返回值计数：个别实现会在
     * {@code isItemValid} 与 {@code insertItem} 之间给出不一致的答案（过滤器、并行上限等），
     * 因此调用方必须拿实际插入量去扣背包，而不是拿计划量。
     *
     * @return 与 {@code insertions} 一一对应的「实际插入量」列表；没插进去的位置是 {@link ItemStack#EMPTY}
     */
    public static List<ItemStack> apply(IItemHandler handler, List<SlotInsert> insertions) {
        List<ItemStack> applied = new ArrayList<>(insertions.size());
        for (SlotInsert insertion : insertions) {
            ItemStack remainder = handler.insertItem(insertion.slot(), insertion.stack().copy(), false);
            int moved = insertion.stack().getCount() - remainder.getCount();
            applied.add(moved > 0 ? insertion.stack().copyWithCount(moved) : ItemStack.EMPTY);
        }
        return applied;
    }

    // ------------------------------------------------------------ 并列组均分

    /** 按 {@link ItemStack#isSameItemSameTags} 合并同一物品的多个分片（采集器单项超 4096 会切块）。 */
    public static List<ItemStack> mergeByItem(List<ItemStack> stacks) {
        List<ItemStack> merged = new ArrayList<>();
        if (stacks == null) {
            return merged;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean appended = false;
            for (int index = 0; index < merged.size(); index++) {
                ItemStack existing = merged.get(index);
                if (ItemStack.isSameItemSameTags(existing, stack)) {
                    merged.set(index, existing.copyWithCount(existing.getCount() + stack.getCount()));
                    appended = true;
                    break;
                }
            }
            if (!appended) {
                merged.add(stack.copy());
            }
        }
        return merged;
    }

    /**
     * 把总份数按成员数均分。
     *
     * <p>每台先各拿 {@code ⌊份数 ÷ 台数⌋} 份，均分不尽的余数<b>从主容器往后依次多分一份</b>
     * （主容器优先，然后是第一个并列成员，依此类推）。不提供玩家自定义优先级：顺序就是绑定顺序。
     *
     * <p>这只是「每台都有足够空间」时的理想配额；真正投放走 {@link #applyByShares}，
     * 它会在此基础上按各台<b>当前剩余容量</b>把分不完的份轮流摊给还有空间的成员。
     *
     * @return 长度等于 {@code memberCount} 的配额数组
     */
    public static int[] splitByShares(int batches, int memberCount) {
        int[] shares = new int[Math.max(0, memberCount)];
        if (batches <= 0 || memberCount <= 0) {
            return shares;
        }
        int base = batches / memberCount;
        int remainder = batches % memberCount;
        for (int index = 0; index < memberCount; index++) {
            shares[index] = base + (index < remainder ? 1 : 0);
        }
        return shares;
    }

    /**
     * 把整份需求拆成「每一份的材料」。
     *
     * <p>正常情况每份完全一样；某一物品的总量不能被份数整除时（通配符输入项可能由多种物品凑成），
     * 余量并入<b>最后一份</b>，这样总量永远守恒、也不会凭空多出材料。
     *
     * @return 长度为 {@code batches} 的列表，每一项是该份所需的材料
     */
    public static List<List<ItemStack>> toBatches(List<ItemStack> requirements, int batches) {
        List<List<ItemStack>> result = new ArrayList<>();
        if (batches <= 0) {
            return result;
        }
        List<ItemStack> perBatch = new ArrayList<>();
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : mergeByItem(requirements)) {
            int share = stack.getCount() / batches;
            if (share > 0) {
                perBatch.add(stack.copyWithCount(share));
            }
            int rest = stack.getCount() - share * batches;
            if (rest > 0) {
                leftover.add(stack.copyWithCount(rest));
            }
        }
        for (int index = 0; index < batches; index++) {
            result.add(perBatch);
        }
        if (!leftover.isEmpty()) {
            List<ItemStack> last = new ArrayList<>(perBatch);
            last.addAll(leftover);
            result.set(batches - 1, List.copyOf(last));
        }
        return result;
    }

    /**
     * 一次按份均分投放的结果。
     *
     * @param inserted        实际插进目标的材料（未按物品合并，用于扣背包与统计份数）
     * @param overflowBatches 其中落到<b>从容器</b>的份数（整组都放满后溢出过去的那部分）
     */
    public record Applied(List<ItemStack> inserted, int overflowBatches) {
    }

    /**
     * 按份均分投放：主容器 + 并列成员先分，剩下的余量再交给从容器。
     *
     * <p>逐份尝试，<b>整份模拟通过才落下去</b>；某台放不下自己那份时这一份留在队里，
     * 顺延给下一个落点。因此任何一台都只会收到整数份，不会出现「塞一半」。
     *
     * <p>分三步：先按 {@link #splitByShares 理想配额} 各分一轮，再把分不完的份在组内
     * {@link #roundRobin 轮流摊}，最后才轮到从容器。第二步是关键——一台已经塞满的机器
     * 不该把自己的份额整堆甩给紧挨着的下一台，那会让下一台独占大头。
     *
     * @param members   参与均分的落点：主容器在前，并列成员按绑定顺序
     * @param overflow  溢出落点（从容器），按绑定顺序；整组都放满后才轮到它们
     * @return 实际插进目标的材料 + 溢出到从容器的份数
     */
    public static Applied applyBySharesDetailed(List<Target> members, List<Target> overflow,
                                                List<ItemStack> requirements, int batches) {
        List<ItemStack> inserted = new ArrayList<>();
        if (members == null || members.isEmpty() || requirements == null || requirements.isEmpty()
                || batches <= 0) {
            return new Applied(inserted, 0);
        }
        List<List<ItemStack>> batchMaterials = toBatches(requirements, batches);
        Deque<Integer> pending = new ArrayDeque<>();
        for (int index = 0; index < batchMaterials.size(); index++) {
            // 通配符输入项可能把一种材料摊到多种物品上，导致某一份算不出整数份额而变空。
            // 这种份没有任何材料要放，直接跳过，否则它会把后面所有落点都堵住。
            if (!batchMaterials.get(index).isEmpty()) {
                pending.addLast(index);
            }
        }
        if (pending.isEmpty()) {
            return new Applied(inserted, 0);
        }
        takeByQuota(members, splitByShares(pending.size(), members.size()), batchMaterials, pending, inserted);
        // 配额分完还有剩：说明某台装不下自己那份，剩下的份在还有空间的成员之间轮流摊。
        roundRobin(members, batchMaterials, pending, inserted);
        // 整组都放满后才轮到从容器；从容器同样按份收，一份塞不下就不塞。
        // 从容器收到的份数单独记下来，回执里要告诉玩家「材料去哪儿了」。
        int overflowed = roundRobin(overflow, batchMaterials, pending, inserted);
        return new Applied(inserted, overflowed);
    }

    /** 只要投放结果、不要溢出统计时的简写。 */
    public static List<ItemStack> applyByShares(List<Target> members, List<Target> overflow,
                                                List<ItemStack> requirements, int batches) {
        return applyBySharesDetailed(members, overflow, requirements, batches).inserted();
    }

    /** 逐个落点消耗队首的份；{@code shares} 为 {@code null} 表示不限配额。 */
    private static void takeByQuota(List<Target> targets, int[] shares, List<List<ItemStack>> batchMaterials,
                                    Deque<Integer> pending, List<ItemStack> inserted) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (int index = 0; index < targets.size(); index++) {
            IItemHandler handler = targets.get(index).handler();
            if (handler == null) {
                continue;
            }
            int quota = shares == null ? Integer.MAX_VALUE : shares[index];
            while (quota > 0 && !pending.isEmpty()) {
                List<SlotInsert> plan = plan(handler, batchMaterials.get(pending.peekFirst()));
                if (plan == null || plan.isEmpty()) {
                    // 这一份装不下：留在队里顺延给下一个落点。
                    break;
                }
                for (ItemStack stack : apply(handler, plan)) {
                    if (!stack.isEmpty()) {
                        inserted.add(stack);
                    }
                }
                pending.removeFirst();
                quota--;
            }
        }
    }

    /**
     * 让一组落点轮流取份，直到份发完或所有落点都放不下。
     *
     * <p>轮流取是「按当前容量分配」的关键：某台塞满之后会在每一轮被就地跳过，
     * 它本该收的份由其余还有空间的成员平摊，而不是整堆压到紧挨着的下一台身上。
     *
     * <p>某台对队首这一份装不下就标记为「已满」并不再尝试。这是可靠的：除了最后一份
     * （带着除不尽的余量、只多不少）以外所有份的材料完全相同，而队列按顺序消费，
     * 所以最后一份只会出现在所有普通份之后——一台装不下普通份，就必然装不下最后一份。
     *
     * @return 本轮真正放进这组落点的份数（调用方用它统计有多少份溢出到了从容器）
     */
    private static int roundRobin(List<Target> targets, List<List<ItemStack>> batchMaterials,
                                  Deque<Integer> pending, List<ItemStack> inserted) {
        if (targets == null || targets.isEmpty() || pending.isEmpty()) {
            return 0;
        }
        int placed = 0;
        boolean[] saturated = new boolean[targets.size()];
        int available = targets.size();
        while (!pending.isEmpty() && available > 0) {
            boolean progressed = false;
            for (int index = 0; index < targets.size() && !pending.isEmpty(); index++) {
                if (saturated[index]) {
                    continue;
                }
                IItemHandler handler = targets.get(index).handler();
                List<SlotInsert> plan = handler == null
                        ? null : plan(handler, batchMaterials.get(pending.peekFirst()));
                if (plan == null || plan.isEmpty()) {
                    saturated[index] = true;
                    available--;
                    continue;
                }
                for (ItemStack stack : apply(handler, plan)) {
                    if (!stack.isEmpty()) {
                        inserted.add(stack);
                    }
                }
                pending.removeFirst();
                placed++;
                progressed = true;
            }
            if (!progressed) {
                return placed;
            }
        }
        return placed;
    }

    /** 规划一个需求在目标槽位中的投放位置。 */
    private static boolean planInsertion(ItemStack requirement, List<SimulatedSlot> slots,
                                         List<SlotInsert> insertions) {
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
                insertions.add(new SlotInsert(simulated.slot, requirement.copyWithCount(moved)));
                remaining -= moved;
            }
        }
        return remaining == 0;
    }

    /** 目标槽位的只读模拟，容量计算不会影响真实容器。 */
    private static final class SimulatedSlot {
        private final IItemHandler handler;
        private final int slot;
        private ItemStack stack;

        private SimulatedSlot(IItemHandler handler, int slot) {
            this.handler = handler;
            this.slot = slot;
            ItemStack current = handler.getStackInSlot(slot);
            this.stack = current == null ? ItemStack.EMPTY : current.copy();
        }

        /** 判断模拟槽位能否接收指定物品。 */
        private boolean canReceive(ItemStack incoming) {
            if (!handler.isItemValid(slot, incoming)) {
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
            return Math.min(incoming.getMaxStackSize(), handler.getSlotLimit(slot));
        }
    }
}
