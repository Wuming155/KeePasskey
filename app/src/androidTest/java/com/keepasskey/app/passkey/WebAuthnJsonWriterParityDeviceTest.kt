package com.keepasskey.app.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `WebAuthnJsonWriter` 与**平台 `org.json`** 的逐字节对拍（`ISSUE-P3-188` 第 4 项 §174 路线②）。
 *
 * 写入器的序列化语义按 AOSP `JSONStringer` 实现书写（含 `/` → `\/` 的短转义）；宿主 JVM 无法
 * 执行平台 `org.json`（Android 桩），**只有设备 / 模拟器上的 `org.json` 是真实实现**——故
 * 「写入器输出 == 平台输出」这一契约只能在设备侧常态锁定。两处 payload（注册 / 断言）改走
 * 写入器后，本用例红即意味着响应材料的字节形态发生漂移（WebAuthn RP 侧语义等价，但会破坏
 * 「只改组装、不改字节」的保守承诺），必须先逐字节核对再谈放宽。
 *
 * 平台 `JSONObject` 键序为插入序（LinkedHashMap）——键序同样在对拍范围内。
 */
@RunWith(AndroidJUnit4::class)
class WebAuthnJsonWriterParityDeviceTest {

    /** 覆盖转义判据的关键输入集：URL 斜杠 / 引号 / 反斜杠 / 短转义族 / 低段控制字符 / 非 ASCII */
    private val stringCases: List<String> = listOf(
        "https://rp.example/login?next=/a",
        "plain ascii",
        "quote \" backslash \\ end",
        "tab\tnewline\ncarriage\rbackspace\bform\u000C",
        "low\u0001\u0002\u001f",
        "空格 ok 中文 ♡ \u2028\u2029",
        ""
    )

    private fun platformString(value: String): String = JSONObject().put("k", value).toString()

    private fun writerString(value: String): String = WebAuthnJsonWriter.obj { str("k", value) }

    @Test
    fun `字符串值逐字节一致`() {
        for (value in stringCases) {
            assertEquals(
                "字符串值与平台 org.json 输出不一致：$value",
                platformString(value),
                writerString(value)
            )
        }
    }

    @Test
    fun `键序等于插入序且紧凑`() {
        val platform = JSONObject()
            .put("zeta", "1")
            .put("alpha", "2")
            .put("m", "3")
            .toString()
        val writer = WebAuthnJsonWriter.obj {
            str("zeta", "1")
            str("alpha", "2")
            str("m", "3")
        }
        assertEquals("键序与紧凑形态必须与平台一致", platform, writer)
    }

    @Test
    fun `整数字段与平台一致`() {
        // ISSUE-P2-265：写入器新增 `int`——`response.publicKeyAlgorithm` 是 COSE 算法号（可负），
        // 必须与平台 `JSONObject.put(String, int)` 的输出（十进制、无引号）逐字节一致，
        // 否则「写入器输出 == 平台输出」这条契约就破了。
        val platform = JSONObject()
            .put("publicKeyAlgorithm", -7)
            .put("zero", 0)
            .put("max", 2147483647)
            .put("min", -2147483648)
            .toString()
        val writer = WebAuthnJsonWriter.obj {
            int("publicKeyAlgorithm", -7)
            int("zero", 0)
            int("max", 2147483647)
            int("min", -2147483648)
        }
        assertEquals("整数字段与平台 org.json 输出不一致", platform, writer)
    }

    @Test
    fun `布尔与嵌套对象与数组形态一致`() {
        val platform = JSONObject()
            .put("enabled", true)
            .put("off", false)
            .put("prf", JSONObject().put("results", JSONObject().put("first", "AAA")))
            .put("transports", JSONArray().put("internal"))
            .toString()
        val writer = WebAuthnJsonWriter.obj {
            bool("enabled", true)
            bool("off", false)
            obj("prf", WebAuthnJsonWriter.Obj().apply {
                obj("results", WebAuthnJsonWriter.Obj().apply { str("first", "AAA") })
            })
            strArray("transports", listOf("internal"))
        }
        assertEquals(platform, writer)
    }
}
