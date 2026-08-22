/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.binding

import io.github.proify.lyricon.central.CentralRuntime
import io.github.proify.lyricon.central.internal.provider.ProviderConnection
import io.github.proify.lyricon.provider.IRemoteService

internal class RemoteServiceStub(
    private var connection: ProviderConnection? = null
) : IRemoteService.Stub() {

    private var player: PlayerCommandStub? = connection?.let {
        PlayerCommandStub(it.providerInfo, CentralRuntime.activePlayers)
    }

    override fun getPlayer(): PlayerCommandStub? = player

    fun close() {
        player?.close()
        player = null
        connection = null
    }

    override fun disconnect() {
        connection?.let { CentralRuntime.providers.unregister(it) }
    }
}
