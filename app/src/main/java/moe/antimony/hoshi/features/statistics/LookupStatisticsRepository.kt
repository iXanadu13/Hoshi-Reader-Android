package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.StatisticsDay
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

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

internal class AndroidLookupStatisticsRepository(
    private val store: LookupStatisticsStore,
    applicationScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val readerSettingsRepository: ReaderSettingsRepository,
) : LookupStatisticsRepository {
    override val changes: StateFlow<Int> = store.changes
    private val commands = Channel<LookupStatisticsSession>(Channel.UNLIMITED)

    init {
        applicationScope.launch(ioDispatcher) {
            for (session in commands) {
                try {
                    store.append(session)
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
        commands.trySend(LookupStatisticsSession(startedAt, normalizedEnd, lookupCount.coerceAtLeast(0)))
    }

    override suspend fun load(): List<LookupStatisticsDay> {
        val resetMinutes = readerSettingsRepository.statisticsResetMinutes.first()
        val sessions = store.loadSessions().values.mapNotNull { it.value }
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

}
