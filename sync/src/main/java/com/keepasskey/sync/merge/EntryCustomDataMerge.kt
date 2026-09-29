package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import java.time.Instant

/**
 * 条目级 customData / SignCount 三方合并策略（ISSUE-P2-385 / P2-387）。
 * 自 [KdbxEntryMerger] 按单一职责拆出（行数门禁）。
 */
internal object EntryCustomDataMerge {

    /**
     * SignCount 取 max：两侧均可解析出非负整数时取较大者（保持 RP 单调性）；
     * 不可解析 / 缺失时回退 LWW（有值侧胜，双缺为空）。
     */
    fun mergeSignCountField(
        lc: KdbxCustomField?,
        rc: KdbxCustomField?,
        bc: KdbxCustomField?
    ): KdbxCustomField? {
        val lNum = lc?.let { parseSignCountValue(it.value) }
        val rNum = rc?.let { parseSignCountValue(it.value) }
        return when {
            lNum == null && rNum == null -> bc
            lNum == null -> rc
            rNum == null -> lc
            else -> if (rNum >= lNum) rc else lc
        }
    }

    fun parseSignCountValue(value: ProtectedString?): Long? {
        if (value == null) return null
        val text = try {
            value.readString()
        } catch (_: Exception) {
            null
        } ?: return null
        return text.trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    /**
     * 自定义字段三方合并：判定口径同标准字段，冲突记入 [diffFields]。
     * ISSUE-P2-387：SignCount 键改取 max。
     */
    fun mergeCustomFields(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry,
        diffFields: MutableList<String>
    ): List<KdbxCustomField> {
        val baseCustomMap = base?.customFields?.associateBy { it.key } ?: emptyMap()
        val localCustomMap = local.customFields.associateBy { it.key }
        val remoteCustomMap = remote.customFields.associateBy { it.key }
        val allCustomKeys = (localCustomMap.keys + remoteCustomMap.keys + baseCustomMap.keys).toSet()
        val mergedCustomFields = mutableListOf<KdbxCustomField>()

        for (key in allCustomKeys) {
            val bc = baseCustomMap[key]
            val lc = localCustomMap[key]
            val rc = remoteCustomMap[key]
            val lChanged = lc?.value != bc?.value
            val rChanged = rc?.value != bc?.value

            if (key == PasskeyData.FIELD_SIGN_COUNT) {
                val winner = mergeSignCountField(lc, rc, bc)
                if (winner != null) mergedCustomFields.add(winner)
                continue
            }

            when {
                lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                !lChanged && rChanged -> if (rc != null) mergedCustomFields.add(rc)
                !lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                else -> {
                    if (lc?.value == rc?.value) {
                        if (lc != null) mergedCustomFields.add(lc)
                    } else {
                        diffFields.add(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + key)
                        val picked = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) rc else lc
                        if (picked != null) mergedCustomFields.add(picked)
                    }
                }
            }
        }
        return mergedCustomFields
    }

    /**
     * 条目级 customData 三方合并（ISSUE-P2-385）：键并集 + 三方值裁决。
     */
    fun mergeCustomData(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): Map<String, String> {
        val baseMap = base?.customData.orEmpty()
        val localMap = local.customData
        val remoteMap = remote.customData
        return mergeCustomDataMap(baseMap, localMap, remoteMap, local.times.lastModificationTime, remote.times.lastModificationTime)
    }

    fun mergeCustomDataMap(
        base: Map<String, String>?,
        local: Map<String, String>,
        remote: Map<String, String>,
        localTime: Instant,
        remoteTime: Instant
    ): Map<String, String> {
        val baseMap = base.orEmpty()
        if (local == remote) return local
        if (local == baseMap && remote != baseMap) return remote
        if (remote == baseMap && local != baseMap) return local
        val keys = baseMap.keys + local.keys + remote.keys
        val merged = LinkedHashMap<String, String>()
        for (key in keys) {
            val bv = baseMap[key]
            val lv = local[key]
            val rv = remote[key]
            val winner = when {
                lv != bv && rv == bv -> lv
                lv == bv && rv != bv -> rv
                lv == rv -> lv
                else -> if (remoteTime.isAfter(localTime)) rv else lv
            }
            if (winner != null) merged[key] = winner
        }
        return merged
    }
}
