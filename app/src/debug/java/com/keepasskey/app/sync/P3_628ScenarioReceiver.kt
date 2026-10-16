package com.keepasskey.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only bridge for real app-process P3-628 lifecycle acceptance; never merged into release. */
class P3_628ScenarioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        File(context.filesDir, RECEIVED_MARKER).writeText(intent.action.orEmpty())
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val services = EntryPointAccessors.fromApplication(
                    appContext,
                    PeriodicSyncWorker.PeriodicSyncEntryPoint::class.java
                )
                writeStage(appContext, "entrypoint")
                val settings = com.keepasskey.app.data.repository.RealSettingsRepository(appContext)
                val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                when (intent.action) {
                    ACTION_SETUP -> {
                        val runId = intent.getStringExtra(EXTRA_RUN_ID)
                            ?.takeIf { RUN_ID_PATTERN.matches(it) }
                            ?: error("invalid run id")
                        val port = intent.getIntExtra(EXTRA_PORT, -1)
                            .takeIf { it in 1..65535 }
                            ?: error("invalid port")
                        val session = services.databaseSession()
                        val credentials = services.syncCredentialsStore()
                        val coordinator = services.syncCoordinator()
                        val vault = File(appContext.filesDir, "p3-628-main-$runId.kdbx")
                        val originalActiveId = com.keepasskey.app.data.repository.ActiveDatabaseIdStore(appContext).load()
                        val originalProbeEnabled = settings.getSettings().first().syncProbeOnResumeEnabled
                        preferences.edit()
                            .putString(KEY_VAULT, vault.absolutePath)
                            .putString(KEY_ACTIVE_ID, originalActiveId)
                            .putBoolean(KEY_HAS_ACTIVE_ID, originalActiveId != null)
                            .putBoolean(KEY_PROBE_ENABLED, originalProbeEnabled)
                            .commit()

                        session.close()
                        writeStage(appContext, "session-closed")
                        val masterPassword = MASTER_PASSWORD.toCharArray()
                        try {
                            check(
                                session.create(vault, VAULT_NAME, masterPassword, useArgon2 = false)
                                    is com.keepasskey.core.result.KdbxResult.Success
                            ) { "synthetic vault creation failed" }
                        } finally {
                            masterPassword.fill('\u0000')
                        }
                        writeStage(appContext, "vault-created")
                        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(appContext).save(vault.absolutePath)
                        check(
                            credentials.saveWebDavConfig(
                                url = "https://127.0.0.1:$port",
                                username = WEBDAV_USER,
                                password = WEBDAV_PASSWORD.toCharArray(),
                                remotePath = REMOTE_PATH,
                                dbId = vault.absolutePath
                            )
                        ) { "synthetic WebDAV configuration could not be sealed" }
                        writeStage(appContext, "credentials-sealed")
                        credentials.saveProvider(com.keepasskey.app.ui.screens.settings.CloudSyncProvider.WEBDAV, vault.absolutePath)
                        val target = requireNotNull(coordinator.resolveProbeTarget())
                        coordinator.rememberRemoteEtagForProbe(STALE_ETAG, target.identity)
                        settings.setSyncProbeOnResumeEnabled(false)
                        withContext(Dispatchers.Main.immediate) { services.autoLockManager().onUnlockSuccess() }
                        writeStage(appContext, "session-ready")
                        File(appContext.filesDir, READY_MARKER).writeText("ready\n")
                    }

                    ACTION_ENABLE_PROBE -> settings.setSyncProbeOnResumeEnabled(true)

                    ACTION_ASSERT_NOTICE -> {
                        val notice = (appContext as com.keepasskey.app.MainApplication)
                            .resumeSyncProbeCoordinator.notice.value
                        val target = services.syncCoordinator().resolveProbeTarget()
                        val etagChanged = target?.let {
                            services.syncCoordinator().lastRemoteEtagForProbe(it.identity) != STALE_ETAG
                        } == true
                        File(appContext.filesDir, NOTICE_MARKER).writeText(
                            "notice=${notice.orEmpty()}\ntargetPresent=${target != null}\netagChanged=$etagChanged\n"
                        )
                    }

                    ACTION_CLEANUP -> {
                        val vaultPath = preferences.getString(KEY_VAULT, null)
                        val session = services.databaseSession()
                        session.close()
                        vaultPath?.let { path ->
                            services.syncCredentialsStore().clearFor(path)
                            File(path + ".bak").delete()
                            File(path).delete()
                        }
                        val activeId = if (preferences.getBoolean(KEY_HAS_ACTIVE_ID, false)) {
                            preferences.getString(KEY_ACTIVE_ID, null)
                        } else {
                            null
                        }
                        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(appContext).save(activeId)
                        settings.setSyncProbeOnResumeEnabled(preferences.getBoolean(KEY_PROBE_ENABLED, true))
                        preferences.edit().clear().commit()
                        File(appContext.filesDir, READY_MARKER).delete()
                        File(appContext.filesDir, NOTICE_MARKER).delete()
                        File(appContext.filesDir, RECEIVED_MARKER).delete()
                        File(appContext.filesDir, STAGE_MARKER).delete()
                        File(appContext.filesDir, ERROR_MARKER).delete()
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                runCatching {
                    File(context.filesDir, ERROR_MARKER).writeText(failure.javaClass.simpleName)
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun writeStage(context: Context, stage: String) {
        File(context.filesDir, STAGE_MARKER).writeText(stage)
    }

    companion object {
        const val ACTION_SETUP = "com.keepasskey.debug.P3_628_SETUP_MAIN_PROCESS"
        const val ACTION_ENABLE_PROBE = "com.keepasskey.debug.P3_628_ENABLE_PROBE"
        const val ACTION_ASSERT_NOTICE = "com.keepasskey.debug.P3_628_ASSERT_NOTICE"
        const val ACTION_CLEANUP = "com.keepasskey.debug.P3_628_CLEANUP"
        const val EXTRA_RUN_ID = "p3_628_run_id"
        const val EXTRA_PORT = "p3_628_webdav_port"

        private const val PREFERENCES = "p3_628_main_process_test"
        private const val KEY_VAULT = "vault"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_HAS_ACTIVE_ID = "has_active_id"
        private const val KEY_PROBE_ENABLED = "probe_enabled"
        private const val READY_MARKER = ".p3-628-main-process.ready"
        private const val RECEIVED_MARKER = ".p3-628-main-process.received"
        private const val STAGE_MARKER = ".p3-628-main-process.stage"
        private const val NOTICE_MARKER = ".p3-628-main-process.notice"
        private const val ERROR_MARKER = ".p3-628-main-process.error"
        private const val WEBDAV_USER = "p2-627-test"
        private const val WEBDAV_PASSWORD = "Synthetic-P2-627-WebDav-Password"
        private const val MASTER_PASSWORD = "Synthetic-P3-628-MainProcess-Master"
        private const val VAULT_NAME = "P3-628 app process"
        private const val REMOTE_PATH = "/p3-628-probe.kdbx"
        private const val STALE_ETAG = "synthetic-stale-etag"
        private val RUN_ID_PATTERN = Regex("[0-9a-f]{12}")
    }
}
