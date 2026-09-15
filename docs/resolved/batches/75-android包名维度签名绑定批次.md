<a id="s75"></a>
## §75 `android://` 维度签名绑定批次（2026-09-15）：ISSUE-P2-46

> **本批次缘起**：`ISSUE-P2-46` 指出条目以 `android://<包名>` 绑定时，匹配仅做**精确字符串相等**
> ——真实应用未安装时，任意应用只需以同 `applicationId` 侧载即可命中并取得候选。
> 本批按复核报告**定版口径**（未授权一律「**不命中**」，而非「弱候选」）整改**自动填充通道**，
> 并把同根因下**未覆盖的 Credential Manager 通道**拆分为 `ISSUE-P2-83` 独立跟踪。

### 75.1 交付清单

| 子项 | 改动 |
|---|---|
| 放行判定 | 新增纯函数 [AndroidPackageBindingPolicy](../../../app/src/main/java/com/keepasskey/app/autofill/AndroidPackageBindingPolicy.kt)：调用方**包名 + 签名摘要**已绑定 **且摘要可读**时才允许 `android://` 维度参与放行（两条 fail-closed 判据，见类 KDoc） |
| 候选门控 | `AutofillCandidateRanker.rank(...)` 新增**无默认值**的 `packageDimensionAuthorized`；`EXACT_PACKAGE` 只在授权为真时成立（无默认值 = 调用方必须逐次显式决策，避免「忘了传参」静默放宽） |
| 判定接线 | `AutofillDatasetBuilders` 由 `autofillOriginResolver.callingAppCertDigests(callingPkg)` + `callerTrustStore.isTrusted` 计算授权并传入；未授权时落**脱敏**日志（不含包名 / 摘要，ISSUE-P1-10 语义） |
| 首次绑定**写入** | 选择器 `AutofillPickerActivity.deliver()` 写入绑定——它是**唯一**由用户在受保护窗口内**显式指认**「把这条凭据填给这个调用方」的入口，且该页已展示包名 / 应用名 / 签名摘要（ISSUE-P2-70）；摘要不可读时**不写降级键**（否则等于只认包名，正是本项要消灭的形态） |
| 服务注入 | `KeePasskeyAutofillService` 注入 `AutofillCallerTrustStore`——与确认页 / 选择器**同一实例**，保证「写入面 = 判定面」 |
| 回归 | 新增 `AndroidPackageBindingPolicyTest`（8 例）+ `AndroidPackageBindingWiringTest`（3 例）；`AutofillCandidateRankerTest` 新增 3 例门控用例，既有 11 处调用点补齐显式授权参数 |
| 拆分跟踪 | 同根因在 **Credential Manager** 通道的 4 处调用点（`CredentialResponseAssembler:117/188`、`KeePasskeyCredentialProviderService:342`、`PasswordFillActivity:97`）拆分为 **`ISSUE-P2-83`**，附「为何不能直接复用 `AutofillCallerTrustStore`」的语义边界分析 |

### 75.2 为什么绑定写入必须落在选择器（否则会退化为功能回归）

若只在**已有候选**的确认页写入绑定，而候选本身又被该门控拦掉，则形成**鸡生蛋死锁**：
未绑定的调用方永远看不到候选 ⇒ 永远无法绑定 ⇒ `android://` 绑定条目在所有调用方上**永久失效**。
因此绑定写入必须放在**不依赖候选命中**的路径上：选择器（全库搜索 + 用户显式指认）。
用户首次需手动搜一次；绑定后同签名调用方恢复自动命中——这正是「首次绑定」的语义。

### 75.3 验证证据（2026-09-15）

- **全量单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → `BUILD SUCCESSFUL in 2m 8s`，
  **1802 例 / 0 失败 / 0 错误 / 13 跳过**（app **1005**，较 §73 基线 +14）。
- **新增用例逐项**（`app` 模块测试 XML 实测）：
  `AndroidPackageBindingPolicyTest` **8 例 / 0 失败**、
  `AndroidPackageBindingWiringTest` **3 例 / 0 失败**、
  `AutofillCandidateRankerTest` 新增 3 例（未授权不命中 / 未授权仍走域名维度 / 未授权不改变域名排序）。
- **AC② 两条回归**：`同包名不同签名（侧载重打包）不放行`（换签名重新视为首次）、
  `未安装绑定包 + 侧载同 applicationId 不命中`；另补 `签名摘要不可读时不放行（不得退化到仅包名）`。
- **发布产物**：`.\gradlew.bat assembleRelease` → `BUILD SUCCESSFUL`，产物
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`（已配置 release 签名）。

### 75.4 残余与边界（如实登记）

1. **未做设备侧 E2E**：触发真实自动填充需要「第三方带登录表单的应用 + 真实 autofill 会话」，
   本批以 JVM 行为用例 + 接线守卫为证据。**建议与 `ISSUE-P2-73` / `ISSUE-P3-122` 的设备实测同批进行**
   （那两条本就要求 `adb logcat -s AutofillManager` 观测填充链路），届时可顺带核对本门控的现场表现。
2. **未覆盖 Credential Manager 通道**：见 `ISSUE-P2-83`（**不得**把本批读作「`android://` 维度整体已加固」）。
3. **签名摘要依赖包可见性**：`callingAppCertDigests` 受包可见性过滤（`ISSUE-P2-74` 已为浏览器域名补
   `<queries>`；**任意包**的签名读取仍可能在受限 ROM 上不可读）。不可读时本门控**fail-closed**
   （该调用方 `android://` 候选不命中，仍可经选择器手动填充并绑定——但摘要不可读时**不写绑定**，
   故下次仍需手动选择）。该行为是刻意取舍：**可用性让位于「不把凭据交给无法验证签名的调用方」**。
4. **既有信任记录的迁移面**：本门控只影响「自动命中」，不迁移 / 不清除任何既有记录；
   升级前若已存在信任记录（用户曾在确认页授权过），该调用方**立即**满足门控。

### 75.5 过程缺陷（如实留痕）

1. **`rank(...)` 的授权参数刻意不给默认值**：初稿曾考虑默认 `false`（fail-closed 默认）以免改动
   11 处测试调用点，但**默认值本身就是「忘记传参」的温床**——安全参数一旦有默认值，
   新调用点就会静默继承。故改为**必填**，并逐一核对既有调用点（域名维度用例传 `false`、
   包名维度用例传 `true`），宁可多改 11 处。
2. **同根因的通道拆分而非「顺手也改了」**：CM 通道看似可复用同一存储，但复用会要么造成
   「CM 侧永不写入 ⇒ 功能回归」，要么为写入而放宽自动填充侧的严格性（**反向削弱 ISSUE-P1-24**）。
   ⇒ 纪律：**同一根因在不同通道的整改必须各自评估语义边界**；无法同批安全闭环时，
   拆分为独立条目并写明边界，而不是把「半覆盖」记成「已闭环」。
3. **批次被外部提交切分为两段（如实留痕，非本批次内我可控）**：本批的 `main` 源集改动被一个
   **不在本轮次操作序列内**的提交 `5371a0d`（提交信息「实现 `android://` 包名维度的首次绑定与信任存储机制」，
   2026-09-15 21:11:36）先行收录，随后由本批次的正式提交 `2118546` 补齐
   （选择器的绑定写入方法、全部回归用例、文档）。**后果与影响**：
   - 最终 `HEAD` 状态**完整且全绿**（1802 例 / 0 失败），归档以 `§75` 为**单一真相源**，可追溯性未受损；
   - 但 `5371a0d` **单独不可发布**：该时点 `AutofillPickerActivity` 已注入信任存储却**尚未写入绑定**，
     恰是 75.2 所述「鸡生蛋死锁」的形态（此时 `android://` 候选被拦而无法绑定）；
   - 本仓「一次提交承载一个完整批次」的纪律因此未被满足，且**无法通过改写历史修复**（已推送）。
   ⇒ 纪律：**多会话 / 多进程并发操作同一工作树时，中间态可能被外部提交固化**；
   收尾时应核对「`HEAD` 是否自洽且可发布」而非仅核对「我的改动是否已提交」。
