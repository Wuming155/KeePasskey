package com.keepasskey.database.file

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * `ISSUE-P2-284` AC③（`:database:` 面）：子树墓碑集合的整库加密往返回归。
 *
 * 组硬删除整改后墓碑数可达「子孙对象数 + 组自身」量级，锁定 DeletedObjects 列表
 * 在写出 → 解析全链后**逐个对象**保留（UUID 与删除时间），跨设备合并的裁决输入不丢。
 */
class SubtreeTombstoneRoundtripTest {

    @Test
    fun `子树墓碑集合整库往返逐个保留`() {
        val tombstones = (1..5).map { index ->
            DeletedObject(id = KdbxUuid(ByteArray(16) { index.toByte() }), deletionTime = T0.plusSeconds(index.toLong()))
        }
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(name = "Root"),
            deletedObjects = tombstones
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, PASSWORD)
        val reloaded = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), PASSWORD, null)

        assertEquals(
            "墓碑集合必须整库往返逐个保留（含删除时间）",
            tombstones.toSet(),
            reloaded.deletedObjects.toSet()
        )
    }

    private companion object {
        val PASSWORD = "SubtreeTombstone#2026".toCharArray()
        val T0: Instant = Instant.parse("2026-09-23T00:00:00Z")
    }
}
