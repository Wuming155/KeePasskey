# §498 备份第一道闸门 allowBackup 静态守卫批次（2026-10-11）

**条目**：`ISSUE-P3-583` **整条闭环**（P3 4 → **3**）
**来源**：2026-10-11 数据流安全审查发现 11（verdict=adjusted），六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md)「### 11.」。

## 498.1 原始条目（原样收录）

### `ISSUE-P3-583`：`android:allowBackup="false"` 第一道闸门无任何静态守卫锁定

**背景与影响**：`AndroidManifest.xml:93` `android:allowBackup="false"` 是备份面第一道闸门，但
`BackupAndSafHardeningTest.kt:24-73` 三个测试只断言 dataExtractionRules 引用字面量、九域排除与 SAF 归属判定，
**对 allowBackup 取值零断言**——未来改动（接入要求开启备份的 SDK、误编辑清单）把取值改回 true 后无任何断言变红。
复核修正：九域排除的任何破坏形态会被既有 contains/内容断言抓红，故泄露需 allowBackup 误改与九域破坏同时静默成立，
路径很窄，定性为回归防护缺口（纯静态、无数据随之出域）。

**验收标准**：`BackupAndSafHardeningTest` 新增对 `allowBackup="false"` 取值的断言。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md) 发现 11（verdict=adjusted）。
来源攻击面：备份与本地存储。

## 498.2 前提复核（2026-10-11 直读）

- **缺口成立**：`app/src/main/AndroidManifest.xml:93` 逐字为 `android:allowBackup="false"`（**行号零漂移**）；
  `BackupAndSafHardeningTest.kt` 既有三例（`清单必须引用数据提取规则文件` :28-31、
  `云备份与设备迁移必须逐域排除` :39-49、`SAF 清理必须在删除前做文档 URI 归属判定` :53-73）**对 allowBackup 取值零断言**，
  条目描述与实况逐字吻合（本批整改后该三例仍在原行号区间内，新增例插于第二 / 第三例之间）。
- **守卫面零断言复核**：`grep -rn "allowBackup"` 覆盖 `app/src/test/**`、`tools/**`、`.github/**`
  与 `AndroidManifest.xml` 本体——**仅命中清单第 93 行一条**（测试 / 机检脚本 / CI yml 内零命中）。
  仓内其余 `allowBackup` 出现（`app/build.gradle.kts:288`、`UnlockThrottle.kt:62`、`AGENTS.md:176` 及
  `.audit-recheck/`、`docs/` 多处）均为**注释 / 文档文本**，非断言，核心论点「守卫面内零断言」成立。
- **定性维持**：纯静态、无数据随之出域——九域排除（`data_extraction_rules.xml` 两域各九域）仍兜底，
  真实缺口是「allowBackup 单独被误改 true（规则文件完好）时全部测试仍绿、第一道闸门失去纵深防御价值」。

## 498.3 整改

### ① 新增静态守卫断言（AC 唯一项）

在 `BackupAndSafHardeningTest` 新增测试 `清单必须以 allowBackup=false 关闭系统备份第一道闸门`，
与既有 `清单必须引用数据提取规则文件` **同文件、同一 `readSource(MANIFEST_SOURCE)` 清单读取方式**：

1. 先以既有 `XML_COMMENT` 正则剔除 XML 注释（与既有 `云备份与设备迁移必须逐域排除` 同型用法）——
   注释内出现的 `allowBackup="false"` 字面量不得冒充 `<application>` 上的真实属性取值；
2. 再按 `android:allowBackup\s*=\s*"([^"]*)"` **取属性值**后 `assertEquals("false", …)`，
   而非 `contains` 整串字面量。

**为何取「正则取值后比对」而非 `contains` 整串字面量（稳健性说明）**：

- **布局无关**：`\s*` 容忍属性与 `=` 间、`=` 与取值间的任意空白（同行 / 异行 / 重排后仍命中），
  `contains("android:allowBackup=\"false\"")` 则对空白量与实际书写形态敏感；
- **可分辨**：取值 `"true"` 会被取到并令 `assertEquals` 失败（见 498.4 反校实测），
  `contains` 同样可分辨，但前者额外对「属性值被改成第三种形态（如未加引号的 `false` 变体）」给出「取不到值」的红；
- **注释免疫**：先剔 `XML_COMMENT` 保证不误取注释里的同形文本（`contains` 若无此步会被注释误导）。

新增 1 例、修改 0 例、删除 0 例（既有三例断言逐字未动）；配套把类 KDoc 的「两处」订正为「三处」并补第 3 条失效形态说明。

### ② 未改面

`AndroidManifest.xml` 本体（`allowBackup` 取值本就为 `false`）、`data_extraction_rules.xml`、
任何生产代码与实际打包产物路径**逐字未动**；本批仅动 `app/src/test/**` 一个测试文件。

## 498.4 验证

### 宿主

- **定向单测**（`--tests "com.keepasskey.app.security.BackupAndSafHardeningTest"`）→ `BUILD SUCCESSFUL`；
  §201 立规**回读 XML 确认在册**：`tests="4" skipped="0" failures="0" errors="0"`，
  四条 `testcase name` 含新增例 `清单必须以 allowBackup=false 关闭系统备份第一道闸门`。
- **反校（负样本必红，实测）**：把清单第 93 行临时改为 `android:allowBackup="true"`
  （`dataExtractionRules` 一行原样保留）后 `--rerun-tasks` 重跑同一测试类——
  **仅新增例失败**（`4 tests completed, 1 failed`），`<failure>` 逐字为
  `org.junit.ComparisonFailure: … expected:<[fals]e> but was:<[tru]e>`，命中 `BackupAndSafHardeningTest.kt:49`
  （`assertEquals` 处）；既有三例仍绿 ⇒ 断言**可分辨**、确在锁定该取值而非恒真。随后**清单已复原**为
  `"false"`（`git diff` 对 `AndroidManifest.xml` 零改动）。
  > 过程留痕：首次改坏后直接 `--tests` 跑得 `testDebugUnitTest UP-TO-DATE`（清单非该测试任务的声明输入，
  > 源码树文本由用例运行期读取）——**必须 `--rerun-tasks` 方真执行**，否则「改坏了却仍绿」属假绿。
- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（1m 31s / 114 tasks：6 executed、108 up-to-date）。
  `python tools/doc/preserve_test_failures.py` → `failures=0`（无首轮红，无需留痕）。
  `python tools/doc/count_test_results.py` → `xml=558 tests=3636 failures=0 errors=0 skipped=15`
  （§497 基线 `xml=558 tests=3635` ⇒ **+1 例**：同一测试类新增 1 例，无新增 XML）。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 495 份；分册登记 497 条；全量索引 497 条；最大 §498）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 616 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 742 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 495 个 / 批次正文 493 份；现树 工作区：497 / 495；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 742 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 742 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 742 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```

### 设备侧

- `app/src/androidTest/**` 与其余任何 `*/src/androidTest/**` **未改动** ⇒ 依测试资产纪律无需真机实跑；
  本批全部改动落在 `app/src/test/java/com/keepasskey/app/security/BackupAndSafHardeningTest.kt`（宿主单测）。

## 498.5 如实声明

- **纯回归防护缺口、无数据随之出域**：本条的泄露路径需「allowBackup 误改 true」与「九域排除同时被破坏」
  两个条件同时静默成立，而后者几乎必然触发既有 `dataExtractionRules` 引用 / 九域内容断言报警 ⇒ 路径很窄。
  本批的收益是让**第一道闸门**获得与第二道（规则文件）同级的静默失效防护，而非修补一条可利用的泄露链。
- **方案取舍**：采用「剔 XML 注释 + 正则取属性值 + `assertEquals`」而非最小化的 `contains` 整串字面量。
  理由见 498.3——布局无关、可分辨、注释免疫；代价是断言略长，但本仓已有 `XML_COMMENT` 正则与
  `云备份与设备迁移必须逐域排除` 的同型先例，风格一致。
- **重言风险自评**：断言读的是磁盘上的清单源文件（经 `readSource` 运行时读取）后取真实取值与字面量比对，
  非「测试体内可求值的纯局部表达式」；`check_tautological_assertions.py` 对本例零命中（读数见门禁块）。
- **设备侧**：本批不触碰平台行为，`allowBackup=false 整体禁备份` 的平台语义承仓内历史真机实测
  （`.audit-recheck/batches/B05-findings.md`）与 AOSP 公开契约，**本批未重跑设备实测**（静态守卫类改动无需）。
- **未改产品口径 / 未新增限界**：本条为回归防护加固、无新增取舍或残余风险，不涉及两表登记。
