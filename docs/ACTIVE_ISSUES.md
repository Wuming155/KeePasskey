# KeePasskey 现存问题与待办清单（Active Issues）

> **文档定位**：本项目**唯一**的现存缺陷、功能缺口与待办任务清单。
> **排序规则**：严格按优先级 **P0 → P1 → P2 → P3** 降序排列。每项均包含完整背景、整改依据、涉及文件与验收标准，**实现时直接依据本文件操作，无需额外制定计划文件**。
> **只放现存问题**：本文件**只保留尚未闭环的条目**——已闭环条目的正文、批次流水与索引段一律不留；历史实现与验收证据以 [RESOLVED_LOG.md](RESOLVED_LOG.md) 与 `docs/resolved/batches/` 为单一真相源。
> **实施前必读**：各条目的 AC 已按 [`SECURITY_RECHECK_2026-09.md`](security/SECURITY_RECHECK_2026-09.md) §10 / §11 的**第四轮独立复核定版结论**逐条对齐，更正口径已内联到对应条目——照条目原文 AC 实施者可能**降低安全性**或**引入数据损坏**。
> **闭环纪律**：任务完成后，将该条目从本文件**整条移入** [RESOLVED_LOG.md](RESOLVED_LOG.md)（加一行索引 + 新增批次正文），并执行 `git commit & push`。

---

## 条目维护规则（ISSUE-P3-28 确立）

1. **新增条目必须附「核实时间点」与「核实方式」**：任何声称「某文件需要删除 / 某处没有消费方 / 某硬编码为 0」一类**关于代码库现状的前提**，都必须写明**何时、以何种手段**核实过，例如「2026-09-10 经 `Test-Path` 核实」「2026-09-10 经全仓 `grep maskPasswordsDefault` 核实（仅设置页回显）」。
   > 立规缘由：条目与代码库演进之间存在时间差，曾出现正文前提在开工时**已不成立**（文件早已删除、引用早已清理），致执行者去做已经完成的工作。
2. **开工前复核前提**：认领条目时先复核其正文前提（路径是否存在、行号是否漂移、消费方是否已出现）。前提已不成立的，**就地修正或显式标注**后再动手；完全无对象可改的条目应直接归档并注明原因。
3. **行号一律视为「核实时刻的快照」**：正文内引用的 `文件:行号` 仅作定位提示，实现前须以真实内容为准。

---

## 优先级定义

| 等级 | 严重度与类型 | 处理原则 |
|:---:|---|:---:|
| **P0** | **阻断级 / 致命安全漏洞**（数据损坏、篡改未拦截、明文泄露、严重崩溃） | 立即停止其他工作优先修复 |
| **P1** | **高危缺陷 / 核心功能阻断**（权限失效、关键契约异常、核心流程失败） | 最高优先级排期修复 |
| **P2** | **中危缺陷 / 协议互操作 / 性能瓶颈 / 测试有效性缺口** | 正常排期，按批次连续解决 |
| **P3** | **低危项 / 进阶特性接线 / 代码整洁度 / 评估与体验优化** | 渐进优化与特性补齐 |

---

## P1 高危与核心功能问题（0 项）

> **暂无开放项**（历史 P1 条目的实现与验收证据见 [RESOLVED_LOG.md](RESOLVED_LOG.md)）。

---

## P2 中危缺陷与协议/测试缺口（0 项）

> **暂无开放项**（历史 P2 条目——含 [RESOLVED_LOG.md](RESOLVED_LOG.md) §219 闭环的 `ISSUE-P2-199` /
> `ISSUE-P2-200` / `ISSUE-P2-208`，以及 §220 闭环的 `ISSUE-P2-210` / `ISSUE-P2-211`——的实现与验收证据见
> 该归档及 [`docs/resolved/batches/`](resolved/batches/)）。

---

## P3 低危问题、特性接线与体验优化（7 项）

### ISSUE-P3-201：`AutofillPickerActivity` 交付时点缺「锁定即丢弃」双时点复核（同形于 ISSUE-P3-95，picker 漏网）

- **背景与证据**（2026-09-19 对抗审计存活，证伪组复核引用行号全部无误）：`AutofillConfirmActivity.kt:233,276` 两处
  `canDeliverAuthResult(vaultRepository.isLocked())`，而 `AutofillPickerActivity.kt:168-286` 的 `confirmAndFill → deliver → setResult(RESULT_OK)`
  **全程无该判定**；凭据在生物识别**之前**已解密（`:171`），`deliver()` 内含 `totpPostFillActions.runAfterFill` 的 500ms 窗口（§56 批次正是为确认页立规的那个窗口）。
  上游无锁定关页机制（**第三轮更正**：picker 经 `AutofillPickerViewModel` 的 `SessionLockGuard` **确有一个** `SessionLockObserver`，但其回调只把候选列表清空、**不 gate 交付链路**；`AutoLockManager.kt:110-142` 的 onStop 延迟锁与 `AutoLockSessionGuard.lockOnScreenOff()` 的**熄屏即时锁**均可在页面存活期点火，且 `lockWhenScreenOff` / `autoLockBackground` **默认开启** ⇒ 可达性高于初版陈述）。
  副作用面比 P3-95 略宽：锁定后仍写 `AutofillSessionGrants.grant`（`:281`）与 `android://` 首次绑定（`:255`）。
  守卫缺口：`AutofillConfirmDeliveryLockTest.kt:100-101` 只扫确认页源码。限界表 §1.2/§13 与 PD-05 均未登记此面。
- **涉及文件**：`app/.../autofill/AutofillPickerActivity.kt`、守卫测试文件。
- **验收标准**：① picker 进入交付与 `setResult` 前双时点接入 `canDeliverAuthResult`；② 守卫测试扩至覆盖 picker 路径（或抽公共断言面）；③ test 全绿。

### ISSUE-P3-202：`SyncCache.writeCache/writeBaseContent` 与 `SyncRollbackGuard` 未按自声明口径做「第一字节即仅属主」权限收敛

- **背景与证据**（2026-09-19 对抗审计存活，定级 Low）：`SyncCacheFiles.kt:38-46`（`writeTmpSynced`）为正确范式并自述不变量
  「密文自落盘第一刻起即为仅属主可见」（ISSUE-P1-07 口径，见 `SyncCache.kt:32-34` KDoc），但 `SyncCache.writeCache`（`:113-121`）与
  `writeBaseContent`（`:196-205`）自建裸 `FileOutputStream(tmp)` 仅 rename 后收敛**目标**；`SyncRollbackGuard.persist`（`:199-209`）与
  `stateDir.mkdirs()`（`:106-110`）**全程不调用**任何收敛原语（自带 `moveAtomically:212-222` 亦无）。
  `SyncCacheAndroidRuntimeTest.kt:45-61` 只断言终态权限，写入窗口与 `filesDir/rollback` 无断言。
  实际暴露接近零（app 私目录 + 每应用 SELinux/FBE + tmp 名含随机 UUID；回滚状态文件仅摘要+Keystore HMAC，无密文）⇒ 属**不变量一致性缺口**，非可利用漏洞。
  **第三轮真机取证（Redmi 4X / Android 17 / API 37，2026-09-19）**：新用例 `RawFileWritePermissionDeviceTest`（`sync/src/androidTest/`）实测——裸 `FileOutputStream` 新建文件**即为 0600**、`mkdirs()` 新建目录**即为 0700**（进程 umask 0077），而平台预置的应用私有目录为 `0771`（others **仅可穿越、不可读亦不可写**）⇒ **「写入窗口期以默认 umask 暴露」在真机上不存在**；本条据此**确定收窄为「不依赖 umask 的自声明口径 vs 实际依赖 umask」的代码一致性/风格问题**，不再是安全暴露面。AC①（改走 `writeTmpSynced` 同法）仍成立，但理由由「防暴露」改为「消口径漂移」。
- **涉及文件**：`sync/.../engine/SyncCache.kt`、`SyncRollbackGuard.kt`、`SyncCacheFiles.kt`、设备侧测试。
- **验收标准**：① 两裸路径改走 `writeTmpSynced` 同法（或 rename 前收敛 tmp）；② 设备侧测试增补 tmp 窗口与 rollback 目录断言；③ 真机 `:sync:connectedDebugAndroidTest`（此面属「只有真机才能证伪」类，宿主恒降级）。

### ISSUE-P3-203：cargo 生产构建未 `--locked`——出厂 `.so` 与受审 `Cargo.lock` 无绑定断言

- **背景与证据**（2026-09-19 对抗审计存活，证伪组由 P2 降级 P3）：`crypto/build.gradle.kts` 的 `cargoNdkBuild`/`cargoHostBuild` 均无 `--locked`
  （**第三轮更正计数**：全仓 `--locked` 共 4 处——`build.yml:380` 的 `cargo test` 与**三次** `cargo install`（`:157` / `:383` / `:454`））。CI 绿态下 lock↔manifest 不一致会使 `cargo test --locked` 失败，
  故**非**静默投毒通道；真缺口是发布 `.so` 由 CI 外本地 `assembleRelease` 产出（native-gate 产物标 NOT-FOR-RELEASE），无任何 lock 一致性断言把出厂产物绑定到受审依赖树。
  PD-04 裁决的是闸门「检测 vs 阻断」，不豁免本条的**绑定证据缺失**。
- **涉及文件**：`crypto/build.gradle.kts`、CI 工作流。
- **验收标准**：① 两条 cargo 构建任务加 `--locked`；② 批次文档记录一次 `cargo update --dry-run` 类核对（如锁需更新须显式提交 lock 变更并在批次说明理由）。

### ISSUE-P3-204：`NativeArgon2` 运行时探活无 KAT 对照，与同批其余内核口径不齐（纵深防御一致性）

- **背景与证据**（2026-09-19 对抗审计提出，证伪组判定「非漏洞、纵深一致性缺口」保留 nit）：`NativeArgon2.kt:26-46` 探活判据仅 `probe != null`；
  对照 `NativeAesKdf.kt:34-55` 与 BC 逐字节比对（其 `:30-32` KDoc 明写「非空即通过」测不出内核返回同长度垃圾值）、`NativeAes/NativeChaCha20/NativePasskeySign/NativePasswordStrength` 均有官方向量对照（**第三轮更正**：原生内核实为 **7** 个——Argon2 之外 6 个均有对照，初版漏列 `NativeTwofish.kt`；结论不变：仍**仅** Argon2 用「非空即通过」）。
  无机密性后果（垃圾输出⇒密钥错⇒HMAC 失败⇒fail-closed），且设备侧发布门常态覆盖⇒运行时缺口影响仅可用性窗口。
- **涉及文件**：`crypto/.../kdf/NativeArgon2.kt`。
- **验收标准**：探活改固定输入 + 冻结期望摘要（或同参数与 BC `Argon2BytesGenerator` 比对，先例即 `NativeAesKdf`；探活参数 t=1/m=8 KiB 使对照成本可忽略）；test 全绿。

### ISSUE-P3-205：供应链豁免守卫不机检通配 regex 宽度——Kotlin 全量豁免的版本界护栏仅为散文承诺

- **背景与证据**（2026-09-19 对抗审计提出，证伪组确认现状无暴露后改归闸门加固缺口）：`.github/owasp-dependency-suppressions.xml` 以
  `^pkg:maven/org\.jetbrains\.kotlin/.*$`（无版本界）豁免 CVE-2026-53914（**第三轮补正**：同文件 `:48` 的 `^pkg:maven/androidx\.sqlite/sqlite(-framework)?@.*$` 亦为**版本无界**通配，仅 artifactId 受限 ⇒「无版本界」非孤例）；护栏「<2.4.20 构件出现即失效」只写在 `<notes>`。
  `SupplyChainSuppressionPolicyTest` 仅校验 notes 非空，**不校验 regex 宽度/版本界、不联动 `releaseRuntimeClasspath` 实测**。
  当前 kotlin=2.4.20（已修复版）⇒ 现状无暴露；残余为一次降级 PR 或传递解析变化即可让 CVSS 闸门对受影响构件保持绿灯。属闸门**判定本身**的旁路，不在 PD-04 覆盖内。
- **涉及文件**：`SupplyChainSuppressionPolicyTest`、suppressions.xml。
- **验收标准**：① 守卫测试增断言：豁免 regex 必须含版本界（或白名单化 artifactId），并核对其绑定版本 ≥ notes 声明的修复版；② test 全绿。

### ISSUE-P3-206：同步下载体在 Provider 内整份物化——`SyncProvider.download` 的 `ByteArray` 契约使下载期峰值达 ~2×S（**第三轮重写**：原「未接 `writeCacheStreaming`／缓存路径多驻留一份」两条立论已被逐行证伪）

- **背景与证据**（2026-09-19 提出，**第三轮重写**——原表述「`SyncEngine` 未接已存在的 `writeCacheStreaming`／该 API 全仓零调用方／交付副本之外再驻留一份 128 MiB」经四轮对抗审计**逐行证伪**，已全部删除）：
  - 物化点在 `SyncDownloadLimits.readBounded`（`SyncDownloadLimits.kt:55-76`）：**接受路径**以 `ByteArrayOutputStream` 累积后 `toByteArray()` 返回 ⇒ **持久 1×S，复制期瞬态再 +1×S**；声明长度缺失（chunked）时该缓冲倍增，峰值可达 **~3×S**。`SyncProvider.download` 契约即 `Result<ByteArray>`（`SyncProvider.kt:25`），WebDAV / S3 均经此物化。
  - 「不物化」只作用于 P0-09 的**超限拒绝**路径（`:47-53` / `:65-70`）；**接受路径整份物化**不在 P0-09 任何 AC 内（P0-09 只做「封顶 + 遏制」，未消除双缓冲）。
  - 两条原立论的证伪依据：①「`writeCacheStreaming` 全仓零调用方」**假**——`FileBinaryStore.kt:46-49` 有生产调用，经 `InnerHeader.kt:352` 附件落盘路径可达；②「接入即消掉交付副本之外的一份驻留」**假**——`SyncCache.writeCache`（`SyncCache.kt:115-119`）以 `FileOutputStream.write(data)` 原生直写，**不产生第二份堆数组**，窄义接入收益为 0（反多 64 KiB 缓冲）。
  - `SyncDownloadLimits.kt:23` 的「合法 `.kdbx` 远小于上限」前提**失实**：附件密文随库体存在（`KdbxFile.kt:306-319` 收编附件进内层头），外层 XML 内联附件形态（`KdbxXmlBinaryNode.kt:110-139`）亦不经 `binaryStore` ⇒ 数十~百 MiB 的合法库正落于上限邻域。
  - 解析侧**已就绪**：`KdbxFile.load(inputStream: InputStream, …)`（`KdbxFile.kt:118-123`）全程流式；唯一 `ByteArray` 边界是 `SyncProvider.download` 与 `DatabaseSession.parseExternalDatabase(bytes)`。
- **涉及文件**：`sync/.../provider/SyncProvider.kt`、`sync/.../network/SyncDownloadLimits.kt`、`sync/.../engine/SyncEngine.kt`、（可选）`database/.../session/DatabaseSession.kt`。
- **验收标准**：① `download` 契约改流式（返回 `InputStream`，或接收目标 `File`/`OutputStream`），`readBounded` 相应改「边读边写 + 累计封顶」，超限即中止且**不遗留半成品文件**；② 批次记录改造前后**下载期峰值**对比（现 ~2×S、可达 ~3×S → 目标 ~1×S）；③ 更正 `SyncDownloadLimits.kt:23` KDoc 的失实前提；④ `.\gradlew.bat test` 全绿。

### ISSUE-P3-209：`CleartextPolicyDeviceTest` 的 SSRF 用例恒真——断言不区分异常类型，无法侦测守卫缺失（四轮对抗审计收敛，原 P3-207 归档后新立）

- **背景与证据**（2026-09-19 对抗审计；**指控本身经真机取证修正**）：
  `app/src/androidTest/.../CleartextPolicyDeviceTest.kt:190-208` 的用例「工厂客户端的 SSRF 防线在设备上拒绝回环解析」只断言 `error != null`，**不区分异常类型**。
  - **原指控「请求目标是 IP 字面量 ⇒ 根本不经 `SsrfGuardDns` ⇒ 假阳性归因」经真机取证证伪**：`MockWebServer.start()` 无参时以 `InetAddress.getByName("localhost")` 绑定，`url()` 取 `socketAddress.address.hostName`（**记忆主机名、不反向解析**）⇒ host 为 **`localhost` 主机名**，请求确实经 `Dns.lookup` 并被 `SsrfGuardDns` 拦截（`UnknownHostException`）。新增设备用例 `SsrfRedirectBypassDeviceTest` 已把该前提固化为断言，Redmi 4X 真机通过。
  - **残余真缺陷（成立）**：自签证书使请求**无论守卫是否接线**都会失败（守卫在位 → `UnknownHostException`；守卫被删 → TLS `SSLHandshakeException`，同为非空）⇒ 该用例**恒绿、对守卫是否生效零判别力**。
  - **后果**：`docs/architecture/已知工程限界.md:245` 与 §203 批次据此登记「**工厂客户端 SSRF 回环防线生效**」为已证项——该声明**超出该用例的证据能力**（同形于 `ISSUE-P3-205` 的「散文承诺 ≠ 机检」）。该结论本身经本轮新建的 `SsrfRedirectBypassDeviceTest` 以**能红的判别断言**独立证实（`localhost` → `UnknownHostException`）⇒ 登记结论正确、但**证据链引用错误**：须把引用指向具备判别力的用例，并让原用例自身也能红。
- **涉及文件**：`app/src/androidTest/.../CleartextPolicyDeviceTest.kt`、`docs/architecture/已知工程限界.md`（§4.1 措辞）、§203 批次（追溯更正）。
- **验收标准**：① 断言收紧为**能红**的判据——`causes(error).any { it is UnknownHostException }`（或 message 含守卫文案 `"SSRF 防护"`，见 `SyncEndpointGuard.kt:255`），并在用例内补 `factoryClient.dns is SsrfGuardDns` 的接线静态断言；② 与 `SsrfRedirectBypassDeviceTest` 的判别面去重合并，避免两处重复维护；③ `:app:connectedDebugAndroidTest` 全绿。

> **本轮对抗审计已推翻/改归已知限界的候选**（不再立条，防重复认领；含第二轮终裁修正）：Ed25519/EC 私钥未擦除（`alloc` 传递点亮 `zeroize`、`cargo tree -e features` 实证 Drop 生效，裁决记录见本批文档）；CBC padding oracle（Encrypt-then-MAC 分层，MAC 先于交付）；ECDSA nonce 偏置（RFC6979 确定性）；proguard `-dontwarn` 残留（逐包定向、标准正用；release 未上设备系限界 §4.1 已登记）；下载全量物化「崩溃循环」（`SyncCycleRunner` catch Throwable 归一，第二轮核实于 :416-428，行号与原述 419-426 微漂）；`KdbxCipherKeyResolver` Int 溢出逃逸（输入须已过块 HMAC⇒自伤面，上层 catch Throwable 归一；建议随 P2-200 顺手补 `InnerHeader:319` 同法上界，不单立条目）；
> **第二轮改判**：「SSRF 重定向旁路」的推翻**部分失实**——主机名全跳经 Dns、跨主机重定向 Authorization 由 OkHttp `buildRedirectRequest` 剥离（本项目零 Interceptor 重加）两项证伪**成立**，但 IPv4 字面量旁路第二层 ⇒ 拆出 **P2-208 存活**、文档半边留 P3-207；「REF 展开未命中保持原文致逐层增长」不成立（实测输出与输入等长）。
> **第三/四轮终裁（2026-09-19，四轮循环对抗收敛 + 上游源码级 + 真机取证）**：
> ① **P3-207 归档（判为误报）**——`SyncEndpointGuard.kt:23-27` 逐字写着「防线分两层…连接期（DNS 解析后校验）：经 `[SsrfGuardDns]` 拦截**主机名解析结果**」⇒ 标题断言「声明与生效面不符」与背景断言「接线人无从知道」**均失实**；残余仅是「未写出『重定向』三字」的可读性 nit，且其唯一交付物（302 用例）与 P2-208 AC② **完全重合** ⇒ 无独立安全语义、无独有交付物，**已归档**，那一句 KDoc 澄清并入 `ISSUE-P2-208` 落笔。
> ② **P2-208 由符号级升级为源码级 + 设备级**：源码见 `okhttp-android-5.5.0-sources.jar` 的 `RouteSelector.kt:174-184`；设备侧由新建 `SsrfRedirectBypassDeviceTest` 三例坐实（含 302 旁路与主机名对照）。**「安全声明与生效面不符」半边删除**，`[::1]` 括注更正（IPv6 字面量**同样**旁路）。
> ③ **P3-206 重写**：原两条立论（`writeCacheStreaming` 零调用方 / 削减缓存路径驻留）经逐行证伪，改为「`SyncProvider.download` 契约使下载期峰值 ~2×S（chunked 可达 ~3×S）」。
> ④ **新立 `ISSUE-P3-209`**：`CleartextPolicyDeviceTest` 的 SSRF 用例恒真（原指控「IP 字面量假阳性归因」经真机取证**证伪**——`MockWebServer.url()` 的 host 是 `localhost` 主机名；但断言不区分异常类型 ⇒ 零判别力，而限界表据此登记「防线生效」）。
> ⑤ 本轮真机新增用例（Redmi 4X / Android 17 / API 37 全绿）：`SsrfRedirectBypassDeviceTest`（sync，3 例）、`RawFileWritePermissionDeviceTest`（sync，2 例）、`InlineCompressedBinaryBudgetDeviceTest`（database，2 例）、`PendingIntentMatchKeyDeviceTest`（app，3 例）。

