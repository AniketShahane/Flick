package com.flick.receiver.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packer exists so the DECODER cell reaches a full-width row: at a 144.7 dp
 * column the 16 sp mono value ellipsised `c2.mtk.dvhe.sth.decoder` down to its
 * vendor prefix, which is the half that identifies nothing.
 */
class StatsGridPackingTest {

    private fun cell(label: String, span: Int = 1) = StatCell(label, "—", Color.White, span)

    /** The nine cells the panel actually ships, DECODER last and full width. */
    private fun shippedCells() = (1..8).map { cell("$it") } + cell("DECODER", span = 3)

    @Test fun theDecoderCellGetsAWholeRowRatherThanAThirdOfOne() {
        val rows = packStatRows(shippedCells())
        assertEquals(listOf(3, 3, 2, 1), rows.map { it.size })
        assertEquals(listOf("DECODER"), rows.last().map { it.label })
        assertEquals(3, rows.last().single().span)
    }

    @Test fun noRowEverOverflowsTheGrid() {
        packStatRows(shippedCells()).forEach { row ->
            assertTrue(row.sumOf { it.span } <= 3)
        }
    }

    @Test fun everyCellSurvivesThePackInReadingOrder() {
        val cells = shippedCells()
        assertEquals(cells, packStatRows(cells).flatten())
    }

    @Test fun aCellBreaksTheRowOnlyWhenItCannotShareIt() {
        // Exactly filling the row is not a break — that is the ordinary last pair.
        assertEquals(
            listOf(listOf("A", "WIDE")),
            packStatRows(listOf(cell("A"), cell("WIDE", span = 2))).map { row -> row.map { it.label } },
        )
        assertEquals(
            listOf(listOf("A"), listOf("WIDE")),
            packStatRows(listOf(cell("A"), cell("WIDE", span = 3))).map { row -> row.map { it.label } },
        )
    }

    @Test fun aGridOfPlainCellsPacksExactlyAsChunkingDid() {
        val cells = (1..9).map { cell("$it") }
        assertEquals(cells.chunked(3), packStatRows(cells))
    }
}
