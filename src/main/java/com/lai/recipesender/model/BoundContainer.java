package com.lai.recipesender.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一条「已绑定容器」记录。
 *
 * <p>一条记录只描述一个方块坐标以及它在发送单元里的角色，不含距离、不含兜底标记：
 * <ul>
 *   <li>{@link Role#MASTER} 主容器：发送单元的中心，是唯一携带 {@link #routeKeys} 的角色。</li>
 *   <li>{@link Role#PARALLEL} 并列成员：挂在主容器下，和主容器一起按份均分材料。</li>
 *   <li>{@link Role#SLAVE} 从容器：只服务<b>整体模式</b>的并列组（见 {@link GroupMode#UNIFIED}），
 *       是这一组的容量缓冲，只一级；独立模式的组（含只有主容器自己的单机）里它不参与投送
 *       （1.0.38 起，见 {@code BoundGroupResolver#effectiveSlaves()}），把主容器改成整体模式后
 *       自动恢复，数据不用动。</li>
 * </ul>
 *
 * <p>角色关系在发送时动态解析、不固化，所以主容器后来多了并列成员时，已有的从容器不需要重新绑定，
 * 只是暂时不再接收余量。
 *
 * <p>绑定数量不设上限；名称允许重复，用 {@link #id} 区分。
 */
public record BoundContainer(UUID id, String name, ResourceKey<Level> dimension, BlockPos pos,
                             ItemStack iconItem, Role role, Kind kind, UUID parentId,
                             Set<ResourceLocation> routeKeys, long createdAt, GroupMode groupMode) {

    /** 发送单元里的角色。 */
    public enum Role {
        MASTER,
        PARALLEL,
        SLAVE
    }

    /**
     * 这条绑定在「配方材料从哪里来 / 往哪里去」里扮演什么。
     *
     * <p>两个用途的方向是相反的，所以必须在数据上分开，不能靠角色或配方类别硬凑：
     * <ul>
     *   <li>{@link #ITEM_TARGET} 物品目标：现有全部绑定，是投放的<b>去处</b>，有主/并/从的分摊语义。</li>
     *   <li>{@link #FLUID_HUB} 流体中枢：直达模式的流体<b>来源</b>，固定单个、没有分摊语义，
     *       所以它永远不参与主/并/从的分组，也不带配方类别。</li>
     * </ul>
     *
     * <p>所有物品通路都必须按 {@code kind == ITEM_TARGET} 过滤：否则流体中枢会被当成候选目标，
     * 材料可能被塞进储罐。
     */
    public enum Kind {
        ITEM_TARGET,
        FLUID_HUB
    }

    /**
     * 并列组的工作模式。只有主容器上的值有意义，成员一律跟随主容器。
     *
     * <p>它描述的是「这个组怎么对待组内机器正在跑的那一炉」，因此必须由玩家显式选择，
     * 不能靠机器状态猜：
     * <ul>
     *   <li>{@link #INDEPENDENT} 独立模式（默认）：组内每台机器各自独立，电路可以互不相同，
     *       材料只投给<b>空闲</b>的机器（在跑的跳过、电路也不动），要改某台机器的电路就单独用
     *       直达发送点名那台。从容器在这种模式下<b>不参与</b>投送——一台机器配一个箱子没有意义，
     *       而组内机器电路不一致时箱子里的材料归属不到任何一台机器上。</li>
     *   <li>{@link #UNIFIED} 整体模式：整个组被当作一台大机器。组内机器电路必须一致，
     *       从容器（由玩家自己铺管路抽到组内）是这一组的缓冲；在跑的机器<b>也接料</b>（它按同一份
     *       配方继续吃）。安全机制是电路一致性闸门：本次发配的电路与在跑机器的电路不一致时
     *       整单取消、一台机器的电路都不动（见 {@code GroupCircuitGate}）。</li>
     * </ul>
     */
    public enum GroupMode {
        INDEPENDENT,
        UNIFIED
    }

    /** 名称长度上限，超出部分截断。 */
    public static final int MAX_NAME_LENGTH = 32;
    private static final String UNNAMED = "未命名";

    public BoundContainer {
        name = normalizeName(name);
        iconItem = iconItem == null || iconItem.isEmpty()
                ? ItemStack.EMPTY : iconItem.copyWithCount(1);
        role = role == null ? Role.MASTER : role;
        kind = kind == null ? Kind.ITEM_TARGET : kind;
        groupMode = groupMode == null ? GroupMode.INDEPENDENT : groupMode;
        routeKeys = routeKeys == null ? Set.of() : Set.copyOf(routeKeys);
    }

    /** 去掉首尾空白并截断到长度上限；空名称退化为占位名。 */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return UNNAMED;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return UNNAMED;
        }
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    /** 判断绑定是否指向同一个方块（用于「同坐标重复绑定 = 更新」）。 */
    public boolean samePos(ResourceKey<Level> otherDimension, BlockPos otherPos) {
        return dimension.equals(otherDimension) && pos.equals(otherPos);
    }

    /** 只有主容器携带配方类别，其余角色跟随父容器。 */
    public boolean isMaster() {
        return role == Role.MASTER;
    }

    /** 这条绑定是不是流体中枢（直达模式的流体来源）。 */
    public boolean isFluidHub() {
        return kind == Kind.FLUID_HUB;
    }

    /**
     * 这个发送单元是不是「整体模式」。
     *
     * <p>组模式只写在主容器上，所以问成员没有意义——调用方拿到的应当是
     * {@code BoundGroupResolver.BoundGroup#master()}。
     */
    public boolean isUnified() {
        return groupMode == GroupMode.UNIFIED;
    }

    /** 生成改名后的副本。 */
    public BoundContainer renamed(String newName) {
        return new BoundContainer(id, newName, dimension, pos, iconItem, role, kind, parentId,
                routeKeys, createdAt, groupMode);
    }

    /** 生成替换图标后的副本。 */
    public BoundContainer withIcon(ItemStack newIcon) {
        return new BoundContainer(id, name, dimension, pos, newIcon, role, kind, parentId,
                routeKeys, createdAt, groupMode);
    }

    /**
     * 生成替换用途后的副本。
     *
     * <p>换成流体中枢时顺手把角色、类别与组模式清成中枢该有的样子：中枢不参与分组、也不参与路由，
     * 留着旧的角色、类别或组模式只会让它在物品路径里被误当成目标。
     */
    public BoundContainer withKind(Kind newKind) {
        Kind target = newKind == null ? Kind.ITEM_TARGET : newKind;
        return new BoundContainer(id, name, dimension, pos, iconItem,
                target == Kind.FLUID_HUB ? Role.MASTER : role, target,
                target == Kind.FLUID_HUB ? null : parentId,
                target == Kind.FLUID_HUB ? Set.of() : routeKeys, createdAt,
                target == Kind.FLUID_HUB ? GroupMode.INDEPENDENT : groupMode);
    }

    /** 生成替换角色与父容器后的副本。 */
    public BoundContainer withRole(Role newRole, UUID newParentId) {
        return new BoundContainer(id, name, dimension, pos, iconItem, newRole, kind, newParentId,
                newRole == Role.MASTER ? routeKeys : Set.of(), createdAt, groupMode);
    }

    /**
     * 生成替换组模式后的副本。
     *
     * <p>只有主容器上的值有意义：并列成员与从容器一律跟随主容器，所以它们身上的这个字段
     * 保持原样即可，读取方（{@code BoundGroupResolver}）只认主容器的那一份。
     */
    public BoundContainer withGroupMode(GroupMode newMode) {
        return new BoundContainer(id, name, dimension, pos, iconItem, role, kind, parentId,
                routeKeys, createdAt, newMode == null ? GroupMode.INDEPENDENT : newMode);
    }

    /**
     * 生成替换配方类别后的副本。
     *
     * <p>只有主容器带类别：并列成员与从容器一律清空，免得它们被当成发送单元参与路由
     * （{@link #withRole} 也按同一规则处理）。
     */
    public BoundContainer withRoutes(Set<ResourceLocation> newRoutes) {
        return new BoundContainer(id, name, dimension, pos, iconItem, role, kind, parentId,
                isMaster() && newRoutes != null ? Set.copyOf(newRoutes) : Set.of(), createdAt, groupMode);
    }

    /** 序列化为 NBT；同时用于玩家存档和网络同步，保证两侧格式一致。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Pos", pos.asLong());
        tag.put("Icon", iconItem.save(new CompoundTag()));
        tag.putString("Role", role.name());
        // 旧存档没有 Kind 字段，load 时会退回 ITEM_TARGET，所以这里只在非默认时写。
        if (kind != Kind.ITEM_TARGET) {
            tag.putString("Kind", kind.name());
        }
        // 组模式同理：旧存档读不到就是独立模式，只在非默认时写，免得给每条记录都多一个字段。
        if (groupMode != GroupMode.INDEPENDENT) {
            tag.putString("GroupMode", groupMode.name());
        }
        if (parentId != null) {
            tag.putUUID("Parent", parentId);
        }
        if (!routeKeys.isEmpty()) {
            ListTag routes = new ListTag();
            for (ResourceLocation key : routeKeys) {
                routes.add(StringTag.valueOf(key.toString()));
            }
            tag.put("Routes", routes);
        }
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    /** 从 NBT 还原；维度字段损坏时返回 {@code null}，由调用方跳过这一条。 */
    public static BoundContainer load(CompoundTag tag) {
        if (tag == null || !tag.contains("Id")) {
            return null;
        }
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimensionId == null) {
            return null;
        }
        Set<ResourceLocation> routes = new LinkedHashSet<>();
        ListTag routeList = tag.getList("Routes", Tag.TAG_STRING);
        for (int index = 0; index < routeList.size(); index++) {
            ResourceLocation key = ResourceLocation.tryParse(routeList.getString(index));
            if (key != null) {
                routes.add(key);
            }
        }
        ItemStack icon = tag.contains("Icon") ? ItemStack.of(tag.getCompound("Icon")) : ItemStack.EMPTY;
        return new BoundContainer(
                tag.getUUID("Id"),
                tag.getString("Name"),
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                BlockPos.of(tag.getLong("Pos")),
                icon,
                parseRole(tag.getString("Role")),
                parseKind(tag.getString("Kind")),
                tag.hasUUID("Parent") ? tag.getUUID("Parent") : null,
                routes,
                tag.getLong("CreatedAt"),
                parseGroupMode(tag.getString("GroupMode")));
    }

    /** 写入网络缓冲区；字段顺序与 {@link #readFrom} 严格对应。 */
    public void writeTo(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(name, MAX_NAME_LENGTH);
        buffer.writeResourceLocation(dimension.location());
        buffer.writeBlockPos(pos);
        buffer.writeItem(iconItem);
        buffer.writeEnum(role);
        // 1.0.32 起在角色之后多写一个用途枚举（协议版本同步 +1）。
        buffer.writeEnum(kind);
        // 1.0.38 起再写一个组模式枚举（协议版本同步 +1）。
        buffer.writeEnum(groupMode);
        buffer.writeBoolean(parentId != null);
        if (parentId != null) {
            buffer.writeUUID(parentId);
        }
        buffer.writeVarInt(routeKeys.size());
        for (ResourceLocation key : routeKeys) {
            buffer.writeResourceLocation(key);
        }
        buffer.writeLong(createdAt);
    }

    /** 从网络缓冲区还原；字段损坏时返回 {@code null}，由调用方跳过这一条。 */
    public static BoundContainer readFrom(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        String name = buffer.readUtf(MAX_NAME_LENGTH);
        ResourceLocation dimensionId = buffer.readResourceLocation();
        BlockPos pos = buffer.readBlockPos();
        ItemStack icon = buffer.readItem();
        Role role = buffer.readEnum(Role.class);
        Kind kind = buffer.readEnum(Kind.class);
        GroupMode groupMode = buffer.readEnum(GroupMode.class);
        UUID parentId = buffer.readBoolean() ? buffer.readUUID() : null;
        int routeCount = buffer.readVarInt();
        if (routeCount < 0 || routeCount > 4096) {
            throw new IllegalArgumentException("绑定路由数量超出限制: " + routeCount);
        }
        Set<ResourceLocation> routes = new LinkedHashSet<>();
        for (int index = 0; index < routeCount; index++) {
            routes.add(buffer.readResourceLocation());
        }
        long createdAt = buffer.readLong();
        return new BoundContainer(id, name, ResourceKey.create(Registries.DIMENSION, dimensionId),
                pos, icon, role, kind, parentId, routes, createdAt, groupMode);
    }

    /** 解析角色名；无法识别时退回主容器，保证旧存档仍可用。 */
    private static Role parseRole(String raw) {
        for (Role candidate : Role.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        return Role.MASTER;
    }

    /** 解析用途名；旧存档没有这个字段、或值无法识别时退回物品目标。 */
    private static Kind parseKind(String raw) {
        for (Kind candidate : Kind.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        return Kind.ITEM_TARGET;
    }

    /** 解析组模式名；旧存档没有这个字段、或值无法识别时退回独立模式（1.0.35 之前的行为）。 */
    private static GroupMode parseGroupMode(String raw) {
        for (GroupMode candidate : GroupMode.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        return GroupMode.INDEPENDENT;
    }
}
