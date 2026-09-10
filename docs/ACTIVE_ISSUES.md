# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **闭环纪律**：任务完成后，将该条目从本文件**移入** [**docs/RESOLVED_LOG.md**](RESOLVED_LOG.md)，并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」
   一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如
   「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：本批次发现 ISSUE-P3-08 与 ISSUE-P3-16 的正文前提在开工时**已不成立**——两者都声称
   > `docs/plans/`、`STATUS.md`、`plans/rust-enclave-poc.md` 等文件「需要删除」，但这些文件早在
   > `7dba64d`（重构文档体系）与 `d578df7` / `863d81c` 中就已删除；`AGENTS.md` 也已不含相关引用。
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

> 当前 P1 级别无待办。历史 P1 项（含 ISSUE-P1-10 / ZT-10）已全部闭环，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.5。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> ISSUE-P2-05 ~ ISSUE-P2-13 九项已全部整改并归档，见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §2.16 ~ §2.20。
> P2-12 整改中如实登记的残余面 ISSUE-P2-15 / ISSUE-P2-16（受保护值经 String 退化、
> 密码生成引擎出边界仍返回 String）已整改并归档，见 §2.21。
> 当前 P2 级别无待办。

## P3 低危问题、特性接线与体验优化（4 项）

> **背景**：P3 残余批次原 **12 项**（ISSUE-P3-17 ~ P3-28）已于 **2026-09-10** 整体整改。
> 其中 **10 项完整闭环并归档**（P3-17 / 18 / 19 / **20** / 21 / 22 / 25 / 26 / 27 / 28，含逐项代码证据与 15 条过程缺陷留痕），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§4**；**2 项部分达标**（P3-23 / P3-24 —— 均为**本环境物理不可达**的
> 验证类条目：arm64 设备与 GitHub runner）就地更新后保留于本节；
> 另有 **2 项新登记**（**ISSUE-P3-29** 全仓超阈值债务、**ISSUE-P3-30** 子库条目投影未并入根库列表）。
> 归档门禁证据：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**，
> **1189 例 / 1176 通过 / 0 失败 / 13 跳过**（基线 921 → **+268 例，零退化**）；`assembleDebug` 通过。

### 前提复核记录（2026-09-10，依「条目维护规则」第 2 条）

> 已对本节全部条目完成一次前提复核：**核实时间点 2026-09-10**。**逐条的核实方式记录在各条目自身的
> 「核实时间点与核实方式」段落内**（ISSUE-P3-28 确立的格式），此处不再重复列表。
> **复核结论**：4 条前提均成立 —— 其中 P3-23 的「语料未入库」成立，但其「`database` 无 `androidTest`
> 源集」已不成立（该源集本批次已建立并接线），已在条目内就地标注；其余 3 条前提完整成立。

> **ISSUE-P3-20 已闭环**（子库挂载 **UI 接线**：`childDatabasesCount` 去硬编码并接真实 `mountedCount`；
> `ChildDatabaseDialog` 成为真实入口并调用核心层 `mount`/`open`/`unmount`；两条失真文案随能力上线删除；
> 新增 26 例单测），归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §4.2。
> 其接线过程中**由执行者如实发现的「过度声明」残余面**已作为 **ISSUE-P3-30** 登记。
>
> **ISSUE-P3-25 已闭环**（3 个点名文件全部降至阈值内：`SyncCoordinator` 965→254、`KdbxXmlGroupReader`
> 407→218、`UnlockViewModel` 979→396），但其「全仓扫描」暴露的整体债务已作为 **ISSUE-P3-29** 登记，
> **不代表巨型类问题已解决**。


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
- **验收标准**：① `.\gradlew.bat :database:assembleDebugAndroidTest` 编译通过；② 真实语料（含同名 `.json`）
  入库两处后 `:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` **不再是 skip** 且全绿；
  ③ arm64 真机（`adb shell getprop ro.product.cpu.abi` = `arm64-v8a`）上取到 §4.3 表格数据并与 §4.1/§4.2 **分表**归档；
  ④ 归档文件「待填」表按实际情况回填（**严禁编造数据**）。
- **禁止**：以 x86_64 模拟器或宿主侧数据填充 §4.3；把「用例就绪」表述为「验证通过」；
  用自生成 `.kdbx` 往返冒充互操作证据。

---

### ISSUE-P3-24 (P3-09 残余): CI 门禁首跑校准

- **优先级**：P3（供应链安全；依赖联网 CI 环境）
- **核实时间点与核实方式（2026-09-10）**：完整留痕见新建 **`docs/ci-静态校准记录.md`**
  （每项结论均附核实时间点与核实方式）。本批次**已修 3 处「首次必红」缺陷**：
  1. `build.yml:52` `platforms;android-37` → **`android-37.0`**（`sdkmanager --list --channel=0` 与本机
     `platforms/android-37.0/package.xml` 双证据：远端**不存在** `platforms;android-37`）；
  2. 签名断言**两处必然误红**：`apksigner` 不自动读同目录 `.idsig`（不传参时 v4 恒 `false`）→ 补
     `--v4-signature-file "${apk}.idsig"`；且 minSdk 36≥28 且 v3 同开时 AGP **省略 v2 块**（`AGENTS.md` §1 基线）
     → 原「v2:true」断言**不可能成立**，改为「v2 块必须缺席（出现即 error）」并新增 `v1: false` 断言（**未削弱**）；
  3. JDK 17 → 21，与 `gradle/gradle-daemon-jvm.properties: toolchainVersion=21` 对齐
     （**残留不确定性已如实标注**：Gradle 对 daemon JVM criteria 不满足时「失败还是自动供给」未实测）。
  另经核实：7 个 Action SHA **全部真实存在**且与声明版本一致（**未遇 403/限流**）；
  **Rust 1.97.1 确认真实已发布**（推翻「未发布必红」担忧）；`cargo-ndk 4.1.2` / `cargo-deny 0.20.2` 真实存在；
  `material3` **1.5.0 stable 核实不存在** → 退出条件未满足、维持 `1.5.0-alpha27`（**未改** `libs.versions.toml`）；
  **`cargo deny check` 本机实跑通过**（`advisories/bans/licenses/sources ok`，advisory-db 当日真实拉取，
  `curl 28` **未复现**）；Linux 侧「疑似首次即红」静态判定 **0 例**。
- **仍未达成**：**本环境从未运行 CI**，以下全部未验证——三 job 在 runner 上的真实执行（Action 拉取 /
  SDK 安装 / NDK+Rust+cargo-ndk / 4 ABI 交叉编译 / Debug+Release 打包 / 全部断言）、
  `dependency-scan.yml` 在 CVSS≥7 的真实阻断行为、CI 网络下 advisory-db 拉取、镜像实际预装 API 级别、
  Code Scanning/GHAS 可用性与 `NVD_API_KEY` 是否已配、3 条 POSIX 断言的最终结果。
- **已登记未改的风险点（含补丁草案，见留痕 §7）**：R1 `ubuntu-latest` 浮动标签（建议固定 `ubuntu-24.04`）；
  R2 fast-gate 不装 SDK；R3 runner 文件系统不支持目录 fd fsync；R4 wrapper 指向腾讯镜像且无
  `distributionSha256Sum`（**未改**：改动会影响国内网络下的本地构建，仅登记）；R5 `~/.cargo` 未缓存；
  R6 GHAS 前提与 `if-no-files-found: error` 的二次失败；R7 `dependency-check.init.gradle.kts:49` 注释与代码不符
  （**本批次已修正**）；R8 一次性 CI 密钥口令明文（自洽）；R9 `ls *.apk | head -n 1` 未来歧义。
- **验收标准**：三 job 在 CI 上真实跑通并按实际结果校准；`dependency-scan.yml` 首次以 CVSS≥7 运行后
  按实际命中处置（修依赖或登记 suppression，**不得回调阈值**）；`cargo deny check advisories` 在 CI 上真实通过。
  **不得据此认为 CI 已跑通。**

---
### ISSUE-P3-29 (P3-25 后续 · 全仓扫描发现): 生产 Kotlin 文件超 400 行阈值的**整体债务**

- **优先级**：P3（代码整洁度）
- **核实时间点与核实方式（2026-09-10）**：ISSUE-P3-25 整改完成后，对全仓生产源集执行
  `Get-ChildItem -Recurse -Include *.kt -Path {app,database,sync,core,crypto}/src/main/java`
  并逐文件 `(Get-Content).Count` 统计，筛出 `> 400` 行者。**共 34 个生产文件超标**（其中 P3-20 接线令 **1 个**文件新增越界）。
- **为何单独登记**：ISSUE-P3-25 的正文只点名了 **3 个文件**（`SyncCoordinator` / `UnlockViewModel` /
  `KdbxXmlGroupReader`），该 3 项均已降至阈值内并归档（见 `RESOLVED_LOG.md` §4.2）。
  但全仓扫描显示**超标是普遍性既有债务**，而非 3 个孤例——按「严禁只记聊天或脑中」纪律就地登记，
  避免后人误以为「巨型类问题已解决」。

- **清单（2026-09-10 快照，实测 34 项）**：分为两组——**① 本批次接线致增长的 8 项（优先处理）**，
  **② 纯既有债务 26 项**。

  **① 本批次接线致增长（8 项）**

  | 行数 | 文件 | 变化 |
  |---:|---|---|
  | 1120 | `app/.../ui/screens/settings/SettingsViewModel.kt` | 879 → +23（P3-19 导入接线）+218（P3-20 子库 UI 接线） |
  | 731 | `app/.../ui/screens/vault/VaultListViewModel.kt` | 641 → +90（P3-17 四个偏好派生量） |
  | 617 | `app/.../ui/screens/settings/subscreens/DatabaseSettingsDialogs.kt` | 338 → +279（P3-20 子库对话框真实化）← **本批次唯一新致超标者** |
  | 614 | `database/.../file/KdbxFile.kt` | 602 → +12（P3-27 安全常量 KDoc，**仅注释**） |
  | 590 | `app/.../ui/KeePasskeyApp.kt` | 555 → +35（P3-17 / 19 / 20 接线） |
  | 466 | `app/.../ui/screens/vault/VaultEntryRows.kt` | 既有 + P3-22 分组图标接线 |
  | 435 | `app/.../data/repository/VaultRepository.kt` | 既有 + P3-27 `incrementPasskeySignCount` 等 |
  | 417 | `app/.../ui/screens/vault/VaultListScreen.kt` | 既有 + P3-17 / P3-22 接线 |

  **② 纯既有债务（26 项，本批次未触及）**

  `1090 RealVaultRepository.kt` · `968 DatabasePickerScreen.kt` · `762 ThemeSettingsScreen.kt` ·
  `712 EntryEditScreen.kt` · `708 EntryDetailViewModel.kt` · `697 DatabaseSession.kt` ·
  `674 SecuritySettingsScreen.kt` · `629 S3SyncProvider.kt` · `596 PasskeyCryptoEngine.kt` ·
  `588 AutofillSettingsScreen.kt` · `578 KdbxMerger.kt` · `569 SettingsScreen.kt` · `562 UnlockScreen.kt` ·
  `550 GeneratorScreen.kt` · `510 WebDavSyncProvider.kt` · `494 EntryEditComponents.kt` ·
  `488 KeePasskeyAutofillService.kt` · `481 CloudSyncComponents.kt` · `477 EntryDetailComponents.kt` ·
  `470 EntryEditViewModel.kt` · `462 KeystoreManager.kt` · `453 HealthCheckScreen.kt` · `443 SyncEngine.kt` ·
  `421 SyncCredentialsStore.kt` · `418 KdbxHeader.kt` · `401 DicewareWordList.kt`

  > 路径简写：`app/.../` = `app/src/main/java/com/keepasskey/app/`；`database/.../`、`sync/.../`、`crypto/.../` 同理。
  > `DicewareWordList.kt`（401 行）为**纯查表常量**，建议按验收标准 1 登记为经论证的例外。
  > **注**：`UnlockScreen.kt`（562）与已拆分的 `UnlockViewModel` 同目录，属既有债务，P3-25 未纳入其范围。

- **验收标准（未来分批）**：
  1. 按「是否含真实逻辑」分级——纯数据/常量表（如 `DicewareWordList.kt`）建议**登记为例外**并说明理由；
  2. 其余按模块分批拆分，**优先处理上表 ① 的 8 项**（其中 `SettingsViewModel` / `DatabaseSettingsDialogs` /
     `VaultListViewModel` 最值得先拆——后两者是本批次新致超标或大幅增长）；
  3. 每批拆分为**纯结构性**改动：`.\gradlew.bat test` 全绿且用例数不减（当前 **1189 例**）；
  4. 拆分后**逐条对照敏感数据清零点与公开 API 可见性**（沿用 P3-25 对 `UnlockViewModel` 的验证范式：
     公开 API 零丢失零新增 + 清零点逐一对照）。

### ISSUE-P3-30 (P3-20 后续 · 接线中如实发现的过度声明): 子库条目投影尚未合并进根库列表

- **优先级**：P3（功能完整性 / 拒绝过度声明）
- **分类**：多库展示 / UI 接线
- **核实时间点与核实方式（2026-09-10）**：ISSUE-P3-20 的 UI 接线工作在收口自查时发现——
  经全仓 `grep projectedEntries app/src`，该 Flow 的**生产消费方为零**（仅 `ChildDatabaseSessionManager`
  自身与 `ChildDatabaseSessionManagerTest` 引用）；而对话框原说明文案
  `dbset_child_db_dialog_desc` 原文写作「**挂载的子密码库将以只读分组形式出现在当前库中**」。
  即：**挂载、真实解密、条目计数、状态流转、解锁/卸载全部为真，唯独「出现在当前库中」未接线**。
- **本批次已做的诚实化处置**：该文案**已就地改写**为与实现一致——
  「子库以独立凭据只读挂载：可查看条目数与解锁状态；**其条目暂未合并进当前库列表**」
  （`values/strings.xml` / `values-en/strings.xml`）。同时删除两条随能力上线而失真的文案
  `dbset_child_db_not_supported` 与 `dbset_child_db_reserved_note`（后者仅剩一处代码注释留痕）。
- **仍未达成**：`ChildDatabaseSessionManager.projectedEntries`（`List<ChildDatabaseEntryProjection>`，
  含 `mountId/mountAlias/entryUuid/title/username/url/notes/groupPath/tags/iconId/hasPassword`）
  尚未接入 `VaultListViewModel` / `VaultListScreen` / 搜索 / 自动填充，故**子库条目在当前库中不可见**。
- **施工要点（须先定案再动手）**：
  1. **只读投影而非真实条目**：子库条目**不得**并入根库 `KdbxGroup`/`KdbxEntry` 对象树
     （核心层已结构性保证此点，见 `ChildDatabaseSessionManager` KDoc 与同步隔离测试）；
     根库列表需以**并列的只读数据源**呈现，任何编辑/删除入口都必须对投影条目 fail-closed 拒绝；
  2. **同步与合并零交集**：投影条目**不得**进入 `SyncCoordinator` 的上传候选、`KdbxMerger` 的
     UUID/墓碑语义、历史修订与回收站路径（否则会造成「根库保存时把子库条目写进根库」的数据事故）；
  3. **锁库联动**：根库锁定时投影必须同步消失（核心层已终止子库会话并清零凭据，UI 只需停止展示）；
  4. **搜索/自动填充**：子库条目是否参与全局搜索与自动填充须**显式裁决并写入 KDoc**——
     若参与，需评估「子库未解锁时的检索降级」与凭据隔离边界；首版建议**不参与**并如实标注。
- **验收标准**：① 已挂载且已解锁的子库条目在库列表中以其分组路径可见，且**编辑/删除入口被拒**；
  ② 根库同步、合并、历史、回收站路径**零子库条目**（补结构断言）；
  ③ 根库锁库后投影即时消失；④ 原「过度声明」文案无需再改（已诚实化）；
  ⑤ 补单测覆盖投影装配、只读拒绝与锁库消失；⑥ `.\gradlew.bat test` 全绿且用例数不减。
