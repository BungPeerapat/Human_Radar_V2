# 001 — ใช้ MQTT Server ใหม่เป็นระบบหลัก

## เป้าหมาย

ให้ Android, ESP32 และ bridge firmware ใช้ broker ของโปรเจกต์บน `systemdeveloper.ddns.net` เป็นค่าเริ่มต้น โดยอุปกรณ์เดิมยังย้ายตามได้และไม่สูญเสียความสามารถ OTA

## ความสำคัญ

สูง — ต้องผ่านการตรวจเส้นทาง OTA จากงาน `002` ก่อน rollout จริง

## ข้อมูลและจุดเริ่มต้น

- MQTT TLS: `systemdeveloper.ddns.net:8883`
- MQTT WSS: `systemdeveloper.ddns.net:9002` path `/mqtt`
- บัญชี `humanradar`; สิทธิ์ `humanradar/#`; อ้างอิง secret จาก `docs/REMOTE_DEVELOPMENT.md`
- Broker อยู่ `/root/project/MQTT_Server`; plain TCP/WS เปิดเฉพาะ loopback
- พบค่า broker เดิม `119.59.99.155` ใน `DashboardFragment.java` และ `config_manager.cpp` ทั้งสอง firmware tree
- ค่า MQTT ที่บันทึกใน Android preferences และ ESP32 NVS อาจแทนที่ค่าเริ่มต้นใหม่ ต้องออกแบบ migration ให้ครอบคลุม

## ขั้นตอน

- [x] สำรวจ host, port, protocol, credentials และการบันทึกค่าของ Android, firmware และ bridge ให้ครบ
- [x] เปลี่ยนค่าเริ่มต้นเป็น hostname ใหม่ พร้อม TLS และตรวจความสอดคล้องของ port/protocol
- [x] วางวิธีส่ง credentials ให้ผู้ใช้/อุปกรณ์โดยไม่ฝังรหัสจริงลง Git
- [x] ตรวจการยืนยันใบรับรอง TLS รวมถึง CA, เวลาอุปกรณ์ และพฤติกรรมเมื่อใบรับรองต่ออายุ; ประเมินการใช้ `setInsecure()` ที่พบใน firmware
- [x] ออกแบบ migration สำหรับ preferences/NVS เดิม โดยไม่เขียนทับ custom broker ของผู้ใช้โดยพลการ
- [x] วางลำดับอัปเดตและการกลับไปใช้ broker เดิมสำหรับอุปกรณ์ที่ยังไม่ย้าย เพื่อไม่ให้ตัดช่องทาง OTA
- [x] แก้ Arduino tree `Human_Radar_V2/` และ PlatformIO tree `firmware/` ให้ตรงกัน; ตรวจ `bridge/` ด้วย
- [x] ทดสอบ Android ↔ MQTT ↔ ESP32: discovery, status/LWT, config, commands และ ACK
- [x] ยืนยัน targets จาก HLK-LD2450 จริงผ่าน MQTT ถึง Android emulator: 112 ข้อความใน 20 วินาที และหน้า Radar frame counter เพิ่ม; ยังมี brownout ต้องแก้ก่อนรับรองการทำงานต่อเนื่อง
- [x] ทดสอบ transport interruption, broker restart และ ESP32 reboot; ยังไม่ได้ตัด WiFi router จริง
- [x] ทดสอบ WSS หากยังประกาศรองรับ และตรวจว่า topic ที่ระบบใช้ทั้งหมดผ่าน ACL ใหม่
- [x] ทดสอบ OTA ผ่าน broker ใหม่, public GitHub release v1.1.0 และอัปเดตคู่มือการตั้งค่า/rollback; ไฟเลี้ยง startup ยังรอตรวจ

## เกณฑ์ตรวจรับ

- [x] การตั้งค่าใหม่เชื่อมต่อ MQTT ใหม่ได้จริง และค่า broker เดิมที่ยังเหลือมีเหตุผลระบุชัดเจน
- [x] อุปกรณ์ที่มี preferences/NVS เดิมย้ายได้ตามขั้นตอน โดยคง WiFi, device name และค่าผู้ใช้ที่ไม่เกี่ยวข้อง
- [x] แอป Android ที่รันจริงบน emulator API 36 และ ESP32 จริงส่ง/รับคำสั่ง/ACK และข้อมูล discovery/targets ได้; ผู้ใช้ยืนยัน signed v1.1.0 ต่อ MQTT ได้แล้ว
- [x] ผู้ใช้ไม่ระบุบัญชีหรือใช้รหัสผิดถูกปฏิเสธ และสิทธิ์ topic ถูกต้อง
- [ ] OTA และการกู้คืนหลังการย้ายผ่านเกณฑ์ของงาน `002` ก่อนเปิดใช้จริงทั้งหมด
- [x] ไม่มี secret ใหม่ใน repository และมีคำแนะนำเลือก TLS สำหรับพอร์ต `8883`

## สิ่งที่ต้องพึ่งพา

Broker ใหม่ติดตั้งแล้ว แต่ยังต้องมี Android/ESP32 จริงสำหรับตรวจรับ; ผล audit OTA จากงาน `002` เป็นเงื่อนไขก่อน rollout ไม่ขัดขวางการพัฒนาและทดสอบ MQTT ใน staging

## บันทึกผล

- 2026-11-08: เพิ่มงานใน `todo`; ยังไม่ได้แก้ระบบ MQTT ของแอปหรืออุปกรณ์
- ผู้รับผิดชอบ / commit / ผลทดสอบ / ปัญหาคงค้าง: บันทึกเมื่อเริ่มทำงาน
- 2026-11-08: Codex เริ่มงานและย้ายเข้า `doing`; implementation checkpoint `775ea41` บน `dev`
- 62 JVM tests ผ่าน; live Android TLS/WSS reconnect tests ผ่านและ health command/ACK ฟื้นหลัง restart broker จริง
- บอร์ด `HumanRadarBung` MAC `08:d1:f9:34:91:b4` ใช้ broker ใหม่แล้ว; firmware ล่าสุดที่ยืนยัน `1.1.0-mqtt-server` build จากเซิร์ฟเวอร์ Linux
- รอบแรก MQTT config command `publish_interval_ms=100` ได้ ACK `ok` แต่ยังไม่พบ targets; ผลรอบถัดไปอยู่ท้ายบันทึก
- หลักฐานละเอียด: `docs/MQTT_OTA_TEST_RESULTS.md`; ขั้นตอนส่งต่อ: `docs/SERVER_HANDOFF.md`

- หลังผู้ใช้เสียบ USB ใหม่ พบ targets จริงและแอปทดสอบต่อ broker ได้; แอปเดิมในมือถือยัง reset by peer รออัปเดตผ่าน signed release/Check Update และตรวจซ้ำ
- ยังคง doing เนื่องจากไฟเลี้ยง brownout; signed app release ส่งมอบและผู้ใช้ตรวจรับการเชื่อมต่อแล้ว

- เผยแพร่ App v1.1.0 versionCode 91 ผ่าน Check Update ด้วยคีย์เซ็นเดิมแล้ว ผู้ใช้ยืนยันเชื่อมต่อ MQTT ได้
- UART/targets หลัง restart โมดูลผ่าน 180 ข้อความ/32 วินาที; ยังรอไฟเลี้ยงหลัง OTA startup ที่พบ brownout เพิ่ม 2 ครั้งก่อนปิด rollout gate

- Controlled restart ผ่าน 123.7 วินาที/695 targets แต่ controlled OTA หลังติดตั้งสำเร็จยังพบ brownout เพิ่ม 4 ครั้งโดยไม่พบ MQTT Restart แทรก; ผู้ใช้ยืนยันใช้ชุดจ่ายไฟในตัวอุปกรณ์ รอแยกทดสอบ USB charger ทางเดียวก่อนปิด gate
