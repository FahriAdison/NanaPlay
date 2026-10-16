package com.opencloudgaming.opennow

/**
 * 1.0.72 — Evaluates runtime network statistics (packet loss, jitter, RTT) and
 * applies hysteresis-driven adaptation profiles based on [DynamicNetworkAdjustment].
 *
 * Strategies:
 * - [DynamicNetworkAdjustment.NoAdjustment]: fixed settings; no runtime or SDP adaptation.
 * - [DynamicNetworkAdjustment.OptimalQuality]: conservative; high hysteresis prevents flapping,
 *   holding on to stream resolution and high bitrate unless prolonged network failure occurs.
 * - [DynamicNetworkAdjustment.OptimalLatency]: aggressive; quickly reacts to loss/jitter spikes
 *   so frames keep rendering and input remains responsive, with fast recovery.
 */
class NetworkConditionAdaptor(
    val mode: DynamicNetworkAdjustment,
) {
    var isNetworkDegraded: Boolean = false
        private set

    private var consecutiveDegradedTicks = 0
    private var consecutiveHealthyTicks = 0

    fun reset() {
        isNetworkDegraded = false
        consecutiveDegradedTicks = 0
        consecutiveHealthyTicks = 0
    }

    fun onStats(stats: StreamRuntimeStats) {
        if (mode == DynamicNetworkAdjustment.NoAdjustment) return

        val loss = stats.packetLossPct ?: 0.0
        val jitter = stats.jitterMs ?: 0.0
        val ping = stats.pingMs ?: 0

        val (isDegradedTick, isHealthyTick, requiredDegradeTicks, requiredHealthyTicks) = when (mode) {
            DynamicNetworkAdjustment.OptimalLatency -> {
                val degraded = loss >= 2.0 || jitter >= 25.0 || ping >= 100
                val healthy = loss < 0.5 && jitter < 10.0 && ping < 80
                Tuple4(degraded, healthy, 2, 3)
            }
            DynamicNetworkAdjustment.OptimalQuality -> {
                val degraded = loss >= 5.0 || jitter >= 45.0 || ping >= 150
                val healthy = loss < 1.0 && jitter < 15.0 && ping < 100
                Tuple4(degraded, healthy, 5, 8)
            }
            DynamicNetworkAdjustment.NoAdjustment -> return
        }

        if (isDegradedTick) {
            consecutiveDegradedTicks++
            consecutiveHealthyTicks = 0
            if (consecutiveDegradedTicks >= requiredDegradeTicks && !isNetworkDegraded) {
                isNetworkDegraded = true
                NativeInputDiagnostics.add(
                    "network degraded ($mode): loss=${"%.1f".format(loss)}% jitter=${"%.1f".format(jitter)}ms ping=${ping}ms"
                )
            }
        } else if (isHealthyTick) {
            consecutiveHealthyTicks++
            consecutiveDegradedTicks = 0
            if (consecutiveHealthyTicks >= requiredHealthyTicks && isNetworkDegraded) {
                isNetworkDegraded = false
                NativeInputDiagnostics.add(
                    "network recovered ($mode): loss=${"%.1f".format(loss)}% jitter=${"%.1f".format(jitter)}ms ping=${ping}ms"
                )
            }
        } else {
            // Neutral tick: prevent quick flapping
            consecutiveDegradedTicks = (consecutiveDegradedTicks - 1).coerceAtLeast(0)
            consecutiveHealthyTicks = (consecutiveHealthyTicks - 1).coerceAtLeast(0)
        }
    }

    private data class Tuple4(
        val degraded: Boolean,
        val healthy: Boolean,
        val degradeThreshold: Int,
        val healthyThreshold: Int,
    )
}
