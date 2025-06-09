package dev.buzzverse.buzzapp.model

data class SensorSample(
    val timeMillis: Long,
    val temperature: Float?,
    val pressure: Float?,
    val humidity: Float?
)
