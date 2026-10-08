package app.locomate.data

import android.content.Context
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

enum class AppAppearance { System, Light, Dark }
enum class MapLighting { Auto, Day, Night }
enum class MapImagery { Standard, Satellite }
/** Dark by default, matching iOS: the night map and glass sheets are the signature look. */
data class AppearanceSettings(val app: AppAppearance = AppAppearance.Dark,
                              val lighting: MapLighting = MapLighting.Auto,
                              val imagery: MapImagery = MapImagery.Standard,
                              val rotation: Boolean = true)

class AppearanceStore(context: Context) {
    internal val preferences = context.applicationContext.getSharedPreferences("locomate.appearance", Context.MODE_PRIVATE)
    fun load() = AppearanceSettings(
        enumValue(preferences.getString("app", null), AppAppearance.Dark),
        enumValue(preferences.getString("lighting", null), MapLighting.Auto),
        enumValue(preferences.getString("imagery", null), MapImagery.Standard),
        preferences.getBoolean("rotation", true))
    fun save(value: AppearanceSettings) {
        preferences.edit().putString("app", value.app.name).putString("lighting", value.lighting.name)
            .putString("imagery", value.imagery.name).putBoolean("rotation", value.rotation).apply()
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

/** Approximate solar elevation at the visible map center, including polar day/night. No location permission. */
object MapDaylight {
    fun isDay(instant: Instant, latitude: Double, longitude: Double): Boolean {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val utc = instant.atZone(ZoneOffset.UTC)
        val hour = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val yearDays = if (utc.toLocalDate().isLeapYear) 366 else 365
        val gamma = 2 * PI / yearDays * (utc.dayOfYear - 1 + (hour - 12) / 24)
        val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma)
            - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val declination = (0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma)
            - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma)
            - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma))
        val solarMinute = (hour * 60 + equation + 4 * longitude) % 1440
        val angle = Math.toRadians(solarMinute / 4 - 180)
        val lat = Math.toRadians(latitude)
        val elevation = asin((sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(angle)).coerceIn(-1.0, 1.0))
        return Math.toDegrees(elevation) >= -0.833
    }
}
