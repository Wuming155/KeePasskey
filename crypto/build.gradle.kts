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
}

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（JVM 17）

dependencies {
    implementation(project(":core"))
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    implementation(libs.bouncycastle)
    // TASK-50：Argon2 原生 JNI 加速（对齐 KeePassDX native 实现），BC 纯 Java 实现仅作兜底
    implementation(libs.argon2kt)

    testImplementation(libs.junit)
}
