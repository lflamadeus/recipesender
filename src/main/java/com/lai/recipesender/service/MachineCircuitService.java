package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在服务端把配方要求的编程电路写入当前打开的机器。
 *
 * <p>客户端提交的只是“想要哪个编号”，机器是否具备电路槽、是否启用、写入哪一格全部由服务端
 * 根据真实菜单决定，因此客户端无法借此改动任意机器。
 */
public final class MachineCircuitService {
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private MachineCircuitService() {
    }

    /** 校验并执行一次电路调整；目标不支持时静默跳过。 */
    public static void apply(ServerPlayer player, SetContainerCircuitPacket packet) {
        if (player == null || packet.circuit() < 0 || packet.circuit() > GtCircuitSupport.MAX_CIRCUIT) {
            return;
        }
        // 先确认玩家和菜单仍然对应，防止延迟数据包写到错误的机器上。
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != packet.containerId()) {
            return;
        }
        try {
            Object machine = GtCircuitSupport.findCircuitHolder(menu);
            if (machine == null || !GtCircuitSupport.canAdjustCircuit(machine)) {
                return;
            }
            // 电路已经就是目标编号时不再写：写入会改动物品容器并触发一次方块实体同步，
            // 而 GT 界面里点一下同一个编号本来也是空操作，跳过可以省掉这次无谓的同步。
            if (GtCircuitSupport.readCurrentCircuit(machine) == packet.circuit()) {
                return;
            }
            if (!GtCircuitSupport.setCircuit(machine, packet.circuit())) {
                return;
            }
            LOGGER.debug("已将 {} 的电路调整为 {}", machine.getClass().getSimpleName(),
                    packet.circuit());
        } catch (RuntimeException exception) {
            // 第三方机器可能在关闭或重载时改变状态，单次失败不能破坏服务器线程。
            LOGGER.warn("Failed to set machine circuit for {}", player.getGameProfile().getName(), exception);
        }
    }
}
