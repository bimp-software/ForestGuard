package com.forestguard.monitor.model

enum class ForestState(val firestoreValue: String, val displayName: String) {
    NORMAL(
        "NORMAL",
        "NORMAL"
    ),
    PREALERTA(
        "PREALERTA",
        "PREALERTA"
    ),
    ALERTA_MAXIMA(
        "ALERTA_MAXIMA",
        "ALERTA MÁXIMA"
    )
}