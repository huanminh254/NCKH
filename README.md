# SOS Mesh – Ứng dụng cảnh báo thiên tai & điều phối cứu nạn trong điều kiện bị cô lập

Dự án nghiên cứu khoa học sinh viên (Trường Đại học Thủy Lợi) xây dựng hệ thống truyền thông khẩn cấp đa chặng không cần Internet dành cho vùng thiên tai bị cô lập.

## Kiến trúc Module
- `:core-map`: Bản đồ online/offline (`MapLibre` + `OfflineManager` + `hanoi_test_region.mbtiles`), CSDL `Room` (KSP, `Flow`, `Migration`, `exportSchema`), truyền thông `Nearby Connections` (`Strategy.P2P_CLUSTER`), hàng đợi ưu tiên & chống lặp (`MeshQueueEngine`), xác thực chữ ký số `ECDSA P-256` theo danh sách khóa công khai tin cậy (`AlertSignatureVerifier`), chu kỳ quét thích nghi (`BatteryScanController`), và nút cổng đồng bộ đám mây (`FirebaseMeshGateway`).
- `:citizen-app`: Ứng dụng dành cho Người dân (SOS một chạm ≤ 3 thao tác, GPS `Fused Location` + `GNSS`, theo dõi trạng thái `QUEUED → DELIVERED → APPROACHING → REACHED`, nhận & xác thực cảnh báo `ALERT` từ danh sách khóa công khai tin cậy, nhắn tin lân cận `CHAT`, và cẩm nang an toàn offline). **Không chứa khóa bí mật hay chức năng ký `ALERT`.**
- `:rescue-app`: Ứng dụng dành cho Đội cứu hộ & Cán bộ chỉ huy (bản đồ offline hiển thị marker SOS theo mức khẩn cấp, danh sách ưu tiên theo mức độ & khoảng cách Haversine, phản hồi `Đang tới` / `Đã tiếp cận` qua gói `ACK_STATUS` lan ngược trong mạng mesh, và màn hình Cán bộ phát `ALERT` ký bằng khóa bí mật lưu trong **Android Keystore** `OfficialKeystoreSigner`).

## Cơ chế Chữ ký số Cảnh báo Cán bộ (T6-03 & T7-01)
1. **Không nhúng khóa bí mật vào mã nguồn / APK**: Toàn bộ mã nguồn và các gói APK (`citizen-app`, `rescue-app`) tuyệt đối không chứa khóa bí mật PKCS#8.
2. **Ký số bằng Android Keystore (`rescue-app`)**:
   - `OfficialKeystoreSigner` sinh và lưu cặp khóa `EC` (`secp256r1`, `SHA256withECDSA`) trực tiếp trong phần cứng/hệ thống `AndroidKeyStore` (alias `vn.tlu.sosmesh.official.ecdsa.p256`).
   - Chỉ ứng dụng `rescue-app` mới có màn hình phát `ALERT` của cán bộ chỉ huy và gọi `OfficialKeystoreSigner.signOfficialAlert(...)`.
3. **Danh sách Khóa công khai Tin cậy (Trust List)**:
   - `AlertSignatureVerifier` trong `:core-map` chỉ lưu danh sách khóa công khai X.509 Base64 (`DEFAULT_TRUSTED_PUBLIC_KEYS_B64` nhúng sẵn kèm `SharedPreferences` trust store).
   - Khi cấp quyền cho thiết bị cán bộ mới tại hiện trường, khóa công khai X.509 Base64 hiển thị trên màn hình Cán bộ của `rescue-app` được nhập/đăng ký vào Trust List trên các máy qua `AlertSignatureVerifier.registerTrustedOfficialPublicKey(...)` (hoặc cấu hình trong màn hình Trust List).
   - Mọi gói `ALERT` nhận qua Mesh hoặc Cloud đều được kiểm tra chữ ký `SHA256withECDSA` đối chiếu với Trust List; nếu chữ ký sai hoặc ký bằng khóa không nằm trong Trust List thì gói bị loại bỏ (`DroppedInvalidSignature`).

## Cấu hình Firebase cho Nút Cổng (T6-01)
Dự án đã tích hợp `FirebaseMeshGateway` (Firestore) và plugin `com.google.gms.google-services` ở chế độ tự động kích hoạt khi có file cấu hình:
1. Tạo project trên Firebase Console và bật **Cloud Firestore** (với 2 collection `sos_mesh_packets` và `sos_mesh_alerts`).
2. Đăng ký 2 ứng dụng Android với package name:
   - `vn.tlu.sosmesh.citizen`
   - `vn.tlu.sosmesh.rescue`
3. Tải file `google-services.json` từ Firebase Console và đặt vào:
   - `citizen-app/google-services.json`
   - `rescue-app/google-services.json`
4. Khi chưa có `google-services.json`, hệ thống vẫn build và vận hành đầy đủ chế độ P2P Mesh ngoại tuyến mà không báo lỗi biên dịch.

## Lệnh Build, Kiểm thử & Quét APK
```bash
./gradlew :core-map:testDebugUnitTest :citizen-app:assembleDebug :rescue-app:assembleDebug
./scripts/verify_no_private_key_in_apk.sh
./gradlew installDebug
```
