package com.keepasskey.core.model

/**
 * KDBX 格式标准常量定义。
 * 严禁代码中出现裸露的魔法数字或字面量。
 */
object KdbxConstants {

    /**
     * 文件签名魔数
     */
    object Signature {
        const val SIGNATURE_1: Int = 0x9AA2D903.toInt()
        const val SIGNATURE_2_KDBX: Int = 0xB54BFB67.toInt()
        const val SIGNATURE_2_KDBX_OLD: Int = 0xB54BFB65.toInt()
        const val SIGNATURE_2_KDBX_PRE: Int = 0xB54BFB66.toInt()
    }

    /**
     * 版本定义
     */
    object Version {
        const val VERSION_MAJOR_MASK: Int = 0xFFFF0000.toInt()
        const val VERSION_MINOR_MASK: Int = 0x0000FFFF

        const val VERSION_3_1: Int = 0x00030001
        const val VERSION_4_0: Int = 0x00040000
        const val VERSION_4_1: Int = 0x00040001
    }

    /**
     * 外层 Header 字段 ID (KDBX 3 & 4)
     */
    object HeaderFieldId {
        const val END_OF_HEADER: Byte = 0
        const val COMMENT: Byte = 1
        const val CIPHER_ID: Byte = 2
        const val COMPRESSION_FLAGS: Byte = 3
        const val MASTER_SEED: Byte = 4
        const val TRANSFORM_SEED: Byte = 5 // KDBX 3
        const val TRANSFORM_ROUNDS: Byte = 6 // KDBX 3
        const val ENCRYPTION_IV: Byte = 7
        const val INNER_RANDOM_STREAM_KEY: Byte = 8 // KDBX 3
        const val STREAM_START_BYTES: Byte = 9 // KDBX 3
        const val INNER_RANDOM_STREAM_ID: Byte = 10
        const val KDF_PARAMETERS: Byte = 11 // KDBX 4
        const val PUBLIC_CUSTOM_DATA: Byte = 12 // KDBX 4
    }

    /**
     * 内层 Header 字段 ID (KDBX 4)
     */
    object InnerHeaderFieldId {
        const val END: Byte = 0
        const val INNER_RANDOM_STREAM_ID: Byte = 1
        const val INNER_RANDOM_STREAM_KEY: Byte = 2
        const val BINARY: Byte = 3
    }

    /**
     * 压缩算法标识
     */
    object Compression {
        const val NONE: Int = 0
        const val GZIP: Int = 1
    }

    /**
     * 数据加密 Cipher UUID
     */
    object Cipher {
        val AES_256_CBC = KdbxUuid.fromHexString("31C1F2E6BF714350BE5805216AFC5AFF")
        val CHACHA20 = KdbxUuid.fromHexString("D6038A2B3B6F4CA5A20118F6A4419777")
        val TWOFISH = KdbxUuid.fromHexString("AD68F29FB35B4DB7B9C0C272C151F072")
    }

    /**
     * 密钥派生函数 KDF UUID 与默认参数
     */
    object Kdf {
        val AES_KDF = KdbxUuid.fromHexString("C9D9F39A628A4460BF740D08C18A4FEA")
        val ARGON2D = KdbxUuid.fromHexString("EF636DDF8C29444B91F7A948E42D3E28")
        val ARGON2ID = KdbxUuid.fromHexString("9E7071B53CA345F1AC6D34ECDA197A31")

        const val DEFAULT_AES_KDF_ROUNDS: Long = 6_000_000L
        const val DEFAULT_ARGON2_ITERATIONS: Long = 2L
        const val DEFAULT_ARGON2_MEMORY_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_ARGON2_PARALLELISM: Int = 2
    }

    /**
     * 内部受保护流密码算法
     */
    object InnerRandomStream {
        const val NONE: Int = 0
        const val ARCFOUR: Int = 1
        const val SALSA20: Int = 2
        const val CHACHA20: Int = 3
    }

    /**
     * 标准条目字段名
     */
    object Fields {
        const val TITLE = "Title"
        const val USER_NAME = "UserName"
        const val PASSWORD = "Password"
        const val URL = "URL"
        const val NOTES = "Notes"
        /** 标准 TOTP 字段（KeePassXC 语义：otpauth:// URI 或纯 Base32 种子） */
        const val OTP = "otp"
    }

    /**
     * XML 节点名与属性
     */
    object Xml {
        const val ROOT = "KeePassFile"
        const val META = "Meta"
        const val ROOT_GROUP = "Root"
        const val GROUP = "Group"
        const val ENTRY = "Entry"
        const val UUID = "UUID"
        const val NAME = "Name"
        const val NOTES = "Notes"
        const val ICON_ID = "IconID"
        const val CUSTOM_ICON_UUID = "CustomIconUUID"
        const val TIMES = "Times"
        const val IS_EXPANDED = "IsExpanded"
        const val STRING = "String"
        const val KEY = "Key"
        const val VALUE = "Value"
        const val PROTECTED = "Protected"
        const val BINARY = "Binary"
        const val REF = "Ref"
        const val HISTORY = "History"
        const val AUTO_TYPE = "AutoType"
        const val ENABLED = "Enabled"
        const val DATA_TRANSFER_OBFUSCATION = "DataTransferObfuscation"
        const val DEFAULT_SEQUENCE = "DefaultSequence"
        const val ASSOCIATION = "Association"
        const val WINDOW = "Window"
        const val KEYSTROKE_SEQUENCE = "KeystrokeSequence"
        const val LAST_MODIFICATION_TIME = "LastModificationTime"
        const val CREATION_TIME = "CreationTime"
        const val LAST_ACCESS_TIME = "LastAccessTime"
        const val EXPIRY_TIME = "ExpiryTime"
        const val EXPIRES = "Expires"
        const val USAGE_COUNT = "UsageCount"
        const val LOCATION_CHANGED = "LocationChanged"

        // Meta 节点与配置
        const val GENERATOR = "Generator"
        const val DATABASE_NAME = "DatabaseName"
        const val DATABASE_NAME_CHANGED = "DatabaseNameChanged"
        const val DATABASE_DESCRIPTION = "DatabaseDescription"
        const val DATABASE_DESCRIPTION_CHANGED = "DatabaseDescriptionChanged"
        const val DEFAULT_USER_NAME = "DefaultUserName"
        const val DEFAULT_USER_NAME_CHANGED = "DefaultUserNameChanged"
        const val MAINTENANCE_HISTORY_DAYS = "MaintenanceHistoryDays"
        const val COLOR = "Color"
        const val MASTER_KEY_CHANGED = "MasterKeyChanged"
        const val MASTER_KEY_CHANGE_REC = "MasterKeyChangeRec"
        const val MASTER_KEY_CHANGE_FORCE = "MasterKeyChangeForce"
        const val MEMORY_PROTECTION = "MemoryProtection"
        const val PROTECT_TITLE = "ProtectTitle"
        const val PROTECT_USER_NAME = "ProtectUserName"
        const val PROTECT_PASSWORD = "ProtectPassword"
        const val PROTECT_URL = "ProtectURL"
        const val PROTECT_NOTES = "ProtectNotes"
        const val CUSTOM_ICONS = "CustomIcons"
        const val ICON = "Icon"
        const val DATA = "Data"
        const val RECYCLE_BIN_ENABLED = "RecycleBinEnabled"
        const val RECYCLE_BIN_UUID = "RecycleBinUUID"
        const val RECYCLE_BIN_CHANGED = "RecycleBinChanged"
        const val ENTRY_TEMPLATES_GROUP = "EntryTemplatesGroup"
        const val ENTRY_TEMPLATES_GROUP_CHANGED = "EntryTemplatesGroupChanged"
        const val HISTORY_MAX_ITEMS = "HistoryMaxItems"
        const val HISTORY_MAX_SIZE = "HistoryMaxSize"
        const val LAST_SELECTED_GROUP = "LastSelectedGroup"
        const val LAST_TOP_VISIBLE_GROUP = "LastTopVisibleGroup"
        const val DELETED_OBJECTS = "DeletedObjects"
        const val DELETED_OBJECT = "DeletedObject"
        const val DELETION_TIME = "DeletionTime"
        const val CUSTOM_DATA = "CustomData"
        const val ITEM = "Item"

        // 条目与分组额外属性
        const val FOREGROUND_COLOR = "ForegroundColor"
        const val BACKGROUND_COLOR = "BackgroundColor"
        const val OVERRIDE_URL = "OverrideURL"
        const val TAGS = "Tags"
        const val QUALITY_CHECK = "QualityCheck"
        const val PREVIOUS_PARENT_GROUP = "PreviousParentGroup"
        const val DEFAULT_AUTO_TYPE_SEQUENCE = "DefaultAutoTypeSequence"
        const val ENABLE_AUTO_TYPE = "EnableAutoType"
        const val ENABLE_SEARCHING = "EnableSearching"
        const val LAST_TOP_VISIBLE_ENTRY = "LastTopVisibleEntry"
    }
}
