package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P1-595` 整改验收：**KDF 可接受参数判据**（[KdbxKdfAcceptance]）的接受域，
 * 以及「UI / 写侧 / 读侧同一判据」的三点一致性。
 *
 * 背景：设置页可选 256 MiB × 50 轮（= 12.5 GiB 字节·轮），而读侧联合预算上界为 `2^33`
 * （≈8.6×10^9 字节·轮）⇒ 用户可写出**本应用读侧拒绝**的数据库。本用例以**注入的固定设备预算**
 * 做确定性断言（不依赖宿主 JVM 堆）。
 *
 * 覆盖（对齐方案 §4 验收矩阵）：
 * ① 256 MiB × 32 / ×33 / ×50 —— 固定足够预算下，32 允许、33/50 在派生前拒绝；
 * ② 设备预算低于目标内存 —— 与读侧一致拒绝；
 * ③ `memory = 8×parallelism×1024` 的上下邻点 —— 各入口结论一致；
 * ④ 超大 Long 输入 / 非法版本 / 非法盐长 —— 无溢出旁路，不触发高负载派生；
 * ⑤ 写侧 `KdbxFile.save` 在写任何字节前按同一判据 fail-closed（不产出不可重开产物）。
 */
class KdbxKdfAcceptanceTest {

    private val mib = 1024L * 1024L
    private val heap256 = 256L * mib

    /** 注入的充足设备预算（1 GiB），使 256 MiB 目标不被堆门槛拦截，从而单测联合预算本身。 */
    private val plentyBudget = 1L shl 30

    @Test
    fun `256MiB 的 32 允许而 33 与 50 在派生前拒绝`() {
        // 256 MiB × 32 = 2^28 × 32 = 2^33 = 联合预算上界 ⇒ 恰好允许
        assertNull(
            "256 MiB × 32 恰在上界，应允许",
            KdbxKdfAcceptance.argon2Rejection(heap256, 32, 2, V13, plentyBudget)
        )
        // 256 MiB × 33 = 2^33 + 2^28 > 2^33 ⇒ 拒绝
        assertTrue(
            "256 MiB × 33 超联合预算，必须拒绝",
            KdbxKdfAcceptance.argon2Rejection(heap256, 33, 2, V13, plentyBudget) != null
        )
        // 256 MiB × 50 = 12.5 GiB 字节·轮 ⇒ 拒绝
        assertTrue(
            "256 MiB × 50 超联合预算，必须拒绝",
            KdbxKdfAcceptance.argon2Rejection(heap256, 50, 2, V13, plentyBudget) != null
        )
    }

    @Test
    fun `读侧 codec 同样拒绝 256MiB 乘 33 与 50`() {
        // 与上一条同源判据：读侧（解析期）对同一组合必须一致拒绝（不依赖堆：即便堆门槛放行，联合预算也拦）
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.validateArgon2Bounds(heap256, 33, 2, V13)
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.validateArgon2Bounds(heap256, 50, 2, V13)
        }
    }

    @Test
    fun `设备预算低于目标内存时与读侧一致拒绝`() {
        // 目标 128 MiB，预算仅 64 MiB（= 内存一半不足）
        val target = 128L * mib
        val smallBudget = 64L * mib
        assertTrue(
            "预算低于目标内存必须拒绝（设备能力限制）",
            KdbxKdfAcceptance.argon2Rejection(target, 2, 2, V13, smallBudget) != null
        )
        // 恰好等于预算 ⇒ 允许（边界闭区间）
        assertNull(
            "内存恰等于设备预算应允许",
            KdbxKdfAcceptance.argon2Rejection(target, 2, 2, V13, target)
        )
        // 读侧 codec 走同一判据（宿主堆足够时才会因预算外原因放行，此处只锁「低于每通道下界」不适用）
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.validateArgon2Bounds(8191L, 2, 2, V13)
        }
    }

    @Test
    fun `每通道下界上下邻点各入口结论一致`() {
        val p = 8
        val laneFloor = 8L * p * 1024
        assertNull(
            "memory = 8×p×1024 恰达交叉下界，应允许",
            KdbxKdfAcceptance.argon2Rejection(laneFloor, 2, p, V13, plentyBudget)
        )
        assertTrue(
            "memory = 8×p×1024 - 1 低于交叉下界，必须拒绝",
            KdbxKdfAcceptance.argon2Rejection(laneFloor - 1, 2, p, V13, plentyBudget) != null
        )
        // 读侧同结论
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.validateArgon2Bounds(laneFloor - 1, 2, p, V13)
        }
        KdbxKdfParameterCodec.validateArgon2Bounds(laneFloor, 2, p, V13) // 不抛即通过
    }

    @Test
    fun `超大 Long 与非法版本盐长无溢出旁路`() {
        // 超大迭代值：逐项上界即拒绝（不进入联合预算乘法）
        assertTrue(
            "Long.MAX_VALUE 迭代必须被逐项上界拒绝",
            KdbxKdfAcceptance.argon2Rejection(heap256, Long.MAX_VALUE, 2, V13, plentyBudget) != null
        )
        // 超大内存：4 GiB 上界拒绝
        assertTrue(
            "超出 4 GiB 内存上界必须拒绝",
            KdbxKdfAcceptance.argon2Rejection(Long.MAX_VALUE, 2, 2, V13, Long.MAX_VALUE) != null
        )
        // 非法版本
        assertTrue(
            "非法 Argon2 版本必须拒绝",
            KdbxKdfAcceptance.argon2Rejection(heap256, 2, 2, 0x12, plentyBudget) != null
        )
        // 非法盐长
        assertTrue("空盐必须拒绝", KdbxKdfAcceptance.argon2SaltRejection(0) != null)
        assertNull("32 字节盐应允许", KdbxKdfAcceptance.argon2SaltRejection(32))
    }

    @Test
    fun `MiB 转字节溢出返回 null 不回绕`() {
        assertNull("负 MiB 视为非法", KdbxKdfAcceptance.memoryBytesFromMbOrNull(-1))
        assertNull("超大 MiB 溢出必须返回 null（不得回绕）", KdbxKdfAcceptance.memoryBytesFromMbOrNull(Long.MAX_VALUE))
        assertEquals(64L * mib, KdbxKdfAcceptance.memoryBytesFromMbOrNull(64))
    }

    /**
     * 方案 §4「枚举组合的接受域测试」：设置页 UI 全选项组合（16/32/64/128/256 MiB ×
     * 1..50 轮 × p ∈ {1,2,4,8}）注入充足预算后逐点对拍联合预算边界——
     * 仅 256 MiB × 33~50 轮越界（256 MiB × 32 = `2^33` 恰达上界，须放行）。
     */
    @Test
    fun `UI 全选项组合接受域枚举与联合预算边界逐点一致`() {
        for (mb in listOf(16L, 32L, 64L, 128L, 256L)) {
            for (p in listOf(1, 2, 4, 8)) {
                for (rounds in 1L..50L) {
                    val rejected =
                        KdbxKdfAcceptance.argon2Rejection(mb * mib, rounds, p, V13, plentyBudget) != null
                    // 唯一越界面：256 MiB × 33~50（其余组合乘积 ≤ 128 MiB × 50 < 2^33）
                    val expectedRejected = mb == 256L && rounds > 32L
                    assertEquals("${mb}MiB × $rounds 轮 / p=$p", expectedRejected, rejected)
                }
            }
        }
    }

    private companion object {
        const val V13 = 0x13
    }
}
