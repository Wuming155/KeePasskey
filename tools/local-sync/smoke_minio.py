#!/usr/bin/env python3
"""MinIO(S3) 冒烟：独立数据目录 + 按次随机凭据 + 仅 loopback，走真实 HTTPS SigV4。

ISSUE-P3-650 起：
- 不再使用源码固定的 `tester/tester1234`；
- 不再用 `--address :9000`（等于监听所有接口），默认只绑 127.0.0.1；
- 数据目录是 `build/local-sync-lab/<run-id>/` 下的一次性目录，不读写 `tools/local-sync/minio-data`；
- 退出只结束本脚本启动的 PID。
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import lab_services  # noqa: E402

BUCKET = "keepasskey-smoke"
OBJECT = "vault.kdbx"
PAYLOAD = b"s3-kdbx-bytes-payload"


def mc(lab, *args):
    env = dict(os.environ)
    env["MC_CONFIG_DIR"] = os.path.join(lab.root, "mc-config")
    return subprocess.run([lab_services.MC, "--insecure", *args], env=env,
                          capture_output=True, text=True)


def main():
    lab = lab_services.Lab(bind_host=lab_services.LOOPBACK, s3_port=19000, console_port=19001)
    print(f"[smoke] 独立目录 {lab.root}（凭据按次随机，值不落日志）")
    ok = True
    try:
        lab.start_minio()
        if not lab_services.wait_port(lab.host_for_client(), lab.s3_port, timeout=40):
            print("[FATAL] MinIO API 端口未就绪")
            return 1
        alias = "smoke"
        endpoint = f"https://{lab.host_for_client()}:{lab.s3_port}"

        results = []
        results.append(("建别名（真实 SigV4 认证）",
                        mc(lab, "alias", "set", alias, endpoint, lab.s3_user, lab.s3_password).returncode == 0))
        created = mc(lab, "mb", f"{alias}/{BUCKET}")
        results.append(("建桶", created.returncode == 0 or "exists" in (created.stdout + created.stderr)))

        local_put = os.path.join(lab.root, "put.bin")
        local_get = os.path.join(lab.root, "get.bin")
        with open(local_put, "wb") as handle:
            handle.write(PAYLOAD)
        results.append(("上传", mc(lab, "cp", local_put, f"{alias}/{BUCKET}/{OBJECT}").returncode == 0))
        results.append(("下载", mc(lab, "cp", f"{alias}/{BUCKET}/{OBJECT}", local_get).returncode == 0))
        with open(local_get, "rb") as handle:
            downloaded = handle.read()
        results.append(("下载字节一致", downloaded == PAYLOAD))
        results.append(("删除", mc(lab, "rm", f"{alias}/{BUCKET}/{OBJECT}").returncode == 0))

        for name, passed in results:
            print(f"[{'PASS' if passed else 'FAIL'}] {name}")
            ok = ok and passed
        print("RESULT:", "ALL PASS" if ok else "HAS FAILURE")
        return 0 if ok else 1
    finally:
        lab.stop_all()
        lab.cleanup()


if __name__ == "__main__":
    sys.exit(main())
