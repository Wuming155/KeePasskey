# AGENTS.md
This file provides guidance to AI coding agents when working with code in this repository.

## 项目概述

KeePasskey 是一款使用原生 Kotlin 开发的现代化 Android 密码管理器。基于标准 .kdbx（v4）格式，内置 WebDAV 与 S3 兼容协议同步；以 Android 16+（API 36+）为核心基线深度集成系统 Credential Manager，支持通行密钥（Passkey / WebAuthn）的端到端生成、存储与自动验证（低版本平滑退化至传统密码填充）。

技术栈：Jetpack Compose + Material 3、Hilt、Coroutines + Flow。**文档与代码注释使用简体中文。**

## 硬约束（任何操作都适用）

1. **模块依赖严格单向**（详见架构指南），禁止反向或同层互依：
   ```
   app ──> database ──> crypto ──> core
    └───> sync ────────────────> core
   ```
2. **敏感数据铁律**：主密码、密钥用 `CharArray`/`ByteArray` 并显式清零，绝不落地为 `String`，日志严禁敏感明文。
3. **参考项目只读与文档优先铁律（禁止盲目翻看源码）**：
   - 严禁对 `参考项目/` 源码目录执行无目标的全局 `grep`、`glob` 或大面积扫源码；
   - 5 个参考项目均已完成详尽的架构分析，集中存放在 **`.codebuddy/skills/references/`**；
   - **参考项目优先级层级（严格遵照执行）**：
     1. 🥇 **第 1 优先级（核心参考）**：**KeePassDX** — 与本项目技术栈最贴近（Android 原生 Kotlin），优先参考其 `database` / `crypto` 领域模型、`DatabaseSession` 生命周期与 WebAuthn/Passkey；
     2. 🥈 **第 2 优先级（次核心参考）**：**keepass2android** — 重点参考其云同步架构（WebDAV/S3 适配）、文件存储抽象（`IFileStorage`）、本地缓存机制、三方哈希冲突检测与 Quick Unlock 快速解锁；
     3. ⚖️ **标准实现参考（格式与协议裁决者）**：**KeePass-2.61.1 官方 C#** — 作为 `.kdbx` 格式（v3/v4）的官方事实标准，仅在遇到文件格式细节、二进制 Header 字段、加密管线或 XML 树结构歧义时作为终极裁决标准；
     4. ⚖️ **算法级参考（合并引擎与通行密钥 schema）**：**KeePassXC (C++/Qt)** — 其 `Merger` 条目级合并与墓碑复活规则是 `KdbxMerger` 的直接算法参考，`KdbxReader/KdbxWriter` 管线用于 `database` 模块交叉验证，浏览器集成的 `KPEX_PASSKEY_*` 属性 schema 对照 `PasskeyData` 设计；
     5. 🥉 **辅助参考（不做重点）**：**Monica** — 仅作为现代 Compose UI/UX 风格与本地优先思路的补充对照，不作为核心实现重点；
   - **凡涉及实现思路借鉴，必须强制先阅读对应架构分析文档，严禁直接去翻原始代码**；
   - 仅当架构分析文档明确指出某个特定算法或数据格式边界、且文档说明不足以完成独立编写时，才允许按图索骥精确定位阅读该单个源文件；
   - 严禁修改 `参考项目/` 目录下的任何文件，严禁复制其代码入库（许可证约束）。
4. **工程规则**（单一职责、禁止魔法数字、依赖倒置、错误处理等）详见下方规则文件，写代码前必须遵守。
5. **阶段交付与版本归档纪律**：严格遵照 `DELIVERY_PLAN.md` 推进；**每完成一个阶段，必须同步更新 `AGENTS.md`（刷新当前状态、已完成内容与下一阶段目标），并立即将阶段成果全部暂存并提交 Git 到远程仓库**，确保每个阶段里程碑均具备独立、清晰、可回退的远程 Git 提交历史。

## 详细文档索引（按需阅读）

| 文件 | 内容 | 何时阅读 |
|------|------|----------|
| `.codebuddy/rules/engineering-rules.md` | 工程规则：单一职责与巨型类阈值、魔法数字、依赖倒置与 Hilt 注入、Result 错误处理、敏感数据、Compose 规范、**原子写盘、协程调度约束、Credential Provider 隔离、防御性安全（FLAG_SECURE / 剪贴板 / 混淆）** | **写 / 改任何代码前** |
| `DELIVERY_PLAN.md` | **完整项目交付规划**：7 大阶段任务、阶段交付物清单、验收门禁（DoD）与全渠道发布标准 | **规划任务、核对阶段与交付时** |
| `.codebuddy/skills/architecture.md` | 5 模块职责与依赖规则、7 条关键架构决策（加密分离、kdbx 兼容、DatabaseSession、同步模型、passkey 路线、UI 优先），另见 `ARCHITECTURE.md` | 跨模块改动、新增功能落位前 |
| `.codebuddy/skills/reference-projects.md` | 参考项目地图：各功能应参照哪个项目的哪些文件 | 实现 database / crypto / sync / passkey 功能时 |
| `.codebuddy/memory/project-status.md` | 版本配套表、项目现状、决策日志、待办 | 升级依赖、了解进度与历史决策时 |

## 构建命令

使用 Gradle Wrapper（**Gradle 9.3.1**，AGP 9.1.0 / Kotlin 2.4.10 / Hilt 2.60.1，kapt）。Windows 下执行 `.\gradlew.bat <task>`：

- `.\gradlew.bat assembleDebug` — 编译全部模块
- `.\gradlew.bat :app:compileDebugKotlin` — 仅快速检查 Kotlin 编译
- `.\gradlew.bat lint` — Android Lint
- `.\gradlew.bat test` — 单元测试（全模块 `src/test` 已就绪，`testDebugUnitTest` 可单模块执行；当前 212 个测试全绿）
- 版本升级需整体配套：AGP ↔ Gradle ↔ Kotlin ↔ Compose BOM（Compose BOM 2026.06.00+ 要求 compileSdk 37，当前用 2026.06.01 对齐 compileSdk 36）

**当前阶段状态**：**🔐 真实 KDBX 4.0 与复合密钥真机互操作（Wave 12）交付完毕——Wave 1-11 全量整改 + Wave 12（主密码 + XML KeyFile v2.0 复合密钥真机解锁、官方 2.61.1 / KeePassDX / KeePassXC 三方交叉验证算法纠偏、pykeepass 双向往返完全闭环）。**
原「7 阶段全量验收」表述经 2026-09-05 全量代码审计修正：对照参考项目发现 22 项问题（P0×6 / P1×7 / P2×9，含系统服务空壳响应、模拟延时同步、TOTP 假码、cipherKey 非官方派生等），已按 4 个 Wave 修复并逐波验收提交：

- **Wave 1（git ed601da）KDBX 官方兼容 + crypto 底座**：cipherKey 派生修正为官方 SHA-512 截断标准（读取侧旧派生自动回退、保存自动迁移）；XML Times 修正为 .NET Ticks 编码；XML 全字段往返（Meta/AutoType/Binary-Ref/CustomData）；InnerHeader 二进制池与附件去重；类型化异常体系；CBOR/COSE 确定性编码器；Passkey 三算法签名（ES256/Ed25519/RS256 + RFC 6979）；KdfBenchmark 设备自适应基准。
- **Wave 2（git 4927189）同步引擎 + 凭据服务端到端**：SyncEngine 三哈希状态机（Kp2a 决策树 + ICacheSupervisor 六事件 + 离线开关）；KdbxMerger v2 墓碑感知三方合并（删除vs修改/删除后重建/字段级合并/环路自愈）；WebDAV DOM 解析 + uploadAtomic 事务写 + URL 编码；S3 `If-None-Match:*` 原子首传 + SigV4 编码一致性；CredentialProviderService/AutofillService 从空壳真实化（PublicKey/PasswordCredentialEntry + CreateEntry + 锁库 Action + 5s 超时预算）；4 个 Launcher Activity（FLAG_SECURE + attestation/assertion 组装）；DomainMatcher 严格域名匹配根治跨域凭据泄露。
- **Wave 3（git a45bfa5）数据层完整性**：编辑保存完整保留（history/times/tags/图标/自定义字段含 Passkey + HistoryManager 快照）；KDBX 标准库内回收站（recycleBinUuid 落库 + DeletedObject 墓碑 + previousParentGroup 还原）；TOTP 真实化（KeyUri 解析 + RFC 6238 官方向量）；健康检查真实化（HealthCheckEngine 接线 + 评分公式）；SyncCoordinator 同步全链路接线（KDBX4 随机 IV 哈希漂移防抖）；同步凭据 Keystore AES-256-GCM 加密持久化；Unlock 密码 CharArray 边界加固；allowBackup=false + dataExtractionRules；修复 parseDate Base64 含 T 误判缺陷；proguard 包级 keep 扩展。
- **Wave 4（文档收口）**：文档如实化（本节）与存档清理。
- **Wave 5（已知限界清零）**：① KDBX 全链路流式化——读取侧 DOM 改 SAX 流式状态机（官方 `ReadXmlStreamed` / KeePassDX `readDocumentStreamed` 对齐），写入侧改 `KdbxXmlStreamWriter` 紧凑流式写出（官方 `WriteDocument` 对齐），`HmacBlockStream` 新增流式读写（逐块「边校验边交付」），`KdbxFile.load/save` 全管线（HMAC 块流→加密流→GZip→XML）不再物化整条密文/明文/压缩数据；旧派生（SHA-256 cipherKey）识别改为首块解密探针裁决（官方与旧派生 hmacKey64 相同，头部 HMAC 无法区分）。② S3 覆写 PUT 附带 `If-Match: "<etag>"` 条件头，服务端原子校验消除 HEAD+PUT TOCTOU（不支持条件写的兼容存储自动降级为旧行为）。③ Credential Provider 链式解锁（锁库 UX v2）——锁库 Action 指向新 `CredentialUnlockActivity`，解锁成功后经 `PendingIntentHandler.setBeginGetCredentialResponse` 直接回传凭据候选，系统随即继续呈现；候选组装抽取为共享 `CredentialResponseAssembler`。④ `SecurePasswordField` 安全输入组件 + 主密码 CharArray 全链路——`UnlockUiState` 不再持有 String 明文，显示 String 仅存活于组件内部（dispose 清零），CharArray 直达 ViewModel 并在成功/异常路径显式擦除。
- **Wave 6（安全审查整改）**：① H1 Origin/RP-ID 绑定——新增 `CallingOriginResolver`（浏览器走官方 `CallingAppInfo.getOrigin` + 包名/签名指纹特权白名单、异常 fail-closed；普通应用固定 `android:apk-key-hash:` origin，杜绝信任调用方 origin 字符串）；GET 流程 rp.id 与 origin 强绑定（浏览器 rp.id 须为 origin 可注册后缀，普通应用仅按严格包名边界匹配）；PasswordFill/PasskeyAssertion Activity 回传前二次校验；普通应用创建的 Passkey 记录 `android://<包名>` 绑定。② H2 QuickUnlock 真实化——新增 `QuickUnlockPinStore`（PBKDF2-HMAC-SHA256 120k 迭代 PIN 校验器 + 主凭据 Keystore AES-256-GCM 硬件封印），移除 `delay(300)` 空壳桩，首次使用走「登记 PIN → 完整解锁一次绑定凭据」流程。③ M1 `UiVaultEntry`/`UiEntryRevision` 移除 `passwordPlain`，新增 `getEntryPassword`/`getEntryRevisionPassword` 按需单条解密，详情/列表/编辑/回滚/对比全链路接线，`saveEntry` 密码显式参数提交。④ M2 硬编码示例凭据清零 + 掩码固定长度。⑤ L1 移除 title/notes.contains 启发式 + `isPackageMatch` 支持 android:// scheme 剥离（修复永不匹配缺陷）。⑥ L2 WebDAV PROPFIND XXE 四项加固。⑦ L3 生产 DI 切换 `RealSettingsRepository`（SharedPreferences 持久化 + 安全默认值），安全设置跨冷启动保留。⑧ L4 WebDAV authHeader 构造期立即计算（修复同步空密码认证功能失效）+ S3 AccessKey AES-256-GCM 加密落盘。

**Wave 7（安全审计整改）**：对照《KeePasskey 安全代码审计报告》完成 F1-F5 与附录两条观察全量修复——① F1 `isPackageMatch` 双向后缀包名匹配（High 水平越权）收敛为 scheme 剥离后精确相等，6 个生产调用点同步核查；② F2 受保护自定义字段（Passkey 私钥/TOTP 种子/恢复码）与 `UiVaultEntry.totpSecret` 明文彻底退出 UI 投影——新增 `getEntryProtectedField` / `calculateEntryTotp` 按需单条解密，详情页揭示/复制、编辑页加载/回写、验证器页按秒重算全链路接线，`saveEntry` 对空值受保护字段回填既有值（防回滚路径清空）；③ F3 同步变更检测改走 `ProtectedString.equals`（字节数组比较，不再物化全库密码 String）；④ F4 `PasskeyAssertionActivity` 非浏览器分支补充调用包名与 `android://<包名>` 绑定精确二次校验（预期包名经不可伪造 PendingIntent extras 传入），空 origin 一律拒绝签发并修复 RP ID 冒充 web origin 回退；⑤ F5 `isDomainMatch` 公共后缀下限约束（单标签与内置多级公共后缀最小子集拒绝作 RP ID）；⑥ 观察 1 `PasswordSaveActivity` 移除 candidateQueryData 死代码 fallback；⑦ 观察 2 `QuickUnlockPinStore` 增加 PIN 连续失败计数与指数退避熔断（5 次起熔 30s → 封顶 15min）。

**Wave 8（功能完整性检查整改）**：对照功能完整性审查发现的假桩与孤岛功能全量整改——① P1 下拉刷新假同步（`VaultListViewModel.triggerPullRefresh` 仅 delay 即谎报"同步完成"）真实接线 `SyncCoordinator.syncNow()`，按 `SyncOutcome` 映射同步状态与反馈，并移除演示用"上次同步 10:25"假时间戳；② P1 离线开关联动——`SettingsViewModel.setUseOfflineCache` 现已实时传导 `SyncCoordinator.setOfflineMode` → `SyncEngine.isOffline`（此前 UI 开关为空转）；③ P1 ICacheSupervisor 六事件接线——`SyncCoordinator` 事后抽取引擎事件 replayCache 发布为 `syncEvents`/`recentSyncEvents`，VaultList 上浮「云端保存失败已留本地」「远端已更新」提示；④ P2 `uploadAtomic` 接入生产管线——`SyncProvider` 增加默认 `uploadAtomic` 契约，WebDAV 实现修正 MOVE 预条件为 RFC 4918 `If` 头 tagged list（原 `If-Match` 挂 MOVE 对目标无约束效力），SyncEngine 全部 4 处上传路径切换为原子写；⑤ P2 `deleteGroup` 补齐回收站语义（整组移入回收站 + previousParentGroup，物理删除时记录墓碑）；⑥ P2 KdbxMerger 补 `previousParentGroup` 复活回退（复活条目原父组失链时优先回到 previousParentGroup 并同步改写 parentGroupId）；⑦ P2 冲突解决界面不再物化双方密码明文（仅掩码呈现）；⑧ P2 生物识别 fail-closed——移除 `activity == null` 时 delay 后伪发 `UnlockSuccess` 的回退桩，解锁依赖去掉 `= null` 默认值；⑨ P2 死分支假反馈清理（SettingsViewModel/ConflictResolutionViewModel 对非空注入参数的 null 分支 delay 假"完成"路径删除）；⑩ P2 调试日志真实化——新增 `DebugLogBuffer` 进程内环形缓冲并接线 SyncCoordinator/Unlock 事件，DebugSettingsScreen 渲染真实日志（刷新/清空/导出到剪贴板均真实生效）；⑪ P3 HealthCheckEngine 落实 EXPIRED 过期检测（文档承诺此前无实现）；⑫ P3 S3 旧版明文 AccessKey 读取即迁移加密并物理删除明文键；`saveWebDavConfig`/`saveS3Config` 空密码显式清除旧密文；⑬ P3 S3 path-style 寻址支持（provider + 持久化 + UI 开关，兼容自建 MinIO/代理）；⑭ P3 `cleanEtag` 结构级解析（不再逐字符误伤含 W、/ 的合法不透明 ETag）；⑮ P3 `HmacBlockStream.readAll/writeAll` 标注为仅测试/工具使用。

**Wave 9（同步与合并数据丢失专项整改）**：对照同步/合并模块专项安全审计发现的数据丢失缺陷全量整改——① P0 合并引擎单侧新建清零（回归测试发现的最深缺陷）：`KdbxMerger.mergeDatabases` 的 when 决策树缺失"单侧新建"分支（`仅本地有`/`仅远端有`、base 与墓碑中均无记录的条目与分组落入所有分支之外被静默丢弃），补齐条目与分组两个 catch-all 保留分支（置于墓碑分支之后，不遮蔽删除裁决）；② P0 冲突决策路径丢合并产物：`resolveConflicts` 原从纯 localDb 重建结果，远端新增条目/分组与非冲突字段级合并全部丢失——新增 `pendingMergedRoot`/`pendingMergedTombstones` 保存 `mergeResult` 产物，决策时以合并产物为底版应用用户选择并回写墓碑；③ P0 `DUPLICATE_BOTH` 冲突副本换新 `KdbxUuid.random()`（原同 UUID 副本在应用回分组树时与本地原条目命中同一槽位互相覆盖，且违反 KDBX UUID 唯一性）；④ P0 base 内容独立持久化（A2）：`SyncCache` 新增 `<hash>.basecache` 快照（`readBaseContent`/`writeBaseContent`），`SyncEngine` 在全部 5 处"字节确认与远端一致"的 `updateBase` 点同步落盘 base 内容，`SyncCoordinator` 三方合并 base 优先取 basecache——修复"本地缓存兼任 base 内容来源，冲突会话中断后被本地修改版污染 → 后续合并远端全胜"的静默丢失链；⑤ P0 冲突分支 session 落盘（R3）：`runSyncCycle` 的 `ConflictNeedsMerge`/`ConflictDetected` 分支先 `databaseSession.save()` 再进入合并，本地未同步修改不再仅存于缓存与内存；⑥ P1 WebDAV `uploadAtomic` 临时文件唯一化（A4）：临时名 `<path>.<UUID>.kpktmp`，修复多客户端共用固定临时名时 A 的 MOVE 搬运 B 内容的交叉污染竞态（`If` 预条件只约束 MOVE 目标不约束源临时文件）；⑦ P1 `SyncCache` 真原子写（B1）：删除 rename 前的 `delete()` 调用（消除"目标已删、rename 未执行"崩溃窗口），非 POSIX 平台回退 `Files.move(ATOMIC_MOVE → REPLACE_EXISTING)`，废弃非原子 `copyTo`；⑧ P1 冲突时刻 ETag 传递（E2）：`markResolvedAndUpload` 增加 `expectedEtag` 参数，`resolveConflicts` 传 `pendingRemoteEtag`（冲突发生时刻的远端 ETag）作 If-Match 期望值——用户决策期间远端再被修改时 412 暴露新冲突而非静默覆盖，`ConflictError` 时清空待决会话并引导重新 `syncNow` 重新合并；⑨ P1 无 ETag 服务器内容哈希裁决（E1）：`openRemote` 远端一致性双通道裁决——ETag 可用按乐观锁零下载比对，任一侧 ETag 缺失回退"下载一次与 baseversion 的 SHA-256 比对"，修复无 ETag 服务器陷入"每次同步误判远端更新/永远冲突"的退化循环；⑩ P1 `hasDatabaseContentChanged` 全字段递归比较（C1）：原仅比较 5 个常用字段，仅改 tags/自定义字段/附件/图标/分组结构的编辑被误判无变化而复用过期缓存并把旧字节上传云端——改为 deletedObjects + 分组树递归 + 条目全字段（fields/customFields/tags/attachments/图标/overrideUrl/qualityCheck/previousParentGroup/customData/autoType/颜色/history 数量）比较，全部走 `ProtectedString.equals` 字节比较不物化明文；⑪ P2 `markResolvedAndUpload` 写序调整为先上传后落缓存（R1：失败路径缓存与基线保持原状，本地未同步修改保留待重试，消除三处不一致状态）；⑫ P2 `mergeConflictedEntry` 补 history 三方并集合并（对齐官方 MergeIn，按 lastModificationTime 去重升序）；⑬ P2 `KdbxMerger` 比较口径统一 `ProtectedString.equals`（isEntryModified 改 Map 相等性、isFieldDifferent/自定义字段比较去 readString 物化）；⑭ P2 删除旧版时间戳合并死代码 `detectConflictsAndMergeAuto`/`areEntriesIdentical`/`findDifferentFields`；⑮ 回归测试：KdbxMergerTest 重写（DUPLICATE_BOTH 双 UUID/历史并集/冲突+远端新增存活/单侧新建条目与分组存活），SyncEngineTest 新增 6 例（无 ETag 三态裁决×3、base 内容快照推进、markResolved 失败不污染缓存、冲突时刻 ETag 412 防覆盖），WebDavSyncProviderTest 断言唯一临时名与 MOVE 源一致性。

**Wave 10（收尾专项整改：Mock 清零 + 功能断点 + 缺失能力）**：对照《收尾阶段排查报告》（测试桩与假数据 / 功能完整性与断点 / 缺失能力三清单）全量整改——

**高危残留（H1-H5）**：① H1 假时间戳清零——`ConflictResolutionUiState` 的 local/remoteModifiedTime 改由冲突条目真实 `lastModificationTime` 取最新值填充（ConflictResolutionViewModel 新增 formatConflictTime），`SettingsUiState.syncLastTime` 由 `triggerSync` 真实完成时刻填充（默认「尚未同步」/「未验证」），`UnlockUiState` 假库名/假硬件声明/假剩余时长默认值清零并按「有真实数据才展示」渲染；② H2 冲突解决死路由接线——VaultList 新增 `hasPendingConflict` 状态 + 冲突横幅「去解决冲突」入口，`KeePasskeyApp` 传入真实 `onNavigateToConflictResolver` 导航，`SyncCoordinator.conflictFlow` 订阅点亮；③ H3 静默写失败链根治——`VaultRepository` 全部写方法（saveEntry/saveGroup/deleteGroup/deleteEntry/restoreEntry/emptyRecycleBin/batchMove/batchDelete/createDatabase/importExternalDatabase/removeDatabase）改返回 `KdbxResult<Unit>`，`RealVaultRepository` 收敛唯一落盘出口 `persistSession()`（失败经 DebugLogBuffer 记录并原样上抛），全部 ViewModel 调用点消费失败并反馈用户，`AutoLockManager.triggerLock` 锁库前对 DIRTY 会话 best-effort 补存（对齐 KP2A 锁库守卫），SyncCoordinator 全部 6 处 `databaseSession.save()` 结果检查（云端已上传但本地保存失败如实报 Error）；④ H4 Fake 仓库出库——`FakeVaultRepository`/`FakeSettingsRepository` 整体迁至 `app/src/test`（剥离 @Singleton/@Inject），`EntryDetailScreen` 死导入删除，`MockData.kt`/`UnlockUiState`/`VaultListUiState` 演示默认值（2026-09-04 时间戳、personal-vault.kdbx、142 KB 等）清零为中性空串；⑤ H5 回收站搜索过滤修复——`VaultListViewModel` 弃用 mock 常量 `"group_recycle_bin"`，改为按分组投影 `isRecycleBin` 标记连同全部后代分组构建回收站 id 集合过滤全局搜索结果。

**功能断点（1-11 全清）**：① 附件添加真实化——移除写死 `recovery_key_*.pem` 假按钮，`UiAttachment` 新增内存态 `data` 字段，SAF `GetContent` 真实文件选择器 + 显示名/尺寸解析，`EntryEditViewModel.addAttachment` 携带字节（同名单覆盖）；② 附件删除/保存打通——`RealVaultRepository.saveEntry` 消费 UI 附件列表（新附件 inline data 随条目提交、已落库附件按名称匹配保留 refIndex 引用、UI 移除即从条目删除），保存时经 `KdbxBinaryDeduplicator` 入池去重；③ 附件导出真实化——新增 `getAttachmentData` API + SAF `CreateDocument` 另存为（`exportAttachment` 原仅发 Toast），失败有资源化提示；④ TOTP 种子落盘——`saveEntry` 新增 `totpSecret` 显式参数（null=未修改/空串=清除/非空=写标准 `KdbxConstants.Fields.OTP`），`loadEntry` 经新 API `getEntryTotpSecret` 按需回填，修复"输入种子保存即丢"；⑤ TOTP 扫码真实化——集成 `zxing-android-embedded`，二维码按钮呼起真实扫码回填种子；⑥ TOTP 展示跨周期滚动——VaultList 周期翻转时经 `calculateEntryTotp` 批量重算实时验证码，详情页新增每秒 tick + 翻转重算（原进度环静止）；⑦ 图标编辑持久化——`mapIconNameToId`/`mapIconIdToName` 按**官方 KeePass `PwIcon` 枚举**建立 20 图标双向映射（纠正原 2=Warning 误译 email），新建/更新路径均写 `iconId`；⑧ 历史回滚全字段——新增 `getEntryRevisionSnapshot`（整修订投影 + 受保护字段解密回填 + TOTP 配置原文），`rollbackToRevision` 由仅恢复 3 字段改为全字段回滚（title/url/自定义字段/TOTP/密码）；⑨ 清空回收站覆盖子分组——`emptyRecycleBin` 递归清除回收站子分组并逐组记录墓碑；⑩ 只读模式落地——`DatabaseSession` 新增 `readOnlyMode`（open 带 readOnly 参数，`save()` 硬拒绝 + 7 个内存变更方法防御性 no-op，lock/close 复位），解锁页新增「只读打开」开关，VaultList 隐藏新建 FAB、EntryEdit 横幅+保存禁用、EntryDetail 隐藏编辑入口、写操作 VM 层前置拦截；⑪ 解锁后自动重同步——`SyncCoordinator.isSyncConfigured()`（fail-safe 探测）+ VaultList 进入即 `syncNow`。

**缺失能力补齐**：tags（逗号分隔输入）/overrideUrl/AutoType 默认序列（`mergeAutoType` 保留既有 associations，归约空配置置 null）三项编辑 UI 与全链路落盘打通（模型/序列化层此前已完好）；`SyncCoordinator` 测试缝加 `@VisibleForTesting`；UI 附件/标签/AutoType 全部进 `UiVaultEntry` 投影与编辑往返。

**Wave 11（内存安全与密码学审计整改：主密码/加解密/内存驻留）**：对照主密码处理、加解密流程与内存驻留专项审计（本地参考标准 KeePassDX 内存安全设计）发现的 11 项缺陷全量修复——

**高危（H1-H5）**：① H5 KDF 参数上界校验——`KdbxHeader.deserializeKdfParameters` 此前对文件声明参数全盘信任，恶意 KDBX 可声明 1TB 级 Argon2 内存（分配期 OOM）或 2^60 级 AES 轮数/迭代（无限期占用 CPU）造成拒绝服务；现增加绝对封顶（Argon2 内存 [1MiB, 4GiB] + 迭代 ≤ 2^24 + 并行度 ≤ 64 + 版本白名单 + JVM 堆一半动态门槛、AES-KDF 轮数 ≤ 2^28），越界抛 `KdbxCorruptFileException`（对齐 KeePassDX Limits / KeePassXC 封顶语义），+`KdfParametersBoundsTest` 8 用例；② H4 QuickUnlock 封印密钥认证语义修正——原 QUICK_UNLOCK 别名以默认参数生成（per-operation 认证绑定），而 QuickUnlock 纯 PIN 路径无 BiometricPrompt CryptoObject，真机上 seal/unseal 首次 doFinal 必抛 `UserNotAuthenticatedException`（绑定被 `catch(ignored)` 吞掉 → 功能整体静默失效，单测经 customSealer 旁路无法暴露）；新增 `KeystoreManager.getOrCreateUnauthenticatedKey`（KeyInfo 探测旧认证密钥自动删除重建迁移）+ `initSealCipher`/`initUnsealCipher`，安全模型如实收敛为「PIN 校验器门槛 + 硬件密钥不可导出」（KDoc 声明 root 设备边界）；③ H1 QuickUnlock 主密码 String 物化清零——`bindCredential` 的 `String(masterPassword).toByteArray()` 与 `unlockWithPin` 的 `String(plainBytes).toCharArray()` 两处不可变驻留改为 CharBuffer/ByteBuffer 直转，全链路零 String；④ H2 建库链路 CharArray 化——`VaultRepository.createDatabase` 签名 String→CharArray（借用语义），`DatabasePickerViewModel` 复制私有副本 finally 擦除，创建向导弹窗两个密码框换用 `SecurePasswordField`（组件离场 DisposableEffect 擦除、CharArray 桥接比对）；⑤ H3 `KdbxFile.load` 旧派生探针路径 `.first` 取值丢弃的 64 字节 hmacKey64（transformedKey 直接派生物）补 `finally` 擦除。

**中危（M1-M6）+ 低危（L2）**：① M1 详情页明文驻留收敛——`EntryDetailViewModel` 新增 `clearAllRevealedSecrets` 纪律：切换条目（setEntryId）、关闭对比弹窗（`clearRevisionDiff` 原实现漏清 `revealedRevisionPasswordsFlow`，修订密码跨弹窗累积驻留为真实缺陷）、Screen 离开组合（新增 `onScreenDisposed` + DisposableEffect 挂接）三个时机全量擦除按需解密明文；② M3 StrongBox 支持——`KeystoreManager.generateNewKey` 在 `FEATURE_STRONGBOX_KEYSTORE` 设备上优先 `setIsStrongBoxBacked(true)`，`StrongBoxUnavailableException` 自动回退 TEE（对齐 KeePassDX DeviceUnlockManager）；③ M5 `DatabaseSession.open` 从 `Dispatchers.IO` 切至 `Dispatchers.Default`——Argon2/CPU 密集段不再占死 IO 线程池（save 侧对称：CPU 在 Default、写盘在 IO）；④ M6 KdfBenchmark 真实接线——安全设置「运行性能基准测试」原为仅设置假完成消息的死按钮，现接线 `SettingsViewModel.runKdfBenchmark`（Dispatchers.Default 实测 Argon2 单轮耗时按 1s 目标外推，memoryClass 应用堆上限为内存约束），推荐参数自动填入 Argon2 调节项（新增 `KdfBenchmarkUiState` + 中英文资源）；⑤ L2 密钥别名卫生——生物识别凭据清除（KeyPermanentlyInvalidated 路径）联动 `deleteKeyForDatabase`，`QuickUnlockPinStore` 清凭据后无剩余封印时删除共享 QUICK_UNLOCK 别名。

**P3 内存防御升级（对齐 KeePassDX `protectInMemory`，弥合最大能力差距）**：新增 `core/security/InMemoryCipher`——`ProtectedString`（isProtected=true）在 JVM 堆内以**密文形态驻留**（AES-256-CTR，IV=SHA-256(进程密钥‖明文) 前 16 字节确定性派生并与实例共存），读取瞬间解密出临时副本用毕立即擦除；确定性映射保证相同明文恒得相同密文，`equals`/`hashCode` 直接比较密文即可——同步变更检测与三方合并的比较路径（Wave 9 C1/F3 语义）不解密、不物化明文；IV 依赖进程密钥派生，攻击者仅凭 dump 的 (IV, 密文) 无法对低熵值构造离线爆破预言机。如实声明边界：纵深防御层，取得进程密钥或具备任意代码执行能力者仍可在读取瞬间截获明文（KeePassDX 同级取舍）。+`ProtectedStringMemoryEncryptionTest` 12 用例（密文≠明文/往返/确定性/逐值 IV/相等性/清零拒绝）。

**Wave 12（真实 KDBX 4.0 库与复合密钥真机互操作专项整改）**：对照用户真实文件（`测试.kdbx` + `111.keyx` + 主密码）在 Android 16 模拟器真机实测，与 KeePass 2.61.1 官方 C#、KeePassDX 与 KeePassXC 源码交叉验证，完成 6 项深层格式与算法缺陷修复——
① **解锁页密钥文件虚假断链清零**：`VaultRepository.unlockActiveDatabase` 与实现类增加 `keyFileData: ByteArray?` 借用语义透传至会话，`UnlockScreen` 接入系统级真实 SAF 文档选择器（`ActivityResultContracts.OpenDocument`），安全读取并全链路直达会话，用毕显式清零；复合密钥模式下安全禁用不可还原的硬件 QuickUnlock；
② **密钥文件解析梯子落位**：新增 `KdbxKeyFile`，严格实现 XML KeyFile（v1.0 Base64 / v2.0 Hex + Hash 前 4 字节校验）、32B 裸二进制、64Hex 文本与整文件哈希四级梯子，新增 10 个测试用例（覆盖真实 111.keyx 向量）；
③ **Argon2 / Cipher 官方 UUID 纠正**：修正 `KdbxConstants.Kdf.ARGON2D`（`EF636DDF-8C29-444B-91F7-A9A403E30A0C`）、`ARGON2ID`（`9E298B19-56DB-4773-B23D-FC3EC6F0A1E6`）与 ChaCha20/Twofish 四个官方 UUID；
④ **变体字典类型宽容与 P 读参规范**：`VariantDictionary` 实现数值 getter 宽容自适应（防御 UInt32 在 Int/Long 间的非法转换与符号扩展），`KdbxHeader` 对齐 KeePassDX 按 `getUInt32("P")` 读参；
⑤ **HMAC 块签名索引前缀补齐**：块 HMAC 签名数据补齐开头的 `LittleEndian64(blockIndex)` 前缀；
⑥ **载荷压缩与内层 Header 读写顺序纠偏**：对齐官方 C# `KdbxFile.Read.cs:172-178`（"Binary header before XML"）与 KeePassDX `DatabaseInputKDBX`，将内层 Header 置于 GZIP 压缩流内部处理（解密 → GZIP解压 → 读取内层 Header → 解析XML）；派生探针相应升级为优先识别 GZIP 魔数（`1F 8B 08`）；官方 `cipherKey = SHA-256(masterSeed ‖ transformedKey)` 归正，历史 SHA-512 截断公式仅作为旧文件探针回退路径。
**最终双向验证**：模拟器真机以复合密钥成功解锁 `测试.kdbx`，正确读取群组「111」及条目「11」（明文密码 `~W4hUziUy7FSRR#K@N@K` 完全一致）；在 KeePasskey 中新建条目并落盘后，经独立第三方工具 `pykeepass` 完整往返读取校验通过。详见 `docs/KDBX4与复合密钥实战互操作排查日志.md`。

**测试基线**：全工程 223 个单元测试全绿（app 70 / core 21 / crypto 35 / database 50 / sync 47；Wave 12 新增 11 例）；`assembleDebug` 与 `assembleRelease`（R8 混淆）构建闭环通过。

**已知限界（如实记录，详见 `REMEDIATION_PLAN.md` 执行日志）**：KDBX 解析已流式化，但对象树（KdbxGroup/KdbxEntry）仍整体驻留内存（增量加载/进度 Flow 远期）；S3 条件写依赖服务端支持——AWS S3 原子生效，少数未实现 If-Match 覆写的兼容存储降级为 HEAD 预检+无条件 PUT（KDoc 注明），WebDAV uploadAtomic 的 `If` 头 tagged list 预条件在个别极简 DAV 服务端可能被忽略（退化为普通事务写，不影响正确性）；KDBX 受保护字段以字符串承载为格式层边界——Passkey 私钥编码 String 存活期与 ProtectedString 一致，生成/签名路径的中间字节量均显式清零；`ProtectedString` 驻留加密（Wave 11）为纵深防御层——对抗堆扫描/崩溃转储中的明文暴露，取得进程密钥或具备任意代码执行能力的攻击者仍可在读取瞬间截获明文（KeePassDX 同级取舍）；Compose 框架层 TextField 仍以 String 承载输入（框架 API 限制，已收敛至 `SecurePasswordField` 单点、最短生命周期；Unlock 与 DatabasePicker 创建向导已接入该组件，EntryEdit/Settings 的密码框尚未接入）；QuickUnlock PIN 已真实校验（PBKDF2 校验器 + Keystore 封印凭据，封印密钥为非认证绑定硬件密钥——安全门槛由 PIN 校验器 + 密钥不可导出承担，root 设备边界见 KDoc），但 PIN 输入仍为 String（4 位短数字，框架限制）；浏览器特权白名单内置 Chrome 稳定版签名指纹，浏览器证书轮换或白名单外浏览器将 fail-closed 降级为 apk-key-hash 路径（安全不放松，功能降级），需随浏览器版本更新指纹；自定义图标（customIcons 模型/序列化层完好）尚无上传/选择 UI、KeePass 字段引用（{REF:...}）引擎未实现，均列为下一轮特性计划；外部库经导入复制进内部存储后原地编辑（不写回外部原文件）为当前设计取舍。
