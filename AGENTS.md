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
   - 4 个参考项目均已完成详尽的架构分析，集中存放在 **`.codebuddy/skills/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **第 1 优先级（核心参考）**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database` / `crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **第 2 优先级（次核心参考）**：**keepass2android** — 重点参考其云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制、三方哈希冲突检测与 Quick Unlock 快速解锁；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — 作为 `.kdbx` 格式（v3/v4）的官方事实标准，仅在遇到文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作为终极裁决标准；
     4. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作为现代 Compose UI/UX 风格与本地优先思路的补充对照，不作为核心实现重点；
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
- `.\gradlew.bat test` — 单元测试（目前尚无 `src/test`，规划中）
- 版本升级需整体配套：AGP ↔ Gradle ↔ Kotlin ↔ Compose BOM（Compose BOM 2026.06.00+ 要求 compileSdk 37，当前用 2026.06.01 对齐 compileSdk 36）

**当前阶段状态**：**阶段 2「密码学核心与 KDBX 数据库引擎」已圆满完成**（全量实现 `core` 领域模型与安全内存、`crypto` 对称加密/Argon2/AES-KDF/流加密、`database` KDBX v4 二进制/XML 解析写回、`DatabaseSession` 状态机与原子写盘，并通过 `RealVaultRepository` 对接 Hilt，模块单元测试全量通过）。**下一次交互正式进入阶段 3「系统级生物识别与防御性安全加固」**：
- 重点任务：实现 AndroidX Biometric 指纹/面容快速解锁，结合 Android Keystore 硬件安全密钥管理主密码派生；
- 防御性加固：落地主界面与凭据页 `FLAG_SECURE` 防截屏与多任务侧漏守卫、系统剪贴板 `EXTRA_IS_SENSITIVE` 标记与后台倒计时自动抹除、Auto-Lock 后台超时熔断。
