package com.keepasskey.app.ui.screens.settings

import com.keepasskey.database.session.SessionCredentialRequest

/** 非秘密的提交受理快照；后台任务不再解析活动库或同步目标。 */
internal data class CredentialRotationAdmission(
    val request: SessionCredentialRequest?,
    val databaseId: String?,
    val remoteBound: Boolean,
    val syncTarget: String?
)
