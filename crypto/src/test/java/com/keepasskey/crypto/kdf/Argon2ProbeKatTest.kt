package com.keepasskey.crypto.kdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Test

/**
 * `NativeArgon2` 探活 KAT 回归（ISSUE-P3-204）。
 *
 * 背景：`NativeArgon2.available` 此前以「非空即通过」为判据，与同批其余内核
 * （`NativeAesKdf` 与 BC 逐字节比对等 6 个）口径不齐——测不出内核返回同长度垃圾值。
 * ISSUE-P3-204 起探活升级为「固定输入 + 冻结期望摘要」对照，冻结值由独立参考实现
 * （phc-winner-argon2 系 `argon2-cffi`）计算。
 *
 * 本用例把该冻结摘要钉为**回归对拍**：宿主 JVM 经 `cargoHostBuild` 产出的 cdylib
 * 以同一输入派生，必须逐字节等于 [KAT_HEX]。刻意**不**以 `NativeArgon2.available`
 * 作跳过闸门——若内核产出垃圾值，`available` 自身会变 false 并静默跳过（恒绿的
 * 自证循环），故此处直接 `System.loadLibrary`：加载成功就**必须**对上 KAT，
 * 对不上即红；仅「宿主无原生库」才降级跳过（设备侧由 `NativeArgon2InstrumentedTest`
 * 的 BC 冻结向量全量覆盖，与发布门常态锁定）。
 *
 * 敏感数据：输入为公开合成向量（全零占位），输出用后清零。
 */
class Argon2ProbeKatTest {

    @Test
    fun `探活 KAT 输入的原生输出必须逐字节等于冻结摘要`() {
        try {
            System.loadLibrary("keepasskey_argon2")
        } catch (_: UnsatisfiedLinkError) {
            Assume.assumeTrue("宿主无 keepasskey_argon2 原生库（cargo 缺失/未构建），KAT 对照由设备侧覆盖", false)
            return
        }
        val output = NativeArgon2.deriveKey(
            password = ByteArray(PROBE_PASSWORD_LEN),
            salt = ByteArray(PROBE_SALT_LEN),
            secret = null,
            associatedData = null,
            iterations = 1,
            memoryKib = 8,
            parallelism = 1,
            version = 0x13,
            type = NativeArgon2.TYPE_ARGON2ID
        )
        assertNotNull("原生派生返回 null（库已加载但派生失败）", output)
        val expected = KAT_HEX.hexToBytes()
        try {
            assertArrayEquals(
                "探活 KAT 不匹配：原生输出与独立参考实现（argon2-cffi）冻结摘要不一致。" +
                    "若非冻结值漂移，即为内核返回垃圾值（ISSUE-P3-204 要侦测的形态）",
                expected,
                output
            )
        } finally {
            output?.fill(0)
            expected.fill(0)
        }
    }

    private companion object {
        /** 与 [NativeArgon2] 探活输入同参：Argon2id v0x13, t=1, m=8 KiB, p=1, pwd=32×0x00, salt=16×0x00 */
        private const val PROBE_PASSWORD_LEN = 32
        private const val PROBE_SALT_LEN = 16

        /** 冻结期望摘要（argon2-cffi 独立计算；与 `NativeArgon2.PROBE_KAT_HEX` 同源，改一处必改两处） */
        private const val KAT_HEX =
            "c9cc39f9d3cc47bb2db7c1be933c763de2724869bf55c412382afbc904cb3407"

        private fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
