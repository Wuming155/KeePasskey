# §215 批次：ChaCha20 / AES 直扣（DirectByteBuffer）生产化批次（`ISSUE-P3-198` 整条结案）

> 日期：2026-09-19　|　条目：`ISSUE-P3-198`（**整条结案**，`ISSUE-P3-187` 评估的定案实施）
> 评估正文：[`records/JNI零拷贝评估_2026-09-19.md`](../../records/JNI零拷贝评估_2026-09-19.md)（§212）
> 实测数据：[`records/真机吞吐实测记录_2026-09-17.md`](../../records/真机吞吐实测记录_2026-09-17.md) §9（新增）

## 1. 原条目（自 `ACTIVE_ISSUES.md` 剪切，原样收录）

> ### ISSUE-P3-198 ChaCha20 / AES 直扣（DirectByteBuffer）生产化承接（`ISSUE-P3-187` 评估的定案实施）
>
> - **核实时间点与方式**：2026-09-19，`ISSUE-P3-187` 评估定案（见
>   [`records/JNI零拷贝评估_2026-09-19.md`](../../records/JNI零拷贝评估_2026-09-19.md)）——
>   探针 `probeJni零拷贝DirectByteBuffer_对比_连续10轮` 在 Pixel_10 上实测
>   **现状 24.05 ms（中位 10 轮，与 2026-09-17 基线 24.7 ms 复现一致）vs 直扣 3.7 ms（≈6.5×）**，
>   拷贝 + 分配开销（占单次 JNI 44%）被消除，AC② 达标；正确性前置为两条路径输出逐字节一致。
>   原生侧新增导出 `NativeChaCha20.applyKeystreamDirect`（direct `ByteBuffer` 就地变换，
>   **非生产路径**探针），4 ABI 已构建、`cargo test` 73 例全绿、符号契约 CI 清单已更新（10→11）。
> - **本条承接内容（生产化）**：
>   1. `ChaCha20CipherEngine` 与 `CbcStreams` 的调用方 `ByteBuffer` 化（或桥内双形态），
>      生产路径切换到直扣；**擦除责任上移**——direct 缓冲的会话级复用与用毕就地归零
>      由调用方承担（评估文档 §3 契约）；
>   2. AES 族同构探针与生产化（`NativeAes` 直扣，方法学同 ChaCha20）；
>   3. Redmi 4X 真机 10 轮对比补测（评估期间真机 USB 断连缺测，探针已入库随批可跑），
>      以真机数据复核 AC② 达标结论；
>   4. 既有语义回归全绿：`AesNativeParityTest` / `ChaCha20NativeEngineTest` /
>      `StreamKeyOwnershipContractTest` / `CipherFallbackParityTest` 等 + 四层设备侧套件。
> - **边界**：`CipherSpi` 有状态 Provider 路线**不在本条**（评估定案为长线演进方向，
>   与直扣正交；如未来立项须按评估文档 §6 四维对照先行）。
> - **依据**：`records/JNI零拷贝评估_2026-09-19.md`；`已知工程限界.md` §15 / §17（已按结论更新）。

## 2. 本批做了什么（对应条目四项承接内容）

1. **AES 族同构直扣**（条目 ②）：
   - Rust：`aes_cbc.rs` 新增**原地内核** `cbc_encrypt_in_place` / `cbc_decrypt_in_place`
     （加密侧天然原地：明文块消费后覆写；解密侧需 `Zeroizing` 暂存本组密文——它是下一链值，
     覆写前必须保住。参数闸门先于任何写入，通过后循环内无失败路径，**失败不留半截变换中间态**；
     `iv` 出口契约与 Vec 版逐字节一致）；`jni_bridge_ext.rs` 新增
     `NativeAes_cbc{Encrypt,Decrypt}BlocksDirect`（与 ChaCha20 直扣探针契约同构：
     direct `ByteBuffer` 就地、`key`/`iv` 仍 `Zeroizing`、`catch_unwind` 保持、失败 `-1`）；
   - Kotlin：`NativeAes.cbc{Encrypt,Decrypt}BlocksDirect` + Checked 包装
     （`encryptBlocksDirect` / `decryptBlocksDirect`，失败归一 `CryptoException`）；
   - 探针：`probeAes直扣DirectByteBuffer_对比_连续10轮`（正确性前置 = 两路径密文逐字节一致
     + **`iv` 出口契约**（链值演化一致 = 末组密文）+ direct 解密往返；随后 10 轮同载荷对比）。
2. **生产路径切直扣**（条目 ①）：
   - **ChaCha20 解密流**（`NativeDecryptingInputStream`）：流实例自持单 direct 缓冲（64 KiB），
     读入堆内中转（`InputStream` API 限制，不可消）→ `put` → 对 `[0, filled)` 等容量
     `slice()` 视图就地变换（JNI 契约按 capacity 处理整区间，partial 载荷以视图收窄）→
     `get` 交付调用方，**交付即归零**、close 整段兜底归零。每块 4 拷贝 → 2 拷贝、零分配；
   - **AES 双流**（`CbcStreams` **双形态注入**）：`CbcBlockTransform`（byte[] 形态）保留，
     新增 `CbcDirectBlockTransform`（direct 形态，**二选一**的构造不变式）。加密流：累积区移入
     堆外（`position == filled` 不变式），满段对 `[0, aligned)` 视图原地变换（明文就地变密文，
     无明文中转副本），堆内缓冲转作写出暂存；解密流：载荷 put 后原地变换、堆外就地区**交付即归零**，
     EOF 收尾头段/末块两段各自变换、末块拷出去填充。byte[] 形态逻辑**逐字保留**（JCE 兜底与
     宿主注入不受影响），`requireDistinctResult` 契约**仅适用于 byte[] 形态**（direct 原地正是
     其设计，KDoc 写明）；
   - `AesCipherEngine` 原生分支接线 direct 形态；`ChaCha20CipherEngine` 解密流内部直扣
     （公开 API 与 `KdbxFile` 零改动，改动全部收敛 crypto 层）。
3. **擦除责任上移契约的执行原语**：`wipeRange(from, to)`（direct 缓冲绝对区间归零；
   内部先 `clear()` 复位 `limit`，防 flip 残留限位把相对 `put` 以 `BufferOverflowException`
   截断导致擦除不完整）。
4. **Redmi 4X 真机 10 轮补测**（条目 ③）：完成（期间设备时断时续，单跑探针类一次成功），
   AC② 真机复核达标（见 §4）。

## 3. 关键设计裁定与边界（防未来重问）

1. **整块 `byte[]` 路径维持拷贝桥**：契约要求返回新数组且输入不动，直扣后仍需 put/get 两次
   堆内↔堆外拷贝，与现状 JNI 双拷贝等量，还倒贴 `allocateDirect`——无收益，有意保留
   （限界 §15/§17 已登记为「已接受残余面」）。
2. **ChaCha20 加密流维持拷贝桥**：写粒度由调用方决定且可任意大（旧形态把任意尺寸数组**单次**
   交给 JNI）；固定 direct 缓冲需分段循环反而引入每段 JNI 固定开销 + 堆外归零，拷贝次数不变
   （`OutputStream` 字节 API 两侧各留一次），仅省输出数组分配，收益不入（KDoc 边界登记）。
3. **Twofish 不在本批**：同走 `CbcStreams`，双形态注入点已备好，后续同构跟进即可。
4. **`CipherSpi` 有状态 Provider** 不在本条（维持评估定案：长线正交方向）。
5. **direct 缓冲所有权 = 流实例**（每流一个、整流复用、close 兜底归零），不做全局池——
   crypto 底层无会话概念，全局池引入跨流残留面。

## 4. 验证（判据与数字）

- `cargo test`：**76 passed / 0 failed**（73 → +3：原地与分配形态对拍 × NIST 向量、
  原地往返与分段等价、原地闸门负例）；
- 宿主 `test`：**tests=2242 skipped=13 failures=0**（`count_test_results.py`；2233 → +9 =
  `CbcDirectStreamsTest`：direct 与 byte[] 两形态多长度逐字节一致、小分块/逐字节供给、
  小步长跨 refill、write(Int) 路径、三类 fail-closed 错误语义、双形态互斥校验；skipped=13
  全部为 sync 模块既有基线，本批新增用例 skipped=0）；lint **214 持平**；
- 四层设备义务（改动 `crypto/src/main/rust/**` 触发）：
  `:crypto:` Pixel_10 **37 例全绿**（含两枚探针）、`:database:` **15 例全绿**、
  `:sync:` 全绿、`:app:` **63/64**——唯一失败
  `AutofillAuthChainDeviceTest.设备侧实测自动填充认证填充链路`（阶段 3 框架未展示认证数据集）
  经 **HEAD 对照归因为环境面**：`git stash` 后在 HEAD 上单跑同用例同样失败（模拟器冷启动后
  autofill 服务状态漂移；§212 批次时同用例同机绿），与本批无关，未据此放宽任何断言；
- **真机（Redmi 4X）探针类 8 例全绿**——AC③ 补测完成，10 轮中位（10 MiB，同机同轮次）：

  | 锚点 | Pixel_10（模拟器） | Redmi 4X（A53 真机） |
  |---|---|---|
  | ChaCha20 现状 → 直扣 | 23.6 → **3.7 ms**（≈6.4×） | 149.8 → **66.3 ms**（≈2.26×） |
  | AES 现状 → 直扣 | 43.9 → **14.7 ms**（≈3.0×） | 178.9 → **48.7 ms**（≈3.67×） |

  现状路径与基线复现一致（149.8 vs 151.6 等）；AES 模拟器直扣 14.7 ≈ 内核 13.3 × 1.1 ≤ ×1.2
  ⇒ AC② 真机复核达标；**探针数字 ≠ 生产端到端数字**（字节 API 边界拷贝不可消，流路径收益
  比例低于上表，口径声明见实测记录 §9.2），限界 §15/§17 已按此更新；
- 符号契约 CI 清单：4 ABI 导出 11 → **13**（`NativeAes_cbc{Encrypt,Decrypt}BlocksDirect`）；
- `long_functions.py`：`CbcDecryptingInputStream.refill` 曾达 112 逻辑行（≥100 第一档），
  本批内重构为「骨架 + 四分支函数」（最长 44 行），functions_ge_100 回落 4 → **3**（全部为
  app 侧既有豁免）；`check_verbatim_move.py` 核对解密流拆分落位（22 条 MISSING 逐条对应
  双形态签名/分支改动，框架逻辑无丢失）；`check_md_links.py`：`BROKEN_MD_LINKS=0`。

## 5. 过程留痕

1. **探针用例自身缺陷（真机现形）**：AES 直扣探针前置②误用**加密后已演化的 iv** 当解密链值
   （应为原始 IV）——首跑即红（`直扣解密往返不一致 offset=0`），属用例 bug 而非内核/绑定缺陷；
   修正后真机/模拟器全绿。宿主全绿 ≠ 设备可用（§143/§147 教训再次成立：该缺陷只在设备侧现形）。
2. **`wipeRange` 残留限位风险（自查修复）**：close 整段归零最初用相对 `put` 循环，而 flip 后
   `limit < capacity` 会令 `put` 抛 `BufferOverflowException`、擦除不完整——统一为
   `clear()` 后再归零，并由宿主 direct 形态用例覆盖交付路径。
3. **拆分落位**：`CbcDecryptingInputStream` 自 `CbcStreams.kt`（356 行，直扣化后本会超 400）
   拆出独立文件，逐字搬移复核通过。
4. **真机 USB 时断时续**：整层 connected 首跑因 `device '1c859bcc7d24' not found` 中断
   （Gradle 同时发现两台设备时真机半途掉线，`tests=0` + EOFException），后以探针类单跑补齐
   AC③；与评估批次登记的断连现象一致。

## 6. 涉及文件

- **新增**：`CbcDecryptingInputStream.kt`（自 `CbcStreams.kt` 拆分 + direct 形态）、
  `CbcDirectStreamsTest.kt`（宿主 direct 形态框架用例 9 例）
- **修改**：`aes_cbc.rs`（+原地内核）、`aes_cbc_tests.rs`（+3 例）、`jni_bridge_ext.rs`
  （+2 导出）、`NativeAes.kt` / `NativeChaCha20.kt`（+direct 绑定与 Checked 包装）、
  `CbcStreams.kt`（+direct 形态注入 + `wipeRange` + 加密流 direct 分支）、
  `ChaCha20CipherEngine.kt`（解密流直扣化 + 边界 KDoc）、`AesCipherEngine.kt`（接线）、
  `DeviceThroughputProbeTest.kt`（+AES 直扣探针）、`.github/workflows/build.yml`（11→13）、
  `已知工程限界.md` §15/§17（按解除条件更新）、`真机吞吐实测记录_2026-09-17.md`（+§9）
- `ACTIVE_ISSUES.md`：`ISSUE-P3-198` 结案移出（P3 清零）；`RESOLVED_LOG.md` 加 §215 行。
