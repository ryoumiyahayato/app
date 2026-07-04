package app.electronicmuyu.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.electronicmuyu.android.MainActivity
import app.electronicmuyu.android.R

/**
 * 阶段 4A：通知权限与通知栏提醒骨架
 *
 * NotificationHelper 只负责三件事：
 * 1. 创建通知渠道
 * 2. 检查通知权限
 * 3. 发送"收到一次功德提醒"通知
 *
 * 不做：
 * - 常驻通知
 * - Foreground Service 通知
 * - 聊天通知
 * - 历史消息通知
 * - 多消息聚合
 * - 回复按钮 / 输入框 / 自由文本
 * - 离线消息 / 账号相关内容
 */
object NotificationHelper {

    private const val CHANNEL_ID = "merit_reminder"
    private const val CHANNEL_NAME = "功德提醒"
    private const val NOTIFICATION_ID = 1001
    private const val TAG = "ElectronicMuyu"

    /**
     * 创建通知渠道。应在 Application 启动或首次需要时调用一次即可。
     */
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

    /**
     * 检查当前是否有 POST_NOTIFICATIONS 权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下不需要运行时权限，通知默认开启
            true
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun getChannelImportance(context: Context): Int {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.getNotificationChannel(CHANNEL_ID)?.importance ?: -1
    }

    /**
     * 发送"收到一次功德提醒"通知
     *
     * @param context 上下文
     * @return 是否成功发送（false 表示无权限或发送失败）
     */
    fun sendMeritReminderNotification(context: Context): Boolean {
        if (!hasNotificationPermission(context)) {
            Log.e(TAG, "notify failed: notification permission not granted")
            return false
        }

        if (!areNotificationsEnabled(context)) {
            Log.e(TAG, "notify failed: notifications disabled by system")
            return false
        }

        if (getChannelImportance(context) == NotificationManager.IMPORTANCE_NONE) {
            Log.e(TAG, "notify failed: channel importance is IMPORTANCE_NONE")
            return false
        }

        // 点击通知回到 MainActivity
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
            Log.d(TAG, "NotificationHelper: calling notify($NOTIFICATION_ID)")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "notify success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "notify failed: ${e::class.simpleName} - ${e.message}", e)
            false
        }
    }
}
