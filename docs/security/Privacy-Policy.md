# KeePasskey 隐私政策（Privacy Policy）

> KeePasskey for Android 的数据处理承诺。发布前应按实际发行行为复核。

---

## 0. 一句话摘要

KeePasskey 是一款以本地存储为主的密码管理器，不要求注册账号，也不集成遥测、广告或崩溃上报。
同步、已泄露密码检测和通行密钥站点归属校验会在各自条件下发起网络请求；其目标和可用设置见下文。

---

## 1. 我们收集哪些数据

本应用不向 KeePasskey 运营的服务器收集或发送数据，也不集成遥测、广告或崩溃上报 SDK：

- 无遥测（telemetry）、无行为分析（analytics）、无广告（ads）、无崩溃上报（crash reporting）SDK；
- 无账号体系，不需要注册、登录，不采集手机号 / 邮箱 / 设备标识符 / 广告 ID / 位置信息；
- 不向 KeePasskey 运营的服务发送用户库内容。用户启用同步时，加密数据库会发送到用户配置的 WebDAV / S3 服务；HIBP 查询会发送密码 SHA-1 哈希的前缀，详见 §2。

**可核对落点**：`app/build.gradle.kts` 的依赖列表不含任何分析 / 广告 / 崩溃上报 SDK
（全仓检索 `firebase` / `analytics` / `crashlytics` / `sentry` 等关键字，仅命中文案与供应链抑制文件，无实际依赖）；
`app/src/main/AndroidManifest.xml` 未申请任何标识符或位置相关权限。

> 说明：本应用使用开源 HTTP 协议栈 **OkHttp**（`app/build.gradle.kts`）作为传输实现，
> 它只是网络协议库，**不改变数据流向、不引入任何云端服务**。此外不集成任何第三方云服务 SDK。

---

## 2. 网络访问

应用网络请求由用户配置和正在使用的功能决定。以下列出已确认的请求路径：

### 2.1 云同步（默认关闭，需用户显式配置）

- 支持 WebDAV 与 S3 兼容对象存储；凭据与服务器地址**完全由用户自行填写**。
- 目标地址**只**是用户配置的那一台服务器，绝不向任何厂商服务器回传。
- 未配置或关闭同步时，不会发起云同步请求；HIBP 与 Digital Asset Links 请求仍分别按 §2.2 和 §2.3 所述条件触发。
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
（[`ExtendedSettings.kt`](../../app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt)、
[`SettingsUiState.kt`](../../app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt)）；
HIBP 关闭时不发起该功能的请求（[`SettingsHealthController.kt`](../../app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsHealthController.kt)）；
k-匿名实现（[`HibpRangeClient.kt`](../../app/src/main/java/com/keepasskey/app/data/breach/HibpRangeClient.kt)）。

### 2.3 通行密钥站点归属校验（Digital Asset Links）

- 在通行密钥注册流程中，若用户未启用“跳过通行密钥站点归属校验”，应用可能向本次注册所用 RP 域名请求 `https://<rpId>/.well-known/assetlinks.json`。
- 自动填充对非受信浏览器调用方校验其提供的 web 域名时，也可能请求该域名对应的同一路径；注册侧的“跳过”设置不关闭自动填充的域归属校验。
- 请求是无请求体的 GET，并显式发送 `Accept: application/json`。请求目标是用户当前交互所涉及的 RP / web 域名，不是 KeePasskey 厂商服务；应用不在该请求中附加数据库条目、口令或凭据载荷。目标站点会收到该路径请求及常规网络连接信息。
- 注册校验默认不跳过，可通过上述设置关闭注册侧请求；自动填充侧没有可关闭该校验的对应设置。校验结果可能由应用在内存中缓存。

**可核对落点**：[`DigitalAssetLinksVerifier.kt`](../../app/src/main/java/com/keepasskey/app/passkey/DigitalAssetLinksVerifier.kt)、[`AutofillOriginResolver.kt`](../../app/src/main/java/com/keepasskey/app/autofill/AutofillOriginResolver.kt)、[`ExtendedSettings.kt`](../../app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt)。

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
- **删除方式**：卸载会移除应用私有存储中的数据。通过系统文件选择器在 SAF 文档提供方中创建或选择的外部数据库文件位于用户选择的位置，不属于应用私有存储；卸载不等于删除这些文件，用户需在相应提供方中自行管理或删除。云端副本也需在用户自己的 WebDAV / S3 服务上删除。

---

## 5. 敏感数据的内存与日志处理

- **内存**：应用尽量以可显式清理的字符或字节缓冲处理密码和密钥。平台接口、界面状态、展示数据或数据库模型的部分业务路径可能持有不可擦除的字符串；丢弃引用不能保证立即或确定地清除其内存副本。
- **日志脱敏**：日志不得包含主密码、口令、TOTP 种子或其他敏感明文。
- **防截屏**：防截屏设置开启时，随设置控制的窗口受到系统保护；关闭时，这些窗口可能被截屏、录屏或显示在最近任务预览中。建库、修改主密钥、子库凭据、附件预览和修订差异对话框始终受保护；TOTP 与通行密钥扫码窗口遵循该设置。关闭保护前会要求确认风险；已产生的截图或录屏副本无法由应用追溯删除。

---

## 6. 权限清单与用途

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 云同步、默认关闭的 HIBP 查询，以及符合条件时的 Digital Asset Links 归属校验 |
| `CAMERA` | 用户在条目编辑中选择 TOTP 二维码扫码时采集相机画面；应用使用 CameraX 取帧并在设备上解码二维码，将识别文本交给条目编辑流程。扫码实现不发起网络请求，也不上传相机画面或识别结果。也可从系统照片选择器导入二维码图片，无需相机权限。 |
| `USE_BIOMETRIC` | 指纹 / 生物识别快速解锁（本地校验） |
| `POST_NOTIFICATIONS` | 解锁态常驻通知、TOTP 通知 |
| `HIDE_OVERLAY_WINDOWS` | 防覆盖窗口钓鱼（防点击劫持） |

**可核对落点**：`app/src/main/AndroidManifest.xml`。

---

## 7. 政策变更

本政策随应用版本更新；如发生实质性变更，将在对应版本的发布说明与本文档中同步注明。

---

## 8. 联系方式

如对本政策或数据处理有疑问，请通过本仓库的 Issue 渠道反馈。
