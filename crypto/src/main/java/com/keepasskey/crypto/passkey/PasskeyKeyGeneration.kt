package com.keepasskey.crypto.passkey
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory

/**
 * Passkey **密钥对生成**面（`ISSUE-P3-188` §174 自 [PasskeyCryptoEngine] 逐字搬入）。
 *
 * 与已有的 [PasskeyKeyCodec]（编解码 / 解析）和 [PasskeyAssertionSigner]（三类签名）同构：
 * 本对象承载「生成」这一族，[PasskeyCryptoEngine] 保留同名公开入口并一行委托，
 * 因此**调用点与公开 API 不变**。生成侧的内存脱敏纪律随代码同迁且未放宽：
 * 全程零 String 中间量、私钥文本以 [CharArray] 通道封装进 [ProtectedString]、
 * 字节 / 字符副本用毕即 `fill(0)` 擦除（ISSUE-P1-02）。
 */
internal object PasskeyKeyGeneration {


    private val ecParams: X9ECParameters = SECNamedCurves.getByName("secp256r1")
    private val domainParams = ECDomainParameters(ecParams.curve, ecParams.g, ecParams.n, ecParams.h)
    private val secureRandom = SecureRandom()

    /**
     * 生成全新的 ES256 (ECDSA P-256) 密钥对及凭据数据。
     * 公钥编码格式：未压缩椭圆曲线点 (0x04 || X || Y) Base64。
     * 私钥编码格式：标量大整数 16 进制字符串（封装于 ProtectedString 中）。
     */
    fun es256(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = ECKeyPairGenerator()
        val genParam = ECKeyGenerationParameters(domainParams, secureRandom)
        generator.init(genParam)
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as ECPrivateKeyParameters
        val pub = keyPair.public as ECPublicKeyParameters

        val credIdBase64Url = generateRandomCredentialId()
        val pubEncoded = pub.q.getEncoded(false)
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）+ KeePassXC / KeePassDX 互操作：私钥驻留文本为 **PKCS#8 PEM**
        // （`KPEX_PASSKEY_PRIVATE_KEY_PEM` 口径），编码全程在 CharArray 通道上完成、中间 DER 用毕
        // 即清零，再封装进 ProtectedString 密文驻留层供 KDBX 受保护字段存储。
        val privateKeyProtected = sealedFromPrivateChars(PasskeyPkcs8Codec.encodeEcToPem(priv.d))

        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_ES256,
            publicKeyBase64 = pubBase64,
            privateKey = privateKeyProtected,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    /**
     * 生成全新的 Ed25519 (EdDSA) 密钥对及凭据数据。
     * 公钥编码格式：32 字节原始公钥 (Raw Public Key) Base64。
     * 私钥编码格式：32 字节原始私钥种子 (Raw Private Seed) Base64（封装于 ProtectedString 中）。
     */
    fun ed25519(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as Ed25519PrivateKeyParameters
        val pub = keyPair.public as Ed25519PublicKeyParameters

        val privEncoded = priv.encoded
        val pubEncoded = pub.encoded
        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）+ 互操作：驻留文本为 PKCS#8 PEM（RFC 8410 编码），
        // PEM 字符副本与原始种子字节副本用毕即清零。
        val privateKey = try {
            sealedFromPrivateChars(PasskeyPkcs8Codec.encodeEd25519ToPem(privEncoded))
        } finally {
            Arrays.fill(privEncoded, 0.toByte())
        }

        val credIdBase64Url = generateRandomCredentialId()
        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_ED25519,
            publicKeyBase64 = pubBase64,
            privateKey = privateKey,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    /**
     * 生成全新的 RS256 (RSASSA-PKCS1-v1_5 2048 位) 密钥对及凭据数据。
     * 公钥编码格式：X.509 SubjectPublicKeyInfo DER Base64。
     * 私钥编码格式：PKCS#8 PrivateKeyInfo DER Base64（封装于 ProtectedString 中）。
     */
    fun rs256(
        relyingPartyId: String,
        userName: String,
        userHandle: String = "",
        userDisplayName: String = ""
    ): PasskeyData {
        val generator = RSAKeyPairGenerator()
        // P3-13 整改（TASK-29）：素数确定性参数 certainty 由 12 提升至 80——
        // 原值下伪素数（False-prime）漏检概率约 1/2^12，远低于工业要求；80 对齐
        // BouncyCastle 官方示例与主流密码库默认（单素数误判概率 ≤ 2^-80），
        // 生成耗时可接受（Miller-Rabin 轮数增加对 2048 位密钥仅为毫秒级）。
        val rsaGenParam = RSAKeyGenerationParameters(
            BigInteger.valueOf(65537),
            secureRandom,
            2048,
            80
        )
        generator.init(rsaGenParam)
        val keyPair = generator.generateKeyPair()

        val priv = keyPair.private as RSAPrivateCrtKeyParameters
        val pub = keyPair.public as RSAKeyParameters

        val privInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(priv)
        val pubInfo = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub)

        val privEncoded = privInfo.encoded
        val pubEncoded = pubInfo.encoded

        val pubBase64 = Base64.getEncoder().encodeToString(pubEncoded)

        // ISSUE-P1-02（生成侧脱敏）+ 互操作：驻留文本为 PKCS#8 DER 的 PEM 包裹，
        // PEM 字符副本与 DER 中间副本用毕即清零。
        val privateKey = try {
            sealedFromPrivateChars(PasskeyPkcs8Codec.encodeToPem(priv))
        } finally {
            Arrays.fill(privEncoded, 0.toByte())
        }

        val credIdBase64Url = generateRandomCredentialId()
        val handle = resolveUserHandle(userHandle)

        return PasskeyData(
            relyingPartyId = relyingPartyId,
            userHandle = handle,
            userName = userName,
            userDisplayName = userDisplayName.ifBlank { userName },
            credentialId = credIdBase64Url,
            algorithmId = PasskeyData.ALGORITHM_RS256,
            publicKeyBase64 = pubBase64,
            privateKey = privateKey,
            signCount = 0,
            backupEligible = true,
            backupState = true
        )
    }

    // ================= 注册算法协商（WebAuthn `pubKeyCredParams`） =================


    // ================= 私有实现与辅助工具 =================

    /**
     * 以 [CharArray] 承载的私钥文本封装受保护字段（InMemoryCipher 密文驻留），
     * 封装完成后字符副本立即清零。生成侧统一出口：私钥材料在此之后仅以
     * [ProtectedString] 形态存活，明文仅存活于受控读取瞬间。
     */
    private fun sealedFromPrivateChars(chars: CharArray): ProtectedString {
        try {
            return ProtectedString(chars, isProtected = true)
        } finally {
            Arrays.fill(chars, '0')
        }
    }

    /**
     * 生成凭据 ID（16 字节随机 ⇒ Base64URL 无填充）。
     *
     * **长度口径**：WebAuthn 规范（`§5.4.1`）只要求 credentialId 为 1..1023 字节，
     * 本身不限定长度；但两个成熟的参考实现**都取 16 字节**——
     * KeePassDX `HashManager.generateRandom(16)`（其代码显式引用规范该节），
     * Monica `generateCredentialId()` 亦为 `ByteArray(16)`。
     *
     * 本仓此前用 **32 字节**。在「同设备 / 同站点，KeePassDX 与 Monica 都能通过、
     * 仅本仓失败」的对照中，这是两个成功实现唯一的**共同取值**，
     * 故收敛为 16 字节以对齐互操作口径（部分依赖方按 UUID 语义解析该字段，
     * 非 16 字节会被判为无效凭据）。
     */
    private fun generateRandomCredentialId(): String {
        val credIdBytes = ByteArray(16)
        secureRandom.nextBytes(credIdBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(credIdBytes)
    }

    private fun resolveUserHandle(userHandle: String): String {
        return if (userHandle.isBlank()) {
            val handleBytes = ByteArray(16)
            secureRandom.nextBytes(handleBytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes)
        } else {
            userHandle
        }
    }
}
