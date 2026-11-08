#!/usr/bin/env bash
set -euo pipefail
script="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/sync-update-mirror.sh"
test_root="$(mktemp -d /tmp/human-radar-mirror-test.XXXXXX)"
trap '[[ "$test_root" == /tmp/human-radar-mirror-test.* ]] && rm -rf -- "$test_root"' EXIT
export MIRROR_ROOT="$test_root/public" STATE_ROOT="$test_root/state" FIXTURE_ROOT="$test_root/fixtures"
mkdir -p "$test_root/bin" "$FIXTURE_ROOT"
cat > "$test_root/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
url="" output=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output) output="$2"; shift 2 ;;
        https://*) url="$1"; shift ;;
        *) shift ;;
    esac
done
if [[ "$url" == */releases/latest ]]; then
    cp "$FIXTURE_ROOT/release.json" "$output"
else
    id="${url##*/}"
    name="$(jq -er --arg id "$id" '.assets[] | select((.id|tostring) == $id) | .name' "$FIXTURE_ROOT/release.json")"
    cp "$FIXTURE_ROOT/$name" "$output"
fi
MOCK
chmod +x "$test_root/bin/curl"
export PATH="$test_root/bin:$PATH"
fixture() {
    local version="$1" code="$2" id=0 name key file hash size
    printf 'apk fixture' > "$FIXTURE_ROOT/human-radar-v$version.apk"
    printf 'firmware fixture' > "$FIXTURE_ROOT/firmware-v$version.bin"
    printf 'bridge fixture' > "$FIXTURE_ROOT/bridge-v$version.bin"
    jq -n --arg tag "v$version" '{id:1,tag_name:$tag,draft:false,prerelease:false,assets:[]}' > "$FIXTURE_ROOT/release.json"
    for name in update.json firmware.json bridge.json; do
        case "$name" in
            update.json) key=apkUrl; file="human-radar-v$version.apk"; manifest_version="$version" ;;
            firmware.json) key=binUrl; file="firmware-v$version.bin"; manifest_version="$version" ;;
            bridge.json) key=binUrl; file="bridge-v$version.bin"; manifest_version="$version-bridge" ;;
        esac
        hash="$(sha256sum "$FIXTURE_ROOT/$file")"; hash="${hash%% *}"
        size="$(wc -c < "$FIXTURE_ROOT/$file")"
        jq -n --arg version "$manifest_version" --argjson code "$code" --arg key "$key" \
            --arg url "https://github.com/BungPeerapat/Human_Radar_V2/releases/download/v$version/$file" \
            --arg hash "$hash" --argjson size "$size" \
            '{versionName:$version,versionCode:$code,sha256:$hash,sizeBytes:$size,($key):$url}' \
            > "$FIXTURE_ROOT/$name"
        for asset in "$name" "$file"; do
            id=$((id+1))
            jq --arg name "$asset" --argjson id "$id" \
                '.assets += [{id:$id,name:$name,size:1,updated_at:"now",url:("https://api.github.com/repos/BungPeerapat/Human_Radar_V2/releases/assets/"+($id|tostring))}]' \
                "$FIXTURE_ROOT/release.json" > "$FIXTURE_ROOT/tmp.json"
            mv "$FIXTURE_ROOT/tmp.json" "$FIXTURE_ROOT/release.json"
        done
    done
}
fixture 9.9.1 999
bash "$script"
[[ -f "$MIRROR_ROOT/latest.apk" && "$(readlink "$MIRROR_ROOT/latest.apk")" == current/latest.apk ]]
previous="$(readlink "$MIRROR_ROOT/current")"
fixture 9.9.2 1000
printf corrupt >> "$FIXTURE_ROOT/human-radar-v9.9.2.apk"
if bash "$script"; then echo "Corrupt APK was published" >&2; exit 1; fi
[[ "$(readlink "$MIRROR_ROOT/current")" == "$previous" ]]
[[ -f "$MIRROR_ROOT/latest.apk" ]]
fixture 9.9.2 1000
bash "$script"
[[ "$(jq -r .versionCode "$MIRROR_ROOT/update.json")" == 1000 ]]
[[ -f "$MIRROR_ROOT/latest.apk" && "$(readlink "$MIRROR_ROOT/latest.apk")" == current/latest.apk ]]
previous="$(readlink "$MIRROR_ROOT/current")"
fixture 9.9.1 999
if bash "$script"; then echo "Older release was published" >&2; exit 1; fi
[[ "$(readlink "$MIRROR_ROOT/current")" == "$previous" ]]
echo "Mirror tests passed: publish, corrupt-download preservation, next release, no downgrade"
