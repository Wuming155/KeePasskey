# KeePassDX 架构分析

> 分析对象：`参考项目/KeePassDX-master`（Kunzisoft / KeePassDX，v4.5.2，GPL-3.0）
> 分析方式：基于对实际源码的逐文件阅读（非记忆/推测）。文中所有类名与文件路径均来自真实浏览。
> 用途：为 KeePasskey（Kotlin + Compose + Hilt + Coroutines/Flow）的实现提供架构借鉴。**本文档只做架构描述与分析，不复制项目代码入库。**

---

## 目录

1. [项目概况与总体印象](#1-项目概况与总体印象)
2. [Gradle 多模块结构与构建配置](#2-gradle-多模块结构与构建配置)
3. [架构总览图](#3-架构总览图)
4. [crypto 模块：加密引擎与 JNI 边界](#4-crypto-模块加密引擎与-jni-边界)
5. [database 模块：数据模型、kdb 读写与会话生命周期](#5-database-模块数据模型kdb-读写与会话生命周期)
6. [app 模块：UI 体系、服务、凭据提供与设置](#6-app-模块ui-体系服务凭据提供与设置)
7. [关键数据流：从选择数据库到保存的端到端调用链](#7-关键数据流从选择数据库到保存的端到端调用链)
8. [对 KeePasskey 的借鉴要点](#8-对-keepasskey-的借鉴要点)
9. [关键文件索引表](#9-关键文件索引表)

---

## 1. 项目概况与总体印象

KeePassDX 是目前 Android 上对 KeePass 系列格式（`.kdb` v3 / `.kdbx` v3.1 / v4 / v4.1）兼容最完整的开源密码管理器之一。其源码以 **Kotlin 为主 + 少量 Java 遗留 + C/JNI（AES-KDF 与 Argon2）** 构成。

几个决定全局形态的关键事实（均来自实际构建脚本与清单文件）：

| 维度 | 事实 |
|------|------|
| 技术栈 | Android View 体系（XML + Fragment + Material Components），**无 Compose**，**无 Hilt/DI 框架**，无 Navigation 组件 |
| 语言 | Kotlin 2.0.0 为主，遗留少量 Java（如 `credentialprovider/magikeyboard/Keyboard.java`） |
| SDK | minSdk 19 / compileSdk 36 / targetSdk 36，Java 17 |
| 加速器 | NDK 25.2.9519653 + CMake，JNI 提供 AES-KDF 与 Argon2 原生实现 |
| 会话模型 | 数据库会话由**前台服务**（`DatabaseTaskNotificationService`）持有，UI 通过 Binder + Broadcast 双通道与其通信 |
| 本地存储 | app 模块内嵌 Room（数据库文件历史 + 加密凭据封装），schema 导出在 `app/schemas/` |
| 发行形态 | 两个 flavor：`libre`（F-Droid 等）与 `free`（闭源商店），通过 `STYLES_DISABLED` 等 BuildConfig 字段区分 |
| 通行密钥 | 基于 **Android 14 Credential Provider API**（`CredentialProviderService`），自实现 WebAuthn CBOR / 签名（BouncyCastle） |

整体评价：**分层意图清晰（crypto → database → app 单向依赖），但 app 模块内部积累了大量巨型类与回调式耦合**；其对 kdbx 格式细节、密钥派生、凭据封装、Credential Provider 的处理极具参考价值，而其 UI/服务层的组织方式更多是"反面教材 + 部分可移植思想"。

---

## 2. Gradle 多模块结构与构建配置

### 2.1 模块清单（`settings.gradle.kts`）

```text
include(":app")
include(":database")
include(":crypto")
include(":icon-pack")
include(":icon-pack:classic")
include(":icon-pack:material")
```

共 **6 个模块**。注意：仓库根目录虽存在 `src/libre/` 目录，但它只是 fastlane 元数据（图片），**不是** Gradle 模块。

依赖方向（各模块 `build.gradle.kts` 中实测）：

```text
app ──> database ──> crypto            （api(project(":crypto"))，crypto 对 database 暴露为 api）
app ──> icon-pack ──> icon-pack:classic / icon-pack:material
```

- `database/build.gradle.kts`：`api(project(":crypto"))` —— 因此 app 中可直接使用 `com.kunzisoft.encrypt.*`。
- `crypto` 仅依赖 `bouncycastle-pkix`（`bcpkix-jdk18on:1.84`），**不依赖任何 Android UI 库**，是纯"能力层"。
- `icon-pack` 是纯资源 + 极少量代码的动态图标包模块（见 2.4）。

### 2.2 版本目录（`gradle/libs.versions.toml`）

关键版本：AGP 8.13.2、Kotlin 2.0.0、KSP 2.0.0-1.0.21、Coroutines 1.8.1、Room 2.6.1、Biometric 1.1.0、androidx.credentials 1.2.2、BouncyCastle 1.84、joda-time 2.14.1、commons-io 2.21.0、nbvcxz 1.5.1（密码强度估算）、chroma（Kunzisoft 自维护的 AndroidClearChroma，主题取色）。

插件全部经 `alias` 引用：`android-application` / `android-library` / `kotlin-android` / `kotlin-parcelize` / `ksp`。Room 用 **KSP**（非 kapt）。

### 2.3 各模块 build 配置要点

**根 `build.gradle.kts`**：只声明插件版本 + 注册 `clean` 任务；`gradle.properties` 开启 configuration-cache、androidx、jetifier。

**`crypto/build.gradle.kts`**：

- namespace `com.kunzisoft.encrypt`，`minSdk 19`，`compileSdk 36`；
- `externalNativeBuild { cmake { path("src/main/jni/CMakeLists.txt") } }`，NDK 25.2.9519653；
- `cppFlags("-Wl,-z,max-page-size=16384")` —— 手动为 NDK<27 打 16KB 页面对齐补丁（这是 2024 后新设备兼容性的实用技巧）；
- 依赖仅 `bouncycastle-pkix`。

**`database/build.gradle.kts`**：

- namespace `com.kunzisoft.keepass.database`，`minSdk 19`；
- 依赖 joda-time、commons-io、`api(project(":crypto"))`；
- `kotlin-parcelize`（大量模型需要跨 Activity/Service 传递）。

**`app/build.gradle.kts`**：

- namespace `com.kunzisoft.keepass`，`applicationId com.kunzisoft.keepass`，`versionCode 45200 / versionName 4.5.2`；
- `minSdk 19 / targetSdk 36`，`multiDexEnabled true`；
- **无 compose 配置块**（证实 View 体系）；
- `flavorDimensions "version"` + `productFlavors`：`libre` / `free`，差异为 applicationId 后缀、`BUILD_VERSION`、`CLOSED_STORE`、`STYLES_DISABLED`（free 版禁用 8 个主题）、`ICON_PACKS_DISABLED`，及 Google 备份 API key；
- `ksp { arg("room.schemaLocation", ...) }` —— Room 只在 app 模块；
- 依赖：androidx（appcompat/preference/constraintlayout/viewpager2/documentfile/biometric/media/fragment-ktx/lifecycle-process+lifecycle-service）、material、Room、**androidx.credentials**（Credential Manager 客户端侧依赖）、autofill、joda-time、chroma、taptargetview、commons-io、nbvcxz、tokenautocomplete、`project(":database")`、`project(":icon-pack")`；
- `androidResources { generateLocaleConfig = true }`（自动 per-app language）。

### 2.4 icon-pack 模块

`icon-pack/src/main/java/com/kunzisoft/keepass/icon/IconPack.kt` 通过**按命名规范（`{stringId}_{%2d}_32dp`）动态检索另一个包的资源**来构造标准图标池，classic / material 两个子模块各是一组符合命名的 drawable 资源 + `resource_id` 字符串。app 侧由 `icons/IconDrawableFactory.kt`、`icons/IconPackChooser.kt` 消费。这是"插件式图标包"的轻量实现：不需要依赖注入框架，仅靠资源命名约定。

---

## 3. 架构总览图

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                             app 模块（UI + 服务）                          │
│                                                                           │
│  Activity/Fragment 层        ViewModel 层            前台服务层（会话宿主）  │
│  FileDatabaseSelectActivity  DatabaseViewModel       DatabaseTaskNotificationService
│  MainCredentialActivity      MainCredentialViewModel   │ (持有 ContextualDatabase)
│  GroupActivity ──GroupFragment                        │  ├─ ActionRunnable 子类
│  EntryActivity / EntryEditActivity                    │  │   (Load/Save/Merge/...)
│  SettingsActivity / AboutActivity                     │  └─ ActionTaskBinder
│                                                       │      (Binder 监听者回调)
│  CredentialProvider/特殊输入模式                        │
│  KeeAutofillService(Autofill)  MagikeyboardService(IME)│
│  PasskeyProviderService(CredProvider, API34+)          │
│  PasswordLauncher/PasskeyLauncher/EntrySelection...    │
│                                                       │
│  app 内 Room：AppDatabase（文件历史 + 加密凭据封装）      │
│  TimeoutHelper(AlarmManager 自动锁)  DeviceUnlockManager(KeyStore+生物识别)
└──────────────┬──────────────────────────────────────────┘
               │  依赖（单向）
┌──────────────▼──────────────────────────────────────────┐
│                    database 模块（格式 + 领域模型）        │
│  element/Database（门面，1348 行）                        │
│    ├─ element/database/DatabaseKDB  (KDB v3,  NodeIdInt) │
│    └─ element/database/DatabaseKDBX (KDBX,     NodeIdUUID)│
│         └─ DatabaseVersioned<TIME,ID,G,E> 泛型基类        │
│  element/: Group/Entry/Attachment/CustomData/Tag/Icon/   │
│            Template/MasterCredential/CompositeKey/...    │
│  file/input/  DatabaseInputKDB / DatabaseInputKDBX       │
│  file/output/ DatabaseOutputKDB / DatabaseOutputKDBX     │
│  crypto/: EncryptionAlgorithm + CipherEngine(AES/Twofish/│
│           ChaCha20) + kdf/(KdfEngine→AesKdf/Argon2Kdf)   │
│  stream/: HashedBlock/HmacBlock 流    merge/DatabaseKDBXMerger
└──────────────┬──────────────────────────────────────────┘
               │  api 依赖（单向）
┌──────────────▼──────────────────────────────────────────┐
│                  crypto 模块（纯加速能力，namespace encrypt）│
│  CipherFactory（AES 原生优先/BC 兜底、Twofish、ChaCha20）   │
│  AESTransformer（AES-KDF：native 优先，JVM ECB 兜底）       │
│  Argon2Transformer ──> NativeArgon2KeyTransformer(JNI)    │
│  NativeLib（loadLibrary("aes") + loadLibrary("argon2")）  │
│  HashManager / StreamCipher / Signature(Ed25519·ES256·RS256)
│  jni/: aes_jni.c, argon2_jni.c + CMakeLists（argon2 参考实现源码内嵌）
└──────────────────────────────────────────────────────────┘
```

一个贯穿全项目的关键设计：**"能力"与"格式"与"产品"三层严格分离** —— crypto 只提供原语（KDF、分组密码、签名），database 只关心 kdb/kdbx 字节与领域树，app 只做编排与系统集成。跨层传递的数据全部是字节数组/纯数据类，不泄露 Cipher 对象。

---

## 4. crypto 模块：加密引擎与 JNI 边界

包名 `com.kunzisoft.encrypt`（注意：模块叫 crypto，包名却是 encrypt，检索代码时容易混淆）。

### 4.1 原生库装载：`NativeLib.kt`

单例 object，`init()` 依次 `System.loadLibrary("aes")` / `System.loadLibrary("argon2")`，失败则 `loadSuccess=false`。全项目所有原生调用前都先探活（如 `CipherFactory.getAES` 判断 `NativeLib.loaded()`）。这是一个简单但重要的模式：**原生能力降级路径从一开始就内建**。

### 4.2 分组密码工厂：`CipherFactory.kt`

| 方法 | 变换 | 实现 |
|------|------|------|
| `getAES` | `AES/CBC/PKCS5Padding` | 优先 `Cipher.getInstance(transformation, AESProvider())`（注册进 JCA 的**原生 AES Provider**），异常回退平台默认 |
| `getTwofish` | `Twofish/CBC/PKCS7PADDING`（或 NoPadding 兼容模式） | BouncyCastle（类初始化时 `removeProvider`+`addProvider` 强制替换 BC，规避系统旧版 BC 冲突） |
| `getChacha20` | `ChaCha7539`（KDBX 用 7539 轮数） | BouncyCastle |

配套文件：`crypto/src/main/java/com/kunzisoft/encrypt/aes/AESProvider.kt`（把 `NativeAESCipherSpi` 注册为 JCA Provider）与 `aes/NativeAESCipherSpi.java`（303 行，JCA CipherSpi 的原生实现）。也就是说 **KDBX 主体的 AES-CBC 加解密本身也走 JNI**，而不只是密钥派生。

### 4.3 KDF（密钥派生）：JVM 兜底 + 原生优先

**AES-KDF**（`aes/AESTransformer.kt`，97 行，短小精悍）：

- `transformKey(seed, key, rounds)`：优先 `NativeAESKeyTransformer.nTransformKey`（JNI，在 C 层完成全部轮次加密 + SHA-256），异常时回退 `transformKeyInJVM`；
- JVM 兜底实现即教科书式 KeePass 变换：`AES/ECB/NoPadding` 用 seed 作密钥对 key 连续加密 rounds 次，再 `HashManager.sha256`。`rounds` 用 `ULong` 表示。

**Argon2**（`argon2/Argon2Transformer.kt` + `argon2/NativeArgon2KeyTransformer.java` + `jni/argon2/`）：

- 无 JVM 兜底（Argon2 纯 JVM 实现太慢，项目直接依赖原生）；
- `jni/argon2/` 内嵌了 **argon2 官方参考实现源码**（`src/argon2.c`、`opt.c`、`ref.c`、blake2b 等）+ `argon2_jni.c` 桥接，CMakeLists 编译为 `libargon2.so`；
- `Argon2Type.kt`：ARGON2_I / D / ID 三型枚举。

### 4.4 辅助原语

- `HashManager.kt`（214 行）：SHA-256/512、HMAC-SHA-256、随机数生成 —— KDBX4 的 HMAC 块流依赖它；
- `StreamCipher.kt`：对 BouncyCastle `StreamCipher` 的薄包装（`processBytes(ByteArray): ByteArray`），database 模块用它实现保护字段的 ChaCha20/Salsa20 流加密；
- `Base64Helper.kt`、`Signature.kt`（见 4.5）。

### 4.5 `Signature.kt`（448 行）：通行密钥的密码学底座

这是 crypto 模块中最"新"的部分，直接为 Credential Provider 服务：

- `sign(privateKeyPem: CharArray, message)`：从 **PEM CharArray**（未落地 String）解析私钥（`PEMParser`），按算法分发 `SHA256withECDSA` / `SHA256withRSA` / `Ed25519`，签名后**显式 `fill('0')` 清零输入缓冲**；
- `generateKeyPair(keyTypeIdList)`：按 WebAuthn 优先级生成 ES256（secp256r1）/ RS256（2048）/ Ed25519 密钥对；
- `convertPublicKeyToMap`：把公钥编码为 **COSE Map**（IANA COSE 标签，实现 attestationObject 所需）；
- 私钥 PEM 与 KeePassXC 的 PKCS#8 v1 格式兼容（`org.bouncycastle.pkcs8.v1_info_only` 系统属性切换）；
- Android 侧工具：`SigningInfo.getAllFingerprints()` 计算 APK 签名 SHA-256 指纹、`fingerprintToUrlSafeBase64` 把指纹转成 Android App Origin（Relying Party 标识），`SIGNATURE_DELIMITER = "##SIG##"` 多签名分隔。

### 4.6 与 kdbx 格式的衔接方式

crypto 模块**完全不知道 kdbx 存在**。衔接由 database 模块的 `EncryptionAlgorithm`/`KdfEngine` 完成：database 定义格式 UUID（如 AES 的 `{31C1F2E6-BF71-4350-BE58-05216AFC5AFF}`），选择引擎，再把字节交给 crypto。这个"**UUID→策略对象→原语**"的两跳设计让 crypto 保持零业务知识，值得 KeePasskey 原样保留。

---

## 5. database 模块：数据模型、kdb 读写与会话生命周期

### 5.1 版本化继承体系：门面 + 泛型基类

核心骨架（`database/src/main/java/com/kunzisoft/keepass/database/element/`）：

```text
Database（门面，1348 行）                    ← app 只见此类
 ├── mDatabaseKDB : DatabaseKDB?            ← KDB v3
 └── mDatabaseKDBX: DatabaseKDBX?           ← KDBX（两者互斥）
       └── DatabaseVersioned<UUID, UUID, GroupKDBX, EntryKDBX>
             └── DatabaseVersioned<Time, ID, Group, Entry>（抽象泛型基类）
```

- `Database` 是**典型的门面/适配器**：所有属性 getter 都写成 `mDatabaseKDB?.xxx ?: mDatabaseKDBX?.xxx ?: default`；同时用 `allowXxx` 布尔属性暴露"该格式是否支持此特性"（`allowCustomIcons = mDatabaseKDBX != null`、`allowOTP`、`allowTags`、`allowConfigurableRecycleBin`、`allowMultipleAttachments`……）。**UI 因此可以无分支地处理两种格式**，这是很值得借鉴的手法。
- `NodeId` 体系：`NodeIdInt`（KDB）与 `NodeIdUUID`（KDBX），由 `NodeVersionedInterface` / `NodeKDBInterface` / `NodeKDBXInterface` 等接口约束（`element/node/`）。
- `Group` / `Entry` 外壳类同样内含 `groupKDB/groupKDBX` / `entryKDB/entryKDBX` 双视图（`element/Group.kt`、`element/Entry.kt`、`group/GroupVersioned.kt`、`entry/EntryVersioned.kt`）。

### 5.2 领域模型要点

| 模型 | 文件 | 说明 |
|------|------|------|
| Entry 字段 | `element/Field.kt`、`element/entry/FieldReferencesEngine.kt` | KDBX 支持自定义字段与 `{REF:...}` 字段引用引擎 |
| 保护字符串 | `element/security/ProtectedString.kt` | `protectInMemory(encrypt)` 用内层流密码加密内存中的敏感字段 |
| 内存保护配置 | `element/security/MemoryProtectionConfig.kt` | 对齐 KeePass 桌面版的字段级内存保护开关 |
| 附件 | `element/Attachment.kt` + `element/binary/*`（BinaryPool/AttachmentPool/BinaryCache/BinaryFile/BinaryByte/CustomIconPool/LoadedKey） | KDBX4 附件从 XML 迁入 inner header 二进制池；BinaryCache 把大附件落盘到缓存目录，避免 OOM |
| 自定义数据 | `element/CustomData.kt`、`CustomDataItem.kt` | KDBX4.1 的 header/条目级自定义数据（KeePassDX 用来存 OTP、Passkey 等扩展） |
| 标签 | `element/Tag.kt`、`Tags.kt` | 全库标签池 |
| 回收站 | `Database.isRecycleBinEnabled` / `recycle` / `undoRecycle` | KDB 用固定 backupGroup，KDBX 用可配置 recycleBin |
| 模板 | `element/template/*`（Template/TemplateEngine/TemplateBuilder/TemplateField…） | 通过 CustomData 存"伪语言"实现条目模板 |
| OTP | `otp/OtpElement.kt`、`otp/OtpEntryFields.kt`、`otp/TokenCalculator.kt` | TOTP/HOTP |
| 通行密钥模型 | `model/Passkey.kt`、`model/PasskeyEntryFields.kt`、`model/AppOrigin.kt`、`model/AppOriginEntryField.kt` | Passkey 持 `username/privateKeyPem: CharArray/credentialId/userHandle/relyingParty/backupEligibility/backupState/prfSecret`；存为条目自定义字段 |
| 硬件密钥 | `hardware/HardwareKey.kt` | Yubikey challenge-response / FIDO2 secret 枚举 |
| UI 投影模型 | `model/EntryInfo.kt`、`GroupInfo.kt`、`NodeInfo.kt`、`SearchInfo.kt`、`RegisterInfo.kt`、`DatabaseInfo.kt`、`DatabaseFile.kt` 等 | Parcelable 化的"数据库树投影"，供 UI/Service 传递，避免直接传树对象 |

### 5.3 凭据与密钥派生链

`element/MasterCredential.kt`（468 行，Parcelable）是三种凭据因子的容器：

```kotlin
data class MasterCredential(
    private var mPassword: CharArray? = null,     // setter 会先 clear 旧值
    private var mKeyFileData: ByteArray? = null,  // 同上
    var hardwareKey: HardwareKey? = null
): Parcelable
```

- `toMasterKey(encoding, transformSeed, challengeResponseRetriever)`：密码 SHA-256 → 密钥文件解码 SHA-256 → 硬件密钥 challenge-response，三者拼接后 `composedKeyToMasterKey` 得到复合密钥，**过程中每个中间字节数组都显式 clear**；
- `getCheckKey(password, encoding)`：取密码前 N 字符做哈希 —— 用于"记住凭据"后快速校验而不解锁全库；
- `clear()` 清零全部。

`element/CompositeKey.kt`（76 行）：DatabaseKDBX 在加载时缓存已派生的三因子分量，保存时若无新凭据（`deriveCompositeKey`）可复用，避免重复要求 Yubikey 触摸等交互 —— 这解释了 `Database.saveData` 中 `masterCredential == null` 分支的存在意义。

**DatabaseKDBX.deriveMasterKey / makeFinalKey**（`element/database/DatabaseKDBX.kt`，883 行）：`deriveMasterKey` 由凭据重建 CompositeKey → `masterCredential.toMasterKey` → 再 `masterKey`；`makeFinalKey(masterSeed)` 把 KDF 输出与 masterSeed 组合（KDBX4 中再分裂出 `finalKey` 与 `hmacKey`，见 5.5）。

### 5.4 KDF 抽象与设备适配

`crypto/kdf/` 下一套完整的 KDF 策略体系：

- `KdfEngine.kt`（223 行，抽象类）：uuid、parameters（`KdfParameters`，本质是 `VariantDictionary`）、`transform(masterKey)`、以及 `getKeyRounds/setMemoryUsage/setParallelism` 三组可调参数 + min/max/default 三级限制；
- **内建基准测试**：`calculateBenchmark(masterKey, targetTime=1000ms, limits)` 实测一轮变换耗时，按比例外推迭代次数并夹紧到设备限制 —— 这是"在任意手机上自动给出合理 KDF 参数"的完整参考实现；
- `AesKdf.kt`（UUID `{C9D9F39A-...}`，参数 R 轮数 + S seed）、`Argon2Kdf.kt`（D/ID 两型 UUID；参数 S/P/M/I/V；默认 16MiB、4 并行、3 轮；**参数越界会抛专属异常** `KDFMemoryDatabaseException` 等）；
- `Limits.kt` / `KdfLimits.kt`：`isMemorySufficient(memory, opType)` 检查设备可用内存（含附件解压场景 `isMemorySufficientForBinary`），app 侧由 `utils/AppUtil.getLimits()` 提供；
- `KdfFactory.kt` 按 UUID 还原引擎。

### 5.5 文件读写：DatabaseInput/Output

**加载**（`file/input/DatabaseInputKDBX.kt`，1120 行）— `openDatabase(stream, progressTaskUpdater, assignMasterKey)` 的完整管线：

1. `DatabaseHeaderKDBX.loadFromFile`：读 sig1/sig2、版本、主头字段（masterSeed、encryptionIV、KDF 参数 VariantDictionary、compression 等）；
2. 回调 `assignMasterKey()`（上层传入的 lambda，触发 `deriveMasterKey`）→ `makeFinalKey(header.masterSeed)`；
3. KDBX3：解密后比对 32 字节 streamStartBytes 验证凭据；KDBX4：比对**头哈希** + **头部 HMAC**，然后 `HmacBlockInputStream` → `CipherInputStream`；
4. GZIP 解压（若 header 声明）；
5. KDBX4 `readInnerHeader`：读取二进制池（`createBinary`，受 `setMethodToCheckMemoryForBinary` 内存门槛保护）、内层随机流密钥；
6. `readDocumentStreamed(XmlPullParser)`：`KdbContext` 枚举状态机逐元素解析 XML（`readProtectedString` 用 `randomStream` 解密保护值），附 `safeNextText()` 扩展；
7. 捕获 `OutOfMemoryError` → `NoMemoryDatabaseException`、HMAC 失败 → `InvalidCredentialsDatabaseException`（凭据错误的统一信号）。

KDB v3 对应 `DatabaseInputKDB.kt`（390 行，`HashedBlockInputStream` + Rijndael）。

**保存**（`file/output/DatabaseOutputKDBX.kt`，788 行）：`writeDatabase(outputStream) { deriveMasterKeyOrCompositeKey }` —— 写主头 → （KDBX4）写头部 HMAC → 逐块 HMAC 块流加密 → 压缩 → XML 序列化（`entry/Group Output` 辅助）→ KDBX4 追加 inner header（二进制池）。

**Database.saveData 的原子写模式**（`element/Database.kt` L785-850）：

```kotlin
fun saveData(cacheFile: File, databaseOutputStream: () -> OutputStream?, ...) {
    // 先完整写入 cacheDir 下的临时文件（避免写坏原文件）
    cacheFile.outputStream().use { outputStream -> DatabaseOutputKDBX(...)... }
    // 成功后整体拷贝到目标 Uri 的输出流
    databaseOutputStream.invoke()?.use { ... cacheFile.inputStream() ... }
    // finally 中删除 cacheFile
}
```

配合 app 侧 `SaveDatabaseRunnable`（cacheFile 名为 `File(context.cacheDir, databaseCopyUri.hashCode().toString())`），实现了"**临时文件 → 目标流**"的两阶段写，任意一步失败都不污染原库。

**签名嗅探与格式分派**（`element/Database.kt` `readDatabaseStream`）：读 2×4 字节签名 mark/reset，`DatabaseHeaderKDB.matchesHeader` / `DatabaseHeaderKDBX.matchesHeader` 分派，未识别抛 `SignatureDatabaseException`。

**合并**：`merge/DatabaseKDBXMerger.kt` + `Database.mergeData(...)` —— 新建临时 Database 完整加载待合并流，再以删除对象表 + 时间戳做三方合并。这是 KeePassDX 支持多端同步冲突处理的全部实现，对 KeePasskey 的 sync 模块是直接蓝本。

### 5.6 生命周期与敏感数据清理

- `Database.clearAndClose(filesDirectory)`：清索引、图标缓存、附件缓存、二进制 → `mDatabaseKDB[ X]?.clearSensitiveData()`（其中 `DatabaseVersioned.clearSensitiveData` 会 `masterKey.clear()` 清零密钥）→ 置空两个内部引用、`loaded=false`；
- `Database` 与 `ContextualDatabase`（app 层子类）都是 `SingletonHolder`（自定义单例助手 `utils/SingletonHolder.kt`）—— **全库唯一会话实例**，替代 DI；
- `BinaryCache`：附件/自定义图标的磁盘缓存目录随加载传入、随关闭清除。

---

## 6. app 模块：UI 体系、服务、凭据提供与设置

### 6.1 Application 与进程级状态

`app/App.kt`（71 行）：`MultiDexApplication` + 两件事 —— `ProcessLifecycleOwner` 注册 `AppLifecycleObserver`（前后台跟踪，`appJustLaunched: SharedFlow<Unit>` 供"回到前台即锁"逻辑订阅）和 `Stylish.load(this)` 加载主题。注意它用 `GlobalScope` 发事件（被项目自身标注 Delicate）—— 教训而非示范。

### 6.2 UI 架构：View 体系 + Activity 导航

- **无 Compose、无单 Activity、无 Navigation 组件**。导航即 Activity 跳转：
  - `FileDatabaseSelectActivity`（LAUNCHER，530 行）：数据库文件列表/最近文件（Room `FileDatabaseHistoryAction`）+ SAF 选文件（`activities/helpers/ExternalFileHelper.kt`、`dialogs/FileManagerDialogFragment.kt`）；
  - `MainCredentialActivity`（816 行）：输入主密码/密钥文件/硬件密钥，含只读开关与用户验证开关；
  - `GroupActivity`（1536 行，**巨型类**）：浏览主界面，`GroupFragment` + `SearchFragment` 动态替换，`BreadcrumbAdapter` 面包屑导航，`SearchManager` 搜索；
  - `EntryActivity` / `EntryEditActivity`：条目查看/编辑；
  - `KeyGeneratorActivity`、`IconPickerActivity`、`ImageViewerActivity`、`AboutActivity`、`SettingsActivity`；
- Activity 基类链（`activities/legacy/`）：`StylishActivity` → `DatabaseActivity`（实现 `DatabaseRetrieval`，经 `DatabaseTaskProvider` 拿数据库）→ `DatabaseModeActivity` → `DatabaseLockActivity`（订阅锁定广播、超时）。"legacy" 命名说明作者自己也视其为历史包袱；
- Fragment 层（`activities/fragments/`）：GroupFragment、EntryFragment、EntryEditFragment、EntryHistoryFragment、SearchFragment、PasswordGeneratorFragment、PassphraseGeneratorFragment、Icon*Fragment、KeyGeneratorFragment；
- 对话框层（`activities/dialogs/`）：23 个 DialogFragment（MainCredentialDialogFragment、FileManagerDialogFragment、SetOTPDialogFragment、IconEditDialogFragment……），大量用 `show(supportFragmentManager, tag)` 命令式管理；
- **ViewModel 层**（`viewmodels/`，20 个）：`AndroidViewModel` + `StateFlow`/`SharedFlow` —— 这部分是后期现代化改造：
  - `DatabaseViewModel.kt`（600 行）：把 `DatabaseTaskProvider` 的回调（onActionStarted/Finished、onDatabaseInfoChanged）翻译成 `databaseState: StateFlow<ContextualDatabase?>`、`actionState: StateFlow<ActionState>`、`databaseMetadata: StateFlow<DatabaseMetadata?>`；
  - `MainCredentialViewModel.kt`（313 行）：解锁页状态机 —— `databaseFileUIState: StateFlow<DatabaseFileUIState>` + `databaseFileEvent: SharedFlow<DatabaseFileEvent>`（`LoadDatabase`、`OpenGroup`、`ShowError...`），并处理"只读默认值""连接尝试后删除密码""特殊模式强制只读/强制写"等策略；
  - 其余按页面拆分（EntryEditViewModel、GroupEditViewModel、SearchViewModel、SettingsViewModel、DeviceUnlockViewModel、PasskeyLauncherViewModel 等）。

### 6.3 会话宿主：DatabaseTaskNotificationService + DatabaseTaskProvider

这是全项目最核心（也最复杂）的编排，值得仔细研究：

- **`services/DatabaseTaskNotificationService.kt`（1571 行）**：前台服务，`extends LockNotificationService`。它持有**唯一** `ContextualDatabase`（`ContextualDatabase.getInstance()` 单例），所有数据库操作（加载/创建/保存/合并/重载/条目组增删改/历史/设置项修改/KDF 基准）都以 **Intent action**（约 35 个 `ACTION_DATABASE_*` 常量）驱动，在 `onStartCommand` 里 `when(intentAction)` 分派到 `database/action/` 下的 `ActionRunnable` 子类（LoadDatabaseRunnable、SaveDatabaseRunnable、CreateDatabaseRunnable、MergeDatabaseRunnable、UpdateKeyDerivationDatabaseRunnable、BenchmarkKdfRunnable…），于 `mainScope.launch` 中执行；
- 对外暴露两种通道：
  1. **Binder**（`ActionTaskBinder`）：UI 绑定后注册 `DatabaseListener`/`ActionTaskListener`/`DatabaseInfoListener` 回调；
  2. **Broadcast**（`DATABASE_START_TASK_ACTION`/`DATABASE_STOP_TASK_ACTION`）：驱动进度对话框生命周期；
- `DatabaseTaskProvider.kt`（707 行，app 模块 `database/` 包）：UI 侧的"遥控器"，封装 bindService/unbind + 35 个 `startDatabaseXxx(bundle, action)` 方法；KeeAutofillService、PasskeyProviderService 等**服务也用它**接入同一会话 —— 这是让多个入口（Activity、Autofill、IME、CredentialProvider）共享同一数据库实例的关键；
- 外部修改检测：`checkDatabaseInfo()` 比对 `SnapFileDatabaseInfo`（大小/最后修改时间，含 10 秒去抖），变化则 `indicateNotSavedData()` 并回调 UI 弹"数据库已被外部修改/合并"对话框；
- 硬件密钥 challenge-response 通过 `mResponseChallengeChannel: Channel<ByteArray?>` 从 `ACTION_CHALLENGE_RESPONDED` intent 异步回填（服务内 `runBlocking` 等待 —— 一个值得注意的坑）。

### 6.4 自动锁定与通知体系

- `timeout/TimeoutHelper.kt`（184 行）：`AlarmManager.setExact(RTC)` + 把超时时间戳持久化到 `PreferencesUtil`（**进程被杀后仍可判定超时**）；`temporarilyDisableTimeout()` 在数据库任务执行期间挂起锁；`LOCK_ACTION` 广播触发锁定；
- 通知服务族（`services/`）：`NotificationService`（基类）→ `LockNotificationService`（数据库打开期间常驻）→ `DatabaseTaskNotificationService`（任务执行）；另有 `ClipboardEntryNotificationService`（剪贴板倒计时清除）、`AttachmentFileNotificationService`、`KeyboardEntryNotificationService`（给 Magikeyboard 投喂当前字段）、`DeviceUnlockNotificationService`（生物识别封装凭据待处理时提醒）。

### 6.5 生物识别 / 设备凭据封装（直接对标 KeePasskey 需求）

`biometric/` 三件套 + app 内 Room：

- `DeviceUnlockManager.kt`（490 行）：
  - AndroidKeyStore 生成 `PURPOSE_ENCRYPT|DECRYPT` 的 AES 密钥，`setUserAuthenticationRequired(true)`，按 Android 版本区分 `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL)`（R+）与 `setUserAuthenticationValidityDurationSeconds(5)`（M-Q），支持 **StrongBox**（P+，`FEATURE_STRONGBOX_KEYSTORE`）；
  - `initEncryptData/encryptData/initDecryptData`：返回 `DeviceUnlockCryptoPrompt`（携带初始化好的 Cipher）供 BiometricPrompt 使用；`KeyPermanentlyInvalidatedException` 时自动清 keystore 别名重来；
  - 加密结果回传 `(encryptedValue, iv)` 二元组；
- 存储：`app/database/CipherDatabaseEntity.kt`（Room 表 `cipher_database`：`database_uri` 主键 + `encrypted_value` + `specs_parameters`，DAO 见 `CipherDatabaseDao/Action`）—— **按数据库 URI 存"加密后的主凭据 + IV"**；
- UI：`DeviceUnlockFragment.kt`（BiometricPrompt 封装）+ `MainCredential.kt`（app 层凭据模型，`password: CharArray + keyFileUri + hardwareKey`，`toMasterCredential(contentResolver)` 时才读密钥文件字节）。

### 6.6 Autofill、Magikeyboard、Credential Provider（通行密钥）

三种"特殊输入模式"共享统一抽象（`credentialprovider/`）：

- `SpecialMode.kt`：`DEFAULT / SEARCH / SELECTION / REGISTRATION`；
- `TypeMode.kt`：`DEFAULT / MAGIKEYBOARD / AUTOFILL / PASSWORD / PASSKEY`，其中 PASSWORD 与 PASSKEY `useUserVerification = true`；
- `EntrySelectionHelper.kt`：统一的选择/注册条目流程编排；`UserVerificationHelper.kt`：WebAuthn user verification 的校验封装。

**Autofill**（`credentialprovider/autofill/`）：
- `KeeAutofillService.kt`（547 行）：`AutofillService`，`onFillRequest` 解析 `StructureParser` 提取网页/应用标识 → `SearchHelper.checkAutoSearchInfo` 查库 → `AutofillHelper` 构建数据集（含 API 33+ `Dataset.Builder` 内联建议 `CompatInlineSuggestionsRequest`）；`onSaveRequest` 注册新凭据；通过 `DatabaseTaskProvider` 拉起解锁；
- `AutofillComponent.kt` 抽象公共逻辑，`AutofillLauncherActivity` 是选中数据集后落地的 Activity。

**Magikeyboard**（`credentialprovider/magikeyboard/`）：`MagikeyboardService.kt`（809 行，InputMethodService）+ 遗留 Java 的 `Keyboard.java`/`KeyboardView.java`；条目字段经 `KeyboardEntryNotificationService` 传入，逐字段切换"打字"（避免剪贴板泄露）。

**Passkey / Credential Provider**（`credentialprovider/passkey/` + `activity/`）：
- `PasskeyProviderService.kt`（632 行，`@RequiresApi(UPSIDE_DOWN_CAKE)`，`extends CredentialProviderService`）：
  - `onBeginGetCredentialRequest`：区分 `BeginGetPasswordOption`（传统密码填充，也并入 Credential Manager！）与 `BeginGetPublicKeyCredentialOption`（passkey），用 `SearchHelper` 匹配 relyingParty/origin，为每个候选生成 `PasswordCredentialEntry`/`PublicKeyCredentialEntry` + **PendingIntent**（指向 `PasswordLauncherActivity`/`PasskeyLauncherActivity`）；数据库锁定时给出"点击解锁"条目（`userVerifiedWithAuth = true`）；
  - `onBeginCreateCredentialRequest`：区分 `BeginCreatePasswordCredentialRequest` 与 `BeginCreatePublicKeyCredentialRequest` → 注册流程（`SpecialMode.REGISTRATION`，强制可写库）；
  - `onClearCredentialStateRequest`：空实现；
- `activity/` 六个入口 Activity：AuthenticationLauncherActivity、AutofillLauncherActivity、EntrySelectionLauncherActivity、HardwareKeyActivity、PasskeyLauncherActivity、PasswordLauncherActivity —— **PendingIntent → LauncherActivity → ViewModel → DatabaseTaskProvider → 主 UI 选择 → 回传 setResult** 的链路；
- `passkey/data/`：**自实现 WebAuthn 二进制层** —— `Cbor.kt`（CBOR 编码）、`AuthenticatorData.kt`（rpIdHash/flags/signCount/attestedCredentialData/backup flags）、`AuthenticatorAttestationResponse` / `AuthenticatorAssertionResponse`、`ClientDataBuildResponse` / `ClientDataDefinedResponse`（challenge/origin/type）、`PublicKeyCredentialCreationOptions/RequestOptions`、`FidoPublicKeyCredential`、`AndroidPrivilegedApp.kt`；
- `passkey/util/`：`PasskeyHelper.kt`（489 行，请求解析与响应组装）、`PassHelper.kt`（308 行，prf 扩展等）、`PasswordHelper.kt`、`PrivilegedAllowLists.kt`（228 行，对 GMS 等特权应用（`com.google.android.gms`）在无调用方身份时可信任的 origin 白名单 —— Android 特有坑）；
- Android app origin 的可靠校验：`crypto/Signature.kt` 的 APK 签名指纹 → URL-safe Base64（见 4.5），与 `database/model/AppOrigin.kt` 配套；
- passkey 持久化：`database/model/PasskeyEntryFields.kt` 把 Passkey 各字段映射为 KDBX 条目自定义字段。

### 6.7 设置与主题体系

- `settings/`：`SettingsActivity` + 大量 PreferenceFragment（`settings/preference/`），`PreferencesUtil` 静态读写 SharedPreferences；特殊设置页 `DeviceUnlockSettingsActivity`、`MagikeyboardSettingsActivity`（入口是 IME 设置的 ACTION_INPUT_METHOD 系统入口）；
- **主题**（`activities/stylish/Stylish.kt` + `StylishActivity.kt`）：主题名 → themeId 的映射 + 跟随系统/日夜版本换算（`retrieveEquivalentSystemStyle/LightStyle/NightStyle`）；配合 chroma（AndroidClearChroma）做动态取色主题，`libre/free` flavor 用 `STYLES_DISABLED` 控制主题可用性；每个 Activity 都要继承 `StylishActivity` 并在 manifest 设 theme。

### 6.8 其它值得注意的 app 级组件

- `password/PasswordGenerator.kt`、`PassphraseGenerator.kt`、`PasswordEntropy.kt`（基于 nbvcxz 估算熵）；
- `education/`：首用引导（taptargetview）；
- `backup/SettingsBackupAgent.kt`：Android 备份代理（显式排除敏感内容）；
- `mozilla/components/lib/publicsuffixlist/`：内嵌 Mozilla 公共后缀列表，用于 autofill/passkey 的 eTLD+1 域名匹配；
- Room：`app/database/AppDatabase.kt`（两表：file_database_history、cipher_database），schema 位于 `app/schemas/`。

---

## 7. 关键数据流：从选择数据库到保存的端到端调用链

### 7.1 选择数据库文件 → 输入凭据 → 解锁（Load）

```text
[1] 选择文件
    FileDatabaseSelectActivity（app/activities/FileDatabaseSelectActivity.kt）
      ├─ Room: FileDatabaseHistoryAction（app/database/FileDatabaseHistoryAction.kt）最近文件/别名/只读标记
      └─ SAF: ExternalFileHelper / FileManagerDialogFragment → content:// Uri

[2] 输入凭据
    MainCredentialActivity（816 行）
      └─ MainCredentialViewModel（viewmodels/MainCredentialViewModel.kt）
          ├─ 读历史: mFileDatabaseHistoryAction.getDatabaseFile(uri)
          ├─ 恢复 remembered keyFileUri / hardwareKey / readOnly / userVerification
          └─ collect 事件 DatabaseFileEvent.LoadDatabase →
              DatabaseTaskProvider.startDatabaseLoad(uri, MainCredential, readOnly,
                  allowUserVerification, cipherEncryptDatabase?, fixDuplicateUuid)
              （database/DatabaseTaskProvider.kt → Context.startDatabaseService）

[3] 服务执行（唯一会话宿主）
    DatabaseTaskNotificationService.onStartCommand
      ├─ ContextualDatabase.getInstance()（database/ContextualDatabase.kt 单例）
      ├─ buildDatabaseLoadActionTask → LoadDatabaseRunnable
      └─ mainScope.launch { executeAction(...) }，广播 DATABASE_START_TASK_ACTION 显示进度

[4] 真正加载
    LoadDatabaseRunnable.onActionRun（app/database/action/LoadDatabaseRunnable.kt）
      ├─ onStartRun: mDatabase.clearAndClose(binaryDir)   ← 先清旧会话
      ├─ contentResolver.getUriInputStream(databaseUri)
      └─ mDatabase.loadData(stream, MasterCredential, challengeResponseRetriever,
                            readOnly, allowUserVerification, cacheDir, limits,
                            fixDuplicateUUID, progressTaskUpdater)

[5] 格式与密钥
    Database.loadData（database/element/Database.kt L544）
      └─ readDatabaseStream: 嗅探签名 → DatabaseInputKDB / DatabaseInputKDBX
          DatabaseInputKDBX.openDatabase（file/input/DatabaseInputKDBX.kt L124）
            ├─ DatabaseHeaderKDBX.loadFromFile（主头：masterSeed/IV/KDF 参数…）
            ├─ assignMasterKey() → DatabaseKDBX.deriveMasterKey（element/database/DatabaseKDBX.kt L242）
            │     ├─ MasterCredential.toMasterKey（密码 SHA-256 ⊕ 密钥文件 ⊕ Yubikey challenge）
            │     └─ Argon2Kdf/AesKdf.transform（crypto: NativeArgon2KeyTransformer / AESTransformer）
            ├─ makeFinalKey(masterSeed) → finalKey + hmacKey
            ├─ 头 HMAC 校验（KDBX4）/ streamStartBytes 校验（KDBX3）→ 失败=InvalidCredentialsDatabaseException
            ├─ HmacBlockInputStream → CipherInputStream(AES/Twofish/ChaCha20) → GZIP → readInnerHeader
            └─ XmlPullParser 状态机 readDocumentStreamed → GroupKDBX/EntryKDBX 树

[6] 回到 UI
    服务回调 onActionFinished → DatabaseViewModel.onActionFinished（把 StateFlow 置新值）
      → MainCredentialViewModel.onDatabaseRetrieved → emit DatabaseFileEvent.OpenGroup
      → GroupActivity；同时 TimeoutHelper.recordTime 启动自动锁
```

### 7.2 浏览 / 编辑 / 保存（Save）

```text
[浏览]
    GroupActivity（extends DatabaseLockActivity）
      ├─ GroupFragment（RecyclerView 列表：SortedNodeInfo/EntryInfo/GroupInfo 投影）
      ├─ BreadcrumbAdapter 面包屑
      └─ SearchFragment + SearchHelper.checkAutoSearchInfo（database/search/SearchHelper.kt）

[编辑条目]
    EntryEditActivity ─ EntryEditViewModel → DatabaseTaskProvider.startDatabaseUpdateEntry(
        EntryInfo, save = true)
      → ACTION_DATABASE_UPDATE_ENTRY_TASK → UpdateEntryDatabaseRunnable（app/database/action/…）
        （先写内存树 Database.updateEntry；save=true 时串联保存）

[保存]
    SaveDatabaseRunnable.onActionRun（app/database/action/SaveDatabaseRunnable.kt）
      ├─ database.checkVersion()           ← 按内容计算最低 KDBX 版本（3.1/4/4.1）
      ├─ mainCredential?.toMasterCredential(contentResolver)  ← 此刻才读密钥文件
      └─ database.saveData(
             cacheFile = File(cacheDir, uriHash),     ← 两阶段原子写
             databaseOutputStream = { contentResolver.getUriOutputStream(uri) },
             masterCredential | null(则 deriveCompositeKey 复用缓存分量),
             limits)
           ├─ DatabaseOutputKDBX.writeDatabase（主头→HMAC 块→加密→XML→inner header 二进制池）
           └─ 成功后整体拷贝到目标流，finally 删 cacheFile
      → onFinishRun: mMasterCredential.clear() / 缓存的硬件响应 clear()
      → 服务 saveDatabaseInfo() 更新 SnapFileDatabaseInfo，广播 DATABASE_STOP_TASK_ACTION

[自动锁]
    TimeoutHelper（AlarmManager + 持久化时间戳）
      → LOCK_ACTION 广播 → DatabaseTaskNotificationService/LockNotificationService
      → ContextualDatabase.clearAndClose(binaryDir)（清树、清缓存、masterKey.clear()）
      → UI 收到 database=null 回到 FileDatabaseSelectActivity
```

### 7.3 通行密钥请求流（Credential Manager，Android 14+）

```text
浏览器/App → Credential Manager 系统弹窗候选
  PasskeyProviderService.onBeginGetCredentialRequest
    ├─ BeginGetPublicKeyCredentialOption → populatePasskeyData
    │    SearchHelper 按 relyingParty 匹配 → 每条构造 PublicKeyCredentialEntry
    │    + PendingIntent(PasskeyLauncherActivity, SpecialMode.SELECTION, userVerifiedWithAuth)
    └─ BeginGetPasswordOption → 同构的 PasswordCredentialEntry（密码也走 Credential Manager）
用户选择 → PasskeyLauncherActivity（PasskeyLauncherViewModel）
    ├─ 若库锁定：先走 MainCredentialActivity/DeviceUnlock 解锁（TypeMode.PASSKEY 强制 userVerification）
    ├─ UserVerificationHelper 校验 UV
    └─ setResult → PendingIntent 回给 Credential Manager
最终响应由 PasskeyHelper/Cbor/AuthenticatorData 组装：
    assertion = authenticatorData(rpIdHash+flags+signCount) ‖ clientDataHash
    经 crypto/Signature.kt 用 PEM CharArray 私钥 Ed25519/ES256 签名
```

---

## 8. 对 KeePasskey 的借鉴要点

KeePasskey 的既定架构：`app → database → crypto → core` 单向依赖、Compose + Hilt + Coroutines/Flow、UiState + StateFlow 单向数据流、阶段 1 UI 优先（Fake Repository）。对照 KeePassDX 逐项给出建议。

### 8.1 模块划分对照

| KeePassDX | 建议对应到 KeePasskey | 结论 |
|---|---|---|
| `crypto`（原语 + JNI，包名 encrypt） | `crypto` | 保持"零业务知识"边界：只放 KDF/分组密码/Hash/（passkey 签名），**不知道 kdbx**。KeePasskey 用纯 JVM 库（BouncyCastle 的 Argon2/AES-KDF）可先免 JNI；性能不达标再按 KeePassDX 的"native 优先 + JVM 兜底 + NativeLib 探活"模式补 C 层 |
| `database`（格式 + 领域模型 + 读写） | `database` + `core` 的部分职责 | KeePassDX 的 `Database` 门面（双格式互斥 + `allowXxx` 能力位）值得完整移植为接口/密封类设计；KeePasskey 只支持 kdbx v4，可直接砍掉 KDB 分支与版本门面开销，但**保留"能力位"思想**（4.0/4.1 差异仍存在） |
| app 内 Room（文件历史 + 凭据封装） | `database`/`app` 的持久化层 | 结构可复用：`file_database_history` + `cipher_database`（加密凭据+IV）两表 |
| `icon-pack` | 可选 | 图标包用资源命名约定动态加载，不急 |

### 8.2 值得移植的模式（按优先级）

1. **凭据与密钥派生链的内存纪律**（`MasterCredential` / `CompositeKey` / `Database.clearSensitiveData`）：
   - `CharArray`/`ByteArray` 全程、setter 先 clear 旧值、中间产物即用即清、`masterKey.clear()` 收尾 —— 与 KeePasskey 硬约束 2 完全一致，KeePassDX 是现成的正确范本；
   - `getCheckKey`（密码前缀哈希）可用于校验"已记住的凭据"而不解锁全库；
   - `CompositeKey` 缓存已派生分量避免保存时重复硬件交互 —— 对带 Yubikey/UV 的保存流程是必要设计。
2. **两阶段原子保存**（`Database.saveData` + `SaveDatabaseRunnable`）：先写 cacheDir 临时文件、成功后拷贝到目标流、finally 删除。直接对应 KeePasskey 工程规则中的原子写盘要求；"保存为副本"（databaseCopyUri）顺手获得。
3. **KDF 基准与设备内存门槛**（`KdfEngine.calculateBenchmark` + `Limits`）：自动在目标时间（1s）内推参数、按设备可用内存拒绝过大的 Argon2 memory 与附件解压 —— UX 与安全兼得，建议原样借鉴接口形态。
4. **凭据错误的统一信号**：KDBX3 用 streamStartBytes、KDBX4 用头部 HMAC，统一映射为"凭据无效"异常，UI 无需关心格式。
5. **生物识别封装三元组**（`DeviceUnlockManager` + Room cipher_database + `DeviceUnlockFragment`）：AndroidKeyStore AES（UV required / StrongBox / 版本分支）→ 加密主凭据 + IV 存 Room → 解锁时 BiometricPrompt 携带 Cipher 解密。KeePasskey 实现主密码生物封装时可逐件对照；注意 `KeyPermanentlyInvalidatedException` 的自动重建路径。
6. **Credential Provider 全套参考实现**：KeePassDX 是少数完整开源实现（get/create/password+passkey 四象限）：
   - `SpecialMode`/`TypeMode` 枚举区分入口与验证要求（PASSKEY 强制 UV）；
   - `PasskeyProviderService` 的三回调结构与"锁定库也给条目、点击进解锁"的 UX；
   - `PrivilegedAllowLists`（GMS 特权应用 origin 白名单）与 APK 签名指纹→App Origin 的校验（`Signature.fingerprintToUrlSafeBase64`）—— Android 侧 origin 验证的必需细节，自己从零摸索极易漏；
   - WebAuthn 二进制层（CBOR、authenticatorData flags、BE/BS 备份位、prf 扩展）可直接按 W3C 规格对照重写；
   - passkey 数据模型（`Passkey.kt`：privateKeyPem 用 CharArray、prfSecret 同样保护）与 KDBX 自定义字段的映射方案。
   - 注意：KeePassDX 是**自建 Authenticator**（不依赖 GMS passkey 端到端加密），KeePasskey 若走同一路线（自建 CredentialProviderService）此套代码是最完整参照。
7. **自动锁定**：`TimeoutHelper` 把超时时间戳持久化到 Preferences（进程被杀后 Alarm 恢复时仍能判定），`temporarilyDisableTimeout` 在长任务期间挂起锁 —— 两个细节都值得照抄。
8. **KDBX4.1 最小版本计算**（`DatabaseKDBX.getMinKdbxVersion`）：按"是否使用 4.1 才有的特性"动态决定写出版本，保证最大兼容性 —— 写 kdbx 输出端时的正确姿态。
9. **合并引擎**（`DatabaseKDBXMerger` + DeletedObjects 表）：KeePasskey 的 sync 模块（WebDAV/S3）将来必然面对"远端比本地新"的冲突，三方合并 + 删除墓碑是 KeePass 系的既定解法。
10. **带进度的流式解析**（`ProgressTaskUpdater` 贯穿 input/output）：大库在低端机上的可感知性；KeePasskey 可将其转成 `Flow<Float>`。

### 8.3 KeePassDX 的坑与 KeePasskey 应规避之处

1. **巨型类**：`Database`（1348）、`DatabaseTaskNotificationService`（1571）、`GroupActivity`（1536）、`DatabaseTaskProvider`（707）、`MagikeyboardService`（809）、`MainCredentialActivity`（816）。KeePasskey 按工程规则的巨型类阈值拆分：Database 门面可拆为"树操作/设置操作/二进制操作"三个 use case；任务执行器可拆为独立的 UseCase/Repository。
2. **服务即会话宿主 + Binder/Broadcast 双通道**：35 个 Intent action 字符串 + Parcelable 参数 + 回调监听器列表，是全项目复杂度之最（`DatabaseTaskProvider` 的 35 个 start 方法可见一斑），且服务里出现 `runBlocking` 等通道。KeePasskey 应改为：**DatabaseSession 作为 database 模块的 Flow 化单例（对应 ARCHITECTURE.md 的 DatabaseSession 决策）**，用 `StateFlow<SessionState>` + suspend 函数表达任务；若仍需前台通知保活，服务只做"保活壳"不做编排。
3. **无 DI 的 `SingletonHolder` + 静态 `PreferencesUtil`**：可测试性差（唯一好处是 app 内 Room 也好理解）。KeePasskey 用 Hilt 注入 Repository/Session/Preferences DataStore。
4. **UI 层直接持有数据库树**：`DatabaseViewModel` 直接暴露 `ContextualDatabase?`，Activity 里到处 `database.loaded` 判断。KeePasskey 坚持 UiState 投影（KeePassDX 自己其实已有正确直觉 —— `EntryInfo/GroupInfo` 这些"投影模型"就是为此而生的，只是没用彻底）。
5. **`GlobalScope`/`mainScope` 无生命周期治理**：`App.kt` 与服务中的裸 CoroutineScope 应在 KeePasskey 用 viewModelScope/SupervisorJob + 结构化并发替代。
6. **minSdk 19 的兼容税**：`Build.VERSION.SDK_INT` 分支遍布 biometric/autofill/credential 代码。KeePasskey 以 API 36+ 为基线、低版本平滑退化，可删除绝大部分分支，只保留 Credential Manager 的降级路径。
7. **遗留 Java 与双仓库依赖**（Keyboard.java、joda-time、内嵌 mozilla publicsuffixlist）：能少则少；joda-time 换 java.time，域名匹配可评估更小的依赖。
8. ** Parcelable 作为层间契约**：模型既进数据库又跨进程又当投影，改一个字段牵三处。KeePasskey 用清晰的 DTO/Entity 分层 + kotlinx.serialization（跨 Activity 简化为 ID 传递）。
9. **Autofill/IME/CredentialProvider 三套入口各自为政**：KeePassDX 用 `EntrySelectionHelper` 缓解但仍重复较多（三个 LauncherViewModel 高度相似）。KeePasskey 从第一天就把"选择条目/注册凭据"抽成共享的 SelectionCoordinator（Compose 亦可复用导航图）。
10. **保护字段的内层随机流**：KDBX3 的 ArcFourVariant 已废弃，KeePassDX 仍需兼容读取；KeePasskey 只需实现 ChaCha20（v4），可以大幅简化 `CrsAlgorithm` 一层。
11. **别忘收尾清理**：KeePassDX 在 `clearAndClose` 清附件缓存目录（`cleanDirectory` 递归删文件）；KeePasskey 的缓存附件/图标同样必须有对称的清理路径，否则磁盘泄漏。

---

## 9. 关键文件索引表

### 9.1 Gradle / 构建

| 文件 | 内容 |
|---|---|
| `settings.gradle.kts` | 6 模块清单 |
| `gradle/libs.versions.toml` | 版本目录（AGP 8.13.2 / Kotlin 2.0 / Room / credentials / BC 1.84） |
| `crypto/build.gradle.kts` | CMake/NDK、16KB 页面对齐、BC 依赖 |
| `database/build.gradle.kts` | `api(project(":crypto"))` |
| `app/build.gradle.kts` | flavor libre/free、Room KSP、credentials 依赖 |
| `app/src/main/AndroidManifest.xml` | 全部服务/Activity 声明（autofill、IME、credential provider 的 intent-filter） |

### 9.2 crypto 模块（`crypto/src/main/java/com/kunzisoft/encrypt/`）

| 文件 | 内容 |
|---|---|
| `NativeLib.kt` | libaes/libargon2 装载探活 |
| `CipherFactory.kt` | AES（原生 Provider 优先）/Twofish/ChaCha20 |
| `aes/AESTransformer.kt`、`aes/AESProvider.kt`、`aes/NativeAESCipherSpi.java`、`aes/NativeAESKeyTransformer.java` | AES-KDF（native 优先 + JVM 兜底）与原生 AES CipherSpi |
| `argon2/Argon2Transformer.kt`、`argon2/NativeArgon2KeyTransformer.java`、`argon2/Argon2Type.kt` | Argon2 JNI 封装 |
| `HashManager.kt`、`StreamCipher.kt`、`Base64Helper.kt` | 哈希/HMAC/随机、流密码包装 |
| `Signature.kt` | passkey 签名（ES256/RS256/Ed25519）、COSE 公钥、APK 指纹→App Origin |
| `jni/`（aes_jni.c、argon2_jni.c、CMakeLists.txt） | JNI 边界与 argon2 参考实现源码 |

### 9.3 database 模块（`database/src/main/java/com/kunzisoft/keepass/database/`）

| 文件 | 内容 |
|---|---|
| `element/Database.kt` | 门面（1348 行）：加载/保存/合并/树操作/能力位 |
| `element/database/DatabaseVersioned.kt` / `DatabaseKDB.kt` / `DatabaseKDBX.kt` | 版本化基类 / KDB / KDBX（deriveMasterKey、makeFinalKey、getMinKdbxVersion） |
| `element/MasterCredential.kt`、`element/CompositeKey.kt` | 凭据容器与复合密钥 |
| `crypto/EncryptionAlgorithm.kt`、`CipherEngine.kt`、`AesEngine.kt`、`TwofishEngine.kt`、`ChaCha20Engine.kt`、`CrsAlgorithm.kt` | 格式 UUID→引擎策略 |
| `crypto/kdf/KdfEngine.kt`、`AesKdf.kt`、`Argon2Kdf.kt`、`KdfParameters.kt`、`Limits.kt` | KDF 体系与基准 |
| `file/DatabaseHeaderKDBX.kt`、`file/input/DatabaseInputKDBX.kt`、`file/output/DatabaseOutputKDBX.kt` | 头解析 / XML 状态机读 / HMAC 块流写 |
| `file/input/DatabaseInputKDB.kt`、`file/output/DatabaseOutputKDB.kt` | KDB v3 读写 |
| `stream/HashedBlockInputStream.kt`、`stream/HmacBlockInputStream.kt` 等 | KDBX 块流 |
| `merge/DatabaseKDBXMerger.kt` | 三方合并 |
| `element/binary/*`（BinaryPool/AttachmentPool/BinaryCache…） | 附件与自定义图标池 |
| `element/template/*`、`otp/*`、`element/Tag.kt`、`element/CustomData.kt` | 模板 / OTP / 标签 / 自定义数据 |
| `model/Passkey.kt`、`model/PasskeyEntryFields.kt`、`model/AppOrigin.kt` | 通行密钥模型与字段映射 |
| `hardware/HardwareKey.kt` | Yubikey challenge-response |
| `search/SearchHelper.kt`、`search/SearchParameters.kt` | 搜索（autofill/passkey 复用） |
| `tasks/ActionRunnable.kt`、`tasks/ProgressTaskUpdater.kt` | 任务抽象与进度回调 |
| `utils/SingletonHolder.kt` | 手工单例助手 |

### 9.4 app 模块（`app/src/main/java/com/kunzisoft/keepass/`）

| 文件 | 内容 |
|---|---|
| `app/App.kt`、`activities/stylish/Stylish.kt` | Application、前后台观察、主题 |
| `activities/FileDatabaseSelectActivity.kt` | 文件选择（LAUNCHER） |
| `activities/MainCredentialActivity.kt` + `viewmodels/MainCredentialViewModel.kt` | 解锁页与状态机 |
| `activities/GroupActivity.kt`、`activities/fragments/GroupFragment.kt`、`adapters/BreadcrumbAdapter` | 浏览主界面 |
| `activities/EntryActivity.kt`、`activities/EntryEditActivity.kt` + `viewmodels/EntryEditViewModel.kt` | 条目查看/编辑 |
| `activities/legacy/DatabaseLockActivity.kt` 等 4 件 | Activity 基类链 |
| `database/ContextualDatabase.kt` | 会话单例（fileUri、修改标记 StateFlow） |
| `database/DatabaseTaskProvider.kt` | 服务遥控器（bind/35 个 action 启动器） |
| `database/action/LoadDatabaseRunnable.kt`、`SaveDatabaseRunnable.kt`、`MergeDatabaseRunnable.kt` 等 | 任务实现 |
| `services/DatabaseTaskNotificationService.kt` | 会话宿主与任务分派（1571 行） |
| `services/LockNotificationService.kt`、`ClipboardEntryNotificationService.kt`、`KeyboardEntryNotificationService.kt` | 通知族 |
| `timeout/TimeoutHelper.kt` | AlarmManager 自动锁 |
| `biometric/DeviceUnlockManager.kt`、`DeviceUnlockFragment.kt`、`DeviceUnlockCryptoPrompt.kt` | 生物识别封装 |
| `app/database/AppDatabase.kt`、`FileDatabaseHistory*.kt`、`CipherDatabase*.kt` | Room：历史 + 加密凭据 |
| `credentialprovider/SpecialMode.kt`、`TypeMode.kt`、`EntrySelectionHelper.kt`、`UserVerificationHelper.kt` | 特殊模式抽象 |
| `credentialprovider/autofill/KeeAutofillService.kt`、`StructureParser.kt`、`AutofillHelper.kt`、`CompatInlineSuggestionsRequest.kt` | Autofill 服务 |
| `credentialprovider/magikeyboard/MagikeyboardService.kt`（+ Keyboard.java） | 自定义键盘 |
| `credentialprovider/passkey/PasskeyProviderService.kt` | CredentialProviderService（API 34+） |
| `credentialprovider/passkey/data/*`（Cbor、AuthenticatorData、AuthenticatorAssertionResponse…） | WebAuthn 二进制层 |
| `credentialprovider/passkey/util/PasskeyHelper.kt`、`PrivilegedAllowLists.kt`、`PasswordHelper.kt` | passkey 工具与特权白名单 |
| `credentialprovider/activity/PasskeyLauncherActivity.kt`、`PasswordLauncherActivity.kt` 等 6 件 | PendingResult 落地 Activity |
| `viewmodels/DatabaseViewModel.kt` | 服务回调 → StateFlow |
| `password/PasswordGenerator.kt`、`PasswordEntropy.kt` | 生成器与熵估算 |
| `settings/SettingsActivity.kt`、`settings/PreferencesUtil`（utils） | 设置 |
| `icons/IconDrawableFactory.kt`、`icon-pack/src/main/java/.../IconPack.kt` | 图标体系 |

---

## 附：阅读方法说明

本文档的结论基于对以下源码的**实际完整/部分阅读**（主要文件全文读毕，其余为方法级浏览）：`settings.gradle.kts`、根/各模块 `build.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`、`AndroidManifest.xml`；crypto 模块全部 Kotlin/Java（CipherFactory、NativeLib、Signature、StreamCipher、AESTransformer、Argon2Transformer）；database 模块的 Database.kt（全文）、DatabaseKDBX（关键方法）、MasterCredential、EncryptionAlgorithm/CipherEngine/KdfEngine/Argon2Kdf、DatabaseInputKDBX（openDatabase 全文）、SaveDatabaseRunnable；app 模块的 App、ContextualDatabase、DatabaseTaskProvider（全文）、DatabaseTaskNotificationService（核心段）、MainCredentialViewModel、DatabaseViewModel（方法清单）、TimeoutHelper（全文）、DeviceUnlockManager（核心段）、PasskeyProviderService（核心段）、KeeAutofillService（方法级）、MagikeyboardService/GroupActivity/Stylish/MainCredential/SaveDatabaseRunnable（方法级）、CipherDatabaseEntity、HardwareKey、Passkey 模型、SpecialMode/TypeMode。
