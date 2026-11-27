package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.antimony.hoshi.R

@Composable
internal fun StatisticsLookupTimeSection(
    currentRange: CurrentLookupStatisticsUi,
    today: java.time.LocalDate,
    onEvent: (StatisticsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSelectBucket: (String?) -> Unit = { onEvent(StatisticsEvent.SelectTrendBucket(it)) }
    val pager = rememberStatisticsChartPagerState(currentRange) { onEvent(StatisticsEvent.SelectPeriodPage(it)) }
    val summary = currentRange.summary
    val selectedBucket = currentRange.selectedBucket
    val showTotal = selectedBucket != null
    val rangeTitleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal)
    val rangeTitleHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        maxOf(32.dp, rangeTitleStyle.lineHeight.toDp())
    }
    StatisticsSection(
        title = stringResource(R.string.statistics_lookup_time),
        modifier = modifier,
    ) {
        StatisticsSegmentedControl(
            options = StatisticsRangeMode.entries.map { StatisticsSegmentedOption(it, rangeModeLabel(it)) },
            selected = currentRange.mode,
            onSelect = { if (it != currentRange.mode) onEvent(StatisticsEvent.SelectRangeMode(it)) },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = rangeTitleHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedBucket?.let { statisticsBucketTitle(currentRange.mode, it) } ?: stringResource(
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week, StatisticsRangeMode.Month -> R.string.statistics_period_daily_average_format
                        StatisticsRangeMode.Year, StatisticsRangeMode.All -> R.string.statistics_period_monthly_average_format
                    },
                    statisticsCompactRangeTitle(currentRange.mode, currentRange.range, today),
                ),
                modifier = Modifier.weight(1f),
                style = rangeTitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 20.sp, stepSize = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentRange.mode != StatisticsRangeMode.All) {
                StatisticsPeriodNavigation(pager, currentRange.selectedPage)
            }
        }
        Spacer(Modifier.height(4.dp))
        val change = currentRange.periodChangePercent?.takeIf { !showTotal }
        StatisticsReadingTimeHeadline(
            duration = formatStatisticsDuration(
                if (showTotal) summary.lookupSeconds else summary.averageLookupSecondsPerBucket,
            ),
            change = change,
            comparison = change?.let {
                stringResource(
                    when (currentRange.mode) {
                        StatisticsRangeMode.Week -> R.string.statistics_average_change_week_format
                        StatisticsRangeMode.Month -> R.string.statistics_average_change_month_format
                        else -> R.string.statistics_average_change_year_format
                    },
                    formatStatisticsGroupedCount(kotlin.math.abs(it).roundToInt()),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        StatisticsChartPager(
            range = currentRange,
            pager = pager,
            today = today,
            onSelectBucket = onSelectBucket,
        )
        Spacer(Modifier.height(12.dp))
        StatisticsSummaryRow(
            stringResource(R.string.statistics_lookup_count),
            formatStatisticsGroupedCount(summary.totalLookups),
        )
        if (!showTotal) {
            StatisticsSummaryRow(
                stringResource(R.string.statistics_lookup_time),
                formatStatisticsDuration(summary.lookupSeconds),
            )
        }
    }
}
