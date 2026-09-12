# KeePasskey Android 红队攻击路径清单（44 条）

> **文档性质**：合法授权测试环境下的攻击面设计文档（Red Team Test Plan）。
> **目标**：找出「从 Android App 中获取用户密码 / KDBX 明文 / 密钥材料」的现实可行路径，**不评价代码质量**。
> **基线**：`applicationId=com.keepasskey`、`versionCode=1`、`minSdk 36 / targetSdk 36`、
> R8 + 资源收缩 release 构建、v2+v3+v4 签名（无 v1）。
> **方法**：JADX / apktool 反编译、Ghidra + readelf 静态分析、Frida instrumentation、
> rooted AVD、恶意 APK / KDBX / ContentProvider / Intent 构造、Logcat + 文件系统观察。
>
> **每条路径给出**：Attack Goal → Initial Access → Required Privileges → Preconditions →
> Attack Steps → Security Boundary Crossed → Expected Result → Impact → Detection → Mitigation，
> 并附 **证据/置信度**（`已核实`＝可在源码中定位；`待实测`＝需设备验证的假设，不得当作结论）。

---

## §0 授权与范围

| 项 | 值 |
|---|---|
| 授权主体 | 项目所有者（本仓 `D:\GithubWorkplace\KeePasskey`） |
| 测试对象 | 本仓 release APK（`app/build/outputs/apk/release/app-release.apk`） |
| 允许手段 | 反编译、动态插桩、内存取证、恶意文件/应用/Intent/URI 构造、root 与内核级观察 |
| 禁止手段 | 真实用户数据、非本仓目标、供应链投毒外发 |
| 环境 | x86_64 AVD（API 36.1，rooted，Magisk）＋ arm64 真机（StrongBox 验签，仅用于对照） |

### §0.1 已核实的信任边界地图（后续路径的落点）

| # | 边界 | 实现事实 | 证据 |
|---|---|---|---|
| B1 | 应用沙箱 | 无自定义 exported ContentProvider；自有 Activity 全 `exported="false"` | `app/src/main/AndroidManifest.xml` |
| B2 | 系统绑定服务 | `KeePasskeyCredentialProviderService` / `KeePasskeyAutofillService` `exported=true` 但受 `BIND_*` 系统权限保护 | 同上 L76-106 |
| B3 | 深度链接 | **当前无任何 VIEW / deep link intent-filter**（仅 LAUNCHER） | 同上 L29-32 |
| B4 | 备份 | `allowBackup=false` + `data_extraction_rules` 全域排除（cloud-backup & device-transfer） | 同上 L18；`res/xml/data_extraction_rules.xml` |
| B5 | 传输层 | 明文全禁 + **仅系统 CA**（用户 CA 被拒）＋ OkHttp TLS-only；**零证书固定** | `res/xml/network_security_config.xml`；AGENTS.md §1 |
| B6 | 快速解锁封印 | AES-256-GCM，密钥 `AUTH_BIOMETRIC_STRONG` per-operation、`setUnlockedDeviceRequired(true)`、生物录入失效即吊销；封印载荷 = **主密码 + 密钥文件复合帧** | `KeystoreManager.kt` L104-124；`AGENTS.md §29.1` |
| B7 | 封印凭据落盘 | `SharedPreferences("com.keepasskey.biometric_credentials")` 仅存 IV/cipher Base64 | `BiometricCredentialStorage.kt` L78-107 |
| B8 | 解锁通行密钥 | ES256 硬件私钥 + 登记记录 HMAC 防篡改；**自认"同进程同存储，不构成第二因素"** | `UnlockPasskeyManager.kt` L58-60 |
| B9 | 运行完整性 | 仅官方 API + 文件路径探测；**不校验自身签名**；`installer==null` 一律视为可信 | `RuntimeIntegrityDetector.kt` L100-149 |
| B10 | 自动填充匹配 | 严格域名 / **包名精确匹配**；候选仅需 package 或 domain 之一命中 | `AutofillCandidateRanker.kt` L119-142；`DomainMatcher.kt` L137-151 |
| B11 | 受信浏览器 | 仅「包名 + 已取证 SHA-256 指纹」白名单（Chrome/Firefox 三包名） | `BrowserSigningFingerprints.kt` L32-46 |
| B12 | KDBX 解析 | 头部字段 ≤1 MiB、KDF 上下界、GZip 解压尺寸护栏、XML 深度上限、DTD/外部实体拒绝 | `KdbxHeader.kt` L106-207；`SizeBoundedInputStream.kt`；`KdbxXmlParser.kt` L51-95 |
| B13 | Native | 单一 `.so`（`keepasskey_argon2`）；Rust `Zeroizing` RAII + `catch_unwind`；JNI 签名与旧 C 桥逐字一致 | `NativeCryptoLibrary.kt`；`jni_bridge.rs` |
| B14 | 会话内存 | 主密码/密钥文件仅以 `CharArray`/`ByteArray` 驻留，克隆点明确 | `SessionCredentialCache.kt` |
| B15 | 同步凭据封印 | AES-GCM，密钥 **`requireUserAuth=false`（有意决策，后台同步需锁屏可用）** | `SyncCredentialSealer.kt` L31-44 |
| B16 | 落盘位置 | 库文件 `filesDir`；同步 `.cache/.basecache` 与附件在 `cacheDir`，锁定即清 | `VaultLifecycleCoordinator.kt` L113；`SyncCacheEvictor.kt`；`FileBinaryStore.kt` |
| B17 | 剪贴板 | `EXTRA_IS_SENSITIVE` + 定时擦除（**可被用户在设置中关闭**） | `ClipboardSecurityManager.kt` L44-92 |
| B18 | 日志 | 业务代码统一 `AppLog`；release 下 v/d 被 R8 剥离，w/e 异常仅留类名 | `AppLog.kt` |

**关键结论（B9 + B10 + B15 组合）**：本项目对「同 UID 代码执行」与「未安装包名冒领」两类威胁
缺少硬性阻断层——前者被源码自己承认为设计边界（B8/B15 注释），后者只对 3 个浏览器包名做了指纹绑定。
这两点决定了下面 **AP-01 / AP-07 / AP-08 / AP-16** 构成最高优先级攻击簇。

---

## §1 攻击路径

---

### AP-01　未安装包名 / 跨命名空间冒领自动填充凭据

1. **Attack Goal**：让受害者在攻击者自己的 App 里被"正确"填充出 KDBX 中该 App 的用户名+密码。
2. **Initial Access**：安装一个普通恶意 APK（无任何特权），包名取自受害者库中未安装应用的包名。
3. **Required Privileges**：无（普通应用，零权限）。
4. **Preconditions**：
   - 受害者库中存在 `url` 字段为**包名样式**的条目（Android 应用条目的既定约定，如 `android://com.bank.app`）；
   - 该真实应用**未安装**在同一用户空间（或条目指向的域名字符串恰好可作包名）。
   - `DomainMatcher.isPackageMatch`（`DomainMatcher.kt` L137-151）剥离任意 `://` 后**精确相等**即命中，
     且 `AutofillCandidateRanker` L119-124 仅凭 package 命中就给出候选（score > 0）。
     **注意**：`https://mybank.com` 会被剥离为 `mybank.com`，而 `mybank.com` 是完全合法的 Android 包名
     —— **Web 命名空间与包名命名空间在此处混同**。
5. **Attack Steps**：
   1. 从受害者的公开信息 / 常见应用清单推测其密码库条目（或先经 AP-44 读取落盘痕迹辅助定向）；
   2. `aapt`/`apktool` 构建 APK，`applicationId` 设为 `com.bank.app`（或 `mybank.com`），声明一个登录表单；
   3. 用户打开该 App → 系统 `AutofillService.onFillRequest` 被调用，`callingPkg = com.bank.app` 命中条目；
   4. 攻击者 App 侧读取自身 `EditText`（填充结果直接写入攻击者控件）；
   5. 若填充需二次确认，攻击者用与受害者预期一致的 App 名称/图标降低戒心。
6. **Security Boundary Crossed**：应用间凭据隔离（B1）→ 被**命名空间冒领**跨越，而非被沙箱绕过。
7. **Expected Result**：攻击者获得该条目明文口令；若条目为通行密钥条目，还可获得用户名线索。
8. **Impact**：**高**（直接凭据泄露，受害者只看到一个自己"认识"的 App 请求填充）。
9. **Detection**：
   - 应用侧：`AutofillCandidateRanker` 的 `MatchReason.EXACT_PACKAGE` 且**未同时** `EXACT/PARENT_DOMAIN`
     时记录"纯包名命中"事件；同包名条目在短时间窗内反复出现可告警；
   - 设备侧：`PackageManager` 查询「命中的包名是否在本次会话前新安装 / 是否在可信商店来源」；
   - 服务端：无法检测（离线）。
10. **Mitigation**：
    - 纯包名命中（无 domain 佐证）**默认不给候选**，或走「首次绑定确认 + 记录包名+签名证书 SHA-256」；
    - **候选前校验调用方签名指纹**并与条目首次绑定时记录的指纹比对（B10 只对浏览器做了这件事，应用路径没有）；
    - `isPackageMatch` 要求条目侧必须显式 `android://` scheme（拒绝裸包名与 `https://` 误配），
      阻断 Web 域与包名命名空间混同；
    - 对「条目包名应用未安装」的情况降级为需人工二次确认的弱候选。

> 证据：`已核实`（`DomainMatcher.kt` L137-151 / L158-167；`AutofillCandidateRanker.kt` L119-142）。

---

### AP-02　webDomain 冒领（伪造受信浏览器 / DAL 缺失回退）

1. **Attack Goal**：以"浏览器"身份声明任意 `webDomain`，触发网站凭据候选并窃取明文。
2. **Initial Access**：安装恶意 APK；若目标浏览器未安装则沿用其包名，或利用 DAL 校验失败后的降级归属。
3. **Required Privileges**：无。若沿用已安装浏览器包名则需 root（签名不匹配无法覆盖安装）。
4. **Preconditions**：`AutofillOriginResolver.resolveUsableWebDomain`（L34-56）判定链为：
   受信浏览器指纹命中 → `BROWSER_DELEGATED`；否则 DAL（`assetlinks.json`）校验通过 → 归属；
   否则 `AutofillWebDomainPolicy.attribute`（L64-70）走「未验证」分支。**未验证分支的最终处置需实测确认**。
5. **Attack Steps**：
   1. 构造 App 提供 `AssistStructure`，字段 `webDomain = "example.com"`（受害者有该站条目）；
   2. 若目标浏览器未安装 → 以 `org.mozilla.firefox` 等包名安装（指纹不匹配 → 落到 DAL 分支）；
   3. 令 `https://example.com/.well-known/assetlinks.json` 校验失败（域名不受控则自然失败）；
   4. 观察是否仍下发该域候选；若下发即窃取成功。
6. **Security Boundary Crossed**：`webDomain` 归属信任（B11）→ 由调用方可控输入跨越。
7. **Expected Result**：拿到网站用户名+密码，或至少确认"未验证域仍产生候选"这一缺陷。
8. **Impact**：**高**（钓鱼面覆盖任意站点）。
9. **Detection**：记录每次候选产生的 `WebDomainAttribution` 取值分布；`UNVERIFIED` 出现即告警。
10. **Mitigation**：`UNVERIFIED` 归属**绝不产生候选**（fail-closed），只保留手动选择器兜底；
    受信浏览器白名单扩充须先取证指纹（见 `BrowserSigningFingerprints.kt` 纪律）。

> 证据：`已核实`（判定链代码）；最终放行行为 `待实测`。

---

### AP-03　伪造 AssistStructure 表单诱导填充并直接读取

1. **Attack Goal**：不再依赖包名/域名匹配，而是让用户**主动**从自动填充 UI 里选中库中任意条目。
2. **Initial Access**：恶意 APK 渲染一个高仿目标站点的登录页，并正确标注 `autofillHints`。
3. **Required Privileges**：无。
4. **Preconditions**：库已解锁（或用户愿意经链式解锁 `AutofillUnlockActivity`）；用户被诱导点选候选。
5. **Attack Steps**：
   1. 恶意 App 用 Compose/View 构造 username/password 字段并设置 `importantForAutofill=yes`；
   2. 系统回调 `onFillRequest`，本应用按 `callingPkg`/`webDomain` 生成数据集（若 AP-01/02 成立则直接命中）；
   3. 用户点选后经 `AutofillConfirmActivity` 二次确认，`EXTRA_AUTHENTICATION_RESULT` 回传 `Dataset`；
   4. 框架把明文写入**攻击者进程的控件**；攻击者 `TextWatcher`/无障碍/截图回收。
6. **Security Boundary Crossed**：用户"向可信填充器授权"的意图 → 被伪造的现实场景欺骗。
7. **Expected Result**：明文进入攻击者控件。
8. **Impact**：**高**（社会工程 + 平台机制的组合拳，几乎无法在代码层完全阻断）。
9. **Detection**：`AutofillConfirmActivity` 中显示**必填的、不可伪造的归属信息**（包名/域 + 首次/新出现标记）；
   统计「同一包名首次请求填充」的事件并要求显式授权。
10. **Mitigation**：确认页强制展示调用方**应用名 + 签名指纹摘要 + 域**；对首次出现的目标要求
    "记住此应用"式显式授权；禁止在无 UI 的自动途径下发密码（仅用户名可无交互）。

> 证据：`已核实`（`AutofillConfirmActivity.kt` L80-175；`AutofillDatasetBuilders.kt` L147-206）。

---

### AP-04　`FLAG_MUTABLE` PendingIntent 的填充注入与重放

1. **Attack Goal**：篡改/重放认证数据集 PendingIntent，造成无用户交互的填充、UI 钓鱼或会话状态污染。
2. **Initial Access**：恶意 APK 作为**自动填充客户端**（自己拥有被填充的表单）持有数据集 PendingIntent。
3. **Required Privileges**：无。
4. **Preconditions**：
   - `AutofillDatasetBuilders.kt` L235-241 的选择器 PendingIntent 为
     `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`（框架需注入 fillIn extras）；
   - 凭据路径 `CredentialPendingIntents.ENTRY_FLAGS` 同为 `FLAG_MUTABLE`（L44）。
   - 平台语义：`Intent.fillIn` 对 extras 是「base 覆盖 fillIn」，故**改写在正常情况下不成立**；
     但**重放 / 重复触发 / 改变 code / 改变 fillIn 中 base 未设置的键**是可行的。
5. **Attack Steps**：
   1. 触发一次自动填充，从 `Dataset` 中取得 `authentication` PendingIntent；
   2. 反复 `send()`（不限次数）→ 观察是否每次都能拉起 `AutofillPickerActivity`；
   3. 以 fillIn Intent 注入 base 未设置的 extra 键（如未来的新键），检查 Activity 是否读取了它；
   4. 在 Activity 显示期间用 overlay/任务切换做 UI 欺骗（注意 `setHideOverlayWindows(true)` 已阻断悬浮窗）。
6. **Security Boundary Crossed**：PendingIntent 的"仅框架可调用"隐含约束。
7. **Expected Result**：DoS（反复拉起解锁/确认窗口）、UI 钓鱼窗口、或（若存在未设值的 extra）可注入参数。
8. **Impact**：**中**（单独难以直接泄密，但与 AP-03 组合可提升成功率）。
9. **Detection**：对 `AutofillPickerActivity` / `AutofillConfirmActivity` 的启动加**一次性 nonce**
   （extra 中携带、消费即失效）；统计非框架发起（`getCallingActivity()` 异常）的启动。
10. **Mitigation**：所有认证 Activity 入口校验一次性 token 并与 `AutofillManager` 会话绑定；
    对 `EXTRA_*` 全部做白名单读取（当前已只读固定键，继续保持）；
    `FLAG_MUTABLE` 无法避免时，确保 base intent 显式组件 + 显式设置全部安全关键 extra（现状基本符合）。

> 证据：`已核实`（flags 与读取点）；`fillIn` 语义为平台行为，`待实测` 确认可否注入。

---

### AP-05　字段级屏蔽表越权写入（自动填充 DoS）

1. **Attack Goal**：让受害者的某个应用/站点永远不再被填充（拒绝服务），或污染屏蔽表以掩盖攻击。
2. **Initial Access**：普通恶意 APK（或被 AP-04 重放的 PendingIntent）。
3. **Required Privileges**：无（若 Activity 可被重放）。
4. **Preconditions**：`AutofillPickerActivity.blockFieldAndFinish`（L94-104）以
   `intent.getStringExtra(EXTRA_CALLING_PACKAGE)` 为输入直接写入 `AutofillFieldBlocklistStore.block(...)`，
   **不校验该包名与本次真实会话是否一致**。
5. **Attack Steps**：
   1. 通过重放 PendingIntent 拉起选择器；
   2. 以 fillIn extra 尝试提供 `EXTRA_CALLING_PACKAGE = <受害者常用应用包名>`；
   3. 触发 `onBlockField`，写入屏蔽记录；
   4. 目标应用此后不再获得候选（DoS），用户被迫手动复制密码（提高被 AP-21 剪贴板攻击命中的概率）。
6. **Security Boundary Crossed**：用户"以自身意图配置策略"的完整性。
7. **Expected Result**：屏蔽表被第三方写入（若 extra 可注入）。
8. **Impact**：**低-中**（可用性攻击，但可与其他攻击串联）。
9. **Detection**：屏蔽表写入时记录来源 PendingIntent 会话；同一会话外写入告警。
10. **Mitigation**：屏蔽写入必须使用**系统背书的调用方包名**（`getLaunchedFromPackage()` /
    `AutofillManager` 会话数据），绝不采信 Intent extra。

> 证据：`已核实`（读取 extra 的代码路径）；能否注入包名依赖 AP-04 的 `待实测` 结论。

---

### AP-06　自动填充保存路径投毒

1. **Attack Goal**：向受害者密码库写入攻击者控制的值（制造错误口令 / 覆盖正确口令 / 注入自定义字段）。
2. **Initial Access**：恶意 APK 触发保存请求（`PasswordSaveActivity` 链路）。
3. **Required Privileges**：无。
4. **Preconditions**：`AutofillSaveBlocklistStore` 仅按包名黑名单；用户点了"保存"。
5. **Attack Steps**：
   1. 恶意 App 提交包含特定用户名/密码的保存请求；
   2. 用户确认保存 → 库中新增/覆盖条目（覆盖路径取决于同名判定，`EntryDuplicateCoordinator`）；
   3. 若为覆盖：受害者的真实口令被替换为攻击者已知口令（下次登录失败并可能触发"忘记密码"流程 → 账号接管）。
6. **Security Boundary Crossed**：密码库写入完整性（真实用户数据）。
7. **Expected Result**：库被污染 / 账号接管前置条件达成。
8. **Impact**：**中-高**。
9. **Detection**：保存前展示条目 diff（项目已有 `EntryDetailPreviewDiffComponents`）；保存来源包名+签名记录。
10. **Mitigation**：覆盖已存在条目时强制显示 diff 并要求显式确认；保存来源纳入签名指纹复核；
    对"同用户名不同口令"的覆盖给出强提示。

> 证据：`已核实`（链路存在）；具体覆盖判定 `待实测`。

---

### AP-07　模拟器 / 无 StrongBox 设备的软件 Keystore 密钥提取

1. **Attack Goal**：提取 Keystore 中承载快速解锁封印的 AES 密钥，离线解封 `SharedPreferences` 中的密文，
   直接得到**主密码 + 密钥文件**。
2. **Initial Access**：root 的 AVD（无 TEE / 无 StrongBox，KeyStore 走软件实现）。
3. **Required Privileges**：root（模拟器）或宿主 hypervisor 访问。
4. **Preconditions**：
   - `KeystoreKeyMaterial` 在软件级 Keystore 下**不硬失败**（源码注释：`getKeySecurityLevel` 仅告警，
     "不硬失败以兼容模拟器/CI"）——B6 的 `KeyStoreManager.kt` L29-31 自述；
   - 封印密文在 `com.keepasskey.biometric_credentials` XML 中明文可读（B7）。
5. **Attack Steps**：
   1. 用户完成一次快速解锁（或主密码解锁触发重新封印）；
   2. `adb shell` 拷出 `/data/data/com.keepasskey/shared_prefs/com.keepasskey.biometric_credentials.xml`；
   3. dump `/data/misc/keystore*` / `keystore2` 数据库或用 `keymaster` 模拟实现导出密钥材料；
   4. 用导出密钥 AES-GCM 解封，按复合帧格式拆分出主密码与密钥文件；
   5. 用主密码直接打开任意副本 `.kdbx`。
6. **Security Boundary Crossed**：硬件密钥不可导出性（B6）——在软件 Keystore 上**该保证不存在**。
7. **Expected Result**：**明文主密码**。
8. **Impact**：**极高**（等同拿到库口令，且不受后续换设备影响）。
9. **Detection**：`getKeySecurityLevel(alias) != STRONGBOX/TEE` 时**拒绝封印**（而非仅告警）；
    UI 明示"本机快速解锁降级为软件密钥，不提供硬件级保护"。
10. **Mitigation**：
    - 生产策略：软件级 Keystore → **禁用快速解锁封印**（fail-closed），只允许主密码解锁；
    - 允许用户在明确风险提示下选择降级使用；
    - 封印载荷加入设备绑定因子（如 `Settings.Secure.ANDROID_ID` + 安装 nonce）以限制跨设备迁移复用。

> 证据：`已核实`（软件级不硬失败、密文落盘位置）。模拟器上具体解封路径 `待实测`。

---

### AP-08　Frida Hook 解封点截获封印载荷明文（主密码 + 密钥文件）

1. **Attack Goal**：在快速解锁成功的那一瞬间抓取 `decryptData` 的明文输出。
2. **Initial Access**：root + frida-server；进程注入。
3. **Required Privileges**：root（或可运行的 Frida gadget）。
4. **Preconditions**：
   - `B9` 的钩子探测为**磁盘路径 + `/proc/self/maps` 字符串匹配**（`frida`/`xposed`/`substrate`…），
     重命名 gadget（如 `libx.so`）或使用 memfd 加载即可规避；
   - `RuntimeIntegrityDetector.currentEnforcement()`（非 suspend 路径）**不重扫磁盘钩子**
     （源码注释承认"钩子框架为磁盘 IO，此处不扫"）。
5. **Attack Steps**：
   1. 以自定义名 gadget / 隐藏 frida-server 注入；
   2. `Java.perform` hook `com.keepasskey.app.security.KeystoreManager.decryptData` 与
      `javax.crypto.Cipher.doFinal`；
   3. 用户正常触发生物识别快速解锁；
   4. 在 hook 中 dump `byte[]`，得到复合帧；按载荷格式解析出**主密码 CharArray 与密钥文件字节**；
   5. 处理结束后恢复原函数，避免行为差异被发现。
6. **Security Boundary Crossed**：进程内机密性（B6/B14）。
7. **Expected Result**：直接读取主密码。
8. **Impact**：**极高**。
9. **Detection**：
   - 进程内完整性自检（`/proc/self/maps` 的**可执行匿名映射**检查、`dl_iterate_phdr` 比对、
     `art::JavaVMExt` 函数指针完整性校验）——当前完全缺失；
   - 关键解封调用前做一次同步的**memfd/匿名 RX 映射**扫描并对命中直接 abort。
10. **Mitigation**：接受"同 UID 代码执行可截获"这一根因（无法在应用层根治），把资源投入到
    **降低收益**：不把主密钥放入可被一次性 hook 的单一函数出口（分片解封 + 立即使用 + 立即清零），
    并让快速解锁仅是"会话级便利"（下次冷启动必须重新生物认证）。
    检测层做"提高成本"（见 Detection）而非"承诺阻断"。

> 证据：`已核实`（探测能力边界与 hook 目标函数签名）。

---

### AP-09　Frida Hook 会话凭据缓存克隆点

1. **Attack Goal**：不经封印链路，直接在会话期抓取主密码 / 密钥文件副本。
2. **Initial Access**：root + Frida。
3. **Required Privileges**：root。
4. **Preconditions**：`SessionCredentialCache` 的克隆点固定：`useCredentials`(L28)、
   `passwordSnapshot`(L41)、`cachePassword`(L47)、`rotateCredentials`(L64)。
5. **Attack Steps**：
   1. hook `cachePassword`，打印入参 `CharArray`；
   2. 或 hook `passwordSnapshot` 返回值（每次保存/同步都会取快照 → 高频命中）；
   3. 也可 hook `exportKeyFileBytes` 拿密钥文件。
6. **Security Boundary Crossed**：会话内存机密性（B14）。
7. **Expected Result**：主密码以 `CharArray` 明文出现。
8. **Impact**：**极高**（无需等待用户的特定交互，锁库前全程有效）。
9. **Detection**：同 AP-08（进程完整性自检）；额外可对 `CharArray` 快照做"使用次数"异常统计。
10. **Mitigation**：把复合密钥改为**硬件内派生**（Keystore HMAC 输出临时密钥）而不是把主密码
    长期驻留 JVM 堆；对必须驻留的窗口做"每 N 秒重新封存"；接受根因不可根治。

> 证据：`已核实`（克隆点行号）。

---

### AP-10　BiometricPrompt 结果伪造

1. **Attack Goal**：伪造 `onAuthenticationSucceeded`，使快速解锁在没有真实生物识别的情况下通过。
2. **Initial Access**：root + Frida。
3. **Required Privileges**：root。
4. **Preconditions**：
   - `BiometricAuthManager.authenticate` 的回调链可被 hook；
   - 但解封 `Cipher` 是 `AUTH_BIOMETRIC_STRONG` 的 **per-operation 密钥**：即使回调伪造成功，
     `cipher.doFinal` 仍会抛 `UserNotAuthenticatedException`。
5. **Attack Steps**：
   1. hook `AuthenticationResult` / 成功回调，令应用认为认证已通过；
   2. 观察 `Cipher.doFinal` 是否因 Keystore 门控失败；
   3. 若失败，则退化为"无收益"，记录为**有效防御**；
   4. 若成功（例如某路径未把认证与 `CryptoObject` 绑定），即为真实漏洞。
6. **Security Boundary Crossed**：认证 ↔ 密钥操作的密码学绑定（B6）。
7. **Expected Result**：**预期被 Keystore 门控挡住**（`AutofillAuthBindingPolicy.isBound` 与
   `CryptoObject` 设计正是为此）。需验证是否存在未绑定的路径（如 `authenticate` 后自行 `deliver`）。
8. **Impact**：若某路径未绑定 → **极高**；若全部绑定 → **低**（仅为 UI 状态欺骗）。
9. **Detection**：审计所有 `authenticate(...)` 调用点，确认**每个回调都从 `result.cryptoObject` 取 Cipher**
   （`AutofillAuthBindingPolicy.isBound`）。
10. **Mitigation**：保持"认证结果必须携带 `CryptoObject` 且 Cipher 必须成功 `doFinal` 才算通过"的硬约束；
    禁止任何"认证成功即放行"的旁路。

> 证据：`已核实`（设计存在 `isBound`）；是否存在旁路 `待实测`——**这是本次测试最值得优先验证的一条**。

---

### AP-11　生物录入失效标志绕过 / 旧密文重放

1. **Attack Goal**：在新增指纹（攻击者指纹）后仍能解封**受害者原有**的封印凭据。
2. **Initial Access**：物理接触 + root（用于阻止密钥吊销生效或回滚 Keystore 状态）。
3. **Required Privileges**：root。
4. **Preconditions**：`setInvalidatedByBiometricEnrollment(true)` 对**纯生物识别**密钥生效（B6）；
   该标志由 Keystore/keymaster 实现，root 下可尝试：
   (a) 冻结/回滚 `keystore2` 数据库；(b) 阻止 `onBiometricEnrollmentChanged` 通知。
5. **Attack Steps**：
   1. 备份 `/data/misc/keystore`（含密钥 blob）与 prefs XML；
   2. 录入攻击者指纹（若有生物录入失效，应导致解封失败）；
   3. 还原密钥库 blob 与 prefs，尝试解封；
   4. 观察是否抛 `KeyPermanentlyInvalidatedException`（预期抛）→ 若能绕过即成功。
6. **Security Boundary Crossed**：生物特征变更 → 凭据吊销的确定性。
7. **Expected Result**：预期被吊销机制拦住；绕过成功则攻击者指纹可长期复用受害者封印凭据。
8. **Impact**：**中-高**（需物理接触 + root）。
9. **Detection**：解封失败后是否**主动清除**陈旧凭据（源码称 `BiometricUnlockCoordinator` 会清除，
   `ISSUE-P3-25`）——验证清除是否真实执行。
10. **Mitigation**：依赖 Keystore 实现（真机 TEE/StrongBox 下可靠）；应用层保持
    "`KeyPermanentlyInvalidatedException` → 立即 `clearCredential`"的 fail-safe 路径。

> 证据：`已核实`（标志设置与预期异常处理）；绕过可能性 `待实测`。

---

### AP-12　解锁通行密钥断言伪造（同 UID 重算 HMAC + 自签公钥）

1. **Attack Goal**：构造自洽的解锁断言记录以通过 `verifyAndCommit`。
2. **Initial Access**：root 或任意同 UID 代码执行（Frida/恶意 so）。
3. **Required Privileges**：root / 同 UID 代码执行。
4. **Preconditions**：完整性 HMAC 密钥（`UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS`）**无用户认证门控**
   （`KeystoreManager.kt` L206-218 自述"无用户认证门控"）→ 同 UID 可调用它重算任意记录的 MAC；
   源码亦诚实声明（`UnlockPasskeyManager.kt` L58-60）："验证方与证明方同进程同存储，本断言不构成第二因素"。
5. **Attack Steps**：
   1. 用 Keystore 里的完整性密钥对"攻击者公钥 + credentialId + signCount"重算 MAC；
   2. 覆盖 prefs 中的记录；让验证侧认为该公钥是登记的；
   3. 生成一次性 challenge、用攻击者私钥签名 37 字节 authenticatorData + SHA-256(clientDataJSON)；
   4. `verifyAndCommit` 通过 → 断言门控放行。
6. **Security Boundary Crossed**：凭据持有性证明（B8）。
7. **Expected Result**：**断言层被完全绕过**——这正是源码自述的设计边界，不算"意外漏洞"。
8. **Impact**：**低**（对最终目标无独立价值）：断言只是通向封印解封的前置门，**解封仍需生物识别授权的
   Keystore AES 密钥**。此路径证明"断言层不提供对同 UID 攻击者的保护"，避免团队误判。
9. **Detection**：无有效手段（同 UID 可重算 MAC）。可记录"登记记录在无 enroll 事件下发生变化"。
10. **Mitigation**：**不要**把资源投在强化该层；若需要真实第二因素，必须引入应用外信任根
    （系统 Credential Manager 的 hardware-backed 证明 / 独立的认证器），而非同进程自证。

> 证据：`已核实`（源码显式声明的威胁域）。

---

### AP-13　封印凭据 + Keystore blob 回滚（回滚攻击）

1. **Attack Goal**：把快速解锁封印回滚到**攻击者已知的旧主密码**版本，从而长期解锁。
2. **Initial Access**：root（文件级备份/还原）。
3. **Required Privileges**：root 或 ADB 备份还原能力。
4. **Preconditions**：
   - 用户在 `T0` 使用主密码 `P_old`（此时封印密文 `C_old` 生成）；
   - 用户改主密码为 `P_new`（`C_new` 生成）；
   - 攻击者在 `T0` 备份了 `biometric_credentials.xml` + `keystore2` blob；
   - 密钥当前未因生物录入变化而吊销。
5. **Attack Steps**：
   1. 还原 `C_old` 与对应 Keystore blob（若被吊销则需一并回滚 blob）；
   2. 触发快速解锁 → 解封得到 `P_old`；
   3. 用 `P_old` 尝试打开最新 `.kdbx`：**失败**（库已用 `P_new` 重加密）。
6. **Security Boundary Crossed**：凭据新鲜性 / 单调性。
7. **Expected Result**：**部分成功**——得到旧主密码，但**不能**解锁已改密的当前库。
   **诚实结论：此路径价值有限**，除非用户把 `P_old` 复用到其它服务（现实且常见）。
8. **Impact**：**中**（口令复用场景下为高）。
9. **Detection**：封印凭据加入**单调计数器 / 库 header hash 绑定**：解封后校验载荷内记录的
   库版本号（当前库 `versionCode`/header hash）是否落后，落后即拒绝并要求主密码解锁。
10. **Mitigation**：封印载荷内绑定"库 header 摘要 + 封印序号"，解封时对当前库比对，不匹配即失效；
    改主密码时强制删除旧封印（验证现状是否确实删除而非新增）。

> 证据：`待实测`（重封印与改密路径行为）。

---

### AP-14　解锁失败节流记录篡改/回滚 → 主密码在线爆破

1. **Attack Goal**：移除失败节流（或把失败计数清零），对解锁界面做高速主密码猜测。
2. **Initial Access**：root 或同 UID 写文件。
3. **Required Privileges**：root / 同 UID。
4. **Preconditions**：
   - `UnlockThrottleManager` 默认**关闭节流**（AGENTS.md §29.2："解锁失败重试节流默认关闭并支持开关"）；
   - 节流记录的完整性由 `AndroidKeystoreUnlockThrottleIntegrity` HMAC 保护（`UnlockThrottleIntegrity.kt` L46-77），
     但其密钥**无用户认证门控**（"密钥在 AndroidKeyStore 内生成、不可导出，锁屏态亦可用"）→ 同 UID 可重算。
5. **Attack Steps**：
   1. 自动化 UI（`adb shell input` 或无障碍）反复提交候选口令；
   2. 每次失败后删除/回滚节流记录（或直接 hook `UnlockThrottleManager.gate` 返回放行）；
   3. 结合离线 Argon2 成本评估：若 KDF 参数较弱（用户自建库），在线/离线混合爆破可行。
6. **Security Boundary Crossed**：暴力枚举节流（B6 之外的独立防线）。
7. **Expected Result**：无节流的高速爆破；最终由 Argon2id 参数决定可行性。
8. **Impact**：**中-高**（取决于用户库的 KDF 参数与口令强度）。
9. **Detection**：UI 层统计"单位时间解锁尝试次数"（内存计数，不落盘即不可回滚）；
    对同一会话内 > N 次失败强制冷启动间隔。
10. **Mitigation**：**默认开启**节流（当前默认关闭是重大削弱）；节流状态以
    `AppLockState` 之类的进程内不可持久化状态 + 硬件单调计数器（`StrongBox` 的 rollback-resistant
    storage / `KeyInfo` 侧信道）承载；失败次数超阈值 + 时间窗指数退避 + 全局冷却。

> 证据：`已核实`（节流默认关闭见 AGENTS.md §29.2；完整性密钥无认证门控见代码注释）。

---

### AP-15　Keystore 不可达时的节流 fail-open 探测

1. **Attack Goal**：让 Keystore 操作失败，观察完整性校验是否 fail-open（放行）。
2. **Initial Access**：root（停止/冻结 `keystore2` 相关进程，或耗尽实例）。
3. **Required Privileges**：root。
4. **Preconditions**：`UnlockThrottleIntegrity` 的读取侧在 Keystore 抛异常时的分支需实测。
5. **Attack Steps**：
   1. `adb shell stop` 部分服务 / 用 Frida 让 `KeyStore.getInstance` 抛 `KeyStoreException`；
   2. 尝试解锁并观察是否仍受节流；
   3. 若异常路径直接放行 → 缺陷确认（同类缺陷项目历史上出现过：`UnlockPasskeyManagerGateTest` 有
      "JVM 无 AndroidKeyStore：KeystoreManager 返回 null → **不得回退为放行**"的断言，说明这是已知关注点）。
6. **Security Boundary Crossed**：fail-closed 约定。
7. **Expected Result**：预期 fail-closed（拒绝）；若放行则为真实缺陷。
8. **Impact**：**中**（与 AP-14 组合）。
9. **Detection**：所有完整性校验函数的异常分支必须有单测断言"抛异常 → 拒绝"。
10. **Mitigation**：把 `catch { return true }` 类模式全部改为 `catch { return false }`（fail-closed），
    并加设备侧 instrumented 用例覆盖。

> 证据：`待实测`（`app/src/test/.../UnlockPasskeyManagerGateTest.kt` 表明该模式已被关注）。

---

### AP-16　Frida 全量内存扫描抓取明文

1. **Attack Goal**：不 hook 具体函数，直接扫描进程内存中的 `CharArray`/`ByteArray`/XML 片段。
2. **Initial Access**：root + Frida。
3. **Required Privileges**：root。
4. **Preconditions**：库处于解锁态（KDBX 对象树整体驻留内存——AGENTS.md §6 已知限界）；
   `ProtectedString` 只是"纵深防御层"，源码承认"持有进程密钥或任意代码执行者仍可在读取瞬间截获明文"。
5. **Attack Steps**：
   1. `frida -U -f com.keepasskey -l scan.js`，用 `Memory.scan` 搜索特征：
      `<KeePassFile`、`<String>`、`<Value Protected="True">`、`<URL>`、`<UserName`；
   2. 对命中区域 dump 前后 4 KiB；
   3. 对 `Java.perform` 枚举 `java.lang.String` 实例（`Java.choose`）过滤长度/字符集
      （注意：主密码应为 `CharArray`，故重点搜 char 数组与 UTF-16 特征）；
   4. 结合 AP-09 的克隆点做定点确认。
6. **Security Boundary Crossed**：进程内存机密性。
7. **Expected Result**：获得明文条目树；**主密码本身**若仍在 `SessionCredentialCache` 中则一并获得
   （源码明确：主密码在会话期内驻留，直到锁定）。
8. **Impact**：**极高**（一次扫描 ≈ 全库明文 + 主密码）。
9. **Detection**：进程内存扫描无法从应用内可靠检测（会在自身内存里发现扫描痕迹但可被抹除）；
    可行的只有"降低驻留"与"缩短窗口"。
10. **Mitigation**：
    - 缩短主密码驻留：仅在实际需要重写库时重新获取（当前为会话期常驻）；
    - 大字段（含附件）已落盘、锁定即清（§35），继续保持；
    - 会话自动锁超时缩短到分钟级并默认开启；
    - **接受**：root + Frida 对手可读走解锁态全部明文，这是设计边界而非缺陷。

> 证据：`已核实`（AGENTS.md §6 自述边界 + `SessionCredentialCache` 常驻）。

---

### AP-17　`ptrace` / `/proc/pid/mem` 内存取证（无 Frida）

1. **Attack Goal**：用更隐蔽的方式（不加载任何已知钩子库）dump 解锁态内存。
2. **Initial Access**：root。
3. **Required Privileges**：root。
4. **Preconditions**：`B9` 只检查磁盘路径与 maps 中的字符串；`ptrace` 与 `process_vm_readv`
   **不产生新映射**，`maps` 扫描无感。
5. **Attack Steps**：
   1. `adb shell su -c 'cat /proc/<pid>/maps'` 定位 ART 堆（`[anon:dalvik-main space]` 等）区域；
   2. `process_vm_readv` 或 `/proc/<pid>/mem` 批量读取 → 落盘为 raw；
   3. 用 YARA/自定义脚本搜索 UTF-16 的 `<KeePassFile`、主密码候选；
   4. 或直接抓取完整 hprof 后离线分析。
6. **Security Boundary Crossed**：进程隔离（内核层被 root 绕过）。
7. **Expected Result**：同 AP-16，且**更难检测**。
8. **Impact**：**极高**。
9. **Detection**：应用可读 `/proc/self/status` 的 `TracerPid` 做**同步实时**检查（当前
   `RuntimeIntegrityDetector` 只查 `Debug.isDebuggerConnected()`，**不查 `TracerPid`**——这是明确的缺口）。
10. **Mitigation**：关键解密路径前后同步检查 `TracerPid != 0` → 立即 `clearSensitiveCache()` 并
    拒绝解锁；启用 `prctl(PR_SET_DUMPABLE, 0)` 与 `MADV_DONTDUMP`（需 native 支持）。

> 证据：`已核实`（无 `TracerPid` 检查——`RuntimeIntegrityDetector.kt` 全文）。

---

### AP-18　崩溃转储 / tombstone 落盘泄漏

1. **Attack Goal**：让应用在解锁态崩溃，读取系统 `tombstone` / `debuggerd` 产物中的内存片段。
2. **Initial Access**：root（读 `/data/tombstones`）或普通应用若 tombstones 可读（一般不可）。
3. **Required Privileges**：root。
4. **Preconditions**：native 崩溃（Rust panic 已被 `catch_unwind` 兜住；但 JNI 层越界/`abort` 仍可能）；
   或 `Debug.dumpHprofData` 被触发。
5. **Attack Steps**：
   1. 用恶意 KDBX 触发 native 异常路径（配合 AP-36）；
   2. `adb pull /data/tombstones/` 并搜索明文特征；
   3. 或用 `am dumpheap`（需 `android:debuggable` 或 root）导出 hprof。
6. **Security Boundary Crossed**：崩溃现场的机密性。
7. **Expected Result**：tombstone 中通常只有 native 栈与少量寄存器/内存块；**hprof 则可能包含明文**。
8. **Impact**：**中**（tombstone 收益低，hprof 收益高）。
9. **Detection**：无（系统行为）。
10. **Mitigation**：release 保持 `debuggable=false`；关键缓冲使用 `MADV_DONTDUMP`；
    对 native 侧机密缓冲区避免长时间驻留（Rust `Zeroizing` 已做）；监测异常崩溃率。

> 证据：`已核实`（Rust panic 不会跨 FFI，`jni_bridge.rs` L78-123）。

---

### AP-19　重打包 / 篡改 APK 注入（无签名自校验）

1. **Attack Goal**：发布"合法外观"的篡改版 KeePasskey，把用户输入的主密码外发。
2. **Initial Access**：侧载（`adb install` 或第三方渠道）。
3. **Required Privileges**：无（但需受害者在非商店渠道安装）。
4. **Preconditions**：
   - `B9` **不校验 APK 自身签名**，只查 `installer` 是否在白名单；
   - `detectUntrustedInstallSource` 在 `installer == null`（adb 直装 / 部分 ROM）时**返回 false**
     （`RuntimeIntegrityDetector.kt` L141-149）→ 完整性判定为 **TRUSTED**；
   - 隐藏 root/Magisk 路径后，`rootArtifactsDetected`/`magiskDetected` 亦为 false。
5. **Attack Steps**：
   1. `apktool d` 反编译 release APK；注入把主密码 `CharArray` base64 后 POST 到攻击者服务器的代码；
   2. 用**自签密钥**重打包（v2/v3 签名，签名与官方不同）；
   3. 受害者安装并正常使用（**无任何风险提示**，因为 installer=null 不升级风险）；
   4. 攻击者获得主密码 + 库文件（库文件可读，因为同一 App 自己解密）。
6. **Security Boundary Crossed**：分发完整性与"运行的确实是官方构建"这一保证。
7. **Expected Result**：**完全成功**，且应用自身的完整性体系（B9）不产生任何告警。
8. **Impact**：**极高**（拿到主密码 + 全库 + 可长期潜伏）。
9. **Detection**：
   - 运行期校验自身签名证书 SHA-256（与构建期写入的常量比对）——**当前完全缺失**；
   - 校验 `PackageManager.getInstallSourceInfo().initiatingPackageName` 与 `installerPackageName` 双字段；
   - 接入 Play Integrity / 硬件证明（若上架）。
10. **Mitigation**：
    - 加入签名摘要自校验（`PackageInfo` + `SigningInfo`，API 28+ `getApkContentsSigners`），
      不匹配 → 拒绝解锁敏感库或强提示；
    - `installer == null` 应视为**不可判定 → ELEVATED 而非 TRUSTED**（与 `RuntimeIntegrityPolicy`
      的"零信任 Assume Breach"原则冲突，需修正）；
    - 依赖商店分发 + 用户教育。

> 证据：`已核实`（`RuntimeIntegrityDetector.kt` L141-149 + 全文无签名校验）。

---

### AP-20　关闭 `FLAG_SECURE` 后截屏 / Recents 缩略图提取

1. **Attack Goal**：抓取解锁态界面上的明文口令（条目详情、生成器、编辑页）。
2. **Initial Access**：受害者在设置中关闭"防截屏"（或攻击者诱导其关闭，借口"我要截图分享密码"）。
3. **Required Privileges**：无（若开关关闭）。
4. **Preconditions**：`FlagSecurePolicy`（`FlagSecurePolicy.kt` L4-22）语义为
   **"用户开关 ∨ 会话锁定态并集"**——解锁态下用户可真实关闭 FLAG_SECURE（`ISSUE-P2-09` 修订）。
5. **Attack Steps**：
   1. 诱导用户关闭"防截屏"开关；
   2. 恶意 App 用 `MediaProjection`（需用户一次性授权录屏）或等待用户截图；
   3. 从 `/sdcard/Pictures/Screenshots` 或 `MediaStore` 读取（Android 13+ 需 `READ_MEDIA_IMAGES`，
       攻击者可在其 App 内申请）；
   4. 或读取 Recents 缩略图（已解锁态且 FLAG_SECURE 关闭时可见）。
6. **Security Boundary Crossed**：屏幕内容机密性（B6 之外的 UI 层）。
7. **Expected Result**：界面明文进入攻击者可读的媒体库。
8. **Impact**：**中-高**（取决于被看到的条目）。
9. **Detection**：无法从应用侧检测用户截图；可在开关关闭时持续显示常驻安全提示。
10. **Mitigation**：**始终**对条目详情/编辑/生成器施加 FLAG_SECURE（把"用户开关"限制为
    "允许截屏分享"这一**单次、显式、带提示**的动作，而非全局长期关闭）；
    对生成结果页使用"点击即隐藏 + 无剪贴板"的展示策略。

> 证据：`已核实`（`FlagSecurePolicy` 语义 + `RealSettingsRepository` L97 默认 true / L152 可改）。

---

### AP-21　剪贴板窃取

1. **Attack Goal**：读取用户复制出来的密码 / TOTP 种子。
2. **Initial Access**：恶意 APK；或前台焦点诱导。
3. **Required Privileges**：无（Android 10+ 需处于前台或持有焦点；root 无限制）。
4. **Preconditions**：
   - `ClipboardSecurityManager` 设置了 `EXTRA_IS_SENSITIVE`（挡系统气泡）并调度擦除；
   - **但** `armScheduledClear` 在 `settings.autoClearClipboard == false` 或
     `timeoutSec <= 0` 时**直接 return**（L83-91）——用户关闭后剪贴板长期驻留；
   - 擦除是**延迟**的，窗口期内可读。
5. **Attack Steps**：
   1. 诱导用户复制（或等待）；用户一旦复制，Android 10+ 下攻击者需在前台：
      用"启动即抢焦点"的 Activity / 全屏 Intent / 无障碍服务（AP-23）保持前台；
   2. `ClipboardManager.getPrimaryClip()` 读明文；
   3. 若 `autoClearClipboard=false`，可无限期重复读取；
   4. 即便为 true，在超时前（默认值可长达数十秒）完成读取即可。
6. **Security Boundary Crossed**：剪贴板跨应用隔离（B17）。
7. **Expected Result**：获得明文口令。
8. **Impact**：**中-高**。
9. **Detection**：应用侧监听 `OnPrimaryClipChangedListener` 只能看到"被改"，无法识别读取者；
    可统计"敏感复制后 5 秒内前台应用发生变化"作为风险信号并主动立即清空。
10. **Mitigation**：
    - 复制敏感内容后**固定 5-10 秒**强制清空（不提供"永不清空"选项，或该选项下明示风险并禁止复制密码）；
    - 敏感复制时监听前台变化，一旦切换应用立即清空；
    - 优先提供"自动填充直填"而非剪贴板通道。

> 证据：`已核实`（`ClipboardSecurityManager.kt` L81-92）。

---

### AP-22　通知泄漏（TOTP 动态码 / 已解锁状态）

1. **Attack Goal**：从通知栏获取 TOTP 动态码，或推断用户何时解锁。
2. **Initial Access**：恶意 APK；或直接观察锁屏。
3. **Required Privileges**：无（`VISIBILITY_SECRET` 只在锁屏生效；解锁后任何前台应用不可读他人通知，
   但 root / 无障碍 / 通知监听器可读——`NotificationListenerService` 需用户授权）。
4. **Preconditions**：`autofillShowTotpNotification` 开启（默认状态待确认）；
   `TotpNotificationPublisher` 内容仅含动态码 + 剩余秒数。
5. **Attack Steps**：
   1. 诱导用户授权通知访问（`NotificationListenerService`）；
   2. 在用户登录时读取 `extras` 中的动态码；
   3. 结合 AP-01/02 已获得的口令，在有效期内完成登录 → **绕过第二因素**。
6. **Security Boundary Crossed**：第二因素机密性。
7. **Expected Result**：获得 TOTP 动态码（30 秒窗口内可用）。
8. **Impact**：**中-高**（与口令泄露组合即完整账号接管）。
9. **Detection**：`NotificationListenerService` 授权是显式用户行为，应用侧难以检测；
    可统计"动态码通知后紧接着发生登录"无手段。
10. **Mitigation**：TOTP 通知**默认关闭**；开启时通知正文不显示动态码
    （改为"点击查看"→ 打开受保护窗口）；`setVisibility(SECRET)` 保持；缩短 `setTimeoutAfter`。

> 证据：`已核实`（`TotpNotificationPublisher.kt` L46-84）。

---

### AP-23　无障碍服务读取 Compose 语义树与填充窗口

1. **Attack Goal**：以无障碍方式直接读取屏幕上与填充结果中的明文。
2. **Initial Access**：恶意 APK 申请 `BIND_ACCESSIBILITY_SERVICE`，诱导用户在系统设置中开启。
3. **Required Privileges**：用户显式授权无障碍（**不需 root，现实中极常被滥用**）。
4. **Preconditions**：`SecurePasswordField` 是否设置 `AccessibilityNodeInfo` 的密码标记
   （`isPassword=true` / `setImportantForAccessibility=NO`）需实测；Compose 语义树的
   `EditableText` 会以 `SemanticsProperties.EditableText` 暴露。
5. **Attack Steps**：
   1. 开启无障碍服务，监听 `TYPE_VIEW_TEXT_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`；
   2. 在用户于 KeePasskey 内查看/编辑口令时抓取文本；
   3. 或在自动填充写入攻击者 App 前，先由无障碍读出目标字段值。
6. **Security Boundary Crossed**：无障碍 API 的"辅助用户"隐含信任。
7. **Expected Result**：明文口令/主密码（在主密码输入框，Compose 的 `SecurePasswordField` 若仅靠
   视觉遮挡则语义树仍暴露**需要实测确认**）。
8. **Impact**：**高**（无需 root，覆盖面广）。
9. **Detection**：`AccessibilityManager.getEnabledAccessibilityServiceList()` 可枚举已开启的无障碍服务；
   当前完整性体系**完全未考虑无障碍信号** → 建议纳入 `IntegritySignals`。
10. **Mitigation**：
    - 主密码输入框设 `isPassword` 语义 + 禁止文本变更事件外泄（Compose `SemanticsProperties.Password`）；
    - 检测到第三方无障碍服务开启时，对主密码输入施加额外防护（如"物理键盘/随机化键盘"或强提示）；
    - 键位随机化的安全键盘可显著提高无障碍批量抓取的成本。

> 证据：`部分已核实`（`SecurePasswordField.kt` 存在）；语义树暴露程度 `待实测`。

---

### AP-24　恶意 KDBX：内层 XML DTD/XXE 降级路径

1. **Attack Goal**：经恶意 `.kdbx` 触发 XXE（读取本地文件 / SSRF）或实体扩展炸弹。
2. **Initial Access**：用户导入攻击者提供的 `.kdbx`（或经 AP-29 的恶意 ContentProvider 提供）。
3. **Required Privileges**：无（需用户触发导入/打开）。
4. **Preconditions**：
   - `KdbxXmlParser` 用 `DefaultHandler2` 覆盖 `resolveEntity`（L52-55）与 `startDTD`（L65-67）做 fail-closed；
   - **但** `startDTD` 依赖 `parser.xmlReader.setProperty(LEXICAL_HANDLER_PROPERTY, handler)` 注册成功，
     该注册失败时**仅记 WARNING 并继续**（L100-104「属性不受支持时仅告警…绝不阻断合法库解析」）；
   - 若 LexicalHandler 未注册：DTD 拦截降到 `FEATURE_DISALLOW_DOCTYPE_DECL` 特性一层，
     而源码注释明确该特性在 Android Expat 后端**可能不受支持**（L42-44、L60-63）；
     此时"DTD 由特性或回调拒绝"的两层可能**同时失效**，只剩 `resolveEntity`（是否被调用取决于
     解析器是否把它作为 EntityResolver 注册——需实测）。
5. **Attack Steps**：
   1. 构造合法 `.kdbx`（已知口令），解密后把内层 XML 替换为带 `<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///data/data/com.keepasskey/shared_prefs/...">]>`;
   2. 用正确口令重新加密并保持 HMAC 有效（测试者持有口令，等价于"用户导入恶意文件"场景）；
   3. 在 root 设备上 `dumpsys`/文件观察，或用 `file://` 指向攻击者可判断存在性的路径，
      经错误信息/行为差异判定是否发生了实体解析；
   4. 同时试 billion laughs 观察是否内存暴涨（应在 `MAX_XML_DEPTH` 或字符上限前被拦）。
6. **Security Boundary Crossed**：解析器输入信任（B12）。
7. **Expected Result**：预期被 `resolveEntity`/`startDTD` 拦住；**若 `resolveEntity` 未被注册则可能 XXE 成功**。
8. **Impact**：**高**（本地文件读取；在密码管理器语境下可读 prefs/cache 中的密文与配置）。
9. **Detection**：
   - 设备侧 instrumented 用例：注入 DOCTYPE 的 KDBX 必须抛 `KdbxCorruptFileException`（当前 `AGENTS.md §6`
     明确"涉及正则/XML/平台 API 的静态逻辑不能仅凭宿主单测判定在 Android 上可用"——**此条必须在真机验证**）；
   - 运行期：解析失败时记录 `qName`/`fieldId` 摘要（不含内容）以便识别攻击尝试。
10. **Mitigation**：
    - `buildHardenedParser` 在 **LexicalHandler 注册失败时 fail-closed**（抛异常拒绝解析），而不是仅告警；
    - 解析前对 XML 前言做**字节级预检**：扫描前 N KiB 是否出现 `<!DOCTYPE`/`<!ENTITY`，命中即拒绝
      （与 `KeePassXmlImportHandler` L63 的"声明即拒绝"一致做法，不依赖平台特性支持）；
    - 明确不支持的解析器特性组合下**拒绝解析**而非降级。

> 证据：`已核实`（代码分支存在）；**成功与否 `待实测`，本清单把它列为 Android 侧最高优先级验证项之一**。

---

### AP-25　恶意 KDBX：KDF 参数滥用（资源耗尽 / 逼降级）

1. **Attack Goal**：让打开恶意库时出现不可接受的耗时/内存，迫使用户或系统放弃，或诱导用户改小 KDF 参数。
2. **Initial Access**：恶意 `.kdbx`（导入或云同步下发，见 AP-34）。
3. **Required Privileges**：无。
4. **Preconditions**：`KdbxKdfParameterCodec.validateArgon2Bounds` 存在上限（具体数值需读码/实测）；
   上限之内的最大参数（如 memory 上限 + iterations 上限）仍可能造成数十秒卡顿与显著内存占用。
5. **Attack Steps**：
   1. 构造 `kdfParameters` 取"合法但极端"值（贴近上限）；
   2. 用户打开该库 → 主线程/IO 线程长时间占用、ANR 或 OOM；
   3. 反复触发（自动同步）形成持续 DoS；
   4. 若 UI 提示"参数过高"，诱导用户"优化参数"把上限调低 → 降低后续爆破成本。
6. **Security Boundary Crossed**：可用性 + 用户安全决策的完整性。
7. **Expected Result**：ANR / OOM / 用户被诱导降低安全参数。
8. **Impact**：**中**（DoS；间接降低 KDF 强度为高价值）。
9. **Detection**：KDF 预估耗时超过阈值时**先在后台预演并显式告知**，而非直接执行；
    记录被拒绝/被调整的参数以便识别攻击模式。
10. **Mitigation**：KDF 参数改为**硬编码安全下限 + 上限内提示**，禁止用户在"打开文件"流程中被引导调参；
    解析在独立进程/Worker + 可取消 + 内存预算上限；对自动同步来源的库参数变化告警。

> 证据：`已核实`（校验存在于 `KdbxHeader.validateArgon2Bounds`）；具体阈值 `待实测`。

---

### AP-26　恶意 KDBX：GZip 解压炸弹

1. **Attack Goal**：用高压缩比 payload 制造 OOM。
2. **Initial Access**：恶意 `.kdbx`。
3. **Required Privileges**：无。
4. **Preconditions**：`SizeBoundedInputStream` 对**解压输出**计数并抛 `KdbxCorruptFileException`（B12）。
5. **Attack Steps**：
   1. 构造 gzip 内层为数十 GB 的零字节流（压缩后几百 KB）；
   2. 打开 → 观察是否在 `maxBytes` 处中止而非 OOM；
   3. 测试边界：`maxBytes` 的具体值、是否在 `read(byte[])` 的**单次大块**路径也被正确计数。
6. **Security Boundary Crossed**：解析器资源边界。
7. **Expected Result**：**预期被拦住**（该防护实现正确且逐字节计数）。
8. **Impact**：**低**（若防护有效）。
9. **Detection**：解析中止时上报"疑似解压炸弹"事件。
10. **Mitigation**：保持现有防护；把 `maxBytes` 与设备可用内存挂钩；加设备侧用例覆盖
    `read(byte[])` 路径与恰好等于上限的边界。

> 证据：`已核实`（`SizeBoundedInputStream.kt`）；边界行为 `待实测`。

---

### AP-27　恶意 KDBX：版本/密码算法降级绕过

1. **Attack Goal**：用旧版本头或未知 `CipherID` 走非预期代码路径（绕过 HMAC、触发异常、降级到弱算法）。
2. **Initial Access**：恶意 `.kdbx`。
3. **Required Privileges**：无。
4. **Preconditions**：`KdbxHeader.deserialize` 接受 `SIGNATURE_2_KDBX / _OLD / _PRE` 三种签名，
   但版本必须是 `VERSION_4_0` 主版本（L177-181）→ KDBX3 直接拒绝。
   `CipherID` 只做长度校验（L217-221），未知 UUID 交由 `CipherFactory` 裁决。
5. **Attack Steps**：
   1. 构造 `cipherUuid` = 未知值、`EncryptionIV` 长度与已知算法一致（绕过 L281-294 的长度校验）；
   2. 观察 `CipherFactory` 的异常类型与是否有信息泄漏（栈信息、日志）；
   3. 尝试 `AES_256_CBC` 与 `Twofish` 之间切换，配合 block HMAC 缺失/畸形测试完整性判定路径；
   4. 尝试 `publicCustomData` 变体字典的超长/嵌套结构。
6. **Security Boundary Crossed**：格式协商的确定性。
7. **Expected Result**：预期类型化异常 `KdbxUnsupportedVersionException` / `CryptoException`；
   若有路径未校验 HMAC 即进入解密 → 真实缺陷。
8. **Impact**：**中**（取决于是否形成"绕过完整性"或"信息泄露"）。
9. **Detection**：`AppLog` 中未知算法的事件计数；对未知 `CipherID` 明确上报而非仅异常类名。
10. **Mitigation**：`CipherID` 采用**白名单**（只允许 AES-256-CBC / ChaCha20 / Twofish），
    其余在解码阶段即拒绝；保持"先验 HMAC/头部 SHA-256 再解密"的顺序不可颠倒。

> 证据：`已核实`（头部校验逻辑）；未知算法路径 `待实测`。

---

### AP-28　恶意密钥文件（Key File）解析攻击

1. **Attack Goal**：经恶意 key file 造成解析 DoS、因子混淆，或（配合 AP-29）实现"口令因子被替换"。
2. **Initial Access**：用户导入攻击者提供的 key file；或经 SAF/Content URI 提供。
3. **Required Privileges**：无。
4. **Preconditions**：`KdbxKeyFile` 支持多种形态（32 字节原始 / 64 字节 hex / XML v1/v2）。
5. **Attack Steps**：
   1. 构造超长 XML key file（深层嵌套、超大 `Data` 元素、BBBB 编码）测试深度/长度限制；
   2. 构造恰好 32 字节但内容可控的文件（使攻击者**完全掌握**该因子）；
   3. 若应用允许"用户以为在用密码、实际只用 key file"的状态错配 → 组合降级；
   4. 测试 key file 读取失败的 fail-open 行为（无 key file 时是否退化为仅口令）。
6. **Security Boundary Crossed**：多因子组合的语义正确性。
7. **Expected Result**：预期拒绝非法形态；若某形态被静默忽略 → 攻击者仅凭口令即可（削弱多因子）。
8. **Impact**：**中**。
9. **Detection**：key file 解析失败必须显式提示"未使用密钥文件"，绝不可静默退化。
10. **Mitigation**：解析失败 → **拒绝解锁**（fail-closed）；UI 显式展示"本次使用了哪些因子"；
    对 XML key file 复用与 KDBX 内层 XML 相同的加固解析器与尺寸护栏。

> 证据：`已核实`（`KdbxKeyFile.kt` 存在多形态支持）；失败语义 `待实测`。

---

### AP-29　恶意 ContentProvider 提供导入文件（TOCTOU / 竞态）

1. **Attack Goal**：在"校验通过"与"实际读取"之间替换文件内容，绕过任何预校验。
2. **Initial Access**：恶意 APK 声明导出 Provider；诱导用户经 SAF/`ACTION_OPEN_DOCUMENT` 选择它提供的文件。
3. **Required Privileges**：无（用户选择动作）。
4. **Preconditions**：导入链使用 `content://` URI；若先用 `openInputStream` 预检（大小/魔数）后**再次打开**读取，
   则存在 TOCTOU 窗口。恶意 Provider 可在两次 open 之间返回不同内容（甚至 `ParcelFileDescriptor` 管道）。
5. **Attack Steps**：
   1. 恶意 Provider 第一次 open 返回**合法小文件**（通过大小/类型预检）；
   2. 第二次 open 返回**超大或畸形文件**（绕过预检）→ 触发解析器未受保护的路径；
   3. 或返回一个缓慢无限管道（`Pipe`）制造挂起 → 与 AP-31 的同步竞态组合；
   4. 观察是否出现过内存暴涨、ANR 或未捕获异常。
6. **Security Boundary Crossed**：输入来源信任（外部 Provider 内容不可信且可变）。
7. **Expected Result**：若预检与实际读取不是**同一 fd 流**，则可绕过；若为单流则仅剩 DoS。
8. **Impact**：**中-高**（绕过所有前置校验，直达解析器）。
9. **Detection**：对同一 URI 记录"打开次数 > 1"告警；对导入流加总读取字节上限与超时。
10. **Mitigation**：**单次 open、单流贯穿**（预检与实际解析共用同一 `InputStream`/fd）；
    或先 `Files.copy` 到应用私有临时文件（限制最大字节 + 超时），再对临时文件做全部校验与解析；
    导入后立即删除临时文件（注意 AP-44）。

> 证据：`待实测`（需读导入链实现确认是否重复 open）。

---

### AP-30　导入路径注入与字段污染（CSV / KeePass XML / AutoType）

1. **Attack Goal**：经导入的字段值实施二级攻击（自动输入序列注入、字段引用递归、UI 欺骗）。
2. **Initial Access**：诱导用户导入攻击者提供的 CSV / KeePass XML。
3. **Required Privileges**：无。
4. **Preconditions**：`FieldReferenceEngine`（`{REF:...}` 引用）、`AutoType` 序列（`KdbxXmlAutoTypeNode`）、
   自定义图标（`CustomIconCoordinator`）、`EntryReferenceDisplayResolver` 均解析条目内可控内容。
5. **Attack Steps**：
   1. 构造自引用/循环引用 `{REF:U@I...}` 与深层引用链 → 栈溢出或指数展开（DoS）；
   2. 构造 AutoType 序列含大量按键与 `{ENTER}`/`{TAB}` → 在用户触发自动输入时向**当前前台窗口**
      发送任意按键（若自动输入由应用模拟按键实现，可被用于在其它应用中执行操作）；
   3. 构造超大自定义图标 / 畸形 data URI → 图标解码路径 DoS；
   4. CSV 注入：以 `=`/`+`/`@` 开头的字段值 → 导出到 Excel 时的公式注入（供应链方向）。
6. **Security Boundary Crossed**：库内容 → 宿主行为的边界（内容被当作指令执行）。
7. **Expected Result**：DoS 稳定可复现；按键注入取决于自动输入实现（**Android 上通常不易实现**，
   需实测；本项目的 `AutoType` 支持程度需确认）。
8. **Impact**：**中**（DoS 确定；按键注入若成立则为高）。
9. **Detection**：引用解析设深度/次数上限并记录超限；图标大小与格式白名单；CSV 导出对特殊前导字符转义。
10. **Mitigation**：引用解析加**深度与总量上限**（当前需确认是否有）；自动输入在 Android 上
    **不模拟全局按键**（改为仅向自身可控通道写入）；自定义图标限制尺寸与类型；
    导出 CSV 时按 OWASP CSV Injection 转义 `= + - @ \t \r`。

> 证据：`已核实`（相关类存在）；利用效果 `待实测`。

---

### AP-31　同步回滚攻击（服务端回滚旧 KDBX 覆盖本地）

1. **Attack Goal**：让本地库被服务端上的**旧版本**静默覆盖，使攻击者已知口令的旧版本重新生效。
2. **Initial Access**：控制/入侵同步后端（WebDAV/S3）、或 MITM（AP-32）、或中间人缓存。
3. **Required Privileges**：后端写权限，或 MITM 位置。
4. **Preconditions**：`SyncRollbackGuard`（`sync/.../SyncRollbackGuard`）用 `SyncIntegrityMac` 的
   AndroidKeyStore HMAC 保护"已见版本"记录；源码注释指出"仅具备文件级写能力（ADB 备份恢复/取证）
   的攻击者无法在不触发校验失败的前提下篡改"（`KeystoreManager.kt` L206-218）。
   **但服务端回滚是"提供一个合法的旧文件"，不是篡改本地记录**——防护取决于
   guard 的记录是否**单调**且是否持久化在不可回滚的位置。
5. **Attack Steps**：
   1. 备份本地 `cacheDir/sync/**.basecache` 与 guard 记录（root）；
   2. 服务端把远端库替换为用户半年前的旧版本（攻击者已知其口令）；
   3. 触发同步 → 观察是否被 `SyncRollbackGuard` 拒绝；
   4. 若被拒绝，则同时回滚本地 guard 记录（同 UID → 可用 Keystore 重算 MAC）再试。
6. **Security Boundary Crossed**：数据新鲜性 / 单调性（B15 之外的独立防线）。
7. **Expected Result**：仅当"存在本地 monotonic 记录且不可被同 UID 重算"时才拦住；
   因 MAC 密钥无认证门控，**同 UID 攻击者可完整绕过**。
8. **Impact**：**中-高**（结合"用户曾改过口令"场景 → 库里含被替换前的旧口令）。
9. **Detection**：同步时对远端库的 `meta`（`masterKeyChanged`、header 摘要）做单调性校验；
    远端回退即告警并**拒绝自动合并**，要求用户确认。
10. **Mitigation**：把"已见库版本"绑定到**远端不可回滚的信道**（如 S3 Object Versioning / ETag 单调性）；
    对远端 header 摘要做本地单调记录并加密存于 Keystore blob 之外（如绑定到硬件单调计数器）；
    检测到回退时强制走"冲突解决"UI 而非静默覆盖。

> 证据：`已核实`（`SyncRollbackGuard` + `SyncIntegrityMac` 存在，MAC 密钥无认证门控）。

---

### AP-32　MITM（root 安装系统级 CA）

1. **Attack Goal**：解密/篡改同步流量，注入恶意 KDBX（配合 AP-34）或窃取同步凭据。
2. **Initial Access**：root（把攻击者 CA 写入 `/system/etc/security/cacerts` 或 `/apex`）＋DNS/网关控制。
3. **Required Privileges**：root（**用户 CA 已被 `network_security_config` 显式拒绝**，故必须系统级）。
4. **Preconditions**：`B5`：`cleartextTrafficPermitted=false` + 仅 `system` 信任锚 + **零证书固定**。
   `trust-anchors` 只声明 `system` 并不阻止**被植入 system store 的** CA。
5. **Attack Steps**：
   1. root 设备/中间设备植入 CA（或用 `Magisk` 模块挂钩 `TrustManager`）；
   2. 重定向 `SyncCoordinator` 的目标主机到代理（hosts / iptables / DNS）；
   3. 读取同步请求头（WebDAV Basic / S3 SigV4 —— SigV4 的签名不泄漏密钥；
      **WebDAV Basic 明文口令直接可见**，需确认使用的认证方式）；
   4. 替换上传/下载的 KDBX 为攻击者版本。
6. **Security Boundary Crossed**：传输层机密性与完整性（B5）。
7. **Expected Result**：若使用 Basic/Bearer → 同步凭据泄露；若使用 SigV4 → 仅流量可读不可改签，
   但仍可替换 payload（若服务端不校验 ETag/条件写）。
8. **Impact**：**高**（同步凭据 + 恶意库注入）。
9. **Detection**：服务端监控异常 IP/UA；客户端对下载内容做**签名/摘要复核**（当前无此机制）。
10. **Mitigation**：
    - WebDAV 强制 Digest/OAuth2 或应用层加密同步凭据（当前凭据仅用于基本认证，需确认）；
    - 引入**可选**的证书固定（或 `SPKI` 固定）——注意会阻碍云厂商证书轮换，故做"固定 + 可远程更新"；
    - 下载 KDBX 后校验"本地已知 header 摘要链"（见 AP-31），阻断注入。

> 证据：`已核实`（网络安全配置 + SigV4 有 finally 擦除的实现注释）；**WebDAV 认证方式 `待实测`**。

---

### AP-33　同步凭据封印密钥无认证门控 → 后台解封

1. **Attack Goal**：在锁屏态/无人值守时解封 WebDAV/S3 凭据。
2. **Initial Access**：root 或同 UID 代码执行（含 Frida）。
3. **Required Privileges**：root / 同 UID。
4. **Preconditions**：`SyncCredentialSealer.encrypt` 明确以 `requireUserAuth=false` 生成密钥
   （L54-55，源码同时给出"有意为之"的取舍说明）→ **无生物识别门控**。
5. **Attack Steps**：
   1. 直接调用 `KeystoreManager.getOrCreateKey(alias, requireUserAuth=false)`（同 UID 即可，无需 hook）;
   2. 读取 prefs 中同步凭据密文 → 解封得到 `CharArray`；
   3. 得到云存储账号口令 → 可读取/篡改任意同步内容（叠加 AP-31/AP-34）。
6. **Security Boundary Crossed**：云凭据机密性（B15）。
7. **Expected Result**：**稳定成功**（源码已承认此威胁域）。
8. **Impact**：**中-高**（横向到云存储，可能包含其它数据）。
9. **Detection**：无有效手段。
10. **Mitigation**：
    - 同步凭据**不使用长期口令**：改用 OAuth2 刷新令牌 + 设备私钥（AppAuth / SigV4 with STS）；
    - 或对云凭据再加一层"仅在用户解锁会话内可用"的密钥（锁屏不同步，
      用 WorkManager 的"解锁后才运行"约束 `setRequiresDeviceIdle` / 前台用户在场）；
    - 向用户明示取舍（项目已有 `ZeroKnowledgeCard` 说明，继续保持并要求确认）。

> 证据：`已核实`（源码 L31-44 明确声明）。

---

### AP-34　冲突合并投毒 / 恶意远端库覆盖

1. **Attack Goal**：用精心构造的远端库在"合并"中取得内容优势（植入条目、删除条目、覆盖口令）。
2. **Initial Access**：控制同步后端（AP-31/32/33）或共享目录。
3. **Required Privileges**：后端写权限。
4. **Preconditions**：`ConflictResolutionViewModel` / `SyncConflictController` / `ConflictStrategyMapping`
   提供合并策略；`HistoryManager` 提供条目历史。
5. **Attack Steps**：
   1. 构造"时间戳更晚"的条目版本（`Times` 字段完全可控）以在自动策略中胜出；
   2. 或替换 `UUID` 使同一逻辑条目被视作新条目 → 用户看到重复条目（凭据混淆，
      用户可能填入攻击者已知口令的那条）；
   3. 触发同步，观察自动合并是否静默采用远端；
   4. 检查被替换/被删除条目的可见性（历史是否保留可恢复）。
6. **Security Boundary Crossed**：多源数据的信任与用户裁决权。
7. **Expected Result**：攻击者内容进入本地库；若自动策略偏向"新时间戳"则成功率极高。
8. **Impact**：**中-高**（凭据混淆 + 覆盖）。
9. **Detection**：远端库条目数与 UUID 分布突变告警；"同一用户名口令被替换"事件上报。
10. **Mitigation**：远端库默认走**显式冲突裁决**而非自动合并（至少对"口令字段变更"强制人工）；
    不可删除 `HistoryManager` 中的历史；对 `Times` 字段的异常跳跃做合理性校验。

> 证据：`已核实`（相关组件存在）；策略细节 `待实测`。

---

### AP-35　APK 降级安装 / 更新面攻击

1. **Attack Goal**：让用户安装带已知漏洞的旧版本，或利用降级绕过新版修复。
2. **Initial Access**：root（`pm install -d`）或第三方渠道诱导。
3. **Required Privileges**：root（绕过 `versionCode` 单调检查）或用户配合。
4. **Preconditions**：`versionCode = 1`（首个发布版本，无降级面可言——**当前风险为零**，
   但一旦发布 ≥ 2 版本即刻生效）；签名方案为 v2+v3+v4、**关闭 v1**（B 表格外，见 `app/build.gradle.kts` L59-75）。
5. **Attack Steps**：
   1. 获取旧版 APK（含已知缺陷），`adb install -d` 强制降级；
   2. 用旧版解开同一 `filesDir` 中的库（数据目录不变，旧版可能缺少新版防护）；
   3. 或用 `v3` 轮换谱系攻击：若未正确配置 `proof-of-rotation`，换钥后可被诱导安装？
      （本项目尚未轮换密钥，属未来风险）。
6. **Security Boundary Crossed**：更新路径的完整性与降级保护。
7. **Expected Result**：root 下稳定成功；无 root 时需用户配合。
8. **Impact**：**中-高**（依赖于旧版本存在的具体缺陷）。
9. **Detection**：应用启动时把"当前 `versionCode` / 签名摘要"写入 Keystore 保护的单调记录，
    低于历史最高值 → 拒绝解锁库并告警。
10. **Mitigation**：引入降级保护记录（见 Detection）；规划密钥轮换时正确配置 v3 rotation；
    发布渠道收敛到官方商店。

> 证据：`已核实`（构建配置 + versionCode=1）。

---

### AP-36　Rust FFI 边界模糊测试（native memory / panic / 越界）

1. **Attack Goal**：用畸形输入触发 native 层崩溃、越界读（信息泄露）或 `abort`。
2. **Initial Access**：恶意 KDBX（控制 KDF 参数、IV、密文长度）＋ Frida 直接调用 `external fun`。
3. **Required Privileges**：无（经 KDBX 输入）／ root（Frida 直调）。
4. **Preconditions**：已核实原生内核的加固事实：
   - `jni_bridge::params_valid` 以**有符号 `jint` 先行**拦截负值，再 `as u32`（L29-49），注释明确指出
     "杜绝负值经 `as u32` 变巨值绕过下界检查"；
   - 全函数体 `catch_unwind`，panic → 返回 `null`，**绝不 unwind 跨 JNI**（L78-123）；
   - `unsafe` 仅两处：`/proc` 无关的 `u8→i8` 同宽位重解释（`jni_bridge.rs` L113-114、
     `jni_bridge_ext.rs` L33/68/140/146）——长度取自 `out.len()`，**无越界**；
   - `expect(...)` 只出现在"分组长度恒为 16"处（`aes_kdf.rs` L33、`twofish_cbc.rs` L28），
     由 CBC 分块逻辑保证；其余 `unwrap/expect` 全在 `#[cfg(test)]`（`lib.rs` L120+、`tests/*`）。
5. **Attack Steps**：
   1. 用 Frida 直接调用 `NativeArgon2.deriveKey` / `NativeTwofish.*` / `NativeAesKdf.*`，
      传入 `null`、零长、超大、非 16 倍数长度、负 `jint`；
   2. 经 KDBX 控制 `EncryptionIV`（已受长度校验，见 `KdbxHeader.validateEncryptionIvSize`）与密文长度；
   3. 测试 `twofish_cbc` 的 CBC 分块在**非整块长度**下的行为（Kotlin 侧 `CbcStreams` 是否已补齐）；
   4. 观察是否出现 `SIGSEGV`/`abort`（可被 AP-18 利用）或返回值异常。
6. **Security Boundary Crossed**：FFI 边界的内存安全。
7. **Expected Result**：**预期全部安全返回 null 或类型化异常**（加固到位）。
   高价值目标是找到"CBC 非整块长度"或"`jbyteArray` 长度不匹配"一类的边界缺陷。
8. **Impact**：若成立 → **高**（进程内任意读/崩溃）；若不成立 → 低。
9. **Detection**：native 侧输入校验已有；补充 `cargo fuzz` / `honggfuzz` 对四个导出函数做持续模糊测试。
10. **Mitigation**：保持现有闸门；把 `expect("分组长度恒为 16")` 改为返回错误而非 panic
    （防御性：当前由逻辑保证，但改为 `?` 更稳，且不依赖上层不变式）；
    为 `jni_bridge_ext` 的两个 Kotlin 绑定补 androidTest 边界用例。

> 证据：`已核实`（加固实现与 `unsafe` 位置）；缺陷存在性 `待实测`。

---

### AP-37　Native 不可用时的 JVM 降级路径差异

1. **Attack Goal**：让 `System.loadLibrary` 失败，迫使走纯 JVM 实现，利用两者语义差异（更弱/不一致）。
2. **Initial Access**：root（替换/删除 `.so`，或用 ABI 不匹配的环境）；或定制 ROM。
3. **Required Privileges**：root。
4. **Preconditions**：`NativeCryptoLibrary.loaded` 失败时**静默返回 false 并降级到纯 JVM**
   （`NativeCryptoLibrary.kt` L16-17、L29-36："加载失败…降级到纯 JVM 实现，不抛出、不阻断调用方"）。
5. **Attack Steps**：
   1. 删除/替换 `lib/arm64-v8a/libkeepasskey_argon2.so` 后启动；
   2. 观察是否仍能解锁（能）以及使用的 KDF 路径（`Argon2KdfEngine` 的 JVM 回退 / `AesKdfJce`）；
   3. 比较两条路径的**敏感缓冲擦除保证**：native 有 `Zeroizing` RAII（确定性擦除），
      **纯 JVM 路径的 Argon2 是否同样确定性擦除需核实**（AGENTS.md 明示 native 的收益正是确定性擦除）；
   4. 用 AP-16 内存扫描对比两条路径下明文的驻留时长与副本数量。
6. **Security Boundary Crossed**：秘密擦除的确定性与性能假设。
7. **Expected Result**：功能仍可用；**若 JVM 路径残留更多明文副本**，则攻击者可通过"强制降级"
   降低内存取证难度；同时降级也可能是**性能 DoS** 的跳板。
8. **Impact**：**中**（不直接泄密，但削弱纵深防御并可能暴露更多明文）。
9. **Detection**：`NativeCryptoLibrary.loaded == false` 时**显式告警**并写入完整性风险信号
   （当前仅静默降级）。
10. **Mitigation**：native 不可用 → 纳入 `IntegritySignals` 升级为 `ELEVATED`；
    对 JVM 回退路径补齐与 native 同等的显式擦除；文档化"降级 = 较弱保证"。

> 证据：`已核实`（静默降级逻辑）；JVM 路径擦除质量 `待实测`。

---

### AP-38　未知 Cipher / 压缩组合的异常路径探测

1. **Attack Goal**：用边界组合（未知 cipher + 合法 IV 长度 + gzip/none）迫使工厂走未测路径。
2. **Initial Access**：恶意 `.kdbx`。
3. **Required Privileges**：无。
4. **Preconditions**：`CipherFactory` / `CbcStreams` / `Pkcs7` / `InnerRandomStreamCipher` 多条分支；
   `compression` 已在头部限定为 NONE/GZIP（`KdbxHeader.kt` L231-235）。
5. **Attack Steps**：组合遍历 `{AES_256_CBC, ChaCha20, Twofish, 未知UUID}` × `{NONE, GZIP}` ×
   {合法/非法 IV 长度} × {空/超长密文}，观察异常类型、是否静默 EOF、
   是否出现 `CipherInputStream` 的静默截断（项目已明确"抛 IOException 而非静默 EOF"为基线）。
6. **Security Boundary Crossed**：解密路径的确定性与完整性。
7. **Expected Result**：所有组合应返回类型化异常；**静默截断/静默空库**为真实缺陷。
8. **Impact**：**中**（若"截断的密文被当作合法库接受"→ 可制造内容不一致的库）。
9. **Detection**：解密后必须校验 block HMAC 全部通过（项目有 `BlockHmac`/`HmacBlockStream`）；
    对"HMAC 未覆盖的尾部截断"做显式检查。
10. **Mitigation**：把所有组合写成参数化单测 + 设备侧用例；确保截断必抛异常。

> 证据：`已核实`（组件存在）；行为 `待实测`。

---

### AP-39　重打包 APK 未被检测（见 AP-19 的组件/供应链面）

> 与 **AP-19** 同源，此处从"供应链与分发"视角补充：

1. **Attack Goal**：把篡改版包装为"官方渠道"分发（第三方商店 / 网盘 / 二维码）。
2. **Initial Access**：分发渠道；用户侧载。
3. **Required Privileges**：无。
4. **Preconditions**：应用**不做签名自校验**（已核实）；无 Play Integrity。
5. **Attack Steps**：同 AP-19 步骤 1-4，并额外利用"应用内完整性 UI（`SecurityBadge`）显示的
   状态在篡改版中同样可伪造"这一点——**任何应用内的自检结果都能被重打包者改掉**。
6. **Security Boundary Crossed**：分发完整性。
7. **Expected Result**：用户与官方版无法区分。
8. **Impact**：极高（同 AP-19）。
9. **Detection**：**只有应用外**的信任根（商店签名校验、硬件证明、用户比对外部公布的签名指纹）
   才能检测。
10. **Mitigation**：公布官方签名 SHA-256 指纹供用户/第三方校验；
    接入 Play Integrity 或自建证明服务（若需上架）；
    `SecurityBadge` 明确标注"本机自检不能证明 APK 未被篡改"。

> 证据：`已核实`。

---

### AP-40　`MainActivity` exported + 任务劫持 / Intent 重定向

1. **Attack Goal**：借 exported 的启动 Activity 做任务劫持、钓鱼或状态污染。
2. **Initial Access**：恶意 APK 直接 `startActivity(Intent().setComponent(com.keepasskey/.MainActivity))`。
3. **Required Privileges**：无。
4. **Preconditions**：`MainActivity` `exported=true`（LAUNCHER 必需）；**无 deep link intent-filter**（B3）；
   `launchMode` 未声明（默认 `standard`）→ 可被拉起**多个实例**；
   Compose 导航初始目的地取决于持久化会话状态。
5. **Attack Steps**：
   1. 反复启动 `MainActivity` 制造多任务栈 / 前台抢占（用户被从银行 App 切走 → 配合 AP-21）；
   2. 用 `Intent.FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` 清空应用任务栈，
       把用户重置到解锁页（配合 AP-14 增加爆破机会）；
   3. 测试是否可传入 extra 影响初始导航（当前 `onCreate` 不读 extra → 应为不可）；
   4. 检查 Recents 中是否泄露上次会话的界面截图（受 FLAG_SECURE 保护，B18 之外）。
6. **Security Boundary Crossed**：任务栈与前台归属（用户意图）。
7. **Expected Result**：DoS / UI 干扰；**未发现直接泄密**（`onCreate` 不消费外部 extra 是好设计）。
8. **Impact**：**低-中**。
9. **Detection**：无有效手段；可用 `getLaunchedFromPackage()` 记录非 LAUNCHER 来源（需 API 支持）。
10. **Mitigation**：`android:launchMode="singleTask"` + `taskAffinity=""`；
    保持 `onCreate` 不读取外部 extra（**当前正确，须作为回归约束**）；
    对非 LAUNCHER 来源的启动做节流。

> 证据：`已核实`（Manifest + `MainActivity.kt` 全文无 extra 消费、无 deep link）。

---

### AP-41　exported 的第三方组件探测（WorkManager / ProfileInstaller）

1. **Attack Goal**：经依赖库注入的 exported 组件做信息收集或任务干扰。
2. **Initial Access**：恶意 APK 发送显式 Intent。
3. **Required Privileges**：`android.permission.DUMP`（**signature|privileged 级**，普通应用不可持有）
   ——故实际可利用性极低；`SystemJobService` 受 `BIND_JOB_SERVICE` 保护。
4. **Preconditions**（来自**合并后** manifest，已核实）：
   - `androidx.work.impl.background.systemjob.SystemJobService` `exported=true` + `BIND_JOB_SERVICE`；
   - `androidx.work.impl.diagnostics.DiagnosticsReceiver` `exported=true` + `permission=DUMP`；
   - `androidx.profileinstaller.ProfileInstallReceiver` `exported=true` + `permission=DUMP`；
   - `androidx.startup.InitializationProvider` `exported=false`；
   - `com.journeyapps.barcodescanner.CaptureActivity` **无 intent-filter**（默认不导出，安全）。
5. **Attack Steps**：
   1. 用 `adb shell dumpsys package com.keepasskey` 枚举 exported 组件；
   2. 以 `am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -n ...` 在 **root/adb shell**
      身份下触发，观察返回的诊断输出是否包含任务名/标签等敏感信息（**adb shell 持有 DUMP**）；
   3. 尝试经 `ProfileInstallReceiver` 触发 profile 操作（DoS/信息）。
6. **Security Boundary Crossed**：系统权限边界（DUMP）——对普通应用**未跨越**。
7. **Expected Result**：仅 adb/root 可触发；普通应用被权限拦截。诊断输出可能含同步相关标识（非机密）。
8. **Impact**：**低**。
9. **Detection**：无需应用侧检测；可在 CI 中加入"合并 manifest 差异审计"防止未来依赖引入
   `exported=true` 且无权限保护的组件。
10. **Mitigation**：CI 断言"所有 exported 组件必须有 `permission` 或来自白名单"；
    移除未使用的 `profileinstaller`/`work` 诊断面（如需）。

> 证据：`已核实`（`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml` L171-322）。

---

### AP-42　`MediaProjection` 录屏 + 无障碍组合

1. **Attack Goal**：在 FLAG_SECURE 关闭时持续录制解锁态界面。
2. **Initial Access**：恶意 APK 申请录屏（一次性系统弹窗）+ 无障碍（另一个授权）。
3. **Required Privileges**：用户两次显式授权（无 root）。
4. **Preconditions**：FLAG_SECURE 关闭（AP-20）；`MediaProjection` 无法捕获 `FLAG_SECURE` 窗口
   （**这是 FLAG_SECURE 的核心保证，务必验证开启时确实为空**）。
5. **Attack Steps**：
   1. 开启录屏，验证在 FLAG_SECURE 开启（默认）时捕获结果为纯黑/遮挡 —— **回归基线**；
   2. 诱导用户关闭 FLAG_SECURE（借口：需要截图分享）；
   3. 录制用户浏览口令、TOTP 种子、主密码输入过程；
   4. 结合无障碍读取输入框文本（AP-23）交叉验证。
6. **Security Boundary Crossed**：屏幕捕获保护（B18 之外）。
7. **Expected Result**：开启时不可捕获（预期）；关闭时完全可捕获。
8. **Impact**：**中-高**（依赖用户关闭开关的社会工程）。
9. **Detection**：`MediaProjectionManager.getActiveProjectionInfo()`（可用时）检测到录屏 → 强提示并**临时强制** FLAG_SECURE。
10. **Mitigation**：检测到录屏/投屏时强制启用 FLAG_SECURE（即使开关关闭）；
    把 FLAG_SECURE 的"关闭"改为**单次会话**而非持久设置。

> 证据：`已核实`（开关语义）；录屏下的实际表现 `待实测`（必须作为回归项）。

---

### AP-43　备份 / 设备迁移提取（验证性路径）

1. **Attack Goal**：经 `adb backup`、D2D 迁移或云备份导出应用数据。
2. **Initial Access**：物理接触（解锁状态下 USB 调试 / 迁移向导）。
3. **Required Privileges**：无（若 `allowBackup=true`）；本项目为 `false` + 全域排除。
4. **Preconditions**：`B4` 已核实：`allowBackup=false`、`data_extraction_rules` 排除
   `sharedpref/file/database/root` 全部域的 cloud-backup 与 device-transfer。
5. **Attack Steps**：
   1. `adb backup -f kp.ab com.keepasskey` → 预期 "Backup disabled"；
   2. `adb shell bmgr` / D2D 迁移向导 → 预期无本应用数据；
   3. 测试 `run-as com.keepasskey`（release 非 debuggable → 拒绝）；
   4. 用 root 直接读 `/data/data/com.keepasskey/` → **成功**（但这是 root 边界，不是备份边界）。
6. **Security Boundary Crossed**：本地数据导出边界。
7. **Expected Result**：**全部被正确阻断**（这是本项目做得最干净的一项）。
8. **Impact**：**极低**（无 root 时）。
9. **Detection**：不适用。
10. **Mitigation**：保持；CI 断言 `allowBackup=false` + 规则文件中的全域排除不可被删除；
    注意**新增域**（如 `device_protected`/`external`）需同步加入排除列表。

> 证据：`已核实`（Manifest + `data_extraction_rules.xml`）。

---

### AP-44　缓存 / 临时文件残留提取

1. **Attack Goal**：从 `cacheDir` 中提取 KDBX 密文快照、附件明文或原子写盘的 `*.tmp` 残留。
2. **Initial Access**：root 或同 UID 代码执行；无 root 时经恶意 `ContentProvider` 无法读取
   （应用私有目录），故**必须 root 或同 UID**。
3. **Required Privileges**：root / 同 UID。
4. **Preconditions**（已核实的落盘点）：
   - `cacheDir/sync/*.cache` / `*.basecache`：**完整 KDBX 密文快照**（`SyncCacheEvictor.kt` L15-35）；
   - `cacheDir/attachments`：**>1 MiB 附件的明文**（`FileBinaryStore.kt` L19-32；AGENTS.md §35
     "落盘磁盘缓存、锁定即清"）;
   - `AtomicFileWriter` 与 `SyncCache` 的 `*.tmp` 临时文件（`SyncCache.kt` L323-332）；
   - `filesDir/*.kdbx`：库文件本身（密文）。
5. **Attack Steps**：
   1. 解锁库、加载含大附件的条目 → 观察 `cacheDir/attachments` 出现**明文附件**；
   2. **不锁定**应用直接杀进程（`am force-stop`）→ 检查附件与 `.cache` 是否残留
      （"锁定即清"在进程被杀这条路径上是否仍然成立？**这是最值得实测的一条**）；
   3. 触发一次同步中断（断网/杀进程）→ 检查 `.tmp` 残留；
   4. 用 AP-16 的扫描方案配合，验证残留文件中的明文。
6. **Security Boundary Crossed**：静态落盘的机密性（B16）。
7. **Expected Result**：正常锁定路径应清空（设计如此）；**force-stop / 崩溃 / OOM kill 路径可能残留明文附件**。
8. **Impact**：**中-高**（附件常含密钥文件、备份码、证件扫描件）。
9. **Detection**：`MainApplication.onCreate` 启动时**无条件清空** `cacheDir/attachments` 与
   `cacheDir/sync`（幂等自愈），并在发现残留时上报。
10. **Mitigation**：
    - 启动期自愈清空（当前是否有需确认）；
    - 附件改为**加密落盘**（同一会话密钥），而非依赖"锁定即清"这一时序保证；
    - 写盘使用 `0600` + `MADV_DONTDUMP`；对 `AtomicFileWriter` 的临时文件加
      `deleteOnExit` + 启动期清理。

> 证据：`已核实`（落盘点与"锁定即清"设计）；**force-stop 路径 `待实测`**。

---

## §2 排序

### §2.1 排序一：**最现实**（不需要特殊条件、真实攻击者最可能采用）

| 排名 | 路径 | 为什么现实 |
|---|---|---|
| 1 | **AP-01** 未安装包名/跨命名空间冒领 | 零权限、零交互技巧，只依赖"库里有 App 条目"这一常态 |
| 2 | **AP-19/AP-39** 重打包注入 | 不校验签名 + `installer==null` 视为可信 = 无任何告警 |
| 3 | **AP-20/AP-42** 关掉 FLAG_SECURE 后截屏/录屏 | 用户可自行关闭；攻击者只需一次社会工程 |
| 4 | **AP-21** 剪贴板 | 用户为绕过填充失败会手动复制口令；擦除可被关闭 |
| 5 | **AP-03** 伪造表单诱导填充 | 高仿登录页 + 用户自己点选，代码层难以完全阻断 |
| 6 | **AP-23** 无障碍服务 | 用户授权即可，安卓恶意软件的主流手法 |
| 7 | **AP-16** Frida 内存扫描 | 解锁态内存中主密码常驻 + 库对象树全量驻留（源码自述） |
| 8 | **AP-14** 节流默认关闭 → 在线爆破 | 默认关闭是"配置即缺陷" |

### §2.2 排序二：**最容易**（技术门槛最低 / 工具成熟）

| 排名 | 路径 | 门槛 |
|---|---|---|
| 1 | **AP-07** 模拟器软件 Keystore 提取 | AVD 一条命令 + 现成脚本；本项目的 CI/开发环境正是模拟器 |
| 2 | **AP-16/AP-17** Frida / `process_vm_readv` 内存扫描 | 现成脚本 + 一次 `Memory.scan` |
| 3 | **AP-43** 备份提取 | `adb backup` 一条命令（**结论：被正确阻断**，列此仅为验证闭环） |
| 4 | **AP-44** 缓存残留 | `adb shell ls -l /data/data/.../cache` |
| 5 | **AP-19** 重打包 | `apktool d` + 注入 + 重签，1 小时级工作量 |
| 6 | **AP-25/AP-26** KDBX DoS | 改几个字节即可 |
| 7 | **AP-08/AP-09** Hook 解封点 | 需定位函数但签名清晰（`KeystoreManager.decryptData` / `SessionCredentialCache.cachePassword`） |
| 8 | **AP-41** exported 组件探测 | `dumpsys package` |

### §2.3 排序三：**影响最大**（拿到的东西最致命）

| 排名 | 路径 | 影响 |
|---|---|---|
| 1 | **AP-08/AP-09** Hook 解封/缓存点 → **主密码明文** | 拿到主密码 = 永久掌握该库（含历史备份与云端副本） |
| 2 | **AP-07** 模拟器软件密钥提取 → 主密码明文 | 同上，且可离线反复解封 |
| 3 | **AP-16/AP-17** 内存扫描 → 全库明文 + 主密码 | 一次性全量泄露 |
| 4 | **AP-19/AP-39** 重打包 → 主密码外发 | 长期潜伏、可远程控制 |
| 5 | **AP-01/AP-02/AP-03** 填充劫持 | 定点账号接管，可规模化 |
| 6 | **AP-23/AP-42** 无障碍/录屏 | 持续侧写，覆盖所有操作 |
| 7 | **AP-24** XXE（若成立） | 本地文件读取 → 读走密文与配置，配合 AP-07 形成链 |
| 8 | **AP-31/AP-34** 同步回滚/合并投毒 | 用旧口令版本或植入条目长期污染 |

### §2.4 综合优先级（现实 × 易 × 影响，5 分制，取乘积）

| 优先级 | 路径 | 现实 | 易 | 影响 | 乘积 | 一句话 |
|---|---|---|---|---|---|---|
| **P0-1** | AP-07 模拟器/无 StrongBox 软件 Keystore | 5 | 5 | 5 | 125 | 一条命令拿到主密码；**软件级 Keystore 必须直接禁用封印** |
| **P0-2** | AP-08 Hook 解封点 | 5 | 4 | 5 | 100 | 用户正常解锁时被动截获主密码 |
| **P0-3** | AP-09 Hook 会话缓存克隆点 | 5 | 4 | 5 | 100 | 无需等待特定交互即可拿主密码 |
| **P0-4** | AP-01 包名/命名空间冒领 | 5 | 4 | 5 | 100 | 零权限、零技巧的自动填充劫持 |
| **P0-5** | AP-16 Frida 内存扫描 | 5 | 4 | 5 | 100 | 解锁态全库明文 |
| **P0-6** | AP-20/AP-42 FLAG_SECURE 可被关闭 | 5 | 5 | 4 | 100 | 开关即缺陷；应改为单次会话 |
| **P1-1** | AP-19/AP-39 重打包未检测 | 4 | 4 | 5 | 80 | 无签名自校验 + `installer==null` 视为可信 |
| **P1-2** | AP-03 伪造表单诱导填充 | 4 | 4 | 5 | 80 | 社会工程 + 平台机制 |
| **P1-3** | AP-02 webDomain 冒领 | 4 | 4 | 5 | 80 | 未验证域归属的放行行为需实测 |
| **P1-4** | AP-17 ptrace/`/proc/mem`（无 TracerPid 检查） | 4 | 3 | 5 | 60 | 比 Frida 更隐蔽且**当前完全无检测** |
| **P1-5** | AP-23 无障碍读取 | 4 | 3 | 5 | 60 | 完整性体系**完全未纳入无障碍信号** |
| **P1-6** | AP-24 内层 XML DTD 降级（若成立） | 2 | 3 | 4 | 24→**须实测** | 两层防护可能同时失效的架构性问题 |
| **P1-7** | AP-14 节流默认关闭 | 3 | 4 | 3 | 36 | 配置即削弱 |
| **P1-8** | AP-31 同步回滚 | 4 | 3 | 4 | 48 | MAC 密钥无认证门控 → 同 UID 可重算 |
| **P2** | AP-21 剪贴板 / AP-22 通知 / AP-29 TOCTOU / AP-32 MITM / AP-34 合并投毒 / AP-44 缓存残留 | 3-4 | 3-4 | 2-4 | 24-48 | 需组合或特定条件 |
| **P3** | AP-04/05/06、AP-10/11/12/13/15、AP-25-28、AP-30、AP-33、AP-35-38、AP-40/41/43 | 1-3 | 2-5 | 1-4 | ≤36 | 单条收益有限或已被正确防护（AP-43 属**已验证通过**） |

---

## §3 获得 root 之后：哪些边界被突破、哪些仍然成立

> 本节回答"不要因为需要 root 就判定无效"。**root 不是万能钥匙**：
> 它打破的是"进程与文件系统隔离"，**不打破密码学**。

### §3.1 root 能够突破的边界

| 被突破的保证 | 机制 | 对应路径 |
|---|---|---|
| 应用沙箱（读任意应用私有文件） | SELinux + DAC 被 `su` 绕过 | AP-07/13/14/17/31/44 |
| 进程内存机密性 | `ptrace` / `process_vm_readv` / `/proc/pid/mem` | AP-16/17/18 |
| 进程内完整性 | 任意 so 注入 / 函数 hook | AP-08/09/10/11 |
| 运行完整性检测本身 | 隐藏 Magisk 路径、重命名 gadget、`installer==null` | AP-19/AP-37 |
| 剪贴板前台限制 | root 读写不受前台约束 | AP-21 |
| 屏幕捕获限制 | 系统级截屏（部分 ROM）/ `MediaProjection` 绕过 | AP-20/42 |
| 信任锚集合 | 向 system CA store 写入攻击者 CA | AP-32 |
| 降级保护 | `pm install -d` 忽略 `versionCode` 单调 | AP-35 |
| 文件级完整性（HMAC/MAC 记录） | 同 UID 可**重新调用 Keystore** 重算 MAC | AP-12/14/31 |

### §3.2 root 之后**从设计上仍然成立**的保证（务必在报告中明确，避免过度恐慌）

1. **KDBX 静态机密性**：只要攻击者**未**捕获主密码/密钥文件，root 读到的 `filesDir/*.kdbx`
   仍是 AES-256 + Argon2id 保护的密文。root **不能**在没有口令的情况下解密
   （这是**唯一**在最坏情况下仍然保护数据的根保证）。
2. **KDBX 块 HMAC 完整性**：无密钥无法伪造合法 HMAC → root **不能**在保持文件"看似合法"的前提下
   篡改条目（能做的只是**回滚到某个旧的合法版本**，见 AP-13/AP-31）。
3. **StrongBox / TEE 内密钥的不可导出性**：在具备真实安全元件的设备上，
   `getKeySecurityLevel() == STRONGBOX` 时**密钥字节始终无法被读出**，
   即使拥有内核权限（这是硬件边界，root 不等于能读安全元件内部）。
   **推论**：root 攻击者只能"使用"密钥（在满足认证条件时），**不能"复制"密钥到别的设备**。
   故针对硬件密钥，**唯一可行的路径是"在合法解封的那一刻截获明文"**（AP-08/09），
   而**不是**"离线提取密钥"。
4. **生物识别的 per-operation 门控**：`AUTH_BIOMETRIC_STRONG` 要求每次解密都有真实认证事件。
   root **无法凭空伪造一次生物认证**（AP-10 预期因 `CryptoObject` 绑定而失败）；
   攻击者必须等到受害者在场并亲自认证（或通过 Keystore 层软件实现的例外——即 §3.1 中的软件级情形）。
5. **跨应用凭据隔离（无 root 时）**：Android 沙箱 + 全部敏感 Activity `exported=false` +
   `allowBackup=false` + 全域 data-extraction 排除，**在无 root、无无障碍、无重打包的前提下成立**。
   即：AP-43（备份）与 AP-41（组件）这两类"平台外泄通道"已被正确关闭。
6. **传输层对用户安装 CA 的拒绝**：`trust-anchors` 仅 `system` → 攻击者**不能用"用户证书"
   做 MITM**；必须取得系统级写入能力（AP-32）。

### §3.3 由 §3.2 推出的防守优先级重排（工程含义）

既然"root 下无法阻止内存读取"，那么**收益最高的改动不是"加强检测"，而是"降低一次成功的收益"**：

1. **软件级 Keystore → 直接禁用快速解锁封印**（把 AP-07 从"一条命令拿主密码"变成"必须现场 Frida"），
   这是**单点收益最高**的整改；
2. **主密码不常驻会话**（把 AP-16/AP-09 的收益从"主密码 + 全库"降为"当前会话明文片段"）；
3. **附件加密落盘 + 启动期自愈清空**（把 AP-44 从"明文附件"降为"密文残留"）；
4. **同步凭据改为 OAuth/STS**（把 AP-33 的收益从"云端长期口令"降为"短期令牌"）；
5. **签名自校验 + `installer==null` 视为不可判定**（阻断 AP-19 这条**不需要 root** 的高危路径）。

---

## §4 执行清单（建议的实测顺序）

| 阶段 | 内容 | 通过判据 |
|---|---|---|
| D1 静态 | JADX/apktool 反编译 release，人工核对 §0.1 边界地图的 18 条；`dumpsys package` 枚举 exported 组件 | 与本文清单一致或有**新**发现（新发现即补登） |
| D2 平台层（无 root） | AP-01/02/03/04/05/06/20/21/22/23/40/41/43 | AP-43 必须"全绿"；AP-01/03/23 必须复现成功（否则记录为已防住并给出证据） |
| D3 恶意文件 | AP-24/25/26/27/28/29/30/38 | **AP-24 为最高优先级**：在真机上验证 DOCTYPE 注入必被拒绝 |
| D4 动态插桩 | AP-07/08/09/10/11/12/14/15/16/17/18/36/37 | AP-10 必须验证"`CryptoObject` 绑定无旁路"；AP-07 必须在软件级 Keystore 上成功 |
| D5 网络与同步 | AP-31/32/33/34 | 明确 WebDAV/S3 的认证方式与条件写语义 |
| D6 供应链 | AP-19/35/39 | 重打包版能否在无告警情况下解锁 |
| D7 报告 | 用 §2.4 更新优先级；**每条实测结论必须带证据**（命令输出/日志/截图/内存 dump 的脱敏片段） | 所有 `待实测` 项被消除或明确标注为"未验证" |

### 交付物
- 本文件（攻击路径设计与排序）
- 每条路径的实测证据包（命令 + 输出 + 最小复现物）
- 整改项按项目流程登入 `docs/ACTIVE_ISSUES.md`（P0→P3），并在 `docs/RESOLVED_LOG.md` 归档关闭证据

---

## §5 诚实边界声明

1. 本文中带 **`待实测`** 的判断是**假设**，不得作为结论引用。
2. 未在真机与模拟器上分别验证的结论，不得因为"JVM 单测通过"而认定成立
   （`AGENTS.md §6` 已记录两起"JVM 过、Android 运行挂"的缺陷逃逸）。
3. **root 相关路径的有效性必须按"是否能拿到主密码/明文"而非"是否能注入代码"来判定**；
   在 StrongBox 硬件密钥 + per-operation 生物认证成立的真机上，
   AP-07/AP-11/AP-13 的有效性显著低于模拟器，报告必须区分两种环境。

