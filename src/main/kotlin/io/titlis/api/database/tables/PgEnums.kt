package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

private class PostgreSqlEnumColumnType(
    // typeName: schema-qualified (e.g. "titlis_oltp.compliance_status").
    // Used for CREATE TABLE / ADD COLUMN DDL and for PGobject writes.
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

    // PostgreSQL stores enum defaults as 'VALUE'::schema.type_name.
    // Matching this format prevents Exposed from generating a spurious ALTER TABLE
    // (which would be blocked by views depending on the column).
    override fun nonNullValueAsDefaultString(value: String): String = "'$value'::$typeName"
}

fun Table.pgEnum(name: String, typeName: String): Column<String> =
    registerColumn(name, PostgreSqlEnumColumnType(typeName))
