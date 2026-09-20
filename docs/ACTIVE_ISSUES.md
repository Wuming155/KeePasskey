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

## P1 高危与核心功能问题（2 项）

### ISSUE-P1-223：设置页开启生物识别开关点击后闪退（BiometricAuthManager 缺 negativeButtonText 引发致命崩溃）

- **核实时间点**：2026-09-20 经代码走查与 AndroidX 官方契约对拍核实。
- **核实方式**：查阅 `BiometricAuthManager.kt:130` 与 `BiometricEnableCoordinator.kt:225`，在未设置 `DEVICE_CREDENTIAL` 时，`negativeButtonText` 为 `null` 未调用 `setNegativeButtonText()`，直接调用 `build()` 引发 `IllegalArgumentException: Negative text must be set and then should be non-empty.`。
- **背景与根因**：
  1. `BiometricAuthManager.authenticate` 内部构建 `BiometricPrompt.PromptInfo.Builder` 时，条件 `if (!usesDeviceCredential && negativeButtonText != null)` 导致在 `negativeButtonText == null` 且 `usesDeviceCredential == false` 时未设置负向按钮文本；
  2. AndroidX 规范硬性要求：若 authenticator 不含 `DEVICE_CREDENTIAL`，`PromptInfo.Builder.setNegativeButtonText` 为必选项，缺失直接导致 `build()` 抛出未捕获的 `IllegalArgumentException`，主线程立即崩溃闪退；
  3. `BiometricEnableCoordinator.kt`、`BiometricEnrollmentCoordinator.kt`、`BiometricUnlockCoordinator.kt` 均未传 `negativeButtonText`，直接暴露在崩溃路径上。
- **涉及文件**：
  - `app/src/main/java/com/keepasskey/app/security/BiometricAuthManager.kt`
  - `app/src/main/java/com/keepasskey/app/ui/screens/settings/BiometricEnableCoordinator.kt`
- **整改依据与验收标准**：
  1. **AC① 防御性兜底**：在 `BiometricAuthManager.authenticate` 中，当 `!usesDeviceCredential` 时，若 `negativeButtonText` 为 null 或空白，自动回退采用 `activity.getString(R.string.btn_cancel)`，绝不允许抛出 `IllegalArgumentException`；
  2. **AC② 调用方显式传参**：`BiometricEnableCoordinator` 等发起生物验证处显式传入取消文本；
  3. **AC③ 单测全覆盖**：编写或更新针对 `BiometricAuthManager` 负向按钮兜底逻辑的断言测试，保证 JVM / 平台下不再发生闪退。

---

### ISSUE-P1-224：外部输入账号密码后点击保存未落盘写入文件

- **核实时间点**：2026-09-20 经 `KeePasskeyAutofillService.kt`、`AutofillDatasetBuilders.kt` 与 `VaultEntryWriteCoordinator.kt` 代码审查核实。
- **核实方式**：
  1. `applySaveInfoIfNeeded` 中未设置 `SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`，且将用户名与密码一同作为必填项 `requiredIds`，导致输入框未变更或视图隐藏时无法稳定触发表单捕获；
  2. `handleSaveRequest` 仅取 `request.fillContexts.lastOrNull()`，在表单提交触发界面跳转或原输入框清空时，最后一个 context 往往丢失密码数据；提取出的 `password.isBlank()` 时直接调用 `callback.onSuccess()` 静默结束，完全不落库；
  3. 库处于锁定时，`onSaveRequest` 直接调 `saveAutofillCredential` 导致 `persistSession()` 报 `当前无活动数据库` 并向系统报失败，未提供解锁引导或安全保存通道。
- **背景与根因**：
  用户在第三方应用或浏览器输入新凭据，提交时系统弹出保存提示，用户点击保存后，由于上下文提取失真或密码库在后台已自动锁定，保存操作未真正完成，文件内没有任何新记录。
- **涉及文件**：
  - `app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt`
  - `app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt`
  - `app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt`
- **整改依据与验收标准**：
  1. **AC① 多 Context 鲁棒回溯**：`handleSaveRequest` 倒序遍历 `request.fillContexts`，优先选取包含非空密码与有效账号的上下文，并在单个 context 中优先匹配含有效文本的密码节点，杜绝因 `lastOrNull()` 取到空跳转页而静默丢弃；
  2. **AC② 规范 SaveInfo 契约**：`applySaveInfoIfNeeded` 引入 `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`；将密码设为 `requiredIds`，用户名设为 `optionalIds`，提升系统保存提示触发率；
  3. **AC③ 库锁定状态友好处理**：当保存触发时若库处于锁定状态，若系统支持通过 `IntentSender` 交互，安全唤起解锁与保存承接；在已解锁态下保证 `vaultRepository.saveAutofillCredential` 真正写盘成功后回调 `onSuccess()`，失败时明确留痕。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（本区最近一次归零：§226 闭环的 `ISSUE-P2-220`——Passkey 创建链路 fail-closed 拒绝时
> 零用户反馈；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/226-Passkey创建链路拒绝原因呈现批次.md`](resolved/batches/226-Passkey创建链路拒绝原因呈现批次.md)）。

> **历史 P2 条目**（§219 闭环的 `ISSUE-P2-199` / `ISSUE-P2-200` / `ISSUE-P2-208`，§220 闭环的
> `ISSUE-P2-210` / `ISSUE-P2-211`，§223 闭环的 `ISSUE-P2-212`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。

---

## P3 低危问题、特性接线与体验优化（1 项）

### ISSUE-P3-225：已解锁常驻通知未联动呈现自动锁定倒计时

- **核实时间点**：2026-09-20 经 `UnlockedNotificationController.kt` 与 `AutoLockManager.kt` 代码审查核实。
- **核实方式**：
  查阅 `UnlockedNotificationController.kt:124`，通知构建显式 `.setShowWhen(false)`，正文写死固定文案 `R.string.notification_unlocked_text`（“自动锁定计时进行中，点击返回应用”），未设置 `setUsesChronometer(true)` 与截止时间戳，亦未监听 `AutoLockManager` 的后台锁定倒计时。
- **背景与根因**：
  应用已具备常驻已解锁通知，文案声称“计时进行中”，但用户在系统通知栏上看不到任何实时的倒计时数字（分:秒），导致通知未能发挥提示超时锁定的实际功效。
- **涉及文件**：
  - `app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt`
  - `app/src/main/java/com/keepasskey/app/notification/UnlockedNotificationController.kt`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-en/strings.xml`
- **整改依据与验收标准**：
  1. **AC① 状态流透传倒计时截止点**：`AutoLockManager` 暴露 `lockDeadline: StateFlow<Long?>`，在进入后台且配置了超时自动锁定时输出 `backgroundTimestamp + timeoutMillis`，回前台或从不锁定时回落 `null`；
  2. **AC② 接入系统 Chronometer 倒计时**：`UnlockedNotificationController` 订阅该状态流；当存在有效截止时刻时，配置 `setWhen(deadline)`、`setShowWhen(true)`、`setUsesChronometer(true)` 与 `setChronometerCountDown(true)`，由 Android SystemUI 原生渲染秒级递减，零轮询零额外耗电；
  3. **AC③ 空闲态自然回退**：无倒计时时（前台或永不锁定），通知展示默认已解锁状态文案，关闭 Chronometer；
  4. **AC④ 守护单测全覆盖**：编写单元测试验证倒计时时间戳计算与通知目标态的联动。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，以及 §224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)）。
