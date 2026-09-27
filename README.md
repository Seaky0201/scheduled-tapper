# 定时连点器 (scheduled-tapper)

Android 定时多步自动连点工具：**悬浮窗取点 + 无障碍手势点击注入 + 按系统时间对齐的多时间点触发**。
UI 使用 Flutter，核心能力（悬浮窗、点击注入、定时调度、配置持久化）全部由 Android 原生（Kotlin）实现。

---

## 一、技术选型与依据（阶段 1 结论）

工程目录最初为空，无任何既有约定，因此按用户确认结果选型：

| 项目 | 结论 | 依据 |
| --- | --- | --- |
| 平台 | **Android 原生能力 + Flutter UI** | 悬浮窗（`WindowManager`）与点击注入（`AccessibilityService`）只有原生能落地；用户明确选择「Flutter（核心走原生插件）」 |
| 目标机型 | 荣耀 60 | 用户指定。该机型出厂 Android 11 (MagicOS 5.0)，可升级至 Android 13 |
| minSdk | **26** (Android 8.0) | `TYPE_APPLICATION_OVERLAY` 自 API 26 起才可用，且 26 覆盖该机型全部系统版本 |
| 点击注入方案 | **无障碍 `dispatchGesture()`** | 先探测可用性：`TapperAccessibilityService.isReady()` / `isEnabledInSettings()`。**不需要 root**，未开启时界面明确提示并跳转设置 |
| 触发时钟 | 系统日历时间（`Calendar`） | 硬约束 3，禁止 sleep 累加 |

构建环境（本机实测，非假设）：JDK 17、Android SDK（platform 35/36、build-tools 34/35/36、platform-tools）、
Flutter 3.47.5 / Dart 3.13.4、Gradle 9.3.1、AGP 9.1.0、Kotlin 2.4.0 —— 全部安装在项目内 `.toolchain/`（不入库）。

---

## 二、功能

1. **多时间点触发**：支持「每天 第 X 时 第 Y 分 第 Z 秒」与「**每小时 第 Y 分 第 Z 秒**」两种模式；
   可添加多条，可单独启用/停用、删除、上下排序。
2. **重复次数与重复间隔**（本轮新增）：每个时间点可设置「总触发次数」与「相邻两次的间隔(ms)」；
   到点后按设置重复执行，达到次数上限即停止并回到待机。
3. **修改已添加的时间点**（本轮新增）：卡片上的「修改」入口可编辑 时/分/秒 与重复参数，保存后界面与持久化同步生效。
4. **悬浮窗取点 + 快捷操作面板**：可拖动悬浮窗；**单击展开设置面板**（本轮改造），面板内可直接取点/刷新配置/中止执行/回主界面；
   取点模式点击屏幕任意位置即记录该点坐标（连同当时的屏幕宽高）。
5. **多步点击序列**：每步 = 坐标 + 到下一步的延时(ms)，可增删、重排、改延时、重新取点。
6. **到点自动执行**：按系统时间对齐触发，全程写日志（含计划/实际时刻与偏差 ms），执行完回到待机。
7. **配置持久化**：写入 SharedPreferences，重启后仍生效；**旧版本数据自动兼容**（缺少新字段时回落到「单次」）。
8. **悬浮窗选点导入（多选）**：悬浮窗内直接列出全部时间点并**多选**（含「全选 / 取消选择」），
   确认后把本次取到的坐标**追加**到每个被勾选的时间点；**取消或关闭不产生任何写入**。
9. **悬浮窗「测试时间点」**：悬浮窗内**单选**一个时间点 -> 「确认执行」-> 立即按**该时间点自己的步骤序列**
   开始模拟点击（复用既有执行入口，见下）；未选择 / 取消 / 该点没有步骤时**不触发任何点击**。

---

## 三、关键设计（对应硬约束）

### 硬约束 1：坐标必须按当前屏幕重新换算

取点时把当时的屏幕宽高 `(sw, sh)` 与坐标一起存下来；执行时用**当前** `resources.displayMetrics` 重新换算：

| 情况 | 处理 |
| --- | --- |
| 尺寸完全一致 | 原样使用 |
| 同方向、分辨率不同 | 按归一化比例缩放 |
| 横竖屏切换 | 按内容顺时针旋转 90° 映射 |
| 超出当前屏幕 | 夹取到屏幕内；执行前若仍越界则**拒绝点击并报错**，绝不乱点 |

实现：`android/app/src/main/kotlin/com/dsh/tapper/TapMath.kt` 的 `mapToCurrentScreen`。
测试：`TapMathTest.kt` 中 6 个用例（同屏 / 放大 / 缩小夹取 / 旋转 / 旋转往返 / 未知源尺寸）。

### 硬约束 2：点击注入用系统级方案，先探测可用性

`TapperAccessibilityService` 使用 `dispatchGesture` 派发单点手势。
执行前检查服务是否已连接；未连接则记录 ERROR 并中止，不静默失败。**未使用 root 方案**（用户未要求，也无证据表明可用）。

### 硬约束 3：定时必须按系统时间对齐，不能 sleep 累加

调度器每 200ms 轮询，命中判定基于系统日历的**时/分/秒**（`TapMath.dueKey`），
去重键为 `(时间点id, 年+年内第几天, 时, 分, 秒)`，避免同一秒重复触发、并保证次日可再次触发。
「下一次触发时刻」始终由 `TapMath.nextTriggerAt` 从当前绝对时间重新计算，**不做 sleep 累加**。
测试：`simulateManyHoursWithoutDrift` 模拟连续 200 小时，验证无秒级漂移累积。

### 重复触发（本轮新增）

字段含义：

| 字段 | 位置 | 含义 |
| --- | --- | --- |
| `repeatCount` | `TimePoint` | **总触发次数**（含第一次）。1 = 只触发一次（等价旧版行为）；上限 999 |
| `repeatIntervalMs` | `TimePoint` | **相邻两次触发之间的间隔**（毫秒）。0 = 首次触发后立即连续执行；上限 24 小时 |

调度规则（`Scheduler.tick()` + `TapMath.nextRepeatDecision`）：

1. 基准时刻（时/分/秒）命中 → 立即执行第 1 次，并记录本轮进度 `RepeatState(baseAt, 1)`；
2. 第 2..N 次的计划时刻 = `baseAt + (k-1) * repeatIntervalMs`，按**绝对时刻**判定，**不做 sleep 累加**，因此不漂移；
3. 到达 `repeatCount` 上限后本轮结束，等下一个基准时刻再开新一轮；
4. 若上一次序列尚未执行完，**不推进进度**，等它结束后立即补上（间隔 0 时尤其关键），因此不会漏掉次数；
5. 修改某时间点的重复参数后，其重复进度会被重置，避免沿用旧计数导致多触发/漏触发。

边界处理（全部有单测覆盖，见第八节）：
- `repeatCount ≤ 0` 或负数 → 夹取为 1；超过 999 → 夹取为 999
- `repeatIntervalMs < 0` → 夹取为 0；超过 24 小时 → 夹取为上限
- UI 输入为空或非数字 → 对话框内报错并保持打开，不写入非法值

### 后台存活

`OverlayService` 是前台服务（`specialUse` 类型 + 常驻通知），承载悬浮窗与调度线程。
服务内看门狗每 2 秒检测悬浮窗是否丢失并自动恢复；Android 13+ 主动申请通知权限。

---

## 四、构建

```powershell
# 一键构建（设置 JAVA_HOME / ANDROID_HOME，跑单测，再出 APK）
powershell -NoProfile -ExecutionPolicy Bypass -File build-apk.ps1
```

产物：`build/app/outputs/flutter-apk/app-release.apk`，已另存交付副本 `dist/scheduled-tapper-v1.7.1.apk`（48.2MB）。
历史副本 `dist/scheduled-tapper-v1.1.0.apk` / `v1.2.0` / `v1.3.0` / `v1.3.1` / `v1.4.0` / `v1.5.0` / `v1.6.0` / `v1.7.0` 全部保留，便于回退对比
（v1.3.1 及更早均**不含**分组与布局改造；v1.7.1 为时间链路 P0–P3 缺陷修复轮，**不含**新功能）。

### 本机网络限制与应对（实测）

| 域名 | 直连 | 走 127.0.0.1:7890 代理 | 应对 |
| --- | --- | --- | --- |
| `dl.google.com` | 失败 | 失败 (000) | Maven 改用**阿里云镜像**；SDK 组件改用**腾讯云镜像** |
| `plugins.gradle.org` | 失败 | 失败 (000) | 改用阿里云 gradle-plugin 镜像 |
| `maven.google.com` | 301 跳转到 dl.google.com（死路） | 同左 | 不用，直接走阿里云 google 镜像 |
| `storage.googleapis.com` | **可直连** | 可 | 不要给它配代理，否则反而失败 |
| `maven.aliyun.com` / `repo1.maven.org` | **可直连** | 可 | 正常使用 |

因此 `android/gradle.properties` 中**刻意不配置** `systemProp.*.proxyHost`（配了会导致可直连域名解析失败），
仓库镜像配置见 `android/settings.gradle.kts`。

> 注：为让 Flutter 自带的 `includeBuild` 也能用镜像，修改了
> `.toolchain/flutter/packages/flutter_tools/gradle/settings.gradle.kts`（**仅本机工具链，非项目源码**）。
> 重装 Flutter SDK 后需要重新应用。

---

## 五、安装与首次使用

1. 安装 APK（手机连上 USB 并开启调试后）：
   ```powershell
   .\.toolchain\android-sdk\platform-tools\adb.exe install -r dist\scheduled-tapper-v1.7.1.apk
   ```
   也可直接把 `dist/scheduled-tapper-v1.7.1.apk` 传到手机点击安装（需允许「未知来源」）。
2. 打开应用，依次授权：
   - **显示在其他应用上层** —— 点「悬浮窗设置」跳转
   - **无障碍服务** —— 点「无障碍设置」，在「已下载的服务」里找到「定时连点器」并开启
   - Android 13+ 会请求通知权限（前台服务通知）
3. **荣耀/华为机型务必额外设置**（否则息屏后易被系统杀掉导致漏触发）：
   - 应用启动管理 → 改为**手动管理** → 允许**自启动**、允许**后台活动**
   - 电池 → 更多电池设置 → 关闭对该应用的「智能省电」/ 设为「不允许优化」

---

## 六、使用流程

1. 点右下角「新增时间点」，选择 小时（含「**每小时**」）/ 分 / 秒，并填写**重复次数**与**重复间隔(ms)**。
2. 在卡片里点「悬浮窗取点添加步骤」，屏幕出现红色十字，点击目标位置即记录坐标。
3. 步骤右侧「⋮」菜单：重新取点 / 改延时 / 上移 / 下移 / 删除步骤。
4. 需要改时间或重复参数时，点卡片上的「**修改**」按钮，改完点「保存」。
5. 用「**立即执行一次**」验证序列；用「**测试单点**」只验证一次点击注入是否生效。
6. 悬浮窗：**拖动**移动位置，**单击展开/收起设置面板**，**长按**隐藏。
   面板内可直接：选取位置（取点）、**选点导入（多选时间点）**、显示/隐藏悬浮窗、刷新配置、中止当前执行、打开主界面。
7. **测试时间点**：悬浮窗单击展开面板 → 「测试时间点」→ 点一行选中它（再点一次取消）→ 「确认执行」，
   立即按该时间点的步骤开始模拟点击；点「取消」则不做任何点击。步骤仍按「到下一步的延时(ms)」逐步执行。
   与主界面「立即执行一次」的区别：后者**固定执行第 1 个时间点**，本入口可指定任意一个。
8. **选点导入（多选）**：先「选取位置（取点）」拿到坐标 → 悬浮窗单击展开面板 → 「选点导入（多选时间点）」
   （主界面「选点导入」按钮也可直接唤起）→ 勾选目标时间点（可「全选」）→「确认导入」。
   导入语义：**追加到末尾、不去重**（与主界面「悬浮窗取点添加步骤」一致）；未勾选的时间点完全不动。
   注意：待导入的是**最近一次取到的坐标**，重新取点会替换它；该坐标仅存内存，服务重启即失效（不新增持久化）。

---

## 七、日志

页面底部日志区实时显示，格式如：

```
08:12:03.104 [RUN]  触发 每小时:10:00 ｜ 计划 08:12:00.000 实际 08:12:03.104 偏差 3104ms ｜ 共 3 步
08:12:03.110 [TAP]  步骤 1/3 点击(540,1200) 成功（取点时 1080x2400 -> 当前 1080x2400，已换算）
08:12:03.145 [WAIT] 等待 300ms 后执行下一步
08:12:03.451 [TAP]  步骤 2/3 点击(545,1600) 成功
08:12:03.660 [TAP]  步骤 3/3 点击(600,1900) 成功
08:12:03.665 [DONE] 序列结束：每小时:10:00 ｜ 耗时 561ms，回到待机
```

同时可通过 logcat 查看：

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe logcat -s LogBus:D
```

---

## 八、验证证据（实际执行过的）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Dart 静态分析 | `flutter analyze` | **No issues found!** |
| Kotlin 编译 | `gradle :app:testDebugUnitTest` | **BUILD SUCCESSFUL**，全部 Kotlin 源码编译通过 |
| Kotlin 单元测试 | 同上 | **tests=142 failures=0 errors=0 skipped=0**（TapMathTest 35 + PickerModelTest 18 + TestTargetTest 22 + BubbleStatusTest 11 + GroupsTest 8 + ClockTest 48） |
| Flutter 测试 | `flutter test` | **62 项全部通过**（新增 point_menu_test.dart 12 项） |
| Flutter 单元/Widget 测试 | `flutter test` | **25 项全部通过**（含真实渲染的排版测量） |
| release 构建 | `build-apk.ps1` | **BUILD SUCCESSFUL**，**Built app-release.apk (48.0MB)** |
| 新代码进入产物 | 解包 APK 查 `classes.dex` | `选点导入` 字符串命中 = **True**（面板文案确已编译进 release 包） |
| 交付包版本 | `aapt2 dump badging dist/scheduled-tapper-v1.7.1.apk` | package=`com.dsh.tapper`，**versionCode=10，versionName=1.7.1**，targetSdk=36 |
| 新增权限 | 同上 | `android.permission.INTERNET`（仅用于 SNTP 授时，用户已确认） |
| 交付包签名 | `apksigner verify --print-certs` | 已签名（debug 证书），可安装 |
| 交付包哈希 | `Get-FileHash -Algorithm SHA256` | v1.2.0 = `CA64697F…5CEA7A09`；v1.1.0（旧，不含新功能）= `D08784C1…154AFF0B` |
| APK 清单 | `aapt2 dump badging` | package=`com.dsh.tapper`，minSdk=26，targetSdk=36，compileSdk=36 |
| 权限 | 同上 | SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / WAKE_LOCK 均在 |
| 服务注册 | `aapt dump xmltree` | `TapperAccessibilityService`（含 BIND_ACCESSIBILITY_SERVICE 与 accessibilityservice 元数据）、`OverlayService`（foregroundServiceType=specialUse）均已注册 |
| 签名 | `apksigner verify --print-certs` | 已签名（debug 证书），可安装 |

### 本轮四项功能的对应证据

**① 重复次数与间隔**（Kotlin 单测 11 项 + Flutter 5 项）
- `normalizeRepeatCountClampsIllegalInput` / `normalizeRepeatIntervalClampsIllegalInput`：0、负数、超大值均被夹取，不抛异常
- `repeatSequenceProducesExactCountThenStops`：完整模拟一轮（5 次 / 间隔 200ms），断言实际触发时刻为
  `base+200, +400, +600, +800` 且到上限后不再触发
- `repeatDecisionStopsAtCountLimit` / `repeatDecisionSingleShotNeverRepeats`：到达上限、以及 `repeatCount=1` 时不再触发
- `repeatDecisionZeroIntervalFiresImmediately`：**间隔 0** 边界可立即连续执行
- `repeatDecisionDoesNotFireBeforeDueTime`：未到时刻不提前触发
- `repeatFireAtUsesAbsoluteOffsets`：第 k 次时刻按绝对偏移计算，非 sleep 累加

**② 修改时间点**（Flutter Widget 测试 6 项）
- 对话框测试：修改模式标题为「修改时间点」、按钮为「保存」、**预填**现有 `repeatCount`/`repeatIntervalMs`
- 改动后保存：输入 6 / 750 → 返回 `repeatCount=6, repeatIntervalMs=750`
- 边界：清空次数点保存 → 报错且**对话框保持打开**、不返回结果、不崩溃
- 边界：输入 999999 → 夹取为 999（`maxRepeatCount`）
- 端到端保存链路：点卡片「修改」→ 改值 → 保存 → 断言发给原生的 JSON 里
  `points[0].repeatCount == 9`、`repeatIntervalMs == 250`，且 `hour` 等其余字段不变

**③ 卡片排版**（真实渲染测量，非估算）
- Widget 测试在 360×800dp 手机尺寸下**同时渲染「修复前结构」与「修复后真实实现」并测量文本盒尺寸**，实测输出：

  ```
  [排版实测] 修复前 宽度=84.0dp 高度=100.0dp | 修复后 宽度=188.0dp 高度=40.0dp
  [步骤行实测] 描述宽度=228.0dp
  ```

  修复前 84dp 宽 × 100dp 高 → 正是「窄列 + 多行」的竖排观感；修复后 188dp 宽 × 40dp 高 → **横向单行**。
  横向可用宽度提升 **2.24 倍**，高度降到 40%（行数减少）。
- 断言：修复后宽度 > 修复前 × 1.5；修复后高度 ≤ 修复前；文本右边界不超出卡片；描述**不与「修改」按钮重叠**。
- 步骤行描述宽度 228dp（修复前 5 个 IconButton 约占 240dp，几乎挤没描述）。

**④ 悬浮窗点击展开设置**（Kotlin 单测 5 项）
- `bubbleSingleTapOpensPanelWhenClosed` / `bubbleSingleTapClosesPanelWhenOpen`：单击在「展开/收起面板」间切换
- `bubbleDragDoesNotOpenPanel`：拖动后抬手**不会**误触面板
- `bubbleLongPressHidesOverlay` / `bubbleLongPressWinsOverDrag`：长按隐藏优先于拖动
- 面板提供：选取位置（**保留原取点入口**）、显示/隐藏悬浮窗、刷新配置、中止执行、打开主界面、收起面板

**⑤ 悬浮窗选点导入（Kotlin 单测 18 项 + Flutter 4 项）**

Android 的 `WindowManager` 悬浮窗无法在 JVM 中渲染，因此把面板的**判定与写入逻辑**抽成纯函数
`TapMath.PickerModel`（与既有 `TapMath.BubbleGesture` 同样的「判定/执行分离」做法），在 JVM 上覆盖：

- 单选落位：`singleSelectionLandsOnlyOnItsOwnPoint` —— 只勾选 1 个时间点时，只有它增加步骤，其余为 0
- 多选批量落位：`multiSelectionLandsOnEverySelectedPoint` / `multipleStepsAllLandInOrder`
- 取消不写入：`emptySelectionWritesNothing` / `emptyStepsWritesNothing` —— 断言返回的是**同一个 Config 实例**（无改动）
- 未勾选不动：`unselectedPointsAreLeftUntouched` —— 断言未勾选的时间点仍是**同一实例**
- 追加语义：`importAppendsToExistingSteps`（原有步骤保留在前、新步骤追加末尾）、
  `repeatedImportAppendsAgainWithoutDedup`（重复导入会再追加一次，与现有行为一致）
- 契约一致：`importedConfigRoundTripsThroughJson`（导入结果经 `Config -> JSON -> Config` 往返后字段不变）、
  `importedStepUsesSameKeysAsFlutterTapStep`（JSON 键名恰为 `x/y/delayMs/sw/sh`，与 Flutter `TapStep.toJson` 一致）、
  `importKeepsTimePointFieldsUnchanged`（时/分/秒/enabled/repeatCount/repeatIntervalMs/tapDurationMs 均不受影响）
- 边界：`unknownIdDoesNotWriteOrCrash`、`duplicateIdsInSelectionWriteOnce`、`toggleAllLabelSwitchesAtFullSelection`

Flutter 侧（`test/overlay_picker_test.dart`）覆盖**接入点与联动链路**：
- `状态卡片存在「选点导入」入口，点击后请求原生打开面板`：点击后依次调用 `showOverlay` + `openPicker`
- `未授权悬浮窗：提示并跳转设置，不打开选点面板`：断言**没有**发出 `openPicker`
- `收到 configChanged 事件后重新读取配置并刷新界面`：原生写入后界面刷新为「1 步」（此前 Flutter 只在启动时读一次配置）
- `取消取点后迟到的坐标事件不会误写入旧时间点`：断言 `saveConfig` **一次都没被调用**，时间点仍为「0 步」

**⑥ 悬浮窗「测试时间点」（Kotlin 单测 22 项）**

同样沿用「判定 / 执行分离」：把面板的**选择与可执行判定**抽成纯函数 `TapMath.TestTarget`
（`SequenceRunner` 依赖 `Handler(Looper)`、悬浮窗依赖 `WindowManager`，二者都无法在 JVM 中运行），
其中 `TestTarget.plan()` 返回**确认执行时实际传给 `SequenceRunner.run` 的参数**，
因此「确认后到底执行了谁的步骤」是被单测直接断言的，而不是靠人眼推断。

- 可选到具体时间点：`tapSelectsThatExactTimePoint`（选中 p2 → 解析出 `12:30:15`）、
  `tappingAnotherRowSwitchesSelection`、`tappingSelectedRowDeselects`（再点一次取消）、
  `hourlyPointIsSelectableAndLabelled`（「每小时」模式可选中）
- 执行的是**所选时间点自己的**步骤：`resolvedPointCarriesItsOwnSteps`、`eachTimePointResolvesToItsOwnSteps`、
  `planCarriesSelectedPointsOwnSteps`（断言 plan 的 steps/label/tapDurationMs）、
  `planDoesNotCrossMergeOtherPoints`（测试 p1 时不得混入 p2 的步骤）
- 未确认 / 取消不触发点击：`noSelectionIsBlocked`、`planIsNullWhenNotConfirmed`、
  `deselectingMakesItUnrunnableAgain`（选择→取消后重新变为不可执行）
- 边界：`pointWithoutStepsIsBlocked`（选中但没步骤也不执行）、`deletedPointIsBlocked`（面板开着时该点被删）、
  `emptyConfigIsBlockedNotCrashing`、`resolveDoesNotMutateConfig`（判定过程不改动配置）

**⑦ 悬浮球状态实时刷新（Kotlin 单测 11 项）**

修复前的**真实缺陷**（有据可查，不是推测）：悬浮球两行文字只在 `OverlayService.stateReceiver` 里渲染，
而它读的是 Intent 的 `a11y` / `running` / `next` extra —— 全仓库 grep `putExtra("a11y"/"next"/"running")`
**0 命中**，即**从来没有任何发送方写入过这些值**，因此恒为默认值：

```
待机(无无障碍)
下次 -
```

且 `ensureOverlay()` 创建时写死 `"待机"`、`exitPickMode()` 也写死 `"待机"`；
看门狗每 2 秒发的 `ACTION_STATE` 是**空载荷**广播，于是又把默认值反复渲染上去。
更糟的是原先的 `panelNext?.text = "下次触发: " + next` 用的也是那个恒为 `"-"` 的值，
等于每 2 秒把面板里**正确**的「下次触发」覆盖成 `下次触发: -`。

修复（判定抽成纯函数 `TapMath.BubbleStatus`，调用方传**实时读取**的真实状态）：

| 位置 | 修复内容 |
| --- | --- |
| `stateReceiver` | 不再读 extra，改为 `refreshBubbleStatus()` 实时取值 |
| `refreshBubbleStatus()`（新增） | `TapperAccessibilityService.isReady()` + `SequenceRunner.isRunning()` + `Scheduler.nextFireInfo()` |
| `ensureOverlay()` | 创建/重建时读当前状态，不再写死「待机」 |
| `exitPickMode()` | 退出取点后按真实状态恢复，不再写死「待机」 |
| `onStartCommand()` | 服务重启/重新显示时立即刷新一次 |
| `nextLine()` | 调度器空值哨兵 `"-"` 渲染为真实语义 `无（没有启用的时间点）`，不再显示「下次 -」 |

刷新触发链（**不重建悬浮窗**）：无障碍 `onServiceConnected` / `onUnbind` → `TapperPlugin.emitState` →
`ACTION_STATE` 广播 → `stateReceiver` → `refreshBubbleStatus()` → 原地改 `tv.text`；
另有看门狗每 2s 兜底（调度状态 `nextFireLabel` 由调度线程每 200ms 维护）。
**未新增轮询**（沿用的看门狗是改动前就有的）。

测试：`a11yOffShowsNoAccessibilityLine` / `a11yOnDoesNotShowNoAccessibility`（验收 1、2）、
`nextEmptySentinelBecomesRealEmptyState`、`nextUsesRealSchedulerValue`、
`repeatedTogglingIsDeterministicWithoutResidue`（连切 50 次结果恒一致）、`switchingA11yNeverLeavesStaleLine`（验收 4）、
`pickModeWinsOverEverything` / `runningWinsOverIdle`（优先级）、`noneSentinelMatchesSchedulerContract`（与调度器契约一致）。

**⑧ 时间点分组（Kotlin 8 项 + Flutter 15 项）**

关键约束（读代码得出，不是推测）：Kotlin 的 `Config` 是持久化的拥有者
（`saveConfig` -> `Config.fromJson` -> `Prefs.save`）。**若 Kotlin 端不认识 `groups`/`groupId`，
Flutter 写进去的分组会在下一次保存时被静默丢弃**，因此本次两端同步修改：

| 端 | 改动 |
| --- | --- |
| Dart | `PointGroup{id,name}`；`TimePoint.groupId`；`TapperConfig.groups`（构造时即保证默认组存在且排最前）、`grouped()`、`groupById()`、`isDefaultGroup()`、`normalizePointGroups()` |
| Kotlin | `PointGroup`；`TimePoint.groupId`；`Config.groups`（`toJson` 与读盘两侧都规范化）、`DEFAULT_GROUP_ID`；读盘时把指向已删除分组的时间点回落到默认组 |

证据：`groupsSurviveJsonRoundTrip`、`jsonContainsGroupsAndGroupId`、
`legacyJsonWithoutGroupsFallsBackToDefaultGroup`（旧数据兼容）、
`pointPointingToMissingGroupFallsBackToDefault`（不产生孤儿时间点）、
`defaultGroupIsHoistedFirstEvenIfListedLater`、`renamedGroupKeepsIdAndIsPersisted`、
`允许空分组`、`删除分组会连同组内时间点一起删除`、`损坏的 JSON 不抛异常`；
UI 侧 `test/groups_ui_test.dart`：按分组归类展示、空分组也显示、新建分组并持久化、
**重命名后页面立即同步且旧名不残留**、默认组删除项禁用、原生改名后经 `configChanged` 同步。

**⑨ 页面留白与上方布局重排（`test/layout_test.dart` 5 项，真实渲染实测）**

用「改造前结构」与「改造后真实实现」同屏对比实测（沿用项目既有对比测法）：

```
[布局实测] 时间点起始 y：改造前 333.0dp -> 改造后 291.0dp（上移 42.0dp，约 13%）
[布局实测] 800dp 屏首屏可见时间点数量 = 12 / 12
```

- 状态详情（下次触发/系统时区）收进可折叠区，默认收起，可展开再收起
- 状态卡片 4 个次要入口收进「设置」弹出菜单，**入口一个都没少**（测试逐个断言可达）
- 运行日志区默认收起（原先固定 220px 恒常展开），标题与条数照旧可见、可展开
- 按用户确认「不用加宽，保持原样」，**未做**宽度/多列改动

**⑩ 时间点长按菜单 / 复制 / 三处默认值（Flutter 12 项）**

改动（`lib/main.dart`、`lib/models.dart`）：

| 项 | 改动 |
| --- | --- |
| 长按菜单 | `_pointCard` 外层加 `GestureDetector.onLongPressStart` -> `_showPointMenu`（`showMenu` + `PopupMenuItem`，沿用项目既有菜单方案），含 修改/分组/上移/下移/复制/删除 六项 |
| 原入口 | 卡片展开区内的 5 个按钮栏**整段移除**（按确认「不保留新旧两套入口」） |
| 复制 | 新增 `_duplicatePoint`：逐字段显式复制 hour/minute/second/steps/repeatCount/repeatIntervalMs/groupId；**enabled 强制 false**（按确认）；id 由构造函数既有规则生成；插入原项之后 |
| 默认值① | `TimePoint.enabled` 默认 `true` -> `false`（新建默认关闭） |
| 默认值② | `_pointCard` 的 `initiallyExpanded` `true` -> `false`（组内时间点默认收起） |
| 默认值③ | `_groupSection` 的 `initiallyExpanded` `true` -> `false`（分组默认收起，按用户追加要求） |

**旧数据兼容未被波及**：`TimePoint.fromJson` 在 JSON 缺 `enabled` 时仍回落 `true`（既有兼容行为），
已由 `模型层：TimePoint 默认 enabled=false，但旧数据缺字段仍回落 true` 单测锁定。

证据（`test/point_menu_test.dart` 12 项，真实渲染 + 断言发给原生的 JSON）：
`长按时间点弹出六项菜单`（验收1）、`卡片内已无五个直接按钮`（不并存）、
`首项的上移、末项的下移在菜单中禁用`（禁用态等价迁移）、`菜单「上移」/「删除」沿用原有交换逻辑`、
`复制后同分组出现内容一致的新时间点`（验收2，逐字段断言）、`复制项插入在原项之后`、`复制不改动原项`、
`新建时间点后其状态为关闭`（验收3）、`分组默认收起`、`展开分组后组内时间点为收起状态`（验收4，
并验证点开后内容仍在）。

> 说明：本轮把 5 个直接按钮迁入长按菜单后，既有 10 项测试因**断言旧行为**而失败，
> 已同步更新为新预期（改为先展开分组/卡片、或改走长按菜单），**非**回归。

**⑪ 悬浮窗实时时间 / 时间源 / 手动微调（Kotlin 27 项 + Flutter 5 项）**

| 能力 | 实现 | 精度来源 |
| --- | --- | --- |
| 实时时间显示 | 悬浮球新增时间行（等宽字体），格式 **`时:分:秒:百毫秒`**（百毫秒一位，如 `22:33:44:5`） | 取百毫秒位截断值，与 100ms 周期对齐，每秒稳定跳 10 次 |
| 刷新机制 | `OverlayService.timeTicker`：主线程 Handler 每 **100ms** 只重绘时间行（不触发整球重绘） | 复用在位时间，未新增轮询线程 |
| 本地时间 | `System.currentTimeMillis()` | 设备系统时钟（由系统/运营商自动对时） |
| 北京时间 | SNTP 对时（`SntpClient`，JDK 自带 DatagramSocket，**无第三方依赖**），保存「服务器时间−设备时间」偏移 | NTP 四时间戳算法抵消单程延迟；**实测 3 台服务器偏差一致**（9907/9921/9904ms，互差 ≤17ms），RTT 33~84ms |
| 对时策略 | 服务启动/切到北京时间时对时一次 + 每小时自动重对；失败保留上次偏移并标记状态，**从未成功时显示「不可用」而非伪造时间** | — |
| 手动微调 | 面板 `−100ms / +100ms / 归零`，步长固定 100ms，默认 0，可正可负 | 显示值 = 时间源基准 + 微调 |
| 持久化 | 复用既有 `Prefs/Config`（SharedPreferences）新增 `timeSource` / `timeOffsetMs`，Dart 端同步镜像 | 旧数据缺字段回落 local + 0 |

**开发中由真实网络实测发现的缺陷（已修）**：SNTP 报文解析曾把 24..31（ORIGINATE，
服务器回显的我方发出时间）误当成 t2，实测解算出 `offset=-1999558208128ms` 这种量级离谱的值。
已修正为 t2=32..39(RECEIVE)、t3=40..47(TRANSMIT)，并把解析抽成纯函数
`TapMath.parseSntpResponse` 用真实字节布局单测锁死
（`parseSntpResponseReadsReceiveAndTransmitNotOriginate`）。
另修正写入侧 NTP 小数位截断导致的 1ms 误差（改为四舍五入）。

> **如实说明**：用户已确认「荣耀时间与本机时间相同则不需要单独的荣耀时间」，
> 因此**未提供**第三个假源（避免三源退化成同一个时钟）。当前为 **本地时间 / 北京时间** 两个真实独立的源。

**⑫ 本轮五项修改（Kotlin 48 项 + Flutter 21 项）**

| 项 | 实现 | 关键位置 |
| --- | --- | --- |
| ① 时间加小时 | 格式改为 `时:分:秒:百毫秒`；**带时区**（北京时间固定 UTC+8，不随设备时区变） | `TapMath.ClockFormat.format(epoch, zone)`、`TimeSourceKind.zone()` |
| ② 倒计时 1 秒一跳 | 新增 `nextTicker`（1000ms）专门刷新「下次」行 | `OverlayService.nextTicker` |
| ③ 时/分/秒 单选/多选/全选 | 芯片网格 + 全选/清空；`TimePoint` 新增 `hours/minutes/seconds` 集合；`dueKey`/`nextTriggerAt` 按**组合展开**判定；label 中文分隔 + 连续区间压缩 | `TapMath.TimeSets`、`Model.kt`、`dialogs.dart` |
| ④ 时间源+微调影响点击时刻 | 新增 `Scheduler.effectiveNow()`：命中判定/倒计时/下次触发全部改用「时间源 + 微调」轴 | `Scheduler.effectiveNow` |
| ⑤ 日志口径 | 行首时间戳仍是**未微调系统时间**；正文计划/实际按所选时间源展示；偏差按「时间源、不含微调」计算并注明 | `TapMath.TimeContext.sourceDrift/driftNote`、`SequenceRunner` |

**② 的根因**：倒计时数值由 Scheduler 每 200ms 重算，但**显示**过去只被 2 秒的 watchdog 触发，
所以肉眼是「约 2 秒跳一次」。现单独加 1 秒刷新，watchdog 职责不变（它管悬浮窗丢失恢复）。

**③ 语义**（用户确认 A）：一个时间点可含**多个触发时刻**（时×分×秒 笛卡尔积），
例如小时选 8、12 + 分 30 + 秒 0 → 在 08:30:00 与 12:30:00 各触发一次。
label 例：`08,12时 30分 00秒`、`每小时 00-10分 03秒`、`全部小时 30分 00秒`。
**组合数上限 512**（24×60×60=86400 会被拒绝并提示），避免一个时间点产生几万次触发。

**④ 与 ⑤ 的关系**：计划时刻与实际时刻同处「时间源+微调」轴，微调在做差时**互相抵消**，
因此「偏差按不含微调的时间源计算」与在有效轴上做差**结果完全一致**——
这正是 `sourceDrift` 的实现依据，已由 `sourceDriftIsIndependentOfManualOffset` 等单测锁定。

**北京时间未就绪时的调度**（用户确认预案 A）：回落到本地时间继续调度，
并在日志明确标注「未就绪，本次调度回落到本地时间」，对时成功后自动恢复——不静默假装。

> 本轮同时修正了 6 处既有测试对旧 label 格式（`08:00:00`）的断言，
> 以及 `layout_test` 里手写标签格式的问题（改为直接取模型 label，避免格式一变就假失败）。

测试总量：**Kotlin 142 项 + Flutter 83 项 = 225 项，全部通过**。

### 未能验证的部分（如实说明）

本机**没有可运行的 Android 环境**（无 USB 设备、无 WSA、未装模拟器），且用户选择「只交付 APK，我自己装自己验」。因此：

- ❌ **未做真机点击注入验证**（「在真实页面点击生效」没有运行证据）
- ❌ **未做真机悬浮窗显示与面板展开验证**（Android 的 `WindowManager` 悬浮窗无法在 JVM/Flutter 测试环境渲染）
- ❌ **未实测定时触发偏差**（缺少真机上的实测偏差数据）
- ❌ **未做端到端全流程验证**
- ❌ **未做真机「选点导入面板」显示与点击验证**：面板的复选框、全选/取消选择、确认/取消按钮属 `WindowManager` 行为，
  与既有设置面板同一限制；**判定与写入逻辑已被 22 项自动化测试覆盖**，但「面板真的画在屏幕上、能点中」仍需真机确认

以上四项属于**必须真机才能取得的证据**，已按你的选择交由你安装后验证。

本轮四项功能的验证边界（如实说明）：
- ①重复触发、②修改保存：**逻辑与 UI 已被 56 项自动化测试覆盖**，但「真机到点后确实按次数点了几下」仍需真机确认。
- ③卡片排版：**用真实 Flutter 渲染测得实际尺寸**（见上），不是估算，可信度高。
- ④悬浮窗点击：手势**判定逻辑**已单测覆盖；但「面板真的显示在屏幕上、按钮可点」属 `WindowManager` 行为，必须真机确认。

---

## 九、已知限制

- 点击依赖**无障碍手势**。部分应用会主动屏蔽无障碍注入（金融类 App 的防无障碍模式、部分游戏的注入检测），
  此时点击不会生效——这属于系统/应用侧限制，本工具无法绕过。
- 息屏后能否准时触发取决于厂商后台策略。本工具使用前台服务 + 看门狗，但**未申请唤醒锁**（避免额外耗电与权限），
  极端省电策略下仍可能延迟。
- 仅针对**本机屏幕**坐标，未适配多屏/投屏。
- APK 使用 **debug 证书**签名，仅供自用安装；正式分发需替换为自己的签名。
- 重复触发的每轮之间**不跨轮累加**：若一次序列耗时超过 `repeatIntervalMs`，实际节奏会顺延到上一条结束后立即执行
  （这是为保证不漏次数而做的取舍，日志会打印「等上一条序列结束后立即执行」）。
- 未做 UI 美化、云同步、账号系统（按需求明确排除）。
