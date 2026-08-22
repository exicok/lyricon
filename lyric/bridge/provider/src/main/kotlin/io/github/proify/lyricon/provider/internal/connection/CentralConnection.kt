/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.connection

import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.IRemoteService
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.provider.internal.player.AidlRemotePlayer
import io.github.proify.lyricon.provider.internal.player.ResyncingPlayer
import io.github.proify.lyricon.provider.isConnected
import io.github.proify.lyricon.provider.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 提供端与中心服务的连接端点。
 *
 * 连接状态由 [ConnectionStateMachine] 统一决策；该类负责把状态转移翻译成实际的
 * Binder 操作（绑定、死亡监听、拆除），并向外提供带重放缓存的 [RemotePlayer]。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class CentralConnection(
    private val provider: LyriconProvider,
) : RemoteService, RemoteServiceSink<IRemoteService?> {
    private val playerChannel = AidlRemotePlayer()
    private val playerCache = ResyncingPlayer(playerChannel)
    private val listeners = CopyOnWriteArraySet<ConnectionListener>()
    private val machine = ConnectionStateMachine()
    private val callbackScope = CoroutineScope(Dispatchers.Main.immediate)
    private val transitionLock = Any()

    @Volatile
    private var remoteService: IRemoteService? = null

    private val deathRecipient = IBinder.DeathRecipient {
        transition(ConnectionTrigger.SERVICE_LOST)
    }

    override val player: RemotePlayer = playerCache

    override val isActive: Boolean
        get() = remoteService?.asBinder()?.isBinderAlive == true

    override val connectionStatus: ConnectionStatus
        get() = machine.status

    /**
     * 尝试进入连接状态；返回是否真正启动了一次注册。
     *
     * 状态机是权威判定：已在连接中或已连接时返回 false，调用方必须据此放弃
     * 发送注册广播，避免破坏现有连接。
     */
    fun beginRegistration(): Boolean {
        return transition(ConnectionTrigger.REGISTER).status == ConnectionStatus.CONNECTING
    }

    /** 注册等待超时。 */
    fun onRegistrationTimeout() {
        transition(ConnectionTrigger.REGISTRATION_TIMEOUT)
    }

    /** 用户主动断开。 */
    fun disconnectByUser() {
        transition(ConnectionTrigger.USER_DISCONNECT)
    }

    /** 接收中心服务返回的远端服务 Binder。 */
    override fun onRemoteService(service: IRemoteService?) {
        if (ProviderConstants.DEBUG) Log.d(TAG, "Bind remote service")

        // 先拆除旧连接（等价于原有 REPLACE 断连）。
        transition(ConnectionTrigger.PREEMPT)

        if (service == null) {
            Log.w(TAG, "Service is null")
            return
        }
        val binder = service.asBinder()
        if (!binder.isBinderAlive) {
            Log.w(TAG, "Binder is not alive")
            return
        }

        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to link death recipient", e)
            return
        }

        // linkToDeath 与赋值之间远端可能恰好死亡：此时放弃连接，
        // 避免绑定一个已死的 Binder 却对外宣称连接成功。
        if (!binder.isBinderAlive) {
            Log.w(TAG, "Remote binder died during registration")
            return
        }

        remoteService = service
        playerChannel.attachPlayer(service.player)
        transition(ConnectionTrigger.SERVICE_READY)
    }

    /** 将缓存的播放器状态同步到当前远端播放器。 */
    fun syncPlayer() {
        playerCache.sync()
    }

    override fun addConnectionListener(listener: ConnectionListener): Boolean =
        listeners.add(listener)

    override fun removeConnectionListener(listener: ConnectionListener): Boolean =
        listeners.remove(listener)

    /**
     * 执行一次状态转移并分发通知。
     *
     * 状态与 Binder 操作在同一把锁内完成，保证转移的原子性；
     * 监听器通知在锁外发往主线程，维持原有回调线程模型。
     */
    private fun transition(trigger: ConnectionTrigger): ConnectionTransition {
        val result = synchronized(transitionLock) {
            val transition = machine.on(trigger, remoteService != null)
            playerChannel.isSendingEnabled = transition.status.isConnected()
            if (tearsDown(trigger)) {
                playerChannel.attachPlayer(null)
                detachService()
            }
            transition
        }
        dispatchNotice(result.notice)
        return result
    }

    /** 该触发是否要求拆除已有远端服务。 */
    private fun tearsDown(trigger: ConnectionTrigger): Boolean = when (trigger) {
        ConnectionTrigger.PREEMPT,
        ConnectionTrigger.SERVICE_LOST,
        ConnectionTrigger.USER_DISCONNECT,
        -> true

        ConnectionTrigger.REGISTER,
        ConnectionTrigger.REGISTRATION_TIMEOUT,
        ConnectionTrigger.SERVICE_READY,
        -> false
    }

    /** 解除死亡监听并断开远端服务（幂等）。 */
    private fun detachService() {
        val old = remoteService ?: return
        remoteService = null

        runCatching { old.asBinder().unlinkToDeath(deathRecipient, 0) }
            .onFailure { Log.w(TAG, "Failed to unlink death recipient", it) }
        runCatching { old.disconnect() }
            .onFailure { Log.e(TAG, "Failed to disconnect remote service", it) }
    }

    private fun dispatchNotice(notice: ConnectionNotice?) {
        if (notice == null) return

        callbackScope.launch {
            listeners.forEach { listener ->
                when (notice) {
                    ConnectionNotice.CONNECTED -> listener.onConnected(provider)
                    ConnectionNotice.RECONNECTED -> listener.onReconnected(provider)
                    ConnectionNotice.DISCONNECTED -> listener.onDisconnected(provider)
                    ConnectionNotice.CONNECT_TIMEOUT -> listener.onConnectTimeout(provider)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "CentralConnection"
    }
}
