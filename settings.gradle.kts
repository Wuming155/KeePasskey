pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "KeePasskey"

// 5 个模块，先保持粗粒度，确有隔离需求（如同步后端 SDK 冲突）再拆分
include(":app")        // 应用壳：Compose UI、导航、Hilt、平台集成（biometric / autofill / passkey）
include(":core")       // 共享基础：领域模型、工具、DI 限定符，不依赖任何模块
include(":crypto")     // 加密层：分组加密、KDF、KDBX 块流，仅依赖 core
include(":database")   // 数据库层：kdbx 解析/序列化/搜索/合并/OTP，依赖 core + crypto
include(":sync")       // 同步层：文件存储抽象 + WebDAV / S3 实现，依赖 core
