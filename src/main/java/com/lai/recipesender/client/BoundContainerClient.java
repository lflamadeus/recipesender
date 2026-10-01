package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.service.BoundContainerService;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端只读的绑定缓存。
 *
 * <p>唯一的数据来源是服务端的 {@code SyncBoundContainersPacket}；客户端不做本地乐观更新，
 * 所以界面永远显示服务端最后一次确认过的状态。
 *
 * <p>不设绑定数量上限，所以这里用普通 List，不做容量约束。
 */
public final class BoundContainerClient {
    private static volatile List<BoundContainer> bindings = List.of();
    /** 「上次选择」：路由键的字符串形式 → 发送单元主体（主容器）的 id。 */
    private static volatile Map<String, UUID> lastChoices = Map.of();

    private BoundContainerClient() {
    }

    /** 用服务端推送的列表与「上次选择」整体替换本地缓存。 */
    public static void acceptSync(List<BoundContainer> synced, Map<String, UUID> choices) {
        bindings = synced == null ? List.of() : List.copyOf(synced);
        lastChoices = choices == null ? Map.of() : Map.copyOf(choices);
    }

    /** 当前全部绑定。 */
    public static List<BoundContainer> all() {
        return bindings;
    }

    /** 只保留主容器：自动路由与选择弹窗都只认主容器。 */
    public static List<BoundContainer> masters() {
        List<BoundContainer> masters = new ArrayList<>();
        for (BoundContainer binding : bindings) {
            if (binding.isMaster()) {
                masters.add(binding);
            }
        }
        return masters;
    }

    /** 按 id 查找。 */
    public static BoundContainer find(UUID id) {
        if (id == null) {
            return null;
        }
        for (BoundContainer binding : bindings) {
            if (binding.id().equals(id)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 按坐标查找绑定。
     *
     * <p>同一个方块最多只会有一条绑定（服务端 {@code bind} 命中同坐标时是更新而不是新增），
     * 所以拿到第一个匹配项就可以返回。绑定键（按 B）用它判断「这个方块是不是已经绑过了」。
     */
    public static BoundContainer findAt(ResourceLocation dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        for (BoundContainer binding : bindings) {
            if (binding.pos().equals(pos) && binding.dimension().location().equals(dimension)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 某个主容器所在的发送单元。
     *
     * <p>缓存里可能短暂落后于服务端（比如主容器刚被删掉、成员还没收到新列表），
     * 这时返回 {@code null}，调用方按「独立主容器」处理即可。
     */
    public static BoundGroupResolver.BoundGroup groupOf(UUID masterId) {
        return BoundGroupResolver.groupOf(bindings, masterId);
    }

    /** 组内容器数量（含主容器自己、不含从容器）。 */
    public static int memberCount(UUID masterId) {
        return BoundGroupResolver.memberCount(bindings, masterId);
    }

    /** 挂在某个主容器下的从容器数量。 */
    public static int slaveCount(UUID masterId) {
        return BoundGroupResolver.slaveCount(bindings, masterId);
    }

    /** 发送单元的显示名：有并列成员时带 {@code ×N} 后缀。 */
    public static String displayName(BoundContainer master) {
        return BoundGroupResolver.displayName(bindings, master);
    }

    /**
     * 读取某个路由键记下的「上次选择」对应的绑定；没有记录、或记录指向的绑定已被删掉时返回 {@code null}。
     *
     * <p>「记录指向已删除的绑定」理论上不该出现（服务端删除时会连带清理），
     * 但客户端缓存可能短暂落后于服务端，所以这里再兜一层，宁可当作「没有上次」。
     */
    public static BoundContainer lastChoice(ResourceLocation routeKey) {
        if (routeKey == null) {
            return null;
        }
        UUID id = lastChoices.get(routeKey.toString());
        return id == null ? null : find(id);
    }

    /** 清空缓存：断开连接时调用，避免把上一个世界的绑定带进新世界。 */
    public static void clear() {
        bindings = List.of();
        lastChoices = Map.of();
    }

    /** S1 阶段还没有真实路由键，手选结果统一记在这个键下。 */
    public static ResourceLocation manualRoute() {
        return BoundContainerService.MANUAL_ROUTE;
    }
}
