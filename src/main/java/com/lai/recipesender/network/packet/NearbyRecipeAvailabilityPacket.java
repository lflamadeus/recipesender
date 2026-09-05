package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端返回的周围配方统计结果。 */
public record NearbyRecipeAvailabilityPacket(long requestId, int availableBatches) {
    /** 编码服务端返回的请求编号和可用份数。 */
    public static void encode(NearbyRecipeAvailabilityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeVarInt(packet.availableBatches);
    }

    /** 解码服务端返回的请求编号和可用份数。 */
    public static NearbyRecipeAvailabilityPacket decode(FriendlyByteBuf buffer) {
        return new NearbyRecipeAvailabilityPacket(buffer.readLong(), buffer.readVarInt());
    }

    /** 将统计结果切回客户端主线程处理。 */
    public static void handle(NearbyRecipeAvailabilityPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.acceptNearbyAvailability(
                        packet.requestId, packet.availableBatches)));
        context.setPacketHandled(true);
    }
}
