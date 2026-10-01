package com.lai.recipesender.network.packet;

import com.lai.recipesender.service.BoundBindingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端请求把某个主容器的配方类别（{@code routeKeys}）整体替换成这一组。
 *
 * <p>语义是「覆盖」而不是「增量」：类别选择器本身就是一个多选界面，玩家点确定时手上拿着的
 * 就是最终结果，增量合并在两侧状态不一致时反而会越修越乱。
 *
 * <p>只带 id 与键：是不是主容器、键是否合法一律由服务端重新判断——非主容器根本不该带类别
 * （它跟随父容器），客户端缓存落后时可能报上来一个已经改过角色的 id。
 */
public record UpdateBindingRoutesPacket(UUID id, Set<ResourceLocation> routeKeys) {

    /** 单次请求允许携带的最大类别数；250 个类别全勾上也远远够用，超了说明包有问题。 */
    private static final int MAX_ROUTES = 4096;

    /** 编码改类别请求。 */
    public static void encode(UpdateBindingRoutesPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.id);
        Set<ResourceLocation> routes = packet.routeKeys == null ? Set.of() : packet.routeKeys;
        buffer.writeVarInt(routes.size());
        for (ResourceLocation route : routes) {
            buffer.writeResourceLocation(route);
        }
    }

    /** 解码改类别请求。 */
    public static UpdateBindingRoutesPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ROUTES) {
            throw new IllegalArgumentException("绑定路由数量超出限制: " + size);
        }
        Set<ResourceLocation> routes = new LinkedHashSet<>(Math.min(size, 64));
        for (int index = 0; index < size; index++) {
            routes.add(buffer.readResourceLocation());
        }
        return new UpdateBindingRoutesPacket(id, routes);
    }

    /** 交给服务端主线程落盘并回推同步包。 */
    public static void handle(UpdateBindingRoutesPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BoundBindingService.updateRoutes(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
