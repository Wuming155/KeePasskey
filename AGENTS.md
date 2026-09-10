# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

> **双文档敏捷驱动体系**：
> 1. **现存问题与待办清单**：[**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) — 严格按优先级（P0 → P1 → P2 → P3）降序排列，自包含背景与验收标准，**拿起来直接做，无需额外计划文件**。
> 2. **已整改问题与历史归档**：[**docs/RESOLVED_LOG.md**](docs/RESOLVED_LOG.md) — 已完成修复的 49 项核心任务与 120+ 审查项代码证据。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | 代码基线 `9c2a806` → `df9d20b` → `8840feb` → **P3 残余批次整改**（ISSUE-P3-17 ~ P3-28：**10 项完整闭环 + 2 项部分达标**；含 1 处全框架致命缺陷、9 处生产代码缺陷、15 条过程缺陷与事实修正如实留痕）→ **ISSUE-P3-30 单条批次**（子库条目只读投影接入库列表：**闭环归档**，含 2 条过程缺陷如实留痕）→ **ISSUE-P3-29 批次 A**（全仓超阈值债务：正文点名的**优先级 8 项全部降至 400 行阈值内** + 增量完成 2 项 + 1 项纯常量例外登记；**纯结构性拆分零行为变更**，含 8 条过程缺陷如实留痕）→ **CI 首跑实测整改**（`Fast gate` **147 个 lint error 清零** + CodeQL 10 条告警处置 + **实证** `dependency-scan` 的 CVSS 阻断语义静默失效并补硬断言，含 7 条过程缺陷如实留痕）→ **供应链残余处置**（Kotlin **2.4.10 → 2.4.20** 真修复 `CVE-2026-53914`；4 族豁免登记并**由本地真实扫描校验**——含 1 条「豁免文件 schema 非法」过程缺陷）→ **合并 PR #5**（6 个 Action 跨大版本升级，squash `a9b838f`）→ **原生内核扩展与工程化批次**（ISSUE-P3-34 ~ P3-38：**5 项全部闭环**——AES-KDF 原生内核 / Twofish 原生内核（CBC+PKCS7 流式）/ 口令强度评估原生引擎 / `HmacBlockStream` 摘要收敛 / `.kdbx` 语料生成脚本自动化；含 **1 处生产缺陷、1 处安全校验冒充漏洞、1 处文档与实测相反的事实修正**，共 **9 条过程缺陷与 3 项未验证项**如实留痕）→ **自动填充能力对标批次**（ISSUE-P3-39 ~ P3-45：**6 项闭环 + 1 项部分达标**——字段识别与候选打分 / 手动选择器 / 服务健康自检 / 会话授权宽限 / 保存开关真实接线与评估 / 结构化数据评估结论；P3-43 保留②③待办；顺带修复 **2 处无消费方的假开关**，共 **5 条过程缺陷与事实修正**如实留痕）→ **ISSUE-P3-31 批次 B**（残余超阈值债务：`RealVaultRepository` **1090 → 372**、`DatabasePickerScreen` **968 → 319**，共拆出 9 个同包单元；**纯结构性拆分零行为变更**，清零点 9→9 / UI 4→4 逐条对齐、公开 API diff 为空、**1291 例零退化**，含 3 条过程缺陷如实留痕） | 归档见 [RESOLVED_LOG.md](docs/RESOLVED_LOG.md) **§4**（P3-17~P3-28）· **§5**（P3-30）· **§6**（P3-29 批次 A）· **§7**（CI 首跑实测整改 + 供应链处置）· **§8**（P3-34~P3-38）· **§10**（P3-31 批次 B）；残余面为 ISSUE-P3-23 / P3-24 / **P3-31（残余 21 项）** / **P3-32** / **P3-43** |
| **测试基线** | **1291 个单元测试用例**（app 712 / core 58 / **crypto 107** / **database 235** / sync 179）：**1278 通过、0 失败、13 跳过**（基线 921 → **+370 例，零退化**）（另有 Rust 侧 `cargo test` **43 例**，见 §5；crypto 另有 7 例 instrumented 测试，见下） | `.\gradlew.bat test --rerun-tasks --max-workers=1` 强制真实执行全模块；13 例跳过为 `LiveSyncServersTest` 真实联调用例（12 例，需先起 `tools/local-sync` 服务并加 `-DliveSyncTest`）+ `SyncCacheTest` 的 Windows 无 POSIX 权限视图断言（1 例）。**本批次起 `database` 模块单测也注入宿主原生库**（`database/build.gradle.kts`），故标注 `Assume` 的原生差分类用例在桌面**不被跳过** |
| **instrumented 验证** | `crypto` 模块 `androidTest`：x86_64 模拟器（Android 16 / API 36）实测 **7 例 0 失败**——APK 内 `libkeepasskey_argon2.so`（477,976 B）运行时加载、`NativeArgon2.available == true`、与 BC 冻结向量逐字节一致；性能 p=2 **4.98×**、p=4 **8.42×** 于 BC，R1 闸门通过。**`database` 模块本批次首次建立 `androidTest` 源集**（真实 `.kdbx` 语料端到端解锁用例，fail-closed：语料缺失即显式跳过、跳过不等于通过） | **⚠️ 该 7 例早于 ISSUE-P3-34~38**，故当时 `.so` 内**尚无** AES-KDF / Twofish / 口令强度三个内核；其**设备侧**验证仍未做（本机无设备/模拟器），宿主 JNI 差分证据见 RESOLVED_LOG **§8**。arm64 真机与真实 `.kdbx` 语料端到端解锁仍待办，见 ISSUE-P3-23 |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | **AGP 9.4.0 / Gradle 9.7.1** / Kotlin **2.4.20**（经 buildscript classpath 锚定内置 KGP；`ISSUE-P3-32` 为修复 `CVE-2026-53914` 由 2.4.10 升至此版）/ Hilt 2.60.1 / **KSP 2.3.11** |
| **CI 实测状态** | `build.yml` 三 job **已在 GitHub 托管 runner 上真实运行**：`Rust supply chain` ✅ success / `Native gate` ✅ success / `Fast gate` ❌ failure → **✅ 已整改并在 CI 复跑通过**（首跑 147 个 **Android Lint** error 已清零；运行 `34470024328` 三 job 全绿、工作流 exit 0）。另**实证** `dependency-scan` 的 `failBuildOnCVSS = 7.0` 在 `dependencyCheckAggregate` 上**不生效**（报告含 138 条 CVSS ≥ 7.0 仍 `BUILD SUCCESSFUL`），已补硬断言 `.github/check_dependency_cvss.py`，并**已接线为 `dependency-scan.yml` 的独立门禁步骤**（提交 `37e609d`）；**PR #5（6 个 Action 跨大版本升级）已于 `a9b838f` 合并**——现为 `checkout v7.0.1` / `setup-java v6.0.0` / `setup-gradle v6.3.0` / `upload-artifact v7.0.1` / `setup-android v4.0.1` / `upload-sarif v4.37.9` | 实测依据 `gh run view 34463116293` / `34335443660` / `34470024328`；详见 [ci-静态校准记录 §11](docs/ci-静态校准记录.md)、[RESOLVED_LOG §7](docs/RESOLVED_LOG.md) |
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
   - **原生侧**：`crypto/src/main/rust/` 内**全部四个内核**（Argon2 / AES-KDF / Twofish-CBC / 口令强度）
     的敏感缓冲（password / salt / secret / AD / 明文 / 分组缓冲 / 派生输出 / 链值）经
     `Zeroizing` **RAII 全路径确定性擦除**（含错误提前返回路径），panic 经 `catch_unwind` 归一为返回 `null`；
     分组密码对象因 `Cargo.toml` 开启 `zeroize` 特性其**密钥调度随析构归零**；
     禁止再引入手写的 C/C++ 秘密缓冲管理（`malloc`/`free`/手动 wipe）。
   - **JNI 定长布局契约**：跨 FFI 只传基本类型与数组（不传结构体、不传浮点）；
     口令强度评估的返回值为**定长 3 元 `IntArray`**（`[score, log10×100, flags]`），
     `FLAG_*` 位值在 Rust `strength.rs` 与 Kotlin `PasswordStrengthFlags` **两侧逐位对齐**
     （由 `PasswordStrengthTest` 以原生返回的真实位值锁定）。
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
   1. **认领**：从 [**docs/ACTIVE_ISSUES.md**](docs/ACTIVE_ISSUES.md) 顶部按优先级（P0 → P1 → P2 → P3）认领待办事项；若发现新问题，即时按优先级格式补登至 `ACTIVE_ISSUES.md`（**严禁只记聊天或脑中**）。
      - **前提复核（ISSUE-P3-28 起强制）**：认领后先复核条目正文的现状前提（路径是否存在、行号是否漂移、消费方是否已出现），前提已不成立的就地修正或标注后再动手；
      - **新条目须附「核实时间点」与「核实方式」**（如「2026-09-10 经 `Test-Path` / 全仓 grep 核实」），详见 `ACTIVE_ISSUES.md`「条目维护规则」；
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
| [**tools/kdbx-corpus/README.md**](tools/kdbx-corpus/README.md) | `.kdbx` 互操作语料**生成/校验脚本**：五模式用法、退出码、KDF 参数核验、双落位与能力限制 | **生成或复核 ISSUE-P3-23 语料时** |
| `.codebuddy/rules/engineering-rules.md` | **工程规则**：单一职责、敏感数据、Compose 规范、原子写盘、协程调度、防御性安全 | **编写/修改任何代码前** |

---

## 5. 构建与测试命令

统一使用 Gradle Wrapper（**Gradle 9.7.1**，AGP 9.4.0 / Kotlin **2.4.20** / Hilt 2.60.1 / **KSP 2.3.11**，版本集中于 `gradle/libs.versions.toml`）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint（全模块；当前 `app`/`core`/`crypto`/`database`/`sync` **各 0 error**，186 warnings / 2 hints 不阻断；其中 **186 条全部在 `app`**，`crypto` 已于 ISSUE-P3-34~38 批次**清零**）
- `.\gradlew.bat test` — 单元测试（全模块 `src/test`；当前 **1291 例：1278 通过 / 0 失败 / 13 跳过**，分布 app 712 / core 58 / **crypto 107** / **database 235** / sync 179，其中 12 例跳过项需 `-DliveSyncTest` 才启用，1 例为 Windows 无 POSIX 权限视图的缓存权限断言）
  > 数值口径修正（2026-09-10 实测）：此处原记 1257 例 / app 678，系更早快照；现按各模块
  > `build/test-results/testDebugUnitTest/*.xml` 实测汇总为 **1291 例 / app 712**，与 §1 基线一致。
  - **加 `--rerun-tasks` 可强制真实执行**（否则 Gradle 可能以 UP-TO-DATE 跳过而不产生新证据）
  - **单会话内勿并发跑 Gradle**：多进程写同一 build 目录会互相截断产物，报 `java.io.EOFException` / `Kryo Buffer underflow` / `NoSuchFileException: in-progress-results-generic.bin`，或令 `app/build/generated/ksp/.../classes` 被并发删除。串行执行并加 `--max-workers=1` 可避免
- `.\gradlew.bat test -DliveSyncTest` — 追加启用 `LiveSyncServersTest` 真实联调用例（默认跳过 12 例，需先起 `tools/local-sync` 服务；联调凭据为**随机一次性口令**，经 Gradle 配置期求值一次后由 `systemProperty` 下发，服务端需注入同名 `WEBDAV_USER`/`WEBDAV_PASSWORD` 或 `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` — **instrumented 测试**（需先起模拟器/真机；当前 x86_64 模拟器 7 例全绿；arm64 待补，见 ISSUE-P3-23）
- `.\gradlew.bat :database:connectedDebugAndroidTest` — **`database` 模块 instrumented 测试**（本批次首次建立该源集）：消费 `database/src/androidTest/assets/argon2-interop/` 下的**真实 KeePass/KeePassXC `.kdbx` 语料**做端到端解锁；语料未入库时按设计**显式跳过**（跳过 ≠ 通过），生成方法见 `crypto/src/test/resources/argon2-interop/README.md`
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包（签名配置见 `keystore.properties.example` / 环境变量，未配置时产出未签名包）
- `python .github/check_dependency_cvss.py build/reports/dependency-check/dependency-check-report.json` — **供应链 CVSS 阈值硬断言（fail-closed）** 的本地复跑入口（CI 接线见 `.github/workflows/dependency-scan.yml`）；有分数则 ≥ 7.0 即失败、无分数但 `CRITICAL`/`HIGH` 亦失败、**报告缺失同样失败**；豁免唯一通道为 `.github/owasp-dependency-suppressions.xml`（**不得**回调阈值或删除该步骤）
- **Rust 原生内核（ISSUE-P2-14 PoC Batch 1+ · ISSUE-P3-34~36 扩展）**：`cd crypto/src/main/rust && cargo test` — 原生内核单测（当前 **43 例全绿**）：Argon2（IETF 官方 KAT ×4 + BC 冻结向量等价 + 参数闸门 + 确定性）、**AES-KDF**（独立第三方复算向量 + 单轮语义锚点 + 轮数差异性 + 闸门）、**Twofish-CBC**（官方规格 KAT ×2 + 多长度/多密钥往返 + 分段等价 + CBC 链接有效性 + 闸门）、**口令强度**（单调性 + 9 类模式正反例 + 任意字节不 panic + 标志位掩码）、以及四个 JNI 导出符号的静态签名断言
- `python tools/kdbx-corpus/generate_corpus.py --check` — **`.kdbx` 语料生成/校验脚本**（ISSUE-P3-38）：`--check` 环境自检（缺 `keepassxc-cli` 即非零退出并给安装指引）、`--dry-run` 打印计划、`--verify FILE [--json FILE]` 只读核验文件头并与伴生 JSON 逐字段比对、`--ingest FILE` 复核外部（GUI）产出并按**实测参数**推导规范文件名 + 写伴生 JSON + 双落位。用法与退出码见 [tools/kdbx-corpus/README.md](tools/kdbx-corpus/README.md)

> **原生构建前置（ISSUE-P2-14 PoC Batch 3 起，替代原 TASK-52 CMake 方案）**：crypto 模块的原生内核由 **Rust + cargo-ndk 从源码交叉编译**（`crypto/src/main/rust/`，产出 4 ABI `libkeepasskey_argon2.so`）。
> **该 crate 现已含四个内核**（`lib.rs` Argon2 / `aes_kdf.rs` / `twofish_cbc.rs` / `strength.rs`，JNI 导出在 `jni_bridge.rs` 与 `jni_bridge_ext.rs`）；`[lib] name` 仍为历史的 `keepasskey_argon2`（CI 与 6 份文档绑定该名，改名零收益故有意保留，见 RESOLVED_LOG §8.6-9）。前置工具链：
> - **NDK 28.2.13676358**（`sdkmanager "ndk;28.2.13676358"`；AGP strip + cargo-ndk 链接器共用）；
> - **Rust stable + 4 个 Android target**：`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`；
> - **cargo-ndk**：`cargo install cargo-ndk`（Gradle task `:crypto:cargoNdkBuild` 经 `ANDROID_NDK_HOME` 调用，`assembleDebug/Release` 自动触发；纯 `test` 不触发）。
>
> **宿主侧原生验证（Batch 4 起；ISSUE-P3-34~38 起扩至 `database`）**：`.\gradlew.bat :crypto:test`
> 会先跑 `:crypto:cargoHostBuild`（`cargo build --release`，产物 `build/rust/host/release/`）并经
> `-Djava.library.path` 注入单测 JVM，使桌面单测也能加载原生库、真实覆盖 JNI 调用路径；
> **`database/build.gradle.kts` 现已做同样注入**（该模块是 `PasswordStrengthEvaluator` 的唯一消费方
> 与 `KdbxFile` 端到端宿主，不注入则只覆盖降级路径）；
> **未安装 cargo 或构建失败时自动降级为跳过相关用例**（不阻断）。
>
> **供应链**：`cd crypto/src/main/rust && cargo deny check`（需 `cargo install cargo-deny`；`deny.toml` 已入库）。
>
> 原 `crypto/src/main/cpp/`（vendored PHC C + JNI 桥）已于 **Batch 5 `git rm`**（git 历史可回溯），国内网络可经 `RUSTUP_DIST_SERVER=https://rsproxy.cn` 加速 target 下载。

---

## 6. 已知工程限界

- **KDBX 对象树仍整体驻留内存**：解析已流式化，但 `KdbxGroup`/`KdbxEntry` 树仍在内存（增量加载/进度 Flow 为远期项）。
- **条件写依赖服务端**：AWS S3 原子生效，少数未实现 `If-Match` 覆写的兼容存储降级为 HEAD 预检 + 无条件 PUT；WebDAV `uploadAtomic` 预条件在个别极简 DAV 服务端可能被忽略。
- **`ProtectedString` 驻留加密为纵深防御层**：对抗堆扫描与崩溃转储中的明文暴露；取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文。
- **原生内核为 Rust（体积代价）**：4 ABI 各含 Rust std + rayon + blake2 + aes + twofish + sha2，strip 后 `.so` 约 313~506KB/ABI（原 C 内核约 18~22KB/ABI）；收益是秘密确定性擦除、**设备侧实测 4.98×~8.42× 于 BC** 的 Argon2 派生速度（x86_64 模拟器，t=2/m=64MiB），以及 AES-KDF / Twofish 摆脱 JVM 逐轮/纯 Java 路径。arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁待补（ISSUE-P3-23）。
- **AES-KDF / Twofish / 口令强度的设备侧验证缺口（如实登记）**：三者已并入同一 `.so`，但
  `crypto` 模块既有的 7 例 instrumented 记录**早于本次扩展**，其宿主 JNI 差分等价证据见
  RESOLVED_LOG **§8.1 / §8.2 / §8.3**；本机无设备/模拟器，**设备侧证据待补**。
  另 **AES-KDF 的加速倍数未实测**，故文档**不宣称任何倍数**（`KdfBenchmark` 可直接复用做 A/B）。
- **`CipherInputStream` 的真实错误语义（本批次实测修正）**：本机 JDK 实测，`CipherInputStream` 对
  **填充非法**抛 `IOException`（cause `BadPaddingException`）、对**长度非分组整数倍 / 空输入**抛
  `IOException`（cause `IllegalBlockSizeException`）、对**底层流 `IOException`** 原样上抛——**不是**
  此前文档所述的「吞掉并伪装 EOF」。`crypto/.../cipher/CbcDecryptingInputStream`（Twofish 原生解密流）
  即按此实测基线实现，且这是**必需**的：`KdbxCipherKeyResolver.isPlausibleInnerHeaderPrefix` 正是以
  `catch (_: java.io.IOException)` 截断「首块解密探针」的收尾错误并保留已解出前缀。
  **已知差异**：若调用方未读到尾部即 `close`，基线可能仍在 `close` 内校验填充并抛异常，本实现不做该收尾校验
  （双方都不交付未校验明文；对合法库强行校验反而可能误报）。任何新增的 CBC 流式实现都必须遵守同一基线。
- **`System.loadLibrary` 的顺序依赖**：原生库由 `NativeCryptoLibrary.loaded`（`crypto/.../NativeCryptoLibrary.kt`）
  统一懒加载，各绑定（`NativeArgon2` / `NativeAesKdf` / `NativeTwofish` / `NativePasswordStrength`）
  的 `available` 均**先求值该属性**再发起原生调用，否则 `external fun` 会抛 `UnsatisfiedLinkError`。
  生产路径不受影响（各引擎必然先判 `available`，且原生入口已 catch 该错误归一为类型化异常）；
  新增消费方**必须遵循同一顺序**。
- **窗口级遮挡触摸过滤的覆盖范围**：`FlagSecureGuard.applyObscuredTouchFilter` 作用于 `MainActivity` 的 `decorView`，Android 触摸分发取「最近带该标志的祖先」，故其**全部子屏（含各 ComposeView）已被窗口级覆盖**；真正需要单独接线的是**不经 `MainActivity` 的独立窗口**（如 `BaseCredentialActivity` 系）。另注意该标志**只作用于触摸分发路径**，不拦截无障碍 `ACTION_CLICK`/`performClick`。
- **浏览器特权白名单**：内置 Chrome 稳定版签名指纹，证书轮换或白名单外浏览器 fail-closed 降级为 apk-key-hash 路径。
- **外部库导入策略**：经导入复制进内部存储后原地编辑（不写回外部原文件），为当前设计取舍。
