package com.nuvio.tv.livetv.profile

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nuvio One copies settings between "matched" profiles here. Kable Killers keeps its own
 * profile rules (Profile 1 mirrored to new profiles, customer profiles local per device),
 * so this does nothing.
 */
@Singleton
class ProfileMatchMirror @Inject constructor() {
    fun start() = Unit
}
