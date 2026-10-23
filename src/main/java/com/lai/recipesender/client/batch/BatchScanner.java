package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundContainerClient;
import com.lai.recipesender.integration.gt.MachineStateProbe;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BoundTargetResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AABB 里的方块扫成候选池。
 *
 * <p>只回答「这一格能不能用」，不排顺序、不定角色——那是 {@link BatchPlanner} 的事。
 * 扫描只在第二个角点落下时做一次：65³ 最多 27 万格，一次性可接受，每帧做会卡。
 *
 * <p>准入是四层只读判定（见 {@code BoundTargetResolver.canAcceptItems}）：有方块实体 → 提供物品
 * 容器 → 有槽位且容量大于 0 → 槽位接受物品。管道、外壳、装饰方块在这一串里自然出局，
 * **不按名字判断**（名字随语言变，客户端和服务端看到的不一样）。不做模拟插入。
 *
 * <p>已经绑过的方块进候选池但标成 {@link BatchCandidate.Reject#ALREADY_BOUND}，
 * 这样玩家能看到「这里已经有了」，而不是以为框没框上。
 */
public final class BatchScanner {

    /** 单边距离上限（格）：每个方向都不超过 32。 */
    public static final int MAX_SIDE = 32;

    private BatchScanner() {
    }

    /** 两个角点是否在允许范围内（同维度由调用方保证）。 */
    public static boolean withinLimit(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= MAX_SIDE
                && Math.abs(a.getY() - b.getY()) <= MAX_SIDE
                && Math.abs(a.getZ() - b.getZ()) <= MAX_SIDE;
    }

    /** 扫描两个角点之间的闭区间；未加载的区块直接跳过（不当作拒绝项）。 */
    public static List<BatchCandidate> scan(Level level, ResourceLocation dimension, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());

        List<BatchCandidate> found = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null) {
                        continue;
                    }
                    BatchCandidate candidate = classify(level, dimension, pos, blockEntity);
                    if (candidate != null) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found;
    }

    /**
     * 单个坐标的判定（BOX 阶段的「补一台」走这里）。
     *
     * <p>返回 {@code null} 表示「不是容器，不收录」；已绑定的仍会返回，只是带着拒绝原因，
     * 好让调用方说清楚为什么不能加。
     */
    public static BatchCandidate classify(Level level, ResourceLocation dimension, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : classify(level, dimension, pos, blockEntity);
    }

    /** 单个方块的判定；返回 {@code null} 表示「不是容器，不收录」。 */
    private static BatchCandidate classify(Level level, ResourceLocation dimension, BlockPos pos,
                                           BlockEntity blockEntity) {
        BoundContainer existing = BoundContainerClient.findAt(dimension, pos);
        if (existing != null) {
            // 已经绑过的：保留在池子里，但标记出来（中枢单独区分，方便说清为什么不能用）。
            return BatchCandidate.unplanned(pos, existing.iconItem(),
                    existing.isHub() ? BatchCandidate.Reject.HUB : BatchCandidate.Reject.ALREADY_BOUND,
                    isMachine(blockEntity));
        }
        if (!BoundTargetResolver.canAcceptItems(blockEntity)) {
            return null;
        }
        return BatchCandidate.unplanned(pos,
                level.getBlockState(pos).getBlock().asItem().getDefaultInstance(),
                BatchCandidate.Reject.NONE, isMachine(blockEntity));
    }

    /**
     * 是不是「机器」。
     *
     * <p>只认格雷的机器/总线/仓：它们能当主容器与并列成员。箱子、桶、抽屉、接口以及其它模组的
     * 机器都算「非机器」，只能当从容器——本模组的配方路由只认格雷配方，非格雷机器当主容器
     * 也发不了料。
     */
    private static boolean isMachine(BlockEntity blockEntity) {
        return MachineStateProbe.isMachine(blockEntity);
    }
}
