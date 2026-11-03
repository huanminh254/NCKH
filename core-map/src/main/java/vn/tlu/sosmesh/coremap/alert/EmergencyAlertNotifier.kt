package vn.tlu.sosmesh.coremap.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity

object EmergencyAlertNotifier {
    private const val ALERT_CHANNEL_ID = "sos_mesh_critical_alert"
    private val SOS_VIBRATION_PATTERN = longArrayOf(0, 350, 150, 350, 150, 350, 350, 700, 200, 700)

    fun notifyCriticalAlert(context: Context, alert: MeshMessageEntity) {
        triggerLoudAlert(
            context = context,
            title = alert.conditionSummary.ifBlank { "CẢNH BÁO THIÊN TAI (${alert.urgencyLevel})" },
            message = alert.content
        )
    }

    fun triggerLoudAlert(context: Context, title: String, message: String) {
        vibrateSosPattern(context)
        playAlarmTone()
        postHighPriorityNotification(context, title, message)
    }

    private fun vibrateSosPattern(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(SOS_VIBRATION_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(SOS_VIBRATION_PATTERN, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun playAlarmTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
        } catch (_: Exception) {
        }
    }

    private fun postHighPriorityNotification(context: Context, title: String, message: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Cảnh báo Thiên tai Khẩn cấp (ALERT)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Cảnh báo cưỡng bức từ Ban Chỉ huy PCTT qua mạng SOS Mesh"
                    enableVibration(true)
                    vibrationPattern = SOS_VIBRATION_PATTERN
                }
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, ALERT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .build()
            nm.notify((System.currentTimeMillis() % 10000).toInt() + 2000, notification)
        } catch (_: Exception) {
        }
    }
}
