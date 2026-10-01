package com.lai.recipesender.service;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
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
}
