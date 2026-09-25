#!/usr/bin/env python3
"""يملأ صفحة سُور في Google Play من مجلد play/ بكل لغاته، نصوصًا وصورًا.

    PLAY_SERVICE_ACCOUNT='<json>' python3 tools/android/play-listing.py \\
        [--keep-title] [--notes-track alpha]

لكل مجلد لغة في play/listings يضع العنوان والوصفين، ثم يرفع الأيقونة والصورة
الترويجية ولقطات الجوال لتلك اللغة بعد أن يمسح القديم منها، وإن أُعطي مسار
أضاف ملاحظات الإصدار من play/whatsnew إلى إصداره الحالي دون أن يغيّر حالته،
ثم يعتمد التحرير كله مرة واحدة، فإن فشل شيء لم يتغيّر شيء في المتجر.

--keep-title يُبقي العنوان الذي في المتجر كما هو إن كان موجودًا، لأن صاحب
التطبيق قد يختاره بنفسه في Play Console.
"""

import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

# the same token, calls and package as play-upload.py, so both scripts speak to
# Play in exactly one way
_spec = importlib.util.spec_from_file_location('play_upload', HERE / 'play-upload.py')
pu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pu)

IMAGES = {
    'icon': lambda loc: [ROOT / 'play/graphics/icon-512.png'],
    'featureGraphic': lambda loc: [ROOT / 'play/graphics/feature-1024x500.png'],
    'phoneScreenshots': lambda loc: sorted((ROOT / 'play/graphics/phone' / loc).glob('*.png')),
}


def text(loc, name):
    return (ROOT / 'play/listings' / loc / name).read_text(encoding='utf-8').strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--package', default=pu.PACKAGE)
    ap.add_argument('--keep-title', action='store_true')
    ap.add_argument('--notes-track')
    a = ap.parse_args()

    raw = os.environ.get('PLAY_SERVICE_ACCOUNT')
    if not raw:
        sys.exit('PLAY_SERVICE_ACCOUNT غير موجود في البيئة')
    tok = pu.token(json.loads(raw))
    api, up, pkg = pu.API, pu.UPLOAD, a.package

    edit = pu.call('POST', f'{api}/{pkg}/edits', tok, {})['id']
    print('تحرير', edit)
    try:
        existing = {l['language']: l for l in pu.call('GET', f'{api}/{pkg}/edits/{edit}/listings', tok).get('listings', [])}
        locales = sorted(p.name for p in (ROOT / 'play/listings').iterdir() if p.is_dir())

        for loc in locales:
            title = text(loc, 'title.txt')
            if a.keep_title and existing.get(loc, {}).get('title'):
                title = existing[loc]['title']
            body = {'language': loc, 'title': title,
                    'shortDescription': text(loc, 'short_description.txt'),
                    'fullDescription': text(loc, 'full_description.txt')}
            for f, lim in (('title', 30), ('shortDescription', 80), ('fullDescription', 4000)):
                if len(body[f]) > lim:
                    sys.exit(f'{loc} {f} أطول من {lim} حرفًا')
            pu.call('PUT', f'{api}/{pkg}/edits/{edit}/listings/{loc}', tok, body)
            print(loc, 'النصوص | العنوان:', title)

            for kind, files in IMAGES.items():
                paths = files(loc)
                if not paths:
                    continue
                pu.call('DELETE', f'{api}/{pkg}/edits/{edit}/listings/{loc}/{kind}', tok)
                for p in paths:
                    pu.call('POST', f'{up}/{pkg}/edits/{edit}/listings/{loc}/{kind}?uploadType=media',
                            tok, raw=p.read_bytes(), ctype='image/png')
                print('   ', kind, len(paths))

        if a.notes_track:
            track = pu.call('GET', f'{api}/{pkg}/edits/{edit}/tracks/{a.notes_track}', tok)
            notes = pu.release_notes(ROOT / 'play/whatsnew')
            notes = [n for n in notes if n['language'] in locales]
            releases = track.get('releases', [])
            if releases:
                for r in releases:
                    r['releaseNotes'] = notes
                pu.call('PUT', f'{api}/{pkg}/edits/{edit}/tracks/{a.notes_track}', tok,
                        {'track': a.notes_track, 'releases': releases})
                print('ملاحظات الإصدار في', a.notes_track, '|', len(notes), 'لغة | الحالة باقية:',
                      ', '.join(r.get('status', '') for r in releases))

        pu.call('POST', f'{api}/{pkg}/edits/{edit}:commit', tok, {})
        print('اعتُمد التحرير')
    except Exception:
        try:
            pu.call('DELETE', f'{api}/{pkg}/edits/{edit}', tok)
            print('أُلغي التحرير، ولم يتغيّر شيء في المتجر')
        except Exception:
            pass
        raise


if __name__ == '__main__':
    main()
