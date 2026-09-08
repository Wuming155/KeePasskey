plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasskey.core"
    compileSdk = 36
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（JVM 17）

dependencies {
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    testImplementation(libs.junit)
}
