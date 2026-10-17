package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import com.lai.recipesender.model.BoundDetail;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端返回的周围配方统计结果。
 *
 * <p>{@code reason} 解释「为什么只有这么少」：流体中枢没绑定、中枢里的液体不够……没有原因就是
 * {@code null}。份数为 0 时客户端拿它替换掉笼统的「周围没有可用的材料」，玩家才知道该去补什么。
 */
public record NearbyRecipeAvailabilityPacket(long requestId, int availableBatches, BoundDetail reason) {

    private static final int NO_REASON = -1;

    /** 编码服务端返回的请求编号、可用份数与原因。 */
    public static void encode(NearbyRecipeAvailabilityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeVarInt(packet.availableBatches);
        buffer.writeByte(packet.reason == null ? NO_REASON : packet.reason.ordinal());
    }

    /** 解码服务端返回的请求编号、可用份数与原因。 */
    public static NearbyRecipeAvailabilityPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        int availableBatches = buffer.readVarInt();
        // 原因是后加的字段：读不到、或者编号认不出（跨版本）都当作没有原因。
        BoundDetail reason = null;
        if (buffer.readableBytes() > 0) {
            int ordinal = buffer.readByte();
            BoundDetail[] values = BoundDetail.values();
            if (ordinal >= 0 && ordinal < values.length) {
                reason = values[ordinal];
            }
        }
        return new NearbyRecipeAvailabilityPacket(requestId, availableBatches, reason);
    }

    /** 将统计结果切回客户端主线程处理。 */
    public static void handle(NearbyRecipeAvailabilityPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.acceptNearbyAvailability(
                        packet.requestId, packet.availableBatches, packet.reason)));
        context.setPacketHandled(true);
    }
}
