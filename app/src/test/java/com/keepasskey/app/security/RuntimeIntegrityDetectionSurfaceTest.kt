package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钩子检测**判据面**的锁定（**ISSUE-P3-120**）。
 *
 * ## 为什么必须钉死这两份清单
 *
 * `docs/records/运行完整性检测Frida实测基线.md` 里的「**命中率 3/3**」结论，其判据**就是**
 * [RuntimeIntegrityDetector.HOOK_MARKERS] 与 [RuntimeIntegrityDetector.HOOK_TRACE_PATHS]
 * 这两份清单——不是任意其它定义。若有人增删其中一项（哪怕只是「顺手多加一个常见名」），
 * 那份实测基线**立即失效**：它不再描述当前实现，而文档里不会自动出现任何红叉。
 *
 * 故本用例把「判据面」逐项钉住，并要求改动者显式地**重跑真机实测**（见 KDoc 的改动须知）。
 * 这是「实测证据必须与判据同源」这一纪律的可执行形式。
 */
class RuntimeIntegrityDetectionSurfaceTest {

    @Test
    fun `内存映射特征串清单必须与实测基线一致`() {
        assertEquals(
            "HOOK_MARKERS 是 docs/records/运行完整性检测Frida实测基线.md 的判据；" +
                "改动须重跑真机实测并更新该文档",
            listOf("frida", "xposed", "substrate", "edxposed", "lsposed", "libhook"),
            RuntimeIntegrityDetector.HOOK_MARKERS
        )
    }

    @Test
    fun `落点路径清单必须与实测基线一致`() {
        assertEquals(
            "HOOK_TRACE_PATHS 是实测基线的判据（实测已确认：改名即绕过本层）；" +
                "改动须重跑真机实测并更新该文档",
            listOf(
                "/data/local/tmp/frida-server",
                "/data/local/tmp/re.frida.server",
                "/data/local/tmp/frida",
                "/system/lib/libfrida-gadget.so",
                "/system/lib64/libfrida-gadget.so"
            ),
            RuntimeIntegrityDetector.HOOK_TRACE_PATHS
        )
    }

    @Test
    fun `两条防线都必须保留且不得退化为单层`() {
        // 实测结论：两层互补——只留落点层则改名即绕过；只留 maps 层则「落点已就位、尚未注入」
        // 的准备阶段不可见。任何一层被删空都等于退化为单层，须视为对基线结论的推翻。
        assertTrue(
            "maps 特征串不得为空（否则改名 + 注入形态整体失效）",
            RuntimeIntegrityDetector.HOOK_MARKERS.isNotEmpty()
        )
        assertTrue(
            "落点路径不得为空（否则「已下载未运行」的默认落点形态失效）",
            RuntimeIntegrityDetector.HOOK_TRACE_PATHS.isNotEmpty()
        )
    }

    @Test
    fun `实测已确认的绕过面必须仍被文档登记`() {
        // 本用例无法断言「实现是否已修」，只钉住「结论与未测面已被声明」——
        // 若该记录文档被移除，实测证据链即断裂，此处必须红。
        val doc = java.io.File(
            repositoryRoot,
            "docs/records/运行完整性检测Frida实测基线.md"
        )

        assertTrue(
            "实测基线文档不得被移除（ISSUE-P3-120 的证据链）",
            doc.isFile
        )
        val text = doc.readText()
        assertTrue(
            "必须登记「memfd 名改掉」这一最直接绕过面",
            text.contains("memfd 名")
        )
    }

    private companion object {
        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? =
                java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
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
