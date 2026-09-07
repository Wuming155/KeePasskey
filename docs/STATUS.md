# KeePasskey 项目状态单一真相源（Single Source of Truth）

> **更新时间**：2026-09-07  
> **权威声明**：本项目**唯一**有效的状态与任务跟踪页。`README.md` 仅作对外简介。原 `DELIVERY_PLAN.md` / `REMEDIATION_PLAN.md` / `docs/*审查报告*.md` 等历史存档文档已于 2026-09-07 **物理删除**，其结论已并入本文件与 `FINDINGS_TRACKER.md`，不再单独保留。

---

## 1. 当前版本基线

| 维度 | 数值 / 状态 | 官方依据与说明 |
|---|---|---|
| **Git HEAD** | `7b3e756` (main) | 干净工作区无提交滞后（不含本次治理改动） |
| **测试基线** | **417 个单元测试全绿**（app 99 / core 21 / crypto 42 / database 146 / sync 109） | `./gradlew test` 强制重跑校验，其中 `LiveSyncServersTest` 12 例默认跳过（需 `-DliveSyncTest`） |
| **构建状态** | `assembleDebug` + `assembleRelease` (R8) 全量通过 | AGP 9.1.0 / Gradle 9.3.1 / Kotlin 2.4.10 / Hilt 2.60.1 |
| **系统基线** | **minSdk 36**, compileSdk 36, targetSdk 36 | 仅针对 Android 16+ 深度优化，固化无旧版垫片决策 |
| **传输安全防线** | 全站强制 HTTPS（`network_security_config.xml` 禁明文 + OkHttp TLS-only），零证书固定 | 对齐 Google Developer Knowledge `pinning not recommended` 指南 |
| **PSL 与域名匹配** | 完整接入 Mozilla PSL（`public_suffix_list.dat`），IDN punycode 归一 | 消除 47 条硬编码漏判盲区，fail-closed |

---

## 2. 未完成工作唯一看板（42 项）

所有进行中、已立项、待执行体检批次、未实现功能、安全遗留与欠账统一收录于下表，按优先级排序。**新增任务必须在此表注册。**

> **与另两份文档的边界（消除多头管理）**：本表是**任务完成态的唯一看板**。`FINDINGS_TRACKER.md` 为 2026-09-07 审计时点的**代码证据快照**（其「物理状态」列不随修复实时更新，仅供追溯）；审计类任务（TASK-09~42）修复落地后**以本表状态为准**并回写 FINDINGS 的代码证据。`HEALTH_CHECK_ROADMAP.md` 仅作体检批次的**执行方案**（范围 / 依据 / 风险 / 验收），状态不在此重复维护。

| ID | 领域 | 任务名称 | 来源 | 优先级 | 状态 | 说明 / 证据 |
|:---:|:---:|---|---|:---:|:---:|---|
| **TASK-01** | 安全 | **批次 C：HMAC 防篡改回归锁 flaky 排查** | 体检路线图 C | **P0** | ✅ 已修复（2026-09-07） | **根因定位并修复**：`testCorruptHmacBlock` 偶发未抛异常系真实安全缺陷——解析期间 GZip 预读拉取到 HMAC 终止块时，`javax.crypto.CipherInputStream` 将底层 `IOException`（凭据异常）吞掉伪装为 EOF，而 `HmacBlockInputStream` 在校验**通过前**即置 `terminated=true`，`verifyEndOfStream` 误判放行（实测 ~10% 概率篡改文件静默解锁）。整改：`terminated` 仅在终止块 HMAC 校验通过后置位，失败先记录 `terminalValidationFailed` 再抛出，`verifyEndOfStream` 作为权威检查点重放失败（fail-closed）。验收：`testCorruptHmacBlock` 连跑 **20 次零失败**（修复前 40 次内 6 次复现）；`KdbxFile.kt` `!!` 已清理 |
| **TASK-02** | 平台 | **凭据能力注册实机回归** | Wave 16 遗留 | **P1** | 📋 待验证 | Wave 16 修正了 `meta-data` 名为 `android.credentials.provider`，需真机「设置 → 密码、密钥和自动填充」确认 KeePasskey 出现且能力生效 |
| **TASK-03** | 依赖 | **批次 D：kapt → KSP 迁移 + 启用 built-in Kotlin** | 体检路线图 D | **P2** | 📋 规划 | AGP 9 要求切内置 Kotlin，AGP 10 移除 opt-out；`kapt("hilt-compiler")` → `ksp("hilt-android-compiler")` |
| **TASK-04** | 存储 | **批次 E：RealSettingsRepository 迁移 Preferences DataStore** | 体检路线图 E | **P2** | 📋 规划 | 替换 SharedPreferences；`SyncCredentialsStore` Keystore AES-256-GCM 方案保持不动 |
| **TASK-05** | 构建 | **批次 F：Gradle 版本目录（`libs.versions.toml`）** | 体检路线图 F | **P2** | 📋 规划 | 集中 5 模块依赖；核对 `hilt` 1.4.0 / `credentials` 1.6.0 等依赖货币性 |
| **TASK-06** | 性能 | **批次 G：Baseline Profiles + Startup Profiles** | 体检路线图 G | **P3** | 📋 规划 | 引入 `profileinstaller` + Macrobenchmark，针对冷启动/解码生成 DEX 布局 profile |
| **TASK-07** | UI/SDK | **批次 H：compileSdk 37 → Material 3 Expressive** | 体检路线图 H | **P3** | 📋 规划 | 需 Compose BOM 2026.08.00+（compileSdk 37）；固化 minSdk 36 决策 |
| **TASK-08** | 同步 | **周期性后台同步（WorkManager）** | 功能缺口 | **P2** | ❌ 未实现 | 设置项 `periodicBackgroundSyncIntervalMinutes`（默认 30m）与 `wifiOnlySync` 已落地，**无 WorkManager 调度消费方** |
| **TASK-09** | 安全 | **P0-2 测试代码真实凭据清洗** | 审核报告 P0-2 | **P1** | ❌ 未修 | `Argon2InteropDiagnosticTest.kt:27-52` 仍含真实主密码/密钥，须删该文件改用随机自造向量；`KdbxKeyFileTest.kt:33` 同步清洗 |
| **TASK-10** | 内存 | **TOTP 种子与受保护自定义字段编辑态 CharArray 化** | 加解密审查 B9 | **P2** | ❌ 未修 | `EntryEditUiState.kt:28` `totpSecret: String` 与 `customFields.value: String` 编辑态仍以 String 承载，须同模式 CharArray 化 |
| **TASK-11** | 安全 | **Autofill Dataset 已解锁分支增加二次确认/认证** | 审核报告 P2-24 | **P2** | ❌ 未修 | `KeePasskeyAutofillService.kt:228` 已解锁分支直接下发明文密码未设 `setAuthentication` |
| **TASK-12** | 架构 | **设置项 33 个字段持久化与废弃假开关下架** | 审核报告 P1-6 | **P2** | ❌ 未修 | `SettingsViewModel.kt` `ExtendedSettings` 约 35 个开关为纯内存回显，`skipDalVerification` 等假开关须下架 |
| **TASK-13** | UI/SAF | **设置页 5 个动作 SAF 写盘接入** | 审核报告 P1-7 | **P2** | ❌ 未修 | 导出 KDBX、导出 XML、导出密钥文件、模板安装、子库挂载目前仅 `UiMessage` 假提示，需接 `CreateDocument` 写盘 |
| **TASK-14** | 安全 | **`SyncCredentialsStore` 删生产测试钩子** | 审核报告 P2-21 | **P2** | ❌ 未修 | `SyncCredentialsStore.kt:63` 保留 `public var customEncryptor / customDecryptor`，需移除或受 `@VisibleForTesting` 保护 |
| **TASK-15** | 特性 | **自定义图标上传 / 选择 UI** | 功能缺口 | **P3** | ❌ 未实现 | 模型与 XML 序列化层完好，缺前端上传与选择界面 |
| **TASK-16** | 特性 | **条目克隆（duplicate）** | 功能缺口 | **P3** | ❌ 未实现 | 库层与 ViewModel 缺克隆逻辑 |
| **TASK-17** | 协议 | **KeePass 字段引用（`{REF:...}`）引擎** | 功能缺口 | **P3** | ❌ 未实现 | 暂不支持条目间字段动态交叉引用解析 |
| **TASK-18** | 特性 | **Passkey 作为数据库解锁方式** | 功能缺口 | **P3** | ❌ 未实现 | 现快速解锁为设备锁屏凭据绑定密钥，Passkey 仅作条目数据 |
| **TASK-19** | 依赖 | **zxing → CameraX + ML Kit 迁移评估** | 依赖治理 | **P3** | 📋 评估 | `zxing-android-embedded:4.3.0` 保持稳定，评估迁移至现代 CameraX + ML Kit |
| **TASK-20** | CI | **GitHub Dependabot / OWASP 依赖漏洞巡检** | 供应链 | **P3** | 📋 评估 | 配置自动化依赖漏洞扫描工作流 |
| **TASK-21** | 整洁度 | **超 800 行文件拆分与硬编码中文抽取** | 审核报告 P3-22/23 | **P3** | ❌ 未修 | 7 个文件超 800 行（`RealVaultRepository` 1184 行、`SettingsViewModel` 1119 行等）；约 250 处硬编码中文需抽至 `strings.xml` |
| **TASK-22** | 安全/稳定 | **P3-30：`@Singleton` AutoLockManager 在 `MainActivity.onDestroy` 被 destroy** | FINDINGS 核实 | **P0(真实 Bug)** | ✅ 已修复（2026-09-07） | 移除 `MainActivity.onDestroy` 中的 `autoLockManager.destroy()` 调用（含空覆写与随之成为死代码的 `AutoLockManager.destroy()`）。`AutoLockManager` 为进程级单例（监听 `ProcessLifecycleOwner` + 熄屏广播），生命周期与进程对齐，`initialize()` 幂等，资源随进程退出由系统回收；旋转/配置重建不再销毁自动锁定调度器 |
| **TASK-23** | 安全 | **P2-9：`parseEcPrivateKey` 缺 `d ∈ [1, n-1]` 范围校验** | FINDINGS 核实 | **P1** | ❌ 未修 | `PasskeyCryptoEngine.kt:364` 用 `BigInteger(1, bytes)` 构造标量，`bytes=0` 即生成非法/可被利用私钥；应 fail-closed |
| **TASK-24** | 内存 | **P2-10：旧派生回退 `legacyCipherKey` 未清零** | FINDINGS 核实 | **P2** | ❌ 未修 | `KdbxFile.kt:144` `finally` 仅清 `legacyHmacKey`，`legacyCipherKey` 返回后残留；补 `Arrays.fill` |
| **TASK-25** | 互操作 | **P2-11：WebDAV Basic 认证 ISO-8859-1 致中文密码 401** | FINDINGS 核实 | **P2** | ❌ 未修 | `WebDavSyncProvider.kt:85` 应发 `charset=UTF-8` 并改 UTF-8 编码 |
| **TASK-26** | 协议 | **P3-16：S3 SigV4 对 `*` 与 `~` 编码不符 AWS 规范** | FINDINGS 核实 | **P2** | ❌ 未修 | `S3SyncProvider.kt:75` 含 `*`/`~` 对象键会签名不匹配 403 |
| **TASK-27** | 协议 | **P3-11：CBOR `encodeMap` 不强制 RFC 8949 Canonical 键序** | FINDINGS 核实 | **P2** | ❌ 未修 | `CborEncoder.kt:127` 按 Map 迭代序编码，Passkey 签名互验可能因键序不一致失败 |
| **TASK-28** | 敏感 | **P3-12：附件缓存明文无清理** | FINDINGS 核实 | **P2** | ❌ 未修 | `AttachmentManager.kt:14` 应加密缓存/用完即删 |
| **TASK-29** | 安全 | **P3-13：RSA `certainty = 12` 偏低** | FINDINGS 核实 | **P2** | ❌ 未修 | `PasskeyCryptoEngine.kt:173` 常规 ≥80+，False-prime 概率 ~1/2¹² |
| **TASK-30** | 功能 | **P2-17：冲突解决逐字段选择塌缩为整条目二选一** | FINDINGS 核实 | **P2** | ❌ 未修 | `ConflictResolutionViewModel.kt:128` 应实现字段级合并或简化 UI |
| **TASK-31** | 功能 | **P2-19：历史快照为空时谎报「已回滚」** | FINDINGS 核实 | **P2** | ❌ 未修 | `EntryDetailViewModel.kt:242` 应改错误提示 |
| **TASK-32** | 功能 | **P2-27：条目密码强度恒硬编码 112 bit** | FINDINGS 核实 | **P2** | ❌ 未修 | `MockData.kt:106` 应接入真实熵估算或显式标注未计算 |
| **TASK-33** | 功能 | **P2-34：TOTP 缺失时 fallback 假码 "000000"** | FINDINGS 核实 | **P2** | ❌ 未修 | `AuthenticatorViewModel.kt:82` 应显示错误或空白 |
| **TASK-34** | 功能 | **P3-25：收藏功能不落库（仅翻转内存 Flow）** | FINDINGS 核实 | **P2** | ❌ 未修 | `EntryDetailViewModel.kt:195` `toggleFavorite` 未调 Repository 保存 |
| **TASK-35** | 功能 | **P3-26：`EntryCategory` 与银行卡字段未映射** | FINDINGS 核实 | **P2** | ❌ 未修 | `RealVaultRepository.kt:723` 卡条目被当普通登录展示 |
| **TASK-36** | 功能 | **P3-27：自动填充黑名单空 onClick + 写死列表** | FINDINGS 核实 | **P2** | ❌ 未修 | `AutofillSettingsScreen.kt:343` 删除按钮回调为空 |
| **TASK-37** | 数据 | **P2-15：`SyncCache.updateBase` 两文件非原子写** | FINDINGS 核实 | **P2** | ❌ 未修 | `SyncCache.kt:142` `.baseversion` 与 `.meta` 分两次写，应合并原子写 |
| **TASK-38** | 构建 | **P2-32：Release 未开 `shrinkResources`；ProGuard 过度宽松** | FINDINGS 核实 | **P2** | ❌ 未修 | `app/build.gradle.kts:24` 缺 `isShrinkResources = true` |
| **TASK-39** | 性能 | **P2-29：SAF 密钥文件读取在主线程完成** | FINDINGS 核实 | **P2** | ❌ 未修 | `UnlockScreen.kt:110` 应移至 `Dispatchers.IO` |
| **TASK-40** | 测试 | **测试覆盖补强（T-03 / T-04 / P2-35 / P2-37）** | FINDINGS 核实 | **P2** | ❌ 未修 | 补 `SyncCacheTest` / SigV4 已知答案向量 / `SecurityTest` 真实密钥 / 真实 Keystore 路径用例 |
| **TASK-41** | 整洁度 | **低危清理批次（P3-5/7/9/10/14/15/17/24/28/31/32/33/34）** | FINDINGS 核实 | **P3** | ❌ 未修 | O(n²) 去重 / 未用 import / 测试后门 / EMPTY 单例污染 / 魔数 / 路径遍历 / 静默 catch / 演示路径 / 空 if 块 / 吞异常 / Context? / 文件名 / 旧文档 |
| **TASK-42** | 性能 | **低-中调度批次（P2-2 / P2-12 / P2-30 / P2-31）** | FINDINGS 核实 | **P3** | ❌ 未修 | Argon2 走 Dispatchers.IO / 缺 callTimeout / combine 未 flowOn / 构造期扫盘 |

---

## 3. 历史发现项全量审计汇总（131 项）

针对 2026-09-05 及 2026-09-06 三份审查报告中的 131 项发现，对照当前 `main` 分支代码完成逐条物理核对。详细核对卷宗见 [**FINDINGS_TRACKER.md**](FINDINGS_TRACKER.md)。

| 报告来源 | 发现总数 | ✅ 已修复 | ⚠️ 部分修复 | ❌ 未修复 | ➖ 不适用 / 记录备查 |
|---|:---:|:---:|:---:|:---:|:---:|
| **全量代码审核报告（2026-09-05）** | 93 | 37 | 15 | 39 | 2 |
| **安全审查报告（2026-09-06 Wave 13）** | 16 | 15 | 0 | 1 | 0 |
| **加解密实现审查报告（2026-09-06）** | 9 | 8 | 0 | 1 | 0 |
| **审核报告第七节测试覆盖缺口** | 7 | 2 | 2 | 3 | 0 |
| **合计** | **131** | **62 (47%)** | **17 (13%)** | **44 (34%)** | **8 (6%)** |

> **关键结论**：在 131 项发现中，所有 P0 级阻断项（7 项）与高危安全缺陷（如自研 PIN 解锁、全站明文流量、旧派生 HMAC 校验、GCM IV 唯一性等）已**100% 修复**；未修复的 44 项主要集中在：① 约 35 个设置项无消费者（P1-6）；② 5 个假动作 SAF 导出（P1-7）；③ 超 800 行文件与硬编码中文（P3-22/23）；④ 测试代码中的假用例与覆盖缺口（P2-36/37）。

### 3.1 实测核实补充结论（2026-09-07）

> 本节为 2026-09-07 对 [**FINDINGS_TRACKER.md**](FINDINGS_TRACKER.md) 全部「❌ 未修复」项逐条物理核对的补充结论。Tracker 现已为每项补充「是否有必要修复」与「说明」两列。

**结论修正（原报告描述须更正，非 bug）**：
- **P3-19** `hasLocalChanges` 方向：实测为保守策略（.version 缺失→无法确认→`false`；.baseversion 缺失→无法比对→保守 `true`），**非 bug**，仅需补注释明确语义。
- **P3-29** `(context as? MainActivity)`：**安全转换 `as?` 而非强转**，原「强转」结论不准确，风险极低。
- **P3-8** `KdbxFile.save` 头部：**仅一次序列化 + ByteArrayOutputStream 双重缓冲**，非「序列化两遍」，无正确性风险。

**实测判定「必须修复」的未修复项**：已逐条登记为 `STATUS.md` §2 的 **TASK-22 ~ TASK-42**（含对应 P 编号、优先级与代码证据），此处不再复述，仅列严重度速览与 TASK 映射：

- **高（真实 Bug）**：P3-30 → **TASK-22**（`@Singleton` `autoLockManager` 在 `MainActivity.onDestroy` 被 `destroy()`，旋转即失效）
- **中（安全）**：P2-9、P2-10、P3-11、P3-12、P3-13、P3-16 → TASK-23~29（EC 标量越界 / `legacyCipherKey` 残留 / CBOR 非 Canonical / 附件明文缓存 / RSA `certainty=12` / SigV4 `*` `~` 编码不符）
- **中（功能 / 数据 / 互操作 / 构建 / 测试）**：P2-15、P2-17、P2-19、P2-27、P2-34、P3-25、P3-26、P3-27、P2-11、P2-32、T-03、T-04、P2-35、P2-37 → TASK-30~40（非原子写 / 字段级合并塌缩 / 空快照谎报回滚 / 强度硬编码 112 / 假码 000000 / 收藏不落库 / category 与卡字段未映射 / 黑名单空 onClick / WebDAV ISO-8859-1 / `shrinkResources` 未开 / `SyncCacheTest` 缺失 / SigV4 无已知答案 / `SecurityTest` 假密钥 / 真实 Keystore 路径无测）
- **低-中（性能 / 调度）**：P2-2、P2-12、P2-29、P2-30、P2-31 → **TASK-42**（Argon2 走 `Dispatchers.IO` / 缺 callTimeout / SAF 主线程读密钥 / `combine` 未 flowOn / 构造期扫盘）
- **低（整洁度 / 需外部服务）**：P3-5/7/9/10/14/15/17/24/28/31/32/33/34、P2-26、P2-28 → **TASK-41**（死代码 / 魔数 / 空块 / 单例污染 / 路径遍历等清理；P2-26 占位演示数据、P2-28 泄露密码恒 0 需 HIBP 接入）

---

## 4. 历史改动日志索引（取代 Wave 编号）

旧的 Wave 编号体系因插队与顺延已失去时间语义，**即日起整体冻结，改用「Git 提交号 + 日期 + 主题」作唯一标识**：

> 索引中出现的 `Wave N` / `阶段 N` 字样为对应历史提交的**原始主题**，属已冻结语境，仅供追溯；新提交请以 `TASK-xx` / `批次 X` + 提交哈希 引用，勿再使用 Wave / 阶段 编号。

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
