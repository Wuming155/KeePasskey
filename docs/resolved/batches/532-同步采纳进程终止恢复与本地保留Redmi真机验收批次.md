# §532 同步采纳进程终止恢复与本地保留 Redmi 真机验收批次

<a id="s532"></a>

## 1. 闭环范围

本批关闭 ISSUE-P2-621。同步采纳意图在正式 KDBX、缓存/basecache 和回滚高水位各阶段遇到普通 OS 进程终止后，冷启动仍能保留正式 B、拒绝远端旧 A；无 P2-627 探测记录时用户能在解锁页发现恢复状态，并能显式选择保留本地库、以当前远端 ETag 条件发布本地 B。

## 2. Redmi 4X API 37：正式文件与用户恢复

- 设备：Redmi 4X，serial `1c859bcc7d24`，API 37；设备结果见 `app/build/outputs/androidTest-results/connected/debug/TEST-Redmi 4X - 17.xml`，2/2 通过，退出码 0。
- `正式库B保存后高水位提交失败且协调器重建时旧远端A不能覆盖B`：受控 HTTPS 写入 B 后定点注入高水位提交失败；正式 KDBX 仍可重解析为 B。无探测记录的 pending marker 在实际解锁界面显示恢复提示；冷启动后重放 A 被 quarantine 拒绝，B 保全。
- 同一用例把远端先改为 C，恢复上传读取 ETag 后再次改变远端，条件写返回 412；C 与本地 B 均保留且 marker 不清除。随后显式选择保留本地，以最新 ETag 条件上传 B，恢复状态清除，A 仍被识别为历史版本。
- `上传A期间保存B进程重建后与他端C合并且远端不丢任何版本`：2/2 中的第二个用例验证上传期间本地 B 与他端 C 收敛，不丢任一版本。

## 3. Redmi 4X API 37：五阶段真实进程终止矩阵

`tools/device/run_p2_621_process_recovery.py --device 1c859bcc7d24` 使用生产 WebDAV 路径，在阶段回执落盘后由宿主对 KeePasskey 进程执行 SIGKILL；脚本核对被杀 PID 已退出且包未被 force-stop，再启动新进程恢复。每个阶段的非空 crash XML 记录阶段和 PID，配对 resume XML 为 1/1、failures=0、errors=0。

| 阶段 | Run ID | 冷启动恢复结果 |
|---|---|---|
| `INTENT_PERSISTED` | `2b7745cf9206` | `resumeAfterProcessRestart` 通过 |
| `CACHE_RECEIPT_COMMITTED` | `d42a67d95892` | `resumeAfterProcessRestart` 通过 |
| `BASE_METADATA_PERSISTED` | `0ff32fd01892` | `resumeAfterProcessRestart` 通过 |
| `BASE_CONTENT_PERSISTED` | `c156a9af3535` | `resumeAfterProcessRestart` 通过 |
| `HIGH_WATER_WRITE_STARTED` | `d33727c3c946` | `resumeAfterProcessRestart` 通过 |

每个恢复阶段重新打开正式 KDBX B、确认 A 重放被拒、确认恢复状态继续存在，并显式执行本地条件恢复上传。最新矩阵 XML 与 PID 回执位于 `.ci-artifacts/remediation-20261015/p2-621/`。

## 4. 边界

- 证明的是本地提交阶段后的进程终止/冷启动恢复和用户确认的“保留本地”路径；没有把普通对象重建或 force-stop 作为进程死亡证据。
- 远端请求由受控本地 HTTPS WebDAV 驱动，测试使用合成库与虚构凭据；不代表任意服务器兼容性结论。
- 本批不扩大至 P2-620 的凭据入口和 Autofill 请求边界。

## 5. 关联入口

- 整改方案：[同步采纳代际事务与失败隔离方案](../../architecture/问题整改方案/P2/ISSUE-P2-621/同步采纳代际事务与失败隔离方案.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
