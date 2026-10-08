package app.locomate.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.locomate.data.AppearanceSettings
import app.locomate.data.AppearanceStore

internal val LocalDarkTheme = staticCompositionLocalOf { true }
internal val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

@Composable internal fun rememberAppearance(): Pair<AppearanceSettings, AppearanceStore> {
    val context = LocalContext.current
    val store = remember(context) { AppearanceStore(context) }
    var value by remember(store) { mutableStateOf(store.load()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = store.load() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value to store
}
