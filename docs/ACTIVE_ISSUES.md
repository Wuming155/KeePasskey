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

## P2 中危缺陷与协议/测试缺口（1 项）

### ISSUE-P2-22（新发现）：「Argon2 参数」对话框「应用参数」无真实效果（同返回键锁定族的死设置）

- **优先级**：P2（用户显式修改 KDF 强度的操作被静默丢弃——既不重派生密钥也不落盘，且 UI 回显造成「已生效」假象）
- **核实时间点与核实方式（2026-09-11，设备实操 + 字节级地面真值）**：
  1. 设备实操：设置 → 密码库与加密 → Argon2 参数 → 并行线程数选「4 线程」→ 「应用参数」→
     页面回显立即变为 `64 MB · 2 轮 · P=4`；
  2. `run-as` 取回 `files/passwords.kdbx` 经 `build/vd.py` 解析外层头：`P: type=0x05/0x04 raw=02000000`
     ——**值仍为 2 且头未随 UI 改动重写**；该文件其后被 `generate_corpus.py` / keepassxc-cli 交叉确认
     与 UI 显示不符；
  3. 代码核查（2026-09-11，全仓 grep）：`setArgon2Parameters`（`SettingsPreferencesController.kt`）
     仅 `databaseConfigStateFlow.update`（内存回显）——**无任何会话写入、无重派生、无持久化**；
     `KdbxHeader.kt:137` 的建库默认 `parallelism = 2` 亦无运行时修改通道。
- **影响**：用户据此调高 KDF 强度的操作完全无效，且参数显示（P2-19 修复后已与文件头一致）会被
  内存回显再次污染，形成新的「显示与真实不符」。
- **整改方向**：① `onApplyParameters` 接入 `DatabaseSession.updateDatabaseMeta { … }` 更新会话
  KDF 参数并触发重派生 + `save()`（下次解锁生效或立即重加密，需对照 KeePassDX/KP2A 语义取舍）；
  ② 回显流改由会话头下发（P2-19 已建通道），禁止 UI 层自持状态；③ 至少在落地前如实禁用入口。
- **验收标准（待整改）**：① 应用参数后外层头变体字典 `I/M/P` 与所选值一致且经重派生后新旧密码
  语义正确；② 冷启动后回显与文件头一致；③ 设备侧 + 契约测试覆盖。
- **禁止**：只把参数写入 UI 状态流冒充生效（假闭环）；在不重派生密钥的情况下只改文件头。


---

## P3 低危问题、特性接线与体验优化（5 项）

> **状态（2026-09-11）**：历史 P3 批次 **ISSUE-P3-01 ~ P3-57 除下列 2 项外部资源依赖型残余外已全部
> 闭环并归档**，逐条实现细节与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)（§3 ~ §25）。
> 2026-09-11 设备侧批次新闭环 P3-59 / P3-60 / P3-61 / P3-62（见 §27.7 / §27.8），
> 并新登记 **ISSUE-P3-63（升级为确认缺陷）** 与 **ISSUE-P3-65**。

---

### ISSUE-P3-63（已升级为确认缺陷）：库内容变更后「密码库」列表不即时刷新（模板安装 / 导入均可复现）

- **优先级**：P3（功能显示时序：数据已正确落库，仅 UI 快照陈旧；冷启动后数据完整可见）
- **核实时间点与核实方式（2026-09-11 两次设备实测）**：
  1. 原登记（同日前一批次）：「条目模板 → 立即安装」后切 Tab 回「密码库」列表仍无 `模板` 分组，
     锁定→解锁（强制重载）后才出现；
  2. 本批次新增复现：向新建空库导入 KeePass XML（导入报告「新增 1 条 / 2 条」）→ 返回列表仍显示
     「当前目录下没有凭据或文件夹」→ `am force-stop` 冷启动解锁后条目**全部可见**——
     证明数据已持久化、仅内存列表流未刷新。
- **已排除的方向**：`SessionContentMutations.saveEntry/saveGroup` 均为 copy-on-write 替换
  `databaseFlow.value`（StateFlow 会重发）；`VaultListViewModel.uiState` 的 combine 链路包含
  `getEntries()`（映射自 `databaseSession.databaseFlow`）；同会话后续再次导入时列表**能**正常刷新
  （2026-09-11 实测第二次导入返回即见新条目）——现象呈**条件性**（新建库后首轮导入必现，之后消失），
  疑与 `WhileSubscribed(5000)` 首次订阅窗口 / `@EntryDisplayDispatcher` 装配调度 / 空态首帧组合有关。
- **整改方向**：在 `VaultListViewModel`（`app/src/main/java/com/keepasskey/app/ui/screens/vault/`）
  复现窗口期内检查上游流的订阅/重发时序；可考虑导入 / 模板安装完成后由 `VaultImportController` /
  `VaultExportController` 显式触发列表装配刷新，或为 `buildVaultListUiState` 输入流去掉条件性去重。
- **验收标准（待整改）**：① 新建库 → 立即导入 / 安装模板 → 返回列表，新条目与分组**无需重载**即显示；
  ② 设备侧回归用例覆盖该时序。
- **禁止**：以「重新解锁可见」替代修复；在未定位根因前用轮询刷新掩盖。

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

### ISSUE-P3-65（新登记）：「完整性校验」区 TAN 序列号 / 数据库 UUID 开关无持久化、无消费方

- **优先级**：P3（设置项诚实行：开关可拨动但只改内存回显，冷启动静默还原；无任何行为消费方）
- **核实时间点与核实方式（2026-09-11，设备实操 + 全仓只读核查）**：
  1. 设备实操：设置 → 密码库与加密 → 完整性校验区两个开关注入点击可切换（dump 中 `checked` 变化）；
  2. `adb shell run-as com.keepasskey cat shared_prefs/keepasskey_extended_settings.xml` 与
     `files/datastore/keepasskey_settings.preferences_pb` 均无对应键 → **未持久化**；
  3. 全仓核查：`setTanExpiresOnUse` / `setCheckForDuplicateUuids`（`SettingsPreferencesController.kt`）
     仅 `databaseConfigStateFlow.update`（内存回显）；`tanExpiresOnUse` / `checkForDuplicateUuids` 的
     消费方仅有设置页自身回显——**无任何行为层消费**（grep 全仓，2026-09-11）。
- **影响**：用户拨动开关得不到任何真实效果且重启后丢失，违背「设置项必须有真实语义」的一致性预期。
- **验收标准（待整改）**：① 为两个开关补真实语义（TAN 序列号显示 / 重复 UUID 扫描提醒）或如实在
  UI 标注「即将支持」并禁用交互（禁止可拨动但不生效的假开关）；② 若补语义，状态须持久化
  （ExtendedSettingsStore 或库内 Meta）且有设备侧覆盖。
- **禁止**：仅把开关值持久化而依旧无行为消费（假闭环）。

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
