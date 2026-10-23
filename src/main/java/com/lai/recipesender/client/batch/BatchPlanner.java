package com.lai.recipesender.client.batch;

import com.lai.recipesender.model.BoundContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选池 → 分组与配对。纯函数，不认识界面、渲染与网络。
 *
 * <p>排序规则：主向投影升序 → 次轴投影 → y。主向取 AABB 最长边（或玩家手动指定的轴），
 * 它比「玩家朝向」稳——玩家站歪了也不会把一行机器排成锯齿。
 *
 * <p>分组与配对见 {@link #plan(List, Direction.Axis)}。整条规则的目的只有一个：
 * **可以不绑，不能绑错**——配不起来的宁可标成未配对让玩家处理，也不猜一个最近的。
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

    /**
     * 分组与配对。
     *
     * <p>机器（格雷的机器/总线/仓）按<b>方块物品完全相同</b>分组：每组排序第一个当主容器，
     * 其余当并列成员。不同电压等级的同类机器算不同组——产能不一样，并到一起没有意义。
     *
     * <p>非机器（箱子、桶、抽屉、接口）只能当从容器，按<b>主向切片</b>配对：同一个主向坐标
     * 算一片。一片里恰好一台机器 + 一个非机器才绑；其余情况（多机器、多箱子、这一片没有机器）
     * 一律标 {@link BatchCandidate.Reject#UNPAIRED}——宁可让玩家剔掉，也不猜一个"最近的"。
     *
     * <p>整池一台机器都没有时退回老办法：全部当从容器，父容器由调用方按目标组的成员顺序配
     * （见 {@code BatchSelection.pairing()}）。
     */
    public static List<BatchCandidate> plan(List<BatchCandidate> ordered, Direction.Axis primary) {
        boolean anyMachine = false;
        for (BatchCandidate candidate : ordered) {
            if (candidate.accepted() && candidate.machine()) {
                anyMachine = true;
                break;
            }
        }
        if (!anyMachine) {
            return planSlaves(ordered);
        }

        List<BatchCandidate> planned = new ArrayList<>(ordered);

        // 1. 机器分组：按物品身份，组号从 1 开始，按排序首次出现的顺序给。
        Map<Item, Integer> groupOfItem = new LinkedHashMap<>();
        int[] groupOfIndex = new int[ordered.size()];
        int nextGroup = 1;
        for (int index = 0; index < ordered.size(); index++) {
            BatchCandidate candidate = ordered.get(index);
            if (!candidate.accepted() || !candidate.machine()) {
                continue;
            }
            Item item = candidate.icon().getItem();
            Integer group = groupOfItem.get(item);
            if (group == null) {
                group = nextGroup++;
                groupOfItem.put(item, group);
            }
            groupOfIndex[index] = group;
        }

        // 2. 每组的第一个当主容器，其余当并列成员（并列成员要记住它挂哪台主容器）。
        Set<Integer> seenGroups = new HashSet<>();
        Map<Integer, BlockPos> masterOfGroup = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            BatchCandidate candidate = ordered.get(index);
            if (!candidate.accepted() || !candidate.machine()) {
                continue;
            }
            int group = groupOfIndex[index];
            boolean first = seenGroups.add(group);
            if (first) {
                masterOfGroup.put(group, candidate.pos());
                planned.set(index, candidate.withPlan(BoundContainer.Role.MASTER, group, null));
            } else {
                planned.set(index, candidate.withPlan(BoundContainer.Role.PARALLEL, group,
                        masterOfGroup.get(group)));
            }
        }

        // 3. 非机器按主向切片配对。
        Map<Integer, List<Integer>> machinesBySlice = new LinkedHashMap<>();
        Map<Integer, List<Integer>> othersBySlice = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            BatchCandidate candidate = ordered.get(index);
            if (!candidate.accepted()) {
                continue;
            }
            int slice = along(candidate.pos(), primary);
            Map<Integer, List<Integer>> bucket = candidate.machine() ? machinesBySlice : othersBySlice;
            bucket.computeIfAbsent(slice, key -> new ArrayList<>()).add(index);
        }
        for (Map.Entry<Integer, List<Integer>> entry : othersBySlice.entrySet()) {
            List<Integer> machines = machinesBySlice.getOrDefault(entry.getKey(), List.of());
            List<Integer> others = entry.getValue();
            if (machines.size() == 1 && others.size() == 1) {
                BatchCandidate machine = planned.get(machines.get(0));
                int slaveIndex = others.get(0);
                planned.set(slaveIndex, planned.get(slaveIndex)
                        .withPlan(BoundContainer.Role.SLAVE, machine.group(), machine.pos()));
                continue;
            }
            for (int index : others) {
                planned.set(index, planned.get(index).withReject(BatchCandidate.Reject.UNPAIRED));
            }
        }
        return planned;
    }

    /** 从容器方案（整池没有机器时）：全部可用项当从容器，父容器由调用方按顺序配给目标组。 */
    public static List<BatchCandidate> planSlaves(List<BatchCandidate> ordered) {
        List<BatchCandidate> planned = new ArrayList<>(ordered.size());
        for (BatchCandidate candidate : ordered) {
            planned.add(candidate.accepted()
                    ? candidate.withPlan(BoundContainer.Role.SLAVE, 0, null) : candidate);
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
