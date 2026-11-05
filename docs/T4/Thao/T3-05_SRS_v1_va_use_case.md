# Yêu cầu phần mềm SOS Mesh phiên bản 1

Đầu việc T3-05. Hoàng Thị Thao phụ trách, Như hỗ trợ. Bản v1 hệ thống hóa đầu vào từ 20 user story ở T2-07. Bản v2 trong T4-04 là bản chuẩn hóa để đề nghị chốt tại review.

## Mục tiêu và phạm vi

Ứng dụng hỗ trợ cảnh báo, gửi SOS và điều phối tại chỗ khi mất Internet. T4 tập trung vào luồng SOS cục bộ, lưu Room, kết nối Nearby, hàng đợi, chống lặp và chuyển tiếp. Nhận ACK, danh sách cứu hộ, ALERT và gateway là phần kiểm thử hồi quy hoặc Sprint tiếp theo, không dùng để suy rộng rằng mọi mốc tương lai đã nghiệm thu.

Android tối thiểu API 26. Repo có hai APK độc lập và thư viện chung. Nearby phụ thuộc Google Play Services phù hợp; trường hợp không có Play Services thuộc ma trận tương thích cần đo và phương án dự phòng R1.

## Tác nhân

Người dân khởi tạo SOS, xem trạng thái và cảnh báo. Cứu hộ nhận SOS, xem ưu tiên và gửi cập nhật. Máy có khóa chỉ huy đã được tin cậy ký ALERT. Mỗi nút có thể chuyển tiếp và mang tin; nút có Internet có thể thêm vai trò gateway. BA và kỹ thuật viên thu log và thực hiện kiểm thử, không phải người xác nhận đã cứu người trong thực tế.

## Use case

| Mã | Tác nhân | Luồng chính và kết quả |
| --- | --- | --- |
| UC01 | Người dân | Mở app, kiểm tra vị trí, mở SOS, chọn nhanh nếu cần, gửi; tin được ghi Room và hiện chờ chuyển |
| UC02 | Relay | Gặp peer, trao đổi summary, lưu tin mới, chuyển tới peer khác; không cần người dùng nhập lại |
| UC03 | Cứu hộ | Nhận SOS, xem trên bản đồ và danh sách; phát DELIVERED sau khi nhận, cho phép báo APPROACHING và REACHED |
| UC04 | Chỉ huy và người nhận | Chỉ huy ký ALERT; người nhận kiểm tra khóa tin cậy trước khi lưu và báo động |
| UC05 | Gateway | Có Internet và Firebase cấu hình đúng; đẩy tin và kéo cảnh báo hợp lệ; lỗi cloud không chặn SOS cục bộ |
| UC06 | Kỹ thuật viên | Xuất CSV và manifest của lượt chạy để tính PDR, trễ, số chặng và hao pin |

Quan hệ: UC01 bao gồm ghi Room và xếp hàng; UC02 là hành vi nền hỗ trợ UC01, UC03 và UC04; UC03 mở rộng việc theo dõi SOS bằng các trạng thái; UC05 chỉ kích hoạt khi có Internet.

## Luồng UC01 chi tiết

Tiền điều kiện: app đã được cấp quyền cần thiết, có vị trí mới đạt chính sách của bản hiện tại. Người dùng đang ở màn hình chính. Chạm SOS, giữ lựa chọn mặc định hoặc chỉnh thông tin, sau đó gửi. App tạo ID, lưu nội dung và thời điểm, đưa vào mesh, hiện QUEUED. Nếu không có peer, tin vẫn ở Room. Khi truyền tới peer, hiện BROADCASTING. Chỉ khi có ACK từ cứu hộ mới hiện DELIVERED.

Ngoại lệ: thiếu vị trí thì báo lấy vị trí mới và không chèn tọa độ mẫu; thiếu quyền thì chỉ dẫn cấp quyền; mất radio thì giữ tin; không đủ dung lượng lưu hoặc lỗi DB phải hiện lỗi, không xác nhận lưu thành công.

## Luồng UC02 chi tiết

Tiền điều kiện: hai máy cùng service ID và tương thích Nearby. Hai máy trao đổi ID và thứ bậc trạng thái, gửi phần thiếu theo ưu tiên. Nút nhận xác minh tin và lưu. Khi đứt kết nối, giữ bản tin còn hạn và nối lại sau. Gói trùng cùng trạng thái không sinh bản ghi mới. Gói ALERT sai chữ ký bị loại. Bản tin hết hạn không được hồi sinh bằng cách nhận lại từ peer.

## Danh mục yêu cầu

FR01 vị trí; FR02 tạo SOS; FR03 lưu bền vững; FR04 kết nối và phát hiện; FR05 chuyển tiếp đa chặng; FR06 chống lặp và ưu tiên; FR07 summary và đồng bộ lại; FR08 trạng thái SOS; FR09 bản đồ và danh sách cứu hộ; FR10 xác thực ALERT; FR11 gateway; FR12 xuất log. Các tiêu chí đo được và truy vết user story được chuẩn hóa ở SRS v2.

## Yêu cầu phi chức năng

Hoạt động SOS không phụ thuộc Internet; khả năng chuyển phát vẫn phụ thuộc đường tiếp xúc. Bảo toàn dữ liệu qua service restart. Không giả vị trí, không giả RSSI. Giao diện mặc định gửi SOS không quá 3 lần chạm sau khi đã có quyền và vị trí; số chạm của nhánh chỉnh cả số người và mức khẩn phải được đo riêng. Pin và trễ nghiệm thu theo protocol K0 tới K5. Không cam kết luôn vượt chế độ Không làm phiền của Android.

## Review và thay đổi

Nam kiểm tra khả thi kỹ thuật, Như đối chiếu user story và wireframe, Huấn xác nhận luồng UI và Room, GVHD xem xét phạm vi và ngưỡng. Mục reviewer, ngày review và kết luận để nhóm ghi sau buổi thực tế. Chưa có biên bản góp ý GVHD trong thư mục nguồn cho mốc T3.

Nguồn: Board T3-05; Nhu/T2-07-Nghiệp vụ.docx, US01 tới US20; Huan/T3-03.docx và Huan/T4.docx; mã nguồn commit nền 44b9015.
