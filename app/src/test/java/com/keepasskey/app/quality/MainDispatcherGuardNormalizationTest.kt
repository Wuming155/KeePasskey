package com.keepasskey.app.quality

import com.keepasskey.app.autofill.AutofillPickerViewModel
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-307` / §309：守卫「装新不卸」口径的**运行期回归锁**（与
 * [MainDispatcherPollutionGuardTest] 的静态判据互补）。
 *
 * ## 复现的最小条件（条目 AC①）
 *
 * 上一个测试类经 `Dispatchers.setMain(StandardTestDispatcher())` 装上 Main 且「只装不卸」
 * （§153 时代 34 个类如此），其 scheduler 无人推进 ⇒ 死调度器。本类用例随后**不装 Main**、
 * 构造 ViewModel 并发起 `viewModelScope.launch`：修复前该协程落进死调度器永不被执行，
 * 依赖实时等待的用例 5 s 超时 ⇒ `AutofillPickerViewModel` 两个测试类三连偶发红
 * （全量套件是否命中取决于 Gradle 类执行顺序，隔离跑必绿——与条目记录的现象逐点吻合）。
 *
 * ## 判据
 *
 * 装上死调度器后调 [MainDispatcherGuard.tearDown]（收尾**装新**），再以「不装 Main」的真实形态
 * 构造 ViewModel 发起装载：守卫装上的新鲜默认 Main（[UnconfinedTestDispatcher]，eager，等价于
 * 新 JVM 的 ServiceLoader 初始态）必须让该 launch **即时完成**。守卫若回退为「只装不卸」，
 * 本用例立即转红（launch 落进死调度器，entries 恒空）——泄漏在**本机检**当场可见，不依赖
 * 偶发的全量套件顺序（Gradle JUnit4 无 in-JVM 套件级挂载点，探针实测见批次 §309，故以本锁 +
 * 静态判据作为 AC⑤ 的等效载体）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherGuardNormalizationTest {

    @Test
    fun `守卫收尾后 Main 等价于初始 eager 态_死调度器不再被继承`() {
        // ── 模拟 §153 时代最恶劣的遗留态：上一类装 StandardTestDispatcher 且无人推进 ──
        Dispatchers.setMain(StandardTestDispatcher())
        // 守卫收尾：装新（修复前「只装不卸」会把死调度器原样留给下一个类）
        MainDispatcherGuard.tearDown()

        // ── 修复前三连红的真实形态：不装 Main，直接构造 VM 发起实时装载 ──
        val entryId = KdbxUuid.random()
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> = listOf(
                KdbxEntry(
                    id = entryId,
                    fields = mapOf(
                        KdbxConstants.Fields.USER_NAME to
                            ProtectedString("issue-p2-307-user", isProtected = false)
                    )
                )
            )
        }
        val viewModel = MainDispatcherGuard.track(AutofillPickerViewModel(repository))
        viewModel.loadEntries()

        // eager Main ⇒ 装载同步完成，无需任何调度器推进或实时等待；
        // 守卫回退为「只装不卸」时这里恒空（launch 落进死调度器），本用例立即转红
        assertTrue(
            "守卫收尾后 Main 必须等价于初始 eager 态（ISSUE-P2-307：死调度器不得被继承）",
            viewModel.entries.value.isNotEmpty()
        )
        assertEquals(
            "缓存命中路径必须返回装载到的用户名",
            "issue-p2-307-user",
            runBlocking {
                viewModel.resolveCredentials(entryId.toHexString())?.username
            }
        )

        // 收尾装新：本用例自身也不留遗物
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `装新默认态与显式指定的 nextDispatcher 语义一致`() {
        // nextDispatcher 非空时改装该派发器（既有形参契约不回归）
        val explicit = StandardTestDispatcher()
        MainDispatcherGuard.tearDown(explicit)
        val vm = MainDispatcherGuard.track(AutofillPickerViewModel(FakeVaultRepository()))
        vm.loadEntries()
        // 显式指定的 StandardTestDispatcher 无人推进 ⇒ launch 不得执行（保持调用方完全控制权）
        assertTrue(
            "nextDispatcher 契约：显式指定的调度器必须被原样装上（由调用方决定何时推进）",
            vm.entries.value.isEmpty()
        )
        MainDispatcherGuard.tearDown()
    }
}
