package com.panomc.plugins.pano.core.metrics

import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.helper.ServerData
import com.panomc.plugins.pano.core.platform.PlatformRequest
import com.panomc.plugins.pano.core.platform.entity.PlayerData
import com.panomc.plugins.pano.core.platform.request.ServerMetricsRequest
import io.vertx.core.Vertx
import java.lang.management.ManagementFactory
import java.util.logging.Logger

/**
 * Pushes a `SERVER_METRICS` sample to Pano every ten seconds while the connection is up - or as
 * often as Pano asked for while a panel is watching (`SET_METRICS_INTERVAL`, AGENT.md 2.4.23), down
 * to twice a second. Every value below is a cheap read, so a half-second cadence costs a sample
 * per tick and nothing more; the one expensive figure, the disk walk, is cached behind it.
 *
 * The timer runs on a Vert.x context, never on the server's main thread, and every sample is
 * built from values the platform implementations can produce cheaply. A tick where the socket is
 * down is skipped outright rather than queued: metrics are a live signal, and Pano keeps its own
 * history.
 *
 * The one figure that cannot be produced cheaply is the disk usage, so it is not produced here:
 * [DiskUsageProbe] walks the server directory in the background at most every five minutes, and a
 * sample only ever reads the result of the last walk (AGENT.md 2.4.18 A).
 *
 * Between samples, a one-second check watches each online player's op, whitelist and game mode
 * and sends a sample the moment one of them changes, so the panel's player menu follows an
 * `/op` or a `/gamemode` - its own or anyone's - without waiting out the ten-second cadence.
 * Neither Bukkit nor the vanilla server fires an event for op or whitelist changes, which is why
 * this polls instead of listening.
 */
class MetricsReporter(
    private val vertx: Vertx,
    private val logger: Logger,
    private val pluginMain: PanoPluginMain,
    private val serverData: ServerData,
    private val send: (PlatformRequest) -> Unit,
    private val isConnected: () -> Boolean
) {
    companion object {
        /** The cadence nobody asked for: what a source reports at while no panel is watching. */
        const val INTERVAL_MILLIS = 10_000L

        /** Fastest cadence Pano may ask for (AGENT.md 2.4.23). */
        const val MIN_INTERVAL_MILLIS = 500L

        /** Slowest cadence Pano may ask for. */
        const val MAX_INTERVAL_MILLIS = 60_000L

        /**
         * How long a requested cadence holds without being renewed. Pano re-sends it every 60 s
         * while a panel is watching; a Pano that went away without saying so must not leave this
         * server reporting every second forever.
         */
        const val LEASE_MILLIS = 90_000L

        /** A requested cadence brought into the range the protocol allows. */
        fun clampInterval(requested: Long): Long = requested.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)

        /** How often the players' op / whitelist / game mode are compared with the last sample. */
        const val PLAYER_STATE_CHECK_MILLIS = 1_000L

        /** What the state check compares: who is on, and the three things the panel shows. */
        internal fun playerState(players: List<PlayerData>): List<List<Any?>> = players
            .map { listOf(it.uuid, it.op, it.whitelisted, it.gamemode) }
            .sortedBy { it[0] as String }
    }

    @Volatile
    private var timerId: Long? = null

    @Volatile
    private var stateTimerId: Long? = null

    /** [playerState] of the last sample sent; null until one has been. */
    @Volatile
    private var lastPlayerState: List<List<Any?>>? = null

    // The cadence the timer runs at, and when a non-default one stops holding. Both are only
    // written under `this` (setInterval, the lease check, a reset) and read by the timer.
    @Volatile
    private var intervalMillis = INTERVAL_MILLIS

    @Volatile
    private var leaseExpiresAt = 0L

    /** Clock for the lease; a test moves it instead of waiting ninety seconds. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * The server directory's size, measured on the IO dispatcher and kept for five minutes.
     *
     * Built here rather than passed in because nothing else wants it, and internal so a test can
     * look at what the reporter would report.
     */
    internal val diskUsage = DiskUsageProbe({ pluginMain.getServerDirectory().absoluteFile }, logger)

    // Resolved once: com.sun.management.OperatingSystemMXBean is a HotSpot/OpenJ9 extension that
    // is not guaranteed to exist, and looking the method up on the INTERFACE (rather than on the
    // bean's own, package-private implementation class) is what keeps the call accessible.
    private val processCpuLoad by lazy {
        try {
            val bean = ManagementFactory.getOperatingSystemMXBean()
            val extendedClass = Class.forName("com.sun.management.OperatingSystemMXBean")

            if (!extendedClass.isInstance(bean)) {
                null
            } else {
                val method = extendedClass.getMethod("getProcessCpuLoad")
                val accessor: () -> Double? = { (method.invoke(bean) as? Number)?.toDouble() }

                accessor
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun start() {
        if (timerId != null) {
            return
        }

        timerId = vertx.setPeriodic(intervalMillis) { tick() }
        stateTimerId = vertx.setPeriodic(PLAYER_STATE_CHECK_MILLIS) { checkPlayerState() }
    }

    @Synchronized
    fun stop() {
        timerId?.let { vertx.cancelTimer(it) }
        timerId = null
        stateTimerId?.let { vertx.cancelTimer(it) }
        stateTimerId = null
        lastPlayerState = null
    }

    /**
     * Sends a sample now if any player's op, whitelist or game mode differs from the last one
     * sent. A join or quit changes it too, which only means the ping column catches up early.
     */
    internal fun checkPlayerState() {
        if (!isConnected()) {
            return
        }

        val last = lastPlayerState ?: return
        val current = playerState(safely(emptyList()) { pluginMain.getOnlinePlayers() })

        if (current != last) {
            report()
        }
    }

    /** The cadence samples are currently sent at. */
    fun currentInterval(): Long = intervalMillis

    /**
     * `SET_METRICS_INTERVAL` (AGENT.md 2.4.23): report every [requested] milliseconds, clamped to
     * 0.5-60 s, for the next [LEASE_MILLIS] unless Pano renews it. A missing value means "back to
     * normal". Renewing the same cadence only extends the lease; the timer is left alone, so a
     * lease renewal every minute never shifts the tick.
     */
    @Synchronized
    fun setInterval(requested: Long?) {
        val interval = requested?.let { clampInterval(it) } ?: INTERVAL_MILLIS

        leaseExpiresAt = if (interval == INTERVAL_MILLIS) 0L else clock() + LEASE_MILLIS

        reschedule(interval)
    }

    /**
     * Back to the ten-second default, as on every new connection: a cadence was asked for by a
     * Pano that is no longer on the other end, and the one that comes back will ask again if a
     * panel is still watching.
     */
    @Synchronized
    fun resetInterval() {
        leaseExpiresAt = 0L

        reschedule(INTERVAL_MILLIS)
    }

    /** Swaps the running timer for one at [interval]; a stopped reporter just remembers it. */
    private fun reschedule(interval: Long) {
        if (interval == intervalMillis) {
            return
        }

        intervalMillis = interval

        val running = timerId ?: return

        vertx.cancelTimer(running)

        timerId = vertx.setPeriodic(interval) { tick() }
    }

    /**
     * One timer firing: drop an expired cadence first, then report. Checked here rather than on a
     * timer of its own because nothing needs to happen between ticks - a lapsed lease only
     * matters at the moment the next sample would go out too early.
     */
    internal fun tick() {
        if (intervalMillis != INTERVAL_MILLIS && clock() >= leaseExpiresAt) {
            synchronized(this) {
                if (intervalMillis != INTERVAL_MILLIS && clock() >= leaseExpiresAt) {
                    logger.fine("Metrics cadence lease lapsed; back to every ${INTERVAL_MILLIS / 1000} s.")

                    resetInterval()
                }
            }
        }

        report()
    }

    // internal rather than private so MetricsReporterTest can take a sample without waiting out
    // the ten-second timer.
    internal fun report() {
        if (!isConnected()) {
            return
        }

        try {
            send(sample())
        } catch (exception: Throwable) {
            // A metrics sample is disposable: never let one failure kill the periodic timer.
            logger.fine("Failed to report server metrics: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    internal fun sample(): ServerMetricsRequest {
        val runtime = Runtime.getRuntime()
        val players = safely(emptyList()) { pluginMain.getOnlinePlayers() }

        lastPlayerState = playerState(players)

        return ServerMetricsRequest(
            t = System.currentTimeMillis(),
            // Rounded centrally: every platform provider would otherwise have to remember to do
            // it, and a raw double drags a dozen meaningless digits onto the wire.
            tps = safely<DoubleArray?>(null) { pluginMain.getTps() }?.map { round(it) },
            mspt = safely<Double?>(null) { pluginMain.getMspt() }?.let { round(it) },
            memUsed = runtime.totalMemory() - runtime.freeMemory(),
            memMax = runtime.maxMemory(),
            cpu = readCpuPercentage(),
            playerCount = safely(players.size) { serverData.playerCount() },
            maxPlayerCount = safely(0) { serverData.maxPlayerCount() },
            players = players,
            // Never measured here: this only reads the last walk's result and asks for a new one
            // when it has gone stale (see DiskUsageProbe). Null until the first one lands.
            diskUsed = safely<Long?>(null) { diskUsage.read() },
            diskTotal = readDiskTotal()
        )
    }

    /**
     * The size of the partition the server directory lives on, or null when it cannot be had.
     *
     * Unlike [diskUsage] this is one `statvfs` behind the scenes, not a walk, so it is taken
     * inline on every sample. `totalSpace` answers 0 for a path that is not there or that this
     * process may not stat, and a zero would draw an empty disk gauge in the panel rather than
     * the "-" that honestly means "unknown".
     */
    private fun readDiskTotal(): Long? = safely<Long?>(null) {
        pluginMain.getServerDirectory().totalSpace.takeIf { it > 0L }
    }

    /** Two decimal places: plenty for a graph, and short on the wire. */
    private fun round(value: Double): Double =
        if (value.isNaN() || value.isInfinite()) 0.0 else Math.round(value * 100.0) / 100.0

    /** Process CPU as a 0-100 percentage, or null when this JVM does not report it. */
    private fun readCpuPercentage(): Double? {
        val load = try {
            processCpuLoad?.invoke()
        } catch (_: Throwable) {
            null
        } ?: return null

        // The JVM returns a negative value while it has no reading yet, and NaN on some platforms.
        if (load.isNaN() || load < 0.0) {
            return null
        }

        return Math.round((load * 100.0).coerceAtMost(100.0) * 100.0) / 100.0
    }

    /**
     * Runs a platform accessor, falling back to [fallback] if it throws.
     *
     * These accessors reach into the server's own API from a Vert.x thread; one of them blowing up
     * (a version whose reflection target moved, a proxy shutting down mid-sample) must cost the
     * sample that one field, not the whole report.
     */
    private fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        fallback
    }
}
