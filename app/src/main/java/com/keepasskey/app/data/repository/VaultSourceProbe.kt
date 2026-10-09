package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.file.KdbxKdfStrengthAssessment
import com.keepasskey.database.file.KdbxKdfStrengthAssessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 密码库**来源只读探测**（`ISSUE-P2-529` 顺带自 `VaultLifecycleCoordinator` 原样切出，
 * 纯结构性搬移——方法体与 KDoc 逐字保留，仅宿主类型改变）。
 *
 * 职责单一：给定一个库来源（`content://` 或本地路径），打开其读取流并评估 KDF 工作因子。
 * 该对方法此前作为 `VaultLifecycleCoordinator` 的私有/公开成员存在，与本批新增的「另存副本」
 * 逻辑无共同变化理由，故一并下沉，使协调器回到行数分档档沿之内。
 */
internal class VaultSourceProbe(private val context: Context) {

    /**
     * 评估某个密码库来源的工作因子是否**低于本应用建库默认强度**（ISSUE-P2-87，非阻断提示）。
     *
     * 读取的是 KDBX **外层明文头部**：按规范，头部位于认证之前、承载 KDF 参数（Argon2 `M / I / P`
     * 或 AES-KDF `R`），故本方法**不需要任何凭据**，也不解密载荷、不接触库内容。
     * 解析走数据库模块的**唯一**头部解析实现 [KdbxHeader.deserialize]（与解锁、保存同一条路径，
     * 不新开旁路），并同样受其认证前加固闸门（字段长度 / 累计字节 / 字段数）约束。
     *
     * **任何失败一律降级为「未评估」（返回 null），绝不外抛**：本方法只服务于导入成功后的一条
     * 提示，`content://` 提供方拒绝、远端 URL 不是本地文件、第三方构造的损坏头部等情况都不得
     * 反过来影响已成功的导入，也不得据此谎报「低于基线」。
     *
     * 判据与文案口径见 [KdbxKdfStrengthAssessor]（结论只能表述为「低于本应用建库默认强度」，
     * 不得解读为「不安全」；且**不修改任何 KDF 参数**）。
     */
    suspend fun assessKdfStrength(path: String): KdbxKdfStrengthAssessment? = withContext(Dispatchers.IO) {
        try {
            openStream(path)?.use { stream ->
                KdbxKdfStrengthAssessor.assess(KdbxHeader.deserialize(stream).first.kdfParameters)
            }
        } catch (_: Exception) { // cancel-n/a: 保护段为阻塞式流打开与头部解析（无挂起点）
            null
        }
    }

    /**
     * 打开密码库来源的读取流：`content://` 走 SAF，其余按本地文件路径。
     * 来源不可用（非本地文件的远端地址 / 提供方拒绝 / 文件不存在）返回 null。
     */
    private fun openStream(path: String): InputStream? =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            File(path).takeIf { it.isFile }?.inputStream()
        }
}
