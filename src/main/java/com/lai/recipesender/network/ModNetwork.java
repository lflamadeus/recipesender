package com.lai.recipesender.network;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.BoundInsertResultPacket;
import com.lai.recipesender.network.packet.BoundNoticePacket;
import com.lai.recipesender.network.packet.ClearContainerCircuitPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.InsertRecipeItemsToBoundPacket;
import com.lai.recipesender.network.packet.NearbyRecipeAvailabilityPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import com.lai.recipesender.network.packet.SelectBoundTargetPacket;
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
     */
    private static final String PROTOCOL_VERSION = "10";
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
    }
}
