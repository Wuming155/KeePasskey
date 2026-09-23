# §284 LongParameterList 四处参数对象化与展示层 String 限界补全批次

> 本批次由 2026-09-23 全仓工程卫生梳理触发（**非**既有条目整改中的独立闭环项），
> 收口两件：① 4 处 `@Suppress("LongParameterList")` 压制未拆；② 展示层 `readString()` /
> `String` 驻留挂在 `ISSUE-P3-303` 而限界登记不完整。①为**纯结构性**参数对象化；
> ②为**文档面**限界登记补全（代码一行未改）。

## §1 背景

### 1.1 LongParameterList

四处以 `@Suppress("LongParameterList")` 压制而非拆参：

| 文件 | 函数/类 | 原参数数 |
|---|---|---|
| `KeePasskeyNavGraph.kt` | `keepasskeyNavGraph` | 6 |
| `SettingsUiStateProjection.kt` | `settingsUiStateFlow` | 13 |
| `VaultListProjection.kt` | `buildVaultListUiState` | 6 |
| `VaultListActionController.kt` | 构造器 | 9 |

工程规则软阈值「参数 ≤ 4」；压制使规则对这四处**失效**，且 13 / 9 参的可读性已越线。

### 1.2 展示层 String 驻留

`ISSUE-P3-303` 将不可擦 `String` 分两类（导出侧可解 / 模型层不可在导出器层解），但
**展示层**（`GeneratorScreen` 的 `readString()`、`EntryDetailSecrets` 的 `toDisplayString()`
与三个 `String` 状态）只在 §2.4 / §2.6 交叉提及、**未单列**——「限界登记仍不完整」。

## §2 整改内容

### 2.1 四处参数对象化（纯结构性，行为零变化）

| 原 | 新参数对象 | 收工参数数 |
|---|---|---|
| `keepasskeyNavGraph(6)` | `NavGraphHostContext`（navController / motion / themeMode / toggleTheme / killAppAction / autoLockManager） | **1** |
| `settingsUiStateFlow(13)` | `SettingsUiStateFlows`（十路输入流） | **4**（scope / timeoutMillis / flows / strings） |
| `buildVaultListUiState(6)` | `VaultListLibraryState`（databases / allGroups / allEntries） | **4** |
| `VaultListActionController(9)` | `VaultListActionHost`（五条宿主回调） | **5**（repository / scope / strings / clipboard / host） |

四处 `@Suppress("LongParameterList")` **全部摘除**。调用点仅生产三处
（`KeePasskeyApp` / `SettingsViewModel` / `VaultListViewModel`），无单测直调签名。
`VaultListActionController` 体内以 `private val isReadOnly get() = host.isReadOnly` 等
转发保持方法体逐字不动。

### 2.2 限界表新增 §2.7（展示层 + 模型层 String 驻留）

`docs/architecture/已知工程限界.md` 新增 **§2.7【客观限界】**，单列并分层：

1. **展示层**：`GeneratorScreen.kt:163-165` / `:278` 的 `remember { readString() }`；
   `EntryDetailSecrets.kt:19-25` 三个 `String` 状态 + `:62-68` `toDisplayString()` 物化点。
2. **模型层**（`ISSUE-P3-303` 第 2 类）：`KdbxEntry` 的 `title` / `userName` / `url` / `notes`
   与 `KdbxGroup.name` / `notes` 以 `String` 驻留。

与 §2.4（主密码 `TextField`）、§2.6（平台边界瞬时副本）**分列**，解除条件各自写明。
`ISSUE-P3-303` AC⑤ 加「2026-09-23 §284 补全登记」注记：登记分支已落地，
**第 1 类（导出侧 `useChars`）仍未实施，该条继续开放**。

## §3 验证（一律现跑）

| 判据 | 命令 | 读数 |
|---|---|---|
| JVM 单测 | `.\gradlew.bat test --rerun-tasks --max-workers=1` | **BUILD SUCCESSFUL**（两轮，2m23s / 2m44s）；`count_test_results.py` = `xml=374 tests=2590 failures=0 errors=0 skipped=13`（与 §278/§279 **三项全持平**，零测试增减） |
| Suppress 清零 | 全仓扫 `@Suppress("LongParameterList")` | **0 命中** |
| 规模闸门 | `count_line_tiers.py` / `long_functions.py` | EXIT 0 |
| 文档链接 | `check_md_links.py` | `BROKEN_MD_LINKS=0` |
| 归档索引 | `check_resolved_index_sync.py` | `OK` |
| 重言 / 复核 | `check_tautological_assertions.py` / `check_recheck_consistency.py` | 0 命中 / PASS（1331 行 / 11 条） |

## §4 如实声明

- 参数对象化为**纯结构性**：公开行为 / 敏感擦除次序零变化；未改任何测试资产。
- **展示层 / 模型层 `String` 驻留未消除**（§2.7 如实登记为客观限界）；导出侧 `useChars`
  （`ISSUE-P3-303` 第 1 类）**仍未实施**，该 ISSUE 继续开放。
- 未触 `crypto/src/main/rust/**` / `*/src/androidTest/**` / `参考项目/` ⇒ **无设备侧必跑项**。
- 未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍 / 真机。
- 过程留痕：首轮编译因 `inputs`/`flows` 参数名不一致红、`ACTIVE_ISSUES` 注记曾重复插入
  一次（并行 edit），均已就地修正；全量 test 曾因并行重复启动超时中断，**以两轮完整
  BUILD SUCCESSFUL 的读数为准**。

## §5 改动清单

| 路径 | 性质 |
|---|---|
| `app/.../ui/KeePasskeyNavGraph.kt` + `KeePasskeyApp.kt` | `NavGraphHostContext` + 调用点 |
| `app/.../settings/SettingsUiStateProjection.kt` + `SettingsViewModel.kt` | `SettingsUiStateFlows` + 调用点 |
| `app/.../vault/VaultListProjection.kt` + `VaultListViewModel.kt` | `VaultListLibraryState` + 调用点 |
| `app/.../vault/VaultListActionController.kt` + `VaultListViewModel.kt` | `VaultListActionHost` + 调用点 |
| `docs/architecture/已知工程限界.md` | 新增 §2.7 |
| `docs/ACTIVE_ISSUES.md` | `ISSUE-P3-303` AC⑤ 补全登记注记 |
| `docs/resolved/batches/284-LongParameterList参数对象化与展示层String限界补全批次.md` | 本文件 |
| `docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md` | 归档索引 |
