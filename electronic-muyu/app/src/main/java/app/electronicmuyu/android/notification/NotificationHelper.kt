package app.electronicmuyu.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.electronicmuyu.android.MainActivity
import app.electronicmuyu.android.R

object NotificationHelper {

    enum class DeliveryStatus(val label: String) {
        AVAILABLE("通知可用"),
        PERMISSION_DENIED("通知权限未授权"),
        APP_NOTIFICATIONS_DISABLED("系统已关闭本应用通知"),
        CHANNEL_DISABLED("功德提醒通知渠道已关闭")
    }

    private const val CHANNEL_ID = "merit_reminder"
    private const val CHANNEL_NAME = "功德提醒"
    private const val NOTIFICATION_ID = 1001
    private const val TAG = "ElectronicMuyu"

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到一次功德提醒"
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun getChannelImportance(context: Context): Int {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_NONE
    }

    fun getDeliveryStatus(context: Context): DeliveryStatus {
        if (!hasNotificationPermission(context)) {
            return DeliveryStatus.PERMISSION_DENIED
        }
        if (!areNotificationsEnabled(context)) {
            return DeliveryStatus.APP_NOTIFICATIONS_DISABLED
        }
        if (getChannelImportance(context) == NotificationManager.IMPORTANCE_NONE) {
            return DeliveryStatus.CHANNEL_DISABLED
        }
        return DeliveryStatus.AVAILABLE
    }

    fun sendMeritReminderNotification(context: Context): Boolean {
        val deliveryStatus = getDeliveryStatus(context)
        if (deliveryStatus != DeliveryStatus.AVAILABLE) {
            Log.e(TAG, "notify skipped: ${deliveryStatus.name}")
            return false
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_muyu)
            .setContentTitle("电子木鱼")
            .setContentText("收到一次功德提醒")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "notify success")
            true
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "notify failed: ${exception::class.simpleName} - ${exception.message}",
                exception
            )
            false
        }
    }
}
