# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。按 **P0 → P1 → P2 → P3** 降序排列，
> 每项含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需另立计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；
> 历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源
> （批次的来源、严重度映射与登记裁决一并归位到**各批次正文**，产品口径落
> [`产品裁决登记.md`](architecture/产品裁决登记.md)，已接受取舍落
> [`已知工程限界.md`](architecture/已知工程限界.md)）。
> **闭环纪律**：条目整改通过后**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），
> 并执行 `git commit & push`。
> **认领前复核前提**：条目正文的 `文件:行号` 一律是**核实时刻的快照**，认领时须先复核前提再开工（规则 6.1②）。

> **在册批次（2026-10-09，五轴质量审查）**：当前 8 条全部出自
> [`records/五轴质量审查报告_2026-10-09.md`](records/五轴质量审查报告_2026-10-09.md)（正确性 / 可读性 / 架构 /
> 安全 / 性能五轴，四路并行取证 + 主审逐条复核原始代码行）。**无 P0 / P1**；按影响定 P2（用户可感知的功能
> 或告警缺失）/ P3（纪律残留与重复实现）。两项登记裁决：① **认领 `ISSUE-P2-548` 前必读报告 §3**——
> 代理原判「同步层静默失败」已被改写为「先 `post()` 后 `cancel()`」，**照原结论改会误修**；
> ② 已登记限界与格式固有限制（SAF `"rwt"` 截断写限界 §24、KDBX 秒精度等）**不重复立项**。
> 全部为静态取证：未运行构建 / 未跑测试 / 未真机。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。（行号一律视为「核实时刻的快照」，见文首。）

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-537` / `ISSUE-P1-538` 已于 §477 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（**2 项**）

### ISSUE-P2-548：同步失败通知「先发后撤」——鉴权 / 协议失败被伪装成离线且无告警残留

- **现象**：WebDAV 密码填错（401/403）或服务端 5xx 时，UI 显示「离线，已保留本地副本」，
  且**通知栏不留任何失败痕迹**——库可长期未同步成功而用户无感知。
- **核实时间点 / 方式**：2026-10-09，静态逐行取证（**未跑构建 / 未跑测试 / 未真机**）：
  ① `sync/.../webdav/WebDavSyncProvider.kt:163-164` 401/403 → `throw SyncException.AuthenticationError`；
  ② `:150-151` `getMetadata` 走 `runCatching` ⇒ 以 `Result.failure` **返回**而非抛出，故 `SyncCycleRunner.kt:365`
  的 `NetworkError -> Offline` catch **不覆盖此路径**；
  ③ `sync/.../engine/SyncEngine.kt:220-224` `recoverFromMetaFailure` 的 `else` 分支把**所有非 `FileNotFound`**
  失败（含 `AuthenticationError` / `ProtocolError`）归一为 `RemoteUnreachableUsingCache`，
  同时 emit `CouldntOpenFromRemote`；
  ④ `app/.../sync/SyncCycleRunner.kt:354` → `SyncOutcome.Offline`；
  ⑤ `app/.../sync/SyncFailureNotifier.kt:33-42` `isEngineFailure(CouldntOpenFromRemote)=true` ⇒ `post()`；
  ⑥ `:89-103` `lastOutcome.collect` 对**非 `Error`** 一律 `cancel()`。
- **根因（与初版审查结论的差异，勿误修）**：**不是**「`Offline` 不发通知」——事件通道**确实**会 `post()`。
  **真缺陷是「同周期内竞态」**（**2026-10-09 真伪核实更正：原写「定序」不准确**）：
  `SyncCoordinator.syncNow():254-255` 确实**先** `publishSyncEvents()`（内含 `_syncEvents.tryEmit`）**后**
  写 `_lastOutcome.value` ⇒ 发射顺序为「先事件后结论」；但两个订阅者是 `SyncFailureNotifier.start():87-104`
  在同一 scope 下的**兄弟协程**，该 scope 为 `guardedScope(Dispatchers.Default, …)`（`:76`）
  ⇒ 两次唤醒分派到**不同线程并行**，**发射顺序不构成执行顺序保证**；且 `posted`（`:81`）是
  **非 `@Volatile` 普通 `var`**（写于 `post()`、读于 `cancel()`）⇒ 跨线程**无 happens-before**。
  **三种交错都会出现**：① `post()` 先且 `posted` 可见 → 通知被撤（原述结果）；② `cancel()` 先 →
  `if (!posted) return`（`:135`）空转后由 `post()` 留下通知；③ `cancel()` 读到陈旧 `false` → 通知留下。
  ⇒ 「通知被撤掉」**真实可能但非确定发生**；**确定成立**的是「同周期内既 `post()` 又 `cancel()`
  ＋ `posted` 状态机数据竞态」——**两者都须一并整改**。
  **照「`Offline` 也纳入通知」去改会误修**（真实断网时通知刷屏）。
- **前提边界（2026-10-09 真伪核实补正）**：上述「通知被撤」路径只在**库已有缓存基线**（非首次同步）
  时成立。首次同步走 `SyncEngine.openUncached:150-160`——401 时 `:158` emit 事件**并** `:159 throw ex`
  ⇒ `handleOpenRemote` 的 `catch (NetworkError):365-366` **不匹配** ⇒ 落 `catch (Throwable)` ⇒
  `SyncOutcome.Error` ⇒ 通知**留得住**。
- **影响**：部分抵消 `ISSUE-P3-298`（其立项目标正是修复「同步失败零感知」）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/sync/SyncFailureNotifier.kt`、
  `app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt`、
  `sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt`
- **验收标准**：
  ① 鉴权 / 协议错误所在的同步周期结束后，失败通知**仍在通知栏**（新增单测覆盖「事件 + 结论同周期」**竞态**
  与 `posted` 跨线程可见性；**2026-10-09 真伪核实**：`app/src/test` 现仅有 `SyncFailureSignalTest`（覆盖两个纯函数），
  收集器交互**零覆盖**）；
  ② 真实网络不可达（`SyncException.NetworkError` **抛出**路径，`SyncCycleRunner.kt:365-366`）不得因本次整改变成常驻通知；
  ③ UI 侧对鉴权失败不得再显示「离线」文案，须与真实离线区分（错误分类上收，不改降级语义）。

### ISSUE-P2-549：`ExtractedSaveCredentials` 持明文口令却无 `toString()` 覆写 + 无谓 `FLAG_MUTABLE`

- **现象**：自动填充保存链路承载**明文口令**的 data class 未覆写 `toString()`，且口令经
  `FLAG_MUTABLE` 的 `PendingIntent` extras 跨 Binder 下发。
- **核实时间点 / 方式**：2026-10-09，静态逐行取证：
  ① `app/.../autofill/AutofillSaveExtractor.kt:8-12` `internal data class ExtractedSaveCredentials(
  val username: String, val password: String, val webDomain: String?)` —— 全文件**无** `toString()` 覆写；
  ② `app/.../autofill/KeePasskeyAutofillService.kt:439` `putExtra(EXTRA_PASSWORD, extracted.password)`、
  `:445` `PendingIntent.FLAG_MUTABLE or FLAG_UPDATE_CURRENT`；
  ③ **同仓反证**：`AutofillPickerViewModel.kt:162-170` 的 `Credentials` data class **已**就同一形态覆写
  `toString()`（KDoc：「一次日志/异常插值即泄漏」）⇒ 本处属**遗漏而非设计**；
  ④ 现有 3 个消费点（`:8` / `:27` / `:66` 与 `KeePasskeyAutofillService.kt:431`）**均无**日志插值。
- **降档理由（诚实定级，勿按 P1 处置）**：`PasswordSaveActivity` 于 `AndroidManifest.xml:190` 为
  `exported="false"`，且当前无日志消费点 ⇒ **未构成实际泄露**。但 `FLAG_MUTABLE` 在此处**无任何 `fillIn` 需求**
  （属无谓放宽），且一旦新增一行 `AppLog.d(TAG, "$extracted")` 即明文出桶、编译期无护栏。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/autofill/AutofillSaveExtractor.kt`、
  `app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt`
- **验收标准**：
  ① 补 `toString()` 覆写，与 `Credentials` 同口径（仅呈现长度，内容不物化）；
  ② `PendingIntent` 改 `FLAG_IMMUTABLE`（整改前先确认 `PasswordSaveActivity` 确无 `fillIn` 需求）；
  ③ 增补机检：字段名命中 `password` 且承载 `String` 的 data class 必须覆写 `toString()`，并挂入 `hygiene-gate`。

## P3 低危问题、特性接线与体验优化（**5 项**）

### ISSUE-P3-550：11 处异常 message 直出 UI（自订 ZT-10 / `ISSUE-P3-453` 纪律的残留）

- **现象**：`t.message` / `e.message` 被直接拼进用户可见文案，违反本仓自订纪律。
- **纪律出处**：`core/.../result/KdbxResult.kt:19-40` —— `Failure.message` 已标
  `@Deprecated(level = ERROR)`，KDoc 明示「`${t.message}` 把异常细节一并透出 UI……**禁止再用于 UI**」。
- **核实时间点 / 方式**：2026-10-09，`grep -rn "\.message ?:" app/src/main` 全量 + 主审逐条 Read：
  `VaultExportCoordinator.kt:47`、`:67`；`VaultFileDriftResolve.kt:63`、`:65`；
  `VaultLifecycleCoordinator.kt:272`、`:312`；`ResumeSyncProbeCoordinator.kt:157`；
  `SettingsHealthController.kt:161`、`:319`；`SettingsKdfBenchmarkController.kt:51`（共 **10 处**）。
  **渲染路径实锤**：`HealthCheckScreenSections.kt:176` `Text(text = healthMessage)`；
  其余经 `KdbxResult.Failure` 第二参 `userText` 上屏（`textArg() = userText ?: …` 口径 `userText` **优先**，
  如 `VaultListActionController.kt:148` 等 28 处 `textArg(` 调用点）。
- **第 11 处（2026-10-09 真伪核实补入，原 grep 漏计）**：`app/.../sync/SyncConflictAutoMerge.kt:250`
  `SyncOutcome.Error(strings.get(R.string.sync_error_upload_merged_failed, ex?.message))` ——
  原 `grep "\.message ?:"` 只匹配「`.message` + 可选空格 + `:`」，**`ex?.message` 不含 `?:` 故逃过**。
- **排除理由更正（2026-10-09 真伪核实）**：原文写「`ConflictResolutionViewModel.kt:323` 读的是
  `SyncOutcome.Error.message`，属 app 层自产文案，**不计入**」——**该定性不成立**：`SyncOutcome.Error.message`
  **并非恒为 app 层自产**，`SyncConflictAutoMerge.kt:250` 即把原始 `ex.message` 灌入其中，且该字段有
  **三处直出 UI**：`VaultListSyncController.kt:196`、`SettingsSyncController.kt:349`、
  `ConflictResolutionViewModel.kt:323` ⇒ 构成端到端泄露链（上传失败的端点 / 主机 / 协议细节 → 界面），
  **须一并处置**，不得按「不计入」放过。
- **定级说明**：泄露内容为**文件路径 / 端点 / 主机 / 协议细节**，**非主密码明文** ⇒ P3。
- **验收标准**：**11 处** `message` 一律改为错误码映射（**`KdbxErrorTexts.textArg`** 口径；
  `uiTextArg` 一名在本仓**不存在**，`grep uiTextArg` 零命中），细节只进日志；
  连带处置 `SyncOutcome.Error` 的三处 UI 消费点；增补机检禁止 `\.message`（含 `?.message` 与裸 `message` 形参）
  进 `userText` / 文案槽，并挂入 `hygiene-gate`。

### ISSUE-P3-551：序列化缓冲（整库密文）在写盘异常路径不清零

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `database/.../session/SessionPersistence.kt:91-94` `serialized = serializeToBytes(...)`
  → `writer(serialized)` → `serialized.fill(0)`，`fill(0)` 在 **`try` 体内而非 `finally`**，
  `writer` 抛异常时整库密文副本滞留堆等 GC；
  ② `:187-189` 同型第二处（`changeCredentials` 路径）；
  ③ **同文件反证**：`:180-200` 的凭据快照处理正确（`oldPwd` 克隆 + 失败路径所有权移交 `restoreCredentials`）
  ⇒ 本处属遗漏而非取舍。
- **验收标准**：两处 `serialized.fill(0)` 移入 `finally`（或 `use`-式收口），并补单测断言异常路径下缓冲已清零。

### ISSUE-P3-552：「今天/昨天/M月d日 HH:mm」相对时间格式化三处实质重复

- **核实时间点 / 方式**：2026-10-09，静态取证：
  `VaultListSyncController.kt:205-215`、`SettingsSyncController.kt:382-392`、
  `ConflictResolutionViewModel.kt:93-101` —— 三处同一套 `when(今天/昨天/else)` 判据 + 同一套
  `R.string.time_today` / `time_yesterday` / `date_pattern_month_day`，差异仅在入参与「0 → never」分支。
  三处**每次调用都重编译** `DateTimeFormatter.ofPattern("HH:mm")`；
  同类：`EntryDetailExpiryCard.kt:25` 与 `EntryEditExpiryEditor.kt:37` 各自硬编码 `"yyyy-MM-dd"`；
  **2026-10-09 真伪核实补入**：`sync/RemoteBrowsePaths.kt:65` 亦硬编码 `"yyyy-MM-dd HH:mm"`
  （与 `VaultEntryMapper.INSTANT_PATTERN` 同模式，同一收敛动作）。
  （`VaultEntryMapper.kt:339-355` 已用 `ConcurrentHashMap<Locale, DateTimeFormatter>` 缓存并注释此坑 ⇒ 已知未收敛。）
- **验收标准**：抽 `app/.../ui/model/RelativeTimeFormatter.kt` 统一出口（可空入参统一处理「从未」），
  pattern 常量化 + 缓存；三处调用点收敛，行为与文案不变。

### ISSUE-P3-553：剪贴板写入成败裁决助手逐字重复

- **核实时间点 / 方式**：2026-10-09，静态取证：`EntryDetailCopyCoordinator.kt:60-68` 与
  `VaultListClipboardCopy.kt:35-43` 的 `private fun writeToClipboard(write: ...): Boolean` **实现逐字相同**
  （通道 null → false；`try { channel.write(); true } catch { false }`）。
  另 `notification/UnlockedNotificationCopyAction.kt:81`、`:107`、`:120` 三发重复
  `UiMessage(R.string.clipboard_copy_failed)`。
- **验收标准**：提 `ClipboardSecurityChannel?.tryWrite(...): Boolean` 单点扩展，两处调用点收敛；
  失败提示文案一并收口。

### ISSUE-P3-554：`app` 模块依赖声明与实际使用不符（隐式透传脆点）

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `app/build.gradle.kts:257-259` 只声明 `:core` / `:database` / `:sync`，**未声明 `:crypto`**；
  ② `grep -rl "com.keepasskey.crypto" app/src/main` = **11 个文件**（**2026-10-09 真伪核实更正：
  原写「12 个」，经检索工具与 `grep -rl | wc -l` 两路独立取证均为 11**）：`PasskeyEntryCoordinator.kt`、
  `PasskeyAssertionPayload.kt`、`PasskeyAuthFlags.kt`、`PasskeyCreateActivity.kt`、`PasskeyImportFactory.kt`、
  `PasskeyRegistrationPayload.kt`、`WebAuthnJsonKeys.kt`、`PasswordEntropyEstimator.kt:4`、
  `SettingsKdfBenchmarkController.kt:7`、`SettingsPreferencesController.kt`、`SettingsUiStateProjection.kt:8`）；
  ③ 能编译全靠 `database/build.gradle.kts:32` 的 `api(project(":crypto"))` 透传；
  ④ **单向性未被破坏**（已核实 core 无跨模块 import、crypto 只依赖 core、sync 只依赖 core、
  database 只依赖 core+crypto）。
- **验收标准**：`app` 显式 `implementation(project(":crypto"))`，使声明反映真实依赖；
  改动后 `.\gradlew.bat assembleDebug` 仍绿。

### ISSUE-P3-555：`SyncEngine.markResolvedAndUpload` 吞掉协程取消

- **核实时间点 / 方式**：2026-10-09，静态取证：
  ① `sync/.../engine/SyncEngine.kt:495-497` `catch (t: Throwable) { SyncResolveUploadResult.Failed(t) }`
  未重抛 `CancellationException`，与同文件 `openRemote`（抛出）及 `SyncCycleRunner.kt:363-365`（显式重抛）口径不一致；
  ② 同源：`WebDavSyncProvider.kt:151/227/262`、`S3SyncProvider.kt:162/272` 的 `runCatching`、
  `WebDavUploadAtomic.kt:127` 的 `catch (e: Exception)`（`CancellationException` 是 `IllegalStateException` 子类）；
  ③ 全模块 grep `CancellationException` **仅** `TransientHttpRetry.kt:62` 正确重抛 ⇒ 该处是例外而非通例。
- **验收标准**：上述 `catch (t: Throwable)` / `runCatching` 收敛点显式重抛 `CancellationException`；
  增补单测断言「周期被取消时不上报 `Failed`」。


