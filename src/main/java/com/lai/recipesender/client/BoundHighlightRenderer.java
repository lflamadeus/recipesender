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

import java.util.ArrayList;
import java.util.List;

/**
 * 描边的落笔处：把一组包围盒画成彩色棱框，**只画棱、不画填充**。
 *
 * <p>画法照抄同作者的 FindMeExtended（{@code BlacklistHighlighter}，源头是 WorldEditCUI 的
 * {@code BufferBuilderRenderSink}）：**不走 {@code RenderType} / {@code MultiBufferSource}**，
 * 自己开 {@link Tesselator}、自己设 GL 状态。原因是 {@code RenderType} 会在 {@code endBatch}
 * 里把整套状态重新设一遍，从外面压不住深度测试——而「穿墙」恰恰只能靠直接改深度状态实现。
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
public final class BoundHighlightRenderer {

    /** 相对方块表面的外扩量，防共面。 */
    private static final double EXPAND = 0.002D;

    /**
     * 描边宽度（像素）。默认是 1.0，细得像发丝，远一点的容器几乎看不见。
     * {@code rendertype_lines} 的顶点着色器会把 {@code LineWidth} 这个 uniform 当作屏幕空间
     * 的线宽来把线段扩成四边形，所以直接调它就行，不用改几何。
     */
    private static final float LINE_WIDTH = 2.5F;

    /** ⑪ 世界内高亮用的红色。 */
    private static final int HIGHLIGHT_COLOR = 0xFFFF2929;

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

    /** 一条要画的棱框；颜色按 ARGB 给。 */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                      float red, float green, float blue, float alpha) {

        /** 一个方块格（四周外扩防共面）。 */
        public static Box block(BlockPos pos, int argb) {
            return of(pos.getX() - EXPAND, pos.getY() - EXPAND, pos.getZ() - EXPAND,
                    pos.getX() + 1.0D + EXPAND, pos.getY() + 1.0D + EXPAND, pos.getZ() + 1.0D + EXPAND,
                    argb);
        }

        /** 两个方块之间的整个 AABB（含两端）。 */
        public static Box between(BlockPos a, BlockPos b, int argb) {
            return of(Math.min(a.getX(), b.getX()) - EXPAND,
                    Math.min(a.getY(), b.getY()) - EXPAND,
                    Math.min(a.getZ(), b.getZ()) - EXPAND,
                    Math.max(a.getX(), b.getX()) + 1.0D + EXPAND,
                    Math.max(a.getY(), b.getY()) + 1.0D + EXPAND,
                    Math.max(a.getZ(), b.getZ()) + 1.0D + EXPAND,
                    argb);
        }

        /**
         * 垂直于某个轴的一层薄片（主向分片轮廓）。
         *
         * <p>厚度是一格：画出来是这一层的外框，而不是一条悬空的线——一层一层的框叠起来
         * 正好把「哪些方块算同一片」说清楚。{@code maxY}/{@code maxZ} 传的是<b>开区间</b>上界
         * （已经 +1 过），和 {@link #between} 的语义一致。
         */
        public static Box planeX(int x, int minY, int minZ, int maxY, int maxZ, int argb) {
            return of(x - EXPAND, minY - EXPAND, minZ - EXPAND,
                    x + 1.0D + EXPAND, maxY + EXPAND, maxZ + EXPAND, argb);
        }

        /** 垂直于 Y 的一层薄片。 */
        public static Box planeY(int y, int minX, int minZ, int maxX, int maxZ, int argb) {
            return of(minX - EXPAND, y - EXPAND, minZ - EXPAND,
                    maxX + EXPAND, y + 1.0D + EXPAND, maxZ + EXPAND, argb);
        }

        /** 垂直于 Z 的一层薄片。 */
        public static Box planeZ(int z, int minX, int minY, int maxX, int maxY, int argb) {
            return of(minX - EXPAND, minY - EXPAND, z - EXPAND,
                    maxX + EXPAND, maxY + EXPAND, z + 1.0D + EXPAND, argb);
        }

        private static Box of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                              int argb) {
            return new Box(minX, minY, minZ, maxX, maxY, maxZ,
                    ((argb >> 16) & 0xFF) / 255.0F,
                    ((argb >> 8) & 0xFF) / 255.0F,
                    (argb & 0xFF) / 255.0F,
                    ((argb >>> 24) & 0xFF) / 255.0F);
        }
    }

    /**
     * ⑪ 世界内高亮：把一组方块坐标画成红框。
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
        if (minecraft.level == null) {
            return;
        }
        List<Box> boxes = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            // 区块没加载时不画：坐标还在，但方块不在视野里，画出来只是一团悬空的框。
            if (minecraft.level.hasChunkAt(pos)) {
                boxes.add(Box.block(pos, HIGHLIGHT_COLOR));
            }
        }
        renderBoxes(poseStack, camera, boxes);
    }

    /**
     * 一条线段；批量绑定的配对连线用它。
     *
     * <p>不塞进 {@link Box} 是因为退化包围盒会把同一条线画四遍（另外三条棱长度为零被跳过），
     * 半透明时叠加起来会比别的线亮一截。
     */
    public record Segment(double x1, double y1, double z1, double x2, double y2, double z2,
                          float red, float green, float blue, float alpha) {

        /** 两个世界坐标之间的一条线。 */
        public static Segment of(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            return new Segment(x1, y1, z1, x2, y2, z2,
                    ((argb >> 16) & 0xFF) / 255.0F,
                    ((argb >> 8) & 0xFF) / 255.0F,
                    (argb & 0xFF) / 255.0F,
                    ((argb >>> 24) & 0xFF) / 255.0F);
        }
    }

    /**
     * 画任意一组彩色棱框（批量绑定的角色色、框线都走这里）。
     *
     * <p>不做区块加载判断：调用方给的框可能横跨多个区块，逐块判断没有意义。
     */
    public static void renderBoxes(PoseStack poseStack, Camera camera, List<Box> boxes) {
        renderAll(poseStack, camera, boxes, List.of());
    }

    /** 棱框 + 线段一起画：同一个批次，省一次 GL 状态切换。 */
    public static void renderAll(PoseStack poseStack, Camera camera, List<Box> boxes, List<Segment> segments) {
        boolean noBoxes = boxes == null || boxes.isEmpty();
        boolean noSegments = segments == null || segments.isEmpty();
        if (noBoxes && noSegments) {
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
        // pushPose + mulPoseMatrix(V) 的那一段里派发的，栈顶此刻已经是 V，
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
            drawOutlines(boxes == null ? List.of() : boxes, segments == null ? List.of() : segments, origin);
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

    private static void drawOutlines(List<Box> boxes, List<Segment> segments, Vec3 origin) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (Box box : boxes) {
            drawBoxOutline(builder, box, origin);
        }
        for (Segment segment : segments) {
            drawSegment(builder, segment, origin);
        }
        // 着色器颜色是全局状态，别处可能留了非白色，不重置会把颜色乘歪。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        tesselator.end();
    }

    /**
     * 半透明填充：给每个盒子染一层色，**只画朝向摄像机的那三个面**。
     *
     * <p>为什么只画三个面：一个方块六个面里，朝外那三个才是看得见的；全画会让相邻方块共用的那个面
     * 叠两次（同一个颜色混两遍，出现深浅不一的格子），也会把「贴着墙的那一面」透出来。
     *
     * <p>为什么填充**不穿墙**：线框穿墙是为了找远处的容器，而填充穿墙会变成隔着一面墙看到一片彩色，
     * 比线更糊。所以这里用默认的 {@code LEQUAL}，被挡住的方块自然不染色——这正是想要的效果
     * （玩家看到的是「这排机器被标了色」，而不是「屏幕上多了一层雾」）。
     *
     * <p>和描边一样不走 {@code RenderType}：{@code endBatch} 会重置深度状态，这里必须自己管。
     */
    public static void renderFills(PoseStack poseStack, Camera camera, List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Vec3 origin = camera.getPosition();

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPoseMatrix(poseStack.last().pose());
        RenderSystem.applyModelViewMatrix();

        float fogStart = RenderSystem.getShaderFogStart();
        ShaderInstance previousShader = RenderSystem.getShader();
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 只挑朝向摄像机的那三个面，绕序不用管，关掉背面剔除最省事。
        RenderSystem.disableCull();
        // 不写深度：填充只是染色，不该在深度缓冲里留下痕迹挡住别的东西。
        RenderSystem.depthMask(false);
        FogRenderer.setupNoFog();
        try {
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder builder = tesselator.getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (Box box : boxes) {
                addFilledCube(builder, box, origin);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            tesselator.end();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.depthMask(true);
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** 按摄像机位置挑三个朝外的面画；摄像机在盒子里时一个面都不画（方块尺寸下不会发生）。 */
    private static void addFilledCube(VertexConsumer builder, Box box, Vec3 origin) {
        double x0 = box.minX() - origin.x;
        double y0 = box.minY() - origin.y;
        double z0 = box.minZ() - origin.z;
        double x1 = box.maxX() - origin.x;
        double y1 = box.maxY() - origin.y;
        double z1 = box.maxZ() - origin.z;

        if (origin.x <= box.minX()) {
            quad(builder, box, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        } else if (origin.x >= box.maxX()) {
            quad(builder, box, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }
        if (origin.y <= box.minY()) {
            quad(builder, box, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        } else if (origin.y >= box.maxY()) {
            quad(builder, box, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        }
        if (origin.z <= box.minZ()) {
            quad(builder, box, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        } else if (origin.z >= box.maxZ()) {
            quad(builder, box, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        }
    }

    private static void quad(VertexConsumer builder, Box box,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        builder.vertex(ax, ay, az).color(box.red(), box.green(), box.blue(), box.alpha()).endVertex();
        builder.vertex(bx, by, bz).color(box.red(), box.green(), box.blue(), box.alpha()).endVertex();
        builder.vertex(cx, cy, cz).color(box.red(), box.green(), box.blue(), box.alpha()).endVertex();
        builder.vertex(dx, dy, dz).color(box.red(), box.green(), box.blue(), box.alpha()).endVertex();
    }

    private static void drawSegment(VertexConsumer builder, Segment segment, Vec3 origin) {
        double ax = segment.x1() - origin.x;
        double ay = segment.y1() - origin.y;
        double az = segment.z1() - origin.z;
        double bx = segment.x2() - origin.x;
        double by = segment.y2() - origin.y;
        double bz = segment.z2() - origin.z;

        double nx = bx - ax;
        double ny = by - ay;
        double nz = bz - az;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-6D) {
            return;
        }
        float ux = (float) (nx / length);
        float uy = (float) (ny / length);
        float uz = (float) (nz / length);

        builder.vertex(ax, ay, az).color(segment.red(), segment.green(), segment.blue(), segment.alpha())
                .normal(ux, uy, uz).endVertex();
        builder.vertex(bx, by, bz).color(segment.red(), segment.green(), segment.blue(), segment.alpha())
                .normal(ux, uy, uz).endVertex();
    }

    private static void drawBoxOutline(VertexConsumer builder, Box box, Vec3 origin) {
        double minX = box.minX() - origin.x;
        double minY = box.minY() - origin.y;
        double minZ = box.minZ() - origin.z;
        double maxX = box.maxX() - origin.x;
        double maxY = box.maxY() - origin.y;
        double maxZ = box.maxZ() - origin.z;

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

            builder.vertex(ax, ay, az).color(box.red(), box.green(), box.blue(), box.alpha())
                    .normal(ux, uy, uz).endVertex();
            builder.vertex(bx, by, bz).color(box.red(), box.green(), box.blue(), box.alpha())
                    .normal(ux, uy, uz).endVertex();
        }
    }
}
