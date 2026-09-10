// Gradle Kotlin DSL 脚本的隐式导入集与项目源码不同：`java.util.*` 不在默认导入内，
// 必须显式 import 后才能使用 UUID（否则脚本编译期即 Unresolved reference）。
import java.util.UUID

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

/**
 * ISSUE-P3-10 子项 4：联调凭据解析结果（值 + 来源描述）。
 * [value] 绝不入日志，[source] 可入日志——「凭据从哪来」必须可核验，凭据本身不得落痕。
 */
data class LiveCredential(val value: String, val source: String)

/**
 * 解析本地联调凭据：JVM 属性 → 环境变量 → 随机一次性口令。
 *
 * 安全要求（ISSUE-P3-10 子项 4）：构建脚本与测试源码中**不得再出现任何固定弱口令默认值**
 * （原 `tester123` / `tester1234` 已移除）；未注入时随机生成 32 位十六进制一次性口令，
 * 构建与测试运行不中断（可用降级），但需与服务端注入同一组值才能连通。
 *
 * 单一真源数据流：本函数只在 Gradle 配置期求值**一次**，结果经 `systemProperty` 下发到测试 JVM；
 * `LiveSyncServersTest` 只读该属性（自身仅在属性缺失时随机降级，即 IDE 直跑场景），
 * 因此同一次联调中「测试端输入的口令」不存在第二个随机源。
 *
 * 环境变量名对齐服务端自身契约：`WEBDAV_USER` / `WEBDAV_PASSWORD`（`tools/local-sync/run_webdav.py`）
 * 与 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`（MinIO），两侧注入同名变量即可一致。
 */
fun liveCredential(propertyName: String, envName: String): LiveCredential {
    System.getProperty(propertyName)?.let { return LiveCredential(it, "-D$propertyName") }
    System.getenv(envName)?.let { return LiveCredential(it, "环境变量 $envName") }
    val generated = "kp" + UUID.randomUUID().toString().replace("-", "")
    return LiveCredential(generated, "未注入（已随机生成一次性口令，需与服务端一致）")
}

// 本地 HTTPS 服务联调开关：仅当 -DliveSyncTest 存在时，将 live* 参数转发到测试 JVM。
// 普通 CI 不携带该属性，测试默认跳过，不影响常规构建。
tasks.withType<Test>().configureEach {
    val live = System.getProperty("liveSyncTest")
    if (live != null) {
        val webdavUser = liveCredential("liveWebdavUser", "WEBDAV_USER")
        val webdavPass = liveCredential("liveWebdavPass", "WEBDAV_PASSWORD")
        val s3Access = liveCredential("liveS3Access", "MINIO_ROOT_USER")
        val s3Secret = liveCredential("liveS3Secret", "MINIO_ROOT_PASSWORD")

        systemProperty("liveSyncTest", live)
        systemProperty("liveWebdavUrl", System.getProperty("liveWebdavUrl", "https://localhost:9443"))
        systemProperty("liveWebdavUser", webdavUser.value)
        systemProperty("liveWebdavPass", webdavPass.value)
        systemProperty("liveS3Endpoint", System.getProperty("liveS3Endpoint", "https://localhost:9000"))
        systemProperty("liveS3Bucket", System.getProperty("liveS3Bucket", "keepasskey-test"))
        systemProperty("liveS3Access", s3Access.value)
        systemProperty("liveS3Secret", s3Secret.value)
        systemProperty("liveCertPath", System.getProperty("liveCertPath", ""))

        logger.lifecycle(
            "[liveSyncTest] 联调凭据来源（仅来源入日志，凭据值不落痕）: " +
                "WebDAV 用户=${webdavUser.source} / WebDAV 口令=${webdavPass.source} / " +
                "S3 AccessKey=${s3Access.source} / S3 SecretKey=${s3Secret.source}"
        )
    }
}
