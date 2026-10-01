package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.UnbindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ⑦ 已绑定容器 · 管理界面。
 *
 * <p>按主容器分组的嵌套列表：主容器是一级行，并列成员与从容器缩进挂在它下面；
 * 找不到父容器的项收在最外层的「未归组」小节里（父容器被删掉时会这样），
 * **不连带删除**，玩家可以自己决定删掉还是重新绑定。
 *
 * <p>非模态：{@code isPauseScreen()} 返回 false，世界继续跑。
 * 全部操作都只是发一个网络包，界面显示的是服务端回推的最新状态，客户端不做乐观更新。
 *
 * <p>并列成员与从容器**默认收起**，工具栏的「展开 / 收起」按钮切换；一旦搜索就自动展开。
 * 搜索支持中文首字母与全拼（靠 {@link PinyinSupport}，没装 JEC 时静默降级为字面匹配），
 * 搜索框上点右键清空。
 *
 * <p>行内操作：「改名 / 关系 / 高亮（占位）/ 删除」。「类别」要等 S6 的类别选择器。
 */
class BoundContainerManageScreen extends Screen {

    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_MAX_WIDTH = 420;
    private static final int HEADER_HEIGHT = 30;
    private static final int TOOLBAR_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 18;
    private static final int PADDING = 8;
    private static final int ROW_MASTER_HEIGHT = 30;
    private static final int ROW_CHILD_HEIGHT = 26;
    private static final int GROUP_HEAD_HEIGHT = 16;
    private static final int SPACER_HEIGHT = 6;
    private static final int DELETE_CONFIRM_TICKS = 60;
    private static final int TOOLBAR_BUTTON_WIDTH = 56;

    private final Screen parent;

    private EditBox searchBox;
    private EditBox renameBox;
    private Button expandButton;
    private UUID renamingId;
    private UUID pendingDeleteId;
    private int pendingDeleteTicks;
    private String query = "";
    private int scrollOffset;

    /**
     * 并列成员与从容器是否展开。
     *
     * <p>默认**收起**：一级行（主容器）已经带 {@code ×N} 与「+N 并列成员 +M 从容器」的组头说明，
     * 一眼能看出规模；展开才逐行看细节。搜索时无条件展开，否则搜到的子项会因为折叠而「看不见」。
     */
    private boolean expanded;

    /** 上一帧的条目，供「一次滚一行」找行边界。 */
    private List<Entry> lastEntries = List.of();

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listHeight;
    private int contentHeight;

    /** 本帧画出来的行内按钮，用于点击命中判定。每帧重建。 */
    private final List<HitTarget> hitTargets = new ArrayList<>();
    /** 本帧每行的位置，用于把改名输入框摆到行上。 */
    private final Map<UUID, int[]> rowBounds = new HashMap<>();

    BoundContainerManageScreen(Screen parent) {
        super(Component.translatable("text.recipe_sender.manage_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, width - PANEL_MARGIN * 2);
        panelHeight = Math.min(height - PANEL_MARGIN, 320);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + HEADER_HEIGHT + TOOLBAR_HEIGHT;
        listHeight = panelHeight - HEADER_HEIGHT - TOOLBAR_HEIGHT - FOOTER_HEIGHT;

        int toolbarY = top + HEADER_HEIGHT + 2;
        int closeX = left + panelWidth - PADDING - TOOLBAR_BUTTON_WIDTH;
        int expandX = closeX - 4 - TOOLBAR_BUTTON_WIDTH;
        int searchWidth = Math.max(60, expandX - 8 - (left + PADDING));
        searchBox = new EditBox(font, left + PADDING, toolbarY, searchWidth, 18,
                Component.translatable("text.recipe_sender.manage_search"));
        searchBox.setHint(Component.translatable("text.recipe_sender.manage_search"));
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            query = value;
            scrollOffset = 0;
        });
        addRenderableWidget(searchBox);

        expandButton = Button.builder(expandLabel(), button -> toggleExpanded())
                .bounds(expandX, toolbarY, TOOLBAR_BUTTON_WIDTH, 18).build();
        addRenderableWidget(expandButton);
        addRenderableWidget(Button.builder(Component.translatable("text.recipe_sender.manage_close"),
                        button -> onClose())
                .bounds(closeX, toolbarY, TOOLBAR_BUTTON_WIDTH, 18).build());

        if (renamingId != null && !BoundContainerClient.all().isEmpty()) {
            rebuildRenameBox();
        }
    }

    /** 展开 / 收起并列成员与从容器。 */
    private void toggleExpanded() {
        expanded = !expanded;
        if (expandButton != null) {
            expandButton.setMessage(expandLabel());
        }
    }

    private Component expandLabel() {
        return Component.translatable(expanded
                ? "text.recipe_sender.manage_collapse" : "text.recipe_sender.manage_expand");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingDeleteId != null) {
            pendingDeleteTicks--;
            if (pendingDeleteTicks <= 0) {
                pendingDeleteId = null;
            }
        }
    }

    // ------------------------------------------------------------------ 数据组织

    /** 分组：主容器 → 它名下的并列成员与从容器；找不到父容器的单独成组。 */
    private List<Entry> buildEntries() {
        List<BoundContainer> all = BoundContainerClient.all();
        Map<UUID, List<BoundContainer>> children = new LinkedHashMap<>();
        List<BoundContainer> masters = new ArrayList<>();
        List<BoundContainer> orphans = new ArrayList<>();

        for (BoundContainer binding : all) {
            if (binding.isMaster()) {
                masters.add(binding);
            }
        }
        for (BoundContainer binding : all) {
            if (binding.isMaster()) {
                continue;
            }
            BoundContainer parent = findMaster(all, binding.parentId());
            if (parent == null) {
                orphans.add(binding);
            } else {
                children.computeIfAbsent(parent.id(), key -> new ArrayList<>()).add(binding);
            }
        }
        // 组内先列并列成员、再列从容器。两类角色在发送时的地位完全不同（并列成员参与均分、
        // 从容器只在整组放满后收溢出），混在绑定顺序里要一行行看标签才分得清。
        // List.sort 是稳定排序，同一角色内部仍然保持绑定顺序。
        for (List<BoundContainer> group : children.values()) {
            group.sort(Comparator.comparingInt(BoundContainerManageScreen::roleOrder));
        }

        List<Entry> entries = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        boolean searching = !needle.isEmpty();
        // 收起时只列主容器；一搜索就自动展开，否则搜到的并列成员/从容器会因为折叠而「看不见」。
        boolean showChildren = expanded || searching;
        int shown = 0;

        for (BoundContainer master : masters) {
            List<BoundContainer> group = children.getOrDefault(master.id(), List.of());
            List<BoundContainer> visibleChildren = new ArrayList<>();
            for (BoundContainer child : group) {
                if (!searching || matches(child, needle) || matches(master, needle)) {
                    visibleChildren.add(child);
                }
            }
            boolean masterVisible = !searching || matches(master, needle);
            if (!masterVisible && visibleChildren.isEmpty()) {
                continue;
            }
            entries.add(new GroupEntry(Component.translatable("text.recipe_sender.manage_group",
                    groupName(master, group)), groupDetail(group)));
            entries.add(new RowEntry(master, false, false, null));
            shown++;
            for (BoundContainer child : visibleChildren) {
                // 收起时也要计数：页脚的「共 N 项」不该随展开状态跳来跳去。
                shown++;
                if (showChildren) {
                    entries.add(new RowEntry(child, true, false, null));
                }
            }
        }

        List<BoundContainer> visibleOrphans = new ArrayList<>();
        for (BoundContainer orphan : orphans) {
            if (!searching || matches(orphan, needle)) {
                visibleOrphans.add(orphan);
            }
        }
        if (!visibleOrphans.isEmpty()) {
            entries.add(new GroupEntry(Component.translatable("text.recipe_sender.manage_orphan"),
                    Component.translatable("text.recipe_sender.manage_orphan_count", visibleOrphans.size())));
            for (BoundContainer orphan : visibleOrphans) {
                entries.add(new RowEntry(orphan, false, true, orphanReason(orphan)));
                shown++;
            }
        }
        entryCount = shown;
        return entries;
    }

    private int entryCount;

    /** 组内排序权重：并列成员在前，从容器在后。 */
    private static int roleOrder(BoundContainer binding) {
        return binding.role() == BoundContainer.Role.PARALLEL ? 0 : 1;
    }

    private static BoundContainer findMaster(List<BoundContainer> all, UUID id) {
        if (id == null) {
            return null;
        }
        for (BoundContainer binding : all) {
            if (binding.id().equals(id) && binding.isMaster()) {
                return binding;
            }
        }
        return null;
    }

    /** 并列组的名字带 {@code ×N} 后缀，N = 主容器 + 全部并列成员，**不含从容器**。 */
    private static String groupName(BoundContainer master, List<BoundContainer> group) {
        int parallelCount = 1;
        for (BoundContainer child : group) {
            if (child.role() == BoundContainer.Role.PARALLEL) {
                parallelCount++;
            }
        }
        return parallelCount > 1 ? master.name() + " ×" + parallelCount : master.name();
    }

    private static Component groupDetail(List<BoundContainer> group) {
        int parallel = 0;
        int slave = 0;
        for (BoundContainer child : group) {
            if (child.role() == BoundContainer.Role.PARALLEL) {
                parallel++;
            } else if (child.role() == BoundContainer.Role.SLAVE) {
                slave++;
            }
        }
        if (parallel == 0 && slave == 0) {
            return Component.translatable("text.recipe_sender.manage_group_alone");
        }
        return Component.translatable("text.recipe_sender.manage_group_detail", parallel, slave);
    }

    private static Component orphanReason(BoundContainer orphan) {
        // 父容器已经被删掉，名字无从查起，只能拿 id 的前 8 位当线索。
        UUID parentId = orphan.parentId();
        String hint = parentId == null ? "?" : parentId.toString().substring(0, 8);
        return Component.translatable("text.recipe_sender.manage_orphan_reason", hint);
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
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return coords.contains(needle);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        BoundUi.panel(graphics, left, top, panelWidth, panelHeight);
        hitTargets.clear();
        rowBounds.clear();

        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_title_count",
                BoundContainerClient.all().size()), left + panelWidth / 2, top + 6, BoundUi.TEXT);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_subtitle"),
                left + panelWidth / 2, top + 18, BoundUi.TEXT_DIM);

        List<Entry> entries = buildEntries();
        lastEntries = entries;
        contentHeight = 0;
        for (Entry entry : entries) {
            contentHeight += entry.height();
        }
        int maxScroll = Math.max(0, contentHeight - listHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (entries.isEmpty()) {
            drawEmptyState(graphics);
        } else {
            graphics.enableScissor(left + 1, listTop, left + panelWidth - 1, listTop + listHeight);
            int y = listTop - scrollOffset;
            for (Entry entry : entries) {
                if (y + entry.height() > listTop && y < listTop + listHeight) {
                    if (entry instanceof GroupEntry group) {
                        drawGroupHead(graphics, group, y);
                    } else if (entry instanceof RowEntry row) {
                        drawRow(graphics, row, y, mouseX, mouseY);
                    }
                }
                y += entry.height();
            }
            graphics.disableScissor();
        }

        int footerY = top + panelHeight - FOOTER_HEIGHT + 3;
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_footer",
                entryCount), left + panelWidth / 2, footerY, BoundUi.TEXT_DIM);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (renameBox != null) {
            renameBox.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void drawEmptyState(GuiGraphics graphics) {
        int centerX = left + panelWidth / 2;
        int y = listTop + listHeight / 2 - 16;
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty"),
                centerX, y, BoundUi.TEXT);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty_hint"),
                centerX, y + 14, BoundUi.TEXT_DIM);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty_note"),
                centerX, y + 28, BoundUi.TEXT_DIM);
    }

    private void drawGroupHead(GuiGraphics graphics, GroupEntry group, int y) {
        int x = left + PADDING;
        int width = panelWidth - PADDING * 2;
        graphics.fill(x, y, x + width, y + GROUP_HEAD_HEIGHT - 1, 0x22000000);
        graphics.fill(x, y + GROUP_HEAD_HEIGHT - 1, x + width, y + GROUP_HEAD_HEIGHT, BoundUi.BORDER_DARK);
        graphics.drawString(font, group.title(), x + 3, y + 4, BoundUi.TEXT, false);
        graphics.drawString(font, group.detail(), x + 3 + font.width(group.title()) + 6, y + 4,
                BoundUi.TEXT_DIM, false);
    }

    private void drawRow(GuiGraphics graphics, RowEntry row, int y, int mouseX, int mouseY) {
        BoundContainer binding = row.binding();
        int height = row.height();
        // 从容器比并列成员再缩进一档：两类角色在发送时的地位不同（并列成员参与均分、
        // 从容器只收溢出），缩进量拉开才能一眼扫出层级。
        int indent = row.indent()
                ? (binding.role() == BoundContainer.Role.SLAVE ? 28 : 16) : 0;
        int x = left + PADDING + indent;
        int width = panelWidth - PADDING * 2 - indent;
        boolean orphan = row.orphan();

        if (orphan) {
            graphics.fill(x, y, x + width, y + height - 1, BoundUi.WARN_BACKGROUND);
        } else if (BoundUi.inside(mouseX, mouseY, x, y, width, height)) {
            graphics.fill(x, y, x + width, y + height - 1, 0x18000000);
        }
        if (row.indent()) {
            graphics.fill(x, y, x + 1, y + height - 1, binding.role() == BoundContainer.Role.SLAVE
                    ? BoundUi.TAG_SLAVE : BoundUi.TAG_PARALLEL);
        }
        rowBounds.put(binding.id(), new int[]{x, y, width});

        int cursor = x + 4;
        if (row.indent()) {
            graphics.drawString(font, "└", cursor, y + 4, BoundUi.TEXT_DIM, false);
            cursor += 8;
        }
        if (!binding.iconItem().isEmpty()) {
            graphics.renderItem(binding.iconItem(), cursor, y + 3);
        }
        cursor += 20;

        boolean renaming = binding.id().equals(renamingId);
        if (!renaming) {
            graphics.drawString(font, displayName(binding), cursor, y + 4, BoundUi.TEXT, false);
        }
        int nameWidth = font.width(displayName(binding));
        int tagsX = cursor + nameWidth + 4;
        if (!renaming) {
            BoundUi.tags(graphics, tagsX, y + 3, binding, parallelCountOf(binding), slaveCountOf(binding),
                    false);
        }

        int subY = row.indent() ? y + 14 : y + 17;
        Component sub = Component.literal(row.indent()
                ? roleText(binding) + " → " + parentNameOf(binding)
                : BoundUi.subText(binding));
        if (orphan) {
            graphics.drawString(font, row.reason(), x + 20, subY, BoundUi.TEXT_DANGER, false);
        } else {
            graphics.drawString(font, sub, x + 20, subY, BoundUi.TEXT_DIM, false);
        }

        drawRowButtons(graphics, binding, x + width, y, mouseX, mouseY);
    }

    private static String displayName(BoundContainer binding) {
        return binding.name();
    }

    private static String roleText(BoundContainer binding) {
        return binding.role() == BoundContainer.Role.SLAVE
                ? Component.translatable("text.recipe_sender.tag_slave_role").getString()
                : Component.translatable("text.recipe_sender.tag_parallel_member").getString();
    }

    private String parentNameOf(BoundContainer binding) {
        BoundContainer parent = BoundContainerClient.find(binding.parentId());
        return parent == null ? "?" : parent.name();
    }

    private int parallelCountOf(BoundContainer master) {
        int count = 1;
        for (BoundContainer binding : BoundContainerClient.all()) {
            if (binding.role() == BoundContainer.Role.PARALLEL && master.id().equals(binding.parentId())) {
                count++;
            }
        }
        return count;
    }

    private int slaveCountOf(BoundContainer master) {
        int count = 0;
        for (BoundContainer binding : BoundContainerClient.all()) {
            if (binding.role() == BoundContainer.Role.SLAVE && master.id().equals(binding.parentId())) {
                count++;
            }
        }
        return count;
    }

    private void drawRowButtons(GuiGraphics graphics, BoundContainer binding, int rightX, int y, int mouseX,
                                int mouseY) {
        List<Action> actions = actionsFor(binding);
        int totalWidth = 0;
        for (Action action : actions) {
            totalWidth += font.width(action.label()) + 8;
        }
        totalWidth += Math.max(0, actions.size() - 1) * 2;
        int buttonY = y + (binding.role() == BoundContainer.Role.MASTER ? 6 : 4);
        int cursor = rightX - totalWidth;
        for (Action action : actions) {
            int buttonWidth = font.width(action.label()) + 8;
            boolean hovered = BoundUi.inside(mouseX, mouseY, cursor, buttonY, buttonWidth, 16);
            int background = action.danger() ? (hovered ? 0xFFD0D0D0 : BoundUi.PANEL)
                    : (hovered ? BoundUi.BORDER_LIGHT : BoundUi.PANEL);
            graphics.fill(cursor, buttonY, cursor + buttonWidth, buttonY + 16, background);
            graphics.renderOutline(cursor, buttonY, buttonWidth, 16, BoundUi.BORDER_DARK);
            graphics.drawString(font, action.label(), cursor + 4, buttonY + 4,
                    action.danger() ? BoundUi.TEXT_DANGER : BoundUi.TEXT, false);
            hitTargets.add(new HitTarget(cursor, buttonY, buttonWidth, 16, binding.id(), action.action()));
            cursor += buttonWidth + 2;
        }
    }

    private List<Action> actionsFor(BoundContainer binding) {
        if (binding.id().equals(pendingDeleteId)) {
            return List.of(new Action(Component.translatable("text.recipe_sender.button_delete_confirm"),
                    true, "delete"));
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(Component.translatable("text.recipe_sender.button_rename"), false, "rename"));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_relation"), false, "relation"));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_highlight"), false, "highlight"));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_delete"), true, "delete"));
        return actions;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (renameBox != null && renameBox.isMouseOver(mouseX, mouseY)) {
            setFocused(renameBox);
            return renameBox.mouseClicked(mouseX, mouseY, button);
        }
        if (searchBox != null && searchBox.isMouseOver(mouseX, mouseY)) {
            if (button == 1) {
                // 右键清空：搜索词往往是一长串拼音，逐字退格太烦。
                searchBox.setValue("");
            }
            setFocused(searchBox);
            return button == 1 ? true : searchBox.mouseClicked(mouseX, mouseY, button);
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (HitTarget target : hitTargets) {
            if (!BoundUi.inside(mouseX, mouseY, target.x(), target.y(), target.width(), target.height())) {
                continue;
            }
            handleAction(target);
            return true;
        }
        setFocused(null);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleAction(HitTarget target) {
        BoundContainer binding = BoundContainerClient.find(target.bindingId());
        if (binding == null) {
            return;
        }
        switch (target.action()) {
            case "rename" -> beginRename(binding);
            case "relation" -> openRelation(binding);
            case "highlight" -> RecipeSenderClient.notifyHighlightUnavailable(binding.name());
            case "delete" -> requestDelete(binding);
            default -> {
            }
        }
    }

    /**
     * 改容器关系：复用绑定确认弹窗。Esc 退出时回到本界面，而不是回到世界——
     * 服务端改完关系会推一份新列表过来，本界面的 {@code init()} 会重建分组。
     */
    private void openRelation(BoundContainer binding) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new BoundContainerBindScreen(this, binding.dimension().location(),
                binding.pos(), binding));
    }

    private void beginRename(BoundContainer binding) {
        renamingId = binding.id();
        rebuildRenameBox();
        if (renameBox != null) {
            setFocused(renameBox);
            renameBox.setFocused(true);
        }
    }

    private void rebuildRenameBox() {
        renameBox = null;
        int[] bounds = rowBounds.get(renamingId);
        if (bounds == null) {
            // 行不在可见区域内（被滚动出屏幕）：先放弃这次改名，玩家滚回来再点。
            renamingId = null;
            return;
        }
        renameBox = new EditBox(font, bounds[0] + 18, bounds[1] + 1, 120, 16,
                Component.translatable("text.recipe_sender.button_rename"));
        BoundContainer binding = BoundContainerClient.find(renamingId);
        renameBox.setValue(binding == null ? "" : binding.name());
        renameBox.setMaxLength(BoundContainer.MAX_NAME_LENGTH);
    }

    private void commitRename() {
        if (renamingId == null) {
            return;
        }
        UUID id = renamingId;
        String name = renameBox == null ? "" : renameBox.getValue().trim();
        cancelRename();
        if (name.isEmpty()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new UpdateBindingPacket(id, name));
    }

    private void cancelRename() {
        renamingId = null;
        renameBox = null;
        setFocused(null);
    }

    /** 二次确认：第一次点变成红色的「确认删除」，3 秒内再点才真的删。 */
    private void requestDelete(BoundContainer binding) {
        if (binding.id().equals(pendingDeleteId)) {
            pendingDeleteId = null;
            ModNetwork.CHANNEL.sendToServer(new UnbindContainerPacket(binding.id()));
            if (binding.id().equals(renamingId)) {
                cancelRename();
            }
            return;
        }
        pendingDeleteId = binding.id();
        pendingDeleteTicks = DELETE_CONFIRM_TICKS;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, contentHeight - listHeight);
        if (maxScroll > 0 && delta != 0) {
            // MC 的约定是 delta > 0 = 滚轮向上 = 看更前面的行，而 scrollTarget 的参数是
            // 「往哪个方向翻」：正数往后翻、负数往前翻。这里取负号把两者对上，
            // 之前少了这个负号，滚动方向就是反的。
            int direction = (int) -Math.signum(delta);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollTarget(direction)));
        }
        return true;
    }

    /**
     * 一次滚一行的目标偏移。
     *
     * <p>行高有三种（组头 16、一级行 30、子行 26），固定像素步长会让滚动停在一行中间。
     * 这里按累积高度取行边界：向下取「第一个大于当前偏移的边界」，向上取「最后一个小于
     * 当前偏移的边界」——无论从哪个位置开始，一次滚动都正好翻过一整行。
     */
    private int scrollTarget(int direction) {
        List<Integer> bounds = new ArrayList<>(lastEntries.size() + 1);
        bounds.add(0);
        int acc = 0;
        for (Entry entry : lastEntries) {
            acc += entry.height();
            bounds.add(acc);
        }
        if (direction > 0) {
            for (int bound : bounds) {
                if (bound > scrollOffset) {
                    return bound;
                }
            }
            return bounds.get(bounds.size() - 1);
        }
        int result = 0;
        for (int bound : bounds) {
            if (bound >= scrollOffset) {
                break;
            }
            result = bound;
        }
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameBox != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitRename();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelRename();
                return true;
            }
            return renameBox.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameBox != null) {
            return renameBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        cancelRename();
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ 小结构

    private interface Entry {
        int height();
    }

    private record GroupEntry(Component title, Component detail) implements Entry {
        @Override
        public int height() {
            return GROUP_HEAD_HEIGHT;
        }
    }

    private record RowEntry(BoundContainer binding, boolean indent, boolean orphan, Component reason)
            implements Entry {
        @Override
        public int height() {
            return indent ? ROW_CHILD_HEIGHT : ROW_MASTER_HEIGHT;
        }
    }

    private record Action(Component label, boolean danger, String action) {
    }

    private record HitTarget(int x, int y, int width, int height, UUID bindingId, String action) {
    }
}
