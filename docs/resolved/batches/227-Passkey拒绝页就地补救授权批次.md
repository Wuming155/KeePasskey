# §227 Passkey 拒绝页就地补救授权批次（`ISSUE-P3-221`）

> **起因**：§226（`ISSUE-P2-220`）把 Passkey 创建链路的 fail-closed 拒绝从「静默断掉」改为「呈现明确原因」。
> 交付后用户真机（小米，Via 浏览器 + passkeys.io）复现并提出明确设计演进：
> 原文「我有个想法，到时候弹窗说明 xx 不是 xxx 的信息，然后下面两个选择，一个退出，一个是添加到特权名单里面，减少跳转和操作」。
> **主题**：将「说明原因」进一步深化为「**就地补救**」——拒绝页点名调用方应用，并提供**就地将浏览器添加到特权白名单**
> 的主动作，以及次动作「退出」。全程在当前受保护窗口内闭环完成，**零跳转**；本次请求仍以 `RESULT_CANCELED` 收尾，
> 授权生效于下一次发起（产品裁决 `PD-12`）。
> **本环境边界**：本批未改动 `*/src/androidTest/**` 与原生内核，按 `AGENTS.md` §5 无设备侧必跑项；
> 就地授权的动态交互体验（转圈、成功态刷新、回到浏览器重试放行）需真机手工核验，如实登记于 §4。

---

## 1. 条目原文

### ISSUE-P3-221：拒绝原因页只说「为什么被拒」，不说「那怎么办」

- **核实时间点与方式**：2026-09-20，用户真机复现报告（Via 浏览器 + passkeys.io）后对 §226 交付物的
  **当场反馈**（原文：「我有个想法，到时候弹窗说明 xx 不是 xxx 的信息，然后下面两个选择，一个退出，
  一个是添加到特权名单里面，减少跳转和操作」）+ 全链路代码走查（`BaseCredentialActivity` /
  `CredentialRejectionScreen` / `PasskeyCreateActivity` / `PasskeyPrivilegedBrowserStore`）。
- **现状（§226 的已知边界）**：`ISSUE-P2-220` 让拒绝原因**可见**了，但页面只呈现「为什么被拒」，
  未给出「怎么解决」。用户读到「无法确认调用应用对本站点的归属声明」后仍不知道下一步做什么，
  只能反复重试。**这是 §226 刻意留白的结果**（当时口径为「不存在第二条出路」），非实现缺陷。
- **根因**：`CredentialRejectionReason` 只承载文案资源，没有「用户可执行动作」这一维度；
  且拒绝页渲染在系统经 PendingIntent 拉起的 `PasskeyCreateActivity` 窗口内，未接入特权名单就地写入。
- **硬约束（已核实）**：
  1. 凭据 Activity「跳出去却不结束」会让框架收不到认证完成事件（`AutofillUnlockActivity` 有实测教训）；
  2. `MainActivity` 是唯一无权限保护的导出组件，`ExportedComponentHygieneTest` 明令其
     **不得消费任何外部 intent 数据**（禁 `getStringExtra` / `onNewIntent` 等），跨 Activity 传递路由成本高；
  3. 文案纪律（ISSUE-P1-10）：按钮一律无插值、不得携带调用包名 / rpId / 域名；应用展示名仅允许出现在说明句中；
  4. 凭据窗口具有「不提供本应用内导航」的加固取向（`CredentialUnlockPresenter` 等三处 no-op）——
     **就地授权完全符合该取向，因为根本不发生任何跳转**。
- **AC**：
  1. 仅当拒绝原因**确有用户可执行的解法**（DAL 门禁未通过 / 网络不可用）且调用方确为浏览器时，
     页面呈现说明句与两个选择：「添加到特权名单」（主动作）与「退出」（次动作）；
  2. 无从下手的原因（含 `CREDENTIAL_ALREADY_EXISTS` 这类正常结果）以及原生 App 的 DAL 失败
     **维持原布局**（仅退出），不得误导用户；
  3. 授权动作**全程在当前受保护窗口内就地完成，不跳转任何其它界面**；
  4. **本次请求仍然必然失败**：授权只影响下一次发起，授权成功后页面就地提示「已添加，请回到浏览器重新发起创建」，
     对系统的回传契约仍为 `RESULT_CANCELED`；
  5. 写入白名单属于偏好与包管理操作，必须下沉 `Dispatchers.Default` 协程执行，不得阻塞主线程；
  6. 单测覆盖「原因 → 动作」穷举映射、浏览器资格判定守卫、文案规范与按钮布局；`.\gradlew.bat test` 全绿。
- **涉及文件**：`app/.../passkey/CredentialRejectionAction.kt`、`app/.../passkey/BrowserRemedyBuilder.kt`、
  `app/.../passkey/CredentialRejectionScreen.kt`、`app/.../passkey/BaseCredentialActivity.kt`、
  `app/.../passkey/PasskeyCreateActivity.kt`。

---

## 2. 整改明细

### 2.1 方案演化：从「跳转设置页」收敛为「就地补救授权」

在方案探索初期曾设计过「一键跳转到特权浏览器白名单设置页」方案，但在推演中发现三个严重成本：
1. `MainActivity` 禁用 intent 传参，需引入跨 Activity 进程内信箱，依赖复杂的生命周期与解锁态侦听；
2. 凭据窗口跳出后，用户需要在设置页里手动找到对应浏览器并启用，再切回浏览器重新发起；
3. 用户提出明确修正：「**到时候弹窗说明 xx 不是 xxx 的信息，然后下面两个选择，一个退出，一个是添加到特权名单里面，减少跳转和操作**」。

这一修正直接击中了最佳解法：**直接在当前带 FLAG_SECURE 的受保护窗口内完成特权名单写入，零跳转！**

### 2.2 严格的资格守卫（`BrowserRemedyBuilder`，fail-closed）

并非所有被 DAL 门禁拦截的应用都能提供「添加到特权名单」按钮。必须同时满足三个前提（任一不满足即返回 null）：
1. **原因必须属于 DAL 两类**：`DAL_UNVERIFIED` 或 `DAL_NETWORK_UNAVAILABLE`（见 `CredentialRejectionAction.forReason`）；
2. **必须具有系统背书的包名**：`CallingOriginResolver.systemAttestedPackageName` 返回非空；
3. **调用方必须是浏览器候选**：命中 `PasskeyPrivilegedBrowserStore.installedBrowsers()`（能处理 https VIEW 意图且现场可读取签名证书）。
   - **这一条从根本上排除了原生 App**：如果某个恶意或非浏览器 App 试图为某域名注册 Passkey 而被 DAL 拦截，它绝不具备浏览器 VIEW 意图，不会出现「添加」按钮；
4. **排除已启用项**：若该浏览器已经在特权名单中，再给「添加」毫无意义，同样返回 null。

### 2.3 拒绝页面形态与就地交互（`CredentialRejectionScreen`）

页面由单纯的确认框演进为具身选择界面：
- **说明句**：点名系统背书包的展示名（如「Via」），解释「『Via』尚未加入特权浏览器白名单。将其加入后，回到浏览器重新发起创建即可。」；
- **主按钮**：填充样式的 `Button`，文案「添加到特权名单」；
- **次按钮**：文本样式的 `TextButton`，文案「退出」（无补救时仍为主按钮「知道了」）；
- **执行与反馈状态机**：
  - 点击后主按钮显示 `CircularProgressIndicator` 转圈并禁用交互；
  - 授权写入由 `PasskeyCreateActivity.lifecycleScope` 调度至 `Dispatchers.Default` 执行；
  - 成功后就地转换为成功态：「已添加『Via』。请回到浏览器重新发起创建。」，底部变为单一「完成」按钮，点击退出并回传取消；
  - 失败后（如读取签名证书异常等 fail-closed 场景）红字提示并允许重试或退出。

### 2.4 对系统回传契约的不变式（产品裁决 PD-12）

即使授权成功，**本次创建依然以 `RESULT_CANCELED` 结束**：
系统 Credential Manager 在拉起时已根据当时的环境决定了 origin（非特权浏览器退化为 `apk-key-hash`），凭据提供者无法在同一会话内原地更改 origin 并继续注册。授权生效于**下一次发起**——用户按提示回到浏览器重新发起时，KeePasskey 再次拉起就能成功走浏览器特权通道拿到 web origin 并豁免 DAL。

---

## 3. 验证

> 全部计数现跑，不抄上一批。

| 项 | 命令 | 结果 |
|---|---|---|
| 宿主全量单测 | `.\gradlew.bat test --rerun-tasks --max-workers=1` | **BUILD SUCCESSFUL**；`xml=332 tests=2327 failures=0 errors=0 skipped=13`（§226 基线 332/2321 ⇒ **+6 例逐例可溯**） |
| 定向测试覆盖 | `app/src/test/.../passkey/CredentialRejectionFeedbackTest.kt` | **18 例全部通过**（含就地授权判定、fail-closed 写入下沉 Default、无插值与占位符规范、不拉起外部 Activity 等 6 例新断言） |
| 截图包装门禁 | `python tools/export_previews/generate_screenshot_test_wrappers.py` + `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun` | 生成器 `promoted=79 wrappers=79 packages=17`（含新增的 `CredentialRejectionChoicesPreview` 两选择预览）；**BUILD SUCCESSFUL in 2s** |
| 行数分档与长函数 | `python tools/doc/count_line_tiers.py` + `python tools/doc/long_functions.py` | `PasskeyCreateActivity.kt` **397 行**（守住 <400）；长函数 **3 个持平**（拆分 `RejectionActionButtons` 守住 <=100） |
| 文档与索引机检 | `python tools/doc/check_md_links.py` + `python tools/doc/check_resolved_index_sync.py` + `bash tools/audit/check_recheck_consistency.sh` | **`BROKEN_MD_LINKS=0`**、**`RESOLVED_INDEX_SYNC=OK`**、**`PASS: 无残留禁用短语`** |

---

## 4. 过程缺陷与如实声明

1. **`CredentialRejectionScreen` 初版行数超限（过程纠偏）**：引入状态机后主函数一度达到 124 行，被 `long_functions.py` 检出（`functions_ge_100` 3 → 4）。立即将按钮渲染与状态分支下沉为 `RejectionActionButtons` 私有 Composable，主函数降回 55 行，长函数计数稳在 3 个。
2. **`BaseCredentialActivity.rejectAndFinish` 可见性冲突（过程修复）**：方法原声明为 `protected`，但形参包含 `internal class CredentialRejectionRemedy`，Kotlin 编译器报 `exposes internal parameter type`。将其声明对齐为 `internal` 后解决。
3. **占位符正则转义（测试编写细节）**：Kotlin 字符串内匹配字面 `%1$s` 时，`${'$'}` 会被替换为字面 `$`，在正则引擎中被当作行尾锚点导致匹配落空。修正为 `Regex("%[0-9]+\\\$s|%s")`。
4. **无自动化 Compose UI 测试覆盖（如实声明）**：仓库 `app/src/androidTest` 无 Compose UI 驱动测试基建，按钮点击后的转圈动效、状态机刷新、真机写入偏好后的端到端放行属于平台真机核查面（已登记于限界表 §22）。

---

## 5. 涉及文件

**生产代码**

| 文件 | 改动 |
|---|---|
| `app/.../passkey/CredentialRejectionAction.kt` | 动作枚举定义（`ADD_PRIVILEGED_BROWSER`），纯函数映射 `forReason` |
| `app/.../passkey/BrowserRemedyBuilder.kt` | **新增**：就地补救构建器（浏览器资格判定、排除原生 App 与已启用项、下沉 Default 执行授权） |
| `app/.../passkey/CredentialRejectionScreen.kt` | 演化为两选择交互界面（说明句、主次按钮、状态机、授权中/成功/失败渲染，增加两选择预览） |
| `app/.../passkey/BaseCredentialActivity.kt` | `rejectAndFinish` 接收可选 remedy；收尾原语 `settleRejection()` 幂等保护 |
| `app/.../passkey/PasskeyCreateActivity.kt` | 门禁拒绝分支接入 `BrowserRemedyBuilder.build` |
| `app/src/main/res/values/strings_sync_passkey.xml` | 新增就地授权中文字符串 5 条（退出、按钮、说明、成功反馈、失败反馈） |
| `app/src/main/res/values-en/strings.xml` | 同步对应英文字符串 5 条 |

**测试与文档**

| 文件 | 改动 |
|---|---|
| `app/src/test/.../passkey/CredentialRejectionFeedbackTest.kt` | 扩增至 18 例测试，全面锁定就地补救的判定、接线与资源规范 |
| `docs/architecture/产品裁决登记.md` | 新增 **`PD-12`** 产品裁决（凭据拒绝页至多提供一条就地授权补救动作） |
| `docs/architecture/已知工程限界.md` | 新增 **`§22`** 登记就地授权链路的工程边界与测试覆盖缺口 |
| `docs/resolved/batches/226-*.md` | 新增 §6 更正节，按规则说明「不存在第二条出路」口径的收窄与演进 |
| `docs/ACTIVE_ISSUES.md` | 登记并闭环 `ISSUE-P3-221`，低危区归零 |
| `docs/RESOLVED_LOG.md` / `BATCH_158_PLUS.md` | 新增 §227 批次索引条目 |
| `docs/resolved/README.md` | 当前最大批次更新为 **§227** |
