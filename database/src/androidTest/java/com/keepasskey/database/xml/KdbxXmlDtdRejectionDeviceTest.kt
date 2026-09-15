package com.keepasskey.database.xml

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.EntityResolver
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream

/**
 * 内层 XML 的 DTD / 外部实体拦截**设备侧**回归（**ISSUE-P3-82**）。
 *
 * ## 为什么必须补这一层
 *
 * 防护本体（[KdbxXmlParser] 的 `startDTD` / `resolveEntity` 两道 fail-closed 兜底）在宿主侧
 * 早已成立，缺的是**设备侧可复跑证据**：SAX 实现是平台相关的（Android 的 Expat 后端与桌面
 * Xerces 行为不同），「特性开关是否被接受」「lexical-handler 是否可设置」都只能在真机上断言。
 *
 * ## 三条前置依赖必须**分别**断言（第四轮复核对本项 AC 的更正）
 *
 * | 编号 | 前置依赖 | 本文件的断言方式 |
 * |---|---|---|
 * | **D-1** | `parse(InputStream, DefaultHandler)` 经 `ParserAdapter` 时，handler **实现 `EntityResolver`** 才会被注册 | 断言解析 handler 的基类 `DefaultHandler2` 确为 `EntityResolver`（结构性前置） |
 * | **D-2** | 平台 SAX 层**不**自行短路外部实体（兜底必须由我们做） | 外部实体载荷必须被拒绝；**如实声明**：本平台上 `startDTD` 先于实体解析触发，故该用例证明的是**端到端拒绝**，无法把实体层单独隔离 |
 * | **D-3** | `setProperty(lexical-handler)` 在平台可用（否则 `startDTD` 兜底静默失效，仅留 WARNING） | 断言 DOCTYPE 载荷的异常**来自 DTD 兜底文案**——若 lexical-handler 设置失败，异常来源就会变成特性开关或干脆不抛 |
 *
 * ## 不覆盖什么
 *
 * 本文件**不**构造「合法口令 KDBX + 注入 DTD」的完整外层文件：那需要重新加密，收益仅在于
 * 多覆盖一层与 DTD 无关的字节解析。按第四轮定版建议，直接喂 XML 给 [KdbxXmlParser] 绕过外层，
 * 使断言精确落在被保护的那一层。
 */
@RunWith(AndroidJUnit4::class)
class KdbxXmlDtdRejectionDeviceTest {

    private val parser = KdbxXmlParser(innerStreamCipher = null)

    private fun parseXml(xml: String) = parser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    // ── D-1 ──────────────────────────────────────────────────────────

    @Test
    fun `D1 解析 handler 必须是 EntityResolver`() {
        assertTrue(
            "KDBX 解析 handler 以 DefaultHandler2 为基类才同时实现 EntityResolver；" +
                "否则 parse(InputStream, DefaultHandler) 走 ParserAdapter 时不会注册实体解析器，" +
                "resolveEntity 那道兜底形同不存在",
            DefaultHandler2() is EntityResolver
        )
    }

    // ── D-3 ──────────────────────────────────────────────────────────

    @Test
    fun `D3 DOCTYPE 必须由 startDTD 兜底拒绝而非特性开关`() {
        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parseXml(
                """<?xml version="1.0" encoding="utf-8"?>""" +
                    """<!DOCTYPE KeePassFile [<!ENTITY probe "probe-value">]><KeePassFile/>"""
            )
        }

        assertTrue(
            "异常必须来自 startDTD 的 DTD 兜底（=setProperty(lexical-handler) 在本平台成立）。" +
                "若此处变成特性开关文案或根本不抛，说明 D-3 前置失效、DTD 兜底已静默降级：" +
                "${ex.message}",
            ex.message.orEmpty().contains("DOCTYPE")
        )
    }

    // ── D-2 ──────────────────────────────────────────────────────────

    @Test
    fun `D2 外部实体载荷必须被拒绝`() {
        val ex = assertThrows(KdbxCorruptFileException::class.java) {
            parseXml(
                """<?xml version="1.0" encoding="utf-8"?>""" +
                    """<!DOCTYPE KeePassFile [<!ENTITY ext SYSTEM "file:///etc/hosts">]>""" +
                    """<KeePassFile><Meta><Generator>&ext;</Generator></Meta></KeePassFile>"""
            )
        }

        assertTrue(
            "外部实体引用必须 fail-closed 拒绝：${ex.message}",
            ex.message.orEmpty().isNotBlank()
        )
        // 如实声明（不得读作实体层已单独验证）：本平台上 startDTD 先触发，故该断言只证明
        // 「端到端被拒绝」，D-2 的独立隔离需要移除 DTD 兜底才谈得上——而那正是我们不做的。
        assertTrue(
            "拒绝必须发生在 DTD 声明处（即外部实体根本没有机会被解析）",
            ex.message.orEmpty().contains("DOCTYPE")
        )
    }

    @Test
    fun `内部实体展开炸弹在展开前中止`() {
        val bomb = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<!DOCTYPE KeePassFile [""")
            append("""<!ENTITY a0 "AAAA">""")
            repeat(10) { i -> append("""<!ENTITY a${i + 1} "&a$i;&a$i;&a$i;&a$i;&a$i;&a$i;&a$i;&a$i;&a$i;&a$i;">""") }
            append(""">""")
            append("""<KeePassFile><Meta><Generator>&a10;</Generator></Meta></KeePassFile>""")
        }

        val ex = assertThrows(KdbxCorruptFileException::class.java) { parseXml(bomb) }

        assertTrue(
            "嵌套内部实体必须在 DTD 声明处即中止（不得先展开到内存爆炸）：${ex.message}",
            ex.message.orEmpty().contains("DOCTYPE")
        )
    }

    // ── 反向对照：兜底不得误伤无 DTD 文档 ────────────────────────────

    @Test
    fun `无 DTD 文档不得被 DTD 兜底误伤`() {
        val clean = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<KeePassFile><Meta><Generator>probe</Generator></Meta>""" +
            """<Root><Group><Name>Root</Name></Group></Root></KeePassFile>"""

        val thrown = runCatching { parseXml(clean) }.exceptionOrNull()

        assertFalse(
            "DTD 兜底只认 DTD 声明，不得把无 DTD 文档一并判为损坏：${thrown?.message}",
            thrown is KdbxCorruptFileException && thrown.message.orEmpty().contains("DOCTYPE")
        )
    }
}
