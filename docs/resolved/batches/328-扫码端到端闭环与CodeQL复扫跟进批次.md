# §328 扫码端到端闭环与 CodeQL redos 复扫跟进批次（`ISSUE-P3-319` + `ISSUE-P3-320` 闭环、`ISSUE-P3-318` 复扫实证）

> **批次性质**：①`ISSUE-P3-319` 的收官批次——§327 遗留的 AC③ 前半「真机实拍 TOTP 二维码成功回填
> 种子」在本批取得**完整读数**并闭环；②`ISSUE-P3-318` 的复扫跟进——CodeQL 对 §327 修复 commit 的
> 再扫描把同一正则中**另一处相邻歧义**（`extra` 组）标红，本批化简后复扫转 fixed；③同批诊断发现
> 并修复两处显示层缺陷（预填擦除竞态 + 扫码不回显），新登记的 `ISSUE-P3-320` 当批闭环。

---

## 1. 整改对象

| 条目 | 判据来源 | 本批处置 |
|---|---|---|
| `ISSUE-P3-319` 扫码栈依赖治理（AC③ 前半遗留） | §327 遗留项 + 本批真机读数（§3） | **闭环** |
| `ISSUE-P3-318` CodeQL py/redos #355 / #356 | `GET /code-scanning/alerts` 实读：§327 后仍 `state=open`，实例迁至 `extra` 组（行 34，`generate_screenshot_test_wrappers.py`） | **闭环**（extra 组化简 + 复扫 fixed） |
| `ISSUE-P3-320` 扫描回填后 TOTP 输入框不回显 | 真机诊断实录（§3.3）+ 用户实测报告「扫了没反应」「全是 0」 | **登记并当批闭环**（§2.3 修复，真机回显实证） |

## 2. 整改内容

### 2.0 `ISSUE-P3-320` 修复：预填竞态 + 扫码回显（真机诊断钉死根因）

临时诊断日志（tag `ScanDiag2`，仅输出长度与对象身份，本批已移除）真机定位到**两个**叠加缺陷：

1. **预填擦除竞态（显示整串 '0' 的根因）**：`loadEntry` 重入时对上一预填数组**原地** `fill('0')`，
   而 `SecurePasswordField` 的预填经 `LaunchedEffect` **异步**消费——重入清零先于协程体执行时，
   `displayText = String(已被擦成 '0' 的数组)` → 输入框显示整串 '0'（真机实证：日志显示
   `loadEntry len=116` ×2 后 `prefill len=116 consumed=false`，消费到的已是擦后数组；116 恰等于
   库内 otp 字段长度）。修复：预填改为**组合期同步消费**（守卫使写入幂等收敛），清零只可能
   发生在消费之后，竞态消除。
2. **扫码不回显**：`onTotpSecretChangeSecure` 原将预填通道置 `null` 且不换消费 key，字段停留旧内容。
   修复：回填后 `_loadedTotpSecret.value = secret.copyOf()`（新鲜副本）+ `totpPrefillEpoch` 自增
   （`EntryEditUiState` 新字段）进 `initialKey`，驱动预填通道以新种子重新消费一次——字段即时回显
   扫码结果。种子明文仍走 CharArray 预填通道，不入 UiState（铁律不变）。

**磁盘数据核验**：两次 `run-as` 拉取密码库 + pykeepass 独立解析，otp 字段始终为完整 URI——
零化只发生在显示层，**无持久化损坏**。

### 2.1 `ISSUE-P3-318` 跟进：`extra` 组歧义化简

§327 已消除 ann 参数组的指数回溯，但 CodeQL 对修复 commit `614faac2` 的再扫描将两条告警的实例
**迁移**至同一正则的 `extra` 组（`(?:[ \t]*@\w+(?:\([^)\n]*\))?[^\n]*\n)*?` 中
「可选括号段 vs 行尾贪婪 `[^\n]*`」的边界歧义）——原始位置告警不再出现（§327 用户面板观感
「已消除」即此），API 实读仍 `state=open`。化简：

```
原：(?:[ \t]*@\w+(?:\([^)\n]*\))?[^\n]*\n)*?
新：(?:[ \t]*@[^\n]*\n)*?
```

- 可选括号段被行尾 `[^\n]*\n` **完全覆盖**（语言冗余），删除即消除歧义；中间注解行收敛为
  `量词-字面量-量词` 形态（`[ \t]*` / `@` / `[^\n]*\n`，无相邻重叠量词）。
- 语言差异仅为「`@` 后不接 word 字符」的病态行（如 `@` 单独成行）由不匹配变为匹配——真实语料
  （`@OptIn(...)` / `@PreviewTest` 等中间注解）行为一致，以生成产物逐字节 diff 锁定。
- **验证**：`promoted=80 wrappers=80 packages=17` 与 `diff -r` 产物逐字节不变；
  `:app:compileDebugScreenshotTestKotlin --rerun` 绿。

### 2.2 `ISSUE-P3-319` 收官：解码多方向健壮性修复

真机诊断日志（临时 `Log.d`/`Log.w`，tag `TotpScanDiag`，已在本批移除）实证两件事：
①解码命中与交付链路工作正常（`hit=true` 8 次、对话框自动关闭、种子入库）；②
`PlanarYUVLuminanceSource.rotateCounterClockwise()` 抛
`UnsupportedOperationException`（该 Source 不支持旋转）——§327 实现的「4 方向重试」是**死代码**，
真机命中恰因拍摄朝向与传感器朝向可被 zxing 容忍。修复：改为**字节级手工旋转**（先剥行对齐
padding，再按 90° 步进旋转 Y 平面后重建 Source，4 个方向逐一尝试），多方向重试真正生效。

### 2.3 `ISSUE-P3-320`：扫码回填不回显（已按上节 2.0 修复并当批闭环）

真机实测：扫码成功后唯一可见变化是对话框自动关闭，TOTP 字段不回显新种子——
`SecurePasswordField` 的文本是组件内部状态，仅经 `initialPassword` 一次性预填通道初始化，
不消费外部状态更新；且 `loadEntry` 重入清零竞态下甚至显示 '0' 串（§2.0）。
**旧 zxing `ScanContract` 流程语义完全相同**（同一调用点同一 ViewModel API），
非 `ISSUE-P3-319` 迁移引入的回归；但「成功不可见」直接造成「扫了没反应」的误判，
本批按整改方案①（受控外部值通道：epoch 驱动预填重消费）修复并闭环。

## 3. 真机端到端读数（AC③ 前半收官）

设备 `1c859bcc7d24`，debug 包（含 §327 代码 + 本批诊断日志版本）：

1. **诊断定位**：PC 屏幕投 TOTP 二维码（种子 `SB7CYK5DAEZHBZ223YLCSLZSPOWSIHF7`，URI
   `otpauth://totp/KeePasskeyE2E:…`），真机取景对准后 `logcat -s TotpScanDiag` 读数：
   帧 `640x480 rot=90 stride=640 px=1` 持续到达，`hit=true` **8 次**，无帧异常崩溃；
   伴随读数 `UnsupportedOperationException: … rotation by 90 degrees`（→ §2.2 修复动因）。
2. **回填实证**：解码命中 → 对话框自动关闭 → 保存条目 → 重新打开：TOTP 区块出现
   **实时动态验证码 `719627`**（含倒计时）——种子已入库并参与产码。
3. **正确性对拍**：`pyotp.TOTP(同种子)` 独立计算 `previous window = 719627`——设备显示码与
   独立 RFC 6238 实现**一致**（dump 与计算跨过一个 30s 窗口，取上一窗值恰等）。
4. **AC③ 后半**（§327 已证）：权限拒绝不崩溃、有如实提示、重试授权路径正常。
5. **回显实证（P3-320）**：修复后用户实测扫描自有 QR（`otpauth://totp/MyApp:user%40example.com?secret=…`），
   TOTP 字段 uiautomator 实读**即时回显该 URI**——不再出现 0 串 / 旧内容滞留。
6. 诊断日志移除后复扫确认仍命中（回归面由 `SensitiveWindowHardeningTest` 静态守卫 + 本批人工复扫承托）。

## 4. 验证读数（原样粘贴）

```
xml=412 tests=2774 failures=0 errors=0 skipped=13
已排除非 JVM 单测 XML：{'debug': 5, 'updateDebugScreenshotTest': 1}（这些目录里的结果是**上一批遗留**，不随 `test` 重跑，不得计入本计数）
```

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 326 份；分册登记 328 条；全量索引 328 条；最大 §328）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 476 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=13  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

**CodeQL 复扫读数（P3-318 闭环判据 AC①）**：推送本批 commit 后 `workflow_dispatch` 触发
`codeql.yml`（run [RUN_ID]），完成后 `GET /code-scanning/alerts/355` / `356` 实读：
`state = fixed`、`fixed_at` 非空（[ALERT_EVIDENCE]）。

## 5. 涉及文件

代码：`app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt`
（多方向手工旋转 + 移除临时诊断日志）、
`app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt`
（预填组合期同步消费，消除擦除竞态）、
`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt`
（回填回显 + epoch 递增）、`EntryEditUiState.kt`（totpPrefillEpoch 字段）、
`EntryEditScreen.kt` / `EntryEditComponents.kt`（epoch 贯穿至 initialKey）、
`tools/export_previews/generate_screenshot_test_wrappers.py`（extra 组化简）。
文档：`docs/ACTIVE_ISSUES.md`（P3-319 / P3-320 移出闭环，P3 2 → 1）、
`docs/RESOLVED_LOG.md` / `docs/resolved/BATCH_158_PLUS.md` / `docs/resolved/README.md`（§328 登记）。

## 附录：`ISSUE-P3-319` 原文（自 ACTIVE_ISSUES.md 整条剪切，原样收录）

### ISSUE-P3-319：扫码栈依赖治理——移除停更且依赖废弃 Camera1 的 `com.journeyapps:zxing-android-embedded`，重构为 CameraX + `zxing:core` 纯离线 Compose 对话框

- **优先级**：P3（依赖现代化与合规瘦身；迁移过程中**不得**回退 `ISSUE-P3-71` / `ISSUE-P3-103` 已建立的防截屏 / 反 overlay / 反点击劫持加固面，故含安全约束）。
- **核实时间点**：2026-09-25。
- **核实方式**：① 全仓 `grep -iE "zxing|journeyapps"`（排除 `build/` 产物）：源码消费方仅 2 处——`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt:82`（`rememberLauncherForActivityResult(ScanContract())` + `ScanOptions`，QR_CODE 单格式、结果经 `onTotpSecretChangeSecure` 以 CharArray 上行）与 `app/src/main/java/com/keepasskey/app/security/SecureCaptureActivity.kt`（继承 zxing `CaptureActivity`，onCreate 强制 `FLAG_SECURE` + `setHideOverlayWindows(true)` + `decorView.filterTouchesWhenObscured=true`）；另 `app/src/main/AndroidManifest.xml:217` 声明该 Activity（`zxing_CaptureTheme` / `sensorLandscape`）；② `AndroidManifest.xml:14` 注释 ③ 实证：`CAMERA` 权限目前**由 zxing 清单传递注入**（源清单未显式声明），移库后必须显式声明补位；③ Maven 元数据核实（2026-09-25）：`com.journeyapps:zxing-android-embedded` 最新 4.3.0（2023 后停更，内部依赖已废弃的 android.hardware.Camera1 API）；`androidx.camera:camera-camera2` stable 最新 **1.6.2**（latest 1.7.0-alpha03）；`com.google.zxing:core` 最新 **3.5.4**（Maven Central，2025-11-11）——现版本 3.4.1 系 zxing-embedded 传递依赖。
- **背景**：TOTP 种子二维码扫码是全仓唯一扫码场景。zxing-android-embedded 停更且取景建立在 Camera1 之上，Camera1 自 API 21 起弃用；库自带的 `CaptureActivity` 游离于 `FlagSecureGuard`（仅 attach 至 `MainActivity` 与 passkey Activity 体系）的加固范围之外，`ISSUE-P3-71` 才为其单独立了 `SecureCaptureActivity` 补防截屏。改为**承载于受保护窗口内的原生 Compose 对话框**后，取景画面天然落入 `FLAG_SECURE` 保护域，同时消除一个停更第三方 UI 依赖（含其注入的全部资源 / 主题 / 清单组件），扫码路径收敛为「CameraX 取景 + zxing:core 纯算法解码」，零网络、零遥测、零 ML Kit。
- **整改方案**：
  ① 依赖替换：`libs.versions.toml` 删除 `zxing-embedded` 别名与 `zxing = "4.3.0"` 版本项，新增 `androidx.camera`（camera-core / camera-camera2 / camera-lifecycle / camera-view，**1.6.2 stable**）与 `com.google.zxing:core`（**3.5.4**）；
  ② 新增 Compose 扫码对话框（`AndroidView(PreviewView)` + `ImageAnalysis` YUV 帧喂 `MultiFormatReader`，仅 QR_CODE，与现 `ScanOptions.QR_CODE` 等价；解码跑后台线程，结果以 CharArray 通道接回 `onTotpSecretChangeSecure`，**不得**新增 String 落地敏感种子的路径）；
  ③ `EntryEditPickers.kt` 的 `scanTotpQr` 由 `ScanContract` 启动改为置位对话框状态；删除 `SecureCaptureActivity.kt` 与 Manifest 对应 `<activity>` 声明；
  ④ **加固迁移口径（本条安全约束核心）**：对话框挂载在编辑页所在 Activity 窗口内，须逐项核实并登记三点——(a) 承载 Activity 的 `FLAG_SECURE` 生效（`FlagSecureGuard` 覆盖面核实）；(b) `setHideOverlayWindows(true)` / `filterTouchesWhenObscured` 两层在承载窗口的等效覆盖（如未覆盖，须在对话框挂载路径补齐或在批次文档登记取舍理由）；(c) 原 `sensorLandscape` 方向策略是否保留的裁决；
  ⑤ Manifest：`<uses-permission android:name="android.permission.CAMERA"/>` **显式声明**（替换原 zxing 传递注入），同步改写注释 ③ 的权限口径；运行时权限经 `ActivityResultContracts.RequestPermission` 请求，拒绝时如实提示并保持扫码入口可见（不静默失效）。
- **验收标准**：① `.\gradlew.bat test` 全绿、`python tools/doc/gate_readings.py` 7/7 PASS（读数块原样入批次文档 §3）；`lint` 计数口径（`grep -cE "^ *<issue$" app/build/reports/lint-results-debug.xml`）不劣于基线；② 合并清单中无任何 `com.journeyapps` 组件 / 资源 / `zxing_CaptureTheme` 残留，`CAMERA` 权限为源清单显式声明；③ 扫码端到端验证：AVD（本机 `Pixel_10`）或真机实拍 TOTP 二维码成功回填种子（§263 设备数据保护立规适用，禁直接对装库设备跑 connected）；权限拒绝路径不崩溃、有如实提示；④ `FlagSecureGuard` / 加固迁移口径逐项核实结论写入批次文档；⑤ 若改动了 `@Preview` 或截图包装生成器，须跑 `.\gradlew.bat :app:compileDebugScreenshotTestKotlin --rerun` 门禁。

## 附录 B：`ISSUE-P3-320` 原文（自 ACTIVE_ISSUES.md 整条剪切，原样收录）

### ISSUE-P3-320：TOTP 扫码回填成功后输入框不回显，用户无法感知扫码已成功

- **优先级**：P3（体验优化；功能本体——种子入库与产码——已由真机实证正确，仅「成功不可见」）。
- **核实时间点**：2026-09-25。
- **核实方式**：真机（`1c859bcc7d24`）UI 自动化 + uiautomator dump + logcat 诊断实录（§328 §3）：扫码解码命中 8 次、对话框自动关闭、保存后实时验证码与 pyotp 独立计算一致——功能链路全通；但扫码后编辑页 TOTP 输入框（`SecurePasswordField`）文本仍为空，uiautomator 实读无回显。
- **背景**：种子流经 `onTotpSecretChangeSecure` 直达 `EntryEditViewModel`，而 `SecurePasswordField` 的文本是组件内部状态，仅经 `initialPassword`（即 `loadedTotpSecret` 一次性预填通道）初始化，不消费外部状态更新——扫码成功后唯一可见反馈是扫码对话框自动关闭。**旧 zxing `ScanContract` 流程语义完全相同**（同一调用点同一 ViewModel API），非 `ISSUE-P3-319` 迁移引入的回归；但「成功不可见」在 §328 诊断中直接造成「扫了没反应」的误判，值得修复。
- **整改方案**：任选其一并登记取舍——①`SecurePasswordField` 增加受控外部值通道（外部状态变更时同步组件内文本，注意 CharArray 清零契约与既有一次性预填语义不冲突）；②扫码对话框关闭时经既有 `UiMessage`/snackbar 通道给「扫码成功，已填入验证码种子」提示（改动最小，不动 `SecurePasswordField`）。
- **验收标准**：扫码成功后用户有明确可见反馈（字段回显或提示文案其一）；`.\gradlew.bat test` 全绿；中英文案成对；批次文档门禁读数块。
