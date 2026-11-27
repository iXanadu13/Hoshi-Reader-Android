package moe.antimony.hoshi.features.statistics

import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.writeBookJson
import moe.antimony.hoshi.features.sync.SyncBook
import moe.antimony.hoshi.features.sync.Timestamped

@Serializable
data class LookupStatisticsSession(
    val startedAt: Long,
    val endedAt: Long,
    val lookupCount: Int = 0,
)

typealias LookupStatisticsSessions = Map<String, Timestamped<LookupStatisticsSession?>>

@Singleton
class LookupStatisticsStore @Inject constructor(
    @FilesDir private val filesDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()
    private val revision = MutableStateFlow(0)

    val changes = revision.asStateFlow()
    var onChange: (() -> Unit)? = null

    suspend fun loadSessions(): LookupStatisticsSessions = locked { readSessions() }

    suspend fun hasData(): Boolean = locked { readSessions().values.any { it.value != null } }

    suspend fun append(session: LookupStatisticsSession) {
        locked {
            val sessions = readSessions() + (
                UUID.randomUUID().toString() to Timestamped<LookupStatisticsSession?>(System.currentTimeMillis(), session)
                )
            writeSessions(sessions)
        }
        notifyChanged()
    }

    suspend fun mergeSessions(incoming: LookupStatisticsSessions) {
        if (incoming.isEmpty()) return
        val changed = locked {
            val current = readSessions()
            val merged = SyncBook.mergeRecords(current, incoming)
            if (merged == current) false else {
                writeSessions(merged)
                true
            }
        }
        if (changed) notifyChanged()
    }

    /** A dictionary restore replaces the file outside this store's write path. */
    fun notifyChanged() {
        revision.update { it + 1 }
        onChange?.invoke()
    }

    private suspend fun <T> locked(action: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock { action() }
    }

    private suspend fun readSessions(): LookupStatisticsSessions {
        val file = statisticsFile
        return if (file.isFile) decode(file.readText()) else emptyMap()
    }

    private fun writeSessions(sessions: LookupStatisticsSessions) {
        writeBookJson(statisticsFile, encode(sessions))
    }

    private val statisticsFile: File
        get() = filesDir.resolve("Dictionaries/statistics.json")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        private val serializer = MapSerializer(
            String.serializer(),
            Timestamped.serializer(LookupStatisticsSession.serializer().nullable),
        )

        internal fun encode(sessions: LookupStatisticsSessions): String = json.encodeToString(serializer, sessions)

        internal fun decode(contents: String): LookupStatisticsSessions = json.decodeFromString(serializer, contents)
    }

}
