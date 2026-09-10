# -----------------------------------------------------------------------------
# KeePasskey 生产级 R8 混淆与安全加固规则
# -----------------------------------------------------------------------------

# 1. 基础系统与属性保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-dontnote **
-dontwarn **

# 2. Kotlin 标准库与协程 / AndroidX（TASK-38 收紧）
#    kotlin-stdlib、kotlinx-coroutines、androidx.compose/lifecycle/navigation/biometric
#    均自带 AAR consumer rules，R8 已自动应用；此前的整包 `-keep class ** { *; }`
#    完全禁用了这些库的混淆与无用代码剥离，属过度保留，现全部移除交由官方规则接管。

# 3. Hilt / Dagger 依赖注入
-keep class com.google.dagger.** { *; }
-keep class dagger.** { *; }
-keep class * extends dagger.hilt.internal.UnsafeCasts { *; }
-keep class * extends com.google.crypto.tink.** { *; }

# 5. BouncyCastle 密码学底座 (防范算法提供者反射/类重命名失效)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# 6. OkHttp 与网络传输
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    native byte[] findSuffix(java.lang.String[]);
}
-dontwarn okhttp3.**
-dontwarn okio.**

# 7. KeePasskey 核心敏感数据保护规则 (杜绝清空方法被当作无副作用死代码剥离)
-keep class com.keepasskey.core.security.** {
    public *;
    protected *;
    *** clear();
    *** close();
    *** fill(...);
}
-keep class com.keepasskey.core.model.** { *; }
-keep class com.keepasskey.database.file.** { *; }
-keep class com.keepasskey.database.session.** { *; }

# 8. 系统服务生命周期组件保留
#    包级保留：覆盖 Credential Provider / Autofill 服务、Launcher Activity、
#    DomainMatcher 与 AutofillFieldScanner（系统经 Manifest 反射实例化，混淆即失效）。
-keep class com.keepasskey.app.passkey.** { *; }
-keep class com.keepasskey.app.autofill.** { *; }

# 9. androidx.credentials (Credential Manager Provider API)
#    服务回调与 PendingIntentHandler 结果回传依赖框架反射；consumer 规则缺失时兜底。
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# 10. AndroidX WorkManager 与 Room 反射实例化
#     WorkManager 启动初始化 WorkDatabase_Impl 时通过反射获取无参构造函数，
#     在 R8 优化/剪裁下需显式保留其构造器，防止出现 NoSuchMethodException 导致 InitializationProvider 启动崩溃。
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
    *;
}
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
    *;
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 11. ZXing / 条形码与二维码扫描库
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# 12. 日志剥离（ISSUE-P1-10 / ZT-10）
#     release 直接剥离 verbose/debug 日志调用点（AppLog.v/d 与框架 Log.v/d 双重剥离）；
#     Log.e/Log.w 经 AppLog 包装器在运行期脱敏（仅保留异常类名，不透 message 与堆栈）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
-assumenosideeffects class com.keepasskey.core.log.AppLog {
    public static void v(...);
    public static void d(...);
}

