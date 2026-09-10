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

## P3 低危问题、特性接线与体验优化（11 项）

> **背景**：P3 残余批次原 **12 项**（ISSUE-P3-17 ~ P3-28）已于 **2026-09-10** 整体整改。
> 其中 **10 项完整闭环并归档**（P3-17 / 18 / 19 / **20** / 21 / 22 / 25 / 26 / 27 / 28，含逐项代码证据与 15 条过程缺陷留痕），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§4**；**2 项部分达标**（P3-23 / P3-24 —— 均为**本环境物理不可达**的
> 验证类条目：arm64 设备与 GitHub runner）就地更新后保留于本节；
> 另有 **2 项新登记**（**ISSUE-P3-29** 全仓超阈值债务、**ISSUE-P3-30** 子库条目投影未并入根库列表）。
> **2026-09-10 追加一**：**ISSUE-P3-30 已闭环并归档**（子库条目只读投影接入库列表，见 §5）。
> **2026-09-10 追加二**：**ISSUE-P3-29 批次 A 已闭环并归档**（见 §6）——
> 条目正文点名的**优先级 8 项全部降至 400 行阈值内**（`SettingsViewModel` / `VaultListViewModel` /
> `DatabaseSettingsDialogs` / `KdbxFile` / `KeePasskeyApp` / `VaultEntryRows` / `VaultRepository` / `VaultListScreen`），
> 另**增量完成** `KdbxHeader` 与 `SyncCredentialsStore` 两项，并登记 `DicewareWordList` 为**经论证的纯常量例外**；
> 仓库内残余的 **23 个**真逻辑超阈值文件**整体转入 ISSUE-P3-31** 重新登记（附 2026-09-10 实测快照与核实方式）。
> **2026-09-10 追加三（CI 首跑实测 + 供应链残余处置）**：新增 **ISSUE-P3-32**（供应链达阈告警残余）；
> 同一批次内**已闭环**的工作见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§7**：
> `Fast gate` 147 个 lint error 清零、CodeQL 10 条告警处置、`dependency-scan` 的 CVSS 阻断语义
> **静默失效**之实证与硬断言补强、**Kotlin 2.4.20 真修复 `CVE-2026-53914` + 4 族豁免登记**
> （本地真实扫描实测 **188 → 7 条实例、达阈 138 → 0 条**）、以及**合并 PR #5**
> （原 ISSUE-P3-33，已闭环归档）；实测校准追加节见 [docs/ci-静态校准记录.md](ci-静态校准记录.md) **§11**。
> **2026-09-10 追加四（原生内核与工程化扩展批次）**：经全仓性能/架构评审（逐模块读源）新登记的
> **ISSUE-P3-34 ~ P3-38** 五项（AES-KDF 原生内核 / Twofish 原生内核 / 密码强度评估原生引擎 /
> `HmacBlockStream` 摘要收敛 / `.kdbx` 互操作语料生成脚本自动化）**已于同批次全部闭环并归档**，
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§8**——Rust 单测 9 → **43**、全仓用例 1200 → **1257**、
> `crypto` lint 告警 4 → **0**；该节如实留痕 **9 条过程缺陷/事实修正**（含 1 处生产缺陷、
> 1 处安全校验冒充漏洞、1 处文档与实测相反的事实修正）与 **3 项未验证项**。
> **2026-09-10 追加五（自动填充能力对标评估）**：经对照 `docs/references/Monica-架构分析.md`
> 与 Monica 自动填充实现（`autofill_ng` 双通道策略层 + Credential Provider），并**逐文件读取本仓
> 自动填充现状**后，新登记 **ISSUE-P3-39 ~ P3-45** 七项**能力补齐 / 可行性评估**条目
> （对标差距，**非既有功能故障**；每条附核实时间点与核实方式，详见下方自包含正文）。
> 本节余 **11 项**（P3-23 / P3-24 / P3-31 / P3-32 / P3-39 ~ P3-45）。
> 归档门禁证据：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**，
> **1257 例 / 1244 通过 / 0 失败 / 13 跳过**（基线 921 → **+336 例，零退化**）；`assembleDebug` 与
> `lint`（5 模块 **0 error**）通过；`:database:assembleDebugAndroidTest` 通过（ISSUE-P3-23 验收标准①）。

### 前提复核记录（2026-09-10，依「条目维护规则」第 2 条）

> 已对本节全部条目完成一次前提复核：**核实时间点 2026-09-10**。**逐条的核实方式记录在各条目自身的
> 「核实时间点与核实方式」段落内**（ISSUE-P3-28 确立的格式），此处不再重复列表。
> **复核结论**：P3-23 的「语料未入库」成立，但其「`database` 无 `androidTest` 源集」已不成立
> （该源集已建立并接线），已在条目内就地标注；P3-24 前提完整成立。
> **P3-30 已于 2026-09-10 归档**（前提「生产消费方为零」在开工时成立，整改后消费方落地，见 §5）。
> **P3-29 已于 2026-09-10 批次 A 闭环归档**（见 §6）；其残余 23 项已按「新增条目须附核实时间点与核实方式」
> 转入本节 **ISSUE-P3-31**（2026-09-10 经 `wc -l` 全仓复核）。

> **ISSUE-P3-20 已闭环**（子库挂载 **UI 接线**：`childDatabasesCount` 去硬编码并接真实 `mountedCount`；
> `ChildDatabaseDialog` 成为真实入口并调用核心层 `mount`/`open`/`unmount`；两条失真文案随能力上线删除；
> 新增 26 例单测），归档见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §4.2。
> 其接线过程中**由执行者如实发现的「过度声明」残余面**即 **ISSUE-P3-30**，已于 §5 闭环归档
> （投影接入库列表，且只读 / 不并入根库 / 不参与搜索与自动填充）。
>
> **ISSUE-P3-25 已闭环**（3 个点名文件全部降至阈值内：`SyncCoordinator` 965→254、`KdbxXmlGroupReader`
> 407→218、`UnlockViewModel` 979→396）；其「全仓扫描」暴露的整体债务经 **ISSUE-P3-29** 承接，
> 该条**批次 A 已完成优先级 8 项并归档**（见 §6），残余 23 项现由 **ISSUE-P3-31** 承接，
> **仍不代表巨型类问题已全部解决**。


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
- **验收标准**：① `.\gradlew.bat :database:assembleDebugAndroidTest` 编译通过（**2026-09-10 已实测通过**，
  见批次 A 归档 §6.4 门禁证据）；② 真实语料（含同名 `.json`）
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
- **2026-09-10 实测更新（原「仍未达成」条已部分取代）**：CI **已在 GitHub 托管 runner 上真实运行**，
  核实方式 `gh run view 34463116293` / `gh run list --workflow dependency-scan.yml`：
  1. `Rust supply chain` ✅ **success**（`cargo test` + `cargo deny check` 全绿）→「CI 网络下 advisory-db 拉取」**已验证通过**；
  2. `Native gate` ✅ **success**（4 ABI 交叉编译、Debug/Release 打包、v1/v2/v3/v4 签名断言、4 ABI 入包断言全部通过）；
  3. `Fast gate` ❌ **failure** —— 失败点为 **Android Lint 147 个 error**，**已整改并归档**
     （见 [RESOLVED_LOG.md](RESOLVED_LOG.md) §7.2）；`unit tests` 步骤自身通过；
  4. `dependency-scan` 的「真实阻断行为」**已实测且结论为否定**：运行 `34335443660` conclusion=**success**，
     而报告含 138 条 CVSS ≥ 7.0 → `failBuildOnCVSS` 在 aggregate 任务上**不生效**；
     **已补硬断言**（`.github/check_dependency_cvss.py` + workflow 接线），见 §7.4。
- **仍未达成（不得据此认为 CI 已跑通）**：
  1. ~~`Fast gate` 在 lint 修复后的下一次真实 CI 运行~~ → **已于 2026-09-10 完成并转绿**：
     运行 `34470024328`（提交 `c25ac51`）`build` 工作流**三 job 全 success、exit 0**，
     其中 `Fast gate` 的 `Android Lint` ✓ 与 `单元测试（全模块）` ✓、`Native gate` 的
     `Assemble Release` 与签名/入包断言 ✓、`Rust supply chain` ✓（**本仓 CI 史上 fast-gate 首次转绿**）；
     CodeQL 同期复跑 success，**开放告警 10 → 0**；
  2. `dependency-scan` 在硬断言接入后的**首次运行**——按预期**将失败**，直至 ISSUE-P3-32 的达阈条目被处置；
  3. `github/codeql-action/upload-sarif` **v4** 的真实执行（仅出现在手动触发的 `dependency-scan`）；
  4. runner 镜像实际预装 API 级别（R2）、`ubuntu-latest` 指向的镜像版本（R1）；
  5. Gradle 对 daemon JVM criteria 不满足时的失败/自动供给行为（§1#3 残留不确定性）；
  6. 3 条 POSIX 断言的**最终结果**：`Fast gate` 的单元测试步骤确已通过，但该 job 整体因 lint 失败，
     `N/A` 待转绿后的完整运行确认。
- **已登记未改的风险点（含补丁草案，见留痕 §7）**：R1 `ubuntu-latest` 浮动标签（建议固定 `ubuntu-24.04`）；
  R2 fast-gate 不装 SDK；R3 runner 文件系统不支持目录 fd fsync；R4 wrapper 指向腾讯镜像且无
  `distributionSha256Sum`（**未改**：改动会影响国内网络下的本地构建，仅登记）；R5 `~/.cargo` 未缓存；
  R6 GHAS 前提与 `if-no-files-found: error` 的二次失败；R7 `dependency-check.init.gradle.kts:49` 注释与代码不符
  （**本批次已修正**）；R8 一次性 CI 密钥口令明文（自洽）；R9 `ls *.apk | head -n 1` 未来歧义。
- **验收标准**：三 job 在 CI 上真实跑通并按实际结果校准；`dependency-scan.yml` 首次以 CVSS≥7 运行后
  按实际命中处置（修依赖或登记 suppression，**不得回调阈值**）；`cargo deny check advisories` 在 CI 上真实通过。
  **不得据此认为 CI 已跑通。**

---
### ISSUE-P3-31 (P3-29 批次 A 后续): 残余 23 个真逻辑超阈值文件的**分批拆分债务**

- **优先级**：P3（代码整洁度）
- **核实时间点与核实方式（2026-09-10）**：ISSUE-P3-29 批次 A 完成后，于本仓根执行
  `for d in app database sync core crypto; do find $d/src/main/java -name '*.kt'; done | xargs wc -l`，
  以 `awk '$1>400 && $2!="total"'` 筛出 `> 400` 行者并人工剔除纯常量例外。
  **实测残余 23 项**（下列为逐文件实测行数，与批次 A 完成后快照一致）。
- **为何单独登记**：ISSUE-P3-29 正文的**优先级 8 项已于批次 A 全部降至阈值内并归档**（见
  [RESOLVED_LOG.md](RESOLVED_LOG.md) **§6**），但全仓扫描显示超阈值仍是**普遍性既有债务**；
  按「严禁只记聊天或脑中」纪律，残余面必须有独立条目承接，避免后人误以为「巨型类问题已解决」。

- **清单（2026-09-10 批次 A 完成后实测 23 项）**：

  `1090 RealVaultRepository.kt` · `968 DatabasePickerScreen.kt` · `762 ThemeSettingsScreen.kt` ·
  `712 EntryEditScreen.kt` · `708 EntryDetailViewModel.kt` · `697 DatabaseSession.kt` ·
  `674 SecuritySettingsScreen.kt` · `629 S3SyncProvider.kt` · `596 PasskeyCryptoEngine.kt` ·
  `588 AutofillSettingsScreen.kt` · `578 KdbxMerger.kt` · `568 SettingsScreen.kt` · `562 UnlockScreen.kt` ·
  `550 GeneratorScreen.kt` · `509 WebDavSyncProvider.kt` · `494 EntryEditComponents.kt` ·
  `488 KeePasskeyAutofillService.kt` · `481 CloudSyncComponents.kt` · `477 EntryDetailComponents.kt` ·
  `470 EntryEditViewModel.kt` · `462 KeystoreManager.kt` · `453 HealthCheckScreen.kt` · `443 SyncEngine.kt`

  > 路径简写：`app/.../` = `app/src/main/java/com/keepasskey/app/`；`database/.../`、`sync/.../`、`crypto/.../` 同理。
  > **已登记为例外（不再列入债务）**：`app/.../ui/screens/generator/DicewareWordList.kt`（401 行）——
  > 其内容为 EFF/KeePassDX 风格 Diceware **词表**（约 300 行为不可压缩的字符串常量）与少量纯函数，
  > 按「是否含真实逻辑」分级属**经论证的纯常量例外**，不拆分（保留单文件可保证词表作为一个不可分割的整体被审查）。

- **验收标准（未来分批）**：
  1. 按模块分批拆分，**优先处理体量最大且耦合最高的 `RealVaultRepository` / `DatabasePickerScreen` /
     `ThemeSettingsScreen`**（拆解收益最大）；
  2. 每批拆分为**纯结构性**改动：`.\gradlew.bat test` 全绿且用例数不减（当前 **1200 例**）；
  3. 拆分后**逐条对照敏感数据清零点与公开 API 可见性**（沿用批次 A 的验证范式：
     公开 API 零丢失零新增 + 清零点逐一对照）；
  4. 每批完成后按「极简闭环工作流」归档并更新本清单快照。

---

### ISSUE-P3-32 (新登记): 供应链达阈告警残余处置

- **优先级**：P3（供应链安全；阻断面仅在**手动触发**的 `dependency-scan`）
- **核实时间点与核实方式（2026-09-10）**：以
  `gh run download 34335443660 --name dependency-check-report` 取得 engine 13.0.0 的报告 JSON 后，
  以脚本统计 `dependencies[].vulnerabilities[]`：**188 条实例 / 132 个唯一（构件 × CVE）组合**，
  其中 **138 条实例、98 个唯一组合 CVSS ≥ 7.0**（47 条 `CRITICAL` 严重度；51 条分数 ≥ 9.0）；
  各族的依赖来源经 `.\gradlew.bat :app:dependencyInsight --configuration <cfg> --dependency <pkg>` 逐族确认。
- **背景**：本批次已**实证** `failBuildOnCVSS = 7.0f` 在 `dependencyCheckAggregate` 上不生效
  （见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§7.4**），并已补硬断言
  `.github/check_dependency_cvss.py`，且**已接线为 `dependency-scan.yml` 的独立门禁步骤**（提交 `37e609d`）。
- **2026-09-10 处置结果（本地真实扫描实测，本条主要面已消解）**：
  1. **真修复（非豁免）**：Kotlin 2.4.10 → **2.4.20**（即 `CVE-2026-53914` 的修复版本）——
     实测 `test --rerun-tasks` **1200/1187/0/13 零退化**、`lint` **5 模块 0 error**、
     运行时面 `kotlin-stdlib` 解析为 **2.4.20**；
  2. **豁免登记 4 族**（每族附血缘 + 运行面证据）：`org.jline/*@3.24.1`、`protobuf-java@2.6.1`、
     `analytics-library:*@32.4.0`、`org.jetbrains.kotlin/*`（**仅绑定 `CVE-2026-53914` 单一 CVE**）；
  3. **实测结果**：本地 `dependencyCheckAggregate -I .github/dependency-check.init.gradle.kts`
     → 漏洞实例由 **188 条降为 7 条**，其中 **CVSS ≥ 7.0 由 138 条降为 0 条**，
     硬断言 **exit 0 通过**，`CVE-2026-53914` **已消除**；
  4. **残余 7 条**全部为 **CVSS 5.3 MEDIUM**（`commons-lang3@3.16.0`、`httpclient@4.5.6`、
     `kotlin-reflect@1.6.10`、`kotlin-stdlib-jdk7/jdk8@1.8.x` 的 `CVE-2020-29582`），
     皆属构建工具链且**不达阈值**，**如实保留可见**，不做无依据的批量豁免。
- **仍未验证（不得据此认为 CI 侧已跑通）**：该断言在 **CI runner** 上的首次真实运行——
  `workflow_dispatch` 需 PAT 具备 Actions 写权限，本环境被拒
  （HTTP 403 `Resource not accessible by personal access token`），故本条以**本地同参数扫描**为等价验证；
  CI 侧首次运行待维护者手动触发（触发命令：`gh workflow run dependency-scan.yml`）。
  **断言生效后 `dependency-scan` 将按预期失败**，直到下列达阈条目被「修依赖」或「登记 suppression」。
  已登记豁免 **1 族**（`androidx.sqlite`：构件内零 `.so`，不含原生 SQLite C 代码），
  以下为**未豁免残余**。
- **未豁免残余（2026-09-10 核实）**：
  1. **`CVE-2026-53914`（CVSS 9.8）—— 30 个 `org.jetbrains.kotlin/*` 构件**：
     NVD 描述为「JetBrains Kotlin < 2.4.20 经**构建缓存元数据**的不安全反序列化可致代码执行」；
     报告中**实际受影响构件是 `compose-group-mapping@2.4.10`**，其余 29 个
     （`kotlin-stdlib@1.6.10 ~ 2.4.10`、`kotlin-reflect`、`kotlin-daemon-*`、`kotlin-compiler-*` 等）
     系 **CPE 按产品名过度匹配**。正确处置是**升级 Kotlin 至 ≥ 2.4.20**（本仓当前 2.4.10），
     但该升级涉及 AGP 9.4.0 / KSP 2.3.11 / Compose 编译器插件联调，**不可在本环境安全验证**。
  2. **构建工具链族（jline × 11 构件 / `protobuf-java@2.6.1` / `analytics-library:protos@32.4.0`）**：
     报告路径显示其全部**被 shade 在 `kotlin-compiler-32.4.0.jar` 内**（AGP 内置 Kotlin 编译器），
     属**构建期**而非 APK 运行面。是否接受该构建期暴露属**风险接受决策**，不由本批次单方面压制。
  3. **Code Scanning 历史遗留**：依赖类 open 告警 **188 条**（创建于 2026-09-09），
     待上述处置落地后按实际结果收敛；**不得**以批量 dismiss 清空。
- **验收标准**：
  1. `dependency-scan` 的硬断言步骤在**真实运行**中给出确定的 pass/fail（而非静默通过）；
  2. 每个达阈族满足下列二者之一并留痕：**升级到修复版本**，或**写入 suppression 并附核实依据**
     （按 `.github/owasp-dependency-suppressions.xml` 维护纪律 + 本文件登记）；
  3. 处置后 Code Scanning 依赖类 open 告警数与该族结论**一致**（禁止以 dismiss 替代修复依据）。
- **禁止**：回调 `failBuildOnCVSS` 阈值以换取变绿；删除或注释掉硬断言步骤；
  在没有核实依据的情况下批量写入 suppression。

---

### ISSUE-P3-39 (自动填充对标 ①): 字段识别与候选评分引擎升级

- **优先级**：P3（特性覆盖与体验补齐；**不削弱**任何既有安全闸门）
- **核实时间点与核实方式（2026-09-10）**：
  1. 读 `app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt`（**139 行**）——
     仅识别 `username` / `password` 两类框，评分仅「hint / inputType / htmlName」三档，
     兜底为 `htmlName` 大小写不敏感**子串包含**（`isUsernameHtmlName` / `isPasswordHtmlName`）；
  2. 读 `app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt`（**488 行**）——
     候选匹配为**二元布尔** `matchDomain || matchPackage`，固定 `MAX_DATASET_COUNT = 8` 截断，
     无打分、无子域/根域分级、无「上次填充优先」；
  3. 对照 `docs/references/Monica-架构分析.md` §2.3 / §5.1 及 Monica `autofill_ng` 策略层
     （多策略解析 + `Accuracy` 多档置信度 + `BitwardenLikeAutofillMatcherNg` 打分排序）。
- **背景**：本仓自动填充的**匹配内核极为单薄**。`AutofillFieldScanner` 仅 139 行，无法解析
  label 邻近文本、多语言登录词（`nickname` / `логин` / 中文「用户名」）、手机号登录、无 label 的
  WebView 表单与 `importantForAutofill` 变体；候选排序缺失导致多候选时正确条目可能被挤出前 8 条。
  此为**对标参考项目的能力差距**（非既有功能故障），主流「用户名+密码」表单当前可用。
- **整改要点**：
  1. 扩充字段扫描：新增 label 邻近文本、`idEntry` / `hint`、InputType 变体（电话/邮箱）与
     **多语言登录词表**识别，引入**置信度评分**替代布尔判定；
  2. 新增**候选打分器**（纯 Kotlin、JVM 可测）：精确域名 > 子域 > 根域 > 精确包名 > 包名 token，
     叠加收藏状态与更新时间，输出排序后按可配置上限截断；
  3. 新增**「上次填充优先」**（按交互上下文记忆，仅排序不影响放行判定）；
  4. 处理搜索框排除、隐藏字段准入与 `importantForAutofill=no`（与 ISSUE-P3-43 联动）；
  5. 涉及文件：`AutofillFieldScanner.kt`、`KeePasskeyAutofillService.kt`、新增 `AutofillCandidateRanker`
     （暂定名）与 `AutofillMatchPolicy`（暂定名）。
- **与 ISSUE-P3-31 的交互**：`KeePasskeyAutofillService.kt`（488 行）在 P3-31 超阈值债务清单内——
  本条新增逻辑**应优先落在新文件**，避免加剧其体积；若本条顺带拆分了该文件，须同步更新 P3-31 清单快照。
- **禁止**：借打分/召回之名放宽 `AutofillWebDomainPolicy` 与 `AutofillOriginResolver` 的
  **DAL 归属校验**，或绕过 `AutofillAccessPolicy` 的完整性/黑名单闸门；
  使「`webDomain` 未通过归属校验」的候选参与下发。
- **验收标准**：新增纯 JVM 单测覆盖中英文/西里尔 label、手机号登录、无 label WebView、
  多候选排序与上限截断；现有 `autofill` 单测零退化；`.\gradlew.bat test` 全绿且用例数不减。

---

### ISSUE-P3-40 (自动填充对标 ②): 手动选择器（全库搜索兜底入口）

- **优先级**：P3（体验补齐）
- **核实时间点与核实方式（2026-09-10）**：经 `search_file "*Autofill*"` / `"*Picker*"` 与
  全仓 `grep AutofillPicker|EntrySelection|PickerActivity` 核实——本仓**无任何手动选择器**
  （仅 `VaultList*` 命中，与自动填充无关）；`KeePasskeyAutofillService` 仅下发匹配到的 ≤ 8 条候选，
  **无兜底入口**。
- **背景**：Monica 提供 `AutofillPickerActivityV2` 与 `manual_selection_enabled`，自动匹配失败时
  用户可手动打开填充面板搜索全库条目。本仓在弱域名 / 纯 App / 内嵌 WebView 场景下，一旦严格匹配
  不命中即**无候选**，用户无任何补救路径。
- **整改要点**：
  1. 新增受保护的填充选择 Activity（Compose）：支持按标题/用户名搜索、展示最近填充、选中后
     **经既有确认链路**回传；
  2. 接入 `AutofillService`（`FillResponse` 增加手动入口项）与 Credential Manager 两通道；
  3. 涉及文件：新增选择器 Activity + ViewModel、`KeePasskeyAutofillService.kt`、`CredentialResponseAssembler.kt`。
- **安全要求**：
  1. 列表热路径**严禁解密密码**（只展示标题/用户名等元数据，沿用既有投影约定）；
  2. 选中后**必须**仍走 `AutofillConfirmActivity` / `CredentialFillVerifier` 二次确认，
     选择器本身不构成放行；
  3. 选择器窗口须 `FLAG_SECURE` + `setHideOverlayWindows(true)` + 遮挡触摸过滤。
- **验收标准**：单测覆盖搜索过滤与「未确认不解密」；选择器窗口防护三项齐备。

---

### ISSUE-P3-41 (自动填充对标 ③): 服务健康自检与诊断

- **优先级**：P3（可维护性 / 排障体验）
- **核实时间点与核实方式（2026-09-10）**：读
  `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsScreen.kt`
  （**588 行**，且在 ISSUE-P3-31 债务清单内）——仅「provider 状态展示 + 开关 + 黑名单管理」；
  全仓 `grep AutofillServiceChecker|AutofillDiagnostic` **零命中**。
- **背景**：Monica 有 `AutofillServiceChecker` 六维检查（Manifest 声明 / 系统启用 / 应用启用 /
  权限 / 兼容性问题 / 修复建议）与诊断输出。本仓用户遇到「为什么不出候选」时无任何自检入口，
  只能靠猜。
- **整改要点**：
  1. 新增纯 Kotlin `AutofillHealthChecker`（JVM 可测）：检查本应用 `AutofillService` 是否
     声明且带 `BIND_AUTOFILL_SERVICE`、`AutofillManager` 当前服务是否为本应用、应用内开关状态、
     Credential Manager 可用性；
  2. 设置页展示结论项与**修复指引**（可直接跳系统自动填充设置页）；
  3. 涉及文件：新增 `AutofillHealthChecker`（暂定名），`AutofillSettingsScreen.kt`（若加剧体积需先拆分，见 P3-31）。
- **禁止**：诊断输出**不得**包含用户安装应用清单、主密码、条目明文等敏感信息。
- **验收标准**：单测覆盖各失败分支（未声明 / 未启用 / 未获权限 / 正常）；输出无敏感字段。

---

### ISSUE-P3-42 (自动填充对标 ④): 填充侧会话授权宽限与快速填充开关

- **优先级**：P3（体验；**安全默认必须保守**）
- **核实时间点与核实方式（2026-09-10）**：读
  `app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt`——
  **每次**点选数据集均执行 `BiometricAuthManager` 生物识别或受保护窗口内手动确认，
  **无 TTL / 无免重复认证**；设置页亦无对应开关。
- **背景**：Monica 以 `AutofillSessionGrantStore`（30s TTL，绑定上下文）+ `biometric_quick_fill_enabled`
  在**库已解锁**后免重复弹窗。本仓连续填充时**每次都弹确认**，安全更强但体验明显偏重。
- **整改要点**：
  1. 新增 `AutofillSessionGrantStore`（`SystemClock.elapsedRealtime` TTL，绑定「包名 + 域 + 字段签名」）；
  2. **仅**在「库已解锁且存在匹配的有效授权」时跳过重复二次确认；
  3. 设置页提供开关与 TTL 文案说明；**默认上限保守**（短 TTL，或默认关闭由用户开启）。
- **禁止**：改变「库锁定时必须先解锁」语义；授权**仅**作用于已解锁后的**重复**确认，
  绝不可使未解锁状态跳过解锁；不得让授权跨包名/跨域复用。
- **验收标准**：单测覆盖「TTL 过期即失效」「上下文（包名/域/字段签名）不匹配即失效」
  「库锁定态授权一律不适用」；默认配置不得放宽到「无任何确认」。

---

### ISSUE-P3-43 (自动填充对标 ⑤): 字段签名级屏蔽 + 保存侧独立黑名单 + 尊重 importantForAutofill

- **优先级**：P3（粒度补齐）
- **核实时间点与核实方式（2026-09-10）**：读
  `app/src/main/java/com/keepasskey/app/data/repository/AutofillBlocklistStore.kt`——**仅包级**集合
  （`blocked_packages`），无字段级、无保存侧独立控制；读 `KeePasskeyAutofillService.kt`——
  无「尊重 `importantForAutofill=no`」策略。
- **背景**：Monica 具备三级控制——`blacklist_packages`（包级，含默认微信/支付宝/云闪付）、
  `blocked_field_signatures`（**字段签名级**，记住「该包该表单字段不填」）、
  `save_blocked_targets`（**保存侧**独立黑名单），另有 `v2_respect_autofill_off` 开关。
  本仓仅有「按应用屏蔽」一档。
- **整改要点**：
  1. 将黑名单扩展为**三级**：包级 / 字段签名级 / 保存侧目标级，**复用**现有 fail-closed 包名校验；
  2. 新增「尊重 `importantForAutofill=no`」开关（关闭时不填充显式标记为禁用的字段）；
  3. 涉及文件：`AutofillBlocklistStore.kt`、`KeePasskeyAutofillService.kt`、`AutofillSettingsScreen.kt`。
- **安全要求**：字段签名须为**不可逆归一**值（包名 + 域 + 字段角色），**严禁**持久化明文表单内容；
  保存侧黑名单命中必须与填充侧同等 fail-closed。
- **验收标准**：单测覆盖三级判定、非法输入 fail-closed、保存侧命中不落库。

---

### ISSUE-P3-44 (自动填充对标 ⑥): 保存体验补齐（评估 + 按需实现）

- **优先级**：P3（体验评估；受单库 KDBX 约束）
- **核实时间点与核实方式（2026-09-10）**：读 `KeePasskeyAutofillService.kt` 的 `onSaveRequest`
  （约 327-420 行）——已具备内容级**幂等查重**与失败回滚；**无**保存通知开关、无智能标题、
  无重复密码更新提示、无保存目标选择。
- **背景**：Monica 有 `show_save_notification` / `smart_title_generation` /
  `auto_update_duplicate_passwords` / `AutofillSaveActivity` + `resolveAutofillSaveInitialTarget`
  （记住上次保存分类）。本仓保存路径能力较基础（静默落库）。
- **整改要点（评估为主）**：
  1. 评估「保存通知 / 提示」的必要性（当前静默落库，用户可能不知已保存）；
  2. 评估「重复密码时提示更新」与「智能标题生成」的收益与复杂度；
  3. 保存目标：本仓为**单一 KDBX 库**，「选择保存到哪个库」**不适用**；仅评估「保存到分组」的可行性。
- **禁止**：改变现有内容级幂等查重语义（防双通道重复落库）；为对齐参考项目引入多库/外部来源架构
  （属 Monica 私有 MDBX/Bitwarden 设计，见 `docs/references/Monica-架构分析.md` §5.4，**不可直搬**）。
- **验收标准**：产出评估结论（每子项：可行/不可行 + 依据），可行项独立实现并补单测；不可行项如实留痕。

---

### ISSUE-P3-45 (自动填充对标 ⑦): 结构化数据类型（银行卡/证件/地址）自动填充可行性评估

- **优先级**：P3（**纯评估**；需先做数据模型落位决策，不含实现承诺）
- **核实时间点与核实方式（2026-09-10）**：
  1. 读 `AutofillFieldScanner.kt`——`ScanResult` **仅** `usernameId` / `passwordId` / `webDomain` / `packageName`，
     无银行卡/证件/地址字段；
  2. 读 `AutofillStructuredData*` 相关全仓检索——本仓**无** `SecureItem` / `BankCardData` /
     `DocumentData` / `BillingAddressData` 等一等模型，`KdbxEntry` 亦无对应结构；
  3. 对照 `docs/references/Monica-架构分析.md` §5.4——Monica 的结构化数据建立在**私有 MDBX 架构**上，
     属**不可直搬**项。
- **背景**：Monica 支持信用卡（卡号/有效期/安全码/持卡人）、证件号、账单地址填充，并含
  `AutofillPickerRequestProfile` 请求类型判定、`evaluateStructuredConfidence` 结构化置信度闸门
  与卡号/证件号脱敏展示。本仓在**结构化数据类型上完全空白**。
- **整改要点（仅评估）**：
  1. 评估数据落位：**KDBX 自定义字段**（须遵循 KPEX / 业界 schema 以保证互操作）vs 新增内部类型；
  2. 评估 `FieldHint` 扩展至 `CREDIT_CARD_*` / `IDENTITY_NUMBER` / `POSTAL_*` 的可行性，
     及脱敏展示、请求类型判定、置信度闸门的落位；
  3. **产出决策记录后再决定是否实现**；若判定不可行，如实归档并说明理由。
- **禁止**：为对齐参考项目而破坏标准 `.kdbx` 互操作（自定义字段须与 KeePassXC / KeePassDX 互通）；
  在无决策依据时直接引入私有容器格式。
- **验收标准**：产出评估结论（可行/不可行 + 依据 + 若可行的落位方案），据此决定后续是否新开实现条目。
