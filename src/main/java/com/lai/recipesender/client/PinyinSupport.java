package com.lai.recipesender.client;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 中文拼音搜索入口：一个字面匹配 + 一个可选的拼音匹配。
 *
 * <p>让「组装机」同时被 {@code zzj}（首字母）、{@code zuzhuangji}（全拼）、
 * {@code zhuang}（子串）和 {@code 组装}（字面）命中。拼音能力来自玩家实例里的
 * <b>Just Enough Characters</b>（{@code jecharacters}）——它暴露了公开静态方法
 * {@code me.towdium.jecharacters.utils.Match#contains(String, CharSequence)}，
 * 字典随它自己的 jar 走。
 *
 * <p><b>为什么是纯反射</b>：本模组对全部可选模组一律反射隔离，{@code build.gradle} 里
 * 没有 JEC 的编译期依赖，所以不能直接写 {@code Match.contains(...)}。
 * {@link JechAdapter} 因此独立成类——JEC 缺席时它永远不会被加载，
 * 也就不会抛 {@code NoClassDefFoundError}（与 GTOCore {@code PinYinUtils} 的双类模式同理）。
 *
 * <p><b>降级行为</b>：没装 JEC 时 {@link #match} 只剩 {@code toLowerCase().contains}，
 * 中文名需要字面输入，英文名与坐标照常可搜，<b>不报错、不崩</b>。
 */
public final class PinyinSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");
    private static final String MOD_ID = "jecharacters";

    /** 三态：{@code null} = 还没查过。 */
    private static Boolean loaded;

    private PinyinSupport() {
    }

    /** 玩家实例里有没有 JEC。没有时 {@link #match} 只有字面匹配。 */
    public static boolean available() {
        if (loaded == null) {
            try {
                loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            } catch (Throwable throwable) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * {@code candidate} 是否被 {@code needle} 命中。
     *
     * @param candidate 被搜索的原文（容器名、方块名……），大小写不敏感
     * @param needle    搜索词，**必须已经小写**（两个调用方的搜索框都先 {@code toLowerCase}）；
     *                  空串视为命中
     */
    public static boolean match(String candidate, String needle) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        if (candidate.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return available() && JechAdapter.contains(candidate, needle);
    }

    /**
     * 与 JEC 的唯一接触点。
     *
     * <p>刻意只用一个 {@link Method} 字段，不出现任何 JEC 类型——这样本类的类加载
     * 不会牵连 JEC，查找与调用失败也只在这里被兜住。
     */
    private static final class JechAdapter {
        private static Method contains;
        private static boolean broken;

        private static boolean contains(String candidate, String needle) {
            if (broken) {
                return false;
            }
            try {
                if (contains == null) {
                    Class<?> match = Class.forName("me.towdium.jecharacters.utils.Match");
                    contains = match.getMethod("contains", String.class, CharSequence.class);
                }
                return Boolean.TRUE.equals(contains.invoke(null, candidate, needle));
            } catch (Throwable throwable) {
                // 只报一次：搜索框每敲一个字符都会调进来，反复刷日志没有意义。
                broken = true;
                LOGGER.warn("拼音搜索不可用，已降级为字面匹配：{}", throwable.toString());
                return false;
            }
        }
    }
}
