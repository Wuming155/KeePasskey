# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

## 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 .kdbx（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证（低版本平滑退化至传统密码填充）。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

## 硬约束（任何操作都适用）

1. **模块依赖严格单向**（详见架构指南），禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 源码目录执行无目标的全局 `grep`、`glob` 或大面积扫源码；
   - 5 个参考项目均已完成详尽的架构分析，集中存放在 **`.codebuddy/skills/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **第 1 优先级（核心参考）**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database` / `crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **第 2 优先级（次核心参考）**：**keepass2android** — 重点参考其云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制、三方哈希冲突检测与 Quick Unlock 快速解锁；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — 作为 `.kdbx` 格式（v3/v4）的官方事实标准，仅在遇到文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作为终极裁决标准；
     4. ⚖️ **算法级参考（合并引擎与通行密钥 schema）**：**KeePassXC (C++/Qt)** — 其 `Merger` 条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考，`KdbxReader/KdbxWriter` 管线用于 `database` 模块交叉验证，浏览器集成的 `KPEX_PASSKEY_*` 属性 schema 对照 `PasskeyData` 设计；
     5. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作为现代 Compose UI/UX 风格与本地优先思路的补充对照，不作为核心实现重点；
   - **凡涉及实现思路借鉴，必须强制先阅读对应架构分析文档，严禁直接去翻原始代码**；
   - 仅当架构分析文档明确指出某个特定算法或数据格式边界、且文档说明不足以完成独立编写时，才允许按图索骥精确定位阅读该单个源文件；
   - 严禁修改 `参考项目/` 目录下的任何文件，严禁复制其代码入库（许可证约束）。
4. **工程规则**（单一职责、禁止魔法数字、依赖倒置、错误处理等）详见下方规则文件，写代码前必须遵守。
5. **阶段交付与版本归档纪律**：严格遵照 `DELIVERY_PLAN.md` 推进；**每完成一个阶段，必须同步更新 `AGENTS.md`（刷新当前状态、已完成内容与下一阶段目标），并立即将阶段成果全部暂存并提交 Git 到本地仓库（`git add` + 语义化 `git commit`）**，确保每个阶段里程碑均具备独立、清晰、可回退的本地 Git 提交历史。

## 详细文档索引（按需阅读）

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| `.codebuddy/rules/engineering-rules.md` | 工程规则：单一职责与巨型类阈值、魔法数字、依赖倒置与 Hilt 注入、Result 错误处理、敏感数据、Compose 规范、**原子写盘、协程调度约束、Credential Provider 隔离、防御性安全（FLAG_SECURE / 剪贴板 / 混淆）** | **写 / 改任何代码前** |
| `DELIVERY_PLAN.md` | **完整项目交付规划**：7 大阶段任务、阶段交付物清单、验收门禁（DoD）与全渠道发布标准 | **规划任务、核对阶段与交付时** |
| `.codebuddy/skills/architecture.md` | 5 模块职责与依赖规则、7 条关键架构决策（加密分离、kdbx 兼容、DatabaseSession、同步模型、passkey 路线、UI 优先），另见 `ARCHITECTURE.md` | 跨模块改动、新增功能落位前 |
| `.codebuddy/skills/reference-projects.md` | 参考项目地图：各功能应参照哪个项目的哪些文件 | 实现 database / crypto / sync / passkey 功能时 |
| `.codebuddy/memory/project-status.md` | 版本配套表、项目现状、决策日志、待办 | 升级依赖、了解进度与历史决策时 |

## 构建命令

使用 Gradle Wrapper（**Gradle 9.3.1**，AGP 9.1.0 / Kotlin 2.4.10 / Hilt 2.60.1，kapt）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test` 已就绪，`testDebugUnitTest` 可单模块执行；当前 172 个测试全绿）
- 版本升级需整体配套：AGP ↔ Gradle ↔ Kotlin ↔ Compose BOM（Compose BOM 2026.06.00+ 要求 compileSdk 37，当前用 2026.06.01 对齐 compileSdk 36）

**当前阶段状态**：**🔧 参考项目差距修复专项（`REMEDIATION_PLAN.md`）Wave 1-5 全量交付完毕（Wave 5 = 已知限界清零专项）。**
原「7 阶段全量验收」表述经 2026-09-05 全量代码审计修正：对照参考项目发现 22 项问题（P0×6 / P1×7 / P2×9，含系统服务空壳响应、模拟延时同步、TOTP 假码、cipherKey 非官方派生等），已按 4 个 Wave 修复并逐波验收提交：

- **Wave 1（git ed601da）KDBX 官方兼容 + crypto 底座**：cipherKey 派生修正为官方 SHA-512 截断标准（读取侧旧派生自动回退、保存自动迁移）；XML Times 修正为 .NET Ticks 编码；XML 全字段往返（Meta/AutoType/Binary-Ref/CustomData）；InnerHeader 二进制池与附件去重；类型化异常体系；CBOR/COSE 确定性编码器；Passkey 三算法签名（ES256/Ed25519/RS256 + RFC 6979）；KdfBenchmark 设备自适应基准。
- **Wave 2（git 4927189）同步引擎 + 凭据服务端到端**：SyncEngine 三哈希状态机（Kp2a 决策树 + ICacheSupervisor 六事件 + 离线开关）；KdbxMerger v2 墓碑感知三方合并（删除vs修改/删除后重建/字段级合并/环路自愈）；WebDAV DOM 解析 + uploadAtomic 事务写 + URL 编码；S3 `If-None-Match:*` 原子首传 + SigV4 编码一致性；CredentialProviderService/AutofillService 从空壳真实化（PublicKey/PasswordCredentialEntry + CreateEntry + 锁库 Action + 5s 超时预算）；4 个 Launcher Activity（FLAG_SECURE + attestation/assertion 组装）；DomainMatcher 严格域名匹配根治跨域凭据泄露。
- **Wave 3（git a45bfa5）数据层完整性**：编辑保存完整保留（history/times/tags/图标/自定义字段含 Passkey + HistoryManager 快照）；KDBX 标准库内回收站（recycleBinUuid 落库 + DeletedObject 墓碑 + previousParentGroup 还原）；TOTP 真实化（KeyUri 解析 + RFC 6238 官方向量）；健康检查真实化（HealthCheckEngine 接线 + 评分公式）；SyncCoordinator 同步全链路接线（KDBX4 随机 IV 哈希漂移防抖）；同步凭据 Keystore AES-256-GCM 加密持久化；Unlock 密码 CharArray 边界加固；allowBackup=false + dataExtractionRules；修复 parseDate Base64 含 T 误判缺陷；proguard 包级 keep 扩展。
- **Wave 4（文档收口）**：文档如实化（本节）与存档清理。
- **Wave 5（已知限界清零）**：① KDBX 全链路流式化——读取侧 DOM 改 SAX 流式状态机（官方 `ReadXmlStreamed` / KeePassDX `readDocumentStreamed` 对齐），写入侧改 `KdbxXmlStreamWriter` 紧凑流式写出（官方 `WriteDocument` 对齐），`HmacBlockStream` 新增流式读写（逐块「边校验边交付」），`KdbxFile.load/save` 全管线（HMAC 块流→加密流→GZip→XML）不再物化整条密文/明文/压缩数据；旧派生（SHA-256 cipherKey）识别改为首块解密探针裁决（官方与旧派生 hmacKey64 相同，头部 HMAC 无法区分）。② S3 覆写 PUT 附带 `If-Match: "<etag>"` 条件头，服务端原子校验消除 HEAD+PUT TOCTOU（不支持条件写的兼容存储自动降级为旧行为）。③ Credential Provider 链式解锁（锁库 UX v2）——锁库 Action 指向新 `CredentialUnlockActivity`，解锁成功后经 `PendingIntentHandler.setBeginGetCredentialResponse` 直接回传凭据候选，系统随即继续呈现；候选组装抽取为共享 `CredentialResponseAssembler`。④ `SecurePasswordField` 安全输入组件 + 主密码 CharArray 全链路——`UnlockUiState` 不再持有 String 明文，显示 String 仅存活于组件内部（dispose 清零），CharArray 直达 ViewModel 并在成功/异常路径显式擦除。

**测试基线**：全工程 172 个单元测试全绿（app 65 / core 9 / crypto 34 / database 31 / sync 33）；`assembleDebug` 与 `assembleRelease`（R8 混淆）构建闭环通过。

**已知限界（如实记录，详见 `REMEDIATION_PLAN.md` 执行日志）**：KDBX 解析已流式化，但对象树（KdbxGroup/KdbxEntry）仍整体驻留内存（增量加载/进度 Flow 远期）；S3 条件写依赖服务端支持——AWS S3 原子生效，少数未实现 If-Match 覆写的兼容存储降级为 HEAD 预检+无条件 PUT（KDoc 注明）；Compose 框架层 TextField 仍以 String 承载输入（框架 API 限制，已收敛至 `SecurePasswordField` 单点、最短生命周期；EntryEdit/DatabasePicker/Settings 的密码框尚未接入该组件）；QuickUnlock PIN 仍为 String（4 位短数字，模拟解锁路径）。
