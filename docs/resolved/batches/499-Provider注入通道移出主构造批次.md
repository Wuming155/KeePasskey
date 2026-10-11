# §499 Provider 注入通道移出 public 主构造批次（2026-10-11）

**条目**：`ISSUE-P3-584` **整条闭环**（P3 3 → **2**）
**来源**：2026-10-11 数据流安全审查发现 12（verdict=confirmed），六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md)「### 12.」。

## 499.1 原始条目（原样收录）

### `ISSUE-P3-584`：WebDAV/S3 Provider 的 `@VisibleForTesting` client 注入参数位于 public 主构造签名，单行传参即整体旁路网络防线

**背景与影响**：`WebDavSyncProvider.kt:56-60,73-92` 与 `S3SyncProvider.kt:77-78,94-117` 的
`@VisibleForTesting` client 注入参数位于 **public 主构造签名**上（`private val` 修饰的仅是属性而非参数）：
一旦任何生产构造点按名传 `client`，`client==null` 分支整体跳过——TLS-only 连接规格、SSRF 连接期两层守卫
（`SsrfGuardDns`/`SsrfGuardSocketFactory`）与构造期 HTTPS 强制全部失效，Authorization/SigV4 头注入攻击者指定客户端
——绕过网络防线的最短单行改动路径。当前 3 个生产构造点 6 处（`SyncProviderResolver.kt:45,70`；
`CloudVaultImporter.kt:251,265`；`RemoteBrowseController.kt:81,114`）逐一读实均未传参，运行期不可达，
属结构性回归面（`ISSUE-P3-56` 子项 3 的整改未关闭调用点传参路径）。

**验收标准**：① 注入通道移出 public 主构造签名（internal 工厂 / secondary constructor / 单独测试构造），
或以机检锁定生产源集零传参；② lint 对构造参数上 `@VisibleForTesting` 的拦截行为核实并留痕。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md) 发现 12（verdict=confirmed）。
来源攻击面：网络与 TLS。

## 499.2 前提复核（2026-10-11 直读，基准提交 `cd223701`）

- **条目逐条成立**：`WebDavSyncProvider.kt:41-61`（原 `@VisibleForTesting private val client: OkHttpClient? = null`
  位于 public 主构造末尾）与 `S3SyncProvider.kt:41-79`（同型）——`private` 只修饰**属性**，`client` 仍是 public
  主构造的**形参**，跨文件按名传参即可编译，与描述逐字吻合。`init` 中 `if (client == null)` 门控
  （WebDav `:74-92` = https 强制 + `SyncEndpointGuard.validateEndpointHost` + `SyncHttpClientFactory.createSyncClient`；
  S3 `:98-116` 同构，桶名校验 `:97` 恒在门控外）逐行读实。
- **生产构造点 6 处零传参（核实无漂移）**：`SyncProviderResolver.kt:45,70`、`CloudVaultImporter.kt:269,283`、
  `RemoteBrowseController.kt:81,114`（行号为 §499 直读时刻快照，条目取的是更早的 `251/265` 等，属**行号漂移**
  而非形态漂移）逐一读实**均未传 `client`**；运行期不可达。
- **测试注入点全在 sync 模块**（关键形态判定）：`sync/src/test/**`（`LiveSyncServersTest`、`WebDavSyncProviderTest`、
  `S3SyncProviderTest`、`WebDavSyncScenarioTest`、`S3SyncScenarioTest`、`WebDavConflictProbeFailureTest`、
  `WebDavOverwriteFallbackGuardTest`）与 `sync/src/androidTest/**`（`WebDavTransferDeviceTest.kt:53`、
  `S3TransferDeviceTest.kt:46`）均为 `client = …` **具名实参**；androidTest 两处在 **sync 模块内** ⇒
  `internal` 构造对两处均可见（同模块 friend 编译单元），**(a) 方案对 androidTest 透明、无需改动**。
- **结论**：条目成立，选 **(a) 把注入通道移出 public 主构造签名**（题面「优先 (a)」）。

## 499.3 整改

### ① 主构造改 `private` + `client` 置于首位（区别于二级构造形参）

两 Provider 的主构造改为 `private constructor`，并把 `@VisibleForTesting private val client` 从**末尾**移到**首位**：
唯一目的是**消除委托歧义**——若 `client` 仍居末尾，则「private 主构造」与「internal 二级构造」的形参列表除末位
可空性外**完全相同**，`this(client, …)` 之类的委托会解析到二级构造自身，Kotlin 编译器直接报
`There's a cycle in the delegation calls chain`（本批首版即实测命中）。`client` 置于首位后其类型（`OkHttpClient?`）
与二级构造首参（`String`）**不同**，委托唯一解析到 private 主构造，歧义消除。

### ② 生产入口：**public 二级构造（无 `client` 形参）**

```kotlin
constructor(serverUrl: String, username: String, passwordChars: CharArray,
    networkOptions: SyncNetworkOptions = SyncNetworkOptions(),
    transferOptions: SyncTransferOptions = SyncTransferOptions.DISABLED
) : this(null, serverUrl, username, passwordChars, networkOptions, transferOptions)
```

3 个生产构造点 6 处**源码零改动**（它们本就是无 `client` 的具名/位置实参调用，自然落到此 public 二级构造）。

### ③ 测试入口：**`internal` 二级构造（携 `client`，仅同模块可见）**

```kotlin
@VisibleForTesting
internal constructor(…, client: OkHttpClient
) : this(client, …)
```

`internal` 对 sync 的 `src/test` / `src/androidTest` 可见、对 `app` 等下游模块**不可见**。故 androidTest 两处与
`src/test` 全部注入点**源码零改动**（`client = …` 具名实参照旧解析到本构造）。S3 同型（`client` 首位 + 两个二级构造）。

### ④ 未动面

`init` 门控逻辑、`SsrfGuard*`、`SyncHttpClientFactory`、`SyncEndpointGuard`、`WebDavAuthHeader` / `S3RequestSigner`
及两 Provider 其余全部方法逐字未动；本批仅改**构造签名形态**（可见性 + 形参次序 + 二级构造），零行为变更。

## 499.4 验证

### 宿主

- **编译门禁**：`:sync:compileDebugKotlin`、`:sync:compileDebugUnitTestKotlin`、`:sync:compileDebugAndroidTestKotlin`、
  `:app:compileDebugKotlin` 全部 `BUILD SUCCESSFUL` —— 同时证明 ① 3 个生产构造点仍编译（走 public 二级构造）；
  ② androidTest 两处注入点仍编译（走 internal 二级构造，**androidTest 源码零改动**）。
- **AC① 负向编译对照（最强证据）**：临时于 `app/src/main` 复刻「单行旁路」——
  `WebDavSyncProvider(…, client = OkHttpClient())` / `S3SyncProvider(…, client = OkHttpClient())`，实测**编译失败**：
  `Cannot access 'constructor(…, client: OkHttpClient): WebDavSyncProvider': it is internal in
  'com.keepasskey.sync.webdav.WebDavSyncProvider'`（S3 同型）。即整改前「一行改参即旁路」的可编译路径，
  整改后变为**编译错误**；对照文件已删除，工作区仅留两处生产文件改动。
- **AC② lint 实测（`:sync:lintDebug` + 临时探针，探针跑完即删）**：
  1. 整改后对两 Provider 跑 `:sync:lintDebug` → `No issues found.`（`<issue>` 计数 0）；
  2. 探针实测 lint 对三种形态的判定：`@VisibleForTesting private val`（与 Provider 现形态一致的构造属性）→
     **不报**；`@VisibleForTesting` 普通构造参数（非属性）→ **不报**；`@VisibleForTesting val`（**可见 getter** 的
     构造属性）→ 报 `SupportAnnotationUsage`（`severity=Error`：「Did you mean @get:VisibleForTesting?」，
     且该 error 使 `:sync:lintDebug` **BUILD FAILED**）。
  3. **跨模块用法探针**：复刻原形态（public 类 + `@VisibleForTesting private val` 主构造参数）由 `app/src/main`
     按名传参，跑 `:app:lintDebug` → 报告中该探针**零命中**（现有 13 处 `VisibleForTests` 命中均为 app 内既有的
     方法/属性 getter 访问，与探针无关）⇒ lint 的 `VisibleForTests` 检测器**不拦构造形参访问**。
  - **结论（AC②）**：lint 对本条所述旁路面**无拦截力**——不报 `private val` / 普通构造参数上的
    `@VisibleForTesting`，也不拦跨模块生产源集按名传该构造参数；仅当注解放到**可见 getter** 的 `val` 上时
    才以 `SupportAnnotationUsage` 报 Error，但那是另一件事（属注解放错位置），与原旁路无关。故**结构移除
    (a) 是必要而非冗余**；原条目「以 lint 基线登记为 error」的建议**不具备可行判据**（不改动就报不出来）。
- **定向单测**（`:sync:testDebugUnitTest`）→ `BUILD SUCCESSFUL`；按 §201 立规**回读 XML 确认在册**：
  `WebDavSyncProviderTest tests=18 / S3SyncProviderTest tests=20 / WebDavSyncScenarioTest tests=32 /
  S3SyncScenarioTest tests=15 / LiveSyncServersTest tests=14`（后者 `skipped=14`，为既有 `LIVE_SYNC_TEST`
  Assume 门控，非本批引入），全部 `failures=0 errors=0`。
- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（2m 26s / 114 tasks：5 executed、109 up-to-date）。
  `python tools/doc/preserve_test_failures.py` → `failures=0`（无首轮红，无需留痕）。
  `python tools/doc/count_test_results.py` → `xml=558 tests=3636 failures=0 errors=0 skipped=15`
  （§498 基线 `xml=558 tests=3636` ⇒ **±0**：本批纯构造签名形态改动，**零新增/删除用例**）。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 496 份；分册登记 498 条；全量索引 498 条；最大 §499）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 616 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 742 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 496 个 / 批次正文 494 份；现树 工作区：498 / 496；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 742 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 742 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 742 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```

> 说明：`count_line_tiers` 本批一度因 S3 主构造 KDoc 撑到 **509 行**（越 `tier1(>500)` 闸门，实测 EXIT 1）——
> 属**机检真实拦下规模回归**；随后压缩本批新增 KDoc 使 S3 回到 **491 行**、WebDav **433 行**（两者原本即在
> `tier2` 档内），闸门复归 `tier1=0 / tier2=35 == budget`（与 §487/§498 基线一致，未动 `TIER2_BUDGET`）。

### 设备侧

- `sync/src/androidTest/**` **未改动**（两处注入点走 `internal` 二级构造，源码零改动）⇒ 依测试资产纪律无需真机实跑；
  本环境按任务约束**禁跑** `connected*`，故未执行设备侧。改动仅在 `sync/src/main/**` 的两处构造签名（Kotlin 编译期
  可见性），无平台 API / 正则 / XML 参与，设备侧无额外可证面。

## 499.5 如实声明

- **选了 (a)，未选 (b)**（题面「优先 (a)」）：把注入通道**真正移出** public 主构造签名，比「改名 + 机检锁生产源集零传参」
  的 grep 锁更强——对 `app` 的三个生产构造点是**编译期**而非文本级约束（AC① 负向对照实证）。
  (b) 的机检还需接入 `hygiene-gate`，而该 job 有 `check_gate_script_integrity.py` + `gate_scripts.sha256`
  逐脚本 SHA-256 基线，新增脚本须同时改基线清单与 `build.yml`（并重跑门禁读数），面更大而保证更弱，故未采纳。
- **残余（如实登记，非阻塞）**：`internal` 对**同模块** `sync/src/main` 自身仍可见——恶意 PR 若**直接改写
  `sync/src/main` 内的某个文件**，理论上仍可在 module 内调用 internal 二级构造并传 `client`。但这**不是**
  条目所指的「生产构造点单行传参」（那 3 个点在 `app`，已被编译期封死），且需在 Provider 同模块内新增调用语句、
  改动显著大于「构造点加一行实参」，故未另设机检锁定 `sync/src/main` 自调用面。如需进一步收口，可另立条目评估
  「机检 + 门禁完整性基线」路线（(b)）。
- **未改 androidTest**：两处注入点零改动、`compileDebugAndroidTestKotlin` 通过；**但编译通过不等于真机可用**，
  按测试资产纪律其「未真机实跑」的状态如实声明（本环境禁跑设备）。
- **lint 结论以实测为准**：见 499.4 AC② 三条探针；**未**把「lint 会拦」写成已证（实测为**不拦**）。
- **过程缺陷留痕**：首版把 `client` 留在主构造**末位**，触发 Kotlin
  `There's a cycle in the delegation calls chain`（private 主构造与 internal 二级构造形参列表除末位可空性外相同，
  委托解析到二级构造自身）；改为**首位置参**后消除——非生产缺陷，属构造重载歧义，记此以免后人回退到末位方案。
- **行数闸门一度红**：见 499.4 门禁说明；已在本批内修正，非跨批遗留。
