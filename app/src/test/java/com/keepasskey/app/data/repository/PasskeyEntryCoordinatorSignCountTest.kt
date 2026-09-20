package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ISSUE-P3-10 子项 2（ZT-21）签名计数器受控事务回归测试。
 *
 * 旧实现（读-改-写无 CAS）：先 `databaseFlow.first()` 取快照，再 `saveEntry` 各自加锁；
 * 两次并发断言可同时读到 N 并各自写回 N+1，丢失一次递增。
 * 现实现把「读取库内现值 → 计算目标值 → 替换条目」整体收口到
 * [DatabaseSession.updateDatabaseMeta]（会话 Mutex 内单次受控变换），并以
 * `库内现值 + 1` 为单调下界、以 [PasskeyData.MAX_SIGN_COUNT] 为钳制上界。
 *
 * ISSUE-P3-157（§119）：承载该变换的入口改为 [DatabaseSession.updateEntryById]
 * （按 id 定位的**单条条目原子读-改-写**：路径复制 + 增量定点擦除）；本文件除既有
 * 计数器语义外，另锁定该路径的擦除与路径复制契约（旧计数器实例必清零、
 * 同条目存活密文与未命中兄弟分组必留）与 DIRTY / 零写入边界。
 *
 * ISSUE-P3-27 子项 2（并发签名计数器假说）：追加 32 路真实并发用例，收集
 * [PasskeyEntryCoordinator.incrementPasskeySignCount] 的全部返回值断言
 * **互不相同且恰为 1..N**（证伪「协调器内部并发递增会得到同一值」），
 * 并用例锁死唯一被证实的重复来源——调用方以锁外快照自行计算（见对应用例 KDoc）。
 *
 * ISSUE-P3-213：另锁定「v1 旧 schema 条目在计数器修补的同一受控变换内就地迁移为 KPEX」——
 * 旧键清除、KPEX 字段完整建立、私钥重包为 PKCS#8 PEM 且仍是同一把可签名密钥，
 * 以及迁移失败（私钥无法重包）时**不阻断计数器写入**的 fail-safe 边界。
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
        return newSessionWith(customFields)
    }

    /** 与 [newSession] 同一装配，但自定义字段完全由调用方给定（v1 旧 schema 场景用） */
    private fun newSessionWith(customFields: List<KdbxCustomField>): DatabaseSession {
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

    // ===== ISSUE-P3-157：计数器补丁改走单条条目原子变换（路径复制 + 增量定点擦除） =====

    private fun entryIn(session: DatabaseSession): KdbxEntry =
        session.databaseFlow.value!!.rootGroup.allEntries().first { it.id == entryId }

    private fun customFieldValue(session: DatabaseSession, key: String): ProtectedString =
        entryIn(session).customFields.first { it.key == key }.value

    /** 已清零的 ProtectedString 读取会抛 IllegalStateException，此处归一为 null 便于断言 */
    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    @Test
    fun `计数器补丁后旧计数器实例被清零_而同条目其余密文原样存活`() = runTest {
        val session = newSession(signCountText = "5")
        val staleSignCount = customFieldValue(session, PasskeyData.FIELD_SIGN_COUNT)
        val privateKey = customFieldValue(session, PasskeyData.FIELD_PRIVATE_KEY)
        val title = entryIn(session).fields.getValue(KdbxConstants.Fields.TITLE)

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), 9)

        assertEquals(9, storedSignCount(session))
        assertNull("被替换下线的旧计数器实例必须清零", readOrNull(staleSignCount))
        assertEquals("同一被替换条目内的存活密文不得被误擦", "private-key", privateKey.readString())
        assertEquals("E", title.readString())
        assertSame("存活字段实例必须仍是原实例", privateKey, customFieldValue(session, PasskeyData.FIELD_PRIVATE_KEY))
    }

    @Test
    fun `计数器补丁只重建命中路径_未命中的兄弟分组按同一实例复用`() = runTest {
        val session = newSession(signCountText = "1")
        session.saveGroup(KdbxGroup(id = KdbxUuid.random(), parentGroupId = groupId, name = "sibling"))
        val siblingBefore = session.databaseFlow.value!!.rootGroup.subgroups.single()

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), 4)

        assertSame(
            "未命中分支必须按同一实例复用（增量擦除的候选前提）",
            siblingBefore,
            session.databaseFlow.value!!.rootGroup.subgroups.single()
        )
        assertEquals(4, storedSignCount(session))
    }

    @Test
    fun `计数器补丁命中条目后置 DIRTY`() = runTest {
        val session = newSession(signCountText = "2")

        coordinatorOf(session).patchPasskeySignCount(entryId.toHexString(), 3)

        assertEquals(
            "命中条目必须置 DIRTY（供后续统一 save 落盘）",
            DatabaseSession.SessionState.DIRTY,
            session.state.value
        )
    }

    @Test
    fun `计数器补丁未命中条目时库实例与状态均不变`() = runTest {
        val session = newSession(signCountText = "2")
        val before = session.databaseFlow.value!!

        coordinatorOf(session).patchPasskeySignCount(KdbxUuid.random().toHexString(), 9)

        assertSame("未命中不得替换活动库实例（零写入）", before, session.databaseFlow.value)
        assertEquals("未命中不得置 DIRTY", DatabaseSession.SessionState.OPENED, session.state.value)
        assertEquals(2, storedSignCount(session))
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

    // ===== ISSUE-P1-216：回传值必须在临界区内确定（并发提交会定点擦除先前落树实例） =====

    @Test
    fun `后继提交会定点擦除先前落树实例的计数器_故临界区外读回必失效`() = runTest {
        // 机制面（确定性复现，非依赖调度运气）：
        //   `withSignCount` 每次落树都**新建**一个计数器 ProtectedString，因此第 N 次提交上线的
        //   落树实例恰好是第 N+1 次提交的「增量定点擦除」候选。整改前的生产实现正是在
        //   `updateEntryById` 返回（会话 Mutex 已释放）之后才从该实例读回计数器——
        //   并发后继提交若抢在这条读回之前完成提交，读回处即命中
        //   `ProtectedString.checkNotCleared()` 抛 IllegalStateException（CI artifact 的
        //   `ProtectedString.kt:173`）。本用例把「擦除确实会发生」变成确定性断言，
        //   并按整改前的读回写法复演出与 CI **逐字同形**的异常。
        val session = newSession(signCountText = "0")
        val coordinator = coordinatorOf(session)

        // 第 1 次提交：握住所上线的落树实例（= 整改前临界区外读回的那个实例）
        coordinator.incrementPasskeySignCount(entryId.toHexString())
        val firstPlacedEntry = entryIn(session)
        val firstSignCount = customFieldValue(session, PasskeyData.FIELD_SIGN_COUNT)
        assertEquals("第 1 次提交落库值为 1", 1, readOrNull(firstSignCount)?.toInt())

        // 第 2 次提交：只重建命中路径，并对第 1 次的落树实例做定点擦除
        coordinator.incrementPasskeySignCount(entryId.toHexString())

        assertNotSame("落树实例已被后继提交替换", firstPlacedEntry, entryIn(session))
        assertNull(
            "先前落树实例的计数器必被后继提交定点擦除——擦除本身是既定安全语义，" +
                "缺陷只在于整改前于临界区外读回该实例（ISSUE-P1-216）",
            readOrNull(firstSignCount)
        )
        // 复演整改前的读回写法：与 CI 日志逐字同形的异常
        val replayed = runCatching { PasskeyData.readSignCount(firstPlacedEntry.customFields) }.exceptionOrNull()
        assertTrue(
            "临界区外读回落树实例必须复现 CI 的 IllegalStateException（实际：$replayed）",
            replayed is IllegalStateException && replayed.message.orEmpty().contains("已经清零")
        )
        assertEquals("后继提交自身不受影响", 2, storedSignCount(session))
        assertEquals(
            "同一被替换条目内的存活密文（私钥）仍可读，擦除面未外溢",
            "private-key",
            customFieldValue(session, PasskeyData.FIELD_PRIVATE_KEY).readString()
        )
    }

    @Test
    fun `并发递增在真实线程屏障同步下仍取回各自唯一计数器`() {
        // ISSUE-P1-216 AC③：宿主侧**稳定复现**该竞态。
        //
        // 为何必须用真实线程 + 屏障（而非 `Dispatchers.Default` 上的协程）：
        // 竞态窗口 = 「会话 Mutex 释放后 → 读回落树实例前」，宽度仅百纳秒级；而
        // `runTest` + `Dispatchers.Default` 下未获锁的调用方处于**挂起**态、不占 CPU，
        // 解锁方几乎总能先跑完那条读回（实测 1200 轮 × 32 路：0 次命中，与「本机绿」
        // 一致）。CI runner 上并发调用方是真线程争抢 CPU，解锁方极易在该窗口被抢占——
        // 这才是「本地绿 / CI 红」的成因。
        // 本用例以**真实线程 + CyclicBarrier 同时起跑**复刻该条件：
        // 整改前实测命中率约 1/8 ~ 4/轮（32 路 × 800 轮实测命中 3402 次，
        // 异常消息与 CI 逐字同形；12 轮即已在第 11 轮命中）；整改后读回在临界区内完成，
        // 任何交错都不再可能读到已擦除实例。取 150 轮以保证「整改前必红」的判定力。
        val concurrency = 32
        val rounds = 150
        repeat(rounds) { round ->
            val session = newSession(signCountText = "0")
            val coordinator = coordinatorOf(session)
            val startBarrier = CountDownLatch(1)
            val finished = CountDownLatch(concurrency)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val applied = ConcurrentLinkedQueue<Int>()

            repeat(concurrency) {
                Thread {
                    try {
                        startBarrier.await()
                        runBlocking { coordinator.incrementPasskeySignCount(entryId.toHexString()) }
                            ?.let(applied::add)
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        finished.countDown()
                    }
                }.apply { isDaemon = true }.start()
            }
            startBarrier.countDown()
            assertTrue(
                "第 $round 轮：并发调用必须在 30s 内全部退出（有无死锁？）",
                finished.await(30, TimeUnit.SECONDS)
            )

            assertTrue(
                "第 $round 轮：并发递增不得抛异常（整改前此处即 ProtectedString 已清零断言）——" +
                    "实际失败 ${failures.size} 次，首个：${failures.firstOrNull()}",
                failures.isEmpty()
            )
            assertEquals("第 $round 轮：每次并发递增都必须落账并回传", concurrency, applied.size)
            assertEquals(
                "第 $round 轮：回传值必须恰为 1..N（无重复、无丢失更新、无回退）",
                (1..concurrency).toList(),
                applied.sorted()
            )
            assertEquals("第 $round 轮：库内终态必须等于最大回传值", concurrency, storedSignCount(session))
        }
    }

    @Test
    fun `多轮并发递增不丢失更新`() = runTest {
        // ISSUE-P3-27 子项 2 的廉价伴随守卫：在 Default 线程池上反复跑 32 路并发，
        // 覆盖「受控变换的单调下界」在多次交错下是否仍然逐次落账。
        // （擦除面竞态由上面的真实线程用例承担——它才是能命中该窗口的形态。）
        val concurrency = 32
        val rounds = 60
        repeat(rounds) { round ->
            val session = newSession(signCountText = "0")
            val coordinator = coordinatorOf(session)

            val values = withContext(Dispatchers.Default) {
                List(concurrency) { async { coordinator.incrementPasskeySignCount(entryId.toHexString()) } }
                    .awaitAll()
            }.filterNotNull()

            assertEquals("第 $round 轮：并发递增必须全部落账", concurrency, values.size)
            assertEquals(
                "第 $round 轮：回传的计数器必须恰为 1..N",
                (1..concurrency).toList(),
                values.sorted()
            )
            assertEquals("第 $round 轮：库内终态等于最大回传值", concurrency, storedSignCount(session))
        }
    }

    // ===== ISSUE-P3-213：v1 旧 schema 在写路径上就地迁移为 KPEX =====

    /** v1 旧 schema 条目字段（旧键齐备、无任何 KPEX 核心键），私钥文本由调用方给定 */
    private fun legacyFields(privateKeyText: String): List<KdbxCustomField> = listOf(
        KdbxCustomField(PasskeyData.LEGACY_FIELD_RP_ID, ProtectedString("legacy.example", isProtected = false)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, ProtectedString("old-cred", isProtected = true)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_PRIVATE_KEY, ProtectedString(privateKeyText, isProtected = true)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_USER_NAME, ProtectedString("bob", isProtected = false)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_USER_HANDLE, ProtectedString("old-handle", isProtected = false)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_BACKUP_ELIGIBLE, ProtectedString("false", isProtected = false)),
        KdbxCustomField(PasskeyData.LEGACY_FIELD_BACKUP_STATE, ProtectedString("true", isProtected = false))
    )

    private fun fieldsOf(session: DatabaseSession): Map<String, KdbxCustomField> =
        entryIn(session).customFields.associateBy { it.key }

    /** 条目当前私钥字段的 UTF-8 字节副本（调用方负责清零） */
    private fun privateKeyBytesOf(session: DatabaseSession, key: String): ByteArray =
        fieldsOf(session).getValue(key).value.useUtf8 { it.copyOf() }

    @Test
    fun `v1 旧 schema 条目在计数器修补时被就地迁移为 KPEX`() = runTest {
        val session = newSessionWith(legacyFields(HEX_SCALAR_64))
        val staleLegacyPrivateKey = customFieldValue(session, PasskeyData.LEGACY_FIELD_PRIVATE_KEY)

        val applied = coordinatorOf(session).incrementPasskeySignCount(entryId.toHexString())

        assertEquals("迁移不得影响计数器语义", 1, applied)
        val fields = fieldsOf(session)
        // 1. 全部 v1 旧键被清除（否则同一库出现两套不自洽的字段）
        assertNull("Passkey.RelyingParty 必须被移除", fields[PasskeyData.LEGACY_FIELD_RP_ID])
        assertNull(fields[PasskeyData.LEGACY_FIELD_CREDENTIAL_ID])
        assertNull(fields[PasskeyData.LEGACY_FIELD_PRIVATE_KEY])
        assertNull(fields[PasskeyData.LEGACY_FIELD_USER_NAME])
        assertNull(fields[PasskeyData.LEGACY_FIELD_USER_HANDLE])
        assertNull(fields[PasskeyData.LEGACY_FIELD_BACKUP_ELIGIBLE])
        assertNull(fields[PasskeyData.LEGACY_FIELD_BACKUP_STATE])
        // 2. KPEX 规范字段完整建立（键名 / 保护位 / 值均按 KeePassXC 口径）
        assertEquals("legacy.example", fields.getValue(PasskeyData.KPEX_FIELD_RELYING_PARTY).value.readString())
        assertEquals("bob", fields.getValue(PasskeyData.KPEX_FIELD_USERNAME).value.readString())
        assertEquals("old-handle", fields.getValue(PasskeyData.KPEX_FIELD_USER_HANDLE).value.readString())
        assertEquals("0", fields.getValue(PasskeyData.KPEX_FIELD_FLAG_BE).value.readString())
        assertEquals("1", fields.getValue(PasskeyData.KPEX_FIELD_FLAG_BS).value.readString())
        assertTrue(
            "Credential ID 迁移后必须按 KeePassXC 口径受保护",
            fields.getValue(PasskeyData.KPEX_FIELD_CREDENTIAL_ID).value.isProtected
        )
        assertTrue(
            "私钥必须以受保护属性写入 KPEX_PASSKEY_PRIVATE_KEY_PEM",
            fields.getValue(PasskeyData.KPEX_FIELD_PRIVATE_KEY).value.isProtected
        )
        assertEquals("old-cred", fields.getValue(PasskeyData.KPEX_FIELD_CREDENTIAL_ID).value.readString())
        // 3. 私钥重包为 PKCS#8 PEM，且经生产解析链路仍是同一把可签名的密钥
        val pemBytes = privateKeyBytesOf(session, PasskeyData.KPEX_FIELD_PRIVATE_KEY)
        try {
            assertTrue("迁移后的私钥文本必须是 PKCS#8 PEM", PasskeyKeyText.isPem(pemBytes))
            val signingKey = PasskeyCryptoEngine.decodePemPrivateKeyText(pemBytes)
            assertNotNull("迁移后的 PEM 必须能被生产解析链路还原", signingKey)
            assertEquals(PasskeyData.ALGORITHM_ES256, signingKey!!.algorithmId)
            assertArrayEquals(
                "hex 标量迁移到 PEM 必须逐字节保留原标量",
                ByteArray(32) { 0x11.toByte() },
                signingKey.keyBytes
            )
            assertTrue(
                "迁移后必须仍能真实签发断言",
                PasskeyCryptoEngine.signAssertionConsumingKey(
                    signingKey.algorithmId, signingKey.keyBytes, "migration challenge".toByteArray()
                ).isNotEmpty()
            )
        } finally {
            pemBytes.fill(0)
        }
        // 4. 被替换下线的旧私钥实例随定点擦除清零（迁移不给旧密文留驻留机会）
        assertNull("下线的 v1 私钥实例必须清零", readOrNull(staleLegacyPrivateKey))
        // 5. 计数器写入与非 passkey 字段保留
        assertEquals(1, storedSignCount(session))
        assertEquals("E", entryIn(session).fields.getValue(KdbxConstants.Fields.TITLE).readString())
    }

    @Test
    fun `v1 Ed25519 种子条目迁移后算法保持 Ed25519 且种子不变`() = runTest {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val session = newSessionWith(legacyFields(Base64.getEncoder().encodeToString(seed)))

        coordinatorOf(session).incrementPasskeySignCount(entryId.toHexString())

        val fields = fieldsOf(session)
        assertNull(fields[PasskeyData.LEGACY_FIELD_PRIVATE_KEY])
        val pemBytes = privateKeyBytesOf(session, PasskeyData.KPEX_FIELD_PRIVATE_KEY)
        try {
            val signingKey = PasskeyCryptoEngine.decodePemPrivateKeyText(pemBytes)
            assertNotNull(signingKey)
            assertEquals(
                "v1 Ed25519 条目（无 Passkey.Algorithm 扩展键）迁移后仍须按 Ed25519 解析",
                PasskeyData.ALGORITHM_ED25519,
                signingKey!!.algorithmId
            )
            assertArrayEquals("种子必须逐字节保留", seed, signingKey.keyBytes)
        } finally {
            pemBytes.fill(0)
        }
    }

    @Test
    fun `私钥无法重包时保持旧 schema 不迁移_但计数器照常写入`() = runTest {
        val session = newSessionWith(legacyFields("!!not-a-private-key!!"))

        val applied = coordinatorOf(session).incrementPasskeySignCount(entryId.toHexString())

        assertEquals("迁移失败不得阻断计数器写入（fail-safe 而非 fail-closed）", 1, applied)
        val fields = fieldsOf(session)
        assertEquals("legacy.example", fields.getValue(PasskeyData.LEGACY_FIELD_RP_ID).value.readString())
        assertEquals(
            "迁移失败时私钥必须保持原文（绝不写入一份解析不了的 PEM）",
            "!!not-a-private-key!!",
            fields.getValue(PasskeyData.LEGACY_FIELD_PRIVATE_KEY).value.readString()
        )
        assertNull(
            "迁移失败时不得留下半套 KPEX 字段",
            fields[PasskeyData.KPEX_FIELD_RELYING_PARTY]
        )
        assertEquals(1, storedSignCount(session))
    }

    private companion object {
        /** 合法 secp256r1 标量（< n，故可真实签名）的 64 字符 hex 文本：32 个 0x11 */
        val HEX_SCALAR_64 = "1".repeat(64)
    }
}
