package com.lai.recipesender.client;

import com.lai.recipesender.model.NoticeSeverity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 屏幕下方居中的文字提示：停留 3 秒后自动淡出。
 *
 * <p>取代原来往聊天栏里塞提示的做法。聊天栏会被别的模组刷屏、切屏后也要翻回去找，
 * 而「已绑定」「放不下」这类提示是当前操作的直接反馈，应当出现在视线附近。
 *
 * <p>绘制分两处、但<b>每帧只画一次</b>：没有界面时走 {@code RenderGuiEvent.Post}（HUD 层），
 * 有界面时走 {@code ScreenEvent.Render.Post}（界面层）。两处都画不行——HUD 那份会透过
 * 半透明的界面背景露出来，看起来又是一层重影，正是这次要去掉的东西。
 *
 * <p><b>失败要是红的</b>：{@link NoticeSeverity#ERROR} 用暗红底 + 亮红顶条，正文仍是纯白——
 * 红底红字在游戏里的深色画面上根本读不出来，「提示显眼」不能以「看不清写的什么」为代价。
 * 文案过长会自动折行（中文没有空格，按字符断），不会横穿整个屏幕。
 */
final class NoticeOverlay {
    /** 提示停留时间：60 tick = 3 秒。 */
    private static final int DURATION_TICKS = 60;
    /** 最后 10 tick 淡出，别让提示「啪」地消失。 */
    private static final int FADE_TICKS = 10;
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 4;
    /**
     * 提示框底边距屏幕底部的距离。
     *
     * <p>要让开两样东西：快捷栏本身，以及快捷栏上方那行「手持物品名」——后者画在
     * {@code guiHeight - 59} 附近，之前留 46 正好和它叠在一起。
     */
    private static final int BOTTOM_MARGIN = 62;
    /** 正文一行的宽度上限：提示是「一眼看完」的东西，不该横穿半个屏幕。 */
    private static final int MAX_TEXT_WIDTH = 320;

    private static final int BACKGROUND_INFO = 0xC0000000;
    private static final int BACKGROUND_WARN = 0xC04A3A00;
    private static final int BACKGROUND_ERROR = 0xC0701010;
    private static final int ACCENT_INFO = 0xFF4F8A3F;
    private static final int ACCENT_WARN = 0xFFFFC107;
    private static final int ACCENT_ERROR = 0xFFFF5555;
    /** 正文一律纯白：三种底色都是深色，白色在哪种上都读得出来。 */
    private static final int TEXT = 0xFFFFFFFF;

    private static Component text;
    private static NoticeSeverity severity = NoticeSeverity.INFO;
    private static int ticks;

    private NoticeOverlay() {
    }

    /** 显示一条普通提示。 */
    static void show(Component notice) {
        show(notice, NoticeSeverity.INFO);
    }

    /**
     * 显示一条提示。
     *
     * <p>已有提示时<b>直接替换</b>而不是排队：连续操作产生的旧提示已经没有意义，
     * 排队只会让玩家看到一串过时的话。
     */
    static void show(Component notice, NoticeSeverity level) {
        if (notice == null) {
            return;
        }
        text = notice;
        severity = level == null ? NoticeSeverity.INFO : level;
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
        List<String> lines = BoundUi.wrap(text.getString(), MAX_TEXT_WIDTH - PADDING_X * 2);
        if (lines.isEmpty()) {
            return;
        }
        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, font.width(line));
        }
        int width = contentWidth + PADDING_X * 2;
        int height = lines.size() * font.lineHeight + PADDING_Y * 2;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN - height;
        int alpha = ticks >= FADE_TICKS ? 255 : Math.max(0, 255 * ticks / FADE_TICKS);

        int background = switch (severity) {
            case WARN -> BACKGROUND_WARN;
            case ERROR -> BACKGROUND_ERROR;
            case INFO -> BACKGROUND_INFO;
        };
        int accent = switch (severity) {
            case WARN -> ACCENT_WARN;
            case ERROR -> ACCENT_ERROR;
            case INFO -> ACCENT_INFO;
        };

        graphics.fill(x, y, x + width, y + height, withAlpha(background, alpha));
        graphics.fill(x, y, x + width, y + 1, withAlpha(accent, alpha));
        // 不画投影：浅底深字再叠一层投影就是「重影」，1.0.22 已经把界面里的居中文字统一成无投影了。
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(font, lines.get(index), x + PADDING_X,
                    y + PADDING_Y + index * font.lineHeight, withAlpha(TEXT, alpha), false);
        }
    }

    /** 把颜色整体的透明度乘上 {@code alpha}（0-255）。 */
    private static int withAlpha(int color, int alpha) {
        int scaled = (color >>> 24) * alpha / 255;
        return scaled << 24 | (color & 0xFFFFFF);
    }
}
