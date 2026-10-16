# §531 锁态周期同步恢复提示 Redmi 真机验收批次

<a id="s531"></a>

## 1. 闭环范围

本批关闭 ISSUE-P2-627。锁态周期同步遇到无法安全自动覆盖的队列状态后，恢复提示可在冷启后的解锁界面发现；用户解锁并手动同步后，提示撤下且正式库 B 与远端 B 保全。

P2-621 的独立采纳阶段进程终止、正式 KDBX 文件事务对账和无探测记录的采纳意图恢复仍单独开放，不以本批 UI 验收代替。

## 2. Redmi 4X API 37 验收

- 设备：Redmi 4X，serial `1c859bcc7d24`，API 37；运行 `tools/device/run_p2_627_locked_queue.py --device 1c859bcc7d24 --tamper-pending-queue-before-worker`。
- 准备阶段 `tampered-queue-prepare-PeriodicSyncLockedQueueColdStartDeviceTest.xml`：1/1 通过，构造合成库 revision B 并持久化待传密文队列。
- 恢复阶段 `tampered-queue-resume-PeriodicSyncLockedQueueColdStartDeviceTest.xml`：1/1 通过。损坏队列被拒绝且不覆盖远端；恢复状态查询显示待处理；通过实际 MainActivity 前台根节点断言解锁页出现恢复提示；打开正式本地库确认 revision B；执行手动同步后恢复提示清除，并从生产 HTTPS 服务端重新下载、解密确认正式 revision B 保留。
- 本次恢复 XML 时间为 2026-10-16 02:42:05 +0800，instrumentation 用例耗时 91.079 秒。该路径使用仓库本地受控 HTTPS、debug 测试变体和合成数据。
- 设备收尾复核：Wi-Fi 已恢复连接原保存 SSID `CMCC-UYxh`；`secure/autofill_service` 为原值 `null`；仅 `com.keepasskey.test` 既有包仍安装，测试 `.debug` 包已由 harness 清理。

## 3. 边界

- 本批证明 P2-627 的待解锁状态能被用户发现并通过显式完整同步收敛；不证明锁定态持有主密钥，也不自动采纳服务端 KDBX。
- P2-621 的正式文件提交阶段崩溃恢复及缺少 P2-627 探测记录时的意图处置尚未完成，继续由 ISSUE-P2-621 跟踪。

## 4. 关联入口

- 整改方案：[锁定态周期同步分层与持久化任务方案](../../architecture/问题整改方案/P2/ISSUE-P2-627/锁定态周期同步分层与持久化任务方案.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
