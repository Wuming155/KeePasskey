# 架构指南（Architecture Skill）

> 由 `CODEBUDDY.md` 索引。跨模块改动、新增功能落位前阅读。`ARCHITECTURE.md` 是方向的权威来源。

## 多模块架构（5 个模块）

```
app ──> database ──> crypto ──> core
 └───> sync ────────────────> core
```

| 模块 | 类型 | 职责 |
|------|------|------|
| `app` | application | Compose UI、导航、Hilt 装配；平台集成（生物识别 / Autofill / 通行密钥认证）作为 app 内部包实现，**不再单独建模块** |
| `core` | library | 共享领域模型、`Result` 类型、工具、DI 限定符；不依赖任何模块 |
| `crypto` | library | AES / Twofish / ChaCha20 分组加密、Argon2 / AES-KDF、KDBX 块流（HashedBlock / HmacBlock） |
| `database` | library | kdbx v3/v4 解析与序列化、条目/分组模型、搜索、合并、OTP、通行密钥凭据存储 |
| `sync` | library | 文件存储抽象 + WebDAV / S3 兼容实现、本地缓存与冲突检测 |

关键依赖规则：
- 依赖严格单向，禁止反向或同层互依。
- `database` 对 `crypto` 使用 **`api`**（database 的接口签名暴露 crypto 类型，如 KdfParameters），其余一律 `implementation`。
- 若未来同步后端 SDK 冲突或体量过大，才考虑拆出 `sync-webdav` / `sync-s3`。

## 关键架构决策（修改前必读）

1. **加密与解析分离**：`crypto` 只做纯加密，不感知 kdbx 格式，可独立测试。起步用 BouncyCastle 纯 Java 实现；Argon2 / AES 的 NDK 原生加速是后续性能优化项。
2. **kdbx 版本兼容**：读取时按文件头双签名（`0x9AA2D903` / `0xB54BFB66|67`）嗅探分派解析器；版本比较只用主版本号掩码 `0xFFFF0000`，次版本号递增自动兼容。
3. **已解锁数据库所有权**：app 层持有单例 `DatabaseSession`（进程内），生物识别、Autofill、通行密钥与主进程同进程访问；仅在确有需要时才考虑独立进程。
4. **同步模型**：kdbx 同步本质是「整文件读 / 写 / 合并」，不是增量数据同步。`sync` 层提供文件存储抽象（读取、事务式写、版本哈希检测）+ 本地缓存（baseversion / version 哈希对比）；仅两端都修改时才报冲突，冲突合并下沉到 `database` 层的 KDBX merge。
5. **通行密钥路线**：先实现「kdbx 内存储 WebAuthn 凭据 + 自动填充使用」；Android 14+ `CredentialProviderService` 注册为系统凭据提供者是远期目标。不要试图从系统 Credential Manager 导出 passkey 到 kdbx（平台不允许）。
6. **UI 优先与官方 MVVM 路线**：当前处于阶段 1，界面已全量重构为谷歌官方推荐的 MVVM 架构：`UI Layer (Stateful Screen + Stateless Content) ⇄ HiltViewModel (UiState + StateFlow, UDF) ⇄ Data Layer (Repository)`。阶段 1 由 `FakeVaultRepository` 与 `FakeSettingsRepository` 提供内存数据流，后续阶段 2 真实逻辑无缝替换。
7. **安全基线**：主密码等敏感值用 `CharArray` 并显式清零（避免 String）；写库必须先写临时文件再原子落盘。
