package com.keepasskey.app.quality

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 算法 / 数据结构专项批次的**接线守卫**（`ISSUE-P3-162` / `P3-172` / `P3-173`）。
 *
 * 本批改动的验收判据是「**不再产生某类工作**」——源文件内不再有逐次新建的重对象、
 * 不再有全表线性扫描——而「某对象被新建了几次」对运行时用例不可观测。
 * 本仓既有先例（`AutofillAuthResultWiringTest` / `TotpPeriodWiringGuardTest` /
 * `RuntimeIntegrityDetectionSurfaceTest`）一律以**源码文本**作守卫，本类沿用同一口径。
 *
 * **断言前先剥离注释**（§116 的教训）：本批的就地 KDoc 为解释动机**刻意引用了旧写法**
 * （如「原 `sortedBy { it.title.lowercase() }`」），直接全文断言会把「解释为什么改」
 * 判成「没改」。剥离范围：`/* … */` 块注释与**整行** `//` 注释；行尾内联注释不剥离
 * （本类断言的所有旧写法均不位于行尾注释中，故剥离局限不影响判据）。
 *
 * **本类只锁定「接线未被改回」，不构成性能证据**——收益量级未经实测（见批次文档的边界声明）。
 */
class AlgoHotPathGuardsTest {

    @Test
    fun `自动填充字段扫描的 token 切分正则必须为对象级常量`() {
        val source = stripped(SCANNER)
        assertTrue(
            "切分正则必须提为对象级常量（原实现写在 tokensOf 函数体内 ⇒ 每次调用重新编译 Pattern，" +
                "而 scan 对每个节点最多触发 4 次）",
            source.contains("private val TOKEN_SPLIT_REGEX = Regex(")
        )
        assertFalse(
            "不得残留函数内现编译正则的写法",
            source.contains("split(Regex(")
        )
    }

    @Test
    fun `列表页投影必须一次建索引且排序不得逐次小写化`() {
        val source = stripped(PROJECTION)
        assertTrue(
            "面包屑与回收站集合必须复用同一份分组索引",
            source.contains("groupsById") && source.contains("childrenByParent")
        )
        assertTrue("面包屑必须走索引查表 + 前插队列", source.contains("addFirst("))
        assertEquals(
            "两处名称排序都必须改用不敏感比较器（原 `sortedBy { it.title.lowercase() }` 的选择器" +
                "在每次比较中被调用）",
            2,
            Regex("String\\.CASE_INSENSITIVE_ORDER").findAll(source).count()
        )
        assertFalse(
            "排序选择器不得再逐次小写化",
            source.contains(".lowercase()")
        )
        assertFalse(
            "面包屑不得再用全表线性查找",
            source.contains("allGroups.find {")
        )
        assertFalse(
            "回收站后代不得再递归重扫全表（原实现按每个节点 `allGroups.filter { it.parentId == 传入参数 }`）",
            source.contains("allGroups.filter { it.parentId == parentId }")
        )
        assertFalse(
            "不得残留递归展开实现",
            source.contains("fun addDescendants(")
        )
        assertFalse(
            "面包屑不得再用 ArrayList 头插（逐元素搬移）",
            source.contains("breadcrumbs.add(0,")
        )
    }

    @Test
    fun `分组路径批量解析必须只建一次索引`() {
        val source = stripped(GROUP_PATH)
        assertTrue(
            "必须存在接收已建索引的私有实现（原 pathsOf 对每个分组各调一次 fullPathOf，" +
                "而后者每次 associeBy 重建全表）",
            source.contains("private fun pathOfIndexed(")
        )
        assertFalse(
            "pathsOf 不得再对每个分组调用「自建索引」的公开重载",
            source.contains("fullPathOf(groups, group.id)")
        )
    }

    @Test
    fun `WebDAV 响应解析的 DOM 工厂与日期格式必须缓存`() {
        val source = stripped(PROPFIND_PARSER)
        assertEquals(
            "DOM 工厂只允许在（按线程的）缓存初始化处新建一次",
            1,
            Regex("DocumentBuilderFactory\\.newInstance\\(\\)").findAll(source).count()
        )
        assertEquals(
            "HTTP 日期格式只允许在（按线程的）缓存初始化处新建一次",
            1,
            Regex("SimpleDateFormat\\(").findAll(source).count()
        )
        assertTrue(
            "缓存必须存在且每次解析从缓存取工厂（原实现每次响应重建工厂 + 逐项设 6 个特性）",
            source.contains("hardenedFactories") && source.contains("hardenedFactory()")
        )
    }

    @Test
    fun `KDBX XML 解析器的加固特性探测必须为进程级惰性缓存`() {
        val source = stripped(XML_PARSER)
        assertTrue(
            "探测结论必须惰性缓存一次（该类的实例是每次解析新建的，故缓存只能落在 companion）",
            source.contains("by lazy { probeHardenedFeatures() }")
        )
        assertTrue(
            "加固工厂必须按线程缓存（SAXParserFactory 非线程安全），且每次解析仍取全新 SAXParser",
            source.contains("hardenedFactories") &&
                source.contains("private fun buildHardenedParser(): SAXParser = hardenedFactory().newSAXParser()")
        )
    }

    @Test
    fun `OTP 引擎必须走查表而非线性查找与装箱`() {
        val source = stripped(OTP_ENGINE)
        assertTrue(
            "Base32 必须用反查表（原 ALPHABET.indexOf(Char) 是每字符 32 步线性扫描）",
            source.contains("DECODE_TABLE")
        )
        assertTrue(
            "10^n 必须用常量表（原每次取码一遍 10.0.pow）",
            source.contains("POW10") && source.contains("tenPow(digits)")
        )
        assertFalse(
            "不得残留字母表线性查找",
            source.contains("ALPHABET.indexOf(")
        )
        assertFalse(
            "Base32 输出缓冲不得逐字节装箱",
            source.contains("mutableListOf<Byte>")
        )
    }

    @Test
    fun `批量树操作必须走单趟剪枝而不得回退为逐条重走整树`() {
        val mutations =
            stripped("database/src/main/java/com/keepasskey/database/session/SessionContentMutations.kt")
        assertFalse(
            "批量删除 / 批量移动不得再对每个 id 各调一次 removeEntry（O(K × 节点数) 次整树遍历）",
            mutations.contains("currentRoot = SessionTreeEditor.removeEntry(currentRoot,")
        )
        assertTrue(
            "按 id 集合单趟剪枝的批量入口必须存在",
            stripped("database/src/main/java/com/keepasskey/database/session/SessionTreeEditor.kt")
                .contains("fun removeEntries(")
        )

        val conflict = stripped("app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt")
        assertTrue(
            "冲突决议必须收集后单趟落树",
            conflict.contains("applyResolvedEntriesToGroup(")
        )
        assertFalse(
            "不得残留「每条裁决各复制整棵树」的逐条实现",
            conflict.contains("applyResolvedEntryToGroup(")
        )
    }

    @Test
    fun `健康检查不得对同一份口令重复解密与重复哈希`() {
        val source = stripped("database/src/main/java/com/keepasskey/database/audit/HealthCheckEngine.kt")
        assertEquals(
            "SHA-256 只允许算一次——第二趟必须复用第一趟按条目 id 留档的哈希" +
                "（原实现对同一条口令解密 3 次、哈希 2 次）",
            1,
            Regex("HashUtil\\.sha256\\(passBytes\\)").findAll(source).count()
        )
    }

    @Test
    fun `内存驻留加密的加解密与等值标签原语必须按线程复用`() {
        val source = stripped("core/src/main/java/com/keepasskey/core/security/InMemoryCipher.kt")
        assertEquals(
            "Cipher 只允许在 ThreadLocal 初始化处新建一次（原每次 seal / unseal 各一次 provider 查找）",
            1,
            Regex("Cipher\\.getInstance\\(TRANSFORMATION\\)").findAll(source).count()
        )
        assertEquals(
            "等值标签 Mac 只允许在 ThreadLocal 初始化处新建一次",
            1,
            Regex("Mac\\.getInstance\\(MAC_ALGORITHM\\)").findAll(source).count()
        )
        assertTrue(
            "两处复用必须真的被取用（初始化了却每次新建等于没改）",
            source.contains("sealCiphers.get()") && source.contains("eqMacs.get()")
        )
    }

    @Test
    fun `自动填充评分不得逐条目重复解密与建 Map`() {
        val ranker = stripped("app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt")
        assertEquals(
            "url 属性只允许读一次（每次访问都是一次驻留密文解密 + String 物化）",
            1,
            Regex("val entryUrl = entry\\.url").findAll(ranker).count()
        )
        assertFalse(
            "isExactDomain 不得再接收 entry 并自行读一遍 url",
            ranker.contains("isExactDomain(entry,")
        )

        val passkey = stripped("core/src/main/java/com/keepasskey/core/model/PasskeyData.kt")
        assertTrue(
            "fromCustomFields 必须先做不解密、不建 Map 的形状短路（KPEX 与 v1 两套 schema 各三个必需键，" +
                "任一一套齐备即可解析）",
            passkey.contains("if (!kpexComplete && !legacyComplete) return null")
        )
        val preCheck = passkey
            .substringAfter("fun fromCustomFields")
            .substringBefore("val map = fields.associateBy")
        assertFalse(
            "形状短路本身不得解密（只允许扫 key 名）——否则短路就白做了",
            preCheck.contains("readString()")
        )
    }

    @Test
    fun `自动填充请求内的重复读取必须收敛`() {
        val builders = stripped("app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt")
        assertEquals(
            "调用方证书摘要只允许读一次（归属解析与包名维度绑定校验共用同一份快照）",
            1,
            Regex("autofillOriginResolver\\.callingAppCertDigests\\(").findAll(builders).count()
        )
        val resolver = stripped("app/src/main/java/com/keepasskey/app/autofill/AutofillOriginResolver.kt")
        assertTrue(
            "归属解析必须接受调用方传入的摘要快照（默认参数保持既有调用点零改动）",
            resolver.contains("certDigests: CallerCertDigests = callingAppCertDigests(callingPackage)")
        )
        assertFalse("不得残留逐字节 format 的 hex 生成", resolver.contains("\"%02X\".format("))

        assertFalse(
            "字段签名同样不得逐字节 format",
            stripped("app/src/main/java/com/keepasskey/app/autofill/AutofillFieldSignature.kt")
                .contains("\"%02x\".format(")
        )

        val keystore =
            stripped("app/src/main/java/com/keepasskey/app/autofill/KeystoreHmacFieldSignatureSource.kt")
        assertTrue(
            "Keystore 密钥句柄必须缓存（containsAlias + getEntry 都是 IPC 往返）",
            keystore.contains("private var cachedKeyEntry: KeyStore.SecretKeyEntry? = null")
        )
        assertEquals(
            "containsAlias 只允许出现在加载路径一处",
            1,
            Regex("containsAlias\\(KEY_ALIAS\\)").findAll(keystore).count()
        )
    }

    @Test
    fun `字段引用解析不得按每个引用重建整库扁平列表`() {
        val fieldRef =
            stripped("database/src/main/java/com/keepasskey/database/fieldref/FieldReferenceEngine.kt")
        assertEquals(
            "整树展平只允许出现一次（原实现在正则回调体内逐引用展平，且解析递归 ⇒ 每层各一次）",
            1,
            Regex("allEntries\\(\\)").findAll(fieldRef).count()
        )
        assertTrue(
            "该唯一展平点必须在惰性索引内（文本无引用时不得触发）",
            fieldRef.contains("by lazy { root.allEntries() }")
        )
        assertFalse(
            "正则回调内不得再现场线性扫描整树",
            fieldRef.contains("firstOrNull { entry ->")
        )
        assertTrue(
            "索引键必须用与 equalsIgnoreCase 同一套折叠的比较器（lowercase 归一化会在部分码点上漂移）",
            fieldRef.contains("TreeMap<String, KdbxEntry>(String.CASE_INSENSITIVE_ORDER)")
        )
    }

    @Test
    fun `列表页整库投影必须离开收集上下文`() {
        val vm = stripped("app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListViewModel.kt")
        assertTrue(
            "uiState 的整库投影（全库过滤 / 排序 / 面包屑 / 回收站 / 分组路径）必须补 `flowOn`——" +
                "`stateIn` 的收集上下文是 `viewModelScope`（Main），与数据层两条投影流（§117）同口径",
            Regex("\\.flowOn\\(displayDispatcher\\)\\s*\\n\\s*\\.stateIn\\(").containsMatchIn(vm)
        )
    }

    @Test
    fun `完整性探测必须字节级化且缓冲复用`() {
        val detector =
            stripped("app/src/main/java/com/keepasskey/app/security/RuntimeIntegrityDetector.kt")
        assertFalse(
            "maps 扫描不得再逐行解码成 String（useLines / 逐行 contains(ignoreCase)）",
            detector.contains("useLines") || detector.contains("line.contains(")
        )
        assertTrue("必须走流式字节匹配", detector.contains("internal fun containsHookMarker("))
        assertTrue(
            "块间必须保留重叠窗口（否则跨块特征串会漏报）",
            detector.contains("arraycopy(chunk, filled - overlap")
        )

        val probe = stripped("app/src/main/java/com/keepasskey/app/security/TracedProcessProbe.kt")
        assertTrue(
            "读取缓冲必须按线程复用（原每次调用新分配 8 KiB 并物化整份 status）",
            probe.contains(
                "private val statusBuffers = ThreadLocal.withInitial { ByteArray(MAX_STATUS_BYTES) }"
            )
        )
        assertFalse(
            "不得再把整份 status 物化成 String",
            probe.contains("String(buffer, 0, read, Charsets.UTF_8)")
        )
        assertTrue("必须走字节级解析重载", probe.contains("ProcTracerPid.parse(buffer, read)"))
        assertEquals(
            "字节级与字符串两个入口必须共用同一取值实现（否则两者会漂移）",
            1,
            Regex("raw\\.trim\\(\\)\\.toIntOrNull\\(\\)").findAll(probe).count()
        )
    }

    @Test
    fun `大集合渲染必须惰性且过滤不得留在组合期`() {
        val dialogs =
            stripped("app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListDialogs.kt")
        assertTrue(
            "分组选择必须改惰性列表并加高度上限（AlertDialog 的 text 槽自身不滚动，" +
                "原实现把全部分组铺进 Column ⇒ 超出屏幕的部分无法触达）",
            dialogs.contains("LazyColumn(") &&
                dialogs.contains("modifier = Modifier.heightIn(max = 280.dp)")
        )
        assertFalse(
            "不得再用 forEach 把全部分组铺进 Column",
            dialogs.contains("allGroups.filter { !it.isRecycleBin }.forEach {")
        )

        val edit =
            stripped("app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditFormSections.kt")
        assertFalse(
            "编辑页不得把整表过滤写在 items 实参里（每次重组都会重跑）",
            edit.contains("items(uiState.availableGroups.filter")
        )
        assertTrue("必须补 key 以稳定复用项", edit.contains("items(selectableGroups, key = { it.id })"))

        val components =
            stripped("app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListComponents.kt")
        assertTrue("面包屑必须补 key", components.contains("items(breadcrumbs, key = { it.id })"))

        val debug = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/DebugSettingsScreen.kt"
        )
        assertTrue(
            "日志等级色必须按日志内容预计算（原逐行最多 4 次 contains 会在每次重组重跑）",
            debug.contains("val coloredLines = remember(logLines) {") &&
                !debug.contains("logLines.forEach { line ->")
        )
    }

    @Test
    fun `秒级节拍不得常驻且不得逐条重建`() {
        val detail =
            stripped("app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailViewModel.kt")
        assertTrue(
            "详情页节拍必须挂在 uiState 的订阅期上（原在 init 里常驻启动，退到后台栈仍每秒唤醒一次）",
            detail.contains("private var totpTickJob: Job? = null") &&
                detail.contains(".onCompletion { totpTickJob?.cancel() }")
        )
        assertFalse(
            "节拍不得再在 init 里常驻启动",
            detail.substringAfter("init {").substringBefore("\n    }").contains("totpTicker.run")
        )

        val auth = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorViewModel.kt"
        )
        assertFalse(
            "验证器页不得再逐条挂起调用（每拍 × 每条目一次 calculateEntryTotp）",
            auth.contains("vaultRepository.calculateEntryTotp(entry.id)")
        )
        assertFalse(
            "验证器页不得再把秒级 tick 并入整页 combine（ISSUE-P3-182：每拍重建整份 items）",
            auth.contains("timerSecondsFlow")
        )
        assertTrue(
            "验证码刷新必须交给共享的周期边界通道（ISSUE-P3-175 ② / ISSUE-P3-182）",
            auth.contains("TotpCountdownTracker(")
        )
        assertTrue(
            "倒计时与实时码必须走窄通道，不得留在整页状态里",
            auth.contains("totpTracker.nowSeconds") && auth.contains("totpTracker.liveCodes")
        )

        val tracker =
            stripped("app/src/main/java/com/keepasskey/app/ui/model/TotpCountdownTracker.kt")
        assertTrue(
            "批量通道必须仍在该通道内（一次会话读取 + 一次条目索引）",
            tracker.contains("vaultRepository.calculateEntryTotps(")
        )
        assertTrue(
            "周期集合必须随条目快照缓存（原每拍重建 filter + map + Set 三个中间集合）",
            tracker.contains("if (entries !== periodsSnapshot) {")
        )
    }

    @Test
    fun `验证器页卡片状态不得随秒级节拍重建`() {
        val state = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorUiState.kt"
        )
        assertFalse(
            "卡片状态不得再携带 remainingSeconds（每秒都变 ⇒ 整页 combine 每拍重建）",
            state.contains("remainingSeconds")
        )
        assertTrue(
            "验证码格式化必须只有一份实现（ViewModel 与卡片共用）",
            state.contains("internal fun formatTotpCode(")
        )

        val screen = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorScreen.kt"
        )
        assertTrue(
            "卡片必须按窄通道刻度 + 条目自身周期现算剩余秒数",
            screen.contains("OtpEngine.getRemainingSeconds(") && screen.contains("nowSeconds.value")
        )
        assertTrue(
            "验证码必须优先取窄通道的实时码，缺失时回落投影码",
            screen.contains("liveCodes.value[item.entryId] ?: item.codeRaw")
        )
        assertTrue("显示文本必须经共用格式化函数", screen.contains("formatTotpCode("))
    }

    @Test
    fun `同步冲突合并的本地侧必须直取内存树`() {
        val controller = stripped(
            "app/src/main/java/com/keepasskey/app/sync/SyncConflictController.kt"
        )
        assertTrue(
            "本地侧必须支持由调用方传入内存树（ISSUE-P3-168 ①：省去一次「解析 localBytes 回树」" +
                "＝一次 KDF + 一次整树构建）",
            controller.contains("localDbOverride ?: codec.parseKdbxBytes(localBytes)")
        )
        assertTrue(
            "擦除边界（P0）：传入的内存快照不得被 wipeDiscarded 擦除——擦它即静默清空活动库",
            controller.contains("val localDbOwned = localDbOverride == null") &&
                controller.contains("if (localDbOwned) wipeDiscarded(localDb)")
        )
        assertEquals(
            "每一处 wipeDiscarded(localDb) 都必须由 localDbOwned 判据把关（裸调用＝P0 隐患）",
            Regex("wipeDiscarded\\(localDb\\)").findAll(controller).count(),
            Regex("if \\(localDbOwned\\) wipeDiscarded\\(localDb\\)").findAll(controller).count()
        )

        // §155：两条合并入口的其中一处（handleConflictDetected）已下沉到同包分支文件，
        // 故按**两文件并集**扫描——计数判据仍为 2，不放宽强度（AGENTS.md §3 测试资产纪律）。
        val runner = listOf(stripped(RUNNER), stripped(RUNNER_REMOTE_OUTCOMES)).joinToString(separator = " ")
        assertEquals(
            "两处冲突合并入口（快速提交 / openRemote）都必须传入内存树快照" +
                "（ISSUE-P3-188：合并段下沉后经 RemoteSyncContext 取值，故允许 `ctx.` 前缀；" +
                "§155 分支文件下沉后改按门面 + 分支文件并集计数）",
            2,
            Regex("localDbOverride = (ctx\\.)?localDbSnapshot").findAll(runner).count()
        )
        assertTrue(
            "快照必须取本周期起点的会话树（currentDb），而不是在合并内重读 flow（UI 写路径不取同步锁）",
            Regex("localDbSnapshot = (ctx\\.)?currentDb,").containsMatchIn(runner)
        )
    }

    @Test
    fun `整库投影流必须共享给多处消费者`() {
        val vm = stripped("app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListViewModel.kt")
        assertEquals(
            "列表页两条整库投影流必须各 shareIn 一次后共享（原 uiState 与装饰装配各订阅一次，" +
                "而仓库侧是冷流 ⇒ 每次数据变更做两份整库投影）",
            2,
            Regex("shareIn\\(viewModelScope, SharingStarted\\.WhileSubscribed\\(5000\\), replay = 1\\)")
                .findAll(vm).count()
        )
        assertFalse(
            "combine 内不得再直接订阅冷流",
            vm.contains("combine(vaultRepository.getDatabases(), vaultRepository.getGroups())") ||
                vm.contains("\n        vaultRepository.getEntries(),")
        )

        val provider = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListDecorationsProvider.kt"
        )
        assertFalse(
            "装饰装配不得自行再订阅条目 / 分组投影（须消费传入的共享流）",
            provider.contains("vaultRepository.getEntries()") ||
                provider.contains("vaultRepository.getGroups()")
        )

        val assembler = stripped(
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailStateAssembler.kt"
        )
        assertEquals(
            "详情页当前条目投影只允许订阅一次（原 getEntry(id) 有三处消费者）",
            1,
            Regex("vaultRepository\\.getEntry\\(id\\)").findAll(assembler).count()
        )
        assertEquals(
            "详情页分组投影只允许订阅一次（原 getGroups() 有两处消费者）",
            1,
            Regex("vaultRepository\\.getGroups\\(\\)").findAll(assembler).count()
        )
    }

    @Test
    fun `导航图不得捕获整个设置状态`() {
        val graph = stripped("app/src/main/java/com/keepasskey/app/ui/KeePasskeyNavGraph.kt")
        assertFalse(
            "导航图不得再接收整个 SettingsUiState（NavHost 以 remember(builder) 建图 ⇒ " +
                "任一无关偏好变化都会整图 createGraph）",
            graph.contains("appSettings: SettingsUiState")
        )
        assertTrue(
            "应改为收窄的 AppThemeMode（枚举，稳定 ⇒ builder lambda 可被记忆化）",
            graph.contains("themeMode: AppThemeMode")
        )
        assertTrue(
            "调用点必须只传窄字段",
            stripped("app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt")
                .contains("themeMode = appSettings.themeMode,")
        )
    }

    private fun stripped(path: String): String = stripCommentsOnly(readSource(path))
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCANNER = "app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt"
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListProjection.kt"
        const val GROUP_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/GroupPathPresenter.kt"
        const val PROPFIND_PARSER =
            "sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt"
        const val XML_PARSER =
            "database/src/main/java/com/keepasskey/database/xml/KdbxXmlParser.kt"
        const val OTP_ENGINE = "core/src/main/java/com/keepasskey/core/otp/OtpEngine.kt"
        const val RUNNER = "app/src/main/java/com/keepasskey/app/sync/SyncCycleRunner.kt"
        const val RUNNER_REMOTE_OUTCOMES =
            "app/src/main/java/com/keepasskey/app/sync/SyncCycleRemoteOutcomes.kt"


        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
