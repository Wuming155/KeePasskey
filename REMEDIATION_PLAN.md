# REMEDIATION_PLAN.md — 参考项目差距修复计划（多子代理分波执行）

> 依据：2026-09 对本项目与 4 个参考项目（KeePassDX / keepass2android / KeePass 2.61.1 官方 / Monica）的全量代码审计。
> 执行方式：主会话统筹，子代理分 4 个波次并行/串贯执行；每波次结束由主会话统一验证构建并按语义化消息提交 Git。
> 铁律（对所有执行者生效）：严禁读取/扫描 `参考项目/` 源码树（仅允许按架构分析文档精确指引的单点求证）；严禁复制参考代码；模块依赖单向 `app→database→crypto→core`、`app→sync→core`；敏感数据 CharArray/ByteArray 显式清零；文档与注释使用简体中文。

## 一、问题总清单

### P0（功能性缺失 / 官方互操作破坏）

| 编号 | 问题 | 修复归属 |
|---|---|---|
| P0-1 | **cipherKey 派生与官方不符**：现为 `SHA-256(masterSeed‖transformed)`，官方为 `SHA-512(masterSeed‖transformed)` 前 32 字节（KeePass-2.61.1 分析文档 §8.1 精确字节布局）→ 与 KeePass/KeePassXC/KeePassDX 完全互不兼容 | Wave1-A |
| P0-2 | **XML Times 编码错误**：现为「秒+纪元偏移」Base64，官方为 Base64(int64 .NET Ticks，1 tick=100ns，纪元 0001-01-01) → 时间错位/解析官方文件失败回退 now() | Wave1-A |
| P0-3 | CredentialProviderService 只打日志恒返回空响应；无 CBOR/attestation/assertion 组装；无 Launcher Activity 链路 | Wave2-D |
| P0-4 | AutofillService 恒返回空 FillResponse；onSaveRequest 空实现 | Wave2-D |
| P0-5 | triggerSync 为模拟延时，从未调用 WebDAV/S3 Provider；无缓存同步状态机；ConflictResolutionScreen 假数据 | Wave2-C + Wave3-E |
| P0-6 | mapUiEntryToKdbx 每次保存丢失 history/times/tags/图标/颜色/自定义字段（含 passkey 字段） | Wave3-E |

### P1（数据完整性）

| 编号 | 问题 | 修复归属 |
|---|---|---|
| P1-7 | XML 不解析/写回 `<Binary>`(Ref 引用)、`<AutoType>`、Meta（回收站配置/CustomIcons/DeletedObjects/MemoryProtection/CustomData）；save 恒用空 InnerHeader → 打开第三方库再保存即丢附件 | Wave1-A |
| P1-8 | InnerHeader 二进制池不落库不出库；无附件去重（官方 ProtectedBinarySet 语义） | Wave1-A |
| P1-9 | KdbxMerger 无 DeletedObjects 墓碑 → 单边删除被对端复活 | Wave2-C |
| P1-10 | 回收站是内存 StateFlow，进程重启即失效；应为 KDBX 标准库内回收站组 | Wave3-E（引擎支持 Wave1-A） |
| P1-11 | AuthenticatorViewModel 用 generateMockCode 假验证码；OtpEngine 无调用方；KeyUri 解析缺失（若确缺由 Wave3-E 补） | Wave3-E |
| P1-12 | PasskeyCryptoEngine 仅 ES256；无 COSE Key、Ed25519、RS256；无最小 CBOR 编码器 | Wave1-B |
| P1-13 | HealthCheckEngine 无调用方（健康检查页假数据） | Wave3-E |

### P2（安全 / 健壮性 / 工程）

| 编号 | 问题 | 修复归属 |
|---|---|---|
| P2-14 | 域名匹配双向 contains → 相似域名凭据泄露（evilgithub.com 命中 github.com）；必须改严格后缀匹配 | Wave2-D |
| P2-15 | 凭据错误无类型化异常；parseUuid 静默生成新 UUID 洗掉条目身份 | Wave1-A |
| P2-16 | WebDAV 用正则解析 PROPFIND XML；无事务写（PUT tmp+MOVE）；S3 首传无 If-None-Match 原子创建、对象键未编码 | Wave2-C |
| P2-17 | 无 KDF 基准测试与设备内存门槛 | Wave1-B |
| P2-18 | UnlockViewModel 主密码以 String 穿越（persistBiometricCredentialIfEnabled(password: String)） | Wave3-E |
| P2-19 | 同步凭据（WebDAV/S3 密码/密钥）未持久化、未加密存储 | Wave3-E |
| P2-20 | 文档失真（"全量验收/114 测试"与实际 77 个测试及上述缺口不符） | Wave4 |
| P2-21 | 附件管理 AttachmentManager 无调用方；条目详情未接入附件展示/导出 | Wave3-E（尽力） |
| P2-22 | AndroidManifest `allowBackup="true"`：密码管理器敏感 SharedPreferences（生物凭据密文、同步凭据）可被 adb/cloud 备份提取，应置 false 并配置 dataExtractionRules | Wave3-E（app 层，Manifest 归 Wave2-D 改动后由 E 收口） |

## 二、波次与代理分工

| 波次 | 代理 | 范围 | 依赖 |
|---|---|---|---|
| Wave 1（并行） | A：kdbx-engine | core(model/result/security) + database 全部 | 无 |
| Wave 1（并行） | B：crypto-passkey | crypto 全部 | 无 |
| Wave 2（并行） | C：sync-engine | sync 全部 | A（DeletedObject / KdbxDatabase 新字段） |
| Wave 2（并行） | D：app-services | app 的 passkey/autofill/新增 Activity/Manifest/res + repository 附加方法 | B（CBOR/COSE/签名 API） |
| Wave 3 | E：app-wiring | app 的 data/ui（repository 重构、回收站、SyncCoordinator、TOTP、健康检查、解锁边界） | A、C、D |
| Wave 4 | 主会话 + F：docs | 全量构建/测试、文档、Git 提交 | 全部 |

## 三、文件所有权（并行冲突防护）

| 代理 | 允许修改 | 明确禁止 |
|---|---|---|
| A | `core/src/main/java/com/keepasskey/core/{model,result,security}/**`、`database/src/**`（main+test） | crypto/、sync/、app/、git |
| B | `crypto/src/**`（main+test） | core/、database/、sync/、app/、git |
| C | `sync/src/**`（main+test） | 其他一切（只读消费 A 的 core 新模型；若 A 未交付 DeletedObject，则在 sync 模块内自定义等价模型并在报告注明） |
| D | `app/src/main/java/com/keepasskey/app/{passkey,autofill}/**`、`app/data/repository/*`（仅增量方法）、`AndroidManifest.xml`、`app/src/main/res/**`、DI 模块（增量） | app/ui/**（除编译必需最小修补）、crypto/、git |
| E | `app/**`（不得推翻 D 的服务实现，仅可做编译性最小修补） | crypto/、sync/、core/、database/（DatabaseSession 小 API 由 A 提供；若 A 未提供可自行增量添加并在报告注明） |
| 主会话 | 统一验证、文档更新、git 提交 | — |

## 四、跨代理接口契约（先行约定，实际以交付代码为准）

> **架构硬约束（Wave 2-C 必须遵守）**：`sync` 模块 build.gradle 仅依赖 `:core` + okhttp + coroutines（测试含 MockWebServer），**禁止依赖 `:database`**。因此 `SyncEngine` 必须是纯字节级同步状态机（本地字节 + 三哈希 + SyncProvider，不感知 kdbx 语义）；kdbx 解析与合并编排放 `app.sync.SyncCoordinator`（Wave 3-E，app 可见全部模块）。`KdbxMerger` v2 仅消费 core 模型（KdbxEntry / Wave1-A 交付的 DeletedObject）。

> **依赖缺口（Wave 2-D 必须补齐）**：`CredentialProviderService`/`BeginGetCredentialResponse` 是平台 API，但候选条目构建器 `PasswordCredentialEntry`/`PublicKeyCredentialEntry`/`CreateEntry`/`Action` 位于 Jetpack `androidx.credentials:credentials`（KeePassDX 用 1.2.2；建议 1.5.0+ 以获取 API 34+ provider 支持）。当前 `app/build.gradle.kts` 缺此依赖，D 需自行添加（api 元数据 XML 已就位：settingsActivity=MainActivity）。

1. **A 产出**：`KdbxDatabase` 新增带默认值字段（binaries / recycleBinUuid / recycleBinEnabled / customIcons / deletedObjects / memoryProtection / customData / historyMaxItems / historyMaxSize / generator）；`core.model.DeletedObject(id: KdbxUuid, deletionTime: Instant)`；`DatabaseSession.useCredentials { (pwd, keyFile) -> ... }`（返回克隆，调用方负责清零）；database 类型化异常（KdbxInvalidCredentialsException / KdbxCorruptFileException / KdbxUnsupportedVersionException，均继承 IOException）。
2. **B 产出**：`crypto.cbor.CborEncoder`（最小确定性 CBOR）；`crypto.cose.CoseKey`（ec2P256 / ed25519 / rsa2048）；`PasskeyCryptoEngine` 新增 generateEd25519KeyPair / generateRs256KeyPair / signAssertion(algorithmId, privateKeyBytes, data) / buildAuthenticatorData（含 attestedCredentialData 段重载）/ coseKeyFor(...)；`crypto.kdf.KdfBenchmark`。
3. **C 产出**：`sync.engine.SyncEngine`（openRemote / commitLocal / resolveConflict / markOffline + sealed 结果与事件）；`KdbxMerger` v2（墓碑感知：输入含双方 deletedObjects；删除-vs-保留、删除后重建按时间裁决）。
4. **D 产出**：repository 附加方法（getKdbxEntries / findEntriesForRpId / findPasskeyByCredentialId / saveNewPasskeyEntry / patchPasskeySignCount / saveAutofillCredential）；4 个新 Activity（Passkey 注册/断言、密码填充/保存）；Manifest 注册与中英双语字符串；严格域名后缀匹配。
5. **E 消费**以上全部并接线 UI；新增 `app.sync.SyncCoordinator` 组合 SyncEngine + KdbxFile + KdbxMerger + DatabaseSession；同步凭据经 KeystoreManager 加密持久化。

## 五、验收门禁

- 每波：所属模块 `compileDebugKotlin` + `testDebugUnitTest` 全绿 → 主会话 `git add -A && git commit`（语义化消息，注明波次与问题编号）。
- Wave 4：`.\gradlew.bat assembleDebug`、`testDebugUnitTest` 全绿；R8 混淆编译不回归；`AGENTS.md` / `.codebuddy/memory/project-status.md` 如实更新（含真实测试计数与遗留限界）；最终提交。
- **Wave 4 主会话收口清单（轮 5 预研补充）**：
  1. proguard-rules.pro 第 8 节扩展：`com.keepasskey.app.passkey.**` / `com.keepasskey.app.autofill.**` 包级 keep（覆盖 Wave 2-D 新增 4 个 Launcher Activity 与 DomainMatcher/AutofillFieldScanner）；追加 `androidx.credentials.**` 安全 keep（防 AAR consumer 规则缺失时 provider 回调反射失效）；
  2. `assembleRelease`（或 `minifyReleaseWithR8`）验证混淆编译；
  3. 文档更新：AGENTS.md 当前阶段状态改为「差距修复交付」+ 真实测试计数（77 基线 + Wave1 新增 53 + Wave2/3 增量）；project-status.md 决策日志补记 4 个 Wave 的 git 哈希与遗留限界（流式解析、S3 TOCTOU、生物解锁 String 边界妥协等）；
  4. 清理 `.codebuddy/wave2*.md`/`wave3e-prompt.md` 执行存档（保留 REMEDIATION_PLAN.md 执行日志）。

## 六、风险与既定决策

- **cipherKey 修复导致旧库（错误派生产物）无法打开**：读取侧先按官方派生校验头部 HMAC，失败后回退旧派生重试一次（成功即提示并正常打开）；保存恒用官方派生 → 旧文件保存即自动迁移。
- DOM 解析与整库内存解密**暂不**改造为流式（记录为后续优化，不在本次范围）。
- Credential Provider 锁库 UX v1：锁定时返回"解锁 KeePasskey"动作条目，不做链式解锁。
- S3 条件写：首传用 `If-None-Match: *`；更新保留 HEAD+ETag 预检（TOCTOU 限界记录于文档）。
- 并行构建 Gradle 锁冲突：各代理等待 2 分钟重试 ≤3 次；波内代理禁止运行 assembleDebug。
- WebDAV 自签名证书：不在 sync 模块内置"信任所有"逻辑，允许注入自定义 OkHttpClient（文档说明），不静默削弱 TLS。

## 七、执行日志（主会话维护）

- **基线验证（Wave 1 启动前）**：`testDebugUnitTest :app:compileDebugKotlin` 全绿（exit 0，全部 up-to-date），Git HEAD `b0cdc89`，工作区干净（仅新增本计划文档）。Wave 1 代理 A（kdbx-engine）、B（crypto-passkey）已并行启动。
- **Wave 2 预备（Wave 1 运行期间，主会话只读勘察）**：已确认 VaultRepository 接口 22 个既有方法（新增方法将以增量形式追加）、UiVaultEntry 已含 isPasskey/passkeyRpId/totpCode 字段、Manifest 服务注册与 credential_provider_service.xml（settingsActivity=MainActivity）、MainActivity 为 FragmentActivity+Compose 模式、SettingsRepository 含 syncOnColdStart、strings.xml 中英双语 700+ 行分区结构。新发现问题 P2-22（allowBackup=true）已入清单，归 Wave3-E。
- **Wave 2 架构约束确认（轮 2）**：sync 模块仅依赖 :core（禁止依赖 database，SyncEngine 须为纯字节级状态机）；app 模块缺 `androidx.credentials:credentials` 依赖（PasswordCredentialEntry/PublicKeyCredentialEntry 构建器所在），Wave 2-D 需自行添加。两条硬约束均已写入第四节契约。
- **Wave 1 中期进度（轮 2 文件系统观察）**：A 已产出 core 新模型（DeletedObject/CustomIcon/MemoryProtectionConfig）并更新 KdbxAttachment/KdbxConstants/KdbxEntry/KdbxGroup/KdbxDatabase/DatabaseSession 与 3 个类型化异常；B 已产出 CBOR/COSE/KdfBenchmark 全部主代码与测试、PasskeyCryptoEngine 扩展至 20KB。两代理仍在收尾（XML 解析器/序列化器改造与签名测试）。
- **Wave 2 提示词预备完成（轮 3）**：`.codebuddy/wave2c-prompt.md`（SyncEngine 三哈希状态机/墓碑合并 v2/WebDAV·S3 加固，含 sync 禁止依赖 database 硬约束与 Kp2a 9.2 节九条建议映射）与 `.codebuddy/wave2d-prompt.md`（Credential Provider 端到端/Autofill 真实化/DomainMatcher/4 个 Launcher Activity/androidx.credentials 依赖补齐）已存档，Wave 1 验收通过后即可发出。A 正按单一职责拆分 XML 层（8 个新文件：TimeHelper/DomUtil/MetaParser·Serializer/EntryParser·Serializer/GroupParser·Serializer），已完成 KdbxFile/KdbxHeader/HmacBlockStream/BinaryDeduplicator 改造，进行 KdbxAttachment 收尾。
- **✅ Wave 1-B 验收通过（轮 4）**：代理 B 交付 CBOR（RFC 8949 确定性编码）/COSE（EC2/OKP/RSA 三结构）/PasskeyCryptoEngine 三算法扩展（keygen+signAssertion 统一 API+AT 段重载+coseKeyFor，敏感字节 finally 清零）/KdfBenchmark（AES 外推+Argon2 自适应+内存门槛，可注入采样）。主会话独立复跑 `:crypto:compileDebugKotlin :crypto:testDebugUnitTest` BUILD SUCCESSFUL（34 测试全绿）；代码抽查：CoseKey 标签/插入序/前导零剥离正确，CborEncoder 负整数编码含 Long.MIN_VALUE 溢出安全处理。契约 API 已按第四节落实（CborEncoder/CoseKey/PasskeyCryptoEngine/KdfBenchmark），供 Wave 2-D 消费。
- **✅ Wave 1-A 验收通过（轮 4）**：代理 A 交付官方 cipherKey 派生+旧派生回退迁移、.NET Ticks 时间编码（含已知向量 638396640000000000L 测试）、XML 全字段往返（Meta/AutoType/Binary-Ref/CustomData，8 个单一职责子组件）、二进制池+KdbxBinaryDeduplicator 去重、类型化异常+严格解析、useCredentials API、AES-KDF 6M 轮对齐。主会话独立复跑 `:core/:database` 编译测试 BUILD SUCCESSFUL（27 测试全绿），抽查 KdbxFile 回退逻辑正确（官方派生 HMAC 失败→旧派生重试→解密失败再回退，双重保险）。
- **✅ Wave 1 收口（轮 4）**：全模块回归 `testDebugUnitTest` 114 任务全绿（含 app 兼容性）；Git 提交 `ed601da`（feat(wave1)，工作区干净）。Wave 2-C（同步引擎）与 Wave 2-D（凭据服务端到端）已携带 Wave 1 实际 API 契约并行启动。
- **Wave 3 预备完成（轮 5）**：`.codebuddy/wave3e-prompt.md` 已存档（P0-6 编辑保留/E2 库内回收站含墓碑/TOTP KeyUri 解析+OtpEngine 接线/健康检查真实化/SyncCoordinator+E5 同步接线/同步凭据加密持久化/密码 CharArray 边界/allowBackup 加固/附件尽力项；含 Wave 2 API 补注占位区，Wave 2 验收后填入实际签名再发出）。Wave 4 收口清单补入第五节（proguard 包级 keep 扩展/混淆构建/文档如实更新/存档清理）。
- **Wave 2 中期进度（轮 5 观察）**：C 已落盘 SyncCache/SyncEngine/KdbxMerger v2 重写/WebDavSyncProvider 加固与 SyncModels 扩展；D 已落盘 DomainMatcher/VaultRepository 三处增量方法/AutofillFieldScanner/autofill_dataset_item.xml。两代理均按单一职责推进，无越界迹象。
- **Wave 2 预审通过（轮 6，主会话代码抽查）**：① `SyncEngine.kt`（312 行）决策树完整对齐 Kp2a（未缓存下载/无修改刷新/本地赢自动上传/412 后二次下载冲突/404 自愈/网络降级），六事件 sealed 类完备，离线开关、ByteArray equals 规范覆盖、Dispatchers.IO 全覆盖——验收基础良好；② `DomainMatcher.kt` 严格点边界后缀匹配（evilgithub.com 不命中 github.com），extractDomain 处理 scheme/凭据/路径/端口/IPv6，isPackageMatch 点边界包名匹配——P2-14 修复质量确认；③ D 已补 `androidx.credentials:1.5.0` 依赖（解析成功），4 个 Launcher Activity 编写中。
- **Wave 2 预审补充（轮 7）**：④ `PasskeyAssertionActivity`（163 行）组装链正确：UP|UV|BE|BS flags、authData‖SHA-256(clientDataJSON) 签名输入、hex/Base64 双格式私钥解析且 finally 清零、PendingIntentHandler.setGetCredentialResponse 回传、patchPasskeySignCount 落盘；⑤ `KdbxMerger.kt` v2（578 行）三方合并算法对齐官方 MergeIn：删除vs修改（修改胜）、删除后重建（modTime>墓碑时间则新版胜）、分组环路检测自愈挂根、字段级合并+冲突清单、墓碑按 UUID 去重取最晚删除时间；`KdbxDatabaseLite` 聚合类型正确解耦 database 依赖。
- **Wave 2 收尾观察（轮 7 末）**：两代理进入最终阶段——D 重写 KeePasskeyCredentialProviderService 与 PasskeyCreateActivity（9:13）；C 完善 SyncEngineTest/WebDavSyncProviderTest。等待完成报告后进入验收流程。
- **✅ Wave 2-C 验收通过（轮 8）**：代理 C 交付 SyncCache（三哈希磁盘布局+原子写盘）/SyncEngine（决策树+六事件+离线开关）/KdbxMerger v2（墓碑三方合并）/WebDAV DOM 解析+uploadAtomic 事务写+URL 编码/S3 If-None-Match 首传+SigV4 编码一致性。主会话独立复跑 `:sync:compileDebugKotlin :sync:testDebugUnitTest` BUILD SUCCESSFUL（30 测试全绿：SyncEngineTest 11 + KdbxMergerV2Test 7 + WebDav 6 + S3 4 + 旧接口回归 2）；预审已确认决策树/合并算法正确性。旧 API detectConflictsAndMergeAuto/resolveConflict 保持兼容。
