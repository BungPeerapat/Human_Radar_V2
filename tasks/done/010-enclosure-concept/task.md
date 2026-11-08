# 010 — Concept กล่องอุปกรณ์และโมเดล 3D

## เป้าหมาย

ออกแบบคร่าว ๆ ให้เห็น ESP32, LiPo ทรงแบน 1 เซลล์ และ Radar พร้อม HTML โมเดลหมุน/แยกชิ้นส่วน ภาพ AI concept engineering และส่งลิงก์เข้า LINE

## เกณฑ์ตรวจรับ

- [x] สร้างภาพคอนเซปต์และบันทึก asset/พรอมป์ใน workspace
- [x] HTML 3D ทำงานแบบ local file มีมุมประกอบ/cutaway/exploded และอธิบายชิ้นส่วน
- [x] ขนาดสมมติแยกจากข้อมูลผู้ผลิต ไม่อ้างเป็น CAD พร้อมผลิต
- [x] ตรวจ browser/mobile/export/fallback, review และแก้ findings
- [x] Publish HTTPS, ตรวจ MIME/ไฟล์/เว็บเดิม, push/sync และส่ง LINE

Codex; เริ่มจาก 2f4c63c วันที่ 2026-11-09

ร่าง 78 × 112 × 46 mm, ESP32 และ LiPo เป็นขนาดสมมติ; LD2450 44 × 15 mm และระยะฝาหน้า nominal 12.4 mm อิงคู่มือผู้ผลิต มี GLB หน่วยเมตร ไม่มีการเปลี่ยน firmware/แอป/วงจรจริง

## ผลตรวจ

- Built-in imagegen สร้างภาพ 1586 × 992 px บันทึก PNG และพรอมป์ใน workspace
- Chrome ผ่าน local-file/offline 3D, orbit จริง, modes, slider keyboard, selection, PNG export และ GLB export
- GLB 509,840 bytes, 89 meshes, 97 nodes; root scale 0.001 และ metadata conceptOnly; ไม่มี external buffer
- Mobile 390/320 px ไม่ overflow; forced WebGL failure ยังอ่าน part notes และโหลด static GLB ได้ ปิด controls เฉพาะ 3D ครบ
- แก้ reviewer finding เรื่อง inert fallback controls แล้ว Reviewer APPROVE
- Public HTTPS verification ผ่าน: HTML/JS/CSS/PNG/GLB ตรงกับ local bytes และ MIME ถูกต้อง เว็บเดิมยังตอบ 200
- Mobile-context Chrome ผ่าน touch orbit, exploded, image load และ static GLB download เทียบต้นฉบับ ไม่มี JS errors/overflow
- เผยแพร่ https://systemdeveloper.ddns.net/human-radar/concepts/enclosure-v1/ โดยเพิ่มเฉพาะ Nginx static prefix และ backup config เดิมไว้แล้ว
- Push source/artifacts 2100817 และ sync SSH แล้ว ส่งลิงก์หน้า 3D/ภาพ/Windows HTML path เข้า LINE ผ่าน notify.js บน server ยืนยันจาก journal สำเร็จ
- ตัว HTML เปิด offline ได้; ภาพ PNG, พรอมป์ imagegen และ GLB อยู่ใน docs/concepts/enclosure-v1/ พร้อม editable viewer source
