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

### 唯一候选时确认页缺少「改用其它条目」入口（多账号应用）

- **核实时间 / 方式**：2026-11-14；用户真机描述（QQ 类多账号应用：解锁后自动匹配到一条并弹指纹确认），静态定位 `AutofillUnlockRouter.kt:62-73`（`singleOrNull()` 才走 CONFIRM）、`AutofillConfirmManualScreen.kt:62-75`（手动确认页仅「确认 / 取消」，生物识别路径为系统弹窗）、`KeePasskeyAutofillService.kt:294-305`（已解锁态恒有「搜索全部条目」兜底入口）。设备侧交互未复现。
- **影响**：多个账号条目都声明关联时候选 ≥2，会进选择器让用户选；但若只有其中一个账号绑定了 `AndroidApp`（典型：用户只给常用账号点过「记住这个应用」），解锁后只匹配到 1 条 ⇒ 直接弹确认页，指纹 / 手动确认后填这一条，页面内没有换账号入口。用户需取消 → 重新聚焦输入框 → 从「搜索全部条目」进选择器，入口不可发现，容易在未察觉的情况下填入另一个账号。确认成功后写入「上次填充」与关联记忆（`AutofillConfirmActivity.kt:236-245`），下次该条置顶，进一步强化该倾向。
- **触发前提**：同一应用存在多个账号条目，且本轮只有一条能通过匹配准入。
- **处置**：按 KeePassDX 口径取消锁定态解锁后的「唯一强匹配直达确认页」捷径（PD-05 候选 C），使两条路径统一回传候选列表。`AutofillUnlockActivity` 解锁成功后不再经 `AutofillUnlockRouter.route` 分 CONFIRM / PICKER，改为把 `resolveRouting` 现算的**全部命中条目**各装配一个仍带 `setAuthentication` 的候选 Dataset，并恒追加「搜索全部条目」入口，组装成 `FillResponse` 经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传；候选解析与已解锁态同源（本次签名摘要快照必须下传，不得回退为 `EMPTY`），P2-46 首次绑定门、字段级屏蔽、库锁定复核与二次确认一律不放宽。同步改写 `AutofillUnlockRouter` 的 PD-05 候选 C 口径说明与真值表，退役 CONFIRM 分支及其接线用例（`AutofillUnlockRouteWiringTest`），避免两套口径并存。依据：`参考项目/KeePassDX/.../KeeAutofillService.kt:161-205`、`SearchHelper.kt:139-174`（返回全部命中，上限 1000）、`AutofillHelper.kt:454-547,560-583`（每条一个 Dataset、默认开启的「手动选择」入口、完整 `FillResponse` 作认证结果）、`AutofillLauncherViewModel.kt:107-142`（库未打开时解锁后仍回候选列表）。本方案让锁定态与已解锁态行为一致、多账号必然可选、单账号仍只需点一下候选且少一次指纹；代价是改动 PD-05 裁决与解锁页组装逻辑，且框架对 `FillResponse` 认证结果的行为须在本仓真机实测，不外推同类项目结论。
- **关闭条件**：用户真机复验——同一应用多账号场景下解锁后能明确选择要填充的账号，且不存在「未察觉即填入另一账号」的路径；锁定态解锁后与已解锁态呈现同一候选列表口径，每个候选仍带二次确认；判定有 JVM 用例覆盖，涉及 androidTest 时实跑且结果非空；PD-05 候选 C 的口径说明与失效用例同步退役。

### 自动填充服务进程崩溃重启根因未定位

- **核实时间 / 方式**：2026-11-14；真机 logcat 取证（14:43:01 `W/ActivityManager: Scheduling restart of crashed service com.keepasskey/.app.autofill.KeePasskeyAutofillService`，随后新进程写入新的进程诊断头），取证描述留痕于已闭合 P1 条目的处置段（提交 `d2889312`）；与用户 release 诊断日志（15:14 / 15:25 导出）交叉核对，15:25 导出含两个进程诊断头，证实跨进程留存生效。未取得崩溃堆栈 / tombstone，未复现，未证实是代码缺陷。
- **影响**：崩溃会清空当时的内存日志缓冲并中断在途的填充 / 保存请求；诊断日志改落私有目录按周期留存后，跨进程链路可复盘，取证丢失这一后果已消除。未观察到凭据损坏、明文泄露或数据丢失。用户划掉应用、系统回收与未捕获异常均可产生同形事件，当前无法区分。
- **触发前提**：未定位；仅在 14:43 一次真机取证中观察到。
- **处置 / 关闭条件**：下次复现时导出诊断日志归档（进程诊断头 + 自动填充服务创建 / 连接断开 / 填充请求 / 销毁全链路打点），必要时取 `adb logcat` 崩溃堆栈或 tombstone，判定是系统回收 / 用户操作还是未捕获异常；确认为代码缺陷则按实际影响重新分级并修根因，确认为系统行为则从本表移除并说明依据。归档出现新进程头但无异常堆栈且前后链路完整时，按系统回收收口。
