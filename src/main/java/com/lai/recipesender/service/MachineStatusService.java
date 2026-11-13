package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.integration.gt.MachineStateProbe;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.MachineState;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.MachineStatusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 「管理界面每秒刷新每台机器的忙/闲」的服务端一侧。
 *
 * <p>状态只有服务端算得准：客户端的能力表可能不全，而且探测本身要读服务端的机器实例（配方逻辑
 * 是服务端对象）。所以界面每秒发一次请求，这里算完整表回执，客户端整体替换缓存。
 *
 * <p>这条通路只读、不改任何东西，也没有副作用；真正决定「投不投料」的是发送与直达各自在
 * 执行那一刻的现场探测（{@link MachineStateProbe}），界面上的数字只是给玩家看的预览。
 */
public final class MachineStatusService {

    private MachineStatusService() {
    }

    /** 算一次该玩家全部绑定的机器状态并回执。 */
    public static void request(ServerPlayer player) {
        if (player == null) {
            return;
        }
        List<BoundContainer> bindings = BoundContainerService.list(player);
        List<MachineStatusPacket.Entry> entries = new ArrayList<>(bindings.size());
        for (BoundContainer binding : bindings) {
            BlockEntity blockEntity = loadedBlockEntity(player, binding);
            if (blockEntity == null) {
                // 维度不存在、区块没加载、方块实体没了：连「是不是机器」都谈不上。这些情况由客户端
                // 自己的存活性判定显示，这里不发条目，免得一行上出现两个互相打架的标签。
                continue;
            }
            entries.add(new MachineStatusPacket.Entry(binding.id(),
                    MachineStateProbe.stateOf(blockEntity), circuitOf(blockEntity)));
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MachineStatusPacket(List.copyOf(entries)));
    }

    /**
     * 这台机器当前的电路编号，供客户端做整体模式的 HUD 预检与管理界面显示。
     *
     * <p>只读，不改任何东西。没有可调整电路槽（普通箱子、非格雷机器、反射探不到）时回
     * {@link MachineStatusPacket#NO_CIRCUIT_SLOT}——客户端据此知道这台机器不参与电路一致性判定。
     */
    private static int circuitOf(BlockEntity blockEntity) {
        Integer circuit = GtCircuitSupport.currentCircuit(blockEntity);
        return circuit == null ? MachineStatusPacket.NO_CIRCUIT_SLOT : circuit;
    }

    /**
     * 取绑定坐标上的方块实体；<b>绝不触发区块加载</b>。
     *
     * <p>{@code Level.getBlockEntity} 会把没加载的区块读进来。管理界面每秒问一次、一次几十个坐标，
     * 隔着几百格刷上一会儿就能把服务端拖进磁盘 IO。所以先取绑定自己的维度、再判 {@code isLoaded}
     * ——与 {@code BoundTargetResolver.locate}、客户端 {@code BoundLiveness} 是同一条纪律。
     *
     * <p>取的是<b>绑定自己的维度</b>而不是玩家所在维度：直达允许跨维度之后，别的维度的绑定
     * 同样需要显示忙闲与电路。这里只读已加载的区块，跨维度也不会造成额外加载。
     */
    private static BlockEntity loadedBlockEntity(ServerPlayer player, BoundContainer binding) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = server.getLevel(binding.dimension());
        if (level == null || !level.isLoaded(binding.pos())) {
            return null;
        }
        return level.getBlockEntity(binding.pos());
    }
}
