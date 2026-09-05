package com.keepasskey.app.passkey

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * 凭据交互 Activity 公共抽象基类。
 * 强制应用 FLAG_SECURE 保护窗口，杜绝凭据明文被截屏、录屏或在多任务概览中泄露。
 */
abstract class BaseCredentialActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    protected fun failAndFinish(message: String? = null) {
        setResult(RESULT_CANCELED)
        finish()
    }
}
