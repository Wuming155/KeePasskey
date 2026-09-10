package com.keepasskey.app.data.childdb

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子库挂载注册表（**应用侧**持久化 + 可观察快照）。
 *
 * ## 为什么是「应用侧注册表」而不是「写进根库 `.kdbx`」
 *
 * 任务设计要点 1 建议在根库 `KdbxGroup` 上定义挂载点（专用分组 + 自定义属性记录子库 URI/别名）。
 * 开工前已核实**根库确实具备该写入通道**（`KdbxGroup.customData` 在
 * `database/.../xml/KdbxXmlGroupReader.kt:62` 读、`KdbxXmlGroupSerializer.kt:53` 写，
 * 且 `DatabaseSession.saveGroup` / `updateDatabaseMeta` 均为公开 API，无需改动共享文件），
 * 但本阶段**有意不采用**，理由是语义而非权限：
 *
 * 1. **来源是设备本地量**：`content://` 授权与本地绝对路径换设备即失效；写进**会参与同步**的根库后，
 *    其它设备会收到一批指向不存在文件的悬挂挂载点，形成「同步了但用不了」的假状态；
 * 2. **设置动作不应改动用户库**：挂载是设置页动作，若写根库会把根会话置 DIRTY 并触发一次真实落盘
 *    （用户随后放弃保存又会与注册表不一致，出现两个真相源）；
 * 3. **挂载时根库可能处于锁定态**：锁定/只读会话根本没有写入通道，注册表若依赖根库写入将无法工作。
 *
 * 因此本阶段采用应用私有存储中的**非敏感**注册表（[PREFS_NAME]），
 * 并**结构性**满足设计要点 1 的隔离目标：子库条目**从不进入根库对象树**，
 * 因此与 `sync/KdbxMerger` 的 UUID/墓碑语义零交集（不存在「子库条目被根库同步误删」的可能）。
 * 后续收敛路径见 [ChildDatabaseSessionManager] KDoc「挂载点抽象」小节。
 *
 * ## 同步交互不变量（设计要点 4）
 *
 * 本注册表**只写自身偏好文件** [PREFS_NAME]：既不写 `keepasskey_vault_meta`
 * （`known_databases_v1` 是 `RealVaultRepository` 的库列表来源），也不触碰
 * `DatabaseSession.currentFile` / `currentPathIdentifier`。而根库同步
 * （`SyncCoordinator.syncNow` → `databaseSession.currentFile`）只处理当前根库文件，
 * 故**子库路径不可能出现在任何同步候选集合里**——该不变量由
 * `ChildDatabaseSyncIsolationTest` 断言守护。
 *
 * ## 存储形态
 *
 * 记录以分隔符拼接（与 `RealVaultRepository.loadKnownDatabases` 同一轻量约定），
 * 单条损坏仅丢弃该条而不影响其余记录。别名与来源在写入前已拒绝控制字符
 * （见 [ChildDatabaseSourceValidator]），因此分隔符不会被内容拆错。
 */
@Singleton
class ChildDatabaseMountStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mountFlow = MutableStateFlow(readPersisted())

    private val countFlow = MutableStateFlow(mountFlow.value.size)

    /** 已登记挂载（按挂载时刻升序，仅非敏感元数据），供设置页等上层直接观察 */
    val mounts: StateFlow<List<ChildDatabaseMount>> = mountFlow.asStateFlow()

    /**
     * 已登记挂载数量（`childDatabasesCount` 的真实数据源）。
     *
     * 语义为**注册表长度**（已挂载），与「本次进程内已解密打开的会话数」刻意区分：
     * 锁库后挂载依然在册，但会话已终止且凭据已清零。
     */
    val mountedCount: StateFlow<Int> = countFlow.asStateFlow()

    /** 按挂载身份查找；不存在返回 null */
    fun findById(id: String): ChildDatabaseMount? =
        mountFlow.value.firstOrNull { it.id == id }

    /** 按来源查找（用于「同一文件只允许挂载一次」的去重判定）；不存在返回 null */
    fun findBySource(sourceUri: String): ChildDatabaseMount? =
        mountFlow.value.firstOrNull { it.sourceUri == sourceUri }

    /**
     * 登记一条挂载记录。
     *
     * **仅由 [ChildDatabaseSessionManager] 在首次解密成功后调用**：注册表是「已挂载」的事实来源，
     * 手动调用会让运行时状态（会话/凭据）与注册表脱节。
     *
     * @return 新增成功返回 true；挂载身份或来源已存在返回 false（幂等无操作）
     */
    @Synchronized
    internal fun register(mount: ChildDatabaseMount): Boolean {
        val current = mountFlow.value
        if (current.any { it.id == mount.id || it.sourceUri == mount.sourceUri }) return false
        publish(current + mount)
        return true
    }

    /**
     * 摘除一条挂载记录（**仅由 [ChildDatabaseSessionManager] 调用**，须先终止会话并清零凭据）。
     *
     * @return 命中并移除返回 true；不存在返回 false
     */
    @Synchronized
    internal fun remove(id: String): Boolean {
        val current = mountFlow.value
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        publish(remaining)
        return true
    }

    /** 更新内存快照 + 计数并落盘（写失败仅降级为「本次不持久化」，内存语义仍然正确） */
    @Synchronized
    private fun publish(list: List<ChildDatabaseMount>) {
        val sorted = list.sortedBy { it.mountedAtEpochMillis }
        mountFlow.value = sorted
        countFlow.value = sorted.size
        persist(sorted)
    }

    private fun persist(list: List<ChildDatabaseMount>) {
        val target = prefs ?: return
        target.edit().putString(K_MOUNTS, encode(list)).apply()
    }

    private fun readPersisted(): List<ChildDatabaseMount> {
        val raw = prefs?.getString(K_MOUNTS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { decodeRecord(it) }
            .sortedBy { it.mountedAtEpochMillis }
    }

    private fun encode(list: List<ChildDatabaseMount>): String =
        list.joinToString(RECORD_SEPARATOR) { mount ->
            listOf(
                mount.id,
                mount.alias,
                mount.sourceUri,
                mount.sourceKind.name,
                mount.credentialRefId,
                mount.readOnly.toString(),
                mount.mountedAtEpochMillis.toString()
            ).joinToString(FIELD_SEPARATOR)
        }

    /**
     * 解码单条记录：任何字段不自洽（字段数不符、枚举未知、时间非法、
     * 别名/来源未通过再校验）即整条丢弃，绝不半可信装载。
     *
     * 安全性：[ChildDatabaseMount.readOnly] 恒回落为 [CHILD_DATABASE_READ_ONLY]——
     * 即使持久化数据被改写为 `false`，也无法开启子库写通道。
     */
    private fun decodeRecord(record: String): ChildDatabaseMount? {
        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val kind = ChildDatabaseSourceKind.entries.firstOrNull { it.name == fields[FIELD_INDEX_KIND] }
            ?: return null
        val alias = ChildDatabaseSourceValidator.normalizeAlias(fields[FIELD_INDEX_ALIAS]) ?: return null
        val sourceUri = fields[FIELD_INDEX_SOURCE]
        if (ChildDatabaseSourceValidator.resolveKind(sourceUri) != kind) return null
        if (fields[FIELD_INDEX_ID].isBlank() || fields[FIELD_INDEX_CREDENTIAL_REF].isBlank()) return null
        val mountedAt = fields[FIELD_INDEX_MOUNTED_AT].toLongOrNull() ?: return null
        return ChildDatabaseMount(
            id = fields[FIELD_INDEX_ID],
            alias = alias,
            sourceUri = sourceUri,
            sourceKind = kind,
            credentialRefId = fields[FIELD_INDEX_CREDENTIAL_REF],
            mountedAtEpochMillis = mountedAt,
            readOnly = CHILD_DATABASE_READ_ONLY
        )
    }

    companion object {
        /**
         * 子库注册表偏好文件名——与库列表偏好（`keepasskey_vault_meta`）**刻意分离**，
         * 使子库路径在结构上无法进入同步候选集合（见类 KDoc）。
         */
        const val PREFS_NAME: String = "keepasskey_child_databases"

        /** 注册表键（版本化，便于将来无损迁移） */
        const val K_MOUNTS: String = "child_database_mounts_v1"

        /** 记录分隔符 */
        private const val RECORD_SEPARATOR = "\u0002"

        /** 字段分隔符 */
        private const val FIELD_SEPARATOR = "\u0001"

        private const val FIELD_COUNT = 7
        private const val FIELD_INDEX_ID = 0
        private const val FIELD_INDEX_ALIAS = 1
        private const val FIELD_INDEX_SOURCE = 2
        private const val FIELD_INDEX_KIND = 3
        private const val FIELD_INDEX_CREDENTIAL_REF = 4
        private const val FIELD_INDEX_MOUNTED_AT = 6
    }
}
