package com.lai.recipesender.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「这台机器默认该挂哪些配方类别」的唯一来源。
 *
 * <p>单绑界面和批量绑定都必须走这里，不能各写一套：同一个模组里两处默认值不一样的话，
 * 玩家会以为批量绑定坏了，而实际上是规则不同。
 *
 * <p>规则（1.0.32 起单绑就是这么定的）：这台机器**登记过**的类别只有 1~2 个时取第一个；
 * 一个都没有、或者多到三个以上就不猜——猜错比不猜更烦，让玩家自己去管理面板选。
 *
 * <p>为什么不能交给服务端算：类别来自 EMI 的配方管理器，服务端没有 EMI，只有客户端才知道
 * 「这台机器能跑哪些类别」。所以只能在客户端算好，随批量绑定包一起提交。
 */
public final class MachineRoutes {

    /** 猜得动的上限：候选超过这个数就放弃，交给玩家。 */
    private static final int MAX_GUESSABLE = 2;

    private MachineRoutes() {
    }

    /**
     * 这台机器（按方块物品比）的默认类别。
     *
     * @param icon 方块物品（绑定记录里的 {@code iconItem}）
     * @return 空集合表示「不猜」，也就是不参与自动路由
     */
    public static Set<ResourceLocation> defaultFor(ItemStack icon) {
        if (icon == null || icon.isEmpty()) {
            return Set.of();
        }
        List<ResourceLocation> hits = new ArrayList<>();
        Item item = icon.getItem();
        for (RecipeCategoryCache.Entry entry : RecipeCategoryCache.entries()) {
            if (entry.usesMachine(item)) {
                hits.add(entry.id());
            }
        }
        if (hits.isEmpty() || hits.size() > MAX_GUESSABLE) {
            return Set.of();
        }
        return Set.of(hits.get(0));
    }
}
