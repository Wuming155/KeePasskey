package com.keepasskey.app.autofill

/**
 * Autofill 字段信号词表（账号 / 密码 / 搜索 / 非凭据 / OTP）。
 * 自 [AutofillFieldScanner] 拆出（单一职责 + 行数门禁）。
 */
internal object AutofillFieldLexicon {

    /** 账号类 label 词（CJK / 西里尔按子串匹配） */
    val USERNAME_SUBSTRING_TERMS = listOf(
        "用户名", "用戶名", "账号", "帐号", "帳號", "登录名", "登入名", "手機號", "手机号", "手机号码",
        "電話號碼", "电话号码", "登录", "登入",
        "логин", "логін", "пользовател", "користувач", "identifiant", "utilisateur", "usuario", "correo"
    )

    /** 账号类 label 词（拉丁文按 token 精确匹配） */
    val USERNAME_TOKEN_TERMS = setOf(
        "user", "username", "userid", "login", "loginid", "nickname", "account", "email", "mail",
        "phone", "mobile", "telephone", "tel"
    )

    /** 密码类 label 词（子串匹配） */
    val PASSWORD_SUBSTRING_TERMS = listOf(
        "密码", "密碼", "口令", "пароль", "паролі", "motdepasse", "mot de passe", "contraseña", "contrasena"
    )

    /** 密码类 label 词（token 精确匹配） */
    val PASSWORD_TOKEN_TERMS = setOf("password", "passwd", "pwd", "pass", "passphrase", "clave")

    /** OTP hint 白名单（归一化比较） */
    val OTP_HINTS = setOf("onetimecode", "smsotpcode", "2faappotpcode")

    /** 搜索框排除词（子串） */
    val SEARCH_SUBSTRING_TERMS = listOf(
        "搜索", "搜尋", "查询", "查詢", "筛选", "篩選", "search", "query", "keyword"
    )

    /** 搜索框排除词（token） */
    val SEARCH_TOKEN_TERMS = setOf("search", "query", "find", "filter", "keyword")

    /** 非凭据字段排除词（token） */
    val NON_CREDENTIAL_TOKEN_TERMS = setOf(
        "captcha", "verification", "verify", "otp", "comment", "feedback", "message", "reply", "promo", "coupon"
    )

    /** 非凭据字段强排除子串 */
    val NON_CREDENTIAL_SUBSTRING_TERMS = listOf(
        "feedback", "comment", "captcha", "verification", "coupon", "promo", "reply"
    )

    /** label 文本的 token 切分正则 */
    val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

    fun tokensOf(value: String): List<String> =
        TOKEN_SPLIT_REGEX.split(value).filter { it.isNotBlank() }
}
