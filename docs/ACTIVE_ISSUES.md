# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

**核实口径**：各条以条目内注明的核实时间与方式为准；静态定位、宿主行为复现和设备/真实服务端验收分别标明，不相互替代。运行探针使用虚构数据；临时探针已清理，不作为永久复现资产。同类项目只提供比较背景，不作为本仓缺陷成立或必须删除防护的依据。编号保持原有身份，不因移除误报或调整优先级重新编号。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能、测试有效性缺口及需验证影响的安全问题 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

分级按当前已确认的影响与触发条件确定；尚未完成的攻击链或性能测量不能当作既成结果。

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

暂无。

## P2 中危缺陷与协议/测试缺口

### 诊断归档超限后逐行全量重写，启动裁剪可覆盖崩溃行

- **核实时间 / 方式**：2026-11-14；静态审查。`app/src/main/java/com/keepasskey/app/data/logger/DiagnosticLogHistory.kt:77-82` 超 1MB 即调 `retain()`；`:130-137` 只按日期与 4000 行裁剪，无字节上限；`configure()` 的 `:56-60` 的 `atomicWrite` 未持 `fileLock`，与 `appendSync()` `:95-103` 竞态。审查项 E1 / C5。
- **影响**：行均长度超过约 262B（含栈帧时常见）时，每次追加都全量读文件并原子重写，单线程 executor 积压、闪存磨损加剧；启动裁剪还可能覆盖刚写入的崩溃现场，使未捕获异常留痕失效。
- **触发前提**：归档达到 1MB 且行较长；或冷启动裁剪与早期崩溃并发。
- **处置**：未开始。方向：按字节截断至约 768KB、裁剪防抖、`configure` 写路径持 `fileLock`。
- **关闭条件**：1.2MB 归档连续追加 50 行仅触发 ≤1 次重写；并发执行 `configure` 与 `appendSync` 后崩溃行仍在；压力下 `snapshotForExport` 不超时。

### 复制/导出日志阻塞主线程且报告头与正文不一致

- **核实时间 / 方式**：2026-11-14；静态审查。`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt:95-101` 由 UI 回调同步调用，`DiagnosticLogHistory.kt:117` 用 `Future.get(2s)` 等待，`:100` 与 `DebugLogBuffer` 的导出路径对正文脱敏两次；`DiagnosticLogReport.kt:31` 报告头取内存状态、`:56` 正文取磁盘归档、`:61` `isEmpty` 只看内存。审查项 E2 / E3。
- **影响**：executor 积压时主线程冻结最长 2 秒，构成 ANR 风险；重启后导出会出现「头说 2 行、正文有 3 天」，内存为空但磁盘有归档时复制被误拦。
- **触发前提**：用户点击复制/导出，或进程重启后立即导出。
- **处置**：未开始。方向：复制移入 `Dispatchers.IO`、去重二次脱敏、报告头与正文同源、`isEmpty` 同时检查磁盘归档。
- **关闭条件**：StrictMode 下点击复制主线程零磁盘 IO；「内存 0 行 + 磁盘有归档」可正常复制；报告头计数与正文一致。

### 日志缺少链路标识与非敏感失败分型

- **核实时间 / 方式**：2026-11-14；静态审查。行格式仅 `[日期] [时刻] [线程] [级别] [tag]`（`DebugLogBuffer.kt:169`）；`SyncOutcomeDescription.kt:28` 对 `Error` 固定输出「详情见界面提示」；`SessionOpener.kt:347-351` 只区分文件损坏与其余，凭据错误、版本不支持、IO 失败仍合并为 `UNLOCK_FAILED`；`SessionPersistence.kt:167` 全部收敛为 `SAVE_FAILED`；`KdbxError` 37 个常量在 `app/src/main` 的日志/审计调用中零命中。审查项 B1 / B2 / D15。
- **影响**：一次同步/解锁/Passkey 流程无法从导出日志还原因果链；失败原因在日志层被抹平，离线诊断只能看到「某步失败」。
- **触发前提**：任何多步操作或错误路径。
- **处置**：未开始。方向：新增轻量 `opId` 与耗时字段，复用 `SyncRemoteFailureOutcome.kt:41-67` 的 `classifyRemoteFailure` / `remoteFailureDescriptor`，日志行追加 `kind=`、`code=`、`durMs=`；不引入异常 message。
- **关闭条件**：单次同步/解锁可按 `op` 聚合出完整链路；错误行含非敏感分型与 `KdbxError` 码，且不含 URL、账号、主机或异常原文。

### 普通日志双轨与 logcat 双写

- **核实时间 / 方式**：2026-11-14；静态审查 + 计数复核。33 个类共 132 处直接写 `DebugLogBuffer`（`debugLog` / `debugLogBuffer` 接收者）；同期 `AppLog.i/w/e/d/v` 为 71 个文件 282 处；`AppLog.i` 先按原 tag 输出一次，`DebugLogBuffer.kt:185-190` 再以 `KpLog` 回显同一条完整行。审查项 B4 / B5。
- **影响**：级别与 tag 语义不一致（直写路径丢失原 tag），回显使真机 logcat 容量被双倍消耗，故障窗口更早被冲掉；稳定版默认开启直出，还会把脱敏行持续暴露在系统日志环形区，扩大本机取证面。
- **触发前提**：任何日志写入。
- **处置**：未开始。方向：业务一律经 `AppLog`（需要结构化字段时增加重载），移除缓冲对自身的二次回显或明确其取证用途。
- **关闭条件**：`data/logger` 之外无类直接注入 `DebugLogBuffer`；`adb logcat` 中每条事件只出现一次。

### 日志无风暴抑制

- **核实时间 / 方式**：2026-11-14；静态审查。`DebugLogBuffer` 无去重、限流或采样逻辑。审查项 B6。
- **影响**：同步连续失败或自动填充高频报错时，2000 行内存缓冲与 4000 行归档在数分钟内被同质行冲掉，早期现场丢失。
- **触发前提**：同一异常在短时间内反复出现。
- **处置**：未开始。方向：连续相同行折叠（`repeated N times`），按 tag 令牌桶限流；ERROR 不丢弃但合并重复栈。
- **关闭条件**：单测写入 100 条相同异常仅保留 2 行；不同 tag 的 ERROR 全部保留。

### 脱敏存在路径/主机/凭据盲区

- **核实时间 / 方式**：2026-11-14；静态审查。`DiagnosticLogRedactor.kt:39` 的 TLD 白名单 26 个，` :41` 仅支持 IPv4、不剥离端口；`:43` 文件规则遇 `/` 截断，`path=/work/dept/vault.kdbx` 只遮文件名、目录名保留，无后缀库名不遮；`:47` 敏感键仅 7 个；`SyncCacheEventDescriber.kt:13-26` 直接输出原始 `remotePath`。审查项 E4。
- **影响**：内网域名、IPv6 地址、端口、多级目录与无后缀库名可能随日志出域，与隐私政策「移除库与密钥文件名、主机」的承诺存在偏差。
- **触发前提**：使用内网/非常规 TLD/IPv6 同步目标，或远端路径含多级目录。
- **处置**：未开始。方向：增加 IPv6、主机+端口、任意路径段与更多凭据键规则；远端路径改输出摘要或布尔存在性。首传探测的失败日志已在关闭 P1「首次同步探测失败返回值丢失」时改为 `remotePathDigest`（SHA-256 前 8 字节），其余仍输出原文的点位不变。
- **关闭条件**：负样本测试覆盖 `[fe80::1]:8080`、`nas.internal`、`/work/dept/vault`、`accessKey=...`，输出不含原文；幂等性测试保持通过。

### 同步编排异常被吞

- **核实时间 / 方式**：2026-11-14；静态审查。`app/src/main/java/com/keepasskey/app/sync/SyncGuardedLaunch.kt:39-47` 捕获后只调 `onFailure`，自身不记录；多数 `onFailure` 仅把 `simpleName` 塞进 UI；`SyncCycleRunner.kt:375` 与恢复路径的 `catch (Throwable)` 直接返回通用错误。审查项 A3。
- **影响**：同步编排、状态上浮与收尾段的异常既不进归档也不进栈，导出日志只剩「同步开始/结束」。
- **触发前提**：编排或恢复段抛出非取消异常。
- **处置**：未开始。方向：`SyncGuardedLaunch` 在调用 `onFailure` 前先 `AppLog.e`；两个 catch-all 分支补同等留痕。
- **关闭条件**：注入异常后归档出现异常类名与有界栈帧，且 UI 仍给出可理解提示。

### 后台任务与锁态探测失败不可见

- **核实时间 / 方式**：2026-11-14；静态审查。`app/src/main/java/com/keepasskey/app/sync/PeriodicSyncWorker.kt:43-52` 捕获后只写 WorkManager `Data`，不写日志；`SyncLockedProbeRunner.kt:47` 锁态探测失败不写 `lastOutcome`；`SyncCoordinator.kt:376-384` 该路径不更新通知所订阅的状态。审查项 A7。
- **影响**：后台连续多日同步/探测失败时，用户既无通知，导出日志也无记录，形成「零感知失败」。
- **触发前提**：库锁定或后台周期任务遇 401/5xx/未知异常。
- **处置**：未开始。方向：Worker catch 补 `AppLog.w`；锁态探测失败写入可被 `SyncFailureNotifier` 消费的结果；通知增加连续失败阈值。
- **关闭条件**：Mock 401 下 WorkManager 运行后通知出现且归档有对应行；网络恢复后通知撤销。

### 崩溃行受诊断日志开关门控（待产品裁决）

- **核实时间 / 方式**：2026-11-14；静态审查。`DebugLogBuffer.kt:143-144` 的 `recordCrash` 与普通事件共用 `DiagnosticLogGate`；`audit`（`:94`）不受该开关约束；`docs/architecture/已知工程限界.md:69` 已声明该口径。审查项 A6。
- **影响**：用户关闭诊断日志后，致命崩溃同样不留痕，现有「服务崩溃重启」类问题的取证链断裂。
- **触发前提**：诊断日志开关关闭时发生未捕获异常。
- **处置**：未开始，需产品裁决。可选：崩溃行走独立通道（与审计同语义）并更新隐私政策；或维持现口径并在设置页明示「关闭后崩溃也无法留痕」。
- **关闭条件**：裁决登记后实现并验收；关闭开关触发 `am crash` 时，按裁决结果要么归档出现 `[Crash]` 行，要么设置页与隐私政策明确说明不留痕。

### 无进程退出原因采集

- **核实时间 / 方式**：2026-11-14；静态审查。全仓 `getHistoricalProcessExitReasons` / `ApplicationExitInfo` 零命中；`app/build.gradle.kts:248` `minSdk = 36`，API 30+ 能力可用。审查项 C2。
- **影响**：启动后无法区分上次是系统回收、低内存、ANR、native 崩溃还是用户划掉，`P3 自动填充服务进程崩溃重启根因未定位` 的定性条件难以满足。
- **触发前提**：进程非正常退出后重新启动。
- **处置**：未开始。方向：冷启动时读取最近退出记录，经 `audit` 通道写入 `reason`、`status`、`importance`、`pss` 与时间戳。
- **关闭条件**：`kill -9` 与 `am crash` 后重启，导出归档分别出现对应退出原因；无历史时输出「无记录」而非异常。

### onCreate 之前的初始化崩溃不留痕

- **核实时间 / 方式**：2026-11-14；静态审查。`CrashLogRecorder` 在 `MainApplication.kt:137` 的 `onCreate` 中段安装（诊断桥在 `:134`）；全仓 `attachBaseContext` 覆写为 0 处；`app/src/main/AndroidManifest.xml:109-115` 存在 `androidx.startup.InitializationProvider`，其初始化早于 `onCreate`。审查项 A5。
- **影响**：`attachBaseContext`、静态初始化块与 `androidx.startup` Provider 阶段的未捕获异常完全逃逸，归档零记录；这类崩溃发生在冷启动最脆弱阶段，用户只能看到闪退。
- **触发前提**：上述早期阶段抛出未捕获异常。
- **处置**：未开始。方向：在 `attachBaseContext` 安装无依赖的早期处理器，先把异常存入静态 holder，待诊断桥与 `CrashLogRecorder` 就绪后冲刷并恢复单一处理器；早期处理器不得引用 Hilt 注入字段。
- **关闭条件**：debug 构建在 Provider 初始化期注入异常后重启，归档出现 `[Crash]` 行；处理器安装幂等，正常启动不受影响。

### JNI 原生失败不可分型

- **核实时间 / 方式**：2026-11-14；静态审查。`crypto/src/main/rust/src/jni_bridge.rs:82`、`jni_bridge_ext.rs:99-104` 用 `catch_unwind` 把 panic、非法入参与分配失败统一压成 `null`；`NativeFallbackLog.kt:31` 单个 `AtomicBoolean` 使进程内只记录首个回落内核。审查项 C4。
- **影响**：无法区分「ABI/符号缺失」「KAT 不匹配」「运算 panic」；先回落的内核会遮蔽后续内核的失败记录。
- **触发前提**：特定机型缺 ABI、`.so` 缺失、KAT 不符或 Rust 运算 panic。
- **处置**：未开始。方向：按内核名分别记录回落；JNI 边界返回可识别错误码；探活区分静态原因枚举。
- **关闭条件**：单测模拟 KAT 不匹配与 `UnsatisfiedLinkError`，对应内核与原因各输出一行；人为破坏 `.so` 后导出可见原因。

### 错误发生时无现场快照

- **核实时间 / 方式**：2026-11-14；静态审查。全仓无诊断快照入口；进程头仅含版本、构建、API、ABI 与开关状态（`DiagnosticLogBridge.kt:83`），缺 PID、时区、内存等级、存储余量、省电模式与前后台状态。审查项 C3 / B7。
- **影响**：ERROR 只有类型与栈，无法判断是否发生在锁定态、低内存、磁盘满或后台受限场景，复现与根因分析成本高。
- **触发前提**：任意 ERROR/AUDIT 事件。
- **处置**：未开始。方向：新增 `DiagnosticSnapshot`，受「每 op 最多 1 次 + 每分钟限流」约束，仅写枚举与数值，不写字段值或 UUID 全文。
- **关闭条件**：快照行只含枚举/数值；触发一次保存失败后导出含 `snapshot=locked=..., mem=..., storage=...`。

### 敏感缓冲擦除失败被 suppressed 掩盖

- **核实时间 / 方式**：2026-11-14；静态审查。`core/src/main/java/com/keepasskey/core/log/AppLog.kt:165-187` 的 `diagnosticText` 只遍历 `cause`；`core/src/main/java/com/keepasskey/core/result/CleanupFailures.kt:20` 用 `addSuppressed` 挂载清理失败；`SessionOpener.kt:333` 即该类失败场景。审查项 C1。
- **影响**：敏感缓冲未擦除属安全相关失败，但被主异常掩盖，日志中完全不可见；影响范围尚未验证。
- **触发前提**：回滚或清理路径的 `clearSensitiveData()` / `purge()` 抛出异常。
- **处置**：未开始。方向：`diagnosticText` 输出有界 suppressed 异常（类型 + 至少 1 帧定位锚点，不含 message）。
- **关闭条件**：单测断言挂载 suppressed 异常时输出 `Suppressed: 类型` 与帧；真实失败路径可在归档中定位到清理方法。

### 诊断治理动作缺审计

- **核实时间 / 方式**：2026-11-14；静态审查。`SettingsExportController.kt:62-64` 在 `resolver == null` 时直接返回失败，不写日志；`:68-76` 的 `openOutputStream` 返回 null 时仅置失败状态；`SettingsDebugLogActions.kt:20` 清除日志不留审计行。审查项 G3。
- **影响**：导出失败无法自证原因；用户清空日志后，归档中无「日志被主动清除」记录，溯源链断点。
- **触发前提**：SAF 授权失效、目标不可写，或用户点击清除。
- **处置**：未开始。方向：两处失败分支补 `warn`；清除动作写 `audit`。
- **关闭条件**：Mock `openOutputStream` 返回 null 时缓冲出现警告；清除后归档出现审计行且正文已删除。

### 静默 catch 与误导文案

- **核实时间 / 方式**：2026-11-14；静态审查。已逐点确认：`VaultSearchWriteBackCoordinator.kt:91` 吞掉写回异常、`SettingsColdStartSyncGate.kt:36` 空 catch、`SettingsSyncController.kt:41` 吞掉配置检查异常、`SettingsRemoteBrowseHost.kt:44/71` 读取旧凭据失败静默返回 null。同批候选点待处理前复核：`EntryEditPickers.kt:237/305`、`BiometricUnlockCoordinator.kt:202`、`TotpScanDialog.kt:317`。审查项 D10。
- **影响**：功能隐蔽失效无任何记录；「附件为空」「非有效 PNG」等文案把权限/IO 错误曲解为内容问题，用户报障无法定位。
- **触发前提**：权限失效、文件损坏、远端凭据解封失败等。
- **处置**：未开始。方向：逐点补 `AppLog.w` / `debugLog.warn`（仅类名），修正误导文案。
- **关闭条件**：`app/src/main` 中无「空 catch 且无 `cancel-n/a` 说明」；对应点位单测断言留痕；文案与真实原因一致。

### 日志核心守卫与边界缺测试

- **核实时间 / 方式**：2026-11-14；静态审查。`CrashLogRecorder`、`GuardedScope` 无单测；`DiagnosticLogHistoryTest` 仅 2 例，未覆盖 1MB 裁剪、`clear()`、动态周期；`AppLogDiagnosticSinkTest` 未测 `diagnosticLineSink` 与 `KpLog` 防回环；`app/src/androidTest` 无日志归档/SAF 导出用例。审查项 E5。
- **影响**：崩溃串联、协程拦截、归档上限与回环防护的退化无法在 CI 中拦截。
- **触发前提**：相关实现变更。
- **处置**：未开始。方向：补上述 JVM 用例，并新增一条设备端归档/导出用例。
- **关闭条件**：新增用例在定向单测与设备测试中通过，且失败时能指出退化点。

## P3 低危问题与体验改进

### 自动填充服务进程崩溃重启根因未定位

- **核实时间 / 方式**：2026-11-14；真机 logcat 取证（14:43:01 `W/ActivityManager: Scheduling restart of crashed service com.keepasskey/.app.autofill.KeePasskeyAutofillService`，随后新进程写入新的进程诊断头），取证描述留痕于已闭合 P1 条目的处置段（提交 `d2889312`）；与用户 release 诊断日志（15:14 / 15:25 导出）交叉核对，15:25 导出含两个进程诊断头，证实跨进程留存生效。同日静态复核服务回调与进程级异常出口，确认两条取证 / 稳定性缺口：`onConnected`（主线程）与 `onSaveRequest`（系统 Binder 线程）的偏好读取在任何协程守卫之外，读取抛出即进程级崩溃；未捕获异常只进 logcat、不落可导出归档，故崩溃现场无法复盘。未取得崩溃堆栈 / tombstone，未复现，未证实是代码缺陷。
- **影响**：崩溃会清空当时的内存日志缓冲并中断在途的填充 / 保存请求。未观察到凭据损坏、明文泄露或数据丢失。用户划掉应用、系统回收、native 崩溃与未捕获 Java 异常均可产生同形事件，当前仍无法区分具体一次事件。
- **触发前提**：未定位；仅在 14:43 一次真机取证中观察到。
- **处置**：已落地两条：① 进程级未捕获异常在退出前**同步**写入私有目录留存归档（`CrashLogRecorder` → `DebugLogBuffer.recordCrash` → `DiagnosticLogHistory.appendSync`），复用普通行的脱敏与有界栈帧口径记录类型与栈帧、不含异常原文，处理器随后原样转交系统默认处理器，不改变崩溃语义；② `onConnected` / `onSaveRequest` 的偏好读取改为失败即按「通道关闭 / 不保存」收口，自动填充链路上不再有主线程或 Binder 线程的未兜底读取。native 崩溃（SIGSEGV、Rust abort）不经该处理器，仍需 `adb logcat` 或 tombstone；用户关闭诊断日志时不记录崩溃行。设备自验（专用 AVD API 36 / debug）：`am crash` 触发未捕获异常后，归档在进程退出前出现 `[Crash] 未捕获异常，进程即将退出` 与异常类型、栈帧，重启后的新进程头紧随其后；该验证只证明留痕通路成立，不代表真机那次事件的成因。
- **关闭条件**：下次复现时导出诊断日志归档。归档含 `[Crash] 未捕获异常，进程即将退出` 的类型与有界栈帧时，按栈帧定位，确认为代码缺陷则按实际影响重新分级并修根因；归档无该行、仅见新进程头且前后链路完整时，按系统回收收口；两者皆无时按 native 崩溃处理，取 `adb logcat` 崩溃堆栈或 tombstone 定性。用户关闭诊断日志时以 `adb logcat` 为准。

### 日志分级规范与详细模式仅覆盖同步

- **核实时间 / 方式**：2026-11-14；静态审查。级别由调用方自行选择，无使用矩阵；R8 只剥离 `AppLog.v/d`（`app/proguard-rules.pro:209-216`），`DebugLogBuffer.debug` 未剥离；「详细日志模式」的内容消费点只有 `SyncPreferences.kt:36`，`DiagnosticLogBridge.kt:42` 仅用于调栈帧预算。审查项 B3。
- **影响**：级别语义不统一，无法稳定地按级别筛选；非同步模块没有「仅详细模式可见」的等价通道。
- **触发前提**：新增模块或调整日志详细度。
- **处置**：未开始。方向：形成级别使用矩阵（含 `audit` / `crash` 的固定语义），明确详细模式只服务同步或推广为通用通道。
- **关闭条件**：规范落到代码或文档并有静态检查；新模块可按规范选择级别与详细度。

### 静态约束未覆盖 debug/androidTest

- **核实时间 / 方式**：2026-11-14；静态审查。`tools/doc/check_raw_coroutine_scope.py:56-57` 只扫五个生产模块的 `src/main`；`app/src/debug` 仍有 2 处裸 `CoroutineScope(`（`CloudSyncDeviceHostActivity.kt:41`、`P3_628ScenarioReceiver.kt:21`）。审查项 E6。
- **影响**：调试/测试辅助代码的协程异常会直接闪退，打断设备联调；新增违规不会在机检阶段被发现。
- **触发前提**：debug 或 androidTest 代码启动协程。
- **处置**：未开始。方向：扫描面扩至 `src/debug` 与 `src/androidTest`；增加「空 catch」「直调 `DebugLogBuffer`」「异常 message 拼接」检查规则。
- **关闭条件**：机检在现有代码上退出码 0，注入违规样例时退出码非 0。

### 诊断日志复制后长期驻留剪贴板

- **核实时间 / 方式**：2026-11-14；静态审查。`SettingsExportController.kt:100-104` 经 `copyPlainText` 写入，未标记敏感属性、不参与自动擦除；代码注释明确该口径是为便于用户粘贴到反馈渠道。审查项 E4（隐私面）。
- **影响**：含版本、ABI、时序操作序列的诊断文本会进入系统剪贴板历史或第三方输入法缓存，停留时间不受应用控制。
- **触发前提**：用户点击「复制日志」。
- **处置**：未开始，需产品裁决。方向：复制后提供定时擦除或显式提示；若保留现状，在设置页文案中说明剪贴板留存风险。
- **关闭条件**：裁决后实现并验收；选择擦除时超时后内容被清除，选择提示时文案在设置页可见。

### 审查未闭环的待验证项

- **核实时间 / 方式**：2026-11-14；静态审查未能定论，未作为已证事实进入上述条目。审查项 A4 / A8 / E1（积压规模）。
- **内容**：① Worker、无障碍服务、通知广播的主线程段是否仍有未兜底读取；② 多个 Screen 的 Flow `collect` 是否缺 `.catch`；③ 归档 executor 积压到 `Future.get(2s)` 超时的实际规模。
- **影响**：若成立，会扩大 P2「后台任务失败不可见」的实际范围；UI 协程入口已统一经 `safeLaunch` 兜底，故第 ② 项即使成立也只影响「收集中断后界面不再更新」的可观测性，不再构成进程崩溃面。
- **处置**：未开始。方式：注入实验（抛错桩）与真机压力测试；结果出来前不据此排期。
- **关闭条件**：三项分别得出「成立 / 不成立」结论；成立的并入对应条目，不成立的在后续复核中说明依据后移除本条。
