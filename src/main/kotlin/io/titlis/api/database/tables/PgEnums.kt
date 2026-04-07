package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

private class PostgreSqlEnumColumnType(
    private val typeName: String,
) : ColumnType<String>() {
    override fun sqlType(): String = typeName

    override fun valueFromDB(value: Any): String =
        when (value) {
            is PGobject -> value.value.orEmpty()
            else -> value.toString()
        }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = typeName
            this.value = value
        }

    override fun nonNullValueAsDefaultString(value: String): String = "'$value'"
}

fun Table.pgEnum(name: String, typeName: String): Column<String> =
    registerColumn(name, PostgreSqlEnumColumnType(typeName))
