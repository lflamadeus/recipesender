package com.lai.recipesender.client.batch;

import com.lai.recipesender.model.BoundContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 候选池 → 角色分配。纯函数，不认识界面、渲染与网络。
 *
 * <p>排序规则：主向投影升序 → 次轴投影 → y。主向取 AABB 最长边：它比「玩家朝向」稳，
 * 玩家站歪了也不会把一行机器排成锯齿；三轴跨度一样（或都为零）时才退回玩家朝向。
 *
 * <p>第一个可用项当主容器，其余当并列成员；与主容器不同类的标
 * {@link BatchCandidate.Reject#NOT_SAME_KIND}，不参与提交。
 */
public final class BatchPlanner {

    private BatchPlanner() {
    }

    /** 主向：可用候选里跨度最大的轴；都为零时退回 {@code fallback}。 */
    public static Direction.Axis primaryAxis(List<BatchCandidate> candidates, Direction.Axis fallback) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BatchCandidate candidate : candidates) {
            if (!candidate.accepted()) {
                continue;
            }
            BlockPos pos = candidate.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return fallback;
        }
        int spanX = maxX - minX;
        int spanY = maxY - minY;
        int spanZ = maxZ - minZ;
        if (spanX == 0 && spanY == 0 && spanZ == 0) {
            return fallback;
        }
        if (spanX >= spanY && spanX >= spanZ) {
            return Direction.Axis.X;
        }
        return spanY >= spanZ ? Direction.Axis.Y : Direction.Axis.Z;
    }

    /** 按主向排序（稳定：同投影再比 y、再比次轴）。 */
    public static List<BatchCandidate> order(List<BatchCandidate> candidates, Direction.Axis primary) {
        Direction.Axis secondary = primary == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        List<BatchCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((BatchCandidate candidate) -> along(candidate.pos(), primary))
                .thenComparingInt(candidate -> along(candidate.pos(), secondary))
                .thenComparingInt(candidate -> candidate.pos().getY()));
        return sorted;
    }

    /** 并列成员方案：第一个可用项当主容器，其余同类项当并列成员。 */
    public static List<BatchCandidate> planMembers(List<BatchCandidate> ordered) {
        List<BatchCandidate> planned = new ArrayList<>(ordered.size());
        ItemStack reference = null;
        for (BatchCandidate candidate : ordered) {
            if (!candidate.accepted()) {
                planned.add(candidate);
                continue;
            }
            if (reference == null) {
                reference = candidate.icon();
                planned.add(candidate.withRole(BoundContainer.Role.MASTER));
                continue;
            }
            if (!sameKind(reference, candidate.icon())) {
                planned.add(candidate.withReject(BatchCandidate.Reject.NOT_SAME_KIND));
                continue;
            }
            planned.add(candidate.withRole(BoundContainer.Role.PARALLEL));
        }
        return planned;
    }

    /** 从容器方案：全部可用项当从容器，父容器由调用方按顺序配给目标组。 */
    public static List<BatchCandidate> planSlaves(List<BatchCandidate> ordered) {
        List<BatchCandidate> planned = new ArrayList<>(ordered.size());
        for (BatchCandidate candidate : ordered) {
            planned.add(candidate.accepted() ? candidate.withRole(BoundContainer.Role.SLAVE) : candidate);
        }
        return planned;
    }

    /**
     * 同类判定：图标物品相同。
     *
     * <p>客户端只能看图标（`routeKeys` 是主容器的配方类别，从容器侧没有），所以这里只做粗筛；
     * 服务端仍然会按方块状态再校验一遍，两边不一致时以服务端为准。
     */
    public static boolean sameKind(ItemStack a, ItemStack b) {
        return a != null && b != null && !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItem(a, b);
    }

    /** 沿某个轴的分量；排序与配对都用它，所以公开给 {@link BatchSelection} 复用。 */
    public static int along(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }
}
