package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.MachineCircuitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端请求把当前打开容器的编程电路调整到配方要求的编号。
 *
 * <p>与材料投放分开成独立数据包：电路只是机器配置，即使目标容器不支持也只是被服务端忽略，
 * 不会影响材料投放本身的结果。
 */
public record SetContainerCircuitPacket(int containerId, int circuit) {

    /** 编码电路调整请求。 */
    public static void encode(SetContainerCircuitPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId);
        buffer.writeVarInt(packet.circuit);
    }

    /** 解码电路调整请求。 */
    public static SetContainerCircuitPacket decode(FriendlyByteBuf buffer) {
        return new SetContainerCircuitPacket(buffer.readVarInt(), buffer.readVarInt());
    }

    /** 将电路调整请求交给服务端主线程处理。 */
    public static void handle(SetContainerCircuitPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> MachineCircuitService.apply(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
