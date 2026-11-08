# ผลทดสอบ MQTT และ OTA

วันที่ 2026-11-08, branch `dev`, เริ่มจาก commit `cea5c529c02867803d6906f743337742db56f5df` ตารางนี้แยกสิ่งที่พิสูจน์แล้วออกจากงานที่ยังรอตรวจรับ

## อุปกรณ์และสภาพแวดล้อม

- ESP32 จริง: `HumanRadarBung`, ESP32-D0WD-V3 rev 3.1, MAC `08:d1:f9:34:91:b4`, flash 4 MB
- Firmware เริ่มต้น: `1.0.61`; OTA slots `app0`/`app1` ขนาด 1,310,720 bytes ต่อ slot
- IP ที่ทดสอบใน LAN: `10.252.73.75`
- Android: APK ของ checkout นี้บน emulator API 36 (`emulator-5556`), เชื่อมกับ broker และ ESP32 จริง ไม่ใช่ mock broker; ยังไม่ได้ทดสอบบนโทรศัพท์จริง
- Broker: Mosquitto 2.1.2 บน `systemdeveloper.ddns.net`, TLS `8883`, WSS `9002` `/mqtt`
- เครื่องพัฒนา Linux: SSH ผ่าน Tailscale `100.77.46.79`, checkout `/root/project/Human_Radar_V2`

## ผลที่ยืนยันแล้ว

| การทดสอบ | หลักฐาน / ผล |
| --- | --- |
| Broker authentication/ACL | ปฏิเสธ anonymous, password ผิด และ publish นอก `humanradar/#` |
| TLS และ WSS จากภายนอก | Publish/subscribe ผ่าน TLS ที่ตรวจใบรับรองได้ทั้งสองวิธี |
| Retained persistence | ข้อความ retained อยู่หลัง restart broker |
| ย้ายบอร์ดจริง | เปลี่ยนจาก broker เก่าที่เสีย เป็น project hostname + TLS; `/status=online`, `/info` ตรงชื่อ/MAC |
| OTA MQTT pull รอบแรก | `1.0.61` → `1.1.0-mqtt-test1`, รีบูตและกลับมาออนไลน์ ไม่ใช้ USB เขียน firmware |
| OTA HTTP push รอบถัดไป | HTTP 200, `1.1.0-mqtt-test1` → `1.1.0-mqtt-test2`, ยืนยันรุ่นผ่าน MQTT หลังรีบูต |
| Manual rollback | สั่ง HTTP rollback จาก test2 แล้วบอร์ดกลับมาเป็น test1 จริง ไม่สรุปจาก HTTP response อย่างเดียว |
| Metadata ผิดบนบอร์ดจริง | ปฏิเสธ missing SHA, oversized image, plaintext URL, length mismatch, 404 และ SHA mismatch โดยมี error ACK |
| ดาวน์โหลดขาดช่วงจริง | หยุดเฉพาะ HTTPS container ทดสอบระหว่างรับ body; บอร์ดตอบ `Incomplete firmware download` และไม่ commit |
| การรักษาค่าผู้ใช้ | เทียบ WiFi, device name, sensor timing/mode/sensitivity ก่อนและหลังการย้าย ยังตรงกัน |
| JSON regression | JSON แบบมี whitespace/partial update ไม่ล้าง WiFi/MQTT; field type ผิดตอบ HTTP 400 |
| ESP32 WSS | เปลี่ยนเป็น protocol 1, port 9002 แล้วเชื่อมต่อได้โดยใช้ CA verification |
| เข้า bridge ผ่าน OTA | Firmware หลักที่ใช้ WSS ดาวน์โหลด/ตรวจ hash ของ bridge แล้ว bridge ออนไลน์ผ่าน TLS ที่ map จาก project endpoint |
| ออกจาก bridge ผ่าน OTA | Bridge ดาวน์โหลด/ตรวจ hash ของ test2 แล้ว firmware หลักกลับมาใช้ saved WSS config ได้ |
| Android authenticated reconnect | Live regression เดิมล้มเหลวหลัง restart broker; แก้ reconnect auth/state แล้วผ่าน TLS ใน 9.864 วินาที พร้อม health command/ACK จากบอร์ดจริง |
| Android WSS reconnect | เพิ่ม optional HiveMQ WebSocket dependencies; live regression ผ่าน WSS ใน 5.861 วินาที พร้อม health command/ACK จริง |
| Android OTA ครบเส้นทาง | `FirmwareMqttOtaInstrumentedTest` ผ่านใน 63.859 วินาที: checksum rejection ไม่เรียก onUploaded แล้วติดตั้ง Linux-server-built `1.1.0-mqtt-server` และตรวจรุ่นหลัง reboot สำเร็จ |
| GitHub hosting เดิม | ESP32 ดาวน์โหลด `.bin` จาก GitHub Releases ผ่าน HTTPS และ redirect จนครบ โดยใช้ expected hash ผิดเพื่อหยุดก่อน commit; ได้ SHA mismatch ตามคาด ไม่ downgrade |
| MQTT config/health จริง | ตั้ง publish interval เดิม 100 ms ผ่าน config topic ได้ ACK `ok`; health ตอบ firmware `1.1.0-mqtt-server` |
| Native tests บน Linux | Policy + ArduinoJson + production downloader กับ fake platform ผ่าน รวม 12 transfer scenarios; mirrors ตรงกัน |
| Coverage เฉพาะ downloader | `ota_download.h` 97.33% executed lines (75 lines); 100% branches executed, 75.58% branch outcomes taken; ไม่ใช่ coverage ของทั้ง firmware |
| Windows builds | Firmware หลัก/bridge และ Android debug APK build ผ่าน; JVM suite 62 tests, failures 0, errors 0 |
| Linux firmware build | Firmware หลักและ bridge build ผ่านด้วย Espressif32 6.9.0 / Arduino 2.0.17 |
| LINE บนเซิร์ฟเวอร์ | `human-radar-alert.service` active/enabled; server journal ยืนยันส่ง LINE สำเร็จ; local notification app ปิดได้ |

## ผล hardware หลังผู้ใช้ช่วยตัดไฟ

- ตัดไฟจริงระหว่าง MQTT OTA ที่ progress 19%: ไม่พบ commit/restarting ACK; หลังเสียบกลับ firmware ยังเป็น `1.1.0-mqtt-server`, reset reason 1 และ boot count 701 → 702
- หลังตัดไฟ OTA ไป `1.1.0-mqtt-test2` สำเร็จ ตรวจ SHA/commit ACK และ health รุ่นใหม่จริง (boot count 703)
- OTA กลับ Linux-server-built `1.1.0-mqtt-server` สำเร็จหลังเสียบ USB ใหม่; เก็บ `power-recovery-final-result.json` และ raw logs ในพื้นที่ส่วนตัว
- พบ brownout จริงระหว่างทดสอบ: serial ระบุ `Brownout detector was triggered` ตอนเริ่ม WiFi; boot count/brownout counter ยังเพิ่มในรอบหลัง จึงยังไม่อ้างว่าไฟเลี้ยงเสถียร แม้มีช่วงที่ health ติดต่อกัน 15 วินาทีโดยไม่รีเซ็ต
- ผู้ใช้ยืนยันว่าต่อ HLK-LD2450 แล้ว; sample 20 วินาทีรับ targets จริง 112 ข้อความ มี target present และ frame counter 167 → 389, error count 0
- APK `1.1.0-mqtt-uat` บน emulator ต่อได้ (`connected`, device `online`, firmware ถูกต้อง); หน้า Radar รับข้อมูลจริงและ frame counter 20 → 432, error count 0 มีสถานะ NO DATA เป็นช่วง จึงต้องแก้ไฟเลี้ยงก่อนรับรองการทำงานต่อเนื่อง
- APK ทดสอบ build สำเร็จและตรวจ SHA-256 หลังดาวน์โหลดผ่าน HTTPS แล้ว ส่งลิงก์ชั่วคราวผ่าน LINE; ยังไม่ใช่ release ที่ signed ด้วยคีย์ production และยังไม่ปรากฏใน Check Update
- App Check Update อ่าน `update.json` จาก GitHub Releases; public manifest ปัจจุบันยังเป็น `1.0.61`, versionCode 77 ต้องเผยแพร่ signed release ใหม่ก่อนผู้ใช้เดิมจะพบเวอร์ชัน MQTT ใหม่นี้

## ผล release v1.1.0 และไฟเลี้ยงรอบล่าสุด

- เผยแพร่ `v1.1.0` จาก commit `84a70b950990937f5ad85d8a2c32ced0e80c9bba`; CI run `42443082451` สำเร็จ มี APK, firmware, bridge และ manifest รวม 6 assets
- APK versionCode 91, 8,160,007 bytes, SHA-256 `3a006b7ff6b3241b0d313dfaa11fbf9ae96fb362aa655f1814769ccf9a711fb5`; signing certificate SHA-256 ตรงกับ v1.0.61: `1850bfe58c0b293bb0ea9d1fdebec1b1e3c3d61957b8a924d178d3a7e3f3a11a`
- ตรวจ public latest manifest ทั้ง update/firmware/bridge เป็น versionCode 91 และดาวน์โหลด public APK มาตรวจ hash/ขนาดตรงกับ CI; ผู้ใช้ยืนยันอัปเดตแอปแล้วเชื่อมต่อ MQTT ได้
- CI แบบ Draft ได้รับการ review และผ่าน actionlint; live preflight tests ปฏิเสธ published-version collision และ draft target mismatch ตามคาด
- หลัง publish ด้วย CLI มี tag-triggered run `42443479955` ถูก guard ปฏิเสธเพราะเวอร์ชันเผยแพร่แล้ว จึงไม่ build หรือเขียนทับ assets; signed draft run ก่อน publish เป็น run ที่สำเร็จ
- หลังผู้ใช้เปลี่ยนการต่อไฟ มี health 15 samples ตลอด 126.3 วินาที: boot count 973, brownout counter 740 คงเดิม แต่ aggregate sample ไม่ผ่านเพราะ targets ขาดช่วง
- หลังผู้ใช้ปิด–เปิดไฟ HLK-LD2450 (แหล่งจ่ายแยกและ GND ร่วมตามผู้ใช้ยืนยัน) รับ targets 180 ข้อความใน 32 วินาที, frame counter 1177 → 1535, error count 18 คงเดิม และ boot/brownout counter คงเดิม; sample นี้ผ่าน
- สั่ง OTA ด้วย manifest/URL จริงจาก public GitHub release: ติดตั้ง `1.1.0` และตรวจ health หลัง reboot ได้ โดยไม่ใช้ USB data แต่พบ brownout เพิ่ม 2 ครั้ง (740 → 742), boots 973 → 976 ระหว่าง startup
- หลังบูต v1.1.0 มี targets 82 ข้อความในช่วงตรวจ 30 วินาทีและ health ที่ uptime 16 → 46 โดยไม่รีเซ็ตเพิ่ม; ไม่ถือว่าผ่านเกณฑ์ไฟเลี้ยงสะอาด เพราะ brownout เพิ่มและข้อมูล UART ยังมี error 1 ครั้ง
- ปิดและลบ container `human-radar-ota-test` แล้ว ลบเฉพาะ UFW rules 9443 ที่มี comment ของงานทดสอบ; broker/LINE service คงทำงาน เก็บ recovery binaries และ raw evidence ไว้ใน directory ที่ root อ่านได้

## Controlled restart/OTA และการแยกชุดจ่ายไฟ

- ผู้ใช้แจ้งว่าสั่ง Restart จากแอประหว่างรอบก่อน จึงไม่ใช้รอบนั้นสรุปสาเหตุของการรีเซ็ต และยืนยันว่าขณะนี้ ESP32 ใช้ชุดจ่ายไฟในตัวอุปกรณ์ ไม่ใช่ USB charger แยก
- Controlled restart: ส่งคำสั่งหนึ่งครั้งแล้วตรวจ 123.7 วินาที; boots 981 → 982, reset reason 3 (software), brownout 746 คงเดิม, health 16 samples ไม่มี timeout/คำสั่ง Restart แทรก และ targets 695 ข้อความ error count 0; ผ่าน
- Controlled OTA จาก public v1.1.0: ตรวจ SHA/commit แล้ว boots 985 → 986, reset reason 3 และ bo 749 คงเดิมในตอนกลับมาออนไลน์ แสดงว่าติดตั้งและรีบูต OTA สำเร็จ
- ระหว่างติดตามหลัง OTA 64.5 วินาที พบ reset reason 9, bo 749 → 753, boots 986 → 991, health timeout 2 ครั้ง และ targets 227 ข้อความ; ไม่พบ Restart/OTA อื่นผ่าน MQTT ในช่วงนี้ จึงไม่ผ่านเกณฑ์ไฟเลี้ยงต่อเนื่อง
- ไม่ปิด brownout detector และไม่ล้าง counters เพื่อกลบผล; กำลังขอทดสอบ ESP32 ผ่าน USB charger เพียงทางเดียว โดยตัดไฟจากชุดจ่ายในอุปกรณ์ที่เข้า ESP32 ก่อน
- Schematic ที่อยู่ใน repository ระบุ LiPo 3.7V, TP4056 และ XL6009; datasheet ของ XL6009 ระบุ operating input 5–32V จึงเป็นข้อสงสัยที่ต้องเทียบกับวงจรจริง ไม่ใช่ผลวัดยืนยันแล้ว: https://www.mouser.com/catalog/specsheets/DFRobot_03312022_DF00123_DC_DC_Converter_XL60091_DataSheet.pdf
- Raw evidence: `controlled-restart-result.json`, `controlled-published-ota-result.json` อยู่ในพื้นที่หลักฐานส่วนตัวของเซิร์ฟเวอร์/Windows ห้ามนำ config/NVS backups มารวมใน Git

## จุดที่พบและแก้ระหว่างทดสอบ

1. Android ไม่เปิด TLS แม้ใช้ port 8883 — เปลี่ยน transport selection ตาม project endpoint
2. MQTT OTA ไม่ส่ง SHA/size ไปตรวจบนชิป — ส่ง metadata และใช้ downloader เดียวกันทั้ง firmware หลัก/bridge
3. Retry CONNECT ไม่ส่งข้อมูลยืนยันตัวตนที่ใช้งานได้ — เกิด `NOT_AUTHORIZED` หลัง broker restart; สร้าง auth จาก immutable snapshot ใหม่ทุก retry
4. Application connection state อัปเดตเฉพาะ initial connection — เพิ่ม lifecycle listener และ subscription restoration โดยไม่ลง callback ซ้ำ
5. WebSocket transport dependencies ไม่อยู่ใน APK — เพิ่มโมดูล HiveMQ websocket ให้ตรง version ของ client
6. Parser เดิมรับ string เฉพาะ compact JSON — คำสั่งมาตรฐานที่มี whitespace เคยทำให้บอร์ดเข้า AP; กู้ค่าจากสำรองสำเร็จ และแก้ parser พร้อมทดสอบซ้ำบนชิปจริง
7. Bridge ไม่ยืนยัน pending OTA image — เพิ่ม confirmation ก่อนรอ WiFi เพื่อให้ OTA รอบถัดไปทำงานได้
8. OTA GET เสี่ยง watchdog และ timeout หน่วยผิด — จำกัด handshake/connect/redirects และคืน watchdog subscription หลัง GET
9. Log การ save config มี credentials — เปลี่ยนเป็นข้อความที่ไม่แสดง body
10. Offline LWT อาจถูกใช้ยืนยัน firmware — ต้องเป็นสถานะ online และรุ่นตรงตามเงื่อนไข

## เกณฑ์ที่ยังรอยืนยัน

- ตรวจและแก้ไฟเลี้ยงของบอร์ด/โมดูล: การตัดไฟระหว่าง OTA และ OTA ซ้ำผ่านแล้ว แต่ยังพบ brownout ในการใช้งานหลังจากนั้น ต้องทดสอบต่อเนื่องหลังแก้สาเหตุ
- ผู้ใช้เดิมได้รับ signed v1.1.0 ผ่าน Check Update และยืนยันเชื่อมต่อได้แล้ว; ประเด็นที่ยังเปิดคือ brownout ช่วงบูตและความต่อเนื่องของ UART หลังเปลี่ยนไฟ
- ชุดส่งต่อรับ implementation และเอกสารถึง `acd67dc` บน branch `dev` แล้ว และตรวจ working tree บนเซิร์ฟเวอร์ว่าง; หลัง hardware gates ผ่านต้องอัปเดตผลสุดท้ายอีกครั้งก่อนปิดงาน

ไม่ได้ทดสอบการตัด WiFi router จริงหรือสร้าง firmware ที่ boot crash โดยจงใจ จึงไม่อ้างว่าครอบคลุมความขัดข้องทางไฟฟ้า/bootloader ทุกแบบ การทดสอบข้างต้นไม่ใช่การประกาศ release ใหม่บน GitHub หรือ rollout ไปทุกอุปกรณ์

## ตำแหน่งหลักฐานและคำสั่งทำซ้ำ

Windows เก็บ raw hardware logs/fixtures ที่ `%USERPROFILE%\.ssh\human-radar-backups` บางไฟล์มีข้อมูลลับ ห้าม commit ทั้งโฟลเดอร์ สำรอง partition/NVS ผ่าน checksum แล้ว แต่ full flash backup เคย checksum ไม่ผ่าน จึงไม่อ้างว่ามี full image backup ที่ใช้กู้คืนได้

Linux native artifacts รอบที่ตรวจ coverage: `/tmp/human-radar-native.gHHhBq` เป็นไฟล์ชั่วคราว สร้างใหม่ได้ด้วย:

```sh
cd /root/project/Human_Radar_V2
bash tools/test-native.sh
```

Android live tests เป็น opt-in ต้องเตรียม fixture ใน private app files ของ emulator/อุปกรณ์ทดสอบเอง ไม่มีรหัสจริงใน test source:

```sh
adb shell am instrument -w \
  -e class com.example.radarhumanapplication.MqttReconnectInstrumentedTest \
  -e brokerFixture mqtt-test-fixture.json -e brokerPort 8883 \
  com.example.radarhumanapplication.test/androidx.test.runner.AndroidJUnitRunner
```

เมื่อเห็น `READY_FOR_BROKER_RESTART` ให้ restart broker ทดสอบเพียงครั้งเดียว การรันบน production ต้องรู้ว่ามี client อื่นได้รับผลกระทบหรือไม่; ใน session นี้มีการประสานขั้นตอนกับอุปกรณ์ทดสอบ
