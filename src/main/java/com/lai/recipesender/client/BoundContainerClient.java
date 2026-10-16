package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.MachineState;
import com.lai.recipesender.network.packet.MachineStatusPacket;
import com.lai.recipesender.service.BoundContainerService;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端只读的绑定缓存。
 *
 * <p>唯一的数据来源是服务端的 {@code SyncBoundContainersPacket}；客户端不做本地乐观更新，
 * 所以界面永远显示服务端最后一次确认过的状态。
 *
 * <p>不设绑定数量上限，所以这里用普通 List，不做容量约束。
 */
public final class BoundContainerClient {
    private static volatile List<BoundContainer> bindings = List.of();
    /**
     * 按 id 的索引，与 {@link #bindings} 同一次同步整体替换。
     *
     * <p>1.0.27 之前 {@code find()} / {@code findAt()} 是线性扫描，而它们会被「每帧、每行」调用
     * （管理界面的父容器名、点击派发），绑定一多就是每帧 O(n²)。</p>
     */
    private static volatile Map<UUID, BoundContainer> byId = Map.of();
    /** 主容器列表，同样只在同步时重算一次（管理界面每帧都要它）。 */
    private static volatile List<BoundContainer> masters = List.of();
    /**
     * 物品目标（排除流体中枢），同样只在同步时重算一次。
     *
     * <p>发送单元的解析（{@link #groupOf(UUID)}）走这一份：中枢不参与物品分组，
     * 混进去会让它变成一个「独立的主容器发送单元」。
     */
    private static volatile List<BoundContainer> itemTargets = List.of();
    /**
     * 流体中枢（排除物品目标），同样只在同步时重算一次。
     *
     * <p>服务端保证最多一条，但仍然按列表存：管理界面的「流体中枢」页要列的就是它，
     * 而 {@link #hub()} 只关心「有没有、是哪一条」。
     */
    private static volatile List<BoundContainer> fluidHubs = List.of();
    /**
     * 每个主容器的 {@code {并列成员数(含自己), 从容器数}}，同步时一次算好。
     *
     * <p>从容器数是**整组**的总数：既包括挂在主容器下的组级从容器，也包括挂到各并列成员名下的
     * （独立模式下每台机器可以各挂一只箱子给自己扩容）。标签上的「从 ×N」讲的是整组的能力，
     * 所以按主容器汇总；具体挂在谁名下由管理界面自己的 {@code childCounts} 表达。
     *
     * <p>原先 {@code memberCount()} / {@code slaveCount()} / {@code displayName()} 每次调用都会
     * 走一遍 {@code BoundGroupResolver.resolve()}（O(绑定数)），而弹窗每帧每行都要问一次，
     * 就是每帧 O(行数 × 绑定数)。</p>
     */
    private static volatile Map<UUID, int[]> groupStats = Map.of();
    /** 「上次选择」：路由键的字符串形式 → 发送单元主体（主容器）的 id。 */
    private static volatile Map<String, UUID> lastChoices = Map.of();
    /**
     * 每台绑定机器此刻忙不忙，服务端每秒推一次快照。
     *
     * <p>不并进 {@link #byId} 那一套缓存：忙/闲每秒都在变，而 {@link #revision} 一变界面就要重建
     * 整张行列表（1.0.26 的主要开销）。这份状态单独放，界面在 {@code drawRow} 里实时查，
     * 过期一秒是允许的。
     */
    private static volatile Map<UUID, MachineStatusPacket.Entry> machineStates = Map.of();
    /**
     * 缓存版本号，每次同步 +1。
     *
     * <p>界面用它判断「要不要重建行列表」：管理界面原来每帧都重建一次分组与排序，
     * 现在只在版本号变了（或本地搜索/展开状态变了）时重建。</p>
     */
    private static volatile int revision;

    private BoundContainerClient() {
    }

    /** 用服务端推送的列表与「上次选择」整体替换本地缓存。 */
    public static void acceptSync(List<BoundContainer> synced, Map<String, UUID> choices) {
        List<BoundContainer> list = synced == null ? List.of() : List.copyOf(synced);
        Map<UUID, BoundContainer> index = new HashMap<>(Math.max(16, list.size() * 2));
        List<BoundContainer> targetList = new ArrayList<>(list.size());
        List<BoundContainer> masterList = new ArrayList<>();
        List<BoundContainer> hubList = new ArrayList<>(1);
        for (BoundContainer binding : list) {
            index.put(binding.id(), binding);
            // 流体中枢也是 MASTER 角色，但它不是物品目标：物品通路（直达候选、选择弹窗、
            // 按类别路由）只认物品目标，所以在这里就把它挡在外面，调用方不必各自再判一次。
            if (binding.isFluidHub()) {
                hubList.add(binding);
                continue;
            }
            targetList.add(binding);
            if (binding.isMaster()) {
                masterList.add(binding);
            }
        }
        bindings = list;
        byId = Map.copyOf(index);
        itemTargets = List.copyOf(targetList);
        fluidHubs = List.copyOf(hubList);
        masters = List.copyOf(masterList);
        lastChoices = choices == null ? Map.of() : Map.copyOf(choices);
        groupStats = countGroups(itemTargets, index);
        // 绑定列表整体换过了：旧绑定的忙/闲快照按 id 可能还查得到，留着会画到新行上，一律作废。
        machineStates = Map.of();
        revision++;
    }

    /**
     * 一次遍历算出每个主容器的规模（并列成员含自己、从容器数）。
     *
     * <p>入参必须是物品目标（见 {@link #itemTargets}）：中枢不参与分组，它不该在这张表里有条目，
     * 否则管理界面会把它当成一个可展开的主容器、弹窗也会把它当父容器候选。
     */
    private static Map<UUID, int[]> countGroups(List<BoundContainer> list, Map<UUID, BoundContainer> index) {
        Map<UUID, int[]> stats = new HashMap<>(Math.max(16, list.size() * 2));
        for (BoundContainer binding : list) {
            if (binding.isMaster()) {
                stats.put(binding.id(), new int[]{1, 0});
            }
        }
        for (BoundContainer binding : list) {
            if (binding.isMaster()) {
                continue;
            }
            UUID parentId = binding.parentId();
            if (parentId == null) {
                continue;
            }
            BoundContainer parent = index.get(parentId);
            if (parent == null) {
                continue;
            }
            if (binding.role() == BoundContainer.Role.SLAVE) {
                // 从容器可以挂主容器（组级），也可以挂某个并列成员（那一台私有的扩容，1.0.43）。
                // 两种都算进主容器的整组从容器数：标签上的「从 ×N」讲的是整组的能力。
                UUID masterId = parent.isMaster() ? parent.id() : parent.parentId();
                int[] counts = masterId == null ? null : stats.get(masterId);
                if (counts != null) {
                    counts[1]++;
                }
                continue;
            }
            if (!parent.isMaster()) {
                // 并列成员的父只能是主容器；父是别的角色时这条绑定是孤立项，不该算进任何一组。
                continue;
            }
            int[] counts = stats.get(parentId);
            if (counts != null) {
                counts[0]++;
            }
        }
        return Map.copyOf(stats);
    }

    /** 缓存版本号：同步一次 +1。界面据此判断行列表要不要重建。 */
    public static int revision() {
        return revision;
    }

    /**
     * 当前全部绑定，**含流体中枢**。
     *
     * <p>这是列表展示用的全量访问器（管理界面按用途分页列出来就靠它）。物品通路不要用它：
     * 物品目标的主容器看 {@link #masters()}，流体中枢看 {@link #hub()}。
     */
    public static List<BoundContainer> all() {
        return bindings;
    }

    /**
     * 物品目标里的主容器：自动路由与选择弹窗都只认它们。
     *
     * <p>**不含流体中枢**——中枢虽然也是 {@code MASTER} 角色，但它是流体的来源、不参与物品分摊，
     * 当成发送单元的话材料有可能被塞进储罐。中枢请用 {@link #hub()}。
     */
    public static List<BoundContainer> masters() {
        return masters;
    }

    /**
     * 全部物品目标（含主容器、并列成员、从容器），**不含流体中枢**。
     *
     * <p>管理界面的「容器绑定」页用的就是它：那一页要按主容器分组列全部物品目标，
     * 中枢是另一种用途，只能出现在「流体中枢」页（见 {@link #fluidHubs()}）。
     */
    public static List<BoundContainer> itemTargets() {
        return itemTargets;
    }

    /**
     * 全部流体中枢（正常最多一条，服务端只允许一个）。
     *
     * <p>管理界面的「流体中枢」页用它列行。**不要拿它当物品候选**：中枢不参与物品分摊。
     */
    public static List<BoundContainer> fluidHubs() {
        return fluidHubs;
    }

    /**
     * 流体中枢（直达模式的流体来源）：绑定列表里第一条 {@code FLUID_HUB}，没有绑定时返回 {@code null}。
     *
     * <p>服务端保证最多只有一条（{@code BoundBindingService} 会拒绝第二个中枢），所以不必做多选。
     */
    public static BoundContainer hub() {
        List<BoundContainer> hubs = fluidHubs;
        return hubs.isEmpty() ? null : hubs.get(0);
    }

    /**
     * 按 id 查找，**含流体中枢**。
     *
     * <p>这是「按 id 定位」的通用访问器（行渲染、点击派发、改名都用它），刻意不过滤用途：
     * 过滤掉中枢会让流体中枢页的行点不动。要物品候选请用 {@link #masters()}。
     */
    public static BoundContainer find(UUID id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * 按坐标查找绑定，**含流体中枢**。
     *
     * <p>同一个方块最多只会有一条绑定（服务端 {@code bind} 命中同坐标时是更新而不是新增），
     * 所以拿到第一个匹配项就可以返回。绑定键（按 B）用它判断「这个方块是不是已经绑过了」——
     * 中枢占着的坐标同样算绑过，不能再打开绑定弹窗。
     */
    public static BoundContainer findAt(ResourceLocation dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        for (BoundContainer binding : bindings) {
            if (binding.pos().equals(pos) && binding.dimension().location().equals(dimension)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 某个主容器所在的发送单元。
     *
     * <p>只解析物品目标（{@link #itemTargets}）：流体中枢不参与物品分组，它的 id 传进来一律返回
     * {@code null}，调用方按「独立主容器」处理即可（高亮就是这么用的：只框中枢自己那一个方块）。
     *
     * <p>缓存里可能短暂落后于服务端（比如主容器刚被删掉、成员还没收到新列表），
     * 这时返回 {@code null}，调用方按「独立主容器」处理即可。
     */
    public static BoundGroupResolver.BoundGroup groupOf(UUID masterId) {
        return BoundGroupResolver.groupOf(itemTargets, masterId);
    }

    /** 组内容器数量（含主容器自己、不含从容器）；中枢不在分组里，问它一律是 0。 */
    public static int memberCount(UUID masterId) {
        int[] counts = masterId == null ? null : groupStats.get(masterId);
        return counts == null ? 0 : counts[0];
    }

    /** 挂在某个主容器下的从容器数量。 */
    public static int slaveCount(UUID masterId) {
        int[] counts = masterId == null ? null : groupStats.get(masterId);
        return counts == null ? 0 : counts[1];
    }

    /** 发送单元的显示名：有并列成员时带 {@code ×N} 后缀。 */
    public static String displayName(BoundContainer master) {
        if (master == null) {
            return "";
        }
        int count = memberCount(master.id());
        return count > 1 ? master.name() + " ×" + count : master.name();
    }

    /**
     * 读取某个路由键记下的「上次选择」对应的绑定；没有记录、或记录指向的绑定已被删掉时返回 {@code null}。
     *
     * <p>「记录指向已删除的绑定」理论上不该出现（服务端删除时会连带清理），
     * 但客户端缓存可能短暂落后于服务端，所以这里再兜一层，宁可当作「没有上次」。
     */
    public static BoundContainer lastChoice(ResourceLocation routeKey) {
        if (routeKey == null) {
            return null;
        }
        UUID id = lastChoices.get(routeKey.toString());
        BoundContainer binding = id == null ? null : find(id);
        // 中枢不是物品候选：老数据里若记着它（那条绑定后来被改成了中枢）也当作「没有上次」。
        return binding == null || binding.isFluidHub() ? null : binding;
    }

    /**
     * 用服务端这一份「每台绑定机器忙不忙」整体替换本地快照。
     *
     * <p>是替换不是合并：服务端每次给的是全量，合并会让已经删掉、或者这次没被探到的绑定
     * 一直留着上一次的状态。
     */
    static void acceptMachineStates(List<MachineStatusPacket.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            machineStates = Map.of();
            return;
        }
        Map<UUID, MachineStatusPacket.Entry> states = new HashMap<>(Math.max(16, entries.size() * 2));
        for (MachineStatusPacket.Entry entry : entries) {
            if (entry != null && entry.id() != null && entry.state() != null) {
                states.put(entry.id(), entry);
            }
        }
        machineStates = Map.copyOf(states);
    }

    /**
     * 某台绑定机器此刻的忙/闲；没查到时返回 {@code null}（含 id 为 {@code null}）。
     *
     * <p>{@code null} 表示「不知道」，界面据此不画标签——宁可什么都不显示，
     * 也不能把「还没收到」显示成「空闲」。
     */
    static MachineState machineStateOf(UUID id) {
        MachineStatusPacket.Entry entry = id == null ? null : machineStates.get(id);
        return entry == null ? null : entry.state();
    }

    /**
     * 某台绑定机器此刻的电路编号；查不到、或这台机器压根没有可调整的电路槽时返回 {@code null}。
     *
     * <p>{@code null} 与「电路是 0」必须区分：前者是「这台机器没有电路槽 / 还没收到状态」，
     * 后者是「装了一个 0 号编程电路」。服务端用 {@link MachineStatusPacket#NO_CIRCUIT_SLOT}
     * 表达前者，在这里折算成 {@code null}，调用方就不必认识那个哨兵。
     */
    static Integer machineCircuitOf(UUID id) {
        MachineStatusPacket.Entry entry = id == null ? null : machineStates.get(id);
        if (entry == null || entry.circuit() == MachineStatusPacket.NO_CIRCUIT_SLOT) {
            return null;
        }
        return entry.circuit();
    }

    /**
     * 最后一次收到的机器状态快照里，这个组（主容器 + 并列成员）中有哪些成员处于「运行中」。
     *
     * <p>整体模式的 HUD 预检要用：整组电路必须一致，而只有正在跑的机器才可能「带着旧电路
     * 跑一半」，所以只挑在忙的成员去比对。主容器也在返回值里——服务端的闸门同样把主容器
     * 算作成员。返回空列表表示「没有在忙的成员」或「还没收到状态」。
     */
    static List<UUID> busyMembersOf(BoundContainer master) {
        if (master == null) {
            return List.of();
        }
        BoundGroupResolver.BoundGroup group = groupOf(master.id());
        if (group == null) {
            return List.of();
        }
        List<UUID> busy = new ArrayList<>();
        for (BoundContainer binding : group.members()) {
            if (binding != null && machineStateOf(binding.id()) == MachineState.BUSY) {
                busy.add(binding.id());
            }
        }
        return busy;
    }

    /** 清空缓存：断开连接时调用，避免把上一个世界的绑定带进新世界。 */
    public static void clear() {
        bindings = List.of();
        byId = Map.of();
        itemTargets = List.of();
        fluidHubs = List.of();
        masters = List.of();
        groupStats = Map.of();
        lastChoices = Map.of();
        machineStates = Map.of();
        revision++;
    }

    /** S1 阶段还没有真实路由键，手选结果统一记在这个键下。 */
    public static ResourceLocation manualRoute() {
        return BoundContainerService.MANUAL_ROUTE;
    }
}
