# 工程规则（Engineering Rules）

> 由 `CODEBUDDY.md` 索引。**写代码或改代码前必须通读并遵守本文件。**

## 高内聚、低耦合

- **单一职责**：一个类只有一个变化理由。kdbx 解析按职责拆文件（头部解析 / XML 元素 / 写入各自独立），不要聚成一个大杂烩。
- **包即边界**：跨包调用只走对方公开 API；标记 `internal` 的代码禁止被其他模块引用。
- **拒绝巨型类 / 巨型函数**：单文件超过约 400 行、单个函数超过约 50 行、嵌套超过 3 层时，必须拆分重构（参考项目 KeePassDX 的 `DatabaseInputKDBX.kt` 达 50KB，是明确的反面教材）。

## 禁止魔法数字 / 魔法字符串

- kdbx 格式中的文件签名、版本掩码、头部字段 ID、压缩标志、块类型等一律定义命名常量（`const val`，集中放在如 `KdbxConstants` 的对象中），例如 `0x9AA2D903` 必须写成 `SIGNATURE_1`。
- 状态、类型、模式用 `enum class` 或 `sealed class` 表达，禁止用裸 `Int` / `String` 传状态。
- 同一数值在多处出现时必须收敛到一个常量定义，禁止复制粘贴字面量。

## 依赖倒置与注入

- 高层模块依赖抽象而非实现：`sync` 层的 `FileStorage` 接口由抽象层定义，WebDAV / S3 实现类实现接口；调用方永远只见接口。
- 依赖关系一律通过 **Hilt 构造函数注入**，禁止在类内部硬编码 `new` 出协作者或手写单例获取（`DatabaseSession` 等既定单例除外，且须经 DI 提供）。
- 组合优先于继承：不搭建深层继承链；新增加密算法 / KDF / 同步后端时走既有的策略模式（`CipherEngine` / `KdfEngine` / `FileStorage`），只加实现类、不改调用方（开闭原则）。

## 错误处理

- 跨模块返回值统一使用 `core` 的 `Result` 类型表达成败，不向 UI 层抛裸异常。
- 各模块的异常类型集中定义在各自的 `exception` 包并继承清晰层次（如 kdbx 的签名错误、版本错误、KDF 参数错误分型）。
- 禁止空 `catch` 块或捕获后仅打印；IO / 解析处必须给出语义化错误信息。

## 敏感数据处理（密码管理器特有，违反即事故）

- 主密码、派生密钥、字段值等敏感序列用 `CharArray` / `ByteArray` 承载，用完在 `finally` 中显式清零；能用 Char 的地方绝不落到 `String`。
- 日志、异常消息、`toString()` 中严禁出现敏感明文。
- 测试代码使用虚构的假凭据与假数据，不使用真实密码。

## Compose UI 规则

- Screen Composable 不写业务逻辑：状态来自 ViewModel（`StateFlow`），事件回调上行；Screen 可预览、可用假数据独立渲染。
- 状态对象用不可变 `data class`，更新经 ViewModel 产出新实例，不在 Composable 内直接修改。

## 文件 IO 与原子写入（数据完整性）

> 密码管理器最严重的灾难是「保存时断电 / 崩溃导致 KDBX 损坏」。直接写入原文件是绝对禁区。

- **原子化写入（Safe-Write / Atomic Save）**：
  - 禁止直接对现有 `.kdbx` 原文件进行原地覆盖写入（Overwrite）。
  - 保存流程必须遵循：**写入临时文件（`.kdbx.tmp`）→ 同步落盘（`flush()` / `FileDescriptor.sync()`）→ 原子重命名（`renameTo`）替换原文件**。
  - 写入前对当前稳定版本保留滚动备份（如 `.kdbx.bak`），确保任意意外中断（OOM、断电、闪退）均不会损坏原库。
- **文件流强关闭与 Flush**：
  - 所有流操作必须使用 `use { ... }` 确保关闭；写入完成前必须显式调用 `outputStream.flush()` 与底层文件描述符的 `sync()`。

## 协程与并发规则

> 加密（Argon2）、XML 序列化、S3 同步都是重算力或高延迟操作，不约束会导致主线程 ANR 或协程泄露。

- **明确调度器语义（Dispatcher Boundary）**：
  - 严禁在 `Dispatchers.Main` 执行任何解密、KDF 运算、XML 解析与网络 IO。
  - CPU 密集型任务（Argon2 / AES 加解密、KDBX 解析）强制指定 `Dispatchers.Default`。
  - 文件写盘、网络请求（S3 / WebDAV）强制指定 `Dispatchers.IO`。
  - 仓库（Repository）对暴露的 `suspend` 函数负责其自身的线程安全，调用方无需在外层套 `withContext`。
- **作用域对齐生命周期**：
  - 禁止在 ViewModel 或 Service 外部使用裸 `GlobalScope`。
  - 涉及数据库落盘的临界写操作（Critical Section），必须通过注入的受控 Application 级别 `CoroutineScope` 运行，防止用户在保存途中切出 / 关闭页面导致协程被取消，进而引发数据写入半截。

## 系统扩展与凭据提供者规则（Android 14+）

> `CredentialProviderService` 是被系统服务直接拉起的独立组件，生命周期和运行上下文与主界面完全不同。

- **独立上下文与会话解耦**：
  - `CredentialProviderService` 是系统的扩展入口，生命周期短暂且可能在主 App 未启动时被唤醒。禁止假设「主 App UI 的 ViewModel 已经处于活跃状态」。
  - 认证与解密核心服务必须是无状态或严格基于共享的 Safe-Session 单例，能够快速被 Service 注入并调用。
- **超时自动熔断（Auto-Lock）**：
  - 内存中的解密密钥必须绑定严苛的超时机制（Timer），切入后台超过预设时长（如 5 分钟）或锁屏事件发生后，必须主动执行密钥内存清零并丢弃会话。
- **系统限制与性能预算**：
  - 系统对 Credential Provider 的响应有严格的超时限制（通常只有几秒）。查询和解密条目不可执行沉重的无关动作，杜绝阻塞系统身份验证弹窗。

## 防御性安全边界（Defense in Depth）

> 除了不用 `String`，还需防范系统级别的内存转储、截图泄露和剪贴板残留。

- **防止界面侧漏**：
  - 包含密码、Passkey 凭据详情的 Activity / Window 必须在 `onCreate` 中显式设置 `FLAG_SECURE`，禁止系统截图与在多任务最近视图（Recents）中预览明文。
- **剪贴板生命周期管理**：
  - 复制密码到剪贴板必须配置 Android 13+ 的 `EXTRA_IS_SENSITIVE` 标志。
  - 必须由后台调度协程在固定窗口（默认 30 ~ 60 秒）后主动覆盖 / 清空剪贴板内容，防止被第三方输入法或恶意应用监听。
- **防混淆与反射保护（ProGuard / R8）**：
  - KDBX 内部序列化模型与加解密引擎核心类，必须在混淆规则中保留（Keep），并防范未经授权的反射读取。
