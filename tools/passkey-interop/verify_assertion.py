#!/usr/bin/env python3
"""独立 RP 断言验收：只接受 CM 客户端原始响应与事先登记的公钥/请求清单。

python tools/passkey-interop/verify_assertion.py --manifest <expected.json> --response <response.json>
python tools/passkey-interop/verify_assertion.py --selftest

清单必含 credentialId/challenge/origin/rpId/publicKeyBase64/signCount/uv/userHandle。
可选 forbiddenPublicKeyBase64（另一库公钥，必须验签失败）、clientDataJSON（浏览器原始字节）。
publicKeyBase64 为 P-256 未压缩点；不导入任何本仓签发/解析代码。
退出码 0=全部判据通过，1=判据失败，2=环境/输入缺失。自测不是设备证据。
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import pathlib
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def decode(value: str) -> bytes:
    return base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def verify(expected: dict, assertion: dict) -> dict:
    def require(condition: bool, name: str) -> None:
        if not condition:
            raise ValueError(name)

    require(assertion['type'] == 'public-key', 'credential type')
    require(decode(assertion['id']) == decode(expected['credentialId']), 'credential id')
    require(decode(assertion['rawId']) == decode(expected['credentialId']), 'raw credential id')
    response = assertion['response']
    client_bytes = decode(response['clientDataJSON'])
    if 'clientDataJSON' in expected:
        client_bytes = expected['clientDataJSON'].encode('utf-8')
    client = json.loads(client_bytes)
    require(client['type'] == 'webauthn.get', 'clientData type')
    require(decode(client['challenge']) == decode(expected['challenge']), 'challenge')
    require(client['origin'] == expected['origin'], 'origin')
    require(client.get('crossOrigin', False) is False, 'crossOrigin')
    auth = decode(response['authenticatorData'])
    require(len(auth) >= 37, 'authenticatorData length')
    require(auth[:32] == hashlib.sha256(expected['rpId'].encode()).digest(), 'rpIdHash')
    require(bool(auth[32] & 1), 'UP')
    require(bool(auth[32] & 4) == expected['uv'], 'UV')
    require(not (auth[32] & 0x40), 'assertion AT flag')
    count = int.from_bytes(auth[33:37], 'big')
    require(count == expected['signCount'], 'signCount')
    require(decode(response['userHandle']) == decode(expected['userHandle']), 'userHandle')
    signed = auth + hashlib.sha256(client_bytes).digest()
    signature = decode(response['signature'])
    key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), decode(expected['publicKeyBase64']))
    key.verify(signature, signed, ec.ECDSA(hashes.SHA256()))
    if 'forbiddenPublicKeyBase64' in expected:
        other = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), decode(expected['forbiddenPublicKeyBase64']))
        try:
            other.verify(signature, signed, ec.ECDSA(hashes.SHA256()))
        except InvalidSignature:
            pass
        else:
            raise ValueError('另一库公钥不得验签成功')
    return {'verified': True, 'signCount': count, 'up': True, 'uv': bool(auth[32] & 4),
            'responseSha256': hashlib.sha256(json.dumps(assertion, sort_keys=True).encode()).hexdigest()}


def selftest() -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    other = ec.generate_private_key(ec.SECP256R1())
    public = lambda item: base64.b64encode(item.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)).decode()
    expected = dict(credentialId='AQID', challenge='BAUG', origin='https://login.example.com',
                    rpId='example.com', publicKeyBase64=public(key), forbiddenPublicKeyBase64=public(other),
                    signCount=42, uv=False, userHandle='BwgJ')
    client = json.dumps(dict(type='webauthn.get', challenge='BAUG', origin=expected['origin'], crossOrigin=False)).encode()
    auth = hashlib.sha256(b'example.com').digest() + b'\x01' + (42).to_bytes(4, 'big')
    signature = key.sign(auth + hashlib.sha256(client).digest(), ec.ECDSA(hashes.SHA256()))
    assertion = dict(id='AQID', rawId='AQID', type='public-key', response=dict(
        clientDataJSON=encode(client), authenticatorData=encode(auth), signature=encode(signature), userHandle='BwgJ'))
    verify(expected, assertion)
    checks = 1
    for field, value in [('credentialId', 'AAAA'), ('challenge', 'AAAA'), ('origin', 'https://evil.example'),
                         ('rpId', 'login.example.com'), ('signCount', 41), ('uv', True),
                         ('userHandle', 'AAAA'), ('publicKeyBase64', public(other)),
                         ('forbiddenPublicKeyBase64', public(key))]:
        altered = dict(expected, **{field: value})
        try:
            verify(altered, assertion)
        except (ValueError, InvalidSignature):
            checks += 1
        else:
            raise AssertionError(f'negative accepted: {field}')
    for flags in [0, 0x41]:
        altered = copy.deepcopy(assertion)
        altered['response']['authenticatorData'] = encode(auth[:32] + bytes([flags]) + auth[33:])
        try:
            verify(expected, altered)
        except (ValueError, InvalidSignature):
            checks += 1
        else:
            raise AssertionError('negative flags accepted')
    print(json.dumps({'selftest': 'PASS', 'checks': checks, 'deviceEvidence': False}))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=pathlib.Path)
    parser.add_argument('--response', type=pathlib.Path)
    parser.add_argument('--selftest', action='store_true')
    args = parser.parse_args()
    try:
        if args.selftest:
            selftest()
        else:
            if not args.manifest or not args.response:
                parser.error('--manifest and --response are required')
            result = verify(json.loads(args.manifest.read_text(encoding='utf-8')),
                            json.loads(args.response.read_text(encoding='utf-8')))
            print(json.dumps(result, sort_keys=True))
        return 0
    except (OSError, KeyError, TypeError, ValueError, InvalidSignature) as error:
        print(f'RP verification FAILED: {type(error).__name__}: {error}', file=sys.stderr)
        return 2 if isinstance(error, OSError) else 1


if __name__ == '__main__':
    raise SystemExit(main())
