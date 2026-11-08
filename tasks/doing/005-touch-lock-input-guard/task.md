# 005 — ล็อก input ทั้งแอปและกดค้างที่ใดก็ได้เพื่อปลดล็อก

## เป้าหมาย

ผู้ใช้รายงานว่ายังกด overlay/เมนูขณะล็อกได้ ต้องบล็อกทุกปุ่มในแอป และกดค้างที่ใดก็ได้ 2 วินาทีเพื่อปลดล็อก

## เกณฑ์ตรวจรับ

- [x] ดักการแตะระดับ Activity ก่อนส่งถึงปุ่ม, Radar overlays และเมนู
- [x] กดค้างนิ่งที่ใดก็ได้ครบ 2 วินาทีปลดล็อก; แตะสั้น/เลื่อน/หลายจุด/เข้า background ยกเลิก
- [x] นิ้วที่ใช้ปลดล็อกไม่กดเมนูต่อเมื่อยกนิ้ว
- [x] Full Screen, recreation, accessibility และข้อมูล Radar สดยังทำงาน
- [ ] Review, tests, push, sync SSH, release Check Update และแจ้ง LINE

## ผู้รับผิดชอบและหลักฐาน

Codex; เริ่มจาก 238fd47 วันที่ 2026-11-08 ตรวจ input ด้วย pointer events จริงบน Android emulator

- RED commit 537dc22: canvas hold 2 วินาทียังไม่ปลดล็อกตามพฤติกรรมเดิม
- GREEN: 62 JVM tests ผ่าน; UI 9 tests แนวนอน 30.930 s/แนวตั้ง 29.598 s
- ปรับ keyboard regression ให้ focus ปุ่มหมุนหลังล็อก แล้วตรวจ Enter ไม่เปลี่ยน orientation; focused test ผ่าน 9.140 s
- Review production input guard ผ่าน; รอ release v1.1.4 และตรวจ artifacts

## ข้อสังเกต beep/การเชื่อมต่อ

GPIO26 ใช้ร่วม LED+buzzer; MQTT/WSS connect เป็น synchronous และลูปหลักอัปเดต alert หลัง network handler จึงมีโอกาสยืด HIGH ขณะ reconnect พบหลาย TLS handshake ของ HumanRadarBung ใน broker log แต่ยังไม่ยืนยันว่าสาเหตุเดียวกันกับอาการทุกครั้ง ไม่เปลี่ยนระบบจ่ายไฟหรือแฟลชอุปกรณ์ระหว่างแก้แอป
