package com.spacedrift.fieldproof.ui.detail

import com.spacedrift.fieldproof.data.local.InspectionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail screen must never flash a *not found* state while the first
 * database emission is still pending. [asDetailState] only maps a genuinely
 * null row to [InspectionDetailState.NotFound]; an emitted row maps to
 * [InspectionDetailState.Loaded]. The caller supplies [InspectionDetailState.Loading]
 * as the initial value until the first emission arrives.
 */
class InspectionDetailStateTest {

    private val inspection = InspectionEntity(
        id = "i1",
        title = "Facility A",
        location = "Building 1",
        status = "DRAFT",
        createdAt = 1_000L,
        updatedAt = 2_000L
    )

    @Test
    fun emittedRow_mapsToLoadedState() = runBlocking {
        val state = flowOf(inspection).asDetailState().first()

        assertTrue(state is InspectionDetailState.Loaded)
        assertEquals(inspection, (state as InspectionDetailState.Loaded).inspection)
    }

    @Test
    fun nullRow_mapsToNotFoundState() = runBlocking {
        val state = flowOf(null).asDetailState().first()

        assertEquals(InspectionDetailState.NotFound, state)
    }
}
