package vn.tlu.sosmesh.rescue

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import vn.tlu.sosmesh.coremap.MapActivity
import vn.tlu.sosmesh.coremap.R as CoreMapR
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : MapActivity() {
    private lateinit var locationStatusText: TextView
    private lateinit var coordinatesText: TextView
    private lateinit var addressText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var locationMarker: Marker? = null
    private var currentLocation: Location? = null
    private var latestFreshGps: Location? = null
    private var lastCenteredPosition: LatLng? = null
    private var satellitesUsedInFix = 0
    private var addressJob: Job? = null
    private var lastGeocodedLocation: Location? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsedInFix = (0 until status.satelliteCount).count(status::usedInFix)
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (abs(System.currentTimeMillis() - location.time) <= 30_000L) {
                latestFreshGps = location
            }
            renderLocation(location)
        }
    }

    private val systemFusedListener = LocationListener { location ->
        renderLocation(location)
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val freshGps = latestFreshGps
            if (freshGps != null &&
                abs(System.currentTimeMillis() - freshGps.time) <= 10_000L &&
                freshGps.hasAccuracy() &&
                (!loc.hasAccuracy() || freshGps.accuracy < loc.accuracy)
            ) {
                renderLocation(freshGps)
                return
            }
            renderLocation(loc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_ROLE, "SOS Mesh · Đội cứu hộ")
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationStatusText = findViewById(CoreMapR.id.locationStatusText)
        coordinatesText = findViewById(CoreMapR.id.coordinatesText)
        addressText = findViewById(CoreMapR.id.addressText)

        findViewById<Button>(CoreMapR.id.refreshLocationButton).setOnClickListener {
            lastCenteredPosition = null
            startLocationOrRequestPermission()
        }

        startLocationOrRequestPermission()
    }

    override fun onMapReady(map: MapLibreMap) {
        locationMarker = null
        currentLocation?.let { updateMapMarker(map, it, forceCenter = true) }
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
            startFusedAndGpsUpdates()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            locationStatusText.text = "Cần cấp quyền vị trí chính xác để dùng Fused Location / GPS"
        }
    }

    private fun startLocationOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startFusedAndGpsUpdates()
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

    @SuppressLint("MissingPermission")
    private fun startFusedAndGpsUpdates() {
        stopLocationUpdates()
        locationStatusText.text = "Đang lấy tọa độ GPS (Fused Location)..."

        val candidates = mutableListOf<Location>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }
                .getOrNull()?.let(candidates::add)
        }
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()?.let(candidates::add)
        runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()?.let(candidates::add)
        candidates.maxByOrNull { it.time }?.let(::renderLocation)

        fusedClient.lastLocation.addOnSuccessListener { fusedLast ->
            if (fusedLast != null && (currentLocation == null || fusedLast.time > (currentLocation?.time ?: 0L))) {
                renderLocation(fusedLast)
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
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

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                gpsListener,
                Looper.getMainLooper()
            )
            runCatching {
                locationManager.registerGnssStatusCallback(gnssCallback, mainHandler)
            }
        }
    }

    private fun renderLocation(location: Location) {
        val prev = currentLocation
        if (prev != null) {
            val prevAcc = if (prev.hasAccuracy()) prev.accuracy else Float.MAX_VALUE
            val candAcc = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            if (candAcc > 35f && prevAcc <= 30f) return
        }
        currentLocation = location
        val acc = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val accText = if (acc.isNaN()) "chưa rõ" else String.format(Locale.US, "±%.1f m", acc)
        val isFresh = abs(System.currentTimeMillis() - location.time) <= 30_000L
        val sats = if (isFresh) {
            maxOf(satellitesUsedInFix, location.extras?.getInt("satellites", 0) ?: 0)
        } else {
            satellitesUsedInFix
        }
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
