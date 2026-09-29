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
import net.minecraftforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 隔离 FindMeExtended 和 AE2 的可选兼容逻辑。 */
public final class FindMeExtendedAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");
    private static final String FIND_ME_ID = "findmeextended";
    private static final String AE2_ID = "ae2";
    private static final int MAX_SAFE_RADIUS = 32;

    private static volatile ReflectionAccess access;
    private static volatile boolean initializationFailed;
    /** FindMeExtended 黑名单接口，独立解析：对方版本较旧时只退化成「没有黑名单」。 */
    private static volatile Method blacklistMethod;
    private static volatile boolean blacklistUnavailable;

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

    /**
     * FindMeExtended 黑名单里的坐标（只读视图）。
     * <p>
     * 黑名单本体由 FindMeExtended 维护：手持黑名单工具增删，存档按维度保存、只认坐标。这里只是把它的
     * 公开接口读出来，让反转搜索与 FindMeExtended 自己的查找、取出共用同一套排除规则。
     * 未安装 FindMeExtended、或者对方版本还没有这个接口时返回空集合，等于没有黑名单。
     */
    public static Set<BlockPos> blacklistedPositions(ServerLevel level) {
        Method method = blacklistMethod();
        if (method == null || level == null) {
            return Set.of();
        }
        try {
            if (!(method.invoke(null, level) instanceof Set<?> positions)) {
                return Set.of();
            }
            @SuppressWarnings("unchecked")
            Set<BlockPos> blacklisted = (Set<BlockPos>) positions;
            return blacklisted;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("读取 FindMeExtended 黑名单失败，本次不排除任何容器", exception);
            return Set.of();
        }
    }

    /**
     * 解析 FindMeExtended 的黑名单接口（1.0.2 起提供）。
     * <p>
     * 单独解析、允许缺失：解析不了只意味着反转搜索不排除任何容器，其余功能不受影响。
     */
    private static Method blacklistMethod() {
        Method current = blacklistMethod;
        if (current != null || blacklistUnavailable) {
            return current;
        }
        synchronized (FindMeExtendedAdapter.class) {
            if (blacklistMethod != null || blacklistUnavailable) {
                return blacklistMethod;
            }
            if (!isModPresent()) {
                blacklistUnavailable = true;
                return null;
            }
            try {
                Class<?> apiClass = Class.forName(
                        "com.lai.findmeextended.api.FindMeBlacklistApi");
                blacklistMethod = apiClass.getMethod("blacklistedPositions", ServerLevel.class);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                blacklistUnavailable = true;
                LOGGER.warn("当前 FindMeExtended 没有黑名单接口，反转搜索不会排除任何容器", exception);
            }
            return blacklistMethod;
        }
    }

    /**
     * 快照周围容器和 AE2 存储中的物品，用于服务端配方数量计算。
     *
     * @param containerFilter 返回 {@code false} 的坐标会被跳过（例如黑名单里的容器），既不统计也不提取
     */
    public static List<ItemStack> snapshotNearby(ServerPlayer player,
                                                 Predicate<BlockPos> containerFilter) {
        ReflectionAccess reflection = getAccess();
        if (player == null || reflection == null) {
            return List.of();
        }

        List<ItemStack> result = new ArrayList<>();
        List<Ae2Snapshot> physicalStorages = new ArrayList<>();
        List<Ae2Snapshot> networkStorages = new ArrayList<>();
        Set<Object> visitedAe2Storages = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockEntity blockEntity : reflection.collectNearbyBlockEntities(player, containerFilter)) {
            // 先问 AE2 存储。GTO 的物品保险库把同一份库存（keyMap）同时挂成 Forge 物品处理器和
            // AE2 MEStorage，两条路都读就会把材料算成两份——这正是「保险库 + AE2」重复计数的来源。
            // 两者之中只有 AE2 视图是完整的：Forge 侧的 AEItemKeyStackHandler.getStackInSlot
            // 只返回 keyMap 的第一个物品键，读它既重复又不准，所以有 AE2 存储就不读处理器。
            // 保险库的仓（MEStorageHatch）走的是同一个 MEStorage 对象，已被上面的身份去重吃掉，
            // 这里返回 true 同样会跳过它的处理器，因此多个仓也不会把同一份库存重复计入。
            if (!reflection.collectAe2Snapshots(blockEntity, physicalStorages, networkStorages,
                    visitedAe2Storages)) {
                blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                        .ifPresent(handler -> copyHandlerContents(handler, result));
            }
        }
        reflection.appendAe2Stacks(physicalStorages, networkStorages, result);
        return List.copyOf(result);
    }

    /**
     * 一次扫描周围容器后按顺序取回多种材料。
     * 返回每种材料实际取回的数量，顺序与传入列表一致。
     * 分批扫描是必要的：逐种材料各自扫描一遍整个搜索范围会成倍放大服务端主线程开销。
     *
     * @param containerFilter 返回 {@code false} 的坐标会被跳过（例如黑名单里的容器）
     */
    public static int[] pullAll(ServerPlayer player, List<ItemStack> requirements,
                                Predicate<BlockPos> containerFilter) {
        ReflectionAccess reflection = getAccess();
        int[] pulled = new int[requirements.size()];
        if (player == null || reflection == null || requirements.isEmpty()) {
            return pulled;
        }
        List<BlockEntity> containers = reflection.collectNearbyBlockEntities(player, containerFilter);
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
            Method ae2CellInventoryMethod,
            Class<?> ae2NetworkStorageClass,
            Method ae2ExtractMethod,
            Object ae2ActionableModulate,
            Method ae2ActionSourceOfPlayer) {

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
                        null, null, null, null, null, null, null, null, null, null, null, null, null);
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

                // 认不出「整个网络的聚合存储」时只是少一层去重，不该连带关掉整个 AE2 支持，
                // 所以单独解析、允许为 null。
                Class<?> networkStorageClass = null;
                try {
                    networkStorageClass = Class.forName("appeng.me.storage.NetworkStorage");
                } catch (ClassNotFoundException exception) {
                    LOGGER.warn("未找到 appeng.me.storage.NetworkStorage，无法识别 AE 网络聚合视图");
                }

                // 主动取回（见 extractFromAe2Storage）也只是 FindMeExtended 提取器之外的
                // 一条补充通路，同样单独解析：解析不了就只关掉它自己，快照与去重照常工作。
                Method extractMethod = null;
                Object actionableModulate = null;
                Method actionSourceOfPlayer = null;
                try {
                    Class<?> aeKeyClass = Class.forName("appeng.api.stacks.AEKey");
                    Class<?> actionableClass = Class.forName("appeng.api.config.Actionable");
                    actionableModulate = actionableClass.getField("MODULATE").get(null);
                    Class<?> actionSourceClass = Class.forName(
                            "appeng.api.networking.security.IActionSource");
                    actionSourceOfPlayer = actionSourceClass.getMethod("ofPlayer",
                            net.minecraft.world.entity.player.Player.class);
                    extractMethod = storageClass.getMethod("extract", aeKeyClass, long.class,
                            actionableClass, actionSourceClass);
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    LOGGER.warn("AE2 主动取回接口不兼容，将只使用 FindMeExtended 的提取器", exception);
                }

                return new ReflectionAccess(common, radius, extractorList, pull, getCapability,
                        storageCapability, storageContents, itemKeyClass, toStack,
                        chestOrDriveClass, isPowered, cellCount, cellInventory, networkStorageClass,
                        extractMethod, actionableModulate, actionSourceOfPlayer);
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("AE2 API 不兼容，将仅使用 FindMeExtended 的普通容器支持", exception);
                return new ReflectionAccess(common, radius, extractorList, pull,
                        null, null, null, null, null, null, null, null, null, null, null, null, null);
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
         *
         * @param containerFilter 返回 {@code false} 的坐标直接跳过；先于方块状态查询判断，
         *                        这样被排除的容器连一次区块查询都不用做
         */
        private List<BlockEntity> collectNearbyBlockEntities(ServerPlayer player,
                                                             Predicate<BlockPos> containerFilter) {
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
                                || !containerFilter.test(pos)
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

        /**
         * 收集方块实体的 AE2 存储视图。
         * <p>
         * 返回 {@code true} 表示该方块实体<b>暴露了</b> AE2 存储视图，它的 Forge 物品处理器
         * 只是同一份库存的另一个视图，调用方不应再读一次（否则同一批材料会被算成两份）。
         * 注意这里判断的是「暴露」而不是「本次真的计入了内容」：保险库的仓与控制器共用同一个
         * MEStorage 对象，仓这一次会因为已访问而被跳过，但它的处理器依然不能读。
         * <p>
         * 视图按来源分两类。{@code physicalStorages} 是方块自己的库存（物品保险库、ME 箱和
         * ME 驱动器里的存储元件等）；{@code networkStorages} 是整个 ME 网络的聚合存储——
         * AE 接口在未配置时会把 {@code IStorageService.getInventory()} 当成自己的 STORAGE
         * 暴露出去，那个对象是 {@code appeng.me.storage.NetworkStorage}。存储总线把物品保险库
         * 挂进网络之后，保险库的内容就同时出现在这两类视图里，必须分开交给调用方去重。
         */
        private boolean collectAe2Snapshots(BlockEntity blockEntity, List<Ae2Snapshot> physicalStorages,
                                            List<Ae2Snapshot> networkStorages,
                                            Set<Object> visitedStorages) {
            if (getCapabilityMethod == null || ae2StorageCapability == null) {
                return false;
            }
            try {
                if (ae2ChestOrDriveClass.isInstance(blockEntity)
                        && Boolean.TRUE.equals(ae2IsPoweredMethod.invoke(blockEntity))) {
                    int cellCount = ae2CellCountMethod.invoke(blockEntity) instanceof Integer value
                            ? value : 0;
                    for (int cell = 0; cell < cellCount; cell++) {
                        addSnapshot(ae2CellInventoryMethod.invoke(blockEntity, cell), physicalStorages,
                                visitedStorages);
                    }
                    // ME 箱 / ME 驱动器的 MEStorage 是各个存储元件，和它自己那几个内部槽位不是同一份
                    // 库存，所以这里不能算「已覆盖」，物品处理器照旧要读。
                    return false;
                }
                Object capability = getCapabilityMethod.invoke(blockEntity,
                        ae2StorageCapability, null);
                if (capability == null) {
                    return false;
                }
                Object storage = capability.getClass().getMethod("orElse", Object.class)
                        .invoke(capability, new Object[]{null});
                if (storage == null) {
                    return false;
                }
                addSnapshot(storage, isNetworkStorage(storage) ? networkStorages : physicalStorages,
                        visitedStorages);
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("读取 AE2 方块实体 {} 的存储内容失败", blockEntity.getBlockPos(), exception);
                return false;
            }
        }

        /** 判断存储视图是不是整个 ME 网络的聚合存储，而不是某个方块自己的库存。 */
        private boolean isNetworkStorage(Object storage) {
            return ae2NetworkStorageClass != null && ae2NetworkStorageClass.isInstance(storage);
        }

        /**
         * 读取一份 AE2 存储视图的全部物品键。
         * 同一个存储对象只读一次：保险库的仓和控制器共用同一个 MEStorage，仓这次会被跳过。
         */
        private void addSnapshot(Object storage, List<Ae2Snapshot> output, Set<Object> visitedStorages) {
            if (storage == null || !visitedStorages.add(storage)) {
                return;
            }
            try {
                Object keyCounter = storageContentsMethod.invoke(storage);
                if (!(keyCounter instanceof Iterable<?> entries)) {
                    return;
                }
                Map<Object, Long> counts = new LinkedHashMap<>();
                for (Object entry : entries) {
                    if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                        continue;
                    }
                    Object key = mapEntry.getKey();
                    Object value = mapEntry.getValue();
                    long count = value instanceof Number number ? number.longValue() : 0L;
                    if (count > 0 && ae2ItemKeyClass.isInstance(key)) {
                        counts.merge(key, count, Long::sum);
                    }
                }
                if (!counts.isEmpty()) {
                    output.add(new Ae2Snapshot(counts));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("读取 AE2 存储视图失败", exception);
            }
        }

        /**
         * 把 AE2 视图折算成物品列表，并去掉「同一份库存被读了两遍」的部分。
         * <p>
         * 判据是内容包含：某个物理存储的每种物品，在网络聚合视图里都有至少同样多，就说明这份库存
         * 已经在网络里了（存储总线把它挂了上去），不能再单独计一遍。反过来，网络里没有这份库存时
         * ——说明该容器没接进这个网络——就照旧单独计入，不会漏掉。
         * <p>
         * 之所以拿内容而不是「谁属于谁」来判断：存储总线挂进网络的是它自己的一层包装
         * （{@code StorageBusPart$StorageBusInventory}），拿不到被包装的那个方块，对象身份对不上。
         * 而要求「每种物品都不少于」是很强的条件，只有真的同一份库存才会满足。
         */
        private void appendAe2Stacks(List<Ae2Snapshot> physicalStorages,
                                     List<Ae2Snapshot> networkStorages, List<ItemStack> output) {
            for (Ae2Snapshot physical : physicalStorages) {
                if (!isCoveredByAnyNetwork(physical, networkStorages)) {
                    appendSnapshotStacks(physical, output);
                }
            }
            for (Ae2Snapshot network : networkStorages) {
                appendSnapshotStacks(network, output);
            }
        }

        /** 判断某份库存是否已经被某个 AE 网络聚合视图完整覆盖。 */
        private static boolean isCoveredByAnyNetwork(Ae2Snapshot physical,
                                                     List<Ae2Snapshot> networkStorages) {
            for (Ae2Snapshot network : networkStorages) {
                if (physical.isCoveredBy(network)) {
                    return true;
                }
            }
            return false;
        }

        /** 将一份 AE2 存储视图里的物品键转换为普通 ItemStack 快照。 */
        private void appendSnapshotStacks(Ae2Snapshot snapshot, List<ItemStack> output) {
            for (Map.Entry<Object, Long> entry : snapshot.counts().entrySet()) {
                long count = entry.getValue();
                if (count <= 0) {
                    continue;
                }
                try {
                    int safeCount = (int) Math.min(count, Integer.MAX_VALUE);
                    ItemStack stack = (ItemStack) ae2ItemKeyToStackMethod.invoke(
                            entry.getKey(), safeCount);
                    if (!stack.isEmpty()) {
                        output.add(stack);
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    LOGGER.debug("转换 AE2 物品键失败", exception);
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
                // 先试自己这条 AE2 通路，再交给 FindMeExtended 的提取器。
                int fromAe2 = extractFromAe2Storage(blockEntity, player, stack, amount - extracted);
                if (fromAe2 > 0) {
                    extracted = Math.min(amount, extracted + fromAe2);
                    if (extracted >= amount) {
                        break;
                    }
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

        /**
         * 直接从方块实体的 AE2 存储里取回材料，作为 FindMeExtended AE2 提取器之外的补充通路。
         * <p>
         * FindMeExtended 的做法是「拿 ItemStack 反推 AE 物品键，再用 isSameItemSameTags
         * 逐字节比 NBT」。对我们的需求来说这一步既多余又脆弱：需求本来就来自 AE 物品键
         * （快照里是 {@code AEItemKey.toStack} 的产物），反推回去再比一次 NBT，
         * 中间任何一处 NBT 表示差异都会静默地取不回来。这里改成直接遍历存储给出的可用物品键，
         * 并且用<b>存储自己给出的那个键实例</b>去抽取——GTO 的物品保险库用的是引用同一性的
         * {@code AEKeyMap}，换个实例去取必然返回 0。
         * <p>
         * 匹配优先按「物品 + NBT 完全相同」，整个存储里都没有时才退回「同一种物品」。
         * 退回是有意的：配方只认物品本身（通配符输入更是如此），而 AE 侧的键可能带或不带 NBT，
         * 为了这个差异把材料判定成取不回来并不合理。
         */
        private int extractFromAe2Storage(BlockEntity blockEntity, ServerPlayer player,
                                          ItemStack requirement, int amount) {
            if (ae2ExtractMethod == null || ae2ActionableModulate == null
                    || ae2ActionSourceOfPlayer == null || getCapabilityMethod == null
                    || ae2StorageCapability == null || amount <= 0 || requirement.isEmpty()) {
                return 0;
            }
            try {
                Object capability = getCapabilityMethod.invoke(blockEntity,
                        ae2StorageCapability, null);
                if (capability == null) {
                    return 0;
                }
                Object storage = capability.getClass().getMethod("orElse", Object.class)
                        .invoke(capability, new Object[]{null});
                if (storage == null) {
                    return 0;
                }
                Object keyCounter = storageContentsMethod.invoke(storage);
                if (!(keyCounter instanceof Iterable<?> entries)) {
                    return 0;
                }
                Object actionSource = ae2ActionSourceOfPlayer.invoke(null, player);
                int pulled = 0;
                Object looseKey = null;
                for (Object entry : entries) {
                    if (pulled >= amount) {
                        break;
                    }
                    if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                        continue;
                    }
                    Object key = mapEntry.getKey();
                    Object value = mapEntry.getValue();
                    long available = value instanceof Number number ? number.longValue() : 0L;
                    if (available <= 0 || !ae2ItemKeyClass.isInstance(key)) {
                        continue;
                    }
                    ItemStack keyStack = toStack(key, 1);
                    if (keyStack.isEmpty()) {
                        continue;
                    }
                    if (ItemStack.isSameItemSameTags(keyStack, requirement)) {
                        pulled += extractKey(storage, key, player, actionSource, amount - pulled);
                    } else if (looseKey == null && ItemStack.isSameItem(keyStack, requirement)) {
                        looseKey = key;
                    }
                }
                if (pulled < amount && looseKey != null) {
                    pulled += extractKey(storage, looseKey, player, actionSource, amount - pulled);
                }
                return pulled;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("从 AE2 存储 {} 主动取回失败", blockEntity.getBlockPos(), exception);
                return 0;
            }
        }

        /** 用存储给出的那个键实例抽取指定数量，并把抽到的物品直接交给玩家。 */
        private int extractKey(Object storage, Object key, ServerPlayer player,
                               Object actionSource, int amount)
                throws ReflectiveOperationException {
            if (amount <= 0) {
                return 0;
            }
            Object result = ae2ExtractMethod.invoke(storage, key, (long) amount,
                    ae2ActionableModulate, actionSource);
            long count = result instanceof Number number ? number.longValue() : 0L;
            if (count <= 0) {
                return 0;
            }
            ItemStack stack = toStack(key, (int) Math.min(count, Integer.MAX_VALUE));
            if (stack.isEmpty()) {
                return 0;
            }
            ItemHandlerHelper.giveItemToPlayer(player, stack);
            return stack.getCount();
        }

        /** 把 AE 物品键转成普通物品堆，失败时返回空。 */
        private ItemStack toStack(Object key, int count) {
            try {
                Object stack = ae2ItemKeyToStackMethod.invoke(key, count);
                return stack instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return ItemStack.EMPTY;
            }
        }
    }

    /**
     * 一份 AE2 存储视图的物品键快照。
     * <p>
     * 保留原始物品键而不是先转成 ItemStack，是为了和网络聚合视图做「是否已被覆盖」的比对：
     * 物品键的 equals/hashCode 是稳定的，而按物品 + NBT 拼哈希反而容易出偏差；
     * 转换留到确定要计入时再做，被去重掉的那份就不用转。
     */
    private record Ae2Snapshot(Map<Object, Long> counts) {
        /** 本视图的每种物品在给定视图里是否都有至少同样多。 */
        private boolean isCoveredBy(Ae2Snapshot other) {
            for (Map.Entry<Object, Long> entry : counts.entrySet()) {
                if (other.counts.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    return false;
                }
            }
            return true;
        }
    }
}
