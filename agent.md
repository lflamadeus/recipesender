# RecipeSender 项目开发指导与约定 (agent.md)

本文档提炼自项目长期备忘与开发实测经验（原 `.workbuddy` 与 `.workbuddy-ai` 记忆库），记录核心架构约定、踩坑教训与关键操作流程。在本项目进行任何代码修改、构建与调试时必须严格遵守。

---

## 1. 项目基础与核心定位

- **环境栈**：Minecraft `1.20.1` + Forge `47.4.20` + EMI `1.1.24+1.20.1+forge`。
- **定位**：客户端/服务端混合的配方材料高效投递与管理模组。
  - **正向投递**（默认 `Z` / `Space` 全选）：将 EMI 悬停配方所需原料从玩家背包/中枢发送至目标容器/绑定机器。
  - **反向取料**（默认 `~` + `Z`）：通过 FindMeExtended / AE2 从周围容器检索并取回背包缺失的合成材料。
  - **多机绑定与直达**（默认 `Ctrl+B` 开启）：支持单方块/批量框选（切片算法配对主从机器）、跨维度直达投料与流体分发。
  - **格雷科技深度集成**：GTCEu / GTO 多方块输入仓/总线电路自动匹配与配置、Anti-Clog 容量精准模拟。

---

## 2. 构建、版本与实机部署规范（★ 关键约定）

### 2.1 本机构建环境
- **必须使用 JDK 17**：指定路径 `C:\Program Files\Java\jdk-17`（本机默认 `java` 为 Java 8，直接运行会构建失败）。
- **离线构建命令**：
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Java\jdk-17"
  .\gradlew.bat build "-Dnet.minecraftforge.gradle.check.certs=false" --offline
  ```
  *(注：`-Dnet.minecraftforge.gradle.check.certs=false` 与 `--offline` 必须保留，避免 ForgeGradle 证书校验超时导致假编译错误)*

### 2.2 文本修改必须更新版本号（★ 核心避坑）
- **规则**：**凡是涉及文本修改（包括 `assets/recipe_sender/lang/*.json` 语言文件、HUD 提示、界面文案等），必须同时递增版本号（`gradle.properties` 与 `changelog.txt`）后再进行构建打包。**
- **原因**：Minecraft 与 Forge 客户端对资源包的语言文件存在基于 jar 标识和版本的深层内存与本地缓存机制。若仅修改文本而不递增版本号直接替换 jar，客户端启动后极易继续读取旧版缓存，导致新增的键显示为原始字符串键名或依然显示旧文本。

### 2.3 实机测试实例与部署路径（★ 关键路径）
- **实例绝对路径**：`E:\Program Files\Game\Minecraft\PrismLauncher\data\instances\GregTech.Odyssey-0.6.0`
  *(说明：`C:\Users\PC\AppData\Roaming\PrismLauncher` 是指向上述 `data` 目录的 NTFS Junction 链接，操作同一批文件，统一使用 E 盘绝对路径)*
- **模组部署目录**：
  `E:\Program Files\Game\Minecraft\PrismLauncher\data\instances\GregTech.Odyssey-0.6.0\.minecraft\_mymods\mods`
- **部署流程与 ACL 守则**：
  1. **构建输出的 jar 必须更新到 `_mymods\mods` 目录下**。
  2. **严禁直接拷贝到 `.minecraft\mods\`**：实例中的 `.minecraft` 目录在启动时由 `packwiz-update.ps1` 脚本全权托管，且存在 `Users:(RX)` 的只读权限限制（直接复制会报 `Permission denied`）；`_mymods\mods` 为当前用户可写目录，启动器脚本会自动认领并将模组同步至最终游戏目录。
  3. **必须清理旧版本 jar**：放入新版本（如 `recipe_sender-1.0.58.jar`）时，**必须同时删除 `_mymods\mods` 中的旧版 jar**。同 modId 的多个 jar 共存会导致启动崩溃或版本错乱。旧版本统一归档至源码目录下的 `build/libs/past/`。

---

## 3. 服务端权威与通信架构

- **服务端权威原则**：
  - 容器抽取/注入、背包扣除、流体分发、电路更改等物理操作**必须在服务端主线程执行**。
  - 客户端一切统计、显示、预测仅作 UI 提示；服务端收到请求包后必须基于真实 `Menu`、真实方块实体（`BlockEntity`）重新执行严谨校验。
- **规划逻辑复用**：
  - 客户端与服务端共用的配方材料匹配与分配规划统一放在 `service/TransferPlanner`，避免两端计算口径分裂。
- **网络协议版本号（`ModNetwork.PROTOCOL_VERSION`）**：
  - **凡是新增或修改网络包字段/数据结构，必须同步递增 `ModNetwork.PROTOCOL_VERSION`**。
  - 网络包在反序列化时必须做严格的尺寸上限校验（防止异常大包导致内存溢出）。

---

## 4. 输入检测与事件拦截规范（★ 血泪经验）

### 4.1 按键检测严禁使用 `KeyMapping.isDown()`
- **踩坑根因**：Forge 的 `KeyMappingLookup` 表在玩家进入“控制”菜单修改按键后**不会被同步更新**（修改只改动 `KeyMapping.getKey()`）。若代码中调用 `isDown()`，它会继续监听旧按键，表现为“改键后旧键依然能触发功能”。
- **正确做法**：一律通过 `keyMapping.getKey()` 获取物理键位，分派检测 GLFW 底层状态：
  - `InputConstants.Type.KEYSYM` → 调用 `InputConstants.isKeyDown(window, value)` / `glfwGetKey`
  - `InputConstants.Type.MOUSE` → 调用 `glfwGetMouseButton`
  - `SCANCODE`（极少见）→ 才退回 `keyMapping.isDown()`
  - 未绑定状态表现为 `KEYSYM` 且 `value = -1`。

### 4.2 按键必须无条件注册
- Forge 只会在游戏启动时把 `options.txt` 里保存的改键映射刷入“已注册”的 `KeyMapping` 集合。
- 若根据模组是否加载等条件注册按键，条件不满足时玩家自定义的改键会在下次启动被抛弃并重置为默认值。功能是否启用必须移至运行期判定。

### 4.3 右键拦截必须挂载 `InputEvent.InteractionKeyMappingTriggered`
- 取消 `PlayerInteractEvent.RightClickBlock` 拦不住发包：`MultiPlayerGameMode.useItemOn` 内部先进行客户端预测，数据包无条件发出，服务端依然会触发打开机器界面。
- `InteractionKeyMappingTriggered` 位于 `Minecraft.startUseItem()` 最头部，取消该事件才能彻底切断数据包发送，并能稳定阻断每 4 tick 重复的“长按使用键”重放。
- 取消左键（攻击键）拦截时，必须附加 `event.setSwingHand(false)`，防止触发虚假的破坏粒子与摆臂动画。

---

## 5. 跨模组集成与反射防御规范

### 5.1 隔离与失败降级原则
- 依赖层级：**EMI 为硬依赖**；**GTCEu / GTO、AE2、FindMeExtended 为可选依赖**。
- 可选依赖一律隔离在 `integration` 包中通过反射交互。
- **反射初始化必须单项隔离**：跨模组反射绝对禁止“全有或全无”（例如不能因为 AE2 某一个 action source 字段反射失败就全局禁用整个 AE2 支持）；每一项单独尝试、单独记录失败日志并优雅降级。
- 禁止在按键注册期触发外部模组反射。

### 5.2 EMI 联动
- 交互入口仅认 `EmiApi.getHoveredStack()` + `EmiApi.getHandledScreen()`。
- 严禁尝试侵入或兼容 EMI 的配方树等内部复杂界面。
- 鼠标悬停判定采用结构比较（配方比较 `getId()` + `getCategory()`，原料比较 `EmiIngredient.areEqual`），不可轻信对象引用比较。

### 5.3 格雷科技（GTCEu / GTO）集成
- **电路配置统一通路**：格雷配方且不需要电路时清空电路槽；正向发送（`ClearContainerCircuitPacket`）、绑定发送（`BoundInsertionService`）、直达发送（`DirectInsertionService`）三条通路必须行为一致。
- **GT 配方原料识别（`ContentList`）**：新版 GTCEu 的 `itemInputs` 为 `ContentList`（既非 Map 也非 Iterable），必须通过检查接口公开默认方法的 `invokePublicNoArg` 来调用 `circuitConfiguration()`。
- **反射调用缓存**：高频调用的无参公开方法（如 `invokePublicNoArg`）必须维护缓存（`MethodKey`），避免在轮询探测时频繁调用 `Class.getMethods()` 分配垃圾对象。
- **Anti-Clog 容量精确模拟**：必须在模拟计算时考虑目标机器是否启用了防堵转限额（`IInputLimitableMachine.isInputLimit()`），否则会导致容量严重高估引发退料。

### 5.4 AE2 与周围容器扫描
- **聚合视图去重**：AE2 接口暴露的 `NetworkStorage` 会将网络内全部存储重复暴露，去重必须依据**内容包含判定**，不能使用对象身份判断。
- **AE 提取必须使用原始键实例**：AE 内部存储的 `keyMap` 使用 `Reference2LongOpenHashMap`（引用同一性比较），提取时若更换新构造的 `AEItemKey` 实例必然返回 0，必须使用存储自身给出的 Key 实例。
- **避免副作用的模拟**：外部模组（如 GTO 无人机仓）在 `extractItem` 模拟时不遵守 `simulate` 标志直接清空缓存，因此只对相关性筛选通过的物品做抽取探测，杜绝探测导致游戏状态被篡改。

---

## 6. 性能与代码整洁约定

- **方块实体检索**：
  - 检索周围容器时，一律通过确定相交的区块坐标并读取 `LevelChunk#getBlockEntities()`。
  - **严禁逐格遍历三维空间坐标（如 33×33×33）调用 `level.getBlockEntity(pos)`**。
- **准入检查只读原则**：
  - 容器准入四层只读判定：存在方块实体 → 具备 ItemHandler → 槽位有效（`getSlotLimit() > 0`）→ `isItemValid(0, 任意通用物品)`。
  - 严禁在准入过滤阶段进行复杂的 dry-run 插入模拟。
- **配置项三处同步**：
  - 增加或修改配置项时，必须同时维护：
    1. `RecipeSenderConfig.java`（字段定义、默认值与 Forge 读写逻辑）
    2. `RecipeSenderConfigScreen.java`（GUI 控件与排版布局）
    3. 语言文件 `assets/recipe_sender/lang/zh_cn.json` 与 `en_us.json`。
- **代码风格**：
  - 源码与 Javadoc 注释统一使用**中文**，重点记录“为什么这么做”（特别是反直觉逻辑）。
  - 工具类使用 `final` + 私有构造方法；数据载体优先使用 `record`。

---

## 7. 版本控制与推送规范

- **Git 仓库**：`https://github.com/lflamadeus/recipesender.git`，主分支 `main`。
- **代理直连绕过**：
  - 若本机 Clash 等代理未启动，而全局 Git 配置存在 `http.proxy = http://127.0.0.1:7890` 导致推送连接失败时，使用命令临时绕过代理直连推送：
    ```bash
    git -c http.proxy= -c https.proxy= push origin main
    ```
- **提交规范**：
  - 提交前确认 `temp/`、`.workbuddy`、`.workbuddy-ai` 均被 `.gitignore` 忽略。
  - 遵循 Conventional Commits 格式（如 `feat(cross-dim): ...`, `fix(hud): ...`）。
