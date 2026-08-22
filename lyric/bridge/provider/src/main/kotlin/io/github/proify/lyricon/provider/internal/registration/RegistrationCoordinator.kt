/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.registration

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderConstants.ACTION_REGISTER_PROVIDER
import io.github.proify.lyricon.provider.ProviderConstants.EXTRA_BINDER
import io.github.proify.lyricon.provider.internal.binding.RegistrationBinder
import io.github.proify.lyricon.provider.internal.connection.CentralConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 注册协调器：发送注册广播、管理连接超时，并在中心服务重启后恢复注册。
 */
internal class RegistrationCoordinator(
    private val context: Context,
    private val centralPackageName: String,
    private val registrationBinder: RegistrationBinder,
    private val connection: CentralConnection,
    private val scope: CoroutineScope,
) : CentralBootReceiver.BootListener {

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

    /** 发出注册广播；返回是否真正启动了一次注册。 */
    fun start(): Boolean {
        if (centralPackageName.isBlank()) return false
        if (!connection.canRegister()) return false

        connection.beginRegistration()
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
            connection.onRegistrationTimeout()
        }
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MS = 4_000L
    }
}
