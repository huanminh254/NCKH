# Ho so SOS Mesh toi tuan 4

Bo ho so duoc chuan bi ngay 05/11/2026 va dua vao nhanh
`work/hoan-thien-t4`. T4 tren Board goc la tuan 12-18/10/2026.

## Tai lieu

- [So tay hoc va checklist chuan bi](ban-giao/So_tay_hoc_va_chuan_bi.pdf).
- [Board cap nhat](ban-giao/Board_cong_viec_T4.xlsx).
- [Yeu cau, 28 test case va bieu mau PDR, pin, SUS](ban-giao/Kiem_thu_va_doi_chieu_T4.xlsx).
- [Thiet ke va ban giao cua Nam](ban-giao/Nam_Thiet_ke_va_ban_giao_T4.pdf).
- [Phan tich, SRS va protocol cua Thao](ban-giao/Thao_Phan_tich_SRS_va_kiem_thu_T4.pdf).
- [Nhan xet phan Huan va Nhu](ban-giao/Nhan_xet_Huan_Nhu.pdf).
- [Mau bien ban review](ban-giao/Bien_ban_review_mau.md).

Nguon Markdown cua Nam o thu muc nay; nguon cua Thao o [Thao](Thao).
Cac tai lieu ghi ngay 05/11 la ban chup ho so tai thoi diem ban giao.

## Kiem chung

25 unit test dat, khong loi va khong bo qua. Ba instrumentation test Room
dat tren OnePlus OPD2413 Android 16 trong phien 05/11/2026. Hai APK debug
build thanh cong bang JBR 21.0.11, Gradle 8.11.1 va wrapper Gradle 8.9.
Build va unit test duoc kiem tra lai ngay 06/11/2026 bang wrapper.

Chay tren Windows voi JDK 17 hoac 21 va Android SDK:

```powershell
.\scripts\verify-t4.ps1 -JavaHome '<JDK_PATH>'
```

Them `-DeviceTests` khi thiet bi thu nghiem da san sang qua ADB.
APK duoc tao trong `citizen-app/build/outputs/apk/debug` va
`rescue-app/build/outputs/apk/debug`.

## Dieu kien con cho nghiem thu

Can demo Nearby tren it nhat 3 may that cho T4-01 va 4-5 may cho T3-02,
giu log va kiem soat topology. Can review cheo va GVHD xac nhan SRS,
nguong hieu nang va cac moc theo quy uoc nhom. Cac muc nay duoc ghi
`Dang lam` hoac `Cho review` trong Board, khong thay bang ket qua mo phong.

Nhan xet chi tiet co cac dau viec con mo nhu migration Room giu du lieu,
xac thuc ACK, quyen foreground service, du lieu khao sat goc va wireframe.
