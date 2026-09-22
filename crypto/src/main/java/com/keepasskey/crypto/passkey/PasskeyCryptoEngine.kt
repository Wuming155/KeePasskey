package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.cose.CoseKey
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.crypto.digests.SHA256Digest
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * 通行密钥 (Passkey / WebAuthn) 密码学计算引擎。
 * 遵循 W3C WebAuthn Level 3 与 FIDO2 规范：
 * 1. 支持三类主流公钥签名算法密钥对生成：
 *    - ES256 (ECDSA P-256 / secp256r1 with SHA-256)；
 *    - Ed25519 (EdDSA OKP 纯净曲线)；
 *    - RS256 (RSASSA-PKCS1-v1_5 with SHA-256 2048-bit)。
 * 2. 导出 COSE_Key 结构与支持证明数据 (Attested Credential Data) 的 AuthenticatorData 二进制块；
 * 3. 统一签名 API，集成确定性 RFC 6979 DSA、Ed25519 原生验签与 RSA-SHA256，所有私钥运算保证敏感内存清理。
 *
 * ISSUE-P1-02（生成侧内存脱敏）：密钥对生成全程**零 String 中间量**——ES256 私钥标量直接从
 * BigInteger 二进制形态编码为 hex [CharArray]，Ed25519/RS256 的 Base64 编码经 [CharArray] 通道
 * 封装，全部中间字节/字符副本用毕即 `fill(0)` 擦除。私钥文本形态的唯一长期持有者是
 * [ProtectedString]（InMemoryCipher 密文驻留），KDBX 受保护字段以文本承载属格式层不可消解边界。
 *
 * 纯结构性下沉：[PasskeyKeyCodec] 承载编解码/解析，[PasskeyAssertionSigner] 承载三类签名实现，
 * [PasskeyKeyGeneration] 承载三类密钥对生成（`ISSUE-P3-188` §174，生成侧的零 String 中间量与
 * 逐副本清零纪律随代码同迁未放宽），[PasskeyLegacyKeyText] 承载历史 v1 私钥文本形态
 * （`ISSUE-P3-213`）；本对象保留**同名公开入口的一行委托**、装配与统一签名入口，
 * 公开 API 与敏感清零点不变。
 *
 * 签名入口分两档（`ISSUE-P3-212`）：[signAssertion] 不改动调用方缓冲；
 * [signAssertionConsumingKey] 在 `finally` 中单点擦除调用方缓冲，生产断言路径走后者。
 */
object PasskeyCryptoEngine {

    /**
     * 签名侧私钥材料：[algorithmId] 为 COSE 算法标识，[keyBytes] 为该算法的签名入参
     * （ES256 = 32 字节标量、Ed25519 = 32 字节种子、RS256 = PKCS#8 DER）。
     */
    class PasskeySigningKey(
        val algorithmId: Int,
        val keyBytes: ByteArray
    )

    // AuthenticatorData Flags 标志位常量 (W3C WebAuthn Section 6.1)
    const val FLAG_UP: Byte = 0x01             // 用户在场 (User Present)
    const val FLAG_UV: Byte = 0x04             // 用户已验证 (User Verified)
    const val FLAG_BE: Byte = 0x08             // 支持备份 (Backup Eligibility)
    const val FLAG_BS: Byte = 0x10             // 已备份状态 (Backup State)
    const val FLAG_AT: Byte = 0x40             // 包含证明凭据数据 (Attested Credential Data Present)
    const val FLAG_ED: Byte = 0x80.toByte()    // 包含扩展数据 (Extension Data Present)

    /**
     * 本认证器（KeePasskey 自建 Authenticator）的 AAGUID。
     * (`ISSUE-P2-265`：与参考实现对齐，登记真实身份而非全零)
     *
     * **必须长期稳定**：RP 会把 AAGUID 作为认证器身份留存，并据此在凭据管理页展示提供方
     * 名称（Google RP 指引：「When you save the passkey on the app server, make sure that
     * you save the Authenticator Attestation Globally Unique Identifier (AAGUID) from the
     * client data.」）。变更只影响**新注册**，既有凭据保持有效。
     *
     * 取值来源（可离线复算，非随手常量）：
     * `UUIDv5(DNS, "keepasskey.app/webauthn/authenticator")` = `d8a7de40-8975-5786-bd94-605287e4357f`
     * （版本位 `5`、变体位 `10x` 均符合 RFC 4122）。
     *
     * 为什么不再用 16 字节全零：全零在规范里的语义是「该认证器**没有** AAGUID」，
     * 等于主动放弃提供方身份。对照两个可工作的参考实现——`参考项目/KeePassDX-master` 的
     * `credentialprovider/passkey/data/AuthenticatorAttestationResponse.kt:95` 使用固定
     * `eaecdef2-1c31-5634-8639-f1cbd9c00a08`；`参考项目/Monica-main` 的
     * `passkey/PasskeyCreateActivity.kt:99-108` 使用固定 `6d6f6e69-6361-4d33-a001-706173736b79`
     * 并明确注释「Relying parties may display authenticator brand from AAGUID」——
     * 两者都登记了真实 AAGUID，本仓是唯一使用全零的实现。
     */
    val DEFAULT_AAGUID: ByteArray = byteArrayOf(
        0xd8.toByte(), 0xa7.toByte(), 0xde.toByte(), 0x40.toByte(),
        0x89.toByte(), 0x75.toByte(), 0x57.toByte(), 0x86.toByte(),
        0xbd.toByte(), 0x94.toByte(), 0x60.toByte(), 0x52.toByte(),
        0x87.toByte(), 0xe4.toByte(), 0x35.toByte(), 0x7f.toByte()
    )

    /**
     * 凭据 ID 的字节上限：`Attested Credential Data` 里的 `credentialIdLength` 是
     * **2 字节大端整数**（W3C WebAuthn §6.1），故上限即 uint16 最大值
     * （`ISSUE-P3-188` 第 4 目 §168 收敛：原为 `require` 里内联的字面量）。
     */
    const val CREDENTIAL_ID_MAX_BYTES: Int = 0xFFFF


    /**
     * 三类密钥对生成的公开入口（`ISSUE-P3-188` §174：实现下沉 [PasskeyKeyGeneration]，
     * 签名与语义逐字未改，本对象只留一行委托，调用点零改动）。
     */
    fun generateEs256KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData = PasskeyKeyGeneration.es256(relyingPartyId, userName, userHandle, userDisplayName)

    fun generateEd25519KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData = PasskeyKeyGeneration.ed25519(relyingPartyId, userName, userHandle, userDisplayName)

    fun generateRs256KeyPair(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData = PasskeyKeyGeneration.rs256(relyingPartyId, userName, userHandle, userDisplayName)

    /**
     * 本认证器支持的 COSE 算法，按**协商偏好顺序**排列（ES256 → Ed25519 → RS256）。
     *
     * 与 W3C WebAuthn 的通行做法一致：P-256 兼容面最广故列首位；Ed25519 次之；
     * RS256 签名体最大、性能最差列为末位（但仍须支持——部分企业 RP 只接受 RS256）。
     */
    val SUPPORTED_ALGORITHMS: List<Int> = listOf(
        PasskeyData.ALGORITHM_ES256,
        PasskeyData.ALGORITHM_ED25519,
        PasskeyData.ALGORITHM_RS256
    )

    /** [algorithmId] 是否为本认证器可生成的算法 */
    fun isAlgorithmSupported(algorithmId: Int): Boolean = algorithmId in SUPPORTED_ALGORITHMS

    /**
     * 依 RP 的 `pubKeyCredParams` 协商出注册所用算法。
     *
     * 语义（对齐 KeePassDX `Signature.generateKeyPair(pubKeyCredParams.map { it.alg })`）：
     * - 取 [SUPPORTED_ALGORITHMS] 中**首个出现在请求列表里**的算法（即本认证器的偏好顺序）；
     * - 请求列表为空（非规范输入）→ 回落 ES256（兼容面最广，且不因此拒绝注册）；
     * - 请求的算法**全部不受支持** → 抛 [CryptoException.InvalidKeyException]，
     *   调用方 fail-closed 拒绝注册（绝不降级为 RP 未请求的算法后返回给 RP）。
     */
    fun selectAlgorithmForRegistration(requestedAlgorithms: List<Int>): Int {
        if (requestedAlgorithms.isEmpty()) return PasskeyData.ALGORITHM_ES256
        return SUPPORTED_ALGORITHMS.firstOrNull { it in requestedAlgorithms }
            ?: throw CryptoException.InvalidKeyException(
                "RP 请求的公钥算法均不受支持（请求数 ${requestedAlgorithms.size}），拒绝注册"
            )
    }

    /**
     * 按 RP 的 `pubKeyCredParams` 协商算法并生成对应密钥对（注册路径的**唯一**入口）。
     *
     * @throws CryptoException.InvalidKeyException 请求算法全部不受支持
     */
    fun generateKeyPairForAlgorithms(
        requestedAlgorithms: List<Int>,
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData = when (val algorithmId = selectAlgorithmForRegistration(requestedAlgorithms)) {
        PasskeyData.ALGORITHM_ES256 -> generateEs256KeyPair(relyingPartyId, userName, userHandle, userDisplayName)
        PasskeyData.ALGORITHM_ED25519 -> generateEd25519KeyPair(relyingPartyId, userName, userHandle, userDisplayName)
        PasskeyData.ALGORITHM_RS256 -> generateRs256KeyPair(relyingPartyId, userName, userHandle, userDisplayName)
        else -> throw CryptoException.InvalidKeyException("协商得到的算法无法生成密钥对: $algorithmId")
    }

    /**
     * PEM 私钥文本（字节流）→ 算法标识 + 签名侧材料（32 字节标量 / 32 字节种子 / 整段 PKCS#8 DER）。
     *
     * 供 app 层的断言路径消费 KeePassXC / KeePassDX 口径（`KPEX_PASSKEY_PRIVATE_KEY_PEM`）的
     * 私钥文本；**非 PEM 或结构非法返回 null**，由调用方回退到历史 v1 形态的解析。
     * 全程走字节通道（ISSUE-P1-02：私钥不物化为不可擦除的 String）。
     */
    fun decodePemPrivateKeyText(keyTextBytes: ByteArray): PasskeySigningKey? {
        if (!com.keepasskey.core.model.PasskeyKeyText.isPem(keyTextBytes)) return null
        val chars = CharArray(keyTextBytes.size) { keyTextBytes[it].toInt().toChar() }
        try {
            val signingKey = PasskeyPkcs8Codec.pemCharsToSigningKey(chars)
            return PasskeySigningKey(signingKey.algorithmId, signingKey.keyBytes)
        } catch (_: IllegalArgumentException) {
            return null
        } finally {
            chars.fill('0')
        }
    }

    /**
     * 统一 Passkey 认证断言签名 API。
     * 支持 ES256 (-7)、Ed25519 (-8) 与 RS256 (-257)；执行完毕后自动清零**内部克隆**的密钥缓冲。
     *
     * 注意：[privateKeyBytes] 归**调用方**所有，本入口不改动它；需要引擎代为擦除调用方缓冲时
     * 必须改用 [signAssertionConsumingKey]（`ISSUE-P3-212` 的单点清零契约）。
     *
     * @param algorithmId COSE 算法标识
     * @param privateKeyBytes 承载私钥材料的字节流（原始私钥标量、种子或 PKCS#8 DER 编码）
     * @param dataToSign 待签名原始字节流 (通常为 authenticatorData || clientDataHash)
     * @return 遵循 WebAuthn 规范的签名产物（ES256 输出 ASN.1 DER，Ed25519 输出 64B raw，RS256 输出 256B PKCS1-v1_5）
     */
    fun signAssertion(algorithmId: Int, privateKeyBytes: ByteArray, dataToSign: ByteArray): ByteArray {
        val workingKey = privateKeyBytes.clone()
        try {
            return when (algorithmId) {
                PasskeyData.ALGORITHM_ES256 -> PasskeyAssertionSigner.signEs256(workingKey, dataToSign)
                PasskeyData.ALGORITHM_ED25519 -> PasskeyAssertionSigner.signEd25519(workingKey, dataToSign)
                PasskeyData.ALGORITHM_RS256 -> PasskeyAssertionSigner.signRs256(workingKey, dataToSign)
                else -> throw CryptoException.InvalidKeyException("不支持的 Passkey 签名算法标识: $algorithmId")
            }
        } finally {
            Arrays.fill(workingKey, 0.toByte())
        }
    }

    /**
     * **消费式**统一签名入口（`ISSUE-P3-212`）：签名语义与 [signAssertion] 逐字相同，
     * 差别只在私钥缓冲的**所有权**——本入口在 `finally` 中无条件擦除调用方传入的
     * [privateKeyBytes]（无论签名成功还是抛出），使「签名后清零调用方私钥缓冲」成为
     * **引擎的单点契约**（对齐 KeePassDX `Signature.sign`），调用方不再需要各自记忆
     * `finally { fill(0) }`，也就不会再因遗漏 `finally` 而留下敏感私钥残留。
     *
     * **传参即转移所有权**：调用方在本调用返回后**不得**再读取该数组（恒为全零）。
     *
     * @return 同 [signAssertion]
     */
    fun signAssertionConsumingKey(
        algorithmId: Int,
        privateKeyBytes: ByteArray,
        dataToSign: ByteArray
    ): ByteArray {
        try {
            return signAssertion(algorithmId, privateKeyBytes, dataToSign)
        } finally {
            Arrays.fill(privateKeyBytes, 0.toByte())
        }
    }

    /**
     * 历史 v1 私钥文本字节流 → 原始签名材料（ES256 = 标量 / Ed25519 = 种子 / RS256 = PKCS#8 DER）。
     *
     * 供断言回退路径消费既有库中的 v1 条目（`ISSUE-P3-213` 起该解码口径收敛到本入口与
     * `PasskeyLegacyKeyText` 单点，app 层不再各留一份）。
     *
     * @throws IllegalArgumentException 文本既非 hex 也非合法 Base64（fail-closed）
     */
    fun decodeLegacyPrivateKeyBytes(keyTextBytes: ByteArray): ByteArray =
        PasskeyLegacyKeyText.legacyTextToKeyBytes(keyTextBytes)

    /**
     * 历史 v1 私钥文本 → PKCS#8 PEM 文本（`ISSUE-P3-213` 写路径就地迁移的公开入口）。
     *
     * 无法转换（形态非法 / 与 [algorithmId] 不匹配 / 标量越界）时返回 null，由调用方
     * 放弃迁移并保持原文——fail-safe 而非 fail-closed：迁移是可选的收敛动作，绝不因它
     * 失败的而阻断计数器写入或让条目变成不可用。
     *
     * @return PEM 文本（CharArray，调用方用毕清零）；不可迁移时为 null
     */
    fun legacyPrivateKeyTextToPemChars(keyTextBytes: ByteArray, algorithmId: Int): CharArray? =
        PasskeyLegacyKeyText.legacyTextToPemChars(keyTextBytes, algorithmId)

    /**
     * 构建标准 AuthenticatorData 二进制块（无证明凭据数据）
     */
    fun buildAuthenticatorData(
        rpId: String,
        flags: Byte,
        signCount: Int
    ): ByteArray {
        return buildAuthenticatorData(rpId, flags, signCount, null, null)
    }

    /**
     * 构建包含可选证明凭据数据 (Attested Credential Data) 的 AuthenticatorData 二进制块。
     * 当 flags 包含 FLAG_AT (0x40) 时，自动组装：
     * aaguid (16B) + credentialIdLength (2B 大端) + credentialId + COSE 公钥 CBOR。
     */
    fun buildAuthenticatorData(
        rpId: String,
        flags: Byte,
        signCount: Int,
        credentialId: ByteArray?,
        cosePublicKey: ByteArray?,
        aaguid: ByteArray = DEFAULT_AAGUID
    ): ByteArray {
        val sha256 = SHA256Digest()
        val rpIdBytes = rpId.toByteArray(Charsets.UTF_8)
        sha256.update(rpIdBytes, 0, rpIdBytes.size)
        val rpIdHash = ByteArray(32)
        sha256.doFinal(rpIdHash, 0)

        val out = ByteArrayOutputStream()
        // 1. rpIdHash (32 字节)
        out.write(rpIdHash)
        // 2. flags (1 字节)
        out.write(flags.toInt() and 0xFF)
        // 3. signCount (4 字节大端整数)
        out.write((signCount ushr 24) and 0xFF)
        out.write((signCount ushr 16) and 0xFF)
        out.write((signCount ushr 8) and 0xFF)
        out.write(signCount and 0xFF)

        // 4. Attested Credential Data 段 (仅当 flags 具有 FLAG_AT 时写入)
        val hasAttestedData = (flags.toInt() and FLAG_AT.toInt()) != 0
        if (hasAttestedData) {
            requireNotNull(credentialId) { "flags 声明 AT (0x40) 时 credentialId 不能为空" }
            requireNotNull(cosePublicKey) { "flags 声明 AT (0x40) 时 cosePublicKey 不能为空" }
            require(credentialId.size <= CREDENTIAL_ID_MAX_BYTES) { "credentialId 长度超出 16 位整数上限: ${credentialId.size}" }

            // 4.1 aaguid (16 字节)
            val finalAaguid = if (aaguid.size == 16) aaguid else DEFAULT_AAGUID
            out.write(finalAaguid)

            // 4.2 credentialIdLength (2 字节大端整数)
            out.write((credentialId.size ushr 8) and 0xFF)
            out.write(credentialId.size and 0xFF)

            // 4.3 credentialId
            out.write(credentialId)

            // 4.4 credentialPublicKey (COSE_Key CBOR)
            out.write(cosePublicKey)
        }

        return out.toByteArray()
    }

    /**
     * 根据算法标识与原始公钥字节流组装对应的 COSE_Key CBOR 二进制结构。
     * 供上层构建 attestationObject 时直接调用。
     *
     * @param algorithmId COSE 算法标识
     * @param publicKeyBytes 公钥数据（EC 支持 65B 未压缩点/64B raw/SPKI；Ed25519 支持 32B raw/SPKI；RSA 支持 SPKI 或 ASN.1 RSAPublicKey）
     */
    fun coseKeyFor(algorithmId: Int, publicKeyBytes: ByteArray): ByteArray {
        return when (algorithmId) {
            PasskeyData.ALGORITHM_ES256 -> {
                val (x, y) = PasskeyKeyCodec.extractEcPoint(publicKeyBytes)
                CoseKey.ec2P256(x, y)
            }
            PasskeyData.ALGORITHM_ED25519 -> {
                val rawPub = PasskeyKeyCodec.extractEd25519PublicKey(publicKeyBytes)
                CoseKey.ed25519(rawPub)
            }
            PasskeyData.ALGORITHM_RS256 -> {
                val (n, e) = PasskeyKeyCodec.extractRsaModulusAndExponent(publicKeyBytes)
                CoseKey.rsa2048(n, e)
            }
            else -> throw CryptoException.InvalidKeyException("不支持的 COSE Key 算法标识: $algorithmId")
        }
    }

    /**
     * 库内公钥字节流 → **DER 编码的 X.509 `SubjectPublicKeyInfo`（SPKI）**。
     *
     * 供注册响应的 `response.publicKey` 使用：W3C WebAuthn 规范把
     * `AuthenticatorAttestationResponse.getPublicKey()` 定义为「DER-encoded
     * SubjectPublicKeyInfo」，与 [coseKeyFor] 产出（COSE_Key CBOR，只用于
     * `authData.credentialPublicKey`）是**同一公钥在不同字段上的不同编码**，
     * 不可互换、也不得互相替代。
     *
     * 本仓此前该字段误用 COSE_Key CBOR；两个参考实现中 Monica 用 SPKI
     * （`keyPair.public.encoded`）并在「同设备 / 同浏览器 / 同站点」对照下 100% 成功，
     * 故本入口用于对齐规范与参考实现。
     */
    fun publicKeySubjectInfoFor(algorithmId: Int, publicKeyBytes: ByteArray): ByteArray =
        PasskeyKeyCodec.toSubjectPublicKeyInfo(algorithmId, publicKeyBytes)

}
