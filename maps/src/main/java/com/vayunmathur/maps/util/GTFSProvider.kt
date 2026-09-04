package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object GTFSProvider {
    // Accessed concurrently from OfflineRouter.getRoute (Dispatchers.Default)
    // and from the map layers on the Main thread, so use a thread-safe map —
    // a plain HashMap mutation race can corrupt internal buckets and cause
    // ConcurrentModificationException or infinite get() loops.
    private val routeColors = ConcurrentHashMap<String, String>() // Key: feedName:routeName, Value: #HEX

    fun getRouteColor(context: Context, feedName: String, routeName: String): String? {
        val cacheKey = "$feedName:$routeName"
        if (routeColors.containsKey(cacheKey)) return routeColors[cacheKey]

        try {
            val assetPath = "$feedName/routes.txt"
            context.assets.open(assetPath).use { inputStream ->
                val reader = inputStream.bufferedReader()
                val header = parseCsvLine(reader.readLine() ?: return null).map { it.trim() }
                val shortNameIdx = header.indexOf("route_short_name")
                val longNameIdx = header.indexOf("route_long_name")
                val colorIdx = header.indexOf("route_color")

                if (colorIdx == -1) return null

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = parseCsvLine(line ?: continue)
                    val shortName = if (shortNameIdx != -1) parts.getOrNull(shortNameIdx) else null
                    val longName = if (longNameIdx != -1) parts.getOrNull(longNameIdx) else null

                    if (shortName == routeName || longName == routeName) {
                        val color = parts.getOrNull(colorIdx)
                        if (!color.isNullOrEmpty()) {
                            val fullColor = "#$color"
                            routeColors[cacheKey] = fullColor
                            return fullColor
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("GTFSProvider", "Failed to read routes.txt for $feedName", e)
        }
        return null
    }

    /**
     * Split one RFC 4180 CSV record into fields, honouring double-quoted fields
     * that contain commas (GTFS routinely quotes `route_long_name`, e.g.
     * `"Judah, Ocean Beach"`) and `""` escapes. A leading UTF-8 BOM is stripped.
     * Records with embedded newlines are not supported — this is line-oriented,
     * which is fine for the short rows in `routes.txt`.
     */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        if (line.startsWith('\uFEFF')) i = 1
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes ->
                        when {
                            c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                                current.append('"')
                                i++
                            }
                            c == '"' -> inQuotes = false
                            else -> current.append(c)
                        }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                c == '\r' -> {} // trailing CR from a CRLF file
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
