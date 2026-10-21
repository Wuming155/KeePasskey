package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlin.reflect.KProperty

/**
 * 同步配置表单的**草稿承载**——解决「晚到的水合把用户正在输入的内容覆盖掉」。
 *
 * ## 为什么不能直接用 `remember(uiState.xxx)`
 *
 * 原写法以 `uiState` 的字段值为 `remember` 的 key：值一变，Compose 就把本地状态**重建**为
 * 新值。而 `SettingsSyncController.hydrate()` 是在页面组合**之后**从 IO 线程异步解密并回灌
 * 已保存配置的，于是出现确定性竞态——用户在回灌落地前输入的任何内容都会被整段丢弃。
 * 真实表单上表现为「刚敲完端点，一眨眼变回坚果云默认地址」，「保存」随后提交的是被覆盖后的值。
 * `ISSUE-P2-651` 的设备用例正是撞上了这条竞态：断言「http 端点应被拒绝」却拿到
 * 「https 保存成功」，因为输入已被上一次运行留下的已保存配置替换。
 *
 * ## 语义
 *
 * - **播种**（[syncFrom]）：把 `uiState` 视为外部真值源，字段**未被用户编辑过**时跟随它；
 * - **用户编辑**（[stringField] / [booleanField] 的 setter）：一旦写入就记为「已编辑」，
 *   此后任何晚到的播种都不再覆盖——草稿归用户；
 * - **保存成功后放权**（[resetEdits]）：保存成功意味着落盘值已与表单一致（端点还被归一化），
 *   此时解除「已编辑」标记，让归一化后的外部值重新接管显示（与旧行为逐字一致）。
 *
 * 失败保存**不**解除标记（`SyncFormPersistence` 只在成功时回调 `onFieldWiped`），
 * 因此「只改端点、不重输秘密」的重试链路依旧成立。
 *
 * ## 秘密字段也必须提升到页面级
 *
 * 密码 / AccessKey / SecretKey 原本以 `remember { CharArray(0) }` 存在 `CloudSyncScreen` 组合内，
 * 但表单所在的 `LazyColumn` item 滚出视口会被**回收**：`SecurePasswordField` 的
 * `DisposableEffect.onDispose` 随即擦除自身显示态。用户填好密码、滚到下方看状态卡片再滚回来，
 * 密码框就空了——提交随即以空口令失败（`ISSUE-P2-651` 设备用例实证）。
 * 故三个秘密草稿一并由本类持有：item 回收不再丢草稿，只有**离页**（页面组合销毁）才擦除。
 */
internal class SyncFormDrafts {

    private val values = mutableStateMapOf<String, String>()
    private val edited = mutableSetOf<String>()
    private val secrets = mutableStateMapOf<String, CharArray>()

    /** 取秘密草稿的可观察副本（不可变引用，内容归本类所有；调用方不得原地改写）。 */
    fun secret(key: String): CharArray = secrets[key] ?: CharArray(0)

    /** 写入秘密草稿：先擦旧数组再存新副本，避免旧明文在内存里滞留。 */
    fun setSecret(key: String, value: CharArray) {
        secrets[key]?.fill('0')
        secrets[key] = value.copyOf()
    }

    /** 清空秘密草稿（保存成功后由 `SyncFormPersistence` 回调触发）。 */
    fun clearSecret(key: String) {
        secrets[key]?.fill('0')
        secrets[key] = CharArray(0)
    }

    /** 离页擦除全部秘密草稿（页面组合销毁时调用）。 */
    fun wipeSecrets() {
        secrets.values.forEach { it.fill('0') }
        secrets.clear()
    }

    /** 用 `uiState` 播种尚未被用户编辑的字段（幂等：同值写入不触发重组）。 */
    fun syncFrom(uiState: SettingsUiState) {
        seed(KEY_WEBDAV_URL, uiState.webdavUrl.ifBlank { com.keepasskey.app.sync.WebDavDefaults.NUTSTORE_URL })
        seed(KEY_WEBDAV_USERNAME, uiState.webdavUsername)
        seed(
            KEY_WEBDAV_REMOTE_PATH,
            uiState.webdavRemotePath.ifBlank { com.keepasskey.app.sync.WebDavDefaults.DEFAULT_REMOTE_PATH }
        )
        seed(KEY_S3_ENDPOINT, uiState.s3Endpoint)
        seed(KEY_S3_BUCKET, uiState.s3Bucket)
        seed(KEY_S3_REGION, uiState.s3Region)
        seed(KEY_S3_OBJECT_KEY, uiState.s3ObjectKey)
        seed(KEY_S3_USE_PATH_STYLE, uiState.s3UsePathStyle.toString())
    }

    /** 保存成功后解除「已编辑」标记，让归一化后的已保存值重新接管显示。 */
    fun resetEdits() {
        edited.clear()
    }

    private fun seed(key: String, value: String) {
        if (key in edited) return
        if (values[key] != value) values[key] = value
    }

    private fun read(key: String): String = values[key].orEmpty()

    private fun write(key: String, value: String) {
        edited += key
        if (values[key] != value) values[key] = value
    }

    /** 文本字段的委托：`var url by drafts.stringField(KEY)`，用法与 `mutableStateOf` 一致。 */
    fun stringField(key: String): StringFieldDelegate = StringFieldDelegate(this, key)

    /** 布尔字段的委托（`usePathStyle` 为开关，不是文本）。 */
    fun booleanField(key: String): BooleanFieldDelegate = BooleanFieldDelegate(this, key)

    internal class StringFieldDelegate(
        private val drafts: SyncFormDrafts,
        private val key: String
    ) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): String = drafts.read(key)

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            drafts.write(key, value)
        }
    }

    internal class BooleanFieldDelegate(
        private val drafts: SyncFormDrafts,
        private val key: String
    ) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            drafts.read(key).toBoolean()

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            drafts.write(key, value.toString())
        }
    }

    internal companion object {
        const val KEY_WEBDAV_URL = "webdavUrl"
        const val KEY_WEBDAV_USERNAME = "webdavUsername"
        const val KEY_WEBDAV_REMOTE_PATH = "webdavRemotePath"
        const val KEY_S3_ENDPOINT = "s3Endpoint"
        const val KEY_S3_BUCKET = "s3Bucket"
        const val KEY_S3_REGION = "s3Region"
        const val KEY_S3_OBJECT_KEY = "s3ObjectKey"
        const val KEY_S3_USE_PATH_STYLE = "s3UsePathStyle"
        const val KEY_WEBDAV_PASSWORD = "webdavPassword"
        const val KEY_S3_ACCESS_KEY = "s3AccessKey"
        const val KEY_S3_SECRET_KEY = "s3SecretKey"
    }
}

/** 页面级草稿单例（随组合存活，离页即丢弃——与 `DisposableEffect` 的擦除口径一致）。 */
@Composable
internal fun rememberSyncFormDrafts(): SyncFormDrafts = remember { SyncFormDrafts() }
