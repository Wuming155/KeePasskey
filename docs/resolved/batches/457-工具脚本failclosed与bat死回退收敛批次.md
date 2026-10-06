# §457 工具脚本 fail-closed 与 `.bat` 死回退收敛批次（2026-10-06）

**条目**：`ISSUE-P3-514` + `ISSUE-P3-515` + `ISSUE-P3-516` **三条整条闭环**（P3 11 → **8**）
**来源**：`2026-10-06` 三类隐蔽性故障排查报告（[`../../records/三类隐蔽性故障排查报告_2026-10-06.md`](../../records/三类隐蔽性故障排查报告_2026-10-06.md)）；三条均为 **dormant**（零错误效果，缺陷以「无证据当绿 / 静默跳过 / 结构性死代码」形态存在）。
**本批性质**：**纯 `tools/` 与根目录 `.bat` 工具面**——零生产代码改动，故**不触发真机 / 设备侧义务**。

## 457.1 原始条目（原样收录）

### ISSUE-P3-514：`count_test_results.py` 在零 XML 输入下输出全零读数并 exit 0（空证据当绿）

- **核实时间点**：2026-10-06（报告方已实测复现；本轮登记实读判据行）；**核实方式**：实读 `tools/doc/count_test_results.py:59-66`（`totals` 仅对发现的 XML 累加，零文件 ⇒ 全零）、`:67-70`（打印全零行）、`:75`（`return 0 if totals['files'] == 0 and totals['errors'] == 0 else 1`，**判据不含 `files` 计数**）、`:43-45`（`return 2` 只判「未找到任何模块目录」）；对照 `tools/doc/preserve_test_failures.py:27`（「`2` = 未找到任何 JVM 单测 XML（**不得**当绿：无法判别『没红』还是『没跑』）」）、`tools/device/check_connected_device_results.py:19,23`（缺结果 XML 退 2 / `tests==0` 退 1）与 `:28`（明称本脚本是「JVM 单测的唯一尺子」）；实读 `AGENTS.md` §5 称其「JVM 单测聚合计数的唯一尺子（`test` 后跑它）」；实读 `.github/workflows/build.yml:96-104` hygiene-gate **仅 9 条机检、不含本脚本**，`grep 整个 .github/` 无命中 ⇒ 仅人工调用。
- **背景**：该脚本不在 CI、仅人工调用，零证据绿只能靠人读输出行发现。按 AGENTS §5 处方流程（先跑 `test --rerun-tasks` 再跑尺子）JVM 单测 XML 必然存在，故**处方流程下不可达**；零 XML 仅在违背处方时（fresh clone / `gradlew clean` 后未跑 test / cwd 错误）出现。与同族脚本的 fail-closed 口径**双标并存**，与项目自身「无证据不得当绿」家规相悖。
  - **触发状态：dormant**——退出码口径自脚本诞生即如此，历史上从未有批次记录过零 XML 绿读数。
  - **未跑项**：报告方以临时目录拷贝运行复现（脚本按 cwd 取模块根），**未在仓内清 `build/` 实测**（破坏性，不做）。
- **涉及文件**：`tools/doc/count_test_results.py:59-75`。
- **验收标准**：`:75` 前补 `if totals['files'] == 0: return 2`（并同步 `--excluded` 分支），与 `preserve_test_failures.py:27` 口径对齐；`--selftest` 补零 XML 反样本。

### ISSUE-P3-515：`preserve_test_failures.py` 对解析失败的测试结果 XML 静默 `continue`（假绿后按工序即触发毁灭性重跑）

- **核实时间点**：2026-10-06（报告方已用合成截断样本实跑复现；本轮登记实读核心）；**核实方式**：实读 `tools/doc/preserve_test_failures.py:58-61`（`try: root = ET.parse(path).getroot()` / `except ET.ParseError: continue`——**解析失败静默跳过**）、`:25-27`（退出码仅三态，`:27` 的 exit-2 立据是「无法判别『没红』还是『没跑』」——**同型不可判态却被当绿**）、`:164-166`（`if not found: print(f"无失败用例（已扫描 {len(paths)} 份 XML，无需留痕）"); return 0`，`len(paths)` 含不可解析文件，消息反而暗示「已成功扫描」）；对照 `tools/doc/count_test_results.py:61` 与 `tools/device/check_connected_device_results.py:81` 均**裸调用** `ET.parse`（损坏即崩、fail-closed）；`grep -rn ParseError tools/` 全树**仅本脚本一处**吞并；报告方实跑 `--selftest` PASS（6 项），内嵌样本 `:94-110` 全为良构 XML ⇒ **损坏分支无机检覆盖**。
- **背景**：本脚本的核心职责正是「闸门红了 ≠ 红在哪**可查**」（`AGENTS.md` §6.2 首轮红纪律 + `ISSUE-P3-489` AC①：肇事用例清单必须落盘留痕后才允许重跑覆盖）。假绿后按工序即触发毁灭性重跑（`--rerun-tasks` 会全量覆盖 `test-results`，覆盖后首轮肇事者不可复原，§445.6 实测）。
  - 报告方已如实区分：**skip-and-continue 作为取证工具的 best-effort 收集策略本身是对的**（首文件崩会连累其余证据），缺陷**仅在「静默」**——无告警、无不可解析计数、退出码无对应态。
  - **触发状态：dormant**——脚本仅一个提交（`03e2f652`）且不挂 CI（批次文档明示「未进 hygiene-gate 的取舍」），仅人工在红退出后调用，暴露窗口 ≈1 天；触发前提是 `test` 进程异常中断半写 XML（Ctrl+C / daemon 被杀 / OOM），常规红跑不产生。旁证：唯一尺子 `count_test_results.py:61` 裸 `ET.parse` 历经多批实跑从未因仓产 XML 损坏而崩。
  - **未核实项**：报告方**未跑真实红色 `gradlew test`** 制造天然截断 XML，改以 importlib 载入脚本代码、对合成截断样本实跑复现。
- **涉及文件**：`tools/doc/preserve_test_failures.py:58-61` / `:25-27` / `:164-166`。
- **验收标准**：统计并打印**不可解析份数**（带 warning 标记）；全不可解析时按 exit-2 同型判「无法判定」退非 0；`--selftest` 补损坏 XML 反样本（照 `ISSUE-P3-489` 既有内嵌正反样本机制）。

### ISSUE-P3-516：`export-preview-*.bat` 的 py 启动器回退分支是结构性死代码

- **核实时间点**：2026-10-06（报告方已做 5 组 `cmd` 运行期探针；本轮登记实读脚本原文）；**核实方式**：实读 `export-preview-main.bat:13`（`where python >nul 2>nul`）、`:14`（`if %errorlevel%==0 (`）、`:15`（`python …`）、`:17`（`where py >nul 2>nul`）、`:18`（`if %errorlevel%==0 (`）、`:19`（`py -3 …`）、`:21`（`echo [WARN] python not found, skip wrapper regen`）、`:24`（`if errorlevel 1 (`）、`:32`（`call gradlew.bat :app:exportMainPreviewScreenshots`）；`export-preview-secondary.bat:13-23` 逐行同构（仅 `:32` 任务名不同）；**实测探针**（报告方实跑）证实 ①`%errorlevel%` 在同一逻辑行内取语句解析前旧值；②结构镜像探针输出 `BR-1` ⇒ 内层回退分支永不执行；③`if errorlevel 1` 特殊形式确为运行期取值；④`echo` 不复位 `errorlevel`；⑤多行括号块整体为单解析单元、块内 `%var%` 一次冻结 ⇒ `:18` 判据与 `:14` 同源；`where python` / `where py` 命中情况确认当前机器走 `:14` 正常分支；`.github/workflows/build.yml` 对 export-preview 与 `.bat` **零命中** ⇒ CI 不引用；`git log` 两文件仅 `e3463f5f` 一条历史；`git check-ignore` 证实 `.gitignore:55` `app/src/screenshotTest/` 被忽略。
- **背景**：`:19` 的 py 回退**在任何机器上都不可达**（else 分支只在解析期 `errorlevel ≠ 0` 时进入，而 `:18` 冻结同一值）。「仅装 py 启动器（PATH 无 python）」的机器上包装再生成被跳过，打印 WARN 后 `:24` 读到 `:17` `where py` 成功留下的 0 而**放行**，`:32` 继续对 gitignore 生成物目录以陈旧 / 缺失包装导出预览。
  - 报告如实修正候选四处：①内层 if 实为 `:18`（`:17` 是 `where py`）；②死代码范围比候选更强；③`:21` 文案在 py-only 机器上**字面为真**，失实处在于掩盖「py 回退本应运行而未运行」；且**无 python 无 py** 的机器反而会经 `where py` 失败在 `:24` 触发 `[FAIL]` 中止（候选未提此不对称）；④即便开了 delayed expansion，`%errorlevel%` 写法仍冻结，必须改写为 `!errorlevel!` 或 `:24` 已在用的 `if errorlevel N` 运行期形式。
  - **触发状态：dormant**——本机走正常分支（危害路径当前配置不可达）+ CI 不引用 + 死分支按构造在一切机器上不可达；危害仅在 py-launcher-only 安装（未勾选 Add to PATH 的常见配置）的机器显现。
- **涉及文件**：`export-preview-main.bat:13-24` / `:32`、`export-preview-secondary.bat:13-24` / `:32`。
- **验收标准**：改写 `:13-23` 为运行期判定形态（`if errorlevel 1` 特殊形式或 `setlocal enabledelayedexpansion` + `!errorlevel!`），使 py 回退**真实可达**；并确认 py-only 与全无两条路径都给出正确终态。

## 457.2 前提复核（2026-10-06 直读 + 实测）

- **P3-514 前提成立**：判据行 `return 0 if totals['files'] == 0 ...` 确不含 `files` 计数；脚本无 `--selftest` 子命令（AC 提到的「`--selftest` 补零 XML 反样本」需**一并新建**该机制，本批照做）。
- **P3-515 前提成立**：`except ET.ParseError: continue` 在位；`--selftest` 6 项全为良构样本，损坏分支零覆盖。
- **P3-516 前提成立**：两文件 `:13-23` 逐行同构；本机 `where python` 命中 ⇒ 走正常分支。

## 457.3 整改

### ① P3-514：零证据退 2 + 新建 `--selftest`

`count_test_results.py`：聚合逻辑抽出 `aggregate()`，判据抽出 `verdict(totals)`（**零 `testsuite` 元素同样退 2**：根元素非 `testsuite` 时该文件不计数，与零 XML 同属「无证据」）。新增 `--selftest` 子命令，四条内嵌反校：正向样本计 1 份 / 2 条用例且判绿、截图目录不计入、**零 XML ⇒ 2**、**根元素非 `testsuite` ⇒ 2**。文件头判据由三条扩为四条，退出码语义写明。
`--excluded` 分支**不**改判据（该分支「零」的语义是「无被排除项」，属正常态），但同步加一条提示：主计数同样零证据时打 warning。

### ② P3-515：不可解析份数显式化 + 全覆盖退 2 + 损坏反样本

`preserve_test_failures.py`：`collect_failures` 返回 `(found, unparsable)`；新增 `judge(total, found, unparsable)`——**全部不可解析**与「零 XML」同型退 `2`；部分不可解析时按证据可用处理（best-effort 收集策略保留），但份数与路径清单打到 stderr（warning），且「已扫描 N 份」话术追加「其中 M 份不可解析」，修正原消息**反向暗示证据完整**的问题。
`--selftest` 由 6 项扩为 **13 项**：新增截断 XML 反样本（`SAMPLE_BROKEN`，模拟 `test` 进程被 Ctrl+C / OOM / daemon 被杀时的半写 XML）三条 + 混合场景（1 良构含红 + 1 截断）三条。

### ③ P3-516：改为运行期判定，并把「全无」判为硬失败

两文件 `:13-23` 统一改为：

```bat
set "PYCMD="
where python >nul 2>nul
if not errorlevel 1 set "PYCMD=python"
if not defined PYCMD (
  where py >nul 2>nul
  if not errorlevel 1 set "PYCMD=py -3"
)
if not defined PYCMD (
  echo [FAIL] neither "python" nor "py" launcher is on PATH; cannot regenerate wrappers
  pause
  exit /b 1
)
echo   using: %PYCMD%
%PYCMD% tools\export_previews\generate_screenshot_test_wrappers.py
```

`if not errorlevel N` 是 cmd 的**运行期**取值特殊形式（不受括号块解析期冻结影响），故 `py` 回退真实可达；「既无 python 也无 py」由原「打印 WARN 后放行」改为**硬失败退出**（不得带着陈旧 / 缺失包装继续导出 gitignore 生成物目录）。

## 457.4 验证

### ① 脚本自检（实跑，原样粘贴）

```
$ python tools/doc/count_test_results.py --selftest
  [ok] 正向样本计入 1 份 / 2 条用例
  [ok] 正向样本判绿（exit 0）
  [ok] 截图目录不计入主计数
  [ok] 零 XML ⇒ exit 2（不得当绿）
  [ok] 根元素非 testsuite ⇒ exit 2
count_test_results --selftest: PASS
EXIT=0

$ python tools/doc/preserve_test_failures.py --selftest
  [ok] dirty 命中 2 条
  [ok] dirty 无可解析失败项
  [ok] 排序后首条为 BarTest#kaboom(error)
  [ok] 次条为 FooTest#boom(failure)
  [ok] 失败信息实体解码正确
  [ok] 截图测试目录被排除
  [ok] clean 样本零命中
  [ok] 截断 XML 计入 unparsable（1 份）
  [ok] 截断 XML 不产出失败条目
  [ok] 全损坏 ⇒ judge 判 2（无法判定，不当绿）
  [ok] 混合场景：良构证据仍被收集
  [ok] 混合场景：损坏份数被如实计数
  [ok] 混合场景：judge 判 1（有红，不因部分损坏改判）
preserve_test_failures --selftest: PASS
EXIT=0
```

仓内实跑（有真实 XML 证据时仍判绿，未误伤）：`count_test_results.py` ⇒ `xml=521 tests=3393 failures=0 errors=0 skipped=13` EXIT=0；`preserve_test_failures.py` ⇒ `无失败用例（已扫描 521 份 XML，无需留痕）` EXIT=0。

### ② `.bat` 死分支复现与修复对照（实跑，探针已删）

在 `build/` 下建两组**同型探针**（脚本内 `set PATH=C:\Windows;C:\Windows\System32` 模拟「仅 py 启动器」机器，其中 `py.exe` 在 `C:\Windows`、`where.exe` 在 `System32`）：

```
=== old form (py-only machine) ===
OLD_RESULT=skip_warn        ← py 回退**未执行**（死分支复现）
=== new form (py-only machine) ===
NEW_RESULT=py -3            ← 回退真实可达
=== new form (neither python nor py) ===
NEW_NONE_RESULT=hard_fail   ← 不再「WARN 后放行」
```

另以**真实脚本首段**干跑（副本置于仓库根、把 `call gradlew.bat …` 与 `start explorer` 换为 echo、去掉 `pause`）验证语法与正常分支：

```
[1/3] Generate screenshotTest wrappers (locale zh-CN)...
  using: python
locale=zh-CN promoted=119 wrappers=119 packages=17
[2/3] Render and export main previews...   ← (gradle 调用在探针中被跳过)
```

探针文件（`build/probe-516*.bat`、临时副本）**已删除、未入库**（`build/` 本就 gitignore）。

### 门禁读数（`gate_readings.py`，原样粘贴）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 457 份；分册登记 459 条；全量索引 459 条；最大 §459）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 581 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

宿主单测读数（全量 `.\gradlew.bat test --rerun-tasks --max-workers=1` 后 `count_test_results.py`）：`xml=523 tests=3405 failures=0 errors=0 skipped=13`（§455 基线 `3393` ⇒ **+12**：app 侧 `ProguardRuleRealityTest` 3 + `DependencyResolutionDeterminismTest` 2 + `CredentialRequestCodeWiringTest` 2 = 7，sync 侧 `S3DirectoryListTruncationTest` 5）。同轮 `preserve_test_failures.py` ⇒ `无失败用例（已扫描 523 份 XML，无需留痕）`。全量 `test` **BUILD SUCCESSFUL**（12m22s，114 tasks）。

## 457.5 如实声明

- 三条均**零生产代码改动**（`tools/` 与根目录 `.bat`）⇒ 无真机义务。
- `--selftest` 机制中，零 XML / 损坏 XML 均用**临时目录合成样本**（`tempfile`），未做仓内清 `build/` 的破坏性实测（与报告方取舍一致）；「真实 `gradlew test` 产出的截断 XML」仍无天然样本（旁证：唯一尺子裸 `ET.parse` 历经多批从未崩）。
- P3-516 的 `.bat` 回退分支在**本机不可达**（本机 `python` 在 PATH），回退可达性由受控 PATH 探针证明，非本机真实安装形态。
