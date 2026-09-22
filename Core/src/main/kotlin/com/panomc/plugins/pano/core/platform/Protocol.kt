package com.panomc.plugins.pano.core.platform

/**
 * Version of the plugin to platform protocol spoken by this plugin.
 *
 * The version is announced on every connect (see `OnServerConnectRequest`) so the platform can
 * tell which features a connected server supports:
 *
 * - `1` - implicit version of every plugin released *before* this field existed. Such plugins
 *   send no `protocolVersion` at all, so the platform assumes `1` and treats the server as
 *   having no capabilities.
 * - `2` - first version that announces `protocolVersion`, `pluginVersion` and a capability list
 *   on connect.
 *
 * Bump this whenever the wire format changes in a way the platform has to know about, and never
 * assume the peer speaks the same version: the platform tolerates older plugins on purpose.
 */
object Protocol {
    const val VERSION = 2
}
