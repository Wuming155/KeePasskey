package com.keepasskey.crypto

/**
 * 原生密码内核（`libkeepasskey_argon2.so`）的**统一加载入口**。
 *
 * 背景（ISSUE-P3-34 / 35 / 36）：该 `.so` 由 `crypto/src/main/rust/` 经 cargo-ndk 交叉编译，
 * 原先只承载 Argon2 内核（ISSUE-P2-14）。本批次把 AES-KDF、Twofish-CBC 与口令强度评估
 * 一并并入**同一个 crate 与同一个 `.so`**，因此加载点必须收敛为一处。
 *
 * 库名 `keepasskey_argon2` 为历史命名（`System.loadLibrary`、`crypto/build.gradle.kts` 的
 * `cargoNdkBuild`、CI `Native gate` 的 .so 名断言与 6 份文档均绑定该名）；改名会牵动 CI 与
 * 全部文档却零功能收益，故**有意保留**。语义上它已是项目的原生密码内核。
 *
 * 加载语义：
 * - `System.loadLibrary` 对已加载库是幂等的，故与 `NativeArgon2` 各自的加载调用互不冲突；
 * - 加载失败（桌面 JVM 未注入宿主库、个别机型缺 ABI 等）返回 `false`，
 *   由各绑定**降级到纯 JVM 实现**，不抛出、不阻断调用方。
 *
 * ⚠️ 顺序依赖（沿用 `AGENTS.md` 已登记的同型限界）：`external fun` 在库未加载时调用会抛
 * `UnsatisfiedLinkError`。各绑定对象的 `available` 一律**先求值本属性**再发起原生调用，
 * 新增消费方必须遵循同一顺序。
 */
internal object NativeCryptoLibrary {

    /** 与 `crypto/build.gradle.kts` 的 cargo-ndk 产出、`NativeArgon2` 三者一致。 */
    const val LIBRARY_NAME = "keepasskey_argon2"

    /** 库是否已成功加载（懒加载一次，结果缓存）。 */
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
