<a id="s288"></a>

# §288 合并图标池与库级 Meta 口径批次

> `ISSUE-P2-280` **整条闭环**（P2 11 → **10 项**）。
> 触发＝用户命题「解决 Active Issues 内的存量问题」，按优先级认领。
> 覆盖面：`ISSUE-P2-280`（本体）。**未触**原生内核 / `*/src/androidTest/**` / `参考项目/` / 构建脚本 ⇒ 无设备侧必跑项。

---

## 1. 条目正文（原样收录）

### `ISSUE-P2-280`：Meta 完全不参与合并 ⇒ 对端新增自定义图标变成悬空 `CustomIconRef`，库级配置恒取本地

- **核实时间点**：2026-09-23 经 sync 全模块 `customIcons` 检索与写出路径核对（官方 / KXC 前提已独立开源码核实）。
- **核实方式**：`KdbxMerger.kt:34-37` 的 `KdbxDatabaseLite` 仅 `rootGroup` + `deletedObjects`；sync 全模块 `customIcons` **零命中**；落库用 `localDb.copy(rootGroup = ..., deletedObjects = ...)`（`SyncConflictController.kt:127-130` 与 `:353-356`）⇒ 图标池恒为本地；写出侧 `KdbxXmlEntrySerializer.kt:40` 直写 `CustomIconRef`，reader 不校验引用是否命中池成员，仅 UI 渲染回落 `EntryIcon.Missing`（`EntryIconPresenter.kt:124`），不修复引用。
- **对照（前提为真）**：官方 `PwDatabase.cs:936` + `:945-979` 的 `MergeInCustomIcons` 按图标 `LastModificationTime` 做 LWW；KXC `Merger.cpp:700-714` 合并时补图标。`RecycleBinUuid` / `HistoryMaxItems` 等 Meta 项同理恒本地。
- **涉及文件**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt`、`app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt`、`database/src/main/java/com/keepasskey/database/xml/KdbxXmlEntrySerializer.kt`。
- **验收标准**：AC① 合并纳入 `customIcons`（LWW 或并集，口径须与官方一致并写明）；AC② 写出前校验 `CustomIconRef` 命中池成员，未命中须可辨识地失败，禁静默丢图标；AC③ 库级 Meta（`RecycleBinUuid` / `HistoryMaxItems` / `MinVersion`）逐字段给出「合并 / 取 LWW / 以本地为准」结论并登记；AC④ 用例：对端新增图标 + 本端改条目 → 合并后图标**可解析**（含 `:database:` 往返）；AC⑤ 触及外层格式面时按规则 8 对拍。

**开工前前提复核（2026-09-23）**：成立。`KdbxDatabaseLite` 仍仅两字段；sync 全模块 `customIcons` 零命中；落库三处 `copy`（`autoMergeAndUpload`、`resolveConflicts`、§286 新增的守卫路径同型）均未带图标池；写出侧直写 `CustomIconRef` 无校验。`MinVersion` 经全仓检索**本仓模型无此字段**（仅 `KdbxKdfParameterCodec` 注释提及 Argon2 `MinVersion/MaxVersion`，与 Meta 无关）。

---

## 2. 整改

### 2.1 AC①：`customIcons` 参与合并（对齐官方 `MergeInCustomIcons`）

- `KdbxDatabaseLite` 增 `customIcons`（默认空表保持测试构造点源码兼容）；`MergeResult` 增 `mergedCustomIcons`（**不设默认值**——强制每个构造点显式给出，防「忘了采用合并池」式回归）。
- `KdbxMerger.mergeCustomIcons`：按 UUID **并集**；同 UUID 不一致按 `lastModificationTime` **LWW**（任一侧 `null` 视为最旧——KDBX 4.0 库无该字段；均 `null` / 相等取本地）；**只增不删**；**base 不参与**（官方对图标池即双向合并，无三方底版语义）。KDoc 写明与官方 `PwDatabase.cs:945-979` 的逐条对应。
- 接通链：`SyncConflictMergeAdjudication.resolveTrustedBase`（base 镜像同形带池）→ `handleConflictMerge`（local/remote 镜像带池）→ `autoMergeAndUpload` 与 `resolveConflicts` 两处落库 `copy` 均增 `customIcons = mergeResult.mergedCustomIcons`（后者经新待决字段 `pendingMergedCustomIcons` 传递，A1 同型；图标为公开素材，无擦除义务）。

### 2.2 AC②：写出侧 `CustomIconRef` 命中校验（fail-closed）

- `KdbxXmlEntrySerializer` 新增单一判据 `requireIconRefResolvable`（条目 / 分组 / 历史快照三处共用）：`customIconPool` 非 null 且引用未命中 ⇒ 抛 `IllegalStateException`（消息只含 UUID，不落条目标题等敏感内容），经 `KdbxFile.save` 异常归一上浮为可辨识保存失败。
- 透传链：`KdbxXmlSerializer.serialize`（取 `database.customIcons` 的 UUID 集合，**生产恒校验**）→ `KdbxXmlGroupSerializer` → `KdbxXmlEntrySerializer`（含历史快照递归）。形参默认 `null`＝不校验，仅保持既有直接调用点（测试 / 工具）源码兼容。

### 2.3 AC③：库级 Meta 逐字段结论登记

已登记 **`产品裁决登记.md` PD-35**：`customIcons`＝合并（本条）；`recycleBinUuid` / `recycleBinEnabled` / `recycleBinChanged` / `historyMaxItems` / `historyMaxSize` / `maintenanceHistoryDays` 及其余库级 Meta＝**以本地为准**（理由逐字段在表）；`MinVersion`＝本仓模型无此字段（KDBX 4.1 KeePassXC 专有，解析侧按未知元素忽略），支持须另立条目并按规则 8 对拍。重开条件随条登记。

### 2.4 AC⑤：不触发对拍义务的说明

外层格式面**零改动**：图标池 / `CustomIconRef` 的序列化格式一行未改，新增的只是「拒绝悬空引用」的写前校验与「合并带入对端图标」的内存装配。合并产物图标的端到端可解析性由 `:database:` 整库加密往返回例证明（见 §3），故本条不触发规则 8 对拍（`OwnProductInteropProbeTest` 覆盖的是既有格式面，本批未改变其任何字节语义）。

---

## 3. 验证

### 3.1 AC④ 用例（新增 8 例）

**`sync/.../merge/KdbxMergerCustomIconsTest.kt`（4 例）**：对端新增图标 + 本端改条目 ⇒ 合并池含对端图标、本端标题改动保留、条目引用命中池成员（不悬空）；同 UUID LWW 双向；`null` 时间边界（null 视为最旧 / 均 null 取本地）；只增不删并集。

**`database/.../xml/CustomIconRefRoundtripTest.kt`（4 例）**：图标在池 ⇒ 整库加密往返后条目 + 分组引用均可解析（`KdbxFile.save` → `load` 全链）；条目 / 分组 / 历史快照三处悬空引用 ⇒ 写出即抛 `IllegalStateException`（消息含悬空 UUID，可辨识）。

### 3.2 全量回归

`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**；聚合读数
`xml=379 tests=2609 failures=0 errors=0 skipped=13`（`count_test_results.py` 现跑；
基线 §287 `tests=2601` ⇒ **+8** ＝ 本批 8 个新用例，无既有用例改动）。

### 3.3 既有调用方核对

`MergeResult` 新字段无默认值 ⇒ 编译期强制全部构造点显式传池（`mergeDatabases` 唯一构造点 + 本批两处落库消费）；`KdbxDatabaseLite` 带默认值的构造点 60+（测试）原样通过；序列化器新形参默认 `null` ⇒ 既有直接调用点行为逐字不变（`KdbxXmlSerializer` 生产链路恒传真实池）。

## 4. 如实声明

- 未跑 `lint` / 截图门禁 / 真机 `connectedDebugAndroidTest`（无 `@Preview`、无 `androidTest`、无原生面改动）。
- AC② 的 fail-closed 有一个已知边界：第三方库若**自带**悬空 `CustomIconRef`（池外引用），本端打开后首次保存将被拒——这是 AC② 明文的取舍（禁静默丢图标），用户须先在编辑页修正引用；未登记限界（属条目要求的可辨识失败语义本身）。
- 历史快照的 `CustomIconRef` 同受校验（条目历史与分组一并覆盖）；图标池合并不处理「图标内容相同但 UUID 不同」的去重（官方同口径）。
