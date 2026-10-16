package com.lai.recipesender.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方里的一项流体输入需求：每一份需要多少 mB，以及可以接受的流体候选。
 *
 * <p>与 {@link RecipeIngredientSpec} 是并列的两条路，刻意不合并：物品那一侧靠
 * {@code ItemStack} 的 NBT 语义匹配，流体这一侧靠 {@code FluidStack.isFluidEqual}
 * （流体 + NBT），两者的候选数量级与校验边界都不同。配方「一份」的定义是<b>物品和流体都齐</b>，
 * 所以只要配方带流体，份数就必须同时受流体约束。
 *
 * <p>候选一律按 1 mB 传，用量由 {@code amountPerBatch} 决定：流体的 NBT 千差万别
 * （格雷的液体也带 tag），但「这是什么流体」与「要多少」必须分开表达。
 *
 * <p>线格式自己手写：Forge 的 {@code FriendlyByteBuf#writeFluidStack} 在部分版本上不存在，
 * 依赖它会把整个协议绑死在某个 Forge 补丁上。这里只写流体注册名 + 可选 NBT。
 */
public record RecipeFluidSpec(int amountPerBatch, List<FluidStack> options) {
    /** 单个配方允许携带的最大流体项数。 */
    public static final int MAX_FLUIDS = 16;
    /** 单项流体允许的候选数上限。 */
    public static final int MAX_OPTIONS = 8;
    /** 单项流体每份用量上限（mB）：够覆盖格雷的巨型配方，同时挡住明显异常的数字。 */
    public static final int MAX_AMOUNT_PER_BATCH = 1_000_000;

    /** 规范化为不可变副本：调用方传进来的列表之后可以被随意改。 */
    public RecipeFluidSpec {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 这一项是否可用：用量在范围内，且至少有一个非空候选。 */
    public boolean isValid() {
        if (amountPerBatch <= 0 || amountPerBatch > MAX_AMOUNT_PER_BATCH
                || options.isEmpty() || options.size() > MAX_OPTIONS) {
            return false;
        }
        for (FluidStack option : options) {
            if (option == null || option.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 流体的「同一种」判定：流体类型相同、NBT 也相同，不比较数量。
     *
     * <p>物品那一侧用的是 {@code ItemStack.isSameItemSameTags}，语义一致。
     * 这里自己写而不调 {@code FluidStack} 的现成方法，是为了让匹配规则只有一处定义
     * （中枢内容匹配、目标罐匹配、配方候选匹配都走这里）。
     */
    public static boolean sameFluid(FluidStack first, FluidStack second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getFluid() != second.getFluid()) {
            return false;
        }
        CompoundTag firstTag = first.getTag();
        CompoundTag secondTag = second.getTag();
        return firstTag == null ? secondTag == null : firstTag.equals(secondTag);
    }

    /** 给定流体是否满足这一项（流体 + NBT 相等）。 */
    public boolean matches(FluidStack candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (FluidStack option : options) {
            if (sameFluid(option, candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 写出一整张流体需求表。 */
    public static void writeList(FriendlyByteBuf buffer, List<RecipeFluidSpec> specs) {
        List<RecipeFluidSpec> list = specs == null ? List.of() : specs;
        buffer.writeVarInt(list.size());
        for (RecipeFluidSpec spec : list) {
            buffer.writeVarInt(spec.amountPerBatch());
            buffer.writeVarInt(spec.options().size());
            for (FluidStack option : spec.options()) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(option.getFluid());
                // 注册表里查不到的流体不该出现在这里；真出现时写个空串，读的那侧会跳过它。
                buffer.writeResourceLocation(id == null
                        ? ResourceLocation.tryParse("minecraft:empty") : id);
                buffer.writeNbt(option.getTag());
            }
        }
    }

    /** 读回一整张流体需求表，越界一律抛异常（由网络的异常处理断开这次连接）。 */
    public static List<RecipeFluidSpec> readList(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_FLUIDS) {
            throw new IllegalArgumentException("配方流体数量超出限制: " + size);
        }
        List<RecipeFluidSpec> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            int amountPerBatch = buffer.readVarInt();
            int optionCount = buffer.readVarInt();
            if (optionCount < 0 || optionCount > MAX_OPTIONS) {
                throw new IllegalArgumentException("配方流体候选数量超出限制: " + optionCount);
            }
            List<FluidStack> options = new ArrayList<>(optionCount);
            for (int option = 0; option < optionCount; option++) {
                ResourceLocation id = buffer.readResourceLocation();
                CompoundTag tag = buffer.readNbt();
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(id);
                // 两侧模组不一致时可能认不出流体：跳过它，而不是让整包失败。
                if (fluid != null) {
                    options.add(new FluidStack(fluid, 1, tag));
                }
            }
            RecipeFluidSpec spec = new RecipeFluidSpec(amountPerBatch, options);
            if (!spec.isValid()) {
                throw new IllegalArgumentException("收到无效的配方流体需求");
            }
            result.add(spec);
        }
        return result;
    }
}
