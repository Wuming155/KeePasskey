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

### 结构树非文本值导致填充失败，以及保存后无法自动召回

- **核实时间 / 方式**：2026-11-14；用户 release 诊断日志（Android API 37，13:19:49 导出）中 6 条 `onFillRequest 发生异常` 堆栈为 `AutofillValue.getTextValue` → `Preconditions.checkState` → `IllegalStateException`，静态定位 `AutofillStructureScan.kt` 无条件读 `textValue`；14:11:42 复验日志已无该异常，但用户反馈清除数据后重新登录仍无候选、无保存询问，代码复核发现传统 Autofill 保存链落库后未写「调用方首次绑定」。JVM autofill 包 346 例全绿；设备侧平台契约用例在专用 AVD 实跑（真实 `AutofillValue` 四类、`FillResponse.build()` 构造约束）。
- **影响**：`traverseViewNode` 对每个节点无条件调 `getTextValue()`，非 TEXT（TOGGLE / LIST / DATE）即抛异常，被 `onFillRequest` 兜底为 `callback.onFailure`，FillResponse 整体不下发 ⇒ 候选、选择器、SaveInfo 全缺。此外 `onSaveRequest` 与 `AutofillLockedSaveActivity` 落库成功后不写 `AutofillCallerTrustStore`，落库条目的 `android://<包名>` 仍被 P2-46 绑定门挡住，用户同意保存后下次依旧拿不到自动候选。清除应用数据会同时清掉本地信任与互动记忆，旧条目同样无法召回。
- **触发前提**：宿主表单结构树出现非 TEXT `AutofillValue` 节点；清除数据 / 重装 / 换设备后本地绑定与记忆丢失。两处均属既有健壮性缺口，非本轮代码回归。
- **处置**：`resolveAutofillNodeText` 按 `AutofillValue.isText()` 判型，非 TEXT 回退 `ViewNode.getText()`；库锁定态 `FillResponse` 同样注册 SaveInfo；两条传统 Autofill 保存路径落库成功后按 CM 通道同口径写调用方首次绑定（签名摘要不可读即 fail-closed）；填充目标识别、SaveInfo 注册、保存请求入口与绑定结果补 release 可见的无标识诊断日志；关联询问否决亦留痕。诊断日志系统补两层可观测：进程审计头写入系统自动填充状态与装机时间（区分「系统未启用本服务」与「同版本旧包」），服务实例创建 / 系统连接断开 / 填充请求入口 / 销毁全链路打点，用于区分「服务未绑定 / 请求未到达 / 目标未识别」。真机 logcat 取证另证实：服务确被调用且 `SaveInfo 已注册`，但 14:43:01 服务进程崩溃重启导致缓冲清空；导出日志看不到自动填充事件的主因是 `AppLog.i` 纯文案只写 logcat、未接进可导出缓冲。15:14 日志（通道修复后）确证：本次走的是「解锁引导 → 手动选择器选条目」，`android:// 维度首次绑定写入结果=true`，但「应用关联询问条件不满足」被跳过、条目网址未写回，且整份日志无 `收到系统保存请求`——即系统保存流程未被触发。现由 core 行出口把纯文案统一接入 `DebugLogBuffer`（过滤 `KpLog` 回显防回环），并把已脱敏行按设置页周期（默认 3 天，可选 1 / 3 / 7 / 30 天）留存于私有目录，导出正文直接取归档、超期行在启动与导出时裁剪，隐私政策 §5 同步更新。另修「下次直接出候选」的本地记忆链：选择器写入关联记忆改用与召回侧同源的归属校验后域（原用表单自报域，校验不通过时键会错开导致记忆静默失效）；记忆写入 / 命中与询问否决原因均改为可导出的 INFO 行，否决原因逐项写明（只读 / 包名 / 摘要 / 该包名已绑定），不含任何标识。随库持久通道按 KeePassDX 口径改写：交付成功后的关联询问不再要求空 URL / 纯 App 表单，同意后经字段级写入器把 `AndroidApp` / `AndroidApp Signature`（签名受保护、冒号分隔大写 hex、连续编号、可绑多个包名）写入条目自定义字段，`URL` 原样保留；候选打分新增该字段序列的读取并仍受 P2-46「包名 + 签名」首次绑定门约束；保存链新建纯 App 条目同时写该字段、upsert 去重兼容 URL 旧形态与字段两种形态，CM 通道所依赖的 `android://` URL 形态保持不变。
- **关闭条件**：用户真机复验——在**已有网址**的条目上经手动选择器交付后弹出「记住这个应用？」，同意后条目可见 `AndroidApp` 字段、`URL` 原样保留，下次该应用直接出候选；清除数据 / 重装 / 换设备（库同步回来）后关联仍有效；诊断日志不再出现 `onFillRequest 发生异常`，且可见 SaveInfo 注册与保存请求记录。自动验收须同时满足：JVM 定向用例全绿、设备侧 `connectedDebugAndroidTest` 实跑且结果非空、独立实现（pykeepass / keepassxc-cli）对拍 `AndroidApp` / `AndroidApp Signature` 的值与保护位并按 KeePassDX 包名 + 签名口径匹配、`:app:assembleDebug` 与 `:app:assembleRelease` 及文档门禁通过。全部通过即移除本条；若清除数据后系统自动填充服务被重置，需用户在系统设置重新选择 KeePasskey 再复验。

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
- **处置**：待产品裁决。候选方案：① 确认页加「改用其它条目」按钮链现有选择器（保留一键填充、改动集中在确认页与解锁页 extras 透传）；② 改 PD-05 路由，该调用方在库内关联账号 >1 时一律先进选择器（行为最一致，但需单点定义「关联账号数」口径且多账号用户每次多一步）；③ 维持现状，靠取消后重新聚焦换账号。
- **关闭条件**：用户真机复验——同一应用多账号场景下解锁后能明确选择要填充的账号，且不存在「未察觉即填入另一账号」的路径；判定有 JVM 用例覆盖，涉及 androidTest 时实跑且结果非空。
