package vn.tlu.sosmesh.rescue

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import vn.tlu.sosmesh.coremap.MapActivity
import vn.tlu.sosmesh.coremap.R as CoreMapR
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine
import vn.tlu.sosmesh.coremap.service.MeshForegroundService
import vn.tlu.sosmesh.coremap.service.MeshPermissionHelper
import vn.tlu.sosmesh.coremap.ui.MeshFeatureDialogs
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : MapActivity() {
    private lateinit var locationStatusText: TextView
    private lateinit var coordinatesText: TextView
    private lateinit var addressText: TextView
    private lateinit var meshStatusBannerText: TextView
    private lateinit var database: SosMeshDatabase

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private var locationMarker: Marker? = null
    private val sosMarkers = mutableMapOf<Marker, MeshMessageEntity>()
    private var latestLocation: Location? = null
    private var lastCenteredPosition: LatLng? = null
    private var lastGeocodedLocation: Location? = null
    private var addressJob: Job? = null
    private var satellitesUsedInFix: Int = 0
    private var latestMessages: List<MeshMessageEntity> = emptyList()

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satellitesUsedInFix = used
        }
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    private val systemFusedListener = LocationListener { location -> onNewLocation(location) }
    private val gpsListener = LocationListener { location -> onNewLocation(location) }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_ROLE, "SOS Mesh · Đội Cứu Hộ")
        super.onCreate(savedInstanceState)

        database = SosMeshDatabase.getInstance(applicationContext)
        locationStatusText = findViewById(CoreMapR.id.locationStatusText)
        coordinatesText = findViewById(CoreMapR.id.coordinatesText)
        addressText = findViewById(CoreMapR.id.addressText)
        meshStatusBannerText = findViewById(CoreMapR.id.meshStatusBannerText)

        lifecycleScope.launch(Dispatchers.IO) {
            val profile = database.getOrCreateProfile(applicationContext)
            if (profile.activeMode != "RESCUE") {
                database.userProfileDao().saveProfile(profile.copy(activeMode = "RESCUE"))
            }
        }

        findViewById<Button>(CoreMapR.id.refreshLocationButton).setOnClickListener {
            lastCenteredPosition = null
            ensurePermissionAndStartLocation()
        }

        val dispatchBtn = findViewById<Button>(CoreMapR.id.primaryActionButton)
        dispatchBtn.text = "DANH SÁCH ƯU TIÊN SOS & PHẢN HỒI (ĐANG TỚI / ĐÃ TIẾP CẬN)"
        dispatchBtn.setBackgroundColor(Color.parseColor("#E65100"))
        dispatchBtn.setOnClickListener {
            openRescueDispatchSheet()
        }

        findViewById<Button>(CoreMapR.id.alertButton).setOnClickListener {
            showCommanderOfficialAlertDialog(
                latitude = latestLocation?.latitude ?: 21.0075,
                longitude = latestLocation?.longitude ?: 105.8248
            )
        }

        findViewById<Button>(CoreMapR.id.chatButton).setOnClickListener {
            MeshFeatureDialogs.showNearbyChatDialog(
                activity = this,
                scope = lifecycleScope,
                database = database,
                latitude = latestLocation?.latitude ?: 21.0075,
                longitude = latestLocation?.longitude ?: 105.8248
            )
        }

        findViewById<Button>(CoreMapR.id.safetyGuideButton).setOnClickListener {
            MeshFeatureDialogs.showSafetyGuideDialog(
                activity = this,
                scope = lifecycleScope,
                database = database
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(database.messagesFlow, database.peersFlow) { messages, peers ->
                    messages to peers
                }.collect { (messages, peers) ->
                    latestMessages = messages
                    updateRescueBannerAndMarkers(messages, peers.size)
                }
            }
        }

        MeshPermissionHelper.requestMissingPermissions(this)
        ensurePermissionAndStartLocation()
        if (hasLocationPermission()) {
            MeshForegroundService.startOrRefresh(applicationContext)
        }
    }


    private fun showCommanderOfficialAlertDialog(latitude: Double, longitude: Double) {
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                database.getOrCreateProfile(applicationContext)
            }
            val keystorePubB64 = withContext(Dispatchers.IO) {
                OfficialKeystoreSigner.ensureOfficialKeyPairAndTrust(applicationContext)
            }
            val alerts: List<MeshMessageEntity> = latestMessages.filter { it.type == "ALERT" }

            val root = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
            }

            val keystoreInfo = TextView(this@MainActivity).apply {
                text = "Chế độ Cán bộ Chỉ huy (Android Keystore ECDSA P-256 / SHA256withECDSA)\n" +
                    "Khóa công khai thiết bị cán bộ (dùng để cấp quyền / Trust List):\n$keystorePubB64"
                textSize = 11.5f
                setTextIsSelectable(true)
                setTextColor(Color.parseColor("#1B5E20"))
            }
            root.addView(keystoreInfo)

            val summaryInput = android.widget.EditText(this@MainActivity).apply {
                hint = "Tiêu đề cảnh báo khẩn cấp"
                setText("CẢNH BÁO KHẨN CẤP: Lũ dâng cao & sơ tán khu vực trũng")
            }
            root.addView(summaryInput)

            val contentInput = android.widget.EditText(this@MainActivity).apply {
                hint = "Nội dung chỉ đạo chính thức"
                setText("Đề nghị người dân di chuyển lên tầng cao, giữ kết nối Bluetooth/Wi-Fi để duy trì mạng SOS Mesh.")
            }
            root.addView(contentInput)

            val historyText = TextView(this@MainActivity).apply {
                setPadding(0, 16, 0, 0)
                textSize = 12f
                text = if (alerts.isEmpty()) {
                    "Chưa có ALERT nào trong bộ nhớ."
                } else {
                    alerts.take(3).joinToString("\n\n") { alertItem ->
                        val verified = vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier.verifyAlert(alertItem)
                        "• [${if (verified) "CHỮ KÝ HỢP LỆ" else "KHÔNG HỢP LỆ"}] ${alertItem.conditionSummary}\n  ${alertItem.content}"
                    }
                }
            }
            root.addView(historyText)

            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Phát Cảnh báo Cán bộ (Ký số Android Keystore)")
                .setView(root)
                .setPositiveButton("KÝ ECDSA & PHÁT ALERT") { _, _ ->
                    val summary = summaryInput.text?.toString()?.trim().orEmpty()
                        .ifEmpty { "CẢNH BÁO KHẨN CẤP" }
                    val content = contentInput.text?.toString()?.trim().orEmpty()
                        .ifEmpty { "Cảnh báo sơ tán khẩn cấp" }
                    val now = System.currentTimeMillis()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val unsigned = MeshMessageEntity(
                            id = "ALERT-${profile.userId}-$now",
                            type = "ALERT",
                            senderId = profile.userId,
                            senderName = "Ban Chỉ huy PCTT (${profile.displayName})",
                            latitude = latitude,
                            longitude = longitude,
                            peopleCount = 0,
                            conditionSummary = summary,
                            urgencyLevel = "CRITICAL",
                            content = content,
                            signature = null,
                            ttl = MeshQueueEngine.UNLIMITED_TTL,
                            hopCount = 0,
                            status = "DELIVERED",
                            createdAt = now,
                            updatedAt = now
                        )
                        val signed = OfficialKeystoreSigner.signOfficialAlert(applicationContext, unsigned)
                        vn.tlu.sosmesh.coremap.alert.EmergencyAlertNotifier.notifyCriticalAlert(applicationContext, signed)
                        MeshForegroundService.broadcastPacket(applicationContext, signed)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "Đã ký ECDSA qua Android Keystore và phát ALERT!",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNeutralButton("Cấu hình Trust List") { _, _ ->
                    MeshFeatureDialogs.showCitizenAlertViewerDialog(
                        activity = this@MainActivity,
                        scope = lifecycleScope,
                        database = database
                    )
                }
                .setNegativeButton("Đóng", null)
                .show()
        }
    }

    private fun openRescueDispatchSheet() {
        val lat = latestLocation?.latitude ?: 21.0075
        val lon = latestLocation?.longitude ?: 105.8248
        MeshFeatureDialogs.showRescueDispatchDialog(
            activity = this,
            scope = lifecycleScope,
            database = database,
            rescueLat = lat,
            rescueLon = lon
        ) {
            MeshForegroundService.startOrRefresh(applicationContext)
        }
    }

    private fun updateRescueBannerAndMarkers(messages: List<MeshMessageEntity>, peerCount: Int) {
        val rescueLat = latestLocation?.latitude ?: 21.0075
        val rescueLon = latestLocation?.longitude ?: 105.8248
        val sortedSos = MeshFeatureDialogs.sortSosForRescue(messages, rescueLat, rescueLon)
        val topSos = sortedSos.firstOrNull()
        meshStatusBannerText.text = if (topSos != null) {
            val dist = MeshQueueEngine.haversineMeters(
                rescueLat,
                rescueLon,
                topSos.latitude,
                topSos.longitude
            )
            "Ưu tiên #1: [${topSos.urgencyLevel}] ${topSos.peopleCount} người · Cách ${String.format(Locale.US, "%.0f m", dist)} · Tổng SOS: ${sortedSos.size} · Nút: $peerCount"
        } else {
            "Đội Cứu Hộ sẵn sàng · Đang lắng nghe SOS qua P2P_CLUSTER · Nút đã gặp: $peerCount"
        }
        mapLibreMap?.let { renderRescueSosMarkers(it, sortedSos) }
    }

    private fun renderRescueSosMarkers(map: MapLibreMap, sortedSos: List<MeshMessageEntity>) {
        sosMarkers.keys.forEach { map.removeMarker(it) }
        sosMarkers.clear()
        val rescueLat = latestLocation?.latitude ?: 21.0075
        val rescueLon = latestLocation?.longitude ?: 105.8248
        sortedSos.forEachIndexed { index, sos ->
            val dist = MeshQueueEngine.haversineMeters(rescueLat, rescueLon, sos.latitude, sos.longitude)
            val icon = MeshFeatureDialogs.createUrgencyMarkerIcon(this, sos.urgencyLevel, sos.status)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(sos.latitude, sos.longitude))
                    .title("#${index + 1} [${sos.urgencyLevel}] ${sos.senderName} (${String.format(Locale.US, "%.0f m", dist)})")
                    .snippet("${sos.peopleCount} người · ${sos.conditionSummary} · Trạng thái: ${sos.status}")
                    .icon(icon)
            )
            sosMarkers[marker] = sos
        }
        map.setOnInfoWindowClickListener { marker ->
            if (sosMarkers.containsKey(marker)) {
                openRescueDispatchSheet()
                true
            } else {
                false
            }
        }
    }

    override fun onMapReady(map: MapLibreMap) {
        locationMarker = null
        latestLocation?.let { updateMapMarker(map, it, forceCenter = true) }
        val rescueLat = latestLocation?.latitude ?: 21.0075
        val rescueLon = latestLocation?.longitude ?: 105.8248
        renderRescueSosMarkers(map, MeshFeatureDialogs.sortSosForRescue(latestMessages, rescueLat, rescueLon))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) {
            startLocationUpdates()
            MeshForegroundService.startOrRefresh(applicationContext)
        } else if (requestCode == LOCATION_PERMISSION_REQUEST ||
            requestCode == MeshPermissionHelper.MESH_PERMISSION_REQUEST_CODE
        ) {
            locationStatusText.text = "Cần cấp quyền vị trí để định vị Fused Location / GPS"
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionAndStartLocation() {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestPermissions(
                MeshPermissionHelper.requiredPermissions(),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        stopLocationUpdates()
        locationStatusText.text = "Đang lấy tọa độ GPS (Fused Location)..."

        runCatching {
            locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
        }

        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && latestLocation == null) onNewLocation(loc)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()

        runCatching {
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    1_000L,
                    0f,
                    systemFusedListener,
                    Looper.getMainLooper()
                )
            }
        }

        if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private fun onNewLocation(location: Location) {
        val current = latestLocation
        if (current != null && location.time < current.time && location.accuracy >= current.accuracy) {
            return
        }
        latestLocation = location
        val accText = if (location.hasAccuracy()) {
            String.format(Locale.US, "±%.1f m", location.accuracy)
        } else {
            "không rõ"
        }
        val sats = location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }
            ?: satellitesUsedInFix
        val providerLabel = if (sats > 0 || location.provider == LocationManager.GPS_PROVIDER) {
            "Fused + GNSS"
        } else {
            "Fused Location"
        }
        val timeText = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
            .format(Date(location.time))

        locationStatusText.text = "Định vị $providerLabel ổn định · sai số $accText"
        coordinatesText.text = String.format(
            Locale.US,
            "Lat: %.6f · Lon: %.6f\nSai số: %s · Vệ tinh: %d · Nguồn: %s · Lúc: %s",
            location.latitude,
            location.longitude,
            accText,
            sats,
            providerLabel,
            timeText
        )

        mapLibreMap?.let { updateMapMarker(it, location, forceCenter = false) }
        updateRescueBannerAndMarkers(latestMessages, 0)
        lookupAddressIfNeeded(location)
    }

    private fun updateMapMarker(map: MapLibreMap, location: Location, forceCenter: Boolean) {
        val pos = LatLng(location.latitude, location.longitude)
        if (locationMarker == null) {
            locationMarker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("Vị trí đội cứu hộ")
            )
        } else {
            locationMarker?.position = pos
        }

        val movedFar = lastCenteredPosition?.let { prev ->
            val d = FloatArray(1)
            Location.distanceBetween(prev.latitude, prev.longitude, pos.latitude, pos.longitude, d)
            d[0] >= 20f
        } ?: true

        if (forceCenter || movedFar) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 16.0))
            lastCenteredPosition = pos
        }
    }

    private fun lookupAddressIfNeeded(location: Location) {
        val prev = lastGeocodedLocation
        if (prev != null) {
            val d = FloatArray(1)
            Location.distanceBetween(prev.latitude, prev.longitude, location.latitude, location.longitude, d)
            if (d[0] < 30f) return
        }
        lastGeocodedLocation = location
        addressJob?.cancel()
        addressText.text = "Đang xác định địa chỉ online..."
        addressJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = URL(
                        String.format(
                            Locale.US,
                            "https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f",
                            location.latitude,
                            location.longitude
                        )
                    )
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3_500
                        readTimeout = 3_500
                        setRequestProperty("User-Agent", "SosMeshAndroid/1.0")
                    }
                    try {
                        if (conn.responseCode == 200) {
                            val features = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                                .optJSONArray("features")
                            val props = features?.optJSONObject(0)?.optJSONObject("properties")
                            if (props != null) {
                                listOfNotNull(
                                    props.optString("street").takeIf { it.isNotBlank() }
                                        ?: props.optString("name").takeIf { it.isNotBlank() },
                                    props.optString("locality").takeIf { it.isNotBlank() },
                                    props.optString("district").takeIf { it.isNotBlank() },
                                    props.optString("city").takeIf { it.isNotBlank() }
                                ).distinct().joinToString(", ")
                            } else null
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            addressText.text = if (!result.isNullOrBlank()) {
                "Địa chỉ online: $result"
            } else {
                "Địa chỉ (chế độ offline): tạm chưa có mạng để tra cứu"
            }
        }
    }

    private fun stopLocationUpdates() {
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
        runCatching { locationManager.removeUpdates(systemFusedListener) }
        runCatching { locationManager.removeUpdates(gpsListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
    }

    override fun onDestroy() {
        addressJob?.cancel()
        stopLocationUpdates()
        super.onDestroy()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1002
    }
}
