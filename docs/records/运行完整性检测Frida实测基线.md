# 运行完整性检测 Frida 实测基线（ISSUE-P3-120）

> **登记缘由**：`ISSUE-P3-120`（威胁建模 Q-11）指出 `RuntimeIntegrityDetector` 的钩子探测是
> 「磁盘路径存在性 + `/proc/self/maps` 特征串」启发式，**真实 Frida 下的命中率未知**——
> 若命中率低，则「`COMPROMISED` 时禁用生物解锁 + 自动填充」的政策在真实攻击下形同虚设，
> 却给用户「已被保护」的错觉。
> **本文档即该项 AC① 要求的「真机三种形态实测 + 设备侧基线」**；AC② 的结论见文末。

## 1. 被测实现（判据逐条列出，避免「测了别的」）

`RuntimeIntegrityDetector.detectHookFramework()` 两道检查：

| 层 | 判据 |
|---|---|
| **maps 层** | `/proc/self/maps` 任一行含（忽略大小写）六条特征串之一：`frida`、`xposed`、`substrate`、`edxposed`、`lsposed`、`libhook` |
| **落点层** | 五条固定路径任一存在：`/data/local/tmp/frida-server`、`/data/local/tmp/re.frida.server`、`/data/local/tmp/frida`、`/system/lib/libfrida-gadget.so`、`/system/lib64/libfrida-gadget.so` |

命中即 `hookFrameworkDetected = true` ⇒ `RuntimeIntegrityPolicy` 判 **`COMPROMISED`**
（`disableBiometricQuickUnlock` + `disableAutofill` + `requireRiskNotice`）。
扫描时机：进程启动即扫一次，此后**每 30 秒**（`LIVE_RESCAN_INTERVAL_MS`）重扫，
敏感通道（生物快速解锁 / CM / 自动填充）求值时另经 `escalateForLiveSignals` 实时并入。

## 2. 环境与工具

| 项 | 值 |
|---|---|
| 设备 | Redmi 4X（santoni）/ LineageOS 24.0 / Android **17** / API **37** / arm64-v8a |
| 权限 | `adb root` 可用（userdebug） |
| 被测应用 | **debug 包**（`app-debug.apk`，`appDebuggable=true`）⇒ 基线等级天然为 `ELEVATED`，钩子信号命中后升 `COMPROMISED`，构成**干净的二值信号** |
| 注入工具 | frida-server **17.15.3** android-arm64（53 489 240 字节 ELF）；宿主 frida 客户端 **17.15.3**（版本对齐） |
| 取证方式 | `adb logcat` 读 `I/RuntimeIntegrity: 运行完整性扫描完成: level=…`；另以 root 直接 `grep /proc/<pid>/maps` 取原始证据 |

> **取件备注**：本机到 `github.com` 的 release CDN **不可达**（`api.github.com` 可达但下载超时）；
> 经镜像 `https://ghproxy.net/` 前缀取回。复现时若同样受限，可换 `gh-proxy.com`。

## 3. 测量协议

每个形态：`logcat -c` → `am force-stop` → `am start` → 等 12 秒（覆盖冷启动扫描）→ 读等级；
形态 2/3 另等 ≥30 秒以覆盖一次**周期重扫**（否则实时信号可能尚未进入快照）。

## 4. 实测矩阵（2026-09-16）

| # | 形态 | 具体条件 | 观测等级 | 判定 |
|:--:|---|---|:--:|---|
| 0 | **基线** | 无任何 frida 落点、未 attach | `ELEVATED` | 无命中（debug 包基线） |
| 1 | **默认落点** | `/data/local/tmp/frida-server` **仅在案、未运行、未 attach** | **`COMPROMISED`** | 落点层**命中** |
| 2a | **改名（未注入）** | 服务端改名 `/data/local/tmp/fsx` 并运行；五条固定路径**全不存在** | `ELEVATED` | 落点层**漏报**（如预期） |
| 2b | **改名 + attach** | 同上，再用宿主 frida attach 到应用进程 | **`COMPROMISED`** | maps 层**命中** |
| 3 | **内存加载** | 同 2b 的实际加载方式：agent 经 **memfd** 载入、文件已删除（磁盘零落点） | **`COMPROMISED`** | maps 层**命中** |
| 4 | **脱离后** | `detach` + 周期重扫 | `ELEVATED` | **可恢复**（不误报残留） |
| 5 | **清理后复测** | 停服务端 + 删除全部落点后冷启动 | `ELEVATED` | 回到基线 |

**命中率：3 / 3 形态全部命中**（形态 1、2b、3 分别代表被测的三种形态）。

### 4.1 原始证据（形态 3 = 内存加载）

attach 期间以 root 直接读应用进程 maps：

```
6f2f756000-6f30e22000 r-xp 00000000 00:01 705677   /memfd:frida-agent-64.so (deleted)
6f30e22000-6f30e23000 r-xp 016cc000 00:01 705677   /memfd:frida-agent-64.so (deleted)
6f30e23000-6f30e26000 r-xp 016cd000 00:01 705677   /memfd:frida-agent-64.so (deleted)
6f30e26000-6f30e27000 r-xp 016d0000 00:01 705677   /memfd:frida-agent-64.so (deleted)
6f30e27000-6f30e29000 r-xp 016d1000 00:01 705677   /memfd:frida-agent-64.so (deleted)
```

**这行证据本身回答了两个问题**：① agent 的确是**内存加载**形态（`memfd:` + `(deleted)`，
磁盘上不存在该文件）；② 其特征串仍含 `frida` ⇒ maps 层命中。

对应等级序列（同一进程 28694）：

```
level=ELEVATED        ← attach 之前的冷启动扫描
level=COMPROMISED     ← attach 后的周期重扫
level=COMPROMISED
level=ELEVATED        ← detach 后的周期重扫（恢复）
```

### 4.2 两条防线的互补关系（由矩阵直接读出）

- **落点层**：只在**默认路径**命中，但**不需要注入**——连「已下载未运行」都能发现（形态 1）；
  对**改名**完全无效（形态 2a）。
- **maps 层**：对「改名 + 已注入」（含 memfd 内存加载）有效（形态 2b / 3）；
  对**未注入**的落点无效（形态 2a 中服务端在跑但未注入应用进程，等级仍为 `ELEVATED`）。

即：**两层缺一不可**——只留落点层则改名即绕过；只留 maps 层则「落点已就位但尚未注入」的
准备阶段不可见。

## 5. 未经实测的规避面（**如实声明，不得读作已覆盖**）

1. **memfd 名一并改掉**（如 `/memfd:agent-64.so`）⇒ 六条特征串均不命中。frida-server 二进制
   打补丁即可做到——**未测**，是本实现**最直接**的绕过面。
2. **不产生具名映射的注入**（匿名映射），或**纯读取**类手法（`process_vm_readv` / `/proc/<pid>/mem`
   读取，根本无需注入）⇒ maps 无迹可寻——**未测**。后者已由 `ISSUE-P3-83` 的 `TracerPid` 信号
   部分补足（`ptrace` 必然置位）。
3. **hook 本进程的 `open`/`read`**，使 `detectHookFramework` 读到空 maps 或伪造内容——**未测**，
   且原理上无法在应用层完全防御（任何应用内自检都可被同进程 hook 一并篡改）。
4. **时序窗口**：周期重扫间隔 30 秒。若 `attach → 完成读取 → detach` 完整落在两次扫描之间，
   则可能不被捕获——**由矩阵推断的残余**（本次实测 attach 持续约 75 秒，故被覆盖）。
   该项须与 `P2-63` 的「快照陈旧即 fail-closed / 敏感操作前重扫」一起理解：
   敏感通道在**求值时**会 `escalateForLiveSignals` 实时并入，故窗口影响的不是
   「高价值通道是否放行」而是「风险提示文案的及时性」。

## 6. 复现步骤

```bash
export MSYS_NO_PATHCONV=1                     # Git Bash：否则设备绝对路径会被改写成 Windows 路径
ADB=/d/Android/SDK/platform-tools/adb.exe
$ADB root

# 取件（GitHub release CDN 在本机不可达时经镜像）
curl -sSL -o fs.xz "https://ghproxy.net/https://github.com/frida/frida/releases/download/17.15.3/frida-server-17.15.3-android-arm64.xz"
python -c "import lzma;open(r'...\frida-server','wb').write(lzma.open(r'...\fs.xz').read())"

# 形态 1：默认落点（无需运行）
$ADB push "C:/.../frida-server" /data/local/tmp/frida-server && $ADB shell "chmod 755 /data/local/tmp/frida-server"
$ADB logcat -c; $ADB shell am force-stop com.keepasskey; $ADB shell am start -n com.keepasskey/.app.MainActivity; sleep 12
$ADB logcat -d | grep -a RuntimeIntegrity          # 期望 COMPROMISED

# 形态 2/3：改名 + attach（agent 自动以 memfd 载入）
$ADB shell "rm -f /data/local/tmp/frida-server"
$ADB push "C:/.../frida-server" /data/local/tmp/fsx && $ADB shell "chmod 755 /data/local/tmp/fsx; nohup /data/local/tmp/fsx >/dev/null 2>&1 &"
# 启动应用后取 pid，再用宿主 frida attach 并保持 ≥35s（覆盖一次周期重扫）
python -c "import frida,time,sys;d=frida.get_usb_device();s=d.attach(int(sys.argv[1]));print('ATTACHED');time.sleep(75);s.detach()" <app-pid>
$ADB shell "grep -i frida /proc/<app-pid>/maps"     # 期望 /memfd:frida-agent-64.so (deleted)
$ADB logcat -d | grep -a RuntimeIntegrity           # 期望 COMPROMISED

# 清理（勿在设备上留残余）
$ADB shell "pkill -f /data/local/tmp/fsx; rm -f /data/local/tmp/fsx /data/local/tmp/frida-server"
```

> **本轮实测已在设备上完成清理**：`fsx` 与 `frida-server` 均已删除、服务端进程已终止，
> 清理后复测回到基线 `ELEVATED`（矩阵第 5 行）。

## 7. AC② 结论：**不需要加强检测**，按实测如实声明

`ISSUE-P3-120` AC② 为「按结果决定加强检测 **或** 在 `RuntimeIntegrityPolicy` 中如实声明」。
本次实测**三种形态 3/3 全部命中**，故：

- **不做**「加强检测」的改造（例如把 6 条特征串扩成模糊匹配）——**没有实测证据支持其必要性**，
  属无靶加固；
- **改为如实声明**：实测矩阵与**四条未经实测的规避面**已写入
  `RuntimeIntegrityDetector.detectHookFramework()` 的 KDoc 与本文件，明确
  「本检测是**提高成本**的启发式，不是完整性证明」——与 `ISSUE-P3-83` 的定位一致。

## 8. 与其它条目的关系（避免重复排查）

- `ISSUE-P3-83`（`TracerPid`）：本次实测的「纯读取」类手法（`process_vm_readv` / `/proc/<pid>/mem`）
  不产生新映射，正是该项补足的信号面；两项共同构成「注入 vs 读取」两类内存手法的覆盖。
- `ISSUE-P2-63`（周期重扫 + 快照陈旧 fail-closed）：决定第 5 节第 4 条**时序窗口**的实际影响边界。
- `ISSUE-P1-23`：`installer == null` 不升级风险系**显式产品决策**（见该条 KDoc），与本项无关。
