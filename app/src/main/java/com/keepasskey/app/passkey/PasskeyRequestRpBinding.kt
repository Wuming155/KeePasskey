package com.keepasskey.app.passkey

/** 显式 RP 必须对应凭据本身的 RP；缺失 RP 才由既有包名/签名门控裁决。 */
internal object PasskeyRequestRpBinding {
    fun matches(requestRpId: String, credentialRpId: String): Boolean {
        if (requestRpId.isBlank()) return true
        val requested = DomainMatcher.extractDomain(requestRpId)
        val stored = DomainMatcher.extractDomain(credentialRpId)
        return requested.isNotBlank() && requested == stored
    }
}
