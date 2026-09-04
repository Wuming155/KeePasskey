// 顶层构建文件：仅声明插件版本，模块级配置放在各自 build.gradle.kts 中。
// 注意：版本号需与本地 Android Gradle Plugin / Kotlin 匹配，首次导入后按需微调。
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.10" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 一同发布，版本必须与 Kotlin 保持一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}
