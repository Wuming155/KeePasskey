# §114 列表页秒级重建与 TOTP 重算收敛批次

> **条目**：`ISSUE-P2-89` / `ISSUE-P2-90` 闭环；`ISSUE-P3-149` 的 ①②④ 闭环、**③ 转登** `ISSUE-P3-154`
> **触发**：2026-09-17 用户提出「在保持现有稳定性与可靠性的基础上降低 CPU / 内存占用，并评估是否值得把部分 Kotlin 改 Rust」，
> 据此对全仓做了一轮资源占用排查，同批登记 `ISSUE-P2-89` / `P2-90` 与 `ISSUE-P3-149` ~ `P3-153`；本批实施**第一批**（用户指定的落地顺序）。
> **本批范围**：只动「周期性重复计算」这一根因，不改任何密码学语义、文件格式与数据模型。

---

## 1. 缺陷背景（三条同源：高频节拍驱动了不该驱动的整页工作）

### 1.1 `ISSUE-P2-89` 列表页每秒在主线程重跑整页投影

`VaultListViewModel.sessionStateFlow` 把 `totpTracker.remainingSeconds`（秒级 `StateFlow`）并入最外层 `combine`，
于是**每秒**都会重跑一次 `buildVaultListUiState`——全库过滤（2~3 遍）、排序（含 `title.lowercase()` 排序键）、
`entries.map { entry.copy(…) }`、回收站后代递归与模板分组扫描全部重做；而该 `stateIn` 的收集上下文是
`viewModelScope`（Main），整段投影落在主线程。放大器：`UiVaultEntry` 含 4 个 `List` 字段且全仓无
`@Immutable`，行组件不可跳过 ⇒ 每秒的新状态带动**所有可见行**完整重组。

### 1.2 `ISSUE-P2-90` TOTP 验证码重算为 O(T×N)，且频率远超验证码本身的变化频率

验证码**在一个周期内恒定**，但调用方按「每秒（验证器页 / 详情页）」「每周期（列表页）」的节拍，
对每个带 TOTP 的条目各调一次 `calculateEntryTotp`，而每次调用内部是
`databaseFlow.first()` + `KdbxGroup.findEntry`（线性深度优先）+ Base32 解码 + 新建 `Mac` + HMAC。
100 个 TOTP 条目 + 1000 条目库 ≈ 每秒 10⁵ 次节点比较 + 100 次 HMAC，且列表页 ticker 在 ViewModel
`init` 期常驻启动，**页面不可见时照样跑**。

### 1.3 `ISSUE-P3-149` 条目投影热路径的重复开销与「单条查询付全库代价」

- `VaultEntryMapper.formatInstant` **每次调用都重新编译** `DateTimeFormatter.ofPattern(…)`，
  而投影每个条目固定调用 2 次（更新时间 + 创建时间，历史修订 / 附件再叠加）⇒ O(N) 次纯浪费的模式编译；
- `VaultEntryMapper.computeTotpCode` 在投影期对**每个**带 TOTP 的条目算一次 HMAC；
- `RealVaultRepository.getEntry(id)` 以「`getEntries()` 整库投影 + `find`」实现，详情页一次组合挂了 **3 条**这样的链，
  为一个条目付出 O(N × 字段数) 的全库映射。

---

## 2. 整改内容

### 2.1 两条 TOTP 实时值经**窄通道**下发，退出整页状态（P2-89）

| 落点 | 变更 |
|---|---|
| `VaultListProjection.kt` | `VaultListSessionState` 去掉 `totpRemainingSeconds`；`buildVaultListUiState` 去掉 `liveTotpCodes` 形参；删除 `entriesWithLiveTotp` 覆写块（条目列表直接用排序结果，连 `entry.copy` 也一并省掉） |
| `VaultListViewModel.kt` | `sessionStateFlow` 不再并入秒级流；新增窄通道 `totpRemainingSeconds` / `totpLiveCodes`；`init` **不再调 `totpTracker.start()`** |
| `VaultListScreen.kt` | 只**取状态对象**（不读 `.value`）并下传；读取动作下沉到徽标 |
| `VaultEntryRows.kt` / `VaultEntryRowLayouts.kt` | 新增 `totpRemainingSeconds: State<Int>` / `totpLiveCodes: State<Map<String,String>>` 两个形参；徽标抽为独立组件 `EntryTotpBadge`，**只在它内部读状态值** |

**性能契约**：每秒 tick 只令 `EntryTotpBadge` 那一个组合作用域失效，不再牵动整行、整表与整页状态。

### 2.2 节拍改**订阅驱动**（P2-89）

`VaultListTotpTracker` 的秒级节拍由 `remainingSeconds` 自身的订阅驱动
（`tickerFlow(…).map{…}.onStart{…}.onEach{…}.flowOn(dispatcher).stateIn(scope, WhileSubscribed(5s), …)`），
**不存在也不再有显式 `start()`**：无人订阅即停表。跨周期重算挂在该上游的 `onEach` 中，
并为此注入两个可测接口：`dispatcher`（生产 `Dispatchers.Default`，由 ViewModel 复用既有 `displayDispatcher` 注入）
与 `nowMillis`（生产 `System::currentTimeMillis`）。

### 2.3 TOTP 验证码缓存 + 批量取码通道（P2-90）

`VaultEntrySecretReader` 新增：

1. **验证码缓存** `entryId → (周期号, 快照)`（`ConcurrentHashMap`）：命中即返回——**不触碰会话、不解密、不算 HMAC**；
   跨周期由周期号自然失效。**只缓存结果快照，绝不缓存种子配置**（配置内的 Base32 字节仍按 ISSUE-P2-12 在 `finally` 中擦除）；
2. **批量通道** `calculateEntryTotps(ids)`：一次 `databaseFlow.first()` + **一次** `rootGroup.allEntries().associateBy { it.id }` 索引，
   把逐条 `findEntry` 的 O(T×N) 收敛为 O(N + T)；全命中时零遍历、零分配；
3. **失效点**：`invalidateTotpCache()` 由 `RealVaultRepository` 在两处调用——`databaseFlow` 每次变更（锁库归空 / 同步合并 / 外部写入）
   与 `persistSession()` 落库成功后（同步作废，消除「刚改完种子、同一周期内仍读到旧码」的竞态窗口）；
4. **HOTP 一律不入缓存**：其码由**持久化计数器**决定，缓存会交付一个已被推进掉的码（用户显式取码是低频动作，收益远小于风险）。

据此，`AuthenticatorViewModel` / `EntryDetailTotpTicker` 的**调用点结构未改**，但其每秒调用已由缓存吸收为 O(1) 查找：
**非翻转秒的 HMAC 次数为 0、每周期为 O(T)**。

### 2.4 投影热路径（P3-149 ①②④）

- **①** `formatInstant` 改为「按 `Locale` 缓存复用已编译的 `DateTimeFormatter`」（`ConcurrentHashMap`，
  以 Locale 为键避免系统语言变更后沿用旧格式化器）⇒ 投影期模式编译次数由 O(N) 降为 O(不同 Locale 数)；
- **②** 投影期验证码计算由 §2.3 的缓存吸收（`mapKdbxEntryToUi` 仍按投影时刻算一次，属既有语义）；
- **④** `RealVaultRepository.getEntry(id)` 改为 `KdbxGroup.findEntry`（深度优先短路）后只映射命中条目，
  不再物化整库投影。

### 2.5 顺带修正的一处时钟错配

`computeTotpCode` 新增 `timestampMillis` 形参（默认当前墙钟），缓存路径显式传入：
**出码与「周期号」判定共用同一时刻**，杜绝「用 A 时刻判定周期、用 B 时刻出码」的错配窗口
（该参数同时是缓存用例得以确定性断言「跨周期必出新码」的前提）。

---

## 3. 验证

### 3.1 新增 / 改写用例

| 用例 | 断言对象 |
|---|---|
| `VaultListTotpTrackerTest`（新，2 例） | ① 无人订阅时 5 秒内**零**取码请求；② 周期内逐秒推进 3 拍**零**重算、跨周期**必**重算一次，且批量请求只含带 TOTP 的 id（倒计时同步推进，防「干脆不刷新」假绿） |
| `TotpCodeCacheTest`（新，5 例） | 以**实例身份**判别是否重算：同周期命中同一实例 / 跨周期与作废后必得新实例 / 批量与单条共用缓存且无 OTP 条目缺席 / **HOTP 恒不命中** |
| `VaultListViewModelTest`（改 2 例 + 新 1 例） | 原两条「条目携带全局实时秒数」的断言改写为「条目验证码来自投影层 + 倒计时由窄通道下发」；新增**结构契约**断言：`VaultListSessionState` 不得再出现任何 TOTP 字段 |
| `RealVaultRepositoryTest`（新 1 例） | **毒丸**判别：把干扰条目的标题 `ProtectedString.clear()`（再读即抛），`getEntry` 仍能返回目标条目 ⇒ 证明未扫描整库 |

### 3.2 负向对照（变异验证，逐条实测）

| 变异（临时改产品代码） | 结果 |
|---|---|
| `onEach` 的翻转判据恒真（= 每拍重算） | `VaultListTotpTrackerTest > 周期内逐秒推进不得重算验证码，跨周期才重算` **FAILED** ✔ |
| 加回「init 期常驻计时」（模拟旧实现） | `VaultListTotpTrackerTest > 无人订阅时不计时也不重算验证码` **FAILED** ✔ |
| `getEntry` 改回「整库投影 + find」 | `RealVaultRepositoryTest > 单条查询不物化整库投影` **FAILED**（`IllegalStateException`，毒丸命中）✔ |

三处变异均在确认变红后逐字回填并复跑回绿。

### 3.3 全量单测

`.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL in 2m 5s**（114 tasks executed），
聚合 **`tests=1985 skipped=13 failures=0 errors=0`**（§113 为 1977；`skipped` 的 13 例为既有的
`LiveSyncServersTest` 真实联调用例，按 `-DliveSyncTest` 开关跳过）。

---

## 4. 边界与如实声明（**不得**把这些读作已验证）

1. **无性能实测**：本批判据是「不再产生某类工作」的**调用计数与结构断言**，**未**做真机 profile，
   也**未**量化墙钟 / 内存 / 耗电收益（宿主微基准不具代表性）。⇒ 不得据此宣称具体百分比收益。
2. **验证器页「停留 60 秒的 HMAC 次数」为推理级证据**：由「缓存命中即零 HMAC」的实例级断言 +
   调用点直读推出，**未**在真机侧计数。
3. **`AuthenticatorViewModel` / `EntryDetailTotpTicker` 的结构未改**：其每秒调用点**仍每秒调用一次**
   `calculateEntryTotp`（只是被缓存吸收为 O(1)）。
   ⇒ 「非翻转秒 HMAC 为 0」成立，但**不得**读作「这两个调用点已改为只在周期翻转时调用」。
4. **列表页 TOTP 徽标仍按全局 30 秒周期计算倒计时**（历史行为逐字保留）：`period ≠ 30` 的条目在
   **列表徽标**上的倒计时与真实周期不符（详情页 / 验证器页按各自 period 计算）。本批**未改**该行为。
5. **`getEntry` 有一处放宽**：传入**小写** hex id 时旧实现与 `toHexString()`（大写）字符串不等而落空，
   新实现按 UUID 字节比较可命中。调用方（UI 投影）恒传大写 ⇒ 属放宽而非行为变更。
6. **`ISSUE-P3-149` ③ 未做，转登 `ISSUE-P3-154`**：原计划给 `getEntries()` / `getGroups()` 补
   `flowOn(Dispatchers.Default)` 使整库投影离开 Main。**未做的理由**：`RealVaultRepository` 是数据层单例、
   无注入调度器，直接 `flowOn(Dispatchers.Default)` 会把投影放到测试虚拟时钟**无法控制**的真实线程池上，
   而 `RealVaultRepositoryTest` 有 40+ 处直接构造与同步断言 ⇒ 有把既有用例改成偶发红的实际风险。
   正确做法是先为该层引入可注入的调度器限定符（并同步改测试构造点），属**独立一档**的结构改动。
   **残留风险如实声明**：整库投影目前仍在收集上下文（列表页为 Main）执行，只是频率已由「每秒」降为「每次数据变更」。
7. **未跑** `assembleRelease` / 设备侧用例（`connectedAndroidTest` 需真机或 AVD），本批为纯宿主单测口径。

---

## 5. 过程缺陷与教训（工作流自身产生的问题，如实留痕）

1. **工作区行尾违反 `.gitattributes`**：改 `VaultListProjection.kt` 时多行 `Edit` 反复匹配失败，
   字节级核查发现该文件行尾为 **mixed**，进一步全仓统计发现工作区有 **220 个**文件为 `w/crlf` 或 `w/mixed`，
   而 `.gitattributes` 明文规定 `* text=auto eol=lf`（该文件正因历史「编辑工具把 LF 整文件改写为 CRLF」事故而设立）。
   处置：把本批触及的 5 个文件按字节 `\r\n → \n` 归一化（索引本就是 LF，故不产生内容差异）。
   **残留**：全仓其余约 215 个文件的**工作区**行尾仍与之不符（未处理；属工作区卫生问题，不影响提交内容）。
2. **首版缓存用例依赖了两套时钟**：断言 `跨周期必出新码` 时，出码走**真实墙钟**、周期判定走**注入时钟**，
   于是「缓存确实跨周期失效（实例不同）」但「两码恰好相同」⇒ 用例失败。
   修正为**出码与周期判定共用同一时刻**（§2.5）——该修正顺带消除了生产侧一个更窄的错配窗口。
3. **首版节拍用例的作用域选错**：以 `TestScope` 为 tracker 的 `scope`，`WhileSubscribed` 的 `stateIn` 协程永不结束，
   `runTest` 报 `UncompletedCoroutinesError`；改 `backgroundScope`（由用例框架在收尾时取消）。
4. **两处编辑事故**：一次 Edit 在预览注释前多留了一个空行、一次把投影中的步骤注释编号写重，均已就地修正。
5. **首版回归测试判别力不足（自查后加固）**：`秒级倒计时不再驱动整页状态重建` 的倒计时取值来自真实墙钟，
   虚拟时间下 1 秒内的余数相同 ⇒ **回退到旧接线也可能通过**。故同批补上**结构契约**断言
   （会话状态不得含 TOTP 字段）并与实现一道在 KDoc 中写明「本断言证明当前不发生、结构断言证明不可能发生」的判别边界。

---

## 6. 残余（转登开放项）

- `ISSUE-P3-154`：仓库投影流补 `flowOn`（含数据层可注入调度器），使整库投影彻底离开 Main——见 §4.6 的理由与残留风险。
