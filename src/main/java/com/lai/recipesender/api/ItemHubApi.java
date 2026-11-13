package com.lai.recipesender.api;

import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.service.ItemHubService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品中枢对外标准 API。
 *
 * <p>供第三方模组（如蓝图打印模组、自动化建筑、外部物流系统等）安全、便捷地与 Recipe Sender 的
 * 「物品中枢（Item Hub）」进行交互，实现对玩家绑定中枢的<b>材料查询（Query）、抽取（Extract）与放回（Insert）</b>。
 *
 * <p><b>特性支持：</b>
 * <ul>
 *   <li>自动穿透支持 <b>AE2 ME 网络 / ME 接口</b> 以及 <b>GTO（GregTech Odyssey）物品保险库</b> 等特殊存储结构；</li>
 *   <li>自动处理多中枢聚合、跨维度（需开启配置项并保持区块活跃）；</li>
 *   <li>完全在服务端主线程运行，内置严格的空安全防护，不会因调用方参数异常而抛出崩溃。</li>
 * </ul>
 *
 * <p><b>匹配口径：</b>所有带 {@link ItemMatchMode} 参数的重载都按调用方指定的口径比较物品；
 * 不带该参数的历史重载等价于 {@link ItemMatchMode#EXACT}，可放心沿用。
 */
public final class ItemHubApi {

    /**
     * 对外 API 版本号：0 = 仅严格匹配（1.0.62 及更早），1 = 支持 {@link ItemMatchMode} 与 {@link #findMatches}，
     * 2 = 增加 {@link #pullToInventory}（复用 Recipe Sender 自身的取回链路），
     * 3 = 增加 {@link #diagnose}（中枢供料能力只读诊断）。
     *
     * <p>请用 {@link #apiVersion()} 探测中枢是否支持匹配模式，不要直接引用本常量：
     * {@code static final int} 会被 javac 内联进调用方字节码，编译期看不到旧中枢的真实能力。
     */
    public static final int API_VERSION = 3;

    private ItemHubApi() {
    }

    /**
     * 获取当前中枢实现的 API 版本号。
     *
     * @return 版本号；调用方据此决定是否可以使用匹配模式相关接口
     */
    public static int apiVersion() {
        return API_VERSION;
    }

    /**
     * 判断指定玩家当前是否绑定了至少一个有效且已加载的物品中枢。
     *
     * @param player 目标玩家（服务端实例）
     * @return 若玩家有可用物品中枢则返回 {@code true}；若未绑定或中枢所在区块未加载则返回 {@code false}
     */
    public static boolean isAvailable(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return !ItemHubService.resolve(player).isEmpty();
    }

    /**
     * 获取玩家当前可用的物品中枢数量。
     *
     * @param player 目标玩家
     * @return 可用中枢数量（0 表示当前无可用中枢）
     */
    public static int getHubCount(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        return ItemHubService.resolve(player).size();
    }

    /**
     * 查询玩家物品中枢中指定物品（相同 Item 且相同 NBT 标签）的当前可用总存量。
     *
     * @param player 目标玩家
     * @param wanted 目标物品样本（仅匹配物品种类与 NBT 标签，数量字段被忽略）
     * @return 中枢中现存的该物品可用总数量；无可用中枢或未找到时返回 0
     */
    public static int countAvailable(ServerPlayer player, ItemStack wanted) {
        return countAvailable(player, wanted, ItemMatchMode.EXACT);
    }

    /**
     * 按指定匹配口径查询玩家物品中枢中目标物品的当前可用总存量。
     *
     * <p>蓝图类场景通常应使用 {@link ItemMatchMode#CLEAN}：中枢里的方块材料可能被容器补上一个空标签，
     * 严格匹配会把它们当成另一种物品而统计为 0。
     *
     * @param player 目标玩家
     * @param wanted 目标物品样本（数量字段被忽略）
     * @param mode   匹配口径，传 {@code null} 等价于 {@link ItemMatchMode#EXACT}
     * @return 中枢中符合该口径的物品总数量；无可用中枢或未找到时返回 0
     */
    public static int countAvailable(ServerPlayer player, ItemStack wanted, ItemMatchMode mode) {
        if (player == null || wanted == null || wanted.isEmpty()) {
            return 0;
        }
        ItemMatchMode effective = effectiveMode(mode);
        List<ItemStack> contents = ItemHubService.contents(player,
                candidate -> effective.matches(candidate, wanted));
        int total = 0;
        for (ItemStack stack : contents) {
            // contents 的 AE2 视图不保证已套用预筛条件，这里必须再按同一口径复核。
            if (effective.matches(stack, wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 按指定匹配口径列出中枢中所有符合条件的物品堆。
     *
     * <p>返回的每一堆都保留了中枢里的真实 NBT 状态（例如带空标签的堆就是带空标签的堆），
     * 调用方可以把它原样交给 {@link #extract(ServerPlayer, ItemStack, int)} 精确抽取，
     * 不必关心中枢是如何存储这种材料的。
     *
     * @param player 目标玩家
     * @param wanted 目标物品样本（数量字段被忽略）
     * @param mode   匹配口径，传 {@code null} 等价于 {@link ItemMatchMode#EXACT}
     * @return 符合口径的物品堆快照；无可用中枢或未找到时返回空列表
     */
    public static List<ItemStack> findMatches(ServerPlayer player, ItemStack wanted, ItemMatchMode mode) {
        if (player == null || wanted == null || wanted.isEmpty()) {
            return List.of();
        }
        ItemMatchMode effective = effectiveMode(mode);
        List<ItemStack> contents = ItemHubService.contents(player,
                candidate -> effective.matches(candidate, wanted));
        List<ItemStack> matches = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (effective.matches(stack, wanted)) {
                matches.add(stack.copy());
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 获取玩家物品中枢中的所有物品完整快照。
     *
     * <p>返回列表中的每个 {@link ItemStack} 均为独立深拷贝，外部对其进行修改不会影响中枢内部真实库存。
     *
     * @param player 目标玩家
     * @return 包含所有物品堆的只读快照列表；无可用中枢时返回空列表
     */
    public static List<ItemStack> getContents(ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        List<ItemStack> raw = ItemHubService.contents(player, stack -> true);
        List<ItemStack> copy = new ArrayList<>(raw.size());
        for (ItemStack stack : raw) {
            if (!stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        return List.copyOf(copy);
    }

    /**
     * 从玩家物品中枢中抽取指定数量的材料。
     *
     * <p>优先从 AE2 存储 / GTO 物品保险库抽取，不足时再从普通 Forge 容器抽取。
     * 若中枢总存量不足 {@code amount}，将抽空现有存量并如实返回其实际抽取到的数量。
     *
     * @param player 目标玩家
     * @param wanted 要抽取的物品类型与 NBT 样本
     * @param amount 需要抽取的数量上限（必须大于 0）
     * @return 实际抽出的物品堆；若未能抽到任何物品则返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack extract(ServerPlayer player, ItemStack wanted, int amount) {
        return extract(player, wanted, amount, ItemMatchMode.EXACT);
    }

    /**
     * 按指定匹配口径从玩家物品中枢抽取材料。
     *
     * <p>非严格口径下会先按口径找出中枢里真实存在的物品堆，再把这些堆的原样标签作为抽取需求，
     * 因此「样本无 NBT、中枢里是空标签」这类差异不会再导致抽取失败或抽错变体。
     *
     * @param player 目标玩家
     * @param wanted 要抽取的物品类型与 NBT 样本
     * @param amount 需要抽取的数量上限（必须大于 0）
     * @param mode   匹配口径，传 {@code null} 等价于 {@link ItemMatchMode#EXACT}
     * @return 实际抽出的物品堆（NBT 取自 {@code wanted}）；未抽到任何物品时返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack extract(ServerPlayer player, ItemStack wanted, int amount, ItemMatchMode mode) {
        if (player == null || wanted == null || wanted.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemMatchMode effective = effectiveMode(mode);
        // 严格口径下样本即需求，省掉一次全量快照扫描。
        List<ItemStack> requirements = effective == ItemMatchMode.EXACT
                ? List.of(wanted.copyWithCount(amount))
                : planRequirements(player, wanted, amount, effective);
        if (requirements.isEmpty()) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> output = new ArrayList<>();
        ItemHubService.extract(player, requirements, output);
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int total = 0;
        for (ItemStack item : output) {
            if (effective.matches(item, wanted)) {
                total += item.getCount();
            }
        }
        if (total <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack result = wanted.copy();
        result.setCount(total);
        return result;
    }

    /** 把「要 N 个匹配材料」拆成中枢里真实存在的若干堆，避免抽取层因标签差异失配。 */
    private static List<ItemStack> planRequirements(ServerPlayer player, ItemStack wanted, int amount,
                                                   ItemMatchMode mode) {
        List<ItemStack> requirements = new ArrayList<>();
        int remaining = amount;
        for (ItemStack match : findMatches(player, wanted, mode)) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, match.getCount());
            requirements.add(match.copyWithCount(take));
            remaining -= take;
        }
        return requirements;
    }

    private static ItemMatchMode effectiveMode(ItemMatchMode mode) {
        return mode == null ? ItemMatchMode.EXACT : mode;
    }

    /**
     * 便捷重载：从中枢抽取与 {@code wanted} 相同数量（{@code wanted.getCount()}）的材料。
     *
     * @param player 目标玩家
     * @param wanted 包含需求数量的物品样本
     * @return 实际抽出的物品堆；抽不到返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack extract(ServerPlayer player, ItemStack wanted) {
        if (wanted == null) {
            return ItemStack.EMPTY;
        }
        return extract(player, wanted, wanted.getCount());
    }

    /**
     * 让中枢把材料直接送进玩家背包，返回实际送达的数量。
     *
     * <p>用途：GTO 物品保险库、AE2 网络这类存储只接受「自己那条」提取器，通用的
     * {@link #extract} 会静默返回空堆。本方法复用 Recipe Sender 发送配方时的同一条取回链路，
     * 送达的物品落在玩家背包里——调用方随后按自己的口径从背包扣掉即可，
     * 等于「先从中枢拿到手，再用掉」。
     *
     * @param player 目标玩家
     * @param wanted 要取回的材料样本
     * @param amount 需要取回的数量上限（必须大于 0）
     * @return 实际送达玩家背包的数量（0 表示没送到）
     */
    public static int pullToInventory(ServerPlayer player, ItemStack wanted, int amount) {
        return pullToInventory(player, wanted, amount, ItemMatchMode.EXACT);
    }

    /**
     * 按指定匹配口径把中枢材料直接送进玩家背包。
     *
     * <p>非严格口径下会先找出中枢里真实存在的物品堆，用它的原样标签作为取回样本，
     * 避免「需求是纯净堆、中枢里存的是带空标签的堆」这类差异导致取不回来。
     *
     * @param player 目标玩家
     * @param wanted 要取回的材料样本
     * @param amount 需要取回的数量上限（必须大于 0）
     * @param mode   匹配口径，传 {@code null} 等价于 {@link ItemMatchMode#EXACT}
     * @return 实际送达玩家背包的数量（0 表示没送到）
     */
    public static int pullToInventory(ServerPlayer player, ItemStack wanted, int amount, ItemMatchMode mode) {
        if (player == null || wanted == null || wanted.isEmpty() || amount <= 0) {
            return 0;
        }
        ItemMatchMode effective = effectiveMode(mode);
        ItemStack prototype = wanted.copyWithCount(1);
        if (effective != ItemMatchMode.EXACT) {
            List<ItemStack> matches = findMatches(player, wanted, effective);
            if (matches.isEmpty()) {
                return 0;
            }
            prototype = matches.get(0).copyWithCount(1);
        }
        return ItemHubService.pullToInventory(player, prototype, amount);
    }

    /**
     * 列出玩家当前绑定到的中枢（只读诊断，不抽取、不插回）。
     *
     * @param player 目标玩家
     * @return 每个中枢一行的可读描述；未绑定时返回一条说明
     */
    public static List<String> describeHubs(ServerPlayer player) {
        return diagnose(player, ItemStack.EMPTY, ItemMatchMode.EXACT);
    }

    /**
     * 诊断玩家绑定的中枢对某条需求有没有供料能力（只读）。
     *
     * <p>每行依次是：维度、坐标、方块 ID、AE2 存储键数与匹配数、Forge 槽位格数与匹配格数、
     * 保护门判定。这是给放置报告和 {@code /gtoblueprint hubcheck} 用的：把「统计有、抽不到」
     * 拆成三种可分辨的情况——存储里根本没有 / 有但键对不上 / 对得上但被保护模组拦下。
     *
     * @param player 目标玩家
     * @param wanted 要检查的材料原型；{@link ItemStack#EMPTY} 时只列容器、不判定匹配
     * @param mode   匹配口径；{@code null} 等价于 {@link ItemMatchMode#EXACT}
     * @return 可读的诊断行；玩家为空时返回空列表
     */
    public static List<String> diagnose(ServerPlayer player, ItemStack wanted, ItemMatchMode mode) {
        if (player == null) {
            return List.of();
        }
        List<BlockEntity> hubs = ItemHubService.hubs(player);
        if (hubs.isEmpty()) {
            return List.of("未绑定任何已加载的物品中枢");
        }
        ItemStack prototype = wanted == null ? ItemStack.EMPTY : wanted;
        ItemMatchMode effective = mode == null ? ItemMatchMode.EXACT : mode;
        List<String> lines = new ArrayList<>(hubs.size() + 1);
        lines.add("中枢数量: " + hubs.size());
        for (BlockEntity hub : hubs) {
            StringBuilder line = new StringBuilder();
            line.append(hub.getLevel() == null ? "?" : hub.getLevel().dimension().location())
                    .append(' ').append(hub.getBlockPos().toShortString())
                    .append(' ').append(BuiltInRegistries.BLOCK.getKey(hub.getBlockState().getBlock()));
            if (!prototype.isEmpty()) {
                line.append(" | ").append(FindMeExtendedAdapter.describeSupply(hub, prototype, effective, player));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * 将物品放回/存入玩家绑定的物品中枢。
     *
     * <p>写入通道自动匹配：若中枢为 AE2 容器 / GTO 物品保险库，将以 {@code AEItemKey} 形式直接存入 ME 网络；
     * 若中枢为普通 Forge 容器，将按槽位逐格放入。
     *
     * @param player 目标玩家
     * @param stack  要存入的物品
     * @return 无法存入的剩余物品堆（中枢已满或无中枢可用）；若全部成功存入则返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack insert(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        List<ItemStack> leftovers = ItemHubService.insert(player, List.of(stack));
        return leftovers.isEmpty() ? ItemStack.EMPTY : leftovers.get(0);
    }

    /**
     * 批量将多组物品放回/存入玩家绑定的物品中枢。
     *
     * @param player 目标玩家
     * @param stacks 要存入的物品列表
     * @return 未能存入的余料列表；若全部成功存入则返回空列表
     */
    public static List<ItemStack> insertAll(ServerPlayer player, List<ItemStack> stacks) {
        if (player == null || stacks == null || stacks.isEmpty()) {
            return stacks == null ? List.of() : stacks;
        }
        return ItemHubService.insert(player, stacks);
    }
}
