package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISSUE-P2-88 回归：条目凭据取值**不得**只依赖本 VM 的缓存。
 *
 * ## 缺陷形态（真机定位）
 *
 * 本 VM 的条目缓存由 `init` **异步**填充，而 VM 是**按需创建**的——二次确认页
 * （[AutofillConfirmActivity]）在用户点「确认填充」时才首次访问它。那一刻缓存尚未就绪，
 * 若用户名只从缓存取，就会拿到空用户名字符串：回传数据集里只剩口令字段。
 * 真机留痕即此形态（口令 12 字符写入成功、账号框始终为空）。
 *
 * ## 判据
 *
 * 首份快照为空（模拟缓存未就绪）时，`resolveCredentials` 必须回退到仓库快照取用户名；
 * 凭据内容仍只经既有通道（口令走 [VaultRepository.getEntryPasswordChars]）。
 */
class AutofillPickerViewModelCredentialLookupTest {

    @Test
    fun `缓存未就绪时用户名必须回退到仓库快照`() = runBlocking {
        val entryId = KdbxUuid.random()
        val entry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.USER_NAME to ProtectedString(TEST_USERNAME, isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(TEST_PASSWORD, isProtected = true)
            )
        )
        // 首次调用（VM init 拉取缓存）返回空列表，之后返回真实条目——
        // 复现「VM 按需创建、缓存尚未就绪」这一真机形态
        var snapshotCalls = 0
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> {
                snapshotCalls++
                return if (snapshotCalls == 1) emptyList() else listOf(entry)
            }

            override suspend fun getEntryPasswordChars(entryId: String): CharArray? =
                TEST_PASSWORD.toCharArray()
        }

        val viewModel = AutofillPickerViewModel(repository)
        // 等 init 的异步拉取发生一次（缓存被填成「空列表」——即未就绪形态）。
        // 不等待就调用会在竞态下由 resolveCredentials 消费掉第 1 次快照（假红）。
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (snapshotCalls == 0 && waited < 5_000) {
                Thread.sleep(10)
                waited += 10
            }
        }
        assertEquals("测试前提：缓存首份快照为空（模拟未就绪）", emptyList<KdbxEntry>(), viewModel.entries.value)

        val credentials = viewModel.resolveCredentials(entryId.toHexString())

        assertEquals("缓存未命中时必须回退仓库快照取用户名", TEST_USERNAME, credentials?.username)
        assertEquals("口令仍走既有按需解密通道", TEST_PASSWORD, credentials?.password)
    }

    private companion object {
        // 虚构测试值（敏感纪律：不得使用真实凭据）
        const val TEST_USERNAME = "issue-p2-88-user"
        const val TEST_PASSWORD = "IssueP288#2026"
    }
}
