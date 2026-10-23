package com.lai.recipesender.client.batch;

import com.lai.recipesender.RecipeSenderMod;
import com.lai.recipesender.client.NoticeOverlay;
import com.lai.recipesender.client.RecipeSenderClient;
import com.lai.recipesender.model.NoticeSeverity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 批量绑定的输入入口。
 *
 * <p>手势分两套，靠**绑定键按没按着**区分，互不抢占：
 * <ul>
 *   <li><b>改角点</b>（绑定键按着）：Shift+左键 = 重定起点，Shift+右键 = 重定另一个角点。
 *       任何阶段都能用，改完立刻重算，不用退出重来。</li>
 *   <li><b>改池子</b>（绑定键没按）：Shift+右键 = 补一台，Shift+左键 = 移出一台。</li>
 * </ul>
 * 进入模式是「按住 Shift + 绑定键 + 左键（或右键）」；模式内 <b>回车</b> 直接提交，
 * <b>退出键</b>（默认 Backspace，可在控制设置里改）退出，ESC 也能退。
 *
 * <p>**模式不会因为松手而结束**，也不再靠「对着空气按绑定键」退出——那个判定看的是准星指着什么，
 * 上面两套手势都得为它绕开，稍微动一下就可能误退，而误退会丢掉整框。现在退出是一个独立键位，
 * 不看准星。
 *
 * <p><b>为什么钩 {@link InputEvent.InteractionKeyMappingTriggered} 而不是方块交互事件</b>：
 * 1.0.53 用的是 {@code PlayerInteractEvent.RightClickBlock}，结果 Shift+右键 照样会弹出机器界面。
 * 根因在 {@code MultiPlayerGameMode.useItemOn}：它用 {@code startPrediction} 包住
 * {@code performUseItemOn}，而 {@code ServerboundUseItemOnPacket} 是 lambda **无条件**返回的——
 * 取消那个事件只让**客户端**跳过 {@code block.use}，包照发，服务端照跑 {@code block.use}，
 * 机器界面是服务端开的，所以界面还是弹出来。这个事件在 {@code Minecraft.startUseItem()} 的
 * 最开头（{@code gameMode.useItem} 之前），取消后一个包都不会发。
 *
 * <p>它同时也是唯一能挡住「按住不放」的位置：{@code handleKeybinds} 里
 * {@code keyUse.isDown() && rightClickDelay == 0} 每 4 tick 再走一次 {@code startUseItem}，
 * 左键的 {@code continueAttack} 更是**每 tick** 发一次同一事件——所以按住左键的连续挖掘
 * 也会被同一个取消挡住，不会出现「点一下没挖、按住却挖穿了」。
 */
@Mod.EventBusSubscriber(modid = RecipeSenderMod.MOD_ID, value = Dist.CLIENT)
public final class BatchBindInput {

    /**
     * 同一格、同一个键在这个窗口内的重复事件直接丢掉。
     *
     * <p>按住右键时 {@code startUseItem} 每 4 tick 被自动重放一次，不去抖的话
     * 「已在批量绑定中」这类提示会一直刷；4 tick 就是 200ms，取 6 留一点余量。
     * 两次操作本身都是幂等的，误吞一次也无害。
     */
    private static final long HOLD_REPEAT_TICKS = 6L;

    private static BlockPos lastActionPos;
    private static long lastActionTick;
    private static boolean lastActionUse;

    /**
     * 左键定起点的兜底计时（{@code -1} = 没挂）。
     *
     * <p>生存模式下 {@code Minecraft.startAttack()} 开头就是 {@code if (missTime > 0) return false}，
     * 而对着空气点一下会置 {@code missTime = 10}——这 0.5 秒里的左键<b>连事件都不会发</b>。
     * 「手滑点空了、紧接着点机器定起点」因此会静默失效，玩家只会以为模组坏了。所以按下左键时
     * 先挂一个兜底，下一 tick 还没等到攻击事件就自己补一次（此时 {@code hitResult} 已经刷新过）。
     */
    private static long leftEntryArmTick = -1L;
    private static boolean leftEntryHandled;

    /** 主向切换键。固定 P，不单独占一个键位（玩家已经嫌键太多了）。 */
    private static final int PRIMARY_KEY = GLFW.GLFW_KEY_P;

    private BatchBindInput() {
    }

    /** Shift+右键：定起点 / 定另一个角点 / 补一台。 */
    @SubscribeEvent
    public static void onUseItem(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        boolean shift = minecraft.player.isShiftKeyDown();
        if (!BatchSelection.active()) {
            if (!shift || !RecipeSenderClient.isBoundSendKeyHeld()) {
                return;
            }
            // 先取消再判坐标：宁可这一下什么都不发生，也不能把方块放下或者把机器界面打开。
            event.setCanceled(true);
            RecipeSenderClient.cancelPendingBind();
            BlockPos pos = lookedAtBlock(minecraft);
            if (pos == null) {
                noticeAimMiss();
                return;
            }
            if (!accept(minecraft, pos, true)) {
                return;
            }
            BatchSelection.cornerA(pos);
            return;
        }
        // 模式内只认 Shift：进来之后不必一直按着绑定键（松开任何键都不影响模式）。
        if (!shift) {
            // 不带 Shift 的右键照常交给游戏：还能开机器界面看一眼，不然想确认都得先退出。
            return;
        }
        event.setCanceled(true);
        BlockPos pos = lookedAtBlock(minecraft);
        if (pos == null || !accept(minecraft, pos, true)) {
            return;
        }
        // 绑定键按着 = 改角点；没按 = 改池子。改角点优先，否则「想改角点却把方块加进池子」
        // 这种事一定会发生（两个动作都是 Shift+右键）。
        if (RecipeSenderClient.isBoundSendKeyHeld() || BatchSelection.stage() == BatchSelection.Stage.FIRST) {
            BatchSelection.cornerB(pos);
            return;
        }
        BatchSelection.addSingle(pos);
    }

    /**
     * Shift+左键：进模式（定起点）/ 重定起点 / 移除。
     *
     * <p>左键在游戏里是挖方块，所以这里<b>先取消再判坐标</b>：三键没按全就完全不碰，
     * 按全了最坏情况也只是「什么都没发生」，绝不会在机器上挖一个洞。
     */
    @SubscribeEvent
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        boolean shift = minecraft.player.isShiftKeyDown();
        if (!BatchSelection.active()) {
            if (!shift || !RecipeSenderClient.isBoundSendKeyHeld()) {
                return;
            }
            leftEntryArmTick = -1L;
            leftEntryHandled = true;
            event.setCanceled(true);
            RecipeSenderClient.cancelPendingBind();
            BlockPos pos = lookedAtBlock(minecraft);
            if (pos == null) {
                noticeAimMiss();
                return;
            }
            if (!accept(minecraft, pos, false)) {
                return;
            }
            BatchSelection.cornerA(pos);
            return;
        }
        if (!shift) {
            // 不带 Shift 的左键照常挖方块：模式只接管带 Shift 的那几个手势。
            return;
        }
        event.setCanceled(true);
        // 关掉挥臂：按住左键时 continueAttack 每 tick 都会走这里，保留挥臂会连带每 tick
        // 刷一次「正在挖」的方块碎屑，看起来像在挖机器。移除/重定都有文字回执，不缺这一下动画。
        event.setSwingHand(false);
        BlockPos pos = lookedAtBlock(minecraft);
        if (pos == null || !accept(minecraft, pos, false)) {
            return;
        }
        // 左键永远管起点这一侧：绑定键按着 = 重定起点（保留已剔除的与手动主向）。
        if (RecipeSenderClient.isBoundSendKeyHeld()) {
            BatchSelection.cornerA(pos, true);
            return;
        }
        switch (BatchSelection.stage()) {
            case FIRST -> BatchSelection.cornerA(pos, true);
            case BOX -> BatchSelection.remove(pos);
            default -> {
            }
        }
    }

    /**
     * 回车提交；ESC / 退出键退出；**按住绑定键 + P** 定主向。
     *
     * <p>绑定键在模式里不再承担「退出」：按下、抬起都不做任何事，只作为 Shift+左/右键 的修饰键，
     * 以及 P 的前置条件。这样「Shift+绑定键+右键 改角点」松手时不会被当成退出。
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!BatchSelection.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 有界面时这些键归界面（例如聊天框里打字的 Backspace）。
        if (minecraft.screen != null) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (RecipeSenderClient.matchesBatchExitKey(event.getKey(), event.getScanCode())) {
            BatchSelection.exit();
            return;
        }
        // 只有绑定键按着的时候才认 P：别的场景按 P 归游戏和别的模组。
        if (event.getKey() == PRIMARY_KEY && RecipeSenderClient.isBoundSendKeyHeld()) {
            BatchSelection.setPrimaryFromFacing();
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            if (BatchSelection.stage() == BatchSelection.Stage.BOX) {
                BatchSelection.submit();
            }
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            BatchSelection.exit();
        }
    }

    /** 左键定起点的兜底也挂在这里。 */
    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !BatchSelection.active()
                && minecraft.player.isShiftKeyDown() && RecipeSenderClient.isBoundSendKeyHeld()) {
            // 见 leftEntryArmTick：这里只挂兜底，真正定起点由攻击事件负责（那时 hitResult 才是准的）。
            leftEntryArmTick = minecraft.level.getGameTime();
            leftEntryHandled = false;
            RecipeSenderClient.cancelPendingBind();
        }
    }

    /**
     * 每客户端 tick：补一次「左键定起点但攻击事件没来」的情况。
     *
     * <p>只在 {@code missTime} 那条路上会真的执行——正常情况下攻击事件在同一 tick 里已经
     * 处理完，{@link #leftEntryHandled} 为 true，这里直接收摊。
     */
    public static void tick() {
        if (leftEntryArmTick < 0L) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || BatchSelection.active() || leftEntryHandled) {
            leftEntryArmTick = -1L;
            return;
        }
        if (minecraft.level.getGameTime() - leftEntryArmTick < 1L) {
            return;
        }
        leftEntryArmTick = -1L;
        if (!minecraft.player.isShiftKeyDown() || !RecipeSenderClient.isBoundSendKeyHeld()) {
            return;
        }
        BlockPos pos = lookedAtBlock(minecraft);
        if (pos != null && accept(minecraft, pos, false)) {
            BatchSelection.cornerA(pos);
        }
    }

    private static BlockPos lookedAtBlock(Minecraft minecraft) {
        HitResult picked = minecraft.hitResult;
        return picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : null;
    }

    /** 三键按全了却没对准方块：这次按下就这么算了，给一条提示，别让人以为是没响应。 */
    private static void noticeAimMiss() {
        NoticeOverlay.show(Component.translatableWithFallback(
                "text.recipe_sender.bind_no_target", "准星没有指向方块"), NoticeSeverity.WARN);
    }

    /** 同一格同一键的自动重复：返回 false 表示这一下应该丢掉。 */
    private static boolean accept(Minecraft minecraft, BlockPos pos, boolean use) {
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (lastActionPos != null && pos.equals(lastActionPos) && use == lastActionUse
                && now - lastActionTick < HOLD_REPEAT_TICKS) {
            // 丢掉也要刷新窗口：按住不放时每 4 tick 来一次，不刷新的话第 8 tick 就会漏过去，
            // 变成「按住右键在框里定完角点、紧接着又把那一格当单台补一次」。
            lastActionTick = now;
            return false;
        }
        lastActionPos = pos.immutable();
        lastActionUse = use;
        lastActionTick = now;
        return true;
    }
}
