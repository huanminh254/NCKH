package vn.tlu.sosmesh.coremap.guide

data class SafetySection(
    val title: String,
    val steps: List<String>
)

object SafetyGuideRepository {
    fun getOfflineSafetySections(): List<SafetySection> = listOf(
        SafetySection(
            title = "1. Khi bị cô lập do lũ lụt / ngập sâu",
            steps = listOf(
                "Ngắt toàn bộ cầu dao điện tổng và khóa van bình gas ngay lập tức.",
                "Di chuyển lên tầng cao hoặc mái nhà kiên cố; mang theo nước uống, lương khô, thuốc thiết yếu.",
                "Bật Bluetooth + Vị trí (GPS) và mở ứng dụng SOS Mesh để thiết bị tự động làm nút chuyển tiếp.",
                "Bấm nút ĐỎ [GỬI SOS MỘT CHẠM] (≤ 3 thao tác) để phát tọa độ GPS qua các máy lân cận."
            )
        ),
        SafetySection(
            title = "2. Khi có nguy cơ lũ quét & sạt lở đất",
            steps = listOf(
                "Quan sát dấu hiệu: nước suối đột ngột đục ngầu, tiếng nổ/răng rắc từ sườn đồi, cây nghiêng.",
                "Rời khỏi thung lũng, khe suối, chân taluy dương ngay lập tức theo hướng vuông góc dòng chảy.",
                "Tuyệt đối không băng qua ngầm tràn, cầu tràn khi nước chảy xiết."
            )
        ),
        SafetySection(
            title = "3. Sơ cứu y tế khẩn cấp khi chờ đội cứu hộ",
            steps = listOf(
                "Chảy máu nặng: Dùng vải sạch ép trực tiếp lên vết thương và nâng cao chi bị thương.",
                "Hạ thân nhiệt: Lau khô người, ủ ấm vùng ngực - cổ - đầu bằng chăn khô hoặc áo mưa.",
                "Ghi rõ tình trạng người bị thương/người già/trẻ nhỏ trong phiếu SOS để đội cứu hộ ưu tiên."
            )
        ),
        SafetySection(
            title = "4. Duy trì pin & liên lạc mạng lưới SOS Mesh",
            steps = listOf(
                "Bật công tắc [Tiết kiệm pin] trên thanh trạng thái ứng dụng khi pin dưới 30% (chu kỳ quét 45 giây).",
                "Giảm độ sáng màn hình, tắt các ứng dụng ngầm không cần thiết nhưng giữ Bluetooth/Wi-Fi bật.",
                "Dùng tính năng [Nhắn tin lân cận] để hỏi thăm hàng xóm trong bán kính 50–100m."
            )
        )
    )
}
