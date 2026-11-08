#!/usr/bin/env bash
# Publish only a complete, verified snapshot of a public project release.
set -euo pipefail
umask 022
mirror_root="${MIRROR_ROOT:-/var/www/human-radar-updates}"
state_root="${STATE_ROOT:-/var/lib/human-radar-updates}"
public_base="${PUBLIC_BASE:-https://systemdeveloper.ddns.net/human-radar/updates}"
repo="BungPeerapat/Human_Radar_V2"
api="https://api.github.com/repos/$repo"
[[ "$mirror_root" == /* && "$mirror_root" != / && "$state_root" == /* && "$state_root" != / ]]
[[ "$public_base" == https://* ]]
mkdir -p "$mirror_root/releases" "$state_root/staging"
exec 9>"$state_root/sync.lock"
flock -n 9 || exit 0
stage="$(mktemp -d "$state_root/staging/release.XXXXXX")"
cleanup() {
    [[ "$stage" == "$state_root/staging/release."* ]] && rm -rf -- "$stage"
}
trap cleanup EXIT
fetch() {
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --retry 2 --connect-timeout 10 --max-time 90 \
        --header "Accept: $2" --header "User-Agent: HumanRadar-UpdateMirror" "$1" --output "$3"
}
fetch "$api/releases/latest" application/vnd.github+json "$stage/release.json"
jq -e '.draft == false and .prerelease == false' "$stage/release.json" >/dev/null
tag="$(jq -er '.tag_name' "$stage/release.json")"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
fingerprint="$(jq -c '[.id,.tag_name,[.assets[] | [.id,.name,.size,.updated_at]]]' "$stage/release.json" | sha256sum)"
fingerprint="${fingerprint%% *}"
snapshot="$tag-${fingerprint:0:12}"
asset() {
    local name="$1" destination="$2" url
    url="$(jq -er --arg name "$name" '.assets[] | select(.name == $name) | .url' "$stage/release.json")"
    [[ "$url" =~ ^https://api.github.com/repos/BungPeerapat/Human_Radar_V2/releases/assets/[0-9]+$ ]]
    fetch "$url" application/octet-stream "$destination"
}
verify_snapshot() {
    local directory="$1" manifest key filename expected size actual
    for manifest in update.json firmware.json bridge.json; do
        key=binUrl; [[ "$manifest" != update.json ]] || key=apkUrl
        filename="$(jq -er --arg key "$key" '.[$key] | split("/") | last' "$directory/$manifest")"
        [[ "$filename" =~ ^(human-radar|firmware|bridge)-v[0-9]+\.[0-9]+\.[0-9]+\.(apk|bin)$ ]]
        expected="$(jq -er '.sha256' "$directory/$manifest")"
        size="$(jq -er '.sizeBytes' "$directory/$manifest")"
        [[ "$expected" =~ ^[a-fA-F0-9]{64}$ && "$size" =~ ^[1-9][0-9]*$ ]]
        [[ "$manifest" == update.json || "$size" -le 1310720 ]]
        [[ "$(wc -c < "$directory/$filename")" -eq "$size" ]]
        actual="$(sha256sum "$directory/$filename")"; actual="${actual%% *}"
        [[ "${actual,,}" == "${expected,,}" ]]
    done
}
if [[ -d "$mirror_root/releases/$snapshot" ]]; then
    verify_snapshot "$mirror_root/releases/$snapshot"
else
    mkdir "$stage/snapshot"
    for manifest in update.json firmware.json bridge.json; do
        asset "$manifest" "$stage/snapshot/$manifest"
        expected_version="${tag#v}"; [[ "$manifest" != bridge.json ]] || expected_version="$expected_version-bridge"
        jq -e --arg version "$expected_version" \
            '.versionName == $version and (.versionCode | type == "number") and .versionCode > 0' \
            "$stage/snapshot/$manifest" >/dev/null
        key=binUrl; [[ "$manifest" != update.json ]] || key=apkUrl
        source_url="$(jq -er --arg key "$key" '.[$key]' "$stage/snapshot/$manifest")"
        [[ "$source_url" == "https://github.com/$repo/releases/download/$tag/"* ]]
        filename="${source_url##*/}"
        [[ "$filename" =~ ^(human-radar|firmware|bridge)-v[0-9]+\.[0-9]+\.[0-9]+\.(apk|bin)$ ]]
        asset "$filename" "$stage/snapshot/$filename"
        jq --arg key "$key" --arg url "$public_base/releases/$snapshot/$filename" \
            '.[$key] = $url' "$stage/snapshot/$manifest" > "$stage/manifest.tmp"
        mv -- "$stage/manifest.tmp" "$stage/snapshot/$manifest"
    done
    verify_snapshot "$stage/snapshot"
    app_code="$(jq -er '.versionCode' "$stage/snapshot/update.json")"
    for manifest in firmware.json bridge.json; do
        [[ "$(jq -er '.versionCode' "$stage/snapshot/$manifest")" == "$app_code" ]]
    done
    mv -T -- "$stage/snapshot" "$mirror_root/releases/$snapshot"
fi
apk_name="$(jq -er '.apkUrl | split("/") | last' "$mirror_root/releases/$snapshot/update.json")"
if [[ -L "$mirror_root/releases/$snapshot/latest.apk" ]]; then
    [[ "$(readlink "$mirror_root/releases/$snapshot/latest.apk")" == "$apk_name" ]]
else
    ln -s "$apk_name" "$mirror_root/releases/$snapshot/latest.apk"
fi
if [[ -f "$mirror_root/current/update.json" ]]; then
    [[ "$(jq -er '.versionCode' "$mirror_root/releases/$snapshot/update.json")" -ge \
       "$(jq -er '.versionCode' "$mirror_root/current/update.json")" ]]
fi
ln -s "releases/$snapshot" "$mirror_root/.current.$$"
mv -Tf -- "$mirror_root/.current.$$" "$mirror_root/current"
for manifest in update.json firmware.json bridge.json; do
    ln -s "current/$manifest" "$mirror_root/.$manifest.$$"
    mv -Tf -- "$mirror_root/.$manifest.$$" "$mirror_root/$manifest"
done
ln -s "current/latest.apk" "$mirror_root/.latest.apk.$$"
mv -Tf -- "$mirror_root/.latest.apk.$$" "$mirror_root/latest.apk"
printf 'Verified update mirror: %s\n' "$snapshot"
