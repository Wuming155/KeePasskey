package com.keepasskey.app.data.childdb

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 子库挂载单测的共享替身与语料（手写 Fake，不使用 MockK）。
 *
 * 关键取舍：KDBX 语料由**生产写入管线** [KdbxFile.save] 现场生成，
 * 再由待测代码经 [KdbxFile.load] 真实解密，因此「子库条目投影」用例
 * 覆盖的是真实加解密链路而非假数据。
 */

/** 记录被访问过的偏好文件名的假 `Context`（Preferences 语义由内存实现顶替） */
internal class RecordingContextFactory {

    private val files = LinkedHashMap<String, InMemorySharedPreferences>()

    private val accessed = LinkedHashSet<String>()

    /**
     * **生产代码**真正访问过的偏好文件名（按首次访问顺序）。
     *
     * 与 [seedPrefs] 区分：预置数据模拟「上次运行留下的持久化内容」，
     * 不计入本列表，否则存储隔离断言会被测试自身的预置动作污染。
     */
    val accessedNames: List<String>
        get() = accessed.toList()

    val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val key = name.orEmpty()
            accessed.add(key)
            return files.getOrPut(key) { InMemorySharedPreferences() }
        }
    }

    /**
     * 预置某偏好文件的持久化内容（模拟上次运行残留 / 被改写的存储），
     * 返回实例以便用例继续写入原始记录；不计入 [accessedNames]。
     */
    fun seedPrefs(name: String): InMemorySharedPreferences =
        files.getOrPut(name) { InMemorySharedPreferences() }

    /** 查询某偏好文件（从未被访问或预置过时为 null，据此断言「从未触碰」） */
    fun prefsOf(name: String): InMemorySharedPreferences? = files[name]
}

/** 进程内 SharedPreferences 替身（值语义足够覆盖注册表的读写往返） */
internal class InMemorySharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()

    /** 原始持久化值（用于断言「没有写入其他键」） */
    fun rawValue(key: String): Any? = values[key]

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? {
        val stored = values[key]
        return stored as? String ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

/** 内存 Editor（commit/apply 均即时生效） */
internal class InMemoryEditor(
    private val values: LinkedHashMap<String, Any?>
) : SharedPreferences.Editor {

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        if (key != null) values[key] = value
        return this
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?
    ): SharedPreferences.Editor = this

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        if (key != null) values[key] = value
        return this
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        if (key != null) values[key] = value
        return this
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        if (key != null) values[key] = value
        return this
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        if (key != null) values[key] = value
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        values.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        values.clear()
        return this
    }

    override fun commit(): Boolean = true

    override fun apply() = Unit
}

/** 内存来源替身：优先抛错，其次给字节；每次调用返回新鲜流（可重复打开） */
internal class FakeChildDatabaseStreamSource(
    var payload: ByteArray? = null,
    var failure: Throwable? = null
) : ChildDatabaseStreamSource {

    var openCount: Int = 0
        private set

    /** 打开过程中的注入点（用于确定性地复现「解密途中根库锁定」竞态） */
    var onOpen: (() -> Unit)? = null

    override suspend fun open(mount: ChildDatabaseMount): InputStream {
        openCount++
        onOpen?.invoke()
        failure?.let { throw it }
        val bytes = payload ?: throw FileNotFoundException("测试：未配置子库来源")
        return ByteArrayInputStream(bytes)
    }
}

/**
 * 真实 KDBX 语料工厂：以生产写入管线产出可被 [KdbxFile.load] 解密的密文字节。
 *
 * 刻意使用**小轮数 AES-KDF**：单测只关心「真实解密 + 真实投影」，
 * Argon2 正确性与性能已由 `crypto` / `database` 模块用例覆盖，
 * 在 app 单测里跑 64 MiB Argon2 只会让每个用例多花数秒。
 */
internal object ChildDatabaseFixtures {

    /** 子库库名（Meta.DatabaseName） */
    const val DATABASE_NAME = "子库测试库"

    /** 子库根分组名 */
    const val ROOT_GROUP_NAME = "子库根"

    /** 子库子分组名 */
    const val SUB_GROUP_NAME = "分组一"

    const val ROOT_ENTRY_TITLE = "根条目A"
    const val ROOT_ENTRY_WITHOUT_PASSWORD_TITLE = "根条目B"
    const val SUB_ENTRY_TITLE = "子条目C"

    const val ROOT_ENTRY_PASSWORD = "pw-root-a"
    const val SUB_ENTRY_PASSWORD = "pw-sub-c"

    /** 条目标签（断言投影携带标签） */
    const val ENTRY_TAG = "测试标签"

    /** 单测用 AES-KDF 轮数（合法区间内，远低于生产默认值） */
    private const val TEST_KDF_ROUNDS = 1_000L

    /** 子库条目总数（根 2 + 子分组 1） */
    const val ENTRY_COUNT = 3

    /** 分组总数含根分组（根 + 分组一） */
    const val GROUP_COUNT = 2

    fun header(): KdbxHeader {
        val base = KdbxHeader.createDefault(useArgon2 = false)
        val aes = base.kdfParameters as KdfParameters.Aes
        return base.copy(kdfParameters = aes.copy(rounds = TEST_KDF_ROUNDS))
    }

    fun database(): KdbxDatabase {
        val root = KdbxGroup(name = ROOT_GROUP_NAME)
        val subGroup = KdbxGroup(name = SUB_GROUP_NAME, parentGroupId = root.id)
        val decoratedSubGroup = subGroup.copy(
            entries = listOf(
                entry(subGroup.id, SUB_ENTRY_TITLE, SUB_ENTRY_PASSWORD)
            )
        )
        return KdbxDatabase(
            header = header(),
            databaseName = DATABASE_NAME,
            rootGroup = root.copy(
                entries = listOf(
                    entry(root.id, ROOT_ENTRY_TITLE, ROOT_ENTRY_PASSWORD),
                    entry(root.id, ROOT_ENTRY_WITHOUT_PASSWORD_TITLE, password = null)
                ),
                subgroups = listOf(decoratedSubGroup)
            )
        )
    }

    /** 用给定主密码加密出真实 KDBX 字节 */
    fun kdbxBytes(password: CharArray): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            KdbxFile.save(buffer, database(), password)
        }.toByteArray()

    private fun entry(parentGroupId: KdbxUuid, title: String, password: String?): KdbxEntry {
        val fields = mutableMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.USER_NAME to ProtectedString("user-$title", false),
            KdbxConstants.Fields.URL to ProtectedString("https://example.com/$title", false),
            KdbxConstants.Fields.NOTES to ProtectedString("备注-$title", false)
        )
        if (password != null) {
            fields[KdbxConstants.Fields.PASSWORD] = ProtectedString(password, true)
        }
        return KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = parentGroupId,
            fields = fields,
            tags = listOf(ENTRY_TAG)
        )
    }
}

/** 构造一条**通过来源校验**的本地绝对路径（跨平台：基于 `java.io.tmpdir`） */
internal fun validLocalSource(fileName: String = "child-db.kdbx"): String {
    val base = System.getProperty("java.io.tmpdir") ?: "."
    return File(base, fileName).absolutePath
}

/** 构造一条通过来源校验的 `content://` 来源 */
internal fun validContentSource(documentName: String = "child-db"): String =
    "content://com.example.documents/$documentName"

/** 构造一条未登记的挂载记录（直接驱动会话层用例，绕过注册表） */
internal fun testMount(
    id: String = "mount-1",
    alias: String = "子库一",
    sourceUri: String = validLocalSource(),
    credentialRefId: String = "cred-1"
): ChildDatabaseMount = ChildDatabaseMount(
    id = id,
    alias = alias,
    sourceUri = sourceUri,
    sourceKind = ChildDatabaseSourceKind.LOCAL_FILE,
    credentialRefId = credentialRefId,
    mountedAtEpochMillis = 1L
)
