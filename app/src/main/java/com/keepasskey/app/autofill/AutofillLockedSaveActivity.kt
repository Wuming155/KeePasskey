package com.keepasskey.app.autofill

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.BaseCredentialActivity
import com.keepasskey.app.passkey.CredentialUnlockPresenter
import com.keepasskey.app.passkey.SaveRequestLedger
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 传统自动填充「库锁定态保存」落地页（`ENG-20261028-21`）。
 *
 * ## 为什么有独立落地页
 * 锁定态保存的系统入口原先指向 Credential Manager 的保存页，口令以明文 extra 随
 * `PendingIntent` 交给系统；本次整改把口令移出系统句柄（见 [LockedSaveRequestPendingIntent]
 * 与 [LockedSavePayloadStore]），因此需要同一条链路上**只按令牌取载荷**的落地页。
 * CM 保存页继续只服务 Credential Manager 的 `CreatePasswordRequest` 通道：两条通道的
 * 结果契约（CM 创建响应 vs 自动填充保存回执）与调用方身份来源本就不同。
 *
 * ## 时序（口令在解锁完成后才物化）
 * 1. 令牌解析与台账单次消费检查 → 载体是否存在（不解封、不物化）；
 * 2. [CredentialUnlockPresenter] 在同一受保护窗口内完成解锁——**等待解锁期间不持有任何口令副本**
 *    （`ISSUE-P2-606` 的等待副本问题在此结构上不再出现）；
 * 3. 解锁成功后 [LockedSavePayloadStore.take] 在后台线程单次取回载荷，落盘，`finally` 擦除数组；
 * 4. 任何终态（成功 / 明确失败 / 未落盘即销毁）都登记令牌已消费；未落盘销毁时同时丢弃载体。
 *
 * ## 失败即不落库
 * 令牌缺失或畸形、台账已消费、载体过期或解封失败、库仍未解锁、通道开关已关闭、
 * 写盘返回失败——一律 `RESULT_CANCELED` 收尾，绝不把「没有口令」当成「空口令保存」。
 */
@AndroidEntryPoint
class AutofillLockedSaveActivity : BaseCredentialActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var unlockPresenter: CredentialUnlockPresenter

    @Inject
    lateinit var saveRequestLedger: SaveRequestLedger

    @Inject
    lateinit var payloadStore: LockedSavePayloadStore

    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    @Inject
    lateinit var callerTrustStore: AutofillCallerTrustStore

    @Inject
    lateinit var autofillOriginResolver: AutofillOriginResolver

    private var requestToken: String? = null

    /** 已走到明确终态（成功 / 失败均已登记台账）；销毁时不再按「取消」丢弃载体。 */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = LockedSaveRequestPendingIntent.requestTokenFromIntent(intent)
        requestToken = token
        if (token == null) {
            AppLog.e(TAG, "自动填充保存请求缺少有效令牌，拒绝处理")
            failAndFinish()
            return
        }
        if (saveRequestLedger.isConsumed(token)) {
            AppLog.w(TAG, "保存请求令牌已消费，拒绝重复处理")
            // 防御性收敛：已消费令牌不应再留有可解封记录
            payloadStore.discard(token)
            failAndFinish()
            return
        }
        if (!payloadStore.hasPayload(token)) {
            // 载体已过期 / 已被取走：旧句柄不得再被当作可保存请求
            AppLog.w(TAG, "保存请求载荷已过期或不存在，拒绝处理")
            settleToken(token)
            failAndFinish()
            return
        }
        val callingPackage = intent.getStringExtra(LockedSaveRequestPendingIntent.EXTRA_PACKAGE_NAME).orEmpty()
        if (callingPackage.isBlank()) {
            AppLog.e(TAG, "保存请求缺少调用方包名，拒绝处理")
            settleToken(token)
            failAndFinish()
            return
        }
        val webDomain = intent.getStringExtra(LockedSaveRequestPendingIntent.EXTRA_WEB_DOMAIN)
        unlockPresenter.requireUnlocked(this) { startSave(token, callingPackage, webDomain) }
    }

    /**
     * 解锁成功（或库本就解锁）后的保存主流程。任何结果路径都会清零口令数组。
     *
     * 本页的终态口径：**任何终态都登记令牌已消费并清掉载体**（成功、明确失败、取载荷失败、
     * 取消销毁）。代价是「取消解锁后重新点选同一入口」不再能重放该请求——载体已按取消语义
     * 清除，用户需要重新提交表单才会得到新的保存请求；换来的是口令副本不会在系统句柄有效期
     * 内长期留驻。
     */
    private fun startSave(token: String, callingPackage: String, webDomain: String?) {
        lifecycleScope.launch {
            // 解封含 Keystore 解密与私有目录读取，按工程规则离开主线程
            val payload = withContext(Dispatchers.Default) { payloadStore.take(token) }
            if (payload == null) {
                AppLog.e(TAG, "解锁后未能取回保存载荷，按失败收尾")
                // 取不到即终态：不留可解封记录（瞬时 Keystore 故障下记录只会在 TTL 后被清理，
                // 而本页已不再交付它——留着只是无主密文）
                payloadStore.discard(token)
                settleToken(token)
                failAndFinish()
                return@launch
            }
            val passwordChars = payload.passwordChars
            try {
                if (vaultRepository.isLocked()) {
                    AppLog.w(TAG, "密码库仍未解锁，无法保存密码凭据")
                    settleToken(token)
                    failAndFinish()
                    return@launch
                }
                // ENG-20261028-05：最终交付处复核自动填充通道开关——服务侧下发时的判定
                // 不能代表解锁窗口结束时的状态，关闭即不落库。
                if (!settingsStore.isAutofillServiceEnabled() ||
                    !settingsStore.isOfferSaveCredentialsEnabled()
                ) {
                    AppLog.i(TAG, "自动填充通道或新密码保存已关闭，跳过落库")
                    settleToken(token)
                    failAndFinish()
                    return@launch
                }
                val result = vaultRepository.saveAutofillCredential(
                    packageName = callingPackage,
                    webDomain = webDomain,
                    username = payload.username,
                    passwordChars = passwordChars
                )
                if (result is KdbxResult.Failure) {
                    AppLog.e(TAG, "保存密码凭据失败: ${result.error.javaClass.simpleName}")
                    settleToken(token)
                    failAndFinish()
                    return@launch
                }
                // 与解锁态 onSaveRequest / CM 保存通道同口径：落库成功后建立调用方首次绑定，
                // 否则 android:// 条目下次仍被 P2-46 绑定门挡住、无法自动出候选。
                bindCallerAfterAutofillSave(callingPackage, autofillOriginResolver, callerTrustStore)
                settleToken(token)
                setResult(RESULT_OK)
                finish()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                AppLog.e(TAG, "保存密码凭据失败", t)
                settleToken(token)
                failAndFinish()
            } finally {
                passwordChars.fill('0')
            }
        }
    }

    /** 登记令牌已消费（成功 / 明确失败 / 过期）；空白令牌不落记录。 */
    private fun settleToken(token: String?) {
        if (!token.isNullOrBlank()) {
            settled = true
            saveRequestLedger.markConsumed(token)
        }
    }

    override fun onDestroy() {
        // 未走到明确终态即销毁 = 用户取消或窗口被系统收走：丢弃载体并登记消费，
        // 让仍被系统持有的旧句柄在再次被点开时 fail-closed。
        // 配置变更（旋转等）不算取消——重建后的实例会按同一令牌重新取载荷。
        if (!settled && !isChangingConfigurations) {
            requestToken?.let { token ->
                payloadStore.discard(token)
                saveRequestLedger.markConsumed(token)
            }
        }
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "AutofillLockedSave"
    }
}
