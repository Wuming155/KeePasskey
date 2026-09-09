// 顶层构建文件：仅声明插件版本，模块级配置放在各自 build.gradle.kts 中。
buildscript {
    // TASK-03：AGP 9 内置 Kotlin 默认使用其内嵌 KGP（AGP 9.0 为 2.2.10）。
    // 本项目锁定 Kotlin 2.4.10（与 Compose 编译器插件严格对齐），
    // 按官方迁移指南经 buildscript classpath 提升内置 Kotlin 所用的 KGP 版本。
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
