# Môi trường và quy ước phát triển

Đầu việc T1-02. Nguyễn Hoài Nam phụ trách. Repo gốc https://github.com/hnem06/NCKH được lấy tại commit 44b9015. Bản sửa nằm ở nhánh local work/hoan-thien-t4; nhánh dev local được tạo từ main để có luồng tích hợp. Việc bảo vệ nhánh và PR review trên GitHub cần cấu hình tại repo khi nhóm tích hợp.

## Cấu trúc hiện tại

citizen-app là APK người dân. rescue-app là APK cứu hộ. core-map chứa bản đồ, Room, giao thức mesh, service, pin và xác thực ALERT. Kotlin dùng JVM target 17, minSdk 26, compileSdk 35, AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Room 2.6.1. Không chuyển sang cấu trúc module khác khi chưa có nhu cầu tách trách nhiệm rõ hơn.

## Môi trường đã kiểm chứng

Build được thực hiện bằng JBR 21.0.11, Gradle 8.11.1 và Android SDK platform 35, sau đó kiểm chứng lại thành công bằng wrapper Gradle 8.9 của repo. Android Studio đang cài có JBR 25; Gradle hiện tại lỗi khi dùng JBR 25. Cần đặt JAVA_HOME sang JDK 17 hoặc 21 phù hợp. File script verify-t4.ps1 dùng wrapper mặc định, cũng cho phép chọn Gradle cục bộ.

Hai lệnh build chính là :citizen-app:assembleDebug và :rescue-app:assembleDebug. Không có google-services.json vẫn build offline theo cơ chế có sẵn; Firebase thực tế chưa được cấu hình trong thư mục này.

## Quy ước thay đổi

Mỗi việc dùng một nhánh feature hoặc fix, ghi mã T trong PR. Người dev còn lại đọc phần thay đổi và kết quả test. BA đối chiếu ca chấp nhận. Tích hợp vào dev, chỉ đưa bản đã qua chấp nhận lên main. Mẫu PR đã có tại .github/PULL_REQUEST_TEMPLATE.md. Không ghi đã merge hoặc đã review chéo khi mới sửa ở local.

Mỗi bản thử có commit nền, diff, hash APK, phiên bản JDK, Gradle và mã máy. File local.properties và google-services.json là cấu hình máy, không đưa vào báo cáo công khai. Bằng chứng thử nghiệm tách khỏi mã nguồn và tránh lộ vị trí cá nhân.

## Kết quả T1

T1-01 và T1-03 đã được Board gốc ghi Xong; bản bàn giao giữ nhận định nguồn đó, không xác nhận lại việc nộp hồ sơ hay phân quyền Drive. T1-02 đã có repo, module và build, còn review và tích hợp theo quy ước nhóm nên để Chờ review.
