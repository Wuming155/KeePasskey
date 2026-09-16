# KeePasskey 独立安全复核报告（第二轮）

> **报告性质**：独立第三方安全审计复核。对 2026-09 第一轮安全审计（四份报告，共 89 项转入开放清单）的**全部结论**做独立重审。
> **审计基线**：`git rev-parse HEAD` = `a669a48`（2026-09-13）。工作区含未提交的 §39–§43 文档处置改动。
> **复核原则**：**不继承**原真伪判定、**不继承**原严重度、**不继承**原攻击前提。所有结论均以直读源码（`[V]`）、
> 可复跑测试（`[R]`）或设备侧实测（`[D]`，本轮未做）为证据基础。第一轮的计数（29/31/32/35）**一律视为无效**
> 并全程不引用（§2.5 `SYS-4`）。
> **本轮范围**：89 项开放项 + 24 项第一轮"已排除/已撤回/已整改/有意设计"结论（须反证）+ 逐条审查原"验收标准"（即修复方案）。

---

# 1. Executive Summary

## 1.1 规模与结论分布

| 项目 | 数量 |
|---|---:|
| 复核对象（开放项） | **89**（原优先级外观：P0=0 / P1=4 / P2=38 / P3=47） |
| 须反证的第一轮"已排除"结论 | **24** |
| 逐条审查的原修复方案（验收标准） | **89** |
| 本轮**新发现**（原四份报告均未登记） | **14** |
| 本轮**推翻**第一轮排除结论 | **0**（反证全部支持原结论；但纠出多处"结论对、理由错"） |
| 本轮推翻**我自己的**初判 | **4** |

**最终终态分布（89 项）**

| 终态 | 数量 |
|---|---:|
| `CONFIRMED VULNERABILITY` | **24** |
| `DESIGN WEAKNESS` | **19** |
| `HARDENING` | **24** |
| `POTENTIAL SECURITY ISSUE` | **7** |
| `FALSE POSITIVE` | **7** |
| `NOT REPRODUCIBLE` | **1** |
| `DUPLICATE` | **1** |
| `UNVERIFIED` | **2** |
| **合计** | **89**（其中 87 项在 §3 矩阵，`IPC-*`/`SUPPLY-*` 9 项在 §6.8） |

> **计数纪律声明（第二次审核后·强制）**：本节数字曾连续两轮手工失守。现**逐带给出成员清单**，
> **任何数字不得手工维护**（`SYS-4`）。
>
> - **`HIGH` = 1**：`NEW-FINDING-01`
> - **`MEDIUM` = 18**：`P1-24`、`P1-25`、`P2-46`、`P2-47`、`P2-48`、`P2-49`、`P2-52`、`P2-53`、`P2-54`、`P2-58`、
>   `P2-61`、`P2-70`、`P2-75`、`P2-77`、`P3-86`、`P3-117`、`P3-123`、**`P3-120`**（潜在 MEDIUM）
>   **（第三次审核更正：前版漏 `P1-24`/`P1-25`，两者在矩阵 §3.1 终评即为 MEDIUM。）**
> - **`CONFIRMED` 终态 = 24**：`P1-25`、`P2-43`、`P2-46`、`P2-47`、`P2-48`、`P2-49`、`P2-51`、`P2-52`、`P2-53`、
>   `P2-54`、`P2-58`、`P2-61`、`P2-75`、`P2-77`、`P2-78`、`P3-86`、`P3-88`、`P3-97`、`P3-100`、`P3-101`、
>   `P3-102`、`P3-105`、`P3-117`、`P3-123`（另 `SUPPLY-02`/`SUPPLY-06` 为 §6.8 独立裁定，**不并入本条**）
> - **`P2-72` = `POTENTIAL SECURITY ISSUE`**（矩阵与 §4.6 统一；**不在 `CONFIRMED` 清单内**）
>   —— 与矩阵 `POTENTIAL` 带 8 项的差额即源于此条的归属摇摆，现已统一。
>
> **`CONFIRMED` 与严重度是两个正交维度**：`CONFIRMED` 说"机制真实存在"，MEDIUM 说"严重度"，
> 故 `P3-86`（MEDIUM 且 CONFIRMED）、`P2-51`（LOW–MED 且 CONFIRMED）等不构成矛盾。

**重评严重度分布**

| 严重度 | 数量 | 说明 |
|---|---:|---|
| `HIGH` | **1** | `NEW-FINDING-01` —— **系本轮首次发现，且不在原审计的"已确认漏洞"清单内**。> 第二次审核后由 2 更正为 1，见 §15.2(i) |
| `MEDIUM` | **18** | 含 `P2-49`（第二次审核后由 LOW–MED 升入）与 `P1-24`/`P1-25`（**第三次审核补入**：矩阵 §3.1 终评即 MEDIUM，前版清单漏列） |
| `LOW` | **28** | |
| `INFO` | **45** | 含全部文档卫生、不可达代码、已整改项 |

## 1.2 必须向决策者直言的五件事

**① 重评后 `P1` 级只剩 0 项，`HIGH` 仅 1 项——而且这一项原审计不知道。**
原清单以 P1×4 / P2×38 的外观呈现。经独立重审：4 项 P1 **全部降级**（`P1-22`→LOW、`P1-23`→INFO/LOW、
`P1-24`→MEDIUM、`P1-25`→MEDIUM），而唯一 HIGH 是 `NEW-FINDING-01`。
> **（2026-09-13 第二次全量审核后更正）**：本报告初稿另立 `NEW-N1` 为第二项 HIGH，**该定级已撤销**——
> 逐项封顶（内存 ≤4 GiB 且 ≤50% 堆 · 迭代 ≤2²⁴ · 并行度 ≤64 · AES-KDF 轮数 ≤2²⁸）在派生**之前**生效，
> 残缺口只有 `I×M` 联合预算，即 `P2-49`。详见 **§15.2(a)**。`NEW-B01-2` 的 HIGH 亦撤销（**§15.2(b)**）。

**② 89 项里混装了六种不同性质的东西，"问题数量"是误导性指标。**
疑似真实漏洞、正确性缺陷、**验证缺口**、CI/供应链缺口、**文档不实**、**零调用点的不可达代码**。
P3 的 47 项中仅极少数具备"漏洞"资格。直接引用"89 个问题"会产生严重虚高。

**③ 第一轮最有价值的产出不是漏洞清单，而是它自己的方法学纪律——但那些纪律没被用在绝大多数条目上。**
`RESOLVED_LOG` §39.10/§40.8/§41.8/§42.8/§43.11 的 25 条纪律（"不得由 grep 片段推断控制流"、"同一根因只计一次"、
"引用前必须对拍 HEAD"）说明第一轮**已发现并纠正过自己的系统性错误**。但 89 项中 85 项的证据方式是**行号对拍**，
不是独立重放。**对拍只能证明"描述与代码相符"，不能证明"该行为是漏洞"。**

**④ 一份原始报告永久丢失，其结论永远无法被复核。**
`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` 是**未跟踪文件**，退役即销毁。三重确认：`git log --all -- <path>` 零命中、
全历史 `--diff-filter=A` 零命中、全盘检索无副本。它转登的 33 项只能依据现行描述复核，其"已撤回 3 项"**永久不可验证**。

**⑤ 第一轮有一处 10+ 条线索的"复核真空"，本轮补上其中 10 项，另 9 项经复核确属"重合归并"。**
`RESOLVED_LOG` §43.5 自述待复核区（`IPC-01…IPC-11`、`SUPPLY-01…SUPPLY-08`）"**未经首席审计员逐条复核**"，
而第一轮**只做了归并**（转成三条待办），**从未给出任何"成立/不成立"结论**。待复核区全域为 **19** 项，
本轮对其中 **10** 项给出逐条裁定（§6.8 九项：`IPC-01/02/05/10`、`SUPPLY-02/04/06/07/08`；
另 `SUPPLY-05` 由 §6.6 裁定）；其余 **9** 项（`IPC-03/04/06/07/08/09/11`、`SUPPLY-01/03`）**为重合归并**
（`IPC-03→P2-70`、`IPC-04→P3-88`、`IPC-06→P2-72`、`IPC-07→P2-51`、`IPC-11→P2-46`、`SUPPLY-01→P2-54`、
`SUPPLY-03→P2-55`、`IPC-08/09`→产品裁决 / 设计边界），其重合声明**已逐条核实**（7 项成立、2 项部分成立），
判定与证据见 §6.8.1 与
[`待复核区IPC与SUPPLY项重合声明核实.md`](待复核区IPC与SUPPLY项重合声明核实.md)。

## 1.3 最重要的产出不是"找到了什么"，而是"修复方案本身会破坏数据库"

按委托要求逐条审查原"验收标准"，发现 **23 条修复方案存在陷阱，其中 7 条若字面实施会造成数据损坏或安全回归**
（初稿写"8 条"，`P3-99` 一条经第二次审核撤销，见 §15.2(c)）：

| 条目 | 原验收标准字面实施 | 后果 |
|---|---|---|
| **`P3-99`** | 补 `newKeyFileData?.fill(0)` | **静默写坏库**（主密码 + 全零密钥文件）——该 clone 被 `rotateCredentials` 接管为**会话缓存**，未擦是**功能必需** |
| **`P3-105`** | 去掉"冗余" `.copyOf()` | 把**二进制池内部数组**交给调用方 → **保存写坏 `.kdbx`** |
| **`P2-77`** | 切换/新建库前通知锁观察者 | `FileBinaryStore.onSessionLocked()` 会删掉 `openStream` **刚为新库落盘的附件**（落盘 `:144-147` 早于替换 `:156`） |
| **`P2-48`** | AC② 取消逐引用物化 | 内存项 `load()` 返回**池内数组本身**，去掉 `copyOf()` 会**重现 `P3-07`**；且收益为零（拷贝只是移位） |
| **`P2-60`** | AC③"最后一次派生之后"清理 | 时机**不可静态确定**；清早了保存会**静默写出用错误密钥加密的库** |
| **`P2-79`** | AC② 接入 `KdfBenchmark` 建议值 | `MIN_ARGON2_MEMORY_BYTES = 8 MiB` **低于默认 64 MiB** → 直连反而**降低**强度 |
| **`P2-58`** | AC①"超限只按长度评分" | 把 `"1232…"×N` **误判为极强** |
| **`P1-22`** | "软件级 → 禁用封印" | 会**打断全部模拟器/CI 生物识别路径**（15 例设备侧用例全在 x86_64 模拟器采集） |

**`P3-99` 与 `P3-105` 的期望价值为负**：实施它们比不实施更危险。**这是第一轮"发现了问题但没验证解法"的直接代价。**

---

# 2. 复核方法与团队

## 2.1 流程

```
Phase 1  Inventory   → 读取全部 4 份原始报告（可经 git show 9b64415:… 取回）+ §39–§43 处置结论
                      建立 Finding Registry（89 项，不改写原始描述）
Phase 2  Plan        → 12 角色编制 / 9 批次分配 / 证据分级规则 / 反证清单 / 降级与升级挑战区
Phase 3  逐批复核    → 每组至少 3 个独立视角；Prosecution + Defense → Judge
Phase 4  Cross-Review→ 攻击链矩阵 / 重复合并 / Root Cause 定版 / 高估与低估清单
Phase 5  Final Board → 最终裁决 + Finding Matrix + 修复优先级 + 验证计划 + 放行裁决
```

## 2.2 团队编制（12 角色）

| # | 角色 | 承担 |
|---|---|---|
| 1 | Security Audit Lead | 流程管理、任务分配、冲突裁决、**P1 四条亲审**、交叉验证、最终裁决 |
| 2 | Android Security Engineer | B04 批次（19 项：组件 / Manifest / 平台语义） |
| 3 | Kotlin Security Engineer | B01 批次（18 项：秘密生命周期） |
| 4 | Rust Security Engineer | B03 批次 |
| 5 | FFI / JNI Security Engineer | B03 批次（JNI 边界） |
| 6 | Cryptography Engineer | B02 批次 + 全部密码学条目终局意见 |
| 7 | KeePass / KDBX Specialist | B02 / B06 批次（KDBX 格式与解析） |
| 8 | Mobile Red Team Engineer | B05 / B06 批次（可利用性判定） |
| 9 | Threat Modeling Expert | 全部条目的攻击前提与信任边界归属 |
| 10 | Secure Software Architect | Root Cause 归类、修复方案是否引入新问题 |
| 11 | **False Positive Reviewer** | **24 项"已排除"结论的反证** + 强制辩护方论点 |
| 12 | Final Review Board | 汇总、争议裁决、最终严重度、是否进入报告 |

**关键约束**：`False Positive Reviewer` **不得**同时担任同一条目的 Prosecution；标 `[I]`（grep 片段推断）的条目**不得**获得 `CONFIRMED`。

## 2.3 证据分级

| 级别 | 含义 | 可否支撑 `CONFIRMED` |
|---|---|---|
| `[V]` | 直读源码 + 读到函数结尾 + 确认全部调用点 | ✅（配合攻击前提成立） |
| `[R]` | 可复跑测试（`cargo test` / `gradlew test`） | ✅ |
| `[D]` | 设备侧 / 真机实测 | ✅ —— **本轮未使用（见 §2.4）** |
| `[I]` | grep 片段或文档推断（§39.10 纪律 2 禁作结论依据） | ❌ |
| `[U]` | 无法确认（须列明解除所需材料） | ❌ |

## 2.4 本轮证据限制（**必读**）

1. **未做设备侧实测**（委托方裁决）。故 `P2-42`、`P3-82`、`P2-74`、`P2-63`、`P2-73`、`P2-44`、
   `IPC-01`、`IPC-10` 及 `P3-120` 的下游结论**最高为 `UNVERIFIED` / `POTENTIAL`**，已逐条给出复现配方（§10.1）。
2. **一份原始报告永久丢失**（§1.2④），其 33 项转登只能依据现行描述复核。
3. **不引用第一轮任何计数**（§2.5 `SYS-4`）。
4. **复跑证据**：`cargo test --offline` → **43 passed / 0 failed**（含 IETF Argon2 KAT ×4、BC 冻结向量逐字节对照、
   Twofish KAT ×2、参数闸门负例）；`:crypto:testDebugUnitTest` → **35 passed / 0 skipped**
   （⇒ 原生库真实加载，JNI 用例未被静默跳过）。

## 2.5 对第一轮审计体系本身的评价（6 项缺陷）

| # | 缺陷 | 证据 | 本轮后果 |
|---|---|---|---|
| `SYS-1` | 未跟踪文档作为唯一证据载体，退役即永久丢失 | §41.7 自认 | 33 项不可复核 |
| `SYS-2` | **待复核区被"归并待办"替代复核** | §43.5 自认"未经逐条复核" | 10+ 条线索中，本轮对 **10** 项给出逐条结论；另 **9** 项经复核**确属重合归并**（去向见 §6.8.1），其声明已逐条核实 |
| `SYS-3` | **复核方式是"行号对拍"而非独立重放** | 各节"核实方式"字段全为"读取源码 / grep / 对拍" | 无法区分"描述相符"与"真是漏洞" |
| `SYS-4` | 计数不可用（29/31/32/35 并存） | §40.3、§43.2 口径注 | 引用即错 |
| `SYS-5` | 基线与 HEAD 混用；**记录路径多处漂移** | §41.2 曾误判"引文虚构"；本轮实测 ≥8 处路径漂移 | 可复现性下降 |
| `SYS-6` | **编号冲突** | `FP-04` 在 §41.6 与 §43.4 中含义不同 | 引用须带来源前缀 |

---

# 3. 完整 Finding Matrix（89 项）

> **列义**：`原` = 原优先级；`终评` = 本轮重评严重度；`终态` = 八类枚举之一；
> `可利` = 是否存在真实可利用路径；`修` = 建议修复优先级（P0/P1/P2/P3/—）；`证据` = 最高证据级别。
> **一切以本表为单一口径**；正文各节为详细论证。

## 3.1 P1 级（原 4 项）—— **全部降级**

| ID | 原 | 终评 | 终态 | 可利 | 修 | 证据 | 降级理由（要点） |
|---|---|---|---|---|---|---|---|
| `P1-22` | P1 | **LOW** | DESIGN WEAKNESS | 受限 | P2 | `[V]` | 读封印载荷需文件级访问；`allowBackup=false` + 全域排除使该前提**只能由 root/物理提取满足**，而对手 G 已能取得全部明文。**且封印建立路径不存在任何拒绝分支**（`SOFTWARE` 写日志、`UNKNOWN` 连日志都没有，**两者都放行**） |
| `P1-23` | P1 | **INFO–LOW** | DESIGN WEAKNESS | 受限 | P2（**告知项**） | `[V]` | 原记录**自认**"应用内签名自校验对该威胁无效"= 自证不可由应用内修复；威胁模型 §42.6(c) 已裁定对手 J「是」 |
| `P1-24` | P1 | **MEDIUM** | DESIGN WEAKNESS | 需用户交互 | P1 | `[V]` | 认证绑定（`CryptoObject`）**确实存在**，原记录"无显式授权机制"与代码不符；且 `EXTRA_CREDENTIAL_TITLE` **由本应用 Service 写入**，填充方**不能改写** |
| `P1-25` | P1 | **MEDIUM** | **CONFIRMED VULNERABILITY** | 是 | P1 | `[V]` | 机制完全确证；但"**任意**前台应用可读取"低估限制（Android 10+ 仅**有输入焦点**的应用 / 默认 IME / 特权应用可读） |

## 3.2 P2 级（原 38 项）

### 3.2.1 详情式（6 项）

| ID | 原 | 终评 | 终态 | 可利 | 修 | 证据 |
|---|---|---|---|---|---|---|
| `P2-42` | P2 | — | **UNVERIFIED** | — | — | `[V][U]` 需设备侧（3 项）；**AC③ 有前置：补的 instrumented 用例不会在 CI 跑**（= `P3-123③`） |
| `P2-43` | P2 | LOW | CONFIRMED VULNERABILITY | 是 | P2 | `[V]` |
| `P2-44` | P2 | LOW | POTENTIAL SECURITY ISSUE | 未确认 | P2 | `[V][U]` |
| `P2-45` | P2 | **LOW** | DESIGN WEAKNESS | 受限 | P2 | `[V]` **默认关闭系 2026-09-12 用户裁决**（有留痕），非缺陷；原 AC② 哨兵方案**原理上不可闭环** |
| `P2-46` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 需两次手势 | P2 | `[V]` 原 AC"未安装→弱候选"**仍会把凭据交给侧载应用** ⇒ 须选"不命中" |
| `P2-47` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 是 | P2 | `[V]` 原 AC 依赖的"S3 Versioning / ETag 单调性"**作为客户端可信信道不成立** |

### 3.2.2 表格式（32 项）

| ID | 原 | 终评 | 终态 | 可利 | 修 | 证据 |
|---|---|---|---|---|---|---|
| `P2-48` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 需口令 | P1 | `[V]` **AC② 不可实施**（会重现 `P3-07`） |
| `P2-49` | P2 | **MEDIUM** | CONFIRMED VULNERABILITY | **无需口令** | P1 | `[V][R]` |
| `P2-50` | P2 | LOW | HARDENING | 否 | P2 | `[V]` |
| `P2-51` | P2 | LOW–MED | CONFIRMED VULNERABILITY | 受限 | P1 | `[V]` |
| `P2-52` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 是 | P2 | `[V]` |
| `P2-53` | P2 | **MEDIUM** | CONFIRMED VULNERABILITY | 是 | **P1** | `[V]` **与 `P2-63` 互为前提**（见 §4.4、§7） |
| `P2-54` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 否 | P2 | `[V]` 原 HIGH/7.3 系把"闸门不自动触发"与"存在可利用依赖"**合并计分** |
| `P2-55` | P2 | LOW | DESIGN WEAKNESS | 否 | P2 | `[V][R]` `release.jks` **从未入库**（对象级验证）⇒ 链条断裂 |
| `P2-56` | P2 | INFO–LOW | DESIGN WEAKNESS | 否 | P3 | `[V][R]` 断言收窄：`initial_hash` **确已擦**，缺口**只剩 m_cost 工作内存** |
| `P2-57` | P2 | INFO–LOW | DESIGN WEAKNESS | 否 | P3 | `[V][R]` |
| `P2-58` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 是 | P2 | `[V][R]` `MAX_TEXT_CHARS = 8 shl 20` **不约束** Θ(n²) |
| `P2-59` | P2 | **INFO** | **HARDENING** | **不可达** | P3 | `[V][R]` **窄化不可达**：codec 50% 堆门槛**严于**引擎 60% ⇒ 引擎永远拿不到被拒值 |
| `P2-60` | P2 | INFO | DESIGN WEAKNESS | 否 | P3 | `[V]` **Fix 有数据丢失风险** |
| `P2-61` | P2 | **MEDIUM–HIGH** | **CONFIRMED VULNERABILITY** | 是 | **P1** | `[V]` |
| `P2-62` | P2 | INFO | HARDENING | 否 | P3 | `[V]` |
| `P2-63` | P2 | LOW | DESIGN WEAKNESS | 受限 | **P1（与 `P2-53` 同批）** | `[V]` |
| `P2-64` | P2 | INFO | DESIGN WEAKNESS | 否 | P3 | `[V]` |
| `P2-65` | P2 | LOW | CONFIRMED VULNERABILITY | 受限 | P1 | `[V]` **关键前提被静态否定**：全仓无锁态驱动的 UI 导航 |
| `P2-66` | P2 | **LOW–INFO** | **FALSE POSITIVE**（+残余边界） | 否 | P3 | `[V]` 子断言**误报**（`clearAll()` 已覆盖 `.tmp`）；余下 unlink-only 为已接受边界 |
| `P2-67` | P2 | LOW | DESIGN WEAKNESS | 受限 | P2 | `[V]` |
| `P2-68` | P2 | INFO | POTENTIAL SECURITY ISSUE | 否 | P3 | `[V]` **风险面被高估**：JVM 数组 `toString()` 只打印 `[B@hash`，**只泄露 `String` 字段** |
| `P2-69` | P2 | INFO | HARDENING | 否 | P3 | `[V]` |
| `P2-70` | P2 | MEDIUM | DESIGN WEAKNESS | 需交互 | P2 | `[V]` |
| `P2-71` | P2 | LOW | HARDENING | 否 | P3 | `[V]` |
| `P2-72` | P2 | LOW | POTENTIAL SECURITY ISSUE | 是 | P1 | `[V][U]` 归属错误确认，但需设备侧证 RP 侧影响 |
| `P2-73` | P2 | **INFO** | POTENTIAL SECURITY ISSUE | 未确认 | P3 | `[V][U]` **AC② 是负向变更，不可照做** |
| `P2-74` | P2 | LOW | POTENTIAL SECURITY ISSUE | 未确认 | P2 | `[V][U]` **影响被低估**：指纹读取**先于**浏览器判定 ⇒ Chrome 白名单**整体失效** |
| `P2-75` | P2 | MEDIUM | CONFIRMED VULNERABILITY | **无需口令** | **P0** | `[V]` **"唯一远端崩溃面"断言被推翻**（见 `P2-49`；但 `P2-75` 的"远端单方致崩溃"本身成立且**提升能力等级** ⇒ P0 维持） |
| `P2-76` | P2 | LOW | DESIGN WEAKNESS | 受限 | P1 | `[V]` |
| `P2-77` | P2 | MEDIUM | CONFIRMED VULNERABILITY | 受限 | P1 | `[V]` **原 AC 会引入数据损坏** |
| `P2-78` | P2 | LOW | CONFIRMED VULNERABILITY | 是 | P2 | `[V]` |
| `P2-79` | P2 | LOW | DESIGN WEAKNESS | 受限 | P2 | `[V][I]` **AC① 仓库已满足**；**AC② 含削弱陷阱** |

## 3.3 P3 级（原 47 项）

### 3.3.1 详情式（8 项）

| ID | 原 | 终评 | 终态 | 可利 | 修 | 证据 |
|---|---|---|---|---|---|---|
| `P3-76` | P3 | INFO | DESIGN WEAKNESS（**框架阻塞，接受成立**） | 是（框架层无解） | P3 | `[V]` |
| `P3-78` | P3 | INFO | HARDENING | 否 | P3 | `[V][R]` |
| `P3-79` | P3 | INFO | **NOT REPRODUCIBLE** | 否 | — | `[V]` 7 处菜单项**全部**为静态文案 / provider 名称，**无任何口令 / TOTP 明文** |
| `P3-80` | P3 | INFO | HARDENING | 否 | P3 | `[V]` |
| `P3-82` | P3 | INFO | HARDENING | 否 | P3 | `[V]` **用例须分别断言 D-1/D-2/D-3** |
| `P3-83` | P3 | INFO | POTENTIAL SECURITY ISSUE（root 边界内提高成本） | 否 | P3 | `[V]` |
| `P3-84` | P3 | LOW | HARDENING | 否 | P3 | `[V]` |
| `P3-85` | P3 | INFO | HARDENING | 否 | P3 | `[V]` **CI 白名单过窄会立即误红** |

### 3.3.2 表格式（39 项）

| ID | 原 | 终评 | 终态 | 修 | 证据 |
|---|---|---|---|---|---|
| `P3-86` | P3 | **MEDIUM** | CONFIRMED VULNERABILITY | P2 | `[V]` **唯一升格项** |
| `P3-87` | P3 | — | **DUPLICATE** → 并入 `P3-110` | — | `[V]` |
| `P3-88` | P3 | LOW | CONFIRMED VULNERABILITY（功能） | P2 | `[V]` 笔误有**两份副本**（`BrowserSigningFingerprints.kt:35` + `CallingOriginResolver.kt:42`） |
| `P3-89` | P3 | INFO | HARDENING | P3 | `[V]` 实测 **32 bit 熵**（8 hex 字符 × 低 4 位 × 4 byte） |
| `P3-90` | P3 | INFO | **FALSE POSITIVE**（不可达） | P3 | `[V]` 精确零引用；保留为防未来接入 |
| `P3-91` | P3 | INFO | **FALSE POSITIVE**（已在 HEAD 整改） | — | `[V]` 已用 `MessageDigest.isEqual`；仅测试调用者 |
| `P3-92` | P3 | INFO | HARDENING | P3 | `[V]` 安全影响为零；**AC 的 RFC 编号写错**（应为 RFC 7539） |
| `P3-93` | P3 | LOW | HARDENING | P2 | `[V]` |
| `P3-94` | P3 | INFO | HARDENING | P3 | `[V]` 需**先加 `xmlns:tools`**；与 `P2-74` 同文件须同批 |
| `P3-95` | P3 | LOW | HARDENING | P3 | `[V]` |
| `P3-96` | P3 | INFO | HARDENING | P3 | `[V]` **面收窄至 1 处**（`:99` 的 `chunk`） |
| `P3-97` | P3 | LOW | CONFIRMED VULNERABILITY | P2 | `[V][R]` **前提修正**：`rust-supply-chain` **确实**跑 `cargo test --locked`；真实缺口是 Gradle 侧 `:crypto:test` |
| `P3-98` | P3 | INFO | HARDENING | P3 | `[V]` 规则确为 no-op |
| `P3-99` | P3 | INFO | **FALSE POSITIVE** | — | `[V]` 未擦是**功能必需**；原 AC 会**写坏库** |
| `P3-100` | P3 | LOW | CONFIRMED VULNERABILITY | P3 | `[V]` |
| `P3-101` | P3 | LOW | CONFIRMED VULNERABILITY | P3 | `[V]` |
| `P3-102` | P3 | LOW–MED | CONFIRMED VULNERABILITY | P3 | `[V]` |
| `P3-103` | P3 | INFO | HARDENING | P3 | `[V]` 已由 `setHideOverlayWindows` 覆盖 |
| `P3-104` | P3 | INFO | HARDENING | P3 | `[V]` |
| `P3-105` | P3 | LOW | CONFIRMED VULNERABILITY | P3 | `[V][I]` **原 AC 有灾难性陷阱** |
| `P3-106` | P3 | INFO | HARDENING | P3 | `[V]` 暴露为零 |
| `P3-107` | P3 | LOW | DESIGN WEAKNESS | P3 | `[V]` |
| `P3-108` | P3 | INFO | **部分 FALSE POSITIVE** → HARDENING | P3 | `[V]` "附件明文经 `AtomicFileWriter`"**无据** |
| `P3-109` | P3 | INFO | DESIGN WEAKNESS | P3 | `[V]` |
| `P3-110` | P3 | LOW | HARDENING | P3 | `[V]` |
| `P3-111` | P3 | LOW | HARDENING | P3 | `[V]` **与 `IPC-02` 同一整改项**，顺序不可颠倒 |
| `P3-112` | P3 | INFO | HARDENING | P3 | `[V]` **非**死代码类 |
| `P3-113` | P3 | LOW | DESIGN WEAKNESS | P3 | `[V]` fail-closed 正确，缺可观测性 |
| `P3-114` | P3 | LOW | DESIGN WEAKNESS（**文档不实**） | P2 | `[V]` **决定性证据**：平台 `ClipDescription.java:156-159` javadoc 原文 "does not change clipboard behavior or add additional security" |
| `P3-115` | P3 | INFO | HARDENING | P3 | `[V][R]` **完整性风险不成立**：锁定哈希与 wrapper JAR 哈希**均与 Gradle 官方 9.7.1 逐字节一致** |
| `P3-116` | P3 | LOW | DESIGN WEAKNESS | P1 | `[V]` **已在 `docs/architecture/已知工程限界.md` §1.2 声明内** |
| `P3-117` | P3 | **MEDIUM** | CONFIRMED VULNERABILITY | P2 | `[V]` **升格** |
| `P3-118` | P3 | INFO | HARDENING | P3 | `[V]` |
| `P3-119` | P3 | LOW | DESIGN WEAKNESS | P3 | `[V]` |
| `P3-120` | P3 | MEDIUM（潜在） | **UNVERIFIED** | P1 | `[U]` **是多条门控的共同前提**（`P2-53`/`P2-63`/`P2-76`/`P1-23`） |
| `P3-121` | P3 | INFO | DESIGN WEAKNESS | P3 | `[V]` 原"与 README 矛盾"**过强** |
| `P3-122` | P3 | — | 见 §6.8（`IPC-01/02/05/10` 逐条已裁） | P2 | `[V]` |
| `P3-123` | P3 | **MEDIUM** | CONFIRMED VULNERABILITY | P2 | `[V]` **升格**（原 LOW；①②③ 成立） |
| `P3-124` | P3 | INFO | HARDENING（**作为漏洞 = 误报**） | P3 | `[V]` 可利用性误报级：scheme 硬编码、路径固定、结果不回流 |

---

# 4. Confirmed Vulnerabilities（17 项详细说明）

> 按**实际可利用性**排序，而非按终态标签。真正具备独立可利用性的不足 10 项。

## 4.1 `NEW-FINDING-01` ｜ **HIGH** ｜ P0 — 自动填充下发通道把口令送入 4 个泄漏出口

**这一项不在原审计的任何清单里。**

- `app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:155`：
  `val username = vaultRepository.resolveFieldReferences(entryIdHex, entry.userName) ?: entry.userName`
- `database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt:13` 字段码语义：`P` = Password；
  `:149` `RefField.PASSWORD -> entry.password?.readString()`（**口令明文**）。
- **解析结果流往 4 个泄漏出口**（`resolveFieldReferences` 共 **5 处**调用点，第 5 处见下）：

| 出口 | 位置 | 后果 | 需用户交互？ |
|---|---|---|---|
| ① 剪贴板 | `EntryDetailViewModel.kt:460-461` | = 已登记的 `P1-25` | 需要 |
| ①' 确认页 extra | `AutofillDatasetBuilders.kt:202` | 确认页把**口令明文**当"条目名"展示（与 `P1-24` 组合） | 需要 |
| ② 数据集菜单 / 对话框 | `:162` | 系统自动填充 UI 中**直接显示口令明文** | **不需要** |
| ③ IME 内联建议 | `:173` | 口令送入第三方输入法（与 `P2-71` 组合，该开关默认 true） | **不需要** |
| ④ **请求方控件填充** | `:180-183` | **口令被填入请求方应用的"用户名"输入框** | 需要 |

> **第 5 处调用点（第二次审核后补充，见 §15.2(d)）**：`AutofillDatasetBuilders.kt:158`
> `entry.password?.readString()?.let { raw -> vaultRepository.resolveFieldReferences(entryIdHex, raw) }`。
> 该结果被填入**真正的口令字段**（`:185-190` `passwordId`），属**意图行为**，
> **不是泄漏出口**，但**必须纳入白名单**以防回归。**故出口 = 4，调用点 = 5 —— 两个数字不可混用。**

**为什么出口④最严重**：**不需要**恶意应用读剪贴板、**不需要**攻击者应用获得输入焦点。
只要攻击者应用声明 username 型 `autofillHints`（极正常的声明）并使 `DomainMatcher` 产出该条目候选，
用户确认后**口令即落入攻击者控件**——用户以为在填用户名。

**根因（= `RC-09`）**：`FieldReferenceEngine` **已经实现了**正确的约束——`resolveForDisplay` 的 KDoc 明载
"受保护字段（取值面或检索面为 `P`）一律输出 `PROTECTED_PLACEHOLDER` 掩码，**任何递归深度都不物化受保护值**"。
**但取值模式（`resolveFieldReferences`）没有消费点面白名单**，导致 `P` 面可被解析进非口令消费点。
**一条根因、4 个泄漏出口、5 处调用点；修复成本极低。**

- **攻击前提**：条目 `UserName` 含 `{REF:P@…}`（KeePass 标准语法，本仓主动支持；需用户主动写入）+ 用户确认填充。
- **突破边界**：**是**，同时突破 `TB-8`（其他应用 → 自动填充服务）与 `TB-10`（应用 → 其他应用出口）。
- **可行性**：`Moderate`（出口② / ①' 连攻击者时机都不需要）。
- **终态**：`CONFIRMED VULNERABILITY` ｜ **修复优先级 P0**

**根本修复**：为 `resolveFieldReferences` 引入**消费点面白名单**——非口令消费点只允许 `T/U/A/N/I`，
遇 `wantField == P` 或 `searchField == P` 时复用既有 `PROTECTED_PLACEHOLDER` 掩码。
**必须同时覆盖 5 处调用点**：`AutofillDatasetBuilders:155`（username）、**`:158`（password）**、
`EntryDetailViewModel:450`（copyPassword）、`:460`（copyUsername）、`AutofillPickerViewModel:69`（选择器）。

## 4.2 ~~`NEW-N1`~~ ｜ **HIGH/P0 已撤销** ｜ 并轨 `P2-49`（**终评 MEDIUM / P1**，见 §15.2(a)(k)）

> **第二次全量审核更正**：本项原以"恶意 KDF 参数在无口令下驱动**无界** KDF"立为 HIGH。
> **"无界"不成立** —— `KdbxHeader.kt:325` 在解析 `KDF_PARAMETERS` 时就地调用
> `KdbxKdfParameterCodec.deserialize` → `validateArgon2Bounds`，在任何派生**之前**封顶：
> **内存 ≤ 4 GiB 且 ≤ 50% 堆（`:152/:171-174`）· 迭代 ≤ 2²⁴（`:157`）· 并行度 ≤ 64（`:160`）· AES-KDF 轮数 ≤ 2²⁸（`:178`）**。
> 故 **HIGH / P0 撤销，本项不作为独立条目**。

**仍然成立并保留（这才是它的真实价值）**：`database/src/main/java/com/keepasskey/database/file/KdbxFile.kt:135`
的 `deriveKeys(header, …)` **先于** `:147` 的 Header HMAC 校验 —— 即**派生不受认证保护**；
而 `KdbxKdfParameterCodec` **不校验 `I×M` 联合预算**。

**关于"最坏工作量"的关键更正（第二次审核后）**：本报告初稿称"`I = 2²⁴` 且 `M = 4 GiB` 能通过全部闸门，
最坏 ≈ 2²⁴ × 4 GiB"。**该表述错误。** 第二道闸门是**动态**的：
`KdbxKdfParameterCodec.kt:170-174` `heapCap = Runtime.getRuntime().maxMemory() / 2`，
而本应用 Manifest **无 `largeHeap`**（已核 `AndroidManifest.xml` 全文，命中 0）
⇒ Android 应用 `maxMemory()` 典型 256–512 MiB ⇒ **真机上 `M = 4 GiB` 在解析期即抛 `KdbxCorruptFileException`**。
**攻击者实际最多驱动 `M ≈ 堆/2 ≈ 128–256 MiB`**，我原估算**高估 16–32 倍**。

**修正后的真实缺口**：`M` 有动态上界，但 **`I` 只有静态上界 2²⁴，无动态上界**。
故无需口令可达的 KDF 工作量 ≈ **`I × M ≈ 2²⁴ × 128 MiB ≈ 2 × 10⁹` blocks**。

> **关于墙钟量级：本报告不再给未实测的估算（第三次审核指正）。**
> **我方先写"数十小时以上"、后改"小时级"，两次均为推算且未实测** ⇒ 本报告**不再给未实测的墙钟数值**（见下）。
> **解析复核**：正常解锁基线为 `M = 64 MiB`、`t ≈ 10–16`、`p = 2`，折合**每线程约 3.3 × 10⁵ blocks**；
> 攻击 Header（`M = 256 MiB`、`I = 2²⁴`）折合**每线程约 2.2 × 10¹² blocks** ⇒ 比值约 **6.7 × 10⁶（10⁶–10⁷ 量级）**。
> **（第三次审核更正）** 我方前两次给出"3.3 × 10⁴"与"1.4 × 10⁹"，**均错一档**——已按上式重算。
> **结论：定级不依赖该数值**（§15.2(k) 的定级依据是前提与能力等级，不是燃烧时长）；
> **"准持久 DoS"的措辞强度须以设备侧实测替换** —— 见 §10.1 新增的实测项 `M-1`。

- 入口可达性仍成立：`app/src/main/java/com/keepasskey/app/sync/SyncDatabaseCodec.kt:55`
  `KdbxFile.load(ByteArrayInputStream(bytes), pwdClone, keyClone)` —— **未传 `binaryStore`**（= `P2-67`）。
- **链条在"是否需要口令"处分叉**：**无需口令** = `P2-49`；**需口令（低优先）** = `P2-48`、`P2-67`。

**本轮最强组合链（措辞更正）**：`P2-67` + `P2-48` + `P2-49` → 攻击者控制同步后端，**上传单个 `.kdbx`**，
即可让 **KDF 长时间卡死（不可取消）** 与 **XML 引用放大 OOM** 交替触发 → **准持久 DoS，且攻击者无需知道主密码**。

## 4.3 `P2-61` ｜ **MEDIUM–HIGH** ｜ P1 — TOTP 种子写入恒 `isProtected = false`

- `[V]` `VaultEntryMapper.kt:321` 与 `VaultEntryWriteCoordinator.kt:69`：
  `ProtectedString(trimmedTotp, isProtected = false)`；对照口令字段为 `isProtected = true`（`:313` / `:57`）。
- **影响**：TOTP 种子**不做内层流 XOR**、XML **不写 `Protected="True"`**，且被投影进 `UiCustomField.value` 明文。
  → KDBX 文件静态可读 → **第二因素可被离线克隆**。
- **突破边界**：`TB-3`（Repository → KDBX 编解码）+ `TB-6`（附件 → 磁盘，同类落盘明文语义）。
- **修复必须保留兼容性负例**：外部工具写入的**明文** `otp` 仍须可读。

## 4.4 `P2-53` + `P2-63` + `P2-76` ｜ **MEDIUM** / LOW ｜ P1 — CM 通道双缺口（**互为前提**）

- `[V]` `app/src/main/java/com/keepasskey/app/passkey/` 下 `RuntimeIntegrityGate` **零命中**；
  全仓门控消费点仅 `MainApplication` / `KeePasskeyAutofillService` / `BiometricAuthManager`。
- `[V]` CM / Passkey 通道 `BiometricPrompt` **未绑 `CryptoObject`**（自动填充通道**已绑**且可行）。
- **⚠️ 排期硬约束（本轮最重要结论之一）**：`P2-53` 与 `P2-63` **互为前提**——
  若 `P2-53` 的收口点取 `currentEnforcement()`（**非 suspend，最省事的接法**），
  修复会被 `P2-63`（非 suspend 路径不感知启动后注入）**完全抵消**。**必须同批修。**

## 4.5 `P2-77` + `P2-65` + `P3-116` + `P3-117` ｜ **MEDIUM** / LOW ｜ P1 — 清理只覆盖 `lock()` 主路径

- `[V]` `database/src/main/java/com/keepasskey/database/session/SessionOpener.kt:89` / `:156` 直接替换
  `core.database.value`，该类 `clearSensitiveData` / `SessionLockObserver` **零命中**。
- `[V]` `app/src/main/java/com/keepasskey/app/ui/screens/vault/AppTerminationPolicy.kt:29-32` 仅
  `detachTask() + exitProcess(0)`，不触发清理。
- `[V]` `UnlockViewModel.passwordChars` 全程持有**未提交**主密码（`P3-117`）。
- **各环节无外部前提，链路完全连通** → 换库 / 退出 / 锁库后旧库树、明文附件缓存、明文 StateFlow、未提交主密码**可同时滞留**。
- **`P2-77` 的原 AC 会引入数据损坏**：`FileBinaryStore.onSessionLocked()` 会删掉 `openStream` **刚为新库落盘的附件**。

## 4.6 其余**已裁定**条目（简述）

> **标题更正（第二次审核）**：本节原题"其余确认漏洞"不准确 —— 表中 `P2-72` 在矩阵中为 `POTENTIAL SECURITY ISSUE`。
> 现按矩阵口径逐条标注终态。`SUPPLY-02` / `SUPPLY-06` 属 §6.8 独立裁定，不并入本节。

| ID | 终评 | 要点 |
|---|---|---|
| `P1-25` | MEDIUM | `copyUsername` → `copyPlainText`（不设 `EXTRA_IS_SENSITIVE` + `cancelScheduledClear()`）。**机制确证，但原记录两处论证不准确**（见 §5.4） |
| `P2-43` | LOW | `autofillCopyTotp` 默认开启 → TOTP 动态码默认入剪贴板 |
| `P2-46` | MEDIUM | `android://` 维度无调用方签名指纹绑定；**需用户两次手势** |
| `P2-47` | MEDIUM | 同步 / 封印凭据回滚防护不足；**原 AC 依赖的信道不可信** |
| `P2-48` | MEDIUM | 附件引用放大；`MAX_XML_ELEMENTS` 只封元素数，**不封同一池条目被引用 N 次的副本乘法** |
| `P2-49` | **MEDIUM** | KDF 逐项封顶（**内存 ≤4 GiB 且 ≤ 堆/2 · 迭代 ≤2²⁴ · 并行度 ≤64 · AES-KDF 轮数 ≤2²⁸**，见 §15.2(a) 更正）但**无 `I×M` 联合预算**；`M` 有动态上界而 **`I` 只有静态上界** ⇒ 无需口令可达；**定级 P1（未提升能力等级）** |
| `P2-51` | LOW–MED | 剪贴板不随锁定 / 熄屏清理（`RC-05`）+ 后台空读时**误清他处内容** |
| `P2-52` | MEDIUM | 选择器缓存**活** `KdbxEntry`，锁定后就地清零 → 抛 `IllegalStateException` |
| `P2-58` | MEDIUM | 口令强度 Θ(n²)（`MAX_TEXT_CHARS = 8 shl 20` **不构成上界**，可达 ~7×10¹³ 次迭代）+ **两个生产调用方在主线程** |
| `P2-72` | **POTENTIAL** | Assertion 侧 `callingPackage ?: packageName` 归属错误（Create 侧正确）；**非** `CONFIRMED`（需设备侧证 RP 侧影响） |
| `P2-75` | MEDIUM | `body?.bytes()/string()` 无上限 + `findNodes` **递归**；**逃逸口是 `SyncCycleRunner.kt:383-387` 只捕 `Exception`** |
| `P2-78` | LOW | CM 保存路径写畸形 URL（`https://https://…`）→ 条目永久失配 |
| `P3-86` | **MEDIUM** | 明文导出（XML / CSV）字节缓冲写盘后未清零（**唯一升格项**） |
| `P3-97` | LOW | Gradle 侧 `:crypto:test` 从不运行；符号表核对未实现 |
| `P3-100` / `P3-101` / `P3-102` / `P3-105` | LOW | 各处秘密缓冲未清零（同族 `RC-02`） |
| `P3-117` | MEDIUM | `clearPasswordOnLeave` 为**死开关** + 未提交主密码长期驻留 |
| `P3-123` | MEDIUM | CI / 供应链 4 项（依赖校验元数据缺失、Daemon JVM 未锁、CI 不跑 instrumented、`mapping.txt` 可见性未知） |

---

# 5. False Positives（7 项——第一轮报告错了，逐条说明**为什么错**）

> 本节只列**本轮推翻**的原记录。**不**列第一轮自己已撤回的项（那些它已自行纠正）。

## 5.1 `P2-66` 的子断言 —— 真实误报

**原记录**：「`clearAll()` **不清** `<name>.<uuid>.tmp`（仅 `clear(remotePath)` 走 `deleteOrphanTmpFiles`）」

**为什么错**：把两条方法的清理语义混淆了。
- `sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:394-396`：`getFile(...) = File(cacheDir, "$key$suffix")`
  → **所有缓存文件（含 `.tmp`）都是 `cacheDir` 的直接子项**。
- `clearAll()`（`:320-329`）遍历 `cacheDir.listFiles()` 的**全部直接子项**并 `delete()` / `deleteRecursively()`
  （仅跳过 `isRollbackStateFileName`）→ **`.tmp` 必然被一并删除**。
- `deleteOrphanTmpFiles` 的必要性在于 `clear(remotePath)` 是**单路径**清理（只删固定后缀清单，须前缀通配补漏）；
  **`clearAll()` 是全目录清理，不需要它**。

**保留部分**：该条另一半（"落盘清理为 unlink-only，已 unlink 扇区在 TRIM 前可恢复"）**成立**，应作为**已接受边界**登记。

## 5.2 `P3-90` —— 作为"漏洞"不成立

**原记录**：公开死函数 `OtpEngine.parseOtpAuthUri` 对 `period`/`digits` 无钳制。
**为什么错**：本轮精确检索 `OtpEngine.parseOtpAuthUri` **命中 0**（含测试与 `::` 引用）。
`core/src/main/java/com/keepasskey/core/otp/TotpKeyUriParser.kt:112` 调用的是**它自己的 `private` 字节态重载**（`:139`），
**不是** `OtpEngine` 的那个。→ **无攻击者**（不可达）。按 §40.8 纪律 3「无攻击者的确定性缺陷不属漏洞分类体系」，
应**移出漏洞口径**。**保留为 `HARDENING`**（删除或改 `internal`，防未来误接入）。

## 5.3 `P3-91` —— 已在 HEAD 整改，原记录为基线陈述

**实测**：`database/src/main/java/com/keepasskey/database/file/HmacBlockStream.kt:129` / `:137` **已用 `MessageDigest.isEqual`**；
KDoc `:96-97` 明载「F-16：原 `contentEquals` … **已随本路径一并替换**」。调用点**仅测试**
（`KdbxCompatibilityAndSecurityTest.kt:556`、`KdbxFileTest.kt:70,89`）。→ 就当前 HEAD 而言 **`FALSE POSITIVE`**。

## 5.4 `P3-99` —— 原记录把"功能必需"当成缺陷

**原记录**：`changeCredentials` 的默认参数 clone 未清零。
**为什么错**：`newKeyFileData` 默认 clone 被 `rotateCredentials` **接管为会话缓存**，未擦是**功能必需**。
**原 AC 字面实施会静默写坏库**（主密码 + 全零密钥文件）⇒ **期望价值为负**。

## 5.5 `IPC-05` —— 第一轮从未复核，本轮证否

**原记录**（列入待复核区）：`CallingOriginResolver` 白名单 JSON 偏离官方 schema。
**为什么错**：`javap -c` 直读 `credentials-1.6.0`：`CallingAppInfo.getOrigin(String)` →
`extractPrivilegedApps$credentials(JSONObject)` **只读** `apps`/`type`/`info`/`signatures`/`build`/`userdebug`/
`cert_fingerprint_sha256`/`package_name`，**从不读 `relation`/`target`**。本仓 JSON 层级与字段**完全对应**。

## 5.6 `P3-108` 的子断言 —— 无据

**原记录**："附件明文也经 `AtomicFileWriter`"的临时文件路径。
**为什么错**：`AtomicFileWriter` 唯一生产链是 `SessionOpener` 的**库文件（密文）**；
附件明文走 `FileBinaryStore` → `cacheDir/attachments`。

## 5.7 `P3-124` —— 作为漏洞是误报

`DigitalAssetLinksVerifier` 自建 `OkHttpClient` 属实，但**可利用性为零**：
scheme **硬编码 `https://`**（不可能是明文）、路径固定、结果**不回流**、
且触发者本身已有 `INTERNET` 权限**可自行访问**。→ 纯纵深防御，`HARDENING`。

## 5.8 附：原记录的**论证错误**（结论不变，但论证须更正）

| 条目 | 原记录的错误论证 |
|---|---|
| `P1-24` | "`EXTRA_CREDENTIAL_TITLE` **由填充方传入**（受攻击者控制）"——**错**：由本应用 `AutofillService`（`AutofillDatasetBuilders.kt:202`）写入；填充方能影响的是"**哪个条目被匹配**" |
| `P1-24` | "**不存在**显式授权机制"——**错**：`AutofillConfirmActivity.kt:88-107` 的 Keystore `CryptoObject` 绑定确实存在，且"认证成功但未携带绑定 Cipher"时**不放行**（`:104-105`） |
| `P1-24` | AC"**应用名**不可伪造"——**错**：`label`/`icon` 由应用自声明；不可伪造锚点只有**包名 + 签名证书 SHA-256** |
| `P1-25` | "**任意**前台应用可读取"——**错**：Android 10+ 仅**有输入焦点**的应用 / 默认 IME / 特权应用可读 |
| `P1-25` | "**取消**自动擦除计划 / 无时间窗约束"——**错**：`copyPlainText` 取消的是**上一次**的计划，本次复制**从未被调度擦除** |
| `P3-88` | 测试复用同一 65 字符值致**假绿**——**对**，但只有**一份**副本的说法不完整：实际有**两份** |
| `FP-06` | "release 生成组件为**测试专用**"——**与事实相反**（见 §6.1） |
| `§43.7` 强项 9 | "导出组件面仅 `MainActivity` + 两个 `BIND_*` 服务"——**不完整**：合并清单还含 `androidx.work` 注入的 `exported="true"` receiver（`DiagnosticsReceiver`） |

---

# 6. 第一轮"已排除"结论的反证结果（24 项）

> **结论：未推翻任何一项排除，但纠出多处"结论对、理由错"。** 这些错误若不纠正，会成为下一轮误判的种子。

## 6.1 附录 B 误报排除（`RESOLVED_LOG` §43.4）

| 编号 | 第一轮理由 | 本轮反证 |
|---|---|---|
| `FP-01` / `FP-02` / `FP-04` | `OtpEngine.parseOtpAuthUri` 零调用点 | ✅ 精确检索**命中 0**（含测试与 `::`）→ **维持** |
| `FP-03` | `offset = hash[19] & 0x0F ∈ [0,15]` → `offset+3 ≤ 18 < 20` | ✅ 实测 `OtpEngine.kt:75` `hash[hash.size-1] and 0x0F`；最小 hash 20B → `offset+3 ≤ 18` → **维持** |
| `FP-05` | 内层流"每值重置"——规范要求**不**重置 | ⚠️ **原文永久丢失，不可复核**（见 §6.5） |
| `FP-06` | release 生成组件证明为**测试专用** | ❌ **理由与事实相反**：release 生成组件**就是生产组件**（真实现引用 5–6 处，`Fake\|Stub\|NoOp\|TestOnly` **0** 命中，`@TestInstallIn`/`@UninstallModules` 全仓 **0** 命中，`src/main` 无 `Fake*` 类）。→ **结论成立，理由须重写** |
| RUST 侧 1 | 有符号 `jint` 前置判定成立 | ✅ 逐一枚举 **12 个窄化点全部受保护**（`lib.rs:194-198` 仅在 `#[cfg(test)]`）→ **维持** |
| RUST 侧 2 | "**4 个**导出函数全 `catch_unwind`" | ⚠️ **计数错**：`Java_com_keepasskey*` 导出**实测恰 5 个**，**5/5 全包裹**，`Cargo.toml` 未设 `panic="abort"`。→ **结论成立，计数应为 5**（`ISSUE-P3-97` 正确） |
| RUST 侧 3 | 属文档化契约 | ✅ 枚举 9 个调用点全部符合；`[R]` 35 passed / 0 skipped → **维持** |
| `CWE-22` | 附件缓存 key 为随机 UUID | ✅ `FileBinaryStore.kt:92` `UUID.randomUUID()` → **维持** |

## 6.2 §39.2 已撤回（2 项）— 全部维持

- `AP-02`：`WebDomainAttribution` 枚举**只有 3 值**（**无 `UNVERIFIED`**），`attribute()` 读到函数结尾 `return REJECTED`，
  调用侧 `when` 穷尽映射 `null` → **维持撤回**。
- `AP-05`：**本机 API 36.1 平台源码 `Intent.java:11814-11833`** —— `newb = new Bundle(other.mExtras); newb.putAll(mExtras)`
  ⇒ **base 覆盖 fillIn** 成立，且该函数**无 `FILL_IN_EXTRAS` 处理**；追加 picker `exported="false"`、
  `EXTRA_CALLING_PACKAGE` 仅用于写字段屏蔽表 ⇒ 影响上界为骚扰原语 → **维持撤回**。

## 6.3 §39.3 已证否（2 项）— 结论维持，**`AP-24` 归因错置须更正**

- `AP-24`（XXE）：**结论维持 `FALSE POSITIVE`，但第一轮归因错置。**
  "特性探针已存在 ⇒ 有保障"是**弱论证**：探针只能区分「抛异常 vs 不抛」，
  **无法区分「已生效」与「已接受但静默无效」** ⇒ 特性层在部分平台可能是**看起来已加固的死代码**。
  且"`resolveEntity` 必然被调用"**过强**——存在三条**未记录的前置依赖**：
  **D-1** `parser.parse(InputStream, DefaultHandler)` 走 `ParserAdapter` 且 handler 实现 `EntityResolver` 才被注册；
  **D-2** 平台 SAX 层不短路外部实体；**D-3** `setProperty(lexical-handler)` 成功（否则 `startDTD` 兜底静默失效，仅记 WARNING）。
  → **XXE 仍证否，是因为拦截来自 handler 层两道 fail-closed（与特性支持无关），不是因为特性探针。**
  **⚠️ 附加发现：内部实体炸弹（billion laughs）路径的防御纵深弱于外部实体路径。**
- `AP-04`：**维持**；补出内联 attribution 是**单一** `FLAG_IMMUTABLE`（无 `UPDATE_CURRENT`）；
  且 **`FLAG_MUTABLE` 类别未被证否**（picker 与 `CredentialPendingIntents.kt:44` 是官方契约要求、不可移除）。

## 6.4 §41.3 撤回 / 下调（4 项）

| 条目 | 本轮 |
|---|---|
| `H-new-3` | ✅ **撤回正确，确为误报**；**但根因说明不完整**：代码库**同时存在两种拼写且都正确**（属性 `requireRiskNotice` vs 访问器 `requiresRiskNotice()`）——**只搜任一种都会"零命中"，不是单纯漏字**。消费链：`RuntimeIntegrityPolicy.kt:53` → `:122` → `SecuritySettingsScreen.kt:91` → `:249` 渲染 `IntegrityRiskCard` |
| `S1` | ✅ 与 `P2-55` 一致（见 §7 `CHAIN-E`） |
| `H1 附注` | ✅ 撤回正确 |
| `H2` 寿命定级 | ⚠️ 部分可复核（`extractKey` 的 `String` 为函数局部量，驻留上界为**下次 GC**） |

## 6.5 不可复核声明

`FP-05` 与 §41.3 其余部分所依据的 `SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` **原文永久丢失**
⇒ **永久 `UNVERIFIED`**。这是流程缺陷（`SYS-1`），不是技术判断。

## 6.6 §42.4 "有意设计"（6 项）

| 项 | 本轮 |
|---|---|
| `T-5` 浏览器白名单过窄 | ✅ 有意取舍；但**实际后果比原陈述严重**（见 `P2-74`：指纹读取先于浏览器判定 ⇒ Chrome 白名单**整体失效**） |
| `Q-17` 后半 `WebDavAuthHeader` Base64 `String` | ⚠️ KDoc 已声明限界；**但"已声明"不等于"已评估"**——无决策留痕 |
| `SUPPLY-05` 强度评估回退 | ✅ 有意设计，有 parity 合同断言 |
| `T-15` 树外引用者 | ❌ **不完整**：`P3-119` 只列 `lastSyncedDb` **一个**，**漏了三处**（见 `NEW-B01-4`） |
| 交付物 4.3 进程 = 信任域 | ✅ 已文档化取舍；与 `TA-1` 一致 |
| 4.5 主密码 = 唯一凭据 | ⚠️ 部分已登记，其余为设计边界 |

## 6.7 §42.5 关闭项

`Q-8` / `C-6`（`rememberSaveable` / `SavedStateHandle` 是否承载敏感字符）：✅ 关闭成立。

## 6.8 **`IPC-*` / `SUPPLY-*` 逐条结论（第一轮从未做过）**

| 项 | 结论 | 关键证据 |
|---|---|---|
| `IPC-01` | **成立（部分）** → `DESIGN WEAKNESS` / LOW | 4 个 `PendingIntent` 的 requestCode **全为常量**（`REQUEST_CODE_UNLOCK=2001`、`CONFIRM_BASE=2100+index`、`PICKER=2200`、`INLINE_ATTRIBUTION=2002`），3/4 带 `FLAG_UPDATE_CURRENT`，`index` **每响应从 0 重排**，包内**无任何单调分配器**（`AtomicInteger` 检索 exit 1）。**"凭据值串扰"不成立**（值由 `dsBuilder.setField(...)` 携带、框架写入）。成立的是「**TOTP 错配 + 30 秒授权串扰**（需 `P3-42`，默认关）+ 确认页文案」。**CM 通道同构**：`CredentialResponseAssembler.kt:66` 的 `RequestCodeAllocator` 是**每响应局部变量**，而 `:233` 注释**自认**会导致"点中第 1 条实际拉起第 3 条" |
| `IPC-02` | **不成立（作为漏洞）** → `HARDENING` / INFO | 身份因子确从 `intent.getStringExtra` 取，**但全部 5 个落地 Activity 均 `exported="false"`**（`AndroidManifest.xml:36-73`）⇒ 第三方无法伪造 extras。**建议修正 `PasskeyAssertionActivity.kt:112` 表述**：`PendingIntent` 不可伪造 ≠ **extras** 不可伪造 |
| `IPC-05` | **`FALSE POSITIVE`** | 见 §5.5 |
| `IPC-10` | **成立** → `HARDENING` / LOW | `onFillRequest` 有 `withTimeoutOrNull(4_000L)`；**`onSaveRequest` 无任何超时**且签名**不提供 `CancellationSignal`**。后果有界（`awaitEnforcement` 1s 兜底 + DAL 3s + `finally` 清零仍执行）⇒ 仅 Availability |
| `SUPPLY-02` | **成立** → `CONFIRMED VULNERABILITY` / MEDIUM | `git ls-files gradle/` 仅 4 文件；`git check-ignore` exit 1 ⇒ **真缺失**；全仓 `dependencyLocking` / `lockAllConfigurations` / `verification-metadata` **零命中**。已有链条：Gradle 分发 ✅ / wrapper JAR ✅（仅 fast-gate）/ Rust ✅ / **Java 依赖 ❌** |
| `SUPPLY-04` | **成立，且补出第二个出口** | `dalVerifier.verify` 有 **2 个**生产调用点：`PasskeyCreateActivity.kt:133` **与 `AutofillOriginResolver.kt:45`（自动填充通道）**——后者**从未被任何记录提及**；且自动填充侧**无 skip 开关**（Passkey 侧有）⇒ **隐私控制不对称** |
| `SUPPLY-06` | **部分成立** → `CONFIRMED VULNERABILITY` / LOW | SSRF 守卫缺失**成立**；**"TLS-only 缺失"不成立**（URL 字面量 `"https://$host/..."`，scheme 硬编码 ⇒ 不可能明文）。**可外泄通道为零**（响应仅喂 `DalStatementMatcher`，输出三值枚举） |
| `SUPPLY-07` | **成立，且原记录低估** | 三 workflow 中 7 个串（`connectedAndroidTest` / `androidTest` / `managedDevice` / `emulator` / `reactivecircus` / `avd` 等）命中数**全为 0**。用例实数 **28**（app 12 / database 6 / sync 3 / crypto 7），**非原记录的 15** |
| `SUPPLY-08` | **成立（部分）** | `gradle-daemon-jvm.properties` **全文仅 2 行**（`toolchainVersion=21`），无 vendor / URL / 哈希；而 `gradle-wrapper.properties:7` **已锁** `distributionSha256Sum` ⇒ 标题**字面过宽**，实为 **JDK / Daemon JVM 未锁** |

### 6.8.1 复核工作目录独有编号的逐条处置（`ISSUE-P3-141` 收口）

> 本节 6.8 逐条裁定的是 `IPC-*` / `SUPPLY-*` 中的 **9 项**（`IPC-01/02/05/10`、`SUPPLY-02/04/06/07/08`），
> 另 `SUPPLY-05` 由 §6.6 裁定；待复核区全域为 **19 项**（`IPC-01…IPC-11`、`SUPPLY-01…SUPPLY-08`，
> 见 §1）。其余的**处置归属**（`IPC-03/04/06/07/11`、`SUPPLY-01/03` 的重合归并，`IPC-08/09` 的产品裁决）
> 与**未逐条验证**这一事实，见
> [`第四轮复核遗留编号处置.md`](第四轮复核遗留编号处置.md) 第 2.2 节。
>
> **（2026-09-16 补，`ISSUE-P3-145` AC②③）**该 9 项的**重合声明已逐条核实**：**7 项成立、2 项部分成立**
> （`IPC-06` 的 RP 侧影响仍待真机、`IPC-11` 的 CM 通道 `UNBOUND` 残余），另得 **2 处独立残余**
> （CM 通道 `UNBOUND` 越权面未进 `已知工程限界.md` 登记表；`P2-54` 的 AC② 分支保护必需检查无活动落点）。
> 判定、`文件:行号` 与 commit 证据见
> [`待复核区IPC与SUPPLY项重合声明核实.md`](待复核区IPC与SUPPLY项重合声明核实.md)。
>
> 此外，**第四轮复核工作目录（`.audit-recheck/`，`.gitignore` 排除）另有编号从未进入本报告**：
> 对这批编号的逐条四选一结论（已由某条目覆盖 / 已在本批修复 / 确属新缺陷 / 前提不成立）、可核对证据、
> 以及「工作目录结论分流与退役判定」，同见上述文件（第 3、6 节）。
>
> **两点必须连同结论一起读**：
> 1. 本报告 §11 对其中两条使用了**别名**（工作目录的 `NEW-B09-01` 记为 `NEW-B09-x`、`NEW-B04-06`
>    记为 `NEW-B04-x`）⇒ 按**编号**对拍会把已收录项误判为「未进入报告」，结论必须以**主题**复核为准。
> 2. 该文件同时更正两处前提：`NEW-B02-3` **已在本报告 §11 的 INFO 级清单中**（故
>    `ACTIVE_ISSUES.md` 的「从未进入报告」表述过宽）；`NEW-B01-6` 的解除依据须由「有意设计」
>    补为「**零消费点** + 有意设计」——因为 `KdbxHeader.kt:39` 确实比较 `kdfParameters`，
>    原复核者标 `[U]` 观望的那条链路**客观成立**，仅因无消费点而不可利用。
>
> ⇒ 本报告与上述文件合起来，才使「复核未遗留未登记缺陷」从**断言**变成**可核对**。

---

# 7. Attack Chains（攻击链矩阵）

| 链 | 组成 | 连通性 | 最终影响 | 前提 |
|---|---|---|---|---|
| **`NEW-CHAIN-1`** | `P2-49` + `P2-67` + `P2-48`（原含 `NEW-N1`，已并轨 `P2-49`） | ✅ **完全连通**（但**单前提 = 后端写权限**，且该对手本已有零成本 DoS ⇒ 见 §15.2(k)） | **准持久 DoS**：攻击者控制同步后端，**上传单个 `.kdbx`** → 无需口令驱动 KDF（工作量比基线高约 6.7 × 10⁶ 倍，**墙钟待实测** `§10.1 M-1`）与 XML 引用放大 OOM 交替 | **无需主密码**；需后端写权限 |
| **`CHAIN-D`** | `P2-75` + `P2-49` + `P2-59` | ✅ 连通 | 远端单方致进程崩溃 / OOM | 无需口令（`P2-49` 部分）；`P2-75` 需口令 |
| **`CHAIN-B`** | `P2-77` + `P2-65` + `P3-116` + `P3-117` + `P2-67` + `P3-119` | ✅ **完全连通** | 换库 / 退出 / 锁库后旧库树、明文附件缓存、明文 StateFlow、未提交主密码**同时滞留** | **各环节均无外部前提** |
| **`NEW-CHAIN-2`**（`AC-09`） | `P2-53` + `P2-63` + `P2-76` + `P3-120` | ⚠️ **条件连通** | 已 root 设备上 CM 通道**完整性裁决 + 用户验证两层同时可绕** | 取决于 `P3-120` 实测命中率；**`P2-53` / `P2-63` 互为前提** |
| **`NEW-CHAIN-3`**（`AC-11`） | `IPC-01` + `P3-42` + `P2-43` | ⚠️ 条件连通 | 跨应用 **30 秒免二次确认授权穿透** + TOTP 错配 | 需 `P3-42` 开关（默认关） |
| **`NEW-CHAIN-4`**（`AC-12`） | `SUPPLY-02` + `SUPPLY-08` + `SUPPLY-07` + `P1-23` | ✅ 连通 | **构建到分发的全链信任缺失** | 四环修复**必须同批** |
| `CHAIN-F` | `P2-70` + `P2-46` + `P1-24` | ⚠️ **部分连通，断裂点明确** | 恶意应用取得候选并诱导确认 | **断裂点①**：框架渲染候选 UI **不可代选** + 生物识别 / 手动确认 ⇒ **社会工程链，非静默窃取**。**断裂点②**：原审计把 `P3-88` / `P2-74` / `P3-93` 串成"助力攻击"是**错误归因**——这三者方向是 fail-closed **掐断** web 域路径 |
| `CHAIN-A` | `NEW-FINDING-01` + `P1-25` + `P2-43` + `P3-84` + `P2-51` | ⚠️ 部分连通 | 口令泄露 | 因 `NEW-FINDING-01` 的存在，**剪贴板环节已非必需** |
| **`CHAIN-E`** | `P2-55` + `P3-115` + `P2-54` + `P3-123` + `P1-23` | ❌ **已断裂** | — | **断点一**：`release.jks` **从未入库**（214 revision 全对象层零命中）⇒ 仅凭公开口令**无法重建签名**。**断点二**：`P3-115` 有官方哈希锁定，SHA-256 第二原像不可行 |

**可成立的短链**（`P2-54` + `P3-123①`）：`投毒传递依赖 → 未被发现 → 未被校验 → 任意代码执行`，
整体 **MEDIUM**（**前提是上游投毒，当前无证据**）。

**为什么单独看每条会被低估**：
- `NEW-CHAIN-1`：`P2-48` 单看是 OOM、`P2-75` 单看是崩溃、`P2-49` 单看是慢——
  **合起来是"远端单方面、可重复、无需用户交互、无需口令"的持久可用性打击**。
- `CHAIN-B`：每条都是"某处没清理"，**合起来是"锁库这一对外承诺在多个并行路径上同时不成立"**。

---

# 8. Root Causes（应从架构层解决的问题）

## 8.1 Root Cause Matrix

```
RC-01  秘密以不可擦 String 表示（Kotlin 边界）
  P2-62 密钥文件整文件转 String · P3-102 全量 SHA-1 String
  P3-100 WebDav 凭据 String 生命周期 · P1-25 的 String 物化面
  RUST-04 from_utf8_lossy 物化 String · 已接受残余：Compose 主密码
  计价 MEDIUM

RC-02  明文 / 密钥缓冲缺统一清零收口点（"补一处、漏一处"）
  P2-56 / 57 / 60 · P3-86 / 96 / 99 / 100 / 101 / 105 / 118 / 119
  ★ 实证：CbcStreams.kt 同一文件内 :84 :88 :89 已清零而 :99 漏清
    ⇒ 缺失的不是"意识"而是"收口点"
  计价 MEDIUM

R-CLEAR-1  Rust 侧"声明已擦除"与"实际擦除点"不一致
  ★ 真实证据（第二次审核后替换）：同一 Rust 文件内擦除点不齐 —— `aes_kdf.rs:72` 的 `buf` 是
    `Zeroizing`（该文件已合规），但 Cargo.toml 对 argon2 crate 的 zeroize 能力宣称过宽
    （`P2-56` 已核实：argon2 0.6.0 的 `Blocks` Drop 只 dealloc，m_cost 工作内存不擦）
  ★ 原列 `NEW-B01-2`（"aes_kdf.rs:82-88 连 Zeroizing 都没有"）**已撤销**，见 §15.2(b)

R-CLEAR-2  ★★ 共享引用 → 就地清零会误伤（B01 的主导结构性障碍）
  P2-60 · P3-99 · P3-104 · P3-105 · P3-119
  ★ 这解释了为什么"清零"类条目反复无法简单修复——所有权语义从未定义

R-CLEAR-3  明文缓冲无清零点
  NEW-B01-3 Pkcs7.pad 第三份明文 · NEW-B02-3 HmacBlockOutputStream.flushBlock 的 chunk

RC-03  调用方身份 / 签名绑定不足
  P2-46 · P3-93 · P2-70 · P1-24 · P2-72 · P3-120 / P2-63 / P1-23
  计价 MEDIUM–HIGH

RC-04  凭据通道的密码学保证不对称（自动填充 vs Credential Manager）
  P2-76 · P2-53 · P1-22 · P3-111 · P2-73
  ★ 自动填充通道已做对的绑定，CM 通道未做 ⇒ 可修而未修
  计价 HIGH

RC-05  敏感状态清理仅覆盖 lock() 主路径
  P2-65 · P2-77 · P3-116 · P3-109 · P2-51 · P2-67 · NEW-B01-4
  计价 HIGH

RC-06  解析 / 派生输入边界与预算缺失
  P2-48 · P2-49 · P2-59 · P2-75 · P3-78 · P2-79
  ★ P2-49：派生（KdbxFile.kt:135）先于 Header HMAC（:147），且 codec 无 I×M 联合预算
    ⇒ 无需口令可达，但 M 受「堆/2」动态闸门约束（§15.2(k)）
  计价 HIGH

RC-07  验证 / CI 有效性缺口（漏报的成因）
  P2-42 · P3-82 · P2-69 · P3-88 · P2-54 · P3-97 · P3-123 · P3-120 · B03-N1
  ★ 两个"测试假绿"实例：P3-88（测试复用 65 字符值）+ B03-N1（35 例 JNI 用例静默 skip）
  计价 MEDIUM

RC-08  安全承诺与实现 / 文档漂移
  P2-56 · P3-114 · P3-92 · P3-89 · P3-98 · P3-117 · NEW-B02-2 · §43.7 强项 9
  ★ 最强实例：P3-114 —— 平台 ClipDescription.java:156-159 javadoc 明文说明该标记不提供安全语义
  计价 LOW–MEDIUM

RC-09  ★★ 字段引用的解析面未按消费点收敛
  NEW-FINDING-01 · P1-25 · NEW-FINDING-02 · 与 P2-71 组合
  ★ FieldReferenceEngine 已实现 resolveForDisplay 掩码约束，取值模式却没接上
  计价 HIGH
```

## 8.2 按修复收益排序

| 排序 | Root Cause | 承载症状 | 为何应从架构层解决 | 修复成本 |
|---|---:|---:|---|---|
| **1** | **`RC-09`** | 3（含 1 HIGH） | `resolveForDisplay` **已实现**所需约束，只需在复制 / 下发通道接上白名单 → **一次消除 5 个出口** | **极低** |
| **2** | **`R-CLEAR-2`** | 5 | 清零类条目反复失败的**共同原因**：所有权语义未定义。必须先定义，否则逐个打补丁必然再漏 | 中（需设计） |
| **3** | **`RC-06`** | 7（含 1 HIGH） | 应在**入口统一预算**，而非每个解析器内逐项打补丁 | 中 |
| **4** | **`RC-05`** | 7 | 逐个组件注册观察者会持续遗漏；应引入**统一生命周期事件**（锁定 / 换库 / 退出三事件） | 中 |
| **5** | **`RC-04`** | 5 | 自动填充通道已证明可行；应抽**统一凭据下发门控**供两通道共用 | 中 |
| 6 | `RC-02` | 11 | 应建立**统一的"敏感缓冲"类型**，而非散落的 `Arrays.fill` | 中高 |
| 7 | `RC-03` | 6 | 应统一**调用方身份解析**（遍历全部签名者 + 系统背书来源） | 中 |
| 8 | `RC-01` | 5 | 部分不可消除；可在边界收敛（`resolveFieldReferences` 返回字节而非 `String`） | 中高 |
| 9 | `RC-08` | 8 | 应把"文案 ↔ 实现一致性"纳入 CI | 低 |
| 10 | `RC-07` | 9 | **是漏报成因**：应把设备侧用例纳入 CI（`P3-123③`） | 中（有成本约束） |

---

# 9. Remediation Priority

## 9.1 P0 — 必须立即修复

| # | 条目 | 修复要点 |
|---|---|---|
| **P0-1** | **`NEW-FINDING-01`** | 为 `resolveFieldReferences` 引入**消费点面白名单**；非口令消费点只允许 `T/U/A/N/I`；遇 `P` 面复用 `PROTECTED_PLACEHOLDER` 掩码。**必须同批覆盖 5 处调用点**（第二次审核后由 4 更正为 5，见 §15.2(d)）：`AutofillDatasetBuilders.kt:155`（username）、**`:158`（password）**、`EntryDetailViewModel.kt:450`（copyPassword）、`:460`（copyUsername）、`AutofillPickerViewModel.kt:69`（选择器）。**为何是 P0**：它**新增了机密性能力**（口令落入攻击者控件 → 不可逆、无法自救） |
| **P0-2** | `P2-75` | **必须同时**改捕获面 + 迭代遍历 + 入口封顶，**且必须改 `SyncCycleRunner.kt:383-387`**（否则只改 `WebDavSyncProvider` 无效）。**为何是 P0**：它把"同步失败"提升为**进程崩溃**（`Error` 逃逸 `catch(Exception)`），同样是**能力等级提升** |

> **`P2-49` 已按第二次审核由 P0 降为 P1**（见 §15.2(k)）。理由：其攻击前提坍缩为"**拥有同步后端写权限**"这一**单一**前提，
> 而该对手**本来**就能零成本拒绝服务（拒答 / 返回垃圾 / 返回无界大响应 = `P2-75`）⇒ **`P2-49` 未提升对手能力等级**。
> 同一前提前提下 `P2-47` 为 MEDIUM/P2、`P2-75` 为 P0（因提升为崩溃）——`P2-49` 与 `P2-47` 同级。

## 9.2 P1 — 发布前修复

| # | 条目 | 修复要点 |
|---|---|---|
| P1-1 | `P2-49` | **`I×M` 联合预算**（`I` 需动态上界；`M` 已有 `堆/2` 动态闸门）。**定级 P1，与 `P2-47` 对齐**（同前提 = 后端写权限；未提升对手能力等级 —— 见 §15.2(k)）。原独立条目 `NEW-N1` 已并轨于此。**实施可与 P0 同批**（成本约一行），但那是工程安排，不是定级依据 |
| P1-2 | `RC-05` 七项（`P2-65` / `P2-77` / `P3-116` / `P3-117` / `P3-109` / `P2-51` / `NEW-B01-4`） | 引入统一"锁定 / 换库 / 退出"三事件并全部接线；**`P2-77` 须先通知旧库再落盘新库** |
| P1-3 | `P2-61` | 三处写入改 `isProtected = true`；**保留兼容性负例**（外部工具的明文 `otp` 仍须可读） |
| P1-4 | `P2-53` + **`P2-63`（必须同批）** + `P2-76` | CM 通道统一收口完整性裁决 + 复用 `prepareAutofillAuthCipher()` 做 `CryptoObject` 绑定。**若 `P2-53` 取 `currentEnforcement()`，修复会被 `P2-63` 完全抵消** |
| P1-5 | `P2-72` | Assertion 侧改取系统认证的 `CallingAppInfo` |
| P1-6 | `P2-48` | 在**读取侧**加累计预算；**不得**取消逐引用物化（会重现 `P3-07`） |
| P1-7 | `P3-120` | 真机以 Frida 三种形态实测命中率并写入设备侧基线；**它是 `P2-53` / `P2-63` / `P2-76` / `P1-23` 的共同前提** |
| P1-8 | `P1-22` | `UNKNOWN` 与 `SOFTWARE` 一律按 fail-closed；UI 常驻标注；**改为"显式确认 + 常驻声明"而非无条件禁用**（否则打断全部模拟器 / CI 生物识别路径） |

## 9.3 P2 — 建议修复

`P2-43` `P2-44` `P2-46` `P2-47` `P2-50` `P2-52` `P2-54` `P2-55` `P2-56` `P2-57` `P2-58`(含线性惩罚) `P2-67` `P2-70`
`P2-74` `P2-78` `P2-79` `P3-86`(明文导出缓冲) `P3-93` `P3-97` `P3-114` `P3-117` `P3-122` `P3-123` `P1-23`(改为产品告知项)

## 9.4 P3 — 安全加固 / 文档卫生

`P3-76` `P3-78` `P3-80` `P3-82` `P3-83` `P3-84` `P3-85` `P3-88`(含修正测试假绿) `P3-89` `P3-90` `P3-92` `P3-94`
`P3-95` `P3-96` `P3-98` `P3-100` ~ `P3-110` `P3-112` `P3-113` `P3-115` `P3-118` `P3-119` `P3-121` `P3-124` + `RC-02` 十一项

## 9.5 已确认**无需修复**

| 条目 | 理由 |
|---|---|
| `P3-91` | 已在 HEAD 整改（`MessageDigest.isEqual`），仅测试调用者 |
| `P2-66`（`.tmp` 子断言） | 误报（`clearAll()` 已覆盖） |
| `P3-99` | 误报；未擦是功能必需，**原 AC 会写坏库** |
| `P3-90` | 不可达 → 仅保留为防未来接入的 `HARDENING` |
| `P3-79` | 威胁前提不成立（菜单项全为静态文案） |
| `IPC-05` | 误报（JSON schema 完全对应） |
| `P3-124`（作为漏洞） | 可利用性为零 |

## 9.6 **Fix 陷阱清单（23 条——实施任何修复前必读）**

> **这是本轮最重要的交付物之一**：原审计给出了修复方案，但**未验证方案本身的安全性**。

| # | 条目 | 陷阱 | 正确做法 |
|---|---|---|---|
| 1 | `P3-99` | ~~补 `fill(0)` → **写坏库**~~ **【第二次审核后撤销】** `SessionCredentialCache.kt:29-30/:41/:44` 为**独立克隆**，补 `fill(0)` **安全**；`P3-99` 回归**真缺陷**（冗余 clone 泄漏，`RC-02` 族） | 补 `fill(0)`（安全） |
| 2 | `P3-105` | 去掉 `.copyOf()` → **写坏 `.kdbx`** | 保留拷贝 |
| 3 | `P2-77` | 通知锁观察者 → 删掉新库刚落盘的附件 | 先通知旧库、再落盘新库 |
| 4 | `P2-48` | AC② 取消物化 → 重现 `P3-07` | 读取侧加预算 |
| 5 | `P2-60` | AC③ 时机不可静态确定 → **静默写出错误密钥的库** | 需显式生命周期契约 |
| 6 | `P2-79` | AC② 接入建议值 → `8 MiB < 64 MiB` **降低**强度 | 取 `max(建议值, 现默认)` |
| 7 | `P2-58` | AC① 只按长度 → 长数字串**误判极强** | 补**线性惩罚** |
| 8 | `P1-22` | 无条件禁用封印 → 打断模拟器 / CI 生物识别路径 | 显式确认 + 常驻声明 |
| 9 | `P2-43` | "强制不可关闭短擦除" → `armScheduledClear` 分支顺序（`:85` 先于 `:87`）会产出**假加固** | 调整分支顺序 |
| 10 | `P2-73` AC② | 改 `FLAG_MUTABLE` → **为对齐文档而降低安全性** | **不可照做**（本仓这两条路径不消费 fillIn extras） |
| 11 | `P2-73` | 与 `IPC-01` **互斥**（一方成立则另一方无影响） | 须**同批实测** |
| 12 | `P2-74` AC② | "改用 `CallingAppInfo`" → 传统 autofill 通道**没有**该字段 | 需另寻方案 |
| 13 | `IPC-02` / `P3-111` | 顺序颠倒 → 交叉核对**恒失败**，把"能填充"变"不能填充" | 先保证 `retrieve*` 非 null |
| 14 | `P2-46` AC | "未安装 → 弱候选" → **仍把凭据交给侧载应用** | 须选"不命中" |
| 15 | `P2-47` AC | 依赖 S3 ETag 单调性 → **信道不可信** | 需本地单调记录 |
| 16 | `P1-24` AC | "应用名不可伪造" → **错**（label / icon 自声明） | 锚点只有包名 + 签名证书 SHA-256。**但官方 `AutofillService.java:335-373` 明确要求 / 允许展示"请求方⇄域"归属并检查签名证书 ⇒ AC 平台可实现** |
| 17 | `P3-94` | 缺 `xmlns:tools`；与 `P2-74` 的 `<queries>` **同文件同区域** | 同批修改 |
| 18 | `P3-85`② | CI 白名单过窄 → **立即误红**（含库注入的 `exported=true` 组件，如 `DiagnosticsReceiver`） | 需豁免清单 |
| 19 | `P2-60` | `KdbxHeader.copy()` 浅拷贝 `ByteArray` **共享引用** | 需深拷贝或所有权约定 |
| 20 | `P3-82` | "合法口令 KDBX + 注入 DTD"需重新加密 | 推荐**直接喂 XML 给 `KdbxXmlParser`** 绕过外层 |
| 21 | `SUPPLY-07` | 接入 CI：Linux runner **无 KVM**；`AssumptionViolatedException` 会被 AGP 记为 `<failure>` | 改 `macos-latest`（计费 ~10×） |
| 22 | `P2-56` | 改 `hash_password_into_with_memory` 手管内存 | **必须保留 `Error::OutOfMemory` 优雅失败**（否则超大 m_cost 会 abort，**比现状更坏**） |
| 23 | `P2-66` | 若按原记录"补 `deleteOrphanTmpFiles` 到 `clearAll`" | 属**冗余**（已覆盖），但无害；应改为登记 unlink-only 边界 |

---

# 10. Verification Plan

> 对每个 Confirmed Vulnerability 给出**可复跑的证明方式**。项目 `AGENTS.md` §3.8 互操作证据纪律：
> `.kdbx` 产物的互操作性**必须以官方实现端到端对拍为准**（`keepassxc-cli` / `pykeepass` / KeePass 2.61.1 C#），
> `tools/kdbx-corpus/generate_corpus.py --verify` **仅证明外层文件头自洽，不构成互操作证据**。

| ID | Unit Test | Integration / Security Test | Dynamic / Device | Regression |
|---|---|---|---|---|
| `NEW-FINDING-01` | 构造 `UserName = "{REF:P@A:target}"` 的条目：断言 `buildDatasets` 产出的 username 字段**不含**被引用口令；断言 `EXTRA_CREDENTIAL_TITLE` **不含**口令；断言 `copyUsername` 后剪贴板**不含**口令 | 断言 `dsBuilder.setField(usernameId, …)` 的值 ≠ 被引用口令 | 触发自动填充会话，观察数据集菜单是否显示口令明文；确认后检查请求方控件收到值 | `{REF:U@…}` / `{REF:T@…}` 行为不变；外部工具的明文 `otp` 仍可读 |
| `P2-49` | 构造"自洽 Header + `I = 2²⁴`、`M` 接近 `堆/2` 的字节流"，断言 KDF **在无口令情况下**即被 `I×M` 联合预算拒绝 | MockWebServer 返回该字节流，断言同步周期**不进入长时 KDF** | — | 正常库同步不受影响；**不得**误拒合法参数域（附官方对照） |
| `P2-61` | 断言"保存 → 重载后 `otp` 字段 `isProtected == true` 且 XML 含 `Protected="True"`" | 与 KeePass 2.61.1 / KeePassXC 端到端对拍 | — | 外部工具的明文 `otp` 仍须可读 |
| `P2-77` / `P3-116` / `P2-65` | 断言"换库 / 退出 / 锁库后旧库树已擦除、`cacheDir/sync` 与 `cacheDir/attachments` 已驱逐、明文 StateFlow 已清空" | 断言退出路径触发清理（或断言已如实提示） | `run-as` 核对目录；`adb logcat -b crash` 期望 `ProtectedString 已经清零` | **不得**回归 `lock()` 既有语义；`P2-77` 须断言新库附件未被误删 |
| `P2-53` / `P2-63` / `P2-76` | 断言"风险态下 CM 与自动填充两通道**均**拒绝下发"；断言"无 `CryptoObject` 时验证不通过"；**断言快照建立后出现 hook 信号 → 生物快速解锁被拒** | — | 真机 Frida 下验证门控实际生效（依赖 `P3-120`） | CM 响应预算不受影响 |
| `P2-72` | 断言"RP 收到真实调用方包名" | 抓 RP 侧 `clientDataJSON` 核对 `androidPackageName` | 真机以 Chrome 触发通行密钥请求 | Create 侧现有正确实现保持不变 |
| `P2-75` | 负例：超大响应与超深 XML 均被拒绝且不 OOM / 不崩溃 | MockWebServer 构造超深 PROPFIND；**断言错误在 `SyncCycleRunner` 被捕获** | — | 正常尺寸同步不受影响 |
| `P2-49` / `P2-79` | 附官方参数域对照，**防误拒合法库** | `cargo test` + 官方客户端对拍 | — | 既有库可解锁性与官方客户端互操作不得破坏 |
| `P2-52` | 断言锁定后选择器**不抛** `IllegalStateException` | — | 填充 → 选择器 → 手动锁库 → 点选 → `adb logcat -b crash -d` | **不得**削弱 `ProtectedString.clear()` |
| `NEW-B01-2` | 断言 `aes_kdf.rs` 中间缓冲全路径擦除（或如实改写 `Cargo.toml` 宣称） | `cargo test` | — | 算法行为与 KAT 不变 |
| `NEW-B01-4` | 断言同步解析产物在三处路径均调用 `clearSensitiveData()` | — | — | 不得破坏同步合并语义 |

## 10.1 本轮无法完成、需设备侧 / 外部证据的项

| 项 | 所需材料 |
|---|---|
| `P2-42`（3 项） | `dumpsys window \| grep FLAG_SECURE`；导入 >1 MiB 附件 → `am force-stop` → 冷启动 → `run-as ls cache/attachments`；确认 `DialogWindowProvider` 取到 |
| `P2-44` | TalkBack + `uiautomator dump` 检查 `isPassword` 语义 |
| `P2-74` | Chrome 打开库内条目域登录页 → `adb logcat -s AutofillOrigin`，看是否"读取签名证书失败"且无候选 |
| `P2-73` | 确认页认证后字段是否被填入 → `adb logcat -s AutofillManager` |
| `P3-82`（D-1 / D-2 / D-3） | 分别断言三条前置依赖 |
| `P3-120` | 真机 Frida 三形态（默认 / 改名 / 内存加载）命中率 |
| `IPC-01` | 分屏两应用触发填充 → 插桩打印收到的 extras（对照 §43.6 `C-3`） |
| `IPC-10` | AOSP `AutofillManagerService.java` 保存回调是否有兜底超时 |
| `AP-43` | `adb shell bmgr backupnow com.keepasskey.app` + `dumpsys backup` |
| `P3-88` | `apksigner verify --print-certs` 取 Chrome 真实 64-hex 指纹比对 |
| `SUPPLY-06` | `DomainMatcher.extractDomain` 是否允许 IP 字面量（本轮未直读） |
| `P2-54` AC② | `gh api repos/{owner}/{repo}/branches/main/protection` |
| `P3-123④` | `gh api repos/:owner/:repo --jq .visibility`（决定 `mapping.txt` 的 LOW / INFO） |
| **`M-1`（新增）** | **`P2-49` 的墙钟量级实测**：构造合法 Header（`I = 2²⁴`、`M` 取「堆/2」）在设备上实测一次 KDF 耗时，用实测值替换 §4.2 的推算，避免第三次修正。**判据**：记录 API / ABI / 机型 / 实测秒数 |
| **`M-2`（新增，第三次审核精化题面）** | **两条路径的闸门数不同，须分路径实测**：<br>• **native 路径（真机默认）**：只有 codec 一道闸（`KdbxKdfParameterCodec.kt:171-174` 的「堆/2」），`M = 堆/2` **直达 Rust 内核**——`Argon2KdfEngine.kt:52-64` 的 native 分支**没有第二道闸**；<br>• **JVM 兜底路径**：codec 之后还有 `transformJvm`（`:73`）的 `0.6 × maxHeap`（`:130-132`）**二次拦截**。<br>**判据**：分别记录两条路径下 `M = 堆/2` 是否被接受、以及实际到达内核的 `M` 值。<br>**注**：我方原题面写"两处阈值哪一处先触发"**不准确**——二者不在同一路径上，按原题面测会误判。 |

---

# 11. 本轮新发现汇总（14 项）

| ID | 级别 | 内容 | 归入 |
|---|---|---|---|
| **`NEW-FINDING-01`** | **HIGH** | 自动填充下发通道把 `{REF:P@…}` 口令送入 **4 个泄漏出口**（含请求方控件）；**5 处调用点须同批修复** | `RC-09` |
| ~~`NEW-N1`~~ | ~~HIGH~~ | **已撤销** → 并轨 `P2-49`（`I×M` 联合预算，无需口令可达） | `RC-06` |
| ~~`NEW-B01-2`~~ | ~~HIGH~~ | **已撤销**（第二次审核）：`aes_kdf.rs:72` 的 `buf` **就是** `Zeroizing`；`:82-88` 是 SHA-256 终结与返回值构造，**KDF 输出不可能在返回前清零**。见 §15.2(b) | — |
| `NEW-B01-4` | MEDIUM | `SyncConflictController` / `SyncContentChangeDetector` / `loadAndApplyRemoteBytes` **从不 `clearSensitiveData()`**（`P3-119` 只列一个引用者，漏三处） | `RC-05` |
| `B07-N1` | LOW | `build.yml:165-178` 用**一次性 CI 密钥**签出 release 形态 APK 并上传 artifact（发布身份混淆） | `RC-08` |
| `B03-N1` | LOW | `crypto/build.gradle.kts:113 isIgnoreExitValue = true` 使"构建失败"与"cargo 缺失"不可区分 → **35 例 JNI 用例静默 skip 而构建保持绿色** | `RC-07` |
| `NEW-N2` | LOW | 选择器在**零匹配**时也无条件挂入 ⇒ 严格匹配设计对任意应用失效 | `RC-03` |
| `NEW-N3` | LOW | `P3-88` 的 65-hex 笔误**存在两份副本**（`BrowserSigningFingerprints.kt:35` + `CallingOriginResolver.kt:42`） | `RC-07` |
| `NEW-N4` | LOW | `PasskeyCreateActivity.kt:108-109` 的 `?: callingPackage` 回退可产生 `android://android` 绑定 | `RC-03` |
| `NEW-N5` | LOW | 会话授权宽限开启后 `domain=""` ⇒ 30 秒内该应用**任意**表单免确认（默认关闭） | `NEW-CHAIN-3` |
| `NEW-B09-x` | LOW | `DigitalAssetLinksVerifier.endpointOverride` 是 `@Singleton` 上的 `@Volatile internal var` ⇒ **生产可重定向出口** | `SUPPLY-06` |
| `NEW-B04-x` | LOW | `copyPlainText` 的**无条件** `cancelScheduledClear()` 是 `P1-25` 的**共用根因**，且 `VaultListActionController.kt:72` **独立触发** | `RC-09` |
| `NEW-N6` | INFO | `AtomicFileWriter` 固定名 tmp 与 `SyncCache` 的 UUID 唯一名不一致（当前由 mutex 保护） | `RC-02` |
| `NEW-N7` | INFO | `SyncRollbackGuard.State.sequence` **已持久化但不参与裁决**（"看似已修"陷阱） | `P2-47` |

**另有 INFO 级文档 / 计数纠正 6 项**：`NEW-B02-2`（KDBX minor 不校验 + `VERSION_4_1` 死常量 + UI 谎称 4.1）、
`NEW-B02-4`（`K` / `A` 无长度界）、`NEW-B01-3`（`Pkcs7.pad` 第三份明文）、`NEW-B02-3`（`flushBlock` 的 `chunk`）、
`B03-N2`~`N4`（`Cargo.toml` 宣称过宽、`§43.4` 计数 4→5、`FP-06` 理由纠正）、`B07-N2`（`dependency-scan.yml:16` 称空白名单实为 7 条）。

---

# 12. 报告自身的更正留痕（我在本轮被推翻 4 次）

> 保留此节是为了**可追溯性**：本报告的初稿曾给出下列判定，经复核对反证后更正。

| # | 我的初判 | 更正为 | 依据 |
|---|---|---|---|
| 1 | `P2-59` `CONFIRMED VULNERABILITY`（静默窄化） | `HARDENING` | `KdbxKdfParameterCodec` 的 50% 堆门槛**严于** `Argon2KdfEngine` 的 60% ⇒ 原生路径永远拿不到 JVM 会拒的值；`M/1024 ≤ 4 194 304 ≪ Int.MAX` |
| 2 | `P2-45` `CONFIRMED VULNERABILITY` + `MEDIUM` | `DESIGN WEAKNESS` + `LOW` | 默认关闭系 **2026-09-12 用户裁决**（有留痕）；原 AC② 哨兵方案**原理上不可闭环** |
| 3 | `P1-22`"异常路径**反而可能放行**" | **没有任何路径会拒绝** | `SOFTWARE` 写 debug 日志、`UNKNOWN` 连日志都没有，**两者都放行** |
| 4 | `P2-75`"`Error` 不被捕获 → 进程崩溃" | 归因错误：`runCatching` **捕到了**，是在 `SyncCycleRunner.kt:383-387` **脱网** | 修复必须同时改该处捕获面 |

**另有两项论证层面的更正**（结论不变）：`P1-24` 的 AC **平台可实现**（官方 `AutofillService.java:335-373`）；
`P1-25` 的"任意前台应用可读取"应更正为"仅**有输入焦点**的应用 / 默认 IME / 特权应用"。

**复核对亦自我更正 2 次**（`NEW-B04-02` 与 `IPC-05` 的 userdebug 猜测），理由均为"依据条目描述而非直读实现"——
正是 `SYS-3` / §39.10 纪律 2 的同类错误。**双方互有纠正且都留痕，这是本轮方法论有效的证据。**

---

# 13. Overall Security Assessment

## 13.1 当前安全状态

**核心密码学边界完好，外围治理层存在系统性缺口。**

**完好（本轮独立确认）**：
- KDBX4 认证先于解密（头 SHA-256 常时比较 → 头 HMAC → 块 HMAC → 终止块）；
- 全部密钥材料比较使用 `MessageDigest.isEqual`；
- Rust FFI 的 **5 个导出函数逐一确认**包在 `catch_unwind` 内，`Cargo.toml` 未设 `panic="abort"`；
- `unsafe` 仅 5 处同布局位重解释；**`Zeroizing` 全路径成立**（`aes_kdf.rs:72` 的 `buf` 即 `Zeroizing`）
  —— **本项无例外**（原写的"除 `aes_kdf.rs`"对应的 `NEW-B01-2` 已撤销，见 §15.2(b)）；
- 备份面封堵（`allowBackup="false"` + `data_extraction_rules` 两域各自排除）；
- 导出组件面最小（无 `ContentProvider` / `FileProvider` / 深链；**但合并清单含 `androidx.work` 注入的 `exported=true` receiver**）；
- 签名私钥从未入库（**对象级验证**，214 revision 全对象层零命中）；
- 恶意 KDBX 无法通过头部认证、无法构造可通过块 HMAC 的载荷、**无法借解析器实现任意代码执行**（本架构最强结论）。

**系统性缺口**：`RC-04`（凭据通道不对称）、`RC-05`（清理仅覆盖 `lock()`）、`RC-06`（解析预算缺失）、`RC-09`（字段引用解析面未收敛）。

## 13.2 最大风险（按实际可利用性）

1. **`NEW-FINDING-01`（HIGH）** —— 一条根因、**4 个泄漏出口**；出口④可把口令**直接交给攻击者控件**，不需剪贴板、不需焦点。
2. **`P2-49`（原 `NEW-N1`，HIGH 已撤销）** —— **无需主密码**即可由远端驱动极长 KDF
   （逐项封顶已存在，缺 `I×M` 联合预算）；与 `P2-48` / `P2-67` 组合成**准持久 DoS**。
3. **`CHAIN-B`（清理缺口）** —— 各环节无外部前提，换库 / 退出 / 锁库后明文可同时滞留。
4. **`RC-04` 的不对称** —— 自动填充已正确绑定 `CryptoObject`，CM 通道未绑且不查门控；**"已证明可行却未做"**。

## 13.3 最危险的设计问题

**`RC-09`** —— 因为 `FieldReferenceEngine` **已经实现了正确的约束**，只是取值模式没接上。
**修复成本极低，而当前代价是一条根因污染 4 个泄漏出口（5 处调用点）。**

其次是 **`R-CLEAR-2`**（共享引用 → 就地清零会误伤）——**它解释了为什么 5 条"清零"类条目反复无法简单修复**。

## 13.4 是否适合发布

**核心密码学边界与数据静态保护（P0 级）完好**——不存在"库可被未授权解开"或"认证可被绕过"的缺陷。
**但存在必须修复的 HIGH 项**，且修复面明确、不涉及重构。

---

# 14. SECURITY REVIEW VERDICT

# RELEASE AFTER CRITICAL/HIGH FIXES

## 为什么不是 `BLOCK RELEASE`

本项目的**核心安全承诺未被突破**。本轮独立确认 KDBX4 认证链完整且 fail-closed；全部密钥比较为常时；
Rust FFI 5 个导出函数 panic 不外泄；备份面封堵；签名私钥从未入库。
**不存在**"库可被未授权解开"或"认证可被绕过"的缺陷。恶意 KDBX 攻击者（威胁模型对手 D，最强对手）
**无法**通过头部认证、无法构造可通过块 HMAC 的载荷、无法借解析器实现任意代码执行。

## 为什么不是 `ACCEPTABLE WITH KNOWN RISKS` / `REVIEW PASSED`

存在**必须修复的高危项**，且修复成本明确、不涉及重构：

1. **`NEW-FINDING-01`（HIGH）** —— 口令明文可经 4 个出口离开应用，其中**出口④直接交给攻击者控件**；
   根因单点、修复面明确（复用既有 `resolveForDisplay` 掩码机制）。
2. **`P2-49`（原 `NEW-N1`，**终评 MEDIUM / P1**）** —— 派生（`KdbxFile.kt:135`）**先于** Header HMAC（`:147`），
   且 codec **不校验 `I×M` 联合预算** ⇒ `I = 2²⁴` 且 `M` 接近「堆/2」的 Header **能通过全部逐项闸门**，
   在**无需主密码**的前提下驱动长时 KDF。
   **但**该攻击的**前提坍缩为"后端写权限"这一单一前提**，而该对手**本已握有零成本 DoS**（拒答 / 垃圾字节 /
   无界大响应 = `P2-75`）⇒ **未提升能力等级 ⇒ P1，不与 P0 并列**（详见 §15.2(k)）。
   **（第二次审核更正：本条原写"`M = 4 GiB` 能通过全部闸门"，与 §4.2 的更正矛盾，已改。）**
3. **`RC-05` 清理缺口（MEDIUM）** —— "锁库即安全"这一**对外承诺**在多条并行路径上不成立。

## 放行条件

1. 完成 **P0-1**（`NEW-FINDING-01`，**5 处调用点**）与 **P0-2**（`P2-75`，含 `SyncCycleRunner.kt:383-387`）；
2. 完成 **P1-1 ~ P1-8**，其中 **`P1-4` 必须包含 `P2-63`**（否则 `P2-53` 的修复被完全抵消）；
3. 按 §9.6 的 **23 条 Fix 陷阱**逐条规避 —— **其中 7 条若字面实施会写坏数据库或造成安全回归**（§15.2(c)）；
4. 按 §10 的验证方式取得可复跑证据。

**若 `P1-4`（CM 通道 `CryptoObject` 绑定）技术上确不可行**，须按原记录要求在 KDoc 与 UI **如实声明**
为回调级验证——**如实声明可替代修复，但不得沉默**。

---

# 15. 附录

## 15.1 证据与中间产物位置

| 内容 | 位置 |
|---|---|
| 第一轮四份报告原文（可经 git 取回） | `git show 9b64415:docs/SECURITY_AUDIT_2026-09.md`（等 4 份）；本轮已暂存副本于 `.audit-recheck/originals/` |
| Finding Registry + Review Plan（Phase 1 / 2 产物） | `.audit-recheck/00-registry-and-plan.md` |
| 各批次原始复核记录（含逐条 13 字段） | `.audit-recheck/batches/B01-findings.md` ~ `B09-findings.md` |
| 新发现单列 | `.audit-recheck/batches/NEW-FINDINGS.md` |
| 交叉验证台账（22 项确证 + 11 项反证） | `.audit-recheck/batches/00-cross-verification.md` |
| Root Cause / Attack Chain 工作稿 | `.audit-recheck/batches/00-cross-analysis.md` |

> `.audit-recheck/` 已加入 `.gitignore`（证据暂存，不随仓库分发）；**本报告已纳入 git 跟踪**
> （2026-09-13），依 `AGENTS.md` §4 索引纪律（退役纪律）立规——审计报告须在开始阅读时即纳入跟踪，否则退役后**无法**经 `git show` 取回。

## 15.2 **第二次全量审核的更正（2026-09-13，报告发布后）**

> 委托方对本报告做了 93 条断言的全量独立审核。下列为**我方复核后接受的更正**。
> 逐条均已由我亲自直读源码验证，不是被动接受。

### (a) 撤销：`NEW-N1` 不作为独立 HIGH —— 并轨 `P2-49`（**我方错误，成立**）

**审核论点**：`KdbxHeader.kt:325` 在解析 `KDF_PARAMETERS` 字段时**就地**调用
`KdbxKdfParameterCodec.deserialize` → `validateArgon2Bounds`（`:151-175`），在任何派生发生**之前**封顶。

**我的验证**：
- `database/src/main/java/com/keepasskey/database/file/KdbxHeader.kt:324-325`：
  `KdbxConstants.HeaderFieldId.KDF_PARAMETERS -> { kdfParams = KdbxKdfParameterCodec.deserialize(fieldData) }` ✅ 成立
- `KdbxKdfParameterCodec.kt:152`（内存上下界）、`:157`（迭代）、`:160`（并行度）、`:171-174`（50% 动态堆）✅ 成立
- `KdbxFile.kt:135` 的 `deriveKeys(header, …)` 中的 `header` **必经**上述解析 ✅ 成立

**结论**：**"无界 KDF"叙事不成立**。逐项封顶**先于**派生生效，故残缺口**只有 `I×M` 联合预算**——
即已登记的 `P2-49`。**`NEW-N1` 是同机制重计，HIGH/P0 撤销。**

**并附带承认：我犯了自己刚刚给 `P2-59` 降级时所用的同款论证错误。**
我在 §12 更正 #1 中以"上游 codec 闸门更严于引擎自身阈值"为由给 `P2-59` 降级，
却在 `NEW-N1` 中**没有**对同一个上游闸门做同样检查。这是**不一致**，审核方指认准确。

**同时承认常量张冠李戴**：我在 §4.6 把封顶写成"4 GiB / 2²⁴ / 2²⁸"并暗示 2²⁸ 属并行度。
实测 `KdbxKdfParameterCodec.kt:63` `ARGON2_MAX_PARALLELISM = 64`；`:66` `AES_KDF_MAX_ROUNDS = 1L shl 28`。
**2²⁸ 是 AES-KDF 轮数，不是并行度**。两张常量表被我串行。正确表述：
**内存 ≤ 4 GiB 且 ≤ 50% 堆 · 迭代 ≤ 2²⁴ · 并行度 ≤ 64 · AES-KDF 轮数 ≤ 2²⁸**。

**但我要保留一项技术事实（这不挽救 HIGH，只确定 P2-49 的落点）**：
`KdbxFile.kt:135` 的派生**确实在 `:147` Header HMAC 校验之前**，且 `KdbxKdfParameterCodec` **不校验 `I×M` 联合预算**
（逐项闸门确实存在，`M` 另受「堆/2」动态上界）。
→ **`P2-49` 由 `LOW–MED` 升为 `LOW–MED/MEDIUM` 并入 `P1`**（它是本项唯一权威落点，不再另立条目）；
其墙钟量级**待实测**（§10.1 `M-1`），定级不依赖该数值。

### (b) 撤销：`NEW-B01-2` 的事实前提不存在（**我方错误，成立**）

**审核论点**：`aes_kdf.rs:72` 的中间缓冲**就是** `Zeroizing`；`:82-88` 是 SHA-256 终结与返回值构造。

**我的验证**（直读 `crypto/src/main/rust/src/aes_kdf.rs:61-89`）：
`:72 let mut buf = Zeroizing::new([0u8; BUF_LEN]);` ✅ —— **`buf` 确为 `Zeroizing`**，
KDoc `:60` 亦明载"任何返回路径（含下方所有 `?`）均确定性归零"。
`:86-88` 构造 `out` 并 `copy_from_slice(&digest)` 后 `Some(out)` —— **KDF 输出不可能在返回前清零**（调用方需要它）。

**结论**：**"连 `Zeroizing` 都没有"不成立。`NEW-B01-2` 撤销，HIGH 属性撤销。**

**并承认计数自相矛盾**：§1.1 声明 `HIGH = 2`，而 §11 把 `NEW-B01-2` 标为 HIGH ——
**第三项 HIGH 凭空出现**，与 §1.1 直接冲突。这是同一份报告内的自洽性缺陷，审核方指认准确。

### (c) 撤销：Fix 陷阱 #1（`P3-99`）（**我方错误，成立**）

**审核论点**：`SessionCredentialCache.kt:69` 是**第二份独立 clone**，不是"接管"。

**我的验证**：
- `SessionCredentialCache.kt:29-30`：`pwdClone = passwordCache?.clone()` / `keyClone = keyFileCache?.clone()` ✅
- `:41` `passwordSnapshot()`、`:44` `keyFileSnapshot()` **各自再 clone** ✅
- 类 KDoc `:14` 自述"与拆分前把 `passwordCache` / `keyFileCache` 直接传给…"⚠️ **措辞有歧义**，
  但**实现确为独立克隆**（`private var keyFileCache` 自持）。

**结论**：**缓存持独立数组，非别名共享** ⇒ 在 save 与 `restoreCredentials` 之后的 `finally` 补 `fill(0)` **安全**。
**Fix 陷阱 #1 撤销**；`P3-99` 回归**真缺陷**（冗余 clone 泄漏，`RC-02` 族）——当前代码**确实漏擦一份**，
但**实施原 AC 不会写坏库**。**"8 条会写坏库"更正为至多 7 条。**

### (d) 补充：`NEW-FINDING-01` 的调用点是 **5 处**，我的 P0-1 AC 漏 1 处（**我方错误，成立**）

**审核指认**：`AutofillDatasetBuilders.kt:158` 的 `password` 解析路径也是 `resolveFieldReferences` 调用点。

**我的验证**（§4.1 引用过的同一段代码）：
```kotlin
:157  val password = entry.password?.readString()
:158      ?.let { raw -> vaultRepository.resolveFieldReferences(entryIdHex, raw) }
:159      .orEmpty()
```
✅ 成立 —— 这是**第 5 个**调用点，且我此前在 §11 的调用点枚举中**只列了 4 处**。

**结论**：**P0-1 的 AC 必须改为 5 处**：
`AutofillDatasetBuilders.kt:155`（username）、**`:158`（password）**、`EntryDetailViewModel.kt:450`（copyPassword）、
`:460`（copyUsername）、`AutofillPickerViewModel.kt:69`（选择器）。
→ 按我原 AC 字面实施会**留下一个活性出口**。**这恰好违反了我自己给第一轮立的 §15.2 第 7 条纪律
（"验收标准必须与技术结论同等级别审查"）——审核方的这一指控完全成立。**

**对该调用点的准确定性**（重要，避免过度归因）：`:158` 解析结果被填入的是**真正的口令字段**（`:185-190 passwordId`），
属意图行为；它**不**跨字段泄漏。故它是**必须加入白名单以防回归**的第 5 个点，
而**不是**第 5 个泄漏出口 —— 我 §4.1 的"5 个出口"表**不应**把它列为出口。

### (e) 更正：`P3-89` 的熵是 **≤16 bit**，我沿用了第一轮的"32 bit"（**我方错误，成立**）

**我的验证**（我此前已读到同一段代码，但**算术推演错了**）：
`SettingsExportController.kt:278` `DIGEST_HEX_LENGTH = 8`、`:284 NIBBLE_MASK = 0x0F`、`:303`：
```kotlin
val value = byte.toInt() and NIBBLE_MASK              // ∈ [0,15]
builder.append(HEX_DIGITS[value ushr NIBBLE_BITS])     // (value >> 4) == 0 → 恒 '0'
       .append(HEX_DIGITS[value and NIBBLE_MASK])      // 低 4 位
if (builder.length >= DIGEST_HEX_LENGTH) break         // 8 字符后停 → 只消费 4 byte
```
→ 输出 = **4 个恒定 `'0'` + 4 个 hex 字符**（来自 4 byte 的低 4 位）= **4×4 = 16 bit**。
**我先前称"32 bit 精确正确"是错的**（我数错了字对数：把 8 个字符当成 8 个有效 hex 位）。
**第一轮原记录"32 位"同样是错的** ⇒ 这是**双向都错**的条目，正确值是 **16 bit**。

### (f) 我方**部分接受**的两项

- **`P1-24` 的 AC "应用名不可伪造"**：审核未反对我方"锚点只有包名 + 签名证书 SHA-256"的更正；
  我方维持该更正，并保留"官方 `AutofillService.java:335-373` 允许展示归属 ⇒ AC 平台可实现"的结论。
- **`IPC-01` 的裁决框架**：审核指出我方"凭据值串扰不成立"是**在原始描述框架内作否定**，
  与 §2.4 宣称的"不继承原攻击前提"部分冲突。**我接受该观察**：对丢失文档的 33 项，
  我方只能依据现行描述界定问题边界，故该限制应在 §2.4 之外**逐条标注**，而非只在总则声明。

### (g) 我方**驳回**的一项：`NEW-FINDING-01` 的 severity 与"出口②无需交互"

审核称"出口②（数据集菜单直接显示口令明文）连交互都不需要，故 P0 可维持"。
**该论断对出口②成立，但对我方原表述的"5 个出口"不成立**——因 (d) 已确认第 5 点是 `password` 字段（意图行为，
不是泄漏出口）。故修正后为 **4 个泄漏出口**：

| 出口 | 是否需要用户交互 |
|---|---|
| ① 剪贴板（`EntryDetailViewModel.kt:460`） | 需要（用户点"复制用户名"） |
| ①' 确认页 extra（`AutofillDatasetBuilders.kt:202`） | 需要（用户须到达确认页） |
| ② 数据集菜单（`:162`） | **不需要** —— 只要系统渲染候选即可见 |
| ③ IME 内联建议（`:173`） | **不需要**（受 `P2-71` 默认 true 放大） |
| ④ 请求方控件（`:180-183`） | 需要（用户确认填充） |

**故 `NEW-FINDING-01` 的 HIGH / P0 维持**（出口②③ 无需交互），但**出口计数由 5 更正为 4**，
**AC 由 4 处更正为 5 处调用点**。这两处更正是**不同维度**，不可互相抵消。

### (h) 我方**接受但需限定**的一项：`P2-66` "16 bit" 之外的"低估项"

审核另列 5 项"报告低估"（`P3-88` 第三份副本、`P2-51` 空 clip 分支绕开哈希比对、`P3-123④` `mapping.txt`
无条件上传、`P2-58` 两条平方路径且 `HealthCheckEngine` 对全库每条口令循环、`P2-74` 根因是缺 `<queries>`）。
**其中 `P2-51`、`P3-123④`、`P2-74` 根因、`P2-58` 放大面 4 项与我方既有结论方向一致但程度更重，
我方接受并升格**；`P2-74` 的**修复方向应由"调整判定顺序"改为"补 `<queries>`"**，
`P3-94` 亦随之改为与 `P2-74` 同批（`P3-94` 原本就与 `<queries>` 同文件同区域，此项与我方 §9.6 #17 一致）。

### (i) 计数更正后的最终口径（替代 §1.1 与 §17）

| 项目 | 原报告 | **更正后** |
|---|---|---|
| `HIGH` | 2 | **1**（仅 `NEW-FINDING-01`） |
| `MEDIUM` | 14 | **15**（`P2-49` 由 LOW–MED 升入） |
| `LOW` | 28 | 28 |
| `INFO` | 45 | 45 |
| 独立 HIGH 条目 | `NEW-FINDING-01` + `NEW-N1` | **仅 `NEW-FINDING-01`**（`NEW-N1` 并轨 `P2-49`） |
| Fix 陷阱"会写坏库" | 8 条 | **7 条**（撤销 #1 `P3-99`） |
| Fix 陷阱总数 | 23 条 | **23 条**（#1 由"写坏库"降为"真修复"，条目保留） |
| `NEW-B01-2` | HIGH（§11） | **撤销**（事实前提不存在） |

**关于 `CONFIRMED = 17` 的可对账性**：审核指出我方**未提供成员清单**，违反我方给第一轮立的 `SYS-4`。
**该项指控成立。** 现补成员清单（17 项）：
`P1-25`、`P2-43`、`P2-46`、`P2-47`、`P2-48`、`P2-49`、`P2-51`、`P2-52`、`P2-53`、`P2-54`、`P2-58`、`P2-61`、`P2-72`、`P2-75`、`P2-77`、`P2-78`、`P3-86`、`P3-97`、`P3-100`、`P3-101`、`P3-102`、`P3-105`、`P3-117`、`P3-123`
—— 实测为 **24 项**，与 §1.1 的 17 **不符**。**§1.1 的计数按本清单更正为 24**（其分布应重新机械导出，
不再手工维护 —— 这正是 `SYS-4` 的教训，我方亦犯）。

### (j) 我方维持不变的结论（第二次审核亦逐条坐实）

`RC-05` / `CHAIN-B` 全链、`P2-77` 陷阱、`P3-105` 陷阱、`P2-61` 双点、`P3-86` 升格、
`RC-04` 三缺口、`P2-52` 真实崩溃路径、`P2-75` 的 Error 逃逸链归因、以及全部误报裁决
（`P3-79`/`P3-90`/`P3-91`/`P2-66`/`IPC-05`/`P3-124`）与 `IPC-0x`/`SUPPLY-0x` 的逐条结论
（**2026-09-16 口径补注**：此处指已获逐条裁定的 **10 项**——§6.8 九项 + §6.6 的 `SUPPLY-05`；
待复核区全域 **19** 项，另 **9** 项为重合归并，见 §1⑤ 与 §6.8.1）。

**净自评（经两轮全量审核）**：本报告的**机制发现与逐项反证经受住了两轮独立审核**；
**受损的是我方的"包装层"**——两项 HIGH 定级（一项重复计价、一项前提不存在）、
"8 条会写坏库"的表述（实为 7 条）、以及**计数与一致性纪律**
（`HIGH` 自相矛盾、`CONFIRMED` 清单与矩阵错位、熵值算错、`P2-49` 定级三方打架、
"更正已全部写入正文"的声明只兑现约一半）。
**审核方"计数与 HIGH 清单不可直接引用"的告诫，我方接受。**
**三次复发同一错误（同一论证标准不统一使用）已如实记录**，见 §15.2(a)(k) 与 §15.3。

### (k) **接受**：`P2-49` 由 P0 降为 **P1**（第二次审核对 `P2-49` 定级的反驳成立）

我方上一轮主张"`P2-49` 不是备注，是 P0"，依据两条。**两条均被证伪，我接受降级。**

**证伪一：`M` 有动态上界。** `KdbxKdfParameterCodec.kt:170-174` `heapCap = maxMemory()/2`；
本应用 Manifest **无 `largeHeap`**（实测 `AndroidManifest.xml` 命中 **0**）
⇒ 真机 `maxMemory()` 典型 256–512 MiB ⇒ **`M = 4 GiB` 在解析期即被拒**，
攻击者最多驱动 `M ≈ 128–256 MiB`。**我方"最坏 ≈ 2²⁴ × 4 GiB"高估 16–32 倍。**
（修正后真实缺口：`M` 有动态闸门，**`I` 只有静态上界 2²⁴** ⇒ 燃烧量级为**小时级**，非"数十小时以上"。）

**证伪二：CA 级 MITM 被本仓配置封死。** `app/src/main/res/xml/network_security_config.xml:15-18`
`<trust-anchors><certificates src="system" /></trust-anchors>`，注释 `:7` 自述"**拒绝用户安装的 CA 证书**参与信任链"。
⇒ 做 CA 级 MITM 必须把 CA 装进**系统**信任库 = **root / MDM 级设备沦陷**。
而按我方**自己对 `P1-22` 的裁决逻辑**（"该前提只能由 root/物理提取满足，而对手 G 已能取得全部明文"）——
**在已沦陷设备上，一次 KDF 燃烧的边际价值为零。**

**⇒ 前提坍缩为单一**：攻击者拥有**受害者所配同步后端的写权限**。

**能力等级检验（决定性）**：拥有后端写权限的攻击者**今天就能**零成本拒绝服务——
拒答、返回垃圾字节、返回无界大响应（后者正是我方列为 P0-2 的 `P2-75`）。
**`I×M` 联合预算缺失没有提升对手的能力等级**，只是把"免费的同步失败"变成"更费电的同步失败"。
**P0 语义应保留给提升能力等级的项**：`NEW-FINDING-01`（新增**机密性**能力）、
`P2-75`（把同步失败提升为**进程崩溃**）成立；`P2-49` 不成立。

**一致性检验（我方第三次犯同一错误）**：同一前提（后端写权限）下，`P2-47`（完整性）= MEDIUM/P2、
`P2-75`（可用性，提升为崩溃）= P0、`P2-49`（可用性，未提升）= **应为 P1**。
若"后端写权限 + 无需口令 + 可用性"自动入 P0，则任何依赖外部服务器的应用的全部 DoS 向量都是 P0，P0 失去区分度。
**这正是我方 §12 更正 #1 批评过的"同一论证标准不统一使用"的第三次复发。**

**CVSS 口径佐证**：`AV:N / AC:L / PR:L / UI:N / C:N / I:N / A:H` → base ≈ **6.5（MEDIUM）**。

**最终口径（全文统一）**：`P2-49` = **严重度 MEDIUM、修复优先级 P1**，
与 `P2-47` 对齐；`I×M` 联合预算**可与 P0 批次同批实施**（成本约一行），但**那是工程安排，不是定级依据**。

**同时承认我方上一轮消息自相矛盾**：正文写"升为 P1"，表格与结尾却说 P0，`§14` 又引用已删除的 `P0-2`。
**在定级未自洽之前提出分歧是不合格的**，此点审核方指认准确。

### (l) 我方"更正已全部写入正文"的声明**不属实**（第二次审核指认成立）

审核方逐节扫描列出 **10 处残留**（`§1.1` 终态 17、`§11` 第三 HIGH、`P0-2` 幽灵引用、
`§9.2` 与 `§14` 与消息正文三方打架、`§4.1` 调用点 4 处、`§4.2/§14/§15.2(a)` 三处重复的 `2²⁴ × 4 GiB`、
`§4.6` 的 2²⁸ 张冠李戴、`§7` 的 `NEW-CHAIN-1` 成员、`§8` 的 `RC-06` 提及、两处"8 条"）。
**我逐处实测确认全部实存，已在本轮全部回改。**
**根因**：我沿用了"先写更正节、再回改正文"的做法，而回改是**手工**的 ——
**这正是 `SYS-4`（计数须机械导出、不得手工维护）在我方复发**。已立规：**报告定稿前必须做一次全文一致性扫描**（§15.3）。

### (m) 我方 `CONFIRMED=24` 清单与矩阵错位（第二次审核指认成立）

- `§3.3.2` 矩阵把 **`P3-88`** 标为 `CONFIRMED VULNERABILITY（功能）` —— 我方清单**漏了它**；
- 我方清单含 **`P2-72`** —— 而矩阵 `:211` 将它定为 `POTENTIAL SECURITY ISSUE`，`§4.6` 却又列入"其余确认漏洞"（**三处不一致**）；
- `§6.8` 的 **`SUPPLY-02`**（CONFIRMED/MEDIUM）与 **`SUPPLY-06`**（CONFIRMED/LOW）**不在任何清单内**。

**已修正**：`CONFIRMED` 全体成员 = **24 项**（含 `P3-88`，**不含** `P2-72`），
`SUPPLY-02`/`SUPPLY-06` 单列为 `§6.8` 独立裁定；`P2-72` 统一为 `POTENTIAL`；
`§4.6` 标题改为"其余已裁定条目"。**"机械导出"的承诺在我方兑现的第一步即失守，此点指认准确。**

### (n) 第三次审核：我方 4 处残留 + 计数漂移（**全部成立，已修**）

审核方逐节扫描指出"更正已全部写入正文"**仍不属实**。我方逐处实测确认，**全部实存**：

| # | 残留 | 实测 |
|---|---|---|
| 1 | `§14`「为什么不是 BLOCK RELEASE」仍写"`M = 4 GiB` 能通过全部闸门" | ✅ 实存（放行论证段，**最不该留错**） |
| 2 | `§8` 根因矩阵 `R-CLEAR-1` 的 ★ 证据仍是已撤销的 `NEW-B01-2` | ✅ 实存 |
| 3 | `§13.1`「完好」清单仍写"`Zeroizing` 全路径（**除 `aes_kdf.rs`**）" | ✅ 实存（**强项陈述引用已撤销发现作唯一例外**） |
| 4 | `§4.6` 汇总表 `P2-49` 仍 `LOW–MED`（矩阵已 MEDIUM） | ✅ 实存 |
| 5 | `§13.2` 仍写"五个出口" | ✅ 实存 |

**新增发现（由我方自建扫描脚本抓出）**：`§15.2(a)` 段内**重述了已被我方撤销的断言**
（"`I = 2²⁴` 且 `M = 4 GiB` 的 Header 能通过全部闸门"）—— 该段在豁免区间内且行内无豁免标记词，
**手工扫描两次都漏了**，是**脚本第一次运行就抓出来的**。已修。

**根因与处置**：手工回改已连续**两轮**失守（第一轮 10 处、本轮 5 处）。
故 `§15.3` 第 9 条已由"自查声明"升级为**可执行物**：
`tools/audit/check_recheck_consistency.sh`（由"已撤销/已更正断言清单"驱动的 fail-closed 扫描，
已登记 `AGENTS.md` §4 索引与 §5 命令表）。
**当前状态：`PASS`（1203 行，0 残留）** —— 这是**可复跑的**，不再是自称。

### (o) 数量级：接受"待实测"，撤销我方的墙钟估算

审核方指出我方"小时级"仍是推算且方向可能反了（**把缺陷说小**）。
**我方接受**：已把 §4.2 的墙钟断言改为**明示推算 + 待实测**，并新增实测项 `§10.1 M-1`（构造 `I=2²⁴, M=堆/2`
的合法 Header 实测一次 KDF 耗时）与 `M-2`（`codec` 的 `堆/2` 与 `Argon2KdfEngine` 的 `0.6×maxHeap` 两处阈值
不一致，须实测哪一处先触发——**这是审核方本轮暴露出的第二个新问题**）。
**定级不依赖该数值**（§15.2(k) 的判据是前提与能力等级）。

### (p) 第四次审核：最后三处残留 + 脚本自证不足（**全部成立，已修**）

| # | 指认 | 实测与处置 |
|---|---|---|
| 1 | `§7` `NEW-CHAIN-1` 仍以肯定语气写"KDF 小时级燃烧"，与 §4.2 的"待实测"不同步 | ✅ 实存。已改为"工作量比基线高约 6.7 × 10⁶ 倍，**墙钟待实测**"，并补禁用短语 `小时级` |
| 2 | `MEDIUM = 16` 清单**漏 `P1-24`/`P1-25`**（矩阵 §3.1 终评即 MEDIUM） | ✅ 实存。已改为 **`MEDIUM = 18`** 并补全清单 —— **同一张表第四次失守**，认 |
| 3 | 脚本豁免词表**过宽**，削弱 fail-closed 成色 | ✅ 实存，且**指认精准**：原词表含 `审核\|我方\|期望\|应为\|映射` 等在**全报告范围**生效，残留行只要含这些高频词即被静默跳过。**直接证据**：`§14` 的残留是"`I = 2²⁴` **且** `M = 4 GiB`"，而禁用清单只有"`2²⁴ × 4 GiB`"——**该条最终由人工 grep 抓到，脚本漏了** |

**脚本已按指认重构**：
- **豁免词表收窄**为强标记（`已撤销` / `已并轨` / `已更正` / `原列` / `原写` / `初稿` / `与事实相反` / `该表述错误` 等），
  删除全部高频词；
- **禁用短语升级为 POSIX ERE 正则**，`2²⁴.{0,10}(×|且).{0,10}4 ?GiB` 现覆盖"且…"形态；
- 新增 `小时级` 条目。
- **复跑结果**：正向 `PASS`（1237 行 / 11 条）；负向三项（含**当初漏掉的那条**）**全部 `rc=1`**。

### (q) `M-2` 题面精化（第三次审核的技术更正，**成立**）

我方原写"两处阈值哪一处先触发"**不准确——二者不在同一路径上**：
- **native 路径（真机默认）**：只有 codec 一道闸（`KdbxKdfParameterCodec.kt:171-174` 的「堆/2」），
  `M = 堆/2` **直达 Rust 内核**；`Argon2KdfEngine.kt:52-64` 的 native 分支**没有第二道闸**；
- **JVM 兜底路径**：codec 之后另有 `transformJvm`（`:73`）的 `0.6 × maxHeap`（`:130-132`）**二次拦截**。

→ **按原题面实测会误判**。`§10.1 M-2` 已改写为分路径判据。

### (r) 块数换算：我方"重新校准"时**再次算错一档**（**成立**）

| 量 | 我方前两次 | **正确值** |
|---|---|---|
| 基线每线程 blocks（`M = 64 MiB`、`t = 10`、`p = 2`） | 3.3 × 10⁴ | **3.3 × 10⁵**（`64×1024 × 10 / 2 = 327,680`） |
| 攻击每线程 blocks（`M = 256 MiB`、`I = 2²⁴`、`p = 2`） | 1.4 × 10⁹ | **2.2 × 10¹²**（`256×1024 × 2²⁴ / 2 ≈ 2.2×10¹²`） |
| 比值 | "4 个数量级" | **约 6.7 × 10⁶（10⁶–10⁷ 量级）** |

**你最初给的 10⁶–10⁷ 是对的**，我"校准"它时反而错了一档。**墙钟推演这个议题上三方三错**
（我方两次 + 校准一次）—— 这正是 `M-1` 实测被立为独立项的**唯一理由**：**该议题已无继续推算的价值。**

## 15.3 方法学建议（供下一轮审计与流程改进）

1. **审计文档必须在开始阅读时即纳入 git 跟踪**（`SYS-1` 已造成事实损失）。
2. **待复核区必须逐条给出"成立 / 不成立"结论**，不得只归并转待办（`SYS-2`）。
3. **对拍 ≠ 独立重放**：须区分"描述与代码相符"与"该行为是漏洞"（`SYS-3`）。
4. **计数须机械导出**，先冻结"N 项 = 哪些 ID"的成员清单；分类与严重度严禁混用（`SYS-4`）。
5. **记录须给实际命中的仓库相对完整路径**（本轮实测 ≥8 处路径漂移）。
6. **编号空间须全局唯一**，跨文档引用须带来源前缀（`SYS-6`，`FP-04` 冲突）。
7. **验收标准必须与技术结论同等级别审查**——本轮证明 23 条 AC 有陷阱、7 条会破坏数据。
8. **`§43.7` 类"已确认强项"陈述须同样可核**（强项 9 与实际合并清单不符）。
9. **【第三次复发后立规·已落地为可执行物】报告定稿前必须跑一致性扫描**：
   ```bash
   bash tools/audit/check_recheck_consistency.sh docs/SECURITY_RECHECK_2026-09.md   # fail-closed
   ```
   机制：脚本内维护「已撤销 / 已更正断言 → 禁用短语」映射（清单见脚本内 heredoc，共 10 条），
   行内含豁免标记词或处于 §12 / §15.2 区间者跳过。**切勿在正文里复述禁用短语**——
   脚本首跑即抓出过一处此类自引用。
   **立规缘由**：手工回改连续两轮失守（第一轮 10 处、第二轮 5 处），
   且第二轮有 1 处是**脚本首跑即抓出、手工两次都漏**的（§15.2(n)）。
   **"自查声明"不算闭环，可执行物才算。** 该脚本已登记 `AGENTS.md` §4 索引与 §5 命令表。
11. **【P0 判据·锁定，防未来摇摆】DoS / 可用性类条目必须过两道检验**（第三次审核确认后固化）：
    - **检验一（能力等级）**：该缺陷是否使攻击者获得**此前没有**的能力？
      同一前提前已握有等价原语者**不得**定 P0（`P2-49`：后端写权限者本可零成本拒服务）。
    - **检验二（遏制绕过）**：该缺陷是否**绕过应用自身的错误遏制机制**？
      是 ⇒ **能力等级提升 ⇒ P0**（`P2-75`：`Error` 逃逸 `catch(Exception)`（`SyncCycleRunner.kt:383-387`），
      失败模式由"已处理的同步错误（可重试、可自救）"升级为"**进程死亡 + 每同步周期自动复发**"）。
    - **对照结论（定版）**：`P2-49` = **MEDIUM / P1**（消耗资源，但被遏制在既有错误处理框架内）；
      `P2-75` = **MEDIUM / P0**（绕过遏制）。**二者不冲突，判据在此。**
