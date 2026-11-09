package com.kb.ledger.data.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Test helper that connects to an in-memory SQLite database via JDBC
 * and provides a [SupportSQLiteDatabase] wrapper to test Room [androidx.room.migration.Migration]
 * on the JVM without requiring Android device/emulator instrumentation.
 */
class TestDatabaseHelper : AutoCloseable {

    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    val supportDatabase: SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
        object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                return when (method.name) {
                    "execSQL" -> {
                        val sql = args?.get(0) as String
                        connection.createStatement().use { stmt ->
                            stmt.execute(sql)
                        }
                        null
                    }
                    "isOpen" -> !connection.isClosed
                    "close" -> {
                        connection.close()
                        null
                    }
                    "toString" -> "TestSupportSQLiteDatabase(in-memory)"
                    else -> throw UnsupportedOperationException("Method ${method.name} not implemented in test stub")
                }
            }
        }
    ) as SupportSQLiteDatabase

    fun execute(sql: String) {
        connection.createStatement().use { stmt ->
            stmt.execute(sql)
        }
    }

    fun <T> query(sql: String, mapper: (ResultSet) -> T): List<T> {
        val results = mutableListOf<T>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        return results
    }

    override fun close() {
        if (!connection.isClosed) {
            connection.close()
        }
    }
}
