# §530 恢复探测生产 Provider 真机验收批次

<a id="s530"></a>

## 1. 闭环范围

本批关闭 ISSUE-P3-628。恢复探测改为在持久化生产配置下通过统一 Provider 所有权/释放作用域访问 WebDAV，并在目标切换后丢弃迟到结果。

## 2. 实施与验收

- `ResumeSyncProbeCoordinator` 对回前台和 ConnectivityManager 默认网络回调共用生产探测路径；生产 Provider 在每条退出路径确定性释放，凭据副本按所有权清理。
- `ResumeSyncProbeCoordinatorProductionTest` 的生产配置记录型工厂用例通过，覆盖 `testSyncProvider == null`、ETag 变化、目标切换丢弃结果和 Provider 释放。
- Redmi 4X API 37 与隔离 API 36 AVD 首次默认网络 `onAvailable` 触发生产 HTTPS/ETag 提示各通过 1/1。
- Redmi 4X API 37 的主应用进程 HOME→MainActivity 前台恢复触发 HTTPS/ETag 更新通过 1/1；debug-only app-process harness 使用合成库、虚构凭据和仓库本地 HTTPS 证书，不写设备系统 CA。
- Redmi 4X API 37 真实关闭 Wi-Fi、确认断开、重新连接原保存 SSID 后，主应用进程注册的默认网络回调自动触发生产 HTTPS 并更新 ETag/提示，设备 XML 1/1 通过。
- Redmi 4X API 37 服务端延迟生产 HTTPS PROPFIND，在请求在途期间切换 `DatabaseSession` 至第二合成库，迟到目标结果不发布，设备 XML 1/1 通过。
- 设备 XML 与回执：`.ci-artifacts/remediation-20261015/p2-627/probe-foreground-resume-main-process-d4fa72cdfc54.xml`、`probe-network-transition-main-process-b107b5ad4b70.xml`、`probe-target-switch-ResumeSyncProbeProductionDeviceTest.xml`。

## 3. 限制

- 外部调用者归属、云端条目合并或下载不由本探测入口执行；探测只产生待同步提示，后续写操作仍由用户显式发起。
- debug-only 生命周期桥与 raw trust anchor 不进入 release 构建。

## 4. 关联入口

- 整改方案：[恢复探测生产 Provider 接线方案](../../architecture/问题整改方案/P3/ISSUE-P3-628/恢复探测生产Provider接线方案.md)
- 当前未闭环事项：[ACTIVE_ISSUES.md](../../ACTIVE_ISSUES.md)
