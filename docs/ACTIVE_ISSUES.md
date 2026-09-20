# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
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

> **暂无开放项**（本区最近一次归零：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)）。

---

## P2 中危缺陷与协议/测试缺口（3 项）

### ISSUE-P2-227：生物识别被完整性闸门禁用时提示未点名命中信号（归因笼统）

- **核实时间点**：2026-09-20 经用户真机反馈 + 代码走查核实。
- **核实方式**：文案 `sec_biometric_integrity_blocked`（`strings.xml:615`「设备存在安全风险，已禁用生物识别快速解锁，请改用主密码解锁」）**由本应用产生**，非 `BiometricPrompt` / Keystore 返回；触发点 `BiometricAuthManager.kt:116` 读 `currentEnforcement().disableBiometricQuickUnlock`，`RuntimeIntegrityPolicy.kt:183-216` 中 ELEVATED / COMPROMISED / UNDETERMINED 三档皆置 true。
- **背景与根因**：用户看到「设备存在安全风险」无法分辨究竟是「**本机跑的是可调试构建**」（`appDebuggable`，`RuntimeIntegrityDetector.kt:173` 读 `FLAG_DEBUGGABLE`）、「**安装来源不在受信任清单**」（`:286-293`）、还是「**真被 root / 注入框架攻破**」（`:174-176`）「**快照陈旧/扫描未完成**」（`RuntimeIntegrityPolicy.kt:270-274`）。**开发者自测环境（debug 包）必然命中 ELEVATED**，故该提示在正常开发路径上恒定出现，用户据此怀疑整机安全。**另有一处注释前提为假**：`RuntimeIntegrityDetector.kt:276` 称「adb 直装 installer 为 `null` 故不升级」，实测 adb / `pm install` 记录的 `installingPackageName` 为 **`com.android.shell`（非 null）**，不在 `TRUSTED_INSTALLERS`（`:463-468`）⇒ 侧载的 release 包同样被判 ELEVATED。
- **裁决范围（用户 2026-09-20 明示）**：**仅整改归因呈现 + 更正错误注释**；`com.android.shell` **不**加入受信任清单、debug 构建**不**放宽降级——判据与 fail-closed 分级保持不动（避免降低安全性）。
- **验收标准**：AC① 生物识别被拦时的文案点名**具体命中信号**（可调试构建 / 安装来源包名 / root 痕迹 / Magisk 痕迹 / 注入框架 / 调试器附加 / 正被 trace / 尚未完成扫描），多信号并中时按危害度排序呈现，全部资源化中英双语；AC② 设置页风险卡同源（不得第二数据源）；AC③ 错误注释就地更正；AC④ 新增纯函数级用例穷举「信号集合 → 文案资源 id」映射。

### ISSUE-P2-228：自动填充卡三开关为无持久化无消费方的假开关，且「无障碍」措辞与事实不符

- **核实时间点**：2026-09-20 经全仓 grep + Manifest 核对核实。
- **核实方式**：① `AndroidManifest.xml` 仅注册 `CredentialProviderService`（`:137`）与 `AutofillService`（`:157`），全仓 `AccessibilityService` 子类**只出现在 `RuntimeIntegrityDetector` 的第三方服务枚举里**（`:194-211`），本应用**没有**任何无障碍服务；② 字符串 `autofill_legacy_title`「旧版自动填充服务」/ `autofill_legacy_sub`「兼容低版本 Android 的无障碍填充方式」（`strings.xml:813-814`，EN `values-en/strings.xml:807-808`「Accessibility-based filling compatible with older Android versions」）；③ 该开关绑定 `uiState.autofillServiceEnabled`（`AutofillSettingsComponents.kt:97`），默认 `true`（`SettingsUiState.kt:132`、`SettingsPreferencesController.kt:51`），`setAutofillServiceEnabled`（`:228`）只更新内存 `StateFlow`——`ExtendedSettingsStore` **无对应持久化 key**、`KeePasskeyAutofillService` **零消费**，唯一去处是健康卡入参（`AutofillSettingsScreen.kt:75`）；④ 同卡 `credentialProviderEnabled` / `passkeySupportEnabled` 全仓 grep **唯一消费点即 UI 自身**（`:81` / `:89`）。
- **背景与根因**：用户按文案理解「这是需要无障碍权限的旧版通道」，与「安全防护」页「已启用无障碍服务」（`strings.xml:612`，判据为**非本应用**的第三方无障碍服务）撞成语义冲突；实际是**关掉不影响任何行为、重启即回 true 的假开关**，且「旧版」在 minSdk 36（`AutofillService` 自 API 26 起）下无对应实体。项目已有先例反对假开关：`AutofillSettingsComponents.kt:101-108`（ISSUE-P0-02「避免设置页出现『看似可关』的假开关」）、ISSUE-P3-44 把假保存开关接线为真实消费方。
- **验收标准**：AC① 中英文案去除「无障碍」「旧版」虚假描述，如实表述其控制对象；AC② 三开关持久化到 `ExtendedSettingsStore`（重启不回弹）；AC③ 开关有**真实生产消费方**（关闭即对应通道不下发/不注册/不响应），消费点 fail-closed 默认值明确；AC④ 与系统真实状态（`AutofillHealthProbe` 的 `systemEnabled` / CM 可用性）联动呈现，并提供既有 `SystemSettingsNavigation` 跳转入口；AC⑤ 新增接线守卫用例，禁「UI 有开关、生产无消费方」复现。

### ISSUE-P2-229：新建密码库强制落应用私有目录，未给位置选择（SAF 缺失）

- **核实时间点**：2026-09-20 经新建链路走查核实。
- **核实方式**：`DatabasePickerScreen.kt:197`「新建」→ `CreateVaultWizardDialog`（`:263`）→ `DatabasePickerViewModel.kt:131` → `RealVaultRepository.kt:151` → **`VaultLifecycleCoordinator.kt:125/130` `File(context.filesDir, "$name.kdbx")`** ⇒ 实际路径 `/data/user/0/com.keepasskey/files/x.kdbx`；对照「打开已有库」已走 SAF：`OpenExistingVaultDialog.kt:43` `OpenDocument` + `VaultLifecycleCoordinator.kt:209` `takePersistableUriPermission(READ|WRITE)`。`产品裁决登记.md` PD-01~PD-12 **无**存储位置裁决；`Privacy-Policy.md:82` 反而承诺「或用户自行选择的存储位置」。
- **背景与根因**：新建即静默落在私有目录，用户既看不到也无法选位置；而 `docs/ACTIVE_ISSUES` 之外的既有能力（SAF 读写通道）已具备，属**遗漏**而非取舍。**改造风险面（须如实处理，不得静默降级）**：① `AtomicFileWriter.kt:12-30` 的 `.tmp` + `renameTo`/`ATOMIC_MOVE` + `.bak` + 目录 fsync 在 SAF 文档上**不可用**（现存 SAF 通道已用 `openOutputStream(uri,"rwt")` 截断式非原子写，本身违反规则 4）；② `SessionOpener.create` 只收 `File`（`:55`）；③ `SyncCycleRunner.kt:180-181` 硬依赖 `currentFile`、`:102` 以 `activeFile.name` 推 remotePath ⇒ SAF 库失去同步能力；④ `takePersistableUriPermission` 现为静默 `catch`（`:213`），provider 不支持时重启后库不可开且无告警；⑤ `VaultDatabaseCatalog.kt:107-112/162/172` 以文件名作 id，内外同名可碰撞。
- **裁决范围（用户 2026-09-20 明示）**：新建向导提供**二选一**——①应用私有目录（默认，原子写 + 同步全功能）②自选位置（`ACTION_CREATE_DOCUMENT`）；选②时**如实提示**该库不支持 WebDAV/S3 同步、写盘为非原子降级方案，并登记进限界表。
- **验收标准**：AC① 新建向导落地位置二选一，默认内部存储；AC② SAF 分支持久化 `content://` 位置且授权失败**显式告警**（不再静默 catch）；AC③ 非原子写降级面在限界表登记，不得声称已满足规则 4；AC④ 现有内部存储路径行为与既有测试语义零回归。

> **历史 P2 条目**（§219 闭环的 `ISSUE-P2-199` / `ISSUE-P2-200` / `ISSUE-P2-208`，§220 闭环的
> `ISSUE-P2-210` / `ISSUE-P2-211`，§223 闭环的 `ISSUE-P2-212`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（本区最近一次归零：§229 闭环的 `ISSUE-P3-225`——已解锁常驻通知未联动呈现自动锁定倒计时）。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
