// The world map (assets/world.bin, made by tools/gen_world_asset.py from Natural Earth, public domain): land rings and
// country borders as lon/lat pairs in degrees. The same file and reader as HF Propagation (uk.hamprop.map.WorldData);
// its cities section is read past (this app does not show cities).
package uk.hamdigital.map

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorldData(
    val land: List<FloatArray>,                       // each ring: lon0, lat0, lon1, lat1 ...
    val borders: List<FloatArray>,                    // each line, the same way
) {
    companion object {
        @Volatile private var cached: WorldData? = null // loaded once

        /** Read the asset (about 330 KB; a few milliseconds). */
        fun load(ctx: Context): WorldData = cached ?: synchronized(this) {
            cached ?: read(ctx.assets.open("world.bin").use { it.readBytes() }).also { cached = it }
        }

        private fun read(b: ByteArray): WorldData {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN) // little-endian file
            require(b[0] == 'H'.code.toByte() && b[3] == '1'.code.toByte()) { "world.bin: bad header" } // "HPW1"
            bb.position(4)                            // after the magic
            fun lines(): List<FloatArray> = List(bb.int) { // a section of rings / lines
                val n = bb.int                        // points
                FloatArray(n * 2) { bb.short / 100f } // lon, lat, lon, lat ...
            }
            val land = lines(); val brd = lines()     // the two vector sections (the cities after them are not needed)
            return WorldData(land, brd)
        }
    }
}
