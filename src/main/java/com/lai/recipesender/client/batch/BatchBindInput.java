package com.lai.recipesender.client.batch;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.client.RecipeSenderClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 批量绑定的输入入口。
 *
 * <p>手势：**按住 Shift + 绑定键，右键点第一个方块** → 进入批量绑定模式并开始描边；
 * 之后**右键**点第二个方块（不必再按修饰键，长时间按着 Shift 太累）→ 出框。
 * 在框里**左键**点某块 = 剔除/恢复；**回车**打开确认页；**ESC** 退出。
 *
 * <p>为什么用右键：左键在游戏里是挖方块，误触代价太高；右键还能用「取消事件」干净地挡住
 * 方块交互（否则会顺手放下手里的方块或打开机器界面）。
 */
@Mod.EventBusSubscriber(modid = RecipeSenderMod.MOD_ID, value = Dist.CLIENT)
public final class BatchBindInput {

    private BatchBindInput() {
    }

    /** Shift + 绑定键 + 右键 = 开始；模式里右键 = 定第二个角点 / 重新框。 */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() != net.minecraftforge.fml.LogicalSide.CLIENT) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        boolean modifier = RecipeSenderClient.isBoundSendKeyHeld() && minecraft.player.isShiftKeyDown();
        if (!BatchSelection.active() && !modifier) {
            return;
        }
        // 在批量模式里，右键一律不交给方块。
        event.setCanceled(true);
        BatchSelection.click(event.getPos(), modifier);
    }

    /** 回车打开确认页；ESC 清掉批量状态（暂停菜单照常弹出）。 */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !BatchSelection.active()) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            if (BatchSelection.stage() == BatchSelection.Stage.BOX) {
                Minecraft.getInstance().setScreen(new BatchBindScreen());
            }
        } else if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            BatchSelection.cancel();
        }
    }

    /** 左键点候选 = 剔除/恢复；同时吃掉这一下攻击，别把方块挖了。 */
    @SubscribeEvent
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() || !BatchSelection.active()
                || BatchSelection.stage() != BatchSelection.Stage.BOX) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        HitResult picked = minecraft.hitResult;
        if (picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (BatchSelection.candidates().stream().anyMatch(candidate -> candidate.pos().equals(pos))) {
                BatchSelection.toggle(pos);
            }
        }
        event.setCanceled(true);
    }
}
