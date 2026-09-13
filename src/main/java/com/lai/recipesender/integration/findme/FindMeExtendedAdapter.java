package com.lai.recipesender.integration.findme;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
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

    /**
     * 判断 FindMeExtended 是否安装。
     * 只查模组列表，不触发反射初始化，因此可以在按键注册等早期阶段安全调用。
     */
    public static boolean isModPresent() {
        return ModList.get().isLoaded(FIND_ME_ID);
    }

    /** 判断 FindMeExtended 是否存在且兼容接口初始化成功。 */
    public static boolean isAvailable() {
        return isModPresent() && getAccess() != null;
    }

    /** 快照周围容器和 AE2 存储中的物品，用于服务端配方数量计算。 */
    public static List<ItemStack> snapshotNearby(ServerPlayer player) {
        ReflectionAccess reflection = getAccess();
        if (player == null || reflection == null) {
            return List.of();
        }

        List<ItemStack> result = new ArrayList<>();
        Set<Object> visitedAe2Storages = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockEntity blockEntity : reflection.collectNearbyBlockEntities(player)) {
            blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                    .ifPresent(handler -> copyHandlerContents(handler, result));
            reflection.copyAe2Contents(blockEntity, result, visitedAe2Storages);
        }
        return List.copyOf(result);
    }

    /**
     * 一次扫描周围容器后按顺序取回多种材料。
     * 返回每种材料实际取回的数量，顺序与传入列表一致。
     * 分批扫描是必要的：逐种材料各自扫描一遍整个搜索范围会成倍放大服务端主线程开销。
     */
    public static int[] pullAll(ServerPlayer player, List<ItemStack> requirements) {
        ReflectionAccess reflection = getAccess();
        int[] pulled = new int[requirements.size()];
        if (player == null || reflection == null || requirements.isEmpty()) {
            return pulled;
        }
        List<BlockEntity> containers = reflection.collectNearbyBlockEntities(player);
        if (containers.isEmpty()) {
            return pulled;
        }
        for (int index = 0; index < requirements.size(); index++) {
            ItemStack requirement = requirements.get(index);
            if (requirement.isEmpty() || requirement.getCount() <= 0) {
                continue;
            }
            pulled[index] = reflection.pull(containers, player,
                    requirement.copyWithCount(1), requirement.getCount());
        }
        return pulled;
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

    /** 双箱子的两半都会暴露合并后的处理器，只读取坐标较小的一半。 */
    private static boolean isSecondaryVanillaChestHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return false;
        }
        BlockPos connectedPos = pos.relative(ChestBlock.getConnectedDirection(state));
        return pos.compareTo(connectedPos) > 0;
    }

    /** 判断坐标是否落在以玩家为中心的搜索立方体内。 */
    private static boolean isInsideSearchBox(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** 延迟初始化可选模组接口，避免缺少依赖时触发类加载异常。 */
    private static ReflectionAccess getAccess() {
        ReflectionAccess current = access;
        if (current != null || initializationFailed || !isModPresent()) {
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

        /**
         * 枚举搜索范围内所有已加载区块的方块实体。
         * 逐格遍历 (2r+1)^3 个坐标再逐个查询方块实体代价极高（半径 32 时为 27 万次），
         * 按区块读取方块实体表能把开销降到与实际存在的方块实体数量同阶。
         */
        private List<BlockEntity> collectNearbyBlockEntities(ServerPlayer player) {
            int radius = getSafeRadius();
            BlockPos center = player.blockPosition();
            ServerLevel level = player.serverLevel();
            BlockPos min = center.offset(-radius, -radius, -radius);
            BlockPos max = center.offset(radius, radius, radius);
            List<BlockEntity> result = new ArrayList<>();
            for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
                for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
                    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (!isInsideSearchBox(pos, min, max)
                                || isSecondaryVanillaChestHalf(level, pos)) {
                            continue;
                        }
                        BlockEntity blockEntity = entry.getValue();
                        if (blockEntity != null) {
                            result.add(blockEntity);
                        }
                    }
                }
            }
            return result;
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

        /** 在已收集的方块实体中提取指定材料，隔离单个提取器的异常。 */
        private int pull(List<BlockEntity> containers, ServerPlayer player, ItemStack stack, int amount) {
            int extracted = 0;
            List<?> extractorSnapshot = List.copyOf(extractors);
            for (BlockEntity blockEntity : containers) {
                if (extracted >= amount) {
                    break;
                }
                for (Object extractor : extractorSnapshot) {
                    try {
                        Object value = pullMethod.invoke(extractor, blockEntity, stack,
                                amount - extracted, player);
                        if (value instanceof Integer pulled && pulled > 0) {
                            extracted = Math.min(amount, extracted + pulled);
                        }
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        LOGGER.warn("FindMeExtended 提取器处理 {} 时失败",
                                blockEntity.getBlockPos(), exception);
                    }
                    if (extracted >= amount) {
                        break;
                    }
                }
            }
            return extracted;
        }
    }
}
