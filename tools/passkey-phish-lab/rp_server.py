"""仿冒域唤醒实验室的 RP 服务端（`ISSUE-P3-339`）。

## 它证的是什么

一行读数只有同时记下「**origin**（地址栏里的域）与 **rpId**（页面向浏览器申报的 rp）」才有意义：
Chrome 会自己拒掉「rpId 不是 origin 的registrable 域」的请求 ⇒ 那种失败**测的是上游**，
不是本仓代码。真正要证的是 **Chrome 放行了、请求下发到本应用 provider 之后，本应用出不出候选**。
所以每个用例都是 `(origin, rpId)` 二元组，由本页的 `?action=&rp=` 参数决定，
并且**页面自己跑完 create/get 再把结果 POST 回来**（免手工点击，读数可复跑）。

## 判据分工

| 侧 | 读数来源 |
| --- | --- |
| 浏览器 | 本服务 `POST /report`（`outcome` / `errorName` / 耗时） |
| 本应用 provider | `adb logcat`（由 `run_matrix.py` 逐用例切片） |
| 密码学 | `/api/create/verify`、`/api/get/verify`：验 `rpIdHash`、`origin`、签名与**计数器跳变** |

⚠️ 实验室 RP **不代表全网 RP 的严判行为**（`PD-49` 重开条件里写明的口径），
`signCount` 读数只用于回答「本仓导入凭据的计数器起点会不会被判克隆」这一件事。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import ssl
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import minicbor

from cryptography.hazmat.primitives import hashes as chashes
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, padding, rsa
from cryptography.hazmat.primitives.serialization import load_pem_public_key

CERT_DIR = Path(__file__).resolve().parent / "certs"
CHALLENGE_TTL_SECONDS = 300

_state_lock = threading.Lock()
_pending: dict[str, dict] = {}                 # session -> {challenge, rp, action, issuedAt}
_credentials: dict[str, dict] = {}             # "rp|credentialId" -> {pem, signCount, source}
_readings: list[dict] = []


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def unb64url(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def now_ms() -> int:
    return int(time.time() * 1000)


def verify_signature(pem: str, alg: str, signature: bytes, signed_bytes: bytes) -> str:
    """验签；失败抛异常（调用方按「不通过」记录）。返回人读算法名。"""
    key = load_pem_public_key(pem.encode())
    if isinstance(key, ec.EllipticCurvePublicKey):
        key.verify(signature, signed_bytes, ec.ECDSA(chashes.SHA256()))
        return "ES256"
    if isinstance(key, ed25519.Ed25519PublicKey):
        key.verify(signature, signed_bytes)
        return "EdDSA"
    if isinstance(key, rsa.RSAPublicKey):
        key.verify(signature, signed_bytes, padding.PKCS1v15(), chashes.SHA256())
        return "RS256"
    raise ValueError(f"未知公钥类型 {type(key).__name__}")


class Handler(BaseHTTPRequestHandler):
    server_version = "KeePasskeyPhishLab/1.0"

    # ── 基础工具 ────────────────────────────────────────────────────────────────────────
    def log_message(self, fmt: str, *args) -> None:  # 静音默认访问日志，读数一律走 JSONL
        pass

    def _send(self, body: bytes, ctype: str = "text/html; charset=utf-8", code: int = 200) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, code: int = 200) -> None:
        self._send(json.dumps(payload).encode(), "application/json", code)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or "0")
        return json.loads(self.rfile.read(length) or b"{}")

    # ── 页面 ───────────────────────────────────────────────────────────────────────────
    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 命名约定
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self._send(INDEX_HTML.encode())
        elif parsed.path.startswith("/case/"):
            # 路径式用例：/case/<action>/<rp>?allow=a,b
            # 为什么不用 ?action=..&rp=..：`&` 要穿过 adb shell 的两层引号才不被截断，
            # 实测会把 `\&` 原样带进 URL ⇒ 页面根本不发请求。少一个可错项就少一类假读数。
            self._send(CASE_HTML.encode())
        elif parsed.path == "/status":
            with _state_lock:
                snapshot = {
                    "credentials": {k: {kk: vv for kk, vv in v.items() if kk != "pem"}
                                    for k, v in _credentials.items()},
                    "readings": _readings,
                }
            self._json(snapshot)
        else:
            self._send(b"not found", code=404)

    # ── WebAuthn 端点 ──────────────────────────────────────────────────────────────────
    def do_POST(self) -> None:  # noqa: N802
        try:
            {"api/create/options": self._create_options,
             "api/create/verify": self._create_verify,
             "api/get/options": self._get_options,
             "api/get/verify": self._get_verify,
             "report": self._report}[urlparse(self.path).path.lstrip("/")]()
        except Exception as exc:                                  # fail-closed：异常一律如实回
            traceback.print_exc()
            self._json({"ok": False, "detail": f"{type(exc).__name__}: {exc}"}, 500)

    def _new_session(self, rp: str, action: str) -> tuple[str, bytes]:
        session = b64url(secrets.token_bytes(16))
        challenge = secrets.token_bytes(32)
        with _state_lock:
            _pending[session] = {"challenge": challenge, "rp": rp, "action": action,
                                 "issuedAt": now_ms()}
        return session, challenge

    def _take(self, session: str, rp: str, action: str) -> bytes:
        with _state_lock:
            entry = _pending.get(session)
        if not entry:
            raise ValueError("会话不存在（重复提交或已过期）")
        if now_ms() - entry["issuedAt"] > CHALLENGE_TTL_SECONDS * 1000:
            raise ValueError("会话超时")
        if entry["rp"] != rp or entry["action"] != action:
            raise ValueError(f"会话申报与提交不一致：session rp={entry['rp']} action={entry['action']}")
        return entry["challenge"]

    def _origin(self) -> str:
        return f"https://{self.headers.get('Host', '')}"

    def _create_options(self) -> None:
        body = self._body()
        rp = body["rp"]
        session, challenge = self._new_session(rp, "create")
        self._json({
            "ok": True,
            "publicKey": {
                "challenge": b64url(challenge),
                "rp": {"id": rp, "name": f"PhishLab {rp}"},
                "user": {"id": b64url(secrets.token_bytes(12)),
                         "name": body.get("user") or "probe-user",
                         "displayName": body.get("user") or "Probe User"},
                "pubKeyCredParams": [{"type": "public-key", "alg": -7},
                                     {"type": "public-key", "alg": -8},
                                     {"type": "public-key", "alg": -257}],
                "timeout": 120_000,
                "authenticatorSelection": {"residentKey": "required", "userVerification": "preferred"},
                "attestation": "none",
            },
            "session": session,
        })

    def _create_verify(self) -> None:
        body = self._body()
        rp = body["rp"]
        challenge = self._take(body["session"], rp, "create")
        credential = body["credential"]
        client = json.loads(unb64url(credential["clientDataJSON"]))
        att = minicbor.decode_whole(unb64url(credential["response"]["attestationObject"]))
        # 注册半环的 authData 在 attestationObject 里；fmt 不验链（实验室自证，声明见 README）
        auth_parsed = minicbor.parse_auth_data(att["authData"], rp)
        pem, alg = minicbor.cose_to_pem(auth_parsed["cose"])
        key = f"{rp}|{b64url(auth_parsed['credentialId'])}"
        reading = {
            "at": "create", "host": self.headers.get("Host"), "rp": rp,
            "origin": client.get("origin"), "challengeMatches": client.get("challenge") == b64url(challenge),
            "type": client.get("type"), "rpIdHashMatches": auth_parsed["rpIdHash"] == auth_parsed["expectedRpIdHash"],
            "up": auth_parsed["up"], "uv": auth_parsed["uv"], "fmt": att.get("fmt"),
            "credentialId": b64url(auth_parsed["credentialId"]), "alg": alg,
            "signCount": auth_parsed["signCount"],
        }
        ok = reading["challengeMatches"] and reading["rpIdHashMatches"] and client.get("type") == "webauthn.create"
        with _state_lock:
            _credentials[key] = {"pem": pem, "signCount": auth_parsed["signCount"],
                                 "source": "registered-in-lab", "rp": rp}
            _readings.append({**reading, "ok": ok, "ts": now_ms()})
        self._json({"ok": ok, "detail": reading})

    def _get_options(self) -> None:
        body = self._body()
        rp = body["rp"]
        session, challenge = self._new_session(rp, "get")
        options: dict = {
            "challenge": b64url(challenge),
            "rpId": rp,
            "timeout": 120_000,
            "userVerification": "preferred",
        }
        allow = body.get("allow")            # 「有 allowCredentials」与「可发现凭据」两条都要跑
        if allow:
            options["allowCredentials"] = [
                {"type": "public-key", "id": unb64url(item)} for item in allow
            ]
        self._json({"ok": True, "publicKey": options, "session": session})

    def _get_verify(self) -> None:
        body = self._body()
        rp = body["rp"]
        challenge = self._take(body["session"], rp, "get")
        assertion = body["assertion"]
        client = json.loads(unb64url(assertion["clientDataJSON"]))
        auth = unb64url(assertion["response"]["authenticatorData"])
        signature = unb64url(assertion["response"]["signature"])
        auth_parsed = minicbor.parse_auth_data(auth, rp)
        cred_id = b64url(auth_parsed.get("credentialId") or unb64url(assertion["rawId"]))
        stored = None
        with _state_lock:
            stored = dict(_credentials.get(f"{rp}|{cred_id}") or {})
            if not stored:                                   # 导入件：公钥由 expected_keys.json 预置
                stored = dict(_credentials.get(f"expected|{cred_id}") or {})
        detail = {
            "at": "get", "host": self.headers.get("Host"), "rp": rp, "origin": client.get("origin"),
            "challengeMatches": client.get("challenge") == b64url(challenge),
            "type": client.get("type"),
            "rpIdHashMatches": auth_parsed["rpIdHash"] == auth_parsed["expectedRpIdHash"],
            "up": auth_parsed["up"], "uv": auth_parsed["uv"],
            "credentialId": cred_id,
            "signCountStored": stored.get("signCount"), "signCountNew": auth_parsed["signCount"],
            "source": stored.get("source"),
        }
        if not stored.get("pem"):
            detail["signatureVerified"] = "no-stored-key"
            ok = False
        else:
            try:
                detail["alg"] = verify_signature(
                    stored["pem"], stored.get("alg", ""), signature,
                    auth + hashlib.sha256(unb64url(assertion["clientDataJSON"])).digest()
                )
                detail["signatureVerified"] = True
            except Exception as exc:
                detail["signatureVerified"] = f"False:{type(exc).__name__}"
                detail["error"] = str(exc)[:160]
            ok = bool(detail["signatureVerified"]) is True
        # 未决 6 的读数：新计数 ≤ 存量 ⇒ 真实 RP 会判克隆嫌疑
        counter_floor = stored.get("signCount")
        detail["cloneSuspicion"] = (
            counter_floor is not None and auth_parsed["signCount"] <= counter_floor
        )
        ok = ok and detail["challengeMatches"] and detail["rpIdHashMatches"] \
            and client.get("type") == "webauthn.get" and not detail["cloneSuspicion"]
        with _state_lock:
            for lookup in (f"{rp}|{cred_id}", f"expected|{cred_id}"):
                if lookup in _credentials:
                    _credentials[lookup]["signCount"] = max(
                        _credentials[lookup].get("signCount") or 0, auth_parsed["signCount"])
            _readings.append({**detail, "ok": ok, "ts": now_ms()})
        self._json({"ok": ok, "detail": detail})

    def _report(self) -> None:
        """浏览器侧结局（含「抛异常」这种 provider 侧看不到的形态）。"""
        body = self._body()
        entry = {
            "at": "browser", "phase": body.get("phase"), "host": self.headers.get("Host"),
            "rp": body.get("rp"), "outcome": body.get("outcome"),
            "errorName": body.get("errorName"), "detail": str(body.get("detail"))[:200],
            "ms": body.get("ms"), "ok": body.get("outcome") == "success", "ts": now_ms(),
        }
        with _state_lock:
            _readings.append(entry)
        line = json.dumps(entry, ensure_ascii=False)
        print("READ " + line, flush=True)
        self._json({"ok": True})

def load_expected_keys(path: Path) -> int:
    """预置「导出方已知公钥」的凭据（导入件的验签前提：RP 没见过它注册）。"""
    data = json.loads(path.read_text(encoding="utf-8"))
    count = 0
    with _state_lock:
        for item in data:
            _credentials[f"expected|{item['credentialId']}"] = {
                "pem": item["publicKeyPem"], "signCount": None,
                "source": f"imported-expected rp={item['rpId']}", "rp": item["rpId"],
            }
            count += 1
    return count


INDEX_HTML = """<!doctype html><meta charset=utf-8>
<title>Phish Lab</title><h1>仿冒域唤醒实验室</h1>
<p>本页只服务 <code>rp.testlab.xyz</code> 系列的用例；每个用例都由 <code>/case</code> 自跑。</p>
<p>读数：<a href=/status>/status</a></p>
"""

CASE_HTML = """<!doctype html><meta charset=utf-8>
<title>PhishLab case</title><pre id=out>running…</pre>
<script>
const q = new URLSearchParams(location.search);
const m = location.pathname.match(/^\\/case\\/(create|get)\\/(.+?)\\/?$/);
const action = m ? m[1] : (q.get('action') || 'get');
const rp = m ? decodeURIComponent(m[2]) : (q.get('rp') || location.hostname);
   // 页面可申报**与 origin 不同的** rpId（这正是「Chrome 放行后本应用出不出」的用例入口）
const sessionKey = 's:' + action + ':' + rp;
const t0 = performance.now();
function b64u(buf) {
  return btoa(String.fromCharCode.apply(null, new Uint8Array(buf)))
    .replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
}
function unb64u(text) { return Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0)); }
function plain(x) {
  if (x instanceof ArrayBuffer) return b64u(x);
  if (ArrayBuffer.isView(x)) return b64u(x.buffer.slice(x.byteOffset, x.byteOffset + x.byteLength));
  if (x instanceof Uint8Array) return b64u(x);
  return x;
}
function jwk(key) { return key; }
async function post(path, body) {
  const r = await fetch(path, {method: 'POST', headers: {'content-type': 'application/json'},
                               body: JSON.stringify(body)});
  return r.json();
}
async function report(outcome, detail, errorName) {
  try {
    await post('/report', {phase: action, rp, outcome, errorName,
                           detail: typeof detail === 'string' ? detail : JSON.stringify(detail).slice(0, 300),
                           ms: Math.round(performance.now() - t0)});
  } catch (e) { /* 上报失败不改写结论，控制台仍留痕 */ console.error('report failed', e); }
}
function encodeCredential(cred) {
  return {
    id: cred.id, rawId: plain(cred.rawId), type: cred.type,
    response: {
      clientDataJSON: plain(cred.response.clientDataJSON),
      authenticatorData: cred.response.authenticatorData ? plain(cred.response.authenticatorData) : undefined,
      signature: cred.response.signature ? plain(cred.response.signature) : undefined,
      userHandle: cred.response.userHandle ? plain(cred.response.userHandle) : undefined,
      attestationObject: cred.response.attestationObject ? plain(cred.response.attestationObject) : undefined,
    },
  };
}
(async () => {
  const out = document.getElementById('out');
  out.textContent = `origin=${location.origin}\\nrp=${rp}\\naction=${action}\\n` +
                    `secureContext=${window.isSecureContext} hasCreds=${!!navigator.credentials}\\n`;
  try {
    if (!navigator.credentials) throw new Error('navigator.credentials 不可用（非安全上下文？）');
    if (action === 'create') {
      const opt = await post('/api/create/options', {rp, user: q.get('user') || undefined});
      const pk = opt.publicKey;
      pk.challenge = unb64u(pk.challenge);
      pk.user.id = unb64u(pk.user.id);
      const cred = await navigator.credentials.create({publicKey: pk});
      sessionStorage.setItem(sessionKey, opt.session);
      const verified = await post('/api/create/verify',
          {rp, session: opt.session, credential: encodeCredential(cred)});
      out.textContent += 'created ' + JSON.stringify(verified).slice(0, 700);
      await report('success', verified.detail, null);
    } else {
      const opt = await post('/api/get/options', {rp, allow: (q.get('allow') || '').split(',').filter(Boolean)});
      const pk = opt.publicKey;
      pk.challenge = unb64u(pk.challenge);
      if (pk.allowCredentials) pk.allowCredentials.forEach(c => c.id = unb64u(c.id));
      const assertion = await navigator.credentials.get({publicKey: pk});
      if (!assertion) { await report('empty', 'navigator.credentials.get 返回 null', null);
                        out.textContent += 'get() => null（无候选）'; return; }
      const verified = await post('/api/get/verify',
          {rp, session: sessionStorage.getItem(sessionKey), assertion: encodeCredential(assertion)});
      out.textContent += 'got ' + JSON.stringify(verified).slice(0, 700);
      await report(verified.ok ? 'success' : 'verify-failed', verified.detail, null);
    }
  } catch (e) {
    out.textContent += 'ERROR ' + (e && e.name) + ': ' + (e && e.message);
    await report('threw', e && e.message, e && e.name);
  }
})();
</script>
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--expected-keys", type=Path,
                        help="导入件公钥清单 JSON（由 make_import_qr.py 产出）")
    parser.add_argument("--dump-readings", type=Path, help="退出前把读数写到该文件")
    args = parser.parse_args()

    if args.expected_keys and args.expected_keys.exists():
        print(f"预置导入件公钥 {load_expected_keys(args.expected_keys)} 条")
    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT_DIR / "server.pem", CERT_DIR / "server.key")
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"HTTPS RP 监听 0.0.0.0:{args.port}（模拟器侧地址 https://10.0.2.2:{args.port}/）")
    try:
        httpd.serve_forever()
    finally:
        if args.dump_readings:
            with _state_lock:
                args.dump_readings.write_text("\n".join(json.dumps(r, ensure_ascii=False)
                                                        for r in _readings) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
