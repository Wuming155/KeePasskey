package com.keepasskey.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * **真实 KeePass / KeePassXC Argon2 `.kdbx` 语料 · 设备侧端到端解锁**（ISSUE-P3-23 验收标准 2）
 *
 * ## 为什么落在 `database` 模块而不是 `crypto`
 * 模块依赖严格单向（`app → database → crypto → core`），**`crypto` 不依赖 `database`**，
 * 因而不具备 `.kdbx` 读写能力；只有 `database`（[KdbxFile]）能做端到端解锁。
 * 另外 `src/test/resources` **不会**打进 androidTest APK，故设备侧语料必须放
 * `database/src/androidTest/assets/argon2-interop/`（见 `crypto/src/test/resources/argon2-interop/README.md` §3.4）。
 *
 * ## fail-closed 语义（本用例最重要的设计约束）
 * 1. **语料缺失 → 显式跳过**（JUnit [Assume]）：跳过消息内直接给出语料生成方法所在文件路径。
 *    「语料缺失 ≠ 通过」——跳过**不代表**验收标准 2 达成。
 * 2. **语料存在但伴生元数据缺失/不合法 → 硬失败**（[failClosed]）：不允许「有 `.kdbx` 但无从断言」蒙混过关。
 * 3. **任何断言失败 → 硬失败**：解锁失败、条目数不符、KDF 参数与声明不符一律失败。
 *
 * ## 本用例能证明什么 / 不能证明什么（如实边界）
 * - **能**：在真实 Android 运行时（含 JNI 原生 Argon2 路径）上，本工程 `database` 层能解开一个
 *   **外部工具产出**的 `.kdbx`，且其 KDF 参数与条目数与伴生声明逐项一致。
 * - **不能**：仅凭字节无法证明产物确由官方工具生成——`source` 字段是**声明**而非证明，
 *   其真实性由人工复核与 README §3 的生成流程保证；本用例只强制「必须声明且声明合法」。
 * - 本用例**不包含**任何「自生成库再自读回」的往返断言（那种往返不构成互操作证据）；
 *   设备侧往返另见 `SelfGeneratedRoundTripInstrumentedTest`（已显式标注非互操作证据）。
 *
 * 敏感数据：语料口令为**测试专用一次性口令**（见 [CORPUS_PASSPHRASE]），以 `CharArray` 承载并在
 * `finally` 中清零；断言与日志**只输出条目标题（占位内容）与非敏感 KDF 参数**，绝不输出密码明文。
 */
@RunWith(AndroidJUnit4::class)
class RealKdbxCorpusUnlockTest {

    private companion object {

        /** 设备侧语料目录（androidTest APK 的 assets 内）。 */
        private const val CORPUS_ASSET_DIR = "argon2-interop"

        /** `.kdbx` 语料后缀。 */
        private const val KDBX_SUFFIX = ".kdbx"

        /** 伴生元数据后缀。 */
        private const val META_SUFFIX = ".json"

        /**
         * 语料主密码：**公开测试向量专用的一次性口令**（约定见
         * `crypto/src/test/resources/argon2-interop/README.md` §3.1 / §4）。
         * 生成语料时必须逐字使用该口令；**严禁**使用任何真实主密码。
         */
        private const val CORPUS_PASSPHRASE = "Test-Vector-Only-2026!"

        /** 反例口令：显式区别于 [CORPUS_PASSPHRASE]，用于「错误凭据必须被拦截」断言。 */
        private const val WRONG_PASSPHRASE = "Wrong-Passphrase-Not-The-Corpus-One-2026!"

        /** 1 KiB = 1024 字节（伴生元数据以 KiB 声明内存参数，KDF 参数对象以字节承载）。 */
        private const val BYTES_PER_KIB = 1024L

        /** 伴生元数据必需字段（缺失即视为语料不完整 → 硬失败，不做静默降级）。 */
        private val REQUIRED_META_FIELDS = listOf(
            "source", "kdf", "version", "iterations", "memoryKib", "parallelism",
            "entryCount", "containsRealData", "passphraseIsThrowaway"
        )

        /** 盐 hex 归一化所用的十六进制基数。 */
        private const val HEX_RADIX = 16

        /** 伴生元数据中合法的 KDF 名（小写）。 */
        private const val ARGON2D_NAME = "argon2d"
        private const val ARGON2ID_NAME = "argon2id"

        /** 语料缺失时的跳过原因（必须自解释到「照哪个文件生成、放到哪里」）。 */
        private const val SKIP_REASON_CORPUS_MISSING =
            "语料缺失：请按 crypto/src/test/resources/argon2-interop/README.md 生成真实 " +
                "KeePass 2.61.1 / KeePassXC Argon2 .kdbx 语料（含同名 .json 伴生元数据）后放入 " +
                "database/src/androidTest/assets/argon2-interop/。" +
                "本用例在语料缺失时【显式跳过】，跳过【不代表】验收标准 2 达成。"

        /** 测试用 APK 的 assets（androidTest 资源挂在**测试 context**，而非 targetContext）。 */
        private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets

        /** 语料目录下的全部 asset 文件名（目录不存在时为空列表）。 */
        private fun corpusAssetNames(): List<String> =
            testAssets().list(CORPUS_ASSET_DIR)?.toList().orEmpty()

        /** 全部 `.kdbx` 语料文件名（README 等非语料文件被过滤掉）。 */
        private fun corpusKdbxNames(): List<String> =
            corpusAssetNames().filter { it.endsWith(KDBX_SUFFIX) }.sorted()

        /** 读取 assets 内文件字节；不存在时返回 null（由调用方决定 skip / fail）。 */
        private fun readAssetOrNull(assetPath: String): ByteArray? = try {
            testAssets().open(assetPath).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }

        /** 语料 asset 路径。 */
        private fun kdbxAssetName(kdbxFileName: String) = "$CORPUS_ASSET_DIR/$kdbxFileName"

        /** 伴生 `.json` 的 asset 路径。 */
        private fun metaAssetName(kdbxFileName: String) =
            "$CORPUS_ASSET_DIR/${kdbxFileName.removeSuffix(KDBX_SUFFIX)}$META_SUFFIX"

        /**
         * fail-closed 失败：[fail] 之后必须能出现在「值位置」（`?:` 右侧），
         * 故包一层返回 [Nothing] 的函数。
         */
        private fun failClosed(message: String): Nothing {
            fail(message)
            throw AssertionError(message) // 不可达：仅为满足 Nothing 返回类型
        }

        /**
         * 语料缺失则整类跳过（fail-closed：绝不把「语料缺失」当通过）。
         * 返回非空的 `.kdbx` 文件名列表。
         */
        private fun requireCorpusOrSkip(): List<String> {
            val corpora = corpusKdbxNames()
            if (corpora.isEmpty()) {
                println(
                    "[ISSUE-P3-23 验收标准 2] assets/$CORPUS_ASSET_DIR 下未发现任何 .kdbx 语料" +
                        "（该目录现有条目=${corpusAssetNames()}）→ 跳过，不计入通过。"
                )
            }
            Assume.assumeTrue(SKIP_REASON_CORPUS_MISSING, corpora.isNotEmpty())
            return corpora
        }
    }

    /**
     * 一条语料的伴生声明（`<name>.json`，schema 见 README §4）。
     * 仅承载**非敏感**元数据：口令本身不写入该文件。
     */
    private data class CorpusMeta(
        val kdbxAsset: String,
        val source: String,
        val kdf: String,
        val version: Int,
        val iterations: Long,
        val memoryKib: Long,
        val parallelism: Int,
        val entryCount: Int,
        val keyFileAsset: String?,
        val saltHex: String?,
        val entryTitles: List<String>
    )

    /**
     * 解析并**严格校验**伴生元数据：必需字段缺失、取值非法或安全声明不成立时**硬失败**
     * （不允许「语料在、元数据糊」的假绿）。逐项校验拆到 [assertMetaContract] 内。
     */
    private fun parseMetaOrFail(kdbxFileName: String): CorpusMeta {
        val metaAsset = metaAssetName(kdbxFileName)
        val metaBytes = readAssetOrNull(metaAsset) ?: failClosed(
            "语料 $kdbxFileName 存在但缺少伴生元数据 $metaAsset —— 按 README §4，每个 .kdbx " +
                "必须配同名 .json；缺失即视为语料不完整，不得跳过。"
        )
        val json = try {
            JSONObject(String(metaBytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            failClosed("伴生元数据 $metaAsset 不是合法 JSON：${e.message}")
        }

        assertMetaContract(json, metaAsset)

        val keyFileName = json.optString("keyFile").takeIf { it.isNotBlank() }
        return CorpusMeta(
            kdbxAsset = kdbxAssetName(kdbxFileName),
            source = json.getString("source"),
            kdf = json.getString("kdf").lowercase(),
            version = json.getInt("version"),
            iterations = json.getLong("iterations"),
            memoryKib = json.getLong("memoryKib"),
            parallelism = json.getInt("parallelism"),
            entryCount = json.getInt("entryCount"),
            keyFileAsset = keyFileName?.let { "$CORPUS_ASSET_DIR/$it" },
            saltHex = json.optString("saltHex").takeIf { it.isNotBlank() },
            entryTitles = json.optJSONArray("entryTitles").asStringList()
        )
    }

    /** 伴生元数据的全部契约校验（必需字段 / 安全声明 / 来源声明 / KDF 名 / 条目数）。 */
    private fun assertMetaContract(json: JSONObject, metaAsset: String) {
        val missing = REQUIRED_META_FIELDS.filter { !json.has(it) }
        if (missing.isNotEmpty()) {
            failClosed("伴生元数据 $metaAsset 缺少必需字段 $missing（schema 见 README §4），语料不完整。")
        }

        // 安全声明必须成立：公共语料只能是「一次性口令 + 零真实数据」，否则严禁入库
        if (json.optBoolean("containsRealData", true)) {
            failClosed("伴生元数据 $metaAsset 声明 containsRealData=true —— 含真实数据的库严禁作为语料入库。")
        }
        if (!json.optBoolean("passphraseIsThrowaway", false)) {
            failClosed("伴生元数据 $metaAsset 声明 passphraseIsThrowaway=false —— 语料必须使用一次性口令。")
        }

        val source = json.getString("source")
        if (!source.contains("keepass", ignoreCase = true)) {
            failClosed(
                "伴生元数据 $metaAsset 的 source=\"$source\" 未声明为 KeePass / KeePassXC 产物 —— " +
                    "本工程自建夹具（如 database/src/test/resources/fixtures/test_vault.kdbx）" +
                    "不能充当验收标准 2 的互操作语料。"
            )
        }

        val kdfName = json.getString("kdf").lowercase()
        if (kdfName != ARGON2D_NAME && kdfName != ARGON2ID_NAME) {
            failClosed("伴生元数据 $metaAsset 的 kdf=\"${json.getString("kdf")}\" 非法（仅 argon2d / argon2id）。")
        }

        val entryCount = json.getInt("entryCount")
        if (entryCount <= 0) {
            failClosed("伴生元数据 $metaAsset 的 entryCount=$entryCount 非法（应 > 0，零条目无法证伪解锁正确性）。")
        }
    }

    /** `JSONArray?` → `List<String>`（缺失时为 null）。 */
    private fun JSONArray?.asStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    /** 读取语料与其（可选）密钥文件字节，供 [KdbxFile.load] 使用。 */
    private fun loadCorpusBytes(meta: CorpusMeta): Pair<ByteArray, ByteArray?> {
        val kdbxBytes = readAssetOrNull(meta.kdbxAsset)
            ?: failClosed("语料 ${meta.kdbxAsset} 读取失败（assets 列表与实际读取结果不一致）")
        val keyFileBytes = meta.keyFileAsset?.let { keyAsset ->
            readAssetOrNull(keyAsset) ?: failClosed("伴生元数据声明的密钥文件 $keyAsset 不存在")
        }
        return kdbxBytes to keyFileBytes
    }

    /** 把 KDF 参数逐项与伴生声明核对（类型 / 版本 / 迭代 / 内存 / 并行度 / K 与 A 缺省）。 */
    private fun assertKdfMatchesDeclaration(name: String, meta: CorpusMeta, kdf: KdfParameters) {
        assertTrue(
            "[$name] 语料的 KDF 必须为 Argon2，实际 kdfUuid=${kdf.kdfUuid}",
            kdf is KdfParameters.Argon2
        )
        val argon2 = kdf as KdfParameters.Argon2
        val expectedType = if (meta.kdf == ARGON2D_NAME) {
            KdfParameters.Argon2.Argon2Type.ARGON2D
        } else {
            KdfParameters.Argon2.Argon2Type.ARGON2ID
        }
        assertEquals("[$name] Argon2 类型与声明不一致", expectedType, argon2.type)
        assertEquals("[$name] Argon2 版本与声明不一致", meta.version, argon2.version)
        assertEquals("[$name] Argon2 迭代 t 与声明不一致", meta.iterations, argon2.iterations)
        assertEquals(
            "[$name] Argon2 内存 m 与声明不一致",
            meta.memoryKib * BYTES_PER_KIB,
            argon2.memoryInBytes
        )
        assertEquals("[$name] Argon2 并行度 p 与声明不一致", meta.parallelism, argon2.parallelism)
        // 真实 KeePass / KeePassXC 建库不写 K（secret）与 A（associatedData 由 KDBX 头部另行提供）
        assertNull("[$name] 真实语料不应带 KDF secret（K）", argon2.secretKey)
        assertNull("[$name] 真实语料不应带 KDF associatedData（A）", argon2.associatedData)

        // 盐：伴生文件声明了则必须一致（证明设备上读到的正是被声明的那份文件）
        meta.saltHex?.let { declaredHex ->
            assertEquals(
                "[$name] KDF 盐与伴生声明不一致（文件可能被替换）",
                declaredHex.lowercase(),
                argon2.salt.joinToString("") { byte ->
                    byte.toInt().and(0xFF).toString(HEX_RADIX).padStart(2, '0')
                }
            )
        }
    }

    /** 条目数与条目标题核对（标题为占位内容，非敏感）。 */
    private fun assertEntriesMatchDeclaration(name: String, meta: CorpusMeta, entryTitles: List<String>) {
        assertEquals(
            "[$name] 解锁出的条目数与语料声明不一致（声明 ${meta.entryCount}，实际 ${entryTitles.size}）",
            meta.entryCount,
            entryTitles.size
        )
        assertTrue("[$name] 每个条目的标题不应为空（占位条目也必须有标题）", entryTitles.all { it.isNotEmpty() })
        if (meta.entryTitles.isNotEmpty()) {
            assertEquals(
                "[$name] 条目标题集合与声明不一致",
                meta.entryTitles.sorted(),
                entryTitles.sorted()
            )
        }
    }

    // ==================================================================================
    // 1) 端到端解锁：真实语料 → database 层读取 → 条目数与 KDF 参数核对
    // ==================================================================================

    @Test
    fun 真实KeePass语料在设备上完成端到端解锁且条目数与KDF参数与声明一致() {
        val corpora = requireCorpusOrSkip()

        for (fileName in corpora) {
            val meta = parseMetaOrFail(fileName)
            val (kdbxBytes, keyFileBytes) = loadCorpusBytes(meta)

            val passphrase = CORPUS_PASSPHRASE.toCharArray()
            val database = try {
                KdbxFile.load(ByteArrayInputStream(kdbxBytes), passphrase, keyFileBytes)
            } finally {
                passphrase.fill('\u0000')
            }

            assertKdfMatchesDeclaration(fileName, meta, database.header.kdfParameters)

            val entries = database.rootGroup.allEntries()
            assertEntriesMatchDeclaration(fileName, meta, entries.map { it.title })

            println(
                "[ISSUE-P3-23 验收标准 2] 语料 $fileName（声明来源=${meta.source}）端到端解锁成功：" +
                    "entryCount=${entries.size}（声明 ${meta.entryCount}）, " +
                    "KDF=${meta.kdf} v${meta.version} t=${meta.iterations} " +
                    "m=${meta.memoryKib / BYTES_PER_KIB}MiB p=${meta.parallelism}, " +
                    "cipherUuid=${database.header.cipherUuid}"
            )

            database.clearSensitiveData()
        }
    }

    // ==================================================================================
    // 2) 错误口令必须被拒（语料存在时的反向断言）
    // ==================================================================================

    @Test
    fun 错误主密码对真实语料被拦截() {
        val corpora = requireCorpusOrSkip()
        val fileName = corpora.first()

        val meta = parseMetaOrFail(fileName)
        val (kdbxBytes, keyFileBytes) = loadCorpusBytes(meta)

        val wrongPassphrase = WRONG_PASSPHRASE.toCharArray()
        try {
            KdbxFile.load(ByteArrayInputStream(kdbxBytes), wrongPassphrase, keyFileBytes)
            failClosed("[$fileName] 错误主密码竟解锁成功——凭据校验防线失效")
        } catch (expected: KdbxInvalidCredentialsException) {
            // 预期路径：头部 HMAC 校验失败
            assertNotNull("[$fileName] 凭据异常应带语义化消息", expected.message)
            println("[ISSUE-P3-23] 语料 $fileName 的错误口令被正确拦截：${expected.message}")
        } finally {
            wrongPassphrase.fill('\u0000')
        }
    }
}
