package com.mthkr.db

import com.mthkr.inverter.InverterSnapshot
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * One history point recorded while running on battery. Timestamp is epoch ms.
 * `loadW` is stored raw (may be 65535 = invalid) — use [validLoadW]; forecasts use [dischargeW].
 */
data class BatterySample(
    val ts: Long,
    val soc: Int,
    val loadW: Int,
    val pvW: Int,
    val batteryV: Double,
    val batteryA: Double,
) {
    val validLoadW: Int? get() = loadW.takeIf { it <= InverterSnapshot.MAX_VALID_POWER_W }

    val isDischarging: Boolean get() = batteryA < 0

    /** DC power drawn from the battery, W; 0 when not discharging. */
    val dischargeW: Double get() = com.mthkr.inverter.dischargeW(batteryV, batteryA)
}

/** Battery-mode history used by the Smart forecast. */
class BatterySampleRepository(dbPath: String) {
    private val log = LoggerFactory.getLogger(BatterySampleRepository::class.java)
    private val url = "jdbc:sqlite:$dbPath"

    init {
        File(dbPath).parentFile?.mkdirs()
        connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS battery_samples (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts        INTEGER NOT NULL,
                        soc       INTEGER NOT NULL,
                        load_w    INTEGER NOT NULL,
                        pv_w      INTEGER NOT NULL,
                        battery_v REAL    NOT NULL,
                        battery_a REAL    NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_battery_samples_ts ON battery_samples(ts)")
            }
        }
        log.info("BatterySampleRepository initialized at {}", dbPath)
    }

    private fun connect(): Connection = DriverManager.getConnection(url)

    fun insert(s: BatterySample) {
        connect().use { conn ->
            conn.prepareStatement(
                "INSERT INTO battery_samples (ts, soc, load_w, pv_w, battery_v, battery_a) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setLong(1, s.ts)
                stmt.setInt(2, s.soc)
                stmt.setInt(3, s.loadW)
                stmt.setInt(4, s.pvW)
                stmt.setDouble(5, s.batteryV)
                stmt.setDouble(6, s.batteryA)
                stmt.executeUpdate()
            }
        }
    }

    /** Samples with ts >= fromTs, ordered by time. */
    fun findSince(fromTs: Long): List<BatterySample> = connect().use { conn ->
        conn.prepareStatement("SELECT * FROM battery_samples WHERE ts >= ? ORDER BY ts, id").use { stmt ->
            stmt.setLong(1, fromTs)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        BatterySample(
                            ts = rs.getLong("ts"),
                            soc = rs.getInt("soc"),
                            loadW = rs.getInt("load_w"),
                            pvW = rs.getInt("pv_w"),
                            batteryV = rs.getDouble("battery_v"),
                            batteryA = rs.getDouble("battery_a"),
                        )
                    )
                }
            }
        }
    }

    /** Deletes samples older than [ts]; returns the number of deleted rows. */
    fun deleteOlderThan(ts: Long): Int = connect().use { conn ->
        conn.prepareStatement("DELETE FROM battery_samples WHERE ts < ?").use { stmt ->
            stmt.setLong(1, ts)
            stmt.executeUpdate()
        }
    }
}
