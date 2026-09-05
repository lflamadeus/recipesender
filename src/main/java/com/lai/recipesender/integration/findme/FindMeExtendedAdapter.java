package com.lai.recipesender.integration.findme;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 隔离 FindMeExtended 和 AE2 的可选兼容逻辑。 */
public final class FindMeExtendedAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");
    private static final String FIND_ME_ID = "findmeextended";
    private static final String AE2_ID = "ae2";
    private static final int MAX_SAFE_RADIUS = 32;

    private static volatile ReflectionAccess access;
    private static volatile boolean initializationFailed;

    private FindMeExtendedAdapter() {
    }

    /** 判断 FindMeExtended 是否存在且兼容接口初始化成功。 */
    public static boolean isAvailable() {
        return ModList.get().isLoaded(FIND_ME_ID) && getAccess() != null;
    }

    /** 快照周围容器和 AE2 存储中的物品，用于服务端配方数量计算。 */
    public static List<ItemStack> snapshotNearby(ServerPlayer player) {
        ReflectionAccess reflection = getAccess();
        if (player == null || reflection == null) {
            return List.of();
        }

        int radius = reflection.getSafeRadius();
        BlockPos center = player.blockPosition();
        List<ItemStack> result = new ArrayList<>();
        Set<Object> visitedAe2Storages = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (!player.serverLevel().hasChunkAt(pos)) {
                continue;
            }
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
            if (blockEntity == null) {
                continue;
            }
            if (isSecondaryVanillaChestHalf(player, pos)) {
                continue;
            }
            blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                    .ifPresent(handler -> copyHandlerContents(handler, result));
            reflection.copyAe2Contents(blockEntity, result, visitedAe2Storages);
        }
        return List.copyOf(result);
    }

    /** 双箱子的两半都会暴露合并后的处理器，只读取坐标较小的一半。 */
    private static boolean isSecondaryVanillaChestHalf(ServerPlayer player, BlockPos pos) {
        BlockState state = player.serverLevel().getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return false;
        }
        BlockPos connectedPos = pos.relative(ChestBlock.getConnectedDirection(state));
        return pos.compareTo(connectedPos) > 0;
    }

    /** 委托 FindMeExtended 的提取器，从周围容器取回指定数量的物品。 */
    public static int pull(ServerPlayer player, ItemStack stack, int amount) {
        ReflectionAccess reflection = getAccess();
        if (player == null || reflection == null || stack.isEmpty() || amount <= 0) {
            return 0;
        }
        return reflection.pull(player, stack.copyWithCount(1), amount);
    }

    /** 复制 Forge 物品处理器的内容，不修改容器状态。 */
    private static void copyHandlerContents(IItemHandler handler, List<ItemStack> output) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                output.add(stack.copy());
            }
        }
    }

    /** 延迟初始化可选模组接口，避免缺少依赖时触发类加载异常。 */
    private static ReflectionAccess getAccess() {
        ReflectionAccess current = access;
        if (current != null || initializationFailed || !ModList.get().isLoaded(FIND_ME_ID)) {
            return current;
        }
        synchronized (FindMeExtendedAdapter.class) {
            if (access != null || initializationFailed) {
                return access;
            }
            try {
                access = ReflectionAccess.create();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                initializationFailed = true;
                LOGGER.error("无法初始化 FindMeExtended 兼容层，反转功能已禁用", exception);
            }
            return access;
        }
    }

    /** 缓存反射对象，减少重复查找并隔离可选模组类。 */
    private record ReflectionAccess(
            Object commonConfig,
            Field radiusField,
            List<?> extractors,
            Method pullMethod,
            Method getCapabilityMethod,
            Object ae2StorageCapability,
            Method storageContentsMethod,
            Class<?> ae2ItemKeyClass,
            Method ae2ItemKeyToStackMethod,
            Class<?> ae2ChestOrDriveClass,
            Method ae2IsPoweredMethod,
            Method ae2CellCountMethod,
            Method ae2CellInventoryMethod) {

        /** 创建 FindMeExtended、Forge capability 和 AE2 API 的反射访问器。 */
        private static ReflectionAccess create() throws ReflectiveOperationException {
            Class<?> findMeClass = Class.forName("com.lflamadeus.findmeextended.FindMeMod");
            Object config = findMeClass.getField("CONFIG").get(null);
            Object common = config.getClass().getField("COMMON").get(config);
            Field radius = common.getClass().getField("RADIUS_RANGE");

            Object extractorValue = findMeClass.getField("BLOCK_EXTRACTORS").get(null);
            if (!(extractorValue instanceof List<?> extractorList)) {
                throw new IllegalStateException("FindMeExtended 提取器列表类型不兼容");
            }
            Class<?> pullerClass = Class.forName(
                    "com.lflamadeus.findmeextended.IInventoryPuller");
            Method pull = pullerClass.getMethod("pull", BlockEntity.class, ItemStack.class,
                    int.class, net.minecraft.world.entity.player.Player.class);

            if (!ModList.get().isLoaded(AE2_ID)) {
                return new ReflectionAccess(common, radius, extractorList, pull,
                        null, null, null, null, null, null, null, null, null);
            }

            try {
                Method getCapability = BlockEntity.class.getMethod("getCapability", Capability.class,
                        net.minecraft.core.Direction.class);
                Class<?> capabilitiesClass = Class.forName("appeng.capabilities.Capabilities");
                Object storageCapability = capabilitiesClass.getField("STORAGE").get(null);
                Class<?> storageClass = Class.forName("appeng.api.storage.MEStorage");
                Method storageContents = storageClass.getMethod("getAvailableStacks");
                Class<?> itemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
                Method toStack = itemKeyClass.getMethod("toStack", int.class);

                Class<?> chestOrDriveClass = Class.forName(
                        "appeng.api.implementations.blockentities.IChestOrDrive");
                Method isPowered = chestOrDriveClass.getMethod("isPowered");
                Method cellCount = chestOrDriveClass.getMethod("getCellCount");
                Method cellInventory = chestOrDriveClass.getMethod("getCellInventory", int.class);

                return new ReflectionAccess(common, radius, extractorList, pull, getCapability,
                        storageCapability, storageContents, itemKeyClass, toStack,
                        chestOrDriveClass, isPowered, cellCount, cellInventory);
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("AE2 API 不兼容，将仅使用 FindMeExtended 的普通容器支持", exception);
                return new ReflectionAccess(common, radius, extractorList, pull,
                        null, null, null, null, null, null, null, null, null);
            }
        }

        /** 读取配置半径并限制扫描范围，防止过大范围造成服务端卡顿。 */
        private int getSafeRadius() {
            try {
                int configuredRadius = radiusField.getInt(commonConfig);
                if (configuredRadius > MAX_SAFE_RADIUS) {
                    LOGGER.warn("FindMeExtended 搜索半径 {} 过大，已限制为 {}",
                            configuredRadius, MAX_SAFE_RADIUS);
                }
                return Math.max(0, Math.min(configuredRadius, MAX_SAFE_RADIUS));
            } catch (IllegalAccessException exception) {
                LOGGER.warn("读取 FindMeExtended 搜索半径失败，使用默认值 8", exception);
                return 8;
            }
        }

        /** 读取方块实体的 AE2 MEStorage 内容。 */
        private void copyAe2Contents(BlockEntity blockEntity, List<ItemStack> output,
                                     Set<Object> visitedStorages) {
            if (getCapabilityMethod == null || ae2StorageCapability == null) {
                return;
            }
            try {
                if (ae2ChestOrDriveClass.isInstance(blockEntity)
                        && Boolean.TRUE.equals(ae2IsPoweredMethod.invoke(blockEntity))) {
                    int cellCount = ae2CellCountMethod.invoke(blockEntity) instanceof Integer value
                            ? value : 0;
                    for (int cell = 0; cell < cellCount; cell++) {
                        Object storage = ae2CellInventoryMethod.invoke(blockEntity, cell);
                        copyStorageContents(storage, output, visitedStorages);
                    }
                    return;
                }
                Object capability = getCapabilityMethod.invoke(blockEntity,
                        ae2StorageCapability, null);
                if (capability == null) {
                    return;
                }
                Object storage = capability.getClass().getMethod("orElse", Object.class)
                        .invoke(capability, new Object[]{null});
                copyStorageContents(storage, output, visitedStorages);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("读取 AE2 方块实体 {} 的存储内容失败", blockEntity.getBlockPos(), exception);
            }
        }

        /** 将 AE2 KeyCounter 中的物品键转换为普通 ItemStack 快照。 */
        private void copyStorageContents(Object storage, List<ItemStack> output,
                                         Set<Object> visitedStorages)
                throws ReflectiveOperationException {
            if (storage == null || !visitedStorages.add(storage)) {
                return;
            }
            Object keyCounter = storageContentsMethod.invoke(storage);
            if (!(keyCounter instanceof Iterable<?> entries)) {
                return;
            }
            for (Object entry : entries) {
                if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                    continue;
                }
                Object key = mapEntry.getKey();
                Object value = mapEntry.getValue();
                long count = value instanceof Number number ? number.longValue() : 0L;
                if (count <= 0 || !ae2ItemKeyClass.isInstance(key)) {
                    continue;
                }
                int safeCount = (int) Math.min(count, Integer.MAX_VALUE);
                ItemStack stack = (ItemStack) ae2ItemKeyToStackMethod.invoke(key, safeCount);
                if (!stack.isEmpty()) {
                    output.add(stack);
                }
            }
        }

        /** 调用 FindMeExtended 提取器并隔离单个提取器异常。 */
        private int pull(ServerPlayer player, ItemStack stack, int amount) {
            int pulled = 0;
            int radius = getSafeRadius();
            BlockPos center = player.blockPosition();
            List<?> extractorSnapshot = List.copyOf(extractors);
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                if (pulled >= amount) {
                    break;
                }
                if (!player.serverLevel().hasChunkAt(pos)
                        || isSecondaryVanillaChestHalf(player, pos)) {
                    continue;
                }
                BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
                if (blockEntity == null) {
                    continue;
                }
                for (Object extractor : extractorSnapshot) {
                    try {
                        Object value = pullMethod.invoke(extractor, blockEntity, stack,
                                amount - pulled, player);
                        if (value instanceof Integer extracted && extracted > 0) {
                            pulled = Math.min(amount, pulled + extracted);
                        }
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        LOGGER.warn("FindMeExtended 提取器处理 {} 时失败", pos, exception);
                    }
                    if (pulled >= amount) {
                        break;
                    }
                }
            }
            return pulled;
        }
    }
}


