package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import com.lai.recipesender.model.BoundContainer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端把该玩家当前的绑定列表与「上次选择」整体推给客户端。
 *
 * <p>客户端只保留只读缓存，任何增删改都由服务端发起这次同步，客户端不做本地乐观更新。
 * 触发时机：玩家登录、切换维度、以及任何绑定被增/删/改/选择之后。
 */
public record SyncBoundContainersPacket(List<BoundContainer> bindings, Map<String, UUID> lastChoices) {
    /** 单次同步的条目上限，纯粹用于防御损坏数据包。 */
    public static final int MAX_BINDINGS = 4096;
    /** 「上次选择」记录的条数上限，理由同上。 */
    public static final int MAX_CHOICES = 4096;

    public SyncBoundContainersPacket {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        lastChoices = lastChoices == null ? Map.of() : Map.copyOf(lastChoices);
    }

    /** 编码绑定列表与「上次选择」映射。 */
    public static void encode(SyncBoundContainersPacket packet, FriendlyByteBuf buffer) {
        int size = Math.min(packet.bindings.size(), MAX_BINDINGS);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            packet.bindings.get(index).writeTo(buffer);
        }

        int choiceCount = Math.min(packet.lastChoices.size(), MAX_CHOICES);
        buffer.writeVarInt(choiceCount);
        int written = 0;
        for (Map.Entry<String, UUID> entry : packet.lastChoices.entrySet()) {
            if (written >= choiceCount) {
                break;
            }
            buffer.writeUtf(entry.getKey());
            buffer.writeUUID(entry.getValue());
            written++;
        }
    }

    /** 解码绑定列表与「上次选择」映射。 */
    public static SyncBoundContainersPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_BINDINGS) {
            throw new IllegalArgumentException("绑定数量超出限制: " + size);
        }
        List<BoundContainer> bindings = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            BoundContainer binding = BoundContainer.readFrom(buffer);
            if (binding != null) {
                bindings.add(binding);
            }
        }

        int choiceCount = buffer.readVarInt();
        if (choiceCount < 0 || choiceCount > MAX_CHOICES) {
            throw new IllegalArgumentException("「上次选择」数量超出限制: " + choiceCount);
        }
        Map<String, UUID> lastChoices = new LinkedHashMap<>();
        for (int index = 0; index < choiceCount; index++) {
            String routeKey = buffer.readUtf();
            lastChoices.put(routeKey, buffer.readUUID());
        }

        return new SyncBoundContainersPacket(bindings, lastChoices);
    }

    /** 交给客户端替换只读缓存。 */
    public static void handle(SyncBoundContainersPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientPacketHandler.acceptBoundContainers(packet.bindings(), packet.lastChoices()));
        context.setPacketHandled(true);
    }
}
