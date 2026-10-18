package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.keepasskey.app.ui.screens.detail.EntryDetailScreen
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only host for production EntryDetailScreen history interaction tests. */
@AndroidEntryPoint
class HistoryDetailDeviceHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeePasskeyTheme {
                EntryDetailScreen(
                    entryId = intent.getStringExtra(EXTRA_ENTRY_ID),
                    onBackClick = { finish() },
                    onEditClick = {}
                )
            }
        }
    }

    companion object {
        const val EXTRA_ENTRY_ID = "historyEntryId"
    }
}
