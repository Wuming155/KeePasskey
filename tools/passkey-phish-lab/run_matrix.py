"""逐用例驱动 Chrome 跑唤醒矩阵（`ISSUE-P3-339` 浏览器半环）。

⚠️ 当前状态：**被证书信任卡住**（见 README「已证伪的路线」1/2 条）。本脚本仍先行落地，
一旦拿到「两个不同可注册域 + 公开可信证书」即可直接复跑；在没有受信证书时运行它，
会把每个用例的 `ERR_CERT_AUTHORITY_INVALID` / `NotAllowedError` 原样登记成阻塞读数——
**它不会把阻塞伪装成通过**。

判据分工：浏览器侧结局取自 RP 服务的 `/status`（页面自跑后 POST 回来），
本应用侧结局取自 logcat 切片（provider 是否被调、有没有走到签名分支）。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import ssl
import urllib.request
from dataclasses import dataclass

from make_certs import CERT_DIR, ascii_lab_names

PORT_DEFAULT = 8443


@dataclass(frozen=True)
class Case:
    case_id: str
    origin_host: str        # 地址栏里的域（真仿冒形态）
    rp: str                 # 页面向浏览器申报的 rpId
    expect: str             # "wake" | "silent"
    note: str


def default_matrix() -> list[Case]:
    """域矩阵与代码层表驱动用例**同一套形态**，两边结论可互相印证。"""
    return [
        Case("P1", "rp.testlab.xyz", "rp.testlab.xyz", "wake", "真域精确同 eTLD+1"),
        Case("P2", "sub.rp.testlab.xyz", "rp.testlab.xyz", "wake", "同 eTLD+1 子域（规范允许）"),
        Case("N1", "rp.testlab.xyz.phish.testlab.xyz", "phish.testlab.xyz", "silent",
             "把真域堆在别域后缀里"),
        Case("N2", "phish.testlab.xyz", "phish.testlab.xyz", "silent", "库里根本没有这个域"),
        Case("N3", "xn--r-5tb.testlab.xyz", "xn--r-5tb.testlab.xyz", "silent", "同形异码仿冒"),
        Case("N4", "10.0.2.2", "10.0.2.2", "silent", "IP origin 不参与域匹配"),
    ]


def adb(serial: str, *args: str) -> str:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def open_url(serial: str, url: str) -> None:
    adb(serial, "shell", "am", "force-stop", "com.android.chrome")
    time.sleep(1.5)
    adb(serial, "shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", url, "-n", "com.android.chrome/com.google.android.apps.chrome.Main")


def read_status(base: str) -> list[dict]:
    """读 RP 的读数端点：**带**证书校验（CA = 本实验室自建的那张，SAN 含 127.0.0.1）。"""
    context = ssl.create_default_context(cafile=CERT_DIR / "ca.pem")
    with urllib.request.urlopen(base, timeout=15, context=context) as response:
        return json.loads(response.read().decode())["readings"]


def run_case(serial: str, base: str, case: Case, action: str, settle: float) -> dict:
    before = len(read_status(base))
    open_url(serial, f"https://{case.origin_host}:{PORT_DEFAULT}/case/{action}/{case.rp}")
    time.sleep(settle)
    readings = read_status(base)[before:]
    browser = [r for r in readings if r.get("at") == "browser"]
    server = [r for r in readings if r.get("at") in ("get", "create")]
    last_browser = browser[-1] if browser else None
    woke = bool(server) and server[-1].get("ok") is True
    return {
        "case": case.case_id, "origin": case.origin_host, "rp": case.rp, "expect": case.expect,
        "note": case.note, "woke": woke,
        "browserOutcome": (last_browser or {}).get("outcome"),
        "errorName": (last_browser or {}).get("errorName"),
        "browserDetail": str((last_browser or {}).get("detail"))[:120],
        "serverDetail": {k: v for k, v in (server[-1] if server else {}).items()
                         if k in ("rpIdHashMatches", "origin", "credentialId", "signCountStored",
                                  "signCountNew", "cloneSuspicion", "signatureVerified", "source")},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--action", choices=("get", "create"), default="get")
    parser.add_argument("--settle", type=float, default=16.0)
    parser.add_argument("--status-url", default=f"https://127.0.0.1:{PORT_DEFAULT}/status")
    parser.add_argument("--out", type=argparse.FileType("w", encoding="utf-8"), default=None)
    args = parser.parse_args()

    host_status = adb(args.serial, "shell", "getprop", "sys.boot_completed").strip()
    if host_status != "1":
        raise SystemExit("设备未就绪（sys.boot_completed != 1）")

    rows = []
    for case in default_matrix():
        try:
            rows.append(run_case(args.serial, args.status_url, case, args.action, args.settle))
        except Exception as exc:                                  # 阻塞必须显式成一行读数
            rows.append({"case": case.case_id, "origin": case.origin_host, "rp": case.rp,
                         "expect": case.expect, "note": case.note, "blocked": f"{type(exc).__name__}: {exc}"})
        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)

    verdict = []
    for row in rows:
        if "blocked" in row:
            verdict.append(f"{row['case']} 阻塞（{row['blocked'][:70]}）")
            continue
        # 页面**一条读数都没回来**时不得判 PASS：那说明请求根本没跑起来（证书不被信任 /
        # 名字解析不到），此时「仿冒域没唤醒」是环境造成的，不是判据成立。
        # 首轮实测就差点栽在这：P1/P2 无读数被判 FAIL、N1~N4 无读数被判 PASS——
        # 六个用例其实是同一个原因，负向全绿纯属环境假绿。
        if row.get("browserOutcome") is None:
            verdict.append(f"{row['case']} 无效（页面无读数：证书 / 解析未通，不得计为通过）")
            continue
        want_wake = row["expect"] == "wake"
        ok = row["woke"] is want_wake
        verdict.append(f"{row['case']} {'PASS' if ok else 'FAIL'} expect={row['expect']} "
                       f"woke={row['woke']} browser={row['browserOutcome']}/{row['errorName']}")
    print("\n".join(verdict))
    if args.out:
        json.dump({"rows": rows, "verdict": verdict}, args.out, ensure_ascii=False, indent=1)
    print(f"\nA-label 全集（证书 SAN / hosts 用）：{ascii_lab_names()}")


if __name__ == "__main__":
    main()
