package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.RecipeFluidSpec;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.model.SourceScope;
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
 *
 * <p>流体配方（纯液体，或物品 + 液体）的份数上限不只看背包：还要看流体中枢里有多少、目标组
 * 能灌进多少。中枢内容只有服务端知道，所以这里把流体需求一起带上，让服务端算出一个统一的份数。
 * {@code fluids} 为空就是纯物品配方，行为与以前完全一致。
 *
 * <p>{@code scope} 决定物品来源：{@link SourceScope#NEARBY} 扫背包与周围容器，
 * {@link SourceScope#HUB} 只数绑定的物品中枢。中枢直达连枚举都省了，统计一次的开销与基地大小无关。
 */
public record NearbyRecipeQueryPacket(long requestId, List<RecipeIngredientSpec> ingredients,
                                      List<RecipeFluidSpec> fluids, SourceScope scope) {

    /** 老版本客户端不带流体字段，解码时按空表处理。 */
    public NearbyRecipeQueryPacket {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        fluids = fluids == null ? List.of() : List.copyOf(fluids);
        scope = SourceScope.of(scope);
    }

    /** 兼容旧调用点：不带范围时按「周围直达」。 */
    public NearbyRecipeQueryPacket(long requestId, List<RecipeIngredientSpec> ingredients,
                                   List<RecipeFluidSpec> fluids) {
        this(requestId, ingredients, fluids, SourceScope.NEARBY);
    }

    /** 编码周围材料统计请求。 */
    public static void encode(NearbyRecipeQueryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        RecipeIngredientSpec.writeList(buffer, packet.ingredients);
        RecipeFluidSpec.writeList(buffer, packet.fluids);
        // 1.0.45 起末尾多一个取料范围（协议版本同步 +1）。
        SourceScope.of(packet.scope).writeTo(buffer);
    }

    /** 解码周围材料统计请求并校验材料列表。 */
    public static NearbyRecipeQueryPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        List<RecipeIngredientSpec> ingredients = RecipeIngredientSpec.readList(buffer);
        // 流体字段是后加的：读不到就当作没有，兼容同一台服务端上的旧客户端。
        List<RecipeFluidSpec> fluids = buffer.readableBytes() > 0
                ? RecipeFluidSpec.readList(buffer)
                : List.of();
        SourceScope scope = SourceScope.readFrom(buffer);
        return new NearbyRecipeQueryPacket(requestId, ingredients, fluids, scope);
    }

    /** 将统计请求放入服务端主线程执行。 */
    public static void handle(NearbyRecipeQueryPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> NearbyRecipeService.query(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
