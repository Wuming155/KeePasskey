# ISSUE-P2-626：同步检查点 IO 调度与读盘削减方案

> 编制日期：2026-10-15（Asia/Shanghai）；源码基线：`4cfedc840d8cec67d7ba9db594de3b14c9355d0d`。
> 状态：部分实施，仍开放。摘要及文件类型检查已移至 IO；API 37 Redmi 4X StrictMode 用例 1/1 通过。大库读盘量、帧延迟和低速存储耗时未测量。

## 1. 问题与依据

当前登记为 P2、置信度高。`VaultListViewModel` 将 `viewModelScope` 交给 `VaultListSyncController`；前台成功同步返回后，[SyncCycleRunner.kt](../../../../../app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt) 调用普通 `record()`。[SyncLocalFileCheckpoint.kt](../../../../../app/src/main/java/com/keepasskey/app/sync/SyncLocalFileCheckpoint.kt) 的 `digestOf()` 在调用线程用 FileInputStream/SHA-256 遍历整库，装配段的 IO 包装没有覆盖这个收尾。

大库/慢存储可能阻塞 UI；不能据静态调用链宣称已发生 ANR，也不能外推 Worker 在主线程。既有 `SyncAssemblyOffMainThreadTest` 仅覆盖装配段，不能充当收尾线程证据。

## 2. 目标与边界

检查点 API 自行保障文件读取/校验的调度边界，前台调用者无需额外包装。优先复用 P2-624 的可信写入回执摘要，避免整库重复读盘；保留回退读盘时仍须验证相同修订。线程改善与版本正确性分别验收。

## 3. 实施步骤与失败处理

1. 将检查点记录改为明确的 suspend API，注入 IO dispatcher；内部包含文件读取与必要文件元数据操作，禁止只在某个 UI 调用方套 IO。现有测试可注入受控 dispatcher。
2. 接入 P2-624 的库/修订/目标绑定回执；已有可信摘要直接登记，不为“确认”再读整库。缺少回执时，IO 读盘与数据库提交同步，结果经过修订复核才发布。
3. 读取循环按块支持协作取消并确保流关闭。取消/IO 异常不发布半截摘要或沿用未经验证的旧检查点；同步网络已成功的事实与检查点未登记分开表示。
4. 限制并发取样与多次成功收尾的重复读取，按同一修订合并工作；旧任务结束时若新库/新修订已生效则丢弃旧结果。
5. 对前台成功、无变化、下载采纳、合并及异常收尾逐一核对，保证没有其他直接调用 `digestOf` 的同步主线程旁路。不能用删除线程断言或放宽 StrictMode 规避问题。

## 4. 验收矩阵

| 场景 | 必须观察的结果 |
|---|---|
| 从 Main dispatcher 发起成功同步 | 摘要读探针在 IO 执行；主线程 StrictMode 不出现本路径磁盘读取 |
| 可信回执摘要可用 | 收尾额外整库读取为 0；修订错误回执被拒绝 |
| 回退读盘与用户保存交错 | 无错误登记，结果遵守 P2-624 的版本绑定 |
| 大库/慢存储、连续同步 | 记录收尾读字节数、耗时与 UI 帧延迟；与同夹具基线对比 |
| 取消、读失败、换库 | 流关闭，无半摘要发布，正式库不变且后续可重试 |

扩展 `SyncAssemblyOffMainThreadTest` 或新增专用 checkpoint 调度测试，使断言覆盖成功收尾；不能仅断言使用了某个 dispatcher 名称。设备测量需记录库大小、设备、存储条件、冷/热读及帧数据，不预先虚构 ANR/功耗收益。

## 5. 顺序、交付与回退

与 P2-624 同批完成 API 变更和版本绑定，之后再以测量决定是否需要额外摘要优化。JVM 检查调度/取消，AVD 检查真实 StrictMode 和大库交互。

若摘要复用回退，允许恢复 IO 上稳定读盘；不可恢复主线程整库读取或取消版本校验。避免为了短期性能把未经证明的 mtime/长度当成内容摘要。

## 6. 关联入口

- [当前登记](../../../../ACTIVE_ISSUES.md)、[方案索引与共同验收约定](../../README.md)。
- [工程规则](../../../../../.codebuddy/rules/engineering-rules.md)、[模块架构](../../../ARCHITECTURE.md)。
