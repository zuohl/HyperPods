package io.github.zuohl.hyperpods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.zuohl.hyperpods.R
import io.github.zuohl.hyperpods.config.ConfigManager
import io.github.zuohl.hyperpods.pods.NoiseControlMode
import io.github.zuohl.hyperpods.pods.QcyOfficialEqCurves
import io.github.zuohl.hyperpods.pods.WearStatus
import io.github.zuohl.hyperpods.ui.components.AncSwitch
import io.github.zuohl.hyperpods.ui.components.PodStatus
import io.github.zuohl.hyperpods.utils.miuiStrongToast.data.BatteryParams
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import io.github.zuohl.hyperpods.pods.QcyEqPreset
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    dualDeviceConnection: Boolean = false,
    onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    ldac: Boolean = false,
    onLdacChange: (Boolean) -> Unit = {},
    dynamicEq: Boolean = false,
    onDynamicEqChange: (Boolean) -> Unit = {},
    sleepMode: Boolean = false,
    onSleepModeChange: (Boolean) -> Unit = {},
    adaptiveVolume: Boolean = false,
    onAdaptiveVolumeChange: (Boolean) -> Unit = {},
    spatialAudioSupported: Boolean = false,
    spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    eqPreset: Int = -1,
    onEqPresetChange: (Int) -> Unit = {},
    customEqGains: List<Int> = List(10) { 0 },
    onCustomEqGainsChange: (List<Int>) -> Unit = {},
    boxImagePath: String? = null,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath),
                    contentDescription = "Earphones",
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    batteryParams = batteryParams,
                    wearStatus = wearStatus,
                    ancMode = ancMode,
                    onAncModeChange = onAncModeChange,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    gameMode = gameMode,
                    onGameModeChange = onGameModeChange,
                    spatialAudioMode = spatialAudioMode,
                    onSpatialAudioModeChange = onSpatialAudioModeChange,
                    dualDeviceConnection = dualDeviceConnection,
                    onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                    ldac = ldac,
                    onLdacChange = onLdacChange,
                    dynamicEq = dynamicEq,
                    onDynamicEqChange = onDynamicEqChange,
                    sleepMode = sleepMode,
                    onSleepModeChange = onSleepModeChange,
                    adaptiveVolume = adaptiveVolume,
                    onAdaptiveVolumeChange = onAdaptiveVolumeChange,
                    spatialAudioSupported = spatialAudioSupported,
                    spatialSoundSupported = spatialSoundSupported,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    eqPreset = eqPreset,
                    onEqPresetChange = onEqPresetChange,
                    customEqGains = customEqGains,
                    onCustomEqGainsChange = onCustomEqGainsChange,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "hero") {
            Image(
                painter = rememberPodImagePainter(boxImagePath),
                contentDescription = "Earphones",
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        podControlItems(
            batteryParams = batteryParams,
            wearStatus = wearStatus,
            ancMode = ancMode,
            onAncModeChange = onAncModeChange,
            transparencyVocalEnhancement = transparencyVocalEnhancement,
            onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
            gameMode = gameMode,
            onGameModeChange = onGameModeChange,
            spatialAudioMode = spatialAudioMode,
            onSpatialAudioModeChange = onSpatialAudioModeChange,
            dualDeviceConnection = dualDeviceConnection,
            onDualDeviceConnectionChange = onDualDeviceConnectionChange,
            ldac = ldac,
            onLdacChange = onLdacChange,
            dynamicEq = dynamicEq,
            onDynamicEqChange = onDynamicEqChange,
            sleepMode = sleepMode,
            onSleepModeChange = onSleepModeChange,
            adaptiveVolume = adaptiveVolume,
            onAdaptiveVolumeChange = onAdaptiveVolumeChange,
            spatialAudioSupported = spatialAudioSupported,
            spatialSoundSupported = spatialSoundSupported,
            adaptiveModeEnabled = adaptiveModeEnabled,
            eqPreset = eqPreset,
            onEqPresetChange = onEqPresetChange,
            customEqGains = customEqGains,
            onCustomEqGainsChange = onCustomEqGainsChange,
            bottomContentPadding = bottomContentPadding
        )
    }
}

@Composable
private fun rememberPodImagePainter(path: String?): Painter {
    val fallback = painterResource(R.drawable.img_box)
    val customPainter by produceState<BitmapPainter?>(initialValue = null, path) {
        value = null
        val imagePath = path ?: return@produceState
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(imagePath, 1024)
        }?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
    return customPainter ?: fallback
}

private fun decodeSampledBitmap(path: String, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while ((bounds.outWidth / sampleSize) > maxSide || (bounds.outHeight / sampleSize) > maxSide) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

private fun LazyListScope.podControlItems(
    batteryParams: BatteryParams,
    wearStatus: WearStatus,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    transparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    gameMode: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    dualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    ldac: Boolean,
    onLdacChange: (Boolean) -> Unit,
    dynamicEq: Boolean,
    onDynamicEqChange: (Boolean) -> Unit,
    sleepMode: Boolean,
    onSleepModeChange: (Boolean) -> Unit,
    adaptiveVolume: Boolean,
    onAdaptiveVolumeChange: (Boolean) -> Unit,
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    eqPreset: Int,
    onEqPresetChange: (Int) -> Unit,
    customEqGains: List<Int>,
    onCustomEqGainsChange: (List<Int>) -> Unit,
    bottomContentPadding: Dp
) {
    val spatialAudioValues = listOf(
        ConfigManager.SPATIAL_AUDIO_OFF,
        ConfigManager.SPATIAL_AUDIO_FIXED,
        ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING,
    )

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = batteryParams,
                wearStatus = wearStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            AncSwitch(
                ancStatus = ancMode,
                onAncModeChange = onAncModeChange,
                adaptiveModeEnabled = adaptiveModeEnabled,
                transparencyVocalEnhancement = transparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
            )
        }
    }

    item {
        var customEqExpanded by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.game_mode),
                summary = stringResource(R.string.game_mode_summary),
                checked = gameMode,
                onCheckedChange = onGameModeChange
            )
            if (spatialAudioSupported) {
                val spatialAudioOptions = listOf(
                    stringResource(R.string.off),
                    stringResource(R.string.spatial_audio_fixed),
                    stringResource(R.string.spatial_audio_head_tracking),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.spatial_audio),
                    summary = stringResource(R.string.spatial_audio_summary),
                    items = spatialAudioOptions,
                    selectedIndex = spatialAudioValues.indexOf(spatialAudioMode).coerceAtLeast(0),
                    onSelectedIndexChange = { onSpatialAudioModeChange(spatialAudioValues[it]) }
                )
            }
            if (spatialSoundSupported) {
                SwitchPreference(
                    title = stringResource(R.string.spatial_sound),
                    summary = stringResource(if (spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF) R.string.enabled else R.string.off),
                    checked = spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF,
                    onCheckedChange = {
                        onSpatialAudioModeChange(if (it) ConfigManager.SPATIAL_AUDIO_FIXED else ConfigManager.SPATIAL_AUDIO_OFF)
                    }
                )
            }
            val eqOptions = listOf(
                stringResource(R.string.eq_preset_spatial),
                stringResource(R.string.eq_preset_default),
                stringResource(R.string.eq_preset_pop),
                stringResource(R.string.eq_preset_bass),
                stringResource(R.string.eq_preset_rock),
                stringResource(R.string.eq_preset_soft),
                stringResource(R.string.eq_preset_classical),
                stringResource(R.string.eq_preset_custom),
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.eq_preset_title),
                summary = stringResource(R.string.eq_preset_summary),
                items = eqOptions,
                selectedIndex = QcyEqPreset.ALL.indexOf(eqPreset).coerceAtLeast(0),
                onSelectedIndexChange = {
                    val preset = QcyEqPreset.ALL[it]
                    customEqExpanded = preset == QcyEqPreset.CUSTOM
                    onEqPresetChange(preset)
                }
            )
            if (eqPreset == QcyEqPreset.CUSTOM && customEqExpanded) {
                CustomEqPanel(
                    gains = customEqGains,
                    onSave = {
                        onCustomEqGainsChange(it)
                        customEqExpanded = false
                    },
                )
            }
            SwitchPreference(
                title = stringResource(R.string.ldac),
                summary = stringResource(R.string.ldac_summary),
                checked = ldac,
                onCheckedChange = onLdacChange
            )
            SwitchPreference(
                title = stringResource(R.string.dynamic_eq),
                summary = stringResource(if (dynamicEq) R.string.enabled else R.string.off),
                checked = dynamicEq,
                onCheckedChange = onDynamicEqChange
            )
            SwitchPreference(
                title = stringResource(R.string.sleep_mode),
                summary = stringResource(R.string.sleep_mode_summary),
                checked = sleepMode,
                onCheckedChange = onSleepModeChange
            )
            SwitchPreference(
                title = stringResource(R.string.adaptive_volume),
                summary = stringResource(if (adaptiveVolume) R.string.enabled else R.string.off),
                checked = adaptiveVolume,
                onCheckedChange = onAdaptiveVolumeChange
            )
            SwitchPreference(
                title = stringResource(R.string.dual_device_connection),
                summary = stringResource(if (dualDeviceConnection) R.string.enabled else R.string.off),
                checked = dualDeviceConnection,
                onCheckedChange = onDualDeviceConnectionChange
            )
        }
    }
    item {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

@Composable
private fun CustomEqPanel(
    gains: List<Int>,
    onSave: (List<Int>) -> Unit,
) {
    var editedGains by remember(gains) {
        mutableStateOf(normalizeCustomEqGains(gains))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_eq_title),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.headline1,
                )
                Text(
                    text = stringResource(R.string.custom_eq_summary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        QcyOfficialEqCurves.customFrequencies.forEachIndexed { index, frequency ->
            EqBandControl(
                label = formatEqFrequency(frequency),
                gain = editedGains[index],
                onGainChange = { gain ->
                    editedGains = editedGains.toMutableList().also { it[index] = gain }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.custom_eq_reset),
                onClick = { editedGains = List(QcyOfficialEqCurves.customFrequencies.size) { 0 } },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.custom_eq_save),
                onClick = { onSave(editedGains) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun EqBandControl(
    label: String,
    gain: Int,
    onGainChange: (Int) -> Unit,
) {
    var trackWidth by remember { mutableStateOf(1) }
    val primary = MiuixTheme.colorScheme.primary
    val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.24f)
    val centerColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)

    fun gainFromX(x: Float): Int {
        val fraction = (x / trackWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        return (-8 + fraction * 16).roundToInt().coerceIn(-8, 8)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.width(44.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .onSizeChanged { trackWidth = it.width }
                .pointerInput(trackWidth) {
                    detectTapGestures { offset ->
                        onGainChange(gainFromX(offset.x))
                    }
                }
                .pointerInput(trackWidth) {
                    detectDragGestures(
                        onDragStart = { offset -> onGainChange(gainFromX(offset.x)) },
                        onDrag = { change, _ ->
                            onGainChange(gainFromX(change.position.x))
                            change.consume()
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackHeight = 6.dp.toPx()
                val centerY = size.height / 2f
                val centerX = size.width / 2f
                val thumbX = size.width * ((gain + 8) / 16f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                )
                drawRoundRect(
                    color = centerColor,
                    topLeft = Offset(minOf(centerX, thumbX), centerY - trackHeight / 2f),
                    size = Size(kotlin.math.abs(thumbX - centerX), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
                )
                drawCircle(
                    color = primary,
                    radius = 8.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${gain}dB",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.width(46.dp),
        )
    }
}

private fun normalizeCustomEqGains(gains: List<Int>): List<Int> =
    List(QcyOfficialEqCurves.customFrequencies.size) { index ->
        gains.getOrNull(index)?.coerceIn(-8, 8) ?: 0
    }

private fun formatEqFrequency(frequency: Int): String =
    if (frequency >= 1000) "${frequency / 1000}k" else frequency.toString()
