package com.keepasskey.sync

import com.keepasskey.sync.network.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * ISSUE-P3-555 回归：**协程取消不得被 `runCatching` / `catch (Exception)` 吞掉**。
 *
 * 背景：`CancellationException` 在 JVM 上是 `java.util.concurrent.CancellationException` 的别名，
 * 继承 `IllegalStateException` ⇒ 全部 `runCatching` / `catch (e: Exception)` 都会顺手吞掉它。
 * 本仓 `sync` 模块整改前 grep `CancellationException` **仅** `TransientHttpRetry.kt:62` 一处
 * （即「正确重抛」是例外而非通例）：provider 的五个 `runCatching`、`WebDavUploadAtomic` 的
 * 重试 `catch`、`SyncEngine.markResolvedAndUpload` 的 `catch (Throwable)` 都会把取消
 * 归一成业务失败。上层随即把「用户退出 / 换库」记成一次上传失败。
 *
 * 本文件两层：
 * 1. **行为级**：[runCatchingCancellable] 的口径（取消重抛 / 普通异常仍归一 / 成功值原样）；
 * 2. **静态接线**：两个 provider 与上传原子写**不得**再出现裸 `runCatching {` 形态，
 *    重试 `catch` 必须先接住 `CancellationException`。
 */
class CancellationRethrowWiringTest {

    @Test
    fun `取消异常必须原样重抛`() {
        var caught: CancellationException? = null
        try {
            runCatchingCancellable<Unit> { throw CancellationException("user left the screen") }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull("取消必须重抛，不得包成 Result.failure", caught)
        assertEquals("user left the screen", caught?.message)
    }

    @Test
    fun `普通异常仍归一为 failure 成功值原样返回`() {
        val failure = runCatchingCancellable<Unit> { throw IOException("network down") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IOException)

        assertEquals("ok", runCatchingCancellable { "ok" }.getOrNull())
    }

    @Test
    fun `provider 与上传原子写不得再出现裸 runCatching`() {
        listOf(WEBDAV_PROVIDER, S3_PROVIDER, WEBDAV_UPLOAD_ATOMIC).forEach { path ->
            val source = readSource(path)
            assertFalse(
                "$path 不得再出现裸 `runCatching {`（会吞协程取消，改用 runCatchingCancellable）",
                Regex("""\brunCatching\s*\{""").containsMatchIn(source)
            )
        }
    }

    @Test
    fun `上传重试的异常分支必须先重抛取消`() {
        val source = readSource(WEBDAV_UPLOAD_ATOMIC)
        val cancellationIndex = source.indexOf("catch (e: CancellationException)")
        val retryIndex = source.indexOf("if (attempt == 1) throw e")
        assertTrue("必须存在 `catch (e: CancellationException)` 显式重抛点", cancellationIndex > 0)
        assertTrue(
            "取消分支必须排在重试的 `catch (e: Exception)` 之前（后者会吞掉取消）",
            cancellationIndex < retryIndex
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val WEBDAV_PROVIDER =
            "sync/src/main/java/com/keepasskey/sync/webdav/WebDavSyncProvider.kt"
        const val S3_PROVIDER =
            "sync/src/main/java/com/keepasskey/sync/s3/S3SyncProvider.kt"
        const val WEBDAV_UPLOAD_ATOMIC =
            "sync/src/main/java/com/keepasskey/sync/webdav/WebDavUploadAtomic.kt"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
