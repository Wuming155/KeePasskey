package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-P2-60（审计 RUST-06）回归：`KdbxDatabase.clearSensitiveData()` 统一收口
 * 头部 KDF secret `K` 的擦除。
 *
 * `DatabaseSession.lock()` / `close()` / 换库前置释放与子库只读投影全部经本方法
 * 触发条目树擦除；KDF secret 的清零若不在此收口，会话终止后 `K` 仍以普通
 * `ByteArray` 滞留至 GC（寿命上界 = 进程结束）。回归锁：任何人把
 * `clearSensitiveData()` 退化为「只清条目树」时，本用例即失败。
 */
class KdbxDatabaseSensitiveWipeTest {

    @Test
    fun `clearSensitiveData 擦除头部 KDF secret`() {
        val secret = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val database = KdbxDatabase(
            header = KdbxHeader(
                kdfParameters = KdfParameters.Argon2(
                    type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                    salt = ByteArray(32) { 0x42 },
                    secretKey = secret
                )
            ),
            rootGroup = KdbxGroup(name = "root")
        )

        database.clearSensitiveData()

        val kdf = database.header.kdfParameters as KdfParameters.Argon2
        assertNull("会话终止后 KDF secret 必须已被擦除（置 null）", kdf.secretKey)
        assertArrayEquals(
            "原数组必须就地清零（KdbxHeader 浅拷贝共享者同步失效）",
            ByteArray(4),
            secret
        )
    }

    @Test
    fun `clearSensitiveData 对 AES-KDF 头部为安全 no-op`() {
        val seed = ByteArray(32) { 0x5A }
        val database = KdbxDatabase(
            header = KdbxHeader(kdfParameters = KdfParameters.Aes(seed = seed, rounds = 100L)),
            rootGroup = KdbxGroup(name = "root")
        )

        database.clearSensitiveData()

        val kdf = database.header.kdfParameters as KdfParameters.Aes
        assertEquals(100L, kdf.rounds)
        assertArrayEquals(seed, kdf.seed)
    }
}
