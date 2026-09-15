<a id="s85"></a>
## §85 `TracerPid` 内存取证门控批次（2026-09-16）：ISSUE-P3-83

> **本批次缘起**：认领 `ISSUE-P3-83`（`ptrace` / `process_vm_readv` / `/proc/<pid>/mem` 注入
> 不产生新映射、也不置 JDWP 调试位，既有 `RuntimeIntegrityDetector` 对其无感）。
> 该项**无决策阻塞**，故本批直接闭环。

### 85.1 为什么 `TracerPid` 是这一层能做到的最强信号

`TracerPid` 由**内核**为每个被 ptrace 的进程在 `/proc/self/status` 中如实填写，进程读自己的
status **无需任何权限**。相比既有探测：

| 既有探测 | 对 `ptrace` 系注入 |
|---|---|
| `Debug.isDebuggerConnected()` / `waitingForDebugger()` | 无感（不置 JDWP 位） |
| `/proc/self/maps` 特征串 | 无感（`process_vm_readv` / `/proc/<pid>/mem` 不产生新映射） |
| 磁盘路径（root / Magisk / 钩子落点） | 无感（内存加载的 gadget 不落盘） |

### 85.2 AC①：同步读取 + 并入既有门控

| 产物 | 说明 |
|---|---|
| `ProcTracerPid`（新，纯函数） | 解析 `TracerPid:\t<N>`；**判据是 `> 0` 而非 `!= 0`**——内核对未被 trace 的进程填 `0`，若某实现返回负值，`!= 0` 会误判为被 trace |
| `TracedProcessProbe` + `ProcStatusTracedProcessProbe`（新） | 生产实现**同步**读真实内核文件；`/proc` 伪文件 `length()` 恒为 0，故**不能**据此分配缓冲区，改为**有界 8 KiB** 读取 |
| `IntegritySignals.beingTraced`（新字段） | 并入 `evaluate` 的 `compromised` 分支（与 `debuggerAttached` 同属动态攻击特征） |
| `escalateForLiveSignals(..., beingTraced)` | 新增**无默认值**参数（安全信号不允许「忘记传参即放行」）；`currentEnforcement()` 与 `awaitEnforcement()` **两个入口**均已接线 |
| Hilt | `RuntimeIntegrityModule` 新增 `bindTracedProcessProbe` |

**为何是「同步」而不是并入周期重扫**：`TracerPid` 是**瞬时**信号，
`RuntimeIntegrityDetector` 的重扫间隔完全可能整段覆盖「附加 → 读取 → 脱离」窗口。
本批因此在门控被求值的那一刻同步读一次（KB 级 `/proc` 小文件读，施加在解锁 / 填充这类低频入口上）。

**AC① 中 `clearSensitiveCache()` 的落地方式（如实说明）**：本仓并无名为 `clearSensitiveCache()`
的入口；AC① 的语义（「非 0 即拒绝解锁」）由**既有门控链**承担——`COMPROMISED` ⇒
`disableBiometricQuickUnlock`（生物快速解锁拒绝解封主密码凭据）+ `disableAutofill`
（CM 与自动填充两条通道拒绝下发候选）。这是既有的、已被其它信号验证过的收敛路径，
**不新造并行通道**。

### 85.3 AC② 评估结论：`prctl(PR_SET_DUMPABLE, 0)` / `MADV_DONTDUMP` **不实施**

| 项 | 评估 |
|---|---|
| 需要什么 | 二者均为 native 系统调用；本仓 native 面仅 `crypto/src/main/rust` 下的四个密码学内核，JNI 契约为**定长基本类型/数组**（AGENTS.md §3.2）。新增 `prctl` 入口等于扩一条与密码学无关的 native 面 |
| 收益 | `PR_SET_DUMPABLE=0` 影响的是**本进程的可 dump 性 / 被 ptrace 资格**。而本项威胁模型是「root / 同 UID 已可截获」——`root` 不受 `dumpable` 约束；对同 UID 而言，能读我们内存的攻击者同样能改我们的 native 调用 |
| 代价 | `PR_SET_DUMPABLE=0` 会**同时**影响崩溃转储、profiler 与调试器附加（含开发者自身的排障）；`MADV_DONTDUMP` 需对**每一处**敏感缓冲逐段施加，漏一处即无效，且与既有 `Zeroizing`/显式清零的生命周期管理叠加后语义变复杂 |
| 结论 | **不实施**——正是 AC② 所警示的「以牺牲稳定性换取纸面加固」，且真实收益近乎为零 |

### 85.4 AC③：边界留痕（**非阻断承诺**）

已写入 `ProcTracerPid` / `IntegritySignals.beingTraced` 两处 KDoc 与本文件：

1. 本信号拦不住**不依赖 ptrace** 的攻击（同 UID 直读、root 直读内存、内核模块）；
2. 攻击者可 hook `open`/`read` **伪造** `TracerPid:\t0`；
3. **读不到时按「未检测到」处理（明示 fail-open）**——`/proc/self/status` 对进程自身恒可读，
   失败属异常 ROM；若因读不到就禁用生物快速解锁与自动填充，等于用一个纸面加固换掉整机可用性，
   与本项「提高成本、不改变设计边界」的 P3 定级相悖。该取舍由
   `TracerPid 判定只认正数` 与设备侧 `未附加 tracer 的测试进程报告 0` 两处用例锁定。

### 85.5 新增覆盖

| 用例 | 覆盖点 |
|---|---|
| `ProcTracerPidTest`（新，7 例） | 标准 / 被 trace / 字段位置无关 / 字段缺失 / 值非数字 / **不与其他含 `TracerPid` 字样的字段混淆** / 空白分隔 |
| `RuntimeIntegrityPolicyTest`（24 → 27 例） | 实时 ptrace 升级为 `COMPROMISED` 且两条通道同时禁用；缓存快照自带该信号同样判最高风险；`isTraced` 边界（`0` / 正数 / **null 不判为被 trace** / 负值不误判） |
| `TracedProcessProbeDeviceTest`（新，4 例，**真机**） | 真实 `/proc/self/status` 含该字段且可解析；生产实现与直接读取一致；测试进程未被 trace 时报告 0；有界上限（8 KiB）足以覆盖该文件 |

**「不覆盖」如实声明**：真实 ptrace 附加下的**非零**取值未在设备侧覆盖——让被测进程被真实
tracer 附加需 root 侧外部工具（`strace`/gdbserver）配合且会改变被测进程状态；
该分支由宿主用例以合成内容覆盖，设备侧只证明「真实内核格式可解析、未被 trace 时为 0」。

### 85.6 验证证据（2026-09-16，真机 Redmi 4X / LineageOS / Android 17 / API 37）

- `:app:testDebugUnitTest --tests "com.keepasskey.app.security.*"` → BUILD SUCCESSFUL
  （`ProcTracerPidTest` 7/7、`RuntimeIntegrityPolicyTest` 27/27）。
- `:app:connectedDebugAndroidTest` → **BUILD SUCCESSFUL**，
  `tests="33" failures="0" errors="0" skipped="0"`（本批前 29，新增 4 例）。
- 全量 `test --rerun-tasks --max-workers=1` 与 `assembleRelease` 结果见提交信息。

### 85.7 下一批

1. 表内其余 P3：`P3-76`（IME 个性化学习，**已登记为框架阻塞的已接受残余**）、
   `P3-120`（真机 Frida 实测，**P1/UNVERIFIED**，为 4 条目的共同前提）、
   `P3-121`（内网 WebDAV 产品口径）、`P3-122`（IPC-10 未做）、
   `P3-123` / `P3-124` / `P3-125`（CI 与供应链硬化、DAL 出口纵深防御）。
2. P2 三条均卡在决策点（`P2-47` AC②、`P2-73` 数据集回传、`P2-79` 产品口径），
   需定版 / 产品裁决后方可继续。
