package com.lai.recipesender.model;

/**
 * 一条提示的严重程度。
 *
 * <p>放在 {@code model} 而不是 {@code client} 里：服务端也要用它来标注「这条是失败还是成功」，
 * 而 {@code client} 包里的类引用 GUI 类，被专用服务端加载会直接崩。
 *
 * <p>有了它，客户端就不必靠语言键的名字去猜「这条算不算失败」——那种猜法一旦有人改了键名，
 * 失败提示会安静地变回绿色。
 */
public enum NoticeSeverity {
    /** 正常反馈：绑定成功、发送完成。 */
    INFO,
    /** 提醒：还能继续操作，但玩家大概想知道。 */
    WARN,
    /** 失败：这次操作没有生效。 */
    ERROR
}
