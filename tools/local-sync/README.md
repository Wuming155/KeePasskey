# 本地 HTTPS 同步联调环境

为 KeePasskey 的同步引擎（`sync` 模块）提供**真实协议**的本地测试服务端，
用于验证 WebDAV / S3 兼容存储的真实 HTTPS 链路与「下载 → 三方合并」全流程。

> 均使用 `localhost` 自签名证书（`certs/cert.pem`，SAN 含 `DNS:localhost` / `IP:127.0.0.1`）。
> 仅限本机联调，禁止公网暴露。

## 组成

| 组件 | 端口 | 说明 |
|------|------|------|
| **WebDAV** | `9443` | wsgidav + cheroot（HTTPS，Basic Auth，用户 `tester` / `tester123`） |
| **MinIO (S3)** | `9000` | MinIO（HTTPS + SigV4，AccessKey `tester` / `tester1234`，桶 `keepasskey-test`） |
| MinIO Console | `9001` | Web 管理界面（可选） |

对应测试：`sync/src/test/java/com/keepasskey/sync/LiveSyncServersTest.kt`
（默认跳过，仅当设置 `LIVE_SYNC_TEST=1` 或 `-DliveSyncTest` 时执行）。

## 首次准备

```bash
# 1. Python 依赖（wsgidav + SSL）
python -m pip install wsgidav cheroot pyopenssl

# 2. MinIO / mc（已下载则跳过）
curl -sSL -o bin/minio.exe https://dl.min.io/server/minio/release/windows-amd64/minio.exe
curl -sSL -o bin/mc.exe     https://dl.min.io/client/mc/release/windows-amd64/mc.exe

# 3. 自签名证书（若 certs/ 为空则重新生成）
openssl req -x509 -newkey rsa:2048 -nodes -keyout certs/key.pem -out certs/cert.pem \
  -days 3650 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
cp certs/cert.pem certs/minio/public.crt
openssl pkcs8 -topk8 -nocrypt -in certs/key.pem -out certs/minio/private.key
```

## 一键运行（服务 + 真实集成测试）

```bash
python run_lab.py
```

该脚本会：启动 WebDAV → 启动 MinIO → 创建测试桶 → 执行 `LiveSyncServersTest` → 回收服务。

## 手动运行

```bash
# 单独启动 WebDAV（前台，Ctrl-C 退出）
python run_webdav.py

# 单独启动 MinIO（需另开终端）
MINIO_ROOT_USER=tester MINIO_ROOT_PASSWORD=tester1234 \
  bin/minio.exe server minio-data --address :9000 --console-address :9001

# 创建测试桶（mc 对自签名证书需 --insecure，仅测试客户端侧）
bin/mc.exe --insecure alias set local https://localhost:9000 tester tester1234
bin/mc.exe --insecure mb local/keepasskey-test
```

然后运行真实集成测试：

```bash
./gradlew.bat :sync:testDebugUnitTest \
  --tests "com.keepasskey.sync.LiveSyncServersTest" \
  -DliveSyncTest=true \
  -DliveCertPath="<本目录>/certs/cert.pem"
```

## 协议自测（不依赖 Gradle / Kotlin）

```bash
python smoke_webdav.py   # PROPFIND / PUT / GET 字节精确 / MOVE / DELETE
python smoke_minio.py    # 建桶 / 上传 / 下载字节精确 / 删除（SigV4）
```

## 测试覆盖点

- **WebDAV**：PROPFIND 连接测试、PUT（ETag）、GET 下载字节精确、元数据、
  `uploadAtomic` 真实 `PUT .kpktmp → MOVE` 事务写、DELETE。
- **S3**：SigV4 连接测试、首传 `If-None-Match: *`、覆盖 `If-Match` 乐观锁、
  错误 ETag 触发 `412 ConflictError`、DELETE。
- **下载 → 合并**：三方库镜像经真实服务器上传/下载（字节精确），
  由真实 `KdbxMerger.mergeDatabases` 完成并发新增合并与冲突检测。

## 已知限界

- wsgidav 不强制校验 WebDAV `If`/`If-Match` 预条件（412 路径由 MockWebServer 单测覆盖，
  服务端原子性已在 MinIO 侧真实验证）。
- MinIO 私钥必须是 **PKCS8** 格式（`BEGIN PRIVATE KEY`），否则静默回退 HTTP。
- 测试通过「注入信任本地证书的 `OkHttpClient`」绕过生产路径的系统 CA 强校验，
  与既有 MockWebServer 回环注入方式一致，不影响生产代码。
