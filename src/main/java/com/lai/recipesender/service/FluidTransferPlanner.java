package com.lai.recipesender.service;

import com.lai.recipesender.model.RecipeFluidSpec;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 直达模式里「流体往目标容器里灌」的落点规划。
 *
 * <p>与物品那边的 {@link RemoteTransferPlanner} 是同一套思路（先模拟、模拟通过才真填、逐份均分），
 * 但流体没有「槽位」这个概念：一个目标可能有多个罐，能不能装下取决于罐的容量、罐里已有流体的种类，
 * 以及机器自己的 {@code isFluidValid} 过滤器。所以这里不按槽位规划，而是按
 * <b>目标 × 罐</b> 记一份「已预留量」，让同一批流体里的多项需求能共用一次模拟。
 *
 * <p>为什么必须先模拟：用户明确要求「目标放不下时不能出现流体已经抽出中枢、又得灌回去的情况」。
 * 中枢那一侧的抽取是不可逆的（AE2 抽出来就进了手里的 FluidStack），所以顺序只能是
 * 先算清目标能吃多少份，再按这个份数从中枢抽。
 */
public final class FluidTransferPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    /** 一个可以灌流体的落点；{@code resolved} 用于回执与 markChanged。 */
    public record Target(IFluidHandler handler, BoundTargetResolver.ResolvedTarget resolved) {
    }

    private FluidTransferPlanner() {
    }

    /**
     * 目标组能不能装下这一整批流体。
     *
     * <p>纯模拟，不动世界；任何一项装不下就返回 {@code false}（而不是「部分装得下」）——
     * 调用方据此降低份数，保证「发出去的每一份都是完整的」。
     */
    public static boolean fits(List<Target> targets, List<FluidStack> fluids) {
        if (fluids.isEmpty()) {
            return true;
        }
        return distribute(targets, fluids, false) >= 0;
    }

    /**
     * 真正把流体灌进目标组，返回实际灌进去的总 mB。
     *
     * <p>调用前应当先用 {@link #fits} 确认装得下；这里仍然按实际 {@code fill} 的返回值计数，
     * 万一世界在这两步之间变了（别的机器抽走了流体），差额由调用方还给中枢。
     */
    public static long fill(List<Target> targets, List<FluidStack> fluids) {
        long moved = distribute(targets, fluids, true);
        return Math.max(0, moved);
    }

    /**
     * 真填，并逐项返回没灌进去的余量。
     *
     * <p>逐项独立填（而不是把整批交给一次 {@link #distribute}），这样某一项灌不下时还能知道
     * 具体差多少、差的是哪一种流体，调用方才能把它还回中枢。正常路径上余量应当是空表——
     * 份数已经先按容量算过了。
     */
    public static List<FluidStack> fillReporting(List<Target> targets, List<FluidStack> fluids) {
        List<FluidStack> leftovers = new ArrayList<>();
        for (FluidStack fluid : fluids) {
            if (fluid.getAmount() <= 0) {
                continue;
            }
            long moved = Math.max(0, distribute(targets, List.of(fluid), true));
            int remaining = fluid.getAmount() - (int) Math.min(moved, Integer.MAX_VALUE);
            if (remaining > 0) {
                leftovers.add(new FluidStack(fluid.getFluid(), remaining, fluid.getTag()));
            }
        }
        return leftovers;
    }

    /**
     * 目标组最多能装下几份。
     *
     * <p>{@code perBatch} 是「一份配方需要的流体清单」（每项 amount = 每份用量）。
     * 份数越大越难装下，所以对份数做二分查找。
     */
    public static int maxBatches(List<Target> targets, List<FluidStack> perBatch, int upperBound) {
        if (perBatch.isEmpty() || upperBound <= 0) {
            return 0;
        }
        int low = 0;
        int high = upperBound;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (fits(targets, scale(perBatch, mid))) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** 把「一份的流体清单」按份数放大。 */
    public static List<FluidStack> scale(List<FluidStack> perBatch, int batches) {
        List<FluidStack> result = new ArrayList<>(perBatch.size());
        for (FluidStack base : perBatch) {
            long amount = (long) base.getAmount() * Math.max(0, batches);
            result.add(new FluidStack(base.getFluid(), (int) Math.min(amount, Integer.MAX_VALUE),
                    base.getTag()));
        }
        return result;
    }

    /**
     * 把一批流体轮流灌进目标组。
     *
     * @param execute {@code false} = 只模拟（返回已受理的总量），{@code true} = 真填
     * @return 模拟模式下装不下时返回 {@code -1}；否则返回已受理 / 已填入的总 mB
     */
    private static long distribute(List<Target> targets, List<FluidStack> fluids, boolean execute) {
        if (targets.isEmpty() || fluids.isEmpty()) {
            return fluids.isEmpty() ? 0 : -1;
        }
        int[][] reserved = new int[targets.size()][];
        for (int index = 0; index < targets.size(); index++) {
            try {
                reserved[index] = new int[Math.max(0, targets.get(index).handler().getTanks())];
            } catch (RuntimeException exception) {
                LOGGER.debug("读取目标流体罐数量失败", exception);
                reserved[index] = new int[0];
            }
        }
        long moved = 0;
        for (FluidStack fluid : fluids) {
            int remaining = fluid.getAmount();
            if (remaining <= 0) {
                continue;
            }
            boolean progressed = true;
            while (remaining > 0 && progressed) {
                progressed = false;
                for (int index = 0; index < targets.size() && remaining > 0; index++) {
                    int accepted = fillOne(targets.get(index), fluid, remaining, execute,
                            reserved[index]);
                    if (accepted > 0) {
                        remaining -= accepted;
                        moved += accepted;
                        progressed = true;
                    }
                }
            }
            if (remaining > 0) {
                // 模拟阶段装不下：整份作废，让调用方降份数；真填阶段装不下就如实返回已填量。
                return execute ? moved : -1;
            }
        }
        return moved;
    }

    /** 往一个目标的各个罐里灌同一种流体，返回受理量（模拟模式只登记预留量）。 */
    private static int fillOne(Target target, FluidStack fluid, int maxAmount, boolean execute,
                               int[] reserved) {
        IFluidHandler handler = target.handler();
        int accepted = 0;
        try {
            int tanks = handler.getTanks();
            for (int tank = 0; tank < tanks && accepted < maxAmount; tank++) {
                FluidStack inTank = handler.getFluidInTank(tank);
                if (!inTank.isEmpty() && !RecipeFluidSpec.sameFluid(inTank, fluid)) {
                    continue;
                }
                if (!handler.isFluidValid(tank, fluid)) {
                    continue;
                }
                int room = handler.getTankCapacity(tank) - inTank.getAmount();
                if (tank < reserved.length) {
                    room -= reserved[tank];
                }
                if (room <= 0) {
                    continue;
                }
                int request = Math.min(room, maxAmount - accepted);
                if (execute) {
                    int filled = handler.fill(
                            new FluidStack(fluid.getFluid(), request, fluid.getTag()),
                            IFluidHandler.FluidAction.EXECUTE);
                    accepted += Math.max(0, filled);
                } else {
                    if (tank < reserved.length) {
                        reserved[tank] += request;
                    }
                    accepted += request;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("往目标流体罐灌流体失败", exception);
        }
        return accepted;
    }
}
