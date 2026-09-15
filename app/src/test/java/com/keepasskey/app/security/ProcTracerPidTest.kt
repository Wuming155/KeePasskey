package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/proc/self/status` 的 `TracerPid` 解析单元测试（**ISSUE-P3-83**）。
 *
 * 解析是纯函数，故在 JVM 上穷举边界；真机上「这份内容确实来自内核」由
 * `TracedProcessProbeDeviceTest` 覆盖（宿主无法证明 `/proc` 的实际格式）。
 */
class ProcTracerPidTest {

    @Test
    fun `解析未被 trace 的进程`() {
        val status = """
            Name:	com.keepasskey
            State:	S (sleeping)
            Tgid:	1234
            Pid:	1234
            PPid:	900
            TracerPid:	0
            Uid:	10183	10183	10183	10183
        """.trimIndent()

        assertEquals(0, ProcTracerPid.parse(status))
    }

    @Test
    fun `解析正被 trace 的进程`() {
        val status = "Name:\tcom.keepasskey\nTracerPid:\t4242\nUid:\t10183\n"

        assertEquals(4242, ProcTracerPid.parse(status))
    }

    @Test
    fun `字段位置不影响解析`() {
        assertEquals(7, ProcTracerPid.parse("TracerPid:\t7\n"))
        assertEquals(7, ProcTracerPid.parse("Name:\tx\nState:\tS\nTracerPid:\t7\nGid:\t1"))
        // 字段位于最后一行且无尾随换行
        assertEquals(7, ProcTracerPid.parse("Name:\tx\nTracerPid:\t7"))
    }

    @Test
    fun `字段缺失返回 null`() {
        assertNull(ProcTracerPid.parse(""))
        assertNull(ProcTracerPid.parse("Name:\tcom.keepasskey\nState:\tS\nUid:\t10183\n"))
    }

    @Test
    fun `值非数字返回 null`() {
        assertNull(ProcTracerPid.parse("TracerPid:\tabc\n"))
        assertNull(ProcTracerPid.parse("TracerPid:\t\n"))
        assertNull(ProcTracerPid.parse("TracerPid:\n"))
    }

    @Test
    fun `不与其他含 TracerPid 字样的字段混淆`() {
        // 前缀匹配必须要求紧跟冒号：`TracerPidExtra:` 不得被当作 `TracerPid:`
        assertNull(ProcTracerPid.parse("TracerPidExtra:\t9\n"))
        // 行中出现（非行首字段）不得被误取
        assertNull(ProcTracerPid.parse("SomethingTracerPid:\t9\n"))
    }

    @Test
    fun `空白分隔形式同样可解析`() {
        assertEquals(11, ProcTracerPid.parse("TracerPid:   11  \n"))
    }
}
