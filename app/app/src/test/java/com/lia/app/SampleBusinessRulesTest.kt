package com.lia.app

import com.lia.app.data.garments.SampleGarmentRepository
import com.lia.app.data.model.SalesPeriod
import com.lia.app.data.sales.SampleSalesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleBusinessRulesTest {

    @Test
    fun activeCodeCannotBeDuplicated_butSoldOrArchivedCodeCanBeReused() {
        val repo = SampleGarmentRepository()
        val garments = repo.initialGarments()

        // A12 is IN_CATALOG -> cannot be duplicated (case-insensitive)
        assertTrue(repo.isActiveCodeTaken(garments, "a12"))
        // Editing A12 itself is allowed
        assertFalse(repo.isActiveCodeTaken(garments, "A12", excludingGarmentId = "g-1"))
        // A08 is SOLD and D02 is ARCHIVED -> both codes can be reused
        assertFalse(repo.isActiveCodeTaken(garments, "A08"))
        assertFalse(repo.isActiveCodeTaken(garments, "D02"))
    }

    @Test
    fun salesPeriodFilteringAndRevenueSumWorkCorrectly() {
        val repo = SampleSalesRepository()
        val todaySales = repo.filterByPeriod(repo.initialSales(), SalesPeriod.TODAY)
        assertEquals(2, todaySales.size)
        assertEquals(510.0, todaySales.sumOf { it.totalAmount }, 0.001)
    }
}
