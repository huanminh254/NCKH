#!/usr/bin/env bash
set -euo pipefail

APKS=(
  "citizen-app/build/outputs/apk/debug/citizen-app-debug.apk"
  "rescue-app/build/outputs/apk/debug/rescue-app-debug.apk"
)

TMP_DIR="$(mktemp -d)"
trap 'python3 -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$TMP_DIR"' EXIT

TOTAL_FAIL=0

for APK in "${APKS[@]}"; do
  if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found: $APK"
    exit 1
  fi
  SUB="$TMP_DIR/$(basename "$APK" .apk)"
  mkdir -p "$SUB"
  unzip -q "$APK" "classes*.dex" -d "$SUB"

  PRIVATE_KEY_COUNT=$(grep -a -c "PRIVATE_KEY" "$SUB"/classes*.dex || true)
  MEECAQAW_COUNT=$(grep -a -c "MEECAQAw" "$SUB"/classes*.dex || true)

  # Sum counts across multiple dex files if grep outputs filename:count
  PK_SUM=$(echo "$PRIVATE_KEY_COUNT" | awk -F: '{s+=$NF} END {print s+0}')
  ME_SUM=$(echo "$MEECAQAW_COUNT" | awk -F: '{s+=$NF} END {print s+0}')

  echo "$APK -> grep 'PRIVATE_KEY' in classes*.dex: $PK_SUM"
  echo "$APK -> grep 'MEECAQAw' in classes*.dex: $ME_SUM"

  if [[ "$PK_SUM" -ne 0 || "$ME_SUM" -ne 0 ]]; then
    TOTAL_FAIL=1
  fi
done

if [[ "$TOTAL_FAIL" -ne 0 ]]; then
  echo "FAILED: Found forbidden private key markers in APK dex files!"
  exit 1
fi

echo "PASSED: 0 private key markers found in all APK classes*.dex files."
