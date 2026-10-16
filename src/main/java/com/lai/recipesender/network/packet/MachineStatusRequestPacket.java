package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.MachineStatusService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端向服务端要一次「每台绑定机器现在忙不忙」。
 *
 * <p>没有字段：查谁的绑定由服务端按发起请求的玩家自己决定。让客户端指定目标等于开了「问别人的
 * 机器」这个口子，没有理由开。
 */
public record MachineStatusRequestPacket() {

    /** 无字段，占位实现（Forge 的编解码器仍然需要一个方法引用）。 */
    public static void encode(MachineStatusRequestPacket packet, FriendlyByteBuf buffer) {
    }

    /** 无字段。 */
    public static MachineStatusRequestPacket decode(FriendlyByteBuf buffer) {
        return new MachineStatusRequestPacket();
    }

    /** 在服务端主线程上算一次状态并回执。 */
    public static void handle(MachineStatusRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> MachineStatusService.request(context.getSender()));
        context.setPacketHandled(true);
    }
}
