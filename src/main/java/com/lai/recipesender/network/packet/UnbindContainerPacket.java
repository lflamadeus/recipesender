package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求删除一条绑定。
 *
 * <p>{@code cascade} 为 true 时连同**它的所有子节点**一起删（主容器 → 并列成员 → 从容器）。
 * 要删哪些由服务端自己按 parentId 链算：客户端手里那份列表可能比服务端旧，让客户端算会漏掉
 * 刚在别处绑上的子节点。
 */
public record UnbindContainerPacket(UUID id, boolean cascade) {

    /** 编码删除请求。 */
    public static void encode(UnbindContainerPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
        buffer.writeBoolean(packet.cascade);
    }

    /** 解码删除请求。 */
    public static UnbindContainerPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        boolean cascade = buffer.readBoolean();
        return new UnbindContainerPacket(id, cascade);
    }

    /** 交给服务端主线程删除并回推同步包。 */
    public static void handle(UnbindContainerPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.unbind(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
