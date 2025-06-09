package dev.buzzverse.buzzapp.ui.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.buzzverse.buzzapp.model.SensorSample
import ir.ehsannarmani.compose_charts.LineChart
import ir.ehsannarmani.compose_charts.models.Line

@Composable
fun SensorChart(samples: List<SensorSample>, modifier: Modifier = Modifier) {
    val temps      = samples.map { it.temperature }
    val pressures  = samples.map { it.pressure }
    val humidities = samples.map { it.humidity }

    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        LineChart(
            modifier = modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp),

            data = remember(temps.size) {
                listOf(
                    Line(
                        label = "Temp °C",
                        values = temps.map { it?.toDouble() ?: Double.NaN },
                        color = SolidColor(Color(0xFFE57373)),
                        firstGradientFillColor = Color(0x66E57373),
                        secondGradientFillColor = Color.Transparent
                    )
                )
            }
        )
    }

    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        LineChart(
            modifier = modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp),

            data = remember(humidities.size) {
                listOf(
                    Line(
                        label = "Hum %",
                        values = humidities.map { it?.toDouble() ?: Double.NaN },
                        color = SolidColor(Color(0xFF64B5F6)),
                        firstGradientFillColor = Color(0x6664B5F6),
                        secondGradientFillColor = Color.Transparent
                    )
                )
            }
        )
    }

    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        LineChart(
            modifier = modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp),

            data = remember(pressures.size) {
                listOf(
                    Line(
                        label = "Press hPa",
                        values = pressures.map { it?.toDouble() ?: Double.NaN },
                        color = SolidColor(Color(0xFF81C784)),
                        firstGradientFillColor = Color(0x6681C784),
                        secondGradientFillColor = Color.Transparent,
                        curvedEdges = false
                    )
                )
            }
        )
    }
}