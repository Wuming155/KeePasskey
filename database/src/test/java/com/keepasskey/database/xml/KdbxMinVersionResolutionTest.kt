package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * ISSUE-P3-367：最小版本判定单测 + 「判定 ⇔ 序列化」对拍（同源防漂移的回归锁）。
 *
 * 对每个 4.1 特征断言**双向等价**：
 * - 判定升 4.1 ⇒ 序列化产物中确实出现该 4.1 元素（防「判定漏特征 ⇒ 声明 4.0、夹带 4.1」）；
 * - 判定保持 4.0 ⇒ 产物中确实不含该元素（防「判定过严 ⇒ 无谓抬高互操作下限」）。
 *
 * 谓词与序列化器共用 `KdbxVersion41Features` 的同一函数；本对拍在函数层之上再锁
 * 一道端到端等价，任何一侧单改（谓词 / 写出分支）都会在此现形。
 */
class KdbxMinVersionResolutionTest {

    /** 固定时间戳：与默认 `<Times>`（now）取值不同，保证按值定位 4.1 时间元素。 */
    private val fixedTime: Instant = Instant.parse("2001-02-03T04:05:06Z")

    @Test
    fun `空库判定 4_0 且产物不含任何 4_1 元素`() {
        val empty = baseDb()
        assertEquals(KdbxConstants.Version.VERSION_4_0, KdbxVersion41Features.resolveMinVersion(empty))
        val xml = xmlOf(empty)
        listOf(
            "<SettingsChanged>",
            "<MasterKeyChangeForceOnce>",
            "<QualityCheck>",
            "<Tags>",
            "<PreviousParentGroup>",
            "<Name>ProbeIcon</Name>"
        ).forEach { fragment ->
            assertFalse("空库产物不得出现 4.1 元素 $fragment", xml.contains(fragment))
        }
    }

    @Test
    fun `既有 4_1 头部无特征时判定回退 4_0`() {
        // 与官方 GetMinKdbxVersion 一致：版本只反映待写内容，不继承旧声明
        val legacy41 = baseDb(header = KdbxHeader.createDefault(useArgon2 = false).copy(version = KdbxConstants.Version.VERSION_4_1))
        assertEquals(KdbxConstants.Version.VERSION_4_0, KdbxVersion41Features.resolveMinVersion(legacy41))
    }

    @Test
    fun `Meta 层特征对拍`() {
        assertParity(
            "<SettingsChanged>",
            withFeature = baseDb { copy(settingsChanged = fixedTime) },
            withoutFeature = baseDb()
        )
        assertParity(
            "<MasterKeyChangeForceOnce>",
            withFeature = baseDb { copy(masterKeyChangeForceOnce = true) },
            withoutFeature = baseDb()
        )
        // 图标：仅有数据 vs 带名 / 带时间——判据是 4.1 追加字段而非图标本身
        assertParity(
            "<Name>ProbeIcon</Name>",
            withFeature = baseDb { copy(customIcons = listOf(icon(name = "ProbeIcon"))) },
            withoutFeature = baseDb { copy(customIcons = listOf(icon())) }
        )
        val iconTimeFragment = timeFragment(fixedTime)
        assertParity(
            iconTimeFragment,
            withFeature = baseDb { copy(customIcons = listOf(icon(lastModificationTime = fixedTime))) },
            withoutFeature = baseDb { copy(customIcons = listOf(icon())) }
        )
        // Meta CustomData：有条目无时间戳 = 4.0；带该键时间戳 = 4.1
        assertParity(
            iconTimeFragment,
            withFeature = baseDb { copy(customData = mapOf("probeKey" to "v"), customDataTimes = mapOf("probeKey" to fixedTime)) },
            withoutFeature = baseDb { copy(customData = mapOf("probeKey" to "v")) }
        )
    }

    @Test
    fun `条目层特征对拍`() {
        assertParity(
            "<QualityCheck>",
            withFeature = dbWithEntry(KdbxEntry(qualityCheck = false)),
            withoutFeature = dbWithEntry(KdbxEntry())
        )
        assertParity(
            "<Tags>",
            withFeature = dbWithEntry(KdbxEntry(tags = listOf("probeTag"))),
            withoutFeature = dbWithEntry(KdbxEntry())
        )
        assertParity(
            "<PreviousParentGroup>",
            withFeature = dbWithEntry(KdbxEntry(previousParentGroup = KdbxUuid.random())),
            withoutFeature = dbWithEntry(KdbxEntry())
        )
        // 历史快照内的 4.1 特征同样被写出与计数（序列化器 isHistory 分支镜像）
        assertParity(
            "<Tags>",
            withFeature = dbWithEntry(KdbxEntry(history = listOf(KdbxEntry(tags = listOf("histTag"))))),
            withoutFeature = dbWithEntry(KdbxEntry(history = listOf(KdbxEntry())))
        )
    }

    @Test
    fun `分组层特征对拍`() {
        assertParity(
            "<Tags>",
            withFeature = baseDb { copy(rootGroup = KdbxGroup(name = "Root", subgroups = listOf(KdbxGroup(name = "Sub", tags = listOf("probeTag"))))) },
            withoutFeature = baseDb()
        )
        assertParity(
            "<PreviousParentGroup>",
            withFeature = baseDb { copy(rootGroup = KdbxGroup(name = "Root", subgroups = listOf(KdbxGroup(name = "Sub", previousParentGroup = KdbxUuid.random())))) },
            withoutFeature = baseDb()
        )
    }

    /**
     * 端到端：带 4.1 特征的库经 `KdbxFile.save` 落盘必须声明 4.1（头部第 8..11 字节 = 0x00040001），
     * 读回版本保留——覆盖 save 组装 `updatedHeader` 的唯一决策点接线。
     */
    @Test
    fun `带4_1特征的库保存声明4_1`() {
        val fastAes = KdfParameters.Aes(seed = ByteArray(32) { 7 }, rounds = 10L)
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false).copy(kdfParameters = fastAes),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(KdbxEntry(tags = listOf("interop"))))
        )
        val password = "P3-367@parity".toCharArray()

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val bytes = bos.toByteArray()
        val writtenVersion = (bytes[8].toInt() and 0xFF) or
                ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or
                ((bytes[11].toInt() and 0xFF) shl 24)
        assertEquals(0x00040001, writtenVersion)

        val loaded = KdbxFile.load(ByteArrayInputStream(bytes), password)
        assertEquals(KdbxConstants.Version.VERSION_4_1, loaded.header.version)
        assertEquals(listOf("interop"), loaded.rootGroup.entries[0].tags)
    }

    // ===================== 对拍与构造助手 =====================

    /**
     * 对拍核心断言：「判定升 4.1」⇔「产物含该元素」，「判定 4.0」⇔「产物不含该元素」。
     * [withFeature] 与 [withoutFeature] 应仅在目标特征上不同，其余字段保持一致。
     */
    private fun assertParity(fragment: String, withFeature: KdbxDatabase, withoutFeature: KdbxDatabase) {
        assertEquals(
            "有特征必须判定 4.1：$fragment",
            KdbxConstants.Version.VERSION_4_1,
            KdbxVersion41Features.resolveMinVersion(withFeature)
        )
        assertEquals(
            "无特征必须判定 4.0：$fragment",
            KdbxConstants.Version.VERSION_4_0,
            KdbxVersion41Features.resolveMinVersion(withoutFeature)
        )
        assertTrue(
            "判定 4.1 但序列化产物缺元素 $fragment",
            xmlOf(withFeature).contains(fragment)
        )
        assertFalse(
            "判定 4.0 但序列化产物夹带元素 $fragment",
            xmlOf(withoutFeature).contains(fragment)
        )
    }

    private fun baseDb(
        header: KdbxHeader = KdbxHeader.createDefault(useArgon2 = false),
        transform: KdbxDatabase.() -> KdbxDatabase = { this }
    ): KdbxDatabase = KdbxDatabase(
        header = header,
        rootGroup = KdbxGroup(name = "Root")
    ).transform()

    private fun dbWithEntry(entry: KdbxEntry): KdbxDatabase =
        baseDb { copy(rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))) }

    private fun icon(
        name: String = "",
        lastModificationTime: Instant? = null
    ): CustomIcon = CustomIcon(
        uuid = KdbxUuid.random(),
        data = byteArrayOf(1, 2, 3),
        name = name,
        lastModificationTime = lastModificationTime
    )

    /** 4.1 时间元素按值定位（`<LastModificationTime>` 在 `<Times>` 中恒出现，须带值区分）。 */
    private fun timeFragment(instant: Instant): String =
        "<LastModificationTime>${KdbxXmlTimeHelper.formatDate(instant)}</LastModificationTime>"

    private fun xmlOf(db: KdbxDatabase): String {
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, ByteArray(64) { 1 })
        val bos = ByteArrayOutputStream()
        KdbxXmlSerializer(cipher).serialize(bos, db)
        return bos.toByteArray().toString(Charsets.UTF_8)
    }
}
