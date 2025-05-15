package dev.buzzverse.buzzapp.service

import android.content.Context
import android.content.Intent
import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpsServiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

    private val running = AtomicBoolean(false)

    fun updateLastKnownLocation(location: Location?) {
        location?.let { _lastKnownLocation.value = it }
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            val intent = Intent(context, GpsTrackerService::class.java)
            context.startForegroundService(intent)
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            val service = Intent(context, GpsTrackerService::class.java)
            context.stopService(service)
        }
    }
}
