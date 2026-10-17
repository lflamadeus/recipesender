package com.lai.recipesender.service;

/**
 * 一次发送「最多能发几份」的账本。
 *
 * <p>份数从来不是客户端说几份就是几份：物品来源、流体来源、物品落点、流体落点各自都有上限，
 * 真正发出去的份数只能取最小。以前这个「取最小」散在三个类里各写一遍（{@code NearbyRecipeService}
 * 数来源、{@link RemoteTransferPlanner} 数物品容量、{@link FluidTransferPlanner} 数流体容量），
 * 想知道「到底是谁把份数压下来的」得把三处结果拼起来看，还容易拼错。
 *
 * <p>现在四个上限都记在这一个不可变对象里：{@link #limit()} 是这一次真能发的份数，
 * {@link #bottleneck()} 是把它压下来的那一个。加新的一侧（比如能量、气体）只需要多一个字段，
 * 调用方不用改结构。
 *
 * <p>{@link #UNLIMITED} 表示「这一侧不构成限制」：配方没有物品、或者没开流体支持时，
 * 对应的那一侧就是无限制，而不是 0——0 的含义是「一份都发不出去」。
 */
public record BatchBudget(int requested, int itemSource, int itemTarget,
                          int fluidSource, int fluidTarget) {

    /** 这一侧不构成限制。用 {@code Integer.MAX_VALUE} 而不是 -1，是为了让「取最小」直接生效。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** 把份数压下来的那一侧。 */
    public enum Limit {
        /** 客户端要的份数本身就是最小的：能发多少发多少，没发完是运行时差额。 */
        REQUESTED,
        /** 周围（含背包）凑不出更多物品。 */
        ITEM_SOURCE,
        /** 流体中枢里没有更多这一项流体。 */
        FLUID_SOURCE,
        /** 落点装不下更多物品。 */
        ITEM_TARGET,
        /** 落点装不下更多流体。 */
        FLUID_TARGET
    }

    public BatchBudget {
        requested = Math.max(0, requested);
        itemSource = clamp(itemSource);
        itemTarget = clamp(itemTarget);
        fluidSource = clamp(fluidSource);
        fluidTarget = clamp(fluidTarget);
    }

    /** 只有客户端请求份数的初始账本：其余四项都还不构成限制。 */
    public static BatchBudget requested(int requested) {
        return new BatchBudget(requested, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED);
    }

    public BatchBudget withItemSource(int batches) {
        return new BatchBudget(requested, batches, itemTarget, fluidSource, fluidTarget);
    }

    public BatchBudget withItemTarget(int batches) {
        return new BatchBudget(requested, itemSource, batches, fluidSource, fluidTarget);
    }

    public BatchBudget withFluidSource(int batches) {
        return new BatchBudget(requested, itemSource, itemTarget, batches, fluidTarget);
    }

    public BatchBudget withFluidTarget(int batches) {
        return new BatchBudget(requested, itemSource, itemTarget, fluidSource, batches);
    }

    /** 这一次真能发的份数：四个上限与请求份数里的最小值。 */
    public int limit() {
        return Math.min(Math.min(requested, itemSource), Math.min(itemTarget, Math.min(fluidSource, fluidTarget)));
    }

    /** 流体这一侧的上限（来源与落点取小）。 */
    public int fluidLimit() {
        return Math.min(fluidSource, fluidTarget);
    }

    /**
     * 是谁把份数压下来的。
     *
     * <p>判据是「哪一项正好等于 {@link #limit()}」：份数就是它们的最小值，等于它的那一项就是瓶颈。
     * 平局时流体优先，这与旧文案的口径一致——配方含流体时「一份」是物品与流体都齐，先补流体才对。
     */
    public Limit bottleneck() {
        int limit = limit();
        if (limit >= requested) {
            return Limit.REQUESTED;
        }
        if (limit == fluidLimit()) {
            return fluidSource <= fluidTarget ? Limit.FLUID_SOURCE : Limit.FLUID_TARGET;
        }
        if (limit == itemSource) {
            return Limit.ITEM_SOURCE;
        }
        return Limit.ITEM_TARGET;
    }

    private static int clamp(int batches) {
        return batches < 0 ? 0 : batches;
    }
}
