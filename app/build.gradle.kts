plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.keepasskey.app"
    compileSdk = 37

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
            // TASK-38 整改：启用资源收缩——随 R8 移除未被引用的资源（图标/布局/字符串等），
            // 与 minifyEnabled 协同进一步压缩 APK 体积并减少资源面攻击暴露
            isShrinkResources = true
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

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility
// （JVM 17），无需显式 kotlin.compilerOptions 配置

dependencies {
    // 依赖所有功能模块
    implementation(project(":core"))
    implementation(project(":database"))
    implementation(project(":sync"))

    // TASK-07 整改：compileSdk 37 就位后 core-ktx 升至 1.19.0（1.19.0 起要求 API 37）
    implementation(libs.androidx.core.ktx)

    // Compose 物料清单：统一管理所有 androidx.compose.* 版本，与 Kotlin 2.4.10 的 Compose 编译器对齐。
    // TASK-07 整改：2026.08.00（Compose 1.12.x + Material 3 Expressive）要求 compileSdk 37
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // TASK-07：material3 1.5.0-alpha27 显式覆盖 BOM 映射——M3 Expressive 公开 API
    // （MaterialExpressiveTheme/MotionScheme）在 1.4.0 stable 中仍为 internal，
    // 1.5.0 stable 毕业后可移除本行回归 BOM 托管
    implementation(libs.compose.material3.alpha)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    // Wave 12 依赖治理：biometric 稳定渠道最新为 1.1.0（1.2.x/1.4.x 均为 alpha，
    // 官方发布页 2026-04 确认；Authenticators/setAllowedAuthenticators/CryptoObject 均已覆盖），
    // 消除安全关键组件的 alpha 依赖；credentials 升至稳定版 1.6.0（1.5.0 bugfix：isConditional 传播修复）
    implementation(libs.biometric)

    implementation(libs.hilt.android)
    // TASK-03：kapt → KSP（内置 Kotlin 不支持 kapt）
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.credentials)
    // TASK-04 整改：设置持久化迁移 Preferences DataStore（含 SharedPreferences 一次性迁移器）
    implementation(libs.datastore.preferences)
    // TASK-06 整改：Baseline Profile 运行期安装器——首启后异步触发 ART 配置文件安装
    implementation(libs.profileinstaller)
    // TASK-08 整改：周期性后台同步调度（设置项 periodicBackgroundSyncEnabled 此前无消费方）
    implementation(libs.work.runtime.ktx)
    // Autofill IME 内联建议（官方 androidx.autofill.inline v1 内容模型）：
    // 服务侧 InlineSuggestionUi Slice 构建自 1.1.0 起可用，1.3.0 为当前稳定版
    implementation(libs.autofill)
    // TOTP 二维码扫描（断点5 整改：扫码按钮真实化）
    implementation(libs.zxing.embedded)

    debugImplementation(libs.compose.ui.tooling)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
