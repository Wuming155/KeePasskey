package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 验证随机 Intent.data 可隔离进程重建后复位的认证 PendingIntent requestCode。 */
@RunWith(AndroidJUnit4::class)
class AutofillPendingIntentIdentityDeviceTest {

    @Test
    fun 同requestCode的自动填充认证入口保持独立() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestCode = REQUEST_CODE_AFTER_PROCESS_RESTART
        val oldIntent = attachAutofillRequestIdentity(
            Intent(context, AutofillConfirmActivity::class.java)
                .putExtra(EXTRA_CANDIDATE, "old-candidate")
        )
        val newIntent = attachAutofillRequestIdentity(
            Intent(context, AutofillConfirmActivity::class.java)
                .putExtra(EXTRA_CANDIDATE, "new-candidate")
        )
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val oldToken = PendingIntent.getActivity(context, requestCode, oldIntent, flags)
        val newToken = PendingIntent.getActivity(context, requestCode, newIntent, flags)

        try {
            assertFalse("随机 Intent.data 必须进入 filterEquals 匹配键", oldIntent.filterEquals(newIntent))
            assertNotEquals("新请求不得更新系统仍持有的旧候选 token", oldToken, newToken)
        } finally {
            oldToken.cancel()
            newToken.cancel()
        }
    }

    private companion object {
        const val REQUEST_CODE_AFTER_PROCESS_RESTART = 100
        const val EXTRA_CANDIDATE = "candidate"
    }
}
