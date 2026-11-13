package com.lai.recipesender.integration.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.runtime.EmiFavorite;
import net.minecraft.resources.ResourceLocation;

import java.util.OptionalInt;

/**
 * EMI 配方树（Recipe Tree / BoM）需求数量解析支持。
 */
public final class EmiRecipeTreeSupport {
    private EmiRecipeTreeSupport() {
    }

    /**
     * 获取当前悬停目标或指定配方在 EMI 配方树中所需要的执行批次数（份数）。
     *
     * @param hoveredStack 悬停的 EMI 条目/原料（在合成模式收藏栏下是 {@link EmiFavorite.Synthetic}）
     * @param recipe       当前配方
     * @return 树需求的批次数（若无活跃树或配方不在树中则返回 empty）
     */
    public static OptionalInt getTreeBatches(EmiIngredient hoveredStack, EmiRecipe recipe) {
        if (hoveredStack == null && recipe == null) {
            return OptionalInt.empty();
        }
        try {
            // 1. 优先直接从悬停的 Synthetic 条目读取（玩家在 EMI 合成模式收藏栏中悬停）
            if (hoveredStack instanceof EmiFavorite.Synthetic synthetic) {
                long batches = synthetic.batches > 0 ? synthetic.batches : synthetic.total;
                if (batches > 0) {
                    return OptionalInt.of((int) Math.min(batches, Integer.MAX_VALUE));
                }
            }

            // 2. 检查后台活跃的 BoM 配方树
            if (BoM.tree != null && BoM.tree.goal != null && recipe != null) {
                long needed = countNeededBatches(BoM.tree.goal, recipe);
                if (needed > 0) {
                    return OptionalInt.of((int) Math.min(needed, Integer.MAX_VALUE));
                }
            }
        } catch (Throwable ignored) {
            // 防御性保护：避免外部类加载或版本差异引发异常
        }
        return OptionalInt.empty();
    }

    /** 递归累加整棵配方树中该配方所需的批次数。 */
    private static long countNeededBatches(MaterialNode node, EmiRecipe target) {
        if (node == null) {
            return 0;
        }
        long total = 0;
        if (sameRecipe(node.recipe, target)) {
            total += node.neededBatches > 0 ? node.neededBatches : node.totalNeeded;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                total += countNeededBatches(child, target);
            }
        }
        return total;
    }

    /** 结构比较两个配方是否一致。 */
    private static boolean sameRecipe(EmiRecipe a, EmiRecipe b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        ResourceLocation idA = a.getId();
        ResourceLocation idB = b.getId();
        return idA != null && idA.equals(idB) && a.getCategory() == b.getCategory();
    }
}
