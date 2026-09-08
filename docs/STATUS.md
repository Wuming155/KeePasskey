# KeePasskey 项目状态单一真相源（Single Source of Truth）

> **更新时间**：2026-09-07  
> **权威声明**：本项目**唯一**有效的状态与任务跟踪页。`README.md` 仅作对外简介。原 `DELIVERY_PLAN.md` / `REMEDIATION_PLAN.md` / `docs/*审查报告*.md` 等历史存档文档已于 2026-09-07 **物理删除**，其结论已并入本文件与 `FINDINGS_TRACKER.md`，不再单独保留。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | `7b3e756` (main) | 干净工作区无提交滞后（不含本次治理改动） |
| **测试基线** | **443 个单元测试全绿**（app 101 / core 27 / crypto 52 / database 146 / sync 117） | `./gradlew test` 强制重跑校验，其中 `LiveSyncServersTest` 12 例默认跳过（需 `-DliveSyncTest`） |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | AGP 9.1.0 / Gradle 9.3.1 / Kotlin 2.4.10 / Hilt 2.60.1 |
| **系统基线** | **minSdk 36**, compileSdk 36, targetSdk 36 | 仅针对 Android 16+ 深度优化，固化无旧版垫片决策 |
| **传输安全防线** | 全站强制 HTTPS（`network_security_config.xml` 禁明文 + OkHttp TLS-only），零证书固定 | 对齐 Google Developer Knowledge `pinning not recommended` 指南 |
| **PSL 与域名匹配** | 完整接入 Mozilla PSL（`public_suffix_list.dat`），IDN punycode 归一 | 消除 47 条硬编码漏判盲区，fail-closed |

---

## 2. 未完成工作唯一看板（44 项）

所有进行中、已立项、待执行体检批次、未实现功能、安全遗留与欠账统一收录于下表，按优先级排序。**新增任务必须在此表注册。**

> **与另两份文档的边界（消除多头管理）**：本表是**任务完成态的唯一看板**。`FINDINGS_TRACKER.md` 为 2026-09-07 审计时点的**代码证据快照**（其「物理状态」列不随修复实时更新，仅供追溯）；审计类任务（TASK-09~42）修复落地后**以本表状态为准**并回写 FINDINGS 的代码证据。`HEALTH_CHECK_ROADMAP.md` 仅作体检批次的**执行方案**（范围 / 依据 / 风险 / 验收），状态不在此重复维护。

| ID | 领域 | 任务名称 | 来源 | 优先级 | 状态 | 说明 / 证据 |
|:---:|:---:|---|---|:---:|:---:|---|
| **TASK-01** | 安全 | **批次 C：HMAC 防篡改回归锁 flaky 排查** | 体检路线图 C | **P0** | ✅ 已修复（2026-09-07） | **根因定位并修复**：`testCorruptHmacBlock` 偶发未抛异常系真实安全缺陷——解析期间 GZip 预读拉取到 HMAC 终止块时，`javax.crypto.CipherInputStream` 将底层 `IOException`（凭据异常）吞掉伪装为 EOF，而 `HmacBlockInputStream` 在校验**通过前**即置 `terminated=true`，`verifyEndOfStream` 误判放行（实测 ~10% 概率篡改文件静默解锁）。整改：`terminated` 仅在终止块 HMAC 校验通过后置位，失败先记录 `terminalValidationFailed` 再抛出，`verifyEndOfStream` 作为权威检查点重放失败（fail-closed）。验收：`testCorruptHmacBlock` 连跑 **20 次零失败**（修复前 40 次内 6 次复现）；`KdbxFile.kt` `!!` 已清理 |
| **TASK-02** | 平台 | **凭据能力注册实机回归** | Wave 16 遗留 | **P1** | 📋 待验证（代码层已核实） | 2026-09-07 代码层核实：`AndroidManifest.xml` 凭据服务 `meta-data` 名为 `android.credentials.provider`（契约名正确），`@xml/credential_provider_service` 资源存在，Autofill 兼容层 `android.autofill` 同步在位。**剩余动作需真机**：「设置 → 密码、密钥和自动填充」确认 KeePasskey 出现且能力生效 |
| **TASK-03** | 依赖 | **批次 D：kapt → KSP 迁移 + 启用 built-in Kotlin** | 体检路线图 D | **P2** | 📋 规划 | AGP 9 要求切内置 Kotlin，AGP 10 移除 opt-out；`kapt("hilt-compiler")` → `ksp("hilt-android-compiler")` |
| **TASK-04** | 存储 | **批次 E：RealSettingsRepository 迁移 Preferences DataStore** | 体检路线图 E | **P2** | 📋 规划 | 替换 SharedPreferences；`SyncCredentialsStore` Keystore AES-256-GCM 方案保持不动 |
| **TASK-05** | 构建 | **批次 F：Gradle 版本目录（`libs.versions.toml`）** | 体检路线图 F | **P2** | 📋 规划 | 集中 5 模块依赖；核对 `hilt` 1.4.0 / `credentials` 1.6.0 等依赖货币性 |
| **TASK-06** | 性能 | **批次 G：Baseline Profiles + Startup Profiles** | 体检路线图 G | **P3** | 📋 规划 | 引入 `profileinstaller` + Macrobenchmark，针对冷启动/解码生成 DEX 布局 profile |
| **TASK-07** | UI/SDK | **批次 H：compileSdk 37 → Material 3 Expressive** | 体检路线图 H | **P3** | 📋 规划 | 需 Compose BOM 2026.08.00+（compileSdk 37）；固化 minSdk 36 决策 |
| **TASK-08** | 同步 | **周期性后台同步（WorkManager）** | 功能缺口 | **P2** | ❌ 未实现 | 设置项 `periodicBackgroundSyncIntervalMinutes`（默认 30m）与 `wifiOnlySync` 已落地，**无 WorkManager 调度消费方** |
| **TASK-09** | 安全 | **P0-2 测试代码真实凭据清洗** | 审核报告 P0-2 | **P1** | ✅ 已完成（2026-09-07） | **核实完成**：`Argon2InteropDiagnosticTest.kt` 已全部换用合成口令 `TestMasterPassword!2026#Secure` 与自造十六进制密钥/盐（期望值由独立参考实现离线预计算，互操作校验语义不变）；`KdbxKeyFileTest.kt` 为 32B 合成测试字节（0x01..0x20）。仓库级扫描（测试源码密码赋值模式 + `.kdbx`/真实库引用模式）零命中，**仓库内零真实凭据**。FINDINGS P0-2 已在历史提交中标记修复，本次为看板状态同步 |
| **TASK-10** | 内存 | **TOTP 种子与受保护自定义字段编辑态 CharArray 化** | 加解密审查 B9 | **P2** | ✅ 已修复（2026-09-07） | **同 M1 密码模式全面 CharArray 化**：`EntryEditUiState` 移除 `totpSecret: String`，TOTP 种子经 `EntryEditViewModel` CharArray 私有链路 + 一次性预填通道（`loadedTotpSecret`）承载，UI 走 `SecurePasswordField` 桥接；受保护自定义字段明文经 `protectedFieldChars` 私有映射 + `loadedProtectedFields` 预填通道承载（UI 投影恒空串，对齐详情页掩码投影语义），保护标记切换时明文自动迁移存储。仓库契约同步收紧：`saveEntry` 改 `totpSecretChars: CharArray?` + `protectedFieldChars: Map<String, CharArray>`（擦除契约扩展）、`getEntryTotpSecret`/`getEntryProtectedField` 改 CharArray 独占副本读取；详情页展示/复制路径同步改造。`onCleared` 擦除全部驻留。428 例全绿 |
| **TASK-11** | 安全 | **Autofill Dataset 已解锁分支增加二次确认/认证** | 审核报告 P2-24 | **P2** | ✅ 已修复（2026-09-07） | `KeePasskeyAutofillService` 已解锁分支每个数据集下发前挂 `setAuthentication`，认证 PendingIntent 指向新增 `AutofillConfirmActivity`（每数据集独立 requestCode 防 PendingIntent 覆盖）：优先系统级生物识别/锁屏凭据（`UNLOCK_AUTHENTICATORS` 集合），无硬件时退化为受保护窗口内手动确认（确认/取消）。Activity 具备 FLAG_SECURE + `setHideOverlayWindows(true)` 反截屏/反 overlay 加固；仅 RESULT_OK 后框架才将数据集值写入目标表单 |
| **TASK-12** | 架构 | **设置项 33 个字段持久化（全部开关保留为预留功能）** | 审核报告 P1-6 | **P2** | ✅ 已完成（2026-09-08） | **裁定：全部开关保留不下架**（均为预留功能，消费方接线登记为 TASK-43）。整改核心为消除「纯内存回显」：`ExtendedSettings` 提升为公共模型并新增 `ExtendedSettingsStore`（SharedPreferences 持久化，整体读/整体写，null 上下文时退化为内存语义保可测性），`SettingsViewModel` 全部 setter 经 `updateExtended` 统一「更新+落盘」，冷启动不再静默回落默认值；`wifiOnlySync` 以独立键持久化。Store 无持久化层回退语义补单测 |
| **TASK-13** | UI/SAF | **设置页 5 个动作 SAF 真实化** | 审核报告 P1-7 | **P2** | ✅ 已完成（2026-09-08） | 导出三件套真实化：KDBX（`DatabaseSession.exportToBytes` 内存库全量序列化）、XML（新增 `KeePassXmlExporter` 输出 KeePass 2.x 兼容明文格式，可被 KeePass/KeePassXC 导入，明文安全声明见导出警告文案）、密钥文件（会话 `keyFileCache` 原件字节）——三者均经 `CreateDocument` SAF 另存为落盘，失败如实上浮。模板安装真实化：`installEntryTemplates` 幂等创建「模板」分组与 5 个标准模板条目并落库。子库挂载：从谎报「挂载成功」改为如实提示「尚未实现」（真实功能缺口，登记 TASK-43） |
| **TASK-08** | 同步 | **周期性后台同步（WorkManager）** | 功能缺口 | **P2** | ✅ 已完成（2026-09-08） | 新增 `PeriodicSyncWorker`（CoroutineWorker + Hilt EntryPoint 获取 `SyncCoordinator`，与前台同步共享 mutex 天然互斥；冲突留待用户决策、错误不重试避免退避风暴）与 `PeriodicSyncScheduler`（唯一周期任务 UPDATE 语义，间隔强制 ≥15 分钟，`wifiOnlySync` 映射 UNMETERED/CONNECTED 网络约束）。冷启动 `MainApplication` 按持久化偏好恢复调度；设置页开关/间隔/Wi-Fi 三项变更即时生效。依赖 `androidx.work:work-runtime-ktx:2.10.0` |
| **TASK-14** | 安全 | **`SyncCredentialsStore` 删生产测试钩子** | 审核报告 P2-21 | **P2** | ✅ 已修复（2026-09-07） | `customEncryptor`/`customDecryptor` 加 `@VisibleForTesting` 注解并收窄为 `internal`——生产 DI 与外部调用方不可见、不可写，仅本模块单元测试（同一编译单元）可注入模拟加解密闭包 |
| **TASK-15** | 特性 | **自定义图标上传 / 选择 UI** | 功能缺口 | **P3** | ❌ 未实现 | 模型与 XML 序列化层完好，缺前端上传与选择界面 |
| **TASK-16** | 特性 | **条目克隆（duplicate）** | 功能缺口 | **P3** | ❌ 未实现 | 库层与 ViewModel 缺克隆逻辑 |
| **TASK-17** | 协议 | **KeePass 字段引用（`{REF:...}`）引擎** | 功能缺口 | **P3** | ❌ 未实现 | 暂不支持条目间字段动态交叉引用解析 |
| **TASK-18** | 特性 | **Passkey 作为数据库解锁方式** | 功能缺口 | **P3** | ❌ 未实现 | 现快速解锁为设备锁屏凭据绑定密钥，Passkey 仅作条目数据 |
| **TASK-19** | 依赖 | **zxing → CameraX + ML Kit 迁移评估** | 依赖治理 | **P3** | 📋 评估 | `zxing-android-embedded:4.3.0` 保持稳定，评估迁移至现代 CameraX + ML Kit |
| **TASK-20** | CI | **GitHub Dependabot / OWASP 依赖漏洞巡检** | 供应链 | **P3** | 📋 评估 | 配置自动化依赖漏洞扫描工作流 |
| **TASK-21** | 整洁度 | **超 800 行文件拆分与硬编码中文抽取** | 审核报告 P3-22/23 | **P3** | ❌ 未修 | 7 个文件超 800 行（`RealVaultRepository` 1184 行、`SettingsViewModel` 1119 行等）；约 250 处硬编码中文需抽至 `strings.xml` |
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
| **TASK-41** | 整洁度 | **低危清理批次（P3-5/7/9/10/14/15/17/24/28/31/32/33/34）** | FINDINGS 核实 | **P3** | ❌ 未修 | O(n²) 去重 / 未用 import / 测试后门 / EMPTY 单例污染 / 魔数 / 路径遍历 / 静默 catch / 演示路径 / 空 if 块 / 吞异常 / Context? / 文件名 / 旧文档 |
| **TASK-42** | 性能 | **低-中调度批次（P2-2 / P2-12 / P2-30 / P2-31）** | FINDINGS 核实 | **P3** | ✅ 已修复（2026-09-08） | 四项全闭合：P2-2 `DatabaseSession.save` 序列化（Argon2 派生+流加密，CPU 密集）移至 `Dispatchers.Default`，仅字节落盘（writeAtomic+fsync）走 IO（对齐 exportToBytes 先例，写毕擦除序列化缓冲）；P2-12 `SyncHttpClientFactory` 补全局 `callTimeout`（默认 5 分钟，覆盖 DNS+连接+读写全生命周期，弱网悬挂兜底封顶，`SyncNetworkOptions` 新增 `callTimeoutMs`）；P2-30 `AuthenticatorViewModel` uiState 上游显式 `flowOn(Dispatchers.Default)`（combine 内含种子解析+HMAC，兜底脱离主线程，测试同步改为真实时间轮询等待）；P2-31 `RealVaultRepository` 构造期不再同步扫盘——`listFiles` 移至协程 + `Dispatchers.IO`，databasesFlow 经 Flow 自然推送更新 |
| **TASK-43** | 特性 | **进阶偏好消费方接线（预留功能清单）** | TASK-12 裁定衍生 | **P3** | ❌ 未实现 | 设置页全部进阶开关保留为预留功能（已随 TASK-12 持久化，不再回显丢失）。待接线消费方：`webdavChunkedUpload`/`webdavChunkSizeMb`（WebDAV 分块上传）、`createBackupBeforeSave`（保存前 .bak）、`checkRemoteChangesBeforeSave`、`conflictResolution` 默认策略、`useFileTransactions`（已是既定行为，接线为信息展示）、`preloadDatabaseEnabled`、`lockWhenNavigateBack`、`clearPasswordOnLeave`、`rememberRecentFiles`、`rememberKeyFileLocation`、`showKillAppOption`、`offerSaveCredentials`、`inlineSuggestionsEnabled`、`autoReturnFromQuery`、`autofillCopyTotp`、`autofillShowTotpNotification`、`skipDalVerification`、`overrideNoAutofill`、`disabledAutofillQueriesCount`（黑名单，完整生命周期见 TASK-44）、`maskPasswordsDefault`、`maskTotpDefault`、`showUnlockedNotification`、`showGroupInSearchResult`、`showGroupInEntry`、`listDensity`、`autoActivateSearchOnOpen`、`iconSet`、TOTP 字段映射、`debugLogEnabled`、`verboseSyncLog`、子库挂载（TASK-13 改诚实提示）。另：导出/导入五源（1PUX/Bitwarden/KeePass/浏览器 CSV）解析器亦为独立功能缺口 |
| **TASK-44** | 特性 | **自动填充黑名单完整生命周期** | TASK-36 整改衍生 | **P3** | ❌ 未实现 | TASK-36 整改确认黑名单为端到端功能缺口（设置页仅展示计数，且该计数无任何写入方）。待建设：① 黑名单存储（包名集合持久化，替代现无写入方的 `disabledAutofillQueriesCount` 计数）；② 自动填充服务侧消费（黑名单包名不下发数据集）；③ 入口（详情页/系统设置「为本应用禁用填充」写入黑名单）；④ 设置页条目化展示与删除（替代当前仅计数展示）。对齐 KP2A「禁用自动填充查询」语义 |

---

## 3. 历史发现项全量审计汇总（131 项）

针对 2026-09-05 及 2026-09-06 三份审查报告中的 131 项发现，对照当前 `main` 分支代码完成逐条物理核对。详细核对卷宗见 [**FINDINGS_TRACKER.md**](FINDINGS_TRACKER.md)。

| 报告来源 | 发现总数 | ✅ 已修复 | ⚠️ 部分修复 | ❌ 未修复 | ➖ 不适用 / 记录备查 |
|---|:---:|:---:|:---:|:---:|:---:|
| **全量代码审核报告（2026-09-05）** | 93 | 38 | 15 | 38 | 2 |
| **安全审查报告（2026-09-06 Wave 13）** | 16 | 15 | 0 | 1 | 0 |
| **加解密实现审查报告（2026-09-06）** | 9 | 8 | 0 | 1 | 0 |
| **审核报告第七节测试覆盖缺口** | 7 | 2 | 2 | 3 | 0 |
| **合计** | **131** | **63 (48%)** | **17 (13%)** | **43 (33%)** | **8 (6%)** |

> **关键结论**：在 131 项发现中，所有 P0 级阻断项（7 项）与高危安全缺陷（如自研 PIN 解锁、全站明文流量、旧派生 HMAC 校验、GCM IV 唯一性等）已**100% 修复**；未修复的 43 项主要集中在：① 约 35 个设置项无消费者（P1-6）；② 5 个假动作 SAF 导出（P1-7）；③ 超 800 行文件与硬编码中文（P3-22/23）；④ 测试代码中的假用例与覆盖缺口（P2-36/37）。

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

- `68f2bcb` (2026-09-08): 阶段5 功能/质量收尾——TASK-30~36 七项闭合（字段级冲突合并 / 空快照诚实报错 / 密码强度真实熵 / TOTP 假码移除 / 收藏落库 / 卡条目映射 / 黑名单诚实化）+ TASK-44 登记
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
