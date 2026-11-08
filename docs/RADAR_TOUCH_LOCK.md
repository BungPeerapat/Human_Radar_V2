# Radar Touch Lock

## วิธีใช้

บนหน้า Radar แตะไอคอนรูปหน้าจอพร้อมแม่กุญแจในแถวปุ่มด้านบน แล้วหน้าจอจะแสดง “Radar touch locked”

ขณะล็อก Radar ยังรับข้อมูลและวาดภาพตามปกติ แต่การแตะ Radar, ปุ่มควบคุม, bottom navigation และ Back จะไม่เปลี่ยนหน้า ปลดล็อกด้วยการกด “Hold 2 seconds to unlock” ค้างต่อเนื่อง 2 วินาที

การแตะสั้น ยกนิ้วก่อนครบเวลา เลื่อนนิ้วออกนอกปุ่ม ใช้หลายนิ้ว หรือออกจาก foreground จะยกเลิกการกดค้าง ผู้ใช้ TalkBack ปลดล็อกผ่าน accessibility long-click ได้

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

Full Screen เป็นงานแยกใน task 004 ซึ่งผู้ใช้ให้ทำหลัง deploy Touch lock
