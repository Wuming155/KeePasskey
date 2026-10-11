# §500 fast-gate 互操作对拍依赖版本钉定批次（2026-10-11）

**条目**：`ISSUE-P3-585` **整条闭环**（P3 2 → **1**）
**来源**：2026-10-11 数据流安全审查整批登记（外部审查工作流），发现 13，`verdict=confirmed`，经独立复核换人读码成立。
**关联**：本批改动 `tools/passkey-interop/verify_interop.py`，已**同批**更新 `tools/doc/gate_scripts.sha256`
对应哈希（`ISSUE-P2-574` / §489 建立的门禁脚本完整性基线——被清单覆盖的脚本漂移须同步清单，否则
`check_gate_script_integrity.py` 报红）。

## 500.1 原始条目（原样收录）

### `ISSUE-P3-585`：fast-gate 互操作对拍依赖浮动拉取（pip/apt 均无版本 pin），与本仓供应链纪律不一致

**背景与影响**：`build.yml:189-192` 在 fast-gate 内 `apt-get install keepassxc` 与
`pip install pykeepass cryptography` **均无版本 pin**，`:194-197` 调用的 `verify_interop.py` 对拍判据
（cryptography 独立解析 PEM `:155-173`、keepassxc-cli 交叉读 `:176-200`、pykeepass 字段与保护位判据 `:208-308`）
完全运行在浮动拉取包所在解释器进程内（`tools/passkey-interop/verify_interop.py:55-60` import 即执行；
`:391` 仅打印版本、无兼容性断言）——上游包被投毒或发布含行为变更的新版本时，可伪造「对拍通过」输出与退出码，
或吞掉判据失败。前提（上游投毒）当前无证据且不在威胁模型能力清单内；与本仓既有纪律
（`build.yml:14` Actions 全 SHA 钉死、`cargo --locked`）显式不一致。

**验收标准**：① `pykeepass` / `cryptography` / `keepassxc` 版本 pin（含留痕的升级流程），与 Actions SHA 钉死纪律同构；
② `verify_interop.py` 的版本兼容性断言口径一并补齐或登记。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md) 发现 13（verdict=confirmed）。
来源攻击面：CI/CD 与供应链。

**报告的最小修复建议**：① pip install 精确 pin（`pykeepass==X.Y.Z cryptography==A.B.C`），升级随批次显式变更并留读数；
② apt 安装后断言 `keepassxc-cli --version` 落在已验证区间（把 `build.yml:186-187` 已登记的「2.7.6≈2.7.12 未验证」
转为运行时断言或完成一次对拍验证后写死下界）；③ 可选：把对拍所用版本记入 step summary 供批次审计。

## 500.2 前提复核（2026-10-11 直读）

认领时直读判定本体与触发段（AGENTS.md 规则 6.1②「先复核前提再开工」）：

- **条目成立（pip/apt 均无 pin，判据全在浮动包进程内）**：`.github/workflows/build.yml` 的 fast-gate
  「安装对拍依赖」步骤原为 `python3 -m pip install --break-system-packages --quiet pykeepass cryptography`
  与 `sudo apt-get install -y --no-install-recommends keepassxc`，**均无版本约束**；紧随的
  「通行密钥互操作对拍」步骤调 `python3 tools/passkey-interop/verify_interop.py`。
  `verify_interop.py:55-60` 顶层 `import`（`cryptography` / `pykeepass`）即执行，`:391`（原行号，现为版本打印行）
  仅 `print` 版本、**无兼容性断言**——判据完全运行在浮动拉取包所在解释器进程内，与条目描述逐字吻合。
- **行号微漂移（不成立面）**：条目引 `build.yml:189-192,194-197,186-187` 为安装 / 对拍 / apt 版本注快照；
  现树 `build.yml` 因 §488 等批次微调，安装步骤位于 `:198-202`（内容逐字未变，仅行号后移）。
  **drift 不影响判据**。`verify_interop.py` 的 `:391` 现落于 `_cli_version()` 附近（同语义区）。
- **既有留痕（关键，pin 取值依据）**：本仓已就「对拍所用版本」留痕——
  `docs/records/通行密钥互操作对拍记录_2026-09-19.md` §1 表格明列 `pykeepass 4.2.0` /
  `keepassxc-cli 2.7.12` / `cryptography 50.0.0`；`docs/architecture/产品裁决登记.md:362,371,379`、
  `docs/architecture/实现约定与验证现状.md:32`、`docs/records/限界表证据与实测数据汇录.md:403` 与
  `docs/records/六专题综合研究与实测报告_2026-10-07.md` §3.2/§7 多处复述同一组版本。
  **本机实测**：`python --version`=3.12.10、`cryptography.__version__`=`50.0.0`、
  `pykeepass.__version__`=`4.2.0`、`keepassxc-cli --version`=`2.7.12`、`python -m pip index versions pykeepass`
  → `INSTALLED 4.2.0 / LATEST 4.2.0`。⇒ 留痕版本与本机对拍环境**逐一致**，pin 取值有据。
- **登记核查**：`find_decision.py`「浮动/CI 对拍」两表 no_match；批次 463 仅声明「apt 版本差异**未验证**」，
  未把 pip/apt 浮动面登记为取舍——本条非重复登记，且与本仓既有供应链纪律（Actions 全 SHA 钉死、`cargo --locked`）
  显式不一致，fix 与之同构可落地。

## 500.3 整改

### ① pip 精确 pin（AC①，`build.yml` fast-gate）

- 「安装对拍依赖」步骤的 pip 行由 `pykeepass cryptography` 改为
  **`pykeepass==4.2.0 cryptography==50.0.0`**（`--break-system-packages --quiet` 保留）。
- **取值依据**：= 本机对拍留痕版本（`docs/records/通行密钥互操作对拍记录_2026-09-19.md` §1），
  且本机实测同版本对拍 **161 条判据全绿**（见 500.4 基线读数）。**非**「无留痕的候选猜测」。
- **升级流程（留痕）**：改此处两个 pin **与** `verify_interop.py` 的 `PYKEEPASS_*` / `CRYPTOGRAPHY_*` 区间常量
  **同批**变更，并跑一次本 fast-gate 对拍留读数（全绿为基线）后据此登记批次——已在步骤注释与本批次正文写明。
  该口径与 Actions SHA 钉死、`cargo --locked` 的「升级须显式评审 + 留痕」同构。

### ② apt 侧改「运行期区间断言」（AC①，`build.yml` + `verify_interop.py`）

- **取舍**：**不**对 apt 做精确 pin（如 `keepassxc=2.7.6-*`）——Ubuntu runner 的 keepassxc 版本随基础镜像漂移，
  写死版本号会随镜像刷新失效（apt 精确 pin 的版本字符串含发行版修订号，镜像一变即 `E: Version ... not found`）。
  故按条目建议②改为**安装后断言 `keepassxc-cli --version` 落在已验证区间**，越界即 fail-closed。
- **落点**：断言统一实现在 `verify_interop.py`（该脚本已有 `_cli_version()`，是版本口径的单一真相源），
  `build.yml` 步骤注释写明取舍理由并指向该实现。原 `build.yml`「apt 版本 ≈2.7.6 与本机 2.7.12 差异未验证」的
  **登记口径**转为运行期断言。

### ③ `verify_interop.py` 版本兼容性断言（AC②）

- 新增常量（附动因 KDoc）：`PYKEEPASS_MIN=(4,2,0)` / `PYKEEPASS_MAX=(5,0,0)`；
  `CRYPTOGRAPHY_MIN=(50,0,0)` / `CRYPTOGRAPHY_MAX=(51,0,0)`；`KEEPASSXC_MIN=(2,7,6)` / `KEEPASSXC_MAX=(2,8,0)`。
  **区间语义**：下界 = 留痕版本（承载判据权威性的最低版本）；上界取「**下一主版本（排他）**」——
  主版本漂移属未验证面，静默放行即失去闸门意义。`keepassxc-cli` 下界取 **2.7.6** = CI 镜像（Ubuntu 24.04）
  登记版本、上界 2.8.0（排他）。
- 新增纯函数 `parse_version()`（容忍前缀噪声与两位段；不可解析返回 `None`）、
  `version_in_range()`（`[low, high)`；解析失败一律 `False`，**fail-closed**）与
  `verify_dependency_versions()`（对三方**运行期实际版本**逐个 `check`，越界 / 不可解析计入 `_failures`）。
- 在 `main()` 打印版本行后调用 `verify_dependency_versions()`；版本打印行扩为
  `pykeepass … / cryptography … / keepassxc-cli …`（fix③：三方版本进步骤日志供批次审计）。
- **与 pin 的分工**：pin 锚定**供给面**（装什么版本），本断言校验**消费面**（跑的确实是该版本）——
  防「pin 被改 / 预装版本 already satisfied / 上游撤回重发」等漂移静默放行。任一侧漂移均 fail-closed。
- 模块 docstring 补「依赖版本钉定（ISSUE-P3-585）」段；`tools/passkey-interop/README.md` 依赖段由
  `pykeepass >= 4.2` / `cryptography >= 41` 更正为与 pin / 断言一致的精确口径。

### ④ 门禁脚本完整性基线同步（`ISSUE-P2-574` / §489 前置约束）

- `verify_interop.py` 被 `tools/doc/gate_scripts.sha256` 覆盖 ⇒ 改脚本后**同批**重算其**行尾归一后**哈希并回写：
  `9206d2df…`（旧）→ **`64ce588c6e06c7a971c1e996b946840ce0ea70b81d5e46b5d8e516c2e61c32a0`**（新）。
- 归一必要性见 §489：本机 `verify_interop.py` 工作区为 **CRLF**（`git ls-files --eol` 实测 `w/crlf i/lf`），
  CI 恒 LF；按归一哈希计算后两侧一致（本批实测该文件工作区 `crlf=527 / lf_only=0`，无混行）。改后
  `python tools/doc/check_gate_script_integrity.py` → `清单 19 条 / 期望 19 条；命中 0 处`、EXIT 0。

## 500.4 验证

### Python 工具侧（本批唯一可执行验证面）

- **`--selftest` 扩展**：新增 7 条版本区间正 / 反样本（区间内放行 / 下界越界 / 上界越界 / 不可解析 /
  `keepassxc-cli 2.7.12` 在区间内 / `2.8.0` 越界 / 带前缀噪声仍可解析）——`verify_interop --selftest: PASS`，
  12 条判据全 `[ok]`（原 5 条 + 新增 7 条），EXIT 0。
- **真实环境对拍（本机留痕版本，探针产物在场）**：`python tools/passkey-interop/verify_interop.py` →
  `pykeepass 4.2.0 / cryptography 50.0.0 / keepassxc-cli 2.7.12`，4 条目双实现读数一致，
  **`✓ 对拍通过：164 条判据全部成立`**，EXIT 0（整改前基线 = **161** 条 ⇒ **+3** = 三方版本区间断言）。
- **集成反向取证（真实注入越界版本）**：以 `importlib` 载入脚本并注入
  `pykeepass=4.1.0`（低于下界）/ `cryptography=99.0.0`（高于上界）/ `keepassxc-cli="KeePassXC 2.9.0"`（未验证主版本）
  → `verify_dependency_versions()` **checks=3 failures=3**（三条 `HIT 依赖版本越界 …` 逐条指名），
  真实版本基线下 **checks=3 failures=0**。**判据可分辨：已知坏样本被抓红，非「恒绿扫描器」**。
- **门禁脚本完整性**：`python tools/doc/check_gate_script_integrity.py` → `清单 19 条 / 期望 19 条；命中 0 处`、
  清单 19 条与 SHA-256 基线逐条一致，EXIT 0（清单已同批更新）。

### 宿主（Gradle）

- **本批无任何 Kotlin / 模块生产或测试代码改动**——改动全落在 `.github/workflows/build.yml`（fast-gate 段）、
  `tools/passkey-interop/verify_interop.py`（宿主 Python 工具）、`tools/passkey-interop/README.md` 与
  `tools/doc/gate_scripts.sha256`（哈希清单）+ 文档。⇒ 依任务口径「改了 Kotlin 才需全量单测」，
  **未跑 `.\gradlew.bat test`**（无 JVM 侧改动，跑它只会复验未改动的 Kotlin；已如实声明）。
  本批验证证据 = Python 工具 `--selftest` + 真实对拍 + **集成反向注入取证** + 门禁读数（见上）。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 497 份；分册登记 499 条；全量索引 499 条；最大 §500）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 616 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 742 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 497 个 / 批次正文 495 份；现树 工作区：499 / 497；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 742 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 742 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 742 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```

### 设备侧

- 本批**未触碰** `*/src/androidTest/**` 与任何原生分派 / 绑定面，亦**未运行任何 `connected*`** ⇒ 依测试资产纪律无需真机实跑。

## 500.5 如实声明

- **pin 取值依据与「是否已验证」**：`pykeepass==4.2.0` / `cryptography==50.0.0` 取值 = 本机**对拍留痕版本**
  （`docs/records/通行密钥互操作对拍记录_2026-09-19.md` §1，且 `docs/architecture/产品裁决登记.md:362,371,379`、
  `docs/records/六专题综合研究与实测报告_2026-10-07.md`、`docs/records/限界表证据与实测数据汇录.md:403`
  多处一致复述），**非候选猜测**；本机实测同版本对拍 161 条判据全绿（500.4 基线）。
  **边界**：本机为 Windows，**未**在 Ubuntu runner 上实跑 `pip install ==` 解析——pin 在 **CI 首跑**方得最终确认；
  `pykeepass 4.2.0` 另经 `pip index versions` 确认为 PyPI 现存版本（`INSTALLED 4.2.0 / LATEST 4.2.0`）。
  若 CI 首跑 pip 解析失败，按 fail-closed 可见报红并据此复评。
- **apt 收敛方式与理由**：**未**采用 apt 精确 pin（Ubuntu 镜像 keepassxc 版本随基础镜像漂移、带发行版修订号的
  精确版本字符串易随镜像刷新失效）；改采**运行期区间断言**（条目建议②原路径）。**残余**：断言要求
  `keepassxc-cli ∈ [2.7.6, 2.8.0)`——下界 **2.7.6 属候选**（本机为 2.7.12，**无 Ubuntu 环境实测**），
  待 CI 首跑确认；若镜像实际提供更低版本则按 fail-closed 报红并复评。apt 版本随基础镜像的漂移面**不可由
  精确 pin 消除**，故保留该残余并如实声明（区间上界已把「未验证主版本漂移」纳入 fail-closed）。
- **fix③ 采纳形态**：条目「可选：版本记入 step summary」以**脚本 stdout 打印三方版本**（进 CI 步骤日志 =
  留痕载体）实现，**未**写 `$GITHUB_STEP_SUMMARY`（避免在 workflow 内再引一套版本读取逻辑与脚本单一真相源重复）；
  如实声明该形态差异。
- **未采纳项**：无（AC①/② 均已落地；apt 精确 pin 属「二者取其一」中被舍弃的形态，理由见上）。
- **真实缺陷 / 过程留痕**：本批一改即触发 §489 的清单红线——若只改 `verify_interop.py` 而漏更新
  `gate_scripts.sha256`，`check_gate_script_integrity.py`（hygiene-gate 首步）必红。本批**先改脚本、再重算
  归一哈希回写清单**，并以 `check_gate_script_integrity.py` 自校验（命中 0）确认，顺序与 §489 实测教训一致。
- **未改面**：`verify_interop.py` 的全部既有对拍判据（字段 / 保护位 / PEM 独立解析 / 双实现交叉）、退出码语义
  （0/1/2）、摘要行口径、`--selftest` 原有 5 条判据**逐字未动**（仅新增，无修改）；`.github/workflows/build.yml`
  的触发段、其余 job 与步骤、`tools/device/**`、`check_dependency_cvss.py` 及其余门禁脚本**逐字未动**。
