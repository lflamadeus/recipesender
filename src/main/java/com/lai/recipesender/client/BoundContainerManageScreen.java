package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.MachineState;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.AlignGroupCircuitPacket;
import com.lai.recipesender.network.packet.UnbindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * <p>按主容器分组的嵌套列表：主容器是一级行，并列成员与组级从容器缩进挂在它下面；
 * 并列成员名下的从容器（独立模式下给单台机器扩容的箱子）再缩进一级挂在那一台下面。
 * 找不到父容器的项收在最外层的「未归组」小节里（父容器被删掉时会这样），
 * **不连带删除**，玩家可以自己决定删掉还是重新绑定。
 *
 * <p>非模态：{@code isPauseScreen()} 返回 false，世界继续跑。
 * 全部操作都只是发一个网络包，界面显示的是服务端回推的最新状态，客户端不做乐观更新。
 *
 * <p>并列成员与从容器**默认收起**，点有子行的行展开（行首有 ▼ / ▶ 记号），
 * 工具栏的「展开 / 收起」按钮则一次全开或全关；一旦搜索就自动展开，此时点行不再收起。
 * 搜索支持中文首字母与全拼（靠 {@link PinyinSupport}，没装 JEC 时静默降级为字面匹配），
 * 搜索框上点右键清空。
 *
 * <p>E 或 Esc 关界面（焦点在搜索框 / 改名框里时 E 让给输入框）。
 *
 * <p>行内操作：「改名 / 关系 / 类别 / 高亮 / 删除」。其中「类别」只出现在主容器行上，
 * 打开 {@link CategoryPickerScreen} 勾选这个容器负责的配方类别（S6 自动路由）；并列成员与从容器
 * 跟随父容器，不单独勾。
 *
 * <p>分两页签（1.0.33 新增）：
 * <ul>
 *   <li><b>容器绑定</b>（{@link Tab#ITEMS}）：全部**物品目标**，就是上面这套按主容器分组的列表；
 *       流体中枢不在这页里。</li>
 *   <li><b>流体中枢</b>（{@link Tab#FLUIDS}）：**只列流体中枢**（正常最多一条）。中枢是直达功能的
 *       流体来源，固定是主容器角色、没有父容器、没有配方类别，所以它不参与按主容器分组，
 *       每行也只留「改名 / 高亮 / 删除」（没有「关系」与「类别」）。</li>
 * </ul>
 * 两页共用同一份列表渲染、滚动与命中区，只是数据源不同：切页签会作废行缓存并清空命中区
 * （见 {@link #setTab(Tab)}），上一页的按钮不会再被点到。
 */
class BoundContainerManageScreen extends Screen {

    private static final int PANEL_MARGIN = 20;
    private static final int PANEL_MAX_WIDTH = 420;
    private static final int HEADER_HEIGHT = 30;
    private static final int TOOLBAR_HEIGHT = 24;
    /** 页签行的高度（「容器绑定」/「流体中枢」），夹在标题与工具栏之间。 */
    private static final int TAB_HEIGHT = 22;
    /** 两个页签之间的间隙。 */
    private static final int TAB_GAP = 4;
    private static final int FOOTER_HEIGHT = 18;
    private static final int PADDING = 8;
    private static final int ROW_MASTER_HEIGHT = 30;
    private static final int ROW_CHILD_HEIGHT = 26;
    private static final int GROUP_HEAD_HEIGHT = 16;
    private static final int SPACER_HEIGHT = 6;

    /**
     * 行内属性标签的绘制缩放。
     *
     * <p>标签是次要信息，却和名字抢同一条行宽：主容器行上「主容器 / 并列组 / 从 ×N / 类别 ×N」
     * 再加上忙闲标签，量出来能有一百六十多像素，把名字压到只剩两个字加「…」（用户实测反馈）。
     * 整块缩到 0.75 倍画，占位按同一比例折算，名字大约拿回四分之一的标签宽度；
     * 标签本身仍然清楚——原版 9px 的字缩到 6.75px，30px 高的行里放得下。
     */
    private static final float TAG_SCALE = 0.75F;

    /**
     * 名字至少留出的宽度（原字号像素）。
     *
     * <p>原来这里写的是 30：标签一多，名字就只剩两个字加「…」，连是哪个容器都认不出来。
     * 54 ≈ 6 个汉字，够认出名字。真到了这一行也放不下的时候，让位的是**标签**而不是名字：
     * 标签整块不画还有悬停提示兜底，名字被裁成两个字却没有任何补救。
     */
    private static final int NAME_MIN_WIDTH = 54;

    private static final int DELETE_CONFIRM_TICKS = 60;
    private static final int TOOLBAR_BUTTON_WIDTH = 56;
    /** 打开界面后的键盘静默期，见 {@link #swallowOpeningInput()}。 */
    private static final long OPEN_INPUT_GRACE_MS = 250L;
    /** 存活性判定的刷新节拍（20 tick = 1 秒），见 {@link #tick()}。 */
    private static final int LIVENESS_REFRESH_TICKS = 20;

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
     * 当前页签，默认「容器绑定」（物品目标）。
     *
     * <p>换窗口大小 / 重开界面都会重建 Screen 对象，页签回到默认页——管理界面本来就没有跨次打开
     * 记忆状态的先例（搜索词、展开状态也一样）。
     */
    private Tab tab = Tab.ITEMS;

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

    /**
     * 每个容器（主容器或并列成员）名下的 {并列成员数, 从容器数}，在 {@link #buildEntries()} 里一次算好。
     *
     * <p>两个数都是**直接挂在它下面的原始个数**，不含容器自己：并列成员的父必然是主容器，
     * 所以「并列成员数」只有主容器会非 0；从容器可以挂在主容器上（组级从容器），也可以挂在某个
     * 并列成员上（那一台机器私有的库存扩展），两种父都要能查到，所以这里按**实际父容器**归组。
     */
    private final Map<UUID, int[]> childCounts = new HashMap<>();

    /** 本帧悬停中的行内按钮提示，最后统一画（画早了会被后画的控件盖住）。 */
    private Component pendingTooltip;

    /**
     * 每个绑定的本地存活性（见 {@link BoundLiveness}）。
     *
     * <p>1.0.27 新增。破坏方块不会触发服务端同步，所以列表里的数据永远是「上次同步时」的样子；
     * 这张表是界面自己按当前世界问出来的答案，只在打开界面时与之后每秒问一次。
     *
     * <p>只存判定结果、不改任何数据：方块被拆掉之后，这里只是让那一行变红，
     * 绑定本身仍然保留（拆了准备搬走、之后再放回来是常见操作，自动解绑反而更糟）。
     */
    private final Map<UUID, BoundStatus> liveness = new HashMap<>();

    /** {@link #liveness} 的刷新节拍，见 {@link #tick()}。 */
    private int livenessTicks;

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
        // 打开界面即校验一次：拆掉方块不会触发服务端同步，不主动问一次的话那一行看起来完全正常。
        refreshLiveness();
        panelWidth = Math.min(PANEL_MAX_WIDTH, width - PANEL_MARGIN * 2);
        panelHeight = Math.min(height - PANEL_MARGIN, 320);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + HEADER_HEIGHT + TAB_HEIGHT + TOOLBAR_HEIGHT;
        listHeight = panelHeight - HEADER_HEIGHT - TAB_HEIGHT - TOOLBAR_HEIGHT - FOOTER_HEIGHT;

        int toolbarY = top + HEADER_HEIGHT + TAB_HEIGHT + 2;
        // 工具栏从右往左排：关闭 → 展开/收起 → 搜索框吃剩下的宽度。
        int closeX = left + panelWidth - PADDING - TOOLBAR_BUTTON_WIDTH;
        int expandX = closeX - 4 - TOOLBAR_BUTTON_WIDTH;
        // 搜索框吃剩下的宽度。原来这里写着 Math.max(60, ...)，现在把下限换成 20 并让搜索框优先让位：
        // 面板正常宽度（≥ 280）时剩下的宽度本来就有 61 以上，与原来的下限等价；只有玩家把 GUI 缩放
        // 调到 4、窗口又不大时才会真的不够——那时硬撑到 60 会把搜索框顶到展开按钮上，而重叠的控件
        // 点起来是错的（点在重叠处会先被搜索框接走，按钮就永远点不到）。窄一点的输入框总比重叠强。
        int searchWidth = Math.max(20, expandX - 8 - (left + PADDING));
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
        syncToolbar();
        addRenderableWidget(Button.builder(Component.translatable("text.recipe_sender.manage_close"),
                        button -> onClose())
                .bounds(closeX, toolbarY, TOOLBAR_BUTTON_WIDTH, 18).build());

        if (renamingId != null && !BoundContainerClient.all().isEmpty()) {
            rebuildRenameBox();
        }
    }

    /**
     * 让工具栏跟上当前页签。
     *
     * <p>中枢页没有分组可展开，「展开 / 收起」在这一页没有意义：整个按钮藏掉（{@code visible=false}
     * 的控件既画不出来也点不到）。搜索框两页都留着——按名字过滤中枢同样是合理的用法。
     */
    private void syncToolbar() {
        if (expandButton == null) {
            return;
        }
        boolean itemsTab = tab == Tab.ITEMS;
        expandButton.visible = itemsTab;
        expandButton.active = itemsTab;
    }

    /**
     * 工具栏的全局开关：一次全开或全关。
     *
     * <p>展开状态按容器 id 记，而并列成员行自己也能展开（它私有的从容器挂在它下面），
     * 所以「全开」要把有子行的并列成员一并收进来，否则它们的子行仍然看不见。
     */
    private void setAllExpanded(boolean value) {
        expandedMasters.clear();
        if (value) {
            for (BoundContainer binding : BoundContainerClient.itemTargets()) {
                if (hasChildren(binding)) {
                    expandedMasters.add(binding.id());
                }
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

    /**
     * 所有**有子行**的容器都展开时按钮显示「收起」，否则显示「展开」。
     *
     * <p>判据必须与 {@link #setAllExpanded(boolean)} 完全一致（并列成员也可能有子行），
     * 否则点一次「全开」之后按钮还写着「展开」。
     */
    private boolean allExpanded() {
        boolean any = false;
        for (BoundContainer binding : BoundContainerClient.itemTargets()) {
            if (!hasChildren(binding)) {
                continue;
            }
            any = true;
            if (!expandedMasters.contains(binding.id())) {
                return false;
            }
        }
        return any;
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
        // 每秒重问一次存活性：界面是非模态的（世界继续跑），区块可能在界面开着的时候才加载完，
        // 别人也可能在你盯着界面时把容器拆了。判定只是几次方块实体查询，不联网、不改数据。
        if (++livenessTicks >= LIVENESS_REFRESH_TICKS) {
            livenessTicks = 0;
            refreshLiveness();
            // 机器忙/闲只有服务端算得出来（客户端可能没有能力表、也不该去加载区块），
            // 所以蹭同一个 1 秒节流顺手要一次，界面侧只管画服务端回推的快照。
            RecipeSenderClient.requestMachineStatus();
        }
    }

    /** 重算每个绑定的存活性。只在打开界面时与之后每秒各一次，不做每帧查询。 */
    private void refreshLiveness() {
        liveness.clear();
        for (BoundContainer binding : BoundContainerClient.all()) {
            liveness.put(binding.id(), BoundLiveness.of(binding));
        }
    }

    /** 某个绑定的存活性；还没问过时按正常处理。 */
    private BoundStatus livenessOf(BoundContainer binding) {
        return liveness.getOrDefault(binding.id(), BoundStatus.OK);
    }

    /**
     * 页脚用：**当前页签**里有几个绑定现在不工作。
     *
     * <p>判据只有一条：指向的方块已经不存在（{@link BoundStatus#MISSING}）。
     *
     * <p>按页签过滤：页脚说的是「这一页共 N 项、其中 M 项失效」，另一页的失效项不该算进来。
     */
    private int deadCount() {
        int count = 0;
        for (BoundContainer binding : tabSource()) {
            if (livenessOf(binding) == BoundStatus.MISSING) {
                count++;
            }
        }
        return count;
    }

    /** 当前页签的数据源：物品目标页列全部物品目标，中枢页只列中枢。 */
    private List<BoundContainer> tabSource() {
        return tab == Tab.FLUIDS ? BoundContainerClient.fluidHubs() : BoundContainerClient.itemTargets();
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
        // 页签决定数据源：中枢页是平铺的几条（正常只有一条），走 buildHubEntries()。
        if (tab == Tab.FLUIDS) {
            return buildHubEntries();
        }
        // 物品目标页：中枢绝不能进来。它也是 MASTER 角色，混进来会被当成一个「可展开的主容器」，
        // 而它既没有并列成员也没有从容器，只会让分组里多出一个空壳。
        List<BoundContainer> all = BoundContainerClient.itemTargets();
        Map<UUID, List<BoundContainer>> children = new LinkedHashMap<>();
        List<BoundContainer> masters = new ArrayList<>();
        List<BoundContainer> orphans = new ArrayList<>();

        for (BoundContainer binding : all) {
            if (binding.isMaster()) {
                masters.add(binding);
            }
        }
        // 按**实际父容器**归组，不要求父一定是主容器：并列成员的父必然是主容器，而从容器的父既可能
        // 是主容器（组级从容器），也可能是某个并列成员（那一台机器私有的库存扩展）。
        // 父是别的角色（比如从容器挂从容器）时这条绑定在服务端就是孤立项，行列表里也一样：
        // 它既不会出现在任何父行下面，也不该被静默丢掉——归到孤儿组里玩家才能看到并删掉它。
        for (BoundContainer binding : all) {
            if (binding.isMaster()) {
                continue;
            }
            BoundContainer parent = BoundContainerClient.find(binding.parentId());
            if (parent == null || (!parent.isMaster() && parent.role() != BoundContainer.Role.PARALLEL)) {
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

        // 每个容器名下有几个子行一次算好：行内标签、展开记号与「有没有子项」都读这份结果，
        // 不必再为每个可见行遍历整张绑定表（原来每帧 O(n²)）。存的是**原始个数**、不含容器自己，
        // 所以主容器要的「含自己在内的台数」由调用方自己 +1（见 parallelCountOf）。
        childCounts.clear();
        for (Map.Entry<UUID, List<BoundContainer>> group : children.entrySet()) {
            int parallel = 0;
            int slave = 0;
            for (BoundContainer child : group.getValue()) {
                if (child.role() == BoundContainer.Role.PARALLEL) {
                    parallel++;
                } else if (child.role() == BoundContainer.Role.SLAVE) {
                    slave++;
                }
            }
            childCounts.put(group.getKey(), new int[]{parallel, slave});
        }

        List<Entry> entries = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        searching = !needle.isEmpty();
        int shown = 0;

        for (BoundContainer master : masters) {
            // 收起时只列主容器；一搜索就自动展开，否则搜到的并列成员/从容器会因为折叠而「看不见」。
            boolean showChildren = isExpanded(master.id());
            boolean masterHit = !searching || matches(master, needle);
            List<BoundContainer> group = children.getOrDefault(master.id(), List.of());
            // 先把这一组里**可见**的两级子行挑出来。可见性只看搜索词、与展开状态无关：收起的行也要
            // 计入页脚（见下面的计数），所以「哪几行算数」与「画不画」必须分开算。
            List<BoundContainer> visibleChildren = new ArrayList<>(group.size());
            List<List<BoundContainer>> visibleGrand = new ArrayList<>(group.size());
            for (BoundContainer child : group) {
                boolean childHit = !searching || matches(child, needle);
                // depth 2 只可能挂在并列成员下（从容器不能再有子容器），别的角色一律没有下一级。
                List<BoundContainer> grandGroup = child.role() == BoundContainer.Role.PARALLEL
                        ? children.getOrDefault(child.id(), List.of()) : List.of();
                List<BoundContainer> grandRows = new ArrayList<>(grandGroup.size());
                for (BoundContainer grand : grandGroup) {
                    // 主容器命中时整组都可见；否则看它挂的那台（depth 1）或它自己有没有命中。
                    if (masterHit || childHit || matches(grand, needle)) {
                        grandRows.add(grand);
                    }
                }
                if (masterHit || childHit || !grandRows.isEmpty()) {
                    visibleChildren.add(child);
                    visibleGrand.add(grandRows);
                }
            }
            if (!masterHit && visibleChildren.isEmpty()) {
                continue;
            }
            // 这里原来还有一条组头（「名字 ×N」+ 规模说明），紧接着的主容器行又把名字写了一遍。
            // 组头已经删掉：名字与 ×N 归主容器行，规模由行上的「并列组 / 从 ×N」标签表达。
            entries.add(new RowEntry(master, 0, false, null));
            shown++;
            for (int i = 0; i < visibleChildren.size(); i++) {
                BoundContainer child = visibleChildren.get(i);
                // 收起时也要计数：页脚的「共 N 项」不该随展开状态跳来跳去。
                shown++;
                if (showChildren) {
                    entries.add(new RowEntry(child, 1, false, null));
                }
                List<BoundContainer> grandRows = visibleGrand.get(i);
                // depth 2 与 depth 1 同一口径：收起也计、不画。
                shown += grandRows.size();
                // 某一台并列成员展开才画它私有的从容器；搜索时 isExpanded 恒为 true，直接复用。
                if (showChildren && isExpanded(child.id())) {
                    for (BoundContainer grand : grandRows) {
                        entries.add(new RowEntry(grand, 2, false, null));
                    }
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
                entries.add(new RowEntry(orphan, 0, true, orphanReason(orphan)));
                shown++;
            }
        }
        entryCount = shown;
        return entries;
    }

    /**
     * 流体中枢页的行列表：平铺，不分组、不缩进、没有子项。
     *
     * <p>中枢固定是主容器角色、无父容器、无类别，所以这里不建 {@code childCounts}：
     * 行上的「×N」与展开记号都会自然落空（{@link #parallelCountOf} 查不到就是 1、
     * {@link #hasChildren} 查不到就是 false），不必为中枢另写一套行渲染。
     *
     * <p>搜索照常生效：这一页通常只有一行，但按名字过滤的行为两页保持一致。
     */
    private List<Entry> buildHubEntries() {
        childCounts.clear();
        List<Entry> entries = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        searching = !needle.isEmpty();
        int shown = 0;
        for (BoundContainer hub : BoundContainerClient.fluidHubs()) {
            if (searching && !matches(hub, needle)) {
                continue;
            }
            entries.add(new RowEntry(hub, 0, false, null));
            shown++;
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
        // 页签在标题下、工具栏上。和行内按钮一样是自绘的，命中区随绘制一起登记（render 开头已清空）。
        drawTabs(graphics, mouseX, mouseY);

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
        // 失效条数写进页脚：列表能滚，滚到屏幕外的失效项也该被看见。
        int dead = deadCount();
        Component footer = dead > 0
                ? Component.translatable("text.recipe_sender.manage_footer_dead", entryCount, dead)
                : Component.translatable("text.recipe_sender.manage_footer", entryCount);
        BoundUi.centered(graphics, footer, left + panelWidth / 2, footerY,
                dead > 0 ? BoundUi.TEXT_DANGER : BoundUi.TEXT_DIM);

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
        if (tab == Tab.FLUIDS) {
            // 中枢页的空状态只要一句话：这一页是干什么的、怎么加（键由语言文件提供）。
            BoundUi.centered(graphics, Component.translatable("text.recipe_sender.fluid_hub_empty"),
                    centerX, listTop + listHeight / 2 - 4, BoundUi.TEXT_DIM);
            return;
        }
        int y = listTop + listHeight / 2 - 16;
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty"),
                centerX, y, BoundUi.TEXT);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty_hint"),
                centerX, y + 14, BoundUi.TEXT_DIM);
        BoundUi.centered(graphics, Component.translatable("text.recipe_sender.manage_empty_note"),
                centerX, y + 28, BoundUi.TEXT_DIM);
    }

    /**
     * 画两个页签并登记命中区。
     *
     * <p>两页等宽平分面板内侧宽度，样式沿用本模组的面板配色：选中用 {@code ROW_SELECTED} 底 +
     * 正常字色，未选中是面板底色 + 暗字色，悬停提亮一档。不另画选中记号（下划线之类），
     * 底色本身就够区分，也和行内的选中样式一致。
     */
    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int innerWidth = panelWidth - PADDING * 2;
        int tabWidth = (innerWidth - TAB_GAP) / 2;
        int y = top + HEADER_HEIGHT + 2;
        int height = TAB_HEIGHT - 4;
        drawTab(graphics, mouseX, mouseY, left + PADDING, y, tabWidth, height, Tab.ITEMS);
        drawTab(graphics, mouseX, mouseY, left + PADDING + tabWidth + TAB_GAP, y, tabWidth, height,
                Tab.FLUIDS);
    }

    private void drawTab(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width, int height,
                         Tab candidate) {
        boolean selected = tab == candidate;
        boolean hovered = BoundUi.inside(mouseX, mouseY, x, y, width, height);
        int background = selected ? BoundUi.ROW_SELECTED
                : (hovered ? BoundUi.ROW_HOVER : BoundUi.PANEL);
        graphics.fill(x, y, x + width, y + height, background);
        graphics.renderOutline(x, y, width, height, BoundUi.BORDER_DARK);
        Component label = candidate.label();
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - 8) / 2,
                selected ? BoundUi.TEXT : BoundUi.TEXT_DIM, false);
        // 命中区在绘制里登记（render 开头已清空）：切页签时 {@link #setTab} 会再整体清一次，
        // 保证本帧剩下的时间里点不到上一页的按钮。
        hitTargets.add(new HitTarget(x, y, width, height, null, "tab:" + candidate.name()));
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
        // 缩进按层级走：depth 1 的并列成员缩 16、组级从容器再深一档（28），depth 2 的成员私有
        // 从容器在 depth 1 的基础上每级再让 16。两类角色在发送时的地位不同（并列成员参与均分、
        // 从容器只收溢出），缩进量拉开才能一眼扫出层级。
        int indent = row.depth() == 0 ? 0
                : (binding.role() == BoundContainer.Role.SLAVE ? 28 : 16) + (row.depth() - 1) * 16;
        int x = left + PADDING + indent;
        int width = panelWidth - PADDING * 2 - indent;
        boolean orphan = row.orphan();
        BoundStatus status = livenessOf(binding);
        // 忙/闲只有服务端算得出来，每秒推一次快照，允许过期。像 livenessOf 一样在这里实时查，
        // 不并进 entries() 的行缓存：那个缓存按 revision() 失效，每秒重建整表是 1.0.26 的主要开销。
        MachineState state = BoundContainerClient.machineStateOf(binding.id());

        // 目标已经不在了的行整行铺一层红底：标签行可能因为名字太长而整体不画，
        // 底色是「这一行有问题」最不容易被漏看的表达。
        if (orphan || status == BoundStatus.MISSING) {
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
        }
        // 三角记号：▼ 展开、▶ 收起。并列成员行自己也可能带子行（它私有的从容器），所以缩进行
        // 同样要画。没有子容器的（光杆主容器、没有私有从容器的并列成员）不画记号也不给点——
        // 画了却点不动，比不画更让人困惑。图标位照留，让各行图标与名字对齐。
        if (!orphan && hasChildren(binding)) {
            graphics.drawString(font, isExpanded(binding.id()) ? "▼" : "▶", cursor, y + 4,
                    BoundUi.TEXT_DIM, false);
        }
        if (!orphan) {
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
        int aliveWidth = BoundUi.aliveTagWidth(status);
        int machineWidth = BoundUi.machineTagWidth(state);
        // 标签块先按原字号量（BoundUi 那几个 Width 方法只测量、不绘制），再整体折到 TAG_SCALE：
        // 原字号的 tagsRawWidth 给块内绘制用，折算后的 tagsWidth 给名字让位用，两边对得上才不重叠。
        int tagsRawWidth = aliveWidth + BoundUi.tagsWidth(binding, parallel, slave, false)
                + machineWidth + routeTagWidth(binding);
        int tagsWidth = scaleTagWidth(tagsRawWidth);
        // 这一帧名字有没有被裁、标签有没有整块没画出来：两种情况都要靠悬停提示兜底（见 rowTip）。
        boolean nameClipped = false;
        boolean tagsDropped = false;
        // 改名时整行让给输入框：不画名字也不画标签，宽度自然不必算。
        if (!renaming) {
            String name = displayName(binding);
            // 名字列 = 行宽 − 标签列 − 间距 − 图标 / 按钮等固定占位。下限原来是 30（两个字加「…」），
            // 现在是 NAME_MIN_WIDTH；真放不下时让位的是标签——它整块不画还有 tooltip 兜底。
            int nameMax = Math.max(NAME_MIN_WIDTH, rightEdge - reserved - tagsWidth - 4 - cursor);
            nameClipped = font.width(name) > nameMax;
            int nameWidth = BoundUi.clipText(graphics, Component.literal(name), cursor, y + 4,
                    nameMax, BoundUi.TEXT);
            int tagsX = cursor + nameWidth + 4;
            // 标签块整体平移 + 缩放后再画：块内坐标就是「缩小后的像素」，BoundUi 那套
            // 「画一个、返回一个宽度」的累加逻辑原样能用，不必为缩放另写一份绘制。
            // 可用的块内宽度得先把绝对像素折回去（tagBudget），否则缩放后右边界会算错。
            int tagBudget = (int) Math.floor((rightEdge - reserved - tagsX) / TAG_SCALE);
            graphics.pose().pushPose();
            graphics.pose().translate(tagsX, y + 4, 0.0F);
            graphics.pose().scale(TAG_SCALE, TAG_SCALE, 1.0F);
            int drawn = 0;
            if (tagsRawWidth > 0) {
                // 存活性标签优先：一行太窄时先放弃角色标签，也不能放弃它——
                // 「这个绑定还能不能用」比「它是什么角色」重要得多。
                if (aliveWidth > 0 && aliveWidth <= tagBudget) {
                    drawn += BoundUi.aliveTag(graphics, 0, 0, status);
                }
                if (drawn + (tagsRawWidth - aliveWidth) <= tagBudget) {
                    drawn += BoundUi.tags(graphics, drawn, 0, binding, parallel, slave, false);
                    // 机器忙/闲排在角色标签之后、类别之前：它是「现在能不能投料」，
                    // 比「它是什么角色」更贴近玩家这一刻的决定。
                    drawn += BoundUi.machineTag(graphics, drawn, 0, state);
                    // 类别标签只给主容器画：并列成员与从容器跟随父容器，它们身上永远没有类别。
                    if (binding.isMaster() && !binding.routeKeys().isEmpty()) {
                        drawn += BoundUi.tag(graphics, drawn, 0, routeTag(binding), BoundUi.TAG_ROUTE);
                    }
                } else {
                    // 连角色标签都放不下：这一行的属性得在悬停提示里补全。
                    tagsDropped = true;
                }
            }
            graphics.pose().popPose();
        }

        // 悬停整行都给说明，而不是只有那个十来像素高的标签条：红底一定会画出来，标签却可能因为
        // 一行太窄而整块没画，光有红底不给解释最让人摸不着头脑（用户实测反馈）。
        // 鼠标停在行内按钮上时按钮的提示会覆盖它——drawRowButtons 在本方法末尾才调用。
        if (BoundUi.inside(mouseX, mouseY, x, y, width, height)) {
            Component reason = null;
            if (aliveWidth > 0) {
                // 方块都不在了就先解释那个：它是「这条绑定还能不能用」里更硬的一档。
                reason = BoundUi.aliveTip(status);
            }
            // 名字被裁掉、或者标签整块没画出来时，把完整内容补进提示：行内那点宽度只够
            // 「两个字 + …」，悬停是唯一能把这一行看全的地方。两者都没发生时就保持原来的
            // 单一原因提示，不给每一行都加一个多余的气泡。
            pendingTooltip = nameClipped || tagsDropped
                    ? rowTip(binding, nameClipped, tagsDropped, parallel, slave, status, state, reason)
                    : reason;
        }

        int subY = row.indent() ? y + 14 : y + 17;
        int subMax = Math.max(30, rightEdge - (x + 20));
        if (orphan) {
            BoundUi.clipText(graphics, row.reason(), x + 20, subY, subMax, BoundUi.TEXT_DANGER);
        } else {
            Component sub = Component.literal(row.indent()
                    ? roleText(binding) + " → " + parentNameOf(binding)
                    : BoundUi.subText(binding));
            int subWidth = BoundUi.clipText(graphics, sub, x + 20, subY, subMax, BoundUi.TEXT_DIM);
            // 副标题后面再跟一句存活性说明：这一行没有按钮抢位置，一定画得出来，
            // 于是「红底 + 为什么红」永远同时出现。
            if (aliveWidth > 0) {
                BoundUi.aliveText(graphics, x + 20 + subWidth + 4, subY, subMax - subWidth - 4, status);
            }
        }

        if (renaming) {
            // 改名时这一行的按钮让位给输入框：输入框能铺满整行，也不会一边改名一边误点「删除」。
            return;
        }
        drawRowButtons(graphics, binding, x + width, y, mouseX, mouseY);
        // 整行可点：展开 / 收起这一行名下的子行。**必须加在按钮之后**——命中判定按列表顺序取第一个，
        // 按钮先入列才能在点击时优先于整行，否则点「改名」会变成展开。
        if (!orphan && hasChildren(binding)) {
            hitTargets.add(new HitTarget(x, y, width, height, binding.id(), "toggle"));
        }
    }

    /**
     * 这一行名下有没有子行（并列成员 / 从容器）；没有就没什么可展开的。
     *
     * <p>比的是**原始个数**：从容器可以挂在并列成员下，所以子行数只有 1 也是真的能展开，
     * 不能像主容器那样拿「含自己在内的台数 &gt; 1」当判据。
     */
    private boolean hasChildren(BoundContainer binding) {
        int[] counts = childCounts.get(binding.id());
        return counts != null && (counts[0] > 0 || counts[1] > 0);
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

    /**
     * 标签块的占位宽度：把 {@link BoundUi} 量出来的原字号宽度折到 {@link #TAG_SCALE}。
     *
     * <p>取上整而不是四舍五入：缩放后的实际占位是小数，少算半像素就会让标签压到名字上。
     */
    private static int scaleTagWidth(int rawWidth) {
        return (int) Math.ceil(rawWidth * TAG_SCALE);
    }

    /**
     * 一行的悬停提示：完整名字 + 失效原因 + 全部属性标签，各占一行。
     *
     * <p>名字被行宽裁掉之后，界面上看不到完整名字，也没有别的地方能查；属性标签同理——
     * 一行太窄时整块不画。悬停提示是唯一能把「这一行到底是什么、现在还能不能用」说全的地方。
     * 行内画得下、名字也没被裁的行不调用它（见 {@link #drawRow}），免得每行都弹一个多余的气泡。
     *
     * @param nameClipped 名字是否被裁过；没裁过就不重复写一遍名字
     * @param tagsDropped 属性标签是否没能整块画出来
     * @param reason      已经算好的失效原因，没有就传 {@code null}
     * @return 拼好的多行提示；一行内容都没有时返回 {@code null}
     */
    private Component rowTip(BoundContainer binding, boolean nameClipped, boolean tagsDropped,
                             int parallel, int slave, BoundStatus status, MachineState state,
                             Component reason) {
        List<Component> lines = new ArrayList<>(3);
        if (nameClipped) {
            lines.add(Component.literal(displayName(binding)));
        }
        if (reason != null) {
            lines.add(reason);
        }
        if (nameClipped || tagsDropped) {
            Component summary = tagSummary(binding, parallel, slave, status, state);
            if (summary != null) {
                lines.add(summary);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        // 原版 tooltip 自己按 \n 断行：拼成一个多行组件就行，不必自己算行高、画多行。
        MutableComponent tip = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                tip.append("\n");
            }
            tip.append(lines.get(i));
        }
        return tip;
    }

    /**
     * 一行的属性标签文案，用「 · 」串成一行。
     *
     * <p>与行内画的标签是同一批、同一顺序（{@link BoundUi#tags}，再加上 {@link #drawRow} 里
     * 后补的忙闲 / 类别）：行内标签会因为一行太窄而整块不画，悬停提示里必须能看全。
     * 文案全部取自既有语言键，界面里不出现硬编码中文。
     *
     * @return 没有属性时返回 {@code null}
     */
    private Component tagSummary(BoundContainer binding, int parallel, int slave, BoundStatus status,
                                 MachineState state) {
        List<Component> tags = new ArrayList<>();
        Component alive = BoundUi.aliveLabel(status);
        if (alive != null) {
            tags.add(alive);
        }
        if (binding.isMaster()) {
            tags.add(Component.translatable("text.recipe_sender.tag_master"));
            // 顺序与 BoundUi.tags 保持一致（主容器 → 整体 → 独立）：这一行只有在标签整块被
            // 挤出、玩家只能靠悬停看属性时才会读到，两处的顺序必须一样，否则同一组容器的
            // 属性在「画出来的」和「悬停看的」两种呈现里对不上。
            if (binding.isUnified()) {
                tags.add(Component.translatable("text.recipe_sender.tag_unified"));
            }
            if (parallel <= 1) {
                tags.add(Component.translatable("text.recipe_sender.tag_independent"));
            }
        }
        if (parallel > 1) {
            tags.add(Component.translatable("text.recipe_sender.tag_parallel"));
        }
        if (slave > 0) {
            tags.add(Component.translatable("text.recipe_sender.tag_slave", slave));
        }
        Component machine = BoundUi.machineLabel(state);
        if (machine != null) {
            tags.add(machine);
        }
        if (binding.isMaster() && !binding.routeKeys().isEmpty()) {
            tags.add(routeTag(binding));
        }
        if (tags.isEmpty()) {
            return null;
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                text.append(" · ");
            }
            text.append(tags.get(i));
        }
        return text;
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

    /**
     * 含自己在内的机器台数，喂给 {@link BoundUi#tags} 的 {@code parallelCount}。
     *
     * <p>那个参数的口径是「这一组一共几台机器」（{@code <= 1} 画「独立」、{@code > 1} 画「并列组」），
     * 所以主容器要拿「并列成员数 + 1」；非主容器自己就是一台、恒为 1——挂在它名下的从容器是它
     * 私有的库存，不改变它是「这一组里的一员」这件事。
     */
    private int parallelCountOf(BoundContainer binding) {
        if (!binding.isMaster()) {
            return 1;
        }
        int[] counts = childCounts.get(binding.id());
        return counts == null ? 1 : counts[0] + 1;
    }

    /** 直接挂在这个容器下面的从容器数（只算一层，不含并列成员名下的）。 */
    private int slaveCountOf(BoundContainer binding) {
        int[] counts = childCounts.get(binding.id());
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

    /**
     * 一行上要有哪几个按钮。
     *
     * <p>流体中枢只留「改名 / 高亮 / 删除」：它是另一种**用途**而不是一种角色，没有父子关系，
     * 也没有配方类别（服务端绑定中枢时就强制主容器 + 无父容器 + 无类别），所以「关系」与「类别」
     * 对它一律不出现——摆了也点不出结果，只会让人以为有东西没生效。
     *
     * <p>1.0.38：「一键对齐电路」只加在整体模式的主容器行上，位置排在「类别」之后、「高亮」之前
     * （见 {@link #alignCircuitAvailable}）。
     */
    private List<Action> actionsFor(BoundContainer binding) {
        if (binding.id().equals(pendingDeleteId)) {
            return List.of(new Action(Component.translatable("text.recipe_sender.button_delete_confirm"),
                    true, "delete", Component.translatable("text.recipe_sender.tooltip_delete_confirm")));
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(Component.translatable("text.recipe_sender.button_rename"), false, "rename",
                Component.translatable("text.recipe_sender.tooltip_rename")));
        if (!binding.isFluidHub()) {
            actions.add(new Action(Component.translatable("text.recipe_sender.button_relation"), false,
                    "relation", Component.translatable("text.recipe_sender.tooltip_relation")));
            // 「类别」只给主容器：并列成员与从容器跟随父容器，勾类别没有意义（方案 §6⑩ 明确不加）。
            if (binding.isMaster()) {
                actions.add(new Action(Component.translatable("text.recipe_sender.button_category"), false,
                        "category", Component.translatable("text.recipe_sender.tooltip_category")));
                if (alignCircuitAvailable(binding)) {
                    actions.add(new Action(
                            Component.translatable("text.recipe_sender.button_align_circuit"), false,
                            "align_circuit",
                            Component.translatable("text.recipe_sender.tooltip_align_circuit")));
                }
            }
        }
        actions.add(new Action(Component.translatable("text.recipe_sender.button_highlight"), false, "highlight",
                Component.translatable("text.recipe_sender.tooltip_highlight")));
        actions.add(new Action(Component.translatable("text.recipe_sender.button_delete"), true, "delete",
                Component.translatable("text.recipe_sender.tooltip_delete")));
        return actions;
    }

    /**
     * 「一键对齐电路」该不该出现在这一行上（1.0.38）。
     *
     * <p>两个条件缺一不可：
     * <ul>
     *   <li>整体模式（{@link BoundContainer#isUnified()}）：独立模式的语义就是「各机器电路可以不同」，
     *       把整组强行写成同一个值等于替玩家改了玩法；</li>
     *   <li>这一组至少两台机器（{@code memberCount() >= 2}）：组里只有主容器自己时「对齐整组」
     *       没有任何可对齐的对象，点了也只是空转。</li>
     * </ul>
     *
     * <p>刻意**不看**主容器此刻有没有电路：那是每秒刷新的快照，拿它决定按钮出不出现会让按钮
     * 忽隐忽现（玩家正好在它消失的那一帧点下去反而什么都发生不了）。按钮稳定地在那儿，
     * 点下去再按情况提示，见 {@link #alignGroupCircuit}。
     */
    private boolean alignCircuitAvailable(BoundContainer binding) {
        return binding.isUnified() && BoundContainerClient.memberCount(binding.id()) >= 2;
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 切页签。
     *
     * <p>两页共用同一份行渲染、滚动偏移与命中区，所以切页签要当成「换了一整份列表」来处理：
     * <ul>
     *   <li>{@code entriesDirty}：行缓存作废，下一帧按新页签重建（见 {@link #entries()}）。</li>
     *   <li>滚动归零、行位置表 {@code rowBounds} 清空、{@code lastEntries} 清空（滚轮按行边界翻，
     *       拿上一页的行高会翻错）。</li>
     *   <li><b>命中区 {@code hitTargets} 清空</b>：它是每帧在 {@code render()} 里重建的，但重建发生在
     *       下一帧；切页签这一下就在 {@code mouseClicked} 里，同一帧内还可能有后续点击（连点、拖动）
     *       与滚动，残留的矩形会让上一页的「删除 / 类别」等按钮仍然可点。清掉之后本帧只剩页签可点。</li>
     *   <li>改名与「确认删除」取消：它们都挂在具体某一行上，而那一行已经不在当前页。</li>
     * </ul>
     */
    private void setTab(Tab newTab) {
        if (tab == newTab) {
            return;
        }
        tab = newTab;
        entriesDirty = true;
        scrollOffset = 0;
        hitTargets.clear();
        rowBounds.clear();
        lastEntries = List.of();
        contentHeight = 0;
        cancelRename();
        pendingDeleteId = null;
        syncToolbar();
    }

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
        // 页签不是「某一行上的按钮」：先认它，再去查绑定。绑定 id 为 null 的命中项只可能是页签。
        if (target.action().startsWith("tab:")) {
            setTab(Tab.valueOf(target.action().substring("tab:".length())));
            return;
        }
        BoundContainer binding = BoundContainerClient.find(target.bindingId());
        if (binding == null) {
            return;
        }
        switch (target.action()) {
            case "rename" -> beginRename(binding);
            case "relation" -> openRelation(binding);
            case "category" -> openCategories(binding);
            case "align_circuit" -> alignGroupCircuit(binding);
            case "toggle" -> toggleExpanded(binding.id());
            case "highlight" -> highlight(binding);
            case "delete" -> requestDelete(binding);
            default -> {
            }
        }
    }

    /**
     * 一键对齐电路：把整组的电路写成主容器此刻的电路（1.0.38）。
     *
     * <p>参照值取 {@link BoundContainerClient#machineCircuitOf}，也就是每秒刷新的那份机器状态快照
     * ——本界面开着时 {@code tick()} 一直在请求刷新（见 {@link #tick()}），所以这里读到的是最近
     * 一秒内的状态。慢一拍最坏的结果是「对齐到一秒前主容器的电路」，而玩家刚改完主容器电路就又点
     * 对齐，可接受；不刷新状态才是问题，那会让参照值变成「不知道」。
     *
     * <p><b>这个方法的重点全在 {@code machineCircuitOf} 的返回值形态上</b>（它给的是原始编号，
     * 不是「能直接发出去的基准」）：
     * <ul>
     *   <li>{@code null} —— 主容器没有可调整的电路槽（普通箱子、非格雷机器），或者状态还没收到。
     *       {@code NO_CIRCUIT_SLOT} 这个哨兵已经在 {@code machineCircuitOf} 里折成了 {@code null}。
     *       **不发包**，只提示：服务端要的是一个可信的参照，客户端猜一个数字发过去会把整组写错。</li>
     *   <li>{@code -1} —— 槽里放着<b>玩家自己的物品</b>（不是编程电路，见
     *       {@code GtCircuitSupport#NO_CIRCUIT}），也就是「读不出来」。同样**不发包**：把它当基准
     *       发出去，服务端会理解成「把整组电路清空」，等于替玩家删掉一整组机器的电路。</li>
     *   <li>{@code 0} —— 槽是<b>空的</b>（GT 把空槽当电路 0）。这时基准应该是「整组都没有电路」，
     *       所以发 {@code -1}（清空整组，与主容器当前状态一致），**不是**发 0：发 0 会给每台机器的
     *       槽里塞一个 0 号编程电路物品。</li>
     *   <li>{@code 1..32} —— 主容器装着该编号的电路，照发。</li>
     * </ul>
     *
     * <p>「{@code -1} 是清空、{@code 0} 是空槽」是 {@link AlignGroupCircuitPacket} 与
     * {@link com.lai.recipesender.integration.gt.GtCircuitSupport} 之间既有的坑，服务端也是这么
     * 理解的（{@code BoundBindingService} 里 {@code circuit < 0} 走「已清空」的提示）。
     */
    private void alignGroupCircuit(BoundContainer master) {
        Integer reference = BoundContainerClient.machineCircuitOf(master.id());
        if (reference == null || reference < 0) {
            // 本地提示：这不是服务端拒绝，而是客户端压根没发包（见上），所以不经由服务端回话。
            RecipeSenderClient.notifyPlayer(
                    Component.translatable("text.recipe_sender.align_circuit_no_reference"));
            return;
        }
        // 空槽折算成「清空整组」：0 是 GT 给空槽的编号，直接发 0 会往每台机器里塞一个电路 0 物品。
        int circuit = reference == 0 ? -1 : reference;
        ModNetwork.CHANNEL.sendToServer(new AlignGroupCircuitPacket(master.id(), circuit));
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

    private record RowEntry(BoundContainer binding, int depth, boolean orphan, Component reason)
            implements Entry {
        /** 是不是缩进过的子行。保留这个名字，行渲染与滚动里已有大量 {@code row.indent()} 调用。 */
        boolean indent() {
            return depth > 0;
        }

        @Override
        public int height() {
            return depth == 0 ? ROW_MASTER_HEIGHT : ROW_CHILD_HEIGHT;
        }
    }

    /**
     * 管理界面的两个页签。
     *
     * <p>页签直接对应绑定的**用途**（{@code BoundContainer.Kind}），所以没有另建数据结构：
     * 分叉只在「数据源」与「每行有哪几个按钮」两处，行的渲染、滚动、搜索、改名两页共用。
     */
    private enum Tab {
        /** 容器绑定：全部物品目标。 */
        ITEMS("text.recipe_sender.manage_tab_items"),
        /** 流体中枢：只列流体中枢。 */
        FLUIDS("text.recipe_sender.manage_tab_fluids");

        private final String labelKey;

        Tab(String labelKey) {
            this.labelKey = labelKey;
        }

        /** 页签文案。走语言键，界面里不出现硬编码中文。 */
        Component label() {
            return Component.translatable(labelKey);
        }
    }

    private record Action(Component label, boolean danger, String action, Component tooltip) {
    }

    private record HitTarget(int x, int y, int width, int height, UUID bindingId, String action) {
    }
}
