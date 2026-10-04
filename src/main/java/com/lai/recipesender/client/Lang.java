package com.lai.recipesender.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 语言文件取值与兜底文案。
 *
 * <p>以前每个需要兜底的提示都自己写一遍「取完文本再判断返回的是不是 key 本身」，
 * 这段判断在 {@code RecipeSenderClient} 里重复了六份：只要有一处写漏，那条提示在语言文件
 * 缺条目时就会把 {@code text.recipe_sender.xxx} 这种内部键名直接糊到玩家脸上。
 * 收在这里之后只有一个实现。
 *
 * <p>兜底为什么必须留：整合包会替换语言文件，模组自带的条目可能被删掉、也可能被改坏模板。
 * 宁可在聊天栏看到一句中文兜底，也不要看到内部键名。
 */
final class Lang {

    /** 语言文件缺条目时 {@link I18n#get} 原样返回 key；模板参数对不上时返回带这个前缀的文本。 */
    private static final String FORMAT_ERROR = "Format error:";

    private Lang() {
    }

    /**
     * 取语言文本；缺失条目或模板参数对不上时退回兜底文案。
     *
     * @param key      语言键
     * @param fallback 兜底文案
     * @param args     传给语言模板的参数；没有参数时留空
     */
    static String text(String key, String fallback, Object... args) {
        String text = args.length == 0 ? I18n.get(key) : I18n.get(key, args);
        if (text.equals(key) || text.startsWith(FORMAT_ERROR)) {
            return fallback;
        }
        return text;
    }

    /** 同 {@link #text}，但返回可直接发给玩家的 {@link Component}。 */
    static Component message(String key, String fallback, Object... args) {
        return Component.literal(text(key, fallback, args));
    }
}
