# §493 HIBP 响应体尺寸封顶批次（2026-10-11）

**条目**：`ISSUE-P3-578` **整条闭环**（P3 9 → **8**）
**来源**：数据流安全审查（2026-10-11）发现 6，独立复核 verdict=confirmed；登记于 `ISSUE-P3-578`
（核实基准提交 `cd223701`）。六要素见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md)。

## 493.1 原始条目（原样收录）

### `ISSUE-P3-578`：HIBP 泄露库查询响应体无界物化，与 sync 侧已整改的「无界读取」同型未对齐

**背景与影响**：`HibpRangeClient.kt:48-49,53` 对服务端响应体 `response.body.string()` **整体物化为 String、
无尺寸封顶**（`BreachCheckModule.kt:36-43` 的客户端无响应体上限、未定制信任锚）——与 sync 侧 `ISSUE-P0-09`
已整改面同型（sync 以 `SyncDownloadLimits` 双重封顶，此出口未对齐）。触发前置：须先取得设备系统信任的 CA
（纯网络 MitM 因信任锚仅系统 CA 不可达），影响为进程 OOM；缓解因素 `callTimeout 30s` 已由复核确认（佐证 low）。

**验收标准**：响应体读取按 sync 侧同型口径做尺寸封顶（申报上限 + 读后兜底）；单测锁定。

**核实时间点 + 核实方式**：2026-10-11，数据流安全审查工作流（同 P2-573 口径），基准提交 `cd223701`；六要素详情见
[`security/数据流安全审查_2026-10-11.md`](../../security/数据流安全审查_2026-10-11.md) 发现 6（verdict=confirmed）。
来源攻击面：网络与 TLS。

## 493.2 前提复核（2026-10-11 直读）

- **条目成立（快照一致，零漂移）**：直读 `HibpRangeClient.kt` 原状——`:48` 即
  `httpClient.newCall(request).execute().use { response ->`；`:49` 即 `val body = response.body.string()`
  （**无任何尺寸判定**、整体物化为 String）；`:53` 即 `parseRangeBody(body)`。`BreachCheckModule.kt:36-43`
  即 `provideBreachHttpClient` 只配 `connectionSpecs`（RESTRICTED/MODERN TLS）与三处超时（10s/20s/30s），
  **无响应体上限**、未定制信任锚。条目所引行号与语义逐条吻合。
- **sync 侧口径来源**：`sync/.../network/SyncDownloadLimits.kt`——`copyBounded` 先做声明尺寸预检
  （`declaredLength > maxBytes` **未读任何字节即拒**）、再做流式累计封顶（`total > maxBytes` 中途拒），
  堆占用恒为 64 KiB 常量缓冲；`readBounded` 是同口径的「整体物化」变体（仅供 16 MiB 的元数据通道）。
  **异常类型不同**：两者均抛 `SyncException.ProtocolError`，而 app 侧出口须抛 `BreachCheckException`
  ⇒ 按条目「最小修复建议」的提示，**不跨模块复用**该 API，改为语义**等价实现**后转抛本模块异常。
- **测试基建口径**：既有 `HibpRangeClientTest`（8 例）以 `MockWebServer` 直连 `HibpRangeClient`，
  已在宿主 JVM 可复现整条网络路径 ⇒ 本批沿用其范式加例（另有 `BreachCheckModuleTest` /
  `BreachCheckCoordinatorTest` / `BreachCheckHealthTest` 等，全不触及、逐字未动）。
- **远端 API 面核对**：`okhttp3.mockwebserver.MockResponse.setChunkedBody(String, Int)` 在 OkHttp 5.5.0
  的 compatibility shim 中确存（`removeHeader("Content-Length")` + `Transfer-Encoding: chunked`），
  可稳定构造「声明缺失」形态以触发读后兜底。

## 493.3 整改

### ① 上限常量：`HibpRangeClient.MAX_RESPONSE_BYTES = 1 MiB`

`companion object` 新增 `internal const val MAX_RESPONSE_BYTES: Long = 1L * 1024 * 1024`（KDoc 载取值依据：
HIBP v3 Add-Padding 把响应对齐到 800–1000 条 `<35 位后缀>:<次数>` 记录 ≈ 40–50 KiB，合法响应远小于 100 KiB
⇒ 上限留约 10 倍余量，同时把「TLS 对端单方面灌大响应」的物化峰值约束在 MiB 量级）。与 sync 侧同型但
**按本出口合法响应量级单独定值**（不跨模块共用，因异常类型与量级均不同）。另新增
`internal const val UNKNOWN_LENGTH: Long = -1L`（OkHttp `contentLength()` 的未知标记，替代裸字面量）；
块缓冲 `private const val BUFFER_SIZE = 64 * 1024`（与 sync 侧同量级）。

### ② 判定纯函数：`exceedsRangeBodyLimit(bytes: Long?, maxBytes: Long = MAX_RESPONSE_BYTES)`

唯一一条判据 `bytes != null && bytes != UNKNOWN_LENGTH && bytes > maxBytes`——纯函数、无 Android / IO 依赖，
供纯 JVM 单测直测。**同一函数同口径服务两道闸门**：闸门①传**申报长度**（`Content-Length`，`null` / `-1`
视为未知 ⇒ 放行给读后兜底）；闸门②传**已读累计长度**（恒非负、恒非 null）。判据为**严格大于**（恰等于上限放行）。

### ③ `queryRange` 双闸门 + 有界读取（AC 主体验收点）

原 `response.body.string()` 整体物化替换为：

- **闸门①（申报预检，读前）**：`response.body.contentLength()` 为 `Content-Length` 声明值，超限即抛
  `BreachCheckException`（文案携声明值与上限），**不读体**；
- **闸门②（读后兜底）**：新增 `private fun readBounded(body: ResponseBody)`，以 64 KiB 固定缓冲
  `body.byteStream().use { ... }` 循环读取，**累计越界立即抛** `BreachCheckException`——声明缺失 / chunked /
  声明值撒谎三种形态一律在读取中途被拒，堆占用恒被上限约束；读毕以 `Charsets.UTF_8` 解码（HIBP 响应为
  ASCII 文本）再交既有 `parseRangeBody`。
- **判定顺序反转（改进）**：原实现先 `body.string()` 读体、**再**判 `isSuccessful`；本批改为**先判状态码**
  再入闸门——非 2xx 的响应体现在**不再被读取**（错误体也属对端可控内容），语义与既有失败文案逐字保留。

### ④ KDoc 同步

类 KDoc 新增「响应体封顶」段：载明威胁面（TLS 对端为唯一可单方面灌大响应者）、两道闸门语义、
正常响应量级与上限余量，并**明示不复用** sync 的 `SyncDownloadLimits.readBounded`（异常类型不同）——
按其语义等价实现后统一转抛 `BreachCheckException`。

### ⑤ 测试资产

**修改** `app/src/test/java/com/keepasskey/app/data/breach/HibpRangeClientTest.kt`（**新增 8 例**，
既有 8 例逐字保留、删 0 例）：

1. 申报长度超上限即判越界（含「4 MiB 必拦 / 100 KiB 必放行」的生产量级判据）；
2. 申报长度未超上限与**恰好等于上限**均放行（边界＝严格大于）；
3. 长度未知（`null` 与 `-1`）不拦、交读后兜底（并锁 `UNKNOWN_LENGTH == -1L`）；
4. 读后累计长度超上限即判越界（闸门②同口径）；
5. 生产上限为 1 MiB 且远大于合法响应量级；
6. **端到端**：声明 `Content-Length` 超上限 ⇒ 抛 `BreachCheckException`，文案含「声明」（证明拦在闸门①）；
7. **端到端**：`setChunkedBody`（无 `Content-Length`）超上限 ⇒ 抛 `BreachCheckException`，文案**不含**「声明」
   （证明拦在闸门②读后兜底）；
8. **源码接线守卫**：`HibpRangeClient.kt` 内**申报预检先于有界读取**（顺序反转即「先读爆内存再判超限」）、
   `byteStream()` / `readBounded(body)` / `exceedsRangeBodyLimit(total)` 接线齐备，且**不得再出现**
   `body.string()` 无界物化——「超限不读体 / 读后封顶」由此锁定。

## 493.4 验证

### 宿主

- **定向单测**（`--tests "com.keepasskey.app.data.breach.HibpRangeClientTest"`）→ `BUILD SUCCESSFUL`；
  按 §201 立规**回读 XML 确认在册**：
  `TEST-com.keepasskey.app.data.breach.HibpRangeClientTest.xml` `tests=16 skipped=0 failures=0 errors=0`
  （既有 8 + 新增 8；16 例逐条列名核对在册，无静默零匹配）。
- **全量单测**：`.\gradlew.bat test --max-workers=1` → `BUILD SUCCESSFUL`（1m 21s / 114 tasks：2 executed、112 up-to-date）。
  `python tools/doc/preserve_test_failures.py` → `failures=0`（无首轮红，无需留痕）。
  `python tools/doc/count_test_results.py` → `xml=553 tests=3612 failures=0 errors=0 skipped=15`
  （§492 基线 `xml=553 tests=3604` ⇒ **+0 XML / +8 例**，全为既有 `HibpRangeClientTest` 文件内的新增用例）。
- **门禁读数**（`python tools/doc/gate_readings.py` 原样粘贴）：

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/16] tools/doc/check_gate_script_integrity.py     EXIT 0  | [check_gate_script_integrity] 清单 19 条 / 期望 19 条；命中 0 处  [check_gate_script_integrity] PASS：门禁脚本集合 19 条与 SHA-256 基线清单逐条一致
[2/16] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=35  budget=35
[3/16] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[4/16] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[5/16] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 490 份；分册登记 492 条；全量索引 492 条；最大 §493）
[6/16] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 611 个测试文件
[7/16] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[8/16] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[9/16] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  多发射助手函数（顶层发射 ≥2 节点的 @Composable）：62 个  检查过的调用点：BoxScope 组件=61  框架 supportingText 槽=8  box_slot_stacked_sites=0
[10/16] tools/audit/check_launch_language_seed.py    EXIT 0  | === 启动语言种子接线机检（五条 fail-closed）===  [E1] MainApplication.onCreate 灌启动种子	ok  [E2] Compose 首帧 initialValue 取种子	ok  [E3] AppLocaleTracker.init 取种子（StringsProvider 首帧通道）	ok  [E5] AppLocaleTracker 慢路径补种（SP 无历史镜像时的兜底）	ok  [E4] RealSettingsRepository.setAppLanguage 镜像种子	ok  --- launch_language_seed=OK（五条接线齐全）---
[11/16] tools/doc/check_projection_read_safety.py    EXIT 0  | [check_projection_read_safety] 登记面文件 13/13；展示读口文件 16 个（白名单 16）；命中 0 处  [check_projection_read_safety] PASS
[12/16] tools/doc/check_raw_coroutine_scope.py       EXIT 0  | [check_raw_coroutine_scope] 检查文件 739 个（*/src/main/**）；允许清单 1 个；命中 0 处  [check_raw_coroutine_scope] PASS
[13/16] tools/doc/check_archive_monotonicity.py      EXIT 0  | [check_archive_monotonicity] 基线 HEAD~1：索引行 490 个 / 批次正文 488 份；现树 工作区：492 / 490；缺失 0 处  [check_archive_monotonicity] PASS
[14/16] tools/doc/check_plaintext_carrier_to_string.py EXIT 0  | [check_plaintext_carrier_to_string] 检查文件 739 个（*/src/main/**）；允许清单 0 个；命中 0 处  [check_plaintext_carrier_to_string] PASS
[15/16] tools/doc/check_message_not_in_user_text.py  EXIT 0  | [check_message_not_in_user_text] 检查文件 739 个（*/src/main/**）；文案槽 10 类；命中 0 处  [check_message_not_in_user_text] PASS
[16/16] tools/doc/check_cancellation_semantics.py    EXIT 0  | [check_cancellation_semantics] 检查文件 739 个（*/src/main/**）；命中 0 处  [check_cancellation_semantics] PASS
=== 汇总：16/16 PASS ===
```

### 设备侧

- `*/src/main/rust/**`、`jni_bridge_ext.rs` 与任一 `Native*` 绑定**未改动**；`*/src/androidTest/**`
  **未新增或修改** ⇒ 依测试资产纪律无需真机实跑；且按本批任务绝对禁令**禁止运行设备侧 / 连接测试**
  （设备存真实用户库，UTP 会卸载应用致数据永久丢失）。本批全部改动落在
  `app/src/main/java/com/keepasskey/app/data/breach/HibpRangeClient.kt` 与
  `app/src/test/java/com/keepasskey/app/data/breach/HibpRangeClientTest.kt`（外加本批文档流转）。

## 493.5 如实声明

- **上限取 1 MiB（报告建议区间 1–4 MiB 的下界）**：报告明言「正常 padded 响应 <100 KiB」，取 1 MiB 即留
  约 10 倍余量，同时把物化峰值压到最小。**残余**：若某前缀的**合法**响应真实超过 1 MiB（SHA-1 前缀均匀分布下
  现实概率极低），会被拒并如实上浮 `BreachCheckStatus.FAILED`（**非**静默回落为「未泄露」）——属 fail-closed
  的有意收敛，用户可见「检测失败」而非伪造安全结论。若日后需要放宽，改 `MAX_RESPONSE_BYTES` 一处即可
  （判定与文案均从该常量取值，无第二处字面量）。
- **未跨模块复用 `SyncDownloadLimits.readBounded`**：报告已点明直接复用会抛 sync 侧
  `SyncException.ProtocolError`。app 侧若捕获再转换，会引入「为异常翻译而依赖 sync 内部异常类型」的耦合，
  且 `readBounded` 是「整体物化」变体（其 KDoc 自述仅适合小体量元数据通道）。本批改为按**同一语义等价实现**
  有界流式读取，异常类型自始为 `BreachCheckException`——口径对齐而类型不耦合。
- **未采用 OkHttp 拦截器方案**：亦可加 `Interceptor` 在读流前按 `Content-Length` 拦截。未采纳理由：
  ① 需改 `BreachCheckModule` 的客户端装配（扩大改动面到 DI 装配，且会与 `BreachCheckModuleTest` 的
  `connectionSpecs` 断言面相邻）；② 拦截器对 chunked 仍须自己实现累计封顶，逻辑并不更少；
  ③ 判定内聚在客户端本类更易被单测直接锁定（本批纯函数 + 端到端 + 源码守卫三层）。
- **行为改进已声明**：原实现「先读体后判状态码」，本批改为「先判状态码后读体」——非 2xx 的错误响应体
  不再被物化。此为**顺带收紧**（错误体同样是对端可控内容），失败文案与既有 8 例的断言逐字保留。
- **未改面**：`BreachCheckModule`（客户端装配与三处超时）、`BreachCheckCoordinator`、`BreachCheckModels`
  的 `BreachCheckException` 定义、`parseRangeBody` 的解析与 Add-Padding 语义、既有 8 例断言、`DEFAULT_BASE_URL`
  与请求头构造——全部逐字未动。本批仅动 `HibpRangeClient.queryRange` 的响应体读取路径与其新增判定/常量。
- **设备侧缺口**：TLS 对端灌超大响应（真实网络形态）**未获真机证据**（按任务禁令未跑设备侧）；判定与接线
  由纯函数 + MockWebServer 端到端 + 源码静态守卫三层锁定。真机可视回归如需补做，应在 AVD（非用户真机）上执行。
