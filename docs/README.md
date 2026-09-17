# docs/ 文档地图

> **用途**：本仓文档按「入口 / 架构 / 记录 / 安全 / 参考 / 归档」六类分区，不再平铺。
> **新增文档必须归入下列某一分区并登记到本表**（索引纪律见 [`../AGENTS.md`](../AGENTS.md) §4）。

## 入口（docs 根）

| 文档 | 用途 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | 给 AI 协作代理的硬约束与闭环纪律（版本基线、工程限界已下沉至 `docs/`） |
| [`ACTIVE_ISSUES.md`](ACTIVE_ISSUES.md) | **唯一**待办清单（P0 → P3），自包含背景与验收标准 |
| [`RESOLVED_LOG.md`](RESOLVED_LOG.md) | 已整改问题归档**总索引**（一页纸，直达每个批次文件） |

## architecture/ — 架构与技术选型

| 文档 | 用途 |
|---|---|
| [`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) | 模块依赖拓扑与关键架构决策 |
| [`architecture/已知工程限界.md`](architecture/已知工程限界.md) | **已接受工程限界 / 残余风险的唯一登记表**（原 `AGENTS.md` §6，`215ea82` 删除后由本文件承接）：事实 / 边界 / 依据 / 解除条件 |
| [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) | **已裁决的产品口径登记表**（`PD-01`…）：属「产品 / 架构取舍」而非缺陷的条目（2026-09-17 由 `ACTIVE_ISSUES.md` 迁出）；与待办清单、与 `已知工程限界` 的分工见该文件开头 |
| [`architecture/reference-projects.md`](architecture/reference-projects.md) | 参考项目地图与优先级层级 |
| [`architecture/扫码方案评估_ZXing与CameraXMLKit.md`](architecture/扫码方案评估_ZXing与CameraXMLKit.md) | 扫码方案选型评估（ZXing vs CameraX/ML Kit） |

## records/ — 实测与排障记录

| 文档 | 用途 |
|---|---|
| [`records/原生Argon2真机验证记录.md`](records/原生Argon2真机验证记录.md) | 原生内核真机 / 模拟器实测登记（**禁混表**） |
| [`records/KDBX4与复合密钥实战互操作排查日志.md`](records/KDBX4与复合密钥实战互操作排查日志.md) | KDBX4 / 复合密钥互操作排障留痕 |
| [`records/ci-静态校准记录.md`](records/ci-静态校准记录.md) | CI 配置与静态分析的校准留痕 |
| [`records/退役依据承接-ISSUE-P3-09.md`](records/退役依据承接-ISSUE-P3-09.md) | `ISSUE-P3-09` 依据承接（Action 升级 / ProGuard 收窄 / Compose BOM 回归判据）；原 `.handoff` 依据从未入库 |
| [`records/运行完整性检测Frida实测基线.md`](records/运行完整性检测Frida实测基线.md) | 真机 Frida 三形态实测矩阵（`ISSUE-P3-120`）：命中率 3/3、两层防线互补关系、**四条未经实测的规避面**、复现步骤与清理留痕 |
| [`records/存量条目前提复核记录.md`](records/存量条目前提复核记录.md) | `ACTIVE_ISSUES.md` 三条「已裁决暂缓」条目（`P2-47` / `P2-79` / `P3-121`）的前提复核（2026-09-16 对 HEAD `2e5ce38`）：正文前提判定、**失真裁决理由**、可直接粘贴的修正片段 |
| [`records/SyncCache大写CACHE临时文件定位记录.md`](records/SyncCache大写CACHE临时文件定位记录.md) | `ISSUE-P3-142` 定位记录：残留条目**磁盘上并不存在**（四路交叉取证），判为 **Windows/NTFS 目录枚举鬼影**；非本仓写入者、非外部写入者；含复现率、候选解释逐一裁定、断言口径更正与该形态的残余边界 |
| [`records/自动填充认证链路真机实测记录.md`](records/自动填充认证链路真机实测记录.md) | `ISSUE-P2-73` AC③ 真机实测记录（Redmi 4X / Android 17 / API 37）：设备侧驱动方式、**定版用例 4/4 通过**与历史失败逐次留痕、关键 logcat 原文与截图；**4 条新发现**——框架解锁后**不自动重发** `onFillRequest`（`ISSUE-P2-86`）、确认路径因认证结果不带数据集而**写不入凭据**（对照：选择器路径回传真实 `Dataset` 即成功）、`UiAutomation` 每用户单槽位、锁屏下 Activity 无法进入前台。**§11（2026-09-17）** 记录该缺陷的**定位与修复**（根因是**交付路径缺失**，非框架行为）与**真机验证 + 负向对照**，并给出确认路径的**反向结论**（只改 `FLAG_MUTABLE` / 基 Intent flags **无效** ⇒ 不可外推） |

## security/ — 安全、威胁建模与合规

| 文档 | 用途 |
|---|---|
| [`security/同步层记录级完整性威胁建模.md`](security/同步层记录级完整性威胁建模.md) | 同步层跨记录置换 / 防回滚威胁建模（改动同步前必读） |
| [`security/退役审计承接-42-威胁建模与架构评估.md`](security/退役审计承接-42-威胁建模与架构评估.md) | 已退役威胁建模报告的**结论承接处**（信任边界 / 对手 / 假设 / 开放问题） |
| [`security/退役审计承接-43-安全整改方案与附录.md`](security/退役审计承接-43-安全整改方案与附录.md) | 已退役整改报告的**结论承接处**（附录 A–F、产品决策 A-1 ~ A-4） |
| [`security/Privacy-Policy.md`](security/Privacy-Policy.md) | 隐私政策（对外交付物） |
| [`security/SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) | **第二轮独立安全复核裁决报告**（89 项开放项逐条复核 + 第一轮「已排除」结论反证）；2026-09-15 由 `523d0fd^` 恢复入库 |
| [`security/第四轮复核遗留编号处置.md`](security/第四轮复核遗留编号处置.md) | `ISSUE-P3-141` 收口：第四轮复核工作目录「遗留编号」（自称 **22** / 实际枚举 **23** / 两处枚举并集 **25**）**逐条四选一**处置（已由某条目覆盖 / 已在本批修复 / 确属新缺陷 / 前提不成立）+ 可核对证据；三处前提更正；**工作目录（`.audit-recheck/`）结论分流与逐份退役判定** |
| [`security/待复核区IPC与SUPPLY项重合声明核实.md`](security/待复核区IPC与SUPPLY项重合声明核实.md) | `ISSUE-P3-145` 收口：待复核区全域 **19 项**（`IPC-01…11` / `SUPPLY-01…08`）中「**重合归并**」那 **9 项**的**逐条**核实（7 项成立 / 2 项部分成立）+ 两项独立残余（`IPC-11` 族 CM 通道 `UNBOUND` 口径、`SUPPLY-01` 覆盖条目 `P2-54` AC② 无活动落点） |

## references/ — 参考项目架构分析（只读借鉴）

| 文档 | 用途 |
|---|---|
| [`references/`](references/) | 5 个参考项目深度分析 + 索引；借鉴实现思路前先读 |
| [`references/存量5项开放问题的参考项目对照.md`](references/存量5项开放问题的参考项目对照.md) | `ACTIVE_ISSUES.md` **当时 5 项开放条目**（`P2-47` / `P2-79` / `P2-86` / `P3-121` / `P3-146`）逐项「参考项目怎么做」取证对照：**文件级防回滚 5 家全无**、**KDF「只升不降」守卫全无**（且 KeePassDX 基准为降强路径）、**Autofill 两家均不依赖框架重发**（认证 PI 一律 `FLAG_MUTABLE`）、**SSRF 防护全无**（地址口径与本仓相反）、**分支保护即代码全无**；含各项目 `路径:行号` 证据与「未找到证据」逐条区分。**2026-09-17 起**其中 `P2-47` / `P2-79` / `P3-121` 三条已作为**产品裁决**迁出待办清单（见 [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) `PD-01`/`PD-02`/`PD-03`）；本文件的取证结论**仍然有效**，是上述三条裁决的依据 |

## resolved/ — 历史批次归档

| 文档 | 用途 |
|---|---|
| [`resolved/README.md`](resolved/README.md) | 分册体系说明与维护规则 |
| [`resolved/BATCH_01_30.md`](resolved/BATCH_01_30.md) 等 **4 册** | 分册级索引（各 ≤100 行） |
| `resolved/batches/` | **一批次一文件**的批次正文（当前 **128** 份，§1~§130；§42 / §43 已归入 `security/`） |

> **回溯约定**：归档正文只搬迁、不改写。拆分前的完整版本见 git `a144d21`。
