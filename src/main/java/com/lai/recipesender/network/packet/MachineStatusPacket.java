package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import com.lai.recipesender.model.MachineState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端返回的「每台绑定机器现在忙不忙」。
 *
 * <p>每次都是全量：客户端拿它整体替换旧缓存，不做增量合并——增量一旦漏一条，界面就会永远显示
 * 上一次的忙/闲。
 *
 * <p>区块没加载、跨维度、方块实体没了的绑定<b>不发条目</b>：那些情况由客户端自己的存活性判定
 * （{@code BoundLiveness}）显示，这里再报一遍只会出现两个互相打架的标签。
 */
public record MachineStatusPacket(List<Entry> entries) {

    /** 条目上限：绑定容器本身有数量上限，这里只是给伪造的数据包兜底。 */
    private static final int MAX_ENTRIES = 256;

    /**
     * 表示「这台机器没有可调整的电路槽」的哨兵值。
     *
     * <p>必须与 {@link com.lai.recipesender.integration.gt.GtCircuitSupport#NO_CIRCUIT}（-1，表示电路槽里
     * 放着玩家自己的物品）以及 0（表示电路编号就是 0）都区分开：客户端做整体模式的 HUD 预检时，
     * 「没有电路槽」意味着这台机器不参与一致性判定，而「槽里是别的物品」意味着它必然不一致。
     */
    public static final int NO_CIRCUIT_SLOT = Integer.MIN_VALUE;

    /**
     * 一台绑定目标的状态。
     *
     * @param id      对应的 {@code BoundContainer.id()}
     * @param state   忙/闲/不是机器
     * @param circuit 当前电路编号：0-{@link com.lai.recipesender.integration.gt.GtCircuitSupport#MAX_CIRCUIT}
     *                是编号本身，{@link com.lai.recipesender.integration.gt.GtCircuitSupport#NO_CIRCUIT}
     *                表示槽里放着别的物品，{@link #NO_CIRCUIT_SLOT} 表示没有可调整的电路槽
     */
    public record Entry(UUID id, MachineState state, int circuit) {
    }

    /** 编码状态表。 */
    public static void encode(MachineStatusPacket packet, FriendlyByteBuf buffer) {
        List<Entry> entries = packet.entries == null ? List.of() : packet.entries;
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeUUID(entry.id());
            buffer.writeEnum(entry.state());
            buffer.writeVarInt(entry.circuit());
        }
    }

    /** 解码状态表并校验条目数量。 */
    public static MachineStatusPacket decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("机器状态条目数量超出限制: " + size);
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(buffer.readUUID(), buffer.readEnum(MachineState.class), buffer.readVarInt()));
        }
        return new MachineStatusPacket(List.copyOf(entries));
    }

    /** 切回客户端主线程交给界面缓存。 */
    public static void handle(MachineStatusPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.acceptMachineStatus(packet.entries)));
        context.setPacketHandled(true);
    }
}
