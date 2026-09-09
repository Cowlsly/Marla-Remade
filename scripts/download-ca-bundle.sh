#!/usr/bin/env zsh
set -e

# Reduced CA set hardening — downloads specific roots from Mozilla bundle and direct sources,
# converts PEM -> DER for bundling in library:network assets/ca/.
#
# Why: first-party Cloudflare domains only need ISRG + GTS, reducing blast radius of rogue CA.
# Appstore STANDARD needs DigiCert + ISRG + GTS (F-Droid + GitHub + Play Store via GTS).
#
# Nothing fetched here is trusted on its own. Both sources — the Mozilla bundle and the
# direct-URL fallback — are converted inside $TMP_DIR and only moved into assets/ca/ when
# the result matches its PINS entry below. The pins are the SHA-256 of the .der files as
# committed, so the anchors this script can produce are exactly the anchors already in the
# tree, and rolling one is a reviewable edit to both the .der and its pin in the same diff:
#
#   sha256sum library/network/src/main/assets/ca/<label>.der

OUT_DIR="${0:A:h:h}/library/network/src/main/assets/ca"
BUNDLE_URL="https://curl.se/ca/cacert.pem"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

clean=0
if [[ "${1:-}" == "--clean" ]]; then
  clean=1
fi

mkdir -p "$OUT_DIR"

echo "Downloading Mozilla CA bundle ($BUNDLE_URL)..."
curl -LfsS -o "$TMP_DIR/cacert.pem" "$BUNDLE_URL" || { echo "curl failed for bundle"; exit 1; }

# Split bundle into individual certs
echo "Splitting bundle..."
awk -v tmpdir="$TMP_DIR" '
  BEGIN { n=0; out="" }
  /BEGIN CERTIFICATE/ { out=tmpdir"/cert-"n".pem"; n++ }
  { if(out!="") print > out }
  /END CERTIFICATE/ { out="" }
' "$TMP_DIR/cacert.pem"

typeset -A ROOTS
# FIRST_PARTY: ISRG X1/X2 + GTS R1-R4
ROOTS[isrgrootx1]="ISRG Root X1"
ROOTS[isrgrootx2]="ISRG Root X2"
ROOTS[gts-root-r1]="GTS Root R1"
ROOTS[gts-root-r2]="GTS Root R2"
ROOTS[gts-root-r3]="GTS Root R3"
ROOTS[gts-root-r4]="GTS Root R4"
# STANDARD adds DigiCert, Baltimore, Amazon, Sectigo
ROOTS[digicert-global-g2]="DigiCert Global Root G2"
ROOTS[digicert-global-g3]="DigiCert Global Root G3"
ROOTS[baltimore-cybertrust]="Baltimore CyberTrust Root"
ROOTS[amazon-root-ca1]="Amazon Root CA 1"
ROOTS[amazon-root-ca2]="Amazon Root CA 2"
ROOTS[amazon-root-ca3]="Amazon Root CA 3"
ROOTS[amazon-root-ca4]="Amazon Root CA 4"
ROOTS[usertrust-rsa]="USERTrust RSA Certification Authority"
# EXTENDED
ROOTS[microsoft-rsa-2017]="Microsoft RSA Root Certificate Authority 2017"
ROOTS[apple-root-g2]="Apple Root CA - G2"
ROOTS[apple-root-g3]="Apple Root CA - G3"
ROOTS[apple-ist-ca2-g1]="Apple IST CA 2 - G1"
ROOTS[globalsign-root-r3]="GlobalSign Root CA - R3"
ROOTS[godaddy-root-g2]="Go Daddy Root Certificate Authority - G2"

# SHA-256 of each committed assets/ca/<label>.der. A label with no pin is never installed.
typeset -A PINS
PINS[amazon-root-ca1]="8ecde6884f3d87b1125ba31ac3fcb13d7016de7f57cc904fe1cb97c6ae98196e"
PINS[amazon-root-ca2]="1ba5b2aa8c65401a82960118f80bec4f62304d83cec4713a19c39c011ea46db4"
PINS[amazon-root-ca3]="18ce6cfe7bf14e60b2e347b8dfe868cb31d02ebb3ada271569f50343b46db3a4"
PINS[amazon-root-ca4]="e35d28419ed02025cfa69038cd623962458da5c695fbdea3c22b0bfb25897092"
PINS[apple-ist-ca2-g1]="b0d40aa5f024f98e7adc0b10f19764f71030cfaf3dcc4ddc6600869499c9baaa"
PINS[apple-root-g2]="c2b9b042dd57830e7d117dac55ac8ae19407d38e41d88f3215bc3a890444a050"
PINS[apple-root-g3]="63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179"
PINS[baltimore-cybertrust]="16af57a9f676b0ab126095aa5ebadef22ab31119d644ac95cd4b93dbf3f26aeb"
PINS[digicert-global-g2]="cb3ccbb76031e5e0138f8dd39a23f9de47ffc35e43c1144cea27d46a5ab1cb5f"
PINS[digicert-global-g3]="31ad6648f8104138c738f39ea4320133393e3a18cc02296ef97c2ac9ef6731d0"
PINS[globalsign-root-r3]="cbb522d7b7f127ad6a0113865bdf1cd4102e7d0759af635a7cf4720dc963c53b"
# godaddy-root-g2.der is committed PEM-encoded despite the extension, so a freshly
# converted DER will not match this pin until that asset is itself re-encoded.
PINS[godaddy-root-g2]="500329abac100a953a7396b54b36be57d333022f17401bc948248ea179cf1784"
PINS[gts-root-r1]="d947432abde7b7fa90fc2e6b59101b1280e0e1c7e4e40fa3c6887fff57a7f4cf"
PINS[gts-root-r2]="8d25cd97229dbf70356bda4eb3cc734031e24cf00fafcfd32dc76eb5841c7ea8"
PINS[gts-root-r3]="34d8a73ee208d9bcdb0d956520934b4e40e69482596e8b6f73c8426b010a6f48"
PINS[gts-root-r4]="349dfa4058c5e263123b398ae795573c4e1313c83fe68f93556cd5e8031b3c7d"
PINS[isrgrootx1]="96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
PINS[isrgrootx2]="69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470"
PINS[microsoft-rsa-2017]="c741f70f4b2a8d88bf2e71c14122ef53ef10eba0cfa5e64cfa20f418853073e0"
PINS[usertrust-rsa]="e793c9b02fd8aa13e21c31228accb08119643b749c898964b1746d46c3d4cbd2"

# Fallback sources for roots the bundle may drop. Partial by design: a root with no entry
# here simply has to come from the bundle. Every fetch is pin-checked either way.
typeset -A URLS
URLS[isrgrootx1]="https://letsencrypt.org/certs/isrgrootx1.pem"
URLS[isrgrootx2]="https://letsencrypt.org/certs/isrg-root-x2.pem"
URLS[gts-root-r1]="https://pki.goog/roots/gtsr1.pem"
URLS[gts-root-r2]="https://pki.goog/roots/gtsr2.pem"
URLS[gts-root-r3]="https://pki.goog/roots/gtsr3.pem"
URLS[gts-root-r4]="https://pki.goog/roots/gtsr4.pem"
URLS[digicert-global-g2]="https://cacerts.digicert.com/DigiCertGlobalRootG2.crt"
URLS[digicert-global-g3]="https://cacerts.digicert.com/DigiCertGlobalRootG3.crt"
URLS[baltimore-cybertrust]="https://cacerts.digicert.com/BaltimoreCyberTrustRoot.crt"
URLS[amazon-root-ca1]="https://www.amazontrust.com/repository/AmazonRootCA1.pem"
URLS[amazon-root-ca2]="https://www.amazontrust.com/repository/AmazonRootCA2.pem"
URLS[amazon-root-ca3]="https://www.amazontrust.com/repository/AmazonRootCA3.pem"
URLS[amazon-root-ca4]="https://www.amazontrust.com/repository/AmazonRootCA4.pem"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else openssl dgst -sha256 "$1" | awk '{print $NF}'
  fi
}

find_pem_in_bundle() {
  local label=$1 pattern=$2
  for cert in "$TMP_DIR"/cert-*.pem; do
    if openssl x509 -in "$cert" -noout -subject 2>/dev/null | grep -q "$pattern"; then
      cp "$cert" "$TMP_DIR/$label.pem"
      echo "  Found $label <- $pattern"
      return 0
    fi
  done
  # fallback text grep
  for cert in "$TMP_DIR"/cert-*.pem; do
    if openssl x509 -in "$cert" -noout -text 2>/dev/null | grep -q "$pattern"; then
      cp "$cert" "$TMP_DIR/$label.pem"
      echo "  Found $label <- $pattern (text)"
      return 0
    fi
  done
  return 1
}

success=0
fail=0
for label in ${(k)ROOTS}; do
  pattern="${ROOTS[$label]}"
  if find_pem_in_bundle "$label" "$pattern"; then
    success=$((success+1))
  else
    if [[ -n "${URLS[$label]:-}" ]]; then
      echo "  Trying direct URL for $label: ${URLS[$label]}"
      if curl -LfsS -o "$TMP_DIR/$label.pem" "${URLS[$label]}"; then
        echo "  Fetched $label via URL"
        success=$((success+1))
      else
        echo "  FAIL to fetch $label via URL" >&2
        fail=$((fail+1))
      fi
    else
      echo "  MISSING $label ($pattern) — will remain system-trust fallback"
      fail=$((fail+1))
    fi
  fi
done

echo "Converting PEM -> DER in $TMP_DIR, installing only on pin match..."
mkdir -p "$TMP_DIR/der"
installed=0
rejected=0
for label in ${(k)ROOTS}; do
  pem="$TMP_DIR/$label.pem"
  [[ -f "$pem" ]] || continue
  der="$TMP_DIR/der/$label.der"
  if ! openssl x509 -in "$pem" -outform der -out "$der" 2>/dev/null; then
    # maybe DER input
    if ! openssl x509 -in "$pem" -inform der -outform der -out "$der" 2>/dev/null; then
      echo "  FAIL converting $pem" >&2
      rm -f "$der"
      rejected=$((rejected+1))
      continue
    fi
  fi
  want="${PINS[$label]:-}"
  got=$(sha256_of "$der")
  if [[ -z "$want" ]]; then
    echo "  UNPINNED $label — not written (got $got)" >&2
    rejected=$((rejected+1))
  elif [[ "$got" != "$want" ]]; then
    printf '  MISMATCH %s\n             want %s\n             got  %s\n' "$label" "$want" "$got" >&2
    echo "             Not written. Inspect the certificate, then commit the new .der and its pin together." >&2
    rejected=$((rejected+1))
  else
    mv -f "$der" "$OUT_DIR/$label.der"
    echo "  $label.der ok $got"
    installed=$((installed+1))
  fi
done

# Pruning happens last and only on a clean run, so a failed fetch can never leave the
# shipped trust set short of an anchor.
if (( clean )); then
  if (( fail == 0 && rejected == 0 )); then
    for der in "$OUT_DIR"/*.der(N); do
      label="${${der:t}%.der}"
      if [[ -z "${PINS[$label]:-}" ]]; then
        rm -f "$der"
        echo "Removed unpinned $label.der"
      fi
    done
  else
    echo "--clean skipped: run had failures, not pruning $OUT_DIR" >&2
  fi
fi

echo ""
echo "Result: $success found, $fail missing, $installed installed, $rejected rejected"
ls -lh "$OUT_DIR" 2>/dev/null
echo ""
echo "Verify: openssl x509 -in $OUT_DIR/isrgrootx1.der -inform der -noout -subject || true"

if (( fail || rejected )); then
  exit 1
fi
