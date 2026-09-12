package com.keepasskey.database.exception

import java.io.IOException

/**
 * **凭据错误**专用异常：仅当主密码 / 密钥文件不正确时抛出。
 *
 * 判据唯一且明确：KDBX4 **头部 HMAC-SHA256 校验未通过**（`KdbxFile.load` 中
 * `hmacKey64` 派生自 transformedKey，故头部 HMAC 通过即证明凭据正确）。
 *
 * 与完整性失败的边界（D20 整改，对齐官方三态语义）：
 * - 头部 SHA-256 不符 → [KdbxCorruptFileException]（官方 `KdbxFile.Read.cs:150`）；
 * - **头部 HMAC 不符 → 本异常**（官方 `InvalidCompositeKeyException`，`Read.cs:157`）；
 * - 数据块 / 终止块 HMAC 不符 → [KdbxCorruptFileException]（官方
 *   `HmacBlockStream.cs:233,264` 的 `InvalidDataException(FileCorrupted)`）。
 *
 * 该分流直接决定用户可见行为：只有本异常会计入解锁失败节流
 * （`UnlockViewModel` 仅对 `KdbxInvalidCredentialsException` 调用 `registerFailure`），
 * 故**被篡改的库不得伪装成"口令错误"**。
 *
 * 兼容现有的 [IOException] 捕获逻辑。
 */
class KdbxInvalidCredentialsException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
