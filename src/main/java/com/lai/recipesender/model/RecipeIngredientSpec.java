package com.lai.recipesender.model;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 与具体配方查看器解耦的物品输入描述。 */
public record RecipeIngredientSpec(int amountPerBatch, boolean wildcard, List<ItemStack> options) {
    public static final int MAX_BATCHES = 512;
    public static final int MAX_INGREDIENTS = 64;
    public static final int MAX_OPTIONS = 32;

    public RecipeIngredientSpec {
        options = List.copyOf(options);
    }

    /** 检查服务端收到的配方材料描述是否在安全范围内。 */
    public boolean isValid() {
        return amountPerBatch > 0 && amountPerBatch <= 4096
                && !options.isEmpty() && options.size() <= MAX_OPTIONS
                && options.stream().allMatch(stack -> stack != null && !stack.isEmpty());
    }

    /** 判断一个物品是否满足当前配方输入的任一候选项。 */
    public boolean matches(ItemStack candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (ItemStack option : options) {
            if (ItemStack.isSameItemSameTags(option, candidate)) {
                return true;
            }
            if (wildcard && option.getTag() == null && ItemStack.isSameItem(option, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 将配方材料描述写入网络缓冲区。 */
    public static void writeList(FriendlyByteBuf buffer, List<RecipeIngredientSpec> ingredients) {
        int size = Math.min(ingredients.size(), MAX_INGREDIENTS);
        buffer.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            RecipeIngredientSpec ingredient = ingredients.get(i);
            buffer.writeVarInt(ingredient.amountPerBatch);
            buffer.writeBoolean(ingredient.wildcard);
            int optionCount = Math.min(ingredient.options.size(), MAX_OPTIONS);
            buffer.writeVarInt(optionCount);
            for (int optionIndex = 0; optionIndex < optionCount; optionIndex++) {
                buffer.writeItem(ingredient.options.get(optionIndex).copyWithCount(1));
            }
        }
    }

    /** 从网络缓冲区读取并校验配方材料描述。 */
    public static List<RecipeIngredientSpec> readList(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_INGREDIENTS) {
            throw new IllegalArgumentException("配方输入数量超出限制: " + size);
        }
        List<RecipeIngredientSpec> ingredients = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int amount = buffer.readVarInt();
            boolean wildcard = buffer.readBoolean();
            int optionCount = buffer.readVarInt();
            if (optionCount < 0 || optionCount > MAX_OPTIONS) {
                throw new IllegalArgumentException("配方候选数量超出限制: " + optionCount);
            }
            List<ItemStack> options = new ArrayList<>(optionCount);
            for (int optionIndex = 0; optionIndex < optionCount; optionIndex++) {
                ItemStack stack = buffer.readItem();
                if (!stack.isEmpty()) {
                    options.add(stack.copyWithCount(1));
                }
            }
            RecipeIngredientSpec ingredient = new RecipeIngredientSpec(amount, wildcard, options);
            if (!ingredient.isValid()) {
                throw new IllegalArgumentException("收到无效的配方输入");
            }
            ingredients.add(ingredient);
        }
        return List.copyOf(ingredients);
    }
}
