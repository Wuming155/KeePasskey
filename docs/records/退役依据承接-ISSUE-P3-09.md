# ISSUE-P3-09 依据承接（Action 大版本升级 / ProGuard 收窄 / Compose BOM 回归判据）

> **文档定位**：`ISSUE-P3-09`（ZT-20「供应链与构建加固」）落地时的**依据文档**
> `docs/.handoff/ISSUE-P3-09.md` **从未入库**，全仓 4 处注释曾指向它，构成悬空「依据」引用
> （登记为 `ISSUE-P3-129` ③）。本文件即该依据的**承接处**——承接的判据**只收录现存、可复核**
> 的事实，**不重述、不虚构**原文内容。
> **核实时间点与核实方式（2026-09-15，对 HEAD `46fe97a`）**：
> `git log --all --oneline -- docs/.handoff/`（**无任何输出** ⇒ 该目录**从未入库**）、
> `git log --all --oneline --diff-filter=A -- '*handoff*'`（同样无输出 ⇒ 亦**无可经 `git show` 取回的对象**）、
> 逐处直读 4 个引用点的现存注释与其同文件的判据说明。

---

## 1. 为什么是「承接」而不是「补录」

按归档纪律，悬空依据有两条合规出路：**补录**原文或**改为指向现存依据**。

本节选择后者，理由是**不可补录**：`docs/.handoff/` **从未被任何提交纳入**（上列两条 `git log --all`
均为空），故不存在可 `git show` 取回的 blob ——这与 `SECURITY_RECHECK_2026-09.md`
（曾入库、可按 git 对象完整取回，见 `docs/security/SECURITY_RECHECK_2026-09.md`）情形**不同**，
属 `RESOLVED_LOG.md` §41 立规所称「未跟踪文档退役后无法经 `git show` 取回」的类型。
**不得**据本文件推断原文的措辞或结论；本文件只登记「判据今天在哪里可复核」。

---

## 2. 三组判据的现存复核入口

| # | 原引用点（4 处） | 判据主题 | 现存复核入口（**唯一权威**） |
|:--:|---|---|---|
| ① | `.github/workflows/build.yml:15`、`.github/workflows/dependency-scan.yml:8` | 全部 Action 以 commit SHA 钉死；**本次不跨大版本升级**（上游已有新 major，升级涉 Node 运行时与输入契约变更，无法在本批本地验证） | 两个 workflow 各自的头部设计说明（就地留痕，为权威）；Action SHA 的**真实存在性逐条核对**见 [`ci-静态校准记录.md`](ci-静态校准记录.md) §2 |
| ② | `app/proguard-rules.pro:6` | R8 / ProGuard 规则按「据实最小保留」收窄（无人能给出依据的整包 `-keep { *; }` 降级或删除） | [`proguard-rules.pro`](../../app/proguard-rules.pro) 头部注释 + 逐条 `ISSUE-P3-09（ZT-20）AC2` 行内注释；**收窄前后的逐条差异**以 `git log -p -- app/proguard-rules.pro` 复核（该次收窄出自提交 `9c2a806`「fix(ISSUE-P3-01~16): P3 批次 16 项整体闭环」）；批次归档见 [`03-批次整改归档.md`](../resolved/batches/03-批次整改归档.md) 的 `P3-09` 行 |
| ③ | `gradle/libs.versions.toml:30` | `material3 = "1.5.0-alpha27"` 的**退出条件**：`material3 1.5.0 stable` 发布后删除该项与 `compose-material3-alpha` 别名，**回归 Compose BOM 托管** | [`libs.versions.toml`](../../gradle/libs.versions.toml) 内该声明的就地注释（必要性 / 风险 / 约束 / 退出条件四项）；引入该 alpha 的提交为 `56a1d33`「feat(TASK-07): compileSdk 37 + AGP 9.2.1/Gradle 9.4.1 + Compose BOM 2026.08.00 + Material 3 Expressive」 |

---

## 3. 复核配方（可复跑）

```bash
# ① 目录确未入库（两条均应为空输出）
git log --all --oneline -- docs/.handoff/
git log --all --oneline --diff-filter=A -- '*handoff*'
# ② ProGuard 收窄的逐条差异
git log -p -- app/proguard-rules.pro
# ③ Action 钉死值与升级判据
grep -n '# v' .github/workflows/build.yml .github/workflows/dependency-scan.yml
# ④ alpha 退出条件
grep -n 'alpha27\|compose-material3-alpha' gradle/libs.versions.toml
```

---

## 4. 如实声明（残余）

1. **原文措辞不可复原**：本文件**不是** `docs/.handoff/ISSUE-P3-09.md` 的复原稿；
   若上游 PR / 提交信息中另有该文档原本内容，本文件不作担保。
2. **本文件不新增承诺**：三组判据均以**现存文件内的就地注释**为准，本文件只做**指路**，
   避免同一判据在两处漂移（与 `docs/resolved/README.md`「索引只做引用、不复制正文」同口径）。
3. **依据若再漂移**：上述 4 处引用点由本文件承接后，**不得**再改回指向 `docs/.handoff/`
   ——该目录已确证从未入库。
