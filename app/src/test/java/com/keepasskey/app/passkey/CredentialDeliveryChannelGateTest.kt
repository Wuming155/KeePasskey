package com.keepasskey.app.passkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据通道开关的交付判定表（ENG-20261028-05）。
 *
 * 关闭条件要求「按总开关、公钥开关和 Autofill 开关各自作用域，在链式响应和最终交付/保存边界
 * 拒绝已关闭通道」，且「不得用公钥开关屏蔽密码请求」。CM 两路的判定由
 * [CredentialDeliveryChannelGate] 单点给出，本类穷举其输入组合：
 *
 * - 口令交付只看总开关——[CredentialDeliveryChannelGate.allowsPasswordDelivery] 的签名不含
 *   通行密钥开关，故「关掉通行密钥」在构造上无法屏蔽密码；
 * - 公钥交付 = 总开关 ∧ 通行密钥开关——总开关关闭时，即使通行密钥开关仍开启也不得交付。
 *
 * 交付落点（`PasswordFillActivity` / `PasskeyAssertionActivity` / `PasswordSaveActivity` /
 * `CredentialUnlockActivity` / `CredentialResponseAssembler`）均经本对象求值，故判定表正确
 * 即这些边界的作用域正确；边界是否真的接线、开关关闭后系统流程是否停止交付，仍须设备验收，
 * 不以本类替代。
 */
class CredentialDeliveryChannelGateTest {

    @Test
    fun `口令交付只受凭据管理器总开关约束`() {
        assertTrue(
            "总开关开启 ⇒ 口令可交付",
            CredentialDeliveryChannelGate.allowsPasswordDelivery(credentialProviderEnabled = true)
        )
        assertFalse(
            "总开关关闭 ⇒ 口令不可交付",
            CredentialDeliveryChannelGate.allowsPasswordDelivery(credentialProviderEnabled = false)
        )
    }

    @Test
    fun `公钥交付需要总开关与通行密钥开关同时开启`() {
        assertTrue(
            "两个开关都开启 ⇒ 公钥可交付",
            CredentialDeliveryChannelGate.allowsPasskeyDelivery(
                credentialProviderEnabled = true,
                passkeySupportEnabled = true
            )
        )
        assertFalse(
            "关闭通行密钥 ⇒ 公钥不可交付（口令不受影响）",
            CredentialDeliveryChannelGate.allowsPasskeyDelivery(
                credentialProviderEnabled = true,
                passkeySupportEnabled = false
            )
        )
        assertFalse(
            "总开关关闭时，通行密钥开关仍开启也不得交付公钥",
            CredentialDeliveryChannelGate.allowsPasskeyDelivery(
                credentialProviderEnabled = false,
                passkeySupportEnabled = true
            )
        )
        assertFalse(
            "两个开关都关闭 ⇒ 公钥不可交付",
            CredentialDeliveryChannelGate.allowsPasskeyDelivery(
                credentialProviderEnabled = false,
                passkeySupportEnabled = false
            )
        )
    }
}
