# LED/Buzzer และการเชื่อมต่อ — 2026-11-08

## สิ่งที่ยืนยันจากโค้ด

- GPIO26 ขับ LED และ active buzzer ร่วมกัน จึงมีเสียงจากรูปแบบ WiFi ด้วย ไม่ใช่เฉพาะตรวจพบคน (firmware/include/alert_pattern.h)
- ขณะกำลังต่อ/หลุด WiFi เปิด 1 วินาที ปิด 1 วินาทีซ้ำ; เมื่อต่อสำเร็จ pulse 250 ms สี่ครั้ง
- จำนวนเป้าหมายลดลงมี long beep ค่าเริ่มต้น 800 ms และตั้งค่าได้ถึง 10,000 ms ผ่าน config API; ต้องตรวจค่าที่บอร์ดใช้อยู่ก่อนตัดสินว่าผิดปกติ
- alertPattern.update() อยู่ท้าย loop หลัง webServer.loop() และ mqttClient.loop() หาก network handler รอนานขณะ GPIO เป็น HIGH จังหวะปิดเสียงจะช้าตามไปด้วย
- WSS connect วนรอได้ 8 วินาที และ PubSubClient มี synchronous socket waits จึงเป็นเส้นทางที่อธิบายเสียงยืดพร้อมอาการ reconnect ได้ แต่ยังไม่มี timestamp/trace จากอาการจริงมายืนยัน

อ้างอิง timeout: [PubSubClient API](https://pubsubclient.knolleary.net/api)

## ผลตรวจครั้งนี้

- ทดสอบจากเครื่อง Windows ไป broker สาธารณะโดยตรวจ TLS certificate, authentication และ publish/subscribe round-trip ผ่านทั้ง MQTT TLS 8883 (0.22 s) และ WSS 9002 /mqtt (0.17 s)
- Broker log พบ HumanRadarBung negotiate TLS หลายครั้งในช่วงตรวจล่าสุด แสดงว่ามีการต่อ transport ใหม่ แต่บอกเหตุที่หลุดไม่ได้จากบรรทัดเหล่านั้น
- Retained info ระบุ firmware 1.1.0 และ retained status online แต่การขอ health ในหน้าต่าง 15 วินาทีไม่ได้ ACK และไม่มี targets สด จึงไม่ถือ retained online เป็นหลักฐานว่าบอร์ดยังออนไลน์
- ยังไม่มี health/log สดเพียงพอระบุ WiFi RSSI, reset reason, heap หรือค่าจังหวะ beep ที่ใช้จริง ไม่สรุปว่าปัญหามาจากระบบจ่ายไฟ
- ผู้ใช้ยืนยันว่าแอปต่อ broker ได้ แต่บอร์ดไม่ออนไลน์ทั้งที่เปิดอยู่
- เปิด connection logging ชั่วคราวและขอ health 3 รอบในช่วง 45 วินาที ไม่พบ connection ใหม่จากบอร์ด/ACK/targets สด คืนค่า logging เดิมแล้วด้วย SIGHUP และยืนยัน PID broker ไม่เปลี่ยน ตาม [Mosquitto configuration reload](https://mosquitto.org/man/mosquitto-conf-5.html)

## ขั้นต่อไป

เมื่อบอร์ดออนไลน์ เก็บ health และ get_log_buffer แบบอ่านอย่างเดียว แล้วเทียบเวลาเสียงค้างกับ WiFi/MQTT reconnect, ค่า beepLongMs และ uptime หากยืนยันว่า loop ถูก network block ให้แยกการจับจังหวะ GPIO ออกจาก network waits พร้อมทดสอบ disconnect/reconnect และ OTA regression ก่อน deploy firmware

รอบนี้แก้ฝั่ง Android Touch lock ไม่เปลี่ยน firmware/ค่าบอร์ด ไม่สั่ง buzzer test หรือ reboot

## ตรวจซ้ำ 2026-11-09 หลังผู้ใช้เปิดอินเทอร์เน็ต

ผู้ใช้แจ้งว่าเปลี่ยนเป็นแหล่งจ่ายเสถียรและพบว่าแบตเตอรี่อ่อน จากนั้นยืนยันว่าเพิ่งเปิดอินเทอร์เน็ต จึงตรวจใหม่โดยไม่สั่ง reboot/OTA หรือเปลี่ยน config

- health ตอบครบ 3 รอบ: uptime 32 → 40 → 48 วินาที, boot count 1797 คงที่, brownout lifetime count 1492 คงที่ในช่วงตรวจ
- reset reason 1 (power-on), firmware 1.1.0, IP 10.252.73.75, RSSI -62/-55/-59 dBm, heap ประมาณ 152–154 KB
- log รอบบูตระบุ WiFi พร้อมที่ 4.412 วินาที และ MQTT WSS เชื่อมสำเร็จที่ 11.870 วินาที จึงยืนยันการออนไลน์ด้วยข้อความตอบสดได้แล้ว
- ขอ get_log_buffer สำเร็จที่ uptime ประมาณ 83.9 วินาที ไม่พบ boot ใหม่ใน log ชุดนี้ แต่ยังไม่พบ targets สดทั้งช่วง health และอ่าน log
- ค่า brownout เป็นยอดสะสม ไม่ได้หมายความว่ามี 1492 ครั้งหลังเปลี่ยนแหล่งจ่าย การตรวจช่วงสั้นนี้ยังไม่ใช่หลักฐานความเสถียรระยะยาวหรือยืนยันว่า beep ค้างหายแล้ว
- timestamp/up ใน ESP32 log ใช้ millis(); app export ปัจจุบันเติม s หลัง up โดยไม่หาร 1000 จึงห้ามตีความเลข log เหล่านี้เป็นวินาที ส่วน health.uptime หาร 1000 แล้ว
