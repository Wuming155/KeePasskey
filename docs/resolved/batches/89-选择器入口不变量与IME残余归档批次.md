<a id="s89"></a>
## §89 选择器入口不变量与 IME 残余归档批次（2026-09-16）：ISSUE-P3-125 ① / ISSUE-P3-76

> **本批次缘起**：两项均**无决策阻塞**——`ISSUE-P3-125` ① 的 AC 本身提供「改挂占位数据集
> **或**留痕接受现设计」两条路；`ISSUE-P3-76` 的处置在条目正文中**早已定下**
> （框架阻塞的已接受残余 + 可复现解除条件），只是尚未走归档流程。

### 89.1 `ISSUE-P3-125` ①：选择器「无条件挂入」是**设计**，不是疏漏

**复核条目原主张**：`buildPickerDataset` 在 `appendUnlockedDatasets` 之后无条件调用、无候选数门槛
⇒「严格匹配设计对任意应用失效」。

**逐条复核后的结论**：该主张把两件事混在了一起。

| 面 | 是否受严格匹配约束 |
|---|---|
| **自动下发候选** | **是**，且一字未放宽——候选层先过域归属（`ISSUE-P2-07`：不可归属即不下发）与 `android://` 包名维度签名绑定（`ISSUE-P2-46`：未绑定即不命中） |
| **手动指认（选择器）** | **否，且不应受约束**——它就是「用户显式指认调用方」这条**唯一**入口 |

**为什么不能给选择器加候选数门槛**：选择器**恰恰是零匹配时唯一的用户出口**。
「零匹配就不挂」＝用户在最需要手动搜索时找不到入口（功能回归）；
「零匹配才挂」＝有候选时用户无法改选（同样是回归）。
更关键的是，它是 `ISSUE-P1-24`（首现授权）与 `ISSUE-P2-46`（`android://` 首次绑定）的
**写入点**——`AutofillPickerActivity` 是「用户在受保护窗口内显式指认调用方」的唯一落点
（该页展示包名 / 应用名 / 签名摘要，见 `ISSUE-P2-70`）。掐掉它等于同时废掉两条安全机制。

**它为何不是「静默放行」面**：
1. 数据集值恒为 `null`——用户显式点选前**不携带任何明文**（官方认证数据集语义）；
2. 文案**不自称命中**：标题「搜索全部条目…」、副标题「手动选择要填充的凭据」；
3. 点选后进入受保护窗口，展示调用方归属并需用户确认，确认动作即首次绑定写入。

**本批产出（把不变量变成可执行断言）**：新增 `AutofillPickerEntryInvariantTest`（4 例）——
入口**必须无条件挂入**（调用点前 200 字符内不得出现候选数条件包裹）、文案**不得自称命中**、
点选前**不得携带明文**（`setValue(..., null)`）、自动候选**仍须经** `AutofillCandidateRanker.rank`
与 `AndroidPackageBindingPolicy`。这四条互补：**放宽的只有「手动指认」这一条路**。

### 89.2 `ISSUE-P3-76`：按其**已定处置**归档（框架阻塞的已接受残余）

条目正文早已给出完整处置与依据（原文要点，原样收录以免归档后失去可复现性）：

- **框架阻塞实测（2026-09-12，读取本机 Compose 源码核实）**：本仓 `androidx.compose.*` 为
  **1.11.4**（BOM 2026.08.00）。逐一核对 `EditorInfo` 构造链后确认**无任何公开 API 可下发该整型标志**：
  1. `EditorInfo.update(...)` 仅由 `imeAction` 枚举构造 `imeOptions`，不设也无可传入
     `IME_FLAG_NO_PERSONALIZED_LEARNING` 的入口；
  2. `PlatformImeOptions` **仅**暴露 `privateImeOptions: String?`，无 `imeOptions` 位域；
  3. `KeyboardOptions.toImeOptions()` 亦不含原始位域；
  4. 官方路线 `createInputConnection(outAttributes: EditorInfo)` 属平台文本输入会话私有扩展点，
     需自行实现整个输入会话，**无法与 M3 `OutlinedTextField` 组合**；
  5. 参考项目外证：spela（PR #1114）结论一致——「只能下沉到 Android View 包裹真实 `EditText`」。
- **处置：登记为已接受残余风险，不实施**。残余已部分缓解：敏感输入经 `SecurePasswordField` 统一走
  `KeyboardType.Password`（→ `TYPE_TEXT_VARIATION_PASSWORD`），主流输入法对该 inputType
  **默认不做个性化学习**，显式标志只是更强一层提示。**不采用高风险代偿**：把安全关键的
  `SecurePasswordField` 整体改写为 `AndroidView(EditText)` 会牺牲 M3 外观 / 无障碍 /
  现有 CharArray 桥接与擦除契约，风险与 P3 收益不成比例。
- **解除条件（可复现配方，原样保留）**：若 Compose 后续版本在 `PlatformImeOptions` /
  `KeyboardOptions` 暴露 `imeOptions` 位域（或提供 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的公开入口），
  则在 `SecurePasswordField` 统一接线并补回归断言（覆盖主密码 / 条目口令 / TOTP / 同步凭据各调用点），
  届时即可闭环本条。

**归档依据**：处置已定且留痕完整（体例同 `ISSUE-P3-79`「留痕『无需接线』即可闭环」）；
留在待办表会让「已决定不做」长期占据 P3 席位，而真正的触发条件是**框架版本变化**、不是本仓工作。

### 89.3 验证证据（2026-09-16）

- `AutofillPickerEntryInvariantTest` **4/4**。
- 全量 `test --rerun-tasks --max-workers=1`、`assembleRelease`、真机
  `:app:connectedDebugAndroidTest` 结果见提交信息。

### 89.4 下一批

1. 表内仅剩 **`P3-120`**（真机 Frida 实测完整性检测命中率，**P1/UNVERIFIED**，为 4 条目的共同前提）
   与 **`P3-121`**（自建内网 WebDAV / NAS 出厂不可用，需产品口径）——两者**均卡在外部裁决**。
2. P2 三条仍卡决策点（`P2-47` AC②、`P2-73` 数据集回传、`P2-79` 产品口径）。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§89）

选择器入口不变量与 IME 残余归档批次（两项，均无决策阻塞）：**`P3-125` ①** 复核后判定「选择器**无条件**挂入」是**设计而非疏漏**——它是「用户显式指认调用方」的唯一入口、也是 `ISSUE-P1-24` 首现授权与 `ISSUE-P2-46` 首次绑定的**写入点**，而它**恰恰是零匹配时唯一的用户出口**（加「零匹配才挂 / 就不挂」的门槛都会造成功能回归）；严格匹配保护的是**自动下发**，那一条一字未放宽。新增 `AutofillPickerEntryInvariantTest`（4 例）把不变量变成可执行断言：必须无条件挂入 / 文案不得自称命中 / 点选前不得携带明文 / 自动候选仍须经严格匹配与绑定门控。**`P3-76`**（IME 个性化学习）按其正文中**早已定下**的处置归档：Compose 1.11.4 无公开 API 可下发 `IME_FLAG_NO_PERSONALIZED_LEARNING`（逐条源码核实在案），登记为**已接受残余**（`KeyboardType.Password` 已部分缓解；不把 `SecurePasswordField` 改写成 `AndroidView(EditText)` 换纸面加固），**解除条件**（Compose 暴露该位域即接线）原样收录于批次文档
