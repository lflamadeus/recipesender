package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.InputLimitSupport;
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
 *
 * <p>目标的特殊规则都要在模拟里照做，否则「模拟说装得下、{@code insertItem} 却退回来」。
 * 目前有两处：格雷机器输入槽的<b>防止堵料</b>（{@link Target#inputLimited()}，同一种物品只能占
 * 一个槽，见 {@link #plan}），以及槽位自己的 {@code isItemValid} / {@code getSlotLimit}。
 */
public final class RemoteTransferPlanner {

    /** 一次投放：把 {@code stack} 放进第 {@code slot} 个槽位。 */
    public record SlotInsert(int slot, ItemStack stack) {
    }

    /**
     * 一个落点。
     *
     * @param handler      目标的物品能力
     * @param blockEntity  目标方块实体，仅用于投放后 {@code setChanged()}；箱子/总线可能为 {@code null}
     * @param inputLimited 目标是否开着「同种物品最多占用一个输入槽」——格雷的<b>防止堵料</b>开关，
     *                     由调用方用 {@link InputLimitSupport#isInputLimited} 现读（每台机器可以随时改，
     *                     所以不能缓存）。模拟必须照它算，否则容量会成倍高估，见 {@link #plan}
     */
    public record Target(IItemHandler handler, BlockEntity blockEntity, boolean inputLimited) {
    }

    private RemoteTransferPlanner() {
    }

    /**
     * 在副本上模拟一次完整投放。
     *
     * <p>{@code inputLimited} 为 {@code true} 时按格雷「防止堵料」的规则算：<b>同一种物品只能占一个槽</b>
     * （只比 {@code Item}，与 {@code CustomItemStackHandler#limitedInsert} 完全一致）。这条规则必须照做，
     * 因为真实处理器是在它<b>自己的</b>槽位数组上判断的，看不到我们副本里刚虚拟放进去的材料；不照做就会
     * 把同一物品算进好几个空槽，容量成倍高估，最终表现为「模拟说装得下、{@code insertItem} 把材料退回来」，
     * 差额还会变成残片（见 1.0.31 / 1.0.39）。
     *
     * @return 投放清单；目标装不下任何一个需求时返回 {@code null}（调用方据此整体拒绝）
     */
    public static List<SlotInsert> plan(IItemHandler handler, List<ItemStack> requirements,
                                        boolean inputLimited) {
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
            if (!planInsertion(requirement, slots, insertions, inputLimited)) {
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
     * 按「完整送达的份数」统计实际插进目标的份数。
     *
     * <p>份是原子单位：某项材料只要少一份，这一份就不能算送达，因此取各项的最小值。
     * 口径与投放路径完全一致（投放按份走），所以这个数字可以直接拿去和玩家按出来的份数比对。
     *
     * <p>直达与绑定发送的回执都用这一条口径，溢出到从容器的份数也由它折算——
     * <b>用实际插入量折算，而不是数「尝试了几次」</b>：落点只收下一部分时那一次不能算一整份。
     *
     * @param requirements 本次要投的全部材料（总量 = 每份用量 × {@code batches}）
     * @param inserted     实际插进目标的材料，未按物品合并
     * @param batches      玩家要求的份数
     */
    public static int countBatches(List<ItemStack> requirements, List<ItemStack> inserted, int batches) {
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
     * 顺延给下一个落点。
     *
     * <p><b>「模拟说装得下、insertItem 实际只收下一部分」也要当成这一台满了。</b>
     * 个别实现（格雷机器的输入槽、带过滤器的总线）会在 {@code isItemValid} 与
     * {@code insertItem} 之间给出不一致的答案：模拟通过、真实插入却把材料退回来。
     * 这时差额既不能凭空算作已投放，也不能就这么留在原地——它必须顺延给下一个落点，
     * 否则材料永远到不了从容器（表现为「机器没满、箱子也没收到」）。见 {@link #subtract}。
     *
     * <p>分三步：先按 {@link #splitByShares 理想配额} 各分一轮，再把分不完的份在组内
     * {@link #roundRobin 轮流摊}，最后才轮到从容器。第二步是关键——一台已经塞满的机器
     * 不该把自己的份额整堆甩给紧挨着的下一台，那会让下一台独占大头。
     *
     * <p><b>每个成员自己的从容器优先于「顺延给下一个成员」。</b>挂在并列成员下的从容器是给那一台
     * 机器扩容用的箱子：独立模式下机器收不下的余量先问它自己的箱子，只有连自己的箱子也满了，
     * 这一份才留给下一个落点。否则玩家给某台机器配了扩容箱、材料却全被别的机器吃掉，
     * 扩容就等于没配。
     *
     * @param members        参与均分的落点：主容器在前，并列成员按绑定顺序
     * @param memberOverflow 与 {@code members} 下标一一对应的「这一台自己的从容器」；可为 {@code null}
     * @param overflow       组级溢出落点（挂在主容器下的从容器），按绑定顺序；整组都放满后才轮到它们
     * @return 实际插进目标的材料 + 溢出到从容器的份数（按实际插入量折算，不是数尝试次数）
     */
    public static Applied applyBySharesDetailed(List<Target> members, List<List<Target>> memberOverflow,
                                                List<Target> overflow,
                                                List<ItemStack> requirements, int batches) {
        Placement placement = placeByShares(members, memberOverflow, overflow, requirements, batches);
        return new Applied(placement.inserted(), placement.overflowBatches());
    }

    /**
     * 一次按份均分投放的结果，额外带上<b>每个成员各自收下了几份</b>。
     *
     * <p>{@code memberBatches} 与 {@code members} 一一对应，由 {@link #countBatches} 从
     * <b>该台实际插进去的材料</b>折算而来，而不是「它被分到了几份」。这个区别是 1.0.39 修的：
     * 落点只收下一部分时，那一份会带着差额顺延给下一个落点，若按「谁接下了这条队列项就记一份」
     * 记账，只拿到残片的机器也会被记成一整份，流体就跟着多灌——玩家看到的是「材料只发进去
     * 一部分，流体却灌满了这台机器」。
     *
     * <p>流体模式靠它把流体按「这台机器实际收下了几份」分下去——否则第一台会把整块流体一次灌满，
     * 后面的机器只有材料没有流体，结果谁也没法开工。
     *
     * @param inserted        实际插进目标的材料（未按物品合并，用于扣背包与统计份数）
     * @param overflowBatches 其中落到<b>从容器</b>的份数（整组都放满后溢出过去的那部分）
     * @param memberBatches   每个成员按实际插入材料折算出的<b>完整</b>份数，下标与 {@code members} 对应
     */
    public record Placement(List<ItemStack> inserted, int overflowBatches, int[] memberBatches) {
    }

    /**
     * 按份均分投放，并把每个成员收下的份数报出来（见 {@link Placement}）。
     *
     * <p>投放语义与 {@link #applyBySharesDetailed} 完全相同，只是多记一份账。
     */
    public static Placement placeByShares(List<Target> members, List<List<Target>> memberOverflow,
                                          List<Target> overflow,
                                          List<ItemStack> requirements, int batches) {
        List<ItemStack> inserted = new ArrayList<>();
        List<List<ItemStack>> memberInserted = new ArrayList<>();
        for (int index = 0; index < (members == null ? 0 : members.size()); index++) {
            memberInserted.add(new ArrayList<>());
        }
        if (members == null || members.isEmpty() || requirements == null || requirements.isEmpty()
                || batches <= 0) {
            return new Placement(inserted, 0, memberBatches(requirements, memberInserted, batches));
        }
        // 队列里装的是「一份材料」本身，不是份号：某个落点只收下一部分时，差额要带着
        // 「还差多少」重新入队顺延给下一个落点，所以元素必须能表示不完整的一份。
        Deque<List<ItemStack>> pending = new ArrayDeque<>();
        for (List<ItemStack> batch : toBatches(requirements, batches)) {
            // 通配符输入项可能把一种材料摊到多种物品上，导致某一份算不出整数份额而变空。
            // 这种份没有任何材料要放，直接跳过，否则它会把后面所有落点都堵住。
            if (!batch.isEmpty()) {
                pending.addLast(batch);
            }
        }
        if (pending.isEmpty()) {
            return new Placement(inserted, 0, memberBatches(requirements, memberInserted, batches));
        }
        // 落到「某台机器自己的从容器」的份数单独记一份账：回执里它算溢出（材料进了箱子、
        // 没进机器），但不计入 memberBatches——流体按「这台机器实际收下几份」灌，箱子里的不算。
        List<ItemStack> overflowInserted = new ArrayList<>();
        takeByQuota(members, memberOverflow, splitByShares(pending.size(), members.size()), pending,
                inserted, overflowInserted, memberInserted);
        // 配额分完还有剩：说明某台装不下自己那份，剩下的份在还有空间的成员之间轮流摊。
        roundRobin(members, memberOverflow, pending, inserted, null, overflowInserted, memberInserted);
        // 整组都放满后才轮到组级从容器；从容器同样按份收，一份塞不下就不塞。
        roundRobin(overflow, null, pending, inserted, overflowInserted, null, null);
        return new Placement(inserted, countBatches(requirements, overflowInserted, batches),
                memberBatches(requirements, memberInserted, batches));
    }

    /**
     * 把每个落点实际插入的材料折算成「完整份数」。
     *
     * <p>口径与回执完全一致（{@link #countBatches} 对各项材料取最小值）：只拿到残片的落点算 0 份，
     * 所以它拿不到流体。
     */
    private static int[] memberBatches(List<ItemStack> requirements, List<List<ItemStack>> memberInserted,
                                       int batches) {
        int[] counts = new int[memberInserted.size()];
        if (requirements == null || requirements.isEmpty() || batches <= 0) {
            return counts;
        }
        for (int index = 0; index < counts.length; index++) {
            counts[index] = countBatches(requirements, memberInserted.get(index), batches);
        }
        return counts;
    }

    /** 只要投放结果、不要溢出统计时的简写。 */
    public static List<ItemStack> applyByShares(List<Target> members, List<List<Target>> memberOverflow,
                                                List<Target> overflow,
                                                List<ItemStack> requirements, int batches) {
        return applyBySharesDetailed(members, memberOverflow, overflow, requirements, batches).inserted();
    }

    /**
     * 这一组落点一次最多还能<b>完整</b>收下几份。
     *
     * <p>直达是「先把材料抽出来、再投放」，抽出来却投不进去的部分必须还回去：还回背包要占格子、
     * 还回周围容器可能喂给无关的机器、背包放不下还会掉在脚边。所以在抽之前先问一句
     * 「目标到底吃得下几份」，只抽真投得进去的量——这就是流体侧 {@code FluidTransferPlanner#maxBatches}
     * 一直以来的做法，物品侧此前缺这一步。
     *
     * <p>答案靠二分：{@link #placeByShares} 的语义是「某一趟装不下就顺延给下一个落点」，
     * 份数越少越容易装得下，单调性成立，所以可以直接二分出最大的可行份数。
     *
     * <p><b>这个值是上界，不是承诺。</b>模拟已经照做目标自己的规则（{@code isItemValid}、
     * 槽位容量，以及 1.0.39 起格雷机器的「防止堵料」），但真实 {@code insertItem} 仍可能更严
     * （带过滤器的总线、其它模组在插入口上加的判断），所以按它封顶不会让份数变少，只会去掉
     * 本来就要还回去的那部分；剩下那点差额仍由调用方的余料兜底接住。
     *
     * <p>封顶的另一个用处是让流体有个可靠的分母：{@link #Placement#memberBatches()} 按每台
     * 实际收下的材料算份数，灌流体时才不会「只收到残片的机器被灌了一整份」。
     *
     * @param members    参与均分的落点（主容器 + 并列成员），顺序与投放时一致
     * @param memberOverflow 与 {@code members} 下标一一对应的「这一台自己的从容器」；可为 {@code null}
     * @param overflow   组级溢出落点（挂在主容器下的从容器）
     * @param requirements <b>一份</b>配方的材料（内部按份数放大）
     * @param upperBound 已经由源与流体定下的份数上限，二分不会超过它
     * @return 模拟上还能完整收下的份数，取值 {@code 0} 到 {@code upperBound}
     */
    public static int maxBatches(List<Target> members, List<List<Target>> memberOverflow,
                                 List<Target> overflow,
                                 List<ItemStack> requirements, int upperBound) {
        if (members == null || members.isEmpty() || requirements == null || requirements.isEmpty()
                || upperBound <= 0) {
            return 0;
        }
        int low = 0;
        int high = upperBound;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (fits(shadow(members), shadowEach(memberOverflow), shadow(overflow), requirements, middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** 在只读副本上试放 {@code batches} 份，看是不是每一份都落得下去。 */
    private static boolean fits(List<Target> members, List<List<Target>> memberOverflow,
                                List<Target> overflow, List<ItemStack> requirements, int batches) {
        List<ItemStack> scaled = new ArrayList<>(requirements.size());
        for (ItemStack stack : requirements) {
            scaled.add(stack.copyWithCount(stack.getCount() * batches));
        }
        Placement placement = placeByShares(members, memberOverflow, overflow, scaled, batches);
        return countBatches(scaled, placement.inserted(), batches) >= batches;
    }

    /**
     * 把落点换成只读副本，好让同一套 {@link #placeByShares} 跑在模拟上。
     *
     * <p>{@code blockEntity} 一律传 {@code null}：试放阶段不该碰任何真实方块实体
     * （投放后的 {@code setChanged()} 由真实投放那一趟负责）。
     */
    private static List<Target> shadow(List<Target> targets) {
        List<Target> shadows = new ArrayList<>();
        if (targets == null) {
            return shadows;
        }
        for (Target target : targets) {
            if (target.handler() != null) {
                shadows.add(new Target(new SimulatedHandler(target.handler()), null,
                        target.inputLimited()));
            }
        }
        return shadows;
    }

    /**
     * 逐成员把他自己的从容器也换成只读副本，下标与 {@code members} 保持一致。
     *
     * <p>下标必须对齐：投放时靠下标找「这一台的箱子」，模拟时错位就等于拿别人的箱子试放，
     * 二分出来的份数会偏大——真实的 {@code insertItem} 收不下，材料就得还回背包。
     */
    private static List<List<Target>> shadowEach(List<List<Target>> memberOverflow) {
        if (memberOverflow == null || memberOverflow.isEmpty()) {
            return List.of();
        }
        List<List<Target>> shadows = new ArrayList<>(memberOverflow.size());
        for (List<Target> targets : memberOverflow) {
            shadows.add(shadow(targets));
        }
        return shadows;
    }

    /**
     * 逐个落点消耗队首的份；{@code shares} 为 {@code null} 表示不限配额。
     *
     * <p>某个落点放不下（或只收下一部分）时，先问它<b>自己的从容器</b>——那是专给这一台机器
     * 扩容的箱子，优先级必须高于「顺延给下一个落点」，否则扩容了也轮不到它用。连自己的箱子
     * 也放不下的差额才重新入队，留给下一个落点。
     *
     * @param memberOverflow   非 null 且下标对齐时，把每个落点<b>自己的从容器</b>也当落点用
     * @param overflowInserted 非 null 时收进「落进箱子里的材料」（回执用它折算溢出份数）
     * @param memberInserted   非 null 时，把每个落点<b>实际插入</b>的材料按落点分开收进去
     *                         （下标与 {@code targets} 对应，流体按台分配要用）。进箱子的材料
     *                         <b>不</b>记在这里：流体只认机器实际收下的份数
     */
    private static void takeByQuota(List<Target> targets, List<List<Target>> memberOverflow, int[] shares,
                                    Deque<List<ItemStack>> pending, List<ItemStack> inserted,
                                    List<ItemStack> overflowInserted,
                                    List<List<ItemStack>> memberInserted) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (int index = 0; index < targets.size(); index++) {
            Target target = targets.get(index);
            IItemHandler handler = target.handler();
            if (handler == null) {
                continue;
            }
            List<Target> ownOverflow = ownOverflowOf(memberOverflow, index);
            int quota = shares == null ? Integer.MAX_VALUE : shares[index];
            while (quota > 0 && !pending.isEmpty()) {
                List<ItemStack> batch = pending.peekFirst();
                List<SlotInsert> plan = plan(handler, batch, target.inputLimited());
                List<ItemStack> applied = plan == null || plan.isEmpty()
                        ? List.of() : apply(handler, plan);
                collect(applied, inserted);
                if (memberInserted != null) {
                    collect(applied, memberInserted.get(index));
                }
                Placed own = placeInto(ownOverflow, subtract(batch, applied));
                collect(own.inserted(), inserted);
                if (overflowInserted != null) {
                    collect(own.inserted(), overflowInserted);
                }
                pending.removeFirst();
                if (!own.remainder().isEmpty()) {
                    // 机器和自己的箱子都收不完：差额顺延给下一个落点，并且立刻离开这一台，
                    // 否则这一份会在同一个落点上原地打转。
                    pending.addFirst(own.remainder());
                }
                if (applied.isEmpty() && own.inserted().isEmpty()) {
                    // 一份都没收下：队首原样没动，不吃配额，换下一个落点试。
                    break;
                }
                quota--;
                if (!own.remainder().isEmpty()) {
                    break;
                }
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
     * <p>「模拟通过、真实只收下一部分」同样按这一台已满处理：差额重新入队，留给下一个落点。
     * 若不断言这一点，这部分材料会凭空消失（既不进组内，也到不了从容器）。
     *
     * <p>这一轮同样优先喂<b>成员自己的从容器</b>：机器满了但箱子还有空的时候，材料该进箱子
     * 而不是被推给别人。只有机器和它自己的箱子都满了，这一台才算真的满了。
     *
     * @param memberOverflow   非 null 且下标对齐时，把每个落点<b>自己的从容器</b>也当落点用
     * @param targetInserted   非 null 时，把本组落点<b>实际插入</b>的材料单独收进去
     *                         （调用方用它折算溢出到从容器的份数）
     * @param overflowInserted 非 null 时收进「落进箱子里的材料」（与 {@code targetInserted}
     *                         一起折算溢出份数）
     * @param memberInserted   非 null 时，把每个落点<b>实际插入</b>的材料按落点分开收进去
     *                         （下标与 {@code targets} 对应，流体按台分配要用）
     */
    private static void roundRobin(List<Target> targets, List<List<Target>> memberOverflow,
                                   Deque<List<ItemStack>> pending, List<ItemStack> inserted,
                                   List<ItemStack> targetInserted, List<ItemStack> overflowInserted,
                                   List<List<ItemStack>> memberInserted) {
        if (targets == null || targets.isEmpty() || pending.isEmpty()) {
            return;
        }
        boolean[] saturated = new boolean[targets.size()];
        int available = targets.size();
        while (!pending.isEmpty() && available > 0) {
            boolean progressed = false;
            for (int index = 0; index < targets.size() && !pending.isEmpty(); index++) {
                if (saturated[index]) {
                    continue;
                }
                Target target = targets.get(index);
                IItemHandler handler = target.handler();
                List<SlotInsert> plan = handler == null
                        ? null : plan(handler, pending.peekFirst(), target.inputLimited());
                List<ItemStack> applied = plan == null || plan.isEmpty()
                        ? List.of() : apply(handler, plan);
                collect(applied, inserted);
                if (targetInserted != null) {
                    collect(applied, targetInserted);
                }
                if (memberInserted != null) {
                    collect(applied, memberInserted.get(index));
                }
                Placed own = placeInto(ownOverflowOf(memberOverflow, index),
                        subtract(pending.peekFirst(), applied));
                collect(own.inserted(), inserted);
                if (overflowInserted != null) {
                    collect(own.inserted(), overflowInserted);
                }
                boolean placed = !applied.isEmpty() || !own.inserted().isEmpty();
                pending.removeFirst();
                if (placed) {
                    progressed = true;
                }
                if (!own.remainder().isEmpty()) {
                    saturated[index] = true;
                    available--;
                    pending.addFirst(own.remainder());
                }
            }
            if (!progressed) {
                return;
            }
        }
    }

    /**
     * 一次「往一组落点里塞一份材料」的结果。
     *
     * @param inserted  实际放进去的材料
     * @param remainder 没放进去的余量（空表示这一份完整送达）
     */
    private record Placed(List<ItemStack> inserted, List<ItemStack> remainder) {
    }

    /**
     * 把一份材料尽量塞进某一组落点（通常就是某个成员自己的从容器），返回放进去的与剩下的。
     *
     * <p>与 {@link #takeByQuota} / {@link #roundRobin} 的区别是<b>不做「满没满」的判断</b>：
     * 箱子之间互不挤占，一份能放多少就放多少，放不下的余量由调用方顺延给下一个落点。
     */
    private static Placed placeInto(List<Target> targets, List<ItemStack> batch) {
        List<ItemStack> inserted = new ArrayList<>();
        if (targets == null || targets.isEmpty() || batch == null || batch.isEmpty()) {
            return new Placed(inserted, batch == null ? List.of() : batch);
        }
        List<ItemStack> remainder = batch;
        for (Target target : targets) {
            if (remainder.isEmpty()) {
                break;
            }
            IItemHandler handler = target.handler();
            if (handler == null) {
                continue;
            }
            List<SlotInsert> plan = plan(handler, remainder, target.inputLimited());
            if (plan == null || plan.isEmpty()) {
                continue;
            }
            List<ItemStack> applied = apply(handler, plan);
            collect(applied, inserted);
            remainder = subtract(remainder, applied);
        }
        return new Placed(inserted, remainder);
    }

    /**
     * 取下标的「这一台自己的从容器」。
     *
     * <p>越界或没有时给空表，调用方就不必到处判空——空表在 {@link #placeInto} 里
     * 原样返回余量，等价于「这台机器没有扩容箱」。
     */
    private static List<Target> ownOverflowOf(List<List<Target>> memberOverflow, int index) {
        if (memberOverflow == null || index < 0 || index >= memberOverflow.size()) {
            return List.of();
        }
        List<Target> targets = memberOverflow.get(index);
        return targets == null ? List.of() : targets;
    }

    /** 把「实际插入量」列表里的非空项收进 {@code output}。 */
    private static void collect(List<ItemStack> applied, List<ItemStack> output) {
        for (ItemStack stack : applied) {
            if (!stack.isEmpty()) {
                output.add(stack);
            }
        }
    }

    /**
     * 算出 {@code materials} 里落点<b>其实没收下</b>的部分。
     *
     * <p>{@code applied} 是同一次投放里每个槽位的真实插入量（同一物品可能跨多个槽），
     * 按物品求和后从这一份里扣掉，剩下的就是差额。空的返回值表示这一份完整送达。
     */
    private static List<ItemStack> subtract(List<ItemStack> materials, List<ItemStack> applied) {
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack material : materials) {
            int remaining = material.getCount();
            for (ItemStack done : applied) {
                if (!done.isEmpty() && ItemStack.isSameItemSameTags(done, material)) {
                    remaining -= done.getCount();
                }
            }
            if (remaining > 0) {
                rest.add(material.copyWithCount(remaining));
            }
        }
        return rest;
    }

    /** 规划一个需求在目标槽位中的投放位置。 */
    private static boolean planInsertion(ItemStack requirement, List<SimulatedSlot> slots,
                                         List<SlotInsert> insertions, boolean inputLimited) {
        int remaining = requirement.getCount();
        for (SimulatedSlot simulated : slots) {
            if (remaining == 0) {
                break;
            }
            boolean blocked = inputLimited && takenByAnotherSlot(slots, simulated, requirement);
            if (!simulated.canReceive(requirement, blocked)) {
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

    /**
     * 同一种物品是否已经占了别的槽。
     *
     * <p>只比 {@code Item}、不比 NBT，与格雷 {@code CustomItemStackHandler#limitedInsert} 的判据逐字一致：
     * 「防止堵料」打开时，本物品已经有槽就只认那个槽，一个都没有才允许它占一个空槽。
     */
    private static boolean takenByAnotherSlot(List<SimulatedSlot> slots, SimulatedSlot self,
                                              ItemStack incoming) {
        for (SimulatedSlot other : slots) {
            if (other != self && other.holds(incoming)) {
                return true;
            }
        }
        return false;
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

        /**
         * 判断模拟槽位能否接收指定物品。
         *
         * @param blockedByInputLimit 「防止堵料」已经不允许这个槽再收该物品（同物品在别的槽里）；
         *                            真实处理器看不到我们副本里的虚拟投放，所以这一条必须由模拟自己判
         */
        private boolean canReceive(ItemStack incoming, boolean blockedByInputLimit) {
            if (blockedByInputLimit || !handler.isItemValid(slot, incoming)) {
                return false;
            }
            if (stack.isEmpty()) {
                return true;
            }
            return ItemStack.isSameItemSameTags(stack, incoming)
                    && stack.getCount() < getCapacity(incoming);
        }

        /** 这个槽里是否躺着同一个物品（只比 {@code Item}，不比 NBT）。 */
        private boolean holds(ItemStack incoming) {
            return !stack.isEmpty() && stack.getItem() == incoming.getItem();
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

    /**
     * 一个落点的只读副本，供 {@link #maxBatches} 试放。
     *
     * <p>容量规则与 {@link SimulatedSlot} 逐条对齐（{@code isItemValid} + 同物品同 NBT 才能叠加 +
     * {@code min(物品堆叠上限, 槽位上限)}），只是 {@code insertItem} 落在自己的副本数组上，
     * 不碰真实容器。两份规则必须一致，否则试放结果和真实投放会对不上。
     *
     * <p>「防止堵料」不在这里判：它要看<b>整台机器</b>的槽位分布，而副本是按槽拆开的，
     * 所以统一在 {@link #planInsertion} 里按 {@code SimulatedSlot} 列表判断（真实投放与试放
     * 走的是同一段代码，不会漂）。
     */
    private static final class SimulatedHandler implements IItemHandler {
        private final IItemHandler real;
        private final ItemStack[] stacks;

        private SimulatedHandler(IItemHandler real) {
            this.real = real;
            this.stacks = new ItemStack[real.getSlots()];
            for (int slot = 0; slot < stacks.length; slot++) {
                ItemStack current = real.getStackInSlot(slot);
                stacks[slot] = current == null ? ItemStack.EMPTY : current.copy();
            }
        }

        @Override
        public int getSlots() {
            return stacks.length;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot >= 0 && slot < stacks.length ? stacks[slot] : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return real.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return real.isItemValid(slot, stack);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || slot < 0 || slot >= stacks.length
                    || !real.isItemValid(slot, stack)) {
                return stack;
            }
            ItemStack current = stacks[slot];
            int capacity = Math.min(stack.getMaxStackSize(), real.getSlotLimit(slot));
            if (!current.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(current, stack)) {
                    return stack;
                }
                capacity -= current.getCount();
            }
            int moved = Math.min(capacity, stack.getCount());
            if (moved <= 0) {
                return stack;
            }
            if (current.isEmpty()) {
                stacks[slot] = stack.copyWithCount(moved);
            } else {
                current.grow(moved);
            }
            return stack.copyWithCount(stack.getCount() - moved);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // 试放只需要插入；抽取在模拟阶段没有意义。
            return ItemStack.EMPTY;
        }
    }
}
