package com.keepasskey.app.ui.model

/** 省略号字符（单字符，长度参与预算计算） */
private const val ELLIPSIS = "…"

/**
 * 中段省略的**最小可用宽度**：至少容得下「首 1 字符 + 省略号 + 尾 1 字符」。
 * 小于该宽度时退化为纯截断（不做中段省略，避免产出只有省略号的空壳）。
 */
private const val MIN_MIDDLE_ELLIPSIZE_CHARS = 3

/** 路径中「目录段」与「文件名段」的分隔符（本仓展示的均为 POSIX 风格路径 / URL path） */
private const val PATH_SEPARATOR = '/'

/**
 * 路径 / 长文本的**中段省略**（ISSUE-P3-132 ②）。
 *
 * 缺陷形态：密码库卡片与选择器直接渲染完整 `path`，靠 `TextOverflow.Ellipsis` 兜底——
 * 而末端省略恰好砍掉**最有辨识度的文件名**，长路径还会被折成多行把卡片撑高
 * （外部 UI 评审：`/storage/emulated/0/...` 折行、观感拥挤）。
 *
 * 本函数保留头尾、折叠中段，且**优先保住最后一个路径段**（文件名），
 * 使 `/storage/emulated/0/Documents/preview.kdbx` 呈现为 `/storage/…/preview.kdbx`。
 *
 * 不变量（由单测锁定）：
 * 1. 返回长度 **恒 ≤ [maxChars]**；
 * 2. 原文不长于预算时**原样返回**（绝不为了好看而改写短路径）；
 * 3. 头部切点尽量落在路径分隔符上，绝不把文件名的首字符切掉；
 * 4. 纯函数、无副作用，可在宿主 JVM 上直接断言。
 */
fun middleEllipsize(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    if (maxChars < MIN_MIDDLE_ELLIPSIZE_CHARS) return text.take(maxChars)

    val budget = maxChars - ELLIPSIS.length
    val lastSeparatorIndex = text.lastIndexOf(PATH_SEPARATOR)
    val tailSegment = if (lastSeparatorIndex >= 0) text.substring(lastSeparatorIndex + 1) else ""

    // 尾部预算：优先整段保留文件名（含其前导分隔符）；文件名本身超预算时退回对半切
    val tailChars = if (tailSegment.isNotEmpty() && tailSegment.length + 2 <= budget) {
        tailSegment.length + 1
    } else {
        budget / 2
    }
    val tail = text.takeLast(tailChars)

    // 头部预算收敛到「不挤占尾部」；若头部区间内存在分隔符，则切在最后一个分隔符之后
    val headBudget = budget - tail.length
    val rawHead = text.take(headBudget)
    val separatorInHead = rawHead.lastIndexOf(PATH_SEPARATOR)
    val head = if (separatorInHead >= 0) rawHead.take(separatorInHead + 1) else rawHead

    return head + ELLIPSIS + tail
}

/**
 * 密码库路径展示预算（字符数）。
 *
 * 取值依据：卡片路径行为 `labelSmall`（11sp）配右侧时间戳，360dp 宽屏下可用宽度约 200dp，
 * 单字符均宽约 5.5dp ⇒ 约 36 字符；取 36 可在最窄常见屏上单行放下且不触发折行。
 */
const val DATABASE_PATH_MAX_CHARS = 36
