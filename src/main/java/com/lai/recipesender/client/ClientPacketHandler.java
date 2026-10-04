package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundDetail;
import com.lai.recipesender.model.BoundStatus;
import com.lai.recipesender.model.NoticeSeverity;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 客户端网络回调入口，保持网络包与客户端界面解耦。 */
public final class ClientPacketHandler {
    private ClientPacketHandler() {
    }

    /** 将服务端统计结果转交给客户端选择状态。 */
    public static void acceptNearbyAvailability(long requestId, int batches) {
        RecipeSenderClient.acceptNearbyAvailability(requestId, batches);
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
}
