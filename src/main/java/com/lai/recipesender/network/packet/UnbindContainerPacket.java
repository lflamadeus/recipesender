package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** 客户端请求删除一条绑定。 */
public record UnbindContainerPacket(UUID id) {

    /** 编码删除请求。 */
    public static void encode(UnbindContainerPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
    }

    /** 解码删除请求。 */
    public static UnbindContainerPacket decode(FriendlyByteBuf buffer) {
        return new UnbindContainerPacket(buffer.readUUID());
    }

    /** 交给服务端主线程删除并回推同步包。 */
    public static void handle(UnbindContainerPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.unbind(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
