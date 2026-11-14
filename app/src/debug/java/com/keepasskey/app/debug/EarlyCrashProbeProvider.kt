package com.keepasskey.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import java.io.File

/**
 * 仅 debug 变体：`EarlyCrashHandler` 的设备验收探针（`ISSUE-P2` A5）。
 *
 * 时机：ContentProvider 的 `onCreate` 早于 `Application.onCreate`，而
 * `EarlyCrashHandler` 在 `attachBaseContext` 安装，正好覆盖这段窗口。
 *
 * 用法（设备验收）：
 * 1. `run-as <包名> sh -c 'echo probe > no_backup/early_crash_probe'`；
 * 2. 结束进程后冷启动：本 Provider 读取标记、先删除再抛异常，因此只崩一次、不会形成崩溃环；
 * 3. 再次启动后归档应出现 `[Crash] ...（早期初始化阶段）`。
 *
 * release 变体不声明该 Provider（见 `app/src/debug/AndroidManifest.xml`）。
 */
class EarlyCrashProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        val marker = File(ctx.noBackupFilesDir, MARKER_NAME)
        if (marker.exists()) {
            marker.delete()
            throw IllegalStateException("early crash probe")
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val MARKER_NAME = "early_crash_probe"
    }
}
