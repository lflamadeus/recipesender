package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.UnbindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>并列成员与从容器**默认收起**，点主容器行展开这一组（行首有 ▼ / ▶ 记号），
 * 工具栏的「展开 / 收起」按钮则一次全开或全关；一旦搜索就自动展开，此时点行不再收起。
 * 搜索支持中文首字母与全拼（靠 {@link PinyinSupport}，没装 JEC 时静默降级为字面匹配），
 * 搜索框上点右键清空。
 *
 * <p>E 或 Esc 关界面（焦点在搜索框 / 改名框里时 E 让给输入框）。
 *
 * <p>行内操作：「改名 / 关系 / 类别 / 高亮 / 删除」。其中「类别」只出现在主容器行上，
 * 打开 {@link CategoryPickerScreen} 勾选这个容器负责的配方类别（S6 自动路由）；并列成员与从容器
 * 跟随父容器，不单独勾。
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
    /** 打开界面后的键盘静默期，见 {@link #swallowOpeningInput()}。 */
    private static final long OPEN_INPUT_GRACE_MS = 250L;

    private final Screen parent;

    private BoundEditBox searchBox;
    private BoundEditBox renameBox;
    private Button expandButton;
    private UUID renamingId;
    private UUID pendingDeleteId;
    private int pendingDeleteTicks;
    private String query = "";
    private int scrollOffset;

    /**
     * 已经展开的主容器。
     *
     * <p>默认**全收起**：一级行（主容器）已经带 {@code ×N} 与「+N 并列成员 +M 从容器」的组头说明，
     * 一眼能看出规模；展开才逐行看细节。
     *
     * <p>按主容器各记各的，而不是一个全局开关：容器一多，想看的往往只有其中一组，
     * 全局展开会把列表撑得很长。工具栏的「展开 / 收起」按钮仍然是全局的（一次全开或全关），
     * 点主容器行则只翻这一组。
     */
    private final Set<UUID> expandedMasters = new HashSet<>();

    /** 本帧是否处于搜索状态。{@link #buildEntries()} 里赋值，{@link #drawRow} 读。 */
    private boolean searching;

    /** 上一帧的条目，供「一次滚一行」找行边界。 */
    private List<Entry> lastEntries = List.of();

    /**
     * 行列表缓存。
     *
     * <p>原来 {@code render()} 每帧都调 {@code buildEntries()}：每帧新建 List / LinkedHashMap、
     * 每组一个 ArrayList 再排序，还要为每个可见行重新数一遍并列成员与从容器（每帧 O(n²)）。
     * 现在只在「服务端同步过（{@link BoundContainerClient#revision()} 变了）」或「本地搜索词、
     * 展开状态变了」时重建。</p>
     */
    private List<Entry> cachedEntries = List.of();
    private int builtRevision = -1;
    private boolean entriesDirty = true;

    /** 每个主容器的 {并列成员数, 从容器数}，在 {@link #buildEntries()} 里一次算好。 */
    private final Map<UUID, int[]> groupCounts = new HashMap<>();

    /** 本帧悬停中的行内按钮提示，最后统一画（画早了会被后画的控件盖住）。 */
    private Component pendingTooltip;

    /** 打开时刻，配合 {@link #OPEN_INPUT_GRACE_MS} 用。 */
    private long openedAt;

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
        // 重建控件前先把焦点交还：init() 之后输入栏都是新对象，焦点却还挂在上一个对象上，
        // 之后「点到框里没有」的判定就会错位。正在改名时这一下会让改名框失焦，按「失焦即确认」提交。
        setFocused(null);
        // 记下打开时刻：开界面那一按的字符事件会落到下面刚建出来的搜索框上（见 swallowOpeningInput）。
        openedAt = Util.getMillis();
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
        searchBox = new BoundEditBox(font, left + PADDING, toolbarY, searchWidth, 18,
                Component.translatable("text.recipe_sender.manage_search"));
        searchBox.setHint(Component.translatable("text.recipe_sender.manage_search"));
        searchBox.setValue(query);
        searchBox.setResponder(value -> {
            query = value;
            scrollOffset = 0;
            // 行列表是按搜索词过滤出来的：词变了必须重建，否则界面上还是上一轮的搜索结果。
            entriesDirty = true;
        });
        addRenderableWidget(searchBox);

        expandButton = Button.builder(expandLabel(), button -> setAllExpanded(!allExpanded()))
                .bounds(expandX, toolbarY, TOOLBAR_BUTTON_WIDTH, 18).build();
        addRenderableWidget(expandButton);
        addRenderableWidget(Button.builder(Component.translatable("text.recipe_sender.manage_close"),
                        button -> onClose())
                .bounds(closeX, toolbarY, TOOLBAR_BUTTON_WIDTH, 18).build());

        if (renamingId != null && !BoundContainerClient.all().isEmpty()) {
            rebuildRenameBox();
        }
    }

    /** 工具栏的全局开关：一次全开或全关。 */
    private void setAllExpanded(boolean value) {
        expandedMasters.clear();
        if (value) {
            for (BoundContainer master : BoundContainerClient.masters()) {
                expandedMasters.add(master.id());
            }
        }
        if (expandButton != null) {
            expandButton.setMessage(expandLabel());
        }
        // 展开状态决定行列表里有没有子行，必须让缓存失效。
        entriesDirty = true;
    }

    /** 点主容器行：只翻这一组。 */
    private void toggleExpanded(UUID masterId) {
        if (searching) {
            // 搜索时强制展开，点了也不该收起——否则搜出来的子项会突然消失。
            return;
        }
        if (!expandedMasters.remove(masterId)) {
            expandedMasters.add(masterId);
        }
        if (expandButton != null) {
            expandButton.setMessage(expandLabel());
        }
        entriesDirty = true;
    }

    private boolean isExpanded(UUID masterId) {
        return searching || expandedMasters.contains(masterId);
    }

    /** 全部主容器都展开时按钮显示「收起」，否则显示「展开」。 */
    private boolean allExpanded() {
        List<BoundContainer> masters = BoundContainerClient.masters();
        if (masters.isEmpty()) {
            return false;
        }
        for (BoundContainer master : masters) {
            if (!expandedMasters.contains(master.id())) {
                return false;
            }
        }
        return true;
    }

    private Component expandLabel() {
        return Component.translatable(allExpanded()
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

    /** 取行列表：只在数据或本地状态变化时重建（每帧重建是 1.0.26 的主要开销）。 */
    private List<Entry> entries() {
        int revision = BoundContainerClient.revision();
        if (entriesDirty || revision != builtRevision) {
            cachedEntries = buildEntries();
            builtRevision = revision;
            entriesDirty = false;
        }
        return cachedEntries;
    }

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
            BoundContainer parent = BoundContainerClient.find(binding.parentId());
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

        // 每个主容器的规模一次算好：行内标签与「有没有子项」都读这份结果，
        // 不必再为每个可见行遍历整张绑定表（原来每帧 O(n²)）。
        groupCounts.clear();
        for (Map.Entry<UUID, List<BoundContainer>> group : children.entrySet()) {
            int parallel = 1;
            int slave = 0;
            for (BoundContainer child : group.getValue()) {
                if (child.role() == BoundContainer.Role.PARALLEL) {
                    parallel++;
                } else if (child.role() == BoundContainer.Role.SLAVE) {
                    slave++;
                }
            }
            groupCounts.put(group.getKey(), new int[]{parallel, slave});
        }

        List<Entry> entries = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        searching = !needle.isEmpty();
        int shown = 0;

        for (BoundContainer master : masters) {
            // 收起时只列主容器；一搜索就自动展开，否则搜到的并列成员/从容器会因为折叠而「看不见」。
            boolean showChildren = isExpanded(master.id());
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
            // 这里原来还有一条组头（「名字 ×N」+ 规模说明），紧接着的主容器行又把名字写了一遍。
            // 组头已经删掉：名字与 ×N 归主容器行，规模由行上的「并列组 / 从 ×N」标签表达。
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

        List<Entry> entries = entries();
        lastEntries = entries;
        pendingTooltip = null;
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
            BoundUi.scrollbar(graphics, left + panelWidth - 5, listTop, listHeight, contentHeight,
                    scrollOffset, maxScroll);
        }

        int footerY = top + panelHeight - FOOTER_HEIGHT + 3;
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_footer",
                entryCount), left + panelWidth / 2, footerY, BoundUi.TEXT_DIM);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (renameBox != null) {
            renameBox.render(graphics, mouseX, mouseY, partialTick);
        }
        // tooltip 最后画：行内按钮是自绘的，没有原版 Button 的提示，只能自己收集、最后统一画，
        // 否则会被后画的搜索框 / 工具栏按钮盖住。
        if (pendingTooltip != null) {
            BoundUi.tooltip(graphics, mouseX, mouseY, pendingTooltip);
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
        int cursor = x + 4;
        if (row.indent()) {
            graphics.drawString(font, "└", cursor, y + 4, BoundUi.TEXT_DIM, false);
            cursor += 8;
        } else if (!orphan) {
            // 主容器行前面的三角：▼ 展开、▶ 收起。点整行都能翻，这个记号只是让人知道「能点」。
            // 没有子容器的（光杆主容器）不画记号也不给点——画了却点不动，比不画更让人困惑。
            // 位置照留，让各行图标与名字对齐。
            if (hasChildren(binding)) {
                graphics.drawString(font, isExpanded(binding.id()) ? "▼" : "▶", cursor, y + 4,
                        BoundUi.TEXT_DIM, false);
            }
            cursor += 9;
        }
        if (!binding.iconItem().isEmpty()) {
            graphics.renderItem(binding.iconItem(), cursor, y + 3);
        }
        cursor += 20;

        boolean renaming = binding.id().equals(renamingId);
        // 名字文字的起点一并记下来：改名输入框就摆在这儿。图标画在 GUI 的更高深度上（物品渲染带
        // z 偏移），先画的输入框盖不住它，所以只能从图标右边开始，而不是压在它上面。
        rowBounds.put(binding.id(), new int[]{x, y, width, cursor});
        int parallel = parallelCountOf(binding);
        int slave = slaveCountOf(binding);
        // 名字右侧还有角色标签与行内按钮，三方原先互不避让：名字一长就压到标签和按钮上。
        // 先把右边要占的宽度量出来，再把名字裁到剩下的宽度（超出用 … 收尾）。
        int rightEdge = x + width - 4;
        int reserved = actionsWidth(binding) + 6;
        int tagsWidth = BoundUi.tagsWidth(binding, parallel, slave, false) + routeTagWidth(binding);
        int nameWidth;
        if (renaming) {
            nameWidth = font.width(displayName(binding));
        } else {
            int nameMax = Math.max(30, rightEdge - reserved - tagsWidth - 4 - cursor);
            nameWidth = BoundUi.clipText(graphics, Component.literal(displayName(binding)), cursor, y + 4,
                    nameMax, BoundUi.TEXT);
            int tagsX = cursor + nameWidth + 4;
            if (tagsX + tagsWidth <= rightEdge - reserved) {
                int drawn = BoundUi.tags(graphics, tagsX, y + 3, binding, parallel, slave, false);
                // 类别标签只给主容器画：并列成员与从容器跟随父容器，它们身上永远没有类别。
                if (binding.isMaster() && !binding.routeKeys().isEmpty()) {
                    BoundUi.tag(graphics, tagsX + drawn, y + 3, routeTag(binding), BoundUi.TAG_ROUTE);
                }
            }
        }

        int subY = row.indent() ? y + 14 : y + 17;
        int subMax = Math.max(30, rightEdge - (x + 20));
        if (orphan) {
            BoundUi.clipText(graphics, row.reason(), x + 20, subY, subMax, BoundUi.TEXT_DANGER);
        } else {
            Component sub = Component.literal(row.indent()
                    ? roleText(binding) + " → " + parentNameOf(binding)
                    : BoundUi.subText(binding));
            BoundUi.clipText(graphics, sub, x + 20, subY, subMax, BoundUi.TEXT_DIM);
        }

        if (renaming) {
            // 改名时这一行的按钮让位给输入框：输入框能铺满整行，也不会一边改名一边误点「删除」。
            return;
        }
        drawRowButtons(graphics, binding, x + width, y, mouseX, mouseY);
        // 整行可点：展开 / 收起这一组。**必须加在按钮之后**——命中判定按列表顺序取第一个，
        // 按钮先入列才能在点击时优先于整行，否则点「改名」会变成展开。
        if (!row.indent() && !orphan && hasChildren(binding)) {
            hitTargets.add(new HitTarget(x, y, width, height, binding.id(), "toggle"));
        }
    }

    /** 这一组名下有没有子项（并列成员 / 从容器）；没有就没什么可展开的。 */
    private boolean hasChildren(BoundContainer master) {
        int[] counts = groupCounts.get(master.id());
        return counts != null && (counts[0] > 1 || counts[1] > 0);
    }

    /** 名字带 {@code ×N}（N = 主容器 + 并列成员，不含从容器）。组头删掉后，这个后缀归行名。 */
    private String displayName(BoundContainer binding) {
        int parallel = parallelCountOf(binding);
        return parallel > 1 ? binding.name() + " ×" + parallel : binding.name();
    }

    /** 主容器的类别标签文案。 */
    private static Component routeTag(BoundContainer binding) {
        return Component.translatable("text.recipe_sender.manage_route_tag", binding.routeKeys().size());
    }

    /** 类别标签要占的宽度（没有类别时为 0）。 */
    private int routeTagWidth(BoundContainer binding) {
        if (!binding.isMaster() || binding.routeKeys().isEmpty()) {
            return 0;
        }
        return font.width(routeTag(binding)) + 5 + 2;
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
        int[] counts = groupCounts.get(master.id());
        return counts == null ? 1 : counts[0];
    }

    private int slaveCountOf(BoundContainer master) {
        int[] counts = groupCounts.get(master.id());
        return counts == null ? 0 : counts[1];
    }

    /** 一行里全部按钮的总宽度（含按钮间距）。给名字裁剪留位置用。 */
    private int actionsWidth(BoundContainer binding) {
        List<Action> actions = actionsFor(binding);
        int total = 0;
        for (Action action : actions) {
            total += actionWidth(action);
        }
        return total + Math.max(0, actions.size() - 1) * 2;
    }

    /**
     * 单个行内按钮的宽度。
     *
     * <p>删除按钮的宽度按「确认删除」算：二次确认时按钮文案从「删除」变成「确认删除」，
     * 若按各自文案算宽，两次点击之间整排按钮会横向跳一下，玩家第二下很容易点偏。</p>
     */
    private int actionWidth(Action action) {
        int width = BoundUi.buttonWidth(action.label());
        if ("delete".equals(action.action())) {
            width = Math.max(width, BoundUi.buttonWidth(
                    Component.translatable("text.recipe_sender.button_delete_confirm")));
        }
        return width;
    }

    private void drawRowButtons(GuiGraphics graphics, BoundContainer binding, int rightX, int y, int mouseX,
                                int mouseY) {
        List<Action> actions = actionsFor(binding);
        int totalWidth = actionsWidth(binding);
        int buttonY = y + (binding.role() == BoundContainer.Role.MASTER ? 6 : 4);
        int cursor = rightX - totalWidth;
        for (Action action : actions) {
            int buttonWidth = actionWidth(action);
            boolean hovered = BoundUi.inside(mouseX, mouseY, cursor, buttonY, buttonWidth,
                    BoundUi.BUTTON_HEIGHT);
            BoundUi.button(graphics, cursor, buttonY, action.label(), hovered, true, action.danger());
            if (hovered) {
                // 自绘按钮没有原版 Button 的提示，这里收集起来在 render() 末尾统一画。
                pendingTooltip = action.tooltip();
            }
            hitTargets.add(new HitTarget(cursor, buttonY, buttonWidth, BoundUi.BUTTON_HEIGHT, binding.id(),
                    action.action()));
            cursor += buttonWidth + 2;
        }
    }

    private List<Action> actionsFor(BoundContainer binding) {
        if (binding.id().equals(pendingDeleteId)) {
            return List.of(new Action(Component.translatable("text.recipe_sender.button_delete_confirm"),
                    true, "delete", Component.translatable("text.recipe_sender.tooltip_delete_confirm")));
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(Component.translatable("text.recipe_sender.button_rename"), false, "rename",
                Component.translatable("text.recipe_sender.tooltip_rename")));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_relation"), false, "relation",
                Component.translatable("text.recipe_sender.tooltip_relation")));
        // 「类别」只给主容器：并列成员与从容器跟随父容器，勾类别没有意义（方案 §6⑩ 明确不加）。
        if (binding.isMaster()) {
            actions.add(new Action(Component.translatable("text.recipe_sender.button_category"), false,
                    "category", Component.translatable("text.recipe_sender.tooltip_category")));
        }
        actions.add(new Action(Component.translatable("text.recipe_sender.button_highlight"), false, "highlight",
                Component.translatable("text.recipe_sender.tooltip_highlight")));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_delete"), true, "delete",
                Component.translatable("text.recipe_sender.tooltip_delete")));
        return actions;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 失焦即确认：正在改名时点到输入框以外，就当作改完了（不必按回车）。这里只负责摘掉焦点，
        // 提交动作挂在改名框的 onBlur 上；点了别的按钮、别的行、甚至空白处都算。
        BoundUi.blurFocusedIfOutside(this, mouseX, mouseY);
        // 取局部变量：失焦回调（BoundUi.focus 里那一层 false）会把字段置空，别在回调之后再用字段。
        BoundEditBox box = renameBox;
        if (box != null && box.isMouseOver(mouseX, mouseY)) {
            BoundUi.focus(this, box);
            return box.mouseClicked(mouseX, mouseY, button);
        }
        BoundEditBox search = searchBox;
        if (search != null && search.isMouseOver(mouseX, mouseY)) {
            BoundUi.focus(this, search);
            return search.mouseClicked(mouseX, mouseY, button);
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
            case "category" -> openCategories(binding);
            case "toggle" -> toggleExpanded(binding.id());
            case "highlight" -> highlight(binding);
            case "delete" -> requestDelete(binding);
            default -> {
            }
        }
    }

    /** 高亮：关掉界面回到世界，否则红框画在界面后面根本看不见。 */
    private void highlight(BoundContainer binding) {
        BoundHighlightState.show(binding);
        cancelRename();
        if (minecraft != null) {
            minecraft.setScreen(null);
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

    /**
     * 勾选这个主容器负责的配方类别（S6 自动路由）。
     *
     * <p>确定时才发 {@link UpdateBindingRoutesPacket}，而且是**覆盖**语义：选择器本身是多选，
     * 玩家按确定时手上拿的就是最终结果。Esc 退出不发包，本地勾选随之丢弃。
     */
    private void openCategories(BoundContainer binding) {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new CategoryPickerScreen(this, binding.name(), binding.iconItem(),
                binding.routeKeys(),
                routes ->
                        ModNetwork.CHANNEL.sendToServer(new UpdateBindingRoutesPacket(binding.id(), routes))));
    }

    private void beginRename(BoundContainer binding) {
        renamingId = binding.id();
        rebuildRenameBox();
        if (renameBox != null) {
            // 焦点交给改名框；它刚建出来，这里不可能是「已经聚焦」的那种情况。
            BoundUi.focus(this, renameBox);
        }
    }

    private void rebuildRenameBox() {
        // 界面重建（改窗口大小）时文本框要重建，已经敲进去的内容得先捞出来，否则会退回原名。
        String draft = renameBox == null ? null : renameBox.getValue();
        renameBox = null;
        int[] bounds = rowBounds.get(renamingId);
        if (bounds == null) {
            // 行不在可见区域内（被滚动出屏幕）：先放弃这次改名，玩家滚回来再点。
            renamingId = null;
            return;
        }
        // bounds = {行左, 行上, 行宽, 名字文字左}。输入框从名字的位置起画（左边是图标），一直顶到
        // 行的右边缘——改名时这一行的按钮不画，整行都留给输入框。
        int textX = bounds[3];
        int boxWidth = Math.max(60, bounds[0] + bounds[2] - textX - 4);
        renameBox = new BoundEditBox(font, textX, bounds[1] + 1, boxWidth, 16,
                Component.translatable("text.recipe_sender.button_rename"));
        renameBox.onBlur(this::commitRename);
        BoundContainer binding = BoundContainerClient.find(renamingId);
        renameBox.setValue(draft != null ? draft : (binding == null ? "" : binding.name()));
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
     * 一次滚一个「单位」的目标偏移。
     *
     * <p>行高有三种（组头 16、一级行 30、子行 26），固定像素步长会让滚动停在一行中间。
     * 这里按累积高度取落点边界：向下取「第一个大于当前偏移的边界」，向上取「最后一个小于
     * 当前偏移的边界」——无论从哪个位置开始，一次滚动都正好翻过一个单位。
     *
     * <p>组头（「标题行」）不算独立单位：它跟着紧随其后的那一行（主容器行）一起滚过去。
     * 否则一次只滚 16px、下一次滚 30px，看起来忽快忽慢。子行各自算一个单位。
     */
    private int scrollTarget(int direction) {
        List<Integer> bounds = new ArrayList<>(lastEntries.size() + 1);
        bounds.add(0);
        int acc = 0;
        boolean pendingHead = false;
        for (Entry entry : lastEntries) {
            acc += entry.height();
            if (entry instanceof GroupEntry) {
                // 标题行先记着，和下一行合成一个单位，这里不出边界。
                pendingHead = true;
                continue;
            }
            pendingHead = false;
            bounds.add(acc);
        }
        if (pendingHead) {
            // 末尾只剩标题（正常构造下不会出现），给它一个落点，免得滚不到底。
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
        // E 关界面（和原版背包一致），但焦点在输入框里时 E 是普通字符，得留给输入框。
        if (keyCode == GLFW.GLFW_KEY_E && !isTextFocused()) {
            onClose();
            return true;
        }
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
        // 打字即聚焦搜索框：打开界面直接敲字就能搜，不必先用鼠标点一下输入框。
        // （不改成 setInitialFocus 是有意的：搜索框一开场就聚焦，E 关界面会被输入框吃掉。）
        if (!swallowOpeningInput() && BoundUi.shouldTypeToSearch(this, keyCode)) {
            BoundUi.focus(this, searchBox);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 焦点是否落在某个输入框里。E 要留给输入框当普通字符。 */
    private boolean isTextFocused() {
        return (searchBox != null && searchBox.isFocused()) || (renameBox != null && renameBox.isFocused());
    }

    /**
     * 打开界面的那一按（以及它的抬键、连发）不能当成搜索输入。
     *
     * <p>管理界面是用键盘快捷键开的（默认 Ctrl+B）：这一按的字符事件会落到 {@code init()} 刚建出来的
     * 搜索框上，界面一打开搜索框里就自带一个字母。这里在「快捷键还按着」与「刚打开的头
     * {@value #OPEN_INPUT_GRACE_MS} 毫秒」两个窗口里丢掉键盘输入；鼠标点击不受影响，玩家想搜索
     * 仍然点一下输入框就能打字。
     */
    private boolean swallowOpeningInput() {
        if (RecipeSenderClient.isManageKeyHeld()) {
            return true;
        }
        return Util.getMillis() - openedAt < OPEN_INPUT_GRACE_MS;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameBox != null) {
            return renameBox.charTyped(codePoint, modifiers);
        }
        // 静默期放在改名框之后：init() 在改窗口大小时也会跑，不能让静默期吃掉正在改名的输入。
        if (swallowOpeningInput()) {
            return true;
        }
        // 输入法常常只发 charTyped 不发可识别的 keyPressed，这里再补一次聚焦。
        if (searchBox != null && getFocused() != searchBox && !Character.isISOControl(codePoint)) {
            BoundUi.focus(this, searchBox);
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

    private record Action(Component label, boolean danger, String action, Component tooltip) {
    }

    private record HitTarget(int x, int y, int width, int height, UUID bindingId, String action) {
    }
}
