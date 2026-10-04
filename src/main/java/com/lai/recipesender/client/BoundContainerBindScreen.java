package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRelationPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定确认弹窗：按下绑定键、准星指向某个方块时打开。
 *
 * <p>这里只负责收集「名称 + 容器关系（主容器 / 并列成员 / 从容器）」三样东西，真正的落盘、
 * 合法性校验（父容器必须是主容器、不能挂到自己身上、目标必须有物品容器……）全部由服务端
 * {@code BoundBindingService} 再做一遍——客户端的选择永远只是「请求」。
 *
 * <p>同一个界面也承担「改关系」：管理界面的「关系」按钮会带着已有的绑定重新打开它，
 * 这时坐标与方块信息取自绑定自身（绑定的容器可能不在玩家当前维度，客户端读不到方块状态）。
 *
 * <p>主容器下拉带自己的搜索框：主容器一多，靠滚动找一个箱子非常难受。搜索走的是与管理界面、
 * 选择弹窗同一套 {@code PinyinSupport}，所以中文名可以直接打拼音。下拉收起时按键焦点会被交还，
 * 不会出现「界面看着正常、按键却全被看不见的输入框吃掉」。
 */
public class BoundContainerBindScreen extends Screen {

    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 244;
    private static final int PADDING = 8;
    private static final int ROLE_ROW_HEIGHT = 14;
    private static final int DROP_ROW_HEIGHT = 14;
    private static final int DROP_MAX_VISIBLE = 6;

    /** 新建绑定时为 null；从管理界面进来改关系时非 null。 */
    private final Screen parent;
    private final BoundContainer editing;
    private final ResourceLocation dimension;
    private final BlockPos pos;

    private ItemStack blockIcon = ItemStack.EMPTY;
    private String blockName = "";
    private String blockId = "";

    private BoundEditBox nameBox;

    /**
     * 名字输入框里的当前文本。
     *
     * <p>必须存在字段里：{@code init()} 每次都会重建输入框（去类别选择器再回来时 {@code init()} 会再跑
     * 一遍），只把文本留在 {@code EditBox} 里的话，刚敲完名字去勾类别、回来名字就没了。
     */
    private String nameDraft = "";

    private BoundContainer.Role role = BoundContainer.Role.MASTER;
    private UUID parentId;
    private boolean parentDropOpen;
    private int dropScroll;

    /**
     * 面板实际高度与「为了塞进屏幕而挤掉的像素」。
     *
     * <p>{@code PANEL_HEIGHT = 244} 在正常窗口下够用，但 GUI 缩放较大或窗口很小时会被顶出屏幕
     * （240 高的 GUI 里 244 的面板连标题都看不全）。这里把面板压到屏幕内，缺的像素从下半部分的
     * 段间距里挤——文字行高本身压不了。</p>
     */
    private int panelHeight = PANEL_HEIGHT;
    private int squeeze;

    /** 由 {@code squeeze} 决定的纵向锚点，在 {@link #init()} 里算好，render 直接读。 */
    private int roleY;
    private int parentLabelY;
    private int parentY;
    private int categoryY;

    /**
     * 下拉里的搜索框。
     *
     * <p>刻意**不用 {@code addRenderableWidget}**：下拉面板画在 {@code super.render} 之后，
     * 注册进去会被面板底色整个盖住。照管理界面 {@code renameBox} 的做法手动 {@code render}。
     */
    private BoundEditBox dropSearch;

    /**
     * 这个主容器负责的配方类别（S6 自动路由）。
     *
     * <p>只在构造器里初始化、{@code init()} 里**不重置**：从类别选择器回来时 {@code init()} 会再跑一遍，
     * 重置就等于把刚勾好的类别抹掉。空集合 = 「仅手动选择」，这个容器不参与自动路由。
     */
    private Set<ResourceLocation> pendingRoutes = Set.of();

    /** 下拉的搜索词，始终已 trim + 小写。由 {@link #dropSearch} 的 responder 维护。 */
    private String dropQuery = "";

    private int left;
    private int top;

    private final List<Hit> hits = new ArrayList<>();

    /** 本帧悬停中的自绘控件提示，最后统一画（画早了会被后画的控件盖住）。 */
    private Component pendingTooltip;

    /** 新建绑定。 */
    public BoundContainerBindScreen(Screen parent, ResourceLocation dimension, BlockPos pos) {
        this(parent, dimension, pos, null);
    }

    /** 改关系：{@code editing} 非 null 时坐标与方块信息一律取自它。 */
    public BoundContainerBindScreen(Screen parent, ResourceLocation dimension, BlockPos pos,
                                    BoundContainer editing) {
        super(Component.translatable("text.recipe_sender.bind_title"));
        this.parent = parent;
        this.editing = editing;
        this.dimension = editing != null ? editing.dimension().location() : dimension;
        this.pos = editing != null ? editing.pos() : pos;
        this.pendingRoutes = editing != null ? Set.copyOf(editing.routeKeys()) : Set.of();
        this.nameDraft = editing != null ? editing.name() : "";
        // 关系与父容器也在这里定，**不能放 init()**：init() 每次重建控件（去类别选择器再回来、
        // 改窗口大小都会再跑一遍），放进去等于每次回到这个界面都把玩家刚选的关系重置回绑定里的旧值。
        if (editing != null) {
            this.role = editing.role();
            this.parentId = editing.parentId();
        }
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        panelHeight = Math.min(PANEL_HEIGHT, Math.max(140, height - PANEL_MARGIN * 2));
        // 挤掉的像素按「谁下面留白最多谁先让」分配给下半部分的几处段间距。
        squeeze = Math.min(PANEL_HEIGHT - panelHeight, 60);
        top = Math.max(2, (height - panelHeight) / 2);
        roleY = top + 108 - Math.min(squeeze, 10);
        parentLabelY = top + 166 - Math.min(squeeze, 18);
        parentY = parentLabelY + 10;
        categoryY = top + 196 - Math.min(squeeze, 28);

        resolveBlockInfo();

        nameBox = new BoundEditBox(font, left + PADDING, top + 72, PANEL_WIDTH - PADDING * 2, 18,
                Component.translatable("text.recipe_sender.bind_name_label"));
        nameBox.setMaxLength(BoundContainer.MAX_NAME_LENGTH);
        nameBox.setValue(nameDraft);
        // 实时同步回 nameDraft，等于「失焦即确认」：文本永远不会因为输入框被重建而丢。
        nameBox.setResponder(value -> nameDraft = value);
        nameBox.setHint(Component.translatable("text.recipe_sender.bind_name_hint"));
        addRenderableWidget(nameBox);

        // 下拉的第一行是搜索框，坐标与 drawDropdown 里的布局必须一致（面板内边距 +1，行高 14）。
        dropSearch = new BoundEditBox(font, left + PADDING + 1, categoryY, PANEL_WIDTH - PADDING * 2 - 2,
                DROP_ROW_HEIGHT, Component.translatable("text.recipe_sender.bind_parent_search"));
        dropSearch.setMaxLength(BoundContainer.MAX_NAME_LENGTH);
        dropSearch.setHint(Component.translatable("text.recipe_sender.bind_parent_search"));
        dropSearch.setValue(dropQuery);
        dropSearch.setResponder(value -> {
            dropQuery = value.trim().toLowerCase(Locale.ROOT);
            dropScroll = 0;
        });

        addRenderableWidget(Button.builder(Component.translatable("text.recipe_sender.bind_save"),
                        button -> save())
                .bounds(left + PANEL_WIDTH - PADDING - 64, top + panelHeight - 28, 64, 18)
                .build());

        setInitialFocus(nameBox);
    }

    /** 新建时从客户端世界读方块；改关系时只能靠绑定自己存的图标。 */
    private void resolveBlockInfo() {
        if (editing != null) {
            blockIcon = editing.iconItem();
            blockName = BoundUi.blockText(editing);
            blockId = blockIcon.isEmpty()
                    ? ""
                    : BuiltInRegistries.ITEM.getKey(blockIcon.getItem()).toString();
            return;
        }
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        blockIcon = state.getBlock().asItem().getDefaultInstance();
        blockName = state.getBlock().getName().getString();
        blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        BoundUi.panel(graphics, left, top, PANEL_WIDTH, panelHeight);
        hits.clear();

        int textX = left + PADDING;
        int innerWidth = PANEL_WIDTH - PADDING * 2;

        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_title"),
                textX, top + 8, BoundUi.TEXT, false);
        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_subtitle",
                        dimensionText(), posText()), textX, top + 20, BoundUi.TEXT_DIM, false);

        drawSeparator(graphics, top + 34);

        graphics.renderItem(blockIcon, textX, top + 40);
        int textRight = left + PANEL_WIDTH - PADDING;
        BoundUi.clipText(graphics, Component.literal(blockName), textX + 20, top + 40,
                textRight - textX - 20, BoundUi.TEXT);
        BoundUi.clipText(graphics, Component.translatable("text.recipe_sender.bind_block_source",
                blockId), textX + 20, top + 51, textRight - textX - 20, BoundUi.TEXT_DIM);

        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_name_label"),
                textX, top + 62, BoundUi.TEXT_DIM, false);

        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_relation_label"),
                textX, roleY - 12, BoundUi.TEXT_DIM, false);
        drawRoleRow(graphics, mouseX, mouseY, textX, roleY, BoundContainer.Role.MASTER,
                "text.recipe_sender.bind_role_master");
        drawRoleRow(graphics, mouseX, mouseY, textX, roleY + ROLE_ROW_HEIGHT,
                BoundContainer.Role.PARALLEL, "text.recipe_sender.bind_role_parallel");
        drawRoleRow(graphics, mouseX, mouseY, textX, roleY + ROLE_ROW_HEIGHT * 2,
                BoundContainer.Role.SLAVE, "text.recipe_sender.bind_role_slave");
        graphics.drawString(font, roleDescription(), textX, roleY + ROLE_ROW_HEIGHT * 3 + 2,
                BoundUi.TEXT_DIM, false);

        // 这里原来还有一条分隔线，但它正好压在角色说明文字的下沿上，把字挡掉一半；
        // 角色区与父容器区靠标签本身的颜色差异已经能区分开，索性去掉。
        boolean needsParent = role != BoundContainer.Role.MASTER;
        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_parent_label"),
                textX, parentLabelY, needsParent ? BoundUi.TEXT_DIM : BoundUi.TEXT_DISABLED, false);
        drawParentSelect(graphics, mouseX, mouseY, textX, parentY, innerWidth, needsParent);

        // S6：类别按钮。只有主容器需要勾（并列成员与从容器跟随父容器，服务端也会把它们清空）。
        if (role == BoundContainer.Role.MASTER) {
            Component label = Component.translatable("text.recipe_sender.button_category");
            int buttonWidth = BoundUi.buttonWidth(label);
            boolean hovered = BoundUi.inside(mouseX, mouseY, textX, categoryY, buttonWidth,
                    BoundUi.BUTTON_HEIGHT);
            BoundUi.button(graphics, textX, categoryY, label, hovered, true, false);
            if (hovered) {
                pendingTooltip = Component.translatable("text.recipe_sender.tooltip_category");
            }
            // 空集合在服务端是「什么都收」，所以这里必须说清楚，而不是显示「已选 0 个类别」。
            Component summary = pendingRoutes.isEmpty()
                    ? Component.translatable("text.recipe_sender.bind_category_all")
                    : Component.translatable("text.recipe_sender.bind_category_count",
                            pendingRoutes.size());
            graphics.drawString(font, summary, textX + buttonWidth + 6, categoryY + 4,
                    BoundUi.TEXT_DIM, false);
            hits.add(new Hit(textX, categoryY, buttonWidth, BoundUi.BUTTON_HEIGHT, "category"));
        } else {
            graphics.drawString(font, Component.translatable("text.recipe_sender.bind_category_hint"),
                    textX, categoryY + 4, BoundUi.TEXT_DISABLED, false);
        }

        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_footer"),
                textX, top + panelHeight - 22, BoundUi.TEXT_DIM, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (parentDropOpen) {
            drawDropdown(graphics, mouseX, mouseY, textX, categoryY - 1, innerWidth);
        }
        if (pendingTooltip != null) {
            BoundUi.tooltip(graphics, mouseX, mouseY, pendingTooltip);
            pendingTooltip = null;
        }
    }

    private void drawSeparator(GuiGraphics graphics, int y) {
        graphics.fill(left + 1, y, left + PANEL_WIDTH - 1, y + 1, BoundUi.BORDER_DARK);
    }

    private void drawRoleRow(GuiGraphics graphics, int mouseX, int mouseY, int x, int y,
                             BoundContainer.Role candidate, String labelKey) {
        boolean selected = role == candidate;
        boolean hovered = BoundUi.inside(mouseX, mouseY, x, y, PANEL_WIDTH - PADDING * 2,
                ROLE_ROW_HEIGHT);
        if (hovered) {
            graphics.fill(x - 2, y - 1, x + PANEL_WIDTH - PADDING * 2, y + ROLE_ROW_HEIGHT - 1,
                    BoundUi.ROW_HOVER);
        }
        int boxY = y + 1;
        BoundUi.checkbox(graphics, x, boxY, selected, true);
        graphics.drawString(font, Component.translatable(labelKey), x + 16, y + 2,
                selected ? BoundUi.TEXT : BoundUi.TEXT_DIM, false);
        hits.add(new Hit(x - 2, y - 1, PANEL_WIDTH - PADDING * 2, ROLE_ROW_HEIGHT,
                "role:" + candidate.name()));
    }

    private void drawParentSelect(GuiGraphics graphics, int mouseX, int mouseY, int x, int y,
                                  int width, boolean enabled) {
        boolean hovered = enabled && BoundUi.inside(mouseX, mouseY, x, y, width, 18);
        graphics.fill(x, y, x + width, y + 18, hovered ? BoundUi.ROW_HOVER : 0xFFB0B0B0);
        graphics.fill(x, y, x + width, y + 1, BoundUi.BORDER_DARK);
        graphics.fill(x, y + 17, x + width, y + 18, BoundUi.BORDER_LIGHT);
        graphics.fill(x, y, x + 1, y + 18, BoundUi.BORDER_DARK);
        graphics.fill(x + width - 1, y, x + width, y + 18, BoundUi.BORDER_LIGHT);
        BoundUi.clipText(graphics, parentText(), x + 4, y + 5, width - 20,
                enabled ? BoundUi.TEXT : BoundUi.TEXT_DISABLED);
        graphics.drawString(font, "▼", x + width - 12, y + 5,
                enabled ? BoundUi.TEXT_DIM : BoundUi.TEXT_DISABLED, false);
        if (enabled) {
            hits.add(new Hit(x, y, width, 18, "parent"));
        }
    }

    private void drawDropdown(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width) {
        List<BoundContainer> candidates = parentCandidates();
        // 第一行留给搜索框，候选最多 5 行。
        int visible = Math.min(DROP_MAX_VISIBLE - 1, candidates.size());
        dropScroll = Math.max(0, Math.min(dropScroll, Math.max(0, candidates.size() - visible)));
        // 一个候选都没有时也要把面板和搜索框画出来，否则玩家没法改掉那个搜不到东西的关键词。
        int height = (visible + 1) * DROP_ROW_HEIGHT + 2;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BoundUi.BORDER_DARK);
        graphics.fill(x, y, x + width, y + height, 0xFFE0E0E0);
        if (dropSearch != null) {
            dropSearch.render(graphics, mouseX, mouseY, 0.0F);
        }
        for (int index = 0; index < visible; index++) {
            BoundContainer candidate = candidates.get(dropScroll + index);
            int rowY = y + 1 + (index + 1) * DROP_ROW_HEIGHT;
            boolean hovered = BoundUi.inside(mouseX, mouseY, x, rowY, width, DROP_ROW_HEIGHT);
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + DROP_ROW_HEIGHT, BoundUi.ROW_HOVER);
            }
            BoundUi.clipText(graphics, Component.literal(BoundContainerClient.displayName(candidate)),
                    x + 3, rowY + 3, width - 6,
                    candidate.id().equals(parentId) ? BoundUi.TEXT : BoundUi.TEXT_DIM);
            hits.add(new Hit(x, rowY, width, DROP_ROW_HEIGHT, "parent:" + candidate.id()));
        }
    }

    // ------------------------------------------------------------------ 文本

    private String posText() {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private String dimensionText() {
        if ("minecraft".equals(dimension.getNamespace())) {
            return Component.translatable("dimension.minecraft." + dimension.getPath()).getString();
        }
        return dimension.toString();
    }

    private Component roleDescription() {
        String key = switch (role) {
            case MASTER -> "text.recipe_sender.bind_role_master_desc";
            case PARALLEL -> "text.recipe_sender.bind_role_parallel_desc";
            case SLAVE -> "text.recipe_sender.bind_role_slave_desc";
        };
        return Component.translatable(key);
    }

    private Component parentText() {
        if (role == BoundContainer.Role.MASTER) {
            return Component.translatable("text.recipe_sender.bind_parent_not_needed");
        }
        BoundContainer chosen = findParent();
        if (chosen == null) {
            return Component.translatable(masterCandidates().isEmpty()
                    ? "text.recipe_sender.bind_parent_empty"
                    : "text.recipe_sender.bind_parent_none");
        }
        return Component.literal(BoundContainerClient.displayName(chosen));
    }

    private BoundContainer findParent() {
        return parentId == null ? null : BoundContainerClient.find(parentId);
    }

    /** 全部可选的主容器：排除自己（自己不能当自己的父）。下拉与默认挂靠都基于它。 */
    private List<BoundContainer> masterCandidates() {
        List<BoundContainer> result = new ArrayList<>();
        for (BoundContainer master : BoundContainerClient.masters()) {
            if (editing == null || !master.id().equals(editing.id())) {
                result.add(master);
            }
        }
        return result;
    }

    /** 下拉里实际列出的主容器：在 {@link #masterCandidates()} 之上再按下拉搜索词过滤。 */
    private List<BoundContainer> parentCandidates() {
        if (dropQuery.isEmpty()) {
            return masterCandidates();
        }
        List<BoundContainer> result = new ArrayList<>();
        for (BoundContainer master : masterCandidates()) {
            if (matches(master)) {
                result.add(master);
            }
        }
        return result;
    }

    /** 名称、方块名、坐标任一处命中即可；名称与方块名支持拼音。 */
    private boolean matches(BoundContainer binding) {
        if (PinyinSupport.match(binding.name(), dropQuery)
                || PinyinSupport.match(BoundUi.blockText(binding), dropQuery)) {
            return true;
        }
        BlockPos candidate = binding.pos();
        return (candidate.getX() + " " + candidate.getY() + " " + candidate.getZ()).contains(dropQuery);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点到输入框以外 = 名字输入框失焦。文本已经实时同步进 nameDraft，不会因为失焦或界面重建而丢。
        BoundUi.blurFocusedIfOutside(this, mouseX, mouseY);
        // 搜索框在 hits 之外，必须最先判：它的位置在候选行上面，落到 hits 循环里会被当成点空。
        if (parentDropOpen && dropSearch != null && dropSearch.isMouseOver(mouseX, mouseY)) {
            BoundUi.focus(this, dropSearch);
            return dropSearch.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (parentDropOpen) {
                if (clickDropdown(mouseX, mouseY)) {
                    return true;
                }
                closeDropdown();
                return true;
            }
            for (Hit hit : hits) {
                if (BoundUi.inside(mouseX, mouseY, hit.x, hit.y, hit.width, hit.height)) {
                    act(hit.action);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickDropdown(double mouseX, double mouseY) {
        for (Hit hit : hits) {
            if (hit.action.startsWith("parent:")
                    && BoundUi.inside(mouseX, mouseY, hit.x, hit.y, hit.width, hit.height)) {
                parentId = UUID.fromString(hit.action.substring("parent:".length()));
                closeDropdown();
                return true;
            }
        }
        return false;
    }

    /** 收起下拉并把焦点交还给界面，免得收起后按键还被搜索框吃走。 */
    private void closeDropdown() {
        parentDropOpen = false;
        setFocused(null);
    }

    private void act(String action) {
        if ("parent".equals(action)) {
            if (parentDropOpen) {
                closeDropdown();
            } else {
                parentDropOpen = true;
                dropScroll = 0;
                if (dropSearch != null) {
                    // 每次打开都从空搜索开始；留着上次的词只会让人以为候选变少了。
                    dropSearch.setValue("");
                    BoundUi.focus(this, dropSearch);
                }
            }
            return;
        }
        if (action.startsWith("role:")) {
            role = BoundContainer.Role.valueOf(action.substring("role:".length()));
            if (role == BoundContainer.Role.MASTER) {
                parentId = null;
                closeDropdown();
            } else {
                ensureParent();
            }
            return;
        }
        if ("category".equals(action)) {
            openCategories();
            return;
        }
        if ("save".equals(action)) {
            save();
        }
    }

    /** 打开类别选择器；确定时只改本地字段，落盘统一走「保存」。 */
    private void openCategories() {
        if (minecraft == null) {
            return;
        }
        String target = nameBox == null || nameBox.getValue().trim().isEmpty()
                ? blockName : nameBox.getValue().trim();
        minecraft.setScreen(new CategoryPickerScreen(this, target, blockIcon, pendingRoutes,
                routes -> pendingRoutes = Set.copyOf(routes),
                // 右键去 EMI 看类别时选择器会被关掉：把当前勾选先交回来，回来再打开才不会白勾。
                routes -> pendingRoutes = Set.copyOf(routes)));
    }

    /** 切到「并列成员 / 从容器」时，默认先挂到第一个主容器上，省一次点击。 */
    private void ensureParent() {
        if (parentId != null && findParent() != null) {
            return;
        }
        List<BoundContainer> candidates = masterCandidates();
        parentId = candidates.isEmpty() ? null : candidates.get(0).id();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (parentDropOpen) {
            // 第一行是搜索框，能滚的候选只剩 DROP_MAX_VISIBLE - 1 行。
            int max = Math.max(0, parentCandidates().size() - (DROP_MAX_VISIBLE - 1));
            dropScroll = Math.max(0, Math.min(max, dropScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 关界面（和原版背包一致）；焦点在输入框里时 E 是普通字符，交给 super 转发。
        if (keyCode == GLFW.GLFW_KEY_E && !isTextFocused()) {
            onClose();
            return true;
        }
        if (parentDropOpen && dropSearch != null && dropSearch.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            // 在下拉的搜索框里按 Enter 是「过滤好了，收起来看候选」，不该触发保存。
            closeDropdown();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && parentDropOpen) {
            closeDropdown();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 焦点是否落在某个输入框里；是的话 E 应该当普通字符用。 */
    private boolean isTextFocused() {
        return (nameBox != null && nameBox.isFocused())
                || (dropSearch != null && dropSearch.isFocused());
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

    // ------------------------------------------------------------------ 保存

    private void save() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.length() > BoundContainer.MAX_NAME_LENGTH) {
            name = name.substring(0, BoundContainer.MAX_NAME_LENGTH);
        }
        if (role != BoundContainer.Role.MASTER && parentId == null) {
            RecipeSenderClient.notifyPlayer(Component.translatable("text.recipe_sender.bind_need_parent"),
                    NoticeSeverity.ERROR);
            return;
        }
        if (editing == null) {
            ModNetwork.CHANNEL.sendToServer(
                    new BindContainerPacket(dimension, pos, name, role, parentId, pendingRoutes));
        } else {
            // 名字留空 = 保持原名（不是「改成未命名」）；关系无论如何都发一遍，服务端自己比对。
            if (!name.isEmpty() && !name.equals(editing.name())) {
                ModNetwork.CHANNEL.sendToServer(new UpdateBindingPacket(editing.id(), name));
            }
            ModNetwork.CHANNEL.sendToServer(
                    new UpdateBindingRelationPacket(editing.id(), role, parentId));
            // 类别单独一个包（覆盖语义）。新建时它已经随 BindContainerPacket 走了，这里只补改绑的情况；
            // 非主容器不发——服务端保存时本来就会把它们的类别清空。
            if (role == BoundContainer.Role.MASTER) {
                ModNetwork.CHANNEL.sendToServer(
                        new UpdateBindingRoutesPacket(editing.id(), pendingRoutes));
            }
        }
        onClose();
    }

    /** 每帧重建的命中区。 */
    private record Hit(int x, int y, int width, int height, String action) {
    }
}
