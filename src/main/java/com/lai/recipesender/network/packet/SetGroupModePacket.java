package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求切换一条绑定的<b>组模式</b>（独立 / 整体）。
 *
 * <p>组模式只对主容器有意义，所以服务端只用 id 定位、并且要求那条绑定确实是主容器：
 * 两种模式描述的是「这一组机器怎么协同」，挂在别人下面的成员自己说不清这件事。
 *
 * <p>与改关系（{@link UpdateBindingRelationPacket}）分开成两个包，是因为它不会改变组的结构——
 * 只改变组内机器怎么被使用：独立模式各机器电路互不相干、在忙的跳过；整体模式整组电路必须一致、
 * 在忙的也接料。
 */
public record SetGroupModePacket(UUID id, BoundContainer.GroupMode mode) {

    /** 编码切换请求；{@code mode} 为 null 时按默认的独立模式处理。 */
    public static void encode(SetGroupModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
        buffer.writeEnum(packet.mode == null ? BoundContainer.GroupMode.INDEPENDENT : packet.mode);
    }

    /** 解码切换请求。 */
    public static SetGroupModePacket decode(FriendlyByteBuf buffer) {
        return new SetGroupModePacket(buffer.readUUID(),
                buffer.readEnum(BoundContainer.GroupMode.class));
    }

    /** 交给服务端主线程落盘并回推同步包。 */
    public static void handle(SetGroupModePacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.setGroupMode(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
