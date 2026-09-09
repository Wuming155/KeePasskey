# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

> **双文档敏捷驱动体系**：
> 1. **现存问题与待办清单**：[**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) — 严格按优先级（P0 → P1 → P2 → P3）降序排列，自包含背景与验收标准，**拿起来直接做，无需额外计划文件**。
> 2. **已整改问题与历史归档**：[**docs/RESOLVED_LOG.md**](docs/RESOLVED_LOG.md) — 已完成修复的 49 项核心任务与 120+ 审查项代码证据。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | 代码基线 `4235f16`（TASK-53 Base64/Hex 整洁度） | 分支 `main` 与 `origin/main` 同步 |
| **测试基线** | **514 个单元测试用例**（app 154 / core 32 / crypto 52 / database 155 / sync 121，其中 sync 121 含 12 例联调跳过）：**502 通过、0 失败、12 跳过** | `./gradlew test` 全模块执行；跳过的 12 例为 `LiveSyncServersTest` 真实联调用例（需先起 `tools/local-sync` 服务并加 `-DliveSyncTest`） |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | **AGP 9.4.0 / Gradle 9.7.1** / Kotlin 2.4.10（经 buildscript classpath 锚定内置 KGP）/ Hilt 2.60.1 / **KSP 2.3.11** |
| **系统基线** | **minSdk 36**, **compileSdk 37**, targetSdk 36 | 仅针对 Android 16+ 深度优化，固化无旧版垫片决策；compileSdk 37（Compose BOM 2026.08.00 + M3 Expressive） |
| **传输安全防线** | 全站强制 HTTPS（`network_security_config.xml` 禁明文 + OkHttp TLS-only），零证书固定 | 对齐 Google Developer Knowledge `pinning not recommended` 指南 |
| **PSL 与域名匹配** | 完整接入 Mozilla PSL（`public_suffix_list.dat`），IDN punycode 归一 | 消除 47 条硬编码漏判盲区，fail-closed |

---

## 2. 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 `.kdbx`（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

---

## 3. 硬约束与极简闭环纪律

1. **模块依赖严格单向**，禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 源码目录执行无目标的全局 `grep`、`glob` 或大面积扫源码；
   - 5 个参考项目均已完成详尽的架构分析，集中存放在 **`docs/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **核心参考**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database`/`crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **次核心参考**：**keepass2android** — 重点参考云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制与冲突合并；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — `.kdbx` 格式（v4）事实标准，仅在文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作终极裁决；
     4. ⚖️ **算法级参考**：**KeePassXC (C++/Qt)** — `Merger` 条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考，`KPEX_PASSKEY_*` 属性 schema 对照 `PasskeyData` 设计；
     5. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作现代 Compose UI/UX 与本地优先思路的补充对照；
   - **凡涉及实现思路借鉴，必须先读对应架构分析文档，严禁直接翻原始代码**；
   - 严禁修改 `参考项目/` 下任何文件，严禁复制其代码入库（许可证约束）。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、 Result 错误处理等）详见 `.codebuddy/rules/engineering-rules.md`，写代码前必须遵守。
5. **无需中间计划文件（直接看 ACTIVE_ISSUES）**：
   - **严禁创建冗余的 plan 计划文档**；所有任务的背景、整改依据、涉及文件与验收标准直接在 `docs/ACTIVE_ISSUES.md` 内自包含维护。
6. **极简闭环工作流（认领 → 整改+验证 → 流转归档 → 提交推送）**：
   1. **认领**：从 [**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) 顶部按优先级（P0 → P1 → P2 → P3）认领待办事项；若发现新问题，即时按优先级格式补登至 `ACTIVE_ISSUES.md`（**严禁只记聊天或脑中**）；
   2. **整改 + 验证**：修改代码，且 `.\gradlew.bat test` 全绿（含相关回归用例）方准入库；
   3. **流转归档**：将该条目从 `docs/ACTIVE_ISSUES.md` **剪切移入** [**docs/RESOLVED_LOG.md**](docs/RESOLVED_LOG.md)；若测试用例数或基线发生变动，同步更新本文件 §1 基线；
   4. **更新记录并推送**：文档与代码**同一次 `git commit`**，并**立即 `git push`**。
   > 提交信息遵循约定：以 `TASK-xx` / `ISSUE-xx` 引用任务并简述主题，例如 `fix(ISSUE-P0-01): 将 AutoLockManager 下沉至 MainApplication`。

---

## 4. 详细文档索引

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| [**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) | **现存问题与待办清单**：按 P0 → P1 → P2 → P3 降序排列，自包含背景与验收标准 | **认领与开始任何新工作前** |
| [**docs/RESOLVED_LOG.md**](docs/RESOLVED_LOG.md) | **已整改问题与历史任务归档**：已完成任务与 120+ 审查项代码证据 | **确认历史 Bug 是否已修、归档已完成工作时** |
| [**docs/ARCHITECTURE.md**](docs/ARCHITECTURE.md) | 模块依赖拓扑、关键架构决策与目录约定 | **跨模块改动、新增功能落位前** |
| [**docs/reference-projects.md**](docs/reference-projects.md) | 参考项目地图：各功能应参照哪个项目的哪些文件 | **实现算法/格式兼容时** |
| [**docs/references/**](docs/references/) | 5 个参考项目架构分析（KeePassDX / keepass2android / KeePass-2.61.1 / KeePassXC / Monica） | **实现思路借鉴前** |
| [**docs/KDBX4与复合密钥实战互操作排查日志.md**](docs/KDBX4与复合密钥实战互操作排查日志.md) | KDBX4 + 复合密钥（密码+KeyFile）真机互操作排查记录 | **排查 KDBX 解析/密钥兼容性时** |
| `.codebuddy/rules/engineering-rules.md` | **工程规则**：单一职责、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全 | **编写/修改任何代码前** |

---

## 5. 构建与测试命令

统一使用 Gradle Wrapper（**Gradle 9.7.1**，AGP 9.4.0 / Kotlin 2.4.10 / Hilt 2.60.1 / **KSP 2.3.11**，版本集中于 `gradle/libs.versions.toml`）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test`；当前 **514 例：502 通过 / 0 失败 / 12 跳过**，分布 app 154 / core 32 / crypto 52 / database 155 / sync 121，跳过项需 `-DliveSyncTest` 才启用）
- `.\gradlew.bat test -DliveSyncTest` — 追加启用 `LiveSyncServersTest` 真实联调用例（默认跳过 12 例，需先起 `tools/local-sync` 服务）
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包（签名配置见 `keystore.properties.example` / 环境变量，未配置时产出未签名包）

> **原生构建前置（TASK-52 起）**：crypto 模块含 NDK 原生构建（Argon2 官方参考实现，`crypto/src/main/cpp/`），需 **NDK 28.2.13676358 + CMake 3.22.1**（`sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`），缺失时 Gradle 配置阶段即报错。

---

## 6. 已知工程限界

- **KDBX 对象树仍整体驻留内存**：解析已流式化，但 `KdbxGroup`/`KdbxEntry` 树仍在内存（增量加载/进度 Flow 为远期项）。
- **条件写依赖服务端**：AWS S3 原子生效，少数未实现 `If-Match` 覆写的兼容存储降级为 HEAD 预检 + 无条件 PUT；WebDAV `uploadAtomic` 预条件在个别极简 DAV 服务端可能被忽略。
- **`ProtectedString` 驻留加密为纵深防御层**：对抗堆扫描与崩溃转储中的明文暴露；取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文。
- **浏览器特权白名单**：内置 Chrome 稳定版签名指纹，证书轮换或白名单外浏览器 fail-closed 降级为 apk-key-hash 路径。
- **外部库导入策略**：经导入复制进内部存储后原地编辑（不写回外部原文件），为当前设计取舍。
