package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端回执一次「发送到已绑定容器」的结果。
 *
 * <p>失败必须带原因回给玩家：这个功能的落点在玩家看不到的地方（远程方块），
 * 没有回执就只剩「点了没反应」，排查只能靠翻服务端日志。
 *
 * @param requestId        客户端自增的请求号，服务端原样回传；客户端只接受当前值
 * @param bindingId        本次发送的目标绑定
 * @param status           解析/执行结果
 * @param insertedBatches  实际完整送达的份数
 * @param requestedBatches 玩家要求的份数
 * @param overflowBatches  其中落到<b>从容器</b>的份数（主容器与并列成员都放满后溢出的部分）
 * @param detailKey        补充说明的语言键；没有补充说明时为 {@code null}
 */
public record BoundInsertResultPacket(long requestId, UUID bindingId, BoundStatus status,
                                      int insertedBatches, int requestedBatches, int overflowBatches,
                                      String detailKey) {

    /** 编码回执。 */
    public static void encode(BoundInsertResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeEnum(packet.status);
        buffer.writeVarInt(Math.max(0, packet.insertedBatches));
        buffer.writeVarInt(Math.max(0, packet.requestedBatches));
        buffer.writeVarInt(Math.max(0, packet.overflowBatches));
        buffer.writeBoolean(packet.detailKey != null);
        if (packet.detailKey != null) {
            buffer.writeUtf(packet.detailKey, 128);
        }
    }

    /** 解码回执。 */
    public static BoundInsertResultPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        BoundStatus status = buffer.readEnum(BoundStatus.class);
        int insertedBatches = buffer.readVarInt();
        int requestedBatches = buffer.readVarInt();
        int overflowBatches = buffer.readVarInt();
        String detailKey = buffer.readBoolean() ? buffer.readUtf(128) : null;
        return new BoundInsertResultPacket(requestId, bindingId, status, insertedBatches,
                requestedBatches, overflowBatches, detailKey);
    }

    /** 交给客户端界面提示。 */
    public static void handle(BoundInsertResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientPacketHandler.acceptBoundInsertResult(packet.requestId(),
                packet.bindingId(), packet.status(), packet.insertedBatches(),
                packet.requestedBatches(), packet.overflowBatches(), packet.detailKey()));
        context.setPacketHandled(true);
    }
}
