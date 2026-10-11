# §486 协程 `runCatching` 取消语义收口并机检批次（2026-10-11）

**条目**：`ISSUE-P3-570`（**整条闭环**）
**来源**：`ISSUE-P3-569`（§484）闭环时的判据边界声明——`catch` 面机检只钉子句形态，
`runCatching` 同型吞取消（`CancellationException` 继承 `IllegalStateException`）但**不在该判据面**。
**编号说明**：`resolved/README.md` 登记的「下一批次」为 §486（本批取用）。

## 0. 条目原文（登记即闭环，**原样收录**；`ACTIVE_ISSUES.md` 已整条剪切）

> ### ISSUE-P3-570：`runCatching` 的协程取消语义与 `catch` 面同型、**未逐一收口**（`ISSUE-P3-569` 闭环时的判据边界）
>
> - **现象**：`kotlinx.coroutines.CancellationException` 在 JVM 上是
>   `java.util.concurrent.CancellationException` 的别名、继承 `IllegalStateException` ⇒ 裸 `runCatching { }`
>   与裸 `catch (Throwable)` **同型**地吞掉取消。`ISSUE-P3-569`（§484）的机检判据只钉 `catch (…)` 子句，
>   已在脚本文档串显式声明 **`runCatching` 不属本判据面**；`sync` 模块侧的 `runCatchingCancellable`
>   （`ISSUE-P3-555`，§482）只覆盖了 provider / 上传重试与 `markResolvedAndUpload` 数处。
> - **核实时间点 / 方式**：2026-10-09，**静态启发式普查**（临时脚本，一次性分析、未入库）：五模块 `src/main`
>   的 `runCatching` 内联调用共 **80 处**，其中 **30 处**落在协程上下文；按「保护段内出现 `suspend fun`
>   名（排除同名非挂起者）或 `.first(` / `.collect` 等已知挂起调用」进一步筛出 **16 处**——**该数字含已知误报**
>   （例：`sync/.../S3SyncProvider.kt` / `WebDavSyncProvider.kt` 的命中实为已收口的
>   `runCatchingCancellable`，被脚本的子串匹配误计；`SafKeyFileAccess.kt:79` 的保护段只是
>   `Uri.parse`），**故不得当缺陷清单**。
> - **影响**：与 `ISSUE-P3-569` 同——取消被归一为业务失败 / 默认值后，上层按失败处置。
> - **涉及文件**：`app/src/main/**`、`database/src/main/**`、`sync/src/main/**`、`core/src/main/**`（候选以现跑为准）
> - **验收标准**：① 逐处判定并为协程上下文中「保护段含挂起点」的 `runCatching` 换用**等价的不吞取消**写法
>   （可复用 `runCatchingCancellable` 的口径，或就地补 `is CancellationException` 判定）；
>   ② 对判为不适用者登记理由（不得静默跳过）；③ 评估把该面并入 `check_cancellation_semantics.py`
>   （或另立机检），**判据须与 `catch` 面同款避开「前置取消分支」误报**，并以 `--selftest` 正反样本反校。

## 1. 整改

### 1.1 前提复核与判定流程

1. **前提复核**：原条目的「80 处 / 30 协程 / 16 含挂起点（含已知误报）」出自一次性启发式脚本。
   本批**重跑**普查（五模块 `src/main`，裸 `runCatching` 调用，排除注释与 `KdbxResult` 同名**声明**）得
   **65 处**，启发式语境筛出 **20 处**协程语境 ⇒ 「80」已因 §482 / §484 整改缩水，前提以现跑为准。
   原「16 处」启发式**未采信为清单**（其已知误报——provider 侧 `runCatchingCancellable` 子串误计、
   `SafKeyFileAccess` 的 `Uri.parse` 纯 CPU 保护段——均未进入本批命中集）。
2. **权威候选集改由结构判据机检给出**：先扩展机检（§2）并跑**红**，命中 **23 处**
   （runCatching 面 **21** + catch 面 **2**——后者是判据补 `LaunchedEffect` 后现出的 §484 漏网点），
   与逐处人工读码判定**完全吻合**（红 23 → 绿 0 即判据有效性证据，§484 同法）。
3. **逐处判定**：对每处读原码判断「保护段内是否真含挂起点」——含挂起点 ⇒ **换不吞取消写法**（§1.2）；
   不含挂起点（纯 CPU / 阻塞式 IO / 非挂起回调）⇒ 取消在本保护段不可观测 ⇒ **判为不适用**，
   就地标注 `cancel-n/a:`（§1.3）。

### 1.2 换不吞取消写法（**命中集内 6 处 + 命中集外声明面 1 处 = 7 处 / 7 文件**）

| 模块 | 文件（点位） | 面与所在语境（保护段挂起点） | 整改 |
|---|---|---|---|
| app | `autofill/AutofillPickerActivity.kt:276` | RC；`lifecycleScope.launch` 内 `getKdbxEntry`（挂起） | 链 `.onFailure { if (it is CancellationException) throw it }` |
| app | `autofill/AutofillPickerViewModel.kt:137` | RC；`offerAppBindingWriteBack` 内 `getKdbxEntry`（挂起） | 同上 |
| app | `autofill/AutofillPickerViewModel.kt:199` | RC；`resolveCredentials` 内 `getKdbxEntry` + `resolveFieldReferences`（均挂起） | 同上 |
| app | `ui/screens/settings/SettingsDatabaseMetaController.kt:44` | RC；`scope.launch` 内 `session.save()`（挂起，`mutex.withLock` 写盘） | 同上 |
| app | `ui/screens/edit/TotpScanDialog.kt:316` | **catch**；`LaunchedEffect` 内 `awaitOn`（挂起等待）——**§484 漏网点**（当时判据无 `LaunchedEffect` 构造） | catch 体最前补 `if (t is CancellationException) throw t` |
| sync | `webdav/WebDavDirectoryList.kt:41` | RC；`withContext(IO)` 内 `execute(request)`（**挂起 lambda 参数**，provider 瞬时重试封装） | 裸 `runCatching` 换 `runCatchingCancellable`（同模块内复用） |
| core | `result/KdbxResult.kt:82` | **声明面**（机检 KDoc 早已声明的「自定义包装间接吞取消」盲区）：伴生同名 `runCatching` 把 `Throwable` 一律归一为 `Failure`；现无生产调用方（仅 3 处测试），属潜在陷阱 | 补 `if (t is java.util.concurrent.CancellationException) throw t`（core 无协程依赖，用 JVM 别名类判型，与 `kotlinx.coroutines.CancellationException` 同一类型） |

配套测试资产（**只增不改不删**）：
`core/src/test/.../KdbxResultTest.kt` 新增「`runCatching` 不吞协程取消，CancellationException 原样重抛」
（断言**同一实例**沿链重抛而非归一为 `Failure`）；
`sync/src/test/.../CancellationRethrowWiringTest.kt` 的「不得再出现裸 `runCatching`」静态接线面
纳入 `WebDavDirectoryList`（`S3DirectoryList` 不纳入：其 `execute` 实参非挂起，已判 N/A，理由写入该测试 KDoc）。

### 1.3 判为「不适用」并就地标注 `cancel-n/a:`（**17 处 / 11 文件**）

判据＝保护段内**无挂起点** ⇒ 取消只会在该保护段边界外观测，补守卫即死代码。标注随代码走，
新增同型点不会被静默放行（fail-closed）；标注位置为调用行尾或**紧邻上一行**（机检 `_marked` 只回看一行）。

| # | 文件（点位） | 判为不适用的理由（保护段为何不含挂起点） |
|:--:|---|---|
| 1 | `data/repository/SafVaultCreation.kt:66` | `takePersistableUriPermission` 为阻塞式 SAF 调用 |
| 2 | `data/repository/SafVaultCreation.kt:94` | `openOutputStream` + `write`/`flush` 为阻塞式写盘 |
| 3 | `data/repository/SafVaultCreation.kt:134` | `finally` 清理段 `tempFile.delete()` 为阻塞式 |
| 4 | `data/repository/VaultDatabaseCatalog.kt:173` | `File.canonicalPath` 为阻塞式路径解析 |
| 5 | `data/repository/VaultDatabaseCatalog.kt:177` | 同上 |
| 6 | `notification/UnlockedNotificationCopyAction.kt:106` | `clipboard.copyPlainText` 为非挂起接口方法 |
| 7 | `notification/UnlockedNotificationCopyAction.kt:120` | `clipboard.copySensitiveText` 为非挂起接口方法 |
| 8 | `ui/components/AppPickerDialog.kt:68` | `launchableApps` 为阻塞式 PackageManager 枚举 + 图标解码 |
| 9 | `ui/screens/edit/EntryEditPickers.kt:173` | `openAssetFileDescriptor` 为阻塞式 SAF 调用 |
| 10 | `ui/screens/unlock/SafKeyFileAccess.kt:45` | `extendedSettingsStore.load()` 为非挂起同步读取 |
| 11 | `ui/screens/unlock/SafKeyFileAccess.kt:53` | `Uri.parse` 为纯 CPU 解析 |
| 12 | `ui/screens/unlock/SafKeyFileAccess.kt:81` | `Uri.parse` 为纯 CPU 解析 |
| 13 | `ui/screens/unlock/SafKeyFileAccess.kt:103` | `Uri.parse` 为纯 CPU 解析 |
| 14 | `ui/screens/unlock/SafKeyFileAccess.kt:104` | `persistedUriPermissions` 查询为阻塞式 Binder 调用 |
| 15 | `ui/screens/unlock/UnlockScreenPreviews.kt:298` | `requestFocus()` 为非挂起 UI 调用（`LaunchedEffect` body 虽属挂起语境） |
| 16 | `sync/s3/S3DirectoryList.kt:46` | `buildBucketRootUrl` / `sign` / `readBounded` / `parseListObjects` 全非挂起；`execute` 实参是非挂起的阻塞式 OkHttp `newCall().execute()`（与 WebDav 的挂起 lambda 实参成对照） |
| 17 | `ui/screens/edit/TotpScanDialog.kt:295` | 该 catch 位于**非挂起** `ImageAnalysis.Analyzer` 回调（`decodeExecutor` 线程），`decodeQrFrame` 纯 CPU 解码，取消不可达 |

## 2. 机检变更（`tools/doc/check_cancellation_semantics.py`，并入而非另立）

| 变更 | 说明 |
|---|---|
| **runCatching 面** | 裸 `runCatching` **调用**（词边界；`runCatchingCancellable` 后随字母不匹配而天然放行）在协程语境中，其**表达式区域**（自调用行起 `{}`/`()` **配平走查**，`runCatching { … }.getOrElse { … }` 后缀链整体入区域，深度归零后下一非注释行以 `.` 开头则续走）内不含 `CancellationException` 且未标 `cancel-n/a:` ⇒ 命中，前缀 `RC_SWALLOWED_CANCEL`。声明行（`fun <T> runCatching(…)`，如 `KdbxResult` 伴生包装）不算调用 |
| **`LaunchedEffect` 补进协程构造** | 其 body 恒为挂起 lambda——该构造缺失曾使 §484 的 catch 面**漏判** `TotpScanDialog` 相机启动一处（本批补判并收口，见 §1.2 第 5 行）。`DisposableEffect` / `remember { }` 等**非挂起** lambda 体不收（取消不可达，KDoc 口径声明） |
| **同行前缀语境** | 语境上溯先看本行匹配位置之前的文本（`suspend fun … = runCatching {` / `fun f() = try … catch` 的单行形态不再误判） |
| **KDoc / 失败提示** | 判据、两面共用 `cancel-n/a:`、口径声明（仍看不见自定义包装新增、非链式 handler、挂起 lambda 参数体等）全部改写；`AGENTS.md` 机检条目同步更新触发面 |
| **`--selftest` 扩展** | 绿样本 11 态（原 5 态 + `runCatchingCancellable` 放行 / 链式 handler / `LaunchedEffect` 内已标注 / 声明行 / 非协程裸 `runCatching`），红样本 4 态（catch 吞取消 ×1 + RC 吞取消 ×1 + `LaunchedEffect` 内 RC 吞取消 ×1 + `LaunchedEffect` 内 catch 吞取消 ×1） |

`.github/workflows/build.yml` 无需改动：该脚本本就挂 `hygiene-gate` 第 15 道，两面共用同一入口。

## 3. 门禁读数（`ISSUE-P3-305` AC④：原样粘贴，**禁止**只写「机检全绿 / EXIT 0」）

```text
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/15] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/15] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/15] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/15] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 483 份；分册登记 485 条；全量索引 485 条；最大 §486）
[5/15] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 604 个测试文件
[6/15] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/15] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/15] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/15] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[10/15] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[11/15] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 738 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[12/15] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 483 个 / 批次正文 481 份；现树 工作区：485 / 483；缺失 0 处  [check_archive_monotonicity] PASS
[13/15] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 738 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[14/15] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 738 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[15/15] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 738 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：15/15 PASS ===
```

> 附（本批新增面的 `--selftest` 独立实跑，非 CI 项）：
> 绿样本命中 **0**（11 态，含 `runCatchingCancellable` 放行 / 链式 handler / 声明行）、
> 红样本命中 **4**（catch×2 + runCatching×2）。仓库实扫红→绿：**23 命中 → 0 命中 / 738 文件**，
> 红跑清单与逐处人工判定**完全吻合**（21 RC + 2 catch = 6 修复 + 17 N/A）。

## 4. 测试读数

```text
$ python tools/doc/count_test_results.py
xml=546 tests=3561 failures=0 errors=0 skipped=15
```

- 全量命令：`./gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL in 3m 2s**（114/114 全执行）。
- **增量归因**：上批（§485）基线 `xml=546 tests=3560`，本批新增 **1 例**（`KdbxResultTest` 取消重抛）⇒
  3560 + 1 = **3561** ✓；XML 数持平（`CancellationRethrowWiringTest` 只扩断言面、无新文件）；
  `skipped=15` 与基线持平。
- **定向跑回读**（防 `failOnNoMatchingTests` 静默零覆盖，§201）：
  `:core:` `KdbxResultTest` 5 例（新用例在册）、`:sync:` `CancellationRethrowWiringTest` 4 例 +
  `WebDavBrowsePathResolutionTest` 6 例、`:app:` `AutofillPickerViewModel{CredentialLookup,SessionLock,WriteBack}Test`
  10 例——全部 0 失败，均回读 `TEST-*.xml` 确认在册。

## 5. 过程缺陷与更正留痕（如实）

1. **`--selftest` 首跑两处绿样本红**（判据自身缺陷，非产品缺陷）：① 多行链 `.onFailure { … }` 续行——
   配平深度在 `runCatching { … }` 收口行即归零，链上 handler 不在区域 ⇒ 修为「归零后下一非注释行以
   `.` 开头则继续走查」；② `fun parseIt(): Int? = runCatching { … }` 单行形态——语境上溯只看
   `idx-1` 以上，漏看本行的 `fun` ⇒ 修为「先看本行匹配位置之前的文本」。两处修正后 11 绿 + 4 红全过。
2. **`count_line_tiers` 首跑红并已压回（如实）**：`AutofillPickerActivity.kt` 398 → 401 行触发
   `tier2=36 > budget=35`（棘轮只紧不松）。处置＝注释合并进行内（`val entryTitle = runCatching { … }`
   单行化），压回 **399 行**（纯排版、零语义改动）。
3. **`cancel-n/a:` 标注位置首跑漏识别（如实）**：`S3DirectoryList` 的理由注释写成**两行**，而 `_marked`
   按判据只回看调用行与紧邻上一行 ⇒ 命中残留 1 处；把 `cancel-n/a:` 移到**紧邻调用行**后归零。
   该「只回看一行」与 §484 catch 面同款，属判据既定边界（标注过长时理由可另起注释行，`cancel-n/a:`
   前缀行必须紧邻）。
4. **普查数字与本批命中集的关系（如实）**：原条目「16 处含挂起点」含已知误报、不得当清单；本批以
   机检红跑 **23 处**为权威候选集。两口径之差来自判据升级：① 结构判据取代固定窗口回看；
   ② `LaunchedEffect` 构造补入后 catch 面现出 §484 漏网 2 处；③ 原「16 处」中未进命中集者
   （非协程语境 / 非挂起保护段）经逐处读码确认无需处置。

## 6. 归档与如实声明

- **未真机走查**：本批改动全部是**宿主可静态判定的异常分类**（新增取消守卫 / 换同型包装 / 判据注释），
  无 UI / 无文案 / 无协议面变更；未改 `*/src/androidTest/**`，未触 `crypto/src/main/rust/**` / JNI 绑定 ⇒
  **设备侧四层义务未触发**（`.codebuddy/rules/engineering-rules.md`「测试资产纪律」②）。
- **未跑**：`connectedDebugAndroidTest`（四层设备侧）、`lint`、`assembleRelease`、KPEX 对拍。
- **行为面影响（如实）**：7 处整改只在**协程取消**这一条路径上改变控制流——由「归一为业务失败 / 默认值」
  变为「沿链重抛」；对**未被取消**的调用零影响（守卫恒假）。`KdbxResult.runCatching` 现无生产调用方，
  行为变化仅影响未来调用方与测试断言面（既有 3 例断言非取消异常，不受影响）。
  `WebDavDirectoryList` 取消改沿链重抛后，其上游（`WebDavSyncProvider` → `SyncEngine`）§484 已收口，
  取消可正确传播至 `SyncCycleRunner`。
- **判据边界（原样保留，随脚本 KDoc 走）**：两面共用「最近行命中」语境上溯，对「`launch { }` 收口之后的
  后续语句」仍会判为协程语境（该结构误报由 `cancel-n/a:` 标注吸收，§484 同法）；仍看不见：
  自定义包装**新增**、非链式后续语句里的处理、挂起 lambda 参数体内的 `catch` / `runCatching`、
  块注释内不配对括号——**不得据其绿推定「全仓已无吞取消面」**。
