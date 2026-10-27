package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CredentialRequestCodeWiringTest {
    @Test
    fun `同一进程连续分配的请求码递增且不重复`() {
        val allocated = (1..2_000).map { CredentialPendingIntents.nextRequestCode() }
        assertEquals(allocated.size, allocated.toSet().size)
        assertEquals(allocated.sorted(), allocated)
        assertTrue(allocated.first() >= 1_000)
    }

    @Test
    fun `并发分配的请求码不重复`() {
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(4) {
                pool.execute {
                    try {
                        start.await()
                        repeat(500) { results.add(CredentialPendingIntents.nextRequestCode()) }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2_000, results.size)
        assertEquals(results.size, results.toSet().size)
    }
}
