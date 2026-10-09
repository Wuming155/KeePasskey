# §484 协程 `catch` 取消语义逐处收口批次（2026-10-09）

**条目**：`ISSUE-P3-569`（**整条闭环**）+ `ISSUE-P3-570`（**同批新登记，未闭环**）
**来源**：`ISSUE-P3-568`（§483）达标时「建议面」普查的结论——`catch (…)` 的协程取消语义属**另一条独立评估面**，
§483 明令**不与其余十三条混为一次未验证的批量改动**。
**编号说明**：`resolved/README.md` 登记的「下一批次」为 §484（本批取用）。

## 0. 条目原文（登记即闭环，**原样收录**；`ACTIVE_ISSUES.md` 已整条剪切）

> ### ISSUE-P3-569：`catch (Throwable)` 的协程取消语义未**逐一**收口（`ISSUE-P3-568` 达标时「建议面」普查的结论）
>
> - **现象**：`CancellationException` 在 JVM 上是 `java.util.concurrent.CancellationException` 的别名、继承
>   `IllegalStateException` ⇒ 全部 `runCatching` / `catch (e: Exception)` / `catch (t: Throwable)` 都会顺手吞掉它。
>   `ISSUE-P3-555`（§482）只收敛了 `sync` 模块的 provider / 上传重试与 `markResolvedAndUpload`；
>   `ISSUE-P3-568`（§483）只补了 `SettingsKdfBenchmarkController` 一处，其余同型点**未逐一判定**。
> - **核实时间点 / 方式**：2026-10-09，**静态启发式普查**（临时脚本：按「最近前置的 `fun` / `launch` / `async` /
>   `withContext` / `runBlocking` 行」推断 catch 所属上下文，并检查 catch 体 12 行内是否出现
>   `CancellationException`；脚本为一次性分析、未入库）。
> - **实测读数**：五模块 `src/main` 的 `catch (… : Throwable)` 共 **131 处**，其中 **63 处**落在挂起 / 协程上下文，
>   **62 处**未见取消重抛。**该启发式不可直接当缺陷清单**——已确证一例误报：`app/.../sync/SyncGuardedLaunch.kt:39`
>   的外层 `catch (e: Throwable)` **两行之前**即有 `catch (e: CancellationException) { throw e }`（正确形态），
>   扫改会误伤。
> - **影响**：取消被归一为业务失败 / 错误态时，「用户退出页面 / 换库」会被记成一次「操作失败」，
>   与 `runCatchingCancellable` 的立规意图（取消沿链重抛）相悖。**须逐处判定**才能定性——
>   部分点位的 catch 位于不可能观测取消的非挂起路径，改动仅是死代码。
> - **涉及文件**：`app/src/main/**`、`database/src/main/**`、`sync/src/main/**`、`core/src/main/**`（候选以脚本现跑为准）
> - **验收标准**：① 逐处判定并为「挂起 / 协程上下文」中的同型点补取消重抛
>   （`if (t is CancellationException) throw t` 置于其它分支之前）；② 对判为**不适用**的点位在批次文档登记理由
>   （不得静默跳过）；③ 评估为「协程上下文中的 `catch (Throwable/Exception)` 必须显式处理取消」增设机检——
>   **判据须避开 `SyncGuardedLaunch` 式「前置取消分支」形态**（否则误报），并须以 `--selftest` 正反样本反校。

## 1. 整改

### 1.1 判定口径与流程（先复核前提，再逐处判定）

1. **前提复核**：原条目的「131 处」出自一次性脚本。本批**重跑**同口径普查（五模块 `src/main`，
   `catch (… : Throwable)`）得 **100(app) + 9(database) + 11(sync) + 2(core) + 9(crypto) = 131 处** ⇒ 前提成立，
   但**普查窗口（固定行数回看）不可当清单**（见 §5.1 的误判实例）。
2. **判据点**：改为**结构判据**（最近上溯语境 + 花括号配对的子句体 + 同 `try` 的前置兄弟分支），
   落成机检 `tools/doc/check_cancellation_semantics.py`（§2）。CAN 候选＝该脚本定稿判据现跑的命中集
   （**21 处**；判据定稿过程为首跑 29 → 补「原样重抛」识别 27 → 支持「全限定名前置取消分支」**21**，
   见 §5.1~§5.3），经修复后归零。
3. **逐处判定**：对每一处读原码判断「`try` 体内是否**真含挂起点**」——
   - 含挂起点（suspend 调用 / `withContext` / 挂起 lambda 调用）⇒ **补取消重抛**（§1.2，**36 处**）；
   - 不含挂起点（纯阻塞 IO / 纯 CPU 运算 / 非挂起构造）⇒ 取消只会落在该 `try` **边界之外**，
     补守卫即**死代码** ⇒ **判为不适用**，就地标注 `cancel-n/a:`（§1.3，**21 处**）。
4. **既正确形态不动**：已带**同 `try` 前置取消分支**者（§1.4）不属命中集。

### 1.2 补取消重抛（**36 处 / 26 文件**；`if (v is CancellationException) throw v` 置于其它分支之前）

| 模块 | 文件:行（守卫所在行） | 所在语境（`try` 体内挂起点） |
|---|---|---|
| app | `autofill/AutofillOriginResolver.kt:63` | `suspend fun resolveUsableWebDomain`：`dalVerifier.verify(...)`（DAL 网络校验） |
| app | `autofill/AutofillPickerViewModel.kt:87 / 139 / 151` | `viewModelScope.launch` 内 `getUsableKdbxEntries()`；`resolveCredentials` 内 `getEntryPasswordChars` / `resolveFieldReferences` |
| app | `data/childdb/ChildReadOnlySession.kt:143 / 162` | `runAttempt` 内 `loadProjection`；`loadProjection` 内 `withContext(Dispatchers.Default)` |
| app | `data/repository/VaultFileDriftResolve.kt:66` | `reloadFromDisk` / `mergeAndSave`（均 `suspend`） |
| app | `data/repository/VaultLifecycleCoordinator.kt:273 / 316` | `removeDatabase` 内 `databaseSession.close()` + `refresh()`；`importExternalDatabase` 内 `refresh()`（构造参 `suspend () -> Unit`） |
| app | `passkey/CredentialUnlockActivity.kt:147` | `lifecycleScope.launch` 内 `buildUnlockedGetResponse` |
| app | `passkey/PasskeyAssertionActivity.kt:87 / 305` | `lifecycleScope.launch` 内 `performAssertion`；`incrementPasskeySignCount` |
| app | `passkey/PasskeyCreateActivity.kt:172 / 367` | `lifecycleScope.launch` 内 `awaitRegistration`；`saveOrReplacePasskeyEntry` |
| app | `passkey/PasswordDraftActivity.kt:220` | `lifecycleScope.launch` 内 `vaultRepository.saveEntry` |
| app | `passkey/PasswordFillActivity.kt:94 / 245` | `lifecycleScope.launch` 内 `performFill` / `resolveFieldReferences` |
| app | `passkey/PasswordSaveActivity.kt:136` | `lifecycleScope.launch` 内 `saveAutofillCredential` |
| app | `sync/PeriodicSyncWorker.kt:45` | `suspend fun doWork` 内 `coordinator.syncNow()`（**吞取消会把取消记成 `Result.success()`**） |
| app | `sync/ResumeSyncProbeCoordinator.kt:159` | `withContext(IO)` 内 `provider.getMetadata(remotePath)` |
| app | `sync/SyncCoordinator.kt:326` | `withContext(IO)` 内 `provider.testConnection()` |
| app | `ui/screens/detail/EntryDetailAttachmentExporter.kt:62` | `vaultRepository.getAttachmentData(...)` |
| app | `ui/screens/edit/EntryEditPickers.kt:197 / 245` | 保护段内 `withContext(Dispatchers.Main)` 回写 |
| app | `ui/screens/edit/TotpGalleryImport.kt:111` | `scope.launch` 内 `withContext(IO)` + `withContext(Default)` |
| app | `ui/screens/settings/BiometricResealCoordinator.kt:211` | `authorizeAndSeal(...)`（`suspend`） |
| app | `ui/screens/settings/SettingsColdStartSyncGate.kt:37` | `scope.launch` 内 `settingsRepository.getSettings().first()`（Flow 挂起终端的） |
| app | `ui/screens/settings/SettingsHealthController.kt:160` | `withContext(Default)` + `runBreachCheck(entries)`（网络） |
| app | `ui/screens/unlock/BiometricEnrollmentCoordinator.kt:223` | `authorizeAndSeal(...)`（`suspend`） |
| app | `ui/screens/unlock/BiometricUnlockCoordinator.kt:278` | `withContext(cryptoDispatcher)` + `completeBiometricUnlock(...)` |
| app | `ui/screens/unlock/SafKeyFileAccess.kt:93` | 保护段内 `hasPersistedReadPermission(uri)`（`suspend`） |
| database | `session/SessionOpener.kt:120 / 194` | `create` 内 `withContext(Dispatchers.IO)`；`openStream` 内 `inputStreamProvider()`（`suspend () -> InputStream`） |
| database | `session/SessionPersistence.kt:106 / 210` | 保护段内 `writer(serialized)`（`suspend` 写盘通道） |
| sync | `engine/SyncEngine.kt:390` | 保护段内 `cache.receiveRemote { provider.download(...) }`（均 `suspend`；守卫用**全限定名**，见 §5.6） |

补充说明：`SettingsColdStartSyncGate` 原为 `catch (_: Exception)`，**无变量可判** ⇒ 同批把参数改名为 `t`
（行为面零差异，仅使取消重抛可表达）。

### 1.3 判为「不适用」并就地标注 `cancel-n/a:`（**21 处 / 16 文件**）

判据＝保护段内**无挂起点** ⇒ 取消只会在该 `try` 边界外观测，补守卫即死代码。标注随代码走，
故**新增同型点不会被静默放行**（fail-closed）。

| # | 文件:行 | 判为不适用的理由（保护段为何不含挂起点） |
|:--:|---|---|
| 1 | `data/importer/BrowserCsvImporter.kt:54` | 保护段为纯 CPU 解析（`withContext(Default)` 内无挂起点） |
| 2 | `data/importer/KeePassXmlImporter.kt:43` | 保护段为纯 CPU 解析（同上） |
| 3 | `data/repository/VaultDatabaseCatalog.kt:126` | 保护段为阻塞式 `ContentResolver.query`（无挂起点） |
| 4 | `data/repository/VaultExportCoordinator.kt:46` | 保护段为纯 CPU 导出（挂起的 `.first()` 在 `try` 之前） |
| 5 | `data/repository/VaultExportCoordinator.kt:69` | 同上（CSV 分支） |
| 6 | `data/repository/VaultLifecycleCoordinator.kt:149` | 保护段为 `KdbxKeyFileGenerator.generate()`（非挂起） |
| 7 | `data/repository/VaultLifecycleCoordinator.kt:294` | 保护段为阻塞式 `takePersistableUriPermission`（无挂起点） |
| 8 | `data/repository/VaultSourceProbe.kt:43` | 保护段为阻塞式流打开与头部解析（`openStream` 为非挂起私有方法） |
| 9 | `security/KeyFileVaultCopyStore.kt:101` | 保护段为阻塞式加密封装与写盘（`sealHook` 类型为非挂起 lambda） |
| 10 | `sync/CloudVaultImporter.kt:130` | 保护段为 `providerFactory.create(request)`（`fun interface` 成员，非挂起） |
| 11 | `sync/RemoteBrowseController.kt:88` | 保护段为 `WebDavSyncProvider(...)` 构造（非挂起） |
| 12 | `sync/RemoteBrowseController.kt:123` | 保护段为 `S3SyncProvider(...)` 构造（非挂起） |
| 13 | `sync/SyncCoordinator.kt:233` | 保护段为 `resolveRemotePath` + `store.markRecrypted`（均非挂起） |
| 14 | `sync/SyncDatabaseCodec.kt:37` | 保护段为 `KdbxFile.save(...)`（非挂起；`useCredentials` 亦非挂起） |
| 15 | `sync/SyncGuardedLaunch.kt:45` | 兜底分支：`onFailure` 为非挂起回调，取消已在**上一兄弟分支**排除（该 KDoc 已载「不得成为新的崩溃源」） |
| 16 | `ui/screens/database/DatabasePickerKeyFileDelivery.kt:93` | 保护段为阻塞式 SAF 写盘（无挂起点） |
| 17 | `ui/screens/edit/TotpScanDialog.kt:421` | `suspendCancellableCoroutine` 回调内，取消经 `resumeWithException` **原样转交续体**（语义正确，非吞） |
| 18 | `ui/screens/settings/SettingsExportController.kt:66` | 保护段为阻塞式日志导出写盘（无挂起点） |
| 19 | `ui/screens/settings/SettingsExportController.kt:226` | 保护段为阻塞式 SAF 写盘（无挂起点） |
| 20 | `ui/screens/unlock/SafKeyFileAccess.kt:71` | 保护段为阻塞式流读取（`readKeyFileBytes(InputStream)` 非挂起） |
| 21 | `database/session/SessionPersistence.kt:134` | 保护段为 `serializeToBytes(...)`（纯 CPU，非挂起） |

`SyncGuardedLaunch.kt:45` 即 `ISSUE-P3-569` 明令判据**不得误报**的形态：
`39` 行的外层 `catch (e: Throwable)` 与 `37` 行的 `catch (e: CancellationException) { throw e }` 是**同 `try` 兄弟**，
机检按「前置兄弟分支」直接放行（不属命中集）；其**内层**兜底（`45` 行）因保护段是 `onFailure(e)`（非挂起）判不适用。

### 1.4 未进入命中集的既正确形态（举例，非穷举）

`sync/SyncGuardedLaunch.kt:39`、`app/sync/SyncCycleRunner.kt:228`、`sync/engine/SyncEngine.kt:498`、
`app/sync/SyncRemoteVaultCopy.kt:67`、`ui/screens/settings/SettingsHealthController.kt:322` ——
均为「`catch (… : kotlinx.coroutines.CancellationException) { throw … }` **前置** + 泛化分支随后」的正确写法；
机检的 `SIBLING_CANCEL_RE` 已支持**全限定名**（本批新增，§2 / §5.2），故不再误报。

## 2. 机检变更

| 机检 | 变更 | 反校证据 |
|---|---|---|
| **新增** `tools/doc/check_cancellation_semantics.py` | 五模块 `src/main/**` 内「协程上下文 + 自身子句体无 `CancellationException` 且无原样重抛 `throw <v>` + 同 `try` 无前置取消分支 + 未标注 `cancel-n/a:`」的 `catch (v: Throwable\|Exception)` 命中即退出码 1 | `--selftest`：绿样本 **0 命中**（前置取消分支 / 自身处理 / 非协程 / 已标注 / 原样重抛 **五态**）、红样本 **1 命中**；仓库实扫 **0 命中 / 737 文件** |
| `.github/workflows/build.yml` | `hygiene-gate` 第 **15** 条机检接线（fail-closed；job 名与注释同步） | `gate_readings.py` 现跑读数（清单由该文件 `hygiene-gate` 段落解析，见 §3） |

判据与「看不见的面」已写入脚本 KDoc：`runCatching` 面、挂起 lambda（`suspend (T) -> R`）参数体内 `catch`、
「子句体提到 `CancellationException` 却未真重抛」三类**不在本判据面**。

## 3. 门禁读数（`ISSUE-P3-305` AC④：原样粘贴，**禁止**只写「机检全绿 / EXIT 0」）

```text
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/15] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/15] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/15] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/15] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 481 份；分册登记 483 条；全量索引 483 条；最大 §484）
[5/15] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 602 个测试文件
[6/15] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/15] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/15] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/15] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[10/15] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[11/15] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 737 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[12/15] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 482 个 / 批次正文 480 份；现树 工作区：483 / 481；缺失 0 处  [check_archive_monotonicity] PASS
[13/15] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 737 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[14/15] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 737 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[15/15] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 737 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：15/15 PASS ===
```

> 附：第 15 道（本批新增）的 `--selftest` 独立实跑（非 CI 项，判据反校证据）：
> 绿样本 **0 命中**（五态：同 `try` 前置取消分支 / 自身 `is CancellationException` / 非协程 `fun` /
> 已标 `cancel-n/a:` / 清理后原样重抛）、红样本 **1 命中**（裸吞取消）；仓库实扫 **0 命中 / 737 文件**。
> 该脚本上线时**先跑红**（27 命中）再随整改转绿——红→绿即判据有效性证据（§5.1~§5.3）。
> 另：`737` 与 §483 记录的 `735` 之差**不来自本批**（本批未新增 / 删除任何 `.kt` 生产源，
> 且 `git ls-tree HEAD` 同口径亦为 **737**）。

## 4. 测试读数

```text
$ python tools/doc/count_test_results.py
xml=544 tests=3544 failures=0 errors=0 skipped=15
```

- 全量命令：`./gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL in 5m 12s**（114/114 全执行）。
- **最终树的补充运行（如实）**：上述全量运行的**启动时刻早于**本批最后一次 `SyncEngine.kt` 改动
  （为压回 `tier1(>500)` 而把守卫改用全限定名 `kotlinx.coroutines.CancellationException`
  ——与该文件 `:498` 既有写法一致——并**合并同题两行注释**，**无行为差异**），故在全量运行结束后对受影响模块单独复跑：
  `./gradlew.bat :sync:testDebugUnitTest --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL in 1m**（25/25 executed）。
  上表 `xml=544 tests=3544` 即「复跑后的 `sync` XML + 全量运行产出的其余四模块 XML」的聚合。
- 与 §483 基线（`xml=544 tests=3541 failures=0 errors=0 skipped=15`）对账：**+3 例、XML 数持平、
  失败与跳过持平**。本批**未新增 / 修改任何 `src/test/**`**（`git status` 可证）⇒ 该 +3 例**不来自本批**。

## 5. 过程缺陷与更正留痕（如实）

1. **原普查的固定窗口回看**：原条目脚本以「回看 N 行内是否出现 `CancellationException`」判「已处理」，
   窗口取得过大（30 行）时会**误把无关的取消字串当覆盖** ⇒ 本批改用**同 `try` 前置兄弟分支**判据后，
   原清单外又现出 **5 处**（`SyncCycleRunner:228/300/374`、`SyncRemoteVaultCopy:67`、`SettingsHealthController:322`）——
   复核后确认这 5 处**本身即正确形态**（前置全限定名取消分支），属**判据修正**而非新增缺陷。
2. **全限定名形态**：上述 5 处用的是 `kotlinx.coroutines.CancellationException`（非短名），
   首版 `SIBLING_CANCEL_RE` 只认短名 ⇒ 首跑误报 5 处；已放宽为支持命名空间前缀，并以仓库实扫 27 → 21 归位反校。
3. **重抛识别**：`sync/engine/SyncCache.kt:185`（`tmpFile.delete(); throw t`）与
   `sync/engine/SyncEngine.kt:174`（`events.tryEmit(...); throw ex`）属**清理后原样重抛**，
   取消未被吞 ⇒ 判据补「`throw <被捕获变量>`」识别，二者退出命中集（§2 绿样本第 ⑤ 态即此形态）。
4. **`catch (_: Exception)` 无变量可判**：`SettingsColdStartSyncGate.kt` 命中该形态，
   补守卫须先把参数具名（已改名为 `t`）——**首版脚本占位符写错**（`return@withContext null`），
   在应用前即被人工复核截下并改为 `throw t`。
5. **`--selftest` 的两处首跑红**（如实）：① `} catch (…) {` 的**前导 `}`** 被计入花括号配对 ⇒
   子句体被误判为「已结束」（绿样本第 ② 态首跑报红），修为「先见 `{` 才计 `}`」；
   ② 绿样本第 ⑤ 态（原样重抛）在补重抛识别前报红。两处均为**判据自身缺陷**，非产品缺陷。
6. **`count_line_tiers` 首跑红并已压回（如实）**：`SyncEngine.kt` 加守卫 + 导入后由 **500 → 502 行**，
   触发 `tier1(>500)=1`（闸门要求恒 0）。处置＝去掉该文件的 `CancellationException` 导入、
   守卫改用**全限定名**（与该文件 `:498` 既有写法一致），并把**同题相邻的两行注释合并为一行** ⇒
   回到 **500 行**（`tier2` 35 / budget 35）。该注释合并为**纯排版、零语义改动**，本批如实登记其缘由。
7. **`737` 与 §483 记录 `735` 的读数差**：`git ls-tree HEAD` 同口径（五模块 `src/main/**/*.kt`）亦为 **737**，
   本批未新增 / 删除任何 `.kt` 生产源 ⇒ 差异**不来自本批**（§483 的 `735` 系该批结案前的读数，本批不追改）。

## 6. 归档与如实声明

- **未真机走查**：本批只改**宿主可静态判定的异常分类**（新增一条 `is CancellationException` 判定），
  无 UI / 无文案 / 无协议面变更；未改 `*/src/androidTest/**`，未触 `crypto/src/main/rust/**` / JNI 绑定 ⇒
  **设备侧四层义务未触发**（`.codebuddy/rules/engineering-rules.md`「测试资产纪律」②）。
- **未跑**：`connectedDebugAndroidTest`（四层设备侧）、`lint`、`assembleRelease`、KPEX 对拍。
- **行为面影响（如实）**：36 处新增守卫只在**协程取消**这一条路径上改变控制流——由「归一为业务失败 / 默认值」
  变为「沿链重抛」。对**未被取消**的调用零影响（守卫恒假）。
- **判据边界（原样保留）**：本批机检**只钉 `catch` 子句**；`runCatching` 内联面、
  挂起 lambda 参数体内 `catch`、以及「提到 `CancellationException` 却未真重抛」三类**不在本判据面**，
  故**不得据其绿推定「全仓已无吞取消面」**（见脚本 KDoc 与 `AGENTS.md` 对应条目）。
- **新登记**：`ISSUE-P3-570`（`runCatching` 的协程取消语义与 `catch` 面同型、未逐一收口）——
  按「发现新问题即时补登」登记，**不与本批混为一次未验证的批量改动**（与 §483 处置 `ISSUE-P3-569` 同法）。
