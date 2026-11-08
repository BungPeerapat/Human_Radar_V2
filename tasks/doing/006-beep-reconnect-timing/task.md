# 006 — ยืนยันสาเหตุ LED/beep ยาวและเชื่อมต่อเป็นช่วง

## เป้าหมาย

เก็บหลักฐานเวลาจริงเพื่อแยก pattern ปกติ, config จังหวะยาว และ loop ที่รอ network ระหว่างขา HIGH

## หลักฐานปัจจุบัน

รายละเอียด docs/BEEP_CONNECTION_DIAGNOSIS.md; broker TLS/WSS round-trip ผ่าน แต่บอร์ดไม่ตอบ health ในช่วงตรวจ สถานะ online เป็น retained จึงยังสรุปการออนไลน์ไม่ได้

## ขั้นตอน

- [x] ยืนยันอาการต่อไม่ได้ว่าเป็น app→broker หรือ ESP32→broker และบอร์ดเปิดใช้งาน
- [x] เก็บ health/get_log_buffer แบบอ่านอย่างเดียวเมื่อบอร์ดตอบ
- [ ] อ่าน alert config ปัจจุบันเมื่อเข้าถึงได้
- [ ] เทียบเวลา beep ค้างกับ network wait/reconnect และ uptime
- [ ] หากยืนยัน timing bug ให้เพิ่ม regression แล้วแยก GPIO timing จาก synchronous network waits โดยรักษา OTA

ยังไม่มีการเปลี่ยน firmware หรือระบบจ่ายไฟจากสมมติฐาน

ผู้ใช้ยืนยันแอปต่อได้ แต่บอร์ดไม่ออนไลน์แม้เปิดอยู่ ตรวจ logging ชั่วคราว 45 วินาทีแล้วไม่พบ connection ใหม่จากบอร์ด และไม่ได้ health ACK คืน config logging เดิมแล้วโดยไม่ restart broker รอข้อมูลว่า WiFi/ฮอตสปอตเดิมยังเปิดอยู่และเสียงเป็นจังหวะใด

## ตรวจซ้ำ 2026-11-09

ผู้ใช้แจ้งว่าแบตเตอรี่อ่อนและเปลี่ยนแหล่งจ่ายเสถียร จากนั้นเปิดเน็ตใหม่ บอร์ดตอบ health ครบ 3 รอบ (uptime 32/40/48 s, boots 1797, brownout total 1492 คงที่, reset 1) และ get_log_buffer สำเร็จที่ประมาณ 83.9 s WiFi/MQTT กลับมาทำงานแล้ว แต่ยังไม่มี targets สดและยังไม่ได้ยืนยันอาการ beep หลังแก้ไฟ จึงคง doing ไม่สั่ง reboot/OTA หรือเปลี่ยน config

รายละเอียดและข้อควรตีความหน่วยเวลาใน log: docs/BEEP_CONNECTION_DIAGNOSIS.md

ผู้ใช้ยืนยันภายหลังว่า Sensor ทำงานปกติและไม่ต้องตรวจต่อ จึงหยุดตรวจ targets/UART ตามคำสั่งนั้น ความสำคัญรอบนี้อยู่ที่สถานะ firmware และแก้ Radar menu lock ในงาน 008
