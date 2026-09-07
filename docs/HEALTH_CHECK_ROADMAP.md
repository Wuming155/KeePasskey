# KeePasskey 技术体检整改路线图

> **更新时间**：2026-09-07
> **文档定位**：本文档为 `STATUS.md` 第 2 节「未完成工作看板」中**体检批次**（TASK-01 / TASK-03 ~ TASK-07）的详细落地规划，仅作执行依据；批次的「状态 / 优先级」以 `STATUS.md` 看板为唯一真相源。
> **编号规则**：旧「Wave」编号体系已整体冻结（见 `STATUS.md` §4），自本文档起批次统一以 **STATUS TASK ID + Git 提交号**标识。
> **来源**：基于 google-developer-knowledge（developer.android.com 官方语料）完成的技术体检结论。
> **前提**：应用不支持用户自建服务器（不含内网 NAS、私有 WebDAV、本地 S3），所有同步通信仅面向正规公网商业云服务。
> **用途**：体检条目分批整改规划。各批次按风险与依赖排序，逐批执行、逐批验收；本文档随批次完成滚动更新。

---

## 批次总览

| 批次 | TASK | 主题 | 官方依据要点 |
| :--: | :--: | --- | --- |
| A | — | 传输安全：移除证书固定 + 全局强制 HTTPS | security-ssl「pinning not recommended」、security-config |
| B | — | 标准库对齐与安全纵深：PSL 全量接入 + HMAC 归一 JCE + readFully 等价收敛 | publicsuffix.org 官方算法、RFC 4231/2104、JDK DataInputStream |
| C | TASK-01 | HMAC 防篡改回归锁 flaky 排查与定型 | KDBX4 HMAC 块流规范（终止块必校验）；篡改检测零漏报 |
| D | TASK-03 | kapt→KSP 迁移 + 启用 built-in Kotlin | kapt maintenance mode；AGP 10 移除 opt-out |
| E | TASK-04 | Settings 迁移 Preferences DataStore | DataStore「aimed at replacing SharedPreferences」 |
| F | TASK-05 | Gradle 版本目录 + 依赖货币性刷新 | migrate-to-catalogs；AndroidX 版本渠道 |
| G | TASK-06 | Baseline Profiles + Startup Profiles | 首启/交互约 30% 提升；AGP 9.1 全支持 |
| H | TASK-07 | compileSdk 37 → Material 3 Expressive；minSdk 决策固化 | M3 Expressive 配合 Android 16 视觉 |
| 持续 | — | Passkey UX 最佳实践 / 依赖周期核对 / 配置缓存评估 | credential-manager UX 指南 |

> **状态唯一真相源**：上表仅列批次规划（批次 / TASK / 主题 / 依据），**不维护状态列**；各批次的实时状态与优先级以 `STATUS.md` §2「未完成工作唯一看板」为唯一真相源（本文档开头已声明）。批次 A / B 已完成，提交号见 `STATUS.md` §4。

> **关联提交**：批次 A（`a7efa12`）、B（`b05d18b`）及全部历史改动统一登记于 `STATUS.md` §4 历史改动日志索引；批次 C/D/E/F/G/H 完成后以提交号回填该节，本文档不再重复维护提交号。

> **编号说明**：批次按执行顺序连续编号为 **A~H**（A/B 已完成，C=进行中 HMAC 排查，D~H 规划中）；旧 Wave 编号体系已冻结（见 `STATUS.md` §4），本文档不再使用 Wave 编号。批次状态/优先级以 `STATUS.md` §2 为唯一真相源，完成以 Git 提交号登记于 `STATUS.md` §4 改动日志。

---

## 批次 A（提交 `a7efa12`）：传输安全整改

**范围与落地**：
1. **证书固定全量移除**（官方依据：`developer.android.com/privacy-and-security/security-ssl`——"Certificate pinning ... is not recommended for Android apps"；锁定会阻碍云厂商常规证书轮换导致连接阻断，且本应用仅面向商业云、无自建服务器场景）：
   - `SyncNetworkOptions` 删除 `pinnedHosts` 契约字段；`SyncHttpClientFactory` 删除 `CertificatePinner` 组装，回归「TLS-only + 显式超时」单一职责；
   - `SyncCoordinator` 删除 `buildNetworkOptions` 解析器与 `PIN_SCHEME_PREFIX`；`SyncCredentialsStore` 删除 `WebDavCredentials.certPins` 字段、`saveWebDavCertPins` 与存储键写入；
   - UI 链路（`SettingsViewModel`/`SettingsUiState`/`WebDavSyncScreen`/`KeePasskeyApp`）与中英字符串同步移除；
   - **遗留数据清理**：旧版本已存的 `webdav_cert_pins` 键在 `loadWebDavConfig` 加载期一次性物理清除；
   - 移除后证书验证**完全依赖系统默认 CA 链**（代码库本无自定义 TrustManager，不新增任何信任逻辑）。
2. **全站强制 HTTPS（双层防御）**：
   - 平台层：新增 `app/src/main/res/xml/network_security_config.xml`——`<base-config cleartextTrafficPermitted="false">` + 仅系统 CA 信任锚（显式固化 targetSdk 28+ 默认行为，并拒绝用户 CA 注入 MITM），零 domain-config 豁免；Manifest 挂载 `android:networkSecurityConfig`；
   - 传输层：保留 OkHttp `ConnectionSpec` TLS-only（排除 CLEARTEXT）防线；
   - 输入层：`SettingsViewModel.updateWebDavConfig/updateS3Config` 保存期 https-only 归一化校验（无 scheme 自动补 `https://`，显式 `http://` 拒绝并反馈）；`WebDavSyncProvider`/`S3SyncProvider` 构造期 fail-fast 抛类型化 `SyncException.InvalidEndpointError`（豁免 MockWebServer 回环测试注入路径），`runSyncCycle` 将其上浮为用户可理解的同步失败反馈。
3. **S3 Path-Style 保留**：`usePathStyle` 字段/UI 开关/存储键保留（Cloudflare R2 等商业 S3 兼容服务适用），仅修正「自建 MinIO」相关中英文案。
4. **作废旧设计**：旧交付计划中关于「自签名 SSL/TLS 证书信任与局域网 HTTP 明文豁免」的表述作废改写。

**验收**：全模块单测全绿（含新增：遗留键清除、http:// 构造期拒绝、https/无 scheme 放行、系统 CA 链无 pin 装配断言）+ `assembleDebug` 通过；`certPin|pinnedHosts|CertificatePinner` 生产代码零残留（仅余守卫性注释/负向断言与遗留清理代码）。

---

## 批次 B（提交 `b05d18b`）：标准库对齐与安全纵深

> 安全项插队说明：P1（PSL 盲区）属 Passkey 安全核心，优先于批次 D 执行。

**范围与落地**：
1. **P1 `DomainMatcher` 接入完整 PSL**（安全整改）：
   - 原 47 条硬编码 `MULTILABEL_PUBLIC_SUFFIXES` 存在真实漏判盲区（`edu.cn`/`gov.au`/`co.id`/`ac.jp`/`github.io` 自身等），后果是整条公共后缀被当作可注册域放行作 RP ID（恶意 kdbx 导入植入 `rpId="edu.cn"` 可匹配任意 `*.edu.cn` origin，破坏 WebAuthn 域边界）；
   - 打包 Mozilla 官方 `public_suffix_list.dat`（约 330KB，MPL-2.0，版本 2026-09-05）至 `app/src/main/resources/publicsuffix/`，不引 Guava / 不复用 OkHttp internal 类；
   - 新增 `PublicSuffixList.kt`（纯 Kotlin 零依赖，~150 行）：惰性单例 + 双检锁加载，精确/通配 `*`/例外 `!` 三类规则分离，官方算法（例外优先 → 最长匹配 → 默认规则 `*`）；IDN 规则与查询 host 经 `java.net.IDN.toASCII` 归一 punycode；数据加载失败 fail-closed（一律判不可注册）；
   - `DomainMatcher.isRegistrableDomain` 改为委托 `PublicSuffixList`，对外 5 个函数签名全部不动；`isDomainMatch` 两侧 host 补 IDN punycode 归一（unicode/punycode 跨表示可匹配）；
   - 测试：漏判项拒绝（edu.cn/gov.au/co.id/ac.jp）、可注册域放行、`*.ck` 通配、`!www.ck` 例外、私有段（github.io 自身拒绝/子域放行）、IDN 等价、尾点/空串/空标签 fail 处理，共 6 个新用例。
2. **P2 `HashUtil` HMAC 归一 JCE**：
   - 先补测试后动手：新增 RFC 4231 Test Case 1/2 已知答案（HMAC-SHA256/512）+ Test Case 6（131 字节超块长密钥，锁定 RFC 2104「先哈希密钥」语义）+ vararg 分块与单数组一致性 + 输出长度 32/64，共 5 个新用例（对改写前 BC 实现跑绿，双重校验向量与实现）；
   - `hmacSha256`/`hmacSha512` 从 BC lightweight API（`HMac(SHA256Digest())`）改写为 JCE（`Mac.getInstance` + `SecretKeySpec`），与项目既有 `InMemoryCipher`/`OtpEngine` 用法统一；分块 `update`、`CryptoException.HashException` 包装与函数签名不变；
   - **明确不做**：不删 `bcprov-jdk18on`（Twofish/ChaCha20/Argon2/InnerRandomStream/PasskeyCryptoEngine 仍强制依赖），不碰其余 BC 使用点。注意：原 BC lightweight API 自包含可用、不经过平台 BC Provider，本项为一致性收敛而非缺陷修复。
3. **P3 `LittleEndianUtil.readBytes` 等价收敛**：
   - 手写 while 循环换 `DataInputStream.readFully`（「读满否则抛 EOFException」语义逐字等价）；**报告的 `readNBytes(length)` 方案否决**（"up to" 短读静默返回，破坏 KDBX 严格长度解析）；
   - 实现偏差修正：方案原稿 `.use { }` 包装会经 `DataInputStream.close` 传导关闭底层流、破坏 KDBX 流式解析，实际落地为不关闭包装流；`intTo4Bytes`/`bytesToInt` 等小端数值函数保留（KDBX 小端解析标准做法，KAT 已覆盖）。

**验收**：全模块单测全绿（该批次交付时基线为 350 例；截至 2026-09-07 全量基线已为 417 例，增量为后续提交所致，非本批次回退）；`readNBytes` 否决理由与 `.use` 包装关闭底层流的坑均已在代码注释中固化。

---

## 批次 C（TASK-01）：HMAC 防篡改回归锁 flaky 排查与定型

> **安全项插队说明**：`KdbxCompatibilityAndSecurityTest.testCorruptHmacBlockThrowsKdbxInvalidCredentialsException` 守护的是 **KDBX 防篡改检测**这一核心安全属性（被篡改的密码库必须被拒绝加载），且已**实证存在不确定性**（非理论风险），故优先于批次 D 执行。

**问题陈述**：
- **现象**：篡改 KDBX4 文件末尾字节后加载，期望抛 `KdbxInvalidCredentialsException`，但**偶发**出现 `nothing was thrown`——即篡改后的库被成功加载。实测同一份代码 6 次运行失败 1 次（单独执行该类稳定通过，全量执行时间歇复现）。
- **已排除**：与 `KdbxFile` 密钥派生路径的非空断言清理无关（回退对照验证通过）；与 Keystore / 运行环境无关。
- **现有兜底**：`KdbxFile.loadPayload` 在「旧派生探针」与「正常」两条路径之后统一调用 `HmacBlockInputStream.verifyEndOfStream()`，强制消费至终止块并校验其 HMAC，未正常终止则抛 `KdbxCorruptFileException`。因此理论上篡改应被稳定检出，需查清为何偶发不抛。

**排查范围（两步定因，按序执行）**：
1. **先证伪测试侧假设**（低成本优先）：用例以硬编码偏移 `bytes[bytes.size - 20]` 赌其落在终止块 HMAC 内（终止块固定为文件末尾 36 字节 = 32B HMAC + 4B size=0）。需断言/打印文件末尾布局，确认该偏移是否**恒定**落在终止块 HMAC 内；若不恒定，改为**显式定位终止块**而非硬编码偏移，使用例恢复确定性。
2. **若布局恒定成立，则追代码侧漏检**（安全缺陷，优先级立即提升）：查 `verifyEndOfStream()` 为何在终止块 HMAC 被篡改时判定通过。重点核查 `HmacBlockInputStream.loadNextBlock()` 的 `terminated` 置位与 HMAC 比较路径、`readBlock()` 探针经 `SequenceInputStream` 回填是否影响块游标、以及 GZip 层预读对底层流游标的影响。

**风险**：第 2 步若成立，意味着存在「被篡改 KDBX 被静默接受」的窗口，属高危。**严禁**通过删除或放宽断言的方式"修复"。

**验收**：
- 根因结论明确，并固化于代码注释或本文档；
- 该用例在**连续 ≥20 次全量运行**（含 `--rerun-tasks` 强制重跑）中零失败；
- 若属测试侧问题：用例改为确定性定位，且保持「篡改必被拦截」的断言强度不降低；
- 若属代码侧问题：补最小化回归用例锁死修复，并评估是否需同步审计 `HmacBlockStream` 读写两侧；
- 刷新 `AGENTS.md` 当前状态与「已知限界」条目。

**回退策略**：排查期不改生产解析逻辑；第 1 步若证实为测试假设问题，改动仅限测试代码，零生产风险。

---

## 批次 D（TASK-03）：kapt→KSP 迁移 + 启用 built-in Kotlin

**官方依据**：`developer.android.com/build/migrate-to-ksp`——"Kapt is now in maintenance mode, and we recommend that you migrate from kapt to KSP"；KSP 对 Kotlin 代码直接分析，构建最高快 2x。`migrate-to-built-in-kotlin`——AGP 9.0 起内置 Kotlin 与 `org.jetbrains.kotlin.kapt` 插件**不兼容**；`android.builtInKotlin=false` 的 opt-out 在 **AGP 10.0 将被移除**（当前 `gradle.properties` 的 `builtInKotlin=false`/`newDsl=false` 是死路配置）。

**范围**：
- `build.gradle.kts`（root）：`kapt` 插件声明移除；`app/build.gradle.kts`：`kapt("com.google.dagger:hilt-compiler:2.60.1")` → `ksp("com.google.dagger:hilt-android-compiler:2.60.1")`（Hilt 自 2.48 起支持 KSP，官方文档已用 `ksp()` 示例）；
- `gradle.properties`：移除 `android.builtInKotlin=false` 与 `android.newDsl=false`，迁移至 AGP 9 内置 Kotlin（项目已用 `kotlin.compilerOptions` DSL，迁移平滑；内置 Kotlin 下 `jvmTarget` 默认跟随 `compileOptions.targetCompatibility`）；
- 若存在无法迁移的注解处理器，过渡用 `com.android.legacy-kapt`（同 AGP 版本）——本项目仅 Hilt 使用 kapt，预计可直接切 KSP。

**风险**：KSP 插件版本需与 Kotlin 2.4.10 配套（参考 KSP GitHub Releases 选择配套版本）；KSP 对可空性类型信息更精确，可能暴露少量源码修正点。

**验收**：`test` 全绿 + `assembleDebug` + `minifyReleaseWithR8` 通过；对比迁移前后 `:app:compileDebugKotlin` 构建耗时；AGENTS.md 刷新并提交。

**回退策略**：单提交回滚即可（构建配置变更不触碰业务代码）。

---

## 批次 E（TASK-04）：RealSettingsRepository 迁移 Preferences DataStore

**官方依据**：`developer.android.com/topic/libraries/architecture/datastore`——"Jetpack DataStore is a new and improved data storage solution aimed at replacingSharedPreferences. Built on Kotlin coroutines and Flow"；提供 `SharedPreferencesMigration` 平滑迁移；事务性、异步、一致性强于 SharedPreferences。

**范围**：
- `app` 引入 `androidx.datastore:datastore-preferences`；
- `RealSettingsRepository` 重写为 Preferences DataStore（`dataStore` 属性委托单例），现有 `Flow<UserSettings>` 语义天然对齐，可整体替换 `callbackFlow` + `OnSharedPreferenceChangeListener` 手工桥接；
- 用 `SharedPreferencesMigration` 完成 `keepasskey_settings` 键值一次性迁移，迁移后删除旧 prefs 文件。

**边界（重要）**：`SyncCredentialsStore`/`BiometricCredentialStorage` 的**加密凭据存储保持手动 Android Keystore AES-256-GCM 方案不动**——官方已在 `security-crypto 1.1.0` 废弃 `EncryptedSharedPreferences` 与 `MasterKey`（建议"Use Android Keystore directly instead"），现状即合规，**不得**反向迁移。

**风险**：DataStore 单文件单实例约束（需保证单例）；迁移幂等性。

**验收**：冷启动设置保留用例、迁移后旧 prefs 文件清理用例、全量测试绿。

---

## 批次 F（TASK-05）：Gradle 版本目录 + 依赖货币性刷新

**官方依据**：`developer.android.com/build/migrate-to-catalogs`——版本目录使多模块依赖与插件集中、类型安全、可辅助补全；本项目 5 模块正是最大受益场景。

**范围**：
- 新建 `gradle/libs.versions.toml`，将 5 个 `build.gradle.kts` 的硬编码依赖/插件全部迁移为 `alias(libs.xxx)`；
- 依赖货币性核对（以官方 release 页为准）：
  - `androidx.hilt` 1.3.0 → **1.4.0**（2026-07 稳定）；注意 `hiltViewModel()` 已迁移至新构件 `androidx.hilt:hilt-lifecycle-viewmodel-compose`（解耦 `androidx.navigation` 传递依赖），择机迁移 import；
  - `androidx.credentials` 1.6.0、`androidx.biometric` 1.1.0（1.4.0-alpha 含现代 `registerForAuthenticationResult()` Activity-Result API 与 `biometric-compose`，安全关键组件暂持稳定渠道，持续观察其转正）；
  - `lifecycle`/`activity`/`navigation`/`core-ktx` 核对最新稳定；`okhttp` 4.12.0 仍为最新稳定；`bcprov-jdk18on` 在 Android 上为合适构件（保留，注意 R8 keep 规则）；
  - 评估启用 Gradle configuration cache（构建已提示）。

**风险**：`hilt-navigation-compose` → `hilt-lifecycle-viewmodel-compose` 构件迁移涉及全量 `hiltViewModel()` import 调整。

**验收**：全模块测试绿 + release 混淆构建通过；`./gradlew.bat build` 无依赖解析告警。

---

## 批次 G（TASK-06）：Baseline Profiles + Startup Profiles

**官方依据**：`developer.android.com/topic/performance/baselineprofiles`——Baseline Profiles 使首启与关键交互约 **30%** 提速；官方建议 **Baseline + Startup Profiles 同时使用**（后者优化 DEX 布局再提升约 15%）；AGP **9.1** 已支持库模块全源集目录。

**范围**：
- `app` 引入 `androidx.profileinstaller:profileinstaller` + `androidx.benchmark:benchmark-macro-junit4` + Baseline Profile Gradle Plugin；
- 用 Macrobenchmark 采集关键用户旅程：冷启动解锁、Vault 列表滚动、条目详情/编辑、TOTP 列表刷新；
- 生成 `baseline-prof.txt` + `startup-prof.txt`，随 release 构建打包（与既有 R8 全量混淆协同）。

**风险**：生成器需真机/模拟器跑 UI Automator 旅程；profile 体积需 < 1.5MB。

**验收**：Macrobenchmark StartupBenchmark 前后对比（启动耗时下降可量化）；release 包 profile 资产存在。

---

## 批次 H（TASK-07）：compileSdk 37 → Material 3 Expressive；minSdk 决策固化

**官方依据**：`developer.android.com/develop/ui/compose/designsystems/material3`——Material 3 Expressive 是 "the next evolution of Material Design ... complements the Android 16 visual style and system UI"；需 Compose BOM 2026.08.00+（Compose 1.12.x，要求 compileSdk 37）。

**范围**：
- `compileSdk`/`targetSdk` 36 → 37（配套核对 AGP/Kotlin 兼容矩阵）；
- Compose BOM 2026.06.01 → 2026.08.00+，评估 M3 Expressive 主题（色彩/形状/动效）与现有 SAPPHIRE 等自定义调色板的融合策略；
- **minSdk=36 决策固化**：在 README/AGENTS.md 明确「仅 Android 16+」基线的理由（Credential Provider 需 API 36）与代价（放弃 16 以下存量设备；`<36` 场景需退化为传统 Autofill）；若产品决策下探至 34/35，需补功能降级分支——本批次仅固化文档，不做代码变更。

**风险**：targetSdk 37 平台行为变更清单核查；M3 Expressive 部分组件仍标注 experimental（`ExperimentalMaterial3Api`）。

**验收**：全量测试绿 + release 构建通过；新 BOM 下 UI 走查（15 屏）无回归。

---

## 持续项（不设 TASK，纳入例行维护）

1. **Credential Manager / Passkey UX 最佳实践**（官方 2026 指南）：设置页展示每个 Passkey 元数据（rp.id/创建时间/绑定）；在账户创建、登录后、恢复、密码重置等关键时刻引导创建 Passkey；关注 Digital Credential API 与 Verified Email via Credential Manager（2026-04）新能力。
2. **依赖版本周期核对**：每季度按 AndroidX 稳定渠道核对一次；安全关键组件（credentials/biometric/crypto）坚持稳定渠道优先。
3. **网络安全基线复审**：Network Security Config 与 TLS-only 双层防线保持零豁免；如未来引入任何新网络客户端（如 WebView），复核其受平台策略覆盖。
4. **文档如实化纪律**：每批次完成后刷新 `AGENTS.md` 当前状态，本文档滚动更新状态列。
