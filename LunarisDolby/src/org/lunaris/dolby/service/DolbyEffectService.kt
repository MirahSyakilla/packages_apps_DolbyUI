/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handlerThread = HandlerThread("DolbyEffectService")
    private lateinit var handler: Handler
    private lateinit var repository: DolbyRepository
    private var lastApplyUptime = 0L
    private var playbackActive = false

    private val applySavedStateRunnable = Runnable {
        applySavedState("debounced")
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            repository.updateSpeakerState()
            scheduleApplySavedState("device-added", DEVICE_APPLY_DELAY_MS)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            repository.updateSpeakerState()
            scheduleApplySavedState("device-removed", DEVICE_APPLY_DELAY_MS)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            if (isActive && !playbackActive) {
                scheduleApplySavedState("playback-started")
            }
            playbackActive = isActive
        }
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        repository = DolbyRepository(this)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        scheduleApplySavedState("service-created", START_APPLY_DELAY_MS)
        Log.d(TAG, "Dolby effect service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleApplySavedState("service-started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        repository.close()
        handlerThread.quitSafely()
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleApplySavedState(reason: String, delayMs: Long = APPLY_DEBOUNCE_MS) {
        if (!::handler.isInitialized) return

        val sinceLastApply = SystemClock.uptimeMillis() - lastApplyUptime
        val minDelay = (MIN_APPLY_INTERVAL_MS - sinceLastApply).coerceAtLeast(0L)
        val waitMs = maxOf(delayMs, minDelay)

        handler.removeCallbacks(applySavedStateRunnable)
        handler.postDelayed(applySavedStateRunnable, waitMs)
        Log.d(TAG, "Scheduled Dolby state apply for $reason in ${waitMs}ms")
    }

    private fun applySavedState(reason: String) {
        if (!::repository.isInitialized) return

        lastApplyUptime = SystemClock.uptimeMillis()
        runCatching {
            repository.applySavedState()
        }.onFailure {
            Log.w(TAG, "Failed to apply Dolby state for $reason", it)
        }
    }

    companion object {
        private const val TAG = "DolbyEffectService"
        private const val APPLY_DEBOUNCE_MS = 750L
        private const val DEVICE_APPLY_DELAY_MS = 1000L
        private const val START_APPLY_DELAY_MS = 1500L
        private const val MIN_APPLY_INTERVAL_MS = 3000L

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
