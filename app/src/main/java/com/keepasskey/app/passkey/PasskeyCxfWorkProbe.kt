package com.keepasskey.app.passkey

/**
 * 仅同模块测试可见的**工作缓冲观察器**（`ISSUE-P3-647`）。
 *
 * 为什么要它：本条缺陷的形态是「数组被清零了没有」——只看返回值无从判断
 * （`ByteJson.Str.wipe()` / `ByteSink.wipe()` 之后内容为零，与「从未被清零」在结果上完全一样）。
 * 故解析链在**分配时**把这些数组的引用交给本类，测试才能在 `read` / `begin` 返回后
 * 直接断言逐字节为零。
 *
 * 三类缓冲的所有权不同，故分三个清单（测试据此断言各自的终态）：
 * - [utf8Payloads]：入口 `CharArray → UTF-8` 的工作载荷，归 `PasskeyImportDraftHost.begin`；
 * - [sinkBuffers]（继承自 [ByteJsonScanProbe]）：扫描器 `ByteSink` 的每个容量代次，归扫描器；
 * - [stringCopies]（继承）：交给 AST 的字符串副本，归 AST，由 `PasskeyCxfReader.read` 递归清零；
 * - [derBytes]：私钥 Base64 解码出的 PKCS#8 DER 工作数组，归 `extractCxfPasskey`。
 *
 * 纪律（与 `WipableByteArrayOutputStream.observeBuffer` 同族）：
 * - 由调用方**逐次显式传入**，生产路径传 null；**禁止**注册成全局/单例观察者，
 *   否则会把一次解析的明文数组钉在整个进程生命周期里（比原缺陷更糟）；
 * - 观察者只许保存引用并读取，**不得回写、不得清零**——清零责任仍归解析链。
 */
internal class PasskeyCxfWorkProbe : ByteJsonScanProbe() {

    /** 入口 `CharArray → UTF-8` 的工作载荷数组。 */
    val utf8Payloads = ArrayList<ByteArray>()

    /** 私钥 Base64 解码出的 PKCS#8 DER 工作数组。 */
    val derBytes = ArrayList<ByteArray>()

    /**
     * 私钥 PEM 的**字符**工作数组（CXF 形来自 `derToPemChars`，KeePassXC 形来自 `asciiChars`）。
     *
     * 用于锁定 R2 的「转移 / 清零」二选一：每一个被观察到的数组，要么**同一个实例**交给
     * [ImportedPasskey] 且内容有效，要么已被清零——不允许既没交出也没擦除。
     */
    val pemChars = ArrayList<CharArray>()
}
