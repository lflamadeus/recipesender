package com.lai.recipesender.service;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.BoundNoticePacket;
import com.lai.recipesender.network.packet.SelectBoundTargetPacket;
import com.lai.recipesender.network.packet.SyncBoundContainersPacket;
import com.lai.recipesender.network.packet.UnbindContainerPacket;
import com.lai.recipesender.network.packet.UpdateBindingPacket;
import com.lai.recipesender.network.packet.UpdateBindingRelationPacket;
import com.lai.recipesender.network.packet.UpdateBindingRoutesPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定请求的服务端处理：校验、落盘、回推同步。
 *
 * <p>服务端是绑定数据的唯一权威。客户端发来的坐标、名称都要经过这里重新推导与校验；
 * 图标一律由服务端从方块状态推导，客户端数据不参与任何落盘字段的取值。
 */
@Mod.EventBusSubscriber(modid = RecipeSenderMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BoundBindingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private BoundBindingService() {
    }

    /** 处理一次绑定请求。 */
    public static void bind(ServerPlayer player, BindContainerPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        // 只允许绑定玩家自己当前所在维度的方块：准星取到的方块必然在本地维度，
        // 收到别的维度说明客户端数据异常，直接拒绝而不是信任它。
        ResourceLocation current = player.level().dimension().location();
        if (!current.equals(packet.dimension())) {
            LOGGER.warn("拒绝跨维度绑定请求：玩家在 {}，请求 {}", current, packet.dimension());
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = packet.pos();
        if (!level.isLoaded(pos)) {
            notice(player, "text.recipe_sender.bind_unloaded");
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        List<BoundContainer> existing = BoundContainerService.list(player);
        // 已经绑过这个坐标：只回一句提示，不改名、不新增、不动任何字段。
        // 以前是继续往下走，于是 defaultName 会算出「箱子 #2」并写回同一条记录，
        // 玩家每按一次 B 就把名字往上顶一档，最后变成「箱子 #3」「箱子 #4」。
        for (BoundContainer binding : existing) {
            if (binding.samePos(player.level().dimension(), pos)) {
                notice(player, "text.recipe_sender.bound_already", binding.name());
                return;
            }
        }
        // 没有物品容器的方块绑了也发不进去，当场拒绝而不是留一条永远失败的绑定。
        if (!BoundTargetResolver.hasItemHandler(level.getBlockEntity(pos))) {
            notice(player, "text.recipe_sender.bind_no_container");
            return;
        }
        BoundContainer.Role role = packet.role() == null ? BoundContainer.Role.MASTER : packet.role();
        String relationError = validateRelation(existing, null, role, packet.parentId());
        if (relationError != null) {
            notice(player, relationError);
            return;
        }
        String name = packet.name() == null || packet.name().isBlank()
                ? defaultName(existing, state, pos) : packet.name();
        ItemStack icon = iconOf(state);
        BoundContainer bound = BoundContainerService.bind(player, player.level().dimension(), pos,
                name, icon, role, packet.parentId(), packet.routeKeys());
        sync(player);
        notifyRelation(player, bound, existing);
    }

    /**
     * 校验容器关系。
     *
     * @param selfId 正在改关系的绑定 id；新建绑定时传 {@code null}
     * @return 错误提示的语言键；通过校验时返回 {@code null}
     */
    private static String validateRelation(List<BoundContainer> existing, UUID selfId,
                                           BoundContainer.Role role, UUID parentId) {
        if (role == null || role == BoundContainer.Role.MASTER) {
            return null;
        }
        if (parentId == null) {
            return "text.recipe_sender.bind_need_parent";
        }
        if (parentId.equals(selfId)) {
            return "text.recipe_sender.bind_self_parent";
        }
        BoundContainer parent = null;
        for (BoundContainer binding : existing) {
            if (binding.id().equals(parentId)) {
                parent = binding;
                break;
            }
        }
        if (parent == null) {
            return "text.recipe_sender.bind_parent_missing";
        }
        // 只支持一级：父容器必须是主容器，所以并列成员不能挂并列成员、从容器不能再挂从容器，
        // 也就不可能出现循环挂靠。
        if (!parent.isMaster()) {
            return "text.recipe_sender.bind_parent_not_master";
        }
        return null;
    }

    /** 绑定成功后的提示：成员与从容器额外报出挂靠的主容器名。 */
    private static void notifyRelation(ServerPlayer player, BoundContainer bound,
                                       List<BoundContainer> existing) {
        if (bound.isMaster()) {
            notice(player, "text.recipe_sender.bound_added", bound.name());
            return;
        }
        String parentName = "?";
        for (BoundContainer binding : existing) {
            if (binding.id().equals(bound.parentId())) {
                parentName = binding.name();
                break;
            }
        }
        String key = bound.role() == BoundContainer.Role.SLAVE
                ? "text.recipe_sender.bound_added_slave" : "text.recipe_sender.bound_added_member";
        notice(player, key, bound.name(), parentName);
    }

    /**
     * 给玩家显示一条屏幕下方的提示。
     *
     * <p>服务端手里只有 {@link ServerPlayer}，画不出客户端的浮层，所以只把语言键与参数推过去，
     * 由客户端定稿文案（这样也顺带跟着客户端的语言走）。参数一律转成字符串：
     * 语言模板用的是 {@code %s}，数字与名称都能直接填。
     */
    private static void notice(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return;
        }
        List<String> text = new ArrayList<>(args.length);
        for (Object arg : args) {
            text.add(String.valueOf(arg));
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BoundNoticePacket(key, text));
    }

    /**
     * 处理一次「改容器关系」请求。
     *
     * <p>把主容器改成成员、或把成员改成从容器都是允许的：挂在它下面的绑定会变成
     * <b>未归组</b>（不再参与路由），但不会被连带删除——删掉的东西玩家找不回来，孤立项还能重新挂。
     */
    public static void updateRelation(ServerPlayer player, UpdateBindingRelationPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        BoundContainer existing = BoundContainerService.find(player, packet.id());
        if (existing == null) {
            return;
        }
        BoundContainer.Role role = packet.role() == null ? BoundContainer.Role.MASTER : packet.role();
        List<BoundContainer> bindings = BoundContainerService.list(player);
        String error = validateRelation(bindings, existing.id(), role, packet.parentId());
        if (error != null) {
            notice(player, error);
            return;
        }
        int orphaned = 0;
        if (existing.isMaster() && role != BoundContainer.Role.MASTER) {
            for (BoundContainer binding : bindings) {
                if (existing.id().equals(binding.parentId())) {
                    orphaned++;
                }
            }
        }
        if (BoundContainerService.setRelation(player, packet.id(), role, packet.parentId())) {
            sync(player);
            if (orphaned > 0) {
                notice(player, "text.recipe_sender.relation_orphaned", orphaned);
            }
        }
    }

    /** 处理一次解绑请求。 */
    public static void unbind(ServerPlayer player, UnbindContainerPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        if (BoundContainerService.unbind(player, packet.id())) {
            sync(player);
        }
    }

    /**
     * 处理一次改名请求。
     *
     * <p>只改名字：其它字段（维度、坐标、图标、角色、父容器、路由键）一律以服务端自己存的那份为准，
     * 客户端发来的名称也要经过 {@code BoundContainer} 的规范化（去首尾空白 + 截断）。
     */
    public static void update(ServerPlayer player, UpdateBindingPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        BoundContainer existing = BoundContainerService.find(player, packet.id());
        if (existing == null) {
            // 可能刚被别的界面删掉了：不回话也不报错，客户端下一次同步自然会看到最新列表。
            return;
        }
        if (BoundContainerService.rename(player, packet.id(), packet.name())) {
            sync(player);
        }
    }

    /**
     * 处理一次配方类别（自动路由键）修改。
     *
     * <p>类别只属于主容器：并列成员与从容器跟随父容器，收到它们的改类别请求时按空集合落盘，
     * 而不是报错——客户端可能正拿着改角色之前的旧界面，回推一次同步就自然对齐了。
     *
     * <p>键的合法性只做最基本的一道闸：null 与空字符串丢掉，其余原样收下。类别集合是玩家自己的
     * 偏好数据，多存几个不存在的键无害（永远匹配不上），而做白名单校验反而会把自定义类别
     * （GTO 子类别、整合包自加机器）挡在外面。
     */
    public static void updateRoutes(ServerPlayer player, UpdateBindingRoutesPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        BoundContainer existing = BoundContainerService.find(player, packet.id());
        if (existing == null) {
            // 可能刚被别的界面删掉了：不回话也不报错，客户端下一次同步自然会看到最新列表。
            return;
        }
        Set<ResourceLocation> routes = new LinkedHashSet<>();
        if (packet.routeKeys() != null) {
            for (ResourceLocation route : packet.routeKeys()) {
                if (route != null) {
                    routes.add(route);
                }
            }
        }
        if (BoundContainerService.setRoutes(player, packet.id(), routes)) {
            sync(player);
        }
    }

    /**
     * 处理一次「挑中了哪个发送单元」。
     *
     * <p>只接受该玩家自己名下的绑定，且必须是主容器——并列成员与从容器不是发送单元，
     * 记住它们没有意义（客户端缓存落后时可能报上来一个已经改过角色的 id）。
     */
    public static void select(ServerPlayer player, SelectBoundTargetPacket packet) {
        if (player == null || packet == null || packet.routeKey() == null) {
            return;
        }
        BoundContainer target = BoundContainerService.find(player, packet.bindingId());
        if (target == null || !target.isMaster()) {
            return;
        }
        BoundContainerService.rememberChoice(player, packet.routeKey(), target.id());
        sync(player);
    }

    /** 把该玩家当前的绑定列表与「上次选择」整体推给客户端。 */
    public static void sync(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncBoundContainersPacket(BoundContainerService.list(player),
                        BoundContainerService.lastChoices(player)));
    }

    /**
     * 生成默认名称：方块名，重名时补 {@code #2}、{@code #3}…
     * 名称只用于界面显示，允许重复；这里补后缀纯粹是让玩家一眼分得清。
     */
    private static String defaultName(List<BoundContainer> existing, BlockState state, BlockPos pos) {
        String base = BoundContainer.normalizeName(state.getBlock().getName().getString());
        String candidate = base;
        int suffix = 2;
        while (isNameTaken(existing, candidate)) {
            candidate = BoundContainer.normalizeName(base + " #" + suffix);
            suffix++;
        }
        return candidate;
    }

    /** 判断已有绑定里是否用过这个名称。 */
    private static boolean isNameTaken(List<BoundContainer> existing, String name) {
        for (BoundContainer binding : existing) {
            if (binding.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 图标默认取方块的物品形态；方块没有对应物品时留空。 */
    private static ItemStack iconOf(BlockState state) {
        ItemStack icon = state.getBlock().asItem().getDefaultInstance();
        return icon == null ? ItemStack.EMPTY : icon;
    }

    /** 玩家登录时推一次绑定列表：客户端缓存是空的，不推就没得用。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    /** 切换维度后推一次：绑定里带维度，界面需要知道哪些当前不可用。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }
}
