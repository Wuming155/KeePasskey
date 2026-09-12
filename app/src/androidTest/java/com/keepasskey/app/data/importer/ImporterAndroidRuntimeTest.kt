package com.keepasskey.app.data.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * app 层**设备侧（instrumented）**导入解析回归（ISSUE-P2-27 解析层 / P3-66 前置）。
 *
 * ## 为什么必须有这一层
 *
 * `app` / `sync` 模块此前**没有 androidTest 源集**，导入解析仅在宿主 JVM 覆盖。而本仓已两次发生
 * 「JVM 全绿、Android 运行时挂」的逃逸缺陷：
 * - ISSUE-P1-12：明文导入 KeePass XML 在 Android 运行时**全量失败**；
 * - ISSUE-P0-04：字段引用正则在 Android ICU 上**非法**，致列表必崩。
 *
 * 两者都是**平台运行时差异**（XML 解析器 / ICU 正则 / 字符集），x86_64 模拟器即可拦截。
 * 本用例把导入解析放回真实 Android 运行时执行，作为该缺陷类的设备侧看门用例。
 *
 * 敏感纪律：样本口令为伪造测试值；断言用 `contentEquals`，不把明文写入断言消息。
 */
@RunWith(AndroidJUnit4::class)
class ImporterAndroidRuntimeTest {

    @Test
    fun `KeePass XML 导入在 Android 运行时完整映射字段`() = runBlocking {
        val importer = KeePassXmlImporter()

        val result = importer.parse(KEEPASS_XML.toByteArray(Charsets.UTF_8), "sample.xml")

        assertTrue(
            "期望解析成功，实际返回 Failure: ${(result as? KdbxResult.Failure)?.error?.javaClass?.name}",
            result is KdbxResult.Success
        )
        val batch = (result as KdbxResult.Success).data
        assertEquals(1, batch.report.parsed)
        val entry = batch.entries.single()
        assertEquals("站点A", entry.title)
        assertEquals("user@example.com", entry.username)
        assertEquals("https://example.com", entry.url)
        assertEquals("备注", entry.notes)
        assertEquals(FAKE_PASSWORD.length, entry.password.size)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD.toCharArray()))
    }

    @Test
    fun `浏览器 CSV 导入在 Android 运行时按表头映射且列序无关`() = runBlocking {
        val importer = BrowserCsvImporter()

        val result = importer.parse(BROWSER_CSV.toByteArray(Charsets.UTF_8), "browser.csv")

        assertTrue(
            "期望解析成功，实际返回 Failure: ${(result as? KdbxResult.Failure)?.error?.javaClass?.name}",
            result is KdbxResult.Success
        )
        val batch = (result as KdbxResult.Success).data
        assertEquals(1, batch.report.parsed)
        val entry = batch.entries.single()
        assertEquals("站点B", entry.title)
        assertEquals("user2", entry.username)
        assertEquals("https://b.example", entry.url)
        assertTrue(entry.password.contentEquals(FAKE_PASSWORD_2.toCharArray()))
        assertTrue(entry.groupPath.isEmpty())
    }

    @Test
    fun `非 KeePassFile 根的 XML 在 Android 运行时 fail-closed`() = runBlocking {
        val importer = KeePassXmlImporter()

        val result = importer.parse("<NotKeePassFile/>".toByteArray(Charsets.UTF_8), "bad.xml")

        assertTrue("根元素不符必须 fail-closed", result.isFailure)
    }

    private companion object {
        const val FAKE_PASSWORD = "fake-pass-123"
        const val FAKE_PASSWORD_2 = "fake-pass-456"

        val KEEPASS_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <KeePassFile>
              <Meta><Generator>KeePass</Generator></Meta>
              <Root>
                <Group>
                  <Name>Root</Name>
                  <Entry>
                    <String><Key>Title</Key><Value>站点A</Value></String>
                    <String><Key>UserName</Key><Value>user@example.com</Value></String>
                    <String><Key>Password</Key><Value>$FAKE_PASSWORD</Value></String>
                    <String><Key>URL</Key><Value>https://example.com</Value></String>
                    <String><Key>Notes</Key><Value>备注</Value></String>
                  </Entry>
                </Group>
              </Root>
            </KeePassFile>
        """.trimIndent()

        val BROWSER_CSV = "password,url,username,name\n$FAKE_PASSWORD_2,https://b.example,user2,站点B\n"
    }
}
