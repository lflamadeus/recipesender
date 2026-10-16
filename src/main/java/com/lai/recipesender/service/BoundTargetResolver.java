package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.gt.InputLimitSupport;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/**
 * 把一条绑定记录解析成一个可以写入的远程物品容器。
 *
 * <p>这是本模组第一次「按坐标访问未打开的容器」：此前所有容器访问都走
 * {@code AbstractContainerMenu} 的 {@code Slot}（只有打开界面时才存在），或者走
 * FindMeExtended 的只读提取。远程目标只有 {@code IItemHandler}，没有 {@code Slot}。
 */
public final class BoundTargetResolver {

    /** 解析结果；{@code status != OK} 时 {@code handler} 为 {@code null}。 */
    public record ResolvedTarget(IItemHandler handler, BlockEntity blockEntity, BoundStatus status) {
        public boolean available() {
            return status == BoundStatus.OK && handler != null;
        }

        /**
         * 包成投放目标，顺带现读这台机器的「防止堵料」开关。
         *
         * <p>投放目标统一从这里生成，两个投送 service 就都不会漏传开关——漏传的后果是
         * 模拟容量成倍高估、材料被劈成残片、流体跟着多灌。
         *
         * <p>开关是每台机器自己的设置、玩家随时能改，所以每次发送都现读，不缓存；读不到
         * 按「没开」处理，只会让模拟偏保守，不会少发。
         */
        public RemoteTransferPlanner.Target toTarget() {
            return new RemoteTransferPlanner.Target(handler, blockEntity,
                    InputLimitSupport.isInputLimited(handler, blockEntity));
        }
    }

    private BoundTargetResolver() {
    }

    /** 解析目标；任何一步不满足都返回带原因的失败结果，不抛异常。 */
    public static ResolvedTarget resolve(ServerPlayer player, BoundContainer binding) {
        if (player == null || binding == null) {
            return new ResolvedTarget(null, null, BoundStatus.FORBIDDEN);
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return new ResolvedTarget(null, null, BoundStatus.FORBIDDEN);
        }
        if (!player.level().dimension().equals(binding.dimension())) {
            // 不做跨维度发送：材料在玩家背包里，目标在另一个维度时无法核对任何现场状态。
            return new ResolvedTarget(null, null, BoundStatus.DIMENSION_MISMATCH);
        }
        ServerLevel level = server.getLevel(binding.dimension());
        if (level == null) {
            return new ResolvedTarget(null, null, BoundStatus.DIMENSION_MISMATCH);
        }
        int distanceLimit = RecipeSenderConfig.recipeManagementDistance();
        if (distanceLimit >= 0
                && player.distanceToSqr(Vec3.atCenterOf(binding.pos())) > (double) distanceLimit * distanceLimit) {
            // 配方管理距离（默认 128 格，-1 表示不限）：先于区块判定，
            // 因为「太远」比「区块没加载」更容易让玩家知道该怎么做。
            return new ResolvedTarget(null, null, BoundStatus.TOO_FAR);
        }
        if (!level.isLoaded(binding.pos())) {
            // 只判断是否已加载，不为了发送强制加载区块。
            return new ResolvedTarget(null, null, BoundStatus.CHUNK_UNLOADED);
        }
        BlockEntity blockEntity = level.getBlockEntity(binding.pos());
        if (blockEntity == null) {
            return new ResolvedTarget(null, null, BoundStatus.MISSING);
        }
        IItemHandler handler = findItemHandler(blockEntity);
        if (handler == null) {
            return new ResolvedTarget(null, blockEntity, BoundStatus.NO_ITEM_HANDLER);
        }
        return new ResolvedTarget(handler, blockEntity, BoundStatus.OK);
    }

    /**
     * 该方块实体是否提供物品容器。
     *
     * <p>绑定前的准入判定用它：石头、机器外壳、装饰方块这类绑了也永远发不进去，
     * 与其等到发送时才报「目标方块不提供物品容器」，不如当场拒绝。
     */
    public static boolean hasItemHandler(BlockEntity blockEntity) {
        return blockEntity != null && findItemHandler(blockEntity) != null;
    }

    /**
     * 该方块实体是否提供 Forge 流体能力。
     *
     * <p>绑定流体中枢前的准入判定用它。只认 Forge 能力，不含 AE2 存储视图——客户端判定
     * 要能在没有可选模组时照常工作；AE2 那一侧由服务端 {@code FluidHubService} 的判定补齐。
     */
    public static boolean hasFluidHandler(BlockEntity blockEntity) {
        return blockEntity != null && findFluidHandler(blockEntity) != null;
    }

    /**
     * 取出方块实体的物品容器能力。
     *
     * <p><b>必须先试无面（{@code side == null}）再遍历六个面。</b>
     * 格雷机器的 {@code MetaMachineBlockEntity.getCapability} 按面分发，只认自己实现的那几个面；
     * 原版箱子、漏斗这类则不看面。先试无面能同时覆盖两者，反过来先遍历面则会把
     * 「无面可用、按面不可用」的方块误判成 {@link BoundStatus#NO_ITEM_HANDLER}。
     */
    private static IItemHandler findItemHandler(BlockEntity blockEntity) {
        IItemHandler direct = unwrap(blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null));
        if (direct != null) {
            return direct;
        }
        for (Direction side : Direction.values()) {
            IItemHandler sided = unwrap(blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side));
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    /** 把能力容器解包成实例；缺失时返回 {@code null}。 */
    private static IItemHandler unwrap(LazyOptional<IItemHandler> optional) {
        return optional != null && optional.isPresent() ? optional.orElse(null) : null;
    }

    /**
     * 取出方块实体的流体容器能力，规则与 {@link #findItemHandler(BlockEntity)} 完全一致：
     * 先试无面，再遍历六个面。
     */
    private static IFluidHandler findFluidHandler(BlockEntity blockEntity) {
        IFluidHandler direct = unwrapFluid(blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, null));
        if (direct != null) {
            return direct;
        }
        for (Direction side : Direction.values()) {
            IFluidHandler sided = unwrapFluid(blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, side));
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    private static IFluidHandler unwrapFluid(LazyOptional<IFluidHandler> optional) {
        return optional != null && optional.isPresent() ? optional.orElse(null) : null;
    }
}
