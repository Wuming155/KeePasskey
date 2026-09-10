# CI 静态校准记录（ISSUE-P3-24 · 独立工作流 G）

> **核实时间点**：2026-09-10 15:10 ~ 15:35（UTC+8，本机时间）
> **核实方式**：本机只读证据（Android SDK 安装目录、Gradle daemon 日志、已构建 Release 产物 + `apksigner`/`aapt2` 实跑、
> `.git/index` 二进制解析）、联网只读核实（GitHub REST API、Google Maven / crates.io / rust-lang 官方接口、
> `sdkmanager --list` 实测远端包清单）、以及 Python/PyYAML 对 workflow 的语法与结构解析。
> **边界声明**：本环境**无法运行 CI**（无 GitHub Actions runner、无 Linux 宿主）。
> 本记录中**没有任何**「CI 已跑通」的结论；所有判定均为静态推演或本机等价产物证据。
> 本工作流**未执行任何 Gradle 命令、未执行任何 git 命令**（未 add / commit / push）。

---

## 1. 变更明细（4 处 / 2 文件，全部有确凿证据）

| # | 文件:行 | 原值 → 新值 | 证据（时间 + 方式） | 判定理由 |
|---|---|---|---|---|
| 1 | `.github/workflows/build.yml:52`（env `ANDROID_PLATFORM`） | `"android-37"` → `"android-37.0"` | ① 2026-09-10 本机 `sdkmanager --list --channel=0`：远端仅存在 `platforms;android-37.0`(rev2) / `37.1` / `37.2` / `37.2-beta*`，**无** `platforms;android-37`；② 本机 `D:\Android\SDK\platforms\android-37.0\package.xml` 声明 `path="platforms;android-37.0"`，`source.properties` 为 `AndroidVersion.ApiLevel=37.0`、`Pkg.Revision=2`；③ 本机已装平台目录名即 `android-37.0`（无 `android-37`） | 原值会让 `sdkmanager --install "platforms;android-37"` 报「Failed to find package」，且 `compileSdk = 37` 无平台可解析 → native-gate 首次运行必红。属**确凿缺陷修正**，非推测 |
| 2 | `.github/workflows/build.yml:178-208`（签名方案断言块） | ① `apksigner verify --verbose "$apk"` → 加 `--v4-signature-file "${apk}.idsig"`；② 删除「v2 : true」断言 → 改为「v2 块必须**缺席**」的显式断言（并在命中时 `::error::` 退出）；③ 新增 `v1 scheme ...: false` 断言；④ 把 `test -f "${apk}.idsig"` 前移到 apksigner 之前 | 2026-09-10 本机实跑 build-tools **37.0.0** `apksigner.bat verify --verbose` 于真实签名产物 `app/build/outputs/apk/release/app-release.apk`（同日 14:58 构建，签名配置与本 CI 完全一致：`enableV1Signing=false` / v2/v3/v4=true / minSdk 36）：<br>• 不带 `--v4-signature-file` → `v1: false`、`v2: false`、`v3: true`、`v4: false`<br>• 带 `--v4-signature-file app-release.apk.idsig` → 同上但 **`v4: true`**<br>• 包内 `META-INF/*.(SF\|RSA\|DSA\|EC)` 条目数 = **0**；`lib/<4 ABI>/libkeepasskey_argon2.so` = **4 个**（434016/312652/505796/477976 B） | 原断言有两处**必然误红**：<br>(a) `apksigner` 不自动读取同目录 `.idsig`，不传 `--v4-signature-file` 时 v4 恒为 false → 原 `grep v4: true` 必失败；<br>(b) 本项目 `minSdk 36 ≥ 28` 且 v3 同开，AGP **省略 v2 块**（`AGENTS.md` §1「签名与 R8」行即为此基线）→ 原 `grep v2: true` 必失败。<br>修正后断言**未削弱**：v1 仍以「包内条目」+ apksigner 双证缺席；v2 由「不可能成立的 true」改为「必须缺席，出现即人工复核」；v3/v4 从「形式上存在但不可能通过」变为**真实可判** |
| 3 | `.github/workflows/build.yml:72-76`、`:109-113` 与 `.github/workflows/dependency-scan.yml:42-46`（`setup-java`） | `Set up JDK 17` / `java-version: "17"` → `Set up JDK 21` / `java-version: "21"` | ① `gradle/gradle-daemon-jvm.properties:2` = `toolchainVersion=21`（该文件由 Gradle `updateDaemonJvm` 机器生成，属仓库级硬约束）；② 本机 Gradle daemon 日志 `C:\Users\baiyun\.gradle\daemon\9.7.1\daemon-12336.out.log`：`javaHome=…jdk-21.0.11.10-hotspot, javaVersion=21`；本机 `java -version` = `21.0.11`，`JAVA_HOME` = jdk-21 —— 即本仓库**全部已获证据的构建/测试/打包产物都产自 JDK 21**；③ ubuntu-24.04 runner 工具集（`actions/runner-images` `toolset-2404.json`）`java.versions = [8,11,17,21,25]`、`default=17` | 原值使 CI 声明的 JDK（17）与 Gradle 守护进程**实际要求**的 JDK（21）不一致：要么 Gradle 依赖 runner 镜像自带的 JDK 21 自动探测/按需下载（不可控、且与 workflow 声明不符），要么直接失败。改为 21 后 CI 与「已验证可跑的开发机环境」严格对齐，且 `compileOptions`/`targetCompatibility` 仍为 17（JDK 21 编译到 17 字节码，本机实测成立）。<br>**残留不确定性（如实登记）**：Gradle 8.8+ 对 daemon JVM criteria 的失败/自动供给行为未在本环境实测；本次修改不依赖该行为成立（两种情形下改 21 都不劣于原值） |
| 4 | `.github/workflows/build.yml:20-28`（头部设计说明新增第 6 条）、`:44-51`（平台包名注释） | 仅注释（无行为变更） | 同 #1 / #3 | 使「为何是 android-37.0 / 为何是 JDK 21」的判据随文件留痕，避免后续被误改回 |

**未改动但需记入风险点的项**：见 §7（一律只记录、不改）。

---

## 2. Action SHA 真实存在性（7 个，逐条）

核实方式：`GET https://api.github.com/repos/<owner>/<repo>/git/ref/tags/<tag>`（或 `…/git/ref/heads/<branch>`），
将返回的 `object.sha` 与 workflow 中钉死的 SHA 逐字比对；注释型 tag 再经 `…/git/tags/<sha>` 解引用到 commit。
**未遇到 403 / 限流**（全部 HTTP 200，共 11 次请求）。

| owner/repo@SHA | 声明版本 | API 结果 | 判定 |
|---|---|---|---|
| `actions/checkout@11d5960a…677262` | `# v4` | `refs/tags/v4` → `11d5960a…677262`（commit） | ✅ 一致 |
| `actions/setup-java@cf277c60…f6c3` | `# v4` | `refs/tags/v4` → `cf277c60…f6c3`（commit） | ✅ 一致 |
| `gradle/actions/setup-gradle@ed408507…7933a` | `# v4.4.3` | `refs/tags/v4.4.3` 为**注释型 tag** `48b5f213…2947` → 解引用 commit = `ed408507…7933a` | ✅ 一致 |
| `actions/upload-artifact@ea165f8d…7fa02` | `# v4` | `refs/tags/v4` → `ea165f8d…7fa02`；且 `refs/tags/v4.6.2` → 同一 SHA | ✅ 一致（v4 线最新补丁） |
| `android-actions/setup-android@9fc6c4e9…65407` | `# v3` | `refs/tags/v3` → `9fc6c4e9…65407`（commit） | ✅ 一致 |
| `github/codeql-action/upload-sarif@faaca9a8…a2eca` | `# v3` | `refs/tags/v3` 为注释型 tag（2026-09-09 更新，**未签名**）→ 解引用 commit = `faaca9a8…a2eca` | ✅ 一致（tag 为移动标签，故 SHA 钉死是必要的） |
| `dtolnay/rust-toolchain@6bed0761…5ba87` | `# stable` | `refs/tags/stable` → **404（不存在该 tag）**；`refs/heads/stable` → `6bed0761…5ba87` | ✅ 一致——该 action 的 `stable` 是**分支**而非 tag，注释 `# stable` 描述正确 |

补充（`android-actions/setup-android@v3` 用法正确性，核实方式：jsDelivr 取该 tag 的 `README.md` 与 `action.yml`）：
- 默认 `packages: 'tools platform-tools'`、`accept-android-sdk-licenses: true`、`cmdline-tools-version: 12266719`（短版本 16.0，README「Version table」确有该映射）；
- 该 action 依赖/导出 `$ANDROID_SDK_ROOT` 并把 `cmdline-tools/<ver>/bin`（含 `sdkmanager`）加入 `$PATH`；
  这正是 build.yml 用裸 `sdkmanager` 与 `${ANDROID_SDK_ROOT}/…` 的前提 → 用法与官方 README 的 Basic 示例一致。

> 顺带核实（非 workflow 责任）：`gradlew` 在 `.git/index` 中的 mode = **100755（可执行）**，
> `gradle/wrapper/gradle-wrapper.jar` 已被跟踪 → Linux runner 上 `./gradlew …` 不会因权限或缺失 wrapper 而失败。
> （核实方式：以 Python 只读解析 `.git/index` 二进制；索引共 476 条，mode 分布 `100644×475 / 100755×1`，唯一可执行项即 `gradlew`。）

---

## 3. 版本字符串可用性（4 项，全部真实存在）

| 版本项 | 核实方式（2026-09-10） | 结果 |
|---|---|---|
| **NDK `28.2.13676358`** | `sdkmanager --list --channel=0` 实测远端 + 本机 `ndk/28.2.13676358/source.properties` | ✅ 真实（`Pkg.ReleaseName = r28c`，`Pkg.Revision = 28.2.13676358`；本机已安装且参与过已归档的 Release 构建） |
| **Rust `1.97.1`** | `GET https://api.github.com/repos/rust-lang/rust/releases/tags/1.97.1` | ✅ **真实且已发布**：`tag_name=1.97.1`、`name="Rust 1.97.1"`、`prerelease=false`、`published_at=2026-07-16T12:29:15Z`。**「尚未发布」的必然失败点不成立** |
| **cargo-ndk `4.1.2`** | `GET https://crates.io/api/v1/crates/cargo-ndk/4.1.2` | ✅ 真实：`created_at=2025-08-09`、`yanked=false`、`rust_version=1.86`（≤1.97.1 可编译）；本机 `cargo ndk --version` = `cargo-ndk 4.1.2` |
| **cargo-deny `0.20.2`** | `GET https://crates.io/api/v1/crates/cargo-deny/0.20.2` + docs.rs 源码清单 | ✅ 真实：`created_at=2026-07-09`、`yanked=false`、`rust_version=1.88.0`（≤1.97.1）；**发布包内含 `Cargo.lock`** → `cargo install … --locked` 可成立；本机 `cargo deny --version` = `cargo-deny 0.20.2` |

补充：本机 `cargo --version` = **cargo 1.97.1 (c980f4866 2026-06-30)** —— 与 `env.RUST_TOOLCHAIN` 完全一致，
且 4 ABI 原生库正是由该工具链产出（Release APK 内 4 个 `.so`）。

---

## 4. SDK 组件名核对（与 compileSdk 37 / minSdk 36 / targetSdk 36 基线对照）

基线读取自 `app/build.gradle.kts`：`compileSdk = 37`（:39）、`minSdk = 36`（:43）、`targetSdk = 36`（:44）；
`crypto/build.gradle.kts:13` `ndkVersionStr = "28.2.13676358"`。核实方式：`sdkmanager --list --channel=0` 实测远端包路径。

| workflow 中使用的包名 | 位置 | 远端/本机核实 | 判定 |
|---|---|---|---|
| `ndk;28.2.13676358` | build.yml:126 | 远端存在；本机 `ndk/28.2.13676358` 已装 | ✅ |
| `platforms;android-37.0`（修正后） | build.yml:127 | 远端存在（rev 2）；本机 `platforms/android-37.0` 已装 | ✅ |
| ~~`platforms;android-37`~~（修正前） | build.yml:127 | 远端**不存在** | ❌ 已修正（见 §1#1） |
| `build-tools;37.0.0` | build.yml:128 | 远端存在（`Android SDK Build-Tools 37`）；本机 `build-tools/37.0.0` 已装；`apksigner`/`aapt2` 实测可运行 | ✅ |
| `platform-tools` | build.yml:129 | 远端存在（且 setup-android 默认也装它） | ✅ |
| `cmdline-tools`（由 setup-android 隐式安装，默认长版本 12266719） | build.yml:118-119 | action README「Version table」：16.0 ↔ 12266719；远端存在 `cmdline-tools;16.0` | ✅ |

`ANDROID_SDK_ROOT` 相关路径（`${ANDROID_SDK_ROOT}/ndk/...`、`.../build-tools/.../apksigner`）：该变量由 runner 镜像与
setup-android 共同提供（action README 明确以其为 SDK 根）。本机等价证据：`apksigner` 位于
`D:\Android\SDK\build-tools\37.0.0\apksigner.bat`，NDK 位于 `D:\Android\SDK\ndk\28.2.13676358` —— 与 workflow 拼法同构。

---

## 5. workflow YAML 语义自检

核实方式：PyYAML 6.0.3 解析两个文件（语法 + 结构），逐条人工核对；**未执行任何 CI**。

| 检查项 | 结果 |
|---|---|
| YAML 语法 | ✅ 两文件均可被 `yaml.safe_load` 完整解析（`build.yml`：3 job / 6+15+5 步；`dependency-scan.yml`：1 job / 6 步），改动后缩进与块标量完好 |
| `runs-on` | ✅ `ubuntu-latest`（×4）为合法 GitHub 托管标签 |
| `setup-java` distribution / 版本 | ✅ `distribution: temurin`；版本已按 §1#3 由 17 改 21（与 `gradle-daemon-jvm.properties` 的 21 对齐） |
| 缓存 key | 无显式 `actions/cache` 步骤；缓存由 `gradle/actions/setup-gradle`（无 `with:`）全权托管 → 不存在「key 字段不全导致缓存失效不全」的问题。**注**：`rust-supply-chain` 未缓存 `~/.cargo`，每次 `cargo install cargo-deny` 全量编译（耗时风险，非失败风险） |
| `timeout-minutes` | ✅ 60 / 120 / 60 / 60；本机等价耗时（test 全模块 + lint、assembleDebug/assembleRelease、cargo 编译）均在余量内 |
| `if:` 表达式 | ✅ 仅 `if: always()`（build.yml:88,222；dependency-scan.yml:67,77），语法与语义均正确（失败时仍归档/上传） |
| `secrets` / `vars` 引用 | ✅ 仅 `${{ secrets.NVD_API_KEY }}`（dependency-scan.yml:53，`env:` 层允许 secrets 上下文）；无 `vars.*`；`concurrency` 中 `${{ github.workflow }}` / `${{ github.ref }}` 合法 |
| 产物归档路径 | ✅ 实测本机存在 `*/build/reports/tests/**`（5 模块）、`*/build/reports/lint-results-debug.{html,xml,txt}`（core/crypto/database/sync；**app 模块本机无 lint 报告**，但 `if-no-files-found: warn` → 最坏是告警）、`app/build/outputs/mapping/release/{mapping,seeds,usage,configuration}.txt`、`app/build/outputs/apk/release/*.apk` 与 `*.idsig` |
| SARIF 上传路径 | ✅ `build/reports/dependency-check/dependency-check-report.sarif` 指向**根项目** build 目录 —— 本机 aggregate 实跑后该文件确实存在（465,907 B，2026-09-10）；`**/build/reports/dependency-check/…` 通配亦覆盖 6 个模块 |
| NVD datafeed URL | ✅ `https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz` 与官方「Mirroring External Resources」（dependency-check 13.0.0 文档，Last Published 2026-08-03）**逐字一致** |
| 「未配置签名 → 产出未签名包而非失败」断言自洽性 | ✅ 读 `app/build.gradle.kts:11-35,49-74,86-88`：无 `keystore.properties` 且无 `KEYSTORE_*` 环境变量时**不创建** signingConfig，AGP 照常产出未签名包，`assembleRelease` 不失败。CI 场景**恰好相反**：步骤先写 `KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD` 到 `$GITHUB_ENV` 再 `assembleRelease`，因此**必然**走已签名分支，断言对象（v1/v2/v3/v4）与实际产物同源。本条断言与构建脚本行为**一致** |
| `ls …/*.apk \| head -n 1` 选取唯一性 | ✅ 本机 `app/build/outputs/apk/release/` 仅一个 `.apk`（+ `.idsig`），未签名分支不会与该目录共存；若将来两者共存，`app-release-unsigned.apk` 会排在 `app-release.apk` 之前被误选（**风险提示，非当前缺陷**） |

---

## 6. Linux 侧测试表现预判（「Windows 跳过 / Linux 真实执行」用例清单）

> 判断基于**当前工作区**（2026-09-10 15:35 复核，`AtomicFileWriterTest.kt` 已由 ISSUE-P3-26 拆分为
> `AtomicFileWriterTest.kt`(366 行) + `AtomicFileWriterBackupDeletionTest.kt` + `DirectorySyncTestDoubles.kt`）。
> 生产实现判据：`PosixDirectorySync.sync` = `FileChannel.open(dir, READ).force(true)`；Linux 上对目录 fd 执行 `fsync(2)` 合法，
> 故 **Linux 分支期望值 SYNCED**；`FileChannel.open(不存在目录)` 抛 `NoSuchFileException` → DEGRADED。

| 用例（文件:行） | Linux 行为 | 判据 | 预判 |
|---|---|---|---|
| `DirectorySyncTest.testSyncOutcomeMatchesHostDirectoryChannelSupport`（`DirectorySyncTest.kt:41-57`，断言分支在 :44-56，`isWindowsHost` 定义 :32） | **真实执行**（Windows 上走 DEGRADED 分支；Linux 走 SYNCED 分支，该分支从未在 Windows 跑过） | `FileChannel.open(tmpdir, READ).force(true)` 在 Linux ext4/overlayfs 上成功 | **预期通过**（较有把握；若失败则说明 runner 文件系统对目录 fd 的 fsync 返回 EINVAL） |
| `AtomicFileWriterTest.testFourPathsDirectorySyncOutcomeOnRealHost`（`AtomicFileWriterTest.kt:308-360`，Linux 期望值在 :348-354） | **真实执行**：四条路径经**真实** `PosixDirectorySync` 探针，`probe.outcomes.distinct()` 必须 == `[SYNCED]` | 同上；且四条路径的钩子计数（①②/③/④）与平台无关（主路径 `Files.move(ATOMIC_MOVE)` 成功或降级 `renameTo`/`copy` 都恰好各触达一次） | **预期通过**（同上） |
| `DirectorySyncTest.testSyncOfMissingDirectoryDegradesWithoutThrowing`（`DirectorySyncTest.kt:59-65`） | 两平台一致 | 不存在目录 → 异常 → DEGRADED | 预期通过 |
| `AtomicFileWriterTest.testDegradedDirectorySyncNeverBlocksWrite`（:290-306）、`testMainAtomicMovePathInvokesDirectorySyncHook`、`testBackupRollingPathInvokesDirectorySyncHook`、`testFallbackStandardRenamePathInvokesDirectorySyncHook`、`testFallbackCopyOverwritePathInvokesDirectorySyncHook`、`testRefusedUnprotectedOverwriteDoesNotInvokeDirectorySyncHook` | 两平台一致（注入型假实现，与平台解耦） | 断言的是**钩子调用次数**而非平台结果 | 预期通过 |
| `AtomicFileWriterBackupDeletionTest.*`（ISSUE-P3-26 新文件，`DirectorySyncOutcome.DEGRADED` 用例在 :83） | 两平台一致（`RecordingDirectorySync` 假实现；「`.bak` 占位为非空目录 → 删除必失败」在 Windows/Linux 均成立） | 无 `isWindowsHost` 分支 | 预期通过 |
| `SyncCacheTest.缓存文件权限收敛为仅属主可读写`（`SyncCacheTest.kt:118-151`；`Assume` 在 :121-124） | **首次真实执行**（Windows 上因无 POSIX 视图被 `Assume` 跳过——即测试基线中那 1 例跳过） | `SyncCache.init{}` 对 cacheDir 显式 `setPosixFilePermissions(0700)`；`writeCache/writeBaseContent/updateBase` 每条路径都经 `restrictToOwnerOnly(0600)`；用例内产生的 5 个文件（`.cache/.version/.basecache/.baseversion/.meta`）均 0600，`files.size >= 4` 成立 | **预期通过**（`Assume` 不会触发；断言与实现语义自洽） |
| `crypto` 宿主 JNI 用例（`NativeArgon2HostJniTest`、`Argon2InteropDiagnosticTest`） | 两平台**均已执行**（本机 61 例 crypto 测试中无一例被 `Assume` 跳过；`crypto/build/rust/host/release/keepasskey_argon2.dll` 247,808 B 存在） | Linux 上改为 `cargo build --release` 产出 `.so`，JNI 加载路径更标准 | 预期通过 |
| `LiveSyncServersTest`（12 例） | Linux 上仍跳过 | 仅当 `-DliveSyncTest` 存在才向测试 JVM 下发该属性（`sync/build.gradle.kts:58-76`），CI 未传 | 预期跳过 |

**「疑似首次即红」清单（本工作流判定）：跨平台自洽性层面为 0 例** —— 上述 3 条「Linux 首次真实执行」的
POSIX 断言与实现语义一致，未发现断言错误（例如断言 Windows 语义、断言目录 fsync 次数随平台变化等）。
唯一的环境依赖风险是 **runner 文件系统是否支持目录 fd fsync**（见 §7-R3），**不构成代码缺陷，故不提测试补丁**。

**若 CI 首次运行仍在这 3 条断言上变红，推荐处置（不削弱断言）**：把断言改为「探测宿主能力后再断言」，
即先用 `FileChannel.open(dir, READ)` 自身探测一把，再要求 `DirectorySync.default.sync()` 与探测结果一致：

```kotlin
// 草案（仅当首次运行证实 runner 文件系统不支持目录 fsync 时才需要；不要提前削弱断言）
private val hostSupportsDirectoryChannel: Boolean = runCatching {
    java.nio.channels.FileChannel.open(tempFolder.root.toPath(), java.nio.file.StandardOpenOption.READ)
        .use { it.force(true) }
}.isSuccess

val expectedOutcome = if (hostSupportsDirectoryChannel) DirectorySyncOutcome.SYNCED
                      else DirectorySyncOutcome.DEGRADED
```

---

## 7. 风险点清单（**未改动**，仅记录；含补丁草案）

| # | 位置 | 判据 | 建议处置 / 补丁草案 |
|---|---|---|---|
| R1 | `build.yml:66,103,240`、`dependency-scan.yml:35`（`runs-on: ubuntu-latest`） | `ubuntu-latest` 为**浮动标签**，镜像内容（预装 JDK/SDK/NDK/Rust）随时间迁移（如 ubuntu-24.04 → 26.04），与文件「版本固定、禁止漂移」的设计意图相悖 | 固定为 `runs-on: ubuntu-24.04`（4 处同改）。风险等级：中（漂移可能改变预装 SDK/JDK 版本） |
| R2 | `build.yml:81-85`（fast-gate 不安装 Android SDK） | fast-gate 依赖 runner 镜像预装的 SDK 平台。ubuntu-24.04 工具集 `android.platform_min_version = 34`（镜像构建时装入 ≥34 的全部平台），**推理上**应含 `android-37.0`，但**本环境无法验证**该镜像当前实际装入的 API 级别；若缺失，`:app:compileDebugKotlin` / `lint` 会因 `compileSdk 37` 找不到平台而红 | 若要消除不确定性，在 fast-gate 加一步（约 +1 min）：<br>`- uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3`<br>`- run: sdkmanager --install "platforms;android-37.0"` |
| R3 | `database/.../PosixDirectorySync.kt:22` + §6 两条断言 | Linux 下目录 fd 的 `fsync` 在个别文件系统/挂载（overlayfs 变体、只读挂载）可能返回 EINVAL → 降级为 DEGRADED → 两条 POSIX 断言失败 | 先按 §6 草案改为「能力探测 + 一致性断言」；**不得**直接删除断言 |
| R4 | `gradle/wrapper/gradle-wrapper.properties`（**不在本工作流所有权内，故未改**） | ① `distributionUrl` 指向第三方镜像 `mirrors.cloud.tencent.com`，GitHub 托管 runner 对其可达性/限速**未验证**（镜像不可达即无法引导 Gradle）；② 未配置 `distributionSha256Sum`，发行包无完整性锚点（供应链面） | 建议（由编排者决策）：<br>① 首选官方源 `https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`，或保留镜像但补 `distributionSha256Sum=<官方 SHA-256>`；<br>② 无论走哪个源都补 `distributionSha256Sum` |
| R5 | `build.yml:238-266`（rust-supply-chain） | ① 未缓存 `~/.cargo`，每次全量 `cargo install cargo-deny`（编译耗时，非失败）；② `cargo deny check` 需实时克隆 rustsec/advisory-db —— CI 侧可达性未验证（本机今日实测**已可连通**，见 §8） | 可选：加 `actions/cache` 缓存 `~/.cargo/registry`、`~/.cargo/git`、`~/.cargo/bin`；或改用官方预编译二进制安装 |
| R6 | `dependency-scan.yml:66-80` | ① 私有仓库未启用 GitHub Advanced Security 时 `upload-sarif` 会失败（Permissions 已给 `security-events: write`，但仓库能力是外部前提）；② `failOnError=true` 下扫描失败可能不产出报告，`if-no-files-found: error` 会二次失败（属 fail-closed 设计，非缺陷） | 首次运行后按实际报错登记（若 GHAS 不可用，可把 SARIF 上传改为 `continue-on-error` 之外的显式条件，或先只归档 artifact） |
| R7 | `.github/dependency-check.init.gradle.kts:49`（**不在本工作流所有权内，故未改**） | 注释写「NVD 数据源双通道（本地验证中，**暂不提交**）」，但该双通道代码**已在库内提交** → 注释与代码现状不符（误导性注释） | 由编排者改为「已提交，首次 CI 运行后按实际结果复核」；本记录不动该文件 |
| R8 | `build.yml:161-173`（一次性 CI 密钥） | `keytool` 口令经 workflow 级 `env` 明文写入 YAML（注释已声明「非机密、仅 runner 临时目录」），逻辑自洽；**唯一前提**是 `keytool` 在 JDK 21 下可用（JDK 21 自带，✅） | 无需改动；如追求更严，可改为 `${{ github.run_id }}` 派生口令 |
| R9 | `build.yml:181` `ls …/*.apk \| head -n 1` | 当前仅 1 个 APK 故安全；若未来出现 `app-release-unsigned.apk` 共存，按字典序会被优先选中（`-` < `.`）导致断言对象错误 | 建议改为 `apk=$(ls app/build/outputs/apk/release/app-release.apk)` 或显式排除 `*-unsigned.apk` |

---

## 8. 局部复现结果更新：`cargo deny check`（**推翻 ISSUE-P3-24 现象 3 的当前可复现性**）

- **命令**：`cd crypto/src/main/rust && cargo deny check`（cargo-deny 0.20.2 / cargo 1.97.1，本机）
- **时间**：2026-09-10 15:18（UTC+8）
- **结果**：**退出码 0**，末行 `advisories ok, bans ok, licenses ok, sources ok`；另有 5 条
  `warning[license-not-encountered]`（deny.toml:31/32/33/34/36 的许可项在依赖树中未命中，仅为提醒，不影响退出码）。
- **联网证据**：`~/.cargo/advisory-db/advisory-db-3157b0e258782691`（rustsec/advisory-db 检出）的 mtime 为
  **2026-09-10 15:18:21**，即本次运行**真实拉取/更新了 advisory-db** —— `curl 28 Failed to connect to github.com:443`
  在本次运行中**未复现**（网络可达性属环境相关，非永久结论）。
- **由此新增的正向证据**：以当日 advisory-db 为准，本项目钉死的 Android 依赖图
  （argon2/rayon/zeroize/jni 等，4 个 android target）**无 RUSTSEC 公告、无 yanked 依赖**，
  `[bans]/[licenses]/[sources]` 亦全部通过 → rust-supply-chain 门禁在 CI 上（runner 可访问 github.com）
  **预期首次运行即可通过**。
- **同时证实**：`deny.toml` 的配置 schema（`[advisories] version=2`、`yanked="deny"`、`[graph].targets`、
  `[licenses].allow`、`[bans]`、`[sources]`）与 **cargo-deny 0.20.2 完全兼容**（若不兼容会在解析期立即报错）。
- **边界**：该结论来自本机网络与当日 DB 快照，**不代表 CI 环境下 DB 拉取必然成功**，亦不代表未来公告不会命中。

---

## 9. 明确无法在本环境验证的结论（不得据此认为 CI 已跑通）

1. 三条 job 在 GitHub 托管 runner 上的**真实执行结果**（Action 拉取、SDK 安装、NDK/Rust/cargo-ndk 安装、4 ABI 交叉编译、
   Debug/Release 打包、断言通过与否）——**全部未运行**；
2. `dependency-scan.yml` 在 CVSS ≥ 7.0 下的**真实阻断行为**（需完整 NVD 数据同步与真实依赖解析）；
3. `cargo deny check advisories` 在 **CI runner 网络**下的 advisory-db 拉取（本机已连通，但见 §8 边界）；
4. runner 镜像当前**实际预装**的 Android API 级别（R2）、`ubuntu-latest` 指向的镜像版本（R1）；
5. Code Scanning / GHAS 在目标仓库的可用性（R6），以及 secrets `NVD_API_KEY` 是否已配置；
6. Gradle 对 daemon JVM criteria 无法满足时的**失败/自动供给**行为（§1#3 残留不确定性）；
7. Linux 侧 3 条 POSIX 断言的**最终结果**：本记录仅给出「实现语义 vs 断言」的静态一致性判断（§6），
   不构成「Linux 上已通过」的证据。

## 10. 本工作流执行边界确认

- **未执行任何 Gradle 命令**（无 `gradlew` / `gradle` 调用）；**未执行任何 git 命令**（无 add / commit / push / status）。
- 未修改测试文件、未修改 `libs.versions.toml`、未修改 `AGENTS.md` / `ACTIVE_ISSUES.md` / `RESOLVED_LOG.md`、
  未修改 `app/**`、`database/**`、`sync/**`、`core/**`、`crypto/src/**`、`参考项目/**`。
- 本记录写作时，`AtomicFileWriterTest.kt` / `AtomicFileWriter.kt` 正由 ISSUE-P3-26 负责人并行修改，
  本文 §6 已按**当前工作区**状态（15:35 复核）重新判定。
