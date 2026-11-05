# Thiết kế kiến trúc và giao thức SOS Mesh

Đầu việc T3-01. Nguyễn Hoài Nam phụ trách, Thao hỗ trợ. Thiết kế đối chiếu commit nền 44b9015 và bản sửa T4 ngày 05/11/2026.

## Kiến trúc bốn tầng

Tầng trình bày gồm Activity, ViewModel và các hộp thoại. Tầng nghiệp vụ gồm quy tắc vị trí, MeshQueueEngine, ưu tiên, chống lặp và trạng thái. Tầng dữ liệu gồm Room, profile, log, bản đồ offline và gateway. Tầng kết nối gồm Nearby Connections, quyền Android và foreground service điều phối vòng quét. Đây là phân chia trách nhiệm logic; repo hiện thực ba module Gradle, không cần tạo bốn module chỉ để giống sơ đồ.

Luồng SOS: Activity kiểm tra vị trí, hộp thoại tạo dữ liệu, Room giữ dữ liệu, service nạp hàng đợi, Nearby gửi tới peer, peer nhận và lưu rồi chuyển tiếp. Ứng dụng cứu hộ phát ACK khi đã nhận. Mất Internet không cản đoạn Nearby và Room.

## Định dạng frame

| Frame | Trường chính |
| --- | --- |
| SUMMARY_VECTOR | frameType, nodeId, deviceName, androidApi, packetIds, statusRanks |
| PACKET | frameType, id, type, origin, senderName, latitude, longitude, peopleCount, conditionSummary, urgencyLevel, content, signature, createdAt, updatedAt, ttl, hops, status |

JSON UTF-8 được truyền bằng Nearby BYTES. Bản nhận giới hạn frame 32768 byte và summary tối đa 2000 ID để hạn chế parse dữ liệu quá lớn. Payload sản phẩm nên ngắn để phù hợp trần BYTES của Nearby. Thiết bị cần cùng service ID vn.tlu.sosmesh.p2p.CLUSTER_V1. Summary mới thêm statusRanks theo cách đọc được summary cũ; không tuyên bố hỗ trợ đầy đủ mọi bản giao thức tương lai.

ID SOS là định danh sự kiện; origin giữ nút gốc, không thay bằng relay. hops bắt đầu 0 ở nguồn, mỗi liên kết gửi tăng 1. createdAt không đổi qua các nút. updatedAt là thời điểm trạng thái được cập nhật cục bộ. DELIVERED và trạng thái cao hơn chỉ có ý nghĩa nghiệp vụ khi ACK được xem là đáng tin cậy.

## Thuật toán xử lý

Khi nhận tin, kiểm tra tuổi và giá trị định dạng, sau đó xác minh ALERT, kiểm tra ACK hợp lệ về cấu trúc rồi chống lặp. Tin mới vào queue và Room. Tin cùng ID chỉ nâng trạng thái; payload bất biến không bị ghi đè. Với ACK đến trước SOS, hàng đợi lưu ACK để áp dụng khi SOS được đưa vào sau.

Queue giữ tối đa 200 tin theo ưu tiên loại, mức khẩn và thời điểm. Bộ nhớ ID chống lặp có trần 2000 mặc định, dọn theo tuổi 6 giờ. Summary chỉ quảng bá tin thật sự đang giữ trong queue, không quảng bá toàn bộ tombstone. Vì trần hữu hạn, việc nhận lại tin sau khi ID bị loại là rủi ro cần đo trong thử tải, đặc biệt khi chính sách không giới hạn chặng đang bật.

Khi kết nối hoặc tới scan tick, trao đổi ID và thứ bậc trạng thái. Chỉ gửi phần peer thiếu hoặc trạng thái peer thấp hơn. Mỗi payload đang gửi có mục theo dõi để tránh khởi tạo trùng trong lúc truyền. Callback SUCCESS mới ghi SENT, FORWARDED hoặc ACK cho hướng gửi; lỗi ghi SEND_FAILED. Các sự kiện này phản ánh một liên kết, không thay ACK nghiệp vụ từ cứu hộ.

## Ngân sách chặng và thời hạn

Repo nền chủ ý dùng Int.MAX_VALUE cho mọi loại tin để không giới hạn chặng. Bản T4 giữ lựa chọn đó và bổ sung hỗ trợ ttl hữu hạn khi thử có kiểm soát. Trường hợp finite ttl bằng 2: A gửi B mang ttl 1 và hops 1; B gửi C mang ttl 0 và hops 2; C giữ để đọc nhưng không chuyển tiếp. Thời hạn 6 giờ áp dụng cả khi ttl không giới hạn.

Tài liệu cũ nói SOS ttl 5 hoặc 6 và CHAT ttl 2 hoặc 3 nên cần ghi phiên bản áp dụng. Không dùng các con số cũ để giải thích hành vi main hiện tại.

## Lưu bền và trạng thái

Room có messages, peers, logs, user_profile. Query updateStatus có điều kiện thứ bậc tăng. mergeMessage là transaction, giữ nội dung cũ khi cùng ID và chỉ nâng trạng thái. Service nạp lại Room sau khởi tạo. Thời hạn được kiểm tra cả queue và truy vấn dọn Room.

Migration v1 sang v2 hiện còn xóa bảng, là điểm cần Huấn sửa trước beta. Không đưa kết quả test Room trong bộ này thành bằng chứng migration bảo toàn dữ liệu; ba test hiện tại kiểm tra CSDL đang ở schema 2 và truy cập dữ liệu trong phiên test.

## Cơ chế nút cổng

FirebaseMeshGateway là thành phần tùy chọn. Khi có Internet và cấu hình thì tải SOS hoặc ACK lên Firestore và kéo ALERT hợp lệ về. Ngoại tuyến vẫn dùng Nearby. Gateway hiện ghi tập ID đã đồng bộ, nên cập nhật status của cùng ID sau lần đầu cần được kiểm thử và sửa trong T6-01. Phân quyền Firestore, cấu hình thật và thu hồi khóa chưa nằm trong bằng chứng chạy tại đây.

## Các quyết định cần review

Xác nhận hai APK thay cho một app chọn chế độ; giữ hay hạn chế số chặng; thời hạn 6 giờ có phù hợp ca mang tin dài hay không; chính sách không gửi SOS khi vị trí chưa đủ mới; cách xác thực ACK; giới hạn RAM và phân trang Room. Nam và Thao trình các quyết định này trong review M1 và M2 bổ sung, không ghi ngày duyệt lùi về tháng 10.

Nguồn: Board T3-01, T4-01; MeshQueueEngine.kt, MeshPacketCodec.kt, NearbyMeshTransport.kt, MeshForegroundService.kt, SosMeshDatabase.kt, FirebaseMeshGateway.kt. Nguồn nền tảng: https://developers.google.com/nearby/connections/android/exchange-data ; https://developer.android.com/training/data-storage/room .
