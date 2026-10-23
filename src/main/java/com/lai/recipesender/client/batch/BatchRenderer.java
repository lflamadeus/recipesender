package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundHighlightRenderer;
import com.lai.recipesender.model.BoundContainer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量绑定的世界里那层：候选染色 + 配对连线 + 主向分片 + 准星预览 + 框选范围。
 *
 * <p><b>颜色表达「谁和谁一组」，不再表达角色。</b>每组一个色相，主容器最亮、并列成员稍暗、
 * 从容器最浅，连线用同色——这样「这台机器和那个箱子是一伙的」是扫一眼得到的，
 * 不需要读任何编号或坐标。组数超过八个时色相从头循环，靠连线仍然分得清。
 *
 * <p><b>候选只用填充，不描边。</b>以前每个方块画 12 条棱、还穿墙，一排机器就是几百条边叠在一起，
 * 什么都看不清。现在填充走正常遮挡，被墙挡住的方块不染色。
 *
 * <p>仍然用描边的只剩三类，都是「要精确指认某一格」的场合：框选范围、准星预览、
 * 拒绝/剔除的方块（它们不填充，否则灰雾、红雾会盖住机器贴图）。
 *
 * <p>**这里不画 HUD**。模式的常驻提示走 {@code NoticeOverlay} 的常驻通道，位置在屏幕底部：
 * 原来画在顶部（{@code y = 8}）会盖住 Willa 这类机器信息条。
 */
public final class BatchRenderer {

    /**
     * 组色相表。第一组金、第二组天蓝……超过八个循环。
     *
     * <p>挑的都是能在格雷机器那种灰暗贴图上分得开的色相，且避开纯红（那是「拒绝」的语义）。
     */
    private static final int[] GROUP_RGB = {
            0xFFC040, 0x40C0FF, 0x60E060, 0xFF70C0, 0xC080FF, 0x40E0C0, 0xFFE040, 0x90A0FF
    };

    /** 角色的填充浓淡：主容器最亮，并列成员稍暗，从容器最浅。 */
    private static final int MASTER_ALPHA = 0x40;
    private static final int PARALLEL_ALPHA = 0x28;
    private static final int SLAVE_ALPHA = 0x20;

    /** 配不起来的整片：琥珀，和「红=拒绝」「灰=剔除」都区分得开。 */
    private static final int UNPAIRED_FILL = 0x34FFB030;
    private static final int UNPAIRED_OUTLINE = 0x90FFB030;

    private static final int REJECT_COLOR = 0xC0FF4040;
    private static final int EXCLUDED_COLOR = 0x80808080;
    private static final int BOX_COLOR = 0xFF808080;
    private static final int FIRST_COLOR = 0xFFFFFFFF;

    /** 准星那一片的平面：只画一个，够回答「眼前这个和谁一片」了。 */
    private static final int SLICE_COLOR = 0x40FFFFFF;

    /** 准星预览：能加 / 已在池里 / 已剔除 / 加不了。 */
    private static final int HOVER_ADD_COLOR = 0xFF60E060;
    private static final int HOVER_MEMBER_COLOR = 0xFFFFFFFF;
    private static final int HOVER_EXCLUDED_COLOR = 0xFF808080;
    private static final int HOVER_REJECT_COLOR = 0xFFFF4040;

    private BatchRenderer() {
    }

    /** 世界里画：填充 + 框线 + 连线。填充先画，描边才压得住它。 */
    public static void renderWorld(PoseStack poseStack, Camera camera) {
        if (!BatchSelection.active()) {
            return;
        }
        List<BoundHighlightRenderer.Box> fills = new ArrayList<>();
        List<BoundHighlightRenderer.Box> outlines = new ArrayList<>();
        List<BoundHighlightRenderer.Segment> segments = new ArrayList<>();

        BlockPos first = BatchSelection.first();
        BlockPos second = BatchSelection.second();
        if (first != null) {
            outlines.add(BoundHighlightRenderer.Box.block(first, FIRST_COLOR));
        }
        if (second != null) {
            outlines.add(BoundHighlightRenderer.Box.between(first, second, BOX_COLOR));
            addSliceOutlines(outlines, first, second);
        }
        for (BatchCandidate candidate : BatchSelection.candidates()) {
            addCandidate(fills, outlines, candidate);
        }
        addLinks(segments);
        addHoverPreview(outlines);

        BoundHighlightRenderer.renderFills(poseStack, camera, fills);
        BoundHighlightRenderer.renderAll(poseStack, camera, outlines, segments);
    }

    /**
     * 一个候选：能用的染色，不能用的描边。
     *
     * <p>剔除、已绑定、中枢这几类<b>不填充</b>——灰雾、红雾糊在机器上比线条脏得多，
     * 而且它们的数量通常很少，一两条细边就够指认了。
     */
    private static void addCandidate(List<BoundHighlightRenderer.Box> fills,
                                     List<BoundHighlightRenderer.Box> outlines,
                                     BatchCandidate candidate) {
        BatchCandidate.Reject reject = candidate.reject();
        if (reject == BatchCandidate.Reject.UNPAIRED) {
            // 未配对是「这一片说不清」，整片染琥珀；先判它，免得被当成普通的拒绝项画成红边。
            fills.add(BoundHighlightRenderer.Box.block(candidate.pos(), UNPAIRED_FILL));
            return;
        }
        if (reject != BatchCandidate.Reject.NONE) {
            outlines.add(BoundHighlightRenderer.Box.block(candidate.pos(),
                    reject == BatchCandidate.Reject.EXCLUDED ? EXCLUDED_COLOR : REJECT_COLOR));
            return;
        }
        if (BatchSelection.ambiguousAt(candidate.pos())) {
            // 机器本身是好的，但它所在的那一片配不起来：整片一起标出来。
            fills.add(BoundHighlightRenderer.Box.block(candidate.pos(), UNPAIRED_FILL));
            return;
        }
        BoundContainer.Role role = candidate.role() == null ? BoundContainer.Role.PARALLEL : candidate.role();
        int alpha = switch (role) {
            case MASTER -> MASTER_ALPHA;
            case PARALLEL -> PARALLEL_ALPHA;
            case SLAVE -> SLAVE_ALPHA;
        };
        fills.add(BoundHighlightRenderer.Box.block(candidate.pos(),
                (alpha << 24) | groupRgb(candidate)));
    }

    /** 这一格属于第几组就用第几组的色相；纯容器池（没有机器）全部落在第 0 个色相。 */
    private static int groupRgb(BatchCandidate candidate) {
        int group = candidate.group();
        int index = group <= 0 ? 0 : (group - 1) % GROUP_RGB.length;
        return GROUP_RGB[index];
    }

    /**
     * 主向分片：只画**准星所在的那一片**和**未配对的那几片**。
     *
     * <p>以前每个有候选的坐标都画一个横跨整框的矩形，一排 30 台就是 30 个大框叠着，是杂乱的
     * 最大来源。现在只回答两个具体问题：我眼前这个和谁一片（准星那片）、哪里说不清（琥珀片）。
     */
    private static void addSliceOutlines(List<BoundHighlightRenderer.Box> boxes, BlockPos first, BlockPos second) {
        if (BatchSelection.stage() != BatchSelection.Stage.BOX) {
            return;
        }
        Direction.Axis axis = BatchSelection.primary();
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX()) + 1;
        int maxY = Math.max(first.getY(), second.getY()) + 1;
        int maxZ = Math.max(first.getZ(), second.getZ()) + 1;

        Set<Integer> drawn = new LinkedHashSet<>();
        BlockPos hovered = lookedAtBlock(Minecraft.getInstance());
        if (hovered != null) {
            drawn.add(BatchPlanner.along(hovered, axis));
        }
        drawn.addAll(BatchSelection.unpairedSlices());
        for (int slice : drawn) {
            boolean unpaired = BatchSelection.ambiguous(slice);
            int color = unpaired ? UNPAIRED_OUTLINE : SLICE_COLOR;
            boxes.add(switch (axis) {
                case X -> BoundHighlightRenderer.Box.planeX(slice, minY, minZ, maxY, maxZ, color);
                case Y -> BoundHighlightRenderer.Box.planeY(slice, minX, minZ, maxX, maxZ, color);
                case Z -> BoundHighlightRenderer.Box.planeZ(slice, minX, minY, maxX, maxY, color);
            });
        }
    }

    /**
     * 配对连线：从容器中心 → 它那台机器中心，颜色跟着组走。
     *
     * <p>这是「谁配给谁」最直接的表达——比任何数字都直观，而且玩家框选时就站在那里，
     * 一眼就能看出配错了没有。
     */
    private static void addLinks(List<BoundHighlightRenderer.Segment> segments) {
        for (BatchCandidate candidate : BatchSelection.candidates()) {
            if (!candidate.accepted() || candidate.role() != BoundContainer.Role.SLAVE
                    || candidate.parent() == null) {
                continue;
            }
            BlockPos from = candidate.pos();
            BlockPos to = candidate.parent();
            segments.add(BoundHighlightRenderer.Segment.of(
                    from.getX() + 0.5D, from.getY() + 0.5D, from.getZ() + 0.5D,
                    to.getX() + 0.5D, to.getY() + 0.5D, to.getZ() + 0.5D,
                    (0xC0 << 24) | groupRgb(candidate)));
        }
    }

    /**
     * 准星下的方块描一个「按下去会发生什么」的框。
     *
     * <p>没有它，「Shift+右键 补一台」只能靠试：玩家不知道这一下会不会被拒绝、也不知道
     * 眼前这台是不是已经在池里（在池里就该用 Shift+左键）。只在 BOX 阶段画——FIRST 阶段
     * 右键的含义是「定角点」，给个绿框反而会让人以为是在加成员。
     */
    private static void addHoverPreview(List<BoundHighlightRenderer.Box> boxes) {
        if (BatchSelection.stage() != BatchSelection.Stage.BOX) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos pos = lookedAtBlock(minecraft);
        if (pos == null) {
            return;
        }
        BatchCandidate candidate = BatchSelection.candidateAt(pos);
        if (candidate != null) {
            boxes.add(BoundHighlightRenderer.Box.block(pos,
                    candidate.accepted() ? HOVER_MEMBER_COLOR : HOVER_EXCLUDED_COLOR));
            return;
        }
        boxes.add(BoundHighlightRenderer.Box.block(pos, hoverAddableColor(minecraft, pos)));
    }

    /** 这一格「补一台」能不能成：判定与真正按下时完全同一条通路，不会出现绿框点下去被拒。 */
    private static int hoverAddableColor(Minecraft minecraft, BlockPos pos) {
        if (minecraft.level == null) {
            return HOVER_REJECT_COLOR;
        }
        ResourceLocation dimension = minecraft.level.dimension().location();
        BatchCandidate scanned = BatchScanner.classify(minecraft.level, dimension, pos);
        return scanned != null && scanned.accepted() ? HOVER_ADD_COLOR : HOVER_REJECT_COLOR;
    }

    private static BlockPos lookedAtBlock(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.screen != null) {
            return null;
        }
        HitResult picked = minecraft.hitResult;
        return picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : null;
    }
}
