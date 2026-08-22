/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.binding

import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.IProviderService
import io.github.proify.lyricon.provider.IRemoteService
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.provider.internal.connection.RemoteServiceSink
import io.github.proify.lyricon.provider.internal.wire.json
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 提供端暴露给中心服务的注册 AIDL 适配器。
 *
 * 该桩负责向中心服务提供注册信息与本地命令 Binder，并接收中心服务返回的远端服务 Binder。
 */
internal class RegistrationBinder(
    providerInfo: ProviderInfo,
    private val providerCommand: ProviderCommandStub,
    private val remoteServiceSink: RemoteServiceSink<IRemoteService?>,
) : IProviderBinder.Stub() {
    private val registrationCallbacks = CopyOnWriteArraySet<OnRegistrationCallback>()

    private val serializedProviderInfo: ByteArray by lazy {
        json.encodeToString(providerInfo).toByteArray()
    }

    /** 添加注册完成回调。 */
    fun addRegistrationCallback(callback: OnRegistrationCallback) =
        registrationCallbacks.add(callback)

    /** 移除注册完成回调。 */
    fun removeRegistrationCallback(callback: OnRegistrationCallback) =
        registrationCallbacks.remove(callback)

    override fun onRegistrationCallback(remoteProviderService: IRemoteService?) {
        remoteServiceSink.onRemoteService(remoteProviderService)
        registrationCallbacks.forEach { it.onRegistered() }
    }

    override fun getProviderService(): IProviderService = providerCommand
    override fun getProviderInfo(): ByteArray = serializedProviderInfo

    /** 中心服务完成注册后触发的内部回调。 */
    interface OnRegistrationCallback {
        fun onRegistered()
    }
}
