package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ISSUE-P3-10 子项 2（ZT-21）签名计数器受控事务回归测试。
 *
 * 旧实现（读-改-写无 CAS）：先 `databaseFlow.first()` 取快照，再 `saveEntry` 各自加锁；
 * 两次并发断言可同时读到 N 并各自写回 N+1，丢失一次递增。
 * 现实现把「读取库内现值 → 计算目标值 → 替换条目」整体收口到
 * [DatabaseSession.updateDatabaseMeta]（会话 Mutex 内单次受控变换），并以
 * `库内现值 + 1` 为单调下界、以 [PasskeyData.MAX_SIGN_COUNT] 为钳制上界。
 *
 * ISSUE-P3-27 子项 2（并发签名计数器假说）：追加 32 路真实并发用例，收集
 * [PasskeyEntryCoordinator.incrementPasskeySignCount] 的全部返回值断言
 * **互不相同且恰为 1..N**（证伪「协调器内部并发递增会得到同一值」），
 * 并用例锁死唯一被证实的重复来源——调用方以锁外快照自行计算（见对应用例 KDoc）。
 */
class PasskeyEntryCoordinatorSignCountTest {

    private val groupId = KdbxUuid.random()
    private val entryId = KdbxUuid.random()

    private fun newSession(signCountText: String?): DatabaseSession {
        val customFields = mutableListOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString("example.com", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred-id", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("private-key", isProtected = true))
        )
        if (signCountText != null) {
            customFields.add(
                KdbxCustomField(
                    PasskeyData.FIELD_SIGN_COUNT,
                    ProtectedString(signCountText, isProtected = false)
                )
            )
        }
        val entry = KdbxEntry(
            id = entryId,
            parentGroupId = groupId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E", isProtected = false)),
            customFields = customFields
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(id = groupId, name = "Root", entries = listOf(entry))
        )
        return DatabaseSession().also { it.setDatabaseForTesting(db) }
    }

    private fun coordinatorOf(session: DatabaseSession): PasskeyEntryCoordinator =
        PasskeyEntryCoordinator(session, DebugLogBuffer()) { KdbxResult.Success(Unit) }

    /** 直接读库内现值（不经协调器，避免与被测逻辑共用同一路径） */
    private fun storedSignCount(session: DatabaseSession): Int {
        val entry = session.databaseFlow.value?.rootGroup?.allEntries()?.first { it.id == entryId }
        assertNotNull("目标条目必须仍在库内", entry)
        return PasskeyData.readSignCount(entry!!.customFields)
    }

    private fun signCountFieldCount(session: DatabaseSession): Int {
        val entry = session.databaseFlow.value?.rootGroup?.allEntries()?.first { it.id == entryId }
        return entry?.customFields?.count { it.key == PasskeyData.FIELD_SIGN_COUNT } ?: 0
    }

    @Test
    fun `写入超上界的入参被钳制且不溢出`() = runTest {
        val session = newSession(signCountText = null)

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), Int.MAX_VALUE)

        assertEquals(PasskeyData.MAX_SIGN_COUNT, storedSignCount(session))
        assertEquals(1, signCountFieldCount(session))
        // 库内现值继续递增一步仍不得为负（旧实现下 Int.MAX_VALUE + 1 回绕为负）
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.nextSignCount(storedSignCount(session)))
    }

    @Test
    fun `负值入参被钳制为合法非负计数器`() = runTest {
        val session = newSession(signCountText = "5")

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), -100)

        val stored = storedSignCount(session)
        assertEquals("不得写入负值，且不得让已推进的计数器回退", 6, stored)
    }

    @Test
    fun `计数器字段缺失时补写为受保护字段形态`() = runTest {
        val session = newSession(signCountText = null)

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), 1)

        assertEquals(1, storedSignCount(session))
        assertEquals("缺失字段应被补写且仅补写一次", 1, signCountFieldCount(session))
    }

    @Test
    fun `重复写入同一值不得使计数器回退`() = runTest {
        val session = newSession(signCountText = "10")
        val coordinator = coordinatorOf(session)

        coordinator.patchPasskeySignCount(entryId.toHexString(), 11)
        assertEquals(11, storedSignCount(session))

        // 并发场景下游可能带着过期快照回写较小值：库内计数必须单调不回退
        coordinator.patchPasskeySignCount(entryId.toHexString(), 3)
        assertEquals(12, storedSignCount(session))
    }

    @Test
    fun `并发递增不丢失更新`() = runTest {
        val session = newSession(signCountText = "0")
        val coordinator = coordinatorOf(session)

        // 真实并行：两个协程在 Default 线程池上同时进入 patch。
        // 受控变换在会话 Mutex 内串行化且以「库内现值 + 1」为单调下界，
        // 因此无论交错顺序如何，两次递增都必须恰好落账为 2；
        // 旧实现（调用方传入固定 newCount → 各自覆盖写）在此必然只剩 1。
        withContext(Dispatchers.Default) {
            listOf(
                async { coordinator.patchPasskeySignCount(entryId.toHexString(), 1) },
                async { coordinator.patchPasskeySignCount(entryId.toHexString(), 1) }
            ).awaitAll()
        }

        assertEquals("两次递增必须全部落账（无丢失更新）", 2, storedSignCount(session))
    }

    @Test
    fun `非法或未知条目 ID 不改写任何条目`() = runTest {
        val session = newSession(signCountText = "3")
        val coordinator = coordinatorOf(session)

        coordinator.patchPasskeySignCount("not-a-uuid", 99)
        coordinator.patchPasskeySignCount(KdbxUuid.random().toHexString(), 99)

        assertEquals(3, storedSignCount(session))
        assertFalse(session.databaseFlow.value!!.rootGroup.allEntries().isEmpty())
    }

    // ===== ISSUE-P3-27 子项 2：并发递增的返回值唯一性 与 调用方自算的重复面 =====

    @Test
    fun `32 路并发递增返回的计数器互不相同且严格递增`() = runTest {
        val session = newSession(signCountText = "0")
        val coordinator = coordinatorOf(session)
        val concurrency = 32

        // 真实并行：32 个协程在 Default 线程池上同时进入原子递增路径
        val applied = withContext(Dispatchers.Default) {
            List(concurrency) { async { coordinator.incrementPasskeySignCount(entryId.toHexString()) } }.awaitAll()
        }
        val values = applied.filterNotNull()

        assertEquals("每次并发递增都必须落账并回传落库值", concurrency, values.size)
        assertEquals("并发递增回传的计数器不得重复", concurrency, values.toSet().size)
        assertEquals(
            "回传值必须恰为 1..N 的严格递增序列（无重复、无丢失更新、无回退）",
            (1..concurrency).toList(),
            values.sorted()
        )
        assertEquals("库内终态必须等于最大回传值", concurrency, storedSignCount(session))
    }

    @Test
    fun `32 路并发递增经既有无返回值入口同样不丢失任何一次递增`() = runTest {
        // 生产路径（RealVaultRepository → patchPasskeySignCount）同样必须逐次落账：
        // 全部并发方传入同一个过期期望值 1，「库内现值 + 1」的单调下界仍应把它推进为 1..N
        val session = newSession(signCountText = "0")
        val coordinator = coordinatorOf(session)
        val concurrency = 32

        withContext(Dispatchers.Default) {
            List(concurrency) { async { coordinator.patchPasskeySignCount(entryId.toHexString(), 1) } }.awaitAll()
        }

        assertEquals("并发递增不得丢失任何一次", concurrency, storedSignCount(session))
    }

    @Test
    fun `以锁外快照自行计算计数器会产生重复值_断言路径必须使用协调器返回值`() = runTest {
        // 唯一被证实的重复来源在**调用方口径**：生产断言路径（PasskeyAssertionActivity）在锁外
        // 用进入断言前读到的 passkeyData.signCount 快照自行调用 PasskeyData.nextSignCount 写入
        // AuthenticatorData；并发断言各方持有同一快照 → 交给 RP 的 signCount 全部相同。
        // 本用例把该口径与协调器原子返回值口径并列，锁死「唯一安全的取值来源」。
        val session = newSession(signCountText = "7")
        val coordinator = coordinatorOf(session)
        val concurrency = 8
        val snapshot = storedSignCount(session)

        val callerSide = withContext(Dispatchers.Default) {
            List(concurrency) { async { PasskeyData.nextSignCount(snapshot) } }.awaitAll()
        }
        val coordinatorSide = withContext(Dispatchers.Default) {
            List(concurrency) { async { coordinator.incrementPasskeySignCount(entryId.toHexString()) } }.awaitAll()
        }

        assertEquals(
            "锁外快照自行计算在并发下必然重复（RP 侧观察到重复 signCount）",
            1,
            callerSide.toSet().size
        )
        assertEquals(
            "协调器原子回传的落库值两两不同（并发断言各自可取得唯一计数器）",
            concurrency,
            coordinatorSide.filterNotNull().toSet().size
        )
        assertEquals("库内终态按原子返回值口径逐次推进", snapshot + concurrency, storedSignCount(session))
    }

    @Test
    fun `计数器饱和于上界后不再递增_为唯一允许的重复来源`() = runTest {
        // 诚实性留痕：上界饱和语义（防 CWE-190 回绕）决定计数器不可能无限严格递增，
        // 到达 MAX_SIGN_COUNT 后重复是设计使然，与并发无关
        val session = newSession(signCountText = PasskeyData.MAX_SIGN_COUNT.toString())
        val coordinator = coordinatorOf(session)

        val first = coordinator.incrementPasskeySignCount(entryId.toHexString())
        val second = coordinator.incrementPasskeySignCount(entryId.toHexString())

        assertEquals("饱和后不得回绕为负", PasskeyData.MAX_SIGN_COUNT, first)
        assertEquals("上界处的重复是饱和语义的必然结果（非并发缺陷）", first, second)
        assertEquals(PasskeyData.MAX_SIGN_COUNT, storedSignCount(session))
    }
}
