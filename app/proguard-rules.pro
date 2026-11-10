# -----------------------------------------------------------------------------
# KeePasskey 生产级 R8 混淆与安全加固规则
#
# ISSUE-P3-09（ZT-20）AC2：本文件按「据实最小保留」原则维护——每条保留规则都必须能
# 指向真实存在的反射 / 序列化 / 系统契约需求；无法给出依据的整包 `-keep { *; }` 一律
# 降级为「仅保留类名」或删除。精确类名与依赖实物的对应关系由 `ProguardRuleRealityTest`
# 校验；规则是否真被 R8 接受、运行期是否仍可用，仍须以真实 release 构建与设备路径验证。
# -----------------------------------------------------------------------------

# 1. 基础系统与属性保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# JNI：本仓 native 符号走「Java_<包>_<类>_<方法>」静态命名（crypto/src/main/rust/src/jni_bridge*.rs），
# 类名与方法名必须稳定；AGP 默认 proguard-android-optimize.txt 已含
# `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`
# （见 app/build/outputs/mapping/release/configuration.txt），本文件不再重复声明。

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
# 可选实现类缺失都不再报错）。现只保留第 4 节 BouncyCastle 的库级放行（JavaSE 上游库），
# 其余缺失类一律由 R8 直接报错阻断发布构建；AGP 默认规则自带的 `-dontwarn androidx.**` 等
# 由 AGP 维护，不计入本文件的放行清单。

# 2. Kotlin 标准库与协程 / AndroidX（TASK-38 收紧）
#    kotlin-stdlib、kotlinx-coroutines、androidx.compose/lifecycle/navigation/biometric
#    均自带 AAR consumer rules，R8 已自动应用；此前的整包 `-keep class ** { *; }`
#    完全禁用了这些库的混淆与无用代码剥离，属过度保留，现全部移除交由官方规则接管。

# 3. Hilt / Dagger 依赖注入（ENG-20261028-24：整包保留已删除，交由依赖自带规则接管）
#    实物核对（2026-10-29；hilt-android 2.60.1 AAR 与 hilt-core / dagger 2.60.1 JAR，javap 反汇编）：
#      a) hilt-android 的 consumer 规则（proguard.txt）已由 AGP 合并，内容为
#         `-keep,allowobfuscation,allowshrinking @dagger.hilt.EntryPoint class *` 等四条；
#         本仓 3 处 @EntryPoint（AutofillDeviceGraph / DriftDeviceGraph / PeriodicSyncWorker）由它覆盖；
#      b) dagger 2.60.1 的 `META-INF/com.android.tools/r8/r8.pro`
#         （-identifiernamestring @dagger.internal.IdentifierNameString class **）同样已被合并；
#         Hilt 处理器另为每个 @HiltViewModel 生成 `-keep,allowobfuscation,allowshrinking class …ViewModel`
#         （见 app/build/intermediates/generated_proguard_file/release/）；
#      c) 反射面实测：hilt-android / hilt-core / dagger 的 class 文件里没有 Class.forName /
#         ClassLoader.loadClass / getDeclared* 调用；`EntryPoints.get` 的实现是
#         `entryPointClass.cast(component)`（直接接口转换），**不是**按入口点类名反射找同名方法
#         ——原注释据此整包保留的机制在 2.60.1 已不存在；
#      d) `dagger.hilt.internal.UnsafeCasts` 是 final 类，生成代码只调用其静态 `unsafeCast(...)`，
#         没有任何类 extends 它 ⇒ 原 `-keep class * extends …UnsafeCasts` 恒不匹配（静默 no-op）。
#    因此 `com.google.dagger.**` / `dagger.**` 的整包 keep 与上述事实矛盾，已全部删除；
#    Dagger/Hilt 侧本仓不再有自定义 keep。

# 4. BouncyCastle 密码学兜底（ENG-20261028-24：整包保留收窄为按名装配的实际目标）
#    实物核对（2026-10-29；bcprov-jdk18on 1.86，javap 反汇编 + 全量类扫描）：
#      a) 轻量级 API（Argon2BytesGenerator、RSADigestSigner / ECDSASigner / Ed25519Signer、
#         PrivateKeyFactory、SECNamedCurves 等）全部由字节码直接引用，改名、内联与收缩安全，
#         不需要任何 keep；
#      b) JCE 面只有两族经 `Cipher.getInstance(…, ChaCha20CipherEngine.bouncyCastleProvider())` 使用：
#         ChaCha7539（ChaCha20CipherEngine）与 Twofish/CBC/…（TwofishCipherEngine、NativeTwofish 兜底）。
#         `BouncyCastleProvider.setup()` 用 `ClassUtil.loadClass(…, "…symmetric." + 名称 + "$Mappings")`
#         反射加载映射类，映射类再把 "…ChaCha$Base7539" / "…Twofish$ECB" 作为**服务类名**登记，
#         之后由 `Provider$Service.newInstance()` → `Class.forName(类名)` 实例化。这些名字只存在于
#         字符串里，R8 无法从字节码推出，必须按名保留类与 public 无参构造；
#      c) 其余算法族的 $Mappings 类加载失败时 `loadServiceClass` 静默跳过
#         （ClassUtil.loadClass 捕获 ClassNotFoundException 返回 null），故其余 org.bouncycastle.**
#         可自由收缩与改名，不需要整包保留。
#    边界：KeyGenerator / AlgorithmParameters / PBE / GMAC 等同一族的其它嵌套类不在本仓调用路径上，
#    不保留；将来新增按名取用的 transformation 时须同批补对应规则。
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha$Base7539 { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Twofish$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Twofish$ECB { *; }
#    BC 是 JavaSE 上游库，其内部 LDAP/CRL 集成引用 Android 不存在的 javax.naming（属可收缩面）；
#    收窄后可达面是否仍触发缺失类告警未经 R8 实测，故保留库级放行
#    （okhttp-android 5.5.0 的 consumer 规则也自带同一条，见 configuration.txt）。
-dontwarn org.bouncycastle.**

# 5. OkHttp 与网络传输
-keepattributes Signature
-keepattributes *Annotation*
# ISSUE-P3-507：此处原有一条
#   -keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
#       native byte[] findSuffix(java.lang.String[]); }
# 已删除。核实（2026-10-06）：本仓实际解析的 okhttp-android 5.5.0 中
# `PublicSuffixDatabase` **无任何 native 方法、无 `findSuffix` 成员**（解包 AAR 后
# `javap -p` 实测 + 官方 sources grep `native|findSuffix` 零命中 + 全仓除本规则外零引用）
# ⇒ 该规则**恒不匹配、静默 no-op**，与本文件头「据实最小保留」原则相悖。
# 删除的零影响面：该类无反射消费方、AAR 自带 consumer 规则也不需要它，
# `-keepclassmembers` 成员模式不匹配时既不保类也不保成员 ⇒ 对产物零影响。
# 回归守卫：`ProguardRuleRealityTest` 逐条校验本文件**精确类名 / 成员签名**在依赖实物中存在。
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
#    保留策略：仅以 -keepnames 保留类名（允许无用类被收缩移除），目的是让 release 崩溃栈与
#    日志仍能读出模型类名（可运维性），不涉及反射契约。
#    注意：这是可运维性驱动的「名称保留」，不是运行期反射需求——不要据此反推存在反射。
-keepnames class com.keepasskey.core.model.**
-keepnames class com.keepasskey.database.file.**
#    database.session（DatabaseSession / AtomicFileWriter / DirectorySync / PosixDirectorySync）
#    既非序列化模型，也无反射消费方，整包保留已删除：类名与成员均交由 R8 处理。
#    其密钥清零路径（close() → Arrays.fill(...)）由调用方直接调用、具备可观测副作用，
#    不依赖 keep 规则存活；「调用方遗漏 close」这一残余风险不由 R8 规则负责。

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

# 9. androidx.credentials (Credential Manager Provider API)（ENG-20261028-24）
#    实物核对（2026-10-29，androidx.credentials:credentials:1.6.0 AAR）：
#      - 自带 proguard.txt 内容为「Intentionally empty proguard rules to indicate this library
#        is safe to shrink」，即上游明确声明可收缩；原注释「consumer 规则缺失」与实物相反；
#      - classes.jar 375 个类中没有 Class.forName / loadClass / getDeclared* 反射入口；
#      - 服务侧回调经框架 android.app.credentials.CredentialProviderService 直接覆写，组件类由
#        Manifest 声明（AGP aapt 规则 + 本文件第 8 节覆盖）。
#    ⇒ 整包 keep 已删除；`-dontwarn androidx.credentials.**` 一并删除——AGP 默认
#    proguard-android-optimize.txt 已含 `-dontwarn androidx.**`（同一 configuration.txt 可见）。

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

# 11. ZXing / 条形码与二维码扫描库（ENG-20261028-24）
#     ISSUE-P3-319：zxing-android-embedded 已移除（其 keep / dontwarn 规则随之删除）；
#     现依赖 com.google.zxing:core 3.5.4（纯算法 JAR，无 consumer 规则）。
#     实物核对（2026-10-29，解包 core-3.5.4.jar 全量扫描 289 个类）：
#      a) 无 Class.forName / loadClass / getDeclared* 反射入口；唯一命中的 `forName` 是
#         `java.nio.charset.Charset.forName`（JDK 字符集，与混淆无关）；
#      b) 解码入口 MultiFormatReader 直接构造各格式 Reader，属字节码可达引用，改名与收缩安全；
#      c) 不含 client/j2se（JavaSE 适配已拆到独立制品），也未检出 java.awt / javax.imageio /
#         javax.naming 等 Android 缺失引用 ⇒ `-dontwarn` 没有对应目标。
#     因此原整包 keep 与本节「无需 keep」的注释自相矛盾，keep 与 dontwarn 均已删除；
#     上游升级若引入缺失引用，会由 R8 直接报错阻断发布构建，不再静默压制。

# 12. 日志剥离（ISSUE-P1-10 / ZT-10；ISSUE-P3-98 修正签名；ENG-20261028-27 诊断栈）
#     release 直接剥离 verbose/debug 日志调用点（AppLog.v/d 与框架 Log.v/d 双重剥离），
#     因此**只剥 v/d，不剥 i/w/e**——剥掉 i/w/e 会让 release 包在故障时彻底失声。
#     `DebugLogBuffer` 的 logcat 取证桥也经 `AppLog.i` 输出**写入时已脱敏**的诊断行，
#     稳定版同样依赖该级别；不得把 i 加入剥离列表。
#     e/w 的 release 形态由 `AppLog`（core/log/AppLog.kt 的 `diagnostic`）统一产出：调用方
#     message + 异常**类型**（含 cause 链的类型行）+ **有界栈帧**（`at 类.方法(文件:行号)`，
#     帧预算 32、cause 链上限 4 层，超出分别标注 `... N more` / `... cause chain truncated`）；
#     **不输出** Throwable message、cause message、suppressed 内容与 `Throwable.toString()` 结果
#     （message 属不可信外部输入）。
#     ⇒ 第 1 节保留的 `SourceFile,LineNumberTable` 与 `-renamesourcefileattribute SourceFile`
#       因此是**诊断链路的一部分**：混淆后的帧必须能经发布 mapping.txt + retrace 还原到方法与行；
#       发布流程须同时归档 mapping.txt，不得改为剥离行号或丢弃映射。
#     ISSUE-P3-98（审计 L1）：`AppLog` 是 Kotlin `object`，其 `v/d` 为**实例方法**
#     （JVM 签名 `public final void v(String, String)`，未标 `@JvmStatic`）。
#     原规则写作 `public static void v(...)` 因此**永不匹配（no-op）**——本批改为实例方法签名。
#     该形态的行为由设备侧用例 `core/src/androidTest/.../log/AppLogDeviceTest.kt` 校验
#     （格式、门控、脱敏与截断）；本文件只负责「剥 v/d、保留 e/w/i」这条打包契约。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
-assumenosideeffects class com.keepasskey.core.log.AppLog {
    public void v(...);
    public void d(...);
}
