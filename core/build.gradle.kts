plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasskey.core"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        // ISSUE-P2-192 余量第 6 项：core 首次引入 androidTest（instrumented）源集，
        // AppLog 等平台侧行为（android.util.Log 真实写入 / 级别开关）只有在此源集才能证。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（JVM 17）

dependencies {
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    testImplementation(libs.junit)
    // ISSUE-P2-192 余量第 6 项：设备侧源集依赖（与其余四模块同源版本）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
