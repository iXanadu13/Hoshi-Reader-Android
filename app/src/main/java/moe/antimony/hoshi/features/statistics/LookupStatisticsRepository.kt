package moe.antimony.hoshi.features.statistics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.StatisticsDay
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

private val Context.lookupStatisticsDataStore by preferencesDataStore(name = "dictionary-lookup-statistics")

internal fun Context.lookupStatisticsRepository(
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    readerSettingsRepository: ReaderSettingsRepository,
): LookupStatisticsRepository = AndroidLookupStatisticsRepository(
    dataStore = lookupStatisticsDataStore,
    applicationScope = applicationScope,
    ioDispatcher = ioDispatcher,
    readerSettingsRepository = readerSettingsRepository,
)

internal data class LookupStatisticsDay(
    val date: LocalDate,
    val lookupCount: Int,
    val lookupSeconds: Double,
) {
    val isActive: Boolean get() = lookupCount > 0 || lookupSeconds > 0.0
}

internal interface LookupStatisticsRepository {
    val changes: StateFlow<Int>
    fun recordSession(startedAt: Long, endedAt: Long, lookupCount: Int)
    suspend fun load(): List<LookupStatisticsDay>
}

internal object EmptyLookupStatisticsRepository : LookupStatisticsRepository {
    override val changes: StateFlow<Int> = MutableStateFlow(0)
    override fun recordSession(startedAt: Long, endedAt: Long, lookupCount: Int) = Unit
    override suspend fun load(): List<LookupStatisticsDay> = emptyList()
}

@Serializable
private data class PersistedLookupSession(
    val startedAt: Long,
    val endedAt: Long,
    val lookupCount: Int,
)

internal class AndroidLookupStatisticsRepository(
    private val dataStore: DataStore<Preferences>,
    applicationScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val readerSettingsRepository: ReaderSettingsRepository,
) : LookupStatisticsRepository {
    private val mutableChanges = MutableStateFlow(0)
    override val changes: StateFlow<Int> = mutableChanges.asStateFlow()
    private val commands = Channel<PersistedLookupSession>(Channel.UNLIMITED)

    init {
        applicationScope.launch(ioDispatcher) {
            for (session in commands) {
                try {
                    dataStore.edit { preferences ->
                        val sessions = readSessions(preferences)
                        preferences[SessionsKey] = Json.encodeToString(sessions + session)
                    }
                    mutableChanges.update { it + 1 }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.statistics_operation_failed), error)
                }
            }
        }
    }

    override fun recordSession(startedAt: Long, endedAt: Long, lookupCount: Int) {
        val normalizedEnd = maxOf(startedAt, endedAt)
        if (normalizedEnd <= startedAt && lookupCount <= 0) return
        commands.trySend(PersistedLookupSession(startedAt, normalizedEnd, lookupCount.coerceAtLeast(0)))
    }

    override suspend fun load(): List<LookupStatisticsDay> {
        val resetMinutes = readerSettingsRepository.statisticsResetMinutes.first()
        val sessions = readSessions(dataStore.data.first())
        return sessions.groupBy { StatisticsDay.date(it.startedAt, resetMinutes) }
            .toSortedMap()
            .map { (date, grouped) ->
                LookupStatisticsDay(
                    date = date,
                    lookupCount = grouped.sumOf { it.lookupCount },
                    lookupSeconds = grouped.sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0L) / 1_000.0 },
                )
            }
    }

    private fun readSessions(preferences: Preferences): List<PersistedLookupSession> =
        preferences[SessionsKey]?.let { Json.decodeFromString<List<PersistedLookupSession>>(it) }.orEmpty()

    private companion object {
        val SessionsKey = stringPreferencesKey("sessions")
    }
}
