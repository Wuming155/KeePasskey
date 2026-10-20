package com.keepasskey.database.exception

/** 受理请求所属会话已失效；调用方必须停止，不能重试到后来活动的密码库。 */
class KdbxSessionChangedException : IllegalStateException("请求所属密码库会话已变化")
