# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

**核实口径**：各条以条目内注明的核实时间与方式为准；静态定位、宿主行为复现和设备/真实服务端验收分别标明，不相互替代。运行探针使用虚构数据；临时探针已清理，不作为永久复现资产。同类项目只提供比较背景，不作为本仓缺陷成立或必须删除防护的依据。编号保持原有身份，不因移除误报或调整优先级重新编号。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能、测试有效性缺口及需验证影响的安全问题 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

分级按当前已确认的影响与触发条件确定；尚未完成的攻击链或性能测量不能当作既成结果。

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

### ENG-20261111-02 移动目标分组失效时静默删除条目并保存成功

- **核实时间与方式**：2026-11-11，基于 `a5f61dd9` 静态追踪详情页、批量移动、仓库及会话调用链；宿主 JVM 使用虚构库执行创建条目和目标组 → 删除目标组 → 用原目标 UUID 移动 → 保存 → 关闭重开，保存与重开均成功，但原条目不存在。`ReviewCloneProbeTest.staleMoveTargetMustNotDeleteEntry` 在重开后的存在性断言失败；本地临时证据为 `build/review-probes/results/TEST-com.keepasskey.app.data.repository.ReviewCloneProbeTest.xml`，不是永久资产。未实跑设备上的并发 UI 时序。
- **位置**：`database/src/main/java/com/keepasskey/database/session/SessionContentMutations.kt:328-351`；`SessionTreeEditor.kt:24-34`；`app/src/main/java/com/keepasskey/app/data/repository/VaultGroupCoordinator.kt:112-117`。
- **问题与影响**：`batchMoveEntries` 未在会话锁内核验目标组，先 `removeEntries`，再逐条插入；目标 UUID 已不存在时，`updateOrAddEntry` 返回已经摘除条目的树，不报告失败。仓库随后仍执行 `persistSession()`，详情页与多选操作均可显示移动成功。触发前提是收到失效的目标 UUID，例如用户选择后、协程真正取得写锁前目标被同步移除；受影响的是本次选中条目，结果不只是 UI 暂时不可见，而是可落盘的数据丢失。编辑页的 `saveEntryInGroup` 已有目标核验，但批量通道（也供详情页单条移动使用）没有复用该保护。
- **关闭条件**：在同一写锁内、摘除之前核验目标；目标失效须零修改并向仓库/UI 返回失败。复用现有移动测试覆盖失效目标、根目录目标和正常分组目标，并验证失败后保存重开仍保留全部原条目。

## P2 中危缺陷与协议/测试缺口

### ENG-20261111-03 合并把根目录条目误判为失链并移回旧分组

- **核实时间与方式**：2026-11-11，`a5f61dd9` 源码调用链与宿主 JVM 公共入口 `KdbxMerger.mergeDatabases` 复现。基线条目位于子组，本地移至根组并保留指向原组的 `previousParentGroup`，远端条目未改；合并后条目回到原子组。`ReviewMergeProbeTest.moveToRootMustSurviveMerge` 在父组断言失败。本地临时证据为 `build/review-probes/results/TEST-com.keepasskey.sync.merge.ReviewMergeProbeTest.xml`；未做真实服务端同步验收。
- **位置**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt:160`、`KdbxEntryMerger.kt:158-166`。
- **问题与影响**：`sanitizedGroups` 刻意不含根组，但 `assignEntriesToGroups` 仅用其 `containsKey(pid)` 判断父组有效性，合法的 `pid == rootId` 也进入失链回退。如果 `previousParentGroup` 仍指向存活子组，就覆盖正确的根目录归属。触发须同时满足进入三方合并、条目位于根目录、旧父组引用仍有效；没有旧父组引用的根目录条目不受此问题影响。XML 解析会保留该字段，不能假设所有输入都为空。
- **关闭条件**：将根组明确纳入有效父组判据，仅对真正失链的父组执行旧父组回退；补充有/无旧父组引用的根目录合并与真实失链回退测试。

### ENG-20261111-04 两端都删除的附件在三方合并后重新出现

- **核实时间与方式**：2026-11-11，`a5f61dd9` 静态追踪 `SyncConflictController.handleConflictMerge` → `autoMergeAndUpload`，并在宿主 JVM 调用 `KdbxMerger.mergeDatabases`：共同基线有一个虚构附件，两侧当前附件列表均为空，合并产物却重新含有该附件。`ReviewMergeProbeTest.attachmentDeletedByBothSidesMustStayDeleted` 失败。临时证据同 `build/review-probes/results/TEST-com.keepasskey.sync.merge.ReviewMergeProbeTest.xml`；未做真实服务端同步验收。
- **位置**：`sync/src/main/java/com/keepasskey/sync/merge/EntryMergeHelpers.kt:19-29`。
- **问题与影响**：两侧附件均相对基线改变时进入兜底分支，先把基线附件加入名称映射，再用远端、本地覆盖；缺失的名称从不移除，因此双方一致删除也不能生效。默认自动合并路径直接采用该产物进行序列化、上传和本地提交，使用户已经删除的附件复活；这不涉及历史快照中合法保留的旧附件，而是当前条目的附件列表错误。
- **关闭条件**：按附件名称及三方存在性/内容处理删除，至少保证双方一致删除保持删除；复用已有合并测试验证单侧删除、双方删除、不同名称独立修改及同名内容分歧，避免基线无条件并集。

### ENG-20261111-05 根分组普通属性不参与三方合并，远端改动被丢弃

- **核实时间与方式**：2026-11-11，`a5f61dd9` 静态复核根组排除与重建路径；宿主 JVM 以相同根 UUID、未变本地根组及较新远端根组名称调用公共合并入口，结果仍为本地旧名称。`ReviewMergeProbeTest.remoteRootRenameMustSurviveMerge` 失败；临时证据同 `build/review-probes/results/TEST-com.keepasskey.sync.merge.ReviewMergeProbeTest.xml`。设备与真实服务端未验收。
- **位置**：`sync/src/main/java/com/keepasskey/sync/merge/KdbxMerger.kt:160`；`KdbxGroupMerger.kt:211-224`。
- **问题与影响**：根组不经过子组的字段合并和冲突收集，组装时只在 `localRoot` 上替换时间及 `customData`，远端单侧改动的名称、备注、图标等普通组属性被静默丢弃；双侧分歧也不会产生根组冲突。发生于进入三方合并的路径，例如本地改条目、远端改根组。这与保持根 UUID 或本地加密配置不变是不同问题，也不能由库级 Meta 的 `databaseName` 合并替代。
- **关闭条件**：保留根身份及树组装语义，同时对根组普通属性执行相应字段合并/冲突处理；补充根组单侧变更、双侧独立变更和同字段分歧的行为覆盖。

### ENG-20261111-06 复制条目会清除有效期开关和到期时间

- **核实时间与方式**：2026-11-11，`a5f61dd9` 静态追踪 `duplicateEntry` → `toFreshClone` → 保存；宿主 JVM 调用生产克隆函数，源条目设置 `expires=true` 及固定到期时间，副本变为 `expires=false`。`ReviewCloneProbeTest.cloneMustPreserveExpiry` 在有效期开关断言失败，临时证据为 `build/review-probes/results/TEST-com.keepasskey.app.data.repository.ReviewCloneProbeTest.xml`。已有 `RealVaultRepositoryTest` 的复制持久化用例未设置有效期，不能覆盖本问题。
- **位置**：`app/src/main/java/com/keepasskey/app/data/repository/EntryDuplicateCoordinator.kt:71-75`；`core/src/main/java/com/keepasskey/core/model/KdbxTimes.kt:8-16`。
- **问题与影响**：克隆以 `KdbxTimes(creationTime = now)` 替换整个时间对象，不仅刷新创建/修改/访问时间，还把 `expires` 恢复为默认 `false`、`expiryTime` 改成当前时间。复制带有效期或已过期的条目，会无提示地生成永不过期的副本，丢失原有有效期约束。
- **关闭条件**：仅重置新条目生命周期需要重置的时间/计数，保留有效期开关及到期时间；扩展现有复制条目测试，在保存重开后验证未来到期、已过期和永不过期三种情况。

## P3 低危问题与体验改进

### ENG-20261111-01 Android DOM 加固特性无效调用与告警噪音

- **核实时间与方式**：2026-11-11，真机 M332BF / Android API 37 / arm64-v8a / release 0.1.0 的可导出诊断日志与 `adb logcat` 交叉核对（16:05 失败周期与 17:45 成功周期均出现同一组 5 条告警）；源码静态复核 `sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt:193-275`、`settings.gradle.kts`、`sync/src/test` 与 `sync/src/androidTest/java/com/keepasskey/sync/webdav/WebDavPropfindParserDeviceTest.kt:45,70`。未改动代码，未构造删除后的复现。
- **位置**：`sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt:211-217`（`applyXxeGuardFeature`）、`:226-245`（`hardenedFactories` 初始化）、`:255-261`（`applyXxeGuardSetting`）、`:263-275`（`newHardenedBuilder`）；`app/src/main/java/com/keepasskey/app/data/logger/DiagnosticLogRedactor.kt:35,41`（脱敏误伤）。
- **问题**：`hardenedFactories` 按线程初始化时逐项设置 4 个 Xerces/SAX 特性与 `setXIncludeAware(false)`，Android 平台实现 `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl` 分别抛 `ParserConfigurationException` / `UnsupportedOperationException`，容错后只落 WARN；第 6 项 `isExpandEntityReferences = false` 未抛异常、被平台接受。本仓只交付 Android APK（`settings.gradle.kts` 五个模块均为 Android 模块，模块外无 Kotlin/JVM 运行时源码，`sync/src/test` 无 XXE/DOCTYPE 用例），桌面 JVM 仅为单测执行环境，故这 5 项尝试在运行时零效果，却在每个工作线程首次建工厂时抛异常、采集栈帧并写入可导出诊断缓冲；导出的 `<redacted-url>` / `<redacted-file>` 还把 feature URL 与异常类名误判脱敏。Android 上真正有效的外部实体防线是 `EntityResolver`（空输入兑现），由设备测试哨兵验证，不依赖上述特性。
- **影响边界**：诊断日志 5 条重复告警与无谓的异常/栈帧开销；不影响同步结果（同一组告警同时出现在 `Error` 与 `MergedAndUploaded` 两份日志中），无已知安全缺口。删除时须注意 `isExpandEntityReferences = false` 是被接受的设置，直接删除会把工厂状态从 `false` 改回默认 `true`，属于行为变化而非清理无效代码。
- **尚未完成**：删掉 5 个注定抛异常的调用与两个容错包装函数，保留 `isExpandEntityReferences = false` 裸赋值以维持工厂状态；同步更新 `WebDavPropfindParser.kt` 与 `WebDavPropfindParserDeviceTest.kt` 的过时注释；真机跑 `WebDavPropfindParserDeviceTest`（XXE 哨兵 + 合法报文）与 `:sync:testDebugUnitTest` 全量通过，确认 `EntityResolver` 仍兜住外部实体后移除本条。可选：修正 `DiagnosticLogRedactor` 对异常类型行的豁免。
