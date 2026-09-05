# WAVE2-D 启动提示词（Credential Provider 与 Autofill 端到端）

> 本文件为代理提示词存档，由主会话在 Wave 1 验收通过后复制给子代理执行。目标：P0-3、P0-4、P2-14。

你是资深 Android 系统集成工程师，为密码管理器 KeePasskey 打通「Credential Provider 与传统 Autofill 的端到端链路」（REMEDIATION_PLAN.md 的 Wave 2-D）。

## 项目背景
- 项目根目录：D:\GithubWorkplace\KeePasskey（Windows），Gradle 9.3.1（`.\gradlew.bat`），minSdk/targetSdk 36。
- 开工前必读（相对项目根）：
  1. `AGENTS.md`、`.codebuddy/rules/engineering-rules.md`（含「Credential Provider 隔离规范」：无状态会话、Auto-Lock 熔断、响应超时预算）
  2. `REMEDIATION_PLAN.md`（重点：问题 P0-3/P0-4/P2-14，第四节契约中你的部分，第六节风险决策「锁库 UX v1」）
  3. `.codebuddy/skills/references/KeePassDX-架构分析.md` 第 6.6 节（PasskeyProviderService 三回调结构、Launcher Activity 链、PrivilegedAllowLists、APK 指纹→App Origin）与 7.3 节（通行密钥请求流）
  4. `.codebuddy/skills/references/keepass2android-架构分析.md` 第 7.3 节（Autofill 解析纯库化 + fixture 测试思路）
- 【铁律】严禁读取/扫描 `参考项目/` 源码树；严禁复制参考代码；严禁执行任何 git 命令。
- 【铁律】模块依赖单向：app → database → crypto → core；app → sync → core。禁止 database/sync 反向依赖任何上层。

## 文件所有权（并行约束：另一代理正在改 sync/ 模块）
- 允许修改：`app/src/main/java/com/keepasskey/app/{passkey,autofill}/**`（新建或重构）、`app/src/main/java/com/keepasskey/app/data/repository/VaultRepository.kt` 与 `RealVaultRepository.kt`（**仅允许追加新方法**，不得改动既有方法签名/实现逻辑）、`app/build.gradle.kts`（仅追加依赖行）、`app/src/main/AndroidManifest.xml`、`app/src/main/res/**`（strings 双语、xml/、drawable 图标）、`app/src/main/java/com/keepasskey/app/di/**`（增量）。
- 禁止修改：`app/src/main/java/com/keepasskey/app/ui/**`（若既有代码因编译不通过需最小修补，仅限 import 或一行级改动并在报告中列出）、crypto/、database/、core/、sync/ 下任何文件。
- app 模块已有 Hilt（kapt）、Compose、Biometric 依赖。

## 依赖补齐（计划第四节已确认的缺口）
`app/build.gradle.kts` 添加：
```kotlin
implementation("androidx.credentials:credentials:1.5.0")
```
（`PasswordCredentialEntry`/`PublicKeyCredentialEntry`/`CreateEntry`/`Action` 所在库；若 1.5.0 不可解析，用仓库中可用的最新 1.x，并在报告注明版本。）

## 任务清单（全部必做）

### D1 VaultRepository 增量方法（供系统服务只读查询，不触碰既有方法）
在接口与 RealVaultRepository 追加：
- `suspend fun getKdbxEntries(): List<KdbxEntry>` —— databaseFlow 一次性快照映射（直出 core 模型，供 passkey 域名匹配用，不经过 UiVaultEntry 以免 readString 明文扩散）；
- `suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry>` / `findEntriesForUrl(url: String): List<KdbxEntry>`；
- `suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry?` —— 解析 PasskeyData.fromCustomFields(entry.customFields)（core 已有）；
- `suspend fun saveNewPasskeyEntry(data: PasskeyData): KdbxEntry` —— 新建条目（Title=userName@rpId，字段含 UserName，customFields=data.toCustomFields()），走 databaseSession.saveEntry + save；
- `suspend fun patchPasskeySignCount(entryId: String, newCount: Int)`；
- `suspend fun saveAutofillCredential(packageName: String, webDomain: String?, username: String, passwordChars: CharArray)` —— onSaveRequest 落库（passwordChars 用后清零）。
FakeVaultRepository（app/src/test 有引用）同步实现这些方法（简单内存实现），保证 app 测试编译。

### D2 严格域名匹配工具（P2-14，新建 app/passkey/DomainMatcher.kt）
- `fun isRpIdMatch(rpId: String, originOrRpId: String): Boolean` —— 严格后缀匹配：origin 小写化去协议与端口后，等于 rpId 或以 ".$rpId" 结尾（RFC eTLD+1 语义的保守近似）；禁止双向 contains。
- `fun extractDomain(url: String): String`。
- 单元测试（app/src/test，纯 JUnit）：evilgithub.com 不得命中 github.com；github.com 命中 github.com 与 login.github.com；带端口/协议/路径的 URL 解析。

### D3 CredentialProviderService 真实化（P0-3）
重写 `KeePasskeyCredentialProviderService`：
- onBeginGetCredential：遍历 `BeginGetPublicKeyCredentialOption`（解析其 `requestJson` 中的 rpId，用 JSONObject）与 `BeginGetPasswordOption`；库锁定时 → 添加 `Action`（"解锁 KeePasskey" 图标+文案，PendingIntent 到 MainActivity）；未锁定 → DomainMatcher 匹配 → 每个命中条目构建 `PublicKeyCredentialEntry`（beginIcon+displayName+subtitle+PendingIntent，生物识别开关按 BiometricAuthManager 状态）或 `PasswordCredentialEntry`；保持 KDoc 注明"响应超时预算"（serviceScope 内 5s withTimeout 逻辑或说明）。
- onBeginCreateCredential：区分 `BeginCreatePublicKeyCredentialRequest`（解析 requestJson：rp/user/challenge/algo）→ 构建 `CreateEntry`（PendingIntent 到新建 PasskeyCreateActivity）；`BeginCreatePasswordCredentialRequest` → CreateEntry（PendingIntent 到 PasswordSaveActivity）。
- 新建 4 个 Activity（app/passkey/，均普通 ComponentActivity，exported=false，theme 透明或普通）：
  - `PasskeyCreateActivity`：读 intent extras（rpId/userName/userDisplayName/challenge/origin），调 vaultRepository.saveNewPasskeyEntry（PasskeyCryptoEngine.generateEs256KeyPair）→ 立即构建 attestationObject（用 crypto 交付的 CborEncoder/CoseKey/buildAuthenticatorData AT 段：fmt="none"，attStmt 空 map，authData=rpIdHash+flags(UP|UV|AT|BE|BS)+signCount(0)+aaguid+credId+COSE key）→ clientDataJSON（type="webauthn.create"、challenge、origin）→ `PendingIntentHandler.getCreateEntryResponse(...)` 或按 androidx.credentials provider API 组装 `CreateEntryResponse`/`PendingIntentResponse` 回 setResult。组装细节以 androidx.credentials 1.5.0 的公开 API 为准（CreatePublicKeyCredentialResponse / PublicKeyCredentialEntry 等），KDoc 注明。
  - `PasskeyAssertionActivity`：读 extras（entryId、requestJson、challenge、origin）→ 取条目 PasskeyData → authData（flags UP|UV|BE|BS，signCount+1）→ 签名（PasskeyCryptoEngine.signAssertion，输入=authData‖SHA256(clientDataJSON)）→ clientDataJSON(type="webauthn.get") → 组装 assertion 响应回传。完成后 patchPasskeySignCount。
  - `PasswordFillActivity`：读 entryId → 返回密码（autofill 用户名/密码 RemoteViews 或 Credential Manager PasswordCredential 响应，按 API 实际形态）。
  - `PasswordSaveActivity`：读 extras（packageName、webDomain、username、password）→ saveAutofillCredential → finish。
- Manifest 注册 4 个 Activity；strings.xml（中/英）新增全部用户可见文案（服务名、解锁动作、创建/保存提示等），命名延续既有前缀（cred_ / autofill_）。
- 严格遵循工程规则的「Credential Provider 隔离规范」：服务不持有数据库写会话；所有路径 Auto-Lock 状态检查；超时预算常量化。

### D4 AutofillService 真实化（P0-4）
重写 `KeePasskeyAutofillService`：
- onFillRequest：遍历 AssistStructure（parseStructure 辅助函数：收集 autofillHints 含 username/password/current-password/new-password 的 AutofillId，取 webDomain/packageName）→ 未登录（isLocked）时返回 AuthenticationAction 样式 Dataset 或 onSuccess(null)（按可行性，KDoc 说明）；已解锁 → findEntriesForUrl/PackageName → 每个 Dataset 用 RemoteViews（简单两行布局：用户名 + 应用名）填充 username/password id → FillResponse。
- onSaveRequest：提取 username/password 字段值 → saveAutofillCredential。
- 新建 res/layout/autofill_dataset_item.xml（RemoteViews 兼容：仅 FrameLayout/LinearLayout/TextView/ImageView）。
- 单元测试：parseStructure 用 Robolectric 不可行（无依赖），改为把「结构遍历→待填字段识别→域名提取」逻辑抽成纯函数 `AutofillFieldScanner`（输入抽象的节点描述列表）并写 JUnit 测试（对齐 Kp2a「解析纯库化」思想）。

### D5 验证
`.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`。
Gradle 锁等待重试规则同前（等 2 分钟重试 ≤3 次）。禁止 assembleDebug 之外的混淆构建。

## 完成报告格式
改动文件清单（±行数）、新增 API 签名（第四节契约落实情况）、androidx.credentials 实际解析版本、新增测试与结果、gradle 命令与结果、偏差与原因（特别是 androidx.credentials API 与提示词假设不符之处）。
