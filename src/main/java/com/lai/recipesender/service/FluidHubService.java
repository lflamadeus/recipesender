package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.RecipeFluidSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 流体中枢：直达模式在开启流体支持时的<b>唯一</b>流体来源。
 *
 * <p>中枢是绑定系统里的一个独立用途（{@link BoundContainer.Kind#FLUID_HUB}），固定只有一个，
 * 不参与主 / 并列 / 从的分组，也不参与按类别自动路由——它是「源」，而现有绑定全是「目标」。
 * 位置解析与 {@link BoundTargetResolver} 同口径（维度、距离、区块、方块实体），
 * 只把最后一步的能力判定从物品容器换成流体能力。
 */
public final class FluidHubService {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    /** 中枢的解析结果；{@code status != OK} 时 {@code blockEntity} 可能仍非空（用于报原因）。 */
    public record ResolvedHub(BlockEntity blockEntity, BoundStatus status) {
        public boolean available() {
            return status == BoundStatus.OK && blockEntity != null;
        }
    }

    private FluidHubService() {
    }

    /** 找出玩家的流体中枢；没绑定时返回 {@code null}。 */
    public static BoundContainer find(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        for (BoundContainer binding : BoundContainerService.list(player)) {
            if (binding.isFluidHub()) {
                return binding;
            }
        }
        return null;
    }

    /** 解析中枢位置；任何一步不满足都返回带原因的失败结果，不抛异常。 */
    public static ResolvedHub resolve(ServerPlayer player, BoundContainer binding) {
        if (player == null || binding == null) {
            return new ResolvedHub(null, BoundStatus.FORBIDDEN);
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return new ResolvedHub(null, BoundStatus.FORBIDDEN);
        }
        if (!player.level().dimension().equals(binding.dimension())) {
            return new ResolvedHub(null, BoundStatus.DIMENSION_MISMATCH);
        }
        ServerLevel level = server.getLevel(binding.dimension());
        if (level == null) {
            return new ResolvedHub(null, BoundStatus.DIMENSION_MISMATCH);
        }
        int distanceLimit = RecipeSenderConfig.recipeManagementDistance();
        BlockPos pos = binding.pos();
        if (distanceLimit >= 0
                && player.distanceToSqr(Vec3.atCenterOf(pos)) > (double) distanceLimit * distanceLimit) {
            return new ResolvedHub(null, BoundStatus.TOO_FAR);
        }
        if (!level.isLoaded(pos)) {
            return new ResolvedHub(null, BoundStatus.CHUNK_UNLOADED);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return new ResolvedHub(null, BoundStatus.MISSING);
        }
        if (!hasFluidHandler(blockEntity)) {
            return new ResolvedHub(blockEntity, BoundStatus.NO_FLUID_HANDLER);
        }
        return new ResolvedHub(blockEntity, BoundStatus.OK);
    }

    /**
     * 这个方块实体能不能当流体中枢。
     *
     * <p>判定刻意宽松：AE2 存储视图一律算数（驱动器里有没有流体元件只有抽的时候才知道），
     * 提供 Forge 流体能力的方块也算数。
     */
    public static boolean hasFluidHandler(BlockEntity blockEntity) {
        return blockEntity != null && FindMeExtendedAdapter.hasFluidSource(blockEntity);
    }

    /** 读中枢当前持有的流体（AE2 存储视图优先，其次 Forge 流体罐）。 */
    public static List<FluidStack> contents(ServerPlayer player, BoundContainer binding) {
        ResolvedHub hub = resolve(player, binding);
        if (!hub.available()) {
            return List.of();
        }
        return FindMeExtendedAdapter.hubFluids(hub.blockEntity());
    }

    /**
     * 配方里的这一项流体，在中枢里对应哪一种（返回中枢持有的那一种，amount 为中枢里的总量）。
     *
     * <p>按 {@link RecipeFluidSpec#matches} 匹配，也就是流体 + NBT 相等；中枢里没有这一项时返回
     * {@code null}，调用方据此判定「流体不足」。
     */
    public static FluidStack findFluid(List<FluidStack> contents, RecipeFluidSpec spec) {
        if (contents == null || spec == null) {
            return null;
        }
        for (FluidStack content : contents) {
            if (spec.matches(content)) {
                return content;
            }
        }
        return null;
    }

    /**
     * 中枢里的流体够发几份完整配方（取各项流体里最紧的那一项）。
     *
     * @return 份数；中枢缺某一项流体时返回 {@code 0}
     */
    public static int countBatches(List<RecipeFluidSpec> specs, List<FluidStack> contents) {
        if (specs == null || specs.isEmpty()) {
            return 0;
        }
        long best = Long.MAX_VALUE;
        for (RecipeFluidSpec spec : specs) {
            FluidStack available = findFluid(contents, spec);
            if (available == null || spec.amountPerBatch() <= 0) {
                return 0;
            }
            best = Math.min(best, available.getAmount() / spec.amountPerBatch());
        }
        return best == Long.MAX_VALUE ? 0 : (int) Math.min(best, Integer.MAX_VALUE);
    }

    /** 从中枢抽出指定流体，抽到的追加到 {@code output}；返回实际抽出的 mB。 */
    public static int drain(ServerPlayer player, BlockEntity blockEntity, FluidStack requirement,
                            int amount, List<FluidStack> output) {
        return FindMeExtendedAdapter.drainFluid(player, blockEntity, requirement, amount, output);
    }

    /**
     * 把流体灌回中枢（只走 Forge 流体能力）。
     *
     * <p>正常路径上不该用到它：份数是先按目标容量算好、再从中枢抽的。这里是兜底——
     * 万一灌的时候目标真的收不下了，宁可灌回中枢也不该让流体凭空消失。AE2 存储视图没有通用的
     * 「插回去」入口，那种中枢只能记一条警告。
     *
     * @return 灌回去的 mB 数
     */
    public static int insert(BlockEntity blockEntity, FluidStack fluid) {
        if (blockEntity == null || fluid == null || fluid.isEmpty()) {
            return 0;
        }
        IFluidHandler handler = FindMeExtendedAdapter.fluidHandlerOf(blockEntity);
        if (handler == null) {
            LOGGER.warn("流体中枢 {} 不支持 Forge 流体能力，{} mB {} 无法灌回",
                    blockEntity.getBlockPos(), fluid.getAmount(), fluid.getFluid());
            return 0;
        }
        try {
            int filled = handler.fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
            return Math.max(0, filled);
        } catch (RuntimeException exception) {
            LOGGER.warn("把流体灌回中枢 {} 失败", blockEntity.getBlockPos(), exception);
            return 0;
        }
    }

    /** 逐项把配方需求折算成「一份的流体清单」，缺项时返回 {@code null}。 */
    public static List<FluidStack> perBatchFluids(List<RecipeFluidSpec> specs,
                                                  List<FluidStack> contents) {
        List<FluidStack> perBatch = new ArrayList<>(specs.size());
        for (RecipeFluidSpec spec : specs) {
            FluidStack available = findFluid(contents, spec);
            if (available == null) {
                return null;
            }
            perBatch.add(new FluidStack(available.getFluid(), spec.amountPerBatch(),
                    available.getTag()));
        }
        return perBatch;
    }
}
