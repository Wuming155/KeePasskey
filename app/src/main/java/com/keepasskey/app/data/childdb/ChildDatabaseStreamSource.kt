package com.keepasskey.app.data.childdb

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子库来源的**只读字节流**抽象。
 *
 * 抽象成接口是为了依赖倒置与可测性：生产实现 [LocalChildDatabaseStreamSource] 走
 * `ContentResolver` / 文件系统（依赖 Android 框架），JVM 单测注入内存实现即可覆盖
 * 「真实解密 + 真实投影」全链路，无需模拟器。
 *
 * 契约：
 * - **只读**：实现只可返回输入流，不得写入、不得就地修改来源文件，也不得把子库复制到
 *   `cacheDir`（否则锁定后仍会有密文残留，与 ISSUE-P1-07 的缓存销毁纪律相悖）；
 * - **每次调用返回新鲜流**：调用方以 `use { }` 关闭；
 * - 来源不可读（文件缺失 / URI 无权限）抛 [FileNotFoundException]，
 *   由 [ChildReadOnlySession] 归一为 [ChildDatabaseMountState.SourceUnavailable]。
 */
fun interface ChildDatabaseStreamSource {

    /** 打开该挂载对应子库文件的输入流（IO 调度器由实现自行保证） */
    suspend fun open(mount: ChildDatabaseMount): InputStream
}

/**
 * 生产实现：本机 `content://` 与本地绝对路径。
 *
 * 刻意**不实现**任何网络协议前缀（`http(s)` / `webdav` / `s3`）：子库不参与同步，
 * 网络来源需要凭据协商与离线缓存，属另一条独立设计线（见
 * [ChildDatabaseSessionManager] KDoc「同步交互」小节）。
 */
@Singleton
class LocalChildDatabaseStreamSource @Inject constructor(
    @ApplicationContext private val context: Context
) : ChildDatabaseStreamSource {

    override suspend fun open(mount: ChildDatabaseMount): InputStream = withContext(Dispatchers.IO) {
        when (mount.sourceKind) {
            ChildDatabaseSourceKind.CONTENT_URI -> openContentUri(mount)
            ChildDatabaseSourceKind.LOCAL_FILE -> openLocalFile(mount)
        }
    }

    private fun openContentUri(mount: ChildDatabaseMount): InputStream {
        val uri = Uri.parse(mount.sourceUri)
        return context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("无法打开子库来源（URI 无读取权限或已被撤销）: ${mount.id}")
    }

    private fun openLocalFile(mount: ChildDatabaseMount): InputStream {
        val file = File(mount.sourceUri)
        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException("子库文件不存在: ${file.absolutePath}")
        }
        return FileInputStream(file)
    }
}
