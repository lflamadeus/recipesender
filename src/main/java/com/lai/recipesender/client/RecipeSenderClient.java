package com.lai.recipesender.client;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.ClearContainerCircuitPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsToBoundPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import com.lai.recipesender.network.packet.SelectBoundTargetPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import com.lai.recipesender.service.BoundContainerService;
import com.lai.recipesender.service.BoundTargetResolver;
import com.mojang.blaze3d.platform.InputConstants;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 管理 EMI 配方选择、份数调整以及双向材料传输。 */
@Mod.EventBusSubscriber(modid = RecipeSenderMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeSenderClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private static final int CONTROL_STEP = 64;
    private static final int SHIFT_STEP = 8;
    private static final long AVAILABILITY_REFRESH_INTERVAL_TICKS = 10L;
    /** 服务端统计请求的等待上限；超时后解除等待，避免界面永久卡在“统计中”。 */
    private static final long AVAILABILITY_TIMEOUT_TICKS = 40L;
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    /** 「发送到已绑定容器」的请求号；与反转统计分开，两边的回执互不干扰。 */
    private static final AtomicLong BOUND_REQUEST_SEQUENCE = new AtomicLong();

    /** 发送模式的优先级编号；数字越大优先级越高，见 {@link #onClientTick}。 */
    private static final int MODE_FORWARD = 0;
    private static final int MODE_BOUND = 1;
    private static final int MODE_REVERSE = 2;

    private static final KeyMapping INSERT_RECIPE_KEY = new KeyMapping(
            "key.recipe_sender.insert", KeyConflictContext.GUI, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.recipe_sender");
    private static final KeyMapping REVERSE_KEY = new KeyMapping(
            "key.recipe_sender.reverse", KeyConflictContext.GUI, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.recipe_sender");
    /**
     * 绑定容器 / 按住时把 Z 的功能换成「发送到已绑定容器」。
     *
     * <p>一个键干两件事：<b>按住</b>它时 Z 变成「发送到已绑定容器」（与反转键同一套语义，
     * 是按住生效的模式键，不是切换键）；在<b>世界里按一下</b>它则把准星指向的方块绑成容器。
     * 刻意不拆成两个键：两者默认值相同、语义不冲突，而拆开后玩家改键时极易只改一个，
     * 于是出现「改了键但没反应」——这正是 1.0.19 修掉的坑。
     *
     * <p>上下文必须是 {@code UNIVERSAL}：绑定要能在没有任何界面的时候发生。代价是它在世界里
     * 和输入框里都会响应，所以绑定入口显式要求「当前没有任何界面」
     * （见 {@link #bindLookedAtBlock()}），在容器界面里按它不会误绑界面背后的方块。
     */
    private static final KeyMapping BOUND_SEND_KEY = new KeyMapping(
            "key.recipe_sender.bound", KeyConflictContext.UNIVERSAL, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.recipe_sender");
    /**
     * 打开「已绑定容器」管理界面。
     *
     * <p>默认 {@code Ctrl + B}：必须带修饰键，否则会与 {@link #BOUND_SEND_KEY}（裸键 B）撞车——
     * 同一个物理键上挂两个语义，玩家分不清按一下到底会绑定还是开界面。
     */
    private static final KeyMapping MANAGE_KEY = new KeyMapping(
            "key.recipe_sender.manage", KeyConflictContext.UNIVERSAL, KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.recipe_sender");

    private static boolean selecting;
    private static boolean reverseMode;
    /** 当前选择是不是「发送到已绑定容器」；与 {@link #reverseMode} 互斥。 */
    private static boolean boundMode;
    /** 本次发送的目标；只在 {@link #boundMode} 为 true 时有值。 */
    private static BoundContainer activeBinding;
    /** 最近一次绑定发送的请求号；只接受与它相等的回执。 */
    private static long boundRequestId;
    private static boolean awaitingAvailability;
    private static boolean releasePending;
    private static boolean selectAllRequested;
    private static boolean nearbyAvailabilityReceived;
    private static boolean bindingLogged;
    /** 反转搜索不可用的提示每次会话只写一条日志，避免玩家反复按键刷屏。 */
    private static boolean reverseUnavailableLogged;
    /**
     * 等待服务端统计结果期间攒下的滚轮格数（正数向上）。
     * 这段窗口里滚轮不能直接改份数（可发送上限还没刷新），但事件必须被吞掉，
     * 否则会漏给 EMI 去滚动侧栏/翻配方页，把鼠标下方的条目换掉。
     */
    private static int pendingScrollNotches;
    private static int selectedBatches;
    private static int availableBatches;
    private static int insertableBatches;
    /** 当前悬停配方对编程电路的要求；null 表示不动电路（不是格雷配方或识别失败）。 */
    private static GtCircuitSupport.CircuitRequirement activeCircuit;
    /**
     * 当前打开的目标容器有没有可写的电路槽（客户端预判，只用来决定提示行）。
     *
     * <p>原版箱子、漏斗这类容器没有机器实例，{@link GtCircuitSupport#canAdjustCircuit} 给出
     * {@code false}：发送时既不会写电路也不会置空，所以连提示都不该显示。
     */
    private static boolean activeTargetHasCircuit;
    private static long clientTicks;
    private static long nextAvailabilityRefreshTick;
    private static long availabilityRequestTick;
    private static long activeRequestId;
    private static Set<Integer> highlightedInventorySlots = Set.of();
    private static Screen activeScreen;
    private static AbstractContainerScreen<?> activeContainer;
    private static EmiIngredient activeIngredient;
    private static EmiRecipe activeRecipe;
    private static List<RecipeIngredientSpec> activeSpecs = List.of();

    private RecipeSenderClient() {
    }

    /** 注册客户端事件监听。 */
    public static void register(IEventBus modBus) {
        modBus.addListener(RecipeSenderClient::registerKeyMappings);
    }

    /**
     * 注册按键。
     * 反转键必须无条件注册：Forge 只会把 options.txt 里保存的绑定写回“已注册”的 KeyMapping
     * （控制界面的列表同样是 options.keyMappings），一旦按条件注册，玩家在控制设置里改好的键
     * 在下次启动时就会被丢弃、退回默认的“~”。是否启用反转功能改由运行期的模组检测决定。
     * 新增的绑定键同样无条件注册，理由一致：它必须在控制设置里可改、且改了要能存下来。
     */
    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(INSERT_RECIPE_KEY);
        event.register(REVERSE_KEY);
        event.register(BOUND_SEND_KEY);
        event.register(MANAGE_KEY);
    }

    /**
     * 判断反转按键当前是否按下。
     * 只认玩家在控制设置里绑定的那个键：读取实时绑定后查询 GLFW 原始状态，改键立即生效。
     * 不能用 KeyMapping.isDown() 兜底——它依赖 Forge 的按键查找表，而该表在“控制界面改键”
     * 这条路径下不会同步更新，会继续响应旧按键，表现为“改键后旧键还能触发反转”。
     */
    private static boolean isReverseKeyHeld() {
        return isKeyHeld(REVERSE_KEY);
    }

    /** 判断「发送到已绑定容器」模式键当前是否按下；判定方式与反转键完全一致。 */
    public static boolean isBoundSendKeyHeld() {
        return isKeyHeld(BOUND_SEND_KEY);
    }

    /** 读取实时绑定后查询 GLFW 原始状态；改键立即生效，不依赖 Forge 的按键查找表。 */
    private static boolean isKeyHeld(KeyMapping mapping) {
        InputConstants.Key bound = mapping.getKey();
        // 未绑定时 getKey() 给出 InputConstants.UNKNOWN（KEYSYM，value 为 -1）。
        return switch (bound.getType()) {
            case KEYSYM -> bound.getValue() > 0 && isGlfwKeyDown(bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                    bound.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> mapping.isDown();
        };
    }

    /** 处理 Alt、Z、配方反转键和绑定键的按下与松开事件。 */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && selecting && isAltKey(event)) {
            selectAllRequested = true;
            selectedBatches = maxSendableBatches();
            return;
        }
        if (INSERT_RECIPE_KEY.matches(event.getKey(), event.getScanCode())) {
            if (event.getAction() == GLFW.GLFW_PRESS && !selecting) {
                beginSelection();
            } else if (event.getAction() == GLFW.GLFW_RELEASE && selecting) {
                finishSelection();
            }
            return;
        }
        // 本模组自己的界面（选择弹窗、管理界面）打开时，B 与 Ctrl+B 由界面自己处理：
        // InputEvent.Key 是无条件触发的（Forge 在 KeyboardHandler.keyPress 末尾必然调用），
        // 不挡住的话一次按键会被处理两遍——弹窗里按 B 想「直达上次」会同时去绑准星方块，
        // 弹窗里按 Ctrl+B 会把管理界面的父界面从「弹窗的父界面」改成「弹窗本身」。
        if (isOwnScreenOpen()) {
            return;
        }
        // 管理界面键默认是 Ctrl + B，必须连修饰键一起判定：isActiveAndMatches 会检查 Ctrl 是否按下，
        // 所以裸键 B 不会走到这里，而是继续往下落到绑定键上。
        if (event.getAction() == GLFW.GLFW_PRESS && !selecting
                && MANAGE_KEY.isActiveAndMatches(
                        InputConstants.getKey(event.getKey(), event.getScanCode()))) {
            openManageScreen();
            return;
        }
        // 绑定键是裸键（默认 B）且上下文为 UNIVERSAL，在 EMI 搜索框里打字母 b 也会走到这里，
        // 所以真正的判定放在 bindLookedAtBlock() 里：那里会避开搜索框。
        // 这里只比键码（KeyModifier.NONE 的 isActive 恒为 true，比修饰键也区分不开 Ctrl+B），
        // 靠上面管理键分支先判并 return 把 Ctrl+B 截走。
        if (event.getAction() == GLFW.GLFW_PRESS && !selecting
                && BOUND_SEND_KEY.matches(event.getKey(), event.getScanCode())) {
            bindLookedAtBlock();
        }
    }

    /**
     * 鼠标键版本的绑定键与管理键。
     *
     * <p>玩家可以在控制设置里把任意键位改成鼠标键（常见是鼠标侧键），但
     * {@link InputEvent.Key} 只承载键盘事件，鼠标键走的是 {@link InputEvent.MouseButton}——
     * 没有这个入口，改成鼠标侧键后按下去不会有任何反应。
     *
     * <p>用 {@code isActiveAndMatches} 而不是 {@code matches}：前者连修饰键一起判，
     * 管理键是 Ctrl + 键，只比键码的话鼠标侧键会被当成管理键。
     */
    @SubscribeEvent
    public static void onMouseInput(InputEvent.MouseButton.Post event) {
        if (event.getAction() != GLFW.GLFW_PRESS || selecting || isOwnScreenOpen()) {
            return;
        }
        InputConstants.Key button = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
        if (MANAGE_KEY.isActiveAndMatches(button)) {
            openManageScreen();
            return;
        }
        if (BOUND_SEND_KEY.isActiveAndMatches(button)) {
            bindLookedAtBlock();
        }
    }

    /** 当前打开的界面是不是本模组自己的界面（选择弹窗 / 管理界面 / 绑定确认弹窗）。 */
    private static boolean isOwnScreenOpen() {
        return Minecraft.getInstance().screen instanceof BoundContainerPickScreen
                || Minecraft.getInstance().screen instanceof BoundContainerManageScreen
                || Minecraft.getInstance().screen instanceof BoundContainerBindScreen;
    }

    /** 打开「已绑定容器」管理界面；用当前界面当父界面，这样 Esc 能原路返回。 */
    private static void openManageScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (EmiApi.isSearchFocused()) {
            return;
        }
        minecraft.setScreen(new BoundContainerManageScreen(minecraft.screen));
    }

    /**
     * 绑定玩家准星指向的方块。
     *
     * <p><b>只在没有任何界面时生效。</b>打开容器、背包或任何界面时按它什么都不做：
     * 那些时候准星取到的是界面背后的方块，玩家在整理背包时按一下就会被绑上一条自己没预期的绑定。
     * 想绑某个容器，先关掉它的界面、对着它按一下即可。
     *
     * <p>EMI 搜索框必须显式放行——搜索框里打字母 b 是打字，不是绑定请求。
     * 这里用 EMI 的公开 API 判断，不去猜它的焦点状态。
     */
    private static void bindLookedAtBlock() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (minecraft.screen != null) {
            return;
        }
        if (EmiApi.isSearchFocused()) {
            return;
        }
        HitResult picked = minecraft.hitResult;
        BlockPos pos = picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : null;
        // 这一条日志是为了区分「按键没被识别」和「识别了但准星没对着方块」。
        LOGGER.info("绑定键已触发：取到的方块 = {}，EMI 搜索框聚焦 = {}", pos, EmiApi.isSearchFocused());
        if (pos == null) {
            sendMessage(minecraft, getMessage("text.recipe_sender.bind_no_target",
                    "准星没有指向方块"));
            return;
        }
        // 石头、混凝土这类没有方块实体的方块永远不可能提供物品容器，绑了也永远发不进去，
        // 所以连弹窗都不打开。服务端 BoundBindingService 还会用同一个判定再拦一次：
        // 客户端的能力表可能不全（某些模组只注册服务端），不能把它当成唯一依据。
        if (!BoundTargetResolver.hasItemHandler(minecraft.level.getBlockEntity(pos))) {
            sendMessage(minecraft, getMessage("text.recipe_sender.bind_no_container",
                    "这个方块不是物品容器"));
            return;
        }
        ResourceLocation dimension = minecraft.player.level().dimension().location();
        // 同一个方块重复绑定只会把名字顶掉（服务端按同坐标更新），玩家多半是想改关系——
        // 那走管理界面的「关系」按钮。这里直接说清楚，别让一次手抖覆盖掉已经调好的名字。
        BoundContainer existing = BoundContainerClient.findAt(dimension, pos);
        if (existing != null) {
            sendMessage(minecraft, Component.translatable("text.recipe_sender.bound_already",
                    existing.name()));
            return;
        }
        // 名称、容器关系（主容器 / 并列成员 / 从容器）都在弹窗里定，服务端还会再校验一遍。
        // 名称留空时由服务端从方块状态生成默认名，客户端不参与命名。
        minecraft.setScreen(new BoundContainerBindScreen(null, dimension, pos));
    }

    /** 每客户端 tick 检查界面、目标配方和背包统计是否仍然有效。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTicks++;
        // 提示的生命周期与界面无关，必须每 tick 都走：下面的 selecting 早退不能把它挡掉，
        // 否则「绑定成功」这种在非选择状态下发出的提示会一直挂在屏幕上。
        NoticeOverlay.tick();
        Minecraft minecraft = Minecraft.getInstance();
        if (!bindingLogged) {
            logReverseBinding(minecraft);
        }
        if (!selecting) {
            return;
        }
        // 发送模式由「此刻按住哪些修饰键」实时决定，优先级固定为：反转 > 发送到已绑定容器 > 正向发送。
        // 玩家习惯先按 Z 再补按修饰键，如果只在按下 Z 的那一瞬间判定，后补的修饰键就完全无效，
        // 所以每 tick 复核一次。
        //
        // 但只升级、不降级：模式一旦升到高优先级就锁住，直到本次选择结束。
        // 降级是错的——松开两个键必然有先后（常见是先松修饰键再松 Z，间隔几十毫秒），
        // 若在 Z 松开前的某一 tick 看到修饰键已抬起就把模式降回去，最终就会按降级后的模式发送，
        // 表现为「HUD 明明显示发送到已绑定容器，结果发到了当前界面」。
        // 锁定之后松键顺序不再影响结果，也就不需要给「同时松开」猜一个容差毫秒数。
        int wantedMode = isReverseKeyHeld() ? MODE_REVERSE
                : (isBoundSendKeyHeld() ? MODE_BOUND : MODE_FORWARD);
        int currentMode = reverseMode ? MODE_REVERSE : (boundMode ? MODE_BOUND : MODE_FORWARD);
        if (wantedMode > currentMode && canEnterMode(wantedMode)) {
            cancelSelection("发送模式已提升");
            beginSelection();
            return;
        }
        if (minecraft.screen != activeScreen) {
            cancelSelection("当前界面已改变");
            return;
        }
        if (!isActiveTarget(EmiApi.getHoveredStack(true))) {
            cancelSelection("鼠标已不在目标配方上");
            return;
        }
        if (awaitingAvailability && clientTicks - availabilityRequestTick >= AVAILABILITY_TIMEOUT_TICKS) {
            onAvailabilityTimeout();
            return;
        }
        if (!reverseMode && clientTicks >= nextAvailabilityRefreshTick) {
            refreshInventoryAvailability(minecraft);
            nextAvailabilityRefreshTick = clientTicks + AVAILABILITY_REFRESH_INTERVAL_TICKS;
        } else if (reverseMode && !awaitingAvailability
                && clientTicks >= nextAvailabilityRefreshTick) {
            requestNearbyAvailability();
        }
    }

    /**
     * 想切到某个模式，先确认它这次真的能成立。
     *
     * <p>切模式要先把当前选择取消再重开；如果重开失败（没有绑定容器、反转搜索不可用），
     * 玩家会落得「按了修饰键，连原来的正向发送也没了」。所以不可成立时干脆不切，
     * 让当前这次选择按原模式走完。
     */
    private static boolean canEnterMode(int mode) {
        return switch (mode) {
            case MODE_REVERSE -> FindMeExtendedAdapter.isAvailable();
            case MODE_BOUND -> !BoundContainerClient.masters().isEmpty();
            default -> true;
        };
    }

    /**
     * 玩家按住反转键、但反转搜索不可用时给出明确原因。
     * <p>
     * 区分“没装”和“装了但不兼容”两种情况：前者是玩家漏装可选依赖，后者几乎总是
     * FindMeExtended 换了包名或改动了兼容层依赖的类/字段/方法。过去这两种情况都只是
     * 静默失效（按住反转键毫无反应、也没有任何提示），排查只能靠翻日志。
     */
    private static void warnReverseUnavailable(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        boolean installed = FindMeExtendedAdapter.isModPresent();
        if (!reverseUnavailableLogged) {
            reverseUnavailableLogged = true;
            LOGGER.warn("反转搜索不可用：FindMeExtended 已安装 = {}", installed);
        }
        NoticeOverlay.show(installed
                ? getMessage("text.recipe_sender.reverse_incompatible",
                        "反转搜索需要 FindMeExtended 1.0.2 或更高版本，当前版本不兼容")
                : getMessage("text.recipe_sender.reverse_missing",
                        "反转搜索需要安装 FindMeExtended"));
    }

    /** 生成带语言文件回退文本的提示。 */
    private static Component getMessage(String key, String fallback) {
        return Component.literal(getMessageText(key, fallback));
    }

    /** 取语言文件里的文本；缺失或格式错误时退回兜底文案。 */
    private static String getMessageText(String key, String fallback) {
        String text = I18n.get(key);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return fallback;
        }
        return text;
    }

    /** 把提示发给玩家；玩家还没准备好时静默丢弃。 */
    private static void sendMessage(Minecraft minecraft, Component text) {
        if (minecraft.player != null) {
            NoticeOverlay.show(text);
        }
    }

    /** 把提示画在 HUD 层；没有界面时走这里。 */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        NoticeOverlay.renderInHud(event.getGuiGraphics());
    }

    /** 生成带语言文件回退文本的「已发送 N 份到 X」提示。 */
    private static Component getBoundText(String key, Object[] args, String fallback) {
        return Component.literal(getBoundTextText(key, args, fallback));
    }

    /** 与 {@link #getBoundText} 同源，但返回纯文本——供嵌进别的语言模板的 {@code %s}。 */
    private static String getBoundTextText(String key, Object[] args, String fallback) {
        String text = I18n.get(key, args);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return fallback;
        }
        return text;
    }

    /** 生成 HUD 上的「发送到「X」」目标行。 */
    private static Component getBoundTargetText(String name) {
        String key = "text.recipe_sender.bound_target";
        String text = I18n.get(key, name);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return Component.literal("发送到「" + name + "」");
        }
        return Component.literal(text);
    }

    /**
     * 每次启动只记录一次反转键的真实绑定和反转搜索是否可用，用于排查“按住反转键没反应”这类问题：
     * 日志会给出模组读到的键名、这个 KeyMapping 是否真的进了控制设置的列表
     * （只有进了列表的按键，options.txt 里保存的绑定才会被写回），以及兼容层能否初始化。
     * <p>
     * 可用性这一项是有意留着的：1.0.13 里 FindMeExtended 改名导致兼容层初始化失败时，
     * 表现是“按住反转键毫无反应”，而日志里一条记录都没有，只能靠猜。
     */
    private static void logReverseBinding(Minecraft minecraft) {
        bindingLogged = true;
        LOGGER.info("反转键绑定 = {}，已进入控制设置列表 = {}，反转搜索可用 = {}",
                REVERSE_KEY.getKey().getName(), isRegistered(minecraft, REVERSE_KEY),
                FindMeExtendedAdapter.isAvailable());
        LOGGER.info("按键绑定：Z = {}（已注册 = {}），绑定键 = {}（已注册 = {}），管理键 = {}（已注册 = {}）",
                INSERT_RECIPE_KEY.getKey().getName(), isRegistered(minecraft, INSERT_RECIPE_KEY),
                BOUND_SEND_KEY.getKey().getName(), isRegistered(minecraft, BOUND_SEND_KEY),
                MANAGE_KEY.getKey().getName(), isRegistered(minecraft, MANAGE_KEY));
    }

    /** 判断某个按键是否真的进了「控制设置」列表：只有进列表的键，options.txt 里的改键才会被写回。 */
    private static boolean isRegistered(Minecraft minecraft, KeyMapping mapping) {
        for (KeyMapping candidate : minecraft.options.keyMappings) {
            if (candidate == mapping) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用滚轮调整当前选择的配方份数。
     *
     * <p><b>选择期间滚轮必须无条件吞掉</b>，包括“正在等服务端统计结果”的那几 tick：
     * EMI 自己也会响应滚轮——容器界面下是它的 {@code MouseMixin}（注入在
     * {@code Screen.mouseScrolled} 调用之前），配方界面下是 {@code RecipeScreen.mouseScrolled}
     * 与 {@code EmiScreenManager.mouseScrolled}。事件一旦漏出去，EMI 就会把侧栏翻页
     * （{@code SidebarPanel.scroll} 直接改 page）或把配方翻页（{@code RecipeScreen.setPage}），
     * 鼠标下方的条目/配方就换了一个对象，本模组下一 tick 的 {@link #isActiveTarget} 判定随即失效、
     * 选择被自己取消——表现就是“鼠标没动，选择却莫名中断”。
     *
     * <p>等待窗口里攒下的格数记在 {@link #pendingScrollNotches}，统计结果回来时一次性补上，
     * 既不丢输入，也不会让滚轮停手。
     */
    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!selecting || event.getScreen() != activeScreen) {
            return;
        }
        if (!isActiveTarget(EmiApi.getHoveredStack((int) event.getMouseX(),
                (int) event.getMouseY(), true))) {
            cancelSelection("滚轮时鼠标已不在目标配方上");
            return;
        }
        event.setCanceled(true);
        double delta = event.getScrollDelta();
        int direction = delta > 0.0D ? 1 : (delta < 0.0D ? -1 : 0);
        if (direction == 0) {
            // 横向滚轮等零增量事件同样要吞掉，否则会漏给 EMI。
            return;
        }
        if (awaitingAvailability) {
            pendingScrollNotches += direction;
            return;
        }
        applyScroll(direction);
    }

    /**
     * 选择期间屏蔽其他按键，避免误触打断选择。
     *
     * <p>放行 Esc（保留关闭界面的能力）、修饰键（Shift/Ctrl/Alt 决定步长）和本模组自己的两个按键。
     *
     * <p>已核对字节码的边界：这里只能拦住交给界面的按键（原版容器界面的数字键换物品、Q 丢弃、
     * E 关闭，以及 EMI 配方界面自身的按键处理）。<b>容器界面下拦不住 EMI 的全局按键</b>——
     * 它走自己的 {@code KeyboardMixin}（注入在 {@code Screen.wrapScreenError} 调用之前），
     * 位置在 Forge 的 {@code ScreenEvent.KeyPressed.Pre} 之前，Forge 事件层够不着；
     * 也不能改走 {@code InputEvent.Key}：已核对字节码，{@code ForgeHooksClient.onKeyInput}
     * 丢弃了事件取消结果，取消它不影响原版行为。要拦住那一条需要额外 Mixin，暂不做。
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (shouldBlockKey(event.getScreen(), event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    /** 与按下对称地屏蔽松开，避免界面收到没有对应按下的松开事件。 */
    @SubscribeEvent
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (shouldBlockKey(event.getScreen(), event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    /** 判断某个按键在选择期间是否应当被屏蔽。 */
    private static boolean shouldBlockKey(Screen screen, int keyCode) {
        if (!selecting || screen != activeScreen) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || isModifierKey(keyCode) || isModKeyMapping(keyCode)) {
            return false;
        }
        return true;
    }

    /** 判断是否为修饰键；这些键只影响步长，放行不影响选择。 */
    private static boolean isModifierKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT
                || keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
                || keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT;
    }

    /** 判断按键是否为“插入配方”“反转方向”“绑定容器”或管理键（按玩家当前实际绑定判断，改键后立即生效）。 */
    private static boolean isModKeyMapping(int keyCode) {
        return isBoundKeySym(INSERT_RECIPE_KEY, keyCode) || isBoundKeySym(REVERSE_KEY, keyCode)
                || isBoundKeySym(BOUND_SEND_KEY, keyCode) || isBoundKeySym(MANAGE_KEY, keyCode);
    }

    /** 绑定的键是键盘按键且等于 keyCode 时返回 true；鼠标绑定不参与判断。 */
    private static boolean isBoundKeySym(KeyMapping mapping, int keyCode) {
        InputConstants.Key bound = mapping.getKey();
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == keyCode;
    }

    /** 按格数调整份数：一次滚轮一格，等待期间累计的多格在这里逐格补上（逐格才能正确夹住上限）。 */
    private static void applyScroll(int notches) {
        int step = notches > 0 ? 1 : -1;
        for (int index = 0; index < Math.abs(notches); index++) {
            selectedBatches = adjustBatches(selectedBatches, step);
        }
    }

    /** 把等待统计结果期间攒下的滚轮格数补上。 */
    private static void applyPendingScroll() {
        int notches = pendingScrollNotches;
        pendingScrollNotches = 0;
        if (notches != 0) {
            applyScroll(notches);
        }
    }

    /**
     * 在 EMI 界面上绘制材料高亮和份数提示。
     *
     * <p>屏幕下方的浮层提示也挂在这里（界面层的那一份）：优先级 {@code LOWEST} 让它画在
     * 高亮之上。有界面时 HUD 层的那份会被界面背景盖住但还会透出来，两处都画就是重影，
     * 所以 {@link NoticeOverlay} 按「当前有没有界面」在两条路径里二选一。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        NoticeOverlay.renderInScreen(event.getGuiGraphics());
        if (!selecting || event.getScreen() != activeScreen) {
            return;
        }
        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();
        if (!isActiveTarget(EmiApi.getHoveredStack(mouseX, mouseY, true))) {
            cancelSelection("绘制时鼠标已不在目标配方上");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        renderInventoryHighlights(graphics, event.getScreen());

        String availableKey = reverseMode
                ? "text.recipe_sender.nearby_available_count"
                : "text.recipe_sender.available_count";
        List<Component> lines = new ArrayList<>(5);
        List<Integer> colors = new ArrayList<>(5);
        if (boundMode) {
            // 落点在玩家看不到的远程方块上，所以第一行必须先说清楚发到哪儿。
            // 有多个候选时这里说不了具体是哪一个（等松开 Z 才由玩家挑），只说候选数量。
            lines.add(activeBinding != null
                    ? getBoundTargetText(activeBinding.name())
                    : getBoundText("text.recipe_sender.bound_target_pick",
                            new Object[]{BoundContainerClient.masters().size()}, "候选容器"));
            colors.add(0x8FE08F);
        }
        lines.add(getCountText(availableKey, availableBatches,
                reverseMode ? "周围现有" : "背包现有"));
        colors.add(0xB8B8B8);
        lines.add(getCountText(reverseMode
                        ? "text.recipe_sender.pull_count" : "text.recipe_sender.send_count",
                selectedBatches, reverseMode ? "取回" : "发送"));
        colors.add(0xFFFFFF);
        Component circuitLine = reverseMode ? null : describeCircuitChange();
        if (circuitLine != null) {
            // 提前显示这次发送会顺带把机器电路改成什么，避免玩家不知道配置被动过。
            lines.add(circuitLine);
            colors.add(0xFFD479);
        }
        if (!reverseMode && insertableBatches < availableBatches) {
            // 目标容器成为瓶颈时明确提示上限，避免玩家以为滚轮失效。
            lines.add(getCountText("text.recipe_sender.target_limit", insertableBatches,
                    "目标最多接收"));
            colors.add(0x9AD9FF);
        }

        int width = lines.stream().mapToInt(line -> minecraft.font.width(line)).max().orElse(0);
        int lineHeight = minecraft.font.lineHeight + 2;
        int textHeight = lineHeight * lines.size();
        int x = Math.max(2, Math.min(mouseX - width / 2, event.getScreen().width - width - 2));
        int y = mouseY - textHeight - 6;
        if (y < 2) {
            y = Math.max(2, Math.min(mouseY + 6, event.getScreen().height - textHeight - 2));
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 1000.0D);
        graphics.fill(x - 2, y - 2, x + width + 2, y + textHeight + 2, 0xB0000000);
        for (int index = 0; index < lines.size(); index++) {
            Component line = lines.get(index);
            graphics.drawString(minecraft.font, line,
                    x + (width - minecraft.font.width(line)) / 2, y + index * lineHeight,
                    colors.get(index), true);
        }
        graphics.pose().popPose();
    }

    /** 初始化当前悬停配方，并根据按键状态进入正向、反向或「发送到已绑定容器」模式。 */
    private static void beginSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen == null) {
            return;
        }
        AbstractContainerScreen<?> container = EmiApi.getHandledScreen();
        EmiStackInteraction hovered = EmiApi.getHoveredStack(true);
        EmiRecipe recipe = getRecipe(hovered);
        if (hovered.isEmpty() || recipe == null) {
            return;
        }

        // 绑定键与反转键同时按住时以反转为准：反转是既有行为，不能因为新增按键而改变含义。
        boolean boundRequested = isBoundSendKeyHeld() && !isReverseKeyHeld();
        boolean reverseRequested = !boundRequested && isReverseKeyHeld();
        // 每次开始选择都记一行：按住 Z 却没进绑定模式时，这一行能直接说明模组读到的绑定是什么。
        LOGGER.info("开始选择：绑定键 = {}（按住 = {}），反转键 = {}（按住 = {}），判定 = {}",
                BOUND_SEND_KEY.getKey().getName(), isBoundSendKeyHeld(),
                REVERSE_KEY.getKey().getName(), isReverseKeyHeld(),
                boundRequested ? "绑定发送" : reverseRequested ? "反转" : "正向发送");
        if (!boundRequested && container == null) {
            // 正向发送的落点就是当前打开的容器，没有容器界面就无从谈起。
            return;
        }
        if (reverseRequested && !FindMeExtendedAdapter.isAvailable()) {
            // 反转搜索完全依赖 FindMeExtended 的容器扫描与提取器。不可用时必须中止并明说：
            // 悄悄退化成正向发送会把玩家背包里的材料送进机器，和按住反转键的意图正好相反。
            warnReverseUnavailable(minecraft);
            return;
        }
        BoundContainer boundTarget = null;
        if (boundRequested) {
            List<BoundContainer> masters = BoundContainerClient.masters();
            if (masters.isEmpty()) {
                sendMessage(minecraft, getMessage("text.recipe_sender.bound_none",
                        "还没有绑定容器：对着方块按 B 绑定"));
                return;
            }
            // 只有一个主容器时直接定下；有多个时这里不定，等松开 Z 再弹选择界面让玩家挑。
            // 绝不静默挑一个：那会把材料发进玩家没预期的机器里。
            if (masters.size() == 1) {
                boundTarget = masters.get(0);
            }
        }
        selecting = true;
        reverseMode = reverseRequested;
        boundMode = boundRequested;
        activeBinding = boundTarget;
        selectAllRequested = isAltDown();
        selectedBatches = reverseMode ? 0 : 1;
        pendingScrollNotches = 0;
        activeScreen = minecraft.screen;
        activeContainer = container;
        activeIngredient = hovered.getStack();
        activeRecipe = recipe;
        // 电路只影响机器的配方匹配，不参与材料统计，因此在开始选择时解析一次即可。
        // 绑定模式的落点是远程方块，客户端拿不到它的机器实例，电路衔接在后续切片处理。
        activeCircuit = boundMode ? null : GtCircuitSupport.findCircuit(recipe);
        activeTargetHasCircuit = !boundMode && container != null
                && GtCircuitSupport.canAdjustCircuit(
                        GtCircuitSupport.findCircuitHolder(container.getMenu()));
        highlightedInventorySlots = RecipeMaterialCollector.findMatchingInventorySlots(recipe,
                minecraft.player);

        if (reverseMode) {
            activeSpecs = RecipeMaterialCollector.createIngredientSpecs(recipe);
            if (activeSpecs.isEmpty()) {
                cancelSelection();
                return;
            }
            awaitingAvailability = false;
            nearbyAvailabilityReceived = false;
            requestNearbyAvailability();
        } else {
            refreshInventoryAvailability(minecraft);
            if (selectAllRequested) {
                selectedBatches = maxSendableBatches();
            }
            nextAvailabilityRefreshTick = clientTicks + AVAILABILITY_REFRESH_INTERVAL_TICKS;
        }
    }

    /** 请求服务端统计周围材料，并通过节流避免高频扫描容器。 */
    private static void requestNearbyAvailability() {
        if (!reverseMode || activeSpecs.isEmpty() || awaitingAvailability) {
            return;
        }
        awaitingAvailability = true;
        activeRequestId = REQUEST_SEQUENCE.incrementAndGet();
        availabilityRequestTick = clientTicks;
        ModNetwork.CHANNEL.sendToServer(new NearbyRecipeQueryPacket(activeRequestId, activeSpecs));
    }

    /** 松开 Z 时提交正向发送、反向取回或「发送到已绑定容器」请求。 */
    private static void finishSelection() {
        if (!isActiveTarget(EmiApi.getHoveredStack(true))) {
            cancelSelection("松手时鼠标已不在目标配方上");
            return;
        }
        if (reverseMode) {
            if (awaitingAvailability) {
                releasePending = true;
                return;
            }
            sendPullRequest();
            cancelSelection();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (boundMode) {
            finishBoundSelection(minecraft);
            return;
        }
        // 选择的份数超过目标容器能容纳的数量时，按目标容器可容纳的份数发送；
        // 这里重新估算一次，避免使用最多 10 tick 前的旧值。
        int batches = RecipeMaterialCollector.countInsertableBatches(activeRecipe, minecraft.player,
                activeContainer.getMenu(), Math.min(selectedBatches, availableBatches));
        RecipeMaterialCollector.CollectionResult result =
                RecipeMaterialCollector.collect(activeRecipe, minecraft.player, batches);
        if (result.success()) {
            // 电路先于材料送达：机器可能在下一个 tick 就按新电路匹配配方，先设电路可以避免抢跑一次。
            sendCircuitRequest();
            List<ItemStack> requirements = result.requirements();
            ModNetwork.CHANNEL.sendToServer(new InsertRecipeItemsPacket(
                    activeContainer.getMenu().containerId, requirements));
        }
        cancelSelection();
    }

    /**
     * 提交一次「发送到已绑定容器」。
     *
     * <p>客户端只能数背包里的存量（远程容器的容量在服务端），所以这里不做上限夹取：
     * 要几份就报几份，装不下由服务端的回执说清楚。
     *
     * <p>有多个主容器时这里不直接发，而是把已经凑好的材料交给选择界面：
     * 玩家挑完再发。材料在这一刻就已经从背包「预定」出来了，弹窗期间不会因为背包变动而失效。
     */
    private static void finishBoundSelection(Minecraft minecraft) {
        List<BoundContainer> masters = BoundContainerClient.masters();
        if (masters.isEmpty()) {
            sendMessage(minecraft, getMessage("text.recipe_sender.bound_none",
                    "还没有绑定容器：对着方块按 B 绑定"));
            cancelSelection();
            return;
        }
        int batches = Math.min(selectedBatches, availableBatches);
        if (batches <= 0) {
            cancelSelection();
            return;
        }
        RecipeMaterialCollector.CollectionResult result =
                RecipeMaterialCollector.collect(activeRecipe, minecraft.player, batches);
        if (!result.success()) {
            // 从背包里凑不齐材料：直接把原因说出来，不要静默什么都不做。
            sendMessage(minecraft, Component.literal(result.message()));
            cancelSelection();
            return;
        }
        List<ItemStack> requirements = result.requirements();
        if (masters.size() == 1) {
            sendBoundInsert(masters.get(0).id(), requirements, batches);
            cancelSelection();
            return;
        }
        Screen parent = minecraft.screen;
        cancelSelection();
        minecraft.setScreen(new BoundContainerPickScreen(parent, masters, requirements, batches));
    }

    /**
     * 把一份材料发往指定绑定，并记下这次的请求号用于认领回执。
     *
     * <p>这个入口同时被选择界面调用，所以请求号的分配收在这里，避免两处各写一遍。
     */
    public static void sendBoundInsert(UUID bindingId, List<ItemStack> requirements, int batches) {
        if (bindingId == null || requirements.isEmpty() || batches <= 0) {
            return;
        }
        boundRequestId = BOUND_REQUEST_SEQUENCE.incrementAndGet();
        ModNetwork.CHANNEL.sendToServer(new InsertRecipeItemsToBoundPacket(boundRequestId, bindingId,
                requirements, batches));
    }

    /**
     * 告诉服务端「玩家这次选了哪个发送单元」，服务端记下来供下次按 B 直达。
     *
     * <p>写在玩家确认选择这一刻，而不是材料成功送达那一刻：
     * 材料没送出去（目标满了之类）不该把玩家的选择也一起忘掉。
     */
    public static void rememberBoundChoice(UUID bindingId) {
        if (bindingId == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(
                new SelectBoundTargetPacket(BoundContainerClient.manualRoute(), bindingId));
    }

    /** 给玩家显示一条屏幕下方的提示。界面代码拿不到私有发送方法，所以开这个口子。 */
    public static void notifyPlayer(Component text) {
        sendMessage(Minecraft.getInstance(), text);
    }

    /** S1 的高亮还只是占位，真正的世界内红色描边在后续切片实现。 */
    public static void notifyHighlightUnavailable(String name) {
        notifyPlayer(Component.translatable("text.recipe_sender.highlight_pending", name));
    }

    /** 判断某个按键事件是不是绑定发送键（带修饰键判定）。 */
    public static boolean matchesBoundSendKey(int keyCode, int scanCode) {
        return BOUND_SEND_KEY.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    /** 判断某个按键事件是不是管理键（Ctrl+B）。 */
    public static boolean matchesManageKey(int keyCode, int scanCode) {
        return MANAGE_KEY.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    /**
     * 判断某个鼠标键事件是不是绑定发送键。
     *
     * <p>键盘事件与鼠标事件是两条独立通路，所以同一个键位要分别判一次：
     * 把绑定发送键改成鼠标侧键之后，弹窗里「再按一次直达上次」只能靠这里识别。
     */
    public static boolean matchesBoundSendMouse(int button) {
        return BOUND_SEND_KEY.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    /**
     * 请求服务端按当前配方调整目标机器的电路：配方要求编号就写编号，配方不使用电路就清空。
     *
     * <p>这里不预先判断容器是否支持电路：机器实例只有服务端才有，客户端只看得到菜单，
     * 判断交给服务端，不支持的容器会直接忽略这个数据包。
     *
     * <p>唯一的例外是“目标压根不是格雷机器界面”（原版箱子、漏斗等）——菜单类型在客户端与
     * 服务端是一致的，这类目标连一个能装电路的机器实例都取不到，直接不发，省掉一次无意义往返。
     *
     * <p>清空请求只在“认出了配方对象、且它确实不使用电路”时发；目标容器有没有电路槽由服务端
     * 拿机器实例判断（写不进去时服务端直接返回，不会误删别的东西）。
     */
    private static void sendCircuitRequest() {
        if (reverseMode || activeCircuit == null || activeContainer == null) {
            return;
        }
        if (!GtCircuitSupport.isModularUiContainer(activeContainer.getMenu())) {
            return;
        }
        int containerId = activeContainer.getMenu().containerId;
        if (activeCircuit.requiresCircuit()) {
            ModNetwork.CHANNEL.sendToServer(new SetContainerCircuitPacket(
                    containerId, activeCircuit.circuit()));
            return;
        }
        if (activeCircuit.gregRecipe()) {
            ModNetwork.CHANNEL.sendToServer(new ClearContainerCircuitPacket(containerId));
        }
    }

    /** 处理服务端返回的周围配方份数。 */
    public static void acceptNearbyAvailability(long requestId, int batches) {
        if (!selecting || !reverseMode || requestId != activeRequestId) {
            return;
        }
        awaitingAvailability = false;
        availableBatches = Math.max(0, Math.min(RecipeIngredientSpec.MAX_BATCHES, batches));
        if (availableBatches == 0) {
            selectedBatches = 0;
        } else if (!nearbyAvailabilityReceived) {
            selectedBatches = selectAllRequested ? availableBatches : 1;
        } else if (selectedBatches == 0) {
            selectedBatches = 1;
        } else {
            selectedBatches = Math.min(selectedBatches, availableBatches);
        }
        nearbyAvailabilityReceived = true;
        // 等待期间攒下的滚轮在这里补上；必须在 releasePending 之前，
        // 否则“松手前最后一格”会赶不上这次取回。
        applyPendingScroll();
        nextAvailabilityRefreshTick = clientTicks + AVAILABILITY_REFRESH_INTERVAL_TICKS;
        if (releasePending) {
            sendPullRequest();
            cancelSelection();
        }
    }

    /**
     * 服务端的绑定列表变了。
     *
     * <p>正在「发送到已绑定容器」的选择过程中、而目标刚被删掉时立即中止：
     * 继续下去只会在松手时拿到一条「绑定不存在」的失败回执，不如当场说清楚。
     */
    public static void onBoundContainersSynced() {
        if (selecting && boundMode && (activeBinding == null
                || BoundContainerClient.find(activeBinding.id()) == null)) {
            cancelSelection("绑定的容器已被删除");
        }
    }

    /** 处理「发送到已绑定容器」的回执。 */
    public static void acceptBoundInsertResult(long requestId, UUID bindingId, BoundStatus status,
                                               int insertedBatches, int requestedBatches,
                                               int overflowBatches, String detailKey) {
        if (requestId != boundRequestId) {
            return;
        }
        boundRequestId = 0;
        Minecraft minecraft = Minecraft.getInstance();
        BoundContainer target = BoundContainerClient.find(bindingId);
        String name = target == null ? "已绑定容器" : target.name();
        if (status != BoundStatus.OK) {
            // 失败必须说出具体原因：落点在玩家看不到的地方，没有提示就只剩「点了没反应」。
            String key = BoundContainerService.describe(status);
            String fallback = "发送失败：目标「" + name + "」不可用";
            sendMessage(minecraft, key == null ? Component.literal(fallback)
                    : getMessage(key, fallback));
            return;
        }
        if (requestedBatches <= 0) {
            return;
        }
        if (insertedBatches >= requestedBatches) {
            if (overflowBatches > 0) {
                // 主容器与并列成员都放满后溢出去了。必须说出来：玩家看主容器没收到全部份数，
                // 否则会以为发错了地方。
                sendMessage(minecraft, getBoundText("text.recipe_sender.bound_sent_overflow",
                        new Object[]{insertedBatches, name, overflowBatches},
                        "已发送 " + insertedBatches + " 份到「" + name + "」，其中 " + overflowBatches
                                + " 份进了从容器"));
                return;
            }
            sendMessage(minecraft, getBoundText("text.recipe_sender.bound_sent",
                    new Object[]{insertedBatches, name},
                    "已发送 " + insertedBatches + " 份到「" + name + "」"));
            return;
        }
        String detail = detailKey == null ? ""
                : getMessageText(detailKey, detailFallback(detailKey));
        if (overflowBatches > 0) {
            // 部分送达 + 已有溢出：先把「溢出到从容器的份数」说清楚，再补没发完的原因。
            String overflowNote = getBoundTextText("text.recipe_sender.bound_overflow_note",
                    new Object[]{overflowBatches},
                    "其中 " + overflowBatches + " 份进了从容器");
            detail = detail.isEmpty() ? overflowNote : overflowNote + "，" + detail;
        }
        sendMessage(minecraft, getBoundText("text.recipe_sender.bound_partial",
                new Object[]{insertedBatches, requestedBatches, name, detail},
                "只发出 " + insertedBatches + "/" + requestedBatches + " 份到「" + name + "」："
                        + detail));
    }

    /** 回执里 detailKey 的中文兜底文案（语言文件缺失时使用）。 */
    private static String detailFallback(String detailKey) {
        return detailKey.endsWith("target_full")
                ? "目标容器放不下（可能已满或不允许该物品）" : "背包材料不足";
    }

    /**
     * 服务端长时间没有返回统计结果时解除等待。
     * 否则一旦数据包丢失，滚轮和松开按键都会被 awaitingAvailability 永久阻断。
     */
    private static void onAvailabilityTimeout() {
        awaitingAvailability = false;
        applyPendingScroll();
        nextAvailabilityRefreshTick = clientTicks + AVAILABILITY_REFRESH_INTERVAL_TICKS;
        if (releasePending) {
            sendPullRequest();
            cancelSelection();
        }
    }

    /** 向服务端发送反向取回请求。 */
    private static void sendPullRequest() {
        if (selectedBatches > 0 && !activeSpecs.isEmpty()) {
            ModNetwork.CHANNEL.sendToServer(new NearbyRecipePullPacket(selectedBatches, activeSpecs));
        }
    }

    /** 从 EMI 悬停对象中取得对应配方。 */
    private static EmiRecipe getRecipe(EmiStackInteraction hovered) {
        EmiRecipe recipe = hovered.getRecipeContext();
        return recipe != null ? recipe : EmiApi.getRecipeContext(hovered.getStack());
    }

    /**
     * 判断鼠标当前是否仍然指向开始选择时的配方。
     *
     * <p>用结构比较而不是引用相等。EMI 侧栏里的条目对象会被整体重建：在配方树里点“合成”进入
     * 合成模式后，收藏栏显示的那批 {@code EmiFavorite.Synthetic} 由
     * {@code EmiFavorites.updateSynthetic} 维护，而它每次都是先 {@code clear()} 再重新填入
     * （FAVORITES 侧栏是 {@code CompoundList} 直指那份列表的实时视图，所以索引不变、对象换新）。
     * 同一个物品配同一个配方、对象却被换掉，引用相等会把这种“换了对象但目标没变”误判成鼠标离开，
     * 选择于是按住没多久就自己中断——表现成“鼠标没动、选择却断了”。
     */
    private static boolean isActiveTarget(EmiStackInteraction hovered) {
        if (hovered.isEmpty() || activeIngredient == null || activeRecipe == null) {
            return false;
        }
        return sameRecipe(getRecipe(hovered), activeRecipe)
                && EmiIngredient.areEqual(hovered.getStack(), activeIngredient);
    }

    /**
     * 判断两个配方是不是同一个配方：先比引用，再退回配方 ID + 类别。
     *
     * <p>EMI 的 {@code EmiResolutionRecipe}（合成树里“改用另一种材料”那种节点）ID 为 null，
     * 这类只能靠引用判断——比较保守，但不会把两个不同配方误判成同一个。
     */
    private static boolean sameRecipe(EmiRecipe candidate, EmiRecipe current) {
        if (candidate == current) {
            return true;
        }
        if (candidate == null || current == null) {
            return false;
        }
        ResourceLocation candidateId = candidate.getId();
        return candidateId != null && candidateId.equals(current.getId())
                && candidate.getCategory() == current.getCategory();
    }

    /** 刷新背包可制作份数、目标槽可容纳份数和材料槽位高亮。 */
    private static void refreshInventoryAvailability(Minecraft minecraft) {
        availableBatches = RecipeMaterialCollector.countAvailableBatches(activeRecipe,
                minecraft.player);
        highlightedInventorySlots = RecipeMaterialCollector.findMatchingInventorySlots(
                activeRecipe, minecraft.player);
        // 绑定模式的落点是远程方块，客户端拿不到它的槽位，所以不做容量估计：
        // 报多少份由玩家决定，装不下由服务端回执说明。
        insertableBatches = boundMode || activeContainer == null ? availableBatches
                : RecipeMaterialCollector.countInsertableBatches(activeRecipe, minecraft.player,
                        activeContainer.getMenu(), availableBatches);
        int ceiling = maxSendableBatches();
        if (ceiling == 0) {
            selectedBatches = 0;
        } else if (selectedBatches == 0) {
            selectedBatches = 1;
        } else {
            selectedBatches = Math.min(selectedBatches, ceiling);
        }
    }

    /**
     * 当前最多可发送的份数：反向模式只看周围存量；
     * 正向模式还要受目标容器容量限制。
     */
    private static int maxSendableBatches() {
        return reverseMode ? availableBatches : Math.min(availableBatches, insertableBatches);
    }

    /** 绘制当前配方匹配的背包槽位。 */
    private static void renderInventoryHighlights(GuiGraphics graphics, Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)
                || minecraft.player == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 900.0D);
        int pulse = (int) (Math.sin(clientTicks * 0.35D) * 18.0D);
        int fillAlpha = 48 + pulse;
        int outlineAlpha = 190 + pulse;
        for (Slot slot : containerScreen.getMenu().slots) {
            if (slot.container != inventory
                    || !highlightedInventorySlots.contains(slot.getContainerSlot())) {
                continue;
            }
            int x = containerScreen.getGuiLeft() + slot.x;
            int y = containerScreen.getGuiTop() + slot.y;
            graphics.fillGradient(x + 1, y + 1, x + 15, y + 15, 900,
                    (fillAlpha << 24) | 0x2DE2B4, (fillAlpha << 24) | 0xF2B544);
            graphics.renderOutline(x, y, 16, 16, (outlineAlpha << 24) | 0x5CFFE0);
            graphics.renderOutline(x + 1, y + 1, 14, 14, 0xA0F4C85E);
        }
        graphics.pose().popPose();
    }

    /** 生成带语言文件回退文本的数量提示。 */
    private static Component getCountText(String key, int count, String fallbackPrefix) {
        String text = I18n.get(key, count);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return Component.literal(fallbackPrefix + count + "份");
        }
        return Component.literal(text);
    }

    /**
     * 描述这次发送会怎样改动机器电路。
     *
     * @return 提示行；目标没有电路槽、或不是格雷配方（识别失败、只从 EMI 原料看到电路）时返回
     *         {@code null}
     */
    private static Component describeCircuitChange() {
        if (activeCircuit == null || !activeTargetHasCircuit) {
            // 目标没有电路槽（原版箱子、漏斗等）时什么都不会发，别提示得像是会改电路。
            return null;
        }
        if (activeCircuit.requiresCircuit()) {
            return getCircuitText(activeCircuit.circuit());
        }
        // 认出了配方对象、且它确实不使用电路：目标有电路槽（上面已判）就会置空。
        return activeCircuit.gregRecipe() ? getCircuitClearText() : null;
    }

    /** 生成带语言文件回退文本的电路提示。 */
    private static Component getCircuitText(int circuit) {
        String key = "text.recipe_sender.circuit";
        String text = I18n.get(key, circuit);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return Component.literal("电路 #" + circuit);
        }
        return Component.literal(text);
    }

    /** 生成带语言文件回退文本的置空提示。 */
    private static Component getCircuitClearText() {
        String key = "text.recipe_sender.circuit_clear";
        String text = I18n.get(key);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return Component.literal("电路置空");
        }
        return Component.literal(text);
    }

    /** 根据修饰键和滚轮方向计算新的份数。 */
    private static int adjustBatches(int current, double scrollDelta) {
        int ceiling = maxSendableBatches();
        if (ceiling == 0) {
            return 0;
        }
        int direction = scrollDelta > 0 ? 1 : -1;
        if (isAltDown()) {
            selectAllRequested = true;
            return ceiling;
        }
        if (isControlDown()) {
            if (direction > 0) {
                int next = current == 1 ? CONTROL_STEP : current + CONTROL_STEP;
                return Math.min(ceiling, Math.min(RecipeIngredientSpec.MAX_BATCHES, next));
            }
            return Math.max(1, current <= CONTROL_STEP ? 1 : current - CONTROL_STEP);
        }
        int step = isShiftDown() ? SHIFT_STEP : 1;
        return Math.max(1, Math.min(ceiling,
                Math.min(RecipeIngredientSpec.MAX_BATCHES, current + direction * step)));
    }

    /** 判断 Shift 是否处于按下状态。 */
    private static boolean isShiftDown() {
        return isGlfwKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
                || isGlfwKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** 判断 Ctrl 是否处于按下状态。 */
    private static boolean isControlDown() {
        return isGlfwKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL)
                || isGlfwKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    /** 判断 Alt 是否处于按下状态。 */
    private static boolean isAltDown() {
        return isGlfwKeyDown(GLFW.GLFW_KEY_LEFT_ALT)
                || isGlfwKeyDown(GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /** 判断输入事件是否来自任一 Alt 键。 */
    private static boolean isAltKey(InputEvent.Key event) {
        return event.getKey() == GLFW.GLFW_KEY_LEFT_ALT
                || event.getKey() == GLFW.GLFW_KEY_RIGHT_ALT;
    }

    /** 查询 GLFW 中指定按键的实时状态。 */
    private static boolean isGlfwKeyDown(int key) {
        long handle = Minecraft.getInstance().getWindow().getWindow();
        int state = GLFW.glfwGetKey(handle, key);
        return state == GLFW.GLFW_PRESS || state == GLFW.GLFW_REPEAT;
    }

    /**
     * 带原因地取消选择。
     * 只在 debug 级别记录，用来排查“鼠标没动、选择却中断”这类问题：
     * 正常使用时不产生任何日志，需要时把 recipe_sender 的日志级别调到 debug 即可。
     */
    private static void cancelSelection(String reason) {
        if (selecting) {
            LOGGER.debug("取消配方选择：{}", reason);
        }
        cancelSelection();
    }

    /** 清理当前选择状态，防止旧界面或旧请求继续生效。 */
    private static void cancelSelection() {
        selecting = false;
        reverseMode = false;
        boundMode = false;
        activeBinding = null;
        awaitingAvailability = false;
        releasePending = false;
        selectAllRequested = false;
        nearbyAvailabilityReceived = false;
        pendingScrollNotches = 0;
        selectedBatches = 0;
        availableBatches = 0;
        insertableBatches = 0;
        activeRequestId = 0;
        activeScreen = null;
        activeContainer = null;
        activeIngredient = null;
        activeRecipe = null;
        activeSpecs = List.of();
        activeCircuit = null;
        activeTargetHasCircuit = false;
        highlightedInventorySlots = Set.of();
    }
}
