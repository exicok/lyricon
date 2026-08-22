/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.subscriber.internal.binding

import io.github.proify.lyricon.subscriber.IRemoteService
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo
import io.github.proify.lyricon.subscriber.internal.wire.json
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 订阅端暴露给中心服务的 AIDL 适配器。
 *
 * 该类只负责传递注册信息和接收中心服务返回的远端服务 Binder。
 */
internal class RegistrationBinder(
    private val subscriberInfo: SubscriberInfo
) : ISubscriberBinder.Stub() {

    private val serializedSubscriberInfo by lazy {
        json.encodeToString(subscriberInfo).toByteArray()
    }

    private val registrationCallbacks = CopyOnWriteArraySet<RegistrationCallback>()

    /** 添加注册完成回调。 */
    fun addRegistrationCallback(callback: RegistrationCallback) {
        registrationCallbacks.add(callback)
    }

    /** 移除注册完成回调。 */
    fun removeRegistrationCallback(callback: RegistrationCallback) {
        registrationCallbacks.remove(callback)
    }

    override fun onRegistrationCallback(service: IRemoteService?) {
        registrationCallbacks.forEach { it.onRegistered(service) }
    }

    override fun getSubscriberInfo(): ByteArray = serializedSubscriberInfo

    /** 中心服务完成注册后触发的内部回调。 */
    interface RegistrationCallback {
        fun onRegistered(service: IRemoteService?)
    }
}
