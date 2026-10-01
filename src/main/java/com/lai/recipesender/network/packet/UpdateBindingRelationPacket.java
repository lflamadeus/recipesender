package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求修改一条已有绑定的容器关系（角色 + 父容器）。
 *
 * <p>刻意只带关系：名字走 {@link UpdateBindingPacket}，配方类别（{@code routeKeys}）是 S6 的事。
 * 三种语义各用各的包，服务端按包做校验时一眼就能看出在改什么。
 *
 * <p>服务端只用 id 定位，角色与父容器都要重新校验：
 * 父容器必须存在、必须是主容器、不能是自己；主容器不能挂到别的容器下面。
 */
public record UpdateBindingRelationPacket(UUID id, BoundContainer.Role role, UUID parentId) {

    /** 编码改关系请求。 */
    public static void encode(UpdateBindingRelationPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
        buffer.writeEnum(packet.role == null ? BoundContainer.Role.MASTER : packet.role);
        buffer.writeBoolean(packet.parentId != null);
        if (packet.parentId != null) {
            buffer.writeUUID(packet.parentId);
        }
    }

    /** 解码改关系请求。 */
    public static UpdateBindingRelationPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        BoundContainer.Role role = buffer.readEnum(BoundContainer.Role.class);
        UUID parentId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new UpdateBindingRelationPacket(id, role, parentId);
    }

    /** 交给服务端主线程落盘并回推同步包。 */
    public static void handle(UpdateBindingRelationPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.updateRelation(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
