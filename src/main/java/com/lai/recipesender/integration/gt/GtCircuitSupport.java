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
 *       只要机器（或其父类）有无参方法 {@code getCircuitInventory()} 且返回可写物品槽，就往第 0 格写。
 *       {@code isCircuitSlotEnabled()} 存在时用它把关（蒸汽总线、以及控制器不允许电路槽的部件
 *       都是靠它关掉的），不存在则视为可用。</li>
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
 * <h2>调用频率</h2>
 * {@link #findCircuit} 只在玩家按下按键开始选择配方时调用一次，{@link #findCircuitHolder} 等
 * 只在提交时调用一次，都不是每 tick 的路径。其中唯一有点开销的是 {@code recipe.getInputs()}，
 * 但 GTO 与材料统计本来就要用它，且配方自己带懒初始化，重复调用不会再算一遍。
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

    /** GT 配方对象里可能装着输入原料的字段名，GTCEu 与 GTO 各用一套。 */
    private static final String[] RECIPE_CONTENT_FIELDS = {
            "itemInputs", "inputs", "tickInputs", "inputItems", "itemInput", "input"};
    /** 包装对象（Content / ItemIngredient）里指向真正原料的字段名。 */
    private static final String[] WRAPPER_FIELDS = {"inner", "content", "value", "ingredient"};
    /** 包装层数上限：Content -> ItemIngredient -> Ingredient 已经够用。 */
    private static final int MAX_WRAPPER_DEPTH = 4;

    /** {@code resolve()} 期间发现的问题，只在解析完成后打一次日志。 */
    private static final List<String> problems = new ArrayList<>();
    /** 每个机器类的 {@code getCircuitInventory()} 查找结果缓存。 */
    private static final Map<Class<?>, Optional<Method>> CIRCUIT_INVENTORY_METHODS = new ConcurrentHashMap<>();

    private static boolean resolved;
    private static Item circuitItem;
    private static Method isIntegratedCircuitMethod;
    private static Method getCircuitConfigurationMethod;
    private static Method circuitStackMethod;

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
     * 从 EMI 配方里找出它要求的电路编号。
     *
     * <p>两条通路依次尝试：
     * <ol>
     *   <li>EMI 的催化剂 / 输入列表——对 GTCEu 原生配方有效；</li>
     *   <li>直接翻配方对象里的 GT 配方数据——对 GTO 配方是唯一可行的通路。</li>
     * </ol>
     *
     * @return 电路编号（0-32）；配方不需要电路时返回 {@link #NO_CIRCUIT}
     */
    public static int findCircuit(EmiRecipe recipe) {
        if (recipe == null) {
            return NO_CIRCUIT;
        }
        resolve();
        if (circuitItem == null) {
            return NO_CIRCUIT;
        }
        try {
            return detectCircuit(recipe);
        } catch (RuntimeException | LinkageError error) {
            // 反射翻配方内部数据有可能撞上第三方实现的意外结构，绝不能让它冒泡打断按键处理。
            LOGGER.warn("检测配方电路时出错，本次按“无电路”处理", error);
            return NO_CIRCUIT;
        }
    }

    /** {@link #findCircuit} 的实际实现，异常由调用方兜住。 */
    private static int detectCircuit(EmiRecipe recipe) {
        // 顺序不能反：GTO 是在 getInputs() 里才把零概率输入补进 catalysts 的。
        List<EmiIngredient> inputs = inputsOf(recipe);
        int circuit = scanIngredients(catalystsOf(recipe));
        if (circuit != NO_CIRCUIT) {
            return circuit;
        }
        circuit = scanIngredients(inputs);
        if (circuit != NO_CIRCUIT) {
            return circuit;
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
     * GTO 的 {@code GTEMIRecipe} 持有 {@code GTRecipeDefinition recipe}（{@code itemInputs} 是
     * {@code List<Content>}，{@code Content.inner} 再包一层）。两条都按“字段名 + 递归解包”处理，
     * 不依赖具体类名。
     */
    private static int scanGtRecipe(EmiRecipe recipe) {
        Object gtRecipe = findGtRecipeObject(recipe);
        if (gtRecipe == null) {
            return NO_CIRCUIT;
        }
        for (String fieldName : RECIPE_CONTENT_FIELDS) {
            Object collection = readFieldByName(gtRecipe, fieldName);
            if (collection == null) {
                continue;
            }
            int circuit = scanCollection(collection);
            if (circuit != NO_CIRCUIT) {
                return circuit;
            }
        }
        return NO_CIRCUIT;
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

    /** 把 Map 的 values 或 Iterable 的元素逐个当“配方内容”扫描。 */
    private static int scanCollection(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                int circuit = scanElements(entry);
                if (circuit != NO_CIRCUIT) {
                    return circuit;
                }
            }
            return NO_CIRCUIT;
        }
        return scanElements(value);
    }

    private static int scanElements(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return NO_CIRCUIT;
        }
        for (Object element : iterable) {
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
     * 判断该机器当前是否提供可调整的电路槽。
     * 只要有无参方法 {@code getCircuitInventory()} 就认为具备；{@code isCircuitSlotEnabled()}
     * 存在时用它把关，不存在则视为可用。
     */
    public static boolean canAdjustCircuit(Object holder) {
        if (holder == null || circuitInventoryMethod(holder) == null) {
            return false;
        }
        Object enabled = invokeNoArg(holder, "isCircuitSlotEnabled");
        return enabled == null || Boolean.TRUE.equals(enabled);
    }

    /**
     * 读取机器电路槽当前的编号。
     *
     * @return 当前编号；空槽按 GT 的语义视为 0；槽里有非电路物品或读不到时返回
     *         {@link #NO_CIRCUIT}，调用方应照常写入而不是跳过
     */
    public static int readCurrentCircuit(Object holder) {
        Object inventory = circuitInventory(holder);
        if (!(inventory instanceof IItemHandlerModifiable handler) || handler.getSlots() < 1) {
            return NO_CIRCUIT;
        }
        ItemStack current = handler.getStackInSlot(0);
        if (current == null || current.isEmpty()) {
            return 0;
        }
        return readCircuit(current);
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
        Object inventory = circuitInventory(holder);
        if (!(inventory instanceof IItemHandlerModifiable handler) || handler.getSlots() < 1) {
            return false;
        }
        ItemStack configured = createCircuitStack(circuit);
        if (configured.isEmpty()) {
            return false;
        }
        handler.setStackInSlot(0, configured);
        return true;
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

    private static Object readFieldByName(Object owner, String name) {
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

        if (circuitItem == null) {
            LOGGER.debug("未找到编程电路物品，电路自动配置功能已关闭（未安装格雷科技？）");
        } else if (!problems.isEmpty()) {
            // 部分入口没拿到，但功能已经自动降级、仍然可用，打一行说明便于事后排查。
            LOGGER.info("[电路] 部分格雷接口不可用（已自动降级）：{}", String.join("；", problems));
        }
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
