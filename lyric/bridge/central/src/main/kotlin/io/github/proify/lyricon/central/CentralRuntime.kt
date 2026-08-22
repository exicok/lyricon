/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import io.github.proify.lyricon.central.internal.player.ActivePlayerHub
import io.github.proify.lyricon.central.internal.provider.ProviderDirectory
import io.github.proify.lyricon.central.internal.registration.RegistrationDispatcher
import io.github.proify.lyricon.central.internal.subscriber.SubscriberDirectory

internal object CentralRuntime {
    val activePlayers = ActivePlayerHub()
    val providers = ProviderDirectory(activePlayers)
    val subscribers = SubscriberDirectory()
    val registration = RegistrationDispatcher(providers, subscribers)
}
