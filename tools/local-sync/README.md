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

## 场景 → 测试映射总表

10 项经典场景在三层（Provider 协议层 / 引擎与合并层 / 真实服务联调层）的落点。
Mock 层为确定性单测（随 CI 常跑），LIVE 层需启动本地服务（`run_lab.py`）。

| # | 场景 | Mock 层（确定性） | LIVE 层（真实服务） | 既有覆盖 |
|---|------|-------------------|---------------------|----------|
| 1 | 多端并发冲突（双编/双删/编删） | `WebDavSyncScenarioTest` 场景1×3（412 拒绝 / 同基线竞态恰好一胜 / 编辑对删除）`S3SyncScenarioTest` 场景1×2（HEAD 预检即拒 / 条件写 412） | `LIVE MinIO 同基线并发条件写 恰好一胜` | 合并层：删除vs修改、删除后重建、双方同字段冲突（KdbxMergerV2Test）；引擎层：双方修改冲突、404 自愈（SyncEngineTest） |
| 2 | 大文件分段上传下载 | 两协议 2MiB 字节精确往返 + 元数据大小一致 | 两协议 1MiB 往返 | — |
| 3 | 网络中断与重试 | MOVE 重试成功 / 持续失败回滚后目标原状（幂等）/ 上传中断不污染 / 下载超时 / 半截数据不静默交付 / 5xx、401 映射 / 删除幂等 | 全链路超时预算内完成 | 引擎层：网络不可达降级、冲突后下载失败不伪造空远端（SyncEngineTest） |
| 4 | 元数据一致性 | ETag→If-Match 规范化（弱校验清洗+引号）/ PUT 缺 ETag 回退 PROPFIND / 回退失败即失败 / ISO8601 与 RFC1123 时间 / 多 response 解析 / S3 HEAD ETag+Last-Modified+大小 | `LIVE 元数据一致性 ETag跨操作稳定` | cleanEtag 单测（SyncModelsTest） |
| 5 | 特殊字符与中文文件名 | 两协议全量编码断言（`# ? & = % +` 空格、中文、防裸 `?`）+ MOVE Destination 编码 | WebDAV 中文名往返；S3 嵌套中文键往返（真实 SigV4 一致性） | WebDavSyncProviderTest 中文空格编码 |
| 6 | 空文件/零字节 | 零字节 PUT/GET/元数据；S3 空载荷 SHA-256 头与 `If-None-Match:*` | 两协议零字节往返 | — |
| 7 | 深层嵌套与路径归一 | 端点尾斜杠×路径前导斜杠四种组合归一 + 深层 MOVE 保结构；S3 嵌套键 | S3 嵌套中文键 | — |
| 8 | 并发上传不同版本 | 并入场景 1 同基线竞态；S3 首传竞态结果有界（成败仅限 ConflictError，内容完整不混合） | `LIVE MinIO 同基线并发条件写` | — |
| 9 | 后端不可用降级 | 连接拒绝 / 连接超时 / HTTPS 对明文服务器握手失败 | 全链路在真实服务下通过 | 引擎层降级用缓存（SyncEngineTest） |
| 10 | 大目录分页 | **N/A**：`SyncProvider` 契约无 LIST 操作（KDBX 单文件同步模型，无目录枚举需求）；可行部分以 PROPFIND 多 response 解析健壮性覆盖 | — | — |

**分段上传/断点续传（场景 2 一部分）同样 N/A**：`SyncProvider` 契约为整文件上传
（`upload/uploadAtomic`），未实现分块协议。KDBX 库文件体积通常远低于分块阈值，
如未来引入大附件分块，需扩展契约并补齐分块合并/续传用例。

### 测试过程中发现并修复的真实缺陷

1. **`WebDavSyncProvider.getMetadata` 零字节文件元数据撒谎**（场景 6 挖出）：
   `getcontentlength=0` 因 `> 0` 判断被忽略，回退到 207 响应的 HTTP `Content-Length`
   ——那是 PROPFIND XML 报文自身大小（数百字节），零字节文件大小被误报。
   已改为 `-1` 哨兵区分「节点缺失」与「节点存在但为 0」。
2. 有状态模拟器自身的 check-then-act 竞态（MockWebServer 每连接一线程）曾让两个并发
   MOVE 双双通过预条件——修复后「恰好一胜」断言才成立。真实服务端本就原子化
   预条件评估与写入；此坑记录于此供后续写协议模拟器参考。

## 已知限界

- wsgidav 不强制校验 WebDAV `If`/`If-Match` 预条件（412 路径由 MockWebServer 单测与
  有状态模拟器覆盖，服务端原子性已在 MinIO 侧真实验证）。
- Windows 文件系统保留字符（`\ / : * ? " < > |`）无法在 wsgidav 真机用例中落盘，
  其编码正确性由 MockWebServer 层断言覆盖。
- MinIO 私钥必须是 **PKCS8** 格式（`BEGIN PRIVATE KEY`），否则静默回退 HTTP。
- 测试通过「注入信任本地证书的 `OkHttpClient`」绕过生产路径的系统 CA 强校验，
  与既有 MockWebServer 回环注入方式一致，不影响生产代码。
