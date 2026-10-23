package com.lai.recipesender.client;

import com.lai.recipesender.model.NoticeSeverity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕下方居中的文字提示：停留 3 秒后自动淡出。
 *
 * <p>取代原来往聊天栏里塞提示的做法。聊天栏会被别的模组刷屏、切屏后也要翻回去找，
 * 而「已绑定」「放不下」这类提示是当前操作的直接反馈，应当出现在视线附近。
 *
 * <p><b>两条通道</b>：{@link #show} 是瞬态回执（3 秒淡出、可被下一条替换）；
 * {@link #showSticky} 是常驻状态（批量绑定这类「模式还在生效」，只能显式清除）。
 * 两者同一个底部锚点、同一套底色与折行，常驻块贴底固定、瞬态块叠在它上方——
 * 这样瞬态提示来去时常驻块不会上下跳。常驻块只让左侧竖条呼吸，正文恒亮（见
 * {@link #breathingAlpha()}）。
 *
 * <p>绘制分两处、但<b>每帧只画一次</b>：没有界面时走 {@code RenderGuiEvent.Post}（HUD 层），
 * 有界面时走 {@code ScreenEvent.Render.Post}（界面层）。两处都画不行——HUD 那份会透过
 * 半透明的界面背景露出来，看起来又是一层重影，正是这次要去掉的东西。
 * 常驻块只在 HUD 层画：界面里已经有标题和计数了。
 *
 * <p><b>失败要是红的</b>：{@link NoticeSeverity#ERROR} 用暗红底 + 亮红顶条，正文仍是纯白——
 * 红底红字在游戏里的深色画面上根本读不出来，「提示显眼」不能以「看不清写的什么」为代价。
 * 文案过长会自动折行（中文没有空格，按字符断），不会横穿整个屏幕。
 */
public final class NoticeOverlay {
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

    /** 常驻块与瞬态块之间的空隙。 */
    private static final int STICKY_GAP = 2;
    /**
     * 常驻块的左侧竖条宽度。
     *
     * <p>瞬态提示是 1px 顶条，常驻块是 3px 左侧竖条——形态上一眼就能分出「一条回执」和
     * 「一个还在生效的模式」，不用读文字。
     */
    private static final int STICKY_BAR_WIDTH = 3;
    /** 呼吸周期：40 tick = 2 秒。 */
    private static final int STICKY_BREATH_TICKS = 40;
    private static final int STICKY_BREATH_MIN_ALPHA = 140;
    private static final int STICKY_BREATH_MAX_ALPHA = 255;

    private static Component text;
    private static NoticeSeverity severity = NoticeSeverity.INFO;
    private static int ticks;

    /**
     * 常驻提示（模式状态）：不淡出、不排队，只被 {@link #clearSticky()} 显式清掉。
     *
     * <p>和瞬态提示是两条独立通道：批量绑定这类「模式还在生效」的状态不能和「已绑定」
     * 这类三秒回执挤在同一条通道里——后者会把它顶掉，前者会让人以为模式三秒后自己结束。
     */
    private static Component stickyText;
    private static NoticeSeverity stickySeverity = NoticeSeverity.INFO;
    /** 只作呼吸相位；不倒计时，所以每 tick 调一次 {@link #showSticky} 不会让动画归零。 */
    private static int stickyTicks;

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
    public static void show(Component notice, NoticeSeverity level) {
        if (notice == null) {
            return;
        }
        text = notice;
        severity = level == null ? NoticeSeverity.INFO : level;
        ticks = DURATION_TICKS;
    }

    /**
     * 显示一条常驻提示：不淡出、不倒计时，直到 {@link #clearSticky()}。
     *
     * <p>每 tick 重复调用是安全的（内容直接替换，呼吸相位不重置）——调用方可以每帧
     * 把最新状态推过来，不用自己判断「内容变了没有」。文案里的 {@code \n} 是硬换行。
     */
    public static void showSticky(Component notice, NoticeSeverity level) {
        stickyText = notice;
        stickySeverity = level == null ? NoticeSeverity.INFO : level;
    }

    /** 清掉常驻提示（模式结束、换维度、提交成功）。 */
    public static void clearSticky() {
        stickyText = null;
    }

    /** 每客户端 tick 递减一次剩余时间。 */
    static void tick() {
        if (ticks > 0) {
            ticks--;
        }
        stickyTicks++;
    }

    /** HUD 层入口：只在没有界面时画，避免和界面层那份叠成重影。 */
    static void renderInHud(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        render(graphics, true);
    }

    /** 界面层入口：只在有界面时画，否则会被界面背景盖住。 */
    static void renderInScreen(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen == null) {
            return;
        }
        // 界面里不画常驻块：界面自己有标题与计数，再叠一条世界模式提示只会挡内容。
        render(graphics, false);
    }

    /**
     * 画两条通道。
     *
     * <p><b>常驻块贴底、位置固定</b>，瞬态块叠在它上方。反过来的话，每来一条回执常驻块就要
     * 上下跳一次，而它恰恰是玩家要一直盯着的那块。
     */
    private static void render(GuiGraphics graphics, boolean allowSticky) {
        var font = Minecraft.getInstance().font;
        int bottom = graphics.guiHeight() - BOTTOM_MARGIN;
        if (allowSticky && stickyText != null) {
            List<String> stickyLines = layout(stickyText, MAX_TEXT_WIDTH - PADDING_X * 2 - STICKY_BAR_WIDTH);
            if (!stickyLines.isEmpty()) {
                bottom -= drawSticky(graphics, font, stickyLines, bottom) + STICKY_GAP;
            }
        }
        if (ticks <= 0 || text == null) {
            return;
        }
        List<String> lines = layout(text, MAX_TEXT_WIDTH - PADDING_X * 2);
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
        int y = bottom - height;
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

    /** 常驻块：贴底、位置固定；只有左侧竖条呼吸，正文恒亮。返回块高度。 */
    private static int drawSticky(GuiGraphics graphics, Font font, List<String> lines, int bottom) {
        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, font.width(line));
        }
        int width = contentWidth + PADDING_X * 2 + STICKY_BAR_WIDTH;
        int height = lines.size() * font.lineHeight + PADDING_Y * 2;
        int x = (graphics.guiWidth() - width) / 2;
        int y = bottom - height;
        int background = switch (stickySeverity) {
            case WARN -> BACKGROUND_WARN;
            case ERROR -> BACKGROUND_ERROR;
            case INFO -> BACKGROUND_INFO;
        };
        int accent = switch (stickySeverity) {
            case WARN -> ACCENT_WARN;
            case ERROR -> ACCENT_ERROR;
            case INFO -> ACCENT_INFO;
        };
        graphics.fill(x, y, x + width, y + height, background);
        graphics.fill(x, y, x + STICKY_BAR_WIDTH, y + height, withAlpha(accent, breathingAlpha()));
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(font, lines.get(index), x + STICKY_BAR_WIDTH + PADDING_X,
                    y + PADDING_Y + index * font.lineHeight, TEXT, false);
        }
        return height;
    }

    /**
     * 呼吸用的透明度（140 ~ 255，余弦，2 秒一周期）。
     *
     * <p>只作用在左侧竖条上。<b>正文与底色恒亮</b>：整块一起呼吸时，亮度谷底的文字会掉进
     * 天空、雪地、白墙这类亮背景里，而可读性最差的那一刻恰恰是玩家最需要看清手势的时候。
     */
    private static int breathingAlpha() {
        double phase = (stickyTicks % STICKY_BREATH_TICKS) / (double) STICKY_BREATH_TICKS;
        double wave = 0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D);
        return (int) Math.round(STICKY_BREATH_MIN_ALPHA
                + (STICKY_BREATH_MAX_ALPHA - STICKY_BREATH_MIN_ALPHA) * wave);
    }

    /** 先按 {@code \n} 硬换行，再按宽度折行（{@link BoundUi#wrap} 不认识换行符）。 */
    private static List<String> layout(Component notice, int width) {
        List<String> lines = new ArrayList<>();
        for (String segment : notice.getString().split("\n", -1)) {
            lines.addAll(BoundUi.wrap(segment, width));
        }
        return lines;
    }

    /** 把颜色整体的透明度乘上 {@code alpha}（0-255）。 */
    private static int withAlpha(int color, int alpha) {
        int scaled = (color >>> 24) * alpha / 255;
        return scaled << 24 | (color & 0xFFFFFF);
    }
}
