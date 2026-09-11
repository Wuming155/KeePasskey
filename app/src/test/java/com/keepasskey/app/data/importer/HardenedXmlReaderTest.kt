package com.keepasskey.app.data.importer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.ext.DefaultHandler2
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * [HardenedXmlReader] 解析器构建降级逻辑单测（ISSUE-P1-15）。
 *
 * 设备实测（2026-09-11，AVD Pixel_10 / API 36）：Android Harmony SAX 对
 * `resolve-dtd-uris` 等特性的拒绝**延迟到 `newSAXParser()` 才抛**
 * `ParserConfigurationException`，导致明文导入在设备上全量失败（宿主 JVM
 * Xerces 无此行为，原实现零改动即可通过全部宿主用例——「宿主绿、设备挂」类缺陷）。
 *
 * 本测试注入假工厂复现该延迟拒绝行为，锁定「特性被拒不得阻断导入」的降级契约；
 * 安全兜底语义（handler 侧 startDTD / resolveEntity fail-closed）不变。
 */
class HardenedXmlReaderTest {

    @After
    fun restoreFactory() {
        HardenedXmlReader.factoryProvider = { SAXParserFactory.newInstance() }
    }

    @Test
    fun `newSAXParser 延迟拒绝特性时逐轮剔除并成功解析`() {
        val deferredRejectingFactory = DeferredRejectFactory(
            deferredRejectedFeature = HardenedXmlReaderTestHelper.FEATURE_RESOLVE_DTD_URIS
        )
        HardenedXmlReader.factoryProvider = { deferredRejectingFactory.freshInstance() }

        val handler = KeePassXmlImportHandler(ImportWarningCollector())
        val doc = HardenedXmlReaderTestHelper.minimalDocument()
        HardenedXmlReader.parse(doc, handler)

        val batch = handler.buildBatch(ImportSource.KEEPASS_XML)
        assertEquals(1, batch.report.parsed)
        assertTrue("假工厂的拒绝分支未被触发，测试失效", deferredRejectingFactory.rejectedOnce.get())
    }

    @Test
    fun `普通合法 KeePass XML 全量特性下解析成功`() {
        HardenedXmlReader.factoryProvider = { SAXParserFactory.newInstance() }

        val handler = KeePassXmlImportHandler(ImportWarningCollector())
        HardenedXmlReader.parse(HardenedXmlReaderTestHelper.minimalDocument(), handler)

        assertEquals(1, handler.buildBatch(ImportSource.KEEPASS_XML).report.parsed)
    }

    @Test
    fun `日志告警不打断解析（静默降级不抛出）`() {
        // 平台对个别特性的 setFeature 直接抛 SAXNotRecognizedException 属既有告警路径；
        // 真实解析必须不受日志副作用影响。
        val noisyLogger = Logger.getLogger(HardenedXmlReader::class.java.name)
        val originalLevel = noisyLogger.level
        noisyLogger.level = Level.OFF
        try {
            val handler = KeePassXmlImportHandler(ImportWarningCollector())
            HardenedXmlReader.parse(HardenedXmlReaderTestHelper.minimalDocument(), handler)
            assertEquals(1, handler.buildBatch(ImportSource.KEEPASS_XML).report.parsed)
        } finally {
            noisyLogger.level = originalLevel
        }
    }
}

/** 测试辅助（与被测对象同包）。 */
private object HardenedXmlReaderTestHelper {
    val FEATURE_RESOLVE_DTD_URIS: String = HardenedXmlReader.FEATURE_RESOLVE_DTD_URIS

    fun minimalDocument(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <KeePassFile>
          <Meta></Meta>
          <Root>
            <Group>
              <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
              <Name>Root</Name>
              <Entry>
                <String><Key>Title</Key><Value>站点</Value></String>
                <String><Key>UserName</Key><Value>user</Value></String>
                <String><Key>Password</Key><Value>p</Value></String>
              </Entry>
            </Group>
          </Root>
        </KeePassFile>
    """.trimIndent()
}

/**
 * 假工厂：记录 setFeature 请求，`newSAXParser()` 时若目标特性仍在待用集则
 * 抛 [ParserConfigurationException]（复现 Android Harmony 的延迟拒绝行为）；
 * 否则把剩余特性转发给真实平台工厂产出可用解析器。
 */
private class DeferredRejectFactory(
    private val deferredRejectedFeature: String
) {
    val rejectedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    fun freshInstance(): SAXParserFactory = object : SAXParserFactory() {
        val requested = linkedMapOf<String, Boolean>()
        val delegate = SAXParserFactory.newInstance().also {
            it.isNamespaceAware = false
        }

        override fun setFeature(name: String, value: Boolean) {
            requested[name] = value
        }

        override fun getFeature(name: String): Boolean =
            requested[name] ?: delegate.getFeature(name)

        override fun newSAXParser(): SAXParser {
            if (requested[deferredRejectedFeature] == false) {
                rejectedOnce.set(true)
                throw ParserConfigurationException("deferred rejection: $deferredRejectedFeature")
            }
            for ((feature, value) in requested) {
                runCatching { delegate.setFeature(feature, value) }
            }
            return delegate.newSAXParser()
        }

        override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware()
        override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware()
        override fun setNamespaceAware(awareness: Boolean) = delegate.setNamespaceAware(awareness)
        override fun setValidating(validating: Boolean) = delegate.setValidating(validating)
        override fun isValidating(): Boolean = delegate.isValidating()
        override fun setXIncludeAware(state: Boolean) {
            runCatching { delegate.setXIncludeAware(state) }
        }
    }
}
