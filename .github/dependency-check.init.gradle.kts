// TASK-20（P3）：OWASP Dependency-Check 扫描用 Gradle init 脚本。
//
// 由 .github/workflows/dependency-scan.yml 以 `./gradlew dependencyCheckAggregate -I 本文件`
// 注入，仅存在于 CI 扫描作业内——**不进入常驻构建**，开发者本地构建 / 常规 test /
// assemble 任务完全不受影响（AGP 9 要求 Java 17+ 与本仓库 toolchain 一致）。
//
// 插件版本以 initscript classpath 在此钉死（当前 13.0.0）；Dependabot 不解析 init 脚本
// classpath，升级依赖巡检插件需手动同步此处版本号。

initscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.owasp:dependency-check-gradle:13.0.0")
    }
}

// 应用到全部项目（root + app/core/crypto/database/sync 5 模块），
// 以便 root 上的 dependencyCheckAggregate 汇总各模块解析后的依赖图。
allprojects {
    // init script classpath 不参与按 plugin id 的查找，必须按实现类显式 apply
    pluginManager.apply(org.owasp.dependencycheck.gradle.DependencyCheckPlugin::class.java)

    configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        // 失败阈值策略：首次仅告警——CVSS 11.0 恒不阻断构建。
        // 误报白名单经 suppression 文件登记（见 .github/owasp-dependency-suppressions.xml），
        // 待白名单稳定后再评估收紧为 high(>=7)/critical(>=9) blocking。
        failBuildOnCVSS = 11.0f
        formats = mutableListOf("HTML", "SARIF", "JSON")

        // suppression 白名单相对仓库根解析（各项目 rootProject 同指仓库根）
        suppressionFiles = mutableListOf(
            rootProject.file(".github/owasp-dependency-suppressions.xml").absolutePath
        )

        // 跳过 AGP instrumentation / lint 类派生配置：非生产运行面，压缩扫描面与耗时。
        // （配置名不存在时插件自动忽略，向后兼容不同 AGP 变体配置命名）
        skipConfigurations = mutableListOf(
            "androidTestDebugCompileClasspath",
            "androidTestDebugRuntimeClasspath",
            "androidTestCompileClasspath",
            "lintClassPath",
            "lintChecks"
        )
    }
}
