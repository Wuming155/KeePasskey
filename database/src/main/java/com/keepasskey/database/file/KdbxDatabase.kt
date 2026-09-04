package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxGroup

/**
 * 内存中的已打开 KDBX 数据库实体
 */
data class KdbxDatabase(
    val header: KdbxHeader,
    val databaseName: String = "KeePass",
    val databaseDescription: String = "",
    val rootGroup: KdbxGroup
) {
    fun clearSensitiveData() {
        rootGroup.clearSensitiveData()
    }
}
