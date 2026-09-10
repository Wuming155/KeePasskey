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
 */
@HiltViewModel
class AutofillPickerViewModel @Inject constructor(
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _entries = MutableStateFlow<List<KdbxEntry>>(emptyList())

    /** 库内条目光栅（Core 层直出，密码仍为 [com.keepasskey.core.security.ProtectedString] 密文态） */
    val entries: StateFlow<List<KdbxEntry>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            _entries.value = try {
                vaultRepository.getKdbxEntries()
            } catch (t: Throwable) {
                AppLog.e(TAG, "读取库内条目失败，选择器按空列表处理", t)
                emptyList()
            }
        }
    }

    fun search(query: String): List<KdbxEntry> = AutofillEntrySearch.filter(_entries.value, query)

    /**
     * 按需解密单个条目的用户名与密码（用户显式选中后调用）。
     *
     * 安全约定：
     * - 密码经 [VaultRepository.getEntryPasswordChars] 取得 CharArray 副本，转出 String 后
     *   **立即清零**原数组；
     * - 返回值为 String 是 Android 自动填充 API 的硬约束（[android.view.autofill.AutofillValue]
     *   只接受 CharSequence），不可擦除 String 的既有缺口见 ISSUE-P2-15——
     *   其生命周期被压缩到「构建 Dataset → 回传 → 出栈」这一段，不落任何状态流/日志/成员变量。
     */
    suspend fun resolveCredentials(entryId: String): Credentials? {
        if (entryId.isBlank()) return null
        val username = _entries.value
            .firstOrNull { it.id.toHexString() == entryId }
            ?.userName
            .orEmpty()

        val chars = try {
            vaultRepository.getEntryPasswordChars(entryId)
        } catch (t: Throwable) {
            AppLog.e(TAG, "按需解密选中条目密码失败", t)
            null
        } ?: return Credentials(username, "")

        val password = try {
            vaultRepository.resolveFieldReferences(entryId, String(chars)) ?: String(chars)
        } catch (t: Throwable) {
            AppLog.w(TAG, "解析字段引用失败，按原值下发", t)
            String(chars)
        } finally {
            chars.fill('0')
        }
        return Credentials(username = username, password = password)
    }

    data class Credentials(val username: String, val password: String)

    private companion object {
        const val TAG = "AutofillPickerVM"
    }
}
