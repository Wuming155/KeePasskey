import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keepasskey.sync"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// 本地 HTTPS 服务联调开关：仅当 -DliveSyncTest 存在时，将 live* 参数转发到测试 JVM。
// 普通 CI 不携带该属性，测试默认跳过，不影响常规构建。
tasks.withType<Test>().configureEach {
    val live = System.getProperty("liveSyncTest")
    if (live != null) {
        systemProperty("liveSyncTest", live)
        systemProperty("liveWebdavUrl", System.getProperty("liveWebdavUrl", "https://localhost:9443"))
        systemProperty("liveWebdavUser", System.getProperty("liveWebdavUser", "tester"))
        systemProperty("liveWebdavPass", System.getProperty("liveWebdavPass", "tester123"))
        systemProperty("liveS3Endpoint", System.getProperty("liveS3Endpoint", "https://localhost:9000"))
        systemProperty("liveS3Bucket", System.getProperty("liveS3Bucket", "keepasskey-test"))
        systemProperty("liveS3Access", System.getProperty("liveS3Access", "tester"))
        systemProperty("liveS3Secret", System.getProperty("liveS3Secret", "tester1234"))
        systemProperty("liveCertPath", System.getProperty("liveCertPath", ""))
    }
}
