package com.keepasskey.crypto.kdf

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Argon2 BouncyCastle 对照向量（Rust 秘密飞地迁移 PoC · Batch 0）
 *
 * 目的：把「迁移前」的正确性基准钉死，作为 Rust 内核（Batch 1+）逐字节对照的唯一真相源。
 * 产出物 `crypto/src/test/resources/argon2-interop/argon2-bc-vectors.json` 覆盖 KDBX4 全参数域：
 * Argon2d(type=0)/Argon2id(type=2) × version 0x10(16)/0x13(19) × 含/不含 secret(K) + associatedData(A)，
 * 并含并行度 p=1/p=4、现实内存档位，以及两条 **长 AD（64B）探针**用于验证 RustCrypto
 * `AssociatedData` 长度上限（风险 R2）——BC 无 32B 上限，故这些探针在 BC 侧恒可派生。
 *
 * 运行语义（单一 @Test，永不 skip，避免污染基线跳过计数）：
 * - 默认（CI / `gradlew test`）：读取已冻结 JSON，对每条向量用 BC 重算并断言逐字节一致（防漂移锁）；
 * - `-DexportArgon2Vectors=true`：先用 BC 重新派生整个语料并覆写 JSON，再执行同一防漂移校验。
 *
 * 字段约定与 JNI 签名逐一对齐（见 NativeArgon2.deriveKey）：type/version 为原始整数，
 * memoryKib 以 KiB 计，password/salt/secret/ad 均为**原始字节**（非 UTF-8 字符），outputLen=32。
 */
class Argon2BcVectorTest {

    /** 一条对照向量：输入参数 + 期望输出（十六进制）。 */
    private data class Vector(
        val name: String,
        val type: Int,          // 0=Argon2d, 2=Argon2id（对齐 JNI/C 约定）
        val version: Int,       // 16=0x10, 19=0x13
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val passwordHex: String,
        val saltHex: String,
        val secretHex: String?,
        val adHex: String?,
        val note: String? = null
    )

    private companion object {
        const val OUT_LEN = 32

        // 固定合成输入（非真实凭据）：复合密钥风格的 32B 口令
        const val PWD32 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val SALT16 = "0f0e0d0c0b0a09080706050403020100"
        const val SALT32 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val SECRET32 = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        const val AD16 = "0123456789abcdef0123456789abcdef"
        const val AD32 = "deadbeefcafebabe00112233445566778899aabbccddeeff0011223344556677"
        const val AD64 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"

        /** 冻结语料（覆盖矩阵 + 并行度 + 现实档位 + R2 长 AD 探针）。 */
        val CORPUS: List<Vector> = listOf(
            Vector("argon2id_v13_base", 2, 19, 2, 256, 2, PWD32, SALT32, null, null),
            Vector("argon2id_v10_base", 2, 16, 2, 256, 2, PWD32, SALT32, null, null),
            Vector("argon2d_v13_base", 0, 19, 2, 256, 2, PWD32, SALT32, null, null),
            Vector("argon2d_v10_base", 0, 16, 2, 256, 2, PWD32, SALT32, null, null),
            Vector("argon2id_v13_secret", 2, 19, 2, 256, 2, PWD32, SALT32, SECRET32, null),
            Vector("argon2id_v13_ad16", 2, 19, 2, 256, 2, PWD32, SALT32, null, AD16),
            Vector("argon2id_v13_ad32", 2, 19, 2, 256, 2, PWD32, SALT32, null, AD32),
            Vector("argon2id_v13_secret_ad32", 2, 19, 3, 256, 2, PWD32, SALT32, SECRET32, AD32),
            Vector("argon2d_v10_secret_ad16", 0, 16, 2, 512, 2, PWD32, SALT32, SECRET32, AD16),
            Vector("argon2id_v13_p1_salt16", 2, 19, 3, 256, 1, PWD32, SALT16, null, null),
            Vector("argon2id_v13_p4", 2, 19, 2, 512, 4, PWD32, SALT32, null, null),
            Vector("argon2id_v13_realistic_m4096", 2, 19, 3, 4096, 2, PWD32, SALT32, null, null),
            Vector(
                "argon2id_v13_ad64_probe", 2, 19, 2, 256, 2, PWD32, SALT32, null, AD64,
                note = "R2 长 AD 探针：BC 无 32B 上限；用于验证 RustCrypto AssociatedData::MAX_LEN"
            ),
            Vector(
                "argon2d_v13_ad64_probe", 0, 19, 2, 256, 2, PWD32, SALT32, null, AD64,
                note = "R2 长 AD 探针（Argon2d）"
            )
        )
    }

    @Test
    fun argon2BcVectorsAreFrozenAndReproducible() {
        val export = System.getProperty("exportArgon2Vectors")?.equals("true", ignoreCase = true) == true
        val file = vectorsFile()

        if (export) {
            val json = buildJson(deriveAll())
            file.parentFile?.mkdirs()
            file.writeText(json)
            println("[Argon2BcVectorTest] 已导出 ${CORPUS.size} 条 BC 对照向量 -> ${file.absolutePath}")
        }

        assertTrue(
            "对照向量文件缺失，请先运行 `gradlew :crypto:test -DexportArgon2Vectors=true` 生成：${file.absolutePath}",
            file.exists() && file.length() > 0
        )

        val parsed = MiniJson.parseVectors(file.readText())
        assertEquals("JSON 向量条数应与冻结语料一致", CORPUS.size, parsed.size)

        for (v in parsed) {
            val expected = hexToBytes(v.expectedOutHex)
            val actual = deriveBc(v)
            assertArrayEquals(
                "BC 重算与冻结向量不一致（防漂移锁触发）: ${v.name}",
                expected, actual
            )
        }
    }

    // ---- 派生 ----

    private data class Derived(val vector: Vector, val expectedOutHex: String)

    private fun deriveAll(): List<Derived> = CORPUS.map { v ->
        Derived(v, bytesToHex(deriveBc(v.toParsed())))
    }

    /** 解析态向量（含 expectedOutHex），供 BC 重算与断言共用。 */
    private data class ParsedVector(
        val name: String,
        val type: Int,
        val version: Int,
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val passwordHex: String,
        val saltHex: String,
        val secretHex: String?,
        val adHex: String?,
        val expectedOutHex: String
    )

    private fun Vector.toParsed() = ParsedVector(
        name, type, version, iterations, memoryKib, parallelism,
        passwordHex, saltHex, secretHex, adHex, expectedOutHex = ""
    )

    private fun deriveBc(v: ParsedVector): ByteArray {
        val bcType = when (v.type) {
            0 -> Argon2Parameters.ARGON2_d
            2 -> Argon2Parameters.ARGON2_id
            else -> throw IllegalArgumentException("非法 type=${v.type}（仅 0/2）")
        }
        val builder = Argon2Parameters.Builder(bcType)
            .withSalt(hexToBytes(v.saltHex))
            .withParallelism(v.parallelism)
            .withMemoryAsKB(v.memoryKib)
            .withIterations(v.iterations)
            .withVersion(v.version)
        v.secretHex?.let { builder.withSecret(hexToBytes(it)) }
        v.adHex?.let { builder.withAdditional(hexToBytes(it)) }

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(OUT_LEN)
        generator.generateBytes(hexToBytes(v.passwordHex), out)
        return out
    }

    // ---- JSON 产出 ----

    private fun buildJson(derived: List<Derived>): String {
        val bcVersion = try {
            // Provider.getInfo() 形如 "BouncyCastle Security Provider v1.85"，含版本号且非空
            org.bouncycastle.jce.provider.BouncyCastleProvider().info
        } catch (t: Throwable) {
            "unknown"
        }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"generatedBy\": \"BouncyCastle Argon2BytesGenerator ($bcVersion)\",\n")
        sb.append("  \"purpose\": \"Argon2 C->Rust 迁移 PoC 对照向量（Batch 0 冻结）；Rust 内核须逐字节复现 expectedOutHex\",\n")
        sb.append("  \"fieldConvention\": \"type: 0=Argon2d,2=Argon2id; version: 16=0x10,19=0x13; memoryKib 以 KiB 计; 输入均为原始字节(hex)\",\n")
        sb.append("  \"outputLen\": $OUT_LEN,\n")
        sb.append("  \"vectors\": [\n")
        derived.forEachIndexed { i, d ->
            val v = d.vector
            sb.append("    {\n")
            sb.append("      \"name\": ${q(v.name)},\n")
            sb.append("      \"type\": ${v.type},\n")
            sb.append("      \"version\": ${v.version},\n")
            sb.append("      \"iterations\": ${v.iterations},\n")
            sb.append("      \"memoryKib\": ${v.memoryKib},\n")
            sb.append("      \"parallelism\": ${v.parallelism},\n")
            sb.append("      \"passwordHex\": ${q(v.passwordHex)},\n")
            sb.append("      \"saltHex\": ${q(v.saltHex)},\n")
            sb.append("      \"secretHex\": ${v.secretHex?.let { q(it) } ?: "null"},\n")
            sb.append("      \"adHex\": ${v.adHex?.let { q(it) } ?: "null"},\n")
            if (v.note != null) sb.append("      \"note\": ${q(v.note)},\n")
            sb.append("      \"expectedOutHex\": ${q(d.expectedOutHex)}\n")
            sb.append("    }").append(if (i == derived.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- 路径解析（Gradle Test 默认 workingDir = 模块目录 crypto/；兜底仓库根） ----

    private fun vectorsFile(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        val relative = "src/test/resources/argon2-interop/argon2-bc-vectors.json"
        val asModule = File(base, relative)
        // Batch 5：C 源码已 git rm，改以 Rust crate 目录识别 crypto 模块
        val looksLikeCryptoModule =
            File(base, "build.gradle.kts").exists() && File(base, "src/main/rust").exists()
        return if (looksLikeCryptoModule) asModule else File(base, "crypto/$relative")
    }

    // ---- hex 工具 ----

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "非法 hex 长度: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * 极简 JSON 读取器：仅解析本文件写出的固定结构（顶层对象 → "vectors" 数组 → 扁平对象）。
     * 测试专用、零依赖；不支持嵌套容器/转义以外的复杂语法（本语料字符串均为 hex/ASCII，无转义需求）。
     */
    private object MiniJson {
        fun parseVectors(text: String): List<ParsedVector> {
            val vectors = ArrayList<ParsedVector>()
            // 按对象边界切分 "vectors" 数组内的每个 { ... }
            val arrStart = text.indexOf("\"vectors\"")
            assertNotNull("JSON 缺少 vectors 数组", arrStart.takeIf { it >= 0 })
            val body = text.substring(arrStart)
            var i = body.indexOf('[')
            require(i >= 0) { "vectors 后缺少 [" }
            i++
            while (i < body.length) {
                val objStart = body.indexOf('{', i)
                if (objStart < 0) break
                val objEnd = body.indexOf('}', objStart)
                require(objEnd > objStart) { "对象未闭合" }
                val obj = body.substring(objStart + 1, objEnd)
                vectors += ParsedVector(
                    name = str(obj, "name")!!,
                    type = int(obj, "type")!!,
                    version = int(obj, "version")!!,
                    iterations = int(obj, "iterations")!!,
                    memoryKib = int(obj, "memoryKib")!!,
                    parallelism = int(obj, "parallelism")!!,
                    passwordHex = str(obj, "passwordHex")!!,
                    saltHex = str(obj, "saltHex")!!,
                    secretHex = str(obj, "secretHex"),
                    adHex = str(obj, "adHex"),
                    expectedOutHex = str(obj, "expectedOutHex")!!
                )
                i = objEnd + 1
            }
            return vectors
        }

        /** 取 "key": "value"（value 为字符串，null 返回 null）。 */
        private fun str(obj: String, key: String): String? {
            val raw = valueAfter(obj, key) ?: return null
            val t = raw.trim()
            if (t.startsWith("null")) return null
            require(t.startsWith("\"")) { "字段 $key 期望字符串，实为: $t" }
            val end = t.indexOf('"', 1)
            require(end > 0) { "字段 $key 字符串未闭合" }
            return t.substring(1, end).replace("\\\"", "\"").replace("\\\\", "\\")
        }

        /** 取 "key": <int>。 */
        private fun int(obj: String, key: String): Int? {
            val raw = valueAfter(obj, key) ?: return null
            val t = raw.trim()
            if (t.startsWith("null")) return null
            val num = t.takeWhile { it.isDigit() || it == '-' }
            return num.toIntOrNull()
        }

        /** 返回 "key": 之后到行尾（本文件每字段独占一行）的原始片段。 */
        private fun valueAfter(obj: String, key: String): String? {
            val marker = "\"$key\""
            val idx = obj.indexOf(marker)
            if (idx < 0) return null
            val colon = obj.indexOf(':', idx + marker.length)
            if (colon < 0) return null
            val lineEnd = obj.indexOf('\n', colon).let { if (it < 0) obj.length else it }
            return obj.substring(colon + 1, lineEnd).trim().removeSuffix(",")
        }
    }
}
