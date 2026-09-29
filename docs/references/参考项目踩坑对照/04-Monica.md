# 04 · Monica（UI 补充参考 🥉）踩坑对照详情

> 来源：参考项目 `参考项目/Monica-main/` 的踩坑标注与主项目 KeePasskey 的逐条对照。
> 扫描日期 2026-09-28。踩坑标注 34 条，对照行 34 条（risk=yes 2 / unclear 1 / no 31）。
> 总表见 [00-对照总表.md](00-对照总表.md)。

## 一、踩坑标注（34 条）

### 1. app/src/main/java/takagi/ru/monica/passkey/MonicaCredentialProviderService.kt:85
- **代码上下文**：构建 Passkey 确认/注册 Activity 的 PendingIntent
- **原文**：
  > 重要：不要使用 FLAG_ACTIVITY_NEW_TASK！Credential Provider API 会自动处理任务栈，使用 NEW_TASK 会导致 Activity 在错误的任务栈中启动
- **链接**：无（可达性：none）
- **坑的本质**：给 Credential Provider 的 PendingIntent 加 NEW_TASK 会让 Activity 落在错误任务栈，无法正确返回调用方应用
- **置信度**：confirmed

### 2. app/src/main/java/takagi/ru/monica/passkey/PasskeyAuthActivity.kt:477
- **代码上下文**：WebAuthn 断言签名时写入 signCount
- **原文**：
  > 签名计数器永远写 0……A 设备签到 6，B 设备恢复后还是 5，B 再签 6，RP 端看到 6 ≤ 6 直接拒签——表现就是用户长期反映的"用了若干次后 passkey 突然失效"
- **链接**：无（可达性：none）
- **坑的本质**：同步型 passkey 私钥可漫游且无实时双向同步，signCount 单调递增跨设备恢复后必然分叉，RP 单调性校验拒签
- **置信度**：confirmed

### 3. app/src/main/java/takagi/ru/monica/passkey/CredentialProviderRefreshReceiver.kt:15
- **代码上下文**：应用更新后记录 Credential Provider 状态
- **原文**：
  > Avoid force-toggling component state on upgrade/startup. Some OEM builds may invalidate provider selection when component state is flipped programmatically
- **链接**：无（可达性：none）
- **坑的本质**：部分厂商 ROM 在程序化翻转组件开关时会作废用户已选的凭据提供者，用户须手动重进系统设置重选
- **置信度**：confirmed

### 4. app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt:505
- **代码上下文**：KDBX 文件解码的并发控制
- **原文**：
  > kotpass decode 在并发下可能触发 native 崩溃，必须跨实例串行化。……部分设备/ABI 下 decode 在不同工作线程切换时更易触发 native 崩溃，固定到单线程执行更稳。
- **链接**：无（可达性：none）
- **坑的本质**：kotpass 解码库并发调用触发 native 崩溃，且线程切换加剧；用全局互斥 + 固定单线程执行器双重复规避
- **置信度**：confirmed

### 5. app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt:1319
- **代码上下文**：KeePass 组跨库移动的写入顺序
- **原文**：
  > Write the target first; if source cleanup fails, preserving both trees is safer than deleting source data before the target is durable.
- **链接**：无（可达性：none）
- **坑的本质**：移动操作必须先写目标再清理源；若先删源而目标未落盘，中途失败即丢数据；重试时按目标组 UUID 判重防重复插入
- **置信度**：confirmed

### 6. app/src/main/java/takagi/ru/monica/keepass/KeePassPasskeyUpdateExecutor.kt:54
- **代码上下文**：Passkey 跨存储迁移时删除旧 KDBX 条目
- **原文**：
  > Cross-store moves must never delete the KDBX source before the target store and Room projection have accepted the passkey.
- **链接**：无（可达性：none）
- **坑的本质**：跨库移动若先删 KDBX 源而目标库/Room 投影未接受，失败即永久丢 passkey；先接受后删，失败可从重复态安全重试
- **置信度**：confirmed

### 7. app/src/main/java/takagi/ru/monica/MainActivity.kt:1050
- **代码上下文**：主界面 Compose 全局共享转场配置
- **原文**：
  > Emergency safe mode: disable global shared transition lookahead to avoid "Placement happened before lookahead" crashes on affected devices/builds.
- **链接**：无（可达性：none）
- **坑的本质**：部分设备/系统构建上 Compose lookahead 与共享转场顺序错乱直接闪退；紧急安全模式置空 LocalSharedTransitionScope 降级
- **置信度**：confirmed

### 8. app/src/main/java/takagi/ru/monica/MainActivity.kt:954
- **代码上下文**：NavHost 起始目的地选择
- **原文**：
  > 使用固定的 startDestination 避免竞态条件
- **链接**：无（可达性：none）
- **坑的本质**：认证状态异步变化时动态改 startDestination 会与导航图组建竞态；固定起始目的地，后续变化交 LaunchedEffect 处理
- **置信度**：confirmed

### 9. app/src/main/java/takagi/ru/monica/ui/screens/TimelineScreen.kt:1482
- **代码上下文**：时间线 SafeAnimatedVisibility 显隐动画
- **原文**：
  > Android 14+ 机型上此处可能触发 Lookahead 布局竞态，降级为无动画避免闪退。
- **链接**：无（可达性：none）
- **坑的本质**：Android 14+ 上 AnimatedVisibility 与 lookahead 布局竞态闪退；SDK≥34 直接渲染内容、跳过动画
- **置信度**：confirmed

### 10. app/src/main/java/takagi/ru/monica/ui/components/BottomSheetStability.kt:31
- **代码上下文**：底部弹层显隐动画统一封装
- **原文**：
  > Android 14+ still has intermittent placement jitter/crash cases when ModalBottomSheet and AnimatedVisibility both mutate layout in the same frame.
- **链接**：无（可达性：none）
- **坑的本质**：同一帧内 ModalBottomSheet 与 AnimatedVisibility 都修改布局时 Android 14+ 间歇性抖动/崩溃；高版本跳过动画
- **置信度**：confirmed

### 11. app/src/main/java/takagi/ru/monica/ui/components/UnifiedCategoryFilterBottomSheet.kt:1616
- **代码上下文**：分类筛选弹层显隐动画
- **原文**：
  > Android 14+ ModalBottomSheet may hit a Lookahead placement race with AnimatedVisibility. Fall back to non-animated content on these versions to avoid runtime crash.
- **链接**：无（可达性：none）
- **坑的本质**：同型 Lookahead placement race 触发运行时崩溃；SDK≥34 降级为无动画直接渲染
- **置信度**：confirmed

### 12. app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt:1362
- **代码上下文**：卡包拖拽排序落库
- **原文**：
  > 三种类型共用 SecureItemRepository，一次写入即可覆盖全部，拆成每类型一次会让同一张表触发多次 Flow 失效，拖动结果被中间态回拉。
- **链接**：无（可达性：none）
- **坑的本质**：排序按类型拆成多次写库会多次触发同一表 Flow 失效，拖动结果被中间态回拉；必须合并为一次批量写入
- **置信度**：confirmed

### 13. app/src/main/java/takagi/ru/monica/autofill_ng/AutofillDetectionPolicy.kt:67
- **代码上下文**：自动填充字段准入策略
- **原文**：
  > 密码类是强登录信号，即便是低精度……且当前不可见，也应纳入解析，避免……聚焦账号框时因密码框尚未可见而被整体丢弃、导致密码填充失效。
- **链接**：无（可达性：none）
- **坑的本质**：密码框因布局/动画时序不可见时整组字段被丢弃、填充失效；密码 hint 放宽到最低精度仍纳入，账号类保持中精度防误判搜索框
- **置信度**：confirmed

### 14. app/src/main/java/takagi/ru/monica/autofill_ng/MonicaAutofillServiceNg.kt:71
- **代码上下文**：跨 FillRequest 的登录字段记忆与回补
- **原文**：
  > 跨请求登录字段记忆……仅当缓存的所有登录字段 AutofillId 仍存在于当前 AssistStructure 时才整体回补，避免注入失效 id。
- **链接**：无（可达性：none）
- **坑的本质**：登录目标因字段可见性时有时无；缓存回补必须先校验旧 AutofillId 仍在当前结构中，否则注入失效 id 填充失败
- **置信度**：confirmed

### 15. app/src/main/java/takagi/ru/monica/autofill_ng/builder/FilledDataBuilderNg.kt:55
- **代码上下文**：自动填充解锁态判定
- **原文**：
  > "永不过期"允许会话跨进程生命周期恢复，但显式锁定仍必须立即生效。不能仅凭 Keystore 材料可读就绕过 SessionManager
- **链接**：无（可达性：none）
- **坑的本质**：只看密钥材料可读性判未锁会绕过用户显式锁定；必须同时检查会话状态与可用密钥材料
- **置信度**：confirmed

### 16. app/src/main/java/takagi/ru/monica/autofill_ng/builder/FillResponseBuilderNg.kt:597
- **代码上下文**：填充响应挂载 SaveInfo 保存提示
- **原文**：
  > Bitwarden-compatible: skip save for login fields in compat mode because password values can be masked and lead to low-quality save prompts.
- **链接**：无（可达性：none）
- **坑的本质**：兼容模式下登录字段密码值可能被系统掩码，据此触发的保存会把掩码值存下来形成低质量保存提示；compat 模式跳过 SaveInfo
- **置信度**：confirmed

### 17. app/src/main/java/takagi/ru/monica/autofill_ng/core/AutofillServiceChecker.kt:172
- **代码上下文**：检测自动填充服务是否已启用
- **原文**：
  > 兜底路径：Settings.Secure（部分 ROM 上 manager 返回会延迟/空值）
- **链接**：无（可达性：none）
- **坑的本质**：部分厂商 ROM 上 AutofillManager 查询延迟或返回空导致误报未启用；回退读 Settings.Secure 的 autofill_service 双路核对
- **置信度**：confirmed

### 18. app/src/main/java/takagi/ru/monica/utils/DeviceUtils.kt:330
- **代码上下文**：键盘内联建议（键盘上方气泡）支持判定
- **原文**：
  > 某些国产ROM的内联建议有兼容性问题……MIUI需要Android 12+……HarmonyOS暂不完全支持
- **链接**：无（可达性：none）
- **坑的本质**：国产 ROM 内联建议兼容性差：MIUI/HyperOS、OriginOS 需 Android 12+，HarmonyOS 直接禁用；按 ROM 类型分级降级
- **置信度**：confirmed

### 19. app/src/main/java/takagi/ru/monica/autofill_ng/ui/AppIconCache.kt:52
- **代码上下文**：自动填充选择器应用图标缓存
- **原文**：
  > 使用固定尺寸转换，避免某些 Drawable (如 AdaptiveIconDrawable) 崩溃
- **链接**：无（可达性：none）
- **坑的本质**：AdaptiveIconDrawable 等不指定尺寸的 toBitmap 转换会崩溃（另可 OOM）；固定 96px 尺寸转换并捕获 OOM/异常兜底 null
- **置信度**：confirmed

### 20. app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt:497
- **代码上下文**：Room 迁移 25→26 为表加列
- **原文**：
  > 修复版：增加容错检查，防止重复添加字段导致崩溃
- **链接**：无（可达性：none）
- **坑的本质**：迁移中直接 ALTER TABLE 加列在字段已存在时崩溃；先 PRAGMA table_info 检查列是否存在再执行
- **置信度**：confirmed

### 21. app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt:559
- **代码上下文**：custom_fields 建表迁移的 catch 分支
- **原文**：
  > 不抛出异常，让迁移继续，避免应用崩溃（Room 会在后续操作中处理不一致性）
- **链接**：无（可达性：none）
- **坑的本质**：迁移内建表/建索引失败若向上抛异常会让应用启动即崩；吞异常让迁移继续，不一致交给 Room 后续处理
- **置信度**：confirmed

### 22. app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt:2439
- **代码上下文**：Room 数据库多进程失效通知
- **原文**：
  > 启用多进程失效通知：IME 跑在 :ime 独立进程，主进程需要感知 IME 进程对数据库的修改（例如最近填充时间戳等）。
- **链接**：无（可达性：none）
- **坑的本质**：IME 运行在独立 :ime 进程共用同一 Room 库，不开 enableMultiInstanceInvalidation 则跨进程写入对主进程不可见、读到陈旧数据
- **置信度**：confirmed

### 23. app/src/main/java/takagi/ru/monica/security/SecurityManager.kt:1083
- **代码上下文**：口令解锁成功后刷新 Keystore 包装的 MDK
- **原文**：
  > if the user recently passed biometric auth, Android may otherwise allow writing a fresh AUTH wrapper that still fails on some devices on the next biometric-only app unlock.
- **链接**：无（可达性：none）
- **坑的本质**：刚通过生物认证时写入的新 AUTH 包装密钥在部分设备下次纯生物解锁时仍会失败；改为口令解锁成功即直接重写兼容包装
- **置信度**：confirmed

### 24. app/src/main/java/takagi/ru/monica/security/SecurityManager.kt:1400
- **代码上下文**：旧版无前缀 V1 字段密文的解密兼容路径
- **原文**：
  > Historical unprefixed V1 payloads were derived from this predictable value. Keep it read-only so old local data can be opened and migrated.
- **链接**：无（可达性：none）
- **坑的本质**：历史 V1 密文用可预测值（masterKey 字符串截断 32 字节）作 AES 密钥，属已知弱点；仅保留只读解密供旧数据迁移，不用于新写入
- **置信度**：confirmed

### 25. app/src/main/java/takagi/ru/monica/ui/haptic/HapticFeedbackHelper.kt:76
- **代码上下文**：触觉反馈统一开关封装
- **原文**：
  > 与振动出口共用同一开关，否则 API 30+ 的 performHapticFeedback 路径会绕过设置继续触发。
- **链接**：无（可达性：none）
- **坑的本质**：View.performHapticFeedback 与 Vibrator 是两条独立路径，前者不受应用设置约束；必须共用同一开关否则用户关不掉
- **置信度**：confirmed

### 26. app/src/main/java/takagi/ru/monica/bitwarden/api/BitwardenApiFactory.kt:85
- **代码上下文**：Bitwarden/Vaultwarden 客户端 OkHttpClient 构造
- **原文**：
  > 添加 Keyguard 使用的 Cloudflare 绕过 headers（User-Agent / Keyguard-Client / Sec-Ch-Ua 等）
- **链接**：无（可达性：none）
- **坑的本质**：自建服务器在 Cloudflare 后会拦截默认 UA 的 API 请求；伪装成 Keyguard 客户端 headers 绕过 bot 防护才能同步
- **置信度**：confirmed

### 27. app/src/main/java/takagi/ru/monica/attachments/executor/BitwardenAttachmentExecutor.kt:261
- **代码上下文**：Bitwarden 云端附件下载
- **原文**：
  > Bitwarden attachment URLs are short-lived. Always ask the cipher endpoint for fresh download metadata first; the URL cached from /sync is only a compatibility fallback.
- **链接**：无（可达性：none）
- **坑的本质**：附件下载 URL 是短时效签名链接，用 /sync 缓存的旧 URL 会下载失败；每次先向 cipher 端点取新鲜元数据
- **置信度**：confirmed

### 28. app/src/main/java/takagi/ru/monica/repository/Mdbx2ExternalStorage.kt:230
- **代码上下文**：MDBX2 外部附件目录刷新切换
- **原文**：
  > A source without a sidecar is authoritative. Do not leave stale local attachment blobs from an older revision attached to the newly refreshed vault.
- **链接**：无（可达性：none）
- **坑的本质**：无 sidecar 元数据的远端源视为权威；刷新后必须清掉本地旧修订附件 blob，否则陈旧附件错挂到新库上
- **置信度**：confirmed

### 29. app/src/main/java/takagi/ru/monica/repository/Mdbx2RemoteSyncCoordinator.kt:479
- **代码上下文**：Rust 引擎 apply 成功后的检查点落盘时序
- **原文**：
  > Persist that checkpoint immediately so a later Blob transfer failure cannot make the received change look like a new local change and echo it back out.
- **链接**：无（可达性：none）
- **坑的本质**：Rust apply 已推进本地引擎，若不立即持久化检查点，后续 Blob 传输失败会让收到的变更被误判为本地新变更回传远端
- **置信度**：confirmed

### 30. app/src/main/java/takagi/ru/monica/repository/Mdbx2RemoteSyncCoordinator.kt:491
- **代码上下文**：远端同步游标的确认时序
- **原文**：
  > Do not acknowledge the remote cursor until every Blob referenced by the applied segment is present.
- **链接**：无（可达性：none）
- **坑的本质**：段引用的 Blob 未全部落地前不得推进远端游标；中断后旧游标触发幂等重放并续传同一 Blob，保证不丢附件
- **置信度**：confirmed

### 31. app/src/main/java/takagi/ru/monica/autofill_ng/WifiAutofillAssist.kt:33
- **代码上下文**：Wi-Fi 密码填充的托管包名识别清单
- **原文**：
  > "com.miui.securitycenter", // MIUI Wi-Fi 有时托管在安全中心
- **链接**：无（可达性：none）
- **坑的本质**：MIUI 上 Wi-Fi 凭据界面有时托管在安全中心应用而非设置内；按包名清单扩展识别才能命中填充场景
- **置信度**：suspected

### 32. documentation/website/public/data/github-data.json:95
- **代码上下文**：浏览器扩展 WebDAV 备份传输（提交信息存档数据）
- **原文**：
  > fix: 使用 Base64 编码优化 WebDAV 二进制数据传输——问题：ArrayBuffer 转 number[] 导致内存溢出，10MB ZIP ~80MB 内存，超过扩展内存限制导致崩溃和重载
- **链接**：无（可达性：none）
- **坑的本质**：扩展经 message passing 传二进制用 number[] 数组，10MB ZIP 膨胀到约 80MB 内存崩掉扩展；改 Base64 编码并兼容旧格式
- **置信度**：confirmed

### 33. Monica for Android/fastlane/metadata/android/en-US/changelogs/13.txt:4
- **代码上下文**：F-Droid 构建更新说明（扫码引擎换 ZXing）
- **原文**：
  > QR scanning rewritten on ZXing: fixed portrait camera scanning, gallery scanning with dual-binarizer fallback and high-resolution retry
- **链接**：无（可达性：none）
- **坑的本质**：原扫码（ML Kit 被移除的 FOSS 构建语境）存在竖屏相机无法扫描、相册图识别失败缺陷；改 ZXing 并加双二值化回退与高分辨率重试
- **置信度**：confirmed

### 34. Monica for Android/docs/autofill/INTEGRATION_CHECKLIST.md:411
- **代码上下文**：自动填充已知问题清单（项目文档）
- **原文**：
  > 华为 HarmonyOS 不支持内联建议（已降级）……部分 MIUI 11 设备内联建议不稳定（已降级）
- **链接**：无（可达性：none）
- **坑的本质**：HarmonyOS 不支持内联建议、部分 MIUI 11 不稳定，均已按 ROM 降级为下拉菜单模式；属厂商兼容性已知缺陷
- **置信度**：confirmed

## 二、对照行（34 条）

### 1. MonicaCredentialProviderService.kt:85 → CredentialPendingIntents.kt:54
- **坑的本质**：给 Credential Provider 的 PendingIntent 加 NEW_TASK 会让 Activity 落在错误任务栈，无法正确返回调用方应用
- **触发条件**：构建 Credential Provider 响应 PendingIntent 时手动加 NEW_TASK 标志
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt:54`
- **对照情况**：功能相似：同为 CM 提供者响应 PendingIntent 的标志位契约单点
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 2. PasskeyAuthActivity.kt:477 → PasskeyAssertionActivity.kt:240 ⚠ risk=yes
- **坑的本质**：同步型 passkey 无实时双向同步，signCount 单调递增跨设备/恢复后必然分叉，RP 单调性校验（new ≤ stored 判克隆嫌疑）拒签
- **触发条件**：同一 passkey 在多设备（或经同步/恢复的副本）上继续签名
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt:240`（另见合并面 sync/src/main/java/com/keepasskey/sync/merge/KdbxEntryMerger.kt:341-379，冲突取 LMT 在 :373）
- **对照情况**：同类模式：同为可同步库内承载 passkey 并真实维护单调递增计数器（自产凭据 signCount=0 起步 PasskeyKeyGeneration.kt:81，断言前原子递增落库 PasskeyAssertionActivity.kt:240 / core PasskeyData.kt:398-410 readSignCount/nextSignCount；条目合并时 SignCount 走自定义字段按最后修改时间取胜方，无数值 max 特例）
- **是否有同样风险**：**yes**
- **建议**：不可照搬 Monica 的「恒写 0」——主项目 PD-49 裁决一已用真实 RP 实测否证该方案（stored>0 时每次断言都落嫌疑区，open-passkey 直接判失败，见 docs/architecture/产品裁决登记.md PD-49）。但已知工程限界 §34（docs/architecture/已知工程限界.md:860-878）只登记了**导入**凭据的计数器限界，其 ③「自产凭据不受影响」仅对单设备成立：本仓支持 WebDAV/S3 同库多设备，两台设备各自离线断言后，SignCount 按最后修改时间合并（KdbxEntryMerger.kt:373）会使 RP 收到重复/回退计数。建议：① 把「同库多设备使用自产通行密钥的计数器分叉」登记进已知工程限界表（现无此条）；② 评估在 KdbxEntryMerger 自定义字段合并处对 Passkey.SignCount 特例取两侧数值 max（保证合并后单调不减、缩小分叉窗口；同值重复仍无法根除，属产品裁决面）。可行性已复核：字段键常量 FIELD_SIGN_COUNT 在 core（core/src/main/java/com/keepasskey/core/model/PasskeyData.kt:220），sync 依赖 core（sync/build.gradle.kts:28）不违反单向纪律；该字段 isProtected=false（PasskeyEntryCoordinator.kt:382），取 max 只触非敏感整数；max 特例只加「双侧均改」分支且仍记 diffFields
- **需补充信息**：（无）

### 3. CredentialProviderRefreshReceiver.kt:15 → 无直接对应
- **坑的本质**：部分厂商 ROM 在程序化翻转组件开关时会作废用户已选的凭据提供者，用户须手动重进系统设置重选
- **触发条件**：升级/启动时用 setComponentEnabledSetting 强切服务开关
- **主项目对应位置**：无直接对应
- **对照情况**：无：全仓（app/database/crypto/sync/core）grep `setComponentEnabledSetting|COMPONENT_ENABLED_STATE` 零命中，无任何程序化组件开关路径
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 4. KeePassKdbxService.kt:505 → DatabaseSession.kt:51
- **坑的本质**：kotpass 解码库并发调用触发 native 崩溃，线程切换加剧；用全局互斥 + 固定单线程执行器双重复规避
- **触发条件**：多协程/多实例并发 decode .kdbx 文件
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt:51`
- **对照情况**：同类模式（同场景不同实现）：同为 .kdbx 解码的并发控制，但主项目不用 kotpass，是自有 Kotlin+Rust 解析器
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 5. KeePassKdbxService.kt:1319 → SessionContentMutations.kt:172
- **坑的本质**：移动操作必须先写目标再清理源；若先删源而目标未落盘，中途失败即丢数据；重试按目标 UUID 判重防重复插入
- **触发条件**：目标写入成功但源清理失败的分裂状态
- **主项目对应位置**：`database/src/main/java/com/keepasskey/database/session/SessionContentMutations.kt:172`
- **对照情况**：同类模式：同为「移动条目/分组」的写入顺序问题
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 6. KeePassPasskeyUpdateExecutor.kt:54 → 无直接对应
- **坑的本质**：跨库移动若先删 KDBX 源而目标库/Room 投影未接受，失败即永久丢 passkey；先接受后删，失败可从重复态安全重试
- **触发条件**：passkey 从 KDBX 迁出且旧条目为受管状态
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目 passkey 只存 kdbx 单一形态，无 Room/第二活库投影；子库为只读挂载（app/src/main/java/com/keepasskey/app/data/childdb/ChildDatabaseSessionManager.kt:47），无跨库条目搬移 API；导入（CXF/.passkey）是一次性复制非迁移
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 7. MainActivity.kt:1050 → 无直接对应
- **坑的本质**：部分设备上 Compose lookahead 与共享转场顺序错乱直接闪退；紧急安全模式置空 LocalSharedTransitionScope 降级
- **触发条件**：受影响设备/构建上渲染含共享转场的界面
- **主项目对应位置**：无直接对应
- **对照情况**：无：app/src/main 全域 grep `SharedTransition|lookahead|Lookahead` 零命中，未启用任何 lookahead/共享转场
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 8. MainActivity.kt:954 → KeePasskeyApp.kt:349
- **坑的本质**：认证状态异步变化时动态改 startDestination 会与导航图组建竞态；固定起始目的地，后续变化交 LaunchedEffect 处理
- **触发条件**：认证状态在 NavHost 组建期间发生变化
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt:349`
- **对照情况**：功能相似：同为 NavHost 起始目的地选择
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 9. TimelineScreen.kt:1482 → 无直接对应
- **坑的本质**：Android 14+ 上 AnimatedVisibility 与 lookahead 布局竞态闪退；SDK≥34 直接渲染内容、跳过动画
- **触发条件**：Android 14+ 设备上显示/隐藏时间线内容
- **主项目对应位置**：无直接对应
- **对照情况**：API 相似（app/src/main 有 4 处 AnimatedVisibility 常规使用）但无竞态前提：全仓未启用 lookahead/共享转场，也无 ModalBottomSheet 调用点（SecureDialog.kt:119 盘点）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 10. BottomSheetStability.kt:31 → 无直接对应
- **坑的本质**：同一帧内 ModalBottomSheet 与 AnimatedVisibility 都修改布局时 Android 14+ 间歇性抖动/崩溃；高版本跳过动画
- **触发条件**：Android 14+ 弹层与动画同帧变更布局
- **主项目对应位置**：无直接对应
- **对照情况**：无：全仓无 ModalBottomSheet 调用点（app/src/main/java/com/keepasskey/app/security/SecureDialog.kt:119 盘点注记：仅有 Popup/DropdownMenu 4 处调用点）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 11. UnifiedCategoryFilterBottomSheet.kt:1616 → 无直接对应
- **坑的本质**：ModalBottomSheet 与 AnimatedVisibility 的 Lookahead placement race 触发运行时崩溃；SDK≥34 降级为无动画直接渲染
- **触发条件**：Android 14+ 弹层内动画显隐内容
- **主项目对应位置**：无直接对应
- **对照情况**：同上：无 ModalBottomSheet 面、无 lookahead 面，竞态前提不成立
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 12. CardWalletScreen.kt:1362 → 无直接对应
- **坑的本质**：排序按类型拆成多次写库会多次触发同一表 Flow 失效，拖动结果被中间态回拉；必须合并为一次批量写入
- **触发条件**：拖拽排序后按类型逐个调用 updateSortOrders
- **主项目对应位置**：无直接对应
- **对照情况**：模式相似点仅「批量变更单次落库」：主项目无拖拽排序落库功能（列表排序为查询态枚举 SortOrder，app/src/main/java/com/keepasskey/app/ui/model/../screens/vault/VaultListUiState.kt:16）；条目移动走对话框单次 batchMoveEntries（SessionContentMutations.kt:172，一趟剪枝+单次写回，ISSUE-P3-160）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 13. AutofillDetectionPolicy.kt:67 → AutofillFieldScanner.kt:43
- **坑的本质**：密码框因布局/动画时序不可见时整组字段被丢弃、填充失效；密码 hint 放宽到最低精度仍纳入，账号类保持中精度防误判搜索框
- **触发条件**：影视类等 App 聚焦账号框时密码框尚未可见
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt:43`
- **对照情况**：功能相似：同为自动填充字段可见性准入策略
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 14. MonicaAutofillServiceNg.kt:71 → AutofillTargetFieldResolver.kt:107
- **坑的本质**：跨请求登录字段记忆的缓存回补必须先校验旧 AutofillId 仍在当前 AssistStructure 中，否则注入失效 id 填充失败
- **触发条件**：密码框在新请求中不可见、解析器丢弃登录字段
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt:107`
- **对照情况**：功能相似且同口径：主项目 AutofillLoginFieldMemory（ISSUE-P3-372）回补前校验「记忆的全部键仍在当前结构中才整体回补，任一键失效则整体放弃」
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 15. FilledDataBuilderNg.kt:55 → AutofillSessionGrantStore.kt:127
- **坑的本质**：只看密钥材料可读性判未锁会绕过用户显式锁定；必须同时检查会话状态与可用密钥材料，显式锁定必须立即生效
- **触发条件**：用户显式锁定但 Keystore 材料仍可解密
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillSessionGrantStore.kt:127`
- **对照情况**：同类模式：同为自动填充解锁态/授权判定，但主项目以会话状态（vaultLocked）为权威而非密钥材料
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 16. FillResponseBuilderNg.kt:597 → AutofillDatasetBuilders.kt:465
- **坑的本质**：兼容模式下登录字段密码值可能被系统掩码，据此触发的保存会把掩码值存下来形成低质量保存提示；compat 模式跳过 SaveInfo
- **触发条件**：目标 App 以兼容模式填充且值被掩码
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt:465`
- **对照情况**：功能相似：同为填充响应挂载 SaveInfo（applySaveInfoIfNeeded）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 17. AutofillServiceChecker.kt:172 → AutofillHealthProbe.kt:54 ⚠ risk=yes
- **坑的本质**：部分厂商 ROM 上 AutofillManager 查询延迟或返回空导致误报未启用；回退读 Settings.Secure 的 autofill_service 双路核对
- **触发条件**：部分国产 ROM 上调用 hasEnabledAutofillServices
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillHealthProbe.kt:54`
- **对照情况**：功能相似：同用 AutofillManager.hasEnabledAutofillServices() 单路判定系统启用态，无 Settings.Secure 双路核对（isSystemAutofillServiceEnabled）
- **是否有同样风险**：**yes**
- **建议**：影响面有限（该读数只被设置页 AutofillHealthCard 诊断展示消费，AutofillHealthPolicy 会据此渲染 SYSTEM_NOT_ENABLED 并给修复指引），但 Monica 已确认部分 ROM 返回延迟/空值 ⇒ 健康卡会误报「系统未启用」并把用户引向多余的系统设置跳转。建议在 isSystemAutofillServiceEnabled 内加双路核对：manager 读数为 false 时回退/复核 Settings.Secure.getString(contentResolver, "autofill_service")（Monica 同款），或对单次读数延迟重试后再下结论；至少在 AutofillHealthPolicy 文案上注明单源判定。
- **需补充信息**：（无）

### 18. DeviceUtils.kt:330 → AutofillInlinePresentationFactory.kt:65
- **坑的本质**：国产 ROM 内联建议兼容性差：MIUI/HyperOS、OriginOS 需 Android 12+，HarmonyOS 直接禁用；按 ROM 类型分级降级
- **触发条件**：MIUI/HyperOS/ColorOS/OriginOS/HarmonyOS 设备请求内联建议
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillInlinePresentationFactory.kt:65`
- **对照情况**：功能相似：同为 IME 内联建议支持判定与降级
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 19. AppIconCache.kt:52 → InstalledAppsCatalog.kt:115
- **坑的本质**：AdaptiveIconDrawable 等不指定尺寸的 toBitmap 转换会崩溃（另可 OOM）；固定尺寸转换并捕获 OOM/异常兜底 null
- **触发条件**：加载 AdaptiveIconDrawable 类型的应用图标
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/apps/InstalledAppsCatalog.kt:115`
- **对照情况**：功能相似：同为第三方应用图标加载转换（另 safeIcon 同型，同文件 :144）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 20. PasswordDatabase.kt:497 → 无直接对应
- **坑的本质**：迁移中直接 ALTER TABLE 加列在字段已存在时崩溃；先 PRAGMA table_info 检查列是否存在再执行
- **触发条件**：升级路径上目标字段已存在（重复迁移）
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目不使用 Room（全仓 rg `androidx.room|RoomDatabase|enableMultiInstanceInvalidation` 仅 proguard-rules.pro 一处混淆配置命中），无 Room 迁移面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 21. PasswordDatabase.kt:559 → 无直接对应
- **坑的本质**：迁移内建表/建索引失败若向上抛异常会让应用启动即崩；吞异常让迁移继续，不一致交给 Room 后续处理
- **触发条件**：迁移过程中建表 SQL 执行失败
- **主项目对应位置**：无直接对应
- **对照情况**：无：同上，主项目无 Room 迁移面（持久化为自有 kdbx 格式）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 22. PasswordDatabase.kt:2439 → 无直接对应
- **坑的本质**：IME 运行在独立 :ime 进程共用同一 Room 库，不开 enableMultiInstanceInvalidation 则跨进程写入对主进程不可见、读到陈旧数据
- **触发条件**：IME 进程写库、主进程同时读库
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 Room、无多进程（AndroidManifest 无 android:process 声明），自动填充与主逻辑同进程，不存在跨进程数据库失效通知面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 23. SecurityManager.kt:1083 → BiometricCredentialStorage.kt:84
- **坑的本质**：刚通过生物认证时写入的新 AUTH 包装密钥在部分设备下次纯生物解锁时仍会失败；改为口令解锁成功即直接重写兼容包装
- **触发条件**：密码解锁后刷新 Keystore AUTH 包装密钥
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/security/BiometricCredentialStorage.kt:84`
- **对照情况**：同类模式（同 API 用法）：同为 Keystore 包装的快速解锁凭据 + 生物识别授权
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 24. SecurityManager.kt:1400 → PasskeyAssertionPayload.kt:87
- **坑的本质**：历史 V1 密文用可预测值作 AES 密钥属已知弱点；仅保留只读解密供旧数据迁移，不用于新写入
- **触发条件**：解密无版本前缀的历史 V1 密文字段
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionPayload.kt:87`
- **对照情况**：功能相似：同为「历史 v1 旧形态只读回退解密」兼容路径
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 25. HapticFeedbackHelper.kt:76 → Haptics.kt:27
- **坑的本质**：View.performHapticFeedback 与 Vibrator 是两条独立路径，前者不受应用设置约束；必须共用同一开关否则用户关不掉
- **触发条件**：用户在设置中关闭触觉但界面仍走 View 路径
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/components/Haptics.kt:27`
- **对照情况**：功能相似：同为触觉反馈统一开关封装
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 26. BitwardenApiFactory.kt:85 → 无直接对应
- **坑的本质**：自建服务器在 Cloudflare 后会拦截默认 UA 的 API 请求；伪装成 Keyguard 客户端 headers 绕过 bot 防护才能同步
- **触发条件**：服务器前置 Cloudflare 且拦截非浏览器 UA
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 Bitwarden API 客户端；Bitwarden 仅作为**离线 JSON 导入源**（app/src/main/java/com/keepasskey/app/data/importer/，字符串资源 dbset_src_bitwarden），无任何 Bitwarden 网络请求面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 27. BitwardenAttachmentExecutor.kt:261 → 无直接对应
- **坑的本质**：附件下载 URL 是短时效签名链接，用 /sync 缓存的旧 URL 会下载失败；每次先向 cipher 端点取新鲜元数据
- **触发条件**：缓存的下载 URL 过期后仍被使用
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 Bitwarden 云附件下载面；附件随 kdbx 内嵌存储，导入器只读源文件
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 28. Mdbx2ExternalStorage.kt:230 → 无直接对应
- **坑的本质**：无 sidecar 元数据的远端源视为权威；刷新后必须清掉本地旧修订附件 blob，否则陈旧附件错挂到新库上
- **触发条件**：刷新附件目录时源端不存在 sidecar
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无 MDBX2 外部附件目录 + sidecar 元数据机制（附件在 kdbx 内），无此面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 29. Mdbx2RemoteSyncCoordinator.kt:479 → SyncEngine.kt:480
- **坑的本质**：Rust apply 已推进本地引擎，若不立即持久化检查点，后续 Blob 传输失败会让收到的变更被误判为本地新变更回传远端
- **触发条件**：apply 成功后的 Blob 传输中断
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncEngine.kt:480`
- **对照情况**：同类模式（同为「本地已接受远端内容 vs 检查点/基线未前移」的时序问题），但主项目无 Blob 分段协议，为整文件三哈希状态机
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 30. Mdbx2RemoteSyncCoordinator.kt:491 → SyncEngineAdoptionSettlement.kt:26
- **坑的本质**：段引用的 Blob 未全部落地前不得推进远端游标；中断后旧游标触发幂等重放并续传同一 Blob，保证不丢附件
- **触发条件**：应用段后 Blob 传输被打断
- **主项目对应位置**：`sync/src/main/java/com/keepasskey/sync/engine/SyncEngineAdoptionSettlement.kt:26`
- **对照情况**：同类模式（同上）：主项目以「延迟结算」对应「游标确认时序」
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 31. WifiAutofillAssist.kt:33 → WifiFillBoostPolicy.kt:21 ⚠ risk=unclear
- **坑的本质**：MIUI 上 Wi-Fi 凭据界面有时托管在安全中心应用而非设置内；按包名清单扩展识别才能命中填充场景
- **触发条件**：MIUI 设备在安全中心内查看/输入 Wi-Fi 密码
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/WifiFillBoostPolicy.kt:21`
- **对照情况**：功能相似：同为 Wi-Fi 填充的托管包名识别清单（主项目该清单即吸收 Monica WifiAutofillAssist 落地，ISSUE-P3-373），但清单未含 com.miui.securitycenter 等 OEM 专属包名
- **是否有同样风险**：**unclear**
- **建议**：（待实测后确定，见需补充信息）
- **需补充信息**：需要确认目标用户群是否有 MIUI/HyperOS 及 OPPO/OnePlus/ColorOS 设备并实测其 Wi-Fi 密码输入界面的宿主包名：① 是否为 com.miui.securitycenter（Monica 侧该条本身 confidence=suspected）；② Monica 清单另含 com.oneplus.settings / com.oppo.settings / com.coloros.settings，与主项目 WifiFillBoostPolicy.kt:17-18 KDoc「ColorOS / OneUI / OriginOS 宿主均为 com.android.settings」的断言相抵，须一并实测核对。主项目影响面仅「Wi-Fi 场景排序加成不生效」，不改准入，代价低。若实测存在，在 WifiFillBoostPolicy.WIFI_SETTINGS_PACKAGES（:21）追加命中包名，并同步更正其 KDoc 中「主流 ROM 宿主均为 com.android.settings」的断言

### 32. github-data.json:95 → 无直接对应
- **坑的本质**：扩展经 message passing 传二进制用 number[] 数组，10MB ZIP 膨胀到约 80MB 内存崩掉扩展；改 Base64 编码并兼容旧格式
- **触发条件**：扩展拉取大体积备份 ZIP
- **主项目对应位置**：无直接对应
- **对照情况**：无：主项目无浏览器扩展与 message-passing 二进制传输面
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 33. changelogs/13.txt:4 → TotpScanDialog.kt:363
- **坑的本质**：原扫码存在竖屏相机无法扫描、相册图识别失败缺陷；改 ZXing 并加双二值化回退与高分辨率重试
- **触发条件**：竖屏扫码或从相册选图识别二维码
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt:363`
- **对照情况**：功能相似：同为二维码扫描（TOTP/通行密钥导入）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

### 34. INTEGRATION_CHECKLIST.md:411 → AutofillInlinePresentationFactory.kt:31
- **坑的本质**：HarmonyOS 不支持内联建议、部分 MIUI 11 不稳定，均已按 ROM 降级为下拉菜单模式；属厂商兼容性已知缺陷
- **触发条件**：HarmonyOS / MIUI 11 设备请求内联建议
- **主项目对应位置**：`app/src/main/java/com/keepasskey/app/autofill/AutofillInlinePresentationFactory.kt:31`
- **对照情况**：功能相似：同为内联建议的按条件降级（主项目按用户开关 + 构建失败降级，无 ROM 白名单）
- **是否有同样风险**：no
- **建议**：（无）
- **需补充信息**：（无）

## 三、复核记录（独立复核结论）

1. **【SignCount 行 ourLocation 行号修正 + advice 可行性证据补强】**：原 ourLocation 写 PasskeyAssertionActivity.kt:239 与 KdbxEntryMerger.kt:340：实读确认 :239 是 ISSUE-P3-27 注释行、原子递增落库调用在 :240；KdbxEntryMerger.kt:340 是 mergeCustomFields 的 KDoc、函数体 :341-379、冲突按最后修改时间取胜方在 :373。性质与 risk=yes 维持不变。advice 补三条已核实证据：①依赖合法性——SignCount 字段键常量 FIELD_SIGN_COUNT 在 core 模块（core/src/main/java/com/keepasskey/core/model/PasskeyData.kt:220），sync 已依赖 core（sync/build.gradle.kts:28 implementation(project(":core"))），故在 KdbxEntryMerger 做该特例合并不违反模块单向纪律（sync→core 为既有合法方向）；②秘密纪律——该自定义字段以 isProtected=false 明文形态落库（app/src/main/java/com/keepasskey/app/data/repository/PasskeyEntryCoordinator.kt:382），合并且取 max 只触碰非敏感整数，无需物化任何受保护字段；③实施口径——max 特例只应加在「双侧均改且值不同」分支取 max(local, remote)，单侧改分支取改侧本就相对 base 单调，且仍须按 ISSUE-P2-281 惯例把该键记入 diffFields 留痕。
2. **【WifiFillBoostPolicy 行 needInfo 扩展】**：复核时实读 Monica WifiAutofillAssist.kt 包名清单（参考项目/Monica-main/.../autofill_ng/WifiAutofillAssist.kt:25-38），除行内已提的 com.miui.securitycenter 外，清单还含 com.oneplus.settings / com.oppo.settings / com.coloros.settings 三个 OEM 专属设置包——这与主项目 WifiFillBoostPolicy.kt:17-18 KDoc「主流 ROM（AOSP / MIUI / ColorOS / OneUI / OriginOS）的 Wi-Fi 面板宿主均为 com.android.settings」的断言直接相抵（Monica 认为 ColorOS/OneUI/OPPO 宿主是独立包名）。故 unclear 范围应从「仅 MIUI 宿主包名」扩大为「MIUI + OPPO/OnePlus/ColorOS 三系宿主包名」，实测时一并核对；KDoc 更正义务同理扩大。
3. **【抽查通过，未改动】**：抽查两条 risk=no 行均属实：①CredentialPendingIntents 行——实读 app/src/main/java/com/keepasskey/app/passkey/CredentialPendingIntents.kt:54，ENTRY_FLAGS = FLAG_MUTABLE or FLAG_UPDATE_CURRENT，确无 NEW_TASK/新任务栈标志，risk=no 成立；②DatabaseSession 并发行——实读 database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt:51，private val mutex = Mutex()，.kdbx 解码并发控制存在且实现与 kotpass 互斥方案无关（自有解析器），risk=no 成立。
4. **【AutofillHealthProbe 行复核通过，未改动】**：risk=yes 三要素全部实读证实：①单路判定——AutofillHealthProbe.kt:54 仅 AutofillManager.hasEnabledAutofillServices() 一路、无 Settings.Secure 复核；②Monica 侧兜底确凿——参考项目 AutofillServiceChecker.kt:172-175 注释「兜底路径：Settings.Secure（部分 ROM 上 manager 返回会延迟/空值）」并实读 Settings.Secure.getString(resolver, "autofill_service")；③影响面定性准确——grep 全部消费点，probe 读数仅经 AutofillHealthViewModel.kt:30 进设置页 AutofillHealthCard.kt:134（SYSTEM_NOT_ENABLED → 跳系统设置 Intent）/:166（文案渲染），不参与填充运行时链路。advice（双路核对或延迟重试）可行且不涉敏感数据纪律；risk=yes 维持。
