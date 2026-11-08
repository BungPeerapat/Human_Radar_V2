# Radar Full Screen

## วิธีใช้

แตะพื้นที่ Radar เพื่อเข้า Full Screen แถบสถานะของแอปและ bottom navigation จะซ่อน พร้อมแถบสถานะ/แถบนำทางของ Android กดปุ่ม X ด้านบนหรือ Back เพื่อกลับสู่หน้าปกติ

Touch lock ใช้ร่วมกับ Full Screen ได้ ต้องปลดล็อกด้วยการกดค้าง 2 วินาทีก่อนจึงออกด้วย Back หรือ X ได้ สถานะทั้งสองคงอยู่เมื่อ Activity สร้างใหม่

Android อาจแสดงคำแนะนำการใช้งานเต็มจอครั้งแรก และเรียกแถบระบบชั่วคราวได้ด้วยการปัดขอบจอตามพฤติกรรมระบบ

## สิ่งที่แก้

- ใช้ immersive system bars และตัด padding จาก system insets ขณะเต็มจอ คืนค่าตอนออก
- RadarView เคารพความกว้าง EXACTLY ที่ parent กำหนด แทนการย่อหน้าต่างวาดในจอกว้าง
- เพิ่มปุ่มออก Full Screen และซ่อน device chip ที่ชนกับแถวปุ่มควบคุม
- คงมุม ระยะ และสัดส่วนวงเรดาร์เดิม พื้นที่วาดเต็มหน้าต่าง แต่แนวตั้งยังมีพื้นที่เหนือวงเรดาร์ตามรูปทรงครึ่งวงกลม

## หลักฐานทดสอบ

- RED: รุ่นเดิมไม่ซ่อน system bars/insets และวัด Radar กว้าง 600 แทน 1,400 พิกเซล
- GREEN: JVM tests/build ผ่าน; UI 7 tests แนวนอนผ่าน 25.573 วินาที แนวตั้งผ่าน 22.562 วินาที
- ตรวจ actual window insets, viewport bounds, คืนหน้าปกติ, recreation ร่วมกับ Touch lock, Back priority และ password autofill
- ตรวจภาพจริงทั้งแนวตั้งและหลังหมุนเป็นแนวนอนบน API 36 ที่ใช้ gesture navigation
- Code review ผ่าน ไม่มี finding ค้าง

ใช้แนวทาง [Android immersive mode](https://developer.android.com/develop/ui/views/layout/immersive)

## การส่งมอบ

เผยแพร่ผ่าน Check Update แล้วเป็น v1.1.3 / versionCode 98 จาก commit 8e59159; CI 42449553105 success

ตรวจ public app/firmware/bridge manifests และ binary hash/size ผ่าน APK 8,168,971 bytes, SHA-256 fa5bd156f3ce1e068dba62908fb74b5686aee48e510d9342ce868d2d061c68b9 ลายเซ็นตรงรุ่นก่อน และตรวจว่ามีรหัสโปรเจกต์กับ Touch lock ใน APK แล้ว

Push GitHub, sync ผ่าน Tailscale ไป SSH server และยืนยันการส่ง LINE จาก server journal สำเร็จ รุ่นนี้ตรวจการเปลี่ยนแปลงฝั่ง Android บน emulator; ยังไม่มีผลยืนยัน Full Screen จากโทรศัพท์ผู้ใช้
