package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 保存请求超时预算接线守卫（**ISSUE-P3-122 IPC-10**）。
 *
 * ## 缺陷形态
 *
 * `onSaveRequest` 原实现**无任何上限**：`runtimeIntegrityGate.awaitEnforcement()` 在首次扫描未完成时
 * 可等待一整个扫描周期，库侧 `Save` 亦可能长时间不返回；而平台对该回调**不提供**
 * `CancellationSignal`，故无上限的直接后果是**系统的保存 UI 永久等待**。
 *
 * ## 判据为什么是源码扫描
 *
 * 「是否给某回调加了上限」是结构性约束；运行期用例需要构造 `SaveRequest` + `SaveCallback` 才能覆盖，
 * 而一旦有人在重构中把包裹去掉，不会有任何测试变红。
 */
class AutofillSaveTimeoutWiringTest {

    private val source = readSource(SERVICE)

    @Test
    fun `保存回调必须整体受超时预算约束`() {
        assertTrue(
            "onSaveRequest 必须经 withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS) 包裹",
            source.contains("withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS) {")
        )
        assertTrue(
            "预算常量必须存在（internal 以便本守卫断言）",
            source.contains("internal const val SAVE_REQUEST_TIMEOUT_MS =")
        )
    }

    @Test
    fun `超时必须向系统给出答复而非静默悬挂`() {
        // 刻意**不**用 `substringBefore("}")` 取窗口：分支里的日志用了字符串模板
        // `${SAVE_REQUEST_TIMEOUT_MS}`，其中就含 `}`，按首字符 `}` 截断会取到半截、误判为「没回调」。
        // 改用固定长度窗口 + 断言同一窗口内确有 onSuccess。
        val branch = source.substringAfter("if (handled == null) {").take(400)

        assertTrue(
            "超时分支必须回调 onSuccess（表示「本次无需保存」），否则系统保存 UI 仍会等待",
            branch.contains("callback.onSuccess()")
        )
        assertTrue(
            "超时分支必须留痕（否则线上无法区分「超时」与「本来就不需要保存」）",
            branch.contains("AppLog.w(TAG,")
        )
    }

    @Test
    fun `处理体必须抽为普通 suspend 函数`() {
        // withTimeoutOrNull **不是** inline 函数：处理体若留在 lambda 内，
        // 其中的 `return@launch` 属非局部返回、无法编译。抽取是编译期的硬约束，
        // 不只是风格选择——故钉住「抽取 + 用普通 return」这一形态。
        assertTrue(
            "处理体必须抽为 private suspend fun handleSaveRequest(...)",
            source.contains("private suspend fun handleSaveRequest(")
        )
        val handler = source.substringAfter("private suspend fun handleSaveRequest(")
            .substringBefore("\n    override fun ")
        assertFalse(
            "抽取后的处理体不得残留 return@launch（无法编译 / 语义错位）",
            handler.contains("return@launch")
        )
    }

    @Test
    fun `服务解绑仍不得回调`() {
        val handler = source.substringAfter("private suspend fun handleSaveRequest(")

        assertTrue(
            "处理体必须原样重抛 CancellationException——服务解绑后不得再向框架回调",
            handler.contains("catch (c: CancellationException)") && handler.contains("throw c")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SERVICE = "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
