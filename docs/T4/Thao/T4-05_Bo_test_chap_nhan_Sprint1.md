# Kiểm thử chấp nhận Sprint 1

Đầu việc T4-05. Hoàng Thị Thao phụ trách. Các ca AT01 tới AT28 trong sheet Test_chap_nhan của workbook là danh mục thực thi; mỗi dòng có tiền điều kiện, bước thực hiện, kết quả mong đợi, yêu cầu và mức kiểm chứng.

## Quy tắc ghi kết quả

Unit test đạt là bằng chứng cho logic đã gọi trong môi trường JVM. Kiểm thử Room đạt trên một máy là bằng chứng cho query SQLite và truy cập Room trên thiết bị đó. Chỉ ghi Đạt máy thật khi đúng ca chấp nhận đã thực hiện với số máy và topology được yêu cầu. Chụp báo cáo test và giữ XML; không đổi Chưa chạy thành Đạt theo cảm nhận.

## Phạm vi Sprint 1

Ưu tiên nghiệm thu FR01 tới FR08: vị trí, tạo SOS, lưu bền, kết nối, chuyển tiếp, hàng đợi và trạng thái. FR09 kiểm thử tích hợp với phần Huấn; FR10 tới FR12 dùng làm hồi quy và chuẩn bị Sprint tiếp theo. Yêu cầu lỗi DB phải hiện ra cho người dùng, service không crash khi thu hồi quyền và lỗi cloud không chặn mesh cần được đo thêm, chưa coi là đã xử lý chỉ vì có ca test.

## Nhóm ca quan trọng

AT01 tới AT06 kiểm tra đầu vào và không mất SOS. AT07 tới AT11 kiểm tra liên kết, nhiều chặng và thời hạn. AT12 tới AT16 kiểm tra chống lặp, tải và summary. AT17 tới AT20 kiểm tra ACK cũ, đến trước và quay về nguồn. AT21 tới AT28 kiểm tra UI cứu hộ, ALERT, gateway, log và trạng thái khi không có peer.

## Điều kiện trước buổi chạy

Cài cùng bản APK lên các máy, ghi hash và mã thiết bị. Chỉ rõ máy nguồn, relay và đích cứu hộ. Mỗi lần chạy có run_id riêng. Xóa dữ liệu thử cũ chỉ sau khi đã sao lưu và được người phụ trách lượt đo xác nhận; không xóa khóa chỉ huy trong thử nghiệm có ALERT. Kiểm tra pin và radio; đồng bộ đồng hồ trước khi ngắt Internet.

## Mẫu kết luận một ca

Ghi AT, run_id, người chạy, ngày giờ, APK, số máy, bước thực tế, quan sát, link log và kết luận Đạt, Không đạt hoặc Chưa chạy. Nếu hành vi khác dự kiến, tạo lỗi có bước tái hiện và mức ảnh hưởng. Review lại ca sau khi sửa để tránh kết luận từ bản APK cũ.

## Bằng chứng tự động đã chuẩn bị

MeshQueueEngineTest có các ca chống lặp, ưu tiên, ECDSA, summary, finite TTL, không giới hạn chặng, 4 nút, 5 nút, ACK đảo thứ tự, thời hạn, giới hạn bộ nhớ, codec và chính sách vị trí. MeshMessageDaoTest thực thi query chống lùi trạng thái, chống ghi đè payload cũ và nạp lại hàng đợi từ Room. Kết quả chi tiết nằm trong thư mục evidence của bộ bàn giao.

Nguồn: Board T4-02, T4-05; SRS v2 FR01 tới FR12; hai lớp test trong repo. Số ca và kết quả cuối cùng lấy từ XML bàn giao.
