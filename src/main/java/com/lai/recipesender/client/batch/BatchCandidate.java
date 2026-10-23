package com.lai.recipesender.client.batch;

import com.lai.recipesender.model.BoundContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 框选出来的一个候选方块。
 *
 * <p>{@link Reject} 只回答「为什么不能用」，能用时是 {@link Reject#NONE}，此时 {@link #role()}
 * 才是它在这次批量绑定里的角色。拒绝原因要留着——确认页逐条说明比静默丢弃有用得多。
 *
 * <p>{@link Reject#EXCLUDED} 是玩家自己剔除的，与「扫出来就不可用」分开记：两者都能在确认页
 * 里重新加回来，只是剔除项默认不提交。
 *
 * <p>{@link #machine()} 是「能不能当主容器」的判据：格雷的机器/总线/仓是 {@code true}，
 * 箱子、桶、抽屉、接口、其它模组的机器是 {@code false}（只能当从容器）。
 *
 * <p>{@link #group()} 是 1 起的组号（0 = 还没分组），{@link #parent()} 是从容器挂靠的那台机器的
 * 坐标（不是从容器时为 {@code null}）。确认页的编号 `2-3` / `2-3'` 就是从这两个字段来的。
 */
public record BatchCandidate(BlockPos pos, ItemStack icon, Reject reject, BoundContainer.Role role,
                             boolean machine, int group, BlockPos parent) {

    /** 候选不可用的原因。 */
    public enum Reject {
        /** 可用。 */
        NONE,
        /** 这个坐标已经绑过了。 */
        ALREADY_BOUND,
        /** 已经是物品中枢或流体中枢：物品通路不能把它当目标。 */
        HUB,
        /** 玩家自己剔除了。 */
        EXCLUDED,
        /**
         * 这一片配不起来（机器不是恰好一台，或者非机器不是恰好一个）。
         *
         * <p>单独一种原因而不是复用 {@link #EXCLUDED}：玩家没有剔除它，是规则不让他绑。
         * 混在一起会让「为什么提交不了」说不清楚。
         */
        UNPAIRED
    }

    /** 刚扫出来、还没规划的样子。 */
    public static BatchCandidate unplanned(BlockPos pos, ItemStack icon, Reject reject, boolean machine) {
        return new BatchCandidate(pos, icon, reject, null, machine, 0, null);
    }

    public boolean accepted() {
        return reject == Reject.NONE;
    }

    public BatchCandidate withReject(Reject newReject) {
        return new BatchCandidate(pos, icon, newReject, role, machine, group, parent);
    }

    /** 规划结果：角色 + 组号 + 挂靠的机器坐标。 */
    public BatchCandidate withPlan(BoundContainer.Role newRole, int newGroup, BlockPos newParent) {
        return new BatchCandidate(pos, icon, reject, newRole, machine, newGroup, newParent);
    }
}
