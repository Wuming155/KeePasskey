package com.keepasskey.app.autofill

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.passkey.SaveRequestLedger
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ENG-20261028-21`：锁定态保存的**真实 UI 解锁落库**与**真实进程重建**验收。
 *
 * ## 为什么必须由 UI 输入主密码
 * 落地页的生产前提是「库锁定」。用 `repository.unlockActiveDatabase()` 预先解锁再去点句柄，
 * 会把「用户在受保护窗口内输入主密码 → 解锁 → 落库」这段真实链路整段跳过，得到的绿不能代表
 * 生产可用。故本类的**每一段落库都用 a11y 在解锁页输入合成主密码并点击解锁按钮**。
 *
 * ## 覆盖
 * - 常规运行（不传 phase 参数）——四类终态里的三类，全部经真实落地页：
 *   1. **成功**：锁定态入口 → 解锁页 → 输入主密码 → 真实落库 → 载体单次消费；
 *   2. **取消**：解锁页按返回 → 载体丢弃 + 台账登记消费 + 库文件不变（未落库）；
 *   3. **超时**：把封存记录的时间戳移出 TTL → 生产过期判据 fail-closed（不交付口令、不落库）。
 * - `p2_603_phase=prepare` / `p2_603_phase=consume`——**第四类终态：进程重建**。
 *   prepare 段封存载荷并把系统句柄交给独立 holder 进程；宿主以 `am kill` 送 SIGKILL
 *   （**不 force-stop**，那会取消该包的 `PendingIntent`）后运行 consume 段：新进程
 *   （PID 必须不同）在 UI 解锁后仍能从封存记录恢复并落库——「不丢用户新凭据」的验收点。
 *   A 令牌在 prepare 段已登记消费，新进程必须拒绝。
 *
 * ## 分工
 * 句柄内容边界与令牌解析由 [LockedSaveRequestPendingIntentDeviceTest] 覆盖；本类不重复。
 */
@RunWith(AndroidJUnit4::class)
class LockedSaveRequestProcessRestartDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testContext: Context = instrumentation.context
    private val probe = DeviceAutofillProbe()
    private val arrivals = CopyOnWriteArrayList<LockedArrival>()

    /** 已销毁的落地页令牌：取消分支据此停止继续发送返回键。 */
    private val destroyedTokens = CopyOnWriteArrayList<String>()

    /** 与被测应用 Hilt 单例共享同一 prefs 文件与 Keystore 别名，故可观察其终态。 */
    private val store = LockedSavePayloadStore(context, KeystoreManager(context))
    private val ledger = SaveRequestLedger(context)

    @After
    fun cleanup() {
        arrivals.clear()
        destroyedTokens.clear()
        // prepare 段封存的载荷必须跨进程存活至 consume 段：只有非 prepare 运行才清载体。
        // （在 prepare 成功完成时，材料保留给新进程；prepare 失败由其内部 finally 显式清理；
        // consume 无论成功或失败均由其 finally 显式清理，其余常规运行在 @After 统一清载体。）
        if (phaseArgument() != PHASE_PREPARE) store.discardAll()
    }

    @Test
    fun `锁定态保存在UI解锁与真实进程重建后完成落库`() = runBlocking {
        when (val phase = phaseArgument()) {
            PHASE_PREPARE -> prepareRestartPhase()
            PHASE_CONSUME -> consumeRestartPhase()
            null -> uiTerminalMatrix()
            else -> error("未知的两阶段参数 $ARG_PHASE=$phase；只接受 prepare / consume 或不传")
        }
    }

    // ================================================================ 一、常规运行：三类终态

    private suspend fun uiTerminalMatrix() {
        store.discardAll()
        withLockedSyntheticVault { repository, vaultFile ->
            val callbacks = recordCallbacks()
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(callbacks)
            try {
                probe.requireAutomation()
                assertUiSuccess(repository)
                // 成功分支真实新增了条目：先清掉再跑「不得写库」的两条负对照，
                // 既让合成库回到播种形态，也让库文件字节比较有可解释的基线。
                pruneSavedEntries(repository, setOf(SUCCESS_USER))
                assertUiCancel(vaultFile)
                assertUiTimeout(vaultFile)
            } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
                // 本轮结束即释放 holder 里的单句柄（下一轮/下一段重新投递）。
                holderCall(LockedSaveRequestHolderProvider.RELEASE)
            }
        }
    }

    /** 终态一：锁定态入口 → 解锁页 → 输入主密码 → 真实落库。 */
    private suspend fun assertUiSuccess(repository: VaultRepository) {
        val token = UUID.randomUUID().toString()
        assertTrue("成功分支载荷必须封存成功", seal(token, SUCCESS_USER, SUCCESS_PASSWORD))
        assertSealedPayloadAtRest(SUCCESS_USER, SUCCESS_PASSWORD)
        val arrivalFloor = arrivals.size
        sendViaForegroundHolder(create(token), ONE_REQUEST)
        awaitArrivals(arrivalFloor + 1)
        assertEquals(token, arrivals.last().token)

        unlockThroughUi("成功分支")

        assertNotNull(
            "UI 解锁后必须真实落库（口令不得因生命周期改造而丢失）",
            awaitSavedEntry(repository, SUCCESS_USER, SUCCESS_PASSWORD)
        )
        awaitTrue("取回即单次消费，记录必须消失") { !store.hasPayload(token) }
        awaitTrue("落盘成功后必须登记令牌已消费") { ledger.isConsumed(token) }
        println("ENG21_UI_SUCCESS token=$token payloads=${store.pendingCount()}")
    }

    /** 终态二：解锁页按返回 ⇒ 载体丢弃、台账登记消费、库文件字节不变（未落库）。 */
    private suspend fun assertUiCancel(vaultFile: File) {
        lockVault()
        val token = UUID.randomUUID().toString()
        assertTrue("取消分支载荷必须封存成功", seal(token, CANCEL_USER, CANCEL_PASSWORD))
        val digestBefore = AutofillTestVaultSeeder.sha256(vaultFile)
        val arrivalFloor = arrivals.size
        sendViaForegroundHolder(create(token), ONE_REQUEST)
        awaitArrivals(arrivalFloor + 1)
        assertEquals(token, arrivals.last().token)

        awaitUnlockField("取消分支")
        // 返回键可能先被 IME 消费（输入框刚写入过文本），故按到载体真的被丢弃为止；
        // 一旦落地页已销毁就停止按键，避免把返回键继续送给系统桌面。
        var presses = 0
        while (presses < MAX_BACK_PRESSES && store.hasPayload(token) && token !in destroyedTokens) {
            probe.shell("input keyevent 4")
            presses++
            SystemClock.sleep(UI_SETTLE_MS)
        }

        awaitTrue("取消后载体必须丢弃（按键次数=$presses）") { !store.hasPayload(token) }
        awaitTrue("取消也是终态，必须登记令牌已消费") { ledger.isConsumed(token) }
        assertEquals(
            "取消分支不得写库（库文件字节必须不变）",
            digestBefore,
            AutofillTestVaultSeeder.sha256(vaultFile)
        )
        println("ENG21_UI_CANCEL token=$token presses=$presses payloads=${store.pendingCount()}")
    }

    /** 终态三：封存记录时间戳移出 TTL ⇒ 生产过期判据 fail-closed（不交付口令、不落库）。 */
    private suspend fun assertUiTimeout(vaultFile: File) {
        lockVault()
        val token = UUID.randomUUID().toString()
        assertTrue("超时分支载荷必须封存成功", seal(token, EXPIRED_USER, EXPIRED_PASSWORD))
        assertEquals("超时分支应有且仅有这一条在封记录", 1, store.pendingCount())
        ageSealedPayloadsBeyondTtl()
        // 故意**不**在这里调用任何 store 读接口：过期清理必须由落地页自己的判据完成，
        // 测试先把记录删掉就只能证明「没有记录时不落库」，证明不了「过期即失效」。
        val digestBefore = AutofillTestVaultSeeder.sha256(vaultFile)
        val arrivalFloor = arrivals.size
        // 送入前的记录数必须是 1（否则「清零」可能只是从未写入过，而非过期被清理）
        assertEquals("送入前必须恰有一条在封记录", 1, rawPayloadRecordKeys())
        sendViaForegroundHolder(create(token), ONE_REQUEST)
        awaitArrivals(arrivalFloor + 1)
        assertEquals(token, arrivals.last().token)

        awaitTrue("过期记录必须由落地页清理", timeoutMs = REFUSE_WAIT_MS) {
            rawPayloadRecordKeys() == 0
        }
        assertFalse("超时请求不得留下可解封记录", store.hasPayload(token))
        awaitTrue("超时请求必须登记令牌已消费") { ledger.isConsumed(token) }
        assertEquals(
            "超时分支不得写库（库文件字节必须不变）",
            digestBefore,
            AutofillTestVaultSeeder.sha256(vaultFile)
        )
        println("ENG21_UI_TIMEOUT token=$token payloads=${store.pendingCount()}")
    }

    // ================================================================ 二、两阶段：真实进程重建

    /** 第一阶段：真实 Keystore 封存载荷 + 系统 token 交给独立 holder；磁盘只落令牌与 pid。 */
    private fun prepareRestartPhase() {
        var prepareSucceeded = false
        val tokenA = UUID.randomUUID().toString()
        val tokenB = UUID.randomUUID().toString()
        try {
            assertTrue("A 载荷必须封存成功", seal(tokenA, PROCESS_USER_A, PROCESS_PASSWORD_A))
            assertTrue("B 载荷必须封存成功", seal(tokenB, PROCESS_USER_B, PROCESS_PASSWORD_B))
            val pendingA = create(tokenA)
            val pendingB = create(tokenB)
            assertFalse("A/B 必须是不同的系统 PendingIntent", pendingA == pendingB)
            val held = holderCall(LockedSaveRequestHolderProvider.HOLD, Bundle().apply {
                putParcelable(LockedSaveRequestHolderProvider.EXTRA_A, pendingA)
                putParcelable(LockedSaveRequestHolderProvider.EXTRA_B, pendingB)
            })
            assertTrue("独立 test APK 进程必须持有 A/B 系统 token", held.getBoolean("ready"))
            // A 在上一进程登记为已消费：新进程必须拒绝（台账跨进程可判）
            ledger.markConsumed(tokenA)
            assertTrue(
                "prepare 阶段令牌状态必须同步写入 app 私有偏好",
                restartPrefs().edit()
                    .putString(KEY_TOKEN_A, tokenA)
                    .putString(KEY_TOKEN_B, tokenB)
                    .putInt(KEY_PREPARE_PID, android.os.Process.myPid())
                    .commit()
            )
            // SaveRequestLedger.markConsumed 走 apply()（异步刷盘）。宿主随后对进程发 SIGKILL，
            // 未刷盘的记录会连同进程一起丢失，consume 段就会把 A 当成「未消费」而误判。
            // 对**同一** SharedPreferences 实例再 commit 一次，会等待此前排队的 apply 落盘。
            assertTrue(
                "台账记录必须真正落盘（否则 SIGKILL 会丢掉 A 的消费标记）",
                context.getSharedPreferences(SaveRequestLedger.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(LEDGER_FLUSH_KEY, System.currentTimeMillis())
                    .commit()
            )
            assertTrue("A 的消费标记必须已可跨进程读取", SaveRequestLedger(context).isConsumed(tokenA))
            prepareSucceeded = true
            // 不取消 token：宿主等待本次 app 进程被杀后启动新 instrumentation。
            // 不 force-stop / uninstall 包——系统会在这些操作时主动取消该包的 PendingIntent。
            val marker = "ENG21_RESTART_PREPARE pid=${android.os.Process.myPid()} payloads=${store.pendingCount()}"
            println(marker)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$marker\n") })
        } finally {
            if (!prepareSucceeded) {
                // prepare 失败路径必须显式清理，避免残留未完成的测试载荷与偏好
                store.discard(tokenA)
                store.discard(tokenB)
                restartPrefs().edit().clear().commit()
            }
        }
    }

    /** 第二阶段：新进程用 UI 解锁，旧句柄的封存载荷必须仍能恢复并落库。 */
    private suspend fun consumeRestartPhase() {
        val prefs = restartPrefs()
        val tokenA = requireNotNull(prefs.getString(KEY_TOKEN_A, null)) { "prepare 段未落下 A 令牌" }
        val tokenB = requireNotNull(prefs.getString(KEY_TOKEN_B, null)) { "prepare 段未落下 B 令牌" }
        val preparePid = prefs.getInt(KEY_PREPARE_PID, -1)
        val consumePid = android.os.Process.myPid()
        assertNotEquals("consume 阶段必须运行于新进程", preparePid, consumePid)
        println(
            "ENG21_RESTART_CONSUME preparePid=$preparePid consumePid=$consumePid " +
                "payloads=${store.pendingCount()}"
        )
        assertTrue("封存载荷必须跨进程存活", store.hasPayload(tokenB))

        try {
            withLockedSyntheticVault { repository, _ ->
                val callbacks = recordCallbacks()
                val application = context.applicationContext as Application
                application.registerActivityLifecycleCallbacks(callbacks)
                try {
                    probe.requireAutomation()
                    sendViaForegroundHolder("B")
                    awaitArrivals(1)
                    assertEquals(tokenB, arrivals[0].token)

                    unlockThroughUi("进程重建-恢复")

                    assertNotNull(
                        "新进程必须用封存载荷完成落库（不得把丢失用户新凭据当作成功）",
                        awaitSavedEntry(repository, PROCESS_USER_B, PROCESS_PASSWORD_B)
                    )
                    awaitTrue("落盘后记录必须消失") { !store.hasPayload(tokenB) }
                    awaitTrue("落盘成功后必须登记令牌已消费") { ledger.isConsumed(tokenB) }
                    val marker = "ENG21_RESTART_SAVED pid=${android.os.Process.myPid()} token=$tokenB payloads=${store.pendingCount()}"
                    println(marker)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$marker\n") })

                    sendViaForegroundHolder("A")
                    awaitArrivals(2)
                    assertEquals(tokenA, arrivals[1].token)
                    awaitTrue("已消费令牌不得残留可解封记录") { !store.hasPayload(tokenA) }
                    awaitTrue("上一进程已消费的 A 在新进程仍须视为已消费") { ledger.isConsumed(tokenA) }

                    // 本段真实落库了 B：清掉后再收尾，避免把合成库留在非播种形态
                    pruneSavedEntries(repository, setOf(PROCESS_USER_B))
                } finally {
                    application.unregisterActivityLifecycleCallbacks(callbacks)
                    holderCall(LockedSaveRequestHolderProvider.RELEASE)
                }
            }
        } finally {
            // 显式清理在 consume/finally 失败与成功路径：
            // 无论正常完成或断言/超时异常失败，均显式清理两个令牌在封存载体中的记录，并清空偏好标记
            store.discard(tokenA)
            store.discard(tokenB)
            store.discardAll()
            prefs.edit().remove(KEY_TOKEN_A).remove(KEY_TOKEN_B).remove(KEY_PREPARE_PID).commit()
        }
    }

    // ================================================================ 三、真实 UI 解锁

    /** 等待解锁页的主密码框（登录页与落地页同一个 `UnlockScreen`）。 */
    private fun awaitUnlockField(stage: String): AccessibilityNodeInfo {
        val field = probe.awaitActiveNode("$stage 解锁页主密码框", ACTIVITY_WAIT_MS) { node ->
            AutofillNodeIdentity.appNode(node, context.packageName) && node.isEditable && node.isPassword
        }
        assertNotNull("未在锁定态保存落地页找到主密码框（stage=$stage）:\n${probe.snapshot()}", field)
        return field!!
    }

    /**
     * 在**落地页的解锁窗口**内输入合成主密码并点击解锁按钮。
     *
     * 只走真实 UI：不调用 `repository.unlockActiveDatabase()`，否则「用户输入主密码」这段
     * 生产链路会被整段跳过。
     */
    private fun unlockThroughUi(stage: String) {
        val field = awaitUnlockField(stage)
        val typed = probe.setText(field, AutofillSeedContract.MASTER_PASSWORD_TEXT)
        if (!typed) {
            // 退化路径：真实触摸聚焦 + shell 注入文本（AC3 用例同款退路）
            probe.tapNode(field)
            SystemClock.sleep(UI_SETTLE_MS)
            probe.shell("input text ${AutofillSeedContract.MASTER_PASSWORD_TEXT}")
        }
        SystemClock.sleep(UI_SETTLE_MS)

        val labels = unlockButtonLabels()
        val button = probe.awaitActiveNode("$stage 解锁按钮", ACTIVITY_WAIT_MS) { node ->
            labels.any { label -> AutofillNodeIdentity.appButton(node, context.packageName, label) }
        }
        assertNotNull("未找到解锁按钮（候选文案=$labels，stage=$stage）:\n${probe.snapshot()}", button)
        assertTrue("解锁按钮点击必须发出（stage=$stage）", probe.click(button!!))
    }

    /**
     * 解锁按钮在**两种随包语言**下的文案。
     *
     * 应用内语言可被用户切换（`values` / `values-en` 两套资源），只取 targetContext 当前配置
     * 会在另一种语言下定位失败——这是定位口径问题，不是产品行为差异。两套都作为候选，
     * 由 `AutofillNodeIdentity.appButton` 的「角色 + 精确文字」双重匹配各自校验。
     */
    private fun unlockButtonLabels(): List<String> = listOf(
        java.util.Locale.SIMPLIFIED_CHINESE,
        java.util.Locale.ENGLISH
    ).map { locale ->
        val config = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(locale) }
        context.createConfigurationContext(config).getString(R.string.unlock_btn_unlock)
    }.distinct()

    // ================================================================ 四、状态与断言工具

    private fun phaseArgument(): String? {
        val args = InstrumentationRegistry.getArguments()
        return args.getString(ARG_PHASE_ENG21)
            ?: args.getString(ARG_PHASE)
            ?: args.getString(ARG_PHASE_GENERIC)
    }

    private suspend fun lockVault() {
        graph().databaseSession().lock()
    }

    private fun seal(token: String, username: String, password: String): Boolean {
        val chars = password.toCharArray()
        return try {
            store.seal(token, username, chars)
        } finally {
            chars.fill('0')
        }
    }

    /** 私有目录只应有密文 / IV / 时间戳；封印密钥必须真实落在 AndroidKeyStore 且不可导出。 */
    private fun assertSealedPayloadAtRest(username: String, password: String) {
        val values = context.getSharedPreferences(LockedSavePayloadStore.PREFS_NAME, Context.MODE_PRIVATE)
            .all.values.joinToString("\n")
        assertTrue("必须真的写出了封存记录", values.isNotEmpty())
        assertFalse("私有偏好不得出现口令明文", values.contains(password))
        assertFalse("私有偏好不得出现用户名明文", values.contains(username))

        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "封印密钥必须真实落在 AndroidKeyStore",
            keyStore.containsAlias(LockedSavePayloadStore.PAYLOAD_KEY_ALIAS)
        )
        val sealKey = (keyStore.getEntry(LockedSavePayloadStore.PAYLOAD_KEY_ALIAS, null)
            as java.security.KeyStore.SecretKeyEntry).secretKey
        assertNull("AndroidKeyStore 密钥不可导出（getEncoded 必须为 null）", sealKey.encoded)
    }

    /**
     * 把封存记录的时间戳整体移出 TTL，触发**生产**的过期判据。
     *
     * 只改记录自身的落盘时间戳，不注入测试钩子、不改 TTL 常量、也不等 10 分钟；此刻载体里
     * 只有本分支写入的一条记录（前序分支均已消费/丢弃），故按「所有长整型值」整体前移即可。
     */
    private fun ageSealedPayloadsBeyondTtl() {
        val prefs = context.getSharedPreferences(LockedSavePayloadStore.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var aged = 0
        prefs.all.forEach { (key, value) ->
            if (value is Long) {
                editor.putLong(key, value - LockedSavePayloadStore.TTL_MS - 1_000L)
                aged++
            }
        }
        assertTrue("必须真的改写了封存记录的时间戳", aged > 0 && editor.commit())
    }

    private fun create(token: String): PendingIntent = LockedSaveRequestPendingIntent.create(
        context = context,
        requestCode = REQUEST_CODE,
        requestToken = token,
        callingPackage = "com.synthetic.client",
        webDomain = "example.test"
    )

    /**
     * 直接数私有偏好里的**封存记录**条数（`_at` 时间戳键），不经 store 的读口。
     *
     * 超时分支要证明的是「落地页的过期判据把记录清掉」，因此观测点不能是同样会触发
     * `pruneExpired` 的 [LockedSavePayloadStore] API——那会把清理算到测试头上。
     */
    private fun rawPayloadRecordKeys(): Int =
        context.getSharedPreferences(LockedSavePayloadStore.PREFS_NAME, Context.MODE_PRIVATE)
            .all
            .count { (key, value) -> value is Long && key.endsWith(LockedSavePayloadStore.TIME_SUFFIX) }

    private fun recordCallbacks(): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is AutofillLockedSaveActivity) return
                arrivals += LockedArrival(
                    data = activity.intent.data?.toString().orEmpty(),
                    token = activity.intent
                        .getStringExtra(LockedSaveRequestPendingIntent.EXTRA_REQUEST_TOKEN)
                        .orEmpty()
                )
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (activity !is AutofillLockedSaveActivity) return
                val token = activity.intent
                    .getStringExtra(LockedSaveRequestPendingIntent.EXTRA_REQUEST_TOKEN)
                    .orEmpty()
                if (token.isNotEmpty()) destroyedTokens += token
            }
        }

    private fun graph(): AutofillDeviceGraph =
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)

    /**
     * 合成活动库在**锁定**态下运行用例（锁定态保存的唯一合法前提），结束后恢复选择并删除合成库。
     * 不做任何预解锁——解锁只由 [unlockThroughUi] 在落地页内完成。
     */
    private suspend fun <T> withLockedSyntheticVault(block: suspend (VaultRepository, File) -> T): T {
        val vault = AutofillTestVaultSeeder.seed(context, context.packageName)
        val selection = AutofillVaultSelection(context)
        val snapshot = selection.select(vault.name)
        val graph = graph()
        val repository = graph.vaultRepository()
        try {
            graph.databaseSession().lock()
            assertTrue("合成活动库必须处于锁定态（不得预解锁冒充 UI 解锁）", repository.isLocked())
            return block(repository, vault)
        } finally {
            graph.databaseSession().lock()
            selection.restore(snapshot)
            AutofillTestVaultSeeder.deleteOwned(vault, AutofillSeedContract.MASTER_PASSWORD_TEXT)
        }
    }

    /**
     * 清掉本用例真实落库产生的条目，使合成库回到播种时的**单条目**形态。
     *
     * 播种器的归属判定（[AutofillTestVaultSeeder.deleteOwned]）以「能用测试主密码解开且恰好
     * 一条条目」认定产物归属；落库成功会新增条目，不清理就会让收尾拒绝删除合成库、把文件
     * 残留在 `filesDir` 里干扰后续用例。这里只删**本用例写入的用户名**，不碰播种条目。
     * 必须在会话已解锁时调用（本用例在 UI 解锁后、锁库前执行）。
     */
    private suspend fun pruneSavedEntries(repository: VaultRepository, usernames: Set<String>) {
        val doomed = repository.getUsableKdbxEntries()
            .filter { it.userName in usernames }
            .map { it.id.toHexString() }
        if (doomed.isEmpty()) return
        doomed.forEach { id ->
            val deleted = repository.deleteEntry(id)
            assertTrue("清理合成条目失败（$id）：$deleted", deleted is KdbxResult.Success<*>)
        }
        val emptied = repository.emptyRecycleBin()
        assertTrue("清空回收站失败（合成库需回到单条目形态）：$emptied", emptied is KdbxResult.Success<*>)
    }

    private fun restartPrefs() = context.getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE)

    private fun holderCall(method: String, extras: Bundle = Bundle()): Bundle = requireNotNull(
        testContext.contentResolver.call(
            Uri.parse("content://${testContext.packageName}.p2_603_holder"),
            method,
            null,
            extras
        )
    ) { "独立 PendingIntent holder 未返回回执" }

    /**
     * 通过独立 holder 进程 + 前台测试 Activity 发送系统句柄。
     *
     * 不在 instrumented 进程内直接 `PendingIntent.send()`：instrumentation 进程没有可见窗口，
     * 启动会在 Android 的后台启动限制下失败（这也是既有 `ISSUE-P2-603` 用例采用本通道的原因）。
     */
    private fun sendViaForegroundHolder(pending: PendingIntent, request: String) {
        val held = holderCall(LockedSaveRequestHolderProvider.HOLD_ONE, Bundle().apply {
            putParcelable(LockedSaveRequestHolderProvider.EXTRA_ONE, pending)
        })
        assertTrue("holder 必须先接住单句柄", held.getBoolean("ready"))
        sendViaForegroundHolder(request)
    }

    /** 由 test APK 的可见 Activity 发送 holder 进程已保留的系统句柄。 */
    private fun sendViaForegroundHolder(request: String) {
        holderCall(LockedSaveRequestHolderProvider.CLEAR_RESULT)
        val component = "${testContext.packageName}/com.keepasskey.app.autofill.LockedSaveRequestHolderActivity"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -n $component --es ${LockedSaveRequestHolderActivity.EXTRA_REQUEST} $request"
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        val expected = "$request:OK"
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var result = holderCall(LockedSaveRequestHolderProvider.READ_RESULT).getString("result")
        while (SystemClock.uptimeMillis() < deadline && result != expected) {
            SystemClock.sleep(POLL_MS)
            result = holderCall(LockedSaveRequestHolderProvider.READ_RESULT).getString("result")
        }
        assertEquals("前台 test Activity 应从独立 holder 取得并发送 $request token", expected, result)
    }

    private fun awaitArrivals(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && arrivals.size < expected) SystemClock.sleep(POLL_MS)
        assertEquals("系统必须实际拉起保存落地页", expected, arrivals.size)
    }

    private fun awaitTrue(description: String, timeoutMs: Long = WAIT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && !condition()) SystemClock.sleep(POLL_MS)
        assertTrue(description, condition())
    }

    private suspend fun awaitSavedEntry(
        repository: VaultRepository,
        username: String,
        password: String,
        timeoutMs: Long = WAIT_MS
    ): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val hit = repository.getUsableKdbxEntries().firstOrNull { entry ->
                entry.userName == username && entry.password?.readStringForDisplay() == password
            }
            if (hit != null) return hit.id.toHexString()
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private data class LockedArrival(val data: String, val token: String)

    private companion object {
        const val REQUEST_CODE = 2411
        const val RESTART_PREFS = "p2_603_process_restart"
        const val KEY_TOKEN_A = "token_a"
        const val KEY_TOKEN_B = "token_b"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val ARG_PHASE = "p2_603_phase"
        const val ARG_PHASE_ENG21 = "eng21_phase"
        const val ARG_PHASE_GENERIC = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_CONSUME = "consume"

        /** holder 的单句柄槽请求名（A/B 槽专供进程重建场景）。 */
        const val ONE_REQUEST = "P"

        /**
         * 台账刷盘屏障键。`SaveRequestLedger.markConsumed` 用 `apply()`（异步刷盘），宿主随后
         * SIGKILL 会丢掉未落盘的记录；对同一 prefs 再 `commit()` 一次可等到此前排队的写入完成。
         * 键名只在本用例内使用，不进入产品语义。
         */
        const val LEDGER_FLUSH_KEY = "eng21_ledger_flush_at"

        /** 合成用例口令全部为虚构值（敏感纪律：不得使用任何真实凭据）。 */
        const val SUCCESS_USER = "ui-success-user"
        const val SUCCESS_PASSWORD = "Ui-Success-Password-1"
        const val CANCEL_USER = "ui-cancel-user"
        const val CANCEL_PASSWORD = "Ui-Cancel-Password-1"
        const val EXPIRED_USER = "ui-expired-user"
        const val EXPIRED_PASSWORD = "Ui-Expired-Password-1"
        const val PROCESS_USER_A = "process-user-a"
        const val PROCESS_PASSWORD_A = "Process-Password-A"
        const val PROCESS_USER_B = "process-user-b"
        const val PROCESS_PASSWORD_B = "Process-Password-B"

        /** 系统 UI（解锁页 → 键盘/动画）落定余量 */
        const val UI_SETTLE_MS = 600L

        /** 取消分支最多按几次返回键（首次可能被 IME 消费）。 */
        const val MAX_BACK_PRESSES = 3
        const val ACTIVITY_WAIT_MS = 15_000L
        const val WAIT_MS = 10_000L

        /** 负对照（不得落库 / 过期清理）的短观察窗口 */
        const val REFUSE_WAIT_MS = 3_000L
        const val POLL_MS = 50L
    }
}
