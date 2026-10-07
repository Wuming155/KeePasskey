# 批次 464：MIUI 三例失败逐例定性与登记回读补强批次（`ISSUE-P3-523` 整条闭环；P3 1 → 1：`P3-523` 闭环 −1、§463 残留登记为 `P3-526` +1）

> 批次来源：[`../../records/六专题综合研究与实测报告_2026-10-07.md`](../../records/六专题综合研究与实测报告_2026-10-07.md) 与
> [`../../architecture/实现约定与验证现状.md`](../../architecture/实现约定与验证现状.md) §4.1「2026-10-06 第二数据点（MIUI 真机 `M332BF`）」条。

## §1 条目与整改

### ISSUE-P3-523：MIUI 第二数据点（tests=51 / failures=3）的 3 例失败逐例定性未做

- **前提复核（规则 6.1②，开工前现查）**：条目前提「三例的逐例定性未做」**成立**（§4.1 原文自陈，2026-10-07 实读）。
  同时现查发现两件事须先如实记下：
  ① **MIUI 机型本会话不在位**——`adb devices -l` 仅 `1c859bcc7d24`（Redmi 4X / LineageOS / Android 17 / API 37 / arm64-v8a），
  故 MIUI 侧**只能沿用 2026-10-06 的既有读数**，本批无法取得新的 MIUI 机制级证据；
  ② 设备上已有一份**今日（2026-10-07 05:06~05:09）**的 `:app:` 整层结果（`TEST-Redmi 4X - 17.xml` = `tests=68 failures=0 errors=0 skipped=0`，
  三例均在其中通过）与 `AutofillAuthChainDeviceTest` 的证据文件——本批**不以该旧读数结案**，仍按 AC 要求**自行复跑取证**（见 §2）。
- **§263 设备数据边界（跑前必查，已查）**：`pm list packages` 只有 `com.keepasskey`（release）与 `com.keepasskey.test`；
  `run-as com.keepasskey find /data/data/com.keepasskey -name '*.kdbx'` **无命中**（`files/` 下仅 `datastore/keepasskey_settings.preferences_pb` 与 `profileInstalled`）
  ⇒ **应用私有目录内无待保留密码库**；且自 §390 起 debug 包名为 `com.keepasskey.debug`+`.test`，安装不会顶替 release 包
  （§263 事故形态是 `applicationId` 相同的 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，此形态已由 §390 结构性消除）。
  读数留存：`build/issue523-device-precheck.txt`。

- **整改①（定性，AC①）**：在非 MIUI 真机上**逐类单独复跑**三例，取得本批读数并与 MIUI 侧现象逐例对照，判「厂商面 / 真实缺陷」：

  | 例 | MIUI 侧现象（2026-10-06 既有读数） | 非 MIUI 本批读数（2026-10-07） | 定性 |
  |---|---|---|---|
  | ① `DialogWindowHardeningDeviceTest` | 单例 **479 s** 后以**空 `<failure>`** 结束 | **3/3** 全绿，耗时 2.870 / 1.377 / 1.293 s（suite 5.540 s） | **厂商面**（进程冻结面） |
  | ② `AutofillAuthChainDeviceTest` | 断言文案「测试客户端 Activity 未出现在无障碍树中（跨包显式启动失败）」 | **1/1** 全绿，90.373 s | **厂商面**（后台启动策略面） |
  | ③ `CredentialSaveChainDeviceTest` | 经 `Assume` 记录 skipped | **1/1** 通过，4.475 s（`skipped=0`） | **厂商面**；跳过属用例设计的如实标注，非缺陷 |

  三例合计 `tests=5 failures=0 errors=0 skipped=0`，三个 Gradle 任务退出码全 0。

- **逐例依据（不写「大概」）**：
  - **① 空 `<failure>` ⇒ 断言体从未执行**。用例内所有等待都有上界（`awaitProbe` 10 s ×2 + `awaitFlagsCleared` 5 s ⇒ 单例最长 ≈25 s，
    三例合计 ≈75 s 上界）；实测 MIUI 单例 **479 s**、非 MIUI 三例合计 **5.5 s**（≈87×）。若失败发生在断言内，`<failure>` 必带
    `awaitProbe` 的超时消息；实际为空 ⇒ 失败落在**用例开始执行之前**，即 MIUI 对被测进程反复 `freezeUid`（2026-10-06 logcat 留痕
    `frozen process` / 自动填充服务反复 bind-unbind）导致 UTP 层超时。**属 UTP 层超时，不是用例逻辑失败。**
  - **② 被判失败的是测试 APK 自己的 Activity 能否被跨包显式拉起**（`assertTrue(…, clientWindowUp)`），该动作由系统**后台启动（BAL）策略**
    决定，应用侧无控制权。本机 logcat 留痕其**在标准 AOSP 行为下成立**：
    `ActivityTaskManager: Displayed com.keepasskey.debug.test/com.keepasskey.app.autofill.AutofillClientActivity for user 0: +1s508ms`，
    用例自证 `客户端窗口出现=true`，且整条链路（认证数据集 → 框架拉起解锁页 → 解锁 → 链入选择器 → 写入真实凭据 → 二次确认页写入）全绿
    （证据文件 `/sdcard/Download/autofill-auth-chain-evidence.txt`，本批已另存 `build/issue523-evidence/autofill-evidence.txt`）。
  - **③ 跳过本身即设计口径**。该用例自 `ISSUE-P2-494` 起经 `UiAutomation` 自备登记 `credential_service`，并把「未收到 provider 创建回调」
    按设计记为 `Assume`（环境前提缺失如实记 skipped，**不得**升级为失败），故 MIUI 上那条 `<failure>` 是 AGP 对 `AssumptionViolatedException`
    的记账形态，**不构成应用缺陷证据**；本机该用例真收到回调并走完（`KeePasskeyCredProvider` 的 `onBeginCreateCredentialRequest` 在 15 s 窗口内命中）。

- **整改②（定性可自证，AC①的延伸）**：`CredentialSaveChainDeviceTest` 此前**只记「没收到回调」，不记「自备登记是否被平台接受」**——
  而「平台未接受 shell 写入」与「系统侧路由未落到本 provider」对下一次厂商 ROM 复跑的处置完全不同，缺这条读数就只能继续停在「未定性」。
  本批补**登记回读**（`settings put secure credential_service <期望>` 后回读该键）：
  - 落 **logcat**（`Log.i(TAG, …)` + `println`，UTP 按用例归档 logcat artifact；实测可见
    `I CredentialSaveChain: credential_service 登记回读=com.keepasskey.debug/com.keepasskey.app.passkey.KeePasskeyCredentialProviderService`）；
  - 同值并入 **`Assume` 文案** ⇒ 跳过时随结果 XML 的 `<failure>` 一并归档；
  - 回读调用放在 `logcat -c` **之后**（否则被自清缓冲吃掉）。
  **`Assume` 语义一字未改**（环境前提缺失仍记 skipped）；用例 KDoc 同步写明两条成因的判别方式。
  **UTP 结果 XML 不含 `<system-out>`**（实测确认），故留证通道取 logcat + Assume 文案，不取 stdout。

## §2 验证

- **设备侧定向复跑（三类逐类单独执行，AC①）**：`./gradlew.bat :app:connectedDebugAndroidTest --max-workers=1 -Pandroid.testInstrumentationRunnerArguments.class=<单个类全名>`
  - ① `…security.DialogWindowHardeningDeviceTest` → `tests=3 failures=0 errors=0 skipped=0`，suite 5.540 s，**EXIT 0**；
  - ② `…autofill.AutofillAuthChainDeviceTest` → `tests=1 failures=0 errors=0 skipped=0`，90.373 s，**EXIT 0**；
  - ③ `…passkey.CredentialSaveChainDeviceTest` → `tests=1 failures=0 errors=0 skipped=0`，4.475 s，**EXIT 0**。
  - 结果 XML 与 logcat artifact 归档：`build/issue523-evidence/{dialog,autofill,savechain}/`（设备 `1c859bcc7d24`）。
- **设备侧源集编译门禁**：`./gradlew.bat :app:compileDebugAndroidTestKotlin --max-workers=1` BUILD SUCCESSFUL
  （改动后重编译通过；设备侧用例按 §150「新增/修改的 `androidTest` 必须真机实跑」执行——③ 即在改动后终态上复跑通过）。
- **全量宿主回归**：`./gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**（4m39s / **114 任务全执行**，EXIT 0），
  `python tools/doc/count_test_results.py` ＝ **`xml=524 tests=3413 failures=0 errors=0 skipped=15`**
  （与 §463 基线**逐项持平**——本批未新增 / 未删除宿主用例，只改了 `*/src/androidTest/**` 一个文件，宿主计数本就不含设备侧用例）。
- **机检**：`python tools/doc/gate_readings.py` **9/9 PASS**（原样读数见 §3）。

## §3 门禁读数（结案前现跑，原样粘贴）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 462 份；分册登记 464 条；全量索引 464 条；最大 §464）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 582 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

## §4 过程缺陷与如实声明

- **`-Pandroid.testInstrumentationRunnerArguments.class=类A,类B,类C` 会静默只跑首个类（本批实测，已落 §4.1 工具节第 3 条）**：
  首轮以逗号一次请求三例，结果 XML 只有**首个类**的 3 例（`tests=3`，另两类一行未跑），**退出码 0、无任何告警**；
  以两类复验（`Dialog…,CredentialSave…`）仍只跑首个（`tests=3`）。⇒ 本批改为逐类单独执行，并以结果 XML 的 `testsuite name` 核对实际执行的类。
  这是 `ISSUE-P3-493`「空转显绿」的**第三种触发路径**（不是零用例，而是**用例集被静默截断**）。**成因未溯源**（AGP / UTP 对 `class` 值的截断位置本批未追）。
- **⚠️ 本批「厂商面」是归因，不是机制实测**：本会话**无 MIUI 机型在位**，MIUI 侧只能沿用 2026-10-06 既有读数（含 ① 的 `frozen process` logcat 留痕）。
  本机只证成「三例在标准 AOSP 行为下可复现通过、在 MIUI 上不可推进」；**不得**据此外推「MIUI 上跑不出真缺陷」——
  该层取证口径不变：**须在非 MIUI 环境执行**（§4.1）。
- **`ISSUE-P3-523` 的 AC 之外未扩面**：`§463` 的残留「`.bak` 恢复入口的真机 UI 走查」与本条设备侧窗口相邻，
  §463 曾建议「一并覆盖为宜」；本批按其自身 AC 只做三例定性，**未**扩至该面 ⇒ 同批登记为 `ISSUE-P3-526` 入清单（避免只活在批次正文而不入清单）。
- **未做**：未跑 `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍（本批未触 UI 主源码、原生面、`参考项目/`、构建脚本与依赖清单）；
  `:app:` 之外的 connected 层本批未重跑（与本条无关，且 §4.1 已登记各层最近读数）。
- **设备数据**：本批复跑前后设备上 `com.keepasskey`（release）与 `com.keepasskey.test` 仍在，未顶替、未卸载（§263 事故形态未复发）；
  UTP 收尾按既有行为清理的是 `com.keepasskey.debug*`。
