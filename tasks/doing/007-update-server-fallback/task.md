# 007 — แก้ Check Update HTTP 504

## เป้าหมาย

GitHub latest/tagged download URLs ตอบ 504 ทั้งจาก Windows และ SSH server แต่ GitHub API ดาวน์โหลด asset ได้ เพิ่ม HTTPS mirror ของโปรเจกต์และ fallback โดยรักษา checksum/signature

## เกณฑ์ตรวจรับ

- [x] Reproduce HTTP 504 และเตรียม signed APK v1.1.4 สำรองบน HTTPS server พร้อมแจ้ง LINE
- [ ] App/firmware/bridge checks fallback ไป mirror เมื่อ project release URL ล้มเหลว; ไม่เปลี่ยน custom channel
- [ ] Mirror sync release สาธารณะผ่าน GitHub API ตรวจ hash/size และสลับชุดไฟล์เมื่อครบเท่านั้น
- [ ] Tests/review, release v1.1.5, ตรวจ public artifacts, push/sync และแจ้ง LINE

## หลักฐาน

Codex; เริ่มจาก 2f160c9 วันที่ 2026-11-08

Bootstrap https://systemdeveloper.ddns.net/human-radar/updates/human-radar-v1.1.4.apk ตรวจ hash/size/signer จาก GitHub เดิมแล้ว Nginx เพิ่มเฉพาะ location ของโปรเจกต์ reload ผ่าน เว็บเดิมยังตอบ 200 และรองรับ Range 206
