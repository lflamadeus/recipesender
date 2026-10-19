package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundHighlightRenderer;
import com.lai.recipesender.model.BoundContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量绑定的落笔处：世界里的描边（{@link #renderWorld}）与 HUD 计数条（{@link #renderHud}）。
 *
 * <p>颜色只表**角色**：主容器金、并列成员青、从容器绿、拒绝项红、框线灰。配对**不用颜色**
 * 区分——成员一多就需要 N 种颜色，分不清也色盲不友好；配对靠确认页的编号与列表。
 *
 * <p>描边走 {@link BoundHighlightRenderer}：它已经处理好了穿墙（关深度测试）、线宽、雾与
 * 状态还原，这里只负责「给哪些盒子、什么颜色」。
 */
public final class BatchRenderer {

    private static final int MASTER_COLOR = 0xFFFFC040;
    private static final int PARALLEL_COLOR = 0xFF40C0FF;
    private static final int SLAVE_COLOR = 0xFF60E060;
    private static final int REJECT_COLOR = 0xFFFF4040;
    private static final int BOX_COLOR = 0xFF808080;
    private static final int FIRST_COLOR = 0xFFFFFFFF;

    private BatchRenderer() {
    }

    /** 世界里画：第一个角点 + 框线 + 每个候选按角色描边。 */
    public static void renderWorld(PoseStack poseStack, Camera camera) {
        if (!BatchSelection.active()) {
            return;
        }
        List<BoundHighlightRenderer.Box> boxes = new ArrayList<>();
        BlockPos first = BatchSelection.first();
        BlockPos second = BatchSelection.second();
        if (first != null) {
            boxes.add(BoundHighlightRenderer.Box.block(first, FIRST_COLOR));
        }
        if (second != null) {
            boxes.add(BoundHighlightRenderer.Box.between(first, second, BOX_COLOR));
        }
        for (BatchCandidate candidate : BatchSelection.candidates()) {
            boxes.add(BoundHighlightRenderer.Box.block(candidate.pos(), colorOf(candidate)));
        }
        BoundHighlightRenderer.renderBoxes(poseStack, camera, boxes);
    }

    /** HUD：阶段提示 + 计数条（3D 画面给不出总数，必须另有一行）。 */
    public static void renderHud(GuiGraphics graphics) {
        if (!BatchSelection.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.font == null) {
            return;
        }
        List<Component> lines = new ArrayList<>(3);
        if (BatchSelection.stage() == BatchSelection.Stage.FIRST) {
            lines.add(Component.translatableWithFallback("text.recipe_sender.batch_pick_second",
                    "右键点第二个方块（每个方向最多 32 格）"));
        } else {
            int master = 0;
            int parallel = 0;
            int slave = 0;
            int rejected = 0;
            for (BatchCandidate candidate : BatchSelection.candidates()) {
                if (!candidate.accepted()) {
                    rejected++;
                    continue;
                }
                switch (candidate.role()) {
                    case MASTER -> master++;
                    case PARALLEL -> parallel++;
                    case SLAVE -> slave++;
                }
            }
            lines.add(Component.translatableWithFallback("text.recipe_sender.batch_counts",
                    "已选 {0}：主 {1} / 并列 {2} / 从 {3}，拒绝 {4}",
                    master + parallel + slave, master, parallel, slave, rejected)
                    .withStyle(ChatFormatting.WHITE));
            lines.add(Component.translatableWithFallback("text.recipe_sender.batch_hint",
                    "回车确认 · 左键剔除/恢复 · Shift+绑定键 重选")
                    .withStyle(ChatFormatting.GRAY));
        }

        int y = 8;
        for (Component line : lines) {
            int width = minecraft.font.width(line);
            int x = (graphics.guiWidth() - width) / 2;
            graphics.fill(x - 3, y - 2, x + width + 3, y + 10, 0x90000000);
            graphics.drawString(minecraft.font, line, x, y, 0xFFFFFF, false);
            y += 13;
        }
    }

    private static int colorOf(BatchCandidate candidate) {
        if (!candidate.accepted()) {
            return REJECT_COLOR;
        }
        return switch (candidate.role() == null ? BoundContainer.Role.PARALLEL : candidate.role()) {
            case MASTER -> MASTER_COLOR;
            case PARALLEL -> PARALLEL_COLOR;
            case SLAVE -> SLAVE_COLOR;
        };
    }

    /** 摄像机原点在世界里的位置；渲染器内部用它把世界坐标换成相对坐标。 */
    static Vec3 originOf(Camera camera) {
        return camera.getPosition();
    }
}
