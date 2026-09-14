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
| §34 | app 设备侧验证骨架与导入解析回归 | ISSUE-P2-27 / P3-66（部分收窄） |
| §35 | ISSUE-P2-24 大附件磁盘缓存池（阶段 1/2/3 全量落地） | ISSUE-P2-24 |
| §36 | ISSUE-P2-27 设备侧验证缺口收口（app + sync） | ISSUE-P2-27 |
| §37 | 工程整洁与文档准确性收口 | 工程整洁 / 文档准确性 |
| §38 | KDBX 互操作与安全整改批次（P0×3 / P1×6 / P2×14 + 文档纪律） | ISSUE-P0-05~07 / P1-16~21 / P2-28~41 / P3-81 |
| §39 | 红队攻击路径批次处置归档（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P1-22~24 / P2-43~47 / P3-82~85 |
| §40 | 外部安全审计报告退役批次（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P2-48 ~ P2-60 / P3-86 ~ P3-97 |
| §41 | 敏感数据流审计报告退役与分流（`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`） | 转登 ISSUE-P2-61 ~ P2-74 / P3-98 ~ P3-115 |
| §42 | 威胁建模与架构评估报告退役批次（`THREAT-MODEL-AUDIT-d32f3e7.md` 退役 + 存量项转登） | 转登 ISSUE-P2-76 ~ P2-79 / P3-116 ~ P3-124 |
| §43 | 安全问题与整改方案报告退役批次（`SECURITY_AUDIT_REMEDIATION.md` 退役 + 附录 A–F 留存） | 处置归档（正文 32 项核对无缺口，附录 A–F 留存 §43.3 ~ §43.8） |
| §44 | P0 双项整改批次：字段引用消费点白名单 + 同步崩溃面遏制 | ISSUE-P0-08 / ISSUE-P0-09 / ISSUE-P2-75 |
| §45 | P1 双项整改批次：确认页调用方归属与首次绑定授权 + 剪贴板口令面引用敏感通道 | ISSUE-P1-24 / ISSUE-P1-25 |
| §46 | P1 双项整改批次：软件级 Keystore 快速解锁降级确认 + 重打包威胁告知留痕 | ISSUE-P1-22 / ISSUE-P1-23 |
| §47 | 设备侧真机基线批次：两条「模拟器环境假设」用例整改 + arm64 真机全量实测 | 设备侧用例缺陷（无编号） |
| §48 | 存量安全整改批次：KDF 预算 + TOTP 保护 + 剪贴板闭环 + 明文持有者锁观察者 + 换库前置释放 + 附件引用预算 + 完整性门控对称化 + Passkey 归属与验证绑定 | ISSUE-P2-48 / P2-51 / P2-53 / P2-61 / P2-63 / P2-65 / P2-72 / P2-76 / P2-77 / P3-84 / P3-109 / P3-117（+ P2-49 AC①③ 进展） |

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

---

## §34 app 设备侧验证骨架与导入解析回归（2026-09-12）

> **动机**：本仓 `app` 模块此前**没有 `androidTest` 源集**，导致解锁 / 自动填充 / 通行密钥等核心链路
> 从未在真实 Android 运行时被验证；而 §24（ISSUE-P1-12 KDBX XML 在 Android 全量失败）与
> §26（ISSUE-P0-04 字段引用正则在 Android ICU 非法致崩）已**两次**证明该类「JVM 全绿、Android 挂」
> 缺陷会真实逃逸。本节为**收窄该盲区的第一步**（对应 §33 中 P2-27 / P3-66 的解析层部分）。

### 34.1 交付

1. **`app` 设备侧源集接线**（`app/build.gradle.kts`）：新增
   `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` 与
   `androidTestImplementation`（`androidx.test.ext:junit` / `androidx.test:runner` / `coroutines-test`，
   均复用既有版本目录）。
2. **首个设备侧用例集** `app/src/androidTest/.../data/importer/ImporterAndroidRuntimeTest.kt`（3 例）——
   把**导入解析**放回真实 Android 运行时执行（XML 解析器 / ICU 正则 / 字符集均是平台差异面）：
   - KeePass XML 导入完整字段映射；
   - 浏览器 CSV 按表头映射（列序无关）；
   - 非 `KeePassFile` 根 **fail-closed**。

### 34.2 验收证据（2026-09-12，x86_64 模拟器）

- **编译**：`.\gradlew.bat :app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL**。
- **设备侧执行**：`.\gradlew.bat :app:connectedDebugAndroidTest`（`emulator-5554`，**x86_64 / API 36.1 /
  google_apis**）→ **BUILD SUCCESSFUL**；结果 XML `app/build/outputs/androidTest-results/connected/debug/`
  摘要：`tests="3" failures="0" errors="0" skipped="0"`（**真实执行，非跳过**）。
- **环境说明**：使用 `Pixel_10` AVD 冷启动 + `-wipe-data`（首次尝试因模拟器存在旧签名残留报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，清理并以干净实例重跑后通过——如实留痕）。

### 34.3 边界与后续

- 本节仅覆盖 **app 导入解析层**；`app` 端到端（自动填充域解析、解锁、Passkey、通知）与 `sync` 仍无设备侧覆盖。
- **arm64 原生加密内核验证仍缺**：本机仅 x86_64 镜像，arm64 镜像在 x86_64 主机上属翻译模拟（未执行）。
  该缺口对应 §33 的 ISSUE-P3-23，维持「不排期/外部依赖」。
- **关联提交**：本批次代码与文档为**同一次** `git commit`（提交主题以 `ISSUE-P2-27 / P3-66` 引用）。

---

## §35 ISSUE-P2-24 大附件磁盘缓存池（2026-09-12，阶段 1/2/3 全量落地）

> **闭环声明**：`ACTIVE_ISSUES.md` 中 ISSUE-P2-24 的正文（含「为什么本次不落半成品」评估与分阶段方案）
> 已整条移入本节；该条目从待办清单移除。

### 35.1 问题与设计

- **问题**：附件字节「内层二进制池常驻 + 逐附件副本」双份驻留 GC 堆，大附件库存在 OOM / GC 压力。
- **设计**：分离「逻辑引用」与「物理字节」——超过阈值（默认 **1 MiB**，可配）的附件在解析期即
  **流式落盘**，池中只保留 store key；[KdbxAttachment.data] 按需读回**独立副本**，
  别名隔离契约（ISSUE-P3-07）逐条保持。
- **关键取舍（诚实留痕）**：落盘附件的字节由**多个引用者共享**（去重复用同一 store 条目），
  故 [KdbxAttachment.clear] 对落盘项**不动作**（清零会连带损坏其它引用者）——
  其生命周期改由会话锁定时的 `BinaryStore.clear()` 统一收口。经全仓核实**生产代码无 `clear()` 调用方**
  （仅测试使用内存副本路径），故该语义变化无生产影响。

### 35.2 交付清单

1. **`core`**：新增 `BinaryStore`（`store` / `storeFromStream` / `load` / `openStream` / `sizeOf` / `clear`）、
   `BinaryStorePolicy`（阈值策略）、`BinarySource`（附件字节来源抽象）；`KdbxAttachment` 增可选
   `source: BinarySource?`（构造签名向后兼容）与 `size` / `openStream()`。
2. **`database`**：`InnerHeader.BinaryItem` 支持落盘引用（`data` 按需读回、`size` 不触发读取、
   `contentHash()` **流式**计算且与 `Arrays.hashCode(byte[])` 逐位等价、`writeTo` 流式写出、`withFlags` 零读取改标志）；
   `InnerHeader.deserialize(stream, store?, threshold)` 大字段**流式落盘**（长度/条目数/累计字节数三重守卫保留）、
   `serialize` 流式写出；`KdbxXmlBinaryNode` 落盘项不再 `copyOf`（挂引用）；`KdbxBinaryDeduplicator`
   指纹改为 `(flags, size, 内容哈希)` + **同指纹碰撞时流式逐字节复核**（绝不误合并），并复用落盘 store key；
   `KdbxFile.load(..., binaryStore = null)`（`null` → 旧行为逐字不变）。
3. **`app`**：`FileBinaryStore`（`cacheDir/attachments`，实现 `BinaryStore` + `SessionLockObserver`）；
   `SessionOpener` / `DatabaseSession(binaryStore)` 接线；`DatabaseModule` 注入并注册锁库观察者；
   `VaultEntryMapper` 改用 `attachment.size`（**不再把整池 map 成字节数组**）、`VaultEntrySecretReader`
   改用 `attachment.data` 按需读取。
4. **`sync`**：`SyncCache` 增 `writeCacheStreaming` / `openCacheStream` / `cacheSize`
   （复用既有 0600 / 0700 落盘基线，供 `FileBinaryStore` 组合）。

### 35.3 验收证据

| AC | 内容 | 证据 |
|:--:|---|---|
| ① | >1 MiB 附件走磁盘缓存、不整入内存 | `InnerHeaderBinarySpillTest`（7 例）+ `KdbxFile` 往返用例；设备侧 `DatabaseSessionAndroidRuntimeTest` 实测落盘 |
| ② | 锁定 / 关闭时对称清理 | `SessionLockObserver` 接线 + 设备侧用例断言锁定后缓存目录清空 |
| ③ | 权限 0600 / 0700 | `SyncCacheAndroidRuntimeTest`（设备侧 **POSIX 实测**，非降级分支） |
| ④ | KDBX 字节语义不变 | `KdbxAttachmentAliasIsolationTest` **4 例原样通过（未改写）**；`KdbxBinaryDeduplicatorTest` 3 例通过；`InnerHeaderBinarySpillTest` 往返逐字节等价 + 去重/池一致性 |

- **单测**：`.\gradlew.bat test` → **BUILD SUCCESSFUL**；debug 单测 **1423 例 / 0 失败 / 0 错误 / 13 跳过**
  （本批次新增 14 例：`core` 3 / `database` 7 / `sync` 4）。
- **设备侧**：见 §36。

### 35.4 边界

- KDBX 对象树**其余部分**仍整体驻留内存（本次只解决附件字节）；`AGENTS.md` §6 已同步修订。
- 阈值为**编译期默认 + 参数可配**（`BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`），暂无用户可视开关。

---

## §36 ISSUE-P2-27 设备侧验证缺口收口（2026-09-12，app + sync）

> **动机**：§34 建立了 `app` 设备侧骨架但仅覆盖导入解析，`sync` 仍无 `androidTest` 源集。
> 本节把「域解析（正则 / PSL / IDN）」「解锁落盘」「落盘权限」三类**平台运行时相关**逻辑
> 放回真实 Android 运行时执行，收窄「JVM 全绿、Android 挂」缺陷类（§24 / §26 已两度逃逸）的盲区。

### 36.1 交付

1. **`app`**（新增 9 例，累计 12 例）：
   - `DomainMatcherAndroidRuntimeTest`（7 例）：主机名剥离、严格点号边界、公共后缀下限、
     私有段后缀、**IDN ↔ punycode 跨形式匹配**（`java.net.IDN`）、webDomain 归一化与归属 fail-closed。
   - `DatabaseSessionAndroidRuntimeTest`（2 例）：生产管线产出 `.kdbx` → 生产 `DatabaseSession` 解锁 →
     大附件落盘 / 权限 0600 / 目录 0700 / 字节往返 / 锁定即清空；阈值以下不落盘。
2. **`sync`**（新源集 + 3 例）：`sync/build.gradle.kts` 接线 `testInstrumentationRunner` 与
   `androidTestImplementation`；`SyncCacheAndroidRuntimeTest` 验证落盘权限收敛（0600 / 0700）与
   流式落盘读回一致、`clearAll` 清空。

### 36.2 验收证据（2026-09-12，x86_64 / API 36.1，`emulator-5554`）

- `.\gradlew.bat :sync:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**；
  `sync/build/outputs/androidTest-results/connected/debug/TEST-*.xml`：
  `tests="3" failures="0" errors="0" skipped="0"`。
- `.\gradlew.bat :app:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**；
  `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`：
  `tests="12" failures="0" errors="0" skipped="0"`。
- **过程留痕（如实）**：首轮 app 侧 2 处失败——① 测试方法因 `runBlocking` 返回非 `Unit` 触发
  `InvalidTestClassError`；② 误将 `extractDomain` 期望为剥离子域。均已修正后复跑通过。

### 36.3 边界

- **Passkey 系统级交互、`AssistStructure` 结构树扫描、通知渲染**仍未设备侧覆盖：
  前三者依赖系统凭据对话框 / 真实自动填充会话 / 通知栏，超出常规 instrumented 用例的可控范围，
  维持宿主 JVM 覆盖 + 如实留痕。
- **arm64 真机**仍缺（沿用 §33 ISSUE-P3-23「不排期/外部依赖」）。

---

## §37 工程整洁与文档准确性收口（2026-09-12）

| 项 | 问题 | 处置 |
|---|---|---|
| D1 | `dbset_import_reserved_note`（「解析器预留，暂未生效」）为**死文案且与现状相反**（导入已落地、零渲染点） | 从 `values` / `values-en` 删除；保留 `DatabaseSettingsDialogs.kt` 的历史说明注释 |
| D2 | TAN 序列号 / 数据库 UUID 两个**永久禁用**开关标注「即将支持」，隐含无计划兑现的路线图承诺 | 文案改为「暂不支持 / not supported yet」（`values` + `values-en`），实现侧仍是如实禁用态 |
| D3 | 「填充后自动返回」开关可持久化但**无任何行为消费方**（假开关） | `AutofillSwitchRow` 增 `enabled` 参数；该行实测禁用交互并降透明度（保留「预留，暂未生效」标注） |
| D5 | lint 是否具阻断力 | `.\gradlew.bat :app:lintRelease` → **BUILD SUCCESSFUL（EXIT 0）**：无配置豁免即默认 `abortOnError`，当前无阻断项 |
| D6 | `AGENTS.md` §6 称「独立窗口（如 `BaseCredentialActivity` 系）需单独接线」——**已过时** | 更正为按窗口分类如实描述：自动填充 / 通行密钥窗口调用 `ApplyObscuredTouchFilter()`；`BaseCredentialActivity` 体系以 `setHideOverlayWindows(true)` 屏蔽悬浮窗（强于触摸过滤），**无未接线盲区** |

- **产品裁决项（本轮未动，如实留痕）**：`versionCode` / `versionName`（`1` / `0.1.0`）属**发布定型决策**，
  未经明确发布计划不改动（避免版本号与对外发布节奏脱节）。

---

## §38 KDBX 互操作与安全整改批次（P0×3 / P1×6 / P2×14 + 文档纪律）（2026-09-12）

> **本批次缘起**：对「本仓实现 / 官方 KeePass 2.61.1 / KDBX 4.1 规范 / Android 安全模型」四方逐项对比后，
> 确认 23 项缺陷（对应 `ACTIVE_ISSUES` 的 P0-05…P2-41）。**核心结论**：KDBX 格式兼容**不等于**安全属性等价——
> 既能打开同一个库、又通过自家互操作用例的实现，仍可能整体错在官方**另一侧**：D1 即此类，本仓能读官方文件，
> 官方**读不了本仓文件**，而既有互操作用例全是「自家写 → 自家读」，故长期全绿。
>
> **方法论立规（本批次）**：凡跨实现格式的「宽容读 / 回退默认值」分支，必须同时提交**以官方实现产物为 golden**
> 的写侧或读侧对拍用例。理由：宽容读会系统性掩盖写侧错误，本仓已三度复发（P0-4 ChaCha20 IV、D1 时间单位、D9/D4/D15 回退默认值）。

### 38.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 官方依据 |
|---|:--:|---|---|---|
| **P0-05** | P0 | KDBX4 时间写成 .NET **ticks**（官方与规范为**秒**）→ 本仓产物官方客户端不可正常打开 | `KdbxXmlTimeHelper`（`formatDate` 改秒、KDoc 勘误、保留 ticks 读兼容、新增 `ANCIENT_INSTANT`/`ancientTimes`）、`KdbxTimesTest` | `Write.cs:799`、`Read.Streamed.cs:935`、规范 Page History 0.2、KeePassXC `KdbxXmlWriter.cpp:561`、KeePassDX `toDotNetSeconds()` |
| **P0-06** | P0 | Salsa20 内层流 nonce 常量错误 → 受保护字段静默乱码且保存即不可逆覆写 | `InnerRandomStreamCipher`（→ `E8 30 09 4B 97 20 5D 2A`，具名常量 + 三方出处）+ 真值 KAT 11 例 | 规范 §Inner Encryption、`CryptoRandomStream.cs:119-120`、KeePassXC `KeePass2.cpp:35` |
| **P0-07** | P0 | `<DeletedObjects>` 写在 `<Meta>`（官方在 `<Root>`）→ 墓碑双向丢失、删除条目跨客户端复活 | `KdbxXmlSerializer`（Root 内、根 Group 之后）、`KdbxXmlMetaSerializer.serializeDeletedObjects`、`KdbxXmlParser`（Root 层接收 + **兼容 Meta 旧位置**合并去重） | `Write.cs:430`、`Read.Streamed.cs` 的 `KdbxContext.RootDeletedObjects` |
| **P1-16** | P1 | `<Value Ref>` 非数字/缺 Ref/内联 base64 全被折叠为**池索引 0** → 静默交付错误附件；不识别 `Compressed` | `KdbxXmlBinaryNode`（池内命中才用池、否则回退内联、`Compressed` 解压含 128 MiB 防炸弹、内联 `Protected` 解密）+ `INLINE_REF_INDEX = -1` 不变量 | `Read.Streamed.cs:980-1024`、`Write.cs:930-978` |
| **P1-17** | P1 | 数据块 HMAC 失败被判「主密码错误」并**计入解锁节流**（与官方相反） | `HmacBlockStream`/`KdbxCipherKeyResolver`：块与终止块失败 → `KdbxCorruptFileException`；头部 HMAC 失败仍为唯一凭据出口；异常 KDoc 重写 | `Read.cs:150/157`、`HmacBlockStream.cs:233,264` |
| **P1-18** | P1 | 外层头部无总量/字段数上限（**认证之前**，唯一免口令 DoS 面） | `KdbxHeader`：`MAX_HEADER_TOTAL_BYTES = 4 MiB`、`MAX_HEADER_FIELD_COUNT = 64`；三处闸门**先裁决后写入/读取** | 本仓加固（无官方对应物，KDoc 注明） |
| **P1-19** | P1 | 附件**解密后明文**缓存无冷启动清理（唯一「无需口令即可读库内容」路径） | `FileBinaryStore`（收敛为 `purgeAttachmentCache`，失败落脱敏告警）、`MainApplication.onCreate` 冷启动清理 + 保留锁定清理 | 本仓加固 |
| **P1-20** | P1 | 防回滚状态随缓存被**锁库清除** → 云侧在用户锁定一次后即可重放旧库 | 状态迁至 `filesDir/rollback`（`@RollbackStateDir` + DI）、`SyncCache` 删除清单移除 `.rollback` 并加 `isRollbackStateFileName`、`SyncCycleRunner` 惰性解析（避开假 Context NPE） | 本仓加固（威胁建模文档同步补状态生命周期） |
| **P1-21** | P1 | 敏感对话框窗口无 FLAG_SECURE（平台为窗口级属性，Compose 对话框是独立窗口） | 新增 `SecureDialog`/`SecureDialogWindowEffect` + 纯逻辑 `SecureDialogFlagPolicy`；落地 7 处（主密码修改、子库 ×2、修订差异、附件预览、**创建库向导 ×2**） | 官方 assistant 指南 "each window … including dialogs" |
| **P2-28** | P2 | `Protected` 判定过宽（`lowercase()=="true"`）→ 消费非规范产物时密钥流错位 | `KdbxXmlStringNode`/`KdbxXmlBinaryNode` 改精确 `== "True"`；写侧恒写 `"True"` | `Read.Streamed.cs:1066-1068`、`Write.cs:858,949` |
| **P2-29** | P2 | 空 `<Value/>` 使整条 `<String>` 丢失 | `KdbxXmlSaxNodes.TextNode.end()` 恒回调（空元素交付空串） | 官方空元素返回 `string.Empty` |
| **P2-30** | P2 | 布尔/数值语义偏差（`Expires`/`IsExpanded`/`QualityCheck`/`AutoType.Enabled`/`RecycleBinEnabled`/`IconID`/`UsageCount`） | 新增 `KdbxXmlScalarParsers`（精确 bool + 字段级默认；`parseNullableBool` 大小写不敏感；IconID 钳制；UsageCount 饱和）；**Meta 与 AutoType 共 8 处宽松解析一并收敛** | `Read.Streamed.cs:250/256/266/282-291/382/388/441/459/501/503/527/834-851` |
| **P2-31** | P2 | 时间缺省值错误（缺整个 `<Times>` → `now()` 虚假"刚修改"；子元素缺失 → 1970） | 一律 `ANCIENT_INSTANT`（0001-01-01，恒不可能在"越新越胜出"中虚假胜出） | 官方 `DateTime.MinValue` 语义 |
| **P2-32** | P2 | `CustomData` 项时间戳、`CustomIcon` 的 `Name`/时间、`MasterKeyChangeForceOnce` 读写丢失；零 UUID/空 data 图标未按官方丢弃 | `KdbxMetaData`（新增 `customDataTimes` **并行字段**，`customData` 保持 `Map<String,String>` 以免波及 app/sync）、`CustomIcon`、`KdbxXmlMetaReader/Serializer`、`KdbxFile.buildDatabase` 装配补齐 | `Write.cs:461/697-703/808-825`、`Read.Streamed.cs:315/353` |
| **P2-33** | P2 | `MemoryProtection` 读后未重置为默认；写侧未参与标准五字段的 `Protected` 决策 | 读后重置（`officialMemoryProtectionReset`）；写侧 `resolveProtectedFlag`：标准五字段由**库级配置无条件覆盖** per-value，非标准字段保留 per-value（KDoc 贴官方 C# 片段并禁止改回 OR） | `Read.cs:246-248`、`Write.cs:838-854`、`Write.cs:464` |
| **P2-34** | P2 | KDF 参数缺 `M/I/P/V` 静默填默认（官方 fail-closed）；内存下界 1 MiB 严于规范 | `KdbxKdfParameterCodec`：缺参即抛并点名缺键、下界对齐 8192、上界保留防 DoS 并给「规范 vs 本仓」对照表 | `Argon2Kdf.cs:57-58,143-160` |
| **P2-35** | P2 | XML 无元素计数上限 | `KdbxXmlParser.MAX_XML_ELEMENTS = 2_000_000` + 用例 | 本仓加固 |
| **P2-36** | P2 | 受保护值解密后的明文副本未清零 | `KdbxXmlStringNode` 构造后立即 `plainBytes.fill(0)`（先核实 `ProtectedString` 为借用语义 + init 内密封） | 官方 `XorredBuffer` 用后清零 |
| **P2-37** | P2 | 零/缺失 UUID 原样保留 | `KdbxXmlParser.normalizeZeroUuids`（含父引用与 History 同步） | 官方 `PwUuid(true)` 替换 |
| **P2-38** | P2 | base64 内部空白不容忍（官方容忍） | `KdbxXmlValueUtil.decodeBase64LenientWhitespace`（**仅剥空白 + 严格基本解码器**；实测明确否决 `getMimeDecoder()`——它会静默接受非法串并错位 keystream） | `.NET Convert.FromBase64String` 行为 |
| **P2-39** | P2 | `Ref` 附件路径误写 `Protected` | `KdbxXmlEntrySerializer` Ref 分支不写 Protected；池外索引回退内联 | `Write.cs:930-949` |
| **P2-40** | P2 | `isPackageMatch` 剥离任意 scheme → 域名形态包名冒充（`https://github.com` ↔ 包名 `github.com`） | 新增 `DomainMatcher.isAndroidPackageMatch` 并替换 **5 处**放行决策；浏览器 allowlist / DAL / 域匹配路径**一行未改** | 官方包名精确匹配语义 |
| **P2-41** | P2 | `requireRiskNotice` 声明式属性生产零消费 | `RuntimeIntegrityPolicy.requiresRiskNotice` 成为唯一消费点，设置页据此渲染风险卡 | 本仓策略自述 |
| **P3-81** | P3 | 文档纪律与勘误（**本批次内建立并闭环**） | `AGENTS.md` §4 索引补 7 份安全文档 + 立"索引纪律"、§6 附件缓存措辞如实化；`docs/references/KeePass-2.61.1-架构分析.md` 勘误 AES-KDF 出厂常量（6 000 000 → **600 000**，`PwDefs.cs:116`）与块 HMAC 摘要输入（`LE64(i)‖LE32(size)‖C`，纠正"索引不进摘要"的误读）；`docs/同步层记录级完整性威胁建模.md` 补状态生命周期与异常分型 | 直接读官方源码核实 |

### 38.2 验收证据

#### (1) 单测全绿（权威强制重跑）
```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 3s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 111 | 832 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 349 | 0 | 0 | 0 |
| sync | 18 | 195 | 0 | 0 | 13 |
| **合计** | **198** | **1557** | **0** | **0** | **13** |

**基线变动**：1423 → **1557（+134 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
`crypto` 的 Rust 原生内核（`cargoHostBuild`）本轮成功构建。

#### (2) D1 的**外部官方实现端到端对拍**（本批次新增的证据形式，决定性）
探针 `OwnProductInteropProbeTest` 由本仓 writer 产出真实 `.kdbx`（1175 B，
SHA-256 `c23ed3cfd9d68e3af45dc37cb64178c81c4b6eb9db40fc145617fd5555b2c3d8`，AES-KDF 6000 轮，口令 `interop-probe-password-2026`），
并留 `PROBE.md` 记录复现命令。

- **`keepassxc-cli 2.7.12`**（`db-info` / `ls -R`）→ **成功打开**：名称 / 描述 / 加密 AES-256 / KDF / 群组数 1 / 条目数 1 全部正确；
  `数据库创建时间: 2026/9/12 14:01`、`保存时间: 2026/9/12 22:01` —— **时间正常**；
- **`pykeepass 4.2.0`** → 读出条目 `Probe Entry / probe-user / Probe-P@ssw0rd-2026`，
  `ctime = mtime = 2026-09-12 14:01:44+00:00` —— **与 `PROBE.md` 期望值逐秒一致，未抛 `OverflowError`**。

**修复前对照（机理）**：本仓写出的 ticks 值是官方期望秒值的 10⁷ 倍，官方 `new DateTime(lSec * 10^7)` 在 long 回绕后
仅约 1/6 概率落回 `DateTime` 合法区间 ⇒ 多数文件直接打不开、其余得到荒谬日期；pykeepass 则抛 `OverflowError`。

#### (3) F-09 真值 KAT（跨实现，非自洽往返）
- 向量来源：**pycryptodome 3.23.0**（独立于本仓）与 **BouncyCastle 1.85.2**（本仓生产引擎）**双实现逐字节一致**，
  另经 Bernstein/ECRYPT 官方 Salsa20 向量校准工具可信度；
- Salsa20（正确 nonce）前 32 B = `f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d`；
  **错误 nonce** 对照流 = `739a24411659762d97ba9107082efe718ee8f793295f3666b48d72cf62642fd5`（与正确值无任何字节相同）；
- ChaCha20 前 32 B = `8ce8bc610ac05ff2e3dd88b49a1404c2844f148037027476b83d58f5609adf65`；
- KAT 另含「跨调用密钥流必须连续」用例，防「每次调用重置引擎」导致的**密钥流复用**（流密码致命缺陷）。

#### (4) 集成期修出的真实缺陷（如实留痕，含 1 个生产缺陷）
| # | 现象 | 定性 | 处置 |
|---|---|---|---|
| 1 | `KdbxXmlMetaSerializer` 对跨模块属性 smart cast → 编译失败 | 编译期 | 先取局部不可变副本 |
| 2 | 测试 `failure is KdbxInvalidCredentialsException` 恒假 → 编译失败 | 编译期 | 向上转型到共同基类 `IOException` 后再判（语义不变） |
| 3 | **`KdbxXmlEntrySerializer.writeInlineAttachmentValue` 在 `finally` 清零 `att.data`**，而 `KdbxAttachment.data` 对**内存附件返回自身数组（非副本）** ⇒ **写出即销毁调用方的附件字节**（同实例再次保存 / UI 读取全为 0） | **生产缺陷**（由新增用例暴露） | 写侧改为只借用不清零；`KdbxAttachment.data` KDoc 改为**按来源分类声明所有权**（内存=借用、落盘=独立副本），并注明该差异就是缺陷成因 |
| 4 | 「声明长度越界须在读取前拒绝」用例构造错误（每个字段都声明 1 MiB 却零数据 ⇒ 第 1 个字段即 EOF，累计预算无法推进） | 测试缺陷 | 改为「前 3 个字段带真实 1 MiB 数据 + 第 4 个仅声明」使预算恰在**读取前**越界（算术与消息关键字均已核对） |

> 首轮跑测为 **3 例失败**（上述 #3、#4，以及一处 KAT 期望未扣除"前序受保护字段已消耗密钥流偏移"），
> 修正后复跑全绿。**失败过程一并留痕**，避免"一次就绿"的失真叙述。

### 38.3 边界、未覆盖与有意偏离（如实声明）

- **设备侧待验（JVM 无法闭环）**（已登记为 `ISSUE-P2-42`，含逐项复现配方与验收标准）：① 对话框窗口真实带上 `FLAG_SECURE`（建议 `dumpsys window` 或截图实测，
  覆盖本轮 7 处对话框）；② 附件缓存**冷启动清理端到端**（落盘 → force-stop → 冷启动 → 目录应为空）；
  ③ `SecureDialog` 取到 `DialogWindowProvider` 的路径（理论上 `DialogLayout implements DialogWindowProvider` 已由
  compose-ui 字节码核实，仍建议真机确认未静默 fail-safe 空操作）。
- **F-23 未做**经真实 `SyncCoordinator.syncNow()` + 真 Keystore MAC 的端到端用例：单测装配路径固定注入
  `NoopSyncIntegrityMac`（防回滚在单测路径天然禁用），端到端需大改装配脚手架，超出本批次范围；
  已由 `SyncCache` + evictor 两级锁定 + 真实 `DatabaseSession.lock()` 路径覆盖。
- **Salsa20 无真实语料**：本仓无「KDBX4 + `InnerRandomStreamID=2`」的官方产物（KeePass/KeePassXC 的 v4 恒写 ChaCha20，
  v3 被版本门拒绝），故 KAT 以**跨实现真值**替代端到端语料；合成该语料的配方已写在用例 KDoc 内。
- **有意偏离（留痕）**：① **未实施"F-09 拒存保护"**（既有整改文档建议的第一步）——修复常量后读 Salsa20 已正确，
  拒存反而阻断合法迁移；改以 KAT 锁定常量。② KDF 上下界**保留比规范更严**的防 DoS 封顶（KDoc 给对照表），
  仅下界与版本取值集对齐官方。③ 布尔解析**不 trim**（对齐官方裸字符串精确比较）。
- **本批次新增登记、仍未闭环的待办**：`ISSUE-P3-78`（Argon2 `S` 长度未按官方 `MinSalt=8`/`MaxSalt=0x3FFFFFFF` 校验）、
  `ISSUE-P3-79`（Compose Popup 系窗口未接线 `PopupProperties(securePolicy)`）、
  `ISSUE-P3-80`（`KdbxConstants.Xml.COMPRESSED` 等属性常量未上收）。**同类已记录的接受域差异**：
  `HistoryMaxItems` 缺省官方为 `-1`（本仓 10）、官方 `ReadTime` 对非 8 字节 base64 零填充宽容（本仓严格拒绝）。
- **CodeQL 影响评估**：`java-kotlin` **不在** code scanning 语言矩阵内（`.github/workflows/codeql.yml:55-64`，
  文件头 :21-23 说明理由），故新增 Kotlin 硬编码 KAT 向量不会产生 `hard-coded-cryptographic-value` 告警，
  无需改 `.github/codeql/codeql-config.yml`。
- **唯一功能收紧**：`isPackageMatch` 语义收窄后，URL 为「裸包名」（无 scheme）的条目不再按包名命中（fail-closed，已入 KDoc）。

### 38.4 基线同步
- `AGENTS.md` §1 单测基线：**1423 → 1557 例**（0 失败 / 0 错误 / 13 跳过）；
- `AGENTS.md` §4 新增 7 份安全文档索引 + 索引纪律；§6 附件缓存清理改为「冷启动 + 锁定」两层并附如实边界。

---

## §39 红队攻击路径批次处置归档（报告退役 + 存量项转登 ACTIVE_ISSUES）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对 `docs/security/REDTEAM_ATTACK_PATHS.md`
（44 条攻击路径，下称"红队批次"）逐条对拍，把**仍成立的开放项**转登 `ACTIVE_ISSUES.md`，
**已撤回 / 已证否 / 已验证 / 风险接受**项在此留痕，随后**退役并删除**该文档。

### 39.1 来源与时间线事实（本批次必须记录，避免复发"同批自相矛盾"）

| 事实 | 证据 |
|---|---|
| 红队文档正文撰写于 `d32f3e7`（§38 批次之前） | 撰写期引用 `DomainMatcher.kt` 为 **168 行**、`AutofillCandidateRanker` 调 `isPackageMatch`（`git show d32f3e7:…` 复核） |
| `ISSUE-P2-40`（新增 `isAndroidPackageMatch`）于 `9b64415` 落地 | `git log -S "isAndroidPackageMatch"` → **仅** `9b64415` |
| 红队文档被 `9b64415` **一并提交**（`+1357` 行） | `git show --stat 9b64415` |
| `ISSUE-P2-40` **从未登记**于 `ACTIVE_ISSUES.md` | `d32f3e7` 与对拍时 HEAD **均**无 `P2-40`；仅本文件 §38 有记录 |

**结论（双向断链，二者缺一不会出现"同 commit 内报告指控、修复生效"）**：
① **报告方缺状态列**、定稿前未重新对拍（已在本批次以 §39.8 纪律收口）；
② **修复方缺认领单据**——`P2-40` 绕过 `ACTIVE_ISSUES.md` 的认领 / 登记环节直接落本文件，违反
`AGENTS.md` §3.6 第 1 步。**故 `AP-01` 在撰写基线是真实且未被记录的缺陷，其"机制已闭环"属同批修复，非发现无效。**

### 39.2 已撤回（内容作废，仅留编号占位防重复发现）

| 条目 | 硬伤（与时间线无关） | 证据 |
|---|---|---|
| `AP-02` webDomain 冒领（未验证分支） | 所依赖的 `UNVERIFIED` 枚举值**从未存在**；`attribute()` 末尾恒 `REJECTED`，调用侧映射为 `null`（本就 fail-closed）。**由 grep 片段推断控制流**所致 | `WebDomainAttribution` 三值枚举；`AutofillWebDomainPolicy.attribute` 末尾 `return REJECTED` |
| `AP-05` 字段屏蔽表越权写入 | base intent **已显式** `putExtra(EXTRA_CALLING_PACKAGE, callingPkg)`；叠加 `Intent.fillIn` 的"base 覆盖 fillIn"语义 → 注入该键**必然失败**；且该值源于系统背书 callingPkg。原文自身已承认 fillIn 语义却仍宣称可注入，**自相矛盾** | `AutofillDatasetBuilders.buildPickerDataset`（含包名 extra）；`AutofillPickerActivity.blockFieldAndFinish` |

### 39.3 已证否 / 降级

| 条目 | 处置 | 依据 |
|---|---|---|
| `AP-24` 内层 XML DTD 降级 → XXE | **XXE 路径证否**；降级为"补设备侧 DTD 回归用例" → 转登 **ISSUE-P3-82** | `buildHardenedParser` 的**特性探针**撰写期即已存在；`parser.parse(inputStream, handler)` 按 API 契约把 `DefaultHandler2` 同时注册为 `EntityResolver` → `resolveEntity` **必然**被调用并抛异常 |
| `AP-04` `FLAG_MUTABLE` 注入 / 重放 | **前置不成立**：`FillResponse`/`Dataset` 回传**系统**渲染，客户端不经手 `IntentSender`。扣除后仅剩重复拉起型 DoS → 并入 **ISSUE-P3-85** | 内联建议路径确会向客户端给出 `PendingIntent`，但为 `FLAG_IMMUTABLE` 且指向 `MainActivity`（`AutofillInlinePresentationFactory`），**无注入面** |

### 39.4 已修正（结论保留、论证纠偏）

| 条目 | 修正 | 处置去向 |
|---|---|---|
| `AP-01` 包名冒领 | 命名空间混同路径**已由 `P2-40` 闭环**（填充链路改 `isAndroidPackageMatch`，只认 `android://`）；**残余项**（`android://` 条目无调用方签名指纹绑定）转登 | **ISSUE-P2-46** |
| `AP-14` 节流 | 绕过**不需要**重算 MAC：删掉三个 prefs 键即命中 `read()` 的"**全新安装**"分支被判完整（原文选了更难的路径，说明未读读取侧） | **ISSUE-P2-45** |
| `AP-22` TOTP 通知 | 通知默认**已关闭**（原建议即现状）；**真正漏掉的是** `autofillCopyTotp` 默认 **true** —— 动态码默认入剪贴板 | **ISSUE-P2-43** |
| `AP-44` 缓存残留 | 冷启动清理**早已实现**（`MainApplication.onCreate` 起始段 `fileBinaryStore.clear()`），且 `F-13 / ISSUE-P1-19` 残余窗口已如实声明；原文"待确认"系未执行的检查 | force-stop 路径复测**并入既有 ISSUE-P2-42**，不另立条目 |

### 39.5 已验证通过（本批次首个"全绿"项）

- `AP-43` 备份 / 设备迁移提取：`allowBackup=false` + `data_extraction_rules` 全域排除
  （cloud-backup & device-transfer）——**实测应记为通过**，无需整改。

### 39.6 风险接受 / 产品裁决（记录出处，不作价值否定）

| 项 | 性质与出处 | 保留的技术面 |
|---|---|---|
| `AP-20` / `AP-42` FLAG_SECURE 可关闭 | **显式产品裁决**（`FlagSecurePolicy` KDoc 载明"2026-09-12 用户裁决语义修订"：锁定态强制、解锁态随开关真实解除） | 关闭期间 `MediaProjection` / 截屏不受阻 |
| `AP-19` 的 `installer==null` 不升级风险 | **有意取舍**（`RuntimeIntegrityDetector.detectUntrustedInstallSource` 注释："避免误报"） | 重打包 APK 在无 root 痕迹时被判 `TRUSTED`（**该后果本身转登 ISSUE-P1-23 处置**） |
| `AP-33` / `B15` 同步凭据封印 `requireUserAuth=false` | **有意决策**（后台同步需锁屏可用，`SyncCredentialSealer` 注释） | 锁屏态可解封云凭据；可选降收益方向＝OAuth2 刷新令牌 + 设备私钥（**未立案**） |
| `AP-08/09/12/16/17/31` 同 UID / root 截获 | **设计边界**（源码自认 + `AGENTS.md` §6） | 见 39.7；仅 `TracerPid` 一项作为"提高成本"转登 **ISSUE-P3-83** |

### 39.7 设计边界合并计价（`AP-R1` / `AP-R2`）

原报告把同根因拆成多条独立高危（`AP-08`、`AP-09`、`AP-16`、`AP-17`）并按乘积排序。
本批次**合并为一次计价**：

- `AP-R1` ＝ `AP-08`（Hook 解封点）＋ `AP-09`（会话缓存克隆点）：**同 UID / root 可截获主密码**；
- `AP-R2` ＝ `AP-16`（内存扫描）＋ `AP-17`（`ptrace` / `proc/mem`）：**同 UID / root 可截获全库明文**。

**处置口径**：**接受根因，不追"承诺阻断"**，转向"降低一次成功的收益"。可选的降收益方向
（主密码不常驻会话 / 硬件内派生临时密钥 / 附件加密落盘 / 同步凭据改 OAuth-STS）
**本批次不立案**——属可选项而非缺陷，须待专项排期时再评估。

### 39.8 对拍中被反驳但经复核**不予接受**的两点

1. **`B10`（候选准入结构）成立且不撤**：`AutofillCandidateRanker.scoreEntry` 单独 `packageMatch`
   即计分、`if (score <= 0) return null` 表明"命中其一即产出候选"。
   `android://` 约束收窄的是"**什么算包名命中**"，未改变"**其一即可**"的结构。
   原文之误在 `AP-01` 的**具体 URL 形态**，不在 `B10` 本身——两者曾被合并反批评，特此拆开。
2. **`B13`（JNI 签名与旧 C 桥一致）有其证据链，不撤**：`crypto/src/main/rust/src/jni_bridge.rs`
   L3-5 模块文档明载"符号名与签名逐字一致"，且 L151-170 有**编译期**类型断言
   （`exported_symbol_has_c_parity_signature`，把导出函数赋给显式 typed `extern "system" fn` 指针）。
   原文缺失的是**行号**，非依据。
   **附带精确修正（本批次新增）**：其对照物 `keepasskey_argon2_jni.c` **已不在仓库内**
   （全仓检索 0 命中，仅 `lib.rs:7` / `jni_bridge.rs:3,25` 以文字提及），故该编译期断言自证的是
   "Rust 导出 = **本仓手写的期望签名**"，**不自证**"= 已移除的 C 桥"。引用该项时应采用
   "文档断言（对照物已移除）"口径，或由 KDoc 注明 C 桥已删除。

### 39.9 本批次产出（转登 `ACTIVE_ISSUES.md` 的开放项）

| 新编号 | 主题 | 地图来源 |
|---|---|---|
| `ISSUE-P1-22` | 软件级 Keystore 下快速解锁封印未 fail-closed | `AP-07` |
| `ISSUE-P1-23` | 篡改 / 重打包 APK 无检测；`installer==null` 判为无风险 | `AP-19` / `AP-39` |
| `ISSUE-P1-24` | 自动填充确认页可伪造归属信息，且无"首次绑定"显式授权 | `AP-03` |
| `ISSUE-P2-43` | `autofillCopyTotp` 默认开启 —— TOTP 动态码默认入剪贴板 | `AP-22′` |
| `ISSUE-P2-44` | 无障碍服务信号未纳入运行完整性体系 | `AP-23` |
| `ISSUE-P2-45` | 解锁失败节流默认关闭，且记录可被"删键复位" | `AP-14` |
| `ISSUE-P2-46` | `android://` 包名绑定条目缺调用方签名指纹绑定 | `AP-01` 残余 |
| `ISSUE-P2-47` | 同步与封印凭据的回滚防护不足 | `AP-31` / `AP-13` |
| `ISSUE-P3-82` | 内层 XML DTD 拦截缺设备侧回归用例 | `AP-24` 残余 |
| `ISSUE-P3-83` | `TracerPid` / 内存取证门控缺失 | `AP-17` |
| `ISSUE-P3-84` | 剪贴板"可关闭擦除 / 延时窗口"的风险明示 | `AP-21` |
| `ISSUE-P3-85` | 自动填充 / 组件面低危硬化（3 小项） | `AP-04` 残余 / `AP-40` / `AP-41` |

**未转登（明确接受的残余）**：`AP-06/10/11/12/18/25–30/32/34–38`
（单条收益有限、需组合，或属设计边界），维持"不作独立缺陷"的判定；`AP-12` 属源码自认边界。

### 39.10 方法纪律（本批次确立，供后续审计 / 攻防文档继承）

1. **必须带状态列**：每条须标注 `已验证 / 已被同批修复 / 待实测 / 已撤回`，**定稿前重新对拍 HEAD**。
2. **不得由 grep 片段推断控制流**（`AP-02` / `AP-24` 两条硬伤同源）：**必须读到函数结尾与其调用点**。
3. **不得把 `待实测` 前提计入 `现实×易×影响` 乘积**。
4. **同一根因只计一次**：观测点数量 ≠ 独立风险数量。
5. **区分"缺陷"与"产品裁决 / 风险接受"**：后者须引用裁决出处，不作价值否定。
6. **引用核对须在文档声明的基线（commit）上进行，而非 HEAD**：原文行号对 `d32f3e7` 是精确的，
   在 HEAD 上"看似漂移"仅因同批改动扩大了文件——**该现象本身不构成对引用方的反驳理由**。
7. **审计输入文档的处置结论必须落本归档库**：报告退役前，其"撤回 / 证否 / 风险接受"结论不得随文件删除而丢失。

### 39.11 报告退役与索引同步

- 删除 `docs/security/REDTEAM_ATTACK_PATHS.md`（含其未提交的 §0.2 修订稿）；
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律）；
- 本批次所有"现存问题"已转登 `ACTIVE_ISSUES.md`（12 项，见 39.9），历史与已排除项以本节为单一真相源。

---

## §40 外部安全审计报告退役批次（报告退役 + 存量项转登 ACTIVE_ISSUES）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对 `docs/SECURITY_AUDIT_2026-09.md`
（第三方安全审计，自称 29 项已复核发现）逐条对拍，把**仍成立的开放项**转登 `ACTIVE_ISSUES.md`，
**已整改 / 误报 / 待复核 / 无法确认 / 已确认强项**在此留痕，随后**退役并删除**该报告。

### 40.1 来源与时间线事实（本批次必须记录）

| 事实 | 证据 |
|---|---|
| 报告审计基线 = `d32f3e7`（2026-09-12 19:38） | 报告表头「审计基线（快照）」 |
| 报告、整改方案与威胁模型**随 `9b64415` 一并提交** | `git log --oneline -- docs/SECURITY_AUDIT_2026-09.md` → 仅 `9b64415` |
| `9b64415`（§38）**并非对报告方的响应** | §38 自述缘起为「本仓实现 / 官方 KeePass 2.61.1 C# / KDBX 4.1 规范 / Android 安全模型」四方对比；两路径独立命中同一批缺陷（如报告 `F-09` ↔ `ISSUE-P0-06`） |
| 本批次处置时 HEAD = `a669a48`（2026-09-13） | `git log --oneline -1` |

### 40.2 已整改（报告结论对当前代码不成立）

| 报告条目 | 对应整改 | 当前代码事实 |
|---|---|---|
| `F-09` Salsa20 nonce 错误 | `ISSUE-P0-06`（§38） | `InnerRandomStreamCipher.kt:131-134` 已为 `E8 30 09 4B 97 20 5D 2A`，附规范 / 官方 C# / KeePassXC 三方出处 + KAT |
| `F-11` 外层头部无总量上限 | `ISSUE-P1-18`（§38） | 双闸门 `MAX_HEADER_TOTAL_BYTES = 4 MiB` / `MAX_HEADER_FIELD_COUNT = 64`，先裁决后读写 |
| `F-13` 附件明文无冷启动清理 | `ISSUE-P1-19`（§38） | `MainApplication.onCreate` 起始段 `fileBinaryStore.clear()` |
| `F-15` 受保护值明文未清零 | `ISSUE-P2-36`（§38） | `KdbxXmlStringNode.kt:73-75` `finally { Arrays.fill(plainBytes, 0) }` |
| `F-23` 防回滚状态随锁库清除 | `ISSUE-P1-20`（§38） | 状态迁至 `filesDir/rollback`，`SyncCache` 删除清单移除 `.rollback` 并加文件名守卫 |
| `F-25` 解锁失败日志含库 id / 密钥文件长度 | 存量批次 | `UnlockViewModel.kt:299-303` 已收敛为 `errType=…, invalidCreds=…` |

**结论**：报告是**修复前快照**；其中至少 6 项完整发现在其基线之后数小时内即被同批修复，
报告未设状态列亦未对拍 HEAD，故**不得直接引用其计数或结论**（见 40.8 纪律 1）。

### 40.3 定性更正与降级（本批次复核结论）

| 项 | 更正 |
|---|---|
| **计数内部矛盾** | 误报数曾同时写「9」与「6」；严重度分布曾算作 30；「合计 22 / 29 项 / 总表 32 行」并存（实测总表 **31 行**：`F-01…F-25` 共 25 + `RUST-01…RUST-06` 共 6）；分类分布称 `Confirmed Vulnerability 9` 却仅列 8 个 ID，且 `F-07` 未落入任何分类桶；另设「INFO 类」桶属**严重度误用为分类**。→ 本批次以 `ACTIVE_ISSUES` 的转登清单为**唯一权威口径** |
| `F-05` | CVSS 7.3 / HIGH 偏高：需仓库写权限（已高度受信主体），且不直接造成运行时泄露，`VI:H` 未充分论证 → 转登时按 **P2** |
| `F-10` | HIGH → **P2**。元素计数上限（`MAX_XML_ELEMENTS`）**不构成有效缓解**（不约束同一池条目被引用 N 次的副本乘法），但该缓解须在条目内如实披露 |
| `F-12` | 报告自述「把有界收紧为预算」，本质为**加固建议** → 按 P2 转登，不再计为独立漏洞 |
| `F-04` / `F-19` / `F-08` / `F-16` / `F-17` / `F-07` / `F-20` | 无攻击者 / 不可达 / 文档卫生 → **移出「漏洞」口径**，按 P3 转登（`F-04`、`F-19` 方向为 fail-closed，无机密性影响） |

### 40.4 与既有条目的重合（不重复登记）

- **`F-01`（解锁节流默认关闭）** → 已由 §39 转登的 **`ISSUE-P2-45`** 覆盖；且 `P2-45` 额外发现
  「删除三个 prefs 键即命中『全新安装』分支被判完整」的**复位旁路**，强于报告结论。
- `F-18` 的「风险明示」面与 **`ISSUE-P3-84`** 重合，转登时交叉引用。
- `F-19` 是 **`ISSUE-P2-46`**（`android://` 签名指纹绑定）的**前置条件**。

### 40.5 本批次产出（转登 `ACTIVE_ISSUES.md` 的开放项，25 项）

| 新编号 | 主题 | 报告来源 |
|---|---|---|
| `ISSUE-P2-48` | 附件引用放大无累计预算 | `F-10` |
| `ISSUE-P2-49` | KDF 无工作量 / 墙钟预算 | `F-12` |
| `ISSUE-P2-50` | DAL 响应体先物化后检查 | `F-14` |
| `ISSUE-P2-51` | 剪贴板不随锁定 / 熄屏清理 + 误清他处内容 | `F-18` |
| `ISSUE-P2-52` | 自动填充选择器锁定后崩溃 | `F-22` |
| `ISSUE-P2-53` | CM 通道不查询 `RuntimeIntegrityGate` | `F-24` |
| `ISSUE-P2-54` | 依赖 CVSS 闸门未接入自动触发路径 | `F-05` |
| `ISSUE-P2-55` | 发布签名口令 = 公开示例值 | `F-06` |
| `ISSUE-P2-56` | Argon2 工作内存释放前未擦除 | `RUST-01` |
| `ISSUE-P2-57` | 派生密钥栈副本残留（`sha2` 未启 `zeroize`） | `RUST-02` |
| `ISSUE-P2-58` | 口令强度评估 Θ(n²) → 主线程 ANR | `RUST-03` |
| `ISSUE-P2-59` | 原生 Argon2 路径缺内存上界预检 | `RUST-05` |
| `ISSUE-P2-60` | KDF secret `K` 常驻且无清零点 | `RUST-06` |
| `ISSUE-P3-86` | 明文导出缓冲未清零 | `F-02` |
| `ISSUE-P3-87` | 明文导出确认仅在 UI 层 | `F-03` |
| `ISSUE-P3-88` | Chrome 指纹首条 65 hex 永不匹配 | `F-04` |
| `ISSUE-P3-89` | 审计摘要实际仅 32 位 | `F-07` |
| `ISSUE-P3-90` | 公开死函数 `parseOtpAuthUri` 缺参数钳制 | `F-08` |
| `ISSUE-P3-91` | `HmacBlockStream.readAll` 非常时比较 | `F-16` |
| `ISSUE-P3-92` | UI 误标「ChaCha20-Poly1305」 | `F-17` |
| `ISSUE-P3-93` | 调用方证书仅取首个签名者 | `F-19` |
| `ISSUE-P3-94` | 合并清单冗余 / 废弃权限 | `F-20` |
| `ISSUE-P3-95` | 填充确认不校验会话锁定 | `F-21` |
| `ISSUE-P3-96` | Kotlin CBC 加密流明文中转副本未清零 | `RUST-07` |
| `ISSUE-P3-97` | CI 不跑 JNI 边界测试 / 符号表核对 | `RUST-09` |

### 40.6 未转登（明确不作待办，随报告退役）

1. **误报排除 9 项**：`FP-01`/`FP-02`（`parseOtpAuthUri` 除零可达性）、`FP-03`（`calculateHotp` 越界）、
   `FP-04`（`parseOtpAuthUri` 无调用点）、`FP-05`（内层流「每值重置」——规范要求**不**重置，实现正确）、
   `FP-06`（生产 Hilt 未注入真实依赖——已由 release 生成组件证明为测试专用）；
   `RUST` 侧 3 项（负值经 `as u32` 绕过闸门、panic 跨界 UB、Twofish JNI 原地修改 IV）。
   另 **CWE-22 路径穿越**经查证不成立（附件缓存 key 为随机 UUID）。
2. **§10 待复核区**（`IPC-01`…`IPC-11`、`SUPPLY-01`…`SUPPLY-08`）：报告自述**未经首席审计员逐条复核**，
   不计入发现，故不转登。
3. **§11 无法确认**（`C-1`…`C-10`）：条目内容是「解除所需材料」而非缺陷。
4. **已确认强项**（报告 §1.3 / §9B.2）：非缺陷，作为复核证据留痕。

> 上述四类由 `SECURITY_AUDIT_REMEDIATION.md`（已退役，见 §43）**附录 A–F** 承接，
> 该文档自此成为**该轮审计的唯一留存记录**；完整原文见 `git show 9b64415:docs/SECURITY_AUDIT_2026-09.md`。
>
> **（2026-09-13 §43 补注，不改写上文）**：该留存文档**亦已退役删除**，其附录 A–F 与产品决策
> 已**完整留存于本库 §43.3 ~ §43.9**；`ACTIVE_ISSUES.md` 内指向该文档的指针已改指 §43。

### 40.7 报告退役与索引同步

- 删除 `docs/SECURITY_AUDIT_2026-09.md`（git 跟踪文件，完整原文保留于 `9b64415`，可 `git show` 取回）；
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律），
  `SECURITY_AUDIT_REMEDIATION.md` 行改为「该轮审计唯一留存记录」；
- `AGENTS.md` §4 索引纪律的「反例代价」表述更新为：该报告已退役、其存量项已转登 `ACTIVE_ISSUES.md`；
- `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` 内对其 `文件:行号` 的引用改指向本归档与 `ACTIVE_ISSUES.md`；
- `SECURITY_AUDIT_REMEDIATION.md` 头注改写并追加附录 A–F。

### 40.8 方法纪律（继承 §39.10，本批次新增三条）

1. **引用审计结论前必须对拍 HEAD**：本报告 6 项完整发现在其基线之后数小时内即被同批修复，
   而报告无状态列 → 直接引用会产生错误结论（本批次「时点错误」为独立复现的实例）。
2. **计数必须可机械导出**：分布表不得手工维护；须先冻结「N 项 = 哪些 ID」的成员清单，再机械导出；
   且**分类（7 类）与严重度（4 档）严禁混用**（本报告曾出现「INFO 类」桶）。
3. **无攻击者的确定性缺陷不属「漏洞」分类体系**：应另立「Correctness Defect / 代码卫生」口径，
   否则清单虚高（`F-04`/`F-19`/`F-08`/`F-16` 等即此类）。
4. **退役前必须完成内容分流**：报告删除之前，其「仍成立 / 已整改 / 误报 / 待复核 / 无法确认 / 强项」
   六类结论必须全部落到 `ACTIVE_ISSUES.md`、本归档库或留存记录文档，**不得随文件删除而丢失**（延续 §39.10 第 7 条）。

---

## 41. 敏感数据流审计（`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`）退役与分流

### 41.1 报告与基线

- **报告**：`docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`（AI 执行的**只读静态**数据流审计，成文日期 2026-09-13）。
- **审计基线**：`d32f3e7`（2026-09-12 19:38:31）。**阅读发生在 `9b64415`（2026-09-12 22:22:32）合入之前**。
- **报告自身最严重缺陷**：未在开头锚定 `git rev-parse HEAD`，导致基线漂移后全部 `文件:行号` 与代码引文
  对 HEAD 失效 —— 这直接引发了本批次的两轮复核。

### 41.2 两轮复核与结论

**第一轮（外部复核，2026-09-13）**：抽查报告标注 `[V]`（自称"亲自复读源码确认"）的高优先级条目，
对 HEAD 逐条比对，一度判定 H3 / 前提二 / H1 证据链为错误。

**第二轮（基线回溯，同日）**：`git log -p` 逐行回溯证明 —— 上述三条**在基线 `d32f3e7` 上全部为真**，
其引文与行号**精确**（`KdbxXmlStringNode.kt` 基线共 57 行、`:30` 为 `?.lowercase() == "true"`、
`:50-51` 为无 `finally` 的 `plainBytes`；`KdbxXmlEntrySerializer.kt` 基线共 135 行、`:99` 为
`if (value.isProtected)`、`:113` 为 `writer.text(value.readString())`）。`9b64415` 已分别以
「缺陷 D4」「缺陷 D24」整改并改写这些行。

> **结论**：第一轮判定为**归因错误**（"引文虚构"），实为**基线漂移**；第二轮已更正。

### 41.3 撤回 / 下调（报告自身的错误）

| 报告结论 | 判定 | 依据 |
|---|---|---|
| **H-new-3**：`requireRiskNotice` 无消费者 → 用户得不到可见警告 | ❌ **撤回** | 基线与 HEAD 的 `SecuritySettingsScreen.kt` **均已**渲染 `IntegrityRiskCard`；根因是子审计 grep 只查 `requireRiskNotice`、漏 `requiresRiskNotice` |
| **S1**：持有仓库者可伪造升级包（严重） | ❌ **定级下调为中** | 同一段内自认 `release.jks` 未入库；签名 = 口令 + 私钥库两件套，仅凭口令无法重建 |
| **H1 附注**：`VaultEntryMapperTotpTest` 给出"虚假安全感" | ❌ **撤回** | 该用例测**读取**路径兼容性，用受保护夹具必要 |
| **H2 寿命定级**：Lifetime Map 标"无上界 ★★★" | ⚠️ **修正** | `extractKey` 的 String 为函数局部量，驻留上界为**下次 GC**；副本数论断不变 |
| **H-new-2 表述**："硬编码 false / 唯一门控输入" | ⚠️ **补上下文** | 紧邻 ISSUE-P3-53 注释，是有意的分层取舍；技术结论不变 |
| **§8.1**："A–H 八个面全部闭合" | ⚠️ **过度声明** | 同章 §8.2 又列 14 项未闭合 |

### 41.4 本轮复核新增发现（报告自身的误差）

1. **`L12`（口令长度进日志）在 HEAD 已不存在**：`UnlockViewModel.kt:191` 文案为 `input updated`，不含长度 → **不转登**。
2. **`E4` 仅 Assertion 侧成立**：`PasskeyCreateActivity.kt:296` 已优先取 `providerReq.callingAppInfo.packageName`，
   报告"未被用于该字段"对 Create 侧为**误报** → 转登时已收窄（`ISSUE-P2-72`）。
3. **报告遗漏的 `otp` 读写归属不对称**：写入进 `entry.fields`、读取落 `entry.customFields`（有回退兜底，
   非安全缺陷）—— 报告自述"十类 Secret 逐项追踪已闭合"因此打折（报告 §10.8 已自认）。

### 41.5 转登 `ACTIVE_ISSUES.md` 的开放项（**33 项**）

| 新编号 | 主题 | 报告来源 |
|---|---|---|
| `ISSUE-P1-25` | `copyUsername` 把 `{REF:P@…}` 口令写进剪贴板且不标敏感 | `H4` |
| `ISSUE-P2-61` | TOTP 种子写入恒 `isProtected = false` | `H1` |
| `ISSUE-P2-62` | `KdbxKeyFile.extractKey` 整文件转 String | `H2` |
| `ISSUE-P2-63` | 生物识别门控只用冷启动快照的 hook 信号 | `H-new-2` |
| `ISSUE-P2-64` | 库级 MemoryProtection 不影响内存密封 | `M1` |
| `ISSUE-P2-65` | 明文 StateFlow 未注册 `SessionLockObserver` | `M3` |
| `ISSUE-P2-66` | 落盘清理 unlink-only + `clearAll()` 不清 `.tmp` | `M4` |
| `ISSUE-P2-67` | 同步下载路径绕过附件落盘 | `L9` |
| `ISSUE-P2-68` | `data class` 默认 `toString()` 打印明文 | `M6` |
| `ISSUE-P2-69` | 日志脱敏测试正则漏 `DebugLogBuffer` 通道 | `M7` |
| `ISSUE-P2-70` | 手动选择器不显示请求方身份 | `E1` |
| `ISSUE-P2-71` | IME 内联建议默认把候选名送入输入法 | `E2` |
| `ISSUE-P2-72` | `clientDataJSON.androidPackageName` 归属（Assertion 侧） | `E4`（收窄） |
| `ISSUE-P2-73` | 自动填充认证流协议漂移（裸 `setResult` / `FLAG_IMMUTABLE`） | `E5` |
| `ISSUE-P2-74` | 包可见性可能使浏览器域自动填充失效 | `E6` |
| `ISSUE-P3-98` | `proguard-rules.pro` 的 `AppLog` 剥离规则为 no-op | `L1` |
| `ISSUE-P3-99` | `changeCredentials` 默认参 clone 未清零 | `L2` |
| `ISSUE-P3-100` | `resolveRemotePath` 解密凭据未清零 | `L3` |
| `ISSUE-P3-101` | `S3RequestSigner` 漏擦 `combined` | `L4` |
| `ISSUE-P3-102` | 全量 SHA-1 作为 String 驻留 | `L5` |
| `ISSUE-P3-103` | `SecureCaptureActivity` 缺遮挡触摸过滤 | `L6` |
| `ISSUE-P3-104` | `KdbxAttachment.data` 与 KDoc 矛盾 | `L7` |
| `ISSUE-P3-105` | `getAttachmentData` 双重拷贝 | `L8` |
| `ISSUE-P3-106` | `data_extraction_rules` 未排除 `external` / `device_*` | `L10` |
| `ISSUE-P3-107` | `.kdbx.bak` 默认保留（上一口令加密） | `L13` |
| `ISSUE-P3-108` | `AtomicFileWriter` 的 `.tmp` 残留窗口 | `L14` |
| `ISSUE-P3-109` | `AutofillLastFilledStore.clear()` 零调用方 | `L15` |
| `ISSUE-P3-110` | 明文导出确认仅在 UI 层（XML / CSV） | `L16` |
| `ISSUE-P3-111` | Fill / Assertion 不检索 provider 请求 | `L17` |
| `ISSUE-P3-112` | `SafDocumentCleanup` 无条件删除 | `L18` |
| `ISSUE-P3-113` | 字段黑名单签名失败即 fail-closed 静默禁用 | `L19` |
| `ISSUE-P3-114` | 剪贴板文案承诺与实现漂移 | `L20` |
| `ISSUE-P3-115` | Gradle Wrapper 分发源为第三方镜像 | `L21` |

### 41.6 未转登（随报告退役）

1. **已撤回 / 误报（3 项）**：`H-new-3`、`H1 附注`、`S1`（下调为中，实质面已由既有 **`ISSUE-P2-55`** 覆盖）。
2. **非缺陷 / 已不存在（4 项）**：`M8`（`flagSecureEnabled` 为产品裁决的有意设计，§29.3 / §38）、
   `L11`（hex `String` 是交付物，格式边界，设计接受）、`L12`（HEAD 已不存在）、`L22`（`_gitobj/` 为空目录，
   git 本不跟踪空目录）。
3. **与既有条目重合（6 项，不重复登记）**：`H-new-1` → `ISSUE-P2-53`；`S1` → `ISSUE-P2-55`；
   `E3` → `ISSUE-P2-43`；`M9` → `ISSUE-P2-51` + `ISSUE-P3-84`；`M5` → `ISSUE-P3-86`；
   `M2` → 输入法通道由 `ISSUE-P3-76` 覆盖，其余（Compose `String` 不可擦）为已接受残余风险（`AGENTS.md` §6）。
4. **已核实为 Info / 非问题（报告 §6.3 尾）**：`CredentialPendingIntents` 的 `FLAG_MUTABLE` 有文档依据；
   已跟踪测试密钥材料为一次性夹具；`keystore.properties` / `release.jks` / `local.properties` 从未被 git 跟踪；
   无 WebView 残留；`network_security_config` 与 `src/main` 的 `http://` 字面量均为 XXE 加固特性；
   `AutoLockManager` 的 `ACTION_SCREEN_OFF` 已带 `RECEIVER_NOT_EXPORTED`；完整性判定非 honor-system。

### 41.7 报告退役与索引同步

- **删除 `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`**。
- ⚠️ **不可恢复声明**：该文档为**未跟踪文件**（`git status` 显示 `Untracked`），删除后
  **无法**经 `git show` / `git log` 取回 —— **不同于** §40.7 的 `SECURITY_AUDIT_2026-09.md`
  （后者为跟踪文件，原文保留于 `9b64415`）。其结论已按 §41.5 / §41.6 全部分流。
- `AGENTS.md` §4 文档索引**移除**该行，并更新「退役纪律」注（§41 立规）。
- `SECURITY_AUDIT_REMEDIATION.md` 头部对本文档的指针改写为「已退役删除 + 转登去向」。

### 41.8 方法纪律（继承 §40.8，本批次新增四条）

1. **审计报告必须在开头锚定基线**：记录 `git rev-parse HEAD` + `git status` 快照，并把全部
   `文件:行号` 明确声明为「核实时刻快照」。本报告缺失该字段，是其**不可直接引用**的唯一根因。
2. **`[A]` 标注的结论不得未经独立复现即提升进最高优先级清单**：`H-new-3` 即此例（标 `[A]` 却进 §1.3）。
   标注体系未失效，失效的是**采信方式**。
3. **区分「不可擦除」与「驻留上界」两条正交轴**：String 一律"不可确定性擦除"，但驻留时长须分别判定
   （函数局部量 = 下次 GC；Compose / prefs 内存映射引用 = 进程生命），不得合并为单一 ★ 等级。
4. **未跟踪文档不得作为唯一证据载体**：审计报告须在**开始阅读时即纳入 git 跟踪**，否则退役 = 永久丢失。

---

## §42 威胁建模与架构评估报告退役批次（`THREAT-MODEL-AUDIT-d32f3e7.md` 退役 + 存量项转登）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对第三方「威胁建模与架构评估（只读阶段）」报告逐条对拍：
**仍成立且此前未登记**的开放项转登 `ACTIVE_ISSUES.md`（14 项，见 42.3）；已登记项给出映射（42.2）；
已复核为「有意设计 / 已声明限界 / 前提不成立」者留痕（42.4 / 42.5）；报告的**分析结论**
（信任边界、对手模型、信任假设、条件化生存性、开放问题与威胁清单）在本节**留存**（42.6）；
随后**退役并删除**该报告。

### 42.1 来源与时间线事实

| 事实 | 证据 |
|---|---|
| 报告基线 = `d32f3e7`（2026-09-12 19:38） | 报告表头「目标版本」 |
| 报告为**只读**阶段产物，自述「未修改任何文件」；证据分级 (a) 代码核实 / (b) 推断 / (c) 无法确认 | 报告头注 |
| 报告随 `9b64415`（§38 批次）与主审计报告一并提交 | `git log --oneline -- docs/THREAT-MODEL-AUDIT-d32f3e7.md` → 仅 `9b64415` |
| 本批次处置时 HEAD = `a669a48`（2026-09-13） | `git log --oneline -1` |
| 报告是**跟踪文件**，退役后可经 `git show 9b64415:docs/THREAT-MODEL-AUDIT-d32f3e7.md` 取回原文（583 行） | `git ls-files` + `git show … \| wc -l` |

### 42.2 已登记（覆盖）项映射（不重复登记）

| 报告条目 | 落点 / 状态 |
|---|---|
| `Q-1`（CM 通道不消费 `RuntimeIntegrityGate`） | `ISSUE-P2-53`（§40 转登） |
| `Q-4`（受保护明文副本未清零） | §38 `ISSUE-P2-36`（已整改） |
| `Q-5`（密钥文件物化 `String`） | `ISSUE-P2-62`（§41 转登） |
| `Q-6`（API 36 SAX 加固实际生效集合） | `ISSUE-P3-82`（设备侧回归缺口） |
| `Q-7`（`copyUsername` 经 `{REF:P@…}` 泄露口令） | `ISSUE-P1-25`（§41 转登） |
| `Q-9`（`.bak` 代际与生命周期） | `ISSUE-P3-107`（§41 转登） |
| `Q-12`（`AppLog.i` 无闸门 / 日志脱敏测试覆盖） | `ISSUE-P2-69`（§41 转登） |
| `Q-13` / `T-8`（防回滚状态随锁库清除） | §38 `ISSUE-P1-20`（已整改：状态迁 `filesDir/rollback`） |
| `Q-17` 前半（`resolveRemotePath` 解密凭据不清零） | `ISSUE-P3-100`（§41 转登） |
| `T-3`（恶意 IME） | `ISSUE-P3-76`（框架阻塞，保留跟踪 + 解除条件） |
| `T-4`（同意保真度：确认页不指名请求方 / 选择器全库搜索） | `ISSUE-P1-24` + `ISSUE-P2-70` |
| `T-7`（恶意 KDBX 造成有界 DoS） | RESOLVED_LOG §22.2 子项 5（「纵深防御缺口当前不可达」） |
| `T-11`（`copyUsername` 口令入剪贴板） | `ISSUE-P1-25` |
| `T-12`（`SecureCaptureActivity` 缺遮挡触摸过滤） | `ISSUE-P3-103`（§41 转登） |
| `T-13`（软 Keystore 落位 fail-open） | `ISSUE-P1-22`（§39 转登） |
| `T-14`（UI 误标 `ChaCha20-Poly1305`） | `ISSUE-P3-92`（§40 转登） |
| `T-15`（KDF `secret(K)` 常驻无清零点） | `ISSUE-P2-60`（§40 转登）；内存附件池部分见 42.3 `P3-119` |
| `T-16`（凭据未清零的实现不一致：`resolveRemotePath`） | `ISSUE-P3-100`；其余两部分见 42.4 / 42.3 `P3-118` |
| `T-2`（弱主口令 + `.bak` 离线破解）/ `T-6`（解锁态 FLAG_SECURE） | 产品裁决 / 已接受残余（§29.3、§33） |
| 交付物 4 的 `B-1`~`B-6`、交付物 5、交付物 6 生命周期分析 | 见 42.6 留存 |

### 42.3 本批次新转登 `ACTIVE_ISSUES.md`（14 项）

| 新编号 | 来源 | 主题 |
|---|---|---|
| `ISSUE-P2-75` | `T-8b` / `Q-14` | 远端读取无尺寸上限 + PROPFIND 递归 / 只 catch `Exception` → OOM / `StackOverflowError` |
| `ISSUE-P2-76` | `Q-2` | CM / Passkey 通道 `BiometricPrompt` 未绑 `CryptoObject`（与自动填充通道不对称） |
| `ISSUE-P2-77` | `Q-16` / `T-9c` | 切换 / 新建库不擦除旧库、不通知锁观察者 |
| `ISSUE-P2-78` | `T-10` | CM 保存路径写入畸形 URL（`https://https://…` / `https://android:apk-key-hash:…`） |
| `ISSUE-P2-79` | `Q-3` / 审计 `A-1`、`A-2` | KDF 强度基线未对齐（建库默认偏弱、导入弱参数原样保留；**需产品确认**） |
| `ISSUE-P3-116` | `T-9b` | 「彻底退出应用」不清缓存（`exitProcess` 不经锁观察者） |
| `ISSUE-P3-117` | `Q-15` / `T-9d` | `clearPasswordOnLeave` 为死开关 + 未提交主密码长期驻留 |
| `ISSUE-P3-118` | `T-16`（残余） | `save()` / `exportToBytes()` 的 `ByteArrayOutputStream` 内部缓冲从不擦除 |
| `ISSUE-P3-119` | `Q-10` / `T-15`（残余） | 内存附件池（`KdbxDatabase.binaries`）无擦除入口 |
| `ISSUE-P3-120` | `Q-11` | `RuntimeIntegrityDetector` 拦截力无实测（启发式、非完整性证明） |
| `ISSUE-P3-121` | `T-17` | 自建内网 WebDAV / NAS 出厂配置下不可用（`ssrfAllowedHosts` 未接线） |
| `ISSUE-P3-122` | 审计附录 C（`T6`） | IPC 面待复核 4 项（`IPC-01` / `IPC-02` / `IPC-05` / `IPC-10`） |
| `ISSUE-P3-123` | 审计附录 C（`T7`） | CI / 供应链硬化遗留 4 项（依赖校验元数据、Daemon JVM 校验和、CI 不跑 instrumented、`mapping.txt` 可见性） |
| `ISSUE-P3-124` | 审计 `SUPPLY-06` / `AC-06` | DAL 出口未接 SSRF 守卫与 TLS-only 声明 |

### 42.4 已复核为「有意设计 / 已声明限界」（不立案，仅留痕）

| 项 | 结论 | 证据 |
|---|---|---|
| `T-5` 受信浏览器白名单过窄（Brave / Edge / Samsung / Focus 走 DAL，DAL 预算常在填充窗口内超时 → 静默不下发候选） | **有意取舍**：无权威来源的指纹一律不收录（禁止臆写），方向 fail-closed | `BrowserSigningFingerprints.kt:11-27` KDoc 载明取证纪律与"禁止臆写" |
| `Q-17` 后半（`WebDavAuthHeader` 以 Base64 `String` 持凭据整个 Provider 生命周期） | **代码内已声明限界**，非新缺陷 | `WebDavAuthHeader.kt:25-26` KDoc「已声明限界」 |
| `SUPPLY-05` 原生不可用时口令强度评估回退 JVM 实现 | **有意设计**：`nativeAvailable` 明示"不应作为业务分支依据"，回退实现有跨语言 parity 合同断言 | `PasswordStrength.kt:106-118`；`PasswordStrengthNativeParityTest` |
| `T-15` 树外引用者持整棵 `KdbxDatabase` 树 | **已由 `ISSUE-P1-07` 收口**（`SyncCoordinator` 注册为锁观察者并释放 `lastSyncedDb` 等） | `SyncCoordinator.kt:96-102` |
| 交付物 4.3「应用进程 = 信任域」（`InMemoryCipher` 进程密钥永不擦除 / 永不轮换） | **已文档化取舍**（原语选择的必然结果，非可"补开关"修复） | `InMemoryCipher.kt:36-44,62,65`；`AGENTS.md` §6 |
| 交付物 4.5「主密码 = 唯一凭据、在线爆破默认无节流」 | 部分已登记（`ISSUE-P2-45` 节流 / `ISSUE-P2-79` KDF 强度 / `ISSUE-P3-107` `.bak`），其余为设计边界 | 同上 + §33 |

### 42.5 已核实为「前提不成立 / 非缺陷」（本批次新增结论）

- **`Q-8` / `C-6`（Compose `rememberSaveable` / `SavedStateHandle` 是否可能承载主密码或条目口令字符）—— 关闭**。
  核实于 2026-09-13（HEAD `a669a48`）：`app/src/main` 内 `rememberSaveable` **零命中**；`SavedStateHandle`
  仅见于 `EntryEditViewModel.kt:44,91-93`（`entryId` / `groupId` / `templateId`）与 `EntryDetailViewModel.kt:48,76`
  （`entryId`）——**只承载标识，不含任何口令 / 主密码字段**。故「秘密不出进程」的不变式在本批次核实范围内成立，
  该开放问题**不再作为待办**（此前列于报告 `§7 Q-8` 与 `§9 C-6`）。

### 42.6 留存结论（随报告退役，本节为单一真相源）

#### (a) 信任边界 TB-1 ~ TB-10

| # | 边界 | 跨越的东西 | 验证者（要点） |
|---|---|---|---|
| TB-1 | 用户 → UI | 主密码、条目口令、TOTP 种子 | 无（人机边界）；侧漏防护 = `FLAG_SECURE`（锁定态强制）+ 遮挡触摸过滤 + 反 overlay；`SecurePasswordField` 为唯一 `CharArray` 桥接点 |
| TB-2 | UI → Repository / 会话 | `CharArray` 主密码 / 密钥文件字节 / 解锁意图 | 节流闸门、空密码拒绝、失败清零、完整性闸门（出厂节流默认关闭 → `ISSUE-P2-45`） |
| TB-3 | Repository → KDBX 编解码 | 明文口令字节、复合密钥、`KdbxDatabase` 树 | 单入口 `KdbxFile.load`（三个生产调用点）+ 凭据缓存克隆语义 + 只读模式 |
| TB-4a | 文件 → 头部认证 | header bytes + 存储的 SHA-256 / HMAC | 常量时间比对，失败即 `KdbxCorruptFileException` / `KdbxInvalidCredentialsException` |
| TB-4b | 密文 → 明文（载荷） | HMAC 认证后的分块密文 | 块 HMAC 先验后用（索引并入密钥）+ 终止块权威检查 |
| TB-4c | 解压 / XML → 对象树 | 解压后明文 XML | 解压上限、XXE 四特性 + handler 侧 fail-closed、深度 ≤64、文本长度上限、内层各上限 |
| TB-4d | Kotlin ↔ Rust（JNI） | 复合密钥、KDF 参数、块数据 | 定长布局契约 + `available` 探活 + `catch_unwind` + `Zeroizing`；失败一律回退 JVM 而非静默重派生 |
| TB-5 | 密码学 → Keystore / TEE | 封印凭据、MAC 密钥、断言私钥 | `KeyGenParameterSpec` + `KeyInfo` 全等探测；解封需 per-op 强生物识别（软 Keystore 落位仅告警 → `ISSUE-P1-22`） |
| TB-6 | 附件 → 磁盘 | 明文附件字节 | 0600 / 0700 + 锁定即清 + **冷启动对账**（§38 `ISSUE-P1-19`） |
| TB-7 | 同步出口 → 不可信云端 | 整份 `.kdbx` 密文、同步凭据、S3 SigV4 签名 | TLS-only + SSRF / DNS 重绑定守卫 + 防回滚 MAC + 三哈希状态机 |
| TB-8 | 其他应用 → 自动填充服务 | `AssistStructure`（调用方可控）、`autofillId`、`webDomain` | 系统背书包名 + 完整性 / 黑名单 + 域归属双向绑定 + 强制二次确认 |
| TB-9 | 其他应用 → Credential Provider | `BeginGetCredentialRequest`、`requestJson`、`origin` | 官方 `getOrigin` + 特权白名单 / `apk-key-hash` 固定颁发；RP-ID 与包名双重严格匹配 + 交付前复验 |
| TB-10 | 应用 → 其他应用 / 系统（出口） | 剪贴板明文、验证码、导出明文、备份 | 敏感标记 + 定时擦除；通知最小化；备份全排除；明文导出需显式二次确认 |

#### (b) 6 条真实有效的安全边界（交付物 4.1）

| 边界 | 拦截对象 | 强度 |
|---|---|---|
| B-1 KDBX4 完整性契约（头 SHA-256 → 头 HMAC → 块 HMAC → 终止块） | 恶意 `.kdbx`、篡改流量、恶意导入文件 | **强**（密码学级、fail-closed、无旁路） |
| B-2 Argon2 KDF + 主密码熵 | 离线破解、未解锁物理接触 | **强但依赖参数与口令强度**（→ `ISSUE-P2-79`） |
| B-3 Android 沙箱 + 组件权限模型 | 普通恶意应用（无 / 有 normal 权限） | **强**（平台提供） |
| B-4 域 / 包名归属绑定（浏览器指纹 + DAL + PSL + 严格标签边界） | 跨应用 / 跨域读取凭据 | **强** |
| B-5 自动填充强制二次确认 + Keystore `CryptoObject` 绑定 | 恶意自动填充客户端静默取走口令 | **中强**（CM 通道未绑定 → `ISSUE-P2-76`） |
| B-6 TEE / StrongBox 不可导出密钥 | 未解锁物理接触、离线窃取 prefs | **中**（可防拷走文件后解密，不防进程内调用；软 Keystore fail-open → `ISSUE-P1-22`） |

#### (c) 15 类对手结论（交付物 3）

| 对手 | 能否最终拿到明文口令 | 决定性拦截点 / 失效原因 |
|---|---|---|
| A 普通恶意应用（无权限） | **否** | 沙箱 + `exported`/BIND 权限 + 域归属校验 + 精确包名相等 |
| B 恶意应用（normal 权限） | **否** | 同上 + `setHideOverlayWindows` + 遮挡触摸过滤 |
| C 恶意输入文件攻击者 | **否** | 解析上限 + XXE fail-closed + 密钥文件不做 XML 解析 |
| D 恶意 KDBX 攻击者 | **否**（最强结论） | 头 HMAC → 块 HMAC → 终止块三道 fail-closed，无主密钥不可构造可通过内容 |
| E 物理接触（设备未解锁） | **否** | Argon2 + TEE 不可导出；设备已解锁则退化为 F |
| F 设备已解锁状态攻击者 | **是** | 自动锁定窗口（默认 60 s）+ 解锁态 `FLAG_SECURE` 随用户开关真实解除 |
| G root | **是** | 无软件边界（`/proc/<pid>/mem`、Frida、调用 Keystore）；`ProtectedString` 进程密钥为静态字段 |
| H 被攻陷的 OS | **是** | 同 G，且可早于应用启动 |
| I 网络攻击者（同步在途） | **否** | TLS-only + 密文传输；无证书固定（有意），但篡改不可被接受；重放受防回滚链限制 |
| J 供应链（反编译 / patch / Frida） | **是** | R8 仅提高成本；`RuntimeIntegrityDetector` 可绕过 |
| K 恶意插件 / 第三方组件 | **是** | 同进程即同信任域 |
| L 恶意 WebView / Intent 源 | **否**（作为普通应用） | 域归属校验 + 非浏览器 origin 被完全忽略；无外部 `startActivity` 拉入敏感页 |
| M 恶意自动填充客户端 | **否** | `setAuthentication` 门控 + 域归属校验；可社交工程诱导确认（→ `ISSUE-P1-24` / `ISSUE-P2-70`） |
| N 恶意 IME | **是** | 应用层无有效边界（框架层无法下发 `IME_FLAG_NO_PERSONALIZED_LEARNING` → `ISSUE-P3-76`，**明示接受**） |
| O 离线数据库破解者 | **否** | Argon2 KDF + 主密码熵（唯一纯密码学边界；强度口径见 `ISSUE-P2-79`） |

#### (d) 信任假设 TA-1 ~ TA-8

| # | 假设 | 若违反 |
|---|---|---|
| TA-1 | 「应用进程内的代码都是受信的」 | 崩溃式失效：主密码、解密树、进程内密钥同处一堆 → G / H / J / K 全部成功 |
| TA-2 | 「Android 沙箱 + TEE 是真的」 | Keystore 密钥可导出、`filesDir` 可被其他 UID 读 → B-3 / B-6 归零 |
| TA-3 | 「用户主密码有足够熵」 | 唯一纯密码学边界失效（`.kdbx` / `.bak` 可被离线解开） |
| TA-4 | 「用户会看清确认框再点确认」 | 自动填充 / 选择器门控退化为形式；确认框不显示请求方使该假设更脆弱 |
| TA-5 | 「设备未被解锁 / 未被攻击者短暂持有」 | 解锁态保护取决于用户开关；自动锁定默认 60 s；窗口期内明文可见 |
| TA-6 | 「KDBX 对象树整体驻留内存是唯一可行实现」 | 内存 dump 可得（与 TA-1 同源）；附件字节已移出（§35），对象树仍在 |
| TA-7 | 「云端只做存储，不参与信任」 | 架构上成立；但**跨路径整文件混淆**不可拦（已明示接受） |
| TA-8 | 「用户不需要输入法保护」 | 恶意 IME 取得全部键入秘密（`ISSUE-P3-76` 明示接受） |

#### (e) 条件化生存性分析（交付物 4.6，结论摘要）

- **(a) 攻击者取得 root**：**仍成立**——TEE / StrongBox 密钥不可导出、`.kdbx` 静态加密、KDBX4 完整性 fail-closed
  （仅对"离线篡改后回归"有意义）、生物识别录入变更即吊销。**不再成立**——`ProtectedString` 驻留加密、
  `FLAG_SECURE`、`RuntimeIntegrityDetector`、自动锁定、剪贴板擦除。
- **(b) 攻击者取得 APK**：**仍成立**——KDBX 静态加密强度（不依赖代码保密）、TEE 密钥不可导出、服务端无秘密。
  **不再成立**——`RuntimeIntegrityDetector` 全部结论、`FlagSecurePolicy`、`AutofillAuthBindingPolicy`、
  `CredentialFillVerifier`、`UnlockThrottle` 及其 MAC 校验、`ExportConfirmationPolicy`；R8 仅成本提升（保留行号）。
- **(c) 攻击者取得 KDBX 文件**：**全部机密性与完整性密码学保证仍成立**（前提 TA-3）。**可以**：离线暴力破解、
  重放整份旧文件（受防回滚链拦截，跨路径混淆不拦）；`.bak` 使攻击面扩大到"曾用过的任何主密码"。
- **(d) 攻击者能控制应用进程**：**仅两项仍成立**——TEE 内密钥不可导出、Keystore `KeyGenParameterSpec`
  约束（如 per-op 强生物识别未授权时无法 `doFinal`）。**其余全部失效**（主密码明文、`ProtectedString` 全部明文、
  解密树、各策略类、节流完整性、运行时完整性）；即便密钥不可导出，仍可在**合法读取瞬间**截获明文（项目已如实声明）。
- **(e) 攻击者只能控制一个恶意 KDBX 文件**：**全部保证仍然成立**（本架构最强结论）——无法通过头部认证、
  无法构造可通过块 HMAC 的载荷、无法借解析器实现任意代码执行；唯一可达影响是**有界 DoS**
  （Argon2 `M` 上限 4 GiB，已登记为「纵深防御缺口当前不可达」，见 §22.2 子项 5）。

#### (f) 「看起来安全但不一定」逐项裁定（交付物 5）

| 假设 | 是否赚到 | 裁定要点 |
|---|---|---|
| 用 AES ≠ 安全 | **赚到**（正确用法范围内） | AES-256-CBC + PKCS#7；AES-ECB 仅出现在规范指定的 AES-KDF 变换且有 lint 抑制理由；IV 每次保存由 `SecureRandom` 重生成；无固定 IV / 无 ECB 误用 / 无 `java.util.Random`；CBC 无内置认证但整条载荷被块 HMAC 覆盖 |
| 用 Argon2 ≠ 安全 | **部分赚到** | 算法 / 版本 / 参数上界 / 原生与兜底差分等价均可；**未赚到**：出厂 `M/I/P` 强度口径（`ISSUE-P2-79`）、`secret(K)` / `A` 随会话长期驻留（`ISSUE-P2-60`） |
| 用 Android Keystore ≠ 自动安全 | **部分赚到** | per-op 强生物识别、`setInvalidatedByBiometricEnrollment(true)`、`setUnlockedDeviceRequired(true)`、StrongBox 优先、`KeyInfo` 全等探测迁移均到位；**未赚到**：软 Keystore 仅告警不硬失败（`ISSUE-P1-22`）、HMAC 类密钥无用户认证门控（KDoc 已如实声明） |
| 用 Rust ≠ 自动内存安全 | **赚到**（就内存安全而言） | `catch_unwind` 全导出包裹、`panic="abort"` 刻意未设、`Zeroizing` 全路径擦除、signed-before-narrowing；**但**秘密跨 FFI 复制，且 `CbcStreams` 在流生命周期内保留调用方 key 引用（顺序安全但属脆弱不变式） |
| 用 Kotlin ≠ 自动安全 | **未赚到——甚至更危险** | `String` 不可擦是主动对抗对象；实测缺口：密钥文件物化 `String`（`ISSUE-P2-62`）、受保护明文副本交主构造（§38 已修）、解锁失败日志含库标识（§38 存量已收敛）。均为「用了 Kotlin 不会自动避免」者 |
| 用生物识别 ≠ 安全 | **部分赚到，存在通道不对称** | 快速解锁与自动填充均已密码学绑定；**CM / Passkey 通道未绑 `CryptoObject`**（`ISSUE-P2-76`）；无强生物识别时两通道退化为受保护窗口内的手动确认（降级手段而非免验证） |
| `FLAG_SECURE` ≠ 全部截屏都被阻止 | **项目自己承认** | 补 `setHideOverlayWindows(true)`；锁定态无条件强制、解锁态随开关真实解除（§29.3）；所有敏感页面共用单窗口 → 关闭时**全部**同时失去保护；`SecureCaptureActivity` 曾缺遮挡触摸过滤（`ISSUE-P3-103`） |
| ProGuard / R8 ≠ 防逆向 | **未声称、也未做到** | release 仅 `isMinifyEnabled + isShrinkResources`，主动保留行号（取舍已写明）；整包 `-keep` 降低利用难度；无字符串加密 / 无完整性自检 / 无反调试 → 逆向与 patch 难度 ≈ 普通加固后的 Android 应用 |
| ChaCha20-Poly1305 ≠ 实现正确 | **本仓根本没有该 AEAD** | 实现是 raw ChaCha20（RFC 7539）+ 独立 HMAC 块流（即 KDBX4 规范）；UI 标签错误（`ISSUE-P3-92`）；曾把 16 字节 IV 静默截断为 12，现已 fail-fast |
| 成熟密码学 crate ≠ 用对了 | **大体赚到，有真实落差** | 用对口：IV / nonce 长度硬校验、`Pkcs7` 单一实现、差分等价测试、KAT。落差：Argon2 `AD ≤ 32B` 使超长 AD 静默改走 BouncyCastle（行为等价但原生路径不覆盖全部输入）；`AesKdfJce` 忽略 `Cipher.update(...)` 返回值（provider 依赖）；`KdfParameters.Argon2.equals/hashCode` 忽略 `secretKey` / `associatedData`、`KdbxHeader.equals` 忽略 `publicCustomData`（**当前无生产消费方**，属潜伏隐患） |
| 实现 KeePass / KDBX 格式 ≠ 继承其安全属性 | **未完全继承** | 项目自身已发现并修两个「本地自读自写永远通过」的结构性掩盖缺陷（写侧恒写 16B IV / Argon2 `P` 用 UInt64）；另有两次「JVM 过、Android 挂」逃逸（§24 / §26）。**教训**：格式兼容性必须由**外部官方实现当裁判** |

#### (g) 开放问题与威胁清单状态总表

| 条目 | 状态 / 去向 |
|---|---|
| `Q-1` / `Q-2` | `ISSUE-P2-53` / `ISSUE-P2-76` |
| `Q-3` | `ISSUE-P2-79`（含审计 `A-1`、`A-2`） |
| `Q-4` / `Q-5` / `Q-6` / `Q-7` | `ISSUE-P2-36`（§38 已整改）/ `ISSUE-P2-62` / `ISSUE-P3-82` / `ISSUE-P1-25` |
| `Q-8` | **本批次核实后关闭**（见 42.5） |
| `Q-9` / `Q-10` / `Q-11` / `Q-12` | `ISSUE-P3-107` / `ISSUE-P3-119` / `ISSUE-P3-120` / `ISSUE-P2-69` |
| `Q-13` / `Q-14` / `Q-15` / `Q-16` / `Q-17` | `ISSUE-P1-20`（§38）/ `ISSUE-P2-75` / `ISSUE-P3-117` / `ISSUE-P2-77` / `ISSUE-P3-100`（前半）+ 42.4（后半） |
| `T-1` / `T-2` / `T-3` | 设计边界（交付物 4.3 / 4.5）；`ISSUE-P3-76` |
| `T-4` / `T-5` / `T-6` | `ISSUE-P1-24` + `ISSUE-P2-70` / 42.4（有意取舍） / 产品裁决（§29.3、§33） |
| `T-7` | 已登记（§22.2 子项 5） |
| `T-8` / `T-8b` / `T-8c` | `ISSUE-P1-20`（§38）/ `ISSUE-P2-75` / Assume-Breach 固有性质（非缺陷，随本表留痕） |
| `T-9` / `T-9b` / `T-9c` / `T-9d` | `ISSUE-P3-107` / `ISSUE-P3-116` / `ISSUE-P2-77` / `ISSUE-P3-117` |
| `T-10` / `T-11` / `T-12` / `T-13` / `T-14` | `ISSUE-P2-78` / `ISSUE-P1-25` / `ISSUE-P3-103` / `ISSUE-P1-22` / `ISSUE-P3-92` |
| `T-15` / `T-16` / `T-17` | `ISSUE-P2-60` + `ISSUE-P3-119` / `ISSUE-P3-100` + `ISSUE-P3-118` + 42.4 / `ISSUE-P3-121` |

#### (h) 无法确认 / 所需信息（报告 §9）

| # | 事项 | 归属 / 状态 |
|---|---|---|
| C-1 | arm64 真机 + 真实语料的端到端解锁 | `ISSUE-P3-23`（产品裁决不排期） |
| C-2 | Passkey 系统级交互 / `AssistStructure` 真实结构树 / 通知渲染的设备侧行为 | `ISSUE-P3-66`（产品裁决不排期） |
| C-3 | API 36 上 SAX 加固特性的实际生效集合（`Q-6`） | `ISSUE-P3-82`（设备侧回归） |
| C-4 | Android 沙箱是否确实阻止其他 UID 读取 `filesDir/*.kdbx` 与 `cacheDir` | 平台语义保证（(b) 推断），未做越权读取实测 |
| C-5 | `securityLevel` 为 SOFTWARE 的设备上的实际风险（`T-13`） | `ISSUE-P1-22`（要求设备侧实测） |
| C-6 | Compose `rememberSaveable` / `SavedStateHandle` 是否承载敏感字符（`Q-8`） | **已核实关闭**（42.5） |
| C-7 | 核验报告提供的 `webDomain` 在真实浏览器上是否总能拿到可用值 | 设备侧（可归入 `ISSUE-P2-42` 同族验证） |
| C-8 | `onSaveRequest` 是否只会在用户确认保存 UI 之后被系统调用 | 框架语义；与 `ISSUE-P3-122`（`IPC-10`）同批复核 |
| C-9 | `AtomicFileWriter` tmp / target 的实际 umask 权限 | 设备侧 `stat`（与 `ISSUE-P2-66` 同族） |
| C-10 | release 产物中是否存在未预期的类 / 字符串残留 | 需对 `app-release.apk` 逆向核对（`ISSUE-P3-123④` 同批） |

### 42.7 报告退役与索引同步

- **删除 `docs/THREAT-MODEL-AUDIT-d32f3e7.md`**；该文件为跟踪文件，原文可经
  `git show 9b64415:docs/THREAT-MODEL-AUDIT-d32f3e7.md` 取回（583 行）。
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律）。
- 报告内对现存问题的全部结论已转登 `ACTIVE_ISSUES.md`（14 项，42.3）；
  「有意设计 / 已声明限界 / 前提不成立 / 无法确认 / 留存结论」以本节为**单一真相源**。

### 42.8 方法纪律（继承 §41.8，本批次新增三条）

1. **威胁建模报告的 `Q`（开放问题）/ `T`（威胁清单）/ `C`（无法确认）三类必须分别处置**：
   `Q` 须给出「成立并转登 / 已覆盖 / 核实后关闭」之一，`T` 须给出严重度复核结论，
   `C` 须给出「归属既有条目 / 保持不可确认」——**不得整篇留档而无人流转**。
2. **「有意设计」与「缺陷」必须分列**：如 `T-5`（白名单过窄）与 `Q-17` 后半（凭据 `String` 驻留）
   均已在代码 KDoc 内声明取舍，登记时应引其出处，**不得当作新发现重复上报**（延续 §39.10 第 5 条）。
3. **第三方报告的「未登记开放问题」是本次审计流程的盲区**：本报告 `Q-2` / `Q-11` / `Q-14` / `Q-15` /
   `Q-16` 等多项在其提交时即成立，但既未进 `ACTIVE_ISSUES.md` 也未进 §40 的转登清单——
   根因是 §40 / §41 只处置了**审计主报告**与**敏感数据流报告**的清单，未覆盖**配套威胁建模文档**。
   故立规：**同批提交的多份审计文档必须**在同一退役批次内**逐份处置**。

---

## §43 安全问题与整改方案报告退役批次（`SECURITY_AUDIT_REMEDIATION.md` 退役 + 附录 A–F 留存）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。`SECURITY_AUDIT_REMEDIATION.md` 本是 2026-09 外部安全审计
主报告退役后的「该轮审计唯一留存记录」（§40.6）。本批次核实其**正文 32 项条目已全部完成覆盖**
（§40.2 / §40.4 / §40.5，无缺口），其**附录 A–F 的非待办类结论**移交本库留存后，**退役并删除**该文档。

### 43.1 来源与事实

| 事实 | 证据 |
|---|---|
| 该文档为 2026-09 外部审计（基线 `d32f3e7`）的整改执行清单 + 主报告退役后的唯一留存记录 | 文档头注 + §40.6 |
| 随 `9b64415` 提交；`§40` 批次追加了附录 A–F（**该次追加在原文档中未提交**） | `git log --oneline -- docs/SECURITY_AUDIT_REMEDIATION.md` → 仅 `9b64415`；`git status` 显示 `M` |
| 本批次处置时 HEAD = `a669a48` | `git log --oneline -1` |
| 退役后可经 `git show 9b64415:docs/SECURITY_AUDIT_REMEDIATION.md` 取回**未含附录 A–F** 的版本（528 行）；附录 A–F 的完整内容自本批次起以 §43.3 ~ §43.8 为单一真相源 | `git show … \| wc -l` |

### 43.2 正文 32 项覆盖完整性核对（无缺口）

| 类别 | 数量 | 落点 |
|---|---:|---|
| 已在 §38 批次整改（`F-09` / `F-11` / `F-13` / `F-15` / `F-23` / `F-25`） | 6 | §40.2 |
| 与原红队批次条目重合（`F-01` → `ISSUE-P2-45`；`F-18` 风险明示面 → `ISSUE-P3-84`） | 2（不重复登记） | §40.4 |
| 转登 `ACTIVE_ISSUES.md` | 25 | §40.5（`P2-48`~`P2-60`、`P3-86`~`P3-97`） |
| **合计** | **32** | 与附录 A 口径注（下表 35 行 = `F-01…F-25` 25 项 + `RUST-01…RUST-11` 11 项中另见 §13.N 明细者）一致 |

> **计数纪律**：本表以 §40.5 的转登清单为权威口径；文档内「29 项 / 32 行 / 31 行」并存的自相矛盾见 §40.3，
> **不得**再作为计数依据（延续 §40.8 第 2 条）。

### 43.3 附录 A — CVSS 4.0 ↔ CWE 对照表（原件 §9 / §13.N，**档案留存**）

> **口径注**：原总表实际 31 行（`F-01…F-25` 共 25 + `RUST-01…RUST-06` 共 6），
> 另 `RUST-07…RUST-11` 仅见于原报告 §13.N 明细；其「29 项」计数与分布表存在内部矛盾（见 §40.3），
> 故本表**仅作档案留存，不作为计数依据**。「不适用」表示原判定该项不可被攻击者利用
> （方向 fail-closed / 零调用点 / 文档缺陷），依报告自身纪律不强行打分。

| ID | 标题（简） | 严重度 | 分类 | CWE | CVSS 4.0 向量 | 分数 |
|---|---|---|---|---|---|---|
| F-01 | 解锁节流生产默认关闭 | MEDIUM | Security Weakness | CWE-307 | AV:P/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N | 4.6 |
| F-02 | 明文导出缓冲未清零 | LOW | Hardening | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:L/VI:N/VA:N | 2.3 |
| F-03 | 导出确认仅在 UI 层 | LOW | Hardening | CWE-602 | AV:L/AC:H/AT:N/PR:H/UI:P/VC:L/VI:N/VA:N | 2.0 |
| F-04 | Chrome 指纹条目 65 字符永不匹配 | LOW | Confirmed Vulnerability | CWE-1289 | 不适用（fail-closed） | — |
| F-05 | 依赖 CVSS 闸门未接入自动触发 | HIGH | Security Weakness | CWE-693 | AV:N/AC:L/AT:N/PR:L/UI:N/VC:L/VI:H/VA:L | 7.3 |
| F-06 | 发布密钥库口令即示例口令 | MEDIUM | Security Weakness | CWE-1391 | AV:L/AC:H/AT:P/PR:N/UI:N/VC:H/VI:H/VA:N | 5.9 |
| F-07 | 审计摘要截断至 32 位 | INFO | Hardening | 不适用 | 不适用（无安全影响） | — |
| F-08 | 公开死函数缺参数校验 | INFO | Security Weakness | CWE-1164 | 不适用（零调用点） | — |
| F-09 | Salsa20 nonce 常量错误 | MEDIUM | Confirmed Vulnerability | CWE-1240 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:H/VA:N | 5.3 |
| F-10 | 附件引用放大 | HIGH | Confirmed Vulnerability | CWE-400/770 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:H | 6.5 |
| F-11 | 外层头部无总量上限 | MEDIUM | Confirmed Vulnerability | CWE-770 | AV:L/AC:L/AT:N/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| F-12 | KDF 无墙钟预算 | MEDIUM | Security Weakness | CWE-400 | AV:L/AC:L/AT:N/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| F-13 | 解密附件/密文快照无冷启动清理 | MEDIUM | Confirmed Vulnerability | CWE-459/212 | AV:P/AC:L/AT:P/PR:N/UI:N/VC:H/VI:N/VA:N | 4.0 |
| F-14 | DAL 响应体先物化后检查 | LOW | Confirmed Vulnerability | CWE-770 | AV:L/AC:H/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| F-15 | 解密后的受保护值明文未清零 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| F-16 | HmacBlockStream.readAll 非常时比较 | LOW | Security Weakness | CWE-208 | 不适用（无生产调用者） | — |
| F-17 | UI 误标 ChaCha20-Poly1305 | INFO | Design Concern | CWE-1059 | 不适用（文档缺陷） | — |
| F-18 | 剪贴板不随锁定清理 + 误清他处内容 | LOW | Security Weakness | CWE-226 | AV:P/AC:H/AT:P/PR:N/UI:P/VC:H/VI:N/VA:N | 3.4 |
| F-19 | 仅取首个签名者 | LOW | Hardening | CWE-1289 | 不适用（fail-closed） | — |
| F-20 | 冗余/废弃权限 | INFO | Hardening | CWE-272 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.0 |
| F-21 | 填充确认不校验锁定 | LOW | Design Concern | CWE-613 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:L/VI:N/VA:N | 3.1 |
| F-22 | 选择器锁定后崩溃 | LOW | Potential Vulnerability | CWE-248 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| F-23 | 同步防回滚状态随锁库清除 | HIGH | Confirmed Vulnerability | CWE-693 | AV:N/AC:H/AT:P/PR:N/UI:P/VC:N/VI:H/VA:N | 6.9 |
| F-24 | CM 通道从不查询 RuntimeIntegrityGate | MEDIUM | Confirmed Vulnerability | CWE-693 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:H/VI:L/VA:N | 4.4 |
| F-25 | 解锁失败日志含库 id / 密钥文件长度 / 异常原文 | LOW | Security Weakness | CWE-532 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:L/VI:N/VA:N | 2.4 |
| RUST-01 | Argon2 工作内存未擦除 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-02 | 派生密钥栈副本残留 | LOW | Hardening | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-03 | 口令强度评估二次复杂度 → 主线程 ANR | MEDIUM | Security Weakness | CWE-407 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| RUST-04 | 非法 UTF-8 口令的未擦除副本 | INFO | Hardening | CWE-226 | 不适用（调用方不可达） | — |
| RUST-05 | 原生路径缺内存上界预检 | LOW | Hardening | CWE-770 | AV:L/AC:H/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| RUST-06 | KDF secret `K` 常驻无清零点 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-07 | Kotlin CBC 加密流遗留未擦除明文分块 | LOW | Security Weakness | CWE-226 | （原报告未给向量） | — |
| RUST-08 | Twofish JNI 创建输出数组前回写 IV | INFO | Design Concern | — | 不适用（无调用方在异常后继续） | — |
| RUST-09 | CI 从不执行 JNI 边界测试；符号表核对未实现 | LOW | Design Concern | — | 不适用 | — |
| RUST-10 | Kotlin `Arrays.fill` 不受 JVM 保证 | INFO | Hardening | — | 不适用（平台固有限制） | — |
| RUST-11 | 一次性 cipher API 对整段缓冲全量拷贝 | INFO | Hardening | — | 不适用（生产未使用） | — |

### 43.4 附录 B — 经查证不成立的疑似问题（误报排除，**防重复上报**）

| 编号 | 疑似问题 | 排除理由 |
|---|---|---|
| FP-01 | `OtpEngine.parseOtpAuthUri` 缺校验致除零/指数爆炸 | 该函数**零调用点**（生产走 `TotpKeyUriParser`，`period`/`digits` 已钳制）→ 不可达 |
| FP-02 | 同上（重复论证） | 同 FP-01 |
| FP-03 | `OtpEngine.calculateHotp` 的 `hash[offset+3]` 越界 | `offset = hash[19] & 0x0F ∈ [0,15]`，`offset+3 ≤ 18 < 20`；SHA-256/512 边界更宽 |
| FP-04 | 「解锁节流可经自动填充入口绕过」 | 三个解锁入口共用同一 `UnlockViewModel.gate()`，不存在平行入口 |
| FP-05 | 内层流「每个值重置」 | 规范要求状态**不**重置，实现正确 |
| FP-06 | 生产 Hilt 未注入真实依赖致 fail-open | release 生成组件已证明为**测试专用**，生产注入真实现 |
| RUST 侧 1 | 负值经 `as u32` 穿过闸门 | 有符号 `jint` 前置判定成立，负值不可能变为巨值 |
| RUST 侧 2 | panic 跨 JNI 边界 UB | 4 个导出函数全 `catch_unwind` 包裹，panic 归一为 `null` |
| RUST 侧 3 | Twofish JNI 原地修改 IV | 属**文档化契约**（流式调用方依赖它；一次性调用方传 `iv.copyOf()`）→ 不可利用 |

另：**CWE-22 路径穿越**经查证不成立——附件缓存 key 为随机 UUID（`FileBinaryStore`）。

### 43.5 附录 C — 待复核区（原件 §10，**未经逐条复核，不作为待办**）

> 原报告自述这些条目「证据充分但首席审计员尚未逐条核实，故暂不编号、不计入统计」。
> 本批次**首次**把它们转为可复跑的待办（下表右侧），以避免"线索随文档删除而丢失"。

**T6（IPC / 自动填充 / 剪贴板）提交**：`IPC-01` 跨响应复用 `requestCode` 致 PendingIntent extras 串扰 ·
`IPC-02` 凭据落地 Activity 以可变 extras 作身份来源 · `IPC-03` 选择器不显示请求方 · `IPC-04`（已复认为 `F-04`）·
`IPC-05` `CallingOriginResolver` 白名单 JSON 偏离官方 schema · `IPC-06` `clientDataJSON.androidPackageName`
取自已废弃 `getCallingPackage()` · `IPC-07`（已并入 `AC-02`）· `IPC-08` FLAG_SECURE 解锁态真实解除 ·
`IPC-09` 无障碍 / 输入法属设计边界 · `IPC-10` `onSaveRequest` 无超时预算 · `IPC-11` `android://<pkg>` 未与签名绑定。
（T6 另提交 16 项已验证强项。）

**T7（供应链 / 构建 / CI）提交**：`SUPPLY-01`（已复认为 `F-05`）· `SUPPLY-02` 无 `verification-metadata.xml` /
依赖锁定 · `SUPPLY-03`（已复认为 `F-06`）· `SUPPLY-04` 未文档化的 DAL 出口 · `SUPPLY-05` 原生不可用时强度检测
静默降级 · `SUPPLY-06` DAL 未接 SSRF 守卫与 TLS-only · `SUPPLY-07` CI 不运行 instrumented 用例 ·
`SUPPLY-08` Gradle toolchain 未锁校验和。

| 待复核项 | 本批次去向 |
|---|---|
| `IPC-01` / `IPC-02` / `IPC-05` / `IPC-10` | **`ISSUE-P3-122`**（合并为「IPC 面待复核 4 项」，要求逐条给出成立/不成立结论） |
| `IPC-03` / `IPC-06` / `IPC-11` | 已分别由 `ISSUE-P2-70` / `ISSUE-P2-72` / `ISSUE-P2-46` 覆盖 |
| `IPC-04` | 已由 `ISSUE-P3-88` 覆盖（`F-04` 复认） |
| `IPC-07` | 已由 `ISSUE-P2-51` + `ISSUE-P3-84` 覆盖（`AC-02` 口径） |
| `IPC-08` / `IPC-09` | 产品裁决 / 设计边界（§29.3、`ISSUE-P2-44`、`ISSUE-P3-76`） |
| `SUPPLY-01` / `SUPPLY-03` | 已由 `ISSUE-P2-54` / `ISSUE-P2-55` 覆盖 |
| `SUPPLY-02` / `SUPPLY-07` / `SUPPLY-08` | **`ISSUE-P3-123`**（合并为「CI / 供应链硬化遗留 4 项」，含 `mapping.txt` 可见性） |
| `SUPPLY-04` / `SUPPLY-06` | **`ISSUE-P3-124`**（DAL 出口纵深防御，含「未文档化出口」如实登记要求） |
| `SUPPLY-05` | **已复核为有意设计**（回退实现有 parity 合同断言，见 §42.4）→ 不立案 |

### 43.6 附录 D — 无法确认 / 所需信息（原件 §11）

| # | 无法确认的问题 | 解除所需 |
|---|---|---|
| C-1 | Android Keystore 密钥在解密路径中是必要条件还是装饰性闸门 | 读全部解密调用点 + 设备侧验证「锁定后无认证能否解密」 |
| C-2 | `CallingAppInfo.getOrigin()` 对 `build:"default"` 与 `userdebug` 布尔键的容忍度 | 真机以 Chrome 触发通行密钥请求，打印 `resolveTrustedOrigin` 结果 |
| C-3 | 系统是否在多次响应间复用同一 `PendingIntentRecord` | 同一测试应用连续两次 `getCredential`，打印落地 Activity 收到的 extras |
| C-4 | 框架是否接受「属于另一会话的 `AutofillId`」 | 设备侧自动填充会话测试 |
| C-5 | ChaCha20/Salsa20 内流的 nonce/counter 重置语义与 nonce 复用可能性 | `crypto` 内流实现逐行复核（**注**：`F-09` 常量错误已由 §38 修复） |
| C-6 | PKCS7 填充校验与坏填充错误处理路径 | `crypto` CBC 流实现复核 |
| C-7 | gzip 解压炸弹的流式边界与 `MAX_*` 上限的实际强制位置 | `database` 解压路径复核 |
| C-8 | XML 解析器 XXE / DTD / 深度限制 | 已由 `KdbxXmlParser` 加固路径 + `ISSUE-P3-82` 设备侧回归覆盖 |
| C-9 | TEE / StrongBox 在具体硬件上的认证行为；`KeyPermanentlyInvalidatedException` 语义 | 设备侧测试（见 `AGENTS.md` §6 真机待补） |
| C-10 | 分支保护是否要求 `build` 工作流通过（决定 `F-05` 的爆炸半径） | `gh api repos/{owner}/{repo}/branches/main/protection` |
| C-11 | `dependency-scan` 是否曾对 `d32f3e7` 运行过 | `gh run list --workflow=dependency-scan.yml` |
| C-12 | 发布 APK 是否字节可复现 | 两次干净 `GRADLE_USER_HOME` 的 `assembleRelease` + `diffoscope` |
| C-13 | 完整传递依赖图（CVSS 闸门实际扫描的集合） | `./gradlew :app:dependencies` |
| C-14 | `mapping.txt` 产物的可见范围（对公众开放等同公开去混淆映射） | CI 产物可见性设置（→ `ISSUE-P3-123④`） |

### 43.7 附录 E — 已确认的安全强项（原件 §1.3 / §9B.2，**非缺陷**）

1. **认证先于解密，端到端**：头部 SHA-256 常时比较 → 头部 HMAC → 才 `loadPayload`；载荷内每个 HMAC 块在解密前验证。
2. **全部密钥材料比较使用常时算法**（`MessageDigest.isEqual`），未发现对秘密派生字节使用 `==` / `String.equals`。
3. **Rust FFI 边界纪律完整**：导出函数全 `catch_unwind`；窄化前有符号 `jint` 闸门 + `saturating_mul`；秘密经 `Zeroizing`。
4. **`unsafe` 仅 5 处**，全为 `u8`→`i8` 同布局重解释，SAFETY 注释成立；无 `transmute` / 裸指针解引用。
5. **`AssociatedData` 上限 fail-closed**：Rust 侧 >32 B 返回 `None`，Kotlin 侧显式改走 BouncyCastle。
6. **SSRF / DNS 重绑定防护**（`SyncEndpointGuard`，含 IPv4-mapped IPv6 递归判定，「任一解析结果被挡即整体拒绝」）。
7. **备份面已封堵**：`allowBackup=false` + `data_extraction_rules` 对 cloud-backup 与 device-transfer 双通道排除。
8. **发布包最小化**：无 WebView、无 `addJavascriptInterface`、无动态加载、无隐式动态广播。
9. **导出组件面**：仅 `MainActivity` + 两个 `BIND_*`（signature）服务；无 `ContentProvider` / `FileProvider` / 深链。
10. **R8 日志剥离线真实生效**（`AppLog.d`/`v` 已在 release 移除）。
11. **签名凭据从未入库**（对象级证明：全历史仅 `keystore.properties.example`）。
12. **供应链无遥测**：无 analytics / ads / crash-reporting SDK；全部 CI Action 以 commit SHA 钉死；
    Gradle Wrapper 分发 SHA-256 已锁定；`Cargo.lock` 入库并以 `--locked` 使用。

### 43.8 附录 F — 组合攻击链（原件 §8，供威胁评估引用）

| 编号 | 链条 | 结论 |
|---|---|---|
| AC-01 | 弱主口令 + 节流默认关闭 + 已解锁设备 → 本地暴力破解 | MEDIUM（受 Argon2 成本限制） |
| AC-02 | 敏感剪贴板 + 进程在超时前被杀 → 口令长期驻留剪贴板 | LOW（依赖时序） |
| AC-03 | 示例口令公开 + 密钥文件经其他渠道泄露 → 伪造签名更新 | MEDIUM（**前提**：密钥文件外泄；密钥材料未入库） |
| AC-04 | 无效浏览器指纹条目 + DAL 不可用 → 功能退化 | fail-closed，**无凭据泄露** |
| AC-05 | （**已排除**）自动填充入口绕过解锁节流 | 三入口共用同一 `gate()`，不成立 |
| AC-06 | DAL 使用裸 `OkHttpClient`（无 SSRF 守卫 / TLS-only `ConnectionSpec`） | **本批次已核实并转登 `ISSUE-P3-124`** |
| AC-07 | 进程在解锁态被杀 + 已解析大附件 → 无需口令获得附件明文 | MEDIUM（**已由 §38 `ISSUE-P1-19` 冷启动清理闭环**，残余窗口见 `AGENTS.md` §6） |
| AC-08 | 弱主口令 × 节流默认关闭 × 冷启动计数窗口 | MEDIUM（已由 `ISSUE-P2-45` 承接） |

### 43.9 附：非缺陷类产品决策（原件「附」节，A-1 ~ A-4）

| 项 | 内容 | 本批次去向 |
|---|---|---|
| A-1 | **新建库 KDF 默认强度偏弱**（默认 Argon2id `m=64 MiB, t=2, p=2`；`KdfBenchmark` 已有设备自适应建议但**建库路径未消费**） | **`ISSUE-P2-79`**（需产品确认，已核实 `KdbxHeader.kt:162-171`） |
| A-2 | **导入无工作量下限**（接受 `m=1 MiB, t=1, p=1` 与 AES-KDF `R=1`，保存时原样保留弱参数） | **`ISSUE-P2-79`**②③ |
| A-3 | **`F-13` 配套文档收紧**（`FileBinaryStore` KDoc 与 `AGENTS.md` §6/§35 的「锁定即闭环」措辞） | **已在 §38 落实**（`AGENTS.md` §6 已改为如实声明非正常终止路径的残余窗口） |
| A-4 | **`mapping.txt` 可见范围**（77.5 MB，retrace 必需品；对公众开放等同公开去混淆映射） | **`ISSUE-P3-123④`** |

### 43.10 报告退役与索引同步

- **删除 `docs/SECURITY_AUDIT_REMEDIATION.md`**；该文件为跟踪文件，原文（未含 §40 追加的附录 A–F）可经
  `git show 9b64415:docs/SECURITY_AUDIT_REMEDIATION.md` 取回（528 行）；附录 A–F 自本批次起以 §43.3 ~ §43.9 为准。
- `AGENTS.md` §4 文档索引**移除**该行；
- `ACTIVE_ISSUES.md` 内指向该文档的「详细修复方案 / 唯一留存记录」指针改指本节（§43）；
- §40.6 中「上述四类由 `SECURITY_AUDIT_REMEDIATION.md` 附录 A–F 承接」的表述**由本节取代**
  （历史原文保留在 §40，仅追加本指针，不改写既往记录）。

### 43.11 方法纪律（继承 §42.8，本批次新增两条）

1. **「唯一留存记录」文档也必须可退役**：退役条件是「正文条目 100% 已在 `ACTIVE_ISSUES.md` / 本库有落点」
   **且**「附录类非待办结论已在本库留存」——二者缺一不得删除（本批次以 43.2 的完整性核对表作为前置证据）。
2. **待复核区（未逐条复核的线索）不得以"不计入统计"为由直接丢弃**：应转为**待办条目**
   （本批次即把 `IPC-01/02/05/10`、`SUPPLY-02/04/06/07/08`、`mapping.txt` 转为 `P3-122` / `P3-123` / `P3-124`），
   或在核实后写明证否依据——**"未经复核"不是"不作处置"的理由**。

---

## §44 P0 双项整改批次：字段引用消费点白名单 + 同步崩溃面遏制（2026-09-13）

> **本批次缘起**：第四轮独立复核定版（`SECURITY_RECHECK_2026-09.md`）转登的两项 P0——
> `ISSUE-P0-08`（`{REF:P@…}` 口令经用户名通道 4 个泄漏出口离开应用）与 `ISSUE-P0-09`
> （远端把"同步失败"提升为"进程崩溃"的遏制绕过）同批整改闭环；`ISSUE-P0-09` 的整改
> **同时覆盖 `ISSUE-P2-75` 全部 AC**（后者为其降级前的原始条目，本批一并闭环归档）。

### 44.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P0-08** | P0 | `FieldReferenceEngine` 取值模式无消费点白名单：条目 `UserName` 含 `{REF:P@…}` 时，被引用条目的**口令明文**经用户名通道离开应用（剪贴板 / 确认页 extra / 数据集 RemoteViews / IME 内联建议 / 请求方输入框） | 引擎 `resolve` 强制显式 `consumerField`（**消费点面白名单**）：口令消费点（`P`）保持 KDBX 语义展开；非口令消费点（`T/U/A/N/I`）遇 `wantField == P` 或 `searchField == P` 输出既有 `PROTECTED_PLACEHOLDER` 掩码，**递归链全程约束**。`VaultRepository.resolveFieldReferences` 签名加 `consumerField`（无默认值，编译期强制声明意图），**5 处调用点同批覆盖**：`AutofillDatasetBuilders.kt` username 通道→`USER_NAME`、password 通道→`PASSWORD`；`EntryDetailViewModel` copyPassword→`PASSWORD`、copyUsername→`USER_NAME`；`AutofillPickerViewModel`→`PASSWORD`。展示侧 `resolveForDisplay` 语义不变 | `FieldReferenceEngineTest` 新增 4 例（P 取值面掩码不外泄 / P 检索面掩码 / `U@`、`T@` 负例行为不变 / 掩码随递归链全程约束）；`FieldReferenceDisplayModeTest`、`FieldReferenceEngineDeviceTest`（含新增设备侧 P0-08 回归锁）同批更新；既有 `FieldReferenceEngineTest` 全部调用点补声明消费点面 |
| **P0-09** | P0 | 远端（或系统 CA 级 MITM）可单方面把"同步失败"提升为"进程崩溃"：provider `runCatching` 捕到 `Error`（超深 XML 的 `StackOverflowError` / 超大响应 OOM）包成 `Result.failure`，经引擎 `getOrThrow()` 原样重抛，`SyncCycleRunner` 仅捕 `Exception` → `Error` 脱网杀死进程且每周期复发 | 三道防线同批落地（AC①"必须同时"）：① **下载体入口封顶**——新增 `SyncDownloadLimits`（声明尺寸预检 + 流式累积双重封顶，超限抛 `ProtocolError(413)`；数据库 128 MiB / PROPFIND 16 MiB），WebDAV / S3 的 `download` 与 PROPFIND `getMetadata` 全部改有界读取；② **`WebDavPropfindParser.findNodes` 递归 → 显式栈迭代 + `MAX_XML_DEPTH = 64` 深度上限**（超深节点不采信），parse 捕获面扩到 `Throwable`；③ **`SyncCycleRunner` 捕获面**：`handleOpenRemote` 与 `runSyncCycle` 外层均 `catch (Throwable)` 归一为 `SyncOutcome.Error`（`CancellationException` 原样重抛保结构化并发） | `SyncDownloadLimitsTest` 5 例（声明超限拒收不消费流 / 声明缺失流式封顶 / 声明撒谎中途拒绝 / 正常尺寸完整读取 / 常量量级）；`WebDavPropfindParserTest` 3 例（正常解析行为不变 / **5000 层超深 XML 遏制为回退元数据不栈溢出** / 上限内正常解析）；`SyncCoordinatorTest` 新增端到端用例——模拟 provider 重抛 `StackOverflowError`，断言遏制为 `SyncOutcome.Error` 而非进程崩溃 |
| P2-75 | P2（P0-09 前身） | 远端读取无尺寸上限（P0-09 的降级前原始条目） | **随 P0-09 一并闭环**——其 AC①②③ 与 P0-09 完全同构（下载体双重封顶 / PROPFIND 迭代化+深度上限+`Throwable` 捕获 / 超大响应与超深 XML 负例），无剩余独立面 | 同 P0-09 |

### 44.2 验收证据

#### (1) 单测全绿（权威强制重跑，2026-09-13）

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 38s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 111 | 833 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 353 | 0 | 0 | 0 |
| sync | 20 | 203 | 0 | 0 | 13 |
| **合计** | **200** | **1570** | **0** | **0** | **13** |

**基线变动**：1557 → **1570（+13 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
新增分布：app +1（`SyncCoordinatorTest` P0-09 端到端）、database +4（`FieldReferenceEngineTest` 白名单）、
sync +8（`SyncDownloadLimitsTest` ×5 + `WebDavPropfindParserTest` ×3）。

#### (2) AC 逐条核对

- **P0-08**：AC① 白名单落在引擎取值通道且 `consumerField` 为无默认值参数（不存在无约束取值入口）✓；
  AC② 全仓 `resolveFieldReferences` 消费点穷尽复核恰 5 处、同批全覆盖 ✓；AC③ 正例 `UserName = {REF:P@A:target}`
  断言掩码且不含明文、负例 `{REF:U@…}` / `{REF:T@…}` 行为不变 ✓（外部工具明文 `otp` 走自定义字段通道，
  不经本引擎，天然不受影响）。
- **P0-09**：AC① 捕获面 + `findNodes` 迭代化/深度上限 + 下载体封顶三道同批 ✓；AC② `SyncCycleRunner`
  两处捕获均已改（provider 之外的重抛链在引擎层）✓；AC③ 超大响应（`SyncDownloadLimitsTest` 三种形态）与
  超深 XML（`WebDavPropfindParserTest` 负例）均被拒绝且不 OOM / 不崩溃 ✓；AC④ 正常尺寸同步
  （`SyncCoordinatorTest` 既有 5 例 + `SyncDownloadLimitsTest` 正常读取例）不受影响 ✓。
- **附注（设备侧）**：P0-08 新增 1 例设备侧回归锁
  （`FieldReferenceEngineDeviceTest.设备上非口令消费点遇密码引用输出掩码`），计入 `database`
  androidTest 源集，待下次设备批次随基线实测（本批 JVM 侧已覆盖同语义用例）。

---

## §45 P1 双项整改批次：确认页调用方归属与首次绑定授权 + 剪贴板口令面引用敏感通道（2026-09-13）

> **本批次缘起**：P1 节四项中经第四轮定版（`SECURITY_RECHECK_2026-09.md`）仍保持「修 P1」的
> 两项同批整改闭环——`ISSUE-P1-24`（终评 MEDIUM）与 `ISSUE-P1-25`（终评 MEDIUM；其根因已于
> §44 由 `ISSUE-P0-08` 消费点白名单闭环，本批补齐 AC①②③ 的通道侧整改）。
> `ISSUE-P1-22`（降修 P2）与 `ISSUE-P1-23`（降 P2 产品告知项）仍留 `ACTIVE_ISSUES.md` 待后续批次。

### 45.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P1-24** | P1（MEDIUM） | 自动填充确认页只展示条目标题、不展示调用方归属（包名 / 签名 / 域），且无「首次绑定」显式授权——恶意应用渲染高仿登录页即可诱导用户确认填充；另会话授权宽限（P3-42）可携带**口令值**免确认下发 | ① **AC① 归属展示**：`CredentialFillConfirmScreen` 新增 `attributionContent` 槽位与 `confirmEnabled` 门控（默认参数，既有调用方 `PasswordFillActivity` 零改动）；`AutofillConfirmActivity` 强制渲染不可伪造锚点——调用方**包名**（系统结构树来源，经本服务构建的 FLAG_IMMUTABLE 确认 PendingIntent extra 传递）+ **签名证书 SHA-256**（复用 `AutofillOriginResolver.callingAppCertSha256Hex`，`private`→`internal`；包可见性受限读取失败时如实标注「不可读」）+ **归属校验后的目标域**（未通过校验的域本就不下发候选）；② **AC② 首次绑定**：新增 `AutofillCallerTrustStore`（信任键 = 归一化包名 + 签名摘要，SharedPreferences 持久化，`context=null` 内存语义供 JVM 单测；同包名**换签名重新视为首次**；非法包名 fail-closed）；首次出现目标**不进入系统认证弹窗**（归属与授权只能在受保护窗口内展示执行），强制走手动确认且须显式勾选「记住此应用」方可确认；已授权目标走系统弹窗时把包名并入副标题；③ **AC③ 口令禁无 UI 下发**：`AutofillAuthenticationPolicy.skipRepeatConfirmation` 新增 `datasetCarriesPassword` 参数——授权宽限对携带口令值的数据集一律不生效（口令仅在显式确认后下发，用户名可例外），`AutofillDatasetBuilders` 调用点同步 | `AutofillCallerTrustStoreTest` 新增 7 例（首次未授权 / 授权后命中 / **换签名重置** / 摘要不可读退化仅包名 / 跨包名不互通 / 非法包名 fail-closed / 撤销对称）；`AutofillSessionGrantStoreTest` 新增 1 例（携带口令值 → 宽限不生效）；strings.xml / values-en 新增 8 条归属与授权文案 |
| **P1-25** | P1（MEDIUM） | `copyUsername` 把解析结果恒走明文通道（不设 `EXTRA_IS_SENSITIVE`、不调度擦除）；且 `ClipboardSecurityManager.copyPlainText` **无条件** `cancelScheduledClear()` 会把「上一次敏感复制」的擦除计划一并取消 | ① **AC①**：`FieldReferenceEngine` 新增 `containsPasswordFaceReference`（P 取值面 / P 检索面逐引用判定，大小写不敏感）；`EntryDetailViewModel.copyUsername` 分流——UserName 含口令面引用时走 `copySensitiveText`（`EXTRA_IS_SENSITIVE` + 自动擦除调度；P0-08 白名单已把此类引用掩码输出，敏感通道为白名单被未来削弱时的纵深防线），无引用行为不变；② **AC②**：`copyPlainText` 移除无条件 `cancelScheduledClear()`（结构性消除，全仓仅此一处调用）——到期哈希比对保证：剪贴板已被普通内容覆盖时不误清、仍持有敏感值时按时清除；③ 通道抽象 `ClipboardSecurityChannel` 接口（生产经 `SecurityModule` `@Binds` 绑定 `ClipboardSecurityManager`），ViewModel 依赖接口便于纯 JVM 断言通道决策 | `FieldReferenceEngineTest` 新增 6 例（P 取值面 / P 检索面 / 大小写不敏感 / 公开字段负例 / 混合文本逐引用判定 / 无引用直返）；`EntryDetailViewModelTest` 新增 3 例（P 取值面引用 → 敏感通道 / P 检索面引用 → 敏感通道 / 无引用 → 明文通道行为不变），经 `RecordingClipboardChannel` 记录桩断言通道选择 |

### 45.2 AC 逐条核对

- **P1-24**：AC① 确认页强制展示不可伪造归属 ✓（手动确认路径恒渲染归属块；首次目标强制走手动路径；
  已授权目标走系统弹窗时包名并入副标题——系统弹窗无法渲染自定义归属块，取舍见 45.3）；
  AC② 首次绑定显式授权 ✓（勾选前确认按钮禁用 + 信任存储持久化 + 换签名重置）；
  AC③ 口令仅显式确认后下发 ✓（宽限策略排除口令数据集；既有 `setAuthentication` 路径语义不变）；
  AC④ 三条各有回归 ✓（信任存储 7 例 + 宽限策略 1 例；归属展示属 Compose 渲染层，其数据装配
  与授权判定已由信任存储 / 归属数据类承载并被 JVM 断言，设备侧登记见 45.3）。
- **P1-25**：AC① ✓（口令面引用 → `copySensitiveText`，`EXTRA_IS_SENSITIVE` + 擦除调度由敏感通道
  统一承载）；AC② ✓（无条件 `cancelScheduledClear()` 已结构性删除，全仓该 API 零无条件调用点）；
  AC③ ✓（3 例通道分流回归 + 6 例引擎检测回归）。

### 45.3 已知边界与设计取舍

1. **归属展示的设备侧实测未含于本批**：确认页 / 归属块为 Compose 渲染，属「JVM 全绿不构成证据」的
   Android 运行时面（同 ISSUE-P2-42 纪律）；建议并入下次设备批次一并实测（复现配方：首次向任意应用
   触发自动填充确认 → 核对归属块渲染与勾选门控；已授权应用 → 核对系统弹窗副标题含包名）。
2. **签名摘要读取受 Android 11+ 包可见性约束**：`ISSUE-P2-74`（缺 `<queries>`）闭环前，对不可见包
   摘要恒「不可读」并如实标注；信任键随之退化为仅包名——P2-74 落地后自动增强为「包名 + 摘要」，
   无需本批代码变更。
3. **「记住此应用」为持久授权**：撤销通道为既有「为本应用禁用自动填充」黑名单（屏蔽即完全停止向该
   应用填充）；确认页取消勾选可撤销当次记录（`untrust` 对称实现）。
4. **授权宽限的口径变化（有意收紧）**：P3-42 会话授权宽限自本批起仅对**不携带口令值**的数据集生效
   （纯用户名表单）；携带口令值时一律回退「每次强制确认」，与 AC③ 一致。

### 45.4 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 1m 43s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 112 | 844 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 359 | 0 | 0 | 0 |
| sync | 20 | 203 | 0 | 0 | 13 |
| **合计** | **201** | **1587** | **0** | **0** | **13** |

**基线变动**：1570 → **1587（+17 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
新增分布：app +11（`AutofillCallerTrustStoreTest` ×7 + `AutofillSessionGrantStoreTest` ×1 +
`EntryDetailViewModelTest` ×3）、database +6（`FieldReferenceEngineTest` 口令面检测）。

---

## §46 P1 双项整改批次：软件级 Keystore 快速解锁降级确认 + 重打包威胁告知留痕（2026-09-13）

> **本批次缘起**：P1 节剩余两项同批整改闭环——`ISSUE-P1-22`（第四轮终评 LOW / DESIGN WEAKNESS，
> 条目留 P1 节至闭环）与 `ISSUE-P1-23`（第四轮终评 INFO–LOW / P2 产品告知项）。
> **附带发现并修复 1 项 P1 级隐藏功能缺陷**（AndroidKeyStore SecretKey 探针 API 误用，见 46.3）——
> 该缺陷由 P1-22 AC③ 强制的设备侧实测暴露，属 AGENTS §6 所述「JVM 过、Android 运行时挂」的又一实例。

### 46.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P1-22** | P1（LOW / DESIGN WEAKNESS） | 封印密钥落位等级（SOFTWARE / UNKNOWN）不影响任何行为——软件级 Keystore 上封印照常建立且无任何提示，封印载荷可被离线解出主密码 | ① **策略单点**：`UnlockAuthPolicy.requiresDowngradeConsent(level)`——SOFTWARE 与 UNKNOWN（无法证明硬件落位）一律须「显式降级确认」（AC① 第四轮修正：不取「无条件禁用」，保住模拟器 / CI 生物识别路径）；② **探测接线**：`BiometricAuthManager.getKeySecurityLevelForDatabase`（封印路径唯一消费点），封印协调器新增 `SealedKeyProvision` 供给链——**先经 `prepareEncryptCipher` 建钥、后探测实际落位**（密钥不存在时探测恒 UNKNOWN，次序不可颠倒）；③ **确认闸门**：`BiometricEnrollmentCoordinator` 在落位须降级且 `quickUnlockDowngradeAcknowledged == false` 时挂起解锁流程、经 UI 状态 `quickUnlockDowngradeConsentPending` 驱动解锁页 `QuickUnlockDowngradeConsentDialog`（60s 超时未决 → fail-closed 跳过封印；确认 → `SettingsRepository.setQuickUnlockDowngradeAcknowledged(true)` 持久化留痕（AC②）；拒绝 → 关闭 `biometricEnabled` 不封印不留痕）；④ **常驻声明**：确认记录驱动解锁页快速解锁卡片与安全设置页生物识别开关下方常驻渲染「本机快速解锁降级为软件密钥，不提供硬件级保护」（中英双语资源），并带自愈逻辑（确认记录残留但实际落位为 TEE/StrongBox 时自动清除）；⑤ strings.xml / values-en 新增 5 条 | `QuickUnlockSealDowngradeTest` 新增 8 例（策略纯函数 1 + JVM 全流程 7：SOFTWARE 未确认拒绝 / UNKNOWN 同策略 / 确认后持久化 / 拒绝关开关 / 已确认不重复弹窗 / TEE 直放 / 超时 fail-closed，均注入假探测结果）；`QuickUnlockSealDowngradeDeviceTest` 设备侧 3 例（见 46.4） |
| **P1-23** | P1（INFO–LOW / P2 产品告知项） | 重打包 APK 无检测且 `installer==null` 判为无风险——应用内自检对该威胁无效，需应用外信任根告知 | ① **AC①**：README 新增「官方签名指纹」节——公布 release 签名证书 SHA-256（`F3:A6:F0:92:…:84:2E`，本批经 `keytool -list` 对 `release.jks` 实算）+ `apksigner verify` / `keytool -printcert` 可复跑核对命令 + 安全须知（应用内自检不能证明 APK 未被篡改）；② **AC②**：`RuntimeIntegrityDetector.detectUntrustedInstallSource` KDoc 显式决策留痕（`installer==null` 不升级风险系显式产品决策及其三条理由，并明确「不引入无效的应用内签名自校验」）；③ **AC③**：尚未上架任何商店（README「已知局限」已声明），登记为上架前置项（Play Integrity 或同等平台完整性证明），暂不适用 | 无代码行为变更（文档 + KDoc 留痕）；指纹核对命令本身即 AC① 的可复跑校验路径 |

### 46.2 AC 逐条核对

- **P1-22**：AC①（修正版）✓——SOFTWARE / UNKNOWN 一律「显式降级确认 + 常驻声明」（`UnlockAuthPolicy.requiresDowngradeConsent` 单点语义，模拟器 / CI 生物识别路径不被打断）；AC② ✓——封印建立前弹窗风险提示、确认经 `setQuickUnlockDowngradeAcknowledged` 持久化留痕、解锁页 + 安全设置页常驻声明；AC③ ✓——JVM 8 例注入假探测结果断言「SOFTWARE 未经确认 → 封印被拒」等分支 + 设备侧 3 例实测（见 46.4）。
- **P1-23**：AC① ✓（README 公布指纹 + 核对命令）；AC② ✓（`installer==null` 语义在 KDoc 显式决策留痕，未以应用内自检充当整改）；AC③ ✓（未上架 → 如实登记为上架前置项）。

### 46.3 附带发现并修复：AndroidKeyStore SecretKey 探针 API 误用（P1 级功能缺陷）

- **发现过程**：P1-22 设备侧用例首跑即失败——模拟器（Pixel_10，x86_64 / API 36.1）上 `getKeySecurityLevel` 恒返回 `UNKNOWN`。加入 provider 服务诊断后实锤：**AndroidKeyStore provider 仅注册 EC / RSA / XDH / ED25519 的 `KeyFactory`**，`KeyFactory.getInstance("AES", "AndroidKeyStore")` 恒抛 `NoSuchAlgorithmException`。
- **根因**：`KeystoreKeyMaterial` 两处对 **SecretKey** 条目取 `KeyInfo` 误用 `java.security.KeyFactory`；官方 API 为 `javax.crypto.SecretKeyFactory`（`SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)`）。原实现 catch-all 吞掉异常：
  - `probeSecurityLevel` 恒返回 `SECURITY_LEVEL_UNKNOWN`（诊断 API 失效，P1-22 修复前该 API 在任何设备上从未真实工作过）；
  - `getOrCreateDeviceCredentialKey` 的 ISSUE-P1-08 规格探针恒判「不匹配」→ **每次解封前删钥重建**——解密用「新密钥 + 旧 IV」必然失败，**快速解锁功能自 ISSUE-P1-08 以来在真实设备上必然损坏**（宿主 JVM 无法触及 AndroidKeyStore，故 844 例 JVM 单测全绿不放行该缺陷；典型「JVM 过、Android 运行时挂」）。
- **修复**：两处改用 `SecretKeyFactory`（EC 私钥探针的 `KeyFactory` 用法合法，保留）。修复后设备侧探测实返回 `SOFTWARE`（模拟器）。
- **登记说明**：本缺陷随 P1-22 同批发现、同批修复，不单独占编号；其修复由设备侧用例 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` 长期回归锁定。

### 46.4 设备侧实测记录（x86_64 / API 36.1 模拟器 Pixel_10，`emulator-5554`，`OK (3 tests)`）

| 用例 | 结果 | 说明 |
|---|---|---|
| `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` | PASS | 真实 AndroidKeyStore 密钥实测落位 = SOFTWARE（P1-22 问题前提在 Android 运行时成立，非推算）；同时锁定 46.3 的 `SecretKeyFactory` 修复 |
| `未录入强生物识别时封印fail-closed且不请求降级确认` | PASS | 生产默认供给链（真实建钥）在未录入 Class 3 生物识别时抛 `InvalidAlgorithmParameterException` → 供给失败 → 不请求确认、不封印、不改用户设置（fail-closed 闭环） |
| `SOFTWARE落位降级确认闸门在设备侧闭环` | PASS | 注入假 `SOFTWARE` 落位，在真实 Android 运行时验证闸门三条分支：**拒绝** → 关闭 `biometricEnabled` 且不留确认记录、不建立封印；**确认** → 确认记录持久化、开关不被改写；**已有记录** → 不再重复请求确认 |
| **环境边界（如实声明）** | — | 模拟器**无法录入** Class 3 强生物识别（emulator console 无 enroll 子命令），故「SOFTWARE 确认后经真实 BiometricPrompt 授权并落盘封印密文」的**端到端**链路仍须在已录入生物识别的设备实测；该链路的封装逻辑（`BiometricSealedPayloadCodec` / 登记弹窗）已有既有设备侧与 JVM 覆盖 |

### 46.5 已知边界与设计取舍

1. **供给次序**：封印密钥供给（建钥 + 探测）置于宿主 Activity / `canSeal` 闸门**之前**（确认无需宿主 Activity）；未录入生物识别的设备上建钥抛异常被供给链捕获 → fail-closed 跳过封印（设备侧实测覆盖），与既有 `canSeal` 闸门语义一致。
2. **确认记录口径**：`quickUnlockDowngradeAcknowledged` 是「用户曾显式确认在软件密钥上快速解锁」的持久标记（跨库共享）；封印时逐次以真实落位重判（确认只在「落位须降级且未记录」时请求），硬件设备不受残留标记影响；解锁页常驻声明带自愈（存在封印凭据且实测落位为硬件时清除标记）。
3. **拒绝语义**：弹窗「不启用」（含取消 / 关闭）→ 关闭 `biometricEnabled`——软件降级是落位受限设备上唯一可用路径，拒绝即等于不启用快速解锁，避免每次解锁重复弹窗。
4. **确认挂起上限**：60s（与登记弹窗同量级）——确认弹窗挂起期间主密码明文驻留窗口有界，超时 fail-closed 跳过封印。

### 46.6 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL；全量 1595 例 / 0 失败 / 0 错误 / 13 跳过（app 852 / core 65 / crypto 116 / database 359 / sync 203）
$ adb shell am instrument -w -e class com.keepasskey.app.security.QuickUnlockSealDowngradeDeviceTest \
    com.keepasskey.test/androidx.test.runner.AndroidJUnitRunner
# → OK (3 tests)
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL；产物 app\build\outputs\apk\release\app-release.apk（已签名）
```

**基线变动**：1587 → **1595（+8 例）**；跳过数 13 与旧基线一致。新增分布：app +8
（`QuickUnlockSealDowngradeTest`）+ 设备侧 app 15 例（+3：`QuickUnlockSealDowngradeDeviceTest`）。
`AGENTS.md` §1 / §6 基线已同步。

**过程缺陷（如实留痕）**：全量回归首跑 `SyncCacheTest > clear 与 clearAll 均不删除防回滚状态文件`
1 例失败（残留他例的 `.CACHE.tmp`，与本批改动无关）——单独复跑该类即通过（`--rerun-tasks`），
全量重跑全绿，判定为异步 `.tmp` 写入竞态的偶发失败；若再次复现应按缺陷登记。

---

## §47 设备侧真机基线批次：两条「模拟器环境假设」用例整改 + arm64 真机全量实测（2026-09-14）

> **本批次缘起**：首次将设备侧全量基线（app / database / sync / crypto 四模块 androidTest）
> 放到 **arm64 真机**上执行。此前该基线的既定环境是 **x86_64 / API 36.1 模拟器**（§34 / §36 / §46），
> 两条用例把**模拟器环境语义当成了设备侧通用语义**，真机首跑即失败（32 例中 30 过 / 2 挂）；
> 二者均属**用例缺陷**（无产品行为变更），已按「环境无关」口径整改并在真机复跑到全绿。

### 47.1 交付清单

| 编号 | 类型 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **设备侧-43** | 用例缺陷（假失败） | `NativeArgon2InstrumentedTest` 断言「被测 APK 内应存在 `lib/<nativeDir.name>/libkeepasskey_argon2.so`」，把平台**短 ABI 目录名**（`ApplicationInfo.nativeLibraryDir` 末段 `arm64`）直接当成 APK zip 条目的**完整 ABI 名**（`arm64-v8a`）——x86_64 模拟器上两者同名故一直为绿，arm64 真机上必然失败 | `apkAbiDirNameFor()` 归一化：`arm64→arm64-v8a`、`arm→armeabi-v7a`、`x86`/`x86_64` 同名（依据 AOSP `VMRuntime.getInstructionSet()`）；加载证据打印同时给出 `nativeLibraryDir末段` 与 `apkAbiDir` | 该用例真机 PASS（7/7）；加载证据见 47.3 |
| **设备侧-44** | 用例缺陷（假失败） | `QuickUnlockSealDowngradeDeviceTest` 中 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` 硬编码断言落位 = `SOFTWARE`，等于假设「设备侧 = 无 TEE 的软件 Keystore」——真机（有 TEE）实测为 `TRUSTED_ENVIRONMENT`，必然失败 | 改为**环境分支断言**：① 无条件断言「落位 ≠ `UNKNOWN`」（锁 46.3 的 `SecretKeyFactory` 修复，环境无关）；② 软件 Keystore 环境（模拟器，`isSoftwareKeystoreEnvironment()` 按 `PRODUCT`/`HARDWARE`/`FINGERPRINT` 特征判定）→ 断言 `SOFTWARE`；③ 真机 → 断言 `TRUSTED_ENVIRONMENT` / `STRONGBOX`。用例更名为 `AndroidKeyStore真实密钥落位在设备侧返回真实等级` | 该用例真机 PASS（app 15/15）；实测值见 47.3 |

### 47.2 设备侧实测记录（arm64 真机，Redmi 4X / LineageOS `lineage_Mi8937_4_19`，Android 17 / **API 37**，`ro.hardware=qcom`，userdebug）

| 模块 | 用例数 | 结果 | 耗时 | 备注 |
|---|:--:|:--:|:--:|---|
| `app` | 15 | **15 pass / 0 fail / 0 skip** | — | 域解析 7 + 导入 3 + 解锁落盘 2 + Keystore 封印 3（整改后复跑） |
| `database` | 7 | **7 pass / 0 fail / 0 skip** | 18.8s | 含 **真实语料端到端解锁 2/2**（`RealKdbxCorpusUnlockTest`，单例 ~8.9s，arm64 真机） |
| `sync` | 3 | **3 pass / 0 fail / 0 skip** | 0.14s | 落盘权限基线 |
| `crypto` | 7 | **7 pass / 0 fail / 0 skip** | 83.3s | 原生↔BC **逐字节一致**、R1 性能闸门（79.6s，本机为旧款 SoC，**耗时不构成性能结论**）、JNI 通路 |
| **合计** | **32** | **32 pass / 0 fail / 0 skip** | — | 整改后**单批次合并复跑**（四任务同一 Gradle 调用）为 32/32；首跑 30/32（两例假失败见 47.1） |

### 47.3 验收证据

```powershell
.\gradlew.bat :app:installDebug            # → Installed on 1 device（arm64-v8a/armeabi-v7a/x86_64/x86 四 ABI 均真实编译，cargo-ndk 未降级）
.\gradlew.bat :crypto:connectedDebugAndroidTest :database:connectedDebugAndroidTest `
              :sync:connectedDebugAndroidTest :app:connectedDebugAndroidTest
# 首跑 → database 7/7、sync 3/3；crypto 6/7 FAILED、app 14/15 FAILED（两例假失败）
#   ① java.lang.AssertionError: 被测 APK（…com.keepasskey.crypto.test…/base.apk）内应存在 lib/arm64/libkeepasskey_argon2.so
#   ② java.lang.AssertionError: … expected:<SOFTWARE> but was:<TRUSTED_ENVIRONMENT>
# 整改后复跑 → .\gradlew.bat :crypto:connectedDebugAndroidTest :app:connectedDebugAndroidTest → BUILD SUCCESSFUL（7/7、15/15）
# 合并复跑（同一 Gradle 调用，四任务）→ BUILD SUCCESSFUL in 2m 34s；
#   结果 XML 汇总：tests=32 failures=0 errors=0 skipped=0（app 15 / database 7 / sync 3 / crypto 7）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL；全量 1595 例 / 0 失败 / 0 错误 / 13 跳过（与 §46 基线一致，本批未改单测）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL；产物 D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#   （15,422,987 B，已签名；同目录含 app-release.apk.idsig）
```

**设备侧直接证据（用例自打印，取自 `*/build/outputs/androidTest-results/connected/debug/**/logcat-*.txt`）**：

```text
# NativeArgon2InstrumentedTest（crypto）
[NativeArgon2 设备侧加载证据] nativeLibraryDir末段=arm64, apkAbiDir=arm64-v8a,
  supportedAbis=arm64-v8a, armeabi-v7a, armeabi,
  nativeLibraryDir=/data/app/~~…==/com.keepasskey.crypto.test-…==/lib/arm64,
  apk=…/base.apk, lib/arm64-v8a/libkeepasskey_argon2.so=486896 bytes, 磁盘解包副本=false
# → 平台短 ABI 名与 APK 完整 ABI 名确为两套命名；`.so` 自 APK 内 mmap 加载（未解包落盘）

# QuickUnlockSealDowngradeDeviceTest（app）
[Keystore 落位设备侧实测] level=TRUSTED_ENVIRONMENT, 软件Keystore环境=false,
  MODEL=Redmi 4X, PRODUCT=lineage_Mi8937_4_19, HARDWARE=qcom, SDK=37, ABI=arm64-v8a, armeabi-v7a, armeabi
# → 真机落位为 TEE（非 UNKNOWN，46.3 的 SecretKeyFactory 修复在真机同样成立）
```

### 47.4 已知边界与口径（如实声明）

1. **命名引用更正**：§46.3 / §46.4 中「设备侧用例 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE`
   长期回归锁定」指的是本批同一条用例——该用例经 47.1 更名为
   `AndroidKeyStore真实密钥落位在设备侧返回真实等级`（断言口径由「硬编码 SOFTWARE」改为「环境分支」），
   §46 正文按历史留痕不改写。
2. **模拟器侧口径不回归**：`isSoftwareKeystoreEnvironment()` 为**启发式**（平台无「是否具备 TEE」的公开查询，
   仅有 `FEATURE_STRONGBOX_KEYSTORE`）；模拟器（`PRODUCT=sdk*` / `HARDWARE=ranchu|goldfish` / 旧镜像 `FINGERPRINT=generic`）
   仍走 `SOFTWARE` 断言分支，故模拟器基线与 §46.4 的结论不受本批影响。
3. **本批未改变任何产品行为**：仅改 `androidTest` 源集（2 个文件），`app/src/main` 与其余模块零改动。
4. **仍未覆盖**（沿用既有登记，不因本批而消解）：`ISSUE-P2-42`（敏感对话框 `FLAG_SECURE` 实效 /
   附件缓存冷启动清理 / `SecureDialog` provider 命中）与 `ISSUE-P2-80`（KDF 墙钟与内存闸门分路径实测）
   仍为开放项——本批只证明「既有设备侧用例在 arm64 真机可复跑且全绿」，**不构成**上述两项的验收。

---

## §48 存量安全整改批次：KDF 预算 + TOTP 保护 + 剪贴板闭环 + 明文持有者锁观察者 + 换库前置释放 + 附件引用预算 + 完整性门控对称化 + Passkey 归属与验证绑定（2026-09-14）

> **本批次缘起**：认领 `ISSUE-P2-48 / P2-49 / P2-51 / P2-53 / P2-61 / P2-63 / P2-65 / P2-72 / P2-76 / P2-77`
> 与 P3 升格项 `P3-109 / P3-117`（均为第四轮独立复核后**升格 P1 排期**的开放项）及联动项 `ISSUE-P3-84`。
> 整改落在 `app/src/main` / `database/src/main`，配套单测同步补齐；
> `P2-49` 的 AC② 因墙钟量级未实测（挂 `ISSUE-P2-80`）**未闭环**，条目仍留在 `ACTIVE_ISSUES.md`；
> `P3-116 / P3-120` 涉及真机实测，本环境无设备，仍保留。

### 48.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-61** | P2（升格 P1） | TOTP 种子在**三处生产写入路径**恒 `isProtected = false` → XML 走非保护分支、种子以明文落盘（无 `Protected="True"`、无内层流 XOR） | `VaultEntryMapper.mapUiEntryToKdbx`、`VaultEntryWriteCoordinator`（合并更新 + `saveTotpSecret`）三处改 `isProtected = true`；回归锁「新建路径写出受保护 + 往返后仍受保护 + 明文 `otp` 仍可读（兼容外部库）」 | 官方 `Write.cs:838-854`（非标准字段保留 per-value `IsProtected`） |
| **ISSUE-P2-51** | P2（升格 P1） | ① `clearClipboard()` 仅由定时器调用，未接锁定 / 熄屏 / 冷启动；② 后台读不到剪贴板时 `lastSensitiveHash` **陈旧匹配**会误清他处内容 | `ClipboardSecurityManager`：实现 `SessionLockObserver`（`DatabaseModule` 注册）+ 熄屏广播 + `ProcessLifecycleOwner` 切后台即清（P3-84）+ 冷启动对账（跨进程仅存**布尔**待清标记）；新增 `clipboardSuperseded` 标志 + 纯裁决 `ClipboardClearPolicy` 消除误清 | 审计 F-18；`AGENTS.md` §6 冷启动缺口同族 |
| **ISSUE-P3-84** | P3 | 关闭「自动擦除」无风险明示；敏感值无前台切走即清机制 | 设置页关闭态渲染 `sec_clipboard_risk_notice`（中英双语）；切后台即清（见上）；文案引导「改用自动填充直填」 | 审计 M9 / 产品裁决语义（对齐 `FlagSecurePolicy`） |
| **ISSUE-P2-65** | P2（升格 P1） | 多处持有明文的 ViewModel/控制器**未注册 `SessionLockObserver`**：锁库后明文继续驻留（原仅靠 `onCleared` / 导航离开擦除） | `EntryDetailViewModel`（按需解密明文 + 实时 TOTP 码）、`GeneratorViewModel`（生成结果，参数由 `ClipboardSecurityManager` 收窄为 `ClipboardSecurityChannel` 以便注入断言）、`EntryEditViewModel`（口令 / 种子 / 受保护字段编辑态）、`SettingsViewModel`（WebDAV 口令 / S3 SecretKey / AccessKey 预填通道）四处注册锁观察者并在 `onCleared` 注销；断言「锁库 → 明文已清零」 | 审计 M3；机制对齐 `DatabaseModule` / `SyncCoordinator` 既有 4 处用法 |
| **ISSUE-P2-77** | P2（升格 P1） | **切换 / 新建密码库不擦除旧库、不通知锁观察者**：`SessionOpener.create/openStream` 直接替换 `core.database.value` → 旧库全部 `ProtectedString` 密文滞留至 GC；旧库的同步缓存与**明文附件缓存**不被驱逐 | `DatabaseSession.releaseSessionStateForReplacement()`（语义对齐 `lock()`，但不取互斥锁避免重入）+ `SessionOpener` 新增 `releaseCurrentSession` 回调，在 `create` / `openStream` **装载新库之前**调用；回归断言「换库事件序 = `clear`→`store`」+「旧库受保护字段已擦除」+「新库附件存活」 | 威胁建模 Q-16 / T-9c；顺序硬约束见 `SessionOpener` KDoc |
| **ISSUE-P2-48** | P2（升格 P1） | **附件引用放大无累计预算**：同一池条目被引用 N 次即 N 份 `copyOf()` 副本；`MAX_XML_ELEMENTS` 与整包上限只**间接**约束该乘积 | 新增 `BinaryReferenceBudget`（`2 × 池总字节 + 1 MiB` 余量，fail-closed），经 `KdbxXmlParser` → `FileNode/RootNode/GroupNode/EntryNode/HistoryNode` → `BinaryNode` 逐层传入；**不取消**逐引用物化（ISSUE-P3-07 契约防线） | 审计 F-10；`KdbxAttachmentAliasIsolationTest` 4 例保持通过 |
| **ISSUE-P2-53** | P2（升格 P1，与 P2-63 同批） | **CM 主通道（`passkey/`）对 `RuntimeIntegrityGate` 零命中**：完整性裁决在自动填充 fail-closed，CM 通道 fail-open（风险态仍下发候选 / 保存入口） | `KeePasskeyCredentialProviderService` 注入门控，在 `buildBeginGetResponse` / `buildBeginCreateResponse` 两条入口**最先**裁决 `awaitEnforcement().disableAutofill`，风险态返回**空响应**；静态接线回归断言「两入口各裁决一次 + 自动填充通道同判据」 | 审计 F-24；通道对称性与自动填充对齐 |
| **ISSUE-P2-63** | P2（升格 P1，与 P2-53 同批） | **生物快速解锁门控只用冷启动快照**：非 suspend `currentEnforcement()` 把 `hookFrameworkDetected` 硬编码为 `false`，钩子重扫仅存在于 suspend 路径 → 启动后附加 Frida 不触发信号、门控不拦 | `RuntimeIntegrityDetector`：① 由「一次性扫描」改为**后台周期重扫**（30s，注入信号进入快照）；② `currentEnforcement()` 显式并入快照内的钩子信号；③ 新增 `RuntimeIntegrityPolicy.isSnapshotStale`——快照陈旧（默认窗口 120s）或未判定即返回保守策略并触发重扫（fail-closed） | 审计 H-new-2；`RuntimeIntegrityPolicyTest` 既有「实时钩子升级」用例 + 新增 3 例新鲜度用例 |
| **ISSUE-P2-72** | P2（升格 P1，审计 E4 已收窄） | `clientDataJSON.androidPackageName` 归属可错：Assertion 侧用 `Activity.getCallingPackage()`（PendingIntent 拉起时为 `android`/null → 回退**本应用包名**，向 RP 虚假归属）；Create 侧 `?: callingPackage` 回退可产生 `android://android` 绑定 | `CallingOriginResolver` 新增 `systemAttestedPackageName`（仅接受平台 `CallingAppInfo`，排除 `android`/空白，**禁止回退**）与 `clientDataAndroidPackageName`（候选过滤，全不可用即**省略字段**）；Assertion 侧改取系统认证包名并与 `EXTRA_EXPECTED_PACKAGE` 交叉核对（不一致即 fail-closed）；Create 侧回退收窄、DAL 门控对 null fail-closed 拒绝；两侧 `clientDataJSON` 均不再回退本包名 | 审计 E4（收窄）；`CallingOriginResolverAttributionTest`（6 例，含核心负例「android 候选被跳过」） |
| **ISSUE-P2-76** | P2（升格 P1，威胁建模 Q-2） | **两条凭据通道认证强度不对称**：自动填充通道有 Keystore `CryptoObject` 密码学绑定，CM / Passkey 通道只凭 `BiometricSucceeded` 回调放行（hook 可伪造） | `CredentialVerificationLauncher` 复用自动填充已验证的同一机制：`prepareAutofillAuthCipher()` 准备绑定 Cipher 并以 `CryptoObject` 发起认证，放行条件升级为 `isSatisfied && AutofillAuthBindingPolicy.isBound(result)`（**无 CryptoObject 不放行**）；Cipher 不可用时**降级为受保护窗口内手动确认**（与自动填充同策略），绝不回落回调级放行 | 威胁建模 Q-2；静态接线回归 `CredentialVerificationBindingWiringTest`（4 断言）；`AutofillAuthBindingPolicyTest` 既有 3 例复用 |
| **ISSUE-P3-109** | P3（升格 P1） | `AutofillLastFilledStore.clear()` 全仓**零调用方**，与 KDoc「换库 / 锁库时清」矛盾 → 上次填充条目 UUID 在明文 prefs 长期驻留 | 该类实现 `SessionLockObserver`，`DatabaseModule` 注册——锁定 / 关闭 / **换库**（`releaseSessionStateForReplacement`）统一清除；新增静态装配清单回归（`DatabaseModuleLockObserversWiringTest`）防「装配被静默移除」 | 审计 L15；`AutofillLastFilledStoreTest`（+2：锁定回调清空 / 幂等） |
| **ISSUE-P3-117** | P3（升格 P1，威胁建模 Q-15 / T-9d） | `clearPasswordOnLeave` 为**死开关**（9 处命中全为持久化 / 投影 / UI 回调，零行为消费方）；未提交主密码跨后台 / 旋转长期驻留 | `UnlockViewModel` 注入 `ExtendedSettingsStore` 并新增 `onScreenLeft()`（开关开启 → `wipeMasterPassword` + 递增擦除令牌）；`UnlockScreen` 经 `DisposableEffect`（ON_STOP / onDispose）接线；断言以「清空后提交必命中空密码拦截」为可观测证据（不引入测试后门） | 威胁建模 Q-15 / T-9d；`UnlockViewModelClearOnLeaveTest`（3 例：开启清空 / 关闭保留 / 令牌递增） |

### 48.2 ISSUE-P2-49 进展（**部分完成，条目保留在 `ACTIVE_ISSUES.md`**）

- **AC①（已完成）**：`KdbxKdfParameterCodec.validateArgon2Bounds` 增加 **`I×M` 联合预算** `2^40` 字节·轮
  （逐项封顶不约束总工作量，单项均合法时乘积可达 `2^58`；该派生**先于** Header HMAC，**无需口令即可触发**）。
  取值宽于本仓 `KdfBenchmark` 自荐上限（≤512 MiB × 20 ≈ 2^33.3）约 100 倍；官方参数域对照
  （`Argon2Kdf.cs:53-71`：`M ≤ int.MaxValue`、`I ≤ uint.MaxValue`、默认乘积 ≈ 2^27）已写入类 KDoc。
  AES-KDF `R` 已由既有 `AES_KDF_MAX_ROUNDS = 2^28` 封顶（无联合项）。
- **AC③（已完成）**：`KdfParametersBoundsTest` 新增「预算内合法配置通过（官方默认 / 自荐迭代上限 / 边界值 2^40）」
  与「逐项均合法但乘积越界被拒」两例。
- **AC②（未闭环）**：阻塞式原生派生**不可被协程 `withTimeout` 打断**（超时仅在阻塞调用返回后的挂起点生效，
  等于无效的纸面加固），且墙钟量级须真机实测（`ISSUE-P2-80` 明令「不得以推算替代」）。故本轮**不引入**
  无效超时，如实留痕并挂 `ISSUE-P2-80`；`P2-49` 条目保留为开放项。

### 48.3 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 4m 13s；114 actionable tasks: 114 executed（全部真实执行）
#   结果汇总（build/test-results/**/TEST-*.xml）：tests=1626 failures=0 errors=0 skipped=13
```

**新增 / 修改用例**（共 +31 例）：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `database` | `KdfParametersBoundsTest`（+2） | 联合预算边界通过 / 越界拒绝 |
| `database` | `KdbxEntrySerializerProtectedFlagTest`（+1） | `otp` per-value 受保护 → 写出 `Protected="True"` |
| `database` | `KdbxEmptyFieldRoundTripTest`（+1） | `otp` 全链路往返后仍受保护、值不变 |
| `database` | `SessionReplacementReleaseTest`（+1，新文件） | 换库事件序 `clear`→`store`（新库附件不被误删）+ 旧库受保护字段已擦除 |
| `database` | `AttachmentReferenceBudgetTest`（+2，新文件） | 单池条目 × 5000 引用被累计预算拒绝（非 OOM）+ 共享池条目少量引用不误拒 |
| `app` | `VaultEntryMapperTotpTest`（+2） | 新建路径写出受保护 / 明文 `otp` 兼容可读 |
| `app` | `ClipboardClearPolicyTest`（+4，新文件） | 空读裁决：未覆盖写且匹配→清；已覆盖写→不清（核心负例）；无记录 / 摘要不匹配→不清 |
| `app` | `GeneratorViewModelSessionLockTest`（+1，新文件） | 锁库 → 生成结果已清零（`readString` 抛 `IllegalStateException`） |
| `app` | `RuntimeIntegrityPolicyTest`（+3） | 快照新鲜度：从未扫描 / 超出窗口 → 陈旧；窗口内（含边界）→ 不陈旧 |
| `app` | `CredentialProviderIntegrityWiringTest`（+1，新文件） | 静态接线：CM get/create 两入口各消费门控一次 + 自动填充通道同判据 |
| `app` | `CallingOriginResolverAttributionTest`（+6，新文件） | 归属过滤：系统包名 / 空白被排除、android 候选跳过后回退 extras 记录、无可用即省略字段（核心负例）、无系统背书返回 null |
| `app` | `CredentialVerificationBindingWiringTest`（+1，新文件） | 静态接线：CM 通道认证传绑定 Cipher + 放行前校验 isBound + 不可绑定降级手动确认 |
| `app` | `AutofillLastFilledStoreTest`（+2） | 锁定回调清空记忆 / 幂等 |
| `app` | `DatabaseModuleLockObserversWiringTest`（+1，新文件） | 静态装配清单：4 个会话终止观察者全部注册 |
| `app` | `UnlockViewModelClearOnLeaveTest`（+3，新文件） | 开关开启清空未提交主密码（可观测证据）/ 关闭保留 / 擦除令牌递增 |

### 48.4 已知边界与口径（如实声明）

1. **冷启动对账不留口令等价物**：跨进程仅留存一处**布尔**待清标记（`clipboard_security` prefs），
   不含明文、不含摘要；正常路径 30 秒窗口内定时器已清空，故该分支极少触发。
2. **切后台即清为产品语义收紧**：切走应用即擦除待清敏感值（`ProcessLifecycleOwner` ON_STOP），
   与「自动擦除开关」独立生效；关闭自动擦除者亦受此保护，代价是切后台后无法粘贴。
3. **`P2-51` 与 `P3-84` 同批**（AC④「产品确认」以设置页风险明示落地），二者互相引用。
4. **`P2-65` 的 `IconBitmapCache` 未接线（评估留痕）**：该类为**屏幕级**自定义图标解码位图 LRU 缓存
   （由 `EntryIconPresenter` 持有，随组合销毁），其内容为派生**图像**而非明文口令 / 种子；且无进程级单例持有点，
   故不注册锁观察者（与 `AGENTS.md` §6 对「已接受边界」的处置口径一致）。AC③ 的「锁库 → 明文清空」
   已以 `GeneratorViewModelSessionLockTest` 在宿主 JVM 断言（该判定不涉平台 API，JVM 即权威）。
5. **`P2-77` 带来的有意行为变更（fail-closed）**：换库前置释放发生在**装载新库之前**，因此
   「已开库 A → 尝试打开库 B 但口令错误」现在会**保持锁定态**（旧库 A 已被释放），
   而不再是「保留 A」。这是 AC 明确要求的顺序（"必须先通知旧库会话锁定、再 load / 落盘新库"）的必然结果，
   方向 fail-closed（错误口令不会让上一库继续在内存中可用）。同理，`create()` 建库亦会先释放旧会话。
6. **`P2-63` 的周期重扫代价与口径**：后台每 30s 重扫一次（`/proc/self/maps` 读取 + 若干
   `File.exists()`，均在 `Dispatchers.IO`），快照新鲜度窗口 120s；窗口内为正常放行，
   超出窗口（进程挂起 / IO 受限）即转保守（fail-closed）。**注入框架探测仍是启发式**
   （磁盘落点 + maps 特征串），命中率真机实测见 `ISSUE-P3-120`，本项只消除「快照陈旧」这一确定性缺口。
7. **`P2-53` 与 CM 响应预算**：门控走 suspend `awaitEnforcement()`（含一次重扫，自带 1s 兜底超时），
   仍在服务既有的 5s `TIMEOUT_MS` 预算内；风险态返回**空响应**而非错误，系统弹窗表现为「无候选」。
8. **`P2-72` 的口径收窄**：审计原断言对 Create 侧为误报（该侧本就优先取系统 `CallingAppInfo`），
   本批仅治理「回退链」——两侧 `clientDataJSON` 与 Create 侧 `boundPackage` 均不再回退本应用包名 /
   `Activity.getCallingPackage()`；归属字段全不可用时**省略**而非回退。`PasskeyCreateActivity` 对
   `callerPackage == null` 的 fail-closed 拒绝沿用既有 DAL 门控，未新增行为。
9. **`P2-76` 的验证预算影响**：绑定 Cipher 准备为同步 Keystore 调用（微秒级）；无强认证器设备
   （原走手动确认）行为不变；Keystore 可用但 Cipher 初始化失败的场景**新增**降级到手动确认
   （此前会以无绑定认证放行——这正是缺陷本身）。
10. **本批未触及**：`ISSUE-P2-49` AC②（见 48.2）、`ISSUE-P3-116 / P3-120`（需真机实测，
    本环境无设备）等升格 P1 开放项仍在 `ACTIVE_ISSUES.md`。

---

## §49 存量安全整改批次（续）：密钥文件纯字节解析 + DAL 有界流式读取 + 选择器会话锁定对齐 + CM 保存 URL 分流 + 依赖扫描触发面（2026-09-15）

> **本批次缘起**：认领外部审计转登项 `ISSUE-P2-62`（审计 H2，敏感数据流批次）、
> `ISSUE-P2-50`（审计 F-14）、`ISSUE-P2-52`（审计 F-22）、威胁建模项 `ISSUE-P2-78`（T-10）
> 与审计项 `ISSUE-P2-54`（F-05，CI 变更）。前四项为「敏感数据 / 恶意输入面 / 完整性」的确定性缺口，
> JVM 侧即可闭环（不涉 Android 运行时差异），无需设备；`P2-54` 为工作流触发面整改。

### 49.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-62** | P2 | `KdbxKeyFile.extractKey` 把**整个密钥文件**转为不可擦 `String`（`raw.toString(Charsets.UTF_8)`），每次解锁尝试与保存都重放；寿命上界为下次 GC | 改**纯字节解析**：`extractKey(raw: ByteArray)` 全链路零 `String` 物化——ASCII 头探测（`<?xml` / `<KeyFile`）、XML 元素定位、v1.0 Base64 / v2.0 Hex 剥离与解码均在 `ByteArray` 上进行，hex 解码走 `kotlin.io.encoding.Base64`/自实现字节 hex；`toString` 仅保留 ASCII 分类校验（无法避免的 String 显式标注）；四类解析梯子（XML v1.0 / v2.0 / 裸 32B / 64-hex / 任意二进制 SHA-256）结果与原实现逐字一致 | 审计 H2；敏感数据铁律（`AGENTS.md` §3.2） |
| **ISSUE-P2-50** | P2 | DAL 响应体先 `body?.string()` **整份物化**，之后才比较 `length`（且为**字符数**，多字节字符下与字节上限错位）→ 恶意端点可借超大响应撑爆内存 | `DigitalAssetLinksVerifier` 改**有界流式读取**：新增 `readBounded(input, limit)`（`MAX_BODY_BYTES = 256 KiB` 封顶，读到上限 +1 即判越界、立即中止、不继续消费剩余字节），并按**字节数**裁决（消除字符数/字节数错位）；空响应体显式拒绝 | 审计 F-14；fail-closed 语义保持（越界 / 空 → `NOT_VERIFIED`） |
| **ISSUE-P2-52** | P2 | 选择器缓存**活** `KdbxEntry` 树：锁定时 `ProtectedString` 就地清零后，缓存条目的任何 `title`/`userName`/`url` 读取都会抛 `IllegalStateException`；且 `resolveCredentials` 在 `try` 之外读 `userName` | `AutofillPickerViewModel` ① 注册 `SessionLockObserver`（锁定即清空缓存列表；选择器为一次性 Activity，解锁后重开即重新拉取，无需解锁重载）；② `search` 与 `resolveCredentials` 的非敏感字段读取 `runCatching` fail-safe（清零→通知观察者的固有竞态窗口内按空结果 / 空用户名降级）；③ `onCleared` 注销观察者。**不削弱** `ProtectedString.clear()`（只调缓存持有与读取侧容错） | 审计 F-22；机制对齐 §48 `P2-65` 四处 ViewModel 的观察者模式 |
| **ISSUE-P2-78** | P2 | **CM 保存路径写入畸形 URL**：`KeePasskeyCredentialProviderService` 把调用方 **origin**（浏览器委派 `https://…` / 普通应用 `android:apk-key-hash:…`）作为 `EXTRA_WEB_DOMAIN` 下传，`PasswordSaveActivity` 原样透传，`VaultEntryWriteCoordinator.saveAutofillCredential` **无条件**拼 `"https://$domain"` → 落库 `https://https://host` / `https://android:apk-key-hash:…`，条目此后既不匹配 web 域也不匹配 `android://` 包名 | 新增纯函数收口 `VaultEntryWriteCoordinator.resolveCredentialUrlBinding`（自动填充与 CM 双保存通道共用）：空白 / apk-key-hash origin → `android://<调用包名>`；`https://`（含 `http://`）origin → **原样入库**不二次拼前缀；裸域名（自动填充既有形态）→ `https://<裸域名>`；`displayDomain` 归一为裸域名供标题与域匹配；负例断言任何形态不得产生 `https://` 二次叠加或 apk-key-hash 尾巴混入 | 威胁建模 T-10；与自动填充保存路径语义对齐（AC①） |
| **ISSUE-P2-54** | P2 | 依赖 CVSS 闸门仅 `workflow_dispatch` 触发，PR / push 路径不含依赖扫描 | `dependency-scan.yml` 的 `on:` 补 `pull_request` 与 `push{branches:[main]}`（AC①，采纳「直接补触发」路线，扫描完整性优先于耗时）；CVSS 缺失报告 fail-closed 经本地实测复核：对不存在路径执行 `check_dependency_cvss.py` → `[FATAL] 报告不存在…fail-closed` 退出码 1（AC③，`if: always()` + `if-no-files-found: error` 既有机制不变） | 审计 F-05；AC② 留痕见 49.3.6 |

### 49.2 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 4m 36s；114 actionable tasks: 114 executed（全部真实执行）
#   结果汇总（build/test-results/**/TEST-*.xml）：tests=1639 failures=0 errors=0 skipped=13
#   （app 887 / core 65 / crypto 116 / database 368 / sync 203）
```

**新增用例（共 +13 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `database` | `KdbxKeyFileTest`（+2） | ① v1.0 跨行 + 大量空白 Base64 的字节解析与原 String 版逐字一致（回归锁）；② 非法 Base64 `Data` 抛 `KdbxCorruptFileException` |
| `app` | `DigitalAssetLinksVerifierTest`（+3） | ① 2 MiB 响应（> 256 KiB 上限）被拒且不被整体物化 → `NOT_VERIFIED`；② **恰好等于**字节上限且内容合法仍可校验通过（边界不误拒，字节数断言精确抵平 `MAX_BODY_BYTES`）；③ 空响应体拒绝 |
| `app` | `AutofillPickerViewModelSessionLockTest`（+2，新文件） | ① 会话锁定 → 选择器缓存条目清空；② 锁定竞态窗口内已清零条目：`search` 按空结果降级、`resolveCredentials` 按空用户名降级（均不抛 `IllegalStateException`，核心负例） |
| `app` | `VaultEntryWriteCoordinatorUrlBindingTest`（+6，新文件） | ① web origin 原样入库且 `DomainMatcher` 可命中（含子域正例 / 仿冒域负例）；② apk-key-hash origin 落 `android://<包名>` 且 `isPackageMatch` / `isAndroidPackageMatch` 均命中；③ 空白域回落包名绑定；④ 裸域名保持既有 `https://` 拼接；⑤ 负例：任何形态不得 `https://` 二次叠加或混入 apk-key-hash 尾巴；⑥ 带路径 / 端口 origin 的归一（AC②③） |

### 49.3 已知边界与口径（如实声明）

1. **`P2-62` 的残留 String 面**：`extractXmlElementContent` 内部对**标签名/属性名**等非秘密骨架
   仍存在短命 `String`（仅用于 ASCII 结构定位，不含密钥材料字节）；密钥材料
   （Base64 / Hex 数据段）全程 `ByteArray`，无可擦 `String` 路径。`KdbxKeyFile` 产物 `ByteArray`
   由调用方（复合密钥装配）按既有契约清零，本批未改变所有权。
2. **`P2-50` 的字节裁决口径**：上限 256 KiB 为防御性预算（官方 `assetlinks.json` 实际远小于该值）；
   越界响应**不整体物化**（读到上限 +1 即中止），但 OkHttp 连接层缓冲（响应头 + 前 8 KiB 读取窗）
   不在本裁决可控范围——该残余面与既有 `AGENTS.md` §6 口径一致。
3. **测试字节对齐**：边界用例的填充公式为 `pad = MAX − len(dalJson)`（`body = "[" + pad空格 + dalJson().substring(1)`
   的总字节数 = `pad + len`），曾因误写 `− 2` 偏差 2 字节（`expected:<262144> but was:<262142>`），
   已修正并留公式注释防回归。
4. **`P2-52` 的容错边界**：fail-safe 只覆盖**非敏感投影字段**（`title` / `userName` / `url`）的
   读取侧；`resolveCredentials` 的密码解密路径本就在 `try` 内（`getEntryPasswordChars` 失败按
   空密码降级，既有语义）。锁定后选择器清空列表意味着「锁定瞬间打开的选择器」呈现空列表——
   用户解锁后重开即恢复，属 fail-closed 的预期 UX。
5. **`P2-78` 的既有条目不受影响**：分流只作用于**新建**条目的 URL 落库与新凭据去重匹配；
   历史上已被写坏的 `https://https://host` 条目不在本批做数据迁移（`extractDomain` 会把它
   归一为 `https`，本就无法可靠还原原域）——用户可在条目编辑页手动修正 URL。
   `FakeVaultRepository` 中的同形测试替身逻辑**未同步**（测试假数据通道，不承载 AC 语义）。
6. **`P2-54` 的 AC②（分支保护必需检查）未闭环——本环境无权限，如实留痕**：本地 `gh` PAT
   对 `repos/.../branches/main/protection` 返回 HTTP 403（Resource not accessible by personal
   access token），分支保护属仓库管理面动作、无法经工作流文件声明。**待仓库所有者**在
   GitHub → Settings → Branches → Branch protection rule（main）中把
   `OWASP Dependency-Check` 设为必需状态检查（并按需把 `build.yml` 各 job 一并纳入）。
   该子项不因留痕而视为完成；后续持管理员凭据的环境应补做并把本行闭环。
7. **本批 ⑤（P2-54）为纯 CI 工作流变更**：未触碰任何 Kotlin / 资源代码，单测基线
   （1639 / 0 / 0 / 13）与 release 产物不受影响，未重跑全量测试与 `assembleRelease`；
   验证手段为 YAML 解析（`workflow_dispatch / pull_request / push` 三触发齐备）+
   fail-closed 脚本实测（见交付清单行）。
8. **顺带修正上批漏删行**：`ISSUE-P2-53`（§48 已闭环）的正文行在 `ACTIVE_ISSUES.md`
   审计表内漏删，本批发现后补删并把 P2 计数修正为 24（前序批注的 2026-09-14 闭环
   声明与 RESOLVED_LOG §48.1 为准，非重新闭环）。

