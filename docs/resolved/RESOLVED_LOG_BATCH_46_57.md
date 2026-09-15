# KeePasskey 已整改问题与历史归档 · 分册 03（§46 ~ §57）

> **分册定位**：本册是 [RESOLVED_LOG.md](../RESOLVED_LOG.md) 的历史分册，收录 **§46 ~ §57** 全量正文（2026-09-13 ~ 2026-09-15 批次）。
> **主文件职责**：RESOLVED_LOG.md 只保留全量批次索引与最近 10 个批次（当前 §58 ~ §67）正文；新批次先写入主文件，满额后按序下沉分册。
> **引用定位**：仓内其它文档的「RESOLVED_LOG.md §NN」引用，凡 NN 落在 §46 ~ §57 区间，正文即本册对应小节（可用本册索引跳转）。
> **体例（2026-09-15 保守精简立规）**：各节保留原结论、裁决、残余登记与「过程缺陷」原文；仅把重复的验收记录压缩为「命令 + 状态摘要」。**未删除任何结论性内容**。

---

## 本册章节索引

| 章节 | 批次 | 条目范围 |
|---|---|---|
| [§46](#s46) | P1 双项整改批次：软件级 Keystore 快速解锁降级确认 + 重打包威胁告知留痕 | ISSUE-P1-22 / ISSUE-P1-23 |
| [§47](#s47) | 设备侧真机基线批次：两条「模拟器环境假设」用例整改 + arm64 真机全量实测 | 设备侧用例缺陷（无编号） |
| [§48](#s48) | 存量安全整改批次：KDF 预算 + TOTP 保护 + 剪贴板闭环 + 明文持有者锁观察者 + 换库前置释放 + 附件引用预算 + 完整性门控对称化 + Passkey 归属与验证绑定 | ISSUE-P2-48 / P2-51 / P2-53 / P2-61 / P2-63 / P2-65 / P2-72 / P2-76 / P2-77 / P3-84 / P3-109 / P3-117（+ P2-49 AC①③ 进展） |
| [§49](#s49) | 存量安全整改批次（续）：密钥文件纯字节解析 + DAL 有界流式读取 + 选择器会话锁定对齐 + CM 保存 URL 分流 + KDF 参数与秘密治理 + 依赖扫描触发面 | ISSUE-P2-50 / P2-52 / P2-54 / P2-55 / P2-56 / P2-57 / P2-58 / P2-59 / P2-60 / P2-62 / P2-78 |
| [§50](#s50) | 日志与对象字符串化卫生批次：四类 `toString()` 明文泄漏面 + 日志抽样口径按「日志调用」特征跨行抽取 | ISSUE-P2-68 / ISSUE-P2-69 |
| [§51](#s51) | 自动填充默认值与内存保护口径批次：TOTP 复制 / IME 内联建议默认关闭 + 内存密封与写出标志口径分离 + unlink-only 边界登记 | ISSUE-P2-43 / P2-64 / P2-66 / P2-71 |
| [§52](#s52) | 同步解析落盘与内存池擦除边界批次：远端大附件解析期落盘 + 树外可达性收口 + 单次解析产物显式擦除 | ISSUE-P2-67 / P3-119 |
| [§53](#s53) | 自动填充请求方归属与授权宽限收窄批次：选择器页强制展示请求方身份 + 不可归属域不再享受免重复确认 | ISSUE-P2-70 / P2-81 |
| [§54](#s54) | 包可见性与序列化缓冲擦除批次：最小 `<queries>` 恢复调用方指纹可读 + 整库序列化缓冲具名擦除 | ISSUE-P2-74 / P3-118 |
| [§55](#s55) | 多签名者匹配批次：签名轮换期以「调用方全部签名摘要」参与判定（任一命中即通过） | ISSUE-P3-93 |
| [§56](#s56) | 清单权限口径与确认回传门控批次：弃用权限 `tools:node="remove"` + 会话锁定即丢弃未决响应 | ISSUE-P3-94 / P3-95 |
| [§57](#s57) | 清零与规则一致性批次：CBC 加密流明文副本清零 + `AppLog` 剥离规则签名修正 + 换密密钥快照副本清零 | ISSUE-P3-96 / P3-98 / P3-99 |

---

<a id="s46"></a>
## §46 P1 双项整改批次：软件级 Keystore 快速解锁降级确认 + 重打包威胁告知留痕（2026-09-13）

> **本批次缘起**：P1 节剩余两项同批整改闭环——`ISSUE-P1-22`（第四轮终评 LOW / DESIGN WEAKNESS，
> 条目留 P1 节至闭环）与 `ISSUE-P1-23`（第四轮终评 INFO–LOW / P2 产品告知项）。
> **附带发现并修复 1 项 P1 级隐藏功能缺陷**（AndroidKeyStore SecretKey 探针 API 误用，见 46.3）——
> 该缺陷由 P1-22 AC③ 强制的设备侧实测暴露，属 AGENTS §6 所述「JVM 过、Android 运行时挂」的又一实例。

### 46.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **P1-22** | P1（LOW / DESIGN WEAKNESS） | 封印密钥落位等级（SOFTWARE / UNKNOWN）不影响任何行为——软件级 Keystore 上封印照常建立且无任何提示，封印载荷可被离线解出主密码 | ① **策略单点**：`UnlockAuthPolicy.requiresDowngradeConsent(level)`——SOFTWARE 与 UNKNOWN（无法证明硬件落位）一律须「显式降级确认」（AC① 第四轮修正：不取「无条件禁用」，保住模拟器 / CI 生物识别路径）；② **探测接线**：`BiometricAuthManager.getKeySecurityLevelForDatabase`（封印路径唯一消费点），封印协调器新增 `SealedKeyProvision` 供给链——**先经 `prepareEncryptCipher` 建钥、后探测实际落位**（密钥不存在时探测恒 UNKNOWN，次序不可颠倒）；③ **确认闸门**：`BiometricEnrollmentCoordinator` 在落位须降级且 `quickUnlockDowngradeAcknowledged == false` 时挂起解锁流程、经 UI 状态 `quickUnlockDowngradeConsentPending` 驱动解锁页 `QuickUnlockDowngradeConsentDialog`（60s 超时未决 → fail-closed 跳过封印；确认 → `SettingsRepository.setQuickUnlockDowngradeAcknowledged(true)` 持久化留痕（AC②）；拒绝 → 关闭 `biometricEnabled` 不封印不留痕）；④ **常驻声明**：确认记录驱动解锁页快速解锁卡片与安全设置页生物识别开关下方常驻渲染「本机快速解锁降级为软件密钥，不提供硬件级保护」（中英双语资源），并带自愈逻辑（确认记录残留但实际落位为 TEE/StrongBox 时自动清除）；⑤ strings.xml / values-en 新增 5 条 | `QuickUnlockSealDowngradeTest` 新增 8 例（策略纯函数 1 + JVM 全流程 7：SOFTWARE 未确认拒绝 / UNKNOWN 同策略 / 确认后持久化 / 拒绝关开关 / 已确认不重复弹窗 / TEE 直放 / 超时 fail-closed，均注入假探测结果）；`QuickUnlockSealDowngradeDeviceTest` 设备侧 3 例（见 46.4） |
| **P1-23** | P1（INFO–LOW / P2 产品告知项） | 重打包 APK 无检测且 `installer==null` 判为无风险——应用内自检对该威胁无效，需应用外信任根告知 | ① **AC①**：README 新增「官方签名指纹」节——公布 release 签名证书 SHA-256（`F3:A6:F0:92:…:84:2E`，本批经 `keytool -list` 对 `release.jks` 实算）+ `apksigner verify` / `keytool -printcert` 可复跑核对命令 + 安全须知（应用内自检不能证明 APK 未被篡改）；② **AC②**：`RuntimeIntegrityDetector.detectUntrustedInstallSource` KDoc 显式决策留痕（`installer==null` 不升级风险系显式产品决策及其三条理由，并明确「不引入无效的应用内签名自校验」）；③ **AC③**：尚未上架任何商店（README「已知局限」已声明），登记为上架前置项（Play Integrity 或同等平台完整性证明），暂不适用 | 无代码行为变更（文档 + KDoc 留痕）；指纹核对命令本身即 AC① 的可复跑校验路径 |

### 46.2 AC 逐条核对

- **P1-22**：AC①（修正版）✓——SOFTWARE / UNKNOWN 一律「显式降级确认 + 常驻声明」（`UnlockAuthPolicy.requiresDowngradeConsent` 单点语义，模拟器 / CI 生物识别路径不被打断）；AC② ✓——封印建立前弹窗风险提示、确认经 `setQuickUnlockDowngradeAcknowledged` 持久化留痕、解锁页 + 安全设置页常驻声明；AC③ ✓——JVM 8 例注入假探测结果断言「SOFTWARE 未经确认 → 封印被拒」等分支 + 设备侧 3 例实测（见 46.4）。
- **P1-23**：AC① ✓（README 公布指纹 + 核对命令）；AC② ✓（`installer==null` 语义在 KDoc 显式决策留痕，未以应用内自检充当整改）；AC③ ✓（未上架 → 如实登记为上架前置项）。

### 46.3 附带发现并修复：AndroidKeyStore SecretKey 探针 API 误用（P1 级功能缺陷）

- **发现过程**：P1-22 设备侧用例首跑即失败——模拟器（Pixel_10，x86_64 / API 36.1）上 `getKeySecurityLevel` 恒返回 `UNKNOWN`。加入 provider 服务诊断后实锤：**AndroidKeyStore provider 仅注册 EC / RSA / XDH / ED25519 的 `KeyFactory`**，`KeyFactory.getInstance("AES", "AndroidKeyStore")` 恒抛 `NoSuchAlgorithmException`。
- **根因**：`KeystoreKeyMaterial` 两处对 **SecretKey** 条目取 `KeyInfo` 误用 `java.security.KeyFactory`；官方 API 为 `javax.crypto.SecretKeyFactory`（`SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)`）。原实现 catch-all 吞掉异常：
  - `probeSecurityLevel` 恒返回 `SECURITY_LEVEL_UNKNOWN`（诊断 API 失效，P1-22 修复前该 API 在任何设备上从未真实工作过）；
  - `getOrCreateDeviceCredentialKey` 的 ISSUE-P1-08 规格探针恒判「不匹配」→ **每次解封前删钥重建**——解密用「新密钥 + 旧 IV」必然失败，**快速解锁功能自 ISSUE-P1-08 以来在真实设备上必然损坏**（宿主 JVM 无法触及 AndroidKeyStore，故 844 例 JVM 单测全绿不放行该缺陷；典型「JVM 过、Android 运行时挂」）。
- **修复**：两处改用 `SecretKeyFactory`（EC 私钥探针的 `KeyFactory` 用法合法，保留）。修复后设备侧探测实返回 `SOFTWARE`（模拟器）。
- **登记说明**：本缺陷随 P1-22 同批发现、同批修复，不单独占编号；其修复由设备侧用例 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` 长期回归锁定。

### 46.4 设备侧实测记录（x86_64 / API 36.1 模拟器 Pixel_10，`emulator-5554`，`OK (3 tests)`）

| 用例 | 结果 | 说明 |
|---|---|---|
| `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` | PASS | 真实 AndroidKeyStore 密钥实测落位 = SOFTWARE（P1-22 问题前提在 Android 运行时成立，非推算）；同时锁定 46.3 的 `SecretKeyFactory` 修复 |
| `未录入强生物识别时封印fail-closed且不请求降级确认` | PASS | 生产默认供给链（真实建钥）在未录入 Class 3 生物识别时抛 `InvalidAlgorithmParameterException` → 供给失败 → 不请求确认、不封印、不改用户设置（fail-closed 闭环） |
| `SOFTWARE落位降级确认闸门在设备侧闭环` | PASS | 注入假 `SOFTWARE` 落位，在真实 Android 运行时验证闸门三条分支：**拒绝** → 关闭 `biometricEnabled` 且不留确认记录、不建立封印；**确认** → 确认记录持久化、开关不被改写；**已有记录** → 不再重复请求确认 |
| **环境边界（如实声明）** | — | 模拟器**无法录入** Class 3 强生物识别（emulator console 无 enroll 子命令），故「SOFTWARE 确认后经真实 BiometricPrompt 授权并落盘封印密文」的**端到端**链路仍须在已录入生物识别的设备实测；该链路的封装逻辑（`BiometricSealedPayloadCodec` / 登记弹窗）已有既有设备侧与 JVM 覆盖 |

### 46.5 已知边界与设计取舍

1. **供给次序**：封印密钥供给（建钥 + 探测）置于宿主 Activity / `canSeal` 闸门**之前**（确认无需宿主 Activity）；未录入生物识别的设备上建钥抛异常被供给链捕获 → fail-closed 跳过封印（设备侧实测覆盖），与既有 `canSeal` 闸门语义一致。
2. **确认记录口径**：`quickUnlockDowngradeAcknowledged` 是「用户曾显式确认在软件密钥上快速解锁」的持久标记（跨库共享）；封印时逐次以真实落位重判（确认只在「落位须降级且未记录」时请求），硬件设备不受残留标记影响；解锁页常驻声明带自愈（存在封印凭据且实测落位为硬件时清除标记）。
3. **拒绝语义**：弹窗「不启用」（含取消 / 关闭）→ 关闭 `biometricEnabled`——软件降级是落位受限设备上唯一可用路径，拒绝即等于不启用快速解锁，避免每次解锁重复弹窗。
4. **确认挂起上限**：60s（与登记弹窗同量级）——确认弹窗挂起期间主密码明文驻留窗口有界，超时 fail-closed 跳过封印。

### 46.6 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL；全量 1595 例 / 0 失败 / 0 错误 / 13 跳过（app 852 / core 65 / crypto 116 / database 359 / sync 203）
$ adb shell am instrument -w -e class com.keepasskey.app.security.QuickUnlockSealDowngradeDeviceTest \
    com.keepasskey.test/androidx.test.runner.AndroidJUnitRunner
# → OK (3 tests)
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL；产物 app\build\outputs\apk\release\app-release.apk（已签名）
```

**基线变动**：1587 → **1595（+8 例）**；跳过数 13 与旧基线一致。新增分布：app +8
（`QuickUnlockSealDowngradeTest`）+ 设备侧 app 15 例（+3：`QuickUnlockSealDowngradeDeviceTest`）。
`AGENTS.md` §1 / §6 基线已同步。

**过程缺陷（如实留痕）**：全量回归首跑 `SyncCacheTest > clear 与 clearAll 均不删除防回滚状态文件`
1 例失败（残留他例的 `.CACHE.tmp`，与本批改动无关）——单独复跑该类即通过（`--rerun-tasks`），
全量重跑全绿，判定为异步 `.tmp` 写入竞态的偶发失败；若再次复现应按缺陷登记。

---

<a id="s47"></a>
## §47 设备侧真机基线批次：两条「模拟器环境假设」用例整改 + arm64 真机全量实测（2026-09-14）

> **本批次缘起**：首次将设备侧全量基线（app / database / sync / crypto 四模块 androidTest）
> 放到 **arm64 真机**上执行。此前该基线的既定环境是 **x86_64 / API 36.1 模拟器**（§34 / §36 / §46），
> 两条用例把**模拟器环境语义当成了设备侧通用语义**，真机首跑即失败（32 例中 30 过 / 2 挂）；
> 二者均属**用例缺陷**（无产品行为变更），已按「环境无关」口径整改并在真机复跑到全绿。

### 47.1 交付清单

| 编号 | 类型 | 缺陷（一句话） | 关键改动 | 回归 |
|---|:--:|---|---|---|
| **设备侧-43** | 用例缺陷（假失败） | `NativeArgon2InstrumentedTest` 断言「被测 APK 内应存在 `lib/<nativeDir.name>/libkeepasskey_argon2.so`」，把平台**短 ABI 目录名**（`ApplicationInfo.nativeLibraryDir` 末段 `arm64`）直接当成 APK zip 条目的**完整 ABI 名**（`arm64-v8a`）——x86_64 模拟器上两者同名故一直为绿，arm64 真机上必然失败 | `apkAbiDirNameFor()` 归一化：`arm64→arm64-v8a`、`arm→armeabi-v7a`、`x86`/`x86_64` 同名（依据 AOSP `VMRuntime.getInstructionSet()`）；加载证据打印同时给出 `nativeLibraryDir末段` 与 `apkAbiDir` | 该用例真机 PASS（7/7）；加载证据见 47.3 |
| **设备侧-44** | 用例缺陷（假失败） | `QuickUnlockSealDowngradeDeviceTest` 中 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE` 硬编码断言落位 = `SOFTWARE`，等于假设「设备侧 = 无 TEE 的软件 Keystore」——真机（有 TEE）实测为 `TRUSTED_ENVIRONMENT`，必然失败 | 改为**环境分支断言**：① 无条件断言「落位 ≠ `UNKNOWN`」（锁 46.3 的 `SecretKeyFactory` 修复，环境无关）；② 软件 Keystore 环境（模拟器，`isSoftwareKeystoreEnvironment()` 按 `PRODUCT`/`HARDWARE`/`FINGERPRINT` 特征判定）→ 断言 `SOFTWARE`；③ 真机 → 断言 `TRUSTED_ENVIRONMENT` / `STRONGBOX`。用例更名为 `AndroidKeyStore真实密钥落位在设备侧返回真实等级` | 该用例真机 PASS（app 15/15）；实测值见 47.3 |

### 47.2 设备侧实测记录（arm64 真机，Redmi 4X / LineageOS `lineage_Mi8937_4_19`，Android 17 / **API 37**，`ro.hardware=qcom`，userdebug）

| 模块 | 用例数 | 结果 | 耗时 | 备注 |
|---|:--:|:--:|:--:|---|
| `app` | 15 | **15 pass / 0 fail / 0 skip** | — | 域解析 7 + 导入 3 + 解锁落盘 2 + Keystore 封印 3（整改后复跑） |
| `database` | 7 | **7 pass / 0 fail / 0 skip** | 18.8s | 含 **真实语料端到端解锁 2/2**（`RealKdbxCorpusUnlockTest`，单例 ~8.9s，arm64 真机） |
| `sync` | 3 | **3 pass / 0 fail / 0 skip** | 0.14s | 落盘权限基线 |
| `crypto` | 7 | **7 pass / 0 fail / 0 skip** | 83.3s | 原生↔BC **逐字节一致**、R1 性能闸门（79.6s，本机为旧款 SoC，**耗时不构成性能结论**）、JNI 通路 |
| **合计** | **32** | **32 pass / 0 fail / 0 skip** | — | 整改后**单批次合并复跑**（四任务同一 Gradle 调用）为 32/32；首跑 30/32（两例假失败见 47.1） |

### 47.3 验收证据

```powershell
.\gradlew.bat :app:installDebug            # → Installed on 1 device（arm64-v8a/armeabi-v7a/x86_64/x86 四 ABI 均真实编译，cargo-ndk 未降级）
.\gradlew.bat :crypto:connectedDebugAndroidTest :database:connectedDebugAndroidTest `
              :sync:connectedDebugAndroidTest :app:connectedDebugAndroidTest
# 首跑 → database 7/7、sync 3/3；crypto 6/7 FAILED、app 14/15 FAILED（两例假失败）
#   ① java.lang.AssertionError: 被测 APK（…com.keepasskey.crypto.test…/base.apk）内应存在 lib/arm64/libkeepasskey_argon2.so
#   ② java.lang.AssertionError: … expected:<SOFTWARE> but was:<TRUSTED_ENVIRONMENT>
# 整改后复跑 → .\gradlew.bat :crypto:connectedDebugAndroidTest :app:connectedDebugAndroidTest → BUILD SUCCESSFUL（7/7、15/15）
# 合并复跑（同一 Gradle 调用，四任务）→ BUILD SUCCESSFUL in 2m 34s；
#   结果 XML 汇总：tests=32 failures=0 errors=0 skipped=0（app 15 / database 7 / sync 3 / crypto 7）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL；全量 1595 例 / 0 失败 / 0 错误 / 13 跳过（与 §46 基线一致，本批未改单测）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL；产物 D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#   （15,422,987 B，已签名；同目录含 app-release.apk.idsig）
```

**设备侧直接证据（用例自打印，取自 `*/build/outputs/androidTest-results/connected/debug/**/logcat-*.txt`）**：

```text
# NativeArgon2InstrumentedTest（crypto）
[NativeArgon2 设备侧加载证据] nativeLibraryDir末段=arm64, apkAbiDir=arm64-v8a,
  supportedAbis=arm64-v8a, armeabi-v7a, armeabi,
  nativeLibraryDir=/data/app/~~…==/com.keepasskey.crypto.test-…==/lib/arm64,
  apk=…/base.apk, lib/arm64-v8a/libkeepasskey_argon2.so=486896 bytes, 磁盘解包副本=false
# → 平台短 ABI 名与 APK 完整 ABI 名确为两套命名；`.so` 自 APK 内 mmap 加载（未解包落盘）

# QuickUnlockSealDowngradeDeviceTest（app）
[Keystore 落位设备侧实测] level=TRUSTED_ENVIRONMENT, 软件Keystore环境=false,
  MODEL=Redmi 4X, PRODUCT=lineage_Mi8937_4_19, HARDWARE=qcom, SDK=37, ABI=arm64-v8a, armeabi-v7a, armeabi
# → 真机落位为 TEE（非 UNKNOWN，46.3 的 SecretKeyFactory 修复在真机同样成立）
```

### 47.4 已知边界与口径（如实声明）

1. **命名引用更正**：§46.3 / §46.4 中「设备侧用例 `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE`
   长期回归锁定」指的是本批同一条用例——该用例经 47.1 更名为
   `AndroidKeyStore真实密钥落位在设备侧返回真实等级`（断言口径由「硬编码 SOFTWARE」改为「环境分支」），
   §46 正文按历史留痕不改写。
2. **模拟器侧口径不回归**：`isSoftwareKeystoreEnvironment()` 为**启发式**（平台无「是否具备 TEE」的公开查询，
   仅有 `FEATURE_STRONGBOX_KEYSTORE`）；模拟器（`PRODUCT=sdk*` / `HARDWARE=ranchu|goldfish` / 旧镜像 `FINGERPRINT=generic`）
   仍走 `SOFTWARE` 断言分支，故模拟器基线与 §46.4 的结论不受本批影响。
3. **本批未改变任何产品行为**：仅改 `androidTest` 源集（2 个文件），`app/src/main` 与其余模块零改动。
4. **仍未覆盖**（沿用既有登记，不因本批而消解）：`ISSUE-P2-42`（敏感对话框 `FLAG_SECURE` 实效 /
   附件缓存冷启动清理 / `SecureDialog` provider 命中）与 `ISSUE-P2-80`（KDF 墙钟与内存闸门分路径实测）
   仍为开放项——本批只证明「既有设备侧用例在 arm64 真机可复跑且全绿」，**不构成**上述两项的验收。

---

<a id="s48"></a>
## §48 存量安全整改批次：KDF 预算 + TOTP 保护 + 剪贴板闭环 + 明文持有者锁观察者 + 换库前置释放 + 附件引用预算 + 完整性门控对称化 + Passkey 归属与验证绑定（2026-09-14）

> **本批次缘起**：认领 `ISSUE-P2-48 / P2-49 / P2-51 / P2-53 / P2-61 / P2-63 / P2-65 / P2-72 / P2-76 / P2-77`
> 与 P3 升格项 `P3-109 / P3-117`（均为第四轮独立复核后**升格 P1 排期**的开放项）及联动项 `ISSUE-P3-84`。
> 整改落在 `app/src/main` / `database/src/main`，配套单测同步补齐；
> `P2-49` 的 AC② 因墙钟量级未实测（挂 `ISSUE-P2-80`）**未闭环**，条目仍留在 `ACTIVE_ISSUES.md`；
> `P3-116 / P3-120` 涉及真机实测，本环境无设备，仍保留。

### 48.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-61** | P2（升格 P1） | TOTP 种子在**三处生产写入路径**恒 `isProtected = false` → XML 走非保护分支、种子以明文落盘（无 `Protected="True"`、无内层流 XOR） | `VaultEntryMapper.mapUiEntryToKdbx`、`VaultEntryWriteCoordinator`（合并更新 + `saveTotpSecret`）三处改 `isProtected = true`；回归锁「新建路径写出受保护 + 往返后仍受保护 + 明文 `otp` 仍可读（兼容外部库）」 | 官方 `Write.cs:838-854`（非标准字段保留 per-value `IsProtected`） |
| **ISSUE-P2-51** | P2（升格 P1） | ① `clearClipboard()` 仅由定时器调用，未接锁定 / 熄屏 / 冷启动；② 后台读不到剪贴板时 `lastSensitiveHash` **陈旧匹配**会误清他处内容 | `ClipboardSecurityManager`：实现 `SessionLockObserver`（`DatabaseModule` 注册）+ 熄屏广播 + `ProcessLifecycleOwner` 切后台即清（P3-84）+ 冷启动对账（跨进程仅存**布尔**待清标记）；新增 `clipboardSuperseded` 标志 + 纯裁决 `ClipboardClearPolicy` 消除误清 | 审计 F-18；`AGENTS.md` §6 冷启动缺口同族 |
| **ISSUE-P3-84** | P3 | 关闭「自动擦除」无风险明示；敏感值无前台切走即清机制 | 设置页关闭态渲染 `sec_clipboard_risk_notice`（中英双语）；切后台即清（见上）；文案引导「改用自动填充直填」 | 审计 M9 / 产品裁决语义（对齐 `FlagSecurePolicy`） |
| **ISSUE-P2-65** | P2（升格 P1） | 多处持有明文的 ViewModel/控制器**未注册 `SessionLockObserver`**：锁库后明文继续驻留（原仅靠 `onCleared` / 导航离开擦除） | `EntryDetailViewModel`（按需解密明文 + 实时 TOTP 码）、`GeneratorViewModel`（生成结果，参数由 `ClipboardSecurityManager` 收窄为 `ClipboardSecurityChannel` 以便注入断言）、`EntryEditViewModel`（口令 / 种子 / 受保护字段编辑态）、`SettingsViewModel`（WebDAV 口令 / S3 SecretKey / AccessKey 预填通道）四处注册锁观察者并在 `onCleared` 注销；断言「锁库 → 明文已清零」 | 审计 M3；机制对齐 `DatabaseModule` / `SyncCoordinator` 既有 4 处用法 |
| **ISSUE-P2-77** | P2（升格 P1） | **切换 / 新建密码库不擦除旧库、不通知锁观察者**：`SessionOpener.create/openStream` 直接替换 `core.database.value` → 旧库全部 `ProtectedString` 密文滞留至 GC；旧库的同步缓存与**明文附件缓存**不被驱逐 | `DatabaseSession.releaseSessionStateForReplacement()`（语义对齐 `lock()`，但不取互斥锁避免重入）+ `SessionOpener` 新增 `releaseCurrentSession` 回调，在 `create` / `openStream` **装载新库之前**调用；回归断言「换库事件序 = `clear`→`store`」+「旧库受保护字段已擦除」+「新库附件存活」 | 威胁建模 Q-16 / T-9c；顺序硬约束见 `SessionOpener` KDoc |
| **ISSUE-P2-48** | P2（升格 P1） | **附件引用放大无累计预算**：同一池条目被引用 N 次即 N 份 `copyOf()` 副本；`MAX_XML_ELEMENTS` 与整包上限只**间接**约束该乘积 | 新增 `BinaryReferenceBudget`（`2 × 池总字节 + 1 MiB` 余量，fail-closed），经 `KdbxXmlParser` → `FileNode/RootNode/GroupNode/EntryNode/HistoryNode` → `BinaryNode` 逐层传入；**不取消**逐引用物化（ISSUE-P3-07 契约防线） | 审计 F-10；`KdbxAttachmentAliasIsolationTest` 4 例保持通过 |
| **ISSUE-P2-53** | P2（升格 P1，与 P2-63 同批） | **CM 主通道（`passkey/`）对 `RuntimeIntegrityGate` 零命中**：完整性裁决在自动填充 fail-closed，CM 通道 fail-open（风险态仍下发候选 / 保存入口） | `KeePasskeyCredentialProviderService` 注入门控，在 `buildBeginGetResponse` / `buildBeginCreateResponse` 两条入口**最先**裁决 `awaitEnforcement().disableAutofill`，风险态返回**空响应**；静态接线回归断言「两入口各裁决一次 + 自动填充通道同判据」 | 审计 F-24；通道对称性与自动填充对齐 |
| **ISSUE-P2-63** | P2（升格 P1，与 P2-53 同批） | **生物快速解锁门控只用冷启动快照**：非 suspend `currentEnforcement()` 把 `hookFrameworkDetected` 硬编码为 `false`，钩子重扫仅存在于 suspend 路径 → 启动后附加 Frida 不触发信号、门控不拦 | `RuntimeIntegrityDetector`：① 由「一次性扫描」改为**后台周期重扫**（30s，注入信号进入快照）；② `currentEnforcement()` 显式并入快照内的钩子信号；③ 新增 `RuntimeIntegrityPolicy.isSnapshotStale`——快照陈旧（默认窗口 120s）或未判定即返回保守策略并触发重扫（fail-closed） | 审计 H-new-2；`RuntimeIntegrityPolicyTest` 既有「实时钩子升级」用例 + 新增 3 例新鲜度用例 |
| **ISSUE-P2-72** | P2（升格 P1，审计 E4 已收窄） | `clientDataJSON.androidPackageName` 归属可错：Assertion 侧用 `Activity.getCallingPackage()`（PendingIntent 拉起时为 `android`/null → 回退**本应用包名**，向 RP 虚假归属）；Create 侧 `?: callingPackage` 回退可产生 `android://android` 绑定 | `CallingOriginResolver` 新增 `systemAttestedPackageName`（仅接受平台 `CallingAppInfo`，排除 `android`/空白，**禁止回退**）与 `clientDataAndroidPackageName`（候选过滤，全不可用即**省略字段**）；Assertion 侧改取系统认证包名并与 `EXTRA_EXPECTED_PACKAGE` 交叉核对（不一致即 fail-closed）；Create 侧回退收窄、DAL 门控对 null fail-closed 拒绝；两侧 `clientDataJSON` 均不再回退本包名 | 审计 E4（收窄）；`CallingOriginResolverAttributionTest`（6 例，含核心负例「android 候选被跳过」） |
| **ISSUE-P2-76** | P2（升格 P1，威胁建模 Q-2） | **两条凭据通道认证强度不对称**：自动填充通道有 Keystore `CryptoObject` 密码学绑定，CM / Passkey 通道只凭 `BiometricSucceeded` 回调放行（hook 可伪造） | `CredentialVerificationLauncher` 复用自动填充已验证的同一机制：`prepareAutofillAuthCipher()` 准备绑定 Cipher 并以 `CryptoObject` 发起认证，放行条件升级为 `isSatisfied && AutofillAuthBindingPolicy.isBound(result)`（**无 CryptoObject 不放行**）；Cipher 不可用时**降级为受保护窗口内手动确认**（与自动填充同策略），绝不回落回调级放行 | 威胁建模 Q-2；静态接线回归 `CredentialVerificationBindingWiringTest`（4 断言）；`AutofillAuthBindingPolicyTest` 既有 3 例复用 |
| **ISSUE-P3-109** | P3（升格 P1） | `AutofillLastFilledStore.clear()` 全仓**零调用方**，与 KDoc「换库 / 锁库时清」矛盾 → 上次填充条目 UUID 在明文 prefs 长期驻留 | 该类实现 `SessionLockObserver`，`DatabaseModule` 注册——锁定 / 关闭 / **换库**（`releaseSessionStateForReplacement`）统一清除；新增静态装配清单回归（`DatabaseModuleLockObserversWiringTest`）防「装配被静默移除」 | 审计 L15；`AutofillLastFilledStoreTest`（+2：锁定回调清空 / 幂等） |
| **ISSUE-P3-117** | P3（升格 P1，威胁建模 Q-15 / T-9d） | `clearPasswordOnLeave` 为**死开关**（9 处命中全为持久化 / 投影 / UI 回调，零行为消费方）；未提交主密码跨后台 / 旋转长期驻留 | `UnlockViewModel` 注入 `ExtendedSettingsStore` 并新增 `onScreenLeft()`（开关开启 → `wipeMasterPassword` + 递增擦除令牌）；`UnlockScreen` 经 `DisposableEffect`（ON_STOP / onDispose）接线；断言以「清空后提交必命中空密码拦截」为可观测证据（不引入测试后门） | 威胁建模 Q-15 / T-9d；`UnlockViewModelClearOnLeaveTest`（3 例：开启清空 / 关闭保留 / 令牌递增） |

### 48.2 ISSUE-P2-49 进展（**部分完成，条目保留在 `ACTIVE_ISSUES.md`**）

- **AC①（已完成）**：`KdbxKdfParameterCodec.validateArgon2Bounds` 增加 **`I×M` 联合预算** `2^40` 字节·轮
  （逐项封顶不约束总工作量，单项均合法时乘积可达 `2^58`；该派生**先于** Header HMAC，**无需口令即可触发**）。
  取值宽于本仓 `KdfBenchmark` 自荐上限（≤512 MiB × 20 ≈ 2^33.3）约 100 倍；官方参数域对照
  （`Argon2Kdf.cs:53-71`：`M ≤ int.MaxValue`、`I ≤ uint.MaxValue`、默认乘积 ≈ 2^27）已写入类 KDoc。
  AES-KDF `R` 已由既有 `AES_KDF_MAX_ROUNDS = 2^28` 封顶（无联合项）。
- **AC③（已完成）**：`KdfParametersBoundsTest` 新增「预算内合法配置通过（官方默认 / 自荐迭代上限 / 边界值 2^40）」
  与「逐项均合法但乘积越界被拒」两例。
- **AC②（未闭环）**：阻塞式原生派生**不可被协程 `withTimeout` 打断**（超时仅在阻塞调用返回后的挂起点生效，
  等于无效的纸面加固），且墙钟量级须真机实测（`ISSUE-P2-80` 明令「不得以推算替代」）。故本轮**不引入**
  无效超时，如实留痕并挂 `ISSUE-P2-80`；`P2-49` 条目保留为开放项。

### 48.3 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 4m 13s；114 actionable tasks: 114 executed（全部真实执行）
#   结果汇总（build/test-results/**/TEST-*.xml）：tests=1626 failures=0 errors=0 skipped=13
```

**新增 / 修改用例**（共 +31 例）：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `database` | `KdfParametersBoundsTest`（+2） | 联合预算边界通过 / 越界拒绝 |
| `database` | `KdbxEntrySerializerProtectedFlagTest`（+1） | `otp` per-value 受保护 → 写出 `Protected="True"` |
| `database` | `KdbxEmptyFieldRoundTripTest`（+1） | `otp` 全链路往返后仍受保护、值不变 |
| `database` | `SessionReplacementReleaseTest`（+1，新文件） | 换库事件序 `clear`→`store`（新库附件不被误删）+ 旧库受保护字段已擦除 |
| `database` | `AttachmentReferenceBudgetTest`（+2，新文件） | 单池条目 × 5000 引用被累计预算拒绝（非 OOM）+ 共享池条目少量引用不误拒 |
| `app` | `VaultEntryMapperTotpTest`（+2） | 新建路径写出受保护 / 明文 `otp` 兼容可读 |
| `app` | `ClipboardClearPolicyTest`（+4，新文件） | 空读裁决：未覆盖写且匹配→清；已覆盖写→不清（核心负例）；无记录 / 摘要不匹配→不清 |
| `app` | `GeneratorViewModelSessionLockTest`（+1，新文件） | 锁库 → 生成结果已清零（`readString` 抛 `IllegalStateException`） |
| `app` | `RuntimeIntegrityPolicyTest`（+3） | 快照新鲜度：从未扫描 / 超出窗口 → 陈旧；窗口内（含边界）→ 不陈旧 |
| `app` | `CredentialProviderIntegrityWiringTest`（+1，新文件） | 静态接线：CM get/create 两入口各消费门控一次 + 自动填充通道同判据 |
| `app` | `CallingOriginResolverAttributionTest`（+6，新文件） | 归属过滤：系统包名 / 空白被排除、android 候选跳过后回退 extras 记录、无可用即省略字段（核心负例）、无系统背书返回 null |
| `app` | `CredentialVerificationBindingWiringTest`（+1，新文件） | 静态接线：CM 通道认证传绑定 Cipher + 放行前校验 isBound + 不可绑定降级手动确认 |
| `app` | `AutofillLastFilledStoreTest`（+2） | 锁定回调清空记忆 / 幂等 |
| `app` | `DatabaseModuleLockObserversWiringTest`（+1，新文件） | 静态装配清单：4 个会话终止观察者全部注册 |
| `app` | `UnlockViewModelClearOnLeaveTest`（+3，新文件） | 开关开启清空未提交主密码（可观测证据）/ 关闭保留 / 擦除令牌递增 |

### 48.4 已知边界与口径（如实声明）

1. **冷启动对账不留口令等价物**：跨进程仅留存一处**布尔**待清标记（`clipboard_security` prefs），
   不含明文、不含摘要；正常路径 30 秒窗口内定时器已清空，故该分支极少触发。
2. **切后台即清为产品语义收紧**：切走应用即擦除待清敏感值（`ProcessLifecycleOwner` ON_STOP），
   与「自动擦除开关」独立生效；关闭自动擦除者亦受此保护，代价是切后台后无法粘贴。
3. **`P2-51` 与 `P3-84` 同批**（AC④「产品确认」以设置页风险明示落地），二者互相引用。
4. **`P2-65` 的 `IconBitmapCache` 未接线（评估留痕）**：该类为**屏幕级**自定义图标解码位图 LRU 缓存
   （由 `EntryIconPresenter` 持有，随组合销毁），其内容为派生**图像**而非明文口令 / 种子；且无进程级单例持有点，
   故不注册锁观察者（与 `AGENTS.md` §6 对「已接受边界」的处置口径一致）。AC③ 的「锁库 → 明文清空」
   已以 `GeneratorViewModelSessionLockTest` 在宿主 JVM 断言（该判定不涉平台 API，JVM 即权威）。
5. **`P2-77` 带来的有意行为变更（fail-closed）**：换库前置释放发生在**装载新库之前**，因此
   「已开库 A → 尝试打开库 B 但口令错误」现在会**保持锁定态**（旧库 A 已被释放），
   而不再是「保留 A」。这是 AC 明确要求的顺序（"必须先通知旧库会话锁定、再 load / 落盘新库"）的必然结果，
   方向 fail-closed（错误口令不会让上一库继续在内存中可用）。同理，`create()` 建库亦会先释放旧会话。
6. **`P2-63` 的周期重扫代价与口径**：后台每 30s 重扫一次（`/proc/self/maps` 读取 + 若干
   `File.exists()`，均在 `Dispatchers.IO`），快照新鲜度窗口 120s；窗口内为正常放行，
   超出窗口（进程挂起 / IO 受限）即转保守（fail-closed）。**注入框架探测仍是启发式**
   （磁盘落点 + maps 特征串），命中率真机实测见 `ISSUE-P3-120`，本项只消除「快照陈旧」这一确定性缺口。
7. **`P2-53` 与 CM 响应预算**：门控走 suspend `awaitEnforcement()`（含一次重扫，自带 1s 兜底超时），
   仍在服务既有的 5s `TIMEOUT_MS` 预算内；风险态返回**空响应**而非错误，系统弹窗表现为「无候选」。
8. **`P2-72` 的口径收窄**：审计原断言对 Create 侧为误报（该侧本就优先取系统 `CallingAppInfo`），
   本批仅治理「回退链」——两侧 `clientDataJSON` 与 Create 侧 `boundPackage` 均不再回退本应用包名 /
   `Activity.getCallingPackage()`；归属字段全不可用时**省略**而非回退。`PasskeyCreateActivity` 对
   `callerPackage == null` 的 fail-closed 拒绝沿用既有 DAL 门控，未新增行为。
9. **`P2-76` 的验证预算影响**：绑定 Cipher 准备为同步 Keystore 调用（微秒级）；无强认证器设备
   （原走手动确认）行为不变；Keystore 可用但 Cipher 初始化失败的场景**新增**降级到手动确认
   （此前会以无绑定认证放行——这正是缺陷本身）。
10. **本批未触及**：`ISSUE-P2-49` AC②（见 48.2）、`ISSUE-P3-116 / P3-120`（需真机实测，
    本环境无设备）等升格 P1 开放项仍在 `ACTIVE_ISSUES.md`。

---

<a id="s49"></a>
## §49 存量安全整改批次（续）：密钥文件纯字节解析 + DAL 有界流式读取 + 选择器会话锁定对齐 + CM 保存 URL 分流 + KDF 参数与秘密治理 + 依赖扫描触发面（2026-09-15）

> **本批次缘起**：认领外部审计转登项 `ISSUE-P2-62`（审计 H2，敏感数据流批次）、
> `ISSUE-P2-50`（审计 F-14）、`ISSUE-P2-52`（审计 F-22）、`ISSUE-P2-60`（审计 RUST-06）、
> `ISSUE-P2-56`（审计 RUST-01）、`ISSUE-P2-57`（审计 RUST-02）、`ISSUE-P2-58`（审计 RUST-03）、
> `ISSUE-P2-59`（审计 RUST-05）、威胁建模项 `ISSUE-P2-78`（T-10）与审计项 `ISSUE-P2-54`（F-05，CI 变更）。
> 除 `P2-56` / `P2-57`（原生 Rust 内核，`cargo test` 验证）、`P2-58`（原生内核 + 两个 Kotlin 调用点）、
> `P2-59`（派生入口 Kotlin 侧）与 `P2-54`（工作流触发面）外，其余五项的整改落在 Kotlin/JVM 侧，无需设备。

### 49.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-62** | P2 | `KdbxKeyFile.extractKey` 把**整个密钥文件**转为不可擦 `String`（`raw.toString(Charsets.UTF_8)`），每次解锁尝试与保存都重放；寿命上界为下次 GC | 改**纯字节解析**：`extractKey(raw: ByteArray)` 全链路零 `String` 物化——ASCII 头探测（`<?xml` / `<KeyFile`）、XML 元素定位、v1.0 Base64 / v2.0 Hex 剥离与解码均在 `ByteArray` 上进行，hex 解码走 `kotlin.io.encoding.Base64`/自实现字节 hex；`toString` 仅保留 ASCII 分类校验（无法避免的 String 显式标注）；四类解析梯子（XML v1.0 / v2.0 / 裸 32B / 64-hex / 任意二进制 SHA-256）结果与原实现逐字一致 | 审计 H2；敏感数据铁律（`AGENTS.md` §3.2） |
| **ISSUE-P2-50** | P2 | DAL 响应体先 `body?.string()` **整份物化**，之后才比较 `length`（且为**字符数**，多字节字符下与字节上限错位）→ 恶意端点可借超大响应撑爆内存 | `DigitalAssetLinksVerifier` 改**有界流式读取**：新增 `readBounded(input, limit)`（`MAX_BODY_BYTES = 256 KiB` 封顶，读到上限 +1 即判越界、立即中止、不继续消费剩余字节），并按**字节数**裁决（消除字符数/字节数错位）；空响应体显式拒绝 | 审计 F-14；fail-closed 语义保持（越界 / 空 → `NOT_VERIFIED`） |
| **ISSUE-P2-52** | P2 | 选择器缓存**活** `KdbxEntry` 树：锁定时 `ProtectedString` 就地清零后，缓存条目的任何 `title`/`userName`/`url` 读取都会抛 `IllegalStateException`；且 `resolveCredentials` 在 `try` 之外读 `userName` | `AutofillPickerViewModel` ① 注册 `SessionLockObserver`（锁定即清空缓存列表；选择器为一次性 Activity，解锁后重开即重新拉取，无需解锁重载）；② `search` 与 `resolveCredentials` 的非敏感字段读取 `runCatching` fail-safe（清零→通知观察者的固有竞态窗口内按空结果 / 空用户名降级）；③ `onCleared` 注销观察者。**不削弱** `ProtectedString.clear()`（只调缓存持有与读取侧容错） | 审计 F-22；机制对齐 §48 `P2-65` 四处 ViewModel 的观察者模式 |
| **ISSUE-P2-78** | P2 | **CM 保存路径写入畸形 URL**：`KeePasskeyCredentialProviderService` 把调用方 **origin**（浏览器委派 `https://…` / 普通应用 `android:apk-key-hash:…`）作为 `EXTRA_WEB_DOMAIN` 下传，`PasswordSaveActivity` 原样透传，`VaultEntryWriteCoordinator.saveAutofillCredential` **无条件**拼 `"https://$domain"` → 落库 `https://https://host` / `https://android:apk-key-hash:…`，条目此后既不匹配 web 域也不匹配 `android://` 包名 | 新增纯函数收口 `VaultEntryWriteCoordinator.resolveCredentialUrlBinding`（自动填充与 CM 双保存通道共用）：空白 / apk-key-hash origin → `android://<调用包名>`；`https://`（含 `http://`）origin → **原样入库**不二次拼前缀；裸域名（自动填充既有形态）→ `https://<裸域名>`；`displayDomain` 归一为裸域名供标题与域匹配；负例断言任何形态不得产生 `https://` 二次叠加或 apk-key-hash 尾巴混入 | 威胁建模 T-10；与自动填充保存路径语义对齐（AC①） |
| **ISSUE-P2-54** | P2 | 依赖 CVSS 闸门仅 `workflow_dispatch` 触发，PR / push 路径不含依赖扫描 | `dependency-scan.yml` 的 `on:` 补 `pull_request` 与 `push{branches:[main]}`（AC①，采纳「直接补触发」路线，扫描完整性优先于耗时）；CVSS 缺失报告 fail-closed 经本地实测复核：对不存在路径执行 `check_dependency_cvss.py` → `[FATAL] 报告不存在…fail-closed` 退出码 1（AC③，`if: always()` + `if-no-files-found: error` 既有机制不变） | 审计 F-05；AC② 留痕见 49.3.6 |
| **ISSUE-P2-60** | P2 | KDF secret `K` 以普通 `ByteArray` 常驻头部且全仓无清零点，会话锁定 / 关闭后仍滞留至 GC | ① `KdfParameters` 新增 `clearSensitive()`（基类 no-op；Argon2 覆写为「就地 fill(0) + 置 null」——只 fill 不置 null 会让引擎把全零数组当合法 secret 参与派生，故 `secretKey` 改 `var`）；② 统一收口 `KdbxDatabase.clearSensitiveData()`：`lock()` / `close()` / 换库前置释放 / 子库只读投影全部既有调用点自动覆盖；③ **显式生命周期契约**（类 KDoc）：仅限会话终止路径调用（互斥锁内、随后 `database = null`，不可能再发起保存派生），`KdbxHeader.copy()` 浅拷贝共享引用的**就地清零**语义与所有权约定成文；④ `Argon2KdfEngine` 对 `var secretKey` 取局部快照消除 smart-cast 编译错误与并发中间态。清零后引擎按「无 secret」跳过、序列化按「缺 K」不写出——可观测失效而非静默错密钥 | 审计 RUST-06；敏感数据铁律（`AGENTS.md` §3.2） |
| **ISSUE-P2-56** | P2 | Argon2 `m_cost` 工作内存（派生中间态）释放前不擦除：`lib.rs` 用 `hash_password_into`，而 `Cargo.toml:30` 宣称 `zeroize` feature 已擦除 | ① **实证**（2026-09-15 读 argon2 0.6.0 源码）：`zeroize` feature 只覆盖 `initial_hash`（`lib.rs:389-390`）与 finalize 的 `blockhash` / `blockhash_bytes`（`lib.rs:570-573`）；`hash_password_into` 内部分配的 `Blocks` 其 `Drop` **仅 dealloc、不清零**（`block.rs:190-200`，`Zeroize for Block` 存在但从未被调用）→ 原宣称不成立；② 改 `hash_password_into_with_memory` + 自持 `Zeroizing<Vec<Block>>`（析构含提前错误路径经 `Vec<Block>: Zeroize` → `Block::zeroize` 全量归零）；③ 分配失败走 `try_reserve_exact` → `None`，**保留** `Error::OutOfMemory` 的优雅失败语义（严禁 `resize` panic / abort——超大 m_cost 下 abort 比现状更坏）；④ `Cargo.toml` 注释更正宣称口径 | 审计 RUST-01；`AGENTS.md` §3.2 原生内核擦除纪律 |
| **ISSUE-P2-57** | P2 | 派生密钥**栈副本**残留：① `sha2` 未启用 `zeroize`（`Cargo.toml`）→ `Sha256` 内部 state（AES-KDF 的 composite key 即驻留其中）析构不清零；② `Some(*out)`（`lib.rs`）把 `Zeroizing` 缓冲整份拷出为**不可擦栈副本** | ① `sha2` 启用 `zeroize`（= `digest/zeroize`）：`Sha256` 满足 `zeroize::ZeroizeOnDrop`，且 `Sha256VarCore::finalize_*_core` 收尾显式擦除 `state` / `block_len`；② 新增 `derive_into(..., out: &mut Zeroizing<[u8; OUT_LEN]>)`（Argon2）与 `aes_kdf_into(...)`（AES-KDF），**输出直接写入调用方受管缓冲**；JNI 两桥（`jni_bridge` / `jni_bridge_ext`）改走 `_into` 变体；`derive` / `aes_kdf` 门面**保留**供 KAT / BC 向量比对（KDoc 明示「仅测试与非秘密比对，生产必须走 `_into`」）；③ AES-KDF 额外显式 `zeroize` finalize 返回的摘要副本（`Zeroizing` 包裹 + 提前擦除）；④ **无算法变更**——三条独立证据锁定：IETF KAT 4 例 + BC 冻结向量 12 例 + 新增「`_into` 与门面逐字节一致」2 例 | 审计 RUST-02；`AGENTS.md` §3.2 |
| **ISSUE-P2-59** | P2 | 原生 Argon2 路径**缺内存上界预检**（`Argon2KdfEngine.transform` 直调 JNI，`isMemoryParamFeasible` 仅覆盖 BC 分支），且 `(memoryInBytes / 1024).toInt()` / `iterations.toInt()` 存在**静默窄化** | ① 新增 `Argon2KdfEngine.isWithinKdfBounds(memoryInBytes, iterations, parallelism)`：镜像 `KdbxKdfParameterCodec.validateArgon2Bounds` 的**逐项上界**（8 KiB ~ 4 GiB / 2²⁴ 迭代 / 64 并行度；crypto 不可反向依赖 database，故同值声明 + 跨模块用例锁）；原生入口前置该裁决，越界即不进原生路径（回落既有 BC 兜底，其自带堆预检）；② 新增受检窄化 `requireExpressibleAsInt(value, field, scale)`：超 `Int` 范围**抛 `KdfException`** 并指明字段与上限，**取代**两处 `.toInt()`（BC 分支同步改用已预检的窄化值）；③ 边界值宽于一切合法用户配置（官方默认 64 MiB / 本仓自荐 ≤512 MiB × 20 均远小于封顶），并附跨模块「越界值双侧一致拒绝」用例防两侧漂移 | 审计 RUST-05；`AGENTS.md` §3.2 |
| **ISSUE-P2-58** | P2 | 口令强度评估三条**平方级路径**（`unique_char_count` 用 `Vec::contains`、`longest_keyboard_walk` 逐起点内扫、`minimal_period` 逐周期长度试探）；`MAX_TEXT_CHARS`（XML 节点封顶）对**该热路径不构成约束**，而 `HealthCheckEngine` 对**全库每条口令**循环评估（恶意库 CPU DoS 面乘性放大）；两个生产调用方运行在**主线程**（`SettingsHealthController` / `EntryDetailRevealController`，均 `viewModelScope` 裸 `launch`） | ① `estimate` 入口加**长度上限 `MAX_ANALYZED_CHARS = 256`**：熵基线与模式识别只用前缀、未分析尾部**不获熵信用**且按 `EXCESS_PENALTY_PER_CHAR = 0.05` **线性惩罚**（陷阱 #7：原「超出只按长度评分」会把 `'1234'×100` 判为极强）；② `longest_keyboard_walk` 改**单趟 O(n)**（与前驱比较递推，保持「同排且列差绝对值 1」与跨排负例语义）；③ `unique_char_count` 改 **O(n)**（ASCII 128 位图 + 非 ASCII 小表）；④ **额外**将 `minimal_period` 改 **KMP 前缀函数 O(n)**（全模块最后一条平方级路径）并让周期判据跑**全量字符**（截断会使 `abc × 100` 因不整除漏判周期而被抬到高档）；⑤ 两个调用方的 CPU 段（整库投影 + 审计扫描 / 解密 + 原生强度内核）移入 `withContext(Dispatchers.Default)` | 审计 RUST-03；`AGENTS.md` §3.2 |
| **ISSUE-P2-55** | P2 | 发布签名口令等于仓库公开的示例值：`keystore.properties.example` 把 `storePassword` / `keyPassword` 写成 `keepasskey123`，而本地与流水线直接照抄该值签名——示例值随公开仓库泄露，等于把发布密钥公开（任何人可签出被系统认可的「官方」包，签名校验永远通过） | ① **示例改为不可误用占位符**：`__REPLACE_WITH_HIGH_ENTROPY_PASSWORD__`，并在示例内写明硬性要求与 **re-key（只换口令不换密钥）** 的完整 `keytool -importkeystore` 命令；② **构建期断言（fail-closed，无豁免开关）**：`app/build.gradle.kts` 在配置阶段校验已启用的签名口令——命中**已公开弱口令黑名单**（含 `keepasskey123`）或**模板占位符**或**长度 < 16** 即 `error(...)` 终止构建（未配置签名的未签名构建不受影响）；判定顺序为「黑名单/占位符 → 长度」，以便对 F-06 场景给出精确诊断；③ **本机既有 PKCS#12 已 re-key**（`keytool -importkeystore` 换口令），并用 `apksigner verify --print-certs` 证明产物签名证书 SHA-256 与旧库**逐字一致**（已安装用户仍可覆盖升级） | 审计 F-06；`AGENTS.md` §1 发布链路 |

### 49.2 验收证据

```powershell
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 5m 49s；114 actionable tasks: 114 executed（全部真实执行）
#   结果汇总（build/test-results/**/TEST-*.xml）：tests=1663 failures=0 errors=0 skipped=13
#   （app 891 / core 65 / crypto 127 / database 377 / sync 203）

cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（原生内核，含本批新增 14 例）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL；产物 D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk

# —— ISSUE-P2-55 构建期闸门的**现场实测**（三条规则各跑一次，配置阶段即裁决）——
# 1) 已公开弱口令（经环境变量注入，模拟"照抄示例值"）：
$env:KEYSTORE_PASSWORD='changeme'; $env:KEY_PASSWORD='changeme'; .\gradlew.bat :app:help
#   → BUILD FAILED in 2s：发布签名口令命中**已公开的示例 / 弱口令**（storePassword…）…
# 2) 模板占位符原样拷贝：
$env:KEYSTORE_PASSWORD='__REPLACE_WITH_HIGH_ENTROPY_PASSWORD__'; .\gradlew.bat :app:help
#   → BUILD FAILED in 2s：发布签名口令仍为模板占位符（storePassword…）…
# 3) 真实高熵口令（本机 re-key 后的 keystore.properties）：
.\gradlew.bat assembleRelease
#   → BUILD SUCCESSFUL in 51s（正向：闸门不误伤）
& $env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk
#   → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
#     （与 re-key 前旧库指纹 F3:A6:F0:92:…:84:2E 逐字一致 → 同密钥、可覆盖升级）
```

**新增用例（共 +49 例：JVM +35 / 原生 +14）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `database` | `KdbxKeyFileTest`（+2） | ① v1.0 跨行 + 大量空白 Base64 的字节解析与原 String 版逐字一致（回归锁）；② 非法 Base64 `Data` 抛 `KdbxCorruptFileException` |
| `app` | `DigitalAssetLinksVerifierTest`（+3） | ① 2 MiB 响应（> 256 KiB 上限）被拒且不被整体物化 → `NOT_VERIFIED`；② **恰好等于**字节上限且内容合法仍可校验通过（边界不误拒，字节数断言精确抵平 `MAX_BODY_BYTES`）；③ 空响应体拒绝 |
| `app` | `AutofillPickerViewModelSessionLockTest`（+2，新文件） | ① 会话锁定 → 选择器缓存条目清空；② 锁定竞态窗口内已清零条目：`search` 按空结果降级、`resolveCredentials` 按空用户名降级（均不抛 `IllegalStateException`，核心负例） |
| `app` | `VaultEntryWriteCoordinatorUrlBindingTest`（+6，新文件） | ① web origin 原样入库且 `DomainMatcher` 可命中（含子域正例 / 仿冒域负例）；② apk-key-hash origin 落 `android://<包名>` 且 `isPackageMatch` / `isAndroidPackageMatch` 均命中；③ 空白域回落包名绑定；④ 裸域名保持既有 `https://` 拼接；⑤ 负例：任何形态不得 `https://` 二次叠加或混入 apk-key-hash 尾巴；⑥ 带路径 / 端口 origin 的归一（AC②③） |
| `crypto` | `KdfParametersSensitiveClearingTest`（+5，新文件） | ① `clearSensitive` 清零原数组并置 null（浅拷贝共享者同步失效）；② 不触碰 `associatedData` / `salt`（非秘密）；③ 重复调用幂等；④ AES no-op；⑤ `equals`/`hashCode` 忽略 K（既有语义防回归） |
| `database` | `KdbxDatabaseSensitiveWipeTest`（+2，新文件） | ① `clearSensitiveData()` 擦除头部 KDF secret（收口回归锁：退化为只清条目树即失败）；② AES-KDF 头部安全 no-op |
| `crypto`(原生) | `argon2_memory_tests`（+4，新文件，按 ISSUE-P3-57 落在 `rust/src/tests/`） | ① 自持 `Zeroizing<Vec<Block>>` 路径与 crate 内部分配路径输出**逐字节一致**（纯内存管理替换、零算法漂移）；② `Zeroizing<Vec<Block>>` 全量归零链路（8 块 × 1024B 填 0xAA 后清零，逐字断言无残留）；③ 超大 m_cost（2^31 KiB）分配失败**优雅返回 None**（不得 panic/abort）；④ **`derive_into` 与 `derive` 逐字节一致**（P2-57 AC②，含 secret + AD 路径） |
| `crypto`(原生) | `aes_kdf_tests`（+3，追加至既有文件） | ① `aes_kdf_into` 与 `aes_kdf` **逐字节一致** + 同步锚定独立第三方 KAT（防两路径同时偏移而互证通过）；② `_into` 闸门语义与门面一致且拒绝路径**不写坏**调用方缓冲；③ **编译期锁定** `sha2/zeroize` 已启用（`assert_zeroize_on_drop::<sha2::Sha256>()`，回退该 feature 即编译失败） |
| `crypto` | `Argon2KdfEngineBoundsTest`（+6，新文件） | ① 上界镜像内存边界（8192B / 8191B / 4 GiB / 4 GiB+1）；② 迭代与并行度边界（2²⁴、0、64、65）；③ 不误拒合法配置（官方默认 / 本仓自荐上限 512 MiB×20 / 界内大内存）；④ 超 `Int` 可表达范围的内存参数**抛异常而非静默截断**（断言异常信息指明字段与「超出可表达范围」）；⑤ 同型迭代越界；⑥ 受检窄化边界值（`Int.MAX_VALUE` 恰好放行、+1 抛异常） |
| `database` | `Argon2KdfBoundsMirrorTest`（+7，新文件） | **跨模块上界锁**：内存 / 迭代 / 并行度三类**越界值双侧一致拒绝**（解码侧 `KdbxKdfParameterCodec` ⇔ 派生入口 `isWithinKdfBounds`，不依赖堆环境）；界内且堆可容纳时两侧一致放行；**显式区分口径**——4 GiB 逐项界内但解码侧受动态堆门槛裁决（按实际 `maxMemory()/2` 分支断言，不做环境依赖的硬编码预期）；合法用户配置不得被派生入口误拒 |
| `crypto`(原生) | `strength_tests`（+6，追加至既有文件） | ① **陷阱 #7 防线**：超长重复串（`1234`×100 / `abc`×100）必须命中周期标志且 `score ≤ 1`；② 白名单：300 字符杂乱串仍为最高档（长度上限不得误伤）；③ 10 万字符输入**线性完成**且语义合理（原三条平方级路径在此规模为 `10^10` 量级）；④ ASCII 位图计数（95 互异字符、唯一率恰 0.5 不触发低唯一率）；⑤ 非 ASCII 计数分支语义不变（互异不触发 / 全同触发）；⑥ 键盘行走单趟化等价性（整排列行走命中、非键盘字符打断、跨排负例）+ 长度上限边界单调性 |
| `app` | `HealthScanOffMainThreadTest`（+1，新文件） | **AC④ 真实运行现场断言**：`Dispatchers.Main` 指向测试派发器后记录仓库真被调用的**线程名**，必须落在 `DefaultDispatcher-worker-*`（摘掉 `withContext(Dispatchers.Default)` 即失败） |
| `app` | `EntryRevealEntropyOffMainThreadWiringTest`（+1，新文件） | **AC④ 第二调用点接线检查**（静态源码比对，沿既有 `*WiringTest` 先例）：`getEntryPasswordChars` + `PasswordEntropyEstimator.estimateBits` 必须同处 `withContext(Dispatchers.Default)` 块内 |
| `app` | `OffMainComputation.kt`（+0 例，新测试工具） | 等待「已移出主线程的纯 CPU 工作」完成（真实时间轮询 + 虚拟时间推进），供 3 个既有用例在并发位置变更后确定性断言（见 49.3.14） |
| `app` | `ReleaseSigningPasswordGateTest`（+2，新文件） | ① 示例文件的口令字段必须是占位符且不得命中任一已公开弱口令（同时要求示例显式警示 `keepasskey123` 不可复用）；② 构建脚本须保留闸门三要素（弱口令黑名单 / 长度下限 / 占位符检测）且以 `error(...)` fail-closed（防「被人删掉」，动态行为见 49.2 实测） |

### 49.3 已知边界与口径（如实声明）

1. **`P2-62` 的残留 String 面**：`extractXmlElementContent` 内部对**标签名/属性名**等非秘密骨架
   仍存在短命 `String`（仅用于 ASCII 结构定位，不含密钥材料字节）；密钥材料
   （Base64 / Hex 数据段）全程 `ByteArray`，无可擦 `String` 路径。`KdbxKeyFile` 产物 `ByteArray`
   由调用方（复合密钥装配）按既有契约清零，本批未改变所有权。
2. **`P2-50` 的字节裁决口径**：上限 256 KiB 为防御性预算（官方 `assetlinks.json` 实际远小于该值）；
   越界响应**不整体物化**（读到上限 +1 即中止），但 OkHttp 连接层缓冲（响应头 + 前 8 KiB 读取窗）
   不在本裁决可控范围——该残余面与既有 `AGENTS.md` §6 口径一致。
3. **测试字节对齐**：边界用例的填充公式为 `pad = MAX − len(dalJson)`（`body = "[" + pad空格 + dalJson().substring(1)`
   的总字节数 = `pad + len`），曾因误写 `− 2` 偏差 2 字节（`expected:<262144> but was:<262142>`），
   已修正并留公式注释防回归。
4. **`P2-52` 的容错边界**：fail-safe 只覆盖**非敏感投影字段**（`title` / `userName` / `url`）的
   读取侧；`resolveCredentials` 的密码解密路径本就在 `try` 内（`getEntryPasswordChars` 失败按
   空密码降级，既有语义）。锁定后选择器清空列表意味着「锁定瞬间打开的选择器」呈现空列表——
   用户解锁后重开即恢复，属 fail-closed 的预期 UX。
5. **`P2-78` 的既有条目不受影响**：分流只作用于**新建**条目的 URL 落库与新凭据去重匹配；
   历史上已被写坏的 `https://https://host` 条目不在本批做数据迁移（`extractDomain` 会把它
   归一为 `https`，本就无法可靠还原原域）——用户可在条目编辑页手动修正 URL。
   `FakeVaultRepository` 中的同形测试替身逻辑**未同步**（测试假数据通道，不承载 AC 语义）。
6. **`P2-54` 的 AC②（分支保护必需检查）未闭环——本环境无权限，如实留痕**：本地 `gh` PAT
   对 `repos/.../branches/main/protection` 返回 HTTP 403（Resource not accessible by personal
   access token），分支保护属仓库管理面动作、无法经工作流文件声明。**待仓库所有者**在
   GitHub → Settings → Branches → Branch protection rule（main）中把
   `OWASP Dependency-Check` 设为必需状态检查（并按需把 `build.yml` 各 job 一并纳入）。
   该子项不因留痕而视为完成；后续持管理员凭据的环境应补做并把本行闭环。
7. **本批 ⑤（P2-54）为纯 CI 工作流变更**：未触碰任何 Kotlin / 资源代码，单测基线
   （1639 / 0 / 0 / 13）与 release 产物不受影响，未重跑全量测试与 `assembleRelease`；
   验证手段为 YAML 解析（`workflow_dispatch / pull_request / push` 三触发齐备）+
   fail-closed 脚本实测（见交付清单行）。
8. **顺带修正上批漏删行**：`ISSUE-P2-53`（§48 已闭环）的正文行在 `ACTIVE_ISSUES.md`
   审计表内漏删，本批发现后补删并把 P2 计数修正为 24（前序批注的 2026-09-14 闭环
   声明与 RESOLVED_LOG §48.1 为准，非重新闭环）。
9. **`P2-60` 的残余面与互操作口径**：① `clearSensitive()` 收口于 `KdbxDatabase` 层，
   但 `KdbxHeader` 若被**外部代码深拷贝出独立 K 数组**（当前全仓无此用法，核实于本批）
   则不在清零范围——契约已在 KDoc 声明「浅拷贝共享同一逻辑所有者」；② 清零仅发生在
   会话终止，**解锁存续期**内 `K` 仍以 `ByteArray` 驻留（保存派生必需，AC③ 明示的
   时机约束）——与 `ProtectedString` 驻留加密同族的既定边界；③ 带真实 `K` 的库
   （`K` ≠ null）在真实语料中为零（`RealKdbxCorpusUnlockTest` 断言语料不带 K），
   故本整改对既有解锁 / 互操作路径零行为变化。
10. **`P2-56` 的口径与代价**：① 原 `Cargo.toml` 关于 `zeroize` 的宣称**经源码实证不成立**
    （详见交付清单），本批以「自持 `Zeroizing<Vec<Block>>` + `hash_password_into_with_memory`」
   **择一实施整改**，并同步更正该注释；② 该改动**只替换工作内存的分配与释放方式**，
   算法、参数与输出**逐字节不变**（已由 `bc_frozen_vectors_equivalence` 12 条 BC 冻结向量
   + 4 条 IETF KAT + 新增「自持路径 vs crate 路径逐字节一致」用例三重锁定）；③ 代价是多一次
   `Zeroizing` 包装（无额外拷贝，`Vec` 直接 move 入守卫），**R1 性能面不受影响**；④ 分配路径
   由 crate 的 `alloc_zeroed + Drop(dealloc)` 改为 `try_reserve_exact + resize + zeroize Drop`，
   失败语义保持 `None`（JNI → Kotlin `KdfException`），与 C 桥「非法参数返回 NULL」契约一致；
   ⑤ 本机未安装 `clippy` 组件（`cargo-clippy.exe` 缺失），静态检查由 CI 承担，本地以
   `cargo test`（50/50）与 `assembleRelease` 双通过为验收。
11. **`P2-57` 的改动范围与口径**：① `${...}` 门面（`derive` / `aes_kdf`）**保留**是**有意的**——
   IETF KAT 与 BC 冻结向量用例以「返回值」形态断言，且 JNI 之外无生产消费方；
   KDoc 已明示其「仅测试与非秘密比对」定位，生产路径（JNI 两桥）一律走 `_into` 变体；
   ② 除 AC 点名的 Argon2 `derive` 外，**同型缺陷一并整改** AES-KDF（`aes_kdf` 同样以
   `Option<[u8; 32]>` 返回普通栈副本，属同一条目「派生密钥栈副本残留」的同一缺陷类），
   并在 `jni_bridge_ext` 同步改走受管缓冲；③ `sha2` 0.11.0 的 `zeroize` feature 定义在
   `Cargo.toml:52`（`zeroize = ["digest/zeroize"]`），启用后 `digest/zeroize` → `block-buffer/zeroize`
   使 `Sha256`（`CtOutWrapper<Sha256VarCore, U32>`）满足 `zeroize::ZeroizeOnDrop`，
   且 sha2 在 `finalize_variable_core` 中显式 `state.zeroize()`（`block_api.rs:91-95`）；
   ④ 残余面：JNI 边界把 32 字节派生密钥写入 Java 数组后，Kotlin 侧由 `NativeArgon2` /
   `NativeAesKdf` 既有 `CharArray`/`ByteArray` 契约负责擦除（本批未改变该契约）；
   ⑤ 本批原生侧改动只影响分配 / 擦除路径，Kotlin 侧 JNI 签名与语义**零改动**
   （JVM 基线 1659 例在全量重跑后保持全绿即为证据）。
12. **`P2-59` 的口径与边界**：① **镜像范围 = 逐项上界**（AC① 枚举的内存 / 迭代 / 并行度），
   **不含**解码侧的 `I×M` 联合预算与动态堆门槛——后者分别由 `KdbxKdfParameterCodec`（deserialize 阶段）
   与 BC 分支的 `isMemoryParamFeasible` 承担；跨模块用例**显式区分**该口径（越界值严格同界断言，
   界内值按实际堆容量分支断言），不制造「两侧完全等价」的假承诺；
   ② **行为变更（fail-closed 方向）**：逐项越界的参数此前会直送原生内核（其无上界，仅靠 JNI 有符号闸门
   与派生失败兜底），现改为**不进入原生路径**并回落 BC 兜底（BC 自带堆预检，越界即异常）。
   合法库不受影响——`validateArgon2Bounds` 已在反序列化阶段拒绝同一批参数，
   故两侧同时越界仅可能出现在「绕过 codec 的内部构造」场景，此时前置拒绝正是期望语义；
   ③ **`P2-56`/`P2-57`/`P2-59` 三条同属原生 KDF 治理链**，本批一并闭环：工作内存擦除（`P2-56`）→
   输出与摘要状态的受管缓冲（`P2-57`）→ 参数上界与受检窄化（`P2-59`），
   三者的失败语义均保持 `KdfException` / `null`（不向 Kotlin 抛出裸 `Error`）；
   ④ 本批**未触及** `ISSUE-P2-49` AC②（KDF 墙钟超时，须 `ISSUE-P2-80` 真机实测）与
   `ISSUE-P2-58`（口令强度平方级路径，属独立条目）。
13. **`ACTIVE_ISSUES.md` 的 P2 计数校正（本批顺带发现并修正）**：该节开放条目存在两种承载形式——
   表格行（`| ISSUE-P2-xx |`）与标题条目（`### ISSUE-P2-xx（新登记）`，即 P2-42 ~ P2-47 六项）。
   核对现状（2026-09-15）：表行 **15** + 标题条目 **6** = **21**，而前序批次递减时**只按表行**计算
   （HEAD 处 18 行 + 6 标题 = 24，却记 23），故存在**继承性 off-by-one**。本批按
   「表行 + 标题条目」统一口径校正为 **21**；同法核对 P3 节为 **39 + 7 = 46**，与声明一致（无需修正）。
   **纪律补充**：后续任何增删条目，均须按此双形式口径复算节标题计数。
14. **`P2-58` 的口径、代价与既有用例适配（如实声明）**：
    ① **模型局限（有意保留，非本批引入）**：`estimate` 的熵基线为
    `已分析长度 × log2(字符集)`，故「长但低熵」的来源若前缀本身形如长随机串
    （例：`qwertyuiop × 26`，全长 260 恰好整周期）仍可能落在最高档——原实现同样如此
    （周期项按「单元自身随机 + 重复次数」计 credit），本批**未**改动该建模取舍；
    本批消除的是**确定性缺陷**：三条平方级路径 + 超长输入的 DoS 面 + 「超出只按长度评分」陷阱。
    ② **长度上限的语义**：`len`（真实长度）仍决定 `FLAG_TOO_SHORT` 与长度分档上限（口径不变）；
    `analyzed`（≤256 前缀）承担熵基线与模式识别；`excess` 尾部不获熵信用且按 0.05/字符线性扣减。
    ③ **周期判据跑全量字符**：这是与②的必要例外——KMP 版本为 O(n)（常数极小），
    而截断会让 `abc × 100` 这类重复块因 256 不整除 3 而漏判，反而被抬到高档。
    ④ **既有用例适配（4 例，语义未变）**：`HealthCheckViewModelTest`（1）/ `BreachCheckHealthTest`（2）/
    `EntryDetailDisplayPreferencesTest`（1）原先依赖「CPU 工作在主线程 + 虚拟时间」同步完成，
    AC④ 后须等待真实线程回写——新增测试工具 `app/src/test/.../testutil/OffMainComputation.kt`
    （真实时间轮询 + 虚拟时间推进）补齐等待；其中「默认遮掩不预解密」负例**同时加强**：
    显式等待一个空窗期后再断言为空（否则该负例会被「尚未回写」蒙混通过）。
    ⑤ **未覆盖面**：`HealthCheckEngine` 内部若有其他调用方（核实于本批：仅此两处生产调用方）
    仍须各自确认线程归属；本批未引入统一的「CPU 工作派发器」抽象（避免过度设计）。
15. **`P2-55` 的口径、凭据处置与流水线影响（如实声明）**：
    ① **密钥库本体不在仓库内**：`release.jks` 与 `keystore.properties` 均被 `.gitignore` 忽略、
    从未被 git 跟踪（核实：`git check-ignore -v` 命中 `.gitignore:49` / `:51`）；因此本项治理的
    是「**示例值即真实口令**」这一发布链路缺陷，而非仓库内的凭据泄露。
    ② **re-key 已在本机对既有 PKCS#12 执行**（原口令即示例值）：`keytool -importkeystore` 生成
    新库后以**指纹逐字相等**校验（`SHA256: F3:A6:…:84:2E` 前后一致），随后替换 `release.jks` 并更新
    本地 `keystore.properties`（未跟踪）；**新口令仅存在于该未跟踪文件与构建产物签名中，未写入仓库、
    未写入本报告、未出现在命令文本或终端回显中**（生成 → 立即落盘 → 变量与临时文件即时清除）。
    ③ **流水线影响（预期行为）**：CI 若曾用示例值作为 `KEYSTORE_PASSWORD` / `KEY_PASSWORD` Secret，
    现会在配置阶段直接失败——需把 Secret 换成高熵口令（与本地 re-key 同步）；**这是本 AC 期望的
    fail-closed 行为，不提供任何豁免开关**。发布链路的「无签名构建」路径不受影响（闸门仅在
    `hasReleaseSigning` 为真时生效）。
    ④ **未做的取舍**：未把闸门做成可在 CI 上独立运行的任务（构建期断言已覆盖同一不变式）；
    未引入 `keytool` 的程序化封装（re-key 属一次性运维动作，示例文件内已给出可直接执行的命令）。

---

<a id="s50"></a>
## §50 日志与对象字符串化卫生批次（2026-09-15）：ISSUE-P2-68 / P2-69（审计 M6 / M7）

> **本批次缘起**：认领敏感数据流审计转登项 `ISSUE-P2-68`（审计 **M6**，`data class` 默认 `toString()`
> 展开明文）与 `ISSUE-P2-69`（审计 **M7**，`LogHygieneTest` 抽样正则漏掉注入型日志通道）。
> 两项同属**「潜在缺陷」类**——当前零触发点，但**一步之遥**：一次 `log("$obj")`、一次异常消息插值、
> 一次 IDE 调试求值即漏明文。整改全部落在 JVM 侧，无需设备。

### 50.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-68** | P2 | 四个 `data class` 的默认 `toString()` 展开全部属性，而它们分别持有**条目明文**（`EntryDetailUiState.revealedPassword` / `revealedRevisionPasswords` / `revealedProtectedFields`、`liveTotpCode`）、**凭据内容**（`UiVaultEntry.username` / `totpCode` / `cardCvv` / `notes` / `customFields.value`）、**自动填充明文口令**（`AutofillPickerViewModel.Credentials.password`）与**页面字段当前值**（`ParsedAutofillNode.text`） | 逐个覆写 `toString()` 为**结构摘要**：只出计数（`revealedRevisionPasswords.size` / `customFields.size` / `attachments.size` / `tags.size`）、布尔（`hasTotpCode` / `hasRevealedPassword` / `hasCardFields`）、长度（`textLength=`）与非秘密元数据（条目 id / `groupId` / `category` / 强度位 / 调用方包名），**零内容展开**；对齐 `ProtectedString` / `KdbxEntry` / `KdbxAttachment` 既有做法（AC①）；**不改任何语义**（AC③：字段定义、可空性、默认值、`equals`/`hashCode` 一律未动） | 审计 M6；`AGENTS.md` §3.2 敏感数据铁律 |
| **ISSUE-P2-69** | P2 | `LogHygieneTest` 的抽样口径为**逐行**正则 `\b(Log\|AppLog)\.[edviw]\(`：① 注入型日志字段 `debugLog` / `debugLogBuffer`（全仓 40+ 个 @Inject 日志出口）与委托型 `preferences.verbose(` **完全不在覆盖内**；② 只匹配单行，**换行书写**的调用体（`debugLog.warn(\n TAG,\n "…${e.message}"\n)`）即使通道被覆盖也会逃逸 | ① 抽样口径改为**统一按「日志调用」特征抽取完整调用体**：接收者标识**含 `log`/`Log` 段**（覆盖 `AppLog` / `debugLog` / `debugLogBuffer` / 未来新增 `*Log` 字段）或委托通道 `preferences.verbose(`，后接级别方法（长名 `error/warn/info/debug/verbose/audit/wtf` 与 `AppLog` 短名 `e/w/i/d/v`）；② **括号配对跨行**取完整调用体（跳过字符串字面量内的括号，消息含半角括号不误判配对）；③ AC② 复核该通道现存插值点，**整改 9 处**（见 50.3.3）；④ 新增**防空跑护栏**用例（第 4 例）：断言抽样确曾命中 `debugLog` 通道，且合成源码中跨行调用体的续行插值点被完整抽取——否则前两条断言会在「抽样恒为空」时**伪绿** | 审计 M7；`AGENTS.md` §3.2 日志脱敏纪律 |

**AC② 复核结论（ISSUE-P2-69，三类插值点逐类处置）**：

| 插值点类别 | 复核结果 | 处置 |
|---|---|---|
| `${e.message}`（裸异常 message） | 该通道现存 **8 处**：`KeystoreKeyMaterial`×2 / `UnlockPasskeyManager`×2 / `BiometricEnrollmentCoordinator`×2（均为 `${e.javaClass.simpleName} - ${e.message}`）与 `VaultImportController`×2（跨行，`${error.javaClass.name}: ${error.message ?: "（无消息）"}`）。导入解析失败那两处原注释自称「异常类型 + **静态**文案」——该推定不成立：解析器消息属**不可信外部输入**（JSON/XML 解析器错误消息可回显文档片段，而导入对象是含明文的导出文件） | 全部**去掉 message 分量**，只保留**异常类型**（`javaClass.name` / `simpleName`）——设备侧 SAX 解析器实现差异线索由**类型**承载（JVM 与 Android 解析器异常类不同），诊断能力保留、内容面归零 |
| **endpoint URL** | `SyncCoordinator.describeOutcome` 的 `is SyncOutcome.Error -> "Error(${outcome.message})"` 会把 **原始端点 URL / 主机 / 桶名**写进调试缓冲——其上游 `SyncOutcome.Error(e.message)` 直接取自 `SyncException.InvalidEndpointError`，而该异常消息由 `SyncEndpointGuard` / Provider 构造期**逐字拼接 `"$rawEndpoint"` / `"$host"` / `"$bucketName"`**（`:86` / `:97` / `:121` / `:127`、`WebDavSyncProvider:73`、`S3SyncProvider:98`、`SyncEndpointGuard:67`） | 该分支改为**不含 message 的固定摘要**（`"Error(同步失败，详情见界面提示)"`）——失败详情已由 UI 提示承载（用户自己填的端点），日志只留结果类型。**保留** `remotePath` 记录（`describeCacheEvent*` 的 `path=`）：经复核其为**远端相对路径 / 对象键**（`resolveRemotePath` 返回 `cfg.remotePath` 或 `objectKey`），非 endpoint，且 URL 形态另有 `DebugLogBuffer.exportSanitizedText()` 导出脱敏兜底 |
| **子库别名** | `ChildDatabaseSessionManager` 两处 `"子库挂载成功（别名=${mount.alias}）"` / `"子库已卸载（别名=${it.alias}）"` | 改为 `${mount.alias.length}` / `${it.alias.length}`（沿用「只出长度」口径） |

### 50.2 验收证据

```powershell
# ① 定向验证扩展后的抽样口径（含新增防空跑护栏）
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.log.LogHygieneTest"
# → BUILD SUCCESSFUL；tests=4 skipped=0 failures=0 errors=0（AC③：扩展后既有违规为 0）

# ② 全量单测（强制真实执行，单会话勿并发）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 5m 51s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1668 failures=0 errors=0 skipped=13
#   （app 896 / core 65 / crypto 127 / database 377 / sync 203）——较上批 +5（app）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核，基线保持）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 4m 7s；216 actionable tasks: 17 executed, 199 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,460,235 字节，2026-09-15 10:20:18）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
#   （与 §49 re-key 后指纹逐字一致 → 稳定版签名链路未受影响）
```

**新增 / 改动用例（本批 +5 例，全部落在 `app` 模块 JVM 侧）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `SensitiveToStringRedactionTest`（+4，新文件） | ① `UiVaultEntry`：以**唯一哨兵串** `S3NT1N3L-D0-N0T-LEAK` 填满 title / username / passwordMasked / url / rpId / totpCode / notes / 卡字段 / tags / customFields / attachments / revisions 后断言 `toString()` **不含哨兵**；② `EntryDetailUiState`：`revealedPassword` / `revealedRevisionPasswords` / `revealedProtectedFields` / `liveTotpCode` 填哨兵断言不泄漏，**同时正向断言**非秘密元数据（调用方包名、条目 id）仍保留以便排障；③ `Credentials`：用户名与口令填哨兵断言不泄漏且摘要含 `redacted`；④ 静态源码断言：四类型所在文件均须存在 `override fun toString(): String`，且 `ParsedAutofillNode.toString()` 体内**不得**出现 `${text}` / `$text` / `${label}` / `${htmlName}` 插值（该类型持 Android `AutofillId`，JVM 无法构造实例，故退化为源码断言） |
| `app` | `LogHygieneTest`（+1，扩展既有文件） | **防空跑护栏**：「抽样须覆盖注入通道与跨行调用体」——① 真实源码抽样结果中至少一条命中 `debugLog`（防抽样恒空）；② 合成跨行源码 `debugLogBuffer.warn(\n TAG,\n "落盘失败: ${saved.error.message}"\n)` 必须被抽取为**恰好 1 条**且调用体含续行插值（防窗口截断） |

### 50.3 已知边界与口径（如实声明）

1. **`P2-68` 有意保留的「非秘密元数据」**：`EntryDetailUiState.toString()` 保留 `autofillBoundPackage`
   （调用方包名）与 `entryId`；`ParsedAutofillNode.toString()` 保留 `webDomain` / `inputType` /
   `autofillHints.size`。契约口径为「**不展开条目内容 / 凭据明文 / 页面字段值**」，
   不追求「一律打印 `<redacted>`」——后者会让 `toString()` 在排障中失去全部价值。
   用例**正向断言**该保留（防止后人「一刀切全遮掩」把排障线索也抹掉）。
2. **`P2-68` 未采用的替代方案**（AC② 允许二选一）：未引入「禁止对上述类型整对象插值」的全局静态检查——
   同型检查需类型解析（正则无法可靠判定 `"$state"` 的静态类型），而逐类 `toString()` 覆写已把
   防线放在**类型自身**（任何插值点、任何调用方、含 IDE 求值与崩溃报告全自动受益），
   属更强且零维护的落点。
3. **`P2-69` 的抽样口径性质（启发式，非类型感知）**：以「**接收者标识名含 `log`/`Log` 段**」+
   「级别方法名」为特征。边界面：① 标识名不含 `log` 字样且非 `preferences.verbose(` 的日志出口
   不在覆盖内——**当前全仓无此形态**（核实于本批：日志出口只有统一包装器 `AppLog` 与
   Hilt 注入的 `DebugLogBuffer` 字段，字段名一律 `debugLog` / `debugLogBuffer`）；
   ② 单字母级别名（`e/w/i/d/v`）仅 `AppLog` 提供，其余通道用长名（此设计使 `$catalog.warn(`
   之类的**非日志同形调用**不会被误判为日志或漏判，全仓零误报已由 50.2 ① 实测证明）。
4. **跨行调用体抽取的依赖**：靠「括号配对 + 字符串字面量跳过」实现。已知不构成风险的理论边界：
   字符串模板 `${...}` 内若出现**失衡**括号会使窗口提前收口——此类写法在可编译的 Kotlin 源码中不存在；
   日志消息内的**半角**括号（如 `"（fail-closed）"` 用的是全角）已由字面量跳过逻辑正确忽略。
5. **`P2-69` 唯一的行为改动面**：`SyncCoordinator.describeOutcome` 的 `Error` 分支文案。该函数为
   **private 且仅被一行调试日志消费**（核实：全仓唯一调用点 `SyncCoordinator.kt:179`），
   不影响返回给 UI 的 `SyncOutcome.Error.message`（用户提示文案与错误语义**零改动**）。
6. **本批未纳入的相邻项（留给后续条目）**：① `DebugLogBuffer.exportSanitizedText()` 的 URL / 邮箱
   正则脱敏维持原样（本批未扩大其覆盖面，`remotePath` 等**非 URL 形态**的路径串仍会出现在导出文本中）；
   ② `VaultImportController` 去掉 message 后，导入失败的**细节定位**退化为「异常类型 + UI 分类文案」，
   若后续设备侧需要更细线索，应在**解析层**产出结构化错误码（而非恢复 message 透传）。
7. **`ACTIVE_ISSUES.md` 的 P2 计数**：本批按「表行 + 标题条目」双形式口径复核后由 **19 → 17**
   （表行 13 → 11，标题条目仍 6），与 §49 第 13 条确立的复算纪律一致。

---

<a id="s51"></a>
## §51 自动填充默认值与内存保护口径批次（2026-09-15）：P2-43 / P2-64 / P2-66 / P2-71

> **本批次缘起**：认领四项开放条目——`ISSUE-P2-43`（红队转登：`autofillCopyTotp` 默认开启）、
> `ISSUE-P2-71`（敏感数据流审计 E2：IME 内联建议默认开启）、
> `ISSUE-P2-64`（审计 M1：MemoryProtection 的「内存密封」与「写出标志」被混为一谈）、
> `ISSUE-P2-66`（审计 M4：落盘清理 unlink-only 未登记为已接受边界）。
> 前三项落在 JVM 侧并可完全由单测覆盖，第四项为文档登记（其 AC①/③ 已在第四轮复核中被撤销）。

### 51.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-43** | P2 | `autofillCopyTotp` **默认开启**：每次自动填充确认都把该条目的 TOTP 动态码写入系统剪贴板（`AutofillConfirmActivity` → `AutofillTotpCopyPolicy` → `ClipboardSecurityManager.copySensitiveText`）；擦除窗口内前台应用可读，且定时擦除可由用户关闭（`ISSUE-P3-84`）→ 与口令泄露组合可在有效期内完成第二因素绕过 | ① **三处默认值同步翻转为关闭**：数据类 `ExtendedSettings.autofillCopyTotp`、UI 投影 `SettingsUiState.autofillCopyTotp`，**以及**单键读取 `ExtendedSettingsStore.isAutofillCopyTotpEnabled()` 的硬编码兜底（原 `prefs?.getBoolean(K, true) ?: true`——**只改前两处无效**，该路径才是自动填充运行期真正读的值）；② 设置页文案（中 / 英）如实说明「会写入系统剪贴板，擦除窗口内前台应用可读，默认关闭」；③ 新增回归断言（默认值 / 单键兜底 / 默认配置下对"确有 TOTP 的条目"也不触达剪贴板 / 显式开启后仍生效） | 红队 `AP-22′`；敏感数据铁律（`AGENTS.md` §3.2） |
| **ISSUE-P2-71** | P2 | `inlineSuggestionsEnabled` **默认开启**：IME 内联建议把**候选用户名 / 条目标题**作为文案交给系统输入法（`AutofillInlinePresentationFactory.build(title = username.ifBlank { entry.title }, subtitle = entry.title)`），而 IME 可能是第三方 / 云端联想键盘 → 条目名与账号名持续越过应用边界 | ① 同型三处默认值翻转（数据类 / UI 投影 / 单键读取硬编码兜底）；② 设置页文案如实说明该通道把候选名交给输入法；③ 断言覆盖默认值、单键兜底、构建器「开关为前置门控」的顺序证据；④ 明确**未**采用「改为固定文案」方案（那会使该特性彻底失去价值，而默认关闭已消除默认外泄） | 审计 E2；`AGENTS.md` §3.2 |
| **ISSUE-P2-64** | P2 | 「内存密封」与「写出标志」两条口径被混为一谈：审计表述把「库级 `MemoryProtectionConfig` 不影响内存密封」当作缺陷，而实际上内存密封由**字段自身的** `ProtectedString.isProtected` 决定（读侧取 XML `Protected` 属性），库级配置只决定写出侧 `Protected` 标志（`KdbxXmlEntrySerializer.resolveProtectedFlag`，官方 `=` 语义） | ① 在 [MemoryProtectionConfig](../../core/src/main/java/com/keepasskey/core/model/MemoryProtectionConfig.kt) 的 KDoc **显式分离两条口径**（内存密封 = per-field；写出标志 = 库级覆盖标准五字段），并点名「Title / UserName / URL / Notes 在内存中未密封」属既定的有意口径；② **AC② 裁决：不采纳「库级开启即内存密封」**（官方不以本配置作内存密封开关；采纳会与官方客户端语义分叉；内存密封已有 per-field 契约）；③ 新增 `MemoryProtectionSemanticsTest`（3 例）锁定：**恶意库写 `<ProtectPassword>False</ProtectPassword>` 不得削弱口令的驻留密封，也不得让产物把口令降级为未保护**；内存密封与库级配置无关；标准字段 `Protected` 的官方归一化行为 | 审计 M1；`AGENTS.md` §3.3（官方实现为格式裁决者） |
| **ISSUE-P2-66** | P2 | 落盘清理为 **unlink-only**（`File.delete` / `deleteRecursively`），已 unlink 扇区在介质 TRIM 前可恢复；此前未在任何"已知工程限界"中登记 | 按第四轮复核后的收敛 AC 执行：**登记为已接受边界**并写入 `AGENTS.md` §6（说明威胁条件 = 需物理介质访问能力；不实施应用层覆写擦除的理由：对现代闪存无确定语义且显著拖慢锁定路径；该层收窄由平台 FBE 承担），并显式禁止据此断言「锁定即不可恢复」。**AC①/③ 不实施代码改动**（原「`clearAll()` 不清 `.tmp`」子断言已被第四轮证伪并撤销） | 审计 M4；第四轮复核更正 |

### 51.2 验收证据

```powershell
# ① 定向验证（三个新增 / 改动用例类）
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.autofill.*"
# → BUILD SUCCESSFUL；AutofillPrivacyDefaultsTest tests=6 skipped=0 failures=0 errors=0
.\gradlew.bat :database:testDebugUnitTest --tests "*MemoryProtectionSemanticsTest"
# → BUILD SUCCESSFUL；MemoryProtectionSemanticsTest tests=3 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 42s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1677 failures=0 errors=0 skipped=13
#   （app 902 / core 65 / crypto 127 / database 380 / sync 203）——较上批 +9（app +6 / database +3）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 1s；216 actionable tasks: 40 executed, 176 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,460,647 字节，2026-09-15 11:24:46）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增 / 改动用例（本批 +9 例 + 2 例既有断言反转）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `AutofillPrivacyDefaultsTest`（+6，新文件） | ① 两通道默认值在数据类与 UI 状态两处均为 false；② **单键读取不得回退为开启**（正则断言源码中不存在 `K_AUTOFILL_COPY_TOTP, true` / `K_INLINE_SUGGESTIONS_ENABLED, true` 形式的兜底——本批修复的真实陷阱）；③ **端到端**：默认配置 × 真实决策层 `AutofillTotpCopyPolicy.shouldCopy` 对"确有 TOTP 的条目"仍为 false（不触达剪贴板）；④ 显式开启后两通道仍可持久化并生效（防「默认关闭」退化为「功能被删」）；⑤ 构建器以开关为**前置**门控（`if (!isEnabled()) return null` 出现在读取 `inlineRequest` 之前）；⑥ 中英双语文案须分别点明「剪贴板」/「输入法」与「默认关闭」/「Off by default」 |
| `app` | `ExtendedSettingsStoreSingleKeyTest`（2 例**断言反转**） | 原为「内联建议默认开启」「填充后复制 TOTP 默认开启」——该文件本身的契约即「单键读取默认值必须与数据类字段默认值逐项一致」，故随语义变更同步反转为 `assertFalse`（这两条正是本批缺陷的"守护性误锁"，是全量重跑才暴露的，见 51.3.1） |
| `database` | `MemoryProtectionSemanticsTest`（+3，新文件） | ① **不可降级不变式**：文件写 `<ProtectPassword>False</ProtectPassword>` + 口令字段 `Protected="True"` → 解析后 `memoryProtection == MemoryProtectionConfig()`（读侧整对象重置）、字段 per-value 密封未被改写、重写产物仍写 `<ProtectPassword>True</ProtectPassword>` 且口令带 `Protected="True"`；② **两条口径独立**：`ProtectUserName` 无论文件写 True 还是缺失，`UserName` 字段的 per-value 密封恒保持（内存密封不由库级配置驱动）；③ **官方归一化如实声明**：标准字段 `Protected` 属性按库级配置（读侧恒为默认）无条件覆盖，故重写**不写回** `UserName` 的 per-value `Protected`——与既有锁「库级关闭时标准字段必须覆盖 per-value 不写 Protected」同源，属官方裁决而非缺陷 |

### 51.3 已知边界与口径（如实声明）

1. **本批最实质的发现：默认值翻转必须同时改「单键读取的硬编码兜底」**。
   `ExtendedSettingsStore.isAutofillCopyTotpEnabled()` / `isInlineSuggestionsEnabled()` 各自带
   `prefs?.getBoolean(K, true) ?: true`——这两个方法**不读**数据类默认值，且正是自动填充运行期
   （`AutofillConfirmActivity` / `AutofillInlinePresentationFactory`）真正调用的入口。
   若只改 `ExtendedSettings` / `SettingsUiState` 的字段默认值，则「无持久化层 / 键缺失」路径仍按
   **开启**判定——即「默认关闭」会被静默抵消，且**该失效不会在设置页显示上体现**（UI 显示关闭、
   行为却开启）。本批把该陷阱写成显式回归断言（含源码正则），防止后人只改一处。
   该陷阱的守护性误锁（`ExtendedSettingsStoreSingleKeyTest` 里两条断言旧的"默认开启"）在全量
   重跑时暴露，已随语义同步反转。
2. **`ISSUE-P2-64` 的 AC③ 前提经官方语义更正（如实声明）**：AC③ 原文为「打开 `ProtectUserName=True`
   的库并保存后**不得**被降级为未保护」。经核实，本仓对标准五字段的 `Protected` 属性按官方
   `KdbxFile.Write.cs:844-853` 的 `=`（无条件覆盖）语义处理，而库级配置读侧**恒定重置为默认值**
   （官方 `KdbxFile.Read.cs:246-248`），故 `UserName` 的 per-value `Protected` 在重写时**确实不会写回**
   ——这是**官方归一化行为**，且已被既有回归锁
   `KdbxEntrySerializerProtectedFlagTest.库级关闭时标准字段必须覆盖 per-value 不写 Protected`
   （含"请勿按直觉改回 OR"注释）明确固定；按 `AGENTS.md` §3.3（官方实现为格式裁决者），本批**不改**
   该行为。**真正需要防的回归**是本批新锁的口令面：库级默认 `protectPassword=true` 使口令**恒受保护**，
   恶意库无法通过文件内容关闭口令的驻留密封或把产物降级。AC③ 的其余可证部分已由该用例覆盖，
   未覆盖面（`UserName` 等非口令标准字段的 per-value 保留）属官方语义取舍，不单独立项。
3. **`ISSUE-P2-66` 的 AC 收敛**：本批只做文档登记（`AGENTS.md` §6），**无代码改动、无新增用例**——
   其 AC①（给 `clearAll()` 补 `deleteOrphanTmpFiles`）与 AC③（断言无 `.tmp` 残留）已在第四轮复核中
   因「`clearAll()` 遍历全部直接子项、`.tmp` 必被删除」被证伪而**撤销**，强行实施属冗余改动。
4. **未采用的替代方案（`ISSUE-P2-71` AC① 的另一分支）**：未把内联展示内容改为「不含用户名 / 标题的
   固定文案」——该方案会让内联建议退化为无信息量的占位卡片（特性价值归零），而"默认关闭 + 显式
   开启 + 文案披露"在保留特性可用性的同时消除了默认外泄；两者对"默认不外泄"的效力等价。
5. **两项默认值变更的用户可见影响**：升级后**未显式开启过**这两个开关的用户，行为变为
   「TOTP 不再自动入剪贴板」「键盘上方不再出现建议条」；自动填充本身（下拉 / 填充对话框路径）
   **完全不受影响**。已显式开启过的用户不受影响（持久化值为 `true`，单键读取命中持久化分支）。
   README 的「含 IME 内联建议」表述仍成立（特性存在，只是默认关闭），未改动。
6. **本批未触及的相邻项**：`autofillShowTotpNotification`（默认已关闭，无需整改）、
   `AutofillTotpCopyPolicy` 的决策逻辑本身（未改语义，仅其输入默认值变化）、
   以及 `ISSUE-P3-84`（剪贴板擦除可被用户关闭）——后者仍是独立的既有条目。
7. **`ACTIVE_ISSUES.md` 的 P2 计数**：本批按「表行 + 标题条目」双形式口径复核后由 **17 → 13**
   （表行 11 → 8，标题条目 6 → 5；`P2-43` 以标题条目形式承载），与 §49 第 13 条确立的复算纪律一致。

---

<a id="s52"></a>
## §52 同步解析落盘与内存池擦除边界批次（2026-09-15）：P2-67 / P3-119

> **本批次缘起**：认领 `ISSUE-P2-67`（审计 L9：同步下载路径绕过附件落盘），其 AC③ 明示须与
> `ISSUE-P3-119`（威胁建模 Q-10 / T-15 残余：内存附件池无擦除入口 + 三处同步路径不调
> `clearSensitiveData()`）**同批**——两项改的正是同一套「解密树生命周期与擦除语义」。

### 52.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-67** | P2 | 同步路径解析远端库时**未传 `binaryStore`**：`SyncDatabaseCodec.parseKdbxBytes` 直调 `KdbxFile.load(...)`（`binaryStore` 默认 `null`）→ 远端库里**超过落盘阈值的附件无论多大都内联进 `InnerHeader` 池**（解密态明文整批常驻），随后仅置空引用、从不零化 | ① 解析**收口到会话层**：新增 `DatabaseSession.parseExternalDatabase(bytes)`，内部用**本会话同一个** `binaryStore` 调 `KdbxFile.load`（凭据克隆 + `finally` 清零同既有契约；**不取会话互斥锁**——同步调用方已持 `SyncSessionState.mutex`，再加锁必自死锁）；② `SyncDatabaseCodec.parseKdbxBytes` 改为委托该 API，失败仍只落「异常类型」日志；③ **由构造关系保证「与主会话一致」**——`SyncCoordinator` 的 `SyncDatabaseCodec(databaseSession, debugLog)` 兜底构造路径也自动获得 store，**新增调用方无法再忘记传参**（这是相对「给 codec 加注入参数」的关键取舍：后者可被兜底构造绕过）；④ **AC② 断言**：新增 `ExternalDatabaseParseSpillTest`（1 例）用真实写入管线生成含 1.2 MB 附件的库，断言解析期 store 收到写入、解析树附件 `inlineBytes()` 为空且 `binarySource() != null`、读回字节逐字一致 | 审计 L9；`AGENTS.md` §6 附件落盘边界 |
| **ISSUE-P3-119** | P3 | ① 内存附件池（`InnerHeader.binaries`）无擦除入口 → ≤1 MiB 附件明文仅随引用丢弃、等待 GC；② 三处同步路径（`SyncConflictController` / `SyncContentChangeDetector` / `loadAndApplyRemoteBytes`）**从不调用** `clearSensitiveData()`，解析出的整棵解密树只靠 GC | ① **树外可达性逐点核实并写入文档**（`AGENTS.md` §6）：整树引用者只有 `SyncSessionState.lastSyncedDb`（锁库经 `clear()` 释放）与 `SyncConflictController` 的 `pendingLocalDb` / `pendingRemoteDb` / `pendingMergedRoot`（随 `clearPendingConflictSession()` 释放）；② **仅服务单次判定 / 一次性合并**的解析产物**显式擦除**：`SyncContentChangeDetector` 的缓存快照树（`finally` 擦除）、`SyncConflictController` 的三处丢弃点（远端解析失败 ⇒ 擦 localDb；不可信 base ⇒ 擦 parsedBase；合并序列化失败 ⇒ 同批擦三方树），并经 `wipeDiscarded()` 集中声明**使用前提**；③ **池内擦除按已接受边界登记**（含解除条件，见 52.3.2）；④ `loadAndApplyRemoteBytes` 明确**不**擦除——其产物被采用为会话库，所有权随之下移 | 威胁建模 Q-10 / T-15；`AGENTS.md` §3.2 / §6 |

### 52.2 验收证据

```powershell
# ① 定向验证（新增用例 + 受影响模块编译）
.\gradlew.bat :database:testDebugUnitTest --tests "*ExternalDatabaseParseSpillTest" :app:compileDebugKotlin
# → BUILD SUCCESSFUL；ExternalDatabaseParseSpillTest tests=1 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 10s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1678 failures=0 errors=0 skipped=13
#   （app 902 / core 65 / crypto 127 / database 381 / sync 203）——较上批 +1（database）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 1m 45s；216 actionable tasks: 27 executed, 189 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,460,647 字节，2026-09-15 11:39:32 —— 晚于本批最后一次源码修改 11:32:25，确为本批产物）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增 / 改动用例（本批 +1 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `database` | `ExternalDatabaseParseSpillTest`（+1，新文件） | **AC② 直接证据**：用生产写入管线（`KdbxFile.save`）生成含 1.2 MB 附件（> `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES` 的 1 MiB）的库 → 建立会话凭据 → 清空记录后调用 `session.parseExternalDatabase(bytes)` → 断言 ① 解析期 store **确实收到写入**（未落盘即失败，**非空跑**：旧实现不传 store，此断言必红）；② 解析树中附件 `inlineBytes()` 为空且 `binarySource() != null`（树内不留 1.2 MB 内联副本）；③ 落盘读回字节与原文逐字一致（落盘不损坏内容） |

### 52.3 已知边界与口径（如实声明）

1. **为什么把解析收口到会话层，而不是给 codec 加一个注入参数**：`SyncCoordinator` 保留了一条
   `?? SyncDatabaseCodec(databaseSession, debugLog)` 的**兜底构造**（测试 / 非 Hilt 装配）。
   若把 store 做成 codec 的构造参数，该兜底路径会以 `null` 传入，**缺陷在这些路径原样复现**；
   收口到 `DatabaseSession.parseExternalDatabase` 后，store 取自会话自身，
   **「与主会话一致」成为不可绕过的构造事实**。这是本批最关键的设计取舍。
2. **`ISSUE-P3-119` ② 的池内擦除为何按「已接受边界」登记而非实施**（含解除条件）：
   `KdbxDatabase.copy()`（合并路径**常态**发生，如 `localDb.copy(rootGroup = mergedRoot, …)`）
   会**共享同一 `binaries` 列表**，故「擦池」必须先把「谁拥有该数组」写成所有权规则
   （对齐 `R-CLEAR-2`），否则会误伤仍在存活树中引用的同一数组——这与本仓已有的
   `KdbxGroup.clearSensitiveData` / `clearSensitiveIdentitiesNotIn` 身份判据是同一类问题。
   解除条件：为 `InnerHeader.BinaryItem` 补「所有权 + 身份判据」后即可实施。
   现状（≤1 MiB 附件明文在池中等 GC）已如实写入 `AGENTS.md` §6，**不得**据此推断「锁定即已全部擦除」。
3. **`ISSUE-P3-119` ②' 的「三处」并非同质**（逐点核实结论）：
   - `SyncContentChangeDetector` 的缓存快照树：**纯只读比较后丢弃** → 已显式擦除 ✅；
   - `SyncConflictController`：**仅三处「确定丢弃」**可擦（远端解析失败 / base 不可信 / 合并序列化失败）；
     `pendingLocalDb` / `pendingRemoteDb` / `pendingMergedRoot` 会在用户决策阶段再次被读取，
     且成功路径下其节点**已被 `updateDatabaseMeta` 采用为会话库**（合并器复用原对象、非深拷贝）——
     **在这些位置擦除会静默清空活动库**，故明确不擦，改由会话生命周期收口（已写入 `AGENTS.md` §6）；
   - `loadAndApplyRemoteBytes`：产物即会话库 → **设计上不应擦除**（已在代码注释中注明原因）。
   即：AC②' 的「补 `clearSensitiveData()`」在**语义安全的位置全部已补**，另两处属「不可擦」而非「漏擦」。
4. **`parseExternalDatabase` 的并发语义**：不加会话互斥锁是**有意**（避免与
   `SyncCycleRunner.runSyncCycle` / `SyncCoordinator.resolveConflicts` 已持有的
   `SyncSessionState.mutex` 自死锁）。它只读凭据克隆、不改任何会话状态，
   因此与「会话正在被并发改写」不冲突；但**不得**据此把它当作可替换当前库的操作
   （替换当前库仍必须走 `openStream` / `updateDatabaseMeta` 等持锁路径）。
5. **本批未触及的相邻项**：`ISSUE-P3-118`（`ByteArrayOutputStream` 内部缓冲不擦除）、
   `ISSUE-P3-116`（彻底退出不清缓存）仍为独立开放条目，未在本批一并整改。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P2 由 **13 → 12**（表行 8 → 7，标题条目仍 5）、
   P3 由 **47 → 46**（表行 40 → 39，标题条目仍 7），均按「表行 + 标题条目」双形式口径复算。

---

<a id="s53"></a>
## §53 自动填充请求方归属与授权宽限收窄批次（2026-09-15）：P2-70 / P2-81

> **本批次缘起**：认领 `ISSUE-P2-70`（审计 E1：手动选择器把任意条目凭据交给请求方且不显示请求方身份）
> 与 `ISSUE-P2-81`（第四轮复核 `NEW-N5`：会话授权宽限的匹配域过宽）。两项同属**自动填充放行面的
> 「用户不知情」问题**：前者是「不知道谁在要」，后者是「不知道为何不再问」。

### 53.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-70** | P2 | **手动选择器不显示请求方身份**：自动匹配路径有严格边界（域 / 包名匹配 + 指纹白名单 / DAL 校验），而手动兜底选择器可把**任意条目**凭据交给请求方，页面上却只有「选择要填充的凭据」——用户在零归属信息下完成一次填充授权 | ① 新增展示模型 [AutofillPickerRequester](../../app/src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt)（包名 / 应用名 / 签名证书 SHA-256 / 表单自报域）+ 纯函数构造 `buildAutofillPickerRequester`（包名缺失返回 null，**不伪造「未知应用」占位**）；② 选择器页在**搜索框之前**（无需滚动即可见）渲染归属块，行序即可信度序：包名（系统背书）→ 应用名（**可被应用自声明**，文案明示「仅作辅助识别」）→ 签名证书（不可读时如实标注）→ 表单自报域；③ Activity 侧解析：包名取 extra、应用名经 `PackageManager`（失败降级为「无名称」）、证书经 `AutofillOriginResolver.callingAppCertSha256Hex`（**与确认页同一读取通道**）；④ 域文案**与确认页区分**——选择器拿到的是表单自报且**未经归属校验**的域，故新文案明示「未通过归属校验」，**不**沿用确认页的「已经归属校验」 | 审计 E1；`AGENTS.md` §3.2 |
| **ISSUE-P2-81** | P2 | **授权宽限的匹配域过宽**：`normalized()` 把空 / 不可归属域归一为 `null`，而 `AutofillGrantContext` 用整体 `==` 比较 → `null == null` 成立 ⇒ 开关开启后 30 秒 TTL 内，同包名的一切「域不可归属」表单（**含攻击者伪造的不可归属域**）免二次确认 | ① **收窄到「域必须可归属」**（在授权存储单点实现，写入与匹配两侧同时收窄）：域归一后为 `null` 时 **`grant()` 不建立授权**、**`isGranted()` 一律返回 false**；② 设置页文案（中 / 英）如实披露代价：宽限**仅对能确定域名的表单生效**，无法归属域名的表单每次仍需确认；③ 新增 2 例断言：伪造 / 空白 / 仅 scheme 的不可归属域**均不得命中**；无域确认既不新增授权也不能替无域请求冒领既有域授权 | 第四轮复核 `NEW-N5`；`AGENTS.md` §3.2 |

### 53.2 验收证据

```powershell
# ① 定向验证（自动填充包全部用例）
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.autofill.*"
# → BUILD SUCCESSFUL；AutofillPickerRequesterDisplayTest tests=7 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 33s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1687 failures=0 errors=0 skipped=13
#   （app 911 / core 65 / crypto 127 / database 381 / sync 203）——较上批 +9（app）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 1m 51s；216 actionable tasks: 28 executed, 188 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,462,983 字节，2026-09-15 11:57:46）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增 / 改动用例（本批 +9 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `AutofillPickerRequesterDisplayTest`（+7，新文件） | ① 展示模型：包名缺失 / 空白 → **不构造归属**（不伪造占位锚点）；空白应用名 / 证书 / 域按「无」处理；四项齐备时逐项保留；② **渲染完整性**：选择器页必须渲染包名、应用名、证书（含不可读分支）、域（含无域分支）六处字符串资源；③ **位置证据**：归属块在源码中的位置必须**早于**搜索框（否则小屏上「强制展示」不可见）；④ **语义诚实性**：选择器页**不得**引用确认页的「已经归属校验」文案，且中英文域文案须分别含「未通过归属校验」/`not ownership-verified`、应用名文案须含「可自声明」；⑤ **接线证据**：Activity 必须调用 `resolveRequester()` 并传入界面，且证书读取必须走 `AutofillOriginResolver`（与确认页同一通道），应用名失败必须如实降级 |
| `app` | `AutofillSessionGrantStoreTest`（+2，扩展既有文件） | ① `域不可归属时不建立授权也不命中`：`null` / 空白 / 仅 scheme（`https://`，无主机）三种形态写入后**均不得命中**（旧实现下三者都会命中，故本用例是**非空跑**的边界锁）；② `无域确认不建立授权且不得冒领可归属域授权`：无域确认既不新增授权，也不能替无域请求冒领既有域授权，且既有域授权不受影响（避免无谓牺牲已获得的宽限） |

### 53.3 已知边界与口径（如实声明）

1. **应用名（label）不是归属锚点**：`appLabel` 可被应用自声明，展示文案已明示「仅作辅助识别」；
   真正的不可伪造锚点是包名（系统结构树提供）与签名证书 SHA-256（本应用经 `PackageManager` 读取）。
   读取失败（包可见性受限 / 已卸载）时按「无名称」处理并记调试日志（仅异常类名），**绝不伪造名称**。
2. **选择器域的语义与确认页不同（故不复用文案）**：确认页的域来自 `resolveUsableWebDomain`
   （受信浏览器白名单 / DAL 归属声明**校验通过**）；而选择器的 `EXTRA_WEB_DOMAIN` 是
   `scanResult.webDomain`（**表单自报**，仅用于字段签名屏蔽）。两者可信度不同，故本批新增
   「表单自报域名：…（未通过归属校验）」文案并加**静态断言**禁止混用确认页措辞。
3. **不展示「首次出现 / 未授权」状态**：确认页有信任存储支撑的显式授权流程（`ISSUE-P1-24`），
   选择器是**兜底入口**且同样携带 `setAuthentication` 二次确认链；本批只补齐「谁在请求」这一
   最低必要信息，不引入第二套授权状态机（避免两处状态各自漂移）。
4. **`P2-70` 的 AC③（与 `ISSUE-P3-93` 同批）未采纳——如实声明**：`P3-93` 要把调用方证书从
   「取 `apkContentsSigners.firstOrNull()` 的**单摘要**」改为「遍历全部签名者（含
   `signingCertificateHistory`）的**摘要集合**」。这不是局部替换：现有比较面（浏览器指纹白名单
   `BrowserSigningFingerprints`、DAL 校验、以及 `AutofillCallerTrustStore` 以「包名 + 证书」
   为键的信任记录）都建立在单摘要之上，「任一匹配即通过」会同时改变**信任记录的键语义**
   （签名轮换期同一条目可能对应多个摘要）。故该条按独立批次实施并在 `ACTIVE_ISSUES.md` 保留
   （其 AC 已注明是 `ISSUE-P2-46` 的前置条件），本批不夹带。
5. **`P2-81` 的行为变更（fail-closed 方向）**：开关开启时，**无法归属域名的表单**（原生应用内表单、
   内嵌 WebView 未上报域、域被上游判为不可用等）恢复为「每次都确认」——这是本项的有意代价，
   已在设置页文案如实说明；对可归属域（如浏览器站点表单）30 秒宽限**完全不变**。
6. **`P2-81` 的残余面（不掩盖）**：TTL（30 秒）内、同一「包名 + 域」的重复请求仍免二次确认——
   这是产品裁决的宽限设计（`ISSUE-P3-42`），本批只消除「域不可归属也享受宽限」这一过宽情形；
   口令字段的宽限豁免（`ISSUE-P1-24` AC③：携带口令值的数据集恒需确认）不受影响。
7. **计数**：本批 `ACTIVE_ISSUES.md` 的 P2 由 **12 → 10**（表行 7 → 5，标题条目仍 5），
   按「表行 + 标题条目」双形式口径复算。

---

<a id="s54"></a>
## §54 包可见性与序列化缓冲擦除批次（2026-09-15）：P2-74 / P3-118

> **本批次缘起**：认领 `ISSUE-P2-74`（审计 E6：清单缺 `<queries>` 致 web 域归属判定整体失效）
> 与 `ISSUE-P3-118`（威胁建模 T-16 残余：整库序列化缓冲只擦「返回数组」）。
> 两项分别落在清单（调用方可见性）与 `database`（序列化缓冲生命周期），JVM 侧可完整验证。

### 54.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P2-74** | P2 | 清单**未声明 `<queries>`**（核实：0 命中），而 `AutofillOriginResolver.callingAppCertSha256Hex` 用 `getPackageInfo(..., GET_SIGNING_CERTIFICATES)` 读取**任意调用方**的签名证书 → Android 11+ 包可见性下抛 `NameNotFoundException` → 指纹恒 null → `BrowserSigningFingerprints.isTrusted`（要求非空指纹）**与** DAL 校验（`:41-42`）**双双恒 false** → web 域候选整体失效（fail-closed 无泄露，但 minSdk 36 ⇒ **全部支持设备**功能不可用） | ① 清单补**最小 `<queries>`**：`https` VIEW + `BROWSABLE` intent（按官方推荐的 intent 签名方式使全部浏览器可见）+ 与 `BrowserSigningFingerprints.TRUSTED` **逐一对应**的显式 `<package>`（`com.android.chrome` / `org.mozilla.firefox` / `org.mozilla.firefox_beta`）；② **不使用** `QUERY_ALL_PACKAGES`（Play 需审核 + 违反最小必要）；③ 新增 `PackageVisibilityQueriesWiringTest`（3 例）把「intent 声明齐备 / 白名单包名逐一在册 / 无 QUERY_ALL_PACKAGES」固化，并**剔除 XML 注释后再断言**（本项说明本身写在清单注释里，否则注释会把"未声明"误判为"已声明"） | 审计 E6；官方《软件包可见性过滤》（`getPackageInfo` 受过滤、非自动可见包须声明） |
| **ISSUE-P3-118** | P3 | **整库序列化缓冲只擦返回值**：`save()` / `exportToBytes()` / `changeCredentials()` 均以 `ByteArrayOutputStream().also { … }.toByteArray()` 产出字节，之后只对**返回的数组** `fill(0)`——产生它的内部缓冲（**第二份完整整库密文**）在 GC 前从不擦除；`reset()` 只置计数不清内容，JDK 亦无清零 API | ① 新增 [WipableByteArrayOutputStream](../../database/src/main/java/com/keepasskey/database/io/WipableByteArrayOutputStream.kt)（子类化以访问父类 `protected buf`，`wipe()` 逐字节清零 + 复位，幂等）；② **三条路径全部改为具名缓冲 + `finally { buffer.wipe() }`**（含 AC 未点名的**同型第三处** `changeCredentials`）；③ 新增 `WipableByteArrayOutputStreamTest`（3 例）：以 `@VisibleForTesting` 探针断言 `wipe()` **逐字节清零**（而非仅计数复位——那正是本缺陷的定义）、幂等与擦后可复用、并静态守卫三条路径均具名且无匿名链式残留 | 威胁建模 T-16 残余；`AGENTS.md` §3.2 敏感数据铁律 |

### 54.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.PackageVisibilityQueriesWiringTest"
# → BUILD SUCCESSFUL；tests=3 skipped=0 failures=0 errors=0
.\gradlew.bat :database:testDebugUnitTest --tests "*WipableByteArrayOutputStreamTest"
# → BUILD SUCCESSFUL；tests=3 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 16s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1693 failures=0 errors=0 skipped=13
#   （app 914 / core 65 / crypto 127 / database 384 / sync 203）——较上批 +6（app +3 / database +3）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 18s；216 actionable tasks: 33 executed, 183 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,191 字节，2026-09-15 12:16:13）
```

**新增用例（本批 +6 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `PackageVisibilityQueriesWiringTest`（+3，新文件） | ① 清单必须有 `<queries>` 且含 `VIEW` + `BROWSABLE` + `https` scheme；② **跨文件一致性**：直接遍历 `BrowserSigningFingerprints.TRUSTED.keys`，每个受信浏览器包名都必须在清单中以 `<package>` 声明（白名单新增浏览器而清单漏同步即失败——这正是本缺陷的复发形态）；③ 不得声明 `QUERY_ALL_PACKAGES`。断言前**剔除 XML 注释**（否则整改说明自身含有的关键字会造成假通过） |
| `database` | `WipableByteArrayOutputStreamTest`（+3，新文件） | ① `wipe()` 必须**逐字节清零**内部缓冲（以 `@VisibleForTesting` 探针读父类 `buf` 断言——「计数复位但字节仍在」正是本项缺陷，仅断言 `size()==0` 会漏判）；② `wipe()` 幂等且擦除后仍可继续写入（避免实现退化为「一次性销毁」）；③ **接线守卫**：`DatabaseSession` 三条序列化路径均须使用具名可擦缓冲、每处均有 `buffer.wipe()`，且不得残留 `ByteArrayOutputStream().also { … }` 匿名链式写法 |

### 54.3 已知边界与口径（如实声明）

1. **`ISSUE-P2-74` 的 AC①（设备侧复现）与 AC③（真机验证）本批未执行——如实声明，待设备补验**：
   本批以**官方文档**为据（《软件包可见性过滤》明列 `getPackageInfo()` 受可见性过滤；未检索到
   autofill 服务的可见性豁免），且修复为**纯增量声明**（只增加可见性，不改变任何匹配/放行逻辑：
   浏览器委派仍必须通过 `BrowserSigningFingerprints` 的**指纹白名单**，DAL 仍必须声明匹配），
   故不存在「修不好反倒放松」的方向性风险。**但**「真机上浏览器域自动填充是否恢复」仍属
   `AGENTS.md` §6 所指的「涉及平台 API 的静态逻辑不能仅凭宿主单测判定」范畴，故：
   该条目的设备侧验证（构造浏览器调用 + 观察候选是否下发）仍列为**待设备项**，不得据本批声明其已可用。
2. **非浏览器调用方仍不可见（无法枚举）**：任意应用的包名不可预测，逐个 `<package>` 声明不可行，
   而 `QUERY_ALL_PACKAGES` 在 Play 上需审核且违反最小必要。故其证书指纹读取会继续失败
   → 相关放行路径保持 fail-closed（`webDomain` 归属判 `REJECTED`、确认页与选择器如实标注
   「不可读」而**不伪造摘要**）。这也是 `ISSUE-P2-46`（`android://` 签名绑定）的前置约束之一。
3. **`https` VIEW intent 的可见性范围**：该声明使「能处理 https URL 的应用」（即全部浏览器）可见。
   这是官方推荐的「按 intent 签名声明」方式，属**用例驱动**的最小扩展（本应用的浏览器委派判定
   必须先确认调用方是浏览器）；它不会让应用获取已安装应用清单（未使用 `getInstalledApplications()`
   等全量查询 API）。
4. **白名单与清单的一致性由单测强制**：`BrowserSigningFingerprints.TRUSTED` 是唯一权威白名单，
   清单里的 `<package>` 只是「可见性」的镜像。二者漂移会导致「白名单已加但指纹读不到」的静默失效，
   故用例直接以生产常量为准遍历断言（而非在测试里重复硬编码包名）。
5. **`ISSUE-P3-118` 覆盖三处（含 AC 未点名的同型第三处）**：AC 只点名 `save()` 与 `exportToBytes()`，
   实际 `changeCredentials()`（换密路径）为**同一缺陷类的第三处**，本批一并整改——
   该类缺陷的判据是「匿名 `ByteArrayOutputStream().also{…}.toByteArray()` 链式写法」，
   故静态守卫直接针对该写法。
6. **`wipe()` 的语义边界**：`wipe()` 只清零**本实例**的内部缓冲。返回给调用方的字节数组
   仍按既有契约由调用方清零（`save()` 在写盘后 `serialized.fill(0)`；`exportToBytes()` 的字节
   归调用方所有）。本批**未**改变该所有权契约。
7. **`@VisibleForTesting` 探针的必要性**：`buf` 是 JDK `ByteArrayOutputStream` 的 `protected` 字段、
   本类为 Kotlin final（默认不可继承），JDK 又无读取内部缓冲的公开 API——若不提供探针，
   测试只能断言「计数已复位」，而本项缺陷的定义恰恰是「**计数复位、字节仍在**」。
   按仓库既有惯例（`DatabaseSession.setDatabaseForTesting` / `WebDavSyncProvider` 等）标注
   `@VisibleForTesting` 并收敛为 `internal`。
8. **本批未触及的相邻项**：`ISSUE-P3-93`（多签名者遍历，独立批次，见 §53.3.4）、
   `ISSUE-P3-94`（合并清单冗余 / 废弃权限 / `tools:node="remove"`）仍为开放条目；
   本批只动 `<queries>`，未触碰权限与 Activity 声明（避免与 P3-94 冲突）。
9. **计数**：本批 `ACTIVE_ISSUES.md` 的 P2 由 **10 → 9**（表行 5 → 4，标题条目仍 5）、
   P3 由 **46 → 45**（表行 39 → 38，标题条目仍 7），均按「表行 + 标题条目」双形式口径复算。

---

<a id="s55"></a>
## §55 多签名者匹配批次（2026-09-15）：ISSUE-P3-93

> **本批次缘起**：认领 `ISSUE-P3-93`（审计 F-19）——调用方证书各处只取
> `signingInfo.apkContentsSigners?.firstOrNull()` 的**单个**摘要。该条在第四轮定版中被明确为
> `ISSUE-P2-46`（`android://` 签名绑定）的**前置条件**，故先于 `P2-46` 落地。

### 55.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-93** | P3 | **签名轮换期的多签名者只取首个**：`apkContentsSigners?.firstOrNull()`（`AutofillOriginResolver`、`CallingOriginResolver` 两处生产者，共 3 个取值点）→ 签名轮换期应用同时持有当前签名者与**历史**签名者（`signingCertificateHistory`），取首个的结果**随系统返回顺序变化** → 浏览器指纹白名单 / DAL 校验 / 信任记录都可能把合法调用方误判为未授权（方向 **fail-closed**，属可用性缺陷而非放行放松） | ① 新增值类型 [CallerCertDigests](../../app/src/main/java/com/keepasskey/app/security/CallerCertDigests.kt)（归一化：trim/大写/去空/去重保序；`primary` 仅供展示与信任记录写入；`anyMatch` 供放行判定）；② **两处生产者**改为遍历 `apkContentsSigners` + `signingCertificateHistory`：`AutofillOriginResolver.callingAppCertDigests` 与 `CallingOriginResolver.certDigests`（原 `…Sha256Hex` 收敛为「主摘要」并委托）；③ **三处放行判定**改为「任一摘要命中即通过」：`BrowserSigningFingerprints.isTrusted`、`AutofillWebDomainPolicy.attribute`、`DalStatementMatcher.match`（DAL 缓存键同步改为包含**完整摘要集**，避免不同集合互相命中）；④ `AutofillCallerTrustStore.isTrusted` 任一摘要命中即视为已授权（写入仍用主摘要）；⑤ **apk-key-hash origin** 新增多签名者访问器 `apkKeyHashOrigins`，单值入口收敛为「有序集合首项」；⑥ 单摘要入口全部保留并委托集合入口（既有调用点与测试零改动）；⑦ 新增 `MultiSignerCertMatchingTest`（8 例） | 审计 F-19；官方「应用可由多签名者之一签署」模型；`AGENTS.md` §3.2 |

### 55.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.MultiSignerCertMatchingTest"
# → BUILD SUCCESSFUL；tests=8 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 1m 48s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1701 failures=0 errors=0 skipped=13
#   （app 922 / core 65 / crypto 127 / database 384 / sync 203）——较上批 +8（app）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 1s；216 actionable tasks: 17 executed, 199 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,191 字节，2026-09-15 12:32:48）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +8 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `MultiSignerCertMatchingTest`（+8，新文件） | ① 摘要集合归一化（trim / 大写 / 去空 / 去重 / 保序 / `primary` / 空集合不匹配任何谓词）；② 受信浏览器白名单：**轮换期历史签名者命中即通过**（旧实现只看首个 → 误判未授权）、两者都不在白名单 / 未取证包名 / 空集合一律拒绝、单摘要入口等价（含小写）；③ webDomain 归属裁决按集合进入浏览器分支，空集合 + DAL 未验证 → REJECTED，非法域即使命中亦 REJECTED；④ DAL 声明匹配：任一摘要命中即 VERIFIED（含冒号 / 小写归一化）、无命中 / 空集合 → NOT_VERIFIED；⑤ 信任记录：`trust(主摘要)` 后调用方以 `[新签名者, 历史签名者]` 仍视为已授权，无关签名者 / 重打包 → 未授权；⑥ **降级分支保持**：摘要全不可读时回退「仅按包名」记录，且摘要可读时**不**回退到该降级键（否则同包名换签名会继承信任）；⑦ **静态守卫**：两处生产者必须同时遍历 `apkContentsSigners` 与 `signingCertificateHistory`，且代码中不得残留 `apkContentsSigners?.firstOrNull()`；`CallingOriginResolver` 必须提供多签名者 origin 集合访问器 |

### 55.3 已知边界与口径（如实声明）

1. **缺陷方向与定级**：本项为**可用性**缺陷（fail-closed 方向）——取错摘要只会导致「本该通过的
   调用方被拒」，不会导致「本不该通过的调用方被放行」。故定级 P3 而非 P2；其价值在于
   消除签名轮换期的误拒，并为 `ISSUE-P2-46`（`android://` 签名绑定）提供前置能力。
2. **「主摘要」与「摘要集合」的分工（有意设计）**：
   - **展示**（确认页 / 选择器）与**信任记录写入**用 `primary`——用户授权的是「当时这个应用」，
     写入当前有效签名者的摘要语义清晰；
   - **放行判定**一律用集合（`anyMatch`）——历史签名者命中即视为同一应用的合法轮换。
   两者混用会造成「记录用历史摘要」这类难以解释的状态，故在类型层面分开（`primary` vs `anyMatch`）。
3. **信任记录的降级分支**未改动：摘要**全部不可读**时仍回退「仅按包名」键（`pkg|`），
   这是 `ISSUE-P1-24` 的既有取舍（确认页会如实标注「不可读」）；摘要可读时**不**回退到该键，
   与整改前的判定面逐字一致（避免悄悄扩大信任面）。
4. **apk-key-hash origin 的结构约束**：`resolveTrustedOrigin` 的返回类型是**单个字符串**
   （下行消费方与 `clientDataJSON` 需要单一 origin），故单值入口保留；本批新增
   `apkKeyHashOrigins(callingAppInfo)` 暴露**全部签名者**的 origin 集合，并在 KDoc 指明
   「按 origin 匹配的路径应遍历该集合」。**残余**：现有下行消费方仍只使用主 origin—
   对多签名者应用，若其历史签名者对应的 origin 需要参与匹配，须由后续批次把集合接入匹配路径
   （当前记录以 `android://<包名>` 绑定为主，该路径不依赖 origin 集合，故影响面有限）。
5. **单摘要入口全部保留并委托集合入口**：`BrowserSigningFingerprints.isTrusted(pkg, String?)`、
   `AutofillWebDomainPolicy.attribute(…, String?, …)`、`DalStatementMatcher.match(…, String)`、
   `DalVerification.verify(…, String)` 均保留——既有调用点与既有测试**零改动**，
   且语义为「只含该摘要的集合」。生产放行路径已全部切换到集合入口。
6. **DAL 缓存键变更**：由 `host|pkg|单摘要` 改为 `host|pkg|逗号连接的完整摘要集`——
   不同摘要集合的判定结果不再互相命中（否则多签名者与单签名者会读到彼此的缓存结论）。
7. **静态守卫的注释剔除（过程留痕）**：本轮首跑时静态守卫**误报**——整改说明自身在 KDoc 里
   引用了缺陷写法 `apkContentsSigners?.firstOrNull()`，被正则命中。已改为**先剔除注释再断言**
   （与 §54 的清单注释同类问题），并把该写法固化为纪律：凡断言「不得出现某写法」，
   必须先剔除注释，否则文档说明会造成假失败。
8. **本批未触及的相邻项**：`ISSUE-P2-46`（`android://` 签名绑定）本批只补齐其**前置能力**
   （多签名者摘要集合 + 信任记录集合判定），其本体（首次绑定 + 未安装包名降级策略）
   涉及产品口径与设备侧验证，仍为开放条目；`ISSUE-P3-94`（合并清单冗余权限）亦未触及。
9. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **45 → 44**（表行 38 → 37，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

<a id="s56"></a>
## §56 清单权限口径与确认回传门控批次（2026-09-15）：P3-94 / P3-95

> **本批次缘起**：认领 `ISSUE-P3-94`（审计 F-20：合并清单冗余 / 废弃权限 / 无 `tools:node="remove"`）
> 与 `ISSUE-P3-95`（审计 F-21：自动填充确认返回 `RESULT_OK` 前不校验会话锁定）。

### 56.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **P3-94** | P3 | 源清单声明 `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC`（审计称与库注入重复），且 `USE_FINGERPRINT`（API 28 起弃用，由 `androidx.biometric` 旧兼容路径注入）未被移除、清单亦无 `tools` 命名空间 | 按 **AC 逐条裁决**（依据写入清单注释）：① **AC①「删除冗余声明」未采纳**——二者是本模块**自身**运行期需求，改依赖第三方库传递声明属脆弱耦合，且 release 合并清单证据显示**各权限只出现一次**（合并器已去重），「冗余」仅在源文件层面；② **AC② 采纳**：`USE_FINGERPRINT` 加 `tools:node="remove"`（minSdk 36 ⇒ 旧 FingerprintManager 路径永不执行），并补 `xmlns:tools`；③ **AC③ 保留** `CAMERA`（zxing 扫码） | 审计 F-20；**合并清单实测证据**见 56.2 |
| **P3-95** | P3 | 自动填充确认页在生物识别 / 手动确认成功后**无条件** `setResult(RESULT_OK)`，**不**校验会话是否已锁定（凭据管理器各路径均有该判定）→ 用户确认期间库被自动/手动锁定后，框架仍会把**已解密的数据集值写入目标表单**，形成「库已锁定但仍完成一次填充」的语义漏洞 | ① 新增纯策略 [AutofillAuthenticationPolicy.canDeliverAuthResult(vaultLocked)](../../app/src/main/java/com/keepasskey/app/autofill/AutofillSessionGrantStore.kt)（锁定即不允许回传）；② `AutofillConfirmActivity.completeAuthResult()` 在**进入时**与**回传前**各校验一次（后者覆盖 TOTP 二次动作与超时等待期间发生的锁定），锁定即走 `discardPendingResult()`（显式 `RESULT_CANCELED`）且**不**写「上次填充条目」记忆、**不**记录会话授权宽限；③ 新增 `AutofillConfirmDeliveryLockTest`（4 例） | 审计 F-21；与 CM 各路径对齐 |

### 56.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.autofill.AutofillConfirmDeliveryLockTest"
# → BUILD SUCCESSFUL；tests=4 skipped=0 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.security.ManifestPermissionHygieneTest"
# → BUILD SUCCESSFUL；tests=4 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 6s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1709 failures=0 errors=0 skipped=13
#   （app 930 / core 65 / crypto 127 / database 384 / sync 203）——较上批 +8（app）
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 9s；216 actionable tasks: 23 executed, 193 up-to-date

# ③ P3-94 的**合并产物级**证据（release 合并清单；整改前 USE_FINGERPRINT 位于 :57）
Select-String -Path "app\build\intermediates\merged_manifests\release\processReleaseManifest\AndroidManifest.xml" `
  -Pattern "android.permission.(USE_FINGERPRINT|CAMERA|USE_BIOMETRIC|ACCESS_NETWORK_STATE)"
# → :12 ACCESS_NETWORK_STATE（本模块声明，单条）
# → :13 USE_BIOMETRIC（本模块声明，单条）
# → :65 CAMERA（zxing 注入，按 AC③ 保留）
# → **USE_FINGERPRINT 已不出现在合并产物中**（AC② 达成）

# ④ 产物与签名
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,159 字节，2026-09-15 12:45:23）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +8 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `app` | `AutofillConfirmDeliveryLockTest`（+4，新文件） | ① 策略：锁定 → 不允许回传、未锁定 → 允许；② **接线守卫**：每一处 `setResult(RESULT_OK)` 之前 400 字符窗口内必须出现 `canDeliverAuthResult(`（防「无条件回传」复发）；③ 必须有显式的 `setResult(RESULT_CANCELED)` 丢弃路径与集中收口 `discardPendingResult()`；④ 门控必须覆盖**进入与回传两个时点**（≥2 处），且「上次填充条目」记忆的写入位置必须晚于首次门控（防锁定后误记录） |
| `app` | `ManifestPermissionHygieneTest`（+4，新文件） | ① 必须声明 `xmlns:tools`；② `USE_FINGERPRINT` 必须以 `tools:node="remove"` 移除；③ `CAMERA` **不得**被移除；④ 本模块自身运行期依赖的 5 项权限必须保留声明且不得被 `remove`（AC① 未采纳的回归守卫）。断言前剔除 XML 注释（整改说明自身含权限名与 `tools:node` 字样） |

### 56.3 已知边界与口径（如实声明）

1. **P3-94 的 AC① 未采纳，依据是「实测 + 耦合风险」双证据**：审计称「合并清单冗余」，
   但 release 合并清单显示 `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC` **各只出现一次**
   （manifest merger 已按 `uses-permission` 去重）——即**产物中并无冗余**；若删除本模块声明，
   本应用的运行期权限将依赖第三方库清单的传递声明，库一旦调整即**静默失去权限**并在运行期抛
   `SecurityException`（且这类失败无法由任何单测捕获）。故保留声明并写入回归守卫。
2. **P3-94 的 AC② 只在合并产物级验证**：单测锁「源清单写法」，真正的行为证据是**合并清单中
   `USE_FINGERPRINT` 消失**（见 56.2 ③）。该权限在 minSdk 36 下无执行路径，
   移除不影响生物识别（`USE_BIOMETRIC` 保留且为 androidx.biometric 的现代路径所需）。
3. **P3-95 的双时点门控是有意为之**：只在「进入时」校验会漏掉「TOTP 二次动作 + 超时等待」期间
   发生的自动锁定（该窗口可达秒级）；只在「回传前」校验则会漏掉锁定后仍写入
   「上次填充条目」记忆与会话授权宽限的副作用。两处均以同一纯策略判定，语义唯一。
4. **`RESULT_CANCELED` 与 `finish()` 的组合语义**：框架只在收到 `RESULT_OK` 时把数据集值写入目标
   表单；显式 `RESULT_CANCELED` 是「丢弃未决响应」的明确表达（避免依赖默认值语义）。
5. **本批未触及的相邻项**：`ISSUE-P3-96`（CBC 加密流明文中转副本清零）、
   `ISSUE-P3-97`（CI 原生用例真实执行）、`ISSUE-P3-98`（`AppLog` proguard 规则 no-op）等仍为开放条目。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **44 → 42**（表行 37 → 35，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

<a id="s57"></a>
## §57 清零与规则一致性批次（2026-09-15）：P3-96 / P3-98 / P3-99

> **本批次缘起**：认领三条「单点改动 + 静态/行为断言」类存量项——`ISSUE-P3-96`（CBC 加密流明文
> 中转副本未清零）、`ISSUE-P3-98`（`AppLog` 剥离规则签名与声明形态不符，规则为 no-op）、
> `ISSUE-P3-99`（换密路径密钥文件快照副本无处可擦）。

### 57.1 交付清单

| 编号 | 级别 | 缺陷（一句话） | 关键改动 | 依据 |
|---|:--:|---|---|---|
| **ISSUE-P3-96** | P3 | `CbcEncryptingOutputStream` 两处**明文中转副本**未清零：`emitAlignedBlocks()` 的 `buffer.copyOf(aligned)`（交给变换的 `chunk`）与 `close()` 的 `buffer.copyOf(filled)`（补齐前残余），与类 KDoc「明文缓冲在 close 时显式归零」的宣称不符 | ① `chunk` 改为 `try { sink.write(transform(key, chain, chunk)) } finally { Arrays.fill(chunk, 0) }`（**先写后擦**，避免擦掉即将写出的内容）；② `close()` 的残余副本改为**具名** `plainSource`，在 `Pkcs7.pad(...)` 返回后**立即**清零（该副本唯一用途就是 pad 入参——`Pkcs7.pad` 内部会再复制一份并返回新数组） | 审计 RUST-07；`AGENTS.md` §3.2 |
| **ISSUE-P3-98** | P3 | `proguard-rules.pro` 把 `AppLog` 剥离规则写作 `public static void v/d(...)`，而 `AppLog` 是 Kotlin `object`、其 `v/d` 为**实例方法**（`public final void v(String, String)`，未标 `@JvmStatic`）→ 规则**永不匹配**（no-op），「双重剥离」实际只有运行期 `debugEnabled` 一重 | 规则改为实例方法签名（`public void v(...); public void d(...);`），并在规则文件与用例中**交叉锁定**「规则签名 ⇔ 声明形态」：任一侧改形态而另一侧未同步即失败；同时用例断言 `e/w/i` **不得**被剥离（release 故障时不得失声） | 审计 L1 |
| **ISSUE-P3-99** | P3 | `changeCredentials` 的密钥文件快照以**默认参数表达式**（`= credentials.keyFileSnapshot()`）注入调用栈——`keyFileSnapshot()` 返回**克隆副本**，而 `rotateCredentials` 内部再克隆写入缓存、`KdbxFile.save` 只读取，故该副本归方法所有却**无处可擦**（换密后随局部变量出栈静默留存至 GC） | ① 拆为**显式重载**：单参 `changeCredentials(newPasswordChars)` 自持快照并在 `finally` 中清零；② 双参重载**移除默认值**（默认值形态正是「副本无处可擦」的成因），并在 KDoc 声明「`newKeyFileData` 归**调用方**所有，本方法不擦除」（`rotateCredentials` 只克隆读取）；③ 清零位置**晚于**写盘与可能回滚（写前擦会静默写出「用全零密钥文件加密」的库） | 审计 L2 + 第四轮机制更正 |

### 57.2 验收证据

```powershell
# ① 定向验证
.\gradlew.bat :crypto:testDebugUnitTest --tests "*CbcEncryptPlaintextWipeTest"
# → BUILD SUCCESSFUL；tests=2 skipped=0 failures=0 errors=0
.\gradlew.bat :database:testDebugUnitTest --tests "*ChangeCredentialsKeyFileWipeTest"
# → BUILD SUCCESSFUL；tests=2 skipped=0 failures=0 errors=0
.\gradlew.bat :app:testDebugUnitTest --tests "com.keepasskey.app.log.AppLogProguardRuleTest"
# → BUILD SUCCESSFUL；tests=3 skipped=0 failures=0 errors=0

# ② 全量单测（强制真实执行）
.\gradlew.bat test --rerun-tasks --max-workers=1
# → BUILD SUCCESSFUL in 2m 1s；114 actionable tasks: 114 executed
#   结果汇总（build/test-results/**/TEST-*.xml）：
#   tests=1716 failures=0 errors=0 skipped=13
#   （app 933 / core 65 / crypto 129 / database 386 / sync 203）——较上批 +7
cd crypto/src/main/rust; cargo test
# → test result: ok. 57 passed; 0 failed; 0 ignored（本批未触碰原生内核）
.\gradlew.bat assembleRelease
# → BUILD SUCCESSFUL in 2m 47s；216 actionable tasks: 38 executed, 178 up-to-date
#   产物：D:\GithubWorkplace\KeePasskey\app\build\outputs\apk\release\app-release.apk
#         （15,463,159 字节，2026-09-15 13:44:10）
$env:ANDROID_HOME\build-tools\<ver>\apksigner.bat verify --print-certs <产物>
# → V3.0 Signer: certificate SHA-256 digest: f3a6f0924d121e273be022589fa68724703cb7d906caa33fe4cded192cca842e
```

**新增用例（本批 +7 例）**：

| 模块 | 用例 | 覆盖 |
|---|---|---|
| `crypto` | `CbcEncryptPlaintextWipeTest`（+2，新文件） | **行为级**：注入记录型变换，捕获它收到的数组**引用**与**当时快照**——① 快照证明该副本原本确为明文（**非空跑**：旧实现下 `chunk` 在 close 后仍为明文，断言必红）；② 引用在 `close()` 后必须逐字节为 0（覆盖对齐块路径与末尾补齐块路径）；③ 断言写出的密文字节数不受清零动作影响（清零不得破坏写出语义）。含「恰好整块对齐 → 追加整填充块」分支 |
| `database` | `ChangeCredentialsKeyFileWipeTest`（+2，新文件） | ① **行为级（非空跑）**：换密后产出的库必须能用「新主密码 + **原密钥文件**」重新解锁——若把快照清零写在**写盘之前**，产物将使用全零密钥文件派生，该断言必红（同时证明生效中的凭据缓存未被误擦）；② **静态接线**：单参重载必须自持快照并在 `finally` 中清零、双参重载**不得**再有默认值、且清零点必须位于写盘之后 |
| `app` | `AppLogProguardRuleTest`（+3，新文件） | ① 规则必须用实例方法签名且不得出现 `public static void v/d(`；② **交叉锁定**：`AppLog.kt` 的 `v/d` 必须确为实例方法（无 `@JvmStatic`），任一侧改形态即失败；③ `e/w/i` 不得被剥离。断言前剔除注释（规则文件中大段说明含关键字） |

### 57.3 已知边界与口径（如实声明）

1. **P3-96 的残余可观测性缺口**：`close()` 路径中的 `plainSource`（`buffer.copyOf(filled)`）
   **不会**传给变换实现，故行为级断言无法直接观察它——本批以「具名 + pad 返回后立即清零」的结构
   保证其无泄漏窗口，并由代码审查（而非用例）兜底。传给变换的 `chunk` 与 `finalBlock` 均可被用例观察。
2. **P3-99 的所有权边界（重要）**：单参重载自持快照（克隆）→ **由本方法擦除**；
   双参重载的 `newKeyFileData` 归**调用方**所有 → **不擦除**（否则会破坏调用方的长期引用）。
   该差异已在 KDoc 逐条声明；`SessionCredentialCache.rotateCredentials` 内部 `clone()` 写入缓存，
   故本方法擦除入参不会影响生效凭据——这一点由行为级用例（换密后仍可解锁）独立证明。
3. **P3-99 的失败路径不受影响**：`restoreCredentials(oldPwd, oldKey)` 是**接管引用**
   （`passwordCache = oldPassword`），故 `oldPwd` / `oldKey` 在失败路径**不得**清零——
   这与成功路径清零（`rotateCredentials` 已克隆新凭据）的差异是既有正确行为，本批未改动。
4. **P3-98 的效果面为「零行为变更」，且产物大小可佐证**：生产代码**没有** `AppLog.v/d` 调用点
   （审计已核），故规则改为可匹配后 R8 输出**不变**——本批 release 产物字节数与上一批完全一致
   （均为 15,463,159 字节），与该判断一致。规则修正的价值在于：消除「看似有双重保险、实则单重」
   的**误导性声明**，并把两侧形态用用例锁死，防未来新增 `AppLog.v/d` 调用点时误以为已被剥离。
5. **本批未触及的相邻项**：`ISSUE-P3-97`（CI 原生用例真实执行）、`ISSUE-P3-100`（同步凭据解密后不清零）、
   `ISSUE-P3-101`（S3 签名中间缓冲）、`ISSUE-P3-102`（口令 SHA-1 哈希 String 驻留）等同族清零项仍为开放条目。
6. **计数**：本批 `ACTIVE_ISSUES.md` 的 P3 由 **42 → 39**（表行 35 → 32，标题条目仍 7），
   按「表行 + 标题条目」双形式口径复算；P2 计数不变（9）。

---

