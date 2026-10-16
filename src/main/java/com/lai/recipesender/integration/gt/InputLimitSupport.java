package com.lai.recipesender.integration.gt;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

/**
 * 格雷机器「防止堵料」（Anti-Clog）开关的只读探测。
 *
 * <p>这是玩家在机器 GUI 的配置器里按下的那个开关（GTCEu 26.10.8 的
 * {@code com.gregtechceu.gtceu.api.machine.feature.multiblock.IInputLimitableMachine}）：
 * 打开之后「每种物品最多占用一个输入槽」——只要别的槽里已经躺着同一个物品，新的空槽就拒收它。
 * 开关不在 {@code config/gtceu.yaml} 里，而是每台机器各自保存的一个字段
 * （{@code CustomItemStackHandler#isInputLimited}），因此只能每次现读，不能缓存。
 *
 * <p>它对投放规划是决定性的：模拟器允许同一物品占满所有空槽，真实处理器只让它占一个，
 * 于是「模拟说装得下、{@code insertItem} 只收下一部分」——差额变成残片，还会让按份记账的
 * 流体跟着超发。所以 {@code RemoteTransferPlanner} 把这条规则也搬进了模拟副本，读到的开关
 * 就是它唯一的输入。
 *
 * <p>三条通路依次尝试，任一条给出答案就用它；全部读不到按「没开」处理——退回 1.0.38 的精度，
 * 只会多算不会少发：
 * <ol>
 *   <li>方块实体 → {@code getMetaMachine()} → {@code IInputLimitableMachine#isInputLimit()}：
 *       语义最准，输入总线部件与单方块机器都走这条；</li>
 *   <li>物品能力对象自己的 {@code isInputLimited} 字段；</li>
 *   <li>能力对象藏在 {@code storage} 字段里的那个处理器
 *       （{@code NotifiableItemStackHandler#storage}）。</li>
 * </ol>
 *
 * <p>全程纯反射：GTCEu 不在编译期依赖里，同款说明见 {@link GtCircuitSupport}。
 */
public final class InputLimitSupport {

    /** 机器上的开关方法名（{@code IInputLimitableMachine#isInputLimit()}）。 */
    private static final String MACHINE_METHOD = "isInputLimit";
    /** 处理器上的开关字段名（{@code CustomItemStackHandler#isInputLimited}）。 */
    private static final String HANDLER_FIELD = "isInputLimited";
    /** 处理器把真正的槽位数组藏在这个字段里（{@code NotifiableItemStackHandler#storage}）。 */
    private static final String STORAGE_FIELD = "storage";
    /** 方块实体取出机器实例的方法名（{@code MetaMachine#getMetaMachine()}）。 */
    private static final String MACHINE_ACCESSOR = "getMetaMachine";

    private InputLimitSupport() {
    }

    /**
     * 这个落点是否开着「同种物品最多占用一个输入槽」。
     *
     * @param handler     落点的物品能力，可能为 {@code null}
     * @param blockEntity 落点的方块实体，可能为 {@code null}（模拟副本就没有）
     * @return 开着返回 {@code true}；读不到、不是格雷机器、或者玩家没开，都返回 {@code false}
     */
    public static boolean isInputLimited(IItemHandler handler, BlockEntity blockEntity) {
        Boolean fromMachine = machineLimit(blockEntity);
        if (fromMachine != null) {
            return fromMachine;
        }
        return handlerLimit(handler);
    }

    /** 走机器实例：{@code getMetaMachine()} → {@code isInputLimit()}；不是格雷机器就返回 {@code null}。 */
    private static Boolean machineLimit(BlockEntity blockEntity) {
        Object machine = GtCircuitSupport.invokePublicNoArg(blockEntity, MACHINE_ACCESSOR);
        Object value = GtCircuitSupport.invokePublicNoArg(machine, MACHINE_METHOD);
        return value instanceof Boolean flag ? flag : null;
    }

    /** 走处理器字段：能力对象自己，或者它 {@code storage} 字段里的那个处理器。 */
    private static boolean handlerLimit(IItemHandler handler) {
        if (handler == null) {
            return false;
        }
        Object value = GtCircuitSupport.readFieldByName(handler, HANDLER_FIELD);
        if (value == null) {
            Object storage = GtCircuitSupport.readFieldByName(handler, STORAGE_FIELD);
            value = GtCircuitSupport.readFieldByName(storage, HANDLER_FIELD);
        }
        return Boolean.TRUE.equals(value);
    }
}
