# §330 供应链告警豁免与 Dependabot 积压 PR 闭环批次（`ISSUE-P3-321` + `ISSUE-P3-322` 闭环）

> **批次性质**：依赖治理 / 运维批次——无生产代码改动。改动面 =
> `.github/owasp-dependency-suppressions.xml` 新增两条人工核实误报豁免 +
> 三个 Dependabot PR 的依赖版本提升（经 GitHub squash 合并进入 main）+ 文档归档。
> **过程教训如实留痕**：`ISSUE-P3-321` 的登记前提被本批第一次重扫**实况推翻**，按条目维护规则 2
> 「前提已不成立→就地修正」完成改道后闭环（见 §2.2）。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P3-321` GitHub Code Scanning 5 条 CVE-2020-29582 陈旧告警未闭合 | 用户指派（面板截图）+ `gh api` 枚举 | **闭环** |
| `ISSUE-P3-322` Dependabot 积压 3 个 PR（#8 / #9 / #10）验证合并 | 用户指派（面板截图）+ `gh pr diff` 逐个审阅 | **闭环** |

## 2. 整改内容

### 2.1 `ISSUE-P3-322`：三个 Dependabot PR 验证合并

三个 PR 开于 2026-09-18，因 §326 治理后 `build.yml` 不再对 PR 触发 CI，按「合并后拉取 main
跑全量宿主验证」口径处置，按 #10 → #9 → #8 顺序 squash 合并（#8/#9 同文件不同 hunk，顺序
合并零冲突；合并后远端 dependabot 分支一并清理）：

| PR | 内容 | 验证口径 |
|---|---|---|
| #10 | github-actions-all 组 4 项：`actions/setup-java` v6.0.0→v6.0.1、`github/codeql-action` v4.37.9→v4.38.0 | patch/minor 提升，由本批两次 workflow 运行（OWASP 扫描 + SARIF 上传）实际运行自证 |
| #9 | material3 `1.5.0-alpha27`→`1.5.0-alpha28` | 属 `ISSUE-P3-09`（ZT-20）AC3 登记的「本值仅允许在 1.5.0-alphaN 内部随安全修复上调，禁止跨 minor 跳跃」**允许范围**（不跨 minor、不退出 alpha 渠道；退出条件「1.5.0 stable 发布」未触发，`compose-material3-alpha` 别名保留） |
| #8 | gradle-minor-patch 组 6 项：AGP `9.2.1`→`9.4.1`、KSP `2.3.11`→`2.3.12`、Compose BOM `2026.08.00`→`2026.09.00`、navigation-compose `2.10.0`→`2.10.1`、bouncycastle `1.85.2`→`1.86` | AGP minor×2 跳跃为最大风险面——`test --rerun-tasks` 全绿（含 `SupplyChainScanSurfaceTest` 对 AGP 9.4.1 工具链面 fail-closed 验证）+ `assembleDebug` 全模块编译实证（§3）；AGP 9.4.1 未引入新的工具链配置名（第二次扫描报告无新增达阈条目，闸门静默即证据） |

合并提交：`83a1db8f`（#10）/ `06341f92`（#9）/ `d53fc93e`（#8），三者合并时间
2026-09-25T10:18:06Z ~ 10:18:34Z（`gh pr view` 读数）。

### 2.2 `ISSUE-P3-321`：前提被实况推翻 → 改道 suppression 白名单 → 9 条告警全闭合

**登记前提（已证伪）**：「构件已不入扫描面、重扫即自动 fixed」——登记时依据是本机（AGP 9.2.1
/ Compose BOM 2026.08.00）`dependencies --all` 全配置零命中。

**第一次重扫（run 36123416214）实况推翻**：报告 `dependency-check-report.json` 显示
CVE-2020-29582 仍 ×5，且**新增** 2 组误报（4 条）：

1. **CVE-2020-29582 ×5 仍被扫出**：
   - `kotlin-reflect@1.6.10`：经 `hilt-compiler@2.60.1` / `kotlin-build-tools-impl@2.4.x`
     承载于 `app:kotlinCompilerClasspath` / `app:kspDebugKotlinProcessorClasspath` /
     `app:hiltAnnotationProcessorDebug` / `app:kotlinBuildToolsApiClasspath` 等**编译器工具链
     配置**（构建期 JVM 面，不入 APK）——这些配置在 PD-25 收口的 AGP 工具链族（UTP / lint /
     截图）之外，**未被 skip**；
   - `kotlin-stdlib-jdk7/jdk8@1.8.0`：同上（hilt-compiler 传递）；
   - `kotlin-stdlib-jdk7/jdk8@1.8.22`：另经 `app:debugRuntimeClasspath` /
     `releaseRuntimeClasspath`——**PR #8/#9 合并后**（Compose BOM 2026.09.00 → foundation
     1.13.0-alpha01 等）androidx 构件以旧 Kotlin 工具链构建、POM 声明 stdlib-jdk8 传递引入，
     且图中无更高声明版本故保留 1.8.22（1.8.20 起 jdk7/jdk8 构件已并回 kotlin-stdlib、为
     空壳构件；实际运行时 stdlib 为 2.4.20）。登记时点在合并前，故本地反查零命中——
     **前提失真的时点根源**。
   - 命中根因（版本不可达误报）：CVE-2020-29582 受影响面是 Kotlin **< 1.4.21**（描述原文
     "In JetBrains Kotlin before 1.4.21"；1.4.21 安全版改走 NIO Files API 修复，与 Guava
     CVE-2020-8908 同源）；NVD 的 CPE 却写成 `versionEndExcluding: 2.1.0`（2026-09-25 NVD
     API v2.0 实查），与其自身描述矛盾——命中构件 1.6.10 / 1.8.0 / 1.8.22 全部 ≥ 1.4.21。
2. **新增 CPE 名称碰撞误报 ×2 组（4 条，§327 扫码栈迁移引入的生产依赖）**：
   - `camera-lifecycle@1.6.2` × CVE-2007-4234：受影响产品为 **Camera Life**（PHP 相册 Web
     应用，2.6 之前；MITRE CVE API 实查该记录无 CPE 数据）；
   - `camera-video@1.6.2` × CVE-2015-3362：受影响产品为 **Drupal Video 模块**（7.x-2.11 之前，
     NVD CPE `cpe:2.3:a:video_project:video:*:*:*:*:*:drupal:*:*`）。

**改道处置**（依据 ISSUE-P3-32 先例；**不扩** PD-25 skip 名单——边界②明文禁止为让闸门变绿
而扩 skip）：人工核实后写入 `.github/owasp-dependency-suppressions.xml` 两条豁免
（提交 `b32ee187`）：

- **kotlin 条目**：`^pkg:maven/org\.jetbrains\.kotlin/(kotlin-reflect|kotlin-stdlib-jdk7|kotlin-stdlib-jdk8)@(1\.6\.10|1\.8\.\d+)$`
  × CVE-2020-29582——版本段显式限定实际出现的版本族，kotlin-stdlib 主构件不在豁免内，
  低于 1.4.21 的构件出现时 regex 不再匹配（fail-closed）；
- **camera 条目**：`^pkg:maven/androidx\.camera/(camera-lifecycle|camera-video)@1\.6\.\d+$`
  × CVE-2007-4234 + CVE-2015-3362——版本段限定 1.6.x，其余 androidx.camera 构件不在豁免内，
  camera 升级后须重新核实登记（fail-closed）。

两条豁免与既有条目同构（notes + packageUrl regex + cve），`SupplyChainSuppressionPolicyTest`
4/4 全过（notes 必填 / 版本界-白名单二选一不变式 / Kotlin 修复版声明机检）；regex 行为另做
10 组正反例反校全过（含「降级 1.4.0 不匹配」「其余 camera 构件不匹配」「升级 1.7.0 不匹配」）。

**第二次重扫（run 36124981448，success）**：GitHub 对不再产出的告警自动流转——9 条
（#46~#50 + #1667~#1670）全部 `state=fixed`（`fixed_at=2026-09-25T10:39:47Z`），Code Scanning
**open 告警清零**（`GET /code-scanning/alerts?state=open` → `[]`）。

## 3. 验证读数（原样粘贴）

### 3.1 JVM 单测聚合（`count_test_results.py`）

```
xml=412 tests=2774 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

`test --rerun-tasks --max-workers=1` 实跑 `BUILD SUCCESSFUL in 4m 9s`（日志文件留存，
非管道退出码）。**过程缺陷如实留痕**：首轮全量后为单验守卫测试跑过
`:app:testDebugUnitTest --tests SupplyChainSuppressionPolicyTest`，该任务覆盖了 app 模块
test-results 目录，致 `count_test_results.py` 一度读出 `tests=1131` 假象（app 只剩 4 例）——
重跑全量后恢复 2774 持平；教训：全量结果目录产出后、计数取尺前，不得再跑任何覆写
test-results 的局部任务。

### 3.2 门禁读数（`gate_readings.py`）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 327 份；分册登记 329 条；全量索引 329 条；最大 §329）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 476 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

> 注：上块采集于归档前；归档改动后按规则复跑（见 §3.5），以复跑读数为准。

### 3.3 供应链扫描与告警 API 读数

- 第一次重扫：run `36123416214` success；报告聚合 348 依赖、命中 CVE 共 3 种
  （CVE-2020-29582 ×5 / CVE-2007-4234 ×2 / CVE-2015-3362 ×2），均 < CVSS 7.0 闸门阈值；
  Code Scanning open 9 条（原 5 陈旧 + 新 4）。
- 第二次重扫：run `36124981448` success（CI 内 `check_dependency_cvss.py` 硬断言通过）；
  `GET /alerts/46..50` 与 `1667..1670` 逐条读数：`state=fixed`、`fixed_at=2026-09-25T10:39:47Z`；
  `GET /code-scanning/alerts?state=open` → `open count: 0 -> []`。

### 3.4 AGP 9.4.1 兼容实证

`assembleDebug`：`BUILD SUCCESSFUL in 1m 34s`（165 actionable tasks: 81 executed,
84 up-to-date）——全模块编译通过。

### 3.5 归档后复跑（索引 / 链接变更）

归档改动（ACTIVE_ISSUES 清空 + RESOLVED_LOG / 分册 / README 索引 + 本批次文档）后复跑
（首跑曾因分册 §330 行误用 `resolved/batches/` 前缀致 `check_md_links` 红 1 条——分册链接
基准是 `docs/resolved/`，修正为 `batches/` 后全绿）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 328 份；分册登记 330 条；全量索引 330 条；最大 §330）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 476 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

## 4. AC 核对

| 条目 | AC | 结果 |
|---|---|---|
| P3-322 | ① 3 PR squash 合并、分支清理 | ✅ `83a1db8f` / `06341f92` / `d53fc93e`，分支已删 |
| P3-322 | ② 合并后 main 全量 test 全绿 | ✅ `tests=2774 failures=0`（含 `SupplyChainScanSurfaceTest`） |
| P3-322 | ③ gate_readings 7/7 PASS | ✅ §3.2 / §3.5 |
| P3-322 | ④ assembleDebug 全模块通过 | ✅ §3.4 |
| P3-321 | ① suppression 两条入库 + 重扫成功 + CVSS 断言过 | ✅ `b32ee187` + run `36124981448` success |
| P3-321 | ② 5 条 CVE-2020-29582 转 fixed | ✅ §3.3 |
| P3-321 | ③ 4 条 camera 误配转 fixed | ✅ §3.3 |
| P3-321 | ④ open 告警清零 | ✅ `open count: 0` |

## 5. 残余与分流

- camera 升级到 1.7+ 时 camera 条目豁免失配（fail-closed 报红），届时按同纪律重新核实登记——
  非缺陷，属豁免维护纪律的预期行为。
- AGP / Kotlin 编译器工具链配置（`kotlinCompilerClasspath` 等）仍**在**扫描面内，其构件命中
  低危 CVE 时会产生 medium 告警（不入 CVSS 闸门）——是否将「Kotlin/Hilt 编译器工具链配置族」
  按 PD-25 同类口径收口，留待该面再次产生噪声时另行评估，本批不扩。
