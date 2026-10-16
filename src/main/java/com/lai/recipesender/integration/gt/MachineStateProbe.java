package com.lai.recipesender.integration.gt;

import com.lai.recipesender.model.MachineState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 探测一台格雷机器此刻在不在跑配方（纯反射——格雷不在编译期依赖里，与
 * {@link GtCircuitSupport} 同一套做法）。
 *
 * <p>判据只有一条：{@code IRecipeLogicMachine.getRecipeLogic()} 拿到的 {@code RecipeLogic} 闲不闲。
 * 它自己的 {@code isIdle()} 把「等料」「输出满」也算作不闲，这正是要的效果——那些机器手上还有一炉，
 * 现在改电路同样会把它冲掉。
 *
 * <p>三档结果的语义见 {@link MachineState}：只有<b>确定在跑</b>才返回 {@link MachineState#BUSY}，
 * 其余一律按「可以投料」处理。探不出来时宁可照旧投料，也不能因为探测失败就少送材料。
 */
public final class MachineStateProbe {

    private MachineStateProbe() {
    }

    /** 探测这个方块实体上的机器此刻忙不忙。 */
    public static MachineState stateOf(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return MachineState.NOT_A_MACHINE;
        }
        Object machine = GtCircuitSupport.invokePublicNoArg(blockEntity, "getMetaMachine");
        if (machine == null) {
            return MachineState.NOT_A_MACHINE;
        }
        // 单方块机器自己就带配方逻辑；多方块控制器也带（IWorkableMultiController 继承了
        // IRecipeLogicMachine），所以这一个判定把两者都覆盖了。
        Boolean idle = idleOf(machine);
        if (idle != null) {
            return idle ? MachineState.IDLE : MachineState.BUSY;
        }
        // 多方块部件（输入总线、输入仓、外壳）自己没有配方逻辑，逻辑在控制器上：顺着
        // IMultiPart.getControllers() 找控制器，再取 self()。不是部件时 getControllers() 不存在，
        // 循环直接空转——与 GtCircuitSupport.findCircuitHoldersAt 走同一条路子。
        Object controllers = GtCircuitSupport.invokePublicNoArg(machine, "getControllers");
        if (controllers instanceof Iterable<?> iterable) {
            boolean sawLogic = false;
            for (Object controller : iterable) {
                if (controller == null) {
                    continue;
                }
                Object candidate = GtCircuitSupport.invokePublicNoArg(controller, "self");
                if (candidate == null) {
                    candidate = controller;
                }
                Boolean controllerIdle = idleOf(candidate);
                if (controllerIdle == null) {
                    continue;
                }
                sawLogic = true;
                if (!controllerIdle) {
                    // 部件理论上可以同时挂在多个已成型控制器上，只要有一台在跑就不能碰它。
                    return MachineState.BUSY;
                }
            }
            if (sawLogic) {
                return MachineState.IDLE;
            }
        }
        return MachineState.NOT_A_MACHINE;
    }

    /**
     * 问一次「闲不闲」。
     *
     * <p>先问 {@code isIdle()}；拿不到就退回 {@code isWorking()} 取反；两个都问不到返回 {@code null}，
     * 由调用方按「没有配方逻辑」处理（也就是照旧投料）。
     */
    private static Boolean idleOf(Object machine) {
        Object logic = GtCircuitSupport.invokePublicNoArg(machine, "getRecipeLogic");
        if (logic == null) {
            return null;
        }
        Object idle = GtCircuitSupport.invokePublicNoArg(logic, "isIdle");
        if (idle instanceof Boolean value) {
            return value;
        }
        Object working = GtCircuitSupport.invokePublicNoArg(logic, "isWorking");
        if (working instanceof Boolean value) {
            return !value;
        }
        return null;
    }
}
