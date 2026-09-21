# §244 release 生产包冷启动验证与 ISSUE-P1-238 闭环批次

> 对应条目：`ISSUE-P1-238`（**整条结案**）；承接《passkeys.io 保存通行密钥失败排查与整改交接》
> （2026-09-21，`6e051c1` 入库、本批完成分流后退役）遗留的「发布前以 release 构建实测冷启动」建议项
> 日期：2026-09-21（同日接 §240 ~ §243）
> 涉及模块：无代码改动（纯真机验证 + 文档）；整改本体已在 `ffa7b0c` 落地
> 真机环境：Redmi 4X（santoni，`lineage_Mi8937_4_19`），**Android 17 / API 37**

---

## 1. 本批要做的事

交接文档 §5 明确了两项遗留：

1. `ISSUE-P1-238` 保持开放的原因是「无 odex 的 debug 构建冷启动间歇性超时」，
   建议发布前以 `assembleRelease`（R8 + 资源收缩）验证生产包冷启动时延；
2. 浏览器端到端（passkeys.io）受外部环境阻塞（Firefox 140 无 GMS 回退 FIDO2 私有 API、
   Via/WebView 未接入 CredMan 委托），属环境前提，应用侧无可整改面（§4 如实承接）。

本批执行第 1 项并据此闭环；第 2 项原样承接为「环境边界声明」。

## 2. 实测方法（可复现）

1. `.\gradlew.bat assembleRelease`（R8 混淆 + 资源收缩，签名走 `keystore.properties` 的 release 配置）；
2. 卸载 debug 包（签名不同必须先卸载）→ `adb install app-release.apk`
   → `dumpsys package dexopt com.keepasskey` 实测 `[status=verify] [reason=install]`
   （**安装期已产 odex**，与 debug 无 odex 的 `status=run-from-apk` 形成对照）；
3. 卸载清空了系统登记（与 `ISSUE-P2-239` 结论一致：全新安装不自动登记）——
   重装后系统弹出「首选服务」选择器，点选 KeePasskey 恢复 `credential_service` /
   `credential_service_primary`（`settings get` 复核均指向
   `com.keepasskey/com.keepasskey.app.passkey.KeePasskeyCredentialProviderService`）；
4. UI 驱动重建测试密码库（向导默认应用私有目录 + ChaCha20，主密码为**虚构测试值，不入文档**）并解锁；
5. 复用 §240/§241 的独立包名 CM 探针（`com.keepasskey.test`，`MODE=passkey`，未改一行），
   每轮 `force-stop` 双方 → `logcat -c` → `am start -f 0x10008000 …`（**CLEAR_TASK 必需**，
   否则 intent 被 deliver-to-top、Activity 不重建、轮次无效——首轮踩坑，如实留痕）
   → 9 s 后抓 `logcat -d -v time`；
6. 取 `ActivityManager: Start proc …:com.keepasskey/`（provider 进程）与
   `KeePasskeyCredProvider: onBeginCreateCredentialRequest` 两条时间戳求差；
   以 `SAVE_ENTRIES_RECEIVED` / `Remote provider response timed out` 计 AC②。

## 3. 实测结果（10/10 达标）

| 轮 | StartProc→onBegin | StartProc→SAVE_ENTRIES | 超时 |
|:--:|--:|--:|:--:|
| 1 | 247 ms | 317 ms | 0 |
| 2 | 212 ms | 267 ms | 0 |
| 3 | 206 ms | 287 ms | 0 |
| 4 | 222 ms | 289 ms | 0 |
| 5 | 213 ms | 268 ms | 0 |
| 6 | 230 ms | 298 ms | 0 |
| 7 | 185 ms | 240 ms | 0 |
| 8 | 229 ms | 282 ms | 0 |
| 9 | 211 ms | 278 ms | 0 |
| 10 | 204 ms | 259 ms | 0 |

**统计**：样本 10，最小 **185 ms**，最大 **247 ms**，中位 **213 ms**，均值 **216 ms**；
**超时 0/10**，10/10 `SAVE_ENTRIES_RECEIVED`。

**对齐验收标准**：

- **AC①**（`Start proc` → `onBeginCreateCredentialRequest` 全部 ≤ 1.5 s）：10/10 达标，
  最差轮 247 ms，**余量约 6×**（对比：debug + odex 为 2.35 s 量级、debug 无 odex 间歇性越过 3.0 s 预算）；
- **AC②**（零 `Remote provider response timed out`）：达标；
- **AC③**（不削减冷启动安全对账语义）：`ffa7b0c` 未触碰
  `fileBinaryStore.clear()` / `clipboardSecurityManager.reconcileOnColdStart()`，
  `ColdStartAttachmentPurgeWiringTest` 原样锁定，本批零代码改动；
- **AC④**（PSL 等价 + 资源三前提机检）：`PublicSuffixListTest` / `PublicSuffixListResourceTest`
  已在 `ffa7b0c` 落地，本批无相关改动；
- **AC⑤**（平台侧占比 + 本机排障手段如实登记）：已登记于条目「背景与根因」第 2 条——
  本批的 release 实测同时**反证**了该归因：同一台低端机上，仅「R8 混淆（dex 体积骤减）+
  安装期 dexopt」即把该分量从 2.35 s 压到 ≤ 0.25 s，证明剩余差距确属
  **debug 构建类型 × 无 AOT** 的平台属性，非应用代码问题。

**闭环判定**：AC① ~ AC⑤ 全部满足（AC①② 以**生产构建类型**达成；debug 无 odex 的
平台属性按 AC⑤ 口径保持如实登记，不构成开放理由）⇒ **整条结案**。

## 4. 环境边界承接（交接文档 §4.2 / §5.2 原样分流）

浏览器端到端（passkeys.io 经真实浏览器创建）在本测试环境**不可达**，与之前记录一致：

- **Firefox 140.0**：`makeCredential` 回退 Google Play Services FIDO2 私有接口
  （`FIDO2_PRIVILEGED_API … SERVICE_INVALID`），全程 **0 条** Credential Manager 日志；
  后续版本的 mozilla-release 已改为优先走 CredMan，需升级 Firefox 才具备该路由；
- **Via（WebView 内核）**：WebView 的 WebAuthn 需要宿主应用显式接入 CredMan 委托，未适配。

**后续可行路径**（环境侧，非应用侧）：升级真机 Firefox 至 CredMan 优先版本 / 使用正确路由
`credentials.create` 的 Chromium 系浏览器 / 在带 GMS 的实体设备回归。
**真机浏览器方向**已按此边界如实声明，**不**作为任何未验证项的开放理由挂在本项目缺陷清单上。

## 5. 交接文档退役

《passkeys.io 保存通行密钥失败排查与整改交接》的结论已完成分流：

- 排障过程、瓶颈归因、三项整改 → 已由 `ffa7b0c` 与 `ACTIVE_ISSUES.md` 的
  `ISSUE-P1-238` 条目承载（条目闭环后由本批次正文 + §240~§243 批次承接）；
- 本批的 release 实测与环境边界 → 本文件 §3 / §4。

文档地图（`docs/README.md`）中该记录的登记行同步移除，文件删除（git 历史可回溯 `6e051c1`）。

## 6. 观察记录（非缺陷，如实留痕）

1. **测试探针在 release 包上 `enabledProbeThrew=IllegalArgumentException`**：
   探针以硬编码类名构造 `ComponentName` 调 `isEnabledCredentialProviderService`；
   release 包内 provider 服务类名经 `dumpsys package` 核实**保留原名**、系统路由正常
   （创建请求正常到达并返回 `SAVE_ENTRIES_RECEIVED`），故该异常属**测试专用探针自身**
   对 release 环境的兼容问题，不影响产品通道；探针属 androidTest 资产，本批不改。
2. **设备残留状态**：真机现装 **release 包**（0.1.0，R8）+ 测试库 `passwords.kdbx`
   （虚构口令）；如需回归 debug 排障，直接 `adb install -r` debug 包会因签名冲突失败，
   须先卸载并重做 §2.3~§2.4 的登记与建库。
3. 各轮原始 logcat 快照在 `app/build/rel-coldstart/`（build 产物目录，不入库；
   复测可按 §2 步骤重跑）。

## 7. 工程验证

- `.\gradlew.bat test --rerun-tasks --max-workers=1` → 全绿
  （本批零代码改动，计数与 §243 基线一致）；
- `python tools/doc/check_md_links.py` → `BROKEN_MD_LINKS=0`；
- `python tools/doc/check_resolved_index_sync.py` → `RESOLVED_INDEX_SYNC=OK`。

## 8. 如实声明

1. **AC①② 的达成环境是 release 生产包**（真机同一台 Redmi 4X）；debug 无 odex 构建上
   平台侧开销依旧存在，已在条目与限界口径内如实登记，不得读作「所有构建类型均已达标」。
2. **未跑 KPEX 外部对拍**（`verify_interop.py`）：与本批无关，不对其作任何声称。
3. **浏览器端到端未达成**（§4 环境边界），「passkeys.io 经真实浏览器创建」这一用户原始场景
   在本环境仍未打通，属环境侧阻塞；不得读作「端到端已验证」。
4. 本批未触 `crypto/src/main/rust/**`、`jni_bridge_ext.rs` 与任一原生绑定，
   亦未新增 / 修改任何自动化用例（含 `*/src/androidTest/**`）⇒ 无设备侧必跑项；
   本批的设备侧操作为手工实测（非 instrumented 用例）。
5. 主密码纪律：重建测试库使用**虚构测试口令**，本文件不记录其取值。
