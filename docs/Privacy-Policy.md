# KeePasskey 隐私政策（Privacy Policy）

> **文档定位**：KeePasskey for Android 对外可引用的成文隐私承诺。
> **核实基线**：2026-09-12。本文每一条承诺均与仓库代码事实逐条对应，并标注可核对的落点；
> 若某条与代码不符，以代码为准并视为缺陷（禁止写入与实现不符的承诺）。
> **适用版本**：`app/build.gradle.kts` 中 `versionName`（当前 `0.1.0`）及之后版本。

---

## 0. 一句话摘要

KeePasskey 是一款**纯本地**的密码管理器：默认情况下不联网、不注册账号、不收集任何数据；
唯一的网络行为是**用户主动启用**的云同步与**默认关闭**的已泄露密码检测，二者均可被用户完全掌控与关闭。

---

## 1. 我们收集哪些数据

**不收集任何数据。** 本应用：

- 无遥测（telemetry）、无行为分析（analytics）、无广告（ads）、无崩溃上报（crash reporting）SDK；
- 无账号体系，不需要注册、登录，不采集手机号 / 邮箱 / 设备标识符 / 广告 ID / 位置信息；
- 不采集、不上传用户库中的任何条目、口令、附件、TOTP 种子或通行密钥。

**可核对落点**：`app/build.gradle.kts` 的依赖列表不含任何分析 / 广告 / 崩溃上报 SDK
（全仓检索 `firebase` / `analytics` / `crashlytics` / `sentry` 等关键字，仅命中文案与供应链抑制文件，无实际依赖）；
`app/src/main/AndroidManifest.xml` 未申请任何标识符或位置相关权限。

> 说明：本应用使用开源 HTTP 协议栈 **OkHttp**（`app/build.gradle.kts`）作为传输实现，
> 它只是网络协议库，**不改变数据流向、不引入任何云端服务**。此外不集成任何第三方云服务 SDK。

---

## 2. 网络访问范围（仅两类，均可关闭）

本应用**仅**在两处访问网络，且都能被用户关闭：

### 2.1 云同步（默认关闭，需用户显式配置）

- 支持 WebDAV 与 S3 兼容对象存储；凭据与服务器地址**完全由用户自行填写**。
- 目标地址**只**是用户配置的那一台服务器，绝不向任何厂商服务器回传。
- 未配置或关闭同步时，本应用**零外联**。
- 同步凭据经 Android Keystore 封印后本地保存；传输全程 TLS（见 §3）。

**可核对落点**：`app/src/main/java/com/keepasskey/app/ui/screens/settings/subsscreens/CloudSyncConfigFields.kt`、
`app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt`、
`sync/src/main/java/com/keepasskey/sync/`。

### 2.2 已泄露密码检测（HIBP，默认关闭，需用户显式开启）

- 采用 [Have I Been Pwned](https://haveibeenpwned.com/API/v3#PwnedPasswords) 的
  **k-匿名（k-anonymity）范围查询**协议：客户端只发送待检密码 SHA-1 哈希的**前 5 位十六进制**，
  完整哈希与明文**永不出端**，命中比对在本地完成。
- 开关默认值为 **false**；关闭状态下代码路径直接返回「已禁用」且**不发起任何网络请求**。
- 请求仅发往 HIBP 官方基址 `https://api.pwnedpasswords.com`（HTTPS）。

**可核对落点**：默认值 `breachCheckEnabled: Boolean = false`
（[`ExtendedSettings.kt`](../app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt)、
[`SettingsUiState.kt`](../app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt)）；
关闭态零外联（[`SettingsHealthController.kt`](../app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsHealthController.kt)）；
k-匿名实现（[`HibpRangeClient.kt`](../app/src/main/java/com/keepasskey/app/data/breach/HibpRangeClient.kt)）。

**除以上两类外，本应用不存在任何其他网络访问。**

---

## 3. 传输安全

- **全局强制 HTTPS（TLS-only）**：`app/src/main/res/xml/network_security_config.xml` 以
  `cleartextTrafficPermitted="false"` 禁止一切明文 HTTP 流量，且**仅信任系统 CA**、不接受用户安装的 CA。
- **不做证书固定（no certificate pinning）**：避免阻碍云厂商合规的证书轮换。
- OkHttp 层另以 TLS-only `ConnectionSpec`（排除 CLEARTEXT）加固，与平台层形成「平台 + 传输」双层防御。

**可核对落点**：`network_security_config.xml`；
`app/src/main/java/com/keepasskey/app/di/BreachCheckModule.kt`；
`sync/` 内 `SyncHttpClientFactory`。

---

## 4. 数据存储与留存

- 用户的库文件（标准 `.kdbx`）**仅存于本地**或用户自行选择的存储位置，除非用户开启云同步。
- 本应用不设云端账户、不保留任何服务器侧副本；用户开启同步时，远端副本位于**用户自己的存储服务**，
  其留存与删除由用户在对应服务上掌控。
- **删除方式**：卸载应用即删除本地数据；如需删除云副本，请在用户自己的 WebDAV / S3 服务上删除。

---

## 5. 敏感数据的内存与日志处理

- **内存**：主密码、密钥、条目口令等敏感数据一律以 `CharArray` / `ByteArray` 承载并**显式清零**，
  绝不落地为长期存活的 `String`（Compose 文本输入等框架边界妥协已收敛到唯一的
  [`SecurePasswordField`](../app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt) 封装点）。
- **日志脱敏**：全仓业务代码统一经
  [`AppLog`](../core/src/main/java/com/keepasskey/core/log/AppLog.kt) 输出日志——
  - `v` / `d` 级别**仅调试构建**输出，release 构建由 R8 直接剥离；
  - `e` / `w` 级别在 release 下**只保留异常类名**，不透出异常 message 与堆栈（避免携带主机地址、用户名等敏感标识）；
  - 日志**严禁**包含主密码、口令、TOTP 种子等明文。
- **防截屏**：提供 `FLAG_SECURE` 开关；锁定态强制遮蔽，解锁态随开关真实生效。

---

## 6. 权限清单与用途

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 仅供用户启用的云同步与默认关闭的 HIBP 查询 |
| `USE_BIOMETRIC` | 指纹 / 生物识别快速解锁（本地校验） |
| `POST_NOTIFICATIONS` | 解锁态常驻通知、TOTP 通知 |
| `HIDE_OVERLAY_WINDOWS` | 防覆盖窗口钓鱼（防点击劫持） |

**可核对落点**：`app/src/main/AndroidManifest.xml`。

---

## 7. 「禁网」构建变体评估（noNet）

本仓于 2026-09-12 评估过新增 `productFlavors { noNet }`（裁剪同步与泄露检测、构建期不申请网络权限）。
**当前结论：暂不实施**，原因如下（属可复核的工程权衡，非隐私承诺弱化）：

1. **与发布产物契约冲突**：引入 flavor 会使 `assembleRelease` 产物路径由
   `app-release.apk` 变为 `app-<flavor>-release.apk`，直接违反 `AGENTS.md` §3.8 约定的稳定版产物路径，
   并波及 CI 与既有安装/升级链路。
2. **裁剪面极大、回归风险高**：同步与泄露检测已深入设置导航、Hilt 注入、WorkManager 后台任务与
   自动填充 / Passkey 链路，flavor 化需要成体系的 `BuildConfig` 条件编译与源集隔离，
   易在「看起来禁网、实则留旁路」的方向上引入隐蔽缺陷——这与本项目「宁缺毋滥」的安全取向相悖。
3. **无实际增益**：默认配置下本应用本就不联网（同步默认关闭、HIBP 默认关闭，见 §2），
   所谓「禁网」在当前默认行为面前几无额外保护收益。

**替代与展望**：如确有硬性「禁网」需求，建议以独立仓库分支 / 独立发行渠道维护，
在保持主仓产物契约不变的前提下裁剪同步与泄露检测模块（届时需移除 `INTERNET` / `ACCESS_NETWORK_STATE` 权限）。

---

## 8. 政策变更

本政策随应用版本更新；如发生实质性变更，将在对应版本的发布说明与本文档中同步注明。

---

## 9. 联系方式

如对本政策或数据处理有疑问，请通过本仓库的 Issue 渠道反馈。
