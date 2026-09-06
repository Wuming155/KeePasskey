---
name: wave14-transport-security-and-health-check-roadmap
overview: 本轮执行 Wave 14 传输安全整改：全量移除证书固定（9 处触点）并声明平台级 Network Security Config 强制 HTTPS（含 https-only 输入校验与文案修正）；同时制定体检报告全量条目的分批整改路线图（Wave 15+：kapt→KSP、DataStore、版本目录、Baseline Profiles 等），本轮不执行。
todos:
  - id: remove-pinning-sync-module
    content: 移除 SyncNetworkOptions.pinnedHosts 字段与 SyncHttpClientFactory 证书锁定组装，KDoc 收敛为 TLS-only 加超时职责
    status: completed
  - id: remove-pinning-app-layer
    content: 移除 app 层证书锁定链路（SyncCoordinator 解析器、SyncCredentialsStore 键与字段、SettingsViewModel/SettingsUiState、KeePasskeyApp 回调），加载时物理清除遗留键
    status: completed
    dependencies:
      - remove-pinning-sync-module
  - id: remove-pinning-ui-strings
    content: 删除 WebDavSyncScreen 证书锁定 UI 区块与中英 cert_pins 字符串，修正 S3 path-style「自建」文案
    status: completed
    dependencies:
      - remove-pinning-app-layer
  - id: force-https-config
    content: 新增 network_security_config.xml（base-config 禁明文加仅系统 CA）并挂载 Manifest；WebDAV/S3 端点实施 https-only fail-fast 校验
    status: completed
  - id: update-tests-verify
    content: 用 [subagent:code-explorer] 核查 certPins/pinnedHosts 零残留，更新 pin 与 https 相关测试，gradlew test 全绿且 assembleDebug 通过
    status: completed
    dependencies:
      - remove-pinning-ui-strings
      - force-https-config
  - id: write-remediation-roadmap
    content: 用 [mcp:google-developer-knowledge] 核实各批次官方建议，编写 docs/HEALTH_CHECK_ROADMAP.md 全量体检项分批整改路线图
    status: completed
  - id: docs-and-commit
    content: 作废 DELIVERY_PLAN 局域网明文豁免设计、刷新 AGENTS.md Wave 14 状态与新前提，全量暂存并语义化 Git 提交
    status: completed
    dependencies:
      - update-tests-verify
      - write-remediation-roadmap
---

## 产品概述

基于《KeePasskey 技术体检报告》的全面分批整改：本阶段执行「网络安全整改」两项具体要求，并同步产出全量体检项的分批整改路线图文档（后续批次仅规划不执行）。

## 方案前提（新）

- 应用不支持用户自建服务器（不含内网 NAS、私有 WebDAV、本地 S3），所有同步通信仅面向正规公网商业云服务。
- 核心意图：杜绝明文流量暴露；避免固定证书阻碍云厂商证书轮换导致连接阻断。

## 本阶段核心功能

### 1. 移除证书固定（Pinning）

- 删除 SyncCredentialsStore.kt 中的 KEY_WEBDAV_CERT_PINS、WebDavCredentials.certPins 字段与 saveWebDavCertPins 方法。
- 删除 SyncNetworkOptions.pinnedHosts 契约字段、SyncHttpClientFactory 的 CertificatePinner 组装逻辑、SyncCoordinator 的 buildNetworkOptions 解析器与调用点。
- 删除 SettingsViewModel / SettingsUiState / WebDavSyncScreen / KeePasskeyApp 中的证书锁定状态、方法、UI 输入区与回调传递。
- 删除中英 strings.xml 各 4 条 cert_pins 文案。
- 证书验证完全依赖 Android 系统默认 CA 链，不新增任何自定义信任逻辑。
- 旧版本用户已存储的 webdav_cert_pins 键在加载时物理清除，杜绝残留配置继续生效。

### 2. 废除局域网 HTTP 明文豁免，全站强制 HTTPS

- 作废 DELIVERY_PLAN 阶段 5「自签名 SSL/TLS 证书信任与局域网 HTTP 明确豁免」旧设计描述。
- 新增全局网络安全配置：base-config 设 cleartextTrafficPermitted="false"，信任锚仅系统 CA，不设任何 domain-config 明文豁免，并挂载至 Manifest。
- WebDAV 与 S3 端点实施 https-only 校验：显式输入 http:// 直接拒绝并给出用户可理解的错误提示；无 scheme 输入自动补 https；保存配置时即时校验，实现 fail-fast 而非网络层晦涩失败。

### 3. S3 Path-Style 寻址保留

- usePathStyle 字段、UI 开关、存储键全部保留不动；仅修正含「自建 MinIO」等表述的中英文案（Cloudflare R2 等商业 S3 兼容服务仍可使用该开关）。

### 4. 测试与验证

- 移除证书固定相关测试断言；新增遗留键清除用例、http:// 拒绝用例与 TLS-only 客户端断言。
- 全模块单元测试全绿 + assembleDebug 编译通过作为验收门禁。

### 5. 文档与路线图

- 编写全量体检项分批整改路线图文档（涵盖 kapt→KSP、Settings 迁移 DataStore、Gradle 版本目录、Baseline Profiles、Material 3 Expressive、依赖货币性与 minSdk 决策等批次，含各批次范围、风险与验收标准）。
- 刷新 AGENTS.md 阶段状态与新前提，阶段成果全量暂存并提交 Git。

## Tech Stack

- Android 原生 Kotlin + Jetpack Compose（既有栈，本阶段零新增依赖）
- 网络层：OkHttp 4.12.0（sync 模块）+ Android Network Security Config（平台级策略，XML 资源）
- 构建：Gradle 9.3.1 / AGP 9.1.0 / Kotlin 2.4.10 / compileSdk = minSdk = targetSdk = 36
- 测试：JUnit4 + kotlinx-coroutines-test + MockWebServer（既有测试基建）

## Implementation Approach

1. **证书固定移除（自内向外收敛）**：先删 sync 模块契约（`SyncNetworkOptions.pinnedHosts`）与 `SyncHttpClientFactory` 的 `CertificatePinner` 组装（工厂回归「TLS-only + 显式超时」单一职责），再删 app 层链路（`SyncCoordinator.buildNetworkOptions` 解析器及调用点、`SyncCredentialsStore` 字段/方法/存储键、Settings 三处状态与方法、`KeePasskeyApp` 回调、`WebDavSyncScreen` UI 区块），最后收敛字符串资源与测试。移除后证书验证即走系统默认 CA 链——已核实代码库不存在自定义 TrustManager，无需新增任何信任逻辑。
2. **平台级强制 HTTPS**：新增 `app/src/main/res/xml/network_security_config.xml`，`<base-config cleartextTrafficPermitted="false">` 并显式声明 `<certificates src="system"/>` 信任锚——将当前 targetSdk 28+ 的隐式默认行为版本化固化，同时防范用户证书注入 MITM（密码管理器场景的实质安全增益），不设任何 domain-config 豁免；Manifest `application` 节点挂载 `android:networkSecurityConfig`。OkHttp 层 `ConnectionSpec` 排除 CLEARTEXT 的既有 Wave 12 防线保留，形成「平台层 + 传输层」双层防御。
3. **https-only fail-fast**：在 WebDAV/S3 Provider 的 URL 构建处校验 scheme（显式 `http://` 抛类型化错误并上浮用户可理解提示；无 scheme 按 S3 现状补 `https://`）；`SettingsViewModel` 保存路径同步校验以即时反馈。
4. **遗留数据清理**：对齐 `SyncCredentialsStore` 既有 legacy 迁移模式（旧版明文 S3 AccessKey 读取即迁移并 remove），加载 WebDAV 配置时物理 remove 遗留 `webdav_cert_pins` 键。
5. **分批路线图**：以体检报告条目按风险与依赖排序成批次（B：kapt→KSP + 启用 built-in Kotlin；C：Settings 迁移 Preferences DataStore；D：版本目录 libs.versions.toml + 依赖货币性刷新；E：Baseline/Startup Profiles；F：compileSdk 37 → M3 Expressive 与 minSdk 决策），逐批写明范围、验收门禁与回退策略，落为文档供后续阶段执行。

## Implementation Notes

- **防回归**：`WebDavSyncScreen.kt` 达 51KB，删除 UI 区块后须编译验证并以 grep 确认 `certPin|pinnedHosts|CertificatePinner|CERT_PINS` 全仓库零残留；`KeePasskeyApp` 的回调参数同步收敛避免死引用。
- **测试口径**：`SyncHttpClientFactoryTest` 删除 pin 用例、保留并强化 TLS-only 与超时断言；`SyncCredentialsStoreTest` 删除 certPins 往返断言、补遗留键清除用例；两个 Provider 测试补 `http://` 拒绝用例。
- **爆炸半径**：`pinnedHosts` 为仓库内部契约，无外部消费者，破坏性变更限于本仓库；`docs/SECURITY_REVIEW_2026-09.md` 为审计存档不修改。
- **工程纪律**：注释与文档使用简体中文；遵守模块单向依赖与 `.codebuddy/rules/engineering-rules.md`；完成门禁为 `.\gradlew.bat test` 全绿 + `assembleDebug` 通过；阶段成果按 AGENTS.md 纪律提交 Git。

## Architecture Design

双层 HTTPS 防线 + 单一信任源（均为既有架构内的策略收紧，不引入新模式）：

- 平台层：Network Security Config 全局禁明文、仅系统 CA，覆盖 App 内一切 HTTP 客户端；
- 传输层：`SyncHttpClientFactory` 恒定 RESTRICTED_TLS/MODERN_TLS（排除 CLEARTEXT）；
- 信任链：系统默认 CA 链为唯一信任源，零 pinning、零自定义 TrustManager；
- 输入层：端点 https-only fail-fast（保存时 + Provider 构建时双闸）；
- 数据层：遗留 certPins 键一次性物理清除。修改属直线型策略收紧，无需架构图。

## Directory Structure Summary

本阶段共修改 16 个文件、新增 2 个文件：

```
KeePasskey/
├── app/src/main/AndroidManifest.xml                                              # [MODIFY] application 挂载 android:networkSecurityConfig
├── app/src/main/res/xml/network_security_config.xml                              # [NEW] 全局强制 HTTPS：base-config cleartextTrafficPermitted=false + 仅系统 CA 信任锚，零豁免
├── app/src/main/res/values/strings.xml                                           # [MODIFY] 删除 4 条 sync_webdav_cert_pins_* 字符串；修正 sync_s3_path_style_sub「自建」文案
├── app/src/main/res/values-en/strings.xml                                        # [MODIFY] 同步英文删除与文案修正
├── sync/src/main/java/com/keepasskey/sync/network/SyncNetworkOptions.kt          # [MODIFY] 删除 pinnedHosts 字段，KDoc 收敛为超时契约
├── sync/src/main/java/com/keepasskey/sync/network/SyncHttpClientFactory.kt       # [MODIFY] 删除 CertificatePinner import 与组装分支，回归 TLS-only + 显式超时职责
├── sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt           # [MODIFY] 端点 https-only 校验（显式 http:// 抛类型化错误）
├── sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt                   # [MODIFY] 端点 https-only 校验（http:// 拒绝、无 scheme 补 https）
├── app/src/main/java/com/keepasskey/app/sync/SyncCredentialsStore.kt             # [MODIFY] 删除 certPins 字段/saveWebDavCertPins/KEY_WEBDAV_CERT_PINS，加载时物理清除遗留键
├── app/src/main/java/com/keepasskey/app/sync/SyncCoordinator.kt                  # [MODIFY] 删除 buildNetworkOptions 解析器与 certPins 传参（networkOptions 仅保留超时语义）
├── app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt  # [MODIFY] 删除 webdavCertPins 状态与 setWebDavCertPins；保存路径补 https-only 校验
├── app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt    # [MODIFY] 删除 webdavCertPins 字段
├── app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/WebDavSyncScreen.kt  # [MODIFY] 删除证书锁定 UI 输入区（label/hint/保存按钮）
├── app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt                      # [MODIFY] 删除证书锁定回调传递
├── sync/src/test/java/com/keepasskey/sync/network/SyncHttpClientFactoryTest.kt   # [MODIFY] 删除 pin 用例，强化 TLS-only/超时断言
├── sync/src/test/java/com/keepasskey/sync/WebDavSyncProviderTest.kt              # [MODIFY] 删除 pin 断言，新增 http:// 拒绝用例
├── app/src/test/java/com/keepasskey/app/sync/SyncCredentialsStoreTest.kt         # [MODIFY] 删除 certPins 往返断言，新增遗留键清除用例
├── docs/HEALTH_CHECK_ROADMAP.md                                                  # [NEW] 全量体检项分批整改路线图（批次 B-F：kapt→KSP、DataStore、版本目录、Baseline Profiles、M3 Expressive/minSdk）
├── DELIVERY_PLAN.md                                                              # [MODIFY] 作废阶段 5「自签名信任 + 局域网明文豁免」旧设计，改写为系统 CA 链 + 强制 HTTPS
└── AGENTS.md                                                                     # [MODIFY] 刷新 Wave 14 阶段状态与「仅公网商业云」新前提
```

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 在证书锁定移除与 UI 删除完成后，全仓库扫描 `certPin|pinnedHosts|CertificatePinner|CERT_PINS` 残留引用，核查 11 处已知触点清零、`WebDavSyncScreen.kt` 大文件删除后无死引用
- Expected outcome: 残留引用清单为零，为编译与测试全绿提供前置保证

### MCP

- **google-developer-knowledge**
- Purpose: 核实 Network Security Config 官方语法与推荐写法（base-config/trust-anchors/pin-set 警示），并逐批核实路线图条目（kapt→KSP、DataStore 迁移、版本目录、Baseline Profiles、M3 Expressive）的官方最新建议
- Expected outcome: `network_security_config.xml` 符合官方 schema 与推荐语义；路线图各批次范围与官方最佳实践一致