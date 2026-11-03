# SOS Mesh — bản đồ online/offline

Khung Android ban đầu gồm hai ứng dụng độc lập:

- `citizen-app`: ứng dụng dành cho người dân.
- `rescue-app`: ứng dụng dành cho đội cứu hộ.
- `core-map`: mô-đun dùng chung, tích hợp MapLibre với bản đồ online và vùng bản đồ offline.

## Chạy thử

Mở thư mục này bằng Android Studio, đồng bộ Gradle, sau đó chạy một trong hai cấu hình:

- `citizen-app`
- `rescue-app`

Trong ứng dụng, nút **Tải bản đồ offline** tải trước vùng Hà Nội ở mức zoom 9–15. Sau đó bản đồ có thể hiển thị vùng đã tải khi thiết bị mất mạng.
