package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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

    static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 把文本按宽度裁成多行（只用于提示段落，不做断词）。 */
    static List<String> wrap(String text, int width) {
        var font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (font.width(candidate) > width && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
