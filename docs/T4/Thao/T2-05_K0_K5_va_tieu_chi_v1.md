# Kịch bản và tiêu chí đánh giá SOS Mesh

Đầu việc T2-05. Sáu kịch bản dưới đây là định nghĩa thống nhất của bộ hồ sơ T4. Board chỉ nêu tên K0 tới K5, chưa kèm định nghĩa chi tiết. Các ngưỡng hiệu năng là mục tiêu đề xuất để trình GVHD.

## Kịch bản và ngưỡng đề xuất

| Mã | Mục đích | Bố trí | Kết quả cần thu |
| --- | --- | --- | --- |
| K0 | Baseline trên một máy | Tạo SOS, chưa có peer, khởi động lại service | Room giữ đúng tin; QUEUED không biến thành DELIVERED |
| K1 | Truyền trực tiếp | A người dân, B cứu hộ, không Internet | 30 SOS mỗi lượt, 3 lượt; PDR mục tiêu ít nhất 95 phần trăm trong 120 giây |
| K2 | Đa chặng có kiểm soát | A B C D E; chỉ cho phép các cặp kế nhau kết nối | 30 SOS mỗi lượt, 3 lượt; ít nhất 2 chặng; PDR mục tiêu ít nhất 90 phần trăm trong 180 giây |
| K3 | Lưu mang chuyển tiếp | A gặp B, tách A B, sau đó B gặp C | 10 SOS mỗi lượt, 3 lượt; tới đích trong 120 giây sau tiếp xúc B C; không tính thời gian mang là lỗi |
| K4 | Pin và chạy nền | 3 chu kỳ quét trên ít nhất 3 máy | Mỗi chu kỳ 60 phút mỗi máy, màn tắt; hao pin mục tiêu không quá 10 điểm phần trăm mỗi giờ |
| K5 | Ngoại lệ và thông tin tin cậy | ALERT sai chữ ký, ACK trễ, quyền bị thu hồi, mất kết nối | Không nhận ALERT sai; không lùi trạng thái; không mất tin đã lưu do ngắt radio |

Không gộp các lượt chạy thành một tỷ lệ chung khi thiết bị, cấu hình hoặc thời tiết khác nhau. Báo cáo từng lượt trước, sau đó mới tổng hợp và giữ nguyên mẫu số.

## Chỉ số và cách tính

PDR = số ID SOS duy nhất được ứng dụng cứu hộ đích ghi nhận trong thời hạn / số ID SOS duy nhất hợp lệ tạo ở nguồn. Nếu có nhiều máy đích, định nghĩa cặp ID và đích ngay từ manifest. Tin trùng và ACK không cộng thêm vào tử số SOS. Tin tới relay chưa được tính là tới cứu hộ.

Độ trễ một chiều = thời điểm nhận ở đích đã hiệu chỉnh đồng hồ - thời điểm tạo ở nguồn. Ghi độ lệch đồng hồ trước và sau buổi thử. Nếu chưa có hiệu chỉnh đáng tin cậy, báo PDR và RTT riêng, để trống độ trễ một chiều. RTT phải ghép đúng SOS với ACK tương ứng trên cùng đồng hồ nguồn.

Báo trung vị và p95 cho tin tới đích, đồng thời báo số tin mất và cửa sổ timeout. Tin mất không được gán độ trễ 0. Các con số 450 ms, 980 ms, 1620 ms trong unit test là thời gian giả lập, không được đưa vào bảng đo vô tuyến.

Tầm liên lạc là khoảng cách thực đo tại từng điều kiện thử, kèm vật cản và hướng cầm máy. Ghi riêng thời gian phát hiện và thời gian hoàn tất kết nối. Nearby tự chọn phương tiện truyền, không suy ra đang dùng Wi-Fi Direct chỉ vì kết nối thành công.

Mức hao pin theo phần trăm = (mức đầu - mức cuối) x 60 / số phút. Ghi sạc có nối hay không, độ sáng, màn bật/tắt, GNSS và số peer. Đo baseline cùng điều kiện để so tổng hệ thống với phần tăng thêm khi bật app.

SUS: câu lẻ đóng góp điểm trả lời trừ 1; câu chẵn đóng góp 5 trừ điểm trả lời; tổng 10 đóng góp nhân 2,5. Chỉ tính phiếu đủ 10 câu từ 1 tới 5; để thiếu kết quả khi chưa đủ dữ liệu. Điểm 68 có thể dùng làm mốc tham khảo thường gặp, không gọi là ngưỡng chuẩn áp dụng cho mọi nhóm.

## Nhật ký đo tối thiểu

Mỗi lượt có run_id, ngày giờ, người điều phối, mã APK hoặc commit, phiên bản thiết bị, profile quét, topology và radio. Mỗi tin có packet_id, origin, target, thời điểm tạo, thời điểm nhận nếu có, hạn quan sát, hopCount và độ lệch đồng hồ. Mỗi thiết bị có pin đầu, pin cuối, thời lượng và cấu hình màn hình.

Các sheet Do_truyen và Do_pin trong workbook bàn giao là biểu mẫu trống cho đo thực tế. Không dùng số mô phỏng để điền vào đó.

## Điều kiện nghiệm thu

Tài liệu đạt khi Nam kiểm tra tính đo được, Như kiểm tra bố trí người và GVHD xác nhận các ngưỡng. Kết quả phần mềm cần qua test case; kết quả nghiên cứu cần log gốc và đủ lượt lặp. Mốc M3 vẫn yêu cầu SOS đi qua ít nhất 2 chặng trên 3 tới 4 máy thật và hiển thị tại máy cứu hộ.

Nguồn: Board T2-05, M1, M3, Quy ước; tài liệu Huấn T2-02, T3-04; Brooke 1996 về System Usability Scale. Bộ K0 tới K5 ở đây là đặc tả đề xuất, chưa phải biên bản thử đã chạy.
