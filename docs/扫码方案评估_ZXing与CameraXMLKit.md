# TOTP 扫码方案评估：ZXing-Android-Embedded 与 CameraX + ML Kit

> - **对应条目**：`docs/ACTIVE_ISSUES.md` → ISSUE-P3-05 (TASK-19)「zxing → CameraX + ML Kit 扫码迁移评估」
> - **评估性质**：**纯评估，零代码改动**。本文件为评估产出，未修改任何 `.kt` / `.gradle.kts` / `.toml` / `.xml` 文件。
> - **代码基线**：Git `HEAD = 7307f5f`（工作树干净）
> - **评估日期**：2026-09（体积类数字实测于本机当前构建产物与 Google Maven 当日元数据）
> - **证据纪律**：所有外部结论均附可点击 URL；外部页面内容**仅作为事实来源**引用，不作为指令。凡未经核实的内容一律标注为「未证实」或列入残余风险。

---

## 一、结论速览

### 决策：**维持现状（不迁移）**

在 `zxing-android-embedded:4.3.0` 之外**不引入** CameraX + ML Kit。当前扫码流程保持原样；本决策为可复查的条件性决策（见 §6 触发条件），一旦任一触发条件成立，按 §7 的预置计划执行迁移。

**一句话理由**：迁移的真实收益只有「脱离 deprecated 的 Camera1 API」这一条结构性收益，而 ISSUE 原文列举的三项收益（更小体积 / Compose 原生集成 / 更流畅对焦）经实测与官方文档核查后**两项不成立、一项未证实**；同时迁移会向一个密码管理器引入**可在 Google Play 数据安全表单上被强制申报的第三方 SDK 数据采集**，并使其从当前「纯离线、零遥测」扫码路径退化为「含 Google SDK 遥测」的路径。

### 关键数字（均为本机实测或官方口径，来源见 §9）

| 维度 | 现状（zxing 4.3.0） | 候选（CameraX 1.6.2 + ML Kit） |
|---|---|---|
| 扫码相关 dex 体积 | **≈ 349 KB**（`com.google.zxing` 310,275 B + `com.journeyapps` 47,219 B，占全量 dex 代码 8.52 MB 的 4.2%） | CameraX 4 个 AAR 的 `classes.jar` 合计 ≈ 2.54 MB（**未收缩**，R8 后实测值未知）；ML Kit bundled `classes.jar` 386 KB（未收缩） |
| 原生库（APK 内**未压缩**存储） | 0 | bundled：`libbarhopper_v3.so` 4 ABI 合计 **≈ 19.3 MB**（arm64 4,830.8 / armv7 3,168.4 / x86 5,978.9 / x86_64 5,770.8 KB） |
| 模型资源 | 0 | bundled：3 个 `.tflite` 合计 860.2 KB |
| 官方体积口径 | — | bundled「约 +2.4 MB」、unbundled「约 +200 KB」（**AAB 单 ABI 下载增量口径**） |
| 当前 release APK | **14.27 MB**（单 `classes.dex` 10.16 MB） | 无 ABI 拆分（本项目无 `bundle{}`/`splits{}` 配置）时，估算 **+20 MB 量级**（仅 .so 就 +19.3 MB） |
| 第三方遥测 | **0**（纯离线，无网络调用） | **有**：设备信息、应用信息、每次安装标识符、性能指标、API 配置、输入输出尺寸、错误码（官方《Android 数据披露》页明列） |
| 图像是否外传 | 不外传（不联网） | **不外传**（官方条款：输入数据与结果输出不发往 Google 服务器） |
| minSdk 要求 | 库自身 minSdk 19（本项目 36 满足） | CameraX AAR `minSdkVersion=23`；ML Kit AAR `minSdkVersion=21`（官方文档口径 API 23+）→ 均满足 minSdk 36 |
| 上游维护 | **冻结**：4.3.0 发布于 2021-10-25，master 最后提交 2022-10-21 | CameraX 1.6.2（活跃维护）；ML Kit barcode 17.3.0 最后发布 2024-08-07（近两年无新版） |

---

## 二、现状核查（有据可查）

### 2.1 依赖坐标与版本

| 项 | 事实 | 证据 |
|---|---|---|
| 直接依赖 | `com.journeyapps:zxing-android-embedded:4.3.0` | `gradle/libs.versions.toml:27`（`zxing = "4.3.0"`）、`:70`（`zxing-embedded` 别名）、`app/build.gradle.kts:142` |
| 传递依赖 | `com.google.zxing:core:3.4.1` | Gradle 缓存 `~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.4.1/`（`core-3.4.1.jar` 526.6 KB）；仓库内**无**任何版本约束/锁覆盖它 |
| AAR 本体 | `zxing-android-embedded-4.3.0.aar` = **148.3 KB**（`classes.jar` 126.8 KB + 2 个 layout + `zxing_beep.ogg` 6.3 KB + 约 30 个多语言 `values-*` 资源） | 本机 AAR 实测 |

### 2.2 实际使用到的 API 面积（全仓仅 12 行代码 + 2 行注释）

```
EntryEditScreen.kt:69-70   import com.journeyapps.barcodescanner.ScanContract
                           import com.journeyapps.barcodescanner.ScanOptions
EntryEditScreen.kt:177     val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
EntryEditScreen.kt:178         result.contents?.let { viewModel.onTotpSecretChangeSecure(it.toCharArray()) }
EntryEditScreen.kt:275-283 onScanTotpQr = {
                               val options = ScanOptions()
                               options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                               options.setPrompt(scanPrompt)
                               options.setBeepEnabled(false)
                               options.setOrientationLocked(true)
                               qrScanner.launch(options)
                           }
```

- **用到 2 个类**：`ScanContract`（`ActivityResultContract`）、`ScanOptions`；**1 个构造 + 4 个 setter + 1 次 `launch` + 1 个结果字段**（`ScanIntentResult.contents: String`）。
- 未使用：`IntentIntegrator`、`DecoratedBarcodeView`、`BarcodeView`、`BarcodeCallback`、`CaptureManager` 等嵌入模式 API（虽然 `mapping.txt` 中因 `-keep` 规则被动保留了大量此类，见 §2.5）。
- 结果处理**已符合** TASK-10 安全契约：框架边界的 `String` 立刻 `toCharArray()` 走安全桥接上行；`viewModel.onTotpSecretChangeSecure(CharArray)`。

### 2.3 集成方式与调用点

| 项 | 事实 | 证据 |
|---|---|---|
| 集成方式 | **Intent / Activity 集成**（zxing 自带 `CaptureActivity` 承担取景、对焦、解码、运行时权限），**不是**嵌入 View | 合并清单 `app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml:289`（`com.journeyapps.barcodescanner.CaptureActivity`） |
| 调用点总数 | **全仓 1 处** | `grep -r "zxing\|journeyapps\|ScanContract\|ScanOptions\|IntentIntegrator"` 命中仅 `EntryEditScreen.kt`；UI 触发按钮在 `EntryEditComponents.kt:81`(回调声明)、`:107`(IconButton) |
| 弹窗文案 | `R.string.edit_scan_totp_qr`（`values/strings.xml:314`、`values-en/strings.xml:308`） | 文件 |
| 其它 TOTP 界面 | `ui/screens/authenticator/**`（认证器列表）**无任何扫码代码**，仅使用 `Icons.Default.QrCode` 作空状态图标（`AuthenticatorScreen.kt:186`） | `grep` + 文件 |
| 单测/仪器测试依赖 | **无**。`app/src/test` 无 zxing/journeyapps 引用；本项目 5 个模块**均无** `androidTest` 目录 | `grep` + `glob` |

> 结论：扫码是**单点、极薄**的集成，替换成本集中在「新 UI 组件」而非「改造既有调用面」。这既是迁移的有利条件（改动面小），也是维持的有利条件（保持现状成本为 0）。

### 2.4 权限与清单合并（**迁移必踩的坑**）

| 项 | 事实 |
|---|---|
| 自有清单 | `app/src/main/AndroidManifest.xml` **未声明** `android.permission.CAMERA`；仅有 INTERNET / ACCESS_NETWORK_STATE / USE_BIOMETRIC / HIDE_OVERLAY_WINDOWS |
| CAMERA 实际来源 | **由 zxing 的库清单合并引入** —— zxing AAR 自带清单实测含 `<uses-permission android:name="android.permission.CAMERA" />` 与 `<uses-sdk android:minSdkVersion="19" />`；合并清单 `:33` 的 CAMERA 及其后的 `uses-feature` 注释即 zxing 上游原文（「Don't require camera, as this requires a rear camera…」） |
| zxing 额外注入 | 6 条 `uses-feature`（`camera` / `camera.front` / `camera.autofocus` / `camera.flash` / `screen.landscape` / `wifi`，**全部 `required="false"`**）+ `CaptureActivity` 声明（`screenOrientation="sensorLandscape"`，合并清单 `:34-52`、`:289`） |

**含义**：一旦移除 zxing，**必须**在自有清单显式补上 `CAMERA` 权限，否则扫码在运行时必然失败（且是权限缺失这类低级故障）。即迁移并非「纯依赖替换」，而是「权限归属从第三方库迁回自有清单」的清单变更 —— 这是评估中被 ISSUE 原文遗漏的一项成本，也是一项**应单独偿还的隐式耦合**（见 §10 建议）。

### 2.5 R8 保留规则

`app/proguard-rules.pro:80-83` 手工保留（zxing AAR **不自带** consumer 规则，实测其 `classes.jar`/AAR 内无 `proguard.txt`）：

```
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**
```

其结果是 release `mapping.txt` 中被动保留 `com.journeyapps` 74 个、`com.google.zxing` 295 个顶层类，而 `usage.txt` 仅移除 12 + 3 个 —— 即 zxing 的 dex 体积**基本未被 R8 收缩**（`-keep { *; }` 全量保留）。这放大了「zxing 体积占用」的绝对值（见 §2.6），但绝对量级仍很小。

对照：CameraX 与 ML Kit bundled 的 AAR **自带 `proguard.txt` 消费者规则**（实测 `camera-core/camera2/lifecycle/view-1.6.2.aar` 与 `barcode-scanning-17.3.0.aar` 均含），迁移后**无需**手写 keep 规则，并可从 `proguard-rules.pro` 删除上述 4 行。

### 2.6 真实的体积占用（实测，非估算）

测量工具：`apkanalyzer`（`D:\Android\SDK\cmdline-tools\latest`）+ APK/AAR zip 直接读数；对象：`app/build/outputs/apk/release/app-release.apk`（2026-09-10 构建，14.27 MB）。

| 指标 | 数值 |
|---|---|
| APK 条目 / 原始体积 / 压缩后体积 | 527 项 / 14.8 MB / **14.17 MB**（`classes.dex` 单项 10.16 MB） |
| dex 代码总量（apkanalyzer `<TOTAL>`） | 8,520,771 B（≈8.52 MB） |
| `com.google.zxing`（含子包） | 1,901 defined methods / **310,275 B** |
| ├ `com.google.zxing.qrcode`（二维码子包） | 254 methods / 44,641 B |
| ├ `com.google.zxing.oned`（一维码，**本场景用不到**） | 342 methods / 60,155 B |
| └ 其余（datamatrix/aztec/pdf417/multi 等，**本场景均用不到**） | 余量 |
| `com.journeyapps` | 555 methods / **47,219 B** |
| **扫码相关 dex 合计** | **357,494 B ≈ 349 KB（占 dex 代码 4.2%）** |
| 原生库 | 仅 6 个自有 `.so`（Rust Argon2 + androidx 小库），**全部 raw == compressed**（清单 `android:extractNativeLibs="false"`）→ 新增 `.so` 会**按未压缩原始体积**计入 APK |
| 是否 ABI 拆分 | **否**。全仓仅有 `crypto/build.gradle.kts:37` 的 `abiFilters`（构建侧），`app` 模块无 `bundle{}` / `splits{}` / `abiFilters` → 通用 APK，含 4 ABI |

> **关键含义**：本项目当前分发形态下，任何新增原生库都按**未压缩**体积进入 APK。ML Kit bundled 的 4 ABI `.so` 合计 19.3 MB，将使 APK 从 14.27 MB 量级跳升至 33~35 MB 量级（**估算，未实测构建**）。

### 2.7 底层相机 API（技术债的实质）

对 `zxing-android-embedded-4.3.0.aar` 内 `classes.jar` 的 80 个 `.class` 做常量池字节检索：

| 检索串 | 命中类数 | 代表类 |
|---|---|---|
| `android/hardware/Camera`（**Camera1，API 21 起 deprecated**） | **8** | `CameraManager`、`CameraConfigurationUtils`、`AutoFocusManager`、`OpenCameraInterface` … |
| `android/hardware/camera2` | **0** | — |
| `androidx/camera` | **0** | — |

官方对该 API 的判词（[Camera API reference](https://developer.android.com/reference/android/hardware/Camera)）：

> "Added in API level 1. **Deprecated in API level 21**. We recommend using the new `android.hardware.camera2` API for new applications."

即 **zxing 4.3.0 的相机层完全建立在 Camera1 之上**。这是「迁移」主张唯一站得住的技术理由；同时官方明确 CameraX 的立场（[Camera（已废弃）指南](https://developer.android.com/media/camera/camera-deprecated/photobasics)）："本页所述是指已淘汰的 Camera 类别。**建议使用 CameraX**，或者，在特定情况使用 Camera2。"

### 2.8 上游维护状态（冻结，但非「死亡」）

| 事实 | 数值 | 来源 |
|---|---|---|
| Maven Central 最新版本 | **4.3.0**（`latest`/`release` 均为 4.3.0，无更新版本） | [maven-metadata.xml](https://repo1.maven.org/maven2/com/journeyapps/zxing-android-embedded/maven-metadata.xml) |
| Maven Central `lastUpdated` | **2021-10-25** | 同上 |
| 4.3.0 发布说明日期 | 2021-10-25 | [CHANGES.md](https://github.com/journeyapps/zxing-android-embedded/blob/master/CHANGES.md) |
| GitHub master 最后提交 | **2022-10-21**（`d09b7c7` "Merge pull request #726 … Fix Typo"，纯文档改动） | [commits API](https://api.github.com/repos/journeyapps/zxing-android-embedded/commits?per_page=3) |
| 仓库状态 | 未归档（`archived: false`）、5,932 stars、1,289 forks、**127 open issues**、`pushed_at` 2024-08-04（分支活动，无发布） | [repos API](https://api.github.com/repos/journeyapps/zxing-android-embedded) |
| 上游解码内核 | `com.google.zxing:core` 最新 **3.5.4**（lastUpdated 2025-11-11），**仍在缓慢维护**；但本项目经 4.3.0 传递锁在 **3.4.1**（2021 年） | [core maven-metadata.xml](https://repo1.maven.org/maven2/com/google/zxing/core/maven-metadata.xml) |

> **判读**：`zxing-android-embedded` **事实上冻结**（约 5 年无发布、4 年无功能提交），但许可证为 Apache-2.0、代码自包含、**无网络 I/O**（实测常量池：`HttpURLConnection`/`Socket`/`java.net.URL`/`java.net.URI` 命中数为 0；仅 `DecodeHintManager` 引用 `android.net.Uri` 用于 intent 参数解析）、无运行时第三方依赖（仅传递 `zxing:core`），因此「冻结」在**风险谱系上属于低危**：它的失效模式是「某天平台不再兼容 Camera1」，而不是「遥测/安全更新缺失」。

---

## 三、候选方案事实核查（禁止凭记忆：逐条附来源）

### 3.1 版本现状（Google Maven / Maven Central 元数据实测抓取）

| 组件 | 最新版本 | 最新**稳定**版 | 元数据 `lastUpdated` | 来源 |
|---|---|---|---|---|
| `androidx.camera:camera-core` | 1.7.0-alpha03 | **1.6.2** | **2026-08-26** | [maven-metadata.xml](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/maven-metadata.xml) |
| `com.google.mlkit:barcode-scanning`（bundled） | **17.3.0** | 17.3.0 | **2024-08-07** | [maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/mlkit/barcode-scanning/maven-metadata.xml) |
| `com.google.android.gms:play-services-mlkit-barcode-scanning`（unbundled） | **18.3.1** | 18.3.1 | **2024-08-07** | [maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-barcode-scanning/maven-metadata.xml) |
| `com.google.android.gms:play-services-code-scanner`（Google 扫码器） | **16.1.0** | 16.1.0 | **2023-08-01** | [maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-code-scanner/maven-metadata.xml) |
| `com.journeyapps:zxing-android-embedded` | **4.3.0** | 4.3.0 | **2021-10-25** | [maven-metadata.xml](https://repo1.maven.org/maven2/com/journeyapps/zxing-android-embedded/maven-metadata.xml) |

> 附带事实：ML Kit 条码扫描两条路线**均自 2024-08-07 起无新版本**。即「迁移到更活跃的生态」这一隐含前提对 ML Kit 只部分成立 —— CameraX 活跃，ML Kit barcode 已近两年未发版。

### 3.2 bundled vs unbundled（官方对比表）

来源：[Scan barcodes with ML Kit on Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android)

| 特性 | Unbundled（`play-services-mlkit-barcode-scanning:18.3.1`） | Bundled（`com.google.mlkit:barcode-scanning:17.3.0`） |
|---|---|---|
| 实现 | 模型经 **Google Play 服务动态下载** | 模型**构建期静态链接**进应用 |
| 应用体积 | **约 +200 KB** | **约 +2.4 MB** |
| 初始化 | 首次使用前可能需等待模型下载 | 模型立即可用 |
| 前置依赖 | 需设备具备 Google Play 服务（非 Google 认证/去 GMS 设备不可用）；可经 `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="barcode"/>` 配置安装后自动下载 | 无 GMS 运行时依赖 |
| 本项目 AAR 实测 | AAR 507.1 KB / `classes.jar` 596.2 KB | AAR **9,666.8 KB** / `classes.jar` 386.4 KB / `.tflite` 860.2 KB |

### 3.3 体积代价：官方口径 vs 本机实测（**两者必须同时看**）

官方「约 +2.4 MB」的前提是 **AAB + ABI 拆分**（Play 按设备架构只下发一份 `.so`）。本机实测原始体量：

| ML Kit bundled AAR（17.3.0）内部构成 | 体积 |
|---|---|
| `jni/arm64-v8a/libbarhopper_v3.so` | 4,830.8 KB |
| `jni/armeabi-v7a/libbarhopper_v3.so` | 3,168.4 KB |
| `jni/x86/libbarhopper_v3.so` | 5,978.9 KB |
| `jni/x86_64/libbarhopper_v3.so` | 5,770.8 KB |
| **4 ABI 合计** | **19,748.9 KB ≈ 19.3 MB** |
| `assets/mlkit_barcode_models/*.tflite` ×3 | 860.2 KB |
| `classes.jar` | 386.4 KB |

**本项目的两种情形**：

1. **维持当前通用 APK 形态**（无 ABI 拆分 + `extractNativeLibs="false"`）：迁入 bundled 将新增 **≈19.3 MB 未压缩 `.so` + 0.86 MB 模型 + dex 增量**，APK 从 14.27 MB 升至 **33~35 MB 量级（估算）**。这是 ISSUE 原文「更小体积」预期的**反向结果**。
2. **改为 AAB 分发**：单设备下载增量 ≈ 官方口径 2.4 MB + CameraX dex 增量；此时体积代价才落到「可接受但需评估」区间。

> 官方另有专门的减包指引：[Reduce Android app package size](https://developers.google.com/ml-kit/tips/reduce-app-size)（动态功能模块、`abiFilters` 排除未用架构）。注意：本项目已有 4 ABI 的 Rust Argon2 内核，`abiFilters` 收窄会牵连 crypto 模块，不能只针对 ML Kit 处理。

### 3.4 minSdk 36 校验（以 AAR 清单实测为准，非文档二手）

| 构件 | AAR `AndroidManifest.xml` 实测 | 本项目 minSdk 36 | 结论 |
|---|---|---|---|
| `camera-core-1.6.2.aar` | `<uses-sdk android:minSdkVersion="23" />` | 36 ≥ 23 | 满足 |
| `camera-camera2-1.6.2.aar` | `minSdkVersion="23"` | 36 ≥ 23 | 满足 |
| `camera-lifecycle-1.6.2.aar` | `minSdkVersion="23"` | 36 ≥ 23 | 满足 |
| `camera-view-1.6.2.aar` | `minSdkVersion="23"` | 36 ≥ 23 | 满足 |
| `barcode-scanning-17.3.0.aar`（bundled） | `minSdkVersion="21"` | 36 ≥ 21 | 满足（官方文档口径为 API 23+：「All ML Kit APIs require Android API level 23 or higher」，见 [ML Kit Guides](https://developers.google.com/ml-kit/guides)） |
| `play-services-mlkit-barcode-scanning-18.3.1.aar`（unbundled） | `minSdkVersion="21"` | 36 ≥ 21 | 满足 |

**结论：minSdk 36 下 CameraX 与 ML Kit 的最低 API 要求均满足，不构成阻碍。**

### 3.5 数据安全声明与隐私（**本评估的决定性一节**）

#### (a) 图像是否外传？—— 官方明确：**不外传**

[ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms)（Last modified: May 14, 2025）原文：

> "When you use ML Kit APIs, processing of the input data (e.g. images, video, text) fully happens on-device, and **ML Kit does not send that data and the resultant outputs to Google servers**."

→ 满足「密码管理器扫描 TOTP 二维码，图像不外传是硬要求」中的**图像不外传**这一条。**这是迁移方案唯一干净的合规结论。**

#### (b) 但是：ML Kit **会**向 Google 发送遥测 —— 官方同页紧接的下一段

> "The ML Kit APIs **may contact Google servers** from time to time in order to receive things like bug fixes, updated models and hardware accelerator compatibility information. The ML Kit APIs also **send metrics about the performance and utilization of the APIs** in your app to Google. … **You are responsible for informing users of your app about Google's processing of ML Kit metrics data** as required by applicable law."

#### (c) Google Play 数据安全申报：**会被改变**（官方专页，逐项列明）

来源：[为满足 Google Play 的数据披露要求做准备（ML Kit Android Data Disclosure）](https://developers.google.com/ml-kit/android-data-disclosure)（Last modified 2025-05-14）。该页明列 ML Kit Android SDK **收集**的数据（用途均为「诊断和使用情况分析」）：

| 数据类型 | 内容 |
|---|---|
| 设备信息 | 制造商、型号、操作系统版本、build、可用的 ML 硬件加速器 |
| 应用信息 | **软件包名称和应用版本** |
| 设备或其他标识符 | bundled：**每次安装标识符**（不用于唯一标识用户或实体设备） |
| 效果指标 | 性能指标（如延迟） |
| API 配置 | 图片格式与分辨率 |
| 特征输入和输出大小 | 输入/输出尺寸 |
| 功能版本 / 活动类型 / 错误代码 | 功能版本；事件类型（初始化、模型下载、检测、资源释放）；对应错误码 |

其中**条形码扫描**的附加采集项（该页明列 `com.google.mlkit:barcode-scanning`、`play-services-mlkit-barcode-scanning`、`play-services-code-scanner`）：

> "如果您启用自动缩放选项，系统会收集以下数据：为扫描会话动态生成的 ID…；自动缩放事件的缩放级别变化；可能包含条形码的边界框的预测坐标，用于自动缩放。"

该页同时说明：数据传输用 HTTPS 加密、**不将这些数据传输给第三方**。

**结论（成本）**：迁移到 ML Kit 后，本应用的 Google Play 数据安全表单**必须新增申报**（至少落在 `Diagnostics`／`Device or other IDs`／`App info` 三类，具体映射需按 [Android 数据类型指南](https://developer.android.com/guide/topics/data/collect-share) 逐项确认），且 Google 条款把「告知用户 Google 对 ML Kit 指标数据的处理」的责任**明确压在应用开发者**身上。对一款以「本地优先、隐私优先」为卖点的密码管理器，这属于**产品定位层面的成本**，而非纯粹的表单工作量。

**对照现状**：当前 zxing 路径为**纯设备内解码**，实测常量池确认其**无网络 I/O**（`HttpURLConnection`/`Socket`/`java.net.URL`/`java.net.URI` 在 zxing-android-embedded 的 80 个类中命中数均为 0；`zxing:core` 仅在 `ResultParser`/`VCardResultParser` 中引用 `java.net.URL`/`URI` 用于**解码结果字符串解析**，且本项目只读取 `result.contents` 原始字符串、不触发该解析逻辑）。故扫码路径的第三方数据采集为 **0**。

#### (d) 三条候选路线的隐私取舍

| 路线 | 图像处理位置 | 是否需 CAMERA 权限 | 运行时依赖 | 遥测披露 |
|---|---|---|---|---|
| CameraX + ML Kit **bundled** | 设备端（自研管线） | 需（自有清单声明） | 无 GMS 依赖 | 有（见 (c)） |
| CameraX + ML Kit **unbundled** | 设备端（模型经 Play 服务下发） | 需 | **需 Google Play 服务**，首用可能需下载模型 | 有（见 (c)） |
| Google 扫码器（`play-services-code-scanner:16.1.0`） | **设备端，但由 Google Play 服务代管** | **不需要** | 需 GMS；扫描 UI 由 Play 服务提供 | 有（同上页明列该坐标） |

Google 扫码器的官方描述（[Google 扫码器（仅限 Android）](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner)）：「提供完整的解决方案，用于扫描代码，而**无需应用请求相机权限**…所有图片处理操作都在设备上运行，并且 **Google 不会存储结果或图片数据**」，并「将扫描代码的任务**委托给 Google Play 服务**」。

> 对本项目而言：Google 扫码器虽然代码量最小（无需相机权限、无需取景 UI），但把「相机取景 + 解码」整体交给 Play 服务，且**去 GMS 设备不可用**，对密码管理器而言「谁在看我扫的二维码」这一问题变得更不透明 —— 不利于产品的安全叙事。**不建议作为迁移目标。**

---

## 四、收益 / 成本矩阵

评级口径：✅ 成立且有量化依据｜⚠️ 未证实/有条件成立｜❌ 经核查不成立｜➖ 与现状相同

| # | ISSUE 原文主张 | 核查结论 | 依据 |
|---|---|---|---|
| 1 | **更小体积** | ❌ **不成立（方向相反）** | 现状扫码 dex 占用仅 **349 KB**（占 dex 4.2%，占 APK 2.4%）；而 CameraX 4 个 AAR `classes.jar` 未收缩即 ≈2.54 MB，ML Kit bundled 官方「约 +2.4 MB」（AAB 口径）/ 未压缩 `.so` **19.3 MB**（通用 APK 口径）。**移除 zxing 省下的 0.35 MB 无法抵消任何一条 ML Kit 路线的增量。** |
| 2 | **更流畅对焦** | ⚠️ **未证实** | Camera1 的 zxing 相机层已实现连续对焦（`AutoFocusManager`，实测存在于 AAR 中）；CameraX 侧确有更好的设备兼容与 `FocusMeteringAction`，但 ISSUE 自述现状「运行稳定」，**无任何可复现对焦缺陷**，且本评估**无真机**可做对比测量。收益记为「未证实」，不纳入决策。 |
| 3 | **Compose 原生集成** | ❌ **不成立（现状已满足；迁移反而更重）** | 现状调用面已是 100% Compose 原生：`rememberLauncherForActivityResult(ScanContract())` 十余行代码完成。迁移需自建 `AndroidView(PreviewView)` + 运行时权限流 + `ImageAnalysis`/`ImageProxy` → `InputImage` 转换 + 生命周期与旋转处理，**UI 代码净增**。当前「不原生」的只是 zxing 内部的取景 Activity，对调用方不可见。 |
| 4 | **脱离 deprecated API（ISSUE 未列，实为唯一真收益）** | ✅ 成立 | zxing 4.3.0 相机层 8 个类依赖 `android/hardware/Camera`（API 21 起 deprecated）；CameraX 为官方推荐替代。 |
| 5 | **依赖引入成本** | ❌ 成本被低估 | 新增 4 个 CameraX 坐标 + 1 个 ML Kit 坐标（`camera-core/camera2/lifecycle/view` + `barcode-scanning`），移除 1 个 zxing 坐标；**并需把 CAMERA 权限迁入自有清单**（现状全靠 zxing 库清单合并，见 §2.4；zxing 另注入 6 条 `uses-feature`，其中 4 条相机相关项需按需复刻，`screen.landscape`/`wifi` 两条可去除）。 |
| 6 | **Google Play 数据安全声明成本** | ✅ 成本真实存在且被官方文档坐实 | 迁移后在表单上新增「诊断/设备或其他标识符/应用信息」类申报，并承担「告知用户 Google 处理 ML Kit 指标」的义务（[Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)、[Terms & Privacy](https://developers.google.com/ml-kit/terms)）。现状为 0 采集。 |
| 7 | **回归风险** | ⚠️ 中 | 扫描入口唯一但位于「新增 TOTP」关键路径；迁移涉及清单/权限/构建脚本/UI 四类改动，且**必须真机验证**（授权拒绝降级、旋转、低光照、连续扫描）。本工作区无设备，风险无法在本次评估内闭合。 |
| 8 | **可回退性** | ✅ 良好 | 调用点唯一（1 处），迁移若失败可单 commit 回退（2 个构建文件 + 清单 + R8 规则 + 2 个 UI 文件）。这也是**维持现状**的有利点：未来随时可低成本迁移。 |
| 9 | **上游维护性** | ⚠️ 双向 | zxing-embedded 冻结（2021-10-25 起无发布）；但 ML Kit barcode 同样自 2024-08-07 无新版本。两者都不是「活跃度」上的明显赢家，真正的差异只有 Camera1 vs CameraX。 |
| 10 | **离线/合规硬要求（图像不外传）** | ➖ / ⚠️ | ML Kit 官方保证「图像与结果输出不发往 Google」（满足硬要求）；但当前 zxing 路径是**更强的不外传**：不联网、零遥测。 |

---

## 五、决策与理由

### 决策：**维持现状（不迁移）**，转为「条件触发式迁移」

**理由（按权重）**：

1. **收益主张经核查后坍塌为单一的结构性收益**。ISSUE 列举的三项收益中，「更小体积」实测方向相反（§四#1），「Compose 原生集成」现状已满足且迁移会使 UI 代码净增（§四#3），「更流畅对焦」无缺陷证据且无法核实（§四#2）。仅剩「脱离 deprecated Camera1」一条 —— 而该风险的**失效时间表并不存在**（官方仅标注 deprecated，未宣布移除），不构成当前行动的充分理由。
2. **成本一侧则出现了 ISSUE 未预料到的两项硬成本**：① 通用 APK 形态下 bundled ML Kit 的未压缩 `.so` 达 **19.3 MB**（APK 将从 14.27 MB 量级升至 33~35 MB 量级）；② 密码管理器将引入**可在 Play 数据安全表单强制申报的第三方 SDK 遥测**，把当前「零采集、纯离线」的扫码路径改写为「含 Google SDK 指标上报」。
3. **收益/成本比在「维持」一侧压倒性**：维持的成本为 0（无改动、无回归、无新申报），且**不锁死未来**（调用点唯一 → 迁移成本不会随时间显著上升）。迁移的成本为「+20 MB 或 +2.4 MB 体积 + 新申报义务 + 清单/权限改动 + 真机验证 + 未见收益」。
4. **稳定性是当前流程的明确属性**：ISSUE 原文即写明「运行稳定」；在被要求「迁移或维持」的二选一中，缺乏具体缺陷驱动的框架替换属于**投机性重构**，与项目「极简闭环纪律」相冲突。

**同时明确**：本决策**不是**对 zxing 的背书，而是「在无触发条件时不动」的工程判断。`zxing-android-embedded` 已事实冻结（5 年无发布），其技术债真实存在，只是**尚未到期**；§6 给出到期判据，§7 给出到期后的一次性执行方案。

---

## 六、重新评估触发条件（任一成立即启动 §7）

| ID | 触发条件 | 判据/取证方式 |
|---|---|---|
| **T1** | **真机可复现的扫码缺陷**：某些机型黑屏/对焦失败/旋转错误/低光照识别率显著低/相机启动缓慢卡顿 | 需以 issue 形式登记**具体机型 + 系统版本 + 复现步骤**；禁止以「感觉不流畅」触发 |
| **T2** | **平台侧收紧 Camera1**：targetSdk 提升后 `android.hardware.Camera` 出现行为回归，或官方 release notes 宣布移除/限制 | Android Developers 官方 release notes / behavior changes 文档 |
| **T3** | **产品需要 in-app 取景器**：要求扫码与解锁/自动填充/详情页复用同一 Compose 取景 UI，或要求扫码期间保持应用内上下文（当前「跳 Activity」不再满足需求） | 产品需求文档 / UI 评审结论 |
| **T4** | **依赖治理硬化**：ISSUE-P3-09 供应链批次将「上游 N 个月无发布」定为阻断门禁，或 Dependabot/SBOM 策略要求替换无维护依赖 | `docs/ACTIVE_ISSUES.md` ISSUE-P3-09 的最终验收标准 |
| **T5** | **分发形态改为 AAB**：届时 bundled ML Kit 的单设备边际成本降至官方口径 ≈2.4 MB，收益/成本比发生实质变化 | `app/build.gradle.kts` 出现 `bundle{}` / Play 上架形态变更 |
| **T6** | **上游复活**：`zxing-android-embedded` 发布基于 CameraX 或至少 camera2 的新版本 | Maven Central metadata `lastUpdated` 变化 + CHANGES.md |

**复核周期建议**：**每 6 个月**（或每次 targetSdk 大版本升级时）复查一次 Maven 元数据与 T2；复核成本约 10 分钟，结论若仍为「维持」，只需更新本文件日期与元数据快照。

---

## 七、若迁移：预置实施计划与验收标准（仅在触发后执行）

> 本节为**预案**，本次评估**不执行**。目标方案已按 §四 的取舍固定为 **CameraX（当前稳定版 1.6.2）+ `com.google.mlkit:barcode-scanning`（bundled）**；不采用 unbundled（引入 GMS 运行时依赖与首用模型下载），不采用 Google 扫码器（取景与解码整体交由 Play 服务，去 GMS 设备不可用）。

### 7.1 改动面估算

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `camerax = "1.6.2"`、`mlkitBarcode = "17.3.0"` 两个 version 与 5 个 library 别名（`camera-core/camera-camera2/camera-lifecycle/camera-view` + `mlkit-barcode-scanning`）；删除 `zxing-embedded` 别名与 `zxing` version |
| `app/build.gradle.kts` | +5 行 `implementation`，-1 行 `libs.zxing.embedded`（`:142`）；若走 AAB 需新增 `bundle{}` 决策 |
| `app/src/main/AndroidManifest.xml` | **必改**：显式新增 `<uses-permission android:name="android.permission.CAMERA"/>`；按需补 4 条相机相关 `uses-feature`（`camera`/`camera.front`/`camera.autofocus`/`camera.flash`，均 `required="false"`，语义与 zxing 现状保持一致以避免 Play 设备过滤行为变化；zxing 另注入的 `screen.landscape`/`wifi` 两条与本功能无关，可一并去除） |
| **新增** `app/src/main/java/com/keepasskey/app/ui/screens/edit/QrScannerDialog.kt` | Compose 对话框：`AndroidView(PreviewView)` + `rememberLauncherForActivityResult(RequestPermission())` + 拒绝授权降级提示 + `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` + `BarcodeScanning.getClient()` + `InputImage.fromMediaImage()` + **仅识别 `FORMAT_QR_CODE`**（保持与现状 `ScanOptions.QR_CODE` 等价的行为面）+ 资源释放（`ImageProxy.close()`、`scanner.close()`） |
| `app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditScreen.kt` | 替换 `:69-70` import、`:177-179` launcher、`:275-283` 启动参数；`onScanTotpQr` 回调签名不变 → **`EntryEditComponents.kt` 无需改动** |
| `app/proguard-rules.pro` | 删除 `:80-83` 的 zxing 相关 keep/dontwarn（CameraX 与 ML Kit AAR 均自带 `proguard.txt` 消费者规则，实测确认） |
| `docs/` | 更新 Google Play 数据安全申报说明（新增 Diagnostics / Device or other IDs / App info）与体积基线记录 |

**量级**：**4 个构建/清单/规则文件**（`libs.versions.toml`、`app/build.gradle.kts`、`AndroidManifest.xml`、`proguard-rules.pro`）+ **1 个新增 Kotlin 文件** + **1 个改造 Kotlin 文件**（`EntryEditScreen.kt`，`EntryEditComponents.kt` 不动）+ `docs/` 更新；无业务逻辑改动（TOTP 种子仍走 `onTotpSecretChangeSecure(CharArray)`，TASK-10 契约不变）。

### 7.2 分步实施顺序

1. **前置测量（必须先做）**：记录当前 `assembleRelease` 产物体积与本文件 §2.6 的 apkanalyzer 基线；决定分发形态（通用 APK 还是 AAB）—— 该决定直接决定体积是否可接受。
2. 引入 CameraX 依赖并**仅**接通取景预览（不动解码），确认编译与清单合并通过。
3. 引入 ML Kit 并接通 `ImageAnalysis` 解码；`QrScannerDialog` 打通「扫描 → 结果回填种子输入框」。
4. 切换 `EntryEditScreen` 调用点；确认 `EntryEditComponents` 未受影响。
5. 移除 zxing 依赖与 R8 规则；**验证合并清单中 CAMERA 仍在**（来源应变为自有清单）。
6. 全量测试 + R8 release 构建 + 体积复测 + 真机回归。
7. 更新 Play 数据安全表单与文档。

**可回退性**：全部改动集中在 1 个 commit（5 个既有文件 + 1 个新文件），`git revert` 即可回到 zxing 路径（调用点唯一，无数据迁移、无 schema 变更）。

### 7.3 验收标准（若执行迁移）

1. `.\gradlew.bat test` 全绿，用例总数不低于当前 737 例基线（0 失败）。
2. `.\gradlew.bat assembleRelease`（R8）通过；**体积门禁**：记录迁移前后 `apkanalyzer dex packages` 与 APK/AAB 体积差，单设备增量超预算（建议 ≤ +3 MB/设备）即回退。
3. **真机验证（≥2 个 OEM，Android 16+）**：首次授权、**拒绝授权后的降级路径**、连续扫描、横竖屏旋转、低光照、扫错码（非二维码/非 otpauth 内容）不崩溃且不误填。
4. 扫描成功后 TOTP 种子正确回填，且认证器列表能正常出码（端到端可用性）。
5. 合并清单中 CAMERA 权限存在且来源为自有清单；无 zxing 残留（依赖、R8 规则、合并清单条目、`mapping.txt` 中无 `journeyapps`）。
6. Google Play 数据安全表单已按官方《Android 数据披露》页更新并留档。

---

## 八、残余与风险（如实登记）

1. **Camera1 的长期风险未量化**：`android.hardware.Camera` 自 API 21 起 deprecated，但官方**未给出移除时间表**；本评估未检索到任何「将在某个 API 级别移除」的官方声明。该风险以 T2 作为监视线。
2. **未做漏洞数据源专门核查**：本次未核查 zxing / zxing-core 的 CVE 与 GitHub Security Advisory，也未运行 `dependency-check`。传递版本 `com.google.zxing:core:3.4.1` 明显偏旧（上游已 3.5.4），且**仓库内无版本约束**，存在静默漂移空间。→ 建议并入 ISSUE-P3-09（供应链批次）统一核查，勿在本条目内闭环。
3. **「对焦更流畅」未被量化**：本工作区无真机、无性能采集，该主张在决策中记「未证实」而非「不成立」；若未来真机验证证伪现状稳定性，应立即转为 T1 触发。
4. **体积数字的时效性**：§2.6 基于 2026-09-10 的 release APK；§三 的版本号基于当日 Google Maven / Maven Central 元数据快照。上游发新版（CameraX 1.6.x/1.7、ML Kit 17.4+）后需刷新。
5. **分发形态未确认**：本仓库**无** `bundle{}` / `splits{}` / `abiFilters`（app 模块）配置，评估按「通用 APK」给出主口径并同时列出 AAB 口径；若实际以 AAB 上架，则成本项 #1（体积）的结论强度会显著减弱，但**不改变决策**（决定性项为 #6 数据安全申报与 #4 无缺陷驱动）。
6. **迁移方案中的 CameraX dex 增量未实测**：仅测得 AAR `classes.jar` 未收缩体积（≈2.54 MB），R8 后实际增量需在实施时经 apkanalyzer 实测。

---

## 九、来源清单

### 9.1 官方文档 / 规范

| # | 来源 | 用于佐证 |
|---|---|---|
| 1 | [Scan barcodes with ML Kit on Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android) | bundled/unbundled 对比（+2.4 MB / +200 KB）、依赖坐标与版本、API 23+ 要求 |
| 2 | [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms)（Last modified 2025-05-14） | 输入数据与结果输出不上传 Google；**会发送性能与使用情况指标**；开发者承担告知义务 |
| 3 | [为满足 Google Play 的数据披露要求做准备（ML Kit Android Data Disclosure）](https://developers.google.com/ml-kit/android-data-disclosure) | 逐项列明 ML Kit 收集的数据类型（设备信息/应用信息/每次安装标识符/性能指标/API 配置/输入输出尺寸/错误码）；条码扫描自动缩放附加采集项 |
| 4 | [ML Kit Guides（Overview）](https://developers.google.com/ml-kit/guides) | 「All ML Kit APIs require Android API level 23 or higher」；API 全部在设备端运行 |
| 5 | [Google 扫码器（仅限 Android）](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) | 免 CAMERA 权限、委托 Play 服务、图像设备端处理且 Google 不存储结果/图像 |
| 6 | [Reduce Android app package size](https://developers.google.com/ml-kit/tips/reduce-app-size) | 动态功能模块、`abiFilters` 排除未用架构等减包手段 |
| 7 | [ML Kit 从 Firebase 迁移（Android）](https://developers.google.com/ml-kit/migration/android) | 现行 artifact 坐标（`com.google.mlkit:barcode-scanning:17.3.0`、`play-services-mlkit-barcode-scanning:18.3.1`） |
| 8 | [Camera（API reference，android.hardware.Camera）](https://developer.android.com/reference/android/hardware/Camera) | 「Deprecated in API level 21」 |
| 9 | [Camera（已废弃）开发者指南](https://developer.android.com/media/camera/camera-deprecated/photobasics) | 官方建议改用 CameraX |
| 10 | [CameraX 概览](https://developer.android.com/media/camera/camerax) / [CameraX 版本说明](https://developer.android.com/jetpack/androidx/releases/camera) | CameraX 定位与发布渠道 |
| 11 | [Android 数据类型指南（collect & share）](https://developer.android.com/guide/topics/data/collect-share) | 数据安全表单的数据类型映射依据 |

### 9.2 版本元数据（直接抓取，HTTP 200）

| # | 来源 | 关键读数 |
|---|---|---|
| 12 | [androidx.camera:camera-core maven-metadata.xml](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/maven-metadata.xml) | `latest = 1.7.0-alpha03`；最新稳定 `1.6.2`；`lastUpdated = 20260826170711` |
| 13 | [com.google.mlkit:barcode-scanning maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/mlkit/barcode-scanning/maven-metadata.xml) | `latest = release = 17.3.0`；`lastUpdated = 20240807012204` |
| 14 | [play-services-mlkit-barcode-scanning maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-barcode-scanning/maven-metadata.xml) | `latest = release = 18.3.1`；`lastUpdated = 20240807012204` |
| 15 | [play-services-code-scanner maven-metadata.xml](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-code-scanner/maven-metadata.xml) | `latest = release = 16.1.0`；`lastUpdated = 20230801152200` |
| 16 | [com.journeyapps:zxing-android-embedded maven-metadata.xml](https://repo1.maven.org/maven2/com/journeyapps/zxing-android-embedded/maven-metadata.xml) | `latest = release = 4.3.0`；`lastUpdated = 20211025140415` |
| 17 | [com.google.zxing:core maven-metadata.xml](https://repo1.maven.org/maven2/com/google/zxing/core/maven-metadata.xml) | `latest = release = 3.5.4`；`lastUpdated = 20251111211538` |
| 18 | [zxing-android-embedded CHANGES.md](https://github.com/journeyapps/zxing-android-embedded/blob/master/CHANGES.md) | 「4.3.0 (2021-10-25)」 |
| 19 | [GitHub REST：仓库元数据](https://api.github.com/repos/journeyapps/zxing-android-embedded) | `archived: false`、`pushed_at: 2024-08-04`、127 open issues、`forks: 1289` |
| 20 | [GitHub REST：master 最近提交](https://api.github.com/repos/journeyapps/zxing-android-embedded/commits?per_page=3) | 最后提交 `d09b7c7` @ **2022-10-21**（"Fix Typo"） |

### 9.3 本机实测（可复现命令）

| 证据 | 复现方式 |
|---|---|
| 扫码相关 dex 体积（`com.google.zxing` 310,275 B / `com.journeyapps` 47,219 B / TOTAL 8,520,771 B） | `apkanalyzer dex packages --defined-only app\build\outputs\apk\release\app-release.apk` |
| APK 体积与 `.so` 未压缩存储 | APK zip 条目读数（`lib/*` 的 raw == compressed；合并清单 `android:extractNativeLibs="false"`） |
| R8 保留类数（journeyapps 74 / zxing 295 顶层类；`usage.txt` 仅移除 12 + 3） | `app\build\outputs\mapping\release\mapping.txt` / `usage.txt` 计数 |
| zxing AAR 依赖 Camera1（8 个类命中 `android/hardware/Camera`，0 命中 camera2/CameraX） | 解压 AAR → `classes.jar` → 80 个 `.class` 常量池 ASCII 检索 |
| zxing 无网络 I/O（`HttpURLConnection`/`Socket`/`java.net.URL`/`java.net.URI` 命中 0；仅 `DecodeHintManager` 引用 `android/net/Uri`） | 同上；`zxing:core` 264 个类中仅 `ResultParser`/`VCardResultParser` 引用 `java.net.URL`/`URI`（字符串解析用途，本项目路径未调用） |
| 各 AAR `minSdkVersion` 与体积、`.tflite`/`.so` 构成 | 解压 AAR 读 `AndroidManifest.xml`；zip 条目分组统计 |
| CameraX / ML Kit AAR 自带 `proguard.txt`；zxing AAR 无 | AAR zip 条目名检索 |

---

## 十、实施动作清单（本条目**不执行**，供主控决定是否另立条目）

以下为 §五 决策之外的**低成本、非迁移性**改进，均为可选，且**不属于 ISSUE-P3-05**（本条目为纯评估，零代码改动）：

1. **显式声明 CAMERA 权限**：把 `android.permission.CAMERA` 与 4 条相机相关 `uses-feature(required=false)` 写入 `app/src/main/AndroidManifest.xml`，消除「扫码权限取决于第三方库清单合并」的隐式耦合（未来一旦移除 zxing，权限会静默消失）。
2. **收敛扫码调用点**：在调用点与 zxing 之间加一层薄接口（当前仅 1 处调用，成本≈0），使未来触发式迁移只影响 1 个文件。
3. **标注冻结状态**：在 `gradle/libs.versions.toml` 顶部「版本货币性核对」注释中把 zxing 标注为「上游冻结（4.3.0 / 2021-10-25），已评估维持（本文件）」，防止后续重复评估。
4. **传递版本可见化**：`com.google.zxing:core` 目前为无约束的传递依赖（3.4.1），建议纳入 ISSUE-P3-09 的依赖核查范围。

---

**评估结论重申**：**维持现状**。本文件即为验收标准「输出清晰的评估结论与决策（迁移或维持当前稳定方案），回写记录」的交付物；`docs/ACTIVE_ISSUES.md` 未被修改（由主控在集成阶段统一流转）。
