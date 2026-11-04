package vn.tlu.sosmesh.coremap.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.alert.EmergencyAlertNotifier
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.guide.SafetyGuideRepository
import vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine
import vn.tlu.sosmesh.coremap.service.MeshForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MeshFeatureDialogs {

    fun sortSosForRescue(
        messages: List<MeshMessageEntity>,
        rescueLat: Double,
        rescueLon: Double
    ): List<MeshMessageEntity> {
        return messages
            .filter { it.type == "SOS" }
            .sortedWith(
                compareBy<MeshMessageEntity>(
                    { if (it.status == "REACHED") 1 else 0 },
                    { MeshQueueEngine.urgencyPriority(it.urgencyLevel) },
                    {
                        MeshQueueEngine.haversineMeters(
                            rescueLat,
                            rescueLon,
                            it.latitude,
                            it.longitude
                        )
                    },
                    { it.createdAt }
                )
            )
    }

    fun buildAckStatusPacket(
        targetSos: MeshMessageEntity,
        newStatus: String,
        responderNodeId: String,
        responderName: String,
        responderLat: Double,
        responderLon: Double,
        nowMs: Long = System.currentTimeMillis()
    ): MeshMessageEntity {
        val summary = when (newStatus) {
            "APPROACHING" -> "Đội cứu hộ đang di chuyển tới vị trí SOS"
            "REACHED" -> "Đội cứu hộ đã tiếp cận hiện trường SOS"
            else -> "Đã nhận tín hiệu SOS"
        }
        return MeshMessageEntity(
            id = "ACK-${targetSos.id}-$newStatus-$nowMs",
            type = "ACK_STATUS",
            senderId = responderNodeId,
            senderName = responderName,
            latitude = responderLat,
            longitude = responderLon,
            peopleCount = targetSos.peopleCount,
            conditionSummary = summary,
            urgencyLevel = targetSos.urgencyLevel,
            content = "${targetSos.id}|$newStatus",
            signature = null,
            ttl = MeshQueueEngine.UNLIMITED_TTL,
            hopCount = 0,
            status = newStatus,
            createdAt = nowMs,
            updatedAt = nowMs
        )
    }

    fun urgencyColorInt(urgencyLevel: String, status: String): Int {
        if (status == "REACHED") return Color.parseColor("#2E7D32")
        return when (urgencyLevel) {
            "CRITICAL" -> Color.parseColor("#D32F2F")
            "HIGH" -> Color.parseColor("#F57C00")
            else -> Color.parseColor("#FBC02D")
        }
    }

    fun createUrgencyMarkerIcon(context: Context, urgencyLevel: String, status: String): Icon {
        val sizePx = 56
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = urgencyColorInt(urgencyLevel, status)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.WHITE
        }
        val radius = sizePx / 2f - 4f
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, fillPaint)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, strokePaint)
        return IconFactory.getInstance(context).fromBitmap(bmp)
    }

    fun formatSosStatusProgression(status: String): String {
        val steps = listOf("QUEUED", "DELIVERED", "APPROACHING", "REACHED")
        val normalized = if (status == "BROADCASTING") "QUEUED" else status
        val activeIdx = steps.indexOf(normalized).coerceAtLeast(0)
        return steps.mapIndexed { idx, s ->
            if (idx <= activeIdx) "[$s]" else s
        }.joinToString(" → ")
    }

    fun showOneTapSosDialog(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase,
        latitude: Double,
        longitude: Double,
        onSosDispatched: (MeshMessageEntity) -> Unit
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 28, 40, 20)
        }

        val gpsText = TextView(activity).apply {
            text = String.format(
                Locale.US,
                "Tọa độ GPS hiện tại: %.6f, %.6f\n(≤ 3 thao tác: Chọn số người → Mức khẩn/Tình trạng → Bấm GỬI SOS)",
                latitude,
                longitude
            )
            textSize = 14f
            setTextColor(Color.parseColor("#1B5E20"))
        }
        container.addView(gpsText)

        val peopleLabel = TextView(activity).apply {
            text = "\n1. Số người cần hỗ trợ:"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(peopleLabel)

        val peopleGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val peopleOptions = listOf(1 to "1-2 người", 4 to "3-5 người", 8 to "6+ người")
        peopleOptions.forEachIndexed { index, (count, label) ->
            val rb = RadioButton(activity).apply {
                id = 100 + index
                text = label
                tag = count
                isChecked = index == 0
            }
            peopleGroup.addView(rb)
        }
        container.addView(peopleGroup)

        val urgencyLabel = TextView(activity).apply {
            text = "\n2. Mức độ khẩn cấp & Tình trạng:"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(urgencyLabel)

        val urgencyGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
        }
        val urgencyOptions = listOf(
            Triple("CRITICAL", "KHẨN CẤP (ĐỎ) – Ngập sâu / Có người bị thương, già yếu", "#B71C1C"),
            Triple("HIGH", "KHẨN CẤP CAO (CAM) – Bị cô lập hoàn toàn, cần sơ tán", "#E65100"),
            Triple("MEDIUM", "TRUNG BÌNH (VÀNG) – Thiếu nước sạch, thuốc men, lương thực", "#F57F17")
        )
        urgencyOptions.forEachIndexed { index, (level, desc, hex) ->
            val rb = RadioButton(activity).apply {
                id = 200 + index
                text = desc
                tag = level
                setTextColor(Color.parseColor(hex))
                isChecked = index == 0
            }
            urgencyGroup.addView(rb)
        }
        container.addView(urgencyGroup)

        val noteInput = EditText(activity).apply {
            hint = "Ghi chú thêm (không bắt buộc, VD: Tầng 2 nhà số 15)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(noteInput)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("PHÁT TÍN HIỆU CỨU NẠN (SOS MỘT CHẠM)")
            .setView(ScrollView(activity).apply { addView(container) })
            .setNegativeButton("Đóng", null)
            .create()

        val sendButton = Button(activity).apply {
            text = "3. GỬI SOS KHẨN CẤP VÀO MẠNG MESH"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setPadding(24, 28, 24, 28)
            setOnClickListener {
                val selectedPeopleRb = peopleGroup.findViewById<RadioButton>(peopleGroup.checkedRadioButtonId)
                val peopleCount = (selectedPeopleRb?.tag as? Int) ?: 2
                val selectedUrgencyRb = urgencyGroup.findViewById<RadioButton>(urgencyGroup.checkedRadioButtonId)
                val urgencyLevel = (selectedUrgencyRb?.tag as? String) ?: "CRITICAL"
                val conditionSummary = selectedUrgencyRb?.text?.toString()
                    ?: "Cần ứng cứu khẩn cấp tại chỗ"
                val extraNote = noteInput.text?.toString()?.trim().orEmpty()

                scope.launch {
                    val profile = database.getOrCreateProfile(activity.applicationContext)
                    val now = System.currentTimeMillis()
                    val sosPacket = MeshMessageEntity(
                        id = "SOS-${profile.userId}-$now",
                        type = "SOS",
                        senderId = profile.userId,
                        senderName = profile.displayName,
                        latitude = latitude,
                        longitude = longitude,
                        peopleCount = peopleCount,
                        conditionSummary = conditionSummary,
                        urgencyLevel = urgencyLevel,
                        content = extraNote.ifBlank { "$conditionSummary ($peopleCount người)" },
                        signature = null,
                        ttl = MeshQueueEngine.UNLIMITED_TTL,
                        hopCount = 0,
                        status = "QUEUED",
                        createdAt = now,
                        updatedAt = now
                    )
                    withContext(Dispatchers.IO) {
                        database.messageDao().upsertMessage(sosPacket)
                    }
                    MeshForegroundService.broadcastPacket(activity.applicationContext, sosPacket)
                    withContext(Dispatchers.Main) {
                        onSosDispatched(sosPacket)
                        Toast.makeText(
                            activity,
                            "Đã lưu Room & đẩy SOS vào hàng đợi Mesh (QUEUED)",
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
        }
        container.addView(sendButton)
        dialog.show()
    }

    fun showCitizenAlertViewerDialog(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase
    ) {
        AlertSignatureVerifier.loadPersistedTrustStore(activity.applicationContext)
        scope.launch {
            val alerts = withContext(Dispatchers.IO) {
                database.messageDao().getAllMessages().filter { it.type == "ALERT" }
            }
            val trustedCount = AlertSignatureVerifier.getTrustedPublicKeys().size
            withContext(Dispatchers.Main) {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 24, 40, 20)
                }

                container.addView(TextView(activity).apply {
                    text = "Chế độ Người dân (Chỉ nhận & xác thực ALERT bằng khóa công khai ECDSA P-256)\n" +
                        "Số khóa công khai Cán bộ trong danh sách tin cậy (Trust List): $trustedCount"
                    textSize = 13f
                    setTextColor(Color.parseColor("#0D47A1"))
                })

                container.addView(TextView(activity).apply {
                    text = "\nDanh sách Cảnh báo ALERT đã xác thực (${alerts.size}):"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })

                if (alerts.isEmpty()) {
                    container.addView(TextView(activity).apply {
                        text = "• Chưa có bản tin ALERT nào trong vùng phủ sóng."
                        textSize = 13f
                    })
                } else {
                    alerts.take(8).forEach { alert ->
                        val verified = AlertSignatureVerifier.verifyAlert(alert)
                        container.addView(TextView(activity).apply {
                            text = "• [${alert.urgencyLevel}] ${alert.conditionSummary}\n  ${alert.content}\n  Xác thực Trust List ECDSA: ${if (verified) "HỢP LỆ" else "TỪ CHỐI"} · Hops: ${alert.hopCount}"
                            textSize = 13f
                            setPadding(0, 8, 0, 8)
                        })
                    }
                }

                container.addView(TextView(activity).apply {
                    text = "\nĐăng ký thêm Khóa công khai Cán bộ (X.509 Base64 / từ mã QR cấp quyền):"
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })

                val pubKeyInput = EditText(activity).apply {
                    hint = "Dán khóa công khai X.509 Base64 của máy Cán bộ..."
                    textSize = 12f
                }
                container.addView(pubKeyInput)

                val importBtn = Button(activity).apply {
                    text = "Thêm Khóa Công Khai vào Trust List"
                    setOnClickListener {
                        val raw = pubKeyInput.text?.toString()?.trim().orEmpty()
                        val ok = AlertSignatureVerifier.registerTrustedOfficialPublicKey(
                            activity.applicationContext,
                            raw
                        )
                        Toast.makeText(
                            activity,
                            if (ok) "Đã thêm khóa công khai Cán bộ vào Trust List" else "Khóa công khai X.509 ECDSA không hợp lệ",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                container.addView(importBtn)

                AlertDialog.Builder(activity)
                    .setTitle("CẢNH BÁO THIÊN TAI (CHỈ XEM & XÁC THỰC)")
                    .setView(ScrollView(activity).apply { addView(container) })
                    .setPositiveButton("Đóng", null)
                    .show()
            }
        }
    }

    fun showNearbyChatDialog(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase,
        latitude: Double,
        longitude: Double
    ) {
        scope.launch {
            val chats = withContext(Dispatchers.IO) {
                database.messageDao().getAllMessages().filter { it.type == "CHAT" }
            }
            val peers = withContext(Dispatchers.IO) {
                database.peerDao().getAllPeers()
            }
            withContext(Dispatchers.Main) {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 24, 40, 20)
                }

                container.addView(TextView(activity).apply {
                    text = "Nút lân cận đã gặp: ${peers.size} nút · Tin nhắn lân cận (${chats.size}):"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })

                if (chats.isEmpty()) {
                    container.addView(TextView(activity).apply {
                        text = "• Chưa có tin nhắn CHAT lân cận nào."
                        textSize = 13f
                    })
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    chats.take(8).forEach { chat ->
                        container.addView(TextView(activity).apply {
                            text = "[${fmt.format(Date(chat.createdAt))}] ${chat.senderName} (${chat.hopCount} hop):\n${chat.content}"
                            textSize = 13f
                            setPadding(0, 6, 0, 6)
                        })
                    }
                }

                val chatInput = EditText(activity).apply {
                    hint = "Nhập tin nhắn ngắn gửi các máy lân cận..."
                }
                container.addView(chatInput)

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("NHẮN TIN LÂN CẬN QUA MESH (CHAT)")
                    .setView(ScrollView(activity).apply { addView(container) })
                    .setNegativeButton("Đóng", null)
                    .create()

                val sendChatBtn = Button(activity).apply {
                    text = "Gửi tin nhắn lân cận (P2P_CLUSTER)"
                    setOnClickListener {
                        val text = chatInput.text?.toString()?.trim().orEmpty()
                        if (text.isNotBlank()) {
                            scope.launch {
                                val profile = database.getOrCreateProfile(activity.applicationContext)
                                val now = System.currentTimeMillis()
                                val chatPacket = MeshMessageEntity(
                                    id = "CHAT-${profile.userId}-$now",
                                    type = "CHAT",
                                    senderId = profile.userId,
                                    senderName = profile.displayName,
                                    latitude = latitude,
                                    longitude = longitude,
                                    peopleCount = 1,
                                    conditionSummary = "Tin nhắn lân cận",
                                    urgencyLevel = "MEDIUM",
                                    content = text,
                                    signature = null,
                                    ttl = MeshQueueEngine.UNLIMITED_TTL,
                                    hopCount = 0,
                                    status = "QUEUED",
                                    createdAt = now,
                                    updatedAt = now
                                )
                                withContext(Dispatchers.IO) {
                                    database.messageDao().upsertMessage(chatPacket)
                                }
                                MeshForegroundService.broadcastPacket(activity.applicationContext, chatPacket)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(activity, "Đã đẩy tin nhắn CHAT vào Mesh", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                }
                container.addView(sendChatBtn)
                dialog.show()
            }
        }
    }

    fun showSafetyGuideDialog(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase
    ) {
        scope.launch {
            val profile = database.getOrCreateProfile(activity.applicationContext)
            withContext(Dispatchers.Main) {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 24, 40, 20)
                }

                val batterySaverBox = CheckBox(activity).apply {
                    text = "Chế độ Tiết kiệm pin (Chu kỳ quét thích nghi 45s)"
                    isChecked = profile.batterySaverEnabled
                    setOnCheckedChangeListener { _, checked ->
                        scope.launch(Dispatchers.IO) {
                            database.userProfileDao().saveProfile(
                                profile.copy(batterySaverEnabled = checked)
                            )
                            MeshForegroundService.startOrRefresh(activity.applicationContext)
                        }
                    }
                }
                container.addView(batterySaverBox)

                SafetyGuideRepository.getOfflineSafetySections().forEach { section ->
                    container.addView(TextView(activity).apply {
                        text = "\n${section.title}"
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    section.steps.forEach { step ->
                        container.addView(TextView(activity).apply {
                            text = "  • $step"
                            textSize = 13f
                            setPadding(0, 4, 0, 4)
                        })
                    }
                }

                val exportBtn = Button(activity).apply {
                    text = "Xuất Log Đo Lường Thực Nghiệm (CSV)"
                    setOnClickListener {
                        scope.launch {
                            val csvFile = database.exportLogsToCsv(activity.applicationContext)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    activity,
                                    "Đã xuất CSV: ${csvFile.absolutePath}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                container.addView(exportBtn)

                AlertDialog.Builder(activity)
                    .setTitle("CẨM NANG AN TOÀN OFFLINE & ĐO LƯỜNG")
                    .setView(ScrollView(activity).apply { addView(container) })
                    .setPositiveButton("Đóng", null)
                    .show()
            }
        }
    }

    fun showRescueDispatchDialog(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase,
        rescueLat: Double,
        rescueLon: Double,
        onStatusUpdated: () -> Unit
    ) {
        scope.launch {
            val allMessages = withContext(Dispatchers.IO) {
                database.messageDao().getAllMessages()
            }
            val sortedSos = sortSosForRescue(allMessages, rescueLat, rescueLon)
            withContext(Dispatchers.Main) {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(36, 24, 36, 20)
                }

                container.addView(TextView(activity).apply {
                    text = "Danh sách SOS sắp theo mức độ ưu tiên & khoảng cách (${sortedSos.size} tín hiệu):"
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("ĐIỀU PHỐI CỨU HỘ & PHẢN HỒI ACK_STATUS")
                    .setView(ScrollView(activity).apply { addView(container) })
                    .setNegativeButton("Đóng", null)
                    .create()

                if (sortedSos.isEmpty()) {
                    container.addView(TextView(activity).apply {
                        text = "\nChưa nhận được tín hiệu SOS nào trong vùng phủ sóng."
                        textSize = 14f
                    })
                } else {
                    sortedSos.forEach { sos ->
                        val distMeters = MeshQueueEngine.haversineMeters(
                            rescueLat,
                            rescueLon,
                            sos.latitude,
                            sos.longitude
                        )
                        val card = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(20, 20, 20, 20)
                        }
                        card.addView(TextView(activity).apply {
                            text = "[${sos.urgencyLevel}] ${sos.senderName} · ${sos.peopleCount} người · Cách ${String.format(Locale.US, "%.0f m", distMeters)}\n" +
                                "Tình trạng: ${sos.conditionSummary}\n" +
                                "Ghi chú: ${sos.content}\n" +
                                "Tọa độ: ${String.format(Locale.US, "%.5f, %.5f", sos.latitude, sos.longitude)} · Hops: ${sos.hopCount}\n" +
                                "Trạng thái: ${formatSosStatusProgression(sos.status)}"
                            textSize = 13f
                            setTextColor(urgencyColorInt(sos.urgencyLevel, sos.status))
                        })

                        val actionRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                        }

                        val approachingBtn = Button(activity).apply {
                            text = "Đang tới (APPROACHING)"
                            textSize = 12f
                            setOnClickListener {
                                dispatchRescueAck(
                                    activity = activity,
                                    scope = scope,
                                    database = database,
                                    targetSos = sos,
                                    newStatus = "APPROACHING",
                                    rescueLat = rescueLat,
                                    rescueLon = rescueLon
                                ) {
                                    onStatusUpdated()
                                    dialog.dismiss()
                                }
                            }
                        }

                        val reachedBtn = Button(activity).apply {
                            text = "Đã tiếp cận (REACHED)"
                            textSize = 12f
                            setOnClickListener {
                                dispatchRescueAck(
                                    activity = activity,
                                    scope = scope,
                                    database = database,
                                    targetSos = sos,
                                    newStatus = "REACHED",
                                    rescueLat = rescueLat,
                                    rescueLon = rescueLon
                                ) {
                                    onStatusUpdated()
                                    dialog.dismiss()
                                }
                            }
                        }

                        actionRow.addView(approachingBtn)
                        actionRow.addView(reachedBtn)
                        card.addView(actionRow)
                        container.addView(card)
                    }
                }

                dialog.show()
            }
        }
    }

    private fun dispatchRescueAck(
        activity: Activity,
        scope: CoroutineScope,
        database: SosMeshDatabase,
        targetSos: MeshMessageEntity,
        newStatus: String,
        rescueLat: Double,
        rescueLon: Double,
        onComplete: () -> Unit
    ) {
        scope.launch {
            val profile = database.getOrCreateProfile(activity.applicationContext)
            val now = System.currentTimeMillis()
            val ackPacket = buildAckStatusPacket(
                targetSos = targetSos,
                newStatus = newStatus,
                responderNodeId = profile.userId,
                responderName = profile.displayName,
                responderLat = rescueLat,
                responderLon = rescueLon,
                nowMs = now
            )
            withContext(Dispatchers.IO) {
                database.messageDao().updateStatus(targetSos.id, newStatus, now)
                database.messageDao().upsertMessage(ackPacket)
            }
            MeshForegroundService.broadcastPacket(activity.applicationContext, ackPacket)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    "Đã phát ACK_STATUS ($newStatus) cho ${targetSos.id} vào mạng Mesh",
                    Toast.LENGTH_SHORT
                ).show()
                onComplete()
            }
        }
    }
}
