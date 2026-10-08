package com.keepasskey.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale

/**
 * 外壳层语言 / 配置派生纯函数（`§185` 自 `KeePasskeyApp.kt` 平移的续拆：
 * `ISSUE-P3-359` AC④ 给外壳加装全局 Snackbar 宿主后母文件触及 tier2 预算，
 * 按行数分档闸门下沉——包未变，既有引用与测试的 import 零改动）。
 */

/** 语言偏好 → [Locale]；[AppLanguage.SYSTEM] 返回 null 表示「跟随系统、不改写配置」（§185 下沉，可 JVM 单测） */
internal fun localeFor(appLanguage: AppLanguage): Locale? = when (appLanguage) {
    AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.EN_US -> Locale.ENGLISH
    AppLanguage.SYSTEM -> null
}

/** 按 [locale] 改写 [base] 的语言与布局方向；`null` 时返回原样副本（§185：原先两处重复逻辑并为一处） */
internal fun localizedConfigurationOf(base: Configuration, locale: Locale?): Configuration {
    val cfg = Configuration(base)
    if (locale != null) {
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
    }
    return cfg
}

/**
 * 供 `LocalContext` 使用的本地化上下文；`null` 语言时退回原上下文。
 *
 * **必须保住 ContextWrapper 链**（2026-10-02 真机实测 P0）：此前直接返回
 * `context.createConfigurationContext(...)` 派生上下文——其以 ContextImpl 为基座、
 * 无法沿链解回宿主 Activity，应用内语言 ≠ 跟随系统时设置页全部 `hiltViewModel()`
 * 抛 `Expected an activity context`（切英文即闪退，语言偏好落盘后冷启动崩溃循环）。
 * 改为 ContextWrapper 包住 base（Activity），仅覆写 `getResources()` 走本地化
 * Configuration——`stringResource` / `getString` 仍按目标语言解析，Activity 查找不受影响。
 */
internal fun localizedContextOf(context: Context, base: Configuration, locale: Locale?): Context =
    if (locale == null) {
        context
    } else {
        object : ContextWrapper(context) {
            private val localizedResources: Resources =
                context.createConfigurationContext(localizedConfigurationOf(base, locale)).resources

            override fun getResources(): Resources = localizedResources
        }
    }

/**
 * 非 Compose 装配点的本地化上下文快捷派生（`ISSUE-P3-390`：确认页 / 选择器 / TOTP 通知共用）——
 * `appLanguage` 在 DataStore，须挂起读取快照；返回上下文的 `getString` 即按应用内语言取文案。
 * 语言偏好为 SYSTEM 时返回原上下文（跟随系统），与主外壳口径一致。
 */
internal suspend fun localizedContextForAppLanguage(
    context: Context,
    settings: SettingsRepository
): Context = localizedContextOf(
    context,
    context.resources.configuration,
    localeFor(settings.getSettings().first().appLanguage)
)

/**
 * 按 [locale] 派生 [Resources]（`ISSUE-P3-459`：`StringsProvider` 走本函数取串）。
 *
 * **为什么不能直接用 Application context 的 `getString`**：`StringsProviderModule` 的生产绑定注入的是
 * `@ApplicationContext`，而 Application 对象的 resources 在进程创建时即按**系统 locale** 定形，
 * 既不会随应用内语言变更重新配置，也拿不到 [localizedContextOf] 包出来的 Wrapper
 * ⇒ 应用内语言切到 English 后，所有经 `StringsProvider.get()` 的文案**恒为中文**。
 * 派生Resources 逐次按目标 locale 重建，与 UI 侧 `localizedContextOf` 口径完全一致。
 */
internal fun localizedResourcesFor(context: Context, locale: Locale?): Resources =
    if (locale == null) context.resources
    else context.createConfigurationContext(
        localizedConfigurationOf(context.resources.configuration, locale)
    ).resources

/**
 * 应用内语言快照持有者（`ISSUE-P3-459`）。
 *
 * 职责：把 [SettingsRepository] 流里的 `appLanguage` 收敛成一个**可被同步读取**的 [Locale] 字段，
 * 供非 Composable 的取串通道（[StringsProvider] 等）消费——这类调用点没有 Compose 组合树，
 * 拿不到 `LocalContext`，只能读快照。
 *
 * - **冷启动首帧**：[locale] 的**初值优先取 [AppLaunchLanguageSeed]**（`Application.onCreate` 已灌种，
 *   零 IO 即可读到上次生效的语言）⇒ 首帧不再回落系统 locale。此前只靠 `launchIn` 异步回填，
 *   进程刚拉起的最初一帧必然是系统语言（数十毫秒后 Flow 才回填）——这正是 §414 遗留的那次闪动。
 *   之后 DataStore 首值到达仍以它为**权威**覆盖种子（种子只用于首帧兜底，不得反向覆盖真源）。
 * - **慢路径（一次性）**：镜像只在用户切语言时写盘，故「用户在旧版本上切过语言、本版本才首次启动」
 *   时 SP 里根本没有历史镜像（真机取证：冷启动后 `shared_prefs/keepasskey_launch_seed.xml` 不存在），
 *   此时 [seedFromSettingsSync] **限时 200ms** 同步等一次 DataStore 首值，把代价挡在首帧之前，
 *   拿到即回灌种子；此后一律走零阻塞快路径。
 *   §414 刻意回避的「主线程等 DataStore 冷读」之所以这次可做：①**限时 200ms 封顶**，超时按
 *   `SYSTEM` 放行（宁可一帧，不可 ANR，口径不变）；②**仅首次**发生，回灌后永不复现。
 * - **兜底保留**：[snapshot] 在 [locale] 尚未就绪（install 与回填都缺席的极窄窗口）时走一次同步补取
 *   （`Dispatchers.IO`），此时 DataStore 通常已有内存缓存，代价可忽略。
 */
@Singleton
class AppLocaleTracker @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val appContext: Context
) {
    @Volatile
    var locale: Locale? = null
        private set

    init {
        val seeded = AppLaunchLanguageSeed.readOrNull()
        if (seeded != null) {
            locale = localeFor(seeded)
        } else {
            locale = seedFromSettingsSync()
        }
        settings.getSettings()
            .map { it.appLanguage }
            .distinctUntilChanged()
            .onEach { locale = localeFor(it) }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    /**
     * 慢路径补种：SP 无历史镜像时，限时 [SEED_TIMEOUT_MS] 等一次 DataStore 首值并回灌种子。
     * 任何异常 / 超时一律落 `SYSTEM`（与 DataStore 缺省口径一致，**绝不**回落到具体语言，
     * 否则首帧会闪一帧错误语言）；此处**不做**无上限阻塞。
     */
    companion object {
        /** 慢路径补种预算：挡在首帧前的一次性等待上限；超时按 `SYSTEM` 放行（宁可一帧、不可 ANR）。 */
        private const val SEED_TIMEOUT_MS = 200L
    }

    private fun seedFromSettingsSync(): Locale? = runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeout(SEED_TIMEOUT_MS) { settings.getSettings().first().appLanguage }
        }
    }.getOrElse { AppLanguage.SYSTEM }.let { lang ->
        AppLaunchLanguageSeed.store(appContext, lang)
        localeFor(lang)
    }

    /** 同步可取的目标 locale；未就绪时补取一次。 */
    fun snapshot(): Locale? {
        val ready = locale
        if (ready != null) return ready
        locale = runBlocking(Dispatchers.IO) { localeFor(settings.getSettings().first().appLanguage) }
        return locale
    }
}

/**
 * 沿 ContextWrapper 链解回宿主 FragmentActivity（§411 装机走查 P1 修复）。
 *
 * `localizedContextOf` 产出的本地化上下文是 **ContextWrapper 包住 Activity**——
 * Compose `LocalContext.current` 拿到的是 Wrapper 本体，`context as? FragmentActivity`
 * 直接强转必然失败（null）：解锁页全部生物识别入口（自动唤起 / 手动按钮 / 登记）
 * 因此静默 fail-closed，用户表现为「设置了指纹也永远无法使用」。
 * **本仓任何把 LocalContext 强转为 Activity 的代码一律改走本函数**，不得直转。
 */
fun Context?.unwrapToFragmentActivity(): androidx.fragment.app.FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        val candidate = ctx as? androidx.fragment.app.FragmentActivity
        if (candidate != null) return candidate
        ctx = ctx.baseContext
    }
    return null
}
