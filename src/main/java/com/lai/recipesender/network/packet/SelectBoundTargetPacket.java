package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端告诉服务端「这次挑中了哪一个发送单元」，用于「上次选择」记忆。
 *
 * <p>与 {@link InsertRecipeItemsToBoundPacket} 分开是刻意的：
 * 记忆写在「玩家确认了选择」这一刻，而不是「材料成功送达」那一刻。
 * 材料因为背包不够、目标满了而没送出去，不该把玩家的选择也一起忘掉——
 * 下次按住 B 再按 Z，他要的仍然是同一台机器。
 *
 * <p>路由键由客户端给出：S1 阶段还没有真实路由（配方类别路由是 S6 的事），
 * 客户端统一报 {@code recipe_sender:manual}；S6 之后按配方类别分别记忆。
 * 服务端只校验「这个绑定确实属于该玩家」，不采信客户端给出的其它任何内容。
 */
public record SelectBoundTargetPacket(ResourceLocation routeKey, UUID bindingId) {

    /** 编码选择结果。 */
    public static void encode(SelectBoundTargetPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.routeKey);
        buffer.writeUUID(packet.bindingId);
    }

    /** 解码选择结果。 */
    public static SelectBoundTargetPacket decode(FriendlyByteBuf buffer) {
        return new SelectBoundTargetPacket(buffer.readResourceLocation(), buffer.readUUID());
    }

    /** 交给服务端主线程记录并回推同步包。 */
    public static void handle(SelectBoundTargetPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.select(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
