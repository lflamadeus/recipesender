package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 批量绑定的汇总回执：成功几条、哪几条失败、各自为什么。
 *
 * <p>失败项必须逐条带坐标回来——整批静默失败会让玩家以为全绑上了。原因用语言键，
 * 由客户端按自己的语言翻。
 */
public record BatchBindResultPacket(UUID requestId, int created, List<Failure> failures) {

    /** 一条失败：哪个坐标、什么原因（语言键）。 */
    public record Failure(BlockPos pos, String reasonKey) {
    }

    /** 编码。 */
    public static void encode(BatchBindResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeVarInt(packet.created);
        List<Failure> failures = packet.failures == null ? List.of() : packet.failures;
        buffer.writeVarInt(failures.size());
        for (Failure failure : failures) {
            buffer.writeBlockPos(failure.pos());
            buffer.writeUtf(failure.reasonKey() == null ? "" : failure.reasonKey(), 96);
        }
    }

    /** 解码。 */
    public static BatchBindResultPacket decode(FriendlyByteBuf buffer) {
        UUID requestId = buffer.readUUID();
        int created = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > BatchBindPacket.MAX_ENTRIES) {
            throw new IllegalArgumentException("批量绑定失败项数量超出限制: " + count);
        }
        List<Failure> failures = new ArrayList<>(Math.min(count, 16));
        for (int index = 0; index < count; index++) {
            BlockPos pos = buffer.readBlockPos();
            String reasonKey = buffer.readUtf(96);
            failures.add(new Failure(pos, reasonKey));
        }
        return new BatchBindResultPacket(requestId, created, failures);
    }

    /** 交给客户端处理。 */
    public static void handle(BatchBindResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientPacketHandler.acceptBatchBindResult(
                packet.requestId(), packet.created(), packet.failures()));
        context.setPacketHandled(true);
    }
}
