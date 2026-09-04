# 项目记忆（Project Memory）

> 由 `CODEBUDDY.md` 索引。升级依赖、评估项目进度或判断历史决策缘由时阅读。

## 版本配套关系（升级时必须整体考虑）

| 组件 | 版本 | 约束 |
|------|------|------|
| AGP | 8.5.2 | 与 Gradle 8.10.2 配套，不兼容 Gradle 9.x |
| Kotlin | 1.9.24 | Compose Compiler 1.5.14 与其配套 |
| Hilt | 2.51.1 | 当前用 kapt，后续迁移 KSP 时需同步升级 Kotlin |
| compileSdk / targetSdk | 36 | Android 16 |
| minSdk | 36 | 仅考虑 API 36+，无旧版垫片负担 |
| JVM | 17 | 所有模块统一 source/target 17 |

构建统一使用 Gradle Wrapper（Gradle 8.10.2），Windows 下执行 `.\gradlew.bat`；分发版已缓存在本机 GRADLE_USER_HOME，离线可用。

## 项目现状

- 实际业务代码极少（仅 `MainApplication.kt`、`MainActivity.kt` 骨架），各 library 模块均为空壳。
- `ARCHITECTURE.md` 是方向的权威来源；`README.md` 记录功能清单与路线图（阶段 0-6）。
- 新增功能先查 `README.md` 路线图定位阶段，再按 `ARCHITECTURE.md` 的模块边界落位。

## 决策日志

- **2026-09-04**：架构决策确立——**项目后续全量仅考虑 API 36+（Android 16+）**。所有模块（`app`、`core`、`crypto`、`database`、`sync`）的 `compileSdk`、`minSdk`、`targetSdk` 均统一设置为 36。无需保留针对 API < 36 的向后兼容分支或冗余版本垫片，系统默认启用 Edge-to-Edge，直接调用现代 Credential Manager / FIDO2 / Passkey 与系统生物识别 API。
- **2026-09-04**：完成底部导航栏（BottomNavigationBar）集成，新增 `AppBottomBar`，包含“密码库”和“设置”一级入口，打通状态保留切换与子界面自动隐藏机制。
- **2026-09-04**：架构评审后，模块从 10 个收敛为 5 个（`app` / `core` / `crypto` / `database` / `sync`）；删除 `sync-webdav`、`sync-s3`（并入 `sync`），删除 `passkey`、`biometric`、`autofill` 独立模块（平台集成归入 `app` 内部包）。理由：零业务代码阶段过早拆分，配置重复且传递依赖复杂。
- **2026-09-04**：`database → crypto` 依赖由 `implementation` 改为 `api`，因 database 接口签名暴露 crypto 类型。
- **2026-09-04**：生成 Gradle Wrapper 8.10.2（本地缓存发行版离线生成），替代"无 Wrapper"状态。
- **2026-09-04**：`CODEBUDDY.md` 拆分为本目录下的 rules / skills / memory 分文件结构，主文件只保留概述、硬约束与索引。
- **2026-09-04**：工程规则补充 4 组密码管理器关键原则：文件 IO 原子写入（tmp → sync → rename + .bak 滚动备份）、协程调度约束（Default/IO 边界、受控 Application Scope 防临界写取消）、Credential Provider 隔离规范（无状态会话、Auto-Lock 熔断、响应超时预算）、防御性安全（FLAG_SECURE、剪贴板 EXTRA_IS_SENSITIVE + 定时清空、R8 keep 规则）。
- **2026-09-04**：完成全套 Google 官方 MVVM（Guide to app architecture）架构重构。按功能建立包（`unlock`, `vault`, `detail`, `edit`, `settings`），抽离不可变 `UiState` 数据模型与 `@HiltViewModel`（通过 `StateFlow` 单向数据流驱动），建立数据层 `VaultRepository`/`SettingsRepository` 与 Hilt DI `RepositoryModule`，并将所有 Screen 拆分为 Stateful Route 与 Stateless Content，彻底解除 Composable 与静态假数据的耦合。

## 待办与已知问题

- kapt 迁移 KSP（需与 Kotlin 升级联动）。
- 引入 version catalog（`libs.versions.toml`）收敛依赖版本。
- `crypto` 模块待引入 BouncyCastle 依赖；Argon2 纯 Java 性能待评估，NDK 加速列为远期优化。
- `database` / `crypto` 需规划 KDBX 标准测试向量与 `src/test`。
