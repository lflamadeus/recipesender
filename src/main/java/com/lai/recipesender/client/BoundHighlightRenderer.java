package com.lai.recipesender.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 高亮的落笔处：把 {@link BoundHighlightState} 里的坐标画成红色描边，**只画棱、不画填充**。
 *
 * <p>画法照抄同作者的 FindMeExtended（{@code BlacklistHighlighter}，源头是 WorldEditCUI 的
 * {@code BufferBuilderRenderSink}）：**不走 {@code RenderType} / {@code MultiBufferSource}**，
 * 自己开 {@link Tesselator}、自己设 GL 状态。原因是 {@code RenderType} 会在 {@code endBatch}
 * 里把整套状态重新设一遍，从外面压不住深度测试——而「穿墙」恰恰只能靠直接改深度状态实现。
 *
 * <p>（tasks.md 的 T5.2 原本写的是 {@code RenderType.lines()}。实测这条路走不通：
 * {@code RenderStateShard.NO_DEPTH_TEST} 之类的常量在 1.20.1 里全是 {@code protected}，
 * 自建 RenderType 要自己拼 {@code LayeringStateShard}/{@code OutputStateShard} 的一堆 GL 开关，
 * 既脆又难验证。这里按 BlacklistHighlighter 的成熟写法来。）
 *
 * <p>具体对应关系：
 * <ul>
 *   <li>穿墙 = {@code RenderSystem.depthFunc(GL_ALWAYS)}，也就是 WorldEditCUI 的 {@code ANY(519)}。</li>
 *   <li>线宽 = {@code RenderSystem.lineWidth(2.5F)}：{@code rendertype_lines} 的顶点着色器把
 *       {@code LineWidth} uniform 当屏幕空间线宽用，默认 1.0 细得像发丝。</li>
 *   <li>顶点用「相对摄像机的世界坐标」，和原版方块选中框一致。摄像机的旋转由 model-view 提供，
 *       所以先把事件给的 {@code poseStack} 并进 model-view 再画。</li>
 *   <li>{@code FogRenderer.setupNoFog()}：{@code rendertype_lines} 的片元着色器会做线性雾，
 *       远处的框会被雾洗淡，而穿墙的意义正是找远处的容器。</li>
 *   <li>法线必须是每条棱的方向单位向量：{@code rendertype_lines} 的顶点着色器拿它把线段
 *       扩成屏幕空间的四边形。**不能**用 {@code LevelRenderer.renderLineBox}——它读
 *       {@code poseStack.last().normal()}，而 {@code AFTER_WEATHER} 拿到的 poseStack 只有
 *       视图旋转，没有模型变换，用它算出来的法线方向不对。</li>
 *   <li>所有改过的状态都在 {@code finally} 里还原。</li>
 * </ul>
 */
final class BoundHighlightRenderer {

    /** 相对方块表面的外扩量，防共面。 */
    private static final double EXPAND = 0.002D;

    /**
     * 描边宽度（像素）。默认是 1.0，细得像发丝，远一点的容器几乎看不见。
     * {@code rendertype_lines} 的顶点着色器会把 {@code LineWidth} 这个 uniform 当作屏幕空间
     * 的线宽来把线段扩成四边形，所以直接调它就行，不用改几何。
     */
    private static final float LINE_WIDTH = 2.5F;

    private static final float RED = 1.0F;
    private static final float GREEN = 0.16F;
    private static final float BLUE = 0.16F;
    private static final float ALPHA = 1.0F;

    /**
     * 12 条棱，每条两个端点，每个端点 3 个数：0 表示取包围盒的 min、1 表示取 max。
     * 按方向分组，方便核对「每个角恰好被 3 条棱共用」。
     */
    private static final int[][] EDGE_CORNERS = {
            {0, 0, 0, 1, 0, 0}, {0, 0, 1, 1, 0, 1}, {0, 1, 0, 1, 1, 0}, {0, 1, 1, 1, 1, 1},   // 沿 X
            {0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {0, 0, 1, 0, 1, 1}, {1, 0, 1, 1, 1, 1},   // 沿 Y
            {0, 0, 0, 0, 0, 1}, {1, 0, 0, 1, 0, 1}, {0, 1, 0, 0, 1, 1}, {1, 1, 0, 1, 1, 1}    // 沿 Z
    };

    private BoundHighlightRenderer() {
    }

    /**
     * 在关卡渲染结束时画出高亮框。
     *
     * @param poseStack 事件给的姿态（只有视图旋转 V，不含投影）；会被并进 model-view，
     *                  顶点随后用「相对摄像机的世界坐标」
     * @param camera    当前摄像机，用来取原点
     * @param positions 要画的方块坐标；空列表时一个顶点都不发
     */
    static void render(PoseStack poseStack, Camera camera, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Vec3 origin = camera.getPosition();

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        // 必须先把栈顶清成单位阵再并：AFTER_WEATHER 是在 LevelRenderer 自己
        // pushPose + mulPoseMatrix(V) 的那一段里派发的（bc 2761-2778），栈顶此刻已经是 V，
        // 直接 mulPoseMatrix(V) 会得到 V·V，框会被画到屏幕外。
        modelView.setIdentity();
        modelView.mulPoseMatrix(poseStack.last().pose());
        RenderSystem.applyModelViewMatrix();

        float fogStart = RenderSystem.getShaderFogStart();
        float previousLineWidth = RenderSystem.getShaderLineWidth();
        ShaderInstance previousShader = RenderSystem.getShader();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        // 不写深度：穿墙画的框不该在深度缓冲里留下痕迹，否则会挡住手部渲染。
        RenderSystem.depthMask(false);
        RenderSystem.lineWidth(LINE_WIDTH);
        FogRenderer.setupNoFog();
        try {
            drawOutline(minecraft, positions, origin);
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.lineWidth(previousLineWidth);
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawOutline(Minecraft minecraft, List<BlockPos> positions, Vec3 origin) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (BlockPos pos : positions) {
            // 区块没加载时不画：坐标还在，但方块不在视野里，画出来只是一团悬空的框。
            if (!minecraft.level.hasChunkAt(pos)) {
                continue;
            }
            drawBoxOutline(builder, pos, origin);
        }
        // 着色器颜色是全局状态，别处可能留了非白色，不重置会把红色乘歪。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        tesselator.end();
    }

    private static void drawBoxOutline(VertexConsumer builder, BlockPos pos, Vec3 origin) {
        double minX = pos.getX() - EXPAND - origin.x;
        double minY = pos.getY() - EXPAND - origin.y;
        double minZ = pos.getZ() - EXPAND - origin.z;
        double maxX = pos.getX() + 1.0D + EXPAND - origin.x;
        double maxY = pos.getY() + 1.0D + EXPAND - origin.y;
        double maxZ = pos.getZ() + 1.0D + EXPAND - origin.z;

        for (int[] edge : EDGE_CORNERS) {
            double ax = edge[0] == 0 ? minX : maxX;
            double ay = edge[1] == 0 ? minY : maxY;
            double az = edge[2] == 0 ? minZ : maxZ;
            double bx = edge[3] == 0 ? minX : maxX;
            double by = edge[4] == 0 ? minY : maxY;
            double bz = edge[5] == 0 ? minZ : maxZ;

            double nx = bx - ax;
            double ny = by - ay;
            double nz = bz - az;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1.0E-6D) {
                continue;
            }
            float ux = (float) (nx / length);
            float uy = (float) (ny / length);
            float uz = (float) (nz / length);

            builder.vertex(ax, ay, az).color(RED, GREEN, BLUE, ALPHA).normal(ux, uy, uz).endVertex();
            builder.vertex(bx, by, bz).color(RED, GREEN, BLUE, ALPHA).normal(ux, uy, uz).endVertex();
        }
    }
}
