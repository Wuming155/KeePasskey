# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

> **实时状态与任务唯一看板**：详见 [**docs/STATUS.md**](docs/STATUS.md)。
> 所有未完成任务、当前版本基线、历史改动索引与已完成发现项跟踪均以 `STATUS.md` 为单一真相源（Single Source of Truth）。

---

## 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 `.kdbx`（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

---

## 硬约束（任何操作都必须严格遵守）

1. **模块依赖严格单向**，禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 源码目录执行无目标的全局 `grep`、`glob` 或大面积扫源码；
   - 5 个参考项目均已完成详尽的架构分析，集中存放在 **`.codebuddy/skills/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **核心参考**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database`/`crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **次核心参考**：**keepass2android** — 重点参考云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制与冲突合并；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — `.kdbx` 格式（v4）事实标准，仅在文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作终极裁决；
     4. ⚖️ **算法级参考**：**KeePassXC (C++/Qt)** — `Merger` 条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考，`KPEX_PASSKEY_*` 属性 schema 对照 `PasskeyData` 设计；
     5. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作现代 Compose UI/UX 与本地优先思路的补充对照；
   - **凡涉及实现思路借鉴，必须先读对应架构分析文档，严禁直接翻原始代码**；
   - 严禁修改 `参考项目/` 下任何文件，严禁复制其代码入库（许可证约束）。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、 Result 错误处理等）详见 `.codebuddy/rules/engineering-rules.md`，写代码前必须遵守。
5. **单一真相源维护纪律**：**新增或修改任务必须在 `docs/STATUS.md` 统一注册与更新**，保持状态看板实时准确。

---

## 详细文档索引

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| [**docs/STATUS.md**](docs/STATUS.md) | **单一真相源**：当前基线、未完成任务唯一看板（21项）、历史提交日志索引 | **开始任何工作前、检查进度时** |
| [**docs/FINDINGS_TRACKER.md**](docs/FINDINGS_TRACKER.md) | **历史审查发现跟踪表**：131 项发现的物理核对状态与代码证据 | **确认历史 Bug 是否已修时** |
| `.codebuddy/rules/engineering-rules.md` | 工程规则：单一职责、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全 | **编写/修改任何代码前** |
| `.codebuddy/skills/architecture.md` | 5 模块职责与依赖规则、关键架构决策，另见 `ARCHITECTURE.md` | **跨模块改动、新增功能落位前** |
| `.codebuddy/skills/reference-projects.md` | 参考项目地图：各功能应参照哪个项目的哪些文件 | **实现算法/格式兼容时** |

---

## 构建与测试命令

统一使用 Gradle Wrapper（**Gradle 9.3.1**，AGP 9.1.0 / Kotlin 2.4.10 / Hilt 2.60.1，kapt）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test`；当前 **417 例全绿**）
- `.\gradlew.bat test -DliveSyncTest` — 追加启用 `LiveSyncServersTest` 真实联调用例（默认跳过 12 例，需先起 `tools/local-sync` 服务）

---

## 已知工程限界

- **KDBX 对象树仍整体驻留内存**：解析已流式化，但 `KdbxGroup`/`KdbxEntry` 树仍在内存（增量加载/进度 Flow 为远期项）。
- **条件写依赖服务端**：AWS S3 原子生效，少数未实现 `If-Match` 覆写的兼容存储降级为 HEAD 预检 + 无条件 PUT；WebDAV `uploadAtomic` 预条件在个别极简 DAV 服务端可能被忽略。
- **`ProtectedString` 驻留加密为纵深防御层**：对抗堆扫描与崩溃转储中的明文暴露；取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文。
- **浏览器特权白名单**：内置 Chrome 稳定版签名指纹，证书轮换或白名单外浏览器 fail-closed 降级为 apk-key-hash 路径。
- **外部库导入策略**：经导入复制进内部存储后原地编辑（不写回外部原文件），为当前设计取舍。
