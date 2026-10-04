package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定相关界面的公共绘制与配色。
 *
 * <p>配色沿用原版 GUI 观感：面板 {@code #c6c6c6}、上/左亮边、下/右暗边；
 * 角色标签沿用 {@code temp\bound-send-plan\prototype.html} 里定下的色值，
 * 这样原型与游戏内看起来是同一套东西。
 */
final class BoundUi {
    static final int PANEL = 0xFFC6C6C6;
    static final int BORDER_LIGHT = 0xFFFFFFFF;
    static final int BORDER_DARK = 0xFF555555;
    static final int ROW_HOVER = 0xFFB8B8B8;
    static final int ROW_SELECTED = 0xFFADADAD;
    static final int ROW_DISABLED = 0xFFB4B4B4;
    static final int TEXT = 0xFF2F2F2F;
    static final int TEXT_DIM = 0xFF5A5A5A;
    static final int TEXT_DISABLED = 0xFF8A8A8A;
    static final int TEXT_DANGER = 0xFFA02020;
    static final int TEXT_ON_TAG = 0xFFFFFFFF;
    static final int WARN_BACKGROUND = 0x40C04040;

    /** 主容器。 */
    static final int TAG_MASTER = 0xFF4F8A3F;
    /** 独立（没有并列成员的主容器）。 */
    static final int TAG_INDEPENDENT = 0xFF7D7D7D;
    /** 并列组。 */
    static final int TAG_PARALLEL = 0xFF2F6FA8;
    /** 从容器。 */
    static final int TAG_SLAVE = 0xFF6B6B6B;
    /** 上次用过的发送单元。 */
    static final int TAG_LAST = 0xFFB8860B;
    /** 已勾选的配方类别数量（S6 自动路由）。 */
    static final int TAG_ROUTE = 0xFF7A4FA8;
    /** 目标容器已经不在了。 */
    static final int TAG_DEAD = 0xFFA02020;
    /** 判不出来（区块未加载）。 */
    static final int TAG_UNKNOWN = 0xFF8A8A8A;
    /** 绑定记在别的维度。 */
    static final int TAG_OTHER_DIMENSION = 0xFF6B6B6B;

    private BoundUi() {
    }

    /** 画一块带原版 3D 边框的面板。 */
    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width - 1, y + 1, BORDER_LIGHT);
        graphics.fill(x, y, x + 1, y + height - 1, BORDER_LIGHT);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, BORDER_DARK);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, BORDER_DARK);
    }

    /**
     * 居中画一行文字，**不带投影**。
     *
     * <p>{@code GuiGraphics#drawCenteredString} 的 4 参重载默认开投影（原版用它给标题加立体感），
     * 但本模组的面板是浅灰底、正文也是深灰，投影会被看成「重影」。所以本模组所有界面统一
     * 走这个无投影版本——行内文字、标题、页脚一视同仁。
     */
    static void centered(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** 画一个小标签，返回它占用的宽度。 */
    static int tag(GuiGraphics graphics, int x, int y, Component text, int background) {
        var font = Minecraft.getInstance().font;
        int width = font.width(text) + 5;
        graphics.fill(x, y, x + width, y + 11, background);
        graphics.drawString(font, text, x + 2, y + 2, TEXT_ON_TAG, false);
        return width;
    }

    /**
     * 按原型 {@code tags()} 的顺序依次画角色标签，返回总宽度。
     *
     * <p>顺序与含义：主容器 → 独立（主容器且没有并列成员）→ 并列组 → 从 ×N → 上次。
     * 并列组标签**不带数字**（组的规模已经体现在名字的 {@code ×N} 后缀里）。
     */
    static int tags(GuiGraphics graphics, int x, int y, BoundContainer binding, int parallelCount,
                    int slaveCount, boolean last) {
        int cursor = x;
        if (binding.isMaster()) {
            cursor += tag(graphics, cursor, y, Component.translatable("text.recipe_sender.tag_master"),
                    TAG_MASTER) + 2;
            if (parallelCount <= 1) {
                cursor += tag(graphics, cursor, y,
                        Component.translatable("text.recipe_sender.tag_independent"), TAG_INDEPENDENT) + 2;
            }
        }
        if (parallelCount > 1) {
            cursor += tag(graphics, cursor, y, Component.translatable("text.recipe_sender.tag_parallel"),
                    TAG_PARALLEL) + 2;
        }
        if (slaveCount > 0) {
            cursor += tag(graphics, cursor, y,
                    Component.translatable("text.recipe_sender.tag_slave", slaveCount), TAG_SLAVE) + 2;
        }
        if (last) {
            cursor += tag(graphics, cursor, y, Component.translatable("text.recipe_sender.tag_last"),
                    TAG_LAST) + 2;
        }
        return cursor - x;
    }

    /**
     * 与 {@link #tags} 同样条件下的标签总宽度，只测量不绘制。
     *
     * <p>给「名字先裁到不压住标签」用：先知道右边要占多少，才知道名字能画多宽。</p>
     */
    static int tagsWidth(BoundContainer binding, int parallelCount, int slaveCount, boolean last) {
        var font = Minecraft.getInstance().font;
        int width = 0;
        if (binding.isMaster()) {
            width += font.width(Component.translatable("text.recipe_sender.tag_master")) + 5 + 2;
            if (parallelCount <= 1) {
                width += font.width(Component.translatable("text.recipe_sender.tag_independent")) + 5 + 2;
            }
        }
        if (parallelCount > 1) {
            width += font.width(Component.translatable("text.recipe_sender.tag_parallel")) + 5 + 2;
        }
        if (slaveCount > 0) {
            width += font.width(Component.translatable("text.recipe_sender.tag_slave", slaveCount)) + 5 + 2;
        }
        if (last) {
            width += font.width(Component.translatable("text.recipe_sender.tag_last")) + 5 + 2;
        }
        return width;
    }

    /**
     * 存活性短标签的文案；正常（或本地判不出来）时返回 {@code null}，表示什么都不画。
     *
     * <p>短标签只说「哪里不对」，完整原因（以及后果）放在悬停提示里，见 {@link #aliveTip}。
     * 行内空间有限，一行上还有名字、角色标签和按钮。
     */
    static Component aliveLabel(BoundStatus status) {
        return switch (status) {
            case MISSING -> Component.translatable("text.recipe_sender.bound_liveness.missing");
            case CHUNK_UNLOADED -> Component.translatable("text.recipe_sender.bound_liveness.chunk_unloaded");
            case DIMENSION_MISMATCH ->
                    Component.translatable("text.recipe_sender.bound_liveness.dimension_mismatch");
            default -> null;
        };
    }

    private static int aliveTagColor(BoundStatus status) {
        return switch (status) {
            case MISSING -> TAG_DEAD;
            case DIMENSION_MISMATCH -> TAG_OTHER_DIMENSION;
            default -> TAG_UNKNOWN;
        };
    }

    /** 悬停提示：完整原因。发送时用的是同一批 {@code bound_status.*} 文案，界面与回执说法一致。 */
    static Component aliveTip(BoundStatus status) {
        return switch (status) {
            case MISSING -> Component.translatable("text.recipe_sender.bound_liveness.missing_tip");
            case CHUNK_UNLOADED -> Component.translatable("text.recipe_sender.bound_status.chunk_unloaded");
            case DIMENSION_MISMATCH -> Component.translatable("text.recipe_sender.bound_status.dimension_mismatch");
            default -> null;
        };
    }

    /** 画存活性短标签，返回它占用的宽度（含与下一个标签的间距）；不画时返回 0。 */
    static int aliveTag(GuiGraphics graphics, int x, int y, BoundStatus status) {
        Component label = aliveLabel(status);
        return label == null ? 0 : tag(graphics, x, y, label, aliveTagColor(status)) + 2;
    }

    /** 与 {@link #aliveTag} 对应的宽度，只测量不绘制。 */
    static int aliveTagWidth(BoundStatus status) {
        Component label = aliveLabel(status);
        return label == null ? 0 : Minecraft.getInstance().font.width(label) + 5 + 2;
    }

    /**
     * 把存活性文案当普通文字画（不铺底色），返回实际画出来的宽度；正常时返回 0。
     *
     * <p>给副标题用：行内标签那一块有可能因为一行太窄而整块不画（名字太长），
     * 副标题这一行没有按钮抢位置，一定画得出来。</p>
     */
    static int aliveText(GuiGraphics graphics, int x, int y, int maxWidth, BoundStatus status) {
        Component label = aliveLabel(status);
        return label == null ? 0 : clipText(graphics, label, x, y, maxWidth, aliveTagColor(status));
    }

    /** 坐标的显示形式：{@code (x, y, z)}。 */
    static String posText(BoundContainer binding) {
        var pos = binding.pos();
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 方块名 · 坐标，例如 {@code 箱子 · (12, 64, -30)}。 */
    static String subText(BoundContainer binding) {
        return binding.iconItem().getHoverName().getString() + " · " + posText(binding);
    }

    /** 方块名，没有图标时退化成「未知方块」。 */
    static String blockText(BoundContainer binding) {
        if (binding.iconItem().isEmpty()) {
            return Component.translatable("text.recipe_sender.unknown_block").getString();
        }
        return binding.iconItem().getHoverName().getString();
    }

    /**
     * 点到当前焦点输入栏以外，就让它失焦（{@link BoundEditBox#onBlur} 接手「失焦即确认」）。
     *
     * <p>界面里自绘的点击目标（行、自绘按钮）不走控件的焦点逻辑，光靠控件自己收不到失焦通知，
     * 所以每次点击先过一下这里。</p>
     */
    static void blurFocusedIfOutside(Screen screen, double mouseX, double mouseY) {
        if (screen.getFocused() instanceof BoundEditBox box && !box.isMouseOver(mouseX, mouseY)) {
            screen.setFocused(null);
        }
    }

    /**
     * 把焦点交给输入栏。**已经聚焦时不能调 {@code setFocused(box)}**。
     *
     * <p>原版的 {@code Screen.setFocused} 是「先给旧焦点 {@code setFocused(false)}，再给新焦点
     * {@code setFocused(true)}」——新旧是同一个输入栏时，中间那一下 false 会被 {@link BoundEditBox}
     * 当成真失焦、当场跑失焦回调（改名框会提交并把自己置空），调用方紧接着用这个字段就 NPE。
     * 1.0.26 实测点第二下改名框就崩在这里。</p>
     */
    static void focus(Screen screen, BoundEditBox box) {
        if (screen.getFocused() != box) {
            screen.setFocused(box);
        }
    }

    static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * 是不是「打字键」：字母、数字、空格。
     *
     * <p>给「打开界面直接打字就聚焦搜索框」用。不能反过来用 {@code setInitialFocus} 代替：
     * 搜索框一开场就聚焦，玩家想按 E / B 关界面时这些键会被输入框吃掉，界面反而关不掉。</p>
     */
    static boolean isTypingKey(int keyCode) {
        return (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z)
                || (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9)
                || (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9)
                || keyCode == GLFW.GLFW_KEY_SPACE;
    }

    /** 没有输入框聚焦时，按下的键是否应该把焦点交给搜索框。 */
    static boolean shouldTypeToSearch(Screen screen, int keyCode) {
        return !(screen.getFocused() instanceof BoundEditBox) && isTypingKey(keyCode);
    }

    // ------------------------------------------------------------------ 通用控件

    /**
     * 自绘按钮的统一样式：浅灰底 + 深色描边，悬停变亮，危险动作红字。
     *
     * <p>1.0.27 之前四个界面各画各的（高度 16/18 混用、悬停色三套），同一个「删除」按钮在管理界面
     * 和类别选择器里长得不一样。这里收成一份，顺便给所有调用方留出统一加 tooltip 的位置。</p>
     *
     * @return 这个按钮占用的宽度
     */
    static int button(GuiGraphics graphics, int x, int y, Component label, boolean hovered, boolean enabled,
                      boolean danger) {
        var font = Minecraft.getInstance().font;
        int width = buttonWidth(label);
        int background = !enabled ? ROW_DISABLED
                : hovered ? (danger ? 0xFFD0D0D0 : BORDER_LIGHT) : PANEL;
        graphics.fill(x, y, x + width, y + BUTTON_HEIGHT, background);
        graphics.renderOutline(x, y, width, BUTTON_HEIGHT, BORDER_DARK);
        int color = !enabled ? TEXT_DISABLED : danger ? TEXT_DANGER : TEXT;
        graphics.drawString(font, label, x + (width - font.width(label)) / 2,
                y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1, color, false);
        return width;
    }

    /** 自绘按钮的标准高度。四个界面统一用这个值。 */
    static final int BUTTON_HEIGHT = 16;

    /** 自绘按钮的宽度：文字宽 + 左右各 5px 内边距。 */
    static int buttonWidth(Component label) {
        return Minecraft.getInstance().font.width(label) + 10;
    }

    /** 自绘勾选框（11×11，和原版观感一致）。 */
    static void checkbox(GuiGraphics graphics, int x, int y, boolean checked, boolean enabled) {
        graphics.fill(x, y, x + 11, y + 11, BORDER_DARK);
        graphics.fill(x + 1, y + 1, x + 10, y + 10, enabled ? PANEL : ROW_DISABLED);
        if (checked) {
            graphics.fill(x + 3, y + 3, x + 8, y + 8, enabled ? TAG_MASTER : TEXT_DISABLED);
        }
    }

    /**
     * 列表滚动条（宽 3px，贴面板右内侧）。
     *
     * <p>原来只有类别选择器有，管理界面和材料弹窗都没有——长列表里玩家看不出自己在哪一段。</p>
     */
    static void scrollbar(GuiGraphics graphics, int trackX, int listTop, int listHeight, int contentHeight,
                          int scrollOffset, int maxScroll) {
        if (maxScroll <= 0 || listHeight <= 0) {
            return;
        }
        graphics.fill(trackX, listTop, trackX + 3, listTop + listHeight, ROW_DISABLED);
        int thumbHeight = Math.max(12, listHeight * listHeight / Math.max(1, contentHeight));
        int travel = listHeight - thumbHeight;
        int thumbY = listTop + travel * scrollOffset / maxScroll;
        graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, BORDER_DARK);
    }

    /**
     * 把一行文字裁到 {@code maxWidth} 以内，超出部分用 {@code …} 收尾。
     *
     * <p>行内的名字、标签、按钮原先互不避让：容器名一长就压到右侧标签和按钮上（1.0.26 的
     * 改名框就是因为这个才和图标打架）。凡是「内容长度由玩家决定、右边还有别的东西」的地方
     * 都要先过这里。</p>
     *
     * @return 实际画出来的宽度
     */
    static int clipText(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        var font = Minecraft.getInstance().font;
        String value = text.getString();
        if (font.width(value) <= maxWidth) {
            graphics.drawString(font, value, x, y, color, false);
            return font.width(value);
        }
        String ellipsis = "…";
        String clipped = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ellipsis)));
        String shown = clipped + ellipsis;
        graphics.drawString(font, shown, x, y, color, false);
        return font.width(shown);
    }

    /** 在鼠标位置画原版 tooltip。自绘控件没有原版 {@code Button} 的提示，靠这个补上。 */
    static void tooltip(GuiGraphics graphics, int mouseX, int mouseY, Component text) {
        graphics.renderTooltip(Minecraft.getInstance().font, text, mouseX, mouseY);
    }

    /**
     * 把文本按像素宽度裁成多行。
     *
     * <p>原来的版本只按空格断词，对中文完全无效（中文没有空格，一整段会原样返回）。
     * 现在按「先找得到空格就断在空格，否则逐字符断」处理，中英混排都能用。</p>
     */
    static List<String> wrap(String text, int width) {
        var font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || width <= 0) {
            return lines;
        }
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int lastSpace = -1;
            while (end < text.length() && font.width(text.substring(start, end + 1)) <= width) {
                if (text.charAt(end) == ' ') {
                    lastSpace = end;
                }
                end++;
            }
            if (end >= text.length()) {
                lines.add(text.substring(start));
                break;
            }
            if (end == start) {
                // 单个字符都放不下（宽度给得太小）：至少吃掉一个字符，避免死循环。
                end = start + 1;
            } else if (lastSpace > start) {
                end = lastSpace;
            }
            lines.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
        }
        return lines;
    }
}
