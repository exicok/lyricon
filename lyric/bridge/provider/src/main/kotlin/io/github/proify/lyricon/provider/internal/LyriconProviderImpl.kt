/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderConstants.ACTION_REGISTER_PROVIDER
import io.github.proify.lyricon.provider.ProviderConstants.EXTRA_BINDER
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.ProviderService
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.provider.internal.binding.ProviderCommandStub
import io.github.proify.lyricon.provider.internal.binding.RegistrationBinder
import io.github.proify.lyricon.provider.internal.connection.CentralConnection
import io.github.proify.lyricon.provider.internal.connection.CentralConnection.DisconnectReason
import io.github.proify.lyricon.provider.internal.registration.CentralBootReceiver
import io.github.proify.lyricon.provider.isConnecting
import io.github.proify.lyricon.provider.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认提供端实现。
 *
 * 负责发送注册广播、处理连接超时、维护本地命令 Binder，并把远端服务交给
 * [CentralConnection] 管理。
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class LyriconProviderImpl(
    private val context: Context,
    override val providerInfo: ProviderInfo,
    providerService: ProviderService? = null,
    private val centralPackageName: String,
) : LyriconProvider, ConnectionListener {
    private val destroyed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandStub = ProviderCommandStub(providerService)
    private val connection = CentralConnection(this)
    private val registrationBinder = RegistrationBinder(providerInfo, commandStub, connection)
    private val registration = Registration()

    override var providerService: ProviderService? = providerService
        set(value) {
            field = value
            commandStub.handler = value
        }

    override val service: RemoteService = connection
    override val player: RemotePlayer get() = service.player
    override var autoSync: Boolean = true

    init {
        service.addConnectionListener(this)
    }

    override fun register(): Boolean = registration.start()

    override fun unregister(): Boolean {
        if (destroyed.get()) return false
        disconnect(DisconnectReason.USER)
        return true
    }

    override fun destroy(): Boolean {
        if (!destroyed.compareAndSet(false, true)) return false
        registration.close()
        disconnect(DisconnectReason.USER)
        service.removeConnectionListener(this)
        scope.cancel()
        return true
    }

    override fun onConnected(provider: LyriconProvider) {
        if (autoSync) connection.syncPlayer()
    }

    override fun onReconnected(provider: LyriconProvider) {
        if (autoSync) connection.syncPlayer()
    }

    override fun onDisconnected(provider: LyriconProvider) = Unit
    override fun onConnectTimeout(provider: LyriconProvider) = Unit

    private fun disconnect(reason: DisconnectReason) {
        registration.cancelTimeout()
        connection.disconnect(reason)
    }

    /** 管理注册广播、超时和中心服务重启后的恢复注册。 */
    private inner class Registration : CentralBootReceiver.BootListener {
        private var timeoutJob: Job? = null
        private val callback = object : RegistrationBinder.OnRegistrationCallback {
            override fun onRegistered() {
                cancelTimeout()
                registrationBinder.removeRegistrationCallback(this)
            }
        }

        init {
            CentralBootReceiver.addBootListener(this)
        }

        fun start(): Boolean {
            if (destroyed.get() || centralPackageName.isBlank()) return false
            if (connection.connectionStatus in setOf(
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.CONNECTING
                )
            ) {
                return false
            }

            connection.connectionStatus = ConnectionStatus.CONNECTING
            registrationBinder.addRegistrationCallback(callback)
            scheduleTimeout()
            context.sendBroadcast(Intent(ACTION_REGISTER_PROVIDER).apply {
                setPackage(centralPackageName)
                putExtra(
                    ProviderConstants.EXTRA_BUNDLE,
                    Bundle().apply {
                        putBinder(EXTRA_BINDER, registrationBinder)
                    }
                )
            })
            return true
        }

        override fun onBootCompleted() {
            if (connection.connectionStatus == ConnectionStatus.DISCONNECTED_REMOTE) start()
        }

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        fun close() {
            cancelTimeout()
            registrationBinder.removeRegistrationCallback(callback)
            CentralBootReceiver.removeBootListener(this)
        }

        private fun scheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                if (!connection.connectionStatus.isConnecting()) return@launch
                connection.connectionStatus = ConnectionStatus.DISCONNECTED
                registrationBinder.removeRegistrationCallback(callback)
                connection.forEachConnectionListener {
                    it.onConnectTimeout(this@LyriconProviderImpl)
                }
            }
        }
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MS = 4_000L
    }
}
