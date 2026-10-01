package com.lai.recipesender.client;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从一条 EMI 配方算出它的「路由键」（方案 §4.2）。
 *
 * <p>取值按优先级，能拿到几个就放几个：
 * <ol>
 *   <li>{@code GTRecipeDefinition.recipeType.registryName}（如 {@code gtceu:assembler}）——
 *       最精确的机器类型，需要 GT 在场 + 反射，拿不到就跳过。</li>
 *   <li>{@link EmiRecipeCategory#getId()}——EMI 公开 API，零反射。GT 默认类别下它<b>等于</b>
 *       机器类型注册名（{@code GTRecipeEMICategory} 构造时 {@code super(category.registryKey, ...)}），
 *       GTO 拆出来的子类别则是 {@code gtceu:<自定义名>}。</li>
 * </ol>
 *
 * <p>两个都拿到时<b>一起放进结果</b>：玩家给主容器勾的往往是默认类别（组装机），而鼠标下的
 * 可能是 GTO 的子类别（某个模块），只要有一个对上就算命中——这正是子类别能被自动归并的原因。
 *
 * <p>纯查询、不缓存：一次选择只算一次（开始选择那一刻悬停的那条配方）。
 */
final class RecipeRouteKeys {

    private RecipeRouteKeys() {
    }

    /** 算出这条配方的全部路由键；识别不出来时返回空集合，调用方据此中止并提示。 */
    static Set<ResourceLocation> of(EmiRecipe recipe) {
        if (recipe == null) {
            return Set.of();
        }
        Set<ResourceLocation> keys = new LinkedHashSet<>(2);
        ResourceLocation machineType = GtCircuitSupport.findRecipeTypeKey(recipe);
        if (machineType != null) {
            keys.add(machineType);
        }
        EmiRecipeCategory category = recipe.getCategory();
        if (category != null && category.getId() != null) {
            keys.add(category.getId());
        }
        return keys;
    }

    /** 配方类别的显示名，用于「没有为「组装机」绑定容器」这类提示；拿不到时返回空串。 */
    static String categoryName(EmiRecipe recipe) {
        if (recipe == null) {
            return "";
        }
        EmiRecipeCategory category = recipe.getCategory();
        return category == null ? "" : category.getName().getString();
    }

    /**
     * 「上次选择」记忆用的那一个键（方案 §4.3 的 D2）。
     *
     * <p>路由键是一组（机器类型 + 类别 id），但记忆只能按一个键存：挑优先级最高的那个。
     * 取不到任何键时返回 {@code null}，调用方退回 {@code recipe_sender:manual}。
     */
    static ResourceLocation primary(EmiRecipe recipe) {
        if (recipe == null) {
            return null;
        }
        ResourceLocation machineType = GtCircuitSupport.findRecipeTypeKey(recipe);
        if (machineType != null) {
            return machineType;
        }
        EmiRecipeCategory category = recipe.getCategory();
        return category == null ? null : category.getId();
    }
}
