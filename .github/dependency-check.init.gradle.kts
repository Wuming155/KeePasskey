// TASK-20（P3）：OWASP Dependency-Check 扫描用 Gradle init 脚本。
//
// 由 .github/workflows/dependency-scan.yml 以 `./gradlew dependencyCheckAggregate -I 本文件`
// 注入，仅存在于 CI 扫描作业内——**不进入常驻构建**，开发者本地构建 / 常规 test /
// assemble 任务完全不受影响（AGP 9 要求 Java 17+ 与本仓库 toolchain 一致）。
//
// 插件版本以 initscript classpath 在此钉死（当前 13.0.0）；Dependabot 不解析 init 脚本
// classpath，升级依赖巡检插件需手动同步此处版本号。
//
// ISSUE-P3-09（ZT-20）AC4：本脚本已从「仅告警」改为「真实阻断」（failBuildOnCVSS=7.0 /
// failOnError=true）。注意它能生效的前提是 workflow 侧不再以 continue-on-error 吞掉失败。

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
        // ISSUE-P3-09（ZT-20）AC4：失败阈值由「恒不阻断」（11.0 超出 CVSS 上限 10.0，
        // 等价于永不失败）收紧为 **CVSS ≥ 7.0（HIGH 及以上）即阻断构建**。
        // 分级依据：0.1-3.9 LOW / 4.0-6.9 MEDIUM / 7.0-8.9 HIGH / 9.0-10.0 CRITICAL；
        // 密码管理器属高价值目标，HIGH 及以上不容许「带病发布」。
        // 误报处置：经人工核实后写入 .github/owasp-dependency-suppressions.xml
        // （该文件当前为空白名单，即尚无任何豁免），并按其维护纪律登记核实依据。
        // 本阈值改动**未在本环境验证**（需联网执行完整 NVD 数据同步）——若 CI 首次运行
        // 因存量依赖高危告警而红，正确处理是「修依赖或登记误报」，**不得回调阈值**。
        failBuildOnCVSS = 7.0f
        formats = mutableListOf("HTML", "SARIF", "JSON")

        // ISSUE-P3-09（ZT-20）AC4：扫描内部错误由「放行」改为 **fail-closed 阻断**。
        // 语义：扫描未能完成 = 供应链结论未知 ≠ 安全通过。此前置 false 的依据是
        // 「未配置 NVD_API_KEY 时插件 13.0.0 会抛 NvdApiException("Invalid API Key, length of 0")」，
        // 而该路径已由下方 nvd{} 的托管 datafeed 兜底（无 Key 时走官方镜像），
        // 故不再需要以「静默放行」换取「报告产出」。
        // 若 CI 因数据源不可达而红，处置方式是补 NVD_API_KEY / 修数据源，而非回退本项。
        failOnError = true

        // NVD 数据源双通道（**已入库生效**；原「本地验证中，暂不提交」的注释已随 ISSUE-P3-24
        // CI 静态校准更正——该状态描述与库内代码不符，属滞后注释）：
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

        // 扫描面 = **生产可运行面**（含 androidTest 编译/运行面）。
        // 为什么必须显式列出：插件对**全部可解析配置**做扫描，而 AGP 会为自带的
        // 测试 / lint / 截图校验工具链创建一批配置——其产物只在构建机 JVM 上运行，
        // 永不进入 APK，其构件（UTP / lint 工具自身）也**不是本仓声明的依赖**。
        //
        // ISSUE-P2-219 实测（2026-09-20，本地同参数 `dependencyCheckAggregate` +
        // `python .github/check_dependency_cvss.py`，得出与 CI 完全一致的
        // 「漏洞实例 1316 条 / 达阈（CVE × 构件）948 条」）：948 条**全部**落在下表新增的
        // AGP 工具链配置族上（35 个构件：netty 4.1.93 / 4.1.110、grpc 1.57.2、
        // kotlin-stdlib 1.9.0、protos 32.2.1、protobuf-java-util 3.22.3、jsoup 1.6.3），
        // 生产 runtime / compile 面为 **0 条**（逐构件的承载配置与引入者见批次证据表）。
        // 故此处按「扫描面收口」处置（而非为 AGP 内部件逐条维护 CVE 白名单），
        // 口径已登记于 docs/architecture/已知工程限界.md；机检由
        // SupplyChainScanSurfaceTest 守卫（工具链族必须在 skip 内、生产配置族不得混入）。
        //
        // 配置族名由 AGP 生成、随 AGP 版本稳定（本仓 AGP 版本钉死）；若将来 AGP 升级
        // 引入**新**的工具链配置名，闸门会以「新增达阈条目」**响亮报红**（fail-closed），
        // 届时按同一原则补入本表并复核——绝不静默放宽。
        skipConfigurations = mutableListOf(
            "androidTestDebugCompileClasspath",
            "androidTestDebugRuntimeClasspath",
            "androidTestCompileClasspath",
            "lintClassPath",
            "lintChecks",
            // === AGP 测试工具链：Unified Test Platform（UTP）各构件解析面 ===
            "unified-test-platform-core",
            "unified-test-platform-launcher",
            "unified-test-platform-gradle-work-action",
            "unified-test-platform-android-test-plugin",
            "unified-test-platform-android-device-provider-ddmlib",
            "unified-test-platform-android-driver-instrumentation",
            "unified-test-platform-android-test-plugin-host-additional-test-output",
            "unified-test-platform-android-test-plugin-host-apk-installer",
            "unified-test-platform-android-test-plugin-host-coverage",
            "unified-test-platform-android-test-plugin-host-device-info",
            "unified-test-platform-android-test-plugin-host-emulator-control",
            "unified-test-platform-android-test-plugin-host-logcat",
            "unified-test-platform-android-test-plugin-result-listener-gradle",
            // === Android Lint 工具自身（`lint-gradle` 及其传递面）===
            "androidLintTool",
            // === AGP 截图测试（screenshotTest）内部工具面 ===
            "_internal-screenshot-test-task-layoutlib",
            "_internal-screenshot-test-task-layoutlib-res",
            "_internal-screenshot-validation-junit-engine"
        )
    }
}
