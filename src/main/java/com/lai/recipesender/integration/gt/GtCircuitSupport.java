package com.lai.recipesender.integration.gt;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 格雷科技（GTCEu / GTO）编程电路的兼容层。
 *
 * <p>模组不把 GT 作为编译依赖，全部通过反射访问，未安装 GT 时所有方法都安全退化为“不支持”。
 *
 * <h2>设计原则：不绑类名，只认结构</h2>
 * 1.0.4 / 1.0.5 都栽在同一件事上——把某个类名或方法名当成固定事实，一旦对方版本挪了包、
 * 改了签名，整个功能就静默失效。现在改成：
 * <ul>
 *   <li><b>读电路</b>：优先用 {@code IntCircuitBehaviour}（若该类存在）；不存在时退回
 *       “注册名 {@code programmed_circuit} + NBT 键 {@code Configuration}”，完全不依赖 GT 的类。</li>
 *   <li><b>写电路</b>：不检查机器是否实现 {@code IHasCircuitSlot}，改成鸭子类型——
 *       只要机器（或其父类）有无参方法 {@code getCircuitInventory()}，就按它返回的东西挑一条通路
 *       写第 0 格：Forge 的 {@code IItemHandlerModifiable} 直接 {@code setStackInSlot}；
 *       26.10.34 起 GT 把机器电路槽换成了 AE 键存储（{@code KeyInventory}，返回值是
 *       {@code NotifiableInventory}），则走 GT 自己电路界面用的 {@code Circuits.get/set}。
 *       <b>不能只认 Forge 那一套</b>：新版 GT 上那样会“找得到电路槽、却永远写不进去”，
 *       而且失败是静默的（1.0.39 的实际现象：材料送进去了、电路没动）。
 *       {@code isCircuitSlotEnabled()} 存在时用它把关（蒸汽总线之类靠它关掉），不存在则视为可用。</li>
 *   <li><b>找机器</b>：不检查菜单是不是 {@code ModularUIContainer}，改成鸭子类型——
 *       先找无参方法 {@code getModularUI()}，找不到再找类型名含 {@code ModularUI} 的字段；
 *       拿到 ModularUI 后读 {@code holder} 字段（找不到就找类型名含 {@code IUIHolder} 的字段）。</li>
 * </ul>
 *
 * <h2>为什么不能只看 EMI 的原料列表</h2>
 * GTO 的 {@code GTEMIRecipe} 把 {@code getFlatWidgetCollection} 覆写成返回空列表，
 * 于是 {@code ModularEmiRecipe} 构造器不会从界面槽位里收集原料；它改成在 {@code getInputs()} 里
 * 懒初始化，并且**顺带**把零概率输入补进 {@code catalysts}，所以必须先调 {@code getInputs()}。
 * 更根本的是：{@code IntCircuitIngredient} 继承 {@code StrictNBTIngredient}，它的 {@code values}
 * 数组是空的（{@code AbstractIngredient} 传的是空流），而 GTO 构造 EMI 原料时要遍历
 * {@code values}，结果是直接返回 {@code EmiStack.EMPTY}。也就是说电路在配方界面上看得见
 * （界面槽位单独走 {@code getXEIIngredients()}），却根本不会出现在原料列表里。
 * 因此除了扫 EMI 列表，还必须有一条直接翻配方内部数据的通路（{@link #scanGtRecipe}）。
 *
 * <p>26.10.34 起这一代又换了表示：配方输入成了 {@code ContentList}（{@code KeyIngredient[]}
 * 平行数组，既不是 Map 也不是 Iterable），电路是其中的 {@code CircuitIngredient}。
 * {@link #scanGtRecipe} 必须同时认这两代结构，否则「格雷配方且不使用电路」会整条判不出来
 * （材料照发、电路不清）。
 *
 * <h2>为什么“配方不需要电路”时要把电路置空</h2>
 * 格雷的配方匹配是按键匹配的：配方侧把 {@code IntCircuitIngredient} 变成搜索表里的一个键，
 * 机器侧再把电路槽里的编号加成同一个键。不需要电路的配方根本不产生电路键，于是槽里放着
 * 电路 5 时，它和要求电路 5 的配方会同时成为候选，跑哪一个取决于搜索顺序——这就是
 * “材料送进去了、跑的却是另一个配方”的原因。把电路槽清空（空槽与电路 0 等价，见
 * {@link #clearCircuit}）能让那些要求具体编号的配方不再匹配。
 *
 * <p>判定只看两件事：悬停的确实是格雷配方（认出了配方对象）、且它不使用电路，就把目标容器的
 * 电路清空。目标没有电路槽（原版箱子等）时自然什么都不会发生——这是服务端按机器实例判断的结果，
 * 不需要客户端预判机型，机器支持什么配方由 GT 自己决定。
 *
 * <h2>调用频率</h2>
 * {@link #findCircuit} 只在玩家按下按键开始选择配方时调用一次，{@link #findCircuitHolder}、
 * {@link #canAdjustCircuit} 也只在开始选择与提交时各调用一次，都不是每 tick 的路径。其中唯一有点
 * 开销的是 {@code recipe.getInputs()}，但 GTO 与材料统计本来就要用它，且配方自己带懒初始化，
 * 重复调用不会再算一遍。
 */
public final class GtCircuitSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    /** 电路编号上限，对应 {@code IntCircuitBehaviour.CIRCUIT_MAX}。 */
    public static final int MAX_CIRCUIT = 32;
    /** 表示“没有电路”的返回值，与合法的电路编号 0 区分开。 */
    public static final int NO_CIRCUIT = -1;

    /** 编程电路物品，GTCEu / GTO 都在 {@code gtceu} 命名空间下。 */
    private static final ResourceLocation CIRCUIT_ITEM_ID =
            ResourceLocation.fromNamespaceAndPath("gtceu", "programmed_circuit");
    /** 注册名兜底匹配用的路径（命名空间可能被整合包改过）。 */
    private static final String CIRCUIT_ITEM_PATH = "programmed_circuit";
    /** 电路编号在 NBT 上的键名，先试 GT 官方写法，再试小写。 */
    private static final String[] CIRCUIT_NBT_KEYS = {"Configuration", "configuration"};

    /** 电路行为类的候选名字，不同版本可能挪过包。 */
    private static final String[] CIRCUIT_BEHAVIOUR_CLASSES = {
            "com.gregtechceu.gtceu.common.item.IntCircuitBehaviour",
            "com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitBehaviour",
    };

    /** 26.10.34 起机器电路槽改成的 AE 键存储类型（{@code NotifiableInventory} 的真正存储）。 */
    private static final String KEY_INVENTORY_CLASS = "com.gregtechceu.gtceu.api.transfer.key.KeyInventory";
    /** AE 键电路槽的官方读写入口，GT 自己的电路界面（GhostCircuitSlotWidget）也用它。 */
    private static final String CIRCUITS_CLASS = "com.gregtechceu.gtceu.api.recipe.content.Circuits";
    /** {@code NotifiableInventory} 里装着真正键存储的字段名。 */
    private static final String KEY_STORAGE_FIELD = "storage";

    /** GT 配方对象里可能装着输入原料的字段名，GTCEu 与 GTO 各用一套。 */
    private static final String[] RECIPE_CONTENT_FIELDS = {
            "itemInputs", "inputs", "tickInputs", "inputItems", "itemInput", "input"};
    /** 包装对象（Content / ItemIngredient）里指向真正原料的字段名。 */
    private static final String[] WRAPPER_FIELDS = {"inner", "content", "value", "ingredient"};
    /** 包装层数上限：Content -> ItemIngredient -> Ingredient 已经够用。 */
    private static final int MAX_WRAPPER_DEPTH = 4;
    /** gtceu 26.10.34 起的 {@code ContentList}：对外只有 {@code size()} 与 {@code ingredient(int)}。 */
    private static final String CONTENT_LIST_SIZE = "size";
    private static final String CONTENT_LIST_ELEMENT = "ingredient";

    /** {@code resolve()} 期间发现的问题，只在解析完成后打一次日志。 */
    private static final List<String> problems = new ArrayList<>();
    /** 每个机器类的 {@code getCircuitInventory()} 查找结果缓存。 */
    private static final Map<Class<?>, Optional<Method>> CIRCUIT_INVENTORY_METHODS = new ConcurrentHashMap<>();

    private static boolean resolved;
    private static Item circuitItem;
    private static Method isIntegratedCircuitMethod;
    private static Method getCircuitConfigurationMethod;
    private static Method circuitStackMethod;
    /** AE 键电路槽通路（26.10.34 起）用到的类型与方法；老版本解析不到时为 {@code null}。 */
    private static Class<?> keyInventoryClass;
    private static Method keyAtMethod;
    private static Method amountAtMethod;
    private static Method circuitsGetMethod;
    private static Method circuitsSetMethod;
    /** 已经提醒过“有电路槽、但没有可用写入通路”的机器类，避免刷屏。 */
    private static final Set<String> UNWRITABLE_REPORTED = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // 配方对电路的要求
    // ------------------------------------------------------------------

    /**
     * 悬停配方对编程电路的要求。
     *
     * @param circuit    0-{@link #MAX_CIRCUIT} 表示配方要求该编号；{@link #NO_CIRCUIT}
     *                   表示这是格雷配方但不使用电路，目标容器有电路槽时应把电路置空
     * @param gregRecipe 是否确认这是格雷配方（认出了配方对象）。只有它为 {@code true} 时
     *                   {@link #NO_CIRCUIT} 才意味着“该置空”；只看 EMI 原料的通路认不出配方对象，
     *                   为 {@code false}，那种情况只用于“按编号写电路”
     */
    public record CircuitRequirement(int circuit, boolean gregRecipe) {

        /** 配方是否要求一个具体的电路编号；否则就是“不使用电路”。 */
        public boolean requiresCircuit() {
            return circuit >= 0;
        }
    }

    private GtCircuitSupport() {
    }

    // ------------------------------------------------------------------
    // 读 / 写电路物品
    // ------------------------------------------------------------------

    /**
     * 读取一个物品堆携带的电路编号。
     *
     * @return 电路编号（0-32）；不是编程电路时返回 {@link #NO_CIRCUIT}
     */
    public static int readCircuit(ItemStack stack) {
        resolve();
        if (stack == null || stack.isEmpty()) {
            return NO_CIRCUIT;
        }
        // 优先走 GT 自己的判定，最准确。
        if (isIntegratedCircuitMethod != null && getCircuitConfigurationMethod != null) {
            try {
                if (Boolean.TRUE.equals(isIntegratedCircuitMethod.invoke(null, stack))) {
                    Object configuration = getCircuitConfigurationMethod.invoke(null, stack);
                    if (configuration instanceof Integer value && value >= 0 && value <= MAX_CIRCUIT) {
                        return value;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 落到下面的注册名判定
            }
        }
        // 兜底：认注册名 + NBT，不依赖 GT 的类。
        if (circuitItem != null && stack.is(circuitItem)) {
            return readCircuitTag(stack);
        }
        return NO_CIRCUIT;
    }

    /** 从编程电路物品的 NBT 里读编号；没有 NBT 就是电路 0（与 GT 的行为一致）。 */
    private static int readCircuitTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return 0;
        }
        for (String key : CIRCUIT_NBT_KEYS) {
            if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
                int value = tag.getInt(key);
                if (value >= 0 && value <= MAX_CIRCUIT) {
                    return value;
                }
            }
        }
        return 0;
    }

    /** 构造一个指定编号的编程电路物品堆。 */
    private static ItemStack createCircuitStack(int circuit) {
        resolve();
        if (circuitStackMethod != null) {
            try {
                Object stack = circuitStackMethod.invoke(null, circuit);
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    return itemStack;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 落到下面的手工构造
            }
        }
        if (circuitItem == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(circuitItem);
        if (circuit != 0) {
            stack.getOrCreateTag().putInt(CIRCUIT_NBT_KEYS[0], circuit);
        }
        return stack;
    }

    // ------------------------------------------------------------------
    // 从配方里找电路
    // ------------------------------------------------------------------

    /**
     * 从 EMI 配方里找出它对编程电路的要求。
     *
     * <p>两条通路依次尝试：
     * <ol>
     *   <li>EMI 的催化剂 / 输入列表——对 GTCEu 原生配方有效；</li>
     *   <li>直接翻配方对象里的 GT 配方数据——对 GTO 配方是唯一可行的通路。</li>
     * </ol>
     *
     * @return 配方对电路的要求；不是格雷配方、未安装格雷科技或识别失败时返回 {@code null}，
     *         调用方不要改动任何电路
     */
    public static CircuitRequirement findCircuit(EmiRecipe recipe) {
        if (recipe == null) {
            return null;
        }
        resolve();
        if (circuitItem == null) {
            return null;
        }
        try {
            return detectCircuit(recipe);
        } catch (RuntimeException | LinkageError error) {
            // 反射翻配方内部数据有可能撞上第三方实现的意外结构，绝不能让它冒泡打断按键处理。
            LOGGER.warn("检测配方电路时出错，本次按“不动电路”处理", error);
            return null;
        }
    }

    /** {@link #findCircuit} 的实际实现，异常由调用方兜住。 */
    private static CircuitRequirement detectCircuit(EmiRecipe recipe) {
        // 顺序不能反：GTO 是在 getInputs() 里才把零概率输入补进 catalysts 的。
        List<EmiIngredient> inputs = inputsOf(recipe);
        int circuit = scanIngredients(catalystsOf(recipe));
        if (circuit == NO_CIRCUIT) {
            circuit = scanIngredients(inputs);
        }
        if (circuit != NO_CIRCUIT) {
            // 这条通路只看得见原料，认不出配方对象，所以只用于“按编号写电路”。
            return new CircuitRequirement(circuit, false);
        }
        return scanGtRecipe(recipe);
    }

    // ------------------------------------------------------------------
    // 通路一：EMI 原料列表
    // ------------------------------------------------------------------

    /** 扫描一批 EMI 原料，返回第一个编程电路的编号。 */
    private static int scanIngredients(List<EmiIngredient> ingredients) {
        if (ingredients == null) {
            return NO_CIRCUIT;
        }
        for (EmiIngredient ingredient : ingredients) {
            if (ingredient == null) {
                continue;
            }
            List<EmiStack> stacks;
            try {
                stacks = ingredient.getEmiStacks();
            } catch (RuntimeException | LinkageError error) {
                continue;
            }
            for (EmiStack emiStack : stacks) {
                int circuit = readCircuit(emiStack.getItemStack());
                if (circuit != NO_CIRCUIT) {
                    return circuit;
                }
            }
        }
        return NO_CIRCUIT;
    }

    /** 读取配方催化剂；个别配方实现可能抛异常，此时视为没有催化剂。 */
    private static List<EmiIngredient> catalystsOf(EmiRecipe recipe) {
        try {
            return recipe.getCatalysts();
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    /** 读取配方输入；个别配方实现可能抛异常，此时视为没有输入。 */
    private static List<EmiIngredient> inputsOf(EmiRecipe recipe) {
        try {
            return recipe.getInputs();
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 通路二：直接翻 GT 配方内部数据
    // ------------------------------------------------------------------

    /**
     * 在 EMI 配方对象里找到底层 GT 配方，再从它的输入内容里找电路。
     *
     * <p>GTCEu 的 {@code GTEmiRecipe} 持有 {@code GTRecipe recipe}（{@code inputs} 是
     * {@code Map<RecipeCapability, List<Content>>}，{@code Content.content} 是 {@code Object}）；
     * GTO 的 {@code GTEMIRecipe} 持有 {@code GTRecipeDefinition recipe}，它的 {@code itemInputs}
     * 在 26.10.34 之前是 {@code List<Content>}、之后换成了 {@code ContentList}。
     * 两条都按“字段名 + 递归解包”处理，不依赖具体类名；容器的形态差异由
     * {@link #contentElements} 统一摊平。
     *
     * @return 配方对电路的要求；连 GT 配方对象或它的输入字段都找不到时返回 {@code null}
     *         —— 结构不认识时宁可不动电路，也不能误判成“这个配方不使用电路”
     */
    private static CircuitRequirement scanGtRecipe(EmiRecipe recipe) {
        Object gtRecipe = findGtRecipeObject(recipe);
        if (gtRecipe == null) {
            return null;
        }
        boolean scanned = false;
        for (String fieldName : RECIPE_CONTENT_FIELDS) {
            List<?> elements = contentElements(readFieldByName(gtRecipe, fieldName));
            if (elements == null) {
                continue;
            }
            scanned = true;
            int circuit = scanElements(elements);
            if (circuit != NO_CIRCUIT) {
                return new CircuitRequirement(circuit, true);
            }
        }
        if (!scanned) {
            return null;
        }
        // 输入字段都在、里面确实没有电路，才能判定“这个配方不使用电路”。
        return new CircuitRequirement(NO_CIRCUIT, true);
    }

    /**
     * 读出这条 EMI 配方所属的 GT 机器类型注册名（如 {@code gtceu:assembler}），用于自动路由。
     *
     * <p>跟电路一样走结构而不是类名：GTCEu 26.x 的配方定义里有 {@code recipeType} 字段，
     * 类型是 {@code GTRecipeType}，注册名在它的 {@code registryName} 字段上。取不到就返回
     * {@code null}，调用方会退回 EMI 类别 id —— 那条路零反射，永远可用。
     *
     * @param recipe 可能是 {@link EmiRecipe}，也可能已经是 GT 配方对象；null 或认不出来都返回 null
     */
    public static ResourceLocation findRecipeTypeKey(Object recipe) {
        if (recipe == null) {
            return null;
        }
        Object gtRecipe = findGtRecipeObject(recipe);
        if (gtRecipe == null) {
            return null;
        }
        Object recipeType = readFieldByName(gtRecipe, "recipeType");
        if (recipeType == null) {
            return null;
        }
        // 注册名可能叫 registryName（GTCEu 26.x），也可能只暴露了 getter。
        ResourceLocation direct = asResourceLocation(readFieldByName(recipeType, "registryName"));
        if (direct != null) {
            return direct;
        }
        direct = asResourceLocation(invokePublicNoArg(recipeType, "getRegistryName"));
        if (direct != null) {
            return direct;
        }
        return asResourceLocation(invokePublicNoArg(recipeType, "getId"));
    }

    /** 把反射拿到的值收拾成 ResourceLocation：本来就是就直接用，是字符串就解析，其它一律 null。 */
    private static ResourceLocation asResourceLocation(Object value) {
        if (value instanceof ResourceLocation location) {
            return location;
        }
        if (value instanceof String text && !text.isEmpty()) {
            return ResourceLocation.tryParse(text);
        }
        return null;
    }

    /** 找到 EmiRecipe 持有的 GT 配方对象：字段类型名里带 “GTRecipe” 的那个。 */
    private static Object findGtRecipeObject(Object owner) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (RuntimeException | LinkageError error) {
                continue;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                if (field.getType().getName().contains("GTRecipe")) {
                    Object value = readField(field, owner);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把一个「配方内容容器」摊成元素列表；不是内容容器时返回 {@code null}。
     *
     * <p>三种形态都要认，否则「这个配方到底有没有电路」会整条判不出来：
     * <ul>
     *   <li>{@code Map<RecipeCapability, List<Content>>}（gtceu 26.10.34 之前）：取 values，
     *       每个 value 还是一个列表，由 {@link #scanElement} 的 Iterable 分支再摊一层；</li>
     *   <li>裸 {@code Iterable}：直接当元素列表；</li>
     *   <li>{@code ContentList}（gtceu 26.10.34 起）：<b>既不是 Map 也不是 Iterable</b>，
     *       内部是 {@code KeyIngredient[]} 平行数组，只能走 {@code size()} / {@code ingredient(int)}。
     *       漏掉这一代时 {@link #scanGtRecipe} 会认为「结构不认识」而返回 {@code null}，
     *       于是「格雷配方且不使用电路」永远判不出来——表现就是材料能发进去、
     *       机器上的旧电路却清不掉。</li>
     * </ul>
     */
    private static List<?> contentElements(Object container) {
        if (container instanceof Map<?, ?> map) {
            return new ArrayList<>(map.values());
        }
        if (container instanceof Iterable<?> iterable) {
            List<Object> elements = new ArrayList<>();
            for (Object element : iterable) {
                elements.add(element);
            }
            return elements;
        }
        return contentListElements(container);
    }

    /**
     * {@code ContentList} 的元素；不是这种结构时返回 {@code null}。
     *
     * <p>认它靠 {@code ingredient(int)} 这个方法：只看 {@code size()} 会把任何碰巧有
     * {@code size()} 的对象当成空内容容器，从而把「读不出来」误判成「配方里没有电路」。
     */
    private static List<?> contentListElements(Object container) {
        if (container == null) {
            return null;
        }
        Method elementAt = findIntMethod(container.getClass(), CONTENT_LIST_ELEMENT);
        Object size = invokeNoArg(container, CONTENT_LIST_SIZE);
        if (elementAt == null || !(size instanceof Integer count)) {
            return null;
        }
        List<Object> elements = new ArrayList<>(Math.max(0, count));
        for (int index = 0; index < count; index++) {
            try {
                Object element = elementAt.invoke(container, index);
                if (element != null) {
                    elements.add(element);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                // 读一半读不下去：已经拿到的照样返回，别把整条通路判死。
                break;
            }
        }
        return elements;
    }

    private static int scanElements(Iterable<?> elements) {
        for (Object element : elements) {
            int circuit = scanElement(element, 0);
            if (circuit != NO_CIRCUIT) {
                return circuit;
            }
        }
        return NO_CIRCUIT;
    }

    /** 逐层解包 Content / ItemIngredient 这类包装，直到遇见真正的 Ingredient。 */
    private static int scanElement(Object element, int depth) {
        if (element == null || depth > MAX_WRAPPER_DEPTH) {
            return NO_CIRCUIT;
        }
        // 老结构的 Map 值是列表，摊平后再往下走。
        if (element instanceof Iterable<?> iterable) {
            return scanElements(iterable);
        }
        // gtceu 26.10.34 起的 KeyIngredient：电路编号直接挂在元素上，不需要再解包。
        int keyCircuit = readCircuitFromKeyIngredient(element);
        if (keyCircuit != NO_CIRCUIT) {
            return keyCircuit;
        }
        if (element instanceof Ingredient ingredient) {
            return readCircuitFromIngredient(ingredient);
        }
        for (String name : WRAPPER_FIELDS) {
            Field field = findField(element.getClass(), name);
            if (field == null || !shouldDescend(field)) {
                continue;
            }
            Object nested = readField(field, element);
            if (nested == null || nested == element) {
                continue;
            }
            int circuit = scanElement(nested, depth + 1);
            if (circuit != NO_CIRCUIT) {
                return circuit;
            }
        }
        return NO_CIRCUIT;
    }

    /**
     * 从 {@code IntCircuitIngredient} 里读出编号。
     * 优先读私有的 {@code configuration} 字段（最准确），读不到再退回 {@code getItems()}。
     */
    private static int readCircuitFromIngredient(Ingredient ingredient) {
        if (ingredient.getClass().getName().contains("IntCircuitIngredient")) {
            Object configuration = readFieldByName(ingredient, "configuration");
            if (configuration instanceof Integer value && value >= 0 && value <= MAX_CIRCUIT) {
                return value;
            }
        }
        ItemStack[] items;
        try {
            items = ingredient.getItems();
        } catch (RuntimeException | LinkageError error) {
            return NO_CIRCUIT;
        }
        if (items == null) {
            return NO_CIRCUIT;
        }
        for (ItemStack stack : items) {
            int circuit = readCircuit(stack);
            if (circuit != NO_CIRCUIT) {
                return circuit;
            }
        }
        return NO_CIRCUIT;
    }

    /**
     * 从 gtceu 26.10.34 起的 {@code KeyIngredient} 里读电路编号。
     *
     * <p>这一代把电路表示成 {@code CircuitIngredient}，编号走接口的
     * {@code circuitConfiguration()}：默认实现返回 -1，只有电路实现返回真实编号，
     * 所以非电路原料会自然落到后面的通路，不必再判 {@code kind() == CIRCUIT}。
     *
     * <p>必须按<b>公开方法表</b>找（{@link #invokePublicNoArg}）：它是接口上的 default 方法，
     * 只查实现类的 declared method 会漏掉那些没有覆写它的实现。
     */
    private static int readCircuitFromKeyIngredient(Object element) {
        Object configuration = invokePublicNoArg(element, "circuitConfiguration");
        if (configuration instanceof Integer value && value >= 0 && value <= MAX_CIRCUIT) {
            return value;
        }
        return NO_CIRCUIT;
    }

    /** 只往“可能是原料”的字段里钻，避免顺着无关引用走到天涯海角。 */
    private static boolean shouldDescend(Field field) {
        Class<?> type = field.getType();
        if (type == Object.class || Ingredient.class.isAssignableFrom(type)) {
            return true;
        }
        String name = type.getName();
        return name.contains("Ingredient") || name.contains("Content");
    }

    // ------------------------------------------------------------------
    // 机器定位与电路写入（鸭子类型）
    // ------------------------------------------------------------------

    /**
     * 该菜单是不是 LDLib 的 ModularUI 界面（原版箱子、漏斗等返回 {@code false}）。
     *
     * <p>用来在客户端提前过滤掉“肯定写不进去”的目标，省掉一次无意义的数据包。
     * 只看菜单有没有 {@code getModularUI()} 方法：菜单类型在客户端与服务端是一致的，
     * 这个判断两边必然得出相同结果。刻意**不**在客户端判断
     * {@code isCircuitSlotEnabled()}——那个值会随多方块成型状态变化（GT 在部件加入/离开
     * 控制器时会改它），客户端可能滞后，交给服务端判断才可靠。
     */
    public static boolean isModularUiContainer(AbstractContainerMenu menu) {
        return menu != null && findNoArgMethod(menu.getClass(), "getModularUI") != null;
    }

    /**
     * 取出当前菜单对应的机器实例，用于判断它是否支持电路调整。
     *
     * <p>非 LDLib 界面（原版箱子等）返回 {@code null}。
     */
    public static Object findCircuitHolder(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        Object modularUI = invokeNoArg(menu, "getModularUI");
        if (modularUI == null) {
            modularUI = readFieldByTypeName(menu, "ModularUI");
        }
        if (modularUI == null) {
            return null;
        }
        Object holder = readFieldByName(modularUI, "holder");
        if (holder == null) {
            holder = readFieldByTypeName(modularUI, "IUIHolder");
        }
        return holder;
    }

    /**
     * 从远程方块实体上找出可调电路的机器实例（<b>方块实体通路</b>）。
     *
     * <p>与 {@link #findCircuitHolder(AbstractContainerMenu)} 的区别只在入口：菜单通路要求玩家正开着
     * 那台机器的界面，而「发送到已绑定容器」的落点在远程方块上，玩家不会开着它的界面。拿到机器
     * 实例之后的鸭子类型判定（{@link #canAdjustCircuit}）与读写完全复用同一套。
     *
     * <p>两条来源：
     * <ol>
     *   <li>方块实体自己就是机器——单方块机器，或者自己就带电路槽的多方块部件
     *       （GTCEu 的输入总线 {@code ItemBusPartMachine} 就自带 {@code getCircuitInventory()}）；</li>
     *   <li>方块实体是多方块部件、电路却在控制器上（GTO 的多方块走这条）：顺着 {@code IMultiPart}
     *       的 {@code getControllers()} 找控制器，再取 {@code self()}。</li>
     * </ol>
     *
     * <p>返回列表而不是单个实例：一个部件理论上可以同时挂在多个已成型控制器上，每台都得调。
     *
     * @return 可调电路的机器实例；方块实体不是格雷机器、或拿不到带电路槽的实例时返回空列表，
     *         调用方应原样跳过（取不到不是错误——绑定目标本来就可能是普通箱子）
     */
    public static List<Object> findCircuitHoldersAt(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return List.of();
        }
        Object machine = invokePublicNoArg(blockEntity, "getMetaMachine");
        if (machine == null) {
            return List.of();
        }
        if (canAdjustCircuit(machine)) {
            return List.of(machine);
        }
        // 部件自己不装电路时，电路在控制器上。不是部件的话 getControllers() 不存在，循环直接空转。
        List<Object> holders = new ArrayList<>();
        Object controllers = invokePublicNoArg(machine, "getControllers");
        if (controllers instanceof Iterable<?> iterable) {
            for (Object controller : iterable) {
                if (controller == null) {
                    continue;
                }
                Object candidate = invokePublicNoArg(controller, "self");
                if (candidate == null) {
                    candidate = controller;
                }
                if (canAdjustCircuit(candidate) && !containsSame(holders, candidate)) {
                    holders.add(candidate);
                }
            }
        }
        return holders;
    }

    /** 按引用判重：同一台控制器可能被部件返回多次。 */
    private static boolean containsSame(List<Object> holders, Object candidate) {
        for (Object holder : holders) {
            if (holder == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断该机器当前是否提供<b>可调整</b>的电路槽。
     *
     * <p>有无参方法 {@code getCircuitInventory()} 只是前提；{@code isCircuitSlotEnabled()} 存在时
     * 还要它没把关掉，并且它的返回值得是我们认得出来、写得进去的那两种形态之一
     * （Forge 物品容器 / AE 键存储）。只认前一种会在 gtceu 26.10.34 上得出“有电路槽、却写不进”
     * 的错误结论，而写入失败是静默的——那正是 1.0.39 “材料进去了、电路没动”的成因。
     */
    public static boolean canAdjustCircuit(Object holder) {
        resolve();
        if (holder == null || circuitInventoryMethod(holder) == null) {
            return false;
        }
        Object enabled = invokeNoArg(holder, "isCircuitSlotEnabled");
        if (enabled != null && !Boolean.TRUE.equals(enabled)) {
            return false;
        }
        // 有电路槽还不够：得有一条我们认得出来的写入通路，否则只是“看得见、写不进”。
        if (itemCircuitInventory(holder) != null || keyCircuitInventory(holder) != null) {
            return true;
        }
        // 落到这里就是“机器有电路槽、却没有可用写入通路”——1.0.39 之前这类失败完全静默，
        // 只能靠翻存档才发现。按机器类提醒一次，用户直接把日志发过来就够了。
        if (UNWRITABLE_REPORTED.add(holder.getClass().getName())) {
            Object inventory = circuitInventory(holder);
            LOGGER.warn("[电路] {} 有电路槽，但它的存储类型（{}）没有可用的写入通路，电路调整会静默失效；"
                            + "请把这条日志反馈给作者",
                    holder.getClass().getSimpleName(),
                    inventory == null ? "null" : inventory.getClass().getName());
        }
        return false;
    }

    /**
     * 读取这台<b>方块实体</b>上第一处可调整电路槽的当前编号。
     *
     * <p>与 {@link #findCircuitHoldersAt} 配套：多方块部件自己的电路在控制器上，这里返回的也是
     * 控制器上的那一格。用于「管理界面显示每台机器的电路」「整体模式的电路一致性判定」这类只读场景。
     *
     * @return 当前编号（空槽按 GT 的语义视为 0）；方块实体不是格雷机器、或没有任何可调整的电路槽时
     *         返回 {@code null}——「没有电路槽」与「电路是 0」是两件事，调用方必须分得清
     */
    public static Integer currentCircuit(BlockEntity blockEntity) {
        for (Object holder : findCircuitHoldersAt(blockEntity)) {
            if (canAdjustCircuit(holder)) {
                return readCurrentCircuit(holder);
            }
        }
        return null;
    }

    /**
     * 读取机器电路槽当前的编号。
     *
     * @return 当前编号；空槽按 GT 的语义视为 0；槽里有非电路物品或读不到时返回
     *         {@link #NO_CIRCUIT}，调用方应照常写入而不是跳过
     */
    public static int readCurrentCircuit(Object holder) {
        resolve();
        IItemHandlerModifiable handler = itemCircuitInventory(holder);
        if (handler != null) {
            ItemStack current = handler.getStackInSlot(0);
            if (current == null || current.isEmpty()) {
                return 0;
            }
            return readCircuit(current);
        }
        Object keyInventory = keyCircuitInventory(holder);
        if (keyInventory != null) {
            return readKeyCircuit(keyInventory);
        }
        return NO_CIRCUIT;
    }

    /**
     * 把机器电路槽设置为指定编号，写入方式与 GT 界面完全一致（第 0 格）。
     *
     * @return 是否真的写入了
     */
    public static boolean setCircuit(Object holder, int circuit) {
        if (circuit < 0 || circuit > MAX_CIRCUIT || !canAdjustCircuit(holder)) {
            return false;
        }
        IItemHandlerModifiable handler = itemCircuitInventory(holder);
        if (handler != null) {
            ItemStack configured = createCircuitStack(circuit);
            if (configured.isEmpty()) {
                return false;
            }
            handler.setStackInSlot(0, configured);
            return true;
        }
        Object keyInventory = keyCircuitInventory(holder);
        if (keyInventory != null && writeKeyCircuit(keyInventory, circuit)) {
            return true;
        }
        Object inventory = circuitInventory(holder);
        LOGGER.warn("[电路] {} 的电路槽（{}）没有可用的写入通路，电路 {} 没能生效；请把这条日志反馈给作者",
                holder.getClass().getSimpleName(),
                inventory == null ? "null" : inventory.getClass().getName(),
                circuit);
        return false;
    }

    /**
     * 清空机器电路槽（第 0 格写入空物品堆）。
     *
     * <p>与 {@link #setCircuit}(holder, 0) 的区别只在观感：GT 的电路 0 也是一块真实的编程电路物品，
     * 留在槽里玩家会以为没生效。对配方匹配两者完全等价（没有任何配方要求电路 0），而空槽更贴近
     * “从没设过电路”的原始状态。
     *
     * <p>槽里放着不是编程电路的东西时不动它：那是玩家自己的物品，不该由“发送配方”代删。
     *
     * @return 是否真的清空了（槽本来就空也算成功）
     */
    public static boolean clearCircuit(Object holder) {
        if (!canAdjustCircuit(holder)) {
            return false;
        }
        IItemHandlerModifiable handler = itemCircuitInventory(holder);
        if (handler != null) {
            ItemStack current = handler.getStackInSlot(0);
            if (current != null && !current.isEmpty() && readCircuit(current) == NO_CIRCUIT) {
                return false;
            }
            handler.setStackInSlot(0, ItemStack.EMPTY);
            return true;
        }
        Object keyInventory = keyCircuitInventory(holder);
        if (keyInventory != null) {
            // 槽里放着非编程电路的东西就别代玩家删。
            if (keyCircuitAmount(keyInventory) > 0 && readKeyCircuit(keyInventory) == NO_CIRCUIT) {
                return false;
            }
            return writeKeyCircuit(keyInventory, NO_CIRCUIT);
        }
        return false;
    }

    /** 取机器的电路槽物品容器。 */
    private static Object circuitInventory(Object holder) {
        Method method = circuitInventoryMethod(holder);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    /**
     * 取电路槽的 Forge 物品容器（gtceu 26.10.34 之前的形态）。
     *
     * @return 可写物品容器；电路槽不是物品容器、或格数为 0 时返回 {@code null}
     */
    private static IItemHandlerModifiable itemCircuitInventory(Object holder) {
        Object inventory = circuitInventory(holder);
        if (inventory instanceof IItemHandlerModifiable handler && handler.getSlots() >= 1) {
            return handler;
        }
        return null;
    }

    /**
     * 取电路槽的 AE 键存储（gtceu 26.10.34 起）。
     *
     * <p>那一版把机器电路槽换成了 {@code NotifiableInventory<AEItemKey>}：它不再实现
     * {@code IItemHandlerModifiable}，真正的存储是它内部字段 {@code storage}（{@code KeyInventory}），
     * 官方读写入口是 GT 自己电路界面用的 {@code Circuits.get/set}。
     *
     * @return 可读写的键存储；该版本没有这套机制、或拿不到时返回 {@code null}
     */
    private static Object keyCircuitInventory(Object holder) {
        if (keyInventoryClass == null || keyAtMethod == null || amountAtMethod == null
                || circuitsGetMethod == null || circuitsSetMethod == null) {
            return null;
        }
        Object inventory = circuitInventory(holder);
        if (inventory == null) {
            return null;
        }
        if (keyInventoryClass.isInstance(inventory)) {
            return inventory;
        }
        Object storage = readFieldByName(inventory, KEY_STORAGE_FIELD);
        return keyInventoryClass.isInstance(storage) ? storage : null;
    }

    /**
     * 读 AE 键电路槽的编号。
     *
     * <p>不能只看 {@code Circuits.get}：空槽它也返回 -1，与“槽里放着别的东西”混在一起，
     * 所以先用 {@code amountAt(0)} 把空槽认出来当 0（与 Forge 通路的口径保持一致）。
     *
     * @return 编号；空槽 0；槽里是非电路键或读不到时 {@link #NO_CIRCUIT}
     */
    private static int readKeyCircuit(Object keyInventory) {
        if (keyCircuitAmount(keyInventory) <= 0) {
            return 0;
        }
        try {
            Object configuration = circuitsGetMethod.invoke(null, keyInventory, 0);
            if (configuration instanceof Integer value && value >= 0 && value <= MAX_CIRCUIT) {
                return value;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 读不到就按“槽里有异物”处理
        }
        return NO_CIRCUIT;
    }

    /** AE 键电路槽第 0 格的存量；读不到按 0（空槽）算。 */
    private static long keyCircuitAmount(Object keyInventory) {
        try {
            Object amount = amountAtMethod.invoke(keyInventory, 0);
            return amount instanceof Number number ? number.longValue() : 0L;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return 0L;
        }
    }

    /**
     * 通过 GT 自己的入口写 AE 键电路槽。
     *
     * @param circuit 编号；{@link #NO_CIRCUIT} 表示清空（{@code Circuits.set} 的语义）
     * @return 是否写入成功
     */
    private static boolean writeKeyCircuit(Object keyInventory, int circuit) {
        try {
            circuitsSetMethod.invoke(null, keyInventory, 0, circuit);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return false;
        }
    }

    /** 在类层次里找无参的 {@code getCircuitInventory()}，结果按类缓存。 */
    private static Method circuitInventoryMethod(Object holder) {
        if (holder == null) {
            return null;
        }
        return CIRCUIT_INVENTORY_METHODS
                .computeIfAbsent(holder.getClass(), GtCircuitSupport::lookupCircuitInventoryMethod)
                .orElse(null);
    }

    private static Optional<Method> lookupCircuitInventoryMethod(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("getCircuitInventory");
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return Optional.of(method);
                }
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // 继续往父类找
            }
        }
        return Optional.empty();
    }

    /** 反射调用一个无参方法；方法不存在或抛异常都返回 {@code null}。 */
    private static Object invokeNoArg(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        Method method = findNoArgMethod(owner.getClass(), name);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    /**
     * 反射调用一个公开的无参方法，按公开方法表查找（含继承来的接口 default 方法）。
     *
     * <p>与 {@link #invokeNoArg} 分开：后者只走类层次，找不到 {@code IRecipeLogicMachine} 这类
     * 接口 default 方法。两条通路互不影响，既有的鸭子类型判断不会被这次新增改到。
     *
     * <p>包内可见：{@link MachineStateProbe} 用同一张公开方法表去找机器实例的
     * {@code getRecipeLogic()} 与 {@code isIdle()}，各写一份只会让两处的查找口径慢慢漂开。
     */
    static Object invokePublicNoArg(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            for (Method method : owner.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method.invoke(owner);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** 在类层次里找带一个 {@code int} 参数的方法（{@code ContentList#ingredient(int)} 用）。 */
    private static Method findIntMethod(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, int.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static Object readFieldByTypeName(Object owner, String typeNameFragment) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (RuntimeException | LinkageError error) {
                continue;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                if (field.getType().getName().contains(typeNameFragment)) {
                    Object value = readField(field, owner);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers())) {
                    return field;
                }
            } catch (NoSuchFieldException | RuntimeException | LinkageError ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 按字段名读一个实例字段，逐层往父类找。
     *
     * <p>包内可见：{@link InputLimitSupport} 用同一条通路去读格雷机器上的「防止堵料」开关
     * （{@code CustomItemStackHandler#isInputLimited}），各写一份只会让两处的查找口径慢慢漂开。
     *
     * @return 字段值；没有这个字段、读不到或值为 {@code null} 时返回 {@code null}
     */
    static Object readFieldByName(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        Field field = findField(owner.getClass(), name);
        return field == null ? null : readField(field, owner);
    }

    private static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 反射入口解析
    // ------------------------------------------------------------------

    /**
     * 一次性解析反射入口。
     *
     * <p>这里刻意**不做**“全有或全无”的判断：每一项单独解析、单独记录失败原因，
     * 任何一项失败都只关掉它自己对应的能力。1.0.4 / 1.0.5 就是因为把一堆查找塞进同一个
     * try 块，一个失败（例如某个类在对方版本里挪了包）导致连“读电路”都被一起关掉，
     * 表现为按住 Z 什么都不显示、发送后也没反应。
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        problems.clear();

        circuitItem = findCircuitItem();

        Class<?> behaviourClass = null;
        for (String name : CIRCUIT_BEHAVIOUR_CLASSES) {
            try {
                behaviourClass = Class.forName(name);
                break;
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                // 试下一个候选名
            }
        }
        if (behaviourClass != null) {
            isIntegratedCircuitMethod = lookupStaticMethod(behaviourClass,
                    "IntCircuitBehaviour.isIntegratedCircuit", "isIntegratedCircuit", ItemStack.class);
            getCircuitConfigurationMethod = lookupStaticMethod(behaviourClass,
                    "IntCircuitBehaviour.getCircuitConfiguration", "getCircuitConfiguration", ItemStack.class);
            circuitStackMethod = lookupStaticMethod(behaviourClass,
                    "IntCircuitBehaviour.stack", "stack", int.class);
        } else {
            problems.add("找不到 IntCircuitBehaviour（已按注册名 + NBT 兜底读电路）");
        }

        resolveKeyInventory();

        if (circuitItem == null) {
            LOGGER.debug("未找到编程电路物品，电路自动配置功能已关闭（未安装格雷科技？）");
        } else if (!problems.isEmpty()) {
            // 部分入口没拿到，但功能已经自动降级、仍然可用，打一行说明便于事后排查。
            LOGGER.info("[电路] 部分格雷接口不可用（已自动降级）：{}", String.join("；", problems));
        }
    }

    /**
     * 解析 AE 键电路槽通路（gtceu 26.10.34 起）。
     *
     * <p>老版本根本没有 {@code KeyInventory}，解析不到属于正常情况：不打日志、也不算问题。
     * 只有“类在、方法却找不到”才记进 {@code problems}——那种情况下电路槽是看得见但写不进的，
     * 必须留下痕迹（1.0.39 之前正是这类失败被静默吞掉，表现为“材料进去了、电路没动”）。
     */
    private static void resolveKeyInventory() {
        try {
            keyInventoryClass = Class.forName(KEY_INVENTORY_CLASS);
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            keyInventoryClass = null;
            return;
        }
        keyAtMethod = lookupInstanceMethod(keyInventoryClass, "KeyInventory.keyAt", "keyAt", int.class);
        amountAtMethod = lookupInstanceMethod(keyInventoryClass, "KeyInventory.amountAt", "amountAt",
                int.class);
        Class<?> circuitsClass;
        try {
            circuitsClass = Class.forName(CIRCUITS_CLASS);
        } catch (ClassNotFoundException | LinkageError | RuntimeException error) {
            problems.add("找不到 Circuits（电路槽是 AE 键存储时无法读写）");
            return;
        }
        circuitsGetMethod = lookupStaticMethod(circuitsClass, "Circuits.get", "get",
                keyInventoryClass, int.class);
        circuitsSetMethod = lookupStaticMethod(circuitsClass, "Circuits.set", "set",
                keyInventoryClass, int.class, int.class);
    }

    /** 找一个公开的实例方法；找不到返回 {@code null} 并记入 {@code problems}。 */
    private static Method lookupInstanceMethod(Class<?> owner, String label, String name,
            Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            if (Modifier.isStatic(method.getModifiers())) {
                problems.add(label + "：不是实例方法");
                return null;
            }
            return method;
        } catch (NoSuchMethodException error) {
            problems.add(label + "：没有方法 " + name + "（参数 " + parameterTypes.length + " 个）");
        } catch (RuntimeException | LinkageError error) {
            problems.add(label + "：" + error);
        }
        return null;
    }

    /** 找编程电路物品：先按标准注册名直接查，查不到再按路径扫描全表兜底。 */
    private static Item findCircuitItem() {
        try {
            Item direct = BuiltInRegistries.ITEM.get(CIRCUIT_ITEM_ID);
            if (direct != null) {
                return direct;
            }
            for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
                if (CIRCUIT_ITEM_PATH.equals(key.getPath())) {
                    Item item = BuiltInRegistries.ITEM.get(key);
                    if (item != null) {
                        return item;
                    }
                }
            }
        } catch (RuntimeException | LinkageError error) {
            LOGGER.warn("查找编程电路物品失败", error);
        }
        return null;
    }

    /**
     * 找一个静态方法。先按精确签名找，找不到再按“名字 + 参数个数”放宽，
     * 这样对方改了参数类型（例如换成自家包装类）时也还能用。
     */
    private static Method lookupStaticMethod(Class<?> owner, String label, String name,
            Class<?>... parameterTypes) {
        try {
            Method exact = owner.getMethod(name, parameterTypes);
            if (Modifier.isStatic(exact.getModifiers())) {
                return exact;
            }
            problems.add(label + "：不是静态方法");
            return null;
        } catch (NoSuchMethodException ignored) {
            // 放宽条件再试
        } catch (RuntimeException | LinkageError error) {
            problems.add(label + "：" + error);
            return null;
        }
        try {
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name)
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == parameterTypes.length) {
                    return method;
                }
            }
            problems.add(label + "：没有静态方法 " + name + "（参数 " + parameterTypes.length + " 个）");
        } catch (RuntimeException | LinkageError error) {
            problems.add(label + "：" + error);
        }
        return null;
    }
}
