package com.keepasskey.app.testutil

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * JVM 单测用的**内存版** `SharedPreferences` / `Context` 替身。
 *
 * 为什么需要：`android.jar` 的桩里 `getSharedPreferences` 直接返回 `null`，而凭据存储类
 * （`SyncCredentialsStore` 等）要**可写**的键值面才能把封印后的密文落进去——只读代理会让
 * `edit()` 返回 `null` 并当场 NPE，于是「先预置凭据、再验证读取链路」这类用例无从写起。
 *
 * 该替身此前在 `SyncCredentialsStoreTest` 内以私有字段实现；本文件只**新增**共享件，
 * 未改写既有测试资产（`AGENTS.md` §3「测试资产只增不改」），后续批次可让该类改用它。
 *
 * 用法：`val store = SyncCredentialsStore(InMemorySharedPreferences().context(), null)`。
 */
class InMemorySharedPreferences {

    private val storage = mutableMapOf<String, Any?>()

    val prefs: SharedPreferences by lazy { newPrefsProxy() }

    /** `getSharedPreferences` 恒返回本实例、`getCacheDir` 指向一次性临时目录的 Context 替身。 */
    fun context(): Context = object : ContextWrapper(null) {
        private val cacheDirectory: File by lazy {
            Files.createTempDirectory("keepasskey-test-cache").toFile().apply { deleteOnExit() }
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs

        override fun getCacheDir(): File = cacheDirectory
    }

    fun clear() = storage.clear()

    private fun newPrefsProxy(): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getString" -> (storage[args[0] as String] as? String) ?: (args[1] as? String)
            "getBoolean" -> (storage[args[0] as String] as? Boolean) ?: (args[1] as? Boolean ?: false)
            "getInt" -> (storage[args[0] as String] as? Int) ?: (args[1] as? Int ?: 0)
            "getLong" -> (storage[args[0] as String] as? Long) ?: (args[1] as? Long ?: 0L)
            "getStringSet" -> storage[args[0] as String] as? Set<*>
            "contains" -> storage.containsKey(args[0] as String)
            "getAll" -> storage.toMap()
            "edit" -> newEditorProxy()
            else -> null
        }
    } as SharedPreferences

    private fun newEditorProxy(): SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean", "putInt", "putLong", "putStringSet" -> {
                storage[args[0] as String] = args[1]
                proxy
            }
            "remove" -> {
                storage.remove(args[0] as String)
                proxy
            }
            "clear" -> {
                storage.clear()
                proxy
            }
            "commit" -> true
            else -> proxy
        }
    } as SharedPreferences.Editor
}
