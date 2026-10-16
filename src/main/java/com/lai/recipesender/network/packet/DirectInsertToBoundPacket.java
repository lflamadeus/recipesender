package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.RecipeFluidSpec;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.service.DirectInsertionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端提交一次「直达」：把周围容器里的材料直接放进已绑定容器，材料不进玩家背包。
 *
 * <p>与 {@link InsertRecipeItemsToBoundPacket} 的区别在材料那一侧：那个包带的是
 * <b>已经点过名的物品堆</b>（客户端先在自己背包里挑好了），而直达的源在玩家周围——
 * 客户端看不到那些容器的内容，也没法替服务端决定「从哪个箱子拿哪一份」，
 * 所以这里带的是<b>配方输入描述</b>（{@link RecipeIngredientSpec}，含通配符候选），
 * 由服务端在执行的这一刻扫描现场、自己规划并抽取。
 *
 * <p>这么设计还有一个必要的好处：客户端不发送任何坐标或具体物品堆，服务端只按绑定 id
 * 和配方描述现场决策，伪造这个包也顶多让玩家把自己的绑定容器填上自己周围的材料。
 *
 * @param circuit    配方要求的电路编号（0-32）；负数表示配方不使用电路，此时由 {@code gregRecipe}
 *                   决定要不要把电路置空
 * @param gregRecipe 客户端是否<b>确认</b>这是格雷配方（认出了配方对象）
 * @param fluids     配方要求的流体输入；流体支持关闭、或配方不含流体时是空表。带流体时
 *                   「一份」的定义是物品与流体都齐，服务端会同时受两边约束
 */
public record DirectInsertToBoundPacket(long requestId, UUID bindingId,
                                        List<RecipeIngredientSpec> specs, List<RecipeFluidSpec> fluids,
                                        int batches, int circuit, boolean gregRecipe) {

    /** 编码直达请求；配方输入描述的编码规则与周围材料统计完全一致。 */
    public static void encode(DirectInsertToBoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeVarInt(Math.max(0, packet.batches));
        buffer.writeVarInt(packet.circuit);
        buffer.writeBoolean(packet.gregRecipe);
        RecipeIngredientSpec.writeList(buffer, packet.specs);
        RecipeFluidSpec.writeList(buffer, packet.fluids);
    }

    /** 解码直达请求。 */
    public static DirectInsertToBoundPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        int batches = buffer.readVarInt();
        int circuit = buffer.readVarInt();
        boolean gregRecipe = buffer.readBoolean();
        List<RecipeIngredientSpec> specs = RecipeIngredientSpec.readList(buffer);
        List<RecipeFluidSpec> fluids = RecipeFluidSpec.readList(buffer);
        return new DirectInsertToBoundPacket(requestId, bindingId, specs, fluids, batches,
                circuit, gregRecipe);
    }

    /** 交给服务端主线程校验并执行。 */
    public static void handle(DirectInsertToBoundPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DirectInsertionService.insert(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
