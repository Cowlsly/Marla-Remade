package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ButtonGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.BlackAndWhiteAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdjustment
import com.vayunmathur.photos.data.ColorBalanceAdjustment
import com.vayunmathur.photos.data.FilterBlur
import com.vayunmathur.photos.data.FilterBlurMode
import com.vayunmathur.photos.data.LevelsAdjustment
import com.vayunmathur.photos.data.NoiseParams
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveColorRange
import com.vayunmathur.photos.data.StylizeMode
import com.vayunmathur.photos.data.StylizeParams
import com.vayunmathur.photos.data.UnsharpMask
import com.vayunmathur.photos.data.applyToBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.vayunmathur.photos.R

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(
            "${value.roundToInt()}",
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PanelColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun LevelsPanel(levels: LevelsAdjustment, onChange: (LevelsAdjustment) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.in_black), levels.inBlack, 0f..254f) { onChange(levels.copy(inBlack = it)) }
    LabeledSlider(stringResource(R.string.in_white), levels.inWhite, 1f..255f) { onChange(levels.copy(inWhite = it)) }
    LabeledSlider(stringResource(R.string.gamma), levels.gamma * 100f, 10f..300f) { onChange(levels.copy(gamma = it / 100f)) }
    LabeledSlider(stringResource(R.string.out_black), levels.outBlack, 0f..254f) { onChange(levels.copy(outBlack = it)) }
    LabeledSlider(stringResource(R.string.out_white), levels.outWhite, 1f..255f) { onChange(levels.copy(outWhite = it)) }
}

@Composable
fun ColorBalancePanel(balance: ColorBalanceAdjustment, onChange: (ColorBalanceAdjustment) -> Unit) = PanelColumn {
    var range by remember { mutableStateOf(0) } // 0 shadows, 1 mid, 2 high
    ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        listOf("Shadows", "Midtones", "Highlights").forEachIndexed { i, label ->
            toggleableItem(checked = range == i, label = label, onCheckedChange = { range = i })
        }
    }
    val rc = when (range) { 0 -> balance.shadowsRedCyan; 1 -> balance.midRedCyan; else -> balance.highRedCyan }
    val gm = when (range) { 0 -> balance.shadowsGreenMagenta; 1 -> balance.midGreenMagenta; else -> balance.highGreenMagenta }
    val by = when (range) { 0 -> balance.shadowsBlueYellow; 1 -> balance.midBlueYellow; else -> balance.highBlueYellow }
    LabeledSlider(stringResource(R.string.red_cyan), rc, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsRedCyan = it); 1 -> balance.copy(midRedCyan = it); else -> balance.copy(highRedCyan = it)
        })
    }
    LabeledSlider(stringResource(R.string.green_mag), gm, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsGreenMagenta = it); 1 -> balance.copy(midGreenMagenta = it); else -> balance.copy(highGreenMagenta = it)
        })
    }
    LabeledSlider(stringResource(R.string.blue_yellow), by, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsBlueYellow = it); 1 -> balance.copy(midBlueYellow = it); else -> balance.copy(highBlueYellow = it)
        })
    }
    SwitchRow("Preserve Luminosity", balance.preserveLuminosity) { onChange(balance.copy(preserveLuminosity = it)) }
}

@Composable
fun ChannelMixerPanel(mixer: ChannelMixerAdjustment, onChange: (ChannelMixerAdjustment) -> Unit) = PanelColumn {
    SwitchRow("Monochrome", mixer.monochrome) { onChange(mixer.copy(monochrome = it)) }
    LabeledSlider("R ← R", mixer.rFromR * 100f, -200f..200f) { onChange(mixer.copy(rFromR = it / 100f)) }
    LabeledSlider("R ← G", mixer.rFromG * 100f, -200f..200f) { onChange(mixer.copy(rFromG = it / 100f)) }
    LabeledSlider("R ← B", mixer.rFromB * 100f, -200f..200f) { onChange(mixer.copy(rFromB = it / 100f)) }
    LabeledSlider("G ← G", mixer.gFromG * 100f, -200f..200f) { onChange(mixer.copy(gFromG = it / 100f)) }
    LabeledSlider("B ← B", mixer.bFromB * 100f, -200f..200f) { onChange(mixer.copy(bFromB = it / 100f)) }
}

@Composable
fun BlackWhitePanel(bw: BlackAndWhiteAdjustment, onChange: (BlackAndWhiteAdjustment) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.reds), bw.reds, 0f..200f) { onChange(bw.copy(reds = it)) }
    LabeledSlider(stringResource(R.string.yellows), bw.yellows, 0f..200f) { onChange(bw.copy(yellows = it)) }
    LabeledSlider(stringResource(R.string.greens), bw.greens, 0f..200f) { onChange(bw.copy(greens = it)) }
    LabeledSlider(stringResource(R.string.cyans), bw.cyans, 0f..200f) { onChange(bw.copy(cyans = it)) }
    LabeledSlider(stringResource(R.string.blues), bw.blues, 0f..200f) { onChange(bw.copy(blues = it)) }
    LabeledSlider(stringResource(R.string.magentas), bw.magentas, 0f..200f) { onChange(bw.copy(magentas = it)) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ApplyButton(onApply: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
        Surface(
            modifier = Modifier.clickable { onApply() },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(stringResource(R.string.apply),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun GradientMapPanel(onSelect: (List<com.vayunmathur.photos.data.GradientStop>) -> Unit) = PanelColumn {
    val presets = listOf(
        "Grayscale" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF000000.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFFFF.toInt()),
        ),
        "Sepia" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF1B0E00.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFF8A5A2B.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFE9C9.toInt()),
        ),
        "Cool" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF001133.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFF00AACC.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFFFF.toInt()),
        ),
        "Warm" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF330000.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFFCC5500.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFF66.toInt()),
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (label, stops) ->
            Surface(
                modifier = Modifier.clickable { onSelect(stops) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
    }
}

/**
 * Destructive filters applied to the active pixel layer. Builds the chosen transform and
 * hands it to [onApply], which runs it on a background thread in the view model.
 */
@Composable
fun FiltersPanel(onAddFx: (com.vayunmathur.photos.data.LayerAdjustment) -> Unit) = PanelColumn {
    var tool by remember { mutableStateOf("Sharpen") }
    var amount by remember { mutableFloatStateOf(50f) }
    var radius by remember { mutableFloatStateOf(2f) }
    var angle by remember { mutableFloatStateOf(0f) }
    val tools = listOf("Sharpen", "Gaussian", "Motion", "Radial", "Spin", "Noise", "Find Edges", "Emboss")
    ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        tools.forEach { t -> toggleableItem(checked = tool == t, label = t, onCheckedChange = { tool = t }) }
    }
    when (tool) {
        "Sharpen" -> {
            LabeledSlider(stringResource(R.string.amount), amount, 0f..100f) { amount = it }
            LabeledSlider(stringResource(R.string.radius), radius, 0.5f..10f) { radius = it }
        }
        "Motion" -> {
            LabeledSlider(stringResource(R.string.length), amount, 1f..100f) { amount = it }
            LabeledSlider(stringResource(R.string.angle), angle, 0f..360f) { angle = it }
        }
        "Noise" -> LabeledSlider(stringResource(R.string.amount), amount, 0f..100f) { amount = it }
        else -> LabeledSlider(stringResource(R.string.amount), amount, 1f..100f) { amount = it }
    }
    ApplyButton {
        val a = amount; val r = radius; val ang = angle
        when (tool) {
            "Sharpen" -> onAddFx(com.vayunmathur.photos.data.UnsharpAdj(UnsharpMask(amount = a, radius = r)))
            "Gaussian" -> onAddFx(com.vayunmathur.photos.data.FilterBlurAdj(FilterBlur(FilterBlurMode.Gaussian, amount = a)))
            "Motion" -> onAddFx(com.vayunmathur.photos.data.FilterBlurAdj(FilterBlur(FilterBlurMode.Motion, amount = a, angle = ang)))
            "Radial" -> onAddFx(com.vayunmathur.photos.data.FilterBlurAdj(FilterBlur(FilterBlurMode.Radial, amount = a)))
            "Spin" -> onAddFx(com.vayunmathur.photos.data.FilterBlurAdj(FilterBlur(FilterBlurMode.Spin, amount = a)))
            "Noise" -> onAddFx(com.vayunmathur.photos.data.NoiseAdj(NoiseParams(amount = a)))
            "Find Edges" -> onAddFx(com.vayunmathur.photos.data.StylizeAdj(StylizeParams(StylizeMode.FindEdges)))
            "Emboss" -> onAddFx(com.vayunmathur.photos.data.StylizeAdj(StylizeParams(StylizeMode.Emboss)))
        }
    }
}

@Composable
fun VibrancePanel(amount: Float, onChange: (Float) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.vibrance), amount, -100f..100f, onChange)
}

@Composable
fun PosterizePanel(levels: Int, onChange: (Int) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.levels), levels.toFloat(), 2f..64f) { onChange(it.roundToInt()) }
}

@Composable
fun ThresholdPanel(level: Int, onChange: (Int) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.level), level.toFloat(), 0f..255f) { onChange(it.roundToInt()) }
}

@Composable
fun InvertPanel() = PanelColumn {
    Text(stringResource(R.string.colors_inverted_toggle_the_layer_s_visib),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PhotoFilterPanel(adj: PhotoFilterAdj, onChange: (PhotoFilterAdj) -> Unit) = PanelColumn {
    val presets = listOf(
        "Warm" to 0xFFEC8A00.toInt(),
        "Cool" to 0xFF00B4EC.toInt(),
        "Sepia" to 0xFF9C6B30.toInt(),
        "Red" to 0xFFEA3323.toInt(),
        "Green" to 0xFF00A651.toInt(),
        "Blue" to 0xFF2E5CFF.toInt(),
    )
    ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        presets.forEach { (name, color) ->
            toggleableItem(
                checked = adj.color == color,
                label = name,
                onCheckedChange = { onChange(adj.copy(color = color)) },
            )
        }
    }
    LabeledSlider(stringResource(R.string.density), adj.density * 100f, 0f..100f) { onChange(adj.copy(density = it / 100f)) }
}

@Composable
fun SelectiveColorPanel(adj: SelectiveColorAdj, onChange: (SelectiveColorAdj) -> Unit) = PanelColumn {
    ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        SelectiveColorRange.entries.forEach { range ->
            toggleableItem(
                checked = adj.range == range,
                label = range.name,
                onCheckedChange = { onChange(adj.copy(range = range)) },
            )
        }
    }
    LabeledSlider(stringResource(R.string.cyan), adj.cyan, -100f..100f) { onChange(adj.copy(cyan = it)) }
    LabeledSlider(stringResource(R.string.magenta), adj.magenta, -100f..100f) { onChange(adj.copy(magenta = it)) }
    LabeledSlider(stringResource(R.string.yellow), adj.yellow, -100f..100f) { onChange(adj.copy(yellow = it)) }
}
