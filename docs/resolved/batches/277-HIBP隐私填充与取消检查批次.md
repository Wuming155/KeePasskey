<a id="s277"></a>

# §277 HIBP 隐私填充与取消检查批次

> `ISSUE-P3-294` **整条闭环**（P3 13 → **12 项**）。
> 触发＝2026-09-23 用户命题「解决P3-294」；开工前经官方 API v3 原文与源码现状复核确认前提成立。
> 覆盖面：`ISSUE-P3-294`（本体）。**未触**原生内核 / `*/src/androidTest/**` / `参考项目/` / 构建脚本。

---

## 1. 条目正文（原样收录）

### `ISSUE-P3-294`：HIBP 泄露检测未发 `Add-Padding`，批量查询循环内无取消检查

- **核实时间点**：2026-09-23 经官方 API v3 原文核实（`Add-Padding` 为**文档化建议**而非强制，故本条按 P3 记，不作协议缺陷）。
- **核实方式**：`app/.../data/breach/HibpRangeClient.kt:36-40` 仅带 User-Agent，未发 `Add-Padding: true`（官方语义：把响应对齐到 800–1000 条记录量级，使能截获加密响应长度者无法据大小判断查了哪个前缀）；`BreachCheckCoordinator.kt:35-43` 对每个不同前缀串行一次请求、**循环内无取消检查**（`HibpRangeClient.kt:39` 的阻塞 `execute()` 在途不可中断，但请求**间隙**可检）。失败口径**已合格**：非 2xx 抛错（`:44-46`）、失败转 `FAILED` 且计数回填 null（`SettingsHealthController.kt:147-151`），超时已在 `BreachCheckModule.kt:24-26/38-43` 收口，触发需用户主动开启且有 `isHealthScanning` 互斥（`:196-213`）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/data/breach/HibpRangeClient.kt`、`app/src/main/java/com/keepasskey/app/data/breach/BreachCheckCoordinator.kt`。
- **验收标准**：AC① 补 `Add-Padding: true` 并按官方语义解析（忽略填充行不影响判定）；AC② 循环内加 `ensureActive()` / `isActive` 检查，禁千条目库的扫描在用户退出后继续外联；AC③ 前缀去重与请求数上限须在正文写明取值依据；AC④ 保持既有 fail-closed 口径（失败**不得**当作「未泄露」），用例只可加不可删。

**开工前前提复核**：成立。`HibpRangeClient` 请求构造仅 `User-Agent`；`BreachCheckCoordinator.check` 的 `for ((prefix, candidates) in …)` 循环内确无取消检查；失败口径四点（非 2xx 抛错 / 转 FAILED / 超时收口 / 开关门控 + 互斥）逐条与源码一致。行号以开工时实读为准（请求构造落在 `:36-41`，查询循环落在 `:35-43`）。

---

## 2. 整改

### 2.1 AC①：`Add-Padding: true` + 按官方语义解析

**生产改动（`HibpRangeClient.kt`）**：

1. 请求恒带 `Add-Padding: true`（常量 `HEADER_ADD_PADDING` / `ADD_PADDING_VALUE`），对齐官方 v3「Introducing padding」建议：响应对齐到 800–1000 条，密文长度不再泄露查询前缀。
2. `parseRangeBody` 丢弃 `count` 全 0 的行。官方原文：

   > Note: Padded entries always have a password count of 0 and can be discarded once received.

   判据用 `countToken.trimStart('0').isEmpty()`（覆盖 `"0"` / `"00"`），**不去 `toLong`**——大计数溢出会把真实泄露行误丢。丢弃填充行**不影响命中判定**：真实泄露行 `count ≥ 1`；后缀若恰与随机填充行重合（概率极低）按未泄露处理，比误报「已泄露」更符合「填充行不是泄露证据」的分界。

3. 类 KDoc 补记官方语义与丢弃口径（含溢出边界说明）。

### 2.2 AC②：请求间隙取消检查

**生产改动（`BreachCheckCoordinator.kt`）**：

- 查询循环每次迭代入口 `coroutineContext.ensureActive()`。`queryRange` 的在途阻塞 `execute()` 仍不可中断（条目已如实写明），但**间隙**可检——用户退出后不再发起后续外联，千条目库扫描不会在后台继续打 HIBP。

**连带收口（`SettingsHealthController.runBreachCheck`）**：

- 原 `catch (e: Exception)` 会把 `CancellationException`（`ensureActive` 的取消信号）转成 `FAILED`——把「用户退出」谎报成「查询失败」。现拆出 `catch (e: CancellationException) { throw e }` 先行分支，取消正常传播；`FAILED` 口径（AC④）不变。

### 2.3 AC③：前缀去重与请求数上限的取值依据（本节即 AC③ 所称「正文」）

| 项 | 取值 | 取值依据 |
|---|---|---|
| **前缀去重** | 按 SHA-1 前缀 `LinkedHashMap` 分组，同前缀仅 **1** 次网络请求（既有实现，本批不改语义） | k-匿名协议以 5 位前缀为查询粒度，相同前缀共享同一响应；N 条重复口令的外联压成 1 次 |
| **请求数上限** | **隐式上界** = 唯一前缀数 ≤ 非空口令条目数；**不设人工硬顶** | ① 扫描由用户开关显式触发、一次性；② 单请求 `callTimeout` 30s 已在 `BreachCheckModule` 收口；③ 本批补上请求间隙取消检查后用户可随时退出；④ HIBP Pwned Passwords range API 免费且官方无强制配额（RateLimiting 节针对需 key 的端点）。若日后要硬顶，属独立取舍、须另行裁决 |

同一表已写入 `BreachCheckCoordinator` 类 KDoc（防文档与代码漂移）。

### 2.4 AC④：fail-closed 口径保持 + 用例只加不删

- 失败口径四点**一行未改**：非 2xx 抛 `BreachCheckException`、失败转 `FAILED` 且计数回填 null、超时收口、开关门控 + `isHealthScanning` 互斥。
- 既有用例全部保留（`HibpRangeClientTest` 6 例 / `BreachCheckCoordinatorTest` 5 例 / `BreachCheckHealthTest` 3 例），只新增不删除。

---

## 3. 用例（只加不删，+3 例）

### `HibpRangeClientTest` +2

1. **恒发 `Add-Padding` 请求头**：MockWebServer 断言 `request.headers["Add-Padding"] == "true"`。
2. **填充行 count 为 0 时丢弃，不影响命中判定**：响应含真实行（count≥1）+ 三种 0 形态填充行（`"0"` / `"00"` 混排）→ 结果集只含真实后缀，三条填充后缀均不在内。

### `BreachCheckCoordinatorTest` +1

3. **请求间隙取消后不再发起后续外联**：三个不同口令（三个唯一前缀）的假客户端在**首个** `queryRange` 内 `job.cancel()`，断言 `queried.size == 1`（取消后第 2、3 个前缀不再外联）。

**既有失败路径用例未动**：非 2xx 抛错、查询失败向上传播、关闭态零外联、FAILED 如实上浮——fail-closed 口径由原用例继续锁定。

---

## 4. 验证

| 项 | 读数 |
|---|---|
| 定向 `:app:testDebugUnitTest --tests "com.keepasskey.app.data.breach.*" --rerun-tasks` | **BUILD SUCCESSFUL** |
| 全量 `test --rerun-tasks --max-workers=1` | **BUILD SUCCESSFUL**（2m 16s / 114 executed） |
| `count_test_results.py` | **`xml=374 tests=2589 failures=0 errors=0 skipped=13`** |
| 相对 §276 基线（374/2586） | **类数持平 / +3 例**（落既有两类，逐条可对） |
| `check_tautological_assertions.py` | **命中 0 处 / 扫描 435**（改测试断言后必跑） |
| `tier1` / `tier2` / `functions_ge_100` | 3 / 33 / 6，与 §276 持平（未触超阈文件与长函数） |
| `check_md_links.py` | `BROKEN_MD_LINKS=0` |
| `check_resolved_index_sync.py` | `RESOLVED_INDEX_SYNC=OK` |
| 未触 | `crypto/src/main/rust/**`、`*/src/androidTest/**`、`参考项目/`、构建脚本 ⇒ **无设备侧必跑项** |

---

## 5. 如实声明

1. **`Add-Padding` 为官方文档化建议、非强制**（条目核实时已如此定性），本批落地的是隐私增强，不构成协议互操作义务；对不发该头的第三方服务端，本仓解析仍兼容（无填充行时行为不变）。
2. **在途 `execute()` 仍不可中断**（条目已写明边界）：取消只在请求**间隙**生效；单请求 30s `callTimeout` 是唯一在途上限。不把 `OkHttp` 调用改成 `enqueue` 异步取消——变更面大且超出本条 AC。
3. **取消信号经 `CancellationException` 正常传播**，不转 `FAILED`；调用方（`SettingsHealthController.rescanHealth` 外层）仍 `catch (e: Exception)` 收尾置 `isHealthScanning = false`——该外层收尾对取消也会执行（副作用是用户可再次触发扫描），本批**刻意不改**（改它牵动扫描状态机，且与 AC②「禁继续外联」无直接关系）。
4. **未跑** `lint` / `assembleRelease` / 截图门禁 / KPEX 对拍 / 真机冒烟（HIBP 外联与取消手感需真机 + 网络，本会话未执行）；MockWebServer 证据止于「请求头与解析语义」层，不得读作「已对 api.pwnedpasswords.com 端到端实测」。
5. 未触原生面 / `androidTest` / `参考项目/` / 构建脚本 ⇒ 无设备侧必跑项。
