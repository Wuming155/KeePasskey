package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.w3c.dom.Element
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * KDBX XML 序列化写回器。
 * 遵循单一职责与高内聚设计：委派 [KdbxXmlMetaSerializer] 与 [KdbxXmlGroupSerializer] 处理具体节点树。
 */
class KdbxXmlSerializer(
    private val innerStreamCipher: InnerRandomStreamCipher?
) {

    fun serialize(
        outputStream: OutputStream,
        database: KdbxDatabase
    ) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        val rootElem = doc.createElement(KdbxConstants.Xml.ROOT)
        doc.appendChild(rootElem)

        // 1. 序列化 <Meta>
        KdbxXmlMetaSerializer.serialize(
            doc = doc,
            rootElem = rootElem,
            generator = database.generator,
            databaseName = database.databaseName,
            databaseNameChanged = database.databaseNameChanged,
            databaseDescription = database.databaseDescription,
            databaseDescriptionChanged = database.databaseDescriptionChanged,
            recycleBinEnabled = database.recycleBinEnabled,
            recycleBinUuid = database.recycleBinUuid,
            recycleBinChanged = database.recycleBinChanged,
            entryTemplatesGroup = database.entryTemplatesGroup,
            entryTemplatesGroupChanged = database.entryTemplatesGroupChanged,
            historyMaxItems = database.historyMaxItems,
            historyMaxSize = database.historyMaxSize,
            lastSelectedGroup = database.lastSelectedGroup,
            lastTopVisibleGroup = database.lastTopVisibleGroup,
            memoryProtection = database.memoryProtection,
            customIcons = database.customIcons,
            deletedObjects = database.deletedObjects,
            customData = database.customData
        )

        // 2. 序列化 <Root> 节点包裹的根分组
        val rootNodeElem = doc.createElement(KdbxConstants.Xml.ROOT_GROUP)
        rootElem.appendChild(rootNodeElem)
        KdbxXmlGroupSerializer.serialize(doc, rootNodeElem, database.rootGroup, innerStreamCipher)

        // 3. 转换并输出至流
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        try {
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        } catch (_: Exception) {
            // 部分 XML 实现可能不支持特定属性
        }

        val source = DOMSource(doc)
        val result = StreamResult(outputStream)
        transformer.transform(source, result)
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
