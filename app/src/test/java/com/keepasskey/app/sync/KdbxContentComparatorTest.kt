package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ISSUE-P2-91`：会话库「内容是否变化」判定的**语义锁定**用例。
 *
 * 该判定是「本地编辑是否会被静默丢弃」的唯一判据——判「无变化」时 `SyncCycleRunner`
 * 直接复用旧缓存字节上传；而改造前它**零用例覆盖**（全仓无任何用例引用
 * `resolveLocalContentChanged`）。本类把它的**每一条比较臂**与**三条刻意排除项**
 * （`times`、历史内容、以及合并侧才需要的 `lastModificationTime`）都变成可执行断言。
 *
 * 与合并侧 `KdbxEntryMerger.isModified` 的口径差异（本判定刻意不比 `times` 的使用性字段）见
 * [KdbxContentComparator] 类 KDoc 与 `docs/architecture/已知工程限界.md` §10。
 *
 * **ISSUE-P2-596 起根级 [KdbxContentComparator.changed] 亦被覆盖**（库级 Meta / 头部语义 /
 * 随机材料排除）；历史内容级比较与到期语义由本类新增用例锁定。
 */
class KdbxContentComparatorTest {

    @Test
    fun `条目内容字段的每一处变化都必须被检出`() {
        val mutations = listOf(
            "fields" to base.copy(fields = base.fields + ("User" to ProtectedString("u"))),
            "customFields" to base.copy(customFields = listOf(KdbxCustomField("k", ProtectedString("v")))),
            "tags" to base.copy(tags = listOf("t")),
            "attachments" to base.copy(attachments = listOf(KdbxAttachment("a.txt"))),
            "iconId" to base.copy(iconId = 7),
            "customIconId" to base.copy(customIconId = KdbxUuid.random()),
            "overrideUrl" to base.copy(overrideUrl = "cmd://run"),
            "qualityCheck" to base.copy(qualityCheck = false),
            "parentGroupId" to base.copy(parentGroupId = KdbxUuid.random()),
            "previousParentGroup" to base.copy(previousParentGroup = KdbxUuid.random()),
            "customData" to base.copy(customData = mapOf("k" to "v")),
            "autoType" to base.copy(autoType = KdbxAutoType(enabled = false)),
            "backgroundColor" to base.copy(backgroundColor = "#FF0000"),
            "foregroundColor" to base.copy(foregroundColor = "#00FF00"),
            "history 条数" to base.copy(history = listOf(base))
        )
        mutations.forEach { (name, mutated) ->
            assertTrue(
                "$name 变化必须判为内容变更（漏判即「本地编辑被静默丢弃」）",
                KdbxContentComparator.entryChanged(mutated, base)
            )
            assertTrue(
                "$name 变化必须判为内容变更（参数对调后同样成立）",
                KdbxContentComparator.entryChanged(base, mutated)
            )
        }
    }

    @Test
    fun `相同条目与空副本不得判为变更`() {
        assertFalse(KdbxContentComparator.entryChanged(base, base))
        assertFalse(KdbxContentComparator.entryChanged(base, base.copy()))
    }

    @Test
    fun `仅使用性 times 的变化刻意不判为内容变更`() {
        val timeMutations = listOf(
            "lastModificationTime" to KdbxTimes(lastModificationTime = Instant.ofEpochMilli(1_700_000_000_000L)),
            "usageCount" to base.times.copy(usageCount = 42L),
            "lastAccessTime" to base.times.copy(lastAccessTime = Instant.ofEpochMilli(1L))
        )
        timeMutations.forEach { (name, times) ->
            val mutated = base.copy(times = times)
            // 理由：KdbxTimes 混装「使用性」字段（lastAccessTime / usageCount）与
            // lastModificationTime，而 KdbxEntry.withField 经 withModified() 一次性改写三者
            // ⇒ 纳入比较会让「触碰但内容等同」被判成变更，触发重序列化 + 上传 + 前移远端 ETag
            //（正是本检测器存在的理由）。ISSUE-P2-596 仅把**到期语义**纳入，使用性字段仍排除。
            assertFalse(
                "$name 属使用性 / 合并性变动，不得判为需要重新上传的内容变更",
                KdbxContentComparator.entryChanged(mutated, base)
            )
            assertFalse(
                "$name （参数对调后同样不判为变更）",
                KdbxContentComparator.entryChanged(base, mutated)
            )
        }
    }

    // ===== ISSUE-P2-596：到期语义纳入（PD-20 重开条件成立） =====

    @Test
    fun `到期语义的每一处变化都必须被检出`() {
        val baseTimes = KdbxTimes(expires = false, expiryTime = Instant.ofEpochMilli(1_000L))
        val entry = base.copy(times = baseTimes)

        assertTrue(
            "expires 由 false 翻转为 true 必须判为变更",
            KdbxContentComparator.entryChanged(entry.copy(times = baseTimes.copy(expires = true)), entry)
        )
        assertTrue(
            "expires 生效时 expiryTime 变化必须判为变更",
            KdbxContentComparator.entryChanged(
                entry.copy(times = baseTimes.copy(expires = true, expiryTime = Instant.ofEpochMilli(2_000L))),
                entry.copy(times = baseTimes.copy(expires = true, expiryTime = Instant.ofEpochMilli(3_000L)))
            )
        )
    }

    @Test
    fun `禁用到期时 expiryTime 差异不判为变更`() {
        val a = base.copy(times = KdbxTimes(expires = false, expiryTime = Instant.ofEpochMilli(1_000L)))
        val b = base.copy(times = KdbxTimes(expires = false, expiryTime = Instant.ofEpochMilli(9_999L)))

        assertFalse(
            "expires == false 时 expiryTime 不生效 ⇒ 不得因陈旧日期差异误报变更",
            KdbxContentComparator.entryChanged(a, b)
        )
    }

    @Test
    fun `历史等条数原地改写必须判为变更`() {
        val sharedId = KdbxUuid.random()
        val oldSnapshot = KdbxEntry(id = sharedId, fields = mapOf("Title" to ProtectedString("旧")))
        val newSnapshot = KdbxEntry(id = sharedId, fields = mapOf("Title" to ProtectedString("新")))
        val a = base.copy(history = listOf(oldSnapshot))
        val b = base.copy(history = listOf(newSnapshot))

        assertTrue(
            "ISSUE-P2-596：历史改内容级比较，等条数原地改写必须判为变更",
            KdbxContentComparator.entryChanged(a, b)
        )
        assertFalse("同内容历史不得误报", KdbxContentComparator.entryChanged(a, a.copy()))
    }

    // ===== ISSUE-P3-542：标准五字段按**内容**比较（isProtected 由库级配置决定、不构成树内容） =====

    @Test
    fun `标准五字段仅受保护标志不同不得判为变更`() {
        val standardField = { isProtected: Boolean, value: String ->
            ProtectedString(value, isProtected = isProtected)
        }
        val inMemory = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to standardField(true, "标题"),
                KdbxConstants.Fields.USER_NAME to standardField(true, "alice"),
                KdbxConstants.Fields.PASSWORD to standardField(true, "pw"),
                KdbxConstants.Fields.URL to standardField(true, "https://example.com"),
                KdbxConstants.Fields.NOTES to standardField(true, "备注")
            )
        )
        // 「解析实例」：写侧以库级 MemoryProtection 覆盖 per-value 标志 ⇒ 读回标志可能与内存构造不同
        val parsed = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to standardField(false, "标题"),
                KdbxConstants.Fields.USER_NAME to standardField(false, "alice"),
                KdbxConstants.Fields.PASSWORD to standardField(false, "pw"),
                KdbxConstants.Fields.URL to standardField(false, "https://example.com"),
                KdbxConstants.Fields.NOTES to standardField(false, "备注")
            )
        )

        assertFalse(
            "内容相同、仅 isProtected 不同（标准五字段）不得判为内容变更——否则无编辑也误报有变化",
            KdbxContentComparator.entryChanged(inMemory, parsed)
        )
        assertFalse(
            "（参数对调后同样成立）",
            KdbxContentComparator.entryChanged(parsed, inMemory)
        )
    }

    @Test
    fun `标准五字段内容不同仍必须判为变更`() {
        val a = KdbxEntry(fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("pw1", false)))
        val b = KdbxEntry(fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("pw2", false)))

        assertTrue(KdbxContentComparator.entryChanged(a, b))
        assertTrue("（参数对调后同样成立）", KdbxContentComparator.entryChanged(b, a))
    }

    @Test
    fun `非标准字段的 per-value 受保护标志仍参与比较`() {
        val a = KdbxEntry(fields = mapOf("Custom" to ProtectedString("v", isProtected = true)))
        val b = KdbxEntry(fields = mapOf("Custom" to ProtectedString("v", isProtected = false)))

        assertTrue(
            "非标准字段的 per-value 标志才是被序列化的真值 ⇒ 标志差异仍须判为变更",
            KdbxContentComparator.entryChanged(a, b)
        )
    }

    @Test
    fun `分组字段的每一处变化都必须被检出`() {
        val mutations = listOf(
            "id" to baseGroup.copy(id = KdbxUuid.random()),
            "name" to baseGroup.copy(name = "改名"),
            "notes" to baseGroup.copy(notes = "备注"),
            "iconId" to baseGroup.copy(iconId = 49),
            "customIconId" to baseGroup.copy(customIconId = KdbxUuid.random()),
            "parentGroupId" to baseGroup.copy(parentGroupId = KdbxUuid.random()),
            "tags" to baseGroup.copy(tags = listOf("重要")),
            "customData" to baseGroup.copy(customData = mapOf("k" to "v")),
            "条目内容" to baseGroup.copy(entries = listOf(base.copy(iconId = 7))),
            "条目条数" to baseGroup.copy(entries = baseGroup.entries + KdbxEntry()),
            "子分组条数" to baseGroup.copy(subgroups = listOf(KdbxGroup(name = "S"))),
            "子分组内容" to baseGroup.copy(
                subgroups = listOf(KdbxGroup(id = childGroupId, name = "S", notes = "子组改名"))
            )
        )
        mutations.forEach { (name, mutated) ->
            assertTrue(
                "分组 $name 变化必须判为内容变更",
                KdbxContentComparator.groupChanged(mutated, baseGroup)
            )
        }

        // 「新树里找不到同 id 条目 / 子组」也属变化（`?: return true` 两条分支）
        assertTrue(
            "条目 id 在新树中缺失必须判为变更",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(entries = listOf(KdbxEntry(parentGroupId = null))),
                baseGroup
            )
        )
        assertTrue(
            "子分组 id 在新树中缺失必须判为变更",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(subgroups = listOf(KdbxGroup(name = "S"))),
                baseGroup.copy(subgroups = listOf(KdbxGroup(parentGroupId = null, name = "S")))
            )
        )
    }

    @Test
    fun `相同分组与仅 times 变化的分组不判为变更`() {
        assertFalse(KdbxContentComparator.groupChanged(baseGroup, baseGroup))
        assertFalse(
            "分组 times 与条目 times 同理不入判据",
            KdbxContentComparator.groupChanged(
                baseGroup.copy(
                    times = KdbxTimes(lastModificationTime = Instant.ofEpochMilli(9L)),
                    entries = listOf(base.copy(times = KdbxTimes(usageCount = 3L)))
                ),
                baseGroup
            )
        )
    }

    // ===== ISSUE-P2-596：库级 Meta / 头部语义纳入根级 changed() =====

    @Test
    fun `库级 Meta 可编辑字段逐项纳入判据`() {
        val db = KdbxDatabase(header = KdbxHeader.createDefault(useArgon2 = true), rootGroup = KdbxGroup(name = "Root"))
        val mutations = listOf(
            "databaseName" to db.copy(databaseName = "改名"),
            "databaseDescription" to db.copy(databaseDescription = "描述"),
            "defaultUserName" to db.copy(defaultUserName = "alice"),
            "recycleBinEnabled" to db.copy(recycleBinEnabled = false),
            "recycleBinUuid" to db.copy(recycleBinUuid = KdbxUuid.random()),
            "entryTemplatesGroup" to db.copy(entryTemplatesGroup = KdbxUuid.random()),
            "customIcons" to db.copy(customIcons = listOf(CustomIcon(uuid = KdbxUuid.random(), data = ByteArray(4)))),
            "customData" to db.copy(customData = mapOf("k" to "v")),
            "historyMaxItems" to db.copy(historyMaxItems = 42),
            "historyMaxSize" to db.copy(historyMaxSize = 1234L)
        )
        mutations.forEach { (name, mutated) ->
            assertTrue("库级 Meta $name 变化必须判为变更（漏判即「设置改动不上云」）", KdbxContentComparator.changed(mutated, db))
        }
        assertFalse("同内容快照不得误报", KdbxContentComparator.changed(db, db.copy()))
    }

    @Test
    fun `会话定位与时间戳变化不判为变更`() {
        val db = KdbxDatabase(header = KdbxHeader.createDefault(useArgon2 = true), rootGroup = KdbxGroup(name = "Root"))
        val moved = db.copy(lastSelectedGroup = KdbxUuid.random(), generator = "X", settingsChanged = Instant.now())
        assertFalse(KdbxContentComparator.changed(moved, db))
    }

    @Test
    fun `随机头部材料不判为变更而同语义参数判为变更`() {
        val root = KdbxGroup(name = "Root")
        val h1 = KdbxHeader.createDefault(useArgon2 = true)
        val randOnly = h1.copy(masterSeed = ByteArray(32) { 7 }, encryptionIv = ByteArray(16) { 3 })
        assertFalse(
            "随机 masterSeed / IV 不得判为内容变更（否则每次保存后无编辑也恒判变更）",
            KdbxContentComparator.changed(KdbxDatabase(header = randOnly, rootGroup = root), KdbxDatabase(header = h1, rootGroup = root))
        )

        val cipherChanged = h1.copy(cipherUuid = KdbxConstants.Cipher.TWOFISH)
        assertTrue(
            "加密算法变化必须判为变更",
            KdbxContentComparator.changed(KdbxDatabase(header = cipherChanged, rootGroup = root), KdbxDatabase(header = h1, rootGroup = root))
        )

        val argon = h1.kdfParameters as KdfParameters.Argon2
        val kdfChanged = h1.copy(kdfParameters = argon.copy(iterations = argon.iterations + 1))
        assertTrue(
            "KDF 语义参数（迭代）变化必须判为变更（salt 差异不计）",
            KdbxContentComparator.changed(KdbxDatabase(header = kdfChanged, rootGroup = root), KdbxDatabase(header = h1, rootGroup = root))
        )
    }

    private companion object {
        val base: KdbxEntry = KdbxEntry(
            fields = mapOf("Title" to ProtectedString("T", false))
        )

        val childGroupId: KdbxUuid = KdbxUuid.random()

        val baseGroup: KdbxGroup = KdbxGroup(
            name = "G",
            entries = listOf(base),
            subgroups = listOf(KdbxGroup(id = childGroupId, name = "S"))
        )
    }
}
