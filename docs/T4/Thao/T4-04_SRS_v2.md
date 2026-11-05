# Yêu cầu phần mềm SOS Mesh phiên bản 2

Đầu việc T4-04. Bản đề nghị chốt ngày 05/11/2026. Hoàng Thị Thao phụ trách. Phạm vi sản phẩm là citizen-app, rescue-app và core-map. Hoàn thành tài liệu cần review chéo và xác nhận GVHD theo Definition of Done trên Board.

## Những thay đổi từ phiên bản 1

Chuẩn hóa hai APK thay cho thanh chọn chế độ. Phân biệt hopCount, ttl hữu hạn và thời hạn lưu 6 giờ. Giữ cấu hình không giới hạn chặng của commit 44b9015. Bổ sung kiểm soát vị trí trước SOS, summary có phiên bản trạng thái, ACK không làm lùi trạng thái và nhật ký truyền dựa trên callback Nearby. Các thay đổi này đến từ đối chiếu tài liệu với mã nguồn, chưa thay thế biên bản duyệt của GVHD.

## Yêu cầu chức năng và tiêu chí chấp nhận

### FR01 Thu thập vị trí

Hiển thị tọa độ, thời điểm, nguồn và sai số khi đã có mẫu. Bản T4 cho phép tạo SOS khi vĩ độ và kinh độ hợp lệ, sai số công bố không quá 100 m và mẫu không cũ quá 120 giây, không ở tương lai. Đây là chính sách triển khai ban đầu cần đánh giá với người dùng. Mẫu Fused không mặc nhiên được gọi là GNSS thuần. Khi chưa đạt, yêu cầu lấy vị trí mới; không tạo SOS tại tọa độ mặc định. Kiểm thử AT01 và AT02, US02.

### FR02 Tạo SOS

Tạo ID mới, ghi nút gửi, vị trí, số người đại diện cho nhóm đã chọn, mức khẩn cấp, tình trạng và thời gian. Luồng mặc định có hai lần chạm: mở SOS và gửi. Đổi một lựa chọn có thể là lần chạm thứ ba; đổi cả hai nhóm thường vượt 3 lần và cần được ghi trong kết quả UX. Không gọi số người đại diện 1, 4 hoặc 8 là số người chính xác khi UI cho chọn các khoảng 1 tới 2, 3 tới 5, 6 trở lên. AT03, AT04; US03, US04.

### FR03 Lưu SOS khi chưa có mạng

Ghi Room trước khi thông báo đã lưu. Chưa có peer thì giữ QUEUED, sau service restart nạp lại tin còn hạn. Mất kết nối Internet không xóa hàng đợi. Migration giữ dữ liệu là điều kiện trước beta; migration v1 sang v2 hiện tại cần sửa riêng vì có DROP TABLE. AT05, AT06; US05, US06.

### FR04 Kết nối Nearby

Quảng bá và phát hiện dùng cùng service ID, P2P_CLUSTER. Kiểm tra kết nối trên ma trận Android và OEM, ghi thời gian phát hiện riêng với thời gian kết nối. Không suy luận RSSI hoặc khả năng Wi-Fi Direct từ thiết bị cục bộ; thông tin chưa biết phải được ghi chưa đo. Chế độ offline tắt truy cập Internet nhưng vẫn bật các radio cần để Nearby vận hành. AT07, AT08; US06, US18, US19.

### FR05 Chuyển tiếp

Mỗi liên kết tăng hopCount một đơn vị. Với ttl hữu hạn, giảm một đơn vị; ttl 0 ở nút nhận vẫn cho đọc nhưng không phát tiếp. Int.MAX_VALUE giữ nguyên để hỗ trợ chính sách không giới hạn chặng đang có. Mọi loại tin vẫn hết hạn sau 6 giờ kể từ createdAt. AT09 tới AT11; US06, US10.

### FR06 Chống lặp và ưu tiên

ID trùng cùng trạng thái không tạo tin mới. Cùng ID chỉ được nâng trạng thái, không thay nội dung bất biến. Ưu tiên loại SOS, ALERT, ACK_STATUS, CHAT; trong SOS ưu tiên CRITICAL, HIGH, MEDIUM rồi thời điểm cũ trước. RAM mặc định giữ tối đa 200 tin và 2000 ID chống lặp. Sau khi ID hết thời hạn nhớ hoặc bị loại do vượt trần, khả năng chống nhận lại ID đó không còn được bảo đảm; cần đo tải để chọn trần. AT12 tới AT14; US05, US06.

### FR07 Đồng bộ summary

Trao đổi ID đang có cùng status rank. Gửi tin peer thiếu và gửi trạng thái cao hơn của cùng ID. Khi kết nối còn sống, trao đổi lại mỗi scan tick. Summary cũ không có map status vẫn đọc được, nhưng đồng bộ nâng trạng thái qua cùng ID chỉ đầy đủ khi các máy cùng bản mới. ACK có ID riêng tiếp tục là đường cập nhật tương thích. AT15, AT16; US05 tới US07.

### FR08 Trạng thái cứu hộ

QUEUED là đã lưu chờ chuyển; BROADCASTING là đã truyền sang peer; DELIVERED là đã nhận ACK cứu hộ; APPROACHING và REACHED là thông tin do đội cứu hộ gửi. Hàng đợi và Room chỉ cho trạng thái tăng. ACK trễ, trùng hoặc đến trước SOS phải có ca kiểm thử. ACK hiện chưa có chữ ký riêng nên việc xác thực vai trò đội cứu hộ còn là rủi ro phải xử lý trước triển khai. AT17 tới AT20; US07, US15.

### FR09 Bản đồ và ưu tiên cứu hộ

Bản đồ có gói offline chuẩn bị trước, các SOS nhận qua mesh hiển thị đúng vị trí và mức khẩn cấp. REACHED xếp sau yêu cầu chưa tiếp cận; sau đó sắp theo mức khẩn, khoảng cách Haversine và thời gian. Không gọi khoảng cách thẳng là tuyến đường xuồng an toàn. Chưa có vị trí cứu hộ thì không đưa khoảng cách từ tọa độ mẫu vào quyết định. AT21, AT22; US01, US13, US14.

### FR10 Cảnh báo có xác thực

Chỉ nhận ALERT có chữ ký ECDSA hợp lệ từ khóa đã được tin cậy. Gói sửa nội dung, chuỗi giả tiền tố hoặc khóa lạ phải bị loại trước khi báo động và chuyển tiếp. Báo động phụ thuộc quyền thông báo, âm lượng báo thức, chế độ Không làm phiền và OEM; không cam kết kêu trong mọi cấu hình. Thu hồi khóa và phân phối khóa là yêu cầu hoàn thiện trước thử rộng. AT23 tới AT25; US08, US09, US16.

### FR11 Gateway

Không có Firebase vẫn build và dùng mesh offline. Khi có cấu hình và Internet thì đồng bộ; lỗi hoặc timeout cloud không được chặn chuyển tiếp P2P. Việc cloud đồng bộ chỉ theo ID hiện tại chưa đủ bảo đảm cập nhật trạng thái cùng ID; cần ca hồi quy riêng và sửa trong phần T6-01. AT26; US17.

### FR12 Nhật ký và tiện ích

Log phân biệt QUEUED, SENT, FORWARDED, RECEIVED, ACK, SEND_FAILED. SENT và FORWARDED ở bản mới chỉ ghi khi Nearby báo transfer SUCCESS; đây là thành công một liên kết, chưa phải cứu hộ đã nhận. Log độ trễ phải kèm cơ sở đồng hồ khi phân tích. CHAT và cẩm nang thuộc Sprint tiếp theo; giới hạn 160 ký tự còn cần hiện thực ở UI hiện tại. AT27, AT28; US10, US11, US12, US20.

## Yêu cầu phi chức năng

| Mã | Yêu cầu và cách nghiệm thu |
| --- | --- |
| NFR01 | Offline: K1, K2, K3 chạy khi không có Internet, radio cục bộ vẫn bật |
| NFR02 | Bền vững: khởi tạo lại service vẫn đọc được tin Room còn hạn; kiểm thử Room trên thiết bị thật |
| NFR03 | Tương thích: Android API 26 trở lên là phạm vi hỗ trợ; mức đã kiểm chứng phải ghi từng máy |
| NFR04 | Hiệu năng: PDR và trễ theo ngưỡng đề xuất trong T2-05 sau khi GVHD duyệt |
| NFR05 | Năng lượng: đo 60 phút mỗi cấu hình, cùng điều kiện, mục tiêu không quá 10 điểm phần trăm mỗi giờ |
| NFR06 | Khả dụng: đo số chạm thực tế của luồng mặc định và luồng chỉnh thông tin riêng; kiểm tra font lớn |
| NFR07 | Tin cậy: chặn ALERT sai chữ ký; đánh giá riêng xác thực ACK và rủi ro khóa bị mất |
| NFR08 | Riêng tư: chỉ lấy dữ liệu cần cho cứu hộ, hạn chế lưu tọa độ trong log công khai, có quy định lưu và xóa dữ liệu |
| NFR09 | Quan sát: log gốc, commit, manifest, số thiết bị và topology phải truy được từ mỗi kết quả |

## Truy vết user story

| Nhóm US từ tài liệu Như | Yêu cầu sở hữu |
| --- | --- |
| US01, US13, US14 | FR09 |
| US02 | FR01 |
| US03, US04 | FR02 |
| US05, US06 | FR03 tới FR07 |
| US07, US15 | FR08 |
| US08, US09, US16 | FR10 |
| US10, US11, US12, US20 | FR12 và NFR05 |
| US17 | FR11 |
| US18, US19 | FR04 |

## Tiêu chí chốt bản

Đối chiếu từng AT trong workbook, ghi reviewer và ngày vào biên bản, giải quyết các ý kiến chặn nghiệm thu. Các phép đo máy thật chưa chạy không chuyển thành Đạt chỉ vì unit test đã qua. Tài liệu chờ review đến khi có người đọc thứ hai và GVHD đã xem theo quy ước của nhóm.

Nguồn: Board T4-04, Quy ước; Huan/T2-03.docx, Huan/T3-03.docx, Huan/T4.docx, Huan/T5.docx, Huan/T6.docx, Huan/T6(1).docx; Nhu/T2-07-Nghiệp vụ.docx; repo nền 44b9015 và bản sửa T4.
