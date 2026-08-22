/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.ProviderService
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.provider.internal.binding.ProviderCommandStub
import io.github.proify.lyricon.provider.internal.binding.RegistrationBinder
import io.github.proify.lyricon.provider.internal.connection.CentralConnection
import io.github.proify.lyricon.provider.internal.registration.RegistrationCoordinator
import io.github.proify.lyricon.provider.service.RemoteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认提供端实现。
 *
 * 只负责装配与生命周期：注册流程交给 [RegistrationCoordinator]，
 * 远端连接交给 [CentralConnection]；自动同步由 [autoSync] 策略驱动。
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
    private val registration = RegistrationCoordinator(
        context,
        centralPackageName,
        registrationBinder,
        connection,
        scope
    )

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

    override fun register(): Boolean {
        if (destroyed.get()) return false
        return registration.start()
    }

    override fun unregister(): Boolean {
        if (destroyed.get()) return false
        registration.cancel()
        connection.disconnectByUser()
        return true
    }

    override fun destroy(): Boolean {
        if (!destroyed.compareAndSet(false, true)) return false
        registration.close()
        connection.disconnectByUser()
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
}
