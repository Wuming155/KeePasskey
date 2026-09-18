# §149 通行密钥对齐 KeePassDX 整改批次

> **起因**：用户命题「看看和 KeePassDX 比较，本项目在通行密钥功能的实现上有什么问题」。
> **核实方式**：先读索引文档 [`docs/references/KeePassDX-架构分析.md`](../../references/KeePassDX-架构分析.md) §4.5 / §6.6 / §8.2
> （遵守 `AGENTS.md` §3「参考项目只读、架构分析集中在 `docs/references/`」），再按该文档给出的路径对
> `app/src/main/java/com/kunzisoft/keepass/credentialprovider/passkey/util/PasskeyHelper.kt`、
> `database/src/main/java/com/kunzisoft/keepass/model/Passkey*.kt`、
> `credentialprovider/passkey/PasskeyProviderService.kt`、`credentialprovider/passkey/util/PrivilegedAllowLists.kt`
> 做**定向只读**逐行核对（未修改、未复制其代码）。
> **核实时间点**：2026-09-18。全部条目经源码逐条对拍，非凭记忆推断。

---

## 1. 核实出的缺口（13 项，按影响排序）

| # | 缺口（整改前事实） | 参照实现（KeePassDX） |
|:--:|---|---|
| 1 | 创建链路（通行密钥 / 密码保存）在库锁定时**直接 `failAndFinish()`**，用户点选系统「创建」后静默失败 | `PasskeyProviderService` 有 `onDatabaseClosed` 分支，下发可解锁的 `CreateEntry` |
| 2 | 注册写死 `generateEs256KeyPair`，**完全不读 `pubKeyCredParams`** | `Signature.generateKeyPair(pubKeyCredParams.map { it.alg })`，无可用算法即抛错 |
| 3 | 注册自造随机 `userHandle`（`userHandle = ""` → 引擎随机 16 字节），**忽略 RP 的 `user.id`** | `val userHandle = creationOptions.userEntity.id` |
| 4 | 断言候选不按 `allowCredentials` 收敛（请求 JSON 只读 `rp.id` / `challenge`） | `credentialIdList → buildPasskeySearchInfo` 参与检索 |
| 5 | 完全不读系统下发的 `clientDataHash`（特权调用方自带 clientDataJSON 时签名摘要不一致） | `ClientDataDefinedResponse(clientDataHash)`，对系统摘要直接签名 |
| 6 | **未实现 `prf` 扩展**（`clientExtensionResults` 恒 `{}`，模型无 prfSecret） | 完整 PRF（域分隔盐 + hmac-secret + `evalByCredential` 约束 + `KPEX_PASSKEY_PRF` 受保护存储） |
| 7 | 特权浏览器白名单**硬编码仅 Chrome 一条**、无用户可配置；非 Chrome 浏览器上通行密钥**不出候选** | Google 官方表 61 条 + 社区表 8 条 + 用户自定义表 + 设置界面 |
| 8 | 落库 schema 自造 `Passkey.*` + hex/Base64 私钥 ⇒ **库文件级零互操作**（KDoc 却称「对齐 KeePassXC / KeeWeb / KeePassDX」） | `KPEX_PASSKEY_*`（注释明写 "field names from KeypassXC are used"）+ PKCS#8 PEM |
| 9 | `PasskeyData.backupEligible/backupState` 仅序列化、**无消费点**；flags 无条件置 `BE|BS` | 按条目值回退用户设置 |
| 10 | 不读 `userVerification` 要求（`required` 被当作 `preferred`） | `retrieveUserVerificationRequirement` 驱动验证等级 |
| 11 | 不读 `excludeCredentials`，重复注册不拦截 | 解析并参与注册判定 |
| 12 | 每次注册**一律新建条目**，同站点重复注册产生重复条目 | 选择既有条目 + `setPasskey`（返回 overwrite 提示） |
| 13 | `credentialId` / `userHandle` 入库存为非保护字段 | 两者均 `enableProtection = true` |

另：工作区未提交改动把 `callingPackage` / `rpId` 写进了 `AppLog.i`，与全仓 `ISSUE-P1-10`
「日志不得携带调用包名 / rpId」纪律冲突，本批一并回退（仅保留「收到创建请求（类型名）」「已返回 CreateEntry」）。

## 2. 整改

### 2.1 协议面（请求是什么就做什么）

- 新增 `app/.../passkey/WebAuthnRequestOptions.kt`（模型 `WebAuthnRequest`）：解析 `rp.id` / `rpId` /
  `challenge` / `user.{id,name,displayName}` / `pubKeyCredParams` / `allowCredentials` /
  `excludeCredentials` / `userVerification` / `authenticatorSelection.userVerification` / `extensions.prf`。
- 新增 `app/.../passkey/SimpleJson.kt`：**零依赖** JSON 解析器（深度上限 32、拒绝尾部多余内容与非有限数、
  字符串不做隐式强转）。动机：`org.json` 在宿主单测中是不可用的 Android 桩（调用即抛
  `Method put not mocked`），而本面是安全关键逻辑，必须有可执行单测；语义上也避免 `{"id":123} → "123"` 之类隐式转换。
- 注册：`PasskeyCryptoEngine.generateKeyPairForAlgorithms(pubKeyCredParams)` 按
  **ES256 → Ed25519 → RS256** 偏好协商；请求算法全不支持即 `InvalidKeyException`（fail-closed，绝不降级为未请求算法）。
  `userHandle` 原样采用 RP `user.id`（缺失才随机）；`excludeCredentials` 命中库内既有凭据即拒绝；
  `extensions.prf` 存在时生成并持久化 PRF 秘密，`evalByCredential` 出现即拒绝（规范禁止）。
- 断言：候选按 `allowCredentials` 收敛（空集＝无用户名流程，不收敛）；`option.clientDataHash` 非空时
  **直接对其签名**（特权调用方自带 clientDataJSON）；`userVerification: required` → 强制系统级强验证
  （`CredentialVerificationLauncher.requireBiometric = true`，无认证器/无法建立密码学绑定一律拒绝，不降级为手动确认）。
- PRF：新增 `crypto/.../passkey/PasskeyPrf.kt`。取值 = `HMAC-SHA-256(secret, SHA-256("WebAuthn PRF" || 0x00 || eval))`
  （与 KeePassDX `derivePrfSalt` / `computePrfValue` 逐字节同口径）。

### 2.2 存储面（与 KeePassXC / KeePassDX 库文件级互操作）

- `PasskeyData` 写入口径改为 **`KPEX_PASSKEY_*`**：`RELYING_PARTY` / `USERNAME` / `USER_HANDLE`(保护) /
  `CREDENTIAL_ID`(保护) / `PRIVATE_KEY_PEM`(保护) / `FLAG_BE` / `FLAG_BS`（`1` / `0`，KeePassXC 口径） /
  `PRF`(保护)。
- 本仓扩展键（KPEX 无对应项，对其它管理器是无关属性）：`Passkey.Algorithm` / `PublicKey` / `SignCount` /
  `UserDisplayName` / `CreatedAt`。其中 `Passkey.Algorithm` 使**热路径无需解密私钥即可判定算法**。
- **读入兼容三形态**：① KPEX（新）；② v1 `Passkey.*` 旧键（既有库无需迁移）；③ 外部管理器条目
  （无扩展键 → 由 `core/.../PasskeyKeyText.kt` 按 PKCS#8 `AlgorithmIdentifier` 的 OID **纯字节嗅探**算法）。
- 私钥文本形态改为 **PKCS#8 PEM**（`PasskeyPkcs8Codec`）：生成侧 ES256/Ed25519/RS256 统一输出 PEM
  （EC 使用**命名曲线** `secp256r1`，避免内联显式曲线参数导致 KeePassXC 拒收）；签名侧
  `pemCharsToSigningKey` 还原为 32 字节标量 / 32 字节种子 / 整段 PKCS#8 DER —— **Rust 内核快路径不受影响**
  （仍需 32 字节原始标量 / 种子）。历史 v1 形态（hex / Base64）保留解码分支。
- `excludeCredentials` 查重与「原地替换」：新增 `saveOrReplacePasskeyEntry`（同 rpId + 同用户名命中既有条目时
  保留其它字段、仅换新 Passkey schema），`findExistingPasskeyCredentialIds`（单趟扫描）。

### 2.3 平台面（浏览器可用性）

- `CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST` 由**手抄字面量**改为**派生自**
  `BrowserSigningFingerprints.TRUSTED`（已取证指纹表，每条注明来源与核实方式）→ 同时消除
  「同一指纹两种写法互相打架」（ISSUE-P3-88 类）与「CM 通道仅 Chrome、自动填充通道已含 Firefox」的通道口径不一致。
- 新增 `PasskeyPrivilegedBrowserStore`（用户显式启用的其它浏览器）：指纹不臆写，而是**现场读取该包自身签名证书的
  SHA-256**（与系统校验同源）；列表由 `queryIntentActivities(https VIEW)` 得出（**不需要 `QUERY_ALL_PACKAGES`**）。
- 新增设置页「特权浏览器白名单」（自动填充页入口 + 路由 `settings/privileged_browsers`），逐项开关 + 风险提示。

### 2.4 语义面

- `PasskeyAuthFlags` 的 `BE` / `BS` 改由**该凭据的持久化值**驱动（不再无条件置位）。
- 创建链路锁库时在**同一受保护窗口内**呈现解锁页（新增 `CredentialUnlockPresenter`）后继续流程。
  为何不是「先回传解锁 Action、解锁后再呈现创建条目」：`androidx.credentials:1.6.0` 的
  `PendingIntentHandler` **没有** `setBeginCreateCredentialResponse`（已核对该版本 AAR 的公开方法表），
  该链路在创建方向不存在。
- `credentialId` / `userHandle` 入库改为受保护字段（对齐 KeePassXC / KeePassDX）。

## 3. 验证

- 全量宿主单测：`.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL**，
  聚合 **`tests=2184 skipped=13 failures=0 errors=0`**（按 `*/build/test-results/*/TEST-*.xml` 汇总）。
  **差额声明**：§148 基准为 `tests=2144`；本批**新增 29 例**（清单见下），差额未逐例溯源（口径差异未核实），
  **不作**「2144 + 29 = 2173 吻合」这类结论。
- 设备侧源集编译：`:app:` / `:crypto:` / `:database:` 三个 `compileDebugAndroidTestKotlin` 通过。
- Android Lint：`:app:lintDebug` / `:crypto:lintDebug` / `:core:lintDebug` **BUILD SUCCESSFUL**。
  首轮 `:app:lintDebug` 报 **4 个 `MissingTranslation` 错误**（本批新增的 4 条中文串未提供英文），
  已补 `values-en/strings.xml` 对应英文条目后复跑通过——**本批自身引入的 lint 错误已清零**（既有 213 条 warning 未处理）。
- 新增 29 例：
  - `PasskeyDataSchemaInteropTest`（core，8 例）：KPEX 键名与保护位、无损读回、三算法 OID 嗅探、
    外部条目缺字段回落、v1 旧键名解析、KPEX 优先、BE/BS 文本宽松解析（`1/0`、`true/false`、非法值）、未知字段不干扰；
  - `WebAuthnRequestOptionsTest`（app，14 例）：注册 / 断言两类字段解析、`userVerification` 缺省与大小写、
    PRF 两输入与 `evalByCredential`、畸形 JSON 与类型不符一律安全缺省、Base64URL 解码边界、
    以及 `SimpleJson` 自身的转义 / 深度上限 / 尾部多余 / 数字解析；
  - `PasskeyPrfTest`（crypto，5 例）：秘密生成与还原、客户端侧处理等价规范域分隔哈希、
    取值等价 JDK `HmacSHA256` 独立复算、不同输入不同输出、非法秘密 fail-closed；
  - 扩展既有用例：`PasskeyAuthFlagsTest` +1（BE/BS 驱动）、`CredentialCandidateMatcherTest` +1（allowCredentials 收敛）。
- 改写既有用例（语义保留、判据加强）：
  - `PasskeyCryptoEngineTest`（4 处）：私钥文本契约由「64 位 hex」改为「PKCS#8 PEM」，签名材料统一经 PEM 通道还原；
  - `PasskeyPkcs8CodecTest`：由「DER → 驻留文本」改为「DER ↔ 签名材料 + PEM 往返 + 命名曲线 OID 必须写出」；
  - `BrowserFingerprintFormatTest`：由「源码文本双副本一致」改为「**派生 JSON 与已取证指纹表逐字一致 + 规范形态**」
    （判据更强：源码副本一致性在派生实现下已无意义），并改用零依赖解析器（`org.json` 在宿主单测不可用）；
  - `AlgoHotPathGuardsTest`：形状短路断言的**实现文本**随双 schema 更新（不变式未变：短路区间不得解密、不得建 Map）。

## 4. 如实声明（未做 / 未验证）

1. **未跑设备侧 / 真机**（`connectedDebugAndroidTest` 与 `assembleRelease` 未跑；`lint` 见 §3 已跑）。
   本批的创建链路解锁 UI、`clientDataHash` 特权路径、`allowCredentials` 候选收敛、PRF 输出**均未经真机验证**；
   `已知工程限界.md` §4.1 记载的「Passkey 系统级交互未覆盖」**不因本批改变**。
2. **KPEX 互操作只有格式面证据**：写入形态按 KeePassXC 源码口径（`KPEX_PASSKEY_*` + PKCS#8 PEM）实现，
   并有本仓「编码 ↔ 解码」往返与 OID 断言；**未**用 `keepassxc-cli` / `pykeepass` / 真实 KeePassDX
   对本仓产物做端到端对拍 ⇒ **不得**据本批宣称「互操作已验证」（§38 证据纪律）。
3. **v1 旧条目不自动迁移**：写入侧不再产出旧键，但既有库中的 `Passkey.*` 条目在下次保存前仍是非 KPEX 形态
   （读侧永久兼容、断言功能不受影响；「原地替换 Passkey」路径也只在用户重新注册该站点时才改写）。
4. **PRF 未与真实 RP 对拍**：实现按 W3C Level 3 §10.1 与 KeePassDX 口径，并由 JDK `HmacSHA256` 独立复算锁定；
   但「同一账号在 Chrome/其它管理器与本应用的 PRF 输出一致」未经真机端到端验证。
5. **条目复用语义变化**：同 rpId + 同用户名再次注册会**原地替换**该条目的 Passkey 字段（此前新建重复条目），
   KDBX 历史快照可回滚；此为行为变更，未在 UI 上提供额外确认。
6. **用户启用的浏览器可断言任意 web origin**（浏览器委派的固有语义）——属显式授权取舍，
   已登记 [`产品裁决登记.md`](../../architecture/产品裁决登记.md) `PD-10`。

## 5. 涉及文件

- `core`：`model/PasskeyData.kt`（schema 与保护位）、`model/PasskeyKeyText.kt`（新增：PEM/OID 字节级工具）。
- `crypto`：`passkey/PasskeyPkcs8Codec.kt`（重写：双向 + PEM）、`passkey/PasskeyPrf.kt`（新增）、
  `passkey/PasskeyCryptoEngine.kt`（PEM 生成 + 算法协商 + `decodePemPrivateKeyText`）。
- `app`：`passkey/{WebAuthnRequestOptions,SimpleJson,CredentialUnlockPresenter,CredentialCandidateMatcher,PasskeyAuthFlags,PasskeyCreateActivity,PasskeyAssertionActivity,CredentialResponseAssembler,PasswordSaveActivity,CallingOriginResolver,KeePasskeyCredentialProviderService}.kt`、
  `data/repository/{PasskeyEntryCoordinator,PasskeyPrivilegedBrowserStore(新增),VaultRepository,RealVaultRepository}.kt`、
  `ui/.../subscreens/PrivilegedBrowserSettingsScreen.kt`(新增) 与设置页 / 导航接线、`res/values/strings.xml`。
- 测试：见 §3 清单（含 `app/src/test/.../FakeVaultRepository.kt` 的契约同步）。
