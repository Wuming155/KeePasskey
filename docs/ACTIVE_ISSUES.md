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
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P0 阻断级与致命安全漏洞（0 项）

> 当前无待办。**ISSUE-P0-04**（库内 ≥1 条目时库列表渲染必崩）于 2026-09-11 修复归档，见
> [RESOLVED_LOG.md](RESOLVED_LOG.md) §26。

---

## P1 高危与核心功能问题（0 项）

> 当前无待办。**ISSUE-P1-13**（写侧 Argon2 `P` UInt64 违反 KDBX4 规范）于 2026-09-11 整改归档，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §27.1；**ISSUE-P1-14**（后台自动锁定）复核为行为符合
> 设计结案，见 §27.2；**ISSUE-P1-15**（明文导入 KeePass XML 在 Android 运行时全量失败）同日
> 发现并整改归档，见 §27.3。

---

## P2 中危缺陷与协议/测试缺口（4 项）

> **历史批次**：**ISSUE-P2-23**（带密钥文件解锁后指纹快速解锁不可用，复合封印整改）于 2026-09-12
> 整改归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §29.1；**ISSUE-P2-22**（「Argon2 参数」应用无
> 真实效果）同日归档于 §28.1；P2-19 / P2-20 / P2-21 已于 §27 归档。
> **新增（2026-09-12）**：ISSUE-P2-24 ~ P2-27 由「参考项目（KeePassDX / keepass2android / Monica）
> 对比分析」产出，分属性能瓶颈 / 协议互操作 / 测试有效性缺口。

---

### ISSUE-P2-24（新登记）：大附件整库常驻内存，无独立磁盘缓存池（潜在 OOM）

- **优先级**：P2（性能瓶颈 / 内存占用；大附件库存在 OOM 与卡顿风险）
- **核实时间点与核实方式（2026-09-12）**：
  1. Read `core/src/main/java/com/keepasskey/core/model/KdbxAttachment.kt`：`data: ByteArray` 为常驻内存字段
     （第 13 行），`resolveData(binaryPool)` 仅做池内引用解析（第 20 行），无落盘路径；
  2. 全仓检索未见 `BinaryCache` 等价的大附件磁盘缓存实现；
  3. `AGENTS.md` §6 自承「KDBX 对象树仍整体驻留内存（解析已流式化）」。
- **问题描述**：附件字节随对象树整体驻留内存；大附件（数十 MB 级）或库内存在多个附件时，
  列表 / 详情 / 同步 / 写回全链路都会携带整批字节，存在 OOM 与 GC 压力。
- **参考做法（据 `docs/references/KeePassDX-架构分析.md`）**：KeePassDX 以 `BinaryPool`（引用）+
  `BinaryCache`（按需落盘 `cacheDir`）分离「逻辑引用」与「物理字节」，并配
  `Limits.isMemorySufficientForBinary` 内存门槛保护。
- **验收标准（待整改）**：① 超过阈值（建议 1 MiB，可配）的附件在读取 / 写回时走磁盘缓存，不整入内存；
  ② 会话锁定 / 关闭时对称清理缓存目录；③ 缓存文件权限与 `SyncCache` 同基线（文件 0600 / 目录 0700）；
  ④ 有回归用例证明「不改动 KDBX 字节语义」（去重与引用池一致性）。
- **禁止**：以「宿主侧未复现 OOM」为由判定无需整改；把附件字节改为 `String` 中转。

---

### ISSUE-P2-25（新登记）：S3 上传 / 下载以整块 `ByteArray` 为载荷，无流式 / 分段

- **优先级**：P2（性能瓶颈 / 内存占用）
- **核实时间点与核实方式（2026-09-12）**：Read `sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt`
  ——`download(remotePath): Result<ByteArray>`（第 186 行）、`upload(..., data: ByteArray, ...)`（第 225-227 行）；
  对照 Read `app/.../sync/SyncProviderResolver.kt:31-37` 显示 WebDAV 侧已有
  `chunkedUploadEnabled` / `chunkSizeMb` 分块传输配置。
- **问题描述**：S3 路径将整库字节一次性物化于内存（上传与下载皆是），与 WebDAV 的分块能力不对称；
  库体积增长时存在 OOM 风险，失败后亦无法续传。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：其 WebDAV / 对象存储路径以
  事务化分块写入为主，避免整块物化。
- **验收标准（待整改）**：① `SyncProvider` 增加流式接口（如 `uploadStream(InputStream)`，
  默认实现可回落现有 `ByteArray` 以保持兼容）；② S3 走 `InputStream` 直传或 Multipart Upload；
  ③ 大库（建议 ≥ 50 MiB 构造用例）同步不产生整库内存峰值；④ 保持既有 ETag / 条件写语义不变。
- **禁止**：为省事关闭边界校验；以降低现有 ETag 条件写强度换取流式。

---

### ISSUE-P2-26（新登记）：云同步后端仅 WebDAV + S3 两类，协议覆盖窄

- **优先级**：P2（协议互操作 / 功能覆盖）
- **核实时间点与核实方式（2026-09-12）**：Read `app/.../ui/screens/settings/SettingsUiState.kt:13-19`
  （`CloudSyncProvider` 枚举仅 `WEBDAV` / `S3_COMPATIBLE`）；Read `app/.../sync/SyncProviderResolver.kt:39-103`
  （`resolveProvider()` 仅两个分支）；`sync/provider/` 下仅 `SyncProvider` 接口 + `webdav/` + `s3/`。
- **问题描述**：无法覆盖 SFTP / FTP / Dropbox / OneDrive / Google Drive 等主流后端，用户迁移成本高。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：`IFileStorage` 插件化抽象 +
  协议前缀路由，覆盖 Local / FTP / WebDAV / ownCloud / Nextcloud / SFTP / Dropbox / Google Drive /
  OneDrive / pCloud / Mega / SMB / content:// 等 12+。
- **验收标准（待整改）**：① 按 `@IntoMap @StringKey` 重构 Provider 注册表（保持开闭，不改调用方）；
  ② **首批实现 SFTP**（自建 NAS 用户群与现有 WebDAV 群体重叠、无需 OAuth）；③ SFTP 凭据复用现有
  `SyncCredentialsStore` + Keystore 封印通道，用毕清零；④ 同步周期 / 冲突 / 防回滚等既有不变量对
  SFTP 同样成立（复用 `SyncEngine`，不改语义）。
- **禁止**：为新增后端绕过 `SyncProvider` 抽象直接调用网络栈；在设置页以「即将支持」占位冒充可用。

---

### ISSUE-P2-27（新登记）：app 侧自动填充结构解析缺真实 `AssistStructure` 快照 fixture 回归

- **优先级**：P2（测试有效性缺口）
- **核实时间点与核实方式（2026-09-12）**：`AGENTS.md` §6 明示「`app` / `sync` 模块无 `androidTest`
  源集……涉及正则 / XML / 平台 API 的静态逻辑不能仅凭宿主单测判定在 Android 上可用」；并核对既有条目
  ISSUE-P3-66（其范围为**真实系统服务接管后的端到端实测**，依赖外部环境）。
- **问题描述**：`AutofillFieldScanner` / 表单解析等逻辑依赖 Android 运行时对象（`AssistStructure`），
  当前仅宿主 JVM 覆盖，无法拦截「JVM 过、Android 挂」类缺陷（§24 / §26 已有先例）。
  与 ISSUE-P3-66 **互补不重复**：本条聚焦**解析层**的真实快照 fixture 回归，P3-66 聚焦端到端链路。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：以真实视图树 JSON fixture
  （chrome / firefox / 银行类页面等十余个）做自动填充解析回归。
- **验收标准（待整改）**：① 采集若干真实应用 / 浏览器的 `AssistStructure` 快照固化为 fixture
  （脱敏，禁止含真实口令）；② 在 Android 运行时（instrumented 或等价）对字段扫描 / 匹配做回归断言；
  ③ 覆盖用户名 / 密码 / OTP / 多字段 / 隐藏字段等关键形态；④ 用例失败可复现具体字段判定路径。
- **禁止**：以宿主 JVM 单测冒充 Android 运行时验证；把真实用户表单明文写入 fixture 仓库。

---

## P3 低危问题、特性接线与体验优化（5 项）

> **状态（2026-09-12）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-68 除下列外部资源依赖型残余外已全部
> 闭环并归档**，逐条实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §32）。
> 2026-09-12 存量修复批次闭环 P3-63 / P3-65 / P3-67（§28.2 ~ §28.4）；
> **ISSUE-P3-68**（重试节流开关与自定义最长锁定时长）同日闭环归档（§29.2）；
> **外部安全审计整改批次 P3-69 ~ P3-72** 同日闭环归档（§30）；
> **文档类存量整改批次 P3-75 / P3-77** 同日闭环归档（§31）；
> **CSV 导入 / 导出扩充 P3-73** 同日闭环归档（§32）。
> **新增（2026-09-12）**：ISSUE-P3-74 / P3-76 由「参考项目（KeePassDX / keepass2android / Monica）
> 对比分析」产出，属特性补齐与长期评估项（**非外部资源依赖**）。

---


### ISSUE-P3-23 (P3-11 残余): arm64 真机 instrumented 验证与真实 `.kdbx` 语料端到端解锁

- **优先级**：P3（验证覆盖；依赖外部设备与语料资源）
- **核实时间点与核实方式（2026-09-10）**：经 `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse`
  （已安装 system-image 仅 `android-34` / `android-36.1` 的 **x86_64**，**无任何 arm64-v8a**）、
  `adb devices -l`（**空列表**）、`sdkmanager --list_installed`、`emulator.exe -list-avds` +
  `Pixel_10.avd\config.ini`（`abi.type=x86_64`）、`Test-NetConnection dl.google.com -Port 443`（True）、
  `sdkmanager --list | Select-String "system-images;android-36.1;.*arm64"`（**远端有发布、本机未安装**）
  逐项核实；并核实 `database/src/androidTest/**` 与 `database/build.gradle.kts:41-46` 落盘内容。
- **已消除的阻塞（工程侧就绪）**：`database` 模块 androidTest 源集与依赖接线、设备侧端到端解锁用例
  `RealKdbxCorpusUnlockTest`（fail-closed）、真实 KeePassXC 语料双落位（2026-09-11 AC② 闭环，§25）。
- **仍未达成的残余面**：
  1. **arm64 真机 / arm64 模拟器数据未取得**——本机无 arm64-v8a 镜像、无真机连接；安装 arm64 镜像属
     大体积下载（超出批次范围），且 x86_64 宿主上的 arm64 模拟器数据按纪律**不得**与「arm64 真机」同表登记；
  2. 真实语料已于 2026-09-11 入库（KeePassXC 官方产物），AC② 已闭环（§25）。
- **2026-09-11 复核（阻塞前提再确认）**：`python tools/kdbx-corpus/generate_corpus.py --check` 仍
  **exit 3**（无 `keepassxc-cli`）；且 README §3 明确 KeePassXC CLI `db-create` 无法设定 Argon2 变体/
  版本与 t/m/p，真实语料必须官方 GUI 建库——本条两处阻塞均为**外部资源依赖**，本地不可消除。
- **验收标准**：① `:database:assembleDebugAndroidTest` 编译通过（已达成）；② 真实语料入库后
  `RealKdbxCorpusUnlockTest` 不再 skip 且全绿（**2026-09-11 已达成**：2/2 pass、0 skip、0 failure）；③
  arm64 真机（`adb shell getprop ro.product.cpu.abi` = `arm64-v8a`）上取到 `docs/原生Argon2真机验证记录.md`
  §4.3 表格数据并与 §4.1/§4.2 **分表**归档；④ 归档「待填」表按实际情况回填（**严禁编造数据**）。
- **禁止**：以 x86_64 模拟器或宿主侧数据填充 §4.3；把「用例就绪」表述为「验证通过」；用自生成
  `.kdbx` 往返冒充互操作证据。

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
  3. `c-cpp` 面**已排除**：`{app,core,crypto,database,sync,tools}/**/*.{c,cc,cpp,cxx,h,hpp}` 零命中；
     仓库内命中的 C/C++ 文件全部位于 `.gitignore` 忽略的 `参考项目/`；
  4. 能力边界（GitHub 官方文档 `codeql-build-options-and-steps-for-compiled-languages`，2026-09-11 拉取原文）：
     **Java 支持 `none` / `autobuild` / `manual`；Kotlin 仅支持 `autobuild` 或 `manual`**（**无 `none`**）
     → 想真正覆盖 Kotlin，**必须提供真实构建**（Gradle + Android SDK），无构建抽取对 Kotlin 无效；
  5. 版本风险：本仓为 **Kotlin 2.4.20**（`gradle/libs.versions.toml:13`）→ 即便补上构建，抽取器对
     2.4.x 的支持程度**须先实测确认**，不得假定可用。
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
  Kotlin 侧静态安全分析由 **Android Lint + 单测全量回归 + 人工审计**承接。
  **不得**为提高分析覆盖而回退 Kotlin 版本：本仓 2.4.20 承载 `CVE-2026-53914` 的**真修复**
  （[RESOLVED_LOG.md](RESOLVED_LOG.md) §7），回退等于用真实漏洞换「看起来有覆盖」。
- **解除条件（可复现配方，已留存于 PR #7 的 diff 与评论）**：CodeQL 的 Kotlin 抽取器支持 `>= 2.4.20` 后重试——
  matrix 加 `java-kotlin` + `build-mode: manual`；前置 JDK 21 + Android SDK（`platforms;android-37.0` /
  `build-tools;37.0.0`，**不装 NDK**）；构建仅跑五模块 `compileDebugKotlin`（不打包 → **不触发**
  `cargoNdkBuild`）；**不接** `gradle/actions/setup-gradle`（避免构建缓存使 compile 变 UP-TO-DATE /
  FROM-CACHE，导致抽取器拿不到编译单元）。判据：`code-scanning/analyses` 出现 `/language:java-kotlin`
  且 **`rules > 0`**。
- **本条状态**：**本仓无进一步动作，等待上游支持**——与 ISSUE-P3-23 同属**外部资源依赖型残余**，
  保留于本文件**不归档**。
- **禁止**：为求「看起来有覆盖」而把 `java-kotlin` 加进 matrix 却任其构建失败（`rules=0`）空跑（**虚假覆盖**）；
  在未实测的情况下宣称「CodeQL 已覆盖 Kotlin」；**以回退 Kotlin 版本**换取分析覆盖；为纳入而弱化任何既有门禁。

---


### ISSUE-P3-66（新登记）：自动填充 / Passkey 端到端链路缺设备侧实测（依赖外部环境）

- **优先级**：P3（验证覆盖；依赖外部环境：系统凭据服务接管 + 含登录表单的浏览器/测试页）
- **核实时间点与核实方式（2026-09-11，设备实操部分核实）**：
  1. 「设置 → 自动填充与 Passkey」页渲染正常，如实显示「系统未选择本应用为自动填充服务，请前往系统设置启用」；
  2. Credential Manager / Passkey 支持开关、下发前二次确认、30 秒免重复确认（默认关）等控件齐全；
  3. 解锁后常驻通知确认存在（`dumpsys notification`：`keepasskey_unlocked_status` 通道，ONGOING）；
  4. **未实测**：把本 App 设为系统自动填充服务 + Credential Manager 提供者后的真实表单填充、
     Passkey 创建/断言端到端、TOTP 通知点击行为（`notification/` 包）——需先配置系统服务并安装
     含登录表单的测试页/浏览器，超出本批次环境。
- **验收标准（待整改）**：① 系统服务接管后，在 Chrome/测试页登录表单上完成一次真实填充
  （覆盖下发前二次确认与 30 秒免重复确认两分支）；② 完成一次 Passkey 创建 + 站点断言端到端；
  ③ TOTP 通知渠道创建与点击行为验证；④ 以上均有设备侧取证（uiautomator dump / dumpsys）。
- **禁止**：以宿主 JVM 单测覆盖替代设备侧端到端验证；在未实测时宣称「自动填充已验证可用」。

---

### ISSUE-P3-74（新登记）：条目模板机制僵化（硬编码 5 个，不可自定义 / 导入）

- **优先级**：P3（进阶特性接线）
- **核实时间点与核实方式（2026-09-12）**：Read `app/.../data/repository/VaultTemplateFactory.kt`
  ——`internal object`，`buildTemplateGroup()` 硬编码「网页登录 / 信用卡 / WiFi / 安全笔记 / SSH 密钥」
  5 条，无外部模板加载入口。
- **问题描述**：用户无法定义、保存或导入模板，亦无「从当前条目另存为模板」通道。
- **参考做法（据 `docs/references/KeePassDX-架构分析.md`）**：`element/template/*`
  （Template / TemplateEngine / TemplateBuilder / TemplateField）以 `CustomData` 承载模板定义，
  支持模板字段继承与实例化。
- **验收标准（待整改）**：① 模板定义以 `KdbxGroup.customData`（键前缀建议 `KeePasskey.Template.`）
  持久化，跨端同步不丢；② 支持「从当前条目另存为模板」与从模板实例化；③ 实例化复用既有字段映射，
  不引入第二套字段语义；④ 保留内置 5 个模板作为初始内容（幂等安装语义不变）。
- **禁止**：把模板改成本地偏好存储（会破坏跨端同步与库自包含性）。

---

### ISSUE-P3-76（新登记）：密码 / 主密码输入未禁用输入法个性化学习

- **优先级**：P3（低危隐私加固）
- **核实时间点与核实方式（2026-09-12）**：全仓 `app/src/main` 检索 `IME_FLAG_NO_PERSONALIZED_LEARNING`
  与 `InputMethodService` **零命中**；敏感输入现仅依赖 `KeyboardType.Password` 等常规配置。
- **问题描述**：第三方输入法可能对用户输入做个性化学习 / 候选记忆，主密码与条目口令存在被输入法
  词库记录的风险面。
- **参考做法（据 `docs/references/keepass2android-架构分析.md`）**：`Util.SetNoPersonalizedLearning`
  显式关闭输入法学习。
- **验收标准（待整改）**：① 主密码、条目口令、生成器口令预览等**全部敏感输入路径**显式禁用个性化学习
  （`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`，Compose 侧经 `PlatformImeOptions` 等机制下发）；
  ② 有回归断言覆盖各敏感输入组件；③ 非敏感输入（如搜索框）行为不变。
- **禁止**：仅在部分页面接线导致旁路；以自定义 `VisualTransformation` 冒充已关闭输入法学习
  （防的不是同一威胁）。
- **框架阻塞实测（2026-09-12，读取本机 Compose 源码核实）**：本仓 `androidx.compose.*` 为
  **1.11.4**（BOM 2026.08.00）。逐一核对 Compose 的 `EditorInfo` 构造链后确认**无任何公开 API 可下发
  该整型标志**：
  1. `foundation/androidMain/.../text/input/internal/EditorInfo.android.kt` 的 `EditorInfo.update(...)`
     仅由 `imeAction` 枚举构造 `this.imeOptions`，并附加 `IME_FLAG_FORCE_ASCII` / `IME_FLAG_NO_ENTER_ACTION` /
     `IME_FLAG_NO_FULLSCREEN`，**不设也无可传入 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的入口**；
  2. `ui-text/androidMain/.../input/PlatformImeOptions.android.kt` 的 `PlatformImeOptions` **仅**暴露
     `privateImeOptions: String?`（映射 `EditorInfo.privateImeOptions` 自由字符串，主流输入法**不解析**该
     字符串来禁用学习），**无 imeOptions 位域**；
  3. `KeyboardOptions.toImeOptions()`（`foundation/commonMain`）亦仅承载 `ImeAction` / `singleLine` 等，
     不含原始位域；
  4. 官方路线（`ui/androidMain/.../platform/PlatformTextInputMethodRequest.android.kt` 的
     `createInputConnection(outAttributes: EditorInfo)`）允许拦截 `EditorInfo`，但它属**平台文本输入会话**
     私有扩展点，需以 `PlatformTextInputSession.startInputMethod` 自行实现整个输入会话，**无法与
     M3 `OutlinedTextField` 组合**（等于重造文本输入控件）；
  5. 参考项目外证：开源项目 spela（PR #1114）对同类诉求的结论一致——「Compose 不在公开 Kotlin API 暴露
     这些 int 标志，只能下沉到 Android View 系统包裹真实 `EditText`」。
- **本次处置：登记为框架阻塞的已接受残余风险，不实施**（与 ISSUE-P3-58 同属「本地不可消除、保留跟踪」）：
  - **残余风险已部分缓解**：敏感输入经 [SecurePasswordField](../app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt)
    统一走 `KeyboardType.Password`（→ `TYPE_TEXT_VARIATION_PASSWORD`），主流输入法（Gboard / SwiftKey /
    Samsung / HeliBoard）对该 inputType **默认不做个性化学习**；显式标志只是更强一层的提示。
  - **不采用高风险代偿**：把安全关键的 `SecurePasswordField` 整体改写为 `AndroidView(EditText)`
    会牺牲 M3 外观 / 无障碍 / 现有 CharArray 桥接与擦除契约，风险与 P3 收益不成比例，**本次不做**。
  - **AC③ 语义**：因未接线，非敏感输入行为天然不变（未产生任何旁路）。
- **解除条件（可复现配方）**：若 Compose 后续版本在 `PlatformImeOptions` / `KeyboardOptions` 暴露
  `imeOptions` 位域（或提供 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的公开入口），则在 `SecurePasswordField`
  统一接线并补回归断言（覆盖主密码 / 条目口令 / TOTP / 同步凭据各调用点），届时即可闭环本条。

---

