# §183 Argon2 参数对话框段落拆分批次（`ISSUE-P3-188` 第 3 目：`Argon2ParametersDialog` 168 → 80 行）

> **起因**：剩余清单第 1 项（Compose 面 ≥100 行函数，28 个）里**唯一一份「全仓测试零引用」**的
> 中位长函数——`subscreens/DatabaseSettingsArgon2Dialog.kt`（函数 168 行）。
> §182 结案后回到本目，先挑**无守卫、无设备依赖**的目标，把 §178 的段落组件配方再走一遍。

---

## 1. 改动

| 对象 | 行数 | 内容 |
|---|---|---|
| `subscreens/DatabaseSettingsArgon2Dialog.kt` | 241 → **137** | 本体只留：三个临时参数的 `remember` 状态、`AlertDialog` 骨架、标题行、基准按钮、确认 / 取消，以及**全部状态写入**（含 `tempIterations--` 与推荐参数回填） |
| `subscreens/DatabaseSettingsArgon2DialogSections.kt` | 新建 **201** | 四个段落组件：`Argon2IterationsStepper` 45、`Argon2MemoryChipRow` 22、`Argon2ParallelismChipRow` 22、`KdfBenchmarkResultSection` 40，外加两个 `private` 档位表常量与随迁的 `ITERATIONS_MIN/MAX` |

**函数长度**：`Argon2ParametersDialog` **168 → 80 行 ⇒ 出表**，≥100 行 Compose 函数 **28 → 27 个**。
新拆的四个函数最长 45 行。`@OptIn(ExperimentalLayoutApi::class)` 与 9 条 import 随 FlowRow / 芯片
一并迁入段落文件（本体已无实验性 API 调用 ⇒ 死注解剪掉）。

## 2. 随迁的两处常量命名（第 4 目的顺手收口）

原函数体内两处内联档位表按第 4 目口径提到段落文件的私有常量：

| 原写法 | 现写法 | 值 |
|---|---|---|
| `listOf(16L, 32L, 64L, 128L, 256L).forEach { mb -> … }` | `MEMORY_MB_OPTIONS.forEach { mb -> … }` | **完全一致**（未改任何档位） |
| `listOf(1, 2, 4, 8).forEach { threads -> … }` | `PARALLELISM_OPTIONS.forEach { threads -> … }` | **完全一致** |

`ITERATIONS_MIN` / `ITERATIONS_MAX` 两个常量随步进器迁至段落文件（仍是 `private`，
本体内已无引用）⇒ **不新增跨文件可见性**。

## 3. 唯一的行为相关点：`LaunchedEffect` 的回填搬家（逐项核对）

`KdfBenchmarkResultSection` 承接原「M6 基准状态展示」段，其中把推荐参数自动填入上方调节项的
`LaunchedEffect` 是**唯一带副作用的片段**，逐条核对：

| 维度 | 原实现 | 现实现 | 判定 |
|---|---|---|---|
| 组合条件 | `benchmarkState != null` 且 `recommendedIterations != null` | 段落内同一双重判定（段落总在被组合，判定在其体内） | 等价 |
| effect 键 | `recommendedIterations` | 同 | 等价 |
| 触发时机 | 键变化 / 进入组合时重启；离开 `?.let` 分支即随组合取消 | 同（子组合离开同样取消） | 等价 |
| 状态归属 | 直接写对话框的 `tempIterations` / `tempMemoryMb` / `tempParallelism` | 段落调 `onApplyRecommended(iterations, memoryMb, parallelism)`，**写入仍全在本体 lambda 里** | 状态未外迁 |
| 展示文本 | `dbset_benchmark_result` 三参数（缺失时 `?: 0L` / `?: 0`） | 逐字搬移，缺省值未变 | 等价 |

⇒ 本批**不是纯 UI 搬家**，这一条是其中风险最高处，故单列。它**没有**任何宿主用例覆盖
（该对话框零测试引用），证据只有「编译 + 逐项人工核对 + 套件回归」；
真实渲染与回填时机仍需设备 / 截图基准确认（见 §5 的未执行登记）。

## 4. 逐字性核对

`python tools/doc/check_verbatim_move.py <原文件> <本体> <段落文件>`：

- 原文件内容行 **165** 种；
- 找不到的 **20** 种，逐条归类：
  - **15 处形参改名 / lambda 外提 / 本地别名删除**：`tempMemoryMb` → `memoryMb`、`tempIterations` → `iterations`
    之类（步进器 `enabled` 两条、两个芯片行的 `selected` / `onClick` 各两条、两个芯片行的 label 文案实参、
    回填三行、`rounds_value` 文案实参），其中 `val benchmarkState = kdfBenchmarkState`
    这一**本地别名**被删除（段落组件直接收 `kdfBenchmarkState`，判定与取值时序不变）；
  - **2 处档位表常量化**（§2 表，值未变）；
  - **3 处注释改写**：ISSUE-P3-136 的两行与「触及 1 / 50 边界时置灰」一行——**说明未删除**，
    是随代码并入 `Argon2IterationsStepper` 的 KDoc 并改写为组件口径。
    如实登记：这与整改纪律 ⑤「不得把注释移出文件凑瘦身」不冲突（注释跟着代码进了同一模块的段落文件，
    仓内注释总量未减），但**它确实不是逐字搬运**，故列在此处而不是藏在「纯结构性改动」一句话里；
- 新侧独有 **66** 种行：函数签名、形参、调用点、KDoc 与两行常量声明。

### 4.1 一次差点混过去的脚本缺陷（顺序错置 ⇒ 删除静默落空）

切片脚本里五处替换按「自底向上」写，但**最后一项 `(237, 240)`（删除随迁走的 `ITERATIONS_MIN/MAX`）
其实是最高的行号**，被放在了最后执行——前四处替换已把文件从 241 行缩到 152 行，
`lines[236:239] = []` 落在列表之外，**成为一次空操作**：

- 编译器**不报错**（本体里那两个 `private const val` 只是未被使用，Kotlin 报 warning）；
- 套件也**全绿**（无人读该文件文本，无断言涉及它们）；
- 是**量出来的**：段落文件写完后按「文件内声明清单」复核（`grep -n "ITERATIONS_"`）才发现两处定义，
  即同一对合法域常量在两个文件里各存一份——**恰好是本目第 4 项要消掉的重复**。已补删（137 行终值）。

教训两条：**多段替换必须严格按起始行降序执行**（本批前四处是降序、第五处破序即是 bug）；
「删掉的声明」必须用 `grep` 复查残留，**不能以「编译通过 + 测试全绿」充当删除成功的证据**。

## 5. 验证

| 命令 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | `BUILD SUCCESSFUL`（搬动后先编译；补删常量后再编译一次，仍绿） |
| `test --rerun-tasks --max-workers=1` | `BUILD SUCCESSFUL in 2m 17s` / `114 actionable tasks: 114 executed`；聚合 **tests=2198 failures=0 errors=0 skipped=13**（312 份 XML），`Uncaught exception` 命中 **0** |
| `:app:testDebugUnitTest --rerun-tasks`（**终态复跑**） | `BUILD SUCCESSFUL in 1m 35s` —— 全量套件跑在「补删 4 行死常量」之前，故对终态再单独复跑一次 `:app:` 层，避免拿旧状态的绿当新状态的证据 |
| `:app:lintDebug` | `BUILD SUCCESSFUL`；按 §182 更正后的口径统计 `issue` 元素 = **215**（与基线同一把尺，**无新增**） |
| `:app:compileDebugScreenshotTestKotlin --rerun` | 任务**确实执行**、`BUILD SUCCESSFUL`（对话框的 `@Preview` 包装未动） |
| `python tools/doc/long_functions.py` | **`functions_ge_100=27`**（原 28，`Argon2ParametersDialog` 168 → **80** 出表）；`files_scanned=503` |
| `python tools/doc/count_line_tiers.py` | 第一档 2、第二档 **29**（本体 137 / 段落 201，均 <400） |
| `python tools/doc/check_verbatim_move.py` | §4 的 165 / 20 / 66 三组数字 |
| `python tools/doc/check_md_links.py` | `BROKEN_MD_LINKS=0` |
| **未执行** | `cargo test`、`test -DliveSyncTest`、四层 `connectedDebugAndroidTest`（无设备） |

**证据强度的如实边界**：该对话框**在全仓测试里零引用**（§1 起因即以此选它），故本批的自动化证据只有
「编译 + 套件回归（不受影响项）+ 包装编译」，**没有任何一条断言直接盯着这四个段落组件**；
`KdfBenchmarkResultSection` 的 `LaunchedEffect` 回填（§3）更是只有人工核对。
若要把它变成可断言面，需要 Compose UI 测试或 Robolectric 组合（仓内目前无该层设施）——
属独立一段，不在本批范围。

## 6. 下一批

第 3 目余 **27** 个 ≥100 行 Compose 函数（清单见 181 §3，本批已把 28 削到 27）。下一批候选（**均已实测引用面**）：

1. `AboutSettingsScreen` 153 行——**全仓测试零引用**，与本批同型，最省事；
2. `UnlockContentSections.kt::UnlockStandardUnlockContent` 158 行——**兼属第二档**（该文件 430 行），
   一次改动可同时削两个维度，但文件被 `UiMd3AlignmentWiringTest` 点名读文本 ⇒ 须先做 §179 式并集扩扫；
3. `ConflictResolutionScreen` 172 被 `ObscuredTouchWiringTest` 点名、`EntryDetailTopBar` 151 被
   `PopupSecureFlagInventoryTest` 点名、`ChildDatabaseDialogs` 170 被 `SecureDialogFlagPolicyTest` 点名
   —— 三者都要先核守卫锚点再动手（勿只搬一半）。
