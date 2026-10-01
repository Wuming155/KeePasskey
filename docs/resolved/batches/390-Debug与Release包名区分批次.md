# §390 Debug 与 Release 包名区分批次（`ISSUE-P3-423` 整条闭环）

> **触发**：2026-10-01 用户提议「Debug 版本包名加一个 `.debug` 后缀，方便与稳定版并存安装、互不影响」，方案讨论后用户确认按仓库流程实施。
> **结案条目**：`ISSUE-P3-423`（整条闭环，同日登记同日结案）。

---

## 0. 原文登记

> ### ISSUE-P3-423：Debug 与 Release 包名区分（`applicationIdSuffix ".debug"`）以支持并存安装
>
> - **背景（2026-10-01 用户指示）**：debug 包走 debug keystore、release 包走 release.jks，签名不一致；而两者
>   `applicationId` 同为 `com.keepasskey`，装 debug 会把 release **连同数据一起顶替卸载**
>   （`allowBackup="false"` 无备份可恢复，与 §263 实测事故同形态），日常调试严重影响正常使用。
> - **核实（2026-10-01）**：grep `app/src/main/AndroidManifest.xml` 与 `app/src/main` 全部 `.kt`：
>   provider authority 用 `${applicationId}` 占位符，autofill / passkey 自校验、`InstalledAppsCatalog`
>   均走 `Context.packageName` 动态取值，**无任何硬编码 `com.keepasskey`**（仅字符串文案里出现，与包名无关）；
>   测试源码同样无硬编码包名。⇒ 加后缀不会破这些面。
> - **整改方向**：`app/build.gradle.kts` 的 `buildTypes.debug` 加 `applicationIdSuffix = ".debug"`；
>   debug 桌面名加「Debug」后缀（两个图标可分辨），release 包名与桌面名保持原样不变。
> - **如实取舍**：debug 与 release 数据完全隔离（各自独立存储）；Credential Manager 侧按包名+签名注册，
>   RP 侧视其为两个不同应用（正是测试期望的效果）；系统 autofill / 凭据默认项二选一，需手动切换。
> - **验收标准**：
>   - AC① `:app:assembleDebug` 产出的 APK `applicationId` 为 `com.keepasskey.debug`，label 带「Debug」后缀；
>   - AC② release 配置下 `applicationId` 与桌面名不变（`com.keepasskey` / 原 label）；
>   - AC③ 全量 `test` 全绿（含回归）+ `python tools/doc/gate_readings.py` 全 PASS，读数块原样入批次文档。

---

## 1. 整改

| 层 | 变更 |
|---|---|
| `app/build.gradle.kts` | `buildTypes.debug` 新增 `applicationIdSuffix = ".debug"`（注释记录动机：签名不一致＋包名相同＝互相顶替卸载，§263 同形态；以及「代码与 manifest 无硬编码包名」的核实结论与数据隔离取舍） |
| `app/src/debug/res/values/strings.xml`（新增） | debug 变体应用名覆盖：`app_name` → `KeePasskey Debug` |
| `app/src/debug/res/values-en/strings.xml`（新增） | 英文场景同批覆盖（缺此文件时英文 locale 回落 main 源集 `values-en` 的无后缀名） |

**方案裁决（桌面名通道）**：曾先实现 manifest 占位符注入（`android:label="${appName}"` + `manifestPlaceholders`），
随后核实发现 **`credential_provider_service.xml:11` 的 `settingsSubtitle` 也引用 `@string/app_name`**
（系统「设置 → 凭据提供方」页副标题），而 manifest 占位符**只作用于 manifest、覆盖不到 res XML**——
若沿用占位符方案，系统凭据设置页里 debug 与 release 同名，无法分辨当前激活的是哪一个。
故切换为 **debug 源集资源覆盖**（build-type source set 优先级高于 main），两处引用一次生效，
manifest 与 gradle 均保持零侵入（release 侧无任何改动）。

**包名通道核实（开工前）**：`grep` 证实生产代码无硬编码 `com.keepasskey`
（provider authority 用 `${applicationId}`；autofill / passkey 自校验、`InstalledAppsCatalog` 走 `Context.packageName`），
测试源码同样无硬编码。`applicationIdSuffix` 只改 applicationId、不改 namespace / R 类 / 代码包结构。

---

## 2. 验证

- **AC①（debug 包名与桌面名）**：`:app:assembleDebug` 后
  `aapt2 dump badging app-debug.apk` → `package: name='com.keepasskey.debug'`、
  `application-label:'KeePasskey Debug'`。
- **AC②（release 不回退）**：`:app:assembleRelease` 后
  `aapt2 dump badging app-release.apk` → `package: name='com.keepasskey'`、
  `application-label:'KeePasskey'`（与改动前逐字一致）。
- **AC③（全量回归）**：`.\gradlew.bat test --rerun-tasks --max-workers=1` **BUILD SUCCESSFUL**（114 tasks 全执行）；
  `python tools/doc/count_test_results.py` → `xml=498 tests=3222 failures=0 errors=0 skipped=13`（基线一致）。

### 2.1 门禁读数（原样粘贴，归档后采集）

```
=== 门禁读数（ISSUE-P3-305 AC④：批次文档 §3 须原样粘贴本块）===
[1/8] tools/doc/count_line_tiers.py                EXIT 0  | tier1(>500)=0  tier2(400~500)=36  budget=37
[2/8] tools/doc/long_functions.py                  EXIT 0  | functions_ge_100=0
[3/8] tools/doc/check_md_links.py                  EXIT 0  | BROKEN_MD_LINKS=0
[4/8] tools/doc/check_resolved_index_sync.py       EXIT 0  | RESOLVED_INDEX_SYNC=OK（批次正文 388 份；分册登记 390 条；全量索引 390 条；最大 §390）
[5/8] tools/audit/check_tautological_assertions.py EXIT 0  | 汇总：命中 0 处 / 扫描 556 个测试文件
[6/8] tools/audit/check_recheck_consistency.py     EXIT 0  | PASS: 无残留禁用短语（已扫描 1331 行，11 条禁用短语）
[7/8] tools/doc/check_bounded_type_names.py        EXIT 0  | allowed=12  unregistered_manager_util_helper_common=0
[8/8] tools/doc/check_box_slot_children.py         EXIT 0  | BoxScope 内容槽组件：['BentoCard']  检查过的调用点：59  box_slot_stacked_sites=0
=== 汇总：8/8 PASS ===
```

---

## 3. 如实声明

1. **过程缺陷（方案返工一次）**：桌面名先按 manifest 占位符实现并跑完一轮全量测试，随后发现
   `settingsSubtitle` 同样引用 `@string/app_name` 且占位符覆盖不到 res XML——占位符方案下系统凭据设置页
   两版同名。切换为 debug 源集资源覆盖后，**重新实跑** `:app:assembleDebug` / `:app:assembleRelease`
   与全量 `test --rerun-tasks`，本档 AC 读数均出自最终实现态。
2. debug / release 数据完全隔离是**预期行为**而非缺陷：debug 里生成的通行密钥、保存的库 release 不可见；
   Credential Manager 按「包名+签名」注册，RP 侧视两者为不同应用。系统 autofill / 凭据默认项二选一，需手动切换。
3. 并存安装与「系统凭据设置页两版各自可辨」的最终视觉效果未在真机逐屏走查（本批无设备侧改动，
   验证以 `aapt2 dump badging` 对产物为准）；首次真机安装时自然核验。
