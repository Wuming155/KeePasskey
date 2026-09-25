import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // 本地开发：把 main 源集全部 @Preview 渲染为 PNG（与 GUI导航.md 配套）
    alias(libs.plugins.compose.screenshot)
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

// =============================================================================================
// ISSUE-P2-55（审计 F-06）AC③：**发布签名口令的构建期断言**（fail-closed，不可绕过）。
//
// 缺陷背景：`keystore.properties.example` 曾公开示例口令 `keepasskey123`，而本地/流水线
// 直接照抄该值签名 —— 示例值随公开仓库泄露，等于把发布密钥交给任何人（可用任意 APK
// 冒充官方渠道包，且签名校验永远通过）。
//
// 断言策略（仅在实际配置了签名时生效；未配置签名的未签名构建不受影响）：
//   1. 口令长度 ≥ `MIN_RELEASE_PASSWORD_LENGTH`（16）——排除"随手敲"的低熵值；
//   2. 不得命中 `FORBIDDEN_RELEASE_PASSWORDS`（历史公开示例值与常见弱口令，比较时忽略大小写）；
//   3. 不得包含模板占位符标记（防止有人直接把 example 拷过来）。
// 违例即 `error(...)`：**构建在配置阶段直接失败**，绝不产出用泄露口令签名的"稳定版"。
//
// 豁免通道：**不存在**——不得通过调低阈值、删断言或改用示例值来变绿；口令只能来自
// 本地 `keystore.properties`（已 gitignore）或 CI Secret（KEYSTORE_PASSWORD / KEY_PASSWORD）。
// =============================================================================================
val minReleasePasswordLength = 16
val forbiddenReleasePasswords = setOf(
    "keepasskey123", // 历史公开示例值（审计 F-06 的直接对象）
    "password", "passw0rd", "changeme", "changeit", "secret", "admin", "123456", "12345678",
)
val releasePasswordPlaceholderMarker = "__REPLACE_WITH"

if (hasReleaseSigning) {
    listOf(
        "storePassword（keystore.properties / KEYSTORE_PASSWORD）" to releaseStorePassword!!,
        "keyPassword（keystore.properties / KEY_PASSWORD）" to releaseKeyPassword!!,
    ).forEach { (label, password) ->
        val normalized = password.trim()
        // 顺序有意为之：先判「是否命中已公开值 / 占位符」（F-06 的精确场景），
        // 再判长度——否则历史示例值会先被"过短"吞掉，丢失"该口令已随仓库泄露"这一关键诊断。
        if (normalized.lowercase() in forbiddenReleasePasswords) {
            error(
                "发布签名口令命中**已公开的示例 / 弱口令**（$label）——该值随公开仓库泄露，" +
                    "用它签名等于发布密钥失控。请对既有密钥库**只 re-key、不换密钥**" +
                    "（keystore.properties.example 内有完整 keytool 命令），再更新本地配置（ISSUE-P2-55）。",
            )
        }
        if (normalized.contains(releasePasswordPlaceholderMarker)) {
            error(
                "发布签名口令仍为模板占位符（$label）——请复制 keystore.properties.example 后填入真实高熵口令（ISSUE-P2-55）。",
            )
        }
        if (normalized.length < minReleasePasswordLength) {
            error(
                "发布签名口令过短（$label 长度 ${normalized.length} < $minReleasePasswordLength）——" +
                    "请使用高熵口令；见 keystore.properties.example 的 re-key 说明（ISSUE-P2-55）。",
            )
        }
    }
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

    androidResources {
        // ISSUE-P1-238（2026-09-21 真机实测）：PSL 数据文件（`src/main/resources/publicsuffix/`）
        // 此前在 APK 内以 DEFLATE 存放（334 KB → 90 KB），读取需解压 + 流式搬运，实测
        // **约 0.13 s**；而该文件正处在凭据提供者约 3.0 s 的应答预算内被同步读取。
        // 改为**不压缩**存放后读取退化为直接搬运（实测约 0.03 s）。代价是 APK 增大约 0.25 MB
        // （本包原本即 95 MB 级）。该开关只影响打包方式，不改变资源内容与访问路径。
        noCompress += "dat"
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

    // Compose Preview 截图测试（AGP 实验特性）。
    // 旗标必须同时满足两处：根 gradle.properties（插件 apply 早期读取）
    // + 本 android 块（插件校验模块级开关）。
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
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
    // ISSUE-P3-319：TOTP 扫码栈——CameraX 取景 + zxing:core 纯算法 QR 解码，
    // 替代停更的 zxing-android-embedded（其取景建立在废弃的 Camera1 API 上）
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)

    // Compose Preview 截图测试渲染依赖（仅 screenshotTest 源集；与 debug tooling 同源）
    screenshotTestImplementation(libs.compose.ui.tooling)
    // 引擎 MethodSelectorResolver 只认 @PreviewTest（screenshot-validation-api）
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // ISSUE-P2-27 / P3-66：app 层设备侧（instrumented）测试依赖（与 crypto / database 同源版本）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
    // ISSUE-P2-192 余量第 2 项：平台网络策略设备侧证明（明文拦截 + 自签证书信任锚拒绝）
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    // ISSUE-P2-192 余量第 4 项：WorkManager 周期同步设备侧调度/执行用例
    androidTestImplementation(libs.work.testing)
    // TASK-47：已泄露密码检测（HIBP k-匿名范围查询）——范围查询客户端与 MockWebServer 回归
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}

// =============================================================================================
// 本地开发：Compose @Preview 截图导出（gitignore）。两个入口：
//   主页面 → preview-exports/main/{light,dark}/         20 整屏 × 明暗
//   次要   → preview-exports/secondary/{light,dark}/    其余（卡片/对话框/分节等），扁平文件名
// 命令：`:app:exportMainPreviewScreenshots` / `:app:exportSecondaryPreviewScreenshots`
// 或双击根目录「导出预览图-主页面.bat」/「导出预览图-次要.bat」
// =============================================================================================
val previewExportsMainDir = rootProject.layout.projectDirectory.dir("preview-exports/main")
val previewExportsSecondaryDir = rootProject.layout.projectDirectory.dir("preview-exports/secondary")
val previewRenderedDir = layout.buildDirectory.dir("outputs/screenshotTest-results/preview/debug/rendered")

// 主页面预览函数名（整屏 / 主内容；浅色+深色各一张）
val mainScreenPreviewPrefixes = listOf(
    "UnlockContentPreviewScreenshotExport",
    "DatabasePickerContentPreviewScreenshotExport",
    "VaultListContentPreviewScreenshotExport",
    "EntryDetailContentPreviewScreenshotExport",
    "EntryEditContentPreviewScreenshotExport",
    "GeneratorContentPreviewScreenshotExport",
    "TotpLargeCardPreviewScreenshotExport",
    "SettingsContentPreviewScreenshotExport",
    "DatabaseSettingsScreenPreviewScreenshotExport",
    "CloudSyncScreenPreviewScreenshotExport",
    "WebDavSyncScreenPreviewScreenshotExport",
    "AutofillSettingsScreenPreviewScreenshotExport",
    "SecuritySettingsScreenPreviewScreenshotExport",
    "ThemeSettingsScreenPreviewScreenshotExport",
    "TotpSettingsScreenPreviewScreenshotExport",
    "HealthCheckScreenPreviewScreenshotExport",
    "DebugSettingsScreenPreviewScreenshotExport",
    "AboutSettingsScreenPreviewScreenshotExport",
    "AutofillPickerScreenPreviewScreenshotExport",
    "CredentialFillConfirmScreenPreviewScreenshotExport",
)

/** 依文件名中的浅色/深色（或 Light/Dark）归入 light/ 或 dark/ 子目录 */
fun org.gradle.api.file.FileCopyDetails.routeByTheme() {
    val n = name
    val theme = when {
        n.contains("深色") || n.contains("Dark", ignoreCase = true) -> "dark"
        n.contains("浅色") || n.contains("Light", ignoreCase = true) -> "light"
        else -> "light"
    }
    path = "$theme/$n"
}

tasks.register<Sync>("exportMainPreviewScreenshots") {
    group = "verification"
    description = "导出主页面 @Preview 到 preview-exports/main/{light,dark}/（扁平文件名）"
    dependsOn("updateDebugScreenshotTest")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(previewRenderedDir) {
        mainScreenPreviewPrefixes.forEach { prefix ->
            include("**/${prefix}_*.png")
        }
        includeEmptyDirs = false
        eachFile { routeByTheme() }
    }
    into(previewExportsMainDir)
    doLast {
        val light = previewExportsMainDir.dir("light").asFile.walkTopDown()
            .count { it.isFile && it.extension.equals("png", true) }
        val dark = previewExportsMainDir.dir("dark").asFile.walkTopDown()
            .count { it.isFile && it.extension.equals("png", true) }
        logger.lifecycle("已导出 主页面: light=$light dark=$dark → ${previewExportsMainDir.asFile.absolutePath}")
    }
}

tasks.register<Sync>("exportSecondaryPreviewScreenshots") {
    group = "verification"
    description = "导出次要界面（非 20 主屏）@Preview 到 preview-exports/secondary/{light,dark}/"
    dependsOn("updateDebugScreenshotTest")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(previewRenderedDir) {
        include("**/*.png", "**/*.webp")
        // 排除主页面白名单
        mainScreenPreviewPrefixes.forEach { prefix ->
            exclude("**/${prefix}_*.png")
        }
        includeEmptyDirs = false
        eachFile { routeByTheme() }
    }
    into(previewExportsSecondaryDir)
    doLast {
        val light = previewExportsSecondaryDir.dir("light").asFile.walkTopDown()
            .count { it.isFile && it.extension.equals("png", true) }
        val dark = previewExportsSecondaryDir.dir("dark").asFile.walkTopDown()
            .count { it.isFile && it.extension.equals("png", true) }
        logger.lifecycle("已导出 次要: light=$light dark=$dark → ${previewExportsSecondaryDir.asFile.absolutePath}")
    }
}

