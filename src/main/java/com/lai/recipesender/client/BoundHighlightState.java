package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.service.BoundGroupResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * ⑪ 世界内高亮：点过「高亮」的容器在 3 秒内以红色描边画出来。
 *
 * <p>状态刻意只有一份：高亮是「看一眼」的动作，不是常驻标记——同时高亮两组只会让画面变乱，
 * 而且没法区分谁是谁。再点一次「高亮」直接覆盖上一次，计时也重新开始。
 *
 * <p>这里只管「画哪些坐标、还剩多久」，不认识渲染；真正落笔在 {@link BoundHighlightRenderer}。
 * 时长固定 3 秒（60 tick），不提供配置项。
 */
final class BoundHighlightState {

    /** 固定 3 秒，不提供配置项。 */
    private static final int DURATION_TICKS = 60;

    private static List<BlockPos> positions = List.of();
    private static ResourceKey<Level> dimension;
    private static int remaining;

    private BoundHighlightState() {
    }

    /**
     * 高亮一个容器（若它是主容器，则连同整个并列组一起），并重新开始计时。
     *
     * <p>主容器带出一整组是有意为之：主容器 + 并列成员 + 从容器在发送时是**一个主体**，
     * 只框住主容器会让玩家以为材料只会进那一台，而实际上并列成员要均分、从容器要收溢出。
     *
     * @return 目标不在当前维度时返回 false（此时不设高亮，只发一条提示）
     */
    static boolean show(BoundContainer binding) {
        if (binding == null) {
            return false;
        }
        List<BoundContainer> targets = new ArrayList<>();
        if (binding.role() == BoundContainer.Role.MASTER) {
            BoundGroupResolver.BoundGroup group = BoundContainerClient.groupOf(binding.id());
            if (group != null) {
                targets.addAll(group.members());
                targets.addAll(group.slaves());
            }
        }
        if (targets.isEmpty()) {
            targets.add(binding);
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !binding.dimension().equals(minecraft.level.dimension())) {
            // 坐标只在本维度里有意义，跨维度画出来的是别处的方块。
            RecipeSenderClient.notifyPlayer(
                    Component.translatable("text.recipe_sender.highlight_other_dimension", binding.name()),
                    NoticeSeverity.WARN);
            return false;
        }

        List<BlockPos> collected = new ArrayList<>(targets.size());
        for (BoundContainer target : targets) {
            if (!collected.contains(target.pos())) {
                collected.add(target.pos());
            }
        }
        positions = List.copyOf(collected);
        dimension = binding.dimension();
        remaining = DURATION_TICKS;
        return true;
    }

    /** 每客户端 tick 调一次；到点后自己清空。 */
    static void tick() {
        if (remaining > 0 && --remaining == 0) {
            positions = List.of();
        }
    }

    /** 当前该画的方块；没在计时、或玩家换了维度时返回空列表。 */
    static List<BlockPos> positionsFor(ResourceKey<Level> current) {
        if (remaining <= 0 || positions.isEmpty() || dimension == null || !dimension.equals(current)) {
            return List.of();
        }
        return positions;
    }
}
