package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `ISSUE-P3-165`：`KdbxGroupMerger.sanitizeParentLinks` 的**判定等价性**回归。
 *
 * 该函数此前**零用例覆盖**（全仓无任何用例引用），而它是「远端可构造链状/环状父链」进入合并树前的
 * 唯一净化面：判定错一处即可能让**环**留在树里（渲染无限递归 / 合并永远不收敛）或把正常分组
 * 误挂到根（分组归属静默丢失）。本批把判定由「逐组上溯 O(G × 深度)」改为「一次函数图染色 O(G)」，
 * 故以**参照实现**（改动前的算法逐字抄录）+ **随机图对拍**锁定两者逐项等价。
 */
class KdbxGroupMergerSanitizeTest {

    private val rootId: KdbxUuid = KdbxUuid.random()

    @Test
    fun `自环与互环均被判为需挂回根组`() {
        val a = KdbxUuid.random()
        val b = KdbxUuid.random()
        // a 指向自身；b 与 c 互指；d 指向已失链的父组
        val c = KdbxUuid.random()
        val d = KdbxUuid.random()
        val groups = mapOf(
            a to group(a, a),
            b to group(b, c),
            c to group(c, b),
            d to group(d, KdbxUuid.random())
        )

        val sanitized = KdbxGroupMerger.sanitizeParentLinks(groups, rootId)

        assertEquals("自环必须挂回根组", rootId, sanitized[a]!!.parentGroupId)
        assertEquals("互环双方都必须挂回根组", rootId, sanitized[b]!!.parentGroupId)
        assertEquals("互环双方都必须挂回根组", rootId, sanitized[c]!!.parentGroupId)
        assertEquals("失链父组必须挂回根组", rootId, sanitized[d]!!.parentGroupId)
    }

    @Test
    fun `父链进入环的分组同样必须挂回根组`() {
        val cycA = KdbxUuid.random()
        val cycB = KdbxUuid.random()
        val tail = KdbxUuid.random() // tail → cycA → cycB → cycA（tail 不在环上，但其父链进入环）
        val groups = mapOf(
            cycA to group(cycA, cycB),
            cycB to group(cycB, cycA),
            tail to group(tail, cycA)
        )

        val sanitized = KdbxGroupMerger.sanitizeParentLinks(groups, rootId)

        assertEquals("链尾（父链进入环）也必须挂回根组——与逐组上溯的「重现即判环」一致", rootId, sanitized[tail]!!.parentGroupId)
    }

    @Test
    fun `健康层级与指向根的分组保持原样`() {
        val parent = KdbxUuid.random()
        val child = KdbxUuid.random()
        val directChild = KdbxUuid.random()
        val groups = mapOf(
            parent to group(parent, rootId),
            child to group(child, parent),
            directChild to group(directChild, rootId)
        )

        val sanitized = KdbxGroupMerger.sanitizeParentLinks(groups, rootId)

        assertSame("健康子分组必须按同一实例透传", groups[child], sanitized[child])
        assertEquals(
            "直接挂在根下的分组按原口径仍走「挂回根组」分支（内容是 copy，父组仍是根）",
            rootId,
            sanitized[directChild]!!.parentGroupId
        )
        assertEquals(
            "指向根的分组同理走挂回根组分支",
            rootId,
            sanitized[parent]!!.parentGroupId
        )
    }

    @Test
    fun `随机图与逐组上溯的判定逐项一致`() {
        val random = Random(20260917) // 固定种子：用例可复现
        repeat(300) { round ->
            val size = 1 + random.nextInt(8)
            val ids = List(size) { KdbxUuid.random() }
            val groups = ids.associateWith { id ->
                // 约 1/6 概率无父组；其余随机指向（含自身 / 已删除的 id —— 后者由父链失链分支覆盖）
                val parent = when (random.nextInt(6)) {
                    0 -> null
                    1 -> KdbxUuid.random()
                    else -> ids[random.nextInt(ids.size)]
                }
                group(id, parent)
            }

            val actual = KdbxGroupMerger.sanitizeParentLinks(groups, rootId)
            val expected = referenceSanitize(groups, rootId)

            assertEquals(
                "第 $round 轮（规模 $size）：染色法与逐组上溯的判定必须逐项一致",
                expected.mapValues { it.value.parentGroupId },
                actual.mapValues { it.value.parentGroupId }
            )
        }
    }

    @Test
    fun `长链上溯不再逐组重复走链`() {
        // 语义断言：200 节点长链的判定结果正确（原实现为 O(G×深度)，本批降为 O(G)；
        // 复杂度本身不可由用例证明，故此处只锁「长链下结论正确」）
        val chain = List(200) { KdbxUuid.random() }
        val groups = chain.withIndex().associate { (index, id) ->
            id to group(id, if (index == chain.lastIndex) rootId else chain[index + 1])
        }

        val sanitized = KdbxGroupMerger.sanitizeParentLinks(groups, rootId)

        chain.forEachIndexed { index, id ->
            val expectedParent = if (index == chain.lastIndex) rootId else chain[index + 1]
            assertEquals("长链第 $index 节的父链必须原样保留", expectedParent, sanitized[id]!!.parentGroupId)
        }
    }

    private fun group(id: KdbxUuid, parent: KdbxUuid?) =
        KdbxGroup(id = id, parentGroupId = parent, name = "g-${id.toHexString().take(4)}")

    /**
     * 参照实现：`ISSUE-P3-165` 改动前的算法**逐字抄录**（逐组上溯 + 每组一个 visited 集）。
     * 仅用于对拍，不参与生产路径。
     */
    private fun referenceSanitize(
        survivingGroups: Map<KdbxUuid, KdbxGroup>,
        rootId: KdbxUuid
    ): Map<KdbxUuid, KdbxGroup> {
        val sanitizedGroups = mutableMapOf<KdbxUuid, KdbxGroup>()
        for ((gid, g) in survivingGroups) {
            val targetParent = g.parentGroupId
            if (targetParent == null || targetParent == rootId || !survivingGroups.containsKey(targetParent)) {
                sanitizedGroups[gid] = g.copy(parentGroupId = rootId)
            } else {
                var curr = targetParent
                var hasCycle = false
                val visited = mutableSetOf(gid)
                while (curr != null && curr != rootId && survivingGroups.containsKey(curr)) {
                    if (!visited.add(curr)) {
                        hasCycle = true
                        break
                    }
                    curr = survivingGroups[curr]?.parentGroupId
                }
                if (hasCycle) {
                    sanitizedGroups[gid] = g.copy(parentGroupId = rootId)
                } else {
                    sanitizedGroups[gid] = g
                }
            }
        }
        return sanitizedGroups
    }
}
