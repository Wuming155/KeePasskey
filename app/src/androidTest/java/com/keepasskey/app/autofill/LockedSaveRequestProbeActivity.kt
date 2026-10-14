package com.keepasskey.app.autofill

import android.app.Activity
import android.os.Bundle
import com.keepasskey.app.passkey.PasswordSaveActivity
import java.util.concurrent.CopyOnWriteArrayList

/** 只用于系统 PendingIntent 身份设备测试的合成接收端；不读取或记录真实凭据。 */
class LockedSaveRequestProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arrivals += Arrival(
            data = intent.data?.toString().orEmpty(),
            requestToken = intent.getStringExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN).orEmpty(),
            username = intent.getStringExtra(PasswordSaveActivity.EXTRA_USERNAME).orEmpty(),
            password = intent.getStringExtra(PasswordSaveActivity.EXTRA_PASSWORD).orEmpty()
        )
        finish()
    }

    data class Arrival(val data: String, val requestToken: String, val username: String, val password: String)

    companion object {
        val arrivals = CopyOnWriteArrayList<Arrival>()
    }
}
