package com.keepasskey.app.data.repository

import android.content.ContextWrapper
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 提交后取消真实调用方，必要的活动库选择与列表刷新仍必须执行。 */
class VaultCreationCancellationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cancellationAfterCommitCannotSkipCatalogRefresh() = runBlocking<Unit> {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = folder.root
        }
        val session = DatabaseSession()
        val strings = StringsProvider { _, _ -> "" }
        val refreshed = AtomicBoolean(false)
        var selected: String? = null
        lateinit var creation: Job
        val coordinator = VaultLifecycleCoordinator(context, strings, session,
            VaultDatabaseCatalog(context, strings, session),
            refresh = { yield(); refreshed.set(true) },
            selectDatabase = { id ->
                selected = id
                creation.cancel()
                yield() // 如果必要收尾依赖已取消调用方，此处就会丢失 refresh。
            })
        val pwd = "Fake#CancelledCreation".toCharArray()
        try {
            creation = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                coordinator.createDatabaseWithKeyFile("created", pwd, CreateKeyFileFactor.None,
                    CreateVaultPreset.TWOFISH_AES_KDF)
            }
            creation.start()
            creation.join()
            assertEquals("created.kdbx", selected)
            assertTrue(refreshed.get())
            assertTrue(File(folder.root, "created.kdbx").length() > 0)
            assertEquals(File(folder.root, "created.kdbx"), session.currentFile)
            assertTrue(session.save().isSuccess)
        } finally { session.close(); pwd.fill('0') }
    }
}
