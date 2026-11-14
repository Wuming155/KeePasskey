package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.autofill.AndroidAppBindingFields
import com.keepasskey.app.autofill.AutofillPackageNames
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.time.Instant

/**
 * 条目「应用关联」自定义字段的**字段级**写入口（KeePassDX `AndroidApp` / `AndroidApp Signature` 口径）。
 *
 * 与 [VaultEntryUrlWriter] 同为「一个写入面一件器」，但语义相反：本器**绝不触碰 `URL` 标准字段**，
 * 只在 `customFields` 上按 [AndroidAppBindingFields] 的连续编号追加一组包名 + 签名：
 * - 包名键明文、签名键受保护（冒号分隔大写 hex）；
 * - 包名已存在 ⇒ 幂等返回成功且不写（该包名不再重复问 / 重复写）；
 * - **不产生历史修订**（字段级即时写，与 URL writer / 收藏标记同口径）；
 * - 落盘仍走会话唯一出口 [persistSession]。
 *
 * 签名摘要为空时按 KeePassDX `setApplicationId(appId, signature = null)` 的合法形态只写包名键；
 * 这**不是**放行降级：消费侧仍受 P2-46「包名 + 签名摘要」首次绑定门约束
 * （见 `AutofillCandidateRanker` 的 `packageDimensionAuthorized`）。
 */
internal class VaultEntryAppBindingWriter(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    suspend fun addAppBinding(
        entryId: String,
        packageName: String,
        fingerprintHex: String
    ): KdbxResult<Unit> = databaseSession.withWriteAcceptance {
        addAppBindingInternal(entryId, packageName, fingerprintHex)
    }

    private suspend fun addAppBindingInternal(
        entryId: String,
        packageName: String,
        fingerprintHex: String
    ): KdbxResult<Unit> {
        val normalized = AutofillPackageNames.normalize(packageName)
            ?: return KdbxResult.Failure(
                IllegalArgumentException("invalid calling package"),
                strings.get(R.string.repo_invalid_entry_id)
            )
        val entry = when (
            val resolved = resolveExistingEntryForFieldWrite(databaseSession, strings, entryId)
        ) {
            is KdbxResult.Success -> resolved.data
            is KdbxResult.Failure -> return resolved
        }
        val existingValues = bindingFieldValues(entry.customFields)
            ?: return KdbxResult.Failure(
                IllegalStateException("app binding field unreadable"),
                strings.get(R.string.repo_entry_not_found)
            )
        val merged = mergedBindingFields(entry.customFields, existingValues, normalized, fingerprintHex)
            ?: return KdbxResult.Success(Unit)

        val updated = entry.copy(
            customFields = merged,
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.saveEntry(updated)
        return persistSession()
    }

    companion object {

        /**
         * 只把 `AndroidApp*` 两个系列的键读成值表，**绝不物化其它自定义字段**（其中可能含受保护凭据）。
         * 返回 null = 绑定键读取失败（实例已被擦除 / 库状态异常），调用方按 fail-closed 处理。
         */
        fun bindingFieldValues(customFields: List<KdbxCustomField>): Map<String, String>? {
            val values = LinkedHashMap<String, String>()
            for (field in customFields) {
                if (!isBindingKey(field.key)) continue
                val value = try {
                    field.value.readString()
                } catch (t: Throwable) {
                    return null
                }
                values[field.key] = value
            }
            return values
        }

        /**
         * 纯字段表变换：按现有值表判定空位，生成追加 / 覆盖后的完整 `customFields`；
         * 包名已存在返回 null（幂等）。保存链的 upsert 与询问链共用本函数，保证键名与编号同源。
         */
        fun mergedBindingFields(
            customFields: List<KdbxCustomField>,
            existingValues: Map<String, String>,
            packageName: String,
            fingerprintHex: String
        ): List<KdbxCustomField>? {
            val bindings = AndroidAppBindingFields.parse(existingValues)
            val position = AndroidAppBindingFields.nextPosition(bindings, packageName) ?: return null
            val (appKey, signatureKey) = AndroidAppBindingFields.keysFor(position)

            val merged = LinkedHashMap<String, KdbxCustomField>(customFields.size + 2)
            customFields.forEach { merged[it.key] = it }
            merged[appKey] = KdbxCustomField(
                appKey,
                ProtectedString(packageName, isProtected = false)
            )
            val signature = AndroidAppBindingFields.toColonHex(fingerprintHex)
            if (signature.isNotEmpty()) {
                merged[signatureKey] = KdbxCustomField(
                    signatureKey,
                    ProtectedString(signature, isProtected = true)
                )
            } else {
                // 无签名形态下不得留下同编号的孤立旧签名键
                merged.remove(signatureKey)
            }
            return merged.values.toList()
        }

        /** 严格键名：`AndroidApp[_N]` / `AndroidApp Signature[_N]`，避免误读同名前缀的用户字段。 */
        private fun isBindingKey(key: String): Boolean {
            if (key == AndroidAppBindingFields.APPLICATION_ID_FIELD_NAME ||
                key == AndroidAppBindingFields.APPLICATION_SIGNATURE_FIELD_NAME
            ) {
                return true
            }
            val suffix = when {
                key.startsWith(AndroidAppBindingFields.APPLICATION_ID_FIELD_NAME + "_") ->
                    key.removePrefix(AndroidAppBindingFields.APPLICATION_ID_FIELD_NAME + "_")
                key.startsWith(AndroidAppBindingFields.APPLICATION_SIGNATURE_FIELD_NAME + "_") ->
                    key.removePrefix(AndroidAppBindingFields.APPLICATION_SIGNATURE_FIELD_NAME + "_")
                else -> return false
            }
            return suffix.isNotEmpty() && suffix.all { it in '0'..'9' }
        }
    }
}
