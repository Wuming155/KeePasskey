# WAVE4 文档更新草稿（主会话执行，Wave 3 验收后使用）

> 执行说明：本文档为 Wave 4 收口的文档改写底稿。使用时需以最终测试统计替换 `{{TEST_COUNT}}` 等占位符，并以 Wave 3 实际交付情况核对「遗留限界」清单后合入。

## 一、AGENTS.md 变更

### 1. 构建命令节（第 49 行替换）
```
- `.\gradlew.bat test` — 单元测试（全模块 src/test 已就绪，`testDebugUnitTest` 可单模块执行）
```

### 2. 「当前阶段状态」整节替换为：

```
**当前阶段状态**：**🔧 参考项目差距修复专项（REMEDIATION_PLAN.md）已完成 Wave 1-4 全量交付。**
原「7 阶段交付完成」的表述经 2026-09-05 全量代码审计修正：审计发现系统服务空壳、模拟数据、
官方互操作缺陷等 22 项问题（P0×6 / P1×7 / P2×9），已按 4 个 Wave 修复并逐波提交 Git：

- **Wave 1（KDBX 官方兼容 + crypto 底座）**：cipherKey 派生修正为官方 SHA-512 截断标准
  （读取侧旧派生自动回退迁移）；XML Times 修正为 .NET Ticks 编码；XML 全字段往返
  （Meta/AutoType/Binary-Ref/CustomData）；InnerHeader 二进制池 + 附件去重；类型化异常；
  CBOR/COSE 编码器；Passkey 三算法签名（ES256/Ed25519/RS256）；KdfBenchmark 设备自适应。
- **Wave 2（同步引擎 + 凭据服务端到端）**：SyncEngine 三哈希状态机（Kp2a 决策树 +
  ICacheSupervisor 六事件）；KdbxMerger v2 墓碑感知三方合并；WebDAV DOM 解析 + 事务写 +
  URL 编码；S3 If-None-Match 原子首传；CredentialProviderService/AutofillService 真实化；
  4 个 Launcher Activity；DomainMatcher 严格域名匹配（根治跨域凭据泄露）。
- **Wave 3（数据层完整性）**：编辑保存完整保留（history/times/tags/图标/自定义字段）；
  KDBX 标准库内回收站（墓碑落库）；TOTP 真实化（KeyUri 解析 + RFC 6238）；
  健康检查真实化；SyncCoordinator 同步全链路接线；同步凭据 Keystore 加密持久化。
- **Wave 4（收口）**：proguard 包级 keep 扩展；R8 混淆构建验证；文档如实更新。

全工程单元测试：{{TEST_COUNT}} 个全部绿灯（{{MODULE_BREAKDOWN}}）。
```

## 二、.codebuddy/memory/project-status.md 变更

### 1. 「项目现状」节顶部插入：

```
## 项目现状

- **2026-09-05 参考项目差距修复专项竣工（REMEDIATION_PLAN.md，4 Wave）**：
  - Wave 1（git ed601da）：KDBX v4 引擎与 KeePass 官方 2.61.1 逐字节互操作（cipherKey
    SHA-512 截断派生 + 旧库回退迁移、.NET Ticks 时间编码、XML 全字段往返、二进制池去重）；
    crypto 模块补齐 CBOR/COSE/三算法 Passkey 签名/KDF 基准。
  - Wave 2（git 4927189）：sync 模块三哈希同步状态机 + 墓碑三方合并 + WebDAV/S3 协议加固；
    app 模块 CredentialProviderService/AutofillService 从空壳真实化为端到端可用（含 4 个
    Launcher Activity 与严格域名匹配）。
  - Wave 3（git {{WAVE3_HASH}}）：数据层编辑保留/库内回收站/TOTP/健康检查/同步接线/
    凭据加密持久化全部去假数据化。
  - Wave 4（git {{WAVE4_HASH}}）：proguard 扩展与混淆验证、文档如实化。
```

### 2. 决策日志顶部插入（2026-09-05 条目）：

```
- **2026-09-05**：发现并修复「7 阶段全量验收」文档失真——代码审计对照 4 个参考项目架构
  分析发现 22 项问题（含 CredentialProviderService 空响应、triggerSync 模拟延时、TOTP 假码、
  cipherKey 非官方派生等 P0 缺陷）。以 REMEDIATION_PLAN.md 4 波次多子代理模式完成修复，
  逐波验收（独立复跑测试）+ 语义化提交。已知限界如实记录：DOM 非流式解析（后续优化）、
  S3 覆写存在 HEAD+PUT TOCTOU 微窗口（KDoc 注明）、Credential Provider 锁库 UX 为 v1
  （Action 引导解锁，非链式解锁）、UnlockUiState 密码 String 边界妥协（ViewModel 内尽早
  toCharArray，KDoc 注明）。
```

### 3. 「待办与已知问题」节替换为：

```
## 待办与已知问题

- KDBX DOM 解析改造流式（XmlPullParser + 进度 Flow）——大库内存优化，远期。
- S3 覆写路径的 TOCTOU 微窗口（HEAD+ETag 预检后 PUT）——S3 原生条件写待版本桶支持后引入。
- Credential Provider 锁库 UX v2：链式解锁（系统弹窗内完成生物识别）。
- kapt 迁移 KSP（需与 Kotlin 升级联动）。
- version catalog 收敛依赖版本。
- 附件管理 UI 完整化（{{E9_STATUS：若 Wave 3 已实现则删除本条}}）。
```

## 三、验收证据核对清单（Wave 4 执行时逐项确认）
- [ ] `testDebugUnitTest` 全绿，统计最终测试数（各模块 test-results XML 汇总）
- [ ] `assembleDebug` 通过
- [ ] `minifyReleaseWithR8`（或 assembleRelease）通过
- [ ] proguard-rules.pro 已含 `app.passkey.**`/`app.autofill.**`/`androidx.credentials.**` keep
- [ ] AGENTS.md / project-status.md 按上述草稿合入（占位符已替换）
- [ ] `.codebuddy/wave2c-prompt.md`/`wave2d-prompt.md`/`wave3e-prompt.md` 已删除
  （REMEDIATION_PLAN.md 执行日志保留）
- [ ] 最终 git commit 完成且工作区干净
