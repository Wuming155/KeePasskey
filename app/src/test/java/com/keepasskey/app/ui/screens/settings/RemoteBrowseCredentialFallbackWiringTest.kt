package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-395 接线守卫（静态源码判据，无 Android 环境）。
 *
 * 判据：
 * 1. `SettingsViewModel` 的浏览入口仍为**单语句委托**（PD-23），不得内联实现体；
 * 2. `SettingsRemoteBrowseHost` 构造注入已保存凭据读取回调；
 * 3. UI browse 调用点必须传 `copyOf()`（宿主按借用语义清零）。
 */
class RemoteBrowseCredentialFallbackWiringTest {

    @Test
    fun `SettingsViewModel 浏览入口保持单语句委托`() {
        val source = readSource(SETTINGS_VIEW_MODEL)
        // PD-23：表达式体委托 `= remoteBrowseHost.browse...`，不得内联 if / store 逻辑
        assertTrue(
            "browseWebDav 必须委托 remoteBrowseHost",
            Regex("""fun browseWebDav[\s\S]*?=\s*\n?\s*remoteBrowseHost\.browseWebDav""").containsMatchIn(source)
        )
        assertTrue(
            "browseS3 必须委托 remoteBrowseHost",
            Regex("""fun browseS3[\s\S]*?=\s*remoteBrowseHost\.browseS3""").containsMatchIn(source)
        )
        val webDavBody = Regex("""fun browseWebDav[\s\S]*?=\s*\n?[^\n]+\n""").find(source)?.value.orEmpty()
        assertTrue(
            "browseWebDav 不得内联 syncCredentialsStore（PD-23）：$webDavBody",
            !webDavBody.contains("syncCredentialsStore") && !webDavBody.contains("if ")
        )
    }

    @Test
    fun `SettingsRemoteBrowseHost 注入已保存凭据回调并清零解析结果`() {
        val source = readSource(SETTINGS_REMOTE_BROWSE_HOST)
        assertTrue("必须注入 savedWebDavPassword 回调", source.contains("savedWebDavPassword"))
        assertTrue("必须注入 loadS3Snapshot 回调", source.contains("loadS3Snapshot"))
        assertTrue("必须委托 RemoteBrowseCredentials", source.contains("RemoteBrowseCredentials.resolveBrowsePassword"))
        assertTrue("协程结束必须清零凭据", source.contains("effective.fill('0')"))
        assertTrue("未采用的保存侧副本必须立即清零", source.contains("saved.fill('0')"))
    }

    @Test
    fun `UI browse 调用点均传 copyOf`() {
        val cloud = readSource(CLOUD_SYNC_SCREEN)
        val section = readSource(REMOTE_BROWSE_SECTION)
        assertTrue("CloudSyncScreen WebDAV browse 必须 copyOf", cloud.contains("webdavPasswordChars.copyOf()"))
        assertTrue("CloudSyncScreen S3 browse 必须 copyOf", cloud.contains("s3AccessKeyChars.copyOf()"))
        assertTrue("RemoteBrowseSection WebDAV navigate 必须 copyOf", section.contains("webdavPasswordChars.copyOf()"))
        assertTrue("RemoteBrowseSection S3 keys 必须 copyOf", section.contains("s3AccessKeyChars.copyOf()"))
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("扫描目标不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SETTINGS_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val SETTINGS_REMOTE_BROWSE_HOST =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsRemoteBrowseHost.kt"
        const val CLOUD_SYNC_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt"
        const val REMOTE_BROWSE_SECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/RemoteBrowseSection.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(6) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录")
        }
    }
}
