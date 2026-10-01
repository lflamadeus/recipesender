package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.RecipeInsertionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端提交一次投放所需的物品，服务端会重新检查背包、菜单和目标槽位。
 * 数量单独使用 VarInt 编码，绕过原版 writeItem 的单字节数量限制。
 */
public record InsertRecipeItemsPacket(int containerId, List<ItemStack> requirements) {
    public static final int MAX_REQUIREMENTS = 128;
    public static final int MAX_ITEMS_PER_REQUIREMENT = 4096;

    /** 编码正向材料投放请求。 */
    public static void encode(InsertRecipeItemsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId);
        writeRequirements(buffer, packet.requirements);
    }

    /**
     * 写入一份材料需求列表。
     * 数量单独使用 VarInt 编码，绕过原版 writeItem 的单字节数量限制。
     * 正向投放与「发送到已绑定容器」共用，保证两条通路的材料编码规则不会各写一份。
     */
    public static void writeRequirements(FriendlyByteBuf buffer, List<ItemStack> requirements) {
        int size = Math.min(requirements.size(), MAX_REQUIREMENTS);
        buffer.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = requirements.get(i);
            int count = Math.min(stack.getCount(), MAX_ITEMS_PER_REQUIREMENT);
            // Vanilla writeItem 使用单字节数量；先写一份物品信息，再单独写 VarInt 数量。
            buffer.writeItem(stack.copyWithCount(1));
            buffer.writeVarInt(count);
        }
    }

    /** 读取一份材料需求列表。 */
    public static List<ItemStack> readRequirements(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_REQUIREMENTS) {
            throw new IllegalArgumentException("Too many recipe requirements: " + size);
        }

        List<ItemStack> requirements = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = buffer.readItem();
            int count = buffer.readVarInt();
            if (!stack.isEmpty()) {
                stack.setCount(Math.min(count, MAX_ITEMS_PER_REQUIREMENT));
            }
            requirements.add(stack);
        }
        return requirements;
    }

    /** 解码正向材料投放请求。 */
    public static InsertRecipeItemsPacket decode(FriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        return new InsertRecipeItemsPacket(containerId, readRequirements(buffer));
    }

    /** 将正向投放请求交给服务端主线程处理。 */
    public static void handle(InsertRecipeItemsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 所有菜单操作都必须在逻辑服务端主线程执行，避免并发修改容器状态。
        context.enqueueWork(() -> RecipeInsertionService.insert(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
