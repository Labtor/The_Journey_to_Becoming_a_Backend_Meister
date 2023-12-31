package com.simsim.plugins.database

import com.simsim.persistence.api.ApiTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.DisposableHandle
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Key
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

fun Events.connectExposed(): DisposableHandle = subscribe(ApplicationStarted) { application: Application ->
    runCatching {
        val config: ApplicationConfig = application.environment.config

        Database.connect(ExposedDataSource(config)).also { database: Database ->
            val tables: Array<Table> = arrayOf(
                ApiTable
            )

            transaction(database) {
                addLogger(StdOutSqlLogger)
                tables.run(SchemaUtils::create)
            }
        }
    }.onFailure { e: Throwable ->
        e.printStackTrace()
        throw e
    }
}

private class ExposedDataSource(
    config: ApplicationConfig
) : HikariDataSource(
    HikariConfig().apply {
        config.config(DATASOURCE).run {
            driverClassName = property(DRIVER).getString()
            username = property(USER).getString()
            jdbcUrl = property(URL).getString()
            password = property(PASSWD).getString()
        }
    }
) {
    companion object {
        private const val DATASOURCE: String = "exposed.datasource"
        private const val DRIVER: String = "driver"
        private const val USER: String = "user"
        private const val PASSWD: String = "password"
        private const val URL: String = "url"
    }
}

class InsertOrUpdate<Key : Any>(
    private val onDuplicateColumn: Column<Key>,
    table: Table,
    isIgnore: Boolean
) : InsertStatement<Key>(table, isIgnore) {
    override fun prepareSQL(transaction: Transaction): String =
        super.prepareSQL(transaction) + " ON DUPLICATE KEY UPDATE ${transaction.identity(onDuplicateColumn)}=VALUES(${transaction.identity(onDuplicateColumn)})"
}

fun <Key : Comparable<Key>, T : IdTable<Key>> T.insertOrUpdate(body: T.(InsertStatement<EntityID<Key>>) -> Unit) =
    InsertOrUpdate(this.id, this, false).run {
        body(this)
        execute(TransactionManager.current())
        get(id)
    }