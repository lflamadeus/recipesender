package com.lai.recipesender.service;

import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 按「配方输入项」把材料拆成逐份的材料表，并统计其中真正凑齐了几份。
 *
 * <p>为什么需要这一层：格雷的电路板一类材料，一个配方输入项下往往有几十种可互换的物品
 * （LV 电路板 1 号、2 号……）。算份数时它们是同一种材料，发送时也必须当成同一种材料——
 * 一份配方要两个板，允许送两种不同的板进去，只要数量对得上。
 *
 * <p>旧实现按「每个具体物品堆」各自除以份数（{@code RemoteTransferPlanner.toBatches}）：
 * A 板 1 个、B 板 1 个、一共 2 份时，两种板各自 1/2 都是 0，于是都进了余量桶，最后整桶塞进
 * 第一台机器——既没有均分，那台机器也配不出方子。这里改成<b>按输入项分组</b>分配：同一组内的
 * 变体合成一个池子，池子总量除以份数，余数摊给前几份，每份从池里顺序取。总量守恒，且每份都是
 * 一套完整材料，不再有「余量桶」这种会破坏均分的兜底。
 */
public final class MaterialBatches {

    private MaterialBatches() {
    }

    /**
     * 把若干「同类可互换的材料组」拆成 {@code batches} 份。
     *
     * <p>每组先按物品合并成变体池（同一变体可能来自多个来源容器），再算每份的基础数量与余数：
     * 余数按「前 rem 份各多一个」摊开，因此总量守恒，任何一份都不会缺料。组内不同变体可以进同一份，
     * 这正是「一份配方的两个电路板可以是两种板」的落点。
     *
     * <p>正常路径下每组的材料总量都是「每份数量 × 份数」，余数为 0；余数分支只为容纳伪造包、
     * 以及来源容器实际给料与统计不一致的情况——那种时候也宁可把多出来的一个摊给某一台，
     * 而不是像旧实现那样全塞给第一台。
     *
     * @param groups  每个元素是一种配方输入项下的材料堆，同组内可互换
     * @param batches 份数，小于 1 时返回空表
     * @return 长度为 {@code batches} 的列表，每份是该份要投的材料堆；没有材料的份是空表
     */
    public static List<List<ItemStack>> split(List<List<ItemStack>> groups, int batches) {
        if (batches < 1 || groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<Pool> pools = new ArrayList<>(groups.size());
        int[] baseCounts = new int[groups.size()];
        int[] extras = new int[groups.size()];
        for (int index = 0; index < groups.size(); index++) {
            List<ItemStack> merged = RemoteTransferPlanner.mergeByItem(groups.get(index));
            pools.add(new Pool(merged));
            int total = totalCount(merged);
            baseCounts[index] = total / batches;
            extras[index] = total % batches;
        }
        List<List<ItemStack>> result = new ArrayList<>(batches);
        for (int batch = 0; batch < batches; batch++) {
            List<ItemStack> materials = new ArrayList<>();
            for (int index = 0; index < pools.size(); index++) {
                int amount = baseCounts[index] + (batch < extras[index] ? 1 : 0);
                pools.get(index).take(amount, materials);
            }
            result.add(materials);
        }
        return result;
    }

    /**
     * 展平成一份材料清单。
     *
     * <p>扣背包、从容器抽料、模拟插入这些环节只关心「总共要哪些物品各多少个」，
     * 分组信息对它们没有意义，所以统一在这里摊平，免得每个调用点各写一遍。
     */
    public static List<ItemStack> flatten(List<List<ItemStack>> batches) {
        List<ItemStack> flat = new ArrayList<>();
        for (List<ItemStack> batch : batches) {
            for (ItemStack stack : batch) {
                if (stack != null && !stack.isEmpty()) {
                    append(flat, stack, stack.getCount());
                }
            }
        }
        return flat;
    }

    /**
     * 数一数「真正凑齐了几份」。
     *
     * <p>旧实现按每件材料的 {@code 已投数量 / 每份数量} 取最小值：遇到总量不足一份的材料会直接
     * 跳过校验，于是少投了也照样报满份。这里逐份扣减：哪一份的材料全都还在库存里，它才算数，
     * 扣完再看下一份。
     *
     * @param batches  逐份材料表（{@link #split} 的结果或其前缀）
     * @param inserted 实际投进目标的材料
     * @return 材料齐全的份数
     */
    public static int countComplete(List<List<ItemStack>> batches, List<ItemStack> inserted) {
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack stack : RemoteTransferPlanner.mergeByItem(inserted)) {
            stock.add(stack.copy());
        }
        int complete = 0;
        for (List<ItemStack> batch : batches) {
            if (batch.isEmpty() || !covers(stock, batch)) {
                continue;
            }
            for (ItemStack need : batch) {
                consume(stock, need);
            }
            complete++;
        }
        return complete;
    }

    /** 材料总数量；空堆与 {@code null} 不计。 */
    public static int totalCount(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 库存里是否还够这一份的全部材料。 */
    private static boolean covers(List<ItemStack> stock, List<ItemStack> batch) {
        for (ItemStack need : batch) {
            int available = 0;
            for (ItemStack stack : stock) {
                if (ItemStack.isSameItemSameTags(stack, need)) {
                    available += stack.getCount();
                }
            }
            if (available < need.getCount()) {
                return false;
            }
        }
        return true;
    }

    /** 从库存里扣掉一份材料的用量；调用前必须已经 {@link #covers} 过。 */
    private static void consume(List<ItemStack> stock, ItemStack need) {
        int remaining = need.getCount();
        for (ItemStack stack : stock) {
            if (remaining <= 0) {
                return;
            }
            if (!ItemStack.isSameItemSameTags(stack, need)) {
                continue;
            }
            int moved = Math.min(remaining, stack.getCount());
            stack.shrink(moved);
            remaining -= moved;
        }
    }

    /**
     * 往清单里加材料，同物品合并、单堆数量封顶。
     *
     * <p>封顶值与网络包的单堆上限一致：材料清单最终要能编码进包，超过上限就再开一堆，
     * 而不是悄悄丢掉多出来的数量。
     */
    private static void append(List<ItemStack> output, ItemStack sample, int amount) {
        int remaining = amount;
        for (ItemStack existing : output) {
            if (remaining <= 0) {
                return;
            }
            if (!ItemStack.isSameItemSameTags(existing, sample)) {
                continue;
            }
            int room = InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT - existing.getCount();
            int moved = Math.min(remaining, room);
            if (moved > 0) {
                existing.grow(moved);
                remaining -= moved;
            }
        }
        while (remaining > 0) {
            int moved = Math.min(remaining, InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT);
            output.add(sample.copyWithCount(moved));
            remaining -= moved;
        }
    }

    /** 一个输入项的变体池：按顺序取用，游标记住「取到哪个变体、这个变体已经取了多少」。 */
    private static final class Pool {
        private final List<ItemStack> variants;
        private int cursor;
        private int used;

        private Pool(List<ItemStack> variants) {
            this.variants = variants;
        }

        private void take(int amount, List<ItemStack> output) {
            int remaining = amount;
            while (remaining > 0 && cursor < variants.size()) {
                ItemStack variant = variants.get(cursor);
                int available = variant.getCount() - used;
                if (available <= 0) {
                    cursor++;
                    used = 0;
                    continue;
                }
                int moved = Math.min(remaining, available);
                append(output, variant, moved);
                remaining -= moved;
                used += moved;
                if (used >= variant.getCount()) {
                    cursor++;
                    used = 0;
                }
            }
        }
    }
}
