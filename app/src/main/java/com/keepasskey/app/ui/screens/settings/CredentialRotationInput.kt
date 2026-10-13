package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import java.io.Closeable

/**
 * 受理时复制的任务私有输入；调用方原数组即时擦除，任务不会读取页面仍可清理的借用输入。
 * [close] 可重复执行：任务 finally 与 Job 完成钩子共同覆盖未进入协程体的取消窗口。
 */
internal class CredentialRotationInput private constructor(
    val password: CharArray,
    val keyFile: ChangeKeyFileIntent
) : Closeable {
    override fun close() {
        password.fill('0')
        if (keyFile is ChangeKeyFileIntent.Use) keyFile.bytes.fill(0)
    }

    companion object {
        fun copyAndEraseCaller(password: CharArray, keyFile: ChangeKeyFileIntent): CredentialRotationInput {
            var ownedPassword: CharArray? = null
            var ownedKey: ByteArray? = null
            var delivered = false
            return try {
                ownedPassword = password.copyOf()
                val ownedIntent = if (keyFile is ChangeKeyFileIntent.Use) {
                    ownedKey = keyFile.bytes.copyOf()
                    ChangeKeyFileIntent.Use(ownedKey, keyFile.sourceUri, keyFile.displayName)
                } else keyFile
                CredentialRotationInput(ownedPassword, ownedIntent).also { delivered = true }
            } finally {
                password.fill('0')
                if (keyFile is ChangeKeyFileIntent.Use) keyFile.bytes.fill(0)
                if (!delivered) {
                    ownedPassword?.fill('0')
                    ownedKey?.fill(0)
                }
            }
        }
    }
}
