#!/usr/bin/env python3
"""生成 SigV4 对拍向量（ISSUE-P2-655 独立实现对拍）。

用 **官方 SDK**（botocore）按本仓签名器实际签的头部集合
（`host;x-amz-content-sha256;x-amz-date`、service=`s3`、payload 为空体摘要）计算签名，
输出可直接贴进 `sync/src/test/java/com/keepasskey/sync/s3/S3OfficialSigV4VectorTest.kt`
的常量。仅用于**生成**期望值；对拍本身是 Kotlin 测试，运行时不依赖 Python。

用法：
    python tools/local-sync/gen_sigv4_vectors.py

前置：`pip install botocore`（本仓已在开发环境验证 1.43.5）。
"""
import datetime
import json

import botocore.auth as botocore_auth
from botocore.auth import S3SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

ACCESS_KEY = "AKIDEXAMPLE"
SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
REGION = "us-east-1"
FIXED_TIME = datetime.datetime(2015, 8, 30, 12, 36, 0, tzinfo=datetime.timezone.utc)
AMZ_DATE = FIXED_TIME.strftime("%Y%m%dT%H%M%SZ")
EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

# (用例名, method, URL)：URL 的查询串**必须**是已按 SigV4 规则编码的 wire 形态，
# 与 S3RequestSigner.canonicalQueryString 的输出逐字一致（这正是被测的那份规范输出）。
CASES = [
    ("amp_prefix_first_page", "GET",
     "https://example.amazonaws.com/bucket"
     "?delimiter=%2F&list-type=2&max-keys=1&prefix=notes%26archive%2F"),
    ("amp_cursor_second_page", "GET",
     "https://example.amazonaws.com/bucket"
     "?continuation-token=1%2Ftok%26en%3D9%2Bz&delimiter=%2F&list-type=2&max-keys=1"
     "&prefix=notes%26archive%2F"),
    ("unicode_and_reserved_value", "GET",
     # `~` 是 RFC 3986 unreserved 字符，按 SigV4 规则**不编码**（原样出现在 wire 与 canonical 中）；
     # 这里的 URL 即「本仓 S3RequestSigner.canonicalQueryString 会产出的形态」
     "https://example.amazonaws.com/bucket"
     "?delimiter=%2F&list-type=2&max-keys=1000&prefix=%E4%B8%AD%E6%96%87%20%E5%BA%93%2B%25~"),
    ("repeated_key_sorted_by_value", "GET",
     "https://example.amazonaws.com/bucket?tag=a&tag=b"),
    ("empty_query", "GET", "https://example.amazonaws.com/bucket"),
    ("path_with_ampersand", "GET",
     "https://example.amazonaws.com/bucket/notes%26archive/a%20b.kdbx"),
]


def sign(method, url):
    """用官方 SDK 计算 Authorization。

    botocore 默认取 `datetime.utcnow()`；这里把其时间源钉到向量时间点，
    否则同一向量每次生成都会得到不同签名（不可复现）。
    """
    original = botocore_auth.get_current_datetime
    botocore_auth.get_current_datetime = lambda: FIXED_TIME
    try:
        request = AWSRequest(
            method=method,
            url=url,
            data=b"",
            # x-amz-date 由 SDK 依时间源写入；显式头部集合对齐本仓签名器
            headers={"x-amz-content-sha256": EMPTY_SHA256},
        )
        # 用 S3 变体：S3 的 SigV4 不对对象键路径做二次编码（键已在 wire URL 里编码好），
        # 与本仓 S3KeyCodec.encodePath → canonicalUri 的口径一致；通用 SigV4Auth 会把
        # `%26` 再编码成 `%2526`，那是非 S3 的 URI 双重编码语义，不能作本仓的对拍基准。
        S3SigV4Auth(Credentials(ACCESS_KEY, SECRET_KEY), "s3", REGION).add_auth(request)
        return request.headers["Authorization"], request.headers["X-Amz-Date"]
    finally:
        botocore_auth.get_current_datetime = original


def main():
    rows = []
    for name, method, url in CASES:
        authorization, amz_date = sign(method, url)
        signature = authorization.rsplit("Signature=", 1)[1]
        assert amz_date == AMZ_DATE, (name, amz_date)
        rows.append({"name": name, "method": method, "url": url, "signature": signature})
        print(f"{name}\n  {url}\n  {signature}")
    print("\nKotlin 常量：")
    for row in rows:
        print(f'        "{row["name"]}" to SigV4Vector(')
        print(f'            method = "{row["method"]}",')
        print(f'            url = "{row["url"]}",')
        print(f'            signature = "{row["signature"]}"')
        print("        ),")
    print("\nJSON：")
    print(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
