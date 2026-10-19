package com.lai.recipesender.service;

import com.lai.recipesender.client.batch.BatchScanner;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BatchBindPacket;
import com.lai.recipesender.network.packet.BatchBindResultPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批量绑定的服务端实现：校验整份计划 → 按「主容器 → 并列成员 → 从容器」的顺序建 → 设组模式
 * → 一次同步 → 一条汇总回执。
 *
 * <p>客户端给的只是**计划**，每一条都要在这里重新校验：坐标是否已加载、方块是否真提供物品
 * 容器、是否已经绑过、父容器是否存在且角色合法。客户端的能力判断可能不全（有些模组只在
 * 服务端注册能力），所以不能把客户端的结果当成依据。
 */
public final class BatchBindService {

    private BatchBindService() {
    }

    /** 处理一次批量绑定请求。 */
    public static void bind(ServerPlayer player, BatchBindPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        List<BatchBindResultPacket.Failure> failures = new ArrayList<>();
        ServerLevel level = player.serverLevel();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, packet.dimension());
        List<BatchBindPacket.Entry> entries = packet.entries() == null ? List.of() : packet.entries();

        if (!level.dimension().equals(dimension)) {
            failAll(failures, entries, "text.recipe_sender.batch_fail_dimension");
            reply(player, packet, 0, failures);
            return;
        }
        if (!BatchScanner.withinLimit(packet.cornerA(), packet.cornerB())) {
            failAll(failures, entries, "text.recipe_sender.batch_fail_range");
            reply(player, packet, 0, failures);
            return;
        }
        if (entries.isEmpty() || entries.size() > BatchBindPacket.MAX_ENTRIES) {
            reply(player, packet, 0, failures);
            return;
        }

        // 位置 → 现有绑定的 id，以及位置 → 本次计划里的角色：父容器的合法性全靠这两张表。
        Map<BlockPos, UUID> ids = new HashMap<>();
        for (BoundContainer existing : BoundContainerService.list(player)) {
            ids.put(existing.pos(), existing.id());
        }
        Map<BlockPos, BoundContainer.Role> roles = new HashMap<>();

        int created = 0;
        for (BatchBindPacket.Entry entry : ordered(entries)) {
            String error = validate(level, dimension, entry, ids, roles);
            if (error != null) {
                failures.add(new BatchBindResultPacket.Failure(entry.pos(), error));
                continue;
            }
            BlockState state = level.getBlockState(entry.pos());
            UUID parentId = entry.parentPos() == null ? null : ids.get(entry.parentPos());
            BoundContainer bound = BoundContainerService.bind(player, dimension, entry.pos(),
                    defaultName(state, created), state.getBlock().asItem().getDefaultInstance(),
                    entry.role(), parentId);
            ids.put(entry.pos(), bound.id());
            roles.put(entry.pos(), entry.role());
            created++;
        }

        applyGroupMode(player, packet, roles);
        BoundBindingService.sync(player);
        reply(player, packet, created, failures);
    }

    /** 主容器 → 并列成员 → 从容器：父容器必须先建出来，否则 {@code parentId} 会解析成 null。 */
    private static List<BatchBindPacket.Entry> ordered(List<BatchBindPacket.Entry> entries) {
        List<BatchBindPacket.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(entry -> rank(entry.role())));
        return sorted;
    }

    private static int rank(BoundContainer.Role role) {
        if (role == null || role == BoundContainer.Role.MASTER) {
            return 0;
        }
        return role == BoundContainer.Role.PARALLEL ? 1 : 2;
    }

    /** 逐条校验；返回 {@code null} 表示可以建。 */
    private static String validate(ServerLevel level, ResourceKey<Level> dimension, BatchBindPacket.Entry entry,
                                   Map<BlockPos, UUID> ids, Map<BlockPos, BoundContainer.Role> roles) {
        BlockPos pos = entry.pos();
        if (pos == null || !level.isLoaded(pos)) {
            return "text.recipe_sender.batch_fail_unloaded";
        }
        if (ids.containsKey(pos)) {
            return "text.recipe_sender.batch_fail_bound";
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !BoundTargetResolver.hasItemHandler(level.getBlockEntity(pos))) {
            return "text.recipe_sender.batch_fail_no_handler";
        }
        BoundContainer.Role role = entry.role() == null ? BoundContainer.Role.MASTER : entry.role();
        if (role == BoundContainer.Role.MASTER) {
            return null;
        }
        if (entry.parentPos() == null || !ids.containsKey(entry.parentPos())) {
            return "text.recipe_sender.batch_fail_no_parent";
        }
        BoundContainer.Role parentRole = roles.get(entry.parentPos());
        if (role == BoundContainer.Role.PARALLEL) {
            // 并列成员只能挂主容器（主容器必须是这一批里新建的那一个）。
            return parentRole == BoundContainer.Role.MASTER ? null : "text.recipe_sender.batch_fail_parent_role";
        }
        // 从容器可以挂主容器或并列成员，但不能挂从容器（两级封顶）。
        return parentRole == null || parentRole == BoundContainer.Role.SLAVE
                ? "text.recipe_sender.batch_fail_parent_role" : null;
    }

    /** 给新建的主容器套上建组模式。 */
    private static void applyGroupMode(ServerPlayer player, BatchBindPacket packet,
                                       Map<BlockPos, BoundContainer.Role> roles) {
        if (packet.mode() == null || packet.mode() == BoundContainer.GroupMode.INDEPENDENT) {
            return;
        }
        for (Map.Entry<BlockPos, BoundContainer.Role> entry : roles.entrySet()) {
            if (entry.getValue() != BoundContainer.Role.MASTER) {
                continue;
            }
            for (BoundContainer binding : BoundContainerService.list(player)) {
                if (binding.pos().equals(entry.getKey())) {
                    BoundContainerService.update(player, binding.withGroupMode(packet.mode()));
                    return;
                }
            }
        }
    }

    private static String defaultName(BlockState state, int index) {
        return state.getBlock().getName().getString() + " #" + (index + 1);
    }

    private static void failAll(List<BatchBindResultPacket.Failure> failures, List<BatchBindPacket.Entry> entries,
                                String key) {
        for (BatchBindPacket.Entry entry : entries) {
            failures.add(new BatchBindResultPacket.Failure(entry.pos(), key));
        }
    }

    private static void reply(ServerPlayer player, BatchBindPacket packet, int created,
                              List<BatchBindResultPacket.Failure> failures) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BatchBindResultPacket(packet.requestId(), created, List.copyOf(failures)));
    }
}
