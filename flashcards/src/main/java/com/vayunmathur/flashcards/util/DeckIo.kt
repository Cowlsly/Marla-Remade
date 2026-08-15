package com.vayunmathur.flashcards.util

/** CSV import for bulk note creation. Per-deck sharing now uses `.apkg` (see [ApkgExport]). */
object DeckIo {

    /**
     * Serializes notes to `front,back` CSV. [rows] are (front, back) pairs already
     * stripped of HTML/markers by the caller. A header row is written first.
     */
    fun writeCsv(rows: List<Pair<String, String>>): String {
        val sb = StringBuilder("front,back\n")
        rows.forEach { (front, back) ->
            sb.append(escapeCsv(front)).append(',').append(escapeCsv(back)).append('\n')
        }
        return sb.toString()
    }

    private fun escapeCsv(field: String): String {
        val needsQuoting = field.contains(',') || field.contains('"') || field.contains('\n')
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * Parses CSV text into (front, back) pairs. Accepts two columns per row; a
     * standard-quoted first line of `front,back` is skipped as a header. Handles
     * double-quoted fields with embedded commas and escaped quotes.
     */
    fun parseCsv(text: String): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val fields = splitCsvLine(raw)
            if (fields.size < 2) return@forEachIndexed
            val front = fields[0].trim()
            val back = fields[1].trim()
            if (index == 0 && front.equals("front", true) && back.equals("back", true)) {
                return@forEachIndexed
            }
            if (front.isNotEmpty() || back.isNotEmpty()) rows.add(front to back)
        }
        return rows
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
