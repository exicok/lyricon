/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.binding

import android.media.session.PlaybackState
import android.os.SharedMemory
import android.util.Log
import io.github.proify.lyricon.central.internal.player.PlaybackStateTracker
import io.github.proify.lyricon.central.internal.player.PlayerSession
import io.github.proify.lyricon.central.internal.player.PlayerListener
import io.github.proify.lyricon.central.internal.player.PositionMemoryChannel
import io.github.proify.lyricon.central.internal.player.PositionTicker
import io.github.proify.lyricon.central.internal.util.ScreenStateMonitor
import io.github.proify.lyricon.central.internal.wire.inflate
import io.github.proify.lyricon.central.internal.wire.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提供端播放命令的 AIDL 桩。
 *
 * 负责把 [IRemotePlayer] 命令写入 [PlayerSession] 并转发事件；
 * 位置计算由 [PlaybackStateTracker] 负责，本类只做 PlaybackState 与
 * 原始参数的适配。
 */
internal class PlayerCommandStub(
    info: ProviderInfo,
    private val playerEvents: PlayerListener
) : IRemotePlayer.Stub(), ScreenStateMonitor.ScreenStateListener {

    private val session = PlayerSession(info)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val closeMutex = Mutex()
    private val positionTracker = PlaybackStateTracker()
    private val positionMemory = PositionMemoryChannel(info)
    private val positionTicker = PositionTicker(
        scope = scope,
        readPosition = positionTracker::computePosition,
        onPosition = ::publishPosition
    )

    @Volatile
    private var positionUpdateInterval: Long = ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL

    init {
        positionTracker.manualPositionReader = positionMemory::readPosition
        ScreenStateMonitor.addListener(this)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ScreenStateMonitor.removeListener(this)
        stopPositionUpdate()

        scope.launch {
            closeMutex.withLock {
                positionMemory.close()
                scope.cancel()
            }
        }
    }

    override fun onScreenOn() {
        if (session.isPlaying) startPositionUpdate()
    }

    override fun onScreenOff() {
        stopPositionUpdate()
    }

    override fun onScreenUnlocked() = Unit

    override fun setPositionUpdateInterval(interval: Int) {
        if (closed.get()) return

        val next = interval.toLong().coerceAtLeast(MIN_INTERVAL_MS)
        if (positionUpdateInterval == next) return

        positionUpdateInterval = next
        if (session.isPlaying) {
            stopPositionUpdate()
            startPositionUpdate()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun setSong(bytes: ByteArray?) {
        if (closed.get()) return

        scope.launch {
            val song = bytes?.let {
                runCatching {
                    it.inflate()
                        .inputStream()
                        .buffered()
                        .use { stream ->
                            json.decodeFromStream(Song.serializer(), stream)
                        }
                }.getOrNull()
            }

            val normalized = song?.normalize()
            session.song = normalized
            playerEvents.safeNotify { onSongChanged(session, normalized) }
        }
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        if (closed.get()) return

        positionTracker.useManualMode()

        if (session.isPlaying != isPlaying) {
            session.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(session, isPlaying) }
        }

        if (isPlaying) startPositionUpdate() else stopPositionUpdate()
    }

    override fun setPlaybackState2(state: PlaybackState?) {
        if (closed.get()) return

        if (state == null) {
            if (positionTracker.stopDriving()) {
                stopPositionUpdate()
            }
            return
        }

        Log.d(TAG, "setPlaybackState2: " + state)

        if (state.state == PlaybackState.STATE_BUFFERING) return

        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        positionTracker.usePlaybackState(
            playing = isPlaying,
            position = state.position,
            lastUpdateTime = state.lastPositionUpdateTime,
            playbackSpeed = state.playbackSpeed
        )

        if (session.isPlaying != isPlaying) {
            session.isPlaying = isPlaying
            playerEvents.safeNotify { onPlaybackStateChanged(session, isPlaying) }
        }

        if (isPlaying) startPositionUpdate() else stopPositionUpdate()
    }

    override fun seekTo(position: Long) {
        if (closed.get()) return

        val safe = position.coerceAtLeast(0L)
        session.position = safe
        playerEvents.safeNotify { onSeekTo(session, safe) }
    }

    override fun sendText(text: String?) {
        if (closed.get()) return

        session.text = text
        playerEvents.safeNotify { onSendText(session, text) }
    }

    override fun setDisplayTranslation(isDisplayTranslation: Boolean) {
        if (closed.get()) return

        session.isDisplayTranslation = isDisplayTranslation
        playerEvents.safeNotify { onDisplayTranslationChanged(session, isDisplayTranslation) }
    }

    override fun setDisplayRoma(isDisplayRoma: Boolean) {
        if (closed.get()) return

        session.isDisplayRoma = isDisplayRoma
        playerEvents.safeNotify { onDisplayRomaChanged(session, isDisplayRoma) }
    }

    override fun getPositionMemory(): SharedMemory? = positionMemory.sharedMemory

    private fun startPositionUpdate() {
        if (closed.get()) return
        if (ScreenStateMonitor.state == ScreenStateMonitor.ScreenState.OFF) return
        positionTicker.start(positionUpdateInterval)
    }

    private fun stopPositionUpdate() {
        positionTicker.stop()
    }

    private fun publishPosition(position: Long) {
        session.position = position
        playerEvents.safeNotify { onPositionChanged(session, position) }
    }

    private inline fun PlayerListener.safeNotify(crossinline block: PlayerListener.() -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "player event dispatch failed", e)
        }
    }

    private companion object {
        private const val TAG = "PlayerCommandStub"
        private const val MIN_INTERVAL_MS = 16L
    }
}
