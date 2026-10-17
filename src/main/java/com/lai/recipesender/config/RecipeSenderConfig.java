package com.lai.recipesender.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本模组的可调参数，落在 {@code config/recipe_sender-common.toml}。
 *
 * <p>此前模组只有两个硬边界，而且都写在代码里：周围检索半径的 32 格上限
 * （{@code FindMeExtendedAdapter.MAX_SAFE_RADIUS}）和份数上限 512
 * （{@code RecipeIngredientSpec.MAX_BATCHES}）。现在它们都是配置项。
 *
 * <p><b>「模组 → 配置」按钮为什么需要自己注册</b>：Forge 只生成 toml，不生成界面。
 * 模组列表里那个按钮的可用状态直接取自 {@code ConfigScreenHandler.getScreenFactoryFor(modInfo).isPresent()}，
 * 没注册过扩展点就永远是灰的。本模组在 {@code ClientBootstrap} 里注册了
 * {@code ConfigScreenHandler.ConfigScreenFactory}，指向
 * {@code client.RecipeSenderConfigScreen}；toml 仍然可以直接编辑。
 *
 * <p><b>为什么是 COMMON 而不是 CLIENT</b>：检索与投送都发生在服务端
 * （{@code NearbyRecipeService} 用 {@code player.serverLevel()} 遍历区块，
 * {@code BoundTargetResolver} 在服务端校验目标），真正生效的是服务端那份配置。
 * 客户端读到的值只用来夹住 HUD 上显示的份数上限；多人服务器上两者不一致时以服务端为准
 * （服务端会用 {@code FORBIDDEN} 拒掉超限的请求）。
 *
 * <p><b>为什么读取时带兜底</b>：{@code ForgeConfigSpec.ConfigValue#get()} 在配置加载完成前
 * 会抛 {@code IllegalStateException}，而这些取值点分散在渲染与统计路径里，
 * 任何一个在加载完成前被调用都会把整条链路带崩。这里统一在读失败时回落到默认值。
 */
public final class RecipeSenderConfig {

    /** 配方管理距离的默认值（格）。 */
    public static final int DEFAULT_RECIPE_MANAGEMENT_DISTANCE = 128;
    /** 份数上限的默认值。 */
    public static final int DEFAULT_MAX_BATCHES = 8192;
    /** 流体支持的默认值：开（1.0.48 起默认打开，纯液体配方与含液体配方开箱即用）。 */
    public static final boolean DEFAULT_FLUID_SUPPORT = true;
    /** 单块份数的默认值。 */
    public static final int DEFAULT_CHUNK_BATCHES = 64;
    /** 单块份数的允许下限。 */
    public static final int MIN_CHUNK_BATCHES = 1;
    /** 单块份数的允许上限（与 {@code defineInRange} 里的上界必须一致）。 */
    public static final int MAX_CHUNK_BATCHES = 256;
    /** {@link #recipeManagementDistance()} 取这个值表示不限距离。 */
    public static final int UNLIMITED_DISTANCE = -1;
    /** 配方管理距离的允许上限（与 {@code defineInRange} 里的上界必须一致）。 */
    public static final int MAX_RECIPE_MANAGEMENT_DISTANCE = 1024;
    /** 份数上限的允许下限。 */
    public static final int MIN_BATCHES = 1;
    /** 份数上限的允许上限（与 {@code defineInRange} 里的上界必须一致）。 */
    public static final int MAX_BATCHES_LIMIT = 16384;

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private static final ForgeConfigSpec.IntValue RECIPE_MANAGEMENT_DISTANCE;
    private static final ForgeConfigSpec.IntValue MAX_BATCHES;
    private static final ForgeConfigSpec.BooleanValue FLUID_SUPPORT;
    private static final ForgeConfigSpec.IntValue CHUNK_BATCHES;

    /** 配置规格；由 {@link #register(ModLoadingContext)} 交给 Forge。 */
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("本模组愿意跨多远管理一次配方，以及一次最多处理多少份。").push("general");

        RECIPE_MANAGEMENT_DISTANCE = builder
                .comment("配方管理距离（格），默认 128，-1 表示不限。",
                        "它同时管住两个边界：",
                        "1) 绑定发送与直达的目标上限——目标离玩家超过这个距离就不发送，",
                        "   回执里会说「目标超出配方管理距离」；",
                        "2) 周围检索半径的上限——FindMeExtended 自己的搜索半径配置仍然生效，",
                        "   但会被这个值夹住（以前是代码里写死的 32 格）。",
                        "注意：周围检索是服务端逐区块遍历方块实体，半径 32 已经要扫约 25 个区块，",
                        "半径越大，每次统计（选择配方时每 10 tick 一次）的开销越大。")
                .defineInRange("recipeManagementDistance", DEFAULT_RECIPE_MANAGEMENT_DISTANCE,
                        UNLIMITED_DISTANCE, MAX_RECIPE_MANAGEMENT_DISTANCE);

        MAX_BATCHES = builder
                .comment("一次操作最多处理多少份配方，默认 8192。",
                        "份数越大，一次发送要模拟与搬运的材料越多，耗时与内存占用线性增长；",
                        "真正能发出多少仍然取决于目标容器容量与周围材料总量，这里只是上限。")
                .defineInRange("maxBatches", DEFAULT_MAX_BATCHES, MIN_BATCHES, MAX_BATCHES_LIMIT);

        FLUID_SUPPORT = builder
                .comment("流体支持开关，默认开启。",
                        "打开时：含流体的配方「一份」= 物品与流体都齐，缺任何一项都一份都不发；",
                        "  纯液体配方（只有流体输入）只走「中枢直达」这一个入口。",
                        "  流体只能来自绑定的「流体中枢」（管理界面 → 流体中枢页签，全局只允许一个），",
                        "  目前只有直达模式（反转键 + 绑定键，或直达快捷键）会搬运流体；",
                        "  目标容器必须能装流体，装不下会提示「目标无法放下流体」。",
                        "关闭时：配方里的流体输入被完全忽略，只搬运物品（与 1.0.43 之前的行为一致），",
                        "  纯液体配方因此无法发送。",
                        "反转与绑定发送始终只处理物品，不受这个开关影响。")
                .define("fluidSupport", DEFAULT_FLUID_SUPPORT);

        CHUNK_BATCHES = builder
                .comment("单块份数，默认 64。",
                        "一次发送会按这个粒度分块：每块先抽出材料、再放进目标、再抽下一块。",
                        "块越大，一次抽到手的材料越多（省服务端枚举次数），但失败时退回背包的余料也越多；",
                        "块越小，余料越好回收，代价是每块都要重新扫一遍来源容器。",
                        "64 是实测比较平衡的值：多数配方一份只有几种材料，一块的余料通常还能塞回背包。")
                .defineInRange("chunkBatches", DEFAULT_CHUNK_BATCHES, MIN_CHUNK_BATCHES, MAX_CHUNK_BATCHES);

        builder.pop();
        SPEC = builder.build();
    }

    private RecipeSenderConfig() {
    }

    /**
     * 把配置注册进 Forge；必须在模组构造期间调用。
     *
     * <p>参数是模组构造器注入的上下文，而不是 {@code ModLoadingContext.get()}：
     * 那个静态方法在 Forge 47.4 里已被标记为「将来删除」，而
     * {@code registerConfig} 本身没有过时，所以把实例传进来即可。
     */
    public static void register(ModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    /** 配方管理距离（格）；{@link #UNLIMITED_DISTANCE} 表示不限。 */
    public static int recipeManagementDistance() {
        return read(RECIPE_MANAGEMENT_DISTANCE, DEFAULT_RECIPE_MANAGEMENT_DISTANCE);
    }

    /** 一次操作允许的份数上限。 */
    public static int maxBatches() {
        return read(MAX_BATCHES, DEFAULT_MAX_BATCHES);
    }

    /** 流体支持是否开启；只影响直达模式，见配置注释。 */
    public static boolean fluidSupport() {
        return read(FLUID_SUPPORT, DEFAULT_FLUID_SUPPORT);
    }

    /** 单块份数：一次发送按这个粒度分块抽取与投放。 */
    public static int chunkBatches() {
        return read(CHUNK_BATCHES, DEFAULT_CHUNK_BATCHES);
    }

    /**
     * 写入配方管理距离并落盘；入参必须在
     * {@link #UNLIMITED_DISTANCE} ~ {@link #MAX_RECIPE_MANAGEMENT_DISTANCE} 之间。
     *
     * <p>写入口只给游戏内配置界面用。以前只有 toml 一条路，玩家想改就得退出游戏找文件、
     * 还要自己记住取值范围；现在界面负责校验，这里只做最后一道把关。
     *
     * @throws IllegalArgumentException 入参越界
     */
    public static void setRecipeManagementDistance(int distance) {
        if (distance < UNLIMITED_DISTANCE || distance > MAX_RECIPE_MANAGEMENT_DISTANCE) {
            throw new IllegalArgumentException("配方管理距离超出范围: " + distance);
        }
        RECIPE_MANAGEMENT_DISTANCE.set(distance);
        persist();
    }

    /**
     * 写入份数上限并落盘；入参必须在 {@link #MIN_BATCHES} ~ {@link #MAX_BATCHES_LIMIT} 之间。
     *
     * @throws IllegalArgumentException 入参越界
     */
    public static void setMaxBatches(int batches) {
        if (batches < MIN_BATCHES || batches > MAX_BATCHES_LIMIT) {
            throw new IllegalArgumentException("份数上限超出范围: " + batches);
        }
        MAX_BATCHES.set(batches);
        persist();
    }

    /** 写入流体支持开关并落盘。 */
    public static void setFluidSupport(boolean enabled) {
        FLUID_SUPPORT.set(enabled);
        persist();
    }

    /**
     * 写入单块份数并落盘；入参必须在
     * {@link #MIN_CHUNK_BATCHES} ~ {@link #MAX_CHUNK_BATCHES} 之间。
     *
     * @throws IllegalArgumentException 入参越界
     */
    public static void setChunkBatches(int batches) {
        if (batches < MIN_CHUNK_BATCHES || batches > MAX_CHUNK_BATCHES) {
            throw new IllegalArgumentException("单块份数超出范围: " + batches);
        }
        CHUNK_BATCHES.set(batches);
        persist();
    }

    /**
     * 把内存里的值写回 toml。
     *
     * <p>{@code ConfigValue#set} 只改内存，不落盘；不调这一步的话关掉游戏改动就丢了。
     *
     * <p>为什么带 {@code isLoaded} 判断和 try/catch：配置界面理论上可能在任何时刻被打开
     * （模组列表就在主菜单里），而配置还没被 Forge 挂载时 {@code save()} 会抛异常。
     * 那种情况下界面已经改过内存值，本次会话仍然按新值跑，只是没落盘——记一条日志就够，
     * 不该让玩家看到一个崩掉的界面。
     */
    private static void persist() {
        if (!SPEC.isLoaded()) {
            LOGGER.warn("配置尚未加载完成，本次改动只在内存里生效，未写入 recipe_sender-common.toml");
            return;
        }
        try {
            SPEC.save();
        } catch (RuntimeException failed) {
            LOGGER.warn("写入 recipe_sender-common.toml 失败，本次改动只在内存里生效", failed);
        }
    }

    /** 读取配置值；配置尚未加载完成时回落到默认值。 */
    private static <T> T read(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T loaded = value.get();
            return loaded != null ? loaded : fallback;
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }
}
