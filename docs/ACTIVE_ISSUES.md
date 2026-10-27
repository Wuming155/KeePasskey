# KeePasskey 当前未解决问题

本文件是当前缺陷、功能缺口和待办的唯一状态源，只保留尚未闭环事项。新条目须记录核实时间与方式；开始处理前重新核对涉及的路径、行为和前提。关闭事项后从本表移除，不在此追加进度或历史流水；完成任务不自动新建永久文档。

## 优先级

| 等级 | 范围 | 处理原则 |
|---|---|---|
| P0 | 数据损坏、明文泄露等阻塞级问题 | 立即优先处理 |
| P1 | 高风险缺陷或核心功能阻断 | 最高优先级 |
| P2 | 协议互操作、性能或测试有效性缺口 | 正常排期 |
| P3 | 低危问题、体验和渐进改进 | 渐进处理 |

## P0 阻塞级问题

暂无。

## P1 高危与核心功能问题

### ENG-20261027-01 自动锁定补存绕过外部修改检测

- **核实时间与方式**：2026-10-27，静态追踪加 Windows JVM 隔离探针；仅使用 `build/engineering-review-fixtures/` 中的虚构库。探针源码 `build/EngineeringLockProbe.java`，回执 `build/engineering-review-lock-probe.log`（本地产物，不入库）。
- **位置**：`app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt:146-156`。`preserveDirtySessionBeforeLock()` 对 DIRTY 会话直接调用 `databaseSession.save()`，不经过 `VaultWriteBoundary` 的漂移检查；保存成功亦不会进入独立恢复副本路径。
- **复现**：建立库并记录真实文件基线 → 内存新增未保存条目 → 用另一会话生成的合法 KDBX 覆盖正式文件并推进 mtime → 漂移协调器已能检出变化 → 调用生产 `triggerLock()`。结果为 `driftDetected=true outcome=Saved externalOverwritten=true state=LOCKED`，外部版本被覆盖。默认滚动备份可能保留被覆盖版本，但不会阻止静默覆盖。
- **尚未完成**：让锁定补存消费统一漂移判据；发现漂移时保全本地脏树到独立恢复副本并继续锁定，正式文件保持外部版本。补真实文件重解析断言及隔离设备上的熄屏/后台锁定验收。不得在锁定临界路径等待用户裁决或引入递归取锁。

### ENG-20261027-02 漂移重载失败前已擦除旧会话及未保存条目

- **核实时间与方式**：2026-10-27，调用链静态核实与 Windows JVM 真实文件探针；源码 `build/EngineeringReviewProbe.java`，回执 `build/engineering-review-probe.log`（本地产物，不入库）。
- **位置**：`app/src/main/java/com/keepasskey/app/data/repository/VaultFileDriftReload.kt:50-65`；`database/src/main/java/com/keepasskey/database/session/SessionOpener.kt:268-289`。重载直接调用 `open/openStream`，后者在解析前执行 `releaseCurrentSession()`。
- **复现**：打开库 → 添加未保存条目 → 正式文件变为损坏内容 → 以当前凭据执行同一重开入口。结果为 `Failure treeNull=true state=LOCKED oldEntryCleared=true`。与漂移协调器“重载失败保留内存改动”的契约冲突；该重载路径自身没有先保全草稿，已有历史恢复副本不能保证包含最新改动。
- **尚未完成**：候选读取、凭据验证与附件事务成功后才替换活动会话，失败保留原树及凭据；同时保证旧附件清理不误删新候选附件。补损坏文件、外部换密和读取异常的失败回滚验收，不仅测试成功重载。

## P2 中危缺陷与协议/测试缺口

### ENG-20261027-03 预备保存提交端未验证预备密文的凭据代次

- **核实时间与方式**：2026-10-27，Windows JVM 生产 `DatabaseSession` API 真实文件端到端探针；源码及回执同 `ENG-20261027-02`。
- **位置**：`database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt:254-310`。`PreparedSerializedSave` 已携带 `ticket`，但 `commitPreparedSave()` 未与当前 `core.credentialTicket` 比较；持久化基线修订参数允许为 null，换密本身不会替换数据库树实例。
- **复现**：按旧密码预备密文 → 成功换成新密码 → 以原树实例提交该预备产物、不传可选基线修订。结果 `rotation=Success commit=Success reopenNew=Failure reopenOld=Success`，提交把正式文件恢复为旧凭据密文，而会话仍缓存新凭据。
- **影响边界**：已复现的是公开会话 API 的提交契约缺口。生产漂移调用通常同时传基线修订，且 `VaultCrashDrafts.capturePrepared()` 有票据校验，这两道上层保护会拒绝常规过期产物；本轮未复现穿过全部上层保护的 UI 攻击路径，不将其描述为无条件可达的产品换密回退。
- **尚未完成**：提交锁内强制核对预备票据；预备阶段以独占凭据快照保证序列化不与换密/清零交错；拒绝时不改活动树及正式文件。补“预备 → 换密/改绑 → 提交”真实文件回归，覆盖省略基线参数的入口。

### ENG-20261027-04 同步缓存所谓进程内路径锁实际仅在单实例内共享

- **核实时间与方式**：2026-10-27，反射读取锁身份、确定性持锁探针与 Windows JVM 双实例真实文件并发；源码及回执同 `ENG-20261027-02`。随后精简后的全模块测试重跑在未改动的 `SyncCacheCrashRecoveryTest` 中实际出现字节与版本标记不一致，日志 `build/simplification-test.log`。
- **位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncCacheFiles.kt:133-135`；`sync/src/main/java/com/keepasskey/sync/engine/SyncCache.kt:133-150`；`sync/src/test/java/com/keepasskey/sync/engine/SyncCacheCrashRecoveryTest.kt:271-296`。
- **复现**：同目录、同 vaultScope、同 remotePath 的两个 `SyncCache` 拿到不同锁；持有 A 锁期间 B 写入仍完成。100 轮并发写后 9 轮 `.version` 与 `.cache` 实际 SHA-256 不一致。现有并发测试也每个协程新建实例，依赖竞争调度，不能证明锁有效。
- **影响边界**：`hasLocalChanges()` 已读取实际字节摘要，因此本发现本身不证明会把脏缓存误判为干净。生产同步周期另有 `SyncSessionState.mutex` 串行化；本轮证明的是缓存原语与“进程内互斥”声明不符，以及跨实例测试偶发失败风险。
- **尚未完成**：让同一规范目录/命名空间/路径共享协调锁，或收窄 API 所有权并强制单写者；审查流式写与接收回执提交是否需要同一边界。使用确定性门闩覆盖跨实例交错，不用重复概率测试代替互斥证明。

### ENG-20261027-05 当前 Android Lint 门禁失败

- **核实时间与方式**：2026-10-27，实跑 `./gradlew :app:lintDebug -x :crypto:cargoNdkBuild --console=plain`，退出码 1；精简后再跑标准 `./gradlew lint -x :crypto:cargoNdkBuild --console=plain`，app 仍以相同错误失败，四个库模块通过。报告 `app/build/reports/lint-results-debug.xml` / `.html`；日志 `build/engineering-review-lint.log` 与 `build/simplification-lint.log`（本地产物）。
- **结果**：1,594 个 Error、285 个 Warning、3 个 Hint；Error 为 1,593 个 `MissingTranslation` 与 1 个 `UnusedMaterial3ScaffoldPaddingParameter`。
- **位置与判定**：`app/src/main/res/values-zh/create_vault_safety.xml` 仅提供 3 项，默认 `values/` 已是中文但未声明默认资源语言，Lint 仍将其它字符串判为缺少 zh 翻译；这不等同于运行时缺字。`app/src/debug/java/com/keepasskey/app/ui/VaultGroupFeedbackDeviceHostActivity.kt:82` 忽略 Scaffold 内边距。CI fast-gate 包含 Lint，因此当前不能称为全门禁通过；本轮未查询远端 Actions 状态。
- **尚未完成**：统一默认中文资源与语言限定目录的约定，正确配置资源语言语义；修正测试宿主布局，再实跑项目标准 `lint` 并确认零错误。警告应按影响分类，不逐条机械修复或整体屏蔽检测。

## P3 低危问题与体验改进

暂无。
