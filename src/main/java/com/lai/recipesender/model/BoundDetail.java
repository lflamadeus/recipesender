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
    MATERIALS("text.recipe_sender.bound_detail.materials");

    private final String key;

    BoundDetail(String key) {
        this.key = key;
    }

    /** 对应的语言键；语言文件缺条目时由客户端按枚举给兜底文案。 */
    public String key() {
        return key;
    }
}
