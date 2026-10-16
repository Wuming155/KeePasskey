package com.keepasskey.sync.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableOkHttpCallTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `协程取消会取消仍在等待响应头的 OkHttp Call`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(10, TimeUnit.SECONDS)
                .setBody("late response")
        )
        val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
        val requestJob = launch(Dispatchers.IO) { executeCancellable(call) }

        assertNotNull("请求应已进入服务端", server.takeRequest(2, TimeUnit.SECONDS))
        requestJob.cancelAndJoin()

        assertTrue("取消必须传递到活动 OkHttp Call", call.isCanceled())
        assertTrue("调用协程应以取消结束", requestJob.isCancelled)
    }
}
