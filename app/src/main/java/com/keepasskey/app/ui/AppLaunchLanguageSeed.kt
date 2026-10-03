package com.keepasskey.app.ui

import android.content.Context
import com.keepasskey.app.data.repository.AppLanguage

/**
 * 应用内语言的**启动种子**——进程内第一个渲染帧就能拿到、且**零 IO** 的语言值。
 *
 * ### 为什么需要它（`ISSUE-P3-459` 续：冷启动首帧闪系统语言）
 *
 * 语言偏好真源是 `SettingsRepository` 的 **DataStore**，而 DataStore 的首次读取
 * 是**异步冷读**（磁盘 IO + protobuf 解析，真机数十至数百毫秒）。三条消费通道
 * 因此都在「首值到达前」只能拿到默认值：
 *
 * 1. Compose 侧 ——[SettingsUiStateProjection] 的 `initialValue = SettingsUiState()`
 *    首帧 `appLanguage` 恒为 `SYSTEM`，[localeFor] 返回 null ⇒ 按**系统 locale** 渲染：
 *    用户上次选了 English、冷启动却先闪一帧中文，数百毫秒后才翻成英文。
 * 2. `StringsProvider` 通道 ——[AppLocaleTracker] 的 [locale] 初值为 null，
 *    [localizedResourcesFor] 回落 `context.resources`（系统 locale），同上闪一帧。
 * 3. 非 UI 装配点 ——[localizedContextForAppLanguage] 同理。
 *
 * 在 `Application.onCreate` 里同步等 DataStore 首值**不可接受**：Hilt 与主线程
 * 都在启动关键路径上，主线程阻塞等冷读有启动期 ANR 风险（`§414` 已为此取舍过一次）。
 * 而 SharedPreferences 走 **mmap 小文件**，冷读 1~5ms、进程内二次读走内存——
 * 于是把语言偏好**镜像**到 SP，进程一起动就同步灌进本种子的内存字段；
 * 此后所有读取都是一次 volatile 读（零 IO、零锁、零竞态）。
 *
 * ### 契约（勿改）
 *
 * - **只做镜像、不做真源**：read 的结果仅供首帧兜底，DataStore 首值到达后一律覆盖它。
 *   任何「以种子覆盖真源」的写法都是错的（会让 SYSTEM 语义失效）。
 * - **写入点唯一**：[RealSettingsRepository.setAppLanguage] 是唯一写语言的地方，
 *   必须同步 [store]，否则镜像会与真源漂移（表现为「切了语言重启又变回去」）。
 * - **缺失即 SYSTEM**：首次启动 / 用户清数据后种子读不到 ⇒ `SYSTEM` ⇒ 跟随系统，
 *   与 DataStore 缺省值一致——**绝不回落到某个具体语言**，否则首帧会先闪一次错误语言。
 * - **缺镜像 ≠ 缺语言**：镜像只在[store]（用户切语言）时写盘，而「用户在旧版本上切过、本版本才
 *   首次启动」时 SP 里根本没有历史镜像（真机取证见 §415）。此场景由 [AppLocaleTracker]
 *   的限时慢路径补种兜住：一次性等一次 DataStore 首值并回灌，之后恒走零 IO 快路径。
 * - `read()` 不接触任何文件，故 JVM 单测可安全调用（恒返回 `SYSTEM`）。
 */
internal object AppLaunchLanguageSeed {

    private const val PREFS_NAME = "keepasskey_launch_seed"
    private const val KEY_LANGUAGE = "app_language"

    /** 进程内已就绪的种子；null 表示尚未 [install]（首次启动的极窄窗口，按 SYSTEM 处理）。 */
    @Volatile
    private var seeded: AppLanguage? = null

    /** 同步读取种子——**零 IO**，供首帧 / 构造期调用（不得改成挂起或走 DataStore）。 */
    fun read(): AppLanguage = seeded ?: AppLanguage.SYSTEM

    /**
     * 种子是否真的有值（`null` = 本进程从未灌过种，且 SP 里也没有历史镜像）。
     *
     * 用于区分**快 / 慢路径**：镜像只在用户切语言时写入（[store]），因此「用户在旧版本上
     * 切过、本版本才首次启动」这类场景镜像并不存在（SP 文件压根没生成过），
     * 此时需由 [AppLocaleTracker] 走一次限时慢路径补种，见该类的 `seedFromSettingsSync`。
     */
    fun readOrNull(): AppLanguage? = seeded

    /**
     * 冷启动灌种：`Application.onCreate` 主线程调用（SP 冷读 1~5ms，早于任何首帧渲染）。
     * 解析失败一律落 `SYSTEM`，与 DataStore 缺省口径一致。
     */
    fun install(context: Context) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        seeded = parseOrNull(raw) ?: AppLanguage.SYSTEM
    }

    /** 写入点（唯一）：同步回内存 + 落 SP；二者都失败也不得阻断调用方的持久化。 */
    fun store(context: Context, language: AppLanguage) {
        seeded = language
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language.name)
                .apply()
        }
    }

    private fun parseOrNull(raw: String?): AppLanguage? = when (raw) {
        AppLanguage.SYSTEM.name -> AppLanguage.SYSTEM
        AppLanguage.ZH_CN.name -> AppLanguage.ZH_CN
        AppLanguage.EN_US.name -> AppLanguage.EN_US
        else -> null
    }
}
