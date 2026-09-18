# §153 测试 Main 派发器改为「只装不卸」批次（`ISSUE-P3-189` 路线①落地）

> **起因**：§152 反证「先 cancel 再 `resetMain()`」不成立后，用户 2026-09-18 选定**路线①**（Main 只装不卸）推进。
> **本批未改任何生产代码**（改动全部落在 `app/src/test`）。
> **验收口径**：条目 AC 定为 `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` **连续 8 轮**全绿
> （§152 命中率 1/4 ⇒ 3 轮不足以区分「已修」与「未撞上」）。

---

## 1. 改动

| 对象 | 改动 | 为什么 |
|---|---|---|
| `app/src/test/.../testutil/MainDispatcherGuard.kt` | `tearDown()` **不再**调用 `Dispatchers.resetMain()`；仍先 `cancel` 全部已登记的 ViewModel 作用域 / 自持作用域；新增可选形参 `nextDispatcher` | 取消与「卸 Main」是两件事：前者防「在途工作回写到别的用例」，后者才是本次异常的来源。装的工作交给每个用例自己的 `@Before setMain(新实例)` |
| `app/src/test/.../sync/SyncCoordinatorTest.kt`、`SyncConflictMergeLocalTreeEquivalenceTest.kt` | 删除 §152 的「豁免」注释与 `Dispatchers.resetMain()`，统一改走 `MainDispatcherGuard.tearDown()` | 只要同 JVM 内还有**任何一处** reset，Main 就会重新回到「absent 即抛」态，路线①即失效。豁免分支因此被口径本身取消，而非被放宽 |
| `app/src/test/.../quality/MainDispatcherPollutionGuardTest.kt` | 判据由 3 条改 4 条：**①守卫之外不得出现 `resetMain()`**（无豁免）②构造 ViewModel 必须 `track(` ③登记到守卫者必须自行 `setMain(` ④守卫内取消先于 Main 生命周期操作且不得吞异常 | ①把「不卸载」钉成硬约束；③是对路线①代价的**部分**补偿（见 §3 残余） |

## 2. 验证

### 2. 验证（本批实测：连续 8 轮全量，条目 AC 口径）

| 轮次 | 命令 | 结果 |
|---|---|---|
| 1 ~ 5、7 ~ 8（共 **7 轮**） | `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` | **全绿**，聚合 **`tests=2186 failures=0 errors=0 skipped=13`**、`uncaught_files=0`（§152 基线 2185，本批 **+1** = 门禁第四条判据） |
| 6 | 同上 | **1 红**：`SyncCacheEvictorTest > F23 锁库清理缓存不得删除防回滚状态文件`（`SyncCacheEvictorTest.kt:134`，`AssertionError`）⇒ 限界表 **§7**「派生残余（产品代码 · 第二处）」**已登记的观测点**（该处原文即记「观测 3 次全量 / 1 次失败」），8 轮命中 1 次与登记命中率一致；**与本条污染无因果**（该类不 `setMain`，且 `uncaught_files=0`） |

- **症状判据**：8 轮内 §152 那条 `IllegalStateException: Dispatchers.Main was accessed when the platform
  dispatcher was absent` **零复现**；每轮 `UncaughtExceptionsBeforeTest` 命中文件数均为 **0**。
- **统计口径如实（不得把 8 轮读作确证）**：§152 实测命中率 1/4 ⇒ 若「未修好」，8 轮全不命中的概率约 **10%**
  （0.75⁸）。故本批的主证是**机理**而非轮次：该异常的必要条件是 Main 处于「absent」态，
  而全测试源集已**不存在任何** `resetMain()` 调用点（由门禁判据①长期钉死），条件不再可构造。
- **未执行**：设备侧四件套（本批不动生产代码与 `androidTest`）、`cargo test`、`-DliveSyncTest`
  （后两者被本机策略拦下，用户可自行执行）。


- **污染症状计数**：每轮聚合同时报 `uncaught_files`（`UncaughtExceptionsBeforeTest` 命中文件数），口径同 §152；
  XML 计数口径同 §139（只取 `*/build/test-results/testDebugUnitTest/`）。
- **未执行**：设备侧四件套（本批不动生产代码与 `androidTest`）、`cargo test`、`-DliveSyncTest`（后两者被本机策略拦下，
  用户可自行执行）。

## 3. 已知残余（不装作已解决）

1. **失去「忘装 Main 立刻抛错」这一检测**：现不装 Main 的仓库面确实不触达 `Main`，而为它们装
   `StandardTestDispatcher` 会一并改掉 `Dispatchers.Main.immediate` 的**就地执行**语义（属行为改动），
   故本批**不**强制「凡构造 ViewModel 者必须装 Main」。忘装时迟到回跳会落进**上一个**用例的调度器且无人推进，
   该用例多半因「工作没跑」而自身变红——仍会暴露，只是不再是干净的报错。已登记
   [`../architecture/已知工程限界.md`](../../architecture/已知工程限界.md) **§19**。
2. **根因未被本批消除，只是不再触发**：取消 `viewModelScope` 后仍会有一次「回跳访问 Main」发生，
   这是 `withContext` 的实现事实；本批把它从「抛给下一个用例」变成「落进各自 inert 的调度器」。
   若日后 `kotlinx-coroutines-test` 改了 absent 判定或调度器终止语义，本口径需重评（§19 含解除条件）。

## 4. 过程留痕（真实发生，不隐去）

1. **门禁自造违例两次**：① 规则①的**失败消息文本**里写了完整的 `Dispatchers.resetMain()` 字面量 ⇒
   扫描器扫到自己，首轮 `FAILED`；改为 `"Dispatchers." + "resetMain()"` 拼接常量并把扫描器自身列入排除后通过。
   教训：**静态源码守卫的判据字面量必须由常量拼接或把守卫自身排除**，否则守卫永远红。
   ② §152 的「豁免注记」判据在路线①下已无对象，若保留会把「已无豁免」这件事伪装成「仍有豁免」——
   判据与口径必须同步删除，不留僵尸分支。
2. **一次 bash heredoc 传参把 `\\n` 落成真实换行**，Kotlin 字符串字面量被截断 ⇒ `compileDebugUnitTestKotlin`
   `Syntax error: Expecting '"'`（编译期即抓获，未入库）。
3. 定向复跑顺序：先 6 类（守卫 + 被改的 5 个测试类）绿，再进全量 8 轮——避免把编译期错误带进长时验证。
