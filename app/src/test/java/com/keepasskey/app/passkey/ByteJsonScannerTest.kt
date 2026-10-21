package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 2 片：[ByteJsonScanner] 的宿主离线用例。
 *
 * 手写扫描器是全条目最大的单项，也是「私钥不经 `String`」这条铁律的唯一承载点：
 * 它的转义、代理对与层级判定若有偏差，CXF 载荷的 `key` / PRF 就会解错字节——
 * 这类错误在读取器用例里只会表现为「解不出来」，故这里**直接对字节读数**。
 *
 * 判据方向：一切畸形输入**必须**返回 null 而不抛异常（扫码面接受任意二维码，
 * 异常会击穿取景对话框）；非 ASCII 一律**原样透传**（输入本身就是 UTF-8）。
 *
 * `ISSUE-P3-647`：用例「十」~「十二」通过 [ByteJsonScanProbe] 直接保留工作数组引用，
 * 断言 `ByteSink` 的**每个容量代次**（含扩容前那一代）在成功、语法失败与纯键名三种路径上
 * 都已清零——只看返回值无法区分「已清零」与「从未清零」。
 */
class ByteJsonScannerTest {

    private fun scan(text: String): ByteJson? = ByteJsonScanner.scan(text.toByteArray(Charsets.UTF_8))

    private fun obj(text: String): ByteJson.Obj = scan(text) as ByteJson.Obj

    @Test
    fun `一 字符串转义按 RFC 8259 解码为字节`() {
        val node = obj("""{"a":"line\nbreak\ttab\"quote\\slash\/solid \b\f"}""")
            .member("a") as ByteJson.Str
        val expected = "line\nbreak\ttab\"quote\\slash/solid \u0008\u000C"
        assertEquals(
            "转义解码后须与目标文本逐字节一致",
            expected.toByteArray(Charsets.UTF_8).toList(),
            node.bytes.toList()
        )
    }

    @Test
    fun `二 unicode 转义与代理对按 UTF 8 展开`() {
        val bmp = (obj("""{"a":"éA"}""").member("a") as ByteJson.Str).bytes
        assertEquals("éA".toByteArray(Charsets.UTF_8).toList(), bmp.toList())

        val emoji = (obj("""{"a":"\uD83D\uDE00"}""").member("a") as ByteJson.Str).bytes
        assertEquals("\uD83D\uDE00".toByteArray(Charsets.UTF_8).toList(), emoji.toList())
        assertEquals("😀 的 UTF-8 编码是 4 字节", 4, emoji.size)

        // 落单的高代理：不吞掉后续输入，按 3 字节 CESU-8 形态写出（ED A0 BD）。
        // 这是 appendCodePoint 的既有语义，CXF 的 b64url / ASCII 字段永不含代理对，故不改生产码。
        val loneHigh = (obj("""{"a":"\uD83Dok"}""").member("a") as ByteJson.Str).bytes
        assertEquals(
            listOf(0xED.toByte(), 0xA0.toByte(), 0xBD.toByte(), 'o'.code.toByte(), 'k'.code.toByte()),
            loneHigh.toList()
        )
    }

    @Test
    fun `三 非 ASCII 原样透传，不做二次编码`() {
        val node = (obj("""{"密钥":"爱丽丝"}""").member("密钥") as ByteJson.Str).bytes
        assertEquals("爱丽丝".toByteArray(Charsets.UTF_8).toList(), node.toList())
    }

    @Test
    fun `四 嵌套结构与成员顺序，重复键取首个`() {
        val root = obj("""{"a":{"b":[{"c":1},{"c":2}]},"d":true}""")
        val nested = root.member("a") as? ByteJson.Obj
        assertNotNull("对象嵌套须成树", nested)
        val items = (nested!!.member("b") as? ByteJson.Arr)?.items
        assertEquals(2, items?.size)
        assertEquals(1L, ((items?.get(0) as? ByteJson.Obj)?.member("c") as? ByteJson.Num)?.longValue)
        assertEquals(2L, ((items?.get(1) as? ByteJson.Obj)?.member("c") as? ByteJson.Num)?.longValue)
        assertEquals(true, (root.member("d") as? ByteJson.Bool)?.value)
        assertEquals("顶层键顺序按输入保留", listOf("a", "d"), root.keys())

        val dup = obj("""{"rpId":"first","rpId":"second"}""")
        assertEquals("重复键取首个：后写覆盖会让载荷尾部悄悄改写仪式字段", "first", (dup.member("rpId") as ByteJson.Str).asUtf8String())
        assertEquals("成员数按输入保留（不合并）", 2, dup.keys().size)
    }

    @Test
    fun `五 数字与字面量的成型口径`() {
        val root = obj("""{"i":42,"neg":-7,"frac":1.5,"exp":1e3,"t":true,"f":false,"n":null}""")
        assertEquals(-7L, (root.member("neg") as ByteJson.Num).longValue)
        assertEquals(42L, (root.member("i") as ByteJson.Num).longValue)
        assertNull("小数无整数形态 ⇒ longValue 为 null（版本门只认整数）", (root.member("frac") as ByteJson.Num).longValue)
        assertNull("科学计数法同理", (root.member("exp") as ByteJson.Num).longValue)
        assertTrue((root.member("t") as ByteJson.Bool).value)
        assertTrue(!((root.member("f") as ByteJson.Bool).value))
        assertEquals(ByteJson.NullValue, root.member("n"))
    }

    @Test
    fun `六 畸形输入一律返回 null 而不抛异常`() {
        val bad = listOf(
            "空输入" to "",
            "截断对象" to "{\"a\":1",
            "缺冒号" to "{\"a\" 1}",
            "缺逗号" to "{\"a\":1 \"b\":2}",
            "尾随内容" to "{\"a\":1}{\"b\":2}",
            "键未加引号" to "{a:1}",
            "单引号" to "{'a':1}",
            "串内裸换行" to "{\"a\":\"x\ny\"}",
            "串内裸控制字符" to "{\"a\":\"x\u0001y\"}",
            "未知转义" to "{\"a\":\"x\\qy\"}",
            "半截 unicode" to "{\"a\":\"\\u00\"}",
            "裸词" to "undefined",
            "数组缺括号" to "[1,2",
            "多逗号" to "[1,2,]",
            "对象多逗号" to "{\"a\":1,}"
        )
        for ((label, text) in bad) {
            assertNull("$label ⇒ 应判为不可解析（返回 null）", scan(text))
        }
    }

    @Test
    fun `七 深度上限只放行规范实际形态，超深即拒`() {
        // 文档形态最深 7 层：Header > accounts > Account > items > Item > credentials > Passkey > fido2Extensions
        val sevenDeep = """{"a":[{"b":[{"c":[{"d":[{"e":[{"f":{"g":1}}]}]}]}]}]}"""
        assertNotNull("规范形态的实际深度须放行", scan(sevenDeep))
        val tooDeep = "[".repeat(ByteJsonScanner.MAX_DEPTH + 2) + "]".repeat(ByteJsonScanner.MAX_DEPTH + 2)
        assertNull("超深度须拒（防御性硬界）", scan(tooDeep))
    }

    @Test
    fun `八 空白布局不影响判定`() {
        val spaced = scan("\n\t {\"a\" : [ 1 , 2 ] , \"b\":{ } } \t\n") as? ByteJson.Obj
        assertNotNull(spaced)
        assertEquals(2, (spaced!!.member("a") as? ByteJson.Arr)?.items?.size)
        assertEquals(emptyList<String>(), (spaced.member("b") as? ByteJson.Obj)?.keys())
        assertTrue("空对象须成型为空成员表", (spaced.member("b") as ByteJson.Obj).members.isEmpty())
    }

    @Test
    fun `九 wipe 只清零节点副本`() {
        val text = "{\"key\":\"MIGH\"}"
        val payload = text.toByteArray(Charsets.UTF_8)
        val node = obj(String(payload)).member("key") as ByteJson.Str
        node.wipe()
        assertEquals("节点字节须被清零", List(node.bytes.size) { 0.toByte() }, node.bytes.toList())
        assertEquals("调用方传入的载荷数组不得被改动（擦除义务归调用链）", text, payload.decodeToString())
    }

    @Test
    fun `十 字符串工作缓冲的每个容量代次在返回后都已清零`() {
        // 超过初始容量 64 ⇒ 触发扩容；旧代次必须被清零而不是留给 GC
        val longSecret = "S".repeat(BYTE_SINK_INITIAL_CAPACITY * 3)
        val probe = ByteJsonScanProbe()
        val root = ByteJsonScanner.scan("""{"key":"$longSecret"}""".toByteArray(Charsets.UTF_8), probe)
        assertNotNull(root)

        assertTrue(
            "扩容路径必须被覆盖到（否则本用例对旧代次没有说服力）：实际代次数 ${probe.sinkBuffers.size}",
            probe.sinkBuffers.size >= 3
        )
        probe.sinkBuffers.forEachIndexed { index, buffer ->
            assertTrue(
                "第 $index 代 ByteSink 缓冲（容量 ${buffer.size}）在 scan 返回后仍有非零字节",
                buffer.all { it == 0.toByte() })
        }
        // 正向对照：交给 AST 的副本此时**仍持有明文**（所有权已随树交给调用方，不该被提前擦）
        val astCopy = (root as ByteJson.Obj).member("key") as ByteJson.Str
        assertEquals("AST 副本此时仍应保有明文（提前擦会让调用方拿到空值）", longSecret, astCopy.bytes.decodeToString())
        root.wipe()
        assertTrue("AST 副本在调用方 wipe 后清零", astCopy.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `十一 语法失败路径同样清零工作缓冲与已分配节点`() {
        // 截断载荷：字符串已完整解出过若干次（含长串触发扩容），随后对象未闭合 ⇒ 整棵放弃
        val longSecret = "S".repeat(BYTE_SINK_INITIAL_CAPACITY * 3)
        val complete = """{"key":"$longSecret","rpId":"a"}"""
        val truncated = complete.dropLast(1)
        val probe = ByteJsonScanProbe()
        assertNull("截断输入须判为不可解析", ByteJsonScanner.scan(truncated.toByteArray(Charsets.UTF_8), probe))

        assertTrue("失败路径也须观察到缓冲代次（实际 ${probe.sinkBuffers.size}）", probe.sinkBuffers.isNotEmpty())
        probe.sinkBuffers.forEachIndexed { index, buffer ->
            assertTrue("语法失败后第 $index 代工作缓冲仍有非零字节", buffer.all { it == 0.toByte() })
        }
        probe.stringCopies.forEachIndexed { index, copy ->
            assertTrue(
                "语法失败后已放弃的第 $index 个字符串副本仍有非零字节（wipeTemporaryNodes 未覆盖）",
                copy.all { it == 0.toByte() }
            )
        }
    }

    @Test
    fun `十二 只有键名是字符串时也只清零本地缓冲且副本随树转移`() {
        val probe = ByteJsonScanProbe()
        val root = ByteJsonScanner.scan("""{"a":1,"b":[true,null]}""".toByteArray(Charsets.UTF_8), probe)
        assertNotNull(root)
        assertEquals("两个键名各一份字符串副本，值都不是字符串", 2, probe.stringCopies.size)
        assertEquals("两个键名各一代 ByteSink 缓冲", 2, probe.sinkBuffers.size)
        probe.sinkBuffers.forEach { assertTrue("工作缓冲须已清零", it.all { byte -> byte == 0.toByte() }) }
        root!!.wipe()
        probe.stringCopies.forEach { assertTrue("键名副本随 wipe 清零", it.all { byte -> byte == 0.toByte() }) }
    }

    private companion object {
        /** `ByteSink` 的初始容量；用例要构造超过它的字符串才覆盖扩容路径。 */
        const val BYTE_SINK_INITIAL_CAPACITY = 64
    }
}
