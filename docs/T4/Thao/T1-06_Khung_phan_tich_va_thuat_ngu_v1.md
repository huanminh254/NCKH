# Phân tích bài toán SOS Mesh

Hoàng Thị Thao phụ trách. Đầu việc T1-06, nối tiếp T2-04. Phiên bản 1 ngày 05/11/2026.

## Bài toán cần giải quyết

Khi hạ tầng di động và Internet bị gián đoạn, người dân cần báo vị trí và tình trạng tới lực lượng cứu hộ. Điện thoại ở gần có thể trao đổi dữ liệu trực tiếp; điện thoại di chuyển có thể lưu tin rồi mang tới cụm khác. Khả năng chuyển phát phụ thuộc các cuộc tiếp xúc thực tế, pin, quyền Android và việc người tham gia đã cài ứng dụng. Hệ thống hỗ trợ thông tin cứu hộ, không bảo đảm mọi SOS đều có đường tới đích.

Sản phẩm gồm citizen-app cho người dân, rescue-app cho cứu hộ và module core-map dùng chung. Tách hai APK là thiết kế hiện có trong repo, thay cho một màn hình chuyển chế độ được nêu trên Board. Việc tách này cần được xác nhận trong SRS khi review.

## Đề cương tài liệu

1. Bối cảnh cô lập, đối tượng sử dụng và phạm vi nghiên cứu.
2. Thuật ngữ, giả định và ràng buộc thực tế.
3. Mô hình nút, tin, cuộc tiếp xúc và thời hạn lưu.
4. Các luồng cảnh báo, SOS, điều phối và nút cổng.
5. Kịch bản K0 tới K5, chỉ số và phương pháp đo.
6. Hạn chế, các quyết định cần xác nhận và truy vết yêu cầu.

## Thuật ngữ thống nhất

| Thuật ngữ | Cách hiểu trong đề tài |
| --- | --- |
| Nút | Một phiên bản ứng dụng trên thiết bị tham gia mạng |
| Endpoint | Định danh kết nối Nearby tạm thời, có thể đổi khi nối lại |
| nodeId | Định danh logic của nút, khác endpoint và không phải bằng chứng danh tính |
| MANET | Mạng tùy biến giữa các thiết bị di động |
| DTN | Mạng chấp nhận gián đoạn và trễ, có thể thiếu đường đầu cuối liên tục |
| Store carry forward | Lưu tin bền vững, mang theo thiết bị, chuyển khi gặp nút khác |
| P2P_CLUSTER | Chiến lược kết nối nhiều với nhiều của Nearby; ứng dụng tự làm chuyển tiếp đa chặng |
| Summary vector | Danh sách ID hiện đang giữ, kèm thứ bậc trạng thái trong bản cập nhật T4 |
| Chống lặp | Không nhận lại cùng ID và cùng trạng thái khi ID còn trong bộ nhớ chống lặp |
| hopCount | Số liên kết đã truyền qua; A tới B là 1, A tới B tới C là 2 |
| ttl | Ngân sách chặng khi dùng số hữu hạn; Int.MAX_VALUE biểu diễn không giới hạn chặng |
| maxAgeMs | Thời hạn giữ bản tin; hiện là 6 giờ kể từ createdAt, độc lập với ttl |
| SOS | Bản tin cầu cứu có vị trí, số người và tình trạng |
| ALERT | Cảnh báo ký số bằng khóa nằm trong danh sách tin cậy |
| ACK_STATUS | Bản tin riêng tham chiếu ID SOS để cập nhật trạng thái |
| Gateway | Vai trò tạm thời khi nút có Internet và cấu hình Firebase hợp lệ |
| Room | Lớp truy cập SQLite để dữ liệu tồn tại qua lần mở ứng dụng |
| MBTiles | CSDL SQLite chứa các ô bản đồ của vùng đã chuẩn bị |
| GNSS | Định vị vệ tinh; không đồng nghĩa mọi mẫu Fused đều là fix vệ tinh mới |
| PDR | Số SOS tới đích yêu cầu chia số SOS hợp lệ được khởi tạo trong cùng cửa sổ quan sát |
| SUS | Thang khả dụng 10 câu, quy đổi 0 tới 100; không phải phần trăm thành công |

## Môi trường và hệ quả thiết kế

| Đặc điểm | Hệ quả |
| --- | --- |
| Mất Internet | Luồng SOS cục bộ dùng Nearby và Room; bản đồ phải có dữ liệu sẵn |
| Mạng chia cụm | Không xóa tin chỉ vì vừa mất kết nối; đồng bộ lại khi gặp máy khác |
| Pin hạn chế | Quét thích nghi, đo riêng màn bật và màn tắt, không suy thời lượng từ hằng số code |
| Người dùng căng thẳng | Luồng mặc định ngắn, nút rõ, thông báo phân biệt đã lưu với đã tới cứu hộ |
| Tin sai và giả mạo | Xác minh ALERT bằng khóa tin cậy; đánh giá riêng rủi ro ACK chưa ký |
| Hệ điều hành khác nhau | Lập ma trận quyền API 26 tới 36, đo OEM và chế độ tiết kiệm pin |

## Cơ sở tài liệu

Board_cong_viec.xlsx, sheet Board, T1-06 và T2-04; Nhu/T1-06.docx; Nhu/T2-07-Nghiệp vụ.docx. Nội dung triển khai đối chiếu repo hnem06/NCKH tại commit nền 44b9015 và bản sửa T4 trên nhánh work/hoan-thien-t4.

Nguồn kỹ thuật: https://developers.google.com/nearby/connections/overview ; https://developers.google.com/nearby/connections/strategies ; RFC 9171 về kiến trúc chuyển tin chịu gián đoạn tại https://www.rfc-editor.org/rfc/rfc9171.html . SOS Mesh là giao thức ứng dụng riêng, không tuyên bố tuân thủ Bundle Protocol.
