# 007 — แก้ Check Update HTTP 504

## เป้าหมาย

GitHub latest/tagged download URLs ตอบ 504 ทั้งจาก Windows และ SSH server แต่ GitHub API ดาวน์โหลด asset ได้ เพิ่ม HTTPS mirror ของโปรเจกต์และ fallback โดยรักษา checksum/signature

## เกณฑ์ตรวจรับ

- [x] Reproduce HTTP 504 และเตรียม signed APK v1.1.4 สำรองบน HTTPS server พร้อมแจ้ง LINE
- [x] App/firmware/bridge checks fallback ไป mirror เมื่อ project release URL ล้มเหลว; ไม่เปลี่ยน custom channel
- [x] Mirror sync release สาธารณะผ่าน GitHub API ตรวจ hash/size และสลับชุดไฟล์เมื่อครบเท่านั้น
- [x] Tests/review, release v1.1.5, ตรวจ public artifacts, push/sync และแจ้ง LINE

## หลักฐาน

Codex; เริ่มจาก 2f160c9 วันที่ 2026-11-08

Bootstrap https://systemdeveloper.ddns.net/human-radar/updates/human-radar-v1.1.4.apk ตรวจ hash/size/signer จาก GitHub เดิมแล้ว Nginx เพิ่มเฉพาะ location ของโปรเจกต์ reload ผ่าน เว็บเดิมยังตอบ 200 และรองรับ Range 206

- RED a164bcf: เพิ่มข้อกำหนด fallback/validation tests ก่อน implementation
- GREEN: 69 JVM tests ผ่าน; Android live fallback + manual check และ regressions รวม 11 tests ผ่าน 62.075 s
- หลังเพิ่ม binary-only fallback ทดสอบ Android เฉพาะ 2 tests ผ่าน 25.758 s; logcat ยืนยัน primary APK ล้มเหลวแล้ว mirror download/hash สำเร็จ
- Mirror fault tests ผ่าน: valid publish, corrupted download ไม่ทับชุดเดิม, next release, reject downgrade
- แก้ review finding ให้ latest.apk อยู่ใน snapshot และ root ชี้ current/latest.apk คงที่ก่อนส่งมอบ
- Code review ผ่าน ไม่มี finding ค้าง; binary fallback test ใช้ release ที่ไม่มีอยู่เพื่อบังคับเส้นทางสำรอง แม้ GitHub กลับมาปกติแล้ว (ผ่าน 13.259 s)
- Sync source 9d7e66c ไป SSH แล้ว ติดตั้ง/enable timer 5 นาทีและ service รันสำเร็จโดยใช้ v1.1.4
- CI 42466437764 หยุดก่อน compile เพราะ Gradle distribution redirect timeout; เพิ่ม API fallback ที่ตรวจ official Gradle 8.9 checksum แล้ว (136,114,148 bytes, SHA ตรง)
- CI 42466883878 success; เผยแพร่ v1.1.5 / versionCode 105 จาก 1457cfc
- Mirror service sync v1.1.5-162c1e919f30 สำเร็จ และ timer active/enabled
- ตรวจ public mirror app/firmware/bridge manifests เทียบ GitHub API และ binary hash/size ผ่าน; latest.apk ตรง v1.1.5, signer ตรงรุ่นก่อน
- APK 8,170,067 bytes, SHA-256 ac9ad7e2d86a7f8635fc39406bcb47c9e853fa596c739f97f5250b25ed168a9e; ตรวจ fallback/Touch lock/รหัสโปรเจกต์ใน APK แล้ว
- Push/sync SSH และยืนยัน LINE ลิงก์พร้อมติดตั้งจาก server journal แล้ว
- แอปเดิมต้องติดตั้ง APK จาก server ทับหนึ่งครั้งระหว่าง GitHub download outage; รุ่นใหม่มี fallback อัตโนมัติ
