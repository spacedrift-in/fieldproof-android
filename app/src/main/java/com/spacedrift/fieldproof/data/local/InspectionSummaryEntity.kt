package com.spacedrift.fieldproof.data.local

// Result of the home-list aggregate query (not a stored entity).
data class InspectionSummaryEntity(
    val id: String,
    val title: String,
    val location: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val totalItems: Int,
    val completedItems: Int,
    val evidenceCount: Int
)