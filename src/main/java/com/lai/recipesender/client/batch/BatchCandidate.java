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
 */
public record BatchCandidate(BlockPos pos, ItemStack icon, Reject reject, BoundContainer.Role role) {

    /** 候选不可用的原因。 */
    public enum Reject {
        /** 可用。 */
        NONE,
        /** 这个坐标已经绑过了。 */
        ALREADY_BOUND,
        /** 已经是物品中枢或流体中枢：物品通路不能把它当目标。 */
        HUB,
        /** 与这一组的主容器不是同类机器。 */
        NOT_SAME_KIND,
        /** 玩家自己剔除了。 */
        EXCLUDED
    }

    public boolean accepted() {
        return reject == Reject.NONE;
    }

    public BatchCandidate withRole(BoundContainer.Role newRole) {
        return new BatchCandidate(pos, icon, reject, newRole);
    }

    public BatchCandidate withReject(Reject newReject) {
        return new BatchCandidate(pos, icon, newReject, role);
    }
}
