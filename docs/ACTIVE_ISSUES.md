# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`；本文件**不保留**已闭环条目的正文或索引段，历史实现与验收证据一律以归档库为单一真相源。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：曾发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md` 等文件「需要删除」，但这些文件早已先行删除，`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P1 高危与核心功能问题（0 项）

> **历史**：**ISSUE-P1-16 ~ P1-21**（附件 Ref/Compressed 解析、块 HMAC 异常分型、外层头部总量闸门、
> 附件缓存冷启动清理、防回滚状态目录、敏感对话框 FLAG_SECURE）已于 2026-09-12 整改归档，见 §38。
>
> **2026-09-13 新增（红队攻击路径批次）**：下列条目由红队批次对拍后**留存为开放项**转登。
> 该批次文档已于同日处置归档并**删除**，来源证据、已撤回项与纪律见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
>
> **2026-09-13 P1 双项整改批次闭环**：`ISSUE-P1-24`（确认页归属展示 + 首次绑定授权 + 口令
> 无 UI 下发禁止）与 `ISSUE-P1-25`（`copyUsername` 口令面引用敏感通道 + 擦除调度保全）同批
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §45。
>
> **2026-09-13 P1 双项整改批次闭环（第二批）**：`ISSUE-P1-22`（软件级 Keystore 快速解锁
> 显式降级确认 + 常驻声明；附带发现并整改 `SecretKeyFactory` 探针误用致快速解锁必然失败的
> 隐藏缺陷）与 `ISSUE-P1-23`（官方签名指纹对外公布 + `installer==null` 决策留痕）同批
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §46。**P1 节暂无开放项。**

---

## P2 中危缺陷与协议/测试缺口（27 项）

> **历史**：**ISSUE-P2-28 ~ P2-41**（往返丢字段与布尔/数值语义、MemoryProtection 读写语义、
> KDF 缺参 fail-closed、isPackageMatch 的 android:// 硬约束、requireRiskNotice 接线、
> XML 元素计数上限、明文副本清零等 14 项）已于 2026-09-12 整改归档，见 §38。
>
> **2026-09-13 新增（红队攻击路径批次）**：P2-43 ~ P2-47 五项由红队批次对拍后留存为开放项转登，
> 来源与已撤回项见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
>
> **2026-09-14 闭环**：**ISSUE-P2-48**（附件引用累计预算）、**ISSUE-P2-51**（剪贴板清理接线 + 空读误清修复）、
> **ISSUE-P2-53 + P2-63**（CM 通道完整性门控对称化 + 生物快速解锁实时 hook 信号，同批）、
> **ISSUE-P2-61**（TOTP 种子写入恒非保护）、**ISSUE-P2-65**（明文持有者注册锁观察者）、
> **ISSUE-P2-72**（`androidPackageName` 归属修正 + 禁用 `android://android` 回退）与 **ISSUE-P2-76**
> （CM 通道验证升级为密码学绑定）随存量安全整改批次归档，见 §48；**ISSUE-P2-49** 的 AC①③ 同批完成、
> **AC② 未闭环仍保留**（见该行 2026-09-14 进展批注）。
>
> **2026-09-15 闭环**：**ISSUE-P2-62**（密钥文件纯字节解析）、**ISSUE-P2-50**（DAL 响应有界流式读取 +
> 字节数裁决）与 **ISSUE-P2-52**（选择器会话锁定对齐 + 空读 fail-safe）随存量安全整改批次（续）
> 归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §49。
>
> **2026-09-13 第四轮独立复核定版（《SECURITY_RECHECK_2026-09.md》1272 行定版稿）**：
> ① **升格 P1 排期**（条目编号留原地、闭环按编号归档）：`P2-48 / P2-49 / P2-51 / P2-53 / P2-61 / P2-63 /
> P2-65 / P2-72 / P2-76 / P2-77`，及 P3 节的 `P3-109 / P3-116 / P3-117 / P3-120` 与 P1 节 `P1-22` 批注——
> 其中 **`P2-53` 与 `P2-63` 互为前提，必须同批**（若 `P2-53` 收口点取非 suspend 的 `currentEnforcement()`，
> 修复会被 `P2-63` 完全抵消）——**二者已于 2026-09-14 同批闭环（§48）**，`P2-48 / P2-51 / P2-61 / P2-65 / P2-72 / P2-76 / P2-77` 同批；
> 余下 `P2-49`（AC② 未闭环）与四项 P3 升格项（`P3-109` / `P3-117` 已于 2026-09-14 闭环）仍在表内；② **升格 P0**：`P2-75` → `ISSUE-P0-09`（**已整改闭环**，连同 `P2-75` 原行一并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §44）；③ 新增 `ISSUE-P2-80`（M-1/M-2
> 设备实测）与 `ISSUE-P2-81`；④ 各行内「第四轮批注」为定版结论与 AC 修正，**实施前必读**；
> ⑤ 摘要：六项原审计让步 + `P2-49` 定级论证，全过程见该报告 §15.2(a)–(r)。

### ISSUE-P2-42（新登记）：§38 批次的 3 项「JVM 不可闭环」行为未在设备侧验证

- **优先级**：P2（测试有效性缺口——宿主 JVM 全绿**不构成**这些行为的证据）
- **核实时间点与核实方式（2026-09-12）**：§38 收尾时逐项确认「无法在宿主 JVM 闭环」并写入
  `docs/RESOLVED_LOG.md` §38.3；随后复核 `docs/ACTIVE_ISSUES.md`（`Select-String` 检索 `设备侧|真机|冷启动|FLAG_SECURE`）
  **发现零命中** —— 即该 3 项当时只落在归档日志、**未进入待办入口**。本条即为补登，
  以避免重演既有失效模式：「结论只进归档文档 → 无人流转 → 长期不闭环」。
- **问题描述（3 项，均属"代码已接线、行为未在 Android 运行时验证"）**：
  1. **敏感对话框窗口的 `FLAG_SECURE` 是否真实生效**：`SecureDialog` / `SecureDialogWindowEffect` 已在 7 处
     对话框接线（主密码修改、子库凭据、子库挂载、修订差异、附件预览、创建库向导 ×2）；平台语义上
     `FLAG_SECURE` 是**窗口级**属性，结论依赖 `DialogLayout` 确实实现 `DialogWindowProvider` 这一实现细节。
  2. **附件缓存冷启动清理端到端**：`MainApplication.onCreate`（任何会话打开之前）调用 `FileBinaryStore.clear()`；
     需验证「大附件落盘 → force-stop → 冷启动 → 目录为空」这一真实时序。
  3. **`SecureDialog` 是否静默 fail-safe 空操作**：取不到 provider 时不设 flag 且不崩溃——需确认真机上确实取到了 provider。
- **复现配方（三项独立判定 PASS/FAIL）**：
  1. 打开任一敏感对话框 → 系统截图 / 录屏 / Recents 快照应为黑屏或不可截；关闭对话框后 Activity 窗口 flags
     不得被误清（`adb shell dumpsys window | Select-String FLAG_SECURE`，或前后比对）。
  2. 导入 >1 MiB 附件使其落盘 → `adb shell am force-stop <pkg>` → 冷启动（不解锁）→
     `adb shell run-as <pkg> ls cache/attachments` 应为空。
  3. 真机打开对话框并确认 flag 已置（同上 dumpsys）；若为空操作，应能观测到未命中 provider（补日志或插桩断言）。
- **验收标准**：① 3 项各有一次设备侧实测记录（模拟器或真机，注明 API / ABI）并写入 `RESOLVED_LOG` 新批次；
  ② 任一项 FAIL 即按缺陷整改（**不得只标注"待验证"**）；③ 建议照
  `app/src/androidTest/java/com/keepasskey/app/data/session/DatabaseSessionAndroidRuntimeTest.kt` 的既有模式
  为第 2 项补 instrumented 用例，使其进入可复跑的层。
- **为什么不能省**：`AGENTS.md` §6 已明示「涉及正则 / XML / 平台 API 的静态逻辑不能仅凭宿主单测判定在 Android 上可用」，
  且 §24（ISSUE-P1-12）与 §26（ISSUE-P0-04）两次「JVM 过、Android 挂」逃逸均出在此类断言上。

---

### ISSUE-P2-43（新登记）：`autofillCopyTotp` 默认开启 —— TOTP 动态码默认写入剪贴板

- **优先级**：P2（默认配置即开启的第二因素泄露通道）
- **核实时间点与核实方式（2026-09-13）**：读取 `ExtendedSettings.kt` L38（`autofillCopyTotp = true`）
  与 `SettingsUiState.kt` L134（同默认），并核对消费点 `AutofillConfirmActivity.handleTotpAfterConfirm`
  （L192-197：`AutofillTotpCopyPolicy.shouldCopy(...)` 通过即 `clipboardSecurityManager.copySensitiveText(...)`）。
- **问题描述**：每次自动填充确认后**默认**把该条目的 TOTP 动态码写入剪贴板（受 `EXTRA_IS_SENSITIVE`
  + 默认 30 秒定时擦除保护）。剪贴板擦除可被用户关闭（见 ISSUE-P3-84），且在擦除窗口内前台应用可读；
  与口令泄露组合即可在有效期内完成第二因素绕过。
  （对照：`autofillShowTotpNotification` 默认**已关闭**，故通知栏面无需整改。）
- **验收标准**：① `autofillCopyTotp` 默认改为 **false**（或改为"开启时明示动态码将进入剪贴板"
  并在敏感复制路径强制不可关闭的短擦除）；② 设置页文案如实说明该通道的安全代价；
  ③ 回归断言覆盖"默认值"与"关闭后不触达剪贴板"。

---

### ISSUE-P2-44（新登记）：无障碍服务信号未纳入运行完整性体系

- **优先级**：P2（无需 root、覆盖面广：安卓恶意软件主流手法）
- **核实时间点与核实方式（2026-09-13）**：读取 `IntegritySignals` / `RuntimeIntegrityPolicy.evaluate`
  （L29-41 / L125-159）——信号集**不含任何无障碍字段**；读取 `SecurePasswordField.kt`
  确认其仅用 `PasswordVisualTransformation` + `KeyboardType.Password`，**未**设置 Compose 的
  `Modifier.semantics { password() }`。
- **问题描述**：`AccessibilityManager.getEnabledAccessibilityServiceList()` 可枚举已开启的无障碍服务，
  但当前完整性体系与敏感通道策略**完全未考虑该信号**；非无障碍路径下的语义树暴露程度亦尚未实测。
- **验收标准**：① 把"已启用第三方无障碍服务"纳入 `IntegritySignals`，并在（至少）主密码输入页
  给出风险提示或降级策略；② 主密码 / 条目口令输入显式设置 `password` 语义并补回归；
  ③ **设备侧实测**记录语义树实际暴露面（宿主 JVM 不构成证据，见 `AGENTS.md` §6）。

---

### ISSUE-P2-45（新登记）：解锁失败节流默认关闭，且记录可被"删键复位"

- **优先级**：P2（配置即削弱 + 完整性校验存在复位旁路）
- **核实时间点与核实方式（2026-09-13）**：读取 `RealSettingsRepository.kt` L113
  （`unlockThrottleEnabled = prefs[KEY_UNLOCK_THROTTLE_ENABLED] ?: false`，出厂默认关闭）
  与 `UnlockThrottle.kt` 的 `SharedPrefsUnlockThrottleStore.read()`（L80-82：
  `count == 0 && lockUntil == 0L && storedMac == null` 时直接返回"完整"记录）。
- **问题描述**：① 出厂默认不启用退避锁定，在线主密码爆破无应用层限速；
  ② 即便用户开启，删除 prefs 中三个键（计数 / 锁定截止 / MAC）即可命中"**全新安装**"分支、
  **被判定为记录完整**，从而复位计数 —— **不需要**重算 MAC
  （`UnlockThrottleIntegrity` 的 HMAC 并不能防该类删除）。
- **验收标准**：① 默认值改为**开启**（安全默认不放松），保留用户显式关闭的开关；
  ② 修复"删键复位"：区分"从未有过记录"与"记录被删除"（例如以 Keystore 保护的**存在标记**
  或首启写入的哨兵值判定），删除后应 fail-closed 而非视为完整；  ③ 两条各有回归断言
  （含"删除三键后仍受节流 / 锁定"的负向用例）。
- **第四轮定版批注（2026-09-13）**：终评 **LOW（DESIGN WEAKNESS）**。① **撤销**——默认关闭系
  2026-09-12 用户裁决（`UnlockThrottle.kt:190` / `SettingsRepository.kt:51-53` 均有留痕），非缺陷；
  ② **维持但须重新设计**——复核证实原"存在标记哨兵"方案**原理上不可闭环**（哨兵本身可被同路径删除），
  须改用 Keystore 绑定的存在性证明或登记为已接受边界并留痕。

---

### ISSUE-P2-46（新登记）：`android://` 包名绑定条目缺调用方签名指纹绑定

- **优先级**：P2（自动填充包名维度的残余越权面）
- **核实时间点与核实方式（2026-09-13）**：读取 `DomainMatcher.isAndroidPackageMatch`（L194-199）——
  仅做"条目 `android://` 绑定包名 == 调用包名"的**精确字符串相等**，**无签名指纹校验**；
  对照浏览器维度的 `BrowserSigningFingerprints.isTrusted`（包名 + 已取证 SHA-256 二元组）。
- **问题描述**：当条目以 `android://<包名>` 绑定、而该真实应用**未安装**时，任意应用只需以同
  `applicationId` 侧载即可命中并取得候选。
  （原红队报告所述"Web 域冒充包名"的命名空间混同路径，已由 `ISSUE-P2-40` 闭环，**不在本项范围内**。）
- **验收标准**：① 对 `android://` 维度引入**首次绑定 + 调用方签名指纹**校验（与浏览器维度同语义），
  或对"绑定应用当前未安装"的情形降级为需人工二次确认的弱候选；
  ② 回归断言覆盖"同包名不同签名 → 不命中"与"未安装包名 → 弱候选 / 不命中"。

---

### ISSUE-P2-47（新登记）：同步与封印凭据的回滚防护不足

- **优先级**：P2（数据新鲜性；需后端写权限或同 UID 能力）
- **核实时间点与核实方式（2026-09-13）**：确认 `sync/src/main/.../SyncRollbackGuard.kt` +
  `SyncIntegrityMac.kt` 存在且由回滚路径消费（`SyncEngine` / `SyncCache` / `KeystoreSyncIntegrityMac`）；
  读取 `KeystoreManager.kt` L206-218 注释确认其完整性 MAC 密钥**无用户认证门控**（"锁屏态亦可用"）。
- **问题描述**：① **同步回滚** —— 服务端（或 MITM）提供一个**合法的旧 `.kdbx`** 时，
  防护取决于本地"已见版本"记录是否单调且不可回滚；因 MAC 密钥无认证门控，同 UID 可重算，回滚可能不被拦住；
  ② **封印凭据回滚** —— 封印载荷未绑定"库 header 摘要 + 封印序号"，攻击者可把快速解锁
  回滚到旧主密码版本（价值有限，除非用户复用旧口令）。
- **验收标准**：① 把"已见库版本"绑定到**远端不可回滚信道**（如 S3 Object Versioning / ETag 单调性），
  或对远端 header 摘要做本地单调记录，并对回退**强制走冲突裁决 UI 而非静默覆盖**；
  ② 封印载荷内绑定"库 header 摘要 + 封印序号"，解封时与当前库比对，不匹配即失效并要求主密码解锁；
  ③ 两条各有回归用例（含"远端回退 → 拒绝自动合并"）。
- **第四轮定版批注（2026-09-13）**：复核证实 `SyncRollbackGuard.State.sequence` **已持久化但不参与裁决**
  （写点 `:142`/`:180`；裁决点 `inspect :118-127` 只比对 `current`/`recent`；类 KDoc `:76-80` 自认）——
  **勿据"sequence 已持久化"推断防回滚强度**（"看似已修"陷阱）。防护现状为"Keystore-HMAC 已见摘要链 +
  状态缺失 / MAC 失效 **fail-open**（`:145-158`，已留痕取舍）"。另 **AC① 修正**：不得依赖
  "S3 Versioning / ETag 单调性"作为客户端可信信道（不可实施），须本地单调记录。

---

> **2026-09-13 新增（外部安全审计批次）**：`docs/SECURITY_AUDIT_2026-09.md` 主报告已于同日本批次
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §40）。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` **双向对拍后仍成立**的条目转登，逐项附核实结果；详细修复方案、误报排除论证、
> 待复核区、无法确认项与 CVSS↔CWE 对照见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §43
> （承载该轮审计结论的 `SECURITY_AUDIT_REMEDIATION.md` 亦已于 2026-09-13 退役，其附录 A–F 全部留存于该节）。
> **重合声明**：审计 `F-01`（解锁节流默认关闭）已由本轮红队批次的 `ISSUE-P2-45` 覆盖，**不重复登记**。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-49 | 审计 F-12 | **（升格 MEDIUM·P1，第四轮定版）** KDF 逐项封顶为：内存 ≤4 GiB **且 ≤50% 动态堆**（`KdbxKdfParameterCodec.kt:171-174`；无 `largeHeap`，真机实际 M 上界 ≈128–256 MiB）、迭代 ≤2²⁴（**仅静态上界**）、并行度 ≤64、AES-KDF 轮数 ≤2²⁸；**无 `I×M` 联合预算**；派生（`KdbxFile.kt:135`）先于 Header HMAC（`:147`）⇒ **无需口令可达**（同步路径 `SyncDatabaseCodec.kt:55`）。墙钟量级待 `ISSUE-P2-80` 实测，**不得以推算替代** | ① codec 加 Argon2 `I×M` 与 AES-KDF `R` 联合预算（成本约一行，**可与 P0 批次同批实施**，但不改定级）；② 解锁派生加超时 / 取消；③ 不误拒合法库（附**官方参数域对照**——仓内现有 `EQUIVALENCE_MATRIX` 是"原生≡BC"交叉等价，非官方域对照）。**【2026-09-14 进展，见 `RESOLVED_LOG.md` §48.2】AC① 与 AC③ 已完成**（`validateArgon2Bounds` 加 `I×M` 联合预算 2^40 + 官方参数域对照 + 2 例单测，全量 1609 例全绿）；**AC② 未闭环**——阻塞式原生派生不可被协程 `withTimeout` 打断（等于无效纸面加固），且墙钟量级须 `ISSUE-P2-80` 真机实测，本轮**不引入**无效超时，**本条仍开放** |
| ISSUE-P2-53 | 审计 F-24 | CM 主通道（`passkey/`）对 `RuntimeIntegrityGate` **零命中**：完整性裁决在自动填充 fail-closed，却在 CM 通道 fail-open（`RuntimeIntegrityPolicy.kt:47` 自述含"禁止下发数据集"） | ① 在 `onBeginGet/CreateCredentialRequest` 或 `CredentialResponseAssembler` 统一收口；② 用例断言风险态下两条通道均拒绝下发；③ 不影响 CM 响应预算 |
| ISSUE-P2-54 | 审计 F-05 | 依赖 CVSS 闸门仅 `workflow_dispatch` 触发，PR / push 路径不含依赖扫描（`.github/workflows/dependency-scan.yml:21-22`） | ① `on:` 补 `pull_request` 与 `push{branches:[main]}`（或拆"PR 快速任务 + main 全量"以控耗时）；② 设为分支保护必需检查；③ 验证缺失报告时 fail-closed |
| ISSUE-P2-55 | 审计 F-06 | 发布签名口令等于仓库公开的示例值（`keystore.properties.example:6,8`） | ① 高熵口令对既有 PKCS#12 **只 re-key、不换密钥**（换密钥将使已安装用户无法覆盖升级）；② 示例改为不可误用占位符；③ `app/build.gradle.kts` 加构建期断言拒绝示例 / 弱口令 |
| ISSUE-P2-56 | 审计 RUST-01 | Argon2 工作内存（m_cost 秘密派生状态）释放前不擦除：`lib.rs:108-111` 仍用 `hash_password_into`，而 `Cargo.toml:30` 宣称已擦除 | ① **先实证** `Cargo.toml:30-31` 已启用的 argon2 `zeroize` feature 是否覆盖 m_cost 工作内存（读 argon2 0.6.0 源码——第四轮复核发现原论证中"initial_hash 已擦"在仓内无对应缓冲、不可靠），再择一：改 `hash_password_into_with_memory` + 自持 `Zeroizing<Vec<Block>>`（须保留 `Error::OutOfMemory` 优雅失败，否则超大 m_cost 会 abort、比现状更坏）**或**更正宣称；② 二者必择其一，不得都不做 |
| ISSUE-P2-57 | 审计 RUST-02 | 派生密钥栈副本残留：`sha2` 未启用 `zeroize`（`Cargo.toml:41`）；`Some(*out)`（`lib.rs:111`）产生普通栈副本 | ① `sha2` 加 `zeroize` feature；② 改 `derive_into(&mut Zeroizing<[u8;32]>)` 消除普通副本；③ 仅增加清零 `Drop`，无算法变更 |
| ISSUE-P2-58 | 审计 RUST-03 | 口令强度评估 **两条平方级路径**（`strength.rs:491-499` `unique_char_count` 线性扫描嵌套循环、`:406-424` `longest_keyboard_walk`）；`MAX_TEXT_CHARS = 8 shl 20` 是 XML 节点封顶、**对该热路径不构成约束**；`HealthCheckEngine` 对**全库每条口令**循环评估（恶意库 DoS 面乘性放大）；两个生产调用方运行在**主线程**（`SettingsHealthController.kt:73-77`、`EntryDetailRevealController.kt:137-139`，均 viewModelScope 裸 launch） | ① `estimate` 入口加长度上限 **+ 线性惩罚**（陷阱 #7：原"超出只按长度评分"会把 `'1234…'×N` 长数字串误判为极强）；② `longest_keyboard_walk` 单趟化（保持"同排且列差绝对值为 1"语义）；③ `unique_char_count` 改 O(n)；④ 调用方移出主线程；⑤ 保留既有 `keyboard_walk_does_not_bridge_rows` 负例 |
| ISSUE-P2-59 | 审计 RUST-05 | 原生 Argon2 路径缺内存上界预检（`Argon2KdfEngine.kt:52-64` 直接返回；`isMemoryParamFeasible` 仅覆盖 `transformJvm:73`），且 `(memoryInBytes / 1024).toInt()` 存在静默窄化 | ① `derive` 内镜像 Kotlin 上界（内存 / 迭代 / 并行度）；② `.toInt()` 越界抛异常而非截断；③ 上界须 ≥ 一切合法用户配置 |
| ISSUE-P2-60 | 审计 RUST-06 | KDF secret `K` 以普通 `ByteArray` 常驻头部且全仓无清零点（`crypto/src/main/java/com/keepasskey/crypto/kdf/KdfParameters.kt:35`；`equals/hashCode` 刻意忽略该字段） | ① 新增 `clearSensitive()` 擦除 `secretKey`；② 接入会话锁定 / 关闭路径；③ 仅在最后一次需要 `K` 的派生之后调用（保存时会以同一头部重新派生）——**时机不可静态确定，须显式生命周期契约**（清早了保存会**静默写出用错误密钥加密的库**）；另 `KdbxHeader.copy()` 为 data class 浅拷贝，`masterSeed` / `encryptionIv` 等 ByteArray **共享引用**，任何"就地清零头部"方案须先解决所有权（深拷贝或显式约定） |

> **2026-09-13 新增（敏感数据流审计批次）**：`docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` 已于同日
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §41）。该文档为**未跟踪文件**，
> 原文**不可**经 `git show` 取回，故其结论已按 §41 完成分流。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` **双向对拍后仍成立**的条目转登，逐项附核实结果。
>
> **重合声明（不重复登记）**：`H-new-1`（CM 主通道不受完整性门控）→ 已由 `ISSUE-P2-53` 覆盖；
> `S1`（发布签名口令 = 示例值）→ 已由 `ISSUE-P2-55` 覆盖；`E3`（自动填充后 TOTP 默认进剪贴板）→
> 已由 `ISSUE-P2-43` 覆盖；`M9`（剪贴板不随锁定 / 熄屏清理）→ 已由 `ISSUE-P2-51` + `ISSUE-P3-84` 覆盖；
> `M5`（明文导出缓冲未清零）→ 已由 `ISSUE-P3-86` 覆盖；`M2`（主密码在 Compose 中为不可擦 `String`）→
> 其**输入法通道**已由 `ISSUE-P3-76` 覆盖，其余属已接受残余风险（见 `AGENTS.md` §6）。
>
> **已撤回 / 非缺陷（不转登）**：`H-new-3`（`requireRiskNotice` 无消费者）经复核实为**误报**——
> 基线与 HEAD 均已在设置页渲染 `IntegrityRiskCard`（`SecuritySettingsScreen.kt`）；`H1 附注`
> （`VaultEntryMapperTotpTest` "虚假安全感"）系误读测试意图（该用例测**读取**路径兼容性）；
> `M8`（`flagSecureEnabled=false` 解除截屏保护）为产品裁决的有意设计（`RESOLVED_LOG` §29.3 / §38）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-64 | 审计 M1 | 库级 `MemoryProtectionConfig` **不影响内存密封**：`ProtectedString.isProtected` 仅来自 XML 属性（`KdbxXmlStringNode.isProtectedAttribute`）与写入侧硬编码（非口令字段恒 `false`）；库级配置只决定**写出侧** `Protected` 标志（`KdbxXmlEntrySerializer.resolveProtectedFlag:221-235`，实现官方语义）→ `Title`/`UserName`/`URL`/`Notes`/`otp` 在内存中是**未密封明文** | ① 明确「内存密封」与「写出标志」两条独立口径并在 KDoc 声明；② 若采纳「库级开启即内存密封」，须同步写入侧构造；③ 防回归：打开 `ProtectUserName=True` 的库并保存后**不得**被降级为未保护 |
| ISSUE-P2-66 | 审计 M4 | 落盘清理为 **unlink-only**（`SyncCache.kt` 用 `File.delete` / `deleteRecursively`）；已 unlink 扇区在 TRIM 前可恢复。~~`clearAll()` 不清 `<name>.<uuid>.tmp`~~ **（第四轮证伪**：`clearAll()` `:320-329` 遍历 `cacheDir` 全部直接子项，仅跳过 `.rollback` 状态文件，`.tmp` 必被一并删除——该子断言为误报，防回滚状态生产布局在 `filesDir/rollback` 不受影响**）** | ① ~~`clearAll()` 补 `deleteOrphanTmpFiles`~~ **撤销**（冗余无害，不实施）；② 登记「仅 unlink」为已接受边界（对齐 `AGENTS.md` §6）并留痕；③ ~~断言 `clearAll()` 后无 `.tmp` 残留~~ **撤销**（被证伪断言的派生） |
| ISSUE-P2-67 | 审计 L9 | 同步下载路径**绕过附件落盘**：`SyncDatabaseCodec.parseKdbxBytes` 调 `KdbxFile.load(...)` **未传** `binaryStore`（`SyncDatabaseCodec.kt:55`；默认 `null`）→ 远程库全部附件无论多大都内联进堆，随后仅置空引用，从不零化（**第四轮批注**：锁定清理已由 `SyncCoordinator.onSessionLocked` + `SyncCacheEvictor` 覆盖，残余面 = binaryStore 未传 + 同步解析产物不调 `clearSensitiveData()`——后者见 `ISSUE-P3-119` 批注） | ① 传入与主会话一致的 `BinaryStore`；② 断言「>1 MiB 远程附件解析后落盘而非内联」；③ 与 `ISSUE-P3-119`（NEW-B01-4）同批（清理语义须一致） |
| ISSUE-P2-68 | 审计 M6 | `data class` 默认 `toString()` 会打印明文：`EntryDetailUiState`（`:14,34,36,40`，含 `revealedPassword` / `revealedRevisionPasswords` / `revealedProtectedFields`）、`UiVaultEntry`（`UiModels.kt:102,105,110,135`，含 `username` / `totpCode` / `cardCvv`）、`AutofillPickerViewModel.Credentials`（`:79`，含明文口令）、`ParsedAutofillNode`（`AutofillStructureScan.kt:24`）。**当前零字符串化调用点**（核实于 HEAD）→ 属「一行 `log("$state")` 即漏」的潜在缺陷 | ① 逐个覆写 `toString()`（对齐 `ProtectedString` / `KdbxEntry` / `KdbxAttachment` 既有做法）；② 或加静态检查禁止对上述类型整对象插值；③ 不改语义 |
| ISSUE-P2-69 | 审计 M7 | `LogHygieneTest.kt:88` 的日志抽样正则 `\b(Log\|AppLog)\.[edviw]\(` **匹配不到** `debugLog.warn(` / `debugLogBuffer.error(` 通道 → 该通道不在「敏感标识 / 裸异常 message」脱敏测试覆盖内 | ① 正则扩到 `debugLog` / `debugLogBuffer`（或统一按「日志调用」特征抽取）；② 顺带复核该通道现存 `${e.message}` / endpoint URL / 子库别名插值点；③ 断言扩展后既有违规为 0 |
| ISSUE-P2-70 | 审计 E1 | 手动选择器把**任意**条目凭据交给请求方，且**不显示请求方身份**（`AutofillPickerActivity` / `AutofillPickerViewModel.kt:54-77`）；自动匹配路径有严格边界，手动兜底路径无提示。（与 `ISSUE-P1-24`（**已闭环归档**，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §45）**部分重合**：P1-24 验收针对**确认页**，本项针对**选择器页**） | ① 选择器页强制展示请求方应用名 + 签名指纹摘要 + 域；② 断言覆盖展示内容；③ 与 `ISSUE-P3-93`（多签名者遍历）同批 |
| ISSUE-P2-71 | 审计 E2 | IME 内联建议把候选显示串（用户名 / 条目标题）送入输入法，且 `inlineSuggestionsEnabled` **默认 true**（`ExtendedSettings.kt:36`；`AutofillDatasetBuilders.kt:171-175 buildInlinePresentation(...)`） | ① 默认改 false，或把内容改为不含用户名 / 标题的固定文案；② 设置页如实说明该通道把候选名送入 IME；③ 断言覆盖默认值与关闭后行为 |
| ISSUE-P2-73 | 审计 E5 | 自动填充认证流两处协议漂移：① `AutofillUnlockActivity.kt:65` / `AutofillConfirmActivity.kt:160` 用裸 `setResult(RESULT_OK)`，**不带** `AutofillManager.EXTRA_AUTHENTICATION_RESULT`（官方要求经该 extra 回传数据集）；② `AutofillDatasetBuilders.kt:69,206` 创建认证 `PendingIntent` 用 `FLAG_IMMUTABLE`，而平台需向其中填认证参数（picker 路径 `:240` 用 `FLAG_MUTABLE` 是对的） | ① 按官方以 `EXTRA_AUTHENTICATION_RESULT` 回传；② 认证 `PendingIntent` 改 `FLAG_MUTABLE`（并保持 base intent 显式 + `Intent.fillIn` 覆盖语义）；③ 设备侧实测认证填充链路 |
| ISSUE-P2-74 | 审计 E6 | 清单**未声明** `<queries>` / `QUERY_ALL_PACKAGES`（核实：0 命中），而 `AutofillOriginResolver.kt:65-68` 用 `getPackageInfo(..., GET_SIGNING_CERTIFICATES)` 解析任意调用包 → Android 11+ 包可见性下抛异常 → 证书指纹为 null → **所有 webDomain 候选被丢弃**（方向 fail-closed，无泄露）（**第四轮根因更正**："指纹读取先于浏览器判定"不是因——白名单本就要求非空指纹（`BrowserSigningFingerprints.kt:53`）；真因即缺 `<queries>`，且后果**强于原记录**：指纹为 null 同时使浏览器白名单**与** DAL 验证（`:41-42`）**双双恒 false**，web 域路径整体失效） | ① **先在设备侧复现**（判定是否真实失效）；② 若失效，改用平台下发的 `CallingAppInfo` 或**声明最小 `<queries>`**（与 `ISSUE-P3-94` 同文件同批，修复方向由"调整判定顺序"改为"补 `<queries>`"）；③ 断言浏览器域自动填充在真机可用 |

---

> **2026-09-13 新增（威胁建模报告退役批次）**：`docs/THREAT-MODEL-AUDIT-d32f3e7.md` 已于同日
> **退役删除**（处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §42）。下表为在该报告基线 `d32f3e7`
> 与当前 `a669a48` 对拍后**仍成立、且此前未登记**的开放项。
>
> **核实时间点与核实方式（2026-09-13，对 HEAD `a669a48`）**：逐条读取源码复核——
> `WebDavSyncProvider.kt` / `WebDavPropfindParser.kt` / `CredentialVerificationLauncher.kt` /
> `SessionOpener.kt` / `KeePasskeyCredentialProviderService.kt` / `PasswordSaveActivity.kt` /
> `VaultEntryWriteCoordinator.kt` / `KdbxHeader.kt` / `SyncProviderResolver.kt`（均为直读，非 grep 片段推断）。
>
> **已覆盖（不重复登记）**：`Q-1→ISSUE-P2-53`、`Q-4→§38 ISSUE-P2-36`、`Q-5→ISSUE-P2-62`、
> `Q-7→ISSUE-P1-25`、`Q-9→ISSUE-P3-107`、`Q-12→ISSUE-P2-69`、`Q-13`/`T-8→§38 ISSUE-P1-20`、
> `Q-17 前半→ISSUE-P3-100`、`T-4→ISSUE-P1-24`/`ISSUE-P2-70`、`T-11→ISSUE-P1-25`、
> `T-12→ISSUE-P3-103`、`T-13→ISSUE-P1-22`、`T-14→ISSUE-P3-92`、`T-15 部分→ISSUE-P2-60`、
> `Q-6→ISSUE-P3-82`、`T-3→ISSUE-P3-76`。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-78 | 威胁建模 T-10 | **CM 保存路径写入畸形 URL → 条目永久无法被域匹配**：`KeePasskeyCredentialProviderService.kt:249` 把 `callingOrigin` 作为 `EXTRA_WEB_DOMAIN` 下传（浏览器委派为 `https://…`，普通应用为 `android:apk-key-hash:…`，形态见 `CallingOriginResolver.kt:57-83`），`PasswordSaveActivity.kt:48,68-73` 原样透传，`VaultEntryWriteCoordinator.kt:274` **无条件**拼 `"https://$domain"` → 落库为 `https://https://host` 或 `https://android:apk-key-hash:…`；条目此后既不匹配 web 域也不匹配 `android://` 包名（无口令泄露，属完整性 / 可用性缺陷） | ① 按 origin 形态分流：web origin 直接入库、`apk-key-hash` origin 落 `android://<调用包名>`（与自动填充保存路径语义对齐）；② 断言两类 origin 落库 URL 合法且可被 `DomainMatcher` 命中；③ 负例：不得允许 `https://` 前缀重复叠加 |
| ISSUE-P2-79 | 威胁建模 Q-3 / 审计 A-1、A-2（**需产品确认**） | **KDF 强度基线未对齐**（唯一纯密码学边界的强度参数）：① 建库默认 `Argon2id m=64 MiB / t=2 / p=2`（`KdbxHeader.kt:162-171`），而仓库已具备设备自适应推荐 `KdfBenchmark`（`SettingsKdfBenchmarkController.kt:17-49`）——**仅设置页展示，建库路径零消费**（核实：`SessionOpener.create` 直接走 `KdbxHeader.createDefault`）；② 读取路径接受极弱参数（`m=1 MiB, t=1, p=1`；AES-KDF `R=1`）并在保存时**原样保留**（仅刷新盐） | ① 产品确认目标强度口径（如"设备实测约 1 秒"）；② 建库路径消费 `KdfBenchmark` 建议值（或提供"使用推荐参数"默认）；③ 对过低工作量的导入给出告警或升级选项；④ 参数变更不得破坏既有库可解锁性与官方客户端互操作 |

> **2026-09-13 新增（第四轮独立复核定版批次）**：以下 2 项为《SECURITY_RECHECK_2026-09.md》定版稿的
> 新增 / 拆分条目（来源其 §10.1 / §11，附四轮审核定版结论）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13） | 验收标准 |
|---|---|---|---|
| ISSUE-P2-80 | 复核 §10.1 `M-1` / `M-2` | **KDF 墙钟与闸门分路径实测缺口**：`ISSUE-P2-49` 的墙钟量级与 `M = 堆/2` 的实际可达性均为**未实测推算**（推演已四方三错，报告已删除一切墙钟断言）；且两处内存闸门**不在同一路径**——native 路径（真机默认）只有 codec「堆/2」一道闸（`KdbxKdfParameterCodec.kt:171-174`），`M = 堆/2` 直达 Rust 内核（`Argon2KdfEngine.kt:52-64` native 分支**无第二道闸**）；JVM 兜底路径才有 `transformJvm`（`:73`）的 `0.6×maxHeap` 二次拦截 | ① **M-1**：构造合法 Header（`I = 2²⁴`、`M = 堆/2`）在设备实测一次 KDF 耗时，记录 API / ABI / 机型 / 实测秒数并写入设备侧基线；② **M-2**：**分路径**实测 `M = 堆/2` 是否被接受、实际到达内核的 `M` 值（不得按"两处阈值哪处先触发"的旧题面测——会误判）；③ 实测值回填 `P2-49` 验收口径，不得再以推算替代 |
| ISSUE-P2-81 | 复核 `NEW-N5` | **会话授权宽限的匹配域过宽**：宽限开启后 `webDomain.orEmpty()`（`AutofillDatasetBuilders.kt:205`）把空域经 `AutofillConfirmActivity:151` 传入，`AutofillSessionGrantStore.normalized()`（`:17-24`）把 `""` 归一为 `null` → 30 秒 TTL（`DEFAULT_TTL_MILLIS = 30_000L`）内该应用**所有无域 / 域不可归属的表单**免二次确认（含攻击者伪造的不可归属域）。默认关闭（`ExtendedSettings.kt:44`）；grant 按 `(包名, 域)` 整体相等匹配，已验证真实域不命中 | ① grant 匹配对"域不可归属"情形收窄（要求域非空，或宽限仅对同域生效）；② 或在开启开关的设置页如实说明该代价；③ 断言「宽限期内伪造不可归属域 → 仍需确认」或留痕接受 |

---

## P3 低危问题、特性接线与体验优化（46 项）

> **状态（2026-09-12）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-68** 除 P3-23（经产品裁决「不排期」）外
> 已全部闭环并归档，逐条实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §32）。
> 2026-09-12 存量修复批次闭环 P3-63 / P3-65 / P3-67（§28.2 ~ §28.4）；
> **ISSUE-P3-68**（重试节流开关与自定义最长锁定时长）同日闭环归档（§29.2）；
> **外部安全审计整改批次 P3-69 ~ P3-72** 同日闭环归档（§30）；
> **文档类存量整改批次 P3-75 / P3-77** 同日闭环归档（§31）；
> **CSV 导入 / 导出扩充 P3-73** 同日闭环归档（§32）。
> **设备侧验证缺口 P2-27 收口**：§34（app 导入解析设备侧骨架）+ §36（app 域解析 / 解锁落盘、
> sync 权限基线）同日闭环；**工程整洁收口批次**（死文案 / 假开关 / `AGENTS` §6 更正）同日归档（§37）。
> **产品裁决「不排期」**：P3-58 / P3-66 / P3-74（见 §33）。
> **保留待办**：**ISSUE-P3-76**（框架阻塞的已接受残余风险，保留跟踪与解除条件）。
> **2026-09-13 新增（红队攻击路径批次）**：P3-82 ~ P3-85 四项由红队批次对拍后留存为开放项转登，
> 来源与已撤回项见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §39。
> **2026-09-14 闭环**：**ISSUE-P3-84**（剪贴板风险明示 + 切后台即清 + 自动填充引导）随
> 存量安全整改批次归档，见 §48。
> **2026-09-13 新增（威胁建模 / 安全整改报告退役批次）**：P3-116 ~ P3-124 九项由
> `THREAT-MODEL-AUDIT-d32f3e7.md` 与 `SECURITY_AUDIT_REMEDIATION.md` 的对拍结果转登
> （两份报告同日退役删除，处置归档见 §42 / §43）。
> **2026-09-13 第四轮独立复核定版**：`P3-109 / P3-116 / P3-117 / P3-120` **升格 P1 排期**（编号留原地）；
> `P3-86` 升格 MEDIUM / 修 P2；`P3-122` 的 4 项复核结论已裁（转残余整改）；新增 `ISSUE-P3-125` /
> `ISSUE-P3-126`（复核新发现 6 小项）；各行「第四轮批注」为定版结论，实施前必读。

---

### ISSUE-P3-76（新登记）：密码 / 主密码输入未禁用输入法个性化学习

- **优先级**：P3（低危隐私加固）
- **核实时间点与核实方式（2026-09-12）**：全仓 `app/src/main` 检索 `IME_FLAG_NO_PERSONALIZED_LEARNING`
  与 `InputMethodService` **零命中**；敏感输入现仅依赖 `KeyboardType.Password` 等常规配置。
- **问题描述**：第三方输入法可能对用户输入做个性化学习 / 候选记忆，主密码与条目口令存在被输入法
  词库记录的风险面。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：`Util.SetNoPersonalizedLearning`
  显式关闭输入法学习。
- **验收标准（待整改）**：① 主密码、条目口令、生成器口令预览等**全部敏感输入路径**显式禁用个性化学习
  （`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`，Compose 侧经 `PlatformImeOptions` 等机制下发）；
  ② 有回归断言覆盖各敏感输入组件；③ 非敏感输入（如搜索框）行为不变。
- **禁止**：仅在部分页面接线导致旁路；以自定义 `VisualTransformation` 冒充已关闭输入法学习
  （防的不是同一威胁）。
- **框架阻塞实测（2026-09-12，读取本机 Compose 源码核实）**：本仓 `androidx.compose.*` 为
  **1.11.4**（BOM 2026.08.00）。逐一核对 Compose 的 `EditorInfo` 构造链后确认**无任何公开 API 可下发
  该整型标志**：
  1. `foundation/androidMain/.../text/input/internal/EditorInfo.android.kt` 的 `EditorInfo.update(...)`
     仅由 `imeAction` 枚举构造 `this.imeOptions`，并附加 `IME_FLAG_FORCE_ASCII` / `IME_FLAG_NO_ENTER_ACTION` /
     `IME_FLAG_NO_FULLSCREEN`，**不设也无可传入 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的入口**；
  2. `ui-text/androidMain/.../input/PlatformImeOptions.android.kt` 的 `PlatformImeOptions` **仅**暴露
     `privateImeOptions: String?`（映射 `EditorInfo.privateImeOptions` 自由字符串，主流输入法**不解析**该
     字符串来禁用学习），**无 imeOptions 位域**；
  3. `KeyboardOptions.toImeOptions()`（`foundation/commonMain`）亦仅承载 `ImeAction` / `singleLine` 等，
     不含原始位域；
  4. 官方路线（`ui/androidMain/.../platform/PlatformTextInputMethodRequest.android.kt` 的
     `createInputConnection(outAttributes: EditorInfo)`）允许拦截 `EditorInfo`，但它属**平台文本输入会话**
     私有扩展点，需以 `PlatformTextInputSession.startInputMethod` 自行实现整个输入会话，**无法与
     M3 `OutlinedTextField` 组合**（等于重造文本输入控件）；
  5. 参考项目外证：开源项目 spela（PR #1114）对同类诉求的结论一致——「Compose 不在公开 Kotlin API 暴露
     这些 int 标志，只能下沉到 Android View 系统包裹真实 `EditText`」。
- **本次处置：登记为框架阻塞的已接受残余风险，不实施**（与 ISSUE-P3-58 同属「本地不可消除、保留跟踪」）：
  - **残余风险已部分缓解**：敏感输入经 [SecurePasswordField](../app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt)
    统一走 `KeyboardType.Password`（→ `TYPE_TEXT_VARIATION_PASSWORD`），主流输入法（Gboard / SwiftKey /
    Samsung / HeliBoard）对该 inputType **默认不做个性化学习**；显式标志只是更强一层的提示。
  - **不采用高风险代偿**：把安全关键的 `SecurePasswordField` 整体改写为 `AndroidView(EditText)`
    会牺牲 M3 外观 / 无障碍 / 现有 CharArray 桥接与擦除契约，风险与 P3 收益不成比例，**本次不做**。
  - **AC③ 语义**：因未接线，非敏感输入行为天然不变（未产生任何旁路）。
- **解除条件（可复现配方）**：若 Compose 后续版本在 `PlatformImeOptions` / `KeyboardOptions` 暴露
  `imeOptions` 位域（或提供 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的公开入口），则在 `SecurePasswordField`
  统一接线并补回归断言（覆盖主密码 / 条目口令 / TOTP / 同步凭据各调用点），届时即可闭环本条。

---

### ISSUE-P3-78（新登记）：Argon2 KDF 的 `S`（salt）长度未按官方上下界校验

- **优先级**：P3（同类 fail-closed 硬化）
- **核实时间点与核实方式（2026-09-12）**：本轮 F-09/KDF 整改代理在实现 P2-34 时报告并经本会话复核——
  `KdbxKdfParameterCodec.deserialize` 仅校验 `$UUID`/`S`存在性与 `P/M/I/V` 的范围，**未校验 `S` 长度**；
  官方 `参考项目/KeePass-2.61.1-Source/KeePassLib/Cryptography/KeyDerivation/Argon2Kdf.cs:57-58`
  为 `MinSalt = 8` / `MaxSalt = 0x3FFFFFFF`，`:143-144` 越界即抛 `ArgumentOutOfRangeException`。
- **问题描述**：我们会接受官方拒绝的盐长（如 0 字节盐的退化文件）；`S` 的最大长度受 VariantDictionary
  值上限（1 MiB）间接约束，故无内存风险，属**接受域不一致**而非可利用缺陷。
- **本次未实施原因（留痕）**：会新增拒绝面且当轮无法运行构建/测试验证回归，故登记待办而非擅自扩大范围。
- **验收标准**：① 按官方上下界校验 `S` 长度，越界抛 `KdbxCorruptFileException`；② 用例覆盖 `len=8` 通过、
  `len=7` 拒绝，并加一条「本仓自身写出的 32 字节盐必须通过」防误拒。

### ISSUE-P3-79（新登记）：Compose Popup 系窗口（`DropdownMenu` / `ExposedDropdownMenuBox`）未施加 FLAG_SECURE

- **优先级**：P3（同类窗口缺口，本轮已修对话框窗口）
- **核实时间点与核实方式（2026-09-12）**：ISSUE-P1-21 落地时由实现代理在 `SecureDialog` KDoc 中诚实登记——
  Compose 的 Popup 窗口由 `PopupLayout` 承载，**不实现** `DialogWindowProvider`，故 `SecureDialog`
  对其恒为 fail-safe 空操作；官方接线是
  `PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)`（`DropdownMenu` / `ExposedDropdownMenuBox`
  的 `properties` 参数）。
- **问题描述**：若某 Popup 内容出现敏感明文（如长按菜单显示口令），该窗口无 FLAG_SECURE。
- **验收标准**：① 盘点所有 Popup 系窗口的敏感内容面；② 对确有敏感内容的调用点接线 `securePolicy`
  并补回归断言；③ 无敏感内容的调用点留痕说明「无需接线」，避免"全量加 flag"的过度改动。

### ISSUE-P3-80（新登记）：`KdbxConstants.Xml` 缺 `Compressed` 属性常量（实现内局部常量）

- **优先级**：P3（常量归位 / 代码整洁）
- **核实时间点与核实方式（2026-09-12）**：ISSUE-P1-16 落地时由实现代理在报告中提出——`Compressed`
  属性名（官方 `KdbxFile.cs:194 AttrCompressed = "Compressed"`）当前以
  `KdbxXmlBinaryNode.ATTR_COMPRESSED` 私有常量承载，未上收至 `KdbxConstants.Xml`（其余 XML 节点/属性名
  均集中在该对象）。
- **验收标准**：① 在 `KdbxConstants.Xml` 增加 `COMPRESSED` 并替换实现内局部常量；② 无行为变更；
  ③ 若同期新增属性常量（如 `Ref` 已存在），保持命名风格一致。

---

### ISSUE-P3-82（新登记）：内层 XML DTD 拦截缺设备侧回归用例

- **优先级**：P3（测试有效性缺口：代码侧已 fail-closed，缺可复跑的设备侧证据）
- **核实时间点与核实方式（2026-09-13）**：全仓检索 `app/src/androidTest`、`database/src/androidTest`
  内的 `DOCTYPE` / DTD 用例 —— **零命中**。
- **背景（含已证否结论，避免重复排查）**：内层 XML 的 DTD / 外部实体拦截由
  `KdbxXmlParser.buildHardenedParser` 的**特性探针**（逐特性试建 parser，建不起来才剔除）
  + `parser.parse(inputStream, handler)` 把 `DefaultHandler2` **同时注册为 `EntityResolver`**
  （`resolveEntity` 必被调用并抛异常）双重保证。**原红队条目"两层可能同时失效 → XXE"经对拍已证否**
  （`FEATURE_DISALLOW_DOCTYPE_DECL` 是否受支持不影响外部实体的拒绝路径）。
  仍需补的是**设备侧证据**，而非新增防护。
- **验收标准**：① 在 `database/src/androidTest` 增加"注入 `<!DOCTYPE>` / `<!ENTITY>` 的合法口令 KDBX
  必抛 `KdbxCorruptFileException`"的 instrumented 用例；② 该用例在真机 / 模拟器上计入可复跑基线。

---

### ISSUE-P3-83（新登记）：`TracerPid` / 内存取证门控缺失

- **优先级**：P3（root 边界内的"提高成本"项，**非阻断承诺**）
- **核实时间点与核实方式（2026-09-13）**：全仓（`*.kt` / `*.rs`）检索 `TracerPid` —— **零命中**；
  `RuntimeIntegrityDetector` 仅检查 `Debug.isDebuggerConnected()` / `waitingForDebugger()`、
  `/proc/self/maps` 特征串与磁盘路径。
- **问题描述**：`ptrace` / `process_vm_readv` / `/proc/<pid>/mem` **不产生新映射**，
  现有探测无感。**本项属"同 UID / root 可截获"设计边界**（不承诺阻断），仅争取提高攻击成本。
- **验收标准**：① 关键解密路径前后**同步**读取 `/proc/self/status` 的 `TracerPid`，非 0 即
  `clearSensitiveCache()` 并拒绝解锁；② 评估 `prctl(PR_SET_DUMPABLE, 0)` / `MADV_DONTDUMP`
  的可行性与代价（需 native 支持，**不得**以牺牲稳定性换取纸面加固）；
  ③ 明确记录"本项仅为提高成本，不改变设计边界"。

---

### ISSUE-P3-85（新登记）：自动填充 / 组件面低危硬化（3 小项）

- **优先级**：P3（单条收益有限，合并登记）
- **核实时间点与核实方式（2026-09-13）**：
  1. `AutofillDatasetBuilders.buildPickerDataset`（L235-241）选择器 `PendingIntent` 为
     `FLAG_MUTABLE | FLAG_UPDATE_CURRENT`（框架注入 fillIn extras 所需，不可改 IMMUTABLE）；
  2. `AndroidManifest.xml` L24-33 的 `MainActivity` **未声明** `launchMode` / `taskAffinity`；
  3. `.github/` 内检索 `exported` —— **无任何断言** exported 组件必须受权限保护。
- **问题描述**：① 选择器 `PendingIntent` 可被重复拉起（DoS / UI 干扰）——
     **注入面已排除**：base intent 已显式设置全部安全关键 extra，且 `Intent.fillIn` 为
     "base 覆盖 fillIn"，攻击者无法改写既有键；
  ② `MainActivity` 可被任意应用反复拉起 / 清任务栈（**未发现直接泄密**，`onCreate` 不消费外部 extra）；
  ③ 未来依赖可能引入无权限保护的 `exported` 组件而 CI 不告警。
- **验收标准**：① `MainActivity` 加 `launchMode="singleTask"` + `taskAffinity=""`，
  并**保持 `onCreate` 不消费外部 extra**（当前正确，须作为回归约束）；
  ② CI 增加"合并清单断言：所有 `exported=true` 组件必须带 `permission` 或来自白名单"；
  ③ 对选择器 / 确认页入口评估一次性 nonce（可延后）。

---

> **2026-09-13 新增（外部安全审计批次）**：转登自已退役的 `docs/SECURITY_AUDIT_2026-09.md`
> （处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §41）。以下各项均为在 `d32f3e7` 与 `a669a48`
> 双向对拍后仍成立、且**不改变安全承诺**的低危 / 卫生项；该报告的逐条分流结论见 §41。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13） | 验收标准 |
|---|---|---|---|
| ISSUE-P3-86 | 审计 F-02 | **（升格 MEDIUM / 修 P2，第四轮唯一升格项）** 明文导出（XML / CSV）字节缓冲写盘后未清零：`VaultExportCoordinator` → `SettingsExportController.kt:169-184` 全链（导出器返回整库明文 `ByteArray`，写盘 `os.write(bytes)` 后无任何 `fill(0)`，第四轮全链核实） | `finally { bytes?.fill(0) }`；或改流式签名从源头消除整库明文物化 |
| ISSUE-P3-87 | 审计 F-03 | 明文导出二次确认仅在 UI 层（`ExportConfirmationPolicy.allows(..., confirmed = true)` 为恒真调用） | 确认令牌下沉到仓库层（构造器 `internal`）；直接调用仓库导出应编译失败或返回 `Failure` |
| ISSUE-P3-88 | 审计 F-04 | `BrowserSigningFingerprints.kt:35` 首条 Chrome 指纹为 **65** hex（SHA-256 恒 64）→ 该条目永不匹配；`AutofillWebDomainPolicyTest` 复用同一 65 字符值致**测试假绿**（**第四轮批注**：笔误存在**两份副本**——`BrowserSigningFingerprints.kt:35`（大写）+ `CallingOriginResolver.kt:42`（小写同值）；且仓内并存第三份**正确** 64 位值（`CallingOriginResolver.kt:41` 冒号版、`DigitalAssetLinksVerifierTest.kt:30`），仓库自我证明了笔误存在却零测试失败） | ① 按官方 `gpm-passkeys-privileged-apps/apps.json` **sourced** 核对后修正（禁止 retype，**两份副本同批**）；② 新增格式断言单测（全部指纹 `length == 64` 且为大写 hex）；③ 修正测试复用。方向 fail-closed，无机密性影响 |
| ISSUE-P3-89 | 审计 F-07 | `ExportAuditSanitizer.shortDigest` 每字节仅取低 4 位、只取前 4 字节，且 8 个 hex 字符中 **4 个恒为 `'0'`**（`value ushr 4` 对 4 bit 输入恒 0，`SettingsExportController.kt:298-307`）⇒ 有效熵 **≤16 位**（**第四轮更正**：原记 32 位系字对数算错，第一轮同样错） | 改用完整 64 hex，或如实改写注释与命名（无安全影响，属语义一致性） |
| ISSUE-P3-90 | 审计 F-08 | 公开死函数 `OtpEngine.parseOtpAuthUri` 对 `period` / `digits` 无钳制（`OtpEngine.kt:88-121`，零调用点；生产走 `TotpKeyUriParser` 已钳制） | 删除该函数，或改 `internal` 并补齐同等钳制（防未来误接入） |
| ISSUE-P3-91 | 审计 F-16 | `HmacBlockStream.readAll` 用短路 `contentEquals`（`:120,128`；生产流式路径已正确用 `MessageDigest.isEqual`） | 改用 `MessageDigest.isEqual`，或标注为测试专用（当前无生产调用者） |
| ISSUE-P3-92 | 审计 F-17 | UI 把 KDBX 的 ChaCha20 标注为「ChaCha20-Poly1305」（`DatabaseAlgorithmDialogs.kt:42`、`SettingsUiState.kt:69`、`SettingsPreferencesController.kt:36`），而该 AEAD 在本仓不存在 | 改为「ChaCha20 (RFC 8439，无 AEAD 标签)」，同步修正 `RESOLVED_LOG.md` 与相关单测 |
| ISSUE-P3-93 | 审计 F-19 | 调用方证书仅取 `apkContentsSigners.firstOrNull()`（`AutofillOriginResolver.kt:69`、`CallingOriginResolver.kt:77,92`），签名轮换期结果随顺序变化（方向 fail-closed） | 遍历全部签名者（含 `signingCertificateHistory`），任一匹配即通过。**注**：本项是 `ISSUE-P2-46`（`android://` 签名绑定）的前置条件 |
| ISSUE-P3-94 | 审计 F-20 | 合并清单冗余 / 废弃权限，且无 `tools:node="remove"`（源清单 `AndroidManifest.xml:5-6` 声明 `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC`，二者亦由库注入；`USE_FINGERPRINT` 仅来自 `androidx.biometric`） | 删除冗余声明；对废弃权限加 `tools:node="remove"`；保留 zxing 注入的 `CAMERA` |
| ISSUE-P3-95 | 审计 F-21 | 自动填充确认返回 `RESULT_OK` 前不校验会话是否已锁定（`AutofillConfirmActivity.kt:138-164`；CM 各路径均有该判定） | `RESULT_OK` 前加 `if (vaultRepository.isLocked()) { finish(); return }`，并在会话锁定时丢弃未决响应 |
| ISSUE-P3-96 | 审计 RUST-07 | Kotlin CBC **加密**流的明文中转副本未清零（`crypto/src/main/java/com/keepasskey/crypto/cipher/CbcStreams.kt:80` 的 `buffer.copyOf(filled)`、`:99` 的 `val chunk = buffer.copyOf(aligned)`）；解密流侧已修 | 两处均加 `finally { Arrays.fill(..., 0) }`，对齐该类 `:27` 的 KDoc 宣称 |
| ISSUE-P3-97 | 审计 RUST-09 | CI 无一 job 同时具备 Rust 工具链与**原生用例真实执行**（**第四轮表述更正**：fast-gate `./gradlew test` 实际运行 `:crypto:test`，但未装 cargo → JNI 用例经 `Assume` 整类跳过；native-gate 装 cargo 但只跑 `cargoNdkBuild` + assemble 不跑 test。**编译期**符号签名核对已存在——`jni_bridge.rs:152-170` 由 `cargo test` 覆盖；缺的是 readelf / nm 级 `.so` 导出核对） | `native-gate` 中 `cargoNdkBuild` 后追加 `:crypto:test` 与导出符号数断言（实测若实现将通过：恰好 5 个 `Java_com_keepasskey` 导出）；如保留 Assume 跳过机制，须在 CI 汇总显式输出"原生用例 N 例被跳过"防假绿 |

---

> **2026-09-13 新增（敏感数据流审计批次）**：转登自已退役的 `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`
> （处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §41）。以下为在基线 `d32f3e7` 与当前 `a669a48`
> 对拍后**仍成立**、且**不改变安全承诺**的低危 / 卫生项，逐项附核实结果。
>
> **不转登**：`L11`（`KdbxKeyFileGenerator` 的 hex `String` 为交付物）属**格式边界且设计接受**，非缺陷；
> `L12`（口令长度进日志）经复核**在 HEAD 已不存在**（`UnlockViewModel.kt:191` 文案为 `input updated`，不含长度）——
> 该条在报告基线之后已由代码变更消除；`L22`（`_gitobj/` 未被 `.gitignore` 覆盖）为**空目录**，
> git 本不跟踪空目录，非问题。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P3-98 | 审计 L1 | `proguard-rules.pro:144-147` 的 `AppLog` 剥离规则写作 `public static void v/d(...)`，而 `AppLog.kt` 的 `v/d` 是**实例方法**（`object AppLog`，无 `@JvmStatic`）→ 规则为 no-op（影响为零：生产零 `AppLog.v/d` 调用点 + 运行期 `debugEnabled` 守卫） | 改为实例方法签名，或删除该误导性规则；如保留须注明「双保险不成立」 |
| ISSUE-P3-99 | 审计 L2 | `DatabaseSession.changeCredentials` 的默认参数 clone 未清零（`:357 newKeyFileData = credentials.keyFileSnapshot()`；方法内仅擦 `oldPwd` / `oldKey`，`:392-393`）。**第四轮机制更正**：会话缓存持**独立** clone（`SessionCredentialCache.kt:29-30` / `:41` / `:44` / `:69` 多重克隆，非"接管别名"）——**补擦可行且必要**（当前确漏一份冗余副本，RC-02 族） | **在 save 与 `restoreCredentials` 之后的 `finally` 中**补 `newKeyFileData?.fill(0)`（**不得**写在默认参数表达式或 `rotateCredentials` 之前——写前擦会静默写出用全零密钥文件加密的库）；断言「换密后无未擦密钥文件副本」**且**换密后库仍可正常解锁 |
| ISSUE-P3-100 | 审计 L3 | `SyncProviderResolver.resolveRemotePath` 解密同步凭据后不清零（`:105-121` 的 `cfg.password` / `cfg.accessKey` / `cfg.secretKey`）；对照 `resolveProvider` 已清零（`:57,98-99`） | 对齐 `resolveProvider` 的 finally 清零；断言两侧一致 |
| ISSUE-P3-101 | 审计 L4 | `S3RequestSigner.getSignatureKey` 漏擦 `combined`（含 `"AWS4"‖secretAccessKey`），仅擦 `prefix` / `keyBytes`（`:110-118`） | 补擦 `combined`；断言签名后无残留 |
| ISSUE-P3-102 | 审计 L5 | 全量 SHA-1 口令哈希作为 `String` 驻留堆（`BreachCheckCoordinator.kt:63-65`）；虽仅出网前 5 位前缀（`BreachHasher.kt:31-32` → `queryRange(prefix)`），无盐 SHA-1 仍是口令等价物 | 改字节态计算 + 显式清零；断言「全量哈希不物化为 String」 |
| ISSUE-P3-103 | 审计 L6 | `SecureCaptureActivity`（TOTP 扫码取景）设了 `FLAG_SECURE` + `setHideOverlayWindows(true)`，但**未** `setFilterTouchesWhenObscured`（`:24-28`） | 接线遮挡触摸过滤（与自动填充 / 通行密钥窗口一致）；或留痕说明「`setHideOverlayWindows` 已覆盖，无需叠加」 |
| ISSUE-P3-104 | 审计 L7 | `KdbxAttachment.data` 对内存附件**直接返回内部 `inlineData`**（`:41 get() = source?.load() ?: inlineData`），与类 KDoc（`:17-18`「不与任何持有者共享可变引用」）矛盾（属性 KDoc `:33-34` 已如实修正） | 统一 KDoc 与实现口径（改类 KDoc 或返回副本）；若改返回副本须评估清零责任归属 |
| ISSUE-P3-105 | 审计 L8 | `VaultEntrySecretReader.getAttachmentData` 落盘路径**双重拷贝**（`:165-166`：`BinarySource.load()` 已返回独立副本，再 `.copyOf()`），第一份未擦 | 去掉冗余 `.copyOf()`；或对第一份补清零 |
| ISSUE-P3-106 | 审计 L10 | `res/xml/data_extraction_rules.xml:3-14` 未排除 `external` / `device_*` 域（当前暴露为零，因外部存储 API 全仓零使用） | 补排除域（纵深防御）；或留痕「已核实外部存储 API 零使用，无需排除」 |
| ISSUE-P3-107 | 审计 L13 | `.kdbx.bak` 默认保留（`ExtendedSettings.kt:18 createBackupBeforeSave = true`），内容为**用「上一个」口令加密**的完整库；仅换密成功后删（`DatabaseSession.kt:390`） | 明确文档化该行为与保留期；或对「换密」之外的口令变更路径同样处置 |
| ISSUE-P3-108 | 审计 L14 | `AtomicFileWriter` 的 `<vault>.kdbx.tmp` 残留窗口（`:76`），同步流式搬运时附件明文也经该路径 | 评估写失败 / 中断后的 `.tmp` 清理时机；断言异常路径无残留 |
| ISSUE-P3-110 | 审计 L16 | `ExportConfirmationPolicy` 对**明文 XML / CSV** 导出**仅在 UI 层**强制（`DatabaseSettingsScreen.kt:368,413`；`SettingsExportController.kt:107-127` 与 `SettingsViewModel.kt:409-413` 未校验，`confirmed = true` 恒真）。注：**附件**导出已在 VM 层校验（`EntryDetailViewModel.kt:362`） | 确认令牌下沉到仓库层（构造器 `internal`）；直接调用仓库导出应失败；断言覆盖 |
| ISSUE-P3-111 | 审计 L17 | `PasswordFillActivity` / `PasskeyAssertionActivity` 不检索系统下发的 provider 请求（全仓无 `retrieveProviderGetCredentialRequest` 调用；对照 `PasswordSaveActivity.kt:27` / `PasskeyCreateActivity.kt:69` / `CredentialUnlockActivity.kt:67` 分别检索 create / begin 请求）→ 系统认证的 `CallingAppInfo` 未与 `expectedPackage` 交叉核对 | 确认是否需要交叉核对（结合 `ISSUE-P3-93` / `ISSUE-P2-46`）；若需，补检索 + 断言 |
| ISSUE-P3-112 | 审计 L18 | `SafDocumentCleanup.deleteCreatedDocument` **无条件**删除（`:20-25`）；当前安全仅因全部调用方使用 `CreateDocument` | 改为按结果码 / URI 归属判定；或显式断言「仅 `CreateDocument` 产物可调用」 |
| ISSUE-P3-113 | 审计 L19 | `AutofillFieldBlocklistStore.isBlocked` 在签名无法计算时返回 **true**（`:54`）→ Keystore HMAC 失败会**静默禁用自动填充**（方向 fail-closed，但故障模式对用户不透明） | 保持 fail-closed 但补可观测性（日志 / UI 提示）；断言失败路径行为 |
| ISSUE-P3-114 | 审计 L20 | `values/strings.xml:592`（及 `values-en`）承诺「不进入剪贴板历史与云同步」，而实现仅设 `EXTRA_IS_SENSITIVE`（`ClipboardSecurityManager.kt:51`） | 核实该标记在目标 API 级别的确切语义后二者取其一：修正文案或补足实现；断言文案-实现一致 |
| ISSUE-P3-115 | 审计 L21 | `gradle/wrapper/gradle-wrapper.properties:3` 的 `distributionUrl` 指向第三方镜像 `mirrors.cloud.tencent.com`（非 `services.gradle.org`），完整性仅靠 `:7` 锁定的 SHA-256；`AGENTS.md` §1 只记哈希锁定、未记镜像来源 | 在 `AGENTS.md` §1 补记镜像来源与锁定哈希；或评估改回官方分发源 |

---

> **2026-09-13 新增（威胁建模 / 安全整改报告退役批次）**：转登自同日退役删除的
> `THREAT-MODEL-AUDIT-d32f3e7.md` 与 `SECURITY_AUDIT_REMEDIATION.md`
> （处置归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §42 / §43）。以下为对拍后仍成立、
> 且**不改变安全承诺**的低危 / 卫生 / 核实缺口项。
>
> **核实时间点与核实方式（2026-09-13，对 HEAD `a669a48`）**：直读源码 + 全仓检索双证据——
> `AppTerminationPolicy.kt` / `KeePasskeyApp.kt`（退出路径）、全仓 `clearPasswordOnLeave` 检索（9 处命中均为
> 持久化 / 投影 / UI 回调）、`DatabaseSession.kt`（`save` / `exportToBytes`）、`KdbxDatabase.kt`、
> `RuntimeIntegrityDetector.kt`、`SyncNetworkOptions.kt` / `SyncProviderResolver.kt`（SSRF 逃生通道）、
> `DigitalAssetLinksVerifier.kt`（HTTP 客户端构造）、`git ls-files gradle/`（无 `verification-metadata.xml`）、
> `.github/workflows/`（无 `connectedAndroidTest`）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13，对 HEAD `a669a48`） | 验收标准 |
|---|---|---|---|
| ISSUE-P3-116 | 威胁建模 T-9b | **「彻底退出应用」不清缓存**：`AppTerminationPolicy.terminate` 仅执行 `detachTask + exitProcess(0)`（`AppTerminationPolicy.kt:29-32`，调用点 `KeePasskeyApp.kt:57-69`），**不经** `SessionLockObserver` → `cacheDir/attachments`（明文附件）、`cacheDir/sync`（密文快照）与 `.kdbx.bak` 留在磁盘上；用户以为"退出即安全"，实际不是 | ① 退出前执行与锁库等价的清理（缓存驱逐 + 树擦除），或 ② 如实声明"退出 ≠ 清理"并在 UI 提示；③ 断言退出路径触发清理（或断言已如实提示） |
| ISSUE-P3-118 | 威胁建模 T-16（残余） | **序列化缓冲只擦最终数组**：`DatabaseSession.save()` 以 `ByteArrayOutputStream().also { … }.toByteArray()` 产出字节（`DatabaseSession.kt:219-223`），仅对**返回的数组** `fill(0)`（`:226`）——产生它的 `ByteArrayOutputStream` **内部缓冲**（第二份完整序列化密文）从不擦除；`exportToBytes()` 同型（`:303-307`）。内容为密文，影响有限 | ① 复用同一 `ByteArrayOutputStream` 并显式擦除 / `reset()`，或改流式签名从源头消除整库物化；② 断言写入后无未擦缓冲（可用具名缓冲替代匿名链式调用） |
| ISSUE-P3-119 | 威胁建模 Q-10 / T-15（残余） | **内存附件池无擦除入口**：`KdbxDatabase.clearSensitiveData()` 只调 `rootGroup.clearSensitiveData()`（`KdbxDatabase.kt:62-64`），`binaries: List<InnerHeader.BinaryItem>`（`:19`）无清零 API → ≤1 MiB 附件明文仅随引用丢弃并期待 GC；`AGENTS.md` §6 只声明了 `KdbxAttachment.clear()` 对**落盘项**不动作，未声明内存池语义（**第四轮批注（NEW-B01-4，升格 P1 批次）**：`SyncConflictController` / `SyncContentChangeDetector` / `loadAndApplyRemoteBytes` **从不调用** `clearSensitiveData()`——穷尽式通读证实；原记录只列 `lastSyncedDb` 一个树外引用者，**漏三处**） | ① 明确并文档化"内存池生命周期 + 可达性"（树外引用者 `SyncCoordinator.lastSyncedDb` 等已由 `ISSUE-P1-07` 的锁库释放收口，须逐点确认后写入 `AGENTS.md` §6）；② 评估让 `clearSensitiveData()` 覆盖 `binaries`（须处理共享引用者，防误伤，对齐 `R-CLEAR-2` 所有权约定）或如实登记为已接受边界；②' 三处同步路径（`SyncConflictController` / `SyncContentChangeDetector` / `loadAndApplyRemoteBytes`）补 `clearSensitiveData()`；③ 结论须与 `ProtectedString` 密钥不可轮换的论证保持一致 |
| ISSUE-P3-120 | 威胁建模 Q-11 | **`RuntimeIntegrityDetector` 拦截力无实测**：检测为"磁盘路径存在性 + `/proc/self/maps` 特征串"启发式（`RuntimeIntegrityDetector.kt:100-149`），非完整性证明；真实 Frida（默认 gadget 名、改名、内存加载）下的命中率未知。若命中率低，则"COMPROMISED 时禁用生物解锁 + 自动填充"的政策在真实攻击下形同虚设，却给用户"已被保护"的错觉 | ① 真机以 Frida 实测三种形态（默认 / 改名 / 内存加载），记录命中率并写入设备侧基线；② 按结果决定加强检测**或**在 `RuntimeIntegrityPolicy` / UI 如实声明"启发式、非证明"；③ 结论与 `ISSUE-P2-63`（快照门控）统一口径 |
| ISSUE-P3-121 | 威胁建模 T-17 | **自建内网 WebDAV / NAS 在出厂配置下不可用**：`SyncNetworkOptions.ssrfAllowedHosts` 默认空集（`SyncNetworkOptions.kt:25`），`SyncProviderResolver` 两处均传默认 `SyncNetworkOptions()`（`:52,77`）→ RFC1918 / `.local` 主机一律被 `SyncEndpointGuard` 拒绝，且**无生产逃生通道**；与 `README` 宣称支持 WebDAV（Nextcloud / ownCloud 等常见家用自建形态）存在张力 | ① 明确产品口径（"支持内网自建"或"明确不支持"）；② 若支持，提供**受控**逃生通道（用户显式声明内网主机 + 风险二次确认，默认仍为拒绝）；③ 文档与实现必须一致，不得只改其一 |
| ISSUE-P3-122 | 审计附录 C（T6 待复核区） | **IPC 面 4 项——第四轮已逐条裁定**（`SECURITY_RECHECK` §6.8，留痕完整）：`IPC-01` **成立（部分）**——4 个 PendingIntent 的 requestCode 全为常量、3/4 带 `FLAG_UPDATE_CURRENT`，成立面为「TOTP 错配 + 30 秒授权串扰（需 `P3-42`，默认关）+ 确认页文案」，"凭据值串扰"不成立；CM 通道同构（`CredentialResponseAssembler.kt:66` 每响应局部分配器，`:234` 注释自认历史缺陷表现）；`IPC-02` **不成立**——落地 Activity 全部 `exported="false"`（7 个），第三方无法伪造 extras；`IPC-05` **误报**——`javap` 直读 `credentials-1.6.0` 证实 JSON 层级与字段完全对应；`IPC-10` **成立（LOW）**——`onSaveRequest` 无超时且平台不提供 `CancellationSignal`（后果有界，仅 Availability） | ① ~~逐条复核~~ **已完成**（防重复上报依据即上述裁定）；② 残余整改两项：`IPC-01` 的 requestCode 单调化（与 `CredentialResponseAssembler` 同构修复）+ `IPC-10` 的 `onSaveRequest` 超时预算；③ 各补回归断言 |
| ISSUE-P3-123 | 审计附录 C（T7）+ `AGENTS.md` §6 | **CI / 供应链硬化遗留 4 项**（均于 2026-09-13 直读核实；**第四轮更新**）：① `gradle/verification-metadata.xml` **不存在**（依赖校验元数据 / 版本锁定缺失，`gradle/` 仅 4 文件；全仓 `dependencyLocking` / `lockAllConfigurations` 零命中）；② `gradle/gradle-daemon-jvm.properties` 仅 `toolchainVersion=21`，**无分发校验和**（对照 `gradle-wrapper.properties:7` 已锁）；③ 三个工作流**均不运行** instrumented 用例（7 个关键词零命中）——设备侧实测 **28 例**（app 12 / database 6 / sync 3 / crypto 7，`@Test` 注解计数，第四轮更正原记 15 例）目前只能本地跑；④ `mapping.txt`（77.5 MB）**已确证** CI 无条件上传（`build.yml:226-238`，`if: always()` 无可见性限制）→ 公开仓库等同公开去混淆映射 | ① 评估引入依赖校验 / 版本锁定（或如实登记为已知限界）；② Daemon JVM 分发补校验和；③ 至少为 `:crypto` / `:database` 建立可复跑的托管设备任务，使设备侧用例进入 CI 基线（注意陷阱 #21：Linux runner 无 KVM，`AssumptionViolatedException` 会被 AGP 记为 `<failure>`，须改 `macos-latest` 并接受计费）；④ 按"公开 artifact"处置 `mapping.txt`（可见性收窄或上传前剔除）并留痕 |
| ISSUE-P3-124 | 审计附录 C（`SUPPLY-06` / `AC-06`） | **DAL 出口未接纵深防御**：`DigitalAssetLinksVerifier` 自建裸 `OkHttpClient.Builder()`，仅设连接 / 读 / 调用三个超时（`:61-64`），**无** SSRF / DNS 重绑定守卫（对照 `SyncEndpointGuard`）、**无** TLS-only `ConnectionSpec` 声明（对照 `SyncHttpClientFactory`）；目标 host 来自调用方影响面（webDomain / origin），属纵深防御缺口（方向 fail-closed：验签失败即不授权，无机密性影响）（**第四轮批注**：作为漏洞为**误报**——scheme 硬编码 `https://`、路径固定、结果不回流三值枚举，可利用性为零，维持 HARDENING；另 `SUPPLY-04` 补出 `dalVerifier.verify` **第二个**生产出口 `AutofillOriginResolver.kt:45`，且自动填充侧**无** skip 开关而 Passkey 侧有 `skipDalVerification` → 隐私控制不对称，一并治理） | ① 复用 `SyncEndpointGuard` 的主机判定与 TLS-only 配置，或**如实登记**"仅请求 https 固定路径、未做内网可达性防护"；② 断言非 https / 内网目标请求被拒绝；③ 与 `ISSUE-P2-74`（包可见性）同批评估，避免重复排查；④ 评估两侧 skip 开关对称化或留痕 |

> **2026-09-13 新增（第四轮独立复核定版批次）**：以下 2 项为复核新发现低危项合并登记
> （来源 SECURITY_RECHECK §11 / §6.8，附核实行号）。

| 编号 | 来源 | 问题与位置（核实于 2026-09-13） | 验收标准 |
|---|---|---|---|
| ISSUE-P3-125 | 复核 `NEW-N2` / `NEW-B09-x` / `B03-N1` | ① **选择器零匹配仍无条件挂入**：`buildPickerDataset` 在 `appendUnlockedDatasets` 之后无条件调用（`KeePasskeyAutofillService.kt:196-203`），无候选数门槛 → 严格匹配设计对任意应用失效；② **生产可重定向出口**：`DigitalAssetLinksVerifier.endpointOverride` 为 `@Singleton` 上的 `@Volatile internal var`（`:57-59`），自称测试注入点但生产可达；③ **cargo 失败与缺失不可区分**：`crypto/build.gradle.kts:111-120` `isIgnoreExitValue = true`，`cargo --version` 探活只区分"缺失" → 构建失败静默降级、JNI 用例假绿而构建保持绿色 | ① 评估零匹配时改挂"无可信候选"占位数据集，或留痕接受现设计；② `endpointOverride` 改构造注入 / 测试专用隔离（防生产重定向）；③ 构建失败 fail-closed，或区分"缺失降级"与"构建失败"两级日志 + CI 断言 |
| ISSUE-P3-126 | 复核 `B07-N1` / `B07-N2` / `NEW-B02-2` | ① **一次性 CI 密钥签 release 形态 APK**：`build.yml:165-178` `keytool -genkeypair` 生成临时密钥签 `assembleRelease` 并上传 artifact（发布身份混淆，注释自称非发布密钥）；② **suppression 白名单文案不实**：`dependency-scan.yml:15-16` 称"当前为空白名单"，实测 `.github/owasp-dependency-suppressions.xml` 有 **5 条** `<suppress>`（豁免 36 条 CVE）；③ **KDBX minor 版本不校验**：`KdbxHeader.kt:232-236` 只查 major 位，minor 原样放行；`KdbxConstants.kt:28` `VERSION_4_1` 为死常量（全仓零引用）；`SettingsUiState.kt:220` UI 仍标注"KDBX 4.1" | ① CI 签名产物明确标注"非发布签名"或停传 release APK（防下游误当官方构建）；② 修正 workflow 文案；③ 明确 minor 版本策略（校验或如实声明"仅 major"），并处理死常量与 UI 文案三者一致性 |

---
