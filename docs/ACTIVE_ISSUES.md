# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`；本文件**不保留**已闭环条目的正文或索引段，历史实现与验收证据一律以归档库为单一真相源。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：曾发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md` 等文件「需要删除」，但这些文件早已先行删除，`AGENTS.md` 也已不含相关引用。
   > 条目与代码库演进之间存在时间差，会导致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。
   前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|---|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。

---

## P1 高危与核心功能问题（0 项）

> 当前无待办。历史 **ISSUE-P1-10 / P1-11 / P1-12** 均已闭环，分别见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5（ZT / P1 专项）、§22.7（受信浏览器「包名 + 签名证书指纹」）、
> §24（设备端 KDBX XML 解析全量失败的致命缺陷）。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> 当前无待办。历史 **ISSUE-P2-05 ~ P2-18** 均已闭环，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.21、§22.6（子库解锁接入节流）、§22.8（同步防回滚绑定）。

---

## P3 低危问题、特性接线与体验优化（2 项）

> **状态（2026-09-11）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-57 除下列 2 项外已全部闭环并归档**，
> 逐条目的实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §25）。
> 现存 2 项均为**外部资源依赖型残余**，故保留于本文件**不归档**：
> **ISSUE-P3-23**（**真实 `.kdbx` 语料已于 2026-09-11 入库并跑绿——AC② 闭环，见 §25**；仅余 **arm64 真机数据**）
> 与 **ISSUE-P3-58**（CodeQL Kotlin 抽取器上游阻塞）。

---

### ISSUE-P3-23 (P3-11 残余): arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁

- **优先级**：P3（验证覆盖；依赖外部设备与语料资源）
- **核实时间点与核实方式（2026-09-10）**：经 `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse`
  （已安装 system-image 仅 `android-34` / `android-36.1` 的 **x86_64**，**无任何 arm64-v8a**）、
  `adb devices -l`（**空列表**）、`sdkmanager --list_installed`、`emulator.exe -list-avds` +
  `Pixel_10.avd\config.ini`（`abi.type=x86_64`）、`Test-NetConnection dl.google.com -Port 443`（True）、
  `sdkmanager --list | Select-String "system-images;android-36.1;.*arm64"`（**远端有发布、本机未安装**）
  逐项核实；并核实 `database/src/androidTest/**` 与 `database/build.gradle.kts:41-46` 落盘内容。
- **本批次已消除的阻塞（工程侧就绪，非验证结果）**：
  1. `database` 模块**首次建立 `androidTest` 源集与依赖接线**（`database/build.gradle.kts:10-15`、`:41-46`）；
  2. 设备侧端到端解锁用例落地 `RealKdbxCorpusUnlockTest`，**fail-closed**：语料缺失 → `Assume` 显式跳过
     （跳过消息自解释到「照哪个文件生成、放到哪里」，且**明确声明跳过不代表验收达成**）；
     语料在而伴生元数据缺失/非法（含 `containsRealData=true`、`passphraseIsThrowaway=false`、
     `source` 非 KeePass 系、`kdf` 非法、`entryCount<=0`）→ **硬失败**（「有 .kdbx 但无从断言」不可能蒙混成绿）；
  3. `SelfGeneratedRoundTripInstrumentedTest` **四处**声明「不构成与 KeePass 2.61.1 / KeePassXC 的互操作证据」
     （类名 / KDoc / 方法名 / stdout）；
  4. 语料逐步生成清单写入 `crypto/src/test/resources/argon2-interop/README.md`（KeePass 2.61.1 七步 +
     KeePassXC + 双落位 `src/test/resources` ↔ `androidTest/assets` **不可互替** + 伴生 JSON schema + 命名约定），
     并声明语料口令为公开一次性常量、**严禁任何真实主密码**；
  5. `docs/原生Argon2真机验证记录.md` 已加注本次范围与「未取得」项（x86_64 既有数据**原样保留**）。
- **仍未达成的残余面**：
  1. **arm64 真机 / arm64 模拟器数据未取得** —— 本机无 arm64-v8a 镜像、无真机连接；安装 arm64 镜像属大体积下载
     （超出本批次范围），且 x86_64 宿主上的 arm64 模拟器数据按纪律**不得**与「arm64 真机」同表登记；
     `docs/原生Argon2真机验证记录.md` §4.3 表格**保持待填**；
  2. **真实 KeePass 2.61.1 / KeePassXC `.kdbx` 语料仍未入库** —— 需人工 GUI 建库 + 逐条复核，无人值守流程无法产出；
     语料未入库前设备侧用例按设计**跳过**（注意：**该项不依赖 arm64**，现有 x86_64 AVD 即可执行）。
- **2026-09-11 复核（阻塞前提再确认；核实方式：读 `tools/kdbx-corpus/README.md` §3 并实跑脚本）**：
  1. `python tools/kdbx-corpus/generate_corpus.py --check` 实测 **exit 3**：本机无 `keepassxc-cli`；
  2. 且即便安装，README §3 明确 **KeePassXC CLI `db-create` 无法设定 Argon2 变体/版本与 t/m/p**，
     故真实语料**必须由官方 GUI 建库**（脚本 `--ingest` 只做复核 / 命名 / 写伴生 JSON / 双落位）——
     「无人值守产出真实语料」在本环境**不成立**（推翻先前「脚本自动化已可产出」的期待）；
  3. 脚本自身能力已本地实测通过：`--dry-run` 正常列出双落位目录；`--verify database/src/test/resources/fixtures/test_vault.kdbx`
     正确解析 `argon2d / v19 / t=89 / m=65536KiB / p=4 / 32B salt`，与 `crypto/.../argon2-interop/README.md` §5.1 逐项一致。
  **结论**：本条两处阻塞（GUI 语料、arm64 真机）均为**外部资源依赖**，本地不可消除。
- **2026-09-11（本批次收口复核，核实方式：PowerShell 实测）**：`adb devices -l` 仍为**空列表**；
  `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse | ? FullName -match 'arm64'` 仍**零命中**
  （无 arm64-v8a 镜像）；`python tools/kdbx-corpus/generate_corpus.py --check` 仍 **exit 3**（无 `keepassxc-cli`）。
  **阻塞前提不变，本条不归档。**
- **2026-09-11 追加（设备侧链路验证；核实方式：启动本机 AVD `Pixel_10` / x86_64 / API 36，
  实跑 `.\gradlew.bat :database:connectedDebugAndroidTest`）**：
  **链路可用性已验证**——设备侧用例能真实安装并执行（**本仓首次在真实 Android 运行时跑数据库侧用例**），
  并因此**发现并修复了一处设备端致命缺陷 ISSUE-P1-12**（见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§24**）；
  修复后该任务 **BUILD SUCCESSFUL**、`SelfGeneratedRoundTripInstrumentedTest` **pass（0.293s）**。
  据此**阻塞项收窄为两项**：① 真实语料（须官方 GUI 建库——本环境无 `keepassxc-cli`，且该 CLI 无法设定
  Argon2 变体/版本与 t/m/p）；② arm64 真机数据（本机无 arm64-v8a 镜像、无设备）。**本条仍不归档。**
- **措辞修订（同批实测，如实）**：原「语料缺失 → Assume 显式跳过」在**报告呈现**上与实情不符——
  AGP 的 `build/outputs/androidTest-results/connected/debug/TEST-*.xml` 把 `AssumptionViolatedException`
  记为 **`<failure>`（`skipped=0`）**，**但 task 级仍 `BUILD SUCCESSFUL`**。
  即：门禁语义正确（语料缺失不会把任务弄红，也不代表验收标准②达成），但**报告会误导**读者以为用例失败；
  验收标准②的「不再是 skip 且全绿」以该报告口径为准（语料就位后应转为 `pass`）。
- **验收标准**：① `.\gradlew.bat :database:assembleDebugAndroidTest` 编译通过（**2026-09-10 已实测通过**，
  见批次 A 归档 §6.4 门禁证据）；② 真实语料（含同名 `.json`）
  入库两处后 `:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` **不再是 skip** 且全绿；
  ③ arm64 真机（`adb shell getprop ro.product.cpu.abi` = `arm64-v8a`）上取到 §4.3 表格数据并与 §4.1/§4.2 **分表**归档；
  ④ 归档文件「待填」表按实际情况回填（**严禁编造数据**）。
- **禁止**：以 x86_64 模拟器或宿主侧数据填充 §4.3；把「用例就绪」表述为「验证通过」；
  用自生成 `.kdbx` 往返冒充互操作证据。
- **2026-09-11 进展（AC② 闭环；核实方式：解密探针逐条核对 + 设备侧实跑 `:database:connectedDebugAndroidTest`）**：
  1. **真实语料已入库**：本机既有 **KeePassXC 官方产物** `KeePasskey测试/测试.kdbx`
     （内层 `Meta/Generator=KeePassXC`，Argon2d v19 t=89 m=64MiB p=4，AES-256-CBC，32B 盐）经
     **解密探针逐条核对**——14 条条目**全部**为「模拟账号」占位数据、**零真实数据**、
     KDF `secret(K)`/`associatedData(A)` 均为 `null`——后复制为规范名
     `argon2d-v19-t89-m64-p4-keepassxc.kdbx`，连同 `111.keyx`（XML KeyFile v2.0）与同名伴生 `.json`
     **双落位**（`crypto/src/test/resources/argon2-interop/` 与 `database/src/androidTest/assets/argon2-interop/`，不可互替）；
     并已用 `generate_corpus.py --verify --json` 交叉校验「文件头 ↔ 伴生 JSON」逐字段一致；
  2. **设备侧证据**：`RealKdbxCorpusUnlockTest` 由 skip 转为 **2/2 pass、0 skip、0 failure**
     （设备 `emulator-5554`，x86_64 / API 36；报告 `build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 16.xml`）；
  3. **用例增强**：伴生 JSON 新增**可选**字段 `passphrase`（本语料自带专用一次性口令），缺省回退公开常量
     `Test-Vector-Only-2026!`；同步修订 `crypto/.../argon2-interop/README.md` §4 / §6.2 与
     `database/.../assets/argon2-interop/README.md`；
  4. **工具修正**：`tools/kdbx-corpus/generate_corpus.py` 的 `canonical_name` 内存单位由 KiB 改为 **MiB**
     （对齐 README §6.1「文件名即声明」，64MiB → `-m64`），非整 MiB 走 fail-closed 拒绝。
  **本批次后 AC② 闭环；本条残余仅剩 AC③/④（arm64 真机数据），故仍保留不归档。**
  （全量单测 `test --rerun-tasks`：**1370 例 / 0 失败 / 0 错误 / 13 跳过**，与基线持平。）

---

### ISSUE-P3-58: Kotlin/Java 侧无 CodeQL 分析覆盖（实测结论：阻塞于上游 CodeQL 的 Kotlin 版本支持）

- **优先级**：P3（静态分析**覆盖面**缺口；**非本批次引入的回归**——默认设置时期二者即为空分析；
  2026-09-11 实测后登记为**已接受的风险**，见文末结论）
- **核实时间点与核实方式（2026-09-11）**：
  1. `gh api "/repos/Wuming155/KeePasskey/code-scanning/analyses?per_page=100" --paginate` 逐条统计
     `category` / `results_count` / `rules_count`：`/language:java-kotlin` 全部记录均为
     **`rules=0` / `results=0`**（最近一条 `2026-09-09T14:31:13Z`）；`rules=0` 表示**没有任何查询运行**
     （对照：正常语言为 rust 26 / python 43 / actions 17）；
  2. 本仓源码面：`app` / `core` / `crypto` / `database` / `sync` 五模块**全为 Kotlin/Java**（本仓主语言），
     故缺口的**绝对规模最大**；
  3. `c-cpp` 面**已排除**：2026-09-11 经 `Glob` 核实
     `{app,core,crypto,database,sync,tools}/**/*.{c,cc,cpp,cxx,h,hpp}` **零命中**；仓库内命中的 C/C++ 文件
     **全部位于 `.gitignore` 忽略的 `参考项目/`**。历史 `c-cpp` 分析系 C Argon2 遗留（ISSUE-P2-14 已移除）之残留，
     **无需纳入，也不应再纳入**；
  4. 能力边界（GitHub 官方文档 `codeql-build-options-and-steps-for-compiled-languages`，2026-09-11 拉取原文）：
     **Java 支持 `none` / `autobuild` / `manual`；Kotlin 仅支持 `autobuild` 或 `manual`**（**无 `none`**）
     → 想真正覆盖 Kotlin，**必须提供真实构建**（Gradle + Android SDK），无构建抽取对 Kotlin 无效；
  5. 版本风险：`codeql.github.com` 的「Supported languages」页对 Kotlin 的支持上界在不同文档版本下
     分别写作 **2.3.2*x*** 与 **2.4.1*x***，而本仓为 **Kotlin 2.4.20**（`gradle/libs.versions.toml:13`）
     → 即便补上构建，抽取器对 2.4.x 的支持程度**须先实测确认**，不得假定可用。
- **影响**：CodeQL 切换到 advanced setup 后，`.github/workflows/codeql.yml` 的 matrix 仅含
  `rust` / `python` / `actions`。就**有效**覆盖面而言与默认设置时期持平（当时即为空跑），
  但**名义覆盖**（Security 页不再有该语言的条目）属**需要显式决策**的范围收缩——
  本条目即为该决策的载体。
- **候选处置（均须留痕）**：
  1. **补构建纳入 `java-kotlin`**：工作流内先装 Android SDK（`android-actions/setup-android`，与
     `build.yml` Fast gate 同源做法），以 `build-mode: autobuild` 或 `manual`（显式 `./gradlew` 编译命令）驱动。
     **收益最大**（覆盖本仓主语言）；**代价**：分析时长与脆弱点显著上升（AGP / JDK / NDK 版本链），
     且需先实测 Kotlin 2.4.20 是否被抽取器支持。
  2. **保持现状，显式登记为「已接受的风险」**：在 `docs/` 与工作流注释中写明「Kotlin 侧无 CodeQL 覆盖，
     由 Android Lint + 1370 例单测 + 人工审计承接」，作为维护者决策留痕。成本最低，但缺口长期存在。
  3. **先做可行性实测**：在特性分支上加一个 `java-kotlin` job（装 SDK + autobuild），
     只求拿到「`rules>0` 且能产出分析」的证据；跑不通则记录**具体失败点**（缺 SDK / 版本不支持 / 超时）。
- **实测结论（2026-09-11，方案 3 已执行）**：**当前不可纳入，阻塞在 CodeQL 上游**。
  在特性分支以草稿 PR #7 试跑（run `34587552395` 的 `Analyze (java-kotlin)` job）：JDK 21 / Android SDK /
  编译平台与 build-tools / CodeQL init **全部成功**，构建阶段 **31 个任务真实执行**，
  失败点为 `:core:compileDebugKotlin`，日志原文：

  ```text
  > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
     > Kotlin version 2.4.20 is too recent. CodeQL currently supports versions below 2.4.20
  ```

  即 CodeQL 在受跟踪构建中注入的 Kotlin 编译器插件**主动拒绝 Kotlin 2.4.20**，与官方文档给出的支持上界一致。
  **非本仓配置问题**（SDK 安装、依赖解析、资源与 R 文件生成等均成功）。
- **结论与风险接受**：`java-kotlin` **暂不纳入** CodeQL matrix，登记为**已接受的风险**——
  Kotlin 侧静态安全分析由 **Android Lint + 1370 例单测 + 人工审计**承接。
  **不得**为提高分析覆盖而回退 Kotlin 版本：本仓 2.4.20 承载 `CVE-2026-53914` 的**真修复**
  （[RESOLVED_LOG.md](RESOLVED_LOG.md) §7），回退等于用真实漏洞换「看起来有覆盖」。
- **解除条件（可复现配方，已留存于 PR #7 的 diff 与评论）**：CodeQL 的 Kotlin 抽取器支持 `>= 2.4.20` 后重试——
  matrix 加 `java-kotlin` + `build-mode: manual`；前置 JDK 21 + Android SDK（`platforms;android-37.0` /
  `build-tools;37.0.0`，**不装 NDK**）；构建仅跑五模块 `compileDebugKotlin`（不打包 → **不触发**
  `cargoNdkBuild`）；**不接** `gradle/actions/setup-gradle`（避免构建缓存使 compile 变 UP-TO-DATE /
  FROM-CACHE，导致抽取器拿不到编译单元）。判据：`code-scanning/analyses` 出现 `/language:java-kotlin`
  且 **`rules > 0`**。
- **验收标准（就本条而言已达成）**：① 给出明确二选一结论并留痕 → **已达成**（结论：不纳入 + 上游阻塞 + 解除条件）；
  ② 若纳入须有 `rules>0` 证据 → **不适用**（结论为不纳入）；③ 不得以「默认设置当年也这样」跳过决策
  → **已避免**（附本次实测证据）。
- **本条状态**：**本仓无进一步动作，等待上游支持**——与 ISSUE-P3-23 同属**外部资源依赖型残余**，
  保留于本文件**不归档**。
- **禁止**：为求「看起来有覆盖」而把 `java-kotlin` 加进 matrix 却任其构建失败（`rules=0`）空跑（**虚假覆盖**）；
  在未实测的情况下宣称「CodeQL 已覆盖 Kotlin」；**以回退 Kotlin 版本**换取分析覆盖；为纳入而弱化任何既有门禁。
