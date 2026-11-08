# 008 — ล็อกเมนู Radar จริงเมื่อแสดงสถานะ Touch locked

## รายงานผู้ใช้

ผู้ใช้ยืนยันว่าข้อความ Radar touch locked แสดงแล้วแต่ยังกดเมนูได้ ให้หยุดตรวจเซนเซอร์ซึ่งผู้ใช้ยืนยันว่าทำงานปกติ Firmware บนบอร์ดยัง 1.1.0; การแก้รอบนี้เป็น Android

## เกณฑ์ตรวจรับ

- [ ] เปิด Touch lock ด้วย pointer tap จริงและทดสอบทุกเมนู/overlay
- [ ] ปิด enabled ของ controls จริง พร้อม guard คำสั่ง Radar แม้ bypass touch dispatch
- [ ] ครอบคลุม view ที่สร้างภายหลัง/recreation และคืน enabled state เดิมหลังปลดล็อก
- [ ] กดค้าง 2 วินาทีปลดล็อก; Radar ยังอัปเดตข้อมูล; Full Screen ใช้ได้
- [ ] Review, tests, release, public mirror verification, push/sync และ LINE

## ผู้รับผิดชอบ

Codex; เริ่มจาก c714728 วันที่ 2026-11-09 ยังต้องให้ผู้ใช้ตรวจรับบน vivo หลังส่ง release
