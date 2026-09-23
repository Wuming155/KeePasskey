# §279 工程卫生收口：ACTIVE 历史留痕 / 吞异常 / 平台 String 限界批次

> 本批次由 2026-09-23 全仓工程卫生梳理触发（**非** `ACTIVE_ISSUES.md` 既有条目整改），
> 一次收口三件工程卫生事项：① 待办清单违反自登「只放现存问题」的已闭环长篇留痕；
> ② 两处目录类 `catch (_: Throwable)` 静默吞异常 + 一处空 `catch`；③ 平台 Autofill /
> Credential Manager 边界的不可擦 `String` 口令面未登记限界表。
> 本批**不改任何业务行为**（吞异常处仅补上下文日志），**零测试增减**。

## §1 背景与问题清单

### 1.1 ACTIVE 历史留痕违反自规

`docs/ACTIVE_ISSUES.md` 文首写明：

> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留。

但 P1 / P2 节下仍挂多段「历史归零留痕」，对已闭环 `ISSUE-P1-241`（§246）、`ISSUE-P1-276`（§273）、
`ISSUE-P2-271`（§271）、`ISSUE-P2-277`（§274）、`ISSUE-P2-283`（§276）重述整段整改叙事。
这些叙事的**单一真相源**早已是对应批次正文与 `RESOLVED_LOG.md` 索引行；留在待办清单里既违反自规，
也制造「第二份结论」的漂移风险。

### 1.2 吞异常（工程规则「禁止空 catch 块或捕获后仅打印」）

| 位置 | 形态 | 违反 |
|---|---|---|
| `app/.../VaultDatabaseCatalog.kt` 5 处 | `catch (_: Throwable) { emptyList()/null/空体 }`，全文无日志 | 吞异常无上下文 |
| `app/.../InstalledAppsCatalog.kt` 5 处 | `catch (_: Throwable) { null/fallback/emptyList() }`，全文无日志 | 吞异常无上下文 |
| `sync/.../WebDavPropfindParser.kt:217` | `catch (_: Exception) {}` **空 catch** | 禁止空 catch |

两处 Catalog 的回落语义本身正确（失败不伪造数据），缺的是**可观测性**——异常被完全吞掉后，
「列表为空 / 图标占位 / 大小缺省 32KB」无法与「真的没有数据」区分。

### 1.3 平台 String 口令面未登记限界

工程规则要求「能用 Char 的地方绝不落到 `String`」，但系统 Autofill / Credential Manager 在
**进程边界**交付的口令形态即为 JVM `String`。既有 §2.4 只覆盖 Compose 文本状态，未覆盖平台 API 入口。
（模型层字段 `String` 属 `ISSUE-P3-303` 第 2 类，**不在本批**。）

## §2 整改内容

### 2.1 ACTIVE 历史留痕剪除

- P1 节：两段「历史归零留痕」（§246 / §273）整块删除，恢复为与 P0 同形的「暂无开放项」。
- P2 节：三段「历史归零留痕」（§271 / §274 / §276）整块删除，节下直接进开放条目 `ISSUE-P2-278`。
- **处置依据**：正文已在 `resolved/batches/246|271|273|274|276-*.md`，属**冗余重述**而非未归档正文；
  按「归档正文只搬迁、不改写」与「已闭环条目一律不留」，此处**只删不迁**，避免同一结论第三份拷贝。
- 读数：`ACTIVE_ISSUES.md` 324 → **309 行**；全仓检索「历史归零 / 曾闭环」**0 命中**。

### 2.2 吞异常补上下文日志（行为面零变化）

统一改 `catch (t: Throwable)` / `catch (e: Exception)` + `AppLog.w/d`（复用既有 `AppLog`
脱敏口径：release 只留异常类名，不透 message / 堆栈）。回落值**全部保持原语义**：

| 文件 | 改动 | 回落（未变） |
|---|---|---|
| `VaultDatabaseCatalog.kt` | 5 处 catch 补 `AppLog.w(TAG, …, t)`；companion 增 `TAG = "VaultDbCatalog"` | `emptyList()` / `null` / `32L` / 空写 |
| `InstalledAppsCatalog.kt` | 5 处 catch 补 `AppLog.w(TAG, …, t)`；object 增 `TAG = "InstalledApps"` + `AppLog` 导入 | `emptyList()` / `null` / `fallback` |
| `WebDavPropfindParser.parseHttpDate` | 空 catch 改 `AppLog.d`（逐格式不匹配）+ 循环后 `AppLog.w`（全格式失败） | 仍返回 `0L` |

**刻意不改**：不把回落改成抛错 / 不改「失败按未知 / 空 / 占位」的产品语义（与既有 KDoc
「不伪造应用名与图标」「不谎报缺授权」一致）；不把 `Throwable` 收窄为 `Exception`（PMS /
ContentResolver 可能抛 `Error` 族，收窄会改变可达失败面）。

### 2.3 限界表新增 §2.6

`docs/architecture/已知工程限界.md` 新增 **§2.6 平台 Autofill / Credential Manager 边界的口令为不可擦 `String`**【客观限界】：

- **事实**：列出 `AutofillSaveExtractor` / `PasswordSaveActivity` / `AutofillAuthResultDelivery` /
  `AutofillPickerViewModel.Credentials` / `PasswordFillActivity`·`AutofillDatasetBuilders` 的 `readString()`
  等生产入口（2026-09-23 全仓 `val password: String` / `readString()` 清点）。
- **边界**：登记的是**边界瞬时 `String` 副本**；与 §2.4（Compose 文本状态）、`ISSUE-P3-303` 第 2 类
  （模型层字段 `String`）**分列**，禁混谈。
- **已接受原因**：平台 API 契约即 `String`/`CharSequence`；进程内取证者已在信任边界之外（§2.2）。

## §3 验证

- `.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL in 2m 24s / 114 executed**。
- `python tools/doc/count_test_results.py` = **`xml=374 tests=2590 failures=0 errors=0 skipped=13`**
  （§278 基线 374/2590 ⇒ **类数 / 用例数 / skipped 三项全持平**，与「只补日志、零行为变化、零测试增减」吻合）。
- `python tools/doc/check_md_links.py` → `BROKEN_MD_LINKS=0`；`check_resolved_index_sync.py` → `OK`。
- `count_line_tiers.py` = `tier1=3 tier2=33`；`long_functions.py` = `functions_ge_100=6`（本批未触超长函数与档位）。
- 三文件 `catch (_` 检索 **0 命中**；`ACTIVE_ISSUES.md`「历史归零 / 曾闭环」**0 命中**。

## §4 如实声明

1. **历史留痕是「只删不迁」**：删掉的五段叙事在对应批次正文里已有完整版；若将来需要
   「P1/P2 曾归零」的编年史，应读 `RESOLVED_LOG.md` §246/§271/§273/§274/§276，而不是恢复待办留痕。
2. **日志不改变可观测语义的产品面**：界面仍按原回落展示；日志只服务排障。
3. **平台 `String` 只登记不改造**：边界 `toCharArray()` / `useChars` 收口点仍在；模型层 `String`
   残余仍由 `ISSUE-P3-303` 第 2 类承接，**不得**读作「全仓已无口令明文 `String`」。
4. 未跑 `lint` / `assembleRelease` / 截图门禁 / 真机 / KPEX 对拍；未触原生面 / `androidTest` / `参考项目/`
   / 构建脚本 ⇒ **无设备侧必跑项**。
5. 本批由工程卫生梳理直接收口，**未**先登记临时 `ACTIVE_ISSUES` 条目（用户当场指定三件套范围）；
   完整问题清单与核实方式以本批次 §1 为留痕。
