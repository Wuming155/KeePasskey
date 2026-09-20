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

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-220：Passkey 创建链路 fail-closed 拒绝时零用户反馈（用户视角为「点了继续就断」）

- **核实时间点与方式**：2026-09-20，用户真机（小米，Release 构建）复现报告 + 全链路代码走查
  （`KeePasskeyCredentialProviderService` → `CredentialCreateEntries` → `PasskeyCreateActivity`）；
  无真机日志，根因由代码路径与用户问答（Via 浏览器 / 密码库已解锁 / KeePasskey 无任何界面露面）唯一收敛。
- **现象**：Via 浏览器打开 passkeys.io 创建通行密钥，系统确认弹窗（「创建通行密钥…将存储在 KeePasskey 中」）
  正常出现；点「继续」后 KeePasskey 无任何界面，浏览器侧流程直接中断。
- **根因**：Via 不在特权浏览器白名单 → `CallingOriginResolver.resolveTrustedOrigin` 降级为
  `android:apk-key-hash:` origin → `PasskeyCreateActivity.passesRegistrationGates` 走普通应用路径执行
  DAL 远程校验 → passkeys.io 无对 Via 包名的 assetlinks 声明 → `NOT_VERIFIED` → fail-closed
  `failAndFinish()`（RESULT_CANCELED）。该拒绝发生在**任何 UI 呈现之前**，用户无法区分
  「功能坏了」与「被安全门控拒绝」。用户侧临时解法（均不改代码）：① 设置 → 自动填充 →
  「特权浏览器白名单」启用 Via（若 Via 请求确实填充 origin 则走浏览器路径豁免 DAL）；
  ② 开启「跳过 DAL 校验」开关（削弱防线，仅限个人设备自担风险）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt`
  （`passesRegistrationGates` 各拒绝分支 / `failAndFinish`）、`app/src/main/java/com/keepasskey/app/passkey/BaseCredentialActivity.kt`
  （统一收尾）。
- **AC**：
  1. 创建链路任一 fail-closed 拒绝分支（缺系统注入请求 / 缺注册参数 / DAL 未通过 / `excludeCredentials`
     命中 / 锁定态复核失败）在受保护窗口内**呈现明确拒绝原因**、由用户确认后关闭，不再静默 `finish`；
  2. 拒绝文案沿用预定义字符串资源（ISSUE-P1-10 口径），不得携带 rpId / 包名等敏感标识；
  3. 对系统的回传契约不变（仍 `RESULT_CANCELED`，浏览器侧仍收到创建失败）；
  4. 单测覆盖各拒绝分支的文案选择与结果回传；`.\gradlew.bat test` 全绿。

> **历史 P2 条目**（§219 闭环的 `ISSUE-P2-199` / `ISSUE-P2-200` / `ISSUE-P2-208`，§220 闭环的
> `ISSUE-P2-210` / `ISSUE-P2-211`，§223 闭环的 `ISSUE-P2-212`）的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)。

---

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**（历史 P3 条目——含 §221 闭环的 `ISSUE-P3-201` / `202` / `203` / `204` / `205` / `206` /
> `209`，§222 闭环的 `ISSUE-P3-212` / `213` / `214`，以及 §224 闭环的 `ISSUE-P3-215`——的实现与验收证据见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 [`docs/resolved/batches/`](resolved/batches/)）。
