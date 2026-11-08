# Radar Touch Lock

## วิธีใช้

บนหน้า Radar แตะไอคอนรูปหน้าจอพร้อมแม่กุญแจในแถวปุ่มด้านบน ขณะล็อกจะแสดงเลข 2 ขนาดเล็กมุมขวาบน แทนกล่องคำแนะนำที่บัง Radar

ขณะล็อก Radar ยังรับข้อมูลและวาดภาพตามปกติ แต่การแตะ Radar, ปุ่มควบคุม, bottom navigation และ Back จะไม่เปลี่ยนหน้า แตะไอคอน Touch lock เพื่อล็อกทันที ปลดล็อกด้วยการกดค้างนิ่งตรงไหนก็ได้บนพื้นที่แอปต่อเนื่อง 2 วินาที รวมพื้นที่เหนือ overlay หรือเมนู

การแตะสั้น ยกนิ้วก่อนครบเวลา เลื่อนนิ้วเกิน touch slop ใช้หลายนิ้ว หรือออกจาก foreground จะยกเลิกการกดค้าง นิ้วที่ปลดล็อกจะถูก consume จนยกออก ไม่กดเมนูต่อ ผู้ใช้ TalkBack ปลดล็อกผ่าน accessibility long-click ของตัวเลขมุมจอได้

ขณะกดค้าง เลขมุมขวาบนนับ 2 → 1 แล้วหายเมื่อปลดล็อกครบ 2 วินาที ถ้ายกเลิกก่อนครบเวลาจะกลับเป็น 2 คำแนะนำยังมีใน accessibility description แต่ไม่มี label ยาวบนจอ

## การคงสถานะ

สถานะล็อกอยู่ต่อเมื่อ activity สร้างใหม่ การแจ้งอัปเดตแอปที่มาถึงขณะล็อกจะรอจนปลดล็อก รวมถึงหลัง activity recreation

ล็อกนี้เปิดได้จาก Radar และคุมการแตะภายในแอป การทำงานของระบบ Android เช่น Home/notification shade เป็นไปตามระบบเดิม

## หลักฐานทดสอบ

- RED: Android UI tests ยืนยันว่ารุ่นเดิมไม่มีปุ่ม Touch lock
- Portrait: touch-lock/password regressions 3 tests ผ่าน 12.56 วินาที
- Landscape: touch-lock/password regressions 4 tests ผ่าน 17.124 วินาที รวม background cancellation
- ใช้ pointer events จริงกดปุ่มที่อยู่หลัง overlay และยืนยันว่าปุ่มหมุน/เปลี่ยนหน้าไม่ทำงาน
- ส่ง frame ทดสอบผ่าน target dispatcher และตรวจว่า Radar stats ยังอัปเดตขณะล็อก
- ตรวจภาพแนวตั้งและหมุนเป็นแนวนอนว่า guard ยังอยู่
- แก้ ClassCastException เดิมขณะสร้าง Dashboard แนวนอนใหม่ โดยให้ช่องชื่ออุปกรณ์เป็น MaterialAutoCompleteTextView ตรงกับโค้ด

ส่งมอบ v1.1.2 / versionCode 96, CI 42448311832 success; ตรวจ public artifacts และ signing certificate แล้ว แจ้งผู้ใช้ผ่าน LINE บน SSH server

Full Screen ส่งมอบแล้วใน v1.1.3 ตาม task 004

## แก้ไขเพิ่มเติม v1.1.4

เพิ่ม input guard ที่ Activity ก่อนส่ง touch events ถึง view ใด ๆ รวม generic motion และ key input ภายในแอป จึงบล็อกปุ่ม/เมนูแม้ view overlay ถูกซ่อนหรือเปลี่ยนลำดับโดยผิดพลาด การแจ้งอัปเดตรอให้ยกนิ้วที่ปลดล็อกก่อนแสดง dialog

RED ยืนยันว่ารุ่นเดิมกดค้างบน canvas ไม่ปลดล็อก GREEN ผ่าน 62 JVM tests และ UI 9 tests ทั้งแนวนอน 30.930 วินาที/แนวตั้ง 29.598 วินาที ครอบคลุม canvas/menu hold, ไม่กดทะลุตอนยกนิ้ว, เลื่อน/หลายจุด, keyboard guard, background, recreation, fullscreen และ password autofill

เผยแพร่ v1.1.4 / versionCode 101 จาก 6bf1ebb ผ่าน Check Update แล้ว CI 42462864452 success ตรวจ public app/firmware/bridge manifests และ binary hash/size ผ่าน APK 8,169,315 bytes, SHA-256 87d1051998a66de5cb5f803223e4c2eb9231b4a1a79acd50099793af456a2d83 ลายเซ็นตรงรุ่นก่อนและตรวจ input guard/รหัสโปรเจกต์ใน APK แล้ว

Push GitHub, sync SSH ผ่าน Tailscale และยืนยันส่ง LINE จาก server journal สำเร็จ การตรวจอาการ beep/บอร์ด offline แยกบันทึกใน docs/BEEP_CONNECTION_DIAGNOSIS.md และ task 006 ยังไม่มีการเปลี่ยน firmware หรือระบบจ่ายไฟ

## ตรวจรายงานเมนูยังกดได้ — v1.1.6

ผู้ใช้ยืนยันว่าข้อความล็อกแสดงบน vivo แต่ยังใช้เมนู Radar ได้ จึงเพิ่มการป้องกันนอกเหนือจาก Activity input dispatch:

- ปิด enabled จริงของ view tree ที่มองเห็น รวม Radar controls และ bottom navigation พร้อมบันทึกค่าเดิม
- View ที่เพิ่มหลังเปิดล็อกถูกปิดผ่าน global layout listener; hidden tabs เก็บ enabled state ของตัวเอง
- ทุกคำสั่งปุ่ม Radar ตรวจสถานะล็อกก่อนทำงาน รวม direct performClick ที่ไม่ผ่าน touch dispatch
- ปลดล็อกแล้วคืนค่า enabled เดิม โดยไม่เปิดปุ่มที่เดิมถูกปิดอยู่ และถอด listener ตอนจบ lifecycle

เพิ่มการตรวจด้วย pointer tap จริงที่ปุ่ม Touch Lock ไม่ใช้เฉพาะ performClick เพื่อเปิดล็อก จากนั้นทดสอบเมนู, direct rotate/snapshot action และการคืนสถานะ ข้อมูลเรดาร์ทดสอบยังอัปเดตผ่าน dispatcher ขณะ view ถูกปิด input

ทดสอบบน emulator ไม่ใช่หลักฐานว่ารายงานเฉพาะ vivo หายแล้ว งาน 008 จะรอผู้ใช้ตรวจรับหลังส่งรุ่นใหม่ ไม่ตรวจเซนเซอร์เพิ่มเติมตามผู้ใช้ยืนยัน

ส่งมอบ v1.1.6 / versionCode 109 จาก 241a3ec, CI 42517639575 success; public mirror sync v1.1.6-05fc86985cdd แล้ว ตรวจ app/firmware/bridge hash/size เทียบ GitHub API ผ่าน APK 8,170,603 bytes, SHA-256 594498f9e5148608cb5c7e0404a16351e23acc94a891799689e531c6375983ab ลายเซ็นตรงรุ่นก่อน และยืนยัน enabled-state fence/guarded actions ใน APK จริง

ส่ง LINE ขอให้ตรวจรับบน vivo และยืนยัน server journal แล้ว Firmware บน ESP32 ที่ตอบ health ล่าสุดยัง 1.1.0 (uptime 1634 s) ไม่ได้สั่ง OTA ในงาน Android นี้

## เลขมุมจอ v1.1.7

ตามคำขอผู้ใช้ เอากล่องคำแนะนำและ tint คลุม Radar ออก ใช้ badge เลข 12sp ขนาด 32dp มุมขวาบน กดค้างนับ 2 → 1 แล้วปลดล็อกเมื่อครบ 2 วินาที ยกเลิกแล้วกลับ 2 คำแนะนำแบบ spoken accessibility และ long-click ยังคงอยู่

69 JVM tests และ UI regressions 11 tests ผ่าน 43.822 s หลังแก้ System UI ANR ของ emulator ตรวจภาพจริงทั้งแนวตั้ง, หมุนแนวนอน, Full Screen และเลข 1 ระหว่างกดค้างด้วย adb motionevent แล้ว Code review APPROVE ไม่มี finding ค้าง
