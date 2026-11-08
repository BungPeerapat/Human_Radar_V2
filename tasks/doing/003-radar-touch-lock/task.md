# 003 — ล็อกการแตะหน้า Radar

## เป้าหมาย

เพิ่ม Touch lock จากหน้า Radar เพื่อป้องกันการแตะปุ่ม/เปลี่ยนหน้าโดยไม่ตั้งใจ ขณะที่ข้อมูลเรดาร์ยังแสดงสด ผู้ใช้ยืนยันว่า Orientation lock ใช้งานได้แล้ว

## เกณฑ์ตรวจรับ

- [x] มีปุ่มเปิด Touch lock บนหน้า Radar ทั้งแนวตั้งและแนวนอน
- [x] เมื่อเปิดล็อก การแตะ Radar, ปุ่มควบคุม และ bottom navigation ไม่ทำงาน
- [x] มีข้อความบอกสถานะ/วิธีปลดล็อกที่เห็นชัด; แตะสั้นหรือกดค้างไม่ครบเวลาไม่ปลดล็อก
- [x] กดปุ่มปลดล็อกค้าง 2 วินาทีแล้วใช้งานแอปต่อได้; accessibility มีวิธีปลดล็อก
- [x] สถานะล็อกอยู่ต่อเมื่อ activity ถูกสร้างใหม่; ยกเลิกการกดค้างเมื่อเสีย focus/เข้า background
- [x] ทดสอบด้วย Android pointer events จริง รวมการกดปุ่มที่อยู่หลัง overlay และการยกเลิก hold
- [ ] Review, build/tests, push GitHub, sync SSH และ release ให้ Check Update ได้
- [ ] แจ้งผู้ใช้ผ่าน LINE บน SSH server

## ขอบเขต

เป็นการล็อกการแตะภายในแอปขณะใช้หน้า Radar ไม่เปลี่ยนพฤติกรรม Orientation lock, MQTT หรือ OTA และไม่ใช่ kiosk mode ของระบบ Android

## บันทึกผล

- Portrait 3 UI tests ผ่าน 12.56 วินาที; Landscape 4 UI tests ผ่าน 17.124 วินาที รวม Password autofill regression
- Reviewer ตรวจ controller, lifecycle, deferred updater และ layout แล้ว ไม่มี finding ค้าง
- รายละเอียด: docs/RADAR_TOUCH_LOCK.md

- เริ่มจาก source `42d8a0b`; เพิ่ม opt-in Android UI regression ก่อน implementation
