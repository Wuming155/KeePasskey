import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// ============================================================================
// Rust 秘密飞地 PoC · Batch 3：Argon2 原生内核由 cargo-ndk 从源码交叉编译产出，
// 替代原 CMake C 内核（TASK-52）。C 源码 src/main/cpp/ 暂留仓库，Batch 5 git rm。
// ============================================================================

// NDK 版本与 AGP strip/构建所用一致；cargo-ndk 通过 ANDROID_NDK_HOME 复用同一 NDK。
val ndkVersionStr = "28.2.13676358"

// 解析 Android SDK 路径：优先 local.properties 的 sdk.dir，回退环境变量。
val resolvedSdkDir: String = run {
    val lp = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    lp.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("无法解析 Android SDK 路径（local.properties sdk.dir / ANDROID_HOME / ANDROID_SDK_ROOT 均缺失）")
}
val resolvedNdkDir = file("$resolvedSdkDir/ndk/$ndkVersionStr")
val rustProjectDir = file("src/main/rust")
val rustJniLibsDir = layout.buildDirectory.dir("rust/jniLibs")

android {
    namespace = "com.keepasskey.crypto"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        ndk {
            // 与 cargo-ndk 产出的 4 ABI 对齐，显式声明避免歧义
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 仍需 ndkVersion：AGP 用该 NDK 的 strip 工具处理打包的 .so（含 cargo 产出的 Rust .so）
    ndkVersion = ndkVersionStr
    // Batch 3 退役 CMake-argon2：移除 externalNativeBuild.cmake，原生 .so 改由 cargoNdkBuild 产出。
    sourceSets {
        getByName("main") {
            // cargo-ndk 交叉编译产物目录（build/rust/jniLibs/<abi>/libkeepasskey_argon2.so）
            jniLibs.srcDirs(rustJniLibsDir.get().asFile)
        }
    }
}

// cargo-ndk 交叉编译 Rust Argon2 内核 → build/rust/jniLibs/<abi>/libkeepasskey_argon2.so（release）
// 全程源码构建，保持「零二进制信任根」哲学；产出 .so 名与 System.loadLibrary("keepasskey_argon2") 对齐。
val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "cargo-ndk 交叉编译 Rust Argon2 原生内核（4 ABI，release）"
    workingDir = rustProjectDir
    // cargo-ndk 定位 NDK 链接器所需
    environment("ANDROID_NDK_HOME", resolvedNdkDir.absolutePath)
    environment("ANDROID_NDK_ROOT", resolvedNdkDir.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-t", "x86",
        "-o", rustJniLibsDir.get().asFile.absolutePath,
        "build", "--release",
    )
}

// 让原生库合并任务在 cargo 产物就绪后执行（merge{Variant}NativeLibs 是打包 .so 的权威任务）
tasks.matching { it.name.matches(Regex("merge.*NativeLibs")) }.configureEach {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(project(":core"))
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
}

// Rust 迁移 PoC · Batch 0：把 -DexportArgon2Vectors 转发进单元测试 JVM，
// 使 `gradlew :crypto:test -DexportArgon2Vectors=true` 可用 BC 重算并覆写对照向量 JSON；
// 默认（false）时该测试仅读取已冻结向量并防漂移校验，永不 skip。
tasks.withType<Test>().configureEach {
    systemProperty(
        "exportArgon2Vectors",
        System.getProperty("exportArgon2Vectors") ?: "false"
    )
}
