package com.keepasskey.database.file

import com.keepasskey.database.xml.KdbxXmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * `ISSUE-P2-282` AC④：逗号库（pykeepass 写出）读入 → 标签数正确 → 回写为官方裸 `;` 形态。
 *
 * Fixture：`fixtures/tags/pykeepass-comma-tags.kdbx`（口令 `tags-fixture-2026`，
 * 内含 `<Tags>alpha,beta,gamma</Tags>` 逗号形态，出处与期望值见同目录 `FIXTURE.md`）。
 *
 * AC⑤ 的官方实现对拍（keepassxc-cli / pykeepass 读本仓产物）由
 * `KdbxTagsInteropProbeTest` 产出产物 + 批次文档留证承接，本类锁本仓侧往返。
 */
class KdbxTagsFixtureRoundtripTest {

    @Test
    fun `逗号库读入：标签数正确（不再读成单个标签）`() {
        val db = loadFixture()
        val entry = db.rootGroup.allEntries().single { it.fields[com.keepasskey.core.model.KdbxConstants.Fields.TITLE]?.readString() == "CommaTagsEntry" }
        assertEquals(
            "逗号分隔的 3 个标签必须完整解析（整改前只按 ; 切会读成 1 个）",
            listOf("alpha", "beta", "gamma"),
            entry.tags
        )
    }

    @Test
    fun `回写：XML 为官方裸分号形态（对方可读回正确标签数）`() {
        val db = loadFixture()
        val baos = ByteArrayOutputStream()
        KdbxXmlSerializer(null).serialize(baos, db)
        val xml = baos.toString(Charsets.UTF_8.name())
        assertTrue(
            "回写必须是官方裸 `;` 形态（不再写 `; `）",
            xml.contains("<Tags>alpha;beta;gamma</Tags>")
        )
    }

    @Test
    fun `整库加密往返：标签集合不变`() {
        val db = loadFixture()
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, db, PASSWORD)
        val reloaded = KdbxFile.load(baos.toByteArray().inputStream(), PASSWORD, null)
        val entry = reloaded.rootGroup.allEntries().single {
            it.fields[com.keepasskey.core.model.KdbxConstants.Fields.TITLE]?.readString() == "CommaTagsEntry"
        }
        assertEquals(listOf("alpha", "beta", "gamma"), entry.tags)
    }

    private fun loadFixture(): KdbxDatabase {
        val stream = requireNotNull(javaClass.getResourceAsStream(FIXTURE_RESOURCE_PATH)) {
            "fixture 缺失：$FIXTURE_RESOURCE_PATH（出处见 fixtures/tags/FIXTURE.md）"
        }
        return stream.use { KdbxFile.load(it, PASSWORD, null) }
    }

    private companion object {
        const val FIXTURE_RESOURCE_PATH = "/fixtures/tags/pykeepass-comma-tags.kdbx"
        val PASSWORD = "tags-fixture-2026".toCharArray()
    }
}
