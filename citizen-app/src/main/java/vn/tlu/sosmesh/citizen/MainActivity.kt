package vn.tlu.sosmesh.citizen

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import vn.tlu.sosmesh.citizen.data.location.AndroidAddressRepository
import vn.tlu.sosmesh.citizen.data.location.AndroidLocationRepository
import vn.tlu.sosmesh.citizen.data.location.LocationSnapshot
import vn.tlu.sosmesh.citizen.presentation.CitizenUiState
import vn.tlu.sosmesh.citizen.presentation.CitizenViewModel
import vn.tlu.sosmesh.citizen.presentation.CitizenViewModelFactory
import vn.tlu.sosmesh.citizen.presentation.LocationStatus
import vn.tlu.sosmesh.coremap.MapActivity
import vn.tlu.sosmesh.coremap.R as CoreMapR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : MapActivity() {
    private lateinit var locationStatusText: TextView
    private lateinit var coordinatesText: TextView
    private lateinit var addressText: TextView
    private lateinit var viewModel: CitizenViewModel
    private var locationMarker: Marker? = null
    private var hasCenteredOnInitialLocation = false
    private var hasCenteredOnFreshLocation = false
    private var lastCenteredPosition: LatLng? = null
    private var pendingUseLastKnown = true

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_ROLE, "SOS Mesh · Người dân")
        super.onCreate(savedInstanceState)

        locationStatusText = findViewById(CoreMapR.id.locationStatusText)
        coordinatesText = findViewById(CoreMapR.id.coordinatesText)
        addressText = findViewById(CoreMapR.id.addressText)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }

        startLocationOrRequestPermission()
    }

    override fun onMapReady(map: MapLibreMap) {
        locationMarker = null
        if (::viewModel.isInitialized) {
            viewModel.uiState.value.location?.let { renderMarker(map, it, forceCenter = true) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            viewModel.startLocation(pendingUseLastKnown)
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            render(
                viewModel.uiState.value.copy(
                    status = LocationStatus.PERMISSION_REQUIRED,
                    message = "Cần cấp quyền vị trí chính xác để định vị Fused Location / GPS"
                )
            )
        }
    }

    private fun startLocationOrRequestPermission(useLastKnown: Boolean = true) {
        pendingUseLastKnown = useLastKnown
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startLocation(useLastKnown)
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
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
            mapLibreMap?.let { renderMarker(it, snapshot) }
        }
    }

    private fun formatAddress(state: CitizenUiState): String {
        val address = state.address ?: return state.addressMessage
        val parts = listOfNotNull(
            address.street,
            address.area,
            address.district,
            address.city
        ).distinct()

        return "Địa chỉ online: " +
            (parts.joinToString(", ").ifBlank { address.formatted })
    }

    private fun renderMarker(
        map: MapLibreMap,
        snapshot: LocationSnapshot,
        forceCenter: Boolean = false
    ) {
        val position = LatLng(snapshot.latitude, snapshot.longitude)
        if (locationMarker == null) {
            locationMarker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Vị trí thiết bị (${snapshot.provider})")
            )
        } else {
            locationMarker?.position = position
        }

        val movedFarFromCamera = lastCenteredPosition?.let { prev ->
            val dist = FloatArray(1)
            Location.distanceBetween(
                prev.latitude,
                prev.longitude,
                position.latitude,
                position.longitude,
                dist
            )
            dist[0] >= 20f
        } ?: true

        if (forceCenter ||
            !hasCenteredOnInitialLocation ||
            (!snapshot.isLastKnown && !hasCenteredOnFreshLocation) ||
            movedFarFromCamera
        ) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16.0))
            hasCenteredOnInitialLocation = true
            if (!snapshot.isLastKnown) {
                hasCenteredOnFreshLocation = true
            }
            lastCenteredPosition = position
        }
    }

    private fun formatLocation(snapshot: LocationSnapshot): String {
        val accuracy = snapshot.accuracyMeters
        val accuracyText = if (accuracy.isNaN()) {
            "chưa rõ"
        } else {
            "±${accuracy.formatOneDecimal()} m"
        }
        val accuracyQuality = when {
            accuracy.isNaN() -> "chưa đánh giá"
            accuracy <= 12f -> "rất tốt"
            accuracy <= 30f -> "ổn định"
            else -> "yếu"
        }
        val source = if (snapshot.isLastKnown) {
            "${snapshot.provider} (gần nhất)"
        } else {
            snapshot.provider
        }
        val timestamp = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
            .format(Date(snapshot.timestampMillis))

        return "Lat: ${snapshot.latitude.formatSixDecimals()} · " +
            "Lon: ${snapshot.longitude.formatSixDecimals()}\n" +
            "Sai số: $accuracyText · Vệ tinh: ${snapshot.satellitesUsedInFix} · " +
            "Chất lượng: $accuracyQuality · Nguồn: $source · Lúc: $timestamp"
    }

    private fun Double.formatSixDecimals(): String =
        String.format(Locale.US, "%.6f", this)

    private fun Float.formatOneDecimal(): String =
        String.format(Locale.US, "%.1f", this)

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }
}
