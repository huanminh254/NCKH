package vn.tlu.sosmesh.citizen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import vn.tlu.sosmesh.citizen.data.location.AndroidAddressRepository
import vn.tlu.sosmesh.citizen.data.location.AndroidLocationRepository
import vn.tlu.sosmesh.citizen.data.location.LocationSnapshot
import vn.tlu.sosmesh.citizen.presentation.AddressStatus
import vn.tlu.sosmesh.citizen.presentation.CitizenUiState
import vn.tlu.sosmesh.citizen.presentation.CitizenViewModel
import vn.tlu.sosmesh.citizen.presentation.CitizenViewModelFactory
import vn.tlu.sosmesh.citizen.presentation.LocationStatus
import vn.tlu.sosmesh.coremap.MapActivity
import vn.tlu.sosmesh.coremap.R as CoreMapR
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import vn.tlu.sosmesh.coremap.db.SosMeshDatabase
import vn.tlu.sosmesh.coremap.mesh.SosLocationPolicy
import vn.tlu.sosmesh.coremap.service.MeshForegroundService
import vn.tlu.sosmesh.coremap.service.MeshPermissionHelper
import vn.tlu.sosmesh.coremap.ui.MeshFeatureDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : MapActivity() {
    private lateinit var locationStatusText: TextView
    private lateinit var coordinatesText: TextView
    private lateinit var addressText: TextView
    private lateinit var meshStatusBannerText: TextView
    private lateinit var viewModel: CitizenViewModel
    private lateinit var database: SosMeshDatabase
    private var locationMarker: Marker? = null
    private val sosMarkers = mutableListOf<Marker>()
    private var hasCenteredOnInitialLocation = false
    private var hasCenteredOnFreshLocation = false
    private var lastCenteredPosition: LatLng? = null
    private var pendingUseLastKnown = true
    private var latestMessages: List<MeshMessageEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_ROLE, "SOS Mesh · Người dân")
        super.onCreate(savedInstanceState)

        database = SosMeshDatabase.getInstance(applicationContext)
        locationStatusText = findViewById(CoreMapR.id.locationStatusText)
        coordinatesText = findViewById(CoreMapR.id.coordinatesText)
        addressText = findViewById(CoreMapR.id.addressText)
        meshStatusBannerText = findViewById(CoreMapR.id.meshStatusBannerText)

        viewModel = ViewModelProvider(
            this,
            CitizenViewModelFactory(
                locationRepository = AndroidLocationRepository(applicationContext),
                addressRepository = AndroidAddressRepository(applicationContext)
            )
        )[CitizenViewModel::class.java]

        findViewById<Button>(CoreMapR.id.refreshLocationButton).setOnClickListener {
            hasCenteredOnInitialLocation = false
            hasCenteredOnFreshLocation = false
            startLocationOrRequestPermission(useLastKnown = false)
        }

        val primarySosBtn = findViewById<Button>(CoreMapR.id.primaryActionButton)
        primarySosBtn.text = "GỬI SOS MỘT CHẠM (≤ 3 THAO TÁC)"
        primarySosBtn.setOnClickListener {
            val loc = viewModel.uiState.value.location
            if (loc == null || !SosLocationPolicy.isUsable(loc.latitude, loc.longitude,
                    loc.accuracyMeters, loc.timestampMillis)) {
                Toast.makeText(this, "Đang lấy vị trí mới. Hãy bật Vị trí và thử lại ở nơi thoáng.",
                    Toast.LENGTH_LONG).show()
                startLocationOrRequestPermission(useLastKnown = false)
                return@setOnClickListener
            }
            val lat = loc.latitude
            val lon = loc.longitude
            MeshFeatureDialogs.showOneTapSosDialog(
                activity = this,
                scope = lifecycleScope,
                database = database,
                latitude = lat,
                longitude = lon
            ) {
                MeshForegroundService.startOrRefresh(applicationContext)
            }
        }

        findViewById<Button>(CoreMapR.id.alertButton).setOnClickListener {
            MeshFeatureDialogs.showCitizenAlertViewerDialog(
                activity = this,
                scope = lifecycleScope,
                database = database
            )
        }

        findViewById<Button>(CoreMapR.id.chatButton).setOnClickListener {
            val loc = viewModel.uiState.value.location
            MeshFeatureDialogs.showNearbyChatDialog(
                activity = this,
                scope = lifecycleScope,
                database = database,
                latitude = loc?.latitude ?: 21.0075,
                longitude = loc?.longitude ?: 105.8248
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
                launch {
                    viewModel.uiState.collect(::render)
                }
                launch {
                    combine(database.messagesFlow, database.peersFlow) { messages, peers ->
                        messages to peers
                    }.collect { (messages, peers) ->
                        latestMessages = messages
                        val latestSos = messages.firstOrNull { it.type == "SOS" }
                        meshStatusBannerText.text = if (latestSos != null) {
                            "SOS (${latestSos.urgencyLevel}): ${MeshFeatureDialogs.formatSosStatusProgression(latestSos.status)} · Nút lân cận: ${peers.size}"
                        } else {
                            "Mạng Mesh P2P_CLUSTER sẵn sàng · Nút đã gặp: ${peers.size} · Chưa phát SOS"
                        }
                        mapLibreMap?.let { renderSosMarkersOnMap(it, messages) }
                    }
                }
            }
        }

        MeshPermissionHelper.requestMissingPermissions(this)
        startLocationOrRequestPermission()
        if (hasLocationPermission()) {
            MeshForegroundService.startOrRefresh(applicationContext)
        }
        handleTriggerIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTriggerIntent(intent)
    }

    private fun handleTriggerIntent(intent: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val action = intent?.getStringExtra("trigger_action") ?: return
        intent.removeExtra("trigger_action")
        lifecycleScope.launch {
            val profile = database.getOrCreateProfile(applicationContext)
            val loc = viewModel.uiState.value.location
            val lat = loc?.latitude ?: 20.976878
            val lon = loc?.longitude ?: 105.820938
            val now = System.currentTimeMillis()
            when (action) {
                "SEND_SOS" -> {
                    if (loc == null || !SosLocationPolicy.isUsable(loc.latitude, loc.longitude,
                            loc.accuracyMeters, loc.timestampMillis)) return@launch
                    val sos = MeshMessageEntity(
                        id = "SOS-${profile.userId}-$now",
                        type = "SOS",
                        senderId = profile.userId,
                        senderName = profile.displayName,
                        latitude = lat,
                        longitude = lon,
                        peopleCount = intent.getIntExtra("people_count", 3),
                        conditionSummary = intent.getStringExtra("condition")
                            ?: "KHẨN CẤP (ĐỎ) – Ngập sâu / Có người bị thương, già yếu",
                        urgencyLevel = intent.getStringExtra("urgency") ?: "CRITICAL",
                        content = "Cần ứng cứu khẩn cấp tại tọa độ GPS hiện tại",
                        signature = null,
                        ttl = vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine.UNLIMITED_TTL,
                        hopCount = 0,
                        status = "QUEUED",
                        createdAt = now,
                        updatedAt = now
                    )
                    MeshForegroundService.broadcastPacket(applicationContext, sos)
                }
                "SEND_CHAT" -> {
                    val chat = MeshMessageEntity(
                        id = "CHAT-${profile.userId}-$now",
                        type = "CHAT",
                        senderId = profile.userId,
                        senderName = profile.displayName,
                        latitude = lat,
                        longitude = lon,
                        peopleCount = 1,
                        conditionSummary = "Tin nhắn lân cận",
                        urgencyLevel = "MEDIUM",
                        content = intent.getStringExtra("chat_text") ?: "Điểm tránh trú tầng 2 an toàn, còn nước sạch",
                        signature = null,
                        ttl = vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine.UNLIMITED_TTL,
                        hopCount = 0,
                        status = "QUEUED",
                        createdAt = now,
                        updatedAt = now
                    )
                    MeshForegroundService.broadcastPacket(applicationContext, chat)
                }
            }
        }
    }

    override fun onMapReady(map: MapLibreMap) {
        locationMarker = null
        if (::viewModel.isInitialized) {
            viewModel.uiState.value.location?.let { renderMarker(map, it, forceCenter = true) }
        }
        renderSosMarkersOnMap(map, latestMessages)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) {
            viewModel.startLocation(pendingUseLastKnown)
            MeshForegroundService.startOrRefresh(applicationContext)
        } else if (requestCode == LOCATION_PERMISSION_REQUEST ||
            requestCode == MeshPermissionHelper.MESH_PERMISSION_REQUEST_CODE
        ) {
            render(
                viewModel.uiState.value.copy(
                    status = LocationStatus.PERMISSION_REQUIRED,
                    message = "Cần cấp quyền vị trí chính xác để định vị Fused Location / GPS"
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationOrRequestPermission(useLastKnown: Boolean = true) {
        pendingUseLastKnown = useLastKnown
        if (hasLocationPermission()) {
            viewModel.startLocation(useLastKnown)
        } else {
            requestPermissions(
                MeshPermissionHelper.requiredPermissions(),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun render(state: CitizenUiState) {
        locationStatusText.text = state.message
        coordinatesText.text = state.location?.let(::formatLocation)
            ?: "Tọa độ: đang chờ Fused Location / GNSS"
        addressText.text = formatAddress(state)
        state.location?.let { snapshot ->
            mapLibreMap?.let { map -> renderMarker(map, snapshot, forceCenter = false) }
        }
    }

    private fun renderMarker(
        map: MapLibreMap,
        snapshot: LocationSnapshot,
        forceCenter: Boolean
    ) {
        val position = LatLng(snapshot.latitude, snapshot.longitude)
        val marker = locationMarker
        if (marker == null) {
            locationMarker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Vị trí hiện tại")
            )
        } else {
            marker.position = position
        }

        val shouldCenterFirstFresh = !snapshot.isLastKnown && !hasCenteredOnFreshLocation
        val shouldCenterInitial = !hasCenteredOnInitialLocation
        val movedSignificantly = hasMovedSignificantly(lastCenteredPosition, position)

        if (forceCenter || shouldCenterInitial || shouldCenterFirstFresh || movedSignificantly) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, DEFAULT_LOCATION_ZOOM))
            hasCenteredOnInitialLocation = true
            if (!snapshot.isLastKnown) {
                hasCenteredOnFreshLocation = true
            }
            lastCenteredPosition = position
        }
    }

    private fun renderSosMarkersOnMap(map: MapLibreMap, messages: List<MeshMessageEntity>) {
        sosMarkers.forEach { map.removeMarker(it) }
        sosMarkers.clear()
        messages.filter { it.type == "SOS" }.forEach { sos ->
            val icon = MeshFeatureDialogs.createUrgencyMarkerIcon(this, sos.urgencyLevel, sos.status)
            val m = map.addMarker(
                MarkerOptions()
                    .position(LatLng(sos.latitude, sos.longitude))
                    .title("SOS [${sos.urgencyLevel}] · ${sos.status}")
                    .snippet("${sos.peopleCount} người · ${sos.conditionSummary}")
                    .icon(icon)
            )
            sosMarkers.add(m)
        }
    }

    private fun hasMovedSignificantly(previous: LatLng?, current: LatLng): Boolean {
        if (previous == null) return true
        val distance = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
            distance
        )
        return distance[0] >= CAMERA_RECENTER_DISTANCE_METERS
    }

    private fun formatLocation(snapshot: LocationSnapshot): String {
        val accuracy = if (snapshot.accuracyMeters.isNaN()) {
            "không rõ"
        } else {
            String.format(Locale.US, "±%.1f m", snapshot.accuracyMeters)
        }
        val time = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
            .format(Date(snapshot.timestampMillis))
        val sourceType = if (snapshot.isLastKnown) "vị trí gần nhất" else "mới"
        return String.format(
            Locale.US,
            "Lat: %.6f · Lon: %.6f\nSai số: %s · Vệ tinh: %d · Nguồn: %s (%s) · Lúc: %s",
            snapshot.latitude,
            snapshot.longitude,
            accuracy,
            snapshot.satellitesUsedInFix,
            snapshot.provider,
            sourceType,
            time
        )
    }

    private fun formatAddress(state: CitizenUiState): String = when (state.addressStatus) {
        AddressStatus.IDLE -> "Địa chỉ: đang chờ tọa độ"
        AddressStatus.LOADING -> state.addressMessage ?: "Đang xác định địa chỉ online..."
        AddressStatus.READY -> "Địa chỉ online: ${state.address?.formatted.orEmpty()}"
        AddressStatus.ERROR -> state.addressMessage ?: "Địa chỉ (chế độ offline): tạm chưa có mạng"
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val DEFAULT_LOCATION_ZOOM = 16.5
        private const val CAMERA_RECENTER_DISTANCE_METERS = 15f
    }
}
