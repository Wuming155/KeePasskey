# AGENTS.md

给 AI 编码代理的工作指引：**只放长期规则、命令与索引指针**。

> **双文档体系**：待办 [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md)（P0→P3，自包含背景与验收标准）；
> 归档 [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) + [`docs/resolved/`](docs/resolved/)；文档全貌见 [`docs/README.md`](docs/README.md)。
> **章节编号沿用历史**（原 §1 版本基线、§6 已知工程限界已删除），以免破坏代码与文档中的既有 `§` 引用。
> 两节结论均已分流：**§6 已迁移至 [`docs/architecture/已知工程限界.md`](docs/architecture/已知工程限界.md)**
> （代码 KDoc 的「见 `AGENTS.md` §6」一律改指该文件）；§1 的版本基线落于
> [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) 各批次与 [`docs/records/`](docs/records/)（规则 9）。

## 2. 项目概述

KeePasskey：原生 Kotlin 的 Android 密码管理器，标准 `.kdbx`（v4）格式 + WebDAV / S3 兼容同步，集成系统 Credential
Manager 支持通行密钥（Passkey / WebAuthn）端到端生成、存储与自动验证。技术栈：Jetpack Compose + Material 3、Hilt、
Coroutines + Flow；**文档与代码注释使用简体中文**。
平台：minSdk 36 / compileSdk 37 / targetSdk 36；全站强制 HTTPS（TLS-only），零证书固定，接入 Mozilla PSL。

## 3. 硬约束与极简闭环纪律（规则，须继承）

1. **模块依赖严格单向**，禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥只用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`；日志严禁敏感明文。
   - 原生侧 `crypto/src/main/rust/` 四个内核（Argon2 / AES-KDF / Twofish-CBC / 口令强度）的敏感缓冲一律由 `Zeroizing`
     RAII 全路径擦除：**禁止**手写 C/C++ 秘密缓冲管理，**禁止**以「启用了 `zeroize` feature」推定已擦除；生产派生只走
     `derive_into` / `aes_kdf_into`，`derive` / `aes_kdf` 门面仅限测试与非秘密比对。
   - 口令强度评估是全库热路径：新增调用方（健康检查 / 熵估算）**必须**把 CPU 段放 `Dispatchers.Default`，不得在 `viewModelScope` 裸 `launch`。
   - JNI 定长布局契约：跨 FFI 只传基本类型与数组；强度评估返回定长 3 元 `IntArray [score, log10×100, flags]`，`FLAG_*` 位值两侧逐位对齐。
3. **参考项目只读与文档优先（禁止盲目翻看源码）**：严禁对 `参考项目/` 无目标 `grep` / 扫源码，架构分析集中在
   `docs/references/`，借鉴前先读对应文档；严禁修改或复制 `参考项目/` 下代码（许可证约束）。
   - 优先级：🥇 KeePassDX（核心参考）/ 🥈 keepass2android（云同步）/ ⚖️ KeePass-2.61.1 官方 C#（**格式裁决者**）/
     ⚖️ KeePassXC（合并与 Passkey schema）/ 🥉 Monica（UI 补充）。
4. **工程规则**（单一职责与巨型类阈值、魔法数字、依赖倒置、Result 错误处理、Compose 规范、原子写盘、协程调度、防御性安全）
   见 `.codebuddy/rules/engineering-rules.md`，写代码前必读。
5. **无需中间计划文件**：严禁创建 plan 文档；背景与验收标准直接自包含在 `ACTIVE_ISSUES.md`。
6. **极简闭环工作流（认领 → 整改+验证 → 流转归档 → 提交推送）**：
   1. **认领**：从 `ACTIVE_ISSUES.md` 顶部按优先级认领；发现新问题即时补登（**严禁只记聊天或脑中**），新条目附「核实时间点 + 核实方式」。
   2. **整改 + 验证**：`.\gradlew.bat test` 全绿（含相关回归）**且** `python tools/doc/gate_readings.py` **全 PASS**
      （清单与条数随 `hygiene-gate` 段落自动解析，**本文件不写死**）方准入库；
      批次文档须**原样粘贴该脚本输出的读数块**（逐条 EXIT + 读数行），**禁止**只写「机检全绿 / EXIT 0」——
      「闸门存在 ≠ 闸门被执行」正是 `ISSUE-P3-305` 的根因，§308 立规。
      **首轮红取证（`ISSUE-P3-489` 立规）**：`test` 非零退出时**先**跑
      `python tools/doc/preserve_test_failures.py` 把肇事用例清单落盘（`build/failure-evidence/`）**再**重跑——
      `--rerun-tasks` 会全量覆盖 `test-results`，覆盖后首轮肇事者不可复原（§445.6 实测）。
      **装机走查前置（§434 立规）**：先跑 `python tools/device/check_installed_build.py --expect-symbol <本批新增符号>`——
      设备上跑的**不一定**是你刚打的那份包；「回执成功 + 新 UI 不存在」与真缺陷**外观完全一致**，§434 实测白走一轮。
   3. **流转归档**：整条**剪切**出 `ACTIVE_ISSUES.md` → `RESOLVED_LOG.md` 加一行 → `docs/resolved/batches/` 新增
      `<NN>-<中文短名>.md`（原样收录，编号续用不复用）。
   4. **提交推送**：文档与代码**同一次 `git commit`** 并**立即 `git push`**；信息以 `TASK-xx` / `ISSUE-xx` 引用并简述主题。
      **代理直接执行，无须再征询**（2026-09-17 用户明示约定：完成整改+验证即提交推送）。
      **按本次任务的路径清单显式 `git add`**——`git add -A` / `git add .` 会把同一工作区里**别人的在途改动**
      卷进你的提交（§434 实测：一条标题写「仅登记，未动代码」的提交带走了 9 个代码文件）。
      同一工作区**不并行**两条代理工作线（确需并行用 `git worktree add` 各占目录）——并发提交 / 重置会互相撤销。
7. **善用 MCP 知识服务器（强制）**：Android / Jetpack / Kotlin / Gradle / 加密库 / Google 平台 API → `google-developer-knowledge`；
   第三方库与框架（Compose / Hilt / OkHttp 等）→ Context7（`resolve-library-id` + `query-docs`）；**不得凭记忆臆测或盲改**，
   调用前先取该服务器各工具的最新参数 schema。
8. **`.kdbx` 互操作证据纪律（§38 立规）**：产物互操作性只认**官方实现端到端对拍**（`OwnProductInteropProbeTest` + `PROBE.md` +
   `keepassxc-cli` / `pykeepass` 复现）；`tools/kdbx-corpus/generate_corpus.py --verify` 仅证明外层文件头自洽，**不构成**互操作证据。
9. **本文件始终保持精简（强制）**：只放**长期规则、命令、索引指针**——能落到 `docs/` 的一律落 `docs/`；版本基线、批次细节、
    实测数据、历史裁决与已知工程限界一律**不进本文件**。新增内容前先自问「是否已在文档中承载、是否属于长期规则」；每次改动顺手删冗余。

## 4. 文档索引

> **唯一登记表 = 文档地图**：[`docs/README.md`](docs/README.md)（六分区总览；新增文档必须归入分区并登记该表，
> **不得平铺在 `docs/` 根**）。本节只列**动工前必读**，其余按需查文档地图。

- [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) — 待办（P0→P3）；[`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) — 归档总索引
- [`docs/architecture/已知工程限界.md`](docs/architecture/已知工程限界.md) — **已接受工程限界 / 残余风险登记表**（改这些面之前必读；判「是否为已知限界」只认此表）
- [`docs/architecture/产品裁决登记.md`](docs/architecture/产品裁决登记.md) — **已裁决的产品口径登记表**（判「这是不是已定的取舍」只认此表；**属取舍而非缺陷的条目一律不进 `ACTIVE_ISSUES.md`**）
- `.codebuddy/rules/engineering-rules.md` — 工程规则；写代码前
- [`docs/security/同步层记录级完整性威胁建模.md`](docs/security/同步层记录级完整性威胁建模.md) — 改同步 / 合并 / 防回滚前
- [`docs/security/SECURITY_RECHECK_2026-09.md`](docs/security/SECURITY_RECHECK_2026-09.md) — 认领安全条目 / 重评 severity / 发布前
- [`docs/records/原生Argon2真机验证记录.md`](docs/records/原生Argon2真机验证记录.md) — 声称性能或真机验证前
- `tools/audit/check_recheck_consistency.py` — 修改任何审计 / 复核报告后必跑（`.sh` 仅 bash 薄封装）

> **索引纪律（ISSUE-P3-81 立规）**：凡记录**已确认缺陷 / 残余风险 / 验证结论**的文档必须登记到文档地图，
> 且登记的链接**必须可跳转**（相对路径以本文件所在目录为基准，`python tools/doc/check_md_links.py` 自检，§180）。
> **退役纪律**：文档退役前其结论必须完成分流并同步文档地图（**不得脱离索引与工作流入口**）；审计报告须**开始阅读时即纳入
> git 跟踪**（未跟踪文档退役后无法经 `git show` 取回）；同批退役的多份审计文档须**逐份处置**。

## 5. 构建与测试命令

统一用 Gradle Wrapper（版本与 SHA-256 见 `gradle/wrapper/gradle-wrapper.properties`）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` / `lint` / `:app:compileDebugScreenshotTestKotlin --rerun` — 编译全部模块 / Android Lint
  （warning 不阻断；`lint` 报告计数**只认** `grep -cE "^ *<issue$" app/build/reports/lint-results-debug.xml`——
  用 `<issue` 会把根元素 `<issues>` 也算进去，恒多 1）/ **截图测试包装编译门禁**（`app/src/screenshotTest/` 是 gitignore 的生成物目录，改过 `@Preview`
  或 `tools/export_previews/` 生成器后跑；无需设备，`test` 不覆盖它。**用真编译任务 `...Kotlin`**：
  聚合任务 `:app:compileDebugScreenshotTestSources` 只做依赖编排，见到它报 UP-TO-DATE 并不能证明编译发生过；
  需要确凿证据时加 `--rerun`（实测该 Kotlin 任务强制执行约 3s））
- `.\gradlew.bat :core:compileDebugAndroidTestKotlin :crypto:compileDebugAndroidTestKotlin :database:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` —
  **设备侧（`androidTest`）源集编译门禁**（`ISSUE-P2-491` 立规；CI 挂在 `fast-gate`）：`compileDebugAndroidTestKotlin`
  **不在 `test` 任务依赖图内**，`test` 全绿**不代表**设备侧用例可编译——实测 `ISSUE-P1-431` 移除 `keyFileBytes` 形参后
  设备侧用例仍传该具名实参 ⇒ `:app:androidTest` **编译期**失败，四层真机义务被静默阻断。**「设备侧编译通过」同样不构成
  验证证据**（与 `:app:compileDebugScreenshotTestKotlin` 同款，须显式跑），真机实跑仍按测试资产纪律执行。
  不依赖 NDK / Rust（该任务图**不含** `:crypto:cargoNdkBuild`，故可留在 `fast-gate`）
- `export-preview-main.bat` / `export-preview-secondary.bat` — 根目录一键导出 Compose `@Preview` 截图：
  先跑 `tools/export_previews/generate_screenshot_test_wrappers.py` 重生包装，再
  `:app:exportMainPreviewScreenshots` / `:app:exportSecondaryPreviewScreenshots`（主屏 20 张白名单
  vs 其余），产物 `preview-exports/{main,secondary}/{light,dark}/`（gitignore）。另有
  `pwsh -File tools/export-previews.ps1 [-Filter <文件名子串>]` 走 Android CLI `render-compose-preview`
  （须有运行中的 Studio；产物 `build/preview-export/`，单次通常仅默认亮/暗变体，限界见脚本文首）
- `.\gradlew.bat test --rerun-tasks --max-workers=1` — 单元测试（强制真实执行，单会话勿并发）
- `.\gradlew.bat test -DliveSyncTest` — 追加真实联调（需先起 `tools/local-sync`）
- `.\gradlew.bat :crypto:connectedDebugAndroidTest` / `:database:connectedDebugAndroidTest` / `:sync:connectedDebugAndroidTest` / `:app:connectedDebugAndroidTest` — instrumented 测试（需设备；**`:sync:` 那层含 `SyncCacheAndroidRuntimeTest`，即 0600 / 0700 仅属主权限不变量——宿主 JVM 恒走降级分支，只有真机可证**）
- `.\gradlew.bat assembleRelease` — R8 混淆 + 资源收缩发布包
- `python .github/check_dependency_cvss.py build/reports/dependency-check/dependency-check-report.json` — 供应链 CVSS ≥ 7.0 硬断言（fail-closed）
- `cd crypto/src/main/rust && cargo test` — 原生内核单测
- `python tools/kdbx-corpus/generate_corpus.py --check` — `.kdbx` 语料校验
- `python tools/passkey-interop/verify_interop.py` — **通行密钥 KPEX 互操作对拍**（规则 8 在 passkey 面的机检出口；
  先跑 `:database:` 的 `PasskeyInteropProbeTest` 产出产物；判据含 `keepassxc-cli` / `pykeepass` 双实现读数与
  `cryptography` 独立解析 PEM 并重导出公钥；退出码 0 = 通过 / 1 = 判据失败 /
  **2 = 环境缺失（缺 `keepassxc-cli`，不得当绿；摘要行只列实际运行过的实现——`ISSUE-P1-495`）**，
  `--selftest` 为口径反校。**改 `PasskeyData` schema / `PasskeyPkcs8Codec` /
  KPEX 字段后必跑**——`ISSUE-P2-211` 正是被它揭出的）
- `python tools/doc/gate_readings.py` — **门禁读数单点采集**（§308 立规；清单**直接解析**
  `.github/workflows/build.yml` 的 `hygiene-gate` 段落，故与 CI 不可能漂移；任一条非 0 即退出码 1，
  解析不到命令同样报红）。**每批结案前跑它，并把输出原样贴入批次文档 §3**
- `python tools/doc/preserve_test_failures.py [--selftest]` — **全量单测首轮红证据留存**（`ISSUE-P3-489` 立规）：
  扫描 `*/build/test-results/**` 的 JVM 单测 XML（口径与 `count_test_results.py` **共用**），把失败 / 错误用例的
  类名 + 用例名 + 信息摘要打印并落盘 `build/failure-evidence/<UTC 戳>.txt`；无失败退 0、有失败退 1、
  无 XML 退 2（**不得**当绿）。`test` 非零退出后**先跑它再重跑**。`--selftest` 为口径反校（内嵌正 / 反样本）
- `python tools/device/check_installed_build.py [--expect-symbol <符号>] [--selftest]` — **装机走查前置读数**（§434 立规）：
  核对设备包 `lastUpdateTime` **不早于**本地 APK mtime，**且** APK 的 `classes*.dex` 内含指定符号；
  无 adb / 无设备 / 无包信息一律退出码 **2**（**不得**当绿）。`--selftest` 为口径反校（含反面样本）
- `python tools/device/check_connected_device_results.py [--selftest]` — **设备侧结果非空转断言**（`ISSUE-P3-493` 立规；
  CI 挂在 `device-gate`，紧随 `connectedDebugAndroidTest` 之后）：解析
  `*/build/outputs/androidTest-results/connected/**/TEST-*.xml`，对建有 `src/androidTest` 的五层断言 `tests > 0`——
  `tests == 0` 即**空转**（实测：Android 用户未解锁时 UTP 未装包、`am instrument` 以**成功码**返回零用例，
  任务仍 `BUILD SUCCESSFUL`）；缺结果 XML 判 `2`（**不得**当绿）。`--selftest` 为口径反校（6 组正 / 反样本）
- `python tools/doc/count_line_tiers.py` / `python tools/doc/long_functions.py` / `python tools/doc/check_md_links.py`
  / `python tools/doc/logic_lines.py <文件> <函数名>` / `python tools/doc/count_test_results.py`
  / `python tools/doc/check_resolved_index_sync.py` / `python tools/doc/check_bounded_type_names.py`
  — 五模块行数分档复核（**fail-closed**：`tier1(>500)` 恒 0 + `tier2` 棘轮预算只紧不松）
  / 超长函数复核（**fail-closed**：存在 ≥ 阈值 即退出码 1；CI 用默认阈值 100）
  / `docs/` 相对链接自检（**改任何文档后跑**，断链即退出码 1）
  / 装配表「逻辑行」分类计数（`PD-11` 重开条件的可执行判据）
  / **JVM 单测聚合计数的唯一尺子**（`test` 后跑它，**不要**自己 `glob` `test-results/**/*.xml`——
  那会把截图与真机 `connected` 的旧 XML 算进来，§190 现形过一次）。
  / **`resolved/` 索引一致性**自检（`BATCH_*.md` ↔ `batches/*.md` ↔ `RESOLVED_LOG.md` ↔ `resolved/README.md` 最大编号；
  **改任一批次 / 分册 / 全量索引 / `resolved/README.md` 后跑**，漏登、陈旧行、**重登**即退出码 1）
  / **类型名有界性**机检（`PD-34`；**新增 `*Manager`/`*Util`/`*Helper`/`*Common` 类型后必跑**——
  未登记即退出码 1，扩 `ALLOWED` 须同批回写 `PD-34`；`--selftest` 为口径反校）
  / **Box 内容槽同层兄弟叠放**机检（`ISSUE-P3-337`；`BentoCard` 的内容槽是 `Box`，往里加第二个顶层子节点
  会**互相叠放**而非上下流动，**编译期与 `@Preview` 都可能看不出来** ⇒ **改过 `*/ui/**` 卡片内容后必跑**；
  `ISSUE-P3-457` 扩面至**框架 Box 槽**（Material3 文本框的 `supportingText` 槽同在 `Box` 内）与
  **「多发射助手函数」**（槽里只发一个节点、但该节点顶层发射 ≥2 兄弟，如解锁页四行文案——只看调用点是绿、
  看穿调用点才是红）；
  `--selftest` 用内嵌坏样本反校；读数须把「命中数」与「检查过的调用点数」**一起看**——站点数为 0 的绿没有鉴别力，
  脚本自身对此判红）
  / **`@Preview` 状态覆盖普查**（`ISSUE-P3-340`；`python tools/doc/check_preview_state_coverage.py`，
  **只出读数、不进 CI**）：数「带字面量默认值的 `Boolean` 开关参数有没有被预览画过反向那一态」，
  漏态 ⇒ 该态此前只有真机能看见；`--selftest` 三向反校
  这些计数**一律现跑、不得凭记忆或抄上一批文档**，且**度量工具一律用已知值反校**
  （判据与踩坑史写在各脚本文档串里）；逐字搬移复核用
  `python tools/doc/check_verbatim_move.py <原文件> <本体> [段落文件…]`；「多处重复代码是否真逐字相同」
  的前提成立性用 `python tools/doc/scaffold_block_fingerprint.py <git rev> <目录> <页名>…`
  （**目测登记前提曾造成一次真实回归**，见 `ISSUE-P3-195`）
- CI **`hygiene-gate`**（`.github/workflows/build.yml`）——上述规模 / 链接 / 索引 / 重言断言 / 复核 / 类型名 /
  Box 内容槽 / 启动语言种子接线 / **投影读取安全** / **裸 scope 收口** / **归档面不可回退**机检的 **fail-closed 硬门禁**：`count_line_tiers` +
  `long_functions` + `check_md_links` + `check_resolved_index_sync` + `check_tautological_assertions` +
  `check_recheck_consistency` + `check_bounded_type_names` + `check_box_slot_children` +
  `check_launch_language_seed` + `check_projection_read_safety` + `check_raw_coroutine_scope` +
  `check_archive_monotonicity`，非 0 即红；
  **严禁** `|| true` 吞掉（**条数不写死**：以 `gate_readings.py` 现跑读数为准）。
  本 job 的 checkout 必须 `fetch-depth: 0`（归档回退判据要读 `HEAD~1`）
- `python tools/doc/check_raw_coroutine_scope.py` — **裸 `CoroutineScope(` 收口机检**（`ISSUE-P1-538` 立规；
  **改 `*/src/main/**` 的协程作用域后必跑**）：判据＝**五个生产模块**（`app` / `database` / `crypto` / `sync` / `core`）
  各自 `src/main/**` 内的 `CoroutineScope(` 只允许出现在 `GuardedScope.kt`（`\b` 词边界排除
  `rememberCoroutineScope()`；`import` / 类型引用不含 `(` 故不命中）；命中即退出码 1。
  **扫描面按模块白名单、不用 `*/src/main/**` 通配**（§479 更正：通配会把**未跟踪**的只读参考树 `参考项目/`
  扫进来 ⇒ 本机 2430 文件 / 50 命中，本机红、CI 绿，读数不可复现；白名单下复现 §477 的 731 / 0）。
  `--selftest` 为口径反校。**口径写死在脚本文档串**——上一批的普查口径写死为
  `CoroutineScope(SupervisorJob(` 遂**形态性漏检**裸构造（不带 SupervisorJob 者从未进入普查面）
- `python tools/doc/check_archive_monotonicity.py` — **归档面不可回退机检**（`ISSUE-P2-546` 立规；**改
  `docs/RESOLVED_LOG.md` 或增删 `docs/resolved/batches/` 后必跑**）：判据＝与**基线 rev**（默认 `HEAD~1`，
  可用 `--base` 指定）比较，**RULE-A** 已登记批次号（`| §NN |` 索引行）不得消失、**RULE-B** 批次正文
  `.md` 不得消失；命中即退出码 1，**基线解析不到退 2（不得当绿）**。`--head <rev>` 供口径反校
  （`--base 33a8b86b --head bc7f187a` 实测报 §477 双命中）。立规缘由＝`bc7f187a` 曾把 §477 整树回退而
  全部门禁仍绿（索引四份**互相自洽**，自洽的旧态不可分辨）⇒ 与「闸门存在 ≠ 闸门被执行」成对：
  **闸门被静默拆除**也必须有人拦
- `python tools/doc/check_projection_read_safety.py` — **投影 / 解析 / 交付面敏感读取安全**（五条判据，任一命中即红）：
  ① 登记面（`PROJECTION_FILES`，含通行密钥解析面）不得裸 `readString()` / `readUtf8()` / `readChars()` / `useChars{}` / `useUtf8{}`；
  ② 不得引 `KdbxEntry` 四个 fail-fast getter；③ 展示读口的**新调用点**须登记 `ALLOWED_DISPLAY_READ_FILES`；
  ④ **禁止「先查 `cleared` 再裸读」的两步式**（`ISSUE-P1-537` / `ISSUE-P2-540` 裁决②）；
  ⑤ **非交付面禁止 fail-open 直读 `password` 字段**（`ISSUE-P2-539`，`DELIVERY_FACE_FILES` 登记豁免）。
  `--selftest` 五向反校；**不得**据其绿推定「全仓已无裸读 / 无两步式」（静态启发式边界见脚本文档串）
- `python tools/audit/check_recheck_consistency.py` — 复核报告一致性扫描（**改审计 / 复核报告后必跑**；
  PowerShell 直接可跑。历史命令 `bash …/check_recheck_consistency.sh` 仍可用，薄封装调本文件）
- `python tools/audit/check_tautological_assertions.py` — **「永远为真的断言」机检**（§275 立规；**改 `*/src/test/**`
  的任何断言后必跑**）：判据＝断言实参能否在 `@Test` 函数体内按「纯局部 `val`」口径全部求值，命中即退出码 1；
  `--selftest` 为口径反校；**`var` 观测通道一律豁免**（由生产代码经回调写入者恰为最有鉴别力的形态）。
  口径与边界（静态启发式：容器取值类断言会漏报）见脚本文档串，**不得**据其绿推定「仓内已无重言断言」
- `"$env:USERPROFILE\.android\bin\android-cli.exe" studio <子命令>` — IDE / 设备侧调试首选入口（Android CLI，见下条约定；
  **PowerShell 写法**；Git Bash / MSYS 下同义写法为 `"$USERPROFILE/.android/bin/android-cli.exe"`）

> **Android 调试约定**：设备侧调试**统一走  `adb`**；

> **设备侧测试前置检查（2026-09-22 §263 立规，强制）**：对**装有需要保留的数据／应用**的设备，**禁止**直接运行
> `connectedDebugAndroidTest`——若设备上已装应用的签名与测试包不一致（如设备上装的是 `assembleRelease` 包、测试装的是
> debug 包），UTP 会在 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 之后**卸载该应用**，其内部数据**随之永久丢失**
> （应用声明 `android:allowBackup="false"` 时**无任何**备份可恢复）。应改用 AVD（本机 `Pixel_10`），或先与用户确认
> 该应用的本地数据无需保留。**立规缘由（实测事故）**：§263 开发中即为跑一条设备用例导致 `com.keepasskey`
> 被卸载、用户密码库丢失。

> **设备侧是独立一层，判定以 task 结果为准**：`cargo` 缺失（离线 / 无工具链）时原生内核任务经 `onlyIf` **跳过**、JNI 用例经 `Assume` 跳过
> （有意降级），**构建失败则 fail-closed 直接失败**——严禁再引入吞退出码的开关；真实语料缺失时 `RealKdbxCorpusUnlockTest` 抛
> `AssumptionViolatedException`（task 仍 `BUILD SUCCESSFUL`，但 `TEST-*.xml` 记为 `<failure>`、`skipped=0`）。
> 涉及正则 / XML / 平台 API 的逻辑不可只靠宿主单测。
> **但 task 结果本身也不够（`ISSUE-P3-493` 立规）**：`am instrument` 在「一个用例都没跑」时返回**成功码**，
> UTP 据此归为 `tests=0 + SUCCESS` ⇒ 整层空转而显绿（与「闸门存在 ≠ 闸门被执行」同型）。故完整判定＝
> task 结果 **且** `python tools/device/check_connected_device_results.py` 各层 `tests > 0`。
> 注意该脚本读出的 `failures` **不是**门槛数——`Assume` 跳过在 UTP XML 里同样记 `<failure>`（`skipped` 恒 0）。

> **Rust 单测落位约定（ISSUE-P3-57，须遵守）**：单测放 `crypto/src/main/rust/src/tests/<name>_tests.rs`，源文件中以
> `#[cfg(test)] #[path = "tests/<name>_tests.rs"] mod tests;` 引用——Code scanning 的 `paths-ignore` 只能做文件级排除，
> 内联 `mod tests` 里的测试密钥 / 向量会被 `rust/hard-coded-cryptographic-value` 逐条报为 critical 误报。

> **测试资产纪律与原生面验证义务（细则见 `.codebuddy/rules/engineering-rules.md` §「测试资产纪律」，强制）**：
> ① **用例与对拍向量只允许新增或修改**，不得因「暂时用不上 / 已被替代 / 实现回退」删除（丢失后无法经 `git` 取回）；
> 确需删除时须先确认被删对象已无生产代码可测、在批次文档或限界表登记理由、与生产代码同一次提交入库。
> ② 凡改动 `crypto/src/main/rust/**`、`jni_bridge_ext.rs` 或任一 `Native*` 绑定 / 引擎的原生分派与**探活**，**必须**在设备上跑完
> `:crypto:` / `:database:` / `:sync:` / `:app:` 四层 `connectedDebugAndroidTest` 方准入库；`*/src/androidTest/**` 新增或修改的用例
> 同样必须真机实跑——`compileDebugAndroidTestKotlin` 通过**不构成**验证证据。
> ③ 宿主全绿不代表设备可用（§143 平台剥离版 BC 抢占、§147 全零密钥解密均系「宿主 100% 绿、仅真机失败」）；
> 兜底分支（JCE / BC）的等价性由宿主 `CipherFallbackParityTest` 常态锁定，**不得**以「反正有兜底」为由跳过设备侧。
