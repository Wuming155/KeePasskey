package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import java.time.Instant

/**
 * 标量字段规格：`read` / `write` 绑定同一字段，泛型擦除点（`Any?`）的转型只在 `write`
 * 闭包内发生，由词汇表自身保证不错位。
 */
internal class ScalarFieldSpec(
    /** ISSUE-P2-281：冲突差异键（机读词汇，与 `KdbxMerger.CONFLICT_KEY_*` 同表）。 */
    val conflictKey: String,
    val read: (KdbxEntry) -> Any?,
    val write: (KdbxEntry, Any?) -> KdbxEntry
)

/**
 * 条目标量 / 联合到期字段的**单一词汇表**（`ISSUE-P2-279` 立表；`ISSUE-P2-597` 扩至
 * AutoType / 颜色 / 联合到期）。
 *
 * [KdbxEntryMerger.isModified] 的判修改集、`mergeConflictedEntry` 的实际合并集与
 * `diffFields` 上报集一律由本表驱动，**禁止在多处各写一份字段清单**。
 *
 * 字段口径：
 * - `iconId` / `customIconId` / `overrideUrl`：本仓有真实写入者，跨端合并必须生效；
 * - `qualityCheck`：本仓无写入者，保留在表内是为合并 KeePassXC / 官方桌面端产生的改动；
 * - `autoType`：以**完整配置对象**参与三方合并（本项不新增自动输入执行功能）；
 * - `backgroundColor` / `foregroundColor`：条目级颜色，纳入统一词汇表与冲突键；
 * - **联合到期**：`expires` 与 `expiryTime` 以**单一规格**原子读写——`read` 归一为
 *   `Instant?`（禁用到期 ⇒ `null`，不生效的旧日期不参与比较与合并）；`write` 一次性改写两字段，
 *   杜绝望「逐字段获胜」产生**两端都未选择的混合状态**。
 */
internal object EntryScalarVocabulary {

    val FIELDS: List<ScalarFieldSpec> = listOf(
        ScalarFieldSpec(KdbxMerger.CONFLICT_KEY_ICON_ID, { it.iconId }, { e, v -> e.copy(iconId = v as Int) }),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_CUSTOM_ICON_ID,
            { it.customIconId },
            { e, v -> e.copy(customIconId = v as KdbxUuid?) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_OVERRIDE_URL,
            { it.overrideUrl },
            { e, v -> e.copy(overrideUrl = v as String?) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_QUALITY_CHECK,
            { it.qualityCheck },
            { e, v -> e.copy(qualityCheck = v as Boolean) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_AUTO_TYPE,
            { it.autoType },
            { e, v -> e.copy(autoType = v as KdbxAutoType?) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_BACKGROUND_COLOR,
            { it.backgroundColor },
            { e, v -> e.copy(backgroundColor = v as String?) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_FOREGROUND_COLOR,
            { it.foregroundColor },
            { e, v -> e.copy(foregroundColor = v as String?) }
        ),
        ScalarFieldSpec(
            KdbxMerger.CONFLICT_KEY_EXPIRES,
            { e -> if (e.times.expires) e.times.expiryTime else null },
            { e, v ->
                val expiry = v as Instant?
                e.copy(times = e.times.copy(expires = expiry != null, expiryTime = expiry ?: e.times.expiryTime))
            }
        )
    )
}
