# KeePasskey

> 用 Kotlin 开发的 Android 版 KeePass 密码管理器：读写标准 `.kdbx` **v4** 数据库，支持 **WebDAV** / **S3 兼容协议** 同步，原生支持 **通行密钥（Passkey）** 自动填充与解锁，覆盖 KeePass 常用功能。

## 特性亮点

- **KeePass 兼容**：仅 v4（`v3` 及以下明确拒绝），与 KeePass / KeePassXC / pykeepass 真实互通
- **多方式同步**：WebDAV（Nextcloud、ownCloud 等）与 S3 兼容协议（AWS S3、MinIO、Cloudflare R2 等）
- **通行密钥**：FIDO2 / WebAuthn 凭据的安全存储，并可作为设备绑定解锁方式与系统自动填充凭据
- **全功能**：分组树 / 条目字段 / 附件 / 历史版本与回滚 / 模板 / 回收站 / 全文搜索
- **增强**：TOTP·HOTP、密码生成器（含 Diceware）、离线密码健康度审计（可选联网泄露比对，k-匿名、默认关闭）、KeePass 字段引用 `{REF:...}`
- **安全优先**：主密码 + 密钥文件 + 生物识别解锁；`ProtectedString` 驻留加密、FLAG_SECURE、自动锁定熔断
- **仅 Android 16+（API 36+）**：Compose + Material 3，无需向下兼容负担

## 构建与运行

环境前提：JDK 17、Android SDK 37（平台 `android-37.0`）、Android Studio（AGP 9.4.0）、**NDK 28.2.13676358 + CMake 3.22.1**（Argon2 原生加速构建所需，`sdkmanager` 安装）。

```bash
# 编译调试包
.\gradlew.bat assembleDebug
# 单元测试（全模块；12 例真实联调用例需加 -DliveSyncTest 启用）
.\gradlew.bat test
# 混淆发布包（构建链路就绪，尚未发布到应用市场）
.\gradlew.bat assembleRelease
```

将 `app/build/outputs/apk/debug/*.apk` 安装到 Android 16+ 设备即可使用。

## 基本使用

1. **创建 / 打开库**：主密码（+ 可选密钥文件）建库，或从外部导入 `.kdbx` v4。
2. **解锁**：主密码、密钥文件或生物识别；支持设备锁屏绑定的快速解锁。
3. **同步**：设置页配置 WebDAV / S3 账号，手动或周期性后台同步，冲突时可视化逐字段合并。
4. **自动填充**：系统 Autofill 或 Credential Provider 双通道，含 IME 内联建议；通行密钥由系统 Credential Manager 直接调用。

## 文档

| 文档 | 内容 |
|------|------|
| [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) | **现存问题与待办清单**：按 P0 → P1 → P2 → P3 排序，自包含背景与验收标准 |
| [`docs/RESOLVED_LOG.md`](docs/RESOLVED_LOG.md) | **已整改问题与历史任务归档**：已完成核心任务与 120+ 审查项代码证据 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 模块依赖拓扑、技术栈、关键架构决策与目录约定 |
| [`docs/reference-projects.md`](docs/reference-projects.md) 与 [`docs/references/`](docs/references/) | 5 个参考项目的定位地图与深度架构分析 |
| `AGENTS.md` | 给 AI 协作代理的项目约束、版本基线与闭环纪律 |

## 已知局限

> 完整实时清单见 [`docs/ACTIVE_ISSUES.md`](docs/ACTIVE_ISSUES.md) 与 `AGENTS.md` §6。

自定义键盘（Magikeyboard 式）未实现（已提供 IME 内联建议 + 自动填充替代）；`KDBX v3` 及以下明确拒绝；应用尚未发布至 F-Droid / GitHub Release。

## 许可证

以 **GPL-3.0** 开源发布，完整文本见 [`LICENSE`](LICENSE)。

## 致谢

感谢 KeePassDX / keepass2android / KeePass 官方 / KeePassXC / Monica 等开源标杆的架构与设计借鉴；密码学基于 [BouncyCastle](https://www.bouncycastle.org/)，网络栈基于 [OkHttp](https://square.github.io/okhttp/)，平台与 DI 基于 AndroidX、[Jetpack Compose](https://developer.android.com/compose) 与 [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)。本项目代码独立编写，仅借鉴其架构与设计思路。
