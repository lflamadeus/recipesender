package com.lai.recipesender.client;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配方类别的一次性快照，供类别选择器与管理界面共用。
 *
 * <p>为什么要有缓存：EMI 的类别总数约 250（GT 系 ≈200 + 原版 14 + AE2 ~10 + 其它插件 20–40），
 * 每开一次界面就重新枚举一遍「类别 × 机器 × 配方计数」既慢又没必要——这些数据在一次游戏会话里
 * 基本不变（只有重载数据包才会变，所以按类别总数做一次校验，对不上就重建）。
 *
 * <p>全部走 EMI 公开 API：{@code getCategories()} / {@code getWorkstations(category)} /
 * {@code getRecipes()}，零反射、零 Mixin。
 */
final class RecipeCategoryCache {

    /**
     * 一个类别连同显示与搜索所需的全部数据。
     *
     * @param category     原始类别对象（渲染图标、查询配方都要用它）
     * @param id           类别 id，也是存进 {@code BoundContainer#routeKeys} 的那个键
     * @param namespace    分组键：{@code id.getNamespace()}（gtceu / gtocore / minecraft / ae2 …）
     * @param name         中文显示名（{@code category.getName().getString()}）
     * @param workstations 这个类别下的机器，用来画图标、也用来判断「能不能发送」
     * @param stationItems 上面这些机器里能取到物品本体的那些（去重），用于「只看本机可用」过滤：
     *                     拿当前容器方块的物品去比，命中即认为这台机器能跑这个类别
     * @param recipeCount  配方条数，用来隐藏空类别、也顺手给玩家一个数量参考
     * @param searchText   搜索用文本：类别名 + 类别 id + 全部机器名，已小写
     */
    record Entry(EmiRecipeCategory category, ResourceLocation id, String namespace, String name,
                 List<EmiIngredient> workstations, Set<Item> stationItems, int recipeCount,
                 String searchText) {

        /** 是不是 GT 系类别。GT 的机器图标由插件单独注册，不能因为没查到 workstation 就当它没用。 */
        boolean isGt() {
            return namespace.startsWith("gt");
        }

        /** 这台机器（按物品比）有没有被登记成该类别的机器。 */
        boolean usesMachine(Item item) {
            return item != null && stationItems.contains(item);
        }

        /**
         * 这个类别有没有可发送的落点。
         *
         * <p>「没有机器、也不是 GT 类别」的多半是纯展示类类别（例如「多方块信息」），
         * 选中它永远匹配不到能收材料的机器，所以默认不显示（可用「显示全部」翻出来）。
         */
        boolean sendable() {
            return !workstations.isEmpty() || isGt();
        }
    }

    private static List<Entry> entries = List.of();
    private static Map<ResourceLocation, String> names = Map.of();
    private static int cachedCategoryCount = -1;

    private RecipeCategoryCache() {
    }

    /** 全部类别，按命名空间、再按显示名排序；配方管理器还没就绪时返回空列表。 */
    static synchronized List<Entry> entries() {
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        if (manager == null) {
            return List.of();
        }
        List<EmiRecipeCategory> categories = manager.getCategories();
        // 数量对不上 = 数据包重载过（换整合包、换存档），重建一份。
        if (categories.size() != cachedCategoryCount || entries.isEmpty()) {
            rebuild(manager, categories);
        }
        return entries;
    }

    /** 类别 id → 显示名；查不到时退回 id 本身，保证界面上永远有东西可看。 */
    static synchronized String nameOf(ResourceLocation id) {
        entries();
        return names.getOrDefault(id, id.toString());
    }

    private static void rebuild(EmiRecipeManager manager, List<EmiRecipeCategory> categories) {
        // 一次遍历数完所有类别的配方条数：比逐个 getRecipes(category) 稳妥（后者可能是线性扫描）。
        Map<EmiRecipeCategory, Integer> counts = new HashMap<>();
        for (EmiRecipe recipe : manager.getRecipes()) {
            counts.merge(recipe.getCategory(), 1, Integer::sum);
        }

        List<Entry> built = new ArrayList<>(categories.size());
        Map<ResourceLocation, String> builtNames = new HashMap<>(categories.size() * 2);
        for (EmiRecipeCategory category : categories) {
            if (category == null || category.getId() == null) {
                continue;
            }
            ResourceLocation id = category.getId();
            String name = category.getName().getString();
            List<EmiIngredient> workstations = manager.getWorkstations(category);
            if (workstations == null) {
                workstations = List.of();
            }
            StringBuilder text = new StringBuilder(name).append(' ').append(id).append(' ');
            Set<Item> stationItems = new LinkedHashSet<>();
            for (EmiIngredient station : workstations) {
                for (EmiStack stack : station.getEmiStacks()) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    text.append(stack.getName().getString()).append(' ');
                    ItemStack itemStack = stack.getItemStack();
                    if (!itemStack.isEmpty()) {
                        stationItems.add(itemStack.getItem());
                    }
                }
            }
            built.add(new Entry(category, id, id.getNamespace(), name, List.copyOf(workstations),
                    Set.copyOf(stationItems), counts.getOrDefault(category, 0),
                    text.toString().toLowerCase(Locale.ROOT)));
            builtNames.put(id, name);
        }
        built.sort(Comparator.comparing(Entry::namespace).thenComparing(Entry::name));
        entries = List.copyOf(built);
        names = Map.copyOf(builtNames);
        cachedCategoryCount = categories.size();
    }
}
