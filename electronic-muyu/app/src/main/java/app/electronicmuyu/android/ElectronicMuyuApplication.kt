package app.electronicmuyu.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.electronicmuyu.android.service.MuyuConnectionRepository

/**
 * 进程级前后台状态源。
 *
 * 该状态不能依赖 MainViewModel：Activity/ViewModel 被重建或暂时不存在时，
 * Foreground Service 仍需正确决定是否发送普通系统通知。
 */
class ElectronicMuyuApplication : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super<Application>.onCreate()
        MuyuConnectionRepository.setAppForeground(false)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        MuyuConnectionRepository.setAppForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        MuyuConnectionRepository.setAppForeground(false)
    }
}
