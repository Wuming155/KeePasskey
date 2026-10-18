package com.keepasskey.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.data.repository.PasskeyEntryCoordinator
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real EntryEditScreen rendering check for a generated Passkey stored in a synthetic KDBX. */
@RunWith(AndroidJUnit4::class)
class EntryEditPasskeyLockUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "Passkey-UI-Fake-Master".toCharArray()
    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()

    private var file: File? = null
    private var passkeyData: PasskeyData? = null
    private var passkeyEntryId: String? = null
    private val scrollTrace = mutableListOf<String>()

    @Before
    fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    @After
    fun cleanup() {
        runCatching { runBlocking { repository.lockDatabase() } }
        val ownedFile = file
        if (ownedFile != null) {
            runCatching {
                val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
                catalog.saveKnownDatabases(catalog.loadKnownDatabases().filterNot { it.path == ownedFile.absolutePath })
            }
            runCatching { ownedFile.delete() }
            runCatching { File(ownedFile.absolutePath + ".bak").delete() }
            runCatching { ownedFile.parentFile?.delete() }
        }
        passkeyData?.privateKey?.clear()
        password.fill('\u0000')
    }

    @Test
    fun `真实编辑页将Passkey仪式字段渲染为只读卡片且隐藏受保护材料`() {
        val passkey = createStoredPasskey()
        val storedEntry = requireNotNull(
            runBlocking { repository.getKdbxEntry(requireNotNull(passkeyEntryId)) }
        ) { "已落库 Passkey 必须能按 entryId 重读" }

        val lockedKeys = storedEntry.customFields
            .filter { PasskeyData.isPasskeyFieldKey(it.key) }
            .map { it.key }
            .filterNot {
                it == PasskeyData.FIELD_USER_NAME ||
                    it == PasskeyData.LEGACY_FIELD_USER_NAME ||
                    it == PasskeyData.FIELD_USER_DISPLAY_NAME
            }
        assertTrue("合成 Passkey 必须具有多项仪式字段", lockedKeys.size > 5)

        val intent = Intent(context, EntryEditDeviceHostActivity::class.java)
            .putExtra(EntryEditDeviceHostActivity.EXTRA_ENTRY_ID, storedEntry.id.toHexString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<EntryEditDeviceHostActivity>(intent).use {
            val lockedMarker = context.getString(R.string.edit_passkey_field_locked)
            val hiddenValue = context.getString(R.string.edit_passkey_field_value_hidden)
            lockedKeys.forEach { key ->
                val keyVisible = scrollUntilText(key)
                assertTrue(
                    "锁定字段名必须可读: $key（页面=${accessibilityText()}；滚动=${scrollTrace.takeLast(8)}）",
                    keyVisible
                )
                val keyNode = findNodes(key).firstOrNull()
                    ?: error("无障碍树缺少锁定字段名: $key")
                assertFalse("锁定字段名节点不得是可点击编辑控件: $key", keyNode.isClickable)
                assertTrue(
                    "$key 所在页面必须呈现锁定说明（页面=${accessibilityText()}）",
                    waitForText(lockedMarker)
                )
                if (key == PasskeyData.FIELD_PRIVATE_KEY) {
                    assertTrue("私钥卡片必须显示受保护字段隐藏说明", findNodes(hiddenValue).isNotEmpty())
                    assertFalse("私钥 PEM 不得出现在编辑页无障碍文本中", accessibilityText().contains("BEGIN PRIVATE KEY"))
                }
            }
        }

        val persisted = parseFile(requireNotNull(file))
        try {
            val reopened = PasskeyData.fromCustomFields(
                persisted.rootGroup.allEntries().single { entry ->
                    PasskeyData.fromCustomFields(entry.customFields)?.credentialId == passkey.credentialId
                }.customFields
            ) ?: error("编辑页打开后正式 KDBX 必须保留 Passkey")
            assertTrue("credentialId 应保持不变", reopened.credentialId == passkey.credentialId)
            assertTrue("userHandle 应保持不变", reopened.userHandle == passkey.userHandle)
            val originalKey = passkey.usePrivateKeyBytes { it.copyOf() }
            val reopenedKey = reopened.usePrivateKeyBytes { it.copyOf() }
            try {
                assertTrue("私钥字节应保持不变", originalKey.contentEquals(reopenedKey))
            } finally {
                originalKey.fill(0)
                reopenedKey.fill(0)
            }
        } finally {
            persisted.clearSensitiveData()
        }
    }

    private fun createStoredPasskey(): PasskeyData {
        val folder = File(context.cacheDir, "passkey-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val vault = File(folder, "vault.kdbx")
        file = vault
        val initialDatabase = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "Passkey UI Device",
            rootGroup = KdbxGroup(name = "Root")
        )
        val credential = password.clone()
        try {
            vault.outputStream().use { KdbxFile.save(it, initialDatabase, credential, null) }
        } finally {
            credential.fill('\u0000')
            initialDatabase.clearSensitiveData()
        }
        assertTrue(
            "合成 Passkey 库登记失败",
            runBlocking { repository.importExternalDatabase("passkey-ui-${folder.name}", vault.absolutePath) } is KdbxResult.Success
        )
        runBlocking { repository.selectDatabase(vault.absolutePath) }
        assertTrue("合成 Passkey 库必须成为活动库", repository.databaseInfoForPath(vault.absolutePath)?.isActive == true)
        assertTrue(
            "合成 Passkey 库解锁失败",
            runBlocking { repository.unlockActiveDatabase(password.clone(), null, readOnly = false) } is KdbxResult.Success
        )

        val data = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = "passkeys.example",
            userName = "synthetic-user",
            userHandle = "synthetic-user-handle",
            userDisplayName = "Synthetic Passkey"
        )
        passkeyData = data
        val coordinator = PasskeyEntryCoordinator(session, DebugLogBuffer()) { session.save() }
        val result = runBlocking { coordinator.saveNewPasskeyEntry(data, boundPackage = null) }
        assertTrue("生成的测试 Passkey 必须成功落盘: $result", result is KdbxResult.Success)
        passkeyEntryId = (result as KdbxResult.Success).data.id.toHexString()
        return data
    }

    private fun parseFile(vault: File): KdbxDatabase {
        val credential = password.clone()
        return try {
            KdbxFile.load(vault.inputStream(), credential)
        } finally {
            credential.fill('\u0000')
        }
    }

    private fun findNodes(text: String): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == text) nodes += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { collect(it.root) }
        return nodes.distinctBy { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.text}|$bounds"
        }
    }

    private fun waitForText(text: String): Boolean = waitUntil { findNodes(text).isNotEmpty() }

    private fun scrollUntilText(text: String): Boolean {
        scrollTrace.clear()
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (findNodes(text).isNotEmpty()) return true
            // 小步重叠滚动，避免长表单中的短只读字段卡片被一次滑动跨过。
            swipeFormUp()
            scrollTrace += "swipe"
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return findNodes(text).isNotEmpty()
    }

    private fun swipeFormUp() {
        val downTime = android.os.SystemClock.uptimeMillis()
        val startY = 1900f
        val endY = 1780f
        injectPointer(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, startY)
        repeat(SWIPE_STEPS) { index ->
            android.os.SystemClock.sleep(SWIPE_STEP_DELAY_MS)
            val fraction = (index + 1f) / SWIPE_STEPS
            val y = startY + (endY - startY) * fraction
            injectPointer(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 540f, y)
        }
        injectPointer(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 540f, endY)
    }

    private fun injectPointer(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(automation.injectInputEvent(event, false)) { "触屏事件注入失败: action=$action" }
        } finally {
            event.recycle()
        }
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) appendNode(node.getChild(index))
        }
        appendNode(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { appendNode(it.root) }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_DELAY_MS = 40L
    }
}
