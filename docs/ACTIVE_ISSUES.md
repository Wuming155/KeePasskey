# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。  
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。  
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED\_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。  
> **实施前必读**：源自安全复核的条目，其 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。  
> **例外（`ISSUE-P1-276` ～ `ISSUE-P3-299`）**：这批 2026-09-23 条目出自**两轮全仓审查**（8 个铺开代理 + 7 个对抗复核代理，反向口径＝先假设误报再取证），不属上述复核范围；其依据为各条「核实方式」所记的逐行反校与对抗推翻结论。凡条目标注「**未经对抗轮单独攻击**」或「**需外部证据（真机 / 真实 DAV 矩阵 / 官方对拍）**」者，认领时须按规则 6.1② 先自行复核前提，不得直接开工。  
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED\_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。
> **新增批次（2026-10-05）**：`ISSUE-P2-466~470` / `ISSUE-P3-474~487` 出自 [`records/软件工程质量审查记录_2026-10-05.md`](records/软件工程质量审查记录_2026-10-05.md)（五维度主源码静态审查 + 合并 / 同步 / 健康审计引擎深审）。**严重度映射**：记录标 medium（含 low/medium）→ **P2**，low → **P3**。这批条目为**纯静态审查**产出（未运行构建 / 测试 / 真机），认领时须按规则 6.1② 先复核前提（正文行号仅作核实时刻的快照）；其中涉及 `crypto/src/main/rust/**` 的原生面条目，入库前须按 AGENTS.md 测试资产纪律**四层 `connectedDebugAndroidTest` 真机实跑**（执行前按 §263 确认设备上无待保留数据或改用 AVD）。
> **新增批次（2026-10-06）**：`ISSUE-P1-495` / `ISSUE-P2-496~502` / `ISSUE-P3-503~516` 出自 [`records/三类隐蔽性故障排查报告_2026-10-06.md`](records/三类隐蔽性故障排查报告_2026-10-06.md)（假开关 / 接线断裂 / 读取源错误与静默降级三类，九路并行排查 + 逐条独立复核）。**严重度映射**：报告标 high → **P1**，medium → **P2**，low → **P3**。**三项登记裁决**（登记时新定，须随条目一并遵守）：
> ① **同族去重**：报告 F12 为「原生探活失败静默回落零观测」缺陷族的唯一母条目，其子集 F03（cipher 三引擎）/ F07（强度面）/ F11（Passkey 签名面）并入 `ISSUE-P2-499`，不独立立项（该合并条目已于 §454 闭环归档）；
> ② **severity 取族内最大值**：`ISSUE-P2-499` 判 medium（母条目自身 low，子集 F03 为 medium——ChaCha20 BC 回落 ≈44× 慢、Twofish 整库数据流走纯 Java），条目内分层保留两侧论据；
> ③ **F25 不登记**：复核判定其缺陷前提被证伪（与格式裁决者 KeePass 2.61.1 官方 C# release 行为逐行同型，批次 273 勘误②复审显式维持、写侧 D17 守卫在位），`holds=false` 依据是前提证伪而非登记表命中。
> **触发状态口径**（报告 v3 判据 E1/E2，与本文件「只放现存问题」不冲突——masked 指**错误效果已产生或曾实际发作**，dormant 指**零错误效果、缺陷以声明侧残留 / 守卫缺口形态存在**）：masked 7 条（`P1-495` 除外，另 `P2-496~499`）应优先认领；dormant 条目为前瞻性风险，可按批连续解决。全部条目为**只读静态排查**产出，认领时须按规则 6.1② 先复核前提。
> **真机走查（2026-10-06）**：`ISSUE-P1-495` 收工时的真机（MIUI / `M332BF` / Android 17）五层走查——`:crypto:` 37/37、`:database:` 18/18、`:sync:` 25/25 全绿；`:core:` 4 例中 1 例**确定性假红**（曾登记为 `ISSUE-P2-517`，**已于 §450 整条闭环**，本文件不再保留该条目）；`:app:` 51 例中 3 例失败属已登记的 `ISSUE-P2-492` 厂商冻结面（读数与未定性项见 [`architecture/实现约定与验证现状.md`](architecture/实现约定与验证现状.md)）。
> **新增批次（2026-10-07）**：`ISSUE-P2-518~521` / `ISSUE-P3-517~525` 出自 [`records/六专题综合研究与实测报告_2026-10-07.md`](records/六专题综合研究与实测报告_2026-10-07.md)（动态工作流六专题调研 + 五组实测的报告，§9.3 为 8 条 backlog 草稿；报告同日落册入库并登记文档地图）。**严重度映射**：草稿标 P2 → P2、标 P3 → P3；另 4 条（`P3-520` / `P3-521` / `P3-522` / `P3-525`）为报告正文 §1 / §3 / §6 已核实发现的补登。各条登记前均已逐条复核前提（核实时间点与方式见条目正文）；`P3-523` 涉及设备侧复跑，执行前按 §263 确认设备上无待保留数据。**该批 13 条现已全部闭环**：`P2-518/519/521` §463、`P2-520` §461、`P3-517/525` §462、`P3-518/520/521` §460、`P3-519/522/524` §461、`P3-523` §464（设备侧逐例定性）；`P3-526` 为 §463 残留（`.bak` 恢复入口真机走查）同批登记入清单，**已于 §465 闭环归档**，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

|   等级   | 严重度与类型                                 |     处理原则     |
| :----: | -------------------------------------- | :----------: |
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败）  |   最高优先级排期修复  |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口**      | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化**     |   渐进优化与特性补齐  |

---

## P0 阻断级问题（0 项）

> **暂无开放项**（`ISSUE-P0-531` 已于 §473 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（`ISSUE-P1-495` 已于 §449 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)）。

## P2 中危缺陷与协议/测试缺口（**1 项**）

### `ISSUE-P2-536` 内容变更判据的**等值敏感性**——`ProtectedString.isProtected` 让「内容相同」的两棵树被判为「有变化」（§372 误报族的复发面）

- **症状（2026-10-08 用户真机反馈，原话）**：「明明没有修改过，但它却说已经修改过，还会同步到云端。这个问题之前出现过，后来修复了；现在又出现了，不知道是不是同类原因。」——症状文本与 §372（`ISSUE-P2-404`，用户 2026-09-30 反馈的「每次打开都提示『本地修改已上传至云端』」）**同貌**。
- **核实时间点 / 核实方式**（2026-10-08，四路取证）：
  1. 全链路静态逐行复核 `SyncCycleRunner` / `SyncCycleSetup` / `SyncCycleRemoteOutcomes` / `SyncContentChangeDetector` / `KdbxContentComparator` 现树；
  2. `git diff 841e9234..HEAD`（§372 那次真机验证通过的提交 → 现行 HEAD）对 `app/…/sync`、`sync/`、`database/…/session`、`core/` 全量比对——**§372 的修复链与兜底判据一字未改**；
  3. **回归用例现跑**：`.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.sync.SyncColdStartBaselineMissingTest"` → `BUILD SUCCESSFUL`（§372 的两条 AC 用例仍绿）；
  4. **临时探针用例实跑取证**（`ScratchRoundTripProbeTest`，取证后已删，不入库）：同一份字节解析两次、以及「内存树 vs 其自身序列化的解析」两种比较的读数（见下「复现读数」）。
- **已排除（不得再当嫌疑）**：① §372 的五环链**未回退**（判据/三态/兜底/用例均在位且绿）；② 同步引擎决策树（`SyncEngine` / `SyncCache`）自 §372 起**行为零变化**（`git diff` 仅注释与 tmp 清理收口）；③ 冲突合并路径自 §372 起仅三处 `message → textArg` 文案化改动。
- **§372 之后该链上**确实**新增/变更的三处**（本条登记的候选环来源）：`credentialRotationStore.consumeRecrypted → 强制 CHANGED`（§467 `ISSUE-P2-528`）、同步配置按库命名空间化（§422 `ISSUE-P2-465`，影响 `remotePath` → 缓存键）、会话层写路径改「先发布、后擦除」与展示读口降级（§473/§474）。

#### 复现读数（判据缺陷，已确证）

探针夹具：`DatabaseSession.create` → `saveGroup(子组)` → `saveEntry(条目: Title/UserName/Password + tags + customData + parentGroupId)` → `saveEntry(追加 history)` → `updateDatabaseMeta` 追加一条 `DeletedObject` → `save()` → `exportToBytes()`；两侧分别 `parseExternalDatabase(bytes)` 与取 `databaseFlow.value`（内存树）：

```
PROBE deletedObjects live=1 a=1 b=1
PROBE parse-vs-parse changed=false          ← 同一内容的两份解析：判「无变化」✅
PROBE live-vs-parse changed=true            ← 内存树 vs 其自身序列化的解析：判「有变化」❌
PROBE entry: fields=false cf=true tags=true att=true iconId=true icon=true url=true qc=true
             parent=true prevParent=true cd=true autoType=true bg=true fg=true hist=1/1
PROBE live keys=[Title, UserName, Password]   parse keys=[Title, UserName, Password]
PROBE field[Title]    live=prot=true,len=1  parse=prot=false,len=1  eq=false
PROBE field[UserName] live=prot=true,len=1  parse=prot=false,len=1  eq=false
PROBE field[Password] live=prot=true,len=1  parse=prot=true,len=1   eq=true
```

**结论**：`KdbxContentComparator.entryChanged` 的 `a.fields != b.fields` 走 `Map` → `ProtectedString.equals`，而后者**把 `isProtected` 计入等值**（+ 任一侧 `cleared` 即不等）；但对**标准五字段**该标志**不参与序列化往返**——写侧 `KdbxXmlEntrySerializer.resolveProtectedFlag` 明写「标准五字段以**库级 MemoryProtection 无条件覆盖** per-value `IsProtected`（官方 `KdbxFile.Write.cs:838-854`），**请勿按直觉改回** `value.isProtected || config…`」，读侧 `KdbxXmlStringNode` 从 `Protected="True"` 属性派生 ⇒ **内存里造出来的 `ProtectedString(text)`（构造默认 `isProtected=true`）与解析同一份文件得到的实例永不相等**。
⇒ **凡「内存树 vs 解析树」的比较（§372 兜底 `changed(localDbSnapshot, remoteDb)`、`resolveLocalContentChanged` 的缓存解析分支）在左值含内存构造字段时恒判「有变化」**。§372 之所以在真机通过：冷启动两条路径**两侧都是解析产物**（解锁即解析磁盘文件、远端字节另解析一次）⇒ 缺陷被入口形态掩盖；而 §372 自带的回归用例夹具恰好用 `ProtectedString(notes, false)` 显式绕开了该标志（这是它没能拦住本条的原因）。

#### 候选环（按可能性排序；**认领时必须先取证再动手**）

1. **环 A（本条确证面）**：左值含**内存构造**字段的任何比较。触发前提＝同步周期起点的会话树里含「本会话内被写过的节点」——任一编辑 / 通行密钥计数器补丁（`PasskeyEntryCoordinator`）/ 合并采纳（`KdbxMerger` 新建节点）/ 会话层 copy 重写（`save()` 历史修剪、`updateDatabaseMeta`）之后即成立；此外**库级 `MemoryProtection` 非默认**（如导入自其它客户端且 `ProtectTitle/UserName/Url/Notes=True`）的库，其**任何**内存写入字段都与解析值恒不等 —— 该形态下「无编辑也误报」会在**每次**同步复现，与用户描述最吻合。
2. **环 B**：§372 兜底的前置条件 `!ctx.isDirty`——`isDirty` 为真时兜底**整体跳过**，直接落 `ctx.isDirty || ctx.hasLocalContentChanged` ⇒ 合并上传并报「本地修改已上传至云端」。而**内容判据看不见的写入**（`RecycleBinCoordinator` 回填 `recycleBinUuid`/`recycleBinEnabled`/`recycleBinChanged`、`SettingsPreferencesController` 改 `cipherUuid`/`kdfParameters`/变体字典、`SettingsDatabaseMetaController` 改库名/描述/默认用户名）都会置 DIRTY 却**不进 `KdbxContentComparator` 的比较集**（该比较器只比 `deletedObjects` + 分组树/条目字段）。
3. **环 C**：`session.lastSyncedDb` 在**合并 / 用户裁决采纳**路径上未随采纳前移（`autoMergeAndUpload` 与 `SyncConflictResolution` 仅有 `settlement.accept()`，无 `lastSyncedDb = databaseFlow.value`）⇒ 下一轮以「旧树基线（且部分实例已被 `clearSupersededSensitiveData` 就地清零，`equals` 直接判不等）」对当前树比较，必得 CHANGED ⇒ 多一次无意义整库上传与同一提示。

#### 验收标准

- **AC①（取证优先，先做再改）**：真机复现时按 §372 已埋的装配期 verbose 行取证——`同步周期开始: cached=…, dirty=…, localChange=…, 远端比对=…` 同一行可一次性区分三环：`localChange=UNCHANGED` 且仍上传 ⇒ 环 B/C；`localChange=CHANGED` 而用户零编辑 ⇒ 环 A（并可据 `cached` 判断是否走了缓存解析分支）；`localChange=BASELINE_MISSING` 且上传 ⇒ 兜底比较被环 A 击败。**未取得该读数前不得改判据**（改法因环而异，且环 A 的修法在环 B 上无效）。
- **AC②（环 A 判据修正）**：`KdbxContentComparator` 对**标准五字段**改按**内容**比较（不比 `isProtected`——该标志对标准字段本就由库级 `MemoryProtection` 覆盖、不构成树内容），**自定义 / 非标准字段保留 per-value 标志比较**（对它们 per-value 才是被序列化的真值）；比较须仍不解密、不物化明文（现 `equals` 走 HMAC 等值标签，改后同样只读标签或直接比较既有实例）。涉及文件：`app/src/main/java/com/keepasskey/app/sync/KdbxContentComparator.kt`（+ 其 KDoc 的口径声明与 `已知工程限界.md` §10 的对应条目）。
- **AC③（环 B 判据修正，若 AC① 落在该环）**：兜底不得以 `isDirty` 为整体闸——`isDirty` 应与 `localChangeState` **并列**消费（`isDirty` 只表示「内存有未落盘写」，不代表「相对远端有内容差异」）；内容级比较得出「一致」时仍走 `UpToDate` 收口，同时**不为**内容判据看不见的写入（元数据 / 文件头）丢失上传出口——该面的缺口需单列（改 `cipherUuid`/`kdfParameters` 后云端副本是否替换，须单独取证）。
- **AC④（环 C 判据修正，若 AC① 落在该环）**：合并 / 裁决采纳成功后把 `lastSyncedDb` 前移到采纳后的活动树（与 `UploadedLocal` 各分支同口径），并补一条「合并上传后紧邻一次同步不得再产出 `UploadedLocal`/`MergedAndUploaded`」的行为断言。
- **AC⑤（回归与用例）**：新增用例**必须**覆盖「内存构造的 `ProtectedString`（`isProtected=true`）与解析同一份文件得到的实例在内容相同时不判为变更」且**双向**；§372 既有两例（`SyncColdStartBaselineMissingTest`）不得放宽或删除；全量 `test` 绿 + `python tools/doc/gate_readings.py` 全 PASS（读数块原样贴入批次文档）。
- **AC⑥（文档同步）**：若判据口径变更，`KdbxContentComparator` KDoc 与 `docs/architecture/已知工程限界.md` §10 同批更新（§122 立的「双向显式声明」纪律不得破）。

#### 边界（如实声明，不得读作已定性）

- 本条**只确证了判据的等值敏感性缺陷**（探针读数如上）；**用户本次症状具体落在哪一环尚未取证**（缺真机 verbose 行），故三环并列登记、修法各异，**不得**据本条直接推定某一行修复即闭环。
- 探针为**夹具级**（`create` + `saveEntry` 造字段）；生产写入面（`VaultEntryWriteCoordinator` / `VaultEntryMapper`）对标准五字段硬编码 `isProtected=false`、口令硬编码 `true`，**恰与库级默认 `MemoryProtection` 一致** ⇒ 环 A 在**默认配置库**上需要「本会话确有内存写入」才现形；**非默认 `MemoryProtection` 库**（导入自其它客户端）则无需任何编辑即可恒现——两者的取证判据见 AC①。
- 本条**未**改动任何生产代码 / 用例；登记与取证同会话完成（探针文件已删除，工作区无残留）。

> **近期闭环（指针）**：`ISSUE-P2-518` / `519` / `521` 已于 §463 闭环归档；`ISSUE-P2-520` 已于 §461 闭环；`ISSUE-P2-528`（换密后云端副本不被替换）已于 §467 闭环；`ISSUE-P2-530`（CM 通行密钥候选筛选与归属合一）已于 §470 闭环归档；`ISSUE-P2-529`（库文件级「保留副本」出口）已于 §471 闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

## P3 低危问题、特性接线与体验优化（0 项）

> **暂无开放项**：`ISSUE-P3-531`（常驻通知倒计时负数修复的设备侧对照走查）与 `ISSUE-P3-532`
> （QQ 登录页自动填充真机走查）两条纯验证条目经用户 **2026-10-08 真机走查回执通过**，
> 已于 §472 整条闭环归档，见 [`RESOLVED_LOG.md`](RESOLVED_LOG.md)。

