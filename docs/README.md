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

## security/ — 安全、威胁建模与合规

| 文档 | 用途 |
|---|---|
| [`security/同步层记录级完整性威胁建模.md`](security/同步层记录级完整性威胁建模.md) | 同步层跨记录置换 / 防回滚威胁建模（改动同步前必读） |
| [`security/退役审计承接-42-威胁建模与架构评估.md`](security/退役审计承接-42-威胁建模与架构评估.md) | 已退役威胁建模报告的**结论承接处**（信任边界 / 对手 / 假设 / 开放问题） |
| [`security/退役审计承接-43-安全整改方案与附录.md`](security/退役审计承接-43-安全整改方案与附录.md) | 已退役整改报告的**结论承接处**（附录 A–F、产品决策 A-1 ~ A-4） |
| [`security/Privacy-Policy.md`](security/Privacy-Policy.md) | 隐私政策（对外交付物） |
| [`security/SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) | **第二轮独立安全复核裁决报告**（89 项开放项逐条复核 + 第一轮「已排除」结论反证）；2026-09-15 由 `523d0fd^` 恢复入库 |

## references/ — 参考项目架构分析（只读借鉴）

| 文档 | 用途 |
|---|---|
| [`references/`](references/) | 5 个参考项目深度分析 + 索引；借鉴实现思路前先读 |

## resolved/ — 历史批次归档

| 文档 | 用途 |
|---|---|
| [`resolved/README.md`](resolved/README.md) | 分册体系说明与维护规则 |
| [`resolved/BATCH_01_30.md`](resolved/BATCH_01_30.md) 等 **4 册** | 分册级索引（各 ≤100 行） |
| `resolved/batches/` | **一批次一文件**的批次正文（当前 98 份；§42 / §43 已归入 `security/`） |

> **回溯约定**：归档正文只搬迁、不改写。拆分前的完整版本见 git `a144d21`。
