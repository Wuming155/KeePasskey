# 原生 Argon2 真机 / 设备侧验证记录

> 归属条目：**ISSUE-P3-11**（Rust Argon2 原生内核的真机 instrumented 验证，ISSUE-P2-14 遗留）
> 续办条目：**ISSUE-P3-23**（arm64 真机 instrumented 验证与真实 Argon2 `.kdbx` 语料端到端解锁）
> 关联：`plans/rust-enclave-poc.md` §2 Batch 4；风险 R6；决策闸门 R1。
> **本文件只登记真实执行得到的数字。任何未实际取得的数据一律标注「未取得 / 待填」，严禁估算或编造。**

---

## 0. 本次（2026-09-10，ISSUE-P3-23）**实际交付范围**与**未取得项**（如实声明）

**本轮实际完成的（工程侧就绪，非设备验证）**：

1. `database` 模块**首次**建立 `androidTest`（instrumented）源集与 Gradle 接线
   （`database/build.gradle.kts`：`defaultConfig.testInstrumentationRunner` + `androidTestImplementation`）；
2. 设备侧**真实 `.kdbx` 语料端到端解锁用例**落地
   （`database/src/androidTest/java/com/keepasskey/database/RealKdbxCorpusUnlockTest.kt`），
   采用 **fail-closed** 语义：语料缺失时 JUnit `Assume` **显式跳过**，跳过 ≠ 通过；
   另附显式标注「非互操作证据」的自生成 round-trip 用例；
3. 设备侧语料落位目录 `database/src/androidTest/assets/argon2-interop/`（含落位说明，**非语料**）；
4. 语料**逐步可执行生成清单**（KeePass 2.61.1 / KeePassXC / 命名约定 / 伴生 `.json` schema / 双落位位置）
   写入 `crypto/src/test/resources/argon2-interop/README.md`；
5. arm64 可行性**只读探测**（见 §1.5）。

**本轮未取得的（一律不填、不估、不外推）**：

| 未取得项 | 原因（如实） |
|---|---|
| **arm64 真机 / arm64 模拟器性能与加载数据** | 本机 `system-images` 中已安装镜像**只有 `x86_64`**（无任何 arm64-v8a）；`adb devices -l` **无任何真机连接**；安装 arm64 镜像属大体积下载，**超出本轮任务范围，未执行**。详见 §1.5 与 §4.3 |
| **真实 KeePass / KeePassXC `.kdbx` 语料** | 语料需人工 GUI 建库并逐条复核（一次性口令 + 零真实条目），**本轮未入库**；仓库内现有 `.kdbx` 均不可充当（见 `crypto/src/test/resources/argon2-interop/README.md` §5.1） |
| **设备侧端到端解锁的绿证（任何 ABI）** | 依赖上一条：语料未入库 → 用例按设计**跳过**。**「用例写好了」不等于「验证通过了」** |

> ⚠️ 本文件此前登记的 x86_64 模拟器数据（§4.2 / §5）是**真实绿证**，本轮**原样保留、未做任何修改**。
> 本轮**没有**产生任何新的设备侧数字。

---

## 1. 环境探测结论（原样记录，2026-09-10）

### 1.1 `adb devices` —— 会话开始时刻

```text
PS> adb devices -l
List of devices attached

```

**结论：会话开始时无任何已连接设备/已运行模拟器**（输出为空列表）。

### 1.2 设备资源可用性探测（SDK 侧）

```text
PS> Get-Command adb
adb path: D:\Android\SDK\platform-tools\adb.exe

PS> $env:ANDROID_HOME
D:\Android\SDK

PS> Test-Path D:\Android\SDK\emulator\emulator.exe
True

PS> Get-ChildItem D:\Android\SDK\system-images -Recurse -Depth 2 -Directory
D:\Android\SDK\system-images\android-34\google_apis\x86_64
D:\Android\SDK\system-images\android-36.1\google_apis\x86_64

PS> Get-ChildItem D:\Android\SDK\ndk -Directory
28.2.13676358

PS> Get-ChildItem "$env:USERPROFILE\.android\avd"
Pixel_10.avd
Pixel_10.ini
```

**结论（关键更正）**：本机**并非「无设备资源」**——
`emulator.exe`、`system-images\android-36.1\google_apis\x86_64` 与本机 AVD `Pixel_10` **均已存在**，
只是当时**没有正在运行的设备**。因此在无真机的前提下，**可以**通过启动本机 AVD 取得设备侧运行时证据。

**仍然存在的硬缺口**：`system-images` **只有 `x86_64`，没有任何 `arm64-v8a` 镜像**，
且本机无 arm64 真机 —— **arm64 运行时证据本轮无法取得**。

### 1.3 实际启动的模拟器与其事实

启动命令（后台、无窗口）：

```powershell
& D:\Android\SDK\emulator\emulator.exe -avd Pixel_10 -no-window -no-audio -no-boot-anim `
    -no-snapshot -gpu swiftshader_indirect
```

设备事实（`adb shell getprop` 原样）：

| 属性 | 值 |
|---|---|
| `ro.product.model` | `sdk_gphone64_x86_64` |
| `ro.product.cpu.abi` | **`x86_64`** |
| `ro.product.cpu.abilist` | `x86_64,arm64-v8a` |
| `ro.build.version.release` | `16` |
| `ro.build.version.sdk` | `36` |
| `ro.build.fingerprint` | `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:userdebug/dev-keys` |
| `ro.dalvik.vm.native.bridge` | `libndk_translation.so`（存在 ARM 转译层，但主 ABI 仍为 x86_64） |

AVD 配置：`abi.type=x86_64`、`hw.cpu.ncore=4`、`hw.ramSize=2048`、`target=android-36.1`、`PlayStore.enabled=false`。

宿主：`AMD Ryzen 7 8845HS`，Windows x86_64。

> ⚠️ **口径声明**：以下设备侧数据来自 **x86_64 模拟器**，
> **不是 arm64 真机数据，不得冒充 arm64 真机结论**。

### 1.4 工具链

```text
cargo 1.97.1 (c980f4866 2026-06-30)
cargo-ndk 4.1.2
NDK 28.2.13676358
```

### 1.5 本轮（2026-09-10，ISSUE-P3-23）arm64 可行性**只读**探测（命令与输出原样摘录）

> 本轮**只做只读查询**：未执行 `sdkmanager --install`，未下载任何镜像，未安装任何组件。

```powershell
PS> $env:ANDROID_HOME
D:\Android\SDK

PS> Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse -Depth 2 -Directory
D:\Android\SDK\system-images\android-34
D:\Android\SDK\system-images\android-36.1
D:\Android\SDK\system-images\android-34\google_apis
D:\Android\SDK\system-images\android-34\google_apis\x86_64
D:\Android\SDK\system-images\android-36.1\google_apis
D:\Android\SDK\system-images\android-36.1\google_apis\x86_64

PS> Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse -Directory |
      Where-Object { $_.FullName -match 'arm64' }
(无任何路径含 arm64)

PS> & "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
List of devices attached
                        ← 空列表：无任何已连接真机 / 已运行模拟器

PS> & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed   # 摘录
  emulator                                         | 36.6.11       | Android Emulator
  ndk;28.2.13676358                                | 28.2.13676358 | NDK (Side by side) 28.2.13676358
  platform-tools                                   | 37.0.0        | Android SDK Platform-Tools
  system-images;android-34;google_apis;x86_64      | 8             | Google APIs Intel x86 Atom_64 System Image
  system-images;android-36.1;google_apis;x86_64    | 4             | Google APIs Intel x86_64 Atom System Image
                        ← 已安装的 system-image 仅此两项，均为 x86_64

PS> & "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
Pixel_10

PS> Select-String -Path "$env:USERPROFILE\.android\avd\Pixel_10.avd\config.ini" `
      -Pattern "abi.type|image.sysdir|tag.id"
abi.type=x86_64
image.sysdir.1=system-images\android-36.1\google_apis\x86_64\
tag.id=google_apis

PS> Test-NetConnection -ComputerName dl.google.com -Port 443 -InformationLevel Quiet
True                    ← 出网可达（仅 TCP 探测，未下载任何镜像）

PS> & "...\sdkmanager.bat" --list | Select-String "system-images;android-36.1;.*arm64"   # 远端可用性查询
system-images;android-36.1;google_apis;arm64-v8a                                | 4 | Google APIs ARM 64 v8a System Image
system-images;android-36.1;google_apis_playstore;arm64-v8a                      | 4 | Google Play ARM 64 v8a System Image
system-images;android-36.1;google_apis_ps16k;arm64-v8a                          | 4 | 16 KB Page Size Google APIs ARM 64 v8a System Image
（另有 android-36 / android-35 / android-34 等 arm64-v8a 条目若干，此处仅摘录与本工程基线相关的行）
```

**探测结论（逐条，均有上方输出支撑）**：

1. 本机 SDK **已安装**的 system-image **只有 `x86_64` 两个**（`android-34`、`android-36.1`），
   **没有任何 arm64-v8a 镜像**；
2. 本机 AVD 仅 `Pixel_10` 一个，`abi.type=x86_64`、`image.sysdir.1=...google_apis\x86_64\`；
3. `adb devices -l` 为空 —— **无任何真机、也无正在运行的模拟器**；
4. **远端仓库确实发布 arm64-v8a 镜像**（例如 `system-images;android-36.1;google_apis;arm64-v8a`，rev 4，
   与本机已装 x86_64 同 rev），出网亦可达 —— 因此阻塞的准确表述是
   **「arm64 镜像未安装，而非不存在」**；
5. 本轮**未安装**该镜像：属大体积下载（GB 级）与长耗时操作，**超出本任务范围**（任务书明确禁止 `--install`）；
6. **即便安装也无法产出「arm64 真机」数据**：该镜像面向 ARM 宿主（Apple Silicon / ARM Linux）；
   本机宿主为 `AMD Ryzen 7 8845HS`（x86_64 Windows），运行 arm64 镜像需 QEMU 全指令模拟、无硬件加速。
   > 该条为**工程常识性判断，非本轮实测**（本轮未安装、未启动任何 arm64 设备，故**不登记任何 arm64 数字**）。
   > 且按 §7 变更纪律，arm64 **模拟器**数据与 arm64 **真机**数据必须分表登记，不可互相替代。

---

## 2. 测试执行命令

| 目的 | 命令 |
|---|---|
| 宿主侧单测（含 Batch 4 原生运行时验证） | `.\gradlew.bat :crypto:test --console=plain` |
| instrumented 测试**编译**（工程就绪证据） | `.\gradlew.bat :crypto:assembleDebugAndroidTest --console=plain` |
| instrumented 测试**设备侧运行** | `.\gradlew.bat :crypto:connectedDebugAndroidTest --console=plain` |
| 仅跑本条目新增用例 | `.\gradlew.bat :crypto:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.keepasskey.crypto.kdf.NativeArgon2InstrumentedTest` |

设备侧前置：`adb devices` 必须至少有一台 `device` 状态设备；无真机时先启动 AVD（见 §1.3）。

---

## 3. 判定标准（R1 决策闸门）

> **判定式：`native_ms ≤ 2.0 × bc_ms`**（`NATIVE_VS_BC_MAX_RATIO = 2.0`）

- 语义不是「原生必须更快」，而是**禁止 Rust 迁移引入数量级性能回退**；
- 同一阈值同时被宿主侧 `NativeArgon2HostJniTest` 与设备侧 `NativeArgon2InstrumentedTest` 复用；
- 测量方法：**先预热、再多轮采样取中位数**（避免单次调度抖动造成假失败）；
  设备侧预热 `PERF_WARMUP_SAMPLES = 1` 次、采样 `PERF_MEASURED_SAMPLES = 5` 次取中位数。

---

## 4. 数据表

### 4.1 宿主侧 Batch 4 数据（历史归档，来源：`docs/RESOLVED_LOG.md` ISSUE-P2-14 条目）

档位：**Windows x86_64 宿主，m=16 MiB，t=2，warmup 后取 3 次最优**（由 `NativeArgon2HostJniTest` 产出）：

| 档位 | Rust 原生 | BouncyCastle | 加速比（BC/原生） | 闸门（≤2×） |
|---|---|---|---|---|
| p=1 | 11.6 ms | 25.8 ms | 2.22× | 通过 |
| p=2 | 6.8 ms | 21.5 ms | 3.15× | 通过 |
| p=4 | 3.9 ms | 20.8 ms | 5.37× | 通过 |

### 4.2 设备侧 x86_64 模拟器数据（**本轮真实测得**）

档位：**Android 16 / API 36，ABI `x86_64`，`sdk_gphone64_x86_64`，4 核 / 2048 MB，
m=64 MiB，t=2，warmup 1 次后取 5 次中位数**（由 `NativeArgon2InstrumentedTest` 产出）。

#### 4.2.1 定稿数据（来自**最终绿跑**，`failures=0` / exit code `0`，2026-09-10 06:35:14–06:35:25）

| 档位 | Rust 原生 | BouncyCastle | 加速比（BC/原生） | 闸门（≤2×） |
|---|---|---|---|---|
| **p=2** | **132.1 ms** | **657.7 ms** | **4.98×** | **通过** |
| **p=4** | **91.6 ms** | **771.7 ms** | **8.42×** | **通过** |

原始输出（设备 logcat `System.out`，逐字摘录，未做任何加工）：

```text
[Argon2 性能对照 · 设备 x86_64, arm64-v8a] t=2, m=64MiB, warmup=1 次后取 5 次中位数
  t=2 m=64MiB p=2: native=132.1ms bc=657.7ms 加速比=4.98x
  t=2 m=64MiB p=4: native=91.6ms bc=771.7ms 加速比=8.42x
```

产物位置（可从磁盘复核）：
`crypto/build/outputs/androidTest-results/connected/debug/Pixel_10(AVD) - 16/logcat-com.keepasskey.crypto.kdf.NativeArgon2InstrumentedTest-______BouncyCastle_______R1__.txt`

#### 4.2.2 重复性观察（**同批次中间绿跑的另一次观测**，如实登记）

同一档位、同一 AVD，在修正测试断言**之前**的一次运行（2026-09-10 06:26:39–06:26:57，该次亦为 7 例全绿）中测得：

| 档位 | Rust 原生 | BouncyCastle | 加速比 |
|---|---|---|---|
| p=2 | 164.4 ms | 1150.8 ms | 7.00× |
| p=4 | 131.8 ms | 1445.4 ms | 10.97× |

> ⚠️ **该次观测的报告产物已被后续运行覆盖，无法再从磁盘独立复核**，故仅作重复性参考，不作为定稿数据。
>
> **两次观测的离散度**：原生 91.6 ~ 164.4 ms（≈±28%），BC 657.7 ~ 1445.4 ms（≈±37%）。
> 离散来源：模拟器 `swiftshader_indirect` 软件渲染占 CPU、宿主同时承载多个并行 Gradle 构建
> （本机曾出现 14 个 JVM / 10.8 GB 占用）、以及 AVD 仅 4 核。
> **两次观测下 R1 闸门均以很大余量通过**，但**单次绝对值不应被当作设备的稳定性能指标**——
> 复现时请记录宿主负载，并优先看多次运行的最小值/中位数趋势。

观察：p=2 → p=4 原生耗时 132.1 ms → 91.6 ms（约 1.44×），说明该 **模拟器** 上多核收益有限，
**不代表 arm64 真机的 p 扩展性**；宿主侧 Batch 4 的 p1→p4 为约 3×。

### 4.3 **arm64 真机数据 —— 未取得（待填）**

> **本次核实加注（2026-09-10，ISSUE-P3-23）—— 该表继续保持「待填」，本轮未填入任何数字。**
>
> 本轮实际完成的只有 **`database` 模块 androidTest 源集接线**与**语料获取方案**（见 §0），
> **arm64 真机 / arm64 模拟器数据本轮均未取得**，原因（均有 §1.5 的命令输出支撑）：
> 1. 本机 SDK **已安装**的 system-image 只有 `x86_64`（`android-34` / `android-36.1`），
>    **无任何 arm64-v8a 镜像**；
> 2. `adb devices -l` 为空 —— **无任何真机连接**；
> 3. 未安装 arm64 镜像（大体积下载 + 长耗时，**超出本任务范围**，任务书明确禁止 `--install`）；
>    且即便安装，x86_64 宿主上的 arm64 镜像也只能产出 **arm64 模拟器**数据，
>    按 §7 纪律**不得**与「arm64 真机」同表登记。
>
> **该表任何一格都不得由 x86_64 模拟器数据（§4.2）、宿主侧 Batch 4 数据（§4.1）或任何估算/外推值填入。**

**状态：未达成。阻塞原因：本机无 arm64 真机，且 SDK `system-images` 中无 `arm64-v8a` 镜像
（远端仓库有发布但未安装，见 §1.5）。**

| 档位 | Rust 原生 | BouncyCastle | 加速比 | 闸门（≤2×） |
|---|---|---|---|---|
| arm64 真机 p=2（t=2, m=64MiB） | 待填 | 待填 | 待填 | 待填 |
| arm64 真机 p=4（t=2, m=64MiB） | 待填 | 待填 | 待填 | 待填 |

补测方法（连上 arm64 真机后）：

```powershell
adb devices -l                     # 确认 arm64 真机为 device 状态
adb shell getprop ro.product.cpu.abi   # 应输出 arm64-v8a
.\gradlew.bat :crypto:connectedDebugAndroidTest --console=plain
# 设备侧数据从以下文件读取（stdout 走 logcat）：
#   crypto\build\outputs\androidTest-results\connected\debug\<设备名>\logcat-*R1*.txt
```

---

## 5. 运行时加载证据（验收标准 1 的直接证据）

设备侧 logcat `System.out` 原样（来自 §4.2.1 的同一次最终绿跑）：

```text
[NativeArgon2 设备侧加载证据] loadedAbi=x86_64, supportedAbis=x86_64, arm64-v8a,
  nativeLibraryDir=/data/app/~~UZnYILI4hFZU5RX2P_QFwQ==/com.keepasskey.crypto.test-7U8H6TUqQdhPoO0FNST8Tg==/lib/x86_64,
  apk=/data/app/~~UZnYILI4hFZU5RX2P_QFwQ==/com.keepasskey.crypto.test-7U8H6TUqQdhPoO0FNST8Tg==/base.apk,
  lib/x86_64/libkeepasskey_argon2.so=477976 bytes,
  磁盘解包副本=false
```

要点：
1. **`loadedAbi=x86_64`** —— 运行时实际选用 x86_64 分支，**不是**经 `libndk_translation.so` 转译的 arm64；
2. **`lib/x86_64/libkeepasskey_argon2.so=477976 bytes`** —— 测试用 `java.util.zip.ZipFile` 直接在**被测 APK 内部**
   定位该条目并读取字节数（> 0 为断言），这是「APK 内确含本工程 cargo-ndk 产物」的直接证据；
   该字节数与打包期核对值（`docs/RESOLVED_LOG.md`：x86_64 strip 后 478.0 KB）一致；
3. **`磁盘解包副本=false` 是预期行为，非缺陷**：AGP 默认 `extractNativeLibs=false`，
   原生库**不从 APK 解包落盘**，而是由 `System.loadLibrary` 直接在 APK 内 mmap 加载。
   > 该事实由本条目的一次**真实失败**反向证实：首版用例误断言「`nativeLibraryDir` 下 `exists()`」，
   > 运行时如实报出 `exists:false, size:-1` 并失败（logcat 仍可从历史产物目录回溯）。
   > 判据已修正为「在 APK 内定位条目 + `available == true` + 冻结向量逐字节一致」。

---

## 6. 验收标准对应关系（如实裁决）

| 验收标准 | 裁决 | 依据 / 阻塞 |
|---|---|---|
| 1. `connectedAndroidTest` 能加载 APK 内 `libkeepasskey_argon2.so`，断言 `available == true` 且派生与 BC 冻结向量逐字节一致 | **x86_64 模拟器上已达成**；**arm64 真机未达成** | 7 例 instrumented 用例全绿（§4.2 / §5）；arm64 阻塞：无 arm64 镜像/真机 |
| 2. 真实 KeePass 2.61.1 / KeePassXC 生成的 Argon2d/id `.kdbx` 语料端到端解锁 | **仍未达成**（原「工程阻塞」已消除，剩余阻塞为语料本身） | **2026-09-10 核实（ISSUE-P3-23）**：① **语料仍未入库** —— 需人工 GUI 建库并逐条复核（一次性口令 + 零真实条目），无法在无人值守流程内产出；生成清单见 `crypto/src/test/resources/argon2-interop/README.md` §3；② 原阻塞「`database` 无 `androidTest` 源集」**已消除** —— `database/build.gradle.kts` 已接线 `testInstrumentationRunner` 与 `androidx.test.*`，用例 `database/src/androidTest/java/com/keepasskey/database/RealKdbxCorpusUnlockTest.kt` 已落地，采用 **fail-closed** 语义（语料缺失 → `Assume` 显式跳过，**跳过 ≠ 通过**；语料在但伴生元数据不全 → 硬失败）。**「用例就绪」不等于「验证通过」**：在语料入库并于设备上跑出绿证之前，本项一律记为**未达成**。**与设备可用性无关**（现成 x86_64 AVD 即可执行，无需 arm64） |
| 3. 记录 arm64 真机性能数据并与 Batch 4 宿主侧并列归档，复核 R1 闸门 | **部分达成**：m=64MiB t=2 的 p=2 / p=4 数据已在 **x86_64 模拟器** 取得并归档（§4.2），R1 闸门通过；**arm64 真机数据未取得**（§4.3 待填） | 阻塞（2026-09-10 复核）：无 arm64 真机连接（`adb devices -l` 空）；arm64-v8a 镜像在远端仓库**有发布但本机未安装**（§1.5），安装属大体积下载，超出本轮范围 |

---

## 7. 变更纪律

- 本文件的性能数字**只允许**从 `crypto/build/outputs/androidTest-results/connected/debug/<设备名>/logcat-*.txt`
  或 `docs/RESOLVED_LOG.md` 的历史归档**逐字摘录**；任何估算、外推、「参考值」一律禁止；
- 新增设备数据时，必须同时登记**设备型号 / ABI / API 级别 / 内核档位 / 采样方法**，
  否则该行数据不可复核；
- 模拟器数据与真机数据**必须分表登记**，不得合并或互相替代；
- **「代码就绪 / 源集就绪」一律不得表述为「验证通过」**：本文件只登记真实执行得到的数字，
  未执行的项（含被 `Assume` 跳过的用例）一律记「未取得 / 待填」。

---

## 8. 核实时间点与核实方式（ISSUE-P3-28 起强制登记）

| 被核实结论 | 核实时间点 | 核实方式（可复现） |
|---|---|---|
| 本机 SDK **已安装** system-image 只有 `x86_64`，**无任何 arm64-v8a** | 2026-09-10 | `Get-ChildItem "$env:ANDROID_HOME\system-images" -Recurse -Depth 2 -Directory`（路径清单）＋ `Get-ChildItem ... -Recurse -Directory \| Where-Object { $_.FullName -match 'arm64' }`（返回空） |
| **无任何真机 / 无运行中设备** | 2026-09-10 | `& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l` → 空列表 |
| 已装组件清单（emulator / NDK / platform-tools / system-images） | 2026-09-10 | `& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed` |
| 本机 AVD `Pixel_10` 的 ABI 为 `x86_64` | 2026-09-10 | `emulator.exe -list-avds` ＋ `Select-String` 读 `~/.android/avd/Pixel_10.avd/config.ini` 的 `abi.type` / `image.sysdir.1` |
| 出网可达（`dl.google.com:443`），arm64 镜像在远端仓库**有发布但未安装** | 2026-09-10 | `Test-NetConnection dl.google.com -Port 443` → `True`；`sdkmanager.bat --list \| Select-String "system-images;android-36.1;.*arm64"` |
| `database` 模块 androidTest 源集与用例**已就绪**（工程状态，非设备结果） | 2026-09-10 | 读 `database/build.gradle.kts`（`testInstrumentationRunner` + `androidTestImplementation`）与 `database/src/androidTest/**` 落盘文件；**未执行任何 Gradle / connectedAndroidTest**（本轮禁止） |
| 真实 `.kdbx` 语料**仍未入库** | 2026-09-10 | `crypto/src/test/resources/argon2-interop/` 下除 `README.md` 与 `argon2-bc-vectors.json` 外无文件；`database/src/androidTest/assets/argon2-interop/` 下仅有落位说明 `README.md` |

> 上表**不含**任何设备性能数字；§4.1 / §4.2 / §5 的设备数字其核实时间点为 **2026-09-10 06:26–06:35**
> （原 ISSUE-P3-11 交付批次），证据为 `crypto/build/outputs/androidTest-results/connected/debug/` 下的
> logcat 产物与本文档逐字摘录，本轮**未重新执行**、**未改动**。

