package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxUuid

/**
 * 结构化 KDF 参数容器，支持与 KDBX 4 VariantDictionary 序列化互相转换
 *
 * ### ISSUE-P2-60（审计 RUST-06）：KDF secret `K` 的显式擦除契约
 * Argon2 的 `secretKey`（KDBX4 VariantDictionary `K`，官方 `KdfParameters.K`）为**秘密材料**：
 * 原实现以普通 `ByteArray` 常驻头部且全仓无清零点，寿命上界为进程结束。
 * [clearSensitive] 提供显式擦除入口，其**生命周期契约**（AC③）：
 *
 * - **只允许在「拥有该头部的会话终止」时调用**（`DatabaseSession.lock()` / `close()` /
 *   换库前置释放——三者均在会话互斥锁内且随后 `database = null`，之后不可能再发起保存派生）。
 *   **严禁**在会话存活期调用：保存路径会以同一头部重新派生（`KdbxFile.save` 对
 *   `header.kdfParameters` 复制新盐后派生），清早了会**静默写出用错误密钥加密的库**。
 * - **所有权约定**：`KdbxHeader.copy()` 为 data class 浅拷贝，`secretKey` 数组引用被
 *   保存路径的 `freshKdfParams` / `updatedHeader` 等副本**共享**。本方法是**就地清零**
 *   （fill + 置 null），全部浅拷贝共享同一逻辑所有者，清零后所有持有者同步失效——
 *   这正是期望语义：会话终止后任何滞留副本都不得再可用。
 * - 清零后 [Argon2.secretKey] 为 null：`Argon2KdfEngine` 按「无 secret」跳过、
 *   `KdbxKdfParameterCodec.serialize` 按「缺 K」不写出——两者均为可观测的失效态，
 *   不会以全零数组冒充合法 secret 参与派生（fail-visible 而非静默错密钥）。
 *
 * ### ISSUE-P3-143（第四轮复核 NEW-B01-6）：相等性是**秘密不敏感**语义
 *
 * [Argon2.equals] / [Argon2.hashCode] **刻意不比较** [Argon2.secretKey]（KDBX4
 * VariantDictionary `K`，秘密材料）与 [Argon2.associatedData]。[Aes] 同理只比较
 * `seed` / `rounds`。该忽略是**有意设计**而非缺陷：
 * [Argon2.secretKey] 为 `var`，[clearSensitive] 会就地置 null，若纳入哈希则
 * 「清零后哈希变化」会让对象在 `HashSet` / `HashMap` 中失联（见该字段处的就地说明）。
 *
 * ⚠ **禁止**把本相等性用于「凭据 / 秘密材料是否变化」一类裁决，例如：
 * - 以 `old != new` 判定「是否需要重建会话 / 重新派生 / 更新缓存」；
 * - 把本类型实例放进集合做去重，却期望「仅 secret 不同」的两个实例被区分。
 *
 * 上述用法会**静默**把「仅 secret 变化」的更新吞掉。两个仅 `K` / `A` 不同的实例
 * 被判为**相等**是刻意的、被回归用例锁定的语义
 * （`database/src/test/java/…/file/KdbxHeaderSecretInsensitiveEqualityTest.kt`）。
 *
 * **未来接入约束**：若确实出现「仅 secret 变化」的赋值 / 去重路径，必须改为
 * **显式变更标记**（如会话级 `revision` 计数器、独立的版本号字段）来驱动裁决，
 * **不得**依赖本相等性、也不得为迁就该路径而把 `secretKey` 纳入 `equals` / `hashCode`。
 */
sealed class KdfParameters(val kdfUuid: KdbxUuid) {

    /**
     * 擦除本参数容器中的秘密材料。基类（AES-KDF，无 secret 分量）为 no-op；
     * 仅限会话终止路径调用，契约详见类 KDoc。
     */
    open fun clearSensitive() = Unit

    data class Aes(
        val seed: ByteArray,
        val rounds: Long
    ) : KdfParameters(com.keepasskey.core.model.KdbxConstants.Kdf.AES_KDF) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Aes) return false
            if (!seed.contentEquals(other.seed)) return false
            return rounds == other.rounds
        }

        override fun hashCode(): Int {
            var result = seed.contentHashCode()
            result = 31 * result + rounds.hashCode()
            return result
        }
    }

    data class Argon2(
        val type: Argon2Type,
        val salt: ByteArray,
        val parallelism: Int = 2,
        val memoryInBytes: Long = 64L * 1024 * 1024, // 64 MB
        val iterations: Long = 2L,
        val version: Int = ARGON2_VERSION_13,
        // ISSUE-P2-60：var 以支持 clearSensitive() 的「fill(0) + 置 null」两步擦除——
        // 只 fill 不置 null 会让引擎把全零数组当作合法 secret 参与派生（静默错密钥）；
        // equals/hashCode 刻意忽略本字段（与既有语义一致）
        var secretKey: ByteArray? = null,
        val associatedData: ByteArray? = null
    ) : KdfParameters(
        if (type == Argon2Type.ARGON2D) com.keepasskey.core.model.KdbxConstants.Kdf.ARGON2D
        else com.keepasskey.core.model.KdbxConstants.Kdf.ARGON2ID
    ) {
        enum class Argon2Type {
            ARGON2D,
            ARGON2ID
        }

        companion object {
            const val ARGON2_VERSION_10 = 0x10
            const val ARGON2_VERSION_13 = 0x13
        }

        /**
         * ISSUE-P2-60：擦除 KDF secret `K`——先就地 fill(0) 再置 null。
         * 仅限会话终止路径调用（`KdbxDatabase.clearSensitiveData` 统一收口），
         * 契约与所有权约定详见外层类 KDoc。
         */
        override fun clearSensitive() {
            secretKey?.fill(0)
            secretKey = null
        }

        /**
         * ISSUE-P3-143：**秘密不敏感**相等性——`secretKey`（`K`）与 `associatedData`（`A`）
         * 刻意不参与比较，为有意设计（理由与禁用场景见外层类 KDoc「相等性是秘密不敏感语义」一节）。
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Argon2) return false
            if (type != other.type) return false
            if (!salt.contentEquals(other.salt)) return false
            if (parallelism != other.parallelism) return false
            if (memoryInBytes != other.memoryInBytes) return false
            if (iterations != other.iterations) return false
            if (version != other.version) return false
            return true
        }

        /**
         * ISSUE-P3-143：与 [equals] 同源的**秘密不敏感**哈希——刻意不纳入 `secretKey`，
         * 以保证 [clearSensitive] 就地清零后哈希值**稳定**（否则集合内对象会失联）。
         */
        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + parallelism
            result = 31 * result + memoryInBytes.hashCode()
            result = 31 * result + iterations.hashCode()
            result = 31 * result + version
            return result
        }
    }
}
