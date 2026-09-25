#!/usr/bin/env python3
"""يضع أسرار توقيع أندرويد ومفتاح Play في مستودع سُور، مشفّرةً كما تطلبها واجهة GitHub.

    GITHUB_TOKEN=... python3 tools/android/set-secrets.py \\
        --keystore soor-upload.keystore --keystore-password '...' \\
        --key-alias soor-upload [--key-password '...'] \\
        [--play-json service-account.json]

كل ملفّ يُقرأ ويُشفَّر ويُرسل، ولا يُكتب في أيّ مكان، والأسرار التي لم تُعطَ
تُترك كما هي في المستودع.
"""
import argparse, base64, json, os, sys, urllib.request

REPO = 'SiteQ8/Soor'
API = 'https://api.github.com'


def call(method, path, token, body=None):
    req = urllib.request.Request(API + path, method=method,
        headers={'Authorization': 'token ' + token, 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json'},
        data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def seal(public_key_b64, value):
    from nacl import encoding, public
    key = public.PublicKey(public_key_b64.encode('utf-8'), encoding.Base64Encoder())
    return base64.b64encode(public.SealedBox(key).encrypt(value.encode('utf-8'))).decode('utf-8')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--keystore')
    ap.add_argument('--keystore-password')
    ap.add_argument('--key-alias')
    ap.add_argument('--key-password')
    ap.add_argument('--play-json')
    a = ap.parse_args()

    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        sys.exit('GITHUB_TOKEN غير موجود في البيئة')

    secrets = {}
    if a.keystore:
        with open(a.keystore, 'rb') as f:
            secrets['ANDROID_KEYSTORE'] = base64.b64encode(f.read()).decode('ascii')
    if a.keystore_password:
        secrets['ANDROID_KEYSTORE_PASSWORD'] = a.keystore_password
        # one password for both, as keytool makes a PKCS12 store
        secrets['ANDROID_KEY_PASSWORD'] = a.key_password or a.keystore_password
    if a.key_alias:
        secrets['ANDROID_KEY_ALIAS'] = a.key_alias
    if a.play_json:
        with open(a.play_json, encoding='utf-8') as f:
            raw = f.read()
        sa = json.loads(raw)
        if 'client_email' not in sa or 'private_key' not in sa:
            sys.exit('هذا ليس ملفّ حساب خدمة من Google')
        secrets['PLAY_SERVICE_ACCOUNT'] = raw
        print('حساب الخدمة:', sa['client_email'])
    if not secrets:
        sys.exit('لم يُعطَ أيّ سرّ')

    pk = call('GET', f'/repos/{REPO}/actions/secrets/public-key', token)
    for name, value in secrets.items():
        call('PUT', f'/repos/{REPO}/actions/secrets/{name}', token,
             {'encrypted_value': seal(pk['key'], value), 'key_id': pk['key_id']})
        print('وُضع', name)

    have = {s['name'] for s in call('GET', f'/repos/{REPO}/actions/secrets', token).get('secrets', [])}
    need = ['ANDROID_KEYSTORE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']
    missing = [n for n in need if n not in have]
    print('التوقيع، الناقص:', ', '.join(missing) if missing else 'لا شيء، الورك فلو يوقّع')
    print('الرفع إلى Play:', 'جاهز' if 'PLAY_SERVICE_ACCOUNT' in have else 'ينقصه PLAY_SERVICE_ACCOUNT')


if __name__ == '__main__':
    main()
