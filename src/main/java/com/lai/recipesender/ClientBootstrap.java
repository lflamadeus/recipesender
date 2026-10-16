package com.lai.recipesender;

import com.lai.recipesender.client.RecipeSenderClient;
import com.lai.recipesender.client.RecipeSenderConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

/** 隔离 EMI 与 Minecraft 客户端类，避免专用服务端误加载。 */
final class ClientBootstrap {
    private ClientBootstrap() {
    }

    /**
     * 注册仅客户端加载的按键、界面事件，以及游戏内配置界面。
     *
     * <p><b>配置界面必须在这里注册</b>：模组列表里「配置」按钮的可用状态直接取自
     * {@code ConfigScreenHandler.getScreenFactoryFor(modInfo).isPresent()}，
     * 而扩展点只在模组构造期间能注册，错过这个窗口按钮就永远是灰的。
     *
     * <p>用构造器注入的 {@code context} 而不是 {@code ModLoadingContext.get()}：
     * 后者在 Forge 47.4 里已被标记为「将来删除」（同 {@code RecipeSenderConfig#register}）。
     */
    static void register(ModLoadingContext context, IEventBus modBus) {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new RecipeSenderConfigScreen(parent)));
        RecipeSenderClient.register(modBus);
    }
}
