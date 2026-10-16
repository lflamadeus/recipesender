package com.lai.recipesender.service;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.AlignGroupCircuitPacket;
import com.lai.recipesender.network.packet.BindContainerPacket;
import com.lai.recipesender.network.packet.BoundNoticePacket;
import com.lai.recipesender.network.packet.SelectBoundTargetPacket;
import com.lai.recipesender.network.packet.SetGroupModePacket;
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
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.bind_unloaded");
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
                notice(player, NoticeSeverity.WARN, "text.recipe_sender.bound_already", binding.name());
                return;
            }
        }
        BoundContainer.Kind kind = packet.kind() == null
                ? BoundContainer.Kind.ITEM_TARGET : packet.kind();
        BoundContainer.Role role;
        UUID parentId;
        Set<ResourceLocation> routes;
        if (kind == BoundContainer.Kind.FLUID_HUB) {
            // 中枢只有一个：多绑一个会让「直达该从哪抽流体」变得不确定，直接拒绝。
            for (BoundContainer binding : existing) {
                if (binding.isFluidHub()) {
                    notice(player, NoticeSeverity.WARN, "text.recipe_sender.bind_hub_exists", binding.name());
                    return;
                }
            }
            // 中枢是流体的来源，没有流体能力的方块绑了也抽不出东西。
            if (!FluidHubService.hasFluidHandler(level.getBlockEntity(pos))) {
                notice(player, NoticeSeverity.ERROR, "text.recipe_sender.bind_no_fluid");
                return;
            }
            // 中枢固定是主容器、无类别：它不参与物品分组，也不参与按类别自动路由。
            role = BoundContainer.Role.MASTER;
            parentId = null;
            routes = Set.of();
        } else {
            // 没有物品容器的方块绑了也发不进去，当场拒绝而不是留一条永远失败的绑定。
            if (!BoundTargetResolver.hasItemHandler(level.getBlockEntity(pos))) {
                notice(player, NoticeSeverity.ERROR, "text.recipe_sender.bind_no_container");
                return;
            }
            role = packet.role() == null ? BoundContainer.Role.MASTER : packet.role();
            String relationError = validateRelation(existing, null, role, packet.parentId());
            if (relationError != null) {
                notice(player, NoticeSeverity.ERROR, relationError);
                return;
            }
            parentId = packet.parentId();
            routes = packet.routeKeys();
        }
        String name = packet.name() == null || packet.name().isBlank()
                ? defaultName(existing, state, pos) : packet.name();
        ItemStack icon = iconOf(state);
        BoundContainer bound = BoundContainerService.bind(player, player.level().dimension(), pos,
                name, icon, role, kind, parentId, routes);
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
        // 层级最多两级，且每一级的父角色是固定的：
        //   并列成员 → 必须挂主容器（两台机器互相均分，挂到另一台并列成员下会变成三层）；
        //   从容器   → 可以挂主容器（组级缓冲），也可以挂并列成员（这一台机器自己的库存扩展）。
        // 从容器之间禁止挂靠，所以无论怎么连都到不了三级，也就不可能出现循环挂靠。
        if (role == BoundContainer.Role.SLAVE && parent.role() == BoundContainer.Role.PARALLEL) {
            return null;
        }
        if (!parent.isMaster()) {
            return role == BoundContainer.Role.SLAVE
                    ? "text.recipe_sender.bind_parent_not_master_or_member"
                    : "text.recipe_sender.bind_parent_not_master";
        }
        return null;
    }

    /** 绑定成功后的提示：成员与从容器额外报出挂靠的主容器名。 */
    private static void notifyRelation(ServerPlayer player, BoundContainer bound,
                                       List<BoundContainer> existing) {
        if (bound.isFluidHub()) {
            notice(player, NoticeSeverity.INFO, "text.recipe_sender.bound_added_hub", bound.name());
            return;
        }
        if (bound.isMaster()) {
            notice(player, NoticeSeverity.INFO, "text.recipe_sender.bound_added", bound.name());
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
        notice(player, NoticeSeverity.INFO, key, bound.name(), parentName);
    }

    /**
     * 给玩家显示一条屏幕下方的提示。
     *
     * <p>服务端手里只有 {@link ServerPlayer}，画不出客户端的浮层，所以只把语言键、参数与
     * <b>严重程度</b>推过去，由客户端定稿文案与颜色（这样也顺带跟着客户端的语言走）。
     * 参数一律转成字符串：语言模板用的是 {@code %s}，数字与名称都能直接填。
     *
     * <p>严重程度由调用点自己说清楚，而不是让客户端去猜键名：键名是可以随时改的，
     * 一旦改了，失败提示会安静地变回绿色。
     *
     * <p>包内可见：发送与直达的执行路径也要用它报「整体模式电路不一致」这类细节。
     */
    static void notice(ServerPlayer player, NoticeSeverity severity, String key, Object... args) {
        if (player == null) {
            return;
        }
        List<String> text = new ArrayList<>(args.length);
        for (Object arg : args) {
            text.add(String.valueOf(arg));
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BoundNoticePacket(key, text, severity));
    }

    /**
     * 处理一次「改容器关系」请求。
     *
     * <p>把主容器改成成员、或把成员改成从容器都是允许的：挂在它下面的绑定会变成
     * <b>未归组</b>（不再参与路由），但不会被连带删除——删掉的东西玩家找不回来，孤立项还能重新挂。
     *
     * <p>降级成<b>并列成员</b>时，它名下的<b>从容器仍然合法</b>（从容器可以挂到并列成员上），
     * 只有并列成员会失去父容器；降级成<b>从容器</b>则名下两者都失去父容器。提示里报的是
     * 真正变成未归组的条数，否则玩家会以为箱子也一起废了。
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
            notice(player, NoticeSeverity.ERROR, error);
            return;
        }
        int orphaned = 0;
        if (existing.isMaster() && role != BoundContainer.Role.MASTER) {
            for (BoundContainer binding : bindings) {
                if (!existing.id().equals(binding.parentId())) {
                    continue;
                }
                if (role == BoundContainer.Role.PARALLEL
                        && binding.role() == BoundContainer.Role.SLAVE) {
                    continue;
                }
                orphaned++;
            }
        }
        if (BoundContainerService.setRelation(player, packet.id(), role, packet.parentId())) {
            sync(player);
            if (orphaned > 0) {
                notice(player, NoticeSeverity.WARN, "text.recipe_sender.relation_orphaned", orphaned);
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

    /**
     * 处理一次组模式切换（独立 / 整体）。
     *
     * <p>只有主容器能切：组模式描述的是「这一组机器怎么协同」，挂在别人下面的成员自己说不清这件事。
     *
     * <p>切到整体模式时顺带只读检查一遍组内电路是否已经一致，不一致就提示玩家先用「对齐电路」——
     * 整体模式下只要有一台在跑的机器电路与本次发配不符，整单都会被取消，玩家最好在第一次发送之前
     * 就把电路对齐。
     */
    public static void setGroupMode(ServerPlayer player, SetGroupModePacket packet) {
        if (player == null || packet == null) {
            return;
        }
        BoundContainer existing = BoundContainerService.find(player, packet.id());
        if (existing == null) {
            // 可能刚被别的界面删掉了：不回话也不报错，客户端下一次同步自然会看到最新列表。
            return;
        }
        if (!existing.isMaster()) {
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.group_mode_master_only");
            return;
        }
        BoundContainer.GroupMode mode = packet.mode() == null
                ? BoundContainer.GroupMode.INDEPENDENT
                : packet.mode();
        if (existing.groupMode() == mode) {
            // 已经是这个模式：也回一次同步，客户端可能正拿着改模式之前的旧界面。
            sync(player);
            return;
        }
        if (!BoundContainerService.update(player, existing.withGroupMode(mode))) {
            return;
        }
        sync(player);
        if (mode != BoundContainer.GroupMode.UNIFIED) {
            notice(player, NoticeSeverity.INFO, "text.recipe_sender.group_mode_independent",
                    existing.name());
            return;
        }
        BoundGroupResolver.BoundGroup group =
                BoundGroupResolver.groupOf(BoundContainerService.list(player), existing.id());
        if (group != null && circuitsDiffer(player, group)) {
            notice(player, NoticeSeverity.WARN, "text.recipe_sender.group_mode_unified_mismatch",
                    existing.name());
            return;
        }
        notice(player, NoticeSeverity.INFO, "text.recipe_sender.group_mode_unified", existing.name());
    }

    /**
     * 组内已解析出来的机器里，电路槽的编号是否不统一。
     *
     * <p>只读。拿不到电路槽的成员（普通箱子、非格雷机器、反射探不到）不参与判定——它们本来就不会
     * 被整体模式的电路闸门拦下。
     */
    private static boolean circuitsDiffer(ServerPlayer player, BoundGroupResolver.BoundGroup group) {
        Integer reference = null;
        for (BoundContainer member : group.members()) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, member);
            if (!target.available()) {
                continue;
            }
            Integer circuit = GtCircuitSupport.currentCircuit(target.blockEntity());
            if (circuit == null) {
                continue;
            }
            if (reference == null) {
                reference = circuit;
            } else if (!reference.equals(circuit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理一次「一键对齐电路」。
     *
     * <p>整组由服务端现场解析（主容器 + 可用并列成员），逐个写入后<b>回读确认</b>：写不进去的机器
     * （电路槽里放着玩家自己的物品、反射拿不到槽位）会被点名报出来，而不是假装成功。
     *
     * <p>从容器不参与：它没有配方逻辑，也没有电路可言。
     */
    public static void alignGroupCircuit(ServerPlayer player, AlignGroupCircuitPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        BoundContainer master = BoundContainerService.find(player, packet.masterId());
        if (master == null || !master.isMaster()) {
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.align_circuit_no_master");
            return;
        }
        if (packet.circuit() > GtCircuitSupport.MAX_CIRCUIT || packet.circuit() < GtCircuitSupport.NO_CIRCUIT) {
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.align_circuit_invalid",
                    String.valueOf(packet.circuit()));
            return;
        }
        BoundGroupResolver.BoundGroup group =
                BoundGroupResolver.groupOf(BoundContainerService.list(player), master.id());
        if (group == null) {
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.align_circuit_no_master");
            return;
        }
        List<GroupCircuitGate.Member> members = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (BoundContainer member : group.members()) {
            BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, member);
            if (!target.available()) {
                unreachable.add(member.name());
                continue;
            }
            members.add(new GroupCircuitGate.Member(member.name(), target.blockEntity()));
        }
        if (members.isEmpty()) {
            notice(player, NoticeSeverity.ERROR, "text.recipe_sender.align_circuit_none");
            return;
        }
        // 对齐是「设置电路」而不是「投料」：非格雷配方与 gregRecipe 的区分在这里不重要，一律当作
        // 允许置空（circuit < 0 时清空电路槽），因为这是玩家显式按下的按钮。
        List<String> failed = new ArrayList<>(GroupCircuitGate.alignAll(members, packet.circuit(), true));
        failed.addAll(unreachable);
        if (failed.isEmpty()) {
            if (packet.circuit() < 0) {
                notice(player, NoticeSeverity.INFO, "text.recipe_sender.align_circuit_cleared",
                        String.valueOf(members.size()));
            } else {
                notice(player, NoticeSeverity.INFO, "text.recipe_sender.align_circuit_done",
                        String.valueOf(members.size()), String.valueOf(packet.circuit()));
            }
            return;
        }
        notice(player, NoticeSeverity.WARN, "text.recipe_sender.align_circuit_partial",
                String.valueOf(members.size() - (failed.size() - unreachable.size())),
                String.join("、", failed));
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
