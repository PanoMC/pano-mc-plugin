package com.panomc.plugins.pano.core.platform

/**
 * Narrows what a platform implementation *can* do down to what the operator has left switched on
 * in config.conf, before it is announced to Pano on connect.
 *
 * The only switch today is `console.enabled` (config version 6) and it covers both console
 * capabilities: [Capability.COMMANDS] is the write half of the same feature - a panel that cannot
 * show the console has nowhere sensible to type a command either, and the command echo would go
 * nowhere - so capture being off has to withdraw both, or Pano keeps offering a console section
 * the server will never answer.
 *
 * Kept as a pure function rather than folded into the four platform mains: the rule is the same
 * everywhere, and this way it is testable without a running server.
 */
object CapabilityFilter {
    /** The capabilities that are only meaningful while console capture is enabled. */
    val CONSOLE_CAPABILITIES = setOf(Capability.CONSOLE, Capability.COMMANDS)

    /**
     * [announced] minus everything the config switches off, preserving the caller's order.
     */
    fun filter(announced: Set<Capability>, consoleEnabled: Boolean): Set<Capability> =
        if (consoleEnabled) announced else announced - CONSOLE_CAPABILITIES
}
