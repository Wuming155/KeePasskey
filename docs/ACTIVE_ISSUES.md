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

## P1 高危与核心功能问题（1 项）

### ISSUE-P1-238：冷启动关键路径仍逼近系统凭据创建应答预算（点保存间歇性无反应）

- **核实时间点**：2026-09-21 于真机 Redmi 4X（santoni，`lineage_Mi8937_4_19`，Android 17 / API 37）实测。
- **核实方式**：以测试 APK 内**独立包名**的真实客户端（`CredentialSaveClientActivity`，语义等价第三方应用）
  调用 `CredentialManager.createCredential`，经 `adb logcat` 逐轮比对
  `ActivityManager: Start proc …KeePasskeyCredentialProviderService` 与
  `KeePasskeyCredProvider: onBeginCreateCredentialRequest` 两条时间戳。
- **背景与根因**：系统给 provider 的创建应答预算约 **3.0 s**（`Provider session created` →
  `Remote provider response timed out` 两轮实测 2.97 ~ 3.02 s）。本应用进程自 `Start proc`
  到进入 `onBeginCreateCredentialRequest` 实测 **3.05 / 2.49 / 2.38 s**（三轮），其中 3.05 s
  那轮被系统丢弃（`Remote provider response timed out` ⇒ `TYPE_NO_CREATE_OPTIONS`）——
  用户视角即「点了保存没反应」，且呈**间歇性**。§240 已把最重的
  `periodicSyncScheduler.applySavedSchedule()`（会拉起 WorkManager）移出冷启动关键路径，
  但 `MainApplication.onCreate` 其余同步工作（易失缓存清理、剪贴板对账、自动锁定注册、
  完整性探测、通知通道建立）与 Hilt 图初始化仍在关键路径上。
- **验收标准**：AC① 真机 `force-stop` 后连续 ≥ 10 轮冷启动触发保存请求，
  `Start proc` → `onBeginCreateCredentialRequest` **全部** ≤ 1.5 s（留 2× 余量）；
  AC② 同轮次内 `logcat` **零** `Remote provider response timed out`；
  AC③ 改善手段**不得**削减任何冷启动安全对账语义（`fileBinaryStore.clear()` 与
  `clipboardSecurityManager.reconcileOnColdStart()` 必须仍在 `onCreate` 中同步执行，
  由 `ColdStartAttachmentPurgeWiringTest` 锁定）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/MainApplication.kt`；
  定位与已完成的整改见 §240 批次正文。

---

> **本区历史上一次归零**：§229 闭环的 `ISSUE-P1-223`（设置页生物识别开关闪退）/
> `ISSUE-P1-224`（外部输入账号密码点击保存未落盘）；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md`](resolved/batches/229-生物识别闪退与自动填充保存及解锁通知倒计时批次.md)。

---

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-239：凭据提供者通道「系统未登记本应用」的失效完全静默且用户无法自救

- **核实时间点**：2026-09-21 于真机 Redmi 4X（Android 17 / API 37）实测（与 `ISSUE-P1-238` 同一次排查）。
- **核实方式**：`adb shell settings get secure credential_service`（实测为**空**）、
  `credential_service_primary`（实测指向**不存在的包** `com.keepasskey.app`，而实际包名是 `com.keepasskey`）；
  并以四条归因实验（仅改 `credential_service` / 仅改 `primary` / 全新安装仅写 `primary` / `force-stop` 后冷启动）
  分别观察系统侧 `CredentialManager: starting executeCreateCredential` 与 provider 侧回调是否出现。
- **背景与根因**：`credential_service` 为空时，系统**不会**向本应用发起创建请求（框架层直接
  `CreateCredentialException.TYPE_NO_CREATE_OPTIONS`）。而 ① **全新安装后系统不自动登记**本应用；
  ② 本机 ROM 的「首选服务」选择器只写 `credential_service_primary`（且写入命名空间前缀包名，
  为**悬空组件**），**不写** `credential_service`。结果是保存能力对用户**完全不可见地失效**：
  无报错、无提示，只有「点保存没反应」。自动填充通道早有 `AutofillHealthProbe` 与设置页健康卡片，
  CM 通道**既无自检也无引导**。
- **验收标准**：AC① 新增凭据提供者通道健康检查，在「系统未登记本应用」时给出用户可见状态与
  修复指引（跳转系统设置；`android.settings.CREDENTIAL_PROVIDER` 在本机不可解析时**如实降级说明**，
  不得伪造「已开启」）；AC② 检查结果只反映真实系统状态，**读取失败一律呈现「未知」而非「正常」**
  （沿用本项目既有口径）；AC③ 判定落在纯函数上并被 JVM 单测穷举，平台查询单点化便于注入；
  AC④ 设置页文案不得声称「已从系统移除」（组件注册由 Manifest 决定，应用内无法动态摘除）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/**`、设置页安全分区；
  先例 `AutofillHealthProbe`。已执行的处置与真机验证见 §240 批次正文。

---

> **本区历史上一次归零**：§236 闭环 `ISSUE-P2-231`（Java 依赖面完整性锁定缺失）/
> `ISSUE-P2-232`（完整性风险升级无主动熔断接线）——前者落**重开决策**判「仍不引入」并交付
> 可机检的替代缓解口径（`PD-14`），后者经前置裁决 `PD-13` 判「维持现状」、残余风险登记限界 §26；
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/) 的 §236 批次）。
> 本区上一次归零为 §230 ~ §233 四批闭环 `ISSUE-P2-226` / `P2-227` / `P2-228` / `P2-229`
> ——即用户 2026-09-20 真机报告的四项问题（自身界面仍出现填充建议 / 生物识别被禁用时归因笼统 /
> 自动填充卡三个假开关且文案谎称需要无障碍 / 新建库无位置选择入口）。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（本区归零：§238 闭环 `ISSUE-P3-235`（`RC-02` 敏感缓冲所有权收口）——
> AC① 设计交付 [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md)；
> AC② 逐处迁移：`G1`（§235）/ `G2`（§238）闭环、`G3`（池内擦除）维持已登记限界 §1.6、
> `G4`（命名统一）降为「按需」、`G5` / Step 1 已核实；`test` 343 类 / 2402 例全绿。
> 证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/238-待决冲突解析树身份判定擦除批次.md`](resolved/batches/238-待决冲突解析树身份判定擦除批次.md)）。
>
> **本区近期变动**：§236 闭环 `ISSUE-P3-233`（复核报告 `P3-120` 状态陈旧——更正报告
> §10.1 / §3.3.2 / §2.3 / §2.4 / §6.7 / §9.2 并增补 §15.3 方法学第 12 条）与 `ISSUE-P3-234`
> （DAL 出口 IP 字面量面定案，登记 [`已知工程限界.md`](architecture/已知工程限界.md) §25）；
> §237 闭环 `ISSUE-P3-230`（已有 SAF 库授权失败不再静默——提示 + 列表状态 + 重授入口，
> 限界 §24 的残余段同步收口）。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，§224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。
