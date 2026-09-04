package com.keepasskey.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.keepasskey.app.ui.KeePasskeyApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用入口 Activity，承载 KeePasskeyApp 全局 Compose 导航与主题容器。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePasskeyApp()
        }
    }
}
