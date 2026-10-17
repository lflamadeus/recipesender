package com.lai.recipesender.model;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 直达的<b>取料范围</b>：这一次从哪儿找材料。
 *
 * <p>两个快捷键共用同一套管线，只是换了来源，所以这里是一个参数而不是一条新路径：
 * <ul>
 *   <li>{@link #NEARBY} 周围直达：扫玩家周围的容器（FindMeExtended 搜索范围），外加玩家背包。
 *       这是原来的行为。</li>
 *   <li>{@link #HUB} 中枢直达：只从绑定的物品中枢取料，不扫周围、也不动背包。
 *       适合「材料全放在一个中枢里、周围箱子只是机器缓冲区」的基地——既快，也不会顺手拿走
 *       不该拿的东西。</li>
 * </ul>
 *
 * <p>它只影响<b>物品</b>来源：流体始终来自流体中枢，与取料范围无关。
 */
public enum SourceScope {

    /** 周围容器 + 玩家背包（原直达）。 */
    NEARBY,
    /** 只从绑定的物品中枢取料。 */
    HUB;

    /** 空值一律按 {@link #NEARBY} 处理，保证旧客户端/漏传字段时行为不变。 */
    public static SourceScope of(SourceScope scope) {
        return scope == null ? NEARBY : scope;
    }

    /** 写入网络缓冲区；枚举序号会随协议版本一起变，调用方负责版本对齐。 */
    public void writeTo(FriendlyByteBuf buffer) {
        buffer.writeEnum(this);
    }

    /**
     * 从缓冲区读回；尾部缺字段或序号越界时退回 {@link #NEARBY}。
     *
     * <p>兼容老客户端：旧包里没有这个字段，读到这里时缓冲区已经空了。
     */
    public static SourceScope readFrom(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() <= 0) {
            return NEARBY;
        }
        try {
            return buffer.readEnum(SourceScope.class);
        } catch (IllegalArgumentException unknown) {
            return NEARBY;
        }
    }
}
