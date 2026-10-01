package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求绑定一个方块坐标。
 *
 * <p>刻意不接收客户端给的图标：图标一律由服务端从该坐标的方块状态推导
 * （{@code blockState.getBlock().getDefaultInstance()}），客户端数据不参与任何落盘字段的取值。
 *
 * <p>名称留空时由服务端生成默认名。
 *
 * <p>{@code role} 是容器关系（主容器 / 并列成员 / 从容器）；{@code parentId} 只在角色不是主容器时使用，
 * 指向挂靠的主容器。两者都是<b>请求</b>，服务端会重新校验一遍（父容器必须真的存在且是主容器）。
 */
public record BindContainerPacket(ResourceLocation dimension, BlockPos pos, String name,
                                  BoundContainer.Role role, UUID parentId) {
    /** 名称长度上限，与 {@code BoundContainer.MAX_NAME_LENGTH} 保持一致。 */
    public static final int MAX_NAME_LENGTH = 32;

    /** 编码绑定请求。 */
    public static void encode(BindContainerPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.dimension);
        buffer.writeBlockPos(packet.pos);
        buffer.writeUtf(packet.name == null ? "" : packet.name, MAX_NAME_LENGTH);
        buffer.writeEnum(packet.role == null ? BoundContainer.Role.MASTER : packet.role);
        buffer.writeBoolean(packet.parentId != null);
        if (packet.parentId != null) {
            buffer.writeUUID(packet.parentId);
        }
    }

    /** 解码绑定请求。 */
    public static BindContainerPacket decode(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        BlockPos pos = buffer.readBlockPos();
        String name = buffer.readUtf(MAX_NAME_LENGTH);
        BoundContainer.Role role = buffer.readEnum(BoundContainer.Role.class);
        UUID parentId = buffer.readBoolean() ? buffer.readUUID() : null;
        return new BindContainerPacket(dimension, pos, name, role, parentId);
    }

    /** 交给服务端主线程落盘并回推同步包。 */
    public static void handle(BindContainerPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.bind(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
