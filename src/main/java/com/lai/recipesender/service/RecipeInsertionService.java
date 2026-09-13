package com.lai.recipesender.service;

import com.lai.recipesender.network.packet.InsertRecipeItemsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在服务端主线程中完成验证、扣除和菜单同步，保证一次投放的原子性。
 * 所有外部输入都视为不可信，客户端的材料统计只用于界面提示，不能替代服务端校验。
 * 具体规划逻辑位于 {@link TransferPlanner}，与客户端容量估算共用同一份实现。
 */
public final class RecipeInsertionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private RecipeInsertionService() {
    }

    /** 在服务端验证并原子执行一次正向材料投放。 */
    public static void insert(ServerPlayer player, InsertRecipeItemsPacket packet) {
        // 先确认玩家和菜单仍然对应，防止延迟数据包写入错误的容器。
        if (player == null || packet.requirements().isEmpty()) {
            return;
        }

        // containerMenu 由玩家对象始终持有（默认为背包菜单），这里只需要确认它仍是同一个容器。
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != packet.containerId()) {
            return;
        }

        try {
            TransferPlanner.TransferPlan plan = TransferPlanner.create(player.getInventory(), menu,
                    packet.requirements());
            if (plan == null) {
                return;
            }
            plan.apply(player.getInventory());
            menu.broadcastChanges();
        } catch (RuntimeException exception) {
            // 第三方机器菜单可能在关闭或重载时改变插槽，单次失败不能破坏服务器线程。
            LOGGER.warn("Failed to insert recipe ingredients for {}", player.getGameProfile().getName(), exception);
        }
    }
}
