package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `android://` 包名维度放行判定（ISSUE-P2-46）的行为与接线测试。
 *
 * ## 覆盖的三类断言
 *
 * 1. **行为**：未绑定 / 同包名不同签名 / 摘要不可读 / 空包名一律**不命中**；绑定后同签名放行；
 *    多签名者任一命中即放行。
 * 2. **回归语义**：AC②「同包名不同签名 → 不命中」与「未安装绑定包 + 侧载同 applicationId → 不命中」。
 * 3. **接线**：判定结果必须真的被传给排序器（`packageDimensionAuthorized`），
 *    且选择器必须真的写入首次绑定（否则未绑定调用方**永远**无法再经 `android://` 命中——鸡生蛋问题）。
 */
class AndroidPackageBindingPolicyTest {

    private val store = AutofillCallerTrustStore(context = null)

    private fun authorized(pkg: String, digests: CallerCertDigests): Boolean =
        AndroidPackageBindingPolicy.isPackageDimensionAuthorized(
            callingPackage = pkg,
            callingCertDigests = digests,
            isTrusted = { p, d -> store.isTrusted(p, d) }
        )

    @Test
    fun `未绑定的调用方不放行`() {
        assertFalse(authorized("com.example.app", CallerCertDigests.ofSingle("AA")))
    }

    @Test
    fun `绑定同签名后放行`() {
        assertTrue(store.trust("com.example.app", "AA"))

        assertTrue(authorized("com.example.app", CallerCertDigests.ofSingle("AA")))
    }

    @Test
    fun `同包名不同签名（侧载重打包）不放行`() {
        store.trust("com.example.app", "AA")

        assertFalse(
            "AC②：同包名换签名必须重新视为首次出现，不得自动命中",
            authorized("com.example.app", CallerCertDigests.ofSingle("BB"))
        )
    }

    @Test
    fun `未安装绑定包 + 侧载同 applicationId 不命中`() {
        // 条目以 android://com.bank.app 绑定（原应用已卸载）；攻击者以同 applicationId 侧载。
        // 攻击者未被绑定过 → 包名维度不放行（原缺陷下会直接命中）。
        val attackerDigests = CallerCertDigests.ofSingle("ATTACKER_SHA256")

        assertFalse(authorized("com.bank.app", attackerDigests))
    }

    @Test
    fun `多签名者任一摘要命中即放行（签名轮换期）`() {
        store.trust("com.example.app", "AA")

        assertTrue(
            "签名轮换期调用方同时持有当前签名者与历史签名者，任一命中即视为同一应用",
            authorized("com.example.app", CallerCertDigests.of(listOf("BB", "AA")))
        )
    }

    @Test
    fun `签名摘要不可读时不放行（不得退化到仅包名）`() {
        // 降级键（`pkg|`）是确认页在摘要不可读时的既有写入口径；包名维度**不得**采信它——
        // 「只认包名」正是本项要消灭的形态（侧载同 applicationId 即可命中）。
        store.trust("com.example.app", null)

        assertFalse(
            "摘要不可读必须 fail-closed，不得因存在仅包名的降级记录而放行",
            authorized("com.example.app", CallerCertDigests.EMPTY)
        )
    }

    @Test
    fun `空包名不放行`() {
        assertFalse(authorized("", CallerCertDigests.ofSingle("AA")))
        assertFalse(authorized("   ", CallerCertDigests.ofSingle("AA")))
    }

    @Test
    fun `跨包名不互通`() {
        store.trust("com.example.app", "AA")

        assertFalse(authorized("com.other.app", CallerCertDigests.ofSingle("AA")))
    }
}

/**
 * ISSUE-P2-46 接线守卫（静态源码比对）。
 *
 * 失效形态不是「判定算错」，而是**判定没被使用**或**首次绑定没有写入路径**：
 * 前者让门控形同虚设，后者让未绑定调用方永远无法再经 `android://` 自动命中（鸡生蛋）。
 */
class AndroidPackageBindingWiringTest {

    @Test
    fun `排序器调用点必须传入授权判定结果`() {
        val source = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt")

        assertTrue(
            "候选排序必须按 AndroidPackageBindingPolicy 的判定结果给出包名维度授权",
            source.contains("AndroidPackageBindingPolicy.isPackageDimensionAuthorized(")
        )
        assertTrue(
            "授权结果必须真的传给 rank(...) 的 packageDimensionAuthorized",
            source.contains("packageDimensionAuthorized = packageDimensionAuthorized")
        )
    }

    @Test
    fun `网格服务必须注入同一份信任存储`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        )

        assertTrue(
            "服务必须注入 AutofillCallerTrustStore（与确认页 / 选择器同一实例，保证写入面=判定面）",
            source.contains("lateinit var callerTrustStore: AutofillCallerTrustStore")
        )
    }

    @Test
    fun `选择器必须写入首次绑定且摘要不可读时不写降级键`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
        )

        assertTrue(
            "选择器（用户显式指认调用方的唯一入口）必须写入首次绑定",
            source.contains("callerTrustStore.trust(callingPackage, digests.primary)")
        )
        assertTrue(
            "首次绑定必须在交付数据集之前发生（deliver 内调用）",
            source.contains("bindCallerForPackageDimension()")
        )
        assertTrue(
            "摘要不可读必须不写降级键（保持未绑定，fail-closed）",
            source.contains("if (digests.isEmpty)")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
