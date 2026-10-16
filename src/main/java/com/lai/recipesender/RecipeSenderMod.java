package com.lai.recipesender;

import com.lai.recipesender.config.RecipeSenderConfig;
import com.lai.recipesender.network.ModNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** 模组入口仅负责网络层和物理端安全的客户端初始化。 */
@Mod(RecipeSenderMod.MOD_ID)
public final class RecipeSenderMod {
    public static final String MOD_ID = "recipe_sender";

    /** 注册配置、初始化网络通道，并在客户端注册界面交互逻辑。 */
    public RecipeSenderMod(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        RecipeSenderConfig.register(context);
        ModNetwork.register();
        if (FMLEnvironment.dist.isClient()) {
            ClientBootstrap.register(context, modBus);
        }
    }
}
