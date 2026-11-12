#!/usr/bin/env bash
set -euo pipefail
# ATOMIC_SIGNING_DIR defaults to <checkout>/.signing; JAVA_HOME selects keytool.

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
signing_dir="${ATOMIC_SIGNING_DIR-$project_root/.signing}"
: "${JAVA_HOME:?Error: JAVA_HOME is required to create the signing key.}"
if [[ ! -x "$JAVA_HOME/bin/keytool" ]]; then
    printf 'Error: JAVA_HOME must contain executable bin/keytool.\n' >&2
    exit 1
fi
if [[ -z "$signing_dir" ]]; then
    printf 'Error: ATOMIC_SIGNING_DIR must not be empty.\n' >&2
    exit 1
fi
umask 077
mkdir -p "$signing_dir"
signing_dir="$(cd "$signing_dir" && pwd)"
chmod 700 "$signing_dir"
key_file="$signing_dir/atomic-link.jks"
properties_file="$signing_dir/signing.properties"
if [[ -f "$key_file" && -f "$properties_file" ]]; then
    chmod 600 "$key_file" "$properties_file"
    exit 0
fi
if [[ -e "$key_file" || -e "$properties_file" ]]; then
    printf 'Error: incomplete project signing material in ATOMIC_SIGNING_DIR; restore its backup.\n' >&2
    exit 1
fi
temporary_dir="$(mktemp -d "$signing_dir/.create.XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT
openssl rand -hex 32 > "$temporary_dir/store-password"
openssl rand -hex 32 > "$temporary_dir/key-password"
if "$JAVA_HOME/bin/keytool" -genkeypair -storetype JKS -alias atomic \
    -keystore "$temporary_dir/atomic-link.jks" \
    -storepass:file "$temporary_dir/store-password" \
    -keypass:file "$temporary_dir/key-password" \
    -keyalg RSA -keysize 3072 -validity 10000 -dname CN=Atomic -noprompt \
    > "$temporary_dir/keytool.log" 2>&1; then
    :
else
    result=$?
    printf 'Error: project signing key generation failed (keytool exit %s).\n' "$result" >&2
    exit "$result"
fi
IFS= read -r store_password < "$temporary_dir/store-password"
IFS= read -r key_password < "$temporary_dir/key-password"
escaped_key_file="${key_file//\\/\\\\}"
escaped_key_file="${escaped_key_file//:/\\:}"
{
    printf 'storeFile=%s\nkeyAlias=atomic\n' "$escaped_key_file"
    printf 'storePassword=%s\nkeyPassword=%s\n' "$store_password" "$key_password"
} > "$temporary_dir/signing.properties"
chmod 600 "$temporary_dir/atomic-link.jks" "$temporary_dir/signing.properties"
mv "$temporary_dir/atomic-link.jks" "$key_file"
mv "$temporary_dir/signing.properties" "$properties_file"
