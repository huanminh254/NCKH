# Bàn giao lõi mesh tới tuần 4

Nguyễn Hoài Nam phụ trách. Bản sửa T4 giữ cấu trúc repo gốc, tập trung vào mesh core và kiểm thử. T4 tương ứng 12 tới 18/10/2026 theo kế hoạch gốc; hồ sơ này được chuẩn bị thực tế ngày 05/11/2026.

## Thay đổi đã thực hiện

Queue hỗ trợ hữu hạn chặng khi được cấu hình, giữ chế độ không giới hạn của repo. Giới hạn bộ nhớ ID và dọn tuổi tin. Summary kèm thứ bậc trạng thái để đồng bộ cùng ID đã được nâng trạng thái. Trao đổi summary theo mỗi tick trên kết nối đang có. Chống ACK trễ làm lùi trạng thái cả trong RAM và query Room. Giữ ACK đến trước để áp dụng khi SOS tới sau. Từ chối frame lạ, frame quá lớn và giá trị routing sai.

Luồng gửi chỉ ghi thành công khi callback payload SUCCESS, tách QUEUED và SEND_FAILED. Mỗi lần gửi tăng hops kể cả liên kết đầu từ nguồn. Dữ liệu peer chưa biết không điền RSSI giả. citizen-app kiểm tra vị trí mới, sai số và phạm vi trước khi tạo SOS; hook gửi thử qua Intent giới hạn ở bản debug.

## Kiểm chứng

25 unit test đạt, không lỗi và không bỏ qua. Ba instrumentation test Room đạt trên OnePlus OPD2413 Android 16. Hai APK debug build thành công. Bản sao XML và báo cáo test ở evidence; hash APK trong build_manifest.json. Các warning MapLibre marker API bị deprecated là tồn tại sẵn, build vẫn thành công.

## Phạm vi còn chờ

Cần demo Nearby trên ít nhất 3 máy thật cho T4-01, thêm máy cho spike T3-02, đo pin và hoàn thiện log vô tuyến. Cần review chéo, PR và merge vào dev theo quy ước. Chưa xác nhận các mốc GVHD hay nộp hồ sơ bằng chứng thực địa. Mã sửa nằm local, chưa đẩy lên GitHub.

Rủi ro còn ở module lân cận: migration Room xóa dữ liệu, ACK chưa ký, yêu cầu quyền foreground service sau khi bị thu hồi, số thao tác khi tùy chỉnh cả hai nhóm, vị trí mẫu trong một số màn cứu hộ, gateway đồng bộ theo ID, và thử nghiệm đa OEM. Danh sách có file và bước sửa nằm trong nhận xét Huấn Như.

## Cách kiểm tra lại

Trong thư mục Nam/NCKH, chạy script scripts/verify-t4.ps1 với JDK 17 hoặc 21 và Android SDK. Unit test không cần điện thoại. Muốn chạy Room trên thiết bị thêm tham số DeviceTests khi ADB đã thấy thiết bị. Trước demo vô tuyến, cài APK của đúng bản bàn giao lên tất cả máy và ghi SHA-256.
