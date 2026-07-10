package app.electronicmuyu.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.electronicmuyu.android.R

class SoundManager(context: Context) {

    private val lock = Any()
    private val loadedSamples = mutableSetOf<Int>()
    private var pendingMuyuHit = false
    private var pendingNotificationTap = false
    private var released = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(audioAttributes)
        .build()

    private val muyuHitId: Int
    private val notificationTapId: Int

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener

            val shouldPlay = synchronized(lock) {
                if (released) return@synchronized false
                loadedSamples.add(sampleId)
                when (sampleId) {
                    muyuHitId -> pendingMuyuHit.also { pendingMuyuHit = false }
                    notificationTapId -> pendingNotificationTap.also {
                        pendingNotificationTap = false
                    }
                    else -> false
                }
            }
            if (shouldPlay) {
                playSample(sampleId)
            }
        }

        muyuHitId = soundPool.load(context.applicationContext, R.raw.muyu_hit, 1)
        notificationTapId = soundPool.load(
            context.applicationContext,
            R.raw.notification_tap,
            1
        )
    }

    fun playMuyuHit() {
        if (markPendingUnlessLoaded(muyuHitId, isMuyu = true)) {
            playSample(muyuHitId)
        }
    }

    fun playNotificationTap() {
        if (markPendingUnlessLoaded(notificationTapId, isMuyu = false)) {
            playSample(notificationTapId)
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            loadedSamples.clear()
            pendingMuyuHit = false
            pendingNotificationTap = false
        }
        soundPool.setOnLoadCompleteListener(null)
        soundPool.release()
    }

    private fun markPendingUnlessLoaded(sampleId: Int, isMuyu: Boolean): Boolean {
        return synchronized(lock) {
            if (released) return@synchronized false
            if (sampleId in loadedSamples) {
                true
            } else {
                if (isMuyu) {
                    pendingMuyuHit = true
                } else {
                    pendingNotificationTap = true
                }
                false
            }
        }
    }

    private fun playSample(sampleId: Int) {
        synchronized(lock) {
            if (released) return
        }
        soundPool.play(sampleId, 1.0f, 1.0f, 1, 0, 1.0f)
    }
}
