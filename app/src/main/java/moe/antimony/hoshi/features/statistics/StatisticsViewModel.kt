package moe.antimony.hoshi.features.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.DefaultDispatcher
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sync.SyncReloadScheduler
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.diagnosticMessage
import moe.antimony.hoshi.ui.withErrorDetails

@HiltViewModel
internal class StatisticsViewModel internal constructor(
    private val repository: StatisticsRepository,
    private val settings: Flow<StatisticsTargetSettings>,
    private val updateSettings: suspend ((StatisticsTargetSettings) -> StatisticsTargetSettings) -> Unit,
    private val resetMinutes: Flow<Int>,
    private val dateProvider: StatisticsDateProvider,
    private val calculationDispatcher: CoroutineDispatcher,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        repository: StatisticsRepository,
        settingsRepository: StatisticsSettingsRepository,
        readerSettingsRepository: ReaderSettingsRepository,
        dateProvider: StatisticsDateProvider,
        @DefaultDispatcher calculationDispatcher: CoroutineDispatcher,
        statisticsStore: moe.antimony.hoshi.epub.BookStatisticsStore,
        syncStorage: moe.antimony.hoshi.features.sync.SyncStorage,
        lookupStatisticsRepository: LookupStatisticsRepository,
    ) : this(
        repository = repository,
        settings = settingsRepository.settings,
        updateSettings = settingsRepository::update,
        resetMinutes = readerSettingsRepository.statisticsResetMinutes,
        dateProvider = dateProvider,
        calculationDispatcher = calculationDispatcher,
        coroutineScope = null,
    ) {
        val syncReload = SyncReloadScheduler(scope, { syncStorage.applyReaderState != null }, ::reload)
        scope.launch {
            combine(statisticsStore.changes, syncStorage.booksChanged, lookupStatisticsRepository.changes, resetMinutes) { _, _, _, _ -> Unit }
                .collect { syncReload.schedule() }
        }
    }

    private val scope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    private val snapshot = MutableStateFlow(StatisticsSnapshot(days = emptyList(), availableYears = emptyList()))
    private val selection = MutableStateFlow(StatisticsSelectionState(today = dateProvider.currentDate(0)))
    private val isLoading = MutableStateFlow(true)
    private val _uiState = MutableStateFlow(
        buildStatisticsUiState(
            snapshot = snapshot.value,
            settings = StatisticsTargetSettings(),
            selection = selection.value,
            isLoading = true,
        ),
    )
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var reloadJob: Job? = null
    private var reloadGeneration = 0
    private var observationJob: Job? = null
    private val loadError = MutableStateFlow<UiText?>(null)

    init {
        observeSettings()
    }

    private fun observeSettings() {
        observationJob = scope.launch {
            try {
                coroutineScope {
                    launch { resetMinutes.collect { refreshCalendar(it) } }
                    val confirmedSelection = selection.filter { it.resetMinutes != null }
                    combine(snapshot, settings, confirmedSelection, isLoading) { snapshot, settings, selection, isLoading ->
                        withContext(calculationDispatcher) {
                            buildStatisticsUiState(
                                snapshot = snapshot,
                                settings = settings,
                                selection = selection,
                                isLoading = isLoading,
                            )
                        }
                    }.combine(loadError) { state, error -> state.copy(loadError = error) }.collect { state ->
                        _uiState.value = state
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLoadError(UiText.Resource(R.string.settings_load_failed).withErrorDetails(error))
            }
        }
    }

    private fun showLoadError(message: UiText) {
        loadError.value = message
        _uiState.update { it.copy(loadError = message, isLoading = false) }
    }

    fun dismissLoadError() {
        loadError.value = null
        _uiState.update { it.copy(loadError = null) }
    }

    fun reload() {
        dismissLoadError()
        refreshCalendar()
        if (observationJob?.isActive != true) observeSettings()
        val generation = ++reloadGeneration
        reloadJob?.cancel()
        reloadJob = scope.launch {
            isLoading.value = true
            try {
                val loaded = repository.loadSnapshot()
                if (generation == reloadGeneration) {
                    snapshot.value = loaded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == reloadGeneration) {
                    showLoadError(UiText.Resource(R.string.statistics_operation_failed).withErrorDetails(error))
                }
            } finally {
                if (generation == reloadGeneration) {
                    isLoading.value = false
                }
            }
        }
    }

    fun onEvent(event: StatisticsEvent) {
        when (event) {
            is StatisticsEvent.SelectMetricMode -> selection.update { current ->
                current.copy(
                    metric = event.mode,
                    referenceDate = null,
                    selectedBucketKey = null,
                    isTargetEditorVisible = false,
                    isLookupTargetEditorVisible = false,
                )
            }
            StatisticsEvent.OpenTargetSettings -> selection.update { it.copy(isTargetEditorVisible = true, targetError = null) }
            StatisticsEvent.DismissTargetSettings -> selection.update { it.copy(isTargetEditorVisible = false, targetError = null) }
            is StatisticsEvent.SelectDailyTargetType -> updateTargets {
                it.copy(dailyTargetType = event.type)
            }
            is StatisticsEvent.UpdateDailyCharacterTarget -> updateTargets {
                it.copy(dailyCharacterTarget = event.characters).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.UpdateDailyDurationTargetMinutes -> updateTargets {
                it.copy(dailyDurationTargetMinutes = event.minutes).coerceStatisticsTargetSettings()
            }
            StatisticsEvent.OpenLookupTargetSettings -> selection.update {
                it.copy(isLookupTargetEditorVisible = true, lookupTargetError = null)
            }
            StatisticsEvent.DismissLookupTargetSettings -> selection.update {
                it.copy(isLookupTargetEditorVisible = false, lookupTargetError = null)
            }
            is StatisticsEvent.SelectLookupTargetType -> updateLookupTargets {
                it.copy(dailyLookupTargetType = event.type)
            }
            is StatisticsEvent.UpdateDailyLookupCountTarget -> updateLookupTargets {
                it.copy(dailyLookupCountTarget = event.count).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.UpdateDailyLookupDurationTargetMinutes -> updateLookupTargets {
                it.copy(dailyLookupDurationTargetMinutes = event.minutes).coerceStatisticsTargetSettings()
            }
            is StatisticsEvent.SelectRangeMode -> selection.update { current ->
                current.copy(rangeMode = event.mode, referenceDate = null, selectedBucketKey = null)
            }
            is StatisticsEvent.SelectPeriodPage -> selection.update { current ->
                val displayed = uiState.value
                val range = when (current.metric) {
                    StatisticsMetricMode.Reading -> displayed.currentRange
                    StatisticsMetricMode.Lookup -> displayed.lookupRange
                }
                if (range.mode != current.rangeMode || event.index !in 0 until range.pageCount) {
                    current
                } else {
                    val referenceDate = if (event.index == range.pageCount - 1) null else {
                        statisticsPeriodPageAnchor(range.mode, event.index, range.pageCount, displayed.today.date)
                    }
                    current.copy(referenceDate = referenceDate, selectedBucketKey = null)
                }
            }
            is StatisticsEvent.SelectTrendBucket -> selection.update { current ->
                val displayed = uiState.value
                val range = when (current.metric) {
                    StatisticsMetricMode.Reading -> displayed.currentRange
                    StatisticsMetricMode.Lookup -> displayed.lookupRange
                }
                val valid = event.key == null || range.trendPoints.any {
                    it.key == event.key && statisticsTrendBucket(current.rangeMode, it.key)?.start?.isAfter(displayed.today.date) == false
                }
                if (valid) current.copy(
                    selectedBucketKey = event.key.takeUnless { it == current.selectedBucketKey },
                ) else current
            }
        }
    }

    private fun refreshCalendar(resetMinutes: Int? = null) {
        selection.update { current ->
            val minutes = resetMinutes ?: current.resetMinutes
            val today = dateProvider.currentDate(minutes ?: 0)
            val previousAnchor = current.referenceDate ?: current.today
            val resetChanged = minutes != current.resetMinutes
            val currentPeriodRolled = today != current.today &&
                selectedStatisticsRange(current.rangeMode, previousAnchor, current.today).contains(current.today)
            val followToday = resetChanged || currentPeriodRolled
            current.copy(
                today = today,
                resetMinutes = minutes,
                referenceDate = if (followToday) null else current.referenceDate,
                selectedBucketKey = if (followToday && previousAnchor != today) null else current.selectedBucketKey,
            )
        }
    }

    private fun updateTargets(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
        scope.launch {
            try {
                updateSettings { current -> transform(current).coerceStatisticsTargetSettings() }
                selection.update { it.copy(targetError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                selection.update { it.copy(targetError = UiText.Resource(R.string.statistics_goal_save_failed).withErrorDetails(error)) }
            }
        }
    }

    private fun updateLookupTargets(transform: (StatisticsTargetSettings) -> StatisticsTargetSettings) {
        scope.launch {
            try {
                updateSettings { current -> transform(current).coerceStatisticsTargetSettings() }
                selection.update { it.copy(lookupTargetError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                selection.update {
                    it.copy(lookupTargetError = UiText.Resource(R.string.statistics_goal_save_failed).withErrorDetails(error))
                }
            }
        }
    }
}

private data class StatisticsSelectionState(
    val today: LocalDate,
    val resetMinutes: Int? = null,
    val metric: StatisticsMetricMode = StatisticsMetricMode.Reading,
    val rangeMode: StatisticsRangeMode = StatisticsRangeMode.Week,
    val referenceDate: LocalDate? = null,
    val selectedBucketKey: String? = null,
    val isTargetEditorVisible: Boolean = false,
    val targetError: UiText? = null,
    val isLookupTargetEditorVisible: Boolean = false,
    val lookupTargetError: UiText? = null,
)

private fun buildStatisticsUiState(
    snapshot: StatisticsSnapshot,
    settings: StatisticsTargetSettings,
    selection: StatisticsSelectionState,
    isLoading: Boolean,
): StatisticsUiState {
    val today = selection.today
    val daysByDate = snapshot.days.associateBy { it.date }
    val rangeMode = selection.rangeMode
    val activity = snapshot.days.filter { it.isActiveReadingDay() && !it.date.isAfter(today) }
    val firstDate = activity.minOfOrNull { it.date } ?: today
    val earliestPeriod = selectedStatisticsRange(rangeMode, firstDate, today, firstDate).start
    val anchor = minOf(selection.referenceDate ?: today, today).coerceAtLeast(earliestPeriod)
    val selectedRange = selectedStatisticsRange(rangeMode, anchor, today, firstDate)
    val pageCount = statisticsPeriodPageCount(rangeMode, firstDate, today)
    val selectedPage = (pageCount - statisticsPeriodPageCount(rangeMode, anchor, today)).coerceIn(0, pageCount - 1)
    val heatLevels = readingHeatLevels(activity)
    val rangeDays = activity.filter { selectedRange.contains(it.date) }
    val rangeSummary = overviewRangeSummary(snapshot.days, settings, rangeMode, anchor, today)
    val trendPoints = trendPoints(rangeMode, selectedRange, rangeDays)
    val selectedBucket = selection.selectedBucketKey?.let { statisticsTrendBucket(rangeMode, it) }
        ?.takeIf { !it.end.isBefore(selectedRange.start) && !it.start.isAfter(selectedRange.end) && !it.start.isAfter(today) }
    val detailDays = selectedBucket?.let { bucket -> rangeDays.filter { bucket.contains(it.date) } } ?: rangeDays
    val distributionRows = distributionRows(detailDays)

    val lookupDaysByDate = snapshot.lookupDays.associateBy { it.date }
    val lookupActivity = snapshot.lookupDays.filter { it.isActive && !it.date.isAfter(today) }
    val lookupFirstDate = lookupActivity.minOfOrNull { it.date } ?: today
    val lookupEarliestPeriod = selectedStatisticsRange(rangeMode, lookupFirstDate, today, lookupFirstDate).start
    val lookupAnchor = minOf(selection.referenceDate ?: today, today).coerceAtLeast(lookupEarliestPeriod)
    val lookupSelectedRange = selectedStatisticsRange(rangeMode, lookupAnchor, today, lookupFirstDate)
    val lookupPageCount = statisticsPeriodPageCount(rangeMode, lookupFirstDate, today)
    val lookupSelectedPage = (lookupPageCount - statisticsPeriodPageCount(rangeMode, lookupAnchor, today))
        .coerceIn(0, lookupPageCount - 1)
    val lookupRangeDays = lookupActivity.filter { lookupSelectedRange.contains(it.date) }
    val lookupRangeSummary = overviewLookupRangeSummary(snapshot.lookupDays, rangeMode, lookupAnchor, today)
    val lookupTrend = lookupTrendPoints(rangeMode, lookupSelectedRange, lookupRangeDays)
    val lookupHeatLevels = lookupHeatLevels(lookupActivity)
    val lookupSelectedBucket = selection.selectedBucketKey?.let { statisticsTrendBucket(rangeMode, it) }
        ?.takeIf {
            !it.end.isBefore(lookupSelectedRange.start) &&
                !it.start.isAfter(lookupSelectedRange.end) &&
                !it.start.isAfter(today)
        }
    val lookupDetailDays = lookupSelectedBucket?.let { bucket ->
        lookupRangeDays.filter { bucket.contains(it.date) }
    } ?: lookupRangeDays
    return StatisticsUiState(
        isLoading = isLoading,
        today = todaySummary(daysByDate, today, settings),
        settings = StatisticsTargetSettingsUi(
            values = settings.coerceStatisticsTargetSettings(),
            isEditorVisible = selection.isTargetEditorVisible,
            error = selection.targetError,
        ),
        heatmap = StatisticsHeatmapUi(
            windowRange = StatisticsDateRange(statisticsStartOfWeek(firstDate), today),
            days = activity.map { StatisticsHeatmapDayUi(it.date, heatLevels[it.date] ?: 0) },
        ),
        currentRange = CurrentRangeStatisticsUi(
            mode = rangeMode,
            range = selectedRange,
            pageCount = pageCount,
            selectedPage = selectedPage,
            chartPages = (maxOf(0, selectedPage - 1)..minOf(pageCount - 1, selectedPage + 1)).associateWith { page ->
                val pageAnchor = statisticsPeriodPageAnchor(rangeMode, page, pageCount, today)
                val pageRange = selectedStatisticsRange(rangeMode, pageAnchor, today, firstDate)
                StatisticsChartPage(
                    points = trendPoints(rangeMode, pageRange, activity.filter { pageRange.contains(it.date) }),
                    averageSeconds = overviewRangeSummary(activity, settings, rangeMode, pageAnchor, today).averageReadingSecondsPerBucket,
                )
            },
            summary = if (selectedBucket != null) aggregateRange(detailDays, settings) else rangeSummary,
            selectedBucket = selectedBucket,
            trendAverageSeconds = rangeSummary.averageReadingSecondsPerBucket,
            periodChangePercent = rangeSummary.averageReadingTimeChangePercent,
            trendPoints = trendPoints,
            distributionRows = distributionRows,
        ),
        metric = selection.metric,
        lookupToday = todayLookupSummary(lookupDaysByDate, today),
        lookupSettings = StatisticsTargetSettingsUi(
            values = settings.coerceStatisticsTargetSettings(),
            isEditorVisible = selection.isLookupTargetEditorVisible,
            error = selection.lookupTargetError,
        ),
        lookupHeatmap = StatisticsHeatmapUi(
            windowRange = StatisticsDateRange(statisticsStartOfWeek(lookupFirstDate), today),
            days = lookupActivity.map { StatisticsHeatmapDayUi(it.date, lookupHeatLevels[it.date] ?: 0) },
        ),
        lookupRange = CurrentLookupStatisticsUi(
            mode = rangeMode,
            range = lookupSelectedRange,
            pageCount = lookupPageCount,
            selectedPage = lookupSelectedPage,
            chartPages = (maxOf(0, lookupSelectedPage - 1)..minOf(lookupPageCount - 1, lookupSelectedPage + 1)).associateWith { page ->
                val pageAnchor = statisticsPeriodPageAnchor(rangeMode, page, lookupPageCount, today)
                val pageRange = selectedStatisticsRange(rangeMode, pageAnchor, today, lookupFirstDate)
                StatisticsChartPage(
                    points = lookupTrendPoints(rangeMode, pageRange, lookupActivity.filter { pageRange.contains(it.date) }),
                    averageSeconds = overviewLookupRangeSummary(lookupActivity, rangeMode, pageAnchor, today)
                        .averageLookupSecondsPerBucket,
                )
            },
            summary = if (lookupSelectedBucket != null) aggregateLookupRange(lookupDetailDays) else lookupRangeSummary,
            selectedBucket = lookupSelectedBucket,
            trendAverageSeconds = lookupRangeSummary.averageLookupSecondsPerBucket,
            periodChangePercent = lookupRangeSummary.averageLookupTimeChangePercent,
            trendPoints = lookupTrend,
        ),
        history = statisticsHistorySummary(snapshot.days, today, settings),
        lookupHistory = lookupHistorySummary(snapshot.lookupDays, today, settings),
        emptyState = StatisticsEmptyState(
            hasAnyStatistics = snapshot.days.isNotEmpty() || snapshot.lookupDays.isNotEmpty(),
            hasPartialReadError = snapshot.skippedCorruptBookIds.isNotEmpty(),
            readError = snapshot.readErrors.takeIf { it.isNotEmpty() }?.let { errors ->
                UiText.Joined(listOf(UiText.Resource(R.string.statistics_partial_unavailable)) + errors.map { (id, error) ->
                    ErrorDiagnosticEvents.buffer.record(
                        UiText.Joined(listOf(UiText.Resource(R.string.statistics_partial_unavailable), UiText.Literal(id))),
                        error,
                    )
                    UiText.Literal("$id: ${error.diagnosticMessage()}")
                })
            },
        ),
    )
}
