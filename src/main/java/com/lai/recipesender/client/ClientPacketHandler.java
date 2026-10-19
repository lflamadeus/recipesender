package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.NoticeSeverity;
import com.lai.recipesender.model.SourceScope;
import com.lai.recipesender.client.batch.BatchBindScreen;
import com.lai.recipesender.client.batch.BatchSelection;
import com.lai.recipesender.network.packet.BatchBindResultPacket;
import com.lai.recipesender.network.packet.MachineStatusPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 客户端网络回调入口，保持网络包与客户端界面解耦。 */
public final class ClientPacketHandler {
    private ClientPacketHandler() {
    }

    /** 将服务端统计结果转交给客户端选择状态。 */
    public static void acceptNearbyAvailability(long requestId, int batches, BoundDetail reason) {
        RecipeSenderClient.acceptNearbyAvailability(requestId, batches, reason);
    }

    /** 用服务端推送的绑定列表与「上次选择」替换客户端只读缓存。 */
    public static void acceptBoundContainers(List<BoundContainer> bindings, Map<String, UUID> lastChoices) {
        BoundContainerClient.acceptSync(bindings, lastChoices);
        RecipeSenderClient.onBoundContainersSynced();
    }

    /** 转交一次「发送到已绑定容器」的结果。 */
    public static void acceptBoundInsertResult(long requestId, UUID bindingId, BoundStatus status,
                                               int insertedBatches, int requestedBatches,
                                               int overflowBatches, BoundDetail detail) {
        RecipeSenderClient.acceptBoundInsertResult(requestId, bindingId, status, insertedBatches,
                requestedBatches, overflowBatches, detail);
    }

    /** 转交一次「直达」的结果。 */
    public static void acceptDirectInsertResult(long requestId, UUID bindingId, BoundStatus status,
                                                int insertedBatches, int requestedBatches,
                                                BoundDetail detail, SourceScope scope) {
        RecipeSenderClient.acceptDirectInsertResult(requestId, bindingId, status, insertedBatches,
                requestedBatches, detail, scope);
    }

    /**
     * 转交一次「每台绑定机器现在忙不忙」的全量状态。
     *
     * <p>只丢进缓存，界面自己去取：状态每秒刷新，界面每帧都从缓存读，两边不必知道对方。
     */
    public static void acceptMachineStatus(List<MachineStatusPacket.Entry> entries) {
        BoundContainerClient.acceptMachineStates(entries);
    }

    /**
     * 显示服务端推来的一条提示。
     *
     * <p>服务端只给语言键与参数，这里才把文案定下来：跟着客户端的语言走，
     * 也顺便复用语言文件的回退机制。严重程度由服务端一起给：客户端不该靠键名去猜
     * 「这条算不算失败」。
     */
    public static void acceptNotice(String key, List<String> args, NoticeSeverity severity) {
        if (key == null || key.isEmpty()) {
            return;
        }
        NoticeOverlay.show(Component.translatable(key, args.toArray()), severity);
    }

    /**
     * 转交一次批量绑定的汇总结果：清掉批量状态，再按成功/失败给出提示。
     *
     * <p>失败项逐条进聊天栏（最多 5 条，其余汇总成一句）——整批静默失败会让玩家以为全绑上了。
     */
    public static void acceptBatchBindResult(UUID requestId, int created,
                                             List<BatchBindResultPacket.Failure> failures) {
        BatchSelection.cancel();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BatchBindScreen) {
            minecraft.setScreen(null);
        }
        int failed = failures == null ? 0 : failures.size();
        NoticeOverlay.show(Component.translatableWithFallback("text.recipe_sender.batch_result",
                        "批量绑定：成功 %s 项，失败 %s 项", created, failed),
                failed == 0 ? NoticeSeverity.INFO : NoticeSeverity.WARN);
        if (failed == 0 || minecraft.player == null) {
            return;
        }
        int shown = 0;
        for (BatchBindResultPacket.Failure failure : failures) {
            if (shown++ >= 5) {
                minecraft.player.displayClientMessage(
                        Component.translatableWithFallback("text.recipe_sender.batch_more_failures",
                                "还有 %s 项失败未列出", failed - 5), false);
                break;
            }
            minecraft.player.displayClientMessage(Component.translatableWithFallback(
                    "text.recipe_sender.batch_failure", "(%s, %s, %s) 失败：%s",
                    failure.pos().getX(), failure.pos().getY(), failure.pos().getZ(),
                    Lang.text(failure.reasonKey(), failure.reasonKey())), false);
        }
    }
}
