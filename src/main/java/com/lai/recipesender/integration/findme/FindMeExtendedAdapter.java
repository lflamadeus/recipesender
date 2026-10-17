package com.lai.recipesender.integration.findme;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.model.RecipeFluidSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 隔离 FindMeExtended 和 AE2 的可选兼容逻辑。 */
public final class FindMeExtendedAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");
    private static final String FIND_ME_ID = "findmeextended";
    private static final String AE2_ID = "ae2";
    /** 读不到 FindMeExtended 半径配置时使用的兜底半径。 */
    private static final int FALLBACK_RADIUS = 8;
    /**
     * FindMeExtended 的包名候选，按新到旧排列。
     * <p>
     * 1.0.2 起作者把包名从 {@code com.lflamadeus.findmeextended} 改成了 {@code com.lai.findmeextended}。
     * 兼容层原本写死旧包名，改名之后反射初始化直接抛 ClassNotFoundException、
     * {@link #isAvailable()} 恒为 false，表现就是「按住反转键完全没有反应」（1.0.13 的故障）。
     * 这里两个都试：新版走新包名，仍在用 1.0.1 及更早版本的玩家也不会因此丢掉反转搜索。
     */
    private static final String[] FIND_ME_PACKAGES = {
            "com.lai.findmeextended", "com.lflamadeus.findmeextended"};

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
     * 按包名候选加载 FindMeExtended 的类。
     *
     * @param relativeName 相对包名的类名，可以带子包（例如 {@code api.FindMeBlacklistApi}）
     * @throws ClassNotFoundException 所有候选包名里都没有这个类
     */
    private static Class<?> findMeClass(String relativeName) throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (String packageName : FIND_ME_PACKAGES) {
            try {
                return Class.forName(packageName + "." + relativeName);
            } catch (ClassNotFoundException exception) {
                failure = exception;
            }
        }
        throw new ClassNotFoundException("FindMeExtended 里找不到 " + relativeName
                + "（已尝试 " + String.join("、", FIND_ME_PACKAGES) + "）", failure);
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
                Class<?> apiClass = findMeClass("api.FindMeBlacklistApi");
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
     * @param relevant        返回 {@code false} 的物品不进快照，也不会被模拟抽取（见
     *                        {@link #copyHandlerContents(IItemHandler, List, Predicate)}）
     */
    public static List<ItemStack> snapshotNearby(ServerPlayer player,
                                                 Predicate<BlockPos> containerFilter,
                                                 Predicate<ItemStack> relevant) {
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
                        .ifPresent(handler -> copyHandlerContents(handler, result, relevant));
            }
        }
        reflection.appendAe2Stacks(physicalStorages, networkStorages, result);
        return List.copyOf(result);
    }

    /**
     * 只读指定容器（不枚举世界）的物品快照，用于物品中枢的份数统计。
     *
     * <p>与 {@link #snapshotNearby} 是同一条读取路径，唯一区别是容器列表由调用方给出：
     * 中枢直达的成本应该只和中枢本身的大小成正比，而不是和整个基地的容器数量成正比。
     *
     * <p>为什么不能直接读 Forge 物品处理器：GTO 的物品保险库把同一份库存（keyMap）同时挂成
     * Forge 处理器和 AE2 MEStorage，而处理器那一侧的 {@code getStackInSlot} 只返回第一个物品键，
     * 读它既不准也不完整。所以这里与周围扫描一样，<b>先问 AE2 存储，问不到才退回处理器</b>。
     */
    public static List<ItemStack> snapshotContainers(List<BlockEntity> containers,
                                                     Predicate<ItemStack> relevant) {
        if (containers == null || containers.isEmpty()) {
            return List.of();
        }
        ReflectionAccess reflection = getAccess();
        List<ItemStack> result = new ArrayList<>();
        List<Ae2Snapshot> physicalStorages = new ArrayList<>();
        List<Ae2Snapshot> networkStorages = new ArrayList<>();
        Set<Object> visitedAe2Storages = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BlockEntity blockEntity : containers) {
            if (blockEntity == null || blockEntity.isRemoved()) {
                continue;
            }
            boolean ae2 = reflection != null && reflection.collectAe2Snapshots(blockEntity,
                    physicalStorages, networkStorages, visitedAe2Storages);
            if (!ae2) {
                blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                        .ifPresent(handler -> copyHandlerContents(handler, result, relevant));
            }
        }
        if (reflection != null) {
            reflection.appendAe2Stacks(physicalStorages, networkStorages, result);
        }
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

    /**
     * 与 FindMeExtended 完全一致的「这个方块玩家能不能交互」判定。
     * <p>
     * 反转模式的取回把物品交给 FindMeExtended 的提取器，而它的提取器在动手前会问一遍这件事——
     * 领地、锁箱之类的保护模组正是靠取消 {@code RightClickBlock} 拦住玩家的。直达模式自己抽料、
     * 自己插回，绕过了那道闸门，于是本该打不开的容器也能被搬空。这里照抄它的做法：按同样的参数
     * 发一个事件，没人取消就当玩家能碰。
     * <p>
     * 事件参数刻意与 FindMeExtended 逐字对齐（主手、{@code Vec3.ZERO}、{@code Direction.UP}、
     * 不命中内部），保护模组收到的请求与反转模式那次一模一样，不会出现「反转拿不到、直达却拿得到」。
     */
    public static boolean canInteract(ServerPlayer player, BlockEntity blockEntity) {
        if (player == null || blockEntity == null) {
            return false;
        }
        Level level = blockEntity.getLevel();
        if (level == null) {
            return false;
        }
        BlockPos pos = blockEntity.getBlockPos();
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(new Vec3(0, 0, 0), Direction.UP, pos, false));
        return !MinecraftForge.EVENT_BUS.post(event);
    }

    /**
     * 读一个方块实体的流体内容，供「流体中枢」规划用。
     * <p>
     * 与物品快照同一套优先级：AE2 存储视图优先（ME 驱动器里的流体元件、挂在网络上的储罐都算），
     * 读不到再看 Forge 流体能力（格雷的流体仓、原版流体容器）。读操作不经过
     * {@link #canInteract} 保护门——统计每 10 tick 跑一次，反复发交互事件会给保护类模组刷噪音，
     * 真正抽不动时由直达的差额提示如实报出。
     */
    public static List<FluidStack> hubFluids(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return List.of();
        }
        ReflectionAccess reflection = getAccess();
        if (reflection != null) {
            List<FluidStack> fluids = reflection.fluids(blockEntity);
            if (!fluids.isEmpty()) {
                return fluids;
            }
        }
        return forgeFluidContents(blockEntity);
    }

    /**
     * 这个方块实体能不能当流体中枢。
     * <p>
     * 判定刻意宽松：AE2 存储视图一律算数（驱动器里有没有流体元件只有抽的时候才知道），
     * 有 Forge 流体能力的方块也算数。绑定时用这个判定替掉物品的「没有容器就拒绝」。
     */
    public static boolean hasFluidSource(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        // 纯 Forge 流体罐不经过任何反射，所以先单独判一次：FindMeExtended 缺失或兼容层
        // 初始化失败时（反射对象为 null），普通储罐仍然应该能当流体中枢。
        if (forgeFluidHandler(blockEntity) != null) {
            return true;
        }
        ReflectionAccess reflection = getAccess();
        return reflection != null && reflection.hasFluidSource(blockEntity);
    }

    /** 取一个方块实体的 Forge 流体能力（先试无面，再逐个面）；没有时返回 {@code null}。 */
    public static IFluidHandler fluidHandlerOf(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        ReflectionAccess reflection = getAccess();
        if (reflection != null) {
            IFluidHandler handler = reflection.fluidHandler(blockEntity);
            if (handler != null) {
                return handler;
            }
        }
        return forgeFluidHandler(blockEntity);
    }

    /**
     * 纯 Forge 流体能力查找，不依赖 FindMeExtended 与 AE2 的反射对象。
     *
     * <p>与物品侧同一条规矩：先试无面再遍历六个面，格雷机器按面分发能力，先试无面能同时
     * 覆盖「不看面的储罐」与「只认自己那几个面的机器」。
     */
    private static IFluidHandler forgeFluidHandler(BlockEntity blockEntity) {
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

    /** 直接读 Forge 流体罐里的内容，用于反射对象不可用的兜底。 */
    private static List<FluidStack> forgeFluidContents(BlockEntity blockEntity) {
        IFluidHandler handler = forgeFluidHandler(blockEntity);
        if (handler == null) {
            return List.of();
        }
        List<FluidStack> fluids = new ArrayList<>();
        try {
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack stack = handler.getFluidInTank(tank);
                if (stack != null && !stack.isEmpty()) {
                    fluids.add(stack.copy());
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("读取方块实体 {} 的流体罐失败", blockEntity.getBlockPos(), exception);
        }
        return List.copyOf(fluids);
    }

    /** 直接从 Forge 流体罐里抽流体，用于反射对象不可用的兜底。 */
    private static int forgeDrain(BlockEntity blockEntity, FluidStack requirement, int amount,
                                  List<FluidStack> output) {
        IFluidHandler handler = forgeFluidHandler(blockEntity);
        if (handler == null) {
            return 0;
        }
        try {
            FluidStack drained = handler.drain(
                    new FluidStack(requirement.getFluid(), amount, requirement.getTag()),
                    IFluidHandler.FluidAction.EXECUTE);
            if (drained != null && !drained.isEmpty()) {
                output.add(drained.copy());
                return drained.getAmount();
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("从方块实体 {} 抽流体失败", blockEntity.getBlockPos(), exception);
        }
        return 0;
    }

    /**
     * 从一个方块实体里抽出指定流体，抽到的追加到 {@code output}。
     *
     * @return 实际抽出的 mB 数
     */
    public static int drainFluid(ServerPlayer player, BlockEntity blockEntity, FluidStack requirement,
                                 int amount, List<FluidStack> output) {
        if (player == null || blockEntity == null || output == null
                || requirement == null || requirement.isEmpty() || amount <= 0) {
            return 0;
        }
        // 与物品同一条保护门：受保护模组拦下的容器不算可交互，不能从里面抽流体。
        if (!canInteract(player, blockEntity)) {
            return 0;
        }
        ReflectionAccess reflection = getAccess();
        if (reflection != null) {
            int taken = reflection.drainFluid(blockEntity, player, requirement, amount, output);
            if (taken > 0) {
                return taken;
            }
        }
        return forgeDrain(blockEntity, requirement, amount, output);
    }

    /**
     * 扫一遍周围的容器，返回可以抽料的方块实体表。
     *
     * <p>直达按块搬运时会反复调用 {@link #pullAllTo}；每次调用都重新枚举一遍附近方块实体是
     * 纯浪费（半径 128 格要遍历上百个区块，而方块实体在这几毫秒里不会变）。所以枚举与抽取拆开：
     * 调用方一次发送只枚举一次，之后拿着这张表反复抽。
     *
     * <p>表里存的是方块实体本身，抽取时读的仍然是实时的槽位内容，所以别人在这期间搬走了材料
     * 也不会抽到空气；方块被拆掉的情况由 {@link #pullAllTo} 逐块跳过。
     */
    public static List<BlockEntity> nearbyContainers(ServerPlayer player, Predicate<BlockPos> containerFilter) {
        ReflectionAccess reflection = getAccess();
        if (player == null || reflection == null) {
            return List.of();
        }
        return reflection.collectNearbyBlockEntities(player, containerFilter);
    }

    /**
     * 从已经枚举好的容器表里把材料直接抽进 {@code output}，<b>不经过玩家背包</b>。
     * <p>
     * 直达模式要的是「周围容器 → 绑定容器」：材料在服务端主线程上从一个容器搬进另一个容器，
     * 中间不落背包，也就不该受背包空位、掉落物和背包合并的影响。FindMeExtended 的提取器做不到
     * 这一点——它内部一律 {@code giveItemToPlayer}，所以这条路自己走 AE2 存储和 Forge 物品处理器。
     * 代价是只认这两条通路：别的模组注册给 FindMeExtended 的专用提取器在直达模式里用不上，
     * 那些容器里的材料会被算作「周围材料不足」而不是搬错地方。
     *
     * @param containers 由 {@link #nearbyContainers} 枚举出来的容器表；为 {@code null} 时按空表处理
     * @param output 抽出来的材料按抽取顺序追加到这里；调用方负责把没投出去的部分还回去
     * @return 每种材料实际抽出的数量，顺序与 {@code requirements} 一致
     */
    public static int[] pullAllTo(ServerPlayer player, List<BlockEntity> containers,
                                  List<ItemStack> requirements, List<ItemStack> output) {
        ReflectionAccess reflection = getAccess();
        int[] pulled = new int[requirements.size()];
        if (player == null || reflection == null || output == null || requirements.isEmpty()
                || containers == null || containers.isEmpty()) {
            return pulled;
        }
        List<BlockEntity> alive = new ArrayList<>(containers.size());
        for (BlockEntity container : containers) {
            if (container != null && !container.isRemoved()) {
                alive.add(container);
            }
        }
        if (alive.isEmpty()) {
            return pulled;
        }
        for (int index = 0; index < requirements.size(); index++) {
            ItemStack requirement = requirements.get(index);
            if (requirement.isEmpty() || requirement.getCount() <= 0) {
                continue;
            }
            pulled[index] = reflection.extractTo(alive, player,
                    requirement.copyWithCount(1), requirement.getCount(), output);
        }
        return pulled;
    }

    /**
     * 复制 Forge 物品处理器的内容，不修改容器状态。
     * <p>
     * 只统计与本次配方相关、且抽得出来的槽位。这份快照既决定「周围现有 N 份」，又决定取回时挑哪些
     * 物品堆当候选，把抽不出的槽位算进去就会显示虚高的份数，取回时那一项还只能静默失败。格雷机器的
     * 输入槽和输入总线正是「看得见、取不出」：{@code getStackInSlot} 没有闸门，而 {@code extractItem}
     * 在 {@code canCapOutput()} 为假时直接返回空（输入槽的能力 IO 是 {@code IO.IN}）。
     * 模拟抽取能预言取回的结果，因为取回通路最终调用的也是 {@code extractItem}。
     * <p>
     * 判据是槽位行为而不是容器类型：GT 按面合并 trait，同一台机器的输入槽和输出槽可能来自
     * 同一个处理器，而输出槽（{@code IO.OUT}）是抽得出来的，整块排除会误伤。
     * <p>
     * 模拟抽取只对 {@code relevant} 放行的物品做，因为别人的处理器可能在模拟抽取时改自己的状态：
     * GTO 的无人机仓把无人机列表（{@code Drone[]}）挂在物品处理器上，它的 {@code extractItem}
     * 不看 {@code simulate} 就先把缓存清空，于是「这份无人机取得出来吗」这一问会让无人机控制中心
     * 再也看不到仓里的无人机（1.0.37 修）。只探与本次配方相关的物品，就不会再去碰那些和本次取回
     * 毫无关系的槽位——无人机、机器里的电路之类永远不可能出现在配方材料里。
     *
     * @param relevant 返回 {@code false} 的物品既不计入快照，也不做模拟抽取
     */
    private static void copyHandlerContents(IItemHandler handler, List<ItemStack> output,
                                            Predicate<ItemStack> relevant) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty() || !relevant.test(stack)) {
                continue;
            }
            if (!canExtract(handler, slot)) {
                continue;
            }
            output.add(stack.copy());
        }
    }

    /**
     * 模拟抽取一个槽位，判断这份材料是否真的取不回来。
     * <p>
     * 调用方必须先确认这份物品与本次配方相关（见 {@link #copyHandlerContents}）：模拟抽取不是
     * 纯查询，别人的 {@code extractItem} 可能顺手改自己的状态。
     */
    private static boolean canExtract(IItemHandler handler, int slot) {
        try {
            return !handler.extractItem(slot, 1, true).isEmpty();
        } catch (RuntimeException exception) {
            LOGGER.debug("模拟抽取槽位 {} 失败，本次不统计该槽位", slot, exception);
            return false;
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
                LOGGER.error("无法初始化 FindMeExtended 兼容层，反转搜索已禁用（按住反转键不会有反应）",
                        exception);
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
            Class<?> ae2FluidKeyClass,
            Method ae2FluidKeyToStackMethod,
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
            Class<?> findMeModClass = findMeClass("FindMeMod");
            Object config = findMeModClass.getField("CONFIG").get(null);
            Object common = config.getClass().getField("COMMON").get(config);
            Field radius = common.getClass().getField("RADIUS_RANGE");

            Object extractorValue = findMeModClass.getField("BLOCK_EXTRACTORS").get(null);
            if (!(extractorValue instanceof List<?> extractorList)) {
                throw new IllegalStateException("FindMeExtended 提取器列表类型不兼容");
            }
            Class<?> pullerClass = findMeClass("IInventoryPuller");
            Method pull = pullerClass.getMethod("pull", BlockEntity.class, ItemStack.class,
                    int.class, net.minecraft.world.entity.player.Player.class);

            if (!ModList.get().isLoaded(AE2_ID)) {
                return new ReflectionAccess(common, radius, extractorList, pull,
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null);
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

                // 流体键只在「流体中枢」用到，解析不了就只关掉流体这一条路，物品支持照常。
                Class<?> fluidKeyClass = null;
                Method fluidToStack = null;
                try {
                    fluidKeyClass = Class.forName("appeng.api.stacks.AEFluidKey");
                    fluidToStack = fluidKeyClass.getMethod("toStack", int.class);
                } catch (ReflectiveOperationException exception) {
                    LOGGER.warn("未找到 appeng.api.stacks.AEFluidKey，流体中枢将只能读取 Forge 流体能力");
                }

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
                        fluidKeyClass, fluidToStack,
                        chestOrDriveClass, isPowered, cellCount, cellInventory, networkStorageClass,
                        extractMethod, actionableModulate, actionSourceOfPlayer);
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("AE2 API 不兼容，将仅使用 FindMeExtended 的普通容器支持", exception);
                return new ReflectionAccess(common, radius, extractorList, pull,
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null);
            }
        }

        /**
         * 读取 FindMeExtended 的半径配置，再夹到本模组的配方管理距离。
         *
         * <p>半径的主人是 FindMeExtended（它自己的搜索也用这个值），本模组只是借来扫同一片区域，
         * 所以不去覆盖它，只做一个上限：以前这个上限是写死的 32 格，现在是配置项
         * （{@code recipeManagementDistance}，默认 128，-1 表示不限）。
         */
        private int getSafeRadius() {
            try {
                int configuredRadius = radiusField.getInt(commonConfig);
                int limit = RecipeSenderConfig.recipeManagementDistance();
                if (limit >= 0 && configuredRadius > limit) {
                    LOGGER.warn("FindMeExtended 搜索半径 {} 超过配方管理距离 {}，已限制为 {}",
                            configuredRadius, limit, limit);
                    return Math.max(0, limit);
                }
                return Math.max(0, configuredRadius);
            } catch (IllegalAccessException exception) {
                LOGGER.warn("读取 FindMeExtended 搜索半径失败，使用默认值 {}", FALLBACK_RADIUS, exception);
                return FALLBACK_RADIUS;
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
                int fromAe2 = extractFromAe2Storage(blockEntity, player, stack, amount - extracted,
                        taken -> ItemHandlerHelper.giveItemToPlayer(player, taken));
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
                                          ItemStack requirement, int amount,
                                          Consumer<ItemStack> sink) {
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
                // 保护门（1.0.38）懒问：null = 还没问过，只在确实找到匹配的材料、马上就要动手时
                // 才发一次伪造右键。整个存储里没有我们要的东西时，别人一个假事件都收不到。
                Boolean allowed = null;
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
                        if (allowed == null) {
                            allowed = canInteract(player, blockEntity);
                        }
                        if (!allowed) {
                            // 保护模组拦下了这个容器（领地、锁箱之类）：一次都不抽，也不再试别的键。
                            return 0;
                        }
                        pulled += extractKey(storage, key, actionSource, amount - pulled, sink);
                    } else if (looseKey == null && ItemStack.isSameItem(keyStack, requirement)) {
                        looseKey = key;
                    }
                }
                if (pulled < amount && looseKey != null) {
                    if (allowed == null) {
                        allowed = canInteract(player, blockEntity);
                    }
                    if (allowed) {
                        pulled += extractKey(storage, looseKey, actionSource, amount - pulled, sink);
                    }
                }
                return pulled;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("从 AE2 存储 {} 主动取回失败", blockEntity.getBlockPos(), exception);
                return 0;
            }
        }

        /**
         * 用存储给出的那个键实例抽取指定数量，抽到的物品交给 {@code sink}。
         * <p>
         * 交付对象由调用方决定：反转取回传「塞进玩家背包」，直达投放传「收进待投料列表」。
         * 这里是两条路唯一的分叉点，抽取本身完全共用。
         */
        private int extractKey(Object storage, Object key, Object actionSource, int amount,
                               Consumer<ItemStack> sink)
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
            sink.accept(stack);
            return stack.getCount();
        }

        /**
         * 抽出材料并收进调用方给的列表，不经过玩家背包。
         * <p>
         * 与 {@link #pull} 的区别只在交付对象：那条路最后交给 FindMeExtended 的提取器，而提取器
         * 内部一律把物品塞进玩家背包，没法换落点。这里自己抽：AE2 通路复用同一套「用存储给出的键
         * 实例去抽」的匹配，Forge 通路按槽位 {@code extractItem}——正是 {@link #copyHandlerContents}
         * 统计时模拟过的同一次调用，统计说抽得出来才抽得出来。
         * <p>
         * 保护门（{@link #canInteract}）不在这一层问，而是下移到「确实找到了要抽的那一堆」之后
         * （{@link #extractFromAe2Storage} 命中匹配键、{@link #extractFromHandler} 命中匹配槽位）。
         * 这一层是对搜索范围内<b>每一个</b>方块实体都试一遍的循环，在这里问等于为一次注定落空的
         * 尝试也发一个伪造的右键事件——搜索范围是 32 格立方体，一次取回能给整合包里所有监听右键的
         * 模组送出几百个假事件。下移之后，只有真正会动那个容器的时候才问。
         */
        private int extractTo(List<BlockEntity> containers, ServerPlayer player, ItemStack stack,
                              int amount, List<ItemStack> output) {
            int extracted = 0;
            for (BlockEntity blockEntity : containers) {
                if (extracted >= amount) {
                    break;
                }
                int fromAe2 = extractFromAe2Storage(blockEntity, player, stack, amount - extracted,
                        output::add);
                if (fromAe2 > 0) {
                    extracted = Math.min(amount, extracted + fromAe2);
                    if (extracted >= amount) {
                        break;
                    }
                }
                extracted = Math.min(amount, extracted
                        + extractFromHandler(blockEntity, player, stack, amount - extracted, output));
            }
            return extracted;
        }

        /** 只读地问一句：这个处理器里有没有我们要的东西（同物品同 NBT）。 */
        private static boolean holds(IItemHandler handler, ItemStack requirement) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!inSlot.isEmpty() && ItemStack.isSameItemSameTags(inSlot, requirement)) {
                    return true;
                }
            }
            return false;
        }

        /** 按 Forge 物品处理器逐槽抽出材料，抽到的追加到 {@code output}。 */
        private int extractFromHandler(BlockEntity blockEntity, ServerPlayer player,
                                       ItemStack requirement, int amount, List<ItemStack> output) {
            if (amount <= 0 || requirement.isEmpty()) {
                return 0;
            }
            int[] extracted = new int[1];
            try {
                blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                        .ifPresent(handler -> {
                            if (!holds(handler, requirement)) {
                                // 里面根本没有我们要的东西：连保护门都不问。
                                return;
                            }
                            if (!canInteract(player, blockEntity)) {
                                return;
                            }
                            int remaining = amount;
                            for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                                ItemStack inSlot = handler.getStackInSlot(slot);
                                if (inSlot.isEmpty()
                                        || !ItemStack.isSameItemSameTags(inSlot, requirement)) {
                                    continue;
                                }
                                ItemStack taken = handler.extractItem(slot, remaining, false);
                                if (taken.isEmpty()) {
                                    continue;
                                }
                                output.add(taken);
                                remaining -= taken.getCount();
                            }
                            extracted[0] = amount - remaining;
                        });
            } catch (RuntimeException exception) {
                LOGGER.debug("从 {} 的物品处理器抽出材料失败", blockEntity.getBlockPos(), exception);
            }
            return extracted[0];
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

        /** 把 AE 流体键转成流体堆，失败时返回空。 */
        private FluidStack toFluidStack(Object key, int amount) {
            if (ae2FluidKeyToStackMethod == null || amount <= 0) {
                return FluidStack.EMPTY;
            }
            try {
                Object stack = ae2FluidKeyToStackMethod.invoke(key, amount);
                return stack instanceof FluidStack fluidStack ? fluidStack : FluidStack.EMPTY;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return FluidStack.EMPTY;
            }
        }

        /** 读一个方块实体的流体内容：AE2 存储视图优先，读不到再看 Forge 流体能力。 */
        private List<FluidStack> fluids(BlockEntity blockEntity) {
            List<FluidStack> fromAe2 = ae2Fluids(blockEntity);
            return fromAe2.isEmpty() ? handlerFluids(blockEntity) : fromAe2;
        }

        /** 该方块实体是否有任何一条能取流体的通路。 */
        private boolean hasFluidSource(BlockEntity blockEntity) {
            return ae2Storage(blockEntity) != null || fluidHandler(blockEntity) != null;
        }

        /** 取方块实体的 AE2 存储对象，没有时返回 null。 */
        private Object ae2Storage(BlockEntity blockEntity) {
            if (getCapabilityMethod == null || ae2StorageCapability == null) {
                return null;
            }
            try {
                Object capability = getCapabilityMethod.invoke(blockEntity, ae2StorageCapability, null);
                if (capability == null) {
                    return null;
                }
                return capability.getClass().getMethod("orElse", Object.class)
                        .invoke(capability, new Object[]{null});
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("读取 {} 的 AE2 存储失败", blockEntity.getBlockPos(), exception);
                return null;
            }
        }

        /** 取方块实体的 Forge 流体能力：先试无面，再逐个面（格雷机器按面分发）。 */
        private IFluidHandler fluidHandler(BlockEntity blockEntity) {
            try {
                IFluidHandler direct = blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, null)
                        .orElse(null);
                if (direct != null) {
                    return direct;
                }
                for (Direction side : Direction.values()) {
                    IFluidHandler sided = blockEntity
                            .getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
                    if (sided != null) {
                        return sided;
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("读取 {} 的流体能力失败", blockEntity.getBlockPos(), exception);
            }
            return null;
        }

        /** 读 AE2 存储视图里的全部流体键。 */
        private List<FluidStack> ae2Fluids(BlockEntity blockEntity) {
            if (ae2FluidKeyClass == null || ae2FluidKeyToStackMethod == null
                    || storageContentsMethod == null) {
                return List.of();
            }
            Object storage = ae2Storage(blockEntity);
            if (storage == null) {
                return List.of();
            }
            List<FluidStack> result = new ArrayList<>();
            try {
                Object keyCounter = storageContentsMethod.invoke(storage);
                if (!(keyCounter instanceof Iterable<?> entries)) {
                    return List.of();
                }
                for (Object entry : entries) {
                    if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                        continue;
                    }
                    Object key = mapEntry.getKey();
                    Object value = mapEntry.getValue();
                    long count = value instanceof Number number ? number.longValue() : 0L;
                    if (count <= 0 || !ae2FluidKeyClass.isInstance(key)) {
                        continue;
                    }
                    FluidStack stack = toFluidStack(key, (int) Math.min(count, Integer.MAX_VALUE));
                    if (!stack.isEmpty()) {
                        result.add(stack);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("读取 {} 的 AE2 流体存储失败", blockEntity.getBlockPos(), exception);
            }
            return result;
        }

        /** 读 Forge 流体能力的各个罐。 */
        private List<FluidStack> handlerFluids(BlockEntity blockEntity) {
            IFluidHandler handler = fluidHandler(blockEntity);
            if (handler == null) {
                return List.of();
            }
            List<FluidStack> result = new ArrayList<>();
            try {
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack inTank = handler.getFluidInTank(tank);
                    if (!inTank.isEmpty()) {
                        result.add(inTank.copy());
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("读取 {} 的流体罐失败", blockEntity.getBlockPos(), exception);
            }
            return result;
        }

        /** 从一个方块实体里抽出指定流体：AE2 通路优先，不足再看 Forge 流体能力。 */
        private int drainFluid(BlockEntity blockEntity, ServerPlayer player, FluidStack requirement,
                               int amount, List<FluidStack> output) {
            int drained = extractFluidFromAe2(blockEntity, player, requirement, amount, output);
            if (drained >= amount) {
                return drained;
            }
            return drained + drainFromHandler(blockEntity, requirement, amount - drained, output);
        }

        /**
         * 从 AE2 存储里抽流体。
         * <p>
         * 与物品完全同一套做法：遍历存储给出的可用键，用<b>存储自己给出的那个键实例</b>去抽，
         * 匹配用 {@code FluidStack.isFluidEqual}（流体 + NBT）。
         */
        private int extractFluidFromAe2(BlockEntity blockEntity, ServerPlayer player,
                                        FluidStack requirement, int amount, List<FluidStack> output) {
            if (ae2ExtractMethod == null || ae2ActionableModulate == null
                    || ae2ActionSourceOfPlayer == null || ae2FluidKeyClass == null
                    || ae2FluidKeyToStackMethod == null || storageContentsMethod == null
                    || amount <= 0) {
                return 0;
            }
            Object storage = ae2Storage(blockEntity);
            if (storage == null) {
                return 0;
            }
            try {
                Object keyCounter = storageContentsMethod.invoke(storage);
                if (!(keyCounter instanceof Iterable<?> entries)) {
                    return 0;
                }
                Object actionSource = ae2ActionSourceOfPlayer.invoke(null, player);
                int drained = 0;
                for (Object entry : entries) {
                    if (drained >= amount) {
                        break;
                    }
                    if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                        continue;
                    }
                    Object key = mapEntry.getKey();
                    Object value = mapEntry.getValue();
                    long available = value instanceof Number number ? number.longValue() : 0L;
                    if (available <= 0 || !ae2FluidKeyClass.isInstance(key)) {
                        continue;
                    }
                    FluidStack keyFluid = toFluidStack(key, 1);
                    if (keyFluid.isEmpty() || !RecipeFluidSpec.sameFluid(keyFluid, requirement)) {
                        continue;
                    }
                    drained += extractFluidKey(storage, key, actionSource, amount - drained, output);
                }
                return drained;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("从 AE2 存储 {} 抽流体失败", blockEntity.getBlockPos(), exception);
                return 0;
            }
        }

        /** 用存储给出的那个流体键实例抽取指定 mB，抽到的交给 {@code output}。 */
        private int extractFluidKey(Object storage, Object key, Object actionSource, int amount,
                                    List<FluidStack> output) throws ReflectiveOperationException {
            if (amount <= 0) {
                return 0;
            }
            Object result = ae2ExtractMethod.invoke(storage, key, (long) amount,
                    ae2ActionableModulate, actionSource);
            long count = result instanceof Number number ? number.longValue() : 0L;
            if (count <= 0) {
                return 0;
            }
            FluidStack stack = toFluidStack(key, (int) Math.min(count, Integer.MAX_VALUE));
            if (stack.isEmpty()) {
                return 0;
            }
            output.add(stack);
            return stack.getAmount();
        }

        /** 按 Forge 流体能力逐罐抽出指定流体。 */
        private int drainFromHandler(BlockEntity blockEntity, FluidStack requirement, int amount,
                                     List<FluidStack> output) {
            IFluidHandler handler = fluidHandler(blockEntity);
            if (handler == null || amount <= 0) {
                return 0;
            }
            int drained = 0;
            try {
                for (int tank = 0; tank < handler.getTanks() && drained < amount; tank++) {
                    FluidStack inTank = handler.getFluidInTank(tank);
                    if (inTank.isEmpty() || !RecipeFluidSpec.sameFluid(inTank, requirement)) {
                        continue;
                    }
                    FluidStack taken = handler.drain(
                            new FluidStack(requirement.getFluid(), amount - drained,
                                    requirement.getTag()),
                            IFluidHandler.FluidAction.EXECUTE);
                    if (taken.isEmpty()) {
                        continue;
                    }
                    output.add(taken);
                    drained += taken.getAmount();
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("从 {} 的流体能力抽流体失败", blockEntity.getBlockPos(), exception);
            }
            return drained;
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
