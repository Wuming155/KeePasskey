package com.keepasskey.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * ISSUE-P3-395：远端目录浏览凭据解析（表单优先 + 已保存回退）。
 *
 * 对应真机缺陷：测连走已保存凭据成功，浏览只吃表单密码（保存后已清零）→ 401。
 */
class RemoteBrowseCredentialsTest {

    @Test
    fun `表单密码非空时优先使用表单（支持尚未保存的新配置）`() {
        val form = "typed-secret".toCharArray()
        val saved = "old-saved".toCharArray()
        val resolved = RemoteBrowseCredentials.resolveBrowsePassword(form, saved)
        assertSame("表单非空时应原样使用表单数组（调用方持有 copyOf）", form, resolved)
    }

    @Test
    fun `表单密码为空时回退已保存密码`() {
        val saved = "saved-secret".toCharArray()
        val resolved = RemoteBrowseCredentials.resolveBrowsePassword(CharArray(0), saved)
        assertArrayEquals("空表单应原样采用已保存密码", saved, resolved)
    }

    @Test
    fun `表单与已保存皆空时返回空数组（由 Provider 上浮鉴权失败）`() {
        val resolved = RemoteBrowseCredentials.resolveBrowsePassword(CharArray(0), null)
        assertEquals(0, resolved.size)
        val emptySaved = RemoteBrowseCredentials.resolveBrowsePassword(CharArray(0), CharArray(0))
        assertEquals(0, emptySaved.size)
    }

    @Test
    fun `S3 逐侧独立：表单 AccessKey + 保存 SecretKey 的混搭`() {
        val (access, secret) = RemoteBrowseCredentials.resolveS3BrowseKeys(
            formAccessKey = "AKIAFORM".toCharArray(),
            formSecretKey = CharArray(0),
            savedAccessKey = "AKIAOLD".toCharArray(),
            savedSecretKey = "secret-old".toCharArray()
        )
        assertEquals("AKIAFORM", String(access))
        assertEquals("secret-old", String(secret))
    }

    @Test
    fun `S3 表单双键皆空时双侧都回退保存值`() {
        val (access, secret) = RemoteBrowseCredentials.resolveS3BrowseKeys(
            formAccessKey = CharArray(0),
            formSecretKey = CharArray(0),
            savedAccessKey = "AKIASAVE".toCharArray(),
            savedSecretKey = "secret-save".toCharArray()
        )
        assertEquals("AKIASAVE", String(access))
        assertEquals("secret-save", String(secret))
    }
}
