# §219 P2 三条闭环批次（CM 请求码单调 / 载荷累计预算 / SSRF 连接期复核）

> **本批条目**：`ISSUE-P2-199`、`ISSUE-P2-200`、`ISSUE-P2-208`（`docs/ACTIVE_ISSUES.md` 中**整条剪切**，原文**原样收录**于本文末尾）。
> **同一次提交**：代码 + 文档（AGENTS §6.4）。

## 1. 整改摘要

| 条目 | 根因 | 处置 |
|---|---|---|
| `ISSUE-P2-199` | CM 通道 requestCode 分布为「常量 + 每响应复位的局部计数器」，同组件跨响应必然命中同一 `PendingIntent` 记录，`FLAG_UPDATE_CURRENT` 就地覆写 extras | ① `CredentialPendingIntents.nextRequestCode()`（进程级 `AtomicInteger`）收口**四条**落地入口（解锁 Action / 创建 Passkey / 创建密码 / 断言 + 密码填充两条创建点）；② Create/Assertion 两路改以系统注入的 `callingRequest` 为权威、base extras 降级为展示缓存 |
| `ISSUE-P2-200` | ① 内联压缩附件的解压上限是**每次 gunzip 调用**独立封顶（多节点不累计）；② `{REF:}` 展开只封递归深度、不封分支计数与产出体积 | ① `BinaryReferenceBudget` → **`AttachmentBudget`**：池引用累计 + 内联物化累计（64 MiB）+ 内联压缩节点数（1024）三闸门；② `FieldReferenceEngine` 增 `MAX_EXPANSIONS=4000` / `MAX_PRODUCED_CHARS=1 MiB` 双闸门，超限**原样保留引用原文**；③ `EntryReferenceDisplayResolver` 逐字段 Throwable 兜底归一 |
| `ISSUE-P2-208` | OkHttp 路由层对 IP 字面量**短路**（`RouteSelector.nextRoutes` 早于 `dnsLookup`），`302 → 内网字面量` 不经自定义 `Dns` | 新增 `SsrfGuardSocketFactory`（覆写 `Socket.connect`，拦在 SYN 之前）+ `SsrfAddressApprovals`（白名单豁免地址在 Dns 层与连接期层**逐地址**一致，消除可用性回归） |

## 2. 验收证据

- **宿主 JVM 单测**：`.\gradlew.bat test --rerun-tasks --max-workers=1` 全绿 ——
  `python tools/doc/count_test_results.py` 报 **`xml=325 tests=2259 failures=0 errors=0 skipped=13`**；
  本批新增 **16** 例：`InlineCompressedAttachmentBudgetTest`(3) / `FieldReferenceExpansionBudgetTest`(4) /
  `SsrfGuardSocketFactoryTest`(5) / `CredentialRequestCodeWiringTest`(4)。
- **设备侧（Redmi 4X / Android 17 与 Pixel_10 AVD 双设备）**：
  - `:sync:connectedDebugAndroidTest` —— **22 / 22 全绿**（双设备），含被改写的
    `真实工厂客户端对主机名走 SsrfGuardDns、对 IPv4 字面量在连接期即拒`；
  - `:database:connectedDebugAndroidTest` —— **17 / 17 全绿**（双设备），
    含 `InlineCompressedBinaryBudgetDeviceTest`（其 `BinaryNode` 直构路径走 `unlimited()` 默认预算，
    故「单节点满额 ⇒ OOM」的既有实测结论**不受本批影响**，用例逐字未改）；
  - `:app:connectedDebugAndroidTest` —— Redmi 4X **70 / 70 全绿**；Pixel_10 AVD 69/70，
    唯一失败 `AutofillAuthChainDeviceTest.设备侧实测自动填充认证填充链路`（系统填充 UI 未呈现）经
    `git stash` 回 HEAD **复跑同样失败** ⇒ 既有环境面（与 §217 记录的同类现象一致），**非本批引入**；
  - 本批新增/改动的设备用例在真机报告中的逐条留痕（Redmi 4X）：
    `PendingIntentMatchKeyDeviceTest`（5 例，含新增「进程级单调 requestCode 使两路响应各持独立记录且
    extras 互不覆写」与**负对照**「同 requestCode 复核次注册确会覆写既有记录的 extras」）、
    `CredentialProviderRequestContractDeviceTest`（5 例，含新增「组装期 origin 副本与系统背书不一致时
    必须拒绝签发」）。
- **AC③「extras 不被覆写」的判别力自证**：`PendingIntent` 无公开 extras getter，故经一次**真实投递**
  （动态 `BroadcastReceiver` + `RECEIVER_NOT_EXPORTED`）读出记录内的 extras；**负对照**用例先证明
  「同 requestCode 时投递确会读出被覆写的值」，正例才算有效——否则「读回自己的值」可能只是投递噪声。
  该负对照即 §217 中「不用广播投递」这一旧取舍的**正当例外**（旧取舍针对无对照的投递断言）。

## 3. 过程缺陷与更正（如实留痕）

1. **`java.net.SocketFactory` 在 Android 平台不存在**：`SsrfGuardSocketFactory` 首次编译即
   `Unresolved reference`。OkHttp `OkHttpClient.socketFactory` 的实际类型是
   **`javax.net.SocketFactory`**（已在上游源码 `OkHttpClient.kt:28` 核实）——改导入后通过。
   > 教训：跨平台 API 面**必须以编译与上游源码为准**，不得凭「Java SE 有」推定 Android 有。
2. **`providerReq.callingAppInfo` 的非空性假设被编译器纠正**：`PasskeyCreateActivity` 初稿写
   `injected.callingAppInfo?.let { … }`，编译器报 `Unnecessary safe call on a non-null receiver`
   ⇒ `ProviderCreateCredentialRequest.callingAppInfo` 为**非空**。同批删去「系统请求不可得 ⇒ 回退缓存
   origin」的降级设想（该降级不存在；创建入口在 `providerReq == null` 时已 fail-closed）。
3. **新增设备用例的探针初版自造竞态**：`RecordingBroadcastProbe.await()` 原先在 `send()` **之后**才建
   `CountDownLatch`（投递可能已完成才开始等待）⇒ 改为 `expect(n)` 先置锁存、`await()` 后阻塞。
   若留存，正例会在慢设备上偶发假红，而「假红」会被误读为「覆写仍存在」。
4. **断言侧 origin 交叉核对与既有用例前提冲突**：`CredentialProviderRequestContractDeviceTest` 原先
   写入 `EXTRA_ORIGIN=WEB_ORIGIN` 而平台侧 `CallingAppInfo` **不带 origin**（派生值恒为 apk-key-hash）
   ⇒ 新交叉核对必然判为污染。处置＝让平台侧携带同一 origin 并把白名单指纹指向测试包（使派生值确为
   `WEB_ORIGIN`），从而该断言**具备判别力**而非「一律拒绝」。
5. **`{REF:}` 双闸门的误伤面以对照组锚定**：新增「预算内的合法引用链仍完整展开」（线性链 + 两层）与
   「预算内的内联压缩附件正常解压」「白名单豁免地址在连接期放行」三组对照，防「防线生效」退化为
   「一律拒绝」而无人察觉。

## 4. 与既有登记的关系

- `SECURITY_RECHECK_2026-09.md:588` 的 `IPC-01` 裁定行（点名「`RequestCodeAllocator` 每响应局部」而
  从未整改）为本条的前提来源之一；该表是**历史裁定快照**，按「只搬迁、不改写」纪律**不回溯改写**，
  闭环事实以本批次为正本。
- `ISSUE-P3-207` 归档时并入本条的残句（`SyncEndpointGuard` KDoc 未写出「重定向」三字）已随
  `ISSUE-P2-208` 落笔：该类 KDoc 现逐字写明三层防线，并把「IP 字面量 / 重定向跳转」列为
  `SsrfGuardSocketFactory` 的覆盖面。

---

## 5. 条目原文（`docs/ACTIVE_ISSUES.md` 整条剪切，原样收录）

### ISSUE-P2-199：CM 通道 PendingIntent requestCode 跨请求复用——创建侧 extras 优先可旁路 DAL 门控（同形缺陷 autofill 侧已修、CM 侧漏修）

- **背景与证据**（2026-09-19 经五路独立对抗审计 + 交叉证伪存活，主代理复核代码行属实）：
  `CredentialPendingIntents.kt:44` 的 `ENTRY_FLAGS = FLAG_MUTABLE or FLAG_UPDATE_CURRENT`，而落地 Intent 均为
  `Intent(context, Activity::class.java)` + 仅 extras（无 action/data ⇒ `Intent.filterEquals` 相同）。
  `CredentialResponseAssembler.kt:89` **每次响应新建** `RequestCodeAllocator`（基数 1000 复位，`:353-359`），
  分配器有两个调用点（`KeePasskeyCredentialProviderService.kt:172` 与 `CredentialUnlockActivity.kt:120`），同一次用户流程即可二次从 1000 重排；
  `CredentialCreateEntries.kt:28-29` 用常量 103/104，跨请求必然碰撞。同形缺陷已在 autofill 侧以**进程级 AtomicInteger** 修复
  （`AutofillDatasetBuilders.kt:43-63`，ISSUE-P3-122），其 KDoc 自称「与 CM 通道同构」——CM 侧实际未修。
- **攻击路径（证伪后残余的最窄场景）**：
  ① 创建侧（主残余）：`PasskeyCreateActivity.kt:86-105` 无条件优先取 base extras（与 `:97` 注释「系统请求 JSON 是权威来源」矛盾，
  仅 blank 才回落）⇒ 被覆写的陈旧 `origin` 使 `passesRegistrationGates:164` 整段跳过 DAL 校验，而 `callerPackage` 来自本次系统背书
  ⇒ `saveOrReplacePasskeyEntry:302` 持久化「他请求 rpId + 本请求包绑定」错配条目，可**原地替换**用户既有条目；
  ② 断言侧：同一认证调用方多候选（同浏览器两标签页同 rpId 多账号）时用户点 alice 实签 bob（密码学有效），误增他条 signCount、误耗 UV。
  跨调用方错配已被 `PasskeyAssertionRequestParser.kt:36-47` 包名交叉核对拦截、无机密外泄 ⇒ 定 P2（完整性/可用性）。
- **登记对表**：`SECURITY_RECHECK_2026-09.md:588` IPC-01 族已点名「`RequestCodeAllocator` 每响应局部」但从未整改；IPC-02 的「不成立」仅针对第三方伪造 extras（exported=false），与本进程自覆盖无关；限界表 §6（UNBOUND 放行）系另一面，非本条换皮。
- **涉及文件**：`app/.../passkey/CredentialPendingIntents.kt`、`CredentialResponseAssembler.kt`、`CredentialCreateEntries.kt`、`PasskeyCreateActivity.kt`、`PasskeyAssertionRequest.kt`。
- **验收标准**：① 分配器改进程级单调（与 `AutofillDatasetBuilders` 同构，KDoc 同步纠正「同构」自称失实）；② Create/Assertion 两路一律以系统注入的 `callingRequest` 为权威、extras 仅作展示缓存；③ 补 PendingIntent 同一性回归用例（并发两路响应 requestCode 互异 + extras 不被覆写）；④ `.\gradlew.bat test` 全绿。
- **第二轮终裁（2026-09-19，循环对抗收敛）**：维持 P2。androidx 各 Entry KDoc 明确要求「unique request code per entry」，常量 103/104 直接违约，缺陷成立不依赖竞态细节；先例确凿——IPC-01 同形模式已被项目自认并真实修复（批次 84 / `fbfcee2`，复现配方「分屏两应用触发填充」）。**显式假设登记**：A1=CM 新请求到达不取消已呈现响应的 PendingIntent 记录（隐藏≠取消，PI 于响应组装时即注册且刻意非 ONE_SHOT）；A2=同一 provider 两路未消费条目可并存。验证配方：双测试应用分屏各发 create，第二路注册后点第一路条目，断言 extras 是否被覆写；**A1 经实测证伪则本条降为「androidx 契约违规 + 限界登记」（P3/限界表）**。
- **第三轮终裁（2026-09-19，四轮循环对抗收敛 + 真机取证）**：**维持 P2**，但正文两处前提经独立复核**判为失实并就地更正**：
  ① **跨工厂碰撞不存在**——`Intent.filterEquals` 的匹配键含 component（`action / data / type / class / categories`，extras **不**参与），创建侧（`PasskeyCreateActivity` / `PasswordSaveActivity`）、断言侧（`PasskeyAssertionActivity`）、填充侧（`PasswordFillActivity`）、解锁侧（`CredentialUnlockActivity`）**组件两两不同** ⇒ 初版「落地 Intent 均为 `Intent(context, Activity::class.java)` + 仅 extras ⇒ `filterEquals` 相同」属**过度概括**；真实碰撞面只在**同组件跨响应**（创建 103 每次响应复用；断言/填充 1000+ 每响应从 1000 复位）。**真机取证**：`PendingIntentMatchKeyDeviceTest`（`app/src/androidTest/`）断言「同组件仅 extras 不同必 `filterEquals`」「跨组件不 `filterEquals`」「action 参与 `filterEquals`」——Redmi 4X 上 3 例全绿。
  ② **「他请求 rpId + 本请求包绑定」不可达**——跳过 DAL 的唯一分支要求 `origin` 为浏览器 origin（`https://…`），而**同一** `origin` 又决定 `PasskeyCreateActivity.kt:304` 的 `boundPackage = if (isBrowserOrigin(origin)) null else callerPackage` ⇒ 两判据**互斥**，落库必为 `null`（「他请求 rpId + **无**包绑定」）；正文措辞与代码冲突。
  ③ 措辞更正：`KeePasskeyCredentialProviderService.kt:172` 与 `CredentialUnlockActivity.kt:120` 是**两条组装入口的调用者**；分配器 `next()` 的真实调用点是 `CredentialResponseAssembler.kt:224`（断言）与 `:312`（密码填充）。
  ④ **命门仍是 A1/A2**（CM 是否在多响应间保留/复用同一 `PendingIntentRecord`），仓库内为设备侧待验证项 **C-3**，未闭环；本次已把可自动化的匹配键前提补成设备侧回归用例（见 ①）。

### ISSUE-P2-200：解密后载荷无累计资源预算——内联压缩附件解压放大 + `{REF:}` 指数展开（两处落点同根）

- **背景与证据**（2026-09-19 同上对抗审计存活，证伪组重算后**比原报更廉价**）：
  **落点①（解压放大）**：`KdbxXmlBinaryNode.kt:162` 的 `MAX_INFLATED_ATTACHMENT_BYTES`（=128 MiB）判据是**每次 gunzip 调用**的
  `out.size()`，非全会话累计；`SizeBoundedInputStream`（`KdbxFile.kt:94-96`）度量的是解密后 XML 供给量 ⇒ 封顶在压缩侧。
  复算：单文本节点 8M 字符 ⇒ ~131 KiB gzip 即换 128 MiB 驻留（比率仅 21:1），**1 个节点即可打崩低端机堆**；
  池引用分支有 `referenceBudget.account`（`:204`）而内联分支（`:213-221`）零记账——`BinaryReferenceBudget` 为 F-10「引用乘法封预算」而立，恰在此分支缺位。
  **落点②（REF 指数展开）**：`FieldReferenceEngine.kt:47` 的 `MAX_DEPTH=10` 只封递归**深度**；`:174-202` 对每个 match 递归展开目标值，
  分支因子=目标字段内引用个数，**无上界、输出长度无上限**。`{REF:N@T:<自身标题>}` 自引用即可分支：字段内放 k 个重复引用 ⇒ 输出 `k^11 × 原文`，
  **k=4、输入 <1 KB** 即 ~4×10⁶ 叶。`VaultListDecorationsProvider:46` 对整条投影流批量展开且无 Throwable 兜底 ⇒ **列表渲染即崩、每次开库复现**（准持久 DoS）。
- **威胁模型（证伪修正后的口径）**：云端/MITM 无法单方面构造需口令的载荷（`同步层记录级完整性威胁建模.md:25` 攻击者能力上界 + 头 HMAC/逐块 HMAC），
  可达投递面 = **SAF 打开他人分享的 `.kdbx` / 子库只读挂载 / 共享库恶意共同编辑者**（第二轮修正：`ImporterRegistry` 只注册 csv/json/xml/1pux，
  **`.kdbx` 不走导入器**，原「恶意文件导入」措辞作废；SAF/本地解锁路径**无任何先于解析的体积闸**，`SessionOpener.kt:168` 仅 exists 判定）。
  与先例 `P2-48/P2-67`「需口令面」同级，故 P2 非 P1。
  解锁路径有 `SessionOpener:181 catch Throwable` 归一（退化为「该库永久打不开」）；渲染路径**无**此兜底（见落点②第二轮结论）。
- **第二轮终裁（2026-09-19，量化经独立复算修正，P2 两落点均维持）**：
  ① 落点①更廉价、上界更高：`MAX_TEXT_CHARS=8,388,608` 作用于**单 TextNode**，Base64 剥空白后压缩输入上界 6 MiB；实测 gzip(128 MiB 全零)=**130,478 B（1028.7:1）**、
  Base64 后 173,972 字符仅占单节点额度 2.1% ⇒ 原「21:1」保守 48 倍；内联分支**无条数闸**（1024 只封内层池），名义最坏累计 = min(128 MiB÷174,067 B ≈ **771 节点**, 元素闸) × 128 MiB ≈ **96 GiB**；
  单节点峰值驻留 256–320 MiB（128 保留 + `toByteArray` 副本 128 + 末次扩容 64），manifest 无 largeHeap ⇒ **256 MiB 堆 n=1 即崩**（攻击文件成本 ~170 KiB）。
  ② 落点②指数确认且为 **k^11**（d=0..10 共 11 层执行 replace，递归对象=目标字段原值，Python 逐字复刻控制流实测 k=2→40,960=2^11×20）；
  极小构造 `{REF:N@T:}`（空标题命中 RefIndex 的 `""` 键）k=4、输入 44 B ⇒ 1.845×10⁸ 字符（Android UTF-16 **352 MiB**），**k≥5 任何设备必崩**；
  引擎**零**自引用/visited 防护，既有 A→B→A 回归用例分支因子恒为 1、对乘法放大零覆盖（假阳性安心）；
  触发面**比原述更宽**：`VaultListDecorationsProvider.kt:42-49` 消费整库投影（分组/搜索过滤在下游），**全库每次 emission 展开、恶意条目藏隐藏分组照样触发**；
  链路无 catch，SupervisorJob 使 Error 落主线程 uncaughtExceptionHandler ⇒ 终态**进程崩溃**、重启即复现（非页面卡死）。
- **涉及文件**：`database/.../xml/KdbxXmlBinaryNode.kt`、`database/.../file/KdbxFile.kt`、`database/.../fieldref/FieldReferenceEngine.kt`、`app/.../ui/model/EntryReferenceDisplayResolver.kt`、`app/.../data/repository/VaultListDecorationsProvider.kt`。
- **验收标准**：① 内联解压接入全会话累计预算（与 `BinaryReferenceBudget` 同语义，禁「每调用独立封顶」）——**第二轮提示**：预算若仍取 128 MiB，低端机单节点场景不会消失，须显著低于堆界（建议 **≤64 MiB 累计 + 内联压缩附件节点数上限**）；② `{REF:}` 展开加**输出字节预算 + 分支计数**双闸门（超限原样保留未展开段，不抛错不吞原文），并给列表渲染批量展开加 Throwable 兜底归一；③ 用例三件：多内联压缩附件库、自引用 `{REF:N@T:}` 库（**分支因子 k>1**，现有 k=1 用例不算覆盖）、空标题 `""` 检索键命中用例，断言在预算内失败/降级而非 OOM/崩溃；④ `.\gradlew.bat test` 全绿 + `:database:connectedDebugAndroidTest`（涉解析面，按 AGENTS §5② 设备义务评估——本条未触原生分派，如判定免设备须在批次注明理由）。
- **第三轮终裁（2026-09-19，四轮循环对抗收敛 + 真机取证）**：落点①**存活，但措辞与量化须更正**：
  ① 「1 节点打崩低端机堆」**不是夸大**，但**不是进程崩溃**——三个生产解析入口全 `catch (t: Throwable)`（`SessionOpener.kt:181` / `DatabaseSession.kt:199` / `ChildReadOnlySession.kt:127`、`:145`）⇒ 定性应为「**内存放大型 OOM；因入口归一为「该库打不开」而表现为 DoS**」（捕获 OOM 属 best-effort，不得断言「绝无崩溃」）。
  ② **峰值复算修正**：`initialCap = min(4·C, 4 MiB)`（`:151-155`）**非 2 的幂** ⇒ `cap < 2·D`，单节点峰值 `max(3·C_last, cap + D) < 3 × 128 MiB ≈ **384 MiB**`（初版「256 MiB」偏低）；且超限路径 `:162-166` 抛 → `:170-171` 原样重抛，**不经 `:175` 的 `toByteArray()`**——「复制第二份」只在成功路径发生，两分支均 ≥192 MiB。
  ③ `MAX_TEXT_CHARS = 8 shl 20` 作用于**字符**且远不成约束（gzip(128 MiB 全零)≈130 KB ⇒ Base64 ≈174 K 字符，仅占 8.39 M 的 ~2%）。
  ④ **真机取证（Redmi 4X / Android 17 / API 37，2026-09-19）**：设备堆界实测 **`maxHeap = 201,326,592 B = 192 MiB`**（manifest 无 `largeHeap`）；新用例 `InlineCompressedBinaryBudgetDeviceTest`（`database/src/androidTest/`）**直接驱动生产代码 `BinaryNode`**（`Compressed="True"` 内联 `<Value>` 分支）实测 **`outcome = OutOfMemoryError`** ⇒ **单节点即可打崩低端机堆，实证成立**；`:database:connectedDebugAndroidTest` 全绿。

### ISSUE-P2-208：IPv4 字面量重定向目标旁路 `SsrfGuardDns`——PD-02 第二层防线对字面量跳转不生效（第二轮新立）

- **背景与证据**（2026-09-19 第二轮终裁，决定性上游源码取证）：原候选「SSRF 未覆盖重定向」被第一轮以「OkHttp 每跳（含 IP 字面量）都过自定义 Dns」推翻，
  该推翻**失实**。OkHttp 5.5.0 `okhttp3/internal/connection/RouteSelector.kt`（tag `parent-5.5.0`）`nextRoutes`：
  `if (socketHost.canParseAsIpAddress()) return listOf(Route(... InetAddress.getByName(socketHost) ...))`，**其后**才是 `dnsLookup`（`address.dns` 即 `SsrfGuardDns` 的挂载点）
  ⇒ **IPv4 点分字面量完全不经自定义 Dns**（带括号 IPv6 `[::1]` 不匹配 `VERIFY_AS_IP_ADDRESS` regex、仍经 Dns）。
  同时第二轮确证两项**削弱**后果的事实：跨主机重定向时 OkHttp `RetryAndFollowUpInterceptor.buildRedirectRequest` **剥离 Authorization**（本项目零网络 Interceptor、凭据为 `Request.Builder.header` 静态头，剥离照常生效）⇒ 凭据外泄路径闭合；
  响应体若被当作库收下则解密必败，但**基线推进不解密**（`SyncEngine.kt:162-171` writeCache+advanceBaseAndPersist+recordAccepted 均在解密校验前，第二轮核实）——该半边属威胁建模 §4.3/§5.3 已明示接受的同类数据混淆面，不另计。
- **攻击路径（收敛后的残余）**：恶意/被接管同步端点回 `302 → https://<攻击者公网 IPv4>/…`——两层 SSRF 防线（构造期校验只管配置端点、Dns 防线被字面量旁路）全部落空，仅 NSC 证书链约束存活；
  攻击者可为公网 IP 出示合法证书 ⇒ 对本机发起一次携带请求头（无凭据）的探测并回收响应；内网 IPv4 字面量（`169.254.169.254`/`10.x`）则被「保留地址无公网 CA 证书」挡住，**只剩一层防线而非两层**。定 P2：安全声明与生效面不符 + 纵深降级，非直接泄密。
- **涉及文件**：`sync/.../network/SyncHttpClientFactory.kt`、`SyncEndpointGuard.kt`、webdav/s3 Provider。
- **验收标准**：① 修复二选一：`followRedirects(false)` + Provider 对 `Location` 复用 `validateEndpointHost` 自行裁决（最小改动，同时消灭旁路面）；或对**连接期目标地址**复核 `isBlockedAddress`；② MockWebServer 断言两组：`302→内网主机名` 与 `302→https://127.0.0.1` / `https://169.254.169.254`，**修复前记录现状仅 TLS 拦住**、修复后断言连接期即拒；③ test 全绿。
- **第三轮终裁（2026-09-19，四轮循环对抗收敛 + 上游源码级 + 真机取证）**：**机制坐实，维持 P2（下沿），正文三处更正**：
  ① **源码级坐实**（本机 `~/.gradle` 缓存 `okhttp-android-5.5.0-sources.jar` → `commonJvmAndroid/okhttp3/internal/connection/RouteSelector.kt:174-184`）：`if (socketHost.canParseAsIpAddress()) return listOf(Route(address, proxy, InetSocketAddress(InetAddress.getByName(socketHost), socketPort)))`，而 `val routes = dnsLookup(...)` **在其后**；DIRECT 分支 `socketHost = address.url.host` ⇒ IPv4 点分字面量**在 `dnsLookup` 之前返回**，完全不经自定义 Dns。**真机取证**：新用例 `SsrfRedirectBypassDeviceTest`（`sync/src/androidTest/`）断言（a）真实工厂客户端 `https://localhost:<port>/` → `UnknownHostException`、`https://127.0.0.1:<port>/` → `SSLException` 且**无** `UnknownHostException`；（b）`302 → http://127.0.0.1:<port>/` 时自定义 `Dns.lookup` **零调用**且请求抵达目标，而 `302 → http://localhost:<port>/`（对照组）被拦且 lookup 被记录——Redmi 4X 上 3 例全绿。
  ② **更正 `[::1]` 括注**：`VERIFY_AS_IP_ADDRESS = "([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)"` 的字符集**不含方括号**，而 `HttpUrl.host` 对 IPv6 返回**不带括号**的 `::1`，命中第一分支 ⇒ **IPv6 字面量同样旁路**（旁路面比原述**更宽**，非更窄）。
  ③ **「安全声明与生效面不符」应删除**：`SyncEndpointGuard.kt:26-27` 与 `SyncHttpClientFactory.kt:19-21` 均把该层限定为「主机名解析结果」，**声明范围 = 实际生效范围**；真实残余是「重定向面从未写进文档」（原 `ISSUE-P3-207` 承载，现并入本条）。定级理由相应收窄为「**防御纵深降级 / 控制旁路**」：连接期盲 SSRF（OkHttp **先 TCP 后 TLS**，SYN 可达内网字面量），无凭据外泄（跨主机重定向 `buildRedirectRequest` 剥离 Authorization，本项目零 Interceptor）、无数据外泄。
  ④ **AC① 技术更正**：原写「自定义 `SocketFactory` 对 `route.socketAddress.address` 复核 `isBlockedAddress`」**不可直接实现**——OkHttp 调**无参** `createSocket()` 后再 `.connect(addr)`，裸 `SocketFactory` 取不到目标地址；须改为**拦截 `connect()` 的 `Socket`**（首选：在 SYN 之前拦住，且覆盖全部跳），或 `followRedirects(false)` + Provider 复核 `Location`（次选：须同时处理 WebDAV base-URL 301/302 与 S3 307 重签的可用性回归）。
