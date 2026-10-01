package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundInsertionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端提交一次「发送到已绑定容器」。
 *
 * <p>与 {@link InsertRecipeItemsPacket} 的区别只有一个：落点由 {@code bindingId} 指定，
 * 而不是「玩家当前打开的菜单」。因此服务端必须自己按坐标解析目标，并且**不能**依赖
 * 玩家开着那个容器的界面。
 */
public record InsertRecipeItemsToBoundPacket(long requestId, UUID bindingId,
                                             List<ItemStack> requirements, int batches) {

    /** 编码投放请求；材料编码规则与正向投放完全一致。 */
    public static void encode(InsertRecipeItemsToBoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeVarInt(Math.max(0, packet.batches));
        InsertRecipeItemsPacket.writeRequirements(buffer, packet.requirements);
    }

    /** 解码投放请求。 */
    public static InsertRecipeItemsToBoundPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        int batches = buffer.readVarInt();
        List<ItemStack> requirements = InsertRecipeItemsPacket.readRequirements(buffer);
        return new InsertRecipeItemsToBoundPacket(requestId, bindingId, requirements, batches);
    }

    /** 交给服务端主线程校验并执行。 */
    public static void handle(InsertRecipeItemsToBoundPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundInsertionService.insert(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
