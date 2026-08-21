package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ButtonGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.MaskType
import com.vayunmathur.photos.data.SelectiveMask
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.vayunmathur.photos.R

@Composable
fun SelectiveEditPanel(
    mask: SelectiveMask,
    showMask: Boolean,
    onMaskChanged: (SelectiveMask) -> Unit,
    onShowMaskChanged: (Boolean) -> Unit,
    onAddMask: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        ButtonGroup(Modifier.fillMaxWidth()) {
            MaskType.entries.forEach { type ->
                toggleableItem(
                    checked = mask.type == type,
                    label = type.name,
                    onCheckedChange = { onMaskChanged(mask.copy(type = type)) },
                )
            }
        }

        if (mask.type == MaskType.Brush) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.brush_size), fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = mask.brushSize * 100f,
                    onValueChange = { onMaskChanged(mask.copy(brushSize = it / 100f)) },
                    valueRange = 1f..20f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(mask.brushSize * 100f).roundToInt()}",
                    fontSize = 12.sp,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.show_mask), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = showMask, onCheckedChange = onShowMaskChanged)
        }

        SelectiveAdjustmentSlider("Brightness", mask.adjustments.brightness, -100f, 100f) {
            onMaskChanged(mask.copy(adjustments = mask.adjustments.copy(brightness = it)))
        }
        SelectiveAdjustmentSlider("Contrast", mask.adjustments.contrast, -100f, 100f) {
            onMaskChanged(mask.copy(adjustments = mask.adjustments.copy(contrast = it)))
        }
        SelectiveAdjustmentSlider("Saturation", mask.adjustments.saturation, -100f, 100f) {
            onMaskChanged(mask.copy(adjustments = mask.adjustments.copy(saturation = it)))
        }
        SelectiveAdjustmentSlider("Exposure", mask.adjustments.exposure, -100f, 100f) {
            onMaskChanged(mask.copy(adjustments = mask.adjustments.copy(exposure = it)))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.clickable { onAddMask() }.padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(stringResource(R.string.add_mask),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SelectiveAdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${value.roundToInt()}",
            fontSize = 12.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
