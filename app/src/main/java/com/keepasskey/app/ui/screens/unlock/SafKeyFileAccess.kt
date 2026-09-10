package com.keepasskey.app.ui.screens.unlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF（Storage Access Framework）密钥文件访问实现（ISSUE-P3-04）。
 *
 * 安全边界：
 * 1. **密钥材料只经 [ByteArray]**：读取在 [Dispatchers.IO] 内完成，流经 `use {}` 强关闭，
 *    分块缓冲与 [ByteArrayOutputStream] 内部缓冲在 `finally` 中显式清零；
 *    提供方显示名（文档名）与 SAF Uri 属非密钥元数据，可持久化、可进入 UiState；
 * 2. **持久化授权最小化**：仅在「记住密钥文件」偏好开启时申请
 *    `takePersistableUriPermission(FLAG_GRANT_READ_URI_PERMISSION)`，部分 provider
 *    不支持持久化授权时返回 false（调用方优雅降级为「本次会话可用、不记忆」）；
 * 3. **禁止静默失败**：读取/授权失败一律经 [DebugLogBuffer] 留痕，且日志**不含**
 *    Uri、显示名或异常 message（异常只记类名），杜绝密钥文件定位信息外泄。
 */
@Singleton
class SafKeyFileAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val extendedSettingsStore: ExtendedSettingsStore,
    private val debugLog: DebugLogBuffer
) : KeyFileAccess {

    override suspend fun isRememberEnabled(): Boolean = withContext(Dispatchers.IO) {
        // 偏好读取异常按关闭处理（fail-closed）：不记忆只是体验降级，越权留存才是事故
        runCatching { extendedSettingsStore.load().rememberKeyFileLocation }
            .getOrElse {
                debugLog.warn(TAG, "密钥文件记忆偏好读取失败，按关闭处理")
                false
            }
    }

    override suspend fun read(uri: String): KeyFileReadResult = withContext(Dispatchers.IO) {
        val target = runCatching { Uri.parse(uri) }.getOrNull()
        if (target == null || uri.isBlank()) {
            debugLog.warn(TAG, "密钥文件 Uri 非法，拒绝读取")
            return@withContext KeyFileReadResult.Unreadable
        }
        try {
            val stream = context.contentResolver.openInputStream(target)
            if (stream == null) {
                debugLog.warn(TAG, "密钥文件流不可打开（提供方拒绝或已失效）")
                return@withContext KeyFileReadResult.Unreadable
            }
            // use{} 强关闭；分块缓冲与内部缓冲在 readKeyFileBytesCapped 内清零
            val bytes = stream.use { readKeyFileBytesCapped(it) }
            if (bytes == null) {
                debugLog.warn(TAG, "密钥文件超出 $KEY_FILE_MAX_BYTES 字节上限，拒绝读取")
                KeyFileReadResult.TooLarge
            } else if (bytes.isEmpty()) {
                debugLog.warn(TAG, "密钥文件为空文件，拒绝作为密钥材料")
                KeyFileReadResult.Empty
            } else {
                KeyFileReadResult.Success(bytes, queryDisplayName(target))
            }
        } catch (e: Exception) {
            // 禁止静默失败：仅留痕异常类名，不外传 Uri / 异常 message
            debugLog.warn(TAG, "密钥文件读取失败: ${e.javaClass.simpleName}")
            KeyFileReadResult.Unreadable
        }
    }

    override suspend fun persistReadPermission(uri: String): Boolean = withContext(Dispatchers.IO) {
        val target = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext false
        try {
            context.contentResolver.takePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // 显式回读校验：部分 provider 接受调用但不真正落授权
            hasPersistedReadPermission(uri)
        } catch (_: SecurityException) {
            // 提供方不支持持久化授权（或返回 Intent 未携带 FLAG_GRANT_READ_URI_PERMISSION）：
            // 优雅降级——本次会话仍可用（字节已读入），仅不记忆
            debugLog.info(TAG, "密钥文件提供方不支持持久化读授权，降级为仅本次会话可用")
            false
        } catch (e: Exception) {
            debugLog.warn(TAG, "密钥文件持久化读授权申请失败: ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun hasPersistedReadPermission(uri: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext false
            runCatching {
                context.contentResolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.uri == target
                }
            }.getOrElse {
                debugLog.warn(TAG, "持久化读授权查询失败，按无授权处理")
                false
            }
        }

    override suspend fun loadRemembered(): RememberedKeyFile? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSettings().first()
        if (settings.lastKeyFileUri.isBlank()) {
            null
        } else {
            RememberedKeyFile(settings.lastKeyFileUri, settings.lastKeyFileName)
        }
    }

    override suspend fun remember(uri: String, displayName: String) {
        withContext(Dispatchers.IO) {
            settingsRepository.setRememberedKeyFile(uri, displayName)
        }
    }

    override suspend fun forget() {
        withContext(Dispatchers.IO) {
            settingsRepository.clearRememberedKeyFile()
        }
    }

    /** 查询 SAF 文档显示名（非密钥内容）；查询失败回退为 Uri 最后一段 */
    private fun queryDisplayName(uri: Uri): String = querySafDisplayName(context, uri)

    private companion object {
        const val TAG = "Unlock"
    }
}

/**
 * 查询 SAF 文档显示名；查询失败回退为 Uri 最后一段。
 * 显示名属非密钥元数据（可进入 UiState 与偏好持久化），但**不写入日志**。
 */
internal fun querySafDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

/**
 * 读取密钥文件字节（上限 [KEY_FILE_MAX_BYTES] 字节；超限返回 null，绝不截断半截密钥）。
 *
 * 密钥材料擦除纪律：[READ_CHUNK_BYTES] 分块缓冲在 `finally` 清零，
 * [WipeableByteArrayOutputStream] 内部缓冲在返回前清零——堆上仅保留移交调用方的结果数组
 * （调用方用毕 `fill(0)`）。
 */
internal fun readKeyFileBytesCapped(input: InputStream): ByteArray? {
    val sink = WipeableByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    try {
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (sink.size() + read > KEY_FILE_MAX_BYTES) return null
            sink.write(chunk, 0, read)
        }
        return sink.toByteArray()
    } finally {
        chunk.fill(0)
        sink.wipe()
    }
}

/** 单次读取分块大小：8 KiB（密钥文件惯例为 32~128 字节，单块即可读完） */
internal const val READ_CHUNK_BYTES: Int = 8 * 1024

/**
 * 可显式擦除内部缓冲的字节流：密钥材料不在堆上长期驻留（纵深防御）。
 *
 * `open` 的唯一理由是让单测以探针子类直读内部缓冲、断言擦除后全零
 * （JDK 17 下无法用反射访问 `java.base` 的非公开成员）；生产代码不继承本类。
 */
internal open class WipeableByteArrayOutputStream : ByteArrayOutputStream() {

    /** 清零已写入区间并复位计数（BAOS 的 buf/count 对子类可见） */
    fun wipe() {
        Arrays.fill(buf, 0, count, 0.toByte())
        reset()
    }
}
