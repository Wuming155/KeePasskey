package com.keepasskey.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * 全站 CJK 断行策略（ISSUE-P3-138）。
 *
 * 背景：Compose 默认 `LineBreak.Simple` 在无空格语言里**逐字**断行，中文界面因此出现
 * 越界观感——最典型的是**行末悬挂左括号**（如 `历史版本 (预` / 下一行 `览历史快照)`），
 * 属避头尾（禁则）违规；词中换行（`通行密` / `钥`）本身符合中文排版规范，不在整改范围。
 *
 * 取值依据（Android 官方 *Style text → CJK considerations*）：
 * - [LineBreak.WordBreak.Phrase]：不在同一短语单元内部断行，官方标注 "ideal for short text
 *   such as titles and UI labels"；
 * - [LineBreak.Strictness.Strict]：最严格的行首/行末禁则，决定哪些字符可以起行或收行；
 * - [LineBreak.Strategy.HighQuality]：官方建议 HighQuality 作为非 Balanced / Simple 场景的默认。
 *
 * 两条 CJK 参数**按 locale 生效**（官方明示 "line-breaking rules are defined based on locale"），
 * 拉丁语系走各自语言规则，故可安全地**全局套用**——无需按语言分支。
 */
internal val CjkLineBreak: LineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase
)

val Typography = Typography(
    // displayLarge 不再自定义（M3 官方 57sp 规格对手机竖屏过大，且此前 28sp 定义为僵尸样式）：
    // 解锁页大标题等超大字号场景直接使用下方专用的 HeroTitleStyle
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        lineBreak = CjkLineBreak
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        lineBreak = CjkLineBreak
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        lineBreak = CjkLineBreak
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        lineBreak = CjkLineBreak
    ),
    // 回归 M3 默认 14sp：原 13sp 在低端机型上正文可读性不足
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineBreak = CjkLineBreak
    ),
    // 密码列表副文案等次级正文：显式提升至 13sp，兼顾信息密度与可读性
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        lineBreak = CjkLineBreak
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineBreak = CjkLineBreak
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        lineBreak = CjkLineBreak
    )
)

/**
 * 解锁页等核心界面的大标题样式（Hero Title）：
 * 语义上不再占用 displayLarge 槽位，按单手竖屏信息层级定义为 24sp Bold
 */
val HeroTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.25).sp,
    lineBreak = CjkLineBreak
)

/**
 * 专用于密码、安全私钥、TOTP 验证码的等宽字体样式
 */
val MonospacePasswordStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    letterSpacing = 1.5.sp
)

val MonospaceTotpStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 26.sp,
    letterSpacing = 3.sp
)

/**
 * `ISSUE-P3-444` AC①：等宽开关**关闭**时的回落样式。
 *
 * 只把 `fontFamily` 换回默认族并把等宽的额外字距收回常规值——字号 / 字重 / 行高与等宽档
 * **逐项对齐**，故开关只改变字形宽度观感，不会引起排版层级变化（也不会让密码行的行高跳动）。
 */
private val DefaultFontPasswordStyle = MonospacePasswordStyle.copy(
    fontFamily = FontFamily.Default,
    letterSpacing = 0.25.sp,
    // 断行策略必须与 `Typography` 各槽位同源（ISSUE-P3-138）：凡显式落到 FontFamily.Default
    // 的槽位都要带 CjkLineBreak，否则该字号的文案退回逐字断行（`UiCjkLineBreakWiringTest` 机检）
    lineBreak = CjkLineBreak
)

private val DefaultFontTotpStyle = MonospaceTotpStyle.copy(
    fontFamily = FontFamily.Default,
    letterSpacing = 0.5.sp,
    lineBreak = CjkLineBreak
)

/**
 * 密码 / 安全私钥字段样式（`ISSUE-P3-444`）：受 [LocalMonospaceFields] 偏好裁决。
 *
 * 全站等宽样式一律经本访问器取值；直接引用 [MonospacePasswordStyle] 常量会让该处
 * 绕过「界面偏好」开关。默认（未注入偏好）恒返回等宽档＝改动前画面。
 */
@androidx.compose.runtime.Composable
fun passwordFieldStyle(): TextStyle =
    if (LocalMonospaceFields.current) MonospacePasswordStyle else DefaultFontPasswordStyle

/** TOTP 验证码样式（`ISSUE-P3-444`）：受 [LocalMonospaceFields] 偏好裁决，同 [passwordFieldStyle]。 */
@androidx.compose.runtime.Composable
fun totpFieldStyle(): TextStyle =
    if (LocalMonospaceFields.current) MonospaceTotpStyle else DefaultFontTotpStyle
