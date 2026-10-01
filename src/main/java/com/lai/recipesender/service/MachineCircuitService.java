package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.network.packet.ClearContainerCircuitPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在服务端把当前打开的机器的编程电路调整成配方要求的样子。
 *
 * <p>客户端提交的只是“想要哪个编号”或“把这个容器的电路清空”，机器是否具备电路槽、是否启用、
 * 写入哪一格全部由服务端根据真实菜单决定，因此客户端无法借此改动任意机器。
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
        try {
            Object machine = resolveMachine(player, packet.containerId());
            if (machine == null) {
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

    /**
     * 悬停的配方不使用电路时，把目标容器的电路槽清空。
     *
     * <p>不做机型判断：格雷机器支持哪些配方由 GT 自己决定，模组不替它把关。目标容器没有电路槽时
     * {@link #resolveMachine} 就已经返回 {@code null}（原版箱子这类目标连机器实例都取不到），
     * 所以那里什么都不会发生。
     */
    public static void clear(ServerPlayer player, ClearContainerCircuitPacket packet) {
        if (player == null) {
            return;
        }
        try {
            Object machine = resolveMachine(player, packet.containerId());
            if (machine == null) {
                return;
            }
            int current = GtCircuitSupport.readCurrentCircuit(machine);
            if (current < 0) {
                LOGGER.info("[电路] 未置空：{} 的电路槽里不是编程电路（读出 {}）",
                        machine.getClass().getSimpleName(), current);
                return;
            }
            if (!GtCircuitSupport.clearCircuit(machine)) {
                LOGGER.warn("[电路] 未置空：{} 的电路槽清不掉", machine.getClass().getSimpleName());
                return;
            }
            LOGGER.debug("已清空 {} 的电路槽（原编号 {}）", machine.getClass().getSimpleName(), current);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to clear machine circuit for {}", player.getGameProfile().getName(), exception);
        }
    }

    /**
     * 先确认玩家和菜单仍然对应（防止延迟数据包写到错误的机器上），再取出可调整电路的机器实例。
     *
     * @return 机器实例；菜单已切换、目标不是格雷机器界面或没有电路槽时返回 {@code null}
     */
    private static Object resolveMachine(ServerPlayer player, int containerId) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId) {
            return null;
        }
        Object machine = GtCircuitSupport.findCircuitHolder(menu);
        if (machine == null || !GtCircuitSupport.canAdjustCircuit(machine)) {
            return null;
        }
        return machine;
    }
}
