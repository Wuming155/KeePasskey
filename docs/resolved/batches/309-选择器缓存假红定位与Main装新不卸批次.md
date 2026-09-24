<a id="s309"></a>

# §309 选择器缓存偶发假红定位与 Main「装新不卸」批次

> `ISSUE-P2-307` **整条闭环**（P2 1 → **0 项**）。
> 覆盖面：`ISSUE-P2-307`（`AutofillPickerViewModel` 三例非确定性失败——全量套件偶发红、隔离与成对跑皆绿）。
> 本批**未改任何生产代码**（改动全部落在 `app/src/test`）；根因在开工当日即被定位（AC①），
> 修复为测试口径级（AC②），`ISSUE-P3-189` 路线①裁决同步修订（AC③）。

---

## 1. 条目正文（原样收录）

### `ISSUE-P2-307`：选择器缓存三例**非确定性失败**（全量套件偶发红、隔离与成对跑皆绿）——测试有效性缺口

- **核实时间点**：2026-09-24（`ISSUE-P3-301` 批次全量回归中偶发出现；复跑同一命令即绿，无代码变更）。
- **核实方式**：
  1. **现象**：`.\gradlew.bat test --max-workers=1` 一次运行报 3 例红——`AutofillPickerViewModelCredentialLookupTest.选择器页缓存命中时不回查仓库单条`（`expected:<0> but was:<1>`）、`AutofillPickerViewModelSessionLockTest.会话锁定后选择器缓存条目被清空`（`测试前提：选择器应已缓存条目`）、`AutofillPickerViewModelSessionLockTest.锁定竞态下读取已清零条目_search与用户名按空降级而非崩溃`（`expected:<1> but was:<0>`）；同一命令**原地复跑即 BUILD SUCCESSFUL（`tests=2688 failures=0`）**，期间无任何代码改动。
  2. **隔离**：`--tests "com.keepasskey.app.autofill.AutofillPickerViewModel*"` 单跑 **BUILD SUCCESSFUL**。
  3. **成对**：与最可疑的邻居 `com.keepasskey.app.sync.SyncAssemblyOffMainThreadTest` 同批跑 **BUILD SUCCESSFUL**（该测试文件的 `HEAD` 版与待提交版**两种都试过，皆绿**）⇒ 「与该文件同分叉即失败」的假设**未成立**。
  4. **未定位**：失败无堆栈归因（皆为断言差异），未取得可复现的最小条件。
- **背景与根因（**待定位**，以下为假设而非结论）**：`SyncAssemblyOffMainThreadTest` 的 `tearDown` 按 `ISSUE-P3-189` 路线①采用「**只装不卸**」口径（不调 `resetMain`，仅走 `MainDispatcherGuard`），即该测试类结束后 JVM 内的 `Dispatchers.Main` 仍是它安装的 `TestDispatcher`。若同分叉内后续测试类**依赖真实 Main**（未自行 `setMain`），其挂起工作可能永不推进 ⇒ 呈现为「缓存应命中却未命中 / 应已缓存却为空」一类断言差异，与本次 3 例的形态吻合。**但该假设未获证据支持**（第 3 步成对实验即为否证尝试）。
- **后果**：CI 会出现**与代码无关的假红**，侵蚀「红=真问题」的信号价值（与 `ISSUE-P3-305` 的红态长期化属不同面：那是门禁恒红，这是偶发假红）。
- **涉及文件**：`app/src/test/java/com/keepasskey/app/autofill/AutofillPickerViewModelCredentialLookupTest.kt`、`app/src/test/java/com/keepasskey/app/autofill/AutofillPickerViewModelSessionLockTest.kt`；高度相关：`app/src/test/java/com/keepasskey/app/sync/SyncAssemblyOffMainThreadTest.kt`（Main「只装不卸」）与其依赖的 `MainDispatcherGuard`；`app/src/test/java/com/keepasskey/app/testutil/MainDispatcherGuard.kt`。
- **验收标准**：AC① **先定位**：取得可复现的最小条件（建议手段：`--max-workers=1` 固定分叉 + 逐类二分加入，或在 `MainDispatcherGuard` 记录「装上 / 卸下」事件并断言套件结束时 Main 已复位）；AC② 定位后**修根因**（`resetMain` 口径的取舍须一次性贯通，禁只给单个用例打补丁）；AC③ 若根因确为 Main 泄漏，须评估 `ISSUE-P3-189` 路线①的既有裁决是否需修订，并同步该裁决的登记处；AC④ 未定位前**不得**以下调断言强度 / 加 `@Ignore` / 放宽比较的方式让套件变绿（假绿比假红更危险）；AC⑤ 补一条守卫：套件级断言「测试结束时 `Dispatchers.Main` 已复位或与初始一致」，使同类泄漏当场可见。

---

## 2. 定位与整改

### 2.1 根因（AC①）：`viewModelScope` 依赖实时执行的用例继承了上一类「装而不卸」的死调度器

条目原假设「成对实验即否证」的**方向是对的、但实验设计测不出**——真正的因果链与三个未测事实拼齐后成立：

1. **全仓 34 个测试类**（33 个 `val testDispatcher = StandardTestDispatcher()` + `SyncAssemblyOffMainThreadTest`）在 `@Before` 把 `StandardTestDispatcher` 装成 `Dispatchers.Main` 且**从不卸载**（§153 路线①「只装不卸」）。这类调度器的排队协程**必须显式推进其 scheduler 才会执行**。
2. `AutofillPickerViewModelCredentialLookupTest` / `AutofillPickerViewModelSessionLockTest` **从不 `setMain`**，其用例经 `viewModelScope.launch`（= `Dispatchers.Main.immediate`）异步装载条目，再以**实时轮询**（`Thread.sleep(10)` 循环，5 s 超时）等待缓存就绪——三例失败全是这一形态（缓存空 / 未命中）。
3. **隔离跑为何绿**：新 JVM 里 `kotlinx-coroutines-test` 经 ServiceLoader 注册的 `TestMainDispatcherFactory` 把 `Dispatchers.Main` 的默认委托定为 **`UnconfinedTestDispatcher`（eager，launch 即时执行）** ⇒ 不装 Main 的类在干净 JVM 上天然工作。
4. **成对跑为何也绿**：成对命令里类排序为字母序，`autofill.*` **先于** `sync.*` 执行 ⇒ 泄漏方还没跑。
5. **全量为何偶发红**：Gradle 的类执行顺序不保证字典序，**是否命中取决于某个装了 `StandardTestDispatcher` 的类是否恰好先于这两个类执行**。命中时 launch 落进死调度器永不被推进 ⇒ 5 s 实时等待超时 ⇒ 缓存恒空 ⇒ 断言差异（与三例报错逐点吻合：`expected:<0> but was:<1>` 单条回查、`测试前提：应已缓存条目`、`expected:<1> but was:<0>`）。

**可复现最小条件**：同 JVM 内先执行任意一个 `setMain(StandardTestDispatcher())` 且收尾只「取消作用域、不换装 Main」的类，随后执行这两个 autofill 类 ⇒ 必红。已钉成永久机检：`MainDispatcherGuardNormalizationTest.守卫收尾后 Main 等价于初始 eager 态_死调度器不再被继承`（守卫若回退为「只装不卸」该用例立即红）。

### 2.2 修复（AC②）：守卫口径「只装不卸」修订为「**装新不卸**」，一次性贯通

| 位置 | 改动 |
|---|---|
| `MainDispatcherGuard.tearDown` | 取消全部已登记作用域**之后**，`Dispatchers.setMain(nextDispatcher ?: UnconfinedTestDispatcher())`——为下一个用例装上新鲜 eager 默认 Main（等价于新 JVM 的 ServiceLoader 初始态）。**仍绝不调用 `resetMain()`**（§152 反证的「absent 即抛」约束原样保留）；`nextDispatcher` 形参保留显式衔接能力，缺省恒装新实例 |
| `AutofillPickerViewModelCredentialLookupTest` / `AutofillPickerViewModelSessionLockTest` | 补 `@Before Dispatchers.setMain(UnconfinedTestDispatcher())`（显式声明「依赖 eager 实时执行」这一既有语义，与新 JVM 初始态一致）+ `MainDispatcherGuard.track(...)` + `@After MainDispatcherGuard.tearDown()`——关闭 §153 残余「凡构造 ViewModel 者必须装 Main」的豁免面 |
| `MainDispatcherPollutionGuardTest` | 判据 4 条 → **6 条**：②「构造 ViewModel 必须 track」**删去「有 setMain」前提**（§153 残余前提已被本条证伪）；⑤ 新增「守卫收尾必须装新且装新晚于取消」；⑥ 新增「凡引用 `Dispatchers.Main` 的用例必须自行 `setMain`」（封死「装新」修复后唯一剩余的隐式继承面） |

- **为什么这不是「单个用例打补丁」**：结构性修复在守卫收尾一处生效于全部 34 个泄漏类；两个 autofill 类的 `setMain` 是按统一规则补齐其**本就缺失**的显式声明（它们是全仓仅有的两个「构造 ViewModel 却不装 Main」的类，静态扫描证实无第三处）。
- **语义等价性**：收尾装上的 `UnconfinedTestDispatcher` 与新 JVM 初始态的 `TestMainDispatcher(委托 UnconfinedTestDispatcher)` 在 `Main.immediate` / `isDispatchNeeded` / eager 执行上行为一致；迟到的回跳访问（§152 反证的不可避免访问）从「落进死调度器」变为「落入 eager 调度器但父作用域已取消 ⇒ 续体按取消路径即时收敛」，仍无 absent 异常、无用户代码执行。

### 2.3 裁决修订同步（AC③）

`ISSUE-P3-189` 路线①的既有裁决登记处（限界表 §19）已同步修订：口径名「只装不卸」→「**装新不卸**」，登记的代价从「忘装者继承死调度器」降级为「忘装者继承 eager 初始等价态」，边界 ②（≠「跨用例污染已根治」，`track` 取消义务不变）与解除条件原样保留并追加 §309 指针。守卫与守卫测试的 KDoc 同步改写。§153 批次文档为归档历史，**不改写**（其「已知残余 1」由本批关闭，残余 2 的机理描述由限界表 §19 现行版承载）。

### 2.4 AC⑤ 的等效落地（如实声明，前提已实测修正）

条目原文的「套件级断言」在 Gradle JUnit4 工具链下**无 in-JVM 挂载点**——本批实测两路均否：

- **ServiceLoader `RunListener`**：探针（`testRunFinished` 写标记文件）实测**从未触发**（`app/build/listener-probe.txt` 不存在；与 Gradle 官方论坛「JUnit4 无原生监听器挂载设施、需 javaagent」一致）；
- **Gradle `Test` 任务的 `TestListener`**：回调发生在 **build JVM（Gradle daemon）侧**而非测试 worker JVM，无法观测测试 JVM 的 `Dispatchers.Main`。

故 AC⑤ 按条目维护规则 2 就地修正为**等效判据组合**，使同类泄漏「当场可见」：
① 运行期回归锁 `MainDispatcherGuardNormalizationTest`（2 用例：装新语义 + `nextDispatcher` 显式契约，守卫回退即红）；
② 静态判据 ⑤/⑥（守卫必须装新 + 引用 Main 必须自行装），每次测试运行即扫描。泄漏的**触发**被装新结构性消除后，「套件结束时的终态断言」由「每个类收尾后 Main 恒为初始等价态」这一更强的**逐类不变量**替代。

### 2.5 AC④ 合规声明

全批零断言下调、零 `@Ignore`、零比较放宽；三例原断言原样保留，其一的测试前提（等待循环）在 eager 语义下自然即时满足。

---

## 3. 验证

### 3.1 全量单测（一次通过）

`.\gradlew.bat test --rerun-tasks --max-workers=1` ⇒ **BUILD SUCCESSFUL in 7m 33s**（114 任务全执行）。

`python tools/doc/count_test_results.py`（JVM 单测聚合计数的唯一尺子）：

```
xml=400 tests=2700 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

- `tests=2700`（§308 基线 2696 **+4**：`MainDispatcherGuardNormalizationTest` 新增 2 + `MainDispatcherPollutionGuardTest` 判据 4→6 新增 2）；skipped=13 与历史基线持平。
- 定向先行：`:app:testDebugUnitTest --tests "MainDispatcherPollutionGuardTest" --tests "MainDispatcherGuardNormalizationTest" --tests "AutofillPickerViewModel*"` 绿（XML 读数：2 + 6 + 3 + 2，全 0 跳过 0 失败）。

### 3.2 门禁读数（`python tools/doc/gate_readings.py`，结案终态原样粘贴）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=33  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 307 份；分册登记 309 条；全量索引 309 条；最大 §309）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 461 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

**如实声明**：登记 §309 之前的首次采集为 **6/7**（唯一红＝`check_resolved_index_sync.py` 报「§309 未登记」——当时文档流转尚未完成，非代码面红）；分册 / 索引登记完成后终态 **7/7 PASS**（上块）。

### 3.3 未执行面（如实登记）

- 设备侧四件套（`connectedDebugAndroidTest`）：本批**未接设备**且不动生产代码 / `androidTest`，AC 无设备侧义务。
- `cargo test` / `-DliveSyncTest`：本批不触原生内核与真实联调链路。

---

## 4. 过程留痕（真实发生，不隐去）

1. **AC⑤ 探针否定两连**：ServiceLoader RunListener 探针写入 `app/src/test/resources/META-INF/services/` 后定向跑一个类——`testRunFinished` 零触发（标记文件不存在），探针文件随后删除；`TestListener` 的 build-JVM 侧回调属机理否定（未写码实测）。教训先行：**Gradle JUnit4 下的「套件级」钩子不存在，别再按 JUnit5 的 ServiceLoader 习惯臆测**。
2. **一次编译错**：`MainDispatcherGuardNormalizationTest` 首版在非 suspend 测试方法里直呼 `resolveCredentials`（`ILLEGAL_SUSPEND_FUNCTION_CALL`），包 `runBlocking` 后过——`AutofillPickerViewModel` 的 API 面（suspend 取值 + 同步缓存读）在纯 JVM 测试里的调用形态值得后来者注意。
3. **静态守卫两处自造违例被前置拦截**：守卫 KDoc 现引用 `try {` / `runCatching` / `Dispatchers.setMain(` 字面量，判据四/五若按原始源码扫描会自造违例（§153 留痕 1 的同型坑）——两判据一律改在 `stripCommentsOnly` 之后扫描。
