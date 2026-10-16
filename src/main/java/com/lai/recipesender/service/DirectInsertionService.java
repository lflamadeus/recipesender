package com.lai.recipesender.service;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.integration.gt.MachineStateProbe;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.MachineState;
import com.lai.recipesender.model.RecipeFluidSpec;
import com.lai.recipesender.model.RecipeIngredientSpec;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 它那一份流体（见 {@link #pourFluidsByMember}）。1.0.35 及以前是把整块流体一次交给目标组，
 * 底层是贪心的，第一台会被灌满、后面的机器一点拿不到，于是「材料均分了、流体全在第一台」，
 * 多灌的部分还留在机器里。装不下配方流体的成员这次直接不投料。
 *
 * <p><b>「能塞多少塞多少」</b>：先按存量算出这次真能搬动的份数，再按块（{@link #CHUNK_BATCHES}）
 * 抽取并投放；某一块没放满就停下，差额随回执一起告诉玩家。抽出来却没落进目标的材料会先还回
 * 原处（背包来源的还背包、周围来源的还周围容器），实在还不了的才交给玩家——绝不静默丢弃。
 *
 * <p>已知的一处不精确：客户端 HUD 上的「周围现有 N 份」来自不带绑定 id 的统计请求，
 * 会把目标容器自己的存量也数进去。所以那个数字只是上限，真正搬得动几份以这里的现场规划为准，
 * 差额由回执提示说清楚。
 */
public final class DirectInsertionService {
    /**
     * 一次抽出并投放的份数上限。
     * <p>
     * 分块的意义在于控制「抽出来却放不下」的规模：目标还剩一点点空间时，一次抽满几百份会把
     * 大量材料临时搬出容器，余料还得再塞回去。八份一搬，最坏情况也只有八份的余料。
     */
    private static final int CHUNK_BATCHES = 8;
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private DirectInsertionService() {
    }

    /** 处理一次「直达」请求：从周围容器抽料，直接放进绑定容器。 */
    public static void insert(ServerPlayer player, DirectInsertToBoundPacket packet) {
        if (player == null || packet == null || packet.specs().isEmpty()) {
            return;
        }
        try {
            BoundContainer binding = BoundContainerService.find(player, packet.bindingId());
            if (binding == null || !binding.isMaster()) {
                // 与绑定发送同一条准入：只有主容器收材料，并列成员和从容器不独立接收。
                reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
                return;
            }
            if (packet.batches() < 1 || packet.batches() > RecipeSenderConfig.maxBatches()
                    || !validSpecs(packet.specs()) || !validFluids(packet.fluids())) {
                reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
                return;
            }
            BoundGroupResolver.BoundGroup group =
                    BoundGroupResolver.groupOf(BoundContainerService.list(player), binding.id());
            if (group == null) {
                reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
                return;
            }
            BoundTargetResolver.ResolvedTarget master = BoundTargetResolver.resolve(player, group.master());
            if (!master.available()) {
                reply(player, packet, master.status(), 0, null);
                return;
            }
            List<BoundTargetResolver.ResolvedTarget> resolved = new ArrayList<>();
            List<RemoteTransferPlanner.Target> members = new ArrayList<>();
            // 与 members 一一对应：按机器状态过滤时，要用它同步摘掉 resolved 里对应的那一项。
            List<BoundTargetResolver.ResolvedTarget> memberTargets = new ArrayList<>();
            // 同样与 members 一一对应：整体模式的电路闸门要在报错文案里点名是哪一台机器。
            List<String> memberNames = new ArrayList<>();
            // 同样与 members 一一对应：这一台机器<b>自己的</b>从容器（独立模式下给单台扩容的箱子）。
            // 下标必须严格对齐——规划器靠下标找「这一台的箱子」，错位就会把材料塞进别人的箱子。
            List<List<RemoteTransferPlanner.Target>> memberOverflow = new ArrayList<>();
            members.add(master.toTarget());
            memberTargets.add(master);
            memberNames.add(binding.name());
            resolved.add(master);
            memberOverflow.add(BoundInsertionService.resolveMemberSlaves(player, group, group.master(), resolved));
            for (BoundContainer parallel : group.parallels()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, parallel);
                if (!target.available()) {
                    LOGGER.debug("并列成员「{}」当前不可用（{}），本次均分跳过", parallel.name(), target.status());
                    continue;
                }
                members.add(target.toTarget());
                memberTargets.add(target);
                memberNames.add(parallel.name());
                resolved.add(target);
                memberOverflow.add(BoundInsertionService.resolveMemberSlaves(player, group, parallel, resolved));
            }
            List<RemoteTransferPlanner.Target> overflow = new ArrayList<>();
            // 组级从容器（挂在主容器下的那些）：整体模式下它们就是整组的溢出缓冲，材料由玩家自己
            // 铺的路径补进组内；独立模式下 effectiveSlaves() 返回空——那时每个成员的余量由它<b>自己</b>
            // 的从容器接住（见上面的 memberOverflow），组级从容器不再是落点。
            for (BoundContainer slave : group.effectiveSlaves()) {
                BoundTargetResolver.ResolvedTarget target = BoundTargetResolver.resolve(player, slave);
                if (!target.available()) {
                    LOGGER.debug("从容器「{}」当前不可用（{}），本次溢出跳过", slave.name(), target.status());
                    continue;
                }
                overflow.add(target.toTarget());
                resolved.add(target);
            }

            // 忙闲探测：两种模式都要，但用法完全不同。
            //
            // 独立模式（1.0.34 起的既有行为）：只往空闲机器投料、只给空闲机器调电路。机器跑到一半被改
            // 电路，手上那一炉就会按新电路重新匹配——玩家看到的就是「明明在跑却换了配方」。所以正在跑
            // 的成员这一次整个跳过：不改它的电路，也不给它投料，等它跑完，下一次发送自然会用上它。
            //
            // 整体模式（1.0.38 起）：整组被当成一台大机器，电路必须一致。在忙的机器也接料（材料由玩家
            // 自己铺的路径从从容器补进组内，这正是整体模式要解决的场景），代价是它们的电路必须已经
            // 等于本次电路，否则整单取消。
            //
            // 两种模式都只过滤主容器与并列成员：从容器是容量备份（通常是箱子），本来就没有配方逻辑，
            // 也不参与调电路，没有「跑到一半」可言。
            boolean unified = group.unified();
            List<RemoteTransferPlanner.Target> idleMembers = new ArrayList<>(members.size());
            // 与 idleMembers 一一对应：机器在忙被跳过时，它自己的从容器也一起跳过——
            // 箱子是那一台机器的扩容，机器这一趟不接料，箱子就不该替它收（收进去的材料没有归属）。
            List<List<RemoteTransferPlanner.Target>> idleOverflow = new ArrayList<>(members.size());
            List<GroupCircuitGate.Member> busyMembers = new ArrayList<>();
            int skippedMembers = 0;
            for (int index = 0; index < members.size(); index++) {
                RemoteTransferPlanner.Target member = members.get(index);
                if (MachineStateProbe.stateOf(member.blockEntity()) != MachineState.BUSY) {
                    idleMembers.add(member);
                    idleOverflow.add(memberOverflow.get(index));
                    continue;
                }
                if (unified) {
                    // 在忙也接料：记下来交给闸门判定电路，落点表里留着它。
                    busyMembers.add(new GroupCircuitGate.Member(memberNames.get(index), member.blockEntity()));
                    continue;
                }
                skippedMembers++;
                // 从 resolved 里摘掉：它不该再参与后面的收尾（markChanged、流体容量）。
                resolved.remove(memberTargets.get(index));
                LOGGER.debug("成员 {} 正在运行，本次不投料也不改电路", member.blockEntity().getBlockPos());
            }
            // 整体模式下在忙的成员照常接料，候选落点就是全组；独立模式下只有空闲机器能收。
            // 成员自己的从容器表必须跟着同一套选择走，否则下标就对不上机器了。
            List<RemoteTransferPlanner.Target> candidates = unified ? members : idleMembers;
            List<List<RemoteTransferPlanner.Target>> candidateOverflow = unified ? memberOverflow : idleOverflow;
            if (candidates.isEmpty()) {
                // 独立模式下一台空闲机器都没有：一份都不投，也一份都不抽。判断必须排在 prepareFluids 与
                // 周围扫描之前——抽出来的材料再还回去是最坏的结果（AE2 的抽取不可逆），这里宁可什么都不做。
                reply(player, packet, BoundStatus.OK, 0, BoundDetail.ALL_BUSY);
                return;
            }
            if (unified) {
                // 在忙的机器电路必须已经符合本次要求；不一致就整单取消——一台机器的电路都不动、一份材料
                // 都不抽。改它们的电路会让它们按新电路重新匹配，等于毁掉手上那批活。闸门排在这里（抽料
                // 之前）而不是投放之前：直达是先抽料再投放，放后面会让材料白进一趟玩家背包。
                GroupCircuitGate.Result gate =
                        GroupCircuitGate.checkBusy(busyMembers, packet.circuit(), packet.gregRecipe());
                if (!gate.allowed()) {
                    BoundInsertionService.reportGroupCircuitMismatch(player, gate,
                            packet.circuit(), packet.gregRecipe());
                    reply(player, packet, BoundStatus.OK, 0, BoundDetail.GROUP_CIRCUIT_MISMATCH);
                    return;
                }
            }

            // 源：FindMeExtended 黑名单，再加上目标组自己——从落点里掏材料再放回落点是净零动作，
            // 还会把「周围能做几份」算成假的。
            Predicate<BlockPos> searchable = NearbyRecipeService.containerFilter(player, groupPositions(group));

            // 流体侧先算：抽出来的流体不可逆，必须先知道目标吃不吃得下、中枢够不够，
            // 再决定这一次到底做几份（份数不够就一份都不发，避免出现半份配方）。
            FluidPlan fluid = prepareFluids(player, packet, resolved, packet.batches());
            if (fluid.blocked() != null) {
                reply(player, packet, BoundStatus.OK, 0, fluid.blocked());
                return;
            }
            NearbyRecipeService.DirectPlan plan = NearbyRecipeService.planDirect(packet.specs(),
                    player.getInventory().items,
                    FindMeExtendedAdapter.snapshotNearby(player, searchable,
                            NearbyRecipeService.relevance(packet.specs())),
                    packet.batches());

            boolean itemCapped = plan.batches() < packet.batches();
            boolean fluidCapped = fluid.active() && fluid.batches() < packet.batches();
            int sourceBatches = Math.min(plan.batches(), fluid.batches());

            // 流体模式下，连一份流体都装不下的成员（没有流体仓，或仓里全是别的流体）直接不投料：
            // 配方要流体，给了材料它也只会干等，反而占着「目标已满」的名额。
            List<RemoteTransferPlanner.Target> placeMembers = candidates;
            // 与 placeMembers 一一对应：这一台被流体容量筛掉时，它自己的从容器也一起筛掉。
            List<List<RemoteTransferPlanner.Target>> placeOverflow = candidateOverflow;
            List<IFluidHandler> memberFluidHandlers = List.of();
            if (fluid.active() && sourceBatches >= 1) {
                placeMembers = new ArrayList<>(candidates.size());
                placeOverflow = new ArrayList<>(candidates.size());
                List<IFluidHandler> handlers = new ArrayList<>(candidates.size());
                for (int index = 0; index < candidates.size(); index++) {
                    RemoteTransferPlanner.Target member = candidates.get(index);
                    IFluidHandler handler = FindMeExtendedAdapter.fluidHandlerOf(member.blockEntity());
                    if (handler == null || FluidTransferPlanner.maxBatches(
                            List.of(new FluidTransferPlanner.Target(handler, null)),
                            fluid.perBatch(), 1) < 1) {
                        LOGGER.debug("成员 {} 装不下配方流体，本次不投料",
                                member.blockEntity().getBlockPos());
                        continue;
                    }
                    placeMembers.add(member);
                    placeOverflow.add(candidateOverflow.get(index));
                    handlers.add(handler);
                }
                if (placeMembers.isEmpty()) {
                    // 一台都装不下配方流体：一份都不投（材料放进干等的机器里比留在原地更糟），
                    // 但已经调过的电路仍要落盘。
                    for (BoundTargetResolver.ResolvedTarget target : resolved) {
                        BoundInsertionService.markChanged(target);
                    }
                    reply(player, packet, BoundStatus.OK, 0, BoundDetail.NO_FLUID_TARGET);
                    return;
                }
                memberFluidHandlers = handlers;
            }

            // 目标容量预检（1.0.38）：先问一句「这组落点最多还能完整收下几份」，再决定抽多少。
            // 抽出来的材料投不进去就得还回去（还回背包要占格子、还回周围容器可能喂给无关的机器、
            // 背包放不下还会掉在脚边），纯属浪费。模拟是真实容量的上界，所以封顶不会让份数变少，
            // 只会去掉本来就要还回去的那部分；1.0.31 那个「模拟说装得下、insertItem 退回来」的
            // 缺口仍会漏出少量余料，由余料兜底接住。
            int targetBatches = sourceBatches < 1 ? 0 : RemoteTransferPlanner.maxBatches(
                    placeMembers, placeOverflow, overflow, scale(plan.requirements(), plan.batches(), 1),
                    sourceBatches);
            boolean targetCapped = targetBatches < sourceBatches;
            int batches = targetBatches;
            // 源与流体都够、只是目标一次收不下这么多时留 null：差额交给下面的链说成
            // TARGET_FULL / MACHINES_BUSY 才准确，说「材料不足」会误导。
            BoundDetail capDetail = null;
            if (batches < packet.batches() && !targetCapped) {
                capDetail = fluidCapped && fluid.batches() <= plan.batches()
                        ? BoundDetail.FLUID_SHORT
                        : BoundDetail.MATERIALS;
            }

            int moved = 0;
            boolean sourceShort = false;
            boolean fluidShort = false;
            if (batches >= 1) {
                // 电路必须在投料之前调完，与绑定发送同一段逻辑、同一个理由。只调真的会收到材料的成员：
                // 装不下配方流体的那几台，这一次连电路都不该动。
                BoundInsertionService.applyCircuits(placeMembers, packet.circuit(), packet.gregRecipe());
                List<ItemStack> perChunk = scale(plan.requirements(), plan.batches(), batches);
                for (int start = 0; start < batches; start += CHUNK_BATCHES) {
                    int chunk = Math.min(CHUNK_BATCHES, batches - start);
                    List<ItemStack> requirements = scale(perChunk, batches, chunk);
                    List<ItemStack> extracted = new ArrayList<>();
                    FindMeExtendedAdapter.pullAllTo(player, requirements, searchable, extracted);
                    takeFromInventory(player, requirements, extracted);
                    if (extracted.isEmpty()) {
                        sourceShort = true;
                        break;
                    }
                    sourceShort |= totalCount(extracted) < totalCount(requirements);
                    // 每一块从不同的成员开始：否则每一块都从主容器往下数，份数不是台数整数倍时
                    // 靠后的成员永远轮不到（20 份 / 9 台会变成「前 8 台各 2~3 份、第 9 台 0 份」）。
                    int offset = placeMembers.isEmpty() ? 0 : start % placeMembers.size();
                    List<RemoteTransferPlanner.Target> chunkMembers = rotate(placeMembers, offset);
                    // 成员表转了，成员自己的从容器表必须跟着转同样的偏移，否则箱子会配错机器。
                    List<List<RemoteTransferPlanner.Target>> chunkOverflow = rotate(placeOverflow, offset);
                    List<IFluidHandler> chunkHandlers = rotate(memberFluidHandlers, offset);
                    // 先投材料、再按「每台实际收下了几份」灌流体：流体永远不会多于落地的材料，
                    // 也不会出现「材料均分了、流体全灌进第一台」。
                    RemoteTransferPlanner.Placement placement = RemoteTransferPlanner.placeByShares(
                            chunkMembers, chunkOverflow, overflow, extracted, chunk);
                    if (!pourFluidsByMember(player, fluid, chunkHandlers,
                            placement.memberBatches())) {
                        fluidShort = true;
                    }
                    returnLeftovers(player, extracted, placement.inserted());
                    int done = BoundInsertionService.countInsertedBatches(requirements,
                            placement.inserted(), chunk);
                    moved += done;
                    if (done < chunk) {
                        // 这一块没放满：目标已经塞不下了，继续抽只会制造更多余料。
                        break;
                    }
                    if (fluidShort) {
                        // 有机器没灌上流体：材料已经落地，再往下发只会重复同样的问题。
                        break;
                    }
                }
            }
            for (BoundTargetResolver.ResolvedTarget target : resolved) {
                BoundInsertionService.markChanged(target);
            }
            BoundDetail detail;
            if (moved >= packet.batches()) {
                detail = null;
            } else if (fluidShort || fluidCapped) {
                detail = BoundDetail.FLUID_SHORT;
            } else if (sourceShort || itemCapped) {
                // 源只凑得出这么多，跟目标满不满无关。
                detail = BoundDetail.MATERIALS;
            } else if (capDetail != null) {
                detail = capDetail;
            } else if (skippedMembers > 0) {
                // 空闲机器装不下的份数里，可能本来就有正在跑的那几台的一份，比笼统说「目标已满」准确。
                detail = BoundDetail.MACHINES_BUSY;
            } else {
                detail = BoundDetail.TARGET_FULL;
            }
            reply(player, packet, BoundStatus.OK, moved, detail);
        } catch (RuntimeException exception) {
            LOGGER.warn("直达投放配方材料失败：玩家 {}", player.getGameProfile().getName(), exception);
            reply(player, packet, BoundStatus.FORBIDDEN, 0, null);
        }
    }

    /**
     * 把「共 {@code total} 份」的材料清单缩到 {@code chunk} 份。
     * <p>
     * 总量本来就是「每份用量 × total」，所以整除是精确的，分块不会丢掉零头。
     */
    private static List<ItemStack> scale(List<ItemStack> requirements, int total, int chunk) {
        List<ItemStack> scaled = new ArrayList<>(requirements.size());
        for (ItemStack stack : requirements) {
            int count = stack.getCount() * chunk / total;
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
     * 把列表循环左移 {@code offset} 位（分块轮换起点用，见 {@link #CHUNK_BATCHES}）。
     * <p>
     * 返回的是同一个列表本身（{@code offset <= 0} 或空表时），调用方不要修改它。
     */
    private static <T> List<T> rotate(List<T> source, int offset) {
        if (source.isEmpty() || offset <= 0) {
            return source;
        }
        List<T> rotated = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            rotated.add(source.get((index + offset) % source.size()));
        }
        return rotated;
    }

    /**
     * 目标组自身占据的坐标，统计与抽取时都要排除。
     * <p>
     * 排除的是整组而不只是主容器：并列成员和从容器同样是落点，从它们里面掏材料再放回去，
     * 净效果为零，却会打乱目标里已经分好的份数。
     * <p>
     * 独立模式下组级从容器不参与（{@link BoundGroupResolver.BoundGroup#effectiveSlaves()} 返回空），
     * 它们此刻只是普通箱子，里面的材料是可以用的来源；而每个成员<b>自己</b>的从容器仍然是落点，
     * 必须排除——那正是 {@code effectiveMemberSlaves} 给出的那批。
     */
    private static Set<BlockPos> groupPositions(BoundGroupResolver.BoundGroup group) {
        Set<BlockPos> positions = new HashSet<>();
        positions.add(group.master().pos());
        for (BoundContainer parallel : group.parallels()) {
            positions.add(parallel.pos());
        }
        for (BoundContainer slave : group.effectiveSlaves()) {
            positions.add(slave.pos());
        }
        // 独立模式下的成员私有从容器不在 effectiveSlaves() 里（那时它返回空），但它同样是落点，
        // 漏掉它就会从「这一台的扩容箱」里掏出材料再放回去，白折腾还可能打乱份数。
        for (BoundContainer member : group.members()) {
            for (BoundContainer slave : group.effectiveMemberSlaves(member)) {
                positions.add(slave.pos());
            }
        }
        return positions;
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
     */
    private static void returnLeftovers(ServerPlayer player, List<ItemStack> extracted,
                                        List<ItemStack> inserted) {
        for (ItemStack stack : leftoversOf(extracted, inserted)) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    new InvWrapper(player.getInventory()), stack.copy(), false);
            if (!remainder.isEmpty()) {
                LOGGER.warn("玩家 {} 的直达余料 {} × {} 背包放不下，已掉落在脚边",
                        player.getGameProfile().getName(), remainder.getHoverName().getString(),
                        remainder.getCount());
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
     * <p>{@link #blocked} 非空表示流体这一侧根本走不通（没绑中枢 / 目标没有流体能力 / 中枢里没有
     * 这一项流体），此时一份都不发——配方含流体时「一份」是物品与流体都齐。
     */
    private record FluidPlan(BoundDetail blocked, BlockEntity hub,
                             List<FluidTransferPlanner.Target> targets,
                             List<FluidStack> perBatch, int batches) {

        private static final FluidPlan NONE =
                new FluidPlan(null, null, List.of(), List.of(), Integer.MAX_VALUE);

        private static FluidPlan blocked(BoundDetail detail) {
            return new FluidPlan(detail, null, List.of(), List.of(), 0);
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
     * 否则就会出现「抽了又灌回去」。份数取 中枢存量 与 目标容量 的较小值，两者都不超过物品侧。
     */
    private static FluidPlan prepareFluids(ServerPlayer player, DirectInsertToBoundPacket packet,
                                           List<BoundTargetResolver.ResolvedTarget> resolved,
                                           int itemBatches) {
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
        int capacity = FluidTransferPlanner.maxBatches(targets, perBatch, itemBatches);
        int available = FluidHubService.countBatches(packet.fluids(), contents);
        return new FluidPlan(null, resolvedHub.blockEntity(), targets, perBatch,
                Math.min(available, capacity));
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
     * <p>1.0.36 之前是把整块（≤ {@link #CHUNK_BATCHES} 份）流体一次丢给目标组，而底层的
     * {@link FluidTransferPlanner#fillReporting} 是贪心的：第一台的流体仓能被灌满，后面的机器
     * 一点都拿不到。于是材料按份均分了、流体却全在第一台，其余机器缺流体开不了工；这一块的
     * 材料如果本来就没放满，多灌进去的流体还留在机器里——玩家看到的就是「流体只发到一台、
     * 而且发多了」。
     *
     * <p>现在每台的流体量 = 它收下的份数 × 每份用量，逐台单独校验、单独灌：装不下就<b>这一台</b>
     * 不灌（材料留着，玩家补上流体后仍能开工），并如实返回 {@code false} 让上层报「流体不足」。
     *
     * <p>1.0.39 起「收下几份」是<b>按这台实际插进去的材料折算</b>的（{@link
     * RemoteTransferPlanner#placeByShares} 的 {@code memberBatches}），不再是「它被分到了几份」：
     * 只拿到残片的机器算 0 份，不会再被灌一整份流体——那正是玩家看到的「物品放不下了，流体却
     * 灌满了这台机器」。
     *
     * @param counts 与 {@code handlers} 一一对应，来自
     *               {@link RemoteTransferPlanner.Placement#memberBatches()}
     * @return {@code true} 表示所有该灌的机器都完整灌上了
     */
    private static boolean pourFluidsByMember(ServerPlayer player, FluidPlan fluid,
                                              List<IFluidHandler> handlers, int[] counts) {
        if (!fluid.active() || counts == null || counts.length == 0) {
            return true;
        }
        boolean complete = true;
        for (int index = 0; index < counts.length; index++) {
            int batches = counts[index];
            if (batches <= 0 || index >= handlers.size()) {
                continue;
            }
            IFluidHandler handler = handlers.get(index);
            List<FluidStack> needed = FluidTransferPlanner.scale(fluid.perBatch(), batches);
            List<FluidTransferPlanner.Target> single = handler == null
                    ? List.of()
                    : List.of(new FluidTransferPlanner.Target(handler, null));
            if (single.isEmpty() || !FluidTransferPlanner.fits(single, needed)) {
                LOGGER.warn("玩家 {} 的直达：这一台收下了 {} 份材料，但它的流体仓装不下对应的 {} mB，流体未灌入",
                        player.getGameProfile().getName(), batches, totalFluid(needed));
                complete = false;
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
                complete = false;
                continue;
            }
            for (FluidStack leftover : FluidTransferPlanner.fillReporting(single, drained)) {
                // 正常路径不该走到这里（先 fits 再灌），真到了也要有交代：灌回中枢并记警告。
                LOGGER.warn("玩家 {} 的直达流体余料 {} mB {} 灌不进这一台，已灌回中枢",
                        player.getGameProfile().getName(), leftover.getAmount(), leftover.getFluid());
                FluidHubService.insert(fluid.hub(), leftover);
                complete = false;
            }
        }
        return complete;
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
        return specs != null && !specs.isEmpty()
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
                        insertedBatches, packet.batches(), detail));
    }
}
