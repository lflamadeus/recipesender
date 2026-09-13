package com.lai.recipesender.client;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** 管理 EMI 配方选择、份数调整以及双向材料传输。 */
@Mod.EventBusSubscriber(modid = RecipeSenderMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeSenderClient {
    
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
    private static int selectedBatches;
    private static int availableBatches;
    private static int insertableBatches;
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

    /** 注册基础按键；只有 FindMeExtended 存在时才注册反转按键。 */
    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(INSERT_RECIPE_KEY);
        // 这里只判断模组是否存在，不触发可选模组的反射初始化：
        // 注册阶段一旦初始化失败就会被永久标记，导致反转功能再也无法启用。
        if (FindMeExtendedAdapter.isModPresent()) {
            event.register(REVERSE_KEY);
        }
    }

    /**
     * 判断反转按键当前是否按下。
     * 直接读取实时按键绑定并查询 GLFW 原始状态，因此玩家在控制设置里改键后立即生效；
     * 不能依赖 KeyMapping.isDown()，它在 GUI 冲突上下文下并不可靠。
     */
    private static boolean isReverseKeyHeld() {
        // getKey() 不会返回 null，未绑定时给的是 InputConstants.UNKNOWN（value 为 -1），
        // 因此只需要用 value > 0 排除“未绑定”这一种情况。
        InputConstants.Key bound = REVERSE_KEY.getKey();
        if (bound.getType() == InputConstants.Type.KEYSYM
                && bound.getValue() > 0 && isGlfwKeyDown(bound.getValue())) {
            return true;
        }
        // 绑定到鼠标按键等非键盘输入时退回 KeyMapping 自身状态。
        return REVERSE_KEY.isDown();
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
        if (!selecting) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != activeScreen || !isActiveTarget(EmiApi.getHoveredStack(true))) {
            cancelSelection();
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

    /** 用滚轮调整当前选择的配方份数。 */
    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!selecting || event.getScreen() != activeScreen || awaitingAvailability) {
            return;
        }
        if (!isActiveTarget(EmiApi.getHoveredStack((int) event.getMouseX(),
                (int) event.getMouseY(), true))) {
            cancelSelection();
            return;
        }
        if (event.getScrollDelta() != 0) {
            selectedBatches = adjustBatches(selectedBatches, event.getScrollDelta());
            event.setCanceled(true);
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
            cancelSelection();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        renderInventoryHighlights(graphics, event.getScreen());

        String availableKey = reverseMode
                ? "text.recipe_sender.nearby_available_count"
                : "text.recipe_sender.available_count";
        List<Component> lines = new ArrayList<>(3);
        List<Integer> colors = new ArrayList<>(3);
        lines.add(getCountText(availableKey, availableBatches,
                reverseMode ? "周围现有" : "背包现有"));
        colors.add(0xB8B8B8);
        lines.add(getCountText(reverseMode
                        ? "text.recipe_sender.pull_count" : "text.recipe_sender.send_count",
                selectedBatches, reverseMode ? "取回" : "发送"));
        colors.add(0xFFFFFF);
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
        activeScreen = minecraft.screen;
        activeContainer = container;
        activeIngredient = hovered.getStack();
        activeRecipe = recipe;
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
            cancelSelection();
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
            List<ItemStack> requirements = result.requirements();
            ModNetwork.CHANNEL.sendToServer(new InsertRecipeItemsPacket(
                    activeContainer.getMenu().containerId, requirements));
        }
        cancelSelection();
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

    /** 判断鼠标当前是否仍然指向开始选择时的配方。 */
    private static boolean isActiveTarget(EmiStackInteraction hovered) {
        return !hovered.isEmpty() && hovered.getStack() == activeIngredient
                && getRecipe(hovered) == activeRecipe;
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

    /** 清理当前选择状态，防止旧界面或旧请求继续生效。 */
    private static void cancelSelection() {
        selecting = false;
        reverseMode = false;
        awaitingAvailability = false;
        releasePending = false;
        selectAllRequested = false;
        nearbyAvailabilityReceived = false;
        selectedBatches = 0;
        availableBatches = 0;
        insertableBatches = 0;
        activeRequestId = 0;
        activeScreen = null;
        activeContainer = null;
        activeIngredient = null;
        activeRecipe = null;
        activeSpecs = List.of();
        highlightedInventorySlots = Set.of();
    }
}
