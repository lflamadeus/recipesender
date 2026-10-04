package com.lai.recipesender.client;

import com.lai.recipesender.model.BoundContainer;
import com.lai.recipesender.model.BoundStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 绑定目标的本地存活性判定：回答「这个绑定现在指向什么」。
 *
 * <p>1.0.27 新增。在此之前，方块被拆掉后管理界面完全看不出来：列表画的是服务端上次同步过的
 * 数据，而破坏方块不触发任何同步（{@code BoundBindingService.sync} 只在绑定/改名/关系/类别
 * 这些变更路径上被调用），玩家只有真正按一次发送才会拿到一条「目标方块已不存在」。
 *
 * <p><b>只判定客户端能可靠判断的三件事</b>：维度对不对、区块加载了没有、方块实体还在不在。
 * 刻意<b>不</b>判定「这个方块提不提供物品容器」——{@code RecipeSenderClient} 的绑定准入判定
 * 已经写明「客户端的能力表可能不全（某些模组只注册服务端），不能把它当成唯一依据」，
 * 拿它当失效依据会把好机器标成坏的，比不标更糟。这一项留给服务端在发送时判定。
 *
 * <p>判定结果是<b>只读的提示</b>：不联网、不改数据、不解绑，玩家看到的永远是可以自己处置的信息。
 */
final class BoundLiveness {
    private BoundLiveness() {
    }

    /**
     * 判定结果只可能是 {@link BoundStatus#OK}、{@link BoundStatus#DIMENSION_MISMATCH}、
     * {@link BoundStatus#CHUNK_UNLOADED}、{@link BoundStatus#MISSING}。
     *
     * <p>{@code OK} 的含义是「本地看不出问题」，不等于「发送一定成功」：容器满了、材料不够
     * 这些都要等服务端。所以界面只在非 OK 时才画标签。
     */
    static BoundStatus of(BoundContainer binding) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (binding == null || level == null) {
            // 还没进世界（或正在退出）：什么都不知道，就当正常，别在界面上吓人。
            return BoundStatus.OK;
        }
        if (!level.dimension().equals(binding.dimension())) {
            return BoundStatus.DIMENSION_MISMATCH;
        }
        if (!level.isLoaded(binding.pos())) {
            // 和发送时一样：只判断加载了没有，不为了看一眼去强制加载区块。
            return BoundStatus.CHUNK_UNLOADED;
        }
        // 方块实体没了就是真的没了：方块被拆掉（这里变成空气）或被换成了石头这类没有方块实体的方块。
        // 客户端能可靠判断这一条——方块状态与方块实体都是同步过来的。
        return level.getBlockEntity(binding.pos()) == null ? BoundStatus.MISSING : BoundStatus.OK;
    }
}
