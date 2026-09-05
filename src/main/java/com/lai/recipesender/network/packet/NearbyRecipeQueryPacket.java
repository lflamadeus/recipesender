package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.service.NearbyRecipeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** 请求统计背包和 FindMeExtended 搜索范围内可制作的配方份数。 */
public record NearbyRecipeQueryPacket(long requestId, List<RecipeIngredientSpec> ingredients) {
    /** 编码周围材料统计请求。 */
    public static void encode(NearbyRecipeQueryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        RecipeIngredientSpec.writeList(buffer, packet.ingredients);
    }

    /** 解码周围材料统计请求并校验材料列表。 */
    public static NearbyRecipeQueryPacket decode(FriendlyByteBuf buffer) {
        return new NearbyRecipeQueryPacket(buffer.readLong(), RecipeIngredientSpec.readList(buffer));
    }

    /** 将统计请求放入服务端主线程执行。 */
    public static void handle(NearbyRecipeQueryPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> NearbyRecipeService.query(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
