package app.electronicmuyu.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.electronicmuyu.android.R

class SoundManager(context: Context) {

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(audioAttributes)
        .build()

    private val muyuHitId: Int = soundPool.load(context, R.raw.muyu_hit, 1)
    private val notificationTapId: Int = soundPool.load(context, R.raw.notification_tap, 1)

    fun playMuyuHit() {
        soundPool.play(muyuHitId, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    fun playNotificationTap() {
        soundPool.play(notificationTapId, 1.0f, 1.0f, 1, 0, 1.0f)
    }

    fun release() {
        soundPool.release()
    }
}