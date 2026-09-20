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

## P2 中危缺陷与协议/测试缺口（1 项）

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
