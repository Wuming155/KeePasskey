# KeePasskey 项目状态单一真相源（Single Source of Truth）

> **更新时间**：2026-09-07  
> **权威声明**：本项目**唯一**有效的状态与任务跟踪页。`README.md` 仅作对外简介，其余 `DELIVERY_PLAN.md` / `REMEDIATION_PLAN.md` / `docs/*审查报告*.md` 均标记为历史存档并停止状态更新。

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

## 2. 未完成工作唯一看板（21 项）

所有进行中、已立项、待执行体检批次、未实现功能、安全遗留与欠账统一收录于下表，按优先级排序。**新增任务必须在此表注册。**

| ID | 领域 | 任务名称 | 来源 | 优先级 | 状态 | 说明 / 证据 |
|:---:|:---:|---|---|:---:|:---:|---|
| **TASK-01** | 安全 | **批次 H：HMAC 防篡改回归锁 flaky 排查** | 体检路线图 H | **P0** | 🟡 进行中 | `testCorruptHmacBlock` 偶发未抛异常，KDBX 防篡改关键回归锁，需 ≥20 次全跑零失败；代码侧 `KdbxFile.kt` `!!` 清理已完成未提交 |
| **TASK-02** | 平台 | **凭据能力注册实机回归** | Wave 16 遗留 | **P1** | 📋 待验证 | Wave 16 修正了 `meta-data` 名为 `android.credentials.provider`，需真机「设置 → 密码、密钥和自动填充」确认 KeePasskey 出现且能力生效 |
| **TASK-03** | 依赖 | **批次 B：kapt → KSP 迁移 + 启用 built-in Kotlin** | 体检路线图 B | **P2** | 📋 规划 | AGP 9 要求切内置 Kotlin，AGP 10 移除 opt-out；`kapt("hilt-compiler")` → `ksp("hilt-android-compiler")` |
| **TASK-04** | 存储 | **批次 C：RealSettingsRepository 迁移 Preferences DataStore** | 体检路线图 C | **P2** | 📋 规划 | 替换 SharedPreferences；`SyncCredentialsStore` Keystore AES-256-GCM 方案保持不动 |
| **TASK-05** | 构建 | **批次 D：Gradle 版本目录（`libs.versions.toml`）** | 体检路线图 D | **P2** | 📋 规划 | 集中 5 模块依赖；核对 `hilt` 1.4.0 / `credentials` 1.6.0 等依赖货币性 |
| **TASK-06** | 性能 | **批次 E：Baseline Profiles + Startup Profiles** | 体检路线图 E | **P3** | 📋 规划 | 引入 `profileinstaller` + Macrobenchmark，针对冷启动/解码生成 DEX 布局 profile |
| **TASK-07** | UI/SDK | **批次 F：compileSdk 37 → Material 3 Expressive** | 体检路线图 F | **P3** | 📋 规划 | 需 Compose BOM 2026.08.00+（compileSdk 37）；固化 minSdk 36 决策 |
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

---

## 4. 历史改动日志索引（取代 Wave 编号）

旧的 Wave 编号体系因插队与顺延已失去时间语义，**即日起整体冻结，改用「Git 提交号 + 日期 + 主题」作唯一标识**：

- `7b3e756` (2026-09-07): WebDAV 零字节文件元数据误报修复
- `0d4fc16` (2026-09-07): 本地 HTTPS 同步联调工具链与端到端测试 (`LiveSyncServersTest`)
- `fe979fe` (2026-09-07): 全量同步整改与稳定性修复（`CacheCorruptedError`、KDBX4 随机 IV 误判重传修复、`SyncCache` UUID 化、WebDAV/S3 原子写加固）
- `bcfa1f5` (2026-09-07): overlay 攻击防护加固（`HIDE_OVERLAY_WINDOWS` + 首帧遮蔽）
- `a8172c1` (2026-09-07): 系统凭据自动填充双通道与内联建议（Credential Provider `meta-data` 契约名修正、`<capabilities>` 声明、 IME 内联建议）
- `c3dccbc` (2026-09-06): 同步凭据链路 CharArray 化（`SyncCredentialsStore` 借用语义、封印即擦除）
- `b05d18b` (2026-09-06): 体检批次 G——标准库对齐与安全纵深（PSL 全量接入、HMAC 归一 JCE `Mac`、`readFully` 收敛）
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
