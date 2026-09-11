# KeePasskey 已整改问题与历史任务归档（Resolved Log）

> **文档定位**：已完成修复的缺陷、已落地特性与已闭环技术债的**全量归档库**；记录修复实现、验收证据与关联提交，用于防回退与历史溯源。
> **维护规则**：`ACTIVE_ISSUES.md` 的任务经整改并通过测试后，整条移入本文件对应章节。**如实留痕**：整改中真实发生的生产/测试/编排缺陷均不美化、不隐去（详见各批次「过程缺陷」一行）。

---

## 批次索引

| 章节 | 批次 | 条目范围 |
|---|---|---|
| §1 | 已完成核心任务 | `TASK-01` ~ `TASK-53` |
| §2 | 历史全量代码审计（93 项 + 4 类专项 + ZT/P1/P2 逐项） | 2.1 ~ 2.22 |
| §3 | P3 批次（16 项） | ISSUE-P3-01 ~ P3-16 |
| §4 | P3 残余批次（12 项） | ISSUE-P3-17 ~ P3-28 |
| §5 | P3-30 单条（子库条目只读投影） | ISSUE-P3-30 |
| §6 | P3-29 批次 A（全仓超阈值债务） | ISSUE-P3-29 |
| §7 | CI 首跑实测整改（lint / CodeQL / 供应链闸门） | ISSUE-P3-32 |
| §8 | 原生内核扩展与工程化（AES-KDF / Twofish / 口令强度 / HMAC / 语料） | ISSUE-P3-34 ~ P3-38 |
| §9 | 自动填充能力对标 | ISSUE-P3-39 ~ P3-45 |
| §10 | P3-31 批次 B（超阈值债务） | ISSUE-P3-31 |
| §11 | P3-31 批次 C + P3-43 闭环 | ISSUE-P3-31 / P3-43 |
| §12 | P3-31 批次 D + P3-46 闭环 | ISSUE-P3-31 / P3-46 |
| §13 | P3-31 批次 E（超阈值债务） | ISSUE-P3-31 |
| §14 | P3-31 批次 F（超阈值债务） | ISSUE-P3-31 |
| §15 | P3-31 批次 G（超阈值债务） | ISSUE-P3-31 |
| §16 | P3-31 批次 H（超阈值债务） | ISSUE-P3-31 |

> 各批次验收证据（用例数 / 通过 / 失败 / 跳过）分别见 §2.22、§3.1、§4.1、§5.1、§6.1、§7.1、§8.0、§9.5、§10.1、§11、§12、§13、§14、§15、§16。

---

## 1. 已完成核心任务清单

| TASK | 领域 | 主题 | 优先级 | 完成 | 核心实现 |
|:---:|:---:|---|:---:|:---:|---|
| 01 | 安全 | HMAC 防篡改回归锁 flaky 修复 | P0 | 09-07 | 仅校验通过后置 `terminated`；`verifyEndOfStream` 权威 fail-closed；20 连跑零失败 |
| 02 | 集成 | 凭据提供者端到端注册与调起 | P1 | 09-09 | `CredentialPendingIntents.ENTRY_FLAGS` 统一替换 5 处 `FLAG_IMMUTABLE`（P1-01） |
| 03 | 依赖 | kapt → KSP 2.3.11 + 内置 Kotlin | P2 | 09-08 | 编译提速 |
| 04 | 存储 | 设置持久化迁 DataStore | P2 | 09-08 | 21 项设置 Flow 化 |
| 05 | 构建 | Gradle 版本目录集中管理 | P2 | 09-08 | `libs.versions.toml` |
| 06 | 性能 | Baseline / Startup Profiles | P3 | 09-08 | `profileinstaller` |
| 07 | UI | compileSdk 37 + M3 Expressive | P3 | 09-08 | |
| 08 | 同步 | 周期后台同步 WorkManager | P2 | 09-08 | `PeriodicSyncWorker` |
| 09 | 安全 | 测试代码真实凭据清洗 | P1 | 09-07 | 全仓改合成向量 |
| 10 | 内存 | TOTP/受保护字段 CharArray 化 | P2 | 09-07 | |
| 11 | 安全 | 填充前二次确认/认证 | P2 | 09-07 | `AutofillConfirmActivity` |
| 12 | 架构 | 设置项持久化（不下架） | P2 | 09-08 | `ExtendedSettingsStore` |
| 13 | UI/SAF | 设置页 5 动作 SAF 真实化 | P2 | 09-08 | |
| 14 | 安全 | `SyncCredentialsStore` 测试钩子收窄 | P2 | 09-07 | `@VisibleForTesting`+`internal` |
| 15 | 特性 | 自定义图标上传/选择 | P3 | 09-08 | `CustomIconCoordinator` |
| 16 | 特性 | 条目克隆 | P3 | 09-08 | `EntryDuplicateCoordinator` |
| 17 | 协议 | 字段引用 `{REF:...}` 引擎 | P3 | 09-08 | `FieldReferenceEngine` |
| 18 | 特性 | Passkey 作为解锁方式 | P3 | 09-08 | signCount 反克隆 |
| 20 | CI | Dependabot / OWASP 巡检 | P3 | 09-08 | |
| 21 | 整洁 | 超 800 行拆分 + 文案资源化 | P3 | 09-08 | 7 文件拆分 |
| 22 | 安全 | AutoLockManager 误销毁修复 | P0 | 09-07 | 移除单例误销毁 |
| 23 | 安全 | EC 标量 `d ∈ [1,n-1]` 校验 | P1 | 09-07 | `validateEcScalarRange` |
| 24 | 内存 | 旧派生密钥清零 | P2 | 09-07 | `CipherKeyResolution` |
| 25 | 互操作 | WebDAV Basic 改 UTF-8 | P2 | 09-08 | |
| 26 | 协议 | S3 SigV4 规范 URI 编码 | P2 | 09-08 | |
| 27 | 协议 | CBOR Canonical 键序 | P2 | 09-08 | |
| 28 | 敏感 | 附件缓存用完即删 | P2 | 09-07 | |
| 29 | 安全 | RSA 素数 certainty=80 | P2 | 09-07 | |
| 30 | 功能 | 冲突逐字段选择合并 | P2 | 09-08 | `resolveConflictByFields` |
| 31 | 功能 | 空历史快照如实暴露 | P2 | 09-08 | |
| 32 | 功能 | 密码强度消除硬编码 112bit | P2 | 09-08 | |
| 33 | 功能 | TOTP 缺失下线假码 | P2 | 09-08 | |
| 34 | 功能 | 收藏持久化落库 | P2 | 09-08 | `customData["KeePasskey.Favorite"]` |
| 35 | 功能 | 银行卡分类与脱敏 | P2 | 09-08 | |
| 36 | 功能 | 自动填充黑名单去假数据 | P2 | 09-08 | |
| 37 | 数据 | `SyncCache.updateBase` 原子写+fsync | P2 | 09-08 | |
| 38 | 构建 | Release shrinkResources + 混淆收敛 | P2 | 09-08 | |
| 39 | 性能 | SAF 密钥流式读移 IO 线程 | P2 | 09-08 | |
| 40 | 测试 | AES-GCM / SigV4 向量补强 | P2 | 09-08 | |
| 41 | 整洁 | 低危/异味批量清理（13 项 P3） | P3 | 09-08 | |
| 42 | 性能 | 调度批次优化 | P3 | 09-08 | Argon2→Default；5min 超时 |
| 44 | 特性 | 自动填充黑名单端到端闭环 | P3 | 09-08 | `AutofillBlocklistStore` |
| 45 | 协议 | S3 SigV4 时钟偏移自愈 | P3 | 09-08 | |
| 46 | 内存 | `OtpEngine` ByteArray 化+擦除 | P2 | 09-08 | |
| 47 | 安全 | HIBP k-匿名泄露查询 | P3 | 09-08 | 默认关闭 |
| 48 | 数据 | 清理假默认库 + SAF 选库 | P3 | 09-09 | |
| 50 | 依赖 | Dependabot 3 项升级 | P2 | 09-08 | Gradle 9.7.1/AGP 9.4.0/OkHttp 5.5.0 |
| 51 | CI | `dependency-scan` 三层根因修复 | P1 | 09-08 | |
| 52 | 性能 | Argon2 原生 JNI 加速 + OOM 防护 | P1 | 09-09 | vendor PHC C + JNI（后由 Rust 取代） |
| 53 | 整洁 | Base64 规范化 + Hex 现代化 | P3 | 09-09 | |

---

## 2. 历史全量代码审计发现项整改归档

> 全部项已修复。下列为各专项审计的范围与结论；详见当前代码与 `ACTIVE_ISSUES` 对应条目。

- **2.1 全量代码审核（93 项）**：P0×6 / P1×15 / P2×15 等全部闭环（分组重命名安全、凭据泄露、IV 长度、OOM 上界、Base64 损坏、明文索引、密钥文件解锁、冲突伪装、生物尾零、Argon2 调度、TOTP String 驻留、EC 标量、旧密钥清零、WebDAV 中文、S3 时钟等）。
- **2.2 安全审查 Wave 13（16 项）**：鉴权/加密/存储类缺陷全部修复。
- **2.3 加解密实现审查（9 项）**：IV/Header/填充/`InMemoryCipher` 等修复。
- **2.4 测试覆盖缺口（7 项）**：补齐关键路径单测。
- **2.5 零信任专项（ZT 系列）**：ZT 各项全部闭环。
- **2.6 P1-01 凭据提供者端到端契约**：见 TASK-02。
- **2.7 P1-02 生成侧私钥内存脱敏**：私钥字节化+擦除。
- **2.8 P1-03 回收站保留桶与历史保留期**：维护逻辑修复。
- **2.9 P1-04 解锁失败节流与失败态清零**：节流+fail-clean。
- **2.10 P1-06 同步凭据认证绑定与 S3 密钥治理**：凭据绑定+字节化。
- **2.11 P2-14 Argon2 原生内核 C→Rust 迁移**：见 §8（AES-KDF/Twofish/口令强度同源扩展）。
- **2.12 P2-01 S3 AccessKey 去 String 留存**：`SettingsUiState` 字节化。
- **2.13 P2-02 Passkey 注册 DAL 远程资产声明校验**：签名域校验。
- **2.14 P2-03 App 14 例 Fake 自测消除**：改真实依赖。
- **2.15 P2-04 Sync/Merger 边缘分支单测补齐**。
- **2.16 P2-05 原子写盘降级 fsync 与 `.bak` 生命周期**：见 §3.13。
- **2.17 P2-06 锁定≠销毁：copy-on-write 定点擦除**。
- **2.18 P2-07/08/09 Autofill 信任边界与运行时完整性**：见 §3.12/3.14/3.15。
- **2.19 P2-10/13 明文导出治理与自动锁定语义修正**。
- **2.20 P2-12 OTP 种子与详情路径字节化**。
- **2.21 P2-15/16 受保护值字节通道收口与密码生成器 CharArray 化**。
- **2.22 P2 前九项批次验收**：**725 例 / 712 通过 / 0 失败 / 13 跳过**（净增 78 例，零退化）。本批次登记若干过程缺陷已如实留痕。

---

## 3. P3 批次整改归档（ISSUE-P3-01 ~ P3-16）

**范围**：16 项（低危加固/特性接线/体验优化）。**验收**：全模块 `test --rerun-tasks`。

### 3.1 验收证据
**908 通过 / 0 失败 / 13 跳过**（基线 737→921，+184 例）。crypto instrumented 7 例 0 失败（x86_64 模拟器，Argon2 派生 4.98×/8.42× 于 BC，R1 闸门通过）。

### 3.2 条目裁决（摘要）
| 条目 | 主题 | 裁决 |
|---|---|:---:|
| P3-01 | 生物识别第二次解锁不触发 | 达成（真机未验） |
| P3-02 | 自定义图标+字段引用展示 | 字段引用达成；图标部分（渲染需真机） |
| P3-03 | 进阶偏好消费方接线 | 43f 达成；43a/b 部分；43c/d/e 诚实化未生效 |
| P3-04 | 导入密钥与 KeyFile 管理 | 达成（SAF 未验） |
| P3-05 | zxing→CameraX 评估 | 纯评估：维持 zxing，条件触发迁移 |
| P3-06 | UI 冗余 import 清理 | 部分（保守保留） |
| P3-07 | 附件别名共享消除 | 达成 |
| P3-08 | 淘汰旧计划文件 | 达成（前提已不成立） |
| P3-09 | 供应链与构建加固 | 五达成一部分 |
| P3-10 | 解析与计数器边界加固 | 达成 |
| P3-11 | Argon2 真机 instrumented | 1/3 达成（arm64 未达） |
| P3-12 | 敏感屏遮挡触摸过滤 | 部分（真机未验） |
| P3-13 | Windows fsync 无运行时验证 | 达成（POSIX 待 Linux） |
| P3-14 | 生物识别完整性提示文案 | 达成 |
| P3-15 | 编辑页非法包名提示边界 | 达成 |
| P3-16 | 已下架文件悬空引用修正 | 达成 |

**过程缺陷**：本批次登记 13 项（生产缺陷 9 + 编排缺陷 4），含并行 OOM、并发构建截断、条目前提滞后，均已如实留痕。

---

## 4. P3 残余批次整改归档（ISSUE-P3-17 ~ P3-28）

**范围**：12 项，并行多代理团队。**结果**：8 项闭环 + 4 项（P3-20/23/24/25）部分达标。

### 4.1 验收证据
**1176 通过 / 0 失败 / 13 跳过**（基线 921→1189，+268 例）。`test --rerun-tasks --max-workers=1 --continue` BUILD SUCCESSFUL。

### 4.2 条目裁决（摘要）
| 条目 | 主题 | 裁决 |
|---|---|:---:|
| P3-17 | 43c UI 显示偏好接线（7 键） | 达成（真机未验） |
| P3-18 | 通知基础设施与 2 偏好 | 达成（设备未验） |
| P3-19 | 明文导入框架 + 4 源解析器 | 达成（真机未验） |
| P3-20 | 子库挂载 UI 接线 | 达成（SAF 未验） |
| P3-21 | 生成附属密钥文件假开关 | 达成 |
| P3-22 | 分组自定义图标渲染 | 达成 |
| P3-25 | 巨型类拆分（SyncCoordinator 等 3 文件） | 达成（均≤400 行） |
| P3-26 | `deleteBackup` 后 fsync | 达成 |
| P3-27 | 解压上限不自洽 + 签名计数器 | 达成（修正条目误读） |
| P3-28 | 待办条目附核实时间点 | 达成 |
| P3-23 | arm64 + 真实语料 | 部分（设备/语料不可达） |
| P3-24 | CI 首跑校准 | 部分（需真实 runner） |

### 4.3 残余与过程缺陷
- P3-23/P3-24 完整背景以 `ACTIVE_ISSUES.md` 为单一真相源。
- 本批次登记 15 项过程缺陷（含**全框架致命缺陷** `ImportTextDecoder` 误抛、KSP `import` 包名关键字冲突、拆分回归等），均已如实留痕。

---

## 5. ISSUE-P3-30 归档（子库条目只读投影接入库列表）

**范围**：P3-20 后续发现的过度声明（投影生产消费方为零）。把投影接入库列表，守边界：**只读、不并入根库条目流、不参与搜索与自动填充**。
**验收**：`test` 全绿（app 678 / 0 失败）。**过程缺陷** 4 项已留痕。

---

## 6. ISSUE-P3-29 归档（全仓超阈值债务 · 批次 A）

**范围**：优先级 8 项全部降至 400 行 + 增量 2 项 + `DicewareWordList` 纯常量例外。纯结构性拆分，零行为变更。
**验收**：**1200 例 / 0 失败 / 13 跳过**（与拆分前一致）。`SettingsViewModel 1120→397`、`VaultListViewModel 803→349`、`KdbxFile 614→382` 等；敏感数据清零点与公开 API 零丢失零新增。残余 23 项转入 ISSUE-P3-31。**过程缺陷** 8 项已留痕。

---

## 7. CI 首跑实测整改归档（lint / CodeQL / 供应链闸门）

**范围**：`Fast gate` 147 个 lint error 清零、CodeQL 10 条处置、实证 `dependency-scan` CVSS 阻断静默失效并补硬断言。
**验收**：CI 运行 `34470024328` 三 job 全绿、exit 0；**1200 例 / 0 失败 / 13 跳过**；5 模块 lint 0 error。
- **lint 三族**：`MissingTranslation`×144（补英文）、`RestrictedApi`×2（`SlicedContent` 必要压制）、`NewApi`×1（lint API 库误报，改为下标拷贝）。
- **CodeQL 10 条**：3 条 py 改结构性隔离（非 `str.replace` 净化）；7 条 rust 按 `used in tests` 处置。
- **供应链**：实证 `failBuildOnCVSS=7.0` 在 `dependencyCheckAggregate` 不生效，补 `.github/check_dependency_cvss.py` 硬断言（≥7.0 即失败、缺报告亦失败）。
- **PR #5（Actions 大版本升级）**：`a9b838f` 合并（checkout v7 / setup-java v6 / setup-gradle v6.3 / upload-artifact v7 / setup-android v4 / upload-sarif v4）。
- **供应链达阈处置（ISSUE-P3-32 主要面）**：`androidx.sqlite` 登记误报、`CVE-2026-53914` Kotlin 2.4.10→2.4.20 真修复、jline/protobuf 构建链豁免；处置后 188→7 实例、CVSS≥7.0 138→0。残余 7 条 MEDIUM 如实保留。
- **过程缺陷**：11 项已留痕（含 suppression 文件 schema 非法、CodeQL 净化误判等）。

---

## 8. 原生内核扩展与工程化批次归档（ISSUE-P3-34 ~ P3-38）

**范围**：5 项原生内核扩展，全部闭环。**验收**：`cargo test` 43 例；全模块 **1257 例 / 0 失败 / 13 跳过**；5 模块 lint 0 error；crypto 告警清零。

| 条目 | 主题 | 结论 |
|---|---|---|
| P3-34 | AES-KDF 原生内核（Rust） | 闭环；独立复算 KAT + JCE 差分等价；不宣称倍数 |
| P3-35 | Twofish 原生内核（CBC/PKCS7） | 闭环；官方 KAT + BC 差分等价 + 流式等价；实测 `CipherInputStream` 抛 IOException（修正旧文档误述） |
| P3-36 | 口令强度评估原生引擎 | 闭环；模式惩罚型（非 zxcvbn）；跨语言位值契约锁定；降级路径保留 |
| P3-37 | `HmacBlockStream` 摘要收敛 | 闭环；单一 `BlockHmac` 实现，不宣称提速 |
| P3-38 | `.kdbx` 语料生成脚本 | 闭环；`--check/--dry-run/--verify/--ingest` 五模式，fail-closed |

**未验证项**：Twofish/AES-KDF/口令强度设备侧 instrumented 未做（无设备，随 ISSUE-P3-23 补齐）；AES-KDF 性能倍数未实测；`keepassxc-cli` 未装，语料未产出。**过程缺陷** 9 项已留痕（含 `Pkcs7` 单分组契约生产缺陷、`source` 子串冒充漏洞等）。

---

## 9. 自动填充能力对标批次归档（ISSUE-P3-39 ~ P3-45）

**范围**：7 项对标 Monica 自动填充差距（非功能故障）。**验收**：app **712 例 / 0 失败**（+34）。

| 条目 | 主题 | 结论 |
|---|---|:---:|
| P3-39 | 字段识别与候选打分 | 闭环 |
| P3-40 | 手动选择器（全库搜索兜底） | 闭环 |
| P3-41 | 服务健康自检 | 闭环 |
| P3-42 | 填充侧会话授权宽限 | 闭环 |
| P3-43 | 三级黑名单 + 尊重 importantForAutofill | 部分（①闭环；②③闭环于 §11） |
| P3-44 | 保存体验评估+实现 | 闭环（发现并修复 `offerSaveCredentials` 假开关） |
| P3-45 | 结构化数据填充评估 | 闭环（结论：暂不实现） |

**过程缺陷**：5 项已留痕（含 2 处长期假开关 `overrideNoAutofill`/`offerSaveCredentials` 本批发现并接线）。**未验证项**：选择器/健康/宽限设备侧行为、CM 通道接线、打分召回效果待真机/A-B。

---

## 10. ISSUE-P3-31 批次 B 归档（超阈值债务 · `RealVaultRepository` / `DatabasePickerScreen`）

**范围**：纯结构性拆分，零行为变更。**验收**：**1291 例 / 0 失败 / 13 跳过**（逐模块与基线一致）。
- `RealVaultRepository 1090→372`（拆 6 单元）、`DatabasePickerScreen 968→319`（拆 3 单元）。
- 清零点 9→9、UI 4→4；公开 API diff 为空。残余超阈值 23→21 项。**过程缺陷** 3 项已留痕。

---

## 11. ISSUE-P3-31 批次 C + P3-43 闭环归档

**核实**：2026-09-10，`wc -l` 全仓复核；**1328 例 / 0 失败 / 13 跳过**；lint 5 模块 0 error。

| 条目 | 内容 | 结论 |
|---|---|:---:|
| P3-31 批次 C | `ThemeSettingsScreen 762→155` / `EntryEditScreen 712→388` / `EntryDetailViewModel 708→399` | 闭环；清单 21→18 项 |
| P3-43 ② | 字段签名级屏蔽（UI 入口+不可逆持久化+填充判定三件套） | 闭环 |
| P3-43 ③ | 保存侧独立黑名单（管理入口+持久化+`onSaveRequest` 判定三件套） | 闭环 |
| （过程发现） | `passwordStrengthBits` 无写入方→强度条恒不渲染 | 顺带修复；抗枚举加固登记 ISSUE-P3-46（已于 §12 闭环） |

- 清零点 9→9（`EntryDetailSecrets.clearAll()` 单点收口）；公开 API 零丢失零新增。+37 例全为本批新增单测。
- **过程缺陷** 6 项已留痕（含 `passwordStrengthBits` 注释与事实相反的事实修正）。

---

## 12. ISSUE-P3-31 批次 D + ISSUE-P3-46 闭环归档

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；较批次 C 基线 1328 净增 1 例，为 P3-46 新增仓库层单测）；`lint` 5 模块 **0 error**。

| 条目 | 内容 | 结论 |
|---|---|:---:|
| P3-31 批次 D | `DatabaseSession 697→393`（拆 6 协作单元）/ `SecuritySettingsScreen 674→371`（拆 2 文件）/ `KeePasskeyAutofillService 634→334`（拆 2 文件） | 闭环；清单 18→15 项 |
| P3-46 | 字段签名密钥来源 `SHA-256(随机盐)` → `HMAC-SHA256(Keystore 不可导出密钥)`，schema `v1→v2` | 闭环 |

### 12.1 批次 D（纯结构性拆分，零行为变更）

- **公开 API 零丢失零新增**；`lint` 0 error；用例数不减（+1 来自 P3-46）。
- `database` 模块新增 internal 协作类并归档：`SessionCore`（状态容器）/ `SessionCredentialCache`（凭据缓存，清零点逐处对齐，`synchronized` 语义不变）/ `SessionFileWriter`（原子写盘 + 滚动备份）/ `SessionTreeEditor`（copy-on-write 树变换）/ `SessionContentMutations`（条目/分组增删改与批量操作）/ `SessionOpener`（create/open/openStream）。`DatabaseSession` 收敛为门面，状态迁移、`clearSupersededSensitiveData` 调用点、`serialized.fill(0)`、调度器与异常文案均原样保留。
- `app` 模块：`SecuritySettingsScreen` 拆出 `SecuritySettingsComponents.kt`（行组件 / 完整性风险卡 / 文案映射）与 `SecuritySettingsDialogs.kt`（三个超时/风险弹窗）；`KeePasskeyAutofillService` 拆出 `AutofillStructureScan.kt`（AssistStructure 遍历 + `ParsedAutofillNode` + `ScannedStructure`）与 `AutofillDatasetBuilders.kt`（解锁引导 / 候选 / 选择器 / SaveInfo 数据集构建，同包 `internal` 扩展函数）。服务类 companion 常量可见性 `private → internal`（数值逐字不变，无字面量双份）。
- **过程缺陷（如实留痕）**：
  1. `AutofillDatasetBuilders.appendUnlockedDatasets` 首版写为普通函数，调用 `resolveUsableWebDomain`/`getKdbxEntries`/`resolveFieldReferences` 三个 suspend 函数 → 编译报 `ILLEGAL_SUSPEND_FUNCTION_CALL`；已改为 `suspend fun` 后通过。
  2. 强制全量重跑首轮 `SyncCacheEvictorTest.关闭密码库后同步缓存目录为空` 偶发失败：残留 `*.BASEVERSION.tmp`（Windows 下 `File.delete()` 被临时占用静默失败，`SyncCache.updateBase` 的 tmp 清理竞态，**与本批次改动无关**）；隔离重跑 **3/3 通过**、全量重跑转绿。

### 12.2 ISSUE-P3-46（字段签名抗枚举加固）

- 新增 `HmacFieldSignatureSource`（`fun interface`，只出 MAC 不入密钥）+ 生产实现 `KeystoreHmacFieldSignatureSource`（`AndroidKeyStore` 内 `HmacSHA256` 密钥，密钥不可导出；`setUserAuthenticationRequired(false)`，仅用于不可逆字段签名，不承载可解封密文），并由 `di/AutofillModule` 绑定。
- `AutofillFieldSignature.of(source, …)` 以 `SCHEMA_VERSION = "v2"` 参与原文（v1 = `SHA-256(salt‖…)`）；`AutofillFieldBlocklistStore` 注入密钥来源，**删除随机盐的全部落盘逻辑**（`field_signature_salt` 仅在迁移时删除），并以 `field_signature_schema` 标记做一次性**保守迁移**：清空旧签名 + 删除旧盐 → 旧记录等价于清空，不产生误命中。
- 单测：签名 9 例迁移至 HMAC（同一断言面 + 「密钥不可用 fail-closed」），仓库新增 1 例（密钥不可用时写入失败、判定 fail-closed）。
- KDoc 与本文件安全边界声明同步更新：不再宣称 SHA-256+随机盐「抗枚举」。

**未验证项（如实，不以静态结论冒充实测）**：验收标准 1 中「Keystore 密钥不可导出」属**设备侧性质**——本环境 `adb devices` 为空、无 arm64 镜像，无法运行 instrumented 断言；本批以**设计保证 + 代码事实**留痕（密钥生成于 `AndroidKeyStore`、全程不调用 `getEncoded()`、不落盘任何密钥材料），设备侧断言待真机可用时补（登记于 ISSUE-P3-23 同一外部设备缺口）。

---

## 13. ISSUE-P3-31 批次 E 归档（超阈值债务 · `S3SyncProvider` / `PasskeyCryptoEngine` / `KdbxMerger`）

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；纯结构性拆分，用例数与批次 D 持平）；`lint` 5 模块 **0 error**。残余真逻辑超阈值清单 15 → **12 项**。

| 文件 | 前 → 后 | 拆出单元 |
|---|---|---|
| `sync/.../s3/S3SyncProvider.kt` | 629 → 372 | `S3RequestSigner`（SigV4 签名）/ `S3ClockSkewGuard`（时钟偏移守卫）/ `S3KeyCodec`（对象键编码）/ `S3HttpDateCodec`（RFC1123 解析） |
| `crypto/.../passkey/PasskeyCryptoEngine.kt` | 596 → 362 | `PasskeyKeyCodec`（密钥编解码）/ `PasskeyAssertionSigner`（ES256 / Ed25519 / RS256 断言签名） |
| `sync/.../merge/KdbxMerger.kt` | 578 → 207 | `KdbxGroupMerger` / `KdbxEntryMerger` / `KdbxTombstoneMerger` |

- **公开 API 零丢失零新增**：新增单元一律 `internal`；`S3SyncProvider` 的 `SyncProvider` 实现（`testConnection`/`getMetadata`/`download`/`upload`/`delete`）、`encodePath`/`signV4`/`EMPTY_SHA256`、`KdbxMerger` 的 `mergeDatabases`/`isEntryModified`/`resolveConflictByFields`/`resolveConflict` 与全部结果类型（`MergeResult`/`ConflictedEntryPair`/`KdbxDatabaseLite`/`ConflictResolutionChoice`）签名逐字未变；`PasskeyCryptoEngine` 仍为 `object`，`FLAG_*` 位值、`EC_SCALAR_HEX_CHARS`、`DEFAULT_AAGUID` 数值不变。
- **敏感数据清零点逐处对照**：`PasskeyCryptoEngine`+`PasskeyKeyCodec` **6 → 6**；`S3SyncProvider`→`S3RequestSigner` **13 → 13**（含 `canonicalRequestBytes`/`signingKey`/`kSecret`/`kDate`/`kRegion`/`kService` 等）；合并链原本无清零点（**0 → 0**）。全部 `try/finally` 结构与清零条件原样保留，未新增 `String` 落地敏感值或日志；SigV4 凭据仍以**引用借用**持有，`clearCredentials()` 即时生效语义不变。
- **过程缺陷 / 事实修正（如实留痕）**：
  1. 委派子代理执行 S3 拆分时**误判**需新建 `S3XmlParser`；复核发现该 provider 不含任何 XML 解析逻辑（错误全部由 HTTP 状态码/头映射为 `SyncException`），已**不创建无消费方的空壳单元**。
  2. 子代理报告本环境 `GetDiagnostics` 对**故意注入的类型错误**亦返回空诊断（诊断通道不可靠）；本批遂以主流程 `gradlew test`/`lint` 实跑为唯一验收依据，不采信静态诊断结论。
  3. 复核中发现 `PasskeyCryptoEngine.sealedFromPrivateChars` 的 `Arrays.fill(chars, '0')` 为原文既有写法（以 `'0'` 覆盖明文，而非 `'\u0000'`）；该写法已足以覆盖明文，按「零行为变更」**未改**，如需统一为 NUL 应另开条目评审。

---

## 14. ISSUE-P3-31 批次 F 归档（超阈值债务 · `SettingsScreen` / `UnlockScreen` / `GeneratorScreen`）

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；纯结构性拆分，用例数与批次 E 持平）；`lint` 5 模块 **0 error**。残余真逻辑超阈值清单 12 → **9 项**。

| 文件 | 前 → 后 | 拆出单元 |
|---|---|---|
| `app/.../ui/screens/settings/SettingsScreen.kt` | 569 → 306 | `SettingsComponents`（分组卡 / 分隔线 / 设置行 / 分区标题）/ `MasterKeyChangeDialog`（改主密码对话框，含擦除语义） |
| `app/.../ui/screens/unlock/UnlockScreen.kt` | 562 → 275 | `UnlockContentSections`（`UnlockVaultLogo` / `UnlockEmptyVaultContent` / `UnlockQuickUnlockCard` / `UnlockStandardUnlockContent`） |
| `app/.../ui/screens/generator/GeneratorScreen.kt` | 550 → 177 | `GeneratorDisplayCard` / `GeneratorModeOptions`（三模式参数 + `OptionSwitchRow`）/ `HistoryPasswordRow` |

- **公开 API 零丢失零新增**：`SettingsScreen` / `UnlockScreen`（含 `UnlockContent`）/ `GeneratorScreen` 的 `@Composable` 签名、参数顺序与默认值、KDoc 逐字未变；被抽组件均由 `private` 改为同包 `internal`，不进入公开面。UI 文案、布局参数、状态读取、事件回调、条件分支与导航意图逐字保留。
- **敏感数据相关逻辑逐字保留**：改主密码对话框的 `pwdChars.fill('0')` / `wipeDialogPasswords` 与「确认/取消/点外部→擦除」语义；解锁页 `onPasswordChange: (CharArray) -> Unit` 链路、`SecurePasswordField` 全部参数（含 `wipeToken`）、密钥文件「关闭即擦除字节」语义；生成器 `currentPassword.readString()`、`copyGeneratedPassword` 剪贴板路径与 `P0 整改`/`ISSUE-P2-12`/`ISSUE-P2-16` 注释。均未新增 `String` 落地敏感值或日志。
- **过程事实（如实留痕）**：本批三个子代理均报告**本环境 `GetDiagnostics` 诊断通道不可靠**（对故意注入的未解析符号亦返回空诊断，探针文件已删除）；故一律以主流程 `gradlew test`/`lint` 实跑为唯一验收依据，不采信静态诊断结论。子代理改用「逐符号人工核对（跨文件符号同包可见、import 全部被使用、无同名重定义）」作为自检补充。

---

## 15. ISSUE-P3-31 批次 G 归档（超阈值债务 · `WebDavSyncProvider` / `AutofillSettingsScreen` / `EntryEditComponents`）

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；纯结构性拆分，用例数与批次 F 持平）；`lint` 5 模块 **0 error**。残余真逻辑超阈值清单 9 → **6 项**。

| 文件 | 前 → 后 | 拆出单元 |
|---|---|---|
| `sync/.../webdav/WebDavSyncProvider.kt` | 510 → 345 | `WebDavAuthHeader`（Basic 认证头，敏感清零唯一落点）/ `WebDavPropfindParser`（multistatus + XXE 守卫 + HTTP 日期）/ `WebDavUrlCodec`（路径编码 / URL 构造 / ETag 头格式化） |
| `app/.../settings/subscreens/AutofillSettingsScreen.kt` | 504 → 216 | `AutofillSettingsComponents`（4 个分区卡 + `AutofillInfoRow` / `AutofillSwitchRow`） |
| `app/.../ui/screens/edit/EntryEditComponents.kt` | 494 → 296 | `EntryEditListSections`（自定义字段分节 / 附件分节） |

- **公开 API 零丢失零新增**：`WebDavSyncProvider` 构造函数与 `SyncProvider` 六个方法（`testConnection`/`getMetadata`/`download`/`upload`/`uploadAtomic`/`delete`）、`ATOMIC_TMP_SUFFIX` 逐字未变；`AutofillSettingsScreen` 签名（29 参数）、`EntryEditComponents` 内各 `internal` @Composable 签名与可见性逐字未变（搬移符号仍 `internal`，外部引用方 `EntryEditScreen` 同包可见）。新增单元一律 `internal`，不进入公开面。
- **协议/安全语义逐字保留**：WebDAV 的 `PROPFIND`+`Depth: 0`、`PUT`/`MOVE`（`Destination`/`Overwrite`）、RFC 4918 tagged-list `If:`、`If-Match`/ETag、XXE 四项 feature、状态码映射（401/403→`AuthenticationError`、404、412→`ConflictError`、其余→`ProtocolError`）原样；自动填充三级黑名单对话框（包级 / 保存侧 / 字段签名计数）与确认语义原样；条目编辑侧 `SecurePasswordField` 的 `CharArray` 链路与 `initialPassword`/`initialKey` 原样。
- **敏感数据清零点**：WebDAV 认证头 **3 → 3**（`passwordChars.fill('0')`、`combined.fill(0)`、`passwordBuffer.array().fill(0)`，`try/finally` 与 `hasArray()` 条件不变）；其余两文件不承载清零点（**0 → 0**）。未新增 `String` 落地敏感值或日志。
- **过程事实（如实留痕）**：本批子代理同样报告本环境 `GetDiagnostics` 通道不可靠；一律以主流程 `gradlew test`/`lint` 实跑为唯一验收依据，并补做「逐符号人工核对（可见性满足引用方、import 全部被使用）」。

---

## 16. ISSUE-P3-31 批次 H 归档（超阈值债务 · `CloudSyncComponents` / `EntryDetailComponents` / `EntryEditViewModel`）

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；纯结构性拆分，用例数与批次 G 持平）；`lint` 5 模块 **0 error**。残余真逻辑超阈值清单 6 → **3 项**。

| 文件 | 前 → 后 | 拆出单元 |
|---|---|---|
| `app/.../settings/subscreens/CloudSyncComponents.kt` | 481 → 291 | `CloudSyncConfigFields`（WebDAV / S3 凭据输入表单字段） |
| `app/.../ui/screens/detail/EntryDetailComponents.kt` | 477 → 223 | `EntryDetailCards`（`BasicCredentialsCard` / `TotpCard` / `PasskeyCard` + `TOTP_MASK`） |
| `app/.../ui/screens/edit/EntryEditViewModel.kt` | 470 → 400 | `EntryEditFormProjection`（加载投影 / 字段与附件映射 / 口令生成）/ `EntryEditSaveProjection`（保存校验 / 标签解析 / 保存快照组装） |

- **公开 API 零丢失零新增**：`EntryEditViewModel` 全部 public 成员（构造、`uiState`/`events`/`loadedPassword`/`loadedTotpSecret`/`loadedProtectedFields`/`init`/31 个事件入口/`onCleared`）逐字未变；Compose 侧被搬移符号维持 `internal`（引用方仅 `EntryDetailScreen`/`CloudSyncScreen`/`CloudSyncSections`，同包可见），**未下调任何可见性**；新增单元一律 `internal`。
- **敏感数据清零点**：`EntryEditViewModel` **26 → 26**（24 × `.fill('0')` + 2 × `.clear()`，逐条映射位置与条件不变）；密码/TOTP 揭示、`CharArray` 预填、剪贴板敏感标志等逻辑逐字保留。未新增 `String` 落地敏感值或日志；`EntryEditSaveProjection` 的快照组装**不含任何敏感明文**。
- **过程事实（如实留痕）**：本批子代理同样报告本环境 `GetDiagnostics` 通道不可靠；一律以主流程 `gradlew test`/`lint` 实跑为唯一验收依据，并补做「逐符号人工核对（可见性满足引用方、import 全部被使用、公开成员逐条比对）」。

---

> **当前残余面（ACTIVE）**：ISSUE-P3-23（arm64 真机 + 真实 `.kdbx` 语料端到端）· P3-24（CI 首跑校准）· P3-31（超阈值 3 项）· P3-32（供应链 CVE 收尾）。
