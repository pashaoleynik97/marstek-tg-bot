package com.mthkr.db

import com.mthkr.monitor.GridState
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** A continuous period during which the inverter was observed in [state]. Timestamps are epoch ms. */
data class GridInterval(
    val id: Long,
    val state: GridState,
    val startTs: Long,
    val endTs: Long,
    val startSoc: Int?,
)

/**
 * Stores inverter grid presence as intervals. Each successful poll extends the current interval;
 * periods with no successful polls (bot down, dongle unreachable) are simply not covered → "No data".
 */
class InverterStateRepository(dbPath: String) {
    private val log = LoggerFactory.getLogger(InverterStateRepository::class.java)
    private val url = "jdbc:sqlite:$dbPath"

    init {
        File(dbPath).parentFile?.mkdirs()
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS inverter_grid_intervals (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        state     TEXT    NOT NULL,
                        start_ts  INTEGER NOT NULL,
                        end_ts    INTEGER NOT NULL,
                        start_soc INTEGER
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_inv_intervals_end ON inverter_grid_intervals(end_ts)")
            }
        }
        log.info("InverterStateRepository initialized at {}", dbPath)
    }

    private fun connect(): Connection = DriverManager.getConnection(url)

    fun insert(state: GridState, startTs: Long, endTs: Long, startSoc: Int?): GridInterval {
        connect().use { conn ->
            conn.prepareStatement(
                "INSERT INTO inverter_grid_intervals (state, start_ts, end_ts, start_soc) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, state.name)
                stmt.setLong(2, startTs)
                stmt.setLong(3, endTs)
                if (startSoc != null) stmt.setInt(4, startSoc) else stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
            val id = conn.createStatement().use { s ->
                s.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) }
            }
            log.debug("Inserted interval #{} {} [{}..{}]", id, state, startTs, endTs)
            return GridInterval(id, state, startTs, endTs, startSoc)
        }
    }

    fun updateEnd(id: Long, endTs: Long) {
        connect().use { conn ->
            conn.prepareStatement("UPDATE inverter_grid_intervals SET end_ts = ? WHERE id = ?").use { stmt ->
                stmt.setLong(1, endTs)
                stmt.setLong(2, id)
                stmt.executeUpdate()
            }
        }
    }

    fun findLast(): GridInterval? = connect().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM inverter_grid_intervals ORDER BY end_ts DESC, id DESC LIMIT 1").use { rs ->
                if (rs.next()) rs.toInterval() else null
            }
        }
    }

    /** Latest time the given state was observed, or null. */
    fun findLastEnd(state: GridState): Long? = connect().use { conn ->
        conn.prepareStatement("SELECT MAX(end_ts) FROM inverter_grid_intervals WHERE state = ?").use { stmt ->
            stmt.setString(1, state.name)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }
    }

    fun findFirstStart(): Long? = connect().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT MIN(start_ts) FROM inverter_grid_intervals").use { rs ->
                if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null
            }
        }
    }

    /** Intervals with end_ts >= fromTs and start_ts <= toTs, ordered by start. */
    fun findOverlapping(fromTs: Long, toTs: Long): List<GridInterval> = connect().use { conn ->
        conn.prepareStatement(
            "SELECT * FROM inverter_grid_intervals WHERE end_ts >= ? AND start_ts <= ? ORDER BY start_ts, id"
        ).use { stmt ->
            stmt.setLong(1, fromTs)
            stmt.setLong(2, toTs)
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toInterval()) }
            }
        }
    }

    private fun ResultSet.toInterval() = GridInterval(
        id = getLong("id"),
        state = GridState.valueOf(getString("state")),
        startTs = getLong("start_ts"),
        endTs = getLong("end_ts"),
        startSoc = getInt("start_soc").takeIf { !wasNull() },
    )
}
