package com.mthkr.db

import com.mthkr.monitor.GridState
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager

class GridStateRepository(dbPath: String) {
    private val log = LoggerFactory.getLogger(GridStateRepository::class.java)
    private val url = "jdbc:sqlite:$dbPath"

    init {
        File(dbPath).parentFile?.mkdirs()
        createTable()
        log.info("GridStateRepository initialized at {}", dbPath)
    }

    private fun createTable() {
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS grid_state_registry (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        grid_state TEXT NOT NULL,
                        timestamp  INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    fun record(state: GridState, timestamp: Long = System.currentTimeMillis()) {
        if (state == GridState.UNKNOWN) return
        DriverManager.getConnection(url).use { conn ->
            conn.prepareStatement(
                "INSERT INTO grid_state_registry (grid_state, timestamp) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setString(1, state.name)
                stmt.setLong(2, timestamp)
                stmt.executeUpdate()
            }
        }
        log.debug("Recorded grid state {} at {}", state, timestamp)
    }

    /** Returns the timestamp of the most recent row with the given state, or null if none exists. */
    fun findMostRecentTimestamp(state: GridState): Long? {
        DriverManager.getConnection(url).use { conn ->
            conn.prepareStatement(
                "SELECT timestamp FROM grid_state_registry WHERE grid_state = ? ORDER BY timestamp DESC LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, state.name)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getLong("timestamp") else null
            }
        }
    }
}