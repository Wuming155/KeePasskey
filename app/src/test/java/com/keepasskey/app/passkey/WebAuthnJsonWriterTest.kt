package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `WebAuthnJsonWriter` 的序列化语义回归（宿主可执行——本写入器存在的意义）。
 *
 * 判据基准：**Android 平台 `org.json`（AOSP）的输出形态**（两处 payload 组装改走本写入器前的
 * 既有行为）。字符串转义规则详见 [WebAuthnJsonWriter] KDoc；设备侧另有与平台 `org.json`
 * 的**逐字节对拍**（`WebAuthnJsonWriterParityDeviceTest`）常态锁定。
 *
 * 控制字符以 `\uXXXX` 源码转义书写（Kotlin 无 `\f` 短转义，裸控制字符不得进源码）；
 * expected 侧的 `\uXXXX` / `\t` 等是**字面反斜杠文本**（与写入器输出一致），勿「顺手改单斜杠」。
 */
class WebAuthnJsonWriterTest {

    @Test
    fun `对象为紧凑输出且键序等于插入序`() {
        val json = WebAuthnJsonWriter.obj {
            str("zeta", "1")
            str("alpha", "2")
            str("mid", "3")
        }
        assertEquals("""{"zeta":"1","alpha":"2","mid":"3"}""", json)
    }

    @Test
    fun `空对象`() {
        assertEquals("{}", WebAuthnJsonWriter.obj { })
    }

    @Test
    fun `布尔值输出为 true 与 false 字面量`() {
        val json = WebAuthnJsonWriter.obj {
            bool("on", true)
            bool("off", false)
        }
        assertEquals("""{"on":true,"off":false}""", json)
    }

    @Test
    fun `嵌套对象与字符串数组`() {
        val json = WebAuthnJsonWriter.obj {
            obj("outer", WebAuthnJsonWriter.Obj().apply {
                strArray("list", listOf("a", "b"))
                bool("flag", true)
            })
        }
        assertEquals("""{"outer":{"list":["a","b"],"flag":true}}""", json)
    }

    @Test
    fun `URL 斜杠按 AOSP 语义转义`() {
        // Android 平台 org.json 把 / 转义为 \/ —— origin（https://…）在设备上的既有字节形态
        assertEquals("""{"origin":"https:\/\/rp.example\/login"}""", WebAuthnJsonWriter.obj {
            str("origin", "https://rp.example/login")
        })
    }

    @Test
    fun `引号反斜杠与短转义控制字符`() {
        // input 字节: a " b \ c TAB d NL e CR f FF g BS i
        val input = "a\"b\\c\td\ne\rf\u000Cg\u0008i"
        // 写入器输出的字面文本: {"v":"a\"b\c\td\ne\rf\fg\bi"}
        val expected = "{\"v\":\"a\\\"b\\\\c\\td\\ne\\rf\\fg\\bi\"}"
        assertEquals(expected, WebAuthnJsonWriter.obj { str("v", input) })
    }

    @Test
    fun `其余控制字符写小写四位十六进制且补零`() {
        val expected = "{\"v\":\"a\\u0001b\\u0002c\\u001f\"}"
        val input = "a\u0001b\u0002c\u001f"
        assertEquals(expected, WebAuthnJsonWriter.obj { str("v", input) })
    }

    @Test
    fun `空格与非 ASCII 字符原样输出`() {
        // U+2028 / U+2029 > 0x1F，AOSP 原样输出——两侧经 Kotlin 源码转义构造出**实际**字符
        val input = "空格 ok 中文 ♡ \u2028\u2029"
        val expected = "{\"v\":\"空格 ok 中文 ♡ \u2028\u2029\"}"
        assertEquals(expected, WebAuthnJsonWriter.obj { str("v", input) })
    }
}
