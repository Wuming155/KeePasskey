# KeePasskey 项目状态单一真相源（Single Source of Truth）

> **更新时间**：2026-09-08（TASK-44 自动填充黑名单完整生命周期）  
> **权威声明**：本项目**唯一**有效的状态与任务跟踪页。`README.md` 仅作对外简介。原 `DELIVERY_PLAN.md` / `REMEDIATION_PLAN.md` / `docs/*审查报告*.md` 等历史存档文档已于 2026-09-07 **物理删除**，其结论已并入本文件与 `FINDINGS_TRACKER.md`，不再单独保留。  
> **2026-09-08 文档梳理**：看板去重（删除 TASK-08/15/16/17/18 的 5 条失效「❌ 未实现」副本），44 项按 ID 升序重排；基线 HEAD、测试口径与 §4 日志索引按实际提交校正。
> **2026-09-08 TASK-44**：自动填充黑名单完整生命周期闭环，看板 43 ✅ / 2 📋 / 1 ❌（仅余 TASK-43）。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | `c530761` (main) | TASK-45/20 提交（S3 SigV4 时钟偏移补偿 `d4bc46c` + Dependabot/OWASP 依赖巡检 `c530761`）；**已推送 `origin/main`（本地与远端 0 ahead / 0 behind）** |
| **测试基线** | **486 个单元测试用例**（app 126 / core 32 / crypto 52 / database 155 / sync 121）：**474 通过、0 失败、12 跳过** | `./gradlew test` 全模块执行；跳过的 12 例为 `LiveSyncServersTest` 真实联调用例（需先起 `tools/local-sync` 服务并加 `-DliveSyncTest`） |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | **AGP 9.2.1 / Gradle 9.4.1** / Kotlin 2.4.10（经 buildscript classpath 锚定内置 KGP）/ Hilt 2.60.1 / **KSP 2.3.11** |
| **系统基线** | **minSdk 36**, **compileSdk 37**, targetSdk 36 | 仅针对 Android 16+ 深度优化，固化无旧版垫片决策；compileSdk 37 随批次 H 升级（Compose BOM 2026.08.00 + M3 Expressive） |
| **传输安全防线** | 全站强制 HTTPS（`network_security_config.xml` 禁明文 + OkHttp TLS-only），零证书固定 | 对齐 Google Developer Knowledge `pinning not recommended` 指南 |
| **PSL 与域名匹配** | 完整接入 Mozilla PSL（`public_suffix_list.dat`），IDN punycode 归一 | 消除 47 条硬编码漏判盲区，fail-closed |

---

## 2. 任务唯一看板（46 项：43 ✅ 完成 / 2 📋 待验证·评估 / 1 ❌ 未实现）

所有进行中、已立项、待执行体检批次、未实现功能、安全遗留与欠账统一收录于下表，**按 TASK ID 升序排列**（优先级见各行「优先级」列）。**新增任务必须在此表注册，新 ID 顺延。**

> **与另两份文档的边界（消除多头管理）**：本表是**任务完成态的唯一看板**。`FINDINGS_TRACKER.md` 为 2026-09-07 审计时点的**代码证据快照**（其「物理状态」列不随修复实时更新，仅供追溯）；审计类任务（TASK-09~42）修复落地后**以本表状态为准**并回写 FINDINGS 的代码证据。`HEALTH_CHECK_ROADMAP.md` 仅作体检批次的**执行方案**（范围 / 依据 / 风险 / 验收），状态不在此重复维护。

| ID | 领域 | 任务名称 | 来源 | 优先级 | 状态 | 说明 / 证据 |
|:---:|:---:|---|---|:---:|:---:|---|
| **TASK-01** | 安全 | **批次 C：HMAC 防篡改回归锁 flaky 排查** | 体检路线图 C | **P0** | ✅ 已修复（2026-09-07） | **根因定位并修复**：`testCorruptHmacBlock` 偶发未抛异常系真实安全缺陷——解析期间 GZip 预读拉取到 HMAC 终止块时，`javax.crypto.CipherInputStream` 将底层 `IOException`（凭据异常）吞掉伪装为 EOF，而 `HmacBlockInputStream` 在校验**通过前**即置 `terminated=true`，`verifyEndOfStream` 误判放行（实测 ~10% 概率篡改文件静默解锁）。整改：`terminated` 仅在终止块 HMAC 校验通过后置位，失败先记录 `terminalValidationFailed` 再抛出，`verifyEndOfStream` 作为权威检查点重放失败（fail-closed）。验收：`testCorruptHmacBlock` 连跑 **20 次零失败**（修复前 40 次内 6 次复现）；`KdbxFile.kt` `!!` 已清理 |
| **TASK-02** | 平台 | **凭据能力注册实机回归** | Wave 16 遗留 | **P1** | 📋 待验证（代码层已核实） | 2026-09-07 代码层核实：`AndroidManifest.xml` 凭据服务 `meta-data` 名为 `android.credentials.provider`（契约名正确），`@xml/credential_provider_service` 资源存在，Autofill 兼容层 `android.autofill` 同步在位。**剩余动作需真机**：「设置 → 密码、密钥和自动填充」确认 KeePasskey 出现且能力生效 |
| **TASK-03** | 依赖 | **批次 D：kapt → KSP 迁移 + 启用 built-in Kotlin** | 体检路线图 D | **P2** | ✅ 已完成（2026-09-08） | kapt → KSP 2.3.11（AGP 9 要求 ≥2.3.6）；移除 kotlin-android / kapt 插件与 `android.builtInKotlin` / `android.newDsl` opt-out 旗标；Kotlin 2.4.10 经顶层 buildscript classpath 锚定内置 Kotlin（与 Compose 编译器插件版本严格对齐）；各模块 `kotlin.compilerOptions.jvmTarget` 移除（内置 Kotlin 默认取 compileOptions JVM 17）。KSP `kspDebugKotlin` 正常执行，全量测试绿 |
| **TASK-04** | 存储 | **批次 E：RealSettingsRepository 迁移 Preferences DataStore** | 体检路线图 E | **P2** | ✅ 已完成（2026-09-08） | datastore-preferences 1.2.1（稳定版）；21 个设置键全量迁移：事务性原子写、Flow 原生变更通知；手写一次性迁移（首次收集设置流时旧 SharedPreferences 键值整体写入 DataStore 后删旧文件，Mutex 保证恰好一次，IO 调度器承载）——弃用官方 `SharedPreferencesMigration`（1.2.1 构造器默认参数解析异常，手写更可控）。`ExtendedSettingsStore` 保持 SharedPreferences（同步 load/save API 与「null 上下文注入 JVM 单测」可测性设计强绑定，KDoc 已注明后续整体评估） |
| **TASK-05** | 构建 | **批次 F：Gradle 版本目录（`libs.versions.toml`）** | 体检路线图 F | **P2** | ✅ 已完成（2026-09-08） | 5 模块插件与依赖全部入 `gradle/libs.versions.toml` 集中管理；版本货币性核对（hilt 2.60.1 / credentials 1.6.0 / okhttp 4.12.0 / coroutines 1.10.2 / zxing 4.3.0）；后续批次（datastore / profileinstaller / BOM 2026.08.00 / material3 / AGP 9.2.1 / core-ktx 1.19.0 / KSP）版本均经目录注入 |
| **TASK-06** | 性能 | **批次 G：Baseline Profiles + Startup Profiles** | 体检路线图 G | **P3** | ✅ 已完成（2026-09-08） | 引入 `androidx.profileinstaller:1.4.1`（首启异步触发 ART 配置安装）；新增手动 `app/src/main/baseline-prof.txt`：冷启动 + 解锁首屏路径优先（应用壳/导航/主题、Hilt DI、Unlock/安全封存、设置仓库/自锁、database/crypto 解锁链路），S 标志规则同时驱动 DEX 布局优化；release APK 实测产出 `assets/dexopt/baseline.prof(+m)`。Macrobenchmark 实测生成（需真机跑 `generateBaselineProfile`）为后续可选精化 |
| **TASK-07** | UI/SDK | **批次 H：compileSdk 37 → Material 3 Expressive** | 体检路线图 H | **P3** | ✅ 已完成（2026-09-08） | 5 模块 compileSdk 36→37（android-37.0 平台）；AGP 9.1.0→9.2.1（SDK 37 正式支持）+ Gradle Wrapper 9.3.1→9.4.1；core-ktx 1.17.0→1.19.0；Compose BOM 2026.06.01→2026.08.00（material3 1.4.0 底座）；M3 Expressive：material3 显式采用 1.5.0-alpha27（ExpressiveTheme/MotionScheme 公开 API 1.5.0 才毕业，1.4.0 中 internal），`KeePasskeyTheme` 切 `MaterialExpressiveTheme` + `MotionScheme.expressive()`；ExposedDropdownMenu 1.5.0 移除→迁移 `DropdownMenu + exposedDropdownSize()`。minSdk 36 决策固化不变 |
| **TASK-08** | 同步 | **周期性后台同步（WorkManager）** | 功能缺口 | **P2** | ✅ 已完成（2026-09-08） | 新增 `PeriodicSyncWorker`（CoroutineWorker + Hilt EntryPoint 获取 `SyncCoordinator`，与前台同步共享 mutex 天然互斥；冲突留待用户决策、错误不重试避免退避风暴）与 `PeriodicSyncScheduler`（唯一周期任务 UPDATE 语义，间隔强制 ≥15 分钟，`wifiOnlySync` 映射 UNMETERED/CONNECTED 网络约束）。冷启动 `MainApplication` 按持久化偏好恢复调度；设置页开关/间隔/Wi-Fi 三项变更即时生效。依赖 `androidx.work:work-runtime-ktx:2.10.0` |
| **TASK-09** | 安全 | **P0-2 测试代码真实凭据清洗** | 审核报告 P0-2 | **P1** | ✅ 已完成（2026-09-07） | **核实完成**：`Argon2InteropDiagnosticTest.kt` 已全部换用合成口令 `TestMasterPassword!2026#Secure` 与自造十六进制密钥/盐（期望值由独立参考实现离线预计算，互操作校验语义不变）；`KdbxKeyFileTest.kt` 为 32B 合成测试字节（0x01..0x20）。仓库级扫描（测试源码密码赋值模式 + `.kdbx`/真实库引用模式）零命中，**仓库内零真实凭据**。FINDINGS P0-2 已在历史提交中标记修复，本次为看板状态同步 |
| **TASK-10** | 内存 | **TOTP 种子与受保护自定义字段编辑态 CharArray 化** | 加解密审查 B9 | **P2** | ✅ 已修复（2026-09-07） | **同 M1 密码模式全面 CharArray 化**：`EntryEditUiState` 移除 `totpSecret: String`，TOTP 种子经 `EntryEditViewModel` CharArray 私有链路 + 一次性预填通道（`loadedTotpSecret`）承载，UI 走 `SecurePasswordField` 桥接；受保护自定义字段明文经 `protectedFieldChars` 私有映射 + `loadedProtectedFields` 预填通道承载（UI 投影恒空串，对齐详情页掩码投影语义），保护标记切换时明文自动迁移存储。仓库契约同步收紧：`saveEntry` 改 `totpSecretChars: CharArray?` + `protectedFieldChars: Map<String, CharArray>`（擦除契约扩展）、`getEntryTotpSecret`/`getEntryProtectedField` 改 CharArray 独占副本读取；详情页展示/复制路径同步改造。`onCleared` 擦除全部驻留。428 例全绿 |
| **TASK-11** | 安全 | **Autofill Dataset 已解锁分支增加二次确认/认证** | 审核报告 P2-24 | **P2** | ✅ 已修复（2026-09-07） | `KeePasskeyAutofillService` 已解锁分支每个数据集下发前挂 `setAuthentication`，认证 PendingIntent 指向新增 `AutofillConfirmActivity`（每数据集独立 requestCode 防 PendingIntent 覆盖）：优先系统级生物识别/锁屏凭据（`UNLOCK_AUTHENTICATORS` 集合），无硬件时退化为受保护窗口内手动确认（确认/取消）。Activity 具备 FLAG_SECURE + `setHideOverlayWindows(true)` 反截屏/反 overlay 加固；仅 RESULT_OK 后框架才将数据集值写入目标表单 |
| **TASK-12** | 架构 | **设置项 33 个字段持久化（全部开关保留为预留功能）** | 审核报告 P1-6 | **P2** | ✅ 已完成（2026-09-08） | **裁定：全部开关保留不下架**（均为预留功能，消费方接线登记为 TASK-43）。整改核心为消除「纯内存回显」：`ExtendedSettings` 提升为公共模型并新增 `ExtendedSettingsStore`（SharedPreferences 持久化，整体读/整体写，null 上下文时退化为内存语义保可测性），`SettingsViewModel` 全部 setter 经 `updateExtended` 统一「更新+落盘」，冷启动不再静默回落默认值；`wifiOnlySync` 以独立键持久化。Store 无持久化层回退语义补单测 |
| **TASK-13** | UI/SAF | **设置页 5 个动作 SAF 真实化** | 审核报告 P1-7 | **P2** | ✅ 已完成（2026-09-08） | 导出三件套真实化：KDBX（`DatabaseSession.exportToBytes` 内存库全量序列化）、XML（新增 `KeePassXmlExporter` 输出 KeePass 2.x 兼容明文格式，可被 KeePass/KeePassXC 导入，明文安全声明见导出警告文案）、密钥文件（会话 `keyFileCache` 原件字节）——三者均经 `CreateDocument` SAF 另存为落盘，失败如实上浮。模板安装真实化：`installEntryTemplates` 幂等创建「模板」分组与 5 个标准模板条目并落库。子库挂载：从谎报「挂载成功」改为如实提示「尚未实现」（真实功能缺口，登记 TASK-43） |
| **TASK-14** | 安全 | **`SyncCredentialsStore` 删生产测试钩子** | 审核报告 P2-21 | **P2** | ✅ 已修复（2026-09-07） | `customEncryptor`/`customDecryptor` 加 `@VisibleForTesting` 注解并收窄为 `internal`——生产 DI 与外部调用方不可见、不可写，仅本模块单元测试（同一编译单元）可注入模拟加解密闭包 |
| **TASK-15** | 特性 | **自定义图标上传 / 选择 UI** | 功能缺口 | **P3** | ✅ 已完成（2026-09-08） | 新增 `CustomIconCoordinator`（PNG 魔数校验 fail-closed / 单图 256KB 上限 / 内容去重 / KDBX Meta CustomIcons 落库）；`VaultRepository` 新增 `addCustomIcon` / `getCustomIconBytes`；`UiVaultEntry.customIconId` 投影与保存路径双向写回；`IconPickerDialog` 扩展自定义图标区（位图网格 + 相册上传，既有分组调用点零变更）；编辑页 Photo Picker 选图 → 降采样 ≤128px PNG（离主线程）→ 上传即选中，标准/自定义图标互斥。**余项**：列表行/详情页位图渲染与图标删除未接线（图标数据通道已就绪） |
| **TASK-16** | 特性 | **条目克隆（duplicate）** | 功能缺口 | **P3** | ✅ 已完成（2026-09-08） | 新增 `EntryDuplicateCoordinator`（独立协调器，沿 RecycleBinCoordinator 模式）：全字段保真复制（标准/自定义/受保护字段、TOTP、附件引用、tags、AutoType、customData）+ 新 `KdbxUuid`（否则会话层视为更新覆盖）+ 清空历史修订 + 时间属性重置；只读会话如实拒绝（会话 saveEntry 只读静默 no-op，故仓库层显式前置校验）；详情页顶栏克隆入口（只读隐藏），成功后就地切换至克隆体；`RealVaultRepositoryTest` 真实文件持久化往返用例 |
| **TASK-17** | 协议 | **KeePass 字段引用（`{REF:...}`）引擎** | 功能缺口 | **P3** | ✅ 已完成（2026-09-08） | database 模块新增 `FieldReferenceEngine`：`{REF:<Want>@<SearchIn>:<Text>}` 官方语法子集（T/U/P/A/N/I，大小写不敏感），整库检索首个命中条目取值替换，引用链递归展开（深度上限 10 防循环），未命中保守保持原文；取值消费点接入自动填充下发与详情页复制（密码/用户名）——投影层不展开，引用指向的密码明文不提前物化进 UI 状态流（M1 语义不变）；8 例引擎单测。**余项**：Notes/URL 展示侧解析未接（同消费点策略可平移） |
| **TASK-18** | 特性 | **Passkey 作为数据库解锁方式** | 功能缺口 | **P3** | ✅ 已完成（2026-09-08） | 快速解锁升级为「设备绑定解锁通行密钥」：主密码 AES-256-GCM 封印（生物识别/锁屏凭据门控）之上叠加 WebAuthn 形态本地断言——登记生成硬件不可导出 ES256（P-256，StrongBox 优先）密钥对，公钥 + 随机 credentialId 落私有存储；解锁时硬件私钥签名 AuthenticatorData（rpIdHash + UP + signCount），公钥验证 + rpIdHash 归属 + signCount 严格单调（反克隆），未通过 fail-closed 拒绝并清除登记；旧凭据兼容通道（首解跳过断言并后台补登记）。新增 `UnlockPasskeyManager`（验证纯逻辑 JVM 可测，6 例单测） |
| **TASK-19** | 依赖 | **zxing → CameraX + ML Kit 迁移评估** | 依赖治理 | **P3** | 📋 评估 | `zxing-android-embedded:4.3.0` 保持稳定，评估迁移至现代 CameraX + ML Kit |
| **TASK-20** | CI | **GitHub Dependabot / OWASP 依赖漏洞巡检** | 供应链 | **P3** | ✅ 已完成（2026-09-08） | `.github/dependabot.yml`：`gradle`（目录 `/` 覆盖 5 模块 build 文件与 `libs.versions.toml` 版本目录）+ `github-actions` 两生态每周分组 PR（gradle minor/patch 合并、major 独立评审）。`.github/workflows/dependency-scan.yml`：每日 18:00 UTC 定时 + 手动 + 依赖清单变更（push/PR paths）触发；OWASP `dependency-check-gradle:13.0.0` 经 `.github/dependency-check.init.gradle.kts` 仅 CI 注入（**不进常驻构建**，init 脚本与 `dependencyCheckAggregate` 任务注入已本地 `gradlew help/tasks` 验证），汇总 5 模块依赖解析图；`failBuildOnCVSS=11` **首次仅告警**，HTML/SARIF/JSON 报告 artifact 归档 + SARIF 上传 Code Scanning，`NVD_API_KEY` Secret 可选提速。误报经 `.github/owasp-dependency-suppressions.xml` 白名单登记（核实依据/日期/责任人纪律见文件头注释） |
| **TASK-21** | 整洁度 | **超 800 行文件拆分与硬编码中文抽取** | 审核报告 P3-22/23 | **P3** | ✅ 已完成（2026-09-08） | **P3-22 全闭合**：7 个超 800 行文件全部拆分（`RealVaultRepository` 1436→771 + 新增 `VaultEntryMapper`/`RecycleBinCoordinator`/`PasskeyEntryCoordinator`/`VaultTemplateFactory`；`SettingsViewModel` 1193→700 + 新增 `SettingsSyncController`/`SettingsHealthController`/`SettingsExportController`；5 个大屏 Compose 文件拆出区块/组件/对话框文件，公共 API 与行为零变更）。**P3-23 渐进闭合**：用户可见文案全量资源化（新增 `strings_ui_messages.xml` 39 键、`strings_sync_passkey.xml` 30 键、主 strings.xml `repo_*`/`health_*`/`time_*` 等 44 键；非 Compose 层新增 `StringsProvider` 通道 + Hilt 绑定，生产转发 getString、单测注入假实现）。中文字面量 294→114，剩余均为合理保留：debugLog/Log 日志、开发者面向异常消息、KDBX 持久化数据（回收站/模板/卡字段键/占位库名）、`core` 纯 JVM 模块异常兜底「未知错误」（无 Android 资源层）。**446 例测试全绿** |
| **TASK-22** | 安全/稳定 | **P3-30：`@Singleton` AutoLockManager 在 `MainActivity.onDestroy` 被 destroy** | FINDINGS 核实 | **P0(真实 Bug)** | ✅ 已修复（2026-09-07） | 移除 `MainActivity.onDestroy` 中的 `autoLockManager.destroy()` 调用（含空覆写与随之成为死代码的 `AutoLockManager.destroy()`）。`AutoLockManager` 为进程级单例（监听 `ProcessLifecycleOwner` + 熄屏广播），生命周期与进程对齐，`initialize()` 幂等，资源随进程退出由系统回收；旋转/配置重建不再销毁自动锁定调度器 |
| **TASK-23** | 安全 | **P2-9：`parseEcPrivateKey` 缺 `d ∈ [1, n-1]` 范围校验** | FINDINGS 核实 | **P1** | ✅ 已修复（2026-09-07） | `PasskeyCryptoEngine.kt` 新增显式标量范围校验 `validateEcScalarRange`（权威检查点，不依赖库层行为），越界 fail-closed 抛类型化 `CryptoException.InvalidKeyException`；库层构造器 IAE 经 `newEcPrivateKey` 归一为同一异常类型且不作回退放行。补 5 例单测：d=0 / d=n / d>n（32B 标量与 64B hex 文本两形态）均拒绝，边界 d=1 / d=n-1 签名可用 |
| **TASK-24** | 内存 | **P2-10：旧派生回退 `legacyCipherKey` 未清零** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-07） | `KdbxFile.resolveCipherKey` 重构为返回 `CipherKeyResolution`（activeKey + legacyKeyToWipe）：未选中的 `legacyCipherKey` 在任何结果路径（含裁决失败抛异常）的 `finally` 中统一清零；被选中的旧派生密钥在解密流建立（`SecretKeySpec` 已克隆密钥材料）后立即擦除。`legacyHmacKey` 派生后即时擦除语义保持不变 |
| **TASK-25** | 互操作 | **P2-11：WebDAV Basic 认证 ISO-8859-1 致中文密码 401** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `WebDavSyncProvider.buildBasicAuthHeader` 凭据拼接改按 UTF-8 编码（CharBuffer 直转，敏感数据铁律不变）；主流 WebDAV 服务端（sabre 系按 UTF-8 解码）中文密码鉴权恢复。RFC 7617 §2.1 的 `charset` 参数仅存在于服务端挑战侧，请求侧无声明机制，故改编码不附参数。补中文用户名/密码回归测试（Authorization 头 Base64 解码逐字节断言） |
| **TASK-26** | 协议 | **P3-16：S3 SigV4 对 `*` 与 `~` 编码不符 AWS 规范** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `S3SyncProvider.encodePath` 重写为 AWS SigV4 规范 URI 编码：保留集仅 RFC 3986 unreserved（`A-Za-z0-9-_.~`），其余大写百分号编码（此前 `URLEncoder` 表单语义 `*` 不编码、`~` 强制 %7E，恰与 AWS 规范相反，含这两字符的键必 403）。补两类回归：编码已知答案（`*`→%2A、`~` 保留、空格→%20、中文 UTF-8）+ SigV4 签名已知答案向量（独立 Python 参考实现离线预计算，含 `*`/`~`/UTF-8 键，与被测实现零共享代码） |
| **TASK-27** | 协议 | **P3-11：CBOR `encodeMap` 不强制 RFC 8949 Canonical 键序** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `CborEncoder` Map 编码强制 RFC 8949 §4.2.1 Canonical 键序：按键自身 CBOR 编码字节流字典序升序写出（新增 `writeCanonicalMap`），重复键（编码字节相同，如异型 `Long(1)`/`Int(1)`）fail-fast 拒绝。COSE 公钥（EC2/Ed25519/RSA）输出经 Canonical 重排后与 CTAP2 规范形态一致，互验兼容性提升；补 5 例键序回归锁（乱序重排/整数键编码序/变长键/嵌套 Map/重复键拒绝） |
| **TASK-28** | 敏感 | **P3-12：附件缓存明文无清理** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-07） | `AttachmentManager` 重写为「用完即删」纵深防线：导出集中至专用子目录 `attachment_view`（`cleanCache` 不再全盘粗暴删除）、随机 UUID 前缀防路径猜测/劫持、每次新导出清除上一轮遗留明文（含上次进程残留）、新增 `deleteExported` 供查看方消费后即删、`deleteOnExit` JVM 退出兜底。补 6 例单测（子目录隔离/文件名净化/会话即弃/用完即删/定向清理回归锁/同名互异） |
| **TASK-29** | 安全 | **P3-13：RSA `certainty = 12` 偏低** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-07） | `PasskeyCryptoEngine.generateRs256KeyPair` 素数确定性参数 `certainty` 12 → 80（False-prime 概率 ~1/2¹² → ≤1/2⁸⁰），对齐 BouncyCastle 官方示例与主流密码库默认 |
| **TASK-30** | 功能 | **P2-17：冲突解决逐字段选择塌缩为整条目二选一** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | **字段级合并落地**：`KdbxMerger` 新增 `resolveConflictByFields`（按字段 `KEEP_REMOTE` 逐字段采纳远端值，采纳时刷新 `lastModificationTime`）；`SyncCoordinator.resolveConflicts` 扩展 `fieldResolutions` 参数走字段级路径（整条目二选一语义保持兼容）；`ConflictResolutionUiState.ConflictedField` 增加 `fieldKey`，`ConflictResolutionViewModel` 新增 `selectFieldChoice(entryId, fieldKey, choice)` 逐字段选择，`applyMerge` 按字段生成 resolutions + fieldResolutions 双通道下发；`ConflictResolutionScreen` 字段行接线逐字段单选。补字段级合并 ViewModel 回归测试 |
| **TASK-31** | 功能 | **P2-19：历史快照为空时谎报「已回滚」** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `EntryDetailViewModel.rollbackToRevision` 快照缺失（已被修剪/清理）时不再落入通用成功提示，改发新增 `detail_history_rollback_failed`（中英双语）如实暴露失败 |
| **TASK-32** | 功能 | **P2-27：条目密码强度恒硬编码 112 bit** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `UiVaultEntry.strengthBits` 改可空 `Int?` 且投影层恒为 null（不解密密码）；真实熵由 `EntryDetailViewModel` 在用户显式查看密码时按需估算，经 `EntryDetailUiState.passwordStrengthBits` 下发；`PasswordStrengthBar` 接受可空熵位（null=隐藏强度条，不回显误导默认值），`EntryDetailScreen` 改用 uiState 熵位。Mock 层注释同步标注「未计算」语义 |
| **TASK-33** | 功能 | **P2-34：TOTP 缺失时 fallback 假码 "000000"** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `AuthenticatorViewModel` 移除 `?: "000000"` 假码回退：快照缺失（种子缺失/解析失败/计算异常）时 `codeRaw=null` + 占位符 `------`；`TotpCardItem.codeRaw` 改可空，`AuthenticatorScreen` 复制通道整体禁用（行点击 + 复制按钮 `clickable(enabled=...)`，配色降级）。测试同步更新（种子完好条目非空且全数字断言保留） |
| **TASK-34** | 功能 | **P3-25：收藏功能不落库（仅翻转内存 Flow）** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `VaultRepository` 新增 `setEntryFavorite(entryId, favorite)` 契约；`RealVaultRepository` 实现持久化至 KDBX 条目 `customData`（键 `KeePasskey.Favorite`）并刷新 `lastModificationTime` 后落盘；`EntryDetailViewModel.toggleFavorite` 改调仓库保存，失败如实上浮错误提示；收藏状态随条目投影（`UiVaultEntry.isFavorite`）下发，重启不再丢失 |
| **TASK-35** | 功能 | **P3-26：`EntryCategory` 与银行卡字段未映射** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `RealVaultRepository.mapKdbxEntryToUi` 识别银行卡条目（模板「信用卡」以自定义字段存放卡号/持卡人/有效期/CVV）：非受保护卡字段映射至 `UiVaultEntry.cardNumberMasked/cardHolder/cardExpiry/cardCvv` + `category=CARD`；受保护卡号/CVV 保持 null 由 UI 整卡掩码兜底（F2 不物化明文语义不变） |
| **TASK-36** | 功能 | **P3-27：自动填充黑名单空 onClick + 写死列表** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | **诚实化整改**（沿 TASK-12/13 裁定先例）：`AutofillSettingsScreen` 黑名单对话框移除两条写死示例条目（银行/门户）与空 onClick 删除按钮，改为如实展示真实计数（`disabledAutofillQueriesCount`）或空态文案；对应孤儿字符串资源（中英 4 条）清理。黑名单完整生命周期（服务侧写入/条目展示/删除/填充拦截）为真实功能缺口，登记 TASK-44 |
| **TASK-37** | 数据 | **P2-15：`SyncCache.updateBase` 两文件非原子写** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | 重构为「两份内容先各自写唯一 tmp 并 fsync（慢路径）→ 背靠背两次原子 rename（快路径，微秒级）」，把 `.baseversion` 与 `.meta` 的不一致窗口从两次完整写盘压缩至两个原子 rename 之间；失败路径 `finally` 清理未交付 tmp。跨多文件的完全原子性受 POSIX 限制不存在，此为工程最小窗口（KDoc 已注明）。新增 `SyncCacheTest` 5 例（TASK-40 T-03 缺口一并补强：读写往返/updateBase 一致性/etag 保留/无 tmp 残留） |
| **TASK-38** | 构建 | **P2-32：Release 未开 `shrinkResources`；ProGuard 过度宽松** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `app/build.gradle.kts` release 启用 `isShrinkResources = true`；`proguard-rules.pro` 移除对自带 consumer rules 库的整包过度 keep（kotlin-stdlib / kotlinx-coroutines / Compose / lifecycle / navigation / biometric），混淆与无用代码剥离交由官方规则接管（Hilt/BouncyCastle/自有包/OkHttp 规则保留）。顺手修复阻塞 `assembleRelease` 的既有 lintVital 致命错误：values-en 存在 15 条对应已删功能（自研 PIN QuickUnlock / 自签名证书 / 明文同步）的孤儿翻译（ExtraTranslation），已清理并补默认 locale `unlock_error_pin_length` |
| **TASK-39** | 性能 | **P2-29：SAF 密钥文件读取在主线程完成** | FINDINGS 核实 | **P2** | ✅ 已修复（2026-09-08） | `UnlockScreen` 密钥文件 SAF 回调整体重构：1 MiB 流式读取与 `DISPLAY_NAME` 游标查询经 `rememberCoroutineScope.launch + withContext(Dispatchers.IO)` 移出主线程，结果折叠（成功/失败/空文件）回主线程统一分发 ViewModel；读取失败反馈语义不变 |
| **TASK-40** | 测试 | **测试覆盖补强（T-03 / T-04 / P2-35 / P2-37）** | FINDINGS 核实 | **P2** | ✅ 已完成（2026-09-08） | 四缺口全闭合：T-03 `SyncCacheTest` 5 例随 TASK-37 补齐；T-04 SigV4 编码与签名已知答案向量随 TASK-26 补齐（独立 Python 参考实现离线预计算，零共享代码）；P2-35 `SecurityTest` 诚实化——KDoc/用例名澄清 JVM 测试为 JDK 软件密钥算法语义验证（非 AndroidKeyStore 硬件路径，不虚标），并补 GCM 密文篡改 fail-closed 回归锁；P2-37/T-05 `SyncCredentialsStoreTest` 新增真实 AES-GCM 算法路径用例（生产同款 TRANSFORMATION 软件密钥替代 XOR 假加密：封印往返/IV 一次性/密文篡改解封 fail-closed 双协议），AndroidKeyStore 硬件隔离属 Instrumented 范畴（KDoc 注明） |
| **TASK-41** | 整洁度 | **低危清理批次（P3-5/7/9/10/14/15/17/24/28/31/32/33/34）** | FINDINGS 核实 | **P3** | ✅ 已完成（2026-09-08） | 13 项全闭合（口径见 FINDINGS 代码证据）：P3-5 去重 O(n²)→HashMap 指纹索引（整库共享防跨条目去重失效）；P3-7 删 8 处已核实未用 import，**UI 层余 ~80 条留待 IDE inspection**（Compose 委托导入误报风险，批量脚本被拒）；P3-9 `setDatabaseForTesting` 加 `@VisibleForTesting`（database 新增 `androidx.annotation:annotation:1.9.1`，不用 internal 因 app 单测跨模块调用）；P3-10 `ProtectedString.EMPTY` clear no-op；P3-14 `KdbxFile` 两处 `ByteArray(64)` 抽语义常量（只抽关键处，余项控风险不批量改）；P3-15 `encodePath` 剔除 `.`/`..`；P3-17 PROPFIND 解析失败记 `Log.w`；P3-24 设置演示默认值→空串；P3-28 空 if 块移除；P3-31 两处吞异常→`debugLog.warn`；P3-32/34 按 FINDINGS 原裁定**不修**；P3-33 `MockData.kt`→`UiModels.kt`（git mv）。443 例全绿 |
| **TASK-42** | 性能 | **低-中调度批次（P2-2 / P2-12 / P2-30 / P2-31）** | FINDINGS 核实 | **P3** | ✅ 已修复（2026-09-08） | 四项全闭合：P2-2 `DatabaseSession.save` 序列化（Argon2 派生+流加密，CPU 密集）移至 `Dispatchers.Default`，仅字节落盘（writeAtomic+fsync）走 IO（对齐 exportToBytes 先例，写毕擦除序列化缓冲）；P2-12 `SyncHttpClientFactory` 补全局 `callTimeout`（默认 5 分钟，覆盖 DNS+连接+读写全生命周期，弱网悬挂兜底封顶，`SyncNetworkOptions` 新增 `callTimeoutMs`）；P2-30 `AuthenticatorViewModel` uiState 上游显式 `flowOn(Dispatchers.Default)`（combine 内含种子解析+HMAC，兜底脱离主线程，测试同步改为真实时间轮询等待）；P2-31 `RealVaultRepository` 构造期不再同步扫盘——`listFiles` 移至协程 + `Dispatchers.IO`，databasesFlow 经 Flow 自然推送更新 |
| **TASK-43** | 特性 | **进阶偏好消费方接线（预留功能清单）** | TASK-12 裁定衍生 | **P3** | ❌ 未实现 | 设置页全部进阶开关保留为预留功能（已随 TASK-12 持久化，不再回显丢失）。待接线消费方：`webdavChunkedUpload`/`webdavChunkSizeMb`（WebDAV 分块上传）、`createBackupBeforeSave`（保存前 .bak）、`checkRemoteChangesBeforeSave`、`conflictResolution` 默认策略、`useFileTransactions`（已是既定行为，接线为信息展示）、`preloadDatabaseEnabled`、`lockWhenNavigateBack`、`clearPasswordOnLeave`、`rememberRecentFiles`、`rememberKeyFileLocation`、`showKillAppOption`、`offerSaveCredentials`、`inlineSuggestionsEnabled`、`autoReturnFromQuery`、`autofillCopyTotp`、`autofillShowTotpNotification`、`skipDalVerification`、`overrideNoAutofill`（黑名单 `disabledAutofillQueriesCount` 计数已随 TASK-44 下架，改由 `AutofillBlocklistStore` 承载真实条目）、`maskPasswordsDefault`、`maskTotpDefault`、`showUnlockedNotification`、`showGroupInSearchResult`、`showGroupInEntry`、`listDensity`、`autoActivateSearchOnOpen`、`iconSet`、TOTP 字段映射、`debugLogEnabled`、`verboseSyncLog`、子库挂载（TASK-13 改诚实提示）。另：导出/导入五源（1PUX/Bitwarden/KeePass/浏览器 CSV）解析器亦为独立功能缺口 |
| **TASK-44** | 特性 | **自动填充黑名单完整生命周期** | TASK-36 整改衍生 | **P3** | ✅ 已完成（2026-09-08） | 端到端闭环（对齐 KP2A「禁用自动填充查询」）：① 新增 `AutofillBlocklistStore`（SharedPreferences 持久化包名集合 + `blockedPackages` StateFlow + Android 官方包名规则校验，非法/重复由返回值如实告知，null Context 退化为内存语义保可测性）；② 双通道消费——`KeePasskeyAutofillService` 与 `KeePasskeyCredentialProviderService` 命中即 fail-closed 返回空响应（不产出解锁引导/数据集/SaveInfo/凭据候选；Android 16+ 上 CM 为主通道，仅屏蔽传统 Autofill 等于形同虚设）；③ 入口——详情页 `android://<包名>` 绑定条目顶栏「为本应用禁用自动填充」写入/移除黑名单并如实提示（未绑定应用的条目不呈现该入口，避免无意义开关）；④ 设置页改为条目化列表（应用名+包名，解析失败回落包名）与删除、按包名新增且失败如实报错。无写入方的 `disabledAutofillQueriesCount` 计数及其持久化键、`ExtendedSettings`/`SettingsUiState` 对应字段一并下架，零假开关。回归 11 例（仓库 8 + 详情页 3），486 例全绿 |
| **TASK-45** | 协议 | **S3 SigV4 服务端时钟偏移补偿** | FINDINGS P2-14 | **P3** | ✅ 已完成（2026-09-08） | `S3SyncProvider` 新增时钟偏移补偿链路：**每响必刷新**——任何响应（含 4xx/5xx）携带有效 `Date` 头即经 `refreshClockOffset` 更新运行时偏移（变化 ≥1s 才回调持久化，抑制 HTTP Date 秒级抖动刷盘）；**签名补偿**——`signingDate()` = 本地时间 + 偏移，5 个签名请求全走补偿时间（TASK-26 已知答案向量不回退，`signV4` 纯函数语义保持）；**skew 自愈**——`executeSignedRequest` 统一执行器对「403 且偏移跳变 >14min（HEAD 无错误实体场景同样适用）或错误主体含 `RequestTimeTooSkewed`」**恰好一次**重签重试，首次同步偏移未知也能自愈；**fail-closed**——无有效 `Date` 头不补偿、不盲目重试、按原路径如实上浮。偏移经 `SyncCredentialsStore.loadS3ClockOffsetMillis/saveS3ClockOffsetMillis` 持久化跨进程恢复（非敏感常量，与凭据同文件；`saveS3Config` 重录配置即作废旧偏移重新学习），`SyncCoordinator.resolveProvider` 注入初始偏移 + 刷新回调（持久化失败仅丢跨进程记忆，不阻断同步）。回归 4 例：正/负偏移补偿签名断言、首次同步 skew 自愈（恰 2 请求 + 回调偏移 ≈ 服务端偏差）、无 Date 头 fail-closed（单请求不重试） |
| **TASK-46** | 内存 | **`OtpEngine` TOTP 计算链路 ByteArray 化** | FINDINGS P2-5 | **P2** | ✅ 已完成（2026-09-08） | **计算侧残余闭合**：`OtpEngine.calculateTotp`/`calculateHotp` 入参由 `String` 改 `ByteArray`（`calculateHotpRaw` 合并移除），种子经 Base32 解码后全程字节态（HMAC-over-counter 链路零 String 密钥中间值）；`Base32Decoder.decode` 固化借用语义（调用方独占新数组、无内部缓存，KDoc 注明用毕 `fill(0)`）；`VaultEntryMapper.computeTotpCode` 解码产物成功/失败路径 `finally fill(0)` 擦除（fail-clean，不因早退残留种子副本）。回归：RFC 4226 Appendix D 全量 10 组、RFC 6238 Appendix B SHA-1/256/512 各 6 组、RFC 4648 §10 官方向量 + 「引擎不篡改调用方种子」「擦除后重解码重算一致（无缓存驻留）」断言全绿；新增 `VaultEntryMapperTotpTest` 3 例（有效出码 / 无效种子 fail-clean / SHA-256·512 消费）。**470 例全绿**（458 通过 / 12 跳过） |

---

## 3. 历史发现项全量审计汇总（125 项）

针对 2026-09-05 及 2026-09-06 四份审查报告共 **125 项**发现（全量代码审核 93 + 安全审查 16 + 加解密审查 9 + 测试覆盖缺口 7），对照当前 `main` 分支代码完成逐条物理核对。详细核对卷宗见 [**FINDINGS_TRACKER.md**](FINDINGS_TRACKER.md)。

| 报告来源 | 发现总数 | ✅ 已修复 | ⚠️ 部分修复 | ❌ 未修复 | ➖ 不适用 / 记录备查 |
|---|:---:|:---:|:---:|:---:|:---:|
| **全量代码审核报告（2026-09-05）** | 93 | 71 | 12 | 3 | 7 |
| **安全审查报告（2026-09-06 Wave 13）** | 16 | 16 | 0 | 0 | 0 |
| **加解密实现审查报告（2026-09-06）** | 9 | 9 | 0 | 0 | 0 |
| **审核报告第七节测试覆盖缺口** | 7 | 5 | 2 | 0 | 0 |
| **合计** | **125** | **101 (81%)** | **14 (11%)** | **3 (2%)** | **7 (6%)** |

> **计数校正（2026-09-08）**：原记「131 项」为列向加总错误，按四份报告行枚举实为 **125 项**（93 + 16 + 9 + 7）；上表数字已按 `FINDINGS_TRACKER.md` 当前物理状态重新点算。
>
> **关键结论**：125 项发现中，所有 P0 级阻断项（7 项）与高危安全缺陷（自研 PIN 解锁、全站明文流量、旧派生 HMAC 校验、GCM IV 唯一性等）已 **100% 修复**。原「未修复 43 项」的四类主因——① 约 35 个设置项无消费者（P1-6）、② 5 个假动作 SAF 导出（P1-7）、③ 超 800 行文件与硬编码中文（P3-22/23）、④ 测试假用例与覆盖缺口（P2-36/37）——已随 TASK-12/13/21/40 全部闭合。**当前仅余 2 项未修复**：P2-26（占位库演示数据，低优先级可接受）、P2-28（「已泄露密码」恒 0，需接入 HIBP 外部服务）；P2-14（S3 时钟偏移补偿）与 S-16（CI 依赖巡检）已于 2026-09-08 分别随 **TASK-45** / **TASK-20** 闭合。另有 14 项为「部分修复」（均属风险已接受的残余项，详见 FINDINGS「说明」列）。

### 3.1 实测核实补充结论（2026-09-07）

> 本节为 2026-09-07 对 [**FINDINGS_TRACKER.md**](FINDINGS_TRACKER.md) 全部「❌ 未修复」项逐条物理核对的补充结论。Tracker 现已为每项补充「是否有必要修复」与「说明」两列。

**结论修正（原报告描述须更正，非 bug）**：
- **P3-19** `hasLocalChanges` 方向：实测为保守策略（.version 缺失→无法确认→`false`；.baseversion 缺失→无法比对→保守 `true`），**非 bug**，仅需补注释明确语义。
- **P3-29** `(context as? MainActivity)`：**安全转换 `as?` 而非强转**，原「强转」结论不准确，风险极低。
- **P3-8** `KdbxFile.save` 头部：**仅一次序列化 + ByteArrayOutputStream 双重缓冲**，非「序列化两遍」，无正确性风险。

**实测判定「必须修复」的未修复项**：已逐条登记为 `STATUS.md` §2 的 **TASK-22 ~ TASK-42**（含对应 P 编号、优先级与代码证据），此处不再复述，仅列严重度速览与 TASK 映射：

- **高（真实 Bug）**：P3-30 → **TASK-22**（`@Singleton` `autoLockManager` 在 `MainActivity.onDestroy` 被 `destroy()`，旋转即失效）
- **中（安全）**：P2-9、P2-10、P3-11、P3-12、P3-13、P3-16 → TASK-23~29（EC 标量越界 / `legacyCipherKey` 残留 / CBOR 非 Canonical / 附件明文缓存 / RSA `certainty=12` / SigV4 `*` `~` 编码不符）
- **中（功能 / 数据 / 互操作 / 构建 / 测试）**：P2-15、P2-17、P2-19、P2-27、P2-34、P3-25、P3-26、P3-27、P2-11、P2-32、T-03、T-04、P2-35、P2-37 → TASK-30~40（非原子写 / 字段级合并塌缩 / 空快照谎报回滚 / 强度硬编码 112 / 假码 000000 / 收藏不落库 / category 与卡字段未映射 / 黑名单空 onClick / WebDAV ISO-8859-1 / `shrinkResources` 未开 / `SyncCacheTest` 缺失 / SigV4 无已知答案 / `SecurityTest` 假密钥 / 真实 Keystore 路径无测）。其中 P2-17/P2-19/P2-27/P2-34/P3-25/P3-26/P3-27 七项已随 TASK-30~36 于 2026-09-08 闭合；T-03（`SyncCacheTest`）已随 TASK-37 补齐；T-04/P2-35/P2-37 见 TASK-40 余项
- **低-中（性能 / 调度）**：P2-2、P2-12、P2-29、P2-30、P2-31 → **TASK-42**（Argon2 走 `Dispatchers.IO` / 缺 callTimeout / SAF 主线程读密钥 / `combine` 未 flowOn / 构造期扫盘）
- **低（整洁度 / 需外部服务）**：P3-5/7/9/10/14/15/17/24/28/31/32/33/34、P2-26、P2-28 → **TASK-41**（死代码 / 魔数 / 空块 / 单例污染 / 路径遍历等清理；P2-26 占位演示数据、P2-28 泄露密码恒 0 需 HIBP 接入）

---

## 4. 历史改动日志索引（取代 Wave 编号）

旧的 Wave 编号体系因插队与顺延已失去时间语义，**即日起整体冻结，改用「Git 提交号 + 日期 + 主题」作唯一标识**：

> 索引中出现的 `Wave N` / `阶段 N` 字样为对应历史提交的**原始主题**，属已冻结语境，仅供追溯；新提交请以 `TASK-xx` / `批次 X` + 提交哈希 引用，勿再使用 Wave / 阶段 编号。

- `c530761` (2026-09-08): TASK-20 依赖巡检 CI——`.github/dependabot.yml`（gradle + github-actions 每周分组 PR）+ OWASP Dependency-Check workflow（13.0.0 init 脚本仅 CI 注入、`dependencyCheckAggregate` 汇总 5 模块、failBuildOnCVSS=11 首次仅告警、SARIF 归档）+ suppression 白名单骨架
- `d4bc46c` (2026-09-08): TASK-45 S3 SigV4 时钟偏移补偿——每响 `Date` 头刷新偏移（≥1s 节流持久化）/ 签名统一 `signingDate()` 补偿 / `executeSignedRequest` 对偏斜 403 恰一次自愈重试 / 无 Date 头 fail-closed；偏移经 `SyncCredentialsStore` 跨进程持久化、`saveS3Config` 重录作废；回归 4 例（475 例全绿）；TASK-26 已知答案向量不回退
- `5f03036` (2026-09-08): TASK-46 `OtpEngine` TOTP 计算链路 ByteArray 化 + 用毕擦除（fail-clean）——计算入参改 ByteArray、解码借用语义固化、`computeTotpCode` finally 擦除；RFC 4226/6238/4648 官方向量回归 + 擦除断言入单测（470 例全绿）；STATUS/FINDINGS/AGENTS 同步回写
- `8133576` (2026-09-08): docs README 参考项目说明更新（keepass2android 定位措辞）
- `be0a11b` (2026-09-08): docs README 删除许可证合规提示冗余说明
- `2f4d419` (2026-09-08): docs 发布准备——README 补 5 个参考项目 GitHub 地址与许可证，确立 GPL-3.0 并新增 LICENSE
- `d578df7` (2026-09-08): docs 阶段 6 批次收尾——看板吸收 TASK-03~07/15~18 九项，删除已冗余的 bug-fix-plan.md
- `7dcc092` (2026-09-08): TASK-18 设备绑定解锁通行密钥——快速解锁叠加 WebAuthn 形态本地断言（硬件 ES256 私钥 + signCount 反克隆 + fail-closed；旧凭据兼容通道）；新增 `UnlockPasskeyManager`（验证纯逻辑 JVM 可测 6 例）
- `8a7816e` (2026-09-08): TASK-17 `{REF:...}` 字段引用引擎——官方语法子集（T/U/P/A/N/I）整库检索 + 递归展开 + 循环防护；自动填充/详情复制消费点接线（投影层不展开）；8 例单测
- `1a0b759` (2026-09-08): TASK-15 自定义图标上传/选择——`CustomIconCoordinator`（PNG 校验/去重/Meta 落库）+ 图标池投影/写回 + `IconPickerDialog` 自定义区 + Photo Picker 降采样上传
- `e91c913` (2026-09-08): TASK-16 条目克隆——`EntryDuplicateCoordinator` 全字段保真 + 新 UUID + 清历史；详情页克隆入口；真实文件持久化往返用例
- `56a1d33` (2026-09-08): TASK-07 compileSdk 37——AGP 9.2.1 / Gradle 9.4.1 / core-ktx 1.19.0 / BOM 2026.08.00 / material3 1.5.0-alpha27 `MaterialExpressiveTheme + MotionScheme.expressive()`
- `1e27379` (2026-09-08): TASK-04 设置持久化迁移 Preferences DataStore——21 键全量 + 手写一次性 SharedPreferences 迁移（Mutex 恰好一次）
- `4b7c6d1` (2026-09-08): TASK-03/05 kapt→KSP 2.3.11 + AGP 9 内置 Kotlin（移除 opt-out 旗标，Kotlin 2.4.10 经 buildscript classpath 锚定）+ Gradle 版本目录 `libs.versions.toml` 落地
- `abe5cef` (2026-09-08): TASK-06 Baseline Profiles——profileinstaller 1.4.1 + 手动 `baseline-prof.txt`（冷启动/解锁首屏路径，S 标志驱动 DEX 布局）
- `7da95f5` (2026-09-08): TASK-12/13/08 阶段4 功能债——设置项全量持久化（开关保留为预留功能，登记 TASK-43）/ 5 个 SAF 动作真实化（导出 KDBX·XML·密钥文件 + 模板安装，子库挂载改诚实提示）/ WorkManager 周期性后台同步
- `4af3382` (2026-09-08): TASK-37/38/39 阶段4 数据·构建·性能债——`SyncCache` 合并原子写 / `shrinkResources` + ProGuard 收紧（顺手修 lintVital 孤儿翻译）/ SAF 密钥读取移 IO；新增 `SyncCacheTest`
- `1136235` (2026-09-08): TASK-25/26/27 阶段4 协议互操作——WebDAV Basic 认证改 UTF-8 / SigV4 AWS 规范 URI 编码 + 已知答案向量 / CBOR RFC 8949 Canonical 键序
- `f2188cd` (2026-09-08): TASK-42 调度批次——Argon2 序列化移 `Dispatchers.Default` 仅落盘走 IO / OkHttp 全局 `callTimeout` / 验证器 `flowOn(Default)` / 构造期扫盘移 IO 协程；同步补齐 FINDINGS 历史漏回写
- `5ff2e18` (2026-09-08): TASK-40 测试覆盖收口——`SecurityTest` 诚实化 + GCM 篡改回归锁 / `SyncCredentialsStoreTest` 真实 AES-GCM 算法路径用例

- `863d81c` (2026-09-08): TASK-41 低危清理批次——13 项 P3 闭合（O(n²) 去重 / 未用 import / 测试后门 / EMPTY 单例污染 / 魔数 / 路径遍历 / 静默 catch / 演示默认值 / 空 if 块 / 吞异常 / MockData 改名；P3-32/34 按原裁定不修）；bug-fix-plan 与交接文档被看板吸收后删除
- `c69779e` (2026-09-08): TASK-21 整洁度整改——P3-22 七文件拆分（`RealVaultRepository` 1436→771 拆出 `VaultEntryMapper`/`RecycleBinCoordinator`/`PasskeyEntryCoordinator`/`VaultTemplateFactory`；`SettingsViewModel` 1193→700 拆出 `SettingsSyncController`/`SettingsHealthController`/`SettingsExportController`；5 个大屏拆出组件/对话框文件，公共 API 零变更）+ P3-23 用户可见文案全量资源化（新增 `StringsProvider` 通道 + Hilt 绑定、`strings_ui_messages.xml` 39 键、`strings_sync_passkey.xml` 30 键、主 strings.xml 44 键；中文字面量 294→114，余为日志/开发异常/持久化数据）。446 例全绿
- `68f2bcb` (2026-09-08): 阶段5 功能/质量收尾——TASK-30~36 七项闭合（字段级冲突合并 / 空快照诚实报错 / 密码强度真实熵 / TOTP 假码移除 / 收藏落库 / 卡条目映射 / 黑名单诚实化）+ TASK-44 登记
- `6e4e6c7` (2026-09-07): TASK-10/11/14/24/28/29 安全与功能债第一批——TOTP 种子与受保护字段 CharArray 化 / Autofill 二次确认 / 测试钩子收窄 / `legacyCipherKey` 清零 / 附件缓存用完即删 / RSA certainty 80
- `5db6372` (2026-09-07): TASK-09/TASK-23——测试代码真实凭据清洗核实 + EC 私钥标量范围校验 fail-closed
- `1173e31` (2026-09-07): TASK-01/TASK-22——HMAC 终止块校验 fail-closed 门禁 + `AutoLockManager` 单例生命周期守卫（移除 `onDestroy` 误销毁）
- `7b3e756` (2026-09-07): WebDAV 零字节文件元数据误报修复
- `0d4fc16` (2026-09-07): 本地 HTTPS 同步联调工具链与端到端测试 (`LiveSyncServersTest`)
- `fe979fe` (2026-09-07): 全量同步整改与稳定性修复（`CacheCorruptedError`、KDBX4 随机 IV 误判重传修复、`SyncCache` UUID 化、WebDAV/S3 原子写加固）
- `bcfa1f5` (2026-09-07): overlay 攻击防护加固（`HIDE_OVERLAY_WINDOWS` + 首帧遮蔽）
- `a8172c1` (2026-09-07): 系统凭据自动填充双通道与内联建议（Credential Provider `meta-data` 契约名修正、`<capabilities>` 声明、 IME 内联建议）
- `c3dccbc` (2026-09-06): 同步凭据链路 CharArray 化（`SyncCredentialsStore` 借用语义、封印即擦除）
- `b05d18b` (2026-09-06): 体检批次 B——标准库对齐与安全纵深（PSL 全量接入、HMAC 归一 JCE `Mac`、`readFully` 收敛）
- `ef17057` (2026-09-06): Material You 动态取色与主题系统优化
- `9910237` (2026-09-06): 日志脱敏与文件导出（`DebugLogBuffer`）
- `73944a2` (2026-09-06): 统一认证策略与多模块重构
- `15a4156` (2026-09-06): 加解密安全审查全量整改（P2×3 + P3 M1–M4 全闭合）
- `a7efa12` (2026-09-06): 体检批次 A——传输安全整改（全量移除证书固定、全站强制 HTTPS）
- `7c025c1` (2026-09-06): Wave 13 安全审计整改（QuickUnlock 设备凭据绑定、rp.id 绑定、KDBX 解析资源防线）
- `5448425` (2026-09-05): 真实 KDBX4 复合密钥（密码 + XML KeyFile v2.0）真机互操作专项
- `d94ccd8` (2026-09-05): 收尾专项整改（功能断点 11 项、只读模式、tags/overrideUrl/AutoType）
- `0ae5835` (2026-09-05): 同步与合并数据丢失专项整改（单侧新建清零、冲突决策保合并产物、base 内容持久化）
- `7316cbf` (2026-09-05): 全面安全审查整改（H1 CallingOriginResolver、H2 QuickUnlock 真实化、M1 明文驻留清零）
- `0d452a2` (2026-09-05): 已知限界清零（SAX/Writer 流式化、S3 `If-Match` 条件写、链式解锁）
- `a45bfa5` (2026-09-05): 数据层完整性修复与时间解析嗅探缺陷修复
- `4927189` (2026-09-05): 云同步引擎三哈希状态机与 Credential Provider / Autofill 端到端贯通
- `ed601da` (2026-09-05): KDBX v4 引擎官方兼容与 crypto 底座
- `b0cdc89` (2026-09-04): 7 阶段全量竣工初始提交

---

## 5. 功能清单（功能勾选状态唯一源）

> 本表为功能勾选状态的唯一真相源，与代码一致（2026-09-08 全量核对）。未勾选项在 §6 列出。README 仅保留「特性亮点」摘要，不重复本明细。

### 5.1 数据库与解锁
- [x] 创建 / 打开 `.kdbx` 数据库（**仅 v4**；v3 及以下明确拒绝）
- [x] 主密码、密钥文件（key file：`KdbxKeyFile` 四级解析梯子）、生物识别解锁
- [x] AES-256 / Twofish / ChaCha20 加密算法
- [x] Argon2（d / id）、AES-KDF（SHA-256）派生
- [x] 与 KeePass / KeePassXC / pykeepass 文件互通（真实复合密钥库解锁 + 第三方往返校验已实测）
- [x] 只读模式打开（会话期写盘硬拒绝）

### 5.2 条目与分组
- [x] 分组树结构（无限层级）
- [x] 条目字段：标题、用户名、密码、URL、备注、自定义字段（含 tags / overrideUrl / AutoType）
- [x] 图标：内置图标选择 + 自定义图标上传 / 选择 UI 已完成（模型/序列化/图标池/`IconPickerDialog`/Photo Picker 全链路落地）；**残余**：列表行/详情页位图渲染与图标删除入口未接线
- [x] 附件（文件嵌入、增删导）
- [x] 条目历史版本与恢复（含全字段回滚、Visual Diff 比对）
- [x] 模板（预置网页登录 / 银行卡等常用模板，设置页一键安装）
- [x] 回收站（KDBX 标准库内回收站 + 墓碑 + `previousParentGroup` 还原）
- [x] 全文搜索与过滤（防抖 + 回收站过滤）

### 5.3 增强功能
- [x] **TOTP / HOTP**（`OtpEngine` 支持 SHA-1/256/512，扫码与手动添加，RFC 4226/6238 官方向量校验）
- [x] 密码生成器（强度评估 + Diceware 词表）
- [x] 自动填充（Credential Provider + Autofill 双通道，含 IME 内联建议）
- [x] 应用级自动填充黑名单（双通道 fail-closed：命中包名不下发数据集/凭据候选；详情页 `android://` 绑定条目可一键屏蔽，设置页条目化增删）
- [ ] 自定义键盘（Magikeyboard 式字段填充）：未实现，当前仅提供 IME 内联建议
- [x] 条目移动 / 只读锁定；[x] 条目克隆（全字段保真 + 新 UUID + 清历史）
- [x] 密码健康度离线审计（`HealthCheckEngine`）

### 5.4 同步
- [x] **WebDAV** 同步（账号、URL、路径，全站强制 HTTPS）
- [x] **S3 兼容协议** 同步（Endpoint、Bucket、Access Key/Secret、区域、path-style；服务端时钟偏移自动补偿：`Date` 头探测 + 跨进程持久化 + 偏斜 403 自愈重试）
- [x] 同步状态展示（最新同步时间、云端版本、同步反馈）
- [x] 冲突处理：三方哈希状态机 + 墓碑感知合并 + 可视化逐字段决策界面
- [x] 后台同步（手动 / 冷启动 / 周期性 WorkManager 后台同步）：冷启动按持久化偏好恢复调度，间隔 / Wi-Fi 约束即时生效
- [x] KeePass 字段引用（`{REF:...}`）引擎：官方语法子集（T/U/P/A/N/I）整库检索 + 递归展开 + 循环防护；**残余**：Notes/URL 展示侧未接

### 5.5 通行密钥（Passkey）
- [x] 在数据库中安全存储 FIDO2 / WebAuthn 凭据（对齐 KeePassXC `KPEX_PASSKEY_*` 属性 schema）
- [x] 通过 Credential Manager 创建 / 调用通行密钥（ES256 / Ed25519 / RS256 三算法）
- [x] 使用通行密钥作为数据库解锁方式（设备绑定解锁通行密钥：WebAuthn 本地断言 + 硬件 ES256 + signCount 反克隆，旧凭据兼容通道）

---

## 6. 已知局限（已知未实现摘要）

> 完整实时清单以 §2「未完成工作唯一看板」为唯一真相源；本表为面向用户的局限摘要，不重复维护状态。

| 项 | 现状 |
|---|---|
| 自定义键盘（Magikeyboard 式字段填充） | 未实现；已提供 IME 内联建议 + 自动填充双通道替代 |
| 图标列表行 / 详情页位图渲染与图标删除入口 | TASK-15 自定义图标上传/选择已落地，位图渲染与删除为残余项 |
| KeePass 字段引用展示侧（Notes/URL） | TASK-25 引擎已落地，Notes/URL 引用展开展示未接线（并入 TASK-43 范畴） |
| 进阶偏好消费方接线 | 全部开关已持久化（TASK-12），消费方未接线，登记 **TASK-43** |
| 自动填充黑名单语义边界 | ✅ 已闭环（2026-09-08，TASK-44）：**按应用包名屏蔽**，浏览器类应用以自身包名发起 Credential Manager 请求，屏蔽浏览器即屏蔽其承载的全部站点填充（「按应用」语义的固有结果）；站点级屏蔽属 `TASK-43` 范畴 |
| S3 SigV4 服务端时钟偏移补偿 | ✅ 已闭环（2026-09-08，TASK-45）：`Date` 头探测 + 持久化补偿 + 偏斜 403 恰一次自愈，无 Date 头 fail-closed |
| `OtpEngine` TOTP 计算链路 ByteArray 化 | ✅ 已闭环（2026-09-08，TASK-46）：计算链路全程 ByteArray + 用毕擦除（fail-clean），RFC 4226/6238/4648 官方向量回归全绿 |
| KDBX v3 及以下读写 | 明确拒绝（`KdbxUnsupportedVersionException`） |
| 应用发布 | 构建链路就绪，未完成 F-Droid / GitHub Release 发布 |

> 其余工程限界（对象树内存驻留、条件写依赖服务端、`ProtectedString` 纵深防御边界等）见 `AGENTS.md`「已知限界」。
