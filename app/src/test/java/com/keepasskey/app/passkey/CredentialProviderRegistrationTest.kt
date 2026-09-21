package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-239` 的**判定穷举**用例（AC③：判据落纯函数、平台查询单点化，宿主 JVM 可测）。
 *
 * ## 本项要防的失效形态
 * 「系统未启用本应用」此前完全静默：系统在框架层直接丢弃创建请求，用户只看到「点保存没反应」。
 * 判定一旦错向**宽松**侧（把「读不到」当成「已启用」），界面就会给出一张全绿的卡——比不检测更坏；
 * 错向**严格**侧（把「读不到」当成「未启用」）则会误导用户去系统设置里改一个本来正确的项。
 * 故本类对**全部**输入形态穷举，并锁定「未知既不等于已登记、也不等于未登记」。
 *
 * 输入侧只有三个取值（[CredentialProviderEnabledState]），故穷举是**完备**的；
 * 平台查询一侧（`android.credentials.CredentialManager.isEnabledCredentialProviderService`
 * 的调用与异常收敛）由 [CredentialProviderHealthWiringTest] 与真机读数负责。
 */
class CredentialProviderRegistrationTest {

    @Test
    fun `系统答复已启用时判为已登记`() {
        assertEquals(
            CredentialProviderRegistration.REGISTERED,
            CredentialProviderRegistration.of(CredentialProviderEnabledState.ENABLED)
        )
    }

    @Test
    fun `系统答复未启用时判为未登记`() {
        assertEquals(
            CredentialProviderRegistration.NOT_REGISTERED,
            CredentialProviderRegistration.of(CredentialProviderEnabledState.DISABLED)
        )
    }

    /**
     * AC② 的核心断言：**读取失败一律呈现「未知」，不得呈现为「正常」**。
     *
     * 反例形态（本项要防的）：若把 `UNREADABLE` 映射成 `REGISTERED`，用户在「点保存没反应」时
     * 会看到一张全绿的卡；若映射成 `NOT_REGISTERED`，则会得到一个「系统未启用本应用」的
     * **断言式结论**，而实际上我们什么都没读到——两者都是把「未知」伪装成已知。
     */
    @Test
    fun `读取失败判为未知且不得伪装成已登记或未登记`() {
        val unknown = CredentialProviderRegistration.of(CredentialProviderEnabledState.UNREADABLE)

        assertEquals(CredentialProviderRegistration.UNKNOWN, unknown)
        assertNotEquals(
            "「未知」不得被当成「已登记」（谎报正常是本项最坏的失效形态）",
            CredentialProviderRegistration.REGISTERED,
            unknown
        )
        assertNotEquals(
            "「未知」不得被当成「未登记」（那会给出一个我们并未读到的断言）",
            CredentialProviderRegistration.NOT_REGISTERED,
            unknown
        )
    }

    /** 穷举完备性反校：输入三态 → 输出三态且**互不重合**（任一态被改写即红） */
    @Test
    fun `三态输入到三态输出是一一对应`() {
        val mapped = CredentialProviderEnabledState.entries.map {
            CredentialProviderRegistration.of(it)
        }

        assertEquals("输入取值数已变化，本穷举用例需同步（防空转）", 3, CredentialProviderEnabledState.entries.size)
        assertEquals("输出取值数已变化，本穷举用例需同步（防空转）", 3, CredentialProviderRegistration.entries.size)
        assertEquals("不同输入映射到了同一输出，说明判定丢失了区分度", 3, mapped.toSet().size)
        assertTrue(
            "全部输出必须落在枚举内",
            mapped.all { it in CredentialProviderRegistration.entries }
        )
    }
}
