# Quy trình thử nghiệm SOS Mesh

Đầu việc T4-06. Thao phụ trách, Như hỗ trợ. Các ngưỡng đánh giá tham chiếu T2-05, các bước chấp nhận tham chiếu workbook.

## Phân công tại buổi thử

Nam phụ trách bản APK, topology và log mesh. Huấn phụ trách bản đồ, GPS, kiểm tra Room và pin. Thao giữ protocol, đọc các bước test và ghi sai lệch. Như điều phối người tham gia, ghi thời gian quan sát, phiếu khảo sát và đồng ý tham gia. Chỉ tổ chức ở khu vực khô ráo, không dùng thiên tai thật để thử sản phẩm nghiên cứu.

## Chuẩn bị chung

Mượn thêm ít nhất hai máy để có 3 máy thật cho T4, tốt hơn là 5 máy cho spike và 6 tới 10 máy theo kế hoạch tổng. Ghi model, API, Play Services, chủ máy, pin, kết quả Nearby và ngày đo. Tài liệu hiện có 5 máy, trong đó một máy thiếu thông số; không tự coi tất cả đã đạt tương thích.

Chọn sân trường hoặc hành lang được phép sử dụng. Ghi sơ đồ, khoảng cách bằng thước hoặc mốc thực địa. Chuẩn bị cáp dữ liệu, sạc dự phòng, nhãn A tới E, bút, máy tính, bản đồ offline của đúng khu vực và checklist quyền Android. Mỗi máy dùng cùng phiên bản APK, mỗi phiên bản có SHA-256.

## Chạy K0

1. Trên A, chuẩn bị vị trí hợp lệ rồi mở luồng SOS khi chưa có peer.
2. Ghi ID mới và xác nhận Room có tin, giao diện hiện QUEUED.
3. Khởi tạo lại service hoặc mở lại app, kiểm tra cùng ID còn trong Room và chưa tự chuyển DELIVERED.
4. Thu log và ảnh ứng dụng; ghi riêng việc đóng UI với việc hệ điều hành force-stop. Force-stop không có nghĩa hệ thống sẽ tự khởi động lại service.

## Chạy K1

1. A chạy citizen-app, B chạy rescue-app. Hai máy cùng bản, cách nhau khoảng cách đã ghi. Tắt dữ liệu Internet, giữ radio cục bộ cần cho Nearby.
2. Ghi lúc bắt đầu discovery, lúc thấy endpoint và lúc kết nối hoàn tất. Không gộp ba mốc.
3. Tạo 30 SOS có ID riêng với khoảng cách phát cố định, ví dụ mỗi 3 giây. Ghi manifest toàn bộ ID kể cả tin chưa tới.
4. Theo dõi 120 giây từ mỗi lần tạo. Đối chiếu ID tại B, ghi ACK DELIVERED tại A riêng. Lặp lại 3 lượt với run_id mới.

## Chạy K2

1. Xếp A B C D E thành chuỗi. A là nguồn, E là cứu hộ, các máy còn lại là relay.
2. Kiểm tra từng cặp không kề. Đứng thành hàng gần nhau chưa chứng minh không có đường tắt A tới E. Dùng khoảng cách, vật cản hoặc thiết bị di chuyển để loại liên kết ngoài dự kiến.
3. Xác nhận log endpoint và hopCount. Với A tới B tới C tới D tới E, E phải ghi hopCount 4. Nếu có đường tắt thì phân loại lượt khác, không sửa log để ép đúng mô hình.
4. Phát 30 SOS mỗi lượt, chờ cửa sổ 180 giây và lặp 3 lượt. Ghi số tin mới tại E và ACK về A. Mốc T4 chỉ có thể nghiệm thu phần vô tuyến khi ít nhất 3 máy thật đã chuyển qua 2 liên kết.

## Chạy K3

1. A gặp B và gửi 10 SOS; chưa cho B gặp C.
2. Đưa B ra khỏi vùng A, chờ 60 giây theo đồng hồ người quan sát.
3. Cho B gặp C cứu hộ. Đo thời gian từ bắt đầu tiếp xúc B C tới khi C nhận, đồng thời ghi tổng thời gian từ A tạo tin.
4. Cho B quay lại A để mang ACK. Lặp 3 lượt. Có một lượt mở lại app B trước khi gặp C để kiểm tra dữ liệu Room còn nguyên.

## Chạy K4

Mỗi máy chạy ba cấu hình theo thứ tự hoán đổi giữa các máy. Trước mỗi lượt ổn định máy 10 phút. Đo 60 phút, không cắm sạc, cùng mức sáng và trạng thái màn hình. Thu pin đầu, cuối, batterystats và nhiệt độ nếu có. Đo baseline tắt mesh trong điều kiện tương tự. Không suy kết quả ba ngày hoạt động từ một lượt ngắn khi chưa có khoảng bất định.

## Chạy K5

Phát ALERT từ khóa đã tin cậy, khóa lạ và payload đã sửa. Kiểm tra trên relay lẫn người nhận. Phát lại ACK DELIVERED sau REACHED và ACK trước SOS. Tắt radio rồi bật lại, từ chối quyền, thu hồi quyền trong Cài đặt và mở lại app. Chạy từng ngoại lệ với run_id riêng và ghi lỗi thực tế; không trộn vào PDR của lượt điều kiện chuẩn.

## Thu hồi và nộp bằng chứng

Mỗi thư mục run_id chứa manifest, sơ đồ topology, log từng máy, mẫu đo pin, APK hash và biên bản sai lệch. Không đăng công khai tên, số điện thoại hoặc tọa độ nhà người tham gia. Bản tổng hợp phải ghi số máy thật, số lượt, mẫu số và cách xử lý tin timeout. Thao kiểm tra dữ liệu, Nam xác nhận mã, Huấn xác nhận thiết bị, Như xác nhận người tham gia trước khi nộp GVHD.

## Điểm dừng

Dừng lượt khi máy quá nóng, pin xuống thấp ngoài protocol, mưa ướt, người tham gia yêu cầu dừng hoặc quyền hệ thống thay đổi làm mất điều kiện so sánh. Giữ log hiện có, ghi lượt chưa hoàn tất, sắp lại lịch đo thay vì điền kết quả ước đoán.

Nguồn: Board T4-06, R1 tới R5 và Quy ước; T2-05 bộ hồ sơ này; bảng thiết bị Huan/T1-05.xlsx.
