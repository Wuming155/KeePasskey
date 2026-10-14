package com.keepasskey.app.data.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.autofill.DeviceAutofillProbe
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 594 系统 SAF 建库端到端探针：只操作本测试实际收到的两个 URI。 */
@RunWith(AndroidJUnit4::class)
class SafVaultSystemCreationAndroidTest {
    private lateinit var client: SafCreationProbeClient
    private lateinit var targetContext: Context
    private lateinit var probe: DeviceAutofillProbe
    private lateinit var session: DatabaseSession
    private lateinit var oldFile: File
    private lateinit var oldPassword: CharArray
    private lateinit var oldTree: com.keepasskey.database.file.KdbxDatabase
    private lateinit var oldPath: String
    private val resultUris = mutableListOf<Uri>()
    private val passwords = mutableListOf<CharArray>()

    @Before
    fun setUp() = runBlocking {
        client = SafCreationProbeClient()
        targetContext = client.targetContext
        probe = DeviceAutofillProbe().apply { prepare() }
        oldFile = File(targetContext.filesDir, "saf594-system-old-${UUID.randomUUID()}.kdbx")
        oldPassword = fakePassword("old")
        session = DatabaseSession()
        assertTrue("旧会话合成库创建失败", session.create(oldFile, "Existing", oldPassword, useArgon2 = false).isSuccess)
        oldTree = requireNotNull(session.databaseFlow.value)
        oldPath = requireNotNull(session.currentPathIdentifier)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        var failure: Throwable? = null
        resultUris.distinct().forEach { uri ->
            NewVaultDocumentClaims.consume(uri)
            try {
                if (DocumentsContract.isDocumentUri(targetContext, uri)) {
                    DocumentsContract.deleteDocument(targetContext.contentResolver, uri)
                }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
            try {
                val persisted = targetContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                if (persisted != null) {
                    var flags = 0
                    if (persisted.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (persisted.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    if (flags != 0) targetContext.contentResolver.releasePersistableUriPermission(uri, flags)
                }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        if (::session.isInitialized) {
            try {
                session.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        if (::oldPassword.isInitialized) oldPassword.fill('\u0000')
        passwords.forEach { it.fill('\u0000') }
        if (::oldFile.isInitialized) oldFile.delete()
        if (::client.isInitialized) {
            try {
                client.cleanupAndEnd()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Test
    fun createsTwoDistinctSystemDocumentsWithActualNamesAndReopensBoth() = runBlocking {
        val initialDownload = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload"
        )
        val suggestedName = "${UUID.randomUUID()}_saf594.kdbx"
        val firstReceipt = chooseSystemDocument(suggestedName, initialDownload)
        assertEquals("第一次系统创建必须返回 RESULT_OK", Activity.RESULT_OK, firstReceipt.getInt("code"))
        val firstUri = requireNotNull(firstReceipt.getString("uri")).let(Uri::parse)
        resultUris += firstUri
        val firstFlags = firstReceipt.getInt("flags")
        assertTrue("系统回执必须授予读写及可持久化标志", firstFlags and REQUIRED_RESULT_FLAGS == REQUIRED_RESULT_FLAGS)
        assertTrue(NewVaultDocumentClaims.record(targetContext, firstUri))

        assertSame("系统挑选文件期间旧活动数据库对象不得改变", oldTree, session.databaseFlow.value)
        assertEquals("系统挑选文件期间旧活动路径不得改变", oldPath, session.currentPathIdentifier)
        val firstRegistration = createFromSystemResult(firstUri, suggestedName)
        assertEquals(firstUri.toString(), firstRegistration.second)
        assertEquals(queryDisplayName(firstUri), firstRegistration.first)
        assertNotSame("首次 SAF 建库成功后应切换活动会话", oldTree, session.databaseFlow.value)
        assertEquals(firstUri.toString(), session.currentPathIdentifier)
        reopen(firstUri, passwords.last())

        val firstBytes = targetContext.contentResolver.openInputStream(firstUri)?.use { it.readBytes() }
            ?: error("无法读取第一次系统创建的密文")
        try {
            assertTrue("第一次真实 SAF 产物应为非空 KDBX 密文", firstBytes.isNotEmpty())
            val firstHash = sha256(firstBytes)
            val evidenceId = UUID.randomUUID().toString()
            val evidenceDirectory = File(targetContext.filesDir, "saf594-system-evidence-$evidenceId").apply {
                check(mkdir())
            }
            File(evidenceDirectory, "first-document.kdbx").writeBytes(firstBytes)

            val firstSessionTree = session.databaseFlow.value
            val firstSessionPath = session.currentPathIdentifier
            val secondReceipt = chooseSystemDocument(suggestedName, initialDownload)
            assertEquals("第二次系统创建必须返回 RESULT_OK", Activity.RESULT_OK, secondReceipt.getInt("code"))
            val secondUri = requireNotNull(secondReceipt.getString("uri")).let(Uri::parse)
            resultUris += secondUri
            val secondFlags = secondReceipt.getInt("flags")
            assertSame("第二次系统挑选期间第一份活动数据库对象不得改变", firstSessionTree, session.databaseFlow.value)
            assertEquals("第二次系统挑选期间第一份活动路径不得改变", firstSessionPath, session.currentPathIdentifier)
            assertTrue("第二次系统回执必须授予读写及可持久化标志", secondFlags and REQUIRED_RESULT_FLAGS == REQUIRED_RESULT_FLAGS)
            assertNotEquals("系统必须为同名第二次建议创建独立 URI", firstUri, secondUri)
            val secondName = queryDisplayName(secondUri)
            assertNotEquals("系统必须为同名第二次建议创建不同实际名称", firstRegistration.first, secondName)
            assertArrayEquals("第二次系统创建不能改写第一次密文", firstHash, sha256(readDocument(firstUri)))
            assertTrue(NewVaultDocumentClaims.record(targetContext, secondUri))

            val secondRegistration = createFromSystemResult(secondUri, suggestedName)
            assertEquals(secondUri.toString(), secondRegistration.second)
            assertEquals(secondName, secondRegistration.first)
            assertEquals(secondUri.toString(), session.currentPathIdentifier)
            reopen(secondUri, passwords.last())
            assertArrayEquals("第二次建库后第一次密文仍应保持不变", firstHash, sha256(readDocument(firstUri)))

            JSONObject()
                .put("evidenceId", evidenceId)
                .put("interopFile", "first-document.kdbx")
                .put("firstUri", firstUri.toString())
                .put("firstName", firstRegistration.first)
                .put("firstFlags", firstFlags)
                .put("firstSha256", firstHash.hex())
                .put("firstRegistrationPath", firstRegistration.second)
                .put("secondUri", secondUri.toString())
                .put("secondName", secondRegistration.first)
                .put("secondFlags", secondFlags)
                .put("secondSha256", sha256(readDocument(secondUri)).hex())
                .put("secondRegistrationPath", secondRegistration.second)
                .writeTo(File(evidenceDirectory, "receipt.json"))
        } finally {
            firstBytes.fill(0)
        }
    }

    private fun chooseSystemDocument(suggestedName: String, initialUri: Uri): Bundle {
        client.clearResult()
        targetContext.startActivity(Intent().apply {
            setClassName(client.testContext.packageName, SafCreationProbeActivity::class.java.name)
            putExtra("target", targetContext.packageName)
            putExtra("name", suggestedName)
            putExtra("initial", initialUri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

        val nameNode = awaitNode("DocumentsUI 文件名输入框", "android:id/title", EditTextClass)
        check(probe.setText(nameNode, suggestedName)) {
            "无法通过 ACTION_SET_TEXT 设置 DocumentsUI 文件名；窗口快照:\n${probe.snapshot()}"
        }
        val saveNode = awaitNode("DocumentsUI 保存按钮", "android:id/button1", ButtonClass)
        check(saveNode.isEnabled && saveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "无法通过 ACTION_CLICK 点击 DocumentsUI 保存按钮；窗口快照:\n${probe.snapshot()}"
        }
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val receipt = client.result()
            if (receipt.containsKey("code")) return receipt
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("等待系统 ACTION_CREATE_DOCUMENT 回执超时；窗口快照:\n${probe.snapshot()}")
    }

    private fun awaitNode(label: String, resourceId: String, className: String): AccessibilityNodeInfo =
        requireNotNull(probe.awaitActiveNode(label, UI_TIMEOUT_MS) {
            it.packageName?.toString() == DOCUMENTS_UI_PACKAGE &&
                it.viewIdResourceName == resourceId &&
                it.className?.toString() == className &&
                it.isVisibleToUser &&
                (resourceId != SAVE_BUTTON_ID || it.isEnabled)
        }) { "$label 不存在或不唯一；窗口快照:\n${probe.snapshot()}" }

    private suspend fun createFromSystemResult(uri: Uri, requestedName: String): Pair<String, String> {
        val password = fakePassword("new")
        passwords += password
        var registration: Pair<String, String>? = null
        val result = SafVaultCreation.create(
            context = targetContext,
            strings = StringsProvider { id, args -> targetContext.getString(id, *args) },
            databaseSession = session,
            name = requestedName,
            masterPassword = password,
            keyFileData = null,
            preset = CreateVaultPreset.DEFAULT,
            targetUri = uri
        ) { actualName, actualPath ->
            registration = actualName to actualPath
            KdbxResult.Success(Unit)
        }
        assertTrue("生产 SAF 建库应成功: $result", result is KdbxResult.Success)
        return requireNotNull(registration) { "成功建库必须使用系统实际名称和 URI 登记" }
    }

    private fun reopen(uri: Uri, expectedPassword: CharArray) {
        val password = expectedPassword.copyOf()
        try {
            val loaded = targetContext.contentResolver.openInputStream(uri)?.use {
                KdbxFile.load(it, password, null, null)
            } ?: error("无法打开系统创建的 KDBX")
            loaded.clearSensitiveData()
        } finally {
            password.fill('\u0000')
        }
    }

    private fun queryDisplayName(uri: Uri): String =
        targetContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "系统文档查询无结果: $uri" }
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: error("系统文档查询失败: $uri")

    private fun readDocument(uri: Uri): ByteArray =
        targetContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取系统文档: $uri")

    private fun fakePassword(prefix: String): CharArray = "$prefix#594!Kdbx".toCharArray()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun JSONObject.writeTo(file: File) {
        file.writeText(toString(2))
    }

    private companion object {
        const val UI_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 100L
        const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
        const val SAVE_BUTTON_ID = "android:id/button1"
        const val EditTextClass = "android.widget.EditText"
        const val ButtonClass = "android.widget.Button"
        const val REQUIRED_RESULT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}
