plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasskey.database"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        // ISSUE-P3-23：database 模块首次引入 androidTest（instrumented）源集。
        // 动因：ISSUE-P3-11 验收标准 2（真实 KeePass / KeePassXC Argon2 `.kdbx` 语料端到端解锁）
        // **不可能**落在 crypto 模块——模块依赖严格单向（crypto 不依赖 database），
        // crypto 不具备 `.kdbx` 读写能力；该用例只能落在唯一具备 KdbxFile 读写能力的 database 模块。
        // 与 crypto/build.gradle.kts 的既有接线保持同一范式。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // androidTest 的 assets 源目录无需显式声明：AGP 默认即 `src/androidTest/assets`
    //（真实 `.kdbx` 语料落位 `database/src/androidTest/assets/argon2-interop/`，
    // 见 crypto/src/test/resources/argon2-interop/README.md §3.4 —— `src/test/resources`
    // **不会**打进 androidTest APK，设备侧只能读 assets）。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// TASK-03：内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（JVM 17）

dependencies {
    implementation(project(":core"))
    // database 的接口签名会暴露 crypto 类型（如 KdfParameters），需用 api 传递
    api(project(":crypto"))
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    implementation(libs.coroutines.core)
    // P3-9 整改：@VisibleForTesting 注解（setDatabaseForTesting 测试后门显式约束）
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    // ISSUE-P3-23：androidTest（instrumented）源集依赖 —— 与 crypto/build.gradle.kts 的既有接线
    // 保持同一范式，**仅 androidx.test.***，不引入其他第三方库（伴生元数据 JSON 用平台内置 org.json 解析，
    // 无需额外坐标）。缺此两行则 androidx.test.ext.junit.runners.AndroidJUnit4 /
    // androidx.test.platform.app.InstrumentationRegistry 无法解析，androidTest 源集整体编译失败。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// ISSUE-P3-34/35/36：把 :crypto 的**宿主原生库**注入 database 模块的单测 JVM。
//
// 动因：database 是 `PasswordStrengthEvaluator`（ISSUE-P3-36）的唯一消费方，也是
// `KdbxFile` 端到端读写的宿主；若不注入，database 侧单测只会覆盖**降级路径**，
// 原生内核在该层等于零集成覆盖。与 `crypto/build.gradle.kts` 的既有接线保持同一范式
// （`cargoHostBuild` 未安装 cargo 时自跳过 → 目录为空 → 各绑定 `available=false` 自动降级，不阻断）。
val cryptoNativeHostLibDir = rootProject.layout.projectDirectory
    .dir("crypto/build/rust/host/release")

tasks.withType<Test>().configureEach {
    dependsOn(":crypto:cargoHostBuild")
    jvmArgs(
        "-Djava.library.path=" + listOfNotNull(
            System.getProperty("java.library.path"),
            cryptoNativeHostLibDir.asFile.absolutePath
        ).joinToString(File.pathSeparator)
    )
}
