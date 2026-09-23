package com.example.karmcredapp

/**
 * Display model for a single reason/suggestion card on the dashboard.
 * (The API's richer models - ReasonItem / Counterfactual - live in
 * ApiService.kt; MainActivity maps them into this simple pair.)
 */
data class ReasonCard(
    val reason: String,
    val impact: String
)
