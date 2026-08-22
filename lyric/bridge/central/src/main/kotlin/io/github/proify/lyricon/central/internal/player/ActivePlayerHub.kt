/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import android.util.Log
import io.github.proify.lyricon.central.internal.CentralConstants
import io.github.proify.lyricon.central.internal.player.PlayerSession.LyricType.NONE
import io.github.proify.lyricon.central.internal.player.PlayerSession.LyricType.SONG
import io.github.proify.lyricon.central.internal.player.PlayerSession.LyricType.TEXT
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal class ActivePlayerHub : PlayerListener {

    private val debug = CentralConstants.isDebug()
    private val lock = ReentrantReadWriteLock()
    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()

    @Volatile
    private var activeSession: PlayerSession? = null

    private val activeInfo: ProviderInfo? get() = activeSession?.providerInfo

    @Volatile
    private var activeIsPlaying: Boolean = false

    fun addListener(listener: ActivePlayerListener) {
        if (listeners.add(listener)) {
            syncLatestState(listener)
        }
    }

    fun removeListener(listener: ActivePlayerListener) = listeners.remove(listener)

    fun syncLatestState(listener: ActivePlayerListener) {
        val snapshot = lock.read {
            activeSession?.snapshot(activeIsPlaying)
        } ?: return

        dispatchSnapshot(snapshot, listener)
    }

    fun notifyProviderInvalid(provider: ProviderInfo) {
        val shouldNotify = lock.write {
            if (activeInfo == provider) {
                activeSession = null
                activeIsPlaying = false
                true
            } else {
                false
            }
        }

        if (shouldNotify) {
            broadcast {
                it.onActiveProviderChanged(null)
                it.onPlaybackStateChanged(false)
            }
        }
    }

    override fun onSongChanged(session: PlayerSession, song: Song?) {
        if (debug) Log.d(TAG, "onSongChanged: " + song)
        dispatchIfActive(session, allowDuplicateIfSwitching = false) {
            it.onSongChanged(song)
        }
    }

    override fun onPlaybackStateChanged(session: PlayerSession, isPlaying: Boolean) {
        if (debug) Log.d(TAG, "onPlaybackStateChanged: " + isPlaying)
        dispatchIfActive(session) {
            it.onPlaybackStateChanged(isPlaying)
        }
    }

    override fun onPositionChanged(session: PlayerSession, position: Long) {
        dispatchIfActive(session) {
            it.onPositionChanged(position)
        }
    }

    override fun onSeekTo(session: PlayerSession, position: Long) {
        dispatchIfActive(session) {
            it.onSeekTo(position)
        }
    }

    override fun onSendText(session: PlayerSession, text: String?) {
        dispatchIfActive(session, allowDuplicateIfSwitching = false) {
            it.onSendText(text)
        }
    }

    override fun onDisplayTranslationChanged(
        session: PlayerSession,
        isDisplayTranslation: Boolean
    ) {
        dispatchIfActive(session, allowDuplicateIfSwitching = false) {
            it.onDisplayTranslationChanged(isDisplayTranslation)
        }
    }

    override fun onDisplayRomaChanged(session: PlayerSession, displayRoma: Boolean) {
        dispatchIfActive(session, allowDuplicateIfSwitching = false) {
            it.onDisplayRomaChanged(displayRoma)
        }
    }

    fun syncNewProviderState(session: PlayerSession, listener: ActivePlayerListener) {
        val snapshot = lock.read {
            session.snapshot(activeIsPlaying)
        }
        dispatchSnapshot(snapshot, listener)
    }

    private fun dispatchSnapshot(snapshot: ActivePlayerReport, listener: ActivePlayerListener) {
        listener.onActiveProviderChanged(snapshot.providerInfo)
        listener.onPlaybackStateChanged(snapshot.isPlaying)

        when (snapshot.lyricType) {
            SONG -> listener.onSongChanged(snapshot.song)
            TEXT -> listener.onSendText(snapshot.text)
            NONE -> Unit
        }

        listener.onDisplayTranslationChanged(snapshot.isDisplayTranslation)
        listener.onDisplayRomaChanged(snapshot.isDisplayRoma)
        listener.onPositionChanged(snapshot.position)
    }

    private inline fun dispatchIfActive(
        session: PlayerSession,
        allowDuplicateIfSwitching: Boolean = true,
        crossinline notifier: (ActivePlayerListener) -> Unit
    ) {
        val sessionInfo = session.providerInfo
        val sessionPlaying = session.isPlaying
        var isSwitched = false
        var shouldBroadcastOriginal = false

        lock.write {
            val currentInfo = activeInfo
            if (currentInfo === sessionInfo) {
                activeIsPlaying = sessionPlaying
                shouldBroadcastOriginal = true
            } else {
                val canSwitch = currentInfo == null || (!activeIsPlaying && sessionPlaying)
                if (canSwitch) {
                    activeSession = session
                    activeIsPlaying = sessionPlaying
                    isSwitched = true
                    shouldBroadcastOriginal = allowDuplicateIfSwitching
                }
            }
        }

        if (isSwitched) {
            broadcast { syncNewProviderState(session, it) }
        }

        if (shouldBroadcastOriginal) {
            broadcast(notifier)
        }
    }

    private inline fun broadcast(crossinline notifier: (ActivePlayerListener) -> Unit) {
        for (listener in listeners) {
            try {
                notifier(listener)
            } catch (e: Exception) {
                if (debug) Log.e(TAG, "Dispatch failed for listener: " + listener.javaClass.name, e)
            }
        }
    }

    private fun PlayerSession.snapshot(isPlaying: Boolean) = ActivePlayerReport(
        providerInfo = providerInfo,
        isPlaying = isPlaying,
        song = song,
        text = text,
        lyricType = lyricType,
        isDisplayTranslation = isDisplayTranslation,
        isDisplayRoma = isDisplayRoma,
        position = position
    )

    private companion object {
        private const val TAG = "ActivePlayerHub"
    }
}
