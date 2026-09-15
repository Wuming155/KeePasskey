# AGENTS.md

本文件为 AI 编码代理提供工作指引。

> **双文档敏捷驱动体系**：
> 1. **待办清单**：[`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) — 按优先级（P0 → P1 → P2 → P3）降序排列，自包含背景与验收标准，**拿起来直接做，无需额外计划文件**。
> 2. **历史归档**：[`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) — 已整改任务与批次验收证据。

---

## 1. 版本基线（摘要）

- 测试 / 构建 / CI 当前全绿（具体版本、例数、残余面见 [`RESOLVED_LOG.md`](docs/RESOLVED_LOG.md)）。
  单测基线（2026-09-15，§66 批次后）：**1763 例 / 0 失败 / 0 错误 / 13 跳过**
  （app 973 / core 68 / crypto 131 / database 388 / sync 203；`--rerun-tasks --max-workers=1` 强制真实执行）；
  原生内核基线（同日，§66 批次后）：`cargo test` **57 例 / 0 失败**（§66 未触碰原生内核，基线保持；含 ISSUE-P2-56 工作内存清零 4 例 + ISSUE-P2-57 受管缓冲与 sha2 状态擦除 3 例 + ISSUE-P2-58 强度评估线性化 6 例）；
  设备侧基线（2026-09-14，§47 批次后）：**32 例 / 0 失败 / 0 跳过**（`app` 15 + `database` 7 +
  `sync` 3 + `crypto` 7），已在 **arm64 真机**（Redmi 4X / LineageOS / Android 17 / **API 37**）全量复跑通过；
  此前既定环境为 x86_64 / API 36.1 模拟器（§34 / §36 / §46）。`app` 15 例含 `QuickUnlockSealDowngradeDeviceTest`
  软件级 Keystore 落位实测 / fail-closed 封印拒绝 / 降级确认闸门（见 §46；该用例经 §47 更名与断言口径更正）；大附件（>1 MiB）已落盘
  磁盘缓存，清理为**冷启动 + 会话锁定**两层（§35、§38）；快速解锁封印载荷
  已升级为复合帧格式（主密码 + 密钥文件一并封印，历史格式向后兼容，§29.1）；解锁失败重试节流
  默认关闭并支持开关与自定义最长锁定时长（§29.2）；FLAG_SECURE 防截屏改为开关即生效模型
  （锁定态强制遮蔽，解锁态随开关关闭真实解除，§29.3）并已覆盖敏感**对话框**独立窗口（§38）；
  外部安全审计整改：Gradle Wrapper 锁定分发 SHA-256 并启用 CI wrapper 校验、KDBX 口令中间缓冲清零、
  TOTP 扫码取景窗口纳入 FLAG_SECURE（§30）。**Gradle 分发来源与锁定值（ISSUE-P3-115 补记）**：
  `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向**第三方镜像**
  `mirrors.cloud.tencent.com`（非 `services.gradle.org`），完整性由同文件 `distributionSha256Sum`
  锁定的官方 `-bin` ZIP 摘要承担——Gradle **9.7.1**：`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`
  （来源 <https://gradle.org/release-checksums/>，2026-09-12 核实）。镜像被劫持 / 篡改时 Wrapper 因哈希不符拒绝使用；
  若要彻底消除第三方镜像依赖，把 `distributionUrl` 改回 `https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip` 即可（哈希不变）。
  写侧 Argon2 `P` 已按 KDBX4 规范以 UInt32 编码；
  **KDBX4 时间已改为官方秒级 Base64**（§38 P0-05，此前误写 .NET ticks 致官方客户端读不了本仓产物）。
- **互操作证据纪律（§38 立规）**：`.kdbx` 产物的互操作性以**官方实现端到端对拍**为准
  （`OwnProductInteropProbeTest` 产出真实产物 + `PROBE.md` + `keepassxc-cli` / `pykeepass` 复现命令）；
  `tools/kdbx-corpus/generate_corpus.py --verify` **仅证明外层文件头自洽，不构成互操作证据**（工具自述）。
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
     **Argon2 `m_cost` 工作内存除外项已闭环（ISSUE-P2-56，§49）**：argon2 crate 的 `zeroize` feature **不覆盖** `hash_password_into` 内部 `Blocks::drop`（实证：`block.rs:190-200` 仅 dealloc），
     故主工作内存改由本 crate 自持 `Zeroizing<Vec<Block>>` + `hash_password_into_with_memory` 承担擦除；新增原生内核不得再依赖「启用 `zeroize` feature 即已擦除」的推定。
     **派生输出与摘要状态亦须走受管缓冲（ISSUE-P2-57，§49）**：`sha2` 已启用 `zeroize`（`Sha256` 满足 `ZeroizeOnDrop`）；
     生产路径（JNI 桥）一律用 `derive_into` / `aes_kdf_into` 把结果**直接写入** `Zeroizing` 缓冲，
     不得以 `Option<[u8; 32]>` 返回值形态把派生密钥拷成不可擦栈副本（`derive` / `aes_kdf` 门面仅限测试与非秘密比对）。
     **口令强度评估为全库热路径（ISSUE-P2-58，§49）**：`estimate` 只分析前 `MAX_ANALYZED_CHARS`（256）字符，
     超额部分不给熵信用且线性惩罚（防「超出只按长度评分」陷阱）；三条原平方级路径（键盘行走 / 唯一字符 / 周期检测）已线性化。
     **新增调用方（如新的健康检查 / 熵估算入口）必须把 CPU 段放在 `Dispatchers.Default`**，不得在 `viewModelScope` 主线程裸 `launch`。
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
8. **每批次收尾必须编译稳定版并回传产物完整路径（强制）**：每完成一批次代码修改（提交推送之前或同时），执行 `.\gradlew.bat assembleRelease` 编译稳定版本（R8 混淆 + 资源收缩 + release 签名），并在最终交付说明中**原样给出产物完整绝对路径**：
   `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`（未配置 release 签名时为同目录 `app-release-unsigned.apk`，须如实注明）。构建失败必须如实报告并修复，不得以 debug 包冒充稳定版，也不得引用旧产物路径充当本次构建结果。
   - **发布签名口令有构建期闸门（ISSUE-P2-55，§49）**：`app/build.gradle.kts` 在配置阶段拒绝**已公开的示例 / 弱口令**（含历史泄露值 `keepasskey123`）、**模板占位符**与**长度 < 16** 的口令，直接 `error(...)` 终止。口令只能来自本地 `keystore.properties`（gitignore）或 CI Secret（`KEYSTORE_PASSWORD` / `KEY_PASSWORD`，须为高熵值）；**该闸门无豁免开关**。既有密钥库若为弱口令，按 `keystore.properties.example` 内的 `keytool -importkeystore` 说明**只 re-key、不换密钥**（换密钥将导致已安装用户无法覆盖升级）。

---

## 4. 文档索引

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) | 现存问题与待办清单（P0→P3） | 认领与开始新工作前 |
| [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) | 已整改任务与历史批次证据 | 确认历史 Bug 是否已修 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块依赖拓扑与关键架构决策 | 跨模块改动、新功能落位前 |
| [`docs/reference-projects.md`](docs/reference-projects.md) | 参考项目地图 | 实现算法/格式兼容时 |
| [`docs/references/`](docs/references/) | 5 个参考项目架构分析 | 实现思路借鉴前 |
| [`docs/同步层记录级完整性威胁建模.md`](docs/同步层记录级完整性威胁建模.md) | 同步层跨记录置换与防回滚威胁建模 | 改动同步 / 合并 / 防回滚前 |
| [`docs/原生Argon2真机验证记录.md`](docs/原生Argon2真机验证记录.md) | 原生内核真机与模拟器实测登记（**禁混表**） | 声称性能或真机验证前 |
| [`docs/KDBX4与复合密钥实战互操作排查日志.md`](docs/KDBX4与复合密钥实战互操作排查日志.md) | KDBX4 / 复合密钥互操作排障记录 | 排查互操作差异时 |
| [`docs/SECURITY_RECHECK_2026-09.md`](docs/SECURITY_RECHECK_2026-09.md) | **第二轮独立安全复核裁决报告**（对 89 项开放项 + 第一轮全部"已排除"结论的独立复核：真伪 / 重评严重度 / 攻击链 / Root Cause / 放行裁决） | 认领任何安全条目、重评 severity、准备发布前 |
| `.codebuddy/rules/engineering-rules.md` | 工程规则 | 编写/修改任何代码前 |
| `tools/audit/check_recheck_consistency.sh` | **复核报告一致性扫描**（由"已撤销/已更正断言清单"驱动；防止更正节与正文打架。对应 `docs/SECURITY_RECHECK_2026-09.md` 定稿前必跑） | 修改任何审计/复核报告后 |

> **索引纪律（ISSUE-P3-81 立规）**：任何记录**已确认缺陷 / 残余风险 / 验证结论**的文档，必须登记在本表内。
> 反例代价：本索引曾长期漏登 `docs/SECURITY_AUDIT_2026-09.md` 等 5 份安全文档，
> 其 29 项发现因无人流转而在 `RESOLVED_LOG.md` 中零引用、长期未闭环。
> **退役纪律（2026-09-13 补充）**：`docs/SECURITY_AUDIT_2026-09.md`、`docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`、
> `docs/SECURITY_AUDIT_REMEDIATION.md` 与 `docs/THREAT-MODEL-AUDIT-d32f3e7.md` **均已退役删除**
> （处置归档见 `RESOLVED_LOG.md` §40 / §41 / §42 / §43）——**仍成立项已全部转登 `ACTIVE_ISSUES.md`**，
> 误报排除 / 待复核区 / 无法确认 / 已确认强项 / CVSS↔CWE 对照 / 威胁建模结论由 `RESOLVED_LOG.md`
> **§42**（信任边界 TB、15 类对手、信任假设 TA、条件化生存性、开放问题与威胁清单状态）与
> **§43**（附录 A–F、产品决策 A-1~A-4）承接。
> 任何文档退役前，其结论必须完成分流并同步本表，**不得随文件删除而脱离索引与工作流入口**（§40.8 纪律 4）。
> **另记（§41 立规）**：审计报告须在**开始阅读时即纳入 git 跟踪**——未跟踪文档退役后**无法**经 `git show` 取回。
> **另记（§42 立规）**：同批提交的多份审计文档必须**在同一退役批次内逐份处置**——本仓曾只处置主报告与
> 敏感数据流报告，致配套威胁建模文档的 `Q-2` / `Q-11` / `Q-14` / `Q-15` / `Q-16` 等开放问题长期无人流转。

---

## 5. 构建与测试命令

统一用 Gradle Wrapper（版本见 §1）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat lint` — Android Lint（warning 不阻断）
- `.\gradlew.bat test --rerun-tasks --max-workers=1` — 单元测试（强制真实执行，单会话勿并发）
- `.\gradlew.bat test -DliveSyncTest` — 追加真实联调（需先起 `tools/local-sync`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` / `:database:connectedDebugAndroidTest` / `:app:connectedDebugAndroidTest` — instrumented 测试（需设备）
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包
- `python .github/check_dependency_cvss.py build/reports/dependency-check/dependency-check-report.json` — 供应链 CVSS ≥ 7.0 硬断言（fail-closed）
- `cd crypto/src/main/rust && cargo test` — 原生内核单测
- `python tools/kdbx-corpus/generate_corpus.py --check` — `.kdbx` 语料校验
- `bash tools/audit/check_recheck_consistency.sh` — **复核报告一致性扫描**（fail-closed：发现残留禁用短语即退出码 1）。**修改任何审计 / 复核报告后必须跑**

> 原生内核（`crypto/src/main/rust/`）由 Rust + cargo-ndk 交叉编译，`assembleDebug/Release` 自动触发。
> **降级与 fail-closed 的边界（ISSUE-P3-125，§66 更正）**：`cargo` **缺失**（离线 / 无工具链）时
> 宿主库任务经 `onlyIf` **跳过**，相关 JNI 用例经 `Assume` 跳过（有意的降级路径）；
> `cargo` **存在但构建失败**时任务**直接失败**（fail-closed）——此前 `isIgnoreExitValue = true`
> 会把编译失败吞成「用例跳过、构建全绿」的假绿通道，现已移除；`assembleDebug/Release` 的
> `cargoNdkBuild` 本就不吞退出码，故同样 fail-closed。
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

- KDBX 对象树仍整体驻留内存（解析已流式化）；**附件字节除外**——超过阈值（默认 1 MiB，可配）的附件
  经 `BinaryStore` 落盘（`cacheDir/attachments`，0600/0700），池中只留引用。清理为**两层**：
  ① 应用冷启动（`MainApplication.onCreate`，任何会话打开之前）② 会话锁定 / 关闭事件（§35、§38）。
  **如实声明的边界（F-13 / ISSUE-P1-19）**：进程被 kill / force-stop 而未经过上述任一路径时，
  已解密附件快照会留存至下次冷启动——**不得再单独使用「锁定即闭环」这类措辞**。
  `KdbxAttachment.clear()` 对**落盘项**不动作（其字节由多个引用者共享），生命周期由 store 统一收口。
- 条件写依赖服务端：AWS S3 原子生效；少数兼容存储降级为 HEAD 预检 + 无条件 PUT。
- **落盘清理为 unlink-only（ISSUE-P2-66，已接受边界）**：`SyncCache` / 附件缓存的清理走
  `File.delete` / `deleteRecursively`，已 unlink 的扇区在介质 TRIM 前仍可能被恢复（取证级威胁，
  需物理介质访问能力）。本仓**不**实施应用层覆写擦除——对现代闪存无确定语义且显著拖慢锁定路径；
  该层面的收窄由平台全盘加密 / FBE 承担。**不得**据此断言「锁定即不可恢复」。
- **`.kdbx.bak` 滚动备份的语义与保留期（ISSUE-P3-107，如实登记）**：`createBackupBeforeSave`
  （默认 **true**）开启时，每次**成功写入**都会把写入前的稳定版本另存为**同目录**的
  `<库文件名>.kdbx.bak`——滚动保留**恰好一份**（上一次成功写入的版本），逐次覆盖，**不**多代累积、
  **不**迁往其它目录。该文件是用**写入当时生效的凭据**加密的**完整库副本**，因此：
  ① 成功换密（`DatabaseSession.changeCredentials`，含换密钥文件）后**一律删除**——否则旧口令仍可解开；
  ② 关闭开关时不生成并清理历史遗留 `.bak`。
  **残余（如实声明）**：若用户在**本应用之外**（其它 KDBX 客户端）更换口令，本应用无从知晓，
  该 `.bak` 会一直可用**旧口令**解开，直至下次在本应用内写入（滚动覆盖或被换密路径删除）。
  行为与保留期同时写在该偏好的 KDoc（`DatabaseSession.createBackupBeforeSave`）与设置页文案
  （`sync_backup_title` / `sync_backup_sub`，2026-09-15 由「同步前自动备份 / 备份至安全目录」
  更正——原文案描述的「同步前上传到安全目录」在全仓**无对应实现**）。
- **KDBX 内存池的擦除边界与树外可达性（ISSUE-P3-119，如实声明）**：`KdbxDatabase.clearSensitiveData()`
  擦除**条目树**（受保护字段 / 自定义字段 / 附件 / 历史）与头部 KDF secret，但**不擦内层二进制池**
  （`InnerHeader.binaries`）——≤ 落盘阈值的附件明文在池中仅随引用丢弃、等待 GC。
  **树外可达性已逐点核实并收口**：整树引用者只有 `SyncSessionState.lastSyncedDb`（锁库经 `clear()` 释放）
  与 `SyncConflictController` 的 `pendingLocalDb` / `pendingRemoteDb` / `pendingMergedRoot`
  （随 `clearPendingConflictSession()` 释放）；同步路径上**仅服务单次内容判定 / 一次性合并**的解析产物
  已显式擦除（`SyncContentChangeDetector` 的缓存快照树、`SyncConflictController.wipeDiscarded` 的三处丢弃点）。
  **未实施池内擦除的原因与解除条件**：`KdbxDatabase.copy()` 会**共享同一 `binaries` 列表**
  （合并路径常态发生），池项擦除须先为 `InnerHeader.BinaryItem` 定义「谁拥有该数组」的所有权规则
  （对齐 R-CLEAR-2 所有权约定），否则会误伤存活树仍在引用的同一数组；补齐该规则前按已接受边界登记。
- `ProtectedString` 驻留加密为纵深防御层；持有进程密钥或任意代码执行者仍可在读取瞬间截获明文。
- 原生内核为 Rust（代价是体积，收益是秘密确定性擦除、Argon2 优于纯 Java 路径）；**arm64 真机已验证**
  （2026-09-14：原生内核 JNI 通路 / 与 BouncyCastle 逐字节一致 / R1 性能闸门 + 真实语料端到端解锁 2/2，
  见 RESOLVED_LOG §47）。**仍未覆盖**：官方客户端（`keepassxc-cli` / `pykeepass`）对**本仓产物**的端到端
  互操作对拍（该口径以 §1 互操作证据纪律为准），以及 `ISSUE-P2-80` 的 KDF 墙钟 / 内存闸门**真机分路径实测**。
- 原生侧 `System.loadLibrary` 经 `NativeCryptoLibrary.loaded` 统一懒加载；各绑定 `available` 须先求值该属性再发起原生调用。
- `CipherInputStream` 对填充非法/长度非整数倍抛 `IOException`（非静默 EOF）；新增 CBC 流式实现须遵守同一基线。
- 窗口级遮挡触摸过滤（`setFilterTouchesWhenObscured`）在 `MainActivity` 经 `FlagSecureGuard` 施加于 `decorView`；
  独立窗口按各自威胁面分别接线，**无未接线盲区**：自动填充窗口（`AutofillConfirmActivity` / `AutofillPickerActivity` /
  `AutofillUnlockActivity`）与通行密钥窗口（`CredentialUnlockActivity` / `CredentialVerificationLauncher`）调用
  `ApplyObscuredTouchFilter()`；`BaseCredentialActivity` 体系（`PasswordSaveActivity` / `PasswordFillActivity` /
  `PasskeyAssertionActivity` / `PasskeyCreateActivity`）则直接 `setHideOverlayWindows(true)` 屏蔽悬浮窗覆盖
  （API 31+ 强于触摸过滤，覆盖被完全阻断故无需再叠触摸过滤）。
- **设备侧（instrumented）覆盖（2026-09-14，见 RESOLVED_LOG §34 / §36 / §46 / §47）**：`app` / `database` /
  `sync` / `crypto` 四模块均已建立 `androidTest` 源集，当前共 **32 例**——`app` 15（导入解析 3 + 域解析 7 +
  解锁落盘 2 + 软件级 Keystore 封印 3，见 §46）、`database` 7（真实语料解锁 2 + 自生成往返 1 + 字段引用 4）、
  `sync` 3（落盘权限基线）、`crypto` 7（原生内核 JNI 通路 / 与 BouncyCastle 逐字节一致 / R1 性能闸门），
  在 **arm64 真机**（Redmi 4X / LineageOS / Android 17 / API 37）与 x86_64 / API 36.1 模拟器上均
  **0 failure / 0 skip**。**仍未覆盖**：Passkey 系统级交互、
  `AssistStructure` 结构树扫描、通知渲染（依赖系统凭据对话框 / 真实自动填充会话 / 通知栏）。
  另：**§38 新接线但未在设备侧验证的 3 项**（敏感对话框 `FLAG_SECURE` 实效、附件缓存冷启动清理端到端、
  `SecureDialog` 是否取到 `DialogWindowProvider`）已登记为 **ISSUE-P2-42**，含逐项复现配方与验收标准。
  §24 的 ISSUE-P1-12 与 §26 的 ISSUE-P0-04 均属「JVM 过、Android 运行时挂」类缺陷逃逸，故**涉及正则 / XML /
  平台 API 的静态逻辑不能仅凭宿主单测判定在 Android 上可用**。
