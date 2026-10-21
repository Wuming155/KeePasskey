package com.keepasskey.app.ui.screens.settings.subscreens

/**
 * 提交凭据数组的独立工作副本；调用方保留草稿所有权，本函数无论返回或抛错都擦除工作副本。
 */
internal fun <T> withSyncCredentialSubmissionCopy(draft: CharArray, submit: (CharArray) -> T): T {
    val submission = draft.copyOf()
    return try {
        submit(submission)
    } finally {
        submission.fill('0')
    }
}

/** 保存失败时短路后续联网动作，返回值如实表示是否已继续。 */
internal fun persistThenContinue(persist: () -> Boolean, continueAfterPersist: () -> Unit): Boolean {
    if (!persist()) return false
    continueAfterPersist()
    return true
}

