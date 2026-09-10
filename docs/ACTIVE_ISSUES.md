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
> 其中 **8 项完整闭环并归档**（P3-17 / 18 / 19 / 21 / 22 / 26 / 27 / 28，含逐项代码证据与 15 条过程缺陷留痕），
> 见 [RESOLVED_LOG.md](RESOLVED_LOG.md) **§4**；**4 项部分达标**，其残余面就地更新后保留于本节。
> 归档门禁证据：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**，
> **1159 例 / 1146 通过 / 0 失败 / 13 跳过**（基线 921 → **+238 例，零退化**）。

### 前提复核记录（2026-09-10，依「条目维护规则」第 2 条）

| 条目 | 正文前提 | 核实方式 | 结论 |
|:--:|---|---|:--:|
| P3-20 | `childDatabasesCount` 硬编码 0；`ChildDatabaseDialog.onSelectFile` 无落地 | 读 `SettingsViewModel.kt:133`、`DatabaseSettingsDialogs.kt:198-239` | ✅ 成立（**两处至今未改**，故标识如实保留） |
| P3-23 | 语料未入库；`database` 无 `androidTest` 源集 | 目录枚举 | ⚠️ **前半仍成立；后半已不成立**（`database/src/androidTest/` 已建立并接线） |
| P3-24 | CI 从未真实运行 | 只读探测 + 联网核实 | ✅ 成立（**三 job 仍未真实执行**） |
| P3-25 | `SyncCoordinator` ~965 / `UnlockViewModel` ~979 / `KdbxXmlGroupReader` 407 行 | `(Get-Content).Count` | ⚠️ **部分不成立**：前两者已拆（965→254、407→218），**`UnlockViewModel` 仍 979** |

---

### ISSUE-P3-20 (P3-03 残余): 子库挂载 —— **仅剩 UI 接线**（核心层已完成）

- **优先级**：P3（大特性收尾）
- **核实时间点与核实方式（2026-09-10）**：经 `Test-Path` / 读 `SettingsViewModel.kt:133` /
  `DatabaseSettingsDialogs.kt:198-239` 核实两处 UI 缺口仍在；经目录枚举核实
  `app/src/main/java/com/keepasskey/app/data/childdb/` 6 个生产文件 + `di/ChildDatabaseModule.kt`
  与 5 个测试类（37 例）**已落盘且经门禁编译通过**。
- **本批次已完成（核心层，勿重做）**：
  1. 数据模型 / 挂载注册表（非敏感元数据，独立偏好文件 `keepasskey_child_databases`）/ 凭据存储
     （独立通道 + 闭包返回即清零，强于 `DatabaseSession.useCredentials`）/ 只读子库会话 /
     流来源 / 会话管理器（`@Singleton`）；
  2. **凭据隔离**：与根库 `passwordCache`/`keyFileCache` 零共享；`clearAll()` 统一清零；
     会话世代号 + 根库锁定世代防「锁定后才解密完」的凭据回写竞态；
  3. **锁库联动**：经 `DatabaseSession.addLockObserver`（与 `SyncCacheEvictor` **同一熔断触发点**），
     覆盖手动锁定 / 熄屏熔断 / 后台超时 / 切换库；
  4. **同步隔离**：子库来源绝不写库列表偏好、不改 `currentFile`，由结构断言守护；
  5. **挂载点抽象有意降级为应用侧注册表**（理由与收敛路径已写入 KDoc：来源是设备本地量，
     写进会同步的根库会产生悬挂挂载点；且挂载时根库可能处于锁定/只读态）。
- **待接线（本条目剩余全部工作）**：
  1. `SettingsViewModel`：注入 `ChildDatabaseSessionManager`，把 `mountedCount: StateFlow<Int>`
     并入既有 `combine(...)`，替换 `SettingsViewModel.kt:133` 的 `childDatabasesCount = 0`
     （语义 = **已挂载数**，非「已解锁数」；后者用 `mountStates.count { it.value is Opened }`）；
  2. `ChildDatabaseDialog.onSelectFile`：接 `mount(alias, sourceUri, passwordChars, keyFileData)`，
     并**在 SAF 选择后立即申请持久化读授权**（否则进程重启后如实报 `SOURCE_UNAVAILABLE`）；
     密钥文件字节复用 `ui/screens/unlock/KeyFileAccess`（`SafKeyFileAccess`）；
  3. 挂载/卸载/重新解锁的状态 UI（`ChildDatabaseMountState` = `Closed` / `Opening` / `Opened` /
     `CredentialRejected` / `SourceUnavailable` / `Failed(reason)`），失败分型经
     `ChildDatabaseFailureReason.of(error)` 自动穿透 cause 链；
  4. 新增 23 条 strings.xml 资源，并**改写或删除两条已失真文案**：
     `dbset_child_db_reserved_note`（`strings.xml:965`）、`dbset_child_db_not_supported`（`:486`，
     同时 `DatabaseSettingsScreen.kt:248` 的 `UiMessage` 需替换为真实挂载反馈）。
     资源清单见 P3-20 交付报告（别名/密码标签、挂载/卸载/解锁按钮、5 种状态、12 种错误分型）。
- **诚实性红线**：在 UI 真正可挂载之前，**必须保留** `dbset_child_db_reserved_note` 标识——
  「未接线却移除标识」与「未实现却显示可用」同属不诚实。
- **验收标准**：`childDatabasesCount` 显示真实挂载数；挂载/卸载/凭据失效在 UI 上可观察；
  移除两条失真文案；补 UI 层测试。

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

### ISSUE-P3-25 (P3-03 / P3-04 残余): 巨型类拆分（剩余部分）

- **优先级**：P3（代码整洁度）
- **核实时间点与核实方式（2026-09-10）**：经 `(Get-Content <file>).Count` 逐文件统计。
- **本批次已完成**：
  - `app/.../sync/SyncCoordinator.kt` **965 → 254 行**，拆出 10 个新类（`SyncCycleRunner` /
    `SyncConflictController` / `SyncProviderResolver` / `SyncContentChangeDetector` / `SyncDatabaseCodec` /
    `SyncSessionState` / `SyncPreferences` / `SyncOutcome` / `SyncLogTag` / `SyncCacheEvictor` 系），均 ≤400；
  - `database/.../xml/KdbxXmlGroupReader.kt` **407 → 218 行**，拆出 `KdbxXmlAutoTypeNode` /
    `KdbxXmlBinaryNode` / `KdbxXmlStringNode` / `KdbxXmlTimesNode`，均 ≤400；
  - **回归验证**：全仓 12 处 `debugLog` 诊断点**逐一核对全部无损迁移**（`SyncCycleRunner` 确无日志需求）；
    **P3-07 的附件别名隔离语义完整保留**（`binariesPool[refIndex].data.copyOf()` 仍在
    `KdbxXmlBinaryNode.kt:50`，回归锁 `KdbxAttachmentAliasIsolationTest` 4 例未改）。
- **仍未达成（本条目剩余全部工作）**：
  1. **`app/.../ui/screens/unlock/UnlockViewModel.kt` 仍 979 行未拆**（原计划第二波拆分因执行者不可达未完成）。
     建议方向：`BiometricUnlockCoordinator`（生物识别状态机与自动唤起意图）/ `KeyFileSessionCoordinator`
     （密钥文件借用与清零）/ 保留 `UnlockViewModel` 仅做状态编排；
  2. **本批次新增代码令两个文件越过 400 行阈值**，一并登记：
     `app/.../ui/screens/settings/SettingsViewModel.kt` **902 行**（既有 879 + 本批次导入接线；
     建议按「同步 / 健康 / 导出 / 导入 / 显示偏好」五个已存在的 `Settings*Controller` 模式继续外移）、
     `app/.../ui/screens/vault/VaultListViewModel.kt` **731 行**（既有 641 + 本批次 4 个偏好派生量与
     「条目/分组共用图标投影器」装配；建议按「偏好派生 / 图标投影 / 分组路径」外移）。
- **验收标准**：上述文件均降至阈值内（或就「为何不可拆」给出经论证的例外说明并登记）；
  拆分为**纯结构性**改动——`.\gradlew.bat test` 全绿且用例数不减（当前 **1146 例**）；
  优先依赖倒置（抽接口 + Hilt 绑定），不得引入循环依赖。
