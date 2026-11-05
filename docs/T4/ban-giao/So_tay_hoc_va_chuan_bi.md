# Sổ tay học SOS Mesh và chuẩn bị review

Mục tiêu là hiểu được một SOS được tạo, giữ lại, chuyển qua nhiều máy và xác nhận tới cứu hộ như thế nào; sau đó tự chạy test, đọc log và trình bày giới hạn của kết quả. Các tài liệu mã T trong thư mục Thao và Nam là hồ sơ chi tiết để đọc tiếp.

## Bắt đầu bằng một câu chuyện

Điện thoại A của người dân chưa có Internet. A tạo SOS và lưu Room. B của hàng xóm đi ngang, nhận tin và lưu. Sau đó B di chuyển tới gần C của cứu hộ, chuyển cùng ID cho C. C gửi ACK DELIVERED, rồi APPROACHING khi đội xác nhận đang tới. ACK được các máy mang và chuyển về A. Cả lượt không cần một đường A tới C liên tục, nhưng cần chuỗi tiếp xúc có thật trước khi tin hết hạn.

Nếu A gửi sang B thành công, A mới biết có peer nhận ở mức liên kết. Điều đó chưa có nghĩa đội cứu hộ biết tin. Đây là lý do phải phân biệt QUEUED, BROADCASTING và DELIVERED.

## Bản đồ kiến thức

| Cần hiểu | Ý nghĩa | Đọc code ở đâu |
| --- | --- | --- |
| Kotlin data class và copy | Dữ liệu bất biến, tạo bản mới khi cập nhật trạng thái hoặc routing | MeshMessageEntity, MeshQueueEngine |
| Coroutine và Flow | Công việc nền và luồng dữ liệu cập nhật UI | MeshForegroundService, MainActivity |
| Room và DAO | Ghi tin bền vững, truy vấn và transaction | SosMeshDatabase |
| Nearby Connections | Kết nối trực tiếp giữa hai endpoint lân cận | NearbyMeshTransport |
| Summary vector | Hỏi máy kia thiếu ID hoặc trạng thái nào | MeshPacketCodec, MeshQueueEngine |
| DTN và đa chặng | Mang dữ liệu qua các cuộc tiếp xúc khác thời điểm | T2-04, ca mô phỏng 5 nút |
| ECDSA và danh sách khóa | Kiểm tra ALERT được ký bởi khóa đã tin cậy | AlertSignatureVerifier |
| Thử nghiệm | Từ log gốc tới PDR, trễ và hao pin | T2-05, T4-06, workbook |

## Bài học 1 Phân biệt các tầng

Nearby cung cấp liên kết cục bộ, không tự làm toàn bộ mesh nhiều chặng. Thuật toán của ứng dụng quyết định giữ tin, chống lặp, chọn phần thiếu và chuyển tiếp. Room giữ tin sau khi tiến trình khởi tạo lại, còn queue RAM giúp chọn thứ tự gửi. UI chỉ phản ánh dữ liệu; dòng thông báo đẹp chưa chứng minh truyền thành công.

Thực hành: lần theo luồng từ citizen-app MainActivity tới showOneTapSosDialog, MeshForegroundService.broadcastPacket, NearbyMeshTransport.broadcastPacketNow, MeshPacketCodec và handleIncomingPacket. Ghi bên cạnh mỗi bước: dữ liệu ở RAM hay đã ở Room, và bằng chứng nào cho thấy bước đó thành công.

## Bài học 2 Hiểu ID và chống lặp

ID biểu diễn một sự kiện gốc. Relay không được tạo ID SOS mới cho cùng tin vì sẽ làm PDR đếm trùng. ACK là sự kiện khác nên có ID riêng, nhưng content tham chiếu ID SOS. Khi hai đường mang cùng SOS tới một nút, bản trùng cùng trạng thái bị bỏ. Nếu cùng ID có REACHED trong khi máy đang giữ QUEUED, trạng thái phải được nâng.

Bộ nhớ chống lặp có trần để không chiếm RAM mãi. Do đó câu nói không bao giờ nhận trùng là quá mạnh: sau khi ID đã bị dọn hoặc trần nhớ bị vượt, nó có thể được nhận lại. Tin hết hạn vẫn bị chặn theo createdAt để hạn chế hồi sinh tin cũ.

Thực hành: đọc ca seen cache stays bounded, đổi trần queue trong bản thử riêng và giải thích vì sao summary chỉ nên gồm tin thực sự còn giữ.

## Bài học 3 Hiểu số chặng và thời hạn

A tới B là 1 chặng; A tới B tới C là 2. Nguồn có hops 0. Bản main mới nhất trước khi sửa dùng ttl bằng Int.MAX_VALUE, nghĩa không giới hạn số chặng. Bản T4 vẫn giữ mặc định đó. Khi chủ động dùng ttl hữu hạn 2, tin có thể tới C ở ttl 0, C đọc được nhưng không phát tiếp.

Thời hạn 6 giờ là quy tắc khác: một tin giữ 6 giờ hết hạn dù chưa đi chặng nào. Chờ 1 giờ không tự làm ttl giảm. Không dùng hai ý nghĩa này thay nhau khi thuyết trình.

Bài tập: tin bắt đầu ttl 3, hops 0 ở A, đi tới B rồi C. Đáp án: ở C ttl 1, hops 2. Nếu không có tiếp xúc với D trong 6 giờ thì tin hết thời hạn lưu dù còn một chặng.

## Bài học 4 Trạng thái và ACK

QUEUED nghĩa đã lưu, chờ cơ hội. BROADCASTING nghĩa đã chuyển sang peer. DELIVERED nghĩa có ACK từ ứng dụng cứu hộ. APPROACHING nghĩa đội báo đang tới. REACHED nghĩa đội báo đã tiếp cận. ACK đến muộn không làm REACHED thành DELIVERED.

RAM và CSDL phải tuân thủ cùng quy tắc tăng. Nếu chỉ sửa queue mà query UPDATE vô điều kiện thì sau lần đọc Room, UI có thể lùi lại. Bản T4 đã sửa query và có ba test chạy trên Android để chứng minh phần lưu trữ. ACK chưa ký vẫn là rủi ro: chuỗi trạng thái tăng không tự xác thực ai được quyền gửi nó.

## Bài học 5 Bản đồ và vị trí

Bản đồ là nền ảnh hoặc vector đã chuẩn bị; vị trí là mẫu cảm biến. Có bản đồ offline không bảo đảm đang có fix GPS mới, và có tọa độ không bảo đảm bản đồ đã được tải. Fused có thể dùng dữ liệu tổng hợp hoặc bộ nhớ đệm. Muốn chứng minh GNSS offline cần ghi mẫu mới, nguồn và vệ tinh usedInFix trong điều kiện phù hợp.

Không thay vị trí thiếu bằng một tọa độ đẹp ở khu vực thử rồi ghi đó là nhà người gửi. Trong bản mới, SOS phải chờ mẫu đạt chính sách mới không quá 120 giây, accuracy công bố không quá 100 m và tọa độ hợp lệ. Ngưỡng này là lựa chọn ban đầu cần review UX, không phải độ chính xác đã đo của thiết bị.

## Bài học 6 Hiểu chữ ký số

Khóa riêng của máy chỉ huy ký dữ liệu ALERT, khóa công khai trong danh sách tin cậy giúp máy nhận kiểm tra. Sửa phần dữ liệu đã ký thì kiểm tra thất bại. Khóa tin cậy không chứng minh nội dung cảnh báo đúng trong đời thực và không giải quyết khóa bị mất nếu chưa có cơ chế thu hồi. Private key được giữ trong Android Keystore, nhưng cần kiểm tra cấp độ bảo vệ của từng máy thay vì nói mọi máy đều có phần cứng bảo mật giống nhau.

Thực hành: đọc test ECDSA bốn trường hợp: khóa tin cậy, tiền tố giả, payload sửa và khóa lạ. Giải thích vì sao một chuỗi bắt đầu bằng SIG không phải chữ ký số.

## Bài học 7 Đo lường đúng

Giả sử nguồn tạo 30 SOS có ID khác nhau, cứu hộ nhận 27 ID trong 120 giây. PDR là 27/30 bằng 90 phần trăm. Nhận một ID ba lần không làm số nhận thành 29. Ba tin còn lại là mất hoặc quá hạn quan sát, không có độ trễ bằng 0.

Nếu đồng hồ đích nhanh hơn nguồn 5 giây mà log cho trễ 5,2 giây, trễ hiệu chỉnh có thể là khoảng 0,2 giây. Nếu chưa đo độ lệch thì kết luận một chiều đó thiếu cơ sở. Các timestamp tự đặt trong unit test chỉ để kiểm tra logic, không đo tốc độ Bluetooth hay Wi-Fi.

Nếu pin giảm 5 điểm phần trăm trong 106 phút, tốc độ khoảng 2,83 điểm phần trăm mỗi giờ. Mức mAh do batterystats ước lượng cho một UID là đại lượng khác. Không chia mAh của app cho pin toàn hệ thống rồi gọi đó là số đo giống phần trăm màn hình.

## Lộ trình học năm buổi

| Buổi | Nội dung | Sản phẩm tự làm |
| --- | --- | --- |
| 1 trong 60 phút | Đọc câu chuyện, mô hình, phân biệt Nearby và mesh | Vẽ A B C và ghi dữ liệu ở mỗi nút |
| 2 trong 90 phút | Kotlin data class, queue, chống lặp, ttl | Chạy unit test, giải thích hai ca biên |
| 3 trong 90 phút | Room, trạng thái, coroutine và Flow | Đọc query monotonic, chạy test Android nếu có máy |
| 4 trong 60 phút | GPS, offline map, ECDSA và quyền Android | Liệt kê các trường hợp dữ liệu chưa đáng tin |
| 5 trong 90 phút | Protocol, PDR, trễ, pin và trình bày | Điền một bảng tính minh họa có nhãn bài tập, tự thuyết trình 5 phút |

## Cách chạy lại bộ kiểm chứng

Mở thư mục Nam/NCKH trong Android Studio hoặc PowerShell. Chọn JDK 17 hoặc 21. Máy này đã kiểm chứng JBR 21.0.11 tại .jdks. Android SDK platform 35 đã có. Dùng scripts/verify-t4.ps1; nếu máy chưa có Gradle mà dùng wrapper, cần Internet ở lần tải dependency đầu tiên. Build lần đầu cần mạng, còn bản APK đã cài có thể hoạt động SOS offline.

Unit test: :core-map:testDebugUnitTest. Build: :citizen-app:assembleDebug và :rescue-app:assembleDebug. Room trên điện thoại: :core-map:connectedDebugAndroidTest. Kết quả bộ này là 25 unit test và 3 test Android đạt, hai APK build thành công. Test Android dùng một OnePlus Android 16; không gọi đó là thử đủ dải Android.

## Checklist cho buổi demo

- Nam mang mã nguồn, diff, APK đúng hash, báo cáo test và dây ADB.
- Huấn mang gói bản đồ khu vực, danh sách máy, raw pin và evidence hai máy cũ.
- Thao mang SRS v2, các ca AT, protocol và bản ghi ngưỡng được GVHD duyệt.
- Như mang link Forms, CSV ẩn danh, biên bản có ngày giờ, wireframe và danh sách người tham gia đồng ý.
- Có ít nhất 3 máy cho T4, tốt hơn là 5 cho T3-02, chuẩn bị 6 tới 10 cho kế hoạch nghiên cứu đầy đủ.
- Có sạc dự phòng, nhãn A tới E, khoảng cách và topology, đồng hồ quan sát và thư mục run_id trống.

## Kịch bản trình bày năm phút

Phút 1 nêu bài toán mất hạ tầng và giới hạn đường tiếp xúc. Phút 2 mô tả A lưu, B mang và C cứu hộ nhận. Phút 3 giải thích ID, summary và ACK trạng thái. Phút 4 trình kết quả 25 unit test, ba test Room và build APK, tách phần chưa có demo ba máy. Phút 5 xin ý kiến về thời hạn 6 giờ, số chặng, vị trí thiếu và ngưỡng thử. Không dùng số 41 trên 41 của buổi hai máy cũ để chứng minh chuỗi đa chặng.

## Phiếu SUS chuẩn bị cho buổi dùng thử

Đây là bản dịch làm việc để nhóm rà ngôn ngữ trước khi khảo sát, chưa tuyên bố là phiên bản SUS tiếng Việt đã được kiểm định. Giữ nguyên thứ tự câu thuận và nghịch. Với mỗi câu chọn từ 1 hoàn toàn không đồng ý tới 5 hoàn toàn đồng ý sau khi người tham gia đã thực hiện cùng một nhóm nhiệm vụ.

1. Tôi muốn sử dụng hệ thống này thường xuyên.
2. Tôi thấy hệ thống phức tạp hơn mức cần thiết.
3. Tôi thấy hệ thống dễ sử dụng.
4. Tôi nghĩ mình cần người có kiến thức kỹ thuật hỗ trợ để sử dụng hệ thống.
5. Tôi thấy các chức năng của hệ thống phối hợp tốt với nhau.
6. Tôi thấy hệ thống có nhiều điểm thiếu nhất quán.
7. Tôi nghĩ phần lớn mọi người có thể học cách dùng hệ thống nhanh chóng.
8. Tôi thấy hệ thống gây khó khăn khi sử dụng.
9. Tôi cảm thấy tự tin khi sử dụng hệ thống.
10. Tôi cần học khá nhiều trước khi có thể bắt đầu sử dụng hệ thống.

Nhập câu trả lời Q1 tới Q10 vào sheet SUS. Phiếu thiếu câu giữ kết quả trống. Lưu mã phiếu và nhiệm vụ, tránh thu tên hoặc số điện thoại nếu không cần. Phiếu đồng ý tham gia cần nêu mục đích nghiên cứu, thao tác thử, dữ liệu thu, quyền dừng và đầu mối liên hệ. Không tự điền thay ý kiến người tham gia.

## Tự kiểm tra

1. Nearby P2P_CLUSTER có tự tìm đường nhiều chặng cho ứng dụng không? Không, ứng dụng phải làm chuyển tiếp.
2. A không gặp B trong hai giờ thì ttl có tự giảm không? Không; tuổi tin tăng, ttl theo số chặng.
3. SENT có nghĩa cứu hộ nhận chưa? Chưa; nó chỉ là thành công một liên kết.
4. Fused accuracy 10 m có chứng minh sai số thật so với điểm chuẩn là 10 m không? Không; cần tham chiếu vị trí chuẩn và phương pháp đo.
5. Unit test 5 nút đạt có thay demo 5 điện thoại không? Không; nó chứng minh thuật toán ở mô hình test.
6. Chữ ký hợp lệ có chặn mọi tin sai không? Không; nó xác minh dữ liệu được ký bởi khóa đang tin cậy theo giả định của hệ thống.

## Nguồn để đọc tiếp

Google Nearby Connections overview và strategies tại developers.google.com/nearby/connections. Android foreground service types tại developer.android.com/develop/background-work/services/fgs/service-types. Room migration tại developer.android.com/training/data-storage/room/migrating-db-versions. RFC 9171 cho nguyên lý mạng chịu gián đoạn. Brooke 1996 cho SUS. Các yêu cầu cụ thể và trạng thái công việc phải đối chiếu Board gốc và hồ sơ T4, không lấy ví dụ bài học làm số liệu thử nghiệm.
