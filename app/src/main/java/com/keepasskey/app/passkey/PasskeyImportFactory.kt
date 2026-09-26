package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import java.security.SecureRandom
import java.util.Base64

/**
 * 导入所得凭据 → [PasskeyData]（`ISSUE-P3-337` 口径 6，第 3 片落库接线的**唯一构造点**）。
 *
 * ## 三处非显然口径（都有裁决依据，改动前必读）
 *
 * 1. **PRF 存储形态 = 标准 Base64 文本**（不是载荷里的 Base64URL）。
 *    理由：驻留字段的消费方 [com.keepasskey.crypto.passkey.PasskeyPrf] 用
 *    `Base64.getDecoder()` 解码（`PasskeyPrf.decodeSecret`），且生成侧
 *    [PasskeyPrf.newSecretProtected] 也用标准编码器——导入若写 Base64URL，
 *    含 `+` / `/` 的种子会在断言时抛「PRF 秘密不是合法 Base64 文本」，
 *    一把完好凭据的 prf 扩展就此失效。载荷解码出的原始字节在这里**重新编码**，
 *    与生成侧逐字节同形。
 *    ⚠️ 连带事实（`PD-49` 裁决二当场暴露的边界）：`decodeSecret` 还硬要求**恰 32 字节**，
 *    故长度非 32 的导入种子虽按裁决「原样保真存」，**断言时会被 fail-closed 拒绝**。
 *    这是「存储保真」与「可用性」的有意分工：宁可有值不可用，也不静默改写字节；
 *    已登记进 `docs/architecture/已知工程限界.md`（见条目第 3 片留痕）。
 * 2. **签名计数器 = 随机高位起点**（`PD-49` 裁决一 (γ′)）：写入
 *    `[2^20, 2^24)` 内一枚 `SecureRandom` 均匀随机值，此后由既有
 *    [PasskeyData.nextSignCount] 单调 +1，断言链零改动。
 *    为什么不用「不写键、从 1 递增」：RP 侧存的是**别家认证器**给出的既有计数（重度使用的
 *    硬件密钥以千计，且我方不可见），从 1 起步要成功断言 N 次才爬出「new ≤ stored」的
 *    克隆嫌疑区间（WebAuthn L3 §6.1.1），实践上等价于永不恢复 ⇒ 随机高位一步跨过。
 *    ⚠️ 代价如实登记：**导入凭据的计数器不再具备克隆检测语义**，须同批进限界表，
 *    未登记不得声称本条闭环（`PD-49` 重开条件：真实 RP 对大幅跳变触发风控时回退「不写键」）。
 * 3. **`Passkey.PrfNoUv` 只存不用**（`PD-48` 裁决二 (c′)）：CXF 的 `credWithoutUV`
 *    写入本仓扩展键（受保护），断言链**不**按 UV 选种子——WebAuthn L3 §10.1.4 明文
 *    prf 扩展「MUST be the one used for when user verification is performed」，
 *    照 §10.1.4 才是合规，改造断言反而是错。第二枚不可恢复，故必须存（一次性捕获）。
 *
 * ## 公钥与 BE/BS
 *
 * - `publicKeyBase64` 留空：与「外部管理器创建的条目」同一口径（公钥可由私钥推导，
 *   生产路径只在注册当场消费，不回读；见 [PasskeyData.publicKeyBase64] KDoc）；
 * - `backupEligible = true` / `backupState = true`（代理取向，理由：载荷能到本仓即已经过
 *   一次**导出**，「已备份」为如实陈述；BE 取本仓默认「库可同步故可备份」。
 *   CXF 与 KeePassXC `.passkey` 都不承载这两位，属**我方裁量**，两值会经 AuthenticatorData
 *   的 BE/BS 位交给 RP，故在此写明依据而不是沉默沿用默认）。
 *
 * **擦除义务**：本工厂**不**清零入参 [ImportedPasskey] 的秘密材料——它只复制进
 * [ProtectedString] 驻留形态；调用链必须在**所有路径**（含用户取消）调用
 * [ImportedPasskey.wipeSecrets]（AC③ 锁这一点，取消路径不经此处，故不能靠本工厂擦）。
 */
object PasskeyImportFactory {

    /** 随机高位起点区间的下界（含）：`2^20`，远高于个人真实使用可达的既有计数。 */
    const val IMPORT_SIGN_COUNT_MIN: Int = 1 shl 20

    /** 随机高位起点区间的上界（不含）：`2^24`，仍远低于 uint32 上限，留足单调递增余量。 */
    const val IMPORT_SIGN_COUNT_MAX_EXCLUSIVE: Int = 1 shl 24

    private val secureRandom = SecureRandom()

    /** 导入凭据的计数器起点：`[IMPORT_SIGN_COUNT_MIN, IMPORT_SIGN_COUNT_MAX_EXCLUSIVE)` 均匀随机。 */
    fun randomImportSignCount(): Int =
        IMPORT_SIGN_COUNT_MIN + secureRandom.nextInt(IMPORT_SIGN_COUNT_MAX_EXCLUSIVE - IMPORT_SIGN_COUNT_MIN)

    /**
     * 构造落库用 [PasskeyData]。
     *
     * 私钥以 [ImportedPasskey.privateKeyPemChars]（PKCS#8 PEM 字符）直接挂接成受保护驻留字段，
     * 全程不产生 `String`（`AGENTS.md` §3 铁律 / AC③）。
     */
    fun toPasskeyData(credential: ImportedPasskey): PasskeyData = PasskeyData(
        relyingPartyId = credential.relyingPartyId,
        userHandle = credential.userHandle,
        userName = credential.userName,
        userDisplayName = credential.userDisplayName,
        credentialId = credential.credentialId,
        algorithmId = credential.algorithmId,
        publicKeyBase64 = "",
        privateKey = ProtectedString(credential.privateKeyPemChars, isProtected = true),
        signCount = randomImportSignCount(),
        backupEligible = true,
        backupState = true,
        createdAtMillis = System.currentTimeMillis(),
        prfSecret = credential.prfWithUv?.let { toBase64Protected(it) },
        prfNoUvSecret = credential.prfWithoutUv?.let { toBase64Protected(it) }
    )

    /**
     * 原始种子字节 → 与生成侧同形的**标准 Base64 文本**受保护驻留字段。
     *
     * 逐字节照 [com.keepasskey.crypto.passkey.PasskeyPrf] 的 `secretToProtected` 口径
     * （编码副本用毕即清零；不经过 `String`）。**不校验长度**——`PD-49` 裁决二规定
     * 长度不判、不裁、不拒收，原样保真存。
     */
    private fun toBase64Protected(secret: ByteArray): ProtectedString {
        val encoded = Base64.getEncoder().encode(secret)
        try {
            return ProtectedString(CharArray(encoded.size) { encoded[it].toInt().toChar() }, isProtected = true)
        } finally {
            encoded.fill(0)
        }
    }
}
