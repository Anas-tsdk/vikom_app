package com.example.tv_caller_app.domain

import kotlinx.serialization.Serializable

@Serializable
data class PatientProfile(
    val fullName: String = "",
    val userName: String = "",
    val medications: List<PatientMedication> = emptyList(),
    val dailyTasks: String? = null
)

@Serializable
data class PatientMedication(
    val name: String = "",
    val dosage: String? = null,
    val schedule: String? = null
)

@Serializable
data class CareTeamMember(
    val fullName: String = "",
    val relationshipType: String = "",
    val role: String = "",
    val phoneNumber: String? = null
)
