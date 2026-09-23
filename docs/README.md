# docs/ 文档地图

> **用途**：本仓文档按「入口 / 架构 / 记录 / 安全 / 参考 / 归档」六类分区，不再平铺。
> **新增文档必须归入下列某一分区并登记到本表**（索引纪律见 [`../AGENTS.md`](../AGENTS.md) §4）。

## 入口（docs 根）

| 文档 | 用途 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | 给 AI 协作代理的硬约束与闭环纪律（版本基线、工程限界已下沉至 `docs/`） |
| [`ACTIVE_ISSUES.md`](ACTIVE_ISSUES.md) | **唯一**待办清单（P0 → P3），自包含背景与验收标准 |
| [`RESOLVED_LOG.md`](RESOLVED_LOG.md) | 已整改问题归档**总索引**（**一行索引、不复制正文**；§154 迁移后 47 KB / 152 行，直达每个批次文件） |

## architecture/ — 架构与技术选型

| 文档 | 用途 |
|---|---|
| [`architecture/ARCHITECTURE.md`](architecture/ARCHITECTURE.md) | 模块依赖拓扑与关键架构决策 |
| [`architecture/已知工程限界.md`](architecture/已知工程限界.md) | **已接受工程限界 / 残余风险的唯一登记表**（原 `AGENTS.md` §6，`215ea82` 删除后由本文件承接）：事实 / 边界 / 依据 / 解除条件 |
| [`architecture/产品裁决登记.md`](architecture/产品裁决登记.md) | **已裁决的产品口径登记表**（`PD-01`~`PD-14`）：属「产品 / 架构取舍」而非缺陷的条目；与待办清单、`已知工程限界` 的分工见该文件开头 |
| [`architecture/敏感缓冲所有权契约.md`](architecture/敏感缓冲所有权契约.md) | **敏感缓冲所有权与擦除责任的唯一规范**（`ISSUE-P3-235` / `RC-02` AC① 交付物）：四类所有权（O/B/S/P）与判定树、规则 R1~R6、收口点登记表、非秘密豁免下界清单、`已知工程限界` §1.6 池内擦除解除条件评估、逐处迁移路线图；**改任何清零 / 所有权语义前必读** |
| [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md) | **实现约定与验证现状**（§255 从 `已知工程限界.md` 迁出、**逐字原样**）：§1.2 彻底退出的清理面 / §2.3 Rust 内核与互操作**已达成** / §3.1 原生库懒加载顺序 / §3.2 `CipherInputStream` 异常语义 / §3.3 遮挡触摸过滤接线清单 / §3.5 完整性重扫参数与契约不变量 / §4.1 设备侧覆盖现状 / §4.2 视觉与系统 UI 未核对项 / §4.3 KPEX 互操作证据现状；**非残余风险**，限界表对应编号处留存根（节标题 + 规范句 + 指针） |
| [`architecture/reference-projects.md`](architecture/reference-projects.md) | 参考项目地图与优先级层级 |
| [`architecture/扫码方案评估_ZXing与CameraXMLKit.md`](architecture/扫码方案评估_ZXing与CameraXMLKit.md) | 扫码方案选型评估（ZXing vs CameraX/ML Kit） |

## records/ — 实测与排障记录

| 文档 | 用途 |
|---|---|
| [`records/原生Argon2真机验证记录.md`](records/原生Argon2真机验证记录.md) | 原生内核真机 / 模拟器实测登记（**禁混表**） |
| [`records/真机吞吐实测记录_2026-09-17.md`](records/真机吞吐实测记录_2026-09-17.md) | 真机吞吐实测（`ISSUE-P3-153` / `P3-155` / 新发现 `P2-92`）：ChaCha20 / AES-CBC / Passkey 三算法前后对比，含方法学差异声明与复现命令 |
| [`records/JNI零拷贝评估_2026-09-19.md`](records/JNI零拷贝评估_2026-09-19.md) | JNI 零拷贝评估（`ISSUE-P3-187`）：三条候选路线契约、有状态 vs 无状态四维对照（AC⑤）、DirectByteBuffer 探针真机对比与结论（AC②④） |
| [`records/KDBX4与复合密钥实战互操作排查日志.md`](records/KDBX4与复合密钥实战互操作排查日志.md) | KDBX4 / 复合密钥互操作排障留痕 |
| [`records/ci-静态校准记录.md`](records/ci-静态校准记录.md) | CI 配置与静态分析的校准留痕 |
| [`records/退役依据承接-ISSUE-P3-09.md`](records/退役依据承接-ISSUE-P3-09.md) | `ISSUE-P3-09` 依据承接（Action 升级 / ProGuard 收窄 / Compose BOM 回归判据）；原 `.handoff` 依据从未入库 |
| [`records/运行完整性检测Frida实测基线.md`](records/运行完整性检测Frida实测基线.md) | 真机 Frida 三形态实测矩阵（`ISSUE-P3-120`）：命中率、两层防线互补关系、四条未经实测的规避面、复现步骤与清理留痕 |
| [`records/存量条目前提复核记录.md`](records/存量条目前提复核记录.md) | `ACTIVE_ISSUES.md` 三条「已裁决暂缓」条目（`P2-47` / `P2-79` / `P3-121`）的前提复核（2026-09-16）：正文前提判定、失真裁决理由、修正片段 |
| [`records/SyncCache大写CACHE临时文件定位记录.md`](records/SyncCache大写CACHE临时文件定位记录.md) | `ISSUE-P3-142` 定位记录：残留条目**磁盘上并不存在**（四路交叉取证），判为 Windows/NTFS 目录枚举鬼影；含复现率、候选解释裁定与断言口径更正 |
| [`records/自动填充认证链路真机实测记录.md`](records/自动填充认证链路真机实测记录.md) | `ISSUE-P2-73` AC③ 真机实测记录（Redmi 4X / Android 17 / API 37）：设备侧驱动方式、定版用例与历史失败逐次留痕、logcat 原文与截图；四条新发现与该缺陷的定位 / 修复（§11） |
| [`records/通行密钥互操作对拍记录_2026-09-19.md`](records/通行密钥互操作对拍记录_2026-09-19.md) | `ISSUE-P2-210` / `ISSUE-P2-211` 的证据留痕（`AGENTS.md` 规则 8）：`keepassxc-cli` + `pykeepass` + `cryptography` 双向对拍的命令与原文输出、114 条判据构成、Ed25519 PKCS#8 形态缺陷的隔离实验与修复复跑、**github.com 端到端手工冒烟清单（本批未执行）** |
| [`records/限界表证据与实测数据汇录.md`](records/限界表证据与实测数据汇录.md) | [`architecture/已知工程限界.md`](architecture/已知工程限界.md) 的**证据 / 实测 / 沿革附卷**（§253 批次外迁，§253 补正扩充）：按 `§x.y` 分节收录从限界表移出的实测表、多轮叙事、根因推导与观测点记录（**整句 / 整段逐字搬移、不改写**），另含原表文末「附」表 40 行逐字归档与**附录 A**（§253 补正时由正文移出的全部说明性句子，按原节标题分组）；限界表内相应位置留一行指针 |
| [`records/宿主开发环境现象.md`](records/宿主开发环境现象.md) | **宿主开发环境现象**（§255 从 `已知工程限界.md` 迁出、**逐字原样**）：§7 Windows/NTFS 目录枚举鬼影及其派生残余、§12 宿主侧 Windows 原子 rename 偶发 `AccessDeniedException`——两节原文均自陈「**非产品缺陷**」、Android 生产环境未观测到；限界表对应编号处留存根（节标题 + 规范句 + 指针） |
| [`records/注册响应CBOR键误用定位记录.md`](records/注册响应CBOR键误用定位记录.md) | `ISSUE-P2-265` 定位记录（§263 批次）：`attestationObject` 的 CBOR 键误用 `authenticatorData`（应为 `authData`）的**字节级证据**、Chromium 侧报错出处链路、**五条走偏路径留痕**（每条都曾「自洽解释症状」）、**镜像测试盲区**（断言与被测代码共用常量导致实现与测试一起错）、以及 `connectedDebugAndroidTest` **卸载应用致数据丢失**的操作事故与立规；含复现取数的命令骨架 |

## security/ — 安全、威胁建模与合规

| 文档 | 用途 |
|---|---|
| [`security/同步层记录级完整性威胁建模.md`](security/同步层记录级完整性威胁建模.md) | 同步层跨记录置换 / 防回滚威胁建模（改动同步前必读） |
| [`security/退役审计承接-42-威胁建模与架构评估.md`](security/退役审计承接-42-威胁建模与架构评估.md) | 已退役威胁建模报告的**结论承接处**（信任边界 / 对手 / 假设 / 开放问题） |
| [`security/退役审计承接-43-安全整改方案与附录.md`](security/退役审计承接-43-安全整改方案与附录.md) | 已退役整改报告的**结论承接处**（附录 A–F、产品决策 A-1 ~ A-4） |
| [`security/Privacy-Policy.md`](security/Privacy-Policy.md) | 隐私政策（对外交付物） |
| [`security/SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) | **第二轮独立安全复核裁决报告**（89 项开放项逐条复核 + 第一轮「已排除」结论反证）；2026-09-15 由 `523d0fd^` 恢复入库 |
| [`security/第四轮复核遗留编号处置.md`](security/第四轮复核遗留编号处置.md) | `ISSUE-P3-141` 收口：第四轮复核工作目录「遗留编号」**逐条四选一**处置（已由某条目覆盖 / 本批修复 / 确属新缺陷 / 前提不成立）+ 可核对证据 + 工作目录结论分流与逐份退役判定 |
| [`security/待复核区IPC与SUPPLY项重合声明核实.md`](security/待复核区IPC与SUPPLY项重合声明核实.md) | `ISSUE-P3-145` 收口：待复核区全域 19 项中「重合归并」那 9 项的逐条核实（7 项成立 / 2 项部分成立）+ 两项独立残余（`IPC-11` CM 通道 `UNBOUND` 口径、`SUPPLY-01` 覆盖 `P2-54` AC②） |

## references/ — 参考项目架构分析（只读借鉴）

| 文档 | 用途 |
|---|---|
| [`references/README.md`](references/README.md) | 参考项目总览：项目列表、**参考优先级层级**（严格执行）与使用方式；借鉴实现思路前先读 |
| [`references/KeePassDX-架构分析.md`](references/KeePassDX-架构分析.md) | 🥇 **核心参考**（Android / Kotlin+JNI）：Gradle 多模块同构、`DatabaseSession` 生命周期、Argon2 策略、Passkey、Compose 改造 |
| [`references/keepass2android-架构分析.md`](references/keepass2android-架构分析.md) | 🥈 **云同步参考**（Android / Xamarin）：`IFileStorage` 插件化、WebDAV/S3 适配、离线缓存、ETag 三方冲突、Quick Unlock |
| [`references/KeePass-2.61.1-架构分析.md`](references/KeePass-2.61.1-架构分析.md) | ⚖️ **格式裁决者**（官方 C#）：`.kdbx` v3/v4 事实标准、`KeePassLib` 加密管线 / Inner Random Stream / Header-HMAC 权威终审 |
| [`references/KeePassXC-架构分析.md`](references/KeePassXC-架构分析.md) | ⚖️ **合并与 Passkey schema**（C++/Qt）：`Merger` 条目级合并与墓碑复活、`KPEX_PASSKEY_*` 属性、KDBX 读写管线交叉验证 |
| [`references/Monica-架构分析.md`](references/Monica-架构分析.md) | 🥉 **UI / 本地优先辅助**（Kotlin + Rust `mdbx`）：Material 3 + Compose 动效、本地优先 vault（逻辑历史 / 冲突 / 快照） |
| [`references/存量5项开放问题的参考项目对照.md`](references/存量5项开放问题的参考项目对照.md) | `ACTIVE_ISSUES.md` 当时 5 项开放条目逐项「参考项目怎么做」取证对照：文件级防回滚 / KDF「只升不降」/ Autofill 不依赖框架重发 / SSRF / 分支保护，含 `路径:行号` 证据与「未找到证据」的区分；其中三条已作为产品裁决迁出，本文件仍是其依据 |

## resolved/ — 历史批次归档

| 文档 | 用途 |
|---|---|
| [`resolved/README.md`](resolved/README.md) | 分册体系说明与维护规则 |
| [`resolved/BATCH_01_30.md`](resolved/BATCH_01_30.md) 等 **5 册** | 分册级索引（各 ≤100 数据行；分册 04 已封卷、05 自 §158 滚动） |
| `resolved/batches/` | **一批次一文件**的批次正文（份数与最大 § 编号**一律现查**：`ls docs/resolved/batches \| wc -l`，最大编号见 [`resolved/README.md`](resolved/README.md)；§42 / §43 已归入 `security/`） |

> **回溯约定**：归档正文只搬迁、不改写。拆分前的完整版本见 git `a144d21`。
