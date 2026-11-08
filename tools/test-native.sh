#!/usr/bin/env bash
set -euo pipefail

task_repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/human-radar-native.XXXXXX")"
task_json_dir="$task_repo_dir/firmware/.pio/libdeps/esp32-wroom32/ArduinoJson/src"
if [[ ! -f "$task_json_dir/ArduinoJson.h" ]]; then
    echo "Install firmware dependencies first: pio pkg install -d firmware" >&2
    exit 1
fi

for task_name in config_manager.cpp mqtt_client.cpp web_server.cpp; do
    cmp "$task_repo_dir/Human_Radar_V2/$task_name" "$task_repo_dir/firmware/src/$task_name"
done
for task_name in config_json.h server_policy.h ota_policy.h ota_download.h ota_trust.h mqtt_trust.h mqtt_client.h web_server.h; do
    cmp "$task_repo_dir/Human_Radar_V2/$task_name" "$task_repo_dir/firmware/include/$task_name"
done
for task_name in server_policy.h ota_policy.h ota_download.h ota_trust.h mqtt_trust.h; do
    cmp "$task_repo_dir/bridge/include/$task_name" "$task_repo_dir/firmware/include/$task_name"
done

g++ -std=c++11 -Wall -Wextra -Werror -I "$task_repo_dir/firmware/include" \
    "$task_repo_dir/tests/native/ota_policy_test.cpp" -o "$task_build_dir/ota_policy_test"
"$task_build_dir/ota_policy_test"
g++ -std=c++11 -Wall -Wextra -Werror -I "$task_repo_dir/firmware/include" -I "$task_json_dir" \
    "$task_repo_dir/tests/native/config_json_test.cpp" -o "$task_build_dir/config_json_test"
"$task_build_dir/config_json_test"
g++ -std=c++11 -Wall -Wextra -Werror -Wno-deprecated-declarations --coverage \
    -I "$task_repo_dir/tests/native/fakes" -I "$task_repo_dir/firmware/include" \
    -c "$task_repo_dir/tests/native/ota_download_test.cpp" -o "$task_build_dir/ota_download_test.o"
g++ --coverage "$task_build_dir/ota_download_test.o" -lcrypto -o "$task_build_dir/ota_download_test"
(
    cd "$task_build_dir"
    ./ota_download_test
    gcov -b -o "$task_build_dir" "$task_repo_dir/tests/native/ota_download_test.cpp" > coverage.txt
)
echo "Firmware mirrors match. Native tests passed. Artifacts: $task_build_dir"
