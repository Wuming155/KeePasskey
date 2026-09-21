package com.keepasskey.app.ui.model

import java.io.File

/**
 * 「移除密码库」动作的**真实对象**（`ISSUE-P1-241`）。
 *
 * 该类型存在的唯一理由：这两种存储类型下「移除」的后果**根本不同**，而此前界面用语只有一套
 * （`db_picker_delete_confirm_desc`「不会删除物理文件」），对应用私有库构成**与真实行为相反的
 * 承诺**——用户按文案理解为「只是从列表移除」，实际把密码库文件永久删掉（真机取证见 §243 §7）。
 */
enum class VaultRemovalKind {

    /**
     * 应用私有目录（`filesDir`）内的库文件：移除即**删除该物理文件**，无回收站、不可恢复。
     *
     * 这类库由 `VaultDatabaseCatalog.buildDatabaseList` **按目录扫描**发现（`id` 即文件名、
     * `path` 即 `filesDir` 下的绝对路径），因此**不存在**「只摘登记、文件仍在」的中间态——
     * 只要文件还在，它下次刷新就会重新出现在列表里。
     */
    PRIVATE_FILE,

    /**
     * 应用**外部**来源的库（`content://` 文档或应用外路径）：物理文件在应用沙盒之外，
     * 移除只摘除本机的登记条目，**不触碰任何文件**。
     */
    EXTERNAL_LINK;

    companion object {

        /**
         * 存储类型判定（纯函数：只做路径运算，**不做任何文件系统 I/O**）。
         *
         * 判据即下面这一个事实，并与删除实现严格同源：
         * **该库文件的父目录就是应用私有目录 `filesDir`**。故：
         * - `filesDir/<name>.kdbx`（建库向导的默认落点）⇒ [PRIVATE_FILE]；
         * - `content://…` / 应用外绝对路径 / 用户以「打开已有」输入的相对路径（父目录为 `null`，
         *   例如把本地路径填成裸文件名）⇒ [EXTERNAL_LINK]。
         *
         * 失败侧一律取 [EXTERNAL_LINK]（不删文件）——**判不出私有目录时宁可不删**：
         * 反过来的错误（承诺「不删」却真删）正是本项要根治的形态。
         *
         * @param vaultPath 该条目的 `VaultDatabaseInfo.path`（库文件的真实位置）
         * @param filesDirPath 应用私有目录绝对路径；`null`（上下文不可用）时按 [EXTERNAL_LINK] 兜底
         */
        fun of(vaultPath: String, filesDirPath: String?): VaultRemovalKind {
            if (filesDirPath.isNullOrBlank()) return EXTERNAL_LINK
            val parent = File(vaultPath).parentFile ?: return EXTERNAL_LINK
            return if (parent == File(filesDirPath)) PRIVATE_FILE else EXTERNAL_LINK
        }
    }
}
