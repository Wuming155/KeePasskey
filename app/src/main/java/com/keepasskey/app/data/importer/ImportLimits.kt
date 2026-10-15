package com.keepasskey.app.data.importer

/**
 * ISSUE-P3-19：明文导入框架的防御性上限与解析常量集中定义（工程规则：禁止魔法数字）。
 *
 * 全部上限均为 **fail-closed** 语义：超限即拒绝，绝不产生「部分落库 + 静默成功」。
 * 上限值本身与 UI 文案无关，属安全闸门参数。
 */
object ImportLimits {

    /** 单次导入条目数上限；超限整批拒绝（[ImportLimitExceededException]）。 */
    const val MAX_ENTRIES_PER_IMPORT: Int = 10_000

    /** 单个导入文件的字节上限（64 MiB）：明文导出文件量级远低于此，超限即拒绝。 */
    const val MAX_IMPORT_BYTES: Int = 64 * 1024 * 1024

    /** 单个字段的字符上限（1 Mi 字符）：防单字段（畸形引号未闭合）撑爆内存。 */
    const val MAX_FIELD_CHARS: Int = 1 shl 20

    /**
     * 单条记录（含表头行）的字段数上限。
     *
     * `ISSUE-P2-619`：文件体积闸门只约束总字节数，逗号密集行仍能在允许体积内生成海量
     * 字段对象（每个分隔符一个 `CharArray`）⇒ 解析器在**分配字段对象前**按此上限拒绝。
     * 取值远高于真实浏览器导出（Chrome/LastPass/Bitwarden 均 < 20 列）。
     */
    const val MAX_FIELDS_PER_RECORD: Int = 256

    /**
     * JSON 解析的**总节点数**预算（对象 / 数组 / 字符串 / 数值 / 字面量；对象键计入）。
     *
     * `ISSUE-P2-618`：体积与深度闸门不约束节点总数，`[0,0,...]` 这类小节点洪流可在
     * 64 MiB 内生成数千万节点 ⇒ 解析器在**节点分配前**累计并按此上限 fail-closed。
     * 取值校准：10k 条目上限下，Bitwarden 导出约 0.5M 节点、1PUX 约 1.3M 节点，均有裕量；
     * 而最坏形态（约 32M 节点）被拦在预算处，峰值分配可控。
     */
    const val MAX_JSON_NODES: Int = 2_000_000

    /**
     * JSON 单个容器的成员数上限（对象键值对 / 数组元素）。
     *
     * `ISSUE-P2-618`：防单个容器在总预算内膨胀到先耗尽堆。取值须高于合法导出的最大容器
     * （条目数组 ≤ `MAX_ENTRIES_PER_IMPORT`），取 1.6 倍裕量；合法导入的条目数检查仍按
     * `MAX_ENTRIES_PER_IMPORT` 在导出层执行，二者语义分离。
     */
    const val MAX_JSON_CONTAINER_MEMBERS: Int = 16_384

    /** 报告内保留的警告条数上限；超出部分折叠为一条 [ImportWarningReason.WARNINGS_TRUNCATED]。 */
    const val MAX_WARNINGS: Int = 64

    /** XML 元素嵌套深度上限（与库内 `KdbxXmlParser.MAX_XML_DEPTH` 同界，防解析炸弹）。 */
    const val MAX_XML_DEPTH: Int = 64

    /** 落库时允许匹配/创建的分组链最大层级；更深的尾部层级被丢弃（条目仍安全落库）。 */
    const val MAX_GROUP_PATH_DEPTH: Int = 32
}
