# Nhận xét phần việc của Huấn và Như

Đối chiếu ngày 05/11/2026 với Board gốc, 11 tài liệu Word, hai workbook của Huấn và repo hnem06/NCKH tại commit nền 44b9015. Nhận xét đánh giá sản phẩm và bằng chứng, không suy đoán mức đóng góp cá nhân từ số trang tài liệu.

## Kết luận cần xử lý trước review

Huấn đã có mã nguồn cho bản đồ offline, Room, SOS, màn cứu hộ và xác thực ALERT. Tài liệu mới cũng nêu rõ nhiều hạn chế. Như đã chuẩn bị đề cương, phiếu khảo sát, năm biên bản phỏng vấn và 20 user story. Điểm thiếu chung là sự liên kết giữa phát biểu trong báo cáo với bằng chứng gốc có thể kiểm tra, cùng sự không thống nhất về ttl và hành vi của bản code mới nhất.

Các sửa lõi mesh đã thực hiện trong phần Nam gồm query trạng thái không lùi, merge payload cũ, summary có status, bộ nhớ chống lặp, hỗ trợ finite TTL và log callback. Phần nhận xét bên dưới phân biệt vấn đề đã sửa với việc còn chờ, để nhóm không sửa lại cùng một lỗi hai lần.

## Phần của Huấn

### H1 Vị trí mẫu khi gửi SOS

Mức ưu tiên cao. citizen-app MainActivity trước bản sửa dùng tọa độ khu vực mặc định khi loc rỗng. Huan/T4.docx đã nêu hạn chế này. Một SOS như vậy có thể dẫn cứu hộ tới sai vị trí. Bản T4 đã kiểm tra vị trí mới và sai số trước luồng SOS, bỏ việc gửi mặc định; hook thử chỉ hoạt động ở debug. Cần chạy lại luồng UI trên thiết bị thật, gồm trường hợp trong nhà không có fix. Một số màn cứu hộ còn dùng vị trí mặc định để tính khoảng cách, chưa được sửa trong phạm vi lõi Nam.

### H2 Migration Room xóa dữ liệu

Mức ưu tiên cao. SosMeshDatabase.kt, MIGRATION_1_2 có DROP TABLE đối với cả messages, peers, logs và user_profile. So sánh hai schema cho thấy thay đổi chính ở logs là thêm origin; không có lý do kỹ thuật bắt buộc phải xóa mọi SOS. Đề nghị migration giữ dữ liệu, bổ sung cột origin với giá trị khởi đầu thích hợp và chạy MigrationTestHelper từ schema 1 sang 2. Bỏ fallback phá dữ liệu ở bản beta hoặc chỉ cho phép đường phát triển có cảnh báo rõ. Ba instrumentation test đã chạy ở bản T4 không kiểm chứng migration này.

### H3 ACK và payload cũ làm lùi trạng thái

Mức ưu tiên cao. Query updateStatus cũ cập nhật vô điều kiện, trong khi Huan/T3-03.docx và T5.docx nói trạng thái chỉ tăng. ACK DELIVERED tới sau REACHED có thể hạ dữ liệu Room dù queue đã giữ REACHED. Bản T4 đã thêm điều kiện thứ bậc và transaction mergeMessage, kèm test Room trên Android 16. Cần test thêm nhiều đội cứu hộ, ACK đảo thứ tự qua nhiều máy và service restart.

### H4 Bảng pin thiếu tính nhất quán của đại lượng

Mức ưu tiên cao với kết luận nghiên cứu. Huan/T3-04.xlsx trộn phần trăm pin hệ thống, mAh ước tính của UID và phần tiêu hao gọi là cụm P2P trong cùng ô. Với 99 xuống 94 trong 106 phút, phép tính là khoảng 2,83 điểm phần trăm mỗi giờ. Nếu lấy 172 mAh trên pin danh định 7550 mAh và cùng 106 phút, kết quả khoảng 1,29 phần trăm dung lượng danh định mỗi giờ. Hai cách không tự tương đương và chưa rõ cùng cửa sổ đo.

Dòng SOS_ACTIVE ghi 0,55 phần trăm mỗi giờ ở nền nhưng thời lượng 80 phần trăm pin lại ghi khoảng 28 giờ; phép chia đơn giản 80/0,55 là khoảng 145 giờ. Đây là dấu hiệu đang dùng các đại lượng khác nhau, cần chỉ rõ mẫu số và phép tính, chưa đủ để kết luận thời lượng thực tế. Nên tách mỗi lượt thành số typed ở cột riêng, giữ raw batterystats, thời gian và điều kiện. Không thay số gốc bằng số suy tính trong nhận xét này.

### H5 Kết quả Fused chưa chứng minh GNSS offline

Mức ưu tiên trung bình. T2-02.docx đã ghi thẳng 529 mẫu có satellites bằng 0 và chưa chứng minh khóa vệ tinh mới, đây là cách trình bày đúng giới hạn. Cần đo ngoài trời với mẫu GPS mới và satellitesUsedInFix, phân biệt lastKnown, Fused và GPS_PROVIDER. Không dùng số chữ số tọa độ hoặc accuracy do hệ thống công bố làm độ chính xác so với vị trí chuẩn. Giữ log gốc và mốc mở app để tính time to first fix.

### H6 Số máy và tính tái lập

T1-05.xlsx hiện kiểm kê 5 máy, thấp hơn mục tiêu 6 tới 10; một máy garnet thiếu model thương mại và thông số. Các tài liệu dẫn docs/evidence/PING-2MAY nhưng thư mục này không đi kèm repo vì bị ignore và cũng không có trong bộ nguồn đã tải. Đề nghị đóng gói một evidence bundle riêng, ghi checksum, ngày đo và APK. Tỷ lệ 41 trên 41 SOS trong T5.docx là kết quả báo cáo từ buổi hai máy, chưa phải bằng chứng đa chặng hay số liệu đã tái hiện trong phiên này.

### H7 Quyền và foreground service

T2-03.docx mô tả START_STICKY như điều kiện duy trì nền nhưng hệ điều hành và OEM vẫn có thể dừng tiến trình. MeshForegroundService.onStartCommand cần xử lý trường hợp quyền bị thu hồi trước khi gọi startForeground. Khi khai báo cả connectedDevice và location phải đáp ứng các điều kiện tương ứng; không mô tả đơn giản rằng chỉ cần một trong hai nhóm quyền. Cần test API 26, 29, 31, 33, 34 và 36 theo máy có sẵn, thêm Doze, từ chối quyền và battery saver. POST_NOTIFICATIONS bị từ chối không đồng nghĩa mọi foreground service đều không được khởi chạy.

### H8 Giao diện và diễn giải trạng thái

T4.docx và T5.docx phân biệt phạm vi một máy với đa máy khá rõ. Tuy vậy, mở SOS, đổi số người, đổi mức khẩn rồi gửi là ít nhất 4 lần chạm. Chỉ luồng giữ mặc định hoặc sửa một nhóm mới có thể đạt 2 tới 3 chạm. Các giá trị 1, 4 và 8 trong code đại diện cho khoảng lựa chọn, không phải số người chính xác. Cần kiểm tra font lớn, chiều ngang màn hẹp, dialog cuộn và nhãn QUEUED so với DELIVERED.

### H9 ALERT và giới hạn bảo mật

Chuyển từ kiểm tra tiền tố sang ECDSA với Android Keystore là thay đổi đúng về kỹ thuật; citizen-app không có private key. Tuy nhiên tin cậy khóa chưa chứng minh người giữ máy là cán bộ, và ký số không bảo đảm nội dung đúng ngoài đời. Cần quy trình cấp, thu hồi và thay khóa. ACK_STATUS hiện chưa ký nên còn khả năng giả trạng thái điều phối. STREAM_ALARM và rung còn phụ thuộc DND, âm lượng, quyền và OEM; tránh khẳng định luôn báo động được. Việc chặn ALERT sai chữ ký đã có unit test, chưa thay thế các kiểm thử thiết bị và vận hành khóa.

### H10 Phần T6 và gateway

T6.docx đã đính chính CHAT chưa giới hạn 160 ký tự và RSSI cũ là giá trị cố định; đây là thông tin cần giữ trong báo cáo. Bản T4 đã bỏ RSSI giả trong transport mới, số 0 được quy ước là chưa đo. Gateway đồng bộ theo tập ID có thể bỏ qua cập nhật status của cùng ID sau lần upload đầu. Cần test lại Firestore thực tế, quyền truy cập và lỗi timeout. Cẩm nang cần người có chuyên môn xem trước pilot, không coi nội dung trong code là hướng dẫn đã được thẩm định.

## Phần của Như

### N1 Khung phân tích và 20 user story

T1-06.docx đặt vấn đề, thuật ngữ và môi trường cô lập có cấu trúc rõ. T2-07-Nghiệp vụ.docx phân rã đủ 20 user story với vai trò, lợi ích và tiêu chí. Cần tách yêu cầu nghiệp vụ khỏi tên lớp và lựa chọn SDK để SRS không bị phụ thuộc một bản code. Các user story về đăng nhập, RSSI, 160 ký tự và ttl cần đánh dấu chưa hiện thực hoặc đã đổi theo bản hiện tại.

### N2 Phiếu khảo sát chưa có dữ liệu định lượng kiểm tra được

T1-07.docx chứa bảng câu hỏi và kế hoạch gửi, nhưng chưa có link Forms thật hoặc CSV phản hồi. T2-06-Ksat.docx nói đủ vượt ngưỡng 10 phản hồi mà bảng không ghi số phản hồi thực tế theo nhóm. Cần xuất CSV đã ẩn danh, n tổng, n hợp lệ, quy tắc loại phiếu và ngày khóa khảo sát trước khi coi T3-06 hoàn tất. Chỉ tiêu ba nhóm 10 tới 12, 4 tới 5 và 2 tới 3 cộng thành 16 tới 20; cần thống nhất với tổng mục tiêu 15 tới 20.

### N3 Biên bản phỏng vấn thiếu dấu vết thực hiện

Đã có năm ghi chép PV01 tới PV05 và mapping nhu cầu. Các bản hiện chưa ghi ngày giờ, người phỏng vấn, cách đồng ý tham gia, trích dẫn đã mã hóa hay bản ghi gốc tham chiếu. Cần bổ sung các trường đó và phân biệt lời người tham gia với diễn giải của BA. Không suy diễn rằng ghi chép là bịa, nhưng cũng không dùng nó như dữ liệu đã kiểm chứng đầy đủ.

### N4 Câu hỏi có thể dẫn dắt

Một số câu mô tả sẵn lợi ích multi-hop và bán kính 30 tới 100 m như khả năng bảo đảm, hoặc hỏi chấp nhận mức pin 4 tới 8 phần trăm sau khi đưa lợi ích. C1 dùng cụm tọa độ mặc định dễ làm người trả lời hiểu nhầm. Nên hỏi về trải nghiệm và mức chấp nhận trước, sau đó mới giới thiệu giải pháp; tách CHAT và cẩm nang thành hai mục; cho tùy chọn chưa biết hoặc không áp dụng. Giữ bảng mã câu hỏi để phân tích câu nhiều lựa chọn đúng mẫu số.

### N5 Wireframe và kế hoạch thử nghiệm

Trong thư mục Như chưa có sản phẩm tương ứng T3-07, T4-07, T4-08 và T4-09. Vì vậy cần link Figma hoặc bản export, kế hoạch địa điểm và người tham gia, cùng nháp chương 1. Các file phân tích ở T1 và T2 không tự thay cho các đầu ra này. Bộ protocol và checklist đã chuẩn bị ở phần Thao giúp bắt đầu, nhưng Như vẫn cần xác nhận khâu tổ chức và UX.

### N6 Các phát biểu tuyệt đối cần chỉnh

T2-07 nói chặn 100 phần trăm nguy cơ tin giả, trong khi chữ ký chỉ xác thực dữ liệu và khóa theo giả định tin cậy. Phạm vi 30 tới 100 m không phải kết quả đo đa thiết bị. Fused không tương đương GNSS thuần. Quy trình ACK nên nói lan truyền qua mạng tiếp xúc, không bảo đảm quay ngược đúng một đường. Cập nhật các câu này theo SRS v2 để báo cáo giữ đúng mức bằng chứng.

## Việc cần mang vào buổi review

Ưu tiên xác nhận thiết kế hai APK, chính sách chặng và thời hạn tin, cách xử lý không có vị trí và ý nghĩa DELIVERED. Huấn mang evidence bundle, APK và raw pin. Như mang link Forms, CSV ẩn danh, ngày phỏng vấn và wireframe. Nam mang diff, báo cáo 25 unit test và ba test Room. Thao mang SRS v2, danh mục AT và protocol K0 tới K5. Ghi người review và kết luận thực tế, không điền ngày hoàn tất lùi theo lịch cũ.
