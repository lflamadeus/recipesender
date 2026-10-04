package com.lai.recipesender.service;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定容器的服务端权威存储。
 *
 * <p>数据落在 {@link ServerPlayer#getPersistentData()} 里，随玩家存档走：
 * 玩家自己的绑定跟着玩家自己走，多人服务器上互不干扰。
 * 客户端只保留只读缓存（见 {@code client.BoundContainerClient}），任何增删改都由服务端发起同步。
 *
 * <p>刻意没有绑定数量上限。
 */
public final class BoundContainerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundContainerService.class);

    private static final String KEY_BINDINGS = "recipe_sender:bindings";
    /** 「上次选择」：路由键 → 发送单元主体（主容器）的 id。 */
    private static final String KEY_LAST_CHOICE = "recipe_sender:last_choice";

    /**
     * 「上次选择」最多记多少条路由键。
     *
     * <p>这张表原先没有任何上限，玩得越久键越多（每个用过的配方类别一条），而且随玩家存档永久
     * 保留。256 条足够覆盖任何玩家真正在用的类别（GT 全类别也就约 250），超出时按 NBT 的无序
     * 键集合随便丢掉几条最不常用的——丢掉的代价只是「那个类别下次要重新选一次」。</p>
     */
    private static final int MAX_LAST_CHOICES = 256;

    /**
     * 「认不出路由键」时的兜底记忆键。
     *
     * <p>S1–S5 没有真实路由键，玩家在弹窗里手选的结果全部记在这一个键下；S6 起自动路由按各自的
     * 路由键分别记录（{@link #KEY_LAST_CHOICE}），本键只在「连类别都认不出来、玩家自己挑了一个」
     * 时才用得上。
     *
     * <p>用命名空间自己的 id 而不是空串：空串做 NBT 键名合法但极易写错，也看不出是「手选」。
     */
    public static final ResourceLocation MANUAL_ROUTE =
            ResourceLocation.fromNamespaceAndPath(RecipeSenderMod.MOD_ID, "manual");

    private BoundContainerService() {
    }

    /** 读取该玩家当前的绑定列表；损坏的条目直接跳过。 */
    public static List<BoundContainer> list(ServerPlayer player) {
        if (player == null) {
            return List.of();
        }
        ListTag stored = player.getPersistentData().getList(KEY_BINDINGS, Tag.TAG_COMPOUND);
        List<BoundContainer> bindings = new ArrayList<>(stored.size());
        for (int index = 0; index < stored.size(); index++) {
            BoundContainer binding = BoundContainer.load(stored.getCompound(index));
            if (binding != null) {
                bindings.add(binding);
            }
        }
        return List.copyOf(bindings);
    }

    /** 按 id 查找该玩家的绑定；找不到时返回 {@code null}。 */
    public static BoundContainer find(ServerPlayer player, UUID id) {
        if (id == null) {
            return null;
        }
        for (BoundContainer binding : list(player)) {
            if (binding.id().equals(id)) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 绑定一个坐标。
     *
     * <p>同一维度同一坐标已经绑过时改为「更新」而不是新增：重复按绑定键不会堆出一串重复条目。
     * 名称留空时保留原有名称（更新场景），新建场景由调用方给出默认名称。
     *
     * <p>{@code role} 为 {@code null} 时按主容器处理；{@code role} 不是主容器时才使用 {@code parentId}。
     *
     * @return 写入后的绑定记录
     */
    public static BoundContainer bind(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos,
                                      String name, ItemStack icon, BoundContainer.Role role, UUID parentId) {
        return bind(player, dimension, pos, name, icon, role, parentId, Set.of());
    }

    /**
     * 绑定一个坐标，并顺带落盘配方类别。
     *
     * <p>{@code routes} 为<b>空集合时表示「不动类别」</b>：绑定弹窗上没打开类别选择器时不该把
     * 已有主容器的类别清空。想清空类别走 {@link #setRoutes}（管理界面的类别按钮就是这么做的）。
     */
    public static BoundContainer bind(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos,
                                      String name, ItemStack icon, BoundContainer.Role role, UUID parentId,
                                      Set<ResourceLocation> routes) {
        BoundContainer.Role effectiveRole = role == null ? BoundContainer.Role.MASTER : role;
        UUID effectiveParent = effectiveRole == BoundContainer.Role.MASTER ? null : parentId;
        List<BoundContainer> bindings = new ArrayList<>(list(player));
        for (int index = 0; index < bindings.size(); index++) {
            BoundContainer existing = bindings.get(index);
            if (!existing.samePos(dimension, pos)) {
                continue;
            }
            BoundContainer updated = existing;
            if (name != null && !name.isBlank()) {
                updated = updated.renamed(name);
            }
            if (icon != null && !icon.isEmpty()) {
                updated = updated.withIcon(icon);
            }
            updated = updated.withRole(effectiveRole, effectiveParent);
            if (routes != null && !routes.isEmpty()) {
                // 放在 withRole 之后：withRole 会把非主容器的类别清空，顺序反过来会被它抹掉。
                updated = updated.withRoutes(routes);
            }
            bindings.set(index, updated);
            save(player, bindings);
            return updated;
        }
        BoundContainer created = new BoundContainer(UUID.randomUUID(), name, dimension, pos,
                icon, effectiveRole, effectiveParent,
                routes == null ? Set.of() : Set.copyOf(routes),
                System.currentTimeMillis());
        bindings.add(created);
        save(player, bindings);
        return created;
    }

    /**
     * 修改一条已有绑定的容器关系（角色 + 父容器）。
     *
     * <p>与 {@link #rename} 一样只动指定字段，其余一律以服务端存的那份为准。
     */
    public static boolean setRelation(ServerPlayer player, UUID id, BoundContainer.Role role, UUID parentId) {
        BoundContainer existing = find(player, id);
        if (existing == null || role == null) {
            return false;
        }
        UUID effectiveParent = role == BoundContainer.Role.MASTER ? null : parentId;
        return update(player, existing.withRole(role, effectiveParent));
    }

    /** 删除一条绑定；返回是否真的删掉了。 */
    public static boolean unbind(ServerPlayer player, UUID id) {
        List<BoundContainer> bindings = new ArrayList<>(list(player));
        boolean removed = bindings.removeIf(binding -> binding.id().equals(id));
        if (removed) {
            save(player, bindings);
            forgetChoicesFor(player, id);
        }
        return removed;
    }

    /** 用给定记录替换同 id 的绑定；返回是否找到并替换。 */
    public static boolean update(ServerPlayer player, BoundContainer replacement) {
        List<BoundContainer> bindings = new ArrayList<>(list(player));
        for (int index = 0; index < bindings.size(); index++) {
            if (bindings.get(index).id().equals(replacement.id())) {
                bindings.set(index, replacement);
                save(player, bindings);
                return true;
            }
        }
        return false;
    }

    /** 改名；返回是否找到并改掉。名称会被规范化（去首尾空白 + 截断到 32 字符）。 */
    public static boolean rename(ServerPlayer player, UUID id, String name) {
        BoundContainer existing = find(player, id);
        if (existing == null) {
            return false;
        }
        return update(player, existing.renamed(name));
    }

    /**
     * 整体替换一个主容器的配方类别；返回是否找到并改掉。
     *
     * <p>非主容器会被 {@code withRoutes} 清成空集合，所以这里不必先判断角色——
     * 但客户端不该发出这种请求，收到时也照旧落盘（空集合是安全结果）。
     */
    public static boolean setRoutes(ServerPlayer player, UUID id, Set<ResourceLocation> routes) {
        BoundContainer existing = find(player, id);
        if (existing == null) {
            return false;
        }
        return update(player, existing.withRoutes(routes));
    }

    /** 读取全部「上次选择」记录：路由键的字符串形式 → 发送单元主体 id。 */
    public static Map<String, UUID> lastChoices(ServerPlayer player) {
        if (player == null) {
            return Map.of();
        }
        CompoundTag stored = player.getPersistentData().getCompound(KEY_LAST_CHOICE);
        Map<String, UUID> choices = new HashMap<>();
        for (String key : stored.getAllKeys()) {
            try {
                choices.put(key, UUID.fromString(stored.getString(key)));
            } catch (IllegalArgumentException exception) {
                // 单个条目损坏不该让整张表失效：跳过它，其余照常读出来。
                LOGGER.debug("跳过损坏的「上次选择」记录：{}", key);
            }
        }
        return Map.copyOf(choices);
    }

    /** 读取某个路由键记下的「上次选择」；没有记录时返回 {@code null}。 */
    public static UUID lastChoice(ServerPlayer player, ResourceLocation routeKey) {
        if (player == null || routeKey == null) {
            return null;
        }
        CompoundTag stored = player.getPersistentData().getCompound(KEY_LAST_CHOICE);
        String raw = stored.getString(routeKey.toString());
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 记下某个路由键的「上次选择」。 */
    public static void rememberChoice(ServerPlayer player, ResourceLocation routeKey, UUID bindingId) {
        if (player == null || routeKey == null || bindingId == null) {
            return;
        }
        CompoundTag stored = player.getPersistentData().getCompound(KEY_LAST_CHOICE).copy();
        stored.putString(routeKey.toString(), bindingId.toString());
        evictOldestChoices(stored, routeKey.toString());
        player.getPersistentData().put(KEY_LAST_CHOICE, stored);
    }

    /**
     * 「上次选择」表超过 {@link #MAX_LAST_CHOICES} 时丢掉多余的条目。
     *
     * <p>NBT 的复合标签是无序的，没有「写入时间」可依，所以这里丢掉的是任意条目（刚写的这条除外）。
     * 这是有意的取舍：这张表只是「省一次选择」的便利缓存，丢一条的代价远小于让它随存档无限增长。</p>
     */
    private static void evictOldestChoices(CompoundTag stored, String keepKey) {
        if (stored.getAllKeys().size() <= MAX_LAST_CHOICES) {
            return;
        }
        for (String key : new ArrayList<>(stored.getAllKeys())) {
            if (stored.getAllKeys().size() <= MAX_LAST_CHOICES) {
                break;
            }
            if (!key.equals(keepKey)) {
                stored.remove(key);
            }
        }
    }

    /**
     * 清掉所有指向该绑定的「上次选择」记录。
     *
     * <p>删绑定必须连带清理，否则下次按 B 会「直达」一个已经不存在的目标——
     * 玩家的感受是「按了没反应」，而真正的原因藏在一条看不见的陈旧记录里。
     */
    private static void forgetChoicesFor(ServerPlayer player, UUID bindingId) {
        CompoundTag stored = player.getPersistentData().getCompound(KEY_LAST_CHOICE).copy();
        String target = bindingId.toString();
        boolean changed = false;
        for (String key : new ArrayList<>(stored.getAllKeys())) {
            if (target.equals(stored.getString(key))) {
                stored.remove(key);
                changed = true;
            }
        }
        if (changed) {
            player.getPersistentData().put(KEY_LAST_CHOICE, stored);
        }
    }

    /** 把整个列表写回玩家持久数据。 */
    private static void save(ServerPlayer player, List<BoundContainer> bindings) {
        ListTag stored = new ListTag();
        for (BoundContainer binding : bindings) {
            CompoundTag tag = binding.save();
            if (tag != null) {
                stored.add(tag);
            }
        }
        player.getPersistentData().put(KEY_BINDINGS, stored);
    }

    /** 把状态翻译成给玩家看的原因文本键；{@link BoundStatus#OK} 返回 {@code null}。 */
    public static String describe(BoundStatus status) {
        return switch (status) {
            case OK -> null;
            case DIMENSION_MISMATCH -> "text.recipe_sender.bound_status.dimension_mismatch";
            case CHUNK_UNLOADED -> "text.recipe_sender.bound_status.chunk_unloaded";
            case MISSING -> "text.recipe_sender.bound_status.missing";
            case NO_ITEM_HANDLER -> "text.recipe_sender.bound_status.no_item_handler";
            case FORBIDDEN -> "text.recipe_sender.bound_status.forbidden";
        };
    }
}
