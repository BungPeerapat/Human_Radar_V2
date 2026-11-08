# 005 — ล็อก input ทั้งแอปและกดค้างที่ใดก็ได้เพื่อปลดล็อก

## เป้าหมาย

ผู้ใช้รายงานว่ายังกด overlay/เมนูขณะล็อกได้ ต้องบล็อกทุกปุ่มในแอป และกดค้างที่ใดก็ได้ 2 วินาทีเพื่อปลดล็อก

## เกณฑ์ตรวจรับ

- [ ] ดักการแตะระดับ Activity ก่อนส่งถึงปุ่ม, Radar overlays และเมนู
- [ ] กดค้างนิ่งที่ใดก็ได้ครบ 2 วินาทีปลดล็อก; แตะสั้น/เลื่อน/หลายจุด/เข้า background ยกเลิก
- [ ] นิ้วที่ใช้ปลดล็อกไม่กดเมนูต่อเมื่อยกนิ้ว
- [ ] Full Screen, recreation, accessibility และข้อมูล Radar สดยังทำงาน
- [ ] Review, tests, push, sync SSH, release Check Update และแจ้ง LINE

## ผู้รับผิดชอบและหลักฐาน

Codex; เริ่มจาก 238fd47 วันที่ 2026-11-08 ตรวจ input ด้วย pointer events จริงบน Android emulator

## ข้อสังเกต beep/การเชื่อมต่อ

GPIO26 ใช้ร่วม LED+buzzer; MQTT/WSS connect เป็น synchronous และลูปหลักอัปเดต alert หลัง network handler จึงมีโอกาสยืด HIGH ขณะ reconnect พบหลาย TLS handshake ของ HumanRadarBung ใน broker log แต่ยังไม่ยืนยันว่าสาเหตุเดียวกันกับอาการทุกครั้ง ไม่เปลี่ยนระบบจ่ายไฟหรือแฟลชอุปกรณ์ระหว่างแก้แอป
