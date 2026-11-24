// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val KEY_DOUYIN_BG_AUDIOMIX_GUARD = "douyin_bg_audiomix_guard_enabled"
internal const val DOUYIN_PACKAGE_NAME = "com.ss.android.ugc.aweme"

/**
 * 抖音后台 AudioMix 唤醒锁防泄漏守护：
 * - 追踪抖音进程内创建的 AudioTrack，不持有强引用避免内存泄漏；
 * - 当抖音退至后台或设备息屏并稳定超过 8 秒后，自动暂停并冲刷残留的播放轨道，
 *   释放 audioserver 替其持有的 AudioMix PARTIAL_WAKE_LOCK，避免通宵阻止 Deep Doze；
 * - 用户切回抖音前台后立即解除拦截，不影响任何正常前台刷视频体验。
 */
internal object DouyinAudioMixGuardHooks {
    private const val TAG = "DouyinAudioMixGuard"
    private const val BG_RELEASE_DELAY_MS = 8_000L

    private val installed = AtomicBoolean(false)
    private val lifecycleAttached = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    @Volatile
    private var screenInteractive: Boolean = true

    @Volatile
    private var bgAudioBlocked: Boolean = false

    @Volatile
    private var powerManager: PowerManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedTracks: MutableSet<AudioTrack> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun install(
        module: HyperSystemUiModule,
        preferences: SharedPreferences,
    ) {
        if (!installed.compareAndSet(false, true)) return

        val isEnabled = { preferences.getBoolean(KEY_DOUYIN_BG_AUDIOMIX_GUARD, true) }

        val releaseBgTracksRunnable = Runnable {
            if (!isEnabled()) {
                bgAudioBlocked = false
                return@Runnable
            }
            val interactiveNow = powerManager?.isInteractive ?: screenInteractive
            if (startedActivities.get() > 0 && interactiveNow) {
                bgAudioBlocked = false
                return@Runnable
            }
            bgAudioBlocked = true
            silenceLeakedTracks(module)
        }

        val scheduleCheck: () -> Unit = {
            mainHandler.removeCallbacks(releaseBgTracksRunnable)
            if (isEnabled()) {
                val interactiveNow = powerManager?.isInteractive ?: screenInteractive
                if (startedActivities.get() <= 0 || !interactiveNow) {
                    mainHandler.postDelayed(releaseBgTracksRunnable, BG_RELEASE_DELAY_MS)
                } else {
                    bgAudioBlocked = false
                }
            } else {
                bgAudioBlocked = false
            }
        }

        // 1. 挂载 Application.onCreate 追踪前后台 Activity 状态与亮灭屏
        runCatching {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            module.hookDeoptimized(onCreate).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("douyin-audiomix-app-oncreate")
                .intercept { chain ->
                    val res = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && lifecycleAttached.compareAndSet(false, true)) {
                        attachLifecycle(app, scheduleCheck) {
                            mainHandler.removeCallbacks(releaseBgTracksRunnable)
                            bgAudioBlocked = false
                        }
                    }
                    res
                }
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not hook Application.onCreate", it)
        }

        // 2. Hook AudioTrack.play()：登记实例，并在后台封锁态阻止幽灵重播拉起 AudioMix
        runCatching {
            val playMethod = AudioTrack::class.java.getDeclaredMethod("play")
            module.hookDeoptimized(playMethod).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("douyin-audiomix-track-play")
                .intercept { chain ->
                    val track = chain.thisObject as? AudioTrack
                    if (track != null) trackedTracks.add(track)
                    if (isEnabled() && bgAudioBlocked) {
                        val interactiveNow = powerManager?.isInteractive ?: screenInteractive
                        if (startedActivities.get() <= 0 || !interactiveNow) {
                            return@intercept null
                        }
                        bgAudioBlocked = false
                    }
                    chain.proceed()
                }
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not hook AudioTrack.play", it)
        }

        // 3. Hook AudioTrack.release()：及时清理集合
        runCatching {
            val releaseMethod = AudioTrack::class.java.getDeclaredMethod("release")
            module.hookDeoptimized(releaseMethod).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("douyin-audiomix-track-release")
                .intercept { chain ->
                    (chain.thisObject as? AudioTrack)?.let { trackedTracks.remove(it) }
                    chain.proceed()
                }
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not hook AudioTrack.release", it)
        }
    }

    private fun attachLifecycle(
        app: Application,
        scheduleCheck: () -> Unit,
        onForeground: () -> Unit,
    ) {
        powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenInteractive = powerManager?.isInteractive ?: true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() > 0 && screenInteractive) {
                    onForeground()
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (startedActivities.get() <= 0) startedActivities.set(1)
                if (screenInteractive) onForeground()
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() <= 0) {
                    startedActivities.set(0)
                    scheduleCheck()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            screenInteractive = false
                            scheduleCheck()
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            screenInteractive = true
                            if (startedActivities.get() > 0) onForeground()
                        }
                    }
                }
            }, filter)
        }
    }

    private fun silenceLeakedTracks(module: HyperSystemUiModule) {
        val snapshot = synchronized(trackedTracks) { trackedTracks.toList() }
        var stoppedCount = 0
        for (track in snapshot) {
            runCatching {
                if (track.state != AudioTrack.STATE_INITIALIZED) return@runCatching
                val ps = track.playState
                if (ps == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                    stoppedCount++
                } else if (ps == AudioTrack.PLAYSTATE_PAUSED) {
                    track.flush()
                    track.stop()
                    stoppedCount++
                }
            }
        }
        if (stoppedCount > 0) {
            HookModuleLogger.log(
                module,
                Log.INFO,
                TAG,
                "Stopped $stoppedCount leaked background Douyin AudioTrack(s) to release AudioMix wakelock",
            )
        }
    }
}
