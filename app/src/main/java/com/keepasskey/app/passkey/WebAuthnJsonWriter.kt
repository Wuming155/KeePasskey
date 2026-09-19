package com.keepasskey.app.passkey

/**
 * 极简 JSON **写入器**（零依赖、可 JVM 单测；`ISSUE-P3-188` 剩余清单第 4 项 §174 路线②）。
 *
 * ## 为什么不用 `org.json.JSONObject` 组装
 *
 * `org.json` 在宿主 JVM 单测里是未实现的 Android 桩（调用即抛 `Method put not mocked`，与本仓
 * [SimpleJson] 的立项理由相同）。断言响应材料（`PasskeyAssertionPayload.build`）自 §173 下沉为
 * 纯函数后，其安全语义（ISSUE-P2-72 归属省略 / PRF 绝不伪造 / authData 布局）需要**可离线执行**
 * 的四条断言（归属省略 / 空扩展对象 / authData 布局 / 公钥验签）——只有把组装换到宿主可用的
 * 写入口径上才可行。[SimpleJson] 是**解析器**（只读），本写入器补上**只写**一侧。
 *
 * ## 序列化语义（与 Android 平台 `org.json` 的输出形态对齐，设备侧对拍用例锁定）
 *
 * - **键序 = 插入序**（平台 `JSONObject` 内部为 `LinkedHashMap`）；**紧凑输出**（无空白）；
 * - **字符串转义**（对齐 AOSP `JSONStringer.string`）：`"` `\` `/` 以 `\\` 短转义（AOSP 版**转义
 *   `/`**——`https://` 会写作 `https:\/\/`，WebAuthn RP 侧 JSON 解析后语义等价）；`\t` `\b` `\n`
 *   `\r` `\f` 用短转义；其余 `≤ 0x1F` 写 `\u00xx`（小写四位十六进制）；`≥ 0x20` 一律原样（UTF-8）；
 * - **不实现数字与 null**：WebAuthn 响应材料无数字 / null 值（标识均为 base64url String、
 *   `enabled` 为 Boolean）；可缺席的键由调用方显式省略（ISSUE-P2-72 的「取不到即省略」语义，
 *   同时避免「null 值被序列化」的形态漂移）。
 *
 * ## 与既有静态守卫的关系
 *
 * 两处 payload 的既有守卫（`CredentialManagerCallerBindingWiringTest` 等）盯的是门控**调用点**，
 * 与本文件无交集；序列化输出的一致性由设备侧 `WebAuthnJsonWriterParityDeviceTest`
 * （真机 / 模拟器上与平台 `org.json` 逐字节对拍）常态锁定。
 */
internal object WebAuthnJsonWriter {

    /** 待写入的 JSON 对象（键序 = 插入序）。 */
    class Obj {

        private val pairs = LinkedHashMap<String, String>()

        /** 写入字符串值 */
        fun str(key: String, value: String) {
            pairs[key] = quote(value)
        }

        /** 写入布尔值 */
        fun bool(key: String, value: Boolean) {
            pairs[key] = value.toString()
        }

        /** 写入嵌套对象 */
        fun obj(key: String, value: Obj) {
            pairs[key] = value.render()
        }

        /** 写入字符串数组 */
        fun strArray(key: String, values: List<String>) {
            pairs[key] = values.joinToString(",", "[", "]") { quote(it) }
        }

        /** 紧凑序列化（键序 = 插入序，无空白） */
        fun render(): String = pairs.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:$v" }
    }

    /** 便捷入口：`WebAuthnJsonWriter.obj { str("type", "...") }` */
    fun obj(block: Obj.() -> Unit): String = Obj().apply(block).render()

    /** 字符串 JSON 转义（对齐 AOSP `JSONStringer.string`，见类 KDoc） */
    internal fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c.code <= 0x1F) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
