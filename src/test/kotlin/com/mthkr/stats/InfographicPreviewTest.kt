package com.mthkr.stats

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test

/**
 * On-demand visual preview — excluded from `./gradlew test`.
 * Run: `./gradlew previewInfographics`, then `open build/infographics`.
 */
@Tag("infographic-preview")
class InfographicPreviewTest {
    private val outDir = File(System.getProperty("infographic.outDir") ?: "build/infographics").apply { mkdirs() }

    private fun save(name: String, report: StatsReport) {
        val file = File(outDir, "$name.png")
        file.writeBytes(InfographicRenderer.render(report))
        println("Infographic preview: ${file.absolutePath}")
    }

    @Test
    fun today() = save("today", InfographicSamples.todayReport())

    @Test
    fun week() = save("week", InfographicSamples.weekReport())

    @Test
    fun month() = save("month", InfographicSamples.monthReport())

    @Test
    fun empty() = StatsPeriod.entries.forEach { save("empty-${it.name.lowercase()}", InfographicSamples.emptyReport(it)) }
}
