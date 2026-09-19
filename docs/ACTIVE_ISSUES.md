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

## P2 中危缺陷与协议/测试缺口（3 项）

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

### ISSUE-P3-206：`SyncEngine` 下载→缓存未接已存在的 `writeCacheStreaming`——整份 128 MiB `ByteArray` 在缓存路径驻留

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

