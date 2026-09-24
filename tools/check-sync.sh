#!/usr/bin/env bash
# The engine lives in three places by necessity: the app target, the SwiftPM test
# package, and (as JSON) the site plus the app bundle. They must never drift. This
# script fails if any copy differs from its source of truth, so CI catches drift
# before it can make a finding differ between platforms.
set -eu

fail=0
check() {
  if ! diff -q "$1" "$2" >/dev/null 2>&1; then
    echo "DRIFT: $2 differs from $1"
    fail=1
  fi
}

# App engine sources are the source of truth; the package must match them.
check ios/Soor/Soor/Engine/SoorEngine.swift  ios/SoorEngine/Sources/SoorEngine/SoorEngine.swift
check ios/Soor/Soor/Engine/Knowledge.swift   ios/SoorEngine/Sources/SoorEngine/Knowledge.swift

# The JSON knowledge base in docs/data is the source of truth; app bundle and
# package test fixtures must match it.
check docs/data/services.json  ios/Soor/Soor/Resources/services.json
check docs/data/cameras.json   ios/Soor/Soor/Resources/cameras.json
check docs/data/services.json  ios/SoorEngine/Tests/SoorEngineTests/services.json
check docs/data/cameras.json   ios/SoorEngine/Tests/SoorEngineTests/cameras.json

# The shared vectors are the source of truth for the package test fixture.
check tests/vectors.json  ios/SoorEngine/Tests/SoorEngineTests/vectors.json

if [ "$fail" -eq 0 ]; then
  echo "engine and knowledge base are in sync across app, package, and site"
fi
exit $fail
