# 008 — ล็อกเมนู Radar จริงเมื่อแสดงสถานะ Touch locked

## รายงานผู้ใช้

ผู้ใช้ยืนยันว่าข้อความ Radar touch locked แสดงแล้วแต่ยังกดเมนูได้ ให้หยุดตรวจเซนเซอร์ซึ่งผู้ใช้ยืนยันว่าทำงานปกติ Firmware บนบอร์ดยัง 1.1.0; การแก้รอบนี้เป็น Android

## เกณฑ์ตรวจรับ

- [x] เปิด Touch lock ด้วย pointer tap จริงและทดสอบทุกเมนู/overlay
- [x] ปิด enabled ของ controls จริง พร้อม guard คำสั่ง Radar แม้ bypass touch dispatch
- [x] ครอบคลุม view ที่สร้างภายหลัง/recreation และคืน enabled state เดิมหลังปลดล็อก
- [x] กดค้าง 2 วินาทีปลดล็อก; Radar ยังอัปเดตข้อมูล; Full Screen ใช้ได้
- [x] Review, tests, release, public mirror verification, push/sync และ LINE
- [ ] ผู้ใช้ตรวจรับบน vivo ว่าเมนู/ปุ่ม Radar ไม่ทำงานขณะข้อความล็อกแสดงอยู่

## ผู้รับผิดชอบ

Codex; เริ่มจาก c714728 วันที่ 2026-11-09 ยังต้องให้ผู้ใช้ตรวจรับบน vivo หลังส่ง release

## หลักฐาน

- RED ae52191: control fence test ยืนยัน controls เดิมยัง enabled ขณะล็อก (ไม่อ้างว่าเป็นการ reproduce สาเหตุเฉพาะ vivo)
- แก้สภาพแวดล้อม emulator System UI ANR ด้วย cold boot ก่อนใช้ผล pointer tests ตรวจรับ
- GREEN: 69 JVM tests/build ผ่าน; UI 11 tests แนวตั้งผ่าน 40.336 s รวม physical tap, command guard, original-state/late-child restoration และ regressions
- UI 11 tests แนวนอนผ่าน 42.072 s; reviewer APPROVE ไม่มี finding ค้าง
- Firmware บนบอร์ดยังเป็น 1.1.0; ไม่สั่ง OTA/restart และไม่ตรวจเซนเซอร์ตามคำสั่งผู้ใช้
- ส่งมอบ v1.1.6 / versionCode 109 จาก 241a3ec, CI 42517639575 success; mirror v1.1.6-05fc86985cdd sync ผ่าน
- Public app/firmware/bridge manifests และ binary hash/size เทียบ GitHub API ผ่าน; latest.apk ตรงรุ่นและ production signer เดิม
- APK 8,170,603 bytes, SHA-256 594498f9e5148608cb5c7e0404a16351e23acc94a891799689e531c6375983ab; ตรวจ enabled-state fence/guarded actions/รหัสโปรเจกต์ใน APK แล้ว
- Push/sync และ LINE ขอผู้ใช้ตรวจรับบน vivo ยืนยันจาก server journal แล้ว จึงคง doing จนมีผลตรวจรับ
- health หลัง release ยืนยันบอร์ดยัง firmware 1.1.0, uptime 1634 s ไม่ได้ OTA อัตโนมัติตามเวอร์ชัน release
