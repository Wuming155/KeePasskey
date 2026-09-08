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

        // 扫描内部错误（含 NVD 数据更新失败）不阻断构建（本地验证中，暂不提交）：
        // 仓库未配置 NVD_API_KEY Secret 时，插件 13.0.0 对空密钥直接抛
        // NvdApiException("Invalid API Key, length of 0")，若不放行会使每次
        // 扫描都在 NVD 更新阶段失败、报告无法产出。置 false 后 NVD 更新失败
        // 仅记 error 日志，扫描以其余数据源（KEV / 托管抑制 / 本地缓存）继续，
        // 与「首次仅告警、恒不阻断」策略对齐。配置 NVD_API_KEY 后数据即完整。
        failOnError = false

        // NVD 数据源双通道（本地验证中，暂不提交）：
        // ① 配置了环境变量 NVD_API_KEY（GitHub Secret 同名注入 workflow）→ 走
        //    NVD 官方 API 实时通道；
        // ② 未配置 → 回落 dependency-check 官方托管镜像 datafeed（24h 尽力而为
        //    更新，绕开 API Key 与限流），CI 在无 Secret 时仍能完整跑通：
        //    https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html
        // 背景：13.0.0 在无 Key 时 NVD API 匿名拉取必然抛 NvdApiException
        // ("Invalid API Key, length of 0")，failOnError 不覆盖该阶段；
        // autoUpdate=false 又会因空库抛 NoDataException——故必须有数据源兜底。
        // 注意：不使用 workflow 的 -Dorg.owasp.dependencycheck.nvd.api.key 系统
        // 属性传参（该属性名在 13.0.0 新 Property API 下是否生效未验证），统一以
        // 环境变量为唯一事实源。
        nvd {
            val envKey = System.getenv("NVD_API_KEY")
            if (!envKey.isNullOrBlank()) {
                apiKey = envKey
            } else {
                datafeedUrl = "https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz"
            }
        }

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
