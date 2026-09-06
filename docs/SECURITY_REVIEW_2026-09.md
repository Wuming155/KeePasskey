# KeePasskey 安全审查报告（2026-09）与 Wave 13 整改存档

> 审查方法：以 Context7 官方文档库（`/websites/developer_android`、`/androidx/androidx`、`/websites/square_github_io_okhttp`）
> 与 Android 官方安全文档（密钥库系统、创建通行密钥、OkHttp HTTPS 特性）为对照基准，
> 对认证、授权、输入验证、数据加密、敏感信息处理、依赖安全六个领域逐项审查。
> 整改代号：**Wave 13（安全审计整改）**——编号顺延自既有 Wave 1-12 交付序列。

## 一、发现清单与处置总表（16 项）

| # | 领域 | 发现 | 优先级 | 处置 |
|---|------|------|--------|------|
| 1 | 认证 | QuickUnlock 为「自研 4-6 位应用 PIN + PBKDF2 校验器 + 非认证绑定密钥」，爆破空间仅 10⁴-10⁶，且密钥放弃硬件认证隔离 | P0 | ✅ 改为设备锁屏凭据绑定密钥（AUTH_BIOMETRIC_STRONG \| AUTH_DEVICE_CREDENTIAL），自研 PIN 体系整体删除 |
| 2 | 认证 | BiometricPrompt 仅 BIOMETRIC_STRONG，无设备凭据回退；且 setNegativeButtonText 与设备凭据互斥（官方约束）未被覆盖 | P0 | ✅ 认证器集合参数化 + 统一 UNLOCK_AUTHENTICATORS + 官方互斥约束落地 |
| 3 | 认证 | 纯解锁场景未设置 confirmationRequired=false | P2 | ✅ 参数化并默认免确认 |
| 4 | 授权 | Passkey 创建分支 rp.id 直接取自 requestJson，缺省才回退 origin，未做「可注册后缀」绑定 | P1 | ✅ 新增 DomainMatcher.isRpIdTrustedForCreation，创建分支 fail-closed |
| 5 | 输入验证 | XML 文本层无长度上限（唯一无限制解析层） | P2 | ✅ TextNode 单节点 8 Mi 字符封顶 |
| 6 | 输入验证 | XML 嵌套深度无上限（含 IgnoredNode 未知子树） | P2 | ✅ SAX 深度封顶 64 层 |
| 7 | 输入验证 | 内层 Header 二进制池条目数与总尺寸无上限 | P2 | ✅ 条目 ≤1024、总量 ≤256 MiB 双封顶 |
| 8 | 输入验证 | GZip 解压输出无防护（压缩比炸弹） | P2 | ✅ SizeBoundedInputStream 解压输出 ≤512 MiB |
| 9 | 数据加密/传输 | 「允许明文流量」开关为空实现（设置/UI 已接线，无任何网络层消费者）；「信任自签名证书」同为无消费者假开关且语义不安全 | P0 | ✅ 按用户安全决策整体下线：删除开关/UI/状态/字符串，同步客户端恒定 TLS-only |
| 10 | 数据加密/传输 | WebDAV/S3 使用裸 `OkHttpClient()`：无超时、无证书锁定能力 | P1 | ✅ SyncHttpClientFactory（TLS-only + 显式超时 10s/30s/30s + 可选 CertificatePinner） |
| 11 | 数据加密 | 轻量敏感数据自研加密表面积大 | P2 | ✅ 评估结论：AndroidX Security Crypto（EncryptedSharedPreferences）官方已弃用（建议直连 AndroidKeyStore），维持现有直连 Keystore 方案并统一收敛封印存储，不引入新依赖 |
| 12 | 敏感信息 | UnlockUiState.quickUnlockPin 以 String 承载 PIN | P2 | ✅ 随 PIN 体系删除而消除；全局审计确认其余 readString 均为「按需单条解密」设计（F2）或平台 API 边界必需 |
| 13 | 敏感信息 | Autofill onSaveRequest 密码 CharArray 用毕未清零 | P2 | ✅ finally 显式清零；填充边界（AutofillValue/PasswordCredential 强制 String）留档为平台约束 |
| 14 | 依赖安全 | androidx.biometric:1.2.0-alpha05（alpha 渠道） | P0 | ✅ 迁移至稳定渠道 1.1.0（官方发布页：1.2.x/1.4.x 均为 alpha，稳定渠道最新为 1.1.0） |
| 15 | 依赖安全 | androidx.credentials:1.5.0 落后稳定版 | P0 | ✅ 升级 1.6.0（isConditional 传播修复） |
| 16 | 供应链 | 无自动化依赖漏洞巡检 | P2 | 📋 已在 AGENTS.md 记录建议（Dependabot / OWASP Dependency-Check）；zxing 保持 4.3.0 并列为待办 |

## 二、官方文档核验结论（落地硬约束）

1. **认证器集合一致性**（Android Keystore 文档）：解锁加密操作时请求的认证器集合必须与密钥生成时一致——
   密钥以 `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)` 生成，
   解封必须以 `BiometricManager.Authenticators.BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 发起（`BiometricAuthManager.UNLOCK_AUTHENTICATORS`）。
2. **负向按钮互斥**（BiometricPrompt 文档）：允许 DEVICE_CREDENTIAL 时系统以「使用锁屏凭据」入口取代负向按钮，
   此时调用 `setNegativeButtonText` 属于错误用法——统一快速解锁弹窗不再设置负向文案。
3. **setInvalidatedByBiometricEnrollment**：对含 AUTH_DEVICE_CREDENTIAL 的密钥被系统忽略（锁屏凭据变更不失效），代码注释如实声明。
4. **密钥授权不可变**（Keystore 文档）：既有仅生物识别授权的 per-database 密钥经 `KeyInfo.getUserAuthenticationType()`
   探测后自动删除重建，旧封印凭据失效（fail-safe 迁移，对齐 Wave 11 H4 模式）。
5. **OkHttp 证书锁定警示**（OkHttp 官方文档）：锁定会限制服务端证书轮换（"Do not use certificate pinning
   without the blessing of your server's TLS administrator"）——证书锁定实现为**用户显式可选**（WebDAV 设置项，每行
   `host=sha256/公钥哈希`），默认不锁定；connectionSpecs 恒定排除 CLEARTEXT。
6. **AndroidX Security Crypto 弃用**（androidx 官方源码）：EncryptedSharedPreferences/MasterKeys 已弃用，
   官方建议直接使用 AndroidKeyStore——Wave 13 不引入该库，统一封印存储沿用直连 Keystore 模式
   （`BiometricCredentialStorage` 为唯一封印存储，java.util.Base64 可测化，启动期清理遗留 QuickUnlock 数据）。
7. **rp.id 绑定**（创建通行密钥官方文档，2026-06-03 版）：rp.id 必须是调用方 origin 的可注册后缀；
   浏览器委派经 `CallingOriginResolver`（特权白名单 fail-closed）解析 origin 后做点号边界校验；
   普通应用（android:apk-key-hash origin）无 web 域可绑定，仅要求 rp.id 为可注册域，归属由 RP 服务端 DAL 裁决。

## 三、Wave 13 整改明细

### 认证体系（P0）
- `KeystoreManager`：新增 `getOrCreateDeviceCredentialKey`/`generateNewDeviceCredentialKey`（StrongBox 优先、TEE 回退、
  旧密钥探测迁移）；删除 `getOrCreateUnauthenticatedKey`/`initSealCipher`/`initUnsealCipher`/`containsKey`；
  `QUICK_UNLOCK_KEY_ALIAS` 降级为 `LEGACY_QUICK_UNLOCK_KEY_ALIAS`（仅供清理）。
- `QuickUnlockPinStore` 整体删除（PBKDF2 120k 校验器、盐、指数退避熔断、自研封印）；
  `BiometricCredentialStorage` 收敛为唯一统一封印存储并清理遗留 prefs 与遗留 Keystore 别名。
- `BiometricAuthManager`：`authenticate` 参数化（authenticators/confirmationRequired/negativeButtonText 互斥）；
  `canAuthenticate` 支持认证器集合重载；封印/解封走设备凭据绑定密钥。
- `UnlockViewModel`/`UnlockUiState`/`UnlockScreen`：删除 PIN 登记/校验/熔断全流程与 `quickUnlockPin: String` 状态；
  QuickUnlock 卡片收敛为统一快速解锁单入口；strings 清理 11 项、更新主按钮文案。
- 效益：快速解锁门槛由自选应用 PIN（10⁴-10⁶）提升到系统锁屏凭据强度，密钥授权可由安全硬件强制执行；
  快速解锁路径删除一次 PBKDF2 120k 派生开销。

### 传输加固（P0/P1）
- sync 模块新增 `SyncNetworkOptions`（纯数据契约：超时 + 可选 pinnedHosts，无明文字段）与
  `SyncHttpClientFactory`（connectionSpecs = RESTRICTED_TLS + MODERN_TLS，显式排除 CLEARTEXT；超时 10s/30s/30s；
  可选 CertificatePinner）。
- Provider 网络选项化：`WebDavSyncProvider`/`S3SyncProvider` 接收 `networkOptions` 并在内部构建客户端
  （app 层不触碰 okhttp 类型，维持 sync 不依赖 app 的单向依赖）；保留测试注入口（HTTP 回环）。
- app 层：`SyncCoordinator.resolveProvider` 组装选项传入；WebDAV 凭据模型与持久化扩展 `certPins`
  （`webdav_cert_pins` 键）；`buildNetworkOptions` 解析（非法行忽略并留痕）。
- 「允许明文流量」与「信任自签名证书」假开关整体下线（设置模型/UiState/开关 UI/回调/字符串全链路删除）。

### 授权与输入验证（P1/P2）
- `DomainMatcher.isRpIdTrustedForCreation`：创建分支 fail-closed；多级公共后缀最小集扩充
  （+co.kr/or.kr/ne.kr/re.kr/com.my/co.th/com.ph/com.vn/co.il/com.sa/com.ng/com.co/com.pe）。
- 解析防线：`TextNode.MAX_TEXT_CHARS`（8 Mi 字符）、`KdbxXmlParser.MAX_XML_DEPTH`（64）、
  `InnerHeader.MAX_BINARY_POOL_ENTRIES`（1024）/`MAX_BINARY_POOL_TOTAL_BYTES`（256 MiB）、
  `KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES`（512 MiB）+ `SizeBoundedInputStream`。
  所有上限为 O(1) 常量检查，正常库远低于各界，无热路径回归。

### 敏感信息卫生（P2）
- GCM IV 唯一性回归锁（同密钥连续 16 次 init IV 全新）。
- `KeePasskeyAutofillService.onSaveRequest` 密码 CharArray finally 清零。
- String 残留审计结论：`UnlockUiState.quickUnlockPin` 已随体系删除；其余 `readString()` 均为
  Wave 7 F2「按需单条解密」设计或平台 API 边界必需（AutofillValue.forText / PasswordCredential 强制 String），
  均不进入日志/StateFlow/成员变量。

### 依赖治理（P0）
- `androidx.biometric:biometric:1.2.0-alpha05 → 1.1.0`（稳定渠道最新；1.4.0 无稳定版，1.2.x 已停更）。
- `androidx.credentials:credentials:1.5.0 → 1.6.0`（稳定）。
- OkHttp 保持 4.12.0；zxing-android-embedded 保持 4.3.0（迁移 ML Kit 列为待办）。

## 四、新增/变更测试

- `app/src/test`：`BiometricCredentialStorageTest`（5 用例：往返/按库清理/损坏 fail-safe/clearAll/未登记）、
  `DomainMatcherTest`（9 用例：点号边界/公共后缀/创建绑定方向/F1、F4 回归锁）、
  `SecurityTest` 新增 GCM IV 唯一性用例、删除 QuickUnlock 熔断用例、UnlockViewModelTest 适配构造签名。
- `sync/src/test`：`SyncHttpClientFactoryTest`（6 用例：TLS-only/超时/锁定装配）、
  `WebDavSyncProviderTest` 六处构造显式注入回环客户端。
- `database/src/test`：`KdbxParsingResourceLimitsTest`（6 用例：文本上限/深度/池条目/护栏）。
- 回归基线：全模块 `test` BUILD SUCCESSFUL（原 315 用例基线不回退，新增 26 用例）。

## 五、遗留待办

1. S3 端点证书锁定 UI 接线（sync 契约已支持，仅 WebDAV 已接 UI）。
2. DomainMatcher 公共后缀为内置最小子集——完整 PSL 集成（如引入 Guava InternetDomainName 或内嵌 PSL 数据）待评估。
3. zxing-android-embedded → CameraX + ML Kit Barcode Scanning 迁移评估。
4. CI 供应链巡检（GitHub Dependabot / OWASP Dependency-Check）接入。
