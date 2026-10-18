package com.keepasskey.app.sync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.SyncConflictDeviceHostActivity
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.merge.KdbxMetaConflictKeys
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API 36 设备上经生产 SyncCoordinator 验证 ISSUE-P2-659 的云端 Meta 待决与裁决消费链。 */
@RunWith(AndroidJUnit4::class)
class SyncMetaConflictDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "P2-659-Sync-Meta-Device".toCharArray()

    @Test
    fun `TLS独立S3服务上的云端Meta冲突经生产UI逐字段裁决并回读`() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString(ARG_S3_ENDPOINT)
        org.junit.Assume.assumeTrue("Provide runner argument $ARG_S3_ENDPOINT", !endpoint.isNullOrBlank())
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p2-659-s3-$suffix.kdbx")
        val cacheDir = File(context.cacheDir, "p2-659-s3-$suffix")
        val rollbackDir = File(context.filesDir, "p2-659-s3-rollback-$suffix")
        val remotePath = "notes&archive/p2-659-$suffix.kdbx"
        val bucket = "kp-device-validation"
        val coordinatorProvider = s3Provider(endpoint!!, bucket)
        val externalActor = s3Provider(endpoint, bucket)
        val session = DatabaseSession()
        val coordinator = newCoordinator(session, coordinatorProvider, remotePath, cacheDir, rollbackDir)
        var baselineBytes: ByteArray? = null
        var remoteBytes: ByteArray? = null
        var uploadedBytes: ByteArray? = null
        try {
            val createPassword = password.clone()
            try {
                assertTrue(
                    "S3 Meta 合成库创建失败",
                    session.create(vaultFile, "Base Name", createPassword, useArgon2 = false) is KdbxResult.Success
                )
            } finally {
                createPassword.fill('\u0000')
            }
            session.updateDatabaseMeta {
                it.copy(
                    databaseName = "Base Name",
                    databaseNameChanged = null,
                    databaseDescription = "Base description",
                    databaseDescriptionChanged = null,
                    defaultUserName = "base-user",
                    defaultUserNameChanged = null,
                    color = "#112233",
                    settingsChanged = null,
                    customData = mapOf("base-key" to "base-value"),
                    recycleBinEnabled = true
                )
            }
            assertTrue("S3 Meta 共同底版保存失败", session.save() is KdbxResult.Success)
            val initial = coordinator.syncNow()
            assertTrue("生产 S3 Provider 首传失败: $initial", initial is SyncOutcome.UploadedLocal)

            val baselineMeta = externalActor.getMetadata(remotePath).getOrThrow()
            baselineBytes = downloadBytes(externalActor, remotePath)
            val remoteVariant = rewriteMeta(baselineBytes!!) {
                it.copy(
                    databaseName = "Remote Name",
                    databaseNameChanged = null,
                    databaseDescription = "Remote description",
                    databaseDescriptionChanged = null,
                    defaultUserName = "remote-user",
                    defaultUserNameChanged = null,
                    color = "#445566",
                    settingsChanged = null,
                    customData = mapOf("base-key" to "base-value", "remote-key" to "remote-value"),
                    recycleBinEnabled = false
                )
            }
            remoteBytes = remoteVariant
            val externalWrite = externalActor.upload(remotePath, remoteVariant, baselineMeta.etag)
            assertTrue("第二端写入远端 Meta 应成功: ${externalWrite.exceptionOrNull()}", externalWrite.isSuccess)

            session.updateDatabaseMeta {
                it.copy(databaseName = "Local Name", databaseNameChanged = null, color = "#AABBCC", settingsChanged = null)
            }
            assertTrue("S3 Meta 本地冲突值保存失败", session.save() is KdbxResult.Success)
            val beforeDecision = downloadBytes(externalActor, remotePath)

            val outcome = coordinator.syncNow()
            assertTrue("真实 Provider 同步必须将 Meta 异值送入待裁决: $outcome", outcome is SyncOutcome.ConflictNeedsUser)
            assertEquals(
                setOf(KdbxMetaConflictKeys.NAME, KdbxMetaConflictKeys.COLOR),
                coordinator.metaConflictFlow.value.map { it.fieldKey }.toSet()
            )
            assertArrayEquals("UI 裁决前 S3 对象不得变化", beforeDecision, downloadBytes(externalActor, remotePath))

            resolveRemoteMetaInProductionUi(coordinator, selectLocalColor = true)
            assertTrue("UI 提交后待决 Meta 必须清空", coordinator.metaConflictFlow.value.isEmpty())
            uploadedBytes = downloadBytes(externalActor, remotePath)
            val remoteAfterResolution = load(uploadedBytes!!)
            try {
                assertEquals("S3 上传结果须采用 UI 选择的云端库名", "Remote Name", remoteAfterResolution.databaseName)
                assertEquals("云端单侧描述改动须自动合入", "Remote description", remoteAfterResolution.databaseDescription)
                assertEquals("S3 合并须带入单侧云端默认用户名", "remote-user", remoteAfterResolution.defaultUserName)
                assertEquals("未选择云端的颜色字段应保留本地值", "#AABBCC", remoteAfterResolution.color)
                assertEquals(
                    "远端单侧 customData 增量应保留",
                    mapOf("base-key" to "base-value", "remote-key" to "remote-value"),
                    remoteAfterResolution.customData
                )
                assertTrue("身份字段 recycleBinEnabled 必须保留本地值", remoteAfterResolution.recycleBinEnabled)
            } finally {
                remoteAfterResolution.clearSensitiveData()
            }

            val directory = externalActor.listRemoteDirectory("notes&archive", cursor = null, pageSize = 10)
            assertTrue("S3 ListObjectsV2 查询应成功: ${directory.exceptionOrNull()}", directory.isSuccess)
            val listed = directory.getOrThrow().entries.single { it.path == remotePath }
            assertTrue("独立 S3 的 ISO LastModified 必须解析为非零", listed.lastModifiedMillis > 0L)

            baselineBytes?.fill(0)
            remoteBytes?.fill(0)
            beforeDecision.fill(0)
        } finally {
            SyncConflictDeviceHostActivity.suppliedCoordinator = null
            runCatching { coordinatorProvider.delete(remotePath) }
            runCatching { coordinatorProvider.clearCredentials() }
            runCatching { externalActor.clearCredentials() }
            runCatching { session.lock() }
            runCatching { session.close() }
            providerUnusedCleanup(vaultFile, cacheDir, rollbackDir)
            baselineBytes?.fill(0)
            remoteBytes?.fill(0)
            uploadedBytes?.fill(0)
            password.fill('\u0000')
        }
    }

    @Test
    fun `云端库名双侧异值阻断自动提交并按字段裁决同步结果`() = runBlocking {
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p2-659-sync-$suffix.kdbx")
        val cacheDir = File(context.cacheDir, "p2-659-sync-$suffix")
        val rollbackDir = File(context.filesDir, "p2-659-rollback-$suffix")
        val remotePath = "p2-659-sync-$suffix.kdbx"
        val session = DatabaseSession()
        val provider = MemorySyncProvider()
        val coordinator = newCoordinator(session, provider, remotePath, cacheDir, rollbackDir)
        var baseRemote: ByteArray? = null
        var remoteBytes: ByteArray? = null
        var remoteBeforeDecision: ByteArray? = null
        var uploaded: ByteArray? = null

        try {
            val createPassword = password.clone()
            assertTrue(
                "合成库创建失败",
                try {
                    session.create(vaultFile, "Base Name", createPassword, useArgon2 = false) is KdbxResult.Success
                } finally {
                    createPassword.fill('\u0000')
                }
            )
            session.updateDatabaseMeta {
                it.copy(
                    databaseName = "Base Name",
                    databaseNameChanged = null,
                    databaseDescription = "Base description",
                    databaseDescriptionChanged = null,
                    defaultUserName = "base-user",
                    defaultUserNameChanged = null,
                    customData = mapOf("base-key" to "base-value"),
                    recycleBinEnabled = true
                )
            }
            assertTrue("共同底版保存失败", session.save() is KdbxResult.Success)
            val initial = coordinator.syncNow()
            assertTrue("远端共同底版建立失败: $initial", initial is SyncOutcome.UploadedLocal)
            val baseVariant = requireNotNull(provider.bytes(remotePath))
            baseRemote = baseVariant

            session.updateDatabaseMeta {
                it.copy(databaseName = "Local Name", databaseNameChanged = null)
            }
            assertTrue("本地 Meta 修改保存失败", session.save() is KdbxResult.Success)

            val remoteVariant = rewriteMeta(baseVariant) {
                it.copy(
                    databaseName = "Remote Name",
                    databaseNameChanged = null,
                    databaseDescription = "Remote description",
                    databaseDescriptionChanged = null,
                    defaultUserName = "remote-user",
                    defaultUserNameChanged = null,
                    customData = mapOf("base-key" to "base-value", "remote-key" to "remote-value"),
                    recycleBinEnabled = false
                )
            }
            remoteBytes = remoteVariant
            provider.externalWrite(remotePath, remoteVariant)
            remoteBeforeDecision = requireNotNull(provider.bytes(remotePath))

            val conflict = coordinator.syncNow()
            assertTrue("Meta 双侧异值必须进入待裁决: $conflict", conflict is SyncOutcome.ConflictNeedsUser)
            val metaConflicts = coordinator.metaConflictFlow.value
            assertEquals("只有双侧异值的库名进入冲突面", listOf(KdbxMetaConflictKeys.NAME), metaConflicts.map { it.fieldKey })
            assertEquals("Local Name", metaConflicts.single().localValue)
            assertEquals("Remote Name", metaConflicts.single().remoteValue)
            assertArrayEquals("裁决前不得写回远端", remoteBeforeDecision, requireNotNull(provider.bytes(remotePath)))
            assertEquals(
                "裁决前正式库仍保持本地 Meta",
                "Local Name",
                requireNotNull(session.databaseFlow.value).databaseName
            )

            resolveRemoteMetaInProductionUi(coordinator)
            assertTrue("Meta 冲突流应在裁决完成后清空", coordinator.metaConflictFlow.value.isEmpty())

            val uploadedBytes = requireNotNull(provider.bytes(remotePath))
            uploaded = uploadedBytes
            val reopened = load(uploadedBytes)
            try {
                assertEquals("裁决后的库名应取云端", "Remote Name", reopened.databaseName)
                assertEquals("单侧云端描述应自动合入", "Remote description", reopened.databaseDescription)
                assertEquals("单侧云端默认用户名改动应自动合入", "remote-user", reopened.defaultUserName)
                assertEquals("本机身份字段 recycleBinEnabled 不随云端更改", true, reopened.recycleBinEnabled)
                assertEquals("远端单侧 customData 应并入", mapOf("base-key" to "base-value", "remote-key" to "remote-value"), reopened.customData)
            } finally {
                reopened.clearSensitiveData()
            }
            assertEquals("本地正式库也应提交裁决结果", "Remote Name", requireNotNull(session.databaseFlow.value).databaseName)
            assertEquals("本地正式库应保存远端 customData", mapOf("base-key" to "base-value", "remote-key" to "remote-value"), requireNotNull(session.databaseFlow.value).customData)
            if (InstrumentationRegistry.getArguments().getString("p2InteropExport") == "true") {
                exportInteropFile(vaultFile, "p2-659-meta-merged")
            }
        } finally {
            baseRemote?.fill(0)
            remoteBytes?.fill(0)
            remoteBeforeDecision?.fill(0)
            uploaded?.fill(0)
            runCatching { session.lock() }
            runCatching { session.close() }
            runCatching { provider.clear() }
            runCatching { vaultFile.delete() }
            runCatching { File(vaultFile.absolutePath + ".bak").delete() }
            cacheDir.deleteRecursively()
            rollbackDir.deleteRecursively()
            password.fill('\u0000')
        }
    }

    private fun resolveRemoteMetaInProductionUi(
        coordinator: SyncCoordinator,
        selectLocalColor: Boolean = false
    ) {
        val intent = Intent(context, SyncConflictDeviceHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        SyncConflictDeviceHostActivity.suppliedCoordinator = coordinator
        try {
            ActivityScenario.launch<SyncConflictDeviceHostActivity>(intent).use {
                val metaSection = context.getString(R.string.conflict_meta_section_title)
                assertTrue("生产冲突页必须展示库设置卡片", waitForText(metaSection))
                assertTrue("Meta 卡片必须显示两侧库名", waitUntil {
                    val text = accessibilityText()
                    "Local Name" in text && "Remote Name" in text
                })

                clickText("Remote Name")
                if (selectLocalColor) clickText("#AABBCC")
                val mergeButton = context.getString(R.string.conflict_btn_merge_push)
                clickText(mergeButton)
                assertTrue(
                    "合并推送前必须出现后果确认",
                    waitForText(context.getString(R.string.conflict_confirm_merge_title))
                )
                clickNthText(mergeButton, 0)
                assertTrue("真实 UI 裁决必须清空待决 Meta", waitUntil {
                    coordinator.metaConflictFlow.value.isEmpty()
                })
            }
        } finally {
            SyncConflictDeviceHostActivity.suppliedCoordinator = null
        }
    }

    private fun exportInteropFile(source: File, label: String) {
        val name = "$label-${System.nanoTime()}.kdbx"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
            source.inputStream().use { it.copyTo(output) }
        }
        val size = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { it.statSize }
        assertEquals("Meta 合并后的 KDBX 导出长度必须一致", source.length(), size)
    }

    private fun textNodes(label: String): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == label) nodes += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { collect(it.root) }
        return nodes.distinctBy { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.text}|$bounds"
        }.sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
    }

    private fun waitForText(label: String): Boolean = waitUntil { textNodes(label).isNotEmpty() }

    private fun clickText(label: String) {
        val node = waitForNode(label) ?: error("生产冲突 UI 无障碍树未找到 '$label'：${accessibilityText()}")
        clickNode(node)
    }

    private fun clickNthText(label: String, index: Int) {
        val node = waitUntil { textNodes(label).size > index }.let { ready ->
            if (ready) textNodes(label).getOrNull(index) else null
        } ?: error("生产冲突 UI 无障碍树未找到第 ${index + 1} 个 '$label'：${accessibilityText()}")
        clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0) { "UI 目标无有效点击边界" }
        automation.executeShellCommand(
            "input tap ${bounds.exactCenterX().toInt()} ${bounds.exactCenterY().toInt()}"
        ).use { it.close() }
    }

    private fun waitForNode(label: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        waitUntil { textNodes(label).firstOrNull()?.let { found = it; true } ?: false }
        return found
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (predicate()) return true
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return predicate()
    }

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) appendNode(node.getChild(index))
        }
        appendNode(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { appendNode(it.root) }
    }

    private fun newCoordinator(
        session: DatabaseSession,
        provider: SyncProvider,
        remotePath: String,
        cacheDir: File,
        rollbackDir: File
    ): SyncCoordinator {
        val log = DebugLogBuffer()
        val extended = ExtendedSettingsStore(context).apply {
            publish(ExtendedSettings(conflictResolution = ConflictResolution.PROMPT_USER))
        }
        val preferences = SyncPreferences(log, extended)
        val credentials = SyncCredentialsStore(context, null, log)
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val state = SyncSessionState().apply {
            testRemotePath = remotePath
            testSyncProvider = provider
        }
        val strings = StringsProvider { id, args -> context.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, log)
        val conflicts = SyncConflictController(session, codec, strings, state, log)
        val changes = SyncContentChangeDetector(codec, state)
        val cycle = SyncCycleRunner(
            context = context,
            databaseSession = session,
            session = state,
            providerResolver = resolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = strings,
            rollbackStateDir = rollbackDir
        ).apply { syncCacheFactory = { SyncCache(cacheDir) } }

        return SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = log,
            strings = strings,
            extendedSettingsStore = extended,
            syncSession = state,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )
    }

    private fun rewriteMeta(bytes: ByteArray, transform: (KdbxDatabase) -> KdbxDatabase): ByteArray {
        val loaded = load(bytes)
        val changed = transform(loaded)
        val credential = password.clone()
        return try {
            val output = ByteArrayOutputStream()
            KdbxFile.save(output, changed, credential, null)
            output.toByteArray()
        } finally {
            credential.fill('\u0000')
            changed.clearSensitiveData()
        }
    }

    private fun load(bytes: ByteArray): KdbxDatabase {
        val credential = password.clone()
        return try {
            KdbxFile.load(ByteArrayInputStream(bytes), credential)
        } finally {
            credential.fill('\u0000')
        }
    }

    private fun s3Provider(endpoint: String, bucket: String): S3SyncProvider = S3SyncProvider(
        endpoint = endpoint,
        bucketName = bucket,
        region = REGION,
        accessKeyId = ACCESS_KEY.toCharArray(),
        secretAccessKey = SECRET_KEY.toCharArray(),
        usePathStyle = true
    )

    private suspend fun downloadBytes(provider: SyncProvider, remotePath: String): ByteArray {
        val output = ByteArrayOutputStream()
        val result = provider.download(remotePath, output)
        assertTrue("远端下载失败: ${result.exceptionOrNull()}", result.isSuccess)
        return output.toByteArray()
    }

    private fun providerUnusedCleanup(vaultFile: File, cacheDir: File, rollbackDir: File) {
        runCatching { vaultFile.delete() }
        runCatching { File(vaultFile.absolutePath + ".bak").delete() }
        cacheDir.deleteRecursively()
        rollbackDir.deleteRecursively()
    }

    private class MemorySyncProvider : SyncProvider {
        private data class ObjectValue(val bytes: ByteArray, val etag: String)

        private val remote = mutableMapOf<String, ObjectValue>()
        private var sequence = 0

        fun bytes(path: String): ByteArray? = remote[path]?.bytes?.clone()

        fun externalWrite(path: String, bytes: ByteArray) {
            remote[path] = ObjectValue(bytes.clone(), "external-${++sequence}")
        }

        fun clear() {
            remote.values.forEach { it.bytes.fill(0) }
            remote.clear()
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val objectValue = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("synthetic remote object missing"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    etag = objectValue.etag,
                    contentLength = objectValue.bytes.size.toLong(),
                    lastModifiedMillis = System.currentTimeMillis()
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val objectValue = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("synthetic remote object missing"))
            sink.write(objectValue.bytes)
            return Result.success(Unit)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            val current = remote[remotePath]
            if (expectedEtag != null && current?.etag != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = current?.etag.orEmpty(),
                        localExpectedEtag = expectedEtag,
                        message = "synthetic remote ETag changed"
                    )
                )
            }
            current?.bytes?.fill(0)
            val nextEtag = "device-${++sequence}"
            remote[remotePath] = ObjectValue(data.clone(), nextEtag)
            return Result.success(nextEtag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)?.bytes?.fill(0)
            return Result.success(Unit)
        }
    }

    private companion object {
        const val ARG_S3_ENDPOINT = "s3Endpoint"
        const val BUCKET = "kp-device-validation"
        const val REGION = "us-east-1"
        const val ACCESS_KEY = "DEVICEACCESSKEY"
        const val SECRET_KEY = "devicesecretkey"
        const val UI_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_CLICK_ANCESTORS = 8
    }
}
