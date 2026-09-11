# AGENTS.md

本文件为 AI 编码代理提供工作指引。

> **双文档敏捷驱动体系**：
> 1. **待办清单**：[`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) — 按优先级（P0 → P1 → P2 → P3）降序排列，自包含背景与验收标准，**拿起来直接做，无需额外计划文件**。
> 2. **历史归档**：[`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) — 已整改任务与批次验收证据。

---

## 1. 版本基线（摘要）

- 测试 / 构建 / CI 当前全绿（具体版本、例数、残余面见 [`RESOLVED_LOG.md`](docs/RESOLVED_LOG.md)）。
  单测基线（2026-09-11，§27 批次后）：**1382 例 / 0 失败 / 0 错误 / 13 跳过**；写侧 Argon2 `P`
  已按 KDBX4 规范以 UInt32 编码（官方 `generate_corpus.py --verify` 可解本仓产物）。
- **平台**：minSdk 36 / compileSdk 37 / targetSdk 36；全站强制 HTTPS（TLS-only），零证书固定，接入 Mozilla PSL。

---

## 2. 项目概述

KeePasskey 是一款原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 `.kdbx`（v4）格式，内置 WebDAV 与 S3 兼容同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

---

## 3. 硬约束与极简闭环纪律（规则，须继承）

1. **模块依赖严格单向**，禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
   - 原生侧 `crypto/src/main/rust/` 四个内核（Argon2 / AES-KDF / Twofish-CBC / 口令强度）敏感缓冲经 `Zeroizing` RAII 全路径确定性擦除；禁止再引入手写 C/C++ 秘密缓冲管理。
   - JNI 定长布局契约：跨 FFI 只传基本类型与数组；口令强度评估返回定长 3 元 `IntArray [score, log10×100, flags]`，`FLAG_*` 位值在 Rust 与 Kotlin 两侧逐位对齐。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 目录无目标 `grep`/扫源码；5 个参考项目的架构分析集中在 `docs/references/`。
   - 优先级层级（严格遵照）：🥇 KeePassDX（核心参考）/ 🥈 keepass2android（云同步）/ ⚖️ KeePass-2.61.1 官方 C#（格式裁决者）/ ⚖️ KeePassXC（合并与 Passkey schema）/ 🥉 Monica（UI 补充）。
   - 凡借鉴实现思路，先读对应架构分析文档；严禁修改或复制 `参考项目/` 下代码（许可证约束）。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、Result 错误处理、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全）见 `.codebuddy/rules/engineering-rules.md`，写代码前必须遵守。
5. **无需中间计划文件**：严禁创建冗余 plan 文档；任务背景与验收标准直接在 `ACTIVE_ISSUES.md` 内自包含维护。
6. **极简闭环工作流（认领 → 整改+验证 → 流转归档 → 提交推送）**：
   1. **认领**：从 `ACTIVE_ISSUES.md` 顶部按优先级认领；发现新问题即时补登（**严禁只记聊天或脑中**）。认领后先复核条目前提（路径/行号/消费方是否仍成立），新条目须附「核实时间点」与「核实方式」。
   2. **整改 + 验证**：修改代码，且 `.\gradlew.bat test` 全绿（含相关回归）方准入库。
   3. **流转归档**：整条从 `ACTIVE_ISSUES.md` **剪切移入** `RESOLVED_LOG.md`；基线变动同步更新本文件 §1。
   4. **提交推送**：文档与代码**同一次 `git commit`**，并**立即 `git push`**。提交信息以 `TASK-xx` / `ISSUE-xx` 引用任务并简述主题。
7. **善用 MCP 知识服务器辅助开发（强制）**：遇到以下情形时，**优先调用对应 MCP 服务器**获取权威、时效性强的资料，不得仅凭记忆臆测或盲改：
   - **Google 知识 MCP 服务器**（`google-developer-knowledge`）：凡涉及 Android / Jetpack / Kotlin / Gradle / 加密库 / Google 平台 API、SDK 用法、版本兼容、官方最佳实践等，先向其检索确认。
   - **Context7 MCP 服务器**：涉及第三方库、依赖、框架（如 Compose / Hilt / OkHttp / 加密库等）的 API 用法、版本特性与最佳实践时，先用 `resolve-library-id` + `query-docs` 检索权威文档与官方示例，避免凭旧记忆编写。
   - 调用前先用 `mcp_get_tool_description` 取得该服务器各工具的最新参数 schema，再发起调用；结果用于指导代码与文档，不改变本文件 §3 其余硬约束。

---

## 4. 文档索引

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) | 现存问题与待办清单（P0→P3） | 认领与开始新工作前 |
| [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) | 已整改任务与历史批次证据 | 确认历史 Bug 是否已修 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块依赖拓扑与关键架构决策 | 跨模块改动、新功能落位前 |
| [`docs/reference-projects.md`](docs/reference-projects.md) | 参考项目地图 | 实现算法/格式兼容时 |
| [`docs/references/`](docs/references/) | 5 个参考项目架构分析 | 实现思路借鉴前 |
| `.codebuddy/rules/engineering-rules.md` | 工程规则 | 编写/修改任何代码前 |

---

## 5. 构建与测试命令

统一用 Gradle Wrapper（版本见 §1）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat lint` — Android Lint（warning 不阻断）
- `.\gradlew.bat test --rerun-tasks --max-workers=1` — 单元测试（强制真实执行，单会话勿并发）
- `.\gradlew.bat test -DliveSyncTest` — 追加真实联调（需先起 `tools/local-sync`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` / `:database:connectedDebugAndroidTest` — instrumented 测试（需设备）
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包
- `python .github/check_dependency_cvss.py build/reports/dependency-check/dependency-check-report.json` — 供应链 CVSS ≥ 7.0 硬断言（fail-closed）
- `cd crypto/src/main/rust && cargo test` — 原生内核单测
- `python tools/kdbx-corpus/generate_corpus.py --check` — `.kdbx` 语料校验

> 原生内核（`crypto/src/main/rust/`）由 Rust + cargo-ndk 交叉编译，`assembleDebug/Release` 自动触发；未装 cargo 或失败则自动降级跳过相关用例（`test` 不触发）。
>
> **设备侧（instrumented）用例注意（2026-09-11 实测基线）**：需先启动 AVD 或接真机。
> 真实互操作语料已入库（`ISSUE-P3-23` AC② 闭环，见 `docs/RESOLVED_LOG.md` §25），
> `RealKdbxCorpusUnlockTest` 现为 **2/2 pass**。**若语料被移除**，该用例抛 `AssumptionViolatedException`：
> task 级仍 `BUILD SUCCESSFUL`，但 AGP 生成的 `build/outputs/androidTest-results/connected/debug/TEST-*.xml`
> 会把它 **记为 `<failure>`（`skipped=0`）**——别据此误判为用例失败；判定以 **task 结果**为准
> （详见 `docs/RESOLVED_LOG.md` §24.4）。设备侧用例是**唯一**能覆盖 Android 运行时差异的层
> （§24 的致命缺陷即由它发现，JVM 侧无法复现）。
>
> **Rust 单测落位约定（ISSUE-P3-57，须遵守）**：单测一律放
> `crypto/src/main/rust/src/tests/<name>_tests.rs`，并在源文件中以
> `#[cfg(test)] #[path = "tests/<name>_tests.rs"] mod tests;` 引用。
> 原因：Code scanning 走仓库自管的 advanced setup（`.github/workflows/codeql.yml`，
> 配置见 `.github/codeql/codeql-config.yml`），其 `paths-ignore` **只能做文件级排除**，
> 而 `rust/hard-coded-cryptographic-value` **没有任何测试代码过滤**——**内联** `mod tests`
> 里的测试密钥/向量会被逐条报为 critical 误报（曾一次性产出 77 条）。

---

## 6. 已知工程限界

- KDBX 对象树仍整体驻留内存（解析已流式化）。
- 条件写依赖服务端：AWS S3 原子生效；少数兼容存储降级为 HEAD 预检 + 无条件 PUT。
- `ProtectedString` 驻留加密为纵深防御层；持有进程密钥或任意代码执行者仍可在读取瞬间截获明文。
- 原生内核为 Rust（代价是体积，收益是秘密确定性擦除、Argon2 优于纯 Java 路径）；arm64 真机 + 真实 `.kdbx` 端到端解锁待补（见 ACTIVE_ISSUES）。
- 原生侧 `System.loadLibrary` 经 `NativeCryptoLibrary.loaded` 统一懒加载；各绑定 `available` 须先求值该属性再发起原生调用。
- `CipherInputStream` 对填充非法/长度非整数倍抛 `IOException`（非静默 EOF）；新增 CBC 流式实现须遵守同一基线。
- 窗口级遮挡触摸过滤作用于 `MainActivity` 的 `decorView`；独立窗口（如 `BaseCredentialActivity` 系）需单独接线。
- **`app` / `sync` 模块无 `androidTest` 源集**：设备侧（instrumented）覆盖目前只有 `crypto` / `database` 的库内
  逻辑用例，`app` 端到端功能（UI、自动填充、Passkey、通知等）仅由宿主 JVM 单测覆盖。§24 的 ISSUE-P1-12 与
  §26 的 ISSUE-P0-04 均属「JVM 过、Android 运行时挂」类缺陷逃逸，故**涉及正则 / XML / 平台 API 的静态逻辑
  不能仅凭宿主单测判定在 Android 上可用**；收窄该缺口需为 `app` 关键流程补设备侧用例。
