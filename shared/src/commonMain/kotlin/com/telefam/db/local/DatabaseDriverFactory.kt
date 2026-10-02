package com.telefam.db.local

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

/** Single shared instance so every screen/repository reads and writes the same local outbox. */
object LocalDatabase {
    private var instance: TelefamDatabase? = null

    fun getInstance(factory: DatabaseDriverFactory): TelefamDatabase =
        instance ?: TelefamDatabase(factory.createDriver()).also { instance = it }
}
