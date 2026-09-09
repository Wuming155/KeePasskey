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

---

## 1. 已完成核心任务清单

| TASK ID | 领域 | 任务主题 | 优先级 | 完成日期 | 核心实现与代码证据 / 说明 |
|:---:|:---:|---|:---:|:---:|---|
| **TASK-01** | 安全 | HMAC 防篡改回归锁 flaky 排查与定型 | **P0** | 2026-09-07 | 定位并修复终止块未校验即置 `terminated=true` 导致篡改文件 ~10% 概率静默解锁的漏洞；改为仅校验通过后置位并在 `verifyEndOfStream` 权威检查点 fail-closed；`testCorruptHmacBlock` 20 连跑零失败。 |
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
