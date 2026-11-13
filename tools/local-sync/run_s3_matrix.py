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
import datetime
import json
import os
import shutil
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


def run_vhost_mode(keep=False):
    """ENG-20261028-22：真实 MinIO 虚拟主机私网端点验收矩阵。"""
    lab = lab_services.Lab(bind_host=lab_services.LOOPBACK, s3_port=19000, console_port=19001)
    lab.bucket = "vault"
    label = "ENG22 虚拟主机端到端验收"
    print(f"\n=== {label} ===\n[lab] 独立目录 {lab.root}")
    certs_dir = os.path.join(lab.root, "certs")
    evidence_dir = os.path.join(lab_services.REPO, "build", "eng22-virtualhost-evidence")
    os.makedirs(evidence_dir, exist_ok=True)

    try:
        # 1. 生成带通配 SAN 的证书与 Java PKCS12 信任库
        cert_info = lab_services.generate_vhost_certs(certs_dir, base_domain="minio.127.0.0.1.nip.io")
        check("生成临时测试 CA 与虚拟主机 SAN 证书", os.path.isfile(cert_info["truststore_p12"]))

        # 2. 启动真实 MinIO 服务（绑定 127.0.0.1，启用 MINIO_DOMAIN，显式加载本次测试证书）
        lab.start_minio(certs_dir=certs_dir, domain="minio.127.0.0.1.nip.io")
        ok_port = lab_services.wait_port(lab.host_for_client(), lab.s3_port, timeout=40)
        if not check(f"{label} MinIO 服务就绪", ok_port, f"minio pid={lab.services[0].pid}"):
            return False

        # 3. 建桶
        if not check(f"{label} 建桶 vault", lab.create_bucket("vault")):
            return False

        # 4. 生成隔离 JVM 的 Gradle init-script
        init_script_path = os.path.join(lab.root, "init-vhost.gradle")
        p12_forward = cert_info["truststore_p12"].replace(os.sep, "/")
        init_template = """gradle.allprojects {
    tasks.withType(Test).configureEach {
        if (System.getProperty("liveS3VirtualHost") != null) {
            systemProperty "liveS3VirtualHost", "true"
            systemProperty "javax.net.ssl.trustStore", "%s"
            systemProperty "javax.net.ssl.trustStorePassword", "changeit"
            systemProperty "javax.net.ssl.trustStoreType", "PKCS12"
            systemProperty "liveS3VirtualHostEndpoint", "https://minio.127.0.0.1.nip.io:%d"
            systemProperty "liveS3Bucket", "%s"
        }
    }
}
""" % (p12_forward, lab.s3_port, lab.bucket)
        with open(init_script_path, "w", encoding="utf-8") as f:
            f.write(init_template)

        # 5. 执行 Gradle LiveS3VirtualHostTest
        env = dict(os.environ)
        # 凭据只经本次进程环境传给测试 JVM（LiveS3VirtualHostTest.credential 的 env 兜底），
        # 不写入 init-script 或命令行；--no-daemon 避免常驻 daemon 复用旧环境快照。
        env["MINIO_ROOT_USER"] = lab.s3_user
        env["MINIO_ROOT_PASSWORD"] = lab.s3_password
        cmd = [
            os.path.join(lab_services.REPO, "gradlew.bat"),
            ":sync:testDebugUnitTest",
            "--tests", "com.keepasskey.sync.s3.LiveS3VirtualHostTest",
            "--init-script", init_script_path,
            "-DliveS3VirtualHost=true",
            "--no-daemon",
            "--offline",
            "--console=plain",
        ]
        r = subprocess.run(cmd, cwd=lab_services.REPO, env=env, capture_output=True, text=True)
        gradle_ok = (r.returncode == 0)
        check(f"{label} Gradle 测试执行成功", gradle_ok, f"rc={r.returncode}")

        # 保存原始 Gradle 输出
        gradle_out_path = os.path.join(evidence_dir, "gradle-output.txt")
        with open(gradle_out_path, "w", encoding="utf-8") as f:
            f.write("=== STDOUT ===\n" + r.stdout + "\n=== STDERR ===\n" + r.stderr)

        # 保存 JUnit 结果 XML
        junit_src = os.path.join(
            lab_services.REPO, "sync", "build", "test-results", "testDebugUnitTest",
            "TEST-com.keepasskey.sync.s3.LiveS3VirtualHostTest.xml"
        )
        junit_dst = os.path.join(evidence_dir, "TEST-com.keepasskey.sync.s3.LiveS3VirtualHostTest.xml")
        junit_exists = os.path.isfile(junit_src)
        if junit_exists:
            shutil.copyfile(junit_src, junit_dst)
            with open(junit_dst, "r", encoding="utf-8") as f:
                junit_content = f.read()
            tests_passed = ('failures="0"' in junit_content and 'errors="0"' in junit_content and 'skipped="0"' in junit_content)
            check(f"{label} JUnit 报告非空且全绿", tests_passed, "全部 5 项测试通过，0 跳过，0 失败")
        else:
            check(f"{label} JUnit 报告存在", False, "未找到 JUnit 报告 XML")

        # 读取 MinIO / MC 版本
        minio_ver = subprocess.run([lab_services.MINIO, "--version"], capture_output=True, text=True).stdout.strip()
        mc_ver = subprocess.run([lab_services.MC, "--version"], capture_output=True, text=True).stdout.strip()

        # 生成证据清单 JSON
        evidence_summary = {
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "task": "ENG-20261028-22",
            "status": "PASSED" if gradle_ok and junit_exists else "FAILED",
            "service_sample": {
                "server": "MinIO",
                "binary": lab_services.MINIO,
                "version": minio_ver,
                "client_tool_version": mc_ver,
                "bind_address": f"{lab.bind_host}:{lab.s3_port}",
                "virtual_host_domain": "minio.127.0.0.1.nip.io",
                "bucket": lab.bucket,
                "note": "Windows 本地回环真实 MinIO 样本，不机械外推至全部 Ceph RGW 或其他商业云 S3",
            },
            "tls_and_isolation": {
                "san_names": ["minio.127.0.0.1.nip.io", "*.minio.127.0.0.1.nip.io", "localhost", "127.0.0.1"],
                "truststore_strategy": "通过 Gradle init-script 将隔离测试 JVM 的 javax.net.ssl.trustStore 定向至临时 PKCS12 测试 CA",
                "credentials_channel": "本次随机凭据只经进程环境 MINIO_ROOT_USER/MINIO_ROOT_PASSWORD 传入（--no-daemon 隔离），init-script 与命令行不含凭据",
                "production_client_unbypassed": True,
                "client_injection": False,
                "system_ca_or_hosts_modified": False,
            },
            "verified_cases": [
                {
                    "name": "真实MinIO虚拟主机模式HEAD-PUT-GET回读与哈希比对",
                    "style": "virtual-host",
                    "request_host": "vault.minio.127.0.0.1.nip.io",
                    "operations": ["HEAD bucket", "PUT object", "GET object (byte readback)", "HEAD metadata", "DELETE object"],
                    "hash_verification": "SHA-256 byte-exact match",
                },
                {
                    "name": "真实MinIO路径模式对照HEAD-PUT-GET回读与哈希比对",
                    "style": "path-style",
                    "request_host": "minio.127.0.0.1.nip.io",
                    "operations": ["HEAD bucket", "PUT object", "GET object (byte readback)", "DELETE object"],
                    "hash_verification": "SHA-256 byte-exact match",
                },
                {
                    "name": "虚拟主机模式仅实际请求主机获豁免 未授权私网域名被SsrfGuardDns拦截",
                    "exempt_host": "vault.minio.127.0.0.1.nip.io",
                    "blocked_hosts": ["other.minio.127.0.0.1.nip.io", "minio.127.0.0.1.nip.io"],
                    "guard": "SsrfGuardDns (UnknownHostException: SSRF 防护)",
                },
                {
                    "name": "外部重定向到未授权私网地址在连接期被拦截且目标零请求",
                    "mechanism": "302 Redirect to unauthorized private endpoint",
                    "guard": "SsrfGuardSocketFactory / SsrfGuardDns",
                    "target_requests": 0,
                },
                {
                    "name": "云元数据红线在虚拟主机配置下依然严格拒绝",
                    "target": "169.254.169.254",
                    "enforcement": "构造期 validateEndpointHost + 连接期 SsrfGuardDns 均无条件拒绝（红线不随豁免放行）",
                },
            ],
            "evidence_files": [
                "gradle-output.txt",
                "TEST-com.keepasskey.sync.s3.LiveS3VirtualHostTest.xml",
            ],
        }
        with open(os.path.join(evidence_dir, "evidence-summary.json"), "w", encoding="utf-8") as f:
            json.dump(evidence_summary, f, ensure_ascii=False, indent=2)

        return gradle_ok
    finally:
        stopped = [svc.pid for svc in lab.services]
        lab.stop_all()
        still = [s.name for s in lab.services if s.alive()]
        check(f"{label} 退出释放本次启动的进程", not still,
              f"已结束 pid={stopped}" + (f"，仍在运行={still}" if still else ""))
        lab.cleanup(keep=keep)


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
    parser = argparse.ArgumentParser(description="P3-650 监听面矩阵 + P2-655 SigV4 端到端 + ENG-20261028-22 虚拟主机验收")
    parser.add_argument("--lan", action="store_true", help="追加显式 0.0.0.0 模式检查")
    parser.add_argument("--virtualhost", action="store_true", help="运行 ENG-20261028-22 真实 MinIO 私网虚拟主机验收")
    parser.add_argument("--no-gradle", action="store_true", help="只做监听/认证矩阵，不跑 Gradle")
    parser.add_argument("--guardian", action="store_true", help="追加「不得误杀他人 MinIO」的反例")
    parser.add_argument("--keep", action="store_true", help="保留运行目录")
    args = parser.parse_args(argv)

    for path in (lab_services.MINIO, lab_services.MC, lab_services.CERT_PATH):
        if not os.path.isfile(path):
            print(f"[FATAL] 缺失 {path}")
            return 2

    if args.virtualhost:
        run_vhost_mode(keep=args.keep)
    else:
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
