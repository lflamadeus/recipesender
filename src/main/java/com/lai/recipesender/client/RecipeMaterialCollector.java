package com.lai.recipesender.client;

import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.service.TransferPlanner;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 将 EMI 配方转换为背包统计、发送和服务端查询所需的数据。 */
final class RecipeMaterialCollector {
    private RecipeMaterialCollector() {
    }

    /** 统计玩家背包能够完整制作的最大配方份数。 */
    static int countAvailableBatches(EmiRecipe recipe, Player player) {
        if (recipe == null || player == null) {
            return 0;
        }
        int lower = 0;
        int upper = RecipeIngredientSpec.MAX_BATCHES + 1;
        while (lower + 1 < upper) {
            int candidate = lower + (upper - lower) / 2;
            if (canCollect(recipe, player, candidate)) {
                lower = candidate;
            } else {
                upper = candidate;
            }
        }
        return lower;
    }

    /**
     * 统计当前目标容器还能接收的最大配方份数。
     * 结果同时受背包存量和目标槽位容量限制，用于“尽可能发送”功能。
     * 规划器与服务端共用同一份实现，因此这里的估算与真正的投放判定一致。
     */
    static int countInsertableBatches(EmiRecipe recipe, Player player, AbstractContainerMenu menu,
                                      int availableBatches) {
        int upperBound = Math.min(availableBatches, RecipeIngredientSpec.MAX_BATCHES);
        if (recipe == null || player == null || menu == null || upperBound < 1) {
            return 0;
        }
        int lower = 0;
        int upper = upperBound + 1;
        while (lower + 1 < upper) {
            int candidate = lower + (upper - lower) / 2;
            if (canInsert(recipe, player, menu, candidate)) {
                lower = candidate;
            } else {
                upper = candidate;
            }
        }
        return lower;
    }

    /** 判断背包材料能否完整放进当前目标槽位。 */
    private static boolean canInsert(EmiRecipe recipe, Player player, AbstractContainerMenu menu,
                                     int batches) {
        if (batches < 1) {
            return true;
        }
        CollectionResult result = collect(recipe, player, batches);
        return result.success() && TransferPlanner.create(player.getInventory(), menu,
                result.requirements()) != null;
    }

    /** 找出当前配方匹配的背包槽位，用于界面高亮。 */
    static Set<Integer> findMatchingInventorySlots(EmiRecipe recipe, Player player) {
        if (recipe == null || player == null) {
            return Set.of();
        }
        Inventory inventory = player.getInventory();
        Set<Integer> matchingSlots = new HashSet<>();
        List<EmiIngredient> inputs = recipe.getInputs();
        for (int index = 0; index < inventory.items.size(); index++) {
            ItemStack candidate = inventory.items.get(index);
            if (!candidate.isEmpty() && matchesAnyItemInput(inputs, candidate)) {
                matchingSlots.add(index);
            }
        }
        return Set.copyOf(matchingSlots);
    }

    /** 将 EMI 输入转换为服务端可验证的材料描述。 */
    static List<RecipeIngredientSpec> createIngredientSpecs(EmiRecipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        List<RecipeIngredientSpec> result = new ArrayList<>();
        for (EmiIngredient ingredient : recipe.getInputs()) {
            long amount = ingredient.getAmount();
            if (amount <= 0 || amount > 4096) {
                continue;
            }
            List<ItemStack> options = ingredient.getEmiStacks().stream()
                    .map(EmiStack::getItemStack)
                    .filter(stack -> !stack.isEmpty())
                    .limit(RecipeIngredientSpec.MAX_OPTIONS)
                    .map(stack -> stack.copyWithCount(1))
                    .toList();
            if (options.isEmpty()) {
                continue;
            }
            RecipeIngredientSpec spec = new RecipeIngredientSpec((int) amount,
                    ingredient.getEmiStacks().size() > 1, options);
            if (spec.isValid()) {
                result.add(spec);
            }
            if (result.size() >= RecipeIngredientSpec.MAX_INGREDIENTS) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** 按指定份数从背包快照中规划正向投放材料。 */
    static CollectionResult collect(EmiRecipe recipe, Player player, int batches) {
        if (recipe == null || player == null || batches < 1
                || batches > RecipeIngredientSpec.MAX_BATCHES) {
            return CollectionResult.failure("配方份数超出允许范围");
        }
        List<ItemStack> remaining = copyInventory(player);
        List<ItemStack> requirements = new ArrayList<>();
        List<EmiIngredient> inputs = recipe.getInputs();
        for (int index = 0; index < inputs.size(); index++) {
            EmiIngredient ingredient = inputs.get(index);
            long baseAmount = ingredient.getAmount();
            if (baseAmount <= 0 || !hasItemOption(ingredient)) {
                continue;
            }
            long requested = baseAmount * batches;
            if (requested > Integer.MAX_VALUE) {
                return CollectionResult.failure("第 " + (index + 1) + " 项材料数量过大");
            }
            boolean wildcard = ingredient.getEmiStacks().size() > 1;
            if (!consumeMatching(ingredient, remaining, (int) requested, wildcard, requirements)) {
                return CollectionResult.failure("第 " + (index + 1) + " 项材料不足");
            }
        }
        if (requirements.isEmpty()) {
            return CollectionResult.failure("配方没有可发送的物品输入");
        }
        return CollectionResult.success(List.copyOf(requirements));
    }

    /** 判断背包快照是否足够制作指定份数。 */
    private static boolean canCollect(EmiRecipe recipe, Player player, int batches) {
        List<ItemStack> remaining = copyInventory(player);
        for (EmiIngredient ingredient : recipe.getInputs()) {
            long baseAmount = ingredient.getAmount();
            if (baseAmount <= 0 || !hasItemOption(ingredient)) {
                continue;
            }
            long requested = baseAmount * batches;
            if (requested > Integer.MAX_VALUE || !consumeMatching(ingredient, remaining,
                    (int) requested, ingredient.getEmiStacks().size() > 1, null)) {
                return false;
            }
        }
        return true;
    }

    /** 创建玩家背包的独立物品快照。 */
    private static List<ItemStack> copyInventory(Player player) {
        return player.getInventory().items.stream().map(ItemStack::copy)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /** 从材料快照中消耗满足输入的物品。 */
    private static boolean consumeMatching(EmiIngredient ingredient, List<ItemStack> available,
                                           int amount, boolean wildcard,
                                           List<ItemStack> requirements) {
        int remaining = amount;
        for (ItemStack stack : available) {
            if (remaining == 0) {
                break;
            }
            if (!matchesAny(ingredient, stack, wildcard)) {
                continue;
            }
            int moved = Math.min(remaining, stack.getCount());
            if (requirements != null && !appendRequirement(requirements, stack, moved)) {
                return false;
            }
            stack.shrink(moved);
            remaining -= moved;
        }
        return remaining == 0;
    }

    /** 判断物品是否匹配配方的任一输入项。 */
    private static boolean matchesAnyItemInput(List<EmiIngredient> inputs, ItemStack candidate) {
        return inputs.stream().filter(RecipeMaterialCollector::hasItemOption)
                .anyMatch(ingredient -> matchesAny(ingredient, candidate,
                        ingredient.getEmiStacks().size() > 1));
    }

    /** 判断 EMI 输入是否包含可发送的物品候选。 */
    private static boolean hasItemOption(EmiIngredient ingredient) {
        return ingredient.getEmiStacks().stream()
                .anyMatch(stack -> !stack.getItemStack().isEmpty());
    }

    /** 判断物品是否匹配输入项，必要时支持同物品不同耐久。 */
    private static boolean matchesAny(EmiIngredient ingredient, ItemStack candidate,
                                      boolean wildcard) {
        return ingredient.getEmiStacks().stream().anyMatch(option -> {
            ItemStack expected = option.getItemStack();
            return !expected.isEmpty() && (ItemStack.isSameItemSameTags(expected, candidate)
                    || wildcard && expected.getTag() == null
                    && ItemStack.isSameItem(expected, candidate));
        });
    }

    /** 合并相同材料并拆分到网络包允许的数量范围。 */
    private static boolean appendRequirement(List<ItemStack> requirements,
                                             ItemStack sample, int amount) {
        int remaining = amount;
        for (ItemStack existing : requirements) {
            if (!ItemStack.isSameItemSameTags(existing, sample)
                    || existing.getCount() >= InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT) {
                continue;
            }
            int moved = Math.min(remaining,
                    InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT - existing.getCount());
            existing.grow(moved);
            remaining -= moved;
            if (remaining == 0) {
                return true;
            }
        }
        while (remaining > 0 && requirements.size() < InsertRecipeItemsPacket.MAX_REQUIREMENTS) {
            int moved = Math.min(remaining, InsertRecipeItemsPacket.MAX_ITEMS_PER_REQUIREMENT);
            requirements.add(sample.copyWithCount(moved));
            remaining -= moved;
        }
        return remaining == 0;
    }

    record CollectionResult(boolean success, List<ItemStack> requirements, String message) {
        static CollectionResult success(List<ItemStack> requirements) {
            return new CollectionResult(true, requirements, "");
        }

        static CollectionResult failure(String message) {
            return new CollectionResult(false, List.of(), message);
        }
    }
}
