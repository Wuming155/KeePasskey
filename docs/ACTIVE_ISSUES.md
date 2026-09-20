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

> **暂无开放项**（本区最近一次归零：§225 闭环的 `ISSUE-P1-216`——云端 CI 并发签名计数器用例触达
> 已清零 `ProtectedString`；实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与
> [`docs/resolved/batches/225-CI门禁四项缺陷整改批次.md`](resolved/batches/225-CI门禁四项缺陷整改批次.md)）。

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

### ISSUE-P3-221：拒绝原因页只说「为什么被拒」，不说「那怎么办」

- **核实时间点与方式**：2026-09-20，用户真机复现报告（Via 浏览器 + passkeys.io）后对 §226 交付物的
  **当场反馈**（原文：「用户知道问题之后，也应该知道怎么解决问题，比如说添加白名单什么的。
  最好能够通过按钮一键跳转到对应位置」）+ 全链路代码走查（`BaseCredentialActivity` /
  `CredentialRejectionScreen` / `KeePasskeyApp` / `MainActivity` / `ExportedComponentHygieneTest`）。
- **现状（§226 的已知边界）**：`ISSUE-P2-220` 让拒绝原因**可见**了，但页面只呈现「为什么被拒」，
  未给出「怎么解决」。用户读到「无法确认调用应用对本站点的归属声明」后仍不知道下一步做什么，
  只能反复重试。**这是 §226 刻意留白的结果**（当时口径为「不存在第二条出路」），非实现缺陷。
- **根因**：`CredentialRejectionReason` 只承载文案资源，没有「用户可执行动作」这一维度；
  且拒绝页渲染在系统经 PendingIntent 拉起的 `PasskeyCreateActivity` 窗口内，跨 Activity 进入
  设置页缺通道。
- **硬约束（已核实）**：
  1. `MainActivity` 是唯一无权限保护的导出组件，`ExportedComponentHygieneTest` 明令其
     **不得消费任何外部 intent 数据**（禁 `getStringExtra` / `onNewIntent` / `intent.action` 等），
     manifest 注释亦写死「本组件不得新增任何 intent 数据消费」⇒ 不能靠 intent extra / deep link 传路由。
  2. 凭据 Activity「跳出去却不结束」会让框架收不到认证完成事件（`AutofillUnlockActivity` 有实测教训）
     ⇒ 必须**先收尾再跳转**。
  3. 文案纪律（ISSUE-P1-10）：一律无 `%` 插值、不得携带调用包名 / rpId / 域名 ⇒ 指引不得点名浏览器。
  4. 既有取向「凭据窗口不提供本应用内导航」（`CredentialUnlockPresenter` 等三处 no-op）⇒ 需论证
     「收尾之后的跳转」属独立动作而非「流程中途外跳」。
- **AC**：
  1. 仅当拒绝原因**确有用户可执行的解法**时，页面才额外呈现一段指引与一个直达动作；
     无从下手的原因（含 `CREDENTIAL_ALREADY_EXISTS` 这类正常结果）维持原布局，不得硬凑入口；
  2. 唯一受支持的补救路径是**特权浏览器白名单**；**不得**提供「跳过 DAL 校验」一类使本次请求通过
     或削弱核心防线的直达路径；
  3. 动作发生在**收尾之后**（`RESULT_CANCELED` + `finish()` 先行），对系统的回传契约与 §226 完全一致；
  4. 跨 Activity 路由传递不得违反 `ExportedComponentHygieneTest`；未解锁时不得绕过解锁门；
  5. 单测覆盖「原因 → 动作」穷举映射、指引文案（中英齐备且无插值）、「先收尾再跳转」顺序与信箱幂等；
     `.\gradlew.bat test` 全绿。
- **涉及文件**：`app/.../passkey/CredentialRejectionAction.kt`（新）、
  `app/.../ui/navigation/RemediationNavigationEffect.kt`（新）、
  `app/.../passkey/BaseCredentialActivity.kt`、`app/.../passkey/CredentialRejectionScreen.kt`、
  `app/.../ui/KeePasskeyApp.kt`。

> **历史 P3 条目**（含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，以及 §224 闭环的 `ISSUE-P3-215`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)）。
