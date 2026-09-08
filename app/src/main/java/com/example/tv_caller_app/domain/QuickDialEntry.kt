package com.example.tv_caller_app.domain

data class QuickDialEntry(
    val contact: ContactInfo,
    val position: Int,
    val score: Double = 0.0,
    val callCount: Int = 0
)
