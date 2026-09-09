plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasskey.crypto"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // TASK-52：Argon2 原生 JNI（PHC 官方参考实现自维护构建，替代第三方预编译 argon2kt）
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（JVM 17）

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
