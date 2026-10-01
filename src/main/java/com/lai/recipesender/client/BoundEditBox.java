package com.lai.recipesender.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 本模组统一的输入栏：右键清空 + 失焦即确认。
 *
 * <p>这两条以前在每个界面的 {@code mouseClicked} 里各写一遍，五处输入栏就有好几种写法——
 * 有的支持右键清空、有的不支持；有的把文本存在字段里、有的重建输入框就丢。集中到这里之后，
 * 界面只管把点击交给 {@code box.mouseClicked(...)}，其余交给输入栏自己。</p>
 *
 * <p>右键的原版行为（1.20.1 是粘贴剪贴板）被换成清空：搜索词往往是一长串拼音，
 * 逐字退格太烦。</p>
 */
class BoundEditBox extends EditBox {

    /** 失焦时要跑的动作；null = 失焦什么都不做。 */
    private Runnable onBlur;

    BoundEditBox(Font font, int x, int y, int width, int height, Component narration) {
        super(font, x, y, width, height, narration);
    }

    /** 失焦即确认：注册失焦回调，返回 this 便于链式构造。 */
    BoundEditBox onBlur(Runnable action) {
        this.onBlur = action;
        return this;
    }

    /**
     * 右键清空，左键照旧（定位光标、拖选）。
     *
     * <p>只认「点在框里」的右键；返回 true 表示这次点击已被吃掉，调用方不必再往下传。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && isMouseOver(mouseX, mouseY)) {
            // setValue 会顺带把光标移到末尾并触发 responder，界面上的过滤结果随之刷新。
            setValue("");
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 焦点变化时触发失焦回调。
     *
     * <p>游戏里的失焦路径都收敛到 {@code setFocused(false)}——焦点转给别的控件、界面自己
     * {@code setFocused(null)}、输入框被移除——所以在这里拦一次就够，各界面不必再自己判断
     * 「这一下点在了框外面没有」。判据用的是 super 之后的状态，{@code canLoseFocus = false}
     * 的输入框不会误报失焦。</p>
     */
    @Override
    public void setFocused(boolean focused) {
        boolean was = isFocused();
        super.setFocused(focused);
        if (was && !isFocused() && onBlur != null) {
            onBlur.run();
        }
    }
}
