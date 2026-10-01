package com.lai.recipesender.client;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 配方类别选择器（方案 §6 ⑩）。
 *
 * <p>给一个主容器勾选「它负责哪些配方类别」。类别总数约 250（GT 系 ≈200 + 原版 14 + AE2 ~10 +
 * 其它插件 20–40），所以：
 * <ul>
 *   <li>按类别 id 的命名空间分组（gtceu / gtocore / minecraft / ae2 …），组头带数量、可折叠；</li>
 *   <li>搜索走 {@link PinyinSupport}，中文名、机器名、类别 id 都能搜，支持首字母与全拼；</li>
 *   <li>列表只画可见区间（{@code scrollOffset} + {@code enableScissor}），不为每行建控件；</li>
 *   <li><b>默认只看当前容器这台机器登记的类别</b>：拿容器方块的物品去比 EMI 的 workstation 列表，
 *       命中即认为这台机器能跑这个类别。250 个类别里真正和这台机器有关的通常只有个位数，
 *       先筛出来比让玩家在全部类别里翻要省事得多；这台机器一个类别都没登记时自动退回全部并在
 *       标题下写明原因（而不是给一个空列表）。勾选框可以随时切回「全部」。</li>
 *   <li>「没有机器、也不是 GT 类别」的纯展示类类别默认隐藏，用「含无机器类别」翻出来。</li>
 * </ul>
 *
 * <p><b>关于列表控件的取舍</b>：方案里建议用原版 {@code ObjectSelectionList}。这里改用本模组
 * 既有的「scissor + scrollOffset」画法（{@link BoundContainerPickScreen}、
 * {@link BoundContainerManageScreen} 同款），原因是它同样只渲染可见行、不建 N 个控件，
 * 并且和其余绑定界面的观感、鼠标滚轮手感完全一致；{@code ObjectSelectionList} 的行高、内边距
 * 与自绘边框叠在一起反而更难对齐。
 *
 * <p>勾选是「本地改、按确定才落盘」：中途关掉界面等于什么都没发生。行上右键直接把该类别
 * 在 EMI 里打开，方便先看一眼再决定要不要勾。
 */
class CategoryPickerScreen extends Screen {

    private static final int PANEL_MAX_WIDTH = 380;
    private static final int PANEL_MAX_HEIGHT = 250;
    private static final int PANEL_MARGIN = 10;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 46;
    private static final int FOOTER_HEIGHT = 26;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_STATION_ICONS = 4;
    /** 判定双击的时间窗，和原版界面一致。 */
    private static final long DOUBLE_CLICK_MS = 250L;

    private final Screen parent;
    private final String targetName;
    private final Consumer<Set<ResourceLocation>> onConfirm;
    private final Set<ResourceLocation> selected;
    private final Set<String> collapsed = new LinkedHashSet<>();

    /**
     * 当前容器方块的物品；{@code null} = 不知道是什么机器（拿不到图标）。
     * 只用来做「只看本机可用」过滤，不参与任何落盘数据。
     */
    private final Item machineItem;

    /** 这台机器有没有登记任何配方类别。为 false 时不做过滤（否则会是一个空列表）。 */
    private boolean machineFilterAvailable;
    /** 「只看本机可用」勾选状态，默认开。 */
    private boolean onlyMachine = true;
    /** 「含无机器类别」勾选状态，默认关。 */
    private boolean showHidden;
    private String needle = "";
    private boolean collapseInitialized;

    private EditBox searchBox;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listHeight;
    private int scrollOffset;

    /** 「只看本机」或搜索生效时，所有分组都展开（结果集本来就小，折叠只会把它藏起来）。 */
    private boolean expandAllGroups;

    /** 上一次左键点到的数据行下标与时刻，用来识别双击（-1 = 上一次点的不是数据行）。 */
    private int lastClickRow = -1;
    private long lastClickTime;

    private final List<Row> rows = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();

    /**
     * @param parent     关闭后回到哪个界面
     * @param targetName 正在配置的容器名，只用于标题提示
     * @param machine    当前容器的方块物品（{@link net.minecraft.world.item.ItemStack#EMPTY} 表示未知），
     *                   用来默认筛出「这台机器能跑」的类别
     * @param selected   当前已勾选的类别
     * @param onConfirm  按下确定时的回调；取消不会调用
     */
    CategoryPickerScreen(Screen parent, String targetName, ItemStack machine,
                         Set<ResourceLocation> selected,
                         Consumer<Set<ResourceLocation>> onConfirm) {
        super(Component.translatable("text.recipe_sender.category_title"));
        this.parent = parent;
        this.targetName = targetName == null ? "" : targetName;
        this.machineItem = machine == null || machine.isEmpty() ? null : machine.getItem();
        this.selected = new LinkedHashSet<>(selected == null ? Set.of() : selected);
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, width - PANEL_MARGIN * 2);
        panelHeight = Math.min(PANEL_MAX_HEIGHT, height - PANEL_MARGIN * 2);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + HEADER_HEIGHT;
        listHeight = panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT;
        searchBox = new EditBox(font, left + PADDING, top + 32, panelWidth - PADDING * 2, 16,
                Component.translatable("text.recipe_sender.category_search"));
        searchBox.setHint(Component.translatable("text.recipe_sender.category_search"));
        searchBox.setMaxLength(64);
        searchBox.setResponder(value -> {
            needle = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            scrollOffset = 0;
            rebuildRows();
        });
        addRenderableWidget(searchBox);
        rebuildRows();
    }

    /** 按「只看本机 + 含无机器类别 + 搜索词 + 折叠状态」重建行列表；只在数据变化时调用，不在每帧渲染里做。 */
    private void rebuildRows() {
        List<RecipeCategoryCache.Entry> entries = RecipeCategoryCache.entries();
        if (!collapseInitialized) {
            // 默认只展开第一组：250 个类别平铺出来没人看得下去，先给一个起点，其余玩家自己展开。
            String firstNamespace = null;
            for (RecipeCategoryCache.Entry entry : entries) {
                if (firstNamespace == null) {
                    firstNamespace = entry.namespace();
                } else if (!firstNamespace.equals(entry.namespace())) {
                    collapsed.add(entry.namespace());
                }
            }
            collapseInitialized = true;
        }

        machineFilterAvailable = machineItem != null
                && entries.stream().anyMatch(entry -> entry.usesMachine(machineItem));
        boolean restrictToMachine = onlyMachine && machineFilterAvailable;
        // 只看本机时结果本来就只有个位数，再默认折叠等于把结果藏起来；搜索同理。
        expandAllGroups = restrictToMachine || !needle.isEmpty();

        // entries 已按「命名空间 → 显示名」排好序，这里按顺序分组即可，不用再排序。
        Map<String, List<RecipeCategoryCache.Entry>> groups = new LinkedHashMap<>();
        for (RecipeCategoryCache.Entry entry : entries) {
            if (!showHidden && !entry.sendable()) {
                continue;
            }
            if (restrictToMachine && !entry.usesMachine(machineItem)) {
                continue;
            }
            if (!needle.isEmpty() && !PinyinSupport.match(entry.searchText(), needle)) {
                continue;
            }
            groups.computeIfAbsent(entry.namespace(), key -> new ArrayList<>()).add(entry);
        }

        rows.clear();
        for (Map.Entry<String, List<RecipeCategoryCache.Entry>> group : groups.entrySet()) {
            String namespace = group.getKey();
            List<RecipeCategoryCache.Entry> members = group.getValue();
            rows.add(new Row(null, namespace, members.size()));
            // 搜索与「只看本机」时忽略折叠状态：结果藏在收起的组里等于搜不到。
            if (expandAllGroups || !collapsed.contains(namespace)) {
                for (RecipeCategoryCache.Entry entry : members) {
                    rows.add(new Row(entry, namespace, members.size()));
                }
            }
        }
        clampScroll();
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_HEIGHT - listHeight);
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        buttons.clear();
        renderBackground(graphics);
        BoundUi.panel(graphics, left, top, panelWidth, panelHeight);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.category_title",
                RecipeCategoryCache.entries().size()), left + panelWidth / 2, top + 6, BoundUi.TEXT);
        Component subtitle = Component.translatable("text.recipe_sender.category_target", targetName);
        if (machineItem != null && !machineFilterAvailable && !RecipeCategoryCache.entries().isEmpty()) {
            // 这台机器一个类别都没登记：退回全部，并把原因写在标题下（否则玩家只会看到「怎么全都在」）。
            subtitle = subtitle.copy().append(" · ")
                    .append(Component.translatable("text.recipe_sender.category_machine_none"));
        }
        BoundUi.centered(graphics, subtitle, left + panelWidth / 2, top + 18, BoundUi.TEXT_DIM);

        renderRows(graphics, mouseX, mouseY, partialTick);
        renderFooter(graphics, mouseX, mouseY);

        // 搜索框是控件，最后画，保证它压在面板上。
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int listBottom = listTop + listHeight;
        if (rows.isEmpty()) {
            BoundUi.centered(graphics, Component.translatable("text.recipe_sender.category_empty"),
                    left + panelWidth / 2, listTop + listHeight / 2 - 4, BoundUi.TEXT_DIM);
            return;
        }
        int first = scrollOffset / ROW_HEIGHT;
        int last = Math.min(rows.size(), (scrollOffset + listHeight) / ROW_HEIGHT + 1);
        graphics.enableScissor(left + 1, listTop, left + panelWidth - 1, listBottom);
        for (int index = first; index < last; index++) {
            int y = listTop + index * ROW_HEIGHT - scrollOffset;
            drawRow(graphics, rows.get(index), y, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            drawScrollbar(graphics, listBottom);
        }
    }

    private void drawScrollbar(GuiGraphics graphics, int listBottom) {
        int trackX = left + panelWidth - 5;
        graphics.fill(trackX, listTop, trackX + 3, listBottom, BoundUi.ROW_DISABLED);
        int thumbHeight = Math.max(12, listHeight * listHeight / Math.max(1, rows.size() * ROW_HEIGHT));
        int travel = listHeight - thumbHeight;
        int thumbY = listTop + (maxScroll() == 0 ? 0 : travel * scrollOffset / maxScroll());
        graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, BoundUi.BORDER_DARK);
    }

    private void drawRow(GuiGraphics graphics, Row row, int y, int mouseX, int mouseY, float partialTick) {
        boolean hovered = BoundUi.inside(mouseX, mouseY, left + 1, y, panelWidth - 2, ROW_HEIGHT);
        if (row.header()) {
            graphics.fill(left + 1, y, left + panelWidth - 1, y + ROW_HEIGHT, BoundUi.ROW_SELECTED);
            boolean open = expandAllGroups || !collapsed.contains(row.namespace());
            drawCheckbox(graphics, left + PADDING, y + 4, open);
            String label = row.namespace() + "  (" + row.count() + ")";
            graphics.drawString(font, label, left + PADDING + 16, y + 5, BoundUi.TEXT, false);
            return;
        }

        RecipeCategoryCache.Entry entry = row.entry();
        boolean picked = selected.contains(entry.id());
        if (picked) {
            graphics.fill(left + 1, y, left + panelWidth - 1, y + ROW_HEIGHT, BoundUi.ROW_SELECTED);
        } else if (hovered) {
            graphics.fill(left + 1, y, left + panelWidth - 1, y + ROW_HEIGHT, BoundUi.ROW_HOVER);
        }
        drawCheckbox(graphics, left + PADDING + 8, y + 4, picked);

        int textX = left + PADDING + 24;
        // 类别图标直接交给 EMI 画：它能处理 GT 那些不是物品的图标（多方块结构、流体仓……）。
        entry.category().render(graphics, textX, y + 1, partialTick);
        textX += 18;
        graphics.drawString(font, entry.name(), textX, y + 5, BoundUi.TEXT, false);
        int nameEnd = textX + font.width(entry.name());

        // 配方条数只做参考，0 条的不写（写一排「0 个配方」只是噪声）。
        if (entry.recipeCount() > 0) {
            graphics.drawString(font, Component.translatable("text.recipe_sender.category_count",
                    entry.recipeCount()).getString(), nameEnd + 6, y + 5, BoundUi.TEXT_DIM, false);
        }

        // 机器图标靠右：这是「这个类别由哪些机器出」的最直观提示。
        List<EmiIngredient> stations = entry.workstations();
        int icons = Math.min(stations.size(), MAX_STATION_ICONS);
        int iconX = left + panelWidth - PADDING - icons * 18;
        for (int index = 0; index < icons; index++) {
            stations.get(index).render(graphics, iconX + index * 18, y + 1, partialTick);
        }
        if (stations.size() > MAX_STATION_ICONS) {
            graphics.drawString(font, "+" + (stations.size() - MAX_STATION_ICONS),
                    iconX + icons * 18 - 2, y + 5, BoundUi.TEXT_DIM, false);
        }
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = top + panelHeight - FOOTER_HEIGHT + 4;
        graphics.drawString(font, Component.translatable("text.recipe_sender.category_selected",
                selected.size()).getString(), left + PADDING, y + 4, BoundUi.TEXT, false);

        int cursorX = left + PADDING + font.width(Component.translatable(
                "text.recipe_sender.category_selected", selected.size())) + 10;
        // 「只看本机」只在这台机器确实登记了类别时才给：否则勾了也没东西可看。
        if (machineFilterAvailable) {
            cursorX = drawToggle(graphics, cursorX, y, "text.recipe_sender.category_only_machine",
                    onlyMachine, "machine");
        }
        drawToggle(graphics, cursorX, y, "text.recipe_sender.category_show_hidden", showHidden, "hidden");

        // 按钮从右往左排：确定在最右，符合「主要动作靠右下」的习惯。
        int cursor = left + panelWidth - PADDING;
        cursor = addButton(graphics, mouseX, mouseY, cursor, y, "text.recipe_sender.category_confirm",
                "confirm", true);
        addButton(graphics, mouseX, mouseY, cursor, y, "text.recipe_sender.category_cancel", "cancel", false);
    }

    /** 画一个勾选框 + 文字，返回下一个勾选框的起点 x。 */
    private int drawToggle(GuiGraphics graphics, int x, int y, String key, boolean checked, String action) {
        Component label = Component.translatable(key);
        drawCheckbox(graphics, x, y + 1, checked);
        graphics.drawString(font, label, x + 14, y + 4, BoundUi.TEXT_DIM, false);
        int width = 14 + font.width(label);
        buttons.add(new Button(x - 2, y - 1, width + 4, 16, "", action));
        return x + width + 10;
    }

    private int addButton(GuiGraphics graphics, int mouseX, int mouseY, int rightX, int y,
                          String key, String action, boolean primary) {
        Component label = Component.translatable(key);
        int buttonWidth = font.width(label) + 12;
        int x = rightX - buttonWidth;
        boolean hovered = BoundUi.inside(mouseX, mouseY, x, y, buttonWidth, 16);
        int background = hovered ? BoundUi.BORDER_LIGHT : BoundUi.PANEL;
        graphics.fill(x, y, x + buttonWidth, y + 16, background);
        graphics.renderOutline(x, y, buttonWidth, 16, BoundUi.BORDER_DARK);
        graphics.drawString(font, label, x + 6, y + 4, primary ? BoundUi.TEXT : BoundUi.TEXT_DIM, false);
        buttons.add(new Button(x, y, buttonWidth, 16, "", action));
        return x - 2;
    }

    private void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked) {
        graphics.fill(x, y, x + 11, y + 11, BoundUi.BORDER_DARK);
        graphics.fill(x + 1, y + 1, x + 10, y + 10, BoundUi.PANEL);
        if (checked) {
            graphics.fill(x + 3, y + 3, x + 8, y + 8, BoundUi.TAG_MASTER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            lastClickRow = -1;
            return true;
        }
        for (Button entry : buttons) {
            if (BoundUi.inside(mouseX, mouseY, entry.x(), entry.y(), entry.width(), entry.height())) {
                lastClickRow = -1;
                activate(entry.action());
                return true;
            }
        }
        int rowIndex = rowIndexAt(mouseX, mouseY);
        if (rowIndex < 0) {
            lastClickRow = -1;
            return false;
        }
        Row row = rows.get(rowIndex);
        if (row.header()) {
            lastClickRow = -1;
            if (button == 0) {
                String namespace = row.namespace();
                if (!collapsed.remove(namespace)) {
                    collapsed.add(namespace);
                }
                rebuildRows();
                return true;
            }
            return false;
        }
        if (button == 0) {
            long now = Util.getMillis();
            boolean doubleClick = rowIndex == lastClickRow && now - lastClickTime <= DOUBLE_CLICK_MS;
            lastClickRow = rowIndex;
            lastClickTime = now;
            if (doubleClick) {
                // 双击 = 勾上这一行并直接确定。第一次单击已经把它勾上了；这里只保证它处于
                // 「勾上」状态（双击一个已勾选的行不该反而取消勾选），然后按确定关界面。
                selected.add(row.entry().id());
                onConfirm.accept(Set.copyOf(selected));
                onClose();
                return true;
            }
            if (!selected.remove(row.entry().id())) {
                selected.add(row.entry().id());
            }
            return true;
        }
        if (button == 1) {
            // 右键 = 先在 EMI 里看一眼这个类别：看完关掉界面就等于没勾，勾了才算。
            lastClickRow = -1;
            EmiApi.displayRecipeCategory(row.entry().category());
            onClose();
            return true;
        }
        lastClickRow = -1;
        return false;
    }

    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < left + 1 || mouseX >= left + panelWidth - 1
                || mouseY < listTop || mouseY >= listTop + listHeight) {
            return -1;
        }
        int index = (int) ((mouseY - listTop + scrollOffset) / ROW_HEIGHT);
        return index >= 0 && index < rows.size() ? index : -1;
    }

    private void activate(String action) {
        switch (action) {
            case "machine" -> {
                onlyMachine = !onlyMachine;
                scrollOffset = 0;
                rebuildRows();
            }
            case "hidden" -> {
                showHidden = !showHidden;
                scrollOffset = 0;
                rebuildRows();
            }
            case "confirm" -> {
                onConfirm.accept(Set.copyOf(selected));
                onClose();
            }
            case "cancel" -> onClose();
            default -> {
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0 && delta != 0) {
            scrollOffset -= (int) Math.signum(delta) * ROW_HEIGHT * 2;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onConfirm.accept(Set.copyOf(selected));
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 列表里的一行：{@code entry} 为 null 表示组头。 */
    private record Row(RecipeCategoryCache.Entry entry, String namespace, int count) {
        boolean header() {
            return entry == null;
        }
    }

    /** 每帧重建的按钮命中区。 */
    private record Button(int x, int y, int width, int height, String label, String action) {
    }
}
