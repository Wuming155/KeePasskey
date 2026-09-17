package com.keepasskey.app.autofill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** ISSUE-P2-52：锁定即清空缓存活树（清零后的条目任何字段读取都会抛异常） */
    private val sessionLockObserver = com.keepasskey.core.session.SessionLockObserver {
        _entries.value = emptyList()
    }

    /** ISSUE-P3-148：整库装载只允许发生一次（幂等），且只由选择器页发起 */
    private var entriesLoadStarted = false

    init {
        // ISSUE-P2-52：注册会话锁定观察者（须在 [sessionLockObserver] 声明之后）
        databaseSession?.addLockObserver(sessionLockObserver)
        // ISSUE-P3-148：**刻意不在 init 装载整库**——确认页（按需创建本 VM）不需要全库，
        // 而 init 装载会让每次「确认填充」都付一次与库规模成正比的成本。需要全库的
        // 选择器页显式调用 [loadEntries]。
    }

    /**
     * ISSUE-P3-148：**选择器页专用**——装载整库非敏感投影供搜索。
     *
     * 幂等：重复调用不重复装载（与整改前的 `init` 装载同语义——选择器为一次性 Activity，
     * 锁定清空后重开即新建 VM 并重新拉取）。确认路径**不得**调用本方法。
     */
    fun loadEntries() {
        if (entriesLoadStarted) return
        entriesLoadStarted = true
        viewModelScope.launch {
            _entries.value = try {
                vaultRepository.getKdbxEntries()
            } catch (t: Throwable) {
                AppLog.e(TAG, "读取库内条目失败，选择器按空列表处理", t)
                emptyList()
            }
        }
    }

    override fun onCleared() {
        databaseSession?.removeLockObserver(sessionLockObserver)
        super.onCleared()
    }

    fun search(query: String): List<KdbxEntry> =
        // ISSUE-P2-52：锁定竞态下缓存条目可能已清零（title/userName 读取抛异常）→ 按空结果处理
        runCatching { AutofillEntrySearch.filter(_entries.value, query) }
            .onFailure { AppLog.w(TAG, "选择器检索命中已清零条目，按空结果处理", it) }
            .getOrDefault(emptyList())

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
        // ISSUE-P2-52：用户名读取 fail-safe——锁定竞态窗口内条目可能已清零
        // （readString 抛 IllegalStateException），按空用户名降级而非崩溃
        val username = runCatching {
            cachedUsername(entryId)
                ?: vaultRepository.getKdbxEntry(entryId)?.userName.orEmpty()
        }.getOrDefault("")

        val chars = try {
            vaultRepository.getEntryPasswordChars(entryId)
        } catch (t: Throwable) {
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
            AppLog.w(TAG, "解析字段引用失败，按原值下发", t)
            String(chars)
        } finally {
            chars.fill('0')
        }
        return Credentials(username = username, password = password)
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
