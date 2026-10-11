# AGENTS.md

给 AI 编码代理的工作指引：**只放长期规则、命令与索引指针**——细则一律落 `docs/` 与各脚本 docstring（规则 9）。

> **双文档体系**：待办 [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md)（P0→P3，自包含背景与验收标准）；归档
> [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) + [`docs/resolved/`](docs/resolved/)；文档全貌见 [`docs/README.md`](docs/README.md)。
> **章节编号沿用历史**（§1 版本基线、§6 已知工程限界已删，免破坏既有 `§` 引用）：两节结论分别落于
> [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) / [`docs/records/`](docs/records/) 与
> [`docs/architecture/已知工程限界.md`](docs/architecture/已知工程限界.md)（代码 KDoc 的「见 `AGENTS.md` §6」一律改指该文件）。

## 2. 项目概述

KeePasskey：原生 Kotlin 的 Android 密码管理器，标准 `.kdbx`（v4）格式 + WebDAV / S3 兼容同步，集成系统 Credential
Manager 支持通行密钥（Passkey / WebAuthn）端到端生成、存储与自动验证。技术栈：Jetpack Compose + Material 3、Hilt、
Coroutines + Flow；**文档与代码注释使用简体中文**。平台：minSdk 36 / compileSdk 37 / targetSdk 36；全站强制 HTTPS
（TLS-only），零证书固定，接入 Mozilla PSL。

## 3. 硬约束与极简闭环纪律（规则，须继承）

1. **模块依赖严格单向**，禁止反向或同层互依（`app → crypto` 为 app 直接使用 crypto 的通行密钥 / 熵估算 / KDF 基准面，
   与 `database → crypto(api)` 的透传并存，两者不冲突）：
   ```
   app ──> database ──> crypto ──> core
    ├───> sync ────────────────> core
    └───> crypto ──────────────> core
   ```
   拓扑明细见 [`docs/architecture/ARCHITECTURE.md`](docs/architecture/ARCHITECTURE.md) §2。
2. **敏感数据铁律**：主密码、密钥只用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`；日志严禁敏感明文。
   - 原生侧 `crypto/src/main/rust/` 四个内核（Argon2 / AES-KDF / Twofish-CBC / 口令强度）的敏感缓冲一律由 `Zeroizing`
     RAII 全路径擦除：**禁止**手写 C/C++ 秘密缓冲管理，**禁止**以「启用了 `zeroize` feature」推定已擦除；生产派生只走
     `derive_into` / `aes_kdf_into`，`derive` / `aes_kdf` 门面仅限测试与非秘密比对。
   - 口令强度评估是全库热路径：新增调用方（健康检查 / 熵估算）**必须**把 CPU 段放 `Dispatchers.Default`。
   - JNI 定长布局契约：跨 FFI 只传基本类型与数组；强度评估返回定长 3 元 `IntArray [score, log10×100, flags]`，`FLAG_*` 位值两侧逐位对齐。
3. **参考项目只读与文档优先（禁止盲目翻看源码）**：严禁对 `参考项目/` 无目标 `grep` / 扫源码（架构分析集中在
   `docs/references/`，借鉴前先读对应文档）；严禁修改或复制 `参考项目/` 下代码（许可证约束）。优先级：
   🥇 KeePassDX / 🥈 keepass2android / ⚖️ KeePass-2.61.1 官方 C#（**格式裁决者**）/ ⚖️ KeePassXC（合并与 Passkey schema）/ 🥉 Monica。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、Result 错误处理、Compose 规范、原子写盘、协程调度、
   防御性安全、测试资产纪律）见 `.codebuddy/rules/engineering-rules.md`，写代码前必读。
5. **无需中间计划文件**：严禁创建 plan 文档；背景与验收标准直接自包含在 `ACTIVE_ISSUES.md`。
6. **极简闭环工作流（认领 → 整改+验证 → 流转归档 → 提交推送）**：
   - **认领**：从 `ACTIVE_ISSUES.md` 顶部按优先级认领；发现新问题即时补登（**严禁只记聊天或脑中**），附「核实时间点 + 核实方式」。
   - **整改 + 验证（测试分级：迭代按需、结案全量）**：迭代期用 `--tests "<FQCN>"` 定向跑，**必须回读
     `build/test-results/**/TEST-*.xml` 确认用例在册**（零匹配**不一定**报错，§201 漏跑一整套而回执仍绿），「没跑」不得伪装成「通过」；
     结案前 `test` 全绿 + `gate_readings.py` 全 PASS 方准入库，免全量白名单见 §5。
   - **读数**：聚合计数只认 `count_test_results.py`，且**只在全量跑之后取**（定向跑后是残缺集，§276）。批次文档须**原样粘贴
     `gate_readings.py` 输出的读数块**（逐条 EXIT + 读数行）——「闸门存在 ≠ 闸门被执行」正是 `ISSUE-P3-305` 的根因（§308 立规）；
     **读数块只入批次文档**，报告 / 记录类文档引用批次编号即可。`test` 非零退出时**先**跑 `preserve_test_failures.py`（`ISSUE-P3-489`），
     装机走查**先**跑 `check_installed_build.py --expect-symbol <本批新增符号>`（§434）——设备上跑的**不一定**是你刚打的那份包。
   - **流转归档**：整条**剪切**出 `ACTIVE_ISSUES.md` → `RESOLVED_LOG.md` 加一行 → `docs/resolved/batches/`
     新增 `<NN>-<中文短名>.md`（原样收录，编号续用不复用）。
   - **提交到 GitHub**：文档与代码**同一次 `git commit`**，随后**立即 `git push`** 推送到 GitHub 远端；信息以 `TASK-xx` /
     `ISSUE-xx` 引用并简述主题。**代理直接执行，无须再征询**（2026-09-17 用户明示约定）。**按本次任务的路径清单显式
     `git add`**——`git add -A` / `git add .` 会卷进同一工作区里**别人的在途改动**；同一工作区**不并行**两条代理工作线
     （确需并行用 `git worktree add` 各占目录）。
7. **善用 MCP 知识服务器（强制）**：Android / Jetpack / Kotlin / Gradle / 加密库 / Google 平台 API → `google-developer-knowledge`；
   第三方库与框架（Compose / Hilt / OkHttp 等）→ Context7（`resolve-library-id` + `query-docs`）；**不得凭记忆臆测或盲改**。
8. **`.kdbx` 互操作证据纪律（§38 立规）**：产物互操作性只认**官方实现端到端对拍**（`OwnProductInteropProbeTest` + `PROBE.md` +
   `keepassxc-cli` / `pykeepass` 复现）；`generate_corpus.py --verify` 仅证明外层文件头自洽，**不构成**互操作证据。
9. **本文件始终保持精简（强制）**：只放**长期规则、命令、索引指针**；版本基线、批次细节、实测数据、历史裁决与已知工程限界
   一律**不进本文件**。新增内容前先自问「是否已在文档中承载、是否属于长期规则」；每次改动顺手删冗余。

## 4. 文档索引

> **唯一登记表 = 文档地图**：[`docs/README.md`](docs/README.md)（六分区总览；新增文档必须归入分区并登记该表，
> **不得平铺在 `docs/` 根**）。本节只列**动工前必读**，其余按需查文档地图。

- [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) — 待办（P0→P3）；[`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) — 归档总索引
- [`docs/architecture/已知工程限界.md`](docs/architecture/已知工程限界.md) — **已接受工程限界 / 残余风险登记表**（改这些面之前必读；判「是否为已知限界」只认此表）
- [`docs/architecture/产品裁决登记.md`](docs/architecture/产品裁决登记.md) — **已裁决的产品口径登记表**（判「这是不是已定的取舍」只认此表；**属取舍而非缺陷的条目一律不进 `ACTIVE_ISSUES.md`**）
- 检索上述两表：`python tools/doc/find_decision.py <关键词>`（`--list` 列全量）— **统一检索入口**；两表关联一律**引编号**（`PD-xx` / `§n`）
- `.codebuddy/rules/engineering-rules.md` — 工程规则；写代码前
- [`docs/security/同步层记录级完整性威胁建模.md`](docs/security/同步层记录级完整性威胁建模.md) — 改同步 / 合并 / 防回滚前
- [`docs/security/SECURITY_RECHECK_2026-09.md`](docs/security/SECURITY_RECHECK_2026-09.md) — 认领安全条目 / 重评 severity / 发布前
- [`docs/records/原生Argon2真机验证记录.md`](docs/records/原生Argon2真机验证记录.md) — 声称性能或真机验证前
- `tools/audit/check_recheck_consistency.py` — 修改任何审计 / 复核报告后必跑

> **索引纪律（`ISSUE-P3-81` 立规）**：凡记录**已确认缺陷 / 残余风险 / 验证结论**的文档必须登记到文档地图，且登记的链接
> **必须可跳转**（`python tools/doc/check_md_links.py` 自检，§180）。**退役纪律**：文档退役前其结论必须完成分流并同步文档地图；
> 审计报告须**开始阅读时即纳入 git 跟踪**；同批退役的多份须**逐份处置**。

## 5. 构建与测试命令

统一用 Gradle Wrapper（版本与 SHA-256 见 `gradle/wrapper/gradle-wrapper.properties`）；Windows 下 `.\gradlew.bat <task>`。
**各机检脚本的判据 / 边界 / 退出码一律见其 docstring**，本节只留命令与触发时机。

**编译、Lint 与打包**
- `.\gradlew.bat assembleDebug` / `lint` / `assembleRelease` — 编译全部模块 / Android Lint（warning 不阻断；报告计数
  **只认** `grep -cE "^ *<issue$" app/build/reports/lint-results-debug.xml`，用 `<issue` 会把根元素 `<issues>` 算进去，恒多 1）/
  R8 混淆 + 资源收缩发布包
- `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun` — 截图测试包装编译门禁（无需设备，`test` 不覆盖它）；
  **须用真编译任务**：聚合任务 `:app:compileDebugScreenshotTestSources` 只做依赖编排，其 UP-TO-DATE 不证明编译发生过
- `.\gradlew.bat :core:compileDebugAndroidTestKotlin :crypto:compileDebugAndroidTestKotlin :database:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
  — **设备侧（`androidTest`）源集编译门禁**（`ISSUE-P2-491`；CI 挂 `fast-gate`）：该任务**不在 `test` 依赖图内**，
  `test` 全绿**不代表**设备侧用例可编译；**「编译通过」同样不构成验证证据**，真机实跑仍按测试资产纪律执行
- `export-preview-main.bat` / `export-preview-secondary.bat` — 一键导出 Compose `@Preview` 截图（主屏 20 张白名单 vs 其余），
  产物 `preview-exports/{main,secondary}/{light,dark}/`（gitignore）；另有 `pwsh -File tools/export-previews.ps1 [-Filter <子串>]`
  走 Android CLI（须有运行中的 Studio，限界见脚本文首）

**测试与联调**
- `.\gradlew.bat test --max-workers=1` — **结案前全量单测**（单会话勿并发）。**默认不加 `--rerun-tasks`**：`UP-TO-DATE` 是
  「输入指纹未变 ⇒ 旧结果仍有效」的保证，不是「任务漏跑」。保留全量：CI 自 `ISSUE-P3-316` 起改为**每日定时 + 手动**触发，
  本地这道是唯一即时兜底；且 `test` 是唯一同时编译并运行五模块单测的入口，跨模块契约只能由它证
- `.\gradlew.bat :<module>:testDebugUnitTest --tests "<FQCN>"` — **迭代期定向单测**；**必须回读
  `build/test-results/**/TEST-*.xml` 确认用例在册**——过滤器零匹配**不一定**报错（`failOnNoMatchingTests`），§201 实测漏跑一整套而回执仍绿
- `.\gradlew.bat test -DliveSyncTest` — 追加真实联调（需先起 `tools/local-sync`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` / `:database:` / `:sync:` / `:app:` — instrumented 测试（需设备；
  **`:sync:` 那层含 `SyncCacheAndroidRuntimeTest`，即 0600 / 0700 仅属主权限不变量——宿主 JVM 恒走降级分支，只有真机可证**）
- `cd crypto/src/main/rust && cargo test` — 原生内核单测
- `python tools/kdbx-corpus/generate_corpus.py --check` — `.kdbx` 语料校验
- `python .github/check_dependency_cvss.py build/reports/dependency-check/dependency-check-report.json` — 供应链 CVSS ≥ 7.0 硬断言（fail-closed）
- `python tools/passkey-interop/verify_interop.py` — **通行密钥 KPEX 互操作对拍**（规则 8 的 passkey 面出口；先跑
  `:database:` 的 `PasskeyInteropProbeTest` 产出产物）。**改 `PasskeyData` schema / `PasskeyPkcs8Codec` / KPEX 字段后必跑**。
  退出码 0 = 通过 / 1 = 判据失败 / **2 = 环境缺失（缺 `keepassxc-cli`，不得当绿）**

**门禁与读数**
- `python tools/doc/gate_readings.py` — **门禁读数单点采集**（§308 立规；清单**直接解析** `.github/workflows/build.yml` 的
  `hygiene-gate` 段落，故与 CI 不可能漂移；任一条非 0、或解析不到命令，均退出码 1）。**每批结案前跑它，读数块原样贴入批次文档 §3**
- `python tools/doc/preserve_test_failures.py [--selftest]` — **全量单测首轮红证据留存**（`ISSUE-P3-489` 立规）：无失败退 0 /
  有失败退 1 / 无 XML 退 2（**不得**当绿）。`test` 非零退出后**先跑它再重跑**
- `python tools/device/check_installed_build.py [--expect-symbol <符号>] [--selftest]` — **装机走查前置读数**（§434 立规）：
  核对设备包 `lastUpdateTime` **不早于**本地 APK mtime，**且** APK 的 `classes*.dex` 内含指定符号；无 adb / 无设备 / 无包信息退 **2**
- `python tools/device/check_connected_device_results.py [--selftest]` — **设备侧结果非空转断言**（`ISSUE-P3-493` 立规；
  CI 挂在 `device-gate`）：对建有 `src/androidTest` 的五层断言 `tests > 0`，`tests == 0` 即**空转**；缺结果 XML 判 `2`

**机检脚本（改对应面后必跑的逐条入口）**
- `python tools/doc/count_line_tiers.py` — 五模块行数分档复核（**fail-closed**：`tier1(>500)` 恒 0 + `tier2` 棘轮预算只紧不松）
- `python tools/doc/long_functions.py` — 超长函数复核（**fail-closed**：存在 ≥ 阈值即退出码 1；CI 用默认阈值 100）
- `python tools/doc/logic_lines.py <文件> <函数名>` — 装配表「逻辑行」分类计数（`PD-11` 重开条件的可执行判据）
- `python tools/doc/count_test_results.py` — **JVM 单测聚合计数的唯一尺子**（`test` 后跑它，**不要**自己 `glob` `test-results/**/*.xml`）
- `python tools/doc/check_md_links.py` — `docs/` 相对链接自检（**改任何文档后跑**，断链即退出码 1）
- `python tools/doc/check_resolved_index_sync.py` — `resolved/` 索引一致性（**改任一批次 / 分册 / 全量索引 / `resolved/README.md` 后跑**）
- `python tools/doc/check_bounded_type_names.py` — **类型名有界性**（`PD-34`；**新增 `*Manager`/`*Util`/`*Helper`/`*Common` 后必跑**）
- `python tools/doc/check_box_slot_children.py` — **Box 内容槽同层兄弟叠放**（`ISSUE-P3-337` / `P3-457`；**改过 `*/ui/**` 卡片内容后必跑**）
- `python tools/doc/check_preview_state_coverage.py` — `@Preview` 状态覆盖普查（`ISSUE-P3-340`；**只出读数、不进 CI**）
- `python tools/doc/check_raw_coroutine_scope.py` — **裸 `CoroutineScope(` 收口**（`ISSUE-P1-538`；**改 `*/src/main/**` 的协程作用域后必跑**）
- `python tools/doc/check_archive_monotonicity.py` — **归档面不可回退**（`ISSUE-P2-546`；**改 `RESOLVED_LOG.md` 或增删
  `docs/resolved/batches/` 后必跑**；基线解析不到退 2，**不得**当绿）
- `python tools/doc/check_projection_read_safety.py` — **投影 / 解析 / 交付面敏感读取安全**（五条判据，任一命中即红）
- `python tools/doc/check_plaintext_carrier_to_string.py` — **明文口令载体 `toString()` 护栏**（**改 `*/src/main/**` 的 `data class` 声明后必跑**）
- `python tools/doc/check_message_not_in_user_text.py` — **异常 `message` 不得进用户可见文案槽**（**改 `*/src/main/**` 的文案装配后必跑**）
- `python tools/doc/check_cancellation_semantics.py` — **协程取消语义两面机检**（`ISSUE-P3-569` / `P3-570`：
  `catch` 子句与裸 `runCatching` 同判据；**改 `*/src/main/**` 的 `catch` 或 `runCatching` 后必跑**）
- `python tools/audit/check_recheck_consistency.py` — 复核报告一致性扫描（**改审计 / 复核报告后必跑**）
- `python tools/audit/check_tautological_assertions.py` — **「永远为真的断言」机检**（§275 立规；**改 `*/src/test/**` 的任何断言后必跑**）
- 逐字搬移复核 `python tools/doc/check_verbatim_move.py <原文件> <本体> [段落文件…]`；重复代码块是否真逐字相同
  `python tools/doc/scaffold_block_fingerprint.py <git rev> <目录> <页名>…`（目测登记前提曾造成一次真实回归，见 `ISSUE-P3-195`）

- CI **`hygiene-gate`**（`.github/workflows/build.yml`）—— 下列机检的 **fail-closed 硬门禁**，非 0 即红，**严禁** `|| true` 吞掉
  （**条数不写死**：以 `gate_readings.py` 现跑读数为准）：`count_line_tiers` + `long_functions` + `check_md_links` +
  `check_resolved_index_sync` + `check_tautological_assertions` + `check_recheck_consistency` + `check_bounded_type_names` +
  `check_box_slot_children` + `check_launch_language_seed` + `check_projection_read_safety` + `check_raw_coroutine_scope` +
  `check_archive_monotonicity` + `check_plaintext_carrier_to_string` + `check_message_not_in_user_text` +
  `check_cancellation_semantics`。本 job 的 checkout 必须 `fetch-depth: 0`（归档回退判据要读 `HEAD~1`）

> **Android CLI 调试约定**：设备侧调试**统一走 `adb`**；IDE / 设备侧调试首选入口为
> `"$env:USERPROFILE\.android\bin\android-cli.exe" studio <子命令>`（**PowerShell 写法**；Git Bash / MSYS 下同义写法
> `"$USERPROFILE/.android/bin/android-cli.exe"`）。

> **设备侧测试前置检查（2026-09-22 §263 立规，强制）**：对**装有需要保留的数据／应用**的设备，**禁止**直接运行
> `connectedDebugAndroidTest`——已装应用签名与测试包不一致时，UTP 会在 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 之后
> **卸载该应用**，其内部数据**随之永久丢失**（`android:allowBackup="false"` 时**无任何**备份可恢复）。应改用 AVD
> （本机 `Pixel_10`），或先与用户确认该应用的本地数据无需保留。

> **设备侧是独立一层**：`cargo` 缺失时原生内核任务经 `onlyIf` **跳过**、JNI 用例经 `Assume` 跳过（有意降级），
> **构建失败则 fail-closed 直接失败**——严禁再引入吞退出码的开关；真实语料缺失时 `RealKdbxCorpusUnlockTest` 抛
> `AssumptionViolatedException`（task 仍 `BUILD SUCCESSFUL`，但 `TEST-*.xml` 记 `<failure>`、`skipped=0`）。涉及正则 / XML /
> 平台 API 的逻辑不可只靠宿主单测。**但 task 结果本身也不够（`ISSUE-P3-493` 立规）**：`am instrument` 在「一个用例都没跑」时
> 返回**成功码**，UTP 据此归为 `tests=0 + SUCCESS` ⇒ 整层空转而显绿。故完整判定＝task 结果 **且**
> `check_connected_device_results.py` 各层 `tests > 0`；该脚本读出的 `failures` **不是**门槛数——`Assume` 跳过在 UTP XML 里同样记
> `<failure>`（`skipped` 恒 0）。

> **Rust 单测落位约定（`ISSUE-P3-57`，须遵守）**：单测放 `crypto/src/main/rust/src/tests/<name>_tests.rs`，源文件中以
> `#[cfg(test)] #[path = "tests/<name>_tests.rs"] mod tests;` 引用——Code scanning 的 `paths-ignore` 只能做文件级排除，
> 内联 `mod tests` 里的测试密钥 / 向量会被 `rust/hard-coded-cryptographic-value` 逐条报为 critical 误报。

> **测试资产纪律与原生面验证义务（细则见 `.codebuddy/rules/engineering-rules.md` §「测试资产纪律」，强制）**：
> ① **用例与对拍向量只允许新增或修改**，不得删除；确需删除时须先确认被删对象已无生产代码可测、在批次文档或限界表登记理由、
> 与生产代码同一次提交入库。② 改动 `crypto/src/main/rust/**`、`jni_bridge_ext.rs` 或任一 `Native*` 绑定 / 引擎的原生分派与
> **探活**，**必须**在设备上跑完 `:crypto:` / `:database:` / `:sync:` / `:app:` 四层 `connectedDebugAndroidTest` 方准入库；
> `*/src/androidTest/**` 新增或修改的用例同样必须真机实跑（`compileDebugAndroidTestKotlin` 通过**不构成**验证证据）。
> ③ 宿主全绿不代表设备可用；兜底分支（JCE / BC）的等价性由宿主 `CipherFallbackParityTest` 常态锁定，**不得**以「反正有兜底」为由跳过设备侧。
