package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString

/**
 * 「模板」分组与 5 个标准模板条目工厂（TASK-21 拆分自 RealVaultRepository）。
 * 模板标题/备注/自定义字段键会作为 KDBX 条目数据持久化并跨端同步，
 * 属库内数据内容而非 UI 文案，故保持中文常量、禁止本地化。
 */
internal object VaultTemplateFactory {

    /** 模板分组固定名（installEntryTemplates 幂等判定依据，持久化数据） */
    const val TEMPLATE_GROUP_NAME = "模板"

    /** 构建「模板」分组与 5 个标准模板条目（网页登录 / 信用卡 / WiFi / 安全笔记 / SSH 密钥） */
    fun buildTemplateGroup(): KdbxGroup {
        val groupId = KdbxUuid.random()
        fun template(
            title: String,
            iconId: Int,
            notes: String,
            standard: Map<String, String> = emptyMap(),
            extra: List<Pair<String, Boolean>> = emptyList()
        ): KdbxEntry {
            val fields = mutableMapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString(notes, isProtected = false)
            )
            standard.forEach { (key, value) ->
                fields[key] = ProtectedString(value, isProtected = false)
            }
            return KdbxEntry(
                id = KdbxUuid.random(),
                parentGroupId = groupId,
                iconId = iconId,
                fields = fields,
                customFields = extra.map { (key, protected) ->
                    KdbxCustomField(key, ProtectedString("", isProtected = protected))
                }
            )
        }

        val entries = listOf(
            template(
                title = "网页登录",
                iconId = 1,
                notes = "标准网页登录模板：填写用户名与密码后使用",
                standard = mapOf(
                    KdbxConstants.Fields.USER_NAME to "",
                    KdbxConstants.Fields.PASSWORD to "",
                    KdbxConstants.Fields.URL to "https://"
                )
            ),
            template(
                title = "信用卡",
                iconId = 27,
                notes = "银行卡模板：卡片信息作为自定义字段存放",
                extra = listOf(
                    VaultEntryMapper.CARD_FIELD_HOLDER_ZH to false,
                    VaultEntryMapper.CARD_FIELD_NUMBER_ZH to true,
                    VaultEntryMapper.CARD_FIELD_EXPIRY_ZH to false,
                    "CVV" to true,
                    "PIN" to true
                )
            ),
            template(
                title = "WiFi",
                iconId = 33,
                notes = "无线网络模板",
                extra = listOf(
                    "SSID" to false,
                    "密码" to true
                )
            ),
            template(
                title = "安全笔记",
                iconId = 11,
                notes = "纯文本安全笔记：将内容写入备注字段"
            ),
            template(
                title = "SSH 密钥",
                iconId = 17,
                notes = "SSH 密钥模板：私钥以受保护字段存放",
                standard = mapOf(KdbxConstants.Fields.USER_NAME to ""),
                extra = listOf(
                    "Host" to false,
                    "Private Key" to true,
                    "Passphrase" to true
                )
            )
        )

        return KdbxGroup(
            id = groupId,
            parentGroupId = null,
            name = TEMPLATE_GROUP_NAME,
            iconId = RealVaultRepository.ICON_FOLDER,
            entries = entries
        )
    }
}
