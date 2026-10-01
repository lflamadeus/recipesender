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
 *   <li>{@link Role#SLAVE} 从容器：主容器（含并列成员）全放满后接收余量，只一级。</li>
 * </ul>
 *
 * <p>角色关系在发送时动态解析、不固化，所以主容器后来多了并列成员时，
 * 已有的从容器不需要重新绑定。
 *
 * <p>绑定数量不设上限；名称允许重复，用 {@link #id} 区分。
 */
public record BoundContainer(UUID id, String name, ResourceKey<Level> dimension, BlockPos pos,
                             ItemStack iconItem, Role role, UUID parentId,
                             Set<ResourceLocation> routeKeys, long createdAt) {

    /** 发送单元里的角色。 */
    public enum Role {
        MASTER,
        PARALLEL,
        SLAVE
    }

    /** 名称长度上限，超出部分截断。 */
    public static final int MAX_NAME_LENGTH = 32;
    private static final String UNNAMED = "未命名";

    public BoundContainer {
        name = normalizeName(name);
        iconItem = iconItem == null || iconItem.isEmpty()
                ? ItemStack.EMPTY : iconItem.copyWithCount(1);
        role = role == null ? Role.MASTER : role;
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

    /** 生成改名后的副本。 */
    public BoundContainer renamed(String newName) {
        return new BoundContainer(id, newName, dimension, pos, iconItem, role, parentId,
                routeKeys, createdAt);
    }

    /** 生成替换图标后的副本。 */
    public BoundContainer withIcon(ItemStack newIcon) {
        return new BoundContainer(id, name, dimension, pos, newIcon, role, parentId,
                routeKeys, createdAt);
    }

    /** 生成替换角色与父容器后的副本。 */
    public BoundContainer withRole(Role newRole, UUID newParentId) {
        return new BoundContainer(id, name, dimension, pos, iconItem, newRole, newParentId,
                newRole == Role.MASTER ? routeKeys : Set.of(), createdAt);
    }

    /**
     * 生成替换配方类别后的副本。
     *
     * <p>只有主容器带类别：并列成员与从容器一律清空，免得它们被当成发送单元参与路由
     * （{@link #withRole} 也按同一规则处理）。
     */
    public BoundContainer withRoutes(Set<ResourceLocation> newRoutes) {
        return new BoundContainer(id, name, dimension, pos, iconItem, role, parentId,
                isMaster() && newRoutes != null ? Set.copyOf(newRoutes) : Set.of(), createdAt);
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
                tag.hasUUID("Parent") ? tag.getUUID("Parent") : null,
                routes,
                tag.getLong("CreatedAt"));
    }

    /** 写入网络缓冲区；字段顺序与 {@link #readFrom} 严格对应。 */
    public void writeTo(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(name, MAX_NAME_LENGTH);
        buffer.writeResourceLocation(dimension.location());
        buffer.writeBlockPos(pos);
        buffer.writeItem(iconItem);
        buffer.writeEnum(role);
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
                pos, icon, role, parentId, routes, createdAt);
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
}
