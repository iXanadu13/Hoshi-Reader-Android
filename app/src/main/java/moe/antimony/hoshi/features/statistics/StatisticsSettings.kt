package moe.antimony.hoshi.features.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class StatisticsSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<StatisticsTargetSettings> =
        dataStore.data.map { preferences -> preferences.toStatisticsTargetSettings() }

    suspend fun update(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toStatisticsTargetSettings()).coerceStatisticsTargetSettings()
            preferences[KEY_DAILY_TARGET_TYPE] = next.dailyTargetType.name
            preferences[KEY_DAILY_CHARACTER_TARGET] = next.dailyCharacterTarget
            preferences[KEY_DAILY_DURATION_TARGET_MINUTES] = next.dailyDurationTargetMinutes
            preferences[KEY_DAILY_LOOKUP_TARGET_TYPE] = next.dailyLookupTargetType.name
            preferences[KEY_DAILY_LOOKUP_COUNT_TARGET] = next.dailyLookupCountTarget
            preferences[KEY_DAILY_LOOKUP_DURATION_TARGET_MINUTES] = next.dailyLookupDurationTargetMinutes
        }
    }

    private fun Preferences.toStatisticsTargetSettings(): StatisticsTargetSettings =
        StatisticsTargetSettings(
            dailyTargetType = DailyTargetType.entries.firstOrNull { it.name == this[KEY_DAILY_TARGET_TYPE] }
                ?: DailyTargetType.Characters,
            dailyCharacterTarget = this[KEY_DAILY_CHARACTER_TARGET]
                ?: StatisticsTargetDefaults.DailyCharacterTarget,
            dailyDurationTargetMinutes = this[KEY_DAILY_DURATION_TARGET_MINUTES]
                ?: StatisticsTargetDefaults.DailyDurationTargetMinutes,
            dailyLookupTargetType = LookupTargetType.entries.firstOrNull { it.name == this[KEY_DAILY_LOOKUP_TARGET_TYPE] }
                ?: LookupTargetType.Count,
            dailyLookupCountTarget = this[KEY_DAILY_LOOKUP_COUNT_TARGET]
                ?: StatisticsTargetDefaults.DailyLookupCountTarget,
            dailyLookupDurationTargetMinutes = this[KEY_DAILY_LOOKUP_DURATION_TARGET_MINUTES]
                ?: StatisticsTargetDefaults.DailyLookupDurationTargetMinutes,
        ).coerceStatisticsTargetSettings()

    private companion object {
        val KEY_DAILY_TARGET_TYPE = stringPreferencesKey("statisticsDailyTargetType")
        val KEY_DAILY_CHARACTER_TARGET = intPreferencesKey("statisticsDailyCharacterTarget")
        val KEY_DAILY_DURATION_TARGET_MINUTES = intPreferencesKey("statisticsDailyDurationTargetMinutes")
        val KEY_DAILY_LOOKUP_TARGET_TYPE = stringPreferencesKey("statisticsDailyLookupTargetType")
        val KEY_DAILY_LOOKUP_COUNT_TARGET = intPreferencesKey("statisticsDailyLookupCountTarget")
        val KEY_DAILY_LOOKUP_DURATION_TARGET_MINUTES = intPreferencesKey("statisticsDailyLookupDurationTargetMinutes")
    }
}

internal fun StatisticsTargetSettings.coerceStatisticsTargetSettings(): StatisticsTargetSettings =
    copy(
        dailyCharacterTarget = dailyCharacterTarget.snapToStep(
            min = StatisticsTargetDefaults.MinDailyCharacterTarget,
            max = StatisticsTargetDefaults.MaxDailyCharacterTarget,
            step = StatisticsTargetDefaults.DailyCharacterTargetStep,
        ),
        dailyDurationTargetMinutes = dailyDurationTargetMinutes.snapToStep(
            min = StatisticsTargetDefaults.MinDailyDurationTargetMinutes,
            max = StatisticsTargetDefaults.MaxDailyDurationTargetMinutes,
            step = StatisticsTargetDefaults.DailyDurationTargetStepMinutes,
        ),
        dailyLookupCountTarget = dailyLookupCountTarget.snapToStep(
            min = StatisticsTargetDefaults.MinDailyLookupCountTarget,
            max = StatisticsTargetDefaults.MaxDailyLookupCountTarget,
            step = StatisticsTargetDefaults.DailyLookupCountTargetStep,
        ),
        dailyLookupDurationTargetMinutes = dailyLookupDurationTargetMinutes.snapToStep(
            min = StatisticsTargetDefaults.MinDailyDurationTargetMinutes,
            max = StatisticsTargetDefaults.MaxDailyDurationTargetMinutes,
            step = StatisticsTargetDefaults.DailyDurationTargetStepMinutes,
        ),
    )

private fun Int.snapToStep(min: Int, max: Int, step: Int): Int {
    val clamped = coerceIn(min, max)
    val offset = clamped - min
    val lower = min + (offset / step) * step
    val upper = (lower + step).coerceAtMost(max)
    return if (clamped - lower < upper - clamped) lower else upper
}

private val Context.statisticsSettingsDataStore by preferencesDataStore(name = "statistics-settings")

internal fun Context.statisticsSettingsRepository(): StatisticsSettingsRepository =
    StatisticsSettingsRepository(statisticsSettingsDataStore)
