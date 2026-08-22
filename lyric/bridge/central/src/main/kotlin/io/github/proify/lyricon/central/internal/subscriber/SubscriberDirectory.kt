/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.subscriber

import io.github.proify.lyricon.central.internal.connection.ConnectionRegistry
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo

internal class SubscriberDirectory {
    private val registry = ConnectionRegistry<SubscriberInfo, SubscriberConnection>()

    fun getOrCreate(binder: ISubscriberBinder, info: SubscriberInfo): SubscriberConnection {
        val existing = registry.get(info)
        if (existing != null) {
            // 订阅端以新 Binder 重新注册：换绑死亡监听；已关闭的残留连接会被
            // 清除并由下方新建连接取代。
            if (existing.reattach(binder)) return existing
            registry.unregister(existing)
        }
        return registry.register(SubscriberConnection(binder, info))
    }

    fun unregister(connection: SubscriberConnection) {
        registry.unregister(connection)
    }
}
