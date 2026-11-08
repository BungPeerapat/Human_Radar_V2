# ส่งต่องานไปพัฒนาบนเซิร์ฟเวอร์

## เป้าหมายและสถานะ

ใช้เซิร์ฟเวอร์เป็นพื้นที่พัฒนาหลักหลังตรวจรับ MQTT และ OTA บน ESP32 จริง โค้ดที่ build ผ่านยังไม่ถือว่าผ่านการตรวจรับ hardware ให้ตรวจผลล่าสุดใน `tasks/doing/` หรือ `tasks/done/` ก่อนเริ่มงานต่อ

ณ 2026-11-08: MQTT server และ SSH ผ่าน Tailscale พร้อม, implementation checkpoint `775ea41`, firmware หลัก/bridge build บน Linux ได้แล้ว งาน 001–002 ยังอยู่ `doing` เพราะรอทดสอบตัดไฟจริงและยืนยัน UART targets บน `HumanRadarBung` (ESP32-D0WD-V3, flash 4 MB, MAC `08:d1:f9:34:91:b4`)

## การเชื่อมต่อ

จาก Windows account นี้:

```powershell
ssh human-radar-uat
```

Alias อยู่ `%USERPROFILE%\.ssh\config` และใช้ Tailscale `100.77.46.79:22`, user `root`, private key `%USERPROFILE%\.ssh\human_radar_uat_rsa` คีย์เซิร์ฟเวอร์ตรวจเทียบกับการเชื่อมต่อเดิมแล้ว หากเครื่องอื่นจะเข้าถึง ต้องอยู่ใน tailnet ที่ได้รับอนุญาตและมี SSH key ของตนเอง

Public SSH fallback ยังเป็น `203.159.94.20:22` ไม่ได้ปิดหรือเปลี่ยน firewall เดิม

```sh
cd /root/project/Human_Radar_V2
git status --short
git branch --show-current
git log -5 --oneline
```

Repository คือ `https://github.com/BungPeerapat/Human_Radar_V2.git`, branch `dev` ก่อนแก้ไขให้ตรวจสถานะและ commit จริงบนเซิร์ฟเวอร์ อย่าสันนิษฐานว่า `origin/dev` รวมงานที่ยังไม่ได้ push

## โครงสร้างที่ต้องรู้

- `Human_Radar_V2/`: Arduino firmware; ต้องแก้คู่กับ PlatformIO tree
- `firmware/`: PlatformIO firmware หลัก; `include/`, `src/`, `src/radar/`
- `bridge/`: firmware กู้คืนขนาดเล็ก ใช้ WiFi + MQTT + OTA และใช้ค่า NVS เดียวกับ firmware หลัก
- `app/`: Android Java; Gradle Kotlin DSL อยู่ root
- `tests/native/`: ทดสอบ policy และ downloader จริงด้วย fake platform บน Linux ไม่ทดแทนการทดสอบชิปจริง
- `tasks/todo/`, `tasks/doing/`, `tasks/done/`: ย้ายทั้งโฟลเดอร์งานตามสถานะ; อ่าน `tasks/README.md`

หลังย้ายให้ถือ checkout บนเซิร์ฟเวอร์เป็นชุดทำงานหลัก หลีกเลี่ยงแก้ไฟล์เดียวกันพร้อมกันบน Windows และเซิร์ฟเวอร์

## MQTT และ secrets

Deployment อยู่ `/root/project/MQTT_Server` แยกจาก Git checkout:

- MQTT/TLS: `systemdeveloper.ddns.net:8883`
- WSS: `systemdeveloper.ddns.net:9002`, path `/mqtt`
- Username `humanradar`; secret อยู่ `credentials.json` ภายใน deployment directory (root อ่านได้)
- บัญชีอ่าน/เขียนได้เฉพาะ `humanradar/#`; anonymous ถูกปฏิเสธ
- `127.0.0.1:1883` และ `127.0.0.1:9001` ใช้เฉพาะในเซิร์ฟเวอร์

ห้าม commit credentials, Windows flash/NVS backups, signing keys หรือ local configuration ลง Git

```sh
docker ps --filter name=human-radar-mqtt
docker logs --tail 100 human-radar-mqtt
systemctl status human-radar-mqtt-cert-sync.timer
```

เซิร์ฟเวอร์มีงานอื่นใช้อยู่และเหลือพื้นที่ประมาณ 13 GB ตอนเริ่มงาน อย่าอัปเกรดระบบหรือหยุดบริการอื่นเพื่อเตรียม build โดยไม่ตรวจผลกระทบ

## Build และทดสอบ

Linux มี `g++`, JDK 17, Python 3.8.10 และ virtual environment `/root/project/.venv-human-radar` พร้อม PlatformIO 6.1.18 และ paho-mqtt 2.1.0 แล้ว Firmware หลักและ bridge build ผ่านบนเซิร์ฟเวอร์จริง ยังไม่ได้ติดตั้ง Android SDK บน Linux

คำสั่งสำหรับ firmware (ต้อง stamp version ทุกครั้งที่จะนำ `.bin` ไป OTA):

```sh
source /root/project/.venv-human-radar/bin/activate
PLATFORMIO_BUILD_FLAGS='-DFW_VERSION=\"<version>\"' pio run -d firmware
PLATFORMIO_BUILD_FLAGS='-DFW_VERSION=\"<version>-bridge\"' pio run -d bridge
bash tools/test-native.sh
```

Android ต้องมี JDK 17 และ Android SDK ที่ตรง `compileSdk 35` ใช้ `ANDROID_HOME`/`ANDROID_SDK_ROOT` หรือ `local.properties` ที่สร้างเฉพาะเซิร์ฟเวอร์ ห้ามใช้ Windows SDK path เดิมหรือ commit path ใหม่

JDK 17 พร้อมแล้ว เหลือ Linux SDK command-line tools, platform 35 และ build-tools ที่ AGP 8.7.2 ต้องใช้ ก่อนรัน Android build ด้านล่าง อย่าเพิ่งอ้างว่า Android build บนเซิร์ฟเวอร์ผ่านเพราะผล Windows build ผ่าน

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

GitHub release signing ต้องใช้ secrets ของระบบ release เดิม อย่าคัดลอก keystore หรือรหัสผ่านจากเครื่อง Windows ไปโดยอัตโนมัติ และยังไม่ถือว่า GitHub push authentication บนเซิร์ฟเวอร์พร้อม

## OTA หลังย้าย

MQTT ส่งคำสั่ง `ota_pull` พร้อม URL, `sha256`, `sizeBytes`, `request_id`; ESP32 ดาวน์โหลด `.bin` เองผ่าน HTTPS การย้ายเครื่องพัฒนา/MQTT ไม่บังคับให้ย้าย firmware hosting:

- Manifest และ `.bin` ปัจจุบันอยู่ GitHub Releases
- `.github/workflows/release.yml` สร้าง `firmware.json` และ `bridge.json` พร้อม hash/ขนาด
- Android ต้องส่ง metadata และรอ ACK ของคำสั่งนั้นก่อนตรวจรุ่นหลัง reboot
- Firmware ใหม่ตรวจ HTTPS certificate, ความยาวไฟล์ และ SHA-256 ก่อน commit; การตั้ง broker ใหม่ต้องรักษา WiFi/NVS และทำให้ OTA รอบถัดไปยังใช้ได้

การแฟลช/กดปุ่ม USB ยังอยู่ฝั่ง Windows ที่เสียบบอร์ด การเข้า SSH ไม่ทำให้เซิร์ฟเวอร์เห็น COM port อัตโนมัติ หลัง OTA ผ่านจริงแล้วจึงส่งคำสั่งจากเซิร์ฟเวอร์ได้โดยไม่ใช้ USB แต่เก็บวิธีกู้คืนไว้เสมอ

Firmware `1.1.0-mqtt-server` ที่ทดสอบล่าสุด build บน Linux server และติดตั้งบนบอร์ดจริงผ่าน Android/MQTT แล้ว การทดสอบใช้ HTTPS container `human-radar-ota-test` พอร์ต 9443 ชั่วคราวและไฟล์ใน `/root/project/MQTT_Server/ota-test-artifacts` ไม่ใช่การย้าย production release hosting จาก GitHub

หลังตรวจรับ hardware ครบให้หยุด container ทดสอบและลบ UFW rule เฉพาะ 9443 ที่เพิ่มสำหรับงานนี้ ก่อนปิดอย่าลบไฟล์ firmware ที่ยังต้องใช้กู้คืนบอร์ดทดสอบ

สำรอง partition/NVS ของบอร์ดทดสอบไว้ใน `%USERPROFILE%\.ssh\human-radar-backups` บน Windows มีข้อมูลลับ ห้ามนำมาใส่ใน repository การสำรอง flash ทั้งก้อนเคย checksum ไม่ผ่าน; อย่าเรียกว่า full backup ที่ใช้กู้คืนได้

## การเรียกผู้ใช้ผ่าน LINE

ย้ายระบบ `playsound-alert` ไปฝั่งเซิร์ฟเวอร์แล้ว อยู่ `/root/project/playsound-alert` ทำงานด้วย systemd service `human-radar-alert.service` API ฟังเฉพาะ `127.0.0.1:4477` บนเซิร์ฟเวอร์ ไม่ต้องเปิดโปรแกรมบน Windows ค้างไว้เพื่อส่ง LINE

ผู้ใช้อนุญาตการย้ายและการส่ง LINE แล้ว การตั้งค่าย้ายผ่าน SSH ที่เข้ารหัสและเก็บใน `data/settings.json` (root อ่านได้) ห้ามพิมพ์ token หรือคัดลอกลง Git อย่าเปลี่ยนผู้รับเอง

```sh
systemctl status human-radar-alert.service
ALERT_LINE=1 node /root/project/playsound-alert/notify.js beep "Human Radar: need help with the test board"
journalctl -u human-radar-alert.service --since '-5 min' --no-pager
```

ใช้แจ้งเมื่อจำเป็นต้องช่วยกับ hardware และเมื่อสรุปงานเสร็จ อย่าส่งทุก progress update `notify.js` exit 0 แม้ส่งไม่สำเร็จ จึงต้องตรวจ log ว่าส่ง LINE สำเร็จ ระบบนี้ทดสอบส่งจากเซิร์ฟเวอร์สำเร็จแล้ว และตั้ง auto-start หลัง reboot

## ตรวจรับก่อนเริ่ม session ถัดไป

1. ตรวจ checkout/branch/commit และไฟล์ task ที่เซิร์ฟเวอร์จริง
2. ตรวจว่า broker และ certificate timer ทำงาน และอุปกรณ์ทดสอบออนไลน์
3. อ่านผลทดสอบ hardware ล่าสุด; ห้ามเปลี่ยน checklist เป็นผ่านจากผล simulator หรือ build
4. ยืนยันว่า OTA รอบที่สองและเส้นทางกู้คืนผ่านก่อน rollout อุปกรณ์ทั้งหมด
5. บันทึกขั้นตอนถัดไปและปัญหาคงค้างใน `task.md` โดยไม่ใส่ secrets
