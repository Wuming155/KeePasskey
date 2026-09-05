package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import java.io.OutputStream

/**
 * 从扁平化内存库实体组装 <Meta> 解析产物模型（序列化写侧唯一映射点）。
 */
private fun KdbxDatabase.toMetaData(): KdbxMetaData {
    return KdbxMetaData(
        generator = generator,
        databaseName = databaseName,
        databaseNameChanged = databaseNameChanged,
        databaseDescription = databaseDescription,
        databaseDescriptionChanged = databaseDescriptionChanged,
        defaultUserName = defaultUserName,
        defaultUserNameChanged = defaultUserNameChanged,
        maintenanceHistoryDays = maintenanceHistoryDays,
        color = color,
        masterKeyChanged = masterKeyChanged,
        masterKeyChangeRec = masterKeyChangeRec,
        masterKeyChangeForce = masterKeyChangeForce,
        settingsChanged = settingsChanged,
        recycleBinEnabled = recycleBinEnabled,
        recycleBinUuid = recycleBinUuid,
        recycleBinChanged = recycleBinChanged,
        entryTemplatesGroup = entryTemplatesGroup,
        entryTemplatesGroupChanged = entryTemplatesGroupChanged,
        historyMaxItems = historyMaxItems,
        historyMaxSize = historyMaxSize,
        lastSelectedGroup = lastSelectedGroup,
        lastTopVisibleGroup = lastTopVisibleGroup,
        memoryProtection = memoryProtection,
        customIcons = customIcons,
        deletedObjects = deletedObjects,
        customData = customData
    )
}

/**
 * KDBX XML 序列化写回器（流式，不构建 DOM）。
 * 遵循单一职责与高内聚设计：委派 [KdbxXmlMetaSerializer] 与 [KdbxXmlGroupSerializer] 流式写出具体节点。
 * 注意：[serialize] 结束时仅冲刷写出器缓冲，外层压缩/加密流的级联关闭由 [com.keepasskey.database.file.KdbxFile] 负责。
 */
class KdbxXmlSerializer(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    fun serialize(
        outputStream: OutputStream,
        database: KdbxDatabase
    ) {
        val writer = KdbxXmlStreamWriter(outputStream)
        writer.startDocument()

        writer.startElement(KdbxConstants.Xml.ROOT)

        // 1. 流式写出 <Meta>
        KdbxXmlMetaSerializer.serialize(writer, database.toMetaData())

        // 2. 流式写出 <Root> 包裹的根分组
        writer.startElement(KdbxConstants.Xml.ROOT_GROUP)
        KdbxXmlGroupSerializer.serialize(writer, database.rootGroup, innerStreamCipher)
        writer.endElement()

        writer.endElement()
        writer.close()
    }

    /**
     * 向后兼容现有签名的重载
     */
    fun serialize(
        outputStream: OutputStream,
        databaseName: String,
        databaseDescription: String,
        rootGroup: KdbxGroup
    ) {
        val dummyDb = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            databaseName = databaseName,
            databaseDescription = databaseDescription,
            rootGroup = rootGroup
        )
        serialize(outputStream, dummyDb)
    }
}
