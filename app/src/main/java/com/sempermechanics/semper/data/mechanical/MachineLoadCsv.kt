@file:Suppress("MagicNumber", "ReturnCount")

package com.sempermechanics.semper.data.mechanical

/** The unit a load column was logged in; [toNewton] scales it to N for storage. */
enum class LoadUnit(val toNewton: Float, val label: String) {
    N(1f, "N"),
    KN(1000f, "kN"),
    LBF(4.4482216f, "lbf"),

    /** Kilogram-force (also kilopond, kp): standard gravity, 9.80665 N. */
    KGF(TypedLoads.G, "kgf"),

    /** Tonne-force: 1000 kgf, 9806.65 N. */
    TF(TypedLoads.G * 1000f, "tf"),
}

/**
 * The unit a log's time column is in. Each converts to seconds by a division
 * or a whole multiple, so a round value (500 ms, 0.5 min) stays exact in Float.
 */
private enum class LogTimeUnit {
    MS,
    S,
    MIN,
    H,
    ;

    fun toSeconds(value: Float): Float = when (this) {
        MS -> value / 1000f
        S -> value
        MIN -> value * 60f
        H -> value * 3600f
    }

    companion object {
        private val MS_TEXT = Regex("""\bms\b|msec|millisec""", RegexOption.IGNORE_CASE)
        private val MIN_TEXT = Regex("""\bmin\b|minute""", RegexOption.IGNORE_CASE)
        private val H_TEXT = Regex("""\bh\b|\bhr\b|hour""", RegexOption.IGNORE_CASE)
        private val S_TEXT = Regex("""\bs\b|\bsec|second""", RegexOption.IGNORE_CASE)

        /** The unit named in a time column's header and units cell, or null when none is. */
        fun of(text: String): LogTimeUnit? = when {
            MS_TEXT.containsMatchIn(text) -> MS
            MIN_TEXT.containsMatchIn(text) -> MIN
            H_TEXT.containsMatchIn(text) -> H
            S_TEXT.containsMatchIn(text) -> S
            else -> null
        }
    }
}

/** Something the parser guessed rather than read; shown as a chip, never blocking. */
enum class LoadCsvWarning {
    /** No unit was found in the header or a units row, so the column is taken as N. */
    UNITS_ASSUMED_N,

    /** The load column is in kg, a mass; it is read as kilogram-force (× 9.80665 N). */
    KG_READ_AS_KGF,

    /** There was no header naming a load column; the column was picked by its numbers. */
    COLUMN_GUESSED,

    /** A time column was present but its values do not increase, so it is ignored. */
    TIME_IGNORED,

    /** The time column names no unit (s, ms, min, h), so its values are taken as seconds. */
    TIME_UNIT_ASSUMED_S,
}

/** Why a file could not be read at all. */
sealed interface LoadCsvError {
    data object Empty : LoadCsvError
    data object TooManyRows : LoadCsvError
    data object NoLoadColumn : LoadCsvError

    /** [line] is 1-based in the file as written, [column] is 1-based. */
    data class NonNumeric(val line: Int, val column: Int, val text: String) : LoadCsvError
}

/**
 * A machine load log as read from disk. Loads are already in newtons and keep
 * their sign; [timesS] is index-aligned with [loadsN] or null when the file
 * had no usable time column.
 */
data class ParsedLoadCsv(
    val loadsN: List<Float>,
    val timesS: List<Float>?,
    val unit: LoadUnit,
    val loadColumn: Int,
    val timeColumn: Int?,
    val loadHeader: String,
    val warnings: List<LoadCsvWarning>,
) {
    val rows: Int get() = loadsN.size
}

sealed interface LoadCsvParse {
    data class Ok(val csv: ParsedLoadCsv) : LoadCsvParse
    data class Failed(val error: LoadCsvError) : LoadCsvParse
}

/**
 * Reads a universal-testing-machine export without asking the user anything:
 * the delimiter, decimal mark, header, units row, and the load and time
 * columns are all inferred, and a preamble above the header is skipped. Every guess is reported
 * as a [LoadCsvWarning]; only a row whose load cell is not a number fails.
 *
 * Pure Kotlin — no Android imports — so the fixtures in the unit tests are
 * the whole contract.
 */
object MachineLoadCsv {

    const val MAX_ROWS = 200_000

    private const val SNIFF_LINES = 10

    private val LOAD_HEADER = Regex("""load|force|\bkn\b|\bn\b|newton|lbf|kgf|\bkp\b|\btf\b""", RegexOption.IGNORE_CASE)
    private val TIME_HEADER = Regex("""time|\bs\b|sec|elapsed""", RegexOption.IGNORE_CASE)
    private val UNIT_KN = Regex("""\bkn\b""", RegexOption.IGNORE_CASE)
    private val UNIT_LBF = Regex("""\blbf?\b""", RegexOption.IGNORE_CASE)
    private val UNIT_N = Regex("""\bn\b|newton""", RegexOption.IGNORE_CASE)
    private val UNIT_KGF = Regex("""\bkgf\b|\bkp\b""", RegexOption.IGNORE_CASE)
    private val UNIT_TF = Regex("""\btf\b""", RegexOption.IGNORE_CASE)
    private val UNIT_KG = Regex("""\bkg\b""", RegexOption.IGNORE_CASE)
    private val DECIMAL_COMMA = Regex("""^[+-]?\d+,\d+$""")
    private val THOUSANDS = Regex("""^[+-]?\d{1,3}(,\d{3})+(\.\d+)?$""")
    private val UNIT_CELL = Regex(
        """^[(\[]?\s*(s|sec|ms|min|h|mm|cm|m|µm|um|in|n|kn|lbf|lb|kgf|kg|kp|tf|%|mm/mm|-)\s*[)\]]?$""",
        RegexOption.IGNORE_CASE,
    )

    private data class Line(val number: Int, val cells: List<String>)

    fun parse(text: String): LoadCsvParse {
        val lines = text.removePrefix("\uFEFF")
            .lineSequence()
            .mapIndexed { index, raw -> index + 1 to raw.trim() }
            .filter { (_, line) -> line.isNotEmpty() && !line.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return LoadCsvParse.Failed(LoadCsvError.Empty)

        val delimiter = sniffDelimiter(lines.take(SNIFF_LINES).map { it.second })
        // A line of empty cells (a `" "` spacer, or bare delimiters) is blank.
        val table = lines.map { (number, line) -> Line(number, splitCells(line, delimiter)) }
            .filter { line -> line.cells.any { it.isNotEmpty() } }
        if (table.isEmpty()) return LoadCsvParse.Failed(LoadCsvError.Empty)

        // Where the data starts is found before the decimal mark is known, so a
        // cell counts as a number when it reads as one either way.
        val layout = findHeader(table) { cell -> parseNumber(cell, true) ?: parseNumber(cell, false) }
        val data = table.drop(layout.dataStart)
        if (data.isEmpty()) return LoadCsvParse.Failed(LoadCsvError.Empty)
        if (data.size > MAX_ROWS) return LoadCsvParse.Failed(LoadCsvError.TooManyRows)
        // The decimal mark comes from the data rows alone: a long preamble must
        // not hide them. In a `;` or tab file a comma between digits ("0,875",
        // "1,250") is a decimal mark; a comma file can hold one only in quotes,
        // where it is a thousands mark.
        val decimalComma = delimiter != ',' &&
            data.any { line -> line.cells.any { DECIMAL_COMMA.matches(it) } }
        val numberOf: (String) -> Float? = { cell -> parseNumber(cell, decimalComma) }

        val warnings = mutableListOf<LoadCsvWarning>()
        val columns = pickColumns(layout.header, data, numberOf, warnings)
            ?: return LoadCsvParse.Failed(LoadCsvError.NoLoadColumn)
        val unit = unitFor(layout.header, layout.units, columns.load, warnings)

        val loads = ArrayList<Float>(data.size)
        for (line in data) {
            val cell = line.cells.getOrElse(columns.load) { "" }
            val value = numberOf(cell)
                ?: return LoadCsvParse.Failed(LoadCsvError.NonNumeric(line.number, columns.load + 1, cell))
            loads.add(value * unit.toNewton)
        }
        val times = columns.time?.let { column -> readTimes(data, column, layout, numberOf, warnings) }

        return LoadCsvParse.Ok(
            ParsedLoadCsv(
                loadsN = loads,
                timesS = times,
                unit = unit,
                loadColumn = columns.load,
                timeColumn = if (times == null) null else columns.time,
                loadHeader = layout.header?.getOrNull(columns.load).orEmpty(),
                warnings = warnings.distinct(),
            ),
        )
    }

    // ── delimiter and cells ──────────────────────────────────────────────────

    /**
     * The candidate that splits the most sniffed lines into the same non-trivial
     * number of cells. Ties prefer `;` then tab, since a comma in those files
     * is usually a decimal mark rather than a separator.
     */
    private fun sniffDelimiter(lines: List<String>): Char {
        val candidates = listOf(';', '\t', ',')
        var best = ','
        var bestScore = 0
        for (candidate in candidates) {
            val counts = lines.map { line -> line.count { it == candidate } }.filter { it > 0 }
            if (counts.isEmpty()) continue
            val mode = counts.groupingBy { it }.eachCount().maxBy { it.value }.key
            val score = counts.count { it == mode }
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun splitCells(line: String, delimiter: Char): List<String> =
        line.split(delimiter).map { it.trim().removeSurrounding("\"").trim() }

    private fun parseNumber(cell: String, decimalComma: Boolean): Float? {
        if (cell.isEmpty()) return null
        val normalised = when {
            decimalComma -> cell.replace(',', '.')
            THOUSANDS.matches(cell) -> cell.replace(",", "")
            else -> cell
        }
        val value = normalised.toFloatOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    // ── header and units row ─────────────────────────────────────────────────

    private data class Layout(val header: List<String>?, val units: List<String>?, val dataStart: Int)

    /**
     * The data starts at the first numeric line and the header is the line
     * just before it; when that line holds only units it is the units row and
     * the header is the line above. Anything earlier is a preamble (file name,
     * record number, date) and is ignored. A file with no numeric line keeps
     * its first line as the header, so it reads as empty or as having no load.
     */
    private fun findHeader(table: List<Line>, numberOf: (String) -> Float?): Layout {
        val dataStart = table.indexOfFirst { isNumericRow(it, numberOf) }
        if (dataStart == 0) return Layout(null, null, 0)
        if (dataStart < 0) return Layout(table.first().cells, null, 1)
        val before = table[dataStart - 1].cells
        val above = table.getOrNull(dataStart - 2)?.cells
        val unitsOnly = before.filter { it.isNotEmpty() }.all { UNIT_CELL.matches(it) }
        return if (above != null && unitsOnly) {
            Layout(above, before, dataStart)
        } else {
            Layout(before, null, dataStart)
        }
    }

    private fun isNumericRow(line: Line, numberOf: (String) -> Float?): Boolean {
        val cells = line.cells.filter { it.isNotEmpty() }
        if (cells.isEmpty()) return false
        return cells.count { numberOf(it) != null } * 2 >= cells.size
    }

    // ── columns ──────────────────────────────────────────────────────────────

    private data class Columns(val load: Int, val time: Int?)

    private fun pickColumns(
        header: List<String>?,
        data: List<Line>,
        numberOf: (String) -> Float?,
        warnings: MutableList<LoadCsvWarning>,
    ): Columns? {
        if (header != null) {
            val load = header.indexOfFirst { LOAD_HEADER.containsMatchIn(it) }
            if (load >= 0) {
                val time = header.indices.firstOrNull { it != load && TIME_HEADER.containsMatchIn(header[it]) }
                return Columns(load, time)
            }
        }
        val guessed = guessColumns(data, numberOf) ?: return null
        warnings += LoadCsvWarning.COLUMN_GUESSED
        return guessed
    }

    /**
     * No header named the load. One numeric column is the load; with more,
     * time is the first column that never decreases and the load is the last
     * other numeric column (UTM exports put the machine channels last).
     */
    private fun guessColumns(data: List<Line>, numberOf: (String) -> Float?): Columns? {
        val width = data.maxOf { it.cells.size }
        val numeric = (0 until width).filter { column ->
            data.all { line -> numberOf(line.cells.getOrElse(column) { "" }) != null }
        }
        if (numeric.isEmpty()) return null
        if (numeric.size == 1) return Columns(numeric.single(), null)
        val time = numeric.firstOrNull { column ->
            val values = data.map { numberOf(it.cells[column]) ?: 0f }
            values.zipWithNext().all { (a, b) -> b >= a } && values.first() != values.last()
        }
        val load = numeric.last { it != time }
        return Columns(load, time)
    }

    private fun unitFor(
        header: List<String>?,
        units: List<String>?,
        column: Int,
        warnings: MutableList<LoadCsvWarning>,
    ): LoadUnit {
        val text = listOfNotNull(units?.getOrNull(column), header?.getOrNull(column)).joinToString(" ")
        // A bare "kg" is a mass, not a force: it is read as kgf, and says so.
        return when {
            UNIT_KN.containsMatchIn(text) -> LoadUnit.KN
            UNIT_LBF.containsMatchIn(text) -> LoadUnit.LBF
            UNIT_KGF.containsMatchIn(text) -> LoadUnit.KGF
            UNIT_TF.containsMatchIn(text) -> LoadUnit.TF
            UNIT_N.containsMatchIn(text) -> LoadUnit.N
            UNIT_KG.containsMatchIn(text) -> {
                warnings += LoadCsvWarning.KG_READ_AS_KGF
                LoadUnit.KGF
            }
            else -> {
                warnings += LoadCsvWarning.UNITS_ASSUMED_N
                LoadUnit.N
            }
        }
    }

    /**
     * Times are optional: a bad or non-increasing column is dropped, not
     * fatal. The rest are converted to seconds from the unit the header or
     * units row names (ms, min, h); none named is taken as seconds and says so.
     */
    private fun readTimes(
        data: List<Line>,
        column: Int,
        layout: Layout,
        numberOf: (String) -> Float?,
        warnings: MutableList<LoadCsvWarning>,
    ): List<Float>? {
        val times = data.map { line -> numberOf(line.cells.getOrElse(column) { "" }) ?: return null }
        val monotonic = times.zipWithNext().all { (a, b) -> b >= a }
        if (!monotonic) {
            warnings += LoadCsvWarning.TIME_IGNORED
            return null
        }
        val unitText = listOfNotNull(layout.units?.getOrNull(column), layout.header?.getOrNull(column))
        val timeUnit = LogTimeUnit.of(unitText.joinToString(" "))
            ?: LogTimeUnit.S.also { warnings += LoadCsvWarning.TIME_UNIT_ASSUMED_S }
        return times.map(timeUnit::toSeconds)
    }
}
