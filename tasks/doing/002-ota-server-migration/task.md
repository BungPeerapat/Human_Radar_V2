# 002 — ตรวจ OTA และรักษาการอัปเดตหลังย้าย Server

## เป้าหมาย

อธิบายระบบ OTA ปัจจุบัน ตรวจว่าการย้าย development server และ MQTT broker กระทบอะไร และแก้สิ่งจำเป็นเพื่อให้อุปกรณ์ที่รองรับ OTA ยังอัปเดตผ่านเครือข่ายได้หลังย้าย

## ความสำคัญ

วิกฤต (P0) — OTA ต้องคงเป็นความสามารถหลัก ห้าม rollout ที่ทำให้อุปกรณ์ขาดช่องทางอัปเดต ต้องระบุข้อจำกัดและวิธีกู้คืนตามสภาพจริง

## ข้อมูลและจุดเริ่มต้น

- Android: `app/src/main/java/com/example/radarhumanapplication/update/FirmwareUpdater.java`
- Manifest ปัจจุบันในโค้ด: `https://github.com/BungPeerapat/Human_Radar_V2/releases/latest/download/firmware.json`
- Release pipeline: `.github/workflows/release.yml` สร้าง manifest และเผยแพร่ `.bin` บน GitHub Releases
- MQTT command `ota_pull`: `Human_Radar_V2/mqtt_client.cpp` และ `firmware/src/mqtt_client.cpp`
- Firmware สำรองสำหรับการกู้คืน: `bridge/src/bridge_ota.cpp`, `bridge/src/bridge_mqtt.cpp`
- พบ HTTP push, MQTT pull, การตรวจ SHA-256 ในบางเส้นทาง และ `setInsecure()`; ต้องตรวจแต่ละเส้นทางจริงก่อนสรุปว่ารองรับเท่ากัน

## ขั้นตอน

- [x] ทำแผนภาพตั้งแต่ build/release → manifest → `.bin` → คำสั่ง OTA → download/flash → reboot → ตรวจรุ่นและกลับมาออนไลน์
- [x] แยก dependency ของ MQTT broker, firmware hosting, Android updater และ development server ให้ชัด
- [x] ตรวจ URL, redirects, DNS, HTTPS/CA, credentials, ACL, topic คำสั่ง/ACK และ timeout ของทุกเส้นทาง
- [x] สรุปว่าส่วนใดต้องเปลี่ยนเมื่อย้าย broker และส่วนใดไม่ต้องเปลี่ยนหากยังใช้ GitHub Releases; ไม่ย้าย firmware hosting โดยไม่มีความจำเป็น
- [x] ตรวจ app, firmware เดิม และ bridge ว่าอ่านค่า MQTT จากที่ใด และยังเชื่อมต่อได้หลังอัปเดต/rollback
- [x] ตรวจความแตกต่างของ Arduino/PlatformIO tree และทำให้พฤติกรรม OTA ที่เกี่ยวข้องตรงกัน
- [x] ตรวจ partition layout, ขนาด firmware, heap, image compatibility และการตรวจ integrity/authenticity ในทุกวิธี OTA
- [x] ตรวจ manual rollback จริงและเก็บไฟล์ firmware ทดสอบที่ใช้กู้คืนได้; ไม่อ้าง auto rollback ทุกกรณีหรือ full flash backup ที่ checksum ไม่ผ่าน
- [x] ทดสอบ happy path บน ESP32 จริงผ่าน MQTT ใหม่ รวม ACK/progress, reboot, version verification และ reconnect
- [x] ทดสอบ URL ผิด, download ขาดช่วง, hash ไม่ตรง, image ใหญ่เกิน, HTTPS stream ขาดและ broker restart บนบอร์ดจริง
- [ ] ทดสอบการตัดไฟจริงระหว่าง OTA และการอัปเดตอีกครั้งหลังเสียบไฟกลับ รอผู้ใช้ตามการเรียกผ่าน LINE
- [x] ยืนยันหลังอัปเดต, manual rollback และ bridge recovery ว่ายังสั่ง OTA รอบถัดไปได้ พร้อม WiFi/MQTT settings ที่ใช้งานได้
- [ ] เขียน migration runbook, recovery runbook และ checklist ก่อน release; แก้ปัญหาที่พบก่อนอนุญาต rollout งาน `001`

## เกณฑ์ตรวจรับ

- [x] มีเอกสารอธิบายระบบ OTA ปัจจุบัน พร้อมรายการสิ่งที่ต้องแก้/ไม่ต้องแก้เมื่อย้าย server และเหตุผลจากโค้ด/ผลตรวจ
- [x] ESP32 จริงอัปเดตผ่าน MQTT ใหม่สำเร็จโดยไม่ใช้สาย data ระหว่างกระบวนการปกติ และตรวจรุ่นหลัง reboot ได้
- [x] อัปเดตซ้ำอีกรอบได้หลัง migration เพื่อพิสูจน์ว่าไม่ใช่อัปเดตได้ครั้งเดียวแล้วขาดการเชื่อมต่อ
- [ ] ผลทดสอบกรณีล้มเหลวระบุพฤติกรรมจริงและการกู้คืนได้ ไม่อ้างว่าปลอดภัยจากการอ่านโค้ดเพียงอย่างเดียว
- [x] ระบุชัดว่ากรณีใดต้อง USB เช่น initial provisioning หรือเครื่องที่บูต/เชื่อมต่อไม่ได้
- [x] ไม่มีการปิด broker เดิมหรือลบเส้นทาง OTA เดิม; broker เก่าเสียอยู่ก่อนเริ่มงานตามผู้ใช้แจ้ง

## สิ่งที่ต้องพึ่งพา

เริ่ม audit ได้ทันที; ทดสอบหลังย้ายต้องใช้การตั้งค่า MQTT จากงาน `001`, ESP32 จริง, firmware รุ่นปัจจุบัน/รุ่นใหม่ และช่องทาง USB สำหรับกู้คืนบอร์ดทดสอบ ห้ามทดสอบไฟดับกับอุปกรณ์ใช้งานจริงโดยไม่มีแผนกู้คืน

## บันทึกผล

- 2026-11-08: เพิ่มงานใน `todo`; ข้อมูลข้างต้นเป็นจุดเริ่มจากโค้ด ไม่ใช่ผลยืนยัน OTA บนบอร์ดจริง
- ผู้รับผิดชอบ / commit / รุ่นบอร์ดและ partition / เวอร์ชันก่อนและหลัง / ผลทดสอบ / ปัญหาคงค้าง: บันทึกเมื่อเริ่มทำงาน
- 2026-11-08: Codex เริ่มงานและย้ายเข้า `doing`; implementation checkpoint `775ea41`
- ยืนยัน MQTT OTA: `1.0.61` → test1; HTTP OTA: test1 → test2; manual rollback: test2 → test1; OTA เข้า bridge และกลับ test2 สำเร็จ
- Android updater จริงบน emulator รับ SHA rejection อย่างถูกต้อง แล้วติดตั้ง Linux-server-built `1.1.0-mqtt-server` และตรวจรุ่นสำเร็จใน live test 63.859 วินาที
- ทดสอบ ESP32 ดาวน์โหลด HTTPS/redirect จาก GitHub Releases เดิมครบจนถึง hash check โดยจงใจใช้ hash ผิดเพื่อไม่ downgrade จึงไม่จำเป็นต้องย้าย production firmware hosting
- ยังไม่ย้ายเข้า `done` เพราะรอผลตัดไฟจริง; อ่านรายละเอียดและข้อจำกัดใน `docs/MQTT_OTA_TEST_RESULTS.md`
