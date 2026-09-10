# -----------------------------------------------------------------------------
# KeePasskey 生产级 R8 混淆与安全加固规则
#
# ISSUE-P3-09（ZT-20）AC2：本文件已按「据实最小保留」原则收窄——每条保留规则都必须能
# 指向真实存在的反射 / 序列化 / 系统契约需求；无法给出依据的整包 `-keep { *; }` 一律
# 降级为「仅保留类名」或删除。收窄前后的逐条差异见 docs/.handoff/ISSUE-P3-09.md。
# -----------------------------------------------------------------------------

# 1. 基础系统与属性保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ISSUE-P3-09（ZT-20）AC2：不再原样保留 SourceFile 属性——release 包与崩溃栈里不再出现
# KdbxFile.kt / PasskeyAssertionActivity.kt 这类真实源文件名（弱化版本差异化信息暴露面），
# 改由 -renamesourcefileattribute 统一改写为常量 "SourceFile"。
# 取舍：LineNumberTable 仍然保留。retrace 必须依赖堆栈行号才能把 mapping.txt 还原到具体
# 代码行，剥离行号会让崩溃排查从「文件:行」退化为「类+方法」，对一个承载密钥材料的应用
# 而言可运维性损失过大。因此这里选择「隐去文件名 + 保住行号」，而非两者都剥离。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontnote **

# ISSUE-P3-09（ZT-20）AC2：原 `-dontwarn **` 全局压制已删除。
# 该规则会让「引用了不存在的类」这一真实的供应链/裁剪风险永久静默（依赖被下架、坐标写错、
# 可选实现类缺失都不再报错）。现改为仅对确有必要的具体缺失类逐条放行（见第 13 节），
# 其余缺失类一律由 R8 直接报错阻断发布构建。

# 2. Kotlin 标准库与协程 / AndroidX（TASK-38 收紧）
#    kotlin-stdlib、kotlinx-coroutines、androidx.compose/lifecycle/navigation/biometric
#    均自带 AAR consumer rules，R8 已自动应用；此前的整包 `-keep class ** { *; }`
#    完全禁用了这些库的混淆与无用代码剥离，属过度保留，现全部移除交由官方规则接管。

# 3. Hilt / Dagger 依赖注入
#    Hilt 生成的组件通过 GeneratedComponent 接口与反射式 EntryPoint 查找协作，
#    `dagger.hilt.EntryPoints` 会按入口点类名反射调用生成组件上的同名方法，
#    故 Dagger/Hilt 侧维持整包保留（现状未纳入本次收窄范围）。
-keep class com.google.dagger.** { *; }
-keep class dagger.** { *; }
-keep class * extends dagger.hilt.internal.UnsafeCasts { *; }

# 4. BouncyCastle 密码学底座 (防范算法提供者反射/类重命名失效)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# 5. OkHttp 与网络传输
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    native byte[] findSuffix(java.lang.String[]);
}
-dontwarn okhttp3.**
-dontwarn okio.**

# 6. KeePasskey 核心敏感数据保护规则 (杜绝清空方法被当作无副作用死代码剥离)
#    core.security 承载 ProtectedString / SecureByteArray 等「驻留加密 + 显式清零」类型，
#    其中的 clear()/close()/fill(...) 一旦被 R8 判定为无副作用并内联消除，秘密材料会
#    在堆中滞留——这是有依据的成员级保留，故本节点名保留（不扩大、不收窄）。
-keep class com.keepasskey.core.security.** {
    public *;
    protected *;
    *** clear();
    *** close();
    *** fill(...);
}

# 7. KDBX 数据模型与文件层（ISSUE-P3-09 / ZT-20 AC2 收窄）
#    收窄前：core.model / database.file / database.session 三个包整体 `-keep { *; }`，
#    包内全部成员（私有实现、临时方法、字段名）被一并冻结，R8 无法内联/裁剪/改名。
#    核实结论（决定「真正需要保留什么」的依据）：
#      a) 全仓无 Gson / Moshi / kotlinx.serialization / java.io.Serializable 等反射式
#         序列化框架；KDBX XML 读写为手写 SAX / DOM（KdbxXmlParser、KdbxXmlWriter），
#         逐字段显式访问，不依赖字段名或注解；
#      b) 全仓无 Class.forName / getDeclaredConstructor / getDeclaredField 等反射实例化
#         （唯二 newInstance() 出现在 SAXParserFactory / DocumentBuilderFactory 工厂，
#         与数据模型类无关）；
#      c) 因此「成员级保留」没有任何技术依据，现全部释放给 R8：成员可裁剪、可内联、可改名。
#    保留策略：仅以 -keepnames 保留类名（允许无用类被收缩移除），目的是满足
#    .codebuddy/rules/engineering-rules.md §防御性安全边界「KDBX 内部序列化模型与核心类
#    必须在混淆规则中保留」的约定，并让 release 崩溃栈仍能读出模型类名。
#    注意：这是工程约定驱动的「名称保留」，不是运行期反射需求——不要据此反推存在反射。
-keepnames class com.keepasskey.core.model.**
-keepnames class com.keepasskey.database.file.**
#    database.session（DatabaseSession / AtomicFileWriter / DirectorySync / PosixDirectorySync）
#    既非序列化模型，也无反射消费方，整包保留已删除：类名与成员均交由 R8 处理。
#    其密钥清零路径（close() → Arrays.fill(...)）由调用方直接调用、具备可观测副作用，
#    不依赖 keep 规则存活（残余风险已如实登记于交接文档）。

# 8. 系统生命周期组件保留（ISSUE-P3-09 / ZT-20 AC2 收窄）
#    收窄前：passkey / autofill 整包 `-keep { *; }`。
#    系统只经 Manifest 反射实例化组件类本身，且要求存在 public 无参构造；包内其余协作类
#    （DomainMatcher、AutofillFieldScanner、CallingOriginResolver、CredentialFillVerifier、
#    DigitalAssetLinksVerifier 等）全部由代码直接引用，改名、内联与合并均安全——
#    实测结果也确实出现「改名 / 合并 / 整体收缩移除」三类，符合预期。
#    事实核对：AGP 已为 Manifest 声明的组件生成等价 keep 规则
#    （app/build/intermediates/aapt_proguard_file/release/processReleaseResources/aapt_rules.txt），
#    本节点名保留属防御纵深（与系统契约相关者显式声明、不依赖构建期生成物）。
#    覆盖清单（Manifest 声明 5 Activity + 1 Service / 2 Activity + 1 Service）：
#      passkey：PasskeyCreateActivity / PasskeyAssertionActivity / PasswordFillActivity /
#               PasswordSaveActivity / CredentialUnlockActivity /
#               KeePasskeyCredentialProviderService
#      autofill：AutofillUnlockActivity / AutofillConfirmActivity / KeePasskeyAutofillService
-keep class com.keepasskey.app.passkey.** extends android.app.Activity { public <init>(); }
-keep class com.keepasskey.app.passkey.** extends android.app.Service { public <init>(); }
-keep class com.keepasskey.app.autofill.** extends android.app.Activity { public <init>(); }
-keep class com.keepasskey.app.autofill.** extends android.app.Service { public <init>(); }

# 9. androidx.credentials (Credential Manager Provider API)
#    服务回调与 PendingIntentHandler 结果回传依赖框架反射；consumer 规则缺失时兜底。
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# 10. WorkManager 内部数据库的反射实例化（ISSUE-P3-09 / ZT-20 AC2 收窄）
#     本项目自身**不使用 Room**（无 androidx.room 依赖、无 @Database/@Entity），但
#     WorkManager 内部用 Room 落地 WorkSpec（androidx.work:work-runtime 传递依赖
#     androidx.room:room-runtime）。room-runtime 2.6.1 的 consumer 规则为
#       -keep class * extends androidx.room.RoomDatabase        ← 仅保留类，不保留成员
#     而 Room 的运行期实例化路径是
#       Room.getGeneratedImplementation → Class.forName("androidx.work.impl.WorkDatabase_Impl")
#                                      → Class.newInstance()（要求 public 无参构造）
#     即「类名 + 无参构造」两者缺一不可，consumer 规则只覆盖了前者。
#     收窄前这里是 17 行（`* extends RoomDatabase` + WorkDatabase_Impl 的三段重复 + ListenableWorker/
#     Worker 构造器），其中：
#       - ListenableWorker / Worker 构造器两条与 work-runtime 2.10.0 自带 consumer 规则
#         （-keepnames / -keepclassmembers public class * extends androidx.work.ListenableWorker）
#         完全重复，已删除；
#       - `* extends RoomDatabase` 一条与 room-runtime consumer 规则重复，已删除；
#     最终只保留这一条「精确到唯一反射目标类」的规则，补上 consumer 规则缺失的构造器保留。
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# 11. ZXing / 条形码与二维码扫描库
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# 12. 日志剥离（ISSUE-P1-10 / ZT-10）
#     release 直接剥离 verbose/debug 日志调用点（AppLog.v/d 与框架 Log.v/d 双重剥离）；
#     Log.e/Log.w 经 AppLog 包装器在运行期脱敏（仅保留异常类名，不透 message 与堆栈），
#     因此**只剥 v/d，不剥 i/w/e**——剥掉 e/w 会让 release 包在故障时彻底失声。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
-assumenosideeffects class com.keepasskey.core.log.AppLog {
    public static void v(...);
    public static void d(...);
}
