# §327 扫码栈迁移与 ReDoS 正则修复批次（`ISSUE-P3-318` 闭环 + `ISSUE-P3-319` 整改进展）

> **批次性质**：双条目批次。`ISSUE-P3-318`（CodeQL py/redos 高严重度告警，工具链脚本正则）**本批闭环**；
> `ISSUE-P3-319`（扫码栈依赖治理）**整改代码同批入库**，其 AC③「真机实拍 TOTP 二维码成功回填种子」
> 需真机相机物理对准 PC 屏幕上的二维码（代理无法代为调整手机朝向），实拍读数取得后另行小批闭环归档
> ——权限拒绝 / 重试授权路径与对话框窗口三层加固已在本批真机实证（见 §3）。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P3-318` CodeQL py/redos #355 / #356：`generate_screenshot_test_wrappers.py:30` `PREVIEW_BLOCK` 参数组 `(?:\((?:[^()\n]|\([^()\n]*\))*\))?` 指数回溯 | `GET /code-scanning/alerts?state=open` 实读（本批复现：两条 `state=open`、`most_recent_instance.commit_sha=cd29e7ef`、行 30、`security_severity_level=high`） | **闭环**（正则改写线性等价形态 + 产物逐字节不变 + CodeQL 由修复后巡检自动判定 fixed） |
| `ISSUE-P3-319` 扫码栈依赖治理：移除 `zxing-android-embedded`，重构 CameraX + `zxing:core` 纯离线 Compose 对话框 | 条目正文（2026-09-25 核实） | **整改代码入库**，AC①②④⑤ 已满足、AC③ 权限路径半验证（实拍回填待物理对准），条目保持开放并附进展记录 |

## 2. 整改内容

### 2.1 `ISSUE-P3-318`：ReDoS 正则线性等价改写

`PREVIEW_BLOCK` 参数组改写：

```
原：(?:\((?:[^()\n]|\([^()\n]*\))*\))?
新：(?:\([^()\n]*(?:\([^()\n]*\)[^()\n]*)*\))?
```

- **消除歧义原理**：原 `(?:A|B)*` 中两交替分支对同一位置的消费方式不唯一；新形态把「非括号段」与
  「单层括号组」改为**确定性串接**（`(` 只能开组、组内只能是非括号字符），任一位置只有唯一解析路径，
  回溯为线性。
- **语言等价性实证**：以字母表 `a()00\n@` 随机生成 20,000 个长度 0~14 的样本，新旧正则 fullmatch
  判定**零分歧**；7 组手工构造用例（含未闭合括号、双层嵌套、CodeQL 描述的对抗形态成分）逐一比对一致。
- **行为零变更实证（AC②）**：改写前后各跑一次生成器，`promoted=80 wrappers=80 packages=17` 计数一致，
  `app/src/screenshotTest/kotlin` 产物 **`diff -r` 逐字节相同**；源面文件零改写（`git status` 无噪声）。
- **不采用 dismiss**（条目 ③）：告警系真实回溯结构，修复后由 CodeQL 对修复 commit 的巡检自动判定 fixed。

### 2.2 `ISSUE-P3-319`：扫码栈迁移（代码面）

依赖与构建面：
- `libs.versions.toml`：删 `zxing = "4.3.0"` 与 `zxing-embedded` 别名；新增 `camera = "1.6.2"`（stable）
  与 `zxingCore = "3.5.4"` 及四个 `camera-*` 别名 + `zxing-core` 别名。
- `app/build.gradle.kts`：`implementation(libs.zxing.embedded)` → `camera-core / camera-camera2 /
  camera-lifecycle / camera-view / zxing-core` 五行。
- `proguard-rules.pro` §11：删 `com.journeyapps.barcodescanner.**` 两条（依赖已移除）；zxing 仍无反射
  消费方，保守保留 `-keep com.google.zxing.**` 与 `-dontwarn`（不改变既有 release 混淆行为；
  `assembleRelease` 实跑通过）。

代码面（新增 `TotpScanDialog.kt`，383 行）：
- **取景**：`AndroidView(PreviewView)` + `Preview` 用例；分析流 `ImageAnalysis`
  `STRATEGY_KEEP_ONLY_LATEST`。
- **解码**：单线程执行器（后台线程）喂 `MultiFormatReader`，仅 `QR_CODE`（与原
  `ScanOptions.QR_CODE` 等价）+ `TRY_HARDER`；Y 平面 → `PlanarYUVLuminanceSource`（rowStride
  为 dataWidth，兼容行尾对齐 padding）→ `HybridBinarizer`；未命中按逆时针旋转重试 4 个方向
  （覆盖传感器 90°/270° 旋转与倒置）；单帧异常吞掉保证分析线程存活。
- **敏感数据铁律**：zxing `Result.text` 为框架边界 String（与原 `ScanContract` 回调同口径），
  **即刻**转 `CharArray` 回主线程上行 `onTotpSecretChangeSecure`；单次交付由 `AtomicBoolean`
  保证（连续帧不重复上行）；无任何 String 落地 / 持久化路径。
- **接线**：`EntryEditPickers.kt` 的 `scanTotpQr` 由 `ScanContract` 启动改为置位对话框状态并渲染
  `TotpScanDialog`；删除 `SecureCaptureActivity.kt` 与 Manifest 对应 `<activity>` 声明。
- **加固迁移口径（条目 ④，安全约束核心）**——逐项等效落地并真机实证（读数见 §3）：
  - (a) `FLAG_SECURE`：`DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`（对话框窗口
    该 flag 的实际决定者，`ISSUE-P2-246` 口径）+ `SecureDialogWindowEffect()` 同窗防御性施加；
  - (b) `setHideOverlayWindows(true)`：新增 `ScanDialogWindowHardening` 对**对话框窗口**直接施加
    （`HIDE_OVERLAY_WINDOWS` 权限既有显式声明），dispose 时复位；`filterTouchesWhenObscured`
    由既有统一包装 `SecureDialogWindowEffect` 施加（其设备侧行为已由
    `DialogWindowHardeningDeviceTest` 实证）；
  - (c) **方向策略裁决：不保留 `sensorLandscape`**——该方向锁是 zxing 全屏取景 UX 的产物；
    对话框嵌入编辑页流内，跟随 Activity 既有方向行为。裁决登记于本节。
- **Manifest**：`CAMERA` 显式声明（替换 zxing 传递注入，注释 ③ 口径同步改写）+ 配套
  `<uses-feature android:name="android.hardware.camera" android:required="false"/>`
  （显式声明后 lint `PermissionImpliesUnsupportedChromeOsHardware` 报 error，原由 zxing 清单传递
  补位；`required=false` 保证无相机设备仍可安装，扫码按权限拒绝路径如实降级）。
- **运行时权限**：`ActivityResultContracts.RequestPermission` 打开即请求一次；拒绝**不**自动重发
  （系统「不再询问」时 launch 会静默拒绝），改显式「重试授权」按钮驱动；拒绝态如实提示并保持
  扫码入口（编辑页扫码按钮）可见。
- 守卫测试迁移：`SensitiveWindowHardeningTest` 由守护 `SecureCaptureActivity.kt` 改为守护
  `TotpScanDialog.kt` 三处接线形态（`SecureFlagPolicy.SecureOn` / `setHideOverlayWindows(true)` /
  `SecureDialogWindowEffect()`）；`ManifestPermissionHygieneTest` 把 `CAMERA` 纳入「本模块自身
  运行期依赖的权限必须保留声明」清单（防再次回落为传递声明）。原 `edit_scan_totp_qr` 字符串
  （zxing prompt 专用）随消费方删除而移除，对话框文案 5 键中英成对新增。

## 3. 真机验证读数（Redmi 真机 `1c859bcc7d24`，§263 数据保护立规适用）

**前置核实**：设备上 `com.keepasskey` **未安装**（`pm list packages` 实读为空）⇒ 无 UTP 卸载
丢数据风险，debug 包直接安装（本批全程未跑 `connectedDebugAndroidTest`，纯 adb UI 驱动 + dumpsys）。

已实证（uiautomator dump + logcat + dumpsys window 取证，证据存 `build/evidence/`（gitignore））：
1. **权限请求路径**：点编辑页扫码按钮 → 系统 CAMERA 运行时权限弹窗出现（运行时契约生效）。
2. **权限拒绝路径（AC③ 后半）**：拒绝 → 对话框显示「相机权限被拒绝，无法扫码。可点击下方按钮
   重新授权，或到系统设置中开启相机权限」+「重试授权」按钮；**logcat 无任何 FATAL /
   AndroidRuntime 崩溃**（不崩溃、有如实提示、扫码入口保持可见）。
3. **重试授权路径**：重试 → 系统弹窗 → 允许 → 对话框内 `SurfaceView`（PreviewView）取景流出现 +
   「将二维码对准取景框」提示可见（CameraX 绑定成功）。
4. **对话框窗口三层加固（条目 ④ 硬证据）**：对话框打开时 `dumpsys window windows` 实读——
   对话框窗口（frame=[0,222][720,1106]）`fl=… SECURE …`（FLAG_SECURE 生效）、
   `pfl=… HIDE_NON_SYSTEM_OVERLAY_WINDOWS …`（setHideOverlayWindows(true) 生效）；宿主
   MainActivity 窗口 `fl=… SECURE …`（FlagSecureGuard 覆盖面含承载窗口）。附带：主窗口开启时
   `screencap` 整帧黑屏 = FLAG_SECURE 防截屏真机生效的直接表现。
5. **AC③ 前半（实拍回填种子）**：已把 `otpauth://totp/…secret=SB7CYK5DAEZHBZ223YLCSLZSPOWSIHF7…`
   二维码投至 PC 屏幕并轮询 2 分钟，真机取景流正常但未识别——**手机未对准屏幕**（物理朝向仅用户
   可调整）。该读数取得后按小批闭环归档。

## 4. 验证读数（原样粘贴）

```
xml=412 tests=2774 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 325 份；分册登记 327 条；全量索引 327 条；最大 §327）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 476 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

**lint 计数（条目 AC①「不劣于基线」）**：本批 240（含 `uses-feature` 修复后）= HEAD 基线 240
（`git stash -u` 后对 HEAD 实跑取证）——**持平零新增**；相对 §262 记录的 230 的 +10 漂移在
HEAD 基线中已存在，与本批无关（本批过程曾引入 1 条
`PermissionImpliesUnsupportedChromeOsHardware` error，已按上节补 `uses-feature` 归零）。
`grep -cE "^ *<issue$" app/build/reports/lint-results-debug.xml` = **240**。

**其余门禁**：`python tools/doc/long_functions.py` functions_ge_100=0（首版 `TotpCameraPreview`
105 逻辑行超阈，已拆出 `ScanCameraViewport` 复核）；`python tools/doc/count_line_tiers.py` tier1=0；
`python tools/kdbx-corpus/generate_corpus.py --check` 未触发（未改序列化）；
`:app:compileDebugScreenshotTestKotlin --rerun` **BUILD SUCCESSFUL**（改过生成器，P3-319 AC⑤
同条件跑过）；`./gradlew.bat :app:assembleDebug :app:assembleRelease` 双绿（R8 混淆规则改后
release 实证），release 合并清单 `journeyapps` 计数 **0**、debug 合并清单 **0**、`CAMERA` 为
源清单显式声明。

## 5. 涉及文件

代码：`gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/proguard-rules.pro`、
`app/src/main/AndroidManifest.xml`、`app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt`（新增）、
`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt`、
`app/src/main/java/com/keepasskey/app/security/SecureCaptureActivity.kt`（删除）、
`app/src/main/res/values{,-en}/strings.xml`、
`app/src/test/java/com/keepasskey/app/security/{SensitiveWindowHardeningTest,ManifestPermissionHygieneTest}.kt`、
`tools/export_previews/generate_screenshot_test_wrappers.py`。
文档：`docs/ACTIVE_ISSUES.md`（P3-318 移出闭环；P3-319 附进展，P3 3 → 2）、
`docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md`（§327 登记）。
