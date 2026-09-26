package com.keepasskey.app.passkey

import com.keepasskey.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * 扫码 / 相册导入通行密钥的**草案宿主**（`ISSUE-P3-337` 口径 4）：把「字符载荷 → 字节 →
 * 解析器 → 待确认草案」这一段从两个入口（顶栏建条目、编辑页换当前条目）里收拢成一份实现，
 * AC⑤ 的「取景 / 相册实现不允许复制第二份」在草案层面同口径适用。
 *
 * ## 承载纪律
 *
 * - 草案 [PasskeyImportDraft] **只活在内存的本类字段里**：不进 `SavedStateHandle`、不进路由参数、
 *   不进任何 `UiState`（`P2-105` 立的规矩）；
 * - 入参 [CharArray] 归调用方所有，本类**无论成功失败都负责擦除**（它就是私钥明文的上行副本）；
 *   解析用的 UTF-8 工作字节在解析器返回后即刻清零，含编码器内部 `ByteBuffer`（P0-7 同口径）；
 * - 拒收一律只报**静态文案资源 id**（[onNotice]），**不回显载荷**（AC② / 口径 3 末句）。
 *
 * 确认与落库**不在本类**：两条写入口的语义不同（顶栏 `saveNewPasskeyEntry` 落当前分组、
 * 编辑页 `replacePasskeyOnEntry` 挂当前条目），故本类只交出草案（[takeForImport]），
 * 由调用方在**取到后自行擦除**（[takeForImport] 已把字段置空，取消路径走 [dismiss]）。
 *
 * @param isReadOnly 会话只读判据（只读一律硬拒绝，且仍然擦除入参）
 * @param onNotice 静态文案通道（资源 id → 宿主自行包 `UiMessage`，本层不依赖 UI 类型）
 */
internal class PasskeyImportDraftHost(
    private val isReadOnly: () -> Boolean,
    private val onNotice: (Int) -> Unit
) {

    private val pendingFlow = MutableStateFlow<PasskeyImportDraft?>(null)

    /** 非空即「等待用户确认」的草案；宿主据此显示确认对话框。 */
    val pending: StateFlow<PasskeyImportDraft?> = pendingFlow.asStateFlow()

    /**
     * 解码文本 → 解析器 → 草案。
     *
     * @return 是否已进入确认环节（false 表示已按静态文案拒绝，调用方不得再落库）
     */
    fun begin(decoded: CharArray): Boolean {
        if (isReadOnly()) {
            decoded.fill('0')
            onNotice(R.string.readonly_save_rejected)
            return false
        }
        val outcome = try {
            PasskeyCxfReader.read(toUtf8Bytes(decoded))
        } finally {
            decoded.fill('0')
        }
        return when (outcome) {
            is PasskeyCxfOutcome.Rejected -> {
                // 上一份草案若仍未确认，先擦掉再挂新的（同一入口连续扫两次的路径）
                pendingFlow.value?.wipe()
                pendingFlow.value = null
                onNotice(outcome.reason.staticNoticeRes())
                false
            }

            is PasskeyCxfOutcome.Parsed -> {
                pendingFlow.value?.wipe()
                pendingFlow.value = PasskeyImportDraft(outcome.credential, outcome.notes)
                true
            }
        }
    }

    /**
     * 用户点「确认」：交出草案（本类不再持有）。
     *
     * ⚠️ 返回值是私钥明文的**唯一**持有者，调用方负责在成功与失败路径都调 [PasskeyImportDraft.wipe]；
     * 无草案时返回 null（对话框关闭与确认竞态、重复点击），调用方须直接返回。
     * 读 + 置空两步不具原子性：两条入口的调用点都在各自 ViewModel 的单线程派发器上，无并发窗口。
     */
    fun takeForImport(): PasskeyImportDraft? {
        val draft = pendingFlow.value
        pendingFlow.value = null
        return draft
    }

    /** 用户取消：擦除草案、不落库。 */
    fun dismiss() {
        pendingFlow.value?.wipe()
        pendingFlow.value = null
    }

    /** 宿主销毁 / 会话锁定时的兜底擦除（编辑页 `clearAllSecrets` 同批接入）。 */
    fun wipeAll() = dismiss()

    /**
     * CharArray → UTF-8 字节，并把编码器复用的内部 buffer 一并清零。
     *
     * 单独成函数而不是直接 `String.toByteArray()`：后者会留下一份**不可擦**的 String 中间量
     * （§3 敏感数据铁律）。入参归调用方所有，本函数不擦（由 [begin] 的 finally 统一负责）。
     */
    private fun toUtf8Bytes(chars: CharArray): ByteArray =
        CharBuffer.wrap(chars).let { buffer ->
            val utf8 = StandardCharsets.UTF_8.encode(buffer)
            ByteArray(utf8.remaining()).also { out ->
                utf8.get(out)
                if (utf8.hasArray()) utf8.array().fill(0)
            }
        }
}

/**
 * 拒收原因 → 静态文案资源 id（**不拼接任何载荷内容**）。
 *
 * 「载荷本身不合法」的三个码与「仪式字段缺失 / Base64 非法 / 算法不识别 / 非 passkey 凭据」
 * 合并为一条 `passkey_import_failed_invalid`：细分对用户的下一步动作没有差别（都是「换一张码
 * 重扫」），而把细节写进文案就有把载荷内容带上屏的风险；细分判据留在 [PasskeyCxfReject] 里，
 * 由解析器单测逐码断言。
 */
private fun PasskeyCxfReject.staticNoticeRes(): Int = when (this) {
    PasskeyCxfReject.PayloadTooLarge -> R.string.passkey_import_failed_too_large
    PasskeyCxfReject.NoPasskeyCredential -> R.string.passkey_import_failed_no_credential
    PasskeyCxfReject.MissingCeremonyField,
    PasskeyCxfReject.InvalidBase64Url,
    PasskeyCxfReject.UnsupportedKeyAlgorithm,
    PasskeyCxfReject.NestingMismatch,
    PasskeyCxfReject.DocumentVersionUnsupported,
    PasskeyCxfReject.NotPasskeyCredential,
    PasskeyCxfReject.MalformedJson -> R.string.passkey_import_failed_invalid
}
