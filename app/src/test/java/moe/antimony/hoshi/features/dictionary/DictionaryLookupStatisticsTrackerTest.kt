package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.features.statistics.LookupStatisticsDay
import moe.antimony.hoshi.features.statistics.LookupStatisticsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryLookupStatisticsTrackerTest {
    @Test
    fun recordsActiveDwellTimeAndExplicitSubmissions() {
        var now = 1_000L
        val sessions = mutableListOf<Triple<Long, Long, Int>>()
        val repository = object : LookupStatisticsRepository {
            override val changes = MutableStateFlow(0)
            override fun recordSession(startedAt: Long, endedAt: Long, lookupCount: Int) {
                sessions += Triple(startedAt, endedAt, lookupCount)
            }
            override suspend fun load(): List<LookupStatisticsDay> = emptyList()
        }
        val tracker = DictionaryLookupStatisticsTracker(repository) { now }

        tracker.recordLookup()
        tracker.setActive(true)
        now += 10_000
        tracker.recordLookup()
        now += 20_000
        tracker.setActive(false)
        tracker.recordLookup()

        assertEquals(listOf(Triple(1_000L, 31_000L, 1)), sessions)
    }
}
