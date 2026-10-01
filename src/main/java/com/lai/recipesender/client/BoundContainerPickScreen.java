package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ④ 选择目标容器弹窗。
 *
 * <p>候选 ≥ 2 个主容器时弹出：一行 = 一个发送单元 = 一个主容器。
 * 并列成员与从容器不出现在这里（它们是发送单元的内部结构，不是并列的选项）。
 *
 * <p>材料在这一步**还没有**从背包里扣：{@code requirements} 是客户端按当前份数算好的清单，
 * 真正扣除与插入都发生在服务端。所以玩家取消选择不会损失任何东西。
 *
 * <p>「上次用过的发送单元」只是**预选中**它，并允许按 B 直达——弹窗本身照常弹出。
 */
class BoundContainerPickScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 28;
    private static final int SEARCH_HEIGHT = 20;
    private static final int PADDING = 8;
    private static final int ICON_SIZE = 16;
    private static final int HIGHLIGHT_BUTTON_WIDTH = 26;

    private final Screen parent;
    private final List<BoundContainer> candidates;
    private final List<ItemStack> requirements;
    private final int batches;

    /**
     * 这次选择对应的配方路由键（S6）。「上次选择」按它分别记忆（方案 §4.3 D2）：
     * 组装机选过 A、化学选过 B，两边互不覆盖。取不到时退回 {@code recipe_sender:manual}。
     */
    private final ResourceLocation routeKey;

    /** 过滤后的候选，数字键与方向键都按这个顺序生效。 */
    private final List<BoundContainer> filtered = new ArrayList<>();
    private BoundEditBox searchBox;
    private UUID selectedId;
    private int scrollOffset;
    private int visibleRows;

    /** 打开弹窗时绑定发送键（B）往往还按着，必须先等玩家松手才认「再按一次 B」。 */
    private boolean awaitBoundRelease;

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listHeight;
    private boolean hasSearch;

    BoundContainerPickScreen(Screen parent, List<BoundContainer> candidates, List<ItemStack> requirements,
                             int batches, ResourceLocation routeKey) {
        super(Component.translatable("text.recipe_sender.pick_title"));
        this.parent = parent;
        this.candidates = List.copyOf(candidates);
        this.requirements = List.copyOf(requirements);
        this.batches = batches;
        this.awaitBoundRelease = true;
        this.routeKey = routeKey == null ? BoundContainerClient.manualRoute() : routeKey;
        BoundContainer last = BoundContainerClient.lastChoice(this.routeKey);
        this.selectedId = last == null ? null : last.id();
    }

    @Override
    protected void init() {
        filtered.clear();
        filtered.addAll(candidates);
        moveLastChoiceToFront();

        hasSearch = candidates.size() > MAX_VISIBLE_ROWS;
        int chrome = HEADER_HEIGHT + FOOTER_HEIGHT + (hasSearch ? SEARCH_HEIGHT : 0);
        panelWidth = Math.min(PANEL_WIDTH, width - 20);
        int available = height - 20 - chrome;
        visibleRows = Math.max(1, Math.min(Math.min(candidates.size(), MAX_VISIBLE_ROWS), available / ROW_HEIGHT));
        if (candidates.size() > visibleRows) {
            hasSearch = true;
            chrome = HEADER_HEIGHT + FOOTER_HEIGHT + SEARCH_HEIGHT;
            available = height - 20 - chrome;
            visibleRows = Math.max(1, Math.min(candidates.size(), available / ROW_HEIGHT));
        }
        panelHeight = chrome + visibleRows * ROW_HEIGHT;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + HEADER_HEIGHT + (hasSearch ? SEARCH_HEIGHT : 0);
        listHeight = visibleRows * ROW_HEIGHT;

        if (hasSearch) {
            searchBox = new BoundEditBox(font, left + PADDING, top + HEADER_HEIGHT + 1, panelWidth - PADDING * 2,
                    16, Component.translatable("text.recipe_sender.pick_search"));
            searchBox.setHint(Component.translatable("text.recipe_sender.pick_search"));
            searchBox.setResponder(value -> {
                refreshFilter(value);
                scrollOffset = 0;
            });
            addRenderableWidget(searchBox);
        }

        // 预选上次用过的发送单元；没有记录时选第一个。
        if (selectedId == null || indexOf(selectedId) < 0) {
            selectedId = filtered.isEmpty() ? null : filtered.get(0).id();
        }
        scrollToSelection();
    }

    /**
     * 把「上次用过」的那一项提到列表最前。
     *
     * <p>列表因此是「最近优先」：上次那个永远在第一行，数字键 {@code 1} 也就恒等于「上次那个」。
     * 有搜索词时同样置顶，所以过滤后按 {@code 1} 仍然是上次那个（如果它还在结果里）。
     */
    private void moveLastChoiceToFront() {
        BoundContainer last = BoundContainerClient.lastChoice(routeKey);
        if (last == null) {
            return;
        }
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id().equals(last.id())) {
                if (i > 0) {
                    filtered.add(0, filtered.remove(i));
                }
                return;
            }
        }
    }

    private void refreshFilter(String query) {
        filtered.clear();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (BoundContainer binding : candidates) {
            if (needle.isEmpty() || matches(binding, needle)) {
                filtered.add(binding);
            }
        }
        moveLastChoiceToFront();
        if (indexOf(selectedId) < 0) {
            selectedId = filtered.isEmpty() ? null : filtered.get(0).id();
        }
    }

    /** 搜索匹配名称、方块名、坐标；名称与方块名额外支持拼音（首字母 / 全拼）。 */
    private static boolean matches(BoundContainer binding, String needle) {
        if (PinyinSupport.match(binding.name(), needle)) {
            return true;
        }
        if (PinyinSupport.match(BoundUi.blockText(binding), needle)) {
            return true;
        }
        var pos = binding.pos();
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + BoundUi.posText(binding);
        return coords.toLowerCase(Locale.ROOT).contains(needle);
    }

    private int indexOf(UUID id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void scrollToSelection() {
        int index = indexOf(selectedId);
        if (index < 0) {
            return;
        }
        if (index < scrollOffset) {
            scrollOffset = index;
        } else if (index >= scrollOffset + visibleRows) {
            scrollOffset = Math.min(index - visibleRows + 1, Math.max(0, filtered.size() - visibleRows));
        }
    }

    /**
     * 非模态：世界继续跑。
     *
     * <p>两个理由。一是与来源界面一致——玩家是在容器界面（{@code AbstractContainerScreen#isPauseScreen()}
     * 本来就是 false）里松开 Z 才弹出这里的，突然冻结世界会显得像卡住。二是这里的 {@code awaitBoundRelease}
     * 靠 {@link #tick()} 复位，而暂停时 {@code Screen#tick()} 会不会被调用不该成为「弹窗里再按一次 B
     * 直达上次」能否生效的前提条件。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (awaitBoundRelease && !RecipeSenderClient.isBoundSendKeyHeld()) {
            awaitBoundRelease = false;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        BoundUi.panel(graphics, left, top, panelWidth, panelHeight);

        BoundUi.centered(graphics, title, left + panelWidth / 2, top + 6, BoundUi.TEXT);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.pick_subtitle",
                batches), left + panelWidth / 2, top + 18, BoundUi.TEXT_DIM);

        if (filtered.isEmpty()) {
            BoundUi.centered(graphics, Component.translatable("text.recipe_sender.pick_no_match"),
                    left + panelWidth / 2, listTop + listHeight / 2 - 4, BoundUi.TEXT_DIM);
        } else {
            graphics.enableScissor(left + 1, listTop, left + panelWidth - 1, listTop + listHeight);
            for (int i = 0; i < visibleRows; i++) {
                int index = scrollOffset + i;
                if (index >= filtered.size()) {
                    break;
                }
                drawRow(graphics, filtered.get(index), index, listTop + i * ROW_HEIGHT, mouseX, mouseY);
            }
            graphics.disableScissor();
        }

        int footerY = top + panelHeight - FOOTER_HEIGHT + 4;
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.pick_hint"),
                left + panelWidth / 2, footerY + 4, BoundUi.TEXT_DIM);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, BoundContainer binding, int index, int rowY, int mouseX,
                         int mouseY) {
        int x = left + PADDING;
        int rowWidth = panelWidth - PADDING * 2;
        boolean selected = binding.id().equals(selectedId);
        boolean hovered = BoundUi.inside(mouseX, mouseY, x, rowY, rowWidth, ROW_HEIGHT);

        if (selected) {
            graphics.fill(x, rowY, x + rowWidth, rowY + ROW_HEIGHT, BoundUi.ROW_SELECTED);
            graphics.renderOutline(x, rowY, rowWidth, ROW_HEIGHT, BoundUi.BORDER_LIGHT);
        } else if (hovered) {
            graphics.fill(x, rowY, x + rowWidth, rowY + ROW_HEIGHT, BoundUi.ROW_HOVER);
        }

        int cursor = x + 4;
        if (index < 9) {
            String number = Integer.toString(index + 1);
            graphics.drawString(font, number, cursor, rowY + 7, BoundUi.TEXT_DIM, false);
            cursor += 8;
        } else {
            cursor += 8;
        }
        if (!binding.iconItem().isEmpty()) {
            graphics.renderItem(binding.iconItem(), cursor, rowY + 3);
        }
        cursor += ICON_SIZE + 2;

        // 弹窗里一行 = 一个发送单元，所以名字带 ×N（N 含主容器自己、不含从容器），
        // 标签则显示「并列组」与「从容器 ×M」——数字只出现在从容器标签上。
        String displayName = BoundContainerClient.displayName(binding);
        graphics.drawString(font, displayName, cursor, rowY + 3, BoundUi.TEXT, false);
        cursor += font.width(displayName) + 4;

        int parallelCount = BoundContainerClient.memberCount(binding.id());
        int slaveCount = BoundContainerClient.slaveCount(binding.id());
        boolean last = isLastChoice(binding);
        BoundUi.tags(graphics, cursor, rowY + 2, binding, parallelCount, slaveCount, last);

        graphics.drawString(font, BoundUi.subText(binding), x + 22, rowY + 12, BoundUi.TEXT_DIM, false);

        int buttonX = x + rowWidth - HIGHLIGHT_BUTTON_WIDTH - 2;
        int buttonY = rowY + 3;
        boolean buttonHovered = BoundUi.inside(mouseX, mouseY, buttonX, buttonY, HIGHLIGHT_BUTTON_WIDTH,
                ICON_SIZE - 2);
        graphics.fill(buttonX, buttonY, buttonX + HIGHLIGHT_BUTTON_WIDTH, buttonY + ICON_SIZE - 2,
                buttonHovered ? BoundUi.BORDER_LIGHT : BoundUi.PANEL);
        graphics.renderOutline(buttonX, buttonY, HIGHLIGHT_BUTTON_WIDTH, ICON_SIZE - 2, BoundUi.BORDER_DARK);
        graphics.drawString(font, Component.translatable("text.recipe_sender.button_highlight"),
                buttonX + 3, buttonY + 3, BoundUi.TEXT, false);
    }

    private boolean isLastChoice(BoundContainer binding) {
        BoundContainer last = BoundContainerClient.lastChoice(routeKey);
        return last != null && last.id().equals(binding.id());
    }

    private int highlightButtonX() {
        return left + PADDING + (panelWidth - PADDING * 2) - HIGHLIGHT_BUTTON_WIDTH - 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 绑定发送键被改成鼠标键时，「再按一次直达上次」走的是鼠标通路。
        // 放在最前面：这个手势的语义优先于「点行选中」，否则把左键绑上去会两件事一起做。
        if (RecipeSenderClient.matchesBoundSendMouse(button)) {
            if (!awaitBoundRelease) {
                // 玩家还按着打开弹窗的那一下，不能当成「再按一次」。
                sendToLastChoice();
            }
            return true;
        }
        BoundEditBox search = searchBox;
        if (search != null && search.isMouseOver(mouseX, mouseY)) {
            BoundUi.focus(this, search);
            return search.mouseClicked(mouseX, mouseY, button);
        }
        setFocused(null);
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int rowX = left + PADDING;
        int rowWidth = panelWidth - PADDING * 2;
        for (int i = 0; i < visibleRows; i++) {
            int index = scrollOffset + i;
            if (index >= filtered.size()) {
                break;
            }
            int rowY = listTop + i * ROW_HEIGHT;
            if (!BoundUi.inside(mouseX, mouseY, rowX, rowY, rowWidth, ROW_HEIGHT)) {
                continue;
            }
            BoundContainer binding = filtered.get(index);
            if (BoundUi.inside(mouseX, mouseY, highlightButtonX(), rowY + 3, HIGHLIGHT_BUTTON_WIDTH,
                    ICON_SIZE - 2)) {
                highlight(binding);
            } else {
                confirm(binding);
            }
            return true;
        }
        // 点击弹窗外 = 取消。
        if (!BoundUi.inside(mouseX, mouseY, left, top, panelWidth, panelHeight)) {
            onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (filtered.size() > visibleRows) {
            int maxScroll = filtered.size() - visibleRows;
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(delta)));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (RecipeSenderClient.matchesBoundSendKey(keyCode, scanCode)) {
            if (awaitBoundRelease) {
                // 玩家还按着打开弹窗的那一下 B，不能当成「再按一次」。
                return true;
            }
            sendToLastChoice();
            return true;
        }
        if (RecipeSenderClient.matchesManageKey(keyCode, scanCode)) {
            minecraft.setScreen(new BoundContainerManageScreen(parent));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // E 关界面（和原版背包一致）；焦点在搜索框里时 E 是普通字符，交给下面的分支转发。
        if (keyCode == GLFW.GLFW_KEY_E && (searchBox == null || !searchBox.isFocused())) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            moveSelection(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            moveSelection(1);
            return true;
        }

        boolean searchFocused = searchBox != null && searchBox.isFocused();
        if (!searchFocused && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            int index = indexOf(selectedId);
            if (index >= 0) {
                confirm(filtered.get(index));
            }
            return true;
        }
        if (!searchFocused && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            quickSelect(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void moveSelection(int delta) {
        if (filtered.isEmpty()) {
            return;
        }
        int index = indexOf(selectedId);
        if (index < 0) {
            index = 0;
        } else {
            index = (index + delta + filtered.size()) % filtered.size();
        }
        selectedId = filtered.get(index).id();
        scrollToSelection();
    }

    private void quickSelect(int offset) {
        if (offset >= 0 && offset < filtered.size() && offset < 9) {
            confirm(filtered.get(offset));
        }
    }

    /** 再按一次 B：跳过选择，直接发往上次用过的发送单元；没有记录时弹窗保持打开。 */
    private void sendToLastChoice() {
        BoundContainer last = BoundContainerClient.lastChoice(routeKey);
        if (last == null) {
            RecipeSenderClient.notifyPlayer(Component.translatable("text.recipe_sender.pick_no_last"));
            return;
        }
        BoundContainer target = null;
        for (BoundContainer candidate : candidates) {
            if (candidate.id().equals(last.id())) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            RecipeSenderClient.notifyPlayer(Component.translatable("text.recipe_sender.pick_no_last"));
            return;
        }
        confirm(target);
    }

    private void confirm(BoundContainer binding) {
        RecipeSenderClient.rememberBoundChoice(binding.id(), routeKey);
        RecipeSenderClient.sendBoundInsert(binding.id(), requirements, batches);
        onClose();
    }

    /** 高亮：关掉弹窗回到世界，否则红框画在界面后面根本看不见。 */
    private void highlight(BoundContainer binding) {
        BoundHighlightState.show(binding);
        onClose();
        minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
