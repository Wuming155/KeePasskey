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
| §17 | P3-31 批次 I（超阈值债务 · **本条闭环**） | ISSUE-P3-31 |
| §18 | CI Fast gate 偶发红根因修复（测试调度器污染） | 测试基础设施 |
| §19 | dependency-scan CI 侧首跑留痕 + 断言可观测性修复 | ISSUE-P3-24 / P3-32 |
| §20 | 功能完整性审计批次 A（全文搜索范围 / 详情页单条删除） | ISSUE-P3-47 / P3-48 |
| §21 | 功能完整性审计批次 B（HOTP 端到端 / 便利入口 / 孤儿实现清理） | ISSUE-P3-49 / P3-50 / P3-51 |
| §22 | 存量问题由易到难整改闭环批次（P1-11 / P2-17 / P2-18 / P3-52~P3-56） | ISSUE-P1-11 / P2-17 / P2-18 / P3-52 ~ P3-56 |
| §23 | CI 侧真实跑通归档 + CodeQL Rust 误报治理（P3-24 / P3-32 / P3-57 闭环） | ISSUE-P3-24 / P3-32 / P3-57 |
| §24 | 设备侧实测发现的致命缺陷修复（Android 端 KDBX XML 解析全量失败） | ISSUE-P1-12 |
| §25 | 设备侧互操作语料入库与端到端解锁跑绿（真实 KeePassXC `.kdbx`） | ISSUE-P3-23 |
| §26 | 设备侧手工实操发现的 P0 崩溃修复（字段引用正则在 Android ICU 上非法） | ISSUE-P0-04 |
| §27 | 设备侧功能实测收口 + 9 项缺陷整改（2026-09-11） | 见 §27 |
| §28 | 存量问题修复批次（快捷脱敏 / 关闭校验 / 外部存储清理） | ISSUE-P2-22 / P3-63 / P3-65 / P3-67 |
| §29 | 用户报告修复批次（复合封印指纹解锁 / 重试节流可配置 / FLAG_SECURE 语义修订） | ISSUE-P2-23 / P3-68 |
| §30 | 外部安全审计核实与整改批次（Wrapper 哈希 / 字节清零 / 扫码防截屏 / 许可证注释） | ISSUE-P3-69 ~ P3-72 |
| §31 | 文档类存量整改批次（隐私政策 / 同步层威胁建模） | ISSUE-P3-75 / P3-77 |
| §32 | 存量功能整改批次（CSV 导入 / 导出扩充） | ISSUE-P3-73 |
| §33 | 产品裁决：不排期 / Won't Do（对标项与外部依赖项） | ISSUE-P2-25 / P2-26 / P2-27 / P3-23 / P3-58 / P3-66 / P3-74 |

> 各批次验收证据（用例数 / 通过 / 失败 / 跳过）分别见 §2.22、§3.1、§4.1、§5、§6、§7、§8、§9、§10、§11、§12、§13、§14、§15、§16、§17、§18、§19、§20.3、§21.4、§22.9、§23.1、§24.3、§25.2、§26.3。

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

> 全部项已修复。下列为各专项审计的范围与结论；详见当前代码与本文件对应章节。

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
- P3-23 完整背景以 `ACTIVE_ISSUES.md` 为单一真相源；P3-24 已于 2026-09-11 闭环归档（见 §23）。
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
  > **2026-09-11 更正（滞后前提）**：上句「开放告警归零」在写下时属实；但同日 `13:18:38Z` 的 GitHub 侧
  > 默认设置分析又新产出 **77 条** `rust/hard-coded-cryptographic-value`（critical，全部落在 Rust 单测模块内），
  > 故「CodeQL 开放告警 = 0」**已不再成立**，另立 **ISSUE-P3-57** 跟踪，见 §23.4。
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

## 17. ISSUE-P3-31 批次 I 归档（**本条闭环** · `KeystoreManager` / `HealthCheckScreen` / `SyncEngine`）

**核实**：2026-09-10，逐文件 `(Get-Content …).Count` 复核；门禁 `test --rerun-tasks --max-workers=1` → **1329 例 / 0 失败 / 13 跳过**（app 750 / core 58 / crypto 107 / database 235 / sync 179；纯结构性拆分，用例数与批次 H 持平）；`lint` 5 模块 **0 error**。**闭环判据**：全仓重测 `> 400` 者仅剩 **2 项**，且均为**经论证的例外**，真逻辑超阈值债务归零。

| 文件 | 前 → 后 | 拆出单元 |
|---|---|---|
| `app/.../security/KeystoreManager.kt` | 462 → 239 | `KeystoreKeyMaterial`（AES / 设备凭据封印 / 解锁通行密钥 + 完整性 HMAC 的全部实现、spec 构造、`KeyInfo` 探测与 StrongBox 回退） |
| `app/.../settings/subscreens/HealthCheckScreen.kt` | 453 → 315 | `HealthCheckComponents`（`LeakRowPresentation` / `BreachCheckToggleRow` / `HealthAuditRowItem`） |
| `sync/.../engine/SyncEngine.kt` | 443 → 316 | `SyncEngineResults`（3 个 sealed 结果类型同包平移）/ `SyncEngineSupport`（`RemoteConsistencyProbe` 版本比较 + `advanceBaseAndPersist` 基线前移） |

- **公开 API 零丢失零新增**：`KeystoreManager` 全部 public 成员与 companion 常量、`SyncEngine` 全部 public 成员、`HealthCheckScreen` 签名逐字未变；`SyncEngineResults` 三个 sealed 类型保持 public（仅同包平移，FQN 不变），其余新增单元一律 `internal`。
- **密码学 / 协议语义逐字保留**：`KeystoreManager` 的密钥别名、`KeyGenParameterSpec` 各项（GCM/NoPadding/256/认证绑定/StrongBox/`UnlockedDeviceRequired`/`setUserAuthenticationParameters`）、`StrongBoxUnavailableException` 回退、`KeyPermanentlyInvalidatedException` 清理、`REQUIRED_AUTHENTICATOR_TYPES` 全等探测迁移均原样；`@Synchronized` 锁语义经 `onDeleteKey` 回调逐字保持（无反向锁序）。`SyncEngine` 的缓存写序（`writeCache` → `updateBase` → `writeBaseContent`）、ETag/内容哈希裁决、冲突与错误返回路径一一对应。
- **敏感数据清零点**：本轮三文件 **0 → 0**（`SyncEngine` 原文无清零调用；`KeystoreManager`/`HealthCheckScreen` 不承载明文清零点）；未新增 `String` 落地敏感值或日志。

### 17.1 ISSUE-P3-31 全周期闭环总览（批次 B ~ I）

| 批次 | 归档 | 处理文件（前 → 后） |
|:---:|---|---|
| B | §10 | `RealVaultRepository` 1090→372 · `DatabasePickerScreen` 968→319 |
| C | §11 | `ThemeSettingsScreen` 762→155 · `EntryEditScreen` 712→388 · `EntryDetailViewModel` 708→399 |
| D | §12 | `DatabaseSession` 697→393 · `SecuritySettingsScreen` 674→371 · `KeePasskeyAutofillService` 634→334 |
| E | §13 | `S3SyncProvider` 629→372 · `PasskeyCryptoEngine` 596→362 · `KdbxMerger` 578→207 |
| F | §14 | `SettingsScreen` 569→306 · `UnlockScreen` 562→275 · `GeneratorScreen` 550→177 |
| G | §15 | `WebDavSyncProvider` 510→345 · `AutofillSettingsScreen` 504→216 · `EntryEditComponents` 494→296 |
| H | §16 | `CloudSyncComponents` 481→291 · `EntryDetailComponents` 477→223 · `EntryEditViewModel` 470→400 |
| I | §17 | `KeystoreManager` 462→239 · `HealthCheckScreen` 453→315 · `SyncEngine` 443→316 |

- 残余真逻辑超阈值清单演进：**23**（批次 A 后）→ 18 → 15 → 12 → 9 → 6 → 3 → **0**。
- **保留的两项经论证例外（不再列入债务）**：`DicewareWordList.kt`（408，约 300 行为不可压缩词表常量）、
  `SettingsViewModel.kt`（424，系 ISSUE-P3-43 判定与接线导致的功能性增量，非拆分遗漏）。
- **过程事实（如实留痕）**：批次 D~I 全部委派子代理执行机械拆分，子代理**一致报告本环境 `GetDiagnostics` 诊断通道不可靠**（对故意注入的未解析符号亦返回空诊断）；故所有批次**一律以主流程 `gradlew test`/`lint` 实跑为唯一验收依据**，不采信静态诊断结论，并要求子代理以「逐符号人工核对」自检。批次 D 曾出现一处 `suspend` 误标（`ILLEGAL_SUSPEND_FUNCTION_CALL`）已修复；`SyncCacheEvictorTest` 在 Windows 上存在 `.tmp` 清理竞态偶发（与本系列改动无关，隔离重跑稳定通过）。

---

## 18. CI Fast gate 偶发红根因修复（测试调度器跨用例污染）

**背景（外部权威证据）**：批次 H / I 推送后，GitHub 托管 runner 的 `build` 工作流 **Fast gate 转红**
（核实方式：`gh run list` + `gh run view <id> --job <id> --log` + `gh run download <id> -n fast-gate-reports`），
而同一提交在本地 `test --rerun-tasks`（含 `--max-workers=1` 与默认并发）**均全绿**——典型「CI-only 偶发」。

**失败形态**：`Fast gate → 单元测试（全模块）` 报
`EntryDetailViewModelTest > …` / `EntryEditViewModelTest > 新建条目保存成功并发出 SaveSuccess 事件` FAILED
（批次 H：2 例；批次 I：1 例，同为 `EntryEditViewModelTest`）。工件 HTML 显示失败类型为
`kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`，其 `Caused by` 为
`IllegalStateException: Module with the Main dispatcher had failed to initialize`，
抛出线程为 `DefaultDispatcher-worker-*`。

**根因（逐层证据）**：`EntryDetailViewModel` 的 `uiState` 上游经 `flowOn(displayDispatcher)` 装配，
而 `displayDispatcher` 默认 `Dispatchers.Default`（真实线程池）。
`EntryDetailViewModelTest` 是详情页三组用例中**唯一未注入** `@EntryDisplayDispatcher` 测试调度器的一个
（`EntryDetailDisplayPreferencesTest` / `CustomIconDeleteTest` 均已注入）。
于是真实 Default 线程上的在途工作会在用例结束、`@After` 执行 `Dispatchers.resetMain()` **之后**才回跳
已缺失的 Main → 抛异常并被协程测试记到「用例开始前已有未捕获异常」，**污染同一 JVM 中后续用例**
（表现位置随执行顺序漂移，故呈现偶发与跨类）。

**修复（生产代码零改动）**：
1. `EntryDetailViewModelTest.createViewModel` 与同目录其余用例对齐，注入 `displayDispatcher = UnconfinedTestDispatcher(testScheduler)`；
2. 同类隐患一并加固：`VaultListViewModelTest` 全部 8 处构造注入 `displayDispatcher`（其 ViewModel 同样以 `flowOn(displayDispatcher)` 装配）；
   `AuthenticatorViewModelTest`（其 ViewModel 无调度器注入点、上游硬编码 `flowOn(Dispatchers.Default)`）改为在 `@After`
   **先取消各 ViewModel 作用域、再 `resetMain()`**，终止真实线程上的在途回跳。

**验收证据**：修复后 **连续三次** CI `build` 运行全部 **三 job success**——
`34552887844`（提交 `6b09b6f`）、`34554214053`（提交 `bc23cc8`）、`34555156422`（提交 `9a0595f`），
每次均为 `Fast gate` ✓ / `Native gate` ✓ / `Rust supply chain` ✓，CodeQL 同期三次 ✓；
本地 `:app:testDebugUnitTest --rerun-tasks` 全绿。
另：硬断言 `.github/check_dependency_cvss.py` 的 fail-closed 语义已本地逐例实测（见 §7 与 §23.3）。

**如实留痕**：该 flake 自批次 G 起即存在（G 恰好通过、H/I 命中），属**既有测试基础设施缺陷**，
非批次 D~I 的结构性拆分引入；本地无法复现（时序/核数相关），完全依赖 CI 日志与工件定位。

---

## 19. dependency-scan 的 CI 侧首次真实运行留痕与断言可观测性修复（ISSUE-P3-24 / P3-32）

**前提纠正（原文已不成立，就地修正）**：此前 `ACTIVE_ISSUES` 记载「`workflow_dispatch` 需 PAT 具备 Actions 写权限，
本环境被拒（403），故 CI 侧从未运行」。经 `gh run list --workflow dependency-scan.yml` 复核（2026-09-11）：
**该工作流确实已在托管 runner 上运行过** —— 运行 `34477320673`（`workflow_dispatch`，headSha `8131dcf`，
2026-09-10T12:32Z，`OWASP Dependency-Check` job 56m15s）→ **conclusion=failure**。

**该次运行的真实失败链（逐层证据：`gh run view 34477320673` / `--log-failed`）**：

| # | 步骤 | 结果 | 说明 |
|:--:|---|:--:|---|
| 1 | `Run OWASP Dependency-Check (aggregate)` | ❌ | NVD API 连续 31 次重试后 `NvdApiException: NVD Returned Status Code: 503`；插件提示 “Unable to update 1 or more Cached Web DataSource, using local data instead”；随后 `dependencyCheckAggregate FAILED`（BUILD FAILED in 55m 38s） |
| 2 | （无报告产出） | — | 因 init 脚本 `failOnError = true`（fail-closed 设计），扫描未完成即失败；`build/reports/dependency-check/dependency-check-report.*` 不存在 |
| 3 | `CVSS 阈值硬断言（fail-closed）` | ⏭ **skip** | GitHub Actions 默认「前一步失败即跳过后续步骤」→ **断言自身无判定** |
| 4 | `Upload report artifact` | ❌ | `if-no-files-found: error` → “No files were found …” |
| 5 | `Upload SARIF to Code Scanning` | ❌ | “Path does not exist: …dependency-check-report.sarif” |

**结论（如实）**：失败根因是**外部 NVD 数据源 503（可用性）**，非本仓代码 / 配置缺陷
（init 脚本的 `formats`/`outputDirectory` 与 workflow 期望路径一致：`build/reports/dependency-check/dependency-check-report.{json,sarif,html}`）。
但暴露出**一处可观测性缺陷**：硬断言被跳过，导致 P3-32 验收标准 1「在真实运行中给出确定的 pass/fail」**无法达成**。

**修复（不削弱 fail-closed）**：为硬断言步骤加 `if: always()`。
于是即使上游 aggregate 失败、报告缺失，本步仍执行；`check_dependency_cvss.py` 对「报告不存在」按 fail-closed
返回 **exit 1**（该路径已本地逐例实测），从而**总是给出明确判定**。
该改动只可能**增加**失败信号，不会让任何「达阈」或「无报告」情形变绿；YAML 已用 `yaml.safe_load` 校验通过。

**仍未消除（外部资源，登记为残余）**：NVD 数据源 503 间歇性会导致 aggregate 步骤失败。
按 §7 既定立场，**不得**回调阈值或关闭 `failOnError` 换取变绿；正确处置为配置仓库 Secret `NVD_API_KEY`
（提升限额、绕开匿名限流）或增强 NVD 数据缓存（跨运行 cache）。属维护者决策，不由本批次单方面压制。

---

> **当前残余面（ACTIVE）**：ISSUE-P3-23（arm64 真机 + 真实 `.kdbx` 语料端到端）。
> ~~P3-24（CI 首跑校准）· P3-32（供应链 CVE 收尾）· P3-57（CodeQL Rust 单测误报）~~ → **已于 2026-09-11 闭环，见 §23**。

---

## 20. 功能完整性审计批次 A 归档（全文搜索范围扩展 / 详情页单条删除）

**发现方式（2026-09-11）**：以「README 声称功能 → 引擎/仓库 → ViewModel/控制器 → UI 入口」四层逐项做
**只读**端到端接线审计（全仓 `app` / `core` / `crypto` / `database` / `sync` 五模块 `src/main`），
发现两处 **README 契约与实现不一致**的真实缺口，同日整改并归档。同批就地登记于 `ACTIVE_ISSUES.md`
的 P3-49 ~ P3-51 亦已于同日整改归档，见 §21。

### 20.1 ISSUE-P3-47：全文搜索范围收窄（对照 README「全文搜索」）

- **整改前**：`VaultListProjection` 的条目过滤仅匹配 `title / username / url`（分组另按名称匹配）；
  备注 / 标签 / 自定义字段一律不参与，与 README「全功能：… 全文搜索」的声称不符。
- **整改**：把内联谓词抽出为内部纯函数 `matchesSearchQuery(entry, query)`，命中范围扩展为
  **标题 / 用户名 / URL / 备注 / 标签 / 自定义字段的键与「非受保护」值**；
  受保护字段（`isProtected=true`）明文不进投影（`UiCustomField.value` 恒为空串），故**天然不参与命中**，
  避免经搜索侧信道泄露机密；字段**键**属元数据（如 `TOTP Seed`）仍可命中。
- **证据**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListProjection.kt`
  （新增 `matchesSearchQuery`，过滤调用点改为该函数）；
  新增单测 `app/src/test/java/com/keepasskey/app/ui/screens/vault/VaultListProjectionSearchTest.kt`（7 例：
  空查询 / 标题用户名 URL 大小写不敏感 / 备注 / 标签 / 自定义字段键与值 / 受保护值排除但键可命中 / 无命中）。

### 20.2 ISSUE-P3-48：详情页缺单条「移入回收站」入口

- **整改前**：`EntryDetailTopBar` 的溢出菜单**仅在**「条目绑定了自定义图标」时出现，且只含「删除自定义图标」一项；
  单条条目删除只能通过列表长按进入批量模式再删。
- **整改**：
  1. 溢出菜单改为**非只读会话恒呈现**，首项为「移入回收站」；绑定自定义图标时追加「删除自定义图标」项（原逻辑保留）；
  2. 新增确认弹窗；`EntryDetailViewModel.deleteEntry()` 复用仓库 `deleteEntry` 的**回收站分流语义**
     （不在回收站内 → 软删移入回收站可还原；已在站内或回收站被禁用 → 物理删除 + 墓碑）；
     只读会话 / 无条目 id 为 no-op，失败经 `vault_op_failed` 如实上浮（不谎报成功）；
  3. 成功经一次性 `entryDeleted` 信号回退导航（条目已不在库中，停留会呈现「条目不存在」）；
  4. 新增中英文案 `cd_more_actions` / `detail_delete_entry` / `detail_delete_entry_title` / `detail_delete_entry_message`。
- **证据**：`EntryDetailTopBar.kt` / `EntryDetailScreen.kt` / `EntryDetailViewModel.kt` +
  `res/values/strings.xml`、`res/values-en/strings.xml`。

### 20.3 验收证据（2026-09-11，`--rerun-tasks` 强制真实执行）

`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**（114 任务全部 `executed`），
聚合全部 `build/test-results/**/TEST-*.xml` 得 **1336 例 / 0 失败 / 13 跳过**
（较批次 I 的 1329 基线净增 7 例，即 20.1 新增用例；两处整改均为**纯增量**，不删改既有断言）。

### 20.4 过程留痕（如实）

- 本批次一次审计子代理误判「`AttachmentManager` 属孤儿实现」——经复核**属实**（`src/main` 无消费方，
  仅其单测引用），但该判定**只是静态接线结论，不代表该能力构成用户可见缺口**（附件查看/导出另走
  SAF 直写 + 隔离预览链路），故**未**据此改动代码，仅登记为待办（P3-50）。
- 审计同时发现若干「README 未列举、但为体验便利」的缺口（单条移动到分组、新建条目套用模板），
  两者均可经既有流程达成（批量移动 / 克隆模板条目），故**降级为 P3 便利项**登记，本批次不改代码。

---

## 21. 功能完整性审计批次 B 归档（HOTP 端到端 / 便利入口 / 孤儿实现清理）

**范围**：批次 A 审计就地登记于 `ACTIVE_ISSUES.md` 的 **P3-49 ~ P3-51** 三项，同日整改并归档。

### 21.1 ISSUE-P3-49：HOTP（RFC 4226）端到端接线（对照 README「TOTP·HOTP」）

- **整改前**：应用层仅支持 TOTP——`OtpEngine.calculateHotp` 无生产消费方；`TotpKeyUriParser` **丢弃**
  `otpauth://hotp/` 的类型段与 `counter` 参数（`ParsedTotpConfig` 无对应字段），`VaultEntryMapper` 恒走 `calculateTotp`。
- **整改**：
  1. **core**：`ParsedTotpConfig` 增 `isHotp` / `counter`（`equals`/`hashCode`/`toString` 同步）；
     `TotpKeyUriParser.parseOtpAuthUri` 解析类型段与 `counter`；新增 **`HotpCounterSupport`**
     （**字符语义**递增计数器：在 `CharArray` 上就地扫描/替换/追加，仅数值子串转 `Long`，
     种子不物化为 `String`、不做编码转换；非 `otpauth://` / 非纯数字 / 已达 `Long.MAX_VALUE` 一律 fail-closed 返回 null）。
  2. **app 数据层**：`UiVaultEntry.isHotp`；`VaultEntryMapper.computeTotpCode` 按 `isHotp` 分派 `calculateHotp`
     （投影/展示路径**不推进**计数器）；`EntryTotpSnapshot` 增 `isHotp` / `counter`；
     `VaultEntryWriteCoordinator.updateEntryOtpConfig`（仅改写 `otp` 字段，**不产生历史修订**——属口令取用而非内容修订）；
     `VaultRepository.advanceEntryHotpCounter`（严格顺序：读取配置 → 算当前码 → 计数器 +1 **落库成功** → 才返回该码；
     非 HOTP / 配置不可读 / 计数器非法一律 fail-closed，绝不交付「未推进」的码）。
  3. **UI**：详情页 TOTP 卡片对 HOTP 以「取下一个码」动作（推进计数器并复制，对齐 KeePassXC）替代倒计时环与
     「复制当前码」；本页倒计时节拍**跳过** HOTP（否则取码后 1 秒内会把显示覆盖为「下一码」而与刚交付的码不一致）；
     验证器页同样以「取下一个码」动作出码、隐藏倒计时环与紧迫着色。
- **证据**：`core/.../otp/TotpKeyUriParser.kt`、`core/.../otp/HotpCounterSupport.kt`（新增）、
  `app/.../ui/model/UiModels.kt`、`app/.../data/repository/{VaultEntryMapper,VaultEntryWriteCoordinator,RealVaultRepository,VaultRepository,VaultRepositoryTypes,VaultEntrySecretReader}.kt`、
  `app/.../ui/screens/detail/{EntryDetailCards,EntryDetailScreen,EntryDetailViewModel,EntryDetailTotpTicker}.kt`、
  `app/.../ui/screens/authenticator/{AuthenticatorScreen,AuthenticatorViewModel,AuthenticatorUiState}.kt`；
  新增测试 `core/.../otp/HotpSupportTest.kt`（10 例：类型/计数器解析、TOTP 不受影响、替换/末位/追加/非 ASCII 标签、
  fail-closed、路径段 `counter=` 不误匹配）+ `VaultEntryMapperTotpTest` 追加 RFC 4226 附录 D 官方测试向量（1 例，counter 0/1 → `755224`/`287082`）。

### 21.2 ISSUE-P3-51：单条「移动到分组」与「从模板新建」入口

- **详情页单条移动**：溢出菜单新增「移动到分组」，复用 `VaultBatchMoveDialog`（新增可覆写 `titleRes`），
  经单元素 `batchMoveEntries` 落库；只读会话隐藏入口；分组候选经 `EntryDetailStateAssembler` 叠加 `vaultRepository.getGroups()` 下发。
- **新建条目套用模板**：新建分类对话框在库内已安装「模板」分组时呈现「从模板新建」→
  `VaultTemplatePickerDialog` 选择模板 → 携带 `templateId` 进入编辑页；`EntryEdit` 路由新增可选 `templateId`；
  `EntryEditViewModel.loadTemplate` + `applyTemplateEntry` 仅预填**结构性字段**（标题/用户名/URL/备注/图标/标签/
  AutoType/Override URL/自定义字段键与保护标记），**不复制**密码、TOTP、附件与历史，且**保持 `entryId` 为空**（保存即新建而非覆盖模板）。
- **证据**：`VaultListProjection.kt`（`templateEntries` 投影）、`VaultListUiState.kt`、`VaultListDialogs.kt`
  （`VaultTemplatePickerDialog` + 创建对话框第三项）、`VaultListDialogHost.kt`、`VaultListScreen.kt`、
  `Screen.kt`、`KeePasskeyNavGraph.kt`、`EntryEditFormProjection.kt`、`EntryEditViewModel.kt`；
  新增测试 `EntryEditTemplateProjectionTest.kt`（4 例：entryId 为空 / 结构性字段复制 / 不复制附件与密码长度 / 落点分组优先级）。

### 21.3 ISSUE-P3-50：`AttachmentManager` 孤儿实现清理

- 经全仓复核（`src/main` 零消费方，仅其单测引用；附件查看/导出另走 `EntryDetailAttachmentExporter` SAF 直写 +
  隔离预览），按仓库「不保留无消费方实现」纪律**连同单测删除**
  `core/src/main/java/com/keepasskey/core/attachment/AttachmentManager.kt` 与
  `core/src/test/java/com/keepasskey/core/attachment/AttachmentManagerTest.kt`（-6 例）。

### 21.4 验收证据（2026-09-11，`--rerun-tasks` 强制真实执行）

`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**（114 任务全部 `executed`），
聚合全部 `build/test-results/**/TEST-*.xml` 得 **1345 例 / 0 失败 / 13 跳过**
（= 批次 A 基线 1336 + 新增 15 例（HOTP 10 + 模板预填 4 + 映射向量 1）− 删除孤儿单测 6 例）。

### 21.5 未自动化验证项（如实）

- `RealVaultRepository.advanceEntryHotpCounter` 的**落库顺序**（先算码 → 计数器 +1 持久化 → 返回该码）与
  `updateEntryOtpConfig` 不产生历史修订，属会话/落盘集成路径，本批次以**代码事实 + 设计保证**留痕，
  未做仪器化断言（需真实 `.kdbx` 会话与设备/宿主 JNI 环境）；core 侧解析与计数器递增已由 `HotpSupportTest` 全覆盖，
  映射层出码由 RFC 4226 官方向量锁定。
- HOTP 的真机 UI 交互（详情页 / 验证器页取码）未做 instrumented 验证（无设备），随 ISSUE-P3-23 的设备缺口一并待补。

> **当前残余面（ACTIVE）**：ISSUE-P3-23（真实 `.kdbx` 语料 + arm64 数据）· ISSUE-P3-58（CodeQL Kotlin 抽取器上游阻塞）；
> P3-24 / P3-32 / P3-57 已于 2026-09-11 闭环归档（见 §23）。

---

## 22. 存量问题由易到难整改闭环批次归档（P1-11 / P2-17 / P2-18 / P3-52 ~ P3-56）

**范围**：`ACTIVE_ISSUES.md` 2026-09-11 登记的 8 项**本地可整改**条目，按难度递增顺序整改并归档；
外部阻塞项 P3-23 / P3-24 / P3-32 当时仅复核留痕、**未归档**（P3-24 / P3-32 后于 §23 闭环）。
关键决策：P3-54 采用 **Keystore HMAC 完整性绑定**；P1-11 **只收录已取证指纹、其余 fail-closed 降级 DAL**。

### 22.1 ISSUE-P3-55：复合密钥派生 UTF-8 密码副本清零（最易）

- 整改前 `KdbxKeyDerivation.deriveKeys` 的「密码 + 密钥文件」分支中 `charsToUtf8(passwordChars)` 产出的
  明文 UTF-8 字节副本未被捕获、随 GC 驻留（同文件「仅密码」分支已正确清零）。
- 整改：捕获中间字节并在 `finally` 中 `Arrays.fill` 清零，对齐同文件既有写法；不引入 `String` 中间态。
- 证据：`database/.../file/KdbxKeyDerivation.kt`；复合密钥正确性回归由既有
  `database/.../KdbxCompatibilityAndSecurityTest.kt` 锁定（三分支不回归）。

### 22.2 ISSUE-P3-56：零信任审计次要加固项打包（子项 1-5）

1. **release 强制关闭诊断日志**：`DiagnosticLogModule.provideDiagnosticLogGate` 改为
   `BuildConfig.DEBUG && store.isDiagnosticLogEnabled()`（释放版不落 PII；debug 版保留可观测性）。
2. **S3 对象键过滤 `.`/`..`**：`S3KeyCodec.encodePath` 剔除纯点段（对齐 `WebDavUrlCodec` 既有语义），
   `buildUrl` 与 SigV4 `canonicalUri` 同源一致，不产生签名不匹配；`S3SyncProviderTest` 增 1 例。
3. **Provider 测试注入口标注**：`S3SyncProvider` / `WebDavSyncProvider` 的 `client` 参数改 `@VisibleForTesting private val` 并加 KDoc；
   `sync/build.gradle.kts` 增 `implementation(libs.androidx.annotation)`（纯编译期注解，不改依赖拓扑）。
4. **TOTP 复制默认开启——评估结论**：**维持默认开启**。TOTP 为 30 秒时效验证码，复制走
   `ClipboardSecurityManager`（已带 `EXTRA_IS_SENSITIVE` + 按用户偏好定时擦除）；残余面（窗口内其他前台应用可读）
   属 Android 平台固有限制，不做应用层对抗，改默认关闭无实质安全增益。
5. **`skipDalVerification` / KDF 边界**：确认留痕无遗漏（显式降级开关默认关；Argon2 上界构成有界 DoS 但纵深防御缺口当前不可达），不强制整改。

### 22.3 ISSUE-P3-52：自动填充确认/选择器生物识别绑定 `CryptoObject`

- 整改前两 Activity 调 `BiometricAuthManager.authenticate` 未传 `Cipher`，生物识别仅证明「用户在场」。
- 整改：新增应用级认证绑定 AES 密钥别名 `KeystoreManager.AUTOFILL_AUTH_KEY_ALIAS`
  （规格同快速解锁密钥，仅用于 `CryptoObject` 绑定、不 `doFinal`）；`KeystoreManager.initAutofillAuthCipher()`、
  `BiometricAuthManager.prepareAutofillAuthCipher(): Cipher?`（不可用返回 null，fail-closed）；
  新增纯策略 `AutofillAuthBindingPolicy.isBound(result)`（`Success && cipher != null`）。
  `AutofillConfirmActivity`：绑定认证成功但结果无 Cipher → 退化受保护窗口手动确认；`AutofillPickerActivity`：同样绑定，退化语义不变。
- 证据：`security/{KeystoreManager,KeystoreKeyMaterial,BiometricAuthManager,AutofillAuthBindingPolicy}.kt`、
  `autofill/{AutofillConfirmActivity,AutofillPickerActivity}.kt`；新增 `AutofillAuthBindingPolicyTest`（3 例）。

### 22.4 ISSUE-P3-53：运行完整性探测时变信号实时化

- 消费方复核：`RuntimeIntegrityGate` 生产消费方仅 `BiometricAuthManager.authenticate`（`currentEnforcement()`）
  与 `KeePasskeyAutofillService`（`awaitEnforcement()`）两处。
- 整改：新增纯函数 `RuntimeIntegrityPolicy.escalateForLiveSignals`（实时信号与缓存信号按「或」合并后重新裁决）；
  `currentEnforcement()` 以实时 `Debug.isDebuggerConnected()` 升级缓存快照；`awaitEnforcement()` 前先 `refresh()` 重扫
  （含钩子框架等 IO 信号），保留 UNDETERMINED fail-closed 兜底。
- 证据：`security/{RuntimeIntegrityPolicy,RuntimeIntegrityDetector}.kt`；`RuntimeIntegrityPolicyTest` 增 4 例
  （含「冷启动后附加调试器可被后续实时判定升级为 COMPROMISED」）。

### 22.5 ISSUE-P3-54：解锁节流计数 Keystore HMAC 完整性绑定

- 整改：新增 `UnlockThrottleIntegrity` 抽象 + `AndroidKeystoreUnlockThrottleIntegrity`（AndroidKeyStore `HmacSHA256`，
  别名 `com.keepasskey.unlock_throttle_integrity`，**不绑用户认证**、锁屏态可用）+ 纯函数 `UnlockThrottleMacPayload.encode`。
  `UnlockThrottleRecord` 增 `integrityIntact`；`SharedPrefsUnlockThrottleStore` 写入/校验 MAC；
  `UnlockThrottleManager.gate` 在完整性失效时 **fail-closed**：落一条带有效 MAC 的**有界**锁定期记录（≤ `MAX_BACKOFF_MS`）并返回 `Locked`，
  既不放过也不永久锁死；Hilt 绑定见 `SecurityModule`。
- 证据：`security/{UnlockThrottle,UnlockThrottleIntegrity}.kt`、`di/SecurityModule.kt`；
  `UnlockThrottleManagerTest` 增 1 例、新增 `UnlockThrottleIntegrityTest`（4 例）。

### 22.6 ISSUE-P2-17：子库解锁接入节流

- 整改：`ChildDatabaseSessionManager` 注入 `UnlockThrottleManager`；`open()` 内、进入 `loadProjection` **之前**
  做 `gate(mountId)`，锁定期直接返回 `ChildDatabaseFailureReason.THROTTLED`（**不进入 `KdbxFile.load`**）；
  结算保留主库语义——仅 `CREDENTIAL_REJECTED` 计次，IO/损坏/版本等非认证失败不计次。新增失败分型 `THROTTLED` +
  中英文案 `dbset_child_db_err_throttled`。
- **key 选择决策**：以持久化 `mountId` 为节流键；首次 `mount` 需 SAF 交互、非在线爆破向量，本批不额外门控 `mount`（已留痕）。
- 证据：`data/childdb/{ChildDatabaseSessionManager,ChildDatabaseModels}.kt`、`ui/screens/settings/SettingsUiState.kt`、
  `res/values*/strings*.xml`；`ChildDatabaseSessionManagerTest` 增 3 例（阈值锁定不计入解密 / 成功清零 / 非认证失败不计次），
  其余 3 处测试构造点同步更新；`ChildDatabaseStatusTextTest` 基线 12 → 13。

### 22.7 ISSUE-P1-11：受信浏览器「包名 + 签名证书指纹」

- 整改前浏览器分支仅按包名放行——侧载占用未安装浏览器包名的 APK 可冒领 `webDomain` 触发跨应用凭据泄露。
- 整改：新增 `BrowserSigningFingerprints`（包名 → 已取证 SHA-256 指纹集合），`AutofillWebDomainPolicy.isTrustedBrowser`
  改「包名 + 指纹」二元组、`attribute(...)` 增 `certSha256Hex` 参数；`AutofillOriginResolver` 把证书指纹读取**上移**至浏览器判定之前，
  指纹不匹配 / 未取证浏览器一律 fail-closed 降级 DAL 路径。
- **指纹取证（只收录已取证者）**：`com.android.chrome`（2 个，来源＝仓库既有 passkey 白名单
  `CallingOriginResolver.PRIVILEGED_BROWSER_ALLOWLIST`）；`org.mozilla.firefox` / `org.mozilla.firefox_beta`
  （来源＝Mozilla 官方 `firefox-source-docs.mozilla.org/mobile/android/fenix/certificates.html`，2026-09-11 拉取）。
  其余原「仅包名」列表浏览器（`org.mozilla.focus`、`com.brave.browser`、`com.microsoft.emmx`、`com.sec.android.app.sbrowser` 等）
  **未取得可引用来源，一律不收录** → 回退 DAL fail-closed（安全优先的显式取舍）。
- 证据：`security/BrowserSigningFingerprints.kt`（新增）、`autofill/{AutofillWebDomainPolicy,AutofillOriginResolver}.kt`；
  `AutofillWebDomainPolicyTest` 重写并增「包名占位但签名不匹配 → REJECTED」等断言（9 例）。

### 22.8 ISSUE-P2-18：同步防回滚绑定（设计 + 实现）

- **威胁模型**：Assume Breach，云端不可信，可重放「旧的但仍能用主凭据解密的合法 `.kdbx`」；既有三哈希状态机不解决版本回退。
- **方案（已按计划先出方案再实施）**：本地认证的「**已见内容摘要链**」——
  `SyncIntegrityMac`（sync 定义抽象，app 以 AndroidKeyStore HMAC 实现 `KeystoreSyncIntegrityMac`，别名
  `com.keepasskey.sync_rollback_integrity`、不绑用户认证、锁屏后台可用）；`SyncRollbackGuard` 为每 `remotePath` 维护
  经 MAC 认证的状态（`current` + 有界 32 条 `recent`），裁决 `Unchanged` / `Accept` / `ReplayDetected`。
  `SyncEngine` 在所有「即将接受远端字节」的落点（未缓存下载、远端更新下载、两类冲突下载）先 `inspect`，
  命中重放返回新增结果 `SyncOpenResult.RollbackRejected` / `SyncCommitResult.RollbackRejected`；
  接受/上传成功后 `recordAccepted` 前移高水位；`SyncCache.clear` 一并清理 `.rollback` 状态。
  `SyncCycleRunner` 将 `RollbackRejected` 映射为 `SyncOutcome.Error(sync_error_rollback_rejected)`
  ——**保留本地/基准、不应用远端**并给出明确用户提示。
- **跨端兼容决策（留痕）**：采用「已见摘要链」而非纯单调序号——其他官方客户端（KeePass 2.x / KeePassDX / KeePassXC）
  写入全新内容（新摘要）永远 `Accept`，**不误报**；仅与设备侧曾接受过的历史版本逐字节相同的重放才被拒。
- 证据：`sync/engine/{SyncIntegrityMac,SyncRollbackGuard,SyncEngineResults,SyncEngine,SyncCache}.kt`、
  `app/sync/{KeystoreSyncIntegrityMac,SyncIntegrityModule,SyncCycleRunner,SyncConflictController}.kt`、`res` 中英文案；
  新增 `SyncRollbackGuardTest`（4 例）+ `SyncEngineTest` 增 2 例（旧库重放被拒 / 其他客户端全新内容不误报）。

### 22.9 验收证据（2026-09-11，`--rerun-tasks` 强制真实执行）

- `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**（114 任务全部 `executed`）；
  聚合全部 `build/test-results/**/TEST-*.xml`（175 套件）得 **1370 例 / 0 失败 / 0 错误 / 13 跳过**
  （= 批次 B 基线 1345 + 本批净增 25 例，逐一：`AutofillWebDomainPolicyTest` 净增 3、`AutofillAuthBindingPolicyTest` 3、
  `RuntimeIntegrityPolicyTest` 4、`UnlockThrottleManagerTest` 1、`UnlockThrottleIntegrityTest` 4、`S3SyncProviderTest` 1、
  `ChildDatabaseSessionManagerTest` 3、`SyncRollbackGuardTest` 4、`SyncEngineTest` 2）。
- `.\gradlew.bat lint --max-workers=1` → **BUILD SUCCESSFUL（5 模块 0 error）**。

### 22.10 过程缺陷 / 事实修正（如实留痕）

1. `SyncRollbackGuard` 首次实现把 MAC 载荷写成**含尾随换行**，而 `load` 以 `readLines` 重组时丢失尾随空行，
   导致 MAC 永不匹配、全部状态退化为「无历史」——`sync` 模块首跑 **4 例失败**暴露，改为载荷不含尾随换行后通过。
2. 新增 `UnlockThrottleIntegrityTest` 初版对 `ByteArray` 误用 `assertEquals`/`assertNotEquals`（引用相等），
   改用 `assertArrayEquals` / `contentEquals` 后语义正确。
3. `@VisibleForTesting` 不能用于构造器值参数（Kotlin 目标不兼容），改为 `@VisibleForTesting private val client` 属性后编译通过。
4. P1-11 缩小受信浏览器集属**有意的安全取舍**（仅 Chrome + Firefox release/beta 已取证），其余浏览器域填充便利性下降，
   已在 §22.7 与代码注释留痕，非缺陷。

> **当前残余面（ACTIVE，未归档）**：
> - ISSUE-P3-23（真实 `.kdbx` 语料 + arm64 数据）——**设备侧链路已于 §24 实测验证可用**，阻塞项收窄为「语料」与「arm64 数据」；
> - ISSUE-P3-58（CodeQL 的 Kotlin 抽取器不支持 Kotlin 2.4.20，**上游阻塞**，已登记为已接受的风险）。
>
> **ISSUE-P3-24 / P3-32 / P3-57（§23）与 ISSUE-P1-12（§24）已于 2026-09-11 闭环归档。**

---

## 23. CI 侧真实跑通归档（dependency-scan 首绿 · ISSUE-P3-24 / P3-32 闭环）

**核实时间点与核实方式（2026-09-11）**：以 `gh workflow run dependency-scan.yml --ref main` 手动触发运行
**`34575788016`**（`workflow_dispatch`，`headSha=dfda33d`），`gh run watch --interval 60` 全程盯守，
`gh run view --log` 取原始日志（`gh` 日志缓存目录以 `LOCALAPPDATA` 重定向到工作区，绕开沙箱对
`%LOCALAPPDATA%` 的写限制）；Code Scanning 侧以 `gh api "/repos/Wuming155/KeePasskey/code-scanning/alerts?state=open"
--paginate` 全量统计并分组。

### 23.1 运行事实（原始证据）

| 项 | 实测值 |
|---|---|
| 运行 / 结论 / 耗时 | `34575788016` · **success** · 15m40s |
| runner | `ubuntu-24.04`，image release `20260907.300`（一并回答 P3-24 风险点 R1）|
| aggregate | 六个工程（root/app/core/crypto/database/sync）全部执行，`BUILD SUCCESSFUL in 15m 4s`；**未再出现 `NvdApiException` / 503** |
| 硬断言 | `已扫描报告 1 份；漏洞实例 7 条；达阈（CVSS ≥ 7.0）实例 0 条，去重后（CVE × 构件）0 条。` + `CVSS 闸门通过：无未豁免的 HIGH/CRITICAL 依赖漏洞。`（步骤 exit 0）|
| artifact / SARIF | 两步均 ✓；日志含 `Post-processing sarif files: ["build/reports/dependency-check/dependency-check-report.sarif"]` |
| Code Scanning 依赖类 open | **7 条**（`CVE-2020-29582`×5 / `CVE-2020-13956`×1 / `CVE-2025-48924`×1，均 `medium`），**未做任何 dismiss** |

与 2026-09-10 本地实测（188 → 7 实例、达阈 138 → 0）**逐项吻合**。

### 23.2 ISSUE-P3-24 闭环

- **残余 1**（Fast gate 转绿）此前已闭环（§18）；本次补全其余各项：
  - **残余 2**「aggregate 成功前提下的断言 pass/fail」→ 已取得**确定判定**（exit 0，见 §23.1），不再是被 skip 的「无判定」；
  - **残余 3**「有 SARIF 时的成功上传」→ 已取得（见 §23.1）；
  - **残余 4** 的 R1（`ubuntu-latest` 实际指向）→ 实测为 `ubuntu-24.04`（image release `20260907.300`）；
  - **残余 6**（3 条 POSIX 断言的最终结果）→ 随 Fast gate 转绿已取得。
- **仍未消除（如实，不据此宣称已闭环）**：**残余 5**「Gradle daemon JVM criteria 不满足时的失败/自动供给行为」
  本次**未被触发**（`Set up JDK 21` 已保证 criteria 满足），该不确定性保持登记。
- **已登记未改的风险点**（R2 ~ R9，含 R4 wrapper 镜像与 `distributionSha256Sum`、R5 `~/.cargo` 未缓存、
  R6 GHAS 前提等）按 §19 原状保留为**维护者决策项**，本批次不改。

### 23.3 ISSUE-P3-32 闭环

- **验收标准 1**（真实运行中给出确定的 pass/fail）→ **达成**：在 aggregate 成功前提下硬断言 exit 0，
  给出明确通过判定；其 fail-closed 分支（报告缺失 / 结构非法 → exit 1）此前已在本地逐例实测。
- **验收标准 2**（每个达阈族二选一处置并留痕）→ 本次达阈（CVSS ≥ 7.0）实例 **0 条**，无可处置族；
  4 个豁免族仍按 §7 登记依据在册。
- **验收标准 3**（Code Scanning 依赖类 open 告警数与该族结论一致）→ **7 条 = 未达阈残余 7 条**，一致，且未 dismiss。
- **前置事实修正（如实留痕）**：此前条文中「处置路径为配置仓库 Secret `NVD_API_KEY`」的表述**前提不成立**——
  本次实测证明 `NVD_API_KEY` **早已配置并生效**：上一次运行 `34477320673` 的 aggregate 走的是
  `NvdApiDataSource.processApi`（该路径**仅在 Key 非空时**启用，否则走托管镜像 datafeed），
  失败根因是 **NVD 服务端间歇 503**（重试 31 次），**并非缺 Key**。该前提已就地更正。

### 23.4 过程留痕：CodeQL 侧新发现（ISSUE-P3-57 的发现过程；闭环见 §23.5）

本次核对 Code Scanning 时发现 **77 条** CodeQL `rust/hard-coded-cryptographic-value`（critical）open 告警，
创建时刻同为 `2026-09-10T13:18:38Z`；对全部 77 条逐条取行号并与本地源文件交叉核对，**77/77 落在
Rust 单测模块 `#[cfg(test)] mod tests` 之内**（测试模块起始行：`strength.rs` L521 / `twofish_cbc.rs` L114 /
`aes_kdf.rs` L91）。查询源码（`github/codeql` 的 `HardcodedCryptographicValue.ql`）**无任何测试代码过滤**
（`ConfigSig` 仅 `isSource`/`isSink`/`isBarrier`），故「把单测外移」**单独实施不生效**——该设想已被本批次**推翻并留痕**。
候选处置与验收标准见 §23.5（同一 PR #6 内闭环）。

### 23.5 ISSUE-P3-57 闭环：Code scanning 对 Rust 单测的 critical 误报治理

- **问题**：Code Scanning open 告警 84 条 = CodeQL **77 条** `rust/hard-coded-cryptographic-value`
  （critical，查询声明 `@security-severity 9.8`）+ dependency-check 7 条（未达阈，见 §23.3）。
  CodeQL 77 条经**逐条**行号核对，**77/77 落在** 3 个 Rust 源文件的 `#[cfg(test)] mod tests` 内
  （`strength.rs` 53 / `twofish_cbc.rs` 17 / `aes_kdf.rs` 7），全部为测试夹具与已知答案向量 → 判定**误报**。
- **机制验证（先验证后动手；结论推翻了初始设想并留痕）**：
  1. 查询源码（`github/codeql` 的 `HardcodedCryptographicValue.ql`）**无任何测试代码过滤**
     （`ConfigSig` 仅 `isSource` / `isSink` / `isBarrier`）→ **仅把单测外移无效**；
  2. `paths-ignore` 是**文件级**过滤 → 要拦住，测试必须拥有**独立文件路径**，即"外移"是**前提**而非方案本身；
  3. `paths-ignore` / `query-filters` 属 **advanced setup** 能力；本仓此前为 GitHub 侧**默认设置**
     （仓库内无 CodeQL 工作流），故必须切换。
- **实施**（PR #6，提交 `4d45988`）：
  1. 3 个源文件的内联 `#[cfg(test)] mod tests` 外移到 `crypto/src/main/rust/src/tests/`（3 个**纯测试**文件），
     源文件侧改为 `#[cfg(test)] #[path = "tests/<name>_tests.rs"] mod tests;` —— 模块路径仍为 `<mod>::tests::*`，
     `use super::*` 与私有项可见性不变；
  2. 新增 `.github/codeql/codeql-config.yml`：`paths-ignore` **仅**排除 `crypto/src/main/rust/src/tests/**`，
     并写明「禁止排除含生产代码的文件」的维护纪律；
  3. 新增 `.github/workflows/codeql.yml`（advanced setup）：matrix `rust`（`build-mode: none`）/ `python` / `actions`，
     与默认设置下**实际产出分析**的语言对齐；**未纳入** `java-kotlin` 与 `c-cpp`（二者在默认设置下为
     `rules=0, results=0` 的空分析）——此为**有意的范围收缩**，已在工作流注释与 PR 说明中留痕；
     未显式指定 `queries`，沿用默认套件（依据：三语言 rules 计数 26/43/17 与默认套件规模一致）；
  4. **运维动作（人工）**：Settings → Code security → Code scanning → **Default setup → Disable**
     （默认设置与 advanced 设置会互相覆盖：上传会被拒 `CodeQL analyses from advanced configurations cannot be
     processed when the default setup is enabled`，首次运行已实测到该报错）。
- **验证（双向实证，数据取自 `code-scanning/analyses`）**：

  | 分析时刻 | 来源判据 | ref | rust `results` | `rules` |
  |---|---|---|:---:|:---:|
  | 08:29 | `environment.runner = ["ubuntu-latest"]` → **默认设置** | `refs/pull/6/head`（代码**已外移**） | **77** | 26 |
  | 09:12 | 无 runner 键 + 工作流自带 category → **本仓 advanced setup** | `refs/pull/6/merge` | **0** | 26 |

  第一行**证明「仅外移无效」**（外移后的代码在默认设置下仍报同样 77 条）；第二行证明配置级排除生效，
  且 **`rules` 未变（26）= 查询套件照常运行，生产代码未被 `paths-ignore` 误伤**。
  另：`cargo test`（Rust 1.97.1）**43 passed / 0 failed**，与改动前基线逐项一致；PR 的 `build` 工作流 **success**（9m12s）。
- **合入与最终验收（2026-09-11 实测）**：以 squash 合并为 **`93b088f`**（PR #6）；`codeql.yml` 的 `push`
  触发默认分支分析（运行 `34584389259`，**三个语言 job 全 success**）后实测：
  `code-scanning/alerts?state=open` **84 → 7**，且 7 条**全部**为 dependency-check 未达阈中危项；
  `rust/hard-coded-cryptographic-value` **open = 0**、**state=fixed = 77**（逐条关闭）。
  验收标准 ①②③ 全部达成：① 该 rule 收敛到 0；② 处置依据与证据留痕于本节；
  ③ **未削弱覆盖面**——`rules` 保持 26（查询套件照常运行）、`paths-ignore` 仅一条**不可能匹配生产文件**的
  glob（`crypto/src/main/rust/src/tests/**`）、main 侧分析正常产出。
- **过程留痕（如实）**：「外移单测」是本次**被推翻**的初始处置设想（曾据同类项目经验认为其单独可行）；
  且本轮曾把「首次 PR 运行的 77 条」误读为 advanced setup 未生效，经 `analyses.environment` 比对 `runner`
  标签后才确认其属默认设置的分析——**假设错误、以证据更正**。

---

## 24. 设备侧实测发现的致命缺陷修复（ISSUE-P1-12：Android 端 KDBX XML 解析全量失败）

**发现方式（2026-09-11）**：为验证 ISSUE-P3-23 的「设备侧测试链路是否可用」，启动本机 AVD `Pixel_10`
（x86_64，API 36）并实跑 `.\gradlew.bat :database:connectedDebugAndroidTest`——
这是本仓**首次**在真实 Android 运行时上执行 `database` 设备侧用例（此前一直阻塞于设备/语料）。

### 24.1 缺陷

- **现象**：`SelfGeneratedRoundTripInstrumentedTest`（设备侧写入后读回）失败：
  `KdbxCorruptFileException: KDBX XML 解析失败：文档结构非法或已损坏`，
  根因异常为 `javax.xml.parsers.ParserConfigurationException: org.xml.sax.SAXNotRecognizedException:
  http://xml.org/sax/features/resolve-dtd-uris`，抛出点为 `KdbxXmlParser.parse` 内的
  **`factory.newSAXParser()`**（不是 `setFeature`）。
- **根因**：Android（Harmony）的 `SAXParserFactoryImpl.setFeature` **不校验**特性名，仅记录；
  真正应用与校验发生在 `newSAXParser()`。原实现仅把 try/catch 包在 `setFeature` 外，因此异常从
  `newSAXParser()` 逃出、被上层包装为 `KdbxCorruptFileException` →
  **设备端每一次 KDBX 解析都失败**。
- **影响面（为何是致命级）**：`KdbxFile.load` 是**唯一**解析入口，生产调用方为
  主库打开（`database/.../session/SessionOpener.kt:142`）、子库（`app/.../childdb/ChildReadOnlySession.kt:134`）、
  同步（`app/.../sync/SyncDatabaseCodec.kt:55`）——即**设备端任何库都打不开**。
  JVM 单测走 Xerces（支持该特性）故**从未暴露**；本仓此前从未在设备上跑过，缺陷得以长期潜伏。

### 24.2 修复

- 把「逐项 `setFeature` + 告警」改为**探测式应用**：对每一项在「已接受集合 + 该项」上实例化一次
  `SAXParser` 作探针，平台不支持的项**跳过并留痕告警**
  （新增 `buildHardenedParser` / `newFactory` / `XxeGuardFeature`，移除 `applyXxeGuardFeature`）。
- **安全语义不削弱**：DTD 与外部实体的实际拦截由 handler 侧两道**与特性支持无关**的 fail-closed 兜底完成
  （`DefaultHandler2.startDTD` 直接拒绝 DTD 声明、`resolveEntity` 直接拒绝外部实体，
  见 `KdbxXmlParser.kt` 内 handler 定义）；工厂特性始终只是「尽力加固」层——与 ISSUE-P3-10 子项 3 的既有取舍一致。

### 24.3 验收证据（2026-09-11，真实 AVD 实测）

设备：`emulator-5554`（`sdk_gphone64_x86_64`，API 36）。

| 项 | 修复前 | 修复后 |
|---|---|---|
| `SelfGeneratedRoundTripInstrumentedTest`（设备侧真实 KDBX 读写回环）| ❌ `SAXNotRecognizedException` | ✅ **pass（0.293s）** |
| `:database:connectedDebugAndroidTest` | ❌ BUILD FAILED | ✅ **BUILD SUCCESSFUL** |
| `:database:test`（JVM） | ✅ | ✅ 无退化 |
| `test --rerun-tasks`（全量）| — | ✅ **1370 例 / 0 失败 / 0 错误 / 13 跳过**（174 套件，与基线持平） |
| `lint`（5 模块）| — | ✅ 0 error |

### 24.4 顺带纠正的事实（ISSUE-P3-23 相关）

- **设备侧链路可用性：已验证**（见 24.3）——`RealKdbxCorpusUnlockTest` 之外的设备侧用例可真实执行，
  P3-23 的阻塞项收窄为「真实语料」与「arm64 数据」两项（x86_64 AVD 已可承载语料就绪后的端到端用例）。
- **「语料缺失 → Assume 显式跳过」的报告呈现与实情不符**：AGP 生成的
  `build/outputs/androidTest-results/connected/debug/TEST-*.xml` 把 `AssumptionViolatedException`
  记为 **`<failure>`（`skipped=0`）**，**但 task 级仍 `BUILD SUCCESSFUL`**。
  即：**门禁语义正确**（语料缺失不会把任务弄红，也不代表 AC② 达成），但**报告会误导**读者以为 2 例失败。
  该现象已就地写入 ACTIVE_ISSUES 的 P3-23 条目（措辞修订）。

### 24.5 过程留痕（如实）

1. 首次尝试以 `-Pandroid.testInstrumentationRunnerArguments.class=<类名>` 只跑单个用例，
   **沙箱包装层吞掉了 `-Pandroid` 前缀**，Gradle 把残余参数当成任务名报
   `Task '...class=...' not found`——改用整任务运行 + 从 XML 读单例结果。
2. 统计用例数时首版 PowerShell 脚本恒返回 0：**PowerShell 的 XML 适配器让 `name` 属性遮蔽了
   XmlElement 的 `Name` 属性**，使 `$root.Name -eq 'testsuite'` 判false 而走进空分支；
   改用 Python `xml.etree` 统计后得到 1370/0/0/13。

---

## 25. 设备侧互操作语料入库与端到端解锁跑绿（ISSUE-P3-23 验收标准② 闭环）

**背景**：ISSUE-P3-23 的验收标准②为「真实语料（含同名 `.json`）入库两处后
`:database:connectedDebugAndroidTest` 中 `RealKdbxCorpusUnlockTest` **不再是 skip** 且全绿」。
本批次之前，该用例因语料缺失而整体跳过（AGP 报告中记为 `<failure>`，见 §24.4）。

### 25.1 处置

- **语料来源**：本机既有的 **KeePassXC 官方产物** `KeePasskey测试/测试.kdbx`
  （内层 `Meta/Generator=KeePassXC`；Argon2d · v19 · t=89 · m=64 MiB · p=4 · AES-256-CBC；32B 盐）。
  落位前以**解密探针**（临时宿主 JVM 用例，用后即删）逐条读取：**14 条条目全部为「模拟账号」占位数据**，
  KDF `secret(K)` / `associatedData(A)` 均为 `null`——证实**零真实数据**、无秘密 KDF 分量。
- **落位**：复制为规范名（由文件头实测参数推导）`argon2d-v19-t89-m64-p4-keepassxc.kdbx`，
  连同 `111.keyx`（XML KeyFile v2.0）与**同名伴生 `.json`**，**双落位**至
  `crypto/src/test/resources/argon2-interop/` 与 `database/src/androidTest/assets/argon2-interop/`（不可互替）。
- **一致性校验**：`python tools/kdbx-corpus/generate_corpus.py --verify <kdbx> --json <json>`
  逐字段核对「文件头 ↔ 伴生 JSON」，通过。
- **用例增强**：伴生 JSON 新增**可选**字段 `passphrase`（本语料自带专用一次性口令，公开测试常量），
  缺省回退 `CORPUS_PASSPHRASE = Test-Vector-Only-2026!`；同步更新两处 README。
- **工具修正**：`generate_corpus.py` 的 `canonical_name` 内存单位由 **KiB 改为 MiB**
  （对齐 README §6.1「文件名即声明」：64 MiB → `-m64`），非整 MiB fail-closed 拒绝。

### 25.2 验收证据（2026-09-11，真实 AVD 实测）

设备：`emulator-5554`（`sdk_gphone64_x86_64`，API 36）。

| 项 | 处置前 | 处置后 |
|---|---|---|
| `RealKdbxCorpusUnlockTest`（2 例） | ⏭ skip（报告记为 `<failure>`） | ✅ **2/2 pass、0 skip、0 failure**（1.24s / 1.42s） |
| `:database:connectedDebugAndroidTest` | ✅ BUILD SUCCESSFUL（仅 skip） | ✅ **BUILD SUCCESSFUL**（真跑绿） |
| 语料文件头 ↔ 伴生 JSON | — | ✅ `--verify --json` 逐字段一致 |
| `test --rerun-tasks`（全量） | 1370/0/0/13 | ✅ **1370 例 / 0 失败 / 0 错误 / 13 跳过**（无退化） |

报告：`database/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 16.xml`
（`tests="3" failures="0" errors="0" skipped="0"`）。

### 25.3 残余（不归档原因）

- `ISSUE-P3-23` 的 AC③/④（**arm64 真机** §4.3 表格数据）仍阻塞于本机无 arm64-v8a 镜像、无真机连接，
  故**整条不从 ACTIVE_ISSUES 移出**，仅将 AC② 记为闭环。

### 25.4 过程留痕（如实）

- 曾评估「下载官方 KeePassXC 便携版另建一次性口令语料」方案，后因本机已有官方产物而**未采用**；
  最终按用户指示改用既有真实库，并以探针证据确认其零真实数据后方入库。
- 命名单位分歧：文档（README §6.1）为 MiB，而脚本原实现按 KiB 生成文件名；本批次以文档为准修正脚本。

---

## 26. 设备侧手工实操发现的 P0 崩溃修复（ISSUE-P0-04：字段引用正则在 Android ICU 上非法）

**发现方式（2026-09-11）**：在 AVD `Pixel_10`（`emulator-5554`，x86_64 / API 36）上安装 `:app:assembleDebug`
后**手工实操主流程**（新建库 → 解锁 → 新建条目 → 保存）。这是本仓**首次在真实 Android 运行时上跑
`app` 模块的端到端功能**——`app` / `sync` 无 `androidTest` 源集，此前设备侧只覆盖 `crypto` / `database`
的库内逻辑用例。

### 26.1 缺陷

- **现象**：新建并保存条目后，App 立即崩溃退出到桌面。
- **原始堆栈**（`adb shell logcat -d`）：
  ```
  E/AndroidRuntime: FATAL EXCEPTION: main
  java.lang.ExceptionInInitializerError
    at com.keepasskey.app.ui.model.EntryReferenceDisplayResolver.present(EntryReferenceDisplayResolver.kt:65)
  Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 37
    \{REF:([TUAPNI])@([TUAPNI]):([^{}]*)}          ← 结尾 } 未转义
    at com.keepasskey.database.fieldref.FieldReferenceEngine.<clinit>(FieldReferenceEngine.kt:47)
  ```
- **根因**：`FieldReferenceEngine.REF_REGEX` 结尾 `}` 未转义。宿主 JVM 的 `java.util.regex` 宽容，
  但 Android 运行时 regex 由 **ICU4C** 支撑——未转义 `}` 直接判语法错误，`<clinit>` 抛
  `ExceptionInInitializerError`。
- **影响面（P0 判定依据）**：`containsReference` 在**库列表逐条目投影**中被调用
  （`VaultListDecorationsProvider` → `RealVaultRepository.getEntries` → `EntryReferenceDisplayResolver.present`）。
  空库（0 条目）不触发类初始化，故「空列表看起来正常」；**库内 ≥1 条目即渲染崩溃** →
  解锁后无法查看/管理任何条目，核心可用性归零。
- **为何长期潜伏**：宿主单测的正则引擎更宽松（1370 例全绿），`app` / `sync` 又无设备侧源集——
  与 §24 的 ISSUE-P1-12 同属「JVM 过、Android 运行时挂」类。

### 26.2 修复

1. `database/.../fieldref/FieldReferenceEngine.kt`：正则改为
   `\{REF:([TUAPNI])@([TUAPNI]):([^\{\}]*)\}`（花括号全部转义），并在 KDoc 记录 ICU 差异与崩溃链路；
2. 新增**设备侧回归锁**
   `database/src/androidTest/java/com/keepasskey/database/fieldref/FieldReferenceEngineDeviceTest.kt`
   （类初始化 ICU 兼容 + 取值解析 + 展示侧掩码不外泄），防同类复发；
3. **语义零变更**（仅转义），宿主既有断言原样通过。

### 26.3 验收证据（2026-09-11，真实 AVD 实测）

设备：`emulator-5554`（`sdk_gphone64_x86_64`，API 36）。

| 项 | 修复前 | 修复后 |
|---|---|---|
| `FieldReferenceEngineDeviceTest`（设备侧新用例 3 例） | 不存在（缺陷无从暴露） | ✅ **3/3 pass**（含 `<clinit>` ICU 兼容回归锁） |
| `:database:connectedDebugAndroidTest` | 3/3（未覆盖该缺陷） | ✅ **6/6 pass、0 skip、0 failure** |
| 手工实操：解锁 → 列表渲染（库内 1 条目） | ❌ FATAL 崩溃退出 | ✅ **列表正常渲染**（`GitHub-Test / tester`，含复制用户名/密码操作） |
| 手工实操：详情页密码显示 | — | ✅ **明文回读 `GenPass2026`**（同时印证 KDBX 存取往返正确） |
| 手工实操：生成器页 | — | ✅ 生成 `d*=6n{L@TxBF@*Z8`（16 位 / 104 bits） |
| 手工实操：设置页 | — | ✅ 正常渲染（密码库与加密 / 云端同步 / 更改主密钥 / 安全与审计 …） |
| 手工实操：验证码页 | — | ✅ 空态正常（「暂无双重验证码条目」） |
| 全量 `test --rerun-tasks` | 1370/0/0/13 | ✅ **1370 例 / 0 失败 / 0 错误 / 13 跳过**（无退化） |
| 全过程 logcat | — | ✅ 无 FATAL / `PatternSyntaxException` |

### 26.4 过程留痕与未验证项（如实）

1. **安装签名冲突**：首次 `adb install` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（模拟器上已有旧签名包），
   须先 `adb uninstall com.keepasskey` 再装。
2. **截图不可用**：本机 gfxstream 下 `adb shell screencap` 产出**全黑图**，故设备侧证据一律改用
   `uiautomator dump` 的文本 / bounds。
3. **输入法遮挡**：向 Compose 密码框连续输入时，第二次 `input tap` 会因 IME 上浮而落空——改为
   「每填一个字段先 `input keyevent 4` 收起键盘」；清空既有内容用 `input keycombination 113 29`（Ctrl+A）覆写更可靠。
4. **未验证项（只登记观察，不臆断为缺陷）**：条目编辑页点「密码生成器」图标会展开内联面板并给出
   长度 / 字符集 / 强度（实测「长度 20 / 中等 90 bits」），但本次**未观察到**生成的密码自动回填到密码字段，
   也未在可视区找到「应用」控件（面板可能可滚动）。因未穷尽交互，此处只作观察登记；后续如需，
   可在 `app` 模块补设备侧用例详查。


---

## §27 批次：设备侧功能实测收口 + 9 项缺陷整改（2026-09-11，接手批次）

> 依据交接文档 `HANDOVER-20260911.md` 的未实测清单（**该文档已随本批次于 2026-09-12 闭环删除**，
> 本节即其归档记录）继续设备侧（AVD `Pixel_10`，
> x86_64 / API 36）手工实操，补完 §3.1 ~ §3.7 全部条目；期间新发现 2 个 P1/P2 级缺陷并同批修复，
> 原登记问题中 6 项整改闭环、2 项复核后确认为非缺陷、1 项升级为确认缺陷保留。
> 本批次后全量单测 **1382 例 / 0 失败 / 0 错误 / 13 跳过**（基线 1370 + 新增 12）。

### 27.1 ISSUE-P1-13：写侧 Argon2 `P` 改 UInt32（已整改闭环）

- **整改**：`database/.../file/KdbxKdfParameterCodec.kt` 的 `serialize` 中 `P` 由
  `vd.setUInt64("P", …)` 改为 `vd.setUInt32("P", …)`（`I`/`M` 保持 UInt64、`V` 保持 UInt32）。
- **新增写侧契约断言**（验收标准 ②）：`database/src/test/.../KdbxKdfParameterCodecWriteContractTest.kt`
  4 例——直接解析序列化字节流的类型 ID（不经本仓宽容读侧回读）逐键断言
  `$UUID`/`S`=ByteArray(0x42)、`P`/`V`=UInt32(0x04) len 4、`I`/`M`=UInt64(0x05) len 8，与
  `generate_corpus.py --verify` 的地面真值判定对齐；另含 `P` 值官方严格 uint 语义回读、AES-KDF `R` 类型与 KDF UUID 一致性。
- **设备侧证据**（验收标准 ③）：修复后本 App 新导出 KDBX 经
  `python tools/kdbx-corpus/generate_corpus.py --verify` **通过**：
  `version=4.0 cipher=aes256-cbc kdf=argon2id t=2 m=65536KiB p=2 argon2Version=19`；
  `build/vd.py` 逐字节确认 `P: type=0x04 len=4 raw=02000000`（修复前为 `type=0x05 len=8`）。
- 验收标准 ④：全量单测无退化（见 §27.9）。

### 27.2 ISSUE-P1-14：后台自动锁定复核——**行为符合设计，按非缺陷结案**

- **复核结论**：上会话登记的「HOME 回前台不锁定」**无法以真实生效的偏好复现**。
  以 DataStore 字节为地面真值确认 `auto_lock_background=true`（立即档）后：
  HOME 回桌面 → 回前台 **已锁定** ✅；SAF（DocumentsUI）占前台 ~10s → 返回 **已锁定** ✅——
  HOME 与 SAF 行为一致，「证据矛盾」消失。
- **30 秒档语义验证**：倒计时切 30 秒档后，后台 5s 返回 **不锁** ✅、35s 返回 **锁定** ✅。
- **熄屏锁定**：熄屏 → 亮屏 **已锁定** ✅。
- **根因剖析（为何上会话误报）**：①偏好写入可能被中途弹出的对话框吞掉（本批次实测复现同款干扰：
  「自动锁定倒计时」对话框开启期间，后续所有注入点击都被其吃掉）；②`uiautomator dump` 对 Compose
  Switch 的 `checked` 属性**存在陈旧/失真**（本批次实测：开关真实渲染为 on 时 dump 报 false，
  与 DataStore 字节互相矛盾；以「点击后读 DataStore 翻转方向」反推才还原真相）。
  上会话据 dump 判定「已开启」并据 force-stop 后 dump 仍 true 判定「已持久化」，两步都被同一假象误导。
- **方法论沉淀（交接文档 §2 已补）**：设备侧判定开关状态一律以 DataStore / SharedPreferences
  字节为地面真值；Compose Switch 的注入点击用 `input motionevent DOWN/UP`（部分坐标 `input tap` 不生效）。

### 27.3 ISSUE-P1-15（新发现）：明文导入 KeePass XML 在 Android 运行时全量失败（已整改闭环）

- **发现**：交接文档 §3.3 导入实测中，4 个导入器里 Bitwarden JSON / 浏览器 CSV / 1Password PUX
  全部导入成功，唯 **KeePass XML 100% 失败**（报「文件结构非法或已损坏」）；同一文件在宿主 JVM
  `KeePassXmlImporterTest` 逻辑下解析成功——「宿主 JVM 过、Android 运行时挂」类缺陷再 +1。
- **根因**（经应用内诊断日志 + `VaultImportController` 新增 WARN 留痕定位）：
  Android Harmony SAX 把 `SAXParserFactory.setFeature("http://xml.org/sax/features/resolve-dtd-uris")`
  的拒绝**延迟到 `newSAXParser()` 才抛** `ParserConfigurationException`（宿主 Xerces 无此行为）；
  `HardenedXmlReader.parse` 中 `newSAXParser()` 未设防 → 异常被 `ImportParseGuard` 归一为 MALFORMED。
- **整改**：`HardenedXmlReader` 解析器构建改为**逐轮剔除降级**——`newSAXParser()` 抛
  `ParserConfigurationException` 时剔除最后一个待用加固特性并重建重试，全部剔除仍失败才放行；
  handler 侧 `startDTD` / `resolveEntity` fail-closed 兜底在任意轮次不变（XXE 防护语义零弱化）。
- **回归测试**：`HardenedXmlReaderTest` 3 例（注入假工厂复现「延迟拒绝」分支锁定降级契约；
  普通合法 XML 全量特性解析；告警不打断解析）。
- **设备侧验证**：修复后 kp_min.xml（1 条）与含分组/TOTP 的 keepass_import_test.xml（2 条，
  中文标题 + otp 字段）均导入成功并落库（冷启动后列表可见）。

### 27.4 ISSUE-P2-19：设置页加密参数改由真实文件头下发（已整改闭环）

- **整改**：`SettingsPreferencesController` 注入 `DatabaseSession`，新增纯函数
  `databaseConfigFromHeader(KdbxDatabase)`，订阅 `databaseFlow` 把**真实文件头**映射到
  「密码库与加密」页：`cipherUuid` → 加密算法标签（AES-256-CBC / ChaCha20-Poly1305 / Twofish-CBC）、
  KDF UUID → `Argon2d` / `Argon2id` / `AES-KDF`、真实 Argon2 I/M/P、`compression` → GZip/无压缩；
  占位默认值全部清空（空值由 UI 显示「未设置」）。加密算法对话框标签与显示值统一词汇表
  （去除「AES-256 (KDBX 4.1)」错误绑定）。
- **静态文案纠偏**：解锁页副标题、设置主页副标题、关于页加密规格不再声明与单个文件不符的
  「KDBX 4.1 / Argon2d」事实（改为能力口径 KDBX 4 · Argon2 / AES-KDF）。
- **测试**：`DatabaseConfigHeaderMappingTest` 5 例锁定映射（AES 头显示 AES-256-CBC 而非 ChaCha20 等）。
- **设备侧验证**：页面显示 `AES-256-CBC (256-bit)` / `Argon2id` / `64 MB · 2 轮 · P=2`（此前
  `ChaCha20-Poly1305` / `Argon2d · 64 MB / 8 轮` 假值），与 `--verify` 解出的文件头逐项一致；
  Argon2 参数对话框回显同真值。

### 27.5 ISSUE-P2-20：导出取消/失败不再残留 0 字节文件（已整改闭环）

- **复核结论**：附件导出**功能正常**——关闭自动锁定后完整走通「SAF 另存 → 明文确认 → 写盘」，
  产物 md5（`21e7776188df217346fa104a40a6e60e`）与源文件一致。上会话 0 字节系「SAF 保存即建空文件 +
  第二道确认前被自动锁定熔断」的流程中断遗留，同时本批次实测 KDBX 导出在立即档下也复现同款 0 字节。
- **整改**（系统化，覆盖 4 个路径）：新增 `SafDocumentCleanup.deleteCreatedDocument`（best-effort
  `DocumentsContract.deleteDocument`）：① `SettingsExportController.exportAndWrite` 序列化失败与
  写盘失败两分支；② `EntryDetailAttachmentExporter` 三条失败分支；③ 附件导出确认对话框取消分支；
  ④ 明文 XML 导出确认对话框取消分支。
- **设备侧验证**：XML 导出确认框点「取消」后 `/sdcard/Download/` **不再出现** 0 字节 `passwords-export.xml`
  （修复前同路径残留空文件）；失败路径同样给出可见错误反馈（既有 exportFeedback 机制）。

### 27.6 ISSUE-P2-21（新发现）：「返回键锁定」开关从登记到接线（已整改闭环）

- **发现**：设备实测开启「返回键锁定」后主页按返回键不锁定；代码核查确认 `lockWhenNavigateBack`
  **没有任何消费方**（设置 → 持久化 → 投影齐全，行为层无接线），属「死设置」。
- **整改**（双处）：① `KeePasskeyApp` 主脚手架新增 BackHandler——`lockWhenNavigateBack` 开启且
  处于顶层路由时按返回键触发 `triggerLock("返回键锁定")`，先于 NavHost 组合以保持库列表内部
  （批量选择/搜索/子目录）返回处理优先；② 根因排查中发现**活动域与导航域 SettingsViewModel 的
  extendedSettings 内存快照互不同步**（导航域改开关、活动域永远读旧值）——把权威快照上移到
  `@Singleton ExtendedSettingsStore.settings`，控制器改共享流，任一页面的偏好改动全进程即时可见。
- **设备侧验证**：开启开关 → 主页按返回键 → 立即落在解锁页 ✅。

### 27.7 ISSUE-P3-59：文件路径 / 默认用户名真实下发（已整改闭环）

- **整改**：与 §27.4 同一批——`databasePath` 取自活动库记录（`VaultDatabaseInfo.path`）、
  `defaultUsername` 取自 `KdbxDatabase.defaultUserName`（Meta），经投影下发；
  UI 空值统一显示「未设置」占位（新增 `dbset_value_unset`，验收标准「不得留空白」）。
- **设备侧验证**：文件路径显示 `/data/user/0/com.keepasskey/files/passwords.kdbx`、
  默认用户名如实显示「未设置」（新库 Meta 为空）。

### 27.8 P3-60 / P3-61 / P3-62 复核结案

- **ISSUE-P3-60（搜索框注入不落字）→ 非缺陷结案**：本批次在开关注入上观察到同族现象
  （`input tap` 对部分 Compose Switch 不生效、须 `input motionevent DOWN/UP`），证实是**自动化注入
  路径限制**而非 App 缺陷；`VaultListTopBars` 的搜索框为标准 `BasicTextField` 直通绑定。
- **ISSUE-P3-61（未扫描徽标语义）→ 已整改闭环**：原观察属实——「弱密码」恒显「安全」、
  「重复密码」恒显「需注意」，与扫描状态无关，互相矛盾。整改：`HealthCheckUiState` 新增
  `hasScanned`，未扫描时两行徽标均为中性「未扫描」（outline 色 Security 图标），扫描后按实际计数
  显示「安全 / 需注意」。设备侧验证：未扫描两行均「未扫描」✅，扫描后「弱密码=安全（0）、
  重复密码=需注意（实测库内确有复用）」语义一致 ✅。
- **ISSUE-P3-62（诊断日志预览空）→ 行为符合设计结案**：开启「诊断日志」后执行一次导入（产生新
  WARN 事件）再刷新，预览即显示真实事件（本批次正是靠它定位 §27.3 的异常类型）；
  开关开启前发生的既有事件不回放属缓冲语义，非缺陷。

### 27.9 全量回归与过程留痕

- 全量 `test --rerun-tasks --max-workers=1`：**1382 例 / 0 失败 / 0 错误 / 13 跳过**
  （基线 1370 + 新增 12：写侧契约 4、HardenedXmlReader 3、头映射 5）。
- 交接文档 §3 未实测清单 **3.1 ~ 3.7 全部跑完**：3.3 四导入器全过（修复后）、3.4 KDBX/XML 导出
  产物逐字节/结构验证（XML 含全部条目与「模板」分组——`VaultExportCoordinator` 两种导出均不跳过
  模板分组，交接文档原表述更正）、3.5 子库挂载（KeePassXC 语料 + `111.keyx` 挂载解锁 14 条，
  列表只读分区正常标注「只读子库条目，无编辑或复制入口」）、3.6 Argon2/KDF 对话框回显真值、
  3.7 自动填充页如实反映系统服务未启用状态；解锁后常驻通知（`keepasskey_unlocked_status` 通道，
  ONGOING）确认存在。
- **方法论补充（已写回交接文档 §2）**：①开关状态判定以 DataStore/SharedPreferences 字节为地面真值，
  `uiautomator` 的 `checked` 属性可能陈旧；②Compose Switch 注入用 `input motionevent DOWN/UP`；
  ③对话框（如自动锁定倒计时）打开期间会吞掉后续注入点击，长流程每步 dump 定位；
  ④`adb shell` 传输二进制必须走 base64（CRLF 转换会损坏 KDBX，本批次实测复现）。
- **残余（转入 ACTIVE_ISSUES 继续跟踪）**：ISSUE-P3-63 升级为确认缺陷（导入亦触发列表陈旧，
  保留待专项批次）；新增 ISSUE-P3-65（TAN/UUID 完整性校验开关无持久化、无消费方）；
  自动填充 / Passkey 端到端填充与 Credential Manager 提供者接管未实测（需装浏览器测试页 +
  系统服务配置，见交接文档 §3.7）。

### 27.10 边界收口复核（同日追加）：P2-20 余下路径 / P2-21 双向拨动 / P1-13 官方客户端实解

> 针对 §27 首次归档后仍存在的三处复核覆盖边界逐一收口，全部为**整改后构建**上的真实设备/官方工具实测。

**① P2-20 余下三条清理路径（此前仅 XML 导出取消一条直接验证）**：
- **序列化失败路径**（密钥文件导出，会话未绑定密钥文件）：SAF 保存后点确认前的空文档被清理——
  Download 目录无 `keepasskey.keyx` 残留（修复前同场景实测残留 0 字节），且页面出现可见错误
  「操作失败：当前会话未使用密钥文件，无密钥文件可导出」✅
- **附件确认取消路径**：SAF 保存创建 0 字节 `(2)` 文件 → 点「取消」→ 文件**被删除**，源附件完好 ✅
- **写盘失败路径**（后台自动锁定=立即档，KDBX 导出 SAF 前台 8s 会话熔断后保存）：
  修复前该场景实测遗留 0 字节 `passwords.kdbx`；修复后同场景 Download **无任何残留** ✅
- 附件导出器内部异常分支（附件字节缺失/流不可得）无 UI 可达的自然触发器，与上述路径调用同一行
  `SafDocumentCleanup` 清理函数，以代码覆盖收口。

**② P2-21 跨 ViewModel 域同步的双向现场验证（此前仅验证预持久化开关后的锁定行为）**：
- 现场拨「关」（导航域安全页写入）→ 返回主页按返回键 → **不锁**（进程存活、无锁定跳转）✅
- 现场拨「开」（同一会话内再写入）→ 返回主页按返回键 → **立即锁定** ✅
- 全程无进程重启，证明导航域写入经 @Singleton 共享快照对活动域宿主即时可见（修复前宿主域永远读旧值）。

**③ P1-13 官方客户端实解（环境内最强互操作证据）**：本机经 winget 安装官方 **KeePassXC 2.7.12**，
以 `keepassxc-cli` 对本 App 写侧产物做三组对照实测：
| 产物 | P 编码/值 | keepassxc-cli（官方实现）结果 |
|---|---|---|
| 修复后导出（P=2） | UInt32 / 2 | ✅ 解锁成功并列出条目 |
| 诊断库（头 P=4、密钥按 P=2 派生——临时补丁制造的不一致文件） | UInt32 / 头4实2 | ❌ HMAC 不匹配（证明官方**确实读取 P 值并用于派生**） |
| 诊断库（派生与编码一致 P=4 非默认值） | UInt32 / 4 | ✅ 解锁成功（`ls` 输出 `[空]`） |
即：官方实现按 UInt32 语义正确消费本 App 写出的非默认 `P`；修复后写侧与官方客户端在非默认并行度下
完全互操作（P1-13 危害场景「非默认 P 官方解不开」在修复后不复现）。

**过程留痕（如实）**：
1. 收口期间自写的 DataStore 快速校验脚本存在**偏移错误**（取 `idx+26`，正确为 `idx+23`），一度误报
   「设置写入失效」——经临时插桩（Log.i）+ 正确解析器重读证伪：写入链路（回调 → 协程 → `edit` 完成 →
   落盘）全程正常。临时插桩已移除，工作树已还原。
2. TEMP-DIAG 补丁期间的诊断保存把测试库 `passwords.kdbx` 写成「头 P=4 / 密钥 P=2」不一致文件
   （即上表第二行），库不可解锁——**测试库数据均为一次性公开测试值**，已删除重建（同名新库 +
   `MasterPass2026`），诊断库文件已清理。解锁节流（`unlock_throttle`）按库文件名记账，重建库同名
   继承旧锁，二次清理节流后解锁正常。
3. 环境恢复：两处 TEMP-DIAG 补丁已还原（`git diff` 仅剩行尾噪音，已 checkout 还原），最终 APK 为
   干净构建；新登记 **ISSUE-P2-22**（Argon2 参数对话框「应用参数」无真实效果，见 ACTIVE_ISSUES）。

---

## §28 存量问题修复批次（2026-09-12）：P2-22 / P3-63 / P3-65 / P3-67

> 本批次目标「修复存量问题并模拟器实测通过」：AVD `Pixel_10`（Google APIs / Android 16 / x86_64，
> `emulator-5554`）全程实测。全量回归 `test --rerun-tasks --max-workers=1`：
> **1391 例 / 0 失败 / 0 错误 / 13 跳过**（基线 1382 → 1391，新增 9 例）。

### 28.1 ISSUE-P2-22：「Argon2 参数」对话框「应用参数」真实化（已整改闭环）

- **整改**：`SettingsPreferencesController.setArgon2Parameters` 从「仅回写 UI 内存回显」改为
  接入 `DatabaseSession.updateDatabaseMeta { … }` 更新活动库头变体字典 `I/M/P` 并立即
  `DatabaseSession.save()`——写侧 `KdbxFile.save` 本就以保存时 `header.kdfParameters`
  （含全新随机 salt）重派生加密密钥并写出新外层头，与官方 KeePass「KDF 参数保存时生效」
  语义一致。UI 回显不再自持状态：会话 `databaseFlow` 重发后由 P2-19 建立的头映射通道统一下发。
  无活动会话 / 库头非 Argon2 时如实 no-op（回显保持文件头真值，不产生假变更）。
- **契约测试**：新增 `app/src/test/.../settings/Argon2ParametersApplyTest.kt`（真实
  `DatabaseSession` + 真实时间轮询）：① 应用后内存头 `I/M/P` 与所选值一致；② 落盘文件以同一
  主密码重开成功且头参数一致；③ 错误主密码**无法**解开（证明密钥按新参数真实重派生，非只改头）；
  ④ 无活动会话时如实不生效。
- **设备侧验证（字节级地面真值，对照整改前 `P: raw=02000000` 且头不重写的实测）**：
  新建测试库 `p2test.kdbx`（Argon2id，默认 64MB·2轮·P2）→ 设置 → 密码库与加密 → Argon2 参数
  → 选「4 线程」→ 应用参数 → `run-as` 取回 `files/p2test.kdbx` 解析外层头 KdfParameters
  变体字典：**`P=4（UInt32）、M=67108864（64MB）、I=2、V=0x13`**，与所选值一致 ✅；
  `am force-stop` 冷启动后以同一主密码解锁成功（重派生语义正确）✅，设置页回显
  `64 MB · 2 轮 · P=4` 与文件头一致 ✅。

### 28.2 ISSUE-P3-65：「完整性校验」区假开关如实禁用（已整改闭环）

- **整改**（AC① 的「如实禁用」路径）：`DatabaseIntegrityCard` 两个开关（TAN 序列号 /
  数据库 UUID）改为 `enabled=false` + checked 恒 false + 行降透明度（`SettingsToggleRow`
  增加 `enabled` 形参），描述文案追加「（即将支持）」（中英双语）；整链移除无行为消费方的
  假 setter（`SettingsPreferencesController` / `SettingsViewModel` / 导航图接线 /
  `DatabaseSettingsScreen` 参数）及 `DatabaseConfigUiState`、`SettingsUiState` 中对应字段。
- **设备侧验证**：`uiautomator dump` 确认两行文案带「即将支持」、`checkable="true"` 元素为 0；
  对两开关位置注入点击后 re-dump 无任何 `checked` 变化（不可交互）✅。

### 28.3 ISSUE-P3-67：锁库事件导航守卫恢复真实语义（已整改闭环）

- **整改**：`KeePasskeyApp.kt` 的 `LaunchedEffect(autoLockManager)` 闭包改经
  `rememberUpdatedState(currentRoute)` 读取实时路由（effect key 恒定，闭包捕获值不随导航更新，
  此前恒捕获 `null` 使守卫恒真、已在解锁页时仍 `popUpTo(0)` 重复导航并清空已输入主密码）。
- **设备侧验证**（`p2test2.kdbx`，熄屏自动锁定开启）：
  - 场景①（非解锁路由锁库必达解锁页）：解锁进入库列表 → 顶栏「锁定密码库」→ 立即落在解锁页 ✅；
  - 场景②（解锁页收到锁库事件不重建页面）：解锁页主密码框输入 15 位 → 电源键熄屏（触发
    `triggerLock` 锁库事件）→ 唤醒 → 字段仍显示 `●●●●●●●●●●●●●●●`，**输入保留、无重建**
    （修复前该场景守卫恒真会 `popUpTo(0)` 重建并清空输入）✅；
  - 守卫恒真死代码消除由代码评审可证（`rememberUpdatedState` 语义）。
  - 注：AC① 所述 JVM 侧导航契约测试——锁库守卫位于宿主 Activity 组合层，`app` 模块无
    Compose UI 测试基建（AGENTS §6 已知限界），本批次以设备侧两场景实测替代覆盖。

### 28.4 ISSUE-P3-63：库内容变更后列表不即时刷新——根因定位与修复（已整改闭环）

- **根因定位（本批次完成，此前仅知「条件性」现象）**：设备复现 + 临时插桩（各输入流计数日志，
  已移除）证明数据层全程正常——导入落库后 `combine` 链路持续输出 `entries=1`，屏幕仍渲染空态。
  真因在**内存模型与落盘解析模型的父组语义不一致**：
  1. `GroupPathResolver.resolve(空路径)` 返回 `groupId=null`（语义「根分组」），导入条目以
     `parentGroupId=null` 落库；`SessionTreeEditor.updateOrAddEntry` 仅按 `null=根组` 决定
     **放置位置**、不回写对象——会话内存条目长期持有 null 父组；
  2. UI 投影按 `entry.groupId == 根组id` 过滤 → null 不匹配 → 根级条目（新建/导入）会话内不可见；
  3. 冷启动重新解析 XML 时按**结构归属**还原父组 id（`KdbxXmlGroupReader`）→ 条目可见——
     与「数据已持久化、仅内存列表陈旧、冷启动后完整可见」的全部实测现象吻合；
  4. 同族问题波及分组：`VaultTemplateFactory` 以 `parentGroupId=null` 构造「模板」分组整组保存，
     `updateOrAddGroup` 同样不回写 → 会话内不可见。
- **整改（三处）**：
  1. `SessionTreeEditor.updateOrAddEntry`：落树时把 `parentGroupId=null` 规范化为真实根组 id；
  2. `SessionTreeEditor.updateOrAddGroup`：放置分支对组自身及整棵子树做同一规范化
     （`normalizeParentRefs`——模板分组及其条目以 null 构造后整组保存的场景）；
  3. `GroupPathResolver.resolve`：以**根分组真实 id** 为匹配锚点与空路径返回值（顺带修复：
     此前顶级路径段以 null 为父锚点、永远匹配不上既有顶级分组，重复导入会建出同名重复分组），
     使重复导入的去重键（`EntryKey.groupId`）与规范化后的内存模型一致。
  - 规范化安全性与 P2-06 擦除契约兼容：`copy` 仅改 parentGroupId，字段 `ProtectedString`
    实例引用不变，`clearSupersededSensitiveData` 的身份集合判定不会误擦。
- **回归测试**：`database` 新增 `SessionTreeEditorParentNormalizationTest`（3 例：条目落根/
  落子组/既有更新；分组整树规范化/显式父组不受影响）；`app` 新增 `GroupPathResolverTest`
  （3 例：空路径返回根组真实 id/顶级路径按根组锚点匹配既有分组/未知顶级路径以根组 id 建组）。
- **设备侧验证**（`p2test2.kdbx`，修复后构建）：
  - 导入带分组路径的 XML（`云服务` 组 + 条目）→ 关闭报告 → 返回密码库 Tab → **「云服务」分组
    与组内条目「P3-63 修复验证条目」无需任何重载即时显示**（修复前同场景实测空态）✅；
  - 此前安装的「模板」分组（旧代码以 null 父组落库）在修复后亦正常显示 ✅。

### 28.5 全量回归与过程留痕

- 全量 `test --rerun-tasks --max-workers=1`（收尾后终跑）：**1393 例 / 0 失败 / 0 错误 /
  13 跳过**（+11：Argon2ParametersApplyTest 2、SessionTreeEditorParentNormalizationTest 5、
  GroupPathResolverTest 4；基线 1382 全数保留）。
- 调查期间的临时插桩（`VaultListViewModel` 各输入流 `Log.d` 计数）已完成使命后**整体移除**，
  工作树最终状态不含任何诊断代码（`LogHygieneTest` 全绿佐证）。
- 设备侧测试账号均为一次性公开测试值（`p2test*.kdbx` / `TestP2-2026!`）。

---

## §29 用户报告修复批次（2026-09-12）：P2-23 复合封印指纹解锁 / P3-68 重试节流可配置

> 本批次来源为用户报告：「输入密码和密钥解锁后，重新打开不能使用指纹解锁」（P2-23）、
> 「30 分钟后重试功能希望加开关与自定义时间且默认关闭」（P3-68）、
> 以及「关闭截屏限制后依然会有限制」（P2-09 修订）。全量回归
> `test --rerun-tasks --max-workers=1`：**1402 例 / 0 失败 / 0 错误 / 13 跳过**
> （FlagSecurePolicyTest 移除过时的临时豁免用例 8→4，净增 9 例）。

### 29.1 ISSUE-P2-23：带密钥文件解锁后指纹（快速解锁）不可用——复合封印（已整改闭环）

- **根因**：封印载荷格式只承载主密码（纯 UTF-8），`BiometricEnrollmentCoordinator.requestBiometricEnrollment`
  对携带密钥文件的解锁**整体跳过封印**（`if (hasKeyFile()) return`）→ `BiometricCredentialStorage`
  永无本库凭据 → 解锁页不提供指纹入口；且解封收尾 `completeBiometricUnlock` 仅以明文主密码调用
  `unlockActiveDatabase(chars)`，即便封印也无法解开复合密钥库——属载荷格式能力缺失。
- **整改**：
  1. 新增 `BiometricSealedPayloadCodec`（unlock 包，纯 JVM 可测）：版本化帧格式
     `魔数 "KPB1" | 版本 | 标志位(bit0=携带密钥文件) | 密码长度+UTF-8字节 | [密钥文件长度+字节]`；
     解码对不带魔数的载荷回落**历史格式**（纯主密码）——存量纯密码封印凭据零迁移成本继续可用；
     帧结构损坏 fail-fast，由既有「清陈旧凭据 → 下次主密码解锁重新封印」死循环恢复通道承接；
  2. `BiometricEnrollmentCoordinator`：移除带密钥文件即跳过的守卫，`hasKeyFile: () -> Boolean`
     改为 `keyFileBytes: () -> ByteArray?`（封印前快照克隆 + 编码后立即清零），
     复合因子一并封印；Keystore 密钥、强生物识别授权门控（P1-08 基线）与登记弹窗流程不变；
  3. `BiometricUnlockCoordinator.completeBiometricUnlock`：载荷经编解码器解析后以
     **两因子**送达既有 `unlockActiveDatabase(password, keyFileData)` 管线，不新造解锁通道；
  4. 敏感数据铁律保持：全程 `ByteArray`/`CharArray`，UTF-8 中间缓冲与解码产物用毕 `fill(0)`；
     密钥文件字节与主密码同受硬件 Keystore + `AUTH_BIOMETRIC_STRONG` 门控，不扩大攻击面。
- **验收证据（JVM 单测）**：
  - `BiometricSealedPayloadCodecTest`（9 例）：复合帧往返（含 emoji 主密码 + 257 字节密钥文件）、
    纯密码往返、魔数/版本/标志位布局、**历史格式兼容解析**、未知版本与长度越界 fail-fast、
    `wipe` 清零语义、空密钥文件按未携带语义、decode 不改调用方数组；
  - `UnlockViewModelBiometricAutoPromptTest` 新增「复合封印载荷解封后以两因子送达既有解锁管线」：
    生产同源编解码器 + 真实 JDK AES-GCM 封印 → `handleBiometricResult(Success)` →
    Recording 仓库断言主密码与密钥文件因子**逐字节原样**送达且发出解锁成功事件；
    既有「生物识别成功后经既有解锁管线解锁」用例（历史格式载荷）继续全绿 = 向后兼容回归锁；
  - 登记链路的设备侧弹窗/封印环节依赖 `FragmentActivity` + 硬件 Keystore（AGENTS §6 已知限界，
    `app` 模块无 androidTest 源集），JVM 单测对两条路径均 fail-closed 无法区分——整改有效性由
    「守卫移除（代码可证）+ 载荷编解码/解封链路全绿（数据通路可证）」共同承载；
    真机指纹端到端待补设备侧实测（与 §6 已知限界同源）。

### 29.2 ISSUE-P3-68：解锁失败重试锁定可配置——总开关 + 自定义最长锁定时长（已整改闭环）

- **整改**：
  1. `UserSettings` 新增 `unlockThrottleEnabled`（**默认 false**，按用户裁决出厂关闭重试锁定；
     需要防爆破的用户可在设置页显式开启）与 `unlockLockoutMaxSeconds`（默认 **1800**，
     合法域 [60, 86400]，仓库层写入 coerce）；
     `SettingsRepository` / `RealSettingsRepository`（DataStore 键） / `FakeSettingsRepository` 同步接线；
  2. `UnlockThrottlePolicy.backoffMillisFor` 增加 `ThrottleConfig` 参数（缺省值 = 现行编译期常量
     行为，既有调用与测试零改动）：开关关闭恒不锁定，封顶值随配置；
  3. 新增 `ThrottleConfigSource` 接口 + `UnlockThrottleConfigProvider`（进程级单例，独立协程收集
     设置流缓存 `@Volatile` 快照，节流同步路径零挂起读取）；`UnlockThrottleManager` 构造注入
     （nullable 缺省 null 仅用于 JVM 单测；生产经 `SecurityModule` `@Binds` 绑定）——
     **主解锁（`UnlockViewModel`）与子库挂载（`ChildDatabaseSessionManager`）两条路径自动同时生效**；
  4. 记录完整性 fail-closed 处置（ISSUE-P3-54 防篡改语义）**不受开关影响**，恒按上限锁定；
  5. 设置页「设备解锁与安全 → 自动锁定规则」新增「解锁失败重试限制」开关（关闭时长行随之隐藏）
     与「最长锁定时长」单选弹窗（1/5/15/30 分钟、1/6/24 小时），中英文案齐全；语义为指数退避
     的**封顶值**（连续失败越多锁得越久，至多此时长），弹窗描述文案已明示。
- **验收证据（JVM 单测，`UnlockThrottleManagerTest` 新增 4 例）**：
  - 开关关闭：超阈值多次失败不锁定、预置锁定态被放行（计数保留）；
  - 开关重开：再失败一次即按累计次数重新进入退避锁定；
  - 自定义封顶 60s：第 5 次失败退避 30s（未触顶）、第 6 次起压至 60s（原策略 120s），
    管理器侧 `registerFailure` 同步生效；
  - 完整性失效（`integrityIntact=false`）在开关关闭时仍 fail-closed 锁定（防篡改不可旁路）；
  - 全量回归 1402 例全绿（见批次头），既有节流用例（缺省配置路径）零改动全数保留。

### 29.3 FLAG_SECURE 防截屏设置真实生效（语义修订，消除假开关）

- **根因**：原 ISSUE-P2-09 采用「临时豁免模型」——开关关闭后在解锁态下**默认仍强制遮蔽**，
  仅当存在尚未过期的 5 分钟临时豁免才解除；而 UI 侧「关闭防截屏风险确认」对话框的确认回调
  **从未调用** `requestTemporaryExemption`，导致即使在确认弹窗点了「仍要关闭」，
  底层的 FlagSecureGuard 依然恒强制遮蔽（假开关，用户无法真正截屏）。
- **整改**：
  1. `FlagSecurePolicy` 修订为**开关即生效**模型：锁定态无条件强制遮蔽（fail-closed，
     主密码输入与 Recents 预览绝不泄露）；解锁态只看用户开关——开启强制遮蔽，关闭**真实解除**；
  2. `FlagSecureGuard` 移除过时的内存临时豁免状态机（`exemptionUntilMs`/`Job` 等全部下架），
     代码极简化为仅监听 `flagSecureEnabled` 与 `isSessionLocked` 两流；
  3. `FlagSecurePolicyTest` 单元测试同步更新为新语义断言；
  4. 设置页风险确认对话框保留（关闭前须用户二次确认，确认后真实写入偏好并解除遮蔽）。

---

## §30 外部安全审计核实与整改批次（2026-09-12）：P3-69 ~ P3-72

> **来源**：外部安全审计报告（共 9 条，含 1 条「高危」）。处置原则：先逐条源码核实真伪与前提，
> 再对**确实成立且可整改**的条目闭环；证伪条目如实作废，产品裁决条目维持不动。

### 30.1 审计 9 条逐项核实结论（2026-09-12，静态源码核实）

| 审计条目 | 核实结论 | 处置 |
|---|---|:---:|
| #1 `Cargo.lock` 缺失（审计列为**高危**） | **证伪（已入库并跟踪）** | 无动作 |
| #2 解锁节流出厂默认关闭 | 事实属实，但为 2026-09-12 用户明示裁决 | 维持现状 |
| #3 CodeQL 未覆盖 `java-kotlin` | 属实，已登记为已接受风险（ISSUE-P3-58） | 维持上游跟踪 |
| #4 Wrapper 第三方镜像 + 无 SHA-256 | 属实 | **整改（30.2）** |
| #5 `charsToUtf8` 中间 `ByteBuffer` 未清零 | 属实 | **整改（30.3）** |
| #6 ZXing 扫码窗口无 `FLAG_SECURE` | 属实 | **整改（30.4）** |
| #7 未接入 Play Integrity API | 属实 | 评估不采纳（30.5） |
| #8 自动填充 / Passkey 缺真机 E2E | 属实（= ISSUE-P3-66） | 维持（外部环境依赖） |
| #9 许可证元数据不一致 | 属实（信息级） | **整改（30.6）** |

**#1 证伪证据（2026-09-12）**：`git ls-files --error-unmatch crypto/src/main/rust/Cargo.lock` 命中
（exit 0，**已跟踪**）；`git check-ignore` exit 1（**未被忽略**）；`crypto/src/main/rust/.gitignore:1`
明确注明「Cargo.lock 保留入库以锁定依赖树」；`git log --reverse` 首次入库为 `379f1e4`（2026-09-09）。
故 [build.yml](../.github/workflows/build.yml) 的 `cargo test --locked` **不存在**审计所述缺失隐患。

**#2 说明**：`SettingsRepository.kt:53` / `RealSettingsRepository.kt:113` / `UnlockThrottle.kt:199`
的默认 `false` 系 **2026-09-12 用户裁决**（§29.2），非疏漏；本次**不予改动**。

### 30.2 ISSUE-P3-69：Gradle Wrapper 分发内容完整性锁定

- **整改**：
  1. `gradle/wrapper/gradle-wrapper.properties` 新增 `distributionSha256Sum`
     = `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`
     （Gradle **9.7.1 `-bin` ZIP** 官方校验和，来源 <https://gradle.org/release-checksums/>，2026-09-12 取得）；
  2. `.github/workflows/build.yml` 的 `fast-gate` 新增 `gradle/actions/wrapper-validation`
     （复用仓库既有 pin `9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0`）。
- **前提实测（防误报）**：本仓 `gradle/wrapper/gradle-wrapper.jar` 的 SHA-256
  = `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`，与 Gradle 官方 9.7.1
  Wrapper JAR 校验和**逐位一致** → 启用 wrapper-validation 不会误报。
- **说明**：镜像源保留（国内可访问性），安全性由内容哈希锁定承担——镜像篡改/劫持将被 Wrapper 拒绝。

### 30.3 ISSUE-P3-70：`KdbxKeyDerivation.charsToUtf8` 中间 `ByteBuffer` 清零

- **根因**：`StandardCharsets.UTF_8.encode(charBuffer)` 内部新建的 `ByteBuffer` 承载明文口令字节，
  原实现仅 `get()` 拷贝出 `bytes` 返回，其底层堆数组**未清零**（与 `ProtectedString.kt:224-225`
  的 P0-7 处理不一致）。ISSUE-P3-55 修的是**返回副本**在调用方清零，未覆盖该中间缓冲。
- **整改**：`charsToUtf8` 取用后追加 `if (byteBuffer.hasArray()) Arrays.fill(byteBuffer.array(), 0.toByte())`。

### 30.4 ISSUE-P3-71：ZXing 扫码取景窗口纳入 FLAG_SECURE

- **根因**：扫码走 zxing 默认 `CaptureActivity`（`ScanContract()` 未指定自定义窗口），该窗口游离于
  `FlagSecureGuard`（仅 attach 至 MainActivity / `BaseCredentialActivity` 体系）之外 → 含密钥种子的
  TOTP 二维码取景画面可被截屏 / 录屏 / 多任务缩略图捕获。
- **整改**：
  1. 新增 `app/src/main/java/com/keepasskey/app/security/SecureCaptureActivity.kt`——继承 zxing
     `CaptureActivity`，`onCreate` 施加 `FLAG_SECURE` 与 `setHideOverlayWindows(true)`（与站内其余
     敏感窗口一致的叠加防护）；
  2. `AndroidManifest.xml` 显式声明该 Activity（属性对齐库默认声明，`exported=false`）；
  3. `EntryEditPickers.kt` 扫码选项追加 `options.setCaptureActivity(SecureCaptureActivity::class.java)`。

### 30.5 审计 #7（Play Integrity）评估结论：不采纳

- 本 App 以 GPL-3.0 开源、支持侧载分发；Play Integrity API 依赖 Google Play 服务与 Play 后端校验，
  对侧载/无 GMS 设备不可用，且会引入对 Google 闭源服务的运行时依赖，与离线密码管理器的分发模型不符。
- 现有 `RuntimeIntegrityDetector` 已采用 **fail-closed**（首次扫描完成前 `UNDETERMINED` 保守策略），
  与本地 Root/钩子探测共同构成纵深防御。故**本次不接入**，维持本地检测；如未来上架 Play 渠道，
  可作为渠道专属增强另行评估。

### 30.6 ISSUE-P3-72：Rust crate 许可证元数据注释对齐

- **整改**：`crypto/src/main/rust/Cargo.toml` 在 `license` 字段上方注明：本 crate 为 `publish = false`
  的**内部组件**，其源码随主工程（根 `LICENSE` = GPL-3.0）整体分发，对外生效许可即主工程许可；
  `Apache-2.0 OR MIT` 仅为该 crate 源码的 Cargo 元数据声明、与主工程许可单向兼容。
  **不改动**主工程许可与 `license` 字段值（避免影响传递声明）。

### 30.7 批次验收证据（2026-09-12）

- **单元测试**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL**
  （114 tasks executed；本批次未新增/删除用例，沿用 1402 例基线）。
- **稳定版构建**：`.\gradlew.bat assembleRelease` → **BUILD SUCCESSFUL**（R8 混淆 + 资源收缩 +
  v2/v3/v4 签名）。产物完整路径：
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`（已签名，15,379,879 B，
  含 v4 签名 `.idsig`）。
- **合并清单核验**：release 合并清单已含 `.security.SecureCaptureActivity` 声明。
- **关联提交**：本批次文档与代码为**同一次** `git commit`（提交主题以 `ISSUE-P3-69 ~ P3-72` 引用）。

---

## §31 文档类存量整改批次（2026-09-12）：P3-75 / P3-77

> **来源**：`ACTIVE_ISSUES.md` 中「参考项目对比分析」产出的 P3 项。两条均属**文档/评估型**交付：
> 不新增代码路径，结论与承诺须与代码事实逐条可核。

### 31.1 ISSUE-P3-75：独立隐私政策文档 + noNet 构建变体评估

- **交付物**：新增 [`docs/Privacy-Policy.md`](Privacy-Policy.md)（AC①、AC③）。
  逐条列明并经代码核实：无遥测 / 分析 / 广告 / 崩溃上报 SDK（`app/build.gradle.kts` 依赖列表，全仓检索
  `firebase`/`analytics`/`crashlytics` 等仅命中文案与供应链抑制文件）；网络访问仅限「用户启用的云同步」
  与「默认关闭的 HIBP k-匿名查询」（`ExtendedSettings.breachCheckEnabled` 默认 `false`；
  `SettingsHealthController` 关闭态零外联；`HibpRangeClient` 仅送 SHA-1 前 5 位）；全站 TLS-only
  （`network_security_config.xml` + OkHttp TLS-only ConnectionSpec）；日志脱敏（`AppLog` release 仅留异常类名）。
- **AC② 结论：评估后暂不实施 `productFlavors { noNet }`**，理由（见政策 §7）：
  1. flavor 化会使 `assembleRelease` 产物由 `app-release.apk` 变为 `app-<flavor>-release.apk`，
     直接违反 `AGENTS.md` §3.8 的稳定版产物路径契约并波及 CI；
  2. 同步 / 泄露检测已深入导航、Hilt、WorkManager 与自动填充链路，flavor 裁剪易在「看似禁网、
     实则留旁路」方向引入隐蔽缺陷；
  3. 默认配置下本应用本就不联网，禁网变体几无额外保护收益。
  替代路径已写入政策（如需硬性禁网，建议独立分支 / 渠道维护）。
- **禁止项核对**：政策未写入任何与实现不符的承诺；noNet 结论为「评估不实施」而非「已实施」。

### 31.2 ISSUE-P3-77：同步层记录级密钥承诺威胁建模

- **交付物**：新增 [`docs/同步层记录级完整性威胁建模.md`](同步层记录级完整性威胁建模.md)（AC①、AC③）。
  在 Assume Breach（云端不可信、无主密钥）模型下，拆分「跨记录 / 跨上下文置换」为
  **条目置换 / 块级置换 / 跨路径整文件置换**三类，逐类给出既有机制的覆盖边界。
- **关键结论**：
  - KDBX 是**单体加密流**，条目非独立 AEAD 记录；块 HMAC 的**块索引并入块密钥**
    （`BlockHmac.compute`：`HMAC_{SHA512(LE64(index)‖hmacKey64)}(LE64(index)‖LE32(size)‖data)`），
    故 mdbx 所关注的 key-commitment 置换攻击面在 KDBX 模型中**不成立或已被覆盖**；
  - **不引入**记录级 AEAD 承诺、**不改动 KDBX 字节布局**（避免破坏与 KeePass 2.x / KeePassXC 互操作，
    亦为本条 AC 明令禁止）；
  - **不明示引入**「远端路径 + 版本 / epoch + 内容哈希」MAC 绑定（AC② 条件未触发）：现有同步 MAC
    认证对象是**本地**高水位状态文件（本地文件级攻击者不在模型内），对远端混淆无直接拦截力，
    且「同内容多路径」是合法场景、绑定会引入误报；
  - **残余风险（明示接受）**：跨路径整文件混淆为**低危**（不触及机密性 / 完整性），由内容可见异常与
    三哈希 / 防回滚链共同限制；重评估触发条件（转 per-record 架构）已写入文档 §5。
- **禁止项核对**：未改动 KDBX 字节布局；未照搬 mdbx 草稿规范作为交付基线（仅作方向参考）。

### 31.3 批次验收证据（2026-09-12）

- **单元测试**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL**
  （本批次为纯文档交付，未改动代码，沿用 1402 例基线）。
- **稳定版构建**：`.\gradlew.bat assembleRelease` → **BUILD SUCCESSFUL**。产物完整路径：
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`。
- **关联提交**：本批次文档与代码为**同一次** `git commit`（提交主题以 `ISSUE-P3-75 / P3-77` 引用）。

---

## §32 存量功能整改批次（2026-09-12）：P3-73 CSV 导入 / 导出扩充

### 32.1 AC① CSV 解析器覆盖扩充（LastPass / Chrome / Edge）

- **开工核实**：`BrowserCsvImporter` 已按表头名映射 `name/url/username/password/note` 及 Bitwarden
  的 `login_*` 别名 → **Chrome / Edge 已被覆盖**，无需新增解析器；真正缺口是 **LastPass** 的
  `extra`（备注）与 `grouping`（分组路径）列未被映射。据此在既有 `@IntoSet` 开闭框架内**扩展同一边界**
  （不新增数据源枚举、不改调用方），符合 AC① 的「追加 CSV 解析器（不改调用方）」意图。
- **整改**（`app/src/main/java/com/keepasskey/app/data/importer/BrowserCsvImporter.kt`）：
  1. `NOTE_HEADERS` 增补 `extra`（LastPass 备注列）；
  2. 新增 `CsvColumnRole.GROUP` 与 `GROUP_HEADERS = {grouping, group, group_name, folder}`，
     单元格以 `\` 或 `/` 分层解析为 `groupPath`（`CsvCells.groupPath` → `ImportedEntry.groupPath`）；
  3. 空分组列 → 空路径（落至根分组），Chrome / Edge 行为不变。
- **回归用例**（`BrowserCsvImporterTest`）：新增「LastPass 表头 extra/grouping 映射」「斜杠分层与空分组落根」
  两例；并将原「未识别多余列」用例的列名由 `extra` 改为 `ignored_col`（因 `extra` 已升格为备注别名，
  原用例前提失效，就地修正）。

### 32.2 AC② 通用明文 CSV 导出 + 强制二次确认

- **新增导出器**（`database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt`）：
  列固定 `name,url,username,password,notes,group`，RFC 4180 引号语义（含分隔符 / 引号 / 换行的字段整体
  加引号、内部 `"` 双写转义），分组列以 `\` 分层（与导入侧互为往返）；逐行流式写出，不构造整份明文字符串。
- **接线**：`VaultRepository.exportVaultCsvBytes()` → `VaultExportCoordinator`（`Dispatchers.Default`）→
  `RealVaultRepository` → `SettingsExportController.exportVaultCsvTo` → `SettingsViewModel` →
  设置页导出对话框按钮 + **明文二次确认弹窗**。
- **风险门禁**：新增 `ExportArtifactKind.PLAINTEXT_CSV` 并纳入 `ExportConfirmationPolicy.riskOf` 的
  `PLAINTEXT` 分支 → 未确认一律 fail-closed（不放行任何字节）；确认文案 `dbset_export_csv_plain_warn_title/
  message` **显式写明「明文 CSV」**（中英双语文案齐备）。
- **对称清理**：取消 / 未确认分支复用 `SafDocumentCleanup` 删除 SAF 已创建的空文档（ISSUE-P2-20 同语义）。

### 32.3 AC③ 新增单测

- `database`：`KdbxCsvExporterTest`（4 例：表头与根条目、引号 / 逗号 / 换行转义、子分组 `\` 分层、空分组与明文口令）。
- `app`：`BrowserCsvImporterTest` 增 2 例；`ExportConfirmationPolicyTest` 增 1 例
  （`PLAINTEXT_CSV` 归入明文风险且未确认不放行）。

### 32.4 批次验收证据（2026-09-12）

- **单元测试**：`.\gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL**（全模块 `testDebugUnitTest`
  全绿；本次新增/改动用例均通过）。
- **稳定版构建**：`.\gradlew.bat assembleRelease` → **BUILD SUCCESSFUL**（R8 混淆 + 资源收缩 + 签名）。
  产物完整路径：`D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`。
- **关联提交**：本批次文档与代码为**同一次** `git commit`（提交主题以 `ISSUE-P3-73` 引用）。

---

## §33 产品裁决：不排期 / Won't Do（2026-09-12）

> **本节性质**：以下条目**不是「已整改」**，而是经**产品裁决不排期**的治理记录，移入本节以维持可追溯、
> 防止未来重复评估，并使 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) 只保留真正的待办。
> **裁决口径（须如实留痕，勿误读）**：裁决依据**不是**「经典 KDBX 软件没有这些能力」——经核对，
> P2-24/25/26/74 所述能力在参考项目中**确实存在**（各条目原文的「参考做法」即来源于对标），
> P2-27/23/58/66 则是**验证覆盖**而非功能。真正依据有三：
> ① **产品边界**——本仓定位为 KDBX v4 + WebDAV/S3 的**本地优先**管理器，不追求 keepass2android 式
> 「全协议聚合 / 全特性对齐」；② **无实际用户需求驱动**；③ **部分条目依赖本地不具备的外部环境**。

### 33.1 裁决清单

| 条目 | 原优先级 | 类型 | 裁决理由 | 重新评估触发条件 |
|---|:---:|---|---|---|
| **P2-25** S3 上传/下载流式化 | P2 | 性能（大库） | 收益/代价倒挂：`SyncEngine` 为整文件字节模型，仅改 Provider 拿不到「不产生整库内存峰值」；SigV4 需预知载荷 SHA-256，流式直传受限（`UNSIGNED-PAYLOAD` 削弱签名，被该条 AC 明令禁止） | 出现真实「大库 + S3」OOM 反馈时，与 P2-24 合并为「内存专项」 |
| **P2-26** SFTP 同步后端 | P2 | 功能覆盖 | 产品边界：WebDAV 已覆盖绝大多数自建 NAS；不追求全协议聚合；引入 SSH 依赖与 host key / 密钥管理会扩大攻击面 | 产品明确要求 SFTP 且接受依赖与密钥管理成本 |
| **P2-27** AssistStructure 快照回归 | P2 | 验证覆盖 | 外部环境依赖：需在真机采集真实应用/浏览器快照；**主动接受该解析层未验证风险**（§24 / §26 已有先例） | 具备真机采集环境 |
| **P3-23** arm64 真机 instrumented 验证 | P3 | 验证覆盖 | 外部资源依赖：本机无 arm64-v8a 镜像、无真机连接；真实语料已入库、AC② 已闭环 | 取得 arm64 真机 |
| **P3-58** CodeQL Kotlin 覆盖 | P3 | 静态分析覆盖 | 上游阻塞：CodeQL 不支持 Kotlin 2.4.20；**不得为覆盖回退 Kotlin 版本**（本仓 2.4.20 承载 CVE-2026-53914 真修复） | 上游抽取器支持 ≥ 2.4.20 |
| **P3-66** 自动填充/Passkey 端到端实测 | P3 | 验证覆盖 | 外部环境依赖：需系统凭据服务托管 + 含登录表单的浏览器/测试页；**主动接受端到端未验证风险** | 具备实测环境 |
| **P3-74** 条目模板机制 | P3 | 体验增强 | 无需求、非缺陷；纯产品增强且工作量大 | 产品排期 |

### 33.2 说明与风险留痕

- **P2-25 / P2-26 / P3-74 属「功能/性能增强」**：不做**不影响正确性与安全**，仅影响极端规模或特定后端下的体验与覆盖。
- **P2-27 / P3-23 / P3-58 / P3-66 属「验证覆盖」**：不做 = **主动接受对应面未验证的风险**，
  **不等于「该面已无问题」**。本仓 §24 / §26 已两次证明「宿主 JVM 过、Android 运行时挂」，
  该风险应在后续具备真实环境时**优先回补**。
- 本节为**产品裁决**：若产品目标变化（如新增 SFTP 后端诉求、上架渠道要求更严的完整性/设备侧验证），
  应按上表「重新评估触发条件」重启评估，而非默认永久关闭。
- **保留待办**：**ISSUE-P2-24**（附件磁盘缓存，已留存分阶段方案）与 **ISSUE-P3-76**（输入法个性化学习，
  框架阻塞）仍留在 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) 跟踪，未纳入本节裁决。
