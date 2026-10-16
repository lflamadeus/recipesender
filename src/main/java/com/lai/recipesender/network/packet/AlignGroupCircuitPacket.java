package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求「一键对齐电路」：把整组机器的电路写成同一个值。
 *
 * <p>整体模式下组内电路必须一致，否则整单取消。逐台机器跑过去手动改很烦，而且组里可能有十几台，
 * 所以给一个按钮：服务端按主容器解析出整组（主容器 + 可用并列成员）逐个写入，再回推一条提示说
 * 哪几台没写进去。
 *
 * <p>{@code circuit < 0} 表示<b>清空</b>电路槽（配方不需要电路时用）；0-32 表示设置成该编号。
 * 与 {@link SetContainerCircuitPacket} 一样，服务端会重新校验范围，不信客户端。
 *
 * <p>刻意只带主容器 id：组内成员由服务端现场解析——客户端手里的列表可能已经过期，而且服务端本来
 * 就要重新校验每台机器的可用性。
 */
public record AlignGroupCircuitPacket(UUID masterId, int circuit) {

    /** 编码对齐请求。 */
    public static void encode(AlignGroupCircuitPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.masterId);
        buffer.writeVarInt(packet.circuit);
    }

    /** 解码对齐请求。 */
    public static AlignGroupCircuitPacket decode(FriendlyByteBuf buffer) {
        return new AlignGroupCircuitPacket(buffer.readUUID(), buffer.readVarInt());
    }

    /** 交给服务端主线程写电路并回推提示。 */
    public static void handle(AlignGroupCircuitPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.alignGroupCircuit(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
