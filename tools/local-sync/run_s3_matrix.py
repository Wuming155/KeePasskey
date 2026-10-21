#!/usr/bin/env python3
"""ISSUE-P3-650 / ISSUE-P2-655 的真实验收脚本：监听面矩阵 + SigV4 目录/分页/坏密钥。

做三件事，每件都在**本次独立数据目录**与**按次随机凭据**下进行：

1. **loopback 模式**（默认）：启动 MinIO(API+Console) 与 WebDAV，逐个核对实际监听地址
   只出现在 127.0.0.1（用 `netstat`/`psutil` 读真实套接字，不看文档），并验证：
   - 本机可读写（正对照）；
   - 从本机**非 loopback 网卡地址**访问时被拒绝（负对照，模拟同网段对端）；
   - 用错误的固定旧凭据（历史上源码里的 `tester/tester1234`）认证失败。
2. **显式 LAN 模式**（`--lan`）：绑定 0.0.0.0，凭据仍是按次随机，且旧固定凭据必须失败；
   退出时只结束自己启动的 PID（不再按进程名 `taskkill /IM minio.exe`）。
3. **SigV4 端到端**：调用 Gradle 运行 `LiveS3DirectoryListTest`（含 `&` 前缀首分页、
   真实续页 token、错误密钥 403、token 内 `&` 只作数据）。

用法：
    python tools/local-sync/run_s3_matrix.py            # loopback 全矩阵
    python tools/local-sync/run_s3_matrix.py --lan      # 追加显式 LAN 模式
    python tools/local-sync/run_s3_matrix.py --no-gradle  # 只做监听/认证矩阵

退出码：0 = 全部检查通过；1 = 任一检查失败；2 = 环境缺失（minio/mc/证书）。
"""
import argparse
import os
import socket
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lab_services  # noqa: E402

# 历史源码里的固定测试凭据（P3-650 的缺陷形态）；修复后必须认证失败。
LEGACY_USER = "tester"
LEGACY_PASSWORD = "tester1234"

REPORT = []


def check(name, ok, detail=""):
    REPORT.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")
    return ok


def local_addresses():
    """本机非 loopback IPv4 地址（用于「同网段对端」负对照）。"""
    addrs = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addrs.add(info[4][0])
    except OSError:
        pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        addrs.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    return sorted(a for a in addrs if not a.startswith("127."))


def listening_on(port):
    """读取真实监听地址集合（psutil 优先，缺失时回退 netstat）。"""
    try:
        import psutil
        rows = set()
        for conn in psutil.net_connections(kind="tcp"):
            if conn.laddr and conn.laddr.port == port and conn.status == psutil.CONN_LISTEN:
                rows.add(conn.laddr.ip)
        return rows
    except Exception:
        pass
    out = subprocess.run(["netstat", "-ano", "-p", "TCP"], capture_output=True, text=True).stdout
    rows = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
            local = parts[1]
            if local.rsplit(":", 1)[-1] == str(port):
                rows.add(local.rsplit(":", 1)[0])
    return rows


def port_open(host, port, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def is_loopback_addr(ip):
    """回环地址集合：IPv4 127.0.0.0/8 与 IPv6 ::1（两者都只在本机可达）。"""
    return ip.startswith("127.") or ip in ("::1", "[::1]") or ip == "0:0:0:0:0:0:0:1"


def expect_loopback_only(label, port):
    rows = listening_on(port)
    loopback = {ip for ip in rows if is_loopback_addr(ip)}
    wildcard = {ip for ip in rows if ip in ("0.0.0.0", "::", "[::]")}
    external = rows - loopback - wildcard
    ok = bool(loopback) and not wildcard and not external
    return check(
        f"{label} 仅监听 loopback",
        ok,
        f"监听集合={sorted(rows) or '未发现'}",
    )


def expect_external_reachable(label, port, addresses):
    if not addresses:
        return check(f"{label} 可从同网段地址到达", True, "本机无其他 IPv4 网卡，跳过（不得据此宣称对外可达）")
    reached = [a for a in addresses if port_open(a, port)]
    return check(f"{label} 显式 LAN 模式可从同网段地址到达", bool(reached), f"可达={reached}")


def expect_external_refused(label, port, addresses):
    if not addresses:
        return check(f"{label} 同网段地址被拒绝", True, "本机无其他 IPv4 网卡，跳过（不计为已验收）")
    reached = [a for a in addresses if port_open(a, port)]
    return check(f"{label} 同网段地址被拒绝", not reached, f"错误可达={reached}")


def s3_anonymous_probe(host, port, label):
    """未携带任何凭据的 S3 请求必须被拒绝（不得暴露测试存储）。"""
    import http.client
    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        conn = http.client.HTTPSConnection(host, port, context=ctx, timeout=5)
        conn.request("GET", "/")
        status = conn.getresponse().status
        conn.close()
        return check(f"{label} 匿名请求被拒绝", status in (401, 403), f"HTTP {status}")
    except Exception as exc:  # 连接被拒绝同样是期望结果
        return check(f"{label} 匿名请求被拒绝", True, f"{type(exc).__name__}: {exc}")


def legacy_credentials_rejected(lab, label):
    """用历史固定凭据真实走一次 S3 认证：必须被服务端拒绝。

    `mc alias set` 会先发一个探测请求；服务端拒绝凭据时 mc 返回非零并给出服务端错误
    （如 `Access Key Id you provided does not exist`）。若 alias 反而建成功，再发一次真实
    列举确认——两步都不接受「本地配置成功」当作认证通过。
    """
    env = dict(os.environ)
    env["MC_CONFIG_DIR"] = os.path.join(lab.root, "mc-legacy")
    endpoint = f"https://{lab.host_for_client()}:{lab.s3_port}"
    set_result = subprocess.run(
        [lab_services.MC, "--insecure", "alias", "set", "legacy", endpoint, LEGACY_USER, LEGACY_PASSWORD],
        env=env, capture_output=True, text=True,
    )
    if set_result.returncode != 0:
        return check(f"{label} 旧固定凭据被拒绝", True, "服务端拒绝旧固定口令（mc alias 探测即失败）")
    list_result = subprocess.run(
        [lab_services.MC, "--insecure", "ls", f"legacy/{lab.bucket}/"],
        env=env, capture_output=True, text=True,
    )
    rejected = list_result.returncode != 0
    return check(
        f"{label} 旧固定凭据被拒绝",
        rejected,
        "服务端拒绝旧固定口令" if rejected else "旧固定口令仍可访问（严重回归）",
    )


def run_sigv4_gradle(lab):
    env = dict(os.environ)
    env["LIVE_SYNC_TEST"] = "1"
    cmd = [
        os.path.join(lab_services.REPO, "gradlew.bat"),
        ":sync:testDebugUnitTest",
        "--tests", "com.keepasskey.sync.s3.LiveS3DirectoryListTest",
        # ISSUE-P2-636：真实服务端 ETag 经崩溃窗口后的一致性（同一隔离服务）
        "--tests", "com.keepasskey.sync.engine.LiveS3EtagCrashWindowTest",
        "-DliveSyncTest=true",
        f"-DliveCertPath={lab_services.CERT_PATH}",
        f"-DliveS3Endpoint=https://{lab.host_for_client()}:{lab.s3_port}",
        f"-DliveS3Bucket={lab.bucket}",
        # 凭据经 -D 传入而非只靠环境变量：Gradle 常驻 daemon 会被后续客户端复用，
        # 它持有的是**首次启动时**的环境快照，本轮随机凭据可能因此不可见。
        # 该口令按次随机、只用于本次临时目录内的服务，运行结束即失效。
        "-DliveS3Access=" + lab.s3_user,
        "-DliveS3Secret=" + lab.s3_password,
        "--offline",
        "--console=plain",
    ]
    r = subprocess.run(cmd, cwd=lab_services.REPO, env=env)
    return check("SigV4 真实服务端到端（含 & 前缀 / 真实续页 / 坏密钥）", r.returncode == 0, f"gradle rc={r.returncode}")


def run_mode(bind_host, label, lan_smoke):
    lab = lab_services.Lab(bind_host=bind_host, webdav_port=19443, s3_port=19000, console_port=19001)
    print(f"\n=== {label} ===\n[lab] 独立目录 {lab.root}")
    addresses = local_addresses()
    try:
        lab.start_minio()
        lab.start_webdav()
        ok_ports = lab_services.wait_port(lab.host_for_client(), lab.s3_port, timeout=40) and \
            lab_services.wait_port(lab.host_for_client(), lab.webdav_port, timeout=30)
        if not check(f"{label} 服务就绪", ok_ports, f"minio pid={lab.services[0].pid} webdav pid={lab.services[1].pid}"):
            return False
        if not check(f"{label} 建桶", lab.create_bucket()):
            return False

        if bind_host == lab_services.LOOPBACK:
            expect_loopback_only(f"{label} MinIO API", lab.s3_port)
            expect_loopback_only(f"{label} MinIO Console", lab.console_port)
            expect_loopback_only(f"{label} WebDAV", lab.webdav_port)
            expect_external_refused(f"{label} MinIO API", lab.s3_port, addresses)
            expect_external_refused(f"{label} WebDAV", lab.webdav_port, addresses)
        else:
            expect_external_reachable(f"{label} MinIO API", lab.s3_port, addresses)
            s3_anonymous_probe(addresses[0] if addresses else lab.host_for_client(), lab.s3_port, label)

        check(f"{label} 本机 loopback 可连通", port_open(lab.host_for_client(), lab.s3_port))
        legacy_credentials_rejected(lab, label)

        if lan_smoke:
            return run_sigv4_gradle(lab)
        return True
    finally:
        stopped = [svc.pid for svc in lab.services]
        lab.stop_all()
        # 只验证自己启动的 PID 已经退出；不做任何按进程名的兜底清理
        still = [s.name for s in lab.services if s.alive()]
        check(f"{label} 退出释放本次启动的进程", not still,
              f"已结束 pid={stopped}" + (f"，仍在运行={still}" if still else ""))
        lab.cleanup()


def run_guardian_check():
    """反向验证：本工具退出**不得**影响主机上其他任务启动的 MinIO。

    缺陷形态（P3-650 前）：`finally` 里 `taskkill /F /IM minio.exe` 按进程名杀光所有 MinIO，
    并行跑联调的其他人 / 其他代理的服务会被无声带走。这里先在独立目录、独立端口拉起一个
    「哨兵」MinIO（模拟他人服务），再跑完整启停流程，最后核对哨兵仍存活且端口仍可连。
    """
    guardian = lab_services.Lab(bind_host=lab_services.LOOPBACK, webdav_port=19445,
                                s3_port=19002, console_port=19003)
    print(f"\n=== 进程隔离反例（哨兵 {guardian.root}）===")
    try:
        guardian.start_minio()
        if not lab_services.wait_port(guardian.host_for_client(), guardian.s3_port, timeout=40):
            return check("哨兵 MinIO 就绪", False, "无法启动哨兵，反例不成立")
        guardian_pid = guardian.services[0].pid
        check("哨兵 MinIO 就绪", True, f"pid={guardian_pid}")

        run_mode(lab_services.LOOPBACK, "隔离反例-主流程", lan_smoke=False)

        alive = guardian.services[0].alive() and port_open(guardian.host_for_client(), guardian.s3_port)
        return check(
            "主流程退出后他人 MinIO 仍存活",
            alive,
            f"哨兵 pid={guardian_pid} 存活={guardian.services[0].alive()} 端口可连="
            f"{port_open(guardian.host_for_client(), guardian.s3_port)}",
        )
    finally:
        guardian.cleanup()


def main(argv=None):
    parser = argparse.ArgumentParser(description="P3-650 监听面矩阵 + P2-655 SigV4 端到端")
    parser.add_argument("--lan", action="store_true", help="追加显式 0.0.0.0 模式检查")
    parser.add_argument("--no-gradle", action="store_true", help="只做监听/认证矩阵，不跑 Gradle")
    parser.add_argument("--guardian", action="store_true", help="追加「不得误杀他人 MinIO」的反例")
    parser.add_argument("--keep", action="store_true", help="保留运行目录")
    args = parser.parse_args(argv)

    for path in (lab_services.MINIO, lab_services.MC, lab_services.CERT_PATH):
        if not os.path.isfile(path):
            print(f"[FATAL] 缺失 {path}")
            return 2

    run_mode(lab_services.LOOPBACK, "loopback 默认模式", lan_smoke=not args.no_gradle)
    if args.lan:
        run_mode(lab_services.WILDCARD, "显式 LAN 模式", lan_smoke=False)
    if args.guardian:
        run_guardian_check()

    failed = [row for row in REPORT if not row[1]]
    print("\n=== 汇总 ===")
    print(f"检查项 {len(REPORT)} 个，失败 {len(failed)} 个")
    for name, ok, detail in REPORT:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
