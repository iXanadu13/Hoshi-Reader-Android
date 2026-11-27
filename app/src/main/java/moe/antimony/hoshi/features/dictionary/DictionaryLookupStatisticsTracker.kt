package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.statistics.LookupStatisticsRepository

/** Tracks explicit Dictionary-page submissions separately from the de-duplicated lookup history. */
internal class DictionaryLookupStatisticsTracker(
    private val repository: LookupStatisticsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var startedAt: Long? = null
    private var lookupCount = 0

    fun setActive(active: Boolean) {
        if (active) {
            if (startedAt == null) {
                startedAt = now()
                lookupCount = 0
            }
        } else {
            stop()
        }
    }

    fun recordLookup() {
        if (startedAt != null) lookupCount += 1
    }

    fun stop() {
        val start = startedAt ?: return
        repository.recordSession(start, maxOf(start, now()), lookupCount)
        startedAt = null
        lookupCount = 0
    }
}
