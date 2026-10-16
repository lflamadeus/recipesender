package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 整体模式并列组的电路一致性闸门。
 *
 * <p>{@link com.lai.recipesender.model.BoundContainer.GroupMode#UNIFIED 整体模式}把一组机器当成一台
 * 大机器用：组内电路必须一致。原因是整体模式下从容器会被玩家自己铺的路径抽到组内任意一台机器上，
 * 材料落在哪台取决于哪台先要料——如果各机器电路不同，同一批材料就会按不同配方被加工掉。
 *
 * <p>闸门只拦「正在工作」的机器：改它们的电路会让它们按新电路重新匹配配方，等于毁掉手上那批活。
 * 规则是
 * <ul>
 *   <li><b>整组空闲</b>：随便改，全组写成本次电路；</li>
 *   <li><b>有机器在忙</b>：它们在忙的那一刻的电路必须<b>已经</b>等于本次电路（对它们写入是 no-op），
 *       只要有一台不一致就整单取消——一台机器的电路都不动，一份材料都不投、不抽。</li>
 * </ul>
 * 刻意不做排队、不做部分发送：玩家要的是「要么这一批就是我要的配方，要么什么都别动」。
 *
 * <p><b>判定必须在抽料之前跑</b>：直达是先抽料再投放，闸门放在后面会让材料白白进一趟玩家背包
 * （再从背包还回去、甚至变成掉落物）。
 *
 * <p>拿不到电路槽的成员（普通箱子、非格雷机器、或反射探不到）一律视为通过——整体模式的从容器
 * 也是这种成员，它们本来就不参与电路判定。
 */
public final class GroupCircuitGate {

    /**
     * 参与判定的一台机器。
     *
     * <p>{@code name} 只用于报错文案（告诉玩家是哪一台不一致），{@code blockEntity} 用来读写电路。
     */
    public record Member(String name, BlockEntity blockEntity) {
    }

    /**
     * 判定结果。
     *
     * @param allowed        是否放行
     * @param blocker        拦路的机器名；{@code allowed} 为 true 时是 null
     * @param blockerCircuit 拦路机器当前的电路编号；槽里放着玩家自己的物品时是
     *                       {@link GtCircuitSupport#NO_CIRCUIT}
     */
    public record Result(boolean allowed, String blocker, int blockerCircuit) {

        private static final Result PASS = new Result(true, null, 0);

        /** 放行。 */
        public static Result pass() {
            return PASS;
        }

        /** 取消，并指出是哪一台机器拦住的。 */
        public static Result blocked(String blocker, int blockerCircuit) {
            return new Result(false, blocker, blockerCircuit);
        }
    }

    private GroupCircuitGate() {
    }

    /**
     * 只检查<b>正在工作</b>的成员：它们的电路必须已经符合本次要求。
     *
     * @param busyMembers 本次发配时探到在忙的成员（空闲成员不必传进来）
     * @return 放行，或第一台拦路机器
     */
    public static Result checkBusy(List<Member> busyMembers, int circuit, boolean gregRecipe) {
        if (busyMembers == null || busyMembers.isEmpty()) {
            return Result.pass();
        }
        for (Member member : busyMembers) {
            if (member == null || member.blockEntity() == null) {
                continue;
            }
            if (!matches(member.blockEntity(), circuit, gregRecipe)) {
                Integer current = GtCircuitSupport.currentCircuit(member.blockEntity());
                return Result.blocked(member.name(), current == null ? GtCircuitSupport.NO_CIRCUIT : current);
            }
        }
        return Result.pass();
    }

    /**
     * 把整组电路写成目标状态，写完回读确认。
     *
     * <p>回读是必须的：{@link MachineCircuitService#applyToBlockEntity} 会跳过读不出的槽位，机器也可能
     * 因为槽里放着玩家自己的物品而拒绝写入。回读不到位的成员会被收集起来交给调用方决定怎么报。
     *
     * @param circuit 0-{@link GtCircuitSupport#MAX_CIRCUIT} 表示设置成该编号；{@code < 0} 且
     *                {@code gregRecipe} 为真表示置空
     * @return 没能对齐的成员名（可能为空）
     */
    public static List<String> alignAll(List<Member> members, int circuit, boolean gregRecipe) {
        List<String> failed = new ArrayList<>();
        if (members == null || members.isEmpty()) {
            return failed;
        }
        for (Member member : members) {
            if (member == null || member.blockEntity() == null) {
                continue;
            }
            MachineCircuitService.applyToBlockEntity(member.blockEntity(), circuit, gregRecipe);
            if (!matches(member.blockEntity(), circuit, gregRecipe)) {
                failed.add(member.name());
            }
        }
        return failed;
    }

    /**
     * 这台机器的电路是否已经符合本次要求。
     *
     * <p>与 {@link MachineCircuitService#applyToBlockEntity} 同一条纪律：非格雷配方（{@code circuit < 0}
     * 且 {@code !gregRecipe}）一律不动电路，因此也算「符合」。没有可调整电路槽的机器同样放行。
     *
     * <p>读到的编号有三种互不相同的形态，必须分清：{@code null} = 没有可调整的电路槽；
     * {@code 0} = 空槽（GT 把空槽当电路 0，两者对配方匹配等价）；{@link GtCircuitSupport#NO_CIRCUIT}
     * （-1）= 槽里放着玩家自己的物品。所以要求「不要电路」（{@code circuit < 0} 且是格雷配方）时，
     * 只有真正装着 {@code >= 1} 号电路的机器才算冲突——对它写入会把电路清掉、打断手上那批活；
     * 空槽与异物槽都不会被改动（清空空槽是 no-op，异物槽会被 {@code writeCircuit} 跳过），一律放行。
     */
    public static boolean matches(BlockEntity blockEntity, int circuit, boolean gregRecipe) {
        if (blockEntity == null || (circuit < 0 && !gregRecipe)) {
            return true;
        }
        Integer current = GtCircuitSupport.currentCircuit(blockEntity);
        if (current == null) {
            return true;
        }
        return circuit >= 0 ? current == circuit : current <= 0;
    }
}
