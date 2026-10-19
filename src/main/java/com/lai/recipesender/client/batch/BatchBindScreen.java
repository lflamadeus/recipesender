package com.lai.recipesender.client.batch;

import com.lai.recipesender.client.BoundContainerClient;
import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.client.NoticeOverlay;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.BatchBindPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 批量绑定的确认页：看清单、切角色与建组模式、选目标组、提交。
 *
 * <p>世界里能做的（框选、剔除）这里也能做——每行点一下就是剔除/恢复，省得关掉界面回去点。
 * 提交只发一个 {@link BatchBindPacket}，成败由服务端一条汇总回执说明。
 */
public final class BatchBindScreen extends Screen {

    private static final int ROW_HEIGHT = 12;
    private static final int LIST_TOP = 44;
    private static final int LIST_MARGIN = 24;

    private int scroll;

    public BatchBindScreen() {
        super(Component.translatableWithFallback("text.recipe_sender.batch_title", "批量绑定"));
    }

    @Override
    protected void init() {
        int y = 16;
        addRenderableWidget(Button.builder(roleLabel(), button -> {
            BatchSelection.setTargetRole(BatchSelection.targetRole() == BoundContainer.Role.SLAVE
                    ? BoundContainer.Role.PARALLEL : BoundContainer.Role.SLAVE);
            rebuildWidgets();
        }).bounds(LIST_MARGIN, y, 130, 20).build());

        if (BatchSelection.targetRole() == BoundContainer.Role.PARALLEL) {
            addRenderableWidget(Button.builder(modeLabel(), button -> {
                BatchSelection.setGroupMode(BatchSelection.groupMode() == BoundContainer.GroupMode.UNIFIED
                        ? BoundContainer.GroupMode.INDEPENDENT : BoundContainer.GroupMode.UNIFIED);
                rebuildWidgets();
            }).bounds(LIST_MARGIN + 136, y, 110, 20).build());
        } else {
            addRenderableWidget(Button.builder(groupLabel(), button -> {
                cycleGroup();
                rebuildWidgets();
            }).bounds(LIST_MARGIN + 136, y, 160, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.done", "完成"), button -> submit())
                .bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.cancel", "取消"), button -> onClose())
                .bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    private Component roleLabel() {
        return Component.translatableWithFallback("text.recipe_sender.batch_role", "角色：%s",
                BatchSelection.targetRole() == BoundContainer.Role.SLAVE
                        ? Component.translatable("text.recipe_sender.tag_slave_role", "从容器")
                        : Component.translatable("text.recipe_sender.tag_parallel_member", "并列成员"));
    }

    private Component modeLabel() {
        return Component.translatableWithFallback("text.recipe_sender.batch_mode", "建组模式：%s",
                BatchSelection.groupMode() == BoundContainer.GroupMode.UNIFIED
                        ? Component.translatable("text.recipe_sender.tag_unified", "整体")
                        : Component.translatable("text.recipe_sender.tag_independent", "独立"));
    }

    private Component groupLabel() {
        List<BoundContainer> groups = BatchSelection.eligibleGroups();
        if (groups.isEmpty()) {
            return Component.translatableWithFallback("text.recipe_sender.batch_group_none", "没有数量匹配的组");
        }
        BoundContainer current = currentGroup(groups);
        return Component.translatableWithFallback("text.recipe_sender.batch_group",
                "目标组：%s（共 %s 个匹配）",
                BoundContainerClient.displayName(current), groups.size());
    }

    private BoundContainer currentGroup(List<BoundContainer> groups) {
        UUID selected = BatchSelection.targetGroupId();
        for (BoundContainer group : groups) {
            if (group.id().equals(selected)) {
                return group;
            }
        }
        return groups.get(0);
    }

    private void cycleGroup() {
        List<BoundContainer> groups = BatchSelection.eligibleGroups();
        if (groups.isEmpty()) {
            return;
        }
        BoundContainer current = currentGroup(groups);
        int index = groups.indexOf(current);
        BatchSelection.setTargetGroup(groups.get((index + 1) % groups.size()).id());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 4, 0xFFFFFF);

        List<BatchCandidate> candidates = BatchSelection.candidates();
        int listBottom = height - 36;
        int visible = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, candidates.size() - visible)));

        int y = LIST_TOP;
        for (int index = scroll; index < candidates.size() && y + ROW_HEIGHT <= listBottom; index++) {
            BatchCandidate candidate = candidates.get(index);
            drawRow(graphics, candidate, y, mouseX, mouseY);
            y += ROW_HEIGHT;
        }
        drawFooter(graphics, listBottom);
    }

    private void drawRow(GuiGraphics graphics, BatchCandidate candidate, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= LIST_MARGIN && mouseX <= width - LIST_MARGIN
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
        if (hovered) {
            graphics.fill(LIST_MARGIN - 2, y - 1, width - LIST_MARGIN + 2, y + ROW_HEIGHT - 1, 0x30FFFFFF);
        }
        int color = colorOf(candidate);
        String role = roleText(candidate);
        String name = blockName(candidate.pos());
        BlockPos pos = candidate.pos();
        String line = String.format("%s  %s  (%d, %d, %d)", role, name, pos.getX(), pos.getY(), pos.getZ());
        graphics.drawString(font, line, LIST_MARGIN, y, color, false);
        if (BatchSelection.targetRole() == BoundContainer.Role.SLAVE && candidate.accepted()) {
            String pair = pairText(pos);
            if (!pair.isEmpty()) {
                graphics.drawString(font, pair, width - LIST_MARGIN - font.width(pair), y, 0x9FD0FF, false);
            }
        }
    }

    private void drawFooter(GuiGraphics graphics, int listBottom) {
        List<BatchCandidate> candidates = BatchSelection.candidates();
        int accepted = BatchSelection.acceptedCount();
        int rejected = candidates.size() - accepted;
        Component counts = Component.translatableWithFallback("text.recipe_sender.batch_counts",
                "已选 %s：主 %s / 并列 %s / 从 %s，拒绝 %s",
                accepted, countRole(BoundContainer.Role.MASTER), countRole(BoundContainer.Role.PARALLEL),
                countRole(BoundContainer.Role.SLAVE), rejected);
        graphics.drawString(font, counts, LIST_MARGIN, listBottom + 2, 0xFFFFFF, false);

        if (BatchSelection.targetRole() == BoundContainer.Role.SLAVE) {
            List<BatchSelection.Pair> pairs = BatchSelection.pairing();
            Component hint = pairs.isEmpty()
                    ? Component.translatableWithFallback("text.recipe_sender.batch_pair_mismatch",
                            "数量与目标组不匹配，无法配对")
                    : Component.translatableWithFallback("text.recipe_sender.batch_pair_ready",
                            "已配对 %s 对（按主向顺序）", pairs.size());
            graphics.drawString(font, hint, LIST_MARGIN, listBottom + 14,
                    pairs.isEmpty() ? 0xFF5555 : 0x9FD0FF, false);
        } else if (accepted < 2) {
            graphics.drawString(font, Component.translatableWithFallback("text.recipe_sender.batch_need_two",
                    "至少需要两台同类机器才能成组"), LIST_MARGIN, listBottom + 14, 0xFF5555, false);
        }
    }

    private int countRole(BoundContainer.Role role) {
        int count = 0;
        for (BatchCandidate candidate : BatchSelection.candidates()) {
            if (candidate.accepted() && candidate.role() == role) {
                count++;
            }
        }
        return count;
    }

    private String roleText(BatchCandidate candidate) {
        return switch (candidate.reject()) {
            case ALREADY_BOUND -> text("text.recipe_sender.batch_reject_bound", "已绑定");
            case HUB -> text("text.recipe_sender.batch_reject_hub", "是中枢");
            case NOT_SAME_KIND -> text("text.recipe_sender.batch_reject_kind", "不同类");
            case EXCLUDED -> text("text.recipe_sender.batch_reject_excluded", "已剔除");
            case NONE -> candidate.role() == BoundContainer.Role.MASTER
                    ? text("text.recipe_sender.tag_master", "主容器")
                    : (candidate.role() == BoundContainer.Role.SLAVE
                            ? text("text.recipe_sender.tag_slave_role", "从容器")
                            : text("text.recipe_sender.tag_parallel_member", "并列成员"));
        };
    }

    private static int colorOf(BatchCandidate candidate) {
        return switch (candidate.reject()) {
            case NONE -> candidate.role() == BoundContainer.Role.MASTER ? 0xFFC040
                    : (candidate.role() == BoundContainer.Role.SLAVE ? 0x60E060 : 0x40C0FF);
            case EXCLUDED -> 0x808080;
            default -> 0xFF5555;
        };
    }

    private String pairText(BlockPos slavePos) {
        for (BatchSelection.Pair pair : BatchSelection.pairing()) {
            if (pair.slave().equals(slavePos)) {
                return "→ " + blockName(pair.member());
            }
        }
        return "";
    }

    private String blockName(BlockPos pos) {
        if (minecraft == null || minecraft.level == null) {
            return "?";
        }
        return minecraft.level.getBlockState(pos).getBlock().getName().getString();
    }

    private static String text(String key, String fallback) {
        return com.lai.recipesender.client.Lang.text(key, fallback);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= LIST_TOP && mouseY < height - 36
                && mouseX >= LIST_MARGIN && mouseX <= width - LIST_MARGIN) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            List<BatchCandidate> candidates = BatchSelection.candidates();
            if (index >= 0 && index < candidates.size()) {
                BatchSelection.toggle(candidates.get(index).pos());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) Math.signum(delta) * 3;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 提交整份计划。 */
    private void submit() {
        List<BatchCandidate> accepted = BatchSelection.accepted();
        if (accepted.isEmpty()) {
            NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_empty",
                    "框里没有可用的物品容器"), NoticeSeverity.ERROR);
            return;
        }
        List<BatchBindPacket.Entry> entries = new ArrayList<>();
        if (BatchSelection.targetRole() == BoundContainer.Role.SLAVE) {
            List<BatchSelection.Pair> pairs = BatchSelection.pairing();
            if (pairs.isEmpty()) {
                NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_pair_mismatch",
                        "数量与目标组不匹配，无法配对"), NoticeSeverity.ERROR);
                return;
            }
            for (BatchSelection.Pair pair : pairs) {
                entries.add(new BatchBindPacket.Entry(pair.slave(), BoundContainer.Role.SLAVE, pair.member()));
            }
        } else {
            BlockPos master = BatchSelection.masterPos();
            if (master == null || accepted.size() < 2) {
                NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_need_two",
                        "至少需要两台同类机器才能成组"), NoticeSeverity.ERROR);
                return;
            }
            for (BatchCandidate candidate : accepted) {
                entries.add(new BatchBindPacket.Entry(candidate.pos(), candidate.role(),
                        candidate.role() == BoundContainer.Role.MASTER ? null : master));
            }
        }

        ResourceLocation dimension = minecraft != null && minecraft.level != null
                ? minecraft.level.dimension().location() : null;
        if (dimension == null) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new BatchBindPacket(UUID.randomUUID(), dimension,
                BatchSelection.first(), BatchSelection.second(), BatchSelection.groupMode(), entries));
        onClose();
    }
}
