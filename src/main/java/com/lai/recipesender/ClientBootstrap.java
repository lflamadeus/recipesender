package com.lai.recipesender;

import com.lai.recipesender.client.RecipeSenderClient;
import net.minecraftforge.eventbus.api.IEventBus;

/** 隔离 EMI 与 Minecraft 客户端类，避免专用服务端误加载。 */
final class ClientBootstrap {
    private ClientBootstrap() {
    }

    /** 注册仅客户端加载的按键和界面事件。 */
    static void register(IEventBus modBus) {
        RecipeSenderClient.register(modBus);
    }
}
