#!/usr/bin/env python3
"""Writes app/src/test/resources/licensing/vectors-v1.json from the factory's real issuer (packages/af-license).

Usage: python3 tools/gen_code_vectors.py /path/to/Apps-Factory
The signing key is generated fresh for each run, used once and thrown away: it is never written anywhere. The file keeps the PUBLIC
key, the codes, and what the factory's own reader answers for each (that answer is the authority the Kotlin reader is tested against).
Needs the `cryptography` package. Synthetic inputs only: no real customer, device or production key.
"""
import base64, json, sys
from datetime import date
from pathlib import Path

root = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root / 'packages' / 'af-license'))
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402
from af_license import codes  # noqa: E402

def pair():
    k = Ed25519PrivateKey.generate()
    pem = k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    raw = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return pem, base64.urlsafe_b64encode(raw).decode().rstrip('=')

pem, pub = pair()
_, other_pub = pair()
DEVICE, OTHER_DEVICE = '7KD2M-QX9TP', 'ABCDE-FGHJK'
issued = [
    ('trial_bound', dict(product_id='al-store', edition='trial', first_day=date(2026, 10, 1), days=14, device=DEVICE, issued=date(2026, 10, 1), serial=bytes.fromhex('01020304'))),
    ('standard_grace', dict(product_id='al-store', edition='standard', first_day=date(2026, 10, 1), days=30, grace_days=3, device=None, seats=2, issued=date(2026, 10, 1), serial=bytes.fromhex('0A0B0C0D'))),
    ('perpetual_bound', dict(product_id='al-store', edition='perpetual', first_day=date(2026, 10, 1), days=1, device=DEVICE, issued=date(2026, 10, 1), serial=bytes.fromhex('FFEEDDCC'))),
]
out = {'note': 'Produced by tools/gen_code_vectors.py with packages/af-license. Public key only.', 'publicKey': pub, 'otherPublicKey': other_pub, 'cases': []}
days = [date(2026, 9, 30), date(2026, 10, 1), date(2026, 10, 14), date(2026, 10, 15), date(2026, 10, 30), date(2026, 10, 31), date(2026, 11, 2), date(2026, 11, 3), date(2030, 1, 1)]

def read(code, product, device, key, today):
    r = codes.read_code(code, [key], product, device, today)
    return {'valid': r.valid, 'state': r.state, 'reason': r.reason, 'terms': {k: v for k, v in r.terms.items()}}

for name, kw in issued:
    res = codes.issue_code(pem, **kw)
    code = res['code']
    checks = []
    for d in days:
        for label, product, device, key in [('ok', 'al-store', DEVICE, pub), ('wrong_product', 'other-app', DEVICE, pub), ('wrong_device', 'al-store', OTHER_DEVICE, pub),
                                            ('no_device', 'al-store', None, pub), ('wrong_key', 'al-store', DEVICE, other_pub)]:
            checks.append({'label': label, 'product': product, 'device': device, 'key': 'pub' if key == pub else 'other', 'today': d.isoformat(), 'expect': read(code, product, device, key, d)})
    out['cases'].append({'name': name, 'code': code, 'issued': res, 'checks': checks})
# a code with one character changed, one with lowercase/spaces/misread letters, and junk
good = out['cases'][0]['code']
flip = good[:10] + ('0' if good[10] != '0' else '1') + good[11:]
out['mutations'] = [
    {'label': 'flipped_character', 'text': flip, 'expect': read(flip, 'al-store', DEVICE, pub, date(2026, 10, 2))},
    {'label': 'sloppy_typing', 'text': good.lower().replace('0', 'o').replace('1', 'l').replace('-', ' '), 'expect': read(good.lower().replace('0', 'o').replace('1', 'l').replace('-', ' '), 'al-store', DEVICE, pub, date(2026, 10, 2))},
    {'label': 'too_short', 'text': good[:-7], 'expect': read(good[:-7], 'al-store', DEVICE, pub, date(2026, 10, 2))},
    {'label': 'bad_character', 'text': good[:5] + 'U' + good[6:], 'expect': read(good[:5] + 'U' + good[6:], 'al-store', DEVICE, pub, date(2026, 10, 2))},
    {'label': 'empty', 'text': '', 'expect': read('', 'al-store', DEVICE, pub, date(2026, 10, 2))},
]
dest = Path(__file__).resolve().parents[1] / 'app/src/test/resources/licensing/vectors-v1.json'
dest.write_text(json.dumps(out, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
print('wrote', dest, len(out['cases']), 'cases')
