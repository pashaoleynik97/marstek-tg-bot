package com.mthkr.stats

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InfographicRendererTest {
    private fun assertSquarePng(bytes: ByteArray) {
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "not a decodable image")
        assertEquals(InfographicRenderer.SIZE, image.width)
        assertEquals(InfographicRenderer.SIZE, image.height)
    }

    @Test
    fun `renders square png for every period`() {
        assertSquarePng(InfographicRenderer.render(InfographicSamples.todayReport()))
        assertSquarePng(InfographicRenderer.render(InfographicSamples.weekReport()))
        assertSquarePng(InfographicRenderer.render(InfographicSamples.monthReport()))
    }

    @Test
    fun `renders without data`() {
        StatsPeriod.entries.forEach { assertSquarePng(InfographicRenderer.render(InfographicSamples.emptyReport(it))) }
    }
}
