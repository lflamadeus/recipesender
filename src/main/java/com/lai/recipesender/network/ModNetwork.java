package com.lai.recipesender.network;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.network.packet.AlignGroupCircuitPacket;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.BoundInsertResultPacket;
import com.lai.recipesender.network.packet.BoundNoticePacket;
import com.lai.recipesender.network.packet.ClearContainerCircuitPacket;
import com.lai.recipesender.network.packet.DirectInsertResultPacket;
import com.lai.recipesender.network.packet.DirectInsertToBoundPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsToBoundPacket;
import com.lai.recipesender.network.packet.MachineStatusPacket;
import com.lai.recipesender.network.packet.MachineStatusRequestPacket;
import com.lai.recipesender.network.packet.NearbyRecipeAvailabilityPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import com.lai.recipesender.network.packet.SelectBoundTargetPacket;
import com.lai.recipesender.network.packet.SetGroupModePacket;
import com.lai.recipesender.network.packet.SyncBoundContainersPacket;
import com.lai.recipesender.network.packet.UnbindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRelationPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 集中注册客户端与服务端之间的数据包。 */
public final class ModNetwork {
    /**
     * 协议版本。改动任何数据包的字段顺序或增删字段都必须改这个值，否则新旧客户端与服务端会
     * 按各自的读法解析同一个字节流，静默读出垃圾数据。
     *
     * <p>1.0.23 由 8 升到 9：{@code InsertRecipeItemsToBoundPacket} 尾部加了电路编号与
     * 「是否格雷配方」两个字段。
     *
     * <p>1.0.25 由 9 升到 10：新增 {@code UpdateBindingRoutesPacket}（配方类别选择器）。
     *
     * <p>1.0.27 由 10 升到 11：{@code BoundNoticePacket} 尾部加了提示严重程度（失败红 / 警告黄），
     * {@code BoundInsertResultPacket} 的补充说明由语言键字符串改成 {@code BoundDetail} 枚举。
     *
     * <p>1.0.30 由 11 升到 12：新增直达模式的两个包（{@code DirectInsertToBoundPacket}、
     * {@code DirectInsertResultPacket}），{@code NearbyRecipeQueryPacket} 在请求号之后加了
     * 「只统计周围容器」标志。旧客户端发来的统计请求少一个布尔，会从这个位置开始错位读取
     * 配方输入列表，因此必须靠协议版本把新旧挡开。
     *
     * <p>1.0.32 由 12 升到 13：{@code BoundStatus} 尾部新增 {@code TOO_FAR}
     * （配方管理距离）。枚举是按序号写线上的，旧客户端读到新序号会直接抛异常，
     * 所以即使包结构没变也要升版本。
     *
     * <p>1.0.32 由 13 升到 14：流体中枢。{@code BoundContainer} 在角色之后多了「用途」
     * （物品目标 / 流体中枢）、{@code BindContainerPacket} 同步多了这个字段、
     * {@code BoundStatus} 尾部又加了 {@code NO_FLUID_HANDLER}、
     * {@code DirectInsertToBoundPacket} 在配方输入之后多了流体需求表，
     * {@code NearbyRecipeQueryPacket} 去掉了「只统计周围容器」标志（反转与直达现在统计同一批来源）。
     * 任一处错位都会读出垃圾数据，必须整体挡开。
     *
     * <p>1.0.34 由 14 升到 15：新增机器状态查询的两个包（{@code MachineStatusRequestPacket}、
     * {@code MachineStatusPacket}），{@code BoundDetail} 尾部加了 {@code ALL_BUSY} 与
     * {@code MACHINES_BUSY}——枚举是按序号写线上的，加值就必须升版本。
     *
     * <p>1.0.38 由 15 升到 16：并列组多了「组模式」（独立 / 整体）。{@code BoundContainer} 尾部
     * 多了组模式字段（跟着 {@code SyncBoundContainersPacket} 的 {@code writeTo}/{@code readFrom}
     * 上线），新增 {@code SetGroupModePacket} 与 {@code AlignGroupCircuitPacket} 两个包，
     * {@code BoundDetail} 尾部加了 {@code GROUP_CIRCUIT_MISMATCH}，{@code MachineStatusPacket}
     * 每条多了电路编号。任一处错位都会读出垃圾数据，必须整体挡开。
     */
    private static final String PROTOCOL_VERSION = "16";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(RecipeSenderMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    private ModNetwork() {
    }

    /** 按固定方向注册全部网络数据包。 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(InsertRecipeItemsPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(InsertRecipeItemsPacket::encode)
                .decoder(InsertRecipeItemsPacket::decode)
                .consumerMainThread(InsertRecipeItemsPacket::handle)
                .add();
        CHANNEL.messageBuilder(NearbyRecipeQueryPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NearbyRecipeQueryPacket::encode)
                .decoder(NearbyRecipeQueryPacket::decode)
                .consumerMainThread(NearbyRecipeQueryPacket::handle)
                .add();
        CHANNEL.messageBuilder(NearbyRecipeAvailabilityPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(NearbyRecipeAvailabilityPacket::encode)
                .decoder(NearbyRecipeAvailabilityPacket::decode)
                .consumerMainThread(NearbyRecipeAvailabilityPacket::handle)
                .add();
        CHANNEL.messageBuilder(NearbyRecipePullPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NearbyRecipePullPacket::encode)
                .decoder(NearbyRecipePullPacket::decode)
                .consumerMainThread(NearbyRecipePullPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetContainerCircuitPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetContainerCircuitPacket::encode)
                .decoder(SetContainerCircuitPacket::decode)
                .consumerMainThread(SetContainerCircuitPacket::handle)
                .add();
        CHANNEL.messageBuilder(ClearContainerCircuitPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClearContainerCircuitPacket::encode)
                .decoder(ClearContainerCircuitPacket::decode)
                .consumerMainThread(ClearContainerCircuitPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncBoundContainersPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncBoundContainersPacket::encode)
                .decoder(SyncBoundContainersPacket::decode)
                .consumerMainThread(SyncBoundContainersPacket::handle)
                .add();
        CHANNEL.messageBuilder(BindContainerPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindContainerPacket::encode)
                .decoder(BindContainerPacket::decode)
                .consumerMainThread(BindContainerPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindContainerPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnbindContainerPacket::encode)
                .decoder(UnbindContainerPacket::decode)
                .consumerMainThread(UnbindContainerPacket::handle)
                .add();
        CHANNEL.messageBuilder(InsertRecipeItemsToBoundPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(InsertRecipeItemsToBoundPacket::encode)
                .decoder(InsertRecipeItemsToBoundPacket::decode)
                .consumerMainThread(InsertRecipeItemsToBoundPacket::handle)
                .add();
        CHANNEL.messageBuilder(BoundInsertResultPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoundInsertResultPacket::encode)
                .decoder(BoundInsertResultPacket::decode)
                .consumerMainThread(BoundInsertResultPacket::handle)
                .add();
        CHANNEL.messageBuilder(UpdateBindingPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdateBindingPacket::encode)
                .decoder(UpdateBindingPacket::decode)
                .consumerMainThread(UpdateBindingPacket::handle)
                .add();
        CHANNEL.messageBuilder(SelectBoundTargetPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SelectBoundTargetPacket::encode)
                .decoder(SelectBoundTargetPacket::decode)
                .consumerMainThread(SelectBoundTargetPacket::handle)
                .add();
        CHANNEL.messageBuilder(UpdateBindingRelationPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdateBindingRelationPacket::encode)
                .decoder(UpdateBindingRelationPacket::decode)
                .consumerMainThread(UpdateBindingRelationPacket::handle)
                .add();
        CHANNEL.messageBuilder(UpdateBindingRoutesPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdateBindingRoutesPacket::encode)
                .decoder(UpdateBindingRoutesPacket::decode)
                .consumerMainThread(UpdateBindingRoutesPacket::handle)
                .add();
        CHANNEL.messageBuilder(BoundNoticePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BoundNoticePacket::encode)
                .decoder(BoundNoticePacket::decode)
                .consumerMainThread(BoundNoticePacket::handle)
                .add();
        CHANNEL.messageBuilder(DirectInsertToBoundPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DirectInsertToBoundPacket::encode)
                .decoder(DirectInsertToBoundPacket::decode)
                .consumerMainThread(DirectInsertToBoundPacket::handle)
                .add();
        CHANNEL.messageBuilder(DirectInsertResultPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DirectInsertResultPacket::encode)
                .decoder(DirectInsertResultPacket::decode)
                .consumerMainThread(DirectInsertResultPacket::handle)
                .add();
        CHANNEL.messageBuilder(MachineStatusRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MachineStatusRequestPacket::encode)
                .decoder(MachineStatusRequestPacket::decode)
                .consumerMainThread(MachineStatusRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(MachineStatusPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MachineStatusPacket::encode)
                .decoder(MachineStatusPacket::decode)
                .consumerMainThread(MachineStatusPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetGroupModePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetGroupModePacket::encode)
                .decoder(SetGroupModePacket::decode)
                .consumerMainThread(SetGroupModePacket::handle)
                .add();
        CHANNEL.messageBuilder(AlignGroupCircuitPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AlignGroupCircuitPacket::encode)
                .decoder(AlignGroupCircuitPacket::decode)
                .consumerMainThread(AlignGroupCircuitPacket::handle)
                .add();
    }
}
