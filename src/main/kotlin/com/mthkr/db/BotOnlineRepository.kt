package com.mthkr.db

import org.slf4j.LoggerFactory
import java.sql.DriverManager

class BotOnlineRepository(dbPath: String) {
    private val log = LoggerFactory.getLogger(BotOnlineRepository::class.java)
    private val url = "jdbc:sqlite:$dbPath"

    init {
        createTable()
        log.info("BotOnlineRepository initialized")
    }

    private fun createTable() {
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS bot_online (
                        last_online_timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    fun getLastOnlineTimestamp(): Long? {
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT last_online_timestamp FROM bot_online LIMIT 1")
                return if (rs.next()) rs.getLong("last_online_timestamp") else null
            }
        }
    }

    fun updateTimestamp(timestamp: Long = System.currentTimeMillis()) {
        DriverManager.getConnection(url).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("DELETE FROM bot_online") }
                conn.prepareStatement("INSERT INTO bot_online (last_online_timestamp) VALUES (?)").use { stmt ->
                    stmt.setLong(1, timestamp)
                    stmt.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}