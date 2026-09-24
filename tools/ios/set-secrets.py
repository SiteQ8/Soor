#!/usr/bin/env python3
"""
Set the iOS release secrets on SiteQ8/Soor from your local signing files.

It base64-encodes the certificate and profile, then writes the six repo secrets
the ios-release workflow reads. Values are sent to GitHub encrypted (libsodium
sealed box) and are never printed.

Usage:
  export GH_TOKEN=...            # a PAT with repo scope
  python3 tools/ios/set-secrets.py \
      --p12 sunnati.p12 --p12-pass 'THE_PASSWORD' \
      --profile SoorProfile.mobileprovision \
      --asc-key-id 4FRR4NVMDY \
      --asc-issuer-id 69a6de7c-8afa-47e3-e053-5b8c7c11a4d1 \
      --asc-key-p8 AuthKey_4FRR4NVMDY.p8

Only the flags you pass are set, so you can update one secret at a time.
Requires PyNaCl (pip install pynacl --break-system-packages).
"""
import argparse, base64, json, os, sys, urllib.request

REPO = "SiteQ8/Soor"

def gh(path, method="GET", body=None):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("set GH_TOKEN to a PAT with repo scope")
    url = f"https://api.github.com{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req) as r:
        return json.load(r) if r.length != 0 else {}

def public_key():
    return gh(f"/repos/{REPO}/actions/secrets/public-key")

def put_secret(name, value, pk):
    try:
        from nacl import public, encoding
    except ImportError:
        sys.exit("pip install pynacl --break-system-packages")
    sealed = public.SealedBox(public.PublicKey(pk["key"].encode(), encoding.Base64Encoder))
    enc = base64.b64encode(sealed.encrypt(value.encode())).decode()
    gh(f"/repos/{REPO}/actions/secrets/{name}", method="PUT",
       body={"encrypted_value": enc, "key_id": pk["key_id"]})
    print(f"set {name}")

def b64_file(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--p12")
    ap.add_argument("--p12-pass")
    ap.add_argument("--profile")
    ap.add_argument("--asc-key-id")
    ap.add_argument("--asc-issuer-id")
    ap.add_argument("--asc-key-p8")
    a = ap.parse_args()

    pk = public_key()
    if a.p12:          put_secret("IOS_CERT_P12", b64_file(a.p12), pk)
    if a.p12_pass:     put_secret("IOS_CERT_PASSWORD", a.p12_pass, pk)
    if a.profile:      put_secret("IOS_PROFILE", b64_file(a.profile), pk)
    if a.asc_key_id:   put_secret("ASC_KEY_ID", a.asc_key_id, pk)
    if a.asc_issuer_id:put_secret("ASC_ISSUER_ID", a.asc_issuer_id, pk)
    if a.asc_key_p8:
        with open(a.asc_key_p8) as f:
            put_secret("ASC_KEY_P8", f.read(), pk)
    print("done")

if __name__ == "__main__":
    main()
