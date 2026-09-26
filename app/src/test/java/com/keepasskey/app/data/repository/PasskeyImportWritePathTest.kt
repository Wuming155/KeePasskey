package com.keepasskey.app.data.repository

import com.keepasskey.app.passkey.ImportedPasskey
import com.keepasskey.app.passkey.PasskeyImportFactory
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 3 片 AC④ / 口径 5：两条写通道的**落库形状**与定位语义。
 *
 * 用 [FakeVaultRepository] 的 passkey 写入口（与生产同契约的测试替身），锁四件事：
 * ① AC④① 落库条目**只含** passkey schema 键，找不到整段 JSON 载荷的任何副本（明文面逐值扫）；
 * ② AC④② 保护位逐键（观测点存的是 [KdbxCustomField]，正是为了让这条断言不空转——
 *   Fake 若只记 `Map<String,String>`，`isProtected` 就丢了，断言会**假绿**）；
 * ③ 顶栏新建须落在调用方给的分组（`parentGroupId` 参数是本轮新加的，默认 null＝根组）；
 * ④ 编辑页 Q1 的「挂当前条目」必须走 `replacePasskeyOnEntry(entryId,…)`：
 *   既有的 `saveOrReplacePasskeyEntry` 按 **rpId+userName** 定位，**不保证**命中正在编辑那一条
 *   （本用例给出可判定的对照，防止后来者以「复用现成的就行」把 Q1 语义写歪）。
 */
class PasskeyImportWritePathTest {

    private fun imported(rpId: String = "webauthn.io", userName: String = "johndoe"): ImportedPasskey =
        ImportedPasskey(
            relyingPartyId = rpId,
            credentialId = "Y3JlZGVudGlhbElkRXhhbXBsZQ",
            userHandle = "cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY",
            userName = userName,
            userDisplayName = "John Doe",
            privateKeyPemChars = "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----\n".toCharArray(),
            algorithmId = PasskeyData.ALGORITHM_ES256,
            prfWithUv = ByteArray(32) { (it + 1).toByte() },
            prfWithoutUv = ByteArray(32) { (it + 7).toByte() }
        )

    /** 期望出现在库内的全部键（AC④① 的形状判据）。 */
    private val expectedKeys = setOf(
        PasskeyData.KPEX_FIELD_RELYING_PARTY,
        PasskeyData.KPEX_FIELD_USERNAME,
        PasskeyData.KPEX_FIELD_USER_HANDLE,
        PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
        PasskeyData.FIELD_PRIVATE_KEY,
        PasskeyData.KPEX_FIELD_FLAG_BE,
        PasskeyData.KPEX_FIELD_FLAG_BS,
        PasskeyData.KPEX_FIELD_PRF,
        PasskeyData.FIELD_PRF_NO_UV,
        PasskeyData.FIELD_ALGORITHM,
        PasskeyData.FIELD_SIGN_COUNT,
        PasskeyData.FIELD_USER_DISPLAY_NAME,
        PasskeyData.FIELD_CREATED_AT
    )

    @Test
    fun `一 顶栏新建落调用方给的分组 落库形状与保护位逐键合规`() = runTest {
        val repository = FakeVaultRepository()
        val group = KdbxUuid.random()
        val entry = repository.saveNewPasskeyEntry(
            data = PasskeyImportFactory.toPasskeyData(imported()),
            boundPackage = null,
            parentGroupId = group
        )
        assertEquals("顶栏要求落在用户当下所在分组", group, entry.parentGroupId)

        val fields = requireNotNull(repository.lastSavedPasskeyByEntry[entry.id.toHexString()]) {
            "Fake 观测点没记到 passkey 字段（是否漏了 recordPasskeyFields 一类的写入点）"
        }
        assertEquals("落库键集必须恰为预期 schema 键（多一个键就是载荷副本的藏身处）", expectedKeys, fields.map { it.key }.toSet())
        for (key in listOf(
            PasskeyData.KPEX_FIELD_USER_HANDLE,
            PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
            PasskeyData.FIELD_PRIVATE_KEY,
            PasskeyData.KPEX_FIELD_PRF,
            PasskeyData.FIELD_PRF_NO_UV
        )) {
            assertTrue("$key 必须受保护", requireNotNull(fields.firstOrNull { it.key == key }).value.isProtected)
        }
        for (key in listOf(
            PasskeyData.KPEX_FIELD_RELYING_PARTY,
            PasskeyData.KPEX_FIELD_USERNAME,
            PasskeyData.KPEX_FIELD_FLAG_BE,
            PasskeyData.KPEX_FIELD_FLAG_BS
        )) {
            assertFalse("$key 必须明文", requireNotNull(fields.firstOrNull { it.key == key }).value.isProtected)
        }
        // AC④①：明文面扫不出一段 JSON 的痕迹（不整段落库）
        for (field in fields.filterNot { it.value.isProtected }) {
            val text = field.value.readString()
            assertTrue(
                "非受保护字段 ${field.key} 里出现 JSON 形状内容：$text",
                text.none { it == '{' || it == '}' } && !text.contains("\"credentialId\"")
            )
        }
    }

    @Test
    fun `二 编辑页按 entryId 整体替换 保留其它字段且 schema 键不残留`() = runTest {
        val repository = FakeVaultRepository()
        val first = repository.saveNewPasskeyEntry(PasskeyImportFactory.toPasskeyData(imported()), null)
        val titleBefore = first.title
        val urlBefore = first.url

        val second = PasskeyImportFactory.toPasskeyData(imported(rpId = "second.example", userName = "other"))
        val replaced = repository.replacePasskeyOnEntry(first.id.toHexString(), second)
        assertNotNull("按 id 定位必须命中刚建好的那条", replaced)
        val target = requireNotNull(replaced)
        assertEquals("非 passkey 字段（标题）必须保留", titleBefore, target.title)
        assertEquals("非 passkey 字段（URL）必须保留", urlBefore, target.url)
        val passkey = requireNotNull(PasskeyData.fromCustomFields(target.customFields))
        assertEquals("second.example", passkey.relyingPartyId)
        assertEquals("其它内容保留的前提下 passkey 字段整体换新", second.credentialId, passkey.credentialId)
        assertNotNull("第二枚种子须随新值写出", passkey.prfNoUvSecret)

        // 旧值不得残留：先造一条带旧 PrfNoUv 的库，替换后必须是新值而非两值并存
        val third = PasskeyImportFactory.toPasskeyData(imported(rpId = "third.example"))
        val again = requireNotNull(repository.replacePasskeyOnEntry(first.id.toHexString(), third))
        val noUvValues = again.customFields.filter { it.key == PasskeyData.FIELD_PRF_NO_UV }
        assertEquals("同一 schema 键只允许一份（残留即 isPasskeyFieldKey 覆盖面漏了它）", 1, noUvValues.size)
        assertEquals(third.credentialId, requireNotNull(PasskeyData.fromCustomFields(again.customFields)).credentialId)
    }

    @Test
    fun `三 未命中 entryId 时返回 null 且零写入`() = runTest {
        val repository = FakeVaultRepository()
        repository.saveNewPasskeyEntry(PasskeyImportFactory.toPasskeyData(imported()), null)
        val observedBefore = repository.lastSavedPasskeyByEntry.size
        val missing = KdbxUuid.random().toHexString()
        assertNull("条目不存在必须返回 null（调用方据此如实提示，不得假装成功）", repository.replacePasskeyOnEntry(missing, PasskeyImportFactory.toPasskeyData(imported())))
        assertEquals("未命中路径不得产生任何写库观测", observedBefore, repository.lastSavedPasskeyByEntry.size)
        assertNull(repository.replacePasskeyOnEntry("not-a-uuid", PasskeyImportFactory.toPasskeyData(imported())))
    }

    /**
     * 为什么 Q1 不能直接复用 `saveOrReplacePasskeyEntry`：它按 rpId+userName 检索条目，
     * 与「用户正在编辑这一条」不是同一件事——库里存在另一条同站点同用户名的条目时，
     * 它会把导入结果写到**检索命中的那一条**上，正在编辑的条目反而没被挂上。
     */
    @Test
    fun `四 现成的按内容检索会命中另一条目而非当前条目`() = runTest {
        val repository = FakeVaultRepository()
        val first = repository.saveNewPasskeyEntry(PasskeyImportFactory.toPasskeyData(imported()), null)
        // 同 rpId + 同用户名的第二条（生产中这不常发生，但正是「按内容检索」与「按 id 定位」的分岔点）
        val editing = repository.saveNewPasskeyEntry(PasskeyImportFactory.toPasskeyData(imported()), null)
        assertFalse(first.id == editing.id)

        val incoming = PasskeyImportFactory.toPasskeyData(imported(rpId = "webauthn.io", userName = "johndoe"))
        val touched = repository.saveOrReplacePasskeyEntry(incoming, null)
        assertEquals(
            "saveOrReplace 命中的是「同 rpId+同用户名」检索到的第一条，而不是正在编辑的那一条",
            first.id,
            touched.id
        )
        assertNotEquals(equals = editing.id, other = touched.id)

        // 按 id 的入口只动被点名的那一条
        val exact = requireNotNull(repository.replacePasskeyOnEntry(editing.id.toHexString(), incoming))
        assertEquals("按 id 的入口只动这一条", editing.id, exact.id)
    }

    private fun assertNotEquals(equals: KdbxUuid, other: KdbxUuid) {
        assertTrue("按 id 定位与按内容检索必须落到不同条目，否则本例无意义", equals != other)
    }
}
