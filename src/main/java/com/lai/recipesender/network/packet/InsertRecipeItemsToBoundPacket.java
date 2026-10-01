package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundInsertionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端提交一次「发送到已绑定容器」。
 *
 * <p>与 {@link InsertRecipeItemsPacket} 的区别只有一个：落点由 {@code bindingId} 指定，
 * 而不是「玩家当前打开的菜单」。因此服务端必须自己按坐标解析目标，并且**不能**依赖
 * 玩家开着那个容器的界面。
 *
 * <p>电路要求也随包带上：落点是远程方块，服务端手里没有任何配方信息，不带上就只能不调电路。
 * 服务端拿到后先调电路、再投材料——材料一进机器，配方逻辑就会按当时的电路立刻开跑。
 *
 * @param circuit    配方要求的电路编号（0-32）；负数表示配方不使用电路，此时由 {@code gregRecipe}
 *                   决定要不要把电路置空
 * @param gregRecipe 客户端是否<b>确认</b>这是格雷配方（认出了配方对象）。只看 EMI 原料列表的
 *                   通路认不出配方对象，为 {@code false}，那种情况只用于「按编号写电路」，
 *                   绝不用来置空——识别失败时宁可不动电路
 */
public record InsertRecipeItemsToBoundPacket(long requestId, UUID bindingId,
                                             List<ItemStack> requirements, int batches,
                                             int circuit, boolean gregRecipe) {

    /** 编码投放请求；材料编码规则与正向投放完全一致。 */
    public static void encode(InsertRecipeItemsToBoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeVarInt(Math.max(0, packet.batches));
        buffer.writeVarInt(packet.circuit);
        buffer.writeBoolean(packet.gregRecipe);
        InsertRecipeItemsPacket.writeRequirements(buffer, packet.requirements);
    }

    /** 解码投放请求。 */
    public static InsertRecipeItemsToBoundPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        int batches = buffer.readVarInt();
        int circuit = buffer.readVarInt();
        boolean gregRecipe = buffer.readBoolean();
        List<ItemStack> requirements = InsertRecipeItemsPacket.readRequirements(buffer);
        return new InsertRecipeItemsToBoundPacket(requestId, bindingId, requirements, batches,
                circuit, gregRecipe);
    }

    /** 交给服务端主线程校验并执行。 */
    public static void handle(InsertRecipeItemsToBoundPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundInsertionService.insert(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
