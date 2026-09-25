#!/usr/bin/env python3
"""يرفع حزمة aab إلى مسار اختبار في Google Play.

كُتب هنا ولم يُؤخذ إجراءً جاهزاً من طرف ثالث: الرفع يستلزم تسليم مفتاح حساب
الخدمة والحزمة الموقَّعة معاً، وذلك أوسع ثقة مما يستحقه إجراء لا نقرأ شفرته
ولا نثبّت نسخته. وليس فيه إلا نداءات الواجهة نفسها.

    PLAY_SERVICE_ACCOUNT='<json>' python3 tools/android/play-upload.py \\
        --aab app-release.aab --track alpha [--notes play/whatsnew]

يفتح تحريراً، ويرفع الحزمة، ويضعها في المسار مع ملاحظات الإصدار إن وُجدت،
ثم يعتمد التحرير. وأيّ خطأ يترك التحرير بلا اعتماد فلا يتغيّر شيء في المتجر.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications'
UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications'
PACKAGE = 'com.eworldq8.soor'


def token(sa):
    """رمز وصول من حساب الخدمة، موقّع بـRS256 بلا مكتبة خارجية غير cryptography."""
    import base64
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    def b64(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b'=')

    now = int(time.time())
    claim = {'iss': sa['client_email'], 'scope': 'https://www.googleapis.com/auth/androidpublisher',
             'aud': sa['token_uri'], 'iat': now, 'exp': now + 3600}
    head = b64(json.dumps({'alg': 'RS256', 'typ': 'JWT'}).encode())
    body = b64(json.dumps(claim).encode())
    key = serialization.load_pem_private_key(sa['private_key'].encode(), password=None)
    sig = b64(key.sign(head + b'.' + body, padding.PKCS1v15(), hashes.SHA256()))
    assertion = (head + b'.' + body + b'.' + sig).decode()

    data = urllib.parse.urlencode({
        'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        'assertion': assertion}).encode()
    with urllib.request.urlopen(urllib.request.Request(sa['token_uri'], data=data)) as r:
        return json.load(r)['access_token']


def call(method, url, tok, body=None, raw=None, ctype='application/json'):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, method=method, data=data,
                                 headers={'Authorization': 'Bearer ' + tok, 'Content-Type': ctype})
    try:
        with urllib.request.urlopen(req) as r:
            out = r.read()
            return json.loads(out) if out else {}
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'{method} {url.split("/v3/")[-1][:70]} -> {e.code}: {e.read().decode()[:400]}')


def release_notes(folder):
    """ملاحظات الإصدار، ملفّ لكل لغة باسمها كما تسمّيها Google."""
    out = []
    if not folder:
        return out
    for f in sorted(Path(folder).glob('*')):
        if f.is_file():
            text = f.read_text(encoding='utf-8').strip()
            if text:
                out.append({'language': f.name, 'text': text[:500]})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--aab', required=True)
    ap.add_argument('--track', default='internal')
    ap.add_argument('--notes')
    ap.add_argument('--package', default=PACKAGE)
    a = ap.parse_args()

    raw = os.environ.get('PLAY_SERVICE_ACCOUNT')
    if not raw:
        sys.exit('PLAY_SERVICE_ACCOUNT غير موجود في البيئة')
    sa = json.loads(raw)
    aab = Path(a.aab)
    if not aab.exists():
        sys.exit('لا حزمة عند ' + str(aab))

    tok = token(sa)
    print('حساب الخدمة:', sa['client_email'])

    edit = call('POST', f'{API}/{a.package}/edits', tok, {})['id']
    print('تحرير', edit)
    try:
        up = call('POST', f'{UPLOAD}/{a.package}/edits/{edit}/bundles?uploadType=media', tok,
                  raw=aab.read_bytes(), ctype='application/octet-stream')
        code = up['versionCode']
        print('رُفعت الحزمة، رمزها', code, '|', round(aab.stat().st_size / 1048576, 1), 'ميغابايت')

        notes = release_notes(a.notes)
        call('PATCH', f'{API}/{a.package}/edits/{edit}/tracks/{a.track}', tok,
             {'track': a.track, 'releases': [{'versionCodes': [str(code)], 'status': 'completed',
                                              'releaseNotes': notes}]})
        print('وُضعت في مسار', a.track, '|', len(notes), 'لغة من ملاحظات الإصدار')

        call('POST', f'{API}/{a.package}/edits/{edit}:commit', tok, {})
        print('اعتُمد التحرير، والحزمة عند المختبرين')
    except Exception:
        # تحرير بلا اعتماد لا يغيّر شيئا، لكن تركه معلّقا يمنع التحرير التالي
        try:
            call('DELETE', f'{API}/{a.package}/edits/{edit}', tok)
            print('أُلغي التحرير، ولم يتغيّر شيء في المتجر')
        except Exception:
            pass
        raise


if __name__ == '__main__':
    main()
