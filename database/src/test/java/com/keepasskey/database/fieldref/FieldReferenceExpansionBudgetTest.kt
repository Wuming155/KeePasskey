package com.keepasskey.database.fieldref

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-200 落点②：`{REF:}` 展开的**输出预算 + 分支计数双闸门**。
 *
 * ## 缺陷形态
 * [FieldReferenceEngine.MAX_DEPTH] 只封递归**层数**，不封每层的展开**个数**；而每个 match 的
 * 替换结果会作为下一层输入继续展开 ⇒ 分支因子 = 目标字段内引用个数，k 层后放大 `k^depth`。
 * 自引用（`{REF:N@T:<自身标题>}`）即可构造：字段内放 k 个引用、`k=4` 时叶展开数 ~4×10⁶，
 * 输出长度旧实现下**无上界**；`VaultListDecorationsProvider` 每次 emission 对全库投影批量展开
 * 且无兜底 ⇒ 列表渲染即崩（准持久 DoS）。
 *
 * ## 本类锚定
 * 1. 指数构造必须**在预算内收敛**（不 OOM、不挂起），且超限段**原样保留引用原文**；
 * 2. 空标题检索键（`""`）必须命中——它既是构造放大器的必要前提，也是既有语义的一部分；
 * 3. 预算内的合法链**不得误拒**（对照组的「能绿」面）。
 */
class FieldReferenceExpansionBudgetTest {

    private fun entry(
        title: String,
        username: String = "",
        notes: String = ""
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        fields = buildMap {
            put(KdbxConstants.Fields.TITLE, ProtectedString(title, false))
            put(KdbxConstants.Fields.USER_NAME, ProtectedString(username, false))
            if (notes.isNotEmpty()) {
                put(KdbxConstants.Fields.NOTES, ProtectedString(notes, false))
            }
        }
    )

    private fun rootWith(vararg entries: KdbxEntry): KdbxGroup =
        KdbxGroup(id = KdbxUuid.random(), name = "Root", entries = entries.toList())

    /** 空标题的检索键（`{REF:N@T:}` 第三段为空）命中的就是本条目自身 */
    private val selfReference = "{REF:N@T:}"

    @Test
    fun `空标题检索键必须命中而非判为未命中`() {
        val target = entry(title = "", username = "hit")
        val root = rootWith(target)

        assertEquals(
            "检索键为空串必须命中「标题为空」的条目（KeePass 语义：按字段值精确检索）",
            "hit",
            FieldReferenceEngine.resolve("{REF:U@T:}", root, FieldReferenceEngine.RefField.USER_NAME)
        )
    }

    @Test
    fun `分支因子大于一的指数自引用必须在预算内收敛且保留未展开段`() {
        val notes = selfReference.repeat(BRANCHING_FACTOR)
        val root = rootWith(entry(title = "", notes = notes))

        val resolved = FieldReferenceEngine.resolve(notes, root, FieldReferenceEngine.RefField.NOTES)

        assertTrue(
            "产物上界 ≈ 输入长度 + 输出预算（ISSUE-P2-200：输出长度不再无界）；实际=${resolved.length}",
            resolved.length <= FieldReferenceEngine.MAX_PRODUCED_CHARS + notes.length
        )
        assertTrue(
            "超限段必须原样保留引用原文（不抛错、不吞掉用户数据）",
            resolved.contains("{REF:", ignoreCase = true)
        )
    }

    @Test
    fun `展示侧批量展开走同一预算约束`() {
        // 列表渲染（VaultListDecorationsProvider）消费的就是展示侧解析，须与取值侧同等受限
        val notes = selfReference.repeat(BRANCHING_FACTOR)
        val root = rootWith(entry(title = "", notes = notes))

        val resolved = FieldReferenceEngine.resolveForDisplay(notes, root)

        assertTrue(
            "展示侧产物上界与取值侧一致（列表渲染热路径）；实际=${resolved.length}",
            resolved.length <= FieldReferenceEngine.MAX_PRODUCED_CHARS + notes.length
        )
    }

    @Test
    fun `预算内的合法引用链仍完整展开`() {
        // 对照组：线性链 + 少量分支（真实库形态）绝不能被双闸门误伤
        val target = entry(title = "Target", username = "octocat")
        val relay = entry(title = "Relay", username = "{REF:U@T:Target}")
        val consumer = entry(title = "Consumer", username = "{REF:U@T:Relay}|{REF:U@T:Target}")

        assertEquals(
            "两次展开 + 一层链路的合法解析必须逐字展开（双闸门不得误拒）",
            "octocat|octocat",
            FieldReferenceEngine.resolve(
                consumer.userName,
                rootWith(target, relay, consumer),
                FieldReferenceEngine.RefField.USER_NAME
            )
        )
    }

    private companion object {
        /**
         * 分支因子：字段内的引用个数。ISSUE-P2-200 第二轮终裁指出既有 `A→B→A` 回归用例
         * 分支因子恒为 1（只证循环不死递归，对**乘法放大零覆盖**），故此处显式取 4。
         */
        const val BRANCHING_FACTOR = 4
    }
}
