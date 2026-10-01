package com.lai.recipesender.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 屏幕下方居中的文字提示：停留 3 秒后自动淡出。
 *
 * <p>取代原来往聊天栏里塞提示的做法。聊天栏会被别的模组刷屏、切屏后也要翻回去找，
 * 而「已绑定」「放不下」这类提示是当前操作的直接反馈，应当出现在视线附近。
 *
 * <p>绘制分两处、但<b>每帧只画一次</b>：没有界面时走 {@code RenderGuiEvent.Post}（HUD 层），
 * 有界面时走 {@code ScreenEvent.Render.Post}（界面层）。两处都画不行——HUD 那份会透过
 * 半透明的界面背景露出来，看起来又是一层重影，正是这次要去掉的东西。
 */
final class NoticeOverlay {
    /** 提示停留时间：60 tick = 3 秒。 */
    private static final int DURATION_TICKS = 60;
    /** 最后 10 tick 淡出，别让提示「啪」地消失。 */
    private static final int FADE_TICKS = 10;
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 4;
    /** 提示框底边距屏幕底部的距离：让开快捷栏与手持物品名。 */
    private static final int BOTTOM_MARGIN = 46;
    private static final int BACKGROUND = 0xC0000000;
    private static final int ACCENT = 0xFF4F8A3F;
    private static final int TEXT = 0xFFFFFFFF;

    private static Component text;
    private static int ticks;

    private NoticeOverlay() {
    }

    /**
     * 显示一条提示。
     *
     * <p>已有提示时<b>直接替换</b>而不是排队：连续操作产生的旧提示已经没有意义，
     * 排队只会让玩家看到一串过时的话。
     */
    static void show(Component notice) {
        if (notice == null) {
            return;
        }
        text = notice;
        ticks = DURATION_TICKS;
    }

    /** 每客户端 tick 递减一次剩余时间。 */
    static void tick() {
        if (ticks > 0) {
            ticks--;
        }
    }

    /** HUD 层入口：只在没有界面时画，避免和界面层那份叠成重影。 */
    static void renderInHud(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        render(graphics);
    }

    /** 界面层入口：只在有界面时画，否则会被界面背景盖住。 */
    static void renderInScreen(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen == null) {
            return;
        }
        render(graphics);
    }

    private static void render(GuiGraphics graphics) {
        if (ticks <= 0 || text == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int width = font.width(text) + PADDING_X * 2;
        int height = font.lineHeight + PADDING_Y * 2;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN - height;
        int alpha = ticks >= FADE_TICKS ? 255 : Math.max(0, 255 * ticks / FADE_TICKS);
        graphics.fill(x, y, x + width, y + height, withAlpha(BACKGROUND, alpha));
        graphics.fill(x, y, x + width, y + 1, withAlpha(ACCENT, alpha));
        // 不画投影：浅底深字再叠一层投影就是「重影」，1.0.22 已经把界面里的居中文字统一成无投影了。
        graphics.drawString(font, text, x + PADDING_X, y + PADDING_Y, withAlpha(TEXT, alpha), false);
    }

    /** 把颜色整体的透明度乘上 {@code alpha}（0-255）。 */
    private static int withAlpha(int color, int alpha) {
        int scaled = (color >>> 24) * alpha / 255;
        return scaled << 24 | (color & 0xFFFFFF);
    }
}
