# 006 — ยืนยันสาเหตุ LED/beep ยาวและเชื่อมต่อเป็นช่วง

## เป้าหมาย

เก็บหลักฐานเวลาจริงเพื่อแยก pattern ปกติ, config จังหวะยาว และ loop ที่รอ network ระหว่างขา HIGH

## หลักฐานปัจจุบัน

รายละเอียด docs/BEEP_CONNECTION_DIAGNOSIS.md; broker TLS/WSS round-trip ผ่าน แต่บอร์ดไม่ตอบ health ในช่วงตรวจ สถานะ online เป็น retained จึงยังสรุปการออนไลน์ไม่ได้

## ขั้นตอน

- [x] ยืนยันอาการต่อไม่ได้ว่าเป็น app→broker หรือ ESP32→broker และบอร์ดเปิดใช้งาน
- [ ] เก็บ health/get_log_buffer และ alert config แบบอ่านอย่างเดียวเมื่อบอร์ดตอบ
- [ ] เทียบเวลา beep ค้างกับ network wait/reconnect และ uptime
- [ ] หากยืนยัน timing bug ให้เพิ่ม regression แล้วแยก GPIO timing จาก synchronous network waits โดยรักษา OTA

ยังไม่มีการเปลี่ยน firmware หรือระบบจ่ายไฟจากสมมติฐาน

ผู้ใช้ยืนยันแอปต่อได้ แต่บอร์ดไม่ออนไลน์แม้เปิดอยู่ ตรวจ logging ชั่วคราว 45 วินาทีแล้วไม่พบ connection ใหม่จากบอร์ด และไม่ได้ health ACK คืน config logging เดิมแล้วโดยไม่ restart broker รอข้อมูลว่า WiFi/ฮอตสปอตเดิมยังเปิดอยู่และเสียงเป็นจังหวะใด
