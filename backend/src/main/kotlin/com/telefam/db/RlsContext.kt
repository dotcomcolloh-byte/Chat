package com.telefam.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * Runs a DB block inside a transaction with `app.current_user_id` set for that
 * transaction only (SET LOCAL — automatically reset at commit/rollback, never leaks
 * across pooled connections). Postgres RLS policies (rls_policies.sql) key off this
 * variable, so even a bug in application code cannot leak another user's row —
 * the database itself refuses it.
 *
 * `userId` is a real java.util.UUID, not a raw string, so there is no injection
 * surface: UUID.toString() only ever produces the fixed 8-4-4-4-12 hex format.
 */
object RlsContext {
    suspend fun <T> asUser(userId: UUID, block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction {
                exec("SET LOCAL app.current_user_id = '${userId}'")
                block()
            }
        }
}
