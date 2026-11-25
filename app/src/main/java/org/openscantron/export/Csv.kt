package org.openscantron.export

import java.io.PushbackReader
import java.io.Reader
import java.io.Writer

object Csv {
    fun write(writer: Writer, rows: Sequence<List<String>>) {
        rows.forEach { row ->
            writer.append(row.joinToString(",") { field ->
                if (field.any { it in ",\"\r\n" }) "\"${field.replace("\"", "\"\"")}\"" else field
            })
            writer.append("\r\n")
        }
    }

    fun read(reader: Reader): List<List<String>> {
        val input = PushbackReader(reader.buffered())
        var character = input.read()
        if (character == 0xfeff) character = input.read()
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var started = false
        fun emitField() {
            row += field.toString()
            field.clear()
            closed = false
            started = false
        }
        while (character != -1) {
            if (quoted) {
                if (character == '"'.code) {
                    val next = input.read()
                    if (next == '"'.code) field.append('"')
                    else {
                        quoted = false
                        closed = true
                        if (next != -1) input.unread(next)
                    }
                } else field.append(character.toChar())
            } else when (character.toChar()) {
                ',' -> emitField()
                '\n', '\r' -> {
                    if (character == '\r'.code) {
                        val next = input.read()
                        if (next != -1 && next != '\n'.code) input.unread(next)
                    }
                    emitField()
                    rows += row
                    row = mutableListOf()
                }
                '"' -> {
                    require(!started && !closed) { "Unexpected quote on CSV record ${rows.size + 1}." }
                    quoted = true
                    started = true
                }
                else -> {
                    require(!closed) { "Unexpected content after quote on CSV record ${rows.size + 1}." }
                    field.append(character.toChar())
                    started = true
                }
            }
            character = input.read()
        }
        require(!quoted) { "Unfinished quoted field on CSV record ${rows.size + 1}." }
        if (row.isNotEmpty() || started || closed) {
            emitField()
            rows += row
        }
        return rows
    }
}
