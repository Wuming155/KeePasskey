# §142 选择器路径会话授权宽限与 TOTP 偏好兑现批次（2026-09）

**条目**：`ISSUE-P3-185` + `ISSUE-P3-186`（两条同源——均为 2026-09-17「查找坏交互」填充全链路逐环节通读的产出，
缺陷面同在手动选择器填充路径 `AutofillPickerActivity`，故同批整改）。

## 142.0 原文收录（`ACTIVE_ISSUES.md` 条目正文，原样剪切）

> ### ISSUE-P3-185 手动选择器填充路径不复用会话授权宽限（30 秒内重复填充仍强制二次确认）
>
> - **背景**：`ISSUE-P3-42` 的会话授权宽限（`autofillSessionGrant`，30 秒 TTL，默认关闭）设计意图是「同一站点 / 应用
>   的重复填充免二次确认」，但其消费点**只有数据集路径**一处——`AutofillDatasetBuilders.appendUnlockedDatasets`
>   查询 `AutofillSessionGrants.isGranted`（`AutofillDatasetBuilders.kt:198-211`）决定是否跳过
>   `AutofillConfirmActivity`；写入点在 `AutofillConfirmActivity.completeAuthResult`（`AutofillConfirmActivity.kt:268-274`）。
>   而手动选择器路径 `AutofillPickerActivity.confirmAndFill`（`AutofillPickerActivity.kt:148-185`）**既不查询也不写入**
>   会话授权：从选择器每次填充都无条件走全量生物识别（有认证器时）；且经选择器填充成功也不产生授权记录，
>   30 秒内改走数据集路径同样免不了确认。
> - **影响**：同一「同站 30 秒内重复填充」的用户意图，两条路径行为分叉；选择器本就是更费操作的兜底路径
>   （搜索 + 点选），宽限红利却完全不可得，设置页承诺在选择器路径静默落空。
> - **整改方向**：`confirmAndFill` 在发起生物识别前查询 `AutofillSessionGrants.isGranted`（开关开启 + 库解锁 +
>   授权有效 → 跳过本次生物识别直接 `deliver`）；`deliver` 成功路径同样按确认页同口径写入
>   `AutofillSessionGrants.grant`（`AutofillGrantContext`：包名 + 归属校验后域）。**不得**放宽任何放行判定：
>   宽限豁免的仅是「重复二次确认」，不改变首次绑定写入（P2-46）、黑名单复核与字段 id 回传语义。
> - **验收标准**：① 开关开启时，选择器填充后 30 秒内同「包名 + 域」再次经选择器填充不再弹生物识别，
>   TTL 过期后恢复弹窗（可按 `AutofillSessionGrantStoreTest` 同口径单测）；② 开关关闭时行为与现状完全一致；
>   ③ 库锁定态不走宽限（与 `AutofillAuthenticationPolicy` 现有守卫一致）；④ 既有
>   `AutofillSessionGrantStoreTest` / `AutofillPickerViewModelSessionLockTest` 全绿。
> - **核实时间点与方式**：2026-09-17 全仓 grep `AutofillSessionGrants\.(grant|isGranted)`——仅
>   `AutofillDatasetBuilders.kt:199`（消费）与 `AutofillConfirmActivity.kt:270`（写入）两处命中，
>   `AutofillPickerActivity` 无任何引用。
> - **风险提示**：宽限豁免与 TASK-11（P2-24）的「每数据集二次确认」安全整改存在张力，实现须严格复用
>   既有 `AutofillGrantContext` 判定口径，不得自造更宽的匹配键。
>
> ### ISSUE-P3-186 手动选择器填充路径不兑现「填充后自动复制 TOTP / 验证码通知」偏好
>
> - **背景**：设置页承诺「填充后自动将 TOTP 动态码复制到剪贴板 / 发送验证码通知」（`ISSUE-P3-03` 43b /
>   `ISSUE-P3-18`），其唯一落点是 `AutofillConfirmActivity.handleTotpAfterConfirm`
>   （`AutofillConfirmActivity.kt:374-405`，500ms 硬超时 + 双开关闸门 + 库锁定不触碰）。而手动选择器路径
>   `AutofillPickerActivity.deliver`（`AutofillPickerActivity.kt:187-213`）构造数据集回传后直接结束，
>   **无任何 TOTP 处理**——经选择器填充带 TOTP 的条目后，用户仍须回到应用手动复制验证码，偏好被静默打折。
> - **整改方向**：把「填充交付成功 → TOTP 二次动作」收敛为一份共用实现（复用 `AutofillTotpCopyPolicy` +
>   `ClipboardSecurityManager.copySensitiveText` + `TotpNotificationPublisher.publish`），
>   `AutofillPickerActivity.deliver` 在回传前同样调用；沿用 500ms 硬超时与「开关关闭 / 无 TOTP / 库锁定
>   不触碰」守卫，任何异常 / 超时不得阻断填充回传。
> - **验收标准**：① 开关开启时选择器路径填充带 TOTP 条目后剪贴板为该条目当前验证码（受保护剪贴板 +
>   定时擦除）；② 两开关皆关时零额外开销、零副作用；③ 单测覆盖共用实现的策略判定与超时放弃路径；
>   ④ `EntryDetailTotpCopyTest` 等既有 TOTP 用例全绿。
> - **核实时间点与方式**：2026-09-17 通读 `AutofillPickerActivity` 全文（`deliver` 无 TOTP 引用），并 grep
>   `handleTotpAfterConfirm` / `AutofillTotpCopyPolicy` 确认唯一消费点在确认页。
> - **风险提示**：`deliver` 处于回传 `RESULT_OK` 前的临界区，TOTP 动作必须保持硬超时兜底（超时即放弃），
>   不得拖住回传；通知发布须沿用确认页同一双闸门（偏好 + 通知权限）。

## 142.1 开工前前提复核

- **P3-185 前提成立**：全仓 grep `AutofillSessionGrants.(grant|isGranted)` 复核——生产代码仍仅
  `AutofillDatasetBuilders.kt`（消费）与 `AutofillConfirmActivity.kt`（写入）两处，`AutofillPickerActivity` 无引用。
- **P3-186 前提成立**：`AutofillPickerActivity.deliver` 原实现构造数据集回传后直接结束，无任何 TOTP 引用；
  `AutofillPostFillTotpActions` 所需的 `ClipboardSecurityChannel` 已有 Hilt 绑定（`SecurityModule`），
  `ExtendedSettingsStore` / `VaultRepository` / `TotpNotificationPublisher` 均可注入。
- 行号漂移：`AutofillPickerActivity` 原文所引 `:148-185` / `:187-213` 与现状基本一致（文件未在本批前被改动）。

## 142.2 整改内容

| 项 | 缺陷 | 改动 | 主要文件 |
|---|---|---|---|
| **P3-185 查询侧** | 选择器每次填充无条件走全量生物识别，宽限红利不可得 | 新增 `AutofillAuthenticationPolicy.skipPickerRepeatConfirmation(sessionGrantEnabled, vaultLocked, grantActive)` 纯函数（**不设**「携带口令值」闸门，理由见下）；`confirmAndFill` 在解密凭据后、发起生物识别前：开关开启 → `resolveUsableWebDomain` 归属校验域 → `AutofillSessionGrants.isGranted` → 命中即直接 `deliver` | `AutofillSessionGrantStore.kt` / `AutofillPickerActivity.kt` |
| **P3-185 写入侧** | 经选择器填充成功不产生授权记录，30 秒内改走数据集路径同样免不了确认 | `deliver` 成功路径（数据集非空）`AutofillSessionGrants.grant(grantContext)`；域口径与数据集路径同源（`AutofillOriginResolver.resolveUsableWebDomain`），不可归属域由存储自身拒绝（`AutofillGrantContext.normalized()` → null 不写入不命中，fail-closed） | `AutofillPickerActivity.kt` |
| **P3-186 共用实现** | TOTP 二次动作唯一落点在确认页私有方法，选择器路径无任何 TOTP 处理 | 新建 `AutofillPostFillTotpActions`（`@Singleton`，注入 `VaultRepository` / `ExtendedSettingsStore` / `ClipboardSecurityChannel` / `TotpNotificationPublisher`）：确认页原 `handleTotpAfterConfirm` 逻辑**逐条迁移**为可单测内核 `runPostFillTotpActions`（双开关闸门 → 500ms 硬超时取快照 → `AutofillTotpCopyPolicy.shouldCopy` 复制 / 发布通知）；确认页 `completeAuthResult` 改为委托，**删除**其私有实现、`clipboardSecurityManager` / `totpNotificationPublisher` 两个注入与 `TOTP_ACTION_TIMEOUT_MS` 常量；选择器 `deliver` 在构造数据集回传前调用同一实现 | 新增 `AutofillPostFillTotpActions.kt` / `AutofillConfirmActivity.kt` / `AutofillPickerActivity.kt` |

**「选择器路径不设口令闸门」的口径论证**（与数据集路径 `skipRepeatConfirmation` 的唯一差异）：数据集路径的
`datasetCarriesPassword` 闸门（ISSUE-P1-24 AC③）防的是「口令经**无 UI 的自动途径**下发」——数据集是填充服务
自动匹配并呈现的。选择器路径每次交付都要求用户在受保护窗口内**检索并显式点选**条目（本身即一次显式指认），
不存在自动下发形态；若照搬该闸门，选择器填充恒携带口令 ⇒ 宽限恒不生效，本条整改即成空转。宽限豁免的
**仅是重复的生物识别**，首次绑定写入（P2-46）、字段级屏蔽、黑名单复核与字段 id 回传语义一律未动。

**开关关闭 = 零额外开销**：归属校验（含 DAL 网络校验）只在 `isAutofillSessionGrantEnabled()` 为 true 时执行；
关闭时 `resolveGrantContextIfEnabled` 直接返回 null，不查询授权存储、不做归属校验，行为与既有完全一致。

**TOTP 动作的超时与异常兜底**：`runAfterFill` 内部 catch-all（`CancellationException` 继续上抛）——比确认页
原实现（依赖调用方 `try/finally`）更收口，任何异常 / 超时都不阻断两条路径的填充回传。执行时点在**数据集
构造成功之后、`setResult(RESULT_OK)` 之前**（「无可交付字段」的取消路径不触碰 TOTP，比确认页原顺序——
先 TOTP 后判取消——更收敛）。

## 142.3 回归锁定（新增 9 例）

- `AutofillSessionGrantStoreTest`（**1 例**）：`skipPickerRepeatConfirmation` 四分支——命中 / 开关关 / 库锁定 /
  无授权（P3-185 AC ①③ 的纯函数面；TTL 语义由既有存储用例覆盖，未重复）。
- 新建 `AutofillPostFillTotpActionsTest`（**8 例**，纯 JVM，协作方全为函数参数）：
  两开关皆关**不触达仓库**（零开销负向钉死）/ 条目标识空白零动作 / 仅开复制（复制且不发通知）/
  仅开通知（发布且不复制）/ 无 TOTP 快照零动作 / 计算异常按无快照处理不外抛 / 验证码空白不复制
  （`shouldCopy` 口径）/ **硬超时放弃**（虚拟时钟 `delay(2s)` vs `timeout=500ms`，超时即全放弃不部分执行）
  （P3-186 AC ②③）。

## 142.4 验证与如实声明（残余与边界）

- **全量单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` ⇒ **BUILD SUCCESSFUL**（一次绿、
  无返工、无既知偶发红命中），聚合 **`tests=2115 skipped=13 failures=0 errors=0`**（297 份 XML，
  口径 = 各模块 `testDebugUnitTest` 产物目录，排除 `updateDebugScreenshotTest` 残留）。
  **差额核对**：本批新增 **9 例**（8 + 1，经逐类核对 `AutofillPostFillTotpActionsTest=8`、
  `AutofillSessionGrantStoreTest=11` 均实跑绿）；§141 记的 2108 含 2 例 `updateDebugScreenshotTest`
  截图残留（其批内「余 2 例差额未溯源」），净化基准实为 2106 ⇒ 2106 + 9 = 2115 **吻合**。
- **既有用例全绿**：`AutofillSessionGrantStoreTest` / `AutofillPickerViewModelSessionLockTest` /
  `EntryDetailTotpCopyTest` 等（P3-185 AC ④ / P3-186 AC ④）。
- **未执行**：`lint` / `assembleRelease` / 设备侧用例（`connectedDebugAndroidTest`）/
  `-DliveSyncTest` / KDBX 语料校验（本批不含 KDBX 格式与发布构建面改动）。
- **未实测（如实声明，勿外推）**：
  1. **「30 秒内二次填充不弹生物识别」的端到端行为未在设备侧实测**——两条填充路径的会话授权查询 / 写入
     均经进程内单例 `AutofillSessionGrants`，其 TTL 与匹配语义由既有 `AutofillSessionGrantStoreTest`（假时钟）
     与本批新增用例锁定，但「真机上连续两次填充」的完整链路（含生物识别弹窗的跳过时机）未跑设备侧；
  2. **归属校验的 DAL 网络延迟未实测**——开关开启时选择器每次填充会多一次 `resolveUsableWebDomain`
     （受信任浏览器 / DAL 已缓存时为纯本地判定，否则含一次网络校验）；开关默认关闭，默认路径零新增开销；
     该延迟对「点击候选 → 弹窗」的体感影响未量化；
  3. **TOTP 二次动作的「回传前 500ms 最坏延迟」未做运行期测量**——硬超时为源码常量，超时放弃路径由
     虚拟时钟单测证明，真实设备上 TOTP 计算耗时（毫秒级 HMAC）未采样；
  4. **数据集路径与选择器路径的宽限互通（选择器填充后 30 秒内走数据集路径免确认）依赖两处域口径同源**
     ——均为 `AutofillOriginResolver.resolveUsableWebDomain` 产物，属源码结构结论，未做跨路径设备实测；
  5. 选择器页新增 3 个注入成员（`ExtendedSettingsStore` / `VaultRepository` / `AutofillPostFillTotpActions`）
     的 Hilt 装配以编译 + 全量单测通过为证，未做设备侧启动冒烟。
- **既有行为保持声明**：生物识别路径（`CryptoObject` 绑定）、无认证器退化路径、字段级屏蔽（P3-43）、
  首次绑定写入（P2-46）、`AutofillPickerViewModelSessionLockTest` 锁定清空语义均未改动；
  确认页 `deliverAuthResult` 的「回传前再次校验锁定」守卫未动（TOTP 动作期间锁定仍会丢弃响应）。

## 142.5 过程留痕

- `Edit` 工具两次因 `old_string` 不唯一被拒（确认页 `autofillOriginResolver` 注入块），补上下文后成功；
  期间一次误编辑曾把 `autofillLastFilledStore` 注入连带删除，**编译前自查发现并立即恢复**，未入库。
- 首次全量编译暴露确认页 `autofillOriginResolver` **重复声明**（`REDECLARATION`，误编辑的连带产物），
  删除重复块后编译通过——属编排失误非设计问题，如实留痕。
