package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求修改一条已有绑定的名字。
 *
 * <p>刻意只带名称：S1 的可改字段只有名字。类别（{@code routeKeys}）是 S6 的事，
 * 容器关系（{@code role} / {@code parentId}）是 S2 的事，届时各加各的字段，
 * 不让一个包同时承担三种语义——服务端按包做校验时也能一眼看出在改什么。
 *
 * <p>服务端只用 id 定位、只取名称，其余字段一律以服务端自己存的那份为准。
 */
public record UpdateBindingPacket(UUID id, String name) {
    /** 名称长度上限，与 {@code BoundContainer.MAX_NAME_LENGTH} 保持一致。 */
    public static final int MAX_NAME_LENGTH = 32;

    /** 编码改名请求。 */
    public static void encode(UpdateBindingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
        buffer.writeUtf(packet.name == null ? "" : packet.name, MAX_NAME_LENGTH);
    }

    /** 解码改名请求。 */
    public static UpdateBindingPacket decode(FriendlyByteBuf buffer) {
        return new UpdateBindingPacket(buffer.readUUID(), buffer.readUtf(MAX_NAME_LENGTH));
    }

    /** 交给服务端主线程落盘并回推同步包。 */
    public static void handle(UpdateBindingPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.update(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
