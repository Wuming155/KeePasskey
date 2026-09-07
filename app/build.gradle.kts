import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.keepasskey.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keepasskey"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Kotlin 2.2 起 android.kotlinOptions 已移除，统一使用 kotlin.compilerOptions
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // 依赖所有功能模块
    implementation(project(":core"))
    implementation(project(":database"))
    implementation(project(":sync"))

    // P2 整改：core-ktx 升级到「compileSdk 36 下可用的最高官方稳定版」1.17.0。
    // 官方 release notes 明示：1.18.0 起 compileSdk 由 API 36 改为 API 36.1，1.19.0 更要求 API 37，
    // 而本项目受 Compose BOM 2026.06.01 约束必须停留在 compileSdk 36（AGP 9.1.0 上限亦为 36），
    // 故 1.17.0 是当前版本矩阵下的天花板，不可再升。
    // ContextCompat.RECEIVER_NOT_EXPORTED 自 1.9.0 起可用，本次即为该 API 引入。
    implementation("androidx.core:core-ktx:1.17.0")

    // Compose 物料清单：统一管理所有 androidx.compose.* 版本，与 Kotlin 2.4.10 的 Compose 编译器对齐。
    // 使用 2026.06.01（Compose 1.11.x，要求 compileSdk ≤ 36）；最新 2026.08.00(1.12.x) 要求 compileSdk 37，当前 SDK 未安装。
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    // Wave 12 依赖治理：biometric 稳定渠道最新为 1.1.0（1.2.x/1.4.x 均为 alpha，
    // 官方发布页 2026-04 确认；Authenticators/setAllowedAuthenticators/CryptoObject 均已覆盖），
    // 消除安全关键组件的 alpha 依赖；credentials 升至稳定版 1.6.0（1.5.0 bugfix：isConditional 传播修复）
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("com.google.dagger:hilt-android:2.60.1")
    kapt("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.credentials:credentials:1.6.0")
    // TOTP 二维码扫描（断点5 整改：扫码按钮真实化）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
