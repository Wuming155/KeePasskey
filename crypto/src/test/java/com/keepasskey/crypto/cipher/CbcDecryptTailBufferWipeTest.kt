package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * `ISSUE-P3-654`：CBC **解密流**的堆缓冲在正常/异常/提前关闭三条路径上都必须经
 * `close()` 确定性擦除（敏感缓冲所有权契约 §4 #7、R3）。
 *
 * ## 锁定的行为
 *
 * 1. **正常读到尾部**：读取缓冲、复用输入缓冲、链值、交付缓冲、收尾的头段/末块/去填充结果
 *    在 `close()` 后逐字节为 0；
 * 2. **坏 padding**：`finishTailHeap` 抛 `IOException` 之前，`head` / `lastPlain` / `stripped`
 *    已进入清理域——**特别是第二次 `transformBlocks` 抛错（head 已分配）**这一路径，
 *    整改前 head 会逃出清理（它在 try 之外分配，finally 却只擦 try 内变量）；
 * 3. **坏密文长度 / 空输入**：`buffer` 的已读前缀与实例缓冲同样在 close 后清零；
 * 4. **提前 close（未读到尾部）**：已解出的明文未交付部分必须擦除，自持密钥副本随之清零，
 *    而调用方原数组**不在**本流所有权内（不得被擦）；
 * 5. **所有权负例**：`read` 交付给调用方的字节保持有效（R2 转移），不得被后续 close 连带清零
 *    后仍被当作有效数据使用——本类以「交付内容仍在调用方缓冲中逐字节正确」锁定。
 *
 * 观察方式：`observeBuffer` 回调保留每个进入擦除域的数组**引用**，close 后直接查其内容；
 * 同时保存观察时的快照，证明确实观察到了承载明文的缓冲（非空转断言）。
 */
class CbcDecryptTailBufferWipeTest {

    private val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
    private val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }

    private val observed = mutableListOf<ByteArray>()
    private val snapshotAtObservation = mutableListOf<ByteArray>()

    private val observer: (ByteArray) -> Unit = { buffer ->
        observed += buffer
        snapshotAtObservation += buffer.copyOf()
    }

    // ==================== 注入式纯 JVM 变换（JCE CBC 链，与既有用例同型） ====================

    private fun jceCipher(mode: Int): javax.crypto.Cipher {
        ChaCha20CipherEngine.ensureBouncyCastle()
        val cipher = javax.crypto.Cipher.getInstance(
            "AES/CBC/NoPadding",
            org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME
        )
        cipher.init(
            mode,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        return cipher
    }

    private fun decryptTransform(): CbcBlockTransform {
        val cipher = jceCipher(javax.crypto.Cipher.DECRYPT_MODE)
        return { _, _, data -> cipher.update(data) ?: ByteArray(0) }
    }

    private fun encrypt(plain: ByteArray, keyBytes: ByteArray = key): ByteArray {
        ChaCha20CipherEngine.ensureBouncyCastle()
        val paddingCipher = javax.crypto.Cipher.getInstance(
            "AES/CBC/PKCS5Padding",
            org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME
        )
        paddingCipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        return paddingCipher.doFinal(plain)
    }

    private fun newStream(source: InputStream, chunkSize: Int = 64): CbcDecryptingInputStream =
        CbcDecryptingInputStream(
            source = source,
            key = key,
            iv = iv,
            transform = decryptTransform(),
            chunkSize = chunkSize,
            observeBuffer = observer
        )

    // ==================== 断言工具 ====================

    private fun assertAllWiped(label: String) {
        assertTrue("[$label] 用例必须真的观察到缓冲（否则断言空转）", observed.isNotEmpty())
        assertTrue(
            "[$label] 观察到的缓冲中至少有一个原本承载非零内容",
            snapshotAtObservation.any { snapshot -> snapshot.any { it != 0.toByte() } }
        )
        observed.forEachIndexed { index, buffer ->
            val residue = buffer.count { it != 0.toByte() }
            assertTrue(
                "[$label] 第 ${index + 1} 个缓冲（size=${buffer.size}）在 close 后必须逐字节清零，" +
                    "实际残留 $residue 个非零字节",
                residue == 0
            )
        }
    }

    private fun drain(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(37)
        while (true) {
            val count = stream.read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
            java.util.Arrays.fill(buffer, 0)
        }
        return out.toByteArray()
    }

    // ==================== 1. 正常路径 ====================

    @Test
    fun `正常读到尾部后close擦除全部实例缓冲且交付内容正确`() {
        val plain = ByteArray(500) { ((it * 31 + 7) and 0xFF).toByte() }
        val cipherText = encrypt(plain)

        val stream = newStream(ByteArrayInputStream(cipherText), chunkSize = 64)
        val delivered = drain(stream)
        try {
            assertArrayEquals("前置：解密结果必须正确（否则后续擦除断言无意义）", plain, delivered)
        } finally {
            stream.close()
        }
        try {
            assertAllWiped("正常读到尾部")
            assertArrayEquals("交付给调用方的字节必须保持有效（R2 转移）", plain, delivered)
        } finally {
            delivered.fill(0)
            plain.fill(0)
            cipherText.fill(0)
        }
    }

    @Test
    fun `多次close幂等且不改变擦除结果`() {
        val plain = ByteArray(128) { (it + 1).toByte() }
        val cipherText = encrypt(plain)
        val stream = newStream(ByteArrayInputStream(cipherText))
        val delivered = drain(stream)
        stream.close()
        stream.close()
        try {
            assertAllWiped("close 幂等")
            assertThrows(IOException::class.java) { stream.read() }
        } finally {
            delivered.fill(0)
            plain.fill(0)
            cipherText.fill(0)
        }
    }

    // ==================== 2. 坏 padding ====================

    @Test
    fun `坏padding抛IOException后收尾的头段与末块缓冲仍清零`() {
        val plain = ByteArray(400) { ((it * 11 + 5) and 0xFF).toByte() }
        val cipherText = encrypt(plain)
        // 破坏末块：填充校验必然失败
        cipherText[cipherText.size - 1] = (cipherText[cipherText.size - 1].toInt() xor 0xFF).toByte()

        val stream = newStream(ByteArrayInputStream(cipherText), chunkSize = 64)
        try {
            assertThrows(IOException::class.java) { drain(stream) }
        } finally {
            stream.close()
        }
        assertAllWiped("坏 padding")
        plain.fill(0)
        cipherText.fill(0)
    }

    @Test
    fun `仅一个分组且填充分支中第二次变换抛错时头段仍进入清理域`() {
        // 单分组密文：EOF 收尾时 tailLength == 16、keep == 0，head 为空数组；
        // 另造一例 keep > 0（两分组）以确保 head 是非空分配——它是整改前逃出清理的那个数组。
        val twoBlocks = encrypt(ByteArray(16) { 3 })
        assertEquals("前置：两分组密文（16B 明文 + 16B 填充）", 32, twoBlocks.size)
        twoBlocks[twoBlocks.size - 1] = 0 // 破坏填充

        val stream = newStream(ByteArrayInputStream(twoBlocks), chunkSize = 32)
        try {
            assertThrows(IOException::class.java) { drain(stream) }
        } finally {
            stream.close()
        }
        assertAllWiped("坏 padding（head 非空）")
        twoBlocks.fill(0)
    }

    @Test
    fun `第二次变换抛错时已分配的头段明文缓冲仍被清零`() {
        // 直接注入「第二次调用才抛错」的变换：第一次产出 head（非空明文），第二次抛 IllegalStateException。
        // 整改前 finishTailHeap 的 head 在 try 外分配，异常抛出后无人清零。
        var calls = 0
        val failingSecond: CbcBlockTransform = { _, _, data ->
            calls++
            if (calls >= 2) throw IllegalStateException("模拟第二次分组变换失败")
            ByteArray(data.size) { 0x5A } // 非零 head 明文
        }
        val cipherText = ByteArray(32) { 0x11 }
        val stream = CbcDecryptingInputStream(
            source = ByteArrayInputStream(cipherText),
            key = key,
            iv = iv,
            transform = failingSecond,
            chunkSize = 32,
            observeBuffer = observer
        )
        try {
            assertThrows(IllegalStateException::class.java) { drain(stream) }
        } finally {
            stream.close()
        }
        assertAllWiped("第二次变换抛错")
        assertEquals("必须真的走到两次变换（否则未覆盖 head 分配路径）", 2, calls)
    }

    // ==================== 3. 坏长度 / 空输入 ====================

    @Test
    fun `密文长度非分组整数倍抛错后缓冲仍清零`() {
        val cipherText = encrypt(ByteArray(100) { it.toByte() })
        val truncated = cipherText.copyOf(cipherText.size - 7)
        val stream = newStream(ByteArrayInputStream(truncated), chunkSize = 64)
        val thrown = assertThrows(IOException::class.java) {
            try {
                drain(stream)
            } finally {
                stream.close()
            }
        }
        assertTrue(
            "必须给出可定位的失败原因，实际: ${thrown.message}",
            thrown.message.orEmpty().contains("整数倍")
        )
        assertAllWiped("坏长度")
        cipherText.fill(0)
        truncated.fill(0)
    }

    @Test
    fun `空输入抛错后实例缓冲仍清零`() {
        val stream = newStream(ByteArrayInputStream(ByteArray(0)))
        val thrown = assertThrows(IOException::class.java) {
            try {
                drain(stream)
            } finally {
                stream.close()
            }
        }
        assertTrue(
            "空输入必须以「密文为空」fail-closed，实际: ${thrown.message}",
            thrown.message.orEmpty().contains("密文为空")
        )
        assertAllWiped("空输入")
    }

    // ==================== 4. 提前 close ====================

    @Test
    fun `未读到尾部即close时已解出的明文未交付部分必须擦除`() {
        val plain = ByteArray(1000) { ((it * 17 + 3) and 0xFF).toByte() }
        val cipherText = encrypt(plain)
        val stream = newStream(ByteArrayInputStream(cipherText), chunkSize = 64)

        // 只读走第一批明文（首块载荷 = chunkSize - 16 = 48），其余留在实例缓冲里
        val first = ByteArray(48)
        val read = stream.read(first, 0, first.size)
        assertEquals("前置：首轮必须交付一整段明文", 48, read)
        assertArrayEquals(plain.copyOfRange(0, 48), first)

        stream.close()
        try {
            assertAllWiped("提前 close")
            assertArrayEquals(
                "提前 close 不得回改已交付给调用方的明文（R2 转移）",
                plain.copyOfRange(0, 48),
                first
            )
        } finally {
            first.fill(0)
            plain.fill(0)
            cipherText.fill(0)
        }
    }

    @Test
    fun `提前close后继续读取必须抛流已关闭`() {
        val plain = ByteArray(300) { it.toByte() }
        val cipherText = encrypt(plain)
        val stream = newStream(ByteArrayInputStream(cipherText), chunkSize = 64)
        stream.read(ByteArray(32), 0, 32)
        stream.close()
        assertThrows(IOException::class.java) { stream.read() }
        assertAllWiped("提前 close 后读取")
        plain.fill(0)
        cipherText.fill(0)
    }

    // ==================== 5. 所有权负例 ====================

    @Test
    fun `自持密钥副本随close擦除而调用方原数组不受影响`() {
        val callerKey = ByteArray(32) { ((it * 19 + 1) and 0xFF).toByte() }
        val callerKeySnapshot = callerKey.copyOf()
        // 自持副本：与调用方原数组内容相同但**不同实例**（原生路径 `key.copyOf()` 的形态）
        val ownedCopy = callerKey.copyOf()
        val jce = javax.crypto.Cipher.getInstance(
            "AES/CBC/NoPadding",
            org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME
        )
        jce.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(callerKey, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        val plain = ByteArray(64) { 7 }
        val cipherText = encrypt(plain, callerKey)

        val stream = CbcDecryptingInputStream(
            source = ByteArrayInputStream(cipherText),
            key = ownedCopy,
            iv = iv,
            transform = { _, _, data -> jce.update(data) ?: ByteArray(0) },
            chunkSize = 64,
            ownedSecrets = listOf(ownedCopy),
            observeBuffer = observer
        )
        val delivered = drain(stream)
        stream.close()

        try {
            assertArrayEquals("前置：流必须能正确解密（否则擦除断言无意义）", plain, delivered)
            assertTrue("自持密钥副本必须在 close 时清零", ownedCopy.all { it == 0.toByte() })
            assertArrayEquals(
                "调用方原数组不在本流所有权内，不得被擦除",
                callerKeySnapshot,
                callerKey
            )
            assertFalse(
                "调用方原数组必须仍为非零（负例：证明断言不是空转）",
                callerKey.all { it == 0.toByte() }
            )
            assertAllWiped("自持密钥副本")
        } finally {
            delivered.fill(0)
            plain.fill(0)
            callerKey.fill(0)
            callerKeySnapshot.fill(0)
            cipherText.fill(0)
        }
    }
}
