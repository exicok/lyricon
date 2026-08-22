/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.binding

import android.util.Log
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.IProviderService
import io.github.proify.lyricon.provider.IRemoteService
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.internal.connection.RemoteServiceSink
import io.github.proify.lyricon.provider.internal.wire.json

/**
 * 提供端暴露给中心服务的注册 AIDL 适配器。
 *
 * 该桩负责向中心服务提供注册信息与本地命令 Binder，并接收中心服务返回的远端服务 Binder。
 *
 * 每次注册广播对应一次 [RegistrationAttempt]；尝试被取消（用户断开、销毁或超时）后，
 * 迟到的注册回调将被忽略，不会建立连接。
 */
internal class RegistrationBinder(
    providerInfo: ProviderInfo,
    private val providerCommand: ProviderCommandStub,
    private val remoteServiceSink: RemoteServiceSink<IRemoteService?>,
) : IProviderBinder.Stub() {

    @Volatile
    private var pendingAttempt: RegistrationAttempt? = null

    private val serializedProviderInfo: ByteArray by lazy {
        json.encodeToString(providerInfo).toByteArray()
    }

    /** 进入等待中心服务回调的状态。 */
    fun beginAttempt(attempt: RegistrationAttempt) {
        pendingAttempt = attempt
    }

    /** 结束等待：仅在 [attempt] 仍为当前尝试时清除。 */
    fun endAttempt(attempt: RegistrationAttempt) {
        if (pendingAttempt === attempt) pendingAttempt = null
    }

    override fun onRegistrationCallback(remoteProviderService: IRemoteService?) {
        val attempt = pendingAttempt ?: run {
            Log.w(TAG, "Ignoring registration callback (attempt was cancelled)")
            return
        }

        remoteServiceSink.onRemoteService(remoteProviderService)
        attempt.onCompleted()
    }

    override fun getProviderService(): IProviderService = providerCommand
    override fun getProviderInfo(): ByteArray = serializedProviderInfo

    /** 一次注册尝试：中心服务回调返回并处理完成后触发。 */
    interface RegistrationAttempt {
        /** 回调已处理完成：清理超时等待等。 */
        fun onCompleted()
    }

    private companion object {
        private const val TAG = "RegistrationBinder"
    }
}
