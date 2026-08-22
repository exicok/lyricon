/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider

import io.github.proify.lyricon.central.connection.ConnectionRegistry
import io.github.proify.lyricon.central.provider.player.ActivePlayerCoordinator
import io.github.proify.lyricon.provider.IProviderBinder
import io.github.proify.lyricon.provider.ProviderInfo

internal class ProviderDirectory(
    private val activePlayers: ActivePlayerCoordinator
) {
    private val registry = ConnectionRegistry<ProviderInfo, ProviderConnection>()

    fun getOrCreate(binder: IProviderBinder, info: ProviderInfo): ProviderConnection {
        val existing = registry.get(info)
        if (existing != null) {
            // 提供端以新 Binder 重新注册：换绑死亡监听；已关闭的残留连接会被
            // 清除并由下方新建连接取代。
            if (existing.reattach(binder)) return existing
            registry.unregister(existing)
        }
        return registry.register(ProviderConnection(binder, info, activePlayers))
    }

    fun unregister(connection: ProviderConnection) {
        registry.unregister(connection)
    }
}
