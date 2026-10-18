package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.keepasskey.app.ui.screens.edit.EntryEditScreen
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only host for rendering the production entry editor in accessibility device tests. */
@AndroidEntryPoint
class EntryEditDeviceHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        setContent {
            KeePasskeyTheme {
                EntryEditScreen(
                    entryId = entryId,
                    onBackClick = { finish() },
                    onSaveSuccess = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_ENTRY_ID = "editEntryId"
    }
}
