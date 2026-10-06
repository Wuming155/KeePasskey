# §451 版本号真实来源接线与 Rust 工具链 env 接线批次（2026-10-06）

**条目**：`ISSUE-P2-498` / `ISSUE-P2-502` **两条整条闭环**（P2 7 → **5**）
**来源**：`2026-10-06` 三类隐蔽性故障排查报告（[`records/三类隐蔽性故障排查报告_2026-10-06.md`](../../records/三类隐蔽性故障排查报告_2026-10-06.md)）F14（版本号静态假读数）与 F02（`RUST_TOOLCHAIN` env 零消费）。两条均属「在案声明被违反但产物零影响 / 守卫缺口」形态，按用户指示本批一并收敛。

## 451.1 原始条目（原样收录）

### ISSUE-P2-498：「关于」页与设置主页展示的版本号恒为写死虚构字面量

- **核实时间点**：2026-10-06；**核实方式**：实读 `SettingsUiState.kt:249-250`（`val appVersion: String = "v1.0.0-Preview (2026 Edition)"` / `val buildNumber: String = "Build 2026.09.04"`）；全仓 grep `appVersion|buildNumber`（含隐藏文件，排除参考项目 / build / .gradle）确认项目源码仅 4 文件命中且**全为默认值 / 传参 / 渲染**，零赋值点、零测试引用；`SettingsUiStateProjection.kt` 内 grep 零命中、`:89` `initialValue = SettingsUiState(appLanguage = ...)` 走默认值；实读 `SettingsGroups.kt:255` 确认 `if (BuildConfig.DEBUG)` 只包住调试行、「关于」行在 `:268-277` 无条件 `add`（**release 同样渲染**）、`:274` 为副标题渲染点；实读 `app/build.gradle.kts:162` 确认真实 `versionName = "0.1.0"`（`:161` versionCode = 1）；`git log -S "VERSION_NAME" -- app/` 为空、`git log -S "v1.0.0-Preview"` 仅命中初始化提交 `47eee328` ⇒ **真实来源从未接线**。
- **背景**：字段名与渲染位宣称展示应用版本，实际是自初始化提交起从未接线的静态虚构值——「看似读真实版本、实为静态假读数」。已排查「Preview 品牌属有意取舍」的反假设：`产品裁决登记.md` 无任何版本品牌口径裁决条目，同文件 `ISSUE-P3-126③` 先例（`kdbxFormat` 静态字面量已删，立规「不得再写为静态字面量」）与 §66 批次（2026-09-15，`docs/resolved/batches/66-…md:73-74` 已把它登记为「未核查疑似死字段」）均按「静态字面量失真」定性。
  - **触发状态：masked**（判据 E1 现行错误产出）——投影链零赋值 ⇒ 每次设置页 / 关于页渲染必显错误字面量，**错误输出被持续产出、用户可感**，且全仓零测试引用无任何拦截。需如实说明：错值本身对用户是明示的，**被掩盖的是「与真实来源脱节」这一事实**而非值本身被藏。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt:249-250`、`SettingsUiStateProjection.kt:89`、`SettingsGroups.kt:274`、`subscreens/AboutSettingsScreen.kt:56-57`、`subscreens/AboutSettingsScreenSections.kt:48-49/86/94`、`app/build.gradle.kts:161-162`。
- **验收标准**：
  ① 改由真实来源接线（`BuildConfig.VERSION_NAME` / `VERSION_CODE`，或 `PackageManager` 读 `versionName`），投影链与 `initialValue` 均有赋值点；
  ② 若产品裁决为「Preview 品牌即期望展示」，须**改字段名与文案**使其不再宣称是版本读数，并按 `ISSUE-P3-126③` 先例在两登记表中登记该取舍；
  ③ 补宿主接线守卫测试（断言 `appVersion` 来自 `BuildConfig.VERSION_NAME` 而非字面量），并加跑 `python tools/audit/check_tautological_assertions.py`（防重言断言）；
  ④ 门禁 9/9 PASS。

### ISSUE-P2-502：`RUST_TOOLCHAIN` workflow env 零消费（改 env 静默无效的装饰性控制点）

- **核实时间点**：2026-10-06；**核实方式**：全仓 grep `RUST_TOOLCHAIN` 确认命中仅 `.github/workflows/build.yml:59`（定义）`:236`（注释）与一份文档提及，**零消费点**；实读 `build.yml:237/:474/:552` 三处 `toolchain: "1.97.1"` 字面量为真实钉版点；实读 `:50` 块注释「构建工具链固定版本（禁止漂移）」与 `:18` 头注释「Rust 全部在 env 中写死，禁止漂移」；实读 `:240-245` 打印步骤仅 `rustc --version` / `cargo --version` / `java -version`，**无比对断言**；`ls crypto/src/main/rust/` 确认**无** `rust-toolchain.toml`（故 workflow 字面量即 CI 唯一 Rust 固定点）；另两份 workflow（`codeql.yml` / `dependency-scan.yml`）不装 Rust。
- **背景**：同 env 块内 `ANDROID_NDK_VERSION` / `CARGO_NDK_VERSION` / `CARGO_DENY_VERSION` 均经 `run:` 内 shell 展开真实消费，唯 `RUST_TOOLCHAIN` 是装饰物；而 `:18` 与 `:236` 两处注释都在向维护者**承诺一个不存在的控制点**，`:236` 甚至把「人工保持一致」写成义务却不设机检。危害方向：未来升级 Rust 时三处注释都会引导改 env ⇒ CI 静默继续安装旧版本（「可审计」打印步骤只打印、无断言报红），形成**无声 no-op 升级**；且 3 处字面量互无机检，部分升级漏改会造成 job 间工具链分叉并全绿。`:236` 给出的理由「`with:` 不适于引用 workflow env」经 GitHub 官方 contexts 文档证伪（`jobs.<job_id>.steps.with` 可用上下文含 `env`）。
  - **触发状态：dormant**——当前 env 值与 3 处字面量同为 `1.97.1`、零漂移；git `-S` 全史显示 env 与字面量同笔引入、env 从未被消费亦从未被单独改动，「改 env 期望生效而被静默无视」的实机事件从未发生。
  - **未核实项**：`${{ env.RUST_TOOLCHAIN }}` 在 dtolnay action 下的可用性依据官方 contexts 文档**可用性表**（报告方 WebFetch 实读），**未实跑 GitHub Actions runner 验证解析**。
  - 登记表核查：两表 grep 零命中 ⇒ 不属已接受项。
- **涉及文件**：`.github/workflows/build.yml:18` / `:50` / `:59` / `:236-245` / `:474` / `:552`。
- **验收标准**：
  ① 二选一并使代码与注释同向——**接线**（`toolchain: ${{ env.RUST_TOOLCHAIN }}` 替换 3 处字面量）或**承认字面量为唯一事实源**（删 env 与 `:18` / `:236` 的失真注释）；本仓 `dependency-scan.yml:19-20` 已有「此处不复述条目数」的正确先例可仿；
  ② 若保留 env，则把 `:240-245` 的打印步骤升格为**断言**（`rustc --version | grep -q "$RUST_TOOLCHAIN"`），使漂移能变红；
  ③ 门禁 9/9 PASS。

## 451.2 前提复核（2026-10-06 直读）

- **`ISSUE-P2-498` 前提全部成立**：`SettingsUiState.kt` 的 `appVersion` / `buildNumber` 确为两个静态虚构字面量；`SettingsUiStateProjection.kt` 的 `buildSettingsUiState(...)` 构造 `SettingsUiState` 时**未**为这两字段赋值（仅逐字段映射其余项），首帧 `initialValue = SettingsUiState(appLanguage = ...)` 亦走默认值 ⇒ 真实来源从未接线；`app/build.gradle.kts` 确认真实 `versionName = "0.1.0"` / `versionCode = 1`；`app/build.gradle.kts` 的 `buildFeatures { buildConfig = true }` 已启用 ⇒ `BuildConfig.VERSION_NAME` / `VERSION_CODE` 可直用。
- **`ISSUE-P2-502` 前提复核并微调**：报告称「零消费点」，直读确认 `RUST_TOOLCHAIN` 除 env 定义与一处注释外无任何消费；三处真实钉版字面量位于三个 job 的 `dtolnay/rust-toolchain` 步骤（native-gate / rust-supply-chain / device-gate），行号由报告快照 `:237/:474/:552` 微调为整改时实读值（`:241/:483/:562` 一带）。**方向取 AC 推荐项 ①（接线 + 漂移断言）**，未走「删 env、承认字面量为唯一源」的备选。

## 451.3 整改

### `ISSUE-P2-498`：版本文本的单一真相源接线

- **新增** `app/src/main/java/com/keepasskey/app/ui/screens/settings/AppVersionInfo.kt`：`internal object AppVersionInfo`，两值直接取自 AGP 生成的 `BuildConfig`——`versionLabel = "v${BuildConfig.VERSION_NAME}"`（形如 `v0.1.0`）、`buildLabel = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"`（形如 `Build 0.1.0 (1)`），格式维持「`v` 前缀 + 构建行」的既有观感。
- **改** `SettingsUiState.kt`：`appVersion` / `buildNumber` 的**默认值**由静态虚构字面量改为 `AppVersionInfo.versionLabel` / `AppVersionInfo.buildLabel` ⇒ 首帧 `initialValue` 与预览路径同时得到真实读数，不再有任何宣称是版本却为虚构的默认值。
- **改** `SettingsUiStateProjection.kt`：`buildSettingsUiState(...)` 构造处补上第 9 节映射（`appVersion = AppVersionInfo.versionLabel` / `buildNumber = AppVersionInfo.buildLabel`），使投影链具备**显式赋值点**（原投影对该两字段零赋值）。
- 渲染位（`SettingsGroups.kt:274` / `AboutSettingsScreen.kt:56-57` / `AboutSettingsScreenSections.kt`）**逐字未改**——它们读的仍是 `SettingsUiState` 的同名字段，接线后自动展示真实版本。

### `ISSUE-P2-502`：env 接线 + 漂移断言

- **改** `.github/workflows/build.yml`：三处 `toolchain: "1.97.1"` 字面量**全部**改 `toolchain: ${{ env.RUST_TOOLCHAIN }}`（`steps.with` 支持 `env` 上下文）⇒ `env.RUST_TOOLCHAIN` 成为唯一事实源，改 env 即真实生效。
- **升格断言**：原「打印实际工具链版本（可审计）」步骤改名「校验实际工具链版本与 env.RUST_TOOLCHAIN 一致（漂移即红）」，在打印之外追加 `rustc --version | grep -q "$RUST_TOOLCHAIN" || { echo "::error::…"; exit 1; }`——env 值与安装工具链之间的漂移由「无声」变「报红」。
- **修失真注释**：头部 `:18` 段注明 env 已经 `with.toolchain` 展开并由断言守护；原 `:236` 的「`with:` 不适于引用 workflow env，故此处为字面量」注释删除（该判断经官方 contexts 文档证伪）。

## 451.4 验证

- **新增宿主守卫测试** `app/src/test/java/com/keepasskey/app/ui/screens/settings/AppVersionWiringTest.kt`（4 例）：
  ① 投影版本号 == `"v" + BuildConfig.VERSION_NAME` 且含真实 `VERSION_NAME`；
  ② 投影构建号 == `Build <VERSION_NAME> (<VERSION_CODE>)` 且含真实 `VERSION_CODE`；
  ③ 首帧 `initialValue` 路径（`SettingsUiState()` 默认值）与投影同源；
  ④ 主源码剔除注释后不得再残留旧字面量 `v1.0.0-Preview (2026 Edition)` / `Build 2026.09.04`。
  该断言实参含 `BuildConfig.*` / 成员访问，带真实生产依赖，**非**「字面量对字面量」的重言断言。
- **全量单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL`（4m 23s / 114 tasks executed）；
  `python tools/doc/count_test_results.py` → `xml=520 tests=3380 failures=0 errors=0 skipped=13`（§450 基线 `3376` ⇒ **+4**，即本批新增 4 例；`skipped=13` 为既有 `Assume` 通道）。
- **重言断言机检**：`python tools/audit/check_tautological_assertions.py` → `汇总：命中 0 处 / 扫描 578 个测试文件`（§450 为 577，+1 即本批新测试文件），退出码 0。
- **workflow 语法反校**：`python -c "import yaml; yaml.safe_load(open('.github/workflows/build.yml'))"` → `YAML OK`。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/9] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[2/9] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/9] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/9] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 449 份；分册登记 451 条；全量索引 451 条；最大 §451）
[5/9] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 578 个测试文件
[6/9] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/9] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/9] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[9/9] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
=== 汇总：9/9 PASS ===
```

## 451.5 如实声明

- **`ISSUE-P2-502` 未在真实 GitHub Actions runner 上实跑**：`${{ env.RUST_TOOLCHAIN }}` 在 `steps.with` 的可用性依据 GitHub 官方 contexts 可用性表（本仓条例亦据此），本批只做 YAML 语法反校与本地静态核对，**未触发 workflow**；该 CI 面须由下次 CI 运行确认（与条目「未核实项」一致）。
- **`ISSUE-P2-498` 未取得装机走查回执**：版本号渲染位逻辑未变（仅数据来源接线），预览与投影读数已由宿主用例锁定；真机视觉回执非本批 AC 要求。
- **环境留痕（如实）**：本机首跑 `test` 在 `:crypto:cargoHostBuild` 处**构建失败**（非测试失败）——`~/.cargo/config.toml`（OctoWarp 生成）指向的 `https://mirrors.ustc.edu.cn/crates.io-index/` **git 索引端点 HTTP 404**，`cargo` 无法解析依赖（与代码零关联）。按 §489 先跑 `python tools/doc/preserve_test_failures.py` → `failures=0`（确认无失利用例，首轮红证据为空）。为完成验证，临时在 `crypto/src/main/rust/.cargo/config.toml` 以 **sparse** 形态同源镜像覆盖（先 `cargo fetch --locked` 验证解析可行），跑毕**立即删除**、**未入库**（`git status` 可核）。
- **测试资产口径**：新增 4 例、删除 0 例，净覆盖 +4（符合「只增不删」）。
- 本批**未触**产品代码的敏感数据面、`crypto/src/main/rust/**` 的算法实现、JNI 与原生分派与探活；无新增 `*Manager` / `*Util` / `*Helper` / `*Common` 类型（`AppVersionInfo` 不含受限后缀）。
