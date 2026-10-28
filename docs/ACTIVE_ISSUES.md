# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

> **本批登记口径**：2026-10-28 的批判性安全审查（伪安全设计与无效防御）与随后的同类项目对照（KeePassDX / Keepass2Android，位于 `参考项目/`）所确认的问题。每条"核实时间与方式"如实区分**本轮逐行复核**与**子代理静态定位、本轮未逐行复核**；后者需先复核是否成立再进入实施。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能或测试有效性缺口 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

### ENG-20261028-01 冲突共同基线"字节相等即污染"判据不成立

- **核实时间与方式**：2026-10-28，本轮逐行复核（`resolveTrustedBase` 判据、两条写出路径、冲突入口的 `localBytes` 来源）；未构造复现。置信度：判据逻辑不成立为高；可达触发路径为中。
- **位置**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictMergeAdjudication.kt:52-72`；`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:91-92`；`app/src/main/java/com/keepasskey/app/sync/SyncCycleSetup.kt:207-215`。
- **问题**：判据为 `trustedBase = parsedBase.takeIf { !baseSnapshotBytes.contentEquals(localBytes) }`，理由写为"KDBX4 随机 IV 使同一内容的两次序列化字节必然不同，字节相等即证明 base 已被本地内容顶替"。但实现中 `.cache` 与 `.basecache` 是用**同一个数组**写出的：`writeCache(remotePath, localBytes)` 之后 `advanceBaseAndPersist(..., localBytes)` 落到 `writeBaseContent(..., localBytes)`；且本地无内容变更时 `localBytes` 直接复用缓存快照。因此"字节相等"是同步收敛后的**常态**，而非污染证据。
- **影响边界**：一旦某条冲突路径传入缓存快照，合法基线会被判为不可信并 `wipeDiscarded`，三方合并退化为空库底版＋并集语义——远端删除不生效（被复活）、基线判断整体失效。不表现为本地新改动被删除（作者选择并集正是为避免该方向）。
- **尚未完成**：实测确认可达路径；以独立摘要（`.baseversion` 对应的字节摘要）自证基线有效性，替换字节相等判据；补"本地未变＋远端变更"与"本地变更＋远端变更"两条负/正对照，证明基线不再被误判。

### ENG-20261028-02 同步基线内容读取未按摘要自证

- **核实时间与方式**：2026-10-28，本轮逐行复核 `readBaseContent` 与基线写出顺序；未构造崩溃复现。
- **位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:313-320`；`sync/src/main/java/com/keepasskey/sync/engine/SyncEngineSupport.kt:75-88`。
- **问题**：`readBaseContent` 直接 `file.readBytes()` 返回，不与 `.baseversion` 的摘要比对；而 `advanceBaseAndPersist` 是**先 `updateBase`（写版本标记）再 `writeBaseContent`（写内容）**。两步之间进程死亡会留下"版本标记已前移、内容仍旧"的组合，该陈旧内容无校验即被当作合法基线交出。
- **影响边界**：`docs/architecture/已知工程限界.md:37` 自述"恢复判定须核对实际字节摘要"，此处未落实；`SyncEngineSupport.kt:71-73` 登记的是**写侧顺序**风险，不覆盖读侧不校验。后果为三方合并基于错误底版比对，产生错误的变更判定。
- **尚未完成**：`readBaseContent` 读取后按 `.baseversion` 摘要自证，不匹配即返回 `null`（回退保守并集）；补崩溃窗口（先写标记后写内容）的新进程恢复用例与正常窗口负对照；同步核对写侧顺序与限界第 11 节登记一致。

### ENG-20261028-03 剪贴板切后台即物理清空，破坏跨应用粘贴

- **核实时间与方式**：2026-10-28，本轮逐行复核调用链；并与 KeePassDX `timeout/ClipboardHelper.kt:62-89` 对照（其仅超时清、无任何切后台清理）。
- **位置**：`app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt:118-122`（`onStop` → `clearPendingSensitive()`）、`296-320`（`performClearIfMatching` 命中即 `markCleared()`）、`352-357`（`markCleared()` → `clearClipboard()` → `clearPrimaryClip()`）。
- **问题**："复制口令 → 切到其它应用粘贴"是唯一的实际使用路径；切走瞬间剪贴板仍持有该值，摘要命中即被物理清空，用户到达目标表单时剪贴板已空。同时把"超时清空"（代码自称是"用户唯一在等待的清除时刻"）架空为几乎不会触发的路径。
- **影响边界**：作者的注释表明这是"敏感值只允许在前台窗口内驻留"的有意策略，但与产品用途直接冲突，且与同业的超时清空模型不同。另有待确认项：后台不可读剪贴板时 `ClipboardClearPolicy.shouldClearOnUnreadableClipboard` 分支是否会在无覆盖证据时清空他处内容。
- **尚未完成**：移除 `onStop` 立即清空（保留超时清空、熄屏清空、会话锁定清空、冷启动对账四条）；补设备用例证明"复制后切到其它应用仍可粘贴"，并证明超时/熄屏/锁定三条路径仍清空；确认后台不可读分支不误清他处内容。

### ENG-20261028-04 前台闲置锁定交互刷新从未接线

- **核实时间与方式**：2026-10-28，本轮逐行复核并全仓检索 `onUserInteraction` 调用方；另与 KeePassDX `timeout/TimeoutHelper.kt:100-110`、Keepass2Android `timeout/TimeoutHelper.cs:96-117` 对照（两家均无前台闲置档，超时均为"离开应用后计时"）。
- **位置**：`app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:115-121`；`app/src/main/java/com/keepasskey/app/security/ForegroundIdleLockPolicy.kt:13`。
- **问题**：`onUserInteraction()` 在整个 `app/src/main` **无任何调用方**（仅 `ForegroundIdleLockDeviceTest` 调用）；`MainActivity` 未重写 `onUserInteraction`，Compose 侧无挂载点。开启前台闲置锁定后计时自解锁/回前台起单向流逝，持续阅读或编辑也会到点强锁；`shouldRefreshOnInteraction` 为死逻辑。
- **影响边界**：属于功能阻断与未保存改动丢失风险，不是安全穿透。对照显示两家同业**没有**"前台闲置"这一档，本仓若保留该档必须接线；若不愿接线，等同于自创了一档同业不存在且无法工作的控制。
- **尚未完成**：二选一并给出实测——(a) 在 `MainActivity`/分发层接线并转发到 `AutoLockManager`，补前台持续交互不锁与真正闲置到点锁的双向设备用例；(b) 删除前台闲置档，只保留与同业一致的后台档，同步更新相关设置项与文案。

### ENG-20261028-05 通道开关不在凭据交付与签发路径

- **核实时间与方式**：2026-10-28，本轮逐行复核并全仓检索两个开关的调用点；交付路径可达性未实测。
- **位置**：开关读取仅见于 `app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt:137,230,247` 与 `CredentialResponseAssembler.kt:233`；`PasswordFillActivity.kt`、`PasskeyAssertionActivity.kt`、`PasswordSaveActivity.kt`、`CredentialUnlockActivity.kt` 均无校验。
- **问题**：`docs/architecture/产品裁决登记.md:34` 声明"通道开关控制候选、**交付**和保存"，但交付与签发 Activity 自身不校验。存在"锁库时挂起解锁界面 → 用户在设置中关闭通道 → 返回并继续解锁 → 凭据仍被下发/签发"的窗口。
- **影响边界**：是否可达取决于服务侧校验与已挂起流程的调用链（未实测）；不影响"关闭后系统服务列表仍存在"这一已登记的产品口径。
- **尚未完成**：实测该窗口是否可达；若可达则在交付与签发入口补齐开关门控并对已关闭情形 fail-closed；补"关闭开关后各交付入口一律不下发"的设备用例（含挂起后再返回的时序）。

### ENG-20261028-06 CM 通道未绑定包名放行与缺失 RP 放行的组合缺口

- **核实时间与方式**：2026-10-28，本轮逐行复核两处判据与调用点；并与 KeePassDX 对照（`passkey/util/PassHelper.kt:227-230,241,284-291`、`passkey/data/AndroidPrivilegedApp.kt:46,104`、`assets/passkeys_privileged_apps_*.json`、`viewmodel/PasskeyLauncherViewModel.kt:156`：平台背书 `CallingAppInfo.signingInfo` ＋ 内置指纹白名单 ＋ 用户可编辑 ＋ 未知应用抛 `PrivilegedException`）。
- **位置**：`app/src/main/java/com/keepasskey/app/passkey/CredentialManagerPackageBindingGate.kt:60-79`（`UNBOUND` 仍 `allowsPackageDimension == true`）；`app/src/main/java/com/keepasskey/app/passkey/PasskeyRequestRpBinding.kt:5-10`（`requestRpId.isBlank()` 直接 `return true`）。
- **问题**：两者各自的取舍都有注释声明（前者 KDoc 明写"对从未绑定过的包名越权面依然存在，不得读作已加固"，后者注明"缺失 RP 才由既有包名/签名门控裁决"），但**叠加后**"无 rpId 的非浏览器请求 ＋ 从未绑定的包名"可让凭据对任意 `relyingPartyId` 签发；存量库/导入库中的 `android://<包名>` 条目对侧载同名包不设防。
- **影响边界**：不是隐藏漏洞，而是两个已声明取舍的组合缺口；系统凭据选择界面会展示请求方，因而不是静默外泄。本仓"CM 通道没有中立面、用户无处授权"的前提被同业反驳——KeePassDX 正是通过内置指纹白名单＋设置页可编辑提供了授权面。
- **尚未完成**：产品裁决二选一并实施——(a) 未绑定包名不再静默放行，改由显式授权面（白名单/逐项指认）承接，缺失 rpId 的非浏览器请求要求凭据 rpId 与该包绑定记录一致，否则拒绝；(b) 维持现状并把组合缺口如实登记为接受风险。另需确认 WebAuthn 规范对缺失 `rpId` 的要求（本轮未查阅 `参考项目/passkeys参考/`）。

### ENG-20261028-07 口令强度估算器的三处实质缺陷

- **核实时间与方式**：2026-10-28，本轮逐行复核（日期分支、码点计数、周期阈值）；未实跑两路径对拍。对照：官方 `参考项目/KeePass-2.61.1-Source/KeePassLib/Cryptography/QualityEstimation.cs` **765 行**（`PopularPasswords.cs` 黑名单同目录），本仓 714 行 Rust ＋ 657 行 JVM 回退属**上游行为的移植**。
- **位置**：`crypto/src/main/rust/src/strength.rs`（日期分支、`chars().count()`、`PERIODIC_MIN_REPEATS`）与 `crypto/src/main/java/com/keepasskey/crypto/strength/PasswordStrengthFallback*.kt`。
- **问题**：① 6 位日期只校验前 4 位、后 2 位完全未读取，真正的 `YYMMDD` 因首位为年份反而无法识别，任意前 4 位落在 0101–1231 的 6 位数字都被重罚；② Rust 按 Unicode 标量计数、JVM 按 UTF-16 码元计数，7 个 emoji 的口令在原生侧判"过短/弱"、在回退侧判"合格放行"，代理对还使重复检测完全失效；③ 周期重复阈值 ≥3 使 `passwordpassword` 绕过周期衰减被评为最高档。
- **影响边界**：不能按"退役为 NIST 简单规则"处理——与官方估算器的一致性是有意取舍（`docs/architecture/产品裁决登记.md:12` 保留自研估算器、JVM 回退对应 Rust 模型、更换须重新标定门槛）。真正的问题是这三处偏差与"双实现必须同步"的成本。
- **尚未完成**：修正日期格式判定（补齐 6 位/8 位常用格式并对末 2 位校验）、统一按码点计数与重复检测、修正周期阈值或对 2 次重复施加衰减；补两路径**同输入同结论**的对拍用例（含 emoji、代理对、`passwordpassword`、6/8 位日期正反例），若门槛变化需重新标定并说明。

### ENG-20261028-08 Argon2 参数越界时回退 JVM 而非拒绝

- **核实时间与方式**：2026-10-28，本轮逐行复核；并与 KeePassDX 对照（`database/src/main/java/com/kunzisoft/keepass/database/crypto/kdf/Argon2Kdf.kt:107-117`：越界直接抛 `KDFParallelismDatabaseException`/`KDFMemoryDatabaseException`/`KDFIterationDatabaseException`）。
- **位置**：`crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt:63-89`。
- **问题**：参数超出原生上界（`isWithinKdfBounds` 为 false）时被当作"原生不可用"处理，转入 BouncyCastle 纯 Java 回退路径。原生都判定为过大的负载反而交给受 GC 限制的 JVM 承担，方向颠倒。
- **影响边界**：同层还有 `database/.../KdbxKdfAcceptance.kt` 的前置上界，正常打开路径不会走到该分支；问题是该分支的方向错误与静默降级语义。
- **尚未完成**：越界时 fail-closed 抛出 KDF 异常并给出可处理语义；删除该回退分支或其镜像常量；补越界参数（内存/迭代/并行度）在原生与回退可用两种环境下的拒绝用例，证明不会静默转 JVM。

### ENG-20261028-09 冲突裁决落盘失败后不回滚内存且清空待决会话

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。触发需落盘失败。
- **位置**：`app/src/main/java/com/keepasskey/app/sync/SyncConflictResolution.kt:130-144,219-240`；`app/src/main/java/com/keepasskey/app/sync/SyncConflictAutoMerge.kt:133-166`。
- **问题**：先上传云端并采纳内存合并树，再 `databaseSession.save()`；当保存返回 `Failure` 时仍无条件 `clearPendingConflictSession()`，且未把内存树回滚到采纳前快照，只调用了 `settlement?.reject()`。
- **影响边界**：终态可能为"云端已合并、本地文件未合并、本地内存已合并、基线未前移、冲突会话与用户裁决凭据全部丢失"，应用被杀后未落盘的合并结果消失。可达性未复核。
- **尚未完成**：先复核该路径成立性；仅落盘成功时才清空冲突会话，失败时回滚内存到采纳前快照并保留会话供重试；补"上传成功＋落盘失败"的失败注入用例与成功负对照。

### ENG-20261028-10 零长度 BINARY 内层字段被静默丢弃，可能导致附件索引错位

- **核实时间与方式**：2026-10-28，本轮逐行复核丢弃分支；"索引错位导致附件错配"的后果未构造复现。
- **位置**：`database/src/main/java/com/keepasskey/database/file/InnerHeaderReader.kt:74-107`。
- **问题**：`readBinaryFieldAsStream` 在 `fieldLen <= FLAGS_FIELD_BYTES`（即 0 或 1）时返回 false，交回 `acceptField`；`acceptField` 中 `BINARY -> if (fieldData.isNotEmpty()) acceptInlineBinary(fieldData)`，即长度为 0 的 BINARY 字段被静默忽略、不进 `binaries` 池。
- **影响边界**：KDBX 条目通过整型索引关联附件池，跳过一个池项会使后续附件索引整体前移一位，条目可能关联到错误的附件内容（畸形文件可达；正常文件不触发）。属对抗性输入下的健壮性缺陷，不等同于随机库损坏。
- **尚未完成**：零长度 BINARY 字段改为 fail-closed 抛 `KdbxCorruptFileException`（对齐内层头其它字段的长度校验风格）；补畸形库用例证明拒绝而非静默错配，并补合法库负对照。

## P2 中危缺陷与协议/测试缺口

### ENG-20261028-11 内存秘密保护链与其衍生物（原地擦除→读取期 HMAC→展示读降级→反向白名单）

- **核实时间与方式**：2026-10-28，本轮逐行复核关键节点；并对照 KeePassDX `database/.../element/security/ProtectedString.kt:30-78`（仅明文 `charArray` ＋ 布尔，`toString()` 直出明文，`clear()` 挂 `// TODO Use to properly clear data`）与 Keepass2Android `.../database/security/ProtectedString.java:26-52`（`String` ＋ 布尔，`toString()` 返回明文）。
- **位置**：`core/src/main/java/com/keepasskey/core/security/InMemoryCipher.kt`；`core/src/main/java/com/keepasskey/core/security/ProtectedString.kt`（`plainBytes()` 读取期 HMAC 复核、构造期清零）；`core/src/main/java/com/keepasskey/core/model/KdbxGroup.kt`（换树时原地清零旧节点密文）；`readStringForDisplay` 系列；`tools/doc/check_projection_read_safety.py`。
- **问题**：因"防堆转储"引入进程级密钥的内存加密，其密钥与被加密数据同进程同生命周期；为掩盖 copy-on-write 换树时**原地擦除密文**与 UI 投影的竞态（代码注释记载曾造成真机崩溃 `ISSUE-P0-531`），在每次读取上加 HMAC 复核，再由展示读口吞异常返回空串，再用 372 行脚本与反向白名单锁住调用纪律。整条链不防御已声明不设防的攻击者，却使字段读取（含自动填充搜索热路径）承担解密＋哈希＋分配成本。
- **影响边界**：属伪安全与性能/维护负担，不是新增漏洞；被清零的是密文而非明文，对保护明文无作用。同业的擦除纪律更弱，本仓的严格度是真投入，但"擦除密文/擦除不可能擦干净的 String"这类动作仍无效。
- **尚未完成**：先移除换树时的原地擦除（旧树交 GC），据此删除读取期 HMAC 复核与展示读降级口，并退役 `check_projection_read_safety.py`；内存加密降级为"仅对跨进程/落盘交付的临时缓冲"或整体移除，同步更新 `docs/architecture/敏感缓冲所有权契约.md` 与 `已知工程限界.md`；补列表滚动与自动填充搜索的性能读数，证明删除后无回归且不再有并发读崩溃路径。

### ENG-20261028-12 源码文本锁定的测试与静态扫描脚本

- **核实时间与方式**：2026-10-28，本轮逐行复核代表性用例与脚本清单（37 个测试引用 `src/main/java` 等源码路径；`tools/doc/` 下 `check_projection_read_safety.py`、`check_plaintext_carrier_to_string.py`、`check_raw_coroutine_scope.py`、`check_message_not_in_user_text.py`）。
- **位置**：例 `app/src/test/java/com/keepasskey/app/ui/AutofillChannelSwitchWiringTest.kt`（通篇 `readSource(...)` ＋ 文本断言）；`database/src/test/.../InnerStreamKeyWipeTest.kt`、`ChangeCredentialsKeyFileWipeTest.kt`、`AttachmentDataOwnershipTest.kt`、`app/src/test/.../ProguardRuleRealityTest.kt`、`SensitiveToStringRedactionTest.kt`。
- **问题**：违反工程规则"不新增只锁定源码文本、注释、私有结构或非契约调用次数的测试；静态约束优先使用现有编译器、Lint 或扫描入口，不为每次整改增加专用扫描脚本"。这类断言无行为验证能力（逻辑写进死分支也能通过），却阻断合法重构。
- **影响边界**：危害在测试有效性与维护成本，不是运行时缺陷；删改时须保证其原本想守的行为改由行为测试或既有 Lint/编译入口覆盖。另注：`tools/doc/gate_readings.py` 当前会调用其中三个扫描脚本，退役时须同步调整该门禁入口，否则门禁自身失效。
- **尚未完成**：逐项判定每个文本断言原本要守的行为，能用行为测试表达的改写、不能表达的删除并在提交说明交代理由；退役专用扫描脚本（保留 `check_md_links.py` 等文档门禁）；确认删除后标准测试与 Lint 仍覆盖相同风险。

### ENG-20261028-13 字段引用 `SearchIn=P` 物化全库口令为不可擦 String

- **核实时间与方式**：2026-10-28，本轮逐行复核索引与取值实现（代码注释自陈"P 面会物化 String"）；触发面为 `{REF:...@P:...}` 形态。
- **位置**：`database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt:225-237`（`indexOf` 建 `TreeMap<String, KdbxEntry>`）与 `:311`（口令分支把口令字段的读取结果直接作为索引值）。
- **问题**：索引口令面时会遍历全库把每条口令解密为 `java.lang.String` 作为 `TreeMap` 键，直至 GC；违反本仓"口令不得物化为不可擦 String"的缓冲契约。同包的 Char 通道（`FieldReferenceCharExpansion`）已按 Char 区域线性扫描避开该问题。
- **影响边界**：触发面窄（检索面为 `P` 的引用），且同业的 `ProtectedString.toString()` 同样物化明文，属行业常态；本仓的问题在于它同时违反了自身契约。
- **尚未完成**：`SearchIn == PASSWORD` 时不构建 String 索引，统一走 Char 区域比对并在用毕清零；补"含口令面引用的库在复制/填充路径不产生长期驻留的明文 String"的可验证用例（可结合既有缓冲契约测试）。

### ENG-20261028-14 ChaCha20 解密流"不做过量预读"导致单字节读退化为逐字节 JNI

- **核实时间与方式**：2026-10-28，本轮逐行复核 `refill` 与单字节读取路径；上层消费方式未逐一确认。
- **位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/ChaCha20CipherEngine.kt:302-303,324-345`。
- **问题**：`read()` 单字节路径走 `read(single,0,1)` → `refill(1)` → `cap = minOf(target, CHUNK_SIZE) = 1`，每字节一次 JNI 调用与密钥流定位，64 KiB 堆外缓冲形同虚设；注释给出的理由是"不做过量预读防明文暴露"，但该流明文本就"交付即归零"，少预读没有安全收益。
- **影响边界**：批量读取（上层按请求量读）不受影响，只有逐字节消费的调用方会退化；crypto/database 侧未发现 `BufferedInputStream` 包装，实际影响取决于具体消费方式（待确认）。
- **尚未完成**：确认生产消费方是否存在逐字节读取；固定填满 `CHUNK_SIZE` 并按游标分发、交付即归零；补大库打开/保存的墙钟读数对比，证明无性能回归且擦除语义不变。

### ENG-20261028-15 JVM 回退路径不擦除 Argon2 工作内存，且双路径等价性宣称失实

- **核实时间与方式**：2026-10-28，本轮逐行复核原生与 JVM 两侧实现。
- **位置**：`crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt:134-139`（BouncyCastle `Argon2BytesGenerator` 内部 `Block[]` 无清零）；对照 `crypto/src/main/rust/src/lib.rs:163-168`（自持 `Zeroizing<Vec<Block>>` 确定性清零）。
- **问题**：原生路径有确定性工作内存擦除，JVM 回退路径完全没有；对外宣称"双路径具备对等秘密擦除语义"与事实不符。
- **影响边界**：BouncyCastle 未暴露内部缓冲清零 API，短期内无法在回退路径达成同级擦除；应如实登记为限界，而不是继续宣称等价。
- **尚未完成**：在 `已知工程限界.md` 记明回退路径的工作内存不可擦及其适用环境；修正文档/注释中"双路径等价"的表述；评估回退路径是否仍需保留（与 ENG-20261028-08 一并决策）。

### ENG-20261028-16 KDF 参数上界常量在 database 与 crypto 两处重复定义

- **核实时间与方式**：2026-10-28，本轮逐行复核两处常量与第三处 JNI 校验。
- **位置**：`database/src/main/java/com/keepasskey/database/file/KdbxKdfAcceptance.kt`（约 23-56 行）与 `crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt:180-220`（镜像常量），另有 Rust 侧 `jni_bridge` 的基础校验。
- **问题**：同一套上下界在 `database`（业务边界，不可绕过）与 `crypto`（镜像副本）各定义一次，属跨模块重复；`crypto` 侧的副本还伴随 ENG-20261028-08 中的错误回退。
- **影响边界**：`database` 层与 Rust 层属合理纵深；重复的是 `crypto` 层。常量分处两模块源于依赖方向约束（`crypto` 不得依赖 `database`），需下沉到 `core` 或由 `database` 注入。
- **尚未完成**：把上界常量下沉到共享模块或作为参数注入，消除镜像副本；确认改动后越界参数仍在最早边界被拒绝；补参数边界用例（正常、临界、越界）在三层各自的预期。

### ENG-20261028-17 每次保存强制 `.bak` 全量复制，并以非原子复制兜底

- **核实时间与方式**：2026-10-28，本轮逐行复核写出流程；并与同业对照（Keepass2Android `src/keepass2android-app/app/App.cs:206-212` 用 `CreateBackups_key` 偏好控制；KeePassDX 未发现 `.bak`，保存用 `cacheDir` 临时文件 `action/SaveDatabaseRunnable.kt:68`）。
- **位置**：`database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt:105-121,155-194,238-243`。
- **问题**：单次保存产生"写临时文件 ＋ 全量复制成 `.kdbx.bak` ＋ 原子改名"，即 2 次全量写、1 次全量读、4 次 fsync；原子 rename 本身已提供崩溃安全。`Files.move` 失败时的 `fallbackReplace` 退回 `Files.copy`（先截断再写），该窗口断电/强杀会把原库变成半截文件。
- **影响边界**：`.bak` 有可用性价值（旧凭据可打开的上一稳定版本），但默认强制且与原子替换冗余；契约"事务写入无关闭开关"应只约束原子替换本身。同业把备份做成偏好项而非每次强制。
- **尚未完成**：主保存路径只保留"临时文件 ＋ fsync ＋ 原子改名"；`.bak` 改为可选或降级为非阻塞快照；`Files.move` 失败时拒绝覆盖并如实报错，不使用非原子复制兜底；补磁盘满、只读目录、move 失败三类故障下的文件完整性用例。

### ENG-20261028-18 持久化基线在内存长期持有多份整库密文拷贝，且吞掉 `Throwable`

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`database/src/main/java/com/keepasskey/database/session/SessionPersistedBaseline.kt:52-118,148,159-165`。
- **问题**：为漂移合并基线，在会话内捕获最多约 160 MiB 的整库密文，并在多处 `toByteArray()`/`clone()` 形成多份拷贝；`retainPersistedBaselineBestEffort` 以 `catch (_: Throwable)` 静默吞掉（含 `OutOfMemoryError`），失败时上层无从知晓基线未登记。
- **影响边界**：后果为内存压力（大库/低端机）与基线登记失败被掩盖；与项目"不为减少分配破坏所有权保证"的要求需一并权衡。
- **尚未完成**：先复核触发条件与峰值内存读数；需要基线时从本地已持久化版本按摘要重读，避免长期持有整库拷贝；禁止捕获顶层 `Throwable`，失败须可被协调器感知（至少重置基线状态）；补大库内存读数与失败路径用例。

### ENG-20261028-19 保存链先发布内存树后写盘，失败时形成内存/磁盘分叉窗口

- **核实时间与方式**：2026-10-28，本轮逐行复核 `saveLocked` 与 `commitPreparedSave` 时序；未构造失败复现。
- **位置**：`database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:129-150,308-320`。
- **问题**：修剪超期历史时先 `core.database.value = pruned` 并擦除下线历史的密文，随后才 `writer(serialized)`；写盘失败时内存已是修剪后状态、磁盘仍是旧状态（`commitPreparedSave` 同型）。作者注释说明该顺序是为规避 `ISSUE-P0-531` 的并发读崩溃。
- **影响边界**：影响温和——修剪属维护操作，磁盘保留更多历史，下次成功保存自然收敛，不构成本地新改动丢失；但"内存已更新、文件未写"的半步状态与"发布与持久化同一提交边界"的表述存在张力。
- **尚未完成**：明确该窗口的处置（接受并登记到限界，或改为"落盘成功后才发布并擦除"且不让并发读命中清零实例）；补写盘失败（磁盘满/SAF 拒绝）路径的内存与磁盘一致性断言。

### ENG-20261028-20 WebDAV 覆盖兜底先删后改，S3 条件写存在冗余预检与签名端口缺陷

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt:145-148`；`sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt:338-345`；`sync/src/main/java/com/keepasskey/sync/s3/S3KeyCodec.kt:71-73` 与 `S3RequestSigner.kt:62`。
- **问题**：① 服务端返回 409/423 时兜底路径先 `delete(remotePath)` 再无条件下发 MOVE，两步之间进程死亡/断网会使远端库被删除且新版本未就位；② 条件 PUT 前额外发一次 HEAD（不消除 TOCTOU，仅增加一次 RTT，服务端 `If-Match` 已能原子拒绝）；③ `hostOf` 保留显式默认端口（如 `:443`）而 OkHttp 发请求时剥除，导致 SigV4 签名不匹配。
- **影响边界**：①的后果为远端数据丢失，但需特定 WebDAV 实现＋崩溃时点；②为性能与无效防护；③为兼容性缺陷。
- **尚未完成**：先复核三项成立性；移除先删后改兜底（改为如实报协议不兼容并保留远端文件）；删除 `expectedEtag` 非空时的 HEAD 预检；签名主机名剥除协议默认端口；补对应用例（409/423 服务端不丢远端文件、条件写失败不覆盖、显式默认端口端点签名通过）。

### ENG-20261028-21 锁定态保存把明文口令放进系统持有的 PendingIntent

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`app/src/main/java/com/keepasskey/app/autofill/LockedSaveRequestPendingIntent.kt:29-37`（把口令明文写入 `EXTRA_PASSWORD` 附加项）；调用方 `KeePasskeyAutofillService.kt:443`。
- **问题**：库锁定时捕获到的新口令以明文 `String` 作为 Extra 放入 PendingIntent 交系统服务持有，绕过本仓对敏感值生命周期的控制（无本方清零时机）。
- **影响边界**：需先复核是否确实明文传递、以及系统侧持有的时长与可见性；若可达，属敏感值明文出进程。
- **尚未完成**：复核成立性；改为仅传递无敏感信息的一次性索引，口令以受控临时密文/私有缓存承载并在解锁后立即换取与擦除；补锁定态保存全流程的敏感值生命周期用例。

### ENG-20261028-22 S3 虚拟主机模式下自建私网端点被 SSRF 守卫误杀

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**；与 Keepass2Android `WebDavStorage.java:170,194-210` 对照（仅明文开关＋系统 CA＋证书错误处理，无地址级拦截）。
- **位置**：`sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt:142-143`；`sync/src/main/java/com/keepasskey/sync/network/SsrfGuardDns.kt:300-314`。
- **问题**：白名单只登记 `endpointHost`，虚拟主机模式下实际请求主机为 `bucket.endpointHost`，自建 MinIO/Ceph 场景解析到内网即被拒。
- **影响边界**：与"用户明确配置的自建内网端点可用"（`产品裁决登记.md:20`）冲突，属合法配置被阻断；SSRF 守卫本身在 DAL 抓取路径（目标由调用方 rpId 决定）有独立价值，不宜整体删除。
- **尚未完成**：复核成立性；虚拟主机模式把 `bucket.endpointHost` 一并纳入豁免；明确守卫的适用域（用户自建同步端点 vs 调用方可控的 DAL 目标），并据此收敛同步端点侧的策略；补自建私网虚拟主机端点的连通用例。

### ENG-20261028-23 构建期签名脚本自校验为同仓循环自证

- **核实时间与方式**：2026-10-28，本轮逐行复核；与 `已知工程限界.md:55` 的自述一致。
- **位置**：`app/build.gradle.kts:39-88`。
- **问题**：对 `tools/signing/read-cred.ps1` 做 SHA-256 比对，但哈希基线与被校验脚本同仓同 commit，同时修改即可绿灯放行；同时使每次 release 配置期执行外部 PowerShell。
- **影响边界**：零防护力（限界已自述"仓内哈希不能阻止同时修改脚本与基线的恶意变更"），属伪安全与额外执行面。
- **尚未完成**：删除仓内哈希自校验，依赖代码审阅；签名口令改为环境变量/外部凭据注入，避免构建脚本在配置阶段调用同仓脚本。

### ENG-20261028-24 R8 keep 规则与自身注释矛盾且范围过宽

- **核实时间与方式**：2026-10-28，本轮逐行复核规则与注释。
- **位置**：`app/proguard-rules.pro:138-143`（注释写"zxing 为纯算法库、全仓无反射消费方，**无需 keep**"，下一行即 `-keep class com.google.zxing.** { *; }`）；同类过宽保留见 `:38-43`（`org.bouncycastle.**`、`dagger.**`）。
- **问题**：规则与理由矛盾；整包保留禁用 R8 的死代码裁剪与混淆，增加产物体积，属"看起来加固、实际只是开销"。
- **影响边界**：与 `已知工程限界.md:55` 无关；须以真实 release 构建与运行验证，不能只改文本。
- **尚未完成**：删除 ZXing 整包 keep；按实际反射/JNI 需求收窄 BC/Dagger 保留范围；用 release 构建实测确认扫码、加密与依赖注入路径无裁剪导致的功能缺失。

### ENG-20261028-25 会话凭据门禁在多数路径上缺省放行

- **核实时间与方式**：2026-10-28，本轮逐行复核实现；各调用点是否注入上下文沿用子代理结论，未逐条复核。
- **位置**：`database/src/main/java/com/keepasskey/database/session/SessionRequestGuard.kt:8`（上下文取不到即 `return` 放行）；声明处见 `SessionContentMutations.kt`、`SessionPersistence.kt` 多处 `requireCurrentSessionRequest(credentialTicket())`。
- **问题**：常规条目编辑、分组编辑、同步保存等路径未在协程上下文注入 `SessionCredentialRequest`，使校验被旁路，却造成"所有写入都受凭据代次门禁"的印象。
- **影响边界**：门禁本身只是进程内代次标记（符合"不增加防回滚/MAC 层"的取舍），问题在声明与实效不一致。
- **尚未完成**：复核各调用点的上下文注入情况；统一为"缺失上下文即拒绝"或在文档/注释中如实收窄为协作标记；补换密/换库/锁库交错下的写入拒绝用例。

### ENG-20261028-26 KDBX XML 加固链可由 XmlPullParser 替代，消掉特性探针

- **核实时间与方式**：2026-10-28，本轮逐行复核探针与 handler 兜底；并与 KeePassDX（`database/.../file/input/DatabaseInputKDBX.kt:217` 用 `XmlPullParserFactory` 流式解析）与 Keepass2Android（`src/KeePassLib2Android/Utility/XmlUtilEx.cs:44-67` 集中一处 `XmlResolver=null` ＋ `DtdProcessing=Prohibit`）对照。
- **位置**：`database/src/main/java/com/keepasskey/database/xml/KdbxXmlParser.kt:180-275`（4 项工厂特性 ＋ `probeHardenedFeatures()` ＋ `ThreadLocal` 工厂缓存）；真正生效的兜底为 `DefaultHandler2.startDTD` 拒绝 DTD 与 `resolveEntity` 拒绝外部实体。
- **问题**：工厂特性在 Android 上因 `setFeature` 延迟抛异常而逐项被探针剔除（代码注释已记载 `ISSUE-P1-12` 设备实测），该层实际是"尽力加固"的装饰，却带着探测链、告警与线程本地缓存的维护成本。
- **影响边界**：不构成漏洞（handler 侧 fail-closed ＋ 深度/元素数/解压预算仍生效）；属可简化项，不是必须整改的缺陷。
- **尚未完成**：确认改用 `XmlPullParser` 后 DTD 拒绝与外部实体隔离仍成立；保留解析上限与预算；设备侧保留并扩展 DTD 拒绝回归（既有 `KdbxXmlDtdRejectionDeviceTest`）；删除探针与线程本地工厂缓存。

### ENG-20261028-27 Release 构建丢弃异常堆栈与 message，线上问题不可诊断

- **核实时间与方式**：2026-10-28，本轮逐行复核 `sanitize`。
- **位置**：`core/src/main/java/com/keepasskey/core/log/AppLog.kt:54-56`（`"$message <${t.javaClass.name}>"`）。
- **问题**：release 下 `w/e` 带异常时仅保留类名，message 与堆栈全部丢弃；注释理由是"堆栈行也可能引用含敏感数据的代码上下文"，但 JVM 堆栈只含类/方法/文件/行号，不含局部变量明文。
- **影响边界**：属可用性与可维护性问题（线上故障失明）；对 message 含外部输入（主机名、rpId 等）的顾虑成立，应按类型化白名单处理而非整体丢弃。
- **尚未完成**：release 保留（经 R8 混淆的）堆栈；对 `t.message` 做类型化白名单或模式过滤；确认不引入敏感内容，并补一条"release 日志包含类名与堆栈、不含被过滤字段"的用例。

### ENG-20261028-28 Release 构建仍向系统 Logcat 输出 `i` 与审计通道

- **核实时间与方式**：2026-10-28，本轮复核 `AppLog.i` 无条件输出；审计通道回显与 DI 注入沿用子代理结论，未逐行复核。
- **位置**：`core/src/main/java/com/keepasskey/core/log/AppLog.kt:32`；`app/src/main/java/com/keepasskey/app/data/logger/DebugLogBuffer.kt:59,76-82` 与 `DiagnosticLogGate.kt:67`、`DiagnosticLogModule`（`logcatEchoEnabled = true`）。
- **问题**：`v/d` 有"仅调试期"的双保险，但 `i` 与 audit 通道在 release 仍写系统 Logcat（导入/导出等操作会留下行为特征），与"release 不输出日志"的直觉假设不符。
- **影响边界**：内容已做简化脱敏，泄露面为行为特征与来源标识（非明文口令/密钥）。
- **尚未完成**：把 `logcatEchoEnabled` 绑定到 `BuildConfig.DEBUG`；明确 `i`/audit 在 release 的目标（内存缓冲可留，回显应关）；补 release 构建下的日志输出断言。

### ENG-20261028-29 健康检查的口令重用检测未展开字段引用，存在漏报

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`database/src/main/java/com/keepasskey/database/audit/HealthCheckEngine.kt:87-104,145,179-181`。
- **问题**：第一趟建重用索引时直接读口令原文算 SHA-256，未展开 `{REF:...}`；弱口令检测走展开路径，两趟口径不一致，含引用的条目与目标条目不会被判为重用。
- **影响边界**：安全审计漏报（用户名/口令重用提示失真），不改变库内容。
- **尚未完成**：复核成立性；第一趟同样展开引用后再计算摘要（中间字符数组用毕清零），保持两趟同口径；补含引用的重用/弱口令正反用例。

### ENG-20261028-30 解压预算流未重写 `skip`，上限可被绕过

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`database/src/main/java/com/keepasskey/database/file/SizeBoundedInputStream.kt:14-43`（重写 `read()` 与 `read(b,off,len)`，未重写 `skip(n)`）。
- **问题**：若上层或解析器调用 `skip`，计数不累加，128 MiB 解压上限可被局部绕过。
- **影响边界**：需确认实际调用方是否存在 `skip`（XML 解析器与 ZIP 解压路径）；属对抗性输入的预算绕过面。
- **尚未完成**：复核调用方；重写 `skip` 并计入上限（或在 `super.skip` 返回正值时计数校验）；补构造用例证明上限不可绕过。

### ENG-20261028-31 同步单周期多次整库序列化与 KDF 派生

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`app/src/main/java/com/keepasskey/app/sync/SyncCycleSetup.kt:208`；`SyncCycleCommitPaths.kt:110,133`；`SyncConflictAutoMerge.kt:120,147,178`。
- **问题**：一个冲突合并周期内可能发生 4–6 次整库 XML 序列化＋压缩＋KDF 派生（含 412 重入），其中至少一次是针对与已加密产物内容相同的重复序列化。
- **影响边界**：大库/低端机上 CPU、电量与卡顿开销；不影响正确性。
- **尚未完成**：复核计数；合并产物已加密时复用该字节落盘，避免重复序列化；补大库同步周期的耗时读数与"落盘字节与上传字节一致"断言。

### ENG-20261028-32 WebDAV PROPFIND 解析失败被包装成成功响应

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt:83-92`（`catch (e: Throwable)` 后返回空元数据）；`WebDavSyncProvider.kt:202-224` 将其包装为 `Result.success(RemoteFileMetadata(etag=""))`。
- **问题**：服务端返回损坏 XML/错误页或被截断时，解析失败被当作"文件存在但无 ETag"，上层按无 ETag 逻辑执行无条件覆盖上传，可能静默覆盖他端并发修改。
- **影响边界**：把失败伪装成成功，属错误处理语义缺陷；需要具体服务端行为验证。
- **尚未完成**：复核成立性；解析失败改为 fail-closed 上抛协议错误；补损坏响应与截断响应的用例，证明不会转为无条件覆盖。

### ENG-20261028-33 敏感对话框的防截屏保护存在遗漏与接线错误

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/PasskeyImportConfirmDialog.kt:51-54`（`SecureDialogWindowEffect` 写在 `AlertDialog` 外层，解析不到对话框窗口而静默空转，且未传 `DialogProperties`）；`app/src/main/java/com/keepasskey/app/ui/screens/database/OpenExistingVaultDialog.kt:116`（含 WebDAV/S3 凭据输入但未设 `SecureFlagPolicy.SecureOn`）。
- **问题**：与 `产品裁决登记.md:51-52`"建库、改主密钥、子库凭据、附件预览和修订差异对话框始终保护"的口径不完全一致：导入确认与云端凭据输入对话框未获得强制保护或保护接线无效。
- **影响边界**：需按实际窗口行为在设备上确认；不影响已正确接线的其它高敏感对话框。
- **尚未完成**：复核两处窗口行为；修正 `SecureDialogWindowEffect` 的挂载位置或改传 `DialogProperties(securePolicy=...)`；为云端凭据输入对话框加强制保护；补设备侧断言（对话框窗口带 `FLAG_SECURE`、遮挡触摸过滤生效）。

## P3 低危问题与体验改进

### ENG-20261028-34 `ObscuredTouchPolicy` 为全仓零调用的死代码

- **核实时间与方式**：2026-10-28，本轮全仓检索调用方（生产零命中，仅单测引用）。
- **位置**：`app/src/main/java/com/keepasskey/app/security/ObscuredTouchPolicy.kt:14-29`；生产统一使用 `SecureTouchCompose.kt` 的 `ApplyObscuredTouchFilter()`（原生 `view.filterTouchesWhenObscured`）。
- **尚未完成**：删除该类及其单测（避免测试维护不存在的机制）；确认原生过滤路径覆盖所有敏感界面。

### ENG-20261028-35 附件池引用累计上限判定恒真，分支不可达

- **核实时间与方式**：2026-10-28，本轮逐行复核计数与上限构造（类注释亦承认该式恒真）。
- **位置**：`database/src/main/java/com/keepasskey/database/xml/KdbxAttachmentBudget.kt:123-130,224-226`。
- **尚未完成**：删除恒真判断与对应记账（保留单条目引用次数与物化字节上限）；确认删除后放大防护仍由单条目上限承担。

### ENG-20261028-36 `SecureFileWrite` 的 POSIX 权限收敛在私有目录内收益极低

- **核实时间与方式**：2026-10-28，本轮逐行复核；对照 Keepass2Android WebDAV 侧无同类收敛。
- **位置**：`core/src/main/java/com/keepasskey/core/security/SecureFileWrite.kt:56-67,100-112`（`restrictToOwnerOnly`），调用方例 `app/src/main/java/com/keepasskey/app/security/KeyFileVaultCopyStore.kt`。
- **影响边界**：私有目录已由 UID/SELinux 隔离；写入的密钥文件副本本身已为 Keystore 信封 AES-GCM 密文。`uniqueTmpFor` 与原子改名才是真实价值所在。
- **尚未完成**：评估删除权限收敛（保留唯一临时名与原子替换），或保留但如实说明其收益有限、不依赖 umask 的适用前提。

### ENG-20261028-37 `UnlockThrottle` 应如实定位为体验设置

- **核实时间与方式**：2026-10-28，本轮逐行复核；与两家同业对照（均无客户端解锁节流）。
- **位置**：`app/src/main/java/com/keepasskey/app/security/UnlockThrottle.kt:271-281` 等。
- **影响边界**：出厂默认关闭、状态为明文 SharedPreferences、判定用 `System.currentTimeMillis()`；改系统时钟或清除应用数据即可重置。同业没有该机制；离线爆破不经此路径。与 `产品裁决登记.md:50`"不增加本地节流"的表述需对齐口径。
- **尚未完成**：确认其为"防界面误触"的体验控制并在文档与界面文案中如实表述（不宣称安全边界）；若保留，考虑用单调时钟校验进程内时长的基本绕过。

### ENG-20261028-38 防回滚守卫与崩溃检疫状态机强耦合

- **核实时间与方式**：2026-10-28，**子代理定位**，本轮仅复核其状态可重置与有限窗口，未复核 `RecoveryRequired` 死锁路径。
- **位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncRollbackGuard.kt`（含 `beginAcceptance`/`hasPendingAcceptance`/`RecoveryRequired` 等）。
- **影响边界**：摘要滑窗对"云端重放旧版本"有真实但有限的价值（窗口有限、状态缺失即放行、具备应用私有文件写能力者可重置，均已在 `已知工程限界.md:38` 登记）；问题是它与两阶段提交/检疫状态耦合，单次状态落盘异常可能阻塞整个同步。
- **尚未完成**：复核耦合路径是否会导致同步不可用；把"已见摘要查询"与提交协调分离，使守卫本身不持有恢复状态机；补状态文件损坏/缺失/半写下同步仍可继续或给出可操作提示的用例。

### ENG-20261028-39 公共后缀表为静态内置且无更新机制

- **核实时间与方式**：2026-10-28，本轮确认资源位置与无网络/无更新路径。
- **位置**：`app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt`；资源 `app/src/main/resources/publicsuffix/public_suffix_list.dat`。
- **影响边界**：新出现的多标签公共后缀（如平台型 `*.pages.dev`）在未更新客户端上会回退为默认规则，把平台根域当作 eTLD+1，从而错误放行跨租户子域。
- **尚未完成**：确认随版本更新资源的机制（或按平台/库替代）；把"表可能过期及其后果"如实登记为边界。

### ENG-20261028-40 域与后缀判定的一致性问题（IPv4 未排除、`localhost` 被拒、缺 `Locale.ROOT`）

- **核实时间与方式**：2026-10-28，本轮复核 `isRegistrableDomain` 与 `registrableDomain` 的口径差异、`localhost` 断言与大小写调用。
- **位置**：`app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt:92-99`（未排除 IPv4；注释自陈多标签 IP 放行为历史行为）与 `:110-130`（`registrableDomain` 排除了 IPv4/IPv6）；`app/src/main/java/com/keepasskey/app/passkey/DomainMatcher.kt:90-101,20`；`PublicSuffixList.kt:338`；测试 `PublicSuffixListTest.kt:67,73-74`。
- **影响边界**：IPv4 被当作分层域名可与后缀匹配（实际可利用面窄，需凭据 rpId 为多标签数字形态）；`localhost` 被拒使本地开发/自托管的 Passkey 不可用（测试已固化该行为，与 W3C 允许 `localhost` 作 RP ID 冲突）；`lowercase()` 未指定区域在土耳其语环境会破坏合法域名比对。
- **尚未完成**：对齐两个函数的排除规则并在 IP 字面量上禁用后缀匹配；为 `localhost` 增加显式豁免并调整已固化的断言；所有域名/协议/包名归一化改为 `lowercase(Locale.ROOT)`；补对应正反用例。

### ENG-20261028-41 `SyncEndpointGuard` 云元数据判定的 16 字节分支偏移写错

- **核实时间与方式**：2026-10-28，本轮逐行复核该分支与同文件另一处正确实现（`:246-248` 用标记字节），并确认被 `isBlockedAddress → isBlockedIpv6` 的映射递归兜住。
- **位置**：`sync/src/main/java/com/keepasskey/sync/network/SyncEndpointGuard.kt:176-180`。
- **影响边界**：该分支恒返回 false（把 IPv4-mapped 的标记字节 10-11 当内嵌 IPv4 比 169/254），但 `isBlockedAddress` 对映射形态递归判定内嵌 IPv4，实际不构成绕过。属"错误的防御分支被另一层兜住"。
- **尚未完成**：修正偏移（标记须为 `0xFF,0xFF`，内嵌 IPv4 取字节 12-13）或直接删除该冗余分支，避免"防御性复核"注释掩盖错误；补映射形态元数据地址的判定用例。

### ENG-20261028-42 WebDAV 凭据以不可擦 Base64 字符串长期驻留，底层擦除被抵消

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavAuthHeader.kt:38`；`WebDavSyncProvider.kt:91`（`private val authHeader: String`）。
- **影响边界**：底层大量临时数组擦除在顶层不可变 String 面前无效，且该 Provider 无凭据擦除接口；属"擦除表演"与维护成本。
- **尚未完成**：复核成立性；删除无收益的底层擦除或改为可失效的凭据载体（与 S3 侧能力对齐）；确认日志/异常不打印该头。

### ENG-20261028-43 `PasswordFillActivity` 单方法内三次重读同一条目与多组前置断言

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`app/src/main/java/com/keepasskey/app/passkey/PasswordFillActivity.kt:288-336`。
- **影响边界**：纯内存操作为主的短区间内重复查找与重复断言，未增加安全保证，降低可读性与维护性（安全结论仍以入口一次校验为准）。
- **尚未完成**：复核后收敛为"入口取一次快照＋提交前一次校验"，保持等价的安全断言覆盖。

### ENG-20261028-44 `BiometricCredentialStorage` 构造期重复执行遗留数据清理

- **核实时间与方式**：2026-10-28，**子代理静态定位，本轮未逐行复核**。
- **位置**：`app/src/main/java/com/keepasskey/app/security/BiometricCredentialStorage.kt:33-36,70-81`。
- **影响边界**：每次实例化在主线程同步执行 `deleteSharedPreferences` 与 Keystore 别名删除，无"已完成"标记，构成冷启动无谓开销（Keystore 调用为 Binder IPC）。
- **尚未完成**：复核成立性；改为一次性迁移标记或移入后台只执行一次。

## 附：本轮明确判定为"应保留"的防护（不构成未闭环项）

以下项经审查与同业对照后判定为**真实有效**，不应以"简化"为名削弱：KDBX HMAC 块流完整性（覆盖 `index‖size‖data`、终止块校验、常量时间比较、未通过不交付）；解析预算（外层头字段数/总长、内层字段与附件池上限、128 MiB 解压预算）；原子写盘的临时文件＋fsync＋原子改名；DAL 校验（强制 TLS、禁重定向、严格响应类型与有界读取、包名＋证书指纹＋relation 全量校验、默认 fail-closed）；SSRF 三层守卫中**连接期地址复核的连接职责**（IP 字面量确需独立复核，不能以 DNS 检查成功等同连接安全）；剪贴板摘要式条件清除与熄屏/锁定/冷启动三条清理路径（`EXTRA_IS_SENSITIVE` 已与 KeePassDX 对齐）；`allowBackup=false` ＋ 数据提取规则全域排除；`network_security_config` 禁明文与仅系统 CA（与 Keepass2Android 的明文开关对照后仍更严）；release 的 `minify/shrinkResources`；Rust 侧 `Zeroizing` 工作内存清零与 `sha2` `zeroize`；多签名者证书匹配。