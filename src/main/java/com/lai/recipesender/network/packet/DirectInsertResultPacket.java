package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.SourceScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端回执一次「直达」的结果。
 *
 * <p>份数用两个数字说清楚：{@code insertedBatches} 是真正放进目标的份数，
 * {@code requestedBatches} 是玩家松手时要求的份数，差额由玩家自己看得见——
 * 「能塞多少塞多少」必须配上明确的差额，否则玩家不知道是该再按一次还是去清理目标。
 *
 * @param requestId        客户端自增的请求号，服务端原样回传；客户端只接受当前值
 * @param bindingId        本次投放的目标绑定
 * @param status           校验/执行结果
 * @param insertedBatches  实际完整送达的份数
 * @param requestedBatches 玩家要求的份数
 * @param detail           补充说明的结构化原因；没有补充说明时为 {@code null}
 * @param scope            本次直达的取料范围，客户端据此说「从周围」还是「从中枢」
 */
public record DirectInsertResultPacket(long requestId, UUID bindingId, BoundStatus status,
                                       int insertedBatches, int requestedBatches, BoundDetail detail,
                                       SourceScope scope) {

    public DirectInsertResultPacket {
        scope = SourceScope.of(scope);
    }

    /** 兼容旧调用点（未带范围时按周围直达处理）。 */
    public DirectInsertResultPacket(long requestId, UUID bindingId, BoundStatus status,
                                    int insertedBatches, int requestedBatches, BoundDetail detail) {
        this(requestId, bindingId, status, insertedBatches, requestedBatches, detail, SourceScope.NEARBY);
    }

    /** 编码回执。 */
    public static void encode(DirectInsertResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeEnum(packet.status);
        buffer.writeVarInt(Math.max(0, packet.insertedBatches));
        buffer.writeVarInt(Math.max(0, packet.requestedBatches));
        buffer.writeBoolean(packet.detail != null);
        if (packet.detail != null) {
            buffer.writeEnum(packet.detail);
        }
        SourceScope.of(packet.scope).writeTo(buffer);
    }

    /** 解码回执。 */
    public static DirectInsertResultPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        BoundStatus status = buffer.readEnum(BoundStatus.class);
        int insertedBatches = buffer.readVarInt();
        int requestedBatches = buffer.readVarInt();
        BoundDetail detail = buffer.readBoolean() ? buffer.readEnum(BoundDetail.class) : null;
        return new DirectInsertResultPacket(requestId, bindingId, status, insertedBatches,
                requestedBatches, detail, SourceScope.readFrom(buffer));
    }

    /** 交给客户端界面提示。 */
    public static void handle(DirectInsertResultPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientPacketHandler.acceptDirectInsertResult(packet.requestId(),
                packet.bindingId(), packet.status(), packet.insertedBatches(),
                packet.requestedBatches(), packet.detail(), packet.scope()));
        context.setPacketHandled(true);
    }
}
