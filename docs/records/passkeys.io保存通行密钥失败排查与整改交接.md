# passkeys.io 保存通行密钥失败排查与整改交接

> **文档定位**：记录 2026-09-21 在真机 Redmi 4X（`lineage_Mi8937_4_19`，Android 17 / API 37）排查 passkeys.io 创建/保存通行密钥失败的完整事实、已完成的应用侧整改、剩余环境侧阻塞点与后续建议。

---

## 1. 问题现象与初次复现

- **用户报告**：在 passkeys.io 上测试保存通行密钥失败。
- **环境前提**：
  - 设备：Redmi 4X（santoni，LineageOS，Android 17 / API 37，无 Google Play Services）。
  - 安装构建：`com.keepasskey` 0.1.0 debuggable（未开启 R8 混淆，主 `classes.dex` 约 39.77 MB，总 dex 约 76 MB）。
- **初次抓包复现**（Firefox 打开 passkeys.io 触发流程）：
  ```text
  07:51:19.099 I CredentialManager: Provider session created and being added for: ComponentInfo{com.keepasskey/...}
  07:51:19.187 I ActivityManager: Start proc 8674:com.keepasskey/u0a252 for bound-service {com.keepasskey/...}
  07:51:22.108 I CredentialManager: Remote provider response timed tuo for: ComponentInfo{com.keepasskey/...}
  07:51:22.112 I CredentialManager: Provider Status changed with status: CANCELED, and source: REMOTE_PROVIDER
  ```
  - 系统给凭据提供者的应答预算约 **3.001 ~ 3.002 s**。
  - 在整改前，应用进程冷启动耗时过长，系统在 **3.0 s** 刚到即触发超时取消，导致客户端抛出 `TYPE_NO_CREATE_OPTIONS` 或断开，用户视角即「保存失败 / 点了没反应」。

---

## 2. 耗时分解与定位

为了精确定位 3.0 s 预算内各阶段的开销，引入分段计时埋点，在 Redmi 4X 上测得的原始时序：

```text
49.756 Start proc
49.861 libframework load
49.958 ApplicationLoaders
   [1.40 s 间隙：ART 打开 39.77 MB debug dex 并进行类校验]
51.361 nativeloader Configuring clns-9
51.384 GraphicsEnvironment
51.508 WM-WrkMgrInitializer: Initializing WorkManager（ContentProvider 阶段占 0.41 s）
51.994 MainApplication.onCreate-begin
52.018 MainApplication.onCreate-end（Application.onCreate 仅 24 ms）
52.207 Service onBeginCreateCredentialRequest entry
52.255 entry-parse-done
53.018 PublicSuffixList load done（PSL 加载耗时 760 ms ~ 1000 ms）
53.020 Passkey CreateEntry returned
52.723 [系统 3.0 s 超时线触发]
```

### 瓶颈归因：
1. **应用侧主要瓶颈（1.15 s）**：`PublicSuffixList` 首次装载耗时 **0.76 ~ 1.05 s**（解析 16,475 行 / 10,323 条规则），它作为 `rp.id` 归属校验的同步前置条件，卡在应答路径正中间。分析证实瓶颈不是算法复杂度，而是自写的 Kotlin 逐字节循环在解释执行环境下的单位指令耗时过高。
2. **启动阶段冗余工作（0.41 s）**：`WorkManager` 默认的 `androidx.startup` initializer 在 `ContentProvider` 阶段同步建库、拉起调度器。
3. **平台层硬开销（2.1 ~ 2.6 s）**：ART 虚拟机对于 unminified debug 构建，每次冷启动必须重新打开并校验 39.77 MB 的主 `classes.dex`。

---

## 3. 已落地的应用侧整改（Commit: `ffa7b0c`）

| 模块 / 文件 | 整改内容 | 效果 |
|---|---|---|
| `PublicSuffixList.kt` | 改为**按末标签（TLD）懒加载**：利用 `indexOf("$tld\n")` 单趟框架方法快速定位目标 TLD 规则行，非 ASCII 规则归一移出热路径。 | 首次解析耗时由 **1000 ms 降至 210~260 ms**。 |
| `app/build.gradle.kts` | 添加 `androidResources { noCompress += "dat" }`，PSL 资源由 DEFLATE 改为 STORED 不压缩存放。 | 资源解包读取耗时由 **0.13 s 降至 0.03 s**。 |
| `AndroidManifest.xml`<br>`MainApplication.kt` | 移除 `androidx.work.WorkManagerInitializer`，`MainApplication` 实现 `Configuration.Provider`，WorkManager 变为后台调度时按需初始化。 | 移除启动 ContentProvider 阶段主线程约 **0.41 s** 的开销。 |
| `credential_provider_service.xml` | 参考参考项目 Monica 的实测经验，补充声明兼容性占位 `<capability name="android.credentials.TYPE_PUBLIC_KEY_CREDENTIAL" />`。 | 避免非 Pixel 厂商 ROM 只匹配旧规范字符串导致过滤失效。 |

### 整改后测试验证：
- **JVM 单元测试**：新增 `PublicSuffixListTest`（对全量 PSL 规则与独立引用实现进行穷举一致性比对）与 `PublicSuffixListResourceTest`（机检 LF、小写、无首尾空白三项前提）。
- 全仓单测：**`xml=347 tests=2433 failures=0 errors=0 skipped=13`** 全部通过。

---

## 4. 真机验证与环境阻塞点

### 4.1 独立真实客户端（`com.keepasskey.test`）验证
使用项目自带的真实凭据保存客户端发起冷启动通行密钥创建测试：
- 预校验状态下（`cmd package compile -m verify -f com.keepasskey`），整链路耗时稳定在 **1.32 ~ 1.58 s**（**6/6 轮全部成功收到 `SAVE_ENTRIES_RECEIVED`，零超时**）。
- 成功在真机屏幕弹出系统级创建凭据抽屉（「KeePasskey / 要创建通行密钥以便登录…」），证明 **KeePasskey 作为凭据提供方的系统通道与应答速度已达标**。

### 4.2 浏览器实测中的环境阻塞点
在尝试通过真机浏览器访问 passkeys.io 进行端到端点击验证时，捕获到外部环境的阻塞异常：

#### (1) Firefox 140.0（真机当前安装版本）：
点击「Create a passkey」后报错 `The request either timed out, was canceled...`，日志显示：
```text
09:00:51.979 W/GooglePlayServicesUtil: org.mozilla.firefox requires the Google Play Store, but it is missing.
09:00:51.983 W/WebAuthnTokenManager: Failed to get FIDO intent
09:00:51.983 W/WebAuthnTokenManager: com.google.android.gms.common.api.ApiException: 17:
    API: Fido.FIDO2_PRIVILEGED_API is not available on this device. Connection failed with: ConnectionResult{statusCode=SERVICE_INVALID}
```
- **原因**：Firefox 140.0 在无 Google Play Services 的 ROM 上，其 WebAuthn 创建路径（`makeCredential`）回退/直连到了 Google Play Services FIDO2 私有接口。在没有 GMS 的设备上抛出 `SERVICE_INVALID`，在浏览器内部直接捕获失败，**整个过程完全未调用 Android 系统的 Credential Manager**（系统日志中该时刻 Credential Manager 记录为 0）。
- **补充调研**：查阅 mozilla-release（最新正式版）源码可知，后续版本中的 `webAuthnMakeCredential` 已调整为优先无条件尝试 Android Credential Manager，仅在抛出 `NOT_SUPPORTED_ERR` 时才回退 GMS。当前真机上的 140.0 尚不具备该平滑路由。

#### (2) Via 浏览器（WebView 内核）：
Via 是基于系统 WebView（`com.android.webview` 151.0）封装的浏览器，点击 passkeys.io 的创建同样秒报错，日志同样 0 条 Credential Manager 记录。WebView 的 WebAuthn 功能需要宿主 App 显式通过代码接入 Credential Manager 委托，Via 并未做此适配。

---

## 5. 后续接手建议

1. **针对 ISSUE-P1-238 的状态**：
   - 当前在 `docs/ACTIVE_ISSUES.md` 中**保持开放**。
   - 应用侧应答开销已彻底压低到 0.25~0.32 s；但由于未混淆 debug 构建在低端机（Redmi 4X）上缺少 AOT，每次重装后若未执行 `cmd package compile`，冷启动偶尔会在 3.0 s 边界抖动。
   - 建议后续在发布前通过 `assembleRelease`（R8 混淆 + 资源缩减 + baseline profile）验证生产包的冷启动时延。

2. **真机端到端浏览器通行密钥测试建议**：
   - 当前测试机为去 Google 化的 LineageOS。由于 Firefox 140.0 的创建链路依赖 GMS FIDO2，若要通过真实浏览器跑通 passkeys.io 的创建：
     - **方案 A**：更新真机上的 Firefox 至支持 Credential Manager 优先创建的新版本。
     - **方案 B**：使用将 `credentials.create` 正确路由到 Android 系统 Credential Manager 的现代 Chromium 系移动浏览器（如已启用 CredMan 标志的 Chromium 编译版）。
     - **方案 C**：在带有 GMS 或现代商用 ROM 的实体设备上进行浏览器端到端回归。
