# -----------------------------------------------------------------------------
# KeePasskey 生产级 R8 混淆与安全加固规则
# -----------------------------------------------------------------------------

# 1. 基础系统与属性保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-dontnote **
-dontwarn **

# 2. Kotlin 标准库与协程
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# 3. AndroidX 与 Material 3 / Compose
-keep class androidx.compose.** { *; }
-keep class androidx.biometric.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }

# 4. Hilt / Dagger 依赖注入
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
