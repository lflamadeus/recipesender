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
 * <p>与 {@link InsertRecipeItemsPacket} 的区别有两个：落点由 {@code bindingId} 指定，
 * 而不是「玩家当前打开的菜单」，所以服务端必须自己按坐标解析目标，并且<b>不能</b>依赖
 * 玩家开着那个容器的界面；材料按<b>配方输入分组</b>带上，而不是一张平表。
 *
 * <p>分组是必要的：格雷的同标签多变体（多种 LV 电路板）在配方里完全等价，一份要两个板时
 * 允许两种板各一个。摊成平表以后服务端只能按物品逐堆除以份数，「同类可以互换」这件事就丢了，
 * 多变体凑不出一整份，余料还会全堆进第一台机器。
 *
 * <p>电路要求也随包带上：落点是远程方块，服务端手里没有任何配方信息，不带上就只能不调电路。
 * 服务端拿到后先调电路、再投材料——材料一进机器，配方逻辑就会按当时的电路立刻开跑。
 *
 * @param requirementGroups 每个配方输入一组材料，组内是同一输入下可互换的变体，数量是「这一项
 *                          一共要发多少」（= 每份用量 × {@code batches}）
 * @param circuit           配方要求的电路编号（0-32）；负数表示配方不使用电路，此时由
 *                          {@code gregRecipe} 决定要不要把电路置空
 * @param gregRecipe        客户端是否<b>确认</b>这是格雷配方（认出了配方对象）。只看 EMI 原料
 *                          列表的通路认不出配方对象，为 {@code false}，那种情况只用于「按编号
 *                          写电路」，绝不用来置空——识别失败时宁可不动电路
 */
public record InsertRecipeItemsToBoundPacket(long requestId, UUID bindingId,
                                             List<List<ItemStack>> requirementGroups, int batches,
                                             int circuit, boolean gregRecipe) {

    /** 编码投放请求；组内材料编码规则与正向投放完全一致。 */
    public static void encode(InsertRecipeItemsToBoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUUID(packet.bindingId);
        buffer.writeVarInt(Math.max(0, packet.batches));
        buffer.writeVarInt(packet.circuit);
        buffer.writeBoolean(packet.gregRecipe);
        InsertRecipeItemsPacket.writeGroups(buffer, packet.requirementGroups);
    }

    /** 解码投放请求。 */
    public static InsertRecipeItemsToBoundPacket decode(FriendlyByteBuf buffer) {
        long requestId = buffer.readLong();
        UUID bindingId = buffer.readUUID();
        int batches = buffer.readVarInt();
        int circuit = buffer.readVarInt();
        boolean gregRecipe = buffer.readBoolean();
        List<List<ItemStack>> groups = InsertRecipeItemsPacket.readGroups(buffer);
        return new InsertRecipeItemsToBoundPacket(requestId, bindingId, groups, batches,
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
