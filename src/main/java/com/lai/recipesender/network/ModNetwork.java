package com.lai.recipesender.network;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import com.lai.recipesender.network.packet.NearbyRecipeAvailabilityPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 集中注册客户端与服务端之间的数据包。 */
public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "2";
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
        CHANNEL.messageBuilder(NearbyRecipePullPacket.class, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NearbyRecipePullPacket::encode)
                .decoder(NearbyRecipePullPacket::decode)
                .consumerMainThread(NearbyRecipePullPacket::handle)
                .add();
    }
}
