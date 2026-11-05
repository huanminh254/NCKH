# Spike kết nối và demo đa chặng

T2-01 cần demo kết nối 3 máy và bảng đo. T3-02 cần chuyển tiếp qua 4 tới 5 máy. Nguyễn Hoài Nam phụ trách, Huấn hỗ trợ. Ngày chuẩn bị 05/11/2026.

## Những gì đã chạy

Các unit test của MeshQueueEngineTest thực thi cùng lớp queue và codec sản phẩm. Ca 4 nút đưa SOS qua 3 chặng, ACK quay về nguồn. Ca 5 nút mô phỏng các tiếp xúc tách rời đưa SOS qua 4 chặng và ACK REACHED quay về. Ca finite TTL chứng minh dừng chuyển khi ngân sách bằng 0. Đây là mô phỏng logic trong JVM.

Một OnePlus OPD2413 đang nối ADB, Android 16, được dùng cho kiểm thử Room. Không có đủ ba thiết bị thật trong phiên này để chứng minh T2-01 hoặc T4-01 truyền vô tuyến giữa ba máy. Bảng đo được chuẩn bị trống; không lấy các mốc thời gian giả lập trong test làm độ trễ Nearby.

## Demo 3 máy cho T2 và T4

1. Chuẩn bị A người dân, B relay, C cứu hộ; cài cùng APK và bật các quyền cần thiết.
2. Đo trực tiếp A B rồi B C. Ghi model, API, Play Services, thời gian discovery và connection.
3. Loại đường trực tiếp A C bằng kiểm tra topology, không chỉ đặt ba máy cạnh nhau.
4. A tạo SOS; C hiển thị đúng ID, vị trí, hops 2; A nhận ACK DELIVERED. Thu cả log ba máy.
5. Tắt Internet trên cả ba nhưng giữ radio Nearby; lặp lại ít nhất 3 lượt theo K2.

## Demo 5 máy cho T3

Bố trí A B C D E thành chuỗi hoặc các cuộc tiếp xúc nối tiếp. A gửi SOS, E cứu hộ phải nhận cùng ID ở hops 4 khi không có đường tắt. Tạo ACK APPROACHING hoặc REACHED và đưa tiếp xúc ngược về A. Khi không giữ được topology chuỗi, ghi topology thực tế và không gọi đó là thử nghiệm 4 chặng.

## Biểu mẫu đo

Mỗi dòng thử cần run_id, cặp thiết bị, vai trò, khoảng cách, profile, radio, mốc discovery, mốc connection, packet_id, thời điểm tạo, thời điểm nhận, hops và đường dẫn log. Để trống kết quả chưa đo. Sheet Do_truyen và Do_pin trong workbook bàn giao hỗ trợ ghi số liệu và tính chỉ số có điều kiện.

## Điều kiện hoàn tất

T2-01 chỉ Xong khi có demo 3 máy và bảng đo. T3-02 chỉ Xong khi có bằng chứng 4 tới 5 máy. T4-01 chỉ Xong khi module chạy giữa ít nhất 3 máy theo tiêu chí Board. Unit test và build là điều kiện cần, chưa thay thế các điều kiện số máy đó.
