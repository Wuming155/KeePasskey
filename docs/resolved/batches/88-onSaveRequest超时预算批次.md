<a id="s88"></a>
## §88 `onSaveRequest` 超时预算批次（2026-09-16）：ISSUE-P3-122（IPC-10）

> **本批次缘起**：`ISSUE-P3-122` 的 4 项 IPC 裁定（第四轮）之后，残余整改为两项：
> `IPC-01`（认证 requestCode 单调化，已在 **§84** 完成）与 `IPC-10`（`onSaveRequest` 超时预算）。
> 本批完成 `IPC-10`，该条据此闭环。

### 88.1 后果比原记更重：不是 Availability，而是「系统 UI 永久等待」

原记把 `IPC-10` 描述为「无超时且平台不提供 `CancellationSignal`（后果有界，仅 Availability）」。
复核后确认**后果更重**：平台对该回调不提供取消入口，故一旦处理体不返回，**系统的保存 UI 会一直等**
（用户在这一屏上没有出口）。而处理体里恰好有一个可长等的调用：
`runtimeIntegrityGate.awaitEnforcement()`——首次扫描未完成时它会等待**一整个扫描周期**。

### 88.2 修复

```kotlin
serviceScope.launch {
    val handled = withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS) { handleSaveRequest(structure, callback) }
    if (handled == null) {
        AppLog.w(TAG, "保存请求超出 …ms 预算，按『本次无需保存』收尾")
        callback.onSuccess()
    }
}
```

- **预算**：`SAVE_REQUEST_TIMEOUT_MS = 5_000L`，比填充预算（`AUTOFILL_TIMEOUT_MS = 4_000L`）宽 1 秒
  ——保存路径含一次库写入与落盘，但同样**必须有上限**。
- **超时收尾语义**：`onSuccess()`＝「本次无需保存」。它给系统一个明确答复，**不落库、不报错**，
  与「用户关闭保存提示」时的既有收敛路径**同一语义**；同时落一条脱敏 WARN，使线上能区分
  「超时」与「本来就不需要保存」。
- **服务解绑不变**：处理体仍 `catch (c: CancellationException) { throw c }`——
  解绑后**不得**回调（无状态服务契约）。`withTimeoutOrNull` 只吞**自己**的超时取消，
  父作用域取消会照常向上传播，两者语义不冲突。

### 88.3 一处**编译期**硬约束（本批踩到的坑，留痕）

首版实现是「就地包一层」——直接在 `serviceScope.launch` 内把原 `try { … } catch …` 包进
`withTimeoutOrNull { … }`。**编译失败**，报 `'return' is prohibited here` 与
`Suspension functions can only be called within coroutine body`。根因：

> **`kotlinx.coroutines.withTimeoutOrNull` 不是 inline 函数**（签名是
> `suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend CoroutineScope.() -> T): T?`），
> 因此其 lambda 内的 `return@launch` 属**非局部返回，不被允许**。

故处理体**必须**抽为普通 `private suspend fun handleSaveRequest(...)`，把原来的
`return@launch`（早退分支）改为普通 `return`——抽取是**编译期硬要求**，不是风格选择。
该约束由接线守卫钉住（见 §88.4 第 3 条），避免后人「顺手」把它合回 lambda 内。

**另一次自伤（同一批内）**：守卫首版用 `substringBefore("}")` 截取超时分支窗口——
而该分支的日志用了字符串模板 `${SAVE_REQUEST_TIMEOUT_MS}`，其中**就含 `}`**，
于是窗口被截在半截、误判为「没有回调」。已改为固定长度窗口。这是本会话**第三次**
同类问题（§81.6 注释误命中、§84.5 固定字符窗口对注释长度敏感）：**源码文本锚点必须锚在
语义结构或固定窗口上，不能锚在「遇到的第一个某字符」上**。

### 88.4 新增覆盖

`AutofillSaveTimeoutWiringTest`（新，4 例，源码接线守卫）：

| 用例 | 钉住的形态 |
|---|---|
| 保存回调必须整体受超时预算约束 | 存在 `withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS) {` 且常量存在 |
| 超时必须向系统给出答复而非静默悬挂 | 超时分支内有 `callback.onSuccess()` **且**有 `AppLog.w(TAG,`（可区分「超时」与「无需保存」） |
| 处理体必须抽为普通 suspend 函数 | 存在 `private suspend fun handleSaveRequest(`，且其体内**不得**残留 `return@launch` |
| 服务解绑仍不得回调 | 处理体保留 `catch (c: CancellationException)` + `throw c` |

### 88.5 验证证据（2026-09-16）

- `:app:testDebugUnitTest --tests "com.keepasskey.app.autofill.*"` → **BUILD SUCCESSFUL**
  （`AutofillSaveTimeoutWiringTest` 4/4，其余 autofill 套件全绿）。
- 全量 `test --rerun-tasks --max-workers=1`、`assembleRelease`、真机
  `:app:connectedDebugAndroidTest` 结果见提交信息。

### 88.6 下一批

1. 表内其余 P3：`P3-76`（框架阻塞的已接受残余）、`P3-120`（真机 Frida 实测，**P1/UNVERIFIED**，
   为 4 条目的共同前提）、`P3-121`（内网 WebDAV 产品口径）、`P3-125` ①（选择器零匹配；
   AC 提供「改挂占位数据集」或「留痕接受现设计」两条路）。
2. P2 三条仍卡决策点（`P2-47` AC②、`P2-73` 数据集回传、`P2-79` 产品口径）。

---

## 归档索引行原文（无损迁移承接）

> 迁移前本批次的正文同时存在于三处：总索引行、分册级索引行、本文件。以下按「只搬迁、不改写」
> 原文照录两处索引行正文（更正须另加小节，不得就地改），自此两处索引只留一行指针。
> 承接批次：§154。

### 总索引行原文（§88）

`onSaveRequest` 超时预算批次（`ISSUE-P3-122` 的 `IPC-10`；`IPC-01` 见 §84）：**后果比原记更重**——平台对该回调不提供 `CancellationSignal`，故「无上限」直接等于**系统保存 UI 永久等待**（而 `awaitEnforcement()` 在首次扫描未完成时可等待一整个扫描周期）；现经 `withTimeoutOrNull(SAVE_REQUEST_TIMEOUT_MS = 5_000L)` 包裹，超时按「本次无需保存」收尾（`onSuccess`，给系统明确答复、不落库、不报错），解绑仍原样重抛 `CancellationException`。**一处编译期硬约束留痕**：`withTimeoutOrNull` **不是 inline**，处理体必须抽为普通 `suspend fun`（否则其中 `return@launch` 属非局部返回、无法编译），抽取后早退分支改普通 `return`、语义逐字不变；另记录第三次「源码文本锚点」自伤（`${…}` 中的 `}` 截断窗口）
