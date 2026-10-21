#!/usr/bin/env python3
"""KeePasskey 本地 HTTPS 同步联调一体化脚本（ISSUE-P3-650 起只管理自身进程）。

流程：
1. 拉起本地 HTTPS WebDAV（wsgidav + 自签名证书）
2. 拉起本地 HTTPS MinIO(S3)，创建测试桶
3. 运行 sync 模块 LiveSyncServersTest（真实服务集成测试）
4. 运行 app 模块 KDBX 语义真实 WebDAV 往返测试
5. 回收**本次启动的**本地服务（按 PID，绝不按进程名批量 taskkill）

默认只监听 loopback 且每次运行生成随机凭据。需要在同一网段的其他设备上联调时显式加
`--bind 0.0.0.0`（凭据仍为随机；**不允许**固定弱口令作为外部监听回退），更推荐 `adb reverse`
把设备流量转回本机，避免真的对外开端口。

用法：
    python run_lab.py                # 仅本机
    python run_lab.py --bind 0.0.0.0 # 显式外部监听（随机凭据，退出即回收）
    python run_lab.py --keep         # 保留本次运行目录（默认退出即删）

前置：pip install wsgidav cheroot pyopenssl；bin/ 下含 minio.exe / mc.exe。
"""
import argparse
import os
import subprocess
import sys

import lab_services

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = lab_services.REPO
GRADLEW = os.path.join(REPO, "gradlew.bat")
LIVE_ARTIFACT_DIR = os.path.join(REPO, ".ci-artifacts", "live-sync-kdbx")


def gradle_live(lab, target_args):
    """运行带 live 参数的 Gradle 任务。

    凭据一律以 `-D` 传入：Gradle daemon 会被后续客户端复用，`System.getenv()` 在构建脚本里
    读到的是 **daemon 启动时**的环境快照，本轮随机凭据可能因此不可见（首轮实测即因此 7 例
    403）。命令行属性随每次构建请求下发，不受 daemon 复用影响。
    """
    cmd = [GRADLEW] + target_args + [
        "-DliveSyncTest=true",
        f"-DliveCertPath={lab_services.CERT_PATH}",
        f"-DliveWebdavUrl=https://{lab.host_for_client()}:{lab.webdav_port}",
        f"-DliveWebdavUser={lab.webdav_user}",
        f"-DliveWebdavPass={lab.webdav_password}",
        f"-DliveS3Endpoint=https://{lab.host_for_client()}:{lab.s3_port}",
        f"-DliveS3Bucket={lab.bucket}",
        f"-DliveS3Access={lab.s3_user}",
        f"-DliveS3Secret={lab.s3_password}",
    ]
    return subprocess.run(cmd, cwd=REPO).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description="KeePasskey 本地 HTTPS 同步联调")
    parser.add_argument("--bind", default=lab_services.LOOPBACK,
                        help="监听地址（默认 127.0.0.1；显式外部联调用 0.0.0.0）")
    parser.add_argument("--webdav-port", type=int, default=9443)
    parser.add_argument("--s3-port", type=int, default=9000)
    parser.add_argument("--console-port", type=int, default=9001)
    parser.add_argument("--keep", action="store_true", help="保留本次运行数据目录（默认删除）")
    args = parser.parse_args(argv)

    lab = lab_services.Lab(
        bind_host=args.bind,
        webdav_port=args.webdav_port,
        s3_port=args.s3_port,
        console_port=args.console_port,
    )
    print(f"[lab] 独立运行目录 {lab.root}（数据不复用既有目录）")
    if args.bind not in (lab_services.LOOPBACK, "localhost"):
        print(f"[lab] 注意：显式外部监听 {args.bind}，凭据按次随机且退出即回收进程")

    # 凭据只经环境变量进入子进程；不回显值。
    os.environ["WEBDAV_HOST"] = lab.host_for_client()
    os.environ["WEBDAV_USER"] = lab.webdav_user
    os.environ["WEBDAV_PASSWORD"] = lab.webdav_password
    os.environ.setdefault("LIVE_SYNC_TEST", "1")

    try:
        lab.start_webdav()
        if not lab_services.wait_port(lab.host_for_client(), lab.webdav_port, timeout=30):
            print("[FATAL] WebDAV 未就绪")
            return 1
        print(f"[lab] WebDAV HTTPS 就绪 {lab.host_for_client()}:{lab.webdav_port}")

        lab.start_minio()
        if not lab_services.wait_port(lab.host_for_client(), lab.s3_port, timeout=40):
            print("[FATAL] MinIO 未就绪")
            return 1
        print(f"[lab] MinIO HTTPS 就绪 {lab.host_for_client()}:{lab.s3_port}")

        if not lab.create_bucket():
            print("[WARN] 建桶可能失败，S3 测试可能失败")

        print("[lab] 运行 sync LiveSyncServersTest（针对真实本地服务）...")
        sync_rc = gradle_live(lab, [
            ":sync:testDebugUnitTest",
            "--tests", "com.keepasskey.sync.LiveSyncServersTest",
            "--tests", "com.keepasskey.sync.s3.LiveS3DirectoryListTest",
        ])
        print(f"[lab] sync Gradle 退出码: {sync_rc}")
        if sync_rc != 0:
            return sync_rc

        os.makedirs(LIVE_ARTIFACT_DIR, exist_ok=True)
        print("[lab] 运行 app KDBX 语义真实 WebDAV 往返...")
        app_rc = gradle_live(lab, [
            ":app:testDebugUnitTest",
            "--tests", "com.keepasskey.app.sync.LiveSyncKdbxSemanticsTest",
            f"-DliveArtifactDir={LIVE_ARTIFACT_DIR}",
        ])
        print(f"[lab] app Gradle 退出码: {app_rc}")
        return app_rc
    finally:
        print("[lab] 回收本次启动的本地服务（按 PID）...")
        lab.cleanup(keep=args.keep)


if __name__ == "__main__":
    sys.exit(main())
