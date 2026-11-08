# 004 — แก้ Radar Full Screen ไม่เต็มจอ

## เป้าหมาย

ผู้ใช้รายงานว่าโหมด Full Screen ของหน้า Radar ยังไม่เต็มจอ ให้เริ่มตรวจและแก้หลัง deploy งาน Touch lock เสร็จ

## เกณฑ์ตรวจรับ

- [ ] ตรวจพื้นที่ว่างและ chrome ที่ยังเหลือทั้งแนวตั้ง/แนวนอน รวม system status/navigation bars และ app navigation
- [ ] เมื่อเข้า Full Screen ให้ Radar ใช้พื้นที่หน้าจอตามโหมด immersive โดยไม่เหลือช่องจาก insets เดิม
- [ ] ออกจาก Full Screen แล้วคืน toolbar, navigation, insets และขนาด Radar ถูกต้อง
- [ ] มีวิธีใช้/ปลด Touch lock ในหน้า Radar โดยไม่ทำให้ผู้ใช้ติดอยู่ใน Full Screen
- [ ] ตรวจการหมุนจอ, activity recreation และ gesture navigation
- [ ] ทดสอบ UI จริง, review, push GitHub, sync SSH และ release ผ่าน Check Update
- [ ] แจ้งผู้ใช้ผ่าน LINE บน SSH server

## ลำดับงาน

เริ่มดำเนินการหลัง v1.1.2 Touch lock เผยแพร่และตรวจ public artifacts สำเร็จแล้ว

รอ deploy งาน 003 ก่อน แล้วดำเนินการต่อได้ตามผู้ใช้อนุญาต ไม่รวมการแก้ระบบ Orientation lock ที่ผู้ใช้ยืนยันว่าใช้งานได้
