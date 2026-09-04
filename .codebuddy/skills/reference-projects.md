# 参考项目地图（Reference Projects Skill）

> 由 `AGENTS.md` 索引。实现 `database` / `crypto` / `sync` / `passkey` 相关功能前，先查此文件定位参考实现。

`参考项目/` 包含四个成熟项目的完整源码，**仅作只读借鉴，严禁修改或复制代码入库**（许可证约束）。

## KeePassDX-master/（与本项目最贴近的 Kotlin 实现）

实现 `database`、`crypto` 模块时的直接参照：

- kdbx 解析与版本化领域模型：`database/` 模块（`element/`、`file/input|output/`）
- KDF 策略与基准调优：`database/crypto/kdf/`
- WebAuthn 凭据与签名（Ed25519 / ECDSA / RSA、COSE）：`crypto/` 模块的 `Signature.kt`
- 同步合并语义：`database/merge/DatabaseKdbxMerger.kt`
- ⚠️ 同时也是巨型类的反面教材（如 50KB 的 `DatabaseInputKDBX.kt`），借鉴格式逻辑、不借鉴文件组织。

## keepass2android-main/（同步架构参考）

- 缓存 + 三方哈希冲突检测：`src/Kp2aBusinessLogic/Io/CachingFileStorage.cs`
- 文件存储抽象与后端注册：`src/Kp2aBusinessLogic/Io/IFileStorage.cs`、`src/keepass2android-app/app/App.cs`
- 原子文件事务：`src/KeePassLib2Android/Serialization/FileTransactionEx.cs`

## KeePass-2.61.1-Source/（官方 C# 实现）

kdbx 二进制/XML 格式的权威参照，遇到格式歧义以此为准。

## Monica-main/（Kotlin / Compose 工程结构参考）

通用工程结构与 Compose 实践借鉴。

> 在这些目录内搜索实现思路即可，代码必须独立编写。
