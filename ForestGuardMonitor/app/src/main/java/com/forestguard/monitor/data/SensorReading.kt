package com.forestguard.monitor.data
import com.google.firebase.Timestamp

data class SensorReading(

    val id: String,

    val temperatura: Double,

    val humedad: Double,

    val humo: Long,

    val estado: String,

    val timestamp: Timestamp?,

    val nodo: String
)