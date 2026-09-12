import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// 自动签名的发布密钥库配置：支持从 keystore.properties 或环境变量加载
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val releaseStoreFilePath: String? = System.getenv("KEYSTORE_FILE")
    ?: keystoreProperties.getProperty("storeFile")
val releaseStorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS")
    ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")
    ?: keystoreProperties.getProperty("keyPassword")

val releaseStoreResolvedFile: File? = releaseStoreFilePath?.let { path ->
    val f = File(path)
    if (f.isAbsolute) f else rootProject.file(path)
}

val hasReleaseSigning = releaseStoreResolvedFile?.exists() == true &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.keepasskey.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.keepasskey"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // ISSUE-P2-27 / P3-66：app 层设备侧（instrumented）验证入口——此前 app 无 androidTest 源集，
        // 导致「JVM 过、Android 运行时挂」类缺陷（§24 / §26）无设备侧拦截。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreResolvedFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // ISSUE-P3-09（ZT-20）AC1：签名方案收敛为 v2 + v3 + v4，关闭 v1。
                // - 关闭 v1（JAR 签名）：本应用 minSdk 36，Android 7.0(API 24) 起系统只认
                //   APK Signature Scheme v2+，v1 对安装/校验毫无作用；它同时是三层里最弱的
                //   一层（逐条目 SHA-1/SHA-256 摘要 + 与 ZIP 结构耦合，历史上有 Janus
                //   CVE-2017-13156 一类绕过），保留只会白白扩大签名面与包体。
                // - 启用 v3：携带 proof-of-rotation 签名谱系，支持**发布密钥轮换**——
                //   将来换密钥时旧版本仍可验签升级，v2 方案不具备该能力（换钥匙即断更）。
                // - 启用 v4：额外产出 .idsig 文件，供 adb 增量安装（--incremental）与商店
                //   做安装前完整性校验；仅为附加产物，不改变 APK 本体。
                // 说明：本块整体位于 hasReleaseSigning 判定内。未配置签名（既无
                // keystore.properties 也无 KEYSTORE_* 环境变量）时不会创建该 signingConfig，
                // AGP 照常产出未签名包，assembleRelease 不会因此失败。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // ISSUE-P1-10 (ZT-10)：供 MainApplication 以 BuildConfig.DEBUG 置位 AppLog 调试开关
        buildConfig = true
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
    // ISSUE-P3-09（ZT-20）AC3：material3 1.5.0-alpha27 显式覆盖 BOM 映射——**受控保留 alpha**。
    // 依据（2026-09-10 核对 Google Maven androidx/compose/material3/material3/maven-metadata.xml）：
    //   latest = release = 1.5.0-alpha28；1.5.0 线**尚无 stable**，稳定渠道最新为 1.4.0。
    // 为何不能降级：本项目 ui/theme/Theme.kt 使用 MaterialExpressiveTheme 与
    //   MotionScheme.expressive()（M3 Expressive 动效），这些公开 API 在 1.4.0 stable 中
    //   仍为 internal，降级会直接编译失败。
    // 风险：alpha 渠道存在 API 破坏性变更与回归缺陷，且不属于「稳定版生产依赖」。
    // 退出条件：material3 1.5.0 正式版（stable）发布后，删除本行与
    //   gradle/libs.versions.toml 的 compose-material3-alpha 别名 / material3 版本项，
    //   回归 BOM 托管（届时 Theme.kt 无需改动，M3 Expressive API 已在 stable 中公开）。
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
    // ISSUE-P2-27 / P3-66：app 层设备侧（instrumented）测试依赖（与 crypto / database 同源版本）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
    // TASK-47：已泄露密码检测（HIBP k-匿名范围查询）——范围查询客户端与 MockWebServer 回归
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}
