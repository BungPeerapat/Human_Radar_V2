# 009 — เลขล็อกเล็กมุมขวาบน

## เป้าหมาย

ผู้ใช้เข้าใจระบบล็อกแล้วและขอเอากล่องคำแนะนำที่บัง Radar ออก แทนด้วยตัวเลขเล็กมุมขวาบน

## เกณฑ์ตรวจรับ

- [x] แสดงเฉพาะเลขเล็กมุมขวาบน ไม่มีกล่องคำแนะนำ/แถบยาวบัง Radar
- [x] ขณะล็อกแสดง 2; กดค้างนับ 2 → 1 แล้วปลดล็อกเมื่อครบ 2 วินาที
- [x] ยกเลิกการกดค้างแล้วกลับ 2; controls fence, accessibility และ Full Screen ยังทำงาน
- [ ] ตรวจภาพ/ทดสอบที่เกี่ยวข้อง, review, release, mirror verification, push/sync และ LINE

Codex; เริ่มจาก e79556e วันที่ 2026-11-09 เป็นงาน Android ไม่เปลี่ยน firmware/เซนเซอร์

## ผลตรวจ

- 69 JVM tests/build ผ่าน; UI regressions 11 tests ผ่าน 43.822 s หลังเคลียร์ emulator System UI ANR (ไม่ถือรอบที่ระบบค้างเป็นผลตรวจรับ)
- ตรวจภาพเลข 2/1/ยกเลิกกลับ 2 และมุมจอใน portrait/landscape/fullscreen ด้วย pointer events จริง
- Reviewer APPROVE; ยกเลิก countdown callbacks ทั้งสองตัวเมื่อหยุดกด/เสีย focus/ปิด lifecycle
- รอเผยแพร่ v1.1.7 และตรวจ public mirror/LINE
