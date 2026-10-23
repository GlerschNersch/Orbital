package dev.watchreborn.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A cell of a hex lattice in axial coordinates. */
data class Hex(val q: Int, val r: Int) {
    /** Steps between two cells. */
    fun distanceTo(o: Hex): Int {
        val dq = q - o.q
        val dr = r - o.r
        return max(abs(dq), max(abs(dr), abs(dq + dr)))
    }

    companion object {
        /** The six neighbours' offsets, clockwise from straight above (flat-top) or from the right (pointy-top). */
        val DIRECTIONS = listOf(Hex(1, 0), Hex(1, -1), Hex(0, -1), Hex(-1, 0), Hex(-1, 1), Hex(0, 1))

        /**
         * The first [n] cells outward from the centre, ring by ring, each ring starting from the same
         * side: the order apps fill the grid in (most used in the middle).
         */
        fun spiral(n: Int): List<Hex> {
            val out = ArrayList<Hex>(n)
            if (n <= 0) return out
            out.add(Hex(0, 0))
            var ring = 1
            while (out.size < n) {
                // Start at the ring's cell in direction 4 and walk the six sides.
                var h = Hex(DIRECTIONS[4].q * ring, DIRECTIONS[4].r * ring)
                for (side in 0 until 6) {
                    for (step in 0 until ring) {
                        if (out.size == n) return out
                        out.add(h)
                        val d = DIRECTIONS[side]
                        h = Hex(h.q + d.q, h.r + d.r)
                    }
                }
                ring++
            }
            return out
        }
    }
}

/** Centre of [h] for a pointy-topped lattice (horizontal rows) with neighbours [pitch] apart. */
fun pointyCenter(h: Hex, pitch: Float, out: FloatArray) {
    out[0] = pitch * (h.q + h.r / 2f)
    out[1] = pitch * (sqrt(3f) / 2f) * h.r
}

/** Centre of [h] for a flat-topped lattice (vertical columns) with neighbours [pitch] apart. */
fun flatCenter(h: Hex, pitch: Float, out: FloatArray) {
    out[0] = pitch * (sqrt(3f) / 2f) * h.q
    out[1] = pitch * (h.r + h.q / 2f)
}
