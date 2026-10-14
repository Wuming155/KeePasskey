# KeePasskey

KeePasskey 是一款原生 Kotlin Android 密码管理器，使用标准 KeePass `.kdbx` v4 数据库，可在 Android 上管理密码、通行密钥和其他凭据。

## 核心能力

- 创建、导入和管理 KeePass v4 数据库，包括分组、附件、历史记录、搜索和回收站。
- 使用主密码、密钥文件或生物识别解锁。
- 通过 HTTPS 使用 WebDAV 或 S3 兼容存储同步。
- 通过 Android Autofill 和 Credential Manager 提供自动填充与通行密钥能力。
- 提供 TOTP/HOTP、密码生成、密码健康检查和 KeePass 字段引用。

## 构建与运行

需要 Android 开发环境；构建原生加密内核还需要 Rust stable、Android Rust targets 和 `cargo-ndk`。版本要求以项目构建配置及 Gradle 构建错误为准。

```powershell
# 构建调试 APK
.\gradlew.bat assembleDebug

# 运行 JVM 单元测试
.\gradlew.bat test --max-workers=1
```

调试 APK 位于 `app/build/outputs/apk/debug/`，可安装到 Android 16（API 36）或更新版本的设备。

## 使用与限制

创建或导入 `.kdbx` v4 数据库后，可在设置中配置 WebDAV 或 S3 同步，并使用系统自动填充。应用仅接受 HTTPS；产品行为和其他已知边界见[产品裁决](docs/architecture/产品裁决登记.md)。

当前要求 Android 16（API 36）或更新版本；不支持 KDBX v3 及以下。应用尚未发布到应用商店。

## 文档

- [文档入口](docs/README.md)
- [隐私政策](docs/security/Privacy-Policy.md)

## 官方签名指纹

官方 release 签名证书 SHA-256 指纹：

```text
F3:A6:F0:92:4D:12:1E:27:3B:E0:22:58:9F:A6:87:24:70:3C:B7:D9:06:CA:A3:3F:E4:CD:ED:19:2C:CA:84:2E
```

安装前可用 `apksigner verify --print-certs <apk>` 核对签名。应用内完整性自检不能证明 APK 来源或未被篡改；请以签名核对为准。

## 许可证

本项目以 GPL-3.0 发布，详见 [`LICENSE`](LICENSE)。
