package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.MachineCircuitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端请求把当前打开容器的编程电路置空。
 *
 * <p>与 {@link SetContainerCircuitPacket} 分开成独立数据包：写入某个编号是配方的明确要求，
 * 服务端照写；置空则只该发生在“悬停的是不使用电路的格雷配方”时，服务端需要能区分这两种意图，
 * 所以不能复用同一个包。
 *
 * @param containerId 玩家当前打开的容器菜单编号，服务端据此确认目标没有换过
 */
public record ClearContainerCircuitPacket(int containerId) {

    /** 编码置空请求。 */
    public static void encode(ClearContainerCircuitPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId);
    }

    /** 解码置空请求。 */
    public static ClearContainerCircuitPacket decode(FriendlyByteBuf buffer) {
        return new ClearContainerCircuitPacket(buffer.readVarInt());
    }

    /** 将置空请求交给服务端主线程处理。 */
    public static void handle(ClearContainerCircuitPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> MachineCircuitService.clear(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
