# KeePasskey 技术体检整改路线图（Wave 14+）

> **来源**：2026-09-06 基于 google-developer-knowledge（developer.android.com 官方语料）完成的全量技术体检报告。
> **前提（新）**：应用不支持用户自建服务器（不含内网 NAS、私有 WebDAV、本地 S3），所有同步通信仅面向正规公网商业云服务。
> **用途**：体检报告全部条目的分批整改规划。各批次按风险与依赖排序，逐批执行、逐批验收；本文档随批次完成滚动更新。

---

## 批次总览

| 批次 | Wave | 主题 | 官方依据要点 | 状态 |
| :--: | :--: | --- | --- | :--: |
| A | **14** | 传输安全：移除证书固定 + 全局强制 HTTPS | security-ssl「pinning not recommended」、security-config | ✅ 已完成 |
| B | 15 | kapt→KSP 迁移 + 启用 built-in Kotlin | kapt maintenance mode；AGP 10 移除 opt-out | 📋 规划 |
| C | 16 | Settings 迁移 Preferences DataStore | DataStore「aimed at replacing SharedPreferences」 | 📋 规划 |
| D | 17 | Gradle 版本目录 + 依赖货币性刷新 | migrate-to-catalogs；AndroidX 版本渠道 | 📋 规划 |
| E | 18 | Baseline Profiles + Startup Profiles | 首启/交互约 30% 提升；AGP 9.1 全支持 | 📋 规划 |
| F | 19 | compileSdk 37 → Material 3 Expressive；minSdk 决策固化 | M3 Expressive 配合 Android 16 视觉 | 📋 规划 |
| 持续 | — | Passkey UX 最佳实践 / 依赖周期核对 / 配置缓存评估 | credential-manager UX 指南 | 🔄 长期 |

---

## 批次 A（Wave 14，已完成）：传输安全整改

**范围与落地**：
1. **证书固定全量移除**（官方依据：`developer.android.com/privacy-and-security/security-ssl`——"Certificate pinning ... is not recommended for Android apps"；锁定会阻碍云厂商常规证书轮换导致连接阻断，且本应用仅面向商业云、无自建服务器场景）：
   - `SyncNetworkOptions` 删除 `pinnedHosts` 契约字段；`SyncHttpClientFactory` 删除 `CertificatePinner` 组装，回归「TLS-only + 显式超时」单一职责；
   - `SyncCoordinator` 删除 `buildNetworkOptions` 解析器与 `PIN_SCHEME_PREFIX`；`SyncCredentialsStore` 删除 `WebDavCredentials.certPins` 字段、`saveWebDavCertPins` 与存储键写入；
   - UI 链路（`SettingsViewModel`/`SettingsUiState`/`WebDavSyncScreen`/`KeePasskeyApp`）与中英字符串同步移除；
   - **遗留数据清理**：旧版本已存的 `webdav_cert_pins` 键在 `loadWebDavConfig` 加载期一次性物理清除；
   - 移除后证书验证**完全依赖系统默认 CA 链**（代码库本无自定义 TrustManager，不新增任何信任逻辑）。
2. **全站强制 HTTPS（双层防御）**：
   - 平台层：新增 `app/src/main/res/xml/network_security_config.xml`——`<base-config cleartextTrafficPermitted="false">` + 仅系统 CA 信任锚（显式固化 targetSdk 28+ 默认行为，并拒绝用户 CA 注入 MITM），零 domain-config 豁免；Manifest 挂载 `android:networkSecurityConfig`；
   - 传输层：保留 Wave 12 的 OkHttp `ConnectionSpec` TLS-only（排除 CLEARTEXT）防线；
   - 输入层：`SettingsViewModel.updateWebDavConfig/updateS3Config` 保存期 https-only 归一化校验（无 scheme 自动补 `https://`，显式 `http://` 拒绝并反馈）；`WebDavSyncProvider`/`S3SyncProvider` 构造期 fail-fast 抛类型化 `SyncException.InvalidEndpointError`（豁免 MockWebServer 回环测试注入路径），`runSyncCycle` 将其上浮为用户可理解的同步失败反馈。
3. **S3 Path-Style 保留**：`usePathStyle` 字段/UI 开关/存储键保留（Cloudflare R2 等商业 S3 兼容服务适用），仅修正「自建 MinIO」相关中英文案。
4. **作废旧设计**：`DELIVERY_PLAN.md` 阶段 5「自签名 SSL/TLS 证书信任与局域网 HTTP 明文豁免」表述作废改写。

**验收**：全模块单测全绿（含新增：遗留键清除、http:// 构造期拒绝、https/无 scheme 放行、系统 CA 链无 pin 装配断言）+ `assembleDebug` 通过；`certPin|pinnedHosts|CertificatePinner` 生产代码零残留（仅余守卫性注释/负向断言与遗留清理代码）。

---

## 批次 B（Wave 15）：kapt→KSP 迁移 + 启用 built-in Kotlin

**官方依据**：`developer.android.com/build/migrate-to-ksp`——"Kapt is now in maintenance mode, and we recommend that you migrate from kapt to KSP"；KSP 对 Kotlin 代码直接分析，构建最高快 2x。`migrate-to-built-in-kotlin`——AGP 9.0 起内置 Kotlin 与 `org.jetbrains.kotlin.kapt` 插件**不兼容**；`android.builtInKotlin=false` 的 opt-out 在 **AGP 10.0 将被移除**（当前 `gradle.properties` 的 `builtInKotlin=false`/`newDsl=false` 是死路配置）。

**范围**：
- `build.gradle.kts`（root）：`kapt` 插件声明移除；`app/build.gradle.kts`：`kapt("com.google.dagger:hilt-compiler:2.60.1")` → `ksp("com.google.dagger:hilt-android-compiler:2.60.1")`（Hilt 自 2.48 起支持 KSP，官方文档已用 `ksp()` 示例）；
- `gradle.properties`：移除 `android.builtInKotlin=false` 与 `android.newDsl=false`，迁移至 AGP 9 内置 Kotlin（项目已用 `kotlin.compilerOptions` DSL，迁移平滑；内置 Kotlin 下 `jvmTarget` 默认跟随 `compileOptions.targetCompatibility`）；
- 若存在无法迁移的注解处理器，过渡用 `com.android.legacy-kapt`（同 AGP 版本）——本项目仅 Hilt 使用 kapt，预计可直接切 KSP。

**风险**：KSP 插件版本需与 Kotlin 2.4.10 配套（参考 KSP GitHub Releases 选择配套版本）；KSP 对可空性类型信息更精确，可能暴露少量源码修正点。

**验收**：`test` 全绿 + `assembleDebug` + `minifyReleaseWithR8` 通过；对比迁移前后 `:app:compileDebugKotlin` 构建耗时；AGENTS.md 刷新并提交。

**回退策略**：单提交回滚即可（构建配置变更不触碰业务代码）。

---

## 批次 C（Wave 16）：RealSettingsRepository 迁移 Preferences DataStore

**官方依据**：`developer.android.com/topic/libraries/architecture/datastore`——"Jetpack DataStore is a new and improved data storage solution aimed at replacingSharedPreferences. Built on Kotlin coroutines and Flow"；提供 `SharedPreferencesMigration` 平滑迁移；事务性、异步、一致性强于 SharedPreferences。

**范围**：
- `app` 引入 `androidx.datastore:datastore-preferences`；
- `RealSettingsRepository` 重写为 Preferences DataStore（`dataStore` 属性委托单例），现有 `Flow<UserSettings>` 语义天然对齐，可整体替换 `callbackFlow` + `OnSharedPreferenceChangeListener` 手工桥接；
- 用 `SharedPreferencesMigration` 完成 `keepasskey_settings` 键值一次性迁移，迁移后删除旧 prefs 文件。

**边界（重要）**：`SyncCredentialsStore`/`BiometricCredentialStorage` 的**加密凭据存储保持手动 Android Keystore AES-256-GCM 方案不动**——官方已在 `security-crypto 1.1.0` 废弃 `EncryptedSharedPreferences` 与 `MasterKey`（建议"Use Android Keystore directly instead"），现状即合规，**不得**反向迁移。

**风险**：DataStore 单文件单实例约束（需保证单例）；迁移幂等性。

**验收**：冷启动设置保留用例、迁移后旧 prefs 文件清理用例、全量测试绿。

---

## 批次 D（Wave 17）：Gradle 版本目录 + 依赖货币性刷新

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

## 批次 E（Wave 18）：Baseline Profiles + Startup Profiles

**官方依据**：`developer.android.com/topic/performance/baselineprofiles`——Baseline Profiles 使首启与关键交互约 **30%** 提速；官方建议 **Baseline + Startup Profiles 同时使用**（后者优化 DEX 布局再提升约 15%）；AGP **9.1** 已支持库模块全源集目录。

**范围**：
- `app` 引入 `androidx.profileinstaller:profileinstaller` + `androidx.benchmark:benchmark-macro-junit4` + Baseline Profile Gradle Plugin；
- 用 Macrobenchmark 采集关键用户旅程：冷启动解锁、Vault 列表滚动、条目详情/编辑、TOTP 列表刷新；
- 生成 `baseline-prof.txt` + `startup-prof.txt`，随 release 构建打包（与既有 R8 全量混淆协同）。

**风险**：生成器需真机/模拟器跑 UI Automator 旅程；profile 体积需 < 1.5MB。

**验收**：Macrobenchmark StartupBenchmark 前后对比（启动耗时下降可量化）；release 包 profile 资产存在。

---

## 批次 F（Wave 19）：compileSdk 37 → Material 3 Expressive；minSdk 决策固化

**官方依据**：`developer.android.com/develop/ui/compose/designsystems/material3`——Material 3 Expressive 是 "the next evolution of Material Design ... complements the Android 16 visual style and system UI"；需 Compose BOM 2026.08.00+（Compose 1.12.x，要求 compileSdk 37）。

**范围**：
- `compileSdk`/`targetSdk` 36 → 37（配套核对 AGP/Kotlin 兼容矩阵）；
- Compose BOM 2026.06.01 → 2026.08.00+，评估 M3 Expressive 主题（色彩/形状/动效）与现有 SAPPHIRE 等自定义调色板的融合策略；
- **minSdk=36 决策固化**：在 README/AGENTS.md 明确「仅 Android 16+」基线的理由（Credential Provider 需 API 36）与代价（放弃 16 以下存量设备；`<36` 场景需退化为传统 Autofill）；若产品决策下探至 34/35，需补功能降级分支——本批次仅固化文档，不做代码变更。

**风险**：targetSdk 37 平台行为变更清单核查；M3 Expressive 部分组件仍标注 experimental（`ExperimentalMaterial3Api`）。

**验收**：全量测试绿 + release 构建通过；新 BOM 下 UI 走查（15 屏）无回归。

---

## 持续项（不设 Wave，纳入例行维护）

1. **Credential Manager / Passkey UX 最佳实践**（官方 2026 指南）：设置页展示每个 Passkey 元数据（rp.id/创建时间/绑定）；在账户创建、登录后、恢复、密码重置等关键时刻引导创建 Passkey；关注 Digital Credential API 与 Verified Email via Credential Manager（2026-04）新能力。
2. **依赖版本周期核对**：每季度按 AndroidX 稳定渠道核对一次；安全关键组件（credentials/biometric/crypto）坚持稳定渠道优先。
3. **网络安全基线复审**：Network Security Config 与 TLS-only 双层防线保持零豁免；如未来引入任何新网络客户端（如 WebView），复核其受平台策略覆盖。
4. **文档如实化纪律**：每批次完成后刷新 `AGENTS.md` 当前状态，本文档滚动更新状态列。
