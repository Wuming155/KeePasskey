package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionScreen
import com.keepasskey.app.ui.screens.conflict.ConflictResolutionViewModel
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/** Debug-only host for rendering the production sync conflict screen from an instrumented coordinator. */
class SyncConflictDeviceHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coordinator = requireNotNull(suppliedCoordinator) { "instrumented coordinator not supplied" }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass.isAssignableFrom(ConflictResolutionViewModel::class.java))
                val strings = StringsProvider { id, args -> getString(id, *args) }
                return ConflictResolutionViewModel(coordinator, strings) as T
            }
        }
        val viewModel = ViewModelProvider(this, factory)[ConflictResolutionViewModel::class.java]
        setContent {
            KeePasskeyTheme {
                ConflictResolutionScreen(
                    onBackClick = { finish() },
                    onResolveSuccess = { finish() },
                    viewModel = viewModel
                )
            }
        }
    }

    companion object {
        @Volatile
        var suppliedCoordinator: SyncCoordinator? = null
    }
}
