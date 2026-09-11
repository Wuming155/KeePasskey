# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：本批次发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md`、`plans/rust-enclave-poc.md` 等文件「需要删除」，但这些文件早在
   > `7dba64d`（重构文档体系）与 `d578df7` / `863d81c` 中就已删除；`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|---|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。

---

## P1 高危与核心功能问题（0 项）

> 当前无待办。
> **2026-09-11 闭环**：**ISSUE-P1-11**（自动填充「受信浏览器」仅按包名信任、未校验签名证书）
> 已整改归档——浏览器分支升级为「包名 + 已取证签名证书指纹」二元组，未取证浏览器 fail-closed 降级 DAL，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §22.7。
> 历史 P1 项（含 ISSUE-P1-10 / ZT-10）亦已全部闭环，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5。
> **2026-09-11 追加闭环**：**ISSUE-P1-12**（Android 端 KDBX XML 解析全量失败 —— 设备端**任何库都打不开**）
> 由「设备侧链路验证」实测发现，同批次修复并在真实 AVD 上验证通过，见 §24。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> 当前无待办。
> ISSUE-P2-05 ~ ISSUE-P2-13 九项已全部整改并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.20。
> P2-12 整改中如实登记的残余面 ISSUE-P2-15 / ISSUE-P2-16（受保护值经 String 退化、
> 密码生成引擎出边界仍返回 String）已整改并归档，见 §2.21。
> **2026-09-11 闭环（零信任全量安全审计批次）**：
> - **ISSUE-P2-17**（子库解锁未接入节流）→ 节流下沉至 `ChildDatabaseSessionManager.open()`，按 `mountId` 计次，
>   锁定期不进入 `KdbxFile.load`；仅认证失败计次，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §22.6。
> - **ISSUE-P2-18**（同步缺防回滚绑定）→ 引入本地认证的「已见内容摘要链」（Keystore HMAC + `SyncRollbackGuard`），
>   重放旧库被拒并提示，跨端兼容结论留痕，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §22.8。

## P3 低危问题、特性接线与体验优化（2 项）

> **背景**：P3 残余批次原 **12 项**（ISSUE-P3-17 ~ P3-28）已于 **2026-09-10** 整体整改。
> 其中 **10 项完整闭环并归档**（P3-17 / 18 / 19 / **20** / 21 / 22 / 25 / 26 / 27 / 28，含逐项代码证据与 15 条过程缺陷留痕），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§4**；**2 项部分达标**（P3-23 / P3-24 —— 均为**本环境物理不可达**的
> 验证类条目：arm64 设备与 GitHub runner）就地更新后保留于本节
> （**其中 P3-24 已于 2026-09-11 经真实 CI 运行实证闭环归档，见 §23；P3-23 仍保留**）；
> 另有 **2 项新登记**（**ISSUE-P3-29** 全仓超阈值债务、**ISSUE-P3-30** 子库条目投影未并入根库列表）。
> **2026-09-10 追加一**：**ISSUE-P3-30 已闭环并归档**（子库条目只读投影接入库列表，见 §5）。
> **2026-09-10 追加二**：**ISSUE-P3-29 批次 A 已闭环并归档**（见 §6）——
> 条目正文点名的**优先级 8 项全部降至 400 行阈值内**（`SettingsViewModel` / `VaultListViewModel` /
> `DatabaseSettingsDialogs` / `KdbxFile` / `KeePasskeyApp` / `VaultEntryRows` / `VaultRepository` / `VaultListScreen`），
> 另**增量完成** `KdbxHeader` 与 `SyncCredentialsStore` 两项，并登记 `DicewareWordList` 为**经论证的纯常量例外**；
> 仓库内残余的 **23 个**真逻辑超阈值文件**整体转入 ISSUE-P3-31** 重新登记（附 2026-09-10 实测快照与核实方式）。
> **2026-09-10 追加三（CI 首跑实测 + 供应链残余处置）**：新增 **ISSUE-P3-32**（供应链达阈告警残余）；
> 同一批次内**已闭环**的工作见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§7**：
> `Fast gate` 147 个 lint error 清零、CodeQL 10 条告警处置、`dependency-scan` 的 CVSS 阻断语义
> **静默失效**之实证与硬断言补强、**Kotlin 2.4.20 真修复 `CVE-2026-53914` + 4 族豁免登记**
> （本地真实扫描实测 **188 → 7 条实例、达阈 138 → 0 条**）、以及**合并 PR #5**
> （原 ISSUE-P3-33，已闭环归档）；实测校准追加节见 [docs/ci-静态校准记录.md](ci-静态校准记录.md) **§11**。
> **2026-09-10 追加四（原生内核与工程化扩展批次）**：经全仓性能/架构评审（逐模块读源）新登记的
> **ISSUE-P3-34 ~ P3-38** 五项（AES-KDF 原生内核 / Twofish 原生内核 / 密码强度评估原生引擎 /
> `HmacBlockStream` 摘要收敛 / `.kdbx` 互操作语料生成脚本自动化）**已于同批次全部闭环并归档**，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§8**——Rust 单测 9 → **43**、全仓用例 1200 → **1257**、
> `crypto` lint 告警 4 → **0**；该节如实留痕 **9 条过程缺陷/事实修正**（含 1 处生产缺陷、
> 1 处安全校验冒充漏洞、1 处文档与实测相反的事实修正）与 **3 项未验证项**。
> **2026-09-10 追加六（自动填充能力对标批次整改）**：上述「追加五」登记的 P3-39 ~ P3-45 七项中，
> **6 项已实现并归档**——P3-39（字段识别与候选打分/上次填充优先）、P3-40（手动选择器）、
> P3-41（服务健康自检）、P3-42（会话授权宽限）、P3-44（保存开关真实接线 + 评估结论）、
> P3-45（结构化数据可行性评估结论），见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§9**；
> **P3-43 部分达标**：① 已接线既有 `overrideNoAutofill`（尊重 `importantForAutofill`，同时消除一处
> 假开关），② 字段签名级屏蔽 与 ③ 保存侧独立黑名单 两项因**缺少用户交互写入入口**，
> 就地保留待办并已重写验收标准（严禁先落库无写入方的存储 API）。
> **2026-09-10 追加七（P3-31 批次 B）**：ISSUE-P3-31 验收标准 1 点名的两项
> `RealVaultRepository`（1090 → 372）与 `DatabasePickerScreen`（968 → 319）已按纯结构性拆分完成并归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§10**。
> **2026-09-10 追加八（P3-31 批次 C + P3-43 闭环）**：批次 C 三项
> （`ThemeSettingsScreen` 762 → 155 / `EntryEditScreen` 712 → 388 / `EntryDetailViewModel` 708 → 399）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§11**；**ISSUE-P3-31 本条未闭环**（残余 18 项），清单已就地刷新。
> 同批次 **ISSUE-P3-43 全部闭环并归档**（② 字段签名级屏蔽 / ③ 保存侧独立黑名单，
> 三件套一次性交付，见 §11.3 / §11.4），本文件中该条目已移除；
> 同批次顺带发现并修复一处既有生产缺陷（详情页 `passwordStrengthBits` 无写入方 → 强度条恒不渲染，见 §11.5），
> 其签名抗枚举加固残余新登记为 **ISSUE-P3-46**。
> **2026-09-10 追加九（P3-31 批次 D + P3-46 闭环）**：批次 D 三项
> （`DatabaseSession` 697 → 393 / `SecuritySettingsScreen` 674 → 371 / `KeePasskeyAutofillService` 634 → 334）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§12**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **15 项**，清单已就地刷新）。
> 同批次 **ISSUE-P3-46 全部闭环并归档**（§12）：字段签名密钥来源改为 Android Keystore 内
> **不可导出 HMAC 密钥**，schema `v1 → v2`、盐不再落盘、旧数据一次性保守失效，本文件中该条目已移除。
> **2026-09-10 追加十（P3-31 批次 E）**：验收标准 1 点名的下一批三项
> （`S3SyncProvider` 629 → 372 / `PasskeyCryptoEngine` 596 → 362 / `KdbxMerger` 578 → 207）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§13**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **12 项**，清单已就地刷新）。
> **2026-09-10 追加十一（P3-31 批次 F）**：验收标准 1 点名的下一批三项
> （`SettingsScreen` 569 → 306 / `UnlockScreen` 562 → 275 / `GeneratorScreen` 550 → 177）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§14**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **9 项**，清单已就地刷新）。
> **2026-09-10 追加十二（P3-31 批次 G）**：验收标准 1 点名的下一批三项
> （`WebDavSyncProvider` 510 → 345 / `AutofillSettingsScreen` 504 → 216 / `EntryEditComponents` 494 → 296）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§15**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **6 项**，清单已就地刷新）。
> **2026-09-10 追加十三（P3-31 批次 H）**：验收标准 1 点名的下一批三项
> （`CloudSyncComponents` 481 → 291 / `EntryDetailComponents` 477 → 223 / `EntryEditViewModel` 470 → 400）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§16**；**ISSUE-P3-31 本条仍未闭环**
> （残余真逻辑超阈值 **3 项**，清单已就地刷新）。
> **2026-09-10 追加十四（P3-31 批次 I · 本条闭环归档）**：验收标准点名的最后三项
> （`KeystoreManager` 462 → 239 / `HealthCheckScreen` 453 → 315 / `SyncEngine` 443 → 316）
> 已完成并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§17**；
> 门禁 `test --rerun-tasks` **1329 例 / 0 失败 / 13 跳过**、`lint` 5 模块 0 error，
> 公开 API **零丢失零新增**。**全仓重测后 `> 400` 仅剩 2 项，且均为经论证的例外**
> （纯常量词表 `DicewareWordList` 408、因 ISSUE-P3-43 接线产生功能性增量的 `SettingsViewModel` 424），
> 故 **ISSUE-P3-31 达成闭环并整条移出本文件**。
> 本节余 **2 项**（P3-23 / **ISSUE-P3-58**）。
> **2026-09-11 追加四（CodeQL Rust 误报治理）**：针对上一则登记的 **ISSUE-P3-57**（77 条
> `rust/hard-coded-cryptographic-value` critical 误报）先行**机制验证**——查询源码无测试代码过滤、
> `paths-ignore` 为文件级过滤、配置级排除需 advanced setup——据此把 Rust 单测外移为**纯测试文件**
> 并新增 `.github/workflows/codeql.yml` + `.github/codeql/codeql-config.yml`（PR #6）。
> 验证：advanced 分析的 rust 结果 **77 → 0**（`rules` 仍为 26，生产代码未被误伤），
> 而同一时刻默认设置的分析仍报 77 条 → **双向实证**。本文件该条目已移出，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) **§23.5**。
> **2026-09-11 追加（功能完整性审计批次 A + B）**：以「README 声称功能 → 引擎/仓库 → ViewModel/控制器 → UI 入口」
> 四层逐项做端到端接线审计，两批共发现并**同日整改归档 5 项**（A：全文搜索范围、详情页单条删除；
> B：HOTP 端到端、单条移动分组 / 从模板新建便利入口、`AttachmentManager` 孤儿实现清理），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§20 / §21**（批次 B 验收 1345 例 / 0 失败 / 13 跳过）；
> 本文件中 P3-47 ~ P3-51 均已移出。
> **2026-09-11 追加二（零信任全量安全审计批次）**：对 `app/ core/ crypto/ database/ sync/` 全量
> `src/main`（含 `crypto/src/main/rust/`）按零信任五支柱做静态审计（Assume Breach 威胁模型，
> **未做动态/运行时验证**），7 项正式发现经逐条独立复读源码复核**全部属实、无误报**，按严重度分级登记：
> **ISSUE-P1-11**（P1）、**ISSUE-P2-17 / P2-18**（P2）、**ISSUE-P3-52 ~ P3-55**（P3），
> 次要加固项打包登记为 **ISSUE-P3-56**；各项核实时间点与核实方式见条目内。
> **2026-09-11 同日闭环**：上述 8 项**本地可整改条目已按难度递增顺序全部整改归档**
> （见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§22**），本文件正文已移出；本节现存条目**仅余** P3-23
> 一项**外部资源依赖**的验证类残余。门禁：`test --rerun-tasks` **1370 例 / 0 失败 / 13 跳过**、`lint` 5 模块 0 error。
> **2026-09-11 追加三（CI 侧真实跑通 + CodeQL 新发现）**：以 `gh workflow run` 手动触发 `dependency-scan`
> 运行 **`34575788016`** 并全程盯守，**首次全绿**（aggregate `BUILD SUCCESSFUL in 15m 4s`、
> 硬断言 `达阈（CVSS ≥ 7.0）实例 0 条` 且 exit 0、artifact 与 SARIF 上传均成功），
> **ISSUE-P3-24 与 ISSUE-P3-32 据此闭环归档**，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§23**；
> 同批核对 Code Scanning 时**新发现 77 条** CodeQL `rust/hard-coded-cryptographic-value`（critical）
> open 告警（全部落在 Rust 单测模块内、判定为误报），就地登记为 **ISSUE-P3-57**——
> 并附**机制验证结论：该查询无任何测试代码过滤，单纯「外移单测」无效**（详见条目内）。
> 同时更正两处滞后前提：`NVD_API_KEY` **早已配置**（503 系 NVD 服务端间歇故障，非缺 Key），
> 以及「CodeQL 开放告警 = 0」**已不再成立**。
> **2026-09-11 追加五（CodeQL 语言覆盖面）**：核对 Code Scanning 语言面时发现 **`java-kotlin`（本仓主语言）**
> 的默认设置分析**历来为空跑**（`rules=0 / results=0`），而切换 advanced setup 后该语言未纳入 matrix；
> `c-cpp` 则经 `Glob` 核实本仓**无任何 C/C++ 源**（命中的文件全在 gitignore 的 `参考项目/`），无需纳入。
> 就地登记 **ISSUE-P3-58**——含官方文档给出的构建模式边界（**Kotlin 无 `build-mode: none`**，
> 必须提供真实 Gradle + Android SDK 构建）与 Kotlin 2.4.20 的版本支持风险（须先实测）。
> **2026-09-11 追加六（ISSUE-P3-58 可行性实测收口）**：在草稿 PR #7 上实跑 `java-kotlin` 的 CodeQL 覆盖
> （`build-mode: manual` + Android SDK + 窄化 Kotlin 编译）：**SDK 与构建链路全通、31 个任务真实执行**，
> **唯一阻塞是 CodeQL 的 Kotlin 编译器插件直接拒绝 Kotlin 2.4.20**
> （`Kotlin version 2.4.20 is too recent. CodeQL currently supports versions below 2.4.20`）。
> 结论：**暂不纳入，登记为已接受的风险**（Kotlin 侧静态分析由 Android Lint + 1370 例单测 + 人工审计承接）；
> 解除条件与可复现配方已留痕于条目内与 PR #7；**严禁以回退 Kotlin 版本换取分析覆盖**。证据见 ISSUE-P3-58 正文。
> **2026-09-11 追加七（设备侧链路验证 · 连带修复致命缺陷）**：为验证 P3-23 的设备侧链路，启动本机 x86_64 AVD
> 实跑 `:database:connectedDebugAndroidTest`——**本仓首次在真实 Android 运行时执行设备侧用例**：
> 链路可用性得到验证，同时**发现并修复一处设备端致命缺陷 ISSUE-P1-12**
> （Android 端 `SAXParserFactory.newSAXParser()` 因不支持的 XXE 加固特性抛异常 → **任何库都打不开**；
> JVM 单测走 Xerces 故长期潜伏、从未暴露）。修复后设备侧用例 **pass**、全量门禁
> **1370 例 / 0 失败 / 0 错误 / 13 跳过** 与 `lint` **0 error** 保持，见 §24。
> 本节仍余 **2 项**（P3-23 / P3-58），但 **P3-23 的阻塞项已收窄为「真实语料 + arm64 数据」**。
> 归档门禁证据（2026-09-10 批次 I 实测，`--rerun-tasks` 强制真实执行）：
> `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**，
> **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；
> 纯结构性拆分，用例数与批次 H 基线持平）；
> `lint`（5 模块 **0 error**）通过；`:database:assembleDebugAndroidTest` 通过（ISSUE-P3-23 验收标准①）。

### 前提复核记录（2026-09-10，依「条目维护规则」第 2 条）

> 已对本节全部条目完成一次前提复核：**核实时间点 2026-09-10**。**逐条的核实方式记录在各条目自身的
> 「核实时间点与核实方式」段落内**（ISSUE-P3-28 确立的格式），此处不再重复列表。
> **复核结论**：P3-23 的「语料未入库」成立，但其「`database` 无 `androidTest` 源集」已不成立
> （该源集已建立并接线），已在条目内就地标注；P3-24 前提完整成立。
> **2026-09-10 追加八复核结论**：P3-31 批次 C 前提成立（三项行数经 `wc -l` 复核与清单一致）；
> P3-43 的两项待办前提成立（`AutofillBlocklistStore` 仍无字段级/保存侧能力，手动选择器已在位可作交互落点）。
> **P3-43 已于同批次闭环归档**（见 §11），条目移出本文件。
> **P3-30 已于 2026-09-10 归档**（前提「生产消费方为零」在开工时成立，整改后消费方落地，见 §5）。
> **P3-29 已于 2026-09-10 批次 A 闭环归档**（见 §6）；其残余 23 项已按「新增条目须附核实时间点与核实方式」
> 转入本节 **ISSUE-P3-31**（2026-09-10 经 `wc -l` 全仓复核）；**该条亦已于同批次 B~I 全部拆分闭环**
> （见 §10~§17），本文件不再保留该条目。

> **ISSUE-P3-20 已闭环**（子库挂载 **UI 接线**：`childDatabasesCount` 去硬编码并接真实 `mountedCount`；
> `ChildDatabaseDialog` 成为真实入口并调用核心层 `mount`/`open`/`unmount`；两条失真文案随能力上线删除；
> 新增 26 例单测），归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §4.2。
> 其接线过程中**由执行者如实发现的「过度声明」残余面**即 **ISSUE-P3-30**，已于 §5 闭环归档
> （投影接入库列表，且只读 / 不并入根库 / 不参与搜索与自动填充）。
>
> **ISSUE-P3-25 已闭环**（3 个点名文件全部降至阈值内：`SyncCoordinator` 965→254、`KdbxXmlGroupReader`
> 407→218、`UnlockViewModel` 979→396）；其「全仓扫描」暴露的整体债务经 **ISSUE-P3-29** 承接，
> 该条**批次 A 已完成优先级 8 项并归档**（见 §6），残余 23 项由 **ISSUE-P3-31** 承接，
> **该条已于 2026-09-10 批次 B~I 全部拆分闭环**（见 §10~§17）——巨型类债务至此清零，
> 全仓 `> 400` 仅余两项**经论证的例外**。


### ISSUE-P3-23 (P3-11 残余): arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁

- **优先级**：P3（验证覆盖；依赖外部设备与语料资源）
- **核实时间点与核实方式（2026-09-10）**：经 `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse`
  （已安装 system-image 仅 `android-34` / `android-36.1` 的 **x86_64**，**无任何 arm64-v8a**）、
  `adb devices -l`（**空列表**）、`sdkmanager --list_installed`、`emulator.exe -list-avds` +
  `Pixel_10.avd\config.ini`（`abi.type=x86_64`）、`Test-NetConnection dl.google.com -Port 443`（True）、
  `sdkmanager --list | Select-String "system-images;android-36.1;.*arm64"`（**远端有发布、本机未安装**）
  逐项核实；并核实 `database/src/androidTest/**` 与 `database/build.gradle.kts:41-46` 落盘内容。
- **本批次已消除的阻塞（工程侧就绪，非验证结果）**：
  1. `database` 模块**首次建立 `androidTest` 源集与依赖接线**（`database/build.gradle.kts:10-15`、`:41-46`）；
  2. 设备侧端到端解锁用例落地 `RealKdbxCorpusUnlockTest`，**fail-closed**：语料缺失 → `Assume` 显式跳过
     （跳过消息自解释到「照哪个文件生成、放到哪里」，且**明确声明跳过不代表验收达成**）；
     语料在而伴生元数据缺失/非法（含 `containsRealData=true`、`passphraseIsThrowaway=false`、
     `source` 非 KeePass 系、`kdf` 非法、`entryCount<=0`）→ **硬失败**（「有 .kdbx 但无从断言」不可能蒙混成绿）；
  3. `SelfGeneratedRoundTripInstrumentedTest` **四处**声明「不构成与 KeePass 2.61.1 / KeePassXC 的互操作证据」
     （类名 / KDoc / 方法名 / stdout）；
  4. 语料逐步生成清单写入 `crypto/src/test/resources/argon2-interop/README.md`（KeePass 2.61.1 七步 +
     KeePassXC + 双落位 `src/test/resources` ↔ `androidTest/assets` **不可互替** + 伴生 JSON schema + 命名约定），
     并声明语料口令为公开一次性常量、**严禁任何真实主密码**；
  5. `docs/原生Argon2真机验证记录.md` 已加注本次范围与「未取得」项（x86_64 既有数据**原样保留**）。
- **仍未达成的残余面**：
  1. **arm64 真机 / arm64 模拟器数据未取得** —— 本机无 arm64-v8a 镜像、无真机连接；安装 arm64 镜像属大体积下载
     （超出本批次范围），且 x86_64 宿主上的 arm64 模拟器数据按纪律**不得**与「arm64 真机」同表登记；
     `docs/原生Argon2真机验证记录.md` §4.3 表格**保持待填**；
  2. **真实 KeePass 2.61.1 / KeePassXC `.kdbx` 语料仍未入库** —— 需人工 GUI 建库 + 逐条复核，无人值守流程无法产出；
     语料未入库前设备侧用例按设计**跳过**（注意：**该项不依赖 arm64**，现有 x86_64 AVD 即可执行）。
- **2026-09-11 复核（阻塞前提再确认；核实方式：读 `tools/kdbx-corpus/README.md` §3 并实跑脚本）**：
  1. `python tools/kdbx-corpus/generate_corpus.py --check` 实测 **exit 3**：本机无 `keepassxc-cli`；
  2. 且即便安装，README §3 明确 **KeePassXC CLI `db-create` 无法设定 Argon2 变体/版本与 t/m/p**，
     故真实语料**必须由官方 GUI 建库**（脚本 `--ingest` 只做复核 / 命名 / 写伴生 JSON / 双落位）——
     「无人值守产出真实语料」在本环境**不成立**（推翻先前「脚本自动化已可产出」的期待）；
  3. 脚本自身能力已本地实测通过：`--dry-run` 正常列出双落位目录；`--verify database/src/test/resources/fixtures/test_vault.kdbx`
     正确解析 `argon2d / v19 / t=89 / m=65536KiB / p=4 / 32B salt`，与 `crypto/.../argon2-interop/README.md` §5.1 逐项一致。
  **结论**：本条两处阻塞（GUI 语料、arm64 真机）均为**外部资源依赖**，本地不可消除。
- **2026-09-11（本批次收口复核，核实方式：PowerShell 实测）**：`adb devices -l` 仍为**空列表**；
  `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse | ? FullName -match 'arm64'` 仍**零命中**
  （无 arm64-v8a 镜像）；`python tools/kdbx-corpus/generate_corpus.py --check` 仍 **exit 3**（无 `keepassxc-cli`）。
  **阻塞前提不变，本条不归档。**
- **2026-09-11 追加（设备侧链路验证；核实方式：启动本机 AVD `Pixel_10` / x86_64 / API 36，
  实跑 `.\gradlew.bat :database:connectedDebugAndroidTest`）**：
  **链路可用性已验证**——设备侧用例能真实安装并执行（**本仓首次在真实 Android 运行时跑数据库侧用例**），
  并因此**发现并修复了一处设备端致命缺陷 ISSUE-P1-12**（见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§24**）；
  修复后该任务 **BUILD SUCCESSFUL**、`SelfGeneratedRoundTripInstrumentedTest` **pass（0.293s）**。
  据此**阻塞项收窄为两项**：① 真实语料（须官方 GUI 建库——本环境无 `keepassxc-cli`，且该 CLI 无法设定
  Argon2 变体/版本与 t/m/p）；② arm64 真机数据（本机无 arm64-v8a 镜像、无设备）。**本条仍不归档。**
- **措辞修订（同批实测，如实）**：原「语料缺失 → Assume 显式跳过」在**报告呈现**上与实情不符——
  AGP 的 `build/outputs/androidTest-results/connected/debug/TEST-*.xml` 把 `AssumptionViolatedException`
  记为 **`<failure>`（`skipped=0`）**，**但 task 级仍 `BUILD SUCCESSFUL`**。
  即：门禁语义正确（语料缺失不会把任务弄红，也不代表验收标准②达成），但**报告会误导**读者以为用例失败；
  验收标准②的「不再是 skip 且全绿」以该报告口径为准（语料就位后应转为 `pass`）。
- **验收标准**：① `.\gradlew.bat :database:assembleDebugAndroidTest` 编译通过（**2026-09-10 已实测通过**，
  见批次 A 归档 §6.4 门禁证据）；② 真实语料（含同名 `.json`）
  入库两处后 `:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` **不再是 skip** 且全绿；
  ③ arm64 真机（`adb shell getprop ro.product.cpu.abi` = `arm64-v8a`）上取到 §4.3 表格数据并与 §4.1/§4.2 **分表**归档；
  ④ 归档文件「待填」表按实际情况回填（**严禁编造数据**）。
- **禁止**：以 x86_64 模拟器或宿主侧数据填充 §4.3；把「用例就绪」表述为「验证通过」；
  用自生成 `.kdbx` 往返冒充互操作证据。

---

### ISSUE-P3-58: Kotlin/Java 侧无 CodeQL 分析覆盖（实测结论：阻塞于上游 CodeQL 的 Kotlin 版本支持）

- **优先级**：P3（静态分析**覆盖面**缺口；**非本批次引入的回归**——默认设置时期二者即为空分析；
  2026-09-11 实测后登记为**已接受的风险**，见文末结论）
- **核实时间点与核实方式（2026-09-11）**：
  1. `gh api "/repos/Wuming155/KeePasskey/code-scanning/analyses?per_page=100" --paginate` 逐条统计
     `category` / `results_count` / `rules_count`：`/language:java-kotlin` 全部记录均为
     **`rules=0` / `results=0`**（最近一条 `2026-09-09T14:31:13Z`）；`rules=0` 表示**没有任何查询运行**
     （对照：正常语言为 rust 26 / python 43 / actions 17）；
  2. 本仓源码面：`app` / `core` / `crypto` / `database` / `sync` 五模块**全为 Kotlin/Java**（本仓主语言），
     故缺口的**绝对规模最大**；
  3. `c-cpp` 面**已排除**：2026-09-11 经 `Glob` 核实
     `{app,core,crypto,database,sync,tools}/**/*.{c,cc,cpp,cxx,h,hpp}` **零命中**；仓库内命中的 C/C++ 文件
     **全部位于 `.gitignore` 忽略的 `参考项目/`**。历史 `c-cpp` 分析系 C Argon2 遗留（ISSUE-P2-14 已移除）之残留，
     **无需纳入，也不应再纳入**；
  4. 能力边界（GitHub 官方文档 `codeql-build-options-and-steps-for-compiled-languages`，2026-09-11 拉取原文）：
     **Java 支持 `none` / `autobuild` / `manual`；Kotlin 仅支持 `autobuild` 或 `manual`**（**无 `none`**）
     → 想真正覆盖 Kotlin，**必须提供真实构建**（Gradle + Android SDK），无构建抽取对 Kotlin 无效；
  5. 版本风险：`codeql.github.com` 的「Supported languages」页对 Kotlin 的支持上界在不同文档版本下
     分别写作 **2.3.2*x*** 与 **2.4.1*x***，而本仓为 **Kotlin 2.4.20**（`gradle/libs.versions.toml:13`）
     → 即便补上构建，抽取器对 2.4.x 的支持程度**须先实测确认**，不得假定可用。
- **影响**：`ISSUE-P3-57` 切换到 advanced setup 后，`.github/workflows/codeql.yml` 的 matrix 仅含
  `rust` / `python` / `actions`。就**有效**覆盖面而言与默认设置时期持平（当时即为空跑），
  但**名义覆盖**（Security 页不再有该语言的条目）属**需要显式决策**的范围收缩——
  本条目即为该决策的载体。
- **候选处置（均须留痕）**：
  1. **补构建纳入 `java-kotlin`**：工作流内先装 Android SDK（`android-actions/setup-android`，与
     `build.yml` Fast gate 同源做法），以 `build-mode: autobuild` 或 `manual`（显式 `./gradlew` 编译命令）驱动。
     **收益最大**（覆盖本仓主语言）；**代价**：分析时长与脆弱点显著上升（AGP / JDK / NDK 版本链），
     且需先实测 Kotlin 2.4.20 是否被抽取器支持。
  2. **保持现状，显式登记为「已接受的风险」**：在 `docs/` 与工作流注释中写明「Kotlin 侧无 CodeQL 覆盖，
     由 Android Lint + 1370 例单测 + 人工审计承接」，作为维护者决策留痕。成本最低，但缺口长期存在。
  3. **先做可行性实测**：在特性分支上加一个 `java-kotlin` job（装 SDK + autobuild），
     只求拿到「`rules>0` 且能产出分析」的证据；跑不通则记录**具体失败点**（缺 SDK / 版本不支持 / 超时）。
- **实测结论（2026-09-11，方案 3 已执行）**：**当前不可纳入，阻塞在 CodeQL 上游**。
  在特性分支以草稿 PR #7 试跑（run `34587552395` 的 `Analyze (java-kotlin)` job）：JDK 21 / Android SDK /
  编译平台与 build-tools / CodeQL init **全部成功**，构建阶段 **31 个任务真实执行**，
  失败点为 `:core:compileDebugKotlin`，日志原文：

  ```text
  > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
     > Kotlin version 2.4.20 is too recent. CodeQL currently supports versions below 2.4.20
  ```

  即 CodeQL 在受跟踪构建中注入的 Kotlin 编译器插件**主动拒绝 Kotlin 2.4.20**，与官方文档给出的支持上界一致。
  **非本仓配置问题**（SDK 安装、依赖解析、资源与 R 文件生成等均成功）。
- **结论与风险接受**：`java-kotlin` **暂不纳入** CodeQL matrix，登记为**已接受的风险**——
  Kotlin 侧静态安全分析由 **Android Lint + 1370 例单测 + 人工审计**承接。
  **不得**为提高分析覆盖而回退 Kotlin 版本：本仓 2.4.20 承载 `CVE-2026-53914` 的**真修复**
  （[RESOLVED_LOG.md](RESOLVED_LOG.md) §7），回退等于用真实漏洞换「看起来有覆盖」。
- **解除条件（可复现配方，已留存于 PR #7 的 diff 与评论）**：CodeQL 的 Kotlin 抽取器支持 `>= 2.4.20` 后重试——
  matrix 加 `java-kotlin` + `build-mode: manual`；前置 JDK 21 + Android SDK（`platforms;android-37.0` /
  `build-tools;37.0.0`，**不装 NDK**）；构建仅跑五模块 `compileDebugKotlin`（不打包 → **不触发**
  `cargoNdkBuild`）；**不接** `gradle/actions/setup-gradle`（避免构建缓存使 compile 变 UP-TO-DATE /
  FROM-CACHE，导致抽取器拿不到编译单元）。判据：`code-scanning/analyses` 出现 `/language:java-kotlin`
  且 **`rules > 0`**。
- **验收标准（就本条而言已达成）**：① 给出明确二选一结论并留痕 → **已达成**（结论：不纳入 + 上游阻塞 + 解除条件）；
  ② 若纳入须有 `rules>0` 证据 → **不适用**（结论为不纳入）；③ 不得以「默认设置当年也这样」跳过决策
  → **已避免**（附本次实测证据）。
- **本条状态**：**本仓无进一步动作，等待上游支持**——与 ISSUE-P3-23 同属**外部资源依赖型残余**，
  保留于本文件**不归档**。
- **禁止**：为求「看起来有覆盖」而把 `java-kotlin` 加进 matrix 却任其构建失败（`rules=0`）空跑（**虚假覆盖**）；
  在未实测的情况下宣称「CodeQL 已覆盖 Kotlin」；**以回退 Kotlin 版本**换取分析覆盖；为纳入而弱化任何既有门禁。

---

### ISSUE-P3-24（CI 门禁首跑校准 · 2026-09-11 已闭环）

> 已于 **2026-09-11** 经真实 CI 运行 `34575788016` 实证闭环——aggregate `BUILD SUCCESSFUL in 15m 4s`、
> 硬断言首次给出确定判定（`达阈 0 条`，exit 0）、SARIF 与 artifact 上传均成功；
> 原始证据（含 runner 镜像版本）与逐条残余面结论见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§23.1 / §23.2**。
> 此处留索引，正文已移出本文件。


---

### ISSUE-P3-32（供应链达阈告警残余处置 · 2026-09-11 已闭环）

> 已于 **2026-09-11** 经真实 CI 运行 `34575788016` 实证闭环——硬断言首次在 aggregate 成功前提下给出**确定判定**
> （`漏洞实例 7 条；达阈（CVSS ≥ 7.0）实例 0 条`，exit 0），Code Scanning 依赖类 open 告警 **7 条**与该族
> 未达阈结论**一致**且未 dismiss。原始证据与逐条验收标准核对见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§23.1 / §23.3**。
> 此处留索引，正文已移出本文件。

---

### ISSUE-P3-57（Code scanning 对 Rust 单测的 critical 误报 · 2026-09-11 已闭环）

> 已于 **2026-09-11** 闭环：77 条 `rust/hard-coded-cryptographic-value`（critical）经**逐条**行号核对确认
> **77/77 全在 Rust 单测模块内**（误报）；按「单测外移为纯测试文件 + 切换 advanced setup 并以 `paths-ignore`
> 精确排除」处置——advanced 分析的 rust 结果由 **77 → 0**（`rules` 仍为 26），
> 且**同一时刻默认设置的分析仍报 77 条**，双向实证了「仅外移无效、必须叠加配置级排除」的机制结论。
> 合并（`93b088f` / PR #6）后实测 open 告警 **84 → 7**（该 rule **open 0 / fixed 77**，余 7 条为依赖类未达阈项）。
> 实施细节与原始证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§23.5**。
> 此处留索引，正文已移出本文件。

---

### ISSUE-P3-49 ~ P3-51（2026-09-11 功能完整性审计批次 B · 已闭环）

> 三项（HOTP 端到端 / 单条移动分组与从模板新建便利入口 / `AttachmentManager` 孤儿实现清理）
> 已于 **2026-09-11** 同日整改并归档，实施细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§21**
> （门禁：`test --rerun-tasks` **1345 例 / 0 失败 / 13 跳过**）。此处留索引，正文已移出本文件。

---

### ISSUE-P3-52 ~ P3-56（2026-09-11 零信任审计批次 · 已闭环）

> 五项（自动填充生物识别绑定 `CryptoObject` / 运行完整性时变信号实时化 / 解锁节流 Keystore HMAC
> 完整性绑定 / 复合密钥派生 UTF-8 密码副本清零 / 零信任审计次要加固项打包）已于 **2026-09-11**
> 同日整改并归档，实施细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§22.2 ~ §22.5**
> （门禁：`test --rerun-tasks` **1370 例 / 0 失败 / 13 跳过**、`lint` 5 模块 0 error）。
> 此处留索引，正文已移出本文件。


