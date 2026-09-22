package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-P2-72 回归：`clientDataJSON.androidPackageName` 的归属口径。
 *
 * 原缺陷：两侧都写 `callingPackage ?: packageName`——
 * 1. `Activity.getCallingPackage()` 在系统经 PendingIntent 拉起本窗口时为 `"android"`/null；
 * 2. 兜底 `?: packageName` 又把归属写成**本应用包名**，等于向 RP 谎报调用方。
 *
 * 现口径：只接受「系统认证的包名」或「本应用 PendingIntent extras 中记录的预期包名」，
 * 二者皆不可用时**省略该字段**（而不是回退为本应用包名）；系统包名 `android` 一律排除
 * （否则会落出可被同包名侧载应用命中的 `android://android` 绑定）。
 */
class CallingOriginResolverAttributionTest {

    @Test
    fun `系统包名与空白候选一律不合格`() {
        assertNull(CallingOriginResolver.clientDataAndroidPackageName("android"))
        assertNull(CallingOriginResolver.clientDataAndroidPackageName("", "   "))
        assertNull(CallingOriginResolver.clientDataAndroidPackageName(null, null))
    }

    @Test
    fun `系统认证包名优先于 extras 记录`() {
        assertEquals(
            "com.real.caller",
            CallingOriginResolver.clientDataAndroidPackageName("com.real.caller", "com.other")
        )
    }

    @Test
    fun `系统包名被跳过后回退到 extras 中的预期包名（extras 本身不可伪造性不成立）`() {
        assertEquals(
            "com.example.app",
            CallingOriginResolver.clientDataAndroidPackageName("android", "com.example.app")
        )
        assertEquals(
            "com.example.app",
            CallingOriginResolver.clientDataAndroidPackageName(" android ", " com.example.app ")
        )
    }

    @Test
    fun `无可用归属时返回 null 以省略字段`() {
        // 关键负例：不得回退为本应用包名（调用方必须不把 packageName 作为候选传入）
        assertNull(CallingOriginResolver.clientDataAndroidPackageName("android", "android"))
    }

    @Test
    fun `系统自身包名常量固定为 android`() {
        assertEquals("android", CallingOriginResolver.SYSTEM_PACKAGE_ANDROID)
    }

    /**
     * 系统返回的 origin 可能带末尾斜杠（Android 16 实测 `https://www.passkeys.io/`），
     * 而依赖方按 WebAuthn 语义（`scheme://host[:port]`）严格比对，带斜杠即判 origin 不匹配。
     */
    @Test
    fun `系统返回 origin 的末尾斜杠必须被剥离`() {
        assertEquals(
            "https://www.passkeys.io",
            CallingOriginResolver.normalizeOrigin("https://www.passkeys.io/")
        )
        assertEquals(
            "带端口的 origin 同样剥离",
            "https://example.com:8443",
            CallingOriginResolver.normalizeOrigin("https://example.com:8443/")
        )
        assertEquals(
            "已规范化的 origin 为恒等变换",
            "https://example.com",
            CallingOriginResolver.normalizeOrigin("https://example.com")
        )
        assertEquals(
            "非 web 归属不受影响",
            "android:apk-key-hash:abc",
            CallingOriginResolver.normalizeOrigin("android:apk-key-hash:abc")
        )
    }

    @Test
    fun `无系统背书时包名解析返回 null`() {
        assertNull(CallingOriginResolver.systemAttestedPackageName(null))
    }
}
