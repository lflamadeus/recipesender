package com.lai.recipesender.model;

/**
 * 绑定容器在一次发送前的可用性判定结果。
 *
 * <p>与位置有关的判定有两项：目标所在区块有没有加载，以及目标离玩家有多远。
 * 距离上限来自配置项 {@code recipeManagementDistance}（默认 128 格，-1 表示不限），
 * 判定发生在服务端；绑定管理界面的存活性提示仍然只看「维度 / 区块 / 方块实体」三件事，
 * 不显示距离，免得玩家走动时列表里的标签来回跳。
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
    /** 方块实体存在，但不提供任何流体能力（既不是 AE2 存储，也没有 Forge 流体罐）。 */
    NO_FLUID_HANDLER,
    /** 绑定数据本身有问题（不属于该玩家、id 对不上等）。 */
    FORBIDDEN,
    /** 目标离玩家超过配置的配方管理距离。 */
    TOO_FAR
}
