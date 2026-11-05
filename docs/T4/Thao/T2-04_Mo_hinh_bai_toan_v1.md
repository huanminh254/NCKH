# Mô hình bài toán SOS Mesh

Đầu việc T2-04. Hoàng Thị Thao phụ trách, Nam hỗ trợ. Bản phân tích v1 ngày 05/11/2026.

## Mô hình nút và tiếp xúc

Gọi V là tập các phiên ứng dụng. Tại thời điểm t, G(t) = (V, E(t)). Cạnh (u,v) chỉ tồn tại khi hai nút có khả năng trao đổi dữ liệu: radio và quyền cần thiết đang hoạt động, cùng service ID, kết nối được chấp nhận và đường truyền khả dụng. Khoảng cách gần là điều kiện hỗ trợ, chưa đủ để suy ra có cạnh. Tầm 30 tới 100 m chỉ là giả thuyết bố trí ban đầu, phải đo lại theo môi trường.

Vai trò người dân và cứu hộ thuộc ứng dụng; gateway là vai trò bổ sung khi có Internet. Không chia V thành ba tập rời nhau vì một nút cứu hộ cũng có thể làm gateway hoặc relay.

Một cuộc tiếp xúc c = (u, v, t_bat_dau, t_ket_thuc, dung_luong_truyen). Truyền một tin yêu cầu thời gian tiếp xúc đủ cho phát hiện, kết nối, trao đổi summary và payload. Đường đi theo thời gian A gặp B lúc t1, B gặp C lúc t2 với t2 sau t1 vẫn hợp lệ dù không có đường A tới C tại một thời điểm duy nhất.

## Mô hình bản tin

Tin m có ID bất biến, loại, nút gốc, thời điểm tạo, nội dung, trạng thái, chữ ký nếu có, ttl và hopCount. Tọa độ là dữ liệu thu thập, không dùng tọa độ khu vực mặc định để giả làm vị trí người gửi.

| Biến | Ràng buộc |
| --- | --- |
| id | Không rỗng, duy nhất cho sự kiện gốc; ACK có ID riêng và tham chiếu SOS trong content |
| createdAt | Mốc epoch milliseconds, giữ nguyên qua relay |
| hopCount | Khởi tạo 0, tăng 1 cho mỗi liên kết truyền thành công tới nút kế |
| ttl hữu hạn | Bản gửi có ttl mới bằng ttl cũ trừ 1; nút nhận ttl 0 vẫn lưu và hiển thị nhưng dừng chuyển tiếp |
| ttl không giới hạn | Int.MAX_VALUE giữ nguyên; đây là cấu hình mặc định trong repo hiện tại |
| Tuổi tin | age = now - createdAt; hết hạn khi age lớn hơn hoặc bằng 6 giờ |
| Trạng thái SOS | QUEUED, BROADCASTING, DELIVERED, APPROACHING, REACHED theo thứ tự tăng |
| Queue | Tối đa 200 tin trong RAM, ưu tiên SOS rồi ALERT rồi ACK_STATUS rồi CHAT |
| Bộ nhớ chống lặp | Tối đa 2000 ID mặc định; xóa theo tuổi, có thể nhận lại ID cũ khi đã bị loại khỏi bộ nhớ |

Giới hạn 200 là giới hạn hàng đợi RAM, không phải giới hạn số dòng Room. Đọc lại toàn bộ Room trong mỗi tick có thể tốn tài nguyên khi dữ liệu nhiều; đây là đầu việc tối ưu sau thử nghiệm tải.

## Quy tắc chuyển tin

1. Khi tạo SOS: kiểm tra vị trí, ghi Room, đưa vào hàng đợi. Chưa có máy khác thì giữ QUEUED.
2. Khi kết nối: trao đổi ID hiện có và thứ bậc trạng thái, gửi tin bên kia thiếu hoặc giữ trạng thái thấp hơn.
3. Khi nhận: kiểm tra thời hạn, định dạng, chữ ký ALERT và chống lặp; lưu dữ liệu trước khi phát tiếp.
4. Khi có ACK: chỉ nâng trạng thái. ACK cũ không hạ REACHED về DELIVERED. ACK tới trước SOS được áp dụng khi SOS tới sau trong cùng hàng đợi đang lưu.
5. Khi mất kết nối: giữ Room. Khi service khởi tạo lại: nạp dữ liệu còn hạn. Khi kết nối còn sống: trao đổi summary theo mỗi tick để bù tin chưa chuyển.

DELIVERED có nghĩa ứng dụng cứu hộ đã nhận SOS và phát ACK tương ứng. BROADCASTING chỉ phản ánh đã truyền sang nút lân cận, không suy ra có đội cứu hộ nhận. ACK không tự quay đúng một đường cố định; nó tiếp tục lan qua các tiếp xúc của mạng.

## Năng lượng và cơ hội tiếp xúc

| Cấu hình | Chu kỳ | Cửa sổ quét | Điều kiện chính |
| --- | --- | --- | --- |
| SOS_ACTIVE | 5 giây | 2,5 giây | Có SOS đang chờ, pin trên 15 phần trăm, tùy thứ tự điều kiện trong code |
| BALANCED | 15 giây | 4 giây | Trạng thái thường |
| BATTERY_SAVER | 45 giây | 6 giây | Người dùng chọn tiết kiệm hoặc pin thấp |

Cửa sổ discovery không phải toàn bộ thời gian radio hoạt động: quảng bá và kết nối đã thiết lập có thể vẫn chạy. Hai cửa sổ 2,5 giây xen kẽ có thể không giao nhau; vì vậy duty cycle thấp không đủ chứng minh vẫn phát hiện nhanh. K3 và K4 phải đo thời gian phát hiện cả khi hai máy khởi động lệch pha.

Tiêu hao pin theo mức hiển thị = (pin_dau - pin_cuoi) / so_gio. Nếu dùng charge counter, ghi đơn vị microampere giờ và dung lượng tham chiếu riêng. Không trộn phần trăm pin hệ thống với phần tiêu hao ước tính của một UID.

## Giả định và câu hỏi nghiên cứu

Giả định có đường tiếp xúc tới cứu hộ trước khi hết hạn 6 giờ; các máy đã có ứng dụng, quyền và Play Services phù hợp; khu vực có gói bản đồ sẵn. Câu hỏi cần thử nghiệm: mạng 3 tới 5 máy có chuyển phát qua 2 tới 4 liên kết ổn định không, chu kỳ nào cân bằng trễ với pin, và người dùng có phân biệt được trạng thái chờ với đã nhận không.

Nguồn: Board T2-04, T4-01; Nhu/T1-06.docx; MeshQueueEngine.kt, NearbyMeshTransport.kt, BatteryScanController.kt trong repo. Ngưỡng cụ thể ở T2-05 là đề xuất cần GVHD duyệt, không phải số liệu thực nghiệm.
