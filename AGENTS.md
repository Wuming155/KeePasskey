# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

> **双文档敏捷驱动体系**：
> 1. **现存问题与待办清单**：[**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) — 严格按优先级（P0 → P1 → P2 → P3）降序排列，自包含背景与验收标准，**拿起来直接做，无需额外计划文件**。
> 2. **已整改问题与历史归档**：[**docs/RESOLVED_LOG.md**](docs/RESOLVED_LOG.md) — 已完成修复的 49 项核心任务与 120+ 审查项代码证据。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | 代码基线 `7307f5f` → **P3 批次 16 项整体闭环**（ISSUE-P3-01 ~ P3-16：低危加固、特性接线与体验优化；含 4 个「假开关」实锤整改、13 条过程缺陷如实留痕） | 归档见 [RESOLVED_LOG.md](docs/RESOLVED_LOG.md) **§3**；未完全达标的残余面回登为 ISSUE-P3-17 ~ P3-28 |
| **测试基线** | **921 个单元测试用例**（app 416 / core 58 / crypto 61 / database 207 / sync 179）：**908 通过、0 失败、13 跳过**（另有 Rust 侧 `cargo test` 9 例，见 §5；crypto 另有 7 例 instrumented 测试，见下） | `.\gradlew.bat test --rerun-tasks` 强制真实执行全模块；13 例跳过为 `LiveSyncServersTest` 真实联调用例（12 例，需先起 `tools/local-sync` 服务并加 `-DliveSyncTest`）+ `SyncCacheTest` 的 Windows 无 POSIX 权限视图断言（1 例） |
| **instrumented 验证** | `crypto` 模块 `androidTest`：x86_64 模拟器（Android 16 / API 36）实测 **7 例 0 失败**——APK 内 `libkeepasskey_argon2.so`（477,976 B）运行时加载、`NativeArgon2.available == true`、与 BC 冻结向量逐字节一致；性能 p=2 **4.98×**、p=4 **8.42×** 于 BC，R1 闸门通过 | arm64 真机与真实 `.kdbx` 语料端到端解锁仍待办，见 ISSUE-P3-23 |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | **AGP 9.4.0 / Gradle 9.7.1** / Kotlin 2.4.10（经 buildscript classpath 锚定内置 KGP）/ Hilt 2.60.1 / **KSP 2.3.11** |
| **签名与 R8** | 关闭 v1、启用 **v3 + v4**（`.idsig` 产出）；未配置签名时构建不失败。R8 收窄后 dex 字符串表内源文件名 **12 → 0**，dex −196,816 B（−1.85%） | v2 配置为 true 但 v3 与 v2 同开且 minSdk ≥ 28 时产物省略 v2 块（AGP 标准行为，无功能缺口）；`-dontwarn **` 已移除且无缺失类。见 RESOLVED_LOG §3.2 |
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
   - **原生侧**：Argon2 KDF 的 password/salt/secret/AD/派生输出在 Rust 侧（`crypto/src/main/rust/`）经
     `Zeroizing` **RAII 全路径确定性擦除**（含错误提前返回路径），panic 经 `catch_unwind` 归一为返回 `null`；
     禁止再引入手写的 C/C++ 秘密缓冲管理（`malloc`/`free`/手动 wipe）。
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
| [**docs/扫码方案评估_ZXing与CameraXMLKit.md**](docs/扫码方案评估_ZXing与CameraXMLKit.md) | 扫码方案评估：**维持 zxing、转条件触发式迁移（T1~T6）** 的决策依据（实测体积/隐私/技术债 + 20 条来源） | **评估扫码依赖、或触发条件命中需迁移时** |
| [**docs/原生Argon2真机验证记录.md**](docs/原生Argon2真机验证记录.md) | Rust Argon2 原生内核的**设备侧验证记录**（环境探测、测试命令、性能数据、arm64 待填表） | **复核原生 KDF 性能与 R1 闸门时** |
| `.codebuddy/rules/engineering-rules.md` | **工程规则**：单一职责、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全 | **编写/修改任何代码前** |

---

## 5. 构建与测试命令

统一使用 Gradle Wrapper（**Gradle 9.7.1**，AGP 9.4.0 / Kotlin 2.4.10 / Hilt 2.60.1 / **KSP 2.3.11**，版本集中于 `gradle/libs.versions.toml`）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test`；当前 **921 例：908 通过 / 0 失败 / 13 跳过**，分布 app 416 / core 58 / crypto 61 / database 207 / sync 179，其中 12 例跳过项需 `-DliveSyncTest` 才启用，1 例为 Windows 无 POSIX 权限视图的缓存权限断言）
  - **加 `--rerun-tasks` 可强制真实执行**（否则 Gradle 可能以 UP-TO-DATE 跳过而不产生新证据）
  - **单会话内勿并发跑 Gradle**：多进程写同一 build 目录会互相截断产物，报 `java.io.EOFException` / `Kryo Buffer underflow` / `NoSuchFileException: in-progress-results-generic.bin`，或令 `app/build/generated/ksp/.../classes` 被并发删除。串行执行并加 `--max-workers=1` 可避免
- `.\gradlew.bat test -DliveSyncTest` — 追加启用 `LiveSyncServersTest` 真实联调用例（默认跳过 12 例，需先起 `tools/local-sync` 服务；联调凭据为**随机一次性口令**，经 Gradle 配置期求值一次后由 `systemProperty` 下发，服务端需注入同名 `WEBDAV_USER`/`WEBDAV_PASSWORD` 或 `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` — **instrumented 测试**（需先起模拟器/真机；当前 x86_64 模拟器 7 例全绿；arm64 待补，见 ISSUE-P3-23）
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包（签名配置见 `keystore.properties.example` / 环境变量，未配置时产出未签名包）
- **Rust 原生内核（ISSUE-P2-14 PoC Batch 1+）**：`cd crypto/src/main/rust && cargo test` — Rust Argon2 内核单测（当前 **9 例全绿**：IETF 官方 KAT ×4 + BC 冻结向量等价 + 参数闸门 + 确定性 + JNI 签名/闸门）

> **原生构建前置（ISSUE-P2-14 PoC Batch 3 起，替代原 TASK-52 CMake 方案）**：crypto 模块的 Argon2 原生内核改由 **Rust + cargo-ndk 从源码交叉编译**（`crypto/src/main/rust/`，产出 4 ABI `libkeepasskey_argon2.so`）。前置工具链：
> - **NDK 28.2.13676358**（`sdkmanager "ndk;28.2.13676358"`；AGP strip + cargo-ndk 链接器共用）；
> - **Rust stable + 4 个 Android target**：`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`；
> - **cargo-ndk**：`cargo install cargo-ndk`（Gradle task `:crypto:cargoNdkBuild` 经 `ANDROID_NDK_HOME` 调用，`assembleDebug/Release` 自动触发；纯 `test` 不触发）。
>
> **宿主侧原生验证（Batch 4 起）**：`.\gradlew.bat :crypto:test` 会先跑 `:crypto:cargoHostBuild`
> （`cargo build --release`，产物 `build/rust/host/release/`）并经 `-Djava.library.path` 注入单测 JVM，
> 使桌面单测也能加载原生库、真实覆盖 JNI 调用路径；**未安装 cargo 或构建失败时自动降级为跳过相关用例**（不阻断）。
>
> **供应链**：`cd crypto/src/main/rust && cargo deny check`（需 `cargo install cargo-deny`；`deny.toml` 已入库）。
>
> 原 `crypto/src/main/cpp/`（vendored PHC C + JNI 桥）已于 **Batch 5 `git rm`**（git 历史可回溯），国内网络可经 `RUSTUP_DIST_SERVER=https://rsproxy.cn` 加速 target 下载。

---

## 6. 已知工程限界

- **KDBX 对象树仍整体驻留内存**：解析已流式化，但 `KdbxGroup`/`KdbxEntry` 树仍在内存（增量加载/进度 Flow 为远期项）。
- **条件写依赖服务端**：AWS S3 原子生效，少数未实现 `If-Match` 覆写的兼容存储降级为 HEAD 预检 + 无条件 PUT；WebDAV `uploadAtomic` 预条件在个别极简 DAV 服务端可能被忽略。
- **`ProtectedString` 驻留加密为纵深防御层**：对抗堆扫描与崩溃转储中的明文暴露；取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文。
- **原生 Argon2 为 Rust 内核（体积代价）**：4 ABI 各含 Rust std + rayon + blake2，strip 后 `.so` 约 313~506KB/ABI（原 C 内核约 18~22KB/ABI）；收益是秘密确定性擦除与**设备侧实测 4.98×~8.42× 于 BC** 的派生速度（x86_64 模拟器，t=2/m=64MiB）。arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁待补（ISSUE-P3-23）。
- **`System.loadLibrary` 的顺序依赖**：原生库仅在 `NativeArgon2.available` 的 `by lazy` 内加载，而 `deriveKey` 是 `external fun` → **在 `available` 求值前直接调用 `deriveKey` 会抛 `UnsatisfiedLinkError`**。生产路径不受影响（唯一消费方 `Argon2KdfEngine.transform` 必然先求值 `available`，且 `derive` 已 catch 该错误归一为 `KdfException`）；新增消费方须先判 `available` 或在入口自行确保加载。
- **窗口级遮挡触摸过滤的覆盖范围**：`FlagSecureGuard.applyObscuredTouchFilter` 作用于 `MainActivity` 的 `decorView`，Android 触摸分发取「最近带该标志的祖先」，故其**全部子屏（含各 ComposeView）已被窗口级覆盖**；真正需要单独接线的是**不经 `MainActivity` 的独立窗口**（如 `BaseCredentialActivity` 系）。另注意该标志**只作用于触摸分发路径**，不拦截无障碍 `ACTION_CLICK`/`performClick`。
- **浏览器特权白名单**：内置 Chrome 稳定版签名指纹，证书轮换或白名单外浏览器 fail-closed 降级为 apk-key-hash 路径。
- **外部库导入策略**：经导入复制进内部存储后原地编辑（不写回外部原文件），为当前设计取舍。
