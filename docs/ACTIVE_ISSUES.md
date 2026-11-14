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

暂无。

## P2 中危缺陷与协议/测试缺口

### 设置包既有红测试：生物识别超时错误的文案断言与生产映射不一致

- **核实时间 / 方式**：2026-11-14；扩大范围执行 settings 包定向 JVM 测试（过滤 `settings.*`，566 例、565 通过、1 失败）得到唯一失败用例 `BiometricEnableCoordinatorTest > 系统错误时开关保持关闭并映射失败文案`；静态定位 `app/src/test/java/com/keepasskey/app/ui/screens/settings/BiometricEnableCoordinatorTest.kt:110`、`app/src/main/java/com/keepasskey/app/ui/screens/settings/BiometricEnableCoordinator.kt:152` 与 `app/src/main/java/com/keepasskey/app/ui/screens/unlock/BiometricFailureMessagePolicy.kt:54`。
- **影响**：settings 包整体测试为红，会掩盖该包后续真实回归；生产行为本身自洽——`BiometricEnableCoordinator` 把 `BiometricResult.Error` 交给 `BiometricFailureMessagePolicy.of`，后者将 `BiometricAuthManager.ERROR_AUTH_TIMEOUT` 映射到 `R.string.sec_biometric_timeout`（`生物识别验证超时，请重试或改用主密码解锁`），与 `BiometricFailureMessagePolicyTest`、`UnlockViewModelBiometricAutoPromptTest` 及设备测试 `NotificationLockUnlockDeviceTest` 的既有断言一致；仅旧用例仍期望 `R.string.sec_biometric_auth_failed`（`生物识别验证未通过或已取消`）。
- **触发前提**：用例构造 `BiometricResult.Error(ERROR_AUTH_TIMEOUT, "AUTH_TIMEOUT")` 却断言兜底文案；属旧断言未随超时分档策略更新，非本轮自动填充 / 诊断日志改动引入。本轮未改动该测试与相关生产文件，该红测试不计入本轮验收。
- **处置 / 关闭条件**：单独确认「超时错误应展示哪个文案」的产品口径后，把旧断言对齐生产映射（或按口径调整映射并同步更新其余相关断言），再跑 `:app:testDebugUnitTest --tests "com.keepasskey.app.ui.screens.settings.BiometricEnableCoordinatorTest"` 与 settings 包确认全绿；关闭后从本表移除。

## P3 低危问题与体验改进

### 自动填充服务进程崩溃重启根因未定位

- **核实时间 / 方式**：2026-11-14；真机 logcat 取证（14:43:01 `W/ActivityManager: Scheduling restart of crashed service com.keepasskey/.app.autofill.KeePasskeyAutofillService`，随后新进程写入新的进程诊断头），取证描述留痕于已闭合 P1 条目的处置段（提交 `d2889312`）；与用户 release 诊断日志（15:14 / 15:25 导出）交叉核对，15:25 导出含两个进程诊断头，证实跨进程留存生效。未取得崩溃堆栈 / tombstone，未复现，未证实是代码缺陷。
- **影响**：崩溃会清空当时的内存日志缓冲并中断在途的填充 / 保存请求；诊断日志改落私有目录按周期留存后，跨进程链路可复盘，取证丢失这一后果已消除。未观察到凭据损坏、明文泄露或数据丢失。用户划掉应用、系统回收与未捕获异常均可产生同形事件，当前无法区分。
- **触发前提**：未定位；仅在 14:43 一次真机取证中观察到。
- **处置 / 关闭条件**：下次复现时导出诊断日志归档（进程诊断头 + 自动填充服务创建 / 连接断开 / 填充请求 / 销毁全链路打点），必要时取 `adb logcat` 崩溃堆栈或 tombstone，判定是系统回收 / 用户操作还是未捕获异常；确认为代码缺陷则按实际影响重新分级并修根因，确认为系统行为则从本表移除并说明依据。归档出现新进程头但无异常堆栈且前后链路完整时，按系统回收收口。
