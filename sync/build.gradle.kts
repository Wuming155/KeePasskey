plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.keepasskey.sync"
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
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
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
