package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-397：`.kdbx` 并入后对第二库擦除——必须按身份集合，禁止裸 clearSensitiveData。
 *
 * `KdbxMerger` 对单侧独有对象**复用对端实例**；若对 otherDb 调用 `clearSensitiveData()`，
 * 合并树里仍在引用的 `ProtectedString` 会被清零 ⇒ 会话打开后点击条目/库闪退
 * （`ProtectedString.checkNotCleared` 抛 IllegalStateException）。
 */
class MergeSharedProtectedStringWipeTest {

    private val rootId = KdbxUuid.random()

    @Test
    fun `身份擦除后合并树仍可读出对端独有条目 裸 clear 会清零`() {
        val localOnlyId = KdbxUuid.random()
        val remoteOnlyId = KdbxUuid.random()

        val localEntry = entry(localOnlyId, "local-only", "pw-local")
        val remoteEntry = entry(remoteOnlyId, "remote-only", "pw-remote")

        val localRoot = KdbxGroup(
            id = rootId,
            name = "Root",
            parentGroupId = null,
            entries = listOf(localEntry)
        )
        val remoteRoot = KdbxGroup(
            id = KdbxUuid.random(),
            name = "RemoteRoot",
            parentGroupId = null,
            entries = listOf(remoteEntry)
        )

        val liveDb = db(localRoot)
        val otherDb = db(remoteRoot)

        val merged = KdbxMerger.mergeDatabases(
            base = KdbxDatabaseLite(rootGroup = KdbxGroup(id = rootId, name = "Root")),
            local = KdbxDatabaseLite(rootGroup = localRoot),
            remote = KdbxDatabaseLite(rootGroup = remoteRoot)
        )
        // 生产：localDb.copy(rootGroup = merged.mergedRoot, …) 采用为会话树
        val adopted = liveDb.copy(rootGroup = merged.mergedRoot)

        // ISSUE-P3-397 生产口径
        eraseDiscardedDatabase(otherDb, live = adopted)

        val titles = adopted.rootGroup.allEntries().map { it.title }
        assertTrue(
            "并入后对端独有条目必须仍可读：$titles",
            titles.contains("remote-only")
        )
        assertTrue(titles.contains("local-only"))

        val remoteSurviving = adopted.rootGroup.allEntries().first { it.id == remoteOnlyId }
        assertEquals("remote-only", remoteSurviving.title)
        assertEquals("pw-remote", remoteSurviving.password?.readString())
    }

    private fun db(root: KdbxGroup): KdbxDatabase = KdbxDatabase(
        header = KdbxHeader(
            version = KdbxConstants.Version.VERSION_4_0,
            cipherUuid = KdbxConstants.Cipher.AES_256_CBC,
            kdfParameters = KdfParameters.Argon2(
                type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                salt = ByteArray(32),
                secretKey = ByteArray(32) { 0x5A }
            )
        ),
        rootGroup = root
    )

    private fun entry(id: KdbxUuid, title: String, password: String): KdbxEntry =
        KdbxEntry(
            id = id,
            parentGroupId = rootId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true)
            ),
            times = KdbxTimes(
                creationTime = Instant.parse("2026-01-01T00:00:00Z"),
                lastModificationTime = Instant.parse("2026-01-02T00:00:00Z")
            )
        )
}
