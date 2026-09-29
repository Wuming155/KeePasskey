# 02 · KeePassDX（核心参考 🥇）踩坑对照详情

> 来源：参考项目 `参考项目/KeePassDX-master/` 的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 30 条，对照行 30 条（risk=yes 1 / unclear 0 / no 29）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（30 条）

### 1. app/src/main/java/com/kunzisoft/keepass/credentialprovider/activity/AuthenticationLauncherActivity.kt:84
- **代码上下文**：Passkey/UV 认证启动页处理未知的已解锁数据库
- **原文**：
  > // To manage https://github.com/Kunzisoft/KeePassDX/issues/2283
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2283`（可达性：yes）
- **坑的本质**：库已解锁时 WebAuthn 用户验证(UV)重认证可被绕过（issue 报告的安全缺陷），此处强制：需 UV 且未验过就必须走验证流程
- **置信度**：confirmed

### 2. app/src/main/java/com/kunzisoft/keepass/credentialprovider/activity/AutofillLauncherActivity.kt:80
- **代码上下文**：键盘自动填充选择入口 Activity 的 onCreate
- **原文**：
  > // To apply the bypass https://github.com/Kunzisoft/KeePassDX/issues/2238 // before managing intent in super class
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2238`（可达性：yes）
- **坑的本质**：键盘自动填充点击后无反应（issue 2238），须在父类处理 intent 之前把 PendingIntent 内 Bundle 还原成 specialMode/searchInfo 等 intent 特性
- **置信度**：confirmed

### 3. app/src/main/java/com/kunzisoft/keepass/credentialprovider/activity/AutofillLauncherActivity.kt:197
- **代码上下文**：构建自动填充选择模式的 PendingIntent
- **原文**：
  > // Doesn't work with direct extra Parcelable in Android 11 (don't know why?) // .../issues/2238 // Wrap into a bundle to bypass the problem
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2238`（可达性：yes）
- **坑的本质**：Android 11 下 PendingIntent 直接放 Parcelable extra 数据收不到（根因不明），必须包进 Bundle 再 putExtra 才能送达
- **置信度**：confirmed

### 4. app/src/main/java/com/kunzisoft/keepass/credentialprovider/activity/AutofillLauncherActivity.kt:228
- **代码上下文**：构建注册模式的 PendingIntent（getPendingIntentForRegistration）
- **原文**：
  > // Bypass intent issue
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2238`（可达性：yes）
- **坑的本质**：注册模式 PendingIntent 与选择模式同型的 intent 问题：Parcelable extra 同样要包 Bundle 中转，否则目标页拿不到注册信息
- **置信度**：confirmed

### 5. app/src/main/java/com/kunzisoft/keepass/credentialprovider/viewmodel/CredentialLauncherViewModel.kt:38
- **代码上下文**：凭据启动 ViewModel 的 onResult 清理
- **原文**：
  > // and prevent workflow bug if activity is relaunched // isResultLauncherRegistered = false
- **链接**：无（可达性：none）
- **坑的本质**：Activity 被系统重建时结果发射器注册状态会走错流程；连重置 isResultLauncherRegistered 的语句都被注释，仅靠清空 mSelectionResult 兜底
- **置信度**：suspected

### 6. app/src/main/java/com/kunzisoft/keepass/credentialprovider/passkey/util/PasskeyHelper.kt:275
- **代码上下文**：WebAuthn PRF 扩展 salt 派生常量与函数
- **原文**：
  > // https://github.com/Kunzisoft/KeePassDX/issues/2502#issuecomment-5245533775 // https://www.w3.org/TR/webauthn-3/#prf-extension
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2502#issuecomment-5245533775`（可达性：yes）
- **坑的本质**：PRF 的 salt 不是原始 salt，必须按规范做 SHA-256("WebAuthn PRF"||0x00||input) 域分隔；错用原 salt 会使注册/认证哈希不一致（issue 2502 修复过）
- **置信度**：confirmed

### 7. app/src/main/java/com/kunzisoft/keepass/password/PasswordGenerator.kt:237
- **代码上下文**：密码生成器扩展字符集的构造下界
- **原文**：
  > // From KeePassXC code https://github.com/keepassxreboot/keepassxc/pull/538 // [U+0080, U+009F] are C1 control characters, // U+00A0 is non-breaking space
- **链接**：`https://github.com/keepassxreboot/keepassxc/pull/538`（可达性：yes）
- **坑的本质**：扩展 Latin 字符集必须从 U+00A1 起：U+0080–U+009F 是 C1 控制字符、U+00A0 是不换行空格，误入集合会生成不可用密码
- **置信度**：confirmed

### 8. app/src/main/java/com/kunzisoft/keepass/utils/TimeUtil.kt:34
- **代码上下文**：日期选择器毫秒值转 DataDate
- **原文**：
  > // https://github.com/material-components/material-components-android/issues/882#issuecomment-1111374962 // To fix UTC time in date picker
- **链接**：`https://github.com/material-components/material-components-android/issues/882#issuecomment-1111374962`（可达性：yes）
- **坑的本质**：Material DatePicker 回传的 millis 被按本地时区解释 UTC 时刻，直接用会差一天；须按 UTC 取年月日再用本地 Calendar 重建
- **置信度**：confirmed

### 9. database/src/main/java/com/kunzisoft/keepass/utils/UriHelper.kt:123
- **代码上下文**：ContentResolver 打开库文件输出流保存
- **原文**：
  > // https://issuetracker.google.com/issues/180526528 // Try with rwt to fix content provider issue
- **链接**：`https://issuetracker.google.com/issues/180526528`（可达性：no）
- **坑的本质**：content provider 的 openOutputStream "wt" 模式可能抛 FileNotFoundException（Google issue 180526528），须捕获后降级用 "rwt" 重试（链接不可达，基于注释推断）
- **置信度**：confirmed

### 10. app/src/main/java/com/kunzisoft/keepass/database/helper/SearchHelper.kt:67
- **代码上下文**：自动填充按 web 域名搜索条目
- **原文**：
  > // Warning, the web domain may contain an IP, if so, do not crop it
- **链接**：无（可达性：none）
- **坑的本质**：webDomain 可能是 IP 字面量，若仍走 PSL 裁剪 publicSuffix+1 会得到空/错域名导致搜不到条目；IP 必须原样整串匹配
- **置信度**：confirmed

### 11. app/src/main/java/com/kunzisoft/keepass/database/helper/SearchHelper.kt:148
- **代码上下文**：Passkey 自动搜索入口 checkAutoSearchInfo
- **原文**：
  > // Do not place coroutine at start, bug in Passkey implementation
- **链接**：无（可达性：none）
- **坑的本质**：协程若放在函数开头会导致 Passkey 流程回调时序错乱（注释明言 bug），必须先同步判库状态再进入协程段
- **置信度**：confirmed

### 12. app/src/main/java/com/kunzisoft/keepass/viewmodels/EntryEditViewModel.kt:116
- **代码上下文**：条目编辑 ViewModel 加载条目前的状态刷新
- **原文**：
  > // Just to compensate TemplateView bug
- **链接**：无（可达性：none）
- **坑的本质**：TemplateView 内部状态缺陷导致模板集合变化不生效，须先发一次 loaded=false 的 UIState 补偿刷新才能让模板正确显示
- **置信度**：confirmed

### 13. database/src/main/java/com/kunzisoft/keepass/database/file/input/DatabaseInputKDB.kt:188
- **代码上下文**：KDB(v3) 解析条目 icon 字段
- **原文**：
  > // Clean up after bug that set icon ids to -1
- **链接**：无（可达性：none）
- **坑的本质**：历史版本 bug 会把图标 id 写成 -1，读取时不归零会让 getStandardIcon(-1) 取图标失败；读入时须把 -1 修正为 0
- **置信度**：confirmed

### 14. database/src/main/java/com/kunzisoft/keepass/database/crypto/CipherEngine.kt:39
- **代码上下文**：KDBX 解密时切 Twofish 引擎为 NoPadding（DatabaseInputKDBX.kt:149 置 true）
- **原文**：
  > // Used only with padding workaround
- **链接**：无（可达性：none）
- **坑的本质**：解密 Twofish 库时强制 Cipher 改用 CBC/NoPadding 绕开平台 PKCS7 填充不兼容，注释未写根因，是易碎的兼容开关
- **置信度**：suspected

### 15. app/src/main/java/com/kunzisoft/keepass/activities/legacy/DatabaseLockActivity.kt:153
- **代码上下文**：超时锁定 Activity 的 finish()
- **原文**：
  > // To fix weird crash
- **链接**：无（可达性：none）
- **坑的本质**：finish() 在某些状态下抛异常导致崩溃，根因未写明，只能 try-catch 吞掉并记日志；属掩盖型规避
- **置信度**：confirmed

### 16. app/src/main/java/com/kunzisoft/keepass/view/TextEditFieldView.kt:173
- **代码上下文**：受保护字段密码显隐开关 endIcon
- **原文**：
  > // FIXME Called by itself during orientation change
- **链接**：无（可达性：none）
- **坑的本质**：旋转屏幕重建时 endIconOnClickListener 会被自身误触发，点击回调只能整体注释掉（功能未挂回），FIXME 长期悬置
- **置信度**：confirmed

### 17. app/src/main/java/com/kunzisoft/keepass/activities/stylish/StylishActivity.kt:52
- **代码上下文**：全应用 Activity 基类拦截 startActivity
- **原文**：
  > /* (non-Javadoc) Workaround for HTC Linkify issues
- **链接**：无（可达性：none）
- **坑的本质**：HTC ROM 的 HtcLinkifyDispatcherActivity 会劫持应用内 startActivity，须检测该 shortClassName 并置空 component 绕过
- **置信度**：confirmed

### 18. app/src/main/java/com/kunzisoft/keepass/credentialprovider/autofill/KeeAutofillService.kt:361
- **代码上下文**：构建带认证跳转的 Autofill 响应
- **原文**：
  > // Buggy method on some API 33 devices
- **链接**：无（可达性：none）
- **坑的本质**：API 33 部分设备上新版 setAuthentication(autofillIds,…) 直接抛异常，须 try-catch 后回退到弃用的旧重载，否则自动填充认证失效
- **置信度**：confirmed

### 19. app/src/main/java/com/kunzisoft/keepass/services/DatabaseTaskNotificationService.kt:591
- **代码上下文**：数据库通知加 MediaStyle 展示操作按钮
- **原文**：
  > // Won't work with Xiaomi and Kitkat
- **链接**：无（可达性：none）
- **坑的本质**：MediaStyle.setShowActionsInCompactView 在小米 ROM/Kitkat 上不生效，通知内锁库按钮可能缺失，须按版本门控规避
- **置信度**：suspected

### 20. database/src/main/java/com/kunzisoft/keepass/utils/StreamBytesUtils.kt:122
- **代码上下文**：kdbx 流式解析的定长块读取 readBytesLength
- **原文**：
  > // WARNING this.read(buf, 0, length) Doesn't work
- **链接**：无（可达性：none）
- **坑的本质**：包装流上单次 read(buf,0,len) 不保证读满返回，导致解析错位；只能逐字节循环读取规避，代价是热路径性能下降
- **置信度**：confirmed

### 21. app/src/main/java/com/kunzisoft/keepass/database/action/node/UpdateEntryRunnable.kt:58
- **代码上下文**：条目更新 ActionRunnable 落库前重挂父节点与历史（UpdateGroupRunnable.kt:57 对组同型）
- **原文**：
  > // WARNING : Re attribute parent removed in entry edit activity to save memory
- **链接**：无（可达性：none）
- **坑的本质**：编辑页为省内存经 Bundle 传递时把 parent/history 从新条目剥离，落库前必须重新挂回，否则保存后条目丢父节点与历史
- **置信度**：confirmed

### 22. database/src/main/java/com/kunzisoft/keepass/utils/StringUtil.kt:25
- **代码上下文**：颜色字符串转 colorInt（库/组自定义颜色）
- **原文**：
  > // Use a long to avoid rollovers on #ffXXXXXX
- **链接**：无（可达性：none）
- **坑的本质**：8 位带 alpha 的 #FFxxxxxx 超 Int 上界，用 Int 解析会回绕成错误颜色值，必须先转 long 再 or alpha 位后收窄
- **置信度**：confirmed

### 23. database/src/main/java/com/kunzisoft/keepass/database/element/database/DatabaseKDBX.kt:680
- **代码上下文**：判断组是否位于回收站
- **原文**：
  > // To keep compatibility with old V1 databases
- **链接**：无（可达性：none）
- **坑的本质**：旧版库没有回收站指针，只能靠「根目录下名为 Backup 的组」字符串匹配当回收站，可能把恰好叫 Backup 的普通组误判进回收站语义
- **置信度**：suspected

### 24. app/src/main/java/com/kunzisoft/keepass/credentialprovider/activity/PasskeyLauncherActivity.kt:192
- **代码上下文**：Passkey 更新条目任务完成回调（PasswordLauncherActivity.kt:159 同型）
- **原文**：
  > // TODO When auto save is enabled, WARNING filter by the calling activity
- **链接**：无（可达性：none）
- **坑的本质**：开启自动保存时未按调用方 Activity 过滤就自动选中 passkey，会误响应无关更新，autoSelectPasskey 因此被注释禁用，属已知未收口缺口
- **置信度**：suspected

### 25. app/src/main/java/com/kunzisoft/keepass/biometric/DeviceUnlockFragment.kt:255
- **代码上下文**：跳转系统指纹注册页引导设备解锁
- **原文**：
  > // ACTION_SECURITY_SETTINGS does not contain fingerprint enrollment on some devices...
- **链接**：无（可达性：none）
- **坑的本质**：部分厂商设备上 ACTION_SECURITY_SETTINGS 页面不含指纹注册入口，须先试专用 ACTION_FINGERPRINT_ENROLL 再逐级回退到全局设置
- **置信度**：suspected

### 26. app/src/main/java/com/kunzisoft/keepass/timeout/TimeoutHelper.kt:101
- **代码上下文**：记录自动锁定超时时间戳
- **原文**：
  > // To prevent spam registration, record after at least 2 seconds
- **链接**：无（可达性：none）
- **坑的本质**：recordTime 被高频事件反复调用会不断重置锁定计时，须做 2 秒节流，否则自动锁定可能永不触发
- **置信度**：suspected

### 27. CHANGELOG:4
- **代码上下文**：CHANGELOG 4.5.2 已修复缺陷记录
- **原文**：
  > KeePassDX(4.5.2) * Fix OTP token size #2672
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2672`（可达性：yes）
- **坑的本质**：开启「显示 OTP token」后，条目列表里的 TOTP 码每次刷新字号递增直至不可读（刷新时重复放大文本的渲染缺陷）
- **置信度**：confirmed

### 28. CHANGELOG:86
- **代码上下文**：CHANGELOG 4.4.1 已修复缺陷记录
- **原文**：
  > * Fixed a bug that prevents protected fields from being displayed on some OS with poorly implemented TextView #2524
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2524`（可达性：yes）
- **坑的本质**：部分系统 TextView 实现质量差，导致受保护字段（点查看后）无法显示甚至崩溃；受保护字段渲染对厂商 ROM 是真实兼容坑
- **置信度**：confirmed

### 29. CHANGELOG:83
- **代码上下文**：CHANGELOG 4.4.2 已修复缺陷记录
- **原文**：
  > * Fixed duplication of UUIDs by restoring the old behavior of sorting by access #2527 #1911
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2527`（可达性：yes）
- **坑的本质**：4.4.0 新「按访问时间排序」实现引入 UUID 复制，开库报重复 UUID 警告、部分条目翻倍部分丢失；排序实现改动会危及条目一致性
- **置信度**：confirmed

### 30. CHANGELOG:20
- **代码上下文**：CHANGELOG 4.5.0/4.5.0_beta05 已修复缺陷记录
- **原文**：
  > * Fix 32 bytes XML v2 KeyFiles #2660
- **链接**：`https://github.com/Kunzisoft/KeePassDX/issues/2660`（可达性：yes）
- **坑的本质**：生成的 XML v2 keyfile 密钥材料为 128 字节而非标准 32 字节，导致用其创建的库在 KeePassXC 中打不开（互操作性踩坑）
- **置信度**：confirmed

## 二、对照行（30 条）

### 1. AuthenticationLauncherActivity.kt:84 → CredentialVerificationLauncher.kt:57
- **坑的本质**：库已解锁时 WebAuthn UV 重认证可被绕过（issue 2283），须强制「RP 要求 UV 且未验过就必须走验证流程」
- **触发条件**：Passkey UV 为 REQUIRED/PREFERRED 且库已打开
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/CredentialVerificationLauncher.kt:57`
- **对照情况**：功能相似：同为凭据下发/签发前的用户验证门控，且主项目更严
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 2. AutofillLauncherActivity.kt:80 → 无直接对应
- **坑的本质**：键盘自动填充点击无反应（issue 2238）：须在父类处理 intent 前把 PendingIntent 内 Bundle 还原成 specialMode/searchInfo 等 intent 特性
- **触发条件**：从 Magikeyboard 的 PendingIntent 启动选择入口
- **主项目对应位置**：无直接对应
- **对照情况**：无对应：主项目无自定义键盘入口 Activity；最近似的是 legacy 无障碍通道（LegacyAutofillAccessibilityService.kt:139-148），其入口由通知 PendingIntent 直接携带 String extra，无 specialMode 之类需还原的特性
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 3. AutofillLauncherActivity.kt:197 → LegacyAutofillAccessibilityService.kt:139
- **坑的本质**：Android 11 下 PendingIntent 直接放 Parcelable extra 数据收不到（根因不明），必须包进 Bundle 再 putExtra 才能送达
- **触发条件**：Android 11 上 PendingIntent 携带 Parcelable extra
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillAccessibilityService.kt:139`
- **对照情况**：同类模式（经 PendingIntent 传 extra），但主项目 extra 全部是 String/ArrayList<String> 原始类型
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 4. AutofillLauncherActivity.kt:228 → CredentialCreateEntries.kt:55
- **坑的本质**：注册模式 PendingIntent 与选择模式同型的 intent 问题：Parcelable extra 须包 Bundle 中转，否则目标页拿不到注册信息
- **触发条件**：自动填充注册流程创建 PendingIntent
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt:55`
- **对照情况**：同类模式（凭据注册入口 PendingIntent），但 extras 均为 String（rpId/用户名/challenge/origin），CM 通道请求体本身由 androidx.credentials 的 PendingIntentHandler 契约承载
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. CredentialLauncherViewModel.kt:38 → AutofillUnlockActivity.kt:94
- **坑的本质**：Activity 重建时结果发射器注册状态标志走错流程；连重置 isResultLauncherRegistered 的语句都被注释，仅靠清空结果兜底
- **触发条件**：凭据 Activity 被销毁重建后再次进入
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt:94`
- **对照情况**：同类模式（Activity Result 回调驱动凭据流程），但主项目无 ViewModel 持有的「已注册」布尔标志
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. PasskeyHelper.kt:275 → PasskeyPrf.kt:51
- **坑的本质**：PRF 的 salt 不是原始 salt，必须按规范做 SHA-256("WebAuthn PRF"||0x00||input) 域分隔；错用原 salt 会使注册/认证哈希不一致（issue 2502 修复过）
- **触发条件**：实现或验证 PRF 扩展的 salt 派生
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyPrf.kt:51`
- **对照情况**：功能相似：同实现 WebAuthn PRF salt 派生，且 KDoc 明示与 KeePassDX 同口径
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 7. PasswordGenerator.kt:237 → EntryEditFormProjection.kt:164
- **坑的本质**：扩展 Latin 字符集必须从 U+00A1 起：U+0080–U+009F 是 C1 控制字符、U+00A0 是不换行空格，误入集合会生成不可用密码
- **触发条件**：密码生成勾选扩展字符集
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditFormProjection.kt:164`
- **对照情况**：功能相似面（密码生成器），但主项目字符池只有大小写/数字/符号（165-169 行），无扩展字符集选项
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. TimeUtil.kt:34 → EntryEditExpiryEditor.kt:89 ⚠ risk=yes
- **坑的本质**：DatePicker 回传的 millis 被按本地时区解释 UTC 时刻，直接用会差一天；须按 UTC 取年月日再重建（KeePassDX 修复过）
- **触发条件**：非 UTC 时区用户在日期选择器选日期
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditExpiryEditor.kt:89`
- **对照情况**：同类模式：同样的「选择器毫秒值 ↔ 日历日期」跨时区转换，且主项目恰好踩反
- **是否有同样风险**：**yes**
- **建议**：Compose M3 DatePicker 的 selectedDateMillis 语义是 UTC 毫秒（官方 DatePickerState reference 明确：『selected date start of the day in UTC milliseconds from the epoch』，已现查核实），而 EntryEditExpiryEditor.kt:89-94 用 atZone(ZoneId.systemDefault()) 解释——UTC 西侧时区（如 America/New_York：00:00Z=前一天 19:00）选中日期会变前一天；反向 initialMillis（78-80 行）用 atStartOfDay(systemDefault()) 构造，UTC 东侧时区（如 Asia/Shanghai）初始选中会落在前一天。修法一：读侧 Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()，写侧 expiryDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()，双向统一按 UTC 取年月日（与 KeePassDX TimeUtil 修复同型）；修法二（更简）：material3 1.4+ 的 DatePickerState.getSelectedDate() 扩展直接返回 LocalDate、setSelectedDate(LocalDate) 直接写入（@RequiresApi 26，主项目 minSdk 36 满足）。已核实修后与既有链一致：EntryEditFormProjection.kt:46（读 entry.expiresAt.atZone(systemDefault()).toLocalDate()）与 EntryEditSaveProjection.kt:61-64（写 atTime(23,59,59).atZone(systemDefault())）均以 LocalDate 为中间载体，选择器两侧统一 UTC 即闭环；修法不涉及敏感数据形态与模块依赖，不违反硬纪律。app/src/test 中 'ExpiryEditor|selectedDateMillis|DatePicker' 零命中——无既有守卫测试，建议把双向转换抽成纯函数并加时区参数化单测（至少 America/New_York 与 Asia/Shanghai 两端断言同日往返）。
- **需补充信息**：（无）

### 9. UriHelper.kt:123 → SafVaultCreation.kt:137
- **坑的本质**：content provider 的 openOutputStream "wt" 模式可能抛 FileNotFoundException（Google issue 180526528），须捕获后降级用 "rwt" 重试
- **触发条件**：保存数据库到 content:// URI 且 provider 不支持 wt
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/data/repository/SafVaultCreation.kt:137`
- **对照情况**：功能相似：同为 content:// URI 写库通道
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 10. SearchHelper.kt:67 → PublicSuffixList.kt:116
- **坑的本质**：webDomain 可能是 IP 字面量，若仍走 PSL 裁剪 publicSuffix+1 会得到空/错域名导致搜不到条目；IP 必须原样整串匹配
- **触发条件**：凭据关联的站点域名为 IP 而非域名
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt:116`
- **对照情况**：同类模式：同为「按站点域搜索/匹配条目 + PSL」
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 11. SearchHelper.kt:148 → PasskeyAssertionActivity.kt:82
- **坑的本质**：协程若放在函数开头会导致 Passkey 流程回调时序错乱，必须先同步判库状态再进入协程段
- **触发条件**：Passkey 请求进入 checkAutoSearchInfo
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt:82`
- **对照情况**：同类模式（凭据流程先判库状态），但结构不同：无共享自动搜索助手与回调时序耦合面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 12. EntryEditViewModel.kt:116 → EntryEditViewModel.kt:262
- **坑的本质**：TemplateView 内部状态缺陷导致模板集合变化不生效，须先发一次 loaded=false 的补偿刷新
- **触发条件**：打开或重载条目编辑页
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt:262`
- **对照情况**：功能相似（编辑页模板预填），但主项目为 Compose + StateFlow 声明式投影，无自带内部状态的模板 View
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 13. DatabaseInputKDB.kt:188 → 无直接对应
- **坑的本质**：历史 bug 会把 KDB(v3) 图标 id 写成 -1，读取时不归零会让 getStandardIcon(-1) 取图标失败
- **触发条件**：读取含 -1 图标 id 的旧 kdb 库
- **主项目对应位置**：无直接对应
- **对照情况**：无对应：主项目仅支持标准 .kdbx v4（AGENTS.md §2 项目概述），仓内无 KDB v3 解析器，无 icon id -1 读取面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 14. CipherEngine.kt:39 → TwofishCipherEngine.kt:163
- **坑的本质**：解密 Twofish 库时强制 Cipher 改用 CBC/NoPadding 绕开平台 PKCS7 填充不兼容（易碎兼容开关，根因未写明）
- **触发条件**：打开 Twofish 加密的 kdbx 库
- **主项目对应位置**：`crypto/src/main/java/com/keepasskey/crypto/cipher/TwofishCipherEngine.kt:163`
- **对照情况**：功能相似：同为 Twofish-CBC 解密实现
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. DatabaseLockActivity.kt:153 → AutoLockManager.kt:33
- **坑的本质**：超时锁定流程的 finish() 在某些状态下抛异常致崩溃，只能 try-catch 吞掉并记日志（掩盖型规避）
- **触发条件**：超时锁定流程关闭 Activity
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:33`
- **对照情况**：无直接对应：主项目锁定流程不经专用 Activity
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 16. TextEditFieldView.kt:173 → SecurePasswordField.kt
- **坑的本质**：旋转屏幕重建时 endIconOnClickListener 会被自身误触发，点击回调只能整体注释掉（功能未挂回），FIXME 长期悬置
- **触发条件**：屏幕方向改变导致 View 重建
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt`
- **对照情况**：功能相似（受保护字段密码显隐切换），但主项目为 Compose 声明式实现
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 17. StylishActivity.kt:52 → 无直接对应
- **坑的本质**：HTC ROM 的 HtcLinkifyDispatcherActivity 会劫持应用内 startActivity，须检测该 shortClassName 并置空 component 绕过
- **触发条件**：HTC 设备上启动任意 Activity
- **主项目对应位置**：无直接对应
- **对照情况**：无对应：主项目无公共基类拦截 startActivity（全仓 grep "override.*startActivity" 零命中），无 ROM 级 Linkify 处理面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 18. KeeAutofillService.kt:361 → AutofillDatasetBuilders.kt:152
- **坑的本质**：API 33 部分设备上新版 setAuthentication(autofillIds,…) 直接抛异常，须 try-catch 后回退弃用旧重载，否则自动填充认证失效
- **触发条件**：API 33 设备弹出自动填充解锁响应
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:152`
- **对照情况**：功能相似：同为自动填充认证数据集构建
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 19. DatabaseTaskNotificationService.kt:591 → UnlockedNotificationController.kt:121
- **坑的本质**：MediaStyle.setShowActionsInCompactView 在小米 ROM/Kitkat 上不生效，通知内锁库按钮可能缺失
- **触发条件**：小米设备或 API≤19 显示数据库通知
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/notification/UnlockedNotificationController.kt:121`
- **对照情况**：功能相似（解锁态常驻通知），但主项目不用 MediaStyle、无操作按钮
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 20. StreamBytesUtils.kt:122 → LittleEndianUtil.kt:66
- **坑的本质**：包装流上单次 read(buf,0,len) 不保证读满返回，导致 kdbx 解析错位；只能逐字节循环规避（热路径性能代价）
- **触发条件**：从解压/解密包装流读取定长数据块
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/io/LittleEndianUtil.kt:66`
- **对照情况**：同类模式：同为 kdbx 流式解析的定长读取
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 21. UpdateEntryRunnable.kt:58 → VaultEntryWriteCoordinator.kt:86
- **坑的本质**：编辑页为省内存经 Bundle 传递时把 parent/history 从新条目剥离，落库前必须重新挂回，否则保存后条目丢父节点与历史（UpdateGroupRunnable.kt:57 组同型）
- **触发条件**：条目/组经编辑 Activity Bundle 传递后保存
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/data/repository/VaultEntryWriteCoordinator.kt:86`
- **对照情况**：功能相似（条目编辑落库），但主项目不经济会经 Bundle 传实体，parent/history 天然保留
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 22. StringUtil.kt:25 → KdbxEntry.kt
- **坑的本质**：8 位带 alpha 的 #FFxxxxxx 超 Int 上界，用 Int 解析会回绕成错误颜色值，必须先转 long 再收窄
- **触发条件**：解析带 alpha 通道的十六进制颜色字符串
- **主项目对应位置**：`core/src/main/java/com/keepasskey/core/model/KdbxEntry.kt`
- **对照情况**：同类模式（KDBX 条目/组自定义颜色字段），但主项目从不把颜色解析为 colorInt
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 23. DatabaseKDBX.kt:680 → RecycleBinCoordinator.kt:370
- **坑的本质**：旧版库没有回收站指针时，靠「根目录下名为 Backup 的组」字符串匹配当回收站，可能把恰好叫 Backup 的普通组误判进回收站语义
- **触发条件**：打开缺少 recycleBin 元数据的旧库
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/data/repository/RecycleBinCoordinator.kt:370`
- **对照情况**：同类模式：同为「无 recycleBinUuid 指针时按组名兜底」
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 24. PasskeyLauncherActivity.kt:192 → 无直接对应
- **坑的本质**：开启自动保存时未按调用方 Activity 过滤就自动选中 passkey，会误响应无关更新，autoSelectPasskey 因此被注释禁用（已知未收口缺口）
- **触发条件**：自动保存开启且发生 ACTION_DATABASE_UPDATE_ENTRY_TASK
- **主项目对应位置**：无直接对应
- **对照情况**：无对应：主项目无「自动保存自动选中条目」通道（全仓 grep autoSave/自动保存凭据面零命中），保存一律经 PasswordSaveActivity/PasskeyCreateActivity 显式用户确认
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 25. DeviceUnlockFragment.kt:255 → 无直接对应
- **坑的本质**：部分厂商设备上 ACTION_SECURITY_SETTINGS 页面不含指纹注册入口，须先试专用 ACTION_FINGERPRINT_ENROLL 再逐级回退
- **触发条件**：部分厂商设备上引导指纹注册
- **主项目对应位置**：无直接对应
- **对照情况**：无对应：主项目无「引导去系统注册指纹」页——无可用认证器时按 fail-closed 降级为受保护窗口内手动确认（CredentialFillVerifier.kt:72-77），不跳转系统设置
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 26. TimeoutHelper.kt:101 → AutoLockManager.kt:33
- **坑的本质**：recordTime 被高频事件反复调用会不断重置锁定计时，须做 2 秒节流，否则自动锁定可能永不触发
- **触发条件**：2 秒内多次触发 recordTime 的事件
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/AutoLockManager.kt:33`
- **对照情况**：功能相似（自动锁定计时），但主项目计时模型不同——不存在按交互重置的 recordTime
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 27. CHANGELOG:4 → VaultEntryRowLayouts.kt:355
- **坑的本质**：开启「显示 OTP token」后列表里 TOTP 码每次刷新字号递增直至不可读（刷新时重复放大文本的渲染缺陷，issue 2672）
- **触发条件**：显示 OTP token 且 TOTP 周期性刷新
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultEntryRowLayouts.kt:355`
- **对照情况**：功能相似（条目列表行内 TOTP 码展示），但主项目为 Compose 声明式固定样式
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 28. CHANGELOG:86 → SecurePasswordField.kt
- **坑的本质**：部分系统 TextView 实现质量差，导致受保护字段（点查看后）无法显示甚至崩溃（issue 2524）
- **触发条件**：在 TextView 实现有缺陷的 OS 上查看受保护字段
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt`
- **对照情况**：功能相似（受保护字段显隐渲染），但主项目为 Compose 渲染，无 TextView transformationMethod 面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 29. CHANGELOG:83 → VaultListProjection.kt:296
- **坑的本质**：4.4.0 新「按访问时间排序」实现引入 UUID 复制，开库报重复 UUID 警告、部分条目翻倍部分丢失（issue 2527/1911）——排序实现改动危及条目一致性
- **触发条件**：升级 4.4.0 后按访问排序打开库
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListProjection.kt:296`
- **对照情况**：同类模式（列表排序选项），但主项目排序是纯内存只读投影
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 30. CHANGELOG:20 → KdbxKeyFileGenerator.kt:35
- **坑的本质**：生成的 XML v2 keyfile 密钥材料为 128 字节而非标准 32 字节，导致用其创建的库在 KeePassXC 中打不开（issue 2660，互操作性踩坑）
- **触发条件**：使用 KeePassDX 生成 XML v2 keyfile 的库
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/file/KdbxKeyFileGenerator.kt:35`
- **对照情况**：功能相似：同为 XML v2 keyfile 生成器
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

## 三、复核记录（独立复核结论）

1. **【修正】TimeUtil.kt:34（DatePicker 时区）advice 字段补充核实证据与备选修法**：①主项目代码确认存在且性质如所述——EntryEditExpiryEditor.kt:89-94 读侧用 Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() 解释 selectedDateMillis，78-80 行写侧用 expiryDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() 构造 initialSelectedDateMillis；②官方 API 文档（developer.android.com DatePickerState reference，经 Context7/webReader 现查）明确 selectedDateMillis 语义为『selected date start of the day in UTC milliseconds from the epoch』——advice 的 UTC 判据成立，risk=yes 维持；③触发条件真实：UTC 西侧时区（America/New_York）UTC 00:00=前一天 19:00，读侧 LocalDate 变前一天；UTC 东侧时区（Asia/Shanghai）本地 00:00=前一天 16:00 UTC，写侧 initialMillis 被 picker 按 UTC 语义解释后初始选中落前一天；④守卫缺位：grep app/src/test 中 'ExpiryEditor|selectedDateMillis|DatePicker' 零命中，无该双向转换的任何测试；⑤advice 修法可行且不违反硬纪律（纯时区口径修正，无敏感数据落地 String、无模块依赖变化），并核实与既有链一致——EntryEditFormProjection.kt:46 与 EntryEditSaveProjection.kt:61-64 均以 LocalDate 为中间载体，选择器两侧统一按 UTC 取年月日即与链路闭环；⑥追加备选：material3 1.4+ 提供 DatePickerState.getSelectedDate() 扩展直接返回 LocalDate（@RequiresApi 26，主项目 minSdk 36 满足），setSelectedDate(LocalDate) 同理，可作为更简修法。risk/ourLocation/essence 等其余字段未变。
2. **【抽查 risk=no 行两条均维持原判】**：①PRF 行——读 crypto/src/main/java/com/keepasskey/crypto/passkey/PasskeyPrf.kt 确认 51 行 DOMAIN_SEPARATION_PREFIX="WebAuthn PRF"、89-97 行 clientSideProcess 逐字节 SHA-256("WebAuthn PRF"||0x00||input)，与描述一致；②IP 字面量行——读 app/src/main/java/com/keepasskey/app/passkey/PublicSuffixList.kt:116-128 确认 registrableDomain 对 IPv6（含冒号，118 行）与 IPv4（全数字标签，121 行）一律返回 null（fail-closed），与描述一致。未修改。
