package com.lai.recipesender.model;

/**
 * 绑定容器在一次发送前的可用性判定结果。
 *
 * <p>刻意没有「距离太远」这一项：本功能不计算、不显示、也不限制距离，
 * 唯一与位置有关的限制是目标所在区块有没有加载。
 */
public enum BoundStatus {
    /** 可用。 */
    OK,
    /** 绑定记在另一个维度。 */
    DIMENSION_MISMATCH,
    /** 目标所在区块未加载；不会为了发送强制加载区块。 */
    CHUNK_UNLOADED,
    /** 目标位置已经没有方块实体了。 */
    MISSING,
    /** 方块实体存在，但不提供物品容器能力。 */
    NO_ITEM_HANDLER,
    /** 绑定数据本身有问题（不属于该玩家、id 对不上等）。 */
    FORBIDDEN
}
