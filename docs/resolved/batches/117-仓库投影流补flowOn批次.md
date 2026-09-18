# §117 仓库投影流补 `flowOn` 批次

> **条目**：`ISSUE-P3-154` 闭环（§114 的**残留**——`ISSUE-P3-149` ③ 未做部分）。
> **触发**：2026-09-17「降低 CPU / 内存占用」排查的**残留项收口**（前四批见 §114 ~ §116）。
> **本批范围**：只改**执行线程归属**——投影的输入、输出、映射实现与频率一律不动。

---

## 1. 背景与开工复核

`ISSUE-P3-149` ③ 原计划为 `RealVaultRepository.getEntries()` / `getGroups()` 补
`flowOn(Dispatchers.Default)`，使「整库 `KdbxEntry` → `UiVaultEntry`」的映射（逐字段解密 + 时间格式化）
离开收集上下文。§114 已把该投影的**频率**由「每秒」降为「每次数据变更」，但**执行线程未变**：
列表页的收集上下文是 `viewModelScope`（Main）。

§114 未一并做的理由是：`RealVaultRepository` 是数据层 `@Singleton`、无注入调度器，
直接写死 `Dispatchers.Default` 会把投影放到测试虚拟时钟**无法控制**的真实线程池上。

**开工复核（条目维护规则 2）——一处前提偏差就地更正**：条目原文称
「`RealVaultRepositoryTest` 有 **40+ 处**直接构造与同步断言」，实测为：

- 全仓 `RealVaultRepository(` 直接构造点 **15 处**，**全部**集中于 `RealVaultRepositoryTest`（无其它测试 /
  androidTest / 生产调用点）；
- 该文件 16 例**全部**为 `runTest` + `first()` 形态，**无** `advanceUntilIdle` / `runCurrent` / `advanceTimeBy`
  （即不依赖虚拟时间推进顺序）。

⇒ 风险方向与条目判断一致（测试与真实线程池混用会把在途工作带过用例边界），但**规模是 15 处而非 40+**，
且改造方式可以是**单一构造入口**（见 §2）。此偏差不影响整改方向，仅影响改动量评估。

---

## 2. 整改内容

| 落点 | 变更 |
|---|---|
| `data/repository/VaultProjectionDispatcher.kt`（**新增**） | 数据层调度器限定符 `@VaultProjectionDispatcher` + Hilt 模块 `VaultProjectionModule`（生产固定 `Dispatchers.Default`）。做法对齐展示层既有的 `@EntryDisplayDispatcher`（限定符 + 模块同文件、随消费方所在包声明） |
| `RealVaultRepository` | 构造器新增 `@VaultProjectionDispatcher private val projectionDispatcher: CoroutineDispatcher`——**刻意不给默认值**（默认值等于给未来调用点留一条「悄悄退回收集上下文」的路，正是本条要消除的行为）；`getGroups()` 与 `getEntries()` 末尾补 `flowOn(projectionDispatcher)`；两条流的 KDoc 写明线程归属与语义不变的理由 |
| `RealVaultRepositoryTest` | 15 处构造点收敛为**单一入口** `TestScope.newRepository(session, filesDir, projectionDispatcher)`（默认 `StandardTestDispatcher(testScheduler)`，与 `runTest` 同一虚拟时间轴）；新增 2 例（见 §3.1） |
| `RealVaultRepository`（顺带） | 删除 `getEntry(id)` 上**重复的 KDoc 块**——§114 遗留：同一份说明逐字叠了两遍（第一份为孤立注释）。纯文档清理，零行为变更 |

**刻意未动**（连同理由）：

- `VaultGroupCoordinator` / `VaultRepository` 接口：投影调度器是 `RealVaultRepository` 的**实现属性**，
  不上提到接口——`FakeVaultRepository` 是内存替身、无解密成本，若接口声明该契约则替身不合规（见 §4.5）；
- `FakeVaultRepository`：同上，不补 `flowOn`；
- `repositoryScope`（仍硬编码 `Dispatchers.Default`）：库列表刷新与 TOTP 缓存失效是**常驻**作用域，
  改用注入调度器会让测试注入的虚拟时间调度器上出现永不结束的协程（见 §4.4）；
- `getEntry(id)` 单条投影流：只映射一条，成本与库规模无关；条目原文的整改方向亦只点名两条**整库**投影流（见 §4.2）。

---

## 3. 验证

### 3.1 新增用例

| 用例 | 断言对象 | 判别力 |
|---|---|---|
| `整库条目与分组投影在注入的投影调度器上执行` | 注入**记录型调度器**（`DispatchRecordingDispatcher`：只统计派发次数，执行委托给 `StandardTestDispatcher(testScheduler)`，故断言完全落在虚拟时间轴上、不依赖真实线程池）。断言三段：未收集时计数 **= 0**；`getEntries().first()` 后 **> 0**；`getGroups().first()` 后**继续增长**（两条流**分开计数**，避免一条已接线掩盖另一条漏接线） | 投影若仍在收集上下文执行，该调度器**一次都不会被派发** ⇒ 必红 |
| `生产投影调度器绑定落在 Default 而非 Main` | `assertSame(Dispatchers.Default, VaultProjectionModule.provideVaultProjectionDispatcher())` | 绑定一旦改成 `Dispatchers.Main` 或任何其它调度器即红 |

### 3.2 负向对照（实测变红后逐字节还原）

去掉 `getGroups()` / `getEntries()` 两处 `flowOn` ⇒
`整库条目与分组投影在注入的投影调度器上执行` **FAILED**：
`java.lang.AssertionError: 整库条目投影必须派发到注入的投影调度器` ✔；
随后由备份逐字节还原（`diff` 与备份一致，`grep flowOn` 复核两处均回位）。

### 3.3 全量单测

`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL in 1m 55s`（114 tasks executed），
聚合 **`tests=1995 skipped=13 failures=0 errors=0`**（§116 为 1993，**+2** = 本批新增两例）。

> 口径说明：统计只取各模块 `build/test-results/testDebugUnitTest/`。工作区另存
> `app/build/test-results/updateDebugScreenshotTest/`（2026-09-16 的**陈旧**结果，2 例）——
> 它不由 `test` 任务产出，**不得**计入（计入会得到 1997 的虚高值）。

### 3.4 DI 图编译期核实

Hilt 生成代码 `DaggerMainApplication_HiltComponents_SingletonC` 已将
`VaultProjectionModule_ProvideVaultProjectionDispatcherFactory.provideVaultProjectionDispatcher()`
注入 `RealVaultRepository` 构造点；`MainApplication_HiltComponents` 已登记 `VaultProjectionModule.class`。
即：绑定缺失会在**编译期**失败，无需运行期验证。

---

## 4. 边界与如实声明（**不得**把这些读作已验证）

1. **无真机 / 宿主性能实测**：判据是「投影确实被派发到注入的投影调度器」这一**事实**与生产绑定值，
   **不得**据此宣称任何百分比收益，也不得宣称「主线程不再有该投影的任何开销」。
2. **只覆盖两条整库投影流**：`getEntry(id)`（单条投影）**未**补 `flowOn`。条目原文的整改方向只点名
   `getEntries()` / `getGroups()`，本批严格照此范围执行 ⇒ **不得**读作「仓库层投影已全部离开收集上下文」。
3. **两条流的中间态对齐不再严格同步**：各自独立派发后，`combine` 侧可能短暂出现「新条目 + 旧分组」
   一类中间态（**终态一致**；改造前两者本就是两条独立收集协程，只是同在 Main 上按序推进）。
   既有收集者用例全绿；**但须如实说明其判别力有限**——列表页 / 详情页 / 编辑页的用例多以
   `FakeVaultRepository` 为替身（该替身未接 `flowOn`），它们的全绿**不构成**对本改造的判别；
   对 `RealVaultRepository` 投影**取值正确性**的覆盖来自 `RealVaultRepositoryTest` 自身的
   15 处构造点用例（其中 3 处断言 `getGroups()` / `getEntries()` 的具体取值）。
4. **`repositoryScope` 仍硬编码 `Dispatchers.Default`**（刻意不合并）：它是**常驻**作用域（库列表刷新 +
   `databaseFlow` 失效通知），若改用注入的投影调度器，测试注入的虚拟时间调度器上会出现永不结束的协程，
   `runTest` 将以 `UncompletedCoroutinesError` 收场。
5. **`FakeVaultRepository` 未补 `flowOn`**，且 `VaultRepository` 接口**未**声明调度器契约 ⇒
   「投影离开收集上下文」是 `RealVaultRepository` 的**实现属性**，不是接口保证；替换实现不自动继承该性质。
6. **新增用例是接线断言**：它证明「派发到了注入的调度器」，**不**度量搬移前后的耗时差，
   也**不**证明设备侧主线程帧率 / ANR 有任何变化。
7. **未跑** `assembleRelease`（R8）与设备侧用例（与 §114 / §115 同口径）；本批未触及加解密、
   文件格式与数据模型，`ISSUE-P3-154` 的 AC 亦未要求互操作对拍。
8. 本批**未**处理工作区行尾：只触及 3 个文件（2 改 1 新增），经逐字节核对**均为纯 LF**（CR 计数 0），
   未引入新的 CRLF/mixed 文件。

---

## 5. 过程留痕

1. **用例名首版含点号**：`` fun `生产投影调度器绑定为 Dispatchers.Default`() `` ⇒ Kotlin 编译期报
   `Name contains illegal characters: ..`（JVM 方法名不允许 `.`）⇒ 改名为「生产投影调度器绑定落在
   Default 而非 Main」。
2. **条目前提偏差**：原文「40+ 处直接构造」实测为 **15 处**（见 §1），按条目维护规则 2 就地更正并留痕。
3. **统计口径陷阱**：首次聚合得到 `tests=1997`，比「§116 基线 + 本批新增」多出 2 —— 定位为
   `updateDebugScreenshotTest/` 的**陈旧**结果目录（mtime 为 2026-09-16，非本批产出）。按 §3.3 口径剔除后
   为 1995，与基线严格对齐。**若不做这一步就会把一个虚高数字写进归档**。
4. **顺带清理**：删除 `getEntry(id)` 上 §114 遗留的重复 KDoc 块（同一说明逐字两份）。

---

## 附录：条目原文（迁移前快照）

> 按 `ACTIVE_ISSUES.md` 的闭环纪律「整条剪切」此处收录。

### ISSUE-P3-154 仓库投影流补 `flowOn`：整库投影仍在收集上下文（列表页为 Main）执行

- **背景**：`ISSUE-P3-149` ③ 原计划为 `RealVaultRepository.getEntries()` / `getGroups()` 补
  `flowOn(Dispatchers.Default)`，使「整库条目 → `UiVaultEntry`」的映射（逐字段解密 + 格式化 + TOTP 计算）
  彻底离开收集上下文。§114 已把该投影的**频率**由「每秒」降为「每次数据变更」，但**执行线程未变**：
  列表页的收集上下文是 `viewModelScope`（Main）。
- **未在 §114 一并做的理由**：`RealVaultRepository` 是数据层 `@Singleton`、无注入调度器，
  直接写死 `flowOn(Dispatchers.Default)` 会把投影放到**测试虚拟时钟无法控制**的真实线程池上，
  而 `RealVaultRepositoryTest` 有 40+ 处直接构造与同步断言 ⇒ 有把既有用例改成偶发红的实际风险。
- **整改方向**：先在数据层引入**可注入的调度器限定符**（对齐 UI 层既有 `@EntryDisplayDispatcher` 的做法），
  由 DI 提供 `Dispatchers.Default`、由测试注入测试调度器；再补 `flowOn`，并同步改测试构造点。
- **核实时间点与方式**：2026-09-17 由 §114 实施过程中的实测阻塞得出（工作区行尾与全量测试口径均已核实）。
- **验收标准**：整库投影不再在 Main 上执行（可用测试调度器断言）；`RealVaultRepositoryTest` 全绿且**不引入**
  依赖真实线程池的偶发断言；`getEntries()` 的收集者（列表页 / 自动填充 / 子库）行为零变化。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§117）

仓库投影流补 `flowOn` 批次（`ISSUE-P3-154`，§114 的**残留**收口——`ISSUE-P3-149` ③）：`getEntries()` / `getGroups()` 的整库投影（逐字段解密 + 时间格式化）此前在**收集上下文**执行（列表页为 `viewModelScope` = Main）；§114 只把**频率**由每秒降为每次变更，**执行线程未变**。修法按条目原文：**先在数据层引入可注入的限定符** `@VaultProjectionDispatcher` + Hilt 模块（生产 `Dispatchers.Default`，做法对齐展示层 `@EntryDisplayDispatcher`），**再补 `flowOn`**，构造参数**刻意不给默认值**（默认值等于留一条悄悄退回收集上下文的路）；15 处测试构造点收敛为单一入口 `TestScope.newRepository(...)`（默认 `StandardTestDispatcher(testScheduler)`，与 `runTest` 同一虚拟时间轴）。**验证**：新增 2 例——①**记录型调度器**（只计数派发、执行委托测试调度器）断言「未收集时 0 / `getEntries()` 后 >0 / `getGroups()` 后继续增长」（两条流**分开计数**，防一条接线掩盖另一条）；②`assertSame(Dispatchers.Default, …)` 锁生产绑定。**负向对照**：去掉两处 `flowOn` ⇒ 必红（`AssertionError: 整库条目投影必须派发到注入的投影调度器`）✔，随后逐字节还原。全量 `--rerun-tasks` **1m 55s 绿**，聚合 **`tests=1995 skipped=13 failures=0 errors=0`**（§116 为 1993，+2）；Hilt 生成代码核实绑定已注入（缺失即编译期失败）。**边界如实声明**：**无性能实测**（判据是派发事实与绑定值，**不得**宣称百分比收益）；只覆盖两条**整库**投影流，`getEntry(id)` 单条投影**未**补（条目原文范围亦只点名这两条）⇒ **不得**读作「仓库层投影已全部离开收集上下文」；两条流各自独立派发后 `combine` 的**中间态对齐不再严格同步**（终态一致，既有收集者用例全绿）；`repositoryScope` 仍硬编码 `Dispatchers.Default`（**刻意不合并**——常驻作用域落在测试调度器上会以 `UncompletedCoroutinesError` 收场）；`FakeVaultRepository` 未补且 `VaultRepository` 接口**未**声明该契约 ⇒ 属实现属性而非接口保证；未跑 `assembleRelease` 与设备侧用例。**过程留痕**：条目前提**偏差更正**——原文「40+ 处直接构造」实测为 **15 处**（全在 `RealVaultRepositoryTest`，全为 `runTest` + `first()`、无虚拟时间推进）；用例名含点号触发 Kotlin `Name contains illegal characters` 而改名；**统计口径陷阱**——首次聚合得 `tests=1997`，多出的 2 例定位为 `updateDebugScreenshotTest/` 的**陈旧**结果目录（非 `test` 产出），剔除后方与基线严格对齐；顺带删除 `getEntry` 上 §114 遗留的**重复 KDoc 块**

### 分册 04行原文（§117）

仓库投影流补 `flowOn` 批次（`ISSUE-P3-154`，§114 的**残留**收口——`ISSUE-P3-149` ③）：`getEntries()` / `getGroups()` 的整库投影（逐字段解密 + 时间格式化）此前在**收集上下文**执行（列表页为 `viewModelScope` = Main）；§114 只把**频率**降为「每次数据变更」，**执行线程未变**。按条目原文修法：数据层新增可注入限定符 `@VaultProjectionDispatcher` + Hilt 模块（生产 `Dispatchers.Default`，对齐展示层 `@EntryDisplayDispatcher`），再补 `flowOn`；构造参数**刻意不给默认值**（默认值等于留一条悄悄退回收集上下文的路）；15 处测试构造点收敛为单一入口 `TestScope.newRepository(...)`（默认 `StandardTestDispatcher(testScheduler)`，与 `runTest` 同虚拟时间轴）。**验证**：新增 2 例——①**记录型调度器**（只计数派发、执行委托测试调度器）断言「未收集 0 / `getEntries()` 后 >0 / `getGroups()` 后继续增长」（两条流**分开计数**）；②`assertSame(Dispatchers.Default, …)` 锁生产绑定。**负向对照**：去掉两处 `flowOn` ⇒ 必红（`整库条目投影必须派发到注入的投影调度器`）✔ 后逐字节还原。全量 `--rerun-tasks` **1m 55s 绿**，`tests=1995 skipped=13 failures=0 errors=0`（§116 为 1993，+2）；Hilt 生成代码核实绑定已注入（缺失即编译期失败）。**边界**：无性能实测（不得宣称百分比收益）；只覆盖两条**整库**流，`getEntry(id)` **未**补（不得读作「仓库层投影已全部离开收集上下文」）；两条流独立派发后 `combine` 中间态对齐不再严格同步（终态一致）；`repositoryScope` 仍硬编码 `Dispatchers.Default`（**刻意不合并**，否则常驻作用域落在测试调度器上会 `UncompletedCoroutinesError`）；`FakeVaultRepository` 未补、接口**未**声明该契约 ⇒ 属实现属性非接口保证。**过程留痕**：条目前提更正（「40+ 处直接构造」实测 **15 处**）；用例名含点号触发 Kotlin `Name contains illegal characters` 而改名；**统计口径陷阱**（首次聚合得 1997，多出的 2 例来自 `updateDebugScreenshotTest/` 陈旧结果目录，剔除后与基线对齐）；顺带删 `getEntry` 上 §114 遗留的重复 KDoc 块
