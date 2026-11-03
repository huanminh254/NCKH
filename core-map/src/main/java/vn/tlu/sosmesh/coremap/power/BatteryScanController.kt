package vn.tlu.sosmesh.coremap.power

import android.content.Context
import android.os.BatteryManager

enum class ScanProfile(
    val label: String,
    val scanIntervalMs: Long,
    val advertiseWindowMs: Long,
    val estimatedDrainPctPerHour: Double
) {
    SOS_ACTIVE("Khẩn cấp (Quét dày 5s)", 5_000L, 2_500L, 7.8),
    BALANCED("Cân bằng (Chu kỳ 15s)", 15_000L, 4_000L, 4.2),
    BATTERY_SAVER("Tiết kiệm pin (Chu kỳ 45s)", 45_000L, 6_000L, 1.9)
}

object BatteryScanController {
    fun currentBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
        return if (pct in 1..100) pct else 85
    }

    fun selectAdaptiveProfile(
        batteryPct: Int,
        userBatterySaverEnabled: Boolean,
        hasActiveCriticalSos: Boolean
    ): ScanProfile {
        return when {
            hasActiveCriticalSos && batteryPct > 15 -> ScanProfile.SOS_ACTIVE
            userBatterySaverEnabled || batteryPct <= 25 -> ScanProfile.BATTERY_SAVER
            else -> ScanProfile.BALANCED
        }
    }
}
