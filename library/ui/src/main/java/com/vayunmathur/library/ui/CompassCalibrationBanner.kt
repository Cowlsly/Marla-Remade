package com.vayunmathur.library.ui

import android.hardware.SensorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R

/**
 * Banner shown when the magnetometer reports low accuracy. Renders nothing once the
 * sensor reaches high accuracy, so callers can pass the raw accuracy through without
 * guarding the call site.
 */
@Composable
fun CompassCalibrationBanner(accuracy: Int, modifier: Modifier = Modifier) {
    if (accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCompass(modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.compass_low_accuracy_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.compass_calibrate_figure_8),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
