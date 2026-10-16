package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.service.NearbyRecipeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 请求统计背包（含快捷栏）和 FindMeExtended 搜索范围内可制作的配方份数。
 *
 * <p>1.0.32 起反转与直达统计的是同一批来源：反转把材料取回背包，背包里的现成材料算数；
 * 直达会把背包与快捷栏里的材料也一起送进目标容器，所以背包里的存量同样算数。原先用来区分两者的
 * 「只统计周围」标志因此被删掉，两种模式看到的是同一个数字。
 */
public record NearbyRecipeQueryPacket(long requestId, List<RecipeIngredientSpec> ingredients) {
    /** 编码周围材料统计请求。 */
    public static void encode(NearbyRecipeQueryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        RecipeIngredientSpec.writeList(buffer, packet.ingredients);
    }

    /** 解码周围材料统计请求并校验材料列表。 */
    public static NearbyRecipeQueryPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        return new NearbyRecipeQueryPacket(requestId, RecipeIngredientSpec.readList(buffer));
    }

    /** 将统计请求放入服务端主线程执行。 */
    public static void handle(NearbyRecipeQueryPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> NearbyRecipeService.query(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
