package com.keepasskey.database.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * AtomicFileWriter 替换失败口径的安全回归测试（审计 P2-7 / ENG-20261028-17）。
 *
 * 旧缺陷一：`Files.move` 抛异常后的降级分支先 `targetFile.delete()` 再 `renameTo`——
 * 一旦 delete 成功而 rename 失败，原文件彻底丢失。修复后降级只允许直接标准 rename
 * （POSIX rename(2) 可原子替换已存在目标，全程无需删除原文件）。
 * 旧缺陷二（ENG-20261028-17）：rename 也失败时曾退回 `Files.copy(REPLACE_EXISTING)` 覆盖，
 * 复制中断、磁盘满或只读会留下不完整主文件。现改为 fail-closed：拒绝非原子覆盖并抛出可
 * 处理的 [IOException]（会话层映射为保存失败），旧主文件与既有 `.bak` 原样保留，
 * 临时文件由调用方统一清理；备份偏好只决定是否生成 `.bak`，不参与替换方式的选取。
 *
 * ISSUE-P3-13：目录项 fsync 经 [DirectorySync] 抽象注入，本测试用记录型假实现断言
 * 三条目录项变更路径（① .bak 滚动备份替换后、② 主路径原子 move 后、③ 降级标准 rename
 * 成功后）**均触达目录 fsync 钩子**，并在拒绝覆盖时断言钩子**不得**被触达，
 * 从而在无法真实执行目录通道的 Windows 宿主上也能核验该崩溃安全路径。
 * ISSUE-P3-26 追加的第四条路径（④ 删除滚动备份 .bak 后，unlink 同属目录项变更）由
 * 同包测试类 `AtomicFileWriterBackupDeletionTest` 覆盖；本类与其共用
 * `DirectorySyncTestDoubles.kt` 中的记录型替身，使两个测试文件均保持在行数阈值内。
 * 说明：本测试类不存在 `Assume` 跳过用例；平台差异用例（标准 rename 能否替换既有目标）
 * 在 Windows 与 POSIX 两个分支都给出实质断言。测试基线中「Windows 无 POSIX 权限视图」
 * 的跳过项位于 sync 模块 `SyncCacheTest`，与本模块无关。
 */
class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Windows 判定（ISSUE-P3-13）：Windows 宿主目录通道不可用，目录 fsync 必然降级。 */
    private val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun read(file: File): String = String(file.readBytes())

    private fun tmpOf(target: File): File = File(tempFolder.root, target.name + ".tmp")

    private fun bakOf(target: File): File = File(tempFolder.root, target.name + ".bak")

    @Test
    fun testFirstWriteCreatesTargetFile() {
        val target = File(tempFolder.root, "vault.kdbx")

        AtomicFileWriter.writeAtomic(target) { it.write("first".toByteArray()) }

        assertEquals("first", read(target))
        assertFalse("临时文件不应残留", tmpOf(target).exists())
    }

    @Test
    fun testOverwriteKeepsBakBackupAndNoTmpLeftover() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val receipt = SessionWriteReceipt()
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomicTracked(
            targetFile = target,
            createBackup = true,
            receipt = receipt,
            directorySync = dirSync
        ) { it.write("new-version".toByteArray()) }

        assertEquals("new-version", read(target))
        assertEquals("old-stable", read(bakOf(target)))
        assertEquals(SessionWriteReceipt.Status.COMMITTED, receipt.status)
        assertFalse(tmpOf(target).exists())
        assertFalse("备份经临时件原子替换，完成后不得残留 .bak.tmp", File(tempFolder.root, "vault.kdbx.bak.tmp").exists())
        assertEquals("备份提交与主文件原子替换各触达一次目录同步", 2, dirSync.syncedDirectories.size)
    }

    // ===== ENG-20261028-17：原子替换不可用时的拒绝口径 =====

    @Test
    fun testFullWritePathRefusesWhenBothMoveAndRenameFail() {
        // 关闭条件核心断言：不以直接调用 private/internal fallbackReplace 当证据，
        // 经真实 writeAtomic 生产链路断言 move 与 rename 均失败时的 fail-closed 行为。
        // 测试覆盖备份偏好两态：createBackup = true 与 createBackup = false。
        for (createBackup in listOf(true, false)) {
            val target = File(tempFolder.root, "vault-full-refuse-$createBackup.kdbx")
            target.writeBytes("stable-original".toByteArray())
            val bak = bakOf(target)
            if (createBackup) {
                bak.writeBytes("older-backup".toByteArray())
            }
            val dirSync = RecordingDirectorySync()
            val receipt = SessionWriteReceipt()

            // 构造 move 与 rename 均失败的真实文件系统条件：
            // Windows: 独占打开 target 句柄，操作系统拒绝 ATOMIC_MOVE 与 renameTo 覆盖被占用文件；
            // POSIX: 在 writer 回调将父目录置只读，目录项不可改致 ATOMIC_MOVE 与 renameTo 均失败。
            var lockedRaf: java.io.RandomAccessFile? = null
            var parentDirWritableReset: (() -> Unit)? = null

            try {
                assertThrows(IOException::class.java) {
                    AtomicFileWriter.writeAtomicTracked(
                        targetFile = target,
                        createBackup = createBackup,
                        receipt = receipt,
                        directorySync = dirSync
                    ) { os ->
                        os.write("fresh-new".toByteArray())
                        if (isWindowsHost) {
                            lockedRaf = java.io.RandomAccessFile(target, "rw")
                        } else {
                            tempFolder.root.setWritable(false)
                            parentDirWritableReset = { tempFolder.root.setWritable(true) }
                        }
                    }
                }

                assertEquals("两态下 move/rename 失败后主文件必须保持稳定原样", "stable-original", read(target))
                if (createBackup) {
                    assertEquals("开启备份时既有旧 bak 必须保持原样", "older-backup", read(bak))
                } else {
                    assertFalse("关闭备份时不得生成 bak", bak.exists())
                }
                assertEquals("未提交成功必须标为 NOT_COMMITTED", SessionWriteReceipt.Status.NOT_COMMITTED, receipt.status)
                assertTrue("替换未成立，不得触达主文件替换的目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
            } finally {
                lockedRaf?.close()
                parentDirWritableReset?.invoke()
                tmpOf(target).delete()
                File(tempFolder.root, target.name + ".bak.tmp").delete()
            }
            assertFalse("失败后主临时文件必须被清理", tmpOf(target).exists())
            assertFalse("失败后暂存备份必须被清理", File(tempFolder.root, target.name + ".bak.tmp").exists())
        }
    }

    @Test
    fun testFallbackRefusesNonAtomicOverwriteAndKeepsOriginalAndBackup() {
        // 核心回归：即便本次备份可用，也不得退回非原子 copy 覆盖主文件
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("stable-original".toByteArray())
        val bak = bakOf(target)
        bak.writeBytes("older-backup".toByteArray())
        val tmp = tmpOf(target)
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        // 覆写 renameTo 恒返回 false：确定性模拟「原子 move 与标准 rename 都不可用」
        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(
                renameFailing(tmp),
                target,
                cause = IOException("atomic move failed"),
                directorySync = dirSync
            )
        }

        assertEquals("拒绝覆盖后旧主文件必须保持稳定版本", "stable-original", read(target))
        assertEquals("拒绝覆盖后既有备份不得被改动", "older-backup", read(bak))
        assertTrue("降级分支不得自行删除临时件（由调用方清理）", tmp.exists())
        assertTrue("拒绝覆盖没有任何目录项变更，不得触达目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
    }

    @Test
    fun testFallbackRefusesWhenTemporaryFileMissing() {
        // 临时文件不存在（丢失或已被清理）→ 标准 rename 必然失败 → 拒绝覆盖且不生成任何备份
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("precious-original".toByteArray())
        val tmp = tmpOf(target)

        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(tmp, target, cause = IOException("atomic move failed"))
        }

        // 旧实现此处先 delete 原文件、rename 再失败，原文件已彻底丢失
        assertTrue("原文件不得被删除", target.exists())
        assertEquals("precious-original", read(target))
        assertFalse("拒绝分支不得凭空生成备份", bakOf(target).exists())
    }

    @Test
    fun testFallbackOnExistingTargetFollowsPlatformRenameSemantics() {
        // ATOMIC_MOVE 失败后唯一允许的退路是标准 rename：POSIX rename(2) 可原子替换既有目标；
        // Windows 的 File.renameTo 拒绝替换既有文件（宿主实测返回 false）⇒ 必须 fail-closed 拒绝，
        // 而不是退回非原子 copy。两个分支都给出实质断言，不存在跳过。
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val bak = bakOf(target)
        bak.writeBytes("older-backup".toByteArray())
        val tmp = tmpOf(target)
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        val attempt = {
            AtomicFileWriter.fallbackReplace(
                tmp,
                target,
                cause = IOException("atomic move failed"),
                directorySync = dirSync
            )
        }

        if (isWindowsHost) {
            assertThrows(IOException::class.java) { attempt() }
            assertEquals("Windows 上标准 rename 无法替换既有目标，必须拒绝覆盖", "old-stable", read(target))
            assertEquals("older-backup", read(bak))
            assertTrue(dirSync.syncedDirectories.isEmpty())
        } else {
            attempt()
            assertEquals("POSIX 上标准 rename 原子替换既有目标", "fresh-new", read(target))
            assertEquals("降级替换不得改动既有备份", "older-backup", read(bak))
            assertFalse("rename 成功后临时件随重命名消失", tmp.exists())
            assertEquals("替换成功后必须恰好触达一次目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        }
    }

    @Test
    fun testBackupFailureDoesNotBlockMainFlow() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        // 将 .bak 占位为非空目录：暂存备份提交到该路径必然失败（Windows AccessDenied /
        // POSIX 目录非空）→ 备份失败被记录（logger.warning）且主流程照常完成，
        // 正式 .bak 保持替换前状态，暂存件被清理
        val bakDir = bakOf(target)
        assertTrue(bakDir.mkdirs())
        File(bakDir, "blocked").writeBytes(byteArrayOf(1))

        AtomicFileWriter.writeAtomic(target) { it.write("saved-anyway".toByteArray()) }

        assertEquals("saved-anyway", read(target))
        assertTrue("备份提交失败时必须保留原 .bak（此处为占位目录）", bakDir.isDirectory)
        assertFalse("备份失败后不得残留 .bak.tmp", File(tempFolder.root, "vault.kdbx.bak.tmp").exists())
        assertFalse(tmpOf(target).exists())
    }

    @Test
    fun testReadOnlyTargetFollowsPlatformReplaceSemantics() {
        // 关闭条件要求覆盖「只读」：测试覆盖备份开关两态（createBackup = true / false）。
        // Windows 上 ATOMIC_MOVE 与 renameTo 对只读目标都失败 ⇒ 必须 fail-closed 拒绝，
        // 且旧主文件与既有 .bak 一字节不动、暂存备份被丢弃；
        // POSIX 上 rename(2) 只看父目录写权限，只读属性不阻止替换，写盘照常成功。
        // 两个平台分支都给出实质断言，不做跳过。
        for (createBackup in listOf(true, false)) {
            val target = File(tempFolder.root, "vault-readonly-$createBackup.kdbx")
            target.writeBytes("stable-original".toByteArray())
            val bak = bakOf(target)
            if (createBackup) {
                bak.writeBytes("older-backup".toByteArray())
            }
            val dirSync = RecordingDirectorySync()
            val receipt = SessionWriteReceipt()
            val stagedBak = File(tempFolder.root, target.name + ".bak.tmp")
            assertTrue("前置条件：宿主必须允许设置只读", target.setWritable(false))

            try {
                if (isWindowsHost) {
                    assertThrows(IOException::class.java) {
                        AtomicFileWriter.writeAtomicTracked(
                            targetFile = target,
                            createBackup = createBackup,
                            receipt = receipt,
                            directorySync = dirSync
                        ) {
                            it.write("fresh-new".toByteArray())
                        }
                    }
                    assertEquals("拒绝后只读主文件内容必须原样", "stable-original", read(target))
                    if (createBackup) {
                        assertEquals("拒绝后既有备份必须原样", "older-backup", read(bak))
                    } else {
                        assertFalse("关闭备份时不得生成备份", bak.exists())
                    }
                    assertEquals("拒绝写盘回执状态必须为 NOT_COMMITTED", SessionWriteReceipt.Status.NOT_COMMITTED, receipt.status)
                    assertTrue("拒绝时没有任何目录项变更，不得触达目录 fsync 钩子", dirSync.syncedDirectories.isEmpty())
                    assertFalse("拒绝后不得残留主临时件", tmpOf(target).exists())
                    assertFalse("拒绝后不得残留暂存备份", stagedBak.exists())
                } else {
                    AtomicFileWriter.writeAtomicTracked(
                        targetFile = target,
                        createBackup = createBackup,
                        receipt = receipt,
                        directorySync = dirSync
                    ) {
                        it.write("fresh-new".toByteArray())
                    }
                    assertEquals("POSIX 上只读属性不阻止 rename，替换照常成功", "fresh-new", read(target))
                    if (createBackup) {
                        assertEquals("成功保存后 .bak 滚为本次替换前的稳定版本", "stable-original", read(bak))
                    } else {
                        assertFalse("关闭备份时不得生成备份", bak.exists())
                    }
                    assertEquals("成功写盘回执状态必须为 COMMITTED", SessionWriteReceipt.Status.COMMITTED, receipt.status)
                    assertFalse("提交完成后不得残留暂存备份", stagedBak.exists())
                    assertFalse("提交完成后不得残留主临时件", tmpOf(target).exists())
                }
            } finally {
                target.setWritable(true)
                bak.setWritable(true)
                stagedBak.setWritable(true)
            }
        }
    }

    // ===== ISSUE-P2-05：异常/中断模拟与降级路径 fsync 回归 =====

    @Test
    fun testWriterFailureOnFirstWriteLeavesNoEmptyTarget() {
        val target = File(tempFolder.root, "vault.kdbx")

        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomic(target) { throw IOException("模拟写入中断（断电/崩溃）") }
        }

        // 崩溃安全核心断言：中断后绝不残留空目标文件，也不残留 .tmp 垃圾
        assertFalse("中断后不得残留空目标文件", target.exists())
        assertFalse("中断后不得残留临时文件", tmpOf(target).exists())
    }

    @Test
    fun testWriterFailureKeepsOriginalContentAndCleansTmp() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("stable-original".toByteArray())
        val bak = bakOf(target)
        bak.writeBytes("older-backup".toByteArray())

        // 磁盘满（ENOSPC）与写入中断在 JDK 层同样表现为 IOException：writer 写出部分内容后抛出
        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomic(target) { os ->
                os.write("partial-write".toByteArray())
                throw IOException("模拟写入中途失败（磁盘满/中断）")
            }
        }

        assertEquals("写入失败后原文件内容必须保持稳定版本", "stable-original", read(target))
        assertEquals("失败发生在备份滚动之前，既有备份必须原样保留", "older-backup", read(bak))
        assertFalse("异常退出后不得残留临时文件", tmpOf(target).exists())
    }

    @Test
    fun testPartialWriteEnospcKeepsOriginalAndCleansTmpInBothBackupModes() {
        // 关闭条件要求覆盖「部分写 ENOSPC」两态（createBackup = true / false）：
        // 区分说明：在真实文件系统上执行真实文件事务（创建真实物理临时文件并写出部分字节），
        // 通过 writer 抛出受控的 IOException("No space left on device (ENOSPC)") 注入错误，
        // 验证原子写盘在磁盘满时的强一致保全与临时件清理，而无需真正耗尽宿主/设备磁盘。
        for (createBackup in listOf(true, false)) {
            val target = File(tempFolder.root, "vault-enospc-$createBackup.kdbx")
            target.writeBytes("stable-original".toByteArray())
            val bak = bakOf(target)
            if (createBackup) {
                bak.writeBytes("older-backup".toByteArray())
            }
            val receipt = SessionWriteReceipt()
            val dirSync = RecordingDirectorySync()

            val thrown = assertThrows(IOException::class.java) {
                AtomicFileWriter.writeAtomicTracked(
                    targetFile = target,
                    createBackup = createBackup,
                    receipt = receipt,
                    directorySync = dirSync
                ) { os ->
                    // 真实写入部分有效字节到磁盘物理临时文件
                    os.write("partial-enospc-payload".toByteArray())
                    os.flush()
                    // 注入受控的磁盘满（ENOSPC）IO 异常
                    throw IOException("No space left on device (ENOSPC)")
                }
            }

            assertTrue("异常信息需包含 ENOSPC 错误特征", thrown.message.orEmpty().contains("ENOSPC"))
            assertEquals("部分写失败后主文件必须一字节不差保持稳定版本", "stable-original", read(target))
            if (createBackup) {
                assertEquals("开启备份时旧 bak 必须保持原样", "older-backup", read(bak))
            } else {
                assertFalse("关闭备份时不得生成 bak", bak.exists())
            }
            assertEquals("写入未完成必须标为 NOT_COMMITTED", SessionWriteReceipt.Status.NOT_COMMITTED, receipt.status)
            assertFalse("部分写入的物理临时文件必须已被清理干净", tmpOf(target).exists())
            assertFalse("暂存备份不得存在", File(tempFolder.root, target.name + ".bak.tmp").exists())
            assertTrue("写临时文件失败不得触达任何目录同步钩子", dirSync.syncedDirectories.isEmpty())
        }
    }

    @Test
    fun testUnwritableTemporaryPathFailsBeforeTouchingOriginalOrBackup() {
        // 只读目录 / 无写权限 / 磁盘满导致临时件建不出来：以非空目录占位临时文件路径，
        // Windows 与 POSIX 上 FileOutputStream 均抛 FileNotFoundException（IOException 子类），
        // 确定性复现该失败点，并验证失败发生在备份滚动与主文件替换之前。
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("stable-original".toByteArray())
        val bak = bakOf(target)
        bak.writeBytes("older-backup".toByteArray())
        val tmp = tmpOf(target)
        assertTrue(tmp.mkdirs())
        File(tmp, "blocked").writeBytes(byteArrayOf(1))

        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomic(target) { it.write("new-version".toByteArray()) }
        }

        assertEquals("stable-original", read(target))
        assertEquals("older-backup", read(bak))
        assertTrue("占位目录不是本次写入产物，保留供调用方处置", tmp.exists())
    }

    @Test
    fun testWriteAtomicWithoutBackupDoesNotCreateBak() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val receipt = SessionWriteReceipt()
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomicTracked(
            targetFile = target,
            createBackup = false,
            receipt = receipt,
            directorySync = dirSync
        ) { it.write("new-version".toByteArray()) }

        assertEquals("new-version", read(target))
        assertEquals(SessionWriteReceipt.Status.COMMITTED, receipt.status)
        assertFalse("关闭备份偏好时不得生成 .bak", bakOf(target).exists())
        assertFalse("关闭备份偏好时不得生成备份临时件", File(tempFolder.root, "vault.kdbx.bak.tmp").exists())
        assertFalse(tmpOf(target).exists())
        assertEquals("关闭备份时仅主文件替换触达一次目录同步", 1, dirSync.syncedDirectories.size)
    }

    // ===== ISSUE-P3-13：目录项 fsync 路径的可注入验证（Windows 宿主同样可核验） =====

    @Test
    fun testMainAtomicMovePathInvokesDirectorySyncHook() {
        val target = File(tempFolder.root, "vault.kdbx")
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("first".toByteArray())
        }

        // 首次写入无稳定版本可滚动备份 → 仅主路径原子 move 后触达钩子②
        assertEquals("主路径原子 move 后必须触达目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals(tempFolder.root.absolutePath, dirSync.syncedDirectories.single().absolutePath)
    }

    @Test
    fun testBackupRollingPathInvokesDirectorySyncHook() {
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("new-version".toByteArray())
        }

        // 既有稳定版本 + 开启备份 → 钩子①（.bak 替换后）+ 钩子②（原子 move 后）各一次
        assertEquals("备份滚动与原子 move 两条路径都必须触达目录 fsync 钩子", 2, dirSync.syncedDirectories.size)
        assertTrue(dirSync.syncedDirectories.all { it.absolutePath == tempFolder.root.absolutePath })
        assertEquals("old-stable", read(bakOf(target)))
    }

    @Test
    fun testFallbackStandardRenamePathInvokesDirectorySyncHook() {
        // 目标不存在 → 标准 rename 在所有平台必然成功，确定性进入钩子③路径
        val target = File(tempFolder.root, "vault.kdbx")
        val tmp = tmpOf(target)
        tmp.writeBytes("fresh-new".toByteArray())
        val dirSync = RecordingDirectorySync()

        AtomicFileWriter.fallbackReplace(
            tmp,
            target,
            cause = IOException("atomic move failed"),
            directorySync = dirSync
        )

        assertEquals("降级标准 rename 成功后必须触达目录 fsync 钩子", 1, dirSync.syncedDirectories.size)
        assertEquals("fresh-new", read(target))
    }

    @Test
    fun testDegradedDirectorySyncNeverBlocksWrite() {
        // 平台降级（Windows 必然发生）时写盘必须照常成功：ISSUE-P3-13 验收标准 3
        val target = File(tempFolder.root, "vault.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val dirSync = RecordingDirectorySync(DirectorySyncOutcome.DEGRADED)

        AtomicFileWriter.writeAtomic(target, createBackup = true, directorySync = dirSync) {
            it.write("saved-anyway".toByteArray())
        }

        assertEquals("目录 fsync 降级不得阻断写盘", "saved-anyway", read(target))
        assertEquals("old-stable", read(bakOf(target)))
        assertEquals("降级路径同样要触达钩子（否则无从探测降级）", 2, dirSync.outcomes.size)
    }

    @Test
    fun testThrowingDirectorySyncAfterMoveDoesNotFallbackNorUncommit() {
        // 生产缺陷回归：Files.move 替换成功后，即使父目录 fsync 抛异常（违反契约），
        // 绝不得错误进入 fallbackReplace（已不存在的 tmpFile 会导致二次失败），
        // 且 receipt 必须保持 COMMITTED，绝不得被标记为 NOT_COMMITTED。
        val target = File(tempFolder.root, "vault-throwing-sync.kdbx")
        target.writeBytes("old-stable".toByteArray())
        val bak = bakOf(target)
        val receipt = SessionWriteReceipt()
        var syncInvoked = 0
        val throwingDirSync = DirectorySync {
            syncInvoked++
            throw IllegalStateException("模拟目录同步抛异常（违反契约）")
        }

        AtomicFileWriter.writeAtomicTracked(
            targetFile = target,
            createBackup = true,
            receipt = receipt,
            directorySync = throwingDirSync
        ) { os ->
            os.write("new-version".toByteArray())
        }

        assertEquals("目录同步异常降级为告警，主文件必须替换成功", "new-version", read(target))
        assertEquals("主文件替换成功后备份必须已提交", "old-stable", read(bak))
        assertEquals("提交证据必须为 COMMITTED，不得被异常回退", SessionWriteReceipt.Status.COMMITTED, receipt.status)
        assertFalse("主临时文件不得残留", tmpOf(target).exists())
        assertFalse("备份临时文件不得残留", File(tempFolder.root, target.name + ".bak.tmp").exists())
        assertTrue("目录同步钩子必须已被调用", syncInvoked >= 1)
    }

    @Test
    fun testDirectorySyncPathsOnRealHost() {
        // 委派真实实现的探针覆盖三条目录项变更路径，核验宿主平台语义：
        // POSIX 宿主必须全部 SYNCED（验收标准 1 的真实运行时断言）；
        // Windows 宿主必然 DEGRADED，且各路径的写盘结果均不受影响（验收标准 3）。
        val probe = ProbingDirectorySync()

        // 钩子①+②：既有稳定版本 + 开启备份（备份经 .bak.tmp 原子替换）
        val backedUp = File(tempFolder.root, "with-bak.kdbx")
        backedUp.writeBytes("old-stable".toByteArray())
        AtomicFileWriter.writeAtomic(backedUp, createBackup = true, directorySync = probe) {
            it.write("new-version".toByteArray())
        }

        // 钩子③：目标不存在 → 标准 rename 必然成功
        val renamed = File(tempFolder.root, "renamed.kdbx")
        val renameTmp = tmpOf(renamed)
        renameTmp.writeBytes("renamed-content".toByteArray())
        AtomicFileWriter.fallbackReplace(
            renameTmp,
            renamed,
            cause = IOException("atomic move failed"),
            directorySync = probe
        )

        // 拒绝非原子覆盖：无任何目录项变更，钩子不得被触达（ENG-20261028-17 + 诚实性）
        val refused = File(tempFolder.root, "refused.kdbx")
        refused.writeBytes("old-stable".toByteArray())
        val refusedTmp = tmpOf(refused)
        refusedTmp.writeBytes("fresh-new".toByteArray())
        assertThrows(IOException::class.java) {
            AtomicFileWriter.fallbackReplace(
                renameFailing(refusedTmp),
                refused,
                cause = IOException("atomic move failed"),
                directorySync = probe
            )
        }

        assertEquals("三条落盘路径必须各触达一次目录 fsync 钩子，拒绝分支不得触达", 3, probe.outcomes.size)
        val expectedOutcome =
            if (isWindowsHost) DirectorySyncOutcome.DEGRADED else DirectorySyncOutcome.SYNCED
        assertEquals(
            "宿主平台目录通道能力与实测结果不符（Windows 必然降级 / POSIX 必须真实 fsync）",
            listOf(expectedOutcome),
            probe.outcomes.distinct()
        )
        // 无论降级与否，成功路径的写盘结果都必须成立，拒绝路径必须保持原样
        assertEquals("new-version", read(backedUp))
        assertEquals("old-stable", read(bakOf(backedUp)))
        assertEquals("renamed-content", read(renamed))
        assertEquals("old-stable", read(refused))
    }

    /** 构造标准 rename 恒失败的文件句柄（模拟跨卷 / 目标被占用），确定性进入降级拒绝分支。 */
    private fun renameFailing(file: File): File = object : File(file.path) {
        override fun renameTo(dest: File): Boolean = false
    }
}
