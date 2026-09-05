package com.lai.recipesender.client;

/** 客户端网络回调入口，保持网络包与客户端界面解耦。 */
public final class ClientPacketHandler {
    private ClientPacketHandler() {
    }

    /** 将服务端统计结果转交给客户端选择状态。 */
    public static void acceptNearbyAvailability(long requestId, int batches) {
        RecipeSenderClient.acceptNearbyAvailability(requestId, batches);
    }
}
