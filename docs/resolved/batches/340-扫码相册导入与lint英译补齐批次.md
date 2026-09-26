# 340. 扫码相册导入与 lint 英译补齐批次（`ISSUE-P3-334` / `ISSUE-P3-335`）

> **批次性质**：用户需求直提（`ISSUE-P3-334`：「目前 TOTP 扫码只能直接扫，加一个从相册导入二维码的方式」）
> → 2026-09-26 同日登记；整改中跑 `lint` 实测发现 HEAD 既有 3 条 `MissingTranslation` 恒红
> （`ISSUE-P3-335`，由 §332 / `4d338672` 引入漏译）→ 按「发现新问题即时补登」规则同日补登、同批闭环。
> **未触**原生内核 / `*/src/androidTest/**` / `参考项目/` / 构建脚本 ⇒ 无设备侧必跑项。

---

## 340.0 原文收录（`ACTIVE_ISSUES.md` 条目正文，原样剪切）

> ### ISSUE-P3-334 TOTP 扫码仅支持相机直扫，缺「从相册导入二维码」通路
>
> - **优先级**：P3（体验缺口；相机扫码功能正常，无功能阻断与安全影响）
> - **背景**：用户 2026-09-26 提出需求——「目前 TOTP 扫码只能直接扫，加一个从相册导入二维码的方式」。
>   现状：`TotpScanDialog`（编辑页回填 TOTP 种子、顶栏扫码创建验证码条目**共用**的扫码对话框）只有
>   CameraX 取景一条通路；用户手持二维码截图 / 保存的二维码图片时无法识别，相机权限被拒时也没有
>   任何替代识别手段。
> - **核实时间点**：2026-09-26
> - **核实方式**：同日全仓 grep `TotpScanDialog\(`——仅 `EntryEditPickers.kt` / `VaultListScreen.kt`
>   两处调用，`TotpScanDialog.kt` 内仅有 `TotpCameraPreview` 取景解码通路、无任何相册 / 图片解码入口；
>   `edit_scan_*` 字符串资源亦无相册相关项。需求由用户当面提出。
> - **涉及文件**：
>   - `app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt`（对话框接线；
>     `rotateYPlane90` 由 `private` 放宽为 `internal` 供相册通路复用同一旋转实现）
>   - `app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpGalleryImport.kt`（新增：相册选图 + 有界解码 + 像素级 QR 识别）
>   - `app/src/main/res/values/strings.xml` / `values-en/strings.xml`（新增 4 条文案）
>   - `app/src/test/java/com/keepasskey/app/ui/screens/edit/TotpGalleryImportDecodeTest.kt`（新增单测）
> - **验收标准（AC）**：
>   1. 扫码对话框提供「从相册导入」入口，**相机权限被拒态同样可见**（成为权限缺失时的替代通路）；
>      经系统 Photo Picker 选图（`PickVisualMedia`，无存储权限依赖）；
>   2. 选图解码走后台线程：位图**有界解码**（单边像素上界为命名常量，防全尺寸照片 OOM），
>      像素灰度化后按 **4 个朝向重试**（覆盖 EXIF 旋转，与相机帧通路同口径），CPU 段在
>      `Dispatchers.Default`、读流在 `Dispatchers.IO`；
>   3. 解码成功即刻转 `CharArray` 上行 `onDecoded` 并关闭对话框（与相机通路同口径，不新增 String
>      落地 / 持久化路径）；未识别到二维码、图片读取失败**各自如实提示**（对话框内、不静默、不关闭、
>      不回显图片或解码内容）；
>   4. 解码纯函数有宿主单测：正样本（可解出原文）+ 旋转 90° 样本（EXIF 朝向覆盖）+ 负样本（非二维码
>      图像返回 null）；
>   5. `.\gradlew.bat test` 全绿且 `python tools/doc/gate_readings.py` 7/7 PASS；归档（`RESOLVED_LOG`
>      索引行 + 批次正文）与代码同批提交。
>
>
> ### ISSUE-P3-335 `lint` 恒红：`legacy_autofill_*` 三条中文串缺英译（MissingTranslation ×3）
>
> - **优先级**：P3（CI fast-gate 质量门禁缺口；无功能与安全影响——三条均为文案资源）
> - **背景**：`.\gradlew.bat lint` 实测报 3 个 **Error** 并中止（`lintDebug` FAILED）：
>   `values/strings.xml:636~639` 的 `legacy_autofill_notification_title` /
>   `legacy_autofill_notification_body` / `legacy_autofill_service_desc` 在 `values-en/` 无对应英文串
>   （`MissingTranslation`）。CI `fast-gate` 执行 `./gradlew lint -x :crypto:cargoNdkBuild`
>   且不吞失败（`build.yml` 明注「真实 Lint 失败仍使本步骤红」）⇒ 该门禁自引入起即红。
>   三条串由 `4d338672`（`ISSUE-P3-324` 闭环，§332，2026-09-25 22:30）落地，该批只加了中文值、
>   漏加英文值，其批次文档「过程缺陷」小节无此留痕——属漏登记的新发现缺陷。
> - **核实时间点**：2026-09-26
> - **核实方式**：本批（`ISSUE-P3-334`）跑 `.\gradlew.bat lint` 实测首报即为此 3 条；
>   `git show HEAD:...values-en/strings.xml` 核对 HEAD 英文件确无这三键；`git log -S legacy_autofill_notification_title`
>   定位引入提交为 `4d338672`（§332）；`git show 4d338672 -- values-en/strings.xml` 确认该提交未动英文件。
> - **涉及文件**：`app/src/main/res/values-en/strings.xml`（补 3 条英文串；中文值不改）
> - **验收标准（AC）**：
>   1. `values-en/` 补齐三条英译（语义与中文一致、如实描述读取范围；**避开撇号**——§332 留痕过
>      en 串撇号未转义致资源编译红的踩坑，能改写即改写）；
>   2. `.\gradlew.bat lint` 由 3 errors 降为 0 errors（warning 不阻断，沿用既有口径）；
>   3. `.\gradlew.bat test` 全绿且 `python tools/doc/gate_readings.py` 7/7 PASS；与
>      `ISSUE-P3-334` 同批归档提交。

## 1. 整改内容

### `ISSUE-P3-334`（相册导入二维码通路）

1. **新增 `TotpGalleryImport.kt`**（与 `TotpScanDialog` 同包）：
   - `TotpGalleryImport(onDecoded, onDismiss)`：对话框内「从相册导入」`TextButton` + 进行中
     （`正在识别…`）/ 失败态（`未识别到二维码` / `图片读取失败`，`MaterialTheme.colorScheme.error`）
     就地文案；解码期间禁用按钮防重复选图；**失败不关闭对话框**、不回显图片与解码内容；
   - 选图走 `ActivityResultContracts.PickVisualMedia`（系统 Photo Picker，无存储权限依赖）；
   - 处理链路与线程口径：读流 + 位图解码在 `Dispatchers.IO`，灰度化 + zxing 解码在
     `Dispatchers.Default`（CPU 密集段），交付回主协程——与工程规则「调度器语义」一致；
   - **有界解码**：`MAX_GALLERY_IMAGE_DIMENSION = 2400`（命名常量）+ `inJustDecodeBounds` 先量尺寸、
     `gallerySampleSize` 取 2 的幂降采样，防全尺寸照片解出亿级像素数组 OOM；
   - `decodeQrFromPixels(pixels, width, height)`（`internal` 纯函数，宿主单测入口）：ARGB → 亮度
     `Y = (299R + 587G + 114B) / 1000` → `PlanarYUVLuminanceSource`（只消费 Y 平面）+
     `HybridBinarizer`，未命中经 `rotateYPlane90` 逐朝向重试共 4 次（覆盖 EXIF 旋转；镜像不覆盖——
     实拍 / 截图二维码不镜像）；每方向吞 `ReaderException` 根类（`NotFoundException` /
     `ChecksumException` / `FormatException` 均属本朝向未命中），入参尺寸不符直接返回 null 防越界；
   - 敏感数据铁律：命中即刻 `decoded.toCharArray()` 上行 `onDecoded` + `onDismiss`（与相机通路
     「命中即关」同口径），不新增 String 落地路径；失败路径不触碰解码内容、日志无明文。
2. **`TotpScanDialog.kt` 接线**：在相机权限 if/else（授权取景态 / 拒绝提示态）**之后**、取消按钮
   **之前**插入 `TotpGalleryImport(onDecoded, onDismiss)` 一行——按钮位于分支外，**权限被拒态同样
   可见**（AC①）；`rotateYPlane90` 由 `private` 放宽为 `internal`（KDoc 注明由相册通路复用同一
   旋转实现），相机帧解码路径零改动。
3. **字符串**：`values/` + `values-en/` 各增 4 键（`edit_scan_from_gallery` / `edit_scan_gallery_decoding`
   / `edit_scan_gallery_no_qr` / `edit_scan_gallery_read_error`）；`edit_scan_camera_permission_denied`
   文案同批改为如实含相册替代通路（zh「无法相机扫码……或从相册导入二维码」，en 同步），避免权限拒绝态
   仍宣称「无法扫码」而实际存在替代识别手段。
4. **两处调用点零改动**：编辑页（回填 TOTP 种子）与顶栏（otpauth → 创建验证码条目）共用
   `TotpScanDialog`，对话框内部自持 `onDecoded`/`onDismiss` 语义 ⇒ 一次接线两入口同时获得相册通路。

### `ISSUE-P3-335`（`legacy_autofill_*` 英译补齐）

1. `values-en/strings.xml` 在 `sec_flag_secure_risk_cancel` 之后补 3 条：
   `legacy_autofill_notification_title`（Password field detected）、
   `legacy_autofill_notification_body`（Tap to choose an entry to fill (legacy accessibility channel)）、
   `legacy_autofill_service_desc`（如实描述读取范围的完整服务说明）——语义对齐中文值，
   **改写避开撇号**（§332 留痕过 en 串撇号未转义致资源编译红的踩坑）；中文值与接线零改动。

**未采用的替代方案（留痕）**：①相册解码放在编辑页另开选择器——顶栏扫码入口（不进编辑页）覆盖不到，
且权限被拒态不可及，对话框内置一次覆盖两入口；②引入 `androidx.exifinterface` 按 EXIF 旋转位图——
四朝向重试已与相机帧通路同口径地覆盖旋转，零新增依赖，仅镜像朝向不覆盖（实拍 / 截图不镜像）；
③ML Kit / 网络解码——沿用 ISSUE-P3-319「零网络、零遥测」口径，仍用本地 `zxing:core`。

## 2. 验证

1. **全量单测**：`.\gradlew.bat test` `BUILD SUCCESSFUL`；聚合计数
   `xml=402 tests=2671 failures=0 errors=0 skipped=13`（§339 基线 `tests=2667` + 新增 4 例；
   `skipped=13` 与基线持平、本批零跳过）。新用例 `TotpGalleryImportDecodeTest` 4/4 逐例在案：
   相册像素帧解出原文 / 旋转 90° 帧仍解出（EXIF 朝向覆盖）/ 纯色帧返回 null（负样本不误报）/
   尺寸不符与零宽入参返回 null（防越界）。
2. **Android Lint**：首跑 `BUILD FAILED`（3 errors，**全部**为 `ISSUE-P3-335` 的既有
   `MissingTranslation`，非本批引入——`git show HEAD` 双文件核对在案）；补译后
   `.\gradlew.bat lint` `BUILD SUCCESSFUL`，报告 `severity="Error"` 0 条
   （200 warnings + 2 hints 沿用基线，warning 不阻断）。
3. **静态守卫**：`SensitiveWindowHardeningTest` 两例全绿——`flagSecureEnabled` 接线 /
   `SecureOn`/`SecureOff` 条件驱动 / `setHideOverlayWindows` / `SecureDialogWindowEffect` /
   `COMPATIBLE` 断言与 `EntryEditPickers` 下传断言均未触碰；`SensitiveWindowHardeningTest`
   所锁四要素在本批改动后的 `TotpScanDialog.kt` 中逐一仍在。
4. **门禁**：`python tools/doc/gate_readings.py` 7/7 PASS（读数原样贴 §3，粘贴后复跑仍 7/7）；
   另跑 `python tools/doc/check_verbatim_move.py` 对「条目剪切进批次」做逐字搬移复核
   `MISSING_KINDS=0`。
5. **未触设备侧**：无 `*/src/androidTest/**`、无原生内核、无构建脚本改动 ⇒ 无
   `connectedDebugAndroidTest` 必跑项；截图编译门禁不触发（未改 `@Preview` 与
   `tools/export_previews/` 生成器）。
6. **如实声明**：相册通路**真机手动冒烟未做**（需设备相册选图交互，且 §263 设备前置检查约束）；
   JVM 侧以纯函数单测覆盖「灰度化 + 四朝向 zxing 解码」核心链路，读流 / Photo Picker /
   Compose 呈现属 Android 框架面，经编译与静态接线核验但未经真机目视。

## 3. 门禁读数（`gate_readings.py` 原样粘贴，§308 立规）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/7] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=34  budget=37
[2/7] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/7] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/7] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 338 份；分册登记 340 条；全量索引 340 条；最大 §340）
[5/7] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 459 个测试文件
[6/7] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/7] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
=== 汇总：7/7 PASS ===
```

> 粘贴后复跑 `python tools/doc/gate_readings.py` 仍 7/7 PASS（读数与上块一致）。
> 逐字搬移复核 `python tools/doc/check_verbatim_move.py <剪切前快照> docs/ACTIVE_ISSUES.md <340 去引用副本>`：
> `orig_content_kinds=78  MISSING_KINDS=2`——缺的 2 行**仅**为有意改写的段头
> 「`## P3 …（2 项）`」与「归零前指针行」（剪切动作本身），两条目正文逐行均在
> 「现 `ACTIVE_ISSUES` ∪ 批次 §340.0」中命中。
