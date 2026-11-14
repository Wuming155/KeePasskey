package com.keepasskey.app.autofill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.coroutines.safeLaunch
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 自动填充「手动选择器」ViewModel（ISSUE-P3-40）。
 *
 * 职责：一次性拉取库内条目（**仅非敏感投影**，不含密码）供搜索，并在用户选中后
 * **按需单条解密**该条目的密码（零秘密热路径：列表与搜索期间不解密任何密码）。
 *
 * ### ISSUE-P2-52（审计 F-22）：会话状态观察 + 空读 fail-safe
 * - **缓存的是「活」`KdbxEntry` 树**：锁定时 `DatabaseSession` 会对库树 `ProtectedString`
 *   就地清零（**先清零、后通知观察者**，顺序见 `DatabaseSession.lock()`），本 VM 缓存的
 *   活树在锁定后任何 `title` / `userName` / `url` 读取都会抛 `IllegalStateException`。
 *   故本 VM 注册 [com.keepasskey.core.session.SessionLockObserver]：锁定即清空缓存列表
 *   （选择器为一次性 Activity，解锁后重开即重新拉取，无需「解锁重载」）。
 * - **空读 fail-safe**：观察者清空列表与 `clearSensitiveData()` 之间存在固有竞态窗口
 *   （清零在前、通知在后），选中回调可能读到已清零条目——`search` 与
 *   `resolveCredentials` 的非敏感字段读取一律 `runCatching` 兜底（空结果 / 空用户名），
 *   绝不让锁定竞态演变为选择器崩溃。
 * - **不削弱** `ProtectedString.clear()`（就地清零是该设计的负载承载点）——本整改只调整
 *   缓存持有与读取侧容错，不触碰清零语义。
 *
 * ### ISSUE-P3-148：整库装载改**惰性**，且不再是确认路径的取数通道
 * 本 VM 有两条**成本面完全不同**的调用路径，此前被同一段 `init` 装载逻辑绑在一起：
 * - **选择器页**（[AutofillPickerActivity]）：需要全库做搜索，整库非敏感投影是其**固有开销**；
 * - **确认页**（[AutofillConfirmActivity]）：由 `EXTRA_ENTRY_ID` 指向**单条**，却因复用本 VM
 *   而被动触发整库装载，缓存未命中时还要再装一次 ⇒ 大库上每次确认多付 1~2 次与规模成正比的开销。
 *
 * 故整库装载**不再于 `init` 自动发生**，改由选择器页显式调用 [loadEntries]；确认路径只经
 * [VaultRepository.getKdbxEntry] 按 id 取单条，**从头到尾不触碰整库投影**。
 * 缓存命中仍优先（选择器场景免一次仓库查询），未命中才落到单条查询。
 */
@HiltViewModel
class AutofillPickerViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    // ISSUE-P2-52：会话锁定观察者注册点（null 仅用于纯 JVM 单测）
    private val databaseSession: com.keepasskey.database.session.DatabaseSession? = null
) : ViewModel() {

    private val _entries = MutableStateFlow<List<KdbxEntry>>(emptyList())

    /** 库内条目光栅（Core 层直出，密码仍为 [com.keepasskey.core.security.ProtectedString] 密文态） */
    val entries: StateFlow<List<KdbxEntry>> = _entries.asStateFlow()

    /** ISSUE-P3-148：整库装载只允许发生一次（幂等），且只由选择器页发起 */
    private var entriesLoadStarted = false

    /** ISSUE-P2-52：锁定即清空缓存活树（清零后的条目任何字段读取都会抛异常） */
    private val sessionLockGuard = com.keepasskey.database.session.SessionLockGuard(databaseSession) {
        _entries.value = emptyList()
    }

    init {
        // ISSUE-P2-52：注册会话锁定观察者（须在 [sessionLockGuard] 声明之后）
        sessionLockGuard.register()
        // ISSUE-P3-148：**刻意不在 init 装载整库**——确认页（按需创建本 VM）不需要全库，
        // 而 init 装载会让每次「确认填充」都付一次与库规模成正比的成本。需要全库的
        // 选择器页显式调用 [loadEntries]。
        observeTreeChanges()
    }

    /**
     * 订阅会话快照流：会话树换代时即时重载缓存，避免长期持有失效快照。
     *
     * ENG-20261028-11：读租约仅在装载/重载读取瞬间持有（[vaultRepository.withUsableKdbxEntries]），
     * **绝不将租约常驻为成员变量跨用户等待**，彻底消除用户停留在选择器界面期间全进程回收被阻断的风险。
     */
    private fun observeTreeChanges() {
        val session = databaseSession ?: return
        viewModelScope.safeLaunch("AutofillPickerViewModel") {
            var seenFirst = false
            session.databaseFlow.collect { db ->
                // 首个 emission 是当前已装载的那一代，跳过（避免与 loadEntries 重复装载）
                if (!seenFirst) {
                    seenFirst = true
                    return@collect
                }
                if (!entriesLoadStarted) return@collect
                // **锁定 / 关库不得重载**：此时会话已把库树清零，缓存必须保持为空
                // （`sessionLockGuard` 已清空，这里只是防止重载把它再填回来）。
                if (db == null || vaultRepository.isLocked()) return@collect
                reloadForNewTree()
            }
        }
    }

    /** 会话树已换代：按新快照重载缓存。装载在瞬时作用域租约内完成，映射为非敏感不可变投影后释放租约。 */
    private suspend fun reloadForNewTree() {
        val lease = vaultRepository.acquireEntriesReadLease()
        val loaded = try {
            val list = vaultRepository.getUsableKdbxEntries()
            list.map(::toImmutableProjection)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e(TAG, "库树换代后重载条目失败，选择器按空列表处理", t)
            null
        } finally {
            lease.close()
        } ?: return
        _entries.value = loaded
    }

    /**
     * ISSUE-P3-148：**选择器页专用**——装载整库非敏感投影供搜索。
     *
     * 幂等：重复调用不重复装载（与整改前的 `init` 装载同语义——选择器为一次性 Activity，
     * 锁定清空后重开即新建 VM 并重新拉取）。确认路径**不得**调用本方法。
     *
     * ENG-20261028-11：装载走瞬时租约，在租约内读取可用条目并映射为非敏感不可变投影，
     * 随后立即释放租约，绝不持租约跨用户交互与等待期持有。
     */
    fun loadEntries() {
        if (entriesLoadStarted) return
        entriesLoadStarted = true
        viewModelScope.safeLaunch("AutofillPickerViewModel") {
            val lease = vaultRepository.acquireEntriesReadLease()
            try {
                // ISSUE-P2-341：选择器列的是"可交给调用方的凭据"，回收站内的须排除
                val list = vaultRepository.getUsableKdbxEntries()
                _entries.value = list.map(::toImmutableProjection)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "读取库内条目失败，选择器按空列表处理", t)
                _entries.value = emptyList()
            } finally {
                lease.close()
            }
        }
    }

    /**
     * 将库内活跃条目映射为选择器专用的非敏感不可变投影。
     *
     * 边界：
     * 1. 标题 / 用户名 / 网址等非敏感元数据以独立不可变 String 存入全新 [ProtectedString]（isProtected=false）；
     * 2. 不缓存密码字段，不与数据库会话内部树共享任何敏感 [ProtectedString] 引用；
     * 3. 会话后续改树或就地擦除绝不会使本投影字段失效，搜索与列表渲染无须依赖 clear 降级；
     * 4. 用户点选后由 [resolveCredentials] 在读租约内向活动库重新定位取密；
     * 5. 仅保留 KeePassDX `AndroidApp` **明文包名键**（独立新建实例）供「该包名已绑定」去重；
     *    受保护的签名字段与其它自定义字段一概不进投影，不物化任何受保护值。
     */
    private fun toImmutableProjection(entry: KdbxEntry): KdbxEntry = KdbxEntry(
        id = entry.id,
        parentGroupId = entry.parentGroupId,
        iconId = entry.iconId,
        customIconId = entry.customIconId,
        tags = entry.tags,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(runCatching { entry.title }.getOrDefault(""), isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(runCatching { entry.userName }.getOrDefault(""), isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(runCatching { entry.url }.getOrDefault(""), isProtected = false)
        ),
        // 保留 KeePassDX 应用关联的**明文包名键**，供「该包名已绑定」的幂等去重判定。
        // 受保护的签名字段刻意不进投影：签名摘要不参与去重，且投影不得物化受保护值。
        customFields = entry.customFields
            .filter { cf ->
                cf.key == AndroidAppBindingFields.APPLICATION_ID_FIELD_NAME ||
                    cf.key.startsWith(AndroidAppBindingFields.APPLICATION_ID_FIELD_NAME + "_")
            }
            .mapNotNull { cf ->
                runCatching {
                    KdbxCustomField(
                        cf.key,
                        ProtectedString(cf.value.readStringForDisplay(), isProtected = false)
                    )
                }.getOrNull()
            }
    )

    override fun onCleared() {
        sessionLockGuard.unregister()
        super.onCleared()
    }

    fun search(query: String): List<KdbxEntry> =
        // 投影条目持有独立非敏感字符串，检索不依赖锁定清零降级
        runCatching { AutofillEntrySearch.filter(_entries.value, query) }
            .getOrDefault(emptyList())

    // ---------- ISSUE-P3-571 方案A：「记住这个应用？」询问（Kp2a 式，随库持久通道） ----------

    private val _bindingWriteBackAsk = MutableStateFlow<AppBindingWriteBackAsk?>(null)

    /** 待决询问；null = 无（选择器页渲染 [AppBindingWriteBackDialog]，按钮回调走 [completeBindingWriteBack]） */
    val bindingWriteBackAsk: StateFlow<AppBindingWriteBackAsk?> = _bindingWriteBackAsk.asStateFlow()

    private var pendingBindingDecision: CompletableDeferred<Boolean>? = null

    /**
     * 交付链内询问「是否把调用方应用关联写进条目自定义字段」（KeePassDX 口径）。
     *
     * 判定口径见 [AutofillAppBindingWriteBackPolicy]：只读 / 包名非法 / 签名摘要不可读三条
     * fail-closed，外加「该包名已绑定」的幂等去重；条目已有网址、表单带自报域**不再否决**
     * （写入不触碰 `URL`）。行为：
     * - 不满足询问条件 → 不问，返回 true（照常交付）；
     * - 满足 → 置起 [bindingWriteBackAsk] 并挂起等待用户处置；拒绝 → 不写回，返回 true
     *   （拒绝不得持久化为「永久拒绝」）；同意 → 复核锁定后经
     *   [VaultRepository.addAppBinding] 写入 `AndroidApp` / `AndroidApp Signature`
     *   （只改自定义字段、失败如实日志、**不阻断交付**），并同步本地投影缓存使同一会话内不再重复询问；
     * - **询问期间会话可能自动锁定**（与交付链「回传前再次校验」同口径）：锁定即返回 false，
     *   调用方须丢弃未决响应（不写回、不得回传 RESULT_OK）。
     */
    suspend fun offerAppBindingWriteBack(
        callerDigests: CallerCertDigests,
        callerPackage: String,
        formWebDomain: String?,
        entryId: String,
        entryTitle: String
    ): Boolean {
        // ISSUE-P3-570：getKdbxEntry 为挂起调用，取消须沿链重抛（不归一为「空 URL」）
        // ENG-20261028-11：读 URL 与判断同处一条读租约（`withKdbxEntry`）——否则并发整树替换
        // 会把条目下线擦除，`runCatching` 把失败折叠成空 URL。URL 已不参与询问判定，
        // 此处保留读仅为把条目当前网址如实下传给策略签名（由用例锁定它不构成否决）。
        val currentUrl = runCatching {
            vaultRepository.withKdbxEntry(entryId) { entry -> entry?.url.orEmpty() }
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull().orEmpty()
        val proposedPackage = AutofillAppBindingWriteBackPolicy.proposedBindingPackage(callerPackage)
        val alreadyBound = isAlreadyBound(entryId, proposedPackage)
        if (!AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = callerPackage,
                callerDigestsReadable = !callerDigests.isEmpty,
                webDomain = formWebDomain,
                entryUrl = currentUrl,
                sessionReadOnly = vaultRepository.isSessionReadOnly(),
                alreadyBound = alreadyBound
            )
        ) {
            // 否决是静默的既有口径；留一条不含任何调用方 / 条目标识的痕迹，把「没询问」与
            //「弹窗未渲染 / 装机版本落后」区分开，并写明是哪几条门槛挡住的，不改变放行与写回行为。
            val reasons = buildList {
                if (vaultRepository.isSessionReadOnly()) add("只读会话")
                if (proposedPackage == null) add("包名非法")
                if (callerDigests.isEmpty) add("签名摘要不可读")
                if (alreadyBound) add("该包名已绑定")
            }
            AppLog.i(TAG, "应用关联询问未提出（${reasons.joinToString("、")}），按既有口径跳过（不写回）")
            return true
        }
        val normalizedPackage = proposedPackage.orEmpty()
        val decision = CompletableDeferred<Boolean>()
        pendingBindingDecision = decision
        _bindingWriteBackAsk.value = AppBindingWriteBackAsk(
            entryId = entryId,
            entryTitle = entryTitle,
            proposedPackage = normalizedPackage
        )
        val agreed = try {
            decision.await()
        } finally {
            _bindingWriteBackAsk.value = null
            pendingBindingDecision = null
        }
        if (!vaultRepository.isLocked()) {
            if (agreed) {
                val written = vaultRepository.addAppBinding(
                    entryId = entryId,
                    packageName = normalizedPackage,
                    fingerprintHex = callerDigests.primary.orEmpty()
                )
                if (written is KdbxResult.Success) {
                    rememberAppBindingInProjection(entryId, normalizedPackage)
                }
                AppLog.i(TAG, "应用关联写回条目自定义字段结果=${written is KdbxResult.Success}（不阻断交付）")
            }
            return true
        }
        AppLog.w(TAG, "会话在关联询问期间被锁定，丢弃未决响应（不回传 RESULT_OK）")
        return false
    }

    /**
     * 「该包名已绑定」判定：只读选择器自己的非敏感投影缓存（签名摘要不参与去重）。
     * 缓存未命中一律按未绑定处理——最坏结果是多问一次，不会漏写。
     */
    private fun isAlreadyBound(entryId: String, packageName: String?): Boolean {
        if (packageName == null) return false
        val entry = _entries.value.firstOrNull {
            it.id.toHexString().equals(entryId, ignoreCase = true)
        } ?: return false
        val values = entry.customFields.associate {
            it.key to runCatching { it.value.readStringForDisplay() }.getOrDefault("")
        }
        return AndroidAppBindingFields.nextPosition(
            AndroidAppBindingFields.parse(values), packageName
        ) == null
    }

    /**
     * 写入成功后把新绑定同步进投影缓存：使同一次会话内再次交付不再重复询问，
     * 无需等会话树换代事件（该事件在无会话观察者的路径上不保证及时到达）。
     * 只补明文包名键；受保护签名值刻意不进投影。
     */
    private fun rememberAppBindingInProjection(entryId: String, packageName: String) {
        _entries.value = _entries.value.map { entry ->
            if (!entry.id.toHexString().equals(entryId, ignoreCase = true)) return@map entry
            val values = entry.customFields.associate {
                it.key to runCatching { it.value.readStringForDisplay() }.getOrDefault("")
            }
            val position = AndroidAppBindingFields.nextPosition(
                AndroidAppBindingFields.parse(values), packageName
            ) ?: return@map entry
            val (appKey, _) = AndroidAppBindingFields.keysFor(position)
            entry.copy(
                customFields = entry.customFields +
                    KdbxCustomField(appKey, ProtectedString(packageName, isProtected = false))
            )
        }
    }

    /** 对话框按钮回传用户处置（true＝写入 / false＝拒绝或对话框被取消） */
    fun completeBindingWriteBack(agreed: Boolean) {
        pendingBindingDecision?.complete(agreed)
    }

    /**
     * 按需解密单个条目的用户名与密码（用户显式选中后调用）。
     *
     * 安全约定：
     * - 密码经 [VaultRepository.getEntryPasswordChars] 取得 CharArray 副本，转出 String 后
     *   **立即清零**原数组；
     * - 返回值为 String 是 Android 自动填充 API 的硬约束（[android.view.autofill.AutofillValue]
     *   只接受 CharSequence），不可擦除 String 的既有缺口见 ISSUE-P2-15——
     *   其生命周期被压缩到「构建 Dataset → 回传 → 出栈」这一段，不落任何状态流/日志/成员变量。
     *
     * ISSUE-P2-88：用户名**不得**只依赖本 VM 的条目缓存——缓存由选择器页的 [loadEntries] 异步填充，
     * 而本 VM 在确认页是**按需创建**的（二次确认页在用户点「确认填充」时才首次访问它），且确认路径
     * **刻意不做整库装载**，那一刻缓存必然为空，若用户名只从缓存取，回传数据集就会缺用户名
     * （真机实测：口令写入成功、账号框仍为空）。故缓存未命中时按 id 向仓库取**单条**快照
     * （ISSUE-P3-148：不再是整库装载）；锁定态下仓库同为空读 / 抛错，按空用户名降级（fail-safe 不变）。
     */
    suspend fun resolveCredentials(entryId: String): Credentials? {
        if (entryId.isBlank()) return null
        // ENG-20261028-11：整段（缓存用户名 → 单条快照用户名 → `{REF:}` 展开 → 取口令 → 口令展开）
        // 处于一条读租约内。这些读取分属多个挂起点且跨两个仓库入口；会话若在中间替换整树，
        // 本条目被下线擦除会让口令侧 fail-fast 抛错（交付面「凭据读取失败」）或用户名降级为空。
        val lease = vaultRepository.acquireEntriesReadLease()
        try {
            // ISSUE-P2-52：用户名读取 fail-safe——锁定竞态窗口内条目可能已清零
            // （readString 抛 IllegalStateException），按空用户名降级而非崩溃
            // ISSUE-P3-371 ②：用户名侧补 {REF:} 展开（与同函数 password 侧口径统一）——
            // 非口令消费点声明 USER_NAME 面，{REF:P@…} 经引擎掩码输出，口令明文不走用户名通道
            // ISSUE-P3-570：保护段含 getKdbxEntry / resolveFieldReferences 两个挂起调用，
            // 取消须沿链重抛（与下方 password 侧 catch 守卫同口径），不归一为空用户名
            val username = runCatching {
                val raw = cachedUsername(entryId)
                    ?: vaultRepository.getKdbxEntry(entryId)?.userName.orEmpty()
                vaultRepository.resolveFieldReferences(
                    entryId, raw,
                    com.keepasskey.database.fieldref.FieldReferenceEngine.RefField.USER_NAME
                ) ?: raw
            }.onFailure { if (it is CancellationException) throw it }.getOrDefault("")

            val chars = try {
                vaultRepository.getEntryPasswordChars(entryId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e(TAG, "按需解密选中条目密码失败", t)
                null
            } ?: return Credentials(username, "")

            val password = try {
                // ISSUE-P0-08：选择器按选取下发**口令**，声明口令消费点（P 面）——受保护引用按 KDBX 语义展开
                vaultRepository.resolveFieldReferences(
                    entryId, String(chars),
                    com.keepasskey.database.fieldref.FieldReferenceEngine.RefField.PASSWORD
                ) ?: String(chars)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w(TAG, "解析字段引用失败，按原值下发", t)
                String(chars)
            } finally {
                chars.fill('0')
            }
            return Credentials(username = username, password = password)
        } finally {
            lease.close()
        }
    }

    /**
     * 缓存内查用户名（命中返回该用户名，**未命中返回 null**——注意与「命中但用户名为空」区分，
     * 后者返回空串，不得触发仓库回查）。
     */
    private fun cachedUsername(entryId: String): String? =
        _entries.value.firstOrNull { it.id.toHexString() == entryId }?.userName

    data class Credentials(val username: String, val password: String) {
        /**
         * ISSUE-P2-68（审计 M6）：**覆写默认 `toString()`**——本类型**同时持有明文口令与用户名**，
         * 默认数据类实现会把两者整份展开（一次日志/异常插值即泄漏）。
         * 仅呈现长度，内容一律不物化。
         */
        override fun toString(): String =
            "Credentials(username=<redacted len=${username.length}>, password=<redacted len=${password.length}>)"
    }

    private companion object {
        const val TAG = "AutofillPickerVM"
    }
}
