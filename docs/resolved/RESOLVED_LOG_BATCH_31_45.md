# KeePasskey 已整改问题与历史归档 · 分册 02（§31 ~ §45）

> **分册定位**：本册是 [RESOLVED_LOG.md](../RESOLVED_LOG.md) 的历史分册，收录 **§31 ~ §45** 全量正文（2026-09-12 ~ 2026-09-13 批次）。
> **主文件职责**：RESOLVED_LOG.md 只保留全量批次索引与最近 10 个批次（当前 §58 ~ §67）正文；新批次先写入主文件，满额后按序下沉分册。
> **引用定位**：仓内其它文档的「RESOLVED_LOG.md §NN」引用，凡 NN 落在 §31 ~ §45 区间，正文即本册对应小节（可用本册索引跳转）。
> **体例（2026-09-15 保守精简立规）**：各节保留原结论、裁决、残余登记与「过程缺陷」原文；仅把重复的验收记录压缩为「命令 + 状态摘要」。**未删除任何结论性内容**。

---

## 本册章节索引

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§31](#s31) | 文档类存量整改批次（隐私政策 / 同步层威胁建模） | ISSUE-P3-75 / P3-77 |
| [§32](#s32) | 存量功能整改批次（CSV 导入 / 导出扩充） | ISSUE-P3-73 |
| [§33](#s33) | 产品裁决：不排期 / Won't Do（对标项与外部依赖项） | ISSUE-P2-25 / P2-26 / P2-27 / P3-23 / P3-58 / P3-66 / P3-74 |
| [§34](#s34) | app 设备侧验证骨架与导入解析回归 | ISSUE-P2-27 / P3-66（部分收窄） |
| [§35](#s35) | ISSUE-P2-24 大附件磁盘缓存池（阶段 1/2/3 全量落地） | ISSUE-P2-24 |
| [§36](#s36) | ISSUE-P2-27 设备侧验证缺口收口（app + sync） | ISSUE-P2-27 |
| [§37](#s37) | 工程整洁与文档准确性收口 | 工程整洁 / 文档准确性 |
| [§38](#s38) | KDBX 互操作与安全整改批次（P0×3 / P1×6 / P2×14 + 文档纪律） | ISSUE-P0-05~07 / P1-16~21 / P2-28~41 / P3-81 |
| [§39](#s39) | 红队攻击路径批次处置归档（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P1-22~24 / P2-43~47 / P3-82~85 |
| [§40](#s40) | 外部安全审计报告退役批次（报告退役 + 存量项转登 ACTIVE_ISSUES） | 转登 ISSUE-P2-48 ~ P2-60 / P3-86 ~ P3-97 |
| [§41](#s41) | 敏感数据流审计报告退役与分流（`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`） | 转登 ISSUE-P2-61 ~ P2-74 / P3-98 ~ P3-115 |
| [§42](#s42) | 威胁建模与架构评估报告退役批次（`THREAT-MODEL-AUDIT-d32f3e7.md` 退役 + 存量项转登） | 转登 ISSUE-P2-76 ~ P2-79 / P3-116 ~ P3-124 |
| [§43](#s43) | 安全问题与整改方案报告退役批次（`SECURITY_AUDIT_REMEDIATION.md` 退役 + 附录 A–F 留存） | 处置归档（正文 32 项核对无缺口，附录 A–F 留存 §43.3 ~ §43.8） |
| [§44](#s44) | P0 双项整改批次：字段引用消费点白名单 + 同步崩溃面遏制 | ISSUE-P0-08 / ISSUE-P0-09 / ISSUE-P2-75 |
| [§45](#s45) | P1 双项整改批次：确认页调用方归属与首次绑定授权 + 剪贴板口令面引用敏感通道 | ISSUE-P1-24 / ISSUE-P1-25 |

---

<a id="s31"></a>
## §31 文档类存量整改批次（2026-09-12）：P3-75 / P3-77

> **来源**：`ACTIVE_ISSUES.md` 中「参考项目对比分析」产出的 P3 项。两条均属**文档/评估型**交付：
> 不新增代码路径，结论与承诺须与代码事实逐条可核。

### 31.1 ISSUE-P3-75：独立隐私政策文档 + noNet 构建变体评估

- **交付物**：新增 [`docs/Privacy-Policy.md`](../Privacy-Policy.md)（AC①、AC③）。
  逐条列明并经代码核实：无遥测 / 分析 / 广告 / 崩溃上报 SDK（`app/build.gradle.kts` 依赖列表，全仓检索
  `firebase`/`analytics`/`crashlytics` 等仅命中文案与供应链抑制文件）；网络访问仅限「用户启用的云同步」
  与「默认关闭的 HIBP k-匿名查询」（`ExtendedSettings.breachCheckEnabled` 默认 `false`；
  `SettingsHealthController` 关闭态零外联；`HibpRangeClient` 仅送 SHA-1 前 5 位）；全站 TLS-only
  （`network_security_config.xml` + OkHttp TLS-only ConnectionSpec）；日志脱敏（`AppLog` release 仅留异常类名）。
- **AC② 结论：评估后暂不实施 `productFlavors { noNet }`**，理由（见政策 §7）：
  1. flavor 化会使 `assembleRelease` 产物由 `app-release.apk` 变为 `app-<flavor>-release.apk`，
     直接违反 `AGENTS.md` §3.8 的稳定版产物路径契约并波及 CI；
  2. 同步 / 泄露检测已深入导航、Hilt、WorkManager 与自动填充链路，flavor 裁剪易在「看似禁网、
     实则留旁路」方向引入隐蔽缺陷；
  3. 默认配置下本应用本就不联网，禁网变体几无额外保护收益。
  替代路径已写入政策（如需硬性禁网，建议独立分支 / 渠道维护）。
- **禁止项核对**：政策未写入任何与实现不符的承诺；noNet 结论为「评估不实施」而非「已实施」。

### 31.2 ISSUE-P3-77：同步层记录级密钥承诺威胁建模

- **交付物**：新增 [`docs/同步层记录级完整性威胁建模.md`](../同步层记录级完整性威胁建模.md)（AC①、AC③）。
  在 Assume Breach（云端不可信、无主密钥）模型下，拆分「跨记录 / 跨上下文置换」为
  **条目置换 / 块级置换 / 跨路径整文件置换**三类，逐类给出既有机制的覆盖边界。
- **关键结论**：
  - KDBX 是**单体加密流**，条目非独立 AEAD 记录；块 HMAC 的**块索引并入块密钥**
    （`BlockHmac.compute`：`HMAC_{SHA512(LE64(index)‖hmacKey64)}(LE64(index)‖LE32(size)‖data)`），
    故 mdbx 所关注的 key-commitment 置换攻击面在 KDBX 模型中**不成立或已被覆盖**；
  - **不引入**记录级 AEAD 承诺、**不改动 KDBX 字节布局**（避免破坏与 KeePass 2.x / KeePassXC 互操作，
    亦为本条 AC 明令禁止）；
  - **不明示引入**「远端路径 + 版本 / epoch + 内容哈希」MAC 绑定（AC② 条件未触发）：现有同步 MAC
    认证对象是**本地**高水位状态文件（本地文件级攻击者不在模型内），对远端混淆无直接拦截力，
    且「同内容多路径」是合法场景、绑定会引入误报；
  - **残余风险（明示接受）**：跨路径整文件混淆为**低危**（不触及机密性 / 完整性），由内容可见异常与
    三哈希 / 防回滚链共同限制；重评估触发条件（转 per-record 架构）已写入文档 §5。
- **禁止项核对**：未改动 KDBX 字节布局；未照搬 mdbx 草稿规范作为交付基线（仅作方向参考）。

### 31.3 批次验收证据（2026-09-12）

- **单元测试**：`.\gradlew.bat test --rerun-tasks --max-workers=1` → **BUILD SUCCESSFUL**
  （本批次为纯文档交付，未改动代码，沿用 1402 例基线）。
- **稳定版构建**：`.\gradlew.bat assembleRelease` → **BUILD SUCCESSFUL**。产物完整路径：
  `D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`。
- **关联提交**：本批次文档与代码为**同一次** `git commit`（提交主题以 `ISSUE-P3-75 / P3-77` 引用）。

---

<a id="s32"></a>
## §32 存量功能整改批次（2026-09-12）：P3-73 CSV 导入 / 导出扩充

### 32.1 AC① CSV 解析器覆盖扩充（LastPass / Chrome / Edge）

- **开工核实**：`BrowserCsvImporter` 已按表头名映射 `name/url/username/password/note` 及 Bitwarden
  的 `login_*` 别名 → **Chrome / Edge 已被覆盖**，无需新增解析器；真正缺口是 **LastPass** 的
  `extra`（备注）与 `grouping`（分组路径）列未被映射。据此在既有 `@IntoSet` 开闭框架内**扩展同一边界**
  （不新增数据源枚举、不改调用方），符合 AC① 的「追加 CSV 解析器（不改调用方）」意图。
- **整改**（`app/src/main/java/com/keepasskey/app/data/importer/BrowserCsvImporter.kt`）：
  1. `NOTE_HEADERS` 增补 `extra`（LastPass 备注列）；
  2. 新增 `CsvColumnRole.GROUP` 与 `GROUP_HEADERS = {grouping, group, group_name, folder}`，
     单元格以 `\` 或 `/` 分层解析为 `groupPath`（`CsvCells.groupPath` → `ImportedEntry.groupPath`）；
  3. 空分组列 → 空路径（落至根分组），Chrome / Edge 行为不变。
- **回归用例**（`BrowserCsvImporterTest`）：新增「LastPass 表头 extra/grouping 映射」「斜杠分层与空分组落根」
  两例；并将原「未识别多余列」用例的列名由 `extra` 改为 `ignored_col`（因 `extra` 已升格为备注别名，
  原用例前提失效，就地修正）。

### 32.2 AC② 通用明文 CSV 导出 + 强制二次确认

- **新增导出器**（`database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt`）：
  列固定 `name,url,username,password,notes,group`，RFC 4180 引号语义（含分隔符 / 引号 / 换行的字段整体
  加引号、内部 `"` 双写转义），分组列以 `\` 分层（与导入侧互为往返）；逐行流式写出，不构造整份明文字符串。
- **接线**：`VaultRepository.exportVaultCsvBytes()` → `VaultExportCoordinator`（`Dispatchers.Default`）→
  `RealVaultRepository` → `SettingsExportController.exportVaultCsvTo` → `SettingsViewModel` →
  设置页导出对话框按钮 + **明文二次确认弹窗**。
- **风险门禁**：新增 `ExportArtifactKind.PLAINTEXT_CSV` 并纳入 `ExportConfirmationPolicy.riskOf` 的
  `PLAINTEXT` 分支 → 未确认一律 fail-closed（不放行任何字节）；确认文案 `dbset_export_csv_plain_warn_title/
  message` **显式写明「明文 CSV」**（中英双语文案齐备）。
- **对称清理**：取消 / 未确认分支复用 `SafDocumentCleanup` 删除 SAF 已创建的空文档（ISSUE-P2-20 同语义）。

### 32.3 AC③ 新增单测

- `database`：`KdbxCsvExporterTest`（4 例：表头与根条目、引号 / 逗号 / 换行转义、子分组 `\` 分层、空分组与明文口令）。
- `app`：`BrowserCsvImporterTest` 增 2 例；`ExportConfirmationPolicyTest` 增 1 例
  （`PLAINTEXT_CSV` 归入明文风险且未确认不放行）。

### 32.4 批次验收证据（2026-09-12）

- **单元测试**：`.\gradlew.bat test --max-workers=1` → **BUILD SUCCESSFUL**（全模块 `testDebugUnitTest`
  全绿；本次新增/改动用例均通过）。
- **稳定版构建**：`.\gradlew.bat assembleRelease` → **BUILD SUCCESSFUL**（R8 混淆 + 资源收缩 + 签名）。
  产物完整路径：`D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk`。
- **关联提交**：本批次文档与代码为**同一次** `git commit`（提交主题以 `ISSUE-P3-73` 引用）。

---

<a id="s33"></a>
## §33 产品裁决：不排期 / Won't Do（2026-09-12）

> **本节性质**：以下条目**不是「已整改」**，而是经**产品裁决不排期**的治理记录，移入本节以维持可追溯、
> 防止未来重复评估，并使 [ACTIVE_ISSUES.md](../ACTIVE_ISSUES.md) 只保留真正的待办。
> **裁决口径（须如实留痕，勿误读）**：裁决依据**不是**「经典 KDBX 软件没有这些能力」——经核对，
> P2-24/25/26/74 所述能力在参考项目中**确实存在**（各条目原文的「参考做法」即来源于对标），
> P2-27/23/58/66 则是**验证覆盖**而非功能。真正依据有三：
> ① **产品边界**——本仓定位为 KDBX v4 + WebDAV/S3 的**本地优先**管理器，不追求 keepass2android 式
> 「全协议聚合 / 全特性对齐」；② **无实际用户需求驱动**；③ **部分条目依赖本地不具备的外部环境**。

### 33.1 裁决清单

| 条目 | 原优先级 | 类型 | 裁决理由 | 重新评估触发条件 |
|---|:---:|---|---|---|
| **P2-25** S3 上传/下载流式化 | P2 | 性能（大库） | 收益/代价倒挂：`SyncEngine` 为整文件字节模型，仅改 Provider 拿不到「不产生整库内存峰值」；SigV4 需预知载荷 SHA-256，流式直传受限（`UNSIGNED-PAYLOAD` 削弱签名，被该条 AC 明令禁止） | 出现真实「大库 + S3」OOM 反馈时，与 P2-24 合并为「内存专项」 |
| **P2-26** SFTP 同步后端 | P2 | 功能覆盖 | 产品边界：WebDAV 已覆盖绝大多数自建 NAS；不追求全协议聚合；引入 SSH 依赖与 host key / 密钥管理会扩大攻击面 | 产品明确要求 SFTP 且接受依赖与密钥管理成本 |
| **P2-27** AssistStructure 快照回归 | P2 | 验证覆盖 | 外部环境依赖：需在真机采集真实应用/浏览器快照；**主动接受该解析层未验证风险**（§24 / §26 已有先例） | 具备真机采集环境 |
| **P3-23** arm64 真机 instrumented 验证 | P3 | 验证覆盖 | 外部资源依赖：本机无 arm64-v8a 镜像、无真机连接；真实语料已入库、AC② 已闭环 | 取得 arm64 真机 |
| **P3-58** CodeQL Kotlin 覆盖 | P3 | 静态分析覆盖 | 上游阻塞：CodeQL 不支持 Kotlin 2.4.20；**不得为覆盖回退 Kotlin 版本**（本仓 2.4.20 承载 CVE-2026-53914 真修复） | 上游抽取器支持 ≥ 2.4.20 |
| **P3-66** 自动填充/Passkey 端到端实测 | P3 | 验证覆盖 | 外部环境依赖：需系统凭据服务托管 + 含登录表单的浏览器/测试页；**主动接受端到端未验证风险** | 具备实测环境 |
| **P3-74** 条目模板机制 | P3 | 体验增强 | 无需求、非缺陷；纯产品增强且工作量大 | 产品排期 |

### 33.2 说明与风险留痕

- **P2-25 / P2-26 / P3-74 属「功能/性能增强」**：不做**不影响正确性与安全**，仅影响极端规模或特定后端下的体验与覆盖。
- **P2-27 / P3-23 / P3-58 / P3-66 属「验证覆盖」**：不做 = **主动接受对应面未验证的风险**，
  **不等于「该面已无问题」**。本仓 §24 / §26 已两次证明「宿主 JVM 过、Android 运行时挂」，
  该风险应在后续具备真实环境时**优先回补**。
- 本节为**产品裁决**：若产品目标变化（如新增 SFTP 后端诉求、上架渠道要求更严的完整性/设备侧验证），
  应按上表「重新评估触发条件」重启评估，而非默认永久关闭。
- **保留待办**：**ISSUE-P2-24**（附件磁盘缓存，已留存分阶段方案）与 **ISSUE-P3-76**（输入法个性化学习，
  框架阻塞）仍留在 [ACTIVE_ISSUES.md](../ACTIVE_ISSUES.md) 跟踪，未纳入本节裁决。

---

<a id="s34"></a>
## §34 app 设备侧验证骨架与导入解析回归（2026-09-12）

> **动机**：本仓 `app` 模块此前**没有 `androidTest` 源集**，导致解锁 / 自动填充 / 通行密钥等核心链路
> 从未在真实 Android 运行时被验证；而 §24（ISSUE-P1-12 KDBX XML 在 Android 全量失败）与
> §26（ISSUE-P0-04 字段引用正则在 Android ICU 非法致崩）已**两次**证明该类「JVM 全绿、Android 挂」
> 缺陷会真实逃逸。本节为**收窄该盲区的第一步**（对应 §33 中 P2-27 / P3-66 的解析层部分）。

### 34.1 交付

1. **`app` 设备侧源集接线**（`app/build.gradle.kts`）：新增
   `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` 与
   `androidTestImplementation`（`androidx.test.ext:junit` / `androidx.test:runner` / `coroutines-test`，
   均复用既有版本目录）。
2. **首个设备侧用例集** `app/src/androidTest/.../data/importer/ImporterAndroidRuntimeTest.kt`（3 例）——
   把**导入解析**放回真实 Android 运行时执行（XML 解析器 / ICU 正则 / 字符集均是平台差异面）：
   - KeePass XML 导入完整字段映射；
   - 浏览器 CSV 按表头映射（列序无关）；
   - 非 `KeePassFile` 根 **fail-closed**。

### 34.2 验收证据（2026-09-12，x86_64 模拟器）

- **编译**：`.\gradlew.bat :app:assembleDebugAndroidTest` → **BUILD SUCCESSFUL**。
- **设备侧执行**：`.\gradlew.bat :app:connectedDebugAndroidTest`（`emulator-5554`，**x86_64 / API 36.1 /
  google_apis**）→ **BUILD SUCCESSFUL**；结果 XML `app/build/outputs/androidTest-results/connected/debug/`
  摘要：`tests="3" failures="0" errors="0" skipped="0"`（**真实执行，非跳过**）。
- **环境说明**：使用 `Pixel_10` AVD 冷启动 + `-wipe-data`（首次尝试因模拟器存在旧签名残留报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，清理并以干净实例重跑后通过——如实留痕）。

### 34.3 边界与后续

- 本节仅覆盖 **app 导入解析层**；`app` 端到端（自动填充域解析、解锁、Passkey、通知）与 `sync` 仍无设备侧覆盖。
- **arm64 原生加密内核验证仍缺**：本机仅 x86_64 镜像，arm64 镜像在 x86_64 主机上属翻译模拟（未执行）。
  该缺口对应 §33 的 ISSUE-P3-23，维持「不排期/外部依赖」。
- **关联提交**：本批次代码与文档为**同一次** `git commit`（提交主题以 `ISSUE-P2-27 / P3-66` 引用）。

---

<a id="s35"></a>
## §35 ISSUE-P2-24 大附件磁盘缓存池（2026-09-12，阶段 1/2/3 全量落地）

> **闭环声明**：`ACTIVE_ISSUES.md` 中 ISSUE-P2-24 的正文（含「为什么本次不落半成品」评估与分阶段方案）
> 已整条移入本节；该条目从待办清单移除。

### 35.1 问题与设计

- **问题**：附件字节「内层二进制池常驻 + 逐附件副本」双份驻留 GC 堆，大附件库存在 OOM / GC 压力。
- **设计**：分离「逻辑引用」与「物理字节」——超过阈值（默认 **1 MiB**，可配）的附件在解析期即
  **流式落盘**，池中只保留 store key；[KdbxAttachment.data] 按需读回**独立副本**，
  别名隔离契约（ISSUE-P3-07）逐条保持。
- **关键取舍（诚实留痕）**：落盘附件的字节由**多个引用者共享**（去重复用同一 store 条目），
  故 [KdbxAttachment.clear] 对落盘项**不动作**（清零会连带损坏其它引用者）——
  其生命周期改由会话锁定时的 `BinaryStore.clear()` 统一收口。经全仓核实**生产代码无 `clear()` 调用方**
  （仅测试使用内存副本路径），故该语义变化无生产影响。

### 35.2 交付清单

1. **`core`**：新增 `BinaryStore`（`store` / `storeFromStream` / `load` / `openStream` / `sizeOf` / `clear`）、
   `BinaryStorePolicy`（阈值策略）、`BinarySource`（附件字节来源抽象）；`KdbxAttachment` 增可选
   `source: BinarySource?`（构造签名向后兼容）与 `size` / `openStream()`。
2. **`database`**：`InnerHeader.BinaryItem` 支持落盘引用（`data` 按需读回、`size` 不触发读取、
   `contentHash()` **流式**计算且与 `Arrays.hashCode(byte[])` 逐位等价、`writeTo` 流式写出、`withFlags` 零读取改标志）；
   `InnerHeader.deserialize(stream, store?, threshold)` 大字段**流式落盘**（长度/条目数/累计字节数三重守卫保留）、
   `serialize` 流式写出；`KdbxXmlBinaryNode` 落盘项不再 `copyOf`（挂引用）；`KdbxBinaryDeduplicator`
   指纹改为 `(flags, size, 内容哈希)` + **同指纹碰撞时流式逐字节复核**（绝不误合并），并复用落盘 store key；
   `KdbxFile.load(..., binaryStore = null)`（`null` → 旧行为逐字不变）。
3. **`app`**：`FileBinaryStore`（`cacheDir/attachments`，实现 `BinaryStore` + `SessionLockObserver`）；
   `SessionOpener` / `DatabaseSession(binaryStore)` 接线；`DatabaseModule` 注入并注册锁库观察者；
   `VaultEntryMapper` 改用 `attachment.size`（**不再把整池 map 成字节数组**）、`VaultEntrySecretReader`
   改用 `attachment.data` 按需读取。
4. **`sync`**：`SyncCache` 增 `writeCacheStreaming` / `openCacheStream` / `cacheSize`
   （复用既有 0600 / 0700 落盘基线，供 `FileBinaryStore` 组合）。

### 35.3 验收证据

| AC | 内容 | 证据 |
|:--:|---|---|
| ① | >1 MiB 附件走磁盘缓存、不整入内存 | `InnerHeaderBinarySpillTest`（7 例）+ `KdbxFile` 往返用例；设备侧 `DatabaseSessionAndroidRuntimeTest` 实测落盘 |
| ② | 锁定 / 关闭时对称清理 | `SessionLockObserver` 接线 + 设备侧用例断言锁定后缓存目录清空 |
| ③ | 权限 0600 / 0700 | `SyncCacheAndroidRuntimeTest`（设备侧 **POSIX 实测**，非降级分支） |
| ④ | KDBX 字节语义不变 | `KdbxAttachmentAliasIsolationTest` **4 例原样通过（未改写）**；`KdbxBinaryDeduplicatorTest` 3 例通过；`InnerHeaderBinarySpillTest` 往返逐字节等价 + 去重/池一致性 |

- **单测**：`.\gradlew.bat test` → **BUILD SUCCESSFUL**；debug 单测 **1423 例 / 0 失败 / 0 错误 / 13 跳过**
  （本批次新增 14 例：`core` 3 / `database` 7 / `sync` 4）。
- **设备侧**：见 §36。

### 35.4 边界

- KDBX 对象树**其余部分**仍整体驻留内存（本次只解决附件字节）；`AGENTS.md` §6 已同步修订。
- 阈值为**编译期默认 + 参数可配**（`BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`），暂无用户可视开关。

---

<a id="s36"></a>
## §36 ISSUE-P2-27 设备侧验证缺口收口（2026-09-12，app + sync）

> **动机**：§34 建立了 `app` 设备侧骨架但仅覆盖导入解析，`sync` 仍无 `androidTest` 源集。
> 本节把「域解析（正则 / PSL / IDN）」「解锁落盘」「落盘权限」三类**平台运行时相关**逻辑
> 放回真实 Android 运行时执行，收窄「JVM 全绿、Android 挂」缺陷类（§24 / §26 已两度逃逸）的盲区。

### 36.1 交付

1. **`app`**（新增 9 例，累计 12 例）：
   - `DomainMatcherAndroidRuntimeTest`（7 例）：主机名剥离、严格点号边界、公共后缀下限、
     私有段后缀、**IDN ↔ punycode 跨形式匹配**（`java.net.IDN`）、webDomain 归一化与归属 fail-closed。
   - `DatabaseSessionAndroidRuntimeTest`（2 例）：生产管线产出 `.kdbx` → 生产 `DatabaseSession` 解锁 →
     大附件落盘 / 权限 0600 / 目录 0700 / 字节往返 / 锁定即清空；阈值以下不落盘。
2. **`sync`**（新源集 + 3 例）：`sync/build.gradle.kts` 接线 `testInstrumentationRunner` 与
   `androidTestImplementation`；`SyncCacheAndroidRuntimeTest` 验证落盘权限收敛（0600 / 0700）与
   流式落盘读回一致、`clearAll` 清空。

### 36.2 验收证据（2026-09-12，x86_64 / API 36.1，`emulator-5554`）

- `.\gradlew.bat :sync:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**；
  `sync/build/outputs/androidTest-results/connected/debug/TEST-*.xml`：
  `tests="3" failures="0" errors="0" skipped="0"`。
- `.\gradlew.bat :app:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**；
  `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`：
  `tests="12" failures="0" errors="0" skipped="0"`。
- **过程留痕（如实）**：首轮 app 侧 2 处失败——① 测试方法因 `runBlocking` 返回非 `Unit` 触发
  `InvalidTestClassError`；② 误将 `extractDomain` 期望为剥离子域。均已修正后复跑通过。

### 36.3 边界

- **Passkey 系统级交互、`AssistStructure` 结构树扫描、通知渲染**仍未设备侧覆盖：
  前三者依赖系统凭据对话框 / 真实自动填充会话 / 通知栏，超出常规 instrumented 用例的可控范围，
  维持宿主 JVM 覆盖 + 如实留痕。
- **arm64 真机**仍缺（沿用 §33 ISSUE-P3-23「不排期/外部依赖」）。

---

<a id="s37"></a>
## §37 工程整洁与文档准确性收口（2026-09-12）

| 项 | 问题 | 处置 |
|---|---|---|
| D1 | `dbset_import_reserved_note`（「解析器预留，暂未生效」）为**死文案且与现状相反**（导入已落地、零渲染点） | 从 `values` / `values-en` 删除；保留 `DatabaseSettingsDialogs.kt` 的历史说明注释 |
| D2 | TAN 序列号 / 数据库 UUID 两个**永久禁用**开关标注「即将支持」，隐含无计划兑现的路线图承诺 | 文案改为「暂不支持 / not supported yet」（`values` + `values-en`），实现侧仍是如实禁用态 |
| D3 | 「填充后自动返回」开关可持久化但**无任何行为消费方**（假开关） | `AutofillSwitchRow` 增 `enabled` 参数；该行实测禁用交互并降透明度（保留「预留，暂未生效」标注） |
| D5 | lint 是否具阻断力 | `.\gradlew.bat :app:lintRelease` → **BUILD SUCCESSFUL（EXIT 0）**：无配置豁免即默认 `abortOnError`，当前无阻断项 |
| D6 | `AGENTS.md` §6 称「独立窗口（如 `BaseCredentialActivity` 系）需单独接线」——**已过时** | 更正为按窗口分类如实描述：自动填充 / 通行密钥窗口调用 `ApplyObscuredTouchFilter()`；`BaseCredentialActivity` 体系以 `setHideOverlayWindows(true)` 屏蔽悬浮窗（强于触摸过滤），**无未接线盲区** |

- **产品裁决项（本轮未动，如实留痕）**：`versionCode` / `versionName`（`1` / `0.1.0`）属**发布定型决策**，
  未经明确发布计划不改动（避免版本号与对外发布节奏脱节）。

---

<a id="s38"></a>
## §38 KDBX 互操作与安全整改批次（P0×3 / P1×6 / P2×14 + 文档纪律）（2026-09-12）

> **本批次缘起**：对「本仓实现 / 官方 KeePass 2.61.1 / KDBX 4.1 规范 / Android 安全模型」四方逐项对比后，
> 确认 23 项缺陷（对应 `ACTIVE_ISSUES` 的 P0-05…P2-41）。**核心结论**：KDBX 格式兼容**不等于**安全属性等价——
> 既能打开同一个库、又通过自家互操作用例的实现，仍可能整体错在官方**另一侧**：D1 即此类，本仓能读官方文件，
> 官方**读不了本仓文件**，而既有互操作用例全是「自家写 → 自家读」，故长期全绿。
>
> **方法论立规（本批次）**：凡跨实现格式的「宽容读 / 回退默认值」分支，必须同时提交**以官方实现产物为 golden**
> 的写侧或读侧对拍用例。理由：宽容读会系统性掩盖写侧错误，本仓已三度复发（P0-4 ChaCha20 IV、D1 时间单位、D9/D4/D15 回退默认值）。

### 38.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 官方依据 |
|---|:--:|---|---|---|
| **P0-05** | P0 | KDBX4 时间写成 .NET **ticks**（官方与规范为**秒**）→ 本仓产物官方客户端不可正常打开 | `KdbxXmlTimeHelper`（`formatDate` 改秒、KDoc 勘误、保留 ticks 读兼容、新增 `ANCIENT_INSTANT`/`ancientTimes`）、`KdbxTimesTest` | `Write.cs:799`、`Read.Streamed.cs:935`、规范 Page History 0.2、KeePassXC `KdbxXmlWriter.cpp:561`、KeePassDX `toDotNetSeconds()` |
| **P0-06** | P0 | Salsa20 内层流 nonce 常量错误 → 受保护字段静默乱码且保存即不可逆覆写 | `InnerRandomStreamCipher`（→ `E8 30 09 4B 97 20 5D 2A`，具名常量 + 三方出处）+ 真值 KAT 11 例 | 规范 §Inner Encryption、`CryptoRandomStream.cs:119-120`、KeePassXC `KeePass2.cpp:35` |
| **P0-07** | P0 | `<DeletedObjects>` 写在 `<Meta>`（官方在 `<Root>`）→ 墓碑双向丢失、删除条目跨客户端复活 | `KdbxXmlSerializer`（Root 内、根 Group 之后）、`KdbxXmlMetaSerializer.serializeDeletedObjects`、`KdbxXmlParser`（Root 层接收 + **兼容 Meta 旧位置**合并去重） | `Write.cs:430`、`Read.Streamed.cs` 的 `KdbxContext.RootDeletedObjects` |
| **P1-16** | P1 | `<Value Ref>` 非数字/缺 Ref/内联 base64 全被折叠为**池索引 0** → 静默交付错误附件；不识别 `Compressed` | `KdbxXmlBinaryNode`（池内命中才用池、否则回退内联、`Compressed` 解压含 128 MiB 防炸弹、内联 `Protected` 解密）+ `INLINE_REF_INDEX = -1` 不变量 | `Read.Streamed.cs:980-1024`、`Write.cs:930-978` |
| **P1-17** | P1 | 数据块 HMAC 失败被判「主密码错误」并**计入解锁节流**（与官方相反） | `HmacBlockStream`/`KdbxCipherKeyResolver`：块与终止块失败 → `KdbxCorruptFileException`；头部 HMAC 失败仍为唯一凭据出口；异常 KDoc 重写 | `Read.cs:150/157`、`HmacBlockStream.cs:233,264` |
| **P1-18** | P1 | 外层头部无总量/字段数上限（**认证之前**，唯一免口令 DoS 面） | `KdbxHeader`：`MAX_HEADER_TOTAL_BYTES = 4 MiB`、`MAX_HEADER_FIELD_COUNT = 64`；三处闸门**先裁决后写入/读取** | 本仓加固（无官方对应物，KDoc 注明） |
| **P1-19** | P1 | 附件**解密后明文**缓存无冷启动清理（唯一「无需口令即可读库内容」路径） | `FileBinaryStore`（收敛为 `purgeAttachmentCache`，失败落脱敏告警）、`MainApplication.onCreate` 冷启动清理 + 保留锁定清理 | 本仓加固 |
| **P1-20** | P1 | 防回滚状态随缓存被**锁库清除** → 云侧在用户锁定一次后即可重放旧库 | 状态迁至 `filesDir/rollback`（`@RollbackStateDir` + DI）、`SyncCache` 删除清单移除 `.rollback` 并加 `isRollbackStateFileName`、`SyncCycleRunner` 惰性解析（避开假 Context NPE） | 本仓加固（威胁建模文档同步补状态生命周期） |
| **P1-21** | P1 | 敏感对话框窗口无 FLAG_SECURE（平台为窗口级属性，Compose 对话框是独立窗口） | 新增 `SecureDialog`/`SecureDialogWindowEffect` + 纯逻辑 `SecureDialogFlagPolicy`；落地 7 处（主密码修改、子库 ×2、修订差异、附件预览、**创建库向导 ×2**） | 官方 assistant 指南 "each window … including dialogs" |
| **P2-28** | P2 | `Protected` 判定过宽（`lowercase()=="true"`）→ 消费非规范产物时密钥流错位 | `KdbxXmlStringNode`/`KdbxXmlBinaryNode` 改精确 `== "True"`；写侧恒写 `"True"` | `Read.Streamed.cs:1066-1068`、`Write.cs:858,949` |
| **P2-29** | P2 | 空 `<Value/>` 使整条 `<String>` 丢失 | `KdbxXmlSaxNodes.TextNode.end()` 恒回调（空元素交付空串） | 官方空元素返回 `string.Empty` |
| **P2-30** | P2 | 布尔/数值语义偏差（`Expires`/`IsExpanded`/`QualityCheck`/`AutoType.Enabled`/`RecycleBinEnabled`/`IconID`/`UsageCount`） | 新增 `KdbxXmlScalarParsers`（精确 bool + 字段级默认；`parseNullableBool` 大小写不敏感；IconID 钳制；UsageCount 饱和）；**Meta 与 AutoType 共 8 处宽松解析一并收敛** | `Read.Streamed.cs:250/256/266/282-291/382/388/441/459/501/503/527/834-851` |
| **P2-31** | P2 | 时间缺省值错误（缺整个 `<Times>` → `now()` 虚假"刚修改"；子元素缺失 → 1970） | 一律 `ANCIENT_INSTANT`（0001-01-01，恒不可能在"越新越胜出"中虚假胜出） | 官方 `DateTime.MinValue` 语义 |
| **P2-32** | P2 | `CustomData` 项时间戳、`CustomIcon` 的 `Name`/时间、`MasterKeyChangeForceOnce` 读写丢失；零 UUID/空 data 图标未按官方丢弃 | `KdbxMetaData`（新增 `customDataTimes` **并行字段**，`customData` 保持 `Map<String,String>` 以免波及 app/sync）、`CustomIcon`、`KdbxXmlMetaReader/Serializer`、`KdbxFile.buildDatabase` 装配补齐 | `Write.cs:461/697-703/808-825`、`Read.Streamed.cs:315/353` |
| **P2-33** | P2 | `MemoryProtection` 读后未重置为默认；写侧未参与标准五字段的 `Protected` 决策 | 读后重置（`officialMemoryProtectionReset`）；写侧 `resolveProtectedFlag`：标准五字段由**库级配置无条件覆盖** per-value，非标准字段保留 per-value（KDoc 贴官方 C# 片段并禁止改回 OR） | `Read.cs:246-248`、`Write.cs:838-854`、`Write.cs:464` |
| **P2-34** | P2 | KDF 参数缺 `M/I/P/V` 静默填默认（官方 fail-closed）；内存下界 1 MiB 严于规范 | `KdbxKdfParameterCodec`：缺参即抛并点名缺键、下界对齐 8192、上界保留防 DoS 并给「规范 vs 本仓」对照表 | `Argon2Kdf.cs:57-58,143-160` |
| **P2-35** | P2 | XML 无元素计数上限 | `KdbxXmlParser.MAX_XML_ELEMENTS = 2_000_000` + 用例 | 本仓加固 |
| **P2-36** | P2 | 受保护值解密后的明文副本未清零 | `KdbxXmlStringNode` 构造后立即 `plainBytes.fill(0)`（先核实 `ProtectedString` 为借用语义 + init 内密封） | 官方 `XorredBuffer` 用后清零 |
| **P2-37** | P2 | 零/缺失 UUID 原样保留 | `KdbxXmlParser.normalizeZeroUuids`（含父引用与 History 同步） | 官方 `PwUuid(true)` 替换 |
| **P2-38** | P2 | base64 内部空白不容忍（官方容忍） | `KdbxXmlValueUtil.decodeBase64LenientWhitespace`（**仅剥空白 + 严格基本解码器**；实测明确否决 `getMimeDecoder()`——它会静默接受非法串并错位 keystream） | `.NET Convert.FromBase64String` 行为 |
| **P2-39** | P2 | `Ref` 附件路径误写 `Protected` | `KdbxXmlEntrySerializer` Ref 分支不写 Protected；池外索引回退内联 | `Write.cs:930-949` |
| **P2-40** | P2 | `isPackageMatch` 剥离任意 scheme → 域名形态包名冒充（`https://github.com` ↔ 包名 `github.com`） | 新增 `DomainMatcher.isAndroidPackageMatch` 并替换 **5 处**放行决策；浏览器 allowlist / DAL / 域匹配路径**一行未改** | 官方包名精确匹配语义 |
| **P2-41** | P2 | `requireRiskNotice` 声明式属性生产零消费 | `RuntimeIntegrityPolicy.requiresRiskNotice` 成为唯一消费点，设置页据此渲染风险卡 | 本仓策略自述 |
| **P3-81** | P3 | 文档纪律与勘误（**本批次内建立并闭环**） | `AGENTS.md` §4 索引补 7 份安全文档 + 立"索引纪律"、§6 附件缓存措辞如实化；`docs/references/KeePass-2.61.1-架构分析.md` 勘误 AES-KDF 出厂常量（6 000 000 → **600 000**，`PwDefs.cs:116`）与块 HMAC 摘要输入（`LE64(i)‖LE32(size)‖C`，纠正"索引不进摘要"的误读）；`docs/同步层记录级完整性威胁建模.md` 补状态生命周期与异常分型 | 直接读官方源码核实 |

### 38.2 验收证据

#### (1) 单测全绿（权威强制重跑）
```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 3s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 111 | 832 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 349 | 0 | 0 | 0 |
| sync | 18 | 195 | 0 | 0 | 13 |
| **合计** | **198** | **1557** | **0** | **0** | **13** |

**基线变动**：1423 → **1557（+134 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
`crypto` 的 Rust 原生内核（`cargoHostBuild`）本轮成功构建。

#### (2) D1 的**外部官方实现端到端对拍**（本批次新增的证据形式，决定性）
探针 `OwnProductInteropProbeTest` 由本仓 writer 产出真实 `.kdbx`（1175 B，
SHA-256 `c23ed3cfd9d68e3af45dc37cb64178c81c4b6eb9db40fc145617fd5555b2c3d8`，AES-KDF 6000 轮，口令 `interop-probe-password-2026`），
并留 `PROBE.md` 记录复现命令。

- **`keepassxc-cli 2.7.12`**（`db-info` / `ls -R`）→ **成功打开**：名称 / 描述 / 加密 AES-256 / KDF / 群组数 1 / 条目数 1 全部正确；
  `数据库创建时间: 2026/9/12 14:01`、`保存时间: 2026/9/12 22:01` —— **时间正常**；
- **`pykeepass 4.2.0`** → 读出条目 `Probe Entry / probe-user / Probe-P@ssw0rd-2026`，
  `ctime = mtime = 2026-09-12 14:01:44+00:00` —— **与 `PROBE.md` 期望值逐秒一致，未抛 `OverflowError`**。

**修复前对照（机理）**：本仓写出的 ticks 值是官方期望秒值的 10⁷ 倍，官方 `new DateTime(lSec * 10^7)` 在 long 回绕后
仅约 1/6 概率落回 `DateTime` 合法区间 ⇒ 多数文件直接打不开、其余得到荒谬日期；pykeepass 则抛 `OverflowError`。

#### (3) F-09 真值 KAT（跨实现，非自洽往返）
- 向量来源：**pycryptodome 3.23.0**（独立于本仓）与 **BouncyCastle 1.85.2**（本仓生产引擎）**双实现逐字节一致**，
  另经 Bernstein/ECRYPT 官方 Salsa20 向量校准工具可信度；
- Salsa20（正确 nonce）前 32 B = `f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d`；
  **错误 nonce** 对照流 = `739a24411659762d97ba9107082efe718ee8f793295f3666b48d72cf62642fd5`（与正确值无任何字节相同）；
- ChaCha20 前 32 B = `8ce8bc610ac05ff2e3dd88b49a1404c2844f148037027476b83d58f5609adf65`；
- KAT 另含「跨调用密钥流必须连续」用例，防「每次调用重置引擎」导致的**密钥流复用**（流密码致命缺陷）。

#### (4) 集成期修出的真实缺陷（如实留痕，含 1 个生产缺陷）
| # | 现象 | 定性 | 处置 |
|---|---|---|---|
| 1 | `KdbxXmlMetaSerializer` 对跨模块属性 smart cast → 编译失败 | 编译期 | 先取局部不可变副本 |
| 2 | 测试 `failure is KdbxInvalidCredentialsException` 恒假 → 编译失败 | 编译期 | 向上转型到共同基类 `IOException` 后再判（语义不变） |
| 3 | **`KdbxXmlEntrySerializer.writeInlineAttachmentValue` 在 `finally` 清零 `att.data`**，而 `KdbxAttachment.data` 对**内存附件返回自身数组（非副本）** ⇒ **写出即销毁调用方的附件字节**（同实例再次保存 / UI 读取全为 0） | **生产缺陷**（由新增用例暴露） | 写侧改为只借用不清零；`KdbxAttachment.data` KDoc 改为**按来源分类声明所有权**（内存=借用、落盘=独立副本），并注明该差异就是缺陷成因 |
| 4 | 「声明长度越界须在读取前拒绝」用例构造错误（每个字段都声明 1 MiB 却零数据 ⇒ 第 1 个字段即 EOF，累计预算无法推进） | 测试缺陷 | 改为「前 3 个字段带真实 1 MiB 数据 + 第 4 个仅声明」使预算恰在**读取前**越界（算术与消息关键字均已核对） |

> 首轮跑测为 **3 例失败**（上述 #3、#4，以及一处 KAT 期望未扣除"前序受保护字段已消耗密钥流偏移"），
> 修正后复跑全绿。**失败过程一并留痕**，避免"一次就绿"的失真叙述。

### 38.3 边界、未覆盖与有意偏离（如实声明）

- **设备侧待验（JVM 无法闭环）**（已登记为 `ISSUE-P2-42`，含逐项复现配方与验收标准）：① 对话框窗口真实带上 `FLAG_SECURE`（建议 `dumpsys window` 或截图实测，
  覆盖本轮 7 处对话框）；② 附件缓存**冷启动清理端到端**（落盘 → force-stop → 冷启动 → 目录应为空）；
  ③ `SecureDialog` 取到 `DialogWindowProvider` 的路径（理论上 `DialogLayout implements DialogWindowProvider` 已由
  compose-ui 字节码核实，仍建议真机确认未静默 fail-safe 空操作）。
- **F-23 未做**经真实 `SyncCoordinator.syncNow()` + 真 Keystore MAC 的端到端用例：单测装配路径固定注入
  `NoopSyncIntegrityMac`（防回滚在单测路径天然禁用），端到端需大改装配脚手架，超出本批次范围；
  已由 `SyncCache` + evictor 两级锁定 + 真实 `DatabaseSession.lock()` 路径覆盖。
- **Salsa20 无真实语料**：本仓无「KDBX4 + `InnerRandomStreamID=2`」的官方产物（KeePass/KeePassXC 的 v4 恒写 ChaCha20，
  v3 被版本门拒绝），故 KAT 以**跨实现真值**替代端到端语料；合成该语料的配方已写在用例 KDoc 内。
- **有意偏离（留痕）**：① **未实施"F-09 拒存保护"**（既有整改文档建议的第一步）——修复常量后读 Salsa20 已正确，
  拒存反而阻断合法迁移；改以 KAT 锁定常量。② KDF 上下界**保留比规范更严**的防 DoS 封顶（KDoc 给对照表），
  仅下界与版本取值集对齐官方。③ 布尔解析**不 trim**（对齐官方裸字符串精确比较）。
- **本批次新增登记、仍未闭环的待办**：`ISSUE-P3-78`（Argon2 `S` 长度未按官方 `MinSalt=8`/`MaxSalt=0x3FFFFFFF` 校验）、
  `ISSUE-P3-79`（Compose Popup 系窗口未接线 `PopupProperties(securePolicy)`）、
  `ISSUE-P3-80`（`KdbxConstants.Xml.COMPRESSED` 等属性常量未上收）。**同类已记录的接受域差异**：
  `HistoryMaxItems` 缺省官方为 `-1`（本仓 10）、官方 `ReadTime` 对非 8 字节 base64 零填充宽容（本仓严格拒绝）。
- **CodeQL 影响评估**：`java-kotlin` **不在** code scanning 语言矩阵内（`.github/workflows/codeql.yml:55-64`，
  文件头 :21-23 说明理由），故新增 Kotlin 硬编码 KAT 向量不会产生 `hard-coded-cryptographic-value` 告警，
  无需改 `.github/codeql/codeql-config.yml`。
- **唯一功能收紧**：`isPackageMatch` 语义收窄后，URL 为「裸包名」（无 scheme）的条目不再按包名命中（fail-closed，已入 KDoc）。

### 38.4 基线同步
- `AGENTS.md` §1 单测基线：**1423 → 1557 例**（0 失败 / 0 错误 / 13 跳过）；
- `AGENTS.md` §4 新增 7 份安全文档索引 + 索引纪律；§6 附件缓存清理改为「冷启动 + 锁定」两层并附如实边界。

---

<a id="s39"></a>
## §39 红队攻击路径批次处置归档（报告退役 + 存量项转登 ACTIVE_ISSUES）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对 `docs/security/REDTEAM_ATTACK_PATHS.md`
（44 条攻击路径，下称"红队批次"）逐条对拍，把**仍成立的开放项**转登 `ACTIVE_ISSUES.md`，
**已撤回 / 已证否 / 已验证 / 风险接受**项在此留痕，随后**退役并删除**该文档。

### 39.1 来源与时间线事实（本批次必须记录，避免复发"同批自相矛盾"）

| 事实 | 证据 |
|---|---|
| 红队文档正文撰写于 `d32f3e7`（§38 批次之前） | 撰写期引用 `DomainMatcher.kt` 为 **168 行**、`AutofillCandidateRanker` 调 `isPackageMatch`（`git show d32f3e7:…` 复核） |
| `ISSUE-P2-40`（新增 `isAndroidPackageMatch`）于 `9b64415` 落地 | `git log -S "isAndroidPackageMatch"` → **仅** `9b64415` |
| 红队文档被 `9b64415` **一并提交**（`+1357` 行） | `git show --stat 9b64415` |
| `ISSUE-P2-40` **从未登记**于 `ACTIVE_ISSUES.md` | `d32f3e7` 与对拍时 HEAD **均**无 `P2-40`；仅本文件 §38 有记录 |

**结论（双向断链，二者缺一不会出现"同 commit 内报告指控、修复生效"）**：
① **报告方缺状态列**、定稿前未重新对拍（已在本批次以 §39.8 纪律收口）；
② **修复方缺认领单据**——`P2-40` 绕过 `ACTIVE_ISSUES.md` 的认领 / 登记环节直接落本文件，违反
`AGENTS.md` §3.6 第 1 步。**故 `AP-01` 在撰写基线是真实且未被记录的缺陷，其"机制已闭环"属同批修复，非发现无效。**

### 39.2 已撤回（内容作废，仅留编号占位防重复发现）

| 条目 | 硬伤（与时间线无关） | 证据 |
|---|---|---|
| `AP-02` webDomain 冒领（未验证分支） | 所依赖的 `UNVERIFIED` 枚举值**从未存在**；`attribute()` 末尾恒 `REJECTED`，调用侧映射为 `null`（本就 fail-closed）。**由 grep 片段推断控制流**所致 | `WebDomainAttribution` 三值枚举；`AutofillWebDomainPolicy.attribute` 末尾 `return REJECTED` |
| `AP-05` 字段屏蔽表越权写入 | base intent **已显式** `putExtra(EXTRA_CALLING_PACKAGE, callingPkg)`；叠加 `Intent.fillIn` 的"base 覆盖 fillIn"语义 → 注入该键**必然失败**；且该值源于系统背书 callingPkg。原文自身已承认 fillIn 语义却仍宣称可注入，**自相矛盾** | `AutofillDatasetBuilders.buildPickerDataset`（含包名 extra）；`AutofillPickerActivity.blockFieldAndFinish` |

### 39.3 已证否 / 降级

| 条目 | 处置 | 依据 |
|---|---|---|
| `AP-24` 内层 XML DTD 降级 → XXE | **XXE 路径证否**；降级为"补设备侧 DTD 回归用例" → 转登 **ISSUE-P3-82** | `buildHardenedParser` 的**特性探针**撰写期即已存在；`parser.parse(inputStream, handler)` 按 API 契约把 `DefaultHandler2` 同时注册为 `EntityResolver` → `resolveEntity` **必然**被调用并抛异常 |
| `AP-04` `FLAG_MUTABLE` 注入 / 重放 | **前置不成立**：`FillResponse`/`Dataset` 回传**系统**渲染，客户端不经手 `IntentSender`。扣除后仅剩重复拉起型 DoS → 并入 **ISSUE-P3-85** | 内联建议路径确会向客户端给出 `PendingIntent`，但为 `FLAG_IMMUTABLE` 且指向 `MainActivity`（`AutofillInlinePresentationFactory`），**无注入面** |

### 39.4 已修正（结论保留、论证纠偏）

| 条目 | 修正 | 处置去向 |
|---|---|---|
| `AP-01` 包名冒领 | 命名空间混同路径**已由 `P2-40` 闭环**（填充链路改 `isAndroidPackageMatch`，只认 `android://`）；**残余项**（`android://` 条目无调用方签名指纹绑定）转登 | **ISSUE-P2-46** |
| `AP-14` 节流 | 绕过**不需要**重算 MAC：删掉三个 prefs 键即命中 `read()` 的"**全新安装**"分支被判完整（原文选了更难的路径，说明未读读取侧） | **ISSUE-P2-45** |
| `AP-22` TOTP 通知 | 通知默认**已关闭**（原建议即现状）；**真正漏掉的是** `autofillCopyTotp` 默认 **true** —— 动态码默认入剪贴板 | **ISSUE-P2-43** |
| `AP-44` 缓存残留 | 冷启动清理**早已实现**（`MainApplication.onCreate` 起始段 `fileBinaryStore.clear()`），且 `F-13 / ISSUE-P1-19` 残余窗口已如实声明；原文"待确认"系未执行的检查 | force-stop 路径复测**并入既有 ISSUE-P2-42**，不另立条目 |

### 39.5 已验证通过（本批次首个"全绿"项）

- `AP-43` 备份 / 设备迁移提取：`allowBackup=false` + `data_extraction_rules` 全域排除
  （cloud-backup & device-transfer）——**实测应记为通过**，无需整改。

### 39.6 风险接受 / 产品裁决（记录出处，不作价值否定）

| 项 | 性质与出处 | 保留的技术面 |
|---|---|---|
| `AP-20` / `AP-42` FLAG_SECURE 可关闭 | **显式产品裁决**（`FlagSecurePolicy` KDoc 载明"2026-09-12 用户裁决语义修订"：锁定态强制、解锁态随开关真实解除） | 关闭期间 `MediaProjection` / 截屏不受阻 |
| `AP-19` 的 `installer==null` 不升级风险 | **有意取舍**（`RuntimeIntegrityDetector.detectUntrustedInstallSource` 注释："避免误报"） | 重打包 APK 在无 root 痕迹时被判 `TRUSTED`（**该后果本身转登 ISSUE-P1-23 处置**） |
| `AP-33` / `B15` 同步凭据封印 `requireUserAuth=false` | **有意决策**（后台同步需锁屏可用，`SyncCredentialSealer` 注释） | 锁屏态可解封云凭据；可选降收益方向＝OAuth2 刷新令牌 + 设备私钥（**未立案**） |
| `AP-08/09/12/16/17/31` 同 UID / root 截获 | **设计边界**（源码自认 + `AGENTS.md` §6） | 见 39.7；仅 `TracerPid` 一项作为"提高成本"转登 **ISSUE-P3-83** |

### 39.7 设计边界合并计价（`AP-R1` / `AP-R2`）

原报告把同根因拆成多条独立高危（`AP-08`、`AP-09`、`AP-16`、`AP-17`）并按乘积排序。
本批次**合并为一次计价**：

- `AP-R1` ＝ `AP-08`（Hook 解封点）＋ `AP-09`（会话缓存克隆点）：**同 UID / root 可截获主密码**；
- `AP-R2` ＝ `AP-16`（内存扫描）＋ `AP-17`（`ptrace` / `proc/mem`）：**同 UID / root 可截获全库明文**。

**处置口径**：**接受根因，不追"承诺阻断"**，转向"降低一次成功的收益"。可选的降收益方向
（主密码不常驻会话 / 硬件内派生临时密钥 / 附件加密落盘 / 同步凭据改 OAuth-STS）
**本批次不立案**——属可选项而非缺陷，须待专项排期时再评估。

### 39.8 对拍中被反驳但经复核**不予接受**的两点

1. **`B10`（候选准入结构）成立且不撤**：`AutofillCandidateRanker.scoreEntry` 单独 `packageMatch`
   即计分、`if (score <= 0) return null` 表明"命中其一即产出候选"。
   `android://` 约束收窄的是"**什么算包名命中**"，未改变"**其一即可**"的结构。
   原文之误在 `AP-01` 的**具体 URL 形态**，不在 `B10` 本身——两者曾被合并反批评，特此拆开。
2. **`B13`（JNI 签名与旧 C 桥一致）有其证据链，不撤**：`crypto/src/main/rust/src/jni_bridge.rs`
   L3-5 模块文档明载"符号名与签名逐字一致"，且 L151-170 有**编译期**类型断言
   （`exported_symbol_has_c_parity_signature`，把导出函数赋给显式 typed `extern "system" fn` 指针）。
   原文缺失的是**行号**，非依据。
   **附带精确修正（本批次新增）**：其对照物 `keepasskey_argon2_jni.c` **已不在仓库内**
   （全仓检索 0 命中，仅 `lib.rs:7` / `jni_bridge.rs:3,25` 以文字提及），故该编译期断言自证的是
   "Rust 导出 = **本仓手写的期望签名**"，**不自证**"= 已移除的 C 桥"。引用该项时应采用
   "文档断言（对照物已移除）"口径，或由 KDoc 注明 C 桥已删除。

### 39.9 本批次产出（转登 `ACTIVE_ISSUES.md` 的开放项）

| 新编号 | 主题 | 地图来源 |
|---|---|---|
| `ISSUE-P1-22` | 软件级 Keystore 下快速解锁封印未 fail-closed | `AP-07` |
| `ISSUE-P1-23` | 篡改 / 重打包 APK 无检测；`installer==null` 判为无风险 | `AP-19` / `AP-39` |
| `ISSUE-P1-24` | 自动填充确认页可伪造归属信息，且无"首次绑定"显式授权 | `AP-03` |
| `ISSUE-P2-43` | `autofillCopyTotp` 默认开启 —— TOTP 动态码默认入剪贴板 | `AP-22′` |
| `ISSUE-P2-44` | 无障碍服务信号未纳入运行完整性体系 | `AP-23` |
| `ISSUE-P2-45` | 解锁失败节流默认关闭，且记录可被"删键复位" | `AP-14` |
| `ISSUE-P2-46` | `android://` 包名绑定条目缺调用方签名指纹绑定 | `AP-01` 残余 |
| `ISSUE-P2-47` | 同步与封印凭据的回滚防护不足 | `AP-31` / `AP-13` |
| `ISSUE-P3-82` | 内层 XML DTD 拦截缺设备侧回归用例 | `AP-24` 残余 |
| `ISSUE-P3-83` | `TracerPid` / 内存取证门控缺失 | `AP-17` |
| `ISSUE-P3-84` | 剪贴板"可关闭擦除 / 延时窗口"的风险明示 | `AP-21` |
| `ISSUE-P3-85` | 自动填充 / 组件面低危硬化（3 小项） | `AP-04` 残余 / `AP-40` / `AP-41` |

**未转登（明确接受的残余）**：`AP-06/10/11/12/18/25–30/32/34–38`
（单条收益有限、需组合，或属设计边界），维持"不作独立缺陷"的判定；`AP-12` 属源码自认边界。

### 39.10 方法纪律（本批次确立，供后续审计 / 攻防文档继承）

1. **必须带状态列**：每条须标注 `已验证 / 已被同批修复 / 待实测 / 已撤回`，**定稿前重新对拍 HEAD**。
2. **不得由 grep 片段推断控制流**（`AP-02` / `AP-24` 两条硬伤同源）：**必须读到函数结尾与其调用点**。
3. **不得把 `待实测` 前提计入 `现实×易×影响` 乘积**。
4. **同一根因只计一次**：观测点数量 ≠ 独立风险数量。
5. **区分"缺陷"与"产品裁决 / 风险接受"**：后者须引用裁决出处，不作价值否定。
6. **引用核对须在文档声明的基线（commit）上进行，而非 HEAD**：原文行号对 `d32f3e7` 是精确的，
   在 HEAD 上"看似漂移"仅因同批改动扩大了文件——**该现象本身不构成对引用方的反驳理由**。
7. **审计输入文档的处置结论必须落本归档库**：报告退役前，其"撤回 / 证否 / 风险接受"结论不得随文件删除而丢失。

### 39.11 报告退役与索引同步

- 删除 `docs/security/REDTEAM_ATTACK_PATHS.md`（含其未提交的 §0.2 修订稿）；
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律）；
- 本批次所有"现存问题"已转登 `ACTIVE_ISSUES.md`（12 项，见 39.9），历史与已排除项以本节为单一真相源。

---

<a id="s40"></a>
## §40 外部安全审计报告退役批次（报告退役 + 存量项转登 ACTIVE_ISSUES）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对 `docs/SECURITY_AUDIT_2026-09.md`
（第三方安全审计，自称 29 项已复核发现）逐条对拍，把**仍成立的开放项**转登 `ACTIVE_ISSUES.md`，
**已整改 / 误报 / 待复核 / 无法确认 / 已确认强项**在此留痕，随后**退役并删除**该报告。

### 40.1 来源与时间线事实（本批次必须记录）

| 事实 | 证据 |
|---|---|
| 报告审计基线 = `d32f3e7`（2026-09-12 19:38） | 报告表头「审计基线（快照）」 |
| 报告、整改方案与威胁模型**随 `9b64415` 一并提交** | `git log --oneline -- docs/SECURITY_AUDIT_2026-09.md` → 仅 `9b64415` |
| `9b64415`（§38）**并非对报告方的响应** | §38 自述缘起为「本仓实现 / 官方 KeePass 2.61.1 C# / KDBX 4.1 规范 / Android 安全模型」四方对比；两路径独立命中同一批缺陷（如报告 `F-09` ↔ `ISSUE-P0-06`） |
| 本批次处置时 HEAD = `a669a48`（2026-09-13） | `git log --oneline -1` |

### 40.2 已整改（报告结论对当前代码不成立）

| 报告条目 | 对应整改 | 当前代码事实 |
|---|---|---|
| `F-09` Salsa20 nonce 错误 | `ISSUE-P0-06`（§38） | `InnerRandomStreamCipher.kt:131-134` 已为 `E8 30 09 4B 97 20 5D 2A`，附规范 / 官方 C# / KeePassXC 三方出处 + KAT |
| `F-11` 外层头部无总量上限 | `ISSUE-P1-18`（§38） | 双闸门 `MAX_HEADER_TOTAL_BYTES = 4 MiB` / `MAX_HEADER_FIELD_COUNT = 64`，先裁决后读写 |
| `F-13` 附件明文无冷启动清理 | `ISSUE-P1-19`（§38） | `MainApplication.onCreate` 起始段 `fileBinaryStore.clear()` |
| `F-15` 受保护值明文未清零 | `ISSUE-P2-36`（§38） | `KdbxXmlStringNode.kt:73-75` `finally { Arrays.fill(plainBytes, 0) }` |
| `F-23` 防回滚状态随锁库清除 | `ISSUE-P1-20`（§38） | 状态迁至 `filesDir/rollback`，`SyncCache` 删除清单移除 `.rollback` 并加文件名守卫 |
| `F-25` 解锁失败日志含库 id / 密钥文件长度 | 存量批次 | `UnlockViewModel.kt:299-303` 已收敛为 `errType=…, invalidCreds=…` |

**结论**：报告是**修复前快照**；其中至少 6 项完整发现在其基线之后数小时内即被同批修复，
报告未设状态列亦未对拍 HEAD，故**不得直接引用其计数或结论**（见 40.8 纪律 1）。

### 40.3 定性更正与降级（本批次复核结论）

| 项 | 更正 |
|---|---|
| **计数内部矛盾** | 误报数曾同时写「9」与「6」；严重度分布曾算作 30；「合计 22 / 29 项 / 总表 32 行」并存（实测总表 **31 行**：`F-01…F-25` 共 25 + `RUST-01…RUST-06` 共 6）；分类分布称 `Confirmed Vulnerability 9` 却仅列 8 个 ID，且 `F-07` 未落入任何分类桶；另设「INFO 类」桶属**严重度误用为分类**。→ 本批次以 `ACTIVE_ISSUES` 的转登清单为**唯一权威口径** |
| `F-05` | CVSS 7.3 / HIGH 偏高：需仓库写权限（已高度受信主体），且不直接造成运行时泄露，`VI:H` 未充分论证 → 转登时按 **P2** |
| `F-10` | HIGH → **P2**。元素计数上限（`MAX_XML_ELEMENTS`）**不构成有效缓解**（不约束同一池条目被引用 N 次的副本乘法），但该缓解须在条目内如实披露 |
| `F-12` | 报告自述「把有界收紧为预算」，本质为**加固建议** → 按 P2 转登，不再计为独立漏洞 |
| `F-04` / `F-19` / `F-08` / `F-16` / `F-17` / `F-07` / `F-20` | 无攻击者 / 不可达 / 文档卫生 → **移出「漏洞」口径**，按 P3 转登（`F-04`、`F-19` 方向为 fail-closed，无机密性影响） |

### 40.4 与既有条目的重合（不重复登记）

- **`F-01`（解锁节流默认关闭）** → 已由 §39 转登的 **`ISSUE-P2-45`** 覆盖；且 `P2-45` 额外发现
  「删除三个 prefs 键即命中『全新安装』分支被判完整」的**复位旁路**，强于报告结论。
- `F-18` 的「风险明示」面与 **`ISSUE-P3-84`** 重合，转登时交叉引用。
- `F-19` 是 **`ISSUE-P2-46`**（`android://` 签名指纹绑定）的**前置条件**。

### 40.5 本批次产出（转登 `ACTIVE_ISSUES.md` 的开放项，25 项）

| 新编号 | 主题 | 报告来源 |
|---|---|---|
| `ISSUE-P2-48` | 附件引用放大无累计预算 | `F-10` |
| `ISSUE-P2-49` | KDF 无工作量 / 墙钟预算 | `F-12` |
| `ISSUE-P2-50` | DAL 响应体先物化后检查 | `F-14` |
| `ISSUE-P2-51` | 剪贴板不随锁定 / 熄屏清理 + 误清他处内容 | `F-18` |
| `ISSUE-P2-52` | 自动填充选择器锁定后崩溃 | `F-22` |
| `ISSUE-P2-53` | CM 通道不查询 `RuntimeIntegrityGate` | `F-24` |
| `ISSUE-P2-54` | 依赖 CVSS 闸门未接入自动触发路径 | `F-05` |
| `ISSUE-P2-55` | 发布签名口令 = 公开示例值 | `F-06` |
| `ISSUE-P2-56` | Argon2 工作内存释放前未擦除 | `RUST-01` |
| `ISSUE-P2-57` | 派生密钥栈副本残留（`sha2` 未启 `zeroize`） | `RUST-02` |
| `ISSUE-P2-58` | 口令强度评估 Θ(n²) → 主线程 ANR | `RUST-03` |
| `ISSUE-P2-59` | 原生 Argon2 路径缺内存上界预检 | `RUST-05` |
| `ISSUE-P2-60` | KDF secret `K` 常驻且无清零点 | `RUST-06` |
| `ISSUE-P3-86` | 明文导出缓冲未清零 | `F-02` |
| `ISSUE-P3-87` | 明文导出确认仅在 UI 层 | `F-03` |
| `ISSUE-P3-88` | Chrome 指纹首条 65 hex 永不匹配 | `F-04` |
| `ISSUE-P3-89` | 审计摘要实际仅 32 位 | `F-07` |
| `ISSUE-P3-90` | 公开死函数 `parseOtpAuthUri` 缺参数钳制 | `F-08` |
| `ISSUE-P3-91` | `HmacBlockStream.readAll` 非常时比较 | `F-16` |
| `ISSUE-P3-92` | UI 误标「ChaCha20-Poly1305」 | `F-17` |
| `ISSUE-P3-93` | 调用方证书仅取首个签名者 | `F-19` |
| `ISSUE-P3-94` | 合并清单冗余 / 废弃权限 | `F-20` |
| `ISSUE-P3-95` | 填充确认不校验会话锁定 | `F-21` |
| `ISSUE-P3-96` | Kotlin CBC 加密流明文中转副本未清零 | `RUST-07` |
| `ISSUE-P3-97` | CI 不跑 JNI 边界测试 / 符号表核对 | `RUST-09` |

### 40.6 未转登（明确不作待办，随报告退役）

1. **误报排除 9 项**：`FP-01`/`FP-02`（`parseOtpAuthUri` 除零可达性）、`FP-03`（`calculateHotp` 越界）、
   `FP-04`（`parseOtpAuthUri` 无调用点）、`FP-05`（内层流「每值重置」——规范要求**不**重置，实现正确）、
   `FP-06`（生产 Hilt 未注入真实依赖——已由 release 生成组件证明为测试专用）；
   `RUST` 侧 3 项（负值经 `as u32` 绕过闸门、panic 跨界 UB、Twofish JNI 原地修改 IV）。
   另 **CWE-22 路径穿越**经查证不成立（附件缓存 key 为随机 UUID）。
2. **§10 待复核区**（`IPC-01`…`IPC-11`、`SUPPLY-01`…`SUPPLY-08`）：报告自述**未经首席审计员逐条复核**，
   不计入发现，故不转登。
3. **§11 无法确认**（`C-1`…`C-10`）：条目内容是「解除所需材料」而非缺陷。
4. **已确认强项**（报告 §1.3 / §9B.2）：非缺陷，作为复核证据留痕。

> 上述四类由 `SECURITY_AUDIT_REMEDIATION.md`（已退役，见 §43）**附录 A–F** 承接，
> 该文档自此成为**该轮审计的唯一留存记录**；完整原文见 `git show 9b64415:docs/SECURITY_AUDIT_2026-09.md`。
>
> **（2026-09-13 §43 补注，不改写上文）**：该留存文档**亦已退役删除**，其附录 A–F 与产品决策
> 已**完整留存于本库 §43.3 ~ §43.9**；`ACTIVE_ISSUES.md` 内指向该文档的指针已改指 §43。

### 40.7 报告退役与索引同步

- 删除 `docs/SECURITY_AUDIT_2026-09.md`（git 跟踪文件，完整原文保留于 `9b64415`，可 `git show` 取回）；
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律），
  `SECURITY_AUDIT_REMEDIATION.md` 行改为「该轮审计唯一留存记录」；
- `AGENTS.md` §4 索引纪律的「反例代价」表述更新为：该报告已退役、其存量项已转登 `ACTIVE_ISSUES.md`；
- `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md` 内对其 `文件:行号` 的引用改指向本归档与 `ACTIVE_ISSUES.md`；
- `SECURITY_AUDIT_REMEDIATION.md` 头注改写并追加附录 A–F。

### 40.8 方法纪律（继承 §39.10，本批次新增三条）

1. **引用审计结论前必须对拍 HEAD**：本报告 6 项完整发现在其基线之后数小时内即被同批修复，
   而报告无状态列 → 直接引用会产生错误结论（本批次「时点错误」为独立复现的实例）。
2. **计数必须可机械导出**：分布表不得手工维护；须先冻结「N 项 = 哪些 ID」的成员清单，再机械导出；
   且**分类（7 类）与严重度（4 档）严禁混用**（本报告曾出现「INFO 类」桶）。
3. **无攻击者的确定性缺陷不属「漏洞」分类体系**：应另立「Correctness Defect / 代码卫生」口径，
   否则清单虚高（`F-04`/`F-19`/`F-08`/`F-16` 等即此类）。
4. **退役前必须完成内容分流**：报告删除之前，其「仍成立 / 已整改 / 误报 / 待复核 / 无法确认 / 强项」
   六类结论必须全部落到 `ACTIVE_ISSUES.md`、本归档库或留存记录文档，**不得随文件删除而丢失**（延续 §39.10 第 7 条）。

---

<a id="s41"></a>
## §41 敏感数据流审计（`SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`）退役与分流

### 41.1 报告与基线

- **报告**：`docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`（AI 执行的**只读静态**数据流审计，成文日期 2026-09-13）。
- **审计基线**：`d32f3e7`（2026-09-12 19:38:31）。**阅读发生在 `9b64415`（2026-09-12 22:22:32）合入之前**。
- **报告自身最严重缺陷**：未在开头锚定 `git rev-parse HEAD`，导致基线漂移后全部 `文件:行号` 与代码引文
  对 HEAD 失效 —— 这直接引发了本批次的两轮复核。

### 41.2 两轮复核与结论

**第一轮（外部复核，2026-09-13）**：抽查报告标注 `[V]`（自称"亲自复读源码确认"）的高优先级条目，
对 HEAD 逐条比对，一度判定 H3 / 前提二 / H1 证据链为错误。

**第二轮（基线回溯，同日）**：`git log -p` 逐行回溯证明 —— 上述三条**在基线 `d32f3e7` 上全部为真**，
其引文与行号**精确**（`KdbxXmlStringNode.kt` 基线共 57 行、`:30` 为 `?.lowercase() == "true"`、
`:50-51` 为无 `finally` 的 `plainBytes`；`KdbxXmlEntrySerializer.kt` 基线共 135 行、`:99` 为
`if (value.isProtected)`、`:113` 为 `writer.text(value.readString())`）。`9b64415` 已分别以
「缺陷 D4」「缺陷 D24」整改并改写这些行。

> **结论**：第一轮判定为**归因错误**（"引文虚构"），实为**基线漂移**；第二轮已更正。

### 41.3 撤回 / 下调（报告自身的错误）

| 报告结论 | 判定 | 依据 |
|---|---|---|
| **H-new-3**：`requireRiskNotice` 无消费者 → 用户得不到可见警告 | ❌ **撤回** | 基线与 HEAD 的 `SecuritySettingsScreen.kt` **均已**渲染 `IntegrityRiskCard`；根因是子审计 grep 只查 `requireRiskNotice`、漏 `requiresRiskNotice` |
| **S1**：持有仓库者可伪造升级包（严重） | ❌ **定级下调为中** | 同一段内自认 `release.jks` 未入库；签名 = 口令 + 私钥库两件套，仅凭口令无法重建 |
| **H1 附注**：`VaultEntryMapperTotpTest` 给出"虚假安全感" | ❌ **撤回** | 该用例测**读取**路径兼容性，用受保护夹具必要 |
| **H2 寿命定级**：Lifetime Map 标"无上界 ★★★" | ⚠️ **修正** | `extractKey` 的 String 为函数局部量，驻留上界为**下次 GC**；副本数论断不变 |
| **H-new-2 表述**："硬编码 false / 唯一门控输入" | ⚠️ **补上下文** | 紧邻 ISSUE-P3-53 注释，是有意的分层取舍；技术结论不变 |
| **§8.1**："A–H 八个面全部闭合" | ⚠️ **过度声明** | 同章 §8.2 又列 14 项未闭合 |

### 41.4 本轮复核新增发现（报告自身的误差）

1. **`L12`（口令长度进日志）在 HEAD 已不存在**：`UnlockViewModel.kt:191` 文案为 `input updated`，不含长度 → **不转登**。
2. **`E4` 仅 Assertion 侧成立**：`PasskeyCreateActivity.kt:296` 已优先取 `providerReq.callingAppInfo.packageName`，
   报告"未被用于该字段"对 Create 侧为**误报** → 转登时已收窄（`ISSUE-P2-72`）。
3. **报告遗漏的 `otp` 读写归属不对称**：写入进 `entry.fields`、读取落 `entry.customFields`（有回退兜底，
   非安全缺陷）—— 报告自述"十类 Secret 逐项追踪已闭合"因此打折（报告 §10.8 已自认）。

### 41.5 转登 `ACTIVE_ISSUES.md` 的开放项（**33 项**）

| 新编号 | 主题 | 报告来源 |
|---|---|---|
| `ISSUE-P1-25` | `copyUsername` 把 `{REF:P@…}` 口令写进剪贴板且不标敏感 | `H4` |
| `ISSUE-P2-61` | TOTP 种子写入恒 `isProtected = false` | `H1` |
| `ISSUE-P2-62` | `KdbxKeyFile.extractKey` 整文件转 String | `H2` |
| `ISSUE-P2-63` | 生物识别门控只用冷启动快照的 hook 信号 | `H-new-2` |
| `ISSUE-P2-64` | 库级 MemoryProtection 不影响内存密封 | `M1` |
| `ISSUE-P2-65` | 明文 StateFlow 未注册 `SessionLockObserver` | `M3` |
| `ISSUE-P2-66` | 落盘清理 unlink-only + `clearAll()` 不清 `.tmp` | `M4` |
| `ISSUE-P2-67` | 同步下载路径绕过附件落盘 | `L9` |
| `ISSUE-P2-68` | `data class` 默认 `toString()` 打印明文 | `M6` |
| `ISSUE-P2-69` | 日志脱敏测试正则漏 `DebugLogBuffer` 通道 | `M7` |
| `ISSUE-P2-70` | 手动选择器不显示请求方身份 | `E1` |
| `ISSUE-P2-71` | IME 内联建议默认把候选名送入输入法 | `E2` |
| `ISSUE-P2-72` | `clientDataJSON.androidPackageName` 归属（Assertion 侧） | `E4`（收窄） |
| `ISSUE-P2-73` | 自动填充认证流协议漂移（裸 `setResult` / `FLAG_IMMUTABLE`） | `E5` |
| `ISSUE-P2-74` | 包可见性可能使浏览器域自动填充失效 | `E6` |
| `ISSUE-P3-98` | `proguard-rules.pro` 的 `AppLog` 剥离规则为 no-op | `L1` |
| `ISSUE-P3-99` | `changeCredentials` 默认参 clone 未清零 | `L2` |
| `ISSUE-P3-100` | `resolveRemotePath` 解密凭据未清零 | `L3` |
| `ISSUE-P3-101` | `S3RequestSigner` 漏擦 `combined` | `L4` |
| `ISSUE-P3-102` | 全量 SHA-1 作为 String 驻留 | `L5` |
| `ISSUE-P3-103` | `SecureCaptureActivity` 缺遮挡触摸过滤 | `L6` |
| `ISSUE-P3-104` | `KdbxAttachment.data` 与 KDoc 矛盾 | `L7` |
| `ISSUE-P3-105` | `getAttachmentData` 双重拷贝 | `L8` |
| `ISSUE-P3-106` | `data_extraction_rules` 未排除 `external` / `device_*` | `L10` |
| `ISSUE-P3-107` | `.kdbx.bak` 默认保留（上一口令加密） | `L13` |
| `ISSUE-P3-108` | `AtomicFileWriter` 的 `.tmp` 残留窗口 | `L14` |
| `ISSUE-P3-109` | `AutofillLastFilledStore.clear()` 零调用方 | `L15` |
| `ISSUE-P3-110` | 明文导出确认仅在 UI 层（XML / CSV） | `L16` |
| `ISSUE-P3-111` | Fill / Assertion 不检索 provider 请求 | `L17` |
| `ISSUE-P3-112` | `SafDocumentCleanup` 无条件删除 | `L18` |
| `ISSUE-P3-113` | 字段黑名单签名失败即 fail-closed 静默禁用 | `L19` |
| `ISSUE-P3-114` | 剪贴板文案承诺与实现漂移 | `L20` |
| `ISSUE-P3-115` | Gradle Wrapper 分发源为第三方镜像 | `L21` |

### 41.6 未转登（随报告退役）

1. **已撤回 / 误报（3 项）**：`H-new-3`、`H1 附注`、`S1`（下调为中，实质面已由既有 **`ISSUE-P2-55`** 覆盖）。
2. **非缺陷 / 已不存在（4 项）**：`M8`（`flagSecureEnabled` 为产品裁决的有意设计，§29.3 / §38）、
   `L11`（hex `String` 是交付物，格式边界，设计接受）、`L12`（HEAD 已不存在）、`L22`（`_gitobj/` 为空目录，
   git 本不跟踪空目录）。
3. **与既有条目重合（6 项，不重复登记）**：`H-new-1` → `ISSUE-P2-53`；`S1` → `ISSUE-P2-55`；
   `E3` → `ISSUE-P2-43`；`M9` → `ISSUE-P2-51` + `ISSUE-P3-84`；`M5` → `ISSUE-P3-86`；
   `M2` → 输入法通道由 `ISSUE-P3-76` 覆盖，其余（Compose `String` 不可擦）为已接受残余风险（`AGENTS.md` §6）。
4. **已核实为 Info / 非问题（报告 §6.3 尾）**：`CredentialPendingIntents` 的 `FLAG_MUTABLE` 有文档依据；
   已跟踪测试密钥材料为一次性夹具；`keystore.properties` / `release.jks` / `local.properties` 从未被 git 跟踪；
   无 WebView 残留；`network_security_config` 与 `src/main` 的 `http://` 字面量均为 XXE 加固特性；
   `AutoLockManager` 的 `ACTION_SCREEN_OFF` 已带 `RECEIVER_NOT_EXPORTED`；完整性判定非 honor-system。

### 41.7 报告退役与索引同步

- **删除 `docs/SENSITIVE_DATA_FLOW_AUDIT_2026-09.md`**。
- ⚠️ **不可恢复声明**：该文档为**未跟踪文件**（`git status` 显示 `Untracked`），删除后
  **无法**经 `git show` / `git log` 取回 —— **不同于** §40.7 的 `SECURITY_AUDIT_2026-09.md`
  （后者为跟踪文件，原文保留于 `9b64415`）。其结论已按 §41.5 / §41.6 全部分流。
- `AGENTS.md` §4 文档索引**移除**该行，并更新「退役纪律」注（§41 立规）。
- `SECURITY_AUDIT_REMEDIATION.md` 头部对本文档的指针改写为「已退役删除 + 转登去向」。

### 41.8 方法纪律（继承 §40.8，本批次新增四条）

1. **审计报告必须在开头锚定基线**：记录 `git rev-parse HEAD` + `git status` 快照，并把全部
   `文件:行号` 明确声明为「核实时刻快照」。本报告缺失该字段，是其**不可直接引用**的唯一根因。
2. **`[A]` 标注的结论不得未经独立复现即提升进最高优先级清单**：`H-new-3` 即此例（标 `[A]` 却进 §1.3）。
   标注体系未失效，失效的是**采信方式**。
3. **区分「不可擦除」与「驻留上界」两条正交轴**：String 一律"不可确定性擦除"，但驻留时长须分别判定
   （函数局部量 = 下次 GC；Compose / prefs 内存映射引用 = 进程生命），不得合并为单一 ★ 等级。
4. **未跟踪文档不得作为唯一证据载体**：审计报告须在**开始阅读时即纳入 git 跟踪**，否则退役 = 永久丢失。

---

<a id="s42"></a>
## §42 威胁建模与架构评估报告退役批次（`THREAT-MODEL-AUDIT-d32f3e7.md` 退役 + 存量项转登）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。对第三方「威胁建模与架构评估（只读阶段）」报告逐条对拍：
**仍成立且此前未登记**的开放项转登 `ACTIVE_ISSUES.md`（14 项，见 42.3）；已登记项给出映射（42.2）；
已复核为「有意设计 / 已声明限界 / 前提不成立」者留痕（42.4 / 42.5）；报告的**分析结论**
（信任边界、对手模型、信任假设、条件化生存性、开放问题与威胁清单）在本节**留存**（42.6）；
随后**退役并删除**该报告。

### 42.1 来源与时间线事实

| 事实 | 证据 |
|---|---|
| 报告基线 = `d32f3e7`（2026-09-12 19:38） | 报告表头「目标版本」 |
| 报告为**只读**阶段产物，自述「未修改任何文件」；证据分级 (a) 代码核实 / (b) 推断 / (c) 无法确认 | 报告头注 |
| 报告随 `9b64415`（§38 批次）与主审计报告一并提交 | `git log --oneline -- docs/THREAT-MODEL-AUDIT-d32f3e7.md` → 仅 `9b64415` |
| 本批次处置时 HEAD = `a669a48`（2026-09-13） | `git log --oneline -1` |
| 报告是**跟踪文件**，退役后可经 `git show 9b64415:docs/THREAT-MODEL-AUDIT-d32f3e7.md` 取回原文（583 行） | `git ls-files` + `git show … \| wc -l` |

### 42.2 已登记（覆盖）项映射（不重复登记）

| 报告条目 | 落点 / 状态 |
|---|---|
| `Q-1`（CM 通道不消费 `RuntimeIntegrityGate`） | `ISSUE-P2-53`（§40 转登） |
| `Q-4`（受保护明文副本未清零） | §38 `ISSUE-P2-36`（已整改） |
| `Q-5`（密钥文件物化 `String`） | `ISSUE-P2-62`（§41 转登） |
| `Q-6`（API 36 SAX 加固实际生效集合） | `ISSUE-P3-82`（设备侧回归缺口） |
| `Q-7`（`copyUsername` 经 `{REF:P@…}` 泄露口令） | `ISSUE-P1-25`（§41 转登） |
| `Q-9`（`.bak` 代际与生命周期） | `ISSUE-P3-107`（§41 转登） |
| `Q-12`（`AppLog.i` 无闸门 / 日志脱敏测试覆盖） | `ISSUE-P2-69`（§41 转登） |
| `Q-13` / `T-8`（防回滚状态随锁库清除） | §38 `ISSUE-P1-20`（已整改：状态迁 `filesDir/rollback`） |
| `Q-17` 前半（`resolveRemotePath` 解密凭据不清零） | `ISSUE-P3-100`（§41 转登） |
| `T-3`（恶意 IME） | `ISSUE-P3-76`（框架阻塞，保留跟踪 + 解除条件） |
| `T-4`（同意保真度：确认页不指名请求方 / 选择器全库搜索） | `ISSUE-P1-24` + `ISSUE-P2-70` |
| `T-7`（恶意 KDBX 造成有界 DoS） | RESOLVED_LOG §22.2 子项 5（「纵深防御缺口当前不可达」） |
| `T-11`（`copyUsername` 口令入剪贴板） | `ISSUE-P1-25` |
| `T-12`（`SecureCaptureActivity` 缺遮挡触摸过滤） | `ISSUE-P3-103`（§41 转登） |
| `T-13`（软 Keystore 落位 fail-open） | `ISSUE-P1-22`（§39 转登） |
| `T-14`（UI 误标 `ChaCha20-Poly1305`） | `ISSUE-P3-92`（§40 转登） |
| `T-15`（KDF `secret(K)` 常驻无清零点） | `ISSUE-P2-60`（§40 转登）；内存附件池部分见 42.3 `P3-119` |
| `T-16`（凭据未清零的实现不一致：`resolveRemotePath`） | `ISSUE-P3-100`；其余两部分见 42.4 / 42.3 `P3-118` |
| `T-2`（弱主口令 + `.bak` 离线破解）/ `T-6`（解锁态 FLAG_SECURE） | 产品裁决 / 已接受残余（§29.3、§33） |
| 交付物 4 的 `B-1`~`B-6`、交付物 5、交付物 6 生命周期分析 | 见 42.6 留存 |

### 42.3 本批次新转登 `ACTIVE_ISSUES.md`（14 项）

| 新编号 | 来源 | 主题 |
|---|---|---|
| `ISSUE-P2-75` | `T-8b` / `Q-14` | 远端读取无尺寸上限 + PROPFIND 递归 / 只 catch `Exception` → OOM / `StackOverflowError` |
| `ISSUE-P2-76` | `Q-2` | CM / Passkey 通道 `BiometricPrompt` 未绑 `CryptoObject`（与自动填充通道不对称） |
| `ISSUE-P2-77` | `Q-16` / `T-9c` | 切换 / 新建库不擦除旧库、不通知锁观察者 |
| `ISSUE-P2-78` | `T-10` | CM 保存路径写入畸形 URL（`https://https://…` / `https://android:apk-key-hash:…`） |
| `ISSUE-P2-79` | `Q-3` / 审计 `A-1`、`A-2` | KDF 强度基线未对齐（建库默认偏弱、导入弱参数原样保留；**需产品确认**） |
| `ISSUE-P3-116` | `T-9b` | 「彻底退出应用」不清缓存（`exitProcess` 不经锁观察者） |
| `ISSUE-P3-117` | `Q-15` / `T-9d` | `clearPasswordOnLeave` 为死开关 + 未提交主密码长期驻留 |
| `ISSUE-P3-118` | `T-16`（残余） | `save()` / `exportToBytes()` 的 `ByteArrayOutputStream` 内部缓冲从不擦除 |
| `ISSUE-P3-119` | `Q-10` / `T-15`（残余） | 内存附件池（`KdbxDatabase.binaries`）无擦除入口 |
| `ISSUE-P3-120` | `Q-11` | `RuntimeIntegrityDetector` 拦截力无实测（启发式、非完整性证明） |
| `ISSUE-P3-121` | `T-17` | 自建内网 WebDAV / NAS 出厂配置下不可用（`ssrfAllowedHosts` 未接线） |
| `ISSUE-P3-122` | 审计附录 C（`T6`） | IPC 面待复核 4 项（`IPC-01` / `IPC-02` / `IPC-05` / `IPC-10`） |
| `ISSUE-P3-123` | 审计附录 C（`T7`） | CI / 供应链硬化遗留 4 项（依赖校验元数据、Daemon JVM 校验和、CI 不跑 instrumented、`mapping.txt` 可见性） |
| `ISSUE-P3-124` | 审计 `SUPPLY-06` / `AC-06` | DAL 出口未接 SSRF 守卫与 TLS-only 声明 |

### 42.4 已复核为「有意设计 / 已声明限界」（不立案，仅留痕）

| 项 | 结论 | 证据 |
|---|---|---|
| `T-5` 受信浏览器白名单过窄（Brave / Edge / Samsung / Focus 走 DAL，DAL 预算常在填充窗口内超时 → 静默不下发候选） | **有意取舍**：无权威来源的指纹一律不收录（禁止臆写），方向 fail-closed | `BrowserSigningFingerprints.kt:11-27` KDoc 载明取证纪律与"禁止臆写" |
| `Q-17` 后半（`WebDavAuthHeader` 以 Base64 `String` 持凭据整个 Provider 生命周期） | **代码内已声明限界**，非新缺陷 | `WebDavAuthHeader.kt:25-26` KDoc「已声明限界」 |
| `SUPPLY-05` 原生不可用时口令强度评估回退 JVM 实现 | **有意设计**：`nativeAvailable` 明示"不应作为业务分支依据"，回退实现有跨语言 parity 合同断言 | `PasswordStrength.kt:106-118`；`PasswordStrengthNativeParityTest` |
| `T-15` 树外引用者持整棵 `KdbxDatabase` 树 | **已由 `ISSUE-P1-07` 收口**（`SyncCoordinator` 注册为锁观察者并释放 `lastSyncedDb` 等） | `SyncCoordinator.kt:96-102` |
| 交付物 4.3「应用进程 = 信任域」（`InMemoryCipher` 进程密钥永不擦除 / 永不轮换） | **已文档化取舍**（原语选择的必然结果，非可"补开关"修复） | `InMemoryCipher.kt:36-44,62,65`；`AGENTS.md` §6 |
| 交付物 4.5「主密码 = 唯一凭据、在线爆破默认无节流」 | 部分已登记（`ISSUE-P2-45` 节流 / `ISSUE-P2-79` KDF 强度 / `ISSUE-P3-107` `.bak`），其余为设计边界 | 同上 + §33 |

### 42.5 已核实为「前提不成立 / 非缺陷」（本批次新增结论）

- **`Q-8` / `C-6`（Compose `rememberSaveable` / `SavedStateHandle` 是否可能承载主密码或条目口令字符）—— 关闭**。
  核实于 2026-09-13（HEAD `a669a48`）：`app/src/main` 内 `rememberSaveable` **零命中**；`SavedStateHandle`
  仅见于 `EntryEditViewModel.kt:44,91-93`（`entryId` / `groupId` / `templateId`）与 `EntryDetailViewModel.kt:48,76`
  （`entryId`）——**只承载标识，不含任何口令 / 主密码字段**。故「秘密不出进程」的不变式在本批次核实范围内成立，
  该开放问题**不再作为待办**（此前列于报告 `§7 Q-8` 与 `§9 C-6`）。

### 42.6 留存结论（随报告退役，本节为单一真相源）

#### (a) 信任边界 TB-1 ~ TB-10

| # | 边界 | 跨越的东西 | 验证者（要点） |
|---|---|---|---|
| TB-1 | 用户 → UI | 主密码、条目口令、TOTP 种子 | 无（人机边界）；侧漏防护 = `FLAG_SECURE`（锁定态强制）+ 遮挡触摸过滤 + 反 overlay；`SecurePasswordField` 为唯一 `CharArray` 桥接点 |
| TB-2 | UI → Repository / 会话 | `CharArray` 主密码 / 密钥文件字节 / 解锁意图 | 节流闸门、空密码拒绝、失败清零、完整性闸门（出厂节流默认关闭 → `ISSUE-P2-45`） |
| TB-3 | Repository → KDBX 编解码 | 明文口令字节、复合密钥、`KdbxDatabase` 树 | 单入口 `KdbxFile.load`（三个生产调用点）+ 凭据缓存克隆语义 + 只读模式 |
| TB-4a | 文件 → 头部认证 | header bytes + 存储的 SHA-256 / HMAC | 常量时间比对，失败即 `KdbxCorruptFileException` / `KdbxInvalidCredentialsException` |
| TB-4b | 密文 → 明文（载荷） | HMAC 认证后的分块密文 | 块 HMAC 先验后用（索引并入密钥）+ 终止块权威检查 |
| TB-4c | 解压 / XML → 对象树 | 解压后明文 XML | 解压上限、XXE 四特性 + handler 侧 fail-closed、深度 ≤64、文本长度上限、内层各上限 |
| TB-4d | Kotlin ↔ Rust（JNI） | 复合密钥、KDF 参数、块数据 | 定长布局契约 + `available` 探活 + `catch_unwind` + `Zeroizing`；失败一律回退 JVM 而非静默重派生 |
| TB-5 | 密码学 → Keystore / TEE | 封印凭据、MAC 密钥、断言私钥 | `KeyGenParameterSpec` + `KeyInfo` 全等探测；解封需 per-op 强生物识别（软 Keystore 落位仅告警 → `ISSUE-P1-22`） |
| TB-6 | 附件 → 磁盘 | 明文附件字节 | 0600 / 0700 + 锁定即清 + **冷启动对账**（§38 `ISSUE-P1-19`） |
| TB-7 | 同步出口 → 不可信云端 | 整份 `.kdbx` 密文、同步凭据、S3 SigV4 签名 | TLS-only + SSRF / DNS 重绑定守卫 + 防回滚 MAC + 三哈希状态机 |
| TB-8 | 其他应用 → 自动填充服务 | `AssistStructure`（调用方可控）、`autofillId`、`webDomain` | 系统背书包名 + 完整性 / 黑名单 + 域归属双向绑定 + 强制二次确认 |
| TB-9 | 其他应用 → Credential Provider | `BeginGetCredentialRequest`、`requestJson`、`origin` | 官方 `getOrigin` + 特权白名单 / `apk-key-hash` 固定颁发；RP-ID 与包名双重严格匹配 + 交付前复验 |
| TB-10 | 应用 → 其他应用 / 系统（出口） | 剪贴板明文、验证码、导出明文、备份 | 敏感标记 + 定时擦除；通知最小化；备份全排除；明文导出需显式二次确认 |

#### (b) 6 条真实有效的安全边界（交付物 4.1）

| 边界 | 拦截对象 | 强度 |
|---|---|---|
| B-1 KDBX4 完整性契约（头 SHA-256 → 头 HMAC → 块 HMAC → 终止块） | 恶意 `.kdbx`、篡改流量、恶意导入文件 | **强**（密码学级、fail-closed、无旁路） |
| B-2 Argon2 KDF + 主密码熵 | 离线破解、未解锁物理接触 | **强但依赖参数与口令强度**（→ `ISSUE-P2-79`） |
| B-3 Android 沙箱 + 组件权限模型 | 普通恶意应用（无 / 有 normal 权限） | **强**（平台提供） |
| B-4 域 / 包名归属绑定（浏览器指纹 + DAL + PSL + 严格标签边界） | 跨应用 / 跨域读取凭据 | **强** |
| B-5 自动填充强制二次确认 + Keystore `CryptoObject` 绑定 | 恶意自动填充客户端静默取走口令 | **中强**（CM 通道未绑定 → `ISSUE-P2-76`） |
| B-6 TEE / StrongBox 不可导出密钥 | 未解锁物理接触、离线窃取 prefs | **中**（可防拷走文件后解密，不防进程内调用；软 Keystore fail-open → `ISSUE-P1-22`） |

#### (c) 15 类对手结论（交付物 3）

| 对手 | 能否最终拿到明文口令 | 决定性拦截点 / 失效原因 |
|---|---|---|
| A 普通恶意应用（无权限） | **否** | 沙箱 + `exported`/BIND 权限 + 域归属校验 + 精确包名相等 |
| B 恶意应用（normal 权限） | **否** | 同上 + `setHideOverlayWindows` + 遮挡触摸过滤 |
| C 恶意输入文件攻击者 | **否** | 解析上限 + XXE fail-closed + 密钥文件不做 XML 解析 |
| D 恶意 KDBX 攻击者 | **否**（最强结论） | 头 HMAC → 块 HMAC → 终止块三道 fail-closed，无主密钥不可构造可通过内容 |
| E 物理接触（设备未解锁） | **否** | Argon2 + TEE 不可导出；设备已解锁则退化为 F |
| F 设备已解锁状态攻击者 | **是** | 自动锁定窗口（默认 60 s）+ 解锁态 `FLAG_SECURE` 随用户开关真实解除 |
| G root | **是** | 无软件边界（`/proc/<pid>/mem`、Frida、调用 Keystore）；`ProtectedString` 进程密钥为静态字段 |
| H 被攻陷的 OS | **是** | 同 G，且可早于应用启动 |
| I 网络攻击者（同步在途） | **否** | TLS-only + 密文传输；无证书固定（有意），但篡改不可被接受；重放受防回滚链限制 |
| J 供应链（反编译 / patch / Frida） | **是** | R8 仅提高成本；`RuntimeIntegrityDetector` 可绕过 |
| K 恶意插件 / 第三方组件 | **是** | 同进程即同信任域 |
| L 恶意 WebView / Intent 源 | **否**（作为普通应用） | 域归属校验 + 非浏览器 origin 被完全忽略；无外部 `startActivity` 拉入敏感页 |
| M 恶意自动填充客户端 | **否** | `setAuthentication` 门控 + 域归属校验；可社交工程诱导确认（→ `ISSUE-P1-24` / `ISSUE-P2-70`） |
| N 恶意 IME | **是** | 应用层无有效边界（框架层无法下发 `IME_FLAG_NO_PERSONALIZED_LEARNING` → `ISSUE-P3-76`，**明示接受**） |
| O 离线数据库破解者 | **否** | Argon2 KDF + 主密码熵（唯一纯密码学边界；强度口径见 `ISSUE-P2-79`） |

#### (d) 信任假设 TA-1 ~ TA-8

| # | 假设 | 若违反 |
|---|---|---|
| TA-1 | 「应用进程内的代码都是受信的」 | 崩溃式失效：主密码、解密树、进程内密钥同处一堆 → G / H / J / K 全部成功 |
| TA-2 | 「Android 沙箱 + TEE 是真的」 | Keystore 密钥可导出、`filesDir` 可被其他 UID 读 → B-3 / B-6 归零 |
| TA-3 | 「用户主密码有足够熵」 | 唯一纯密码学边界失效（`.kdbx` / `.bak` 可被离线解开） |
| TA-4 | 「用户会看清确认框再点确认」 | 自动填充 / 选择器门控退化为形式；确认框不显示请求方使该假设更脆弱 |
| TA-5 | 「设备未被解锁 / 未被攻击者短暂持有」 | 解锁态保护取决于用户开关；自动锁定默认 60 s；窗口期内明文可见 |
| TA-6 | 「KDBX 对象树整体驻留内存是唯一可行实现」 | 内存 dump 可得（与 TA-1 同源）；附件字节已移出（§35），对象树仍在 |
| TA-7 | 「云端只做存储，不参与信任」 | 架构上成立；但**跨路径整文件混淆**不可拦（已明示接受） |
| TA-8 | 「用户不需要输入法保护」 | 恶意 IME 取得全部键入秘密（`ISSUE-P3-76` 明示接受） |

#### (e) 条件化生存性分析（交付物 4.6，结论摘要）

- **(a) 攻击者取得 root**：**仍成立**——TEE / StrongBox 密钥不可导出、`.kdbx` 静态加密、KDBX4 完整性 fail-closed
  （仅对"离线篡改后回归"有意义）、生物识别录入变更即吊销。**不再成立**——`ProtectedString` 驻留加密、
  `FLAG_SECURE`、`RuntimeIntegrityDetector`、自动锁定、剪贴板擦除。
- **(b) 攻击者取得 APK**：**仍成立**——KDBX 静态加密强度（不依赖代码保密）、TEE 密钥不可导出、服务端无秘密。
  **不再成立**——`RuntimeIntegrityDetector` 全部结论、`FlagSecurePolicy`、`AutofillAuthBindingPolicy`、
  `CredentialFillVerifier`、`UnlockThrottle` 及其 MAC 校验、`ExportConfirmationPolicy`；R8 仅成本提升（保留行号）。
- **(c) 攻击者取得 KDBX 文件**：**全部机密性与完整性密码学保证仍成立**（前提 TA-3）。**可以**：离线暴力破解、
  重放整份旧文件（受防回滚链拦截，跨路径混淆不拦）；`.bak` 使攻击面扩大到"曾用过的任何主密码"。
- **(d) 攻击者能控制应用进程**：**仅两项仍成立**——TEE 内密钥不可导出、Keystore `KeyGenParameterSpec`
  约束（如 per-op 强生物识别未授权时无法 `doFinal`）。**其余全部失效**（主密码明文、`ProtectedString` 全部明文、
  解密树、各策略类、节流完整性、运行时完整性）；即便密钥不可导出，仍可在**合法读取瞬间**截获明文（项目已如实声明）。
- **(e) 攻击者只能控制一个恶意 KDBX 文件**：**全部保证仍然成立**（本架构最强结论）——无法通过头部认证、
  无法构造可通过块 HMAC 的载荷、无法借解析器实现任意代码执行；唯一可达影响是**有界 DoS**
  （Argon2 `M` 上限 4 GiB，已登记为「纵深防御缺口当前不可达」，见 §22.2 子项 5）。

#### (f) 「看起来安全但不一定」逐项裁定（交付物 5）

| 假设 | 是否赚到 | 裁定要点 |
|---|---|---|
| 用 AES ≠ 安全 | **赚到**（正确用法范围内） | AES-256-CBC + PKCS#7；AES-ECB 仅出现在规范指定的 AES-KDF 变换且有 lint 抑制理由；IV 每次保存由 `SecureRandom` 重生成；无固定 IV / 无 ECB 误用 / 无 `java.util.Random`；CBC 无内置认证但整条载荷被块 HMAC 覆盖 |
| 用 Argon2 ≠ 安全 | **部分赚到** | 算法 / 版本 / 参数上界 / 原生与兜底差分等价均可；**未赚到**：出厂 `M/I/P` 强度口径（`ISSUE-P2-79`）、`secret(K)` / `A` 随会话长期驻留（`ISSUE-P2-60`） |
| 用 Android Keystore ≠ 自动安全 | **部分赚到** | per-op 强生物识别、`setInvalidatedByBiometricEnrollment(true)`、`setUnlockedDeviceRequired(true)`、StrongBox 优先、`KeyInfo` 全等探测迁移均到位；**未赚到**：软 Keystore 仅告警不硬失败（`ISSUE-P1-22`）、HMAC 类密钥无用户认证门控（KDoc 已如实声明） |
| 用 Rust ≠ 自动内存安全 | **赚到**（就内存安全而言） | `catch_unwind` 全导出包裹、`panic="abort"` 刻意未设、`Zeroizing` 全路径擦除、signed-before-narrowing；**但**秘密跨 FFI 复制，且 `CbcStreams` 在流生命周期内保留调用方 key 引用（顺序安全但属脆弱不变式） |
| 用 Kotlin ≠ 自动安全 | **未赚到——甚至更危险** | `String` 不可擦是主动对抗对象；实测缺口：密钥文件物化 `String`（`ISSUE-P2-62`）、受保护明文副本交主构造（§38 已修）、解锁失败日志含库标识（§38 存量已收敛）。均为「用了 Kotlin 不会自动避免」者 |
| 用生物识别 ≠ 安全 | **部分赚到，存在通道不对称** | 快速解锁与自动填充均已密码学绑定；**CM / Passkey 通道未绑 `CryptoObject`**（`ISSUE-P2-76`）；无强生物识别时两通道退化为受保护窗口内的手动确认（降级手段而非免验证） |
| `FLAG_SECURE` ≠ 全部截屏都被阻止 | **项目自己承认** | 补 `setHideOverlayWindows(true)`；锁定态无条件强制、解锁态随开关真实解除（§29.3）；所有敏感页面共用单窗口 → 关闭时**全部**同时失去保护；`SecureCaptureActivity` 曾缺遮挡触摸过滤（`ISSUE-P3-103`） |
| ProGuard / R8 ≠ 防逆向 | **未声称、也未做到** | release 仅 `isMinifyEnabled + isShrinkResources`，主动保留行号（取舍已写明）；整包 `-keep` 降低利用难度；无字符串加密 / 无完整性自检 / 无反调试 → 逆向与 patch 难度 ≈ 普通加固后的 Android 应用 |
| ChaCha20-Poly1305 ≠ 实现正确 | **本仓根本没有该 AEAD** | 实现是 raw ChaCha20（RFC 7539）+ 独立 HMAC 块流（即 KDBX4 规范）；UI 标签错误（`ISSUE-P3-92`）；曾把 16 字节 IV 静默截断为 12，现已 fail-fast |
| 成熟密码学 crate ≠ 用对了 | **大体赚到，有真实落差** | 用对口：IV / nonce 长度硬校验、`Pkcs7` 单一实现、差分等价测试、KAT。落差：Argon2 `AD ≤ 32B` 使超长 AD 静默改走 BouncyCastle（行为等价但原生路径不覆盖全部输入）；`AesKdfJce` 忽略 `Cipher.update(...)` 返回值（provider 依赖）；`KdfParameters.Argon2.equals/hashCode` 忽略 `secretKey` / `associatedData`、`KdbxHeader.equals` 忽略 `publicCustomData`（**当前无生产消费方**，属潜伏隐患） |
| 实现 KeePass / KDBX 格式 ≠ 继承其安全属性 | **未完全继承** | 项目自身已发现并修两个「本地自读自写永远通过」的结构性掩盖缺陷（写侧恒写 16B IV / Argon2 `P` 用 UInt64）；另有两次「JVM 过、Android 挂」逃逸（§24 / §26）。**教训**：格式兼容性必须由**外部官方实现当裁判** |

#### (g) 开放问题与威胁清单状态总表

| 条目 | 状态 / 去向 |
|---|---|
| `Q-1` / `Q-2` | `ISSUE-P2-53` / `ISSUE-P2-76` |
| `Q-3` | `ISSUE-P2-79`（含审计 `A-1`、`A-2`） |
| `Q-4` / `Q-5` / `Q-6` / `Q-7` | `ISSUE-P2-36`（§38 已整改）/ `ISSUE-P2-62` / `ISSUE-P3-82` / `ISSUE-P1-25` |
| `Q-8` | **本批次核实后关闭**（见 42.5） |
| `Q-9` / `Q-10` / `Q-11` / `Q-12` | `ISSUE-P3-107` / `ISSUE-P3-119` / `ISSUE-P3-120` / `ISSUE-P2-69` |
| `Q-13` / `Q-14` / `Q-15` / `Q-16` / `Q-17` | `ISSUE-P1-20`（§38）/ `ISSUE-P2-75` / `ISSUE-P3-117` / `ISSUE-P2-77` / `ISSUE-P3-100`（前半）+ 42.4（后半） |
| `T-1` / `T-2` / `T-3` | 设计边界（交付物 4.3 / 4.5）；`ISSUE-P3-76` |
| `T-4` / `T-5` / `T-6` | `ISSUE-P1-24` + `ISSUE-P2-70` / 42.4（有意取舍） / 产品裁决（§29.3、§33） |
| `T-7` | 已登记（§22.2 子项 5） |
| `T-8` / `T-8b` / `T-8c` | `ISSUE-P1-20`（§38）/ `ISSUE-P2-75` / Assume-Breach 固有性质（非缺陷，随本表留痕） |
| `T-9` / `T-9b` / `T-9c` / `T-9d` | `ISSUE-P3-107` / `ISSUE-P3-116` / `ISSUE-P2-77` / `ISSUE-P3-117` |
| `T-10` / `T-11` / `T-12` / `T-13` / `T-14` | `ISSUE-P2-78` / `ISSUE-P1-25` / `ISSUE-P3-103` / `ISSUE-P1-22` / `ISSUE-P3-92` |
| `T-15` / `T-16` / `T-17` | `ISSUE-P2-60` + `ISSUE-P3-119` / `ISSUE-P3-100` + `ISSUE-P3-118` + 42.4 / `ISSUE-P3-121` |

#### (h) 无法确认 / 所需信息（报告 §9）

| # | 事项 | 归属 / 状态 |
|---|---|---|
| C-1 | arm64 真机 + 真实语料的端到端解锁 | `ISSUE-P3-23`（产品裁决不排期） |
| C-2 | Passkey 系统级交互 / `AssistStructure` 真实结构树 / 通知渲染的设备侧行为 | `ISSUE-P3-66`（产品裁决不排期） |
| C-3 | API 36 上 SAX 加固特性的实际生效集合（`Q-6`） | `ISSUE-P3-82`（设备侧回归） |
| C-4 | Android 沙箱是否确实阻止其他 UID 读取 `filesDir/*.kdbx` 与 `cacheDir` | 平台语义保证（(b) 推断），未做越权读取实测 |
| C-5 | `securityLevel` 为 SOFTWARE 的设备上的实际风险（`T-13`） | `ISSUE-P1-22`（要求设备侧实测） |
| C-6 | Compose `rememberSaveable` / `SavedStateHandle` 是否承载敏感字符（`Q-8`） | **已核实关闭**（42.5） |
| C-7 | 核验报告提供的 `webDomain` 在真实浏览器上是否总能拿到可用值 | 设备侧（可归入 `ISSUE-P2-42` 同族验证） |
| C-8 | `onSaveRequest` 是否只会在用户确认保存 UI 之后被系统调用 | 框架语义；与 `ISSUE-P3-122`（`IPC-10`）同批复核 |
| C-9 | `AtomicFileWriter` tmp / target 的实际 umask 权限 | 设备侧 `stat`（与 `ISSUE-P2-66` 同族） |
| C-10 | release 产物中是否存在未预期的类 / 字符串残留 | 需对 `app-release.apk` 逆向核对（`ISSUE-P3-123④` 同批） |

### 42.7 报告退役与索引同步

- **删除 `docs/THREAT-MODEL-AUDIT-d32f3e7.md`**；该文件为跟踪文件，原文可经
  `git show 9b64415:docs/THREAT-MODEL-AUDIT-d32f3e7.md` 取回（583 行）。
- `AGENTS.md` §4 文档索引**移除**该行（否则索引指向不存在文件，违反 §4 索引纪律）。
- 报告内对现存问题的全部结论已转登 `ACTIVE_ISSUES.md`（14 项，42.3）；
  「有意设计 / 已声明限界 / 前提不成立 / 无法确认 / 留存结论」以本节为**单一真相源**。

### 42.8 方法纪律（继承 §41.8，本批次新增三条）

1. **威胁建模报告的 `Q`（开放问题）/ `T`（威胁清单）/ `C`（无法确认）三类必须分别处置**：
   `Q` 须给出「成立并转登 / 已覆盖 / 核实后关闭」之一，`T` 须给出严重度复核结论，
   `C` 须给出「归属既有条目 / 保持不可确认」——**不得整篇留档而无人流转**。
2. **「有意设计」与「缺陷」必须分列**：如 `T-5`（白名单过窄）与 `Q-17` 后半（凭据 `String` 驻留）
   均已在代码 KDoc 内声明取舍，登记时应引其出处，**不得当作新发现重复上报**（延续 §39.10 第 5 条）。
3. **第三方报告的「未登记开放问题」是本次审计流程的盲区**：本报告 `Q-2` / `Q-11` / `Q-14` / `Q-15` /
   `Q-16` 等多项在其提交时即成立，但既未进 `ACTIVE_ISSUES.md` 也未进 §40 的转登清单——
   根因是 §40 / §41 只处置了**审计主报告**与**敏感数据流报告**的清单，未覆盖**配套威胁建模文档**。
   故立规：**同批提交的多份审计文档必须**在同一退役批次内**逐份处置**。

---

<a id="s43"></a>
## §43 安全问题与整改方案报告退役批次（`SECURITY_AUDIT_REMEDIATION.md` 退役 + 附录 A–F 留存）（2026-09-13）

**批次性质**：**处置归档**（非代码整改批次）。`SECURITY_AUDIT_REMEDIATION.md` 本是 2026-09 外部安全审计
主报告退役后的「该轮审计唯一留存记录」（§40.6）。本批次核实其**正文 32 项条目已全部完成覆盖**
（§40.2 / §40.4 / §40.5，无缺口），其**附录 A–F 的非待办类结论**移交本库留存后，**退役并删除**该文档。

### 43.1 来源与事实

| 事实 | 证据 |
|---|---|
| 该文档为 2026-09 外部审计（基线 `d32f3e7`）的整改执行清单 + 主报告退役后的唯一留存记录 | 文档头注 + §40.6 |
| 随 `9b64415` 提交；`§40` 批次追加了附录 A–F（**该次追加在原文档中未提交**） | `git log --oneline -- docs/SECURITY_AUDIT_REMEDIATION.md` → 仅 `9b64415`；`git status` 显示 `M` |
| 本批次处置时 HEAD = `a669a48` | `git log --oneline -1` |
| 退役后可经 `git show 9b64415:docs/SECURITY_AUDIT_REMEDIATION.md` 取回**未含附录 A–F** 的版本（528 行）；附录 A–F 的完整内容自本批次起以 §43.3 ~ §43.8 为单一真相源 | `git show … \| wc -l` |

### 43.2 正文 32 项覆盖完整性核对（无缺口）

| 类别 | 数量 | 落点 |
|---|---:|---|
| 已在 §38 批次整改（`F-09` / `F-11` / `F-13` / `F-15` / `F-23` / `F-25`） | 6 | §40.2 |
| 与原红队批次条目重合（`F-01` → `ISSUE-P2-45`；`F-18` 风险明示面 → `ISSUE-P3-84`） | 2（不重复登记） | §40.4 |
| 转登 `ACTIVE_ISSUES.md` | 25 | §40.5（`P2-48`~`P2-60`、`P3-86`~`P3-97`） |
| **合计** | **32** | 与附录 A 口径注（下表 35 行 = `F-01…F-25` 25 项 + `RUST-01…RUST-11` 11 项中另见 §13.N 明细者）一致 |

> **计数纪律**：本表以 §40.5 的转登清单为权威口径；文档内「29 项 / 32 行 / 31 行」并存的自相矛盾见 §40.3，
> **不得**再作为计数依据（延续 §40.8 第 2 条）。

### 43.3 附录 A — CVSS 4.0 ↔ CWE 对照表（原件 §9 / §13.N，**档案留存**）

> **口径注**：原总表实际 31 行（`F-01…F-25` 共 25 + `RUST-01…RUST-06` 共 6），
> 另 `RUST-07…RUST-11` 仅见于原报告 §13.N 明细；其「29 项」计数与分布表存在内部矛盾（见 §40.3），
> 故本表**仅作档案留存，不作为计数依据**。「不适用」表示原判定该项不可被攻击者利用
> （方向 fail-closed / 零调用点 / 文档缺陷），依报告自身纪律不强行打分。

| ID | 标题（简） | 严重度 | 分类 | CWE | CVSS 4.0 向量 | 分数 |
|---|---|---|---|---|---|---|
| F-01 | 解锁节流生产默认关闭 | MEDIUM | Security Weakness | CWE-307 | AV:P/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N | 4.6 |
| F-02 | 明文导出缓冲未清零 | LOW | Hardening | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:L/VI:N/VA:N | 2.3 |
| F-03 | 导出确认仅在 UI 层 | LOW | Hardening | CWE-602 | AV:L/AC:H/AT:N/PR:H/UI:P/VC:L/VI:N/VA:N | 2.0 |
| F-04 | Chrome 指纹条目 65 字符永不匹配 | LOW | Confirmed Vulnerability | CWE-1289 | 不适用（fail-closed） | — |
| F-05 | 依赖 CVSS 闸门未接入自动触发 | HIGH | Security Weakness | CWE-693 | AV:N/AC:L/AT:N/PR:L/UI:N/VC:L/VI:H/VA:L | 7.3 |
| F-06 | 发布密钥库口令即示例口令 | MEDIUM | Security Weakness | CWE-1391 | AV:L/AC:H/AT:P/PR:N/UI:N/VC:H/VI:H/VA:N | 5.9 |
| F-07 | 审计摘要截断至 32 位 | INFO | Hardening | 不适用 | 不适用（无安全影响） | — |
| F-08 | 公开死函数缺参数校验 | INFO | Security Weakness | CWE-1164 | 不适用（零调用点） | — |
| F-09 | Salsa20 nonce 常量错误 | MEDIUM | Confirmed Vulnerability | CWE-1240 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:H/VA:N | 5.3 |
| F-10 | 附件引用放大 | HIGH | Confirmed Vulnerability | CWE-400/770 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:H | 6.5 |
| F-11 | 外层头部无总量上限 | MEDIUM | Confirmed Vulnerability | CWE-770 | AV:L/AC:L/AT:N/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| F-12 | KDF 无墙钟预算 | MEDIUM | Security Weakness | CWE-400 | AV:L/AC:L/AT:N/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| F-13 | 解密附件/密文快照无冷启动清理 | MEDIUM | Confirmed Vulnerability | CWE-459/212 | AV:P/AC:L/AT:P/PR:N/UI:N/VC:H/VI:N/VA:N | 4.0 |
| F-14 | DAL 响应体先物化后检查 | LOW | Confirmed Vulnerability | CWE-770 | AV:L/AC:H/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| F-15 | 解密后的受保护值明文未清零 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| F-16 | HmacBlockStream.readAll 非常时比较 | LOW | Security Weakness | CWE-208 | 不适用（无生产调用者） | — |
| F-17 | UI 误标 ChaCha20-Poly1305 | INFO | Design Concern | CWE-1059 | 不适用（文档缺陷） | — |
| F-18 | 剪贴板不随锁定清理 + 误清他处内容 | LOW | Security Weakness | CWE-226 | AV:P/AC:H/AT:P/PR:N/UI:P/VC:H/VI:N/VA:N | 3.4 |
| F-19 | 仅取首个签名者 | LOW | Hardening | CWE-1289 | 不适用（fail-closed） | — |
| F-20 | 冗余/废弃权限 | INFO | Hardening | CWE-272 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.0 |
| F-21 | 填充确认不校验锁定 | LOW | Design Concern | CWE-613 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:L/VI:N/VA:N | 3.1 |
| F-22 | 选择器锁定后崩溃 | LOW | Potential Vulnerability | CWE-248 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| F-23 | 同步防回滚状态随锁库清除 | HIGH | Confirmed Vulnerability | CWE-693 | AV:N/AC:H/AT:P/PR:N/UI:P/VC:N/VI:H/VA:N | 6.9 |
| F-24 | CM 通道从不查询 RuntimeIntegrityGate | MEDIUM | Confirmed Vulnerability | CWE-693 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:H/VI:L/VA:N | 4.4 |
| F-25 | 解锁失败日志含库 id / 密钥文件长度 / 异常原文 | LOW | Security Weakness | CWE-532 | AV:L/AC:H/AT:P/PR:H/UI:P/VC:L/VI:N/VA:N | 2.4 |
| RUST-01 | Argon2 工作内存未擦除 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-02 | 派生密钥栈副本残留 | LOW | Hardening | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-03 | 口令强度评估二次复杂度 → 主线程 ANR | MEDIUM | Security Weakness | CWE-407 | AV:L/AC:L/AT:P/PR:N/UI:P/VC:N/VI:N/VA:H | 5.3 |
| RUST-04 | 非法 UTF-8 口令的未擦除副本 | INFO | Hardening | CWE-226 | 不适用（调用方不可达） | — |
| RUST-05 | 原生路径缺内存上界预检 | LOW | Hardening | CWE-770 | AV:L/AC:H/AT:P/PR:N/UI:P/VC:N/VI:N/VA:L | 3.3 |
| RUST-06 | KDF secret `K` 常驻无清零点 | LOW | Security Weakness | CWE-226 | AV:L/AC:H/AT:P/PR:H/UI:N/VC:L/VI:N/VA:N | 2.6 |
| RUST-07 | Kotlin CBC 加密流遗留未擦除明文分块 | LOW | Security Weakness | CWE-226 | （原报告未给向量） | — |
| RUST-08 | Twofish JNI 创建输出数组前回写 IV | INFO | Design Concern | — | 不适用（无调用方在异常后继续） | — |
| RUST-09 | CI 从不执行 JNI 边界测试；符号表核对未实现 | LOW | Design Concern | — | 不适用 | — |
| RUST-10 | Kotlin `Arrays.fill` 不受 JVM 保证 | INFO | Hardening | — | 不适用（平台固有限制） | — |
| RUST-11 | 一次性 cipher API 对整段缓冲全量拷贝 | INFO | Hardening | — | 不适用（生产未使用） | — |

### 43.4 附录 B — 经查证不成立的疑似问题（误报排除，**防重复上报**）

| 编号 | 疑似问题 | 排除理由 |
|---|---|---|
| FP-01 | `OtpEngine.parseOtpAuthUri` 缺校验致除零/指数爆炸 | 该函数**零调用点**（生产走 `TotpKeyUriParser`，`period`/`digits` 已钳制）→ 不可达 |
| FP-02 | 同上（重复论证） | 同 FP-01 |
| FP-03 | `OtpEngine.calculateHotp` 的 `hash[offset+3]` 越界 | `offset = hash[19] & 0x0F ∈ [0,15]`，`offset+3 ≤ 18 < 20`；SHA-256/512 边界更宽 |
| FP-04 | 「解锁节流可经自动填充入口绕过」 | 三个解锁入口共用同一 `UnlockViewModel.gate()`，不存在平行入口 |
| FP-05 | 内层流「每个值重置」 | 规范要求状态**不**重置，实现正确 |
| FP-06 | 生产 Hilt 未注入真实依赖致 fail-open | release 生成组件已证明为**测试专用**，生产注入真实现 |
| RUST 侧 1 | 负值经 `as u32` 穿过闸门 | 有符号 `jint` 前置判定成立，负值不可能变为巨值 |
| RUST 侧 2 | panic 跨 JNI 边界 UB | 4 个导出函数全 `catch_unwind` 包裹，panic 归一为 `null` |
| RUST 侧 3 | Twofish JNI 原地修改 IV | 属**文档化契约**（流式调用方依赖它；一次性调用方传 `iv.copyOf()`）→ 不可利用 |

另：**CWE-22 路径穿越**经查证不成立——附件缓存 key 为随机 UUID（`FileBinaryStore`）。

### 43.5 附录 C — 待复核区（原件 §10，**未经逐条复核，不作为待办**）

> 原报告自述这些条目「证据充分但首席审计员尚未逐条核实，故暂不编号、不计入统计」。
> 本批次**首次**把它们转为可复跑的待办（下表右侧），以避免"线索随文档删除而丢失"。

**T6（IPC / 自动填充 / 剪贴板）提交**：`IPC-01` 跨响应复用 `requestCode` 致 PendingIntent extras 串扰 ·
`IPC-02` 凭据落地 Activity 以可变 extras 作身份来源 · `IPC-03` 选择器不显示请求方 · `IPC-04`（已复认为 `F-04`）·
`IPC-05` `CallingOriginResolver` 白名单 JSON 偏离官方 schema · `IPC-06` `clientDataJSON.androidPackageName`
取自已废弃 `getCallingPackage()` · `IPC-07`（已并入 `AC-02`）· `IPC-08` FLAG_SECURE 解锁态真实解除 ·
`IPC-09` 无障碍 / 输入法属设计边界 · `IPC-10` `onSaveRequest` 无超时预算 · `IPC-11` `android://<pkg>` 未与签名绑定。
（T6 另提交 16 项已验证强项。）

**T7（供应链 / 构建 / CI）提交**：`SUPPLY-01`（已复认为 `F-05`）· `SUPPLY-02` 无 `verification-metadata.xml` /
依赖锁定 · `SUPPLY-03`（已复认为 `F-06`）· `SUPPLY-04` 未文档化的 DAL 出口 · `SUPPLY-05` 原生不可用时强度检测
静默降级 · `SUPPLY-06` DAL 未接 SSRF 守卫与 TLS-only · `SUPPLY-07` CI 不运行 instrumented 用例 ·
`SUPPLY-08` Gradle toolchain 未锁校验和。

| 待复核项 | 本批次去向 |
|---|---|
| `IPC-01` / `IPC-02` / `IPC-05` / `IPC-10` | **`ISSUE-P3-122`**（合并为「IPC 面待复核 4 项」，要求逐条给出成立/不成立结论） |
| `IPC-03` / `IPC-06` / `IPC-11` | 已分别由 `ISSUE-P2-70` / `ISSUE-P2-72` / `ISSUE-P2-46` 覆盖 |
| `IPC-04` | 已由 `ISSUE-P3-88` 覆盖（`F-04` 复认） |
| `IPC-07` | 已由 `ISSUE-P2-51` + `ISSUE-P3-84` 覆盖（`AC-02` 口径） |
| `IPC-08` / `IPC-09` | 产品裁决 / 设计边界（§29.3、`ISSUE-P2-44`、`ISSUE-P3-76`） |
| `SUPPLY-01` / `SUPPLY-03` | 已由 `ISSUE-P2-54` / `ISSUE-P2-55` 覆盖 |
| `SUPPLY-02` / `SUPPLY-07` / `SUPPLY-08` | **`ISSUE-P3-123`**（合并为「CI / 供应链硬化遗留 4 项」，含 `mapping.txt` 可见性） |
| `SUPPLY-04` / `SUPPLY-06` | **`ISSUE-P3-124`**（DAL 出口纵深防御，含「未文档化出口」如实登记要求） |
| `SUPPLY-05` | **已复核为有意设计**（回退实现有 parity 合同断言，见 §42.4）→ 不立案 |

### 43.6 附录 D — 无法确认 / 所需信息（原件 §11）

| # | 无法确认的问题 | 解除所需 |
|---|---|---|
| C-1 | Android Keystore 密钥在解密路径中是必要条件还是装饰性闸门 | 读全部解密调用点 + 设备侧验证「锁定后无认证能否解密」 |
| C-2 | `CallingAppInfo.getOrigin()` 对 `build:"default"` 与 `userdebug` 布尔键的容忍度 | 真机以 Chrome 触发通行密钥请求，打印 `resolveTrustedOrigin` 结果 |
| C-3 | 系统是否在多次响应间复用同一 `PendingIntentRecord` | 同一测试应用连续两次 `getCredential`，打印落地 Activity 收到的 extras |
| C-4 | 框架是否接受「属于另一会话的 `AutofillId`」 | 设备侧自动填充会话测试 |
| C-5 | ChaCha20/Salsa20 内流的 nonce/counter 重置语义与 nonce 复用可能性 | `crypto` 内流实现逐行复核（**注**：`F-09` 常量错误已由 §38 修复） |
| C-6 | PKCS7 填充校验与坏填充错误处理路径 | `crypto` CBC 流实现复核 |
| C-7 | gzip 解压炸弹的流式边界与 `MAX_*` 上限的实际强制位置 | `database` 解压路径复核 |
| C-8 | XML 解析器 XXE / DTD / 深度限制 | 已由 `KdbxXmlParser` 加固路径 + `ISSUE-P3-82` 设备侧回归覆盖 |
| C-9 | TEE / StrongBox 在具体硬件上的认证行为；`KeyPermanentlyInvalidatedException` 语义 | 设备侧测试（见 `AGENTS.md` §6 真机待补） |
| C-10 | 分支保护是否要求 `build` 工作流通过（决定 `F-05` 的爆炸半径） | `gh api repos/{owner}/{repo}/branches/main/protection` |
| C-11 | `dependency-scan` 是否曾对 `d32f3e7` 运行过 | `gh run list --workflow=dependency-scan.yml` |
| C-12 | 发布 APK 是否字节可复现 | 两次干净 `GRADLE_USER_HOME` 的 `assembleRelease` + `diffoscope` |
| C-13 | 完整传递依赖图（CVSS 闸门实际扫描的集合） | `./gradlew :app:dependencies` |
| C-14 | `mapping.txt` 产物的可见范围（对公众开放等同公开去混淆映射） | CI 产物可见性设置（→ `ISSUE-P3-123④`） |

### 43.7 附录 E — 已确认的安全强项（原件 §1.3 / §9B.2，**非缺陷**）

1. **认证先于解密，端到端**：头部 SHA-256 常时比较 → 头部 HMAC → 才 `loadPayload`；载荷内每个 HMAC 块在解密前验证。
2. **全部密钥材料比较使用常时算法**（`MessageDigest.isEqual`），未发现对秘密派生字节使用 `==` / `String.equals`。
3. **Rust FFI 边界纪律完整**：导出函数全 `catch_unwind`；窄化前有符号 `jint` 闸门 + `saturating_mul`；秘密经 `Zeroizing`。
4. **`unsafe` 仅 5 处**，全为 `u8`→`i8` 同布局重解释，SAFETY 注释成立；无 `transmute` / 裸指针解引用。
5. **`AssociatedData` 上限 fail-closed**：Rust 侧 >32 B 返回 `None`，Kotlin 侧显式改走 BouncyCastle。
6. **SSRF / DNS 重绑定防护**（`SyncEndpointGuard`，含 IPv4-mapped IPv6 递归判定，「任一解析结果被挡即整体拒绝」）。
7. **备份面已封堵**：`allowBackup=false` + `data_extraction_rules` 对 cloud-backup 与 device-transfer 双通道排除。
8. **发布包最小化**：无 WebView、无 `addJavascriptInterface`、无动态加载、无隐式动态广播。
9. **导出组件面**：仅 `MainActivity` + 两个 `BIND_*`（signature）服务；无 `ContentProvider` / `FileProvider` / 深链。
10. **R8 日志剥离线真实生效**（`AppLog.d`/`v` 已在 release 移除）。
11. **签名凭据从未入库**（对象级证明：全历史仅 `keystore.properties.example`）。
12. **供应链无遥测**：无 analytics / ads / crash-reporting SDK；全部 CI Action 以 commit SHA 钉死；
    Gradle Wrapper 分发 SHA-256 已锁定；`Cargo.lock` 入库并以 `--locked` 使用。

### 43.8 附录 F — 组合攻击链（原件 §8，供威胁评估引用）

| 编号 | 链条 | 结论 |
|---|---|---|
| AC-01 | 弱主口令 + 节流默认关闭 + 已解锁设备 → 本地暴力破解 | MEDIUM（受 Argon2 成本限制） |
| AC-02 | 敏感剪贴板 + 进程在超时前被杀 → 口令长期驻留剪贴板 | LOW（依赖时序） |
| AC-03 | 示例口令公开 + 密钥文件经其他渠道泄露 → 伪造签名更新 | MEDIUM（**前提**：密钥文件外泄；密钥材料未入库） |
| AC-04 | 无效浏览器指纹条目 + DAL 不可用 → 功能退化 | fail-closed，**无凭据泄露** |
| AC-05 | （**已排除**）自动填充入口绕过解锁节流 | 三入口共用同一 `gate()`，不成立 |
| AC-06 | DAL 使用裸 `OkHttpClient`（无 SSRF 守卫 / TLS-only `ConnectionSpec`） | **本批次已核实并转登 `ISSUE-P3-124`** |
| AC-07 | 进程在解锁态被杀 + 已解析大附件 → 无需口令获得附件明文 | MEDIUM（**已由 §38 `ISSUE-P1-19` 冷启动清理闭环**，残余窗口见 `AGENTS.md` §6） |
| AC-08 | 弱主口令 × 节流默认关闭 × 冷启动计数窗口 | MEDIUM（已由 `ISSUE-P2-45` 承接） |

### 43.9 附：非缺陷类产品决策（原件「附」节，A-1 ~ A-4）

| 项 | 内容 | 本批次去向 |
|---|---|---|
| A-1 | **新建库 KDF 默认强度偏弱**（默认 Argon2id `m=64 MiB, t=2, p=2`；`KdfBenchmark` 已有设备自适应建议但**建库路径未消费**） | **`ISSUE-P2-79`**（需产品确认，已核实 `KdbxHeader.kt:162-171`） |
| A-2 | **导入无工作量下限**（接受 `m=1 MiB, t=1, p=1` 与 AES-KDF `R=1`，保存时原样保留弱参数） | **`ISSUE-P2-79`**②③ |
| A-3 | **`F-13` 配套文档收紧**（`FileBinaryStore` KDoc 与 `AGENTS.md` §6/§35 的「锁定即闭环」措辞） | **已在 §38 落实**（`AGENTS.md` §6 已改为如实声明非正常终止路径的残余窗口） |
| A-4 | **`mapping.txt` 可见范围**（77.5 MB，retrace 必需品；对公众开放等同公开去混淆映射） | **`ISSUE-P3-123④`** |

### 43.10 报告退役与索引同步

- **删除 `docs/SECURITY_AUDIT_REMEDIATION.md`**；该文件为跟踪文件，原文（未含 §40 追加的附录 A–F）可经
  `git show 9b64415:docs/SECURITY_AUDIT_REMEDIATION.md` 取回（528 行）；附录 A–F 自本批次起以 §43.3 ~ §43.9 为准。
- `AGENTS.md` §4 文档索引**移除**该行；
- `ACTIVE_ISSUES.md` 内指向该文档的「详细修复方案 / 唯一留存记录」指针改指本节（§43）；
- §40.6 中「上述四类由 `SECURITY_AUDIT_REMEDIATION.md` 附录 A–F 承接」的表述**由本节取代**
  （历史原文保留在 §40，仅追加本指针，不改写既往记录）。

### 43.11 方法纪律（继承 §42.8，本批次新增两条）

1. **「唯一留存记录」文档也必须可退役**：退役条件是「正文条目 100% 已在 `ACTIVE_ISSUES.md` / 本库有落点」
   **且**「附录类非待办结论已在本库留存」——二者缺一不得删除（本批次以 43.2 的完整性核对表作为前置证据）。
2. **待复核区（未逐条复核的线索）不得以"不计入统计"为由直接丢弃**：应转为**待办条目**
   （本批次即把 `IPC-01/02/05/10`、`SUPPLY-02/04/06/07/08`、`mapping.txt` 转为 `P3-122` / `P3-123` / `P3-124`），
   或在核实后写明证否依据——**"未经复核"不是"不作处置"的理由**。

---

<a id="s44"></a>
## §44 P0 双项整改批次：字段引用消费点白名单 + 同步崩溃面遏制（2026-09-13）

> **本批次缘起**：第四轮独立复核定版（`SECURITY_RECHECK_2026-09.md`）转登的两项 P0——
> `ISSUE-P0-08`（`{REF:P@…}` 口令经用户名通道 4 个泄漏出口离开应用）与 `ISSUE-P0-09`
> （远端把"同步失败"提升为"进程崩溃"的遏制绕过）同批整改闭环；`ISSUE-P0-09` 的整改
> **同时覆盖 `ISSUE-P2-75` 全部 AC**（后者为其降级前的原始条目，本批一并闭环归档）。

### 44.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P0-08** | P0 | `FieldReferenceEngine` 取值模式无消费点白名单：条目 `UserName` 含 `{REF:P@…}` 时，被引用条目的**口令明文**经用户名通道离开应用（剪贴板 / 确认页 extra / 数据集 RemoteViews / IME 内联建议 / 请求方输入框） | 引擎 `resolve` 强制显式 `consumerField`（**消费点面白名单**）：口令消费点（`P`）保持 KDBX 语义展开；非口令消费点（`T/U/A/N/I`）遇 `wantField == P` 或 `searchField == P` 输出既有 `PROTECTED_PLACEHOLDER` 掩码，**递归链全程约束**。`VaultRepository.resolveFieldReferences` 签名加 `consumerField`（无默认值，编译期强制声明意图），**5 处调用点同批覆盖**：`AutofillDatasetBuilders.kt` username 通道→`USER_NAME`、password 通道→`PASSWORD`；`EntryDetailViewModel` copyPassword→`PASSWORD`、copyUsername→`USER_NAME`；`AutofillPickerViewModel`→`PASSWORD`。展示侧 `resolveForDisplay` 语义不变 | `FieldReferenceEngineTest` 新增 4 例（P 取值面掩码不外泄 / P 检索面掩码 / `U@`、`T@` 负例行为不变 / 掩码随递归链全程约束）；`FieldReferenceDisplayModeTest`、`FieldReferenceEngineDeviceTest`（含新增设备侧 P0-08 回归锁）同批更新；既有 `FieldReferenceEngineTest` 全部调用点补声明消费点面 |
| **P0-09** | P0 | 远端（或系统 CA 级 MITM）可单方面把"同步失败"提升为"进程崩溃"：provider `runCatching` 捕到 `Error`（超深 XML 的 `StackOverflowError` / 超大响应 OOM）包成 `Result.failure`，经引擎 `getOrThrow()` 原样重抛，`SyncCycleRunner` 仅捕 `Exception` → `Error` 脱网杀死进程且每周期复发 | 三道防线同批落地（AC①"必须同时"）：① **下载体入口封顶**——新增 `SyncDownloadLimits`（声明尺寸预检 + 流式累积双重封顶，超限抛 `ProtocolError(413)`；数据库 128 MiB / PROPFIND 16 MiB），WebDAV / S3 的 `download` 与 PROPFIND `getMetadata` 全部改有界读取；② **`WebDavPropfindParser.findNodes` 递归 → 显式栈迭代 + `MAX_XML_DEPTH = 64` 深度上限**（超深节点不采信），parse 捕获面扩到 `Throwable`；③ **`SyncCycleRunner` 捕获面**：`handleOpenRemote` 与 `runSyncCycle` 外层均 `catch (Throwable)` 归一为 `SyncOutcome.Error`（`CancellationException` 原样重抛保结构化并发） | `SyncDownloadLimitsTest` 5 例（声明超限拒收不消费流 / 声明缺失流式封顶 / 声明撒谎中途拒绝 / 正常尺寸完整读取 / 常量量级）；`WebDavPropfindParserTest` 3 例（正常解析行为不变 / **5000 层超深 XML 遏制为回退元数据不栈溢出** / 上限内正常解析）；`SyncCoordinatorTest` 新增端到端用例——模拟 provider 重抛 `StackOverflowError`，断言遏制为 `SyncOutcome.Error` 而非进程崩溃 |
| P2-75 | P2（P0-09 前身） | 远端读取无尺寸上限（P0-09 的降级前原始条目） | **随 P0-09 一并闭环**——其 AC①②③ 与 P0-09 完全同构（下载体双重封顶 / PROPFIND 迭代化+深度上限+`Throwable` 捕获 / 超大响应与超深 XML 负例），无剩余独立面 | 同 P0-09 |

### 44.2 验收证据

#### (1) 单测全绿（权威强制重跑，2026-09-13）

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 38s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 111 | 833 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 353 | 0 | 0 | 0 |
| sync | 20 | 203 | 0 | 0 | 13 |
| **合计** | **200** | **1570** | **0** | **0** | **13** |

**基线变动**：1557 → **1570（+13 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
新增分布：app +1（`SyncCoordinatorTest` P0-09 端到端）、database +4（`FieldReferenceEngineTest` 白名单）、
sync +8（`SyncDownloadLimitsTest` ×5 + `WebDavPropfindParserTest` ×3）。

#### (2) AC 逐条核对

- **P0-08**：AC① 白名单落在引擎取值通道且 `consumerField` 为无默认值参数（不存在无约束取值入口）✓；
  AC② 全仓 `resolveFieldReferences` 消费点穷尽复核恰 5 处、同批全覆盖 ✓；AC③ 正例 `UserName = {REF:P@A:target}`
  断言掩码且不含明文、负例 `{REF:U@…}` / `{REF:T@…}` 行为不变 ✓（外部工具明文 `otp` 走自定义字段通道，
  不经本引擎，天然不受影响）。
- **P0-09**：AC① 捕获面 + `findNodes` 迭代化/深度上限 + 下载体封顶三道同批 ✓；AC② `SyncCycleRunner`
  两处捕获均已改（provider 之外的重抛链在引擎层）✓；AC③ 超大响应（`SyncDownloadLimitsTest` 三种形态）与
  超深 XML（`WebDavPropfindParserTest` 负例）均被拒绝且不 OOM / 不崩溃 ✓；AC④ 正常尺寸同步
  （`SyncCoordinatorTest` 既有 5 例 + `SyncDownloadLimitsTest` 正常读取例）不受影响 ✓。
- **附注（设备侧）**：P0-08 新增 1 例设备侧回归锁
  （`FieldReferenceEngineDeviceTest.设备上非口令消费点遇密码引用输出掩码`），计入 `database`
  androidTest 源集，待下次设备批次随基线实测（本批 JVM 侧已覆盖同语义用例）。

---

<a id="s45"></a>
## §45 P1 双项整改批次：确认页调用方归属与首次绑定授权 + 剪贴板口令面引用敏感通道（2026-09-13）

> **本批次缘起**：P1 节四项中经第四轮定版（`SECURITY_RECHECK_2026-09.md`）仍保持「修 P1」的
> 两项同批整改闭环——`ISSUE-P1-24`（终评 MEDIUM）与 `ISSUE-P1-25`（终评 MEDIUM；其根因已于
> §44 由 `ISSUE-P0-08` 消费点白名单闭环，本批补齐 AC①②③ 的通道侧整改）。
> `ISSUE-P1-22`（降修 P2）与 `ISSUE-P1-23`（降 P2 产品告知项）仍留 `ACTIVE_ISSUES.md` 待后续批次。

### 45.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P1-24** | P1（MEDIUM） | 自动填充确认页只展示条目标题、不展示调用方归属（包名 / 签名 / 域），且无「首次绑定」显式授权——恶意应用渲染高仿登录页即可诱导用户确认填充；另会话授权宽限（P3-42）可携带**口令值**免确认下发 | ① **AC① 归属展示**：`CredentialFillConfirmScreen` 新增 `attributionContent` 槽位与 `confirmEnabled` 门控（默认参数，既有调用方 `PasswordFillActivity` 零改动）；`AutofillConfirmActivity` 强制渲染不可伪造锚点——调用方**包名**（系统结构树来源，经本服务构建的 FLAG_IMMUTABLE 确认 PendingIntent extra 传递）+ **签名证书 SHA-256**（复用 `AutofillOriginResolver.callingAppCertSha256Hex`，`private`→`internal`；包可见性受限读取失败时如实标注「不可读」）+ **归属校验后的目标域**（未通过校验的域本就不下发候选）；② **AC② 首次绑定**：新增 `AutofillCallerTrustStore`（信任键 = 归一化包名 + 签名摘要，SharedPreferences 持久化，`context=null` 内存语义供 JVM 单测；同包名**换签名重新视为首次**；非法包名 fail-closed）；首次出现目标**不进入系统认证弹窗**（归属与授权只能在受保护窗口内展示执行），强制走手动确认且须显式勾选「记住此应用」方可确认；已授权目标走系统弹窗时把包名并入副标题；③ **AC③ 口令禁无 UI 下发**：`AutofillAuthenticationPolicy.skipRepeatConfirmation` 新增 `datasetCarriesPassword` 参数——授权宽限对携带口令值的数据集一律不生效（口令仅在显式确认后下发，用户名可例外），`AutofillDatasetBuilders` 调用点同步 | `AutofillCallerTrustStoreTest` 新增 7 例（首次未授权 / 授权后命中 / **换签名重置** / 摘要不可读退化仅包名 / 跨包名不互通 / 非法包名 fail-closed / 撤销对称）；`AutofillSessionGrantStoreTest` 新增 1 例（携带口令值 → 宽限不生效）；strings.xml / values-en 新增 8 条归属与授权文案 |
| **P1-25** | P1（MEDIUM） | `copyUsername` 把解析结果恒走明文通道（不设 `EXTRA_IS_SENSITIVE`、不调度擦除）；且 `ClipboardSecurityManager.copyPlainText` **无条件** `cancelScheduledClear()` 会把「上一次敏感复制」的擦除计划一并取消 | ① **AC①**：`FieldReferenceEngine` 新增 `containsPasswordFaceReference`（P 取值面 / P 检索面逐引用判定，大小写不敏感）；`EntryDetailViewModel.copyUsername` 分流——UserName 含口令面引用时走 `copySensitiveText`（`EXTRA_IS_SENSITIVE` + 自动擦除调度；P0-08 白名单已把此类引用掩码输出，敏感通道为白名单被未来削弱时的纵深防线），无引用行为不变；② **AC②**：`copyPlainText` 移除无条件 `cancelScheduledClear()`（结构性消除，全仓仅此一处调用）——到期哈希比对保证：剪贴板已被普通内容覆盖时不误清、仍持有敏感值时按时清除；③ 通道抽象 `ClipboardSecurityChannel` 接口（生产经 `SecurityModule` `@Binds` 绑定 `ClipboardSecurityManager`），ViewModel 依赖接口便于纯 JVM 断言通道决策 | `FieldReferenceEngineTest` 新增 6 例（P 取值面 / P 检索面 / 大小写不敏感 / 公开字段负例 / 混合文本逐引用判定 / 无引用直返）；`EntryDetailViewModelTest` 新增 3 例（P 取值面引用 → 敏感通道 / P 检索面引用 → 敏感通道 / 无引用 → 明文通道行为不变），经 `RecordingClipboardChannel` 记录桩断言通道选择 |

### 45.2 AC 逐条核对

- **P1-24**：AC① 确认页强制展示不可伪造归属 ✓（手动确认路径恒渲染归属块；首次目标强制走手动路径；
  已授权目标走系统弹窗时包名并入副标题——系统弹窗无法渲染自定义归属块，取舍见 45.3）；
  AC② 首次绑定显式授权 ✓（勾选前确认按钮禁用 + 信任存储持久化 + 换签名重置）；
  AC③ 口令仅显式确认后下发 ✓（宽限策略排除口令数据集；既有 `setAuthentication` 路径语义不变）；
  AC④ 三条各有回归 ✓（信任存储 7 例 + 宽限策略 1 例；归属展示属 Compose 渲染层，其数据装配
  与授权判定已由信任存储 / 归属数据类承载并被 JVM 断言，设备侧登记见 45.3）。
- **P1-25**：AC① ✓（口令面引用 → `copySensitiveText`，`EXTRA_IS_SENSITIVE` + 擦除调度由敏感通道
  统一承载）；AC② ✓（无条件 `cancelScheduledClear()` 已结构性删除，全仓该 API 零无条件调用点）；
  AC③ ✓（3 例通道分流回归 + 6 例引擎检测回归）。

### 45.3 已知边界与设计取舍

1. **归属展示的设备侧实测未含于本批**：确认页 / 归属块为 Compose 渲染，属「JVM 全绿不构成证据」的
   Android 运行时面（同 ISSUE-P2-42 纪律）；建议并入下次设备批次一并实测（复现配方：首次向任意应用
   触发自动填充确认 → 核对归属块渲染与勾选门控；已授权应用 → 核对系统弹窗副标题含包名）。
2. **签名摘要读取受 Android 11+ 包可见性约束**：`ISSUE-P2-74`（缺 `<queries>`）闭环前，对不可见包
   摘要恒「不可读」并如实标注；信任键随之退化为仅包名——P2-74 落地后自动增强为「包名 + 摘要」，
   无需本批代码变更。
3. **「记住此应用」为持久授权**：撤销通道为既有「为本应用禁用自动填充」黑名单（屏蔽即完全停止向该
   应用填充）；确认页取消勾选可撤销当次记录（`untrust` 对称实现）。
4. **授权宽限的口径变化（有意收紧）**：P3-42 会话授权宽限自本批起仅对**不携带口令值**的数据集生效
   （纯用户名表单）；携带口令值时一律回退「每次强制确认」，与 AC③ 一致。

### 45.4 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 1m 43s；114 actionable tasks: 114 executed（全部真实执行）
```

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| app | 112 | 844 | 0 | 0 | 0 |
| core | 9 | 65 | 0 | 0 | 0 |
| crypto | 15 | 116 | 0 | 0 | 0 |
| database | 45 | 359 | 0 | 0 | 0 |
| sync | 20 | 203 | 0 | 0 | 13 |
| **合计** | **201** | **1587** | **0** | **0** | **13** |

**基线变动**：1570 → **1587（+17 例）**；跳过数 13 与旧基线一致（`sync` 既有 live-sync 类跳过）。
新增分布：app +11（`AutofillCallerTrustStoreTest` ×7 + `AutofillSessionGrantStoreTest` ×1 +
`EntryDetailViewModelTest` ×3）、database +6（`FieldReferenceEngineTest` 口令面检测）。

---

