# KeePasskey 已整改问题与历史任务归档（Resolved Log）

> **文档定位**：本项目已完成修复的缺陷、已落地特性与已闭环技术债的**全量归档库**。
> **追溯作用**：记录每项任务的修复实现、代码证据、关联提交与测试验收，用于防回退与历史溯源。
> **维护规则**：当 [**docs/ACTIVE_ISSUES.md**](ACTIVE_ISSUES.md) 中的任务经整改并通过测试后，整条移入本文件对应章节。

---

## 批次索引

| 章节 | 批次 | 条目范围 |
|---|---|---|
| §1 | 已完成核心任务 | `TASK-01` ~ `TASK-53` |
| §2 | 历史全量代码审计（93 项代码审核 + 4 类专项审查 + ZT/P1/P2 逐项） | 2.1 ~ 2.22 |
| §3 | P3 批次（16 项：低危加固 / 特性接线 / 体验优化） | ISSUE-P3-01 ~ P3-16 |
| §4 | P3 残余批次（12 项：假开关整改 / 特性接线 / 文档治理） | ISSUE-P3-17 ~ P3-28 |
| §5 | P3-30 单条批次（子库条目只读投影接入库列表） | ISSUE-P3-30 |
| §6 | P3-29 批次 A（全仓超阈值债务：优先级 8 项 + 增量 2 项 + 1 项例外登记） | ISSUE-P3-29 |

> 本索引仅到**章节粒度**，因此不会随条目增删而过期；章节内的子条目按编号顺序排列。
> 各批次的**验收证据**（用例数 / 通过 / 失败 / 跳过）分别见 §2.22、§3.1、§4.1、§5.1。

---

## 1. 已完成核心任务清单

| TASK ID | 领域 | 任务主题 | 优先级 | 完成日期 | 核心实现与代码证据 / 说明 |
|:---:|:---:|---|:---:|:---:|---|
| **TASK-01** | 安全 | HMAC 防篡改回归锁 flaky 排查与定型 | **P0** | 2026-09-07 | 定位并修复终止块未校验即置 `terminated=true` 导致篡改文件 ~10% 概率静默解锁的漏洞；改为仅校验通过后置位并在 `verifyEndOfStream` 权威检查点 fail-closed；`testCorruptHmacBlock` 20 连跑零失败。 |
| **TASK-02** | 平台集成 | 凭据提供者服务实机端到端注册与调起（ISSUE-P1-01） | **P1** | 2026-09-09 | 根因：全部凭据条目 PendingIntent 误用 `FLAG_IMMUTABLE`，系统注入的 fillIn extras 被静默丢弃 → 链式解锁 / 密码保存 / 应用内注册全链路握手失败。新增 `CredentialPendingIntents.ENTRY_FLAGS`（`FLAG_MUTABLE｜FLAG_UPDATE_CURRENT`）统一替换 5 处创建点，附 4 例契约回归锁。详见 [§2.6](#26-凭据提供者端到端契约p1-01)。 |
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

> 来源：ISSUE-P2-14（Rust 秘密飞地 PoC）。整改依据：RustCrypto `argon2`（纯 Rust + `zeroize` RAII 确定性擦除，消除 C 手动 `malloc`/`kp_wipe`/`free` 面）；「零二进制信任根」哲学（源码全量入库 + 从源码交叉编译）。原分批计划与风险登记册 `plans/rust-enclave-poc.md` 已随文档体系重构删除；其中风险 R6（桌面单测无 `.so`，原生路径不被覆盖）的残余已转入 [**docs/ACTIVE_ISSUES.md**](ACTIVE_ISSUES.md) 的 ISSUE-P3-11。

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

---

### 2.16 原子写盘降级 fsync 与 `.bak` 生命周期闭环（P2-05 / P2-11）

> 来源：ISSUE-P2-05（P2-7 残余）、ISSUE-P2-11（ZT-16）。整改依据：工程规则「文件 IO 与原子写入」铁律——写入临时文件 → 同步落盘 → 原子重命名 → **目录项 fsync**；零信任「凭据轮换后旧材料必须失效」。

- **ISSUE-P2-05（原子写盘降级分支 fsync 补齐）**：已完成。
  - **缺陷 / 动机**：主路径已在关闭流前调用 `fos.fd.sync()`，但**父目录**未 fsync。POSIX crash-safety 要求 rename 后同步父目录：否则断电后新目录项可能未持久化，文件丢失或回退旧版本；降级替换（`renameTo` / `Files.copy`）与备份 copy 路径同样缺失。
  - **整改实现**：
    1. 新增 `AtomicFileWriter.syncDirectory()`（`FileChannel.open(dir, StandardOpenOption.READ).force(true)`），平台/文件系统不支持目录通道时（Windows 抛 `AccessDeniedException`）捕获降级为告警日志，绝不阻断写盘；
    2. 四条路径全覆盖：原子 move 成功后、备份 copy 后、降级 `renameTo` 成功后、降级 `Files.copy` 后。
  - **测试证据**：`AtomicFileWriterTest` 补 4 例——首次写抛异常不残留空目标与 `.tmp`、覆盖写失败原内容不变且清理 `.tmp`、关闭备份偏好不生成 `.bak`、以覆写 `renameTo` 恒 false 的 `File` 子类**确定性**进入 `Files.copy` 降级分支并断言替换成功/目标非空/`.tmp` 清理（不依赖平台 rename 语义）。
- **ISSUE-P2-11（`.bak` 永久保留、开关未接线、改密后旧口令可解密文）**：已完成。
  - **缺陷 / 动机**：`AtomicFileWriter` 无条件生成 `<name>.kdbx.bak` 且从无删除逻辑；`createBackupBeforeSave` 仅被持久化、无任何消费方；改主密码后 `.bak` 仍可被**旧口令**解开。
  - **整改实现**：
    1. `AtomicFileWriter.writeAtomic(target, createBackup = true, writer)` 增加可控入口（默认值保持既有行为，调用点全兼容）；关闭备份时**无备份兜底**，降级分支对已存在原文件拒绝无保护覆盖（宁可失败也不损坏数据，遵循原子写盘铁律）；
    2. `DatabaseSession.createBackupBeforeSave`（`@Volatile`，默认 true）为会话级偏好；三处落盘统一经 `writeAtomicByBackupPreference()`，关闭时既不出备份、亦清理历史遗留 `.bak`；
    3. `changeCredentials()` 成功后无条件删除活动文件的滚动备份（凭据轮换后旧密文快照失效）；
    4. 装配接线：`DatabaseModule.provideDatabaseSession` 注入 `ExtendedSettingsStore.load()` 的持久化值；`SettingsViewModel.setCreateBackupBeforeSave` 实时下发到唯一会话实例。`database` 模块未反向依赖 `app`（§3.1 单向依赖保持）。
  - **涉及文件**：
    - `database/src/main/java/com/keepasskey/database/session/AtomicFileWriter.kt`
    - `database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt`
    - `app/src/main/java/com/keepasskey/app/di/DatabaseModule.kt`
    - `app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt`
    - `database/src/test/java/com/keepasskey/database/session/AtomicFileWriterTest.kt`（+4）
    - `database/src/test/java/com/keepasskey/database/DatabaseSessionBackupPreferenceTest.kt`（新增 3）
  - **测试证据**：新增用例覆盖「关闭不生成 + 清历史遗留」「开启按既有行为生成」「改密后 `.bak` 删除且旧口令不得再解开目标文件、新口令可解锁」。
  - **如实记录的取舍（已回登待办）**：`syncDirectory` 在 Windows 上确定性降级为告警（目录 `FileChannel` 抛 `AccessDeniedException`），故宿主单测无法断言目录 fsync 真实生效 → 见 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) **ISSUE-P3-13**；正确性当前依赖 POSIX 语义与代码审查背书。

---

### 2.17 锁定不等于销毁：copy-on-write 敏感字段定点擦除（P2-06）

> 来源：ISSUE-P2-06（ZT-11）。整改依据：工程规则敏感数据铁律；「锁定即销毁」声明需与实现一致。

- **缺陷 / 动机**：所有写操作走 `data class.copy()` 产生新树，旧树节点仅变为不可达、其 `ProtectedString` 密文从未清零；`InMemoryCipher` 的 `encKey`/`eqKey` 为 object 级常量、进程生命周期常驻。
- **整改实现**：
  1. `KdbxEntry.clearOwnSensitiveData()`（只清自身 fields/customFields/attachments，**不递归**）；
  2. `KdbxGroup.clearSupersededSensitiveData(surviving)`：以 `Collections.newSetFromMap(IdentityHashMap())` 收集**存活树可达的敏感实例身份**（引用相等，而非 equals），再遍历旧树仅擦除未被存活树引用的实例——保证 copy-on-write 共享的未被修改字段（如 `withField` 只替换目标键、移动条目 `copy(parentGroupId=...)`）绝不被误擦；history 列表同样按身份处理；
  3. `DatabaseSession` 在 `saveEntry` / `saveGroup` / `updateDatabaseMeta` / `batchMoveEntries` 及 `save()` 的历史修剪分支替换前调用该 API（**替换树已就绪**的写入路径）。
- **集成阶段实测缺陷与修正（重要）**：初版在 `deleteEntry` / `deleteGroup` / `batchDeleteEntries` 也做了身份擦除，导致 app 层真实回归——回收站软删是「先 `deleteEntry`，再用与旧条目**共享同一 ProtectedString 实例**的 moved 副本重新 `saveEntry`」，此时 moved 尚未入树，身份集合把即将复用的存活字段判为下线并清零，条目成空壳、`save()` 序列化抛 `IllegalStateException`，回收站元数据无法落盘（`RealVaultRepositoryTest` 真实失败）。**修正原则：擦除只在替换树已就绪时安全；删除语义下不存在替换树，禁止身份擦除**（下线实例交由 GC 回收）。
- **InMemoryCipher 评估结论（如实记录的边界）**：**不可**按锁定边界轮换进程内驻留密钥——`SyncCoordinator` 跨锁定持有含 `ProtectedString` 的整树快照，轮换会让存活实例永久无法解密。已在 KDoc 写明结论并配「lock 只擦除会话树_树外持有的实例仍需进程级密钥」回归锁佐证，不以隐蔽方式制造数据破坏。
- **涉及文件**：
  - `core/src/main/java/com/keepasskey/core/model/KdbxEntry.kt` / `KdbxGroup.kt`
  - `core/src/main/java/com/keepasskey/core/security/InMemoryCipher.kt`（评估结论 KDoc）
  - `database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt`（4 处保留 + 3 处删除路径修正）
  - `core/src/test/java/com/keepasskey/core/model/KdbxSensitiveErasureTest.kt`（新增）
  - `database/src/test/java/com/keepasskey/database/DatabaseSessionSensitiveErasureTest.kt`（新增，含删除后再插入回归锁）
- **测试证据**：身份集合语义、只清自身不递归、共享实例存活、锁定边界与「删除后再以共享字段副本重新插入不得被误擦且可落盘重开」回归锁全部覆盖。

---

### 2.18 Autofill 信任边界与运行时完整性防护（P2-07 / P2-08 / P2-09）

> 来源：ISSUE-P2-07（ZT-12）、ISSUE-P2-08（ZT-13）、ISSUE-P2-09（ZT-14）。整改依据：Google Digital Asset Links 规范；NIST SP 800-207「设备健康状态作为访问决策输入」；OWASP MASVS-RESILIENCE / MASVS-PLATFORM-1。

- **ISSUE-P2-07（域归属无校验 / 保存侧无黑名单 / isBlocked 实为 fail-open）**：已完成。
  - 新增 `AutofillWebDomainPolicy` + `AutofillOriginResolver`：webDomain 参与匹配前必须通过归属裁决——受信浏览器包名白名单直放，非浏览器复用既有 `DigitalAssetLinksVerifier` 校验 `rpId ↔ webDomain` 声明，取不到调用方证书或 DAL 非 VERIFIED 一律返回 null（fail-closed，不下发该域候选；不提供 `skipDalVerification` 旁路以免重开伪造面）；
  - `onSaveRequest` 前置 `AutofillAccessPolicy.rejectReason` 闸门：命中黑名单或完整性风险即拒绝落库并向系统回调非敏感提示；
  - `AutofillBlocklistStore.isBlocked` 对非法包名改为 **fail-closed（return true）**，KDoc 与 `AutofillBlocklistStoreTest` 同步修正；
  - 保存被拒提示文案已资源化（`autofill_save_blocked` / `autofill_save_integrity_blocked`，中英双语）。
- **ISSUE-P2-08（无运行环境完整性 / 反调试 / 反篡改）**：已完成。
  - 新增 `RuntimeIntegrityPolicy`（纯函数分级矩阵，JVM 可测）、`RuntimeIntegrityDetector`（后台 IO 探测：`Debug.isDebuggerConnected`、`FLAG_DEBUGGABLE`、root/`su` 路径、Magisk 痕迹、`/proc/self/maps` 中 Frida/Xposed、安装来源）、`RuntimeIntegrityGate` 抽象与 `RuntimeIntegrityModule` `@Binds` 绑定；
  - 分级 fail-closed：COMPROMISED = 禁生物快速解锁 + 禁自动填充 + 风险提示；ELEVATED = 仅禁生物；UNDETERMINED = 保守双禁（不以「未检测到即安全」自证放行）；
  - 消费点：`BiometricAuthManager`（风险态显式失败回落主密码）、`KeePasskeyAutofillService`（填充与保存双闸门）；
  - UI 风险提示真实渲染：`RuntimeIntegrityDetector.report` → `SettingsViewModel` → `SettingsUiState.integrityReport` → `KeePasskeyApp` → `SecuritySettingsScreen` 的 `IntegrityRiskCard`（ELEVATED/COMPROMISED 才渲染），文案 `sec_integrity_risk_*`。
- **ISSUE-P2-09（FLAG_SECURE 可关闭 + 无遮挡触摸过滤）**：已完成。
  - `FlagSecurePolicy.shouldApplySecure` 改为「临时豁免」模型：锁定态无条件强制；解锁态用户关闭仍默认强制，仅在 UI 显式风险确认后由 `FlagSecureGuard.requestTemporaryExemption()` 授予 ≤5 分钟内存豁免，到期/锁库自动恢复；
  - `SecuritySettingsScreen` 关闭开关前弹出风险确认（`sec_flag_secure_risk_*`），取消则保持开启（fail-closed）；
  - 遮挡触摸过滤：`autofill_dataset_item.xml` 三视图 `filterTouchesWhenObscured="true"`；`FlagSecureGuard.applyObscuredTouchFilter` 与 `AutofillConfirmActivity` 使用 `window.decorView.filterTouchesWhenObscured = true`（**注意：`android.view.Window` 无此方法，经 `android-37.0/android.jar` javap 核验，必须落在 `View` 层**）；Compose 侧 `SecureTouchCompose.ApplyObscuredTouchFilter` 已接于 autofill 两屏。
  - **如实记录的遗留（已回登待办）**：主 App 其余敏感 Compose 屏未接遮挡过滤 → 见 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) **ISSUE-P3-12**；生物识别完整性提示未消费已资源化文案 → **ISSUE-P3-14**；`isBlocked` 改 fail-closed 带来的编辑页文案边界 → **ISSUE-P3-15**；非浏览器 webDomain 依赖联网 DAL，离线不下发候选（设计取舍，不登记）。
- **涉及文件**：`app/src/main/java/com/keepasskey/app/security/{RuntimeIntegrityPolicy,RuntimeIntegrityDetector,RuntimeIntegrityGate,RuntimeIntegrityModule,FlagSecurePolicy,ObscuredTouchPolicy,SecureTouchCompose}.kt`、`app/.../autofill/{AutofillWebDomainPolicy,AutofillOriginResolver,AutofillAccessPolicy}.kt`、`KeePasskeyAutofillService.kt`、`AutofillBlocklistStore.kt`、`FlagSecureGuard.kt`、`BiometricAuthManager.kt`、`SettingsUiState.kt`、`SettingsViewModel.kt`、`KeePasskeyApp.kt`、`SecuritySettingsScreen.kt`、`res/layout/autofill_dataset_item.xml`、`res/values{,-en}/strings.xml` 及对应测试。
- **测试证据**：`RuntimeIntegrityPolicyTest`(9)、`FlagSecurePolicyTest`(8)、`ObscuredTouchPolicyTest`(6)、`AutofillWebDomainPolicyTest`(6)、`AutofillAccessPolicyTest`(6)，`AutofillBlocklistStoreTest` / `SecurityTest` 同步修正，定向 `security.*` + `autofill.*` 全绿。

---

### 2.19 明文导出治理与自动锁定语义修正（P2-10 / P2-13）

> 来源：ISSUE-P2-10（ZT-15）、ISSUE-P2-13（ZT-18/ZT-19）。整改依据：零信任「数据出域需显式授权与可审计」；「与 UI 契约一致 + 时间驱动的会话终止」。

- **ISSUE-P2-10（明文 XML/附件导出沙箱外且无治理）**：已完成。
  - 明文导出强制二次确认：`DatabaseSettingsScreen` 的 XML 导出与 `EntryDetailScreen` 的附件导出均先弹确认；确认弹窗的取消/点外部关闭分支不触发导出；
  - **fail-closed**：`EntryDetailViewModel.exportAttachment` 的 `confirmed` 默认 false，未确认时不解析附件、不打开输出流、不写任何字节；
  - 默认加密导出：`exportKdbxTo` 走 `exportKdbxBytes → DatabaseSession.exportToBytes`，明文 XML 降级为需显式确认的高级选项；
  - 导出审计（不含内容）：`SettingsExportController` + `ExportAuditRecorder`/`ExportAuditSanitizer` 仅记录时间、导出类型、目标 URI 的 `scheme+authority+8 位摘要`，不含路径、文件名与任何明文。
- **ISSUE-P2-13（AutoLock「永不」实为立即锁定 + 后台无定时器）**：已完成。
  - 新增 `AutoLockTimeoutPolicy` 三档内核并与设置页取值严格对齐：`-1`=永不（`lockOnBackgroundResume` 直接放行、绝不启动定时器）、`0`=立即、`>0`=秒；
  - 后台延迟熔断：`AutoLockManager.onStop` 调度延迟任务到点即锁（不再等回前台判定）；`onStart` / `onUnlockSuccess` / `triggerLock` / 超时设置变更（`map+distinctUntilChanged`，仅后台期间重排）均先取消旧任务，任意时刻至多一个存活定时器；受控 `SupervisorJob + Main` scope，无裸 `GlobalScope`；触发时先置 `backgroundTimestamp = 0` 再锁定，避免重复熔断。
- **涉及文件**：`SettingsExportController.kt`、`DatabaseSettingsScreen.kt`、`EntryDetailViewModel.kt`、`EntryDetailScreen.kt`、`AutoLockSessionGuard.kt`、`AutoLockManager.kt`、`res/values{,-en}/strings.xml` 及对应测试。
- **测试证据**：`AutoLockTimeoutPolicyTest`（-1/0/30/负值）+ `AutoLockSessionGuardTest`（永不档 24h 不锁、立即档 1ms 即锁）；`ExportConfirmationPolicyTest`（未确认不导出、加密为默认、审计不含敏感内容）。

---

### 2.20 OTP 种子与详情路径字节化（P2-12）

> 来源：ISSUE-P2-12（ZT-17）。整改依据：工程规则敏感数据铁律（能用 Char/Byte 的地方绝不落到 String）。

- **已完成子项**：
  1. **解析层字节化**：`ParsedTotpConfig.secret` 由 `String` 改为 **Base32 文本字节（ASCII）**；新增 `TotpKeyUriParser.parse(ByteArray)` 全程字节语义（otpauth URI 与种子均不物化 String，仅 label/issuer/account 等非敏感描述转字符串），解析器只清自有中间量并返回全新副本；`OtpEngine` 增加 `Base32Decoder.decode(ByteArray)`；
  2. **消费侧清零责任链**：`VaultEntryMapper.parseTotpConfig` 经 `ProtectedString.readUtf8()` 读取并在 finally 清 `rawBytes`；`mapKdbxEntryToUi` 清 `parsedTotp.secret`；`RealVaultRepository.calculateEntryTotp` 清 `config.secret`；`computeTotpCode` 仅清 Base32 解码出的二进制 key；`getEntryTotpSecretChars` 返回 CharArray 借出给调用方；
  3. **GeneratorUiState 当前密码与 history**：容器已改 `ProtectedString`，淘汰项与 `onCleared` 显式清零；**部分完成**——生成引擎 `generateRandomPassword/generatePassphrase/generateMaskedPassword` 仍返回 `String`（生成边界），`GeneratorScreen` 渲染与剪贴板 `copySensitiveText` 边界仍会物化不可擦 String；
  4. **健康扫描字节化**：`HealthCheckEngine` 删除 `String(passChars)`，改为字符数组大小写不敏感比较。
- **如实保留的残余面（已闭环）**：`RealVaultRepository` **5 处** `readString()` 受仓库接口/UI String 模型限制未改、`TotpKeyUriParser.parse(String)` 兼容重载保留（**ISSUE-P2-15**）；生成引擎三函数仍返回 `String`（`DicewareWordList.kt:277/311/333`，**ISSUE-P2-16**）。
  两项残余面后续已独立整改并归档，见 **§2.21**。
- **涉及文件**：`core/.../otp/TotpKeyUriParser.kt`、`core/.../otp/OtpEngine.kt`、`app/.../data/repository/VaultEntryMapper.kt`、`RealVaultRepository.kt`、`app/.../ui/screens/generator/{GeneratorUiState,GeneratorViewModel,GeneratorScreen,DicewareWordList}.kt`、`database/.../audit/HealthCheckEngine.kt` 及对应测试。
- **测试证据**：新增 `Base32DecoderByteSemanticsTest`，`TotpKeyUriParserTest` / `VaultEntryMapperTotpTest` / `HealthCheckEngineTest` 按字节语义同步修正。

---

### 2.21 受保护值字节通道收口与密码生成器出边界 CharArray 化（P2-15 / P2-16）

> 来源：ISSUE-P2-15 / ISSUE-P2-16（P2-12 OTP 字节化整改中如实登记的残余面，见 §2.20）。
> 整改依据：工程规则敏感数据铁律（能用 Char/Byte 的地方绝不落到 String）；M1「投影层不物化密码明文」；`DicewareWordList.calculateEntropy(CharArray)` 既有先例。

- **ISSUE-P2-15（模型层受保护值经 `readString()` 退化为不可擦除 String）**：已完成。
  - `RealVaultRepository` 原有 **5 处** 直接 `readString()` 全部消除：新增私有兼容通道
    `readErasableString(ProtectedString?)` / `readErasableChars(ProtectedString?)`，一律经
    `readChars()` 独占副本中转并在 `finally` 中 `fill('0')`；
  - 仓库接口 `getEntryPassword` / `getEntryRevisionPassword` 标记 `@Deprecated`（附 `ReplaceWith`
    指向 CharArray 借用通道），生产消费方全部迁移至字节通道：`VaultListViewModel.copyPassword`、
    `EntryDetailViewModel.togglePasswordVisibility` / `prepareRevisionDiff` / `copyCustomField` / `copyPassword`；
  - `EntryRevisionSnapshot.totpSecret: String` → **`totpSecretChars: CharArray`**（仓库返回独占副本），
    回滚路径直接转交 `saveEntry` 的擦除契约，不再经 `toCharArray()` 中转；
  - `TotpKeyUriParser.parse(String)` 兼容重载收敛为 `internal`（仅同模块单测 friend 可见），
    生产路径只用 `parse(ByteArray)`；
  - 新增 `SensitiveCharSequence`（零拷贝只读 CharSequence 视图）与 `sensitiveTextSha256(CharSequence)`，
    受保护剪贴板新增 `copySensitiveChars` 直通 CharArray，且与 String 通道摘要一致
    （自动擦除的「当前内容是否仍为先前敏感值」比对不受通道切换影响）；
  - **如实保留的已知约束**：`EntryRevisionSnapshot.entry`（`UiVaultEntry` / `UiCustomField`）中受保护
    字段值仍为 String 投影，属跨模块契约改造（M1 投影层对齐的独立批次），已在 KDoc 注明为不可擦边界。
- **ISSUE-P2-16（生成引擎出边界返回 String）**：已完成。
  - `PasswordGenerationEngine.generateRandomPassword` / `generatePassphrase` / `generateMaskedPassword`
    返回值由 `String` 改为 **`CharArray` 独占副本**；删除 `calculateEntropy(String)` 重载；
  - 新增可覆盖中间缓冲 `buildChars`（`StringBuilder` → `getChars` → 覆盖清零再截断），
    消除 `joinToString` / `toString()` 的明文物化路径；
  - `GeneratorViewModel.generateNewPassword` 以同一副本完成熵计算与 `ProtectedString` 密封后
    `finally` 清零；`copyGeneratedPassword` 经 `ProtectedString.useChars` 直通 `copySensitiveChars`；
  - `GeneratorScreen` 渲染边界的 String 物化以 `remember(受控容器实例)` 收敛在最小作用域，
    并显式注释为不可擦的 Compose 显示边界。
- **涉及文件**：`core/.../otp/TotpKeyUriParser.kt`、`app/.../data/repository/{VaultRepository,RealVaultRepository}.kt`、
  `app/.../security/{ClipboardSecurityManager,SensitiveClipboardSupport}.kt`、
  `app/.../ui/screens/detail/EntryDetailViewModel.kt`、`app/.../ui/screens/vault/VaultListViewModel.kt`、
  `app/.../ui/screens/generator/{DicewareWordList,GeneratorViewModel,GeneratorScreen}.kt` 及对应测试。
- **测试证据**：新增 `PasswordGenerationEngineTest`（6 例：CharArray 出口、字符集/词数/掩码、熵值、
  密封后清零契约）与 `SensitiveCharSequenceTest`（4 例：零拷贝视图、两通道摘要一致、多字节 UTF-8）；
  `RealVaultRepositoryTest` 追加 2 例借用/清零契约（`getEntryPasswordChars` 独立副本、
  修订密码与 `totpSecretChars` 清零不影响库内原文）；`TotpKeyUriParserTest`（String 重载）
  与既有生成器/详情用例全绿。

---

### 2.22 P2 前九项（§2.16 ~ §2.20）批次验收证据

- **执行命令**：`.\.\gradlew.bat test`（全模块 `src/test`，单次串行执行；集成阶段以 `--project-cache-dir build/parent-verify` 隔离并发构建缓存）。
- **结果**：**全仓 725 例，712 通过 / 0 失败 / 13 跳过**——
  app 283 / core 48 / crypto 61 / database 175 / sync 158（跳过的 13 例与既有基线一致：12 例 `LiveSyncServersTest` 真实联调 + 1 例 Windows 无 POSIX 权限视图）。
  相对本批基线（647 例，634 通过 / 13 跳过）**净增 78 例**。
- **批次特有回归锁**：`database/src/test/.../DatabaseSessionSensitiveErasureTest.kt`
  的 `删除后再以共享字段副本重新插入不得被误擦且可落盘重开`——固化集成阶段实测出的
  「删除路径身份擦除误伤复用字段」缺陷（详见 §2.17），防止回退。

---

## 3. P3 批次整改归档（低危项 / 特性接线 / 体验优化）

> 来源：`docs/ACTIVE_ISSUES.md` §P3 全量 16 项。完成日期：2026-09-10。
> 提交基线：`7307f5f` → 本批次提交。**验收方式：全模块 `.\gradlew.bat test --rerun-tasks` 强制真实执行。**

### 3.1 P3 批次整体验收证据

| 模块 | 测试套件 | 用例 | 失败 | 跳过 |
|---|---:|---:|---:|---:|
| app | 63 | 416 | 0 | 0 |
| core | 8 | 58 | 0 | 0 |
| crypto | 9 | 61 | 0 | 0 |
| database | 28 | 207 | 0 | 0 |
| sync | 16 | 179 | 0 | 13 |
| **合计** | **124** | **921** | **0** | **13** |

**908 通过 / 0 失败 / 13 跳过**（基线 737 → 921，**+184 例，零退化**）。13 例跳过为 `LiveSyncServersTest`
真实联调（12 例，需 `-DliveSyncTest` + `tools/local-sync`）与 `SyncCacheTest` 的 Windows 无 POSIX 权限视图断言（1 例）。

补齐的验证缺口（本批次新增的**非跳过**运行时证据）：
- **crypto `skipped=0`**：原 4 例宿主侧原生 JNI 用例在无 `cargoHostBuild` 产物时 `Assume` 跳过，现真实执行；
- **database `skipped=0`**：原先的 Windows POSIX 权限断言跳过项已不在此模块（实际位于 `sync/.../SyncCacheTest.kt:121`，为独立既有限界）；
- **crypto instrumented**：新增 `crypto/src/androidTest/`，在 x86_64 模拟器（Android 16 / API 36）实测
  `connectedDebugAndroidTest` **7 例 0 失败、exit 0**，证据 `libkeepasskey_argon2.so=477976 bytes` 自 APK 内加载、
  `磁盘解包副本=false`、`NativeArgon2.available==true`、派生结果与 BC 冻结向量逐字节一致；
  性能（t=2/m=64MiB）p=2 native 132.1ms vs BC 657.7ms（**4.98×**）、p=4 native 91.6ms vs BC 771.7ms（**8.42×**），
  R1 闸门（原生 ≤ 2× BC）以极大余量通过。

### 3.2 已闭环条目逐项归档（ISSUE-P3-01 ~ P3-16）

| 条目 | 主题 | 优先级 | 裁决 | 核心实现与代码证据 |
|---|---|:---:|:---:|---|
| **P3-01** (TASK-55) | 生物识别解锁开关开启后第二次解锁不默认触发 | P3 | **达成（真机交互未验证）** | 根因：`unlockMode` 由「设置流」与「封印凭据流」两条独立异步源推导，先到者把终态定格为 `STANDARD`，故开关已开仍停在主密码界面。改为任一路径抵达后统一重算（`UnlockViewModel.refreshUnlockModeAndAutoPrompt`），并把自动唤起收敛为显式一次性意图 `onBiometricAutoPromptRequested()` + `IDLE→PENDING→CONSUMED` 状态机（`BiometricAutoPrompt`/`BiometricAutoPromptPolicy`，`CONSUMED` 不可逆），Screen 仅透传不判定——**取消后回落主密码不再自动重试，死循环结构性不可达**。新增 22 例。 |
| **P3-02** (TASK-49) | 自定义图标渲染/删除 + Notes/URL 字段引用展示侧接线 | P3 | **字段引用与循环引用达成；图标渲染/删除入口部分达成（渲染需真机）** | 图标投影层 `EntryIcon<T>`（`Default`/`Custom`/`Missing`，泛型载荷使 JVM 可测）+ 纯函数 `EntryIconProjection.of` 判定形态；`EntryIconPresenter` 在 `Dispatchers.Default` 解码，`IconBitmapCache` 有界 LRU（64 项、同 id 只解码一次、失败登记不重试）；Composable 只经纯绘制 `EntryIconContent`。删除链路：详情页溢出菜单 + 确认弹窗 → `CustomIconAdmin.deleteCustomIcon` **单次事务**内清理 Meta 图标池并把引用条目/分组回退默认、随后落盘，失败如实上浮。引用展示：引擎新增展示模式 `resolveForDisplay`，**受保护字段在任何递归深度输出 `••••••••` 掩码**（M1 投影层不物化明文），`MAX_DEPTH=10` 兜底循环/超深引用不崩溃，既有 `resolve` 取值语义与签名不变。新增 46 例（含 `FieldReferenceDisplayModeTest` 13 例）。 |
| **P3-03** (TASK-43) | 进阶偏好设置消费方接线（分批） | P3 | **43f 达成；43a/43b 部分达成；43c 接线未达成（诚实化达成）；43d/43e 未达成（如实登记）** | 先出**22 键现状审计矩阵**（逐键 grep 证据）。**查出「假开关」**：`debugLogEnabled` 有 10+ 处写日志、**0 处读偏好** → 接入 `DiagnosticLogGate` 闸门，且审计通道 `audit()` 独立不受偏好关闭（保住 P2-10 导出审计契约）。43a：`webdavChunkedUpload`/`webdavChunkSizeMb`（`SyncTransferOptions` 1..512MB 校验 + `WebDavUploadBody` 分块流式）、`checkRemoteChangesBeforeSave`（`commitLocalForce`/`overwriteRemoteWithoutPrecondition`）、`conflictResolution` **4 策略全部真实生效**（`PROMPT_USER` 经 `BothModifiedEntryCollector` 与自动合并行为可分）。43b：`inlineSuggestionsEnabled`（抽工厂 + 闸门）、`autofillCopyTotp`（下传条目 ID → 确认后经受保护剪贴板复制，500ms 硬超时不拖慢填充）。**未接线项一律在设置页补「（预留，暂未生效）」中英双语诚实标识**，拒绝留假开关。`sync` 新增 21 例（158→179）。 |
| **P3-04** (TASK-54) | 导入密钥与 KeyFile 管理 | P3 | **达成（SAF/真机交互未验证）** | 摸查确认 KeyFile 解析（`KdbxKeyFile`：XML v1/v2 + Hash 校验 + 32B 裸格式 + 64 hex + SHA-256 兜底）、复合密钥三分支（`KdbxFile.deriveKeys`）、解锁透传**均已存在**，未重复造轮子。补齐缺失：`KeyFileAccess` 契约 + `SafKeyFileAccess`（`use{}`/8KiB 分块/1MiB 上限/缓冲 `fill(0)`；**仅当偏好开启时**申请 `takePersistableUriPermission` 并回读校验，失败优雅降级 + 提示）；Uri/显示名经 DataStore 记忆、`init` 三重裁决恢复（偏好+记录+授权）、失效静默降级清记录；解锁成功按「实际使用的因子」记忆/清除，**跨会话绝不缓存密钥字节**；失败语义分型（带密钥文件 → `keyfile_or_password_mismatch`，不谎称主密码错——KDBX 复合密钥单次 HMAC 校验无法区分哪个因子错）。**查出第二个假开关**：`rememberKeyFileLocation` 此前无任何消费方，本次接成记忆功能总闸门。新增 27 例。 |
| **P3-05** (TASK-19) | zxing → CameraX + ML Kit 迁移评估 | P3 | **达成（纯评估，代码零改动）** | 决策：**维持 zxing 4.3.0，转条件触发式迁移（T1~T6）**。核查推翻了 ISSUE 的收益前提：「更小体积」方向相反——实测现状扫码 dex 仅 **349 KB**，而 ML Kit bundled 的 `libbarhopper_v3.so` 4 ABI 合计 **19.3 MB**，本项目通用 APK + `extractNativeLibs=false` 下 APK 14.27MB → 33~35MB；「Compose 原生集成」现状已满足；「对焦更流畅」无缺陷证据亦无真机可测。真实成本：ML Kit **会发送性能/使用指标**（官方数据披露页逐项列明），将把「纯离线零采集」改写为「含 Google SDK 遥测」，Play 数据安全申报必变。唯一真收益是脱离 deprecated Camera1（AAR 常量池实测 8/80 类引用 `android/hardware/Camera`）。另发现 ISSUE 遗漏成本：**自有 manifest 未声明 `CAMERA`，现由 zxing 库清单合并注入**，迁移须显式补声明。产出 `docs/扫码方案评估_ZXing与CameraXMLKit.md`（423 行，20 条来源 URL）。 |
| **P3-06** | UI 层冗余 import 清理 | P3 | **部分达成（保守保留差额）** | 纯 import 行删除：**20 个文件净删 60 行**，`git diff` 证据为「除文件头外所有变更行均以 `-import ` 开头」——**未触碰任何业务代码**，且未误删 Compose 委托所需的 `getValue`/`setValue`。原估约 80 处，差额为**保守保留**（KDoc `[...]` 链接引用、疑似被委托间接使用的导入）；继续激进删除的收益远低于误删导致 Compose 屏编译失败的风险。 |
| **P3-07** | 附件读取侧别名共享消除 | P3 | **达成** | `KdbxXmlGroupReader` 的 `BinaryNode` 出边界即防御性拷贝（`binariesPool[refIndex].data.copyOf()`），消除「同一池条目多引用者共享可变数组」与「调用方按 `Closeable` 契约 `clear()` 会清零池内数据、连带损坏其他引用者并使保存去重指纹取自已清零数据」两条缺陷。回归锁 `KdbxAttachmentAliasIsolationTest` 4 例（含端到端往返：清零一份后保存/读取另一条目仍字节精确、去重语义保持、越界返回空数组）。 |
| **P3-08** | 淘汰旧计划文件并统一单一真相源 | P3 | **达成（本批次开工前已满足）** | 核实：`docs/plans/` 目录、`STATUS.md`、`docs/HEALTH_CHECK_ROADMAP.md`、`docs/FINDINGS_TRACKER.md`、`plans/rust-enclave-poc.md` **均已不存在**；删除发生于 `7dba64d`（重构文档体系，删 `docs/plans/REPAIR_PLAN.md`）与 `d578df7`/`863d81c`（删 `bug-fix-plan.md`）。`AGENTS.md` 经全仓 grep **零悬空引用**。**如实登记条目滞后**：ISSUE-P3-08 与 P3-16 的正文前提（「文件尚未删除」）在 HEAD 上已不成立。 |
| **P3-09** (ZT-20) | 供应链与构建加固批次 | P3 | **五条达成、一条部分达成** | ①签名：关 v1、启 v3/v4。**受控实验**证明 `enableV2Signing=true` 已生效但产物省略 v2 块（v3 与 v2 同开且 minSdk ≥ 28 时省略，minSdk 36 无缺口）；产出 `.idsig`。②R8：移除 `-dontwarn **`（移除后 `missing_rules.txt` 不存在，无需逐类规则）、收窄 passkey/autofill 与 model/file/session、删冗余 Room/WorkManager 规则（保留 1 条 `WorkDatabase_Impl` 无参构造）。实效：dex 字符串表中源文件名由 **12 → 0**，dex −196,816 B（−1.85%）。③依赖：material3 **无可降 stable**（Maven 元数据 + AAR 字节码双证据：`MotionScheme.expressive()` 在 1.4.0 被 mangle 为 Kotlin `internal`，降级必编译失败）→ 受控保留 alpha + 两处管控注释；删孤儿 `argon2kt`。④CI：`failBuildOnCVSS` 11.0f → **7.0f**、`failOnError` → **true**、7 个 Action 全部 SHA 钉死；新增 `build.yml` 三 job（fast-gate / native-gate（NDK 28.2.13676358 + Rust 1.97.1 + cargo-ndk 4.1.2 + 4 ABI）/ rust-supply-chain）。⑤`.gitignore` 补 `*.p12`/`*.pfx`/`*.pem`/`*.key`（`git check-ignore` 实测 7 条命中）。⑥`cargo deny check` 入 CI，**`advisories` 子检查本机实测复现 ISSUE 现象 6**（`curl 28 Failed to connect to github.com:443`）→ 判定逻辑未能本环境验证，如实登记。 |
| **P3-10** (ZT-21) | 解析与计数器边界加固 | P3 | **达成** | ①解压上限 512 MiB → **128 MiB**（4× 收缩且不低于 `InnerHeader` 单字段 64 MiB 上限），补压缩炸弹拒绝/正常透传/区间契约三例。②`signCount`：`SIGN_COUNT_UNKNOWN=0`/`MAX_SIGN_COUNT=Int.MAX_VALUE-1` 命名常量，`parseSignCount`/`clampSignCount`/`nextSignCount` 三处收口，消除 `PasskeyAssertionActivity` 两处 `+1` 溢出；`PasskeyEntryCoordinator` 改 `updateDatabaseMeta` 受控事务 + 「库内现值+1」单调下界（并发两递增 = 2，无丢失更新）。③`setFeature` 失败改为**逐项告警、不 fail-fast**并论证：Expat 不支持 apache 特性，fail-fast 会导致用户打不开自己的库；改用 `startDTD` fail-closed 兜底，并修掉「首项失败致后三项从不尝试」的真实缺陷。④测试凭据：移除固定弱口令字面量，**单一真源**数据流 = 配置期求值一次 → `systemProperty` 下发 → 测试优先读属性 → 环境变量同名对齐（`WEBDAV_*`/`MINIO_ROOT_*`），日志只记来源不记值（避免客户端/服务端各自随机导致 12 例联调失配）。新增 database +4 / core +10 / app +6。 |
| **P3-11** (P2-14 遗留) | Rust Argon2 原生内核真机 instrumented 验证 | P3 | **验收 1/3 在 x86_64 模拟器达成（arm64 未达成）；验收 2 未达成（阻塞与设备无关）** | 建立 `crypto` 模块 `androidTest` 源集（`testInstrumentationRunner` + `androidx.test.*`）。在 x86_64 模拟器（Android 16/API 36）实测 `connectedDebugAndroidTest` **7 例 0 失败 exit 0**：`.so` 自 APK 内加载（477,976 B、磁盘无解包副本）、`available==true`、与 BC 冻结向量逐字节一致；性能 p=2 **4.98×**、p=4 **8.42×**，R1 闸门通过。**arm64 真机未达成**（SDK 无 arm64 system-image）；**验收 2 完全未达成**，阻塞与设备无关：语料未入库 + `crypto` 不依赖 `database`（无 `.kdbx` 读写能力，该用例只能落 `database`，而 `database` 亦无 androidTest 源集）+ `src/test/resources` 不进 androidTest APK（设备侧须放 `androidTest/assets/`）。**查出顺序依赖坑**：`System.loadLibrary` 只在 `NativeArgon2.available` 的 `by lazy` 内，而 `deriveKey` 是 `external fun` → 在 `available` 求值前直接调 `deriveKey` 必抛 `UnsatisfiedLinkError`（首轮 6/7 失败即此因，已用 `@BeforeClass` 前置消除）。已核验生产路径不受影响（唯一消费方 `Argon2KdfEngine.transform` 必然先求值 `available`，且 `derive` 已 catch `UnsatisfiedLinkError`）。归档 `docs/原生Argon2真机验证记录.md`。 |
| **P3-12** (P2-09 残余) | 主 App 敏感 Compose 屏遮挡触摸过滤 | P3 | **部分达成（真实缺口已补；真机交互未验证）** | **修正 ISSUE 两处前提**：①`MainActivity.kt:43` → `FlagSecureGuard.attach` → `:66` → `decorView.filterTouchesWhenObscured = true`，ComposeView 为其子孙，而 Android 触摸分发取「最近带该标志的祖先」→ **MainActivity 承载的全部屏早已被窗口级覆盖**，ISSUE 称「这些界面仍缺失防护」不成立；②该标志只作用于触摸分发路径，**不拦截无障碍 `ACTION_CLICK`/`performClick`**，ISSUE 把「无障碍注入点击」列为防护目标亦不准确。**真实缺口**：`BaseCredentialActivity` 四个子类（`PasskeyAssertionActivity`/`PasskeyCreateActivity`/`PasswordFillActivity`/`PasswordSaveActivity`）只设 `FLAG_SECURE` + `setHideOverlayWindows`，**无** decorView 过滤 → 已在 `CredentialVerificationLauncher`（覆盖三个手动确认窗口）与 `CredentialUnlockActivity` 的 Compose 根补接；另补 authenticator/conflict/database-picker/entry-edit 四屏。共接 8 窗口（6 新增 + 2 既有）。**强证据**：用工程同版 `kotlin-compiler-embeddable 2.4.10` 独立编译 + `JUnitCore` 运行 `ObscuredTouchWiringTest`（OK 2 tests），并做**反向对照**（把未接线的 `GeneratorScreen` 塞进清单 → 测试如期失败），证明断言非空转。 |
| **P3-13** (P2-05 残余) | Windows 宿主下父目录 fsync 无运行时验证 | P3 | **达成（POSIX 支路待 Linux runner）** | 走验收标准 2（依赖倒置）：新增窄接口 `fun interface DirectorySync { fun sync(dir): DirectorySyncOutcome }`（`SYNCED`/`DEGRADED`，契约「禁止抛异常，不支持即降级」）+ 生产实现 `PosixDirectorySync`；`AtomicFileWriter` **保留原三参重载**（委托 `DirectorySync.default`）并新增四参注入重载 → 既有调用点（`DatabaseSession.kt:575` 等）零改动、无跨模块/DI 牵连。四条 fsync 路径经代码实测确认为：① `.bak` copy 后 ② 主路径 `Files.move(ATOMIC_MOVE)` 后 ③ 降级 `renameTo` 成功后 ④ 降级 `Files.copy` 覆盖后（任务描述括注的「写入临时文件」本身不是 fsync 点，已在交接说明）；假实现以「恰好 1 次/2 次」计数断言触达，另有负向断言（拒绝无保护覆盖时钩子 0 次）。**变异探针**：临时注释掉钩子② → 4 例精确失败，还原后复绿 → 断言非空转。Windows 实测 `AccessDeniedException` + 降级告警，同用例仍断言写盘成功（保持降级不阻断语义）。新增 11 例。 |
| **P3-14** (P2-08 残余) | 生物识别完整性提示未使用已资源化文案 | P3 | **达成（真机渲染未验证）** | 逐一核查全 app **8 处 `errString` 消费点**，据实确认**无一处展示给用户**（2 处仅日志、2 处丢弃、3 处产生点、1 处字段定义）→ 采优先路线：`errString` 退化为内部诊断标识 `INTEGRITY_BLOCKED_DIAGNOSTIC`，消费侧按错误码映射 `R.string.sec_biometric_integrity_blocked`；硬编码常量 `INTEGRITY_BLOCKED_MESSAGE` 已删除（grep 0 命中）。新增 5 例。 |
| **P3-15** (P2-07 残余) | 编辑页屏蔽非法绑定包名后的提示文案边界 | P3 | **达成** | 把「包名 → 屏蔽状态」由布尔升维为**三态** `AutofillBlockState`（`Blocked`/`NotBlocked`/`UnidentifiablePackage`）；`isBlocked` 保持公开签名与 fail-closed 语义**一字未改**（内部对三态穷尽 `when` 映射，`UnidentifiablePackage → true`），填充侧 5 个调用方零感知。`toggleAutofillBlockForApp` 在不可识别包名时**不执行任何写操作**、只发 `UiMessage(R.string.autofill_block_unidentifiable_package)`，不再产出「已屏蔽/已恢复」语义。回归锁对 8 个非法包名**同时**断言 `resolveBlockState == UnidentifiablePackage` 与 `isBlocked == true`。 |
| **P3-16** | AGENTS.md/RESOLVED_LOG.md 中已下架文件的悬空引用 | P3 | **达成** | 修正 2 处真实悬空引用：`docs/RESOLVED_LOG.md` §2.11 的死链 `../plans/rust-enclave-poc.md` → 改写为「已随文档体系重构删除 + 残余去向 ISSUE-P3-11」；`docs/ARCHITECTURE.md` §3-6 指向已下架 README 路线图的悬空指引 → 改指 `ACTIVE_ISSUES.md`。`AGENTS.md` 经核查**本就不含**此类引用（ISSUE 原文「§4 仍出现 `STATUS.md` 引用」在 HEAD 上不成立，属条目建立时的滞后描述）。 |

**新增生产代码文件（24 个）**：`app/.../ui/model/{EntryIcon,EntryIconPresenter,IconBitmapCache,EntryReferenceDisplayResolver,EntryDisplayPresenter}.kt`、
`app/.../ui/components/EntryIconContent.kt`、`app/.../ui/screens/vault/VaultEntryCardLayouts.kt`、`app/.../ui/screens/detail/EntryDetailTopBar.kt`、
`app/.../ui/screens/unlock/{BiometricAutoPrompt,BiometricFailureMessagePolicy,UnlockModePolicy,KeyFileAccess,SafKeyFileAccess,KeyFileAccessModule}.kt`、
`app/.../data/logger/DiagnosticLogGate.kt`、`app/.../data/repository/AutofillBlockState.kt`、`app/.../autofill/{AutofillInlinePresentationFactory,AutofillTotpCopyPolicy}.kt`、
`app/.../sync/ConflictStrategyMapping.kt`、`sync/.../network/SyncTransferOptions.kt`、`sync/.../webdav/WebDavUploadBody.kt`、`sync/.../merge/ConflictStrategy.kt`、
`database/.../session/{DirectorySync,PosixDirectorySync}.kt`。

### 3.3 本批次登记的过程缺陷与事实修正

> 本节为**如实留痕**：以下问题均在整改过程中真实发生并被发现/修正，不美化、不隐去。

**A. 生产代码缺陷（由本批次自身的验证门禁查出）**
1. `PasskeyEntryCoordinator.kt` 新增 `KdbxGroup` 使用但**漏 import** → 导致 **app 模块整体编译失败**，阻塞多个并行组验证。由主控定位（`Unresolved reference 'KdbxGroup'/'entries'/'subgroups'`）并补齐 `import com.keepasskey.core.model.KdbxGroup`。
2. `sync/build.gradle.kts` 用 `java.util.UUID` 全限定名 → Gradle **Kotlin DSL 脚本隐式导入集与项目源码不同**，报 `Unresolved reference 'util'`，**令全项目所有 Gradle 构建失败**。修法：脚本顶部 `import java.util.UUID`。
3. `KeyFileAccessModule.kt` 的 KDoc 正文写入 `` `app/.../di/**` `` → **Kotlin 块注释可嵌套**，`/**` 开启嵌套注释致外层 KDoc 直到 EOF 未闭合（`Syntax error: Unclosed comment`，报在文件末行）。修法：改写为不含 `/*` 的表述。
4. `BiometricAutoPrompt` 被声明为 `internal` 却经公开 `UnlockUiState` 暴露 → `'public' function exposes its 'internal' parameter type`。修法：改为 `public`（经公开 API 暴露者本就不能 internal）。
5. `KdbxAttachmentAliasIsolationTest` 首版 **2 例失败**，根因是**测试自身共享可变状态**：`poolData`/`shared` 为类字段，而 `KdbxAttachment.clear()` 是 `data.fill(0)` **原地清零**，测试把同一数组既交给池又用作期望值 → 自清零后断言「池完好」必失败。**结论：生产实现（`copyOf()`）正确，测试有缺陷**——一条针对「别名」的回归测试被别名本身击败。修法：期望值独立 `copyOf()`。
6. `UnlockViewModelBiometricAutoPromptTest` 1 例失败（expected `IDLE`, was `PENDING`），根因是**测试前提写错**：假定 `FakeSettingsRepository()` 默认 `biometricEnabled=false`，实际 `UserSettings.biometricEnabled` 出厂默认 **true**。修法：新增显式 `disabledSettings()` 并补前置断言，**未改期望值**（产品语义「开关关闭 → 不唤起」与 ISSUE 原文一致）。
7. `SyncCoordinator` 强制冲突策略分支曾出现「结果为非 null 但未 return 而落入后续分支」的控制流缺陷，由该组交付前自查修正（改为整体 `return@withLock applyForced(...) ?: handleConflictMerge(...)`）。
8. `WebDavUploadBodyTest` 首版 6 例因测试自身用 8 字节分块、被新加「≥1 MiB」构造期闸门拒绝 → 反向证明合法区间校验真实生效；已改用 `MIN_CHUNK_SIZE_BYTES`。
9. `KdbxXmlParser` 原 `setFeature` 循环在首项失败时**后续三项从不尝试**（真实缺陷，非仅日志缺失），已随 P3-10 子项 3 修正。

**B. 编排过程缺陷（主控自身，如实登记）**
10. **并行构建耗尽内存**：9 组并行 + 多 Gradle 会话使本机 28 GB 内存仅剩 1 GB、15 个 JVM 占 12 GB，**多名执行者被系统 OOM 终止**（属编排失误，非任务设计失败）。处置：`gradlew --stop` 释放 10 GB；`gradle.properties` 增加 `org.gradle.workers.max=2` 与 `kotlin.daemon.jvmargs=-Xmx1536m`；全体改为串行构建。
11. **并发构建破坏产物**：多个 Gradle 进程写同一 build 目录导致 `java.io.EOFException` / `Kryo Buffer underflow`（读回被截断的 `results-generic.bin`）、`NoSuchFileException: in-progress-results-generic.bin`、`app/build/generated/ksp/.../classes` 被并发删除（`StructureTransformAction` 失败）、`Could not close incremental caches`。**排除实验**：清空结果目录重跑仍失败、测试 JVM 堆提至 3g 仍失败 → 确证与测试缺陷、内存均无关。处置：引入文件锁包装器 `build/gw.ps1`（等待其它 Gradle 工作进程静默后再执行；**该脚本为会话临时协调工具，未入库**），最终门禁以 `--rerun-tasks` 强制真实执行通过。
12. **条目前提滞后**：ISSUE-P3-08 / P3-16 正文声称的「文件尚未删除」「`AGENTS.md` 仍引用 `STATUS.md`」在 HEAD 上均不成立（删除早在 `7dba64d` 完成）。已如实标注，避免后人误以为仍有文件待删。
13. **主控误判一次**：曾据会话初值 `adb devices` 为空而告知 P3-11 组「本环境无设备」，该组以 SDK 侧实测（`emulator.exe` + `android-36.1` x86_64 镜像 + AVD `Pixel_10`）反驳并启动模拟器，取得真实 instrumented 绿证。**主控已撤回误判**——本条登记为「不要以单一探测代替环境结论」。

### 3.4 本批次新登记的遗留问题

> 该批次未完全达标的 10 项残余面（ISSUE-P3-17 ~ P3-26）曾在此逐条登记。
> **截至 2026-09-10 已全部闭环或就地更新**：闭环裁决与代码证据见 **§4.2**，仍有残余者见 **§4.3**，
> 未达成项见 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md)。**原 10 行表格已删除**——它与 §4 重复且已过期。

---

## 4. P3 残余批次整改归档（ISSUE-P3-17 ~ P3-28）

> 来源：`docs/ACTIVE_ISSUES.md` §P3 全量 **12 项**。完成日期：2026-09-10。
> 提交基线：`9c2a806` → 本批次提交。**验收方式：全模块 `.\gradlew.bat test --rerun-tasks` 强制真实执行。**
> 编排方式：**并行多代理团队**（9 名并行工作流 + 1 名拆分工作流 + 编排者统一集成与串行构建）。
> 结果：**8 项完整闭环并归档**；**4 项（P3-20 / P3-23 / P3-24 / P3-25）部分达标**，残余面就地更新后留在 `ACTIVE_ISSUES.md`。

### 4.1 本批次整体验收证据

| 模块 | 用例 | 失败 | 跳过 |
|---|---:|---:|---:|
| app | 667 | 0 | 0 |
| core | 58 | 0 | 0 |
| crypto | 61 | 0 | 0 |
| database | 224 | 0 | 0 |
| sync | 179 | 0 | 13 |
| **合计** | **1189** | **0** | **13** |

**1176 通过 / 0 失败 / 13 跳过**（基线 921 → **1189，净增 +268 例，零退化**）。13 例跳过仍为
`LiveSyncServersTest` 真实联调（12 例，需 `-DliveSyncTest` + `tools/local-sync`）与
`SyncCacheTest` 的 Windows 无 POSIX 权限视图断言（1 例），均与本批次无关。

命令：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**；
另 10 个编译任务（app/database/sync/core/crypto 的 `compileDebugKotlin` + `compileDebugUnitTestKotlin`）
在 `--continue` 下全部通过。**`--rerun-tasks` 强制真实执行**；`--max-workers=1` 贯彻「单会话内勿并发跑 Gradle」。

### 4.2 已闭环条目逐项归档（ISSUE-P3-17 / 18 / 19 / 20 / 21 / 22 / 25 / 26 / 27 / 28）

| 条目 | 主题 | 裁决 | 核心实现与代码证据 |
|---|---|:---:|---|
| **P3-17** | 43c UI 显示偏好接线（7 键） | **达成（真机渲染未验证）** | 7 键全部接入真实消费点：`maskPasswordsDefault`/`maskTotpDefault` → `FieldMaskPolicy.initialMaskState(defaultMasked, override) = override ?: defaultMasked`（**「默认值」非「强制覆盖」**，用户显式展开/收起后偏好刷新不覆盖，有正反单测钉住）→ `EntryDetailViewModel` → `EntryDetailComponents`；`showGroupInEntry` → 详情页分组路径行；`listDensity` → `ListDensitySpec`/`ListDensityPresenter` 驱动三种行版式的行高/内边距/字号；`autoActivateSearchOnOpen` → 一次性意图 + `FocusRequester` + 输入法；`showGroupInSearchResult` → `GroupPathPresenter` 仅「搜索中且开启」时装配路径；`showKillAppOption` → `AppTerminationPolicy` + 溢出菜单「彻底退出应用」→ `finishAffinity()` + `exitProcess(0)`。**设置页 7 处「（预留，暂未生效）」标识全部移除**。新增 55 例单测（7 个测试类）。**过程缺陷**：`maskPasswordsDefault=false` 时原实现「不遮掩但无明文」（空字段），补按需解密——边界未放宽（单条 + 屏幕作用域 + 离开即清零；生产默认 `true` 时**零解密**），有正反两例断言。 |
| **P3-18** | 通知基础设施与 2 个通知类偏好接线 | **达成（设备侧未验证）** | 新建 `notification/`（通道规格 / 纯决策闸门 / 权限流程 / 解锁常驻 / 验证码发布，5 文件 + DI + 4 测试文件 36 例）。`AndroidManifest.xml` 声明 `POST_NOTIFICATIONS`；`MainApplication` 冷启动**先建通道再 notify**（Android 8+ 向不存在通道发送会被静默丢弃）。权限三重闸门（已授权 ∨ 已询问过（跨冷启动持久化）∨ 需解释 → 不请求）且**先落「已询问」标志再弹窗**，杜绝反复弹窗；`SecurityException` 一律静默降级。`showUnlockedNotification` 经观察 `DatabaseSession.state`（**`OPENED` 与 `DIRTY` 均视为已解锁**——只认 OPENED 会让任何一次编辑令通知错误消失）双闸门控制常驻通知；`autofillShowTotpNotification` 在自动填充确认落点门控验证码通知。**验收标准 4（零敏感明文）在 API 层面即成立**：`publish(code, periodSeconds, nowMillis)` 形参中根本没有条目标识/用户名/密码/种子字段，文案全为固定 `R.string`，两条通知均 `VISIBILITY_SECRET`。**自查发现并修复自身缺陷**：进程在解锁态被杀后重启 `posted=false` 会致旧通知永不撤销（显示「已解锁」而实际已锁定）→ `start()` 先无条件 `cancel()` 一次。**诚实化修正**：`theme_unlocked_notif_sub` 原承诺「快捷锁定入口」、`autofill_totp_notif_sub` 原承诺「通知栏快捷复制」，而本轮通知**不含任何 action 按钮** → 两处文案改为与实现一致（反向的不诚实同样属违规）。设置页 2 处标识移除。 |
| **P3-19** | 明文导入框架与 4 源解析器 | **达成（真机交互未验证）** | 新建 `data/importer/`（21 文件：契约 / 上限 / 警告编码 / 异常分型 / 失败归类 / 结果模型 / 严格 UTF-8 解码 / 敏感文本缓冲 / 解析闸门 / XXE 加固 SAX 读取器 / KeePass XML 处理器 / 4 源解析器 / CSV 记录读取器 / 分组路径解析 / 落库编排 / 注册表 / Hilt 多绑定）+ `ui/screens/importer/`（状态 / 控制器 / 报告对话框）。**「假回执」消除**：对话框删除 `dbset_import_reserved_note`，改为「选源 → SAF 打开文件 → 控制器（读字节 → 解析 → 落库 → 出报告）」，`ImportSourceDialog` 由传**本地化字符串**改为传 **`ImportSource` 枚举**（杜绝「改文案即静默失配」）。**解析器单测 83 例**（XML 21 / CSV 19 / Bitwarden 20 / 1PUX 23）。fail-closed：XXE（`startDTD` 拒 DOCTYPE + `resolveEntity` 拒外部实体 + 4 项特征关闭，**双保险**）、Zip Slip（`..`/绝对路径/盘符/NUL → 整包拒绝）、解压炸弹（归档体积/条目数/单条目/解压总量四道闸门）、条目数 10 000 / 文件 64 MiB / 嵌套深度 64、非法 UTF-8 与 UTF-16·32 BOM、JSON 损坏（消息只带字符偏移，不含输入片段）。敏感数据：XML 走 SAX 非 DOM，密码在 `characters(char[],start,len)` **第一现场**落 `SensitiveTextBuffer`；CSV 交出独占 `CharArray`；失败路径 `ImportedEntry.clear()` 立即清零；日志只记来源 id + 计数 + 异常**类名**。**框架级致命缺陷见 §4.4-A1**。 |
| **P3-21** | 建库侧「生成附属密钥文件」假开关 | **达成（真机交互未验证）** | 消除安全语义欺骗：`DatabaseSession.create` 新增密钥文件因子形参，`RealVaultRepository.createDatabase` 真实下传，「生成附属密钥文件」产出的 `.kdbx` 确实以「主密码 + 密钥文件」复合密钥加密；`SELECT_EXISTING` 选中的密钥文件字节**真实参与**复合密钥（不再丢弃）；新增一次性交付提示（丢失即无法解锁，中英双语 8 条资源）。KeyFile 解析**复用既有唯一实现** `KdbxKeyFile`（未在 app 层重写）；新增生成器 `KdbxKeyFileGenerator`（`SecureRandom`，与解析实现 round-trip 自洽）。单测：建库后（密码 + 密钥文件）解锁成功、**（仅密码）解锁失败**。 |
| **P3-22** | 分组自定义图标渲染 | **达成（真机渲染未验证）** | 链路摸清后补齐**两处真实缺口**（非条目原文所述「数据源缺失」——`core/.../KdbxGroup.kt:12` 早有 `customIconId`）：① UI 投影 `VaultGroup` 增 `customIconId: String? = null`（带默认值，`RECYCLE_BIN_GROUP` 等既有构造点不受影响）；② 投影构造点 `RealVaultRepository.getGroups()` 透传 `kdbxGroup.customIconId?.toHexString()`。渲染：`EntryIconPresenter.presentGroups()` 与条目侧**共用同一实例、同一 `IconBitmapCache`、同一失败登记表**（硬断言「同一图标只解码一次」）；判定为**唯一实现** `EntryIconProjection.of`（分组与条目共用，单测断言两侧一致）。池中缺失 → `EntryIcon.Missing` → 缺图占位，**不谎报**为标准图标。新增 9 例单测。 |
| **P3-26** | `deleteBackup` 删除 `.bak` 后未做目录 fsync | **达成** | `AtomicFileWriter.deleteBackup` 新增可注入 `DirectorySync` 形参（**保留默认值**，故 `DatabaseSession` 单参调用点源码兼容零改动），unlink 后经父目录 fsync（钩子⑤，类 KDoc「四条路径」→「五条」）。复用既有假实现做**计数断言**（恰好 1 次且落在父目录；无目录项变更不得触达；删除失败不抛且不触达；`DEGRADED` 与「实现违约抛异常」均不阻断删除）。新增 5 例（另将 5 例与共享替身搬迁到 `AtomicFileWriterBackupDeletionTest.kt` / `DirectorySyncTestDoubles.kt` 以守住 ≤400 行阈值，**既有断言逐字未变**）。 |
| **P3-27** | 解压上限不自洽 与 并发签名计数器假说 | **达成（并修正条目错误前提）** | **前提修正**：条目原文「`InnerHeader` 单字段上限 256 MiB」系**误读**——单字段实为 `MAX_INNER_FIELD_BYTES = 64 MiB`，256 MiB 是 `MAX_BINARY_POOL_TOTAL_BYTES`（二进制池**累计**上限）。故「单字段 ≤ 整包」本就自洽。**真实问题**是池累计上限 256 MiB > 整包 128 MiB → **永不生效的死守卫**（内层头部经 `guardPayloadSize` 读取，必在整包约束内）。整改：收敛为**单一真源**（`KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES` = 128 MiB；单字段 = 其 1/2；池累计 = `min(设计值 256 MiB, 整包)` = 128 MiB）+ 伴生对象初始化期 `require` 不变量（「单字段 ≤ 池累计 ≤ 整包」，常量漂移即 fail-fast）。**签名计数器**：协调器内部**证伪**（`updateDatabaseMeta` 读-改-写同处单一 `mutex.withLock` 临界区，32 路并发回归锁断言回传值互不相同且严格递增）；**调用方证实存在真实重复**——`PasskeyAssertionActivity` 原以**锁外快照自算** signCount 写入 `AuthenticatorData`，且先 `setResult(RESULT_OK)` 再落盘（进程中断致 RP 已收到 N+1 而库内仍 N → 跨时间重复）。新增原子 API `incrementPasskeySignCount`（回传**实际落库值**）并完成调用方接线：**先原子递增取唯一值 → 用它写入 AuthenticatorData → 再 setResult**。新增 4 例（含「自算值去重后 = 1（重复）vs 协调器回传去重后 = 8（唯一）」的并列锁定）。 |
| **P3-28** | 待办条目应附「核实时间点」 | **达成** | `docs/ACTIVE_ISSUES.md` 新增「**条目维护规则**」章节（3 条：新增条目须附核实时间点与核实方式；开工前复核前提；行号仅为快照），并写入立规缘由（P3-08/P3-16 的前提滞后事故留痕）；`AGENTS.md` §3 认领步骤同步加入「**前提复核**」强制条款与「新条目须附核实时间点」要求。**并已对全部 12 条做一次前提复核**，产出「前提复核记录」表（核实时间点 2026-09-10 + 逐条核实方式 + 结论），就地修正 **2 条失准前提**（P3-27 见上；P3-22 的链路缺口位置），并更正本节标题计数（原写「10 项」，实际 12 项）。 |
| **P3-20** | 子库挂载 —— **UI 接线** | **达成（核心层由上一批次完成，本条完成全部 UI 接线；真机 SAF 未验证）** | 三个缺口全部闭合：① **去硬编码**——`SettingsViewModel` 新增 `childDatabaseCountFlow = childDatabaseSessionManager?.mountedCount ?: MutableStateFlow(0)`（**控制器缺失回落 0，不谎报**），并入既有 `combine(...)`，承载硬编码的私有字段**整体删除**，全仓再无 `childDatabasesCount = 0` 生产者；② **假提示消除**——`ChildDatabaseDialog` 成为真实入口（别名 1–64 + 主密码 `CharArray`（提交后立即擦除 + 离组清零）+ 子库 SAF + 可选密钥文件 + 已挂载列表），`UiMessage(dbset_child_db_not_supported)` 与 `operationFeedback` 局部态整块删除；**真实调用**核心层 `mount`/`open`/`unmount`（`SettingsViewModel.kt:197/230/248`），`content://` 先经 `KeyFileAccess.persistReadPermission`（**复用 `SafKeyFileAccess`，未重写授权逻辑**）→ 密钥文件读取失败**中止挂载并如实反馈**（绝不静默退化为「仅主密码」）；③ **诚实标识随能力上线退场**——`dbset_child_db_reserved_note` 渲染删除，替换为凭据安全语义说明（「凭据仅存内存、锁库后需重新输入」）。**6 种状态 + 12 种失败分型穷尽映射**（`Opening` 刻意用**无文案**进度指示器表达，不伪造成「未解锁」），失败分型经 `ChildDatabaseFailureReason.of` 穿透 cause 链。**「已挂载」与「已解锁」严格分离**：锁库后计数仍计入但行状态如实回落「未解锁」并给出真实解锁入口（有专测守护）。**新增 26 例单测**（`ChildDatabaseStatusTextTest` 15 + `ChildDatabaseSettingsWiringTest` 11，后者用生产写入管线生成的真实 KDBX 语料做真实解密）。**执行者如实登记的过度声明已就地修正**：原 `dbset_child_db_dialog_desc`「将以只读分组形式出现在当前库中」与实现不符（`projectedEntries` 生产消费方为零）→ 文案改写为「其条目**暂未**合并进当前库列表」，并把该功能缺口登记为 **ISSUE-P3-30**；同时删除 2 条随能力上线而失真的文案。 |
| **P3-25** | 巨型类拆分 | **达成（本条所列 3 个文件全部降至阈值内）** | ① `app/.../sync/SyncCoordinator.kt` **965 → 254 行**（按「周期编排 / 冲突决策 / Provider 解析 / 缓存变更检测 / DB 编解码 / 会话状态 / 偏好 / 输出模型 / 日志标签」拆为 10 个新类，均 ≤400）；② `database/.../xml/KdbxXmlGroupReader.kt` **407 → 218 行**（拆出 AutoType / Binary / String / Times 四个节点文件，均 ≤400）；③ `app/.../ui/screens/unlock/UnlockViewModel.kt` **979 → 396 行**（拆出 `BiometricUnlockCoordinator` 359 / `KeyFileSessionCoordinator` 249 / `BiometricEnrollmentCoordinator` 187，均 ≤400）。**三项独立回归验证**：⑴ 全仓 12 处 `debugLog` 诊断点逐一核对**全部无损迁移**（`SyncCycleRunner` 确无日志需求）；⑵ P3-07 的附件别名隔离语义完整保留（`binariesPool[refIndex].data.copyOf()` 仍在新文件 `KdbxXmlBinaryNode.kt:50`）；⑶ **`UnlockViewModel` 位于解锁安全关键路径**，故逐条对照验证：公开 API **14/14 完全保持**（签名与可见性一字未改、且**零新增对外接口**，证明是纯拆分）、`keyFileData` 原 **5 处清零点**全部落地（`wipe()` 在「解锁成功」与 `onCleared()` **两个调用点均存在**——该收敛把两处独立清零点变成一个函数 + 两个调用点，**漏任一处即为敏感数据泄漏**，已专门核验）、`passwordChars` **3/3 处 `fill('0')` 保持**。**门禁**：拆分后 `test` → **1163 例 / 1150 通过 / 0 失败 / 13 跳过**，其中拆分前为 **1159 例**，
拆分后 **+4 例**（新增 `KeyFileSessionCoordinatorTest` 4 例，锁定搬迁后的密钥文件清零契约：独立副本 / 取消 /
读取失败 / 解锁成功+销毁收尾）——即 **零丢失且用例数净增**，满足「用例数不减」的硬要求；`assembleDebug` 通过。
另：拆分后 `BiometricAuthManager.kt` 与 `KeystoreManager.kt` 中 2 处指向 `UnlockViewModel` 的**陈旧 KDoc 引用**
已由编排者就地更正（改指新协作者）。 |

### 4.3 部分达标条目（本环境物理不可达）

> 本批次 **2 项部分达标**，且两者都是**验证类**条目——缺的是**外部硬件/服务**，不是可写的代码。
> **完整背景、已完成部分与仍未达成项以 [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) 对应条目为单一真相源**；
> 此处仅记录「本批次实际推进了什么」这一句结论，避免与之重复。

| 条目 | 本批次实际推进 | 为何仍不可达 |
|---|---|---|
| **P3-23** arm64 与真实语料 | `database` 模块**首次建立 androidTest 源集**与依赖接线；设备侧端到端解锁用例（**fail-closed**：语料缺失显式跳过、伴生元数据非法硬失败）；语料逐步生成清单写入 `crypto/src/test/resources/argon2-interop/README.md` | 需 **arm64 真机或 arm64 镜像**（本机已安装 system-image 仅 x86_64、`adb devices` 为空）与**人工 GUI 生成**的 KeePass / KeePassXC `.kdbx` 语料 |
| **P3-24** CI 首跑校准 | **静态校准并修正 3 处「首次必红」缺陷**（`platforms;android-37` 远端不存在 → `android-37.0`；apksigner 需 `--v4-signature-file` 才能验 v4；v2 块被 AGP 省略故原断言不可能成立）；7 个 Action SHA 逐一核实；Rust 1.97.1 确认真实已发布；material3 1.5.0 stable 核实不存在；`cargo deny check` 本机实跑通过。留痕 `docs/ci-静态校准记录.md` | 需**真实 GitHub runner** 与 NVD / rustsec 数据通道 |

### 4.4 本批次登记的过程缺陷与事实修正

> 本节为**如实留痕**：以下问题均在本批次真实发生并被发现/修正，不美化、不隐去。

**A. 生产代码缺陷（由本批次自身的验证门禁与队员互查查出）**

1. **⚠️ 全框架致命缺陷 `ImportTextDecoder`（影响全部 4 个数据源）**：`decode()` 写作
   `.decode(...).throwException()` / `.flush(...).throwException()`。`CoderResult.throwException()`
   对 **UNDERFLOW**（三段式解码的**正常收尾**状态）抛出的是 `BufferUnderflowException`——一个
   `RuntimeException`，**不是** `CharacterCodingException`，故 `catch (_: CharacterCodingException)` 根本接不住
   → **任何输入（含纯 ASCII 合法文本）都会抛异常**，四个数据源全部解析失败，用户侧表现为「导入永远报格式非法」。
   由解析器子批在离线直跑中复现定位，编排者落地修复（显式判定 `isError || isOverflow`）。
   **该缺陷一度导致 25 例单测全红**，也解释了为何两个 JSON 源「全部用例同时失败」。
2. **包名 `ui/screens/import` 令 KSP 整体失败**：`import` 是 **Java 关键字**，Kotlin 自身容忍，
   但 KSP（JVM 侧）直接以 `The name 'import' cannot be used as a package name because it is a Java keyword`
   拒绝整个注解处理阶段（比 Kotlin 编译更早失败）。**根因是编排者在任务书里建议了该路径**，
   属编排失误；已改名 `ui/screens/importer` 并写入 `ARCHITECTURE.md` 的包名注意事项。
3. **`SyncCoordinator` 拆分引入 2 处回归**：① 给 `SyncCycleRunner(...)` 传了其不存在的 `debugLog` 形参；
   ② 把原「普通字段 `var isOfflineMode` + `private set`」改成「自定义 `get()`/`private set(value){}` 访问器」后，
   Kotlin 为 `isXxx` 布尔属性生成 JVM 方法 `setOfflineMode(Z)V`，与公开的 `fun setOfflineMode` 构成
   **Platform declaration clash**（编译期失败）。修法：改为只读 `val` + 函数写穿 `session.isOfflineMode`
   （**公开 API 一字未变**，`@Volatile` 语义由 `SyncSessionState` 承接保留）。两处均由编排者在集中编译中定位并修复。
4. **`OnePasswordPuxImporter` 以裸 `Any?` 当 `Map` 用**：`findTotp` 的 `flatMap` 后缺类型判定 →
   补 `mapNotNull { ImportJson.asObject(it) }`（畸形节点跳过而非抛 `ClassCastException`）。
5. **两个 JSON 解析器内部自调用未定义方法**：`Accumulator.clearEntries()` / `warningsSnapshot()` 未定义
   → 补齐（失败路径**真实清零**半成品明文；快照返回不可变副本）。
6. **`ChildDatabaseException` 属性名 `reason` 与调用点不一致**（`Unresolved reference 'reason'`），
   且 `settleWithEpoch` 误用泛型擦除检查 `result is KdbxResult.Success`（应为 `result.isSuccess`）。
7. **`BrowserCsvImporter` 对 `Char` 调 `String.removePrefix`**（该重载只接受 `CharSequence`）→ 补 `.toString()`。
8. **`EntryReferenceDisplayResolver { ... }` 尾随 lambda 绑定错位**：该类**不是** `fun interface` 且
   `protectedPlaceholder: String` 是末位形参，故尾随 lambda 被当作 `String` 传入 → 改为具名参数。

**B. 测试缺陷（生产实现正确，测试自身有缺陷——与 §3.3 第 5 条同类）**

9. **`DatabasePickerKeyFileCreateTest` 别名共享自击**：同一数组既作期望值又被断言「用毕已清零」
   （`assertArrayEquals(keyFileBytes, ...)` 与 `keyFileBytes.all { it == 0 }` 互相矛盾）→
   改为**独立 `copyOf()` 快照**作期望值。**生产实现正确**：既真实下传了用户选中的字节，也真实清零了调用方副本。
10. **`KeePassXmlImporterTest` 3 例夹具缺陷**：① 多行拼接使 `trimIndent()` 退化为空操作 →
    `<?xml?>` 声明前残留空白，JDK SAX 直接抛 `SAXParseException`（XML 规范要求声明位于文档起始处）；
    ② otpauth URI 的裸 `&` 是非法 XML（须写 `&amp;`）。两处均为**夹具**问题，修法为 `.trimStart()` 与 `&amp;`，
    **未削弱任何断言**。
11. **两个 JSON 测试夹具的「JSON 字符串内裸换行」非法**（JSON 不允许字符串字面量含裸控制字符）→ 修正夹具。

**C. 编排过程缺陷（编排者自身，如实登记）**

12. **上游任务书缺陷传导**：编排者给导入 UI 指定的包名 `import` 违反 Java 关键字约束（见 A2），
    且未预先把「Bitwarden/1PUX 的 Hilt 多绑定由谁写」写成单一归属 —— 两名队员各自按「对方会写 / 我不许写」
    理解，导致**两个数据源一度处于「解析器就绪但 `find()` 恒返回 null」**的不可用状态。
    修法：编排者在集成阶段就地补两条 `@Binds @IntoSet`（`ImporterModule.kt`）。
13. **一次基于陈旧快照的误判并已自我纠正**：编排者据一轮**测试运行中途**的结果判定
    「Bitwarden/1PUX 两个解析器对全部合法输入返回 Failure」，遂按「解析器系统性缺陷」方向排查；
    实测用**与测试逐字等价的夹具**直接调用解析器**两次均成功**，证明那一轮失败反映的是**队员仍在写盘的中间态**，
    而非解析器缺陷。**结论：集中编译/测试的结论必须与「执行期间是否有队员在写盘」一并解读**，
    否则会把中间态误判为缺陷（本条登记为「不要以单次快照代替稳定态结论」）。
14. **「单一 Gradle 会话」纪律有效规避了上一批次的事故**：本批次全程**明令禁止队员执行任何 Gradle 命令**，
    由编排者串行构建并回传错误。实测全程仅 2 个 java 进程、可用内存 8.7 GB，
    未复现 §3.3 第 10/11 条登记的 OOM 与 build 目录并发截断（`EOFException` / `Kryo Buffer underflow`）。
15. **测试用例数显著增长**：921 → 1189（**+268**）。其中 app 416 → 667（+251）。
    集中编译把队员的静态自检升级为**真实门禁证据**，是本批次能在 30 个初始编译错误、
    25 例系统性测试失败、6 例残留失败中被逐层收敛到 0 的直接原因。

---

## 5. ISSUE-P3-30 归档（子库条目只读投影接入库列表）

> 来源：`docs/ACTIVE_ISSUES.md` §P3 的 **P3-20 后续 · 接线中如实发现的过度声明**。完成日期：2026-09-10。
> 背景：`ChildDatabaseSessionManager.projectedEntries` 当时**生产消费方为零**，而设置页文案已在 P3-20
> 诚实化为「其条目暂未合并进当前库列表」——即挂载、真实解密、条目计数、状态流转、解锁/卸载全为真，
> **唯独「条目在当前库中可见」未接线**。本批次把该投影接入库列表，并守住三条边界：
> **只读**、**不并入根库条目流**、**不参与搜索与自动填充**。

### 5.1 本批次验收证据

| 模块 | 用例 | 失败 | 跳过 |
|---|---:|---:|---:|
| app | 678 | 0 | 0 |
| core | 58 | 0 | 0 |
| crypto | 61 | 0 | 0 |
| database | 224 | 0 | 0 |
| sync | 179 | 0 | 13 |
| **合计** | **1200** | **0** | **13** |

**1187 通过 / 0 失败 / 13 跳过**（基线 1189 → **1200，净增 +11 例，零退化**；app 667 → 678）。
命令：`.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` → **BUILD SUCCESSFUL**；
`.\gradlew.bat assembleDebug` → **BUILD SUCCESSFUL**。13 例跳过仍为 §4.1 登记的既有两项，与本批次无关。

### 5.2 验收标准逐条对照

| 条目验收标准 | 落实与代码证据 |
|---|---|
| ① 已解锁子库条目以分组路径可见，编辑/删除入口被拒 | `ChildVaultEntryPresenter.groupsOf()` 把扁平投影按挂载归拢为展示分区（保持登记顺序）；`ChildVaultEntryRow.displayPath` = 「挂载别名 + 子库内分组路径」（根级条目回退别名，分隔符复用 `GroupPathPresenter.SEPARATOR`，不另写一套）；`VaultChildDatabaseSections.kt` 的 `ChildDatabaseSectionHeader` / `ChildVaultEntryRowView` **不接收任何回调形参**——`onClick` / `onLongClick` / `onCopyPassword` / `onDelete` 一个都不存在 |
| ② 根库同步/合并/历史/回收站路径零子库条目 | 类型级切割：根库条目流恒为 `VaultListUiState.entries`（`UiVaultEntry`），子库投影恒为 `childEntryGroups`（`ChildVaultEntryRow`），两者**不是同一类型**，故所有写方法（`batchMoveSelected` / `batchDeleteSelected` / `purgeEntry` / `restoreEntry` …）在编译期即不可能收到子库行。新增 `VaultListChildDatabaseTest` 3 条结构断言（根库条目流与总数不因子库挂载改变；全选只覆盖根库条目；批量删除后子库投影不变）。同步隔离沿用 §4.2 P3-20 的 `ChildDatabaseSyncIsolationTest` |
| ③ 根库锁库后投影即时消失 | 核心层 `onSessionLocked` → `terminateAllSessions()` → `projectedEntries` 归空；ViewModel 仅订阅该 StateFlow，UI 无清理逻辑。专测：锁定后 `childEntryGroups` 空、分区隐藏、**挂载计数仍为 1**（登记属非敏感配置） |
| ④ 原过度声明文案 | **见 §5.3 偏离说明**（本批次仍改写了文案） |
| ⑤ 补单测：投影装配 / 只读拒绝 / 锁库消失 | 新增 **11 例**：`ChildVaultEntryPresenterTest` 6 例（纯函数：空输入 / 归拢与顺序 / 路径回退 / 分隔符展开 / 跨挂载同名 UUID 的行 key 唯一性 / 字段映射）+ `VaultListChildDatabaseTest` 5 例（端到端真实解密：分区下发与流隔离 / 锁库消失 / 卸载消失 / 搜索不参与 / 批量写不触碰子库） |
| ⑥ `test` 全绿且用例数不减 | 见 §5.1 |

**非验收要求但一并做的诚实化**：`ChildDatabaseSessionManager` 类 KDoc 原写「是否在根库界面展示由上层决定
（UI 接线不在本阶段范围内）」「供根库界面**按需合并展示**」——本批次接线后该表述已失真，就地改写为
「以**并列的只读分区**展示，且不进入根库条目流 / 搜索 / 自动填充」，避免留下与实现相反的一手注释。

### 5.3 与条目正文的偏离（如实登记）

1. **验收标准 ④ 说「原过度声明文案无需再改」，本批次仍然改写了它。** 该句在 P3-20 被诚实化为
   「其条目**暂未**合并进当前库列表」，而本次接线后条目**已确实在列表中可见**，保留原句会构成
   **反向失真**（§4.4-A 已有多处「反向的不诚实同样属违规」先例）。故中英双语改写为
   「其条目在库列表中只读并列展示（不并入当前库，不参与搜索与自动填充）」——语句与实现逐字对应。
2. **「编辑/删除入口被拒」按「入口不存在」实现，而非「运行期拒绝」。** 子库行与根库条目分属两个类型、
   行组件不接收写回调，故不存在可被绕过的判定。
   **并且刻意不做**按 UUID 的运行时拒绝：子库完全可能是根库的副本（KDBX 复制即 UUID 全同），
   按「子库 UUID 集合」拒绝会**误伤根库自身的合法编辑**——该取舍已写入 `ChildVaultEntryRow` KDoc。
3. **搜索与自动填充的裁决已显式落定并留痕**（条目要求「显式裁决并写入 KDoc」）：首版**均不参与**。
   搜索不参与的理由是投影只是「已解密条目的展示快照」，未解锁即无可检索内容，若纳入会出现
   「同一子库时而搜得到、时而不见」的降级歧义；不参与时由 `ChildDatabaseSearchExclusionHint`
   在搜索态如实提示（`mountedChildDatabaseCount > 0` 才出现），不做静默漏项。
   自动填充侧**零改动**：`KeePasskeyAutofillService` 走 `VaultRepository`，子库投影从不进入该仓库。

### 5.4 本批次过程缺陷（如实留痕）

1. **`VaultListUiState.kt` 新增 `ChildVaultEntryGroup` 字段时漏写 import**，首轮 `:app:compileDebugKotlin`
   报 7 处 `Unresolved reference`（含被连带解析成 `items(count)` 重载的假错误）。补齐 import 后单轮通过——
   登记教训：**新增跨包类型的字段后应立即编译，而非写完所有文件再统一编译**（后者的错误会被连带放大）。
2. **初稿在 `VaultChildDatabaseSections.kt` 留下一个仅为「用掉 import」而写的 `private val densityTypeAnchor`**
   死代码，自查时连同多余的 `ListDensity` import 一并删除。登记教训：**为迁就 import 而新增符号是本末倒置**，
   正确做法是删 import。

---

## 6. ISSUE-P3-29 归档（全仓超阈值债务 · 批次 A）

> **归档日期**：2026-09-10。**范围**：条目正文点名的**优先级 8 项**（全部降至 400 行阈值内），
> 另**增量完成** `KdbxHeader` / `SyncCredentialsStore` 两项，并登记 `DicewareWordList` 为**经论证的纯常量例外**。
> **残余**：23 个真逻辑超阈值文件已按「新增条目须附核实时间点与核实方式」转入
> [ACTIVE_ISSUES.md](ACTIVE_ISSUES.md) **ISSUE-P3-31**（2026-09-10 经 `wc -l` 全仓复核）。

### 6.1 范围与门禁

| 项 | 内容 |
|---|---|
| 整改类型 | **纯结构性拆分**（每一处均零行为变更） |
| 门禁命令 | `.\gradlew.bat test --rerun-tasks --max-workers=1 --continue` |
| 结果 | **BUILD SUCCESSFUL**，**1200 例 / 1187 通过 / 0 失败 / 13 跳过**（**与拆分前完全一致，零退化**） |
| 附加门禁 | `:app:compileDebugKotlin` 通过；`:database:assembleDebugAndroidTest` 通过（ISSUE-P3-23 验收标准①） |

### 6.2 逐项代码证据（行数前后与拆出单元）

**① 优先级 8 项**

| 文件 | 行数变化 | 拆出的新单元（均 ≤ 400 行） |
|---|---|---|
| `app/.../ui/screens/settings/SettingsViewModel.kt` | **1120 → 397** | `SettingsChildDatabaseController`(232) · `SettingsPreferencesController`(326) · `SettingsExtendedPreferencesController`(191) · `SettingsUiStateProjection`(206，含 `settingsUiStateFlow` 状态流装配) · `SettingsKdfBenchmarkController`(68) · `SettingsColdStartSyncGate`(39) · `SettingsImportPresenter`(36) |
| `app/.../ui/screens/vault/VaultListViewModel.kt` | **803 → 349** | `VaultListProjection`(230，纯函数) · `VaultListSyncController`(122) · `VaultListTotpTracker`(81) · `VaultListActionController`(234) · `VaultListDecorationsProvider`(51) |
| `app/.../ui/screens/settings/subscreens/DatabaseSettingsDialogs.kt` | **617 → 200** | `ChildDatabaseDialogs`(349) · `DatabaseAlgorithmDialogs`(127) |
| `database/.../file/KdbxFile.kt` | **614 → 382** | `KdbxCipherKeyResolver`(154，派生变体裁决探针) · `KdbxKeyDerivation`(129)；`KdbxFile.deriveKeys` 保留为同签名门面委托 |
| `app/.../ui/KeePasskeyApp.kt` | **590 → 220** | `KeePasskeyNavGraph`(183，一级路由) · `KeePasskeySettingsNavGraph`(244，二级设置路由) |
| `app/.../ui/screens/vault/VaultEntryRows.kt` | **466 → 122** | `VaultGroupRow`(148) · `VaultEntryRowLayouts`(248)；`StandardEntryLayout` 由 `private` 提升为 `internal`（同包跨文件） |
| `app/.../data/repository/VaultRepository.kt` | **435 → 382** | `VaultRepositoryTypes`(同包顶层值对象 `EntryRevisionSnapshot` / `EntryTotpSnapshot` / `CreateKeyFileFactor`，全限定名不变) |
| `app/.../ui/screens/vault/VaultListScreen.kt` | **448 → 354** | `VaultListDialogHost`(147，8 个对话框的 `@Stable` 状态持有者 + 渲染) |

**② 增量完成 2 项**

| 文件 | 行数变化 | 拆出的新单元 |
|---|---|---|
| `database/.../file/KdbxHeader.kt` | **418 → 315** | `KdbxKdfParameterCodec`(130，KDF 变体字典编解码 + 上界裁决)；`validateArgon2Bounds` / `validateAesKdfBounds` 保留为同签名门面委托 |
| `app/.../sync/SyncCredentialsStore.kt` | **421 → 320** | `SyncCredentialSealer`(137，封印/解封)；测试注入钩子 `customEncryptor` / `customDecryptor` 仍由 Store 持有并逐次传入，`@VisibleForTesting` 语义不变 |

**③ 例外登记（验收标准 1）**

- `app/.../ui/screens/generator/DicewareWordList.kt`（**401 行**）：内容为 EFF/KeePassDX 风格 Diceware
  **词表**（约 300 行为不可压缩的字符串常量）+ 少量纯函数。按「是否含真实逻辑」分级属**经论证的纯常量例外**，
  不拆分——保留单文件可保证词表作为不可分割的整体被审查。**不再列入债务清单**。

### 6.3 敏感数据清零点与公开 API 可见性对照（验收标准 4）

1. **`VaultListViewModel` 公开 API 零丢失零新增**：原 24 个公开成员全部保留；`uiState` 输出字段一字未改
   （拆分仅移动「投影计算」与「写操作实现」到协作者，本类保留门面）。**`copyPassword` 的 CharArray 清零**
   原在 `finally { chars.fill('0') }`，迁移后位于 `VaultListActionController.copyPassword` 的同一 `finally`，语义不变。
2. **`SettingsViewModel` 公开 API 零丢失零新增**：约 100 个公开成员全部保留为门面委托。
   **凭据预填通道清零**仍由本类 `onCleared()` 逐条调用（`clearWebDavPasswordPrefill` / `clearS3SecretKeyPrefill` /
   `clearS3AccessKeyPrefill`）。**子库凭据借用副本清零**原在 `finally { password.fill('0'); keyFileBytes?.fill(0) }`，
   迁移后位于 `SettingsChildDatabaseController.mount` / `unlock` 的同一 `finally`。
   `SettingsColdStartSyncGate` 的 `hasCheckedColdStartSync` 保持 **process-static（伴生对象）**，
   实例重建既不重复触发也不漏触发（与原 `SettingsViewModel.companion` 语义一致）。
3. **`SyncCredentialsStore` 敏感链路**：`encrypt` 的明文字节 `bytes.fill(0)`、`decrypt` 的 `decryptedBytes?.fill(0)`
   均在迁移后保留于 `finally`；Store 的 `password.fill('0')` / `accessKey.fill('0')` / `secretKey.fill('0')`
   与原实现逐字一致。封印实现体（AES/GCM + `requireUserAuth=false` 决策 KDoc）原样迁移至 `SyncCredentialSealer`。
4. **`KdbxFile` 密钥生命周期**：`deriveKeys` 的 `compositeKey` / `transformedKey` / `cmpKey` / `cipherKeyBytes`
   清零点逐条保留在 `KdbxKeyDerivation`；旧派生裁决的「未选中密钥统一清零」由 `KdbxCipherKeyResolver.Resolution`
   的 `legacyKeyToWipe` 契约承接，`loadPayload` 在原位置 `Arrays.fill(it, 0.toByte())`。`MAX_DECOMPRESSED_PAYLOAD_BYTES`
   仍留在 `KdbxFile`（作为内层各级上限的唯一真源，`InnerHeader` 的派生与 `require` 不变量不受影响）。

### 6.4 验收标准逐条对照

| 验收标准 | 落实与证据 |
|---|---|
| ① 按「是否含真实逻辑」分级，纯数据表登记为例外 | `DicewareWordList.kt`（401）登记为经论证的纯常量例外（见 §6.2-③） |
| ② 优先处理正文 ① 的 8 项 | **8/8 全部降至阈值内**（见 §6.2-①），另增量完成 2 项 |
| ③ 纯结构性改动、`test` 全绿且用例数不减 | **1200 例 / 0 失败 / 13 跳过**，与拆分前逐数一致（见 §6.1） |
| ④ 逐条对照敏感数据清零点与公开 API 可见性 | 见 §6.3（四个安全关键面逐条对照） |

### 6.5 本批次过程缺陷（如实留痕）

1. **改写 `VaultListViewModel` 时漏掉 `import com.keepasskey.app.ui.model.EntryDisplayDispatcher`**，
   首轮 `:app:compileDebugKotlin` 报 KSP `NonExistentClass`（注解类型无法解析）。
   登记教训：**重写含注解形参的构造函数时，须与原文件的 import 清单逐条比对**，不能只按新写入的代码补 import。
2. **拆分 `KeePasskeyApp` 时 `KeePasskeyNavGraph` 形参类型写错**（写成 `UserSettings`，实际 `uiState` 是
   `SettingsUiState`），且新文件漏 `androidx.compose.runtime.getValue`（`by` 委托报 17 处
   "has no method getValue"）。登记教训：**迁移 Compose `by` 委托代码块时必须带上 `getValue` import**；
   形参类型应直接以调用点实参类型为准，而非按语义猜测。
3. **`KeePasskeyNavGraph.kt` 首拆后仍 408 行**（未达阈值）→ 追加第二轮拆出二级设置路由
   （`KeePasskeySettingsNavGraph`）。登记教训：**拆分的粒度预判应以「迁移后剩余量」为准，
   一次拆出一个自然边界后必须立即复测行数**，否则「拆了但仍超标」。
4. **`SettingsViewModel` 首轮重写后仍 547 行**（远未达标）→ 追加四轮抽取（状态流工厂 / 导入门面 /
   冷启动闸门 / 进阶偏好副作用下沉）并收紧排版，最终 **397 行**。登记教训：**近千行的「门面 + 全量 setter」
   类，其 setter 样板本身即占数百行——拆分设计必须先把「有副作用的 setter」与「纯投影」分别下沉，
   再评估剩余量**；同时如实登记：397 行距 400 阈值仅 3 行余量，属**贴线达标**，
   后续若再向该类新增成员须同步拆解。
5. **`SettingsUiStateProjection` 初稿引入不存在的 `EntryDecorationsExport` import 且以 5 个 `Boolean` 形参
   代替 `UserSettings`**，编译期暴露后改为直接传 `UserSettings`。登记教训：**迁移投影函数时应保持入参类型
   与原 combine 消费对象一致**，避免用散列布尔「抹平」类型。
6. **`SettingsViewModel` 注释中 `Accesskey` 大小写笔误**（应为 `AccessKey`），自查时修正。
7. **`VaultEntryRows` 拆分后残留未使用的 `BitmapEntryIcon` import**，自查时删除。登记教训同 §5.4-2：
   **拆分后应立即清理失效 import，而非留待编译告警**。
8. **本批次实测快照与原条目 2026-09-10 登记快照存在行号漂移**（如 `VaultListViewModel` 731 → 803、
   `VaultListScreen` 417 → 448、`WebDavSyncProvider` 510 → 509），印证「条目维护规则」第 3 条
   「行号一律视为核实时刻的快照」；已在 ISSUE-P3-31 中以**开工实测**覆盖。

