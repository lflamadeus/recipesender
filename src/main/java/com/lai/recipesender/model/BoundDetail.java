package com.lai.recipesender.model;

/**
 * 回执里「为什么没送完」的结构化原因。
 *
 * <p>以前这里走的是一个自由字符串（语言键），客户端只能靠 {@code key.endsWith("target_full")}
 * 猜出中文兜底文案——键名一改，玩家就会看到「背包材料不足」这种明显不对的说明，而且没有任何
 * 编译期提示。换成枚举之后：服务端只能从这几个原因里选，客户端按枚举给兜底，漏一个原因就编译不过。
 *
 * <p>枚举放 model 包是因为服务端也要用（client 包引用了 GUI，专用服务端加载会崩）。
 */
public enum BoundDetail {

    /** 目标容器放不下：可能已满，也可能这个容器不允许放该物品。 */
    TARGET_FULL("text.recipe_sender.bound_detail.target_full"),

    /** 背包里的材料凑不齐这次发送。 */
    MATERIALS("text.recipe_sender.bound_detail.materials"),

    /** 配方带流体，但玩家没有绑定流体中枢（或中枢所在区块没加载）。 */
    NO_FLUID_HUB("text.recipe_sender.bound_detail.no_fluid_hub"),

    /** 配方带流体，但目标容器一个能装流体的都没有（比如只有物品输入总线）。 */
    NO_FLUID_TARGET("text.recipe_sender.bound_detail.no_fluid_target"),

    /** 配方带流体，中枢里的流体不够这次要发的份数。 */
    FLUID_SHORT("text.recipe_sender.bound_detail.fluid_short"),

    /**
     * 中枢直达，但玩家一个物品中枢都没绑（或绑了的中枢此刻都不可用）。
     *
     * <p>与「周围没有材料」分开说：周围直达找不到材料是正常的（可能确实没库存），
     * 中枢直达找不到材料则十有八九是忘了绑中枢——这两种情况的处理办法完全不同。
     */
    NO_ITEM_HUB("text.recipe_sender.bound_detail.no_item_hub"),

    /** 中枢直达，中枢可用但里面的材料凑不齐一份。 */
    HUB_SHORT("text.recipe_sender.bound_detail.hub_short"),

    /** 主容器与全部并列成员都在跑配方，这一组一台空闲机器都没有：一份都没投，电路也没动。 */
    ALL_BUSY("text.recipe_sender.bound_detail.all_busy"),

    /** 有机器在跑、被跳过了（既不投料也不改电路），剩下的空闲机器装不下全部份数。 */
    MACHINES_BUSY("text.recipe_sender.bound_detail.machines_busy"),

    /**
     * 整体模式的组里，正在跑的机器电路与本次发配的电路不一致：整单取消。
     *
     * <p>一台机器的电路都不动、一份材料都不投（也不抽），玩家要先把电路对齐再发。
     */
    GROUP_CIRCUIT_MISMATCH("text.recipe_sender.bound_detail.group_circuit_mismatch");

    private final String key;

    BoundDetail(String key) {
        this.key = key;
    }

    /** 对应的语言键；语言文件缺条目时由客户端按枚举给兜底文案。 */
    public String key() {
        return key;
    }
}
