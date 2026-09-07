package com.keepasskey.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    // displayLarge 不再自定义（M3 官方 57sp 规格对手机竖屏过大，且此前 28sp 定义为僵尸样式）：
    // 解锁页大标题等超大字号场景直接使用下方专用的 HeroTitleStyle
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    // 回归 M3 默认 14sp：原 13sp 在低端机型上正文可读性不足
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    // 密码列表副文案等次级正文：显式提升至 13sp，兼顾信息密度与可读性
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
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
    letterSpacing = (-0.25).sp
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
