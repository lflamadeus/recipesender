package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.model.RecipeFluidSpec;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.model.SourceScope;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.DirectInsertResultPacket;
import com.lai.recipesender.network.packet.DirectInsertToBoundPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 「直达」的服务端执行：把周围容器里的材料直接放进已绑定容器，材料<b>不经过玩家背包</b>。
 *
 * <p>它由两条既有通路拼成，两侧语义都刻意与原来保持一致：
 * <ul>
 *   <li><b>源</b>是周围容器（FindMeExtended 搜索范围 + AE2 存储）<b>加上玩家背包与快捷栏</b>：
 *       材料不进背包，所以背包里的现成材料同样可以直接搬进目标容器，份数用同一套
 *       「匹配的容器按存量从多到少消耗」算法算出来；</li>
 *   <li><b>落点</b>和绑定发送一样，是整组绑定容器（主容器 + 并列成员 + 从容器），
 *       按份均分、整份模拟通过才落、装不下的份顺延给下一个落点。</li>
 * </ul>
 *
 * <p>与绑定发送唯一的区别是材料从哪来：那个包带的是客户端在自己背包里点过名的物品堆，
 * 这里带的是配方输入描述，由服务端现场扫描、现场决定从哪个容器抽哪一份。
 *
 * <p><b>流体</b>（1.0.32 起）：开启流体支持后，配方里的流体输入必须和物品一起凑齐一份才算数。
 * 流体只能来自玩家绑定的流体中枢（{@link FluidHubService}），落点是目标组里带流体能力的成员。
 * 份数先按 物品来源 / 中枢存量 / 目标流体容量 三者取最小——抽出来的流体不可逆，绝不能抽了又灌回去。
 *
 * <p><b>流体按台均分</b>（1.0.36 起）：一块材料落地之后，按<b>每台机器实际收下的份数</b>逐台灌
 * 它那一份流体（见 {@link #pourFluidsByMember}）。
 *
 * <p><b>「能塞多少塞多少」</b>：先按存量算出这次真能搬动的份数，再按块
 * （{@link RecipeSenderConfig#chunkBatches()} 份）抽取并投放；某一块没放满就停下，差额随回执一起
 * 告诉玩家。抽出来却没落进目标的材料会先还回原处（背包来源的还背包），实在还不了的才交给玩家——
 * 绝不静默丢弃。
 *
 * <p><b>结构</b>（1.0.40）：一次请求分成四步，每步只管一件事——
 * {@link #resolveTargets} 解析落点（{@link DirectTargets}）、{@link #planSources} 数清来源与容量
 * （{@link BatchBudget}）、{@link #execute} 分块搬运、{@link #detailFor} 把差额翻译成原因。
 * 加新的一侧（比如纯流体配方）只需要让对应上限变成「无限制」，不必再改这四步的骨架。
 *
 * <p>已知的一处不精确：客户端 HUD 上的「周围现有 N 份」来自不带绑定 id 的统计请求，
 * 会把目标容器自己的存量也数进去。所以那个数字只是上限，真正搬得动几份以这里的现场规划为准，
 * 差额由回执提示说清楚。
 */
public final class DirectInsertionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private DirectInsertionService() {
    }

    /**
     * 一次抽出并投放的份数上限。
     *
     * <p>分块的意义在于控制「抽出来却放不下」的规模：目标还剩一点点空间时，一次抽满几百份会把
     * 大量材料临时搬出容器，余料还得再塞回去。默认 64 份一搬，最坏情况也只有 64 份的余料；
     * 值来自配置（{@code chunkBatches}），卡顿时可以调小。
     */
    private static int chunkBatches() {
        return Math.max(1, RecipeSenderConfig.chunkBatches());
    }

    /** 处理一次「直达」请求：从周围容器抽料，直接放进绑定容器。 */
    public static void insert(ServerPlayer player, DirectInsertToBoundPacket packet) {
        // 物品与流体至少要有一侧：纯液体配方一个物品输入都没有，正是这条请求要支持的情况；
        // 两侧都空说明客户端什么都没带，丢掉。
        if (player == null || packet == null
                || (packet.specs().isEmpty() && packet.fluids().isEmpty())) {
            return;
        }
        try {
            if (packet.batches() < 1 || packet.batches() > RecipeSenderConfig.maxBatches()
                    || !validSpecs(packet.specs()) || !validFluids(packet.fluids())) {
                reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
                return;
            }
            DirectTargets.TargetSet targets = resolveTargets(player, packet);
            if (targets == null) {
                return;
            }
            if (targets.active().isEmpty()) {
                // 独立模式下一台空闲机器都没有：一份都不投，也一份都不抽。判断必须排在扫描与抽料之前——
                // 抽出来的材料再还回去是最坏的结果（AE2 的抽取不可逆），这里宁可什么都不做。
                reply(player, packet, BoundStatus.OK, 0, BoundDetail.ALL_BUSY);
                return;
            }
            if (targets.unified() && !passCircuitGate(player, packet, targets)) {
                return;
            }
            // 源：FindMeExtended 黑名单，再加上目标组自己——从落点里掏材料再放回落点是净零动作，
            // 还会把「周围能做几份」算成假的。中枢直达不扫世界，这里给一个恒假过滤器当占位，
            // 万一哪条路径漏了判断也只会「找不到来源」，不会偷偷去拿周围的材料。
            Predicate<BlockPos> searchable = packet.scope() == SourceScope.HUB
                    ? container -> false
                    : NearbyRecipeService.containerFilter(player, targets.positions());
            SourcePlan plan = planSources(player, packet, targets, searchable);
            if (plan.blocked() != null) {
                reply(player, packet, BoundStatus.OK, 0, plan.blocked());
                return;
            }
            if (plan.placeMembers().isEmpty()) {
                // 一台都装不下配方流体：一份都不投（材料放进干等的机器里比留在原地更糟），
                // 但已经调过的电路仍要落盘。
                markChanged(targets);
                reply(player, packet, BoundStatus.OK, 0, BoundDetail.NO_FLUID_TARGET);
                return;
            }
            Execution execution = execute(player, packet, plan, searchable);
            markChanged(targets);
            reply(player, packet, BoundStatus.OK, execution.moved(),
                    detailFor(packet, plan, targets, execution));
        } catch (RuntimeException exception) {
            LOGGER.warn("直达投放配方材料失败：玩家 {}", player.getGameProfile().getName(), exception);
            reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
        }
    }

    /**
     * 校验并解析整组落点。
     *
     * @return 落点集合；校验不通过时已经回过执，返回 {@code null}
     */
    private static DirectTargets.TargetSet resolveTargets(ServerPlayer player,
                                                          DirectInsertToBoundPacket packet) {
        BoundContainer binding = BoundContainerService.find(player, packet.bindingId());
        if (binding == null || !binding.isMaster()) {
            // 与绑定发送同一条准入：只有主容器收材料，并列成员和从容器不独立接收。
            reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
            return null;
        }
        BoundGroupResolver.BoundGroup group =
                BoundGroupResolver.groupOf(BoundContainerService.list(player), binding.id());
        if (group == null) {
            reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
            return null;
        }
        BoundTargetResolver.ResolvedTarget master = BoundTargetResolver.resolve(player, group.master());
        if (!master.available()) {
            reply(player, packet, master.status(), 0, null);
            return null;
        }
        return DirectTargets.resolve(player, group, master, needsFluids(packet));
    }

    /** 整体模式下的电路闸门：在忙的机器电路必须已经符合本次要求，否则整单取消。 */
    private static boolean passCircuitGate(ServerPlayer player, DirectInsertToBoundPacket packet,
                                           DirectTargets.TargetSet targets) {
        GroupCircuitGate.Result gate =
                GroupCircuitGate.checkBusy(targets.busy(), packet.circuit(), packet.gregRecipe());
        if (gate.allowed()) {
            return true;
        }
        BoundInsertionService.reportGroupCircuitMismatch(player, gate,
                packet.circuit(), packet.gregRecipe());
        reply(player, packet, BoundStatus.OK, 0, BoundDetail.GROUP_CIRCUIT_MISMATCH);
        return false;
    }

    /**
     * 一次直达的来源与容量：流体侧、物品侧，以及把两者收在一处的份数账本。
     *
     * @param blocked        非空表示这一侧根本走不通，一份都不发
     * @param placeMembers   真正会接料的成员（已按流体容量筛过）
     * @param perBatchItems  每份配方的物品用量
     */
    private record SourcePlan(BoundDetail blocked, FluidPlan fluid, BatchBudget budget,
                              List<DirectTargets.Member> placeMembers,
                              List<ItemStack> perBatchItems,
                              List<RemoteTransferPlanner.Target> groupOverflow) {

        private static SourcePlan blocked(BoundDetail detail) {
            return new SourcePlan(detail, FluidPlan.NONE, BatchBudget.requested(0),
                    List.of(), List.of(), List.of());
        }

        /** 这一次真能发的份数。 */
        private int batches() {
            return budget.limit();
        }
    }

    /**
     * 数清来源与落点各能支持几份。
     *
     * <p>顺序不能反过来：流体侧先算，因为抽出来的流体不可逆（AE2 尤其如此），必须先知道目标
     * 吃不吃得下、中枢够不够；物品侧的周围扫描排在后面，它没有副作用。
     */
    private static SourcePlan planSources(ServerPlayer player, DirectInsertToBoundPacket packet,
                                          DirectTargets.TargetSet targets,
                                          Predicate<BlockPos> searchable) {
        FluidPlan fluid = prepareFluids(player, packet, targets.allResolved());
        if (fluid.blocked() != null) {
            return SourcePlan.blocked(fluid.blocked());
        }
        if (packet.specs().isEmpty() && !fluid.active()) {
            // 纯液体配方，但这一次没有流体需求可执行（例如服务端关掉了流体支持）：
            // 既没有物品也没有流体，一份都发不出去。
            return SourcePlan.blocked(BoundDetail.FLUID_SHORT);
        }
        // 纯液体配方没有物品要凑：连周围容器都不用扫，物品这一侧直接给满。
        // 中枢直达则把来源换成绑定的物品中枢，同样不扫世界。
        NearbyRecipeService.DirectPlan items;
        if (packet.specs().isEmpty()) {
            items = new NearbyRecipeService.DirectPlan(packet.batches(), List.of());
        } else if (packet.scope() == SourceScope.HUB) {
            if (ItemHubService.resolve(player).isEmpty()) {
                // 中枢直达却没有可用的物品中枢：一份都不抽，让玩家先去绑。
                return SourcePlan.blocked(BoundDetail.NO_ITEM_HUB);
            }
            items = NearbyRecipeService.planDirect(packet.specs(), List.of(),
                    ItemHubService.contents(player, NearbyRecipeService.relevance(packet.specs())),
                    packet.batches());
        } else {
            items = NearbyRecipeService.planDirect(packet.specs(),
                    player.getInventory().items,
                    FindMeExtendedAdapter.snapshotNearby(player, searchable,
                            NearbyRecipeService.relevance(packet.specs())),
                    packet.batches());
        }
        BatchBudget budget = BatchBudget.requested(packet.batches())
                .withItemSource(items.batches())
                .withFluidSource(fluid.active() ? fluid.available() : BatchBudget.UNLIMITED)
                .withFluidTarget(fluid.active() ? fluid.capacity() : BatchBudget.UNLIMITED);
        // 流体模式下，连一份流体都装不下的成员（没有流体仓，或仓里全是别的流体）直接不投料：
        // 配方要流体，给了材料它也只会干等，反而占着「目标已满」的名额。
        List<DirectTargets.Member> placeMembers = targets.active();
        if (fluid.active() && budget.limit() >= 1) {
            placeMembers = fluidMembers(placeMembers, fluid.perBatch());
            if (placeMembers.isEmpty()) {
                return new SourcePlan(null, fluid, budget, List.of(), List.of(),
                        targets.groupOverflowSlots());
            }
        }
        List<ItemStack> perBatchItems = items.batches() >= 1
                ? scale(items.requirements(), items.batches(), 1)
                : List.of();
        // 目标容量预检（1.0.38）：先问一句「这组落点最多还能完整收下几份」，再决定抽多少。
        // 抽出来的材料投不进去就得还回去，纯属浪费。模拟是真实容量的上界，所以封顶不会让份数
        // 变少，只会去掉本来就要还回去的那部分。
        int itemTarget = perBatchItems.isEmpty() ? BatchBudget.UNLIMITED
                : RemoteTransferPlanner.maxBatches(DirectTargets.slots(placeMembers),
                DirectTargets.ownOverflows(placeMembers), targets.groupOverflowSlots(),
                perBatchItems, budget.limit());
        return new SourcePlan(null, fluid, budget.withItemTarget(itemTarget), placeMembers, perBatchItems,
                targets.groupOverflowSlots());
    }

    /** 只留下装得下配方流体的成员。 */
    private static List<DirectTargets.Member> fluidMembers(List<DirectTargets.Member> members,
                                                           List<FluidStack> perBatch) {
        List<DirectTargets.Member> usable = new ArrayList<>(members.size());
        for (DirectTargets.Member member : members) {
            IFluidHandler handler = member.fluids();
            if (handler == null || FluidTransferPlanner.maxBatches(
                    List.of(new FluidTransferPlanner.Target(handler, null)), perBatch, 1) < 1) {
                LOGGER.debug("成员 {} 装不下配方流体，本次不投料", member.blockEntity().getBlockPos());
                continue;
            }
            usable.add(member);
        }
        return usable;
    }

    /** 一次分块搬运的结果。 */
    private record Execution(int moved, boolean sourceShort, boolean fluidShort) {
    }

    /**
     * 分块抽料并投放。
     *
     * <p>每一块从不同的成员开始：否则每一块都从主容器往下数，份数不是台数整数倍时靠后的成员
     * 永远轮不到（20 份 / 9 台会变成「前 8 台各 2~3 份、第 9 台 0 份」）。
     */
    private static Execution execute(ServerPlayer player, DirectInsertToBoundPacket packet,
                                     SourcePlan plan, Predicate<BlockPos> searchable) {
        int batches = plan.batches();
        if (batches < 1) {
            return new Execution(0, false, false);
        }
        List<DirectTargets.Member> placeMembers = plan.placeMembers();
        // 电路必须在投料之前调完，与绑定发送同一段逻辑、同一个理由。只调真的会收到材料的成员：
        // 装不下配方流体的那几台，这一次连电路都不该动。
        BoundInsertionService.applyCircuits(DirectTargets.slots(placeMembers),
                packet.circuit(), packet.gregRecipe());
        int moved = 0;
        boolean sourceShort = false;
        boolean fluidShort = false;
        // 纯液体配方没有物品可搬：份数完全由流体这一侧决定，灌进去多少就是发出去多少。
        boolean fluidOnly = plan.perBatchItems().isEmpty();
        boolean hubScope = packet.scope() == SourceScope.HUB;
        // 周围容器只枚举一次：分块循环里每块都重扫一遍附近方块实体是纯浪费，
        // 而这张表里存的是方块实体本身，抽取时读的仍是实时槽位，不会抽到空气。
        // 中枢直达的「来源表」就是绑定列表本身，不需要枚举，所以留空。
        List<BlockEntity> sources = fluidOnly || hubScope
                ? List.of()
                : FindMeExtendedAdapter.nearbyContainers(player, searchable);
        int chunkSize = chunkBatches();
        for (int start = 0; start < batches; start += chunkSize) {
            int chunk = Math.min(chunkSize, batches - start);
            List<DirectTargets.Member> chunkMembers =
                    DirectTargets.rotate(placeMembers, start % placeMembers.size());
            int done;
            if (fluidOnly) {
                // 没有材料可折算「每台收下几份」，只能按各台流体仓还能装几份来分摊。
                int[] shares = FluidTransferPlanner.allocateBatches(
                        fluidSlots(chunkMembers), plan.fluid().perBatch(), chunk);
                done = pourFluidsByMember(player, plan.fluid(), chunkMembers, shares);
                if (done < chunk) {
                    // 落点装不下这么多，或者中枢在这期间被抽走了一部分：这一块没发满。
                    fluidShort = true;
                }
            } else {
                List<ItemStack> requirements = times(plan.perBatchItems(), chunk);
                List<ItemStack> extracted = new ArrayList<>();
                if (hubScope) {
                    // 中枢直达：只从中枢抽，不从背包补料——「只拿中枢里的东西」正是这个模式的承诺。
                    ItemHubService.extract(player, requirements, extracted);
                } else {
                    FindMeExtendedAdapter.pullAllTo(player, sources, requirements, extracted);
                    takeFromInventory(player, requirements, extracted);
                }
                if (extracted.isEmpty()) {
                    sourceShort = true;
                    break;
                }
                sourceShort |= totalCount(extracted) < totalCount(requirements);
                // 先投材料、再按「每台实际收下了几份」灌流体：流体永远不会多于落地的材料，
                // 也不会出现「材料均分了、流体全灌进第一台」。
                RemoteTransferPlanner.Placement placement = RemoteTransferPlanner.placeByShares(
                        DirectTargets.slots(chunkMembers), DirectTargets.ownOverflows(chunkMembers),
                        plan.groupOverflow(), extracted, chunk);
                if (plan.fluid().active()) {
                    // 只有这次真的要灌流体才比对：没有流体需求时 pourFluidsByMember 恒返回 0，
                    // 拿它跟「每台收下的份数」比会把每一单都误判成流体不足。
                    int poured = pourFluidsByMember(player, plan.fluid(), chunkMembers,
                            placement.memberBatches());
                    if (poured < totalBatches(placement.memberBatches())) {
                        fluidShort = true;
                    }
                }
                returnLeftovers(player, packet.scope(), extracted, placement.inserted());
                done = BoundInsertionService.countInsertedBatches(requirements,
                        placement.inserted(), chunk);
            }
            moved += done;
            if (done < chunk || fluidShort) {
                // 这一块没放满：目标已经塞不下了；或者有机器没灌上流体：材料已经落地，
                // 再往下发只会重复同样的问题。两种情况都只会制造更多余料。
                break;
            }
        }
        return new Execution(moved, sourceShort, fluidShort);
    }

    /**
     * 把成员表映射成流体落点表，下标严格对齐；没有流体能力的成员留 {@code null} 占位。
     *
     * <p>纯液体配方的份数表要按位置回填到成员上（见 {@link #pourFluidsByMember}），所以这里
     * 不能把没有流体仓的机器「挤掉」，否则流体就灌到别的机器里去了。
     */
    private static List<FluidTransferPlanner.Target> fluidSlots(List<DirectTargets.Member> members) {
        List<FluidTransferPlanner.Target> slots = new ArrayList<>(members.size());
        for (DirectTargets.Member member : members) {
            slots.add(member.fluids() == null
                    ? null
                    : new FluidTransferPlanner.Target(member.fluids(), null));
        }
        return slots;
    }

    /**
     * 把「没送到」的差额翻译成给玩家看的原因。
     *
     * <p>顺序与文案绑定：只要流体这一侧不够就先说流体不足（配方含流体时「一份」是物品与流体都齐），
     * 其次才是物品不够；两者都够而没送满，就只剩「目标装不下」与「有机器在跑」两种解释。
     */
    private static BoundDetail detailFor(DirectInsertToBoundPacket packet, SourcePlan plan,
                                         DirectTargets.TargetSet targets, Execution execution) {
        if (execution.moved() >= packet.batches()) {
            return null;
        }
        if (execution.fluidShort()) {
            return BoundDetail.FLUID_SHORT;
        }
        if (execution.sourceShort()) {
            // 源只凑得出这么多，跟目标满不满无关。
            return packet.scope() == SourceScope.HUB ? BoundDetail.HUB_SHORT : BoundDetail.MATERIALS;
        }
        return switch (plan.budget().bottleneck()) {
            case FLUID_SOURCE, FLUID_TARGET -> BoundDetail.FLUID_SHORT;
            case ITEM_SOURCE -> packet.scope() == SourceScope.HUB
                    ? BoundDetail.HUB_SHORT
                    : BoundDetail.MATERIALS;
            case ITEM_TARGET, REQUESTED -> targets.skipped() > 0
                    // 空闲机器装不下的份数里，可能本来就有正在跑的那几台的一份，比笼统说「目标已满」准确。
                    ? BoundDetail.MACHINES_BUSY
                    : BoundDetail.TARGET_FULL;
        };
    }

    /** 通知整组目标内容已变化。 */
    private static void markChanged(DirectTargets.TargetSet targets) {
        for (BoundTargetResolver.ResolvedTarget target : targets.allResolved()) {
            BoundInsertionService.markChanged(target);
        }
    }

    /** 这一次是否真的要把流体一起搬。 */
    private static boolean needsFluids(DirectInsertToBoundPacket packet) {
        return RecipeSenderConfig.fluidSupport() && packet.fluids() != null
                && !packet.fluids().isEmpty();
    }

    /**
     * 把「共 {@code total} 份」的材料清单缩到 {@code chunk} 份。
     * <p>
     * 总量本来就是「每份用量 × total」，所以整除是精确的，分块不会丢掉零头。
     */
    private static List<ItemStack> scale(List<ItemStack> requirements, int total, int chunk) {
        if (total <= 0) {
            return List.of();
        }
        List<ItemStack> scaled = new ArrayList<>(requirements.size());
        for (ItemStack stack : requirements) {
            int count = stack.getCount() * chunk / total;
            if (count > 0) {
                scaled.add(stack.copyWithCount(count));
            }
        }
        return scaled;
    }

    /** 把「每份用量」乘成 {@code batches} 份的用量。 */
    private static List<ItemStack> times(List<ItemStack> perBatch, int batches) {
        List<ItemStack> scaled = new ArrayList<>(perBatch.size());
        for (ItemStack stack : perBatch) {
            int count = stack.getCount() * batches;
            if (count > 0) {
                scaled.add(stack.copyWithCount(count));
            }
        }
        return scaled;
    }

    /** 数一数一组物品堆一共多少个。 */
    private static int totalCount(List<ItemStack> stacks) {
        int total = 0;
        for (ItemStack stack : stacks) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * 把没投进目标的材料交给玩家：先塞背包，背包放不下就掉在脚边（{@code giveItemToPlayer} 的语义）。
     * <p>
     * 「能塞多少塞多少」的代价：抽出来的材料可能有一部分没落进目标（目标满了，或某个落点在
     * 模拟与执行之间变了答案）。这部分必须还回原处，绝不能静默消失。
     * <p>
     * 1.0.38 起落点只有玩家背包：以前会先试着塞回周围任意一个收得下的容器，结果余料可能被
     * 无关的机器吃掉（无过滤器的输入总线直接吞），玩家只看到「材料少了」。交给玩家虽然要占背包，
     * 但东西一定在玩家手上；背包满了掉在脚边，也是玩家看得见的位置。
     *
     * <p>中枢直达是唯一的例外：料本来就是从物品中枢抽出来的，余料先回中枢（见
     * {@link ItemHubService#insert}），中枢放不下才交给玩家。
     */
    private static void returnLeftovers(ServerPlayer player, SourceScope scope, List<ItemStack> extracted,
                                        List<ItemStack> inserted) {
        List<ItemStack> leftovers = leftoversOf(extracted, inserted);
        if (leftovers.isEmpty()) {
            return;
        }
        if (scope == SourceScope.HUB) {
            // 中枢直达的料来自物品中枢：余料先放回中枢，放不下才轮到玩家背包。
            // 反过来做等于把中枢的东西搬进背包，下一次中枢直达就会发现料少了一截。
            leftovers = ItemHubService.insert(player, leftovers);
        }
        for (ItemStack stack : leftovers) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    new InvWrapper(player.getInventory()), stack.copy(), false);
            if (!remainder.isEmpty()) {
                // 掉在地上的东西玩家未必会立刻看见（脚边、草里、机器缝里），所以在屏幕下方明说一句：
                // 只记日志等于让玩家自己去翻文件，而这恰恰是「材料少了」最容易被当成 bug 的场景。
                BoundBindingService.notice(player, NoticeSeverity.WARN,
                        "text.recipe_sender.leftover_dropped",
                        remainder.getHoverName().getString(), remainder.getCount());
                ItemHandlerHelper.giveItemToPlayer(player, remainder);
            }
        }
    }

    /** 从抽出来的材料里减去已经落进目标的部分，得到余料。 */
    private static List<ItemStack> leftoversOf(List<ItemStack> extracted, List<ItemStack> inserted) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack source : RemoteTransferPlanner.mergeByItem(extracted)) {
            int used = 0;
            for (ItemStack taken : inserted) {
                if (ItemStack.isSameItemSameTags(taken, source)) {
                    used += taken.getCount();
                }
            }
            int remaining = source.getCount() - used;
            if (remaining > 0) {
                leftovers.add(source.copyWithCount(remaining));
            }
        }
        return leftovers;
    }

    /**
     * 从玩家背包（含快捷栏）补足周围容器没抽到的部分。
     *
     * <p>用户明确要求直达把背包与快捷栏的物品一起算：材料本来就不经过背包，背包里的现成材料
     * 没有理由不算。
     */
    private static void takeFromInventory(ServerPlayer player, List<ItemStack> requirements,
                                          List<ItemStack> extracted) {
        Inventory inventory = player.getInventory();
        boolean changed = false;
        for (ItemStack requirement : requirements) {
            int missing = requirement.getCount() - countMatching(extracted, requirement);
            if (missing <= 0) {
                continue;
            }
            for (int slot = 0; slot < inventory.items.size() && missing > 0; slot++) {
                ItemStack stack = inventory.items.get(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, requirement)) {
                    continue;
                }
                int take = Math.min(missing, stack.getCount());
                addMatching(extracted, stack, take);
                stack.shrink(take);
                if (stack.isEmpty()) {
                    inventory.items.set(slot, ItemStack.EMPTY);
                }
                missing -= take;
                changed = true;
            }
        }
        if (changed) {
            inventory.setChanged();
        }
    }

    /** 把 {@code stack} 的 {@code count} 个合并进列表（同物品同 NBT 就叠加，否则新起一堆）。 */
    private static void addMatching(List<ItemStack> list, ItemStack stack, int count) {
        for (ItemStack existing : list) {
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                existing.grow(count);
                return;
            }
        }
        list.add(stack.copyWithCount(count));
    }

    /** 数一数列表里与 {@code wanted} 同物品同 NBT 的总数。 */
    private static int countMatching(List<ItemStack> list, ItemStack wanted) {
        int total = 0;
        for (ItemStack stack : list) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 校验配方流体描述；上限与 {@link RecipeFluidSpec} 自己声明的一致，不靠客户端自觉。 */
    private static boolean validFluids(List<RecipeFluidSpec> fluids) {
        if (fluids == null || fluids.isEmpty()) {
            return true;
        }
        return fluids.size() <= RecipeFluidSpec.MAX_FLUIDS
                && fluids.stream().allMatch(RecipeFluidSpec::isValid);
    }

    /**
     * 一次直达里的流体侧：中枢、能灌的落点、每份流体，以及「流体这一侧最多支持几份」。
     *
     * <p>上限拆成两项而不是直接给结果：{@link #available} 是中枢存量，{@link #capacity} 是目标容量。
     * 份数账本（{@link BatchBudget}）要分别记这两项，才能说清「是中枢没有，还是目标装不下」。
     *
     * <p>{@link #blocked} 非空表示流体这一侧根本走不通（没绑中枢 / 目标没有流体能力 / 中枢里没有
     * 这一项流体），此时一份都不发——配方含流体时「一份」是物品与流体都齐。
     */
    private record FluidPlan(BoundDetail blocked, BlockEntity hub,
                             List<FluidTransferPlanner.Target> targets,
                             List<FluidStack> perBatch, int available, int capacity) {

        private static final FluidPlan NONE = new FluidPlan(null, null, List.of(), List.of(),
                BatchBudget.UNLIMITED, BatchBudget.UNLIMITED);

        private static FluidPlan blocked(BoundDetail detail) {
            return new FluidPlan(detail, null, List.of(), List.of(), 0, 0);
        }

        /** 配方真的带流体、而且这一侧走得通时才为真。 */
        private boolean active() {
            return hub != null;
        }
    }

    /**
     * 先把流体这一侧算清楚，再决定这一次做几份。
     *
     * <p>顺序不能反过来：中枢抽出来的流体不可逆（AE2 尤其如此），所以必须先知道目标吃不吃得下，
     * 否则就会出现「抽了又灌回去」。两项上限都不超过本次请求的份数，最终由份数账本取最小。
     */
    private static FluidPlan prepareFluids(ServerPlayer player, DirectInsertToBoundPacket packet,
                                           List<BoundTargetResolver.ResolvedTarget> resolved) {
        if (!RecipeSenderConfig.fluidSupport() || packet.fluids() == null
                || packet.fluids().isEmpty()) {
            // 流体功能关闭（或配方不含流体）：完全按物品那条老路走，配方里的流体内容一律忽略。
            return FluidPlan.NONE;
        }
        BoundContainer hub = FluidHubService.find(player);
        if (hub == null) {
            return FluidPlan.blocked(BoundDetail.NO_FLUID_HUB);
        }
        FluidHubService.ResolvedHub resolvedHub = FluidHubService.resolve(player, hub);
        if (!resolvedHub.available()) {
            return FluidPlan.blocked(BoundDetail.NO_FLUID_HUB);
        }
        List<FluidTransferPlanner.Target> targets = fluidTargets(resolved);
        if (targets.isEmpty()) {
            // 目标组里没有一个成员能装流体（例如只绑了输入总线、没绑输入仓）。
            return FluidPlan.blocked(BoundDetail.NO_FLUID_TARGET);
        }
        List<FluidStack> contents = FindMeExtendedAdapter.hubFluids(resolvedHub.blockEntity());
        List<FluidStack> perBatch = FluidHubService.perBatchFluids(packet.fluids(), contents);
        if (perBatch == null) {
            // 中枢里根本没有配方要的这一项流体。
            return FluidPlan.blocked(BoundDetail.FLUID_SHORT);
        }
        int capacity = FluidTransferPlanner.maxBatches(targets, perBatch, packet.batches());
        int available = FluidHubService.countBatches(packet.fluids(), contents);
        return new FluidPlan(null, resolvedHub.blockEntity(), targets, perBatch, available, capacity);
    }

    /** 目标组里带流体能力的成员；顺序与物品落点一致（主容器、并列、从）。 */
    private static List<FluidTransferPlanner.Target> fluidTargets(
            List<BoundTargetResolver.ResolvedTarget> resolved) {
        List<FluidTransferPlanner.Target> targets = new ArrayList<>();
        for (BoundTargetResolver.ResolvedTarget target : resolved) {
            IFluidHandler handler = FindMeExtendedAdapter.fluidHandlerOf(target.blockEntity());
            if (handler != null) {
                targets.add(new FluidTransferPlanner.Target(handler, target));
            }
        }
        return targets;
    }

    /**
     * 按「每台机器实际收下了几份」把这一块的流体从中枢灌下去。
     *
     * <p>1.0.36 之前是把整块（≤ {@link RecipeSenderConfig#chunkBatches()} 份）流体一次丢给目标组，而底层的
     * {@link FluidTransferPlanner#fillReporting} 是贪心的：第一台的流体仓能被灌满，后面的机器
     * 一点都拿不到。于是材料按份均分了、流体却全在第一台，其余机器缺流体开不了工；这一块的
     * 材料如果本来就没放满，多灌进去的流体还留在机器里——玩家看到的就是「流体只发到一台、
     * 而且发多了」。
     *
     * <p>现在每台的流体量 = 它收下的份数 × 每份用量，逐台单独校验、单独灌：装不下就<b>这一台</b>
     * 不灌（材料留着，玩家补上流体后仍能开工），并如实少报份数，让上层报「流体不足」。
     *
     * <p>「收下几份」是<b>按这台实际插进去的材料折算</b>的（{@link RemoteTransferPlanner#placeByShares}
     * 的 {@code memberBatches}），不再是「它被分到了几份」：只拿到残片的机器算 0 份，不会再被灌
     * 一整份流体——那正是玩家看到的「物品放不下了，流体却灌满了这台机器」。
     *
     * <p>纯液体配方没有物品可折算，{@code counts} 由
     * {@link FluidTransferPlanner#allocateBatches} 按各台流体容量给出，这里的返回值就是本次真正
     * 发出去的份数。
     *
     * @param counts 与 {@code members} 一一对应，来自 {@link RemoteTransferPlanner.Placement#memberBatches()}
     *               或 {@link FluidTransferPlanner#allocateBatches}
     * @return 完整灌上了几份（一台都没灌上就是 0）
     */
    private static int pourFluidsByMember(ServerPlayer player, FluidPlan fluid,
                                          List<DirectTargets.Member> members, int[] counts) {
        if (!fluid.active() || counts == null || counts.length == 0) {
            return 0;
        }
        int poured = 0;
        for (int index = 0; index < counts.length && index < members.size(); index++) {
            int batches = counts[index];
            if (batches <= 0) {
                continue;
            }
            IFluidHandler handler = members.get(index).fluids();
            List<FluidStack> needed = FluidTransferPlanner.scale(fluid.perBatch(), batches);
            List<FluidTransferPlanner.Target> single = handler == null
                    ? List.of()
                    : List.of(new FluidTransferPlanner.Target(handler, null));
            if (single.isEmpty() || !FluidTransferPlanner.fits(single, needed)) {
                LOGGER.warn("玩家 {} 的直达：这一台分到了 {} 份，但它的流体仓装不下对应的 {} mB，流体未灌入",
                        player.getGameProfile().getName(), batches, totalFluid(needed));
                continue;
            }
            List<FluidStack> drained = new ArrayList<>();
            for (FluidStack need : needed) {
                FluidHubService.drain(player, fluid.hub(), need, need.getAmount(), drained);
            }
            if (totalFluid(drained) < totalFluid(needed)) {
                // 规划之后中枢被别的玩家抽走了一部分：把已经抽出来的还回去，不能让流体凭空消失。
                LOGGER.warn("玩家 {} 的直达流体不足：这一台需要 {} mB，只从中枢抽到 {} mB",
                        player.getGameProfile().getName(), totalFluid(needed), totalFluid(drained));
                for (FluidStack taken : drained) {
                    FluidHubService.insert(fluid.hub(), taken);
                }
                continue;
            }
            boolean complete = true;
            for (FluidStack leftover : FluidTransferPlanner.fillReporting(single, drained)) {
                // 正常路径不该走到这里（先 fits 再灌），真到了也要有交代：灌回中枢并记警告。
                LOGGER.warn("玩家 {} 的直达流体余料 {} mB {} 灌不进这一台，已灌回中枢",
                        player.getGameProfile().getName(), leftover.getAmount(), leftover.getFluid());
                FluidHubService.insert(fluid.hub(), leftover);
                complete = false;
            }
            if (complete) {
                poured += batches;
            }
        }
        return poured;
    }

    /** 一份份数表一共几份。 */
    private static int totalBatches(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += Math.max(0, count);
        }
        return total;
    }

    /** 数一数一组流体一共多少 mB。 */
    private static int totalFluid(List<FluidStack> fluids) {
        int total = 0;
        for (FluidStack fluid : fluids) {
            total += fluid.getAmount();
        }
        return total;
    }

    /** 校验配方输入描述；上限与周围材料统计一致，不靠客户端自觉。 */
    private static boolean validSpecs(List<RecipeIngredientSpec> specs) {
        // 空表是合法的：纯液体配方只有流体输入，物品侧本来就是空的。
        return specs != null
                && specs.size() <= RecipeIngredientSpec.MAX_INGREDIENTS
                && specs.stream().allMatch(RecipeIngredientSpec::isValid);
    }

    /**
     * 把结果回执给发起请求的玩家。
     * <p>
     * {@code requestedBatches} 一律回玩家要求的份数（不是本次规划出来的份数）：差额是给玩家看的，
     * 得对着他自己按出来的那个数字算，否则「周围只有 5 份、你要 20 份」会显示成「未放入 0 份」。
     */
    private static void reply(ServerPlayer player, DirectInsertToBoundPacket packet,
                              BoundStatus status, int insertedBatches, BoundDetail detail) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new DirectInsertResultPacket(packet.requestId(), packet.bindingId(), status,
                        insertedBatches, packet.batches(), detail, packet.scope()));
    }
}
