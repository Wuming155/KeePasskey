package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CM 通道 `PendingIntent` requestCode 的**分配契约**回归（ISSUE-P2-199）。
 *
 * ## 为什么需要两层用例
 *
 * 1. **语义层**（纯 JVM）：官方契约是「unique request code per entry」，而其唯一性域是
 *    **进程持久范围**（见 [CredentialPendingIntents.nextRequestCode] 的 KDoc）。分配器是否
 *    真的单调、并发是否真的取不到重复值，可以在宿主上一次跑清；
 * 2. **接线层**（源码文本）：四条落地入口分散在服务、创建装配与候选组装器中——
 *    **漏改任何一处都不会让任何运行时用例变红**，只会在真机上表现为「点旧候选、拉起新
 *    上下文」（ISSUE-P3-122 同形缺陷的先例）。故与 `AutofillAuthResultWiringTest` 同法，
 *    以源码断言把「不得再出现按响应复位 / 常量 requestCode」钉死。
 */
class CredentialRequestCodeWiringTest {

    // ── 语义层：进程级单调分配器 ──

    @Test
    fun `分配器在同一进程内严格递增且不重复`() {
        val allocated = (1..ALLOCATION_SAMPLE).map { CredentialPendingIntents.nextRequestCode() }

        assertEquals("同一批分配必须两两互异（零重复）", ALLOCATION_SAMPLE, allocated.toSet().size)
        assertEquals(
            "必须严格递增（单调 +1），不得出现回退或复位——「每响应从基线复位」正是原缺陷形态",
            allocated.sorted(),
            allocated
        )
        assertTrue(
            "分配值必须自 CM 通道基线（≥ 1000）起，不得落回自动填充通道的低位常量段",
            allocated.first() >= CM_REQUEST_CODE_BASELINE
        )
    }

    @Test
    fun `并发分配同样两两互异（两路响应同时组装）`() {
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            repeat(THREADS) {
                pool.execute {
                    start.await()
                    repeat(PER_THREAD) { results.add(CredentialPendingIntents.nextRequestCode()) }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(
                "并发分配必须在 ${TIMEOUT_SECONDS}s 内完成",
                done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )
        } finally {
            pool.shutdownNow()
        }

        val total = THREADS * PER_THREAD
        assertEquals("并发场景下每次分配都必须取到新值（AtomicInteger 读改写）", total, results.size)
        assertEquals("并发场景下不得出现重复 requestCode", total, results.toSet().size)
    }

    // ── 接线层：四条入口一律走共享分配器，不得残留常量 / 每响应复位 ──

    @Test
    fun `分配器必须是进程级单调而非每响应局部计数`() {
        val source = readSource(PENDING_INTENTS)
        assertTrue(
            "必须存在进程级 AtomicInteger 分配器",
            source.contains("private val requestCodeAllocator = AtomicInteger(REQUEST_CODE_BASE)")
        )
        assertTrue(
            "分配必须走 getAndIncrement（原子读改写）",
            source.contains("fun nextRequestCode(): Int = requestCodeAllocator.getAndIncrement()")
        )
    }

    @Test
    fun `四条落地入口一律取共享分配器`() {
        // 候选组装器有两条 PendingIntent 创建点（Passkey 断言 / 密码填充）
        assertEquals(
            "候选组装器的两条创建点都必须取共享分配器",
            2,
            Regex("CredentialPendingIntents\\.nextRequestCode\\(\\)").findAll(readSource(ASSEMBLER)).count()
        )
        assertEquals(
            "创建入口装配（Passkey / 密码）必须取共享分配器",
            1,
            Regex("CredentialPendingIntents\\.nextRequestCode\\(\\)").findAll(readSource(CREATE_ENTRIES)).count()
        )
        assertTrue(
            "解锁 Action 入口必须取共享分配器",
            readSource(PROVIDER_SERVICE).contains("CredentialPendingIntents.nextRequestCode()")
        )
    }

    @Test
    fun `不得残留按响应复位的分配器或常量 requestCode`() {
        val assembler = readSource(ASSEMBLER)
        assertFalse(
            "候选组装器不得再持有每响应复位的局部分配器（ISSUE-P2-199 的原始缺陷形态）",
            assembler.contains("RequestCodeAllocator")
        )
        assertFalse("候选组装器不得残留分配基线常量", assembler.contains("REQUEST_CODE_BASE"))

        val createEntries = readSource(CREATE_ENTRIES)
        assertFalse(
            "创建入口不得再用常量 requestCode（103/104）",
            createEntries.contains("REQUEST_CODE_CREATE_")
        )

        val service = readSource(PROVIDER_SERVICE)
        listOf("REQUEST_CODE_UNLOCK", "REQUEST_CODE_ASSERT", "REQUEST_CODE_FILL").forEach { constant ->
            assertFalse(
                "CM 服务不得残留常量 requestCode：$constant",
                service.contains(constant)
            )
        }
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PENDING_INTENTS = "app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt"
        const val ASSEMBLER = "app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt"
        const val CREATE_ENTRIES = "app/src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt"
        const val PROVIDER_SERVICE = "app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt"

        /** 抽样规模：足以暴露「每响应复位」与「常量复用」，且宿主上瞬时完成 */
        const val ALLOCATION_SAMPLE = 2_000

        const val THREADS = 4
        const val PER_THREAD = 500
        const val TIMEOUT_SECONDS = 30L

        /**
         * CM 通道分配基线（与 `CredentialPendingIntents.REQUEST_CODE_BASE` 同值）。
         *
         * 刻意不引用其内部常量（`private`）：本断言要锁的是「自 1000 起」这一**对外可观察**
         * 的取值口径，常量本身的存在性已由接线层用例的源码断言覆盖。
         */
        const val CM_REQUEST_CODE_BASELINE = 1_000

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
            error("未能从 ${System.getProperty("user.dir")} 向上定位仓库根目录（含 app/src/main/java）")
        }
        const val ROOT_SEARCH_DEPTH = 8
    }
}
