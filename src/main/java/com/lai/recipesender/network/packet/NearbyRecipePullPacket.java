package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.service.NearbyRecipeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** 请求通过 FindMeExtended 将指定份数缺少的材料取回背包。 */
public record NearbyRecipePullPacket(int batches, List<RecipeIngredientSpec> ingredients) {
    /** 编码反向取回请求。 */
    public static void encode(NearbyRecipePullPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.batches);
        RecipeIngredientSpec.writeList(buffer, packet.ingredients);
    }

    /** 解码反向取回请求并校验材料列表。 */
    public static NearbyRecipePullPacket decode(FriendlyByteBuf buffer) {
        return new NearbyRecipePullPacket(buffer.readVarInt(), RecipeIngredientSpec.readList(buffer));
    }

    /** 将取回请求放入服务端主线程执行。 */
    public static void handle(NearbyRecipePullPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> NearbyRecipeService.pull(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
