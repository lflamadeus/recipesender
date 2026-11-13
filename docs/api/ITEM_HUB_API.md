# Recipe Sender - ItemHubApi 技术集成文档

本文档面向第三方 Minecraft 模组开发者（如**蓝图打印机**、**自动建筑机器**、**自动化物流**等模组），提供接入与调用 `recipe_sender` 物品中枢（Item Hub）的标准指引。

---

## 1. 简介 (Introduction)

在复杂整合包（特别是 GregTech / AE2 科技整合包）中，玩家的大量建材与中间产物通常集中存储在大型 ME 网络、GTO 物品保险库或专用的中心存储库中。传统的蓝图或建造模组往往只能访问玩家背包或贴身放置的箱子，无法跨维度、无缝地调度这些庞大仓储。

`recipe_sender` 提供了轻量、无任何 GUI 依赖的标准对外 API —— `ItemHubApi`：
- **查 (Query)**：快速检索玩家绑定的所有中枢内某材料的总可用存量。
- **取 (Extract)**：直接从中枢抽取指定数量的材料用于结构建造。
- **放回 (Insert)**：支持将多取的余料或拆解蓝图时的回收料直接存回中枢。
- **底层全兼容**：原生支持并穿透 **Applied Energistics 2 (AE2)** 的 ME 存储视图，并原生兼容 **GregTech Odyssey (GTO) 的物品保险库 (`MultiblockMEStorageMachine`)**。

---

## 2. 依赖配置 (Gradle Setup)

推荐在你的模组中将 `recipe_sender` 作为**可选依赖 (Soft/Optional Dependency)** 引入。

### 本地 Jar 依赖示例 (`build.gradle`)
将导出的 `recipe_sender-<version>.jar` 放入你工程的 `libs/` 目录下：

```groovy
dependencies {
    // 仅编译期依赖，避免硬打包进你自己的 mod jar 中
    compileOnly fg.deobf(fileTree(dir: 'libs', include: ['recipe_sender-*.jar']))

    // 运行调试时可选加载
    runtimeOnly fg.deobf(fileTree(dir: 'libs', include: ['recipe_sender-*.jar']))
}
```

---

## 3. 运行时安全调用模式 (Safe Runtime Access)

为了确保当玩家未安装 `recipe_sender` 时你的模组不会触发 `NoClassDefFoundError`，建议编写一层薄的隔离代理：

```java
import net.minecraftforge.fml.ModList;

public class RecipeSenderIntegration {
    public static final String MOD_ID = "recipe_sender";

    public static boolean isRecipeSenderLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static boolean hasAvailableHub(ServerPlayer player) {
        if (!isRecipeSenderLoaded()) {
            return false;
        }
        // 将具体的类访问封装在内部类或独立方法中，保证延迟类加载
        return HubBridge.isAvailable(player);
    }

    private static class HubBridge {
        private static boolean isAvailable(ServerPlayer player) {
            return com.lai.recipesender.api.ItemHubApi.isAvailable(player);
        }
    }
}
```

---

## 4. API 完整参考 (API Reference)

类全限定名：`com.lai.recipesender.api.ItemHubApi`

> [!NOTE]
> 所有方法均要求在**服务端主线程**（例如 Tick 事件、方块实体更新、网络包处理线程）中调用。所有方法均具备空安全防护，参数传入 `null` 时将安全返回默认值，绝不抛出运行时异常。

### 4.1 状态与可用性检查
```java
// 检查玩家当前是否有至少一个有效且区块处于加载状态的物品中枢
boolean isAvailable(ServerPlayer player);

// 获取玩家当前可用的中枢数量（绑定了多个中枢时）
int getHubCount(ServerPlayer player);

// 获取中枢实现的 API 版本号：0 = 1.0.62 及更早，1 = 支持 ItemMatchMode / findMatches
// 注意：请调用本方法探测，不要依赖常量 API_VERSION（编译期常量会被内联，看不到旧中枢的真实版本）
int apiVersion();
```

### 4.2 材料查询 (Query)
```java
// 查询玩家中枢中指定物品的总可用存量（严格口径，空标签与无标签视为同一种物品）
int countAvailable(ServerPlayer player, ItemStack wanted);

// 按指定匹配口径统计存量（蓝图建材通常用 ItemMatchMode.CLEAN）
int countAvailable(ServerPlayer player, ItemStack wanted, ItemMatchMode mode);

// 按指定匹配口径列出中枢中所有符合条件的物品堆（保留中枢里的真实标签状态）
List<ItemStack> findMatches(ServerPlayer player, ItemStack wanted, ItemMatchMode mode);

// 获取玩家中枢中所有物品的完整快照（深拷贝列表，只读安全）
List<ItemStack> getContents(ServerPlayer player);
```

### 4.3 材料抽取 (Extract)
```java
// 从中枢中抽取指定数量的某种材料
// 若中枢存量不足，将抽出并返回实际能拿到的所有存量；抽不到返回 ItemStack.EMPTY
ItemStack extract(ServerPlayer player, ItemStack wanted, int amount);

// 按指定匹配口径抽取（推荐：先 findMatches 看清口径，再按同一口径抽取）
ItemStack extract(ServerPlayer player, ItemStack wanted, int amount, ItemMatchMode mode);

// 便捷重载：抽取 wanted.getCount() 数量
ItemStack extract(ServerPlayer player, ItemStack wanted);
```

### 4.4 材料放回 (Insert)
```java
// 将物品放回玩家的物品中枢（支持 AE2 / GTO 保险库 / 普通容器）
// 返回未能存入的剩余物品堆；若全部存入返回 ItemStack.EMPTY
ItemStack insert(ServerPlayer player, ItemStack stack);

// 批量放回多组物品，返回未能存入的余料列表（全部存入返回空列表）
List<ItemStack> insertAll(ServerPlayer player, List<ItemStack> stacks);
```

### 4.5 匹配口径 (ItemMatchMode)

方块类建材（塑料混凝土、机器方块等）本身没有 NBT，但在不同容器、不同存档之间流转时，常被补上一个**空**标签 `{}`。
原版 `ItemStack.isSameItemSameTags` 用 `Objects.equals` 比较标签，会把 `null` 与 `{}` 判成两种物品——
于是同一份材料「背包里能用、中枢里统计为 0」。`ItemMatchMode` 把「算不算同一种物品」的判据集中到一处：

```java
public enum ItemMatchMode {
    EXACT,      // 物品 + 伤害值 + 标签（空标签归一为无标签）+ 能力数据都一致；历史行为，默认值
    CLEAN,      // 物品 + 伤害值相同，且两侧都不携带任何实质 NBT（只认纯净堆）
    IGNORE_NBT  // 只比较物品 + 伤害值（粗粒度检索，慎用于统计）
}
```

- 三种口径都**强制比较伤害值**：GregTech 的 metadata 变体共用同一个 `Item`，忽略伤害值会抽错材料。
- `EXACT` 与 `CLEAN` 都把空标签视为「无 NBT」，因此 `{}` 与 `null` 不再被当成两种物品。
- 蓝图打印这类「要的是一张白纸」的场景请用 `CLEAN`：带配置、带能量、带滤网的同类物品不会被误统计、误抽取。
- 抽取非 `EXACT` 口径时，中枢会先按口径找出真实存在的堆，再以这些堆的原始标签作为抽取需求，
  所以不会出现「统计说有、抽的时候失配」。

**版本探测（可选软依赖必读）：**

```java
private static boolean supportsMatchMode() {
    try {
        return com.lai.recipesender.api.ItemHubApi.apiVersion() >= 1;
    } catch (LinkageError notSupported) {
        return false; // 中枢为 1.0.62 及更早：退回旧接口，空标签材料仍可能统计不到
    }
}
```

探测结果请缓存一次，避免旧版中枢反复抛 `NoSuchMethodError`；把带 `ItemMatchMode` 的调用放进独立的内部类，
确保只有探测通过后才会被类加载（写法可参考本仓库对应蓝图的集成适配器）。

### 4.6 取回背包 (pullToInventory)

```java
// 让中枢把材料直接送进玩家背包，返回实际送达的数量（0 表示没送到）
int pullToInventory(ServerPlayer player, ItemStack wanted, int amount);
int pullToInventory(ServerPlayer player, ItemStack wanted, int amount, ItemMatchMode mode);
```

`extract` 走的是中枢直达的通用反射通路；**GTO 物品保险库、AE2 网络**这类存储只认「网络自己给出的
AE 键」，通用通路在上面会静默返回空堆（统计得出、抽取为 0）。`pullToInventory` 复用的是本模组发送
配方时的同一条取回链路，这些中枢都能正常出货。

调用方拿到的是「材料已经进了玩家背包」：

```java
int delivered = ItemHubApi.pullToInventory(player, required, needed);
if (delivered > 0) {
    // 立刻按自己的口径从背包扣掉，语义上仍算从中枢取得
    takeFromInventory(player.getInventory(), required, delivered);
}
```

同样地，API 2 起才提供本方法；探测写法与 4.5 节一致（`apiVersion() >= 2`）。

### 4.7 供料诊断 (diagnose / describeHubs)

```java
// 只列容器，不判定匹配
List<String> describeHubs(ServerPlayer player);

// 检查每个中枢对某条需求有没有供料能力（只读，不抽取、不插回）
List<String> diagnose(ServerPlayer player, ItemStack wanted, ItemMatchMode mode);
```

典型输出（一行一个中枢）：

```
中枢数量: 1
gtocore:flat 1631,64,5712 gtocore:item_vault | AE存储=有(键 137，匹配 1，首个 塑料混凝土 无NBT)，物品槽=1格(匹配 0)，保护门=可交互
```

三段信息分别回答三个问题：

| 段 | 含义 | 读到 `0` 说明 |
| --- | --- | --- |
| `AE存储=有(键 N，匹配 M)` | ME 存储视图里匹配需求的键数 | 存储里没有这种材料，或键的 NBT 对不上 |
| `物品槽=N格(匹配 M)` | Forge 槽位里匹配的格数（GTO 保险库只暴露第一个键，这里通常为 0） | 不能指望槽位通路 |
| `保护门=可交互 / 被拦截(仅限制自动扫描)` | `RightClickBlock` 探针的结果 | **只影响「扫描周围容器自动取料」，不影响中枢抽取** |

> **保护门与中枢抽取**：中枢由玩家主动绑定，绑定即授权，因此 `extract` / `countAvailable` /
> `pullToInventory` 这些**定点**接口不会再问这道门——那是个伪造的右键事件，会被领地/队伍类模组或
> 方块自身的右键处理取消，让一次合法抽取静默变成 0。保护门只保留在发送配方时「扫描周围容器」
> 的自动发现路径上。AE2 网络的权限由 `MEStorage.extract` 的 `ActionSource` 自己把关。

写失败报告时把这几行原样附上，比只写「材料不足」有用得多——它能直接区分「中枢里根本没有」
「有但匹配不上」「匹配上了但被拦」。诊断会遍历一次 ME 网络内容，**只在失败或玩家主动执行时调用**，
不要放进 tick 循环。

API 3 起才提供本方法，探测写法同上（`apiVersion() >= 3`）。

---

## 5. 蓝图模组集成示例 (Schematic Integration Example)

以下为蓝图建筑机在放置方块与回收拆解时的典型交互逻辑：

```java
import com.lai.recipesender.api.ItemHubApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class BlueprintBuilder {

    /** 尝试建造蓝图上的某一个方块 */
    public static boolean placeBlueprintBlock(ServerPlayer player, ItemStack requiredBlockItem) {
        // 1. 优先检查玩家背包
        if (takeFromPlayerInventory(player, requiredBlockItem)) {
            return true;
        }

        // 2. 背包没有，从 Recipe Sender 物品中枢调取
        if (ItemHubApi.isAvailable(player)) {
            // 先校验中枢是否拥有至少 1 个该物品
            if (ItemHubApi.countAvailable(player, requiredBlockItem) >= 1) {
                // 执行实际扣除抽取
                ItemStack extracted = ItemHubApi.extract(player, requiredBlockItem, 1);
                if (!extracted.isEmpty()) {
                    return true; // 拿到了方块，执行放置
                }
            }
        }

        // 材料不足，无法建造
        return false;
    }

    /** 蓝图拆除或材料退料 */
    public static void refundBlueprintMaterial(ServerPlayer player, ItemStack refundItem) {
        if (refundItem.isEmpty()) {
            return;
        }

        // 若中枢可用，优先放回物品中枢（如 AE 保险库）
        if (ItemHubApi.isAvailable(player)) {
            refundItem = ItemHubApi.insert(player, refundItem);
        }

        // 中枢放不下或未开启中枢，返还给玩家背包
        if (!refundItem.isEmpty()) {
            giveToPlayerOrDrop(player, refundItem);
        }
    }
}
```

---

## 6. 存储特性与注意事项

1. **GTO 物品保险库与 AE2 兼容机制**：
   - 当中枢绑定的方块为 GTO 物品保险库或 AE2 容器时，`ItemHubApi` 自动使用 `AEItemKey` 与 `MEStorage` 原生协议交互。
   - 提取时按键精准抽取；放回时自动调用 `MEStorage.insert` 归入 ME 网络，不会受限于单格虚拟槽位的限制。
2. **跨维度支持**：
   - 若服务端的 `directCrossDimension` 配置项开启（默认开启），且中枢所在的异次元区块保持加载（如拥有区块加载器），蓝图模组跨维度操作依然能秒级读取与存取。
3. **事务性保障**：
   - `extract` 与 `insert` 在服务端执行时是强一致的，调用成功即代表方块实体数据已同步发生改变并触发 `setChanged()`。
4. **不要自行比较 NBT**：
   - 同一材料在中枢里可能以「无标签」或「空标签 `{}`」两种形态存在，第三方模组自行写 `Objects.equals(stack.getTag(), wanted.getTag())` 会重蹈「统计为 0」的覆辙。请使用 `ItemMatchMode` 重载，让判据留在中枢内部。
