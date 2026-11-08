# 009 — เลขล็อกเล็กมุมขวาบน

## เป้าหมาย

ผู้ใช้เข้าใจระบบล็อกแล้วและขอเอากล่องคำแนะนำที่บัง Radar ออก แทนด้วยตัวเลขเล็กมุมขวาบน

## เกณฑ์ตรวจรับ

- [x] แสดงเฉพาะเลขเล็กมุมขวาบน ไม่มีกล่องคำแนะนำ/แถบยาวบัง Radar
- [x] ขณะล็อกแสดง 2; กดค้างนับ 2 → 1 แล้วปลดล็อกเมื่อครบ 2 วินาที
- [x] ยกเลิกการกดค้างแล้วกลับ 2; controls fence, accessibility และ Full Screen ยังทำงาน
- [x] ตรวจภาพ/ทดสอบที่เกี่ยวข้อง, review, release, mirror verification, push/sync และ LINE

Codex; เริ่มจาก e79556e วันที่ 2026-11-09 เป็นงาน Android ไม่เปลี่ยน firmware/เซนเซอร์

## ผลตรวจ

- 69 JVM tests/build ผ่าน; UI regressions 11 tests ผ่าน 43.822 s หลังเคลียร์ emulator System UI ANR (ไม่ถือรอบที่ระบบค้างเป็นผลตรวจรับ)
- ตรวจภาพเลข 2/1/ยกเลิกกลับ 2 และมุมจอใน portrait/landscape/fullscreen ด้วย pointer events จริง
- Reviewer APPROVE; ยกเลิก countdown callbacks ทั้งสองตัวเมื่อหยุดกด/เสีย focus/ปิด lifecycle
- เผยแพร่ v1.1.7 / versionCode 111 จาก e86bcf0, CI 42520243165 success; mirror v1.1.7-baa00d3cad87 sync ผ่าน
- Public app/firmware/bridge manifests และ binary hash/size เทียบ GitHub API ผ่าน; latest.apk ตรงรุ่นและ signer เดิม
- APK 8,169,923 bytes, SHA-256 8405981d53a01f9e0c998e64dfb0e68939af8fd00e96b4df203e5cb95142e10d; ตรวจ release layout และ countdown/fence/รหัสโปรเจกต์ใน APK แล้ว
- Push/sync และ LINE แจ้งพร้อม Check Update ยืนยันจาก server journal แล้ว
