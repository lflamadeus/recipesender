package com.lai.recipesender.service;

import com.lai.recipesender.integration.gt.GtCircuitSupport;
import com.lai.recipesender.network.packet.ClearContainerCircuitPacket;
import com.lai.recipesender.network.packet.SetContainerCircuitPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在服务端把目标机器的编程电路调整成配方要求的样子。
 *
 * <p>两条通路，机器实例的来源不同、拿到之后完全一样：
 * <ul>
 *   <li>{@link #apply} / {@link #clear}：玩家<b>正开着</b>那台机器的界面，从菜单里的 ModularUI 取实例；</li>
 *   <li>{@link #applyToBlockEntity}：落点是<b>远程方块</b>（已绑定容器），从方块实体取实例。</li>
 * </ul>
 *
 * <p>客户端提交的只是「想要哪个编号」或「把这个容器的电路清空」，机器是否具备电路槽、是否启用、
 * 写入哪一格全部由服务端根据真实实例决定，因此客户端无法借此改动任意机器。
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
     * 按配方要求调整一台<b>远程</b>机器的电路（方块实体通路）。
     *
     * <p>与 {@link #apply} / {@link #clear} 的区别只在「机器实例从哪来」：那两条走玩家当前打开的
     * 菜单，这条走绑定记录里的坐标——「发送到已绑定容器」的落点玩家根本不会开着界面。拿到实例
     * 之后的判定与读写完全一样，都交给 {@link GtCircuitSupport}。
     *
     * <p>刻意不校验玩家权限：绑定记录本身已经由服务端按玩家存档校验过，能发送就说明这个落点是
     * 该玩家自己绑的。调用方也<b>不能</b>因为这里失败而中断材料投放——普通箱子取不到电路槽是常态。
     *
     * @param circuit    配方要求的编号（0-{@link GtCircuitSupport#MAX_CIRCUIT}）；
     *                   负数表示配方不使用电路，此时由 {@code gregRecipe} 决定要不要置空
     * @param gregRecipe 是否确认这是格雷配方；{@code false} 且 {@code circuit < 0} 时一律不动电路
     * @return 是否真的改动了至少一处电路
     */
    public static boolean applyToBlockEntity(BlockEntity blockEntity, int circuit, boolean gregRecipe) {
        if (circuit < 0 && !gregRecipe) {
            // 客户端没认出格雷配方（或者压根不是）：一律不动电路，免得把玩家自己设的电路冲掉。
            return false;
        }
        if (circuit > GtCircuitSupport.MAX_CIRCUIT) {
            // 数据包内容不可信，越界一律当作「不动」。
            return false;
        }
        boolean changed = false;
        try {
            for (Object machine : GtCircuitSupport.findCircuitHoldersAt(blockEntity)) {
                changed |= writeCircuit(machine, circuit);
            }
        } catch (RuntimeException exception) {
            // 远程方块可能在解析与写入之间被拆掉、卸载或换掉机器实例，单次失败不能破坏服务器线程，
            // 更不能影响紧接着的材料投放。
            LOGGER.warn("Failed to set circuit on bound machine", exception);
        }
        return changed;
    }

    /** 把一台机器的电路写成目标状态；已经就是目标状态时不写。 */
    private static boolean writeCircuit(Object machine, int circuit) {
        if (circuit >= 0) {
            if (GtCircuitSupport.readCurrentCircuit(machine) == circuit) {
                return false;
            }
            if (GtCircuitSupport.setCircuit(machine, circuit)) {
                LOGGER.debug("已将 {} 的电路调整为 {}", machine.getClass().getSimpleName(), circuit);
                return true;
            }
            return false;
        }
        int current = GtCircuitSupport.readCurrentCircuit(machine);
        if (current < 0) {
            // 槽里放的不是编程电路，那是玩家自己的东西，不代删。
            return false;
        }
        if (GtCircuitSupport.clearCircuit(machine)) {
            LOGGER.debug("已清空 {} 的电路槽（原编号 {}）", machine.getClass().getSimpleName(), current);
            return true;
        }
        return false;
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
