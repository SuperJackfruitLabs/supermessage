package dev.supermessage.kit

import kotlin.math.abs

/**
 * The glide of the history while an answer streams: when the live card grows
 * by some pixels the list lays out at once, without animation, and every
 * visible cell is drawn where it was and eased to where it now is, together.
 *
 * Ported from `TimelineCollectionView.swift`: 0.32 s (`glide`, line 187) on the
 * curve `(0.25, 0.1, 0.25, 1)` (line 226). **Additive**, as iOS adds one
 * animation per growth: a second growth mid-glide adds its own offset to the
 * one still running, each on its own clock, rather than restarting it, so two
 * sentences close together read as one longer move and not a hitch.
 *
 * Pure: the host draws `offsetAt(now)` as a translation and never lets it
 * touch layout.
 */
class HistoryGlide {
    private class Run(val delta: Float) {
        var startNanos: Long? = null
    }

    private val runs = ArrayList<Run>()

    /** Whether any glide has not finished. */
    val isActive: Boolean get() = runs.isNotEmpty()

    /**
     * The history grew by [deltaPx]: it starts that far from its place. The
     * run's clock starts at the first [offsetAt] after this, so the host's
     * frame clock is the only one used.
     */
    fun add(deltaPx: Float) {
        if (abs(deltaPx) > 0f) runs += Run(deltaPx)
    }

    /** How far the history is drawn from its place at [nowNanos], dropping runs that are over. */
    fun offsetAt(nowNanos: Long): Float {
        var sum = 0f
        val it = runs.iterator()
        while (it.hasNext()) {
            val run = it.next()
            val start = run.startNanos ?: nowNanos.also { run.startNanos = it }
            val t = (nowNanos - start).toFloat() / DURATION_NANOS
            if (t >= 1f) {
                it.remove()
            } else {
                sum += run.delta * (1f - ease(t.coerceAtLeast(0f)))
            }
        }
        return sum
    }

    fun clear() = runs.clear()

    companion object {
        /** `TimelineCollectionView.swift:187`. */
        const val DURATION_NANOS = 320_000_000L

        /** `TimelineCollectionView.swift:226`: `CAMediaTimingFunction(controlPoints: 0.25, 0.1, 0.25, 1)`. */
        const val X1 = 0.25f
        const val Y1 = 0.1f
        const val X2 = 0.25f
        const val Y2 = 1f

        /** Progress at time fraction [x] on the cubic Bezier from (0,0) to (1,1) through the control points. */
        fun ease(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            // Solve bezierX(s) = x by bisection, then read bezierY(s).
            var lo = 0f
            var hi = 1f
            repeat(24) {
                val mid = (lo + hi) / 2f
                if (bezier(mid, X1, X2) < x) lo = mid else hi = mid
            }
            return bezier((lo + hi) / 2f, Y1, Y2)
        }

        private fun bezier(s: Float, c1: Float, c2: Float): Float {
            val u = 1f - s
            return 3f * u * u * s * c1 + 3f * u * s * s * c2 + s * s * s
        }
    }
}
