package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb

fun Table.jsonbText(name: String): Column<String> =
    jsonb(name, { it }, { it })
