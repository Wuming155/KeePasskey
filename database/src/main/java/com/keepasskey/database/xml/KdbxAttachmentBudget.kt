package com.keepasskey.database.xml

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.InnerHeader

/**
 * 附件物化预算：**一次解析内**对两条内存放大通道的累计封顶。
 *
 * ISSUE-P1-276 拆分：本类原与 [BinaryNode] 同处 `KdbxXmlBinaryNode.kt`，计费口径重定后该文件
 * 逼近工程规则的 400 行阈值，故按职责（SAX 节点 / 预算记账）拆出本文件，语义与可见性
 * （`internal`，同模块可见）逐字不变。
 *
 * 两条通道同根（都由不可信 `.kdbx` 载荷的单节点/单次调用放大而来）：
 *
 * 1. **池引用副本乘法**（ISSUE-P2-48 / 审计 F-10 → ISSUE-P1-276 重定口径）：逐引用
 *    `item.load().copyOf()`（[BinaryNode.emit]）是 ISSUE-P3-07 的别名隔离契约防线，
 *    **不得取消**——但它意味着同一池条目被引用 N 次即产生 N 份独立副本。
 *    `KdbxXmlParser.MAX_XML_ELEMENTS`（元素数）与 `KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES`
 *    （整包字节）都只**间接**约束该乘积。
 *
 *    ### 旧判据与它的代数缺陷（ISSUE-P1-276 根因）
 *
 *    整改前按**整库累计**计费。设池内各条目尺寸 `sᵢ`、被引用次数 `nᵢ`，旧判据为
 *
 *     ```
 *     拒绝 ⟺ Σᵢ nᵢ·sᵢ > 2·Σⱼ sⱼ + 1 MiB                                 … (旧)
 *     ```
 *
 *    单条目（`sᵢ = s`，`Σⱼ sⱼ = s`）时代数化简为 `s·(N − 2) > 1 MiB` ⇒ `N = 4` 需
 *    `s > 512 KiB`、`N = 5` 需 `s > 341 KiB`、`N = 6` 需 `s > 256 KiB`；而 `s > 1 MiB` 的
 *    附件走落盘分支（[com.keepasskey.core.security.BinaryStorePolicy.shouldSpill]，**严格大于**）
 *    ⇒ `item.isSpilled` 时**不计费** ⇒ 受害区间恰为 **`(512 KiB, 1 MiB]`**（含 1 MiB 端点）：只要「中等尺寸附件
 *    ＋ 若干历史快照」，**自产库与合法第三方库都会被判为引用放大攻击、整库打不开**
 *    （本仓产物被自己的解析器判为损坏）。多条目不组合成安全：两个 1 MiB 内联附件各带
 *    2 条历史时 `6 MiB > 2×2 MiB + 1 MiB` 同样触发。
 *
 *    ### 现判据（ISSUE-P1-276 定版，两面并存，同为 fail-closed）
 *
 *    ```
 *    ① 单条目引用次数：nᵢ ≤ MAX_REFERENCES_PER_POOL_ITEM                … (①)
 *    ② 单条目物化字节：nᵢ·sᵢ ≤ MAX_POOL_ITEM_MATERIALIZED_BYTES         … (②)
 *    ```
 *
 *    ① 封放大**倍数**，② 封单条目**内存**。旧消息措辞「累计物化字节超出预算」在 ② 中原样
 *    保留，作用域由「整库累计」收窄为「单条目累计」（refIndex 落在消息里）。
 *
 *    **池级去重累计面已删除**：定版时另设第三面「去重引用集字节
 *    `Σ_{i∈去重引用集} sᵢ ≤ 2·Σⱼ sⱼ + 1 MiB`」，用于把「同一池条目被引 N 次只计 1 份」的
 *    口径写进代码。但去重引用集 ⊆ 池集合，左式上界就是池总字节，而由池派生的生产预算
 *    （2 倍池总字节再加余量）必定大于它 ⇒ 该式在任何生产预算下都不可能越界；直接构造方
 *    也只有 [unlimited] 与显式内联预算两种，无人注入更小的池预算，故该判据的累计字段与
 *    分支从未拦截过输入，已连同构造参数一并删除。单条目 ①② 才是有效拦截面，删除前后的
 *    总放大上界相同。
 *
 *    **取舍留痕（本批显式接受的残余）**：①② 只封「**单个池条目**被海量引用」这一放大面，
 *    总放大倍数因此从旧口径的「≤ 3× 池字节」放宽为「≤ MAX_REFERENCES_PER_POOL_ITEM × 池字节」——
 *    这是放弃整库字节判据的必然代价：把引用**分散**到多个池条目的构造不再被整库字节判据
 *    拦下，但其虚构样本必须自带与大池等量的文件字节，放大倍数与合法大库同阶。边界与重开
 *    条件见 `docs/architecture/已知工程限界.md` 的「内存与缓存」节。
 *
 * 2. **内联压缩附件解压放大**（ISSUE-P2-200 落点①）：`Compressed="True"` 的内联
 *    `<Value>` 一节点即可用 ~170 KiB 文本换出巨量字节。整改前判据是**每次 gunzip 调用**
 *    以整包上限 128 MiB 封顶，且多节点互不累计（名义最坏累计可达 GiB 量级）；
 *    真机取证（Redmi 4X / 192 MiB 堆）实测**单节点即 OOM**。现改为本对象持有的
 *    **全会话累计字节**预算 + **内联压缩附件节点数**上限，两者都在解压之前/之中生效。
 *
 * ## 内联累计上界取值（[MAX_INLINE_MATERIALIZED_BYTES] = 64 MiB）
 * - 远低于低端机堆界（实测 192 MiB），使单节点峰值（输出缓冲 + `toByteArray()` 副本）
 *   回落至堆界之内，而整改前的 128 MiB 单节点上限必然越界；
 * - 作为**累计**上界，多节点不再叠加（这是「每调用独立封顶」与「累计封顶」的本质差别）；
 * - 单节点输入本身另受 `KdbxXmlParser` 的 `MAX_TEXT_CHARS`（8 MiB 字符 ≈ 6 MiB Base64）
 *   约束，故正常压缩比（数倍）下的合法内联附件远达不到 64 MiB，不会误拒。
 *
 * 超限一律抛 [KdbxCorruptFileException]（fail-closed），视同文件损坏 / 疑似放大攻击。
 */
internal class AttachmentBudget(
    private val maxInlineMaterializedBytes: Long = MAX_INLINE_MATERIALIZED_BYTES,
    private val maxInlineCompressedNodes: Int = MAX_INLINE_COMPRESSED_NODES,
    private val maxReferencesPerPoolItem: Int = MAX_REFERENCES_PER_POOL_ITEM,
    private val maxPoolItemMaterializedBytes: Long = MAX_POOL_ITEM_MATERIALIZED_BYTES
) {

    /** 每个池索引的引用次数 `nᵢ`（判据①的来源）。 */
    private val poolItemReferenceCounts = HashMap<Int, Int>()

    /** 每个池索引已物化的字节 `nᵢ·sᵢ`（判据②的来源）。 */
    private val poolItemMaterializedBytes = HashMap<Int, Long>()

    private var accountedInlineBytes = 0L
    private var inlineCompressedNodes = 0

    /**
     * 计入本次池引用的物化开销（**在 `copyOf()` 之前**调用）。
     *
     * 两面判据与封闭公式见类 KDoc；任一越界即抛 [KdbxCorruptFileException]。
     *
     * @param refIndex 池索引（[BinaryNode] 仅在**池内命中**时才保留该索引，故恒落在池范围内）
     * @param bytes 该池条目尺寸 `sᵢ`（每个引用者都会物化同样多的一份）
     */
    fun accountPoolReference(refIndex: Int, bytes: Long) {
        val references = (poolItemReferenceCounts[refIndex] ?: 0) + 1
        poolItemReferenceCounts[refIndex] = references
        val materialized = (poolItemMaterializedBytes[refIndex] ?: 0L) + bytes
        poolItemMaterializedBytes[refIndex] = materialized

        // ② 单条目物化字节（旧措辞保留：作用域由「整库累计」收窄为「单条目累计」）
        if (materialized > maxPoolItemMaterializedBytes) {
            throw KdbxCorruptFileException(
                "附件池引用累计物化字节超出预算（refIndex=$refIndex 单条目累计 $materialized > " +
                        "$maxPoolItemMaterializedBytes，共被引用 $references 次），疑似引用放大攻击"
            )
        }
        // ① 单条目引用次数（放大倍数维度）
        if (references > maxReferencesPerPoolItem) {
            throw KdbxCorruptFileException(
                "单池条目被引用次数超出上限（refIndex=$refIndex 引用 $references 次 > " +
                        "$maxReferencesPerPoolItem），疑似引用放大攻击"
            )
        }
    }

    /**
     * 认领一个内联压缩附件节点额度（**在解压之前**调用）。
     *
     * 与字节预算互补：字节预算封「单个节点解出多少」，节点数上限封「多少节点各自付出
     * 解压固定开销」，杜绝以海量小压缩节点耗用 CPU 与缓冲。
     */
    fun claimInlineCompressedNode() {
        inlineCompressedNodes++
        if (inlineCompressedNodes > maxInlineCompressedNodes) {
            throw KdbxCorruptFileException(
                "内联压缩附件节点数超出安全上限（$inlineCompressedNodes > $maxInlineCompressedNodes），疑似解压炸弹"
            )
        }
    }

    /** 本节点解压可用的字节上限 = 累计预算剩余量（单节点与整库同时受约束）。 */
    fun remainingInlineBytes(): Long =
        (maxInlineMaterializedBytes - accountedInlineBytes).coerceAtLeast(0L)

    /** 计入本次内联附件物化出的字节数；超累计预算即 fail-closed。 */
    fun accountInlineMaterialized(bytes: Int) {
        accountedInlineBytes += bytes
        if (accountedInlineBytes > maxInlineMaterializedBytes) {
            throw KdbxCorruptFileException(
                "内联附件累计物化字节超出预算（$accountedInlineBytes > $maxInlineMaterializedBytes），疑似解压炸弹"
            )
        }
    }

    internal companion object {

        /**
         * 内联附件（含解压产物）的**本次解析累计**字节上限：64 MiB − 1（由
         * [AttachmentSizeLimits.MAX_ATTACHMENT_BYTES] 派生；`ISSUE-P2-311` AC① 起该值
         * 同源自写侧单字段上限，故由 `const` 放宽为 `val`）。
         * 取值依据与堆界关系见类 KDoc。
         */
        val MAX_INLINE_MATERIALIZED_BYTES: Long = AttachmentSizeLimits.MAX_ATTACHMENT_BYTES

        /** 内联压缩附件节点数上限：与二进制池条目上限同量级，远高于合法库的附件总量。 */
        const val MAX_INLINE_COMPRESSED_NODES: Int = 1024

        /**
         * **单个池条目**的引用次数上限（ISSUE-P1-276 判据①）：`nᵢ ≤ 本值`。
         *
         * 取值依据（1024）：
         * - 合法库中单个池条目的引用数 `nᵢ` = 1（宿主条目）+ 其历史快照数 + 跨条目共享数
         *   （去重器按内容共享同一索引），与 [InnerHeader.MAX_BINARY_POOL_ENTRIES] /
         *   [MAX_INLINE_COMPRESSED_NODES] 同量级；
         * - 攻击样本是「同一条目被引**数千次**」（回归用例 5000 次）⇒ 1024 既远高于合法库，
         *   又低于攻击样本一个量级。
         *
         * **取值依据复核（ISSUE-P3-292 落地后，2026-09-24）**：合并路径已改为在序列化 /
         * 上传 / 落库前按库级 Meta 的 `historyMaxItems` / `historyMaxSize` 截断历史
         * （`SyncConflictMergeAdjudication.truncateMergedHistory`，与保存路径共用
         * `HistoryManager.pruneHistory` 口径）⇒ 单附件的历史快照数**不再随合并轮次单调增长**。
         * 但本上限**仍不收紧**，理由：
         * ① 单个池条目的合法引用数上界是「共享该条目的条目数 × (1 + `historyMaxItems`)」，
         *    条目数这一维度本就不受历史截断约束；
         * ② `historyMaxItems` 的官方语义含 **-1 = 不限制**（合法配置），此时上界回到「无界」；
         * ③ 默认 Meta 下单个条目贡献 ≤ 11 次引用，1024 仅约等于 93 个共享条目的额度——
         *    已不宽裕，进一步收紧会对「多条目共享同一附件（去重）」的合法大库造成
         *    `ISSUE-P1-276` 同型的自伤型误拒。
         * ⇒ 结论：**维持 1024**。放宽仍被限界表的边界约束禁止，该表的「解除条件」已随本条
         * 落地改为「部分兑现、上限不可据此收紧」。
         */
        const val MAX_REFERENCES_PER_POOL_ITEM = 1024

        /**
         * **单个池条目**因被反复引用而允许物化的总字节上限（ISSUE-P1-276 判据②）：
         * `nᵢ·sᵢ ≤ 本值`。
         *
         * 取值依据：与内联通道的**单节点物化上界** [MAX_INLINE_MATERIALIZED_BYTES] 同量级
         * （同一「单节点在低端机堆界内的峰值」论证，见类 KDoc），故直接取自该常量而非另立
         * 字面量。它是「单条目 × 海量引用者」这一放大面的内存维度真拦截：引用倍数若把
         * 单个池条目的物化量推过该界即 fail-closed。
         * （`ISSUE-P2-311` AC①：随 MAX_INLINE_MATERIALIZED_BYTES 一并由 `const` 放宽为 `val`——
         * 上游取值改为同源派生后不再是编译期常量。）
         */
        val MAX_POOL_ITEM_MATERIALIZED_BYTES: Long = MAX_INLINE_MATERIALIZED_BYTES

        /** 不设上限（仅供不经 [KdbxXmlParser] 的直接构造 / 单测）。 */
        fun unlimited(): AttachmentBudget =
            AttachmentBudget(
                maxInlineMaterializedBytes = Long.MAX_VALUE / 2,
                maxInlineCompressedNodes = Int.MAX_VALUE,
                maxReferencesPerPoolItem = Int.MAX_VALUE,
                maxPoolItemMaterializedBytes = Long.MAX_VALUE / 2
            )
    }
}
