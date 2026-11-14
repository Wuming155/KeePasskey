package com.keepasskey.app.ui.screens.detail

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/**
 * ENG-20261028-12：附件导出的「写出即可读、交付副本用毕清零」**设备行为**用例。
 *
 * 替代原先的源码文本守卫（`AttachmentDataOwnershipTest#附件导出写出后必须清零交付副本且清零点晚于写出`）：
 * 那条断言只在源码里找 `os.write(bytes)` / `bytes.fill(0)` 的位置与 finally 形态，重构即误红，
 * 却证明不了真实写出的字节被擦除。本用例只操作本测试创建的临时文件与合成字节，走真实
 * `ContentResolver` → 文件写出路径（`file://` 在 `ContentResolver.openAssetFileDescriptor`
 * 里经 `ParcelFileDescriptor.open` 直写，见 android-37.0 `ContentResolver.java:1906`），
 * 断言两件事：
 *
 * 1. 成功路径：目标文件内容 == 交付字节，且返回后仓库交付的那个数组**已归零**；
 * 2. 早退路径（无 `Context` ⇒ 取不到 `ContentResolver`）：交付数组同样归零，不留下明文副本。
 *
 * 覆盖不到的边界：真实 SAF `content://` 目标、写盘异常与取消路径的清理（`SafDocumentCleanup`）
 * 仍属 SAF 设备验收面，不在本用例内。
 */
@RunWith(AndroidJUnit4::class)
class EntryDetailAttachmentExportDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 导出写出成功后交付缓冲必须归零() = runBlocking {
        val payload = SYNTHETIC_BYTES.copyOf()
        val delivered = payload.copyOf()
        val target = tempTarget("success")
        try {
            val message = EntryDetailAttachmentExporter(
                vaultRepository = repositoryReturning(delivered),
                appContext = context,
                exportAuditRecorder = null,
            ).export(ENTRY_ID, attachment(), Uri.fromFile(target))

            assertEquals("成功导出必须回报成功提示", R.string.detail_attachment_export_toast, message.resId)
            assertArrayEquals("目标文件内容必须等于交付字节", payload, target.readBytes())
            assertTrue(
                "导出返回后仓库交付的解密附件数组必须已清零（否则明文随局部变量静默留存至 GC）",
                delivered.all { it == 0.toByte() }
            )
        } finally {
            target.delete()
        }
    }

    @Test
    fun 取不到输出流而早退时交付缓冲同样必须归零() = runBlocking {
        val delivered = SYNTHETIC_BYTES.copyOf()
        val target = tempTarget("early-exit")
        try {
            val message = EntryDetailAttachmentExporter(
                vaultRepository = repositoryReturning(delivered),
                // null 上下文 ⇒ `appContext?.contentResolver` 早退，命中 finally 前的失败分支
                appContext = null,
                exportAuditRecorder = null,
            ).export(ENTRY_ID, attachment(), Uri.fromFile(target))

            assertEquals("早退必须回报失败提示", R.string.detail_attachment_export_failed, message.resId)
            assertFalse("早退不得创建目标文件", target.exists())
            assertTrue("早退路径也必须清零交付数组", delivered.all { it == 0.toByte() })
        } finally {
            target.delete()
        }
    }

    private fun tempTarget(label: String): File =
        File(context.cacheDir, "eng12-export-$label-${System.nanoTime()}.bin").apply { delete() }

    private fun attachment() = UiAttachment(
        id = "attachment-0",
        fileName = "synthetic.bin",
        fileSizeFormatted = "${SYNTHETIC_BYTES.size} B",
        refIndex = 0
    )

    /**
     * 只实现导出路径实际调用的 `getAttachmentData`（Kotlin `suspend` 接口方法经 Proxy 调用时，
     * 直接返回非挂起值即被调用方当作结果）；其余方法调用即报错，避免掩盖接线变化。
     */
    private fun repositoryReturning(delivered: ByteArray): VaultRepository =
        Proxy.newProxyInstance(
            VaultRepository::class.java.classLoader,
            arrayOf(VaultRepository::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getAttachmentData" -> delivered
                "toString" -> "SyntheticAttachmentRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("附件导出路径不得调用仓库的其它方法：${method.name}")
            }
        } as VaultRepository

    private companion object {
        const val ENTRY_ID = "synthetic-entry-id"

        /** 虚构明文（与真实用户库、凭据无关） */
        val SYNTHETIC_BYTES = "ENG12-SYNTHETIC-ATTACHMENT-PLAINTEXT".toByteArray()
    }
}