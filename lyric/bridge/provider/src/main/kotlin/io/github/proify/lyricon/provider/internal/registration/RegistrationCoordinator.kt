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
 *
 * 每次 [start] 建立一次注册尝试；[cancel] 结束尝试，之后到达的中心回调会被
 * [RegistrationBinder] 忽略，从而同时覆盖"断开已连接"与"取消连接中"两种语义。
 */
internal class RegistrationCoordinator(
    private val context: Context,
    private val centralPackageName: String,
    private val registrationBinder: RegistrationBinder,
    private val connection: CentralConnection,
    private val scope: CoroutineScope,
) : CentralBootReceiver.BootListener {

    private var timeoutJob: Job? = null

    private val attempt = object : RegistrationBinder.RegistrationAttempt {
        override fun onCompleted() {
            cancelTimeout()
            registrationBinder.endAttempt(this)
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
        registrationBinder.beginAttempt(attempt)
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

    /** 取消当前注册尝试：清空超时，并让迟到回调失效。 */
    fun cancel() {
        cancelTimeout()
        registrationBinder.endAttempt(attempt)
    }

    fun close() {
        cancel()
        CentralBootReceiver.removeBootListener(this)
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun scheduleTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            connection.onRegistrationTimeout()
            registrationBinder.endAttempt(attempt)
        }
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MS = 4_000L
    }
}
