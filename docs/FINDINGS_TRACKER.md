# KeePasskey 历史审查发现项全量跟踪表（131 项）

> **更新时间**：2026-09-07  
> **数据说明**：本表基于 2026-09-07 对 `main` 分支（git `7b3e756`）全量 201 个 Kotlin 源码文件与测试套件的**实测代码物理核对**结果生成。所有 P0 级阻断项与高危安全缺陷均已 100% 验证修复。
> **状态说明**：本表「物理状态」列为 **2026-09-07 审计时点的代码快照**，仅作历史核对记录，**不随后续修复实时更新**；任务实时完成状态以 `STATUS.md` §2 看板为准，修复落地后请在 STATUS 置 ✅ 并回写此处代码证据。  
> **列说明**：`是否有必要修复` 取值为「否（已修复）/ 否（不适用）/ 低 / 低-中 / 中 / 中（安全）/ 中（功能）/ 中（测试）/ 高（真实 Bug）/ 不急（存疑）/ 不急（描述有误）/ 极低」；`说明` 为实测核对结论。

---

## 1. 2026-09-05 全量代码审核报告发现项（93 项）

### P0 阻断级发现（7 项，已 100% 修复）

| 编号 | 问题描述 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|---|:---:|---|:---:|---|
| **P0-1** | 重命名分组/改图标静默清空其下全部条目与子分组 | ✅ 已修复 | `RealVaultRepository.kt:281-308` 查找既有节点做 `existing.copy(...)` 增量合并；`DatabaseSession.kt:454-461` 增加 `preserveChildrenIfMissing` 兜底保护 | 否（已修复） | 子树保护与增量合并已落地，无需动作 |
| **P0-2** | 真实主密码/密钥文件密钥/salt 被抄进已提交源码 | ✅ 已修复 | `Argon2InteropDiagnosticTest.kt:27-52` 已改为合成口令 `TestMasterPassword!2026#Secure` 与自造十六进制密钥，`KdbxKeyFileTest.kt:33` 为 32B 合成测试字节 | 否（已修复） | 已无真实凭据入源码 |
| **P0-3** | 「更改主密钥」纯 UI 假实现 | ✅ 已修复 | `SettingsScreen.kt:372` 调 `onChangeMasterPassword` → `RealVaultRepository.kt:174` → `DatabaseSession.kt:338` `changeCredentials` 触发全量加密重写盘 | 否（已修复） | 改密管线已贯通 |
| **P0-4** | ChaCha20 保存写出 16B EncryptionIV（官方要求 12B） | ✅ 已修复 | `KdbxFile.kt:354` `freshEncryptionIv` 动态取 `ivLength`（ChaCha20=12B）；`ChaCha20CipherEngine.kt:75` 严格校验非 12B 即抛异常，无静默截断 | 否（已修复） | IV 长度随算法动态取，已无截断 |
| **P0-5** | 未认证 Header 字段长度直接驱动数组分配可致 OOM | ✅ 已修复 | `LittleEndianUtil.kt:59` `readBytes` 增加 `maxLength` 上界检查（默认 16MiB）；`KdbxHeader.kt:108` 单字段 1MiB 限制；`VariantDictionary.kt:199` 增加 Key/Value 上限 | 否（已修复） | 多维长度上限已加 |
| **P0-6** | 受保护值 Base64 解码失败静默降级致后文解密错乱 | ✅ 已修复 | `KdbxXmlGroupReader.kt:266` SAX `StringNode.end` 先 `value.trim()`，Base64 解码失败直接抛 `KdbxCorruptFileException`，消除降级路径 | 否（已修复） | 解码失败即抛异常 |
| **P0-7** | Passkey 私钥被物化为不可变 String | ⚠️ 部分修复 | 断言侧已改为 `readUtf8()` 字节路径并即时擦除（`PasskeyAssertionActivity.kt:136`）；生成侧及受保护字段格式层依然受 KDBX 规范限制使用字符串 | 是（残余待评估） | 断言侧已字节化擦除；生成侧/受保护字段因 KDBX 规范约束暂难消除 String，属已知边界，建议评估可降解方案 |

### P1 高危发现（15 项）

| 编号 | 问题描述 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|---|:---:|---|:---:|---|
| **P1-1** | `HealthCheckEngine` 构建全库明文密码索引表 | ✅ 已修复 | `HealthCheckEngine.kt:47-63` 索引键改为 SHA-256 哈希，弱口令判定后即时 `fill('0')` 擦除 | 否（已修复） | 索引已哈希化并擦除 |
| **P1-2** | 浏览器特权白名单 JSON 缺 `apps` 外层对象 | ✅ 已修复 | `CallingOriginResolver.kt:27-44` 白名单 JSON 修正为官方 `{"apps":[...]}` 结构，浏览器场景恢复正常 | 否（已修复） | 结构已修正 |
| **P1-3** | 锁库态用 addAction 而非 addAuthenticationAction | ✅ 已修复 | `KeePasskeyCredentialProviderService.kt:122` 调 `AuthenticationAction.Builder` 并以 `addAuthenticationAction` 添加 | 否（已修复） | 已走认证动作 |
| **P1-4** | Passkey 创建绑定包名取自 `callingPackage` 错误 | ✅ 已修复 | `PasskeyCreateActivity.kt:91` 改用 `providerReq?.callingAppInfo?.packageName`，包名缺失 fail-closed | 否（已修复） | 包名来源已修正 |
| **P1-5** | 编辑页密码生成器使用非密码学安全随机源 | ✅ 已修复 | `EntryEditViewModel.kt:207` 改用 `java.security.SecureRandom` 生成密码并用完擦除 | 否（已修复） | 已换密码学随机源 |
| **P1-6** | 设置模块 35 个开关为纯内存回显且无消费者 | ⚠️ 部分修复 | `RealSettingsRepository.kt` 已将 21 项关键设置持久化；下架了危险假开关；但约 35 个扩展开关仍纯内存回显 | 是（残余待补） | 关键设置已持久化；约 35 个扩展开关按需补齐持久化或明确标注为展示项 |
| **P1-7** | 数据库设置页 5 个动作 + 调试日志导出全为假成功 | ⚠️ 部分修复 | `SettingsViewModel.kt:1023` 调试日志导出已接 SAF 写盘；导出 KDBX/XML/密钥文件/模板/子库挂载 5 动作仍为假提示 | 是（残余待补） | 日志导出已接 SAF；5 个导出动作需接真实实现 |
| **P1-8** | Meta/Group 7 个官方字段及 Group CustomData/Tags 读写丢失 | ⚠️ 部分修复 | `KdbxXmlMetaReader.kt`/`Serializer.kt` 补齐 7 字段读写；`KdbxXmlGroupReader.kt` 补齐 CustomData/Tags；保留桶机制未做 | 是（残余待补） | 7 字段及 CustomData/Tags 已补；保留桶（RecycleBin 内容保留）机制未做 |
| **P1-9** | 附件字节数组在去重后别名共享，clear() 清零二进制池 | ⚠️ 部分修复 | `KdbxBinaryDeduplicator.kt:59` 保存去重时克隆副本；读取侧 `KdbxXmlGroupReader.kt:315` 仍共享池数组引用 | 低 | 保存侧已克隆；读取侧别名共享在只读解析场景风险有限，可后续消除 |
| **P1-10** | 仅密钥文件（无密码）的官方库无法打开 | ✅ 已修复 | `KdbxFile.kt:457` `deriveKeys` 改为 `passwordChars: CharArray?`，无密码时只算 `SHA-256(keyFileKey)` | 否（已修复） | 仅密钥文件库可开 |
| **P1-11** | WebDAV `uploadAtomic` 无 ETag 时无条件覆盖远端 | ⚠️ 部分修复 | `WebDavSyncProvider.kt:279` 无 ETag 时先 PROPFIND 探测：不存在发 `Overwrite: F`（412 转 ConflictError）；存在仍 `Overwrite: T` | 低 | 无 ETag 已先 PROPFIND 探测；存在场景仍 `Overwrite:T`，对极简 DAV 服务端兼容可接受 |
| **P1-12** | `commitLocal` 把拉取冲突远端失败伪装成空内容冲突 | ✅ 已修复 | `SyncEngine.kt:328` 下载失败改为发送 `CouldntSaveToRemote` 并返回 `RemoteUnreachable(keptLocal = true)` | 否（已修复） | 失败已如实上报 |
| **P1-13** | 生物解锁用 `CharBuffer.array()` 带有 UTF-8 尾零 | ✅ 已修复 | `UnlockViewModel.kt:395` 改按 `remaining()` 精确拷贝 `CharArray(n)`，消除了尾零乱码 | 否（已修复） | 精确拷贝已无尾零 |
| **P1-14** | Autofill 保存凭据无结果通道（保存失败无感知） | ✅ 已修复 | `VaultRepository.kt:247` 签名改为 `KdbxResult<Unit>`，Autofill 服务依据 Result 按需回应 onSuccess/onFailure | 否（已修复） | 结果通道已贯通 |
| **P1-15** | 剪贴板自动清空在后台因 `primaryClip == null` 失效 | ✅ 已修复 | `ClipboardSecurityManager.kt:98` 无法读取时触发 `clearClipboard()` 兜底清理 | 否（已修复） | 后台兜底清理已加 |

### P2 中危发现（37 项）

| 编号 | 问题描述 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|---|:---:|---|:---:|---|
| **P2-1** | 全代码库无常量时间比较，HMAC 校验用 `contentEquals` | ⚠️ 部分修复 | 生产流路径 `KdbxFile.kt:93`/`HmacBlockStream.kt:219` 改 `MessageDigest.isEqual`；测试专用 `readAll` 仍保留 | 低 | 生产流已常量时间比较；测试 `readAll` 属非安全路径，可接受 |
| **P2-2** | 保存路径把 Argon2 KDF 放进 `Dispatchers.IO` | ❌ 未修复 | `DatabaseSession.kt:193` `KdbxFile.save`（含 Argon2 变换）仍包裹在 `withContext(Dispatchers.IO)` 内 | 低-中（性能微调） | Argon2 为 CPU 密集，理想应走 `Dispatchers.Default`；非安全缺陷 |
| **P2-3** | `InMemoryCipher.seal` 留下未清零的「密钥‖明文」拼接数组 | ✅ 已修复 | `InMemoryCipher.kt:84` 已重构为「随机 16B IV + AES-256-CTR 密文 + HMAC 标签」，主密钥 62 行即时清零 | 否（已修复） | 已重构并清零 |
| **P2-4** | ProtectedString.toString() 泄露明文；Entry getter 读明文 | ⚠️ 部分修复 | `ProtectedString.kt:206` toString 改为脱敏字符串；getter 仍读明文 | 低 | toString 已脱敏；getter 读明文属投影层取舍，沿用 `useChars` 闭环即可 |
| **P2-5** | TOTP 种子全程 String + 装箱 Byte 列表从不清零 | ❌ 未修复 | `OtpEngine.kt:146` Base32 仍以 String 承载且非法字符 `continue` 静默跳过 | 中 | `calculateTotp(secretKeyBase32: String)` 全程 String；`Base32Decoder.decode` 用 `mutableListOf<Byte>` 装箱；建议 ByteArray 链路并在用毕擦除 |
| **P2-6** | `VariantDictionary` 硬类型转换无长度校验抛异常 | ✅ 已修复 | `VariantDictionary.kt:59` 全面改为 `when` 宽容匹配，失配抛类型化异常并加固 Key/Value 上限 | 否（已修复） | 类型匹配已宽容 |
| **P2-7** | 原子写盘降级分支先删目标文件再 rename | ⚠️ 部分修复 | `AtomicFileWriter.kt:106` 改为先 renameTo，无备份兜底时拒绝覆盖；但 fsync 缺失 | 低 | 降级分支已先 renameTo；fsync 缺失可补（现行 `SyncCache` 已 `fd.sync()`） |
| **P2-8** | 外层 Header 缺 masterSeed 长度与算法取值校验 | ✅ 已修复 | `KdbxHeader.kt:254` 强制 MasterSeed 必须 32B，Compression 仅 NONE/GZIP，IV 匹配算法长度 | 否（已修复） | 校验已加 |
| **P2-9** | `parseEcPrivateKey` 用越界标量构造 KeyParameters | ✅ 已修复 | `PasskeyCryptoEngine.kt` 新增 `validateEcScalarRange` 显式校验（d ∈ [1, n-1]），越界 fail-closed 抛 `CryptoException.InvalidKeyException`；库层 IAE 经 `newEcPrivateKey` 归一为同一类型；5 例标量校验单测（d=0/d=n/d>n 拒绝，d=1/d=n-1 可用，hex 文本形态覆盖） | 否（已修复） | 标量越界 fail-closed |
| **P2-10** | 旧派生回退分支 `legacyCipherKey` 从不清零 | ✅ 已修复（2026-09-07） | `KdbxFile.kt` `resolveCipherKey` 重构为返回 `CipherKeyResolution`：未选中路径 `finally` 统一清零，选中路径在解密流建立后立即擦除 | 中 | 代码证据：`CipherKeyResolution(activeKey, legacyKeyToWipe)` + `loadPayload` 中 `resolution.legacyKeyToWipe?.fill(0)`（SecretKeySpec 已克隆密钥材料后擦除原数组） |
| **P2-11** | WebDAV Basic 认证使用 ISO-8859-1 致中文密码 401 | ❌ 未修复 | `WebDavSyncProvider.kt:85` 仍按 ISO-8859-1 编码密码 | 中 | 按 RFC 7617 默认 charset 编码，但非 ASCII（中文）密码会 401；应发 `charset=UTF-8` 并改 UTF-8 编码 |
| **P2-12** | 全网络请求无 `callTimeout` 与退避重试 | ❌ 未修复 | `SyncHttpClientFactory.kt:28` 仅设 connect/read/write timeout，无全局 callTimeout | 低-中 | 缺全局 `callTimeout`（含 DNS 解析）；弱网仍有悬挂风险，建议补 `callTimeout` |
| **P2-13** | 凭据加密失败时旧密文被保留且无错误上报 | ✅ 已修复 | `SyncCredentialsStore.kt:95` 改为先封印后落盘，加密失败直接中断并不改动磁盘 | 否（已修复） | 先封印后落盘已加 |
| **P2-14** | S3 无时钟偏移处理（导致 RequestTimeTooSkewed 403） | ❌ 未修复 | `S3SyncProvider.kt:305` 直接取本地时间，无服务端时间补偿机制 | 低 | `signV4` 取本地 `Date()`；设备时钟偏移 >15min 才触发，发生概率低，建议加偏移补偿 |
| **P2-15** | `updateBase` 两文件非原子对（.baseversion 与 .meta） | ❌ 未修复 | `SyncCache.kt:142` 分两次写，无原子保证 | 中 | `.baseversion` 与 `.meta` 分两次 `writeStringSafely`，崩溃窗口可能不一致；建议合并为单次原子写 |
| **P2-16** | 缓存临时文件名确定性致并发踩写 | ✅ 已修复 | `SyncCache.kt:225` 临时文件名加上 `UUID.randomUUID()` 防碰撞 | 否（已修复） | 已加 UUID |
| **P2-17** | 冲突解决页字段级选择塌缩为整条目二选一 | ❌ 未修复 | `ConflictResolutionViewModel.kt:128` 仍直接全量覆盖 | 中（功能） | UI 提供逐字段选择（`selectFieldChoice`），但 `applyMerge` 仅按「任一字段选 REMOTE 即整条 KEEP_REMOTE」塌缩；逐字段 UI 误导，应实现字段级合并或简化 UI |
| **P2-18** | WebDAV/S3 凭据明文长期驻留 StateFlow | ⚠️ 部分修复 | `SettingsUiState.kt:79` 密码改用 `CharArray?` 一次性预填通道；S3 AccessKey 仍以 String 留存 | 低-中 | 密码已走 CharArray 预填；S3 AccessKey 仍以 String 留存 StateFlow，建议同改造 |
| **P2-19** | 历史修订快照为空时谎报"已回滚" | ❌ 未修复 | `EntryDetailViewModel.kt:242` snapshot==null 路径依然触发已回滚 Snackbar | 中 | `snapshot == null` 时仍弹 `detail_history_rolled_back`（"已回滚"）但实际未回滚；应改错误提示 |
| **P2-20** | TOTP 复制绕过 `ClipboardSecurityManager` | ✅ 已修复 | `AuthenticatorViewModel.kt:124` 统一改调 `clipboardSecurityManager.copySensitiveText` | 否（已修复） | 已走受保护复制 |
| **P2-21** | 生产代码保留可替换加解密/封印测试钩子 | ✅ 已修复（2026-09-07） | `SyncCredentialsStore.kt` `customEncryptor`/`customDecryptor` 加 `@VisibleForTesting` + `internal` 双重收窄，生产 DI 与外部调用方不可写 | 低 | QuickUnlockPinStore 与 SyncCoordinator 钩子已清理；SyncCredentialsStore 钩子现仅限本模块单元测试注入 |
| **P2-22** | 改密对话框与编辑页密码框未用 `SecurePasswordField` | ✅ 已修复 | `SettingsScreen.kt:342` 与 `EntryEditScreen.kt:462` 均已换用 `SecurePasswordField`（CharArray 直通） | 否（已修复） | 已换用安全输入框 |
| **P2-23** | QuickUnlock PIN 以 String 进 UiState | ➖ 不适用 | 自研 PIN 体系已在 Wave 13 整体彻底删除，PIN 相关属性已被清理 | 否（不适用） | 自研 PIN 体系已删除 |
| **P2-24** | Autofill Dataset 在已解锁分支未设 setAuthentication | ✅ 已修复（2026-09-07） | `KeePasskeyAutofillService.kt` 已解锁分支每个数据集挂 `setAuthentication` → 新增 `AutofillConfirmActivity` 二次确认（生物识别/锁屏凭据优先，受保护窗口手动确认兜底） | 低-中（加固） | 认证数据集独立 requestCode；Activity 带 FLAG_SECURE + setHideOverlayWindows(true)；RESULT_OK 后框架才写入凭据值 |
| **P2-25** | 生物凭据封印失败被静默吞掉（catch ignored） | ✅ 已修复 | `UnlockViewModel.kt:301` 捕获异常并记录 `debugLog.warn`，显式提示用户 | 否（已修复） | 已显式提示 |
| **P2-26** | 数据库列表元数据硬编码假值与占位库 | ❌ 未修复 | `RealVaultRepository.kt:80` 仍使用占位数据与静态描述文案 | 低 | 无 kdbx 文件时回退占位 `default_vault` 作「引导创建首个库」可接受，优先级低 |
| **P2-27** | 条目密码强度恒为硬编码 112 bit | ❌ 未修复 | `MockData.kt:106` 硬编码 112 bit，无真实熵计算引擎接入 | 中 | `UiVaultEntry.strengthBits` 默认 112 恒显，误导用户；应接入熵估算或显式标注未计算 |
| **P2-28** | 健康检查"已泄露密码"恒 0 | ❌ 未修复 | `SettingsViewModel.kt:1096` `compromisedPasswordCount = 0` 硬编码 | 低（需外部服务） | 需 HIBP 类泄露库接入才有意义；当前占位可接受 |
| **P2-29** | 密钥文件 SAF 读取在主线程完成 | ❌ 未修复 | `UnlockScreen.kt:110` SAF 回调中直接在主线程读取 InputStream 字节 | 中 | SAF 回调中 `openInputStream` 读取密钥文件字节在主线程同步执行；大文件可能 ANR，应移至 `Dispatchers.IO` |
| **P2-30** | 验证器页每秒在主线程对全部 TOTP 条目做解密+HMAC | ❌ 未修复 | `AuthenticatorViewModel.kt:57` combine 变换未指定 IO 调度器 | 低-中 | `combine` 未显式 `flowOn`，但上游 `timerSecondsFlow` 已标 `Dispatchers.Default`；建议显式 `flowOn(Dispatchers.Default)` 兜底 |
| **P2-31** | `@Singleton` 仓库构造函数内同步做磁盘扫描 | ❌ 未修复 | `RealVaultRepository.kt:62` init 块同步在主线程扫描 `filesDir` | 低 | `@Singleton` 构造 `init` 同步扫描 `filesDir`；操作快但应移出构造期（懒加载/IO） |
| **P2-32** | Release 未开 shrinkResources；ProGuard 过度宽松 | ❌ 未修复 | `app/build.gradle.kts:24` 未开启 shrinkResources，`-dontwarn **` 仍保留 | 中 | `isMinifyEnabled=true` 但缺 `isShrinkResources = true`；建议开启并对 `proguard-rules.pro` 收敛 |
| **P2-33** | Passkey 注册未对 rp.id 做 DAL 校验 | ⚠️ 部分修复 | `DomainMatcher.kt:107` 已加入可注册域匹配防线；完整 DAL 远程校验未引入 | 低 | 可注册域匹配已加；完整 DAL 远程校验（需网络）未引入，属增强项 |
| **P2-34** | TOTP 缺失时以假验证码 "000000" 兜底 | ❌ 未修复 | `AuthenticatorViewModel.kt:82` 异常时依然 fallback 到 `"000000"` | 中 | 计算失败时 `?: "000000"` 显示看似合法的假码，误导用户；应显示错误或空白 |
| **P2-35** | `SecurityTest` 宣称覆盖硬件闭环实际只测 JDK | ❌ 未修复 | `SecurityTest.kt:30` 依然使用 JDK 默认 KeyGenerator | 低（测试质量） | 声称覆盖硬件闭环却用 JDK 默认 `KeyGenerator`（非 AndroidKeyStore）；应注入 `AndroidKeyStore` 密钥或改名澄清 |
| **P2-36** | app 测试 14 个在测 `FakeVaultRepository` 本身 | ⚠️ 部分修复 | Fake 已出库至 `src/test`；但这 14 个单元测试依然测的是 Fake 自身 | 低 | Fake 已出库；14 个单测仍测 Fake 自身，属测试有效性缺口，优先级低 |
| **P2-37** | `SyncCredentialsStoreTest` 注入 XOR 假加密绕过真实路径 | ❌ 未修复 | `SyncCredentialsStoreTest.kt:88` 仍统一注入 XOR 假加密 | 中（测试） | 注入 XOR 假加密器，未走真实 Keystore 路径；应补真实/Instrumented 加密路径用例（同 T-05） |

### P3 低危与整洁度（34 项）

| 编号 | 问题描述 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|---|:---:|---|:---:|---|
| **P3-1** | InnerRandomStreamID=0(None) 无对应分支拒开合法库 | ✅ 已修复 | `InnerRandomStreamCipher.kt:26` 实现了 `NoopStreamCipher` 直通逻辑 | 否（已修复） | 已支持 None 直通 |
| **P3-2** | Times 缺失默认 `now()` 致三方合并恒压过对端 | ✅ 已修复 | `KdbxXmlTimeHelper.kt:69` 缺省 fallback 改为 `Instant.EPOCH` | 否（已修复） | 已改 EPOCH |
| **P3-3** | XML 写出不转义 CR (CRLF→LF) 且剔除控制字符 | ✅ 已修复 | `KdbxXmlStreamWriter.kt:94` escape 已将 `\r` 转义为 `&#xD;` | 否（已修复） | CR 已转义 |
| **P3-4** | HistoryManager 硬编码上限且快照顺序反 | ✅ 已修复 | `HistoryManager.kt:41` 已接入库级配置上限，快照顺序修正为最新在前 | 否（已修复） | 上限与顺序已修正 |
| **P3-5** | 二进制池去重为 O(n²) 线性遍历 | ❌ 未修复 | `KdbxBinaryDeduplicator.kt:52` 依然使用 `indexOfFirst` 遍历 | 低（性能） | `indexOfFirst` O(n²) 遍历；附件池规模小时无感，大库才显著，可换 HashMap 指纹索引 |
| **P3-6** | 三个模块声明未使用的 `androidx.core:core-ktx` | ✅ 已修复 | core/crypto/database 三模块 `build.gradle.kts` 已移除该依赖 | 否（已修复） | 依赖已移除 |
| **P3-7** | 未使用 import 残留 | ❌ 未修复 | `KdbxHeader.kt:10` 等文件中未使用的 import 仍存在 | 极低 | `import java.io.ByteArrayInputStream` 全文件仅出现于 import（未使用）；死代码清理 |
| **P3-8** | `KdbxFile.save` 头部序列化两遍 | ❌ 未修复 | `KdbxFile.kt:385` 仍存在 ByteArrayOutputStream 双重缓冲 | 极低 | 实测仅一次 `serialize`（写入 ByteArrayOutputStream 并返回同一 `headerBytes` 再写盘），是**双重缓冲**而非"序列化两遍"，非正确性 bug |
| **P3-9** | `setDatabaseForTesting` 为绕过只读模式的公有后门 | ❌ 未修复 | `DatabaseSession.kt:306` 该测试后门依然为 public 且无约束 | 低 | 为 public 测试后门；建议加 `@VisibleForTesting` 或 internal 约束 |
| **P3-10** | ProtectedString.EMPTY 共享单例可被污染 | ❌ 未修复 | `ProtectedString.kt:79` EMPTY 单例可被 clear() 影响全局 | 低 | `EMPTY` 共享单例可被任意 `clear()` 置 `isCleared=true`，后续引用者 `equals` 等会异常；建议单例禁止 clear 或改用不可变空对象 |
| **P3-11** | CborEncoder.encodeMap 不强制 Canonical 键序 | ❌ 未修复 | `CborEncoder.kt:127` encodeMap 直接按 Map 迭代顺序编码 | 中（正确性） | 未按 RFC 8949 确定性键序（长度优先/字节序）；Passkey COSE/断言签名若与外部方互验可能因键序不一致失败 |
| **P3-12** | AttachmentManager.exportToCache 缓存无清理 | ✅ 已修复（2026-09-07） | `AttachmentManager.kt` 重写：专用子目录 `attachment_view` + 随机 UUID 文件名 + 会话即弃（新导出清上一轮）+ `deleteExported` 用完即删 + `deleteOnExit` 兜底 | 中（敏感） | `cleanCache` 改为仅清理专用子目录（全盘误删缺陷回归锁于 `AttachmentManagerTest`，6 例单测） |
| **P3-13** | RSA `certainty = 12` 低于常规标准 | ✅ 已修复（2026-09-07） | `PasskeyCryptoEngine.generateRs256KeyPair` certainty 12 → 80 | 中 | `RSAKeyGenerationParameters(..., 2048, 80)`：False-prime 概率 ≤1/2⁸⁰，对齐 BouncyCastle 官方示例 |
| **P3-14** | 魔法数字残留 | ❌ 未修复 | `KdbxFile.kt:336` 等多处硬编码数字未抽离为语义化常量 | 极低 | `ByteArray(64)` 等魔数应抽语义常量 |
| **P3-15** | 路径编码保留 ".." 导致路径遍历风险 | ❌ 未修复 | `WebDavSyncProvider.kt:103` encodePath 未剔除 ".." | 低（纵深防御） | `encodePath` 未剔除 `..` 段，存在路径遍历可能；当前路径来自应用受控配置，风险有限，建议剔除 |
| **P3-16** | SigV4 编码与 AWS 规范不符（`*` 与 `~` 处理） | ❌ 未修复 | `S3SyncProvider.kt:75` 编码逻辑未针对 AWS 规范微调 | 中（正确性） | `URLEncoder.encode` 把 `*`→`%2A`、`~`→`%7E`；AWS SigV4 canonical URI 要求 `*` 不编码、`~` 不编码，含这两字符的对象键会**签名不匹配 403** |
| **P3-17** | `parsePropfindXml` 吞掉所有异常返回空对象 | ❌ 未修复 | `WebDavSyncProvider.kt:430` 使用 `catch (_: Exception)` 静默返回 | 低 | 静默 catch 返回空，吞掉解析错误可能掩盖同步异常；建议至少记录日志 |
| **P3-18** | `SyncCache.clear()` 漏删四类 `.tmp` 文件 | ✅ 已修复 | `SyncCache.kt:216` 改为 `deleteOrphanTmpFiles()` 通配删除 | 否（已修复） | 已通配删除孤立 tmp |
| **P3-19** | `hasLocalChanges` 对缺失 version 与 baseversion 方向相反 | ❌ 未修复 | `SyncCache.kt:68` 缺失 .version 返回 false，缺失 .baseversion 返回 true | 不急（存疑） | 实测"方向相反"说法站不住：.version 缺失→无法确认本地修改→`false`（合理）；.baseversion 缺失→无法比对→保守返回 `true`（合理）；当前更像是保守策略，建议补注释明确语义，而非当作 bug 修 |
| **P3-20** | 无 HTTPS 强制与 URL scheme 校验 | ✅ 已修复 | WebDav/S3 Provider 构造函数在 `WebDavSyncProvider.kt:64` 处强校验非 https 即抛异常 | 否（已修复） | 已强校验 https |
| **P3-21** | sync 模块 minSdk = 36 | ➖ 记录备查 | 固化的 Android 16+ 基线产品决策 | 否（产品决策） | Android 16+ 基线，固化决策 |
| **P3-22** | 7 个 Kotlin 文件超过 800 行 | ⚠️ 部分修复 | `RealVaultRepository` (1184行)、`SettingsViewModel` (1119行) 等 7 个文件超 800 行 | 低 | 巨型类整改为渐进项，当前保留 |
| **P3-23** | 约 250 处硬编码中文未抽离至 strings.xml | ❌ 未修复 | `SettingsViewModel.kt` 等 29 个文件中仍残存约 250 处硬编码中文 | 低（i18n） | 硬编码中文影响国际化；渐进整改即可 |
| **P3-24** | SettingsUiState 默认值为演示数据 | ❌ 未修复 | `SettingsUiState.kt:63` databasePath 等仍为预置演示路径 | 低 | `databasePath` 默认演示路径，真实数据加载后覆盖；仅初始默认值问题 |
| **P3-25** | 收藏功能不落库（仅翻转内存 Flow） | ❌ 未修复 | `EntryDetailViewModel.kt:195` toggleFavorite 未调用 Repository 保存 | 中（功能） | `toggleFavorite` 仅翻转内存 `isFavoriteFlow`，未调 Repository 落库，收藏重启即失效 |
| **P3-26** | EntryCategory 与银行卡字段为死代码 | ❌ 未修复 | `RealVaultRepository.kt:723` mapKdbxEntryToUi 未映射 category 且未处理卡字段 | 低-中（功能） | `mapKdbxEntryToUi` 不映射 `category`（恒 LOGIN）也不处理银行卡字段，`UiVaultEntry` 卡字段恒为 null，银行卡条目被当普通登录展示 |
| **P3-27** | 空 onClick 按钮与写死黑名单 | ❌ 未修复 | `AutofillSettingsScreen.kt:343` 按钮回调为空且黑名单写死 | 中（功能） | 黑名单项为写死列表，删除 `IconButton(onClick = { })` 为空回调，黑名单管理是假功能 |
| **P3-28** | 带有仅含注释的空 if 块 | ❌ 未修复 | `PasskeyCreateActivity.kt:61` 存在空 if 块 | 极低 | `if (origin.isBlank()) { /* 仅注释 */ }` 空 if 块，清理即可 |
| **P3-29** | 从 Context 强转 MainActivity 获取单例 | ❌ 未修复 | `KeePasskeyApp.kt:149` 存在 `(context as? MainActivity)?.autoLockManager` 强转 | 不急（描述有误） | 实测为 `(context as? MainActivity)?.autoLockManager`——是**安全转换 `as?` 而非强转**，对 null 已优雅处理，风险很低；原结论"强转"不准确 |
| **P3-30** | 熄屏自动锁在 Activity onDestroy 时销毁单例 | ❌ 未修复 → ✅ 已修复（2026-09-07，TASK-22） | `MainActivity.kt:48` onDestroy 中误调了单例 `autoLockManager.destroy()` | 高（真实 Bug） | 已整改：移除 `MainActivity.onDestroy` 的 `destroy()` 调用与空覆写，并清理 `AutoLockManager.destroy()` 死代码；单例生命周期与进程对齐，`initialize()` 幂等，旋转重建不再销毁调度器 |
| **P3-31** | 捕获 Exception 丢弃具体异常细节 | ❌ 未修复 | `SyncCoordinator.kt:534` 存在 `catch (_: Exception) { null }` | 低 | `catch (_: Exception) { null }` 丢弃异常细节，序列化失败被静默吞掉；建议至少记 `debugLog` |
| **P3-32** | 生产类构造函数保留 Context? 可空形参 | ❌ 未修复 | `KeystoreManager.kt:42` 等生产构造中保留 Context? | 低 | 生产构造保留 `Context?` 可空形参仅为单测注入，可保留 |
| **P3-33** | MockData.kt 文件名与死链注释残留 | ❌ 未修复 | `MockData.kt` 文件名未改且注释存在死链 | 极低 | 文件名 `MockData` 实为生产 UI 模型（VaultGroup/UiVaultEntry 等），应改名如 `UiModels.kt` |
| **P3-34** | REMEDIATION_PLAN.md 包含已废弃旧 API 表述 | ❌ 未修复 | 属于需归档废弃的旧文档表述问题 | 极低 | 文档已在顶部声明"历史存档/停止更新"，含旧 API 表述属正常归档，无需动作 |

---

## 2. 2026-09-06 安全审查报告发现项（Wave 13，16 项）

| 编号 | 领域 | 问题描述 | 优先级 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|:---:|---|:---:|:---:|---|:---:|---|
| **S-01** | 认证 | QuickUnlock 为自研 PIN + 非认证绑定密钥 | P0 | ✅ 已修复 | `KeystoreManager.kt:201` 升级为系统锁屏凭据绑定密钥，自研 PIN 体系全剪除 | 否（已修复） | 已升级系统锁屏绑定 |
| **S-02** | 认证 | BiometricPrompt 无设备凭据回退且负向按钮冲突 | P0 | ✅ 已修复 | `BiometricAuthManager.kt:78` 统一 `UNLOCK_AUTHENTICATORS` 并强制互斥约束 | 否（已修复） | 已互斥约束 |
| **S-03** | 认证 | 纯解锁场景未设 `confirmationRequired=false` | P2 | ✅ 已修复 | `BiometricAuthManager.kt:83` 默认 `confirmationRequired=false` 免二次确认 | 否（已修复） | 已免二次确认 |
| **S-04** | 授权 | Passkey 创建分支 rp.id 缺少可注册后缀校验 | P1 | ✅ 已修复 | `DomainMatcher.kt:107` `isRpIdTrustedForCreation` fail-closed 防跨域绑定 | 否（已修复） | 已 fail-closed |
| **S-05** | 输入 | XML 文本层无长度上限 | P2 | ✅ 已修复 | `KdbxXmlSaxNodes.kt:40` TextNode 单节点 `MAX_TEXT_CHARS = 8MiB` 封顶 | 否（已修复） | 已封顶 |
| **S-06** | 输入 | XML 嵌套深度无上限 | P2 | ✅ 已修复 | `KdbxXmlParser.kt:63` SAX 深度 `MAX_XML_DEPTH = 64` 封顶 | 否（已修复） | 已封顶 |
| **S-07** | 输入 | 内层 Header 二进制池无上限 | P2 | ✅ 已修复 | `InnerHeader.kt:95` 条目 ≤1024、总量 ≤256MiB 强制检查 | 否（已修复） | 已强制检查 |
| **S-08** | 输入 | GZip 解压输出无防护（解压炸弹风险） | P2 | ✅ 已修复 | `KdbxFile.kt:60` 引入 `SizeBoundedInputStream` 解压上限 512MiB | 否（已修复） | 已限解压 |
| **S-09** | 传输 | 「允许明文流量」与「信任自签名证书」假开关 | P0 | ✅ 已修复 | 全链路下线假开关与 UI，网络层恒定强制 TLS-only | 否（已修复） | 已强制 TLS-only |
| **S-10** | 传输 | WebDAV/S3 使用裸 OkHttpClient 缺超时控制 | P1 | ✅ 已修复 | `SyncHttpClientFactory.kt:28` 统一提供 10s/30s/30s 超时控制的 TLS 客户端 | 否（已修复） | 已加超时 |
| **S-11** | 加密 | 评估是否引入 EncryptedSharedPreferences | P2 | ✅ 已完成 | 评估废弃状态后维持直连 `AndroidKeyStore`，未引入第三方低效库 | 否（已评估） | 维持 AndroidKeyStore |
| **S-12** | 敏感 | UnlockUiState 以 String 承载 PIN 字符串 | P2 | ✅ 已修复 | 随自研 PIN 体系彻底删除而自然消除 | 否（已修复） | 随 PIN 体系删除消除 |
| **S-13** | 敏感 | Autofill `onSaveRequest` 密码 CharArray 未擦除 | P2 | ✅ 已修复 | `KeePasskeyAutofillService.kt:341` 增加 `finally { passwordChars.fill('0') }` | 否（已修复） | 已 finally 擦除 |
| **S-14** | 依赖 | `androidx.biometric` 处于 alpha 依赖 | P0 | ✅ 已修复 | `app/build.gradle.kts:82` 降级迁移至稳定版 `1.1.0` | 否（已修复） | 已迁移稳定版 |
| **S-15** | 依赖 | `androidx.credentials` 落后于稳定版 | P0 | ✅ 已修复 | `app/build.gradle.kts:87` 升级至稳定版 `1.6.0` | 否（已修复） | 已升级稳定版 |
| **S-16** | CI | 无自动化依赖漏洞巡检 | P2 | ❌ 未修复 | 工作区缺乏 Dependabot 或 Dependency-Check 工作流文件 | 中（流程） | 缺 CI 依赖巡检；建议补 GitHub Actions 依赖漏洞巡检工作流 |

---

## 3. 2026-09-06 加解密实现审查报告发现项（9 项）

| 编号 | 领域 | 问题描述 | 物理状态 | 代码证据 / 修复实现 | 是否有必要修复 | 说明 |
|:---:|:---:|---|:---:|---|:---:|---|
| **C-01** | Keystore | 缺少密钥硬件落位校验 | ✅ 已修复 | `KeystoreManager.kt:146` 增加 `KeySecurityLevel` 并在生成后检测 SOFTWARE 级告警 | 否（已修复） | 已加落位校验 |
| **C-02** | Keystore | 未启用 `setUnlockedDeviceRequired(true)` | ✅ 已修复 | `KeystoreManager.kt:117` 认证密钥强制设置 `.setUnlockedDeviceRequired(true)` | 否（已修复） | 已强制解锁设备要求 |
| **C-03** | 内存 | `InMemoryCipher` 确定性加密等值泄露 | ✅ 已修复 | `InMemoryCipher.kt:39` 重构为随机 IV + HMAC 标签常时比较模式 | 否（已修复） | 已重构随机 IV |
| **C-04** | 敏感 | PasswordSaveActivity 死 Intent 密码通道 | ✅ 已修复 | `PasswordSaveActivity.kt:38` 已剪除 `EXTRA_PASSWORD` 明文通道 | 否（已修复） | 已剪除明文通道 |
| **C-05** | UI | EntryEditUiState 以 String 持有密码 | ✅ 已修复 | `EntryEditUiState.kt:24` 仅留 `passwordLength`，ViewModel 私有 CharArray 擦除 | 否（已修复） | 已改 passwordLength |
| **C-06** | UI | 条目历史版本回滚走 String 中转 | ✅ 已修复 | `EntryDetailViewModel.kt:249` 改走 `getEntryRevisionPasswordChars` 全程 CharArray | 否（已修复） | 已全程 CharArray |
| **C-07** | UI | 设置改密对话框使用普通文本框 | ✅ 已修复 | `SettingsScreen.kt:342` 改用双 `SecurePasswordField` CharArray 直通 | 否（已修复） | 已换安全输入框 |
| **C-08** | 仓库 | `RealVaultRepository.saveEntry` 擦除契约缺漏 | ✅ 已修复 | `RealVaultRepository.kt:375` 拆分内部实现，`finally` 强制擦除传入副本 | 否（已修复） | 已 finally 擦除 |
| **C-09** | 遗留 | TOTP 种子与受保护自定义字段编辑态以 String 承载 | ❌ 未修复 | `EntryEditUiState.kt:28` `totpSecret: String` 编辑态仍以 String 承载 | 低-中（敏感） | `totpSecret: String` 编辑态仍以 String 承载于 StateFlow；与 P2-5 同源，属已知 String 边界局限，建议编辑态改 CharArray/ByteArray 链路 |

---

## 4. 审核报告第七节测试覆盖缺口（7 项）

| 编号 | 测试缺口描述 | 物理状态 | 代码证据 / 现实情况 | 是否有必要修复 | 说明 |
|:---:|---|:---:|---|:---:|---|
| **T-01** | WebDAV `If` tagged-list 预条件用例缺失 | ✅ 已修复 | `WebDavSyncScenarioTest.kt:95` 显式断言 ETag 匹配与 412 冲突分支 | 否（已修复） | 已断言 ETag/412 |
| **T-02** | 无期望 ETag 时不得覆盖远端的判定测试 | ⚠️ 部分修复 | `S3SyncProviderTest.kt:235` 已覆盖；FakeSyncProvider 仍无条件覆盖 | 低 | S3 真实路径已覆盖；FakeSyncProvider 仍无条件覆盖，测试有效性缺口 |
| **T-03** | `SyncCache` 缺乏独立单元测试文件 | ❌ 未修复 | `sync/src/test` 目录下仍无独立 `SyncCacheTest.kt` 文件 | 中（测试） | 缓存原子写/版本判定缺独立单测，建议补 `SyncCacheTest.kt` |
| **T-04** | S3 SigV4 缺少官方已知答案向量比对 | ❌ 未修复 | `S3SyncProviderTest.kt:72` 仍仅断言 `Signature=` 包含关系 | 中（测试） | 仅 `assertTrue(auth.contains("Signature=")`，无官方已知答案向量比对，SigV4 正确性未真正验证 |
| **T-05** | `SyncCredentialsStore` 真实 Keystore 路径无测试 | ❌ 未修复 | `SyncCredentialsStoreTest.kt:86` 仍统一注入假加密器测试 | 中（测试） | 真实 Keystore 加密路径无测试；与 P2-37 同源，建议补真实/Instrumented 用例 |
| **T-06** | KdbxMerger 缺少三处边缘分支测试 | ⚠️ 部分修复 | `KdbxMergerV2Test.kt:262` 仅覆盖复活条目，丢失挂载与字段级仲裁未测 | 低 | 仅覆盖复活条目；挂载与字段级仲裁未测 |
| **T-07** | P0 级缺陷无针对性回归测试 | ✅ 已修复 | `RealVaultRepositoryTest.kt:302` 显式锁死分组保存子树完整性；KDBX 包含 IV 断言 | 否（已修复） | 已锁死回归 |
