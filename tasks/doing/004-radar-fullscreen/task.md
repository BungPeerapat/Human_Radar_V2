# 004 — แก้ Radar Full Screen ไม่เต็มจอ

## เป้าหมาย

ผู้ใช้รายงานว่าโหมด Full Screen ของหน้า Radar ยังไม่เต็มจอ ให้เริ่มตรวจและแก้หลัง deploy งาน Touch lock เสร็จ

## เกณฑ์ตรวจรับ

- [x] ตรวจพื้นที่ว่างและ chrome ที่ยังเหลือทั้งแนวตั้ง/แนวนอน รวม system status/navigation bars และ app navigation
- [x] เมื่อเข้า Full Screen ให้ Radar ใช้พื้นที่หน้าจอตามโหมด immersive โดยไม่เหลือช่องจาก insets เดิม
- [x] ออกจาก Full Screen แล้วคืน toolbar, navigation, insets และขนาด Radar ถูกต้อง
- [x] มีวิธีใช้/ปลด Touch lock ในหน้า Radar โดยไม่ทำให้ผู้ใช้ติดอยู่ใน Full Screen
- [x] ตรวจการหมุนจอ, activity recreation และ gesture navigation
- [ ] ทดสอบ UI จริง, review, push GitHub, sync SSH และ release ผ่าน Check Update
- [ ] แจ้งผู้ใช้ผ่าน LINE บน SSH server

## ลำดับงาน

เริ่มดำเนินการหลัง v1.1.2 Touch lock เผยแพร่และตรวจ public artifacts สำเร็จแล้ว

งาน 003 deploy แล้ว จึงเริ่มต่อได้ตามผู้ใช้อนุญาต ไม่รวมการแก้ระบบ Orientation lock ที่ผู้ใช้ยืนยันว่าใช้งานได้

## บันทึกผล

- ผู้รับผิดชอบ: Codex; เริ่มจาก 01a96e5 หลัง v1.1.2 เผยแพร่แล้ว
- RED ยืนยัน system bars/insets ค้างและ RadarView ย่อความกว้าง 1,400 เหลือ 600 พิกเซล
- GREEN: JVM tests/build ผ่าน; UI 7 tests แนวนอน 25.573 วินาที และแนวตั้ง 22.562 วินาที
- ตรวจภาพการหมุนจอจริง, gesture navigation, fullscreen + touch lock recreation และ Back priority
- Reviewer ตรวจแล้ว APPROVE ไม่มี finding ค้าง; รายละเอียด docs/RADAR_FULLSCREEN.md
- รอ release v1.1.3, ตรวจ artifacts, sync SSH และแจ้ง LINE ก่อนย้าย done
