package com.example.signage_front.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.signage_front.data.LanguageManager
import com.example.signage_front.network.WeatherManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val CardBg = Color(0xFFFFFFFF)
private val TextDark = Color(0xFF1F2A37)
private val TextMuted = Color(0xFF6B7280)
private val TextOnGlass = Color(0xFFFFFFFF)
private val TextOnGlassMuted = Color(0xCCFFFFFF)

/**
 * Full-screen weather reader optimised for landscape kiosk displays:
 * a two-pane layout (current conditions + metrics and the hourly strip on the
 * left, the 7-day forecast on the right) over a condition-driven gradient.
 * Falls back to a stacked, scrollable layout on narrow/portrait screens.
 */
@Composable
fun WeatherScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language by LanguageManager.language.collectAsState()

    var bundle by remember { mutableStateOf(WeatherManager.loadCached(context)) }
    var loading by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(!WeatherManager.hasLocationPermission(context)) }

    fun load() {
        scope.launch {
            loading = true
            val fresh = WeatherManager.refresh(context)
            if (fresh != null) bundle = fresh
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it } || WeatherManager.hasLocationPermission(context)
        permissionDenied = !granted
        if (granted) load()
    }

    LaunchedEffect(Unit) {
        if (WeatherManager.hasLocationPermission(context)) {
            load()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    val background = remember(bundle?.current?.skyIcon) {
        weatherBackground(bundle?.current?.skyIcon ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(background))
    ) {
        WeatherTopBar(
            bundle = bundle,
            language = language,
            loading = loading,
            onBack = onBack,
            onRefresh = { load() }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                bundle != null -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Tablet landscape (e.g. 1280x800 @ 1.5x == ~853x533 dp) uses the
                    // two-pane layout; narrow/portrait windows fall back to stacked.
                    val isWide = maxWidth >= 720.dp && maxWidth > maxHeight
                    if (isWide) {
                        LandscapeContent(bundle!!, language)
                    } else {
                        PortraitContent(bundle!!, language)
                    }
                }
                loading -> LoadingState()
                permissionDenied -> MessageState(
                    if (language == LanguageManager.EN)
                        "Location permission is required to show local weather."
                    else
                        "A helyi időjárás megjelenítéséhez helymeghatározási engedély szükséges."
                )
                else -> MessageState(
                    if (language == LanguageManager.EN)
                        "Weather is unavailable. Check the connection and refresh."
                    else
                        "Az időjárás jelenleg nem érhető el. Ellenőrizze a kapcsolatot."
                )
            }
        }
    }
}

@Composable
private fun WeatherTopBar(
    bundle: WeatherManager.WeatherBundle?,
    language: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextOnGlass)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = bundle?.cityName?.takeIf { it.isNotBlank() }
                    ?: if (language == LanguageManager.EN) "Weather" else "Időjárás",
                color = TextOnGlass,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildList {
                bundle?.regionTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                bundle?.let { add(updatedLabel(it, language)) }
                if (bundle?.stale == true) add(if (language == LanguageManager.EN) "offline" else "offline")
            }.joinToString("  •  ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = TextOnGlassMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (loading) {
            CircularProgressIndicator(color = TextOnGlass, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextOnGlass)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Landscape: left pane = current + hourly, right pane = 7-day
// ---------------------------------------------------------------------------

@Composable
private fun LandscapeContent(bundle: WeatherManager.WeatherBundle, language: String) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1.55f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CurrentHero(
                bundle = bundle,
                language = language,
                modifier = Modifier.weight(1f)
            )
            HourlyStrip(
                bundle = bundle,
                language = language,
                modifier = Modifier.height(138.dp)
            )
        }
        WeeklyPanel(
            bundle = bundle,
            language = language,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun CurrentHero(
    bundle: WeatherManager.WeatherBundle,
    language: String,
    modifier: Modifier = Modifier
) {
    val current = bundle.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(CardBg)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Big temperature + condition
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = weatherEmoji(current?.skyIcon ?: ""), fontSize = 50.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTemp(current?.temperature),
                    color = TextDark,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 68.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                text = weatherLabel(current?.skyIcon ?: "", language),
                color = TextMuted,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            current?.frontalSystem?.let {
                Text(
                    text = it,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // Metric grid 2 x 3
        Column(
            modifier = Modifier.weight(1.15f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(Icons.Filled.Thermostat, formatTemp(current?.windChill), if (language == LanguageManager.EN) "Feels like" else "Hőérzet", Modifier.weight(1f))
                Metric(Icons.Filled.Air, current?.let { "${it.windStrength.toInt()} ${it.windDirection}" } ?: "—", if (language == LanguageManager.EN) "Wind" else "Szél", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(Icons.Filled.WaterDrop, current?.let { "${it.humidity}%" } ?: "—", if (language == LanguageManager.EN) "Humidity" else "Pára", Modifier.weight(1f))
                Metric(Icons.Filled.WbSunny, current?.uvIndex?.toString() ?: "—", current?.uvTitle ?: "UV", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(Icons.Filled.Compress, current?.let { "${it.pressure}" } ?: "—", "hPa", Modifier.weight(1f))
                Metric(Icons.Filled.WaterDrop, current?.let { "${formatNum(it.rain)} mm" } ?: "—", if (language == LanguageManager.EN) "Rain" else "Csapadék", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3F5F8))
            .padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF4A90D9), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = value,
                color = TextDark,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = label,
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class HourEntry(val dayOffset: Int, val hour: Int, val temp: Double, val rain: Double, val icon: String)

@Composable
private fun HourlyStrip(
    bundle: WeatherManager.WeatherBundle,
    language: String,
    modifier: Modifier = Modifier
) {
    val entries = remember(bundle) {
        val list = mutableListOf<HourEntry>()
        bundle.hourly.forEachIndexed { dayIndex, day ->
            day.hours.forEach { h -> list.add(HourEntry(dayIndex, h.hour, h.temp, h.rain, h.icon)) }
        }
        list
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardBg)
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = if (language == LanguageManager.EN) "Hourly" else "Óránkénti",
            color = TextDark,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
        ) {
            items(entries) { entry ->
                Column(
                    modifier = Modifier.width(58.dp).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = hourLabel(entry, language),
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(text = weatherEmoji(entry.icon), fontSize = 22.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = formatTemp(entry.temp),
                        color = TextDark,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (entry.rain > 0) "${formatNum(entry.rain)}" else " ",
                        color = Color(0xFF4A90D9),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyPanel(
    bundle: WeatherManager.WeatherBundle,
    language: String,
    modifier: Modifier = Modifier
) {
    val week = bundle.week
    val weekMin = week.minOfOrNull { it.minTemp } ?: 0.0
    val weekMax = week.maxOfOrNull { it.maxTemp } ?: 1.0

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(CardBg)
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = if (language == LanguageManager.EN) "7-day forecast" else "7 napos előrejelzés",
            color = TextDark,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
        )
        if (week.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (language == LanguageManager.EN) "No data" else "Nincs adat",
                    color = TextMuted
                )
            }
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            week.forEach { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dayLabel(day.dayAfter, language),
                        color = TextDark,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.width(74.dp)
                    )
                    Text(text = weatherEmoji(day.icon), fontSize = 22.sp, modifier = Modifier.width(34.dp))
                    Text(
                        text = formatTemp(day.minTemp),
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(38.dp)
                    )
                    TempRangeBar(
                        min = day.minTemp,
                        max = day.maxTemp,
                        weekMin = weekMin,
                        weekMax = weekMax,
                        modifier = Modifier.weight(1f).height(8.dp).padding(horizontal = 8.dp)
                    )
                    Text(
                        text = formatTemp(day.maxTemp),
                        color = TextDark,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
        }
    }
}

/** Apple-Weather-style range bar comparing each day against the week's min/max. */
@Composable
private fun TempRangeBar(
    min: Double,
    max: Double,
    weekMin: Double,
    weekMax: Double,
    modifier: Modifier = Modifier
) {
    val span = (weekMax - weekMin).takeIf { it > 0.0 } ?: 1.0
    val startFraction = ((min - weekMin) / span).toFloat().coerceIn(0f, 1f)
    val endFraction = ((max - weekMin) / span).toFloat().coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(
            color = Color(0xFFE6EAF0),
            cornerRadius = CornerRadius(radius, radius),
            size = Size(size.width, size.height)
        )
        val startX = startFraction * size.width
        val endX = endFraction * size.width
        val width = (endX - startX).coerceAtLeast(radius * 2f)
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF4A90D9), Color(0xFFF6A623)),
                startX = startX,
                endX = startX + width
            ),
            topLeft = Offset(startX, 0f),
            cornerRadius = CornerRadius(radius, radius),
            size = Size(width, size.height)
        )
    }
}

// ---------------------------------------------------------------------------
// Portrait / narrow fallback (stacked, scrollable)
// ---------------------------------------------------------------------------

@Composable
private fun PortraitContent(bundle: WeatherManager.WeatherBundle, language: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CurrentHero(bundle = bundle, language = language, modifier = Modifier.fillMaxWidth())
        HourlyStrip(bundle = bundle, language = language, modifier = Modifier.fillMaxWidth())
        WeeklyPanel(bundle = bundle, language = language, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TextOnGlass)
    }
}

@Composable
private fun MessageState(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = TextOnGlass,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private fun formatTemp(value: Double?): String {
    if (value == null || value.isNaN()) return "—"
    return "${Math.round(value)}°"
}

private fun formatNum(value: Double): String =
    if (value == Math.floor(value)) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun hourLabel(entry: HourEntry, language: String): String = when {
    entry.dayOffset == 0 -> "${entry.hour}:00"
    language == LanguageManager.EN -> "tom ${entry.hour}h"
    else -> "holnap ${entry.hour}h"
}

private fun updatedLabel(bundle: WeatherManager.WeatherBundle, language: String): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(bundle.fetchedAt))
    return if (language == LanguageManager.EN) "Updated $time" else "Frissítve: $time"
}

private fun dayLabel(dayAfter: Int, language: String): String {
    if (dayAfter == 0) return if (language == LanguageManager.EN) "Today" else "Ma"
    if (dayAfter == 1) return if (language == LanguageManager.EN) "Tomorrow" else "Holnap"
    val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayAfter) }
    val locale = if (language == LanguageManager.EN) Locale.ENGLISH else Locale.forLanguageTag("hu")
    return SimpleDateFormat("EEE", locale).format(cal.time)
}

/** Condition-driven ambient background (common in modern weather dashboards). */
private fun weatherBackground(skyIcon: String): List<Color> {
    val i = skyIcon.lowercase()
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val isNight = hour < 6 || hour >= 21
    return when {
        i.contains("zivatar") || i.contains("vihar") -> listOf(Color(0xFF2B3A4A), Color(0xFF4C5D6E))
        i.contains("hav") || i.contains("ho") -> listOf(Color(0xFF5C6B7A), Color(0xFF9DAEBC))
        i.contains("eso") || i.contains("eső") || i.contains("zapor") || i.contains("szital") || i.contains("szitál") ->
            listOf(Color(0xFF3B4A5A), Color(0xFF6B7C8C))
        i.contains("borult") || i.contains("felhos") || i.contains("felhős") ->
            listOf(Color(0xFF4E6273), Color(0xFF8296A6))
        isNight -> listOf(Color(0xFF0E1B33), Color(0xFF28405F))
        else -> listOf(Color(0xFF2E6FB7), Color(0xFF6FA8DC))
    }
}

/** Maps a Köpönyeg `skyIcon`/`icon` value to an emoji. */
private fun weatherEmoji(icon: String): String {
    val i = icon.lowercase()
    return when {
        i.contains("zivatar") || i.contains("vihar") -> "⛈️"
        i.contains("hav") || i.contains("ho") -> "🌨️"
        i.contains("zapor") -> "🌦️"
        i.contains("eso") || i.contains("eső") -> "🌧️"
        i.contains("szital") || i.contains("szitál") -> "🌦️"
        i.contains("kodos") || i.contains("köd") -> "🌫️"
        i.contains("borult") -> "☁️"
        i.contains("valtozo") || i.contains("változó") -> "🌤️"
        i.contains("felhos") || i.contains("felhős") -> "⛅"
        i.contains("derult") || i.contains("derült") -> "☀️"
        else -> "🌡️"
    }
}

/** Localized human label for a Köpönyeg `skyIcon` value. */
private fun weatherLabel(icon: String, language: String): String {
    val i = icon.lowercase()
    val hu = when {
        i.contains("zivatar") || i.contains("vihar") -> "Zivatar"
        i.contains("hav") || i.contains("ho") -> "Havazás"
        i.contains("zapor") -> "Zápor"
        i.contains("eso") || i.contains("eső") -> "Eső"
        i.contains("szital") || i.contains("szitál") -> "Szitálás"
        i.contains("kodos") || i.contains("köd") -> "Köd"
        i.contains("borult") -> "Borult"
        i.contains("valtozo") || i.contains("változó") -> "Változóan felhős"
        i.contains("felhos") || i.contains("felhős") -> "Felhős"
        i.contains("derult") || i.contains("derült") -> "Derült"
        else -> "—"
    }
    if (language != LanguageManager.EN) return hu
    return when (hu) {
        "Zivatar" -> "Thunderstorm"
        "Havazás" -> "Snow"
        "Zápor" -> "Shower"
        "Eső" -> "Rain"
        "Szitálás" -> "Drizzle"
        "Köd" -> "Fog"
        "Borult" -> "Overcast"
        "Változóan felhős" -> "Partly cloudy"
        "Felhős" -> "Cloudy"
        "Derült" -> "Clear"
        else -> "—"
    }
}
