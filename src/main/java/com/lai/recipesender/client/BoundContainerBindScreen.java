package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.SetGroupModePacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRelationPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定确认弹窗：按下绑定键、准星指向某个方块时打开。
 *
 * <p>这里只负责收集「用途（物品目标 / 流体中枢）+ 名称 + 容器关系（主容器 / 并列成员 / 从容器）」
 * 这几样东西，真正的落盘、合法性校验（中枢只能有一个、必须有流体能力；父容器必须是主容器、
 * 不能挂到自己身上、目标必须有物品容器……）全部由服务端 {@code BoundBindingService} 再做一遍
 * ——客户端的选择永远只是「请求」。
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
    /**
     * 面板高度。
     *
     * <p>1.0.32 起比原来高 24px：多了一整行「用途」（物品目标 / 流体中枢）。
     * 1.0.38 起再高 16px：编辑已有主容器时多了一行「组模式」。其余段间距维持原样，
     * 屏幕装不下时的压缩规则见 {@link #init()}。
     */
    private static final int PANEL_HEIGHT = 284;
    private static final int PADDING = 8;
    private static final int ROLE_ROW_HEIGHT = 14;
    /**
     * 「组模式」行的高度（1.0.38）。
     *
     * <p>行里只有一个自绘按钮，所以整行就取按钮高度；行首标签在 {@link #drawGroupModeRow} 里按
     * 「(行高 - 行高文字高) / 2」贴着按钮居中。
     */
    private static final int GROUP_ROW_HEIGHT = BoundUi.BUTTON_HEIGHT;
    private static final int DROP_ROW_HEIGHT = 14;
    private static final int DROP_MAX_VISIBLE = 6;
    /** 用途按钮的高度（与其它自绘按钮一致）与两个按钮之间的间距。 */
    private static final int KIND_BUTTON_HEIGHT = BoundUi.BUTTON_HEIGHT;
    private static final int KIND_BUTTON_GAP = 4;

    /**
     * 父容器搜索框里的「只看同类」前缀（1.0.35）。
     *
     * <p>它是输入框里的<b>真实文本</b>，玩家看得见也能删：要挂并列成员时搜索框默认带上它，
     * 只列出与本方块配方类别有交集的主容器；想挂到别的容器上，把 {@code @} 删掉即可。
     */
    private static final String SAME_KIND_PREFIX = "@";

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
     * 这条绑定的用途：物品目标（投放的去处）还是流体中枢（直达的流体来源）。
     *
     * <p>编辑已有绑定时在构造器里定、{@code init()} 里不重置——{@code init()} 会被反复调用
     * （改窗口大小、去类别选择器再回来），重置就等于把玩家刚选的用途顶掉。
     *
     * <p>新建时先兜底成物品目标，真正「按方块能力猜」在 {@link #applyDefaultKind()} 里做一次
     * （构造器里还没有 {@code minecraft}，读不到世界）。
     */
    private BoundContainer.Kind kind = BoundContainer.Kind.ITEM_TARGET;

    /** 「按方块能力猜用途」是否已经做过一次；{@code init()} 会反复跑，只能猜一次。 */
    private boolean kindDefaultApplied;

    /**
     * 面板实际高度与「为了塞进屏幕而挤掉的像素」。
     *
     * <p>{@code PANEL_HEIGHT}（1.0.38 起是 284）在正常窗口下够用，但 GUI 缩放较大或窗口很小时会被
     * 顶出屏幕（240 高的 GUI 里连标题都看不全）。这里把面板压到屏幕内，缺的像素从下半部分的段间距里
     * 挤——文字行高本身压不了。数字不再写死在这里：常量改过几轮，抄进注释只会变成假信息。</p>
     */
    private int panelHeight = PANEL_HEIGHT;
    private int squeeze;

    /** 由 {@code squeeze} 决定的纵向锚点，在 {@link #init()} 里算好，render 直接读。 */
    private int kindY;
    private int roleY;
    /** 「组模式」这一行的 y（1.0.38）；只有 {@link #groupModeEditable()} 为真时才画。 */
    private int groupModeY;
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

    /**
     * 「这台机器只对应一两个类别，先默认勾上第一个」这件事是否已经处理过。
     *
     * <p>{@code init()} 会被反复调用（改窗口大小、从类别选择器回来），默认勾选只能做一次，
     * 否则玩家刚把类别清掉、一拉窗口又被他没选过的类别塞回来。玩家自己动过类别之后
     * （{@link #openCategories()} 的回调、类别行上的滚轮切换）这个标记同样置 true。
     */
    private boolean categoryDefaultApplied;

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
        // 用途与下面的角色同理，**不能放 init()**：编辑已有绑定时必须用它自己的 kind。
        this.kind = editing != null ? editing.kind() : BoundContainer.Kind.ITEM_TARGET;
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
        //
        // 1.0.38 加了「组模式」一行后，各锚点的让出上限必须自上而下单调不减：每行让出的像素不超过
        // 它与上一行之间的空隙，压缩过头时才不会把两行叠在一起。原来 parentLabelY 让 18、categoryY
        // 让 28（18 → 28 这一步就跨过了两者之间仅 2px 的空隙），GUI 高度 280 左右时父容器下拉与类别
        // 按钮会重叠 8px；这里按 6 → 10 → 10 → 14 → 16 的顺序排开，顺带把它修掉。
        squeeze = Math.min(PANEL_HEIGHT - panelHeight, 60);
        top = Math.max(2, (height - panelHeight) / 2);
        kindY = top + 98 - Math.min(squeeze, 6);
        roleY = top + 132 - Math.min(squeeze, 10);
        // 组模式行夹在角色说明（roleY + 44，最多让 10）与父容器区之间，上限同样取 10。
        groupModeY = top + 186 - Math.min(squeeze, 10);
        parentLabelY = top + 206 - Math.min(squeeze, 14);
        parentY = parentLabelY + 10;
        categoryY = top + 236 - Math.min(squeeze, 16);

        resolveBlockInfo();
        // 用途要先定：它决定角色区画不画，也决定 applyDefaultCategory() 要不要动手。
        applyDefaultKind();
        // 必须在 resolveBlockInfo() 之后：默认类别要拿 blockIcon 的物品去比对。
        applyDefaultCategory();

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

        // 刻意不 setInitialFocus(nameBox)：1/2/3 快捷选角色比「一进来就能打字改名」更重要，
        // 而输入框聚焦时数字键会被当成普通字符吃掉。要改名时点一下名称栏即可（点击会自动聚焦）。
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

    /**
     * 新建绑定、还没人碰过类别、且这台机器登记的类别只有一两个时，默认勾上第一个。
     *
     * <p>只对「一个 / 两个」动手：候选一多，猜错比不猜更烦。EMI 还没就绪（{@code entries()} 返回空）
     * 或者方块信息没取到时安静跳过——这时候既没有可信的候选，也不该往界面上塞东西。
     */
    private void applyDefaultCategory() {
        // 中枢不带类别（服务端保存时也会清空），所以不猜、也不显示。
        if (categoryDefaultApplied || editing != null || !pendingRoutes.isEmpty() || blockIcon.isEmpty()
                || kind.isHub()) {
            return;
        }
        List<RecipeCategoryCache.Entry> machineEntries = machineCategories();
        if (machineEntries.isEmpty() || machineEntries.size() > 2) {
            return;
        }
        pendingRoutes = Set.of(machineEntries.get(0).id());
        categoryDefaultApplied = true;
    }

    /**
     * 新建绑定时按方块能力猜一次用途，猜完就固定（玩家在界面上改过之后不再插手）。
     *
     * <p>规则：有物品能力 → 物品目标；没有物品能力但有流体能力 → 流体中枢；两个都拿不到、方块实体
     * 不在、或者问能力时抛异常 → 物品目标（保守，保持 1.0.32 之前的行为）。
     *
     * <p>只在新建时生效：编辑已有绑定时用途必须用它自己的 {@code kind}（构造器里已经定好）。
     * 客户端的能力表可能不全（某些模组只注册服务端），所以这里只是「默认值」，不是校验——
     * 真正的准入由服务端 {@code BoundBindingService} 判定。
     */
    private void applyDefaultKind() {
        if (kindDefaultApplied || editing != null) {
            return;
        }
        kindDefaultApplied = true;
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        BlockEntity blockEntity;
        try {
            // isLoaded 挡在前面：不为看一眼用途去强制加载一个区块（和存活性判定同一套做法）。
            if (!minecraft.level.isLoaded(pos)) {
                return;
            }
            blockEntity = minecraft.level.getBlockEntity(pos);
        } catch (RuntimeException error) {
            return;
        }
        if (blockEntity == null) {
            return;
        }
        if (hasCapability(blockEntity, ForgeCapabilities.ITEM_HANDLER)) {
            kind = BoundContainer.Kind.ITEM_TARGET;
            return;
        }
        if (hasCapability(blockEntity, ForgeCapabilities.FLUID_HANDLER)) {
            kind = BoundContainer.Kind.FLUID_HUB;
        }
    }

    /**
     * 问一次方块实体的能力。
     *
     * <p>有些方块实体在被问能力时会抛异常（自身状态还没准备好、模组实现有 bug），
     * 一律当成「没有这个能力」，绝不让它把打开弹窗这件事搞崩。
     */
    private static boolean hasCapability(BlockEntity blockEntity, Capability<?> capability) {
        try {
            return blockEntity.getCapability(capability).isPresent();
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 当前方块物品登记过的全部类别，顺序沿用 {@link RecipeCategoryCache#entries()}（命名空间 → 显示名）。 */
    private List<RecipeCategoryCache.Entry> machineCategories() {
        List<RecipeCategoryCache.Entry> result = new ArrayList<>();
        if (blockIcon.isEmpty()) {
            return result;
        }
        Item item = blockIcon.getItem();
        for (RecipeCategoryCache.Entry entry : RecipeCategoryCache.entries()) {
            if (entry.usesMachine(item)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 类别行滚轮循环用的候选：与「只看本机可用」同一套过滤，一个都没命中时退回全部。
     *
     * <p>退回全部是为了「滚得动」：没登记的机器如果滚轮完全没反应，玩家只会以为界面坏了。
     */
    private List<RecipeCategoryCache.Entry> scrollCategories() {
        List<RecipeCategoryCache.Entry> machineEntries = machineCategories();
        return machineEntries.isEmpty() ? RecipeCategoryCache.entries() : machineEntries;
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
        // 点方块名 = 把默认名抄进下面的名字输入框：绝大多数容器就叫方块名，省一次手打。
        // blockName 为空（方块没取到、或方块名本身是空串）时不加命中区，免得点一下反而把名字清空。
        if (blockName != null && !blockName.isEmpty()) {
            int nameHitWidth = font.width(blockName) + 20;
            hits.add(new Hit(textX, top + 40, nameHitWidth, font.lineHeight, "copy_name"));
            if (BoundUi.inside(mouseX, mouseY, textX, top + 40, nameHitWidth, font.lineHeight)) {
                pendingTooltip = Lang.message("text.recipe_sender.bind_copy_name_tooltip",
                        "点击复制默认名称");
            }
        }
        BoundUi.clipText(graphics, Component.translatable("text.recipe_sender.bind_block_source",
                blockId), textX + 20, top + 51, textRight - textX - 20, BoundUi.TEXT_DIM);

        graphics.drawString(font, Component.translatable("text.recipe_sender.bind_name_label"),
                textX, top + 62, BoundUi.TEXT_DIM, false);

        // 用途选择放在角色行之上：角色只对物品目标有意义，先定用途再定角色。
        drawKindRow(graphics, mouseX, mouseY, textX, kindY);
        // 中枢固定主容器、没有父容器、没有配方类别，所以整段关系区都不画。命中区是随绘制一起建立的
        // （hits 每帧清空后由绘制补回），不画就没有命中区：1/2/3 与「滚轮切类别」自然落空。
        if (kind == BoundContainer.Kind.ITEM_TARGET) {
            drawRelationSection(graphics, mouseX, mouseY, textX, innerWidth);
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

    /**
     * 角色行 + 父容器下拉 + 类别区块。
     *
     * <p>只给物品目标画：流体中枢固定主容器、没有父容器、没有配方类别，这三样对它全都不适用。
     */
    private void drawRelationSection(GuiGraphics graphics, int mouseX, int mouseY, int textX,
                                     int innerWidth) {
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

        // 1.0.38：组模式。夹在角色说明与父容器之间——它描述的是「这一组机器怎么协同」，
        // 只有已经存在的绑定能改（见 groupModeEditable()），没这一行时 anchor 白白留着不影响布局。
        if (groupModeEditable()) {
            drawGroupModeRow(graphics, mouseX, mouseY, textX, innerWidth);
        }

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
    }

    /**
     * 「组模式」这一行：行首标签 + 右边一个随模式改字的按钮（1.0.38）。
     *
     * <p>整行可点、整行给 tooltip：说明文字是长句，只把 tooltip 挂在按钮上的话，「点哪儿」和
     * 「点了会怎样」会被拆成两块，鼠标稍微偏一点就什么都没有。命中区与角色行一样用 {@code hits}，
     * 不引入原版按钮（这一屏的纵向空间是按行高硬算的，原版按钮的尺寸与内边距塞不进来）。
     *
     * <p>点完**不做本地乐观更新**：服务端改完（成功、失败、或本来就是该模式）都会回提示并回推一份
     * 新列表，界面读到的永远是服务端确认过的模式，不会出现「界面写着整体、服务端还是独立」这种
     * 半拍状态；代价只是点完到回包之间按钮文字不变。
     */
    private void drawGroupModeRow(GuiGraphics graphics, int mouseX, int mouseY, int x, int width) {
        boolean rowHovered = BoundUi.inside(mouseX, mouseY, x, groupModeY, width, GROUP_ROW_HEIGHT);
        if (rowHovered) {
            graphics.fill(x - 2, groupModeY - 1, x + width, groupModeY + GROUP_ROW_HEIGHT - 1,
                    BoundUi.ROW_HOVER);
        }
        Component label = Component.translatable("text.recipe_sender.bind_group_mode");
        // 行首标签与按钮里的字都贴着 16 高的行居中（行高文字 9px，(16 - 9) / 2 = 3，取 4 与
        // BoundUi.button 内部的下标一致）。
        graphics.drawString(font, label, x, groupModeY + 4, BoundUi.TEXT_DIM, false);

        boolean unified = editing.isUnified();
        Component buttonLabel = Component.translatable(unified
                ? "text.recipe_sender.bind_group_mode_unified"
                : "text.recipe_sender.bind_group_mode_independent");
        int buttonX = x + font.width(label) + 6;
        int buttonWidth = BoundUi.buttonWidth(buttonLabel);
        boolean buttonHovered = BoundUi.inside(mouseX, mouseY, buttonX, groupModeY, buttonWidth,
                GROUP_ROW_HEIGHT);
        BoundUi.button(graphics, buttonX, groupModeY, buttonLabel, buttonHovered, true, false);
        if (rowHovered) {
            pendingTooltip = Component.translatable(unified
                    ? "text.recipe_sender.bind_group_mode_unified_tip"
                    : "text.recipe_sender.bind_group_mode_independent_tip");
        }
        hits.add(new Hit(x, groupModeY, width, GROUP_ROW_HEIGHT, "group_mode"));
    }

    /**
     * 「组模式」这一行该不该画（1.0.38）。
     *
     * <p>三个条件缺一不可：
     * <ul>
     *   <li>{@code editing != null}：{@link SetGroupModePacket} 只带一个 id，新建绑定还没有 id，
     *       服务端也只能对已经存在的绑定改模式；</li>
     *   <li>{@code editing.isMaster()}：组模式描述的是「这一组怎么协同」，服务端会拒掉非主容器；</li>
     *   <li>{@code role == MASTER}：界面上当前选的也是主容器。玩家刚把角色点成「并列成员」时，
     *       这一行留着只会让人以为改了模式还能当并列成员用（保存后服务端会把它变成并列成员，
     *       组模式也就跟着没意义了）。</li>
     * </ul>
     *
     * <p>用途是流体中枢时角色区整段都不画（调用点见 {@code render()}），中枢也永远不是整体模式，
     * 这里再挡一道只是为了这条判据本身自洽。
     */
    private boolean groupModeEditable() {
        return editing != null
                && editing.isMaster()
                && role == BoundContainer.Role.MASTER
                && kind == BoundContainer.Kind.ITEM_TARGET;
    }

    /** 用途选择：三个并排按钮「物品目标 / 物品中枢 / 流体中枢」。 */
    private void drawKindRow(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        // 三个按钮取同一个宽度（按最宽的那个），并排看起来才齐。
        int buttonWidth = Math.max(
                BoundUi.buttonWidth(Component.translatable("text.recipe_sender.bind_kind_item")),
                Math.max(BoundUi.buttonWidth(Component.translatable("text.recipe_sender.bind_kind_item_hub")),
                        BoundUi.buttonWidth(Component.translatable("text.recipe_sender.bind_kind_fluid"))));
        drawKindButton(graphics, mouseX, mouseY, x, y, buttonWidth,
                BoundContainer.Kind.ITEM_TARGET, "text.recipe_sender.bind_kind_item");
        drawKindButton(graphics, mouseX, mouseY, x + buttonWidth + KIND_BUTTON_GAP, y, buttonWidth,
                BoundContainer.Kind.ITEM_HUB, "text.recipe_sender.bind_kind_item_hub");
        drawKindButton(graphics, mouseX, mouseY, x + (buttonWidth + KIND_BUTTON_GAP) * 2, y, buttonWidth,
                BoundContainer.Kind.FLUID_HUB, "text.recipe_sender.bind_kind_fluid");
    }

    /**
     * 单个用途按钮。
     *
     * <p>选中态沿用本模组列表里「选中行」的底色（{@link BoundUi#ROW_SELECTED}）加深色描边，
     * 和勾选框那套自绘控件是同一套观感。
     *
     * <p>编辑已有绑定时两个按钮都画出来但不可点（见 {@link #kindEditable()}）：选中态照旧画，
     * 只是没有命中区，玩家能看出这条绑定当前是什么用途。
     */
    private void drawKindButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width,
                                BoundContainer.Kind candidate, String labelKey) {
        Component label = Component.translatable(labelKey);
        boolean selected = kind == candidate;
        boolean clickable = kindEditable();
        boolean hovered = clickable && !selected
                && BoundUi.inside(mouseX, mouseY, x, y, width, KIND_BUTTON_HEIGHT);
        int background = selected ? BoundUi.ROW_SELECTED : hovered ? BoundUi.ROW_HOVER : BoundUi.PANEL;
        graphics.fill(x, y, x + width, y + KIND_BUTTON_HEIGHT, background);
        graphics.renderOutline(x, y, width, KIND_BUTTON_HEIGHT, BoundUi.BORDER_DARK);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2,
                y + (KIND_BUTTON_HEIGHT - font.lineHeight) / 2 + 1,
                selected ? BoundUi.TEXT : clickable ? BoundUi.TEXT_DIM : BoundUi.TEXT_DISABLED, false);
        if (clickable) {
            hits.add(new Hit(x, y, width, KIND_BUTTON_HEIGHT, "kind:" + candidate.name()));
        }
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
        // 搜索框那行给一句「@ 开头只显示同类容器」：@ 是真实文本，玩家看得见，但不会自己知道它的意思。
        if (BoundUi.inside(mouseX, mouseY, x, y + 1, width, DROP_ROW_HEIGHT)) {
            pendingTooltip = Lang.message("text.recipe_sender.bind_parent_same_type_hint",
                    "@ 开头只显示同类容器");
        }
        for (int index = 0; index < visible; index++) {
            BoundContainer candidate = candidates.get(dropScroll + index);
            int rowY = y + 1 + (index + 1) * DROP_ROW_HEIGHT;
            boolean hovered = BoundUi.inside(mouseX, mouseY, x, rowY, width, DROP_ROW_HEIGHT);
            if (hovered) {
                graphics.fill(x, rowY, x + width, rowY + DROP_ROW_HEIGHT, BoundUi.ROW_HOVER);
            }
            // 从容器可以挂到并列成员名下（那一台机器私有的扩容），所以下拉里要能分清两者：
            // 主容器是「发送单元主体」，并列成员是组里的某一台机器。两种都带后缀——
            // 只给并列成员带后缀时，组长光看名字分不出自己是组长还是组员。
            String label = BoundContainerClient.displayName(candidate) + " · "
                    + (candidate.isMaster()
                            ? Lang.message("text.recipe_sender.tag_master", "主容器")
                            : Lang.message("text.recipe_sender.tag_parallel_member", "并列成员"));
            BoundUi.clipText(graphics, Component.literal(label),
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
            return Component.translatable(parentOptions().isEmpty()
                    ? "text.recipe_sender.bind_parent_empty"
                    : "text.recipe_sender.bind_parent_none");
        }
        return Component.literal(BoundContainerClient.displayName(chosen));
    }

    private BoundContainer findParent() {
        return parentId == null ? null : BoundContainerClient.find(parentId);
    }

    /**
     * 当前角色允许挂靠的父容器：排除自己（自己不能当自己的父）。下拉与默认挂靠都基于它。
     *
     * <p>并列成员的父只能是主容器（一个组只有一个主体）；从容器可以挂主容器（组级缓冲，整体模式下
     * 是整组的溢出缓冲），也可以挂某个并列成员（那一台机器私有的扩容，独立模式下就是它了）。
     * 从容器之间不能互相挂靠——服务端 {@code BoundBindingService#validateRelation} 也这么校验，
     * 否则父子关系会成环。
     */
    private List<BoundContainer> parentOptions() {
        boolean includeMembers = role == BoundContainer.Role.SLAVE;
        List<BoundContainer> result = new ArrayList<>();
        for (BoundContainer binding : BoundContainerClient.itemTargets()) {
            if (editing != null && binding.id().equals(editing.id())) {
                continue;
            }
            if (binding.isMaster()
                    || (includeMembers && binding.role() == BoundContainer.Role.PARALLEL)) {
                result.add(binding);
            }
        }
        return result;
    }

    /**
     * 下拉里实际列出的父容器：在 {@link #parentOptions()} 之上按下拉搜索词过滤。
     *
     * <p>搜索词以 {@link #SAME_KIND_PREFIX} 开头时先按「同类」筛一遍（见
     * {@link #sameKind(BoundContainer)}），剩下的部分才当搜索词——玩家可以继续在同类容器里按名字找。
     */
    private List<BoundContainer> parentCandidates() {
        boolean sameKindOnly = dropQuery.startsWith(SAME_KIND_PREFIX);
        String text = sameKindOnly ? dropQuery.substring(SAME_KIND_PREFIX.length()).trim() : dropQuery;
        List<BoundContainer> result = new ArrayList<>();
        for (BoundContainer master : parentOptions()) {
            if (sameKindOnly && !sameKind(master)) {
                continue;
            }
            if (text.isEmpty() || matches(master, text)) {
                result.add(master);
            }
        }
        return result;
    }

    /** 名称、方块名、坐标任一处命中即可；名称与方块名支持拼音。 */
    private boolean matches(BoundContainer binding, String query) {
        if (PinyinSupport.match(binding.name(), query)
                || PinyinSupport.match(BoundUi.blockText(binding), query)) {
            return true;
        }
        BlockPos candidate = binding.pos();
        return (candidate.getX() + " " + candidate.getY() + " " + candidate.getZ()).contains(query);
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
                    // 每次打开都从默认搜索词开始；留着上次的词只会让人以为候选变少了。
                    // 要挂并列成员时默认是「@」（只看同类），见 defaultParentQuery()。
                    dropSearch.setValue(defaultParentQuery());
                    dropQuery = dropSearch.getValue().trim().toLowerCase(Locale.ROOT);
                    BoundUi.focus(this, dropSearch);
                }
            }
            return;
        }
        if (action.startsWith("kind:")) {
            setKind(BoundContainer.Kind.valueOf(action.substring("kind:".length())));
            return;
        }
        if (action.startsWith("role:")) {
            setRole(BoundContainer.Role.valueOf(action.substring("role:".length())));
            return;
        }
        if ("category".equals(action)) {
            openCategories();
            return;
        }
        if ("group_mode".equals(action)) {
            toggleGroupMode();
            return;
        }
        if ("copy_name".equals(action)) {
            if (nameBox != null && blockName != null) {
                nameBox.setValue(blockName);
                // setValue 内部已经把光标挪到末尾，这里再点明一次：玩家接着打字就是往后接。
                nameBox.setCursorPosition(blockName.length());
                nameDraft = blockName;
            }
            return;
        }
        if ("save".equals(action)) {
            save();
        }
    }

    /**
     * 切换组模式：独立 ↔ 整体（1.0.38）。
     *
     * <p>只发包、不改本地字段：{@code editing} 是服务端推来的那份数据的引用，本地改了下一次同步
     * 也会被覆盖；服务端那边无论成功还是失败都会回提示，成功还会回推一份新列表，界面自然跟着变。
     * 所以这里连「点完先装作切过去了」都不做，宁可让按钮晚一个网络往返再改字。
     */
    private void toggleGroupMode() {
        if (!groupModeEditable()) {
            return;
        }
        BoundContainer.GroupMode next = editing.isUnified()
                ? BoundContainer.GroupMode.INDEPENDENT
                : BoundContainer.GroupMode.UNIFIED;
        ModNetwork.CHANNEL.sendToServer(new SetGroupModePacket(editing.id(), next));
    }

    /**
     * 切换用途。
     *
     * <p>选「流体中枢」时顺手把只对物品目标成立的界面状态收干净（角色行与类别区块本来就不画，
     * 但父容器下拉可能正开着）。这里改的只是**界面状态**：真正发给服务端的值在 {@link #save()} 里
     * 按中枢该有的样子重算一遍（主容器、无父容器、无类别），不依赖玩家之前选过什么。
     */
    private void setKind(BoundContainer.Kind newKind) {
        if (kind == newKind || !kindEditable()) {
            return;
        }
        kind = newKind;
        if (kind.isHub()) {
            closeDropdown();
        }
    }

    /**
     * 用途能不能在界面上改。
     *
     * <p>只有新建绑定能改：现有网络包没有「改一条已有绑定的用途」这一种（名字、关系、类别各有一个
     * 包，用途没有），改了就落不了盘，所以编辑时两个按钮只画不可点（见 {@link #drawKindButton}）。
     */
    private boolean kindEditable() {
        return editing == null;
    }

    /**
     * 切换角色。点击角色行与 1/2/3 数字键走的是同一条路径，行为必须完全一致。
     *
     * <p>主容器不需要父容器（切过去顺手清掉，免得把旧父容器带到服务端）；并列成员 / 从容器
     * 没有父容器时补一个默认的，省一次点击。
     */
    private void setRole(BoundContainer.Role newRole) {
        // 中枢没有角色可言：角色行根本不画，这里是防键盘/异常路径把隐藏状态改掉。
        if (kind.isHub()) {
            return;
        }
        role = newRole;
        if (role == BoundContainer.Role.MASTER) {
            parentId = null;
            closeDropdown();
        } else {
            ensureParent();
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
                routes -> {
                    pendingRoutes = Set.copyOf(routes);
                    // 玩家自己确认过一次类别，默认勾选就永远不再插手（哪怕他把类别清空）。
                    categoryDefaultApplied = true;
                }));
    }

    /**
     * 切到「并列成员 / 从容器」时，默认先挂到一个合适的父容器上，省一次点击。
     *
     * <p>从容器优先挂到<b>最近一次绑定的并列成员</b>名下：独立模式下每台机器各配一只箱子才是常态
     * （整体模式下全组的从容器由主容器名下的那些充当组级缓冲）。其余情况沿用「最近一次并列成员用的
     * 主容器 → 第一个同类 → 列表第一个」的次序——候选里机器和箱子混在一起时，第一个往往不是想要的。
     */
    private void ensureParent() {
        if (parentId != null && findParent() != null) {
            return;
        }
        if (role == BoundContainer.Role.SLAVE) {
            BoundContainer newest = newestParallel();
            if (newest != null && (editing == null || !newest.id().equals(editing.id()))) {
                parentId = newest.id();
                return;
            }
        }
        // 优先沿用最近一次并列成员用的父容器（连续挂同类机器时就是同一个），其次是第一个同类
        // 主容器，最后才退回列表里的第一个：候选里机器和箱子混在一起时，第一个往往不是想要的。
        BoundContainer last = lastParallelParent();
        if (last != null && sameKind(last)) {
            parentId = last.id();
            return;
        }
        List<BoundContainer> candidates = parentOptions();
        for (BoundContainer candidate : candidates) {
            if (sameKind(candidate)) {
                parentId = candidate.id();
                return;
            }
        }
        parentId = candidates.isEmpty() ? null : candidates.get(0).id();
    }

    // ------------------------------------------------------------------ 快捷绑定

    /**
     * 「接受默认」：绑定界面已经开着时再按一次绑定键，就用推断出来的关系直接确定（1.0.35）。
     *
     * <p>为「连续挂同类并列成员」准备：第一台手动挂好之后，后面每一台只要按两下绑定键
     * （第一下开界面、第二下确定），不必每次重选角色和父容器。
     *
     * <p>推断不出来时<b>什么都不做</b>，只弹一句说明（见 {@link #quickBindParent()}），玩家仍然
     * 停在界面上可以手动选。刻意不「勉强绑定」：猜错的父容器会把材料送到另一台机器上，
     * 而玩家多半不会立刻发现。
     */
    public void acceptDefault() {
        if (isTextFocused()) {
            // 焦点在输入框里：这一次按键是打字（名字里完全可能有个 b），不是绑定请求。
            return;
        }
        if (editing != null || kind.isHub()) {
            // 编辑已有绑定、以及两种中枢，都没有可推断的默认关系，按「确定」处理。
            save();
            return;
        }
        BoundContainer parent = quickBindParent();
        if (parent == null) {
            return;
        }
        // 快捷绑定只用来连续挂并列成员：从容器没有可推断的默认父容器（挂主容器还是挂某一台机器
        // 取决于玩家想扩谁的容量），交给玩家在界面上选。
        role = BoundContainer.Role.PARALLEL;
        parentId = parent.id();
        save();
    }

    /**
     * 快捷绑定要挂的父容器：最近一次绑定的并列成员所用的父容器，且必须与本方块同类。
     *
     * @return 可以直接挂上去的主容器；推断不出来时返回 {@code null}，并且已经弹过一句提醒
     */
    private BoundContainer quickBindParent() {
        BoundContainer newest = newestParallel();
        if (newest == null) {
            RecipeSenderClient.notifyPlayer(
                    Component.translatable("text.recipe_sender.bind_quick_no_parent"),
                    NoticeSeverity.WARN);
            return null;
        }
        BoundContainer parent = BoundContainerClient.find(newest.parentId());
        if (parent == null) {
            RecipeSenderClient.notifyPlayer(
                    Component.translatable("text.recipe_sender.bind_quick_stale_parent"),
                    NoticeSeverity.WARN);
            return null;
        }
        if (!sameKind(parent)) {
            RecipeSenderClient.notifyPlayer(
                    Component.translatable("text.recipe_sender.bind_quick_not_same_type"),
                    NoticeSeverity.WARN);
            return null;
        }
        return parent;
    }

    /**
     * 最近一次绑定的并列成员（按 {@code createdAt} 取最新的一条）。
     *
     * <p>「上一次挂到哪儿」不必另存状态：绑定数据里本来就有创建时间，重启游戏后依然有效，
     * 也不会因为玩家中途在管理界面改了别的关系而错乱。
     */
    private BoundContainer newestParallel() {
        BoundContainer newest = null;
        for (BoundContainer binding : BoundContainerClient.all()) {
            if (binding.role() != BoundContainer.Role.PARALLEL || binding.parentId() == null) {
                continue;
            }
            if (newest == null || binding.createdAt() > newest.createdAt()) {
                newest = binding;
            }
        }
        return newest;
    }

    /** 最近一次并列成员挂着的父容器；没有并列成员、或父容器已经不在了就返回 {@code null}。 */
    private BoundContainer lastParallelParent() {
        BoundContainer newest = newestParallel();
        return newest == null ? null : BoundContainerClient.find(newest.parentId());
    }

    /**
     * 这个主容器与本方块算不算「同类」。
     *
     * <p>判据是<b>配方类别有交集</b>，不是方块 id：同一台机器的 LV / HV 是两个不同的方块，
     * 但类别（{@code gtceu:cutter} 之类）相同，而「连续挂不同电压的同类机器」正是这个功能最常见
     * 的用法。
     *
     * <p>两边任一方还没登记类别（玩家没勾过类别，或这台机器在 EMI 里查不到）时退回「方块本身
     * 是不是同一种」：宁可多认一次同类，也不要让快捷绑定在这种机器上永远用不了。
     */
    private boolean sameKind(BoundContainer master) {
        List<RecipeCategoryCache.Entry> mine = machineCategories();
        if (!mine.isEmpty() && !master.routeKeys().isEmpty()) {
            for (ResourceLocation route : master.routeKeys()) {
                for (RecipeCategoryCache.Entry entry : mine) {
                    if (entry.id().equals(route)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return !blockIcon.isEmpty() && !master.iconItem().isEmpty()
                && ItemStack.isSameItem(master.iconItem(), blockIcon);
    }

    /**
     * 打开父容器下拉时的默认搜索词。
     *
     * <p>要挂父容器（并列成员 / 从容器）时默认带上 {@link #SAME_KIND_PREFIX}：这两种关系几乎总是
     * 挂到同类机器上。本方块没有登记任何类别（箱子之类）时不带——那种情况下 {@code @} 只会把
     * 候选清空，反而更麻烦。
     */
    private String defaultParentQuery() {
        return role != BoundContainer.Role.MASTER && !machineCategories().isEmpty() ? SAME_KIND_PREFIX : "";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (parentDropOpen) {
            // 第一行是搜索框，能滚的候选只剩 DROP_MAX_VISIBLE - 1 行。
            int max = Math.max(0, parentCandidates().size() - (DROP_MAX_VISIBLE - 1));
            dropScroll = Math.max(0, Math.min(max, dropScroll - (int) Math.signum(delta)));
            return true;
        }
        if (cycleCategoryByScroll(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 鼠标停在「类别」按钮上时，滚轮直接循环切换类别（单选），不必再开选择器。
     *
     * <p>只认 {@code hits} 里那一块「类别」命中区：角色不是主容器时按钮根本不画，命中区也就不存在，
     * 滚轮自然落回 super。候选与「只看本机可用」同一套过滤（见 {@link #scrollCategories()}）。
     *
     * @return 是否吃掉了这次滚动
     */
    private boolean cycleCategoryByScroll(double mouseX, double mouseY, double delta) {
        int step = (int) Math.signum(delta);
        if (step == 0) {
            return false;
        }
        for (Hit hit : hits) {
            if (!"category".equals(hit.action)
                    || !BoundUi.inside(mouseX, mouseY, hit.x, hit.y, hit.width, hit.height)) {
                continue;
            }
            List<RecipeCategoryCache.Entry> candidates = scrollCategories();
            if (candidates.isEmpty()) {
                return false;
            }
            // 当前已选中的那个在候选里的位置；没选中（或选了多个）时按「往下从第一个、往上从最后一个」起滚。
            int current = -1;
            if (pendingRoutes.size() == 1) {
                ResourceLocation only = pendingRoutes.iterator().next();
                for (int index = 0; index < candidates.size(); index++) {
                    if (candidates.get(index).id().equals(only)) {
                        current = index;
                        break;
                    }
                }
            }
            int next;
            if (current < 0) {
                next = step > 0 ? 0 : candidates.size() - 1;
            } else {
                next = Math.floorMod(current + step, candidates.size());
            }
            pendingRoutes = Set.of(candidates.get(next).id());
            // 滚轮也是「玩家自己选过了」，默认勾选不该再覆盖它。
            categoryDefaultApplied = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E 关界面（和原版背包一致）；焦点在输入框里时 E 是普通字符，交给 super 转发。
        if (keyCode == GLFW.GLFW_KEY_E && !isTextFocused()) {
            onClose();
            return true;
        }
        // 1 / 2 / 3 直接选角色（主容器 / 并列成员 / 从容器），走的是和点击角色行同一个 setRole()。
        // 输入框聚焦时数字是普通字符，不能抢；用途是流体中枢时角色行根本不画，也不该响应。
        if (!isTextFocused() && kind == BoundContainer.Kind.ITEM_TARGET) {
            BoundContainer.Role shortcut = switch (keyCode) {
                case GLFW.GLFW_KEY_1 -> BoundContainer.Role.MASTER;
                case GLFW.GLFW_KEY_2 -> BoundContainer.Role.PARALLEL;
                case GLFW.GLFW_KEY_3 -> BoundContainer.Role.SLAVE;
                default -> null;
            };
            if (shortcut != null) {
                setRole(shortcut);
                return true;
            }
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
        // 两种中枢都固定是主容器、没有父容器、也没有配方类别（服务端会再强制一遍）。界面上角色行与
        // 类别区块对它本来就不画，这里仍然按中枢该有的样子算一遍要发出去的值，不依赖隐藏状态。
        boolean hub = kind.isHub();
        BoundContainer.Role sendRole = hub ? BoundContainer.Role.MASTER : role;
        UUID sendParentId = hub ? null : parentId;
        Set<ResourceLocation> sendRoutes = hub ? Set.of() : pendingRoutes;
        if (!hub && sendRole != BoundContainer.Role.MASTER && sendParentId == null) {
            RecipeSenderClient.notifyPlayer(Component.translatable("text.recipe_sender.bind_need_parent"),
                    NoticeSeverity.ERROR);
            return;
        }
        if (editing == null) {
            ModNetwork.CHANNEL.sendToServer(new BindContainerPacket(dimension, pos, name, sendRole, kind,
                    sendParentId, sendRoutes));
        } else {
            // 名字留空 = 保持原名（不是「改成未命名」）；关系无论如何都发一遍，服务端自己比对。
            if (!name.isEmpty() && !name.equals(editing.name())) {
                ModNetwork.CHANNEL.sendToServer(new UpdateBindingPacket(editing.id(), name));
            }
            ModNetwork.CHANNEL.sendToServer(
                    new UpdateBindingRelationPacket(editing.id(), sendRole, sendParentId));
            // 类别单独一个包（覆盖语义）。新建时它已经随 BindContainerPacket 走了，这里只补改绑的情况；
            // 非主容器不发——服务端保存时本来就会把它们的类别清空。
            if (sendRole == BoundContainer.Role.MASTER) {
                ModNetwork.CHANNEL.sendToServer(
                        new UpdateBindingRoutesPacket(editing.id(), sendRoutes));
            }
        }
        onClose();
    }

    /** 每帧重建的命中区。 */
    private record Hit(int x, int y, int width, int height, String action) {
    }
}
