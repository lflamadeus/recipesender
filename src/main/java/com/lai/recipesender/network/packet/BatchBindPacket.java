package com.lai.recipesender.network.packet;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BatchBindService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端一次性提交一整份批量绑定计划。
 *
 * <p>为什么不是「逐条发 {@code BindContainerPacket}」：那样拿不到主容器的**新 UUID**，
 * 就没法给这个新组设置建组模式；而且每建一条都会推一条回执，一批十几台就是十几条提示。
 * 一个包提交整份计划，服务端一次校验、一次同步、一条汇总回执。
 *
 * <p>{@code parentPos} 用**坐标**而不是 UUID：主容器与并列成员都是这次请求里新建的，
 * 客户端此刻还不知道它们的 id。服务端按坐标把本次新建的项串起来。
 */
public record BatchBindPacket(UUID requestId, ResourceLocation dimension, BlockPos cornerA, BlockPos cornerB,
                              BoundContainer.GroupMode mode, List<Entry> entries) {

    /**
     * 计划里的一项：坐标 + 角色 + 父容器坐标（主容器为 {@code null}）+ 配方类别。
     *
     * <p>{@code routeKeys} 只有主容器会带：类别来自 EMI 的配方管理器，服务端没有 EMI，
     * 所以只能客户端算好带过来。空集合 = 「不参与自动路由」（和单绑的语义一致）。
     */
    public record Entry(BlockPos pos, BoundContainer.Role role, BlockPos parentPos,
                        java.util.Set<ResourceLocation> routeKeys) {

        /** 不带类别的条目（并列成员、从容器）。 */
        public Entry(BlockPos pos, BoundContainer.Role role, BlockPos parentPos) {
            this(pos, role, parentPos, java.util.Set.of());
        }
    }

    /** 上限只是防垃圾包：单边 32 格的框里不可能有这么多个容器。 */
    public static final int MAX_ENTRIES = 4096;
    /** 一台机器登记的类别不会超过几十个，这个上限同样只是防垃圾包。 */
    public static final int MAX_ROUTES = 256;

    /** 编码。 */
    public static void encode(BatchBindPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.requestId);
        buffer.writeResourceLocation(packet.dimension);
        buffer.writeBlockPos(packet.cornerA);
        buffer.writeBlockPos(packet.cornerB);
        buffer.writeEnum(packet.mode == null ? BoundContainer.GroupMode.INDEPENDENT : packet.mode);
        List<Entry> entries = packet.entries == null ? List.of() : packet.entries;
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeBlockPos(entry.pos());
            buffer.writeEnum(entry.role() == null ? BoundContainer.Role.MASTER : entry.role());
            buffer.writeBoolean(entry.parentPos() != null);
            if (entry.parentPos() != null) {
                buffer.writeBlockPos(entry.parentPos());
            }
            java.util.Set<ResourceLocation> routes = entry.routeKeys() == null
                    ? java.util.Set.of() : entry.routeKeys();
            buffer.writeVarInt(routes.size());
            for (ResourceLocation route : routes) {
                buffer.writeResourceLocation(route);
            }
        }
    }

    /** 解码。 */
    public static BatchBindPacket decode(FriendlyByteBuf buffer) {
        UUID requestId = buffer.readUUID();
        ResourceLocation dimension = buffer.readResourceLocation();
        BlockPos cornerA = buffer.readBlockPos();
        BlockPos cornerB = buffer.readBlockPos();
        BoundContainer.GroupMode mode = buffer.readEnum(BoundContainer.GroupMode.class);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("批量绑定条目数量超出限制: " + count);
        }
        List<Entry> entries = new ArrayList<>(Math.min(count, 64));
        for (int index = 0; index < count; index++) {
            BlockPos pos = buffer.readBlockPos();
            BoundContainer.Role role = buffer.readEnum(BoundContainer.Role.class);
            BlockPos parentPos = buffer.readBoolean() ? buffer.readBlockPos() : null;
            int routeCount = buffer.readVarInt();
            if (routeCount < 0 || routeCount > MAX_ROUTES) {
                throw new IllegalArgumentException("配方类别数量超出限制: " + routeCount);
            }
            java.util.Set<ResourceLocation> routes = new java.util.LinkedHashSet<>();
            for (int route = 0; route < routeCount; route++) {
                routes.add(buffer.readResourceLocation());
            }
            entries.add(new Entry(pos, role, parentPos, java.util.Set.copyOf(routes)));
        }
        return new BatchBindPacket(requestId, dimension, cornerA, cornerB, mode, entries);
    }

    /** 交给服务端主线程校验并落盘。 */
    public static void handle(BatchBindPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> BatchBindService.bind(context.getSender(), packet));
        context.setPacketHandled(true);
    }
}
