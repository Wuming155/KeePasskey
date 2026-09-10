# KeePasskey 已整改问题与历史任务归档（Resolved Log）

> **文档定位**：本项目已完成修复的缺陷、已落地特性与已闭环技术债的**全量归档库**。
> **追溯作用**：记录每项任务的修复实现、代码证据、关联提交与测试验收，用于防回退与历史溯源。
> **维护规则**：当 [**docs/ACTIVE_ISSUES.md**](ACTIVE_ISSUES.md) 中的任务经整改并通过测试后，整条移入本文件对应章节。

---

## 目录
1. [已完成核心任务清单（TASK-01 ~ TASK-53）](#1-已完成核心任务清单)
2. [历史全量代码审计发现项整改归档（125 项）](#2-历史全量代码审计发现项整改归档)
   - [2.1 全量代码审核（93 项）](#21-全量代码审核93-项)
   - [2.2 安全审查 Wave 13（16 项）](#22-安全审查-wave-1316-项)
   - [2.3 加解密实现审查（9 项）](#23-加解密实现审查9-项)
   - [2.4 测试覆盖缺口审查（7 项）](#24-测试覆盖缺口审查7-项)
   - [2.5 零信任专项审计（ZT 系列）](#25-零信任专项审计zt-系列)
   - [2.6 凭据提供者端到端契约（P1-01）](#26-凭据提供者端到端契约p1-01)
   - [2.7 生成侧私钥内存脱敏（P1-02）](#27-生成侧私钥内存脱敏p1-02)
   - [2.8 KDBX 回收站保留桶与历史保留期维护（P1-03）](#28-kdbx-回收站保留桶与历史保留期维护p1-03)
   - [2.9 主密码解锁失败节流与失败态清零（P1-04）](#29-主密码解锁失败节流与失败态清零p1-04)
   - [2.10 同步凭据认证绑定与 S3 密钥内存治理（P1-06）](#210-同步凭据认证绑定与-s3-密钥内存治理p1-06)
   - [2.11 Argon2 原生内核 C→Rust 迁移（P2-14）](#211-argon2-原生内核-crust-迁移p2-14)
   - [2.12 S3 AccessKey 在 SettingsUiState 中的 String 留存改造（P2-01）](#212-s3-accesskey-在-settingsuistate-中的-string-留存改造p2-01)
   - [2.13 Passkey 注册 DAL 远程资产声明校验（P2-02）](#213-passkey-注册-dal-远程资产声明校验p2-02)
   - [2.14 App 模块 14 个测试用例消除 Fake 自测（P2-03）](#214-app-模块-14-个测试用例消除-fake-自测p2-03)
   - [2.15 Sync 与 Merger 边缘分支单元测试补齐（P2-04）](#215-sync-与-merger-边缘分支单元测试补齐p2-04)

---

## 1. 已完成核心任务清单

| TASK ID | 领域 | 任务主题 | 优先级 | 完成日期 | 核心实现与代码证据 / 说明 |
|:---:|:---:|---|:---:|:---:|---|
| **TASK-01** | 安全 | HMAC 防篡改回归锁 flaky 排查与定型 | **P0** | 2026-09-07 | 定位并修复终止块未校验即置 `terminated=true` 导致篡改文件 ~10% 概率静默解锁的漏洞；改为仅校验通过后置位并在 `verifyEndOfStream` 权威检查点 fail-closed；`testCorruptHmacBlock` 20 连跑零失败。 |
| **TASK-02** | 平台集成 | 凭据提供者服务实机端到端注册与调起（ISSUE-P1-01） | **P1** | 2026-09-09 | 根因：全部凭据条目 PendingIntent 误用 `FLAG_IMMUTABLE`，系统注入的 fillIn extras 被静默丢弃 → 链式解锁 / 密码保存 / 应用内注册全链路握手失败。新增 `CredentialPendingIntents.ENTRY_FLAGS`（`FLAG_MUTABLE｜FLAG_UPDATE_CURRENT`）统一替换 5 处创建点，附 4 例契约回归锁。详见 [§2.6](##26-凭据提供者端到端契约专项p1-01)。 |
| **TASK-03** | 依赖 | kapt → KSP 2.3.11 迁移 + 启用内置 Kotlin | **P2** | 2026-09-08 | 移除 kapt 插件，全面接入 KSP 2.3.11 与 AGP 9 内置 Kotlin 2.4.10，编译速度提升。 |
| **TASK-04** | 存储 | 设置持久化迁移 Preferences DataStore | **P2** | 2026-09-08 | 21 个设置项全量迁移 DataStore，提供 Flow 响应式通知与 SharedPreferences 自动平滑迁移。 |
| **TASK-05** | 构建 | Gradle 版本目录（`libs.versions.toml`）集中管理 | **P2** | 2026-09-08 | 5 个子模块依赖与插件统一收口至版本目录，依赖版本规范化。 |
| **TASK-06** | 性能 | Baseline Profiles + Startup Profiles 注入 | **P3** | 2026-09-08 | 引入 `profileinstaller:1.4.1` 与首启/解锁路径规则，优化冷启动 DEX 布局与 AOT 预编译。 |
| **TASK-07** | UI/SDK | compileSdk 37 适配 + Material 3 Expressive 主题 | **P3** | 2026-09-08 | 升级 target/compileSdk 37，启用 Material 3 Expressive 动态动效与排版系统。 |
| **TASK-08** | 同步 | 周期性后台同步（WorkManager） | **P2** | 2026-09-08 | 新增 `PeriodicSyncWorker` 与 `PeriodicSyncScheduler`，支持 Wi-Fi 约束与智能互斥。 |
| **TASK-09** | 安全 | 测试代码真实凭据清洗 | **P1** | 2026-09-07 | 彻底清除单测与诊断测试中的真实主密码和 KeyFile 字节，全仓改用合成测试向量。 |
| **TASK-10** | 内存 | TOTP 种子与受保护自定义字段编辑态 CharArray 化 | **P2** | 2026-09-07 | 消除 UI 与 ViewModel 中的 String 明文，改用 CharArray 私有链路并在用毕即时清零。 |
| **TASK-11** | 安全 | Autofill Dataset 已解锁分支增加二次确认/认证 | **P2** | 2026-09-07 | 下发凭据前挂载 `AutofillConfirmActivity` 认证门禁（生物识别/锁屏凭据），防未授权填充。 |
| **TASK-12** | 架构 | 设置项 33 个字段持久化（预留功能不下架） | **P2** | 2026-09-08 | 建立 `ExtendedSettingsStore`，设置项修改即时持久化，消除冷启动丢失与纯内存回显。 |
| **TASK-13** | UI/SAF | 设置页 5 个动作 SAF 真实化 | **P2** | 2026-09-08 | 真实导出 KDBX、XML（兼容 KeePass 2.x）与密钥文件；模板安装真实幂等落库；子库挂载改诚实提示。 |
| **TASK-14** | 安全 | `SyncCredentialsStore` 生产测试钩子收窄 | **P2** | 2026-09-07 | 加密/解密替换钩子标记 `@VisibleForTesting` 并收窄为 `internal`，生产 DI 不可见。 |
| **TASK-15** | 特性 | 自定义图标上传 / 选择 UI | **P3** | 2026-09-08 | 新增 `CustomIconCoordinator`，支持 PNG 降采样、魔数校验、KDBX Meta 图标池落盘与选择器接入。 |
| **TASK-16** | 特性 | 条目克隆（Duplicate） | **P3** | 2026-09-08 | 新增 `EntryDuplicateCoordinator`，全字段深度克隆 + 重新分配 UUID + 清空历史修订与时间重置。 |
| **TASK-17** | 协议 | KeePass 字段引用（`{REF:...}`）解析引擎 | **P3** | 2026-09-08 | 实现 `FieldReferenceEngine` 官方语法子集，支持递归解析与循环引用上限防护，接入自动填充与复制。 |
| **TASK-18** | 特性 | Passkey 作为数据库解锁方式 | **P3** | 2026-09-08 | 引入设备绑定通行密钥，硬件私钥断言签名 + signCount 反克隆校验门控快速解锁。 |
| **TASK-20** | CI | GitHub Dependabot / OWASP 依赖漏洞巡检 | **P3** | 2026-09-08 | 配置 `.github/dependabot.yml` 与每日 OWASP 依赖安全扫描 workflow，白名单误报治理。 |
| **TASK-21** | 整洁度 | 超 800 行大文件拆分与硬编码中文资源化 | **P3** | 2026-09-08 | 7 个巨型文件全部拆分至合理粒度；提取用户可见文案至 XML 资源并构建 `StringsProvider`。 |
| **TASK-22** | 安全 | AutoLockManager 在 `MainActivity.onDestroy` 误销毁修复 | **P0** | 2026-09-07 | 移除单例误销毁调用，确保屏幕旋转/配置重建时自动锁定调度器不失效。 |
| **TASK-23** | 安全 | EC 私钥标量 `d ∈ [1, n-1]` 范围显式校验 | **P1** | 2026-09-07 | 新增 `validateEcScalarRange` 权威检查点，越界 fail-closed 抛类型化异常。 |
| **TASK-24** | 内存 | 旧派生回退 `legacyCipherKey` 内存清零 | **P2** | 2026-09-07 | `KdbxFile.resolveCipherKey` 引入清理生命周期，未选中的派生密钥在 finally 中统一清零。 |
| **TASK-25** | 互操作 | WebDAV Basic 认证改 UTF-8 编码 | **P2** | 2026-09-08 | 解决含有中文或特殊字符的主密码在标准 WebDAV 服务器鉴权 401 失败的问题。 |
| **TASK-26** | 协议 | S3 SigV4 规范 URI 编码（`*` 与 `~` 修复） | **P2** | 2026-09-08 | 严格对齐 AWS 规范：RFC 3986 未保留字符（含 `~`）不编码，`*` 大写百分号编码 `%2A`。 |
| **TASK-27** | 协议 | CBOR 编码强制 RFC 8949 Canonical 键序 | **P2** | 2026-09-08 | `CborEncoder` 引入 Canonical 排序写出，对齐 CTAP2 / WebAuthn 规范。 |
| **TASK-28** | 敏感 | 附件缓存用完即删与目录隔离 | **P2** | 2026-09-07 | 建立专用隔离子目录，导出带 UUID 前缀，消费方用毕立即触发定向清理。 |
| **TASK-29** | 安全 | RSA 密钥生成素数确定性参数提升至 `certainty = 80` | **P2** | 2026-09-07 | 提升 RSA 质数判定可靠性，使伪素数概率降至 ≤1/2⁸⁰。 |
| **TASK-30** | 功能 | 冲突解决支持逐字段选择合并 | **P2** | 2026-09-08 | `KdbxMerger` 新增 `resolveConflictByFields`，UI 支持针对各冲突字段单独选择采纳本地或远端。 |
| **TASK-31** | 功能 | 历史修订快照为空时如实暴露错误 | **P2** | 2026-09-08 | 消除空快照伪造成功提示，快照不存在时如实提示回滚失败。 |
| **TASK-32** | 功能 | 条目密码强度消除硬编码 112 bit | **P2** | 2026-09-08 | 投影层改可空，仅在用户显式查看或计算时按真实熵算法评估。 |
| **TASK-33** | 功能 | TOTP 缺失时下线 "000000" 假验证码 | **P2** | 2026-09-08 | 种子缺失时展示占位符并禁用复制通道，不产出虚假验证码。 |
| **TASK-34** | 功能 | 收藏（Favorite）功能持久化落库 | **P2** | 2026-09-08 | 将收藏状态持久化至 KDBX `customData["KeePasskey.Favorite"]`，重启不丢失。 |
| **TASK-35** | 功能 | 信用卡/银行卡分类与字段脱敏映射 | **P2** | 2026-09-08 | 识别银行卡模板自定义字段，非保护字段脱敏展示，敏感字段整卡掩码保护。 |
| **TASK-36** | 功能 | 自动填充黑名单移除假数据并如实展示 | **P2** | 2026-09-08 | 清理写死示例与空回调，如实展示真实统计或空态（后续随 TASK-44 彻底闭环）。 |
| **TASK-37** | 数据 | `SyncCache.updateBase` 双文件原子写与 fsync 加固 | **P2** | 2026-09-08 | 采用独立 tmp 文件 fsync 后背靠背原子 rename，最小化不一致崩溃窗口。 |
| **TASK-38** | 构建 | Release 开启 `shrinkResources` 与 ProGuard 规则收敛 | **P2** | 2026-09-08 | 启用资源收缩，收紧混淆规则，修复 lintVital 孤儿资源错误。 |
| **TASK-39** | 性能 | SAF 密钥文件流式读取移至 IO 线程 | **P2** | 2026-09-08 | 密钥文件读取与元数据游标查询脱离主线程，避免大文件 ANR。 |
| **TASK-40** | 测试 | 测试套件覆盖补强（AES-GCM 算法路径与 SigV4 向量） | **P2** | 2026-09-08 | 补齐真实 AES-GCM 单测、独立 Python 预计算的 SigV4 向量对比与缓存回归锁。 |
| **TASK-41** | 整洁度 | 低危与代码异味批量清理（13 项 P3 闭合） | **P3** | 2026-09-08 | 消除 O(n²) 二进制去重、清理魔数、补齐测试可见性注解、防止 EMPTY 单例污染等。 |
| **TASK-42** | 性能 | 调度批次优化（Argon2 移 Default / OkHttp 超时 / Flow 调度） | **P3** | 2026-09-08 | CPU 密集型计算移至 `Dispatchers.Default`，网络请求补齐 5 分钟全局兜底超时。 |
| **TASK-44** | 特性 | 自动填充黑名单端到端生命周期闭环 | **P3** | 2026-09-08 | 新增 `AutofillBlocklistStore`，Autofill 与 Credential Manager 双通道命中拦截，支持详情页快捷屏蔽与设置页管理。 |
| **TASK-45** | 协议 | S3 SigV4 服务端时钟偏移自动学习与补偿 | **P3** | 2026-09-08 | 随 HTTP Date 响应头动态更新偏移，遇 403 时钟偏斜时自动重签自愈，跨进程持久化。 |
| **TASK-46** | 内存 | `OtpEngine` TOTP 计算链路 ByteArray 化与用毕擦除 | **P2** | 2026-09-08 | TOTP/HOTP 计算全程字节态，解码种子借用语义固化并在 finally 中强制清零（fail-clean）。 |
| **TASK-47** | 安全 | 健康度「已泄露密码」接入真实 HIBP k-匿名范围查询 | **P3** | 2026-09-08 | 采用 SHA-1 前 5 位 k-匿名查询，密码不出端；显式开关门控（默认关闭），失败如实上浮。 |
| **TASK-48** | 数据 | 清理数据库列表虚假默认库与假路径，实现 SAF 选库 | **P3** | 2026-09-09 | 彻底清空合成 `default_vault` 与 `/storage/emulated/0` 假路径，接入 SAF 真实选库与开箱新建向导。 |
| **TASK-50** | 依赖 | Dependabot 3 项依赖升级 PR 批量落地 | **P2** | 2026-09-08 | 升级 Gradle Wrapper 9.7.1、AGP 9.4.0、OkHttp 5.5.0 等核心依赖并保障构建全绿。 |
| **TASK-51** | CI | `dependency-scan` workflow 三层根因全链修复 | **P1** | 2026-09-08 | 修复 gradlew 权限位、NVD 双通道（Secret Key + 镜像缓存）及报告上传路径错配。 |
| **TASK-52** | 性能 | 自维护 Argon2 原生 JNI 加速解锁 + KDF OOM 防护 | **P1** | 2026-09-09 | vendor 入库 PHC 官方 C 源码 + 薄 JNI 桥，原生派生优先 + BC 兜底，显著降低解锁耗时，补齐 OOM 守卫。 |
| **TASK-53** | 整洁度 | Base64 规范化（消除 android.util.Base64）+ Hex 编解码现代化 | **P3** | 2026-09-09 | 统一 Kotlin 标准库 Base64.UrlSafe（保留无 padding 契约），Hex 字符串操作现代化。 |

---

## 2. 历史全量代码审计发现项整改归档

### 2.1 全量代码审核（93 项）

- **P0-1（分组重命名数据安全）**：已修复。`RealVaultRepository.kt` 增量合并；`DatabaseSession.kt` 兜底保护。
- **P0-2（源码凭据泄露）**：已修复。所有单测替换为合成口令与离线测试向量。
- **P0-3（更改主密码假实现）**：已修复。`changeCredentials` 触发全量加密重写盘。
- **P0-4（ChaCha20 IV 长度）**：已修复。动态按算法取 12B IV，非 12B 抛异常。
- **P0-5（Header 长度 OOM 攻击）**：已修复。`LittleEndianUtil.kt` 增加 16MiB 上界限制，单字段 1MiB 限制。
- **P0-6（Base64 损坏静默降级）**：已修复。Base64 解码失败直接抛 `KdbxCorruptFileException`。
- **P1-1（明文密码索引表）**：已修复。哈希索引 + 弱口令判定后即时擦除。
- **P1-2（浏览器特权白名单格式）**：已修复。修正为官方 `{"apps":[...]}` 结构。
- **P1-3（锁库态操作错误）**：已修复。改用 `AuthenticationAction` 规范。
- **P1-4（Passkey 创建包名来源）**：已修复。改用 `callingAppInfo?.packageName`。
- **P1-5（密码生成器随机源）**：已修复。改用 `SecureRandom` 并在使用后擦除。
- **P1-6（设置项无持久化与无消费者）**：已修复。建立 `ExtendedSettingsStore` 持久化（TASK-12）。
- **P1-7（假 SAF 动作）**：已修复。导出三件套真实落盘，子库挂载如实提示（TASK-13）。
- **P1-10（纯 KeyFile 库无法打开）**：已修复。支持仅 KeyFile 派生主密钥。
- **P1-12（冲突下拉取失败伪装）**：已修复。如实返回 `RemoteUnreachable`。
- **P1-13（生物解锁尾零乱码）**：已修复。改按 `remaining()` 精确拷贝。
- **P1-14（Autofill 保存无结果反馈）**：已修复。改为返回 `KdbxResult<Unit>`。
- **P1-15（剪贴板自动清空失效）**：已修复。后台无法读取时触发兜底清空。
- **P2-1（常时比较）**：已修复。关键校验改用 `MessageDigest.isEqual`。
- **P2-2（Argon2 调度）**：已修复。移至 `Dispatchers.Default`（TASK-42）。
- **P2-3（InMemoryCipher 拼接未清零）**：已修复。重构为随机 IV + AES-CTR + HMAC。
- **P2-5（TOTP 种子 String 驻留）**：已修复。全程 ByteArray 化并在计算后清零（TASK-46）。
- **P2-6（VariantDictionary 类型转换）**：已修复。改用类型化安全匹配与上限校验。
- **P2-8（Header 算法取值校验）**：已修复。强制 MasterSeed 32B 与标准算法校验。
- **P2-9（EC 标量越界）**：已修复。增加 `validateEcScalarRange`（TASK-23）。
- **P2-10（旧派生密钥未清零）**：已修复。引入 `CipherKeyResolution` 擦除（TASK-24）。
- **P2-11（WebDAV 中文密码 401）**：已修复。Basic 认证改用 UTF-8（TASK-25）。
- **P2-12（缺少全局超时）**：已修复。注入 5 分钟全局 `callTimeout`（TASK-42）。
- **P2-13（凭据加密失败未拦截）**：已修复。先封印后落盘，失败不改动磁盘。
- **P2-14（S3 时钟偏移）**：已修复。时钟偏移自愈与签名补偿（TASK-45）。
- **P2-15（SyncCache 双文件非原子写）**：已修复。合并原子写与 fsync（TASK-37）。
- **P2-16（缓存临时文件名冲突）**：已修复。临时文件加入随机 UUID。
- **P2-17（冲突解决整条目二选一）**：已修复。支持逐字段合并（TASK-30）。
- **P2-19（空快照谎报已回滚）**：已修复。快照缺失时如实报错（TASK-31）。
- **P2-20（TOTP 复制绕过安全管理）**：已修复。统一走 `ClipboardSecurityManager`。
- **P2-21（加解密测试钩子泄露）**：已修复。收窄为 internal 与 VisibleForTesting（TASK-14）。
- **P2-22（密码输入框未用安全控件）**：已修复。换用 `SecurePasswordField`。
- **P2-24（Autofill 缺二次确认）**：已修复。挂载 `AutofillConfirmActivity` 认证门禁（TASK-11）。
- **P2-25（生物封印异常被吞）**：已修复。捕获并记录日志且显式反馈。
- **P2-26（占位库假元数据）**：已修复。彻底清理，实现真实 SAF 选库（TASK-48）。
- **P2-27（密码强度硬编码 112）**：已修复。改可空，按需真实估算熵（TASK-32）。
- **P2-28（泄露密码恒 0）**：已修复。接入 HIBP k-匿名范围查询（TASK-47）。
- **P2-29（SAF 密钥读取在主线程）**：已修复。移入 IO 协程（TASK-39）。
- **P2-30（TOTP 循环计算在主线程）**：已修复。ViewModel 上游指定 `flowOn(Default)`（TASK-42）。
- **P2-31（仓库构造期扫盘）**：已修复。扫盘移至协程并推送 Flow（TASK-42）。
- **P2-32（Release 未开 shrinkResources）**：已修复。开启资源收缩并收敛混淆规则（TASK-38）。
- **P2-34（TOTP 缺失假码 000000）**：已修复。下线假码改占位符（TASK-33）。
- **P2-35（SecurityTest 虚标硬件）**：已修复。明确 JVM 单测算法语义，补 GCM 回归锁（TASK-40）。
- **P2-37（假加密测试绕过真实路径）**：已修复。测试接入真实 AES-GCM 路径（TASK-40）。
- **P3-1（InnerRandomStreamID=0 拒开）**：已修复。支持 None 直通。
- **P3-2（Times 缺省 EPOCH）**：已修复。缺省值改用 EPOCH。
- **P3-3（XML 写出转义）**：已修复。CR 转义为 `&#xD;`。
- **P3-4（History 顺序与上限）**：已修复。最新在前并接入配置上限。
- **P3-5（二进制去重 O(n²)）**：已修复。改为 HashMap 指纹索引（TASK-41）。
- **P3-6（未用依赖）**：已修复。移除无用 core-ktx。
- **P3-9（测试后门未注解）**：已修复。标记 VisibleForTesting（TASK-41）。
- **P3-10（EMPTY 单例污染）**：已修复。clear 对 EMPTY 设为 no-op（TASK-41）。
- **P3-11（CBOR 键序非 Canonical）**：已修复。强制升序写出（TASK-27）。
- **P3-12（附件缓存无清理）**：已修复。隔离子目录 + 用完即删（TASK-28）。
- **P3-13（RSA certainty 偏低）**：已修复。提升至 80（TASK-29）。
- **P3-15（路径遍历隐患）**：已修复。剔除 `.` 与 `..`（TASK-41）。
- **P3-16（SigV4 特殊字符编码）**：已修复。对齐 AWS 规范（TASK-26）。
- **P3-17（PROPFIND 静默吞异常）**：已修复。记录警告日志（TASK-41）。
- **P3-18（孤立 tmp 泄漏）**：已修复。通配删除未清理临时文件。
- **P3-20（缺少 HTTPS 校验）**：已修复。构造期强校验 https。
- **P3-22（超 800 行大文件）**：已修复。拆分 7 个大文件（TASK-21）。
- **P3-23（硬编码中文）**：已修复。资源化抽离（TASK-21）。
- **P3-24（设置演示默认值）**：已修复。改为空串（TASK-41）。
- **P3-25（收藏不落盘）**：已修复。持久化落库（TASK-34）。
- **P3-26（银行卡未映射）**：已修复。映射 category 与脱敏字段（TASK-35）。
- **P3-27（黑名单假数据）**：已修复。端到端闭环（TASK-36 / TASK-44）。
- **P3-28（空 if 块）**：已修复。清理死代码（TASK-41）。
- **P3-30（AutoLock onDestroy 销毁）**：已修复。移除误销毁（TASK-22）。
- **P3-31（吞异常细节）**：已修复。记录详细日志（TASK-41）。
- **P3-33（MockData 命名）**：已修复。重命名为 `UiModels.kt`（TASK-41）。

---

### 2.2 安全审查 Wave 13（16 项）

- **S-01（自研 PIN 体系）**：已修复。彻底删除自研 PIN，全面升级为 Android 锁屏凭据绑定。
- **S-02（BiometricPrompt 负向按钮冲突）**：已修复。统一凭据门禁并强制互斥。
- **S-03（解锁二次确认）**：已修复。设置 `confirmationRequired=false`。
- **S-04（Passkey 创建 rp.id 校验）**：已修复。接入 `DomainMatcher` fail-closed 校验。
- **S-05（XML 文本上限）**：已修复。限制单个 TextNode 最大 8MiB。
- **S-06（XML 嵌套深度）**：已修复。限制 SAX 解析最大深度 64 层。
- **S-07（内层 Header 池上限）**：已修复。条目 ≤1024、总量 ≤256MiB 强制校验。
- **S-08（解压炸弹防御）**：已修复。引入 `SizeBoundedInputStream` 限制 512MiB。
- **S-09（明文与自签名假开关）**：已修复。全面下线，强制 TLS-only。
- **S-10（网络超时控制）**：已修复。配置统一带超时的 OkHttpClient。
- **S-11（EncryptedSharedPreferences 评估）**：已完成。评估后维持直连 AndroidKeyStore。
- **S-12（UnlockUiState PIN 字符串）**：已修复。随 PIN 体系删除消除。
- **S-13（Autofill 密码未擦除）**：已修复。增加 `finally { passwordChars.fill('0') }`。
- **S-14（androidx.biometric 版本）**：已修复。切为稳定版 1.1.0。
- **S-15（androidx.credentials 版本）**：已修复。升级为稳定版 1.6.0。
- **S-16（自动化依赖漏洞巡检）**：已完成。接入 GitHub Dependabot + OWASP 扫描（TASK-20）。

---

### 2.3 加解密实现审查（9 项）

- **C-01（Keystore 硬件落位校验）**：已修复。生成后校验 `KeySecurityLevel`。
- **C-02（设备未解锁保护）**：已修复。强制设置 `.setUnlockedDeviceRequired(true)`。
- **C-03（InMemoryCipher 确定性等值泄露）**：已修复。重构为随机 IV + HMAC 标签模式。
- **C-04（死 Intent 密码通道）**：已修复。剪除 `EXTRA_PASSWORD` 明文通道。
- **C-05（EntryEditUiState 密码 String）**：已修复。改用 CharArray 私有链路。
- **C-06（历史回滚 String 中转）**：已修复。改走全链路 CharArray。
- **C-07（改密对话框普通文本框）**：已修复。换用双 `SecurePasswordField`。
- **C-08（saveEntry 擦除契约）**：已修复。内部实现 `finally` 强制清零传入副本。
- **C-09（TOTP/受保护字段 String 承载）**：已修复。编辑态全链路 CharArray 化（TASK-10）。

---

### 2.4 测试覆盖缺口审查（7 项）

- **T-01（WebDAV If tagged-list 预条件用例）**：已修复。显式断言 ETag 匹配与 412 分支。
- **T-03（SyncCache 独立单测）**：已完成。新增 `SyncCacheTest` 5 例（TASK-37）。
- **T-04（S3 SigV4 官方向量比对）**：已完成。引入独立 Python 预计算的 SigV4 向量（TASK-26）。
- **T-05（Keystore 真实路径测试）**：已完成。JVM 层补齐真实 AES-GCM 算法单测（TASK-40）。
- **T-07（P0 缺陷回归用例）**：已修复。锁死子树完整性与 IV 校验断言。

### 2.5 零信任专项审计（ZT 系列）

> 来源：2026-09-09 零信任专项审计（NIST SP 800-207 七支柱 + Assume Breach 视角）。

- **ZT-01（ISSUE-P0-01，自动锁定守护未覆盖 Autofill / Credential 冷启动入口）**：已修复（2026-09-09）。
  - **缺陷**：`AutoLockManager.initialize()` 唯一调用点在 `MainActivity.onCreate()`，而应用存在 `AutofillUnlockActivity` / `CredentialUnlockActivity` 两条不经 MainActivity 的独立冷启动入口——从这两条路径冷启动后 `ProcessLifecycleOwner` 观察者与 `ACTION_SCREEN_OFF` 广播均未注册，后台超时锁定、熄屏锁定全部失效，会话在进程存活期内无限期保持 `OPENED`。
  - **整改**：
    1. `AutoLockManager.initialize()` 下沉至 `MainApplication.onCreate()`（进程级唯一冷启动点，幂等守卫保留），`MainActivity` 移除调用（字段保留供 `KeePasskeyApp` 经 `LocalContext` 订阅 `lockEvents` / 手动锁定）；
    2. 将判定与会话熔断内核拆分为纯 Kotlin 类 `AutoLockSessionGuard`（`app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt`：熄屏熔断 `lockOnScreenOff`、后台超时 `lockOnBackgroundResume`（`now` 可注入）、`triggerLock` 含 DIRTY best-effort 补存），`AutoLockManager` 收窄为 Android 注册管道（生命周期观察者 + 熄屏广播）并对内核做 API 委托，公开 API（`isLocked` / `lockEvents` / `triggerLock` / `onUnlockSuccess`）不变；
    3. `AutofillUnlockActivity` / `CredentialUnlockActivity` 由静态 `FLAG_SECURE` 窗口标志升级为与 `MainActivity` 同源的 `FlagSecureGuard.attach()` 动态守卫（用户开关 ∨ 会话锁定态并集，首帧同步生效）。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/security/AutoLockSessionGuardTest.kt` 7 例，以真实 `DatabaseSession`（AES-KDF 路径免 NDK）驱动「不启动 MainActivity，仅经解锁入口打开会话 → 熄屏 → 会话锁定」全链路：熄屏熔断（`lockWhenScreenOff` / 仅 `autoLockBackground`）/ 双开关关闭不锁 / 后台超时熔断与未达超时放行 / 零时间戳放行 / DIRTY 补存后锁定并重开校验。全量回归 **521 例：509 通过 / 0 失败 / 12 跳过**。

- **ZT-02（ISSUE-P0-02，Credential Manager 密码填充通道零用户验证门控）**：已修复（2026-09-09）。
  - **缺陷**：`CredentialResponseAssembler.buildPasswordEntries()` 构造 `PasswordCredentialEntry` 时未挂 `BiometricPromptData`，且 `PasswordFillActivity` 自身不做任何生物识别或二次确认；而 Autofill 兼容通道每个 dataset 都强制 `setAuthentication` 并拉起 `AutofillConfirmActivity`——两条通道确认强度严重不一致。后果：密码库处于解锁态时，任意调起 Credential Manager 的应用可在**用户零交互**下取得明文密码，设备被短暂占有即等同全库可读。
  - **整改**：
    1. 新增纯 Kotlin 门控内核 `CredentialFillVerifier`（`app/src/main/java/com/keepasskey/app/passkey/CredentialFillVerifier.kt`）：由设备认证器状态映射验证等级 `BIOMETRIC` / `MANUAL_CONFIRMATION`，并以 `isSatisfied()` 对「要求等级 × 实际验证结果」做 fail-closed 裁决——未验证、失败、取消、以及「要求生物识别却仅手动确认」的降级路径一律拒绝；
    2. `PasswordFillActivity` 改为「先验证、后取密」：进入即复核调用包名黑名单（与 Autofill 通道一致的 fail-closed），随后二次校验「条目 ⇄ 调用方」绑定关系，再由 `BiometricAuthManager` 拉起系统级 BiometricPrompt，设备无可用认证器时退化为受保护窗口（FLAG_SECURE + 反 overlay）内的显式手动确认；仅当门控裁决通过才回传明文密码，其余路径一律 `RESULT_CANCELED`；
    3. `BaseCredentialActivity` 基类由 `ComponentActivity` 提升为 `FragmentActivity`（`androidx.biometric.BiometricPrompt` 要求 FragmentActivity 宿主），使门控在同一受保护窗口内闭环；
    4. 手动确认 UI 抽离为双通道共用组件 `CredentialFillConfirmScreen`，`AutofillConfirmActivity` 同步复用，消除重复实现；
    5. `CredentialResponseAssembler`（直查与链式解锁两条路径的唯一候选出口）统一复核黑名单，命中即不产出任何候选；设置页 `AutofillSettingsScreen` 以只读策略行明示「下发前二次确认」为强制保证，不提供可关闭的假开关。
  - **关键取舍（为何不直接在 entry 上挂 `BiometricPromptData`）**：`androidx.credentials:1.6.0` 中 `BiometricPromptData` 标注 `@RestrictTo(LIBRARY)`，`PendingIntentHandler` **未提供** `BiometricPromptResult` 读取入口（已核对 1.6.0 AAR 常量池确认），提供方 Activity 无法判定系统门控究竟成功、失败还是被绕过——挂上即得「看起来已验证」的假门控、无法闭环。故改为 Activity 内自持门控：结果可判定、可记录、可被纯 JVM 单测覆盖。遗留：Passkey 断言侧 UV 位与实际验证解耦问题由 **ISSUE-P0-03 (ZT-03)** 独立闭环。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/passkey/CredentialFillVerifierTest.kt` 11 例，穷举覆盖「无确认路径不得返回 `RESULT_OK`」安全不变式：等级映射（AVAILABLE → BIOMETRIC；NO_HARDWARE / HARDWARE_UNAVAILABLE / NOT_ENROLLED / SECURITY_UPDATE_REQUIRED 一律降级为手动确认而非免验证）/ 生物识别要求下成功放行、未验证·失败·取消·手动确认均拒绝 / 手动确认要求下确认与更强生物识别放行、未确认·取消拒绝 / 全域不变式（任一等级下未验证拒绝、任一等级下非成功结果拒绝）。全量回归 **532 例：520 通过 / 0 失败 / 12 跳过**（app 161 → 172）。

- **ZT-03（ISSUE-P0-03，Passkey 断言与注册无条件硬编码 UV=1，向依赖方谎报用户已验证）**：已修复（2026-09-09）。
  - **缺陷**：`PasskeyAssertionActivity` 签名前 AuthenticatorData flags **无条件** `UP|UV|BE|BS` 全置位，`PasskeyCreateActivity` 注册路径同样硬编码 `UP|UV|BE|BS|AT`——与实际是否发生用户验证完全解耦；真实 UV 门控仅挂在「生物识别可用时」的候选 entry `BiometricPromptData` 上，而 `androidx.credentials:1.6.0` 不提供 `BiometricPromptResult` 读取入口，提供方 Activity 无法闭环判定系统门控是否真实通过。后果：设备无强生物识别 / 无锁屏凭据时仍向 RP 签发 `UV=1` 断言，RP 端据此放宽风控（如免密支付、敏感操作放行），形成**跨系统的信任伪造**。
  - **整改**：
    1. 新增纯 Kotlin flags 组装决策 `PasskeyAuthFlags`（`app/src/main/java/com/keepasskey/app/passkey/PasskeyAuthFlags.kt`）：依据 W3C WebAuthn §6.1 语义由**本次实际验证结果**投影 flags——强验证（生物识别 / 锁屏凭据认证成功）→ `UV=1`；仅手动确认 → 如实 `UV=0`（UP/BE/BS 保持在场与备份位）；未验证 / 失败 / 取消一律返回 null（fail-closed 拒绝签发）；
    2. 抽取共享「受保护窗口内用户验证」入口 `CredentialVerificationLauncher.requestCredentialUserVerification`（自 `PasswordFillActivity` 门控实现提升复用）：可用强认证器 → 系统级 BiometricPrompt；不可用 → 窗口内显式手动确认；任何回调先经 `CredentialFillVerifier.isSatisfied` fail-closed 裁决；
    3. `PasskeyAssertionActivity` 改为「先验证、后签名」：origin/RP-ID 与包名绑定二次校验通过后先执行验证门控，再按 `PasskeyAuthFlags` 结果构造 flags 完成签名回传；验证未通过一律 `RESULT_CANCELED` 拒绝签发，密码学运算迁至 `Dispatchers.Default`；
    4. `PasskeyCreateActivity` 改为「先验证、后生成与落库」：门控通过后才生成 ES256 密钥对、`saveNewPasskeyEntry` 与构造 attestation，注册 flags 的 UV 位如实反映验证结果；
    5. `CredentialResponseAssembler` 移除候选 entry 上的 `BiometricPromptData` 假门控（无法闭环且与窗口内验证重复弹窗），Passkey 候选与密码候选统一「不下发即不验证」模式；同步清理 `KeePasskeyCredentialProviderService` 失效的 `biometricAuthManager` 注入与 import；
    6. 手动确认降级文案向用户明示「未执行用户验证 (UV=0)」并提示仅在可信设备继续（`passkey_assert/create_manual_hint` 等 7 条新字符串），杜绝「看似已验证」的误导。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/passkey/PasskeyAuthFlagsTest.kt` 7 例，锁定两条验收分支——「无生物（手动确认通过）→ 断言 / 注册 UV=0」与「生物识别 / 锁屏凭据二次确认通过 → UV=1」，并覆盖未验证 / 失败 / 取消两路径全 fail-closed 及注册 AT 位独立。全量回归 **539 例：527 通过 / 0 失败 / 12 跳过**（app 172 → 179）。

- **ZT-05（ISSUE-P1-05，同步端点 SSRF 与 S3 bucket 名 host 注入）**：已修复（2026-09-09）。
  - **缺陷**：
    1. **SSRF（CWE-918）**：WebDAV（`WebDavSyncProvider` init）与 S3（`S3SyncProvider` init）端点此前**仅校验 scheme 为 https**，全 `main` 源码 `169.254|InetAddress|isLoopback|isSiteLocalAddress` 零命中 → 用户可控 URL 可直连内网段与云元数据端点（`https://169.254.169.254/`、`https://192.168.x.x/`、`https://localhost/`），或填入解析到内网 IP 的域名，诱导设备向内部网络发起携带凭据的请求；
    2. **S3 bucket 名 host 注入**：virtual-hosted 分支 `buildUrl` 将未校验的 `bucketName` 直接拼进 authority（`"$scheme://$bucketName.$host/$cleanKey"`）。注入 `x@evil.com/`（userinfo 改写真实主机为 evil.com）、`x#`（fragment 截断 authority）、`x?` 即可改写目标主机，且 `signV4()` 的 canonicalHeaders 取自被注入后的 host，**签名仍自洽** → 向攻击者主机投递对其有效的 SigV4 签名，亦可转为 SSRF。
  - **整改依据**：CWE-918 SSRF；AWS S3 桶命名规范；Google Android 开发者文档 `unsafe-uri-loading`（scheme + host 全量校验、拒绝 userinfo 注入、阻断 loopback/link-local/site-local 私有网段）。
  - **整改**：
    1. 新增单一防线 `sync/src/main/java/com/keepasskey/sync/network/SyncEndpointGuard.kt`，两层 fail-closed：
       - **构造期（纯字符串 / 字面 IP 校验，零网络阻塞）**：`validateBucketName` 按 AWS S3 命名正则 `^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$` 严格校验桶名（天然排除 `@ / # ? :` 等 authority 分隔符），并追加拒绝相邻点号、IP 地址格式桶名与 `xn--` / `sthree-` / `-s3alias` / `--ol-s3` 保留前后缀；`validateEndpointHost` 以 **OkHttp `HttpUrl`（与实际连接同源解析器，杜绝校验/连接解析差异）** 解析端点，拒绝 userinfo（`@`）注入、`localhost` / `.local` / `.internal` 等本地内网保留名，以及字面 IP 的内网/保留网段；
       - **连接期（DNS 解析后校验）**：`SsrfGuardDns` 包装系统 DNS，对主机名解析结果逐一判定，**任一地址落入内网/保留网段即整体拒绝**（不做「过滤后放行」，杜绝「公网 IP + 内网 IP」混合应答的 DNS 重绑定绕过）；校验用解析结果即喂给实际连接，故对重绑定同样有效；
       - `isBlockedAddress` 覆盖 IPv4 与 IPv6：环回 / 任意本地 / 链路本地（含 169.254 云元数据）/ 站点本地（RFC1918）/ 组播，补充 CGNAT `100.64/10`、TEST-NET、`198.18/15`、`240/4`、IPv6 ULA `fc00::/7` 与 IPv4-mapped `::ffff:0:0/96`（递归抽取内嵌 IPv4 判定，杜绝映射地址绕过）；
    2. `S3SyncProvider` init：**恒常**校验桶名（注入面与是否回环无关），生产路径（`client == null`）追加端点主机 SSRF 校验；`WebDavSyncProvider` init 生产路径追加同款端点校验；两者沿用既有「注入客户端旁路生产校验」约定，MockWebServer 回环与本地联调测试不受影响；
    3. `SyncHttpClientFactory.createSyncClient` 装配 `SsrfGuardDns(Dns.SYSTEM, options.ssrfAllowedHosts)`，使全部生产同步客户端连接期恒受 SSRF 防护；
    4. `SyncNetworkOptions` 新增 `ssrfAllowedHosts: Set<String> = emptySet()`——显式、可审计的白名单豁免通道（默认空 = 不豁免），满足「可显式白名单豁免」验收项，生产默认恒为空。
  - **测试证据**：新增 `sync/src/test/java/com/keepasskey/sync/network/SyncEndpointGuardTest.kt` 16 例（桶名注入 `x@evil.com` / `x#` / `x?` / IP 格式 / 非法与合法桶名；端点内网与云元数据字面 IP / 本地内网保留名 / userinfo 注入 / 公网放行 / 白名单豁免；`isBlockedAddress` IPv4+IPv6 网段判定；`SsrfGuardDns` 拒绝纯内网、拒绝公私混合重绑定、放行公网、白名单豁免），并在 `S3SyncProviderTest`（+3）与 `WebDavSyncProviderTest`（+2）补齐 Provider 构造期注入被拒回归锁——三类注入（`x@evil.com`、`x#`、内网 IP）均按验收标准 fail-closed 抛 `InvalidEndpointError`。全量回归 **566 例：554 通过 / 0 失败 / 12 跳过**（sync 121 → 142）。

- **ZT-07（ISSUE-P1-07，同步缓存锁库后不清理，KDBX 密文长期驻留）**：已修复（2026-09-09）。
  - **缺陷**：`SyncCoordinator` 在 `context.cacheDir/sync` 落盘 `<sha256(remotePath)>.cache`（工作副本）与 `.basecache`（三方合并基准），二者均为**完整 KDBX 密文**；`DatabaseSession.lock()` / `close()` 仅销毁内存主凭据与数据库树，`SyncCredentialsStore.clear()` 只清 SharedPreferences，二者**均不触碰该目录**，而 `SyncCache.clear()` 方法在 `app/src/main` 下零调用方。后果：设备失窃后，攻击者可对两份密文快照无限期离线爆破主密码——「假设已被入侵」下的数据生命周期缺少终止点。此外缓存文件沿用默认 umask（通常 0644），未做显式降权；`SyncCoordinator` 作为单例持有的 `lastSyncedDb` / `pendingLocalDb` / `pendingRemoteDb`（整棵 `KdbxDatabase` 树，含全部 `ProtectedString`）同样跨锁定周期驻留。
  - **整改依据**：NIST SP 800-207「数据可见性与生命周期治理」；`data_extraction_rules` 不覆盖 `cacheDir`，缓存必须由应用自行治理。
  - **整改**：
    1. core 新增会话终止观察者契约 `SessionLockObserver`（`core/src/main/java/com/keepasskey/core/session/SessionLockObserver.kt`，`fun interface` + `onSessionLocked()`），KDoc 固化三条契约：非阻塞（回调在会话锁内同步触发，严禁重入会话 API）、幂等、自容错（会话为保锁定原子性会隔离吞掉回调异常）；
    2. `DatabaseSession` 新增 `addLockObserver` / `removeLockObserver`（重复注册幂等），并在 `lock()` 与 `close()` 收尾处同步 `notifySessionLockObservers()`——逐个隔离异常，任一观察者的清理失败绝不反噬「锁定」本身；
    3. app 新增单例 `SyncCacheEvictor`（实现 `SessionLockObserver`），统一收口两条销毁时机：`DatabaseModule.provideDatabaseSession` 在装配期注册为会话观察者（覆盖手动锁定 / 熄屏熔断 / 后台超时 / 切换密码库全部路径），`SyncCredentialsStore.clear()` 直接调用（凭据销毁 = 同步关系终止，缓存不得成为无主密文）；「哪些文件属于同步缓存」的知识保留在 sync 模块，app 侧不重复枚举后缀；
    4. `SyncCoordinator` 实现 `SessionLockObserver` 并在 init 注册自身：锁库即释放 `lastSyncedDb` / `lastSyncEngine` / 冲突会话持有的 `pendingLocalDb` / `pendingRemoteDb`，补上内存侧生命周期终止点（磁盘侧由 `SyncCacheEvictor` 负责）；
    5. `SyncCache` 权限加固：新增 `clearAll()`（销毁全部远端路径的缓存内容），全部落盘文件（含 tmp）经 `restrictToOwnerOnly` 显式收敛——优先 POSIX 精确置位（文件 0600 / 目录 0700），非 POSIX 文件系统（Windows / FAT）降级为 `java.io` 仅属主语义；缓存目录名统一收口为 `SyncCache.CACHE_DIR_NAME`，`SyncCoordinator` 与 `SyncCacheEvictor` 共用，杜绝字面量漂移。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/sync/SyncCacheEvictorTest.kt` 5 例（锁库 / 关闭后缓存目录为空、未注册观察者时不越权清理、凭据清空连带销毁、目录缺失时幂等），`database/src/test/java/com/keepasskey/database/session/DatabaseSessionLockObserverTest.kt` 3 例（锁定与关闭均触发、重复注册幂等与注销、观察者抛异常不得阻断锁库），`SyncCacheTest` +3 例（单路径 `clear` 销毁全部后缀、`clearAll` 后目录为空、文件权限 0600 / 目录 0700，POSIX 视图不支持时按 `Assume` 跳过）。全量回归 **602 例：589 通过 / 0 失败 / 13 跳过**（app 196 → 201、database 160 → 163、sync 142 → 145；新增 1 例跳过为 Windows 无 POSIX 权限视图的权限断言）。

- **ZT-08（ISSUE-P1-08，生物封印凭据可被系统 PIN 解封，且新增指纹不使既有凭据失效）**：已修复（2026-09-10）。
  - **缺陷**：
    1. `UnlockAuthPolicy` 认证器集合为 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`，且封存内容是主密码 UTF-8 明文 → 锁屏为 4/6 位 PIN 时解封门槛由 Class 3 生物识别退化为弱 PIN，攻破锁屏即等同获得主密码（OWASP MASVS-AUTH-8）；
    2. `KeystoreManager` 快速解锁密钥 `setInvalidatedByBiometricEnrollment(false)`——官方对含 `AUTH_DEVICE_CREDENTIAL` 的密钥**忽略**该标志（锁屏凭据变更不触发失效），任何人新增自己的指纹后既有封印凭据照常可解密主密码。
  - **整改依据**：Android Keystore 官方密钥失效语义；OWASP MASVS-AUTH-8。两条缺陷同源（DEVICE_CREDENTIAL 授权集合），收敛为「**仅强生物识别**」模式一并根治。
  - **整改**：
    1. `UnlockAuthPolicy`（`app/src/main/java/com/keepasskey/app/security/UnlockAuthPolicy.kt`）：`USE_DEVICE_CREDENTIAL = false`，密钥生成侧 `keystoreAuthTypes = AUTH_BIOMETRIC_STRONG`、认证请求侧 `promptAuthenticators = BIOMETRIC_STRONG`（自动填充确认页等复用 `UNLOCK_AUTHENTICATORS` 的路径同步收敛）；新增封印许可闸门 `canSeal(BiometricStatus)`——仅「硬件存在且已录入」的 Class 3 强生物识别可封印；
    2. `KeystoreManager`（`app/src/main/java/com/keepasskey/app/security/KeystoreManager.kt`）：`generateNewDeviceCredentialKey` 改 `invalidateOnBiometricEnrollment = true`（纯生物识别密钥下该标志生效：录入/清空指纹即吊销密钥，新增指纹不再复用既有封印）；`getOrCreateDeviceCredentialKey` 迁移判据由「位包含」收紧为**全等** `info.userAuthenticationType == REQUIRED_AUTHENTICATOR_TYPES`，旧 `BIOMETRIC_STRONG|DEVICE_CREDENTIAL` 密钥自动删除重建（fail-safe：旧封印凭据随之失效，下次主密码解锁后重新封印）；
    3. `UnlockViewModel`（`app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockViewModel.kt`）：`requestBiometricEnrollment` 封印前经 `UnlockAuthPolicy.canSeal` 校验设备具备已录入强生物识别，弱凭据设备禁用封印（fail-closed，不降级锁屏凭据路径）；`unlockWithBiometric` 解密异常兜底分支补 `storage.clearCredential(dbId)`——密钥迁移重建后旧密文解密必然失败（AEADBadTagException），清除陈旧凭据使下次主密码解锁自动重登记，杜绝「永远解不开又永不重登记」死循环态；
    4. UI 文案同步：`unlock_biometric_primary_btn`「使用生物识别 / 锁屏凭据解锁」→「使用生物识别解锁」，相关注释与 KDoc 全量更新。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/security/UnlockAuthPolicyTest.kt` 8 例——认证器集合断言（密钥生成侧 / 认证请求侧均为纯 `BIOMETRIC_STRONG`、`AUTH_DEVICE_CREDENTIAL` / `DEVICE_CREDENTIAL` 位恒为 0、两侧「是否含设备凭据」语义一致）与封印闸门分支（AVAILABLE 放行；NO_HARDWARE / HARDWARE_UNAVAILABLE / NOT_ENROLLED / SECURITY_UPDATE_REQUIRED 一律拒绝）。全量回归 **610 例：597 通过 / 0 失败 / 13 跳过**（app 201 → 209）。

- **ZT-09（ISSUE-P1-09，快速解锁反克隆断言可被一步绕过且自证同源）**：已修复（2026-09-10）。
  - **缺陷**：`UnlockViewModel.verifyUnlockPasskeyOrCompat` 在 `isEnrolled(dbId)` 为 false 时**直接 `return true` 跳过断言**并后台补登记，而 `isEnrolled()` 仅判 SharedPreferences 中是否存在 passkey 记录（3 个 key）——任何能写应用私有数据者（root / ADB 备份恢复 / 物理取证）删除记录即让 signCount 反克隆断言彻底失效并被静默重新登记；此外断言无 `challenge` / 无 `clientDataJSON`，签名私钥 `setUserAuthenticationRequired(false)`，signCount 与登记记录落明文 XML 可被任意改写。
  - **整改依据**：FIDO2/WebAuthn 断言语义；零信任「验证不可被同一信任域内主体伪造」。
  - **整改**：
    1. **未登记 fail-closed 化**：`UnlockViewModel` 移除「旧凭据兼容通道」，`verifyUnlockPasskey` 将 `UnlockPasskeyGate.NotEnrolled`（未登记 / 记录被删 / 记录被篡改）与 `UnlockPasskeyGate.SigningFailed`（硬件签名失败）一律 fail-closed——清除封印凭据与通行密钥登记，回退 STANDARD 模式并提示「请使用主密码解锁后重新登记」，绝不静默放行；
    2. **断言引入随机 challenge 与 clientDataJSON**：`UnlockPasskeyManager` 新增 `newChallenge()`（32 字节一次性随机）与 `buildClientDataJson`（本地确定性序列化 `{"type":"webauthn.get","challenge":<Base64URL 无填充>,"origin":"https://keepasskey.local"}`）；签名覆盖范围升级为 `AuthenticatorData || SHA-256(clientDataJSON)`（对齐 WebAuthn），验证侧按预期 challenge 重建 clientDataJSON 逐字节比对（type/challenge/origin 全部不可变造），重放旧断言因 challenge 不匹配 fail-closed；`assertUnlock` / `verifyAndCommit` 均要求调用方（验证方）传入 challenge，门控结果以 `UnlockPasskeyGate` 封装（`AssertionReady` / `NotEnrolled` / `SigningFailed`）；
    3. **签名私钥绑定用户认证**：`KeystoreManager.getOrCreateUnlockPasskeyPair` 改 `setUserAuthenticationParameters(UNLOCK_PASSKEY_AUTH_VALIDITY_SECONDS=30, AUTH_BIOMETRIC_STRONG)` + `setUnlockedDeviceRequired(true)`——快速解锁流程内 BiometricPrompt（Class 3）授权解封后的时间窗内方可签名，窗口外签名抛 `UserNotAuthenticatedException` fail-closed；存量「未绑定用户认证」旧密钥经 `KeyInfo` 探测自动轮换重建（公钥随之失效 → 断言 fail-closed → 主密码完整解锁后重登记），杜绝无认证密钥游离；
    4. **signCount 与登记状态防篡改存储**：`KeystoreManager` 新增 `getOrCreateUnlockPasskeyIntegrityMac()`（硬件 HmacSHA256，不可导出）；`BiometricCredentialStorage` 登记记录（公钥/credentialId/signCount）落盘时计算绑定 databaseId 的完整性 MAC（`_passkey_mac`），读取时校验——MAC 缺失/不匹配一律按记录缺失处理（fail-closed）；`commitSignCount` 同步重算 MAC，杜绝「改计数留旧 MAC」；存储抽象为 `UnlockPasskeyStore` 接口（`SecurityModule` @Binds 绑定，对齐 `UnlockThrottleStore` 模式）。诚实边界：同 UID 任意代码执行者可调用 Keystore 重算 MAC，该威胁域本层不设防（与封印密钥同级），但文件级写入 / ADB 备份恢复已无法在绕过校验的前提下篡改或删除记录。
  - **测试证据**：`UnlockPasskeyAssertionTest` 重构为 9 例（合法断言 / 签名篡改 / signCount 回退克隆信号 / rpIdHash 归属 / **challenge 不匹配重放拒绝** / **clientDataJSON 篡改拒绝（含改签后仍拒绝）** / clientDataJSON 规范格式 / AuthenticatorData 37 字节规范 / 非 EC 公钥 fail-closed）；新增 `UnlockPasskeyManagerGateTest.kt` 7 例 + `FakeUnlockPasskeyStore.kt`——**「登记记录被删 → 断言拒绝（NotEnrolled）」**、未登记库拒绝、记录缺失 `verifyAndCommit` 拒绝、记录存在但硬件签名不可用 SigningFailed（fail-closed 不回退放行）、challenge 长度非法拒绝、challenge 一次性随机、enroll 失败不落任何记录。全量回归 **620 例：607 通过 / 0 失败 / 13 跳过**（app 209 → 219）。

- **ZT-10（ISSUE-P1-10，release 包保留日志与异常 message 外传）**：已修复（2026-09-10）。
  - **缺陷**：`app/proguard-rules.pro` 全文无 `-assumenosideeffects class android.util.Log` 且全仓无 `BuildConfig.DEBUG` 闸门 → release 包 logcat 仍输出 `Log.e`；实测泄漏 `userName`（PasskeyCreateActivity）、`rpId`（KeePasskeyCredentialProviderService）、`callingPackage`（双服务黑名单日志，暴露用户安装应用清单）与完整异常堆栈；`KeePasskeyAutofillService.kt:89,391` 与 `KeePasskeyCredentialProviderService.kt:102,172` 将裸 `t.message` 传给 `onFailure` / `GetCredentialCustomException`；`KdbxResult.kt:15` 未设 `userMessage` 时直接把异常 message 上浮 UI。
  - **整改依据**：OWASP MASVS-CODE-2 / MASVS-STORAGE-3；工程规则「日志严禁敏感明文」。
  - **整改**：
    1. **统一日志包装器**：新增 `core/src/main/java/com/keepasskey/core/log/AppLog.kt`（下沉 core 供 app/sync 复用）——v/d 由 `debugEnabled` 闸门控制（`MainApplication.onCreate` 按 `BuildConfig.DEBUG` 置位，app 模块补开 `buildFeatures.buildConfig`）；e/w 在 release（`debugEnabled=false`）下脱敏：只保留异常全限定类名，**绝不透出异常 message 与堆栈**；debug 构建保留完整堆栈便于定位；Log 调用整体 fail-safe（JVM 单测环境无 android.util.Log 实现时静默，日志永不阻断业务）；
    2. **R8 剥离**：`proguard-rules.pro` 新增 §12——`-assumenosideeffects` 对 `android.util.Log` 与 `com.keepasskey.core.log.AppLog` 的 v/d 双重剥离，release 产物直接消除 verbose/debug 调用点；
    3. **敏感标识日志清零**：黑名单命中日志移除 `callingPackage` / `callingPkg`（PasskeyCreateActivity 移除 `rpId=$rpId, userName=$userName`、PasskeyAssertionActivity 移除 `entryId=$entryId`、ProviderService 移除 `rpId=$rpId`）；全仓 7 个 app 文件 + `WebDavSyncProvider` 的 `android.util.Log` 直接调用统一迁移至 `AppLog`（除包装器外零残留）；
    4. **对外回调预定义文案**：Autofill `onFailure` 3 处改 `getString(R.string.autofill_fill_failed / autofill_save_failed)`（新增 2 条 strings 资源）；`GetCredentialCustomException` / `CreateCredentialCustomException` 2 处改 `getString(R.string.cred_error_unknown)`；`BaseCredentialActivity.failAndFinish` 移除被忽略的 message 形参（消除 `failAndFinish(t.message)` 6 处伪外传通道），全部调用点统一无参收尾；
    5. **KdbxResult 兜底脱敏**：`Failure.message` 兜底从 `error.message` 改为固定通用文案「未知错误」，裸异常 message 不再上浮 UI（原始异常仍保留在 `error` 字段供日志侧脱敏记录，需具体原因时由调用方显式构造 userMessage）。
  - **测试证据**：新增 `LogHygieneTest`（app）3 例静态卫生检查——①除 AppLog 包装器外全 5 模块生产源码禁止直接引用 `android.util.Log`；②日志调用行禁止敏感标识插值（callingPackage/callingPkg/rpId/userName/userDisplayName/entryId/expectedPackage）；③日志调用行禁止透传裸异常 message（`t.message` / `result.message` 等）；新增 `KdbxResultTest`（core）4 例——兜底固定文案且不含敏感内容、显式 userMessage 优先、error 字段保留原始异常、onFailure 回调同样受兜底约束。release 产物验证：R8 构建通过，dex 内旧敏感日志串（`拒绝下发数据集: ` / `缺少必要注册参数: ` / `拒绝返回凭据候选: ` / `rpId=` 后缀变体）全部消失，新预定义文案存在。全量回归 **627 例：614 通过 / 0 失败 / 13 跳过**（app 219 → 222，core 32 → 36）。

### 2.6 凭据提供者端到端契约（P1-01）

> 来源：2026-09-09 Android 16+ 真机实测回归（系统设置内已可勾选启用 KeePasskey，但第三方应用调起后握手/响应失败）。

- **ISSUE-P1-01（TASK-02，凭据提供者服务实机端到端注册与调起）**：已修复（2026-09-09）。
  - **现象与定界**：`AndroidManifest.xml` 的凭据服务契约名 `android.credentials.provider`、`SERVICE_INTERFACE` action、`BIND_CREDENTIAL_PROVIDER_SERVICE` 权限、`@xml/credential_provider_service`（含 `<capabilities>` 双能力声明与 `settingsActivity`）**全部正确**，系统在「设置 → 密码、密钥和自动填充」中可正常列出并启用 KeePasskey，说明服务发现与绑定链路无问题；故障发生在**绑定之后的请求注入阶段**。
  - **根因（平台契约级）**：挂在凭据条目上的 PendingIntent 全部以 `FLAG_IMMUTABLE` 创建，而系统 Credential Manager 在用户点选条目时是以 **fillIn Intent** 方式 `send()` 该 PendingIntent 的——请求本体（`EXTRA_BEGIN_GET_CREDENTIAL_REQUEST` / `EXTRA_GET_CREDENTIAL_REQUEST` / `EXTRA_CREATE_CREDENTIAL_REQUEST`）由系统在 send 阶段注入。`PendingIntent.FLAG_IMMUTABLE` 的官方语义为「**传给 send 方法用于填充未设置属性的附加 Intent 将被忽略**」，注入的 extras 因此被**静默丢弃**（已核对 `androidx.credentials:credentials:1.6.0` 源码 KDoc：`Action` / `AuthenticationAction` / `CreateEntry` / `PasswordCredentialEntry` / `PublicKeyCredentialEntry` / `CustomCredentialEntry` 一致要求 `must be created ... with flag PendingIntent.FLAG_MUTABLE to allow the Android system to attach the final request, and NOT with flag FLAG_ONE_SHOT`）。
  - **端到端后果**：
    1. `CredentialUnlockActivity` 的 `PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)` 恒为 null → 走 `RESULT_CANCELED` → **锁库态下点选「解锁 KeePasskey」永远无法回传候选**（系统按规范将选择器重新弹出并标注该 Action「无有效凭据」），对应验收标准 3 失败；
    2. `PasswordSaveActivity` 取不到 `CreatePasswordRequest` → `password` 恒为 null → **密码保存链路 100% 失败**；
    3. `PasskeyCreateActivity` 取不到 `callingAppInfo` → 非浏览器（`android:apk-key-hash`）路径无法绑定调用包名，注册被拒；
    4. 密码 / Passkey 候选条目自身不读取系统注入 extras（凭据内容由本应用 extras 承载），故表现为「部分场景可用、部分场景静默失败」的难定位握手故障。
  - **整改**：
    1. 新增单一事实源 `app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt`，以 `const val ENTRY_FLAGS = FLAG_MUTABLE or FLAG_UPDATE_CURRENT` 固化官方契约，并在 KDoc 中完整记录「为何必须 MUTABLE / 为何禁止 ONE_SHOT」与三类失败后果（常量声明为 `const val`，使纯 JVM 单测无需 Robolectric 即可断言）；
    2. `KeePasskeyCredentialProviderService` 3 处创建点（解锁 `AuthenticationAction`、`Passkey` 注册 `CreateEntry`、密码保存 `CreateEntry`）与 `CredentialResponseAssembler` 2 处创建点（`PublicKeyCredentialEntry`、`PasswordCredentialEntry`）统一改用 `CredentialPendingIntents.ENTRY_FLAGS`，全凭据路径再无 `FLAG_IMMUTABLE` 残留；
    3. `CredentialUnlockActivity` 加固：`lifecycleScope`（`Dispatchers.Main.immediate`）内已完成回传时跳过解锁页渲染，消除「已解锁态点选解锁 Action」的解锁页闪屏后再 finish；缺失原始请求的告警日志显式标注唯一成因（`AuthenticationAction` 的 PendingIntent 需 `FLAG_MUTABLE`），便于真机日志定位。
  - **不相干项说明**：传统 Autofill 兼容层（`KeePasskeyAutofillService`）的 PendingIntent 语义与 Credential Manager 不同，保持 `FLAG_IMMUTABLE` 不变，未纳入本次改动面。
  - **测试证据**：新增 `app/src/test/java/com/keepasskey/app/passkey/CredentialPendingIntentsTest.kt` 4 例，锁定不变式——必须含 `FLAG_MUTABLE`、必须含 `FLAG_UPDATE_CURRENT`、严禁 `FLAG_IMMUTABLE`、严禁 `FLAG_ONE_SHOT`。全量回归 **543 例：531 通过 / 0 失败 / 12 跳过**（app 179 → 183）。

### 2.7 生成侧私钥内存脱敏（P1-02）

> 来源：ISSUE-P1-02（P0-7 残余）。断言侧已于此前改造为 `readUtf8()` 字节流路径，本节闭环生成侧（密钥对生成 / 注册响应构建）与受保护字段（反）序列化层的内存脱敏评估与整改。

- **ISSUE-P1-02（Passkey 私钥在生成侧与受保护字段的内存脱敏评估）**：已修复（2026-09-09）。
  - **缺陷（全链路评估结论）**：
    1. `PasskeyCryptoEngine.generateEs256KeyPair` 经 `String.format("%064x", priv.d)` 生成不可变私钥 hex String；`generateEd25519KeyPair` / `generateRs256KeyPair` 经 `Base64.encodeToString` 生成不可变私钥 Base64 String——三路生成侧私钥编码产物全部驻留堆；
    2. 断言侧解码路径残留两处不可变私钥 String：`String(rawBytes).trim()`（形态判断物化）与 `BigInteger(String, 16)`（hex 解析物化）；
    3. 注册响应构建（`PasskeyCreateActivity.buildRegistrationJson`）派生字节数组（authData / attestationObject）无擦除。
  - **整改**：
    1. `PasskeyCryptoEngine` 新增零 String 编码辅助三件套：`scalarToHexChars`（BigInteger 二进制形态 → 定长 64 hex CharArray，标量副本 finally 清零）、`base64ToChars`（Base64 → CharArray，中间编码字节副本清零）、`sealedFromPrivateChars`（CharArray → ProtectedString 密文封装后字符副本清零）——三路密钥生成全部改走该通道，私钥材料生成后仅以 `ProtectedString`（InMemoryCipher 密文驻留）形态存活；
    2. `PasskeyData` 固化受控生命周期契约：类级 KDoc 声明「KDBX 4 自定义字段为 XML 文本承载、`privateKey` 是私钥明文文本唯一长期持有者、消费一律走新增的 `usePrivateKeyBytes()` 字节流通道（读出即用、退出自动清零）、严禁 `readString()`」；`toCustomFields()` KDoc 明示零拷贝别名语义（落库完成前严禁 clear）；`fromCustomFields()` KDoc 明示私钥只引用不读取；
    3. `PasskeyAssertionActivity.decodePrivateKeyBytes` 重写为纯字节通道：hex 手工半字节解析（奇数长度左对齐补零，等价 `BigInteger(String,16)` 无符号语义）、Base64 直接字节流解码（两侧 ASCII 空白剔除后切片解码、切片副本擦除），全程零 String 中间量；断言会话派生量（authData / clientDataBytes / dataToSign）统一 finally 擦除；
    4. `PasskeyCreateActivity.buildRegistrationJson` 补齐派生数组擦除（authData / attestationObjectBytes finally 清零），并以 KDoc 声明不可消解边界——系统 Credential Manager 契约要求响应为 JSON 字符串，其内容均为公开注册材料（不含私钥），属受控且可接受驻留。
  - **验证过程记录**：初版 `scalarToHexChars` 存在「自右向左回填时高/低半字节写反」缺陷（每字节半字节序颠倒，签名验签闭环测试立即捕获 20/20 公钥反推不匹配），已修正为先写低半字节再写高半字节，并以 50 例随机标量对照 `String.format("%064x")` 全等通过。
  - **测试证据**：新增 `crypto/src/test/java/com/keepasskey/crypto/PasskeyCryptoEngineTest.kt` 2 例——「ES256 私钥保持定长 64 字符小写 hex 且经受控字节流通道签名可用」（既有文本解析契约不变 + `usePrivateKeyBytes` 消费契约）与「Ed25519 与 RS256 私钥经受控字节流通道签名可用」（Base64 文本字节流 44B 解码还原种子 / PKCS#8 DER 签名）。全量回归 **545 例：533 通过 / 0 失败 / 12 跳过**（crypto 52 → 54）。

---

### 2.8 KDBX 回收站保留桶与历史保留期维护（P1-03）

> 来源：ISSUE-P1-03（P1-8 残余）。Meta 与 Group 的 7 个官方字段及 CustomData/Tags 已于 P1-8 补齐读写往返，本节闭环官方 KeePass 2.x 回收站（RecycleBin）分流策略与保留期/维护（Retention / Maintenance）桶机制。整改依据以 KeePass 2.61.1 C# 官方实现为终极裁决：`KeePass/Forms/MainForm_Functions.cs`（`EnsureRecycleBin` / `DeleteEntry` / `DeleteGroup` 分流）与 `KeePass/Forms/DatabaseOperationsForm.cs`（`MaintenanceHistoryDays` 历史维护）。

- **ISSUE-P1-03（KDBX Meta 与 Group 回收站保留桶机制补齐）**：已修复（2026-09-09）。
  - **缺陷**：
    1. **删除「包含回收站」的祖先组会静默丢库**（数据完整性 P1）：`RecycleBinCoordinator.deleteGroup` 旧实现仅沿父链上溯判断「目标是否位于回收站内」，未覆盖官方 `pgRecycleBin.IsContainedIn(pg)`（回收站是目标的后代）分支 → 走软删路径把整棵子树（连同回收站自身）移入 `binGroup.id`，而该父组已先被 `deleteGroup(uuid)` 摘除，`saveGroup` 找不到父组 → 目标组及其全部内容**无墓碑静默消失**。
    2. **回收站嵌套子分组内的条目漏判**：`deleteEntry` / `batchDeleteEntries` 旧实现只比对「直接父组 UUID 或名称是否为回收站」，未覆盖官方 `pgParent.IsContainedIn(pgRecycleBin)`（父组位于回收站子树内）→ 位于回收站子分组内的条目被错误地再次「移入回收站」而非物理删除。
    3. **`maintenanceHistoryDays` 为死字段**：Meta 的保留期字段读写往返完好，但全仓无任何消费方——官方「删除 N 天前的历史条目」维护操作从未生效，历史保留期（Retention）缺失自动清理。
    4. **回收站组创建属性未对齐官方 `EnsureRecycleBin`**：懒创建的回收站组仅设 TrashBin 图标（43），缺 `EnableAutoType=false` / `EnableSearching=false`，导致回收站条目仍被搜索与 AutoType 命中。
  - **整改**：
    1. `core/model/KdbxGroup.kt` 新增 `subtreeContainsGroup(groupId)` 原语，对齐官方 `PwGroup.IsContainedIn` 的祖先/后代判定语义（含自身）；
    2. `RecycleBinCoordinator` 三处删除路径统一改走官方分流：新增只读 `resolveRecycleBinGroup(db)`（UUID 命中优先、名称回退、不创建不改 Meta），`deleteGroup` 物理删除条件补齐为「回收站禁用 ∨ 目标即/在回收站子树内 ∨ **目标包含回收站**」，`deleteEntry` / `batchDeleteEntries` 改为「父组即回收站或位于回收站子树内 → 物理删除 + 墓碑」；
    3. `database/history/HistoryManager.kt` 新增 `pruneHistoryByAge` / `pruneGroupHistoryByAge`，实现官方 `DatabaseOperationsForm` 维护算法（移除 `lastModificationTime` 早于 `now - maintenanceHistoryDays` 的历史快照）；安全取舍：`maintenanceHistoryDays <= 0` 一律视为「未配置保留期」不修剪（官方 uint 语义下 0 会删全部历史，自动路径下拒绝该破坏性行为）；全树无变化时返回同一实例，供保存路径免拷贝；
    4. `DatabaseSession.save()` 序列化前接线保留期维护：按 `db.maintenanceHistoryDays` 修剪超期历史，仅在确有修剪时重建内存树并回写 `_database`，使该 Meta 字段真实生效；
    5. `getOrCreateRecycleBinGroup` 懒创建对齐官方 `EnsureRecycleBin`：补 `enableAutoType=false` / `enableSearching=false`（TrashBin 图标 43 保持）。
  - **测试证据**：
    - `database/HistoryManagerTest` 新增 4 例：保留期修剪移除超期快照、`<=0` 不修剪（同一实例）、无超期返回同一实例、整树递归维护且无变化免拷贝；
    - `database/KdbxXmlFullRoundtripTest` 新增 1 例 `testRecycleBinBucketStructureRoundtrip`：回收站保留桶结构（TrashBin 图标 + 禁用 AutoType/搜索的回收站组、桶内条目 `previousParentGroup` 回退指针、桶内嵌套子分组、Meta `recycleBinUuid/Enabled/Changed` 三字段）完整往返无损；
    - `app/RealVaultRepositoryTest` 新增 2 例：删除包含回收站的父组走物理删除且根组存活（不整库丢失）+ 追加墓碑、删除回收站嵌套子分组内条目物理删除 + 追加墓碑。
    - 全量回归 **573 例：561 通过 / 0 失败 / 12 跳过**（app 183 → 185、database 155 → 160；跳过 12 例仍为 `LiveSyncServersTest` 真实联调用例）。

---

### 2.9 主密码解锁失败节流与失败态清零（P1-04）

> 来源：ISSUE-P1-04（ZT-04）。整改依据：OWASP MASVS-AUTH-10（失败限流）与工程规则敏感数据铁律；节流阈值与退避策略参照 Google/业界惯例（如 Apigee「5 次失败触发锁定」）与 Android 平台「限制认证频率、硬件级退避抵御在线/离线暴力破解」指南（google-developer-knowledge 检索确认）。

- **ISSUE-P1-04（主密码解锁零失败节流与锁定，失败态主密码滞留堆内存）**：已修复（2026-09-09）。
  - **缺陷**：
    1. **零失败节流（反暴力破解缺口）**：全 `app/src/main` 检索 `attemptCount|failedAttempt|lockout|throttle|backoff|cooldown` 零命中——主密码解锁无任何失败计数、指数退避或临时锁定，仅依赖 KDF 计算成本抵御在线爆破；
    2. **失败态主密码永不清零（内存治理缺口）**：`UnlockViewModel.unlock()` 的 `finally` 判据为 `if (_uiState.value.isLoading)`，而失败分支已先将 `isLoading` 置 false → 失败后 `passwordChars` 永不清零，错误主密码持续驻留堆内存直至下次输入或 `onCleared()`。
  - **整改**：
    1. 新增单一防线 `app/src/main/java/com/keepasskey/app/security/UnlockThrottle.kt`，内聚四件套：
       - `UnlockThrottleRecord`（失败计数 + 锁定截止时间戳）、`ThrottleGate`（`Allowed` / `Locked`  sealed 判定，携带 `failureCount` 与剩余毫秒）；
       - `UnlockThrottleStore` 存储抽象 + `SharedPrefsUnlockThrottleStore` 生产实现（`@Singleton`，计数与锁定截止落盘 SharedPreferences，**跨冷启动持久化**，杜绝「杀进程即重置计数」的绕过路径；持久化内容不含任何主密码明文）；
       - `UnlockThrottlePolicy` 纯函数退避策略：连续失败达 `FAILURE_THRESHOLD=5` 起启用指数退避 `BASE_BACKOFF_MS(30s) * 2^(count-5)`，封顶 `MAX_BACKOFF_MS(30min)`，移位安全阈值防溢出；阈值与时长集中为 `const val`（阈值可配）；
       - `UnlockThrottleManager`（`@Singleton`）状态机三原子操作：`gate`（解锁前闸门，锁定期 fail-closed）/ `registerFailure`（累加计数 + 重算锁定截止）/ `registerSuccess`（清零复位）；时间源以方法默认参数 `now` 注入，锁定边界在 JVM 单测可精确断言无需真实等待；
    2. `SecurityModule`（新增 DI 模块）以 `@Binds` 将 `UnlockThrottleStore` 绑定到 `SharedPrefsUnlockThrottleStore`；`UnlockViewModel` 追加 nullable `unlockThrottleManager`（生产 Hilt 恒注入真实实例，单测注入内存实现）；
    3. `UnlockViewModel.unlock()` 重构：
       - **解锁前闸门**：锁定期内直接 fail-closed 拒绝（**绝不触碰 KDF/解密管线**），呈现本地化锁定剩余时长（≥1 分钟按分钟、否则按秒，文案交字符串资源），并同步清零主密码与递增输入框擦除令牌；
       - **失败计数分流**：仅「凭据错误」（`KdbxInvalidCredentialsException`）计入节流，IO/文件损坏等非认证失败不计入，避免瞬时故障误锁用户；达到阈值时错误文案切换为锁定提示；
       - **失败路径无条件清零**（核心修复）：抽出 `wipeMasterPassword()`，失败分支与 `finally` 兜底均**无条件**清零 `passwordChars`（判据不再依赖 `isLoading`），异常/失败/成功/锁定各路径均不残留主密码明文；成功路径追加 `registerSuccess` 复位计数；
    4. `UnlockUiState` 新增 `throttleFailureCount` / `throttleLockoutRemainingMs` / `clearPasswordFieldToken`（递增令牌）；`SecurePasswordField` 新增 `wipeToken` 参数——令牌变化时擦除组件显示态与桥接 CharArray（仅在「变化」时触发，避免首次组合误清空预填），`UnlockScreen` 传入 `uiState.clearPasswordFieldToken`，使失败后 VM 清零与输入框显示态一致（用户须重新输入后重试），杜绝「字段有点、VM 已空」的重试错配；
    5. 新增 `values/` 与 `values-en/` 锁定文案 `unlock_error_locked_out_seconds` / `unlock_error_locked_out_minutes`（双语齐备，规避 `lintVitalRelease` MissingTranslation）。
  - **测试证据**：
    - 新增 `app/security/UnlockThrottleManagerTest` 7 例：阈值以下不退避、达阈值起指数增长、退避封顶不溢出、连续失败累加计数且阈值前不锁定、达阈值触发锁定并到期自动放行（计数保留续升退避）、成功解锁重置、预置锁定态下闸门拒绝且剩余时长正确；
    - `app/ui/screens/unlock/UnlockViewModelTest` 新增 4 例（6 → 10）：失败后无条件清零（二次不输入重试命中「空密码」反证 `passwordChars` 已清）、连续失败累加计数并达阈值触发锁定（锁定期内计数不再累加）、锁定期内闸门拒绝「正确」主密码且不假成功、成功解锁后节流计数归零；配套 `FakeUnlockThrottleStore` 内存实现与 `FakeVaultRepository(forceInvalidCredentials=true)` 认证失败驱动。
    - 全量回归 **584 例：572 通过 / 0 失败 / 12 跳过**（app 185 → 196；跳过 12 例仍为 `LiveSyncServersTest` 真实联调用例）。

---

### 2.10 同步凭据认证绑定与 S3 密钥内存治理（P1-06）

> 来源：ISSUE-P1-06（ZT-06）。整改依据：工程规则敏感数据铁律（CharArray/ByteArray 显式清零，绝不落地为 String）；零信任「凭据最小暴露面」；Android Keystore 官方密钥认证语义。

- **ISSUE-P1-06（同步凭据无认证绑定 + S3 密钥 String 驻留与 SigV4 派生链零擦除）**：已修复（2026-09-09）。
  - **缺陷**：
    1. **S3 凭据 String 不可变驻留**：`S3SyncProvider` 构造器字段 `accessKeyId: String` / `secretAccessKey: String` 与 Provider 同生命周期，结构性不可擦除；`SyncCoordinator` 由 `String(cfg.accessKey)` 物化后传入；
    2. **SigV4 派生链零擦除**：`signV4()` / `getSignatureKey()` 中 `signingKey / kSecret / kDate / kRegion / kService` 全部未 `fill(0)`——整个 sync 模块 S3 路径擦除点数为 0；
    3. **requireUserAuth=false 无 UI 明示**：`SyncCredentialsStore` 以 `getOrCreateKey(SYNC_KEY_ALIAS, requireUserAuth = false)` 封印凭据，进程内任意路径无需用户认证即可解封，但用户对此安全取舍完全不知情。
  - **整改**：
    1. `S3SyncProvider` 构造器凭据改为 `CharArray`（借用语义），新增 `clearCredentials()` 方法供调用方在同步周期结束后显式擦除；
    2. `signV4()` 重写：`canonicalRequestBytes` 用毕 finally 清零；`signingKey` 用毕 finally 清零；`accessKeyId` 仅在构造 Authorization header 瞬间转 String（方法局部变量，随栈帧退出不可达）；
    3. `getSignatureKey()` 重写：接受 `CharArray` 参数，全派生链（kSecret/kDate/kRegion/kService）在 finally 中逐一 `fill(0)` 擦除；新增 `CharArray.toByteArrayUtf8()` 扩展（CharBuffer 直转，不经 String）；
    4. `hmacSha256()` / `hmacSha256Hex()` 重写：`dataBytes` 与 `result` 用毕 finally 清零；
    5. `SyncCoordinator.resolveProvider()` 改为传递 `cfg.accessKey.clone()` / `cfg.secretKey.clone()`（CharArray 借用语义转移），构造失败时 catch 块擦除 clone 副本；`runSyncCycle()` 包裹 try-finally，finally 中调用 `(provider as? S3SyncProvider)?.clearCredentials()`；
    6. `SyncCredentialsStore.encrypt()` 补充完整 KDoc 安全取舍声明（必要性/风险/缓解措施/替代方案评估）；
    7. UI 层 `ZeroKnowledgeCard` 新增 `sync_credential_auth_notice` 文案（中英双语），向用户明示封印密钥不绑定生物认证的取舍与缓解措施。
  - **涉及文件**：
    - `sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt`（构造器 CharArray + signV4/getSignatureKey/hmacSha256 全链 finally 清零 + clearCredentials）
    - `app/src/main/java/com/keepasskey/app/sync/SyncCoordinator.kt`（CharArray clone 传入 + try-finally clearCredentials）
    - `app/src/main/java/com/keepasskey/app/sync/SyncCredentialsStore.kt`（requireUserAuth=false 安全取舍 KDoc）
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt`（ZeroKnowledgeCard 新增文案）
    - `app/src/main/res/values/strings.xml` + `values-en/strings.xml`（sync_credential_auth_notice 双语）
    - `sync/src/test/.../S3SyncProviderTest.kt`、`S3SyncScenarioTest.kt`、`LiveSyncServersTest.kt`（适配 CharArray 构造器）
  - **测试证据**：
    - 全量回归 **584 例：572 通过 / 0 失败 / 12 跳过**（测试数不变，仅构造器签名适配；跳过 12 例仍为 `LiveSyncServersTest` 真实联调用例）。
    - SigV4 已知答案向量测试（`测试 SigV4 签名已知答案向量含星号波浪号与UTF8键`）通过，证明派生链清零未影响签名正确性。

---

### 2.11 Argon2 原生内核 C→Rust 迁移（P2-14）

> 来源：ISSUE-P2-14（Rust 秘密飞地 PoC）。整改依据：RustCrypto `argon2`（纯 Rust + `zeroize` RAII 确定性擦除，消除 C 手动 `malloc`/`kp_wipe`/`free` 面）；「零二进制信任根」哲学（源码全量入库 + 从源码交叉编译）。分批计划与风险登记册见 [`plans/rust-enclave-poc.md`](../plans/rust-enclave-poc.md)。

- **ISSUE-P2-14（Argon2 原生内核 C→Rust 迁移）**：已完成（2026-09-09，Batch 0~5 全部闭环）。
  - **缺陷 / 动机**：原 `crypto/src/main/cpp/` 以 vendored PHC 官方 C 参考实现（`argon2/`，1814 行）+ 手写 JNI 桥
    `keepasskey_argon2_jni.c` 提供派生，靠手动 `malloc`/`kp_wipe`/`free` 管理 password/salt/secret/AD 缓冲
    ——「忘记 wipe / 错误路径漏擦」是 C 手动内存管理的固有隐患，正是 `AGENTS.md §6` 承认的
    「抗堆扫描 / 崩溃转储明文暴露」短板的原生解法缺口。
  - **整改（drop-in 替换，Kotlin 侧库名/签名/null 语义零改动）**：
    1. **Batch 0**：冻结 14 条 BouncyCastle 对照向量（d/id × 0x10/0x13 × secret/AD × p=1/4 × 现实档位
       + 2 条 64B 长 AD 探针）`crypto/src/test/resources/argon2-interop/argon2-bc-vectors.json`；
    2. **Batch 1**：新建 Rust crate `crypto/src/main/rust/`（`argon2 0.6.0` + `zeroize` + `jni`），
       纯 `derive()` 逐条复刻 C 参数闸门；`cargo test` 9/9（IETF draft-irtf-cfrg-argon2-12 §5 官方 KAT ×4
       + BC 冻结向量等价 + 闸门负例 + 确定性 + JNI 签名/闸门）；
    3. **Batch 2**：`jni_bridge.rs` 导出 `Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey`
       （符号/签名与 C 桥逐字一致）；输入拷入 `Zeroizing<Vec<u8>>`、输出 `Zeroizing<[u8;32]>`，
       全路径（含 `?` 提前返回）RAII 擦除；整个 FFI 体裹 `catch_unwind`，panic 归一为返回 `null`；
    4. **Batch 3**：Gradle `:crypto:cargoNdkBuild` 经 cargo-ndk 交叉编译 4 ABI 至 `build/rust/jniLibs/`，
       移除 `externalNativeBuild.cmake`；APK 内 4 ABI `.so` 经 `llvm-readelf` 核对（AArch64 ELF64 DYN、
       导出符号 GLOBAL FUNC、NEEDED 仅 libc/libdl），release `seeds.txt` 核对 native 方法未被 R8 混淆；
       `deny.toml` 落地供应链闸门；
    5. **Batch 4**：宿主侧 JNI 运行时验证（见下）+ R1 性能决策闸门通过；
    6. **Batch 5**：`git rm crypto/src/main/cpp/`（17 文件 / 4212 行，git 历史可回溯），
       同步修正 `Argon2BcVectorTest` 的模块识别（改判 `src/main/rust`）与 `NativeArgon2`/`Argon2KdfEngine`/`ARCHITECTURE` 文档。
  - **R1（并行性能）裁定与实测**：`argon2 0.5.3` 无 `parallel` feature → 采用 **0.6.0 + `parallel`(rayon)**。
    宿主 Windows x86_64，m=16MiB t=2，warmup 后取 3 次最优：

    | 档位 | Rust 原生 | BouncyCastle | 加速比 |
    |---|---|---|---|
    | p=1 | 11.6 ms | 25.8 ms | 2.22× |
    | p=2 | 6.8 ms | 21.5 ms | 3.15× |
    | p=4 | 3.9 ms | 20.8 ms | 5.37× |

    原生 p1→p4 提速约 3×（rayon 多核收益成立），**无性能回退 → 决策闸门通过**。
  - **R2（AD 长度上限）裁定与缓解**：RustCrypto `AssociatedData::MAX_LEN = 32B` 为硬上限（0.5.3/0.6.0 同），
    而 C/BC 接受任意长度。真实 KeePass/KeePassXC 生成库不设 KDF 的 `A` 字段 → 互操作风险 ≈ 0；
    Rust `derive()` 对 AD>32 fail-closed 返回 `None`，并在 `Argon2KdfEngine` 加最小 Kotlin 守卫
    （`NATIVE_MAX_AD_LEN = 32`，AD 超限路由 BC 兜底）—— 对「Kotlin 零改动」目标的**受控偏差**，
    由 `Argon2AdLimitFallbackTest`（64B AD 经 BC 兜底 == 冻结向量）锁定。
  - **R6（桌面无 `.so`）缓解**：计划原定用 `androidTest` 做运行时验证，本机无设备/模拟器；
    改为**宿主侧替代方案** —— `:crypto:cargoHostBuild` 产出宿主 cdylib，
    经 `-Djava.library.path` 注入单测 JVM，使桌面 `NativeArgon2.available == true`，
    新增 `NativeArgon2HostJniTest`（4 例：全参数域原生 ≡ BC、JNI 边界闸门归一 null、确定性、
    性能回归断言 `NATIVE_VS_BC_MAX_RATIO = 2.0`）；无 cargo 时自动 `Assume` 跳过，不阻断 CI。
    真机 arm64 instrumented 验证已外置为 **ISSUE-P3-11**。
  - **体积代价（AGP strip 后 release `.so`）**：arm64-v8a 17.6KB → 434.0KB；armeabi-v7a 19.2KB → 312.7KB；
    x86 21.9KB → 505.8KB；x86_64 22.4KB → 478.0KB（增量来自 Rust std + rayon/crossbeam + blake2）。
  - **涉及文件**：
    - `crypto/src/main/rust/`（`Cargo.toml`、`src/lib.rs`、`src/jni_bridge.rs`、`deny.toml`，新增）
    - `crypto/src/main/cpp/`（Batch 5 `git rm` 删除）
    - `crypto/build.gradle.kts`（`cargoNdkBuild` 4 ABI 交叉编译 + `cargoHostBuild` 宿主验证 + `-Djava.library.path` 注入）
    - `crypto/src/main/java/com/keepasskey/crypto/kdf/Argon2KdfEngine.kt`（R2 守卫 `NATIVE_MAX_AD_LEN`）
    - `crypto/src/test/java/com/keepasskey/crypto/kdf/`（`NativeArgon2HostJniTest` 新增；
      `Argon2InteropDiagnosticTest` 增「原生路径复现 libargon2 参考基准」；`Argon2AdLimitFallbackTest` 新增；
      `Argon2BcVectorTest` 模块识别路径修正）
  - **测试证据**：
    - `./gradlew.bat test` 全绿 **591 例：579 通过 / 0 失败 / 12 跳过**（crypto 55 → 61）；
      跳过 12 例仍为 `LiveSyncServersTest`。
    - `cargo test` 9/9 全绿；`assembleDebug` + `assembleRelease`(R8) 通过。
    - 互操作：宿主原生路径复现 libargon2（C 参考实现）预计算基准 `4423de68…`（`Argon2InteropDiagnosticTest`）。
    - 供应链：`cargo deny check licenses bans sources` → **bans ok, licenses ok, sources ok**（仅「白名单许可未出现」
      级 warning）；`advisories` 子检查需联网拉取 rustsec/advisory-db，本环境 github 连接被重置未能执行，已并入 ISSUE-P3-09。

---

### 2.12 S3 AccessKey 在 SettingsUiState 中的 String 留存改造（P2-01）

> 来源：ISSUE-P2-01（P2-18 残余）。整改依据：敏感数据治理铁律（凭据类字段避免在长期驻留的 UI 状态流中明文驻留）。

- **ISSUE-P2-01（S3 AccessKey String 留存改造）**：已完成（2026-09-10）。
  - **缺陷 / 动机**：WebDAV 密码与 S3 SecretKey 已于 Wave 15 改走 `CharArray?` 一次性预填通道，
    但 S3 `accessKey` 仍在 `SettingsUiState` / `SettingsSyncController.SyncUiState` 中以不可变 `String`
    长期驻留 StateFlow（凭据恢复后直至 ViewModel 销毁才释放），违背敏感数据治理铁律。
  - **整改（完全对齐 Wave 15 既有预填通道模式）**：
    1. `SettingsSyncController`：`SyncUiState` 移除 `s3AccessKey: String` 字段；新增
       `_s3AccessKeyPrefill` / `s3AccessKeyPrefill` CharArray 一次性预填通道与 `clearS3AccessKeyPrefill()`；
       `restoreSyncCredentials()` 将 `cfg.accessKey`（CharArray）直接下发预填通道，
       删除原「转 String 投影」路径；`updateS3Config()` 的 `accessKey` 参数改为 `CharArray` 借用语义
       （https 拒绝分支即时 `fill('0')`，存储库封印后兜底再擦一次；保存成功后与 SecretKey 通道一并终结）；
    2. `SettingsViewModel`：透传 `s3AccessKeyPrefill` StateFlow 与 `clearS3AccessKeyPrefill()`；
       `updateS3Config` 签名同步改为 CharArray 借用；`onCleared()` 销毁时擦除 AccessKey 预填通道；
       uiState 映射移除 `s3AccessKey`；
    3. `SettingsUiState`：移除 `s3AccessKey: String` 字段（UiState/StateFlow 不再有任何 AccessKey 明文驻留点）；
    4. UI 侧（`CloudSyncScreen` / `S3ConfigFields` / `WebDavSyncScreen` / `KeePasskeyApp`）：
       AccessKey ID 输入改走 `SecurePasswordField`（显示用 String 仅存活于组件内部，离场 DisposableEffect
       与保存成功路径均清零本地 CharArray），既有 AccessKey 经 `s3AccessKeyPrefill` 一次性预填下发
       （不触发脏标记），用户开始编辑时经 `onS3AccessKeyEdited` 终结预填通道生命周期。
  - **语义说明**：AccessKey ID 虽随请求头明文传输属标识符，但作为云存储凭据对仍按凭据治理；
    输入框默认圆点遮掩并提供可见性切换（与 SecretKey 输入一致），不影响正常配置读取与保存。
  - **涉及文件**：
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncComponents.kt`（`S3ConfigFields`）
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/WebDavSyncScreen.kt`
    - `app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt`
  - **测试证据**：`./gradlew.bat test` 全绿（app 222 例重跑通过 / 0 失败，其余模块 up-to-date 基线不变，
    全仓 627 例 0 失败）；凭据存储层 CharArray 借用语义与往返由既有 `SyncCredentialsStoreTest` 锁定。

---

### 2.13 Passkey 注册 DAL 远程资产声明校验（P2-02）

> 来源：ISSUE-P2-02（P2-33 残余）。整改依据：Google Digital Asset Links 规范与 FIDO2 CTAP2 规范；零信任「来源归属必须可验证」。

- **ISSUE-P2-02（Passkey 注册的完整 DAL 远程资产声明校验）**：已完成（2026-09-10）。
  - **缺陷 / 动机**：`DomainMatcher` 已有可注册域（eTLD+1 / PSL）白名单防线，但普通应用
    （`android:apk-key-hash` origin）发起 Passkey 注册时，凭据侧仅有「rp.id 为可注册域名」的弱约束——
    任意应用都可为任意域名生成注册响应，缺 Native App 与 RP ID 的强双向绑定。
  - **离线与在线权衡（验收标准 1）**：
    1. **校验时机**：`onBeginCreateCredentialRequest` 有系统 5s 硬超时预算且属查询阶段，在其中做网络拉取
       会拖慢甚至阻塞系统弹窗；权威校验落在 `PasskeyCreateActivity`（用户显式确认创建的最终落地路径），
       begin-create 阶段维持现有本地约束（`isRpIdTrustedForCreation`）不变；
    2. **在线优先、缓存兜底**：带 TTL 的内存缓存吸收重复注册的重复拉取——正向 24h / 负向 10min
       （负向短 TTL 保证站点补发声明或网络恢复后可及时收敛）；
    3. **fail-closed 策略（验收标准 3）**：网络不可用 / DAL 格式错误 / 无匹配声明一律拒绝创建；
       离线注册需求经设置中既有「跳过 DAL 校验」开关（`skipDalVerification`，默认关闭）显式授权降级
       （用户主动操作 + 落告警日志，满足「显式用户告警/授权」替代路径）。
  - **整改实现**：
    1. 新增 `DigitalAssetLinksVerifier`（`@Singleton`）：经 OkHttp 拉取 `https://<rpId>/.well-known/assetlinks.json`
       （connect/read 2s、call 3s 严格超时，响应体 256KB 防御性上限），按
       `delegate_permission/common.get_login_creds` + `namespace=android_app` + 包名精确相等 +
       证书 SHA-256 指纹（冒号/无冒号、大小写归一）匹配声明；`DalResult` 三态
       （VERIFIED / NOT_VERIFIED / NETWORK_UNAVAILABLE）区分确定性失败与网络故障；
    2. 内嵌 `DalStatementMatcher`（纯函数）与 `MinimalJson`（极简 JSON 解析器）：零 Android 框架依赖、
       解析失败一律 fail-closed 返回 null（不引入 org.json 的原因：Android 单测 stub 不可用且工程无既有 JSON 测试依赖）；
    3. `CallingOriginResolver` 新增 `certSha256Hex()`：调用方签名证书 SHA-256 大写无冒号摘要（与 apk-key-hash 共用摘要函数）；
    4. `PasskeyCreateActivity` 注册门控：浏览器委派调用豁免（rp.id ↔ web origin 归属已由
       `DomainMatcher.isDomainMatch` 严格点号边界强制）；普通应用必须通过 DAL 声明校验，且置于
       用户验证门控（ZT-03）**之前** fail-fast；无法获取调用方签名证书同样 fail-closed；
    5. `skipDalVerification` 假开关真实接线（ISSUE-P3-03 43b 部分闭环），设置侧注释同步更新。
  - **涉及文件**：
    - `app/src/main/java/com/keepasskey/app/passkey/DigitalAssetLinksVerifier.kt`（新增）
    - `app/src/main/java/com/keepasskey/app/passkey/CallingOriginResolver.kt`
    - `app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt` / `SettingsUiState.kt`（注释同步）
  - **测试证据**：新增 `DigitalAssetLinksVerifierTest` 17 例（MockWebServer 远程拉取、声明匹配核心、
    JSON 解析容错、指纹跨格式归一、缓存命中/TTL 过期/负向可恢复、非法入参零网络请求）全部通过；
    `./gradlew.bat test` 全绿：全仓 **644 例（app 239 / core 36 / crypto 61 / database 163 / sync 145），
    631 通过 / 0 失败 / 13 跳过**（12 例联调 + 1 例 Windows 权限视图，跳过项与既有基线一致）。

---

### 2.14 App 模块 14 个测试用例消除 Fake 自测（P2-03）

> 来源：ISSUE-P2-03（P2-36 残余）。整改依据：工程规则高质量测试要求（单测必须验证真实生产行为与状态机）。

- **ISSUE-P2-03（App 模块 14 个测试用例消除 Fake 自测）**：已完成（2026-09-10）。
  - **缺陷 / 动机**：`FakeVaultRepositoryTest` 的 14 个用例直接断言 `FakeVaultRepository`
    自身的内存容器增删改行为（多库管理、CRUD、回收站语义、分组操作），而非被测
    ViewModel / Coordinator 对仓库契约的处理——测试目标错位，覆盖率虚高。
  - **逐项处置**（14 例审查结论）：
    1. **11 例语义已被生产 ViewModel 测试真实覆盖**，随文件删除：
       库选择/新建/移除（`DatabasePickerViewModelTest` 4 例已覆盖）、回收站还原/彻底删除/清空、
       批量删除、新建分组（`VaultListViewModelTest` 已覆盖）、`saveEntry` 首删入回收站语义
       （`回收站内还原条目` 等用例依赖同一 Coordinator 路径）；
    2. **3 例纯 fixture 断言**（初始数据仅一库激活、回收站分组标记、mock 类别全覆盖）
       无生产行为可验证，删除；
    3. **3 例生产语义缺口重构为被测 ViewModel 驱动**（见下）。
  - **整改实现**：
    1. 删除 `app/src/test/java/com/keepasskey/app/data/repository/FakeVaultRepositoryTest.kt`（14 例自测）；
    2. 新增 `EntryEditViewModelTest`（2 例）：新建条目保存成功发出 `SaveSuccess` 事件并落库；
       更新既有条目经 ViewModel 保存链路自动归档历史修订（修订数 +1 且修订快照保留旧凭据字段）；
    3. `VaultListViewModelTest` 补 2 例：`batchMoveSelected` 批量移动选中条目到目标分组并退出
       批量模式；`deleteGroup` 删除分组并将下属条目全部移入回收站。
  - **涉及文件**：
    - `app/src/test/java/com/keepasskey/app/data/repository/FakeVaultRepositoryTest.kt`（删除）
    - `app/src/test/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModelTest.kt`（新增）
    - `app/src/test/java/com/keepasskey/app/ui/screens/vault/VaultListViewModelTest.kt`
  - **测试证据**：`./gradlew.bat test` 全绿：全仓 **634 例（app 229 / core 36 / crypto 61 / database 163 / sync 145），
    621 通过 / 0 失败 / 13 跳过**（跳过项与既有基线一致）；净变化 −14 Fake 自测、+4 ViewModel 真行为用例。

### 2.15 Sync 与 Merger 边缘分支单元测试补齐（P2-04）

> 来源：ISSUE-P2-04（T-02 / T-06 残余）。整改依据：KeePassXC Merger 算法规范与双向同步测试要求；工程规则高质量测试要求。

- **ISSUE-P2-04（Sync 与 Merger 边缘分支单元测试补齐）**：已完成（2026-09-10）。
  - **缺陷 / 动机**：
    1. `T-02`：FakeSyncProvider 缺少 ETag 预检（If-Match）行为断言——预检失败时不得覆盖远端、
       预检参数是否随各上传路径真实传递均无回归锁；
    2. `T-06`：`KdbxMergerV2Test` 仅覆盖复活条目回退分支，挂载点丢失自愈、分组子树冲突仲裁
       与字段级边缘场景缺少独立单测。
  - **整改实现**（新增 13 例回归锁）：
    1. **SyncEngineTest（+3）**：
       - `测试 FakeSyncProvider ETag 预检失败不得覆盖远端`：期望 ETag 不匹配 → ConflictError
         且远端内容与 ETag 原样保留（upload 与 uploadAtomic 双路径验证）；匹配 → 覆盖成功并前移
         ETag；`expectedEtag = null` 为显式无条件 PUT 语义（仅限远端 404 自愈恢复等无基线场景）；
       - `测试本地赢自动上传携带基线 ETag 预检` / `测试 commitLocal 上传携带基线 ETag 预检`：
         FakeSyncProvider 新增 `lastExpectedEtag` 预检参数记录，断言本地赢自动上传与 commitLocal
         两条路径均真实携带基线 ETag 走乐观锁，而非无条件 PUT。
    2. **KdbxMergerV2Test（+10）**：
       - **挂载丢失自愈**：条目挂载组被删且无 previousParentGroup 移动史 → 自愈归属根组不静默丢弃
         （`测试条目挂载点丢失且无移动史时归属根组`）；分组父组被删、parentGroupId 悬空 → 自愈归属根组
         （`测试分组挂载点丢失自愈归属根组`）；双方互移形成 parentGroupId 互指环 → 环路打破且两组零丢失
         （`测试分组互指环路检测回退根组且无对象丢失`）；
       - **分组子树冲突**：同组不同属性修改自动合并保留双方变更（`测试双方修改同组不同属性自动合并`）；
         同属性（重命名）冲突按时间戳晚者胜（`测试双方重命名同组时间戳晚者胜`）；
       - **字段级仲裁边缘**：同字段冲突本地时间戳更晚采纳本地值且仍生成冲突清单；单侧字段删除生效
         且不产生冲突；双方新增同名自定义字段冲突时间戳仲裁；单侧新建条目与分组完整保留不静默丢弃；
         冲突条目历史三方并集按最后修改时间去重升序（5 例）。
  - **涉及文件**：
    - `sync/src/test/java/com/keepasskey/sync/SyncEngineTest.kt`
    - `sync/src/test/java/com/keepasskey/sync/KdbxMergerV2Test.kt`
  - **测试证据**：`./gradlew.bat test` 全绿：全仓 **647 例（app 229 / core 36 / crypto 61 / database 163 / sync 158），
    634 通过 / 0 失败 / 13 跳过**（跳过项与既有基线一致）；净变化 +13 例边缘分支回归锁。
