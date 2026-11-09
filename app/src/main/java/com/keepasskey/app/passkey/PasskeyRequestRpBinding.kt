package com.keepasskey.app.passkey

/**
 * 请求侧 RP 与凭据侧 RP 的对照结果。
 *
 * **为什么不能是布尔**：整改前「请求未给出 RP」与「请求 RP 与凭据 RP 一致」在同一个
 * `true` 里被合并（`ENG-20261028-06`）。二者授权含义完全相反：
 * - [MATCHED]：请求自己声明了要哪把密钥，RP 维度有请求侧的参照；
 * - [MISSING]：请求**没有**声明 RP，凭据身份只能由调用方身份或凭据自身推定——
 *   此时能否签发取决于调用方是否可认证，见 [PasskeyRpAuthorization]；
 * - [MISMATCHED]：请求声明的是别的 RP，一律不签发。
 *
 * 口径沿用 WebAuthn：`rpId` 缺省时取「调用方 origin 的有效域」。浏览器委派调用满足该前提
 * （origin 由系统背书，域即 RP）；`android:apk-key-hash:` 形态的原生调用**没有**有效域，
 * 包名也不是 RP 标识符，故缺失 RP 时不存在请求侧的 RP 授权参照。
 */
internal enum class RequestRpBinding {
    /** 请求给出显式 RP，且与凭据 RP 归一化后精确相等 */
    MATCHED,

    /** 请求未给出 RP（缺失或全空白）：RP 身份没有请求侧参照，须由调用方身份裁决 */
    MISSING,

    /** 请求给出显式 RP，但与凭据 RP 不一致（含凭据 RP 为空、或归一化后为空） */
    MISMATCHED
}

/**
 * 显式 RP 与凭据 RP 的精确对照（纯函数，无 Android 依赖）。
 *
 * 父子域规则**不参与**此处：产品口径是「明确 RP ID 必须与通行密钥 RP ID 归一化后精确相等，
 * 父子域只用于 origin 对 RP 的授权」，origin ⇄ RP 的归属复校由签发侧单独执行
 * （见 [PasskeyRpAuthorization] 与 `PasskeyAssertionActivity` 的 web 分支）。
 */
internal object PasskeyRequestRpBinding {

    /** 三值对照结果（新代码请用本函数，勿再以布尔吞掉 [RequestRpBinding.MISSING]） */
    fun evaluate(requestRpId: String, credentialRpId: String): RequestRpBinding {
        if (requestRpId.isBlank()) return RequestRpBinding.MISSING
        val requested = DomainMatcher.extractDomain(requestRpId)
        val stored = DomainMatcher.extractDomain(credentialRpId)
        return if (requested.isNotBlank() && requested == stored) {
            RequestRpBinding.MATCHED
        } else {
            RequestRpBinding.MISMATCHED
        }
    }

    // 原布尔口径 `matches` 已随 ENG-20261028-06 退役：它把「缺失 RP」与「显式相等」读作同一个
    // `true`，正是该问题的成因，整改后也无生产调用方；需要「是否相抵触」直接判
    // [RequestRpBinding.MISMATCHED]。
}
