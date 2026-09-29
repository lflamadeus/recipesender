package com.lai.recipesender.client;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
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

    private static final KeyMapping INSERT_RECIPE_KEY = new KeyMapping(
            "key.recipe_sender.insert", KeyConflictContext.GUI, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.recipe_sender");
    private static final KeyMapping REVERSE_KEY = new KeyMapping(
            "key.recipe_sender.reverse", KeyConflictContext.GUI, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.recipe_sender");

    private static boolean selecting;
    private static boolean reverseMode;
    private static boolean awaitingAvailability;
    private static boolean releasePending;
    private static boolean selectAllRequested;
    private static boolean nearbyAvailabilityReceived;
    private static boolean bindingLogged;
    /**
     * 等待服务端统计结果期间攒下的滚轮格数（正数向上）。
     * 这段窗口里滚轮不能直接改份数（可发送上限还没刷新），但事件必须被吞掉，
     * 否则会漏给 EMI 去滚动侧栏/翻配方页，把鼠标下方的条目换掉。
     */
    private static int pendingScrollNotches;
    private static int selectedBatches;
    private static int availableBatches;
    private static int insertableBatches;
    /** 当前悬停配方要求的编程电路编号；GtCircuitSupport.NO_CIRCUIT 表示该配方不使用电路。 */
    private static int activeCircuit = GtCircuitSupport.NO_CIRCUIT;
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
     */
    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(INSERT_RECIPE_KEY);
        event.register(REVERSE_KEY);
    }

    /**
     * 判断反转按键当前是否按下。
     * 只认玩家在控制设置里绑定的那个键：读取实时绑定后查询 GLFW 原始状态，改键立即生效。
     * 不能用 KeyMapping.isDown() 兜底——它依赖 Forge 的按键查找表，而该表在“控制界面改键”
     * 这条路径下不会同步更新，会继续响应旧按键，表现为“改键后旧键还能触发反转”。
     */
    private static boolean isReverseKeyHeld() {
        InputConstants.Key bound = REVERSE_KEY.getKey();
        // 未绑定时 getKey() 给出 InputConstants.UNKNOWN（KEYSYM，value 为 -1）。
        return switch (bound.getType()) {
            case KEYSYM -> bound.getValue() > 0 && isGlfwKeyDown(bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                    bound.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> REVERSE_KEY.isDown();
        };
    }

    /** 处理 Alt、Z 和配方反转键的按下与松开事件。 */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && selecting && isAltKey(event)) {
            selectAllRequested = true;
            selectedBatches = maxSendableBatches();
            return;
        }
        if (!INSERT_RECIPE_KEY.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS && !selecting) {
            beginSelection();
        } else if (event.getAction() == GLFW.GLFW_RELEASE && selecting) {
            finishSelection();
        }
    }

    /** 每客户端 tick 检查界面、目标配方和背包统计是否仍然有效。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTicks++;
        Minecraft minecraft = Minecraft.getInstance();
        if (!bindingLogged) {
            logReverseBinding(minecraft);
        }
        if (!selecting) {
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
     * 每次启动只记录一次反转键的真实绑定，用于排查“改了键却不生效”这类问题：
     * 日志会给出模组读到的键名，以及这个 KeyMapping 是否真的进了控制设置的列表
     * （只有进了列表的按键，options.txt 里保存的绑定才会被写回）。
     */
    private static void logReverseBinding(Minecraft minecraft) {
        bindingLogged = true;
        boolean registered = false;
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (mapping == REVERSE_KEY) {
                registered = true;
                break;
            }
        }
        LOGGER.info("反转键绑定 = {}，已进入控制设置列表 = {}",
                REVERSE_KEY.getKey().getName(), registered);
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

    /** 判断按键是否为“插入配方”或“反转方向”键（按玩家当前实际绑定判断，改键后立即生效）。 */
    private static boolean isModKeyMapping(int keyCode) {
        return isBoundKeySym(INSERT_RECIPE_KEY, keyCode) || isBoundKeySym(REVERSE_KEY, keyCode);
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

    /** 在 EMI 界面上绘制材料高亮和份数提示。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRender(ScreenEvent.Render.Post event) {
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
        List<Component> lines = new ArrayList<>(4);
        List<Integer> colors = new ArrayList<>(4);
        lines.add(getCountText(availableKey, availableBatches,
                reverseMode ? "周围现有" : "背包现有"));
        colors.add(0xB8B8B8);
        lines.add(getCountText(reverseMode
                        ? "text.recipe_sender.pull_count" : "text.recipe_sender.send_count",
                selectedBatches, reverseMode ? "取回" : "发送"));
        colors.add(0xFFFFFF);
        if (!reverseMode && activeCircuit != GtCircuitSupport.NO_CIRCUIT) {
            // 提前显示将要写入的电路，避免玩家不知道这次发送会顺带改动机器配置。
            lines.add(getCircuitText(activeCircuit));
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

    /** 初始化当前悬停配方，并根据按键状态进入正向或反向模式。 */
    private static void beginSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen == null) {
            return;
        }
        AbstractContainerScreen<?> container = EmiApi.getHandledScreen();
        EmiStackInteraction hovered = EmiApi.getHoveredStack(true);
        EmiRecipe recipe = getRecipe(hovered);
        if (container == null || hovered.isEmpty() || recipe == null) {
            return;
        }

        selecting = true;
        reverseMode = FindMeExtendedAdapter.isAvailable() && isReverseKeyHeld();
        selectAllRequested = isAltDown();
        selectedBatches = reverseMode ? 0 : 1;
        pendingScrollNotches = 0;
        activeScreen = minecraft.screen;
        activeContainer = container;
        activeIngredient = hovered.getStack();
        activeRecipe = recipe;
        // 电路只影响机器的配方匹配，不参与材料统计，因此在开始选择时解析一次即可。
        activeCircuit = GtCircuitSupport.findCircuit(recipe);
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

    /** 松开 Z 时提交正向发送或反向取回请求。 */
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
     * 请求服务端把目标机器的电路调整为当前配方要求的编号。
     *
     * <p>这里不预先判断容器是否支持电路：机器实例只有服务端才有，客户端只看得到菜单，
     * 判断交给服务端，不支持的容器会直接忽略这个数据包。
     *
     * <p>唯一的例外是“目标压根不是格雷机器界面”（原版箱子、漏斗等）——菜单类型在客户端与
     * 服务端是一致的，这类目标连一个能装电路的机器实例都取不到，直接不发，省掉一次无意义往返。
     * 至于多方块控制器、蒸汽输入总线这类“是格雷界面但没有电路槽”的情况，仍然交给服务端判断。
     */
    private static void sendCircuitRequest() {
        if (reverseMode || activeCircuit == GtCircuitSupport.NO_CIRCUIT || activeContainer == null) {
            return;
        }
        if (!GtCircuitSupport.isModularUiContainer(activeContainer.getMenu())) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new SetContainerCircuitPacket(
                activeContainer.getMenu().containerId, activeCircuit));
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
        insertableBatches = activeContainer == null ? 0
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

    /** 生成带语言文件回退文本的电路提示。 */
    private static Component getCircuitText(int circuit) {
        String key = "text.recipe_sender.circuit";
        String text = I18n.get(key, circuit);
        if (text.equals(key) || text.startsWith("Format error:")) {
            return Component.literal("电路 #" + circuit);
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
        activeCircuit = GtCircuitSupport.NO_CIRCUIT;
        highlightedInventorySlots = Set.of();
    }
}
