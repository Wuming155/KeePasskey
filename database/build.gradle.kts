import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keepasskey.database"
    compileSdk = 36
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.2 起 android.kotlinOptions 已移除，统一使用 kotlin.compilerOptions
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core"))
    // database 的接口签名会暴露 crypto 类型（如 KdfParameters），需用 api 传递
    api(project(":crypto"))
    // P3-6 整改：移除从未使用的 androidx.core:core-ktx（三模块源码零 androidx 导入）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // P3-9 整改：@VisibleForTesting 注解（setDatabaseForTesting 测试后门显式约束）
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
