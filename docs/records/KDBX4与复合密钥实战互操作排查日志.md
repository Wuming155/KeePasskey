# KDBX 4.0 与复合密钥真机互操作实战排查日志

## 1. 概述与测试背景

- **测试日期**：2026-09-05
- **测试环境**：Android 16（API 36，x86_64）桌面模拟器（Pixel_10）+ WHPX 硬件加速
- **测试目标**：使用真实 KeePass 生态生成的文件，在真机环境下进行端到端解锁、条目读写及双向往返兼容性验收
- **测试资料**：
  - 数据库文件：`测试.kdbx`（KDBX 4.0 格式，ChaCha20 加密，Argon2d 密钥派生）
  - 密钥文件：`111.keyx`（KeePass 2.x XML KeyFile v2.0 格式，带 32 字节 Hex 密钥及 Hash 属性校验）
  - 主密码：`xdqaCEGFEAHBETAH72732/*632.`
  - 库内数据：包含用户创建的子群组「`111`」及条目「`11`」（密码明文 `~W4hUziUy7FSRR#K@N@K`）

---

## 2. 排查发现的关键缺陷与根因剖析

在真机测试与对照 **KeePass 2.61.1 官方 C# 源码**、**KeePassDX (Kotlin)**、**KeePassXC (C++)** 以及 **pykeepass** 的过程中，共定位并根治了 6 项深层互操作与架构缺陷：

### 2.1 解锁页「密钥文件」开关虚假断链（P0 架构级）
- **根因分析**：
  - `UnlockUiState` 中 `keyFileName` 硬编码为 `"master.key"`，`onToggleKeyFile()` 仅在 UI 内部切换一个布尔值；
  - `UnlockViewModel.unlock()` 调用 `vaultRepository.unlockActiveDatabase(passwordChars, readOnly)` 时**完全没有传递密钥文件数据**；
  - 数据层 `DatabaseSession.open` 虽然具备 `keyFileData: ByteArray?` 接收能力，但上层通道完全闭塞。
- **整改落位**：
  - `VaultRepository.unlockActiveDatabase` 与 `RealVaultRepository` 扩充 `keyFileData: ByteArray?` 借用语义参数，成功后会话自动克隆缓存并供保存写回使用；
  - `UnlockScreen` 接入系统级 SAF 真实文档选择器（`ActivityResultContracts.OpenDocument`），安全读取所选文件字节并传给 ViewModel；
  - 内存纪律：密钥文件字节全程以 `ByteArray` 驻留并在取消/成功/销毁时显式 `fill(0)` 擦除；在复合密钥模式下，防御性跳过无法还原该因子的硬件 QuickUnlock / 生物识别缓存。

### 2.2 密钥文件（.keyx）未按官方语义解析（P0 算法级）
- **根因分析**：
  - `KdbxFile.deriveKeys` 历史实现直接执行 `HashUtil.sha256(keyFileData)`；
  - 但 KeePass 官方规范与 KeePassDX / KeePassXC 标准对密钥文件存在严格的解析梯子：
    - XML KeyFile（`.keyx`）：`<Version>1.0</Version>` 提取 `<Data>` 做 Base64 解码；`<Version>2.0</Version>` 提取 `<Data>` 做十六进制解码，并校验 `Hash` 属性（SHA-256 前 4 字节）；
    - 恰好 32 字节的二进制文件：直接作为 32 字节密钥；
    - 恰好 64 字节的十六进制文本：Hex 解码为 32 字节密钥；
    - 其他任意文件：才回退整文件 SHA-256。
  - 由于测试文件 `111.keyx` 为 XML v2.0，整文件 SHA-256 必然派生出错误的复合密钥。
- **整改落位**：
  - 新增 `KdbxKeyFile` 统一解析器，严格实现上述 4 级解析梯子与 Hash 属性防篡改校验；
  - 编写 10 项全场景单元测试（`KdbxKeyFileTest`），覆盖真实世界 `111.keyx` 向量，100% 绿灯。

### 2.3 Argon2 与 Cipher UUID 声明错误（P0 规范级）
- **根因分析**：
  - `KdbxConstants.kt` 中声明的 `Kdf.ARGON2D` 为 `EF636DDF-8C29-444B-91F7-A948E42D3E28`（错误），而官方事实标准 UUID 为 `EF636DDF-8C29-444B-91F7-A9A403E30A0C`；
  - `ARGON2ID` 同样错误；`CHACHA20` 与 `TWOFISH` 的 Cipher UUID 也与官方不符；
  - 导致遇到 KeePass 官方默认生成的 Argon2d 库直接抛出「未知的 KDF 算法」。
- **整改落位**：
  - 对照 KeePassDX `Argon2Kdf.Type` 与 KeePassXC `KeePass2.h`，修正全部 4 个 UUID 为官方事实标准。

### 2.4 KDBX4 变体字典类型转换 ClassCastException（P1 互操作级）
- **根因分析**：
  - 官方 KeePass 2.x 序列化 KDBX4 Header 变体字典时，Argon2 的并行度 `P` 与版本 `V` 以 `UInt32` 类型写出（反序列化后以 `Int` 驻留）；
  - `KdbxHeader.kt` 在读取时调用 `vd.getUInt64("P")`，其内部硬编码 `item.value as Long`，触发 `ClassCastException: Integer cannot be cast to Long`。
- **整改落位**：
  - `VariantDictionary` 的 `getUInt32`/`getUInt64`/`getInt32`/`getInt64` 改造为类型宽容转换，`Int` 与 `Long` 间平滑自适应并做无符号掩码保护；
  - `KdbxHeader.kt` 读取侧对齐 KeePassDX，规范改用 `getUInt32("P")`。

### 2.5 HMAC 认证块签名数据遗漏 8 字节块索引前缀（P0 协议级）
- **根因分析**：
  - 官方标准与 KeePassXC `HmacBlockStream.cpp` 中，数据块的 HMAC 签名入参为：
    `HMAC-SHA256(key = blockKey, data = LittleEndian64(blockIndex) ‖ LittleEndian32(blockSize) ‖ blockData)`；
  - 原实现漏掉了 `LittleEndian64(blockIndex)` 前缀，导致数据解密前的 HMAC 块 #0 恒定校验失败。
- **整改落位**：
  - 在 `HmacBlockStream` 的全量与流式读写路径（`readAll`、`writeAll`、`HmacBlockInputStream`、`HmacBlockOutputStream`）中全面补齐 8 字节块索引前缀。

### 2.6 载荷解密与 GZIP 解压及内层 Header 顺序颠倒（P0 格式级）
- **根因分析**：
  - 原实现以为载荷结构是：`密文 → 解密 → 裸内层 Header → GZIP 解压 → XML`；
  - 查阅 KeePass 2.61.1 官方 C# 源码（`KdbxFile.Read.cs:172-178`：`sXml = new GZipStream(sPlain); LoadInnerHeader(sXml);`，注释明确说明 `// Binary header before XML`）以及 KeePassDX `DatabaseInputKDBX.kt:201-207`，事实标准为：
    `密文 → 解密 → GZIP 解压 → 从解压流读取内层 Header → 从解压流解析 XML`；
  - 正确密钥解密出来的首个数据块，实际上是 GZIP 文件的魔数头（`1F 8B 08`）。原代码拿 GZIP 压缩字节当内层 Header 解析必然崩溃，首块裁决探针也因此双双误判。
- **整改落位**：
  - `KdbxFile.load` 与 `save` 全链路调整：将 `innerHeader` 序列化与反序列化移入 GZIP 流内部；
  - 派生裁决探针更新为识别 GZIP 压缩魔数前缀（`1F 8B 08`），同时保留未压缩库的 Header 字段结构探测。

### 2.7 官方 cipherKey 与 hmacKey64 派生公式归位（P0 密码学级）
- **根因分析**：
  - 早期历史版本存在认知偏差，曾将正确的 `cipherKey = SHA-256(masterSeed ‖ transformedKey)` 误标为 legacy，反而将无尾部常量的 `SHA-512(...)` 截断当作官方标准；
  - 查阅官方 C# `KdbxFile.ComputeKeys` 与 KeePassXC `KeePass2.cpp`，权威公式如下：
    - `cipherKey = SHA-256(masterSeed ‖ transformedKey)`（AES-256 需要 32 字节密钥）；
    - `hmacKey64 = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)`；
    - `blockKey(i) = SHA-512(LittleEndian64(i) ‖ hmacKey64)`；
    - `headerKey = SHA-512(LittleEndian64(0xFFFFFFFFFFFFFFFF) ‖ hmacKey64)`。
- **整改落位**：
  - 彻底归正 `KdbxFile.deriveKeys` 的官方派生公式，历史的 SHA-512 截断公式仅保留为旧文件探针的回退分支（`isLegacy = true`）。

---

## 3. 调试与实战踩坑经验总结

在本次真机自动化与黑盒排查中，积累了若干极高价值的实战经验：

1. **Windows Git Bash 终端管道 CRLF 篡改陷阱**：
   - 使用 `adb shell "run-as ... cat file" > local_file` 拉取二进制文件时，MSYS/Git Bash 的 stdout 重定向机制会默认将换行符 `0x0A` 转换为 Windows 的 `0x0D 0x0A`（CRLF），导致拉出来的 `.kdbx` 出现诡异的字节膨胀和校验失败；
   - **防坑准则**：拉取二进制必须使用 `adb pull`，或在设备端先复制到 `/data/local/tmp` 后以真正的二进制安全通道拉取。

2. **FLAG_SECURE 与 UI 观测**：
   - 生产环境中默认开启的 `FLAG_SECURE` 会导致原生截图全黑；
   - **实战经验**：自动化测试可通过 UI Automator 的 Accessibility 控件树（`uiautomator dump`）获取不受 `FLAG_SECURE` 影响的高保真结构树进行交互与定位。

3. **Argon2 纯 JVM 实现在模拟器上的耗时特征**：
   - 在内存受限或启用交换内存的 Android 模拟器上，64MiB 内存 + 89 次迭代的 Argon2d 计算耗时可达 30 秒左右；
   - **排查注意**：点击解锁后界面看似无响应并非卡死或未触发，而是后台协程正在密集执行 KDF 计算，切忌误判为 UI 冻结。

---

## 4. 最终双向往返验证结果

1. **单元测试回归**：
   - 运行全工程单元测试：`:app`, `:core`, `:crypto`, `:database`, `:sync` 全模块通过（`BUILD SUCCESSFUL`，212+ 样例全绿）。
2. **模拟器真机解锁**：
   - 在 Pixel_10（Android 16）模拟器中输入真实主密码，并由系统 SAF 选择器成功加载 `111.keyx`；
   - 顺利解锁进入，完整展示群组「`111`」及条目「`11`」，揭示明文密码为 `~W4hUziUy7FSRR#K@N@K`。
3. **真实保存与外部独立复验**：
   - 在 KeePasskey 中新建凭据条目，保存并写入磁盘；
   - 使用第三方官方参考解析工具 `pykeepass` 读取该文件，验证输出：
     ```text
     === PYKEEPASS 往返打开完全成功！ ===
     KDBX Version: (4, 0)
     Database Name: '测试'
     Groups count: 2 (根群组, 111)
     Entries count: 3 (包含原条目 11 及 KeePasskey 新建条目)
     ```
   - 证明 KeePasskey 生成与修改的 KDBX 文件已具备工业级互操作兼容性。
