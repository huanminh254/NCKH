package vn.tlu.sosmesh.citizen.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs

class AndroidLocationRepository(context: Context) : LocationRepository {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var waitingForFreshLocation = false
    private var satellitesUsedInFix = 0
    private var latestFreshHardwareGps: Location? = null

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsedInFix = (0 until status.satelliteCount)
                .count(status::usedInFix)
        }
    }

    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!isStale(location)) {
                latestFreshHardwareGps = location
            }
            handleIncomingLocation(location)
        }

        override fun onProviderDisabled(provider: String) {
            if (!isAnyLocationProviderEnabled()) {
                currentErrorCallback?.invoke(LocationError.GpsDisabled)
            }
        }
    }

    private val systemFusedListener = LocationListener { location ->
        val freshGps = latestFreshHardwareGps
        if (freshGps != null &&
            abs(System.currentTimeMillis() - freshGps.time) <= 10_000L &&
            freshGps.hasAccuracy() &&
            (!location.hasAccuracy() || freshGps.accuracy < location.accuracy)
        ) {
            handleIncomingLocation(freshGps)
            return@LocationListener
        }
        handleIncomingLocation(location)
    }

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val freshGps = latestFreshHardwareGps
            if (freshGps != null &&
                abs(System.currentTimeMillis() - freshGps.time) <= 10_000L &&
                freshGps.hasAccuracy() &&
                (!location.hasAccuracy() || freshGps.accuracy < location.accuracy)
            ) {
                handleIncomingLocation(freshGps)
                return
            }
            handleIncomingLocation(location)
        }
    }

    private var currentLocationCallback: ((LocationSnapshot) -> Unit)? = null
    private var currentErrorCallback: ((LocationError) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override fun start(
        onLocation: (LocationSnapshot) -> Unit,
        onError: (LocationError) -> Unit,
        useLastKnown: Boolean
    ) {
        stop()
        currentLocationCallback = onLocation
        currentErrorCallback = onError

        if (!hasLocationPermission()) {
            onError(LocationError.PermissionDenied)
            return
        }

        if (!isAnyLocationProviderEnabled()) {
            onError(LocationError.GpsDisabled)
            return
        }

        if (useLastKnown) {
            val syncBest = selectFreshestLastKnown(null)
            if (syncBest != null) {
                onLocation(syncBest.toSnapshot(isLastKnown = true))
            }
            fusedLocationClient.lastLocation.addOnSuccessListener { fusedLast ->
                if (currentLocationCallback == null || fusedLast == null) return@addOnSuccessListener
                if (syncBest == null || fusedLast.time > syncBest.time) {
                    currentLocationCallback?.invoke(fusedLast.toSnapshot(isLastKnown = true))
                }
            }
        }

        try {
            waitingForFreshLocation = !useLastKnown

            val fusedRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_INTERVAL_MILLIS
            )
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MILLIS)
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                fusedRequest,
                fusedLocationCallback,
                Looper.getMainLooper()
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
            ) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        LOCATION_INTERVAL_MILLIS,
                        0f,
                        systemFusedListener,
                        Looper.getMainLooper()
                    )
                }
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MILLIS,
                    0f,
                    gpsLocationListener,
                    Looper.getMainLooper()
                )
                runCatching {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler)
                }
            }

            if (!useLastKnown) {
                mainHandler.postDelayed(freshLocationTimeout, FRESH_LOCATION_TIMEOUT_MILLIS)
            }
        } catch (securityException: SecurityException) {
            waitingForFreshLocation = false
            onError(LocationError.PermissionDenied)
        } catch (exception: Exception) {
            waitingForFreshLocation = false
            onError(LocationError.Failed(exception.message ?: "Không thể khởi động Fused Location"))
        }
    }

    override fun stop() {
        runCatching { fusedLocationClient.removeLocationUpdates(fusedLocationCallback) }
        runCatching { locationManager.removeUpdates(systemFusedListener) }
        runCatching { locationManager.removeUpdates(gpsLocationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) }
        waitingForFreshLocation = false
        mainHandler.removeCallbacks(freshLocationTimeout)
        satellitesUsedInFix = 0
        latestFreshHardwareGps = null
        currentLocationCallback = null
        currentErrorCallback = null
    }

    private fun handleIncomingLocation(location: Location) {
        val isStale = isStale(location)
        if (waitingForFreshLocation && isStale) {
            return
        }

        if (waitingForFreshLocation) {
            waitingForFreshLocation = false
            mainHandler.removeCallbacks(freshLocationTimeout)
        }
        currentLocationCallback?.invoke(location.toSnapshot(isLastKnown = isStale))
    }

    @SuppressLint("MissingPermission")
    private fun selectFreshestLastKnown(fusedLast: Location?): Location? {
        val candidates = mutableListOf<Location>()
        if (fusedLast != null) candidates.add(fusedLast)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }
                .getOrNull()?.let(candidates::add)
        }
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()?.let(candidates::add)
        runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()?.let(candidates::add)
        return candidates.maxByOrNull { it.time }
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAnyLocationProviderEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private val freshLocationTimeout: Runnable = object : Runnable {
        override fun run() {
            if (waitingForFreshLocation) {
                waitingForFreshLocation = false
                val fallback = selectFreshestLastKnown(null)
                if (fallback != null) {
                    currentLocationCallback?.invoke(fallback.toSnapshot(isLastKnown = false))
                } else {
                    currentErrorCallback?.invoke(LocationError.Timeout)
                }
            }
        }
    }

    private fun isStale(location: Location): Boolean =
        abs(System.currentTimeMillis() - location.time) > MAX_FRESH_LOCATION_AGE_MILLIS

    private fun Location.toSnapshot(isLastKnown: Boolean): LocationSnapshot {
        val bundleSatellites = if (!isLastKnown) (extras?.getInt("satellites", 0) ?: 0) else 0
        val effectiveSatellites = maxOf(satellitesUsedInFix, bundleSatellites)
        val providerLabel = when {
            effectiveSatellites > 0 || provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) ->
                "Fused + GNSS"
            else -> "Fused Location"
        }
        return LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else Float.NaN,
            timestampMillis = time,
            provider = providerLabel,
            isLastKnown = isLastKnown,
            satellitesUsedInFix = effectiveSatellites
        )
    }

    private companion object {
        const val LOCATION_INTERVAL_MILLIS = 1_000L
        const val FASTEST_INTERVAL_MILLIS = 500L
        const val MAX_FRESH_LOCATION_AGE_MILLIS = 30_000L
        const val FRESH_LOCATION_TIMEOUT_MILLIS = 15_000L
    }
}
