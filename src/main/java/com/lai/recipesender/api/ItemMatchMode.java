package com.lai.recipesender.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * 物品匹配口径：决定「什么算同一种物品」。
 *
 * <p>中枢内的物品来自不同容器（AE2 网络、GTO 物品保险库、普通 Forge 容器），同一种材料的 NBT 状态
 * 并不统一：有的堆完全没有标签（{@code tag == null}），有的堆带一个<b>空</b>标签（{@code tag.isEmpty()}）。
 * 这两种状态对配方而言完全等价，但 {@link ItemStack#isSameItemSameTags} 会把它们判为不同物品，
 * 于是「统计说有、真抽时抽不到」或「明明有却统计为 0」。
 *
 * <p>本枚举把判据集中到一处，所有查询与抽取接口都按同一口径比较，调用方按需选择：
 * <ul>
 *   <li>{@link #EXACT}：严格匹配（默认值，与历史行为一致），{@code null} 标签与空标签视为同一无 NBT 状态；</li>
 *   <li>{@link #CLEAN}：只认纯净物品，两侧都不得携带任何实质 NBT（蓝图建材、方块类材料通常用它）；</li>
 *   <li>{@link #IGNORE_NBT}：只看物品与伤害值，用于粗粒度检索。</li>
 * </ul>
 *
 * <p>三种口径都<b>强制比较伤害值</b>：GregTech 的 metadata 变体共用同一个 {@code Item}，
 * 忽略伤害值会抽错材料。
 */
public enum ItemMatchMode {

    /**
     * 严格匹配：物品、伤害值、NBT 标签、能力数据（ForgeCaps）都必须一致。
     *
     * <p>空标签 {@code {}} 与 {@code null} 归一为「无 NBT」，不再被判为两种物品；
     * 仅存在能力实例但没有任何能力数据的一侧，与完全没有能力实例的一侧视为兼容
     * （Forge 的 {@code areCapsCompatible} 本身已按此规则处理空写入器）。
     */
    EXACT,

    /**
     * 纯净匹配：物品与伤害值相同，且两侧都不携带实质 NBT。
     *
     * <p>适用于「要的是一张白纸」的场景。带配置、带能量、带滤网的同类物品不会被误算进来。
     */
    CLEAN,

    /**
     * 忽略 NBT：只比较物品与伤害值。
     *
     * <p>用于粗粒度检索与兜底抽取，慎用于材料统计——它会把不同配置的同类物品算成同一种。
     */
    IGNORE_NBT;

    /**
     * 判断候选物品能否代表（满足）原型物品。
     *
     * @param candidate 中枢/背包中实际存在的物品堆
     * @param prototype 调用方给出的目标样本（数量字段不参与比较）
     * @return 按本口径匹配则返回 {@code true}；任一参数为 {@code null} 或空堆时返回 {@code false}
     */
    public boolean matches(ItemStack candidate, ItemStack prototype) {
        if (candidate == null || prototype == null || candidate.isEmpty() || prototype.isEmpty()) {
            return false;
        }
        if (!candidate.is(prototype.getItem()) || candidate.getDamageValue() != prototype.getDamageValue()) {
            return false;
        }
        return switch (this) {
            case IGNORE_NBT -> true;
            case CLEAN -> !hasNbt(candidate) && !hasNbt(prototype);
            case EXACT -> Objects.equals(normalizeTag(candidate.getTag()), normalizeTag(prototype.getTag()))
                    && candidate.areCapsCompatible(prototype);
        };
    }

    /**
     * 判断物品堆是否携带实质 NBT（{@code null} 标签与空标签都算「没有 NBT」）。
     *
     * @param stack 待检查的物品堆
     * @return 带有非空标签时返回 {@code true}
     */
    public static boolean hasNbt(ItemStack stack) {
        return stack != null && normalizeTag(stack.getTag()) != null;
    }

    /**
     * 把标签归一为「有 NBT / 无 NBT」两种状态：空标签一律返回 {@code null}。
     *
     * @param tag 原始标签，可为 {@code null}
     * @return 非空标签原样返回；{@code null} 或空标签返回 {@code null}
     */
    public static CompoundTag normalizeTag(CompoundTag tag) {
        return tag == null || tag.isEmpty() ? null : tag;
    }
}
